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
 * <p>All four delivered migrations ship flat from the single location {@value #SCHEMA_LOCATION}:
 * {@code V1__create_schema.sql}, {@code V2__create_indexes.sql},
 * {@code V3__seed_reference_data.sql} and {@code V4__seed_user_security.sql}. The flat layout is
 * required rather than chosen - the migration specifications for V3 and V4 both state that the four
 * scripts are physically flat in one directory and direct that no subdirectory be created, giving the
 * reason explicitly: directory-scoped locations cannot isolate the seeds from the schema, because a
 * location is scanned recursively and any deployment that resolves both directories applies the whole
 * ascending sequence whatever folder each script came from. What the plan means by "profile-scoped"
 * is therefore a profile-scoped <em>ceiling</em>, not a profile-scoped directory, and both
 * specifications name that ceiling: {@code spring.flyway.target=2}.
 *
 * <p>The two seeds are what the scoping exists for. The sign-on seed inserts ten known identities,
 * five of them administrative, whose stored credentials are all digests of one well-known value; the
 * reference seed inserts fifty synthetic customer rows carrying regulated identity data. Neither
 * belongs in a production database, and neither arriving there would be untidiness - it would be a
 * credential incident and a privacy incident respectively.
 *
 * <p><strong>Two independent controls hold that separation, and neither is a directory.</strong>
 * The <em>version ceiling</em> decides what is applied: {@code spring.flyway.target} is
 * {@value #SCHEMA_ONLY_TARGET} in the shared baseline and again in the production overlay, so a
 * profile silent about migrations inherits the production posture, and only the local and test
 * overlays lift it to {@value #SEEDING_TARGET}. V1 and V2 sit at or below the ceiling; V3 and V4 are
 * resolved, reported above target and never executed. The <em>applied-state check</em> decides
 * whether this database was ever entitled to start under production at all:
 * {@link ProductionSeedRejectionCallback} refuses a production start against a database whose history
 * records a seed migration or whose tables still hold seeded rows. The two are genuinely independent -
 * the ceiling cannot help a database that was seeded before production was ever pointed at it, and the
 * applied-state check cannot stop a seed being applied for the first time.
 *
 * <p>Both controls declared in three profile documents are statements, and the documents were the
 * whole of the mechanism until this class existed. <strong>Text is not a control.</strong> Two gaps
 * followed from having no code behind it, and closing them is this class's entire purpose.
 *
 * <p>Decision {@code DL-102} in {@code docs/decision-log.md} records why both controls are kept
 * rather than either alone, and decision {@code DL-110} records the sealing callback this class
 * registers for the two seeding profiles. The third control - a refusal to start production against
 * a database that was seeded before this process existed, which no configuration value can reach - is
 * {@link ProductionSeedRejectionCallback}, registered below for production alone.
 *
 * <p><strong>The reconciliation that decision settles, recorded here so it is not re-litigated:</strong>
 * the module's target-tree description reaches for a <em>profile-scoped seed location</em>, a separate
 * directory that production simply does not list. That mechanism is not implementable against what was
 * actually delivered. Every script ships flat in the one directory, the seed scripts themselves forbid
 * introducing a subdirectory, and a directory-scoped location list cannot separate {@code V3} and
 * {@code V4} from {@code V1} and {@code V2} while all of them sit in the same folder - attempting it
 * would either duplicate the schema scripts into a second location or ship the seeds to production.
 * Both mechanisms aim at one outcome, that the seeds are unreachable in production, and the version
 * ceiling is the one the delivered artefacts support. So the arrangement is <em>one flat location
 * shared by every profile</em>, with production <em>additionally</em> constrained by a version
 * ceiling - which is precisely what the four profile documents declare, and what this class refuses to
 * let a merged environment undo. An earlier revision did split the directory; {@code DL-102} records
 * why that was withdrawn.
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
 * <h2>Gap two: a ceiling is only a control while nothing widens it</h2>
 *
 * <p>{@link #resolveTarget(Collection, String)} guards the control that actually holds the seeds back.
 * When production is active it <strong>refuses</strong> any ceiling that would reach version
 * {@value #FIRST_SEED_VERSION} or beyond - which includes {@value #SEEDING_TARGET}, a predefined
 * marker and an absent value, because Flyway migrates to the latest version when no target is set - so
 * a merged environment, an operator override or a copied overlay block cannot raise it in silence.
 * When local or test is active it <strong>lifts</strong> a ceiling that would stop short of the seeds,
 * because a fixture-bearing profile that stopped at the schema would migrate no fixtures. With neither
 * active the bound value is returned unchanged, which means the shared baseline's production posture
 * is what a profile-less start inherits.
 *
 * <p>{@link #resolveLocations(Collection, Collection)} guards the sequence that ceiling was measured
 * against. When production is active it <strong>refuses</strong> any resolved location other than
 * {@value #SCHEMA_LOCATION}, because a location the ceiling never measured can carry a script the
 * ceiling does not cap. When local or test is active it <strong>completes</strong>
 * {@value #SCHEMA_LOCATION} if the bound list is missing it, because a profile that resolved no
 * migration location migrates nothing at all. With neither active the bound list is returned
 * unchanged. It does not separate schema from seed, and it is not asked to: that is the ceiling's job,
 * and the specifications for V3 and V4 both say so directly.
 *
 * <p>Both resolutions fire on the <em>merged, bound</em> configuration rather than on any one
 * document, so an inherited value, an operator override on the command line and a co-activated
 * overlay are all covered by the same check.
 *
 * <h2>And the consequence of the seeds existing at all: two invariants on the sealed columns</h2>
 *
 * <p>{@code V1__create_schema.sql} defines {@code customer.govt_issued_id} as {@code NOT NULL} and
 * states that any row a seed inserts must carry an application-produced envelope rather than a
 * cleartext identifier. The reference seed honours that directly, inserting fifty fixed envelopes
 * produced by the module's own encryption service and seeding {@code customer.cust_ssn} as
 * {@code null} in every row. {@link SeededIdentifierSealingCallback} is registered here for the two
 * seeding profiles alone, and it holds the columns to two distinct invariants.
 *
 * <p>The first is <strong>shape</strong>: any unsealed value it finds is sealed. On a delivered
 * database that converts nothing, and it stands as defence in depth against a future edit to the
 * seed - which matters because the entity's own accessors would refuse a cleartext value while
 * object-relational hydration bypasses them, so nothing else would object.
 *
 * <p>The second is <strong>key</strong>, and it is not redundant on a delivered database: every
 * stored value is <em>opened</em> under the key the running process actually holds, and one that will
 * not open fails start-up. A shape check cannot make that statement, because an envelope sealed under
 * some other key still looks like an envelope, and the fifty seeded envelopes are fixed literals that
 * no pass can re-key. That is also why the two seeding profiles declare their fixture key as a bare
 * literal rather than as an environment-variable default. Production carries neither invariant nor
 * the component enforcing them, because it lists no seed location and receives no row from either
 * seed.
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
 * <h2>What the scripts below the ceiling replace, and where each fact came from</h2>
 *
 * <p>Everything at or below version {@value #SCHEMA_ONLY_TARGET} - which is exactly what a
 * production deployment applies - stands in for the ten {@code DEFINE CLUSTER} provisioning job
 * streams that built the legacy indexed base clusters, one job per cluster:
 * {@code app/jcl/ACCTFILE.jcl}, {@code app/jcl/CARDFILE.jcl}, {@code app/jcl/CUSTFILE.jcl},
 * {@code app/jcl/XREFFILE.jcl}, {@code app/jcl/TRANFILE.jcl}, {@code app/jcl/TCATBALF.jcl},
 * {@code app/jcl/DISCGRP.jcl}, {@code app/jcl/TRANCATG.jcl}, {@code app/jcl/TRANTYPE.jcl} and
 * {@code app/jcl/DUSRSECJ.jcl}. Each of those states its cluster's key width and offset, and that is
 * where every primary key in {@code V1__create_schema.sql} comes from rather than from a generated
 * surrogate: {@code app/jcl/DUSRSECJ.jcl} lines 64-66, to take the one this class touches most
 * directly, gives the sign-on cluster a key of length 8 at offset 0 in a fixed 80-byte record, which
 * is why {@code user_security} is keyed on an eight-character identifier and no wider.
 *
 * <p>{@code V2__create_indexes.sql} holds exactly three secondary indexes, and the count is a
 * finding rather than a choice: the legacy estate declared exactly three alternate indexes, each
 * {@code NONUNIQUEKEY} and each {@code UPGRADE}. Every one is reproduced as one nonunique B-tree
 * over the column its alternate key addressed, at the key length and record offset its job stream
 * states:
 *
 * <ul>
 *   <li>{@code idx_card_card_acct_id} over {@code card(card_acct_id)} - length 11 at offset 16, from
 *       {@code app/jcl/CARDFILE.jcl} lines 83-88.</li>
 *   <li>{@code idx_card_cross_reference_xref_acct_id} over
 *       {@code card_cross_reference(xref_acct_id)} - length 11 at offset 25, from
 *       {@code app/jcl/XREFFILE.jcl} lines 72-77.</li>
 *   <li>{@code idx_transaction_tran_proc_ts} over {@code transaction(tran_proc_ts)} - length 26 at
 *       offset 304, from {@code app/jcl/TRANIDX.jcl} lines 25-30 <em>and</em>
 *       {@code app/jcl/TRANFILE.jcl} lines 82-87. Those two declare the <strong>same</strong>
 *       alternate index, over the same base cluster, on the same key: it is <strong>one logical
 *       index and is emitted once</strong>, not two. It is also the only one of the three that no
 *       online path reads - the date-range filter of the transaction report is what needs it - so it
 *       is a batch-only index that a production deployment nevertheless has to carry.</li>
 * </ul>
 *
 * <p>Those three are named here so the ceiling can be audited by what it <em>admits</em> as well as
 * by what it excludes. Note where the guarantee that they exist actually rests:
 * {@link #resolveTarget(Collection, String)} refuses a ceiling only for reaching <em>up</em> into the
 * seeds and deliberately accepts one that stops lower, so that a schema script numbered above
 * {@value #SCHEMA_ONLY_TARGET} is never refused merely for being new. Keeping these three indexes in
 * a production deployment is therefore the profile documents' job - they declare
 * {@code spring.flyway.target} as {@value #SCHEMA_ONLY_TARGET}, not below it - and it is asserted
 * against the delivered numbering by {@code FlywayConfigCoverageTest} rather than assumed here.
 *
 * <p>Provenance: this configuration has no single legacy antecedent, because the separation it
 * enforces is one the legacy estate had no equivalent of - there, a provisioning job stream that was
 * simply never submitted was the whole of the protection. The artefacts it stands over are the ten
 * provisioning job streams named above and the in-stream sign-on identities at
 * {@code app/jcl/DUSRSECJ.jcl} lines 35-44, read from checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Those identities are cited by position and
 * by count alone: no job-stream text, no dataset-utility control statement and above all no
 * credential value carried by them appears anywhere in this file.
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
     * The one location every profile migrates from, holding all four delivered scripts. Held as a
     * constant so the value this class refuses to lose and the value the documents declare cannot
     * drift apart.
     *
     * <p>There is deliberately no second location. The migration specifications for
     * {@code V3__seed_reference_data.sql} and {@code V4__seed_user_security.sql} both require the four
     * scripts to be physically flat in this one directory and direct that no subdirectory be created,
     * on the stated ground that directory-scoped locations cannot isolate the seeds from the schema.
     * The seeds are held out of production by the version ceiling
     * {@value #SCHEMA_ONLY_TARGET} instead - see {@link #resolveTarget(Collection, String)} - and a
     * database that has already been seeded is refused outright by
     * {@link ProductionSeedRejectionCallback}. An earlier revision of this class split the scripts
     * across a schema and a seed location; that split is withdrawn, and this constant is the record of
     * it, because a directory boundary is not a boundary Flyway enforces.</p>
     */
    public static final String SCHEMA_LOCATION = "classpath:db/migration";

    /**
     * The classpath-relative path inside {@link #SCHEMA_LOCATION}. A location is recognised as the
     * module's migration location by this path rather than by string equality with the descriptor, so
     * a trailing separator, a differently spelled prefix or a file-system descriptor addressing the
     * same directory is still recognised - and anything else is not.
     */
    private static final String SCHEMA_PATH = "db/migration";

    /**
     * The path separator a normalised location descriptor uses. A location is recognised by finding
     * its path bounded by this separator on both sides, which is what keeps a sibling directory whose
     * name begins with the same characters from matching.
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
     * for a seed script to be applied: a raised ceiling reaches a seed that a widened location list
     * made visible, and a widened location list carries scripts a renumbering put below the ceiling.
     *
     * @param environment the environment whose active-profile list selects the resolution
     * @return a customizer that refuses a seed-reaching ceiling or a non-schema location under
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
                LOGGER.info("Migration location list completed for a non-production profile;"
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
     * Publishes the component that refuses a production start-up against an already-seeded database,
     * for the production profile alone.
     *
     * <p>This is the third control and the only one that inspects the DATABASE rather than this
     * process's configuration. {@link #resolveLocations(Collection, Collection)} and
     * {@link #resolveTarget(Collection, String)} both decide what this run would apply, and both are
     * therefore blind to a database that was seeded before this run existed - a re-pointed connection
     * string, or a development dump restored into a production instance. The callback closes exactly
     * that gap, and it does so before the first script executes.
     *
     * <p>The profile restriction is the whole of the reason this bean is conditional. Under local and
     * test the seeded rows are the point, so the same inspection there would refuse every start-up the
     * fixtures depend on.
     *
     * @return the migration-lifecycle callback that refuses an already-seeded production database
     */
    @Bean
    @Profile(PRODUCTION_PROFILE)
    Callback productionSeedRejectionCallback() {
        return new ProductionSeedRejectionCallback();
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
     * <p>All four delivered scripts sit in {@value #SCHEMA_LOCATION}, so this method does not separate
     * schema from seed - that separation is the version ceiling's job, not a directory's. What it does
     * is keep every profile pointed at the one location the delivered scripts actually occupy, so that
     * the ceiling is applied to the sequence it was measured against.
     *
     * <p>Three outcomes, and each is a different kind of statement:
     *
     * <ul>
     *   <li>Production active - any location that is not {@value #SCHEMA_LOCATION} or a path beneath
     *       it is <strong>refused</strong>. An unrecognised location could carry scripts no ceiling
     *       caps, and the ceiling is the only thing keeping the reference-data seed and the
     *       sign-on-identity seed out of a production database: those insert fifty synthetic customer
     *       rows holding regulated identity data and ten known sign-on identities whose stored
     *       credentials are digests of one well-known value. It fires on the merged, bound list rather
     *       than on one document, so an inherited value, an operator override and a copied overlay
     *       block are all covered by the same check.</li>
     *   <li>Local or test active - {@value #SCHEMA_LOCATION} is <strong>completed</strong> if the
     *       bound list does not already resolve it, keeping the declared order and appending rather
     *       than replacing, because a profile that resolved no migration location migrates nothing at
     *       all.</li>
     *   <li>Neither active - the bound list is returned unchanged. A profile-less start migrates
     *       whatever the shared baseline declares, which carries the production posture.</li>
     * </ul>
     *
     * @param activeProfiles    the active-profile list, which may be empty
     * @param declaredLocations the location descriptors the property binding produced, in order
     * @return the locations to migrate from, in order; the same values when nothing changed
     * @throws IllegalStateException when production is active and a location other than the delivered
     *                               migration location is present
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
                            + "'. All four delivered scripts live there and production is capped at"
                            + " version '" + SCHEMA_ONLY_TARGET + "', which is what keeps the"
                            + " reference-data seed and the sign-on-identity seed out of a production"
                            + " database: those insert fifty synthetic customer rows holding regulated"
                            + " identity data and ten known sign-on identities whose stored credentials"
                            + " are digests of one well-known value. Another location carries scripts"
                            + " that ceiling never measured, so production must migrate from '"
                            + SCHEMA_LOCATION + "' alone");
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
     * Reports whether a location descriptor addresses the module's migration path or anything beneath
     * it.
     *
     * @param descriptor a location descriptor, such as {@code classpath:db/migration}
     * @return {@code true} when the descriptor addresses the migration path
     */
    private static boolean isSchemaLocation(final String descriptor) {
        return addressesPath(descriptor, SCHEMA_PATH);
    }

    /**
     * Reports whether a location descriptor addresses one classpath-relative path or anything
     * beneath it.
     *
     * <p>The match is on a whole path segment sequence rather than on the raw descriptor, so that
     * every spelling of the same directory is recognised: {@code classpath:db/migration}, a trailing
     * separator, a nested sub-path such as {@code classpath:db/migration/regional}, a file-system
     * descriptor pointing at the same directory on disk, and a bare {@code db/migration} with no
     * prefix. A nested sub-path is deliberately accepted rather than refused, because Flyway scans a
     * location recursively: a descriptor beneath the migration path resolves a subset of the same
     * ascending sequence the ceiling was measured against, so it is the same location for this
     * predicate's purpose. A location prefix is separated by a colon and a file-system descriptor may
     * use back-slashes, so both are normalised to the forward slash before the segment is looked for -
     * and because the match requires a separator on both sides, a sibling whose name merely begins with
     * the same characters, such as {@code db/migrations}, is not matched. That exclusion matters: such
     * a directory would carry scripts outside the sequence the production ceiling was measured against,
     * so a production profile that declared it must be refused rather than accepted.
     *
     * <p>The descriptor is deliberately not parsed by {@link Location}. That parser raises its own
     * exception for a malformed descriptor and puts the descriptor into the message, which would
     * hand an operator-supplied value into a diagnostic and defeat decision {@code DL-041}. This
     * predicate answers {@code false} for anything it cannot recognise and lets the production
     * refusal, or the migration tool itself, deal with it.
     *
     * @param descriptor a location descriptor, which may be {@code null} or blank
     * @param path       the classpath-relative path to look for, without a prefix or a separator
     * @return {@code true} when the descriptor addresses the given path or a path beneath it
     */
    private static boolean addressesPath(final String descriptor, final String path) {
        if (descriptor == null || descriptor.isBlank()) {
            return false;
        }
        final String normalised = SEGMENT_SEPARATOR
                + descriptor.strip().replace('\\', '/').replace(':', '/')
                + SEGMENT_SEPARATOR;
        return normalised.contains(SEGMENT_SEPARATOR + path + SEGMENT_SEPARATOR);
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
