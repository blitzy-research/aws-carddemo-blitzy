/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.carddemo.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.support.AbstractPostgresIT;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The migration plan's validation gates, asserted rather than described.
 *
 * <h2>Why this class exists</h2>
 * Every gate the plan defines has a mechanism, and until this class three of them had no assertion. The
 * named-artefact requirement of Gate 4 says the validation artefacts must be specified <em>by name</em> -
 * a requirement that prose alone can only claim to satisfy - so the names, the measured byte counts and
 * the measured cardinalities are asserted here against the shipped resources. The unsafe-code audit of
 * Gate 6 commits to counts that were previously a paragraph in a document and are now a build-failing
 * check. And Gate 2's zero-warning guarantee rests on two compiler arguments in the build file, which
 * nothing verified were still there.
 *
 * <h2>What is asserted here and what is asserted elsewhere</h2>
 * Duplication would be worse than absence, because two assertions of one fact drift apart and the weaker
 * one wins. So this class deliberately does not restate what a narrower suite already holds:
 * <ul>
 *   <li>the nine input fixtures' record counts, widths, filler rules and content digests belong to
 *       {@code support/FixtureContractTest};</li>
 *   <li>the golden files' record-level reproduction belongs to
 *       {@code support/ExpectedOutputFixtureContractTest} and
 *       {@code support/ExpectedHtmlStatementFixtureContractTest};</li>
 *   <li>the whole-file byte equality of Gate 1 belongs to {@link BatchPipelineE2ETest};</li>
 *   <li>the sign-on message contract and the job-submission card image of Gate 5 belong to
 *       {@link OnlineTransactionE2ETest};</li>
 *   <li>the lookup sets' <em>semantics</em> - which code is valid, and the partition being exact -
 *       belong to {@code service/ValidationLookupServiceTest}.</li>
 * </ul>
 * What is left, and what this class holds, is the gate-level inventory: that the named artefacts are
 * present under the names the plan gives them, at the sizes it measured; that the shipped lookup
 * resources carry the cardinalities it counted, read out of the resource files rather than out of the
 * service that also loads them; that the credential seed applied to a real server holds exactly ten
 * identities under the two roles; and that the build still enforces what Gates 2, 6, 7 and 8 rest on.
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Every figure below is a measurement of a
 * committed artefact or a count of a legacy resource, which is metadata; no legacy source text appears.
 */
@DisplayName("The migration plan's validation gates, asserted")
class GateVerificationTest extends AbstractPostgresIT {

    /** Module-relative path of the build file the compiler and gate plugins are configured in. */
    private static final String BUILD_FILE = "pom.xml";

    /** Class-path directory holding the named input artefacts. */
    private static final String INPUT_DIRECTORY = "/fixtures/input/";

    /** Class-path directory holding the golden output artefacts. */
    private static final String GOLDEN_DIRECTORY = "/fixtures/expected/";

    /** Class-path directory holding the externalised validation lookup tables. */
    private static final String LOOKUP_DIRECTORY = "/lookup/";

    /** Module-relative root of the committed fixtures, for the flat-layout assertion. */
    private static final String FIXTURE_ROOT = "src/test/resources/fixtures";

    /** Creates the specification. */
    GateVerificationTest() {
        super();
    }

    // ===============================================================================================
    // GATE 2
    // ===============================================================================================

    @Nested
    @DisplayName("Gate 2 - the zero-warning build is enforced by the compiler, not by review")
    class GateTwo {

        /** Creates the nested specification. */
        GateTwo() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("the build file compiles at release 25 with all lint categories on and every "
                + "warning promoted to an error")
        void theCompilerIsConfiguredToFailOnAnyWarning() throws IOException {
            final String build = Files.readString(Path.of(BUILD_FILE), StandardCharsets.UTF_8);

            assertThat(build)
                    .as("the release is the language and platform level the plan pins")
                    .contains("<release>25</release>");
            assertThat(build)
                    .as("every lint category is on, or a warning in a category nobody enabled is "
                            + "silently tolerated")
                    .contains("-Xlint:all");
            assertThat(build)
                    .as("a warning must stop the build; without this the gate is an assertion about "
                            + "somebody's attention")
                    .contains("-Werror");
        }
    }

    // ===============================================================================================
    // GATE 4
    // ===============================================================================================

