/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License
 */
package com.carddemo.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.callback.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import com.carddemo.service.SensitiveFieldEncryptionService;

/**
 * The single enforcement point for the two guarantees the module's schema evolution rests on: that a
 * production deployment can never reach the seed migrations, and that no seeded regulated identifier
 * is left at rest in cleartext.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>The migrations all ship from one directory, exactly as the plan's file tree lists them:
 * {@code classpath:db/migration} carries {@code V1__create_schema.sql},
 * {@code V1_1__create_batch_metadata.sql}, {@code V2__create_indexes.sql},
 * {@code V3__seed_reference_data.sql} and {@code V4__seed_user_security.sql}. What separates the
 * schema from the seeds is therefore not a directory but a <strong>version ceiling</strong>:
 * {@code spring.flyway.target} is {@value #SCHEMA_ONLY_TARGET} in the shared baseline and again in
 * the production overlay, and only the local and test overlays lift it to
 * {@value #SEEDING_TARGET}. The sign-on seed inserts ten known identities, five of them
 * administrative, whose stored credentials are all digests of one well-known value; the reference
 * seed inserts fifty synthetic customer rows carrying regulated identity data. Neither belongs in a
 * production database, and neither arriving there would be untidiness - it would be a credential
 * incident and a privacy incident respectively.
 *
 * <p>A ceiling declared in three profile documents is a statement, and the documents were the whole
 * of the mechanism until this class existed. <strong>Text is not a control.</strong> Two gaps
 * followed from having no code behind it, and closing them is this class's entire purpose.
 *
 * <p>Decision {@code DL-102} in {@code docs/decision-log.md} records why the seeds are excluded by
 * version rather than by directory - the plan's file tree lists one directory and its version
 * numbers and file names are kept exactly - and decision {@code DL-110} records the sealing callback
 * this class registers for the two seeding profiles.
 *
 * <h2>Gap one: the profile list is a list, and a list can hold both</h2>
 *
 * <p>Spring resolves overlapping property sources by activation order, so the last profile that
 * declares a key wins. Activating {@code prod,local} therefore starts a deployment that reads the
 * production overlay <em>and then</em> lets the local overlay overwrite whatever it also declares -
 * the seed location among it, along with repository-known signing and encryption material, an
 * emulator endpoint, and a relaxed transport rule. Activating {@code local,prod} reverses which
 * document wins but not the fact that both were loaded. Neither ordering is a configuration a
 * reviewer would write on purpose, and neither is refused by anything in a profile document,
 * because a document cannot see the list that selected it.
 *
 * <p>{@link #requireExclusiveProfileActivation(Collection)} refuses the combination outright, and
 * the {@link BeanFactoryPostProcessor} published below is what runs it. That hook is chosen for a
 * specific reason: a bean-factory post-processor executes after every bean <em>definition</em> is
 * loaded and before any bean is <em>instantiated</em>, so the refusal lands before the data source
 * is built, before the migration runs, before the messaging clients are constructed and before any
 * {@code @ConfigurationProperties} value is bound. Refusing later - in a listener, in a health
 * indicator, in the migration itself - would already be too late for at least one of those.
 *
 * <p>The refusal names only the two profile literals this class itself declares. The active-profile
 * list is operator-supplied, so echoing it back into a diagnostic would let whoever composed it
 * write a chosen line into the start-up log; decision {@code DL-041} in {@code docs/decision-log.md}
 * governs that, and the same rule is applied here.
 *
 * <h2>Gap two: a ceiling is only a ceiling while nothing raises it</h2>
 *
 * <p>{@link #resolveTarget(Collection, String)} is the profile-scoped resolution the plan requires
 * of this class, applied to whatever the property binding produced rather than restating it. When
 * production is active it <strong>refuses</strong> any ceiling that would reach version
 * {@value #FIRST_SEED_VERSION} or beyond - which includes {@value #SEEDING_TARGET}, a predefined
 * marker and an absent value, because Flyway migrates to the latest version when no target is set -
 * so a merged environment, an operator override or a copied overlay block cannot raise it in
 * silence. When local or test is active it <strong>lifts</strong> a ceiling that would stop short of
 * the seeds, because a fixture-bearing profile that quietly migrated no fixtures is a defect too,
 * just a different one. With neither active the bound value is returned unchanged.
 *
 * <p>{@link #resolveLocations(Collection, Collection)} guards the other half of the same statement.
 * One directory now carries every script, so under production any location other than
 * {@value #SCHEMA_LOCATION} is refused: a second location is how an unreviewed script would arrive
 * with no version at all to cap it. Under local or test the schema location is added when the bound
 * list holds none, because a profile that resolved no location migrates nothing.
 *
 * <h2>And the consequence of the seeds existing at all: cleartext at rest</h2>
 *
 * <p>{@code V1__create_schema.sql} defines {@code customer.govt_issued_id} as {@code NOT NULL} and
 * states that any row a seed inserts must carry an application-produced envelope rather than a
 * cleartext identifier. Static forward-only SQL cannot honour that: producing an envelope requires
 * the deployment's own key, and committing a key to the repository to make a seed deterministic
 * would be worse than the gap it closed. So the reference seed writes the fixture's twenty-digit
 * identifiers as they stand, and the column then holds fifty cleartext regulated values that the
 * entity's own accessors would have refused - object-relational hydration bypasses those accessors,
 * so nothing failed and nothing said so.
 *
 * <p>{@link SeededIdentifierSealingCallback} closes that. It runs as a migration-lifecycle callback
 * on the one path the seeds can arrive by, converts each unsealed value through the module's single
 * encryption service, and is registered here <em>only</em> for the two profiles whose ceiling
 * reaches the seeds - so production neither seeds a row nor carries the component that would seal
 * one. The delivered reference seed already writes sealed envelopes, so the callback converts
 * nothing today and stands as defence in depth against a future edit to that script.
 *
 * <h2>What this class deliberately does not do</h2>
 *
 * <p>It declares no location, no ceiling, no baseline setting, no validation setting and no clean
 * setting of its own. Those live in the profile documents, where an operator reads them, and
 * duplicating any of them here would create a second place for them to disagree. This class only
 * refuses, completes and seals what those documents produced.
 *
 * <p>It also does not add a versioned migration. Sealing the seeded identifiers as a {@code V5}
 * would have made the delivered migration set end at a version the shipped scripts do not reach,
 * which the profile documents state and a test asserts against the delivered scripts; a callback
 * carries no version and leaves that ledger true.
 *
 * <p>Provenance: this configuration has no single legacy antecedent. The separation it enforces
 * replaces the ten {@code DEFINE CLUSTER} provisioning job streams in {@code app/jcl} and the
 * in-stream sign-on identities of {@code app/jcl/DUSRSECJ.jcl}, taken from checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text appears here.
 */
