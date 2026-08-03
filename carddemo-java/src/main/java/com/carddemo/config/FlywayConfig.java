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
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import com.carddemo.service.SensitiveFieldEncryptionService;

/**
 * The single enforcement point for the two guarantees the module's schema evolution rests on: that a
 * production deployment can never reach the seed migrations, and that no seeded regulated identifier
 * is left at rest in cleartext.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>The four delivered migrations ship from <strong>two</strong> locations, split by purpose.
 * {@value #SCHEMA_LOCATION} carries {@code V1__create_schema.sql} and
 * {@code V2__create_indexes.sql}; {@value #SEED_LOCATION} carries
 * {@code V3__seed_reference_data.sql} and {@code V4__seed_user_security.sql}. Every profile resolves
 * the schema location. <strong>Only the local and test profiles resolve the seed location, and
 * production is refused it outright</strong> - which is the module plan's stated mechanism, quoted
 * from its own structural-decisions list: {@code FlywayConfig} resolves {@code V3} and {@code V4}
 * from profile-scoped locations so that a production deployment migrates schema and indexes without
 * inheriting sample data or seeded credentials.
 *
 * <p>The split is what makes that mechanism expressible at all. A Flyway location is scanned
 * <em>recursively</em>, so while any script sat directly in the shared parent {@code db/migration} no
 * location list could separate the seeds from the schema, and the separation had to be attempted with
 * a version ceiling instead. Moving all four scripts down one level - none left in the parent - is
 * what turns the location list back into a real boundary: a deployment that lists
 * {@value #SCHEMA_LOCATION} alone cannot see {@code V3} or {@code V4} at all, so they are not
 * resolved, not reported and not applied. The file names are unchanged, because each is a load-bearing
 * Flyway log token, and both directories remain inside the plan's own
 * {@code db/migration/**.sql} delivery pattern.
 *
 * <p>The two seeds are what the scoping exists for. The sign-on seed inserts ten known identities,
 * five of them administrative, whose stored credentials are all digests of one well-known value; the
 * reference seed inserts fifty synthetic customer rows carrying regulated identity data. Neither
 * belongs in a production database, and neither arriving there would be untidiness - it would be a
 * credential incident and a privacy incident respectively.
 *
 * <p><strong>Three independent controls hold that separation.</strong> The <em>location list</em> is
 * the primary one and decides what is even visible: {@link #resolveLocations(Collection, Collection)}
 * refuses {@value #SEED_LOCATION}, and refuses the shared parent that would reach it recursively,
 * whenever production is active, and completes both locations under local and test. A script that is
 * never resolved cannot be applied by any ceiling. The <em>version ceiling</em> is retained behind it
 * and decides what is applied out of whatever was resolved: {@code spring.flyway.target} is
 * {@value #SCHEMA_ONLY_TARGET} in the shared baseline and again in the production overlay, so a
 * profile silent about migrations inherits the production posture, and only the local and test
 * overlays lift it to {@value #SEEDING_TARGET}. The <em>applied-state check</em> decides whether this
 * database was ever entitled to start under production at all:
 * {@link ProductionSeedRejectionCallback} refuses a production start against a database whose history
 * records a seed migration or whose tables still hold seeded rows.
 *
 * <p>The three are genuinely independent, and each covers what the others cannot. The location list
 * cannot help a database that was already seeded before production was pointed at it. The ceiling
 * cannot help either, and it is kept because it is the one control that still holds if a seed script
 * is ever renumbered or a seed location is ever added back by an operator override - it is the
 * belt behind the braces, not the mechanism. The applied-state check cannot stop a seed being applied
 * for the first time.
 *
 * <p>Both controls declared in three profile documents are statements, and the documents were the
 * whole of the mechanism until this class existed. <strong>Text is not a control.</strong> Three gaps
 * followed from having no code behind it, and closing them is this class's entire purpose.
 *
 * <p>Decision {@code DL-127} in {@code docs/decision-log.md} records why the location list is the
 * mechanism and why the ceiling is kept behind it, and decision {@code DL-110} records the sealing
 * callback this class registers for the two seeding profiles. The third control - a refusal to start
 * production against a database that was seeded before this process existed, which no configuration
 * value can reach - is {@link ProductionSeedRejectionCallback}, registered below for production alone.
 *
 * <p><strong>One point of history, recorded so the arrangement is not undone again.</strong> Two
 * earlier revisions of this class kept every script flat in the shared parent and held production back
 * with the version ceiling alone, on the ground that a recursively scanned directory cannot isolate a
 * seed from a schema. That ground is real, and it is exactly why the parent now holds no script at all:
 * the observation argues for moving the schema scripts down as well, not for abandoning the location
 * mechanism. A ceiling is a value a merged profile list, a command-line override or a copied overlay
 * block can raise, and each of those is a way for a seed to be applied that this class then has to
 * detect and refuse. A location that was never listed is not a value at all, and there is nothing to
 * detect. Both controls aim at one outcome - that the seeds are unreachable in production - and both
 * are now in force, with the location list first because it is the one the module plan specifies and
 * the one an operator cannot widen by mistake.
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
 * <h2>Gap two: a location list and a ceiling are only controls while nothing widens them</h2>
 *
 * <p>{@link #resolveTarget(Collection, String)} guards the ceiling retained behind the location list.
 * When production is active it <strong>refuses</strong> any ceiling that would reach version
 * {@value #FIRST_SEED_VERSION} or beyond - which includes {@value #SEEDING_TARGET}, a predefined
 * marker and an absent value, because Flyway migrates to the latest version when no target is set - so
 * a merged environment, an operator override or a copied overlay block cannot raise it in silence.
 * When local or test is active it <strong>lifts</strong> a ceiling that would stop short of the seeds,
 * because a fixture-bearing profile that stopped at the schema would migrate no fixtures. With neither
 * active the bound value is returned unchanged, which means the shared baseline's production posture
 * is what a profile-less start inherits.
 *
 * <p>{@link #resolveLocations(Collection, Collection)} is where the separation itself is made. When
 * production is active it <strong>refuses</strong> any resolved location other than
 * {@value #SCHEMA_LOCATION} - which refuses {@value #SEED_LOCATION} by name and refuses the shared
 * parent {@code classpath:db/migration} as well, because a location above the split reaches the seeds
 * recursively and a location beside it can carry a script no ceiling caps. When local or test is
 * active it <strong>completes</strong> both delivered locations if the bound list is missing either,
 * because a profile that resolved no schema location migrates nothing at all and one that resolved no
 * seed location loads none of the fixtures it exists for. With neither active the bound list is
 * returned unchanged, so a profile-less start inherits the shared baseline's schema-only posture.
 *
 * <p>Both resolutions fire on the <em>merged, bound</em> configuration rather than on any one
 * document, so an inherited value, an operator override on the command line and a co-activated
 * overlay are all covered by the same check.
 *
 * <h2>Gap three: a control reached only through the migration tool is a control with a switch</h2>
 *
 * <p>Both resolutions above are applied by a {@link FlywayConfigurationCustomizer}, and a customizer
 * is consulted only while the migration tool is being built. {@code spring.flyway.enabled=false}
 * builds no migration tool, so it applies no customizer, runs no callback, and switches off the
 * ceiling refusal, the location refusal and the already-seeded-database refusal together - three
 * controls disabled by one property, silently, with the application otherwise starting normally
 * against whatever the database already contains.
 *
 * <p>Two members close that path and neither depends on the migration tool existing.
 * {@link #requireCanonicalProductionMigrationSource(Environment)} reads the three settings straight
 * out of the environment in a {@link BeanFactoryPostProcessor} - before any bean is instantiated - and
 * requires all three to be stated and canonical, delegating the ceiling and the location list to the
 * same two resolutions rather than restating their rules.
 * {@link #requireUnseededProductionDatabase(JdbcTemplate)} runs
 * {@link ProductionSeedRejectionCallback}'s own inspection from an ordinary production singleton, so
 * the database is examined for seeded content on every production start-up whatever the migration
 * settings say.
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
 * <p>It sets no baseline setting, no validation setting and no clean setting of its own, and it does
 * not <em>supply</em> a location or a ceiling: the values live in the profile documents, where an
 * operator reads them, and supplying them here would create a second place for them to disagree. What
 * it does instead is <em>require</em> production's location, ceiling and enablement to be the canonical
 * ones and refuse the start-up otherwise. Those two postures are not the same, and the difference is
 * the point of this class: a profile document is a default, and a default can be overridden on the
 * command line, in the environment, or by a co-activated overlay. Leaving the ceiling and the location
 * to the documents alone would leave them to whatever the last property source said. So this class
 * refuses, completes and seals what those documents produced, and refuses to start at all when what
 * they produced for production is not what production must have.
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
 * <p>Those three are named here so the production migration can be audited by what it <em>admits</em>
 * as well as by what it excludes. Note where the guarantee that they exist actually rests:
 * {@link #resolveLocations(Collection, Collection)} requires the schema location and
 * {@link #resolveTarget(Collection, String)} refuses a ceiling only for reaching <em>up</em> into the
 * seeds, deliberately accepting one that stops lower, so that a schema script numbered above
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
     * The location every profile migrates from, holding {@code V1__create_schema.sql} and
     * {@code V2__create_indexes.sql}. Held as a constant so the value this class refuses to lose and
     * the value the documents declare cannot drift apart.
     *
     * <p>This is the location a production deployment resolves, and the only one. It is a sibling of
     * {@link #SEED_LOCATION} rather than its parent, which is the whole point: Flyway scans a location
     * recursively, so a parent that contained either directory would reach the seeds and the location
     * list would stop being a boundary. No script sits directly in the shared parent
     * {@code db/migration}, so listing this location resolves the schema and nothing else.</p>
     */
    public static final String SCHEMA_LOCATION = "classpath:db/migration/schema";

    /**
     * The location only the local and test profiles migrate from, holding
     * {@code V3__seed_reference_data.sql} and {@code V4__seed_user_security.sql}.
     *
     * <p>These two are what the whole separation exists for. The reference seed inserts fifty
     * synthetic customer rows carrying regulated identity data; the sign-on seed inserts ten known
     * identities, five of them administrative, whose stored credentials are all digests of one
     * well-known value. Production is refused this location by
     * {@link #resolveLocations(Collection, Collection)}, so neither script is resolved there - which
     * is the mechanism the module plan specifies, in preference to resolving them and declining to
     * run them.</p>
     */
    public static final String SEED_LOCATION = "classpath:db/migration/seed";

    /**
     * The classpath-relative path inside {@link #SCHEMA_LOCATION}. A location is recognised as the
     * module's schema location by this path rather than by string equality with the descriptor, so
     * a trailing separator, a differently spelled prefix or a file-system descriptor addressing the
     * same directory is still recognised - and anything else is not, including the shared parent.
     */
    private static final String SCHEMA_PATH = "db/migration/schema";

    /**
     * The classpath-relative path inside {@link #SEED_LOCATION}, recognised on the same terms as
     * {@link #SCHEMA_PATH}.
     */
    private static final String SEED_PATH = "db/migration/seed";

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

    /**
     * The property that switches the migration tool on. Production must declare it, and must declare
     * it {@code true}: with it {@code false} no migration bean is created, and the seeded-database
     * refusal that rides on the migration lifecycle never runs.
     */
    private static final String MIGRATIONS_ENABLED_KEY = "spring.flyway.enabled";

    /** The property carrying the migration ceiling, which production must declare exactly. */
    private static final String MIGRATION_TARGET_KEY = "spring.flyway.target";

    /** The property carrying the migration location list, which production must declare exactly. */
    private static final String MIGRATION_LOCATIONS_KEY = "spring.flyway.locations";

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
     * Publishes the guard that requires production to have declared the migration source this module
     * ships, before any bean is instantiated.
     *
     * <p>This guard exists because {@link #migrationScopeResolvingCustomizer(Environment)} cannot
     * reach the case that matters most. A {@link FlywayConfigurationCustomizer} is applied while the
     * migration tool is being configured, so it is only consulted when the migration tool is being
     * built at all. Declare {@code spring.flyway.enabled=false} and no migration bean is created, no
     * customizer is applied, and every control that rides on the migration lifecycle - the ceiling
     * refusal, the location refusal and the already-seeded-database refusal - is skipped in one move
     * by one property. A control that a single property can switch off is not a control.
     *
     * <p>The guard therefore reads the three settings straight out of the environment and refuses a
     * production start-up unless all three are stated and canonical:
     *
     * <ul>
     *   <li>{@value #MIGRATIONS_ENABLED_KEY} present and {@code true}, compared as text rather than
     *       converted to a boolean so that a value the converter would reject cannot put itself into
     *       the resulting diagnostic. Absence is refused rather than treated as the framework's
     *       {@code true} default, for the same reason the other two are: a production migration source
     *       is stated, not inferred.</li>
     *   <li>{@value #MIGRATION_TARGET_KEY} exactly {@value #SCHEMA_ONLY_TARGET}, enforced by handing
     *       the declared value to {@link #resolveTarget(Collection, String)} - the same rule the
     *       customizer applies, called rather than copied, so the two can never drift apart.</li>
     *   <li>{@value #MIGRATION_LOCATIONS_KEY} exactly the one packaged location, enforced by handing
     *       the bound list to {@link #resolveLocations(Collection, Collection)} for the same
     *       reason.</li>
     * </ul>
     *
     * <p>The list is read with {@link Binder} rather than
     * {@link Environment#getProperty(String, Class)} because a location list can be supplied as a
     * scalar, as a comma-separated value or as indexed entries, and only the binder resolves all
     * three. Reading it any other way would let an indexed override slip past unread, which is the
     * kind of gap this guard exists to close.
     *
     * <p>The method is {@code static} for the same reason {@link #profileActivationGuard(Environment)}
     * is: publishing a bean-factory post-processor from an instance method would put this
     * configuration class's own construction ahead of it and give up the earliness the guard depends
     * on.
     *
     * @param environment the environment supplying the active profiles and the three settings
     * @return a post-processor that refuses a non-canonical production migration source before any
     *         bean is instantiated
     */
    @Bean
    static BeanFactoryPostProcessor productionMigrationSourceGuard(final Environment environment) {
        return beanFactory -> requireCanonicalProductionMigrationSource(environment);
    }

    /**
     * Publishes the control that inspects a production database for seeded state on every production
     * start-up, whatever the migration settings say.
     *
     * <p>{@link ProductionSeedRejectionCallback} performs the same inspection earlier - before the
     * first script executes - but only when the migration tool runs. This bean closes the remaining
     * path: it is an ordinary singleton, so it is initialised during every production context refresh,
     * and it reuses the callback's own inspection rather than restating the queries, because two copies
     * of a disclosure rule is how one of them gets widened alone.
     *
     * <p>The inspection is skipped, with a recorded line, when the context publishes no
     * {@link JdbcTemplate}. That is not a bypass: no template means no data source, and no data source
     * means there is no database for a seeded row to be sitting in or for this application to read it
     * from. The configuration half of the guard is unconditional and runs regardless.
     *
     * @param jdbcTemplateProvider provider for the context's template, which supplies the connection
     * @return an initialising bean that refuses a production start-up over an already-seeded database
     */
    @Bean
    @Profile(PRODUCTION_PROFILE)
    InitializingBean productionSeededDatabaseGuard(
            final ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        return () -> requireUnseededProductionDatabase(jdbcTemplateProvider.getIfAvailable());
    }

    /**
     * Refuses a production start-up whose migration settings are not the ones this module ships.
     *
     * <p>Does nothing when production is not among the active profiles; the two seeding profiles exist
     * to apply the seeds and are governed by
     * {@link #resolveLocations(Collection, Collection)} instead.
     *
     * <p>Every refusal names only the keys and literals this class declares, per decision
     * {@code DL-041}. All three settings are operator-supplied, so echoing a supplied value would let
     * whoever supplied it write a chosen line into the start-up log.
     *
     * @param environment the environment to read
     * @throws IllegalStateException when production is active and any of the three settings is absent
     *                               or not canonical
     */
    static void requireCanonicalProductionMigrationSource(final Environment environment) {
        final List<String> activeProfiles = Arrays.asList(environment.getActiveProfiles());
        if (!containsProfile(activeProfiles, PRODUCTION_PROFILE)) {
            return;
        }
        // Read as text and compared, never converted. Asking the environment for a Boolean raises a
        // conversion failure whose own message quotes the offending value, which would put an
        // operator-supplied string into the start-up log and defeat decision DL-041. Comparing the raw
        // text refuses the same values and says only what this class declares.
        final String enabled = environment.getProperty(MIGRATIONS_ENABLED_KEY);
        if (enabled == null || !Boolean.TRUE.toString().equalsIgnoreCase(enabled.strip())) {
            throw new IllegalStateException("profile '" + PRODUCTION_PROFILE + "' must declare "
                    + MIGRATIONS_ENABLED_KEY + " 'true'. With migrations switched off no migration"
                    + " bean is created, and the controls that ride on the migration lifecycle stop"
                    + " with it: the ceiling refusal, the location refusal and the refusal to start"
                    + " over a database that already holds the reference-data seed or the ten known"
                    + " sign-on identities. One property must not be able to switch three controls"
                    + " off, so an absent or false value is refused rather than defaulted");
        }
        resolveTarget(activeProfiles, environment.getProperty(MIGRATION_TARGET_KEY));
        resolveLocations(activeProfiles, Binder.get(environment)
                .bind(MIGRATION_LOCATIONS_KEY, Bindable.listOf(String.class))
                .orElseGet(List::of));
        LOGGER.info("Production migration source accepted: migrations enabled, ceiling '{}',"
                + " one location '{}'", SCHEMA_ONLY_TARGET, SCHEMA_LOCATION);
    }

    /**
     * Refuses a production start-up over a database that already holds seeded content.
     *
     * <p>The inspection itself belongs to {@link ProductionSeedRejectionCallback} and is called here
     * rather than reproduced. The callback is stateless, so a fresh instance costs nothing and keeps
     * the three signals - an applied seed version, a reserved sign-on identifier, a seeded reference
     * volume - defined in exactly one place.
     *
     * @param jdbcTemplate the template to inspect through, or {@code null} when the context publishes
     *                     none
     * @throws IllegalStateException when the database cannot be read, because a state that could not
     *                               be established is refused rather than assumed clean
     */
    static void requireUnseededProductionDatabase(final JdbcTemplate jdbcTemplate) {
        if (jdbcTemplate == null) {
            LOGGER.info("No template published, so no production database was inspected for seeded"
                    + " state; the migration-source guard applies regardless");
            return;
        }
        final ProductionSeedRejectionCallback inspection = new ProductionSeedRejectionCallback();
        try {
            jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
                inspection.inspect(connection);
                return null;
            });
        } catch (final DataAccessException unreadable) {
            // The message names the operation and this class's own literals only. A diagnostic raised
            // over a database suspected of holding seeded personal data is the last place that data
            // should be copied to.
            throw new IllegalStateException("unable to establish whether the production database"
                    + " already holds seeded content; the start-up is refused rather than continued"
                    + " over a database whose state could not be read", unreadable);
        }
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
     * <p>This is where the schema and the seeds are actually separated, and it is a directory that
     * separates them. {@value #SCHEMA_LOCATION} holds {@code V1} and {@code V2};
     * {@value #SEED_LOCATION} holds {@code V3} and {@code V4}; no script sits in the shared parent, so
     * a list that names one sibling resolves that sibling's scripts and no others even though Flyway
     * scans recursively.
     *
     * <p>Three outcomes, and each is a different kind of statement:
     *
     * <ul>
     *   <li>Production active - any location that is not {@value #SCHEMA_LOCATION} or a path beneath
     *       it is <strong>refused</strong>. That refuses {@value #SEED_LOCATION} by name, and it
     *       refuses the shared parent {@code classpath:db/migration} too, because a location above the
     *       split reaches the seeds recursively and would put them back in scope. It also refuses an
     *       unrecognised location, which could carry scripts the retained ceiling never measured. The
     *       two seeds are what this refusal exists for: they insert fifty synthetic customer rows
     *       holding regulated identity data and ten known sign-on identities whose stored credentials
     *       are digests of one well-known value. It fires on the merged, bound list rather than on one
     *       document, so an inherited value, an operator override and a copied overlay block are all
     *       covered by the same check.</li>
     *   <li>Local or test active - {@value #SCHEMA_LOCATION} and {@value #SEED_LOCATION} are each
     *       <strong>completed</strong> if the bound list does not already resolve them, keeping the
     *       declared order and appending rather than replacing. Both halves matter: a profile that
     *       resolved no schema location migrates nothing at all, and one that resolved no seed location
     *       builds an empty schema and loads none of the fixtures the profile exists to provide.</li>
     *   <li>Neither active - the bound list is returned unchanged. A profile-less start migrates
     *       whatever the shared baseline declares, which is the schema location alone.</li>
     * </ul>
     *
     * <p>Nothing here refuses the shared parent under local or test, and nothing needs to: a list
     * holding both the parent and a child resolves the same script twice, and Flyway rejects a repeated
     * version with its own error before a single statement is executed. A loud failure from the tool
     * that owns the sequence is a better outcome than a second refusal in this class that could drift
     * away from it.
     *
     * @param activeProfiles    the active-profile list, which may be empty
     * @param declaredLocations the location descriptors the property binding produced, in order
     * @return the locations to migrate from, in order; the same values when nothing changed
     * @throws IllegalStateException when production is active and any location other than the schema
     *                               location is present
     */
    public static List<String> resolveLocations(final Collection<String> activeProfiles,
            final Collection<String> declaredLocations) {
        final List<String> declared = declaredLocations == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(declaredLocations));
        if (containsProfile(activeProfiles, PRODUCTION_PROFILE)) {
            // EXACTLY ONE location, and it must be the packaged schema location. Every other outcome
            // is refused: an empty list, a second entry, a blank entry, the seed location, the shared
            // parent of the two, and any descriptor that is not character for character the packaged
            // schema location. See isPackagedSchemaLocation for why nothing looser will do.
            if (declared.size() != 1 || !isPackagedSchemaLocation(declared.get(0))) {
                throw new IllegalStateException("profile '" + PRODUCTION_PROFILE
                        + "' must resolve exactly one migration location and it must be the packaged"
                        + " '" + SCHEMA_LOCATION + "'; it resolved " + declared.size()
                        + " location(s), of which the first is not that value. The two seed scripts"
                        + " live in '" + SEED_LOCATION + "', and their shared parent reaches them"
                        + " recursively, so both are refused by the same equality: those scripts"
                        + " insert fifty synthetic customer rows holding regulated identity data and"
                        + " ten known sign-on identities whose stored credentials are digests of one"
                        + " well-known value. Production is additionally capped at version '"
                        + SCHEMA_ONLY_TARGET + "'. A file-system descriptor, a bare path, a nested"
                        + " path, an additional entry and a blank entry are all refused, because each"
                        + " can present scripts the packaged sequence does not contain and the ceiling"
                        + " never measured");
            }
            return declared;
        }
        if (!containsProfile(activeProfiles, LOCAL_PROFILE)
                && !containsProfile(activeProfiles, TEST_PROFILE)) {
            return declared;
        }
        final List<String> completed = new ArrayList<>(declared);
        if (declared.stream().noneMatch(FlywayConfig::isSchemaLocation)) {
            completed.add(SCHEMA_LOCATION);
        }
        if (declared.stream().noneMatch(FlywayConfig::isSeedLocation)) {
            completed.add(SEED_LOCATION);
        }
        return completed.size() == declared.size()
                ? declared
                : Collections.unmodifiableList(completed);
    }

    /**
     * Resolves the migration ceiling for the active profiles.
     *
     * <p>Three outcomes, mirroring {@link #resolveLocations(Collection, Collection)}:
     *
     * <ul>
     *   <li>Production active - the ceiling must be exactly {@value #SCHEMA_ONLY_TARGET}; every other
     *       value is <strong>refused</strong>. Both edges of that equality carry weight. Above it,
     *       version {@value #FIRST_SEED_VERSION} and beyond admit the seeds, and {@value
     *       #SEEDING_TARGET}, any predefined marker, a malformed value and an absent value all reach
     *       them: Flyway migrates to the latest version when no target is set, so silence is the
     *       dangerous case rather than the safe one, and a value this method cannot read is refused
     *       rather than assumed harmless. Below it, a ceiling such as {@code 1.1} reaches no seed and
     *       would have satisfied a seeds-only check, yet it stops before the indexes and constraints
     *       are created and leaves an under-migrated schema behind a migration that reported success.
     *       Neither edge is delegated to the profile document: a profile document is a default, and a
     *       default can be overridden on the command line, in the environment or by a later property
     *       source, so the ceiling is enforced here in code where nothing downstream can relax it.</li>
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
     * @throws IllegalStateException when production is active and the ceiling is not exactly
     *                               {@value #SCHEMA_ONLY_TARGET}
     */
    public static String resolveTarget(final Collection<String> activeProfiles,
            final String declaredTarget) {
        final String declared = declaredTarget == null ? null : declaredTarget.strip();
        final boolean reachesSeeds = reachesSeedVersions(parseVersion(declared));
        if (containsProfile(activeProfiles, PRODUCTION_PROFILE)) {
            // EXACTLY the schema-only ceiling. This is one test with two edges, and both edges are
            // load-bearing. Above it lie the seeds. Below it lies an under-migrated schema: a ceiling
            // of '1.1' reaches neither seed and so would have satisfied a seeds-only check, yet it
            // stops before V2 and leaves the indexes and constraints that V2 creates absent - a
            // schema that reports a successful migration while missing the structures the application
            // and every alternate-index query depend on.
            // Two independent readings of the same requirement, and the ceiling must satisfy both: the
            // declared text is exactly the schema-only value, AND the value that text parses to is the
            // schema-only version and not a predefined marker. The text comparison is the strict one - it
            // refuses even '2.0', which parses to the same version - and the parsed comparison is the one
            // that stays meaningful if the text form is ever relaxed.
            if (!SCHEMA_ONLY_TARGET.equals(declared) || !isSchemaOnlyTarget(declared)) {
                throw new IllegalStateException("profile '" + PRODUCTION_PROFILE
                        + "' must declare spring.flyway.target exactly '" + SCHEMA_ONLY_TARGET
                        + "'. A ceiling that reaches version '" + FIRST_SEED_VERSION + "' or beyond"
                        + " admits the reference-data seed and the sign-on-identity seed, which"
                        + " insert fifty synthetic customer rows holding regulated identity data and"
                        + " ten known sign-on identities whose stored credentials are digests of one"
                        + " well-known value. A ceiling below '" + SCHEMA_ONLY_TARGET + "' is refused"
                        + " too, because it stops before the indexes and constraints are created and"
                        + " leaves an under-migrated schema behind a successful-looking migration. An"
                        + " absent, predefined or unreadable ceiling is refused for the same reason as"
                        + " a high one: Flyway migrates to the latest version when none is set");
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
     * Reports whether a location descriptor <strong>is</strong> the packaged migration location,
     * character for character after nothing more forgiving than trimming surrounding whitespace.
     *
     * <p>This is the predicate production uses, and it is deliberately an equality test rather than a
     * containment test. A containment test cannot separate the packaged directory from a directory
     * that merely has the same name somewhere along its path, and the difference decides whether the
     * scripts Flyway applies are the four this module ships or four an operator placed on disk. Every
     * one of the following is <strong>refused</strong>:
     *
     * <ul>
     *   <li>{@code filesystem:/tmp/anywhere/db/migration} - a file-system descriptor naming a
     *       directory outside the artifact. The scripts there are whatever is on that disk, and the
     *       production ceiling of {@value #SCHEMA_ONLY_TARGET} was measured against the packaged
     *       sequence, not against them: an operator-writable directory could present its own
     *       {@code V1} and {@code V2} carrying seed content the ceiling would then never stop.</li>
     *   <li>{@code db/migration} - a bare path with no prefix. Flyway's default prefix makes this
     *       resolve as a classpath location today, but the descriptor does not say so, and a
     *       production migration source must be stated rather than inferred.</li>
     *   <li>{@code classpath:db/migration/regional} - a nested sub-path. It resolves a
     *       <em>subset</em> of the packaged sequence, so a script the ceiling was measured against
     *       can be silently absent: the schema would come up short of version
     *       {@value #SCHEMA_ONLY_TARGET} while reporting success.</li>
     *   <li>{@code classpath:db/migration/} - a trailing separator, and any other spelling that is
     *       not the exact value. Accepting near-spellings is what obliges the predicate to
     *       normalise, and normalisation is what re-opens the containment weakness.</li>
     *   <li>{@code null} and blank - nothing to migrate from.</li>
     * </ul>
     *
     * <p>The descriptor is deliberately not parsed by {@link Location}. That parser raises its own
     * exception for a malformed descriptor and puts the descriptor into the message, which would hand
     * an operator-supplied value into a diagnostic and defeat decision {@code DL-041}. This predicate
     * answers {@code false} for anything that is not the one accepted value, and the production
     * refusal names the accepted value rather than echoing the rejected one.
     *
     * @param descriptor a location descriptor, which may be {@code null} or blank
     * @return {@code true} only when the descriptor is exactly {@value #SCHEMA_LOCATION}
     */
    private static boolean isPackagedSchemaLocation(final String descriptor) {
        return descriptor != null && SCHEMA_LOCATION.equals(descriptor.strip());
    }

    /**
     * Reports whether a location descriptor addresses the module's migration path or anything beneath
     * it.
     *
     * <p>Used only to decide whether the local and test completion step has anything left to add. It
     * is <strong>not</strong> the production predicate; production uses
     * {@link #isPackagedSchemaLocation(String)}, which is an exact match. The looseness here is
     * harmless because its only effect is to avoid appending a duplicate entry to a list that already
     * addresses the migration path, and because neither profile it serves ever runs against a
     * production database.
     *
     * @param descriptor a location descriptor, such as {@code classpath:db/migration}
     * @return {@code true} when the descriptor addresses the migration path
     */
    private static boolean isSchemaLocation(final String descriptor) {
        return addressesPath(descriptor, SCHEMA_PATH);
    }

    /**
     * Reports whether a location descriptor addresses the module's seed path or anything beneath it.
     *
     * @param descriptor a location descriptor, such as {@code classpath:db/migration/seed}
     * @return {@code true} when the descriptor addresses the seed path
     */
    private static boolean isSeedLocation(final String descriptor) {
        return addressesPath(descriptor, SEED_PATH);
    }

    /**
     * Reports whether a location descriptor addresses one classpath-relative path or anything
     * beneath it.
     *
     * <p><strong>Scope.</strong> This predicate serves the local and test completion step alone, and
     * the question it answers there is only "has the list already named the migration path, so that
     * appending the packaged location would duplicate it?". It must never be used to authorise a
     * production migration source: containment cannot distinguish the packaged directory from a
     * look-alike on disk, which is precisely the distinction production has to make. That is what
     * {@link #isPackagedSchemaLocation(String)} is for.
     *
     * <p>The match is on a whole path segment sequence rather than on the raw descriptor, so that
     * every spelling of the same directory is recognised: {@code classpath:db/migration/schema}, a
     * trailing separator, a nested sub-path such as {@code classpath:db/migration/schema/regional}, a
     * file-system descriptor pointing at the same directory on disk, and a bare
     * {@code db/migration/schema} with no prefix. A nested sub-path is deliberately accepted rather
     * than refused, because Flyway scans a location recursively: a descriptor beneath the given path
     * resolves a subset of the same scripts, so it is the same location for this predicate's purpose.
     * A location prefix is separated by a colon and a file-system descriptor may use back-slashes, so
     * both are normalised to the forward slash before the segment sequence is looked for - and because
     * the match requires a separator on both sides, a sibling whose name merely begins with the same
     * characters, such as {@code db/migration/schemas}, is not matched.
     *
     * <p>Two exclusions follow from matching a whole segment sequence, and both are load-bearing. A
     * sibling directory carries scripts outside the set this predicate stands for, so a production
     * profile that declared one must be refused rather than accepted. And the shared <em>parent</em>
     * {@code db/migration} is not matched by either delivered path either, which is precisely what
     * makes the production refusal cover it: a parent is scanned recursively and would reach the seed
     * scripts through the child directory, so it must not be mistaken for the schema location.
     *
     * <p>The descriptor is deliberately not parsed by {@link Location}. That parser raises its own
     * exception for a malformed descriptor and puts the descriptor into the message, which would
     * hand an operator-supplied value into a diagnostic and defeat decision {@code DL-041}. This
     * predicate answers {@code false} for anything it cannot recognise and lets the migration tool
     * itself deal with it.
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
     * Reports whether a ceiling is exactly the schema-only version.
     *
     * <p>Compared as a parsed version rather than as text, so that every spelling of the same version -
     * with or without a trailing zero component, for instance - is recognised as the one ceiling
     * production is allowed. An absent, blank or unreadable value answers {@code false}, which is the
     * fail-closed direction: it is refused rather than assumed to be the exact value.
     *
     * @param target the ceiling as declared, which may be {@code null} or blank
     * @return {@code true} only when the ceiling is the schema-only version
     */
    private static boolean isSchemaOnlyTarget(final String target) {
        final MigrationVersion parsed = parseVersion(target);
        return parsed != null && !parsed.isPredefined()
                && MigrationVersion.fromVersion(SCHEMA_ONLY_TARGET).equals(parsed);
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