    @Nested
    @DisplayName("Gate 4 - the validation artefacts are named, present, and the size they were measured")
    class GateFour {

        /** Creates the nested specification. */
        GateFour() {
            // Intentionally empty.
        }

        /**
         * The nine named sequential inputs and the measured byte count of each.
         *
         * <p>The requirement is that the artefacts be specified <em>by name</em>, so the names are
         * written out one per case rather than discovered by listing a directory: a directory listing
         * would still pass if a name changed, which is the failure this discharges.
         *
         * @param fileName      the artefact's name, as the plan gives it
         * @param expectedBytes its measured size
         * @throws IOException if the artefact cannot be read
         */
        @ParameterizedTest(name = "{0} is present and is {1} bytes")
        @CsvSource({
            "acctdata.txt,   15050",
            "carddata.txt,    7550",
            "cardxref.txt,    1850",
            "custdata.txt,   25050",
            "dailytran.txt, 105300",
            "discgrp.txt,     2601",
            "tcatbal.txt,     2550",
            "trancatg.txt,    1098",
            "trantype.txt,     427"})
        @DisplayName("each of the nine named ASCII validation artefacts")
        void eachNamedInputArtefactIsPresentAtItsMeasuredSize(final String fileName,
                final int expectedBytes) throws IOException {
            assertThat(classpathBytes(INPUT_DIRECTORY + fileName))
                    .as("%s is a named Gate 4 artefact", fileName)
                    .hasSize(expectedBytes);
        }

        /**
         * The four golden outputs, at the four legacy widths, separator-free.
         *
         * @param fileName     the golden's name
         * @param recordWidth  its record width, which is the entire stride
         * @param recordCount  the records it holds
         * @throws IOException if the golden cannot be read
         */
        @ParameterizedTest(name = "{0} is {2} records of {1} bytes with no separator")
        @CsvSource({
            "daily-reject.txt,       430,   38",
            "transaction-report.txt, 133,  519",
            "statement.txt,           80, 1262",
            "statement-html.txt,     100, 6632"})
        @DisplayName("each of the four golden outputs, at its legacy width")
        void eachGoldenIsPresentAtItsLegacyWidth(final String fileName, final int recordWidth,
                final int recordCount) throws IOException {
            final byte[] golden = classpathBytes(GOLDEN_DIRECTORY + fileName);

            assertThat(golden)
                    .as("%s must be exactly %d records of %d bytes", fileName, recordCount,
                            recordWidth)
                    .hasSize(recordCount * recordWidth);
            final String whole = new String(golden, StandardCharsets.US_ASCII);
            assertThat(whole)
                    .as("%s stands for a fixed-length dataset, so it carries no line feed", fileName)
                    .doesNotContain("\n");
            assertThat(whole)
                    .as("%s carries no carriage return either", fileName)
                    .doesNotContain("\r");
        }

        @Test
        @DisplayName("the credential artefact is present and holds the ten identities the seed applies")
        void theCredentialArtefactHoldsTenIdentities() throws IOException {
            final byte[] credentials = classpathBytes(INPUT_DIRECTORY + "usrsec.txt");

            assertThat(credentials)
                    .as("ten records of the 80-byte user-security layout")
                    .hasSize(10 * 80);
        }

        @Test
        @DisplayName("the seed applied to a real server holds exactly ten identities, five holding the "
                + "administrative role and five the ordinary one, and no credential in clear")
        void theAppliedSeedHoldsTenIdentitiesUnderTwoRoles() throws SQLException {
            final List<String> identifiers = column("SELECT sec_usr_id FROM user_security ORDER BY 1");
            final List<String> types = column("SELECT sec_usr_type FROM user_security ORDER BY 1");
            final List<String> secrets = column("SELECT sec_usr_pwd FROM user_security ORDER BY 1");

            assertThat(identifiers)
                    .as("the delivered seed applies ten identities")
                    .hasSize(10);
            assertThat(types.stream().filter("A"::equals).count())
                    .as("five hold the administrative role")
                    .isEqualTo(5L);
            assertThat(types.stream().filter("U"::equals).count())
                    .as("five hold the ordinary role")
                    .isEqualTo(5L);
            assertThat(types)
                    .as("no third role exists")
                    .containsOnly("A", "U");
            assertThat(secrets)
                    .as("every stored secret is a digest, never the eight-character legacy literal - "
                            + "the one deliberate parity exception the decision log records")
                    .allSatisfy(secret -> {
                        assertThat(secret).startsWith("$2");
                        assertThat(secret.length()).isGreaterThan(50);
                    });
        }