@Configuration(proxyBeanMethods = false)
public final class FlywayConfig {

    /** Name of the profile that carries the production overlay. */
    public static final String PRODUCTION_PROFILE = "prod";

    /** Name of the profile that carries the local development overlay. */
    public static final String LOCAL_PROFILE = "local";

    /** Name of the profile that carries the automated-test overlay. */
    public static final String TEST_PROFILE = "test";

    /**
     * The location every profile migrates from, holding the schema and its indexes. Held as a
     * constant so the value this class refuses to lose and the value the documents declare cannot
     * drift apart.
     */
    public static final String SCHEMA_LOCATION = "classpath:db/migration";

    /**
     * The classpath-relative path inside {@link #SCHEMA_LOCATION}. A location is recognised as the
     * module's own by this path rather than by string equality with the descriptor, so a trailing
     * separator, a differently spelled prefix or a file-system descriptor addressing the same
     * directory is still recognised - and anything else is not.
     */
    private static final String SCHEMA_PATH = "db/migration";

    /**
     * The path separator a normalised location descriptor uses. The schema location is recognised by
     * finding the schema path bounded by this separator on both sides, which is what keeps a sibling
     * directory whose name begins with the same characters from matching.
     */
    private static final String SEGMENT_SEPARATOR = "/";

    /**
     * The ceiling that stops a migration at the schema and its indexes, declared by the shared
     * baseline and re-declared by the production overlay. It is the highest version the delivered
     * schema scripts reach, and the first seed script sits above it.
     */
    public static final String SCHEMA_ONLY_TARGET = "2";

