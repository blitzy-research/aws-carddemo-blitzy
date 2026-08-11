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
package com.carddemo;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringApplicationRunListener;
import org.springframework.boot.autoconfigure.batch.JobLauncherApplicationRunner;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import com.carddemo.support.AbstractPostgresIT;

/**
 * Starts the delivered application against a real PostgreSQL 16 server carrying the real migrated
 * schema, and asserts the handful of things a start-up either gets right or gets wrong silently.
 *
 * <h2>Why this class exists, when a reflection test already inspects the entry point</h2>
 * {@code CardDemoApplicationTest} beside this one reads the entry point's declarations - its package,
 * its single annotation, its one method - without starting anything, and says so explicitly: the line
 * that hands control to the container is left unexecuted by design. That is the right shape for a unit
 * test, and it is deliberately silent about the only question a launch answers, which is whether the
 * container can be brought up at all. Every other integration test in this module loads a
 * <em>sliced</em> context assembled from a few named configurations, because a slice is the cheapest
 * way to assert one collaboration; none of them loads the application.
 *
 * <p>So until this class existed the module had no test that answered "does the artefact we ship
 * start?". The technical specification records that question as having been settled by a
 * <em>runtime probe</em> run by hand on the analysis toolchain, and a probe run by hand proves a claim
 * once and then decays. This class is that probe made repeatable, which is why it lives in the
 * integration tier against a real server rather than in the unit tier against a mock.</p>
 *
 * <h2>What is asserted, and why each one is a real failure mode rather than a formality</h2>
 * <ul>
 *   <li><strong>The context refreshes and publishes the entry point.</strong> A component scan
 *       narrowed by hand does not fail - it silently stops wiring whichever package was left out, and
 *       the application starts perfectly well with a layer missing.</li>
 *   <li><strong>All five delivered migrations are applied, and the application's own migration
 *       component agrees with the server.</strong> A location that resolves to nothing produces a
 *       clean start over an empty database rather than an error, so "the migration ran" has to be read
 *       back rather than assumed.</li>
 *   <li><strong>The eleven application tables exist, and only those eleven.</strong> Counted with the
 *       framework's own metadata tables and the migration history excluded - and with those exclusions
 *       proven non-vacuous by asserting the metadata tables really are there, so the filter is
 *       demonstrated rather than trusted.</li>
 *   <li><strong>The mapping validates against that schema.</strong> Validation is configured to run at
 *       start-up, so a column whose type drifted away from its record layout stops the context, and a
 *       refresh that completes <em>is</em> the validation result. This class pins the setting that
 *       makes that true, because the same context would start just as happily generating the schema
 *       from the mapping instead, which would prove nothing at all.</li>
 *   <li><strong>Nothing runs on start-up.</strong> Job launch here is on demand. Were the auto-run
 *       switch to flip, a start-up would quietly run the batch tier - against production data in a
 *       production environment - and would look exactly like a healthy start.</li>
 *   <li><strong>The entry point itself launches.</strong> The last group runs the real entry point,
 *       which is the only way to exercise the one line inside it and the only assertion here that is
 *       about the artefact rather than about a context a test framework built for it.</li>
 * </ul>
 *
 * <h2>Why the test tree is excluded from the scan, and why that is fidelity rather than a workaround</h2>
 * A deployed artefact's classpath contains production classes and libraries and nothing else. A
 * failsafe JVM's classpath additionally contains {@code target/test-classes}, and the scan implied by
 * {@code @SpringBootApplication} cannot tell the two apart: it reaches every type under the base
 * package on the classpath. This module's test tree holds around two dozen nested
 * {@code @Configuration} classes - eight of them enabling repositories over the same packages - that
 * exist to assemble sliced contexts for their own tests. Harvested into an application context they
 * collide, and the framework's own exclusion does not stop them: it excludes a type annotated as a
 * test component, a type carrying a test annotation, or a type enclosed by one of those, and these are
 * plain configurations enclosed by classes whose every test sits in a nested inner class - so the
 * enclosing class carries no test annotation and the exclusion walks off the end.
 *
 * <p>Reproducing the deployed classpath is therefore a precondition for this class to mean what it
 * says. It is done with the framework's own mechanism, a {@code TypeExcludeFilter} registered as a
 * singleton before refresh, which is exactly how the test framework installs its own exclusion. Two
 * alternatives were rejected: allowing bean definitions to be overridden would let a test double
 * replace a production bean and would leave this class asserting about a hybrid no deployment ever
 * builds; and loading a slice instead of the application would abandon the only question this class
 * exists to answer. Nothing about production is hidden by the exclusion, because production never sees
 * a test class. The mechanism is asserted rather than trusted - see {@code TheProductionOnlyClasspath}.
 *
 * <h2>Where the container comes from, and what this class deliberately does not declare</h2>
 * The server is owned by {@link AbstractPostgresIT}, which starts one PostgreSQL 16 instance for the
 * whole run, migrates it, and publishes its address so a context-booting subclass inherits it.
 * Honouring that contract, this class declares no container, no container extension, no data source
 * property source of its own and no context-dirtying annotation. It declares only its own
 * {@code @SpringBootTest} and the one initializer described above, and it reads the schema back through
 * the base class's own accessors - which is what those accessors were added for.
 *
 * <p>The web environment is mocked rather than served: a context-load check has no use for a bound
 * port, and taking one would make this class the reason a parallel build fails. The launch in the last
 * group asks for no web tier at all, for the same reason.</p>
 *
 * <h2>Why this class carries the {@code IT} suffix and must keep it</h2>
 * The build partitions the suite by file name into two strictly complementary sets: the fast tier
 * claims every {@code Test} suffix and explicitly excludes every {@code IT} suffix, and the tier that
 * runs after the artefact is built claims the {@code IT} suffix. This class needs a real server, so the
 * suffix is what routes it to the tier that provisions one. Renaming it would move a
 * container-dependent class into the tier that provisions nothing.
 *
 * <p>Provenance: this test has no legacy antecedent - the legacy estate carries no test harness of any
 * kind. The application it starts replaces an estate with no single process to start: online work was
 * reached through the transaction table of the CICS resource definition {@code app/csd/CARDDEMO.CSD} and
 * batch work by submitting job streams.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ContextConfiguration(initializers = CardDemoApplicationIT.ProductionOnlyClasspath.class)
@ActiveProfiles("test")
@DisplayName("the delivered application starts against the migrated schema, and starts nothing else")
class CardDemoApplicationIT extends AbstractPostgresIT {

    /**
     * The five delivered migration versions, in the order they must be applied.
     *
     * <p>Restated as literals rather than derived from the files on disk, which is the point: a derived
     * expectation moves whenever the thing it describes moves, and would keep passing if a migration
     * were renumbered, merged or dropped.</p>
     *
     * <p>Versions 1, 2 and 2.2 are schema and 3 and 4 are seeds, so every schema version sorts
     * below every seed version. The dotted version is the protected-value invariants, numbered BELOW the
     * seeds deliberately, for the reasons {@code docs/decision-log.md} DL-343 and DL-349 record. Version
     * 2.1 is absent: it belonged to a sign-on attempt ledger withdrawn with the throttle it served, and
     * the gap in the sequence is deliberate (DL-352).</p>
     */
    private static final List<String> DELIVERED_MIGRATION_VERSIONS =
            List.of("1", "2", "2.2", "3", "4");

    /**
     * The catalogue pattern matching the framework's own job-repository tables.
     *
     * <p>The underscore is escaped because it is a single-character wildcard in a pattern and the
     * server treats a backslash as the escape by default. Bound as a parameter rather than assembled
     * into the statement.</p>
     */
    private static final String METADATA_TABLE_PATTERN = "batch\\_%";

    /**
     * Two of the six job-repository tables, named so the exclusion above is proven non-vacuous.
     *
     * <p>Only two, and deliberately: the point is that the family exists and is excluded, not that the
     * framework's bundled script is unchanged. Pinning all six would make this class fail on a
     * framework upgrade that added a seventh, which is not a defect in this module.</p>
     */
    private static final List<String> SAMPLED_METADATA_TABLES =
            List.of("batch_job_execution", "batch_job_instance");

    /** The package every mapped entity must come from, so a narrowed entity scan cannot hide. */
    private static final String DOMAIN_PACKAGE = "com.carddemo.domain";

    /** The layered packages the scan must reach, so a hand-narrowed scan cannot pass unnoticed. */
    private static final List<String> LAYER_PACKAGES = List.of("com.carddemo.api",
            "com.carddemo.batch", "com.carddemo.config", "com.carddemo.repository",
            "com.carddemo.service");

    /** The mapping posture that makes a completed refresh a validation result. */
    private static final String VALIDATE_ONLY = "validate";

    /** The setting that decides whether a job runs as a side effect of starting up. */
    private static final String BATCH_AUTO_RUN_PROPERTY = "spring.batch.job.enabled";

    /** The value that setting must resolve to for a launch to process nothing. */
    private static final String AUTO_RUN_DISABLED = "false";

    /** The setting that decides the mapping posture. */
    private static final String DDL_AUTO_PROPERTY = "spring.jpa.hibernate.ddl-auto";

    /**
     * The setting that would turn on the second, script-driven schema mechanism.
     *
     * <p>Asserted absent rather than set to a value: two mechanisms owning one schema is the defect,
     * and an explicit "never" would still leave a maintainer a switch to flip.</p>
     */
    private static final String SCRIPT_INITIALISATION_PROPERTY = "spring.sql.init.mode";

    /** The one profile this module's container-backed tier runs under. */
    private static final String TEST_PROFILE = "test";

    /** The refreshed application context, which is the subject of most of what follows. */
    @Autowired
    private ApplicationContext applicationContext;

    /** The application's own migration component, as the context assembled it. */
    @Autowired
    private Flyway flyway;

    /** The persistence unit, whose successful creation is the mapping validation result. */
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    /** The resolved environment, read for the settings that make the assertions above meaningful. */
    @Autowired
    private Environment environment;

    /**
     * Reads back the job-repository tables the framework provisions for itself.
     *
     * <p>These are the framework's own tables, one of the two things
     * {@link AbstractPostgresIT#applicationTableNames()} filters out. Reading them here is what turns
     * that filter from an unexamined clause into a demonstrated one: if the family were absent the filter
     * would be excluding nothing, and the eleven-table count would be passing for the wrong reason. The
     * other exclusion is the migration history table; nothing this module itself delivers is filtered,
     * which {@link AbstractPostgresIT#operationalTableNames()} holds the server to.</p>
     *
     * @return the names of the metadata tables present on the shared server, in name order
     * @throws SQLException if the catalogue cannot be read
     */
    private static List<String> metadataTableNames() throws SQLException {
        final List<String> names = new ArrayList<>();
        try (Connection connection = connect();
                PreparedStatement query = connection.prepareStatement("""
                        SELECT table_name FROM information_schema.tables
                         WHERE table_schema = 'public'
                           AND table_name LIKE ?
                         ORDER BY table_name
                        """)) {
            query.setString(1, METADATA_TABLE_PATTERN);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    names.add(rows.getString(1));
                }
            }
        }
        return List.copyOf(names);
    }

    /**
     * Gives the application the classpath a deployment gives it, by hiding the test tree from the scan.
     *
     * <p>Registered as a singleton rather than published as a {@code @Bean}, and the difference is not
     * stylistic. The base exclusion filter is instantiated by the component-scan parser and asks the
     * bean factory for every filter of its own type at the moment it first matches - which happens while
     * configuration classes are being <em>parsed</em>. Bean definitions contributed by {@code @Bean}
     * methods are not registered until after that parse completes, so a {@code @Bean} filter would be
     * looked for before it existed and silently never applied. A singleton registered here exists before
     * refresh begins, which is the same reason the test framework registers its own exclusion this
     * way.</p>
     */
    static final class ProductionOnlyClasspath
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        /**
         * The name the filter is registered under.
         *
         * <p>Qualified with this class so it cannot collide with the exclusion the test framework
         * registers, which must stay in place: it is what keeps this test class's own nested types out
         * of the scan.</p>
         */
        static final String FILTER_BEAN_NAME =
                "com.carddemo.CardDemoApplicationIT.testTreeExcludeFilter";

        /** Restricts construction to the framework and to the launch group below. */
        ProductionOnlyClasspath() {
            // Intentionally empty: this initializer holds no state.
        }

        /**
         * Installs the exclusion into a context that has not yet been refreshed.
         *
         * <p>Additive: the exclusion the test framework installs is left exactly where it is, so both
         * are consulted. Called by the framework for the cached context, and directly by the launch
         * group for the context the entry point creates.</p>
         *
         * @param applicationContext the context being prepared; must not be null
         */
        @Override
        public void initialize(final ConfigurableApplicationContext applicationContext) {
            applicationContext.getBeanFactory()
                    .registerSingleton(FILTER_BEAN_NAME, new TestTreeExcludeFilter());
        }
    }

    /**
     * Excludes from component scanning every type whose compiled form lives in the test output tree.
     *
     * <p>The decision is made on the location of the class file rather than on any annotation, because
     * the annotation-based rule is precisely the one that does not hold here: the types that need
     * excluding are plain configurations whose enclosing classes carry no test annotation of their own.
     * Location is also the property that actually distinguishes this classpath from a deployment's -
     * production classes and library archives are somewhere else, so they are never matched.</p>
     */
    static final class TestTreeExcludeFilter extends TypeExcludeFilter {

        /** The URL scheme a class read from a directory on disk carries. */
        private static final String FILE_SCHEME = "file";

        /** The suffix of a compiled class resource. */
        private static final String CLASS_SUFFIX = ".class";

        /** The root of the test output tree, resolved once from this class's own compiled form. */
        private static final Path TEST_OUTPUT_ROOT = resolveTestOutputRoot();

        /** Restricts construction to the initializer above and to this class's own assertions. */
        TestTreeExcludeFilter() {
            // Intentionally empty: the root above is resolved statically.
        }

        /**
         * Locates the directory the test sources were compiled into.
         *
         * <p>Resolved from this very class's own compiled resource and then walked up one level per
         * package segment, so nothing about the build's output layout is assumed or spelled out.</p>
         *
         * @return the test output root as an absolute path
         * @throws IllegalStateException if this class's own compiled form cannot be located, which
         *         cannot happen while this class is running and is reported rather than ignored so the
         *         failure names itself instead of surfacing as an unexplained empty exclusion
         */
        private static Path resolveTestOutputRoot() {
            final String ownResource =
                    CardDemoApplicationIT.class.getName().replace('.', '/') + CLASS_SUFFIX;
            final URL located = CardDemoApplicationIT.class.getClassLoader().getResource(ownResource);
            if (located == null) {
                throw new IllegalStateException("Could not locate " + ownResource
                        + " on the class loader that is running it. The test-tree exclusion cannot be"
                        + " resolved without it, and an unresolved exclusion would let the test tree"
                        + " into the application context. Check that the test sources were compiled to"
                        + " a directory rather than packaged into an archive.");
            }
            final Path ownClassFile;
            try {
                ownClassFile = Path.of(located.toURI());
            } catch (final URISyntaxException malformed) {
                throw new IllegalStateException("The class loader reported " + located
                        + " for " + ownResource + ", which is not a usable file location. The"
                        + " test-tree exclusion cannot be resolved from it.", malformed);
            }
            Path root = ownClassFile.getParent();
            if (root == null) {
                throw new IllegalStateException(located + " has no parent directory, so the test"
                        + " output directory could not be identified from it.");
            }
            for (final String segment : CardDemoApplicationIT.class.getPackageName().split("\\.")) {
                root = root.getParent();
                if (root == null) {
                    throw new IllegalStateException("Walking up out of package "
                            + CardDemoApplicationIT.class.getPackageName() + " from " + located
                            + " ran past the file system root while resolving segment " + segment
                            + ", so the test output directory could not be identified.");
                }
            }
            return root;
        }

        /**
         * Decides whether one candidate type is to be hidden from the scan.
         *
         * <p>A type read from anywhere other than a directory on disk - a library archive, most
         * obviously - is never hidden, because nothing in this module's test tree is delivered that way.
         * A type read from a directory is hidden if that directory sits inside the test output root, and
         * the comparison is made between paths rather than between strings so that a directory whose
         * name merely begins with the root's name cannot match by accident.</p>
         *
         * @param metadataReader the reader for the candidate type; must not be null
         * @param metadataReaderFactory the factory the scanner supplies, unused because this rule reads
         *        only the candidate's own location and never has to resolve a related type
         * @return true if the candidate is to be excluded from component scanning
         * @throws IOException if the candidate's location cannot be resolved
         */
        @Override
        public boolean match(final MetadataReader metadataReader,
                final MetadataReaderFactory metadataReaderFactory) throws IOException {
            final URI location = metadataReader.getResource().getURI();
            if (!FILE_SCHEME.equalsIgnoreCase(location.getScheme()) || location.getPath() == null) {
                return false;
            }
            return Path.of(location).startsWith(TEST_OUTPUT_ROOT);
        }

        /**
         * Reports the root this filter is excluding, so an assertion can name it in a diagnostic.
         *
         * @return the test output root
         */
        static Path testOutputRoot() {
            return TEST_OUTPUT_ROOT;
        }
    }

    @Nested
    @DisplayName("the production-only classpath, demonstrated rather than trusted")
    class TheProductionOnlyClasspath {

        @Test
        @DisplayName("is installed in the context, because an exclusion that was never registered "
                + "would leave the test tree in the scan and nothing would say so")
        void isInstalledInTheContext() {
            assertThat(applicationContext.getBeanNamesForType(TypeExcludeFilter.class))
                    .as("the initializer registers the exclusion under this name before refresh. Its"
                            + " absence would mean the initializer never ran, and every assertion in"
                            + " this class would then be describing a context that had harvested the"
                            + " test tree rather than the application")
                    .contains(ProductionOnlyClasspath.FILTER_BEAN_NAME);
        }

        @Test
        @DisplayName("hides a type compiled into the test tree")
        void hidesATypeCompiledIntoTheTestTree() throws IOException {
            assertThat(excludes(CardDemoApplicationIT.class))
                    .as("this very class lives under %s, so a rule that does not exclude it excludes"
                            + " nothing at all", TestTreeExcludeFilter.testOutputRoot())
                    .isTrue();
        }

        @Test
        @DisplayName("and leaves a type compiled into the production tree alone, so the rule is not "
                + "simply excluding everything it is shown")
        void leavesAProductionTypeAlone() throws IOException {
            assertThat(excludes(CardDemoApplication.class))
                    .as("the entry point is production code. Excluding it would empty the context and"
                            + " would make the emptiness look like a passing exclusion")
                    .isFalse();
        }

        /**
         * Asks the exclusion, directly, what it makes of one type.
         *
         * @param type the type to offer the filter
         * @return whether the filter excludes it
         * @throws IOException if the type's compiled form cannot be read
         */
        private boolean excludes(final Class<?> type) throws IOException {
            final MetadataReaderFactory factory = new SimpleMetadataReaderFactory();
            return new TestTreeExcludeFilter()
                    .match(factory.getMetadataReader(type.getName()), factory);
        }
    }

    @Nested
    @DisplayName("the refreshed context")
    class TheRefreshedContext {

        @Test
        @DisplayName("exists, which is the whole of what a hand-run start-up probe proved and what "
                + "this class now proves on every build")
        void exists() {
            assertThat(applicationContext)
                    .as("a null context here cannot happen without the injection above having failed,"
                            + " so this is the premise every other assertion in this class rests on"
                            + " rather than a check of its own")
                    .isNotNull();
        }

        @Test
        @DisplayName("publishes the entry point as a bean, so the scan really reached the base package")
        void publishesTheEntryPoint() {
            assertThat(applicationContext.getBeanNamesForType(CardDemoApplication.class))
                    .as("the entry point is a configuration class as well as a launcher, so the"
                            + " container holds exactly one of it. None would mean the context was"
                            + " assembled from something other than the application; more than one"
                            + " would mean it was registered twice, and every bean it contributes"
                            + " would be defined twice with it")
                    .hasSize(1);
            assertThat(applicationContext.getBean(CardDemoApplication.class)).isNotNull();
        }

        @Test
        @DisplayName("reaches every layered package, so no scan has been narrowed by hand")
        void reachesEveryLayeredPackage() {
            assertThat(LAYER_PACKAGES).allSatisfy(layer -> assertThat(beanCountUnder(layer))
                    .as("no bean was found under %s. The entry point declares no explicit component,"
                            + " entity or repository scan precisely so that the scan implied by its"
                            + " one annotation reaches every package beneath it; a hand-written scan"
                            + " that omitted a package would start the application cleanly with that"
                            + " layer missing, which is the failure this asserts against", layer)
                    .isPositive());
        }

        @Test
        @DisplayName("runs under exactly the test profile, which is the premise of every setting read "
                + "below")
        void runsUnderExactlyTheTestProfile() {
            assertThat(applicationContext.getEnvironment().getActiveProfiles())
                    .as("every property assertion in this class describes what the test profile"
                            + " resolves to, so a second active profile would quietly turn those"
                            + " assertions into statements about something else")
                    .containsExactly(TEST_PROFILE);
        }

        /**
         * Counts the beans in the context whose implementation type comes from the given package.
         *
         * @param packageName the package to count under
         * @return how many bean definitions resolve to a type in that package
         */
        private long beanCountUnder(final String packageName) {
            return List.of(applicationContext.getBeanDefinitionNames()).stream()
                    .map(applicationContext::getType)
                    .filter(type -> type != null && type.getName().startsWith(packageName + "."))
                    .count();
        }
    }

    @Nested
    @DisplayName("the migrated schema the context started against")
    class TheMigratedSchema {

        @Test
        @DisplayName("carries every delivered version, in the order they were delivered, each recorded "
                + "as successful")
        void carriesEveryDeliveredVersionInOrder() throws SQLException {
            assertThat(appliedMigrationVersions())
                    .as("read from the server's own history, counting only rows the migration tool"
                            + " recorded as successful. The five versions create the schema, add the"
                            + " indexes, seed the reference rows and seed the sign-on identities; a"
                            + " missing one would leave the application running against a schema it"
                            + " was not written for, and a location that resolved to nothing would"
                            + " leave this list empty while the context still started cleanly")
                    .containsExactlyElementsOf(DELIVERED_MIGRATION_VERSIONS);
        }

        @Test
        @DisplayName("is the same set of versions the application's own migration component reports, "
                + "so the context is not looking somewhere else")
        void isTheSameSetTheApplicationReports() {
            final List<String> reportedByTheApplication = new ArrayList<>();
            for (final MigrationInfo applied : flyway.info().applied()) {
                if (applied.getVersion() != null && applied.getState() == MigrationState.SUCCESS) {
                    reportedByTheApplication.add(applied.getVersion().getVersion());
                }
            }

            assertThat(reportedByTheApplication)
                    .as("the assertion above reads the server; this one reads the component the"
                            + " context assembled, with the locations the profile gave it. Agreement"
                            + " is what rules out the case where the schema is right because someone"
                            + " else migrated it while the application's own configuration points at"
                            + " a location holding nothing")
                    .containsExactlyElementsOf(DELIVERED_MIGRATION_VERSIONS);
        }

        @Test
        @DisplayName("holds the eleven record-layout tables, no twelfth record table and no "
                + "operational table at all")
        void holdsTheElevenApplicationTables() throws SQLException {
            assertThat(applicationTableNames())
                    .as("the eleven record layouts of the estate become eleven tables. The comparison"
                            + " ignores order because the roster is in record-layout order while the"
                            + " catalogue is read in name order")
                    .containsExactlyInAnyOrderElementsOf(APPLICATION_TABLES);
            assertThat(operationalTableNames())
                    .as("and no delivered table carries no record layout. One did - the sign-on attempt"
                            + " ledger of a throttle the legacy transaction has no counterpart for - and"
                            + " it was withdrawn with that throttle, so this census exists to prove the"
                            + " schema stayed that way rather than to name an exception. DL-352")
                    .containsExactlyInAnyOrderElementsOf(OPERATIONAL_TABLES);
        }

        @Test
        @DisplayName("holds the framework's own metadata tables too, which is what makes excluding "
                + "them from that count a real exclusion")
        void holdsTheFrameworksMetadataTablesAsWell() throws SQLException {
            assertThat(metadataTableNames())
                    .as("the framework provisions these itself, from its own bundled script, because"
                            + " every shipped profile asks it to. Were they absent, the exclusion in"
                            + " the count above would be filtering nothing and the eleven-table"
                            + " assertion would be passing for a reason unrelated to the schema")
                    .containsAll(SAMPLED_METADATA_TABLES);
            assertThat(applicationTableNames())
                    .as("and none of them may leak into the application inventory")
                    .doesNotContainAnyElementsOf(SAMPLED_METADATA_TABLES);
        }
    }

    @Nested
    @DisplayName("the mapping, validated against that schema rather than imposed on it")
    class TheValidatedMapping {

        @Test
        @DisplayName("was checked at start-up, so a refresh that completed is the check having passed")
        void wasCheckedAtStartUp() {
            assertThat(environment.getProperty(DDL_AUTO_PROPERTY))
                    .as("this setting is what makes a completed refresh mean anything. Generating or"
                            + " updating the schema from the mapping would start just as cleanly and"
                            + " would prove only that the mapping agrees with itself")
                    .isEqualTo(VALIDATE_ONLY);
            assertThat(entityManagerFactory)
                    .as("the persistence unit is what performs that check while it is being built, so"
                            + " its presence in the context is the result")
                    .isNotNull();
        }

        @Test
        @DisplayName("maps one entity per application table, all of them from the domain package")
        void mapsOneEntityPerApplicationTable() {
            final Set<EntityType<?>> entities = entityManagerFactory.getMetamodel().getEntities();

            assertThat(entities)
                    .as("eleven record layouts, eleven tables, eleven entities. A count that drifted"
                            + " apart from the table roster would mean either a table nothing maps or"
                            + " an entity mapped onto something the migration does not create")
                    .hasSize(APPLICATION_TABLES.size());
            assertThat(entities).allSatisfy(entity -> assertThat(entity.getJavaType().getPackageName())
                    .as("every entity belongs to the domain layer. One from anywhere else would mean"
                            + " a record layout had been declared outside the package that owns them")
                    .isEqualTo(DOMAIN_PACKAGE));
        }

        @Test
        @DisplayName("is the only mechanism touching the schema, so the script-driven one stays off")
        void isTheOnlyMechanismTouchingTheSchema() {
            assertThat(environment.getProperty(SCRIPT_INITIALISATION_PROPERTY))
                    .as("no shipped profile declares this setting, and the absence is the posture:"
                            + " versioned migration owns the schema alone. A second mechanism would"
                            + " apply on every start-up, outside any version history")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("nothing that runs work runs at start-up")
    class NothingRunsAtStartUp {

        @Test
        @DisplayName("the automatic job launch is off at the setting that decides it")
        void theAutomaticJobLaunchIsOff() {
            assertThat(environment.getProperty(BATCH_AUTO_RUN_PROPERTY))
                    .as("read from the resolved environment rather than from a file, because this is"
                            + " the value the condition behind the automatic launcher actually sees")
                    .isEqualTo(AUTO_RUN_DISABLED);
        }

        @Test
        @DisplayName("so the automatic launcher was never created")
        void theAutomaticLauncherWasNeverCreated() {
            assertThat(applicationContext.getBeanNamesForType(JobLauncherApplicationRunner.class))
                    .as("the framework creates this only when the setting above is on, so its absence"
                            + " is the setting having had its effect rather than merely its value."
                            + " Asserted as an absent bean rather than as an empty job history,"
                            + " because the shared server is reused across this whole run and another"
                            + " test's job rows are none of this test's business")
                    .isEmpty();
        }

        @Test
        @DisplayName("and no start-up task of any other kind was created either")
        void noStartUpTaskOfAnyOtherKindWasCreated() {
            assertThat(applicationContext.getBeanNamesForType(ApplicationRunner.class))
                    .as("a start-up task is the other way work can run as a side effect of a launch,"
                            + " and the delivered application declares none of either kind")
                    .isEmpty();
            assertThat(applicationContext.getBeanNamesForType(CommandLineRunner.class)).isEmpty();
        }
    }

    @Nested
    @DisplayName("the entry point itself, run the way a deployment runs it")
    class TheEntryPointItself {

        @Test
        @DisplayName("brings up a context of its own and hands it back healthy")
        void bringsUpAContextOfItsOwn() {
            final AtomicReference<ConfigurableApplicationContext> launched = new AtomicReference<>();
            try {
                SpringApplication.withHook(application -> capturingListener(launched),
                        () -> CardDemoApplication.main(launchArguments()));
                final ConfigurableApplicationContext started = launched.get();

                assertThat(started)
                        .as("the entry point returned without throwing but no context was ever"
                                + " prepared, which would mean the launch was intercepted by"
                                + " something other than the container")
                        .isNotNull();
                assertThat(started.isActive())
                        .as("a launch that failed would have been reported as a thrown failure, so an"
                                + " inactive context here means it was closed behind the caller's back")
                        .isTrue();
                assertThat(started.getEnvironment().getActiveProfiles())
                        .as("the entry point selects no profile of its own - the same artefact is meant"
                                + " to run against a local stack, a test harness or a production"
                                + " environment on an externally chosen profile - so the profile named"
                                + " on the command line is the one that must take effect")
                        .containsExactly(TEST_PROFILE);
                assertThat(started.getBean(CardDemoApplication.class))
                        .as("the launched context must be the application's own, not a bare one")
                        .isNotNull();
            } finally {
                closeQuietly(launched.get());
            }
        }

        /**
         * Builds the arguments a deployment-shaped launch needs in a test process.
         *
         * <p>Every one of them is either the address of the server this run already owns or a setting
         * that keeps a second application inside one test process from colliding with the first. None
         * of them changes what is under test: whether the entry point can bring the container up.</p>
         *
         * <p>The address is taken from the running container rather than from a file, because the file
         * deliberately does not declare it - an ephemeral port is unknowable before the run, and a
         * reachable default written down would let a launch that started no container connect to a
         * developer's own database and look healthy. The port is asked for as zero so the servlet
         * container takes whatever is free, which is what keeps two builds on one machine, or two
         * contexts in one build, from fighting over it. The web tier is deliberately <em>not</em>
         * switched off: it is what a deployment runs, and the security filter chain the application
         * declares is only assembled in a servlet context, so switching it off would quietly skip the
         * part of the wiring most worth proving.</p>
         *
         * <p>The connection pool is left at the application's own setting. Capping it was tried and is
         * wrong: the migration component holds one connection while it opens a second for its callbacks,
         * so a pool of two deadlocks against itself and reports a connection timeout that looks like a
         * database fault. The context is closed as soon as the assertions finish, which is the honest
         * way to keep the shared server's connection budget available to the rest of the run.</p>
         *
         * @return the command line to hand the entry point
         */
        private String[] launchArguments() {
            return new String[] {
                "--spring.profiles.active=" + TEST_PROFILE,
                "--server.port=0",
                "--spring.main.register-shutdown-hook=false",
                "--spring.datasource.url=" + jdbcUrl(),
                "--spring.datasource.username=" + databaseUser(),
                "--spring.datasource.password=" + databasePassword(),
            };
        }

        /**
         * Builds the run listener that gives this test a handle on the context the entry point creates.
         *
         * <p>The entry point discards the context it starts, which is correct for a launcher and
         * inconvenient for a test, so the handle is taken through the framework's own hook rather than
         * by changing the entry point to suit the test. The callback used runs after the initializers
         * and before the refresh, which is both early enough to give this context the same
         * production-only classpath the cached context above was given, and late enough that the object
         * captured is the real one.</p>
         *
         * @param captured where to put the context when it appears
         * @return the listener to hand the hook
         */
        private SpringApplicationRunListener capturingListener(
                final AtomicReference<ConfigurableApplicationContext> captured) {
            return new SpringApplicationRunListener() {
                @Override
                public void contextPrepared(final ConfigurableApplicationContext prepared) {
                    new ProductionOnlyClasspath().initialize(prepared);
                    captured.set(prepared);
                }
            };
        }

        /**
         * Closes a launched context if there is one.
         *
         * <p>Closing is this test's own responsibility because the launch asks for no shutdown hook: a
         * hook would leave the context alive until the process ended, holding connections that the rest
         * of this run needs. A null handle is not an error here - the assertion above has already
         * reported it - so this only has to not add a second failure on top of the first.</p>
         *
         * @param context the context to close, or null if none was captured
         */
        private void closeQuietly(final ConfigurableApplicationContext context) {
            if (context != null) {
                context.close();
            }
        }
    }

}