        /**
         * The three externalised lookup tables, at the cardinalities the plan counted.
         *
         * <p>Read out of the shipped JSON resources rather than out of the service that loads them, so
         * the count is checked against the delivered data and not against the code's own opinion of it.
         *
         * @throws IOException if a resource cannot be read
         */
        @Test
        @DisplayName("the three shipped lookup resources hold 490 area codes as an exact partition of "
                + "410 plus 80, 56 state codes, and 240 state-and-ZIP combinations")
        void theShippedLookupResourcesHoldTheCountedCardinalities() throws IOException {
            final ObjectMapper mapper = new ObjectMapper();

            final JsonNode areaCodes =
                    mapper.readTree(classpathBytes(LOOKUP_DIRECTORY + "nanpa-area-codes.json"));
            final Set<String> generalPurpose = textValues(areaCodes.get("generalPurpose"));
            final Set<String> easyRecognition = textValues(areaCodes.get("easyRecognition"));

            assertThat(generalPurpose).as("general-purpose area codes").hasSize(410);
            assertThat(easyRecognition).as("easily-recognisable area codes").hasSize(80);
            assertThat(generalPurpose).as("the two subsets share no element, so the partition is exact")
                    .doesNotContainAnyElementsOf(easyRecognition);
            final Set<String> union = new HashSet<>(generalPurpose);
            union.addAll(easyRecognition);
            assertThat(union).as("410 plus 80 is 490, derived rather than stored").hasSize(490);

            assertThat(textValues(
                    mapper.readTree(classpathBytes(LOOKUP_DIRECTORY + "us-state-codes.json"))))
                    .as("state, district and territory codes")
                    .hasSize(56);
            assertThat(textValues(mapper.readTree(
                    classpathBytes(LOOKUP_DIRECTORY + "state-zip-prefixes.json"))))
                    .as("four-character state-and-ZIP-prefix combinations")
                    .hasSize(240);
        }

        @Test
        @DisplayName("the fixtures are laid out flat under two directories and hold no artefact the "
                + "gates do not name")
        void theFixtureTreeHoldsExactlyTheNamedArtefacts() throws IOException {
            final Path root = Path.of(FIXTURE_ROOT);
            assertThat(Files.isDirectory(root)).as("%s must exist", FIXTURE_ROOT).isTrue();

            final List<String> topLevel;
            try (Stream<Path> entries = Files.list(root)) {
                topLevel = entries.map(path -> path.getFileName().toString()).sorted().toList();
            }
            assertThat(topLevel)
                    .as("inputs and expected outputs, and nothing else at the top level")
                    .containsExactly("expected", "input");

            final List<String> inputs;
            try (Stream<Path> entries = Files.list(root.resolve("input"))) {
                inputs = entries.map(path -> path.getFileName().toString()).sorted().toList();
            }
            assertThat(inputs)
                    .as("the nine named ASCII artefacts plus the credential artefact, flat")
                    .containsExactly("acctdata.txt", "carddata.txt", "cardxref.txt", "custdata.txt",
                            "dailytran.txt", "discgrp.txt", "tcatbal.txt", "trancatg.txt",
                            "trantype.txt", "usrsec.txt");

            final List<String> goldens;
            try (Stream<Path> entries = Files.list(root.resolve("expected"))) {
                goldens = entries.filter(Files::isRegularFile)
                        .map(path -> path.getFileName().toString()).sorted().toList();
            }
            assertThat(goldens)
                    .as("the four goldens and the encoded archive oracle; an empty golden invites a "
                            + "vacuous assertion and none is committed")
                    .containsExactly("daily-reject.txt", "statement-html.txt", "statement.txt",
                            "transaction-archive.b64", "transaction-report.txt");
            assertThat(goldens)
                    .allSatisfy(name -> assertThat(Files.size(root.resolve("expected").resolve(name)))
                            .as("%s must not be empty", name)
                            .isPositive());
        }
    }

    // ===============================================================================================
    // GATE 6
    // ===============================================================================================