    /**
     * The ceiling a seeding profile declares for itself, which is Flyway's own word for "apply
     * everything". Only the local and test overlays carry it.
     */
    public static final String SEEDING_TARGET = "latest";

    /**
     * The first version a seed script occupies. A ceiling that reaches this version reaches the
     * reference-data seed, and a ceiling below it cannot.
     */
    private static final String FIRST_SEED_VERSION = "3";

    /** Diagnostic channel. It records the resolution taken and never an operator-supplied value. */
    private static final Logger LOGGER = LoggerFactory.getLogger(FlywayConfig.class);

    /**
     * Creates the configuration singleton.
     *
     * <p>Declared explicitly so that the absence of collaborators is visible: every decision this
     * class takes is a function of the active profiles and the bound location list, and it holds no
     * state of its own.</p>
     */
    public FlywayConfig() {
    }

    /**
     * Publishes the guard that refuses a production profile activated alongside a non-production
     * one.
     *
     * <p>The method is {@code static} so that publishing the guard does not instantiate this
     * configuration class first, which would put an ordinary bean's construction ahead of a
     * bean-factory post-processor and defeat the earliness the guard depends on.
     *
     * @param environment the environment whose active-profile list is examined; supplied by the
     *                    container
     * @return a post-processor that refuses the combination before any bean is instantiated
     */
    @Bean
    static BeanFactoryPostProcessor profileActivationGuard(final Environment environment) {
        return beanFactory ->
                requireExclusiveProfileActivation(Arrays.asList(environment.getActiveProfiles()));
    }

    /**
     * Publishes the profile-scoped resolution of the migration scope as a customizer over the bound
     * configuration.
     *
     * <p>A customizer is applied after the property binding and after the callbacks are registered,
     * which is exactly when the bound location list and the bound ceiling can be inspected and, if
     * necessary, refused. Both halves are resolved here because either one alone would leave a way
     * for a seed script to be applied: a raised ceiling reaches the seeds that ship in the one
     * location, and a second location carries scripts that no ceiling caps.
     *
     * @param environment the environment whose active-profile list selects the resolution
     * @return a customizer that refuses a seed-reaching ceiling or a foreign location under
     *         production, and completes both under local or test
     */
    @Bean
    FlywayConfigurationCustomizer migrationScopeResolvingCustomizer(final Environment environment) {
        return configuration -> {
            final List<String> activeProfiles = Arrays.asList(environment.getActiveProfiles());
            final List<String> declared = Arrays.stream(configuration.getLocations())
                    .map(Location::getDescriptor)
                    .toList();
            final List<String> resolved = resolveLocations(activeProfiles, declared);
            if (!resolved.equals(declared)) {
                configuration.locations(resolved.toArray(String[]::new));
                LOGGER.info("Schema migration location completed for a non-production profile;"
                        + " {} location(s) now resolve", resolved.size());
            }
            final MigrationVersion boundTarget = configuration.getTarget();
            final String resolvedTarget = resolveTarget(activeProfiles, describe(boundTarget));
            if (resolvedTarget != null && !resolvedTarget.equals(describe(boundTarget))) {
                configuration.target(resolvedTarget);
                LOGGER.info("Migration ceiling lifted to the seeding target for a non-production"
                        + " profile");
            }
        };
    }

    /**
     * Publishes the component that seals the seeded regulated identifiers, for the two profiles that
     * can seed them.
     *
     * <p>The profile restriction is the whole of the reason this bean is conditional. Production
     * lists no seed location, so no row this callback would act on can exist there; registering the
     * component anyway would put a table-wide update on a production migration path for no purpose.
     *
     * @param encryption the module's single field-encryption service
     * @return the migration-lifecycle callback that converts each cleartext seeded identifier
     */
    @Bean
    @Profile({LOCAL_PROFILE, TEST_PROFILE})
    Callback seededIdentifierSealingCallback(final SensitiveFieldEncryptionService encryption) {
        return new SeededIdentifierSealingCallback(encryption);
    }

