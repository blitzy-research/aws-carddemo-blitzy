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


/**
 * The single enforcement point for the two guarantees the module's schema evolution rests on: that a
 * production deployment can never reach the seed migrations, and that no seeded regulated identifier
 * is left at rest in cleartext.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>The five delivered migrations ship from <strong>two sibling locations whose shared parent holds no
 * script at all</strong>. {@value #SCHEMA_LOCATION} carries {@code V1__create_schema.sql},
 * {@code V2__create_indexes.sql} and {@code V2_2__add_protected_value_invariants.sql};
 * {@value #SEED_LOCATION} carries {@code V3__seed_reference_data.sql} and
 * {@code V4__seed_user_security.sql}; and {@value #SHARED_PARENT_LOCATION} carries neither, which is what
 * makes the arrangement work. The file names are unchanged, because each is a load-bearing Flyway log
 * token that a bring-up check reads out of the history table, and both directories remain inside the
 * module plan's own {@code db/migration/**.sql} delivery pattern, whose {@code **} anticipates nesting.
 *
 * <p><strong>The primary separation is the LOCATION LIST, and a location a profile never lists is not a
 * value an operator can widen.</strong> Production resolves {@value #SCHEMA_LOCATION} and nothing else,
 * so the seed scripts appear in no state whatsoever there - not applied, not pending, not even resolved.
 * Local and test resolve both locations and apply everything. The location list is the stronger of the
 * two statements available, because a version comparison first resolves a script and then declines to
 * run it, whereas a location a profile never lists produces no script to decline. It is not the only
 * statement made, though: production additionally pins its ceiling at {@value #PRODUCTION_TARGET}, so a
 * seed-numbered script would be excluded by its number even if some other location ever presented one.
 *
 * <p><strong>Why the shared parent must stay empty.</strong> A Flyway location is scanned
 * <em>recursively</em>, so a profile that resolves the parent reaches every child and the boundary becomes
 * notional - which is why the schema scripts moved down as well as the seeds, and why
 * {@link #resolveLocations(Collection, Collection)} refuses the parent under every profile. There is a
 * second reason it must be refused: Flyway records a script name relative to its location, so resolving
 * the parent would write {@code schema/V1__create_schema.sql} into the history where the bring-up check
 * expects {@code V1__create_schema.sql}.
 *
 * <p>The two seeds are what the separation exists for. The sign-on seed inserts ten known identities,
 * five of them administrative, whose stored credentials are all digests of one well-known value; the
 * reference seed inserts fifty synthetic customer rows carrying regulated identity data. Neither
 * belongs in a production database, and neither arriving there would be untidiness - it would be a
 * credential incident and a privacy incident respectively.
 *
 * <p><strong>Production also carries a version ceiling, and the two mechanisms are deliberately kept
 * together.</strong> {@code spring.flyway.target} is pinned at {@value #PRODUCTION_TARGET}, the highest
 * version {@value #SCHEMA_LOCATION} delivers, and {@link #resolveTarget(Collection, String)} refuses any
 * other production value. A pin on its own would freeze the schema - the day a schema script numbered
 * above it shipped, a production migration would stop below it and still report success - so the pin is
 * <em>checked</em> rather than merely written down: {@code FlywayConfigTest} asserts
 * {@value #PRODUCTION_TARGET} against the versions the schema location actually carries, so a schema
 * script added above the pin fails the build instead of being silently skipped at run time.
 *
 * <p><strong>Three independent controls hold the separation.</strong> The <em>location list</em> decides
 * what is resolved: {@link #resolveLocations(Collection, Collection)} pins production to
 * {@value #SCHEMA_LOCATION} by <em>equality</em>, refusing the seed location, the shared parent, a
 * second entry, a file-system descriptor, a bare path, a deeper path and a blank entry - because another
 * location, or an operator-writable directory that merely ends in the same folder name, could present
 * seed content of its own. The <em>version ceiling</em> decides how far a resolved list may be applied:
 * {@link #resolveTarget(Collection, String)} pins production at {@value #PRODUCTION_TARGET} and refuses
 * every other value, so even a location that presented a seed-numbered script could not have it applied.
 * The <em>applied-state check</em> decides whether this database was ever entitled to start under
 * production at all: {@link ProductionSeedRejectionCallback} refuses a production start against a
 * database whose history records a seed migration or whose tables still hold seeded rows.
 *
 * <p>The three are genuinely independent, and each covers what the others cannot. The location list
 * constrains <em>where</em> scripts come from and constrains no version. The ceiling constrains
 * <em>which</em> versions apply and constrains no directory. Neither can help a database that was
 * already seeded before production was pointed at it, and the applied-state check cannot stop a seed
 * being applied for the first time.
 *
 * <p>Both settings declared in the profile documents are statements, and the documents were the whole
 * of the mechanism until this class existed. <strong>Text is not a control.</strong> The gaps that
 * followed from having no code behind it, and closing them, are this class's entire purpose.
 *
 * <p>Decision {@code DL-298} in {@code docs/decision-log.md} records why the location list is the primary
 * mechanism, and {@code DL-334} records why the production ceiling is held alongside it rather than
 * removed, together with the delivered-version assertion that makes holding it safe. Decision
 * {@code DL-110} records the sealing callback -
 * {@code com.carddemo.service.SeededIdentifierSealingCallback}, which registers itself for the two
 * seeding profiles rather than being published from here, so that this package declares no dependency on
 * the service package.
 *
 * <p><strong>The nested split is a named structural decision and governs over a flat path listing.</strong>
 * The plan requires this class to resolve the seeds <em>from profile-scoped locations</em>, so the two
 * sibling directories stand even though the migration specifications elsewhere list flat paths. Both
 * controls are held rather than one: the ceiling is safe because it is asserted against the delivered
 * scripts, and neither control is asked to do the other's work.
 *
 * <h2>Gap one: the profile list is a list, and a list can hold both</h2>
 *
 * <p>Spring resolves overlapping property sources by activation order, so the last profile that
 * declares a key wins. Activating {@code prod,local} therefore starts a deployment that reads the
 * production overlay <em>and then</em> lets the local overlay overwrite whatever it also declares -
 * the lifted migration ceiling among it, along with repository-known signing and encryption material,
 * an emulator endpoint, and a relaxed transport rule. Activating {@code local,prod} reverses which
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
 * <h2>Gap two: a location list is only a control while nothing widens it</h2>
 *
 * <p>{@link #resolveLocations(Collection, Collection)} guards the location list, which is the
 * separating control. When production is active it <strong>refuses</strong> any resolved list other than
 * exactly {@value #SCHEMA_LOCATION}, matched by equality rather than containment, because a second
 * entry, the seed location, the shared parent, a file-system descriptor or an operator-writable
 * directory that merely ends in the same folder name could each present seed content of its own. When
 * local or test is active it <strong>completes</strong> whichever of the two delivered locations the
 * bound list is missing, because a profile that resolved no location migrates nothing at all and one
 * that resolved only half migrates either no schema or no fixtures. Under <em>every</em> profile,
 * including no profile, it refuses the shared parent, because scanning is recursive and because the
 * recorded script names would change.
 *
 * <p>{@link #resolveTarget(Collection, String)} guards the ceiling, which is the second control. When
 * production is active it <strong>refuses</strong> any value other than {@value #PRODUCTION_TARGET} and
 * re-applies that pin: a <em>higher</em> value, {@value #ALL_RESOLVED_VERSIONS_TARGET} among them, would
 * apply whatever a resolved location happened to carry above the delivered schema, and a <em>lower</em>
 * one stops before the indexes or the constraints are created and leaves an
 * under-migrated schema behind a migration that reported success. The pin cannot silently under-migrate a
 * future release either, because it is asserted against the versions {@value #SCHEMA_LOCATION} delivers
 * rather than merely written down. When local or test is active it <strong>lifts</strong> a ceiling that
 * would stop short of version {@value #FIRST_SEED_VERSION}, because a fixture-bearing profile that stopped
 * at the schema would migrate no fixtures. With neither active the bound value is returned unchanged.
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
 * produced by the module's own encryption service for {@code customer.govt_issued_id} and a second
 * fifty for {@code customer.cust_ssn}, so neither regulated column is ever seeded in cleartext.
 * {@link SeededIdentifierSealingCallback} is registered here for the two seeding profiles alone, and it
 * holds both columns to two distinct invariants.
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
 * the component enforcing them, because it resolves neither seed script and its ceiling stops below
 * both seed versions, so it receives no row from either.
 *
 * <h2>What this class deliberately does not do</h2>
 *
 * <p>It sets no baseline setting, no validation setting and no clean setting of its own, and it does
 * not <em>supply</em> a location or a ceiling: the values live in the profile documents, where an
 * operator reads them, and supplying them here would create a second place for them to disagree. What
 * it does instead is <em>require</em> production's location, ceiling and enablement to be the canonical
 * ones and refuse the start-up otherwise. Supplying a value and requiring one are not the same posture,
 * and the difference is
 * the point of this class: a profile document is a default, and a default can be overridden on the
 * command line, in the environment, or by a co-activated overlay. Leaving the ceiling and the location
 * to the documents alone would leave them to whatever the last property source said. So this class
 * refuses, completes and seals what those documents produced, and refuses to start at all when what
 * they produced for production is not what production must have.
 *
 * <p>It also does not add a versioned migration. Sealing the seeded identifiers as a fifth script would
 * have put a seeding step into a numbered sequence, and the sequence is now shared by two locations with
 * different audiences: a version in the seed location is invisible to production, and a version in the
 * schema location must never seed. A callback carries no version and belongs to neither location, so it
 * cannot be resolved by the wrong profile.
 *
 * <h2>What the two locations replace, and where each fact came from</h2>
 *
 * <p>Everything in {@value #SCHEMA_LOCATION} - which is exactly what a production deployment applies -
 * stands in for the ten {@code DEFINE CLUSTER} provisioning job
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
 * as well as by what it excludes. Where the guarantee that they exist rests is worth stating precisely,
 * because it rests on two members rather than one:
 * {@link #resolveLocations(Collection, Collection)} requires the packaged schema location, so the three
 * indexes are resolvable, and {@link #resolveTarget(Collection, String)} pins the ceiling at
 * {@value #PRODUCTION_TARGET} - at or above the version {@code V2__create_indexes.sql} occupies - so
 * they are also
 * <em>reached</em>. A lower ceiling would stop the migration before them and still report success, which
 * is why every other value is refused rather than merely discouraged. The pin is held to the versions
 * the location actually delivers by {@code FlywayConfigTest}, so raising the schema and forgetting the
 * pin fails the build; and a seed script is excluded twice over - by not being in the location production
 * resolves, and by being numbered above the pin.
 *
 * <p>Provenance: this configuration has no single legacy antecedent, because the separation it
 * enforces is one the legacy estate had no equivalent of - there, a provisioning job stream that
 * was simply never submitted was the whole of the protection. Those identities are cited by
 * position and by count alone: no job-stream text, no dataset-utility control statement and above
 * all no credential value carried by them appears anywhere in this file.
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
     * The location holding the schema scripts, and the only location a production deployment resolves:
     * {@code V1__create_schema.sql}, {@code V2__create_indexes.sql}, and every schema version added
     * after them.
     *
     * <p>Held as a constant so the value this class requires and the value the documents declare cannot
     * drift apart.
     */
    public static final String SCHEMA_LOCATION = "classpath:db/migration/schema";

    /**
     * The location holding the seed scripts, resolved by the local and test profiles alone:
     * {@code V3__seed_reference_data.sql} and {@code V4__seed_user_security.sql}.
     *
     * <p>Production {@link #resolveLocations(Collection, Collection) refuses} it, so under production
     * these scripts appear in no state whatsoever - not applied, not pending, not even resolved. That is
     * a stronger statement than a version comparison could make on its own, because a version comparison
     * first resolves the script and then declines to run it. Both scripts are numbered above
     * {@value #PRODUCTION_TARGET} as well, so the ceiling would decline them even if they were resolved.
     */
    public static final String SEED_LOCATION = "classpath:db/migration/seed";

    /**
     * The shared parent of the two locations above, which holds <strong>no script at all</strong> and
     * which <strong>no profile may resolve</strong>.
     *
     * <p>This constant exists to be refused rather than to be used. Flyway scans a location
     * <em>recursively</em>, so a profile that resolved this parent would reach both children and the
     * separation would be notional - which is precisely why two earlier attempts at a directory split
     * were withdrawn: both left the schema scripts in the parent, so the parent stayed reachable. With
     * the parent empty the recursion has nothing to cross. Resolving it would also change the recorded
     * script names, since Flyway records a script name relative to its location, so the history would
     * read {@code schema/V1__create_schema.sql} rather than {@code V1__create_schema.sql}.
     */
    public static final String SHARED_PARENT_LOCATION = "classpath:db/migration";

    /**
     * The classpath-relative path of {@link #SCHEMA_LOCATION}. A location is recognised by this path
     * rather than by string equality with the descriptor, so a trailing separator or a differently
     * spelled prefix is still recognised - and anything else is not.
     */
    private static final String SCHEMA_PATH = "db/migration/schema";

    /** The classpath-relative path of {@link #SEED_LOCATION}. */
    private static final String SEED_PATH = "db/migration/seed";

    /** The classpath-relative path of {@link #SHARED_PARENT_LOCATION}. */
    private static final String SHARED_PARENT_PATH = "db/migration";

    /**
     * The path separator a normalised location descriptor uses. A location is recognised by finding
     * its path bounded by this separator on both sides, which is what keeps a sibling directory whose
     * name begins with the same characters from matching.
     */
    private static final String SEGMENT_SEPARATOR = "/";

    /**
     * Flyway's own word for "apply every version the resolved locations contain", which the two seeding
     * profiles declare and production refuses.
     *
     * <p>Under local and test it is exactly right: those profiles resolve both locations and need every
     * delivered version, fixtures included. Under production it is refused, because production's ceiling
     * is {@value #PRODUCTION_TARGET} and an open marker would apply whatever a resolved location happened
     * to carry above the delivered schema.
     */
    public static final String ALL_RESOLVED_VERSIONS_TARGET = "latest";

    /**
     * The version a production migration stops at: the highest version {@value #SCHEMA_LOCATION}
     * delivers, which is the one {@code V2_2__add_protected_value_invariants.sql} occupies.
     *
     * <p><strong>The pin still excludes the seeds by arithmetic, and keeping that true is why the
     * protected-value invariants are numbered 2.2 rather than 5.</strong> Every
     * schema version sorts below every seed version: 1, 2 and 2.2 are structure, 3 and 4 are fixtures.
     * So a pin of 2.2 declines a seed-numbered script wherever it came from, and a future seed numbered 5
     * or 6 is declined by number as well as by directory.
     *
     * <p>NUMBERING A SCHEMA SCRIPT ABOVE THE SEEDS IS PROHIBITED, and the reasoning that argues for it -
     * the seed versions are already applied, so a dotted version below them would be out-of-order against
     * any database already holding 3 and 4 - is wrong on three counts, each worse than the one it avoids.
     * It breaks this arithmetic, leaving the pin above the seeds rather than beneath them. It breaks
     * {@link ProductionSeedRejectionCallback}, which reads seed contamination off the history as a
     * successful row at or above version {@value #FIRST_SEED_VERSION} - a test a schema script numbered 5
     * satisfies, so a correctly migrated production database would refuse to start, reporting seed data it
     * does not hold. And it breaks the arrangement this module ships: a database migrated production-shaped
     * and later resolving the seed location too would find 3 and 4 pending BELOW an
     * applied 5, which the tool refuses as out-of-order. See {@code docs/decision-log.md} DL-343.
     *
     * <p>The seed separation is therefore carried by both controls again, and by two more besides:
     * {@link #resolveLocations(Collection, List)} refuses under production any resolved location but
     * {@value #SCHEMA_LOCATION} by equality; {@code FlywayConfigTest} asserts which directory each
     * delivered script sits in, so a seed placed in the schema location fails the build; and
     * {@link ProductionSeedRejectionCallback} refuses a production start-up against a database that
     * actually holds seeded content, which is checked against the data rather than against a filename.
     *
     * <p><strong>Why a pin is held here at all, when the location list already separates the
     * seeds.</strong> The two controls constrain different things and neither substitutes for the other.
     * The location list says where scripts may come from and says nothing about versions; the pin says how
     * far a resolved list may be applied and says nothing about directories. Held together, a seed script
     * is excluded twice - by its directory and by its number - and a look-alike location that somehow
     * presented a seed-numbered script would still not have it applied.
     *
     * <p><strong>Why holding a pin is safe here, when the objection to one was sound.</strong> A number
     * written down and never checked does freeze the schema: the day a further schema script shipped above
     * it, a production migration would stop below that script, apply nothing, and report success - which is
     * exactly what would have happened to the invariants script had this constant stayed at 2. That failure
     * mode is closed by checking the number rather than by deleting it - {@code FlywayConfigTest} asserts
     * this constant against the versions {@value #SCHEMA_LOCATION} actually carries, so a schema script
     * added above the pin fails the build at the point it is added. Raising the schema and raising this
     * constant are therefore one commit, enforced, rather than two commits, hoped for.
     *
     * <p>Held as a constant so the value this class requires and the value the shared baseline and the
     * production overlay declare cannot drift apart.
     */
    public static final String PRODUCTION_TARGET = "2.2";

    /**
     * The first version a seed script occupies.
     *
     * <p>It is the figure a seeding profile is measured against, and that is now its only job: an inherited
     * ceiling below this version would migrate the schema and none of the fixtures the profile exists to
     * load, so {@link #resolveTarget(Collection, String)} lifts such a ceiling for local and test.
     *
     * <p>It is also the arithmetic boundary between the two locations, and it remains one: every version
     * the schema location delivers sorts below this figure and every version the seed location delivers is
     * at or above it. {@link #PRODUCTION_TARGET} records what depends on that and what happened when a
     * schema script was numbered above it instead.
     */
    private static final String FIRST_SEED_VERSION = "3";

    /**
     * The property that switches the migration tool on. Production must declare it, and must declare
     * it {@code true}: with it {@code false} no migration bean is created, and the seeded-database
     * refusal that rides on the migration lifecycle never runs.
     */
    private static final String MIGRATIONS_ENABLED_KEY = "spring.flyway.enabled";

    /**
     * The property carrying the migration target. Production must declare it as
     * {@value #PRODUCTION_TARGET} or not at all; every other value, the open marker included, is refused.
     */
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
     * necessary, refused. Both are resolved here because either one alone would leave a way for a seed
     * script to be applied: a raised ceiling applies a seed that is always resolved, and an
     * unrecognised location carries scripts whose numbering the ceiling was never measured against.
     *
     * @param environment the environment whose active-profile list selects the resolution
     * @return a customizer that refuses a seed-reaching ceiling or an unrecognised location under
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
            // UNDER PRODUCTION THE DECLARED CEILING IS READ FROM THE ENVIRONMENT, AND ONLY THERE.
            // The migration tool's own default ceiling is its head marker, so a BOUND value of `latest`
            // is indistinguishable from a document that declared it - and under production those two
            // must be treated differently: silence has to be CORRECTED to the pin, while a declared head
            // marker has to be REFUSED. The environment is the merged view of every property source, so
            // it answers "what did a document, an operator or a co-activated overlay actually state"
            // exactly, and null means nothing did. Under every other profile the bound value is resolved
            // as before, because nothing there is refused - a low ceiling is lifted - so the ambiguity
            // has no consequence and reading the bound value additionally catches one set programmatically
            // by some other customizer.
            final String declaredTarget = containsProfile(activeProfiles, PRODUCTION_PROFILE)
                    ? environment.getProperty(MIGRATION_TARGET_KEY)
                    : describe(boundTarget);
            final String resolvedTarget = resolveTarget(activeProfiles, declaredTarget);
            if (resolvedTarget != null) {
                final boolean changed = !resolvedTarget.equals(describe(boundTarget));
                // Set unconditionally rather than only when it differs. Under production the value is
                // already the pin by the time control reaches here - resolveTarget refused every other
                // value - so this is an idempotent re-statement, and re-stating it is what makes the
                // setting a property of this code path rather than of the document that supplied it.
                configuration.target(resolvedTarget);
                if (changed && containsProfile(activeProfiles, PRODUCTION_PROFILE)) {
                    LOGGER.info("Migration ceiling pinned at '{}' for the production profile, which is"
                            + " the highest version the packaged schema location delivers",
                            PRODUCTION_TARGET);
                } else if (changed) {
                    LOGGER.info("Migration ceiling lifted to '{}' so every resolved version applies",
                            resolvedTarget);
                } else if (containsProfile(activeProfiles, PRODUCTION_PROFILE)) {
                    LOGGER.info("Migration ceiling left at '{}' for the production profile; the seed"
                            + " scripts are held out by that version AND by the location list, and the"
                            + " pin is asserted against the delivered schema scripts",
                            PRODUCTION_TARGET);
                }
            }
        };
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
     * Publishes this module's own record of which versions a start-up applied and which it reached.
     *
     * <p>Unconditional, unlike the callback above, because the evidence it writes is read in every profile:
     * local validation reads it to confirm that all five delivered migrations were applied and that the
     * schema reached its highest delivered version, and a production start-up wants the same two facts for
     * the same reason. Nothing in it is profile-specific and nothing in it inspects data.
     *
     * <p>It exists so that evidence is not read out of the migration tool's own log text. The category that
     * carries the tool's opening announcement is held above the level it speaks at: those three lines name
     * the JDBC URL, the driver and the database type, so they would publish the host, the port, the
     * database name and the exact server and driver versions of the estate into the stream that leaves the
     * process. The lines a text-scraping validation would depend on come from a different category and
     * survive that raise, but depending on a third party's message text at all is a dependency a library
     * upgrade can break in silence. This callback removes it. See
     * {@code docs/decision-log.md} entry DL-311.
     *
     * @return the migration-lifecycle callback that records the versions this start-up applied
     */
    @Bean
    Callback migrationVersionRecordCallback() {
        return new MigrationVersionRecordCallback();
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
     *   <li>{@value #MIGRATION_TARGET_KEY} absent or exactly {@value #PRODUCTION_TARGET} and never any
     *       other value, enforced by handing the declared value to
     *       {@link #resolveTarget(Collection, String)} - the same rule the customizer applies, called
     *       rather than copied, so the two can never drift apart.</li>
     *   <li>{@value #MIGRATION_LOCATIONS_KEY} exactly the one packaged schema location, enforced by
     *       handing the bound list to {@link #resolveLocations(Collection, Collection)} for the same
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
        LOGGER.info("Production migration source accepted: migrations enabled, ceiling pinned at"
                + " version '{}', one location '{}' and no seed location",
                PRODUCTION_TARGET, SCHEMA_LOCATION);
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
                        + " a lifted migration ceiling to a deployment that reads the production"
                        + " overlay, in either activation order. Activate exactly one of them");
            }
        }
    }

    /**
     * Resolves the migration locations for the active profiles.
     *
     * <p><strong>This is where the schema and the seeds are separated, and it is the only place they
     * are.</strong> The schema scripts and the seed scripts sit in two sibling locations, neither inside
     * the other, and their shared parent holds no script at all - so a profile that resolves
     * {@value #SCHEMA_LOCATION} reaches exactly the schema and a profile that resolves both reaches
     * everything. A location a profile never lists is not a value an operator can widen, which is why
     * the separation lives here rather than in a version ceiling.
     *
     * <p>Three outcomes, and each is a different kind of statement:
     *
     * <ul>
     *   <li>Production active - any resolved list other than exactly one entry equal, character for
     *       character, to {@value #SCHEMA_LOCATION} is <strong>refused</strong>. That refuses an empty
     *       list, a second entry, a blank entry, a file-system descriptor, a bare path, a deeper
     *       sub-path, a trailing separator, {@value #SEED_LOCATION} and
     *       {@value #SHARED_PARENT_LOCATION} - the last because scanning is recursive, so the parent
     *       reaches the seeds through their own directory. Equality rather than containment is
     *       deliberate: containment cannot separate the packaged directory from an operator-writable
     *       directory that merely ends in the same folder name, and such a directory could present its
     *       own scripts carrying seed content. It fires on the merged, bound list rather than on one
     *       document, so an inherited value, an operator override and a copied overlay block are all
     *       covered by the same check.</li>
     *   <li>Local or test active - both {@value #SCHEMA_LOCATION} and {@value #SEED_LOCATION} are
     *       <strong>completed</strong> if the bound list does not already resolve them, keeping the
     *       declared order and appending rather than replacing, because a profile that resolved no
     *       location migrates nothing at all. {@value #SHARED_PARENT_LOCATION} is
     *       <strong>refused</strong> here too: it would apply the same five scripts and record them
     *       under different names, and the compose bring-up check reads those names out of the history
     *       table.</li>
     *   <li>Neither active - the bound list is returned unchanged, except that the shared parent is
     *       refused for that same naming reason. A profile-less start migrates whatever the shared
     *       baseline declares.</li>
     * </ul>
     *
     * <p>Nothing here refuses a duplicate entry under local or test, and nothing needs to: a list that
     * resolves the same script twice is rejected by Flyway's own repeated-version error before a single
     * statement is executed. A loud failure from the tool that owns the sequence is a better outcome
     * than a second refusal in this class that could drift away from it. The same holds for a schema
     * script and a seed script that were given the same version number: the tool reports the collision.
     *
     * @param activeProfiles    the active-profile list, which may be empty
     * @param declaredLocations the location descriptors the property binding produced, in order
     * @return the locations to migrate from, in order; the same values when nothing changed
     * @throws IllegalStateException when production is active and the resolved list is not exactly the
     *                               schema location, or when any profile resolves the shared parent
     */
    public static List<String> resolveLocations(final Collection<String> activeProfiles,
            final Collection<String> declaredLocations) {
        final List<String> declared = declaredLocations == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(declaredLocations));
        requireNoSharedParent(declared);
        if (containsProfile(activeProfiles, PRODUCTION_PROFILE)) {
            // EXACTLY ONE location, and it must be the packaged schema location. Every other outcome is
            // refused: an empty list, a second entry, a blank entry, the seed location, the shared
            // parent, and any descriptor that is not character for character the schema location. See
            // isPackagedSchemaLocation for why nothing looser will do.
            if (declared.size() != 1 || !isPackagedSchemaLocation(declared.get(0))) {
                throw new IllegalStateException("profile '" + PRODUCTION_PROFILE
                        + "' must resolve exactly one migration location and it must be the packaged"
                        + " '" + SCHEMA_LOCATION + "'; it resolved " + declared.size()
                        + " location(s), of which the first is not that value. The seed scripts sit in"
                        + " the sibling '" + SEED_LOCATION + "', which production never resolves, so a"
                        + " location this class cannot recognise is a location whose scripts nothing"
                        + " held back: those scripts insert fifty synthetic customer rows holding"
                        + " regulated identity data and ten known sign-on identities whose stored"
                        + " credentials are digests of one well-known value. A file-system descriptor,"
                        + " a bare path, a deeper path, the seed location, the shared parent, an"
                        + " additional entry and a blank entry are all refused, because each can"
                        + " present scripts the packaged schema sequence does not contain");
            }
            return declared;
        }
        if (!containsProfile(activeProfiles, LOCAL_PROFILE)
                && !containsProfile(activeProfiles, TEST_PROFILE)) {
            return declared;
        }
        // A seeding profile must resolve BOTH halves. Completing only one would leave the profile
        // either without a schema to seed into or without the fixtures it exists to load, and the
        // declared order is preserved so an operator's own ordering survives.
        final List<String> completed = new ArrayList<>(declared);
        if (declared.stream().noneMatch(FlywayConfig::isSchemaLocation)) {
            completed.add(SCHEMA_LOCATION);
        }
        if (declared.stream().noneMatch(FlywayConfig::isSeedLocation)) {
            completed.add(SEED_LOCATION);
        }
        return completed.equals(declared)
                ? declared
                : Collections.unmodifiableList(completed);
    }

    /**
     * Refuses a location list that resolves the shared parent of the two delivered locations, whatever
     * profile is active and however the parent is spelled.
     *
     * <p>Applied before the profile is examined because the reason is not a profile's. Flyway scans a
     * location recursively, so the parent reaches both children: under production it would apply the
     * seeds, and under a seeding profile it would apply the same five scripts while recording each one
     * under a name relative to the parent - {@code schema/V1__create_schema.sql} instead of
     * {@code V1__create_schema.sql}. The compose bring-up check reads those names out of the history
     * table, so the second case is a broken contract rather than a harmless equivalence.
     *
     * @param declared the location descriptors the property binding produced
     * @throws IllegalStateException when any entry addresses the shared parent
     */
    private static void requireNoSharedParent(final Collection<String> declared) {
        for (final String descriptor : declared) {
            if (isSharedParentLocation(descriptor)) {
                throw new IllegalStateException("no profile may resolve '" + SHARED_PARENT_LOCATION
                        + "'. It is the shared parent of '" + SCHEMA_LOCATION + "' and '"
                        + SEED_LOCATION + "' and holds no script itself, but a location is scanned"
                        + " RECURSIVELY, so resolving it reaches both children - which applies the seed"
                        + " scripts and additionally records every script under a name relative to the"
                        + " parent, breaking the migration names the bring-up check reads out of the"
                        + " history table. Declare '" + SCHEMA_LOCATION + "' alone, or that location"
                        + " together with '" + SEED_LOCATION + "'");
            }
        }
    }

    /**
     * Resolves the migration ceiling for the active profiles.
     *
     * <p>Three outcomes, mirroring {@link #resolveLocations(Collection, Collection)}:
     *
     * <ul>
     *   <li>Production active - the ceiling must be absent or exactly {@value #PRODUCTION_TARGET}, and
     *       every other value is <strong>refused</strong>; the method returns
     *       {@value #PRODUCTION_TARGET}. A <em>higher</em> ceiling is refused, the open marker
     *       {@value #ALL_RESOLVED_VERSIONS_TARGET} included, because it would apply whatever a resolved
     *       location happened to carry above the delivered schema - the seed versions among them, if a
     *       look-alike location ever presented one. A <em>lower</em> ceiling is refused because it stops
     *       before the indexes and constraints are created and leaves an under-migrated schema behind a
     *       migration that reported success. The known objection to pinning at all - that a number
     *       freezes the schema, so a later schema script numbered above it would never be applied while
     *       the migration still reported success - is answered by checking the pin rather than by removing
     *       it: {@value #PRODUCTION_TARGET} is asserted against the versions {@value #SCHEMA_LOCATION}
     *       delivers, so a schema script added above the pin fails the build at the point it is added
     *       rather than under-migrating a deployment months later.</li>
     *   <li>Local or test active - a ceiling that would stop short of the seeds is
     *       <strong>lifted</strong> to {@value #ALL_RESOLVED_VERSIONS_TARGET}, because an inherited
     *       ceiling reaching a seeding profile would migrate the schema and none of the fixtures the
     *       profile exists to load. This is the case that makes the shared baseline safe to pin: a
     *       profile that inherits {@value #PRODUCTION_TARGET} and needs the fixtures gets it lifted
     *       rather than refused, because completing is the useful outcome there where refusing is the
     *       useful outcome under production.</li>
     *   <li>Neither active - the bound value is returned unchanged.</li>
     * </ul>
     *
     * <p>The refusal names the one version literal this class declares and never the supplied value,
     * per decision {@code DL-041}: the ceiling is operator-supplied, so repeating it would let whoever
     * supplied it write a chosen line into the start-up log.
     *
     * @param activeProfiles the active-profile list, which may be empty
     * @param declaredTarget the ceiling the property binding produced, which may be {@code null}
     * @return the ceiling to migrate to; the same value when nothing changed
     * @throws IllegalStateException when production is active and a ceiling other than
     *                               {@value #PRODUCTION_TARGET} is declared
     */
    public static String resolveTarget(final Collection<String> activeProfiles,
            final String declaredTarget) {
        final String declared = declaredTarget == null ? null : declaredTarget.strip();
        final MigrationVersion parsed = parseVersion(declared);
        final boolean reachesSeeds = reachesSeedVersions(parsed);
        if (containsProfile(activeProfiles, PRODUCTION_PROFILE)) {
            // THE PIN, RE-APPLIED AFTER BINDING. Silence is accepted and corrected to the pin rather
            // than refused, because a profile that forgot the property must still migrate the delivered
            // schema and no further - refusing silence would only stop a deployment that had inherited
            // the right posture from the shared baseline. Every stated value other than the pin is
            // refused, in both directions: above it applies scripts this deployment was never measured
            // against, below it leaves the indexes and constraints uncreated behind a successful
            // migration. The open marker is a value above it and is refused with the rest.
            if (declared != null && !declared.isEmpty() && !isProductionCeiling(parsed)) {
                throw new IllegalStateException("profile '" + PRODUCTION_PROFILE
                        + "' must declare spring.flyway.target as '" + PRODUCTION_TARGET
                        + "' or not at all. That version is the highest one '" + SCHEMA_LOCATION
                        + "' delivers, and it is asserted against the delivered scripts rather than"
                        + " merely written down, so it cannot silently under-migrate a later release."
                        + " A HIGHER ceiling is refused - '" + ALL_RESOLVED_VERSIONS_TARGET + "'"
                        + " included - because it applies whatever a resolved location carries above the"
                        + " delivered schema, and the seed scripts are numbered there. A LOWER one is"
                        + " refused because it stops before the indexes and constraints are created and"
                        + " reports success anyway. The seed scripts are additionally held out by the"
                        + " location list: production resolves '" + SCHEMA_LOCATION + "' and never '"
                        + SEED_LOCATION + "'");
            }
            return PRODUCTION_TARGET;
        }
        if (!containsProfile(activeProfiles, LOCAL_PROFILE)
                && !containsProfile(activeProfiles, TEST_PROFILE)) {
            return declared;
        }
        return reachesSeeds ? declared : ALL_RESOLVED_VERSIONS_TARGET;
    }

    /**
     * Reports whether a parsed ceiling is the production pin.
     *
     * <p>Compared as a <em>version</em> rather than as text, so that the equivalent spellings a
     * deployment legitimately produces - {@code 2} and {@code 2.0} - are both recognised, while a
     * predefined marker never is: {@link MigrationVersion#isPredefined()} covers the latest, current,
     * next and empty markers, none of which is a number and none of which can be shown to stop at the
     * delivered schema.
     *
     * @param parsed the parsed ceiling, or {@code null} when it was absent, blank or unreadable
     * @return {@code true} when the ceiling is exactly the production pin
     */
    private static boolean isProductionCeiling(final MigrationVersion parsed) {
        return parsed != null && !parsed.isPredefined()
                && MigrationVersion.fromVersion(PRODUCTION_TARGET).equals(parsed);
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
     * scripts Flyway applies are the five this module ships or five an operator placed on disk. Every
     * one of the following is <strong>refused</strong>:
     *
     * <ul>
     *   <li>{@code filesystem:/tmp/anywhere/db/migration/schema} - a file-system descriptor naming a
     *       directory outside the artifact. The scripts there are whatever is on that disk, and nothing
     *       else holds the seeds out of production, so an operator-writable directory could simply
     *       present seed content of its own under a schema-looking name.</li>
     *   <li>{@code classpath:db/migration} - the shared parent. Scanning is recursive, so it reaches
     *       the seed directory. Refused twice over, here and in
     *       {@link #resolveLocations(Collection, Collection)}, because it is the single mistake that
     *       would undo the whole separation.</li>
     *   <li>{@code classpath:db/migration/seed} - the seed location itself.</li>
     *   <li>{@code db/migration/schema} - a bare path with no prefix. Flyway's default prefix makes
     *       this resolve as a classpath location today, but the descriptor does not say so, and a
     *       production migration source must be stated rather than inferred.</li>
     *   <li>{@code classpath:db/migration/schema/regional} - a deeper sub-path. It resolves a
     *       <em>subset</em> of the schema sequence, so a script can be silently absent and the schema
     *       comes up incomplete while reporting success.</li>
     *   <li>{@code classpath:db/migration/schema/} - a trailing separator, and any other spelling that
     *       is not the exact value. Accepting near-spellings is what obliges the predicate to
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
     * Reports whether a location descriptor addresses the schema path or anything beneath it.
     *
     * <p>Used only to decide whether the local and test completion step has anything left to add. It
     * is <strong>not</strong> the production predicate; production uses
     * {@link #isPackagedSchemaLocation(String)}, which is an exact match. The looseness here is
     * harmless because its only effect is to avoid appending a duplicate entry to a list that already
     * addresses the path, and because neither profile it serves ever runs against a production database.
     *
     * @param descriptor a location descriptor, such as {@code classpath:db/migration/schema}
     * @return {@code true} when the descriptor addresses the schema path
     */
    private static boolean isSchemaLocation(final String descriptor) {
        return addressesPath(descriptor, SCHEMA_PATH);
    }

    /**
     * Reports whether a location descriptor addresses the seed path or anything beneath it.
     *
     * <p>The completion counterpart of {@link #isSchemaLocation(String)}, for the other half of a
     * seeding profile's list.
     *
     * @param descriptor a location descriptor, such as {@code classpath:db/migration/seed}
     * @return {@code true} when the descriptor addresses the seed path
     */
    private static boolean isSeedLocation(final String descriptor) {
        return addressesPath(descriptor, SEED_PATH);
    }

    /**
     * Reports whether a location descriptor addresses the shared parent <strong>itself</strong>, as
     * opposed to either of its two children.
     *
     * <p>The distinction is the whole of this predicate. {@link #addressesPath(String, String)} matches
     * a path or anything beneath it, so the parent path matches both children as well - which is
     * exactly the recursion this arrangement is built around and exactly why it cannot be used here
     * unqualified. A descriptor is the parent only when it addresses the parent path and addresses
     * neither child, so {@code classpath:db/migration} is refused while
     * {@code classpath:db/migration/schema} is not.
     *
     * @param descriptor a location descriptor, which may be {@code null} or blank
     * @return {@code true} only when the descriptor addresses the shared parent and neither child
     */
    private static boolean isSharedParentLocation(final String descriptor) {
        return addressesPath(descriptor, SHARED_PARENT_PATH)
                && !addressesPath(descriptor, SCHEMA_PATH)
                && !addressesPath(descriptor, SEED_PATH);
    }

    /**
     * Reports whether a location descriptor addresses one classpath-relative path or anything
     * beneath it.
     *
     * <p><strong>Scope.</strong> This predicate serves the completion step and the shared-parent
     * refusal, and the question it answers is only "does this descriptor address that directory or
     * something inside it?". It must never be used to authorise a production migration source:
     * containment cannot distinguish the packaged directory from a look-alike on disk, which is
     * precisely the distinction production has to make. That is what
     * {@link #isPackagedSchemaLocation(String)} is for.
     *
     * <p>The match is on a whole path segment sequence rather than on the raw descriptor, so that
     * every spelling of the same directory is recognised: {@code classpath:db/migration/schema}, a
     * trailing separator, a nested sub-path such as {@code classpath:db/migration/schema/regional}, a
     * file-system
     * descriptor pointing at the same directory on disk, and a bare {@code db/migration} with no
     * prefix. A nested sub-path is deliberately accepted rather than refused, because Flyway scans a
     * location recursively: a descriptor beneath the given path resolves a subset of the same scripts,
     * so it is the same location for this predicate's purpose. A location prefix is separated by a
     * colon and a file-system descriptor may use back-slashes, so both are normalised to the forward
     * slash before the segment sequence is looked for - and because the match requires a separator on
     * both sides, a sibling whose name merely begins with the same characters, such as
     * {@code db/migrations} or {@code db/migration/schemas}, is not matched.
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
            return ALL_RESOLVED_VERSIONS_TARGET;
        }
        return version.getVersion();
    }
}