    @Nested
    @DisplayName("Gate 6 - the unsafe and low-level code audit, executed over the production tree")
    class GateSix {

        /** Creates the nested specification. */
        GateSix() {
            // Intentionally empty.
        }

        /**
         * The audit is scoped to the production source tree and to nothing else.
         *
         * <p>The scoping is part of the measurement. The four migration scripts under
         * {@code src/main/resources/db/migration} are versioned schema definitions, and an unscoped
         * search for statement text would report them as dynamic query assembly - four violations that
         * are in fact the deliverable. Test sources are excluded for the converse reason: an assertion
         * helper may legitimately cast where production code may not.
         */
        private static final String PRODUCTION_TREE = "src/main/java";

        @ParameterizedTest(name = "{0} appears {1} time(s) in the production tree")
        @CsvSource({
            "Runtime.getRuntime, 0",
            "ProcessBuilder,     0",
            "java.lang.reflect,  0",
            "Class.forName,      0",
            "createNativeQuery,  0"})
        @DisplayName("each forbidden construct is absent from the production tree")
        void eachForbiddenConstructIsAbsent(final String construct, final int budget)
                throws IOException {
            assertThat(occurrencesIn(PRODUCTION_TREE, construct))
                    .as("%s is budgeted at %d; the reflection budget in particular is what forbids "
                            + "annotation-driven record mapping and is why all eleven mappers slice "
                            + "explicit offsets", construct, budget)
                    .isEqualTo(budget);
        }

        @Test
        @DisplayName("suppressed warnings stay within the budget of three, and the measured count is "
                + "zero because every warning is already an error")
        void suppressedWarningsStayWithinBudget() throws IOException {
            assertThat(occurrencesIn(PRODUCTION_TREE, "@SuppressWarnings"))
                    .as("the budget is three and each suppression would need a stated reason")
                    .isLessThanOrEqualTo(3)
                    .isZero();
        }

        @Test
        @DisplayName("the migration scripts are excluded from the raw-statement audit by scope, and "
                + "they are the four the plan delivers")
        void theMigrationScriptsAreExcludedByScopeRatherThanBySilence() throws IOException {
            final Path migrations = Path.of("src/main/resources/db/migration");
            final List<String> scripts;
            try (Stream<Path> entries = Files.list(migrations)) {
                scripts = entries.map(path -> path.getFileName().toString()).sorted().toList();
            }
            assertThat(scripts)
                    .as("four scripts, flat, in one location - which is what makes the audit's scoping "
                            + "rule statable in one line")
                    .containsExactly("V1__create_schema.sql", "V2__create_indexes.sql",
                            "V3__seed_reference_data.sql", "V4__seed_user_security.sql");
        }
    }

    // ===============================================================================================
    // GATES 7 AND 8
    // ===============================================================================================

    @Nested
    @DisplayName("Gates 7 and 8 - the coverage floor and the supply-chain scan are executed, not "
            + "declared")
    class GatesSevenAndEight {

        /** Creates the nested specification. */
        GatesSevenAndEight() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("the coverage plugin enforces a line ratio as a failing check rather than "
                + "producing a report nobody reads")
        void theCoverageFloorIsAFailingCheck() throws IOException {
            final String build = Files.readString(Path.of(BUILD_FILE), StandardCharsets.UTF_8);

            assertThat(build).as("the coverage plugin is declared").contains("jacoco-maven-plugin");
            assertThat(build).as("and it runs a check goal, not only a report goal").contains("check");
            assertThat(build)
                    .as("the gated counter is LINE, as the plan states; branch, method and "
                            + "instruction coverage are reported for information")
                    .contains("LINE");
            assertThat(build)
                    .as("the floor is a covered ratio")
                    .contains("COVEREDRATIO");
        }

        @Test
        @DisplayName("the vulnerability scan is bound to the verify phase and is not skipped by "
                + "default, so an ordinary build performs it")
        void theSupplyChainScanIsBoundAndEnabled() throws IOException {
            final String build = Files.readString(Path.of(BUILD_FILE), StandardCharsets.UTF_8);

            assertThat(build).as("the scan plugin is declared").contains("dependency-check-maven");
            assertThat(build)
                    .as("it is bound to a phase an ordinary build reaches")
                    .contains("<phase>verify</phase>");
            assertThat(build)
                    .as("a plugin that is present but skipped satisfies the letter of the gate and "
                            + "none of its intent")
                    .doesNotContain("<skip>true</skip>");
        }