    /**
     * Refuses a profile list that activates production alongside local or test.
     *
     * <p>Comparison is case-insensitive under {@link Locale#ROOT} and ignores surrounding
     * whitespace, because a profile list is frequently supplied as one comma-separated environment
     * variable and {@code PROD, local} selects the same two documents as {@code prod,local}. Order
     * is irrelevant: both documents are loaded either way, and which one wins a contested key only
     * changes which half of the mixture is observed.
     *
     * <p>The message names the two matched literals this class declares and never the supplied list,
     * per decision {@code DL-041}: an operator composes that list, so repeating it would let the
     * composer place a chosen line in the start-up log.
     *
     * @param activeProfiles the active-profile list, which may be empty and whose entries may be
     *                       {@code null}
     * @throws IllegalStateException when production is active together with local or with test
     */
    public static void requireExclusiveProfileActivation(final Collection<String> activeProfiles) {
        if (activeProfiles == null || activeProfiles.isEmpty()) {
            return;
        }
        if (!containsProfile(activeProfiles, PRODUCTION_PROFILE)) {
            return;
        }
        for (final String nonProduction : List.of(LOCAL_PROFILE, TEST_PROFILE)) {
            if (containsProfile(activeProfiles, nonProduction)) {
                throw new IllegalStateException("profile '" + PRODUCTION_PROFILE
                        + "' must not be active together with profile '" + nonProduction
                        + "'; the non-production overlay would supply repository-known signing and"
                        + " encryption material, an emulator endpoint, a relaxed transport rule and"
                        + " the seed migration location to a deployment that reads the production"
                        + " overlay, in either activation order. Activate exactly one of them");
            }
        }
    }

    /**
     * Resolves the migration locations for the active profiles.
     *
     * <p>Three outcomes, and each is a different kind of statement:
     *
     * <ul>
     *   <li>Production active - any location that is not {@value #SCHEMA_LOCATION} is
     *       <strong>refused</strong>. This is the security control on the location half: every
     *       delivered script lives in that one directory and is capped by the version ceiling, so a
     *       second location can only be carrying something unreviewed that no ceiling caps. It fires
     *       on the merged, bound list rather than on one document, so an inherited value, an
     *       operator override and a copied overlay block are all covered by the same check.</li>
     *   <li>Local or test active - the schema location is <strong>added</strong> when the bound list
     *       holds none, keeping the declared order and appending rather than replacing, because a
     *       profile that resolved no location migrates nothing at all.</li>
     *   <li>Neither active - the bound list is returned unchanged. A profile-less start migrates
     *       whatever the shared baseline declares.</li>
     * </ul>
     *
     * @param activeProfiles    the active-profile list, which may be empty
     * @param declaredLocations the location descriptors the property binding produced, in order
     * @return the locations to migrate from, in order; the same values when nothing changed
     * @throws IllegalStateException when production is active and a location other than the schema
     *                               location is present
     */
    public static List<String> resolveLocations(final Collection<String> activeProfiles,
            final Collection<String> declaredLocations) {
        final List<String> declared = declaredLocations == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(declaredLocations));
        if (containsProfile(activeProfiles, PRODUCTION_PROFILE)) {
            for (final String location : declared) {
                if (location != null && !location.isBlank() && !isSchemaLocation(location)) {
                    throw new IllegalStateException("profile '" + PRODUCTION_PROFILE
                            + "' resolved a migration location outside '" + SCHEMA_LOCATION
                            + "'. Every delivered script lives there and is capped at version '"
                            + SCHEMA_ONLY_TARGET + "', which is what keeps the reference-data seed"
                            + " and the sign-on-identity seed out of a production database: those"
                            + " insert fifty synthetic customer rows holding regulated identity data"
                            + " and ten known sign-on identities whose stored credentials are digests"
                            + " of one well-known value. A second location carries scripts no ceiling"
                            + " caps, so production must migrate from '" + SCHEMA_LOCATION
                            + "' alone");
                }
            }
            return declared;
        }
        if (!containsProfile(activeProfiles, LOCAL_PROFILE)
                && !containsProfile(activeProfiles, TEST_PROFILE)) {
            return declared;
        }
        if (declared.stream().anyMatch(FlywayConfig::isSchemaLocation)) {
            return declared;
        }
        final List<String> completed = new ArrayList<>(declared);
        completed.add(SCHEMA_LOCATION);
        return Collections.unmodifiableList(completed);
    }

    /**
     * Resolves the migration ceiling for the active profiles.
     *
     * <p>Three outcomes, mirroring {@link #resolveLocations(Collection, Collection)}:
     *
     * <ul>
     *   <li>Production active - a ceiling that reaches version {@value #FIRST_SEED_VERSION} or
     *       beyond is <strong>refused</strong>. {@value #SEEDING_TARGET}, any predefined marker, a
     *       malformed value and an absent value all count as reaching it: Flyway migrates to the
     *       latest version when no target is set, so silence is the dangerous case rather than the
     *       safe one, and a value this method cannot read is refused rather than assumed
     *       harmless.</li>
     *   <li>Local or test active - a ceiling that would stop short of the seeds is
     *       <strong>lifted</strong> to {@value #SEEDING_TARGET}, because the inherited baseline
     *       ceiling reaching a seeding profile would migrate the schema and none of the fixtures the
     *       profile exists to load.</li>
     *   <li>Neither active - the bound value is returned unchanged.</li>
     * </ul>
     *
     * <p>The refusal names the two version literals this class declares and never the supplied
     * value, per decision {@code DL-041}: the ceiling is operator-supplied, so repeating it would
     * let whoever supplied it write a chosen line into the start-up log.
     *
     * @param activeProfiles the active-profile list, which may be empty
     * @param declaredTarget the ceiling the property binding produced, which may be {@code null}
     * @return the ceiling to migrate to; the same value when nothing changed
     * @throws IllegalStateException when production is active and the ceiling reaches a seed script
     */
    public static String resolveTarget(final Collection<String> activeProfiles,
            final String declaredTarget) {
        final String declared = declaredTarget == null ? null : declaredTarget.strip();
        final boolean reachesSeeds = reachesSeedVersions(parseVersion(declared));
        if (containsProfile(activeProfiles, PRODUCTION_PROFILE)) {
            if (reachesSeeds) {
                throw new IllegalStateException("profile '" + PRODUCTION_PROFILE
                        + "' resolved a migration ceiling that reaches version '"
                        + FIRST_SEED_VERSION + "' or beyond, where the reference-data seed and the"
                        + " sign-on-identity seed sit. Those insert fifty synthetic customer rows"
                        + " holding regulated identity data and ten known sign-on identities whose"
                        + " stored credentials are digests of one well-known value, so production"
                        + " must declare spring.flyway.target '" + SCHEMA_ONLY_TARGET + "'. An"
                        + " absent, predefined or unreadable ceiling is refused for the same reason:"
                        + " Flyway migrates to the latest version when none is set");
            }
            return declared;
        }
        if (!containsProfile(activeProfiles, LOCAL_PROFILE)
                && !containsProfile(activeProfiles, TEST_PROFILE)) {
            return declared;
        }
        return reachesSeeds ? declared : SEEDING_TARGET;
    }

    /**
     * Reports whether a profile list holds one named profile, comparing case-insensitively and
     * ignoring surrounding whitespace.
     *
     * @param activeProfiles the list to examine, which may be {@code null} and may hold {@code null}
     * @param profile        the profile name to look for
     * @return {@code true} when the list holds the named profile
     */
    private static boolean containsProfile(final Collection<String> activeProfiles,
            final String profile) {
        if (activeProfiles == null) {
            return false;
        }
        for (final String candidate : activeProfiles) {
            if (candidate != null && candidate.strip().equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reports whether a location descriptor addresses the module's own migration path or anything
     * beneath it.
     *
     * <p>The match is on a whole path segment sequence rather than on the raw descriptor, so that
     * every spelling of the same directory is recognised: {@code classpath:db/migration}, a trailing
     * separator, a nested sub-path such as {@code classpath:db/migration/regional}, a file-system
     * descriptor pointing at the same directory on disk, and a bare {@code db/migration} with no
     * prefix. A location prefix is separated by a colon and a file-system descriptor may use
     * back-slashes, so both are normalised to the forward slash before the segment is looked for -
     * and because the match requires a separator on both sides, a sibling directory whose name
     * merely begins with the same characters, such as {@code db/migrations}, is correctly not
     * matched, which is the conservative answer here: an unrecognised location is refused under
     * production rather than accepted.
     *
     * <p>The descriptor is deliberately not parsed by {@link Location}. That parser raises its own
     * exception for a malformed descriptor and puts the descriptor into the message, which would
     * hand an operator-supplied value into a diagnostic and defeat decision {@code DL-041}. This
     * predicate answers {@code false} for anything it cannot recognise and lets the production
     * refusal, or the migration tool itself, deal with it.
     *
     * @param descriptor a location descriptor, such as {@code classpath:db/migration}
     * @return {@code true} when the descriptor addresses the schema path
     */
    private static boolean isSchemaLocation(final String descriptor) {
        if (descriptor == null || descriptor.isBlank()) {
            return false;
        }
        final String normalised = SEGMENT_SEPARATOR
                + descriptor.strip().replace('\\', '/').replace(':', '/')
                + SEGMENT_SEPARATOR;
        return normalised.contains(SEGMENT_SEPARATOR + SCHEMA_PATH + SEGMENT_SEPARATOR);
    }

    /**
     * Reports whether a ceiling would reach the first seed script.
     *
     * <p>Fail-closed by construction. An absent ceiling reaches the seeds, because Flyway migrates
     * to the latest version when none is set. A predefined marker - the latest, current, next or
     * empty version - reaches them too as far as this predicate is concerned, because none of the
     * four is a numbered ceiling that can be shown to stop below the seeds. Anything else is
     * compared numerically against the first seed version.
     *
     * @param version the parsed ceiling, or {@code null} when there was none or it was unreadable
     * @return {@code true} when the ceiling reaches a seed script
     */
    private static boolean reachesSeedVersions(final MigrationVersion version) {
        return version == null || version.isPredefined()
                || version.isAtLeast(FIRST_SEED_VERSION);
    }

    /**
     * Parses a ceiling into a comparable version, answering {@code null} for anything unreadable.
     *
     * <p>The migration tool's own parser raises an exception carrying the offending text, so it is
     * called inside a guard: an unreadable ceiling becomes {@code null}, which
     * {@link #reachesSeedVersions(MigrationVersion)} treats as reaching the seeds, and the refusal
     * that follows names only the literals this class declares.
     *
     * @param target the ceiling as declared, which may be {@code null} or blank
     * @return the parsed version, or {@code null} when absent, blank or unreadable
     */
    private static MigrationVersion parseVersion(final String target) {
        if (target == null || target.isBlank()) {
            return null;
        }
        try {
            return MigrationVersion.fromVersion(target);
        } catch (final RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * Renders a bound ceiling as the text a profile document would have declared.
     *
     * <p>{@link MigrationVersion#toString()} renders a predefined marker as a display phrase that
     * cannot be parsed back, so the two predefined values a profile can legitimately declare are
     * mapped to their declared spellings and every numbered version is rendered as itself.
     *
     * @param version the bound ceiling, which may be {@code null}
     * @return the declared spelling of the ceiling, or {@code null} when there is none
     */
    private static String describe(final MigrationVersion version) {
        if (version == null || MigrationVersion.EMPTY.equals(version)) {
            return null;
        }
        if (MigrationVersion.LATEST.equals(version)) {
            return SEEDING_TARGET;
        }
        return version.getVersion();
    }
}