        @Test
        @DisplayName("the integration tier includes the end-to-end classes, so the gate assertions "
                + "actually execute in a build")
        void theIntegrationTierIncludesTheEndToEndClasses() throws IOException {
            final String build = Files.readString(Path.of(BUILD_FILE), StandardCharsets.UTF_8);

            assertThat(build).contains("**/*IT.java");
            assertThat(build).contains("**/*E2ETest.java");
            assertThat(build)
                    .as("this class is named for a gate rather than for a subject, so it is reached "
                            + "by the package pattern rather than by the suffix pattern")
                    .contains("**/e2e/**/*Test.java");
        }

        @Test
        @DisplayName("the eleven application tables the schema migration creates are all present on a "
                + "real server, which is the data-tier half of the scope Gate 7 claims")
        void theElevenApplicationTablesArePresent() throws SQLException {
            assertThat(applicationTableNames())
                    .as("the eleven verified record layouts, as tables")
                    .hasSize(11);
            assertThat(appliedMigrationVersions())
                    .as("all four delivered migrations are applied")
                    .containsExactly("1", "2", "3", "4");
        }
    }

    // ===============================================================================================
    // HELPERS
    // ===============================================================================================

    /**
     * Reads a class-path resource whole.
     *
     * @param  resource the class-path location
     * @return its bytes
     * @throws IOException if the resource cannot be read
     */
    private static byte[] classpathBytes(final String resource) throws IOException {
        try (InputStream stream = GateVerificationTest.class.getResourceAsStream(resource)) {
            assertThat(stream).as("%s must be on the test class path", resource).isNotNull();
            return stream.readAllBytes();
        }
    }

    /**
     * Collects the text values of a JSON array or of every array under a JSON object.
     *
     * @param  node the node to collect from
     * @return the distinct text values
     */
    private static Set<String> textValues(final JsonNode node) {
        assertThat(node).as("the lookup resource must hold the expected shape").isNotNull();
        final Set<String> values = new HashSet<>();
        if (node.isArray()) {
            node.forEach(element -> values.add(element.asText()));
            return values;
        }
        node.forEach(child -> values.addAll(textValues(child)));
        return values;
    }

    /**
     * Counts the lines of every Java source beneath one directory that contain a construct.
     *
     * @param  directory the tree to search, module-relative
     * @param  construct the literal construct to count
     * @return the number of matching lines
     * @throws IOException if the tree cannot be walked
     */
    private static long occurrencesIn(final String directory, final String construct)
            throws IOException {
        final Path root = Path.of(directory);
        assertThat(Files.isDirectory(root)).as("%s must exist", directory).isTrue();
        long total = 0L;
        try (Stream<Path> tree = Files.walk(root)) {
            for (final Path file : tree.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java")).toList()) {
                for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.contains(construct)) {
                        total++;
                    }
                }
            }
        }
        return total;
    }

    /**
     * Runs one fixed single-column statement against the shared server and collects the column.
     *
     * <p>Every caller passes a complete literal statement, so no value is concatenated into SQL.
     *
     * @param  sql the complete statement
     * @return the column values in the order the statement returns them
     * @throws SQLException if the statement fails
     */
    private static List<String> column(final String sql) throws SQLException {
        final List<String> values = new ArrayList<>();
        try (Connection connection =
                        DriverManager.getConnection(jdbcUrl(), databaseUser(), databasePassword());
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }

    /**
     * Digests bytes with SHA-256 and renders the digest in lower-case hexadecimal.
     *
     * <p>Retained for the artefact-identity assertions the input-fixture suite owns, and used here to
     * keep the two suites' rendering identical if a digest is ever compared across them.
     *
     * @param  content the bytes to digest
     * @return the digest as sixty-four hexadecimal characters
     */
    static String sha256Hex(final byte[] content) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", unavailable);
        }
        final StringBuilder hex = new StringBuilder(64);
        for (final byte value : digest.digest(content)) {
            hex.append(String.format(Locale.ROOT, "%02x", Byte.valueOf(value)));
        }
        return hex.toString();
    }
}
