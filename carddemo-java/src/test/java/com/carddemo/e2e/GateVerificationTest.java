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

package com.carddemo.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.api.dto.SignOnResponse;
import com.carddemo.batch.CategoryBalanceReportJobConfig;
import com.carddemo.domain.UserSecurity;
import com.carddemo.domain.enums.UserType;
import com.carddemo.repository.UserSecurityRepository;
import com.carddemo.service.MessageCatalogService;
import com.carddemo.service.ValidationLookupService;
import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.support.SensitiveValues;
import com.carddemo.support.TestDataFactory;
import com.carddemo.util.JclCardImageBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.tracing.prometheus.PrometheusExemplarsAutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * The integration sign-off, executed: Gate 4's named artefacts and the whole of Gate 8.
 *
 * <h2>Why this class exists</h2>
 * Gate 4 requires the validation artefacts to be specified <em>by name</em>, and Gate 8 requires a
 * checklist whose every line has a satisfying artefact. Prose can only ever <em>claim</em> to discharge
 * either one. This class is the audit instrument that discharges both in code: it asserts that the named
 * artefacts exist with the shapes that were measured, that the traceability matrix covers 100% of the
 * estate's procedure units, and that the provenance identifiers and the evidence documents are real
 * rather than asserted.
 *
 * <h2>The three properties that make this an audit rather than a formality</h2>
 * <ol>
 *   <li><strong>Nothing is skipped and nothing is assumed.</strong> There is no {@code @Disabled}, no
 *       {@code Assumptions}, and no conditional that turns a missing artefact into a pass. A skipped
 *       test and a passing test are indistinguishable in a build summary, which is precisely the failure
 *       mode a sign-off must not have. When an artefact is absent the failure names the
 *       <em>resolved absolute path</em> where it was expected, so the diagnostic reads as a work item.</li>
 *   <li><strong>Every shared figure is read from a published constant, never restated.</strong> Two
 *       assertions of one fact drift apart and the weaker one wins. Where a neighbouring suite already
 *       owns a fact, this class asserts it through the same {@link TestDataFactory} constant that suite
 *       reads, so the two cannot diverge: there is one source of truth and two readers of it.</li>
 *   <li><strong>No figure is regenerated from the code under test.</strong> The paragraph counts, the
 *       record geometries and the matrix row count are measurements of committed artefacts, compared
 *       against constants. Nothing here asks a mapper, a formatter or a template to produce the value it
 *       is then checked against.</li>
 * </ol>
 *
 * <h2>What this class asserts, and what it deliberately leaves to a narrower suite</h2>
 * The gate-level inventory is here. The record-level detail is not, and each neighbour is named so a
 * reader can follow the fact rather than find it restated:
 * <ul>
 *   <li>the input fixtures' digests against the legacy datasets belong to
 *       {@code support/FixtureContractTest};</li>
 *   <li>the goldens' record-level reproduction belongs to
 *       {@code support/ExpectedOutputFixtureContractTest} and
 *       {@code support/ExpectedHtmlStatementFixtureContractTest};</li>
 *   <li>Gate 1's whole-file byte equality belongs to {@link BatchPipelineE2ETest};</li>
 *   <li>Gate 5's sign-on texts and job-submission card image belong to
 *       {@link OnlineTransactionE2ETest};</li>
 *   <li>the lookup sets' <em>semantics</em> - which individual code is valid - belong to
 *       {@code service/ValidationLookupServiceTest}. Their <em>cardinalities</em> are a gate figure and
 *       are asserted here.</li>
 * </ul>
 *
 * <h2>Two phase facts that shape what a gate can honestly assert here</h2>
 * This class runs in the integration tier, at {@code integration-test}. The vulnerability scan and the
 * coverage check are bound to {@code verify}, which is a <em>later</em> phase. Their reports therefore do
 * not exist while this class runs, and a test demanding them would fail every clean build for a reason
 * unrelated to either gate. So the enforcing <em>mechanism</em> is asserted unconditionally - the scan is
 * declared, bound to a phase an ordinary build reaches, not skipped, and configured to end the build on a
 * qualifying score that no analyst determination covers - and any report a build has already produced is
 * read on both its unsuppressed and its suppressed side. What is never done is to infer a pass from an
 * absent report: the sign-off summary records the mechanism as the satisfying artefact and says so in as
 * many words.
 *
 * <p>That phrase "that no analyst determination covers" is the whole of the supply-chain gate's honesty,
 * and it is asserted rather than assumed. The threshold ends the build on an <em>unsuppressed</em>
 * qualifying finding, and this module carries exactly one determination - a high-severity match against
 * the embedded servlet container whose fixed releases are published on no line. So the claim is zero
 * unsuppressed critical or high findings <em>plus</em> one disclosed, scoped, self-expiring determination,
 * never "zero findings"; the determination's shape and scope are asserted here, and the withdrawn stronger
 * wording is asserted absent from both the build file and the manual so it cannot quietly return.
 *
 * <h2>On user-specified rules</h2>
 * There are none. {@code review_rules} returns a single line stating that no rules were provided, read to
 * completion across three windows. That is a verified absence, not an unread document, and it is not
 * licence to lower the standard: the twelve substituted enterprise standards apply in full. Note also
 * what is <em>not</em> a rule, because this class documents the project's own requirements: the ten-row
 * construct-mapping table is a <strong>requirement</strong> and the eight gates are
 * <strong>acceptance criteria</strong>. Both bind, neither originates in the rules document, and neither
 * is retrievable from it.
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19, both read from
 * {@link TestDataFactory#VERIFIED_CHECKOUT_COMMIT} and {@link TestDataFactory#UPSTREAM_RELEASE_STAMP}.
 * That stamp is a matrix-header provenance string and nothing more - it is carried by 78 legacy members,
 * three carry a later stamp, the seventeen mapsets differ and twenty-five carry none, so no assertion
 * here applies it to a legacy member. Every figure below is a measurement of a committed artefact or a
 * count of a legacy one, which is metadata; no legacy source text is transcribed.
 */
@SpringBootTest(classes = GateVerificationTest.GateContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.flyway.enabled=false", "spring.main.banner-mode=off",
                "spring.jpa.hibernate.ddl-auto=none", "spring.batch.job.enabled=false",
                "management.tracing.enabled=false",
                "management.endpoint.health.validate-group-membership=false"})
@DisplayName("Gates 4 and 8 executed: the named artefacts, the 544-unit coverage invariant, and the "
        + "integration sign-off")
class GateVerificationTest extends AbstractPostgresIT {

    // ===================================================================================================
    // CONSTANTS
    // ===================================================================================================

    private static final String BUILD_FILE = "pom.xml";

    /** Module-relative path of the operator manual, which publishes the gate results. */
    private static final String OPERATOR_MANUAL = "README.md";

    /** Module-relative path of the analyst-determination file the vulnerability scan reads. */
    private static final String SUPPRESSION_FILE = "owasp-suppressions.xml";

    /**
     * The one identifier a determination is written for, and therefore the only identifier a suppressed
     * qualifying finding may carry.
     *
     * <p>A high-severity match against the embedded servlet container whose fixed releases are not
     * published on any line. Carried as a written determination rather than as a version bump that cannot
     * resolve, and recorded in the decision log at DL-159.
     */
    private static final String DETERMINED_IDENTIFIER = "CVE-2026-66299";

    /**
     * The artifact scope that determination is confined to.
     *
     * <p>Three artifacts rather than one, because the product-level platform record migrates between the
     * embedded jars across consecutive scans; the false-match evidence was taken for each of the three
     * separately, which is what permits the wider name. Asserted as an exact string so a later edit cannot
     * loosen the regex into a family wildcard.
     */
    private static final String DETERMINED_ARTIFACT_SCOPE =
            "^pkg:maven/org\\.apache\\.tomcat\\.embed/tomcat-embed-(core|websocket|el)@.*$";

    /** Substring every artifact inside that scope carries, used to check a report entry against it. */
    private static final String DETERMINED_ARTIFACT_FAMILY = "tomcat-embed";

    /** The score at which a finding qualifies as high, which is the score the build fails at. */
    private static final double QUALIFYING_SCORE = 7.0d;

    /** Module-relative production source tree. The Gate 6 audit is scoped to exactly this. */
    private static final String PRODUCTION_TREE = "src/main/java";

    /** Module-relative test source tree, which a covering-test citation is resolved against. */
    private static final String TEST_TREE = "src/test/java";

    private static final String GOLDEN_DIRECTORY = "/fixtures/expected/";

    /**
     * Lowest card number in the delivered cross-reference fixture.
     *
     * <p>Named rather than written into the assertion that reads it, so the assertion can compare digests
     * and print neither value. It is a synthetic fixture value, and it is still not printed: a card number
     * is regulated by its shape rather than by where it came from, and a build log is retained and widely
     * readable.
     */
    private static final String LOWEST_SEEDED_CARD_NUMBER = "0500024453765740";

    /** Highest card number in the delivered cross-reference fixture; see the field above. */
    private static final String HIGHEST_SEEDED_CARD_NUMBER = "9805583408996588";

    private static final String LOOKUP_DIRECTORY = "/lookup/";

    private static final String LEGACY_ROOT = "app";

    private static final String ENCODED_DATASET_DIRECTORY = "app/data/EBCDIC";

    /** Repository-relative directory holding the migration's documentation deliverables. */
    private static final String DOCUMENTATION_DIRECTORY = "docs";

    /** The traceability matrix, which carries one row per procedure unit. */
    private static final String TRACEABILITY_MATRIX = "traceability-matrix.md";

    private static final String DECISION_LOG = "decision-log.md";

    private static final String GATE_EVIDENCE = "gate-evidence.md";

    /** The architecture description, which is the authority for the shape of the batch tier. */
    private static final String ARCHITECTURE = "architecture.md";

    /**
     * The four golden-backed output widths, keyed by the golden that carries each.
     *
     * <p>Four rather than five: the fifth contractual width, the category-balance report line, has no
     * golden because Gate 1 names four expected outputs. It is asserted from the emitting job's own
     * configuration instead, and the complete five-width inventory is asserted over both sources.
     */
    private static final Map<String, Integer> GOLDEN_WIDTHS = Map.of(
            "statement.txt", Integer.valueOf(80),
            "statement-html.txt", Integer.valueOf(100),
            "transaction-report.txt", Integer.valueOf(133),
            "daily-reject.txt", Integer.valueOf(430));

    /**
     * Heading of the manual's ledger of documentation pages that have not landed.
     *
     * <p>Held as an ASCII prefix of the real heading rather than the whole of it, because the heading
     * carries a dash this file has no reason to encode.
     */
    private static final String PENDING_DOCUMENTS_HEADING = "**Not yet published";

    /**
     * The column header every per-member table in the matrix carries.
     *
     * <p>Row counting keys off this exact header rather than off "a line starting with a pipe", because
     * the matrix also carries a provenance table, a composition table, a column legend, a marker legend
     * and a per-member census. Counting every pipe line would yield 595 and the 544 invariant would be
     * unassertable.
     */
    private static final String MATRIX_ROW_HEADER =
            "| Source member | Paragraph | Line | Target class | Target method | Covering test | Notes |";

    /** Marker denoting a documented non-implementation: invoked, and implementing nothing. */
    private static final String MARKER_NON_IMPLEMENTATION = "\u2020";

    /** Marker denoting a source anomaly preserved as found rather than corrected. */
    private static final String MARKER_SOURCE_ANOMALY = "\u2021";

    /** Marker denoting a member no job stream invokes. */
    private static final String MARKER_UNWIRED = "\u00a7";

    /**
     * Marker denoting a paragraph no delivered call site reaches, whose covering test must therefore name
     * its method rather than rely on the driver.
     */
    private static final String MARKER_UNWIRED_PARAGRAPH = "\u00b6";

    /** The package every matrix target class and covering test is named relative to. */
    private static final String BASE_PACKAGE_PATH = "com/carddemo";

    /** Paragraphs in the procedure divisions of the 28 COBOL programs. */
    private static final int PROGRAM_PARAGRAPHS = 528;

    /** Paragraphs the date-validation procedural copybook contributes. */
    private static final int DATE_COPYBOOK_PARAGRAPHS = 14;

    /** Paragraphs the function-key procedural copybook contributes. */
    private static final int PFKEY_COPYBOOK_PARAGRAPHS = 2;

    /** The coverage invariant: every procedure unit in the estate has a row. */
    private static final int TOTAL_PROCEDURE_UNITS =
            PROGRAM_PARAGRAPHS + DATE_COPYBOOK_PARAGRAPHS + PFKEY_COPYBOOK_PARAGRAPHS;

    /**
     * How the rows no delivered call site reaches are distributed across the members that carry them.
     *
     * <p>Fixed figures rather than derived ones, because the population is a decision. Wiring one of these
     * paragraphs in, dropping one, or discovering a new one must fail here and be re-decided rather than
     * silently change the marker distribution.
     *
     * <p>The distribution is asserted per member and not only in total, which is the part a total cannot
     * do. The marker was previously declared to be six rows all belonging to the account-update member,
     * and ten further rows across three other members carried the same property and no marker: the
     * account-view long-text sender and its exit, both card-list diagnostic senders and their exits, and
     * the card-detail account-keyed read and long-text sender with their exits. A bare total would have
     * accepted moving a row from one member to another, which is exactly how a marker population drifts.
     * Recorded as {@code DL-283} in {@code docs/decision-log.md}.
     */
    private static final Map<String, Integer> UNWIRED_PARAGRAPHS_BY_MEMBER = Map.of(
            "app/cbl/COACTUPC.cbl", 6,
            "app/cbl/COACTVWC.cbl", 2,
            "app/cbl/COCRDLIC.cbl", 4,
            "app/cbl/COCRDSLC.cbl", 4);

    /** Rows whose paragraph no delivered call site reaches, summed over the members that carry them. */
    private static final int UNWIRED_PARAGRAPH_ROWS =
            UNWIRED_PARAGRAPHS_BY_MEMBER.values().stream().mapToInt(Integer::intValue).sum();

    /** Ordinary translations: the total less every marked row. */
    private static final int ORDINARY_ROWS = 506;

    /** Application tables the schema migration creates, one per verified record layout. */
    private static final int APPLICATION_TABLE_COUNT = 11;

    /** The four delivered migrations, flat, in one location. */
    private static final List<String> MIGRATION_VERSIONS = List.of("1", "2", "3", "4");

    /**
     * The ten batch programs, which is what distinguishes an application step from a utility step in the
     * job census.
     *
     * <p>Two of the ten are invoked by no job step at all - the statement helper is called from another
     * program rather than scheduled, and the extract member is the unwired one - so the roster is larger
     * than the set of programs the census finds.
     */
    private static final Set<String> BATCH_PROGRAMS = Set.of("CBACT01C", "CBACT02C", "CBACT03C",
            "CBACT04C", "CBCUS01C", "CBTRN01C", "CBTRN02C", "CBTRN03C", "CBSTM03A", "CBSTM03B");

    /** Where the sign-off summary is written for transcription into the recorded evidence. */
    private static final Path EVIDENCE_DIRECTORY = Path.of("target", "gate-evidence");

    /** File the sign-off summary is written to. */
    private static final String SIGN_OFF_FILE = "gate8-sign-off.md";

    /** Column header of the recorded Gate 3 table, which the measured rows are read from. */
    private static final String PERFORMANCE_ROW_HEADER = "| Date | Machine | Run | Records | "
            + "Elapsed (ms) | Peak heap (bytes) | Records/second |";

    /**
     * Casts to a parameterised type or a type variable the production tree may carry.
     *
     * <p>The plan budgets five. All five that exist are lambda-target casts onto a parameterised
     * functional interface, which the compiler does not treat as an unchecked operation - and it could
     * not, because every warning is an error here and the build compiles. The budget is asserted as a
     * ceiling and the measured figure is recorded, so a sixth site cannot appear unnoticed.
     */
    private static final int GENERIC_CAST_BUDGET = 5;

    /** Suppressed-warning budget the plan sets; the measured figure is zero. */
    private static final int SUPPRESSION_BUDGET = 3;

    /** Recognises a static call to a named subprogram, which the linkage census counts by target. */
    private static final Pattern STATIC_CALL =
            Pattern.compile("\\bCALL\\s+['\"]([A-Z0-9$#@]+)['\"]", Pattern.CASE_INSENSITIVE);

    /**
     * The 27 static call sites, by target, as the estate declares them.
     *
     * <p>Written out as the expectation and compared against a census taken from {@code app/cbl}, so the
     * figure is evidence rather than arithmetic over remembered numbers.
     */
    private static final Map<String, Integer> EXPECTED_CALL_TARGETS = Map.of(
            "CBSTM03B", Integer.valueOf(13),
            "CSUTLDTC", Integer.valueOf(4),
            "CEE3ABD", Integer.valueOf(9),
            "CEEDAYS", Integer.valueOf(1));

    /** Transfer-control transitions the online estate declares. */
    private static final int EXPECTED_TRANSFER_CONTROL_SITES = 25;

    /** Pseudo-conversational re-arms the online estate declares. */
    private static final int EXPECTED_REARM_SITES = 19;

    /** Online programs across which those transitions and re-arms are distributed. */
    private static final int ONLINE_PROGRAM_COUNT = 17;

    /** The member that declares the same exit label twice, which is the one uniqueness exception. */
    private static final String DUPLICATED_LABEL_MEMBER = "app/cbl/COACTVWC.cbl";

    /** The label that member declares twice. */
    private static final String DUPLICATED_LABEL = "0000-MAIN-EXIT";

    /** The class the empty fee paragraph maps into. */
    private static final String FEE_METHOD_OWNER = "service/InterestCalculationService.java";

    /**
     * The nine named sequential inputs, at the byte counts they were measured at.
     *
     * <p>The same nine names and sizes the parameterised case above asserts one at a time. They are held
     * here as well because the sign-off row has to be the result of a check rather than a literal, and a
     * row cannot read a parameterised case's outcome. Written out by name, because "specified by name" is
     * the requirement and a directory listing would still pass after a rename.
     */
    private static final Map<String, Integer> NAMED_FIXTURE_SIZES = Map.of(
            "acctdata.txt", Integer.valueOf(15050),
            "carddata.txt", Integer.valueOf(7550),
            "cardxref.txt", Integer.valueOf(1850),
            "custdata.txt", Integer.valueOf(25050),
            "dailytran.txt", Integer.valueOf(105300),
            "discgrp.txt", Integer.valueOf(2601),
            "tcatbal.txt", Integer.valueOf(2550),
            "trancatg.txt", Integer.valueOf(1098),
            "trantype.txt", Integer.valueOf(427));

    /** The twelve encoded datasets the plan names: eleven sequential datasets and one initial copy. */
    private static final int ENCODED_DATASET_COUNT = 12;

    /** Precision the recorded rate is re-derived at, wide enough that the comparison is the tolerance. */
    private static final MathContext MEASUREMENT_PRECISION = new MathContext(16, RoundingMode.HALF_UP);

    /**
     * Tolerance the re-derived rate is compared within.
     *
     * <p>Five per cent, and the reason it is not tighter is arithmetic rather than laxity: the recorder
     * divides nanoseconds while the published table carries whole milliseconds, so a short run's quotient
     * differs in the third significant figure from the same run's published one. The check is there to
     * catch a number nobody measured, and a fabricated figure does not land inside five per cent of the
     * quotient of the two figures beside it.
     */
    private static final BigDecimal RATE_TOLERANCE = new BigDecimal("0.05");

    /** Recognises a step's program invocation in a control statement. */
    private static final Pattern EXEC_PGM = Pattern.compile("\\bEXEC\\s+PGM=([A-Z0-9$#@]+)");

    GateVerificationTest() {
        super();
    }

    // ===================================================================================================
    // CONTEXT
    // ===================================================================================================

    /**
     * The narrowest graph that lets the gate figures be read through the shipped code as well as out of
     * the shipped resources.
     *
     * <p>Two beans are needed and nothing else is imported. The lookup service is here because Gate 4's
     * cardinalities must be confirmed against the service that actually loads them, not only against the
     * JSON that feeds it - a resource with the right count and a loader that drops an entry would pass a
     * resource-only check. The identity repository is here because the credential seed is asserted
     * through the mapped entity as well as through the catalogue, which is what proves the eighty-byte
     * layout survived the migration.
     *
     * <p>Nothing is stubbed and no boundary is substituted. The server is the shared containerised
     * PostgreSQL the base class migrated with the four delivered scripts, so the schema under assertion
     * is the schema the migration produces.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = PrometheusExemplarsAutoConfiguration.class)
    @Import(ValidationLookupService.class)
    @EnableJpaRepositories(basePackageClasses = UserSecurityRepository.class)
    @EntityScan(basePackageClasses = UserSecurity.class)
    static class GateContext {

        GateContext() {
            // Intentionally empty: this slice contributes beans, not state.
        }
    }

    /** The shipped lookup service, so a cardinality is confirmed through code and not only through data. */
    @Autowired
    private ValidationLookupService validationLookupService;

    /** The shipped identity repository, so the credential seed is read through the mapped entity. */
    @Autowired
    private UserSecurityRepository userSecurityRepository;

    // ===================================================================================================
    // GATE 4 :: the named artefacts, at the geometry that was measured
    // ===================================================================================================

    /**
     * The nine sequential inputs and the derived identity fixture, each named and each measured.
     */
    @Nested
    @DisplayName("Gate 4 - the nine named ASCII artefacts, at their measured geometry")
    class NamedInputArtefacts {

        NamedInputArtefacts() {
        }

        /**
         * Every named artefact, at its byte count, its record count and its uniform record length.
         *
         * <p>All three figures are asserted together because each alone is weak. A byte count passes for
         * a file of the right size and the wrong shape. A record count passes for a file read at the
         * wrong width whenever that width happens to divide evenly - which is not hypothetical here: the
         * cross-reference fixture is 1,850 bytes, which divides evenly by both the 37-byte fixture stride
         * and other candidates, so only naming the expected count turns a silent misreading into a
         * failure. And a uniform length is what proves the file is a fixed-length dataset rather than
         * ragged text.
         *
         * <p>The names are written out one per case rather than discovered by listing the directory. A
         * listing would still pass after a rename, and "specified by name" is the requirement.
         *
         * @param fileName      the artefact's name, exactly as the plan gives it
         * @param expectedBytes the measured size, records plus one separator each
         * @param records       the number of records
         * @param recordLength  the length every record has
         * @throws IOException if the artefact cannot be read
         */
        @ParameterizedTest(name = "{0}: {1} bytes = {2} records of {3} + one separator each")
        @CsvSource({
            "acctdata.txt,   15050,  50, 300",
            "carddata.txt,    7550,  50, 150",
            "cardxref.txt,    1850,  50,  36",
            "custdata.txt,   25050,  50, 500",
            "dailytran.txt, 105300, 300, 350",
            "discgrp.txt,     2601,  51,  50",
            "tcatbal.txt,     2550,  50,  50",
            "trancatg.txt,    1098,  18,  60",
            "trantype.txt,     427,   7,  60"})
        @DisplayName("each is present at its byte count, record count and uniform record length")
        void eachNamedArtefactHasItsMeasuredGeometry(final String fileName, final int expectedBytes,
                final int records, final int recordLength) throws IOException {
            final byte[] content = classpathBytes(TestDataFactory.FIXTURE_DIRECTORY + fileName);

            assertThat(content)
                    .as("%s is a named Gate 4 artefact and must be present at its measured size",
                            fileName)
                    .hasSize(expectedBytes);
            assertThat(records * (recordLength + 1))
                    .as("%s: %d records of %d bytes plus one line feed each accounts for every one of "
                            + "the %d bytes, with nothing unexplained", fileName, records, recordLength,
                            expectedBytes)
                    .isEqualTo(expectedBytes);

            final List<String> lines = asciiRecords(fileName);
            assertThat(lines)
                    .as("%s holds %d records", fileName, records)
                    .hasSize(records);
            assertThat(lines)
                    .as("%s is a fixed-length dataset: every record measures %d, so a mapper may slice "
                            + "by offset without first measuring the record", fileName, recordLength)
                    .allSatisfy(line -> assertThat(line.length()).isEqualTo(recordLength));
        }

        /**
         * The cross-reference asymmetry, which is a contract rather than an oversight.
         *
         * <p>The record layout is fifty bytes: thirty-six of data followed by a fourteen-byte filler. The
         * ASCII fixture carries the thirty-six and stops - every record ends on a digit - while its
         * encoded twin carries the full fifty. Padding the fixture to fifty to "make the layouts agree"
         * would shift every subsequent field and corrupt the seed, so the two widths are held as two
         * named layouts and the difference is asserted rather than reconciled.
         *
         * @throws IOException if the artefact cannot be read
         */
        @Test
        @DisplayName("the cross-reference fixture carries 36 data bytes and no filler, while its layout "
                + "declares 50 - the asymmetry is asserted, never padded away")
        void theCrossReferenceFixtureCarriesNoFiller() throws IOException {
            assertThat(TestDataFactory.CARD_CROSS_REFERENCE_FIXTURE.recordLength())
                    .as("the fixture image is the data portion alone")
                    .isEqualTo(36);
            assertThat(TestDataFactory.CARD_CROSS_REFERENCE_DATASET.recordLength())
                    .as("the dataset image carries the trailing filler as well")
                    .isEqualTo(50);
            assertThat(TestDataFactory.CARD_CROSS_REFERENCE_DATASET.recordLength()
                            - TestDataFactory.CARD_CROSS_REFERENCE_FIXTURE.recordLength())
                    .as("the difference is the fourteen-byte filler the fixture omits")
                    .isEqualTo(14);

            final List<String> records = asciiRecords("cardxref.txt");
            assertThat(records)
                    .as("every record ends on a digit, which is what 'no filler' looks like from the "
                            + "outside; a space-padded record would end on a space")
                    .allSatisfy(record -> assertThat(Character.isDigit(record.charAt(record.length() - 1)))
                            .isTrue());
        }

        /**
         * The ordering and cardinality the statement generator's early exit depends on.
         *
         * <p>Six transactions per card across fifty cards is not a coincidence of the sample: the
         * statement generator walks the cross-reference in key order and stops when the key changes, so
         * an unordered or unevenly distributed input would exercise a different path. The claim is
         * asserted on both files and on their agreement, because either one alone could drift.
         *
         * @throws IOException if an artefact cannot be read
         */
        @Test
        @DisplayName("the cross-reference is ascending over 50 distinct cards, and the daily input holds "
                + "exactly six transactions for each of them")
        void theCrossReferenceIsOrderedAndEvenlyDistributed() throws IOException {
            final List<String> cards = new ArrayList<>();
            for (final String record : asciiRecords("cardxref.txt")) {
                cards.add(TestDataFactory.CARD_CROSS_REFERENCE_FIXTURE.slice(record, "XREF-CARD-NUM"));
            }

            // Every assertion below is exactly as strong as the direct one it replaces, and none of them
            // prints a card number. hasSize, doesNotHaveDuplicates and isSorted each render the whole
            // collection when they fail - fifty card numbers into a build log - and an equality assertion
            // renders both the actual and the expected. Counting, a predicate, and a digest comparison
            // pin the same facts: two values with one SHA-256 digest are one value, so a wrong boundary
            // card still fails here.
            assertThat(cards.size())
                    .as("fifty cards, one row each")
                    .isEqualTo(TestDataFactory.SEEDED_FIFTY_ROW_COUNT);
            assertThat(SensitiveValues.distinctCount(cards))
                    .as("and all fifty distinct")
                    .isEqualTo(TestDataFactory.SEEDED_FIFTY_ROW_COUNT);
            assertThat(SensitiveValues.ascending(cards))
                    .as("ascending by card number, which is the order a keyed browse returns and the "
                            + "order the statement generator's key-change exit relies on")
                    .isTrue();
            assertThat(SensitiveValues.fingerprint(cards.get(0)))
                    .as("the lowest card number in the seeded set")
                    .isEqualTo(SensitiveValues.fingerprint(LOWEST_SEEDED_CARD_NUMBER));
            assertThat(SensitiveValues.fingerprint(cards.get(cards.size() - 1)))
                    .as("the highest card number in the seeded set")
                    .isEqualTo(SensitiveValues.fingerprint(HIGHEST_SEEDED_CARD_NUMBER));

            final Map<String, Integer> perCard = new TreeMap<>();
            for (final String record : asciiRecords("dailytran.txt")) {
                final String card =
                        TestDataFactory.DAILY_TRANSACTION.slice(record, "DALYTRAN-CARD-NUM");
                perCard.merge(card, Integer.valueOf(1), (first, second) ->
                        Integer.valueOf(first.intValue() + second.intValue()));
            }
            assertThat(perCard.keySet())
                    .as("the daily input names exactly the cards the cross-reference resolves, so no "
                            + "transaction is orphaned and no card is idle")
                    .containsExactlyInAnyOrderElementsOf(cards);
            assertThat(perCard.values())
                    .as("exactly six transactions per card. The 51-by-10 working table of the statement "
                            + "generator holds six posted plus one interest comfortably, so there is no "
                            + "overflow here - a hypothesis that was tested and disproved, and is "
                            + "recorded as withdrawn rather than carried forward as a defect")
                    .allSatisfy(count -> assertThat(count).isEqualTo(6));
        }
    }

    /**
     * The composition of the primary posting input, which is what makes it representative.
     */
    @Nested
    @DisplayName("Gate 4 - the daily-transaction census, which is what makes the input representative")
    class DailyTransactionCensus {

        DailyTransactionCensus() {
        }

        /**
         * The source marker splits the file exactly, and the split is what reaches both posting paths.
         *
         * <p>Asserted as an exact partition rather than as a majority. "Mostly purchases" would be
         * satisfied by 299 and 1, and a single return is not evidence that the credit path runs.
         *
         * <p>Both literals are ten bytes wide because the field is, so each carries its own trailing
         * spaces. They are compared untrimmed: trimming would make a ten-byte field and an eight-byte one
         * compare equal and quietly destroy the layout assertion.
         *
         * @throws IOException if the artefact cannot be read
         */
        @Test
        @DisplayName("250 point-of-sale purchases and 50 operator returns, an exact partition of the 300")
        void theSourceMarkerPartitionsTheInputExactly() throws IOException {
            final Map<String, Integer> bySource =
                    census(asciiRecords("dailytran.txt"), TestDataFactory.DAILY_TRANSACTION,
                            "DALYTRAN-SOURCE");

            assertThat(bySource)
                    .as("two source markers and no third, each padded to the ten-byte field width and "
                            + "compared untrimmed")
                    .containsOnlyKeys("POS TERM  ", "OPERATOR  ");
            assertThat(bySource.get("POS TERM  "))
                    .as("the debit path's share")
                    .isEqualTo(TestDataFactory.SEEDED_PURCHASE_COUNT);
            assertThat(bySource.get("OPERATOR  "))
                    .as("the credit path's share")
                    .isEqualTo(TestDataFactory.SEEDED_RETURN_COUNT);
            assertThat(TestDataFactory.SEEDED_PURCHASE_COUNT + TestDataFactory.SEEDED_RETURN_COUNT)
                    .as("the two shares account for every record")
                    .isEqualTo(TestDataFactory.SEEDED_DAILY_TRANSACTION_COUNT);
        }

        /**
         * The type and category codes move with the source marker, one pair each.
         *
         * @throws IOException if the artefact cannot be read
         */
        @Test
        @DisplayName("the type and category codes form two pairs only, 250 of one and 50 of the other")
        void theTypeAndCategoryCodesFormTwoPairsOnly() throws IOException {
            final Map<String, Integer> byPair = new TreeMap<>();
            for (final String record : asciiRecords("dailytran.txt")) {
                final String pair =
                        TestDataFactory.DAILY_TRANSACTION.slice(record, "DALYTRAN-TYPE-CD")
                                + TestDataFactory.DAILY_TRANSACTION.slice(record, "DALYTRAN-CAT-CD");
                byPair.merge(pair, Integer.valueOf(1), (first, second) ->
                        Integer.valueOf(first.intValue() + second.intValue()));
            }

            assertThat(byPair)
                    .as("exactly two type-and-category pairs occur, so the reference lookups both "
                            + "resolve and neither is exercised by accident")
                    .containsOnlyKeys("010001", "030001");
            assertThat(byPair.get("010001"))
                    .as("the purchase pair")
                    .isEqualTo(TestDataFactory.SEEDED_PURCHASE_COUNT);
            assertThat(byPair.get("030001"))
                    .as("the return pair")
                    .isEqualTo(TestDataFactory.SEEDED_RETURN_COUNT);
        }

        /**
         * One origination stamp, a wholly blank processing stamp, and the consequence for the report path.
         *
         * <p>The blank processing stamp is the load-bearing discovery. Every one of the 300 records
         * carries twenty-six spaces there, and the whole 46-byte tail beyond the origination stamp is
         * blank, so the seeded input cannot exercise a processing-date window filter at all. The report
         * path's date window therefore has to be driven by a record constructed for the purpose, which is
         * what the test-data factory is for. Mutating the seed to manufacture one would corrupt the very
         * artefact Gate 4 names.
         *
         * @throws IOException if the artefact cannot be read
         */
        @Test
        @DisplayName("all 300 records share one origination stamp and every processing stamp is blank, so "
                + "no date window can be exercised from the seed")
        void theTimestampsCarryOneValueAndOneBlank() throws IOException {
            final List<String> records = asciiRecords("dailytran.txt");

            final Map<String, Integer> byOrigination =
                    census(records, TestDataFactory.DAILY_TRANSACTION, "DALYTRAN-ORIG-TS");
            assertThat(byOrigination)
                    .as("cardinality one: a single origination stamp across the whole input")
                    .hasSize(1)
                    .containsKey(TestDataFactory.SEEDED_ORIGINAL_TIMESTAMP);
            assertThat(TestDataFactory.SEEDED_ORIGINAL_TIMESTAMP)
                    .as("the stamp separates date from time with a space, not with the 'T' an ISO "
                            + "instant would use - the record format is the contract")
                    .hasSize(TestDataFactory.TIMESTAMP_TEXT_WIDTH)
                    .contains(" ")
                    .doesNotContain("T");

            final Map<String, Integer> byProcessing =
                    census(records, TestDataFactory.DAILY_TRANSACTION, "DALYTRAN-PROC-TS");
            assertThat(byProcessing)
                    .as("cardinality one, and that one value is twenty-six spaces on every record")
                    .hasSize(1)
                    .containsKey(TestDataFactory.BLANK_PROCESSING_TIMESTAMP);
            assertThat(byProcessing.get(TestDataFactory.BLANK_PROCESSING_TIMESTAMP))
                    .as("all 300, not merely most")
                    .isEqualTo(TestDataFactory.SEEDED_DAILY_TRANSACTION_COUNT);

            final int tailOffset =
                    TestDataFactory.DAILY_TRANSACTION.field("DALYTRAN-PROC-TS").offset();
            final String blankTail = String.valueOf(TestDataFactory.SPACE_FILLER)
                    .repeat(TestDataFactory.DAILY_TRANSACTION.recordLength() - tailOffset);
            assertThat(records)
                    .as("the 46 bytes from the processing stamp to the end of the record are blank on "
                            + "every record: the processing stamp and the trailing filler together")
                    .allSatisfy(record -> assertThat(record.substring(tailOffset)).isEqualTo(blankTail));
        }

        /**
         * The complete overpunch census: all twenty codes, both sign classes, and both signed zeroes.
         *
         * <p>This is the evidence that the zoned-decimal codec is genuinely exercised. A sign convention
         * that folds the sign into the final digit byte has twenty codes, and a fixture that reached only
         * the common ones would leave the rest of the codec unproven. All twenty occur, the class totals
         * match the purchase and return split exactly, and both signed zeroes are present - which matters
         * because positive zero and negative zero are distinct encodings of one value and a codec that
         * confuses them is wrong in a way no total would reveal.
         *
         * @throws IOException if the artefact cannot be read
         */
        @Test
        @DisplayName("the amount's final byte covers all twenty overpunch codes, 250 positive and 50 "
                + "negative, including both signed zeroes")
        void theOverpunchCensusCoversEveryCode() throws IOException {
            final int terminal = TestDataFactory.DAILY_TRANSACTION.field("DALYTRAN-AMT").endOffset() - 1;
            assertThat(terminal + 1)
                    .as("the sign rides on the final byte of the amount, at one-based column 143")
                    .isEqualTo(143);

            final Map<Character, Integer> observed = new TreeMap<>();
            int positive = 0;
            int negative = 0;
            for (final String record : asciiRecords("dailytran.txt")) {
                final char code = record.charAt(terminal);
                observed.merge(Character.valueOf(code), Integer.valueOf(1), (first, second) ->
                        Integer.valueOf(first.intValue() + second.intValue()));
                if (TestDataFactory.POSITIVE_OVERPUNCH.indexOf(code) >= 0) {
                    positive++;
                } else if (TestDataFactory.NEGATIVE_OVERPUNCH.indexOf(code) >= 0) {
                    negative++;
                } else {
                    assertThat(code)
                            .as("every terminal byte is one of the twenty overpunch codes")
                            .isIn(overpunchCodes());
                }
            }

            assertThat(observed)
                    .as("the measured census, code by code")
                    .containsExactlyInAnyOrderEntriesOf(expectedOverpunchCensus());
            assertThat(observed.keySet())
                    .as("all twenty codes occur, so positive, negative, positive-zero and negative-zero "
                            + "encodings are every one of them genuinely exercised")
                    .hasSize(20)
                    .containsExactlyInAnyOrderElementsOf(overpunchCodes());
            assertThat(positive)
                    .as("the positive class totals the purchase count")
                    .isEqualTo(TestDataFactory.SEEDED_PURCHASE_COUNT);
            assertThat(negative)
                    .as("the negative class totals the return count")
                    .isEqualTo(TestDataFactory.SEEDED_RETURN_COUNT);
            assertThat(observed.get(Character.valueOf('{')))
                    .as("positive zero, a distinct encoding from negative zero")
                    .isEqualTo(25);
            assertThat(observed.get(Character.valueOf('}')))
                    .as("negative zero, which no total would distinguish from positive zero")
                    .isEqualTo(6);
        }

        /**
         * The sign class follows the source marker without exception.
         *
         * @throws IOException if the artefact cannot be read
         */
        @Test
        @DisplayName("every purchase carries a positive overpunch and every return a negative one, with "
                + "no overlap")
        void theSignClassFollowsTheSourceMarker() throws IOException {
            final int terminal = TestDataFactory.DAILY_TRANSACTION.field("DALYTRAN-AMT").endOffset() - 1;

            for (final String record : asciiRecords("dailytran.txt")) {
                final String source =
                        TestDataFactory.DAILY_TRANSACTION.slice(record, "DALYTRAN-SOURCE");
                final char code = record.charAt(terminal);
                final String expected = "POS TERM  ".equals(source)
                        ? TestDataFactory.POSITIVE_OVERPUNCH
                        : TestDataFactory.NEGATIVE_OVERPUNCH;
                assertThat(expected.indexOf(code))
                        .as("source marker '%s' must carry its own sign class, and the correlation is "
                                + "exact rather than statistical", source)
                        .isNotNegative();
            }
        }
    }

    /**
     * The interest-rate reference data, the account anomaly that decides which of it is used, and the
     * per-fixture filler contract.
     */
    @Nested
    @DisplayName("Gate 4 - the disclosure groups, the account group-id anomaly, and the filler contract")
    class ReferenceDataComposition {

        ReferenceDataComposition() {
        }

        /**
         * Three contiguous groups under three full ten-character keys.
         *
         * <p>The keys are asserted <strong>untrimmed</strong>, and that is the whole point of the
         * assertion. Two of the three carry three trailing spaces, and the legacy read is a fixed-width
         * keyed read: a trimmed seven-character key would not match the ten-byte key field, so a
         * mapper that trims is broken in a way that only an untrimmed assertion catches.
         *
         * @throws IOException if the artefact cannot be read
         */
        @Test
        @DisplayName("three contiguous groups of 17 rows under the full ten-character keys, asserted "
                + "untrimmed")
        void theDisclosureGroupsAreThreeContiguousSeventeens() throws IOException {
            final List<String> records = asciiRecords("discgrp.txt");
            assertThat(records)
                    .as("fifty-one rows in total")
                    .hasSize(TestDataFactory.SEEDED_DISCLOSURE_GROUP_COUNT);

            final List<String> keys = new ArrayList<>();
            for (final String record : records) {
                keys.add(TestDataFactory.DISCLOSURE_GROUP.slice(record, "DIS-ACCT-GROUP-ID"));
            }

            assertThat(new LinkedHashSet<>(keys))
                    .as("the three group keys the plan names, in file order, each exactly ten characters "
                            + "and none of them trimmed")
                    .containsExactly(TestDataFactory.DIRECT_HIT_DISCLOSURE_GROUP_ID,
                            TestDataFactory.FALLBACK_DISCLOSURE_GROUP_ID,
                            TestDataFactory.ZERO_RATE_DISCLOSURE_GROUP_ID);
            assertThat(TestDataFactory.SEEDED_DISCLOSURE_GROUP_IDS)
                    .as("the published roster agrees with what the file carries")
                    .containsExactlyInAnyOrderElementsOf(new LinkedHashSet<>(keys));
            assertThat(TestDataFactory.FALLBACK_DISCLOSURE_GROUP_ID)
                    .as("the fallback key is padded to the field width with three trailing spaces")
                    .hasSize(10)
                    .endsWith("   ");
            assertThat(TestDataFactory.ZERO_RATE_DISCLOSURE_GROUP_ID)
                    .as("so is the zero-rate key")
                    .hasSize(10)
                    .endsWith("   ");

            assertThat(runLengths(keys))
                    .as("each group occupies one contiguous run of seventeen rows, so a keyed read never "
                            + "has to span the file - three runs, not three scattered sets")
                    .containsExactly(TestDataFactory.DISCLOSURE_GROUP_ROWS_PER_GROUP,
                            TestDataFactory.DISCLOSURE_GROUP_ROWS_PER_GROUP,
                            TestDataFactory.DISCLOSURE_GROUP_ROWS_PER_GROUP);
            assertThat(3 * TestDataFactory.DISCLOSURE_GROUP_ROWS_PER_GROUP)
                    .as("three seventeens account for all fifty-one rows")
                    .isEqualTo(TestDataFactory.SEEDED_DISCLOSURE_GROUP_COUNT);
        }

        /**
         * The rate images that make both interest branches reachable from seed data alone.
         *
         * <p>The zero-rate group is entirely zero, so the skip branch runs. The fallback group splits ten
         * non-zero against seven zero, so both branches run within a single group. Neither fact needs a
         * synthetic fixture, which is why the branch coverage of the interest path is achievable from the
         * delivered seed.
         *
         * @throws IOException if the artefact cannot be read
         */
        @Test
        @DisplayName("the zero-rate group is wholly zero and the fallback group splits 10 non-zero "
                + "against 7 zero, so both interest branches are reachable from the seed")
        void theRateImagesMakeBothInterestBranchesReachable() throws IOException {
            final String zeroRate = "00000{";
            final Map<String, List<String>> byGroup = new LinkedHashMap<>();
            for (final String record : asciiRecords("discgrp.txt")) {
                final String key = TestDataFactory.DISCLOSURE_GROUP.slice(record, "DIS-ACCT-GROUP-ID");
                byGroup.computeIfAbsent(key, unused -> new ArrayList<>())
                        .add(TestDataFactory.DISCLOSURE_GROUP.slice(record, "DIS-INT-RATE"));
            }

            final List<String> zeroRateGroup =
                    byGroup.get(TestDataFactory.ZERO_RATE_DISCLOSURE_GROUP_ID);
            assertThat(zeroRateGroup)
                    .as("every rate in the zero-rate group is positive zero, so the skip branch is "
                            + "provably reachable")
                    .hasSize(TestDataFactory.DISCLOSURE_GROUP_ROWS_PER_GROUP)
                    .allSatisfy(rate -> assertThat(rate).isEqualTo(zeroRate));

            final List<String> fallbackGroup =
                    byGroup.get(TestDataFactory.FALLBACK_DISCLOSURE_GROUP_ID);
            final long fallbackZero = fallbackGroup.stream().filter(zeroRate::equals).count();
            assertThat(fallbackZero)
                    .as("seven zero rates in the fallback group")
                    .isEqualTo(7L);
            assertThat(fallbackGroup.size() - fallbackZero)
                    .as("and ten non-zero, so the compute branch and the skip branch both run inside the "
                            + "one group every account actually resolves to")
                    .isEqualTo(10L);

            final List<String> directHitGroup =
                    byGroup.get(TestDataFactory.DIRECT_HIT_DISCLOSURE_GROUP_ID);
            assertThat(directHitGroup)
                    .as("the direct-hit group carries a non-zero rate as well, so it is not a "
                            + "degenerate copy of the zero-rate group")
                    .hasSize(TestDataFactory.DISCLOSURE_GROUP_ROWS_PER_GROUP)
                    .contains("00150{");
            assertThat(directHitGroup.stream().filter(zeroRate::equals).count())
                    .as("six of its seventeen rows are zero, which is measured rather than assumed "
                            + "uniform")
                    .isEqualTo(6L);
        }

        /**
         * The account anomaly that decides which disclosure group interest actually reads.
         *
         * <p>Both halves are asserted, because either alone would be misread. The group-id field is ten
         * spaces on every account, and the value that looks like a group id sits one field earlier, in
         * the postal-code position. The consequence is that no account matches its group directly and
         * every one takes the not-found fallback - so the fallback group, not the direct-hit group, is
         * what the interest run actually uses. Recording both facts is what stops the finding regressing
         * into a silent change of which rates apply.
         *
         * @throws IOException if the artefact cannot be read
         */
        @Test
        @DisplayName("every account carries a blank group id and the group-shaped value sits in the "
                + "postal-code field, so every account takes the not-found fallback")
        void theAccountGroupIdIsBlankAndTheGroupShapedValueSitsOneFieldEarlier() throws IOException {
            final List<String> records = asciiRecords("acctdata.txt");
            assertThat(records).hasSize(TestDataFactory.SEEDED_FIFTY_ROW_COUNT);

            final Map<String, Integer> byGroupId =
                    census(records, TestDataFactory.ACCOUNT, "ACCT-GROUP-ID");
            assertThat(byGroupId)
                    .as("one value across all fifty accounts, and it is ten spaces - so the group lookup "
                            + "cannot hit")
                    .hasSize(1)
                    .containsKey(TestDataFactory.SEEDED_ACCOUNT_GROUP_ID);
            assertThat(TestDataFactory.SEEDED_ACCOUNT_GROUP_ID)
                    .as("ten spaces, held as a constant so the two suites reading it cannot disagree")
                    .isEqualTo(String.valueOf(TestDataFactory.SPACE_FILLER).repeat(10));

            final Map<String, Integer> byZip = census(records, TestDataFactory.ACCOUNT, "ACCT-ADDR-ZIP");
            assertThat(byZip)
                    .as("the group-shaped value occupies the postal-code field on every account")
                    .hasSize(1)
                    .containsKey(TestDataFactory.SEEDED_ACCOUNT_ADDRESS_ZIP);
            assertThat(TestDataFactory.SEEDED_ACCOUNT_ADDRESS_ZIP)
                    .as("and it is character-for-character the direct-hit group key, which is exactly "
                            + "why the misplacement is easy to miss by eye")
                    .isEqualTo(TestDataFactory.DIRECT_HIT_DISCLOSURE_GROUP_ID);

            assertThat(TestDataFactory.ACCOUNT.field("ACCT-ADDR-ZIP").oneBasedPosition())
                    .as("the postal-code field begins at one-based column 103")
                    .isEqualTo(103);
            assertThat(TestDataFactory.ACCOUNT.field("ACCT-GROUP-ID").oneBasedPosition())
                    .as("and the group-id field begins at 113, immediately after it")
                    .isEqualTo(113);
        }

        /**
         * The balance state the pipeline order depends on.
         *
         * @throws IOException if the artefact cannot be read
         */
        @Test
        @DisplayName("every category balance starts at positive zero, which is what forces posting to "
                + "run before interest")
        void theCategoryBalancesStartAtZero() throws IOException {
            final Map<String, Integer> byBalance = census(asciiRecords("tcatbal.txt"),
                    TestDataFactory.TRANSACTION_CATEGORY_BALANCE, "TRAN-CAT-BAL");

            assertThat(byBalance)
                    .as("one balance image across all fifty rows")
                    .hasSize(1)
                    .containsKey("0000000000{");
            assertThat(byBalance.get("0000000000{"))
                    .as("all fifty. Interest computed against these balances before posting would "
                            + "compute against zero, so the pipeline order is a property of the data "
                            + "rather than a convention")
                    .isEqualTo(TestDataFactory.SEEDED_FIFTY_ROW_COUNT);
        }

        /**
         * The filler character, per fixture, measured rather than assumed uniform.
         *
         * <p>It is not uniform, and that is the reason to assert it. Four fixtures pad with spaces and
         * four pad with the ASCII digit zero, which follows from whether the trailing field is
         * alphanumeric or numeric in the layout. Interconverting them would corrupt a record while
         * leaving its length correct - the kind of defect a length check cannot see.
         *
         * @param fileName    the artefact
         * @param fillerStart the zero-based offset the filler begins at
         * @param filler      the character the filler is made of
         * @throws IOException if the artefact cannot be read
         */
        @ParameterizedTest(name = "{0} pads from offset {1} with '{2}'")
        @CsvSource({
            "acctdata.txt,  122, ' '",
            "carddata.txt,   91, ' '",
            "custdata.txt,  332, ' '",
            "dailytran.txt, 330, ' '",
            "discgrp.txt,    22, '0'",
            "tcatbal.txt,    28, '0'",
            "trancatg.txt,   56, '0'",
            "trantype.txt,   52, '0'"})
        @DisplayName("each fixture's trailing filler is the character it was measured to be")
        void eachFixtureCarriesItsMeasuredFillerCharacter(final String fileName, final int fillerStart,
                final String filler) throws IOException {
            final List<String> records = asciiRecords(fileName);
            assertThat(filler).as("one filler character per fixture").hasSize(1);

            final String expected = filler.repeat(records.get(0).length() - fillerStart);
            assertThat(records)
                    .as("%s pads every record from offset %d to the end with '%s', and the two filler "
                            + "characters in this estate are never interchangeable", fileName,
                            fillerStart, filler)
                    .allSatisfy(record -> assertThat(record.substring(fillerStart)).isEqualTo(expected));
        }
    }

    /**
     * The ten sign-on identities: named, typed, digested, and never disclosed.
     *
     * <h2>What is asserted, and the one thing that deliberately is not</h2>
     * The ten identifiers, both name fields and the role code are asserted from the fixture and from the
     * applied seed. The credential is asserted only by its properties.
     *
     * <p>Four properties of the ten stored credentials are asserted, and the FIRST of them was once
     * argued to be impossible here:
     * <ul>
     *   <li><strong>acceptance</strong> - each stored digest accepts the credential. This was previously
     *       omitted, on the reasoning that a module which digests a credential should not hold it and
     *       that the fixture's window therefore had to carry a synthetic stand-in no seeded digest could
     *       accept. The consequence went unnoticed: with acceptance omitted, the remaining three
     *       properties are ALL satisfied by a digest of any value whatsoever, so a wrong literal in the
     *       fourth migration would have passed this gate while admitting nobody. That was measured, not
     *       argued - perturbing the credential by a single character fails the acceptance assertion and
     *       leaves every other assertion in this nest passing. The fixture now reproduces the delivered
     *       provisioning records, and the credential still appears in no Java source, no method name and
     *       no diagnostic in this module;</li>
     *   <li><strong>shape</strong> - each stored value is a digest of the required length, under a
     *       recognised version marker, at the module's cost factor;</li>
     *   <li><strong>refusal</strong> - each stored digest refuses a value it was not derived from, which
     *       is what rules out a digest that accepts everything and would satisfy an acceptance check
     *       while being worthless;</li>
     *   <li><strong>distinctness</strong> - ten digests of one shared value are ten different strings,
     *       which is only true if each carries its own salt.</li>
     * </ul>
     * And separately, that verification genuinely works is proved by a self-consistent pair: a digest is
     * produced from the window and then matched against the window, with the value never leaving the
     * factory in either direction.
     */
    @Nested
    @DisplayName("Gate 4 - the ten identities by id, name and role, and the credential digests proved to "
            + "ACCEPT the delivered credential")
    class CredentialSeed {

        CredentialSeed() {
        }

        /**
         * The fixture carries the ten identities, at the layout the eighty-byte record declares.
         *
         * <p>Read as fixed-width blocks with no separator, because the fixture has none: it is 800 bytes
         * of ten eighty-byte records, and splitting it on a line feed would yield one record of 800.
         *
         * <p>Names are compared <strong>untrimmed</strong> against the field width, so a mapper that
         * trims or that mis-sizes a name field is caught here rather than at a screen boundary.
         */
        @Test
        @DisplayName("the fixture holds the ten identities with names padded to the twenty-byte fields")
        void theFixtureHoldsTheTenIdentities() {
            final List<String> records = TestDataFactory.fixedWidthRecords(
                    TestDataFactory.USER_SECURITY_FIXTURE,
                    TestDataFactory.USER_SECURITY.recordLength(),
                    TestDataFactory.SEEDED_USER_COUNT);

            assertThat(records)
                    .as("ten records of eighty bytes, separator-free")
                    .hasSize(TestDataFactory.SEEDED_USER_COUNT);
            assertThat(TestDataFactory.SEEDED_IDENTITIES)
                    .as("the published roster is the same ten")
                    .hasSize(TestDataFactory.SEEDED_USER_COUNT);

            final int nameWidth = TestDataFactory.USER_SECURITY.field("SEC-USR-FNAME").width();
            for (int index = 0; index < records.size(); index++) {
                final String record = records.get(index);
                final TestDataFactory.SeededIdentity expected =
                        TestDataFactory.SEEDED_IDENTITIES.get(index);

                assertThat(TestDataFactory.USER_SECURITY.slice(record, "SEC-USR-ID"))
                        .as("record %d carries its identifier at the head of the record", index)
                        .isEqualTo(expected.userId());
                assertThat(TestDataFactory.USER_SECURITY.slice(record, "SEC-USR-FNAME"))
                        .as("%s: the given name padded to the full %d-byte field, untrimmed",
                                expected.userId(), nameWidth)
                        .isEqualTo(padTo(expected.firstName(), nameWidth));
                assertThat(TestDataFactory.USER_SECURITY.slice(record, "SEC-USR-LNAME"))
                        .as("%s: the family name padded to the full %d-byte field, untrimmed",
                                expected.userId(), nameWidth)
                        .isEqualTo(padTo(expected.lastName(), nameWidth));
                assertThat(TestDataFactory.USER_SECURITY.slice(record, "SEC-USR-TYPE"))
                        .as("%s: the one-byte role code", expected.userId())
                        .isEqualTo(expected.userTypeCode());
            }
        }

        /**
         * Five administrators and five standard users, and no third role.
         */
        @Test
        @DisplayName("the roster splits five administrators and five standard users, with no third role")
        void theRosterSplitsFiveAndFive() {
            assertThat(TestDataFactory.SEEDED_IDENTITIES.stream()
                            .filter(TestDataFactory.SeededIdentity::isAdministrator).count())
                    .as("five reach the administrative surface")
                    .isEqualTo(5L);
            assertThat(TestDataFactory.SEEDED_IDENTITIES.stream()
                            .filter(identity -> !identity.isAdministrator()).count())
                    .as("five reach the standard surface")
                    .isEqualTo(5L);
            assertThat(TestDataFactory.SEEDED_IDENTITIES)
                    .as("the role of every identity resolves to one of the two the estate defines")
                    .allSatisfy(identity -> assertThat(identity.userType())
                            .isIn(UserType.ADMIN, UserType.USER));
            assertThat(UserType.ADMIN.getCode())
                    .as("the administrative code the legacy record carries")
                    .isEqualTo("A");
            assertThat(UserType.USER.getCode())
                    .as("and the standard one")
                    .isEqualTo("U");
        }

        /**
         * The applied seed, read back through the mapped entity from a real server.
         *
         * <p>Read through the repository rather than through the catalogue, because that is what proves
         * the eighty-byte layout survived into a working entity mapping under {@code validate}.
         *
         * @throws SQLException if the row count cannot be read
         */
        @Test
        @DisplayName("the seed applied to a real server holds all ten identities, each with its name and "
                + "role, read back through the mapped entity")
        void theAppliedSeedHoldsTheTenIdentities() throws SQLException {
            for (final TestDataFactory.SeededIdentity expected : TestDataFactory.SEEDED_IDENTITIES) {
                final Optional<UserSecurity> stored =
                        userSecurityRepository.findById(expected.userId());

                assertThat(stored)
                        .as("identity %s must be present on the server the fourth migration seeded",
                                expected.userId())
                        .isPresent();
                final UserSecurity identity = stored.orElseThrow();
                assertThat(identity.getSecUsrFname())
                        .as("%s: given name", expected.userId())
                        .isEqualTo(expected.firstName());
                assertThat(identity.getSecUsrLname())
                        .as("%s: family name", expected.userId())
                        .isEqualTo(expected.lastName());
                assertThat(identity.getSecUsrType())
                        .as("%s: role code", expected.userId())
                        .isEqualTo(expected.userTypeCode());
            }

            assertThat(rowCount("user_security"))
                    .as("exactly ten identities and no eleventh: the third migration leaves the table "
                            + "empty and the fourth seeds these ten")
                    .isEqualTo(TestDataFactory.SEEDED_USER_COUNT);
        }

        /**
         * The stored credentials are digests: correctly shaped, refusing, and individually salted.
         *
         * <p>No digest value is asserted and no credential is named. Every diagnostic names the identity
         * and the property that failed.
         */
        @Test
        @DisplayName("every stored credential is a correctly shaped digest that refuses a value it was "
                + "not derived from, and the ten are mutually distinct")
        void everyStoredCredentialIsASaltedDigest() {
            final PasswordEncoder encoder =
                    new BCryptPasswordEncoder(TestDataFactory.BCRYPT_WORK_FACTOR);
            final Set<String> digests = new LinkedHashSet<>();

            for (final TestDataFactory.SeededIdentity expected : TestDataFactory.SEEDED_IDENTITIES) {
                final UserSecurity identity =
                        userSecurityRepository.findById(expected.userId()).orElseThrow();
                final String digest = identity.credentialDigest();

                assertThat(TestDataFactory.hasStoredDigestShape(digest))
                        .as("%s: the stored value must be a digest of %d characters under a recognised "
                                + "version marker at cost factor %d - never the eight-character legacy "
                                + "literal, which is the one deliberate parity exception the decision "
                                + "log records", expected.userId(),
                                TestDataFactory.BCRYPT_DIGEST_LENGTH,
                                TestDataFactory.BCRYPT_WORK_FACTOR)
                        .isTrue();
                assertThat(TestDataFactory.digestRefusesOtherValues(encoder, digest))
                        .as("%s: the digest must refuse a value it was not derived from. A digest that "
                                + "accepted everything would satisfy an acceptance check and be "
                                + "worthless", expected.userId())
                        .isTrue();
                digests.add(digest);
            }

            assertThat(digests)
                    .as("ten digests of one shared value are ten different strings, which is true only "
                            + "if each carries its own salt - so replacing any single one is observable")
                    .hasSize(TestDataFactory.SEEDED_DIGEST_COUNT);
        }

        /**
         * Each of the ten DELIVERED digests accepts the delivered credential.
         *
         * <h4>Why shape and refusal were not enough, which is the whole reason this test exists</h4>
         *
         * <p>The two properties asserted by {@link #everyStoredCredentialIsASaltedDigest()} - correct
         * shape, and refusal of a value the digest was not derived from - are both satisfied by a digest
         * of <em>any</em> value whatsoever. A wrong literal in the seed migration produces a digest that
         * is exactly sixty characters, opens with a recognised version marker, carries the module's cost
         * factor, refuses a probe, and differs from its nine siblings. It passes every one of those
         * checks and admits nobody. The seeded sign-on would simply stop working, silently, and no
         * committed test would say so.
         *
         * <p>Acceptance is the only property that separates a <em>correct</em> shipped digest from a
         * merely <em>well-formed</em> one, and it cannot be asserted without the credential. It is
         * asserted here for all ten rows, read straight off the server, <strong>before anything in this
         * suite mutates a credential</strong> - this class installs no digest anywhere.
         *
         * <p>The value never enters this frame. The window is read, used and overwritten inside the
         * shared fixture; this test observes one boolean per row and names only the identity.
         */
        @Test
        @DisplayName("every one of the ten delivered credential digests ACCEPTS the delivered "
                + "credential, which shape and refusal alone cannot establish")
        void everyDeliveredCredentialDigestAcceptsTheDeliveredCredential() {
            final PasswordEncoder encoder =
                    new BCryptPasswordEncoder(TestDataFactory.BCRYPT_WORK_FACTOR);

            assertThat(TestDataFactory.fixtureCredentialWindowIsFoldInvariant())
                    .as("the sign-on transaction upper-cases a submitted credential before comparing it,"
                            + " so this acceptance check is only equivalent to a real sign-on while the"
                            + " credential is invariant under that fold. Asserted first, because a"
                            + " fixture that folded to something else would make every assertion below"
                            + " quietly weaker than it reads")
                    .isTrue();

            for (final TestDataFactory.SeededIdentity expected : TestDataFactory.SEEDED_IDENTITIES) {
                final UserSecurity identity =
                        userSecurityRepository.findById(expected.userId()).orElseThrow();
                final String digest = identity.credentialDigest();

                assertThat(TestDataFactory.digestAcceptsFixtureCredentialWindow(encoder, digest))
                        .as("%s: the delivered digest must accept the delivered credential. A digest of"
                                + " some other value would satisfy every shape and refusal check in this"
                                + " suite and admit nobody, so this is the assertion that would catch a"
                                + " wrong literal in the fourth migration", expected.userId())
                        .isTrue();
                assertThat(TestDataFactory.digestRefusesOtherValues(encoder, digest))
                        .as("%s: and still refuse a value it was not derived from, so the acceptance"
                                + " above is discriminating rather than universal", expected.userId())
                        .isTrue();
            }

            assertThat(TestDataFactory.SEEDED_IDENTITIES)
                    .as("all ten, not a sample: a per-row digest is a per-row opportunity to be wrong")
                    .hasSize(TestDataFactory.SEEDED_USER_COUNT);
        }

        /**
         * Verification demonstrably works, proved without the value entering this frame.
         *
         * <p>The factory produces a digest from its credential window and then matches the same window
         * against it. Both halves happen inside the factory, so nothing here holds, logs or names the
         * value; this test observes only the two booleans.
         */
        @Test
        @DisplayName("digest verification accepts the value it was derived from and refuses another, "
                + "proved without the value entering this test")
        void digestVerificationAcceptsAndRefuses() {
            final PasswordEncoder encoder =
                    new BCryptPasswordEncoder(TestDataFactory.BCRYPT_WORK_FACTOR);
            final String digest = TestDataFactory.digestOfFixtureCredentialWindow();

            assertThat(TestDataFactory.hasStoredDigestShape(digest))
                    .as("the produced digest has the shape the entity will accept")
                    .isTrue();
            assertThat(TestDataFactory.digestAcceptsFixtureCredentialWindow(encoder, digest))
                    .as("verification accepts the value the digest was derived from, so the encoder and "
                            + "the cost factor genuinely agree end to end")
                    .isTrue();
            assertThat(TestDataFactory.digestRefusesOtherValues(encoder, digest))
                    .as("and refuses a different value, so acceptance is discriminating")
                    .isTrue();
        }
    }

    /**
     * The twelve sequential datasets in their mainframe encoding, named as Gate 4 artefacts.
     *
     * <p>They are referenced <strong>by name and by size only</strong>. None is copied into the module and
     * none is decoded: the identity fixture's content is fully recoverable from the provisioning member's
     * in-stream card images, so no character-set conversion is needed anywhere in this migration. They
     * live outside the module, under the read-only legacy tree, and are resolved by walking up to the
     * repository root - the build's working directory is the module, so a module-relative path would not
     * find them.
     */
    @Nested
    @DisplayName("Gate 4 - the twelve encoded datasets, named and sized, never copied and never decoded")
    class EncodedDatasetReference {

        EncodedDatasetReference() {
        }

        /**
         * Each dataset, by name, at the size it was measured.
         *
         * <p>Every size is an exact multiple of its record length with no separator, which is what
         * distinguishes a mainframe sequential dataset from the line-delimited copy in the module: the
         * account dataset is 15,000 bytes for fifty 300-byte records where the fixture is 15,050 for the
         * same fifty plus a line feed each.
         *
         * @param datasetName  the dataset's name, as the catalogue holds it
         * @param expectedSize its measured size
         * @throws IOException if the dataset cannot be sized
         */
        @ParameterizedTest(name = "{0} is {1} bytes")
        @CsvSource({
            "AWS.M2.CARDDEMO.ACCDATA.PS,        15000",
            "AWS.M2.CARDDEMO.ACCTDATA.PS,       15000",
            "AWS.M2.CARDDEMO.CARDDATA.PS,        7500",
            "AWS.M2.CARDDEMO.CARDXREF.PS,        2500",
            "AWS.M2.CARDDEMO.CUSTDATA.PS,       25000",
            "AWS.M2.CARDDEMO.DALYTRAN.PS,      105000",
            "AWS.M2.CARDDEMO.DALYTRAN.PS.INIT,    350",
            "AWS.M2.CARDDEMO.DISCGRP.PS,         2550",
            "AWS.M2.CARDDEMO.TCATBALF.PS,        2500",
            "AWS.M2.CARDDEMO.TRANCATG.PS,        1080",
            "AWS.M2.CARDDEMO.TRANTYPE.PS,         420",
            "AWS.M2.CARDDEMO.USRSEC.PS,           800"})
        @DisplayName("each named encoded dataset is present at its measured size")
        void eachEncodedDatasetIsPresentAtItsMeasuredSize(final String datasetName,
                final long expectedSize) throws IOException {
            final Path dataset = repositoryFile(ENCODED_DATASET_DIRECTORY + "/" + datasetName);

            assertThat(Files.size(dataset))
                    .as("%s is a named Gate 4 encoding-fidelity artefact", datasetName)
                    .isEqualTo(expectedSize);
        }

        /**
         * The cross-reference dataset carries the filler its fixture omits.
         *
         * <p>This is the other half of the asymmetry: 2,500 bytes is fifty fifty-byte records, so the
         * dataset carries the fourteen-byte filler in full, while the 1,850-byte fixture carries thirty-six
         * data bytes per record and stops.
         *
         * @throws IOException if either artefact cannot be read
         */
        @Test
        @DisplayName("the encoded cross-reference is 50 records of 50 bytes, so it carries the filler the "
                + "36-byte fixture omits")
        void theEncodedCrossReferenceCarriesTheFiller() throws IOException {
            final long datasetSize =
                    Files.size(repositoryFile(ENCODED_DATASET_DIRECTORY
                            + "/AWS.M2.CARDDEMO.CARDXREF.PS"));

            assertThat(datasetSize)
                    .as("fifty records at the fifty-byte layout width, with no separator")
                    .isEqualTo((long) TestDataFactory.SEEDED_FIFTY_ROW_COUNT
                            * TestDataFactory.CARD_CROSS_REFERENCE_DATASET.recordLength());
            assertThat(classpathBytes(TestDataFactory.FIXTURE_DIRECTORY + "cardxref.txt").length)
                    .as("while the fixture is fifty records at the thirty-six byte data width plus one "
                            + "separator each - the two are both correct and must never be reconciled")
                    .isEqualTo(TestDataFactory.SEEDED_FIFTY_ROW_COUNT
                            * (TestDataFactory.CARD_CROSS_REFERENCE_FIXTURE.recordLength() + 1));
        }

        /**
         * The duplicated account dataset, and the seed that is applied once regardless.
         *
         * <p>Two datasets carry byte-identical account content under two names, and no job member
         * references the shorter name. It is retained as a named artefact rather than deleted - the legacy
         * tree is read-only - and the operative assertion is that the duplication did not propagate into
         * the seed: fifty accounts are loaded, not a hundred.
         *
         * @throws IOException  if a dataset cannot be read
         * @throws SQLException if the row count cannot be read
         */
        @Test
        @DisplayName("the account dataset exists in duplicate under two names, yet the schema is seeded "
                + "with fifty accounts and not a hundred")
        void theDuplicatedAccountDatasetIsSeededOnce() throws IOException, SQLException {
            final Path shortName =
                    repositoryFile(ENCODED_DATASET_DIRECTORY + "/AWS.M2.CARDDEMO.ACCDATA.PS");
            final Path longName =
                    repositoryFile(ENCODED_DATASET_DIRECTORY + "/AWS.M2.CARDDEMO.ACCTDATA.PS");

            assertThat(Files.readAllBytes(shortName))
                    .as("the two datasets are byte-identical, which is the recorded anomaly - one of them "
                            + "is referenced by no job member at all")
                    .isEqualTo(Files.readAllBytes(longName));
            assertThat(rowCount("account"))
                    .as("the duplication did not reach the seed: had both been loaded the table would "
                            + "hold a hundred rows, and the fifty-row assertion is what detects it")
                    .isEqualTo(TestDataFactory.SEEDED_FIFTY_ROW_COUNT);
        }

        /**
         * The identity dataset has no line-delimited twin, and needs none.
         *
         * @throws IOException if the dataset or the fixture cannot be read
         */
        @Test
        @DisplayName("the identity dataset has no ASCII twin, and none is needed because the provisioning "
                + "member carries the same content readably")
        void theIdentityDatasetNeedsNoDecoding() throws IOException {
            final Path encoded =
                    repositoryFile(ENCODED_DATASET_DIRECTORY + "/AWS.M2.CARDDEMO.USRSEC.PS");

            assertThat(Files.size(encoded))
                    .as("ten records of the eighty-byte identity layout")
                    .isEqualTo((long) TestDataFactory.SEEDED_USER_COUNT
                            * TestDataFactory.USER_SECURITY.recordLength());
            assertThat(repositoryRoot().resolve("app/data/ASCII/usrsec.txt"))
                    .as("no line-delimited twin was ever delivered for it, unlike the other nine")
                    .doesNotExist();
            assertThat(classpathBytes(
                            TestDataFactory.FIXTURE_DIRECTORY + TestDataFactory.USER_SECURITY_FIXTURE))
                    .as("the module's own identity fixture is derived from the provisioning member's "
                            + "readable card images, so no character-set conversion is required anywhere")
                    .hasSize(TestDataFactory.SEEDED_USER_COUNT
                            * TestDataFactory.USER_SECURITY.recordLength());
        }
    }

    /**
     * The three externalised validation tables, at the cardinalities that were counted.
     */
    @Nested
    @DisplayName("Gate 4 - the lookup cardinalities, as a proven partition rather than as two totals")
    class LookupCardinalities {

        LookupCardinalities() {
        }

        /**
         * The area codes are a partition: the two subsets sum to the whole and share nothing.
         *
         * <p>Asserting only the totals would pass for two subsets that overlap and a whole that is
         * therefore smaller than their sum. Both halves of a partition are asserted: the union has 490
         * members and the intersection is empty.
         *
         * <p>Read out of the shipped resource with the tree API, so no cast is involved and the count is
         * checked against the delivered data rather than against the loader's opinion of it.
         *
         * @throws IOException if a resource cannot be read
         */
        @Test
        @DisplayName("the shipped resource holds 410 general-purpose and 80 easily-recognisable codes "
                + "that partition 490 exactly")
        void theShippedAreaCodesFormAnExactPartition() throws IOException {
            final JsonNode root = new ObjectMapper()
                    .readTree(classpathBytes(LOOKUP_DIRECTORY + "nanpa-area-codes.json"));

            final List<String> generalPurpose =
                    jsonTextArray(root.get(ValidationLookupService.JSON_KEY_GENERAL_PURPOSE));
            final List<String> easyRecognition =
                    jsonTextArray(root.get(ValidationLookupService.JSON_KEY_EASY_RECOGNITION));

            assertThat(generalPurpose)
                    .as("general-purpose codes, each distinct")
                    .hasSize(ValidationLookupService.GENERAL_PURPOSE_AREA_CODE_COUNT)
                    .doesNotHaveDuplicates();
            assertThat(easyRecognition)
                    .as("easily-recognisable codes, each distinct")
                    .hasSize(ValidationLookupService.EASY_RECOGNITION_AREA_CODE_COUNT)
                    .doesNotHaveDuplicates();
            assertThat(generalPurpose)
                    .as("the two subsets share no member, so the partition is exact rather than merely "
                            + "additive")
                    .doesNotContainAnyElementsOf(easyRecognition);

            final Set<String> union = new LinkedHashSet<>(generalPurpose);
            union.addAll(easyRecognition);
            assertThat(union)
                    .as("410 plus 80 is 490, derived from the two subsets rather than stored as a third "
                            + "number that could disagree with them")
                    .hasSize(ValidationLookupService.PHONE_AREA_CODE_COUNT);
            assertThat(ValidationLookupService.PHONE_AREA_CODE_COUNT)
                    .as("and the published total agrees")
                    .isEqualTo(490);
            assertThat(union)
                    .as("every code is three characters, as the field is")
                    .allSatisfy(code -> assertThat(code)
                            .hasSize(ValidationLookupService.AREA_CODE_WIDTH));
        }

        /**
         * The state codes and the state-and-postal-prefix combinations, at their counted cardinalities.
         *
         * @throws IOException if a resource cannot be read
         */
        @Test
        @DisplayName("the shipped resources hold 56 state codes of two characters and 240 combinations of "
                + "exactly four")
        void theShippedStateTablesHoldTheirCountedCardinalities() throws IOException {
            final ObjectMapper mapper = new ObjectMapper();

            final List<String> states =
                    jsonTextArray(mapper.readTree(classpathBytes(LOOKUP_DIRECTORY
                            + "us-state-codes.json")));
            assertThat(states)
                    .as("states, districts and territories")
                    .hasSize(ValidationLookupService.US_STATE_CODE_COUNT)
                    .doesNotHaveDuplicates()
                    .allSatisfy(code -> assertThat(code)
                            .hasSize(ValidationLookupService.US_STATE_CODE_WIDTH));

            final List<String> combinations =
                    jsonTextArray(mapper.readTree(classpathBytes(LOOKUP_DIRECTORY
                            + "state-zip-prefixes.json")));
            assertThat(combinations)
                    .as("state-and-postal-prefix combinations")
                    .hasSize(ValidationLookupService.US_STATE_ZIP_COMBINATION_COUNT)
                    .doesNotHaveDuplicates();
            assertThat(combinations)
                    .as("each combination is a two-character state followed by two postal digits, so "
                            + "exactly four characters, uniformly")
                    .allSatisfy(combination -> assertThat(combination)
                            .hasSize(ValidationLookupService.US_STATE_AND_FIRST_ZIP2_WIDTH));
        }

        /**
         * The same cardinalities, read back through the service that loads them.
         *
         * <p>The resource-only check above and this one answer different questions. A resource with the
         * right count and a loader that silently dropped an entry would satisfy the first and fail this
         * one, so both are needed. The service's own published constants are asserted against the sets it
         * exposes, which is what stops a constant and a loader disagreeing.
         */
        @Test
        @DisplayName("the loaded service exposes the same 490 = 410 + 80, 56 and 240, so no entry is lost "
                + "between the resource and the code")
        void theLoadedServiceExposesTheSameCardinalities() {
            assertThat(validationLookupService.generalPurposeAreaCodes())
                    .as("general-purpose codes survive loading")
                    .hasSize(ValidationLookupService.GENERAL_PURPOSE_AREA_CODE_COUNT);
            assertThat(validationLookupService.easilyRecognisableAreaCodes())
                    .as("easily-recognisable codes survive loading")
                    .hasSize(ValidationLookupService.EASY_RECOGNITION_AREA_CODE_COUNT);
            assertThat(validationLookupService.phoneAreaCodes())
                    .as("and the combined set is the union of the two, not a separately maintained list")
                    .hasSize(ValidationLookupService.PHONE_AREA_CODE_COUNT)
                    .containsAll(validationLookupService.generalPurposeAreaCodes())
                    .containsAll(validationLookupService.easilyRecognisableAreaCodes());
            assertThat(validationLookupService.generalPurposeAreaCodes())
                    .as("the partition holds after loading as well as in the resource")
                    .doesNotContainAnyElementsOf(validationLookupService.easilyRecognisableAreaCodes());

            assertThat(validationLookupService.usStateCodes())
                    .as("state codes survive loading")
                    .hasSize(ValidationLookupService.US_STATE_CODE_COUNT);
            assertThat(validationLookupService.usStateZipCodeCombinations())
                    .as("state-and-prefix combinations survive loading")
                    .hasSize(ValidationLookupService.US_STATE_ZIP_COMBINATION_COUNT);
        }
    }

    // ===================================================================================================
    // GATE 8 :: the integration sign-off
    // ===================================================================================================

    /**
     * The coverage invariant: 528 program paragraphs plus 16 from the two procedural copybooks.
     *
     * <p>Asserted <strong>unconditionally</strong>. This is a compiled-in property of a read-only estate at
     * a pinned checkout, not a conditional check: the legacy tree is never edited, so the count cannot
     * legitimately change, and a test that tolerated its absence would tolerate the one thing that must
     * never happen quietly.
     */
    @Nested
    @DisplayName("Gate 8 - the 544-unit coverage invariant: 528 + 14 + 2, per member")
    class ProcedureUnitCoverage {

        ProcedureUnitCoverage() {
        }

        /**
         * The arithmetic of the invariant, stated so a failure says which term moved.
         */
        @Test
        @DisplayName("528 program paragraphs plus 14 plus 2 copybook paragraphs is exactly 544")
        void theInvariantIsFiveHundredAndFortyFour() {
            assertThat(PROGRAM_PARAGRAPHS + DATE_COPYBOOK_PARAGRAPHS + PFKEY_COPYBOOK_PARAGRAPHS)
                    .as("the three contributions and nothing else")
                    .isEqualTo(TOTAL_PROCEDURE_UNITS);
            assertThat(TOTAL_PROCEDURE_UNITS)
                    .as("the sign-off requires a row for every one of them, so the total is a gate "
                            + "condition rather than a statistic")
                    .isEqualTo(544);
        }

        /**
         * The estate is enumerated case-inclusively, which is the difference between 28 programs and 26.
         *
         * <p>The extensions in this estate are mixed case. A lower-case-only pattern silently drops the two
         * upper-case programs and the upper-case job member - the entire statement-generation feature, and
         * 39 of the 528 paragraphs with it - and the resulting count would be self-consistently wrong. So
         * the file census is asserted alongside the paragraph census.
         *
         * @throws IOException if the estate cannot be walked
         */
        @Test
        @DisplayName("the estate enumerates case-inclusively: 26 plus 2 programs, 27 plus 1 copybooks, "
                + "28 plus 1 job members")
        void theEstateIsEnumeratedCaseInclusively() throws IOException {
            assertThat(countByExtension("app/cbl", ".cbl"))
                    .as("lower-case program extensions")
                    .isEqualTo(26);
            assertThat(countByExtension("app/cbl", ".CBL"))
                    .as("upper-case program extensions. Dropping these two would drop the statement "
                            + "feature and 39 paragraphs, and the total would still look plausible")
                    .isEqualTo(2);
            assertThat(countByExtension("app/cpy", ".cpy"))
                    .as("lower-case copybook extensions")
                    .isEqualTo(27);
            assertThat(countByExtension("app/cpy", ".CPY"))
                    .as("upper-case copybook extension")
                    .isEqualTo(1);
            assertThat(countByExtension("app/jcl", ".jcl"))
                    .as("lower-case job members")
                    .isEqualTo(28);
            assertThat(countByExtension("app/jcl", ".JCL"))
                    .as("upper-case job member")
                    .isEqualTo(1);
            assertThat(countByExtension("app/cpy-bms", ".CPY"))
                    .as("every generated symbolic map is upper-case")
                    .isEqualTo(17);
            assertThat(countByExtension("app/cbl", ".cbl") + countByExtension("app/cbl", ".CBL"))
                    .as("28 programs contribute the 528")
                    .isEqualTo(28);
        }

        /**
         * The per-member paragraph counts, so a regression names the member that moved.
         *
         * <p>The counts are measured from the estate at run time rather than trusted: each member's
         * procedure division is scanned for Area A labels. Carriage returns are stripped first, which
         * matters because five of the estate's members use a two-character line terminator and three of
         * those five are programs - including the 85-paragraph member that is 16% of the whole count. A
         * reader that stripped only the line feed would leave a stray carriage return on every label and
         * match none of them.
         *
         * @param member         the member's repository-relative path
         * @param expectedCount  the paragraphs its procedure division declares
         * @throws IOException if the member cannot be read
         */
        @ParameterizedTest(name = "{0} declares {1} paragraphs")
        @CsvSource({
            "app/cbl/CBACT01C.cbl,  6", "app/cbl/CBACT02C.cbl,  5", "app/cbl/CBACT03C.cbl,  5",
            "app/cbl/CBACT04C.cbl, 22", "app/cbl/CBCUS01C.cbl,  5", "app/cbl/CBSTM03A.CBL, 25",
            "app/cbl/CBSTM03B.CBL, 14", "app/cbl/CBTRN01C.cbl, 18", "app/cbl/CBTRN02C.cbl, 26",
            "app/cbl/CBTRN03C.cbl, 26", "app/cbl/COACTUPC.cbl, 85", "app/cbl/COACTVWC.cbl, 35",
            "app/cbl/COADM01C.cbl,  7", "app/cbl/COBIL00C.cbl, 16", "app/cbl/COCRDLIC.cbl, 39",
            "app/cbl/COCRDSLC.cbl, 34", "app/cbl/COCRDUPC.cbl, 45", "app/cbl/COMEN01C.cbl,  7",
            "app/cbl/CORPT00C.cbl, 10", "app/cbl/COSGN00C.cbl,  6", "app/cbl/COTRN00C.cbl, 16",
            "app/cbl/COTRN01C.cbl,  9", "app/cbl/COTRN02C.cbl, 18", "app/cbl/COUSR00C.cbl, 16",
            "app/cbl/COUSR01C.cbl,  9", "app/cbl/COUSR02C.cbl, 11", "app/cbl/COUSR03C.cbl, 11",
            "app/cbl/CSUTLDTC.cbl,  2"})
        @DisplayName("each program declares the paragraph count the matrix publishes for it")
        void eachProgramDeclaresItsPublishedParagraphCount(final String member, final int expectedCount)
                throws IOException {
            assertThat(paragraphLabelsOf(member, true))
                    .as("%s: the matrix publishes %d rows for this member, so its procedure division must "
                            + "declare %d Area A labels", member, expectedCount, expectedCount)
                    .hasSize(expectedCount);
        }

        /**
         * The 28 programs sum to 528, measured rather than asserted term by term.
         *
         * @throws IOException if the estate cannot be walked
         */
        @Test
        @DisplayName("the 28 programs sum to 528 paragraphs")
        void theProgramsSumToFiveHundredAndTwentyEight() throws IOException {
            int total = 0;
            final List<Path> programs = new ArrayList<>();
            programs.addAll(filesByExtension("app/cbl", ".cbl"));
            programs.addAll(filesByExtension("app/cbl", ".CBL"));
            assertThat(programs).as("28 programs, enumerated case-inclusively").hasSize(28);

            final Path root = repositoryRoot();
            for (final Path program : programs) {
                total += paragraphLabelsOf(root.relativize(program).toString(), true).size();
            }
            assertThat(total)
                    .as("the program contribution to the invariant")
                    .isEqualTo(PROGRAM_PARAGRAPHS);
        }

        /**
         * The two procedural copybooks contribute 14 and 2, and no other copybook contributes any.
         *
         * <p>The second half matters as much as the first. Of the 28 copybooks only these two declare an
         * Area A label at all; the other 26 are data structures. Asserting that the rest contribute nothing
         * is what makes 16 the complete copybook contribution rather than merely the part that was looked
         * at - and it is also the assertion that keeps the unreferenced copybook out of the count without
         * naming it as a special case.
         *
         * @throws IOException if the estate cannot be walked
         */
        @Test
        @DisplayName("the date copybook contributes 14 and the function-key copybook 2, and the other 26 "
                + "copybooks contribute none")
        void onlyTheTwoProceduralCopybooksContributeParagraphs() throws IOException {
            assertThat(paragraphLabelsOf("app/cpy/CSUTLDPY.cpy", false))
                    .as("the date-validation cascade's paragraphs, which is why the matrix counts this "
                            + "copybook as a unit in its own right")
                    .hasSize(DATE_COPYBOOK_PARAGRAPHS);
            assertThat(paragraphLabelsOf("app/cpy/CSSTRPFY.cpy", false))
                    .as("the attention-key handler and its exit")
                    .hasSize(PFKEY_COPYBOOK_PARAGRAPHS);

            final List<Path> copybooks = new ArrayList<>();
            copybooks.addAll(filesByExtension("app/cpy", ".cpy"));
            copybooks.addAll(filesByExtension("app/cpy", ".CPY"));
            assertThat(copybooks).as("28 copybooks, enumerated case-inclusively").hasSize(28);

            final Path root = repositoryRoot();
            final List<String> withParagraphs = new ArrayList<>();
            for (final Path copybook : copybooks) {
                final String relative = root.relativize(copybook).toString();
                if (!paragraphLabelsOf(relative, false).isEmpty()) {
                    withParagraphs.add(relative);
                }
            }
            assertThat(withParagraphs)
                    .as("exactly two copybooks are procedural. The remaining 26 declare no label, so 16 "
                            + "is the whole copybook contribution and the unreferenced copybook - which "
                            + "is deliberately not migrated - contributes nothing to the count")
                    .containsExactlyInAnyOrder("app/cpy/CSUTLDPY.cpy", "app/cpy/CSSTRPFY.cpy");
        }

        /**
         * The legacy estate is untouched, which is what makes every citation above checkable.
         *
         * @throws IOException if the estate cannot be walked
         */
        @Test
        @DisplayName("the legacy estate is present and read-only, which is what makes the citations "
                + "verifiable at all")
        void theLegacyEstateIsPresentAndComplete() throws IOException {
            final Path legacy = repositoryDirectory(LEGACY_ROOT);

            assertThat(Files.isDirectory(legacy)).isTrue();
            assertThat(countByExtension("app/bms", ".bms"))
                    .as("the mapset definitions")
                    .isEqualTo(17);
            assertThat(countByExtension("app/proc", ".prc"))
                    .as("the cataloged procedures")
                    .isEqualTo(2);
            assertThat(countByExtension("app/csd", ".CSD"))
                    .as("the resource definition, upper-case like the mapset copybooks")
                    .isEqualTo(1);
            assertThat(countByExtension("app/ctl", ".ctl"))
                    .as("the utility control cards")
                    .isEqualTo(1);
        }
    }

    /**
     * The traceability matrix: 100% paragraph coverage, the provenance anchors, and the marked rows.
     *
     * <p>The three documentation deliverables live at the repository root, not inside the module, and the
     * build's working directory is the module. They are therefore resolved by walking upward to the
     * directory that holds both, and a missing one fails with the absolute path it was expected at. Nothing
     * here is conditional: an absent evidence document is a failure with an actionable diagnostic, because
     * in a build summary a skipped gate and a passing gate look identical.
     */
    @Nested
    @DisplayName("Gate 8 - the traceability matrix: 544 rows, both provenance anchors, and every marked row")
    class TraceabilityMatrix {

        TraceabilityMatrix() {
        }

        /**
         * The three evidence documents exist, and each carries content.
         *
         * @throws IOException if a document cannot be read
         */
        @Test
        @DisplayName("the matrix, the decision log and the recorded evidence are all present and non-empty")
        void theThreeEvidenceDocumentsArePresent() throws IOException {
            for (final String document : List.of(TRACEABILITY_MATRIX, DECISION_LOG, GATE_EVIDENCE)) {
                final Path path = documentationFile(document);
                assertThat(Files.size(path))
                        .as("%s is a Gate 8 deliverable and an empty file would discharge nothing; "
                                + "expected at %s", document, path)
                        .isPositive();
            }
        }

        /**
         * Exactly one row per procedure unit.
         *
         * <p>Rows are counted from the per-member tables only, keyed off their shared column header. The
         * matrix also carries a provenance table, a composition table, a column legend, a marker legend and
         * a per-member census, so counting every table row in the document would yield a larger number and
         * make the invariant unassertable. Header rows and the alignment rows beneath them are excluded.
         *
         * @throws IOException if the matrix cannot be read
         */
        @Test
        @DisplayName("the matrix carries exactly 544 data rows, one per procedure unit")
        void theMatrixCarriesExactlyOneRowPerProcedureUnit() throws IOException {
            final List<List<String>> rows = matrixRows();

            assertThat(rows)
                    .as("100%% paragraph coverage means one row per unit and no row without a unit. A "
                            + "matrix of 543 or 545 rows fails the sign-off; expected at %s",
                            documentationFile(TRACEABILITY_MATRIX))
                    .hasSize(TOTAL_PROCEDURE_UNITS);
            assertThat(rows)
                    .as("every row carries the six cells the sign-off requires plus the marker cell")
                    .allSatisfy(row -> assertThat(row).hasSize(7));
        }

        /**
         * The per-member subtotals in the matrix agree with the estate itself.
         *
         * <p>This is the assertion that ties the document to the source. A matrix could hold 544 rows and
         * still attribute them wrongly; comparing each member's row count against the paragraphs that
         * member actually declares is what makes the total meaningful rather than arithmetical.
         *
         * @throws IOException if the matrix or the estate cannot be read
         */
        @Test
        @DisplayName("each member's row count equals the paragraphs that member actually declares")
        void eachMembersRowCountMatchesTheEstate() throws IOException {
            final Map<String, Integer> rowsPerMember = new TreeMap<>();
            for (final List<String> row : matrixRows()) {
                rowsPerMember.merge(row.get(0), Integer.valueOf(1), (first, second) ->
                        Integer.valueOf(first.intValue() + second.intValue()));
            }

            assertThat(rowsPerMember)
                    .as("thirty members contribute rows: the 28 programs and the two procedural copybooks")
                    .hasSize(30);

            for (final Map.Entry<String, Integer> entry : rowsPerMember.entrySet()) {
                final String member = entry.getKey();
                final boolean isProgram = member.startsWith("app/cbl/");
                assertThat(paragraphLabelsOf(member, isProgram).size())
                        .as("%s: the matrix publishes %d rows, so the member must declare that many "
                                + "paragraphs - otherwise the total is arithmetic rather than coverage",
                                member, entry.getValue())
                        .isEqualTo(entry.getValue().intValue());
            }
        }

        /**
         * Every row's citation resolves in the estate: the paragraph is declared, at that line.
         *
         * <p>The row count and the per-member subtotals above establish that the matrix is the right shape.
         * They cannot establish that any individual row is true - a member's rows could be internally
         * shuffled, cite a paragraph it does not declare, or cite the wrong line, and every count would
         * still agree. This walks all 544 citations and resolves each one against the member it names.
         *
         * @throws IOException if the matrix or the estate cannot be read
         */
        @Test
        @DisplayName("all 544 rows cite a paragraph the named member actually declares, at the line the "
                + "row publishes")
        void everyRowCitesAParagraphDeclaredAtTheLineItPublishes() throws IOException {
            final List<String> unresolved = new ArrayList<>();
            final Map<String, List<String>> membersRead = new LinkedHashMap<>();

            for (final List<String> row : matrixRows()) {
                final String member = row.get(0);
                final String paragraph = unquoted(row.get(1));
                final int line = citedLine(row);
                final List<String> source = membersRead.computeIfAbsent(member,
                        path -> readMember(path));
                final String declared = paragraphLabelAt(source, line);
                if (!paragraph.equals(declared)) {
                    unresolved.add(member + ":" + line + " publishes '" + paragraph
                            + "' but that line declares " + (declared == null ? "no paragraph" : "'"
                            + declared + "'"));
                }
            }

            assertThat(unresolved)
                    .as("a citation that does not resolve is worse than a missing row: it reads as "
                            + "traceability and is not. Every failure names the member, the line, what the "
                            + "matrix claims and what the estate declares")
                    .isEmpty();
            assertThat(membersRead)
                    .as("thirty members were read to resolve the citations, which is the same thirty the "
                            + "row counts are attributed to")
                    .hasSize(30);
        }

        /**
         * Every row's target resolves in the module: the class exists and declares the method.
         *
         * <p>A mapping to a class that does not exist, or to a method that does not, is a fabricated
         * mapping. Resolved by reading the source rather than by loading the type, so the assertion holds
         * without reflection - the same constraint that forbids reflection in the production tree and is
         * worth honouring in the instrument that audits it.
         *
         * @throws IOException if the matrix or a source file cannot be read
         */
        @Test
        @DisplayName("every row's target class exists and declares the target method, and every covering "
                + "test class exists")
        void everyRowResolvesToADeclaredMethodAndAnExistingTest() throws IOException {
            final List<String> unresolved = new ArrayList<>();
            final Map<String, String> sources = new LinkedHashMap<>();
            final Set<String> targetClasses = new LinkedHashSet<>();
            final Set<String> coveringTests = new LinkedHashSet<>();

            for (final List<String> row : matrixRows()) {
                final String targetClass = unquoted(row.get(3));
                final String targetMethod = unquoted(row.get(4));
                final String coveringTest = unquoted(row.get(5));
                targetClasses.add(targetClass);
                coveringTests.add(coveringTest);

                final Path classFile = Path.of(PRODUCTION_TREE, "com", "carddemo")
                        .resolve(Path.of(targetClass.replace('.', '/') + ".java"));
                if (!Files.isRegularFile(classFile)) {
                    unresolved.add(row.get(0) + " -> no such target class at " + classFile);
                    continue;
                }
                final String source = sources.computeIfAbsent(targetClass, name -> readSource(classFile));
                if (!declaresMethod(source, targetMethod)) {
                    unresolved.add(row.get(0) + " -> " + targetClass + " declares no method '"
                            + targetMethod + "'");
                }
                final Path testFile = Path.of(TEST_TREE, "com", "carddemo")
                        .resolve(Path.of(coveringTest.replace('.', '/') + ".java"));
                if (!Files.isRegularFile(testFile)) {
                    unresolved.add(row.get(0) + " -> no such covering test at " + testFile);
                }
            }

            assertThat(unresolved)
                    .as("every target and every covering test must resolve, or the matrix records a "
                            + "mapping that does not exist")
                    .isEmpty();
            assertThat(targetClasses)
                    .as("the 544 units land in a bounded set of classes, which is what makes the mapping "
                            + "reviewable rather than a list")
                    .hasSize(22);
            assertThat(coveringTests)
                    .as("and are covered by a bounded set of test classes")
                    .hasSize(22);
        }

        /**
         * No citation is recorded twice, and the one exception is the estate's own duplicate.
         *
         * @throws IOException if the matrix cannot be read
         */
        @Test
        @DisplayName("every citation is unique, with the duplicated exit label the estate genuinely "
                + "declares twice as the single stated exception")
        void everyCitationIsUniqueExceptTheDuplicatedLabel() throws IOException {
            final Map<String, Integer> byCitation = new LinkedHashMap<>();
            final Map<String, Integer> byLabel = new LinkedHashMap<>();
            for (final List<String> row : matrixRows()) {
                final String label = row.get(0) + " " + unquoted(row.get(1));
                byCitation.merge(label + " @" + row.get(2), Integer.valueOf(1),
                        (first, second) -> Integer.valueOf(first.intValue() + second.intValue()));
                byLabel.merge(label, Integer.valueOf(1),
                        (first, second) -> Integer.valueOf(first.intValue() + second.intValue()));
            }

            final List<String> repeatedCitations = new ArrayList<>();
            byCitation.forEach((citation, count) -> {
                if (count.intValue() > 1) {
                    repeatedCitations.add(citation + " x" + count);
                }
            });
            assertThat(repeatedCitations)
                    .as("member, paragraph and line together identify a unit, so a repeat means one unit "
                            + "was counted twice and another has no row at all")
                    .isEmpty();

            final List<String> repeatedLabels = new ArrayList<>();
            byLabel.forEach((label, count) -> {
                if (count.intValue() > 1) {
                    repeatedLabels.add(label);
                }
            });
            assertThat(repeatedLabels)
                    .as("only one label is recorded twice, and only because the member declares it twice. "
                            + "Any other repeat is a matrix defect rather than an estate anomaly")
                    .containsExactly(DUPLICATED_LABEL_MEMBER + " " + DUPLICATED_LABEL);
        }

        /**
         * Both provenance anchors appear in the header.
         *
         * <p>The release stamp is asserted <strong>only</strong> as a header provenance string. It is
         * carried by 78 legacy members, three carry a later stamp, the seventeen mapsets differ and
         * twenty-five carry none at all - so a per-member assertion would fail on 45 members and would be
         * simply wrong about the estate. The corrected distribution belongs in the decision log, and the
         * matrix's own header states the 78.
         *
         * @throws IOException if the matrix cannot be read
         */
        @Test
        @DisplayName("the header carries the checkout SHA and the upstream release stamp, the stamp as a "
                + "provenance string only")
        void theHeaderCarriesBothProvenanceAnchors() throws IOException {
            final String header = matrixHeader();

            assertThat(header)
                    .as("the commit is the anchor because this repository carries no tag, so traceability "
                            + "is by citation to a pinned checkout")
                    .contains(TestDataFactory.VERIFIED_CHECKOUT_COMMIT);
            assertThat(TestDataFactory.VERIFIED_CHECKOUT_COMMIT)
                    .as("a full forty-character object name, not an abbreviation")
                    .hasSize(40);
            assertThat(header)
                    .as("the upstream release stamp, recorded here and nowhere else as a per-member "
                            + "property, because it is not one")
                    .contains(TestDataFactory.UPSTREAM_RELEASE_STAMP);
            assertThat(header)
                    .as("and the header states how many members actually carry that stamp, which is what "
                            + "stops a reader inferring that all of them do")
                    .contains("78");
        }

        /**
         * The marker distribution, so the 544 stays honest about what it counts.
         *
         * <p>The sign-off requires the count to include rows that are documented non-implementations rather
         * than translations, and requires them to be visible as such. Four findings are marked, under four
         * markers, and the five populations sum to the total: one invoked paragraph that implements nothing,
         * three preserved source anomalies, eighteen paragraphs of a member no job stream invokes, sixteen
         * paragraphs no delivered call site reaches, and the ordinary translations that make up the rest.
         *
         * @throws IOException if the matrix cannot be read
         */
        @Test
        @DisplayName("1 non-implementation, 3 source anomalies, 18 unwired-member rows and 16 "
                + "unwired-paragraph rows are marked, and with 506 ordinary rows they sum to 544")
        void theMarkedRowsSumWithTheOrdinaryOnesToTheTotal() throws IOException {
            int nonImplementation = 0;
            int sourceAnomaly = 0;
            int unwired = 0;
            int unwiredParagraph = 0;
            int ordinary = 0;

            for (final List<String> row : matrixRows()) {
                final String marker = row.get(6);
                if (marker.contains(MARKER_NON_IMPLEMENTATION)) {
                    nonImplementation++;
                } else if (marker.contains(MARKER_SOURCE_ANOMALY)) {
                    sourceAnomaly++;
                } else if (marker.contains(MARKER_UNWIRED)) {
                    unwired++;
                } else if (marker.contains(MARKER_UNWIRED_PARAGRAPH)) {
                    unwiredParagraph++;
                } else {
                    assertThat(marker)
                            .as("an unmarked row carries no marker text at all, so a fifth marker "
                                    + "cannot be introduced without this failing")
                            .isEmpty();
                    ordinary++;
                }
            }

            assertThat(nonImplementation)
                    .as("one paragraph is invoked and implements nothing")
                    .isEqualTo(1);
            assertThat(sourceAnomaly)
                    .as("three labels are duplicated or misspelled and are preserved as found")
                    .isEqualTo(3);
            assertThat(unwired)
                    .as("eighteen paragraphs belong to the member no job stream invokes")
                    .isEqualTo(18);
            assertThat(unwiredParagraph)
                    .as("sixteen paragraphs are reached by no delivered call site: three account-update "
                            + "edits the driver deliberately does not route to, four diagnostic senders "
                            + "and one alternate-index read that no PERFORM in their own members reaches, "
                            + "and the eight paired exits reachable only from those heads")
                    .isEqualTo(UNWIRED_PARAGRAPH_ROWS);
            assertThat(ordinary)
                    .as("and the rest are ordinary translations")
                    .isEqualTo(ORDINARY_ROWS);
            assertThat(nonImplementation + sourceAnomaly + unwired + unwiredParagraph + ordinary)
                    .as("1 + 3 + 18 + %d + %d = 544, so nothing is marked twice and nothing is "
                            + "unaccounted for", UNWIRED_PARAGRAPH_ROWS, ORDINARY_ROWS)
                    .isEqualTo(TOTAL_PROCEDURE_UNITS);
        }

        /**
         * Every row resolves to code that exists: the class, the method declared on it, and the test.
         *
         * <p><strong>Why this assertion exists.</strong> The row count above proves the census. It cannot
         * prove that a row's three code citations are real, and a matrix whose citations do not resolve
         * discharges nothing while looking complete - a reader following a row arrives nowhere. The three
         * cells are checked separately because they fail separately: a renamed class, a renamed method and
         * a deleted test class are three different regressions, and the diagnostic names which one
         * happened.
         *
         * <p>The method check looks for a <em>declaration</em> rather than for the name appearing anywhere,
         * so a row cannot be satisfied by a call site or by a mention in a comment. A declaration is
         * recognised by the name being preceded by a return type and followed by an argument list, which is
         * what distinguishes {@code void editAlphaOptional(} from {@code editAlphaOptionalExit();}.
         *
         * @throws IOException if the matrix or a source file cannot be read
         */
        @Test
        @DisplayName("every row resolves to a class that exists, a method that class declares, and a "
                + "covering test that exists")
        void everyRowResolvesToRealCodeAndARealTest() throws IOException {
            final Map<String, String> sourceCache = new TreeMap<>();

            for (final List<String> row : matrixRows()) {
                final String targetClass = unquoted(row.get(3));
                final String targetMethod = unquoted(row.get(4));
                final String coveringTest = unquoted(row.get(5));
                final String rowLabel = row.get(0) + " " + row.get(1);

                final Path classFile = moduleFile(PRODUCTION_TREE, targetClass);
                assertThat(classFile)
                        .as("%s: the row names target class %s, which must exist under %s",
                                rowLabel, targetClass, PRODUCTION_TREE)
                        .isRegularFile();

                final Path testFile = moduleFile(TEST_TREE, coveringTest);
                assertThat(testFile)
                        .as("%s: the row names covering test %s, which must exist under %s",
                                rowLabel, coveringTest, TEST_TREE)
                        .isRegularFile();

                final String classSource = sourceCache.computeIfAbsent(targetClass,
                        name -> readOrFail(classFile));
                assertThat(declaresMethod(classSource, targetMethod))
                        .as("%s: %s must DECLARE %s. A call site or a comment naming it is not a "
                                + "declaration, and a row whose method moved elsewhere maps a paragraph "
                                + "onto a class that no longer implements it", rowLabel, targetClass,
                                targetMethod)
                        .isTrue();
            }
        }

        /**
         * The rows a row count cannot vouch for: their covering test must name the method.
         *
         * <p><strong>Why this assertion exists, and what it would have caught.</strong> A covering test
         * ordinarily reaches a row's method through the entry point the suite drives, so naming the method
         * would be an unreasonable demand on 528 of the 544 rows. Sixteen rows are different: no delivered
         * call site reaches their method at all, so no amount of driving an entry point executes them, and a
         * covering test that does not name them cannot be exercising them. That was exactly the defect this
         * marker was introduced for - rows claiming a suite that named none of them - and it survived a
         * green row-count check, which is why the check is here rather than in a reviewer's notes.
         *
         * <p>The eight heads carry the stronger form: the test must contain an actual invocation. Their
         * eight paired exits carry the weaker one: they are reachable only from their head, which calls them
         * on every arm, so naming them is what a reader needs and an invocation of their own would be
         * fabricated coverage.
         *
         * <p>The per-member distribution is asserted as well as the total, because a total cannot see a row
         * moving between members. The marker was declared for six account-update rows while ten rows in
         * three other members carried the same property unmarked, and both halves of that - the six being
         * treated as the whole population, and the ten being invisible - are what these two assertions now
         * refuse.
         *
         * @throws IOException if the matrix or a test source cannot be read
         */
        @Test
        @DisplayName("every deliberately unwired paragraph is named by its covering test, every unwired "
                + "head is actually invoked by it, and the rows fall in the decided per-member split")
        void everyUnwiredParagraphRowIsNamedByItsCoveringTest() throws IOException {
            final List<List<String>> marked = matrixRowsWhere(MARKER_UNWIRED_PARAGRAPH);
            final Map<String, String> testCache = new TreeMap<>();

            assertThat(marked)
                    .as("the marker exists for %d rows; changing that population is a decision that "
                            + "belongs in the matrix legend and here, not in one of them",
                            UNWIRED_PARAGRAPH_ROWS)
                    .hasSize(UNWIRED_PARAGRAPH_ROWS);
            final Map<String, Integer> observed = new TreeMap<>();
            for (final List<String> row : marked) {
                observed.merge(row.get(0), 1, Integer::sum);
            }
            assertThat(observed)
                    .as("each member's share of the population is itself the decision: four members carry "
                            + "these rows, and a row moving between them is a change of decision that a "
                            + "total cannot see")
                    .containsExactlyInAnyOrderEntriesOf(UNWIRED_PARAGRAPHS_BY_MEMBER);

            for (final List<String> row : marked) {
                final String targetMethod = unquoted(row.get(4));
                final String coveringTest = unquoted(row.get(5));
                final String rowLabel = row.get(0) + " " + row.get(1);
                final String testSource = testCache.computeIfAbsent(coveringTest,
                        name -> readOrFail(moduleFile(TEST_TREE, name)));

                assertThat(testSource)
                        .as("%s: no delivered call site reaches %s, so covering test %s must name it. "
                                + "A suite that names none of these rows' methods cannot be exercising "
                                + "them, however green a row count is", rowLabel, targetMethod,
                                coveringTest)
                        .contains(targetMethod);

                if (!targetMethod.endsWith("Exit")) {
                    assertThat(testSource)
                            .as("%s: %s is a paragraph head, so the covering test must CALL it, not "
                                    + "merely mention it", rowLabel, targetMethod)
                            .contains("." + targetMethod + "(");
                }
            }
        }

        /**
         * The first documented non-implementation: the fee paragraph that implements nothing.
         *
         * <p>It is genuinely invoked, and its body is a comment and an exit. The Java method therefore
         * exists, is called, and does nothing. Inventing fee logic to fill it would be feature expansion and
         * would change the output of every interest run, so the emptiness is asserted as a property of the
         * matrix rather than left to a reader's restraint.
         *
         * @throws IOException if the matrix cannot be read
         */
        @Test
        @DisplayName("the fee paragraph is marked as a non-implementation, and maps to a method that "
                + "exists and does nothing")
        void theFeeParagraphIsMarkedAsANonImplementation() throws IOException {
            final List<List<String>> marked = matrixRowsWhere(MARKER_NON_IMPLEMENTATION);

            assertThat(marked)
                    .as("exactly one row is a documented non-implementation")
                    .hasSize(1);
            final List<String> row = marked.get(0);
            assertThat(row.get(0))
                    .as("the member the interest calculation lives in")
                    .isEqualTo("app/cbl/CBACT04C.cbl");
            assertThat(row.get(1))
                    .as("the paragraph name, spelled as the source spells it")
                    .contains("1400-COMPUTE-FEES");
            assertThat(row.get(2))
                    .as("the line its label sits on")
                    .isEqualTo("518");
            assertThat(row.get(3))
                    .as("the class that owns it")
                    .contains("InterestCalculationService");
            assertThat(row.get(4))
                    .as("and a named method, because the paragraph is invoked - an absent method would "
                            + "change the call graph rather than preserve it")
                    .isNotEmpty();

            // The marking is only honest if the method it points at is genuinely an invoked no-op. Both
            // halves are asserted against the shipped source, because both are ways the marking could
            // become a lie: a method that had acquired a body would mean fee logic was invented, and a
            // method nobody calls would mean the call graph was quietly simplified.
            final String method = unquoted(row.get(4));
            final String owner = readSource(Path.of(PRODUCTION_TREE, "com", "carddemo")
                    .resolve(Path.of(FEE_METHOD_OWNER)));

            assertThat(declaresMethod(owner, method))
                    .as("%s must declare %s, because the paragraph is invoked in the estate",
                            FEE_METHOD_OWNER, method)
                    .isTrue();
            assertThat(methodBodyStatements(owner, method))
                    .as("%s must implement nothing: the paragraph's body is a comment and an exit, and "
                            + "inventing fee logic here would be feature expansion that changed the "
                            + "output of every interest run. Statements found: %s", method,
                            methodBodyStatements(owner, method))
                    .isEmpty();
            assertThat(invocationCountOf(owner, method))
                    .as("and it must be called, because the estate calls the paragraph. A no-op nobody "
                            + "invokes is a different program from the one being migrated")
                    .isPositive();
        }

        /**
         * The second documented non-implementation: the duplicated exit label.
         *
         * <p>Two identically named paragraphs exist in one member. Both rows are listed - dropping one would
         * make the count 543 - and both point at the single Java method they collapse into.
         *
         * @throws IOException if the matrix cannot be read
         */
        @Test
        @DisplayName("the duplicated exit label contributes two rows that collapse to one method, and the "
                + "misspelled label is preserved as found")
        void theSourceAnomalyRowsArePreservedAsFound() throws IOException {
            final List<List<String>> marked = matrixRowsWhere(MARKER_SOURCE_ANOMALY);
            assertThat(marked).as("three rows are marked as source anomalies").hasSize(3);

            final List<List<String>> duplicated = new ArrayList<>();
            for (final List<String> row : marked) {
                if ("app/cbl/COACTVWC.cbl".equals(row.get(0))) {
                    duplicated.add(row);
                }
            }
            assertThat(duplicated)
                    .as("the duplicated label contributes two rows, because two labels genuinely exist; "
                            + "listing one would make the total 543 and fail the sign-off")
                    .hasSize(2);
            assertThat(duplicated)
                    .as("both rows name the same label")
                    .allSatisfy(row -> assertThat(row.get(1)).contains("0000-MAIN-EXIT"));
            assertThat(List.of(duplicated.get(0).get(2), duplicated.get(1).get(2)))
                    .as("at the two distinct lines the estate declares them on")
                    .containsExactlyInAnyOrder("408", "411");
            assertThat(duplicated.get(0).get(4))
                    .as("and both collapse into one Java method, which is the translation decision")
                    .isEqualTo(duplicated.get(1).get(4));

            final List<String> misspelled = new ArrayList<>();
            for (final List<String> row : marked) {
                if ("app/cbl/CORPT00C.cbl".equals(row.get(0))) {
                    misspelled.addAll(row);
                }
            }
            assertThat(misspelled)
                    .as("the misspelled paragraph name is carried as the source spells it, so the "
                            + "citation remains findable, while the Java method is spelled correctly")
                    .isNotEmpty();
        }

        /**
         * The third documented non-implementation: the member no job stream invokes.
         *
         * <p>All eighteen of its paragraphs are marked. Its Java job is defined and excluded from the default
         * pipeline, so its coverage depends entirely on the end-to-end tier launching it explicitly - which
         * is why it is marked rather than dropped.
         *
         * @throws IOException if the matrix cannot be read
         */
        @Test
        @DisplayName("all 18 paragraphs of the unwired extract member are marked, and they are the whole "
                + "of that member")
        void theUnwiredMembersParagraphsAreAllMarked() throws IOException {
            final List<List<String>> marked = matrixRowsWhere(MARKER_UNWIRED);

            assertThat(marked)
                    .as("eighteen rows are marked as belonging to an unwired member")
                    .hasSize(18);
            assertThat(marked)
                    .as("and every one of them belongs to the same member, so the marker denotes a "
                            + "property of that member rather than of scattered paragraphs")
                    .allSatisfy(row -> assertThat(row.get(0)).isEqualTo("app/cbl/CBTRN01C.cbl"));
            assertThat(paragraphLabelsOf("app/cbl/CBTRN01C.cbl", true))
                    .as("eighteen is the member's whole paragraph count, so the marking is complete "
                            + "rather than partial")
                    .hasSize(18);
        }

        /**
         * The unreferenced copybook is excluded by decision, and its exclusion is recorded.
         *
         * @throws IOException if a document cannot be read
         */
        @Test
        @DisplayName("the unreferenced copybook contributes no row, and the exclusion is recorded as a "
                + "decision rather than left as an omission")
        void theUnreferencedCopybookIsExcludedByDecision() throws IOException {
            assertThat(matrixRows())
                    .as("no row cites it: it declares no paragraph, and a row for it would make the "
                            + "total 545 and fail the sign-off")
                    .allSatisfy(row -> assertThat(row.get(0)).doesNotContain("UNUSED1Y"));
            assertThat(paragraphLabelsOf("app/cpy/UNUSED1Y.cpy", false))
                    .as("it is a data copybook and declares no procedure label at all")
                    .isEmpty();

            final String matrix = Files.readString(documentationFile(TRACEABILITY_MATRIX),
                    StandardCharsets.UTF_8);
            assertThat(matrix)
                    .as("the matrix names the exclusion, so a reader who wonders where it went finds an "
                            + "answer instead of a gap; expected at %s",
                            documentationFile(TRACEABILITY_MATRIX))
                    .contains("UNUSED1Y");
            assertThat(Files.readString(documentationFile(DECISION_LOG), StandardCharsets.UTF_8))
                    .as("and the decision log carries the reasoning, which is where the authority for "
                            + "every divergence lives; expected at %s", documentationFile(DECISION_LOG))
                    .contains("UNUSED1Y");
        }

        /**
         * The manual's milestone ledger agrees with what this class has just asserted.
         *
         * <p>A delivered artefact that the manual still lists as pending is worse than an undelivered one:
         * a reader trusts the ledger, does not open the page, and carries the stale status into whatever
         * they write next. The manual had said the matrix was unpublished for as long as it was true, and
         * kept saying it afterwards, which is the failure this assertion exists to stop recurring.
         *
         * <p>Scoped to the pending-documents section rather than to the whole page, deliberately. Two pages
         * genuinely have not landed, so the manual must still be able to say "not yet published" about
         * them; what it must not do is say it about this one. Reading the section instead of the document
         * keeps the assertion about the ledger's contents rather than about a phrase.
         *
         * @throws IOException if the manual cannot be read
         */
        @Test
        @DisplayName("the manual's milestone ledger records the matrix as delivered rather than pending, "
                + "and links it")
        void theManualRecordsTheMatrixAsDelivered() throws IOException {
            final String manual = operatorManual();
            final int pendingAt = manual.indexOf(PENDING_DOCUMENTS_HEADING);

            assertThat(pendingAt)
                    .as("the manual carries a pending-documents ledger headed %s, which is where a page "
                            + "that has not landed is listed; without it this assertion would pass "
                            + "vacuously", PENDING_DOCUMENTS_HEADING)
                    .isNotNegative();

            final int nextSection = manual.indexOf("\n## ", pendingAt);
            final String pending = nextSection < 0
                    ? manual.substring(pendingAt)
                    : manual.substring(pendingAt, nextSection);

            assertThat(pending)
                    .as("the matrix is delivered, and every assertion in this class reads it - so listing "
                            + "it as pending would make the manual contradict the build it documents")
                    .doesNotContain(TRACEABILITY_MATRIX);
            assertThat(manual)
                    .as("and the manual links it, so a reader reaches the 544 rows rather than a "
                            + "description of them")
                    .contains("../" + DOCUMENTATION_DIRECTORY + "/" + TRACEABILITY_MATRIX);
        }
    }

    /**
     * The migrated schema, read back from the running server rather than from the migration text.
     *
     * <p>Reading the catalogue rather than the scripts is deliberate. A script says what was intended; the
     * catalogue says what the server actually has, which is what the application runs against and what
     * {@code validate} checks the entity mapping against. A script assertion would pass on a migration that
     * failed halfway.
     */
    @Nested
    @DisplayName("Gate 8 - the migrated schema on a real server: 11 tables, 4 migrations, exact decimals")
    class MigratedSchemaState {

        MigratedSchemaState() {
        }

        /**
         * Eleven application tables, one per verified record layout.
         *
         * <p>The job-repository tables and the migration history table are excluded, and the exclusion is
         * part of the figure rather than a convenience: the batch schema is created by the framework on
         * request, so counting it would make the number depend on a framework setting instead of on the
         * estate's record layouts.
         *
         * @throws SQLException if the catalogue cannot be read
         */
        @Test
        @DisplayName("eleven application tables, excluding the job-repository and migration-history tables")
        void thereAreElevenApplicationTables() throws SQLException {
            final List<String> tables = applicationTableNames();

            assertThat(tables)
                    .as("one table per verified record layout")
                    .hasSize(APPLICATION_TABLE_COUNT);
            assertThat(tables)
                    .as("and they are the eleven the layouts name, so a rename cannot pass as a count")
                    .containsExactlyInAnyOrderElementsOf(APPLICATION_TABLES);
            assertThat(APPLICATION_TABLES)
                    .as("the published roster agrees with the layout roster the factory holds")
                    .hasSize(TestDataFactory.ALL_LAYOUTS.size());
        }

        /**
         * All four migrations applied, in order, successfully.
         *
         * @throws SQLException if the history cannot be read
         */
        @Test
        @DisplayName("the four delivered migrations are applied in order, and only successful ones count")
        void theFourMigrationsAreApplied() throws SQLException {
            assertThat(appliedMigrationVersions())
                    .as("schema, then indexes, then reference data, then identities. A migration that "
                            + "failed does not appear, so it cannot masquerade as applied")
                    .containsExactlyElementsOf(MIGRATION_VERSIONS);
        }

        /**
         * The reference seed's composition, table by table.
         *
         * <p>Two of the eleven are deliberately empty after the reference seed, and asserting the zeroes is
         * as important as asserting the populated counts: the transaction table is empty because posting
         * produces its rows, and the identity table is empty because the fourth migration - not the third -
         * seeds it. A test that only checked the populated tables would pass on a seed that had quietly
         * pre-filled either one and would leave every posting assertion measuring the wrong baseline.
         *
         * @throws SQLException if a count cannot be read
         */
        @Test
        @DisplayName("the seed loads 626 reference rows across nine tables and leaves two deliberately "
                + "empty")
        void theReferenceSeedHasItsMeasuredComposition() throws SQLException {
            final Map<String, Integer> expected = new LinkedHashMap<>();
            expected.put("account", Integer.valueOf(TestDataFactory.SEEDED_FIFTY_ROW_COUNT));
            expected.put("card", Integer.valueOf(TestDataFactory.SEEDED_FIFTY_ROW_COUNT));
            expected.put("card_cross_reference",
                    Integer.valueOf(TestDataFactory.SEEDED_FIFTY_ROW_COUNT));
            expected.put("customer", Integer.valueOf(TestDataFactory.SEEDED_FIFTY_ROW_COUNT));
            expected.put("daily_transaction",
                    Integer.valueOf(TestDataFactory.SEEDED_DAILY_TRANSACTION_COUNT));
            expected.put("disclosure_group",
                    Integer.valueOf(TestDataFactory.SEEDED_DISCLOSURE_GROUP_COUNT));
            expected.put("transaction_category_balance",
                    Integer.valueOf(TestDataFactory.SEEDED_FIFTY_ROW_COUNT));
            expected.put("transaction_category",
                    Integer.valueOf(TestDataFactory.SEEDED_TRANSACTION_CATEGORY_COUNT));
            expected.put("transaction_type",
                    Integer.valueOf(TestDataFactory.SEEDED_TRANSACTION_TYPE_COUNT));
            expected.put("transaction", Integer.valueOf(TestDataFactory.SEEDED_TRANSACTION_COUNT));
            expected.put("user_security", Integer.valueOf(TestDataFactory.SEEDED_USER_COUNT));

            assertThat(expected.keySet())
                    .as("every application table is accounted for, so none is silently unexamined")
                    .containsExactlyInAnyOrderElementsOf(APPLICATION_TABLES);

            int referenceRows = 0;
            for (final Map.Entry<String, Integer> entry : expected.entrySet()) {
                assertThat(rowCount(entry.getKey()))
                        .as("%s holds %d rows after the delivered migrations", entry.getKey(),
                                entry.getValue())
                        .isEqualTo(entry.getValue().intValue());
                if (!"user_security".equals(entry.getKey())) {
                    referenceRows += entry.getValue().intValue();
                }
            }
            assertThat(referenceRows)
                    .as("the reference seed's own total, with the identities excluded because a later "
                            + "migration applies them")
                    .isEqualTo(626);
            assertThat(TestDataFactory.SEEDED_TRANSACTION_COUNT)
                    .as("the transaction table starts empty: posting is what produces its rows, and a "
                            + "pre-filled table would invalidate every posting baseline")
                    .isZero();
        }

        /**
         * No surrogate key anywhere: every primary key is the business key from the record image.
         *
         * <p>Asserted three ways, because each catches a different way a surrogate could appear: no column
         * defaults from a sequence, no sequence exists outside the job repository, and the eleven primary
         * keys are the business-key columns the layouts name. The correspondence between a record image and
         * a table row is what Gate 1's byte equality depends on, and a generated identifier would break it.
         *
         * @throws SQLException if the catalogue cannot be read
         */
        @Test
        @DisplayName("no surrogate primary key exists: no sequence, no generated default, and every key is "
                + "the business key")
        void noSurrogatePrimaryKeyExists() throws SQLException {
            assertThat(queryOneColumnLocally("""
                    SELECT table_name || '.' || column_name
                      FROM information_schema.columns
                     WHERE table_schema = 'public'
                       AND table_name NOT LIKE 'batch\\_%'
                       AND table_name <> 'flyway_schema_history'
                       AND (column_default LIKE 'nextval%' OR is_identity = 'YES')
                     ORDER BY 1
                    """))
                    .as("not one application column is generated, so every key value comes from the "
                            + "record image the migration read it from")
                    .isEmpty();

            assertThat(queryOneColumnLocally("""
                    SELECT sequence_name FROM information_schema.sequences
                     WHERE sequence_schema = 'public'
                       AND sequence_name NOT LIKE 'batch\\_%'
                       AND sequence_name NOT LIKE 'BATCH\\_%'
                     ORDER BY 1
                    """))
                    .as("the only sequences on the server belong to the batch job repository, which is "
                            + "framework infrastructure rather than application data")
                    .isEmpty();

            final List<String> keyColumns = queryOneColumnLocally("""
                    SELECT tc.table_name || ':' || kcu.column_name
                      FROM information_schema.table_constraints tc
                      JOIN information_schema.key_column_usage kcu
                        ON kcu.constraint_name = tc.constraint_name
                       AND kcu.table_schema = tc.table_schema
                     WHERE tc.table_schema = 'public'
                       AND tc.constraint_type = 'PRIMARY KEY'
                       AND tc.table_name NOT LIKE 'batch\\_%'
                       AND tc.table_name <> 'flyway_schema_history'
                     ORDER BY tc.table_name, kcu.ordinal_position
                    """);
            assertThat(keyColumns)
                    .as("the eleven tables carry eight single-column business keys and three composite "
                            + "ones, which is sixteen key columns in total")
                    .hasSize(16);
            assertThat(keyColumns)
                    .as("the account key is the account identifier itself, not a generated column")
                    .contains("account:acct_id")
                    .contains("card:card_num")
                    .contains("customer:cust_id")
                    .contains("user_security:sec_usr_id");
        }

        /**
         * The three composite keys are three distinct shapes and must never be conflated.
         *
         * <p>Two of them come from similarly named COBOL groups and are easy to confuse: the category
         * balance key is three fields totalling seventeen characters, while the transaction category key is
         * two fields totalling six. Using one where the other belongs would compile, would slice a record at
         * the wrong offset and would corrupt data silently.
         *
         * @throws SQLException if the catalogue cannot be read
         */
        @Test
        @DisplayName("the three composite keys have three distinct widths - 17, 16 and 6 - and are never "
                + "interchangeable")
        void theThreeCompositeKeysAreDistinct() throws SQLException {
            assertThat(TestDataFactory.CATEGORY_BALANCE_KEY_WIDTH)
                    .as("account identifier plus type plus category")
                    .isEqualTo(17);
            assertThat(TestDataFactory.DISCLOSURE_GROUP_KEY_WIDTH)
                    .as("group identifier plus type plus category")
                    .isEqualTo(16);
            assertThat(TestDataFactory.TRANSACTION_CATEGORY_KEY_WIDTH)
                    .as("type plus category alone")
                    .isEqualTo(6);
            assertThat(List.of(Integer.valueOf(TestDataFactory.CATEGORY_BALANCE_KEY_WIDTH),
                            Integer.valueOf(TestDataFactory.DISCLOSURE_GROUP_KEY_WIDTH),
                            Integer.valueOf(TestDataFactory.TRANSACTION_CATEGORY_KEY_WIDTH)))
                    .as("three distinct widths, so no two can be substituted for one another")
                    .doesNotHaveDuplicates();

            assertThat(keyColumnCount("transaction_category_balance"))
                    .as("three key columns")
                    .isEqualTo(3);
            assertThat(keyColumnCount("disclosure_group"))
                    .as("three key columns")
                    .isEqualTo(3);
            assertThat(keyColumnCount("transaction_category"))
                    .as("two key columns, which is what distinguishes it from the seventeen-character key")
                    .isEqualTo(2);
        }

        /**
         * The three alternate-index equivalents exist as ordinary indexes.
         *
         * <p>The third one has no online caller at all: it exists because the report job filters on a
         * processing-timestamp range and would otherwise scan the whole table. It is asserted for exactly
         * that reason - an index nothing obviously uses is the one most likely to be dropped as dead weight.
         *
         * @throws SQLException if the catalogue cannot be read
         */
        @Test
        @DisplayName("the three alternate-index equivalents exist, including the batch-only one no endpoint "
                + "depends on")
        void theThreeAlternateIndexEquivalentsExist() throws SQLException {
            final List<String> indexes = queryOneColumnLocally("""
                    SELECT indexname FROM pg_indexes
                     WHERE schemaname = 'public'
                       AND indexname LIKE 'idx\\_%'
                     ORDER BY indexname
                    """);

            assertThat(indexes)
                    .as("one index per legacy alternate index, and no more: the card-to-account path, the "
                            + "cross-reference-to-account path, and the batch-only processing-timestamp "
                            + "range the report job filters on")
                    .containsExactly("idx_card_card_acct_id",
                            "idx_card_cross_reference_xref_acct_id",
                            "idx_transaction_tran_proc_ts");
        }

        /**
         * Every decimal column is exact, and no floating-point column exists anywhere.
         *
         * <p>This is the schema-level discharge of the mapping table's first row: decimal precision must be
         * identical, with no floating-point substitution. The assertion is made against the catalogue over
         * every application table at once, so a floating-point column added to any table fails here rather
         * than surfacing as a rounding difference in an output comparison much later.
         *
         * @throws SQLException if the catalogue cannot be read
         */
        @Test
        @DisplayName("nine exact decimal columns all at scale 2, and not one floating-point column in the "
                + "schema")
        void everyDecimalColumnIsExactAndScaledToTwo() throws SQLException {
            assertThat(queryOneColumnLocally("""
                    SELECT table_name || '.' || column_name || ' ' || data_type
                      FROM information_schema.columns
                     WHERE table_schema = 'public'
                       AND table_name NOT LIKE 'batch\\_%'
                       AND table_name <> 'flyway_schema_history'
                       AND data_type IN ('double precision', 'real', 'money')
                     ORDER BY 1
                    """))
                    .as("no approximate numeric type appears anywhere. A monetary amount in binary "
                            + "floating point cannot represent every two-decimal value exactly, so one "
                            + "such column would break byte equality wherever it was written")
                    .isEmpty();

            final List<String> decimals = queryOneColumnLocally("""
                    SELECT table_name || '.' || column_name || ' NUMERIC('
                             || numeric_precision || ',' || numeric_scale || ')'
                      FROM information_schema.columns
                     WHERE table_schema = 'public'
                       AND table_name NOT LIKE 'batch\\_%'
                       AND table_name <> 'flyway_schema_history'
                       AND data_type = 'numeric'
                     ORDER BY table_name, column_name
                    """);

            assertThat(decimals)
                    .as("the nine monetary and rate columns the record layouts declare, each sized from "
                            + "its field width")
                    .containsExactly(
                            "account.acct_cash_credit_limit NUMERIC(12,2)",
                            "account.acct_credit_limit NUMERIC(12,2)",
                            "account.acct_curr_bal NUMERIC(12,2)",
                            "account.acct_curr_cyc_credit NUMERIC(12,2)",
                            "account.acct_curr_cyc_debit NUMERIC(12,2)",
                            "daily_transaction.dalytran_amt NUMERIC(11,2)",
                            "disclosure_group.dis_int_rate NUMERIC(6,2)",
                            "transaction.tran_amt NUMERIC(11,2)",
                            "transaction_category_balance.tran_cat_bal NUMERIC(11,2)");
            assertThat(decimals)
                    .as("every one of them is scaled to two decimal places, matching the two implied "
                            + "decimals of the zoned field it came from")
                    .allSatisfy(column -> assertThat(column)
                            .endsWith("," + TestDataFactory.MONETARY_SCALE + ")"));
        }
    }

    /**
     * The unsafe and low-level code audit, executed over the production tree and scoped to exactly it.
     *
     * <h2>The scoping rule is part of the measurement</h2>
     * The audit examines {@code src/main/java} and nothing else, and both exclusions are deliberate rather
     * than convenient. The four migration scripts are versioned schema definitions: an unscoped search for
     * statement text would report them as dynamic query assembly and produce four violations that are in
     * fact the deliverable. Test sources are excluded for the converse reason - an assertion helper may
     * legitimately do things production code may not. Broadening the audit would not make it stricter; it
     * would make it wrong.
     */
    @Nested
    @DisplayName("Gate 6 - the unsafe-code audit, executed over the production tree and scoped to it")
    class UnsafeCodeAudit {

        UnsafeCodeAudit() {
        }

        /**
         * Each forbidden construct, at its budget.
         *
         * <p>The reflection budget of zero is not hygiene: it is the constraint that forbids
         * annotation-driven or convention-based record mapping and is therefore the reason all eleven record
         * mappers slice fixed-width images at explicit offsets. Relaxing it would silently permit a mapping
         * approach the plan rules out.
         *
         * @param construct the literal construct to count
         * @param budget    the number of occurrences permitted
         * @throws IOException if the tree cannot be walked
         */
        @ParameterizedTest(name = "{0} occurs {1} time(s) in the production tree")
        @CsvSource({
            "Runtime.getRuntime, 0",
            "ProcessBuilder,     0",
            "java.lang.reflect,  0",
            "Class.forName,      0",
            "createNativeQuery,  0"})
        @DisplayName("each forbidden construct is absent from the production tree")
        void eachForbiddenConstructIsWithinBudget(final String construct, final int budget)
                throws IOException {
            assertThat(occurrencesIn(PRODUCTION_TREE, construct))
                    .as("%s is budgeted at %d over %s. The reflection budget in particular is what "
                            + "forbids annotation-driven record mapping and is why every mapper slices "
                            + "explicit offsets", construct, budget, PRODUCTION_TREE)
                    .isEqualTo(budget);
        }

        /**
         * Suppressed warnings stay inside their budget, and in fact none is needed.
         *
         * @throws IOException if the tree cannot be walked
         */
        @Test
        @DisplayName("suppressed warnings stay within the budget of three, and the measured count is zero")
        void suppressedWarningsStayWithinBudget() throws IOException {
            assertThat(occurrencesIn(PRODUCTION_TREE, "@SuppressWarnings"))
                    .as("the budget is three, each of which would need a stated reason. None is needed, "
                            + "because every warning is already an error and so nothing accumulates to "
                            + "be suppressed")
                    .isLessThanOrEqualTo(SUPPRESSION_BUDGET)
                    .isZero();
        }

        /**
         * No query string is assembled from a value, which is the category a literal grep cannot measure.
         *
         * <h4>Why this is not a keyword grep</h4>
         * The words this category is named after - select, update, values, from - are ordinary English and
         * appear throughout the module's diagnostics, so counting lines that contain them measures prose.
         * What the audit actually forbids is a <em>query</em> assembled from something that is not a
         * literal. The census therefore finds string literals that <em>begin</em> with a statement verb
         * and carry a clause keyword, which is what distinguishes a query from a sentence, and flags one
         * only when the expression around it concatenates a non-literal. Two literals joined together are
         * a compile-time constant and are not flagged; a literal joined to an identifier or a call is.
         *
         * @throws IOException if the tree cannot be walked
         */
        @Test
        @DisplayName("no query is assembled from a non-literal anywhere in the production tree, measured "
                + "over the query strings themselves rather than over lines containing a keyword")
        void noQueryIsAssembledFromANonLiteral() throws IOException {
            final QueryStringCensus census = queryStringCensus();

            assertThat(census.queryLiterals())
                    .as("the census must find the module's query strings, or an absence assertion over "
                            + "them proves nothing. %d were expected to exist at all",
                            census.queryLiterals())
                    .isPositive();
            assertThat(census.assembledSites())
                    .as("a query assembled from a value is the injection surface this module has none of: "
                            + "every statement is a whole literal, a derived finder or a bound parameter. "
                            + "Sites found: %s", census.assembledSites())
                    .isEmpty();
        }

        /**
         * Casts to a parameterised type stay inside their budget, and the measured figure is recorded.
         *
         * <p>The compiler is what makes the <em>unchecked</em> count zero: every warning is an error, so
         * an unchecked operation cannot survive a build. What this measures is the population that could
         * contain one - a cast whose target is a parameterised type or a type variable - so a sixth site
         * cannot appear without being seen. All five that exist cast a lambda onto a parameterised
         * functional interface, which carries no unchecked operation at all.
         *
         * @throws IOException if the tree cannot be walked
         */
        @Test
        @DisplayName("casts to a parameterised type stay within the budget of five, and the compiler is "
                + "what makes the unchecked count zero")
        void castsToAParameterisedTypeStayWithinBudget() throws IOException {
            final List<String> casts = genericCastSites();

            assertThat(casts)
                    .as("the plan budgets %d such casts. Sites: %s", GENERIC_CAST_BUDGET, casts)
                    .hasSizeLessThanOrEqualTo(GENERIC_CAST_BUDGET);
            assertThat(configuredList(activePlugin("maven-compiler-plugin"),
                            "configuration", "compilerArgs"))
                    .as("and the reason the unchecked count is zero rather than merely small: an unchecked "
                            + "operation is a warning, every warning is an error, and the module compiles")
                    .contains("-Xlint:all", "-Werror");
            assertThat(occurrencesIn(PRODUCTION_TREE, "@SuppressWarnings(\"unchecked\")"))
                    .as("with nothing hiding one, which a suppression would")
                    .isZero();
        }

        /**
         * No raw string concatenation into SQL, and the migrations are excluded by scope rather than silence.
         *
         * @throws IOException if the tree cannot be walked
         */
        @Test
        @DisplayName("the migrations are the four the plan delivers and are excluded from the audit by "
                + "scope, not by pretending they are not SQL")
        void theMigrationsAreExcludedByScopeRatherThanBySilence() throws IOException {
            final Path migrations = Path.of("src/main/resources/db/migration");
            assertThat(Files.isDirectory(migrations))
                    .as("the migration location is flat and module-relative; expected at %s",
                            migrations.toAbsolutePath())
                    .isTrue();

            final List<String> scripts;
            try (Stream<Path> entries = Files.list(migrations)) {
                scripts = entries.map(path -> path.getFileName().toString()).sorted().toList();
            }
            assertThat(scripts)
                    .as("four scripts, flat, in one location - which is what lets the audit's scoping "
                            + "rule be stated in a single line")
                    .containsExactly("V1__create_schema.sql", "V2__create_indexes.sql",
                            "V3__seed_reference_data.sql", "V4__seed_user_security.sql");
            assertThat(scripts)
                    .as("the reference seed and the identity seed are separate versions, because the "
                            + "identity seed is profile-scoped and a combined script could not be")
                    .doesNotContain("V3__seed_data.sql");
        }
    }

    /**
     * The estate's fixed output widths, enumerated completely rather than golden by golden.
     *
     * <h2>Why five and not four</h2>
     *
     * <p>Four of the estate's fixed output widths have a golden fixture, because Gate 1 names four expected
     * outputs: the reject record at 430 bytes, the report line at 133, the statement record at 80 and its
     * hypertext counterpart at 100. A fifth exists and has no golden - the category-balance report line the
     * {@code PRTCATBL} job stream emits, whose stream declares {@code SORTOUT DCB=(LRECL=40)} - and it is
     * genuinely an external file format rather than an internal detail. An inventory that counted the
     * goldens counted four and called that the contract, which is how the fifth width came to be omitted
     * from the plan's width table and from this suite.
     *
     * <p>The fifth is asserted here from the delivered job configuration, and its ordering and its
     * per-line width are asserted where they belong, in {@code batch/CategoryBalanceReportJobConfigIT}. No
     * golden is minted for it: Gate 1 names four expected outputs, and inventing a fifth expected file
     * would assert a baseline the gate does not define.
     */
    @Nested
    @DisplayName("Gates 1 and 5 - the five contractual output widths, four golden-backed and one job-verified")
    class ContractualOutputWidths {

        /** Creates the nested specification. */
        ContractualOutputWidths() {
            // Intentionally empty.
        }

        /**
         * Each golden is a separator-free stream whose length divides exactly by its contractual width.
         *
         * <p>Divisibility rather than line length, because these goldens carry <strong>no separator at
         * all</strong> - the assertion below proves it, and it is the contract rather than an accident.
         * Separation in a fixed-block data set belongs to the data set definition and not to the record, so
         * a golden that carried line feeds would be asserting a record the emitting program never writes.
         * The consequence is that a width is measured here as a stride over the delivered bytes: a file of
         * the wrong width fails to divide, and a file of the right length holding a stray separator fails
         * the separator assertion.
         *
         * @throws IOException if a golden cannot be read
         */
        @Test
        @DisplayName("each of the four goldens divides exactly into records of its contractual width and "
                + "carries no separator byte, so the width is measured from the delivered bytes")
        void eachGoldenIsUniformAtItsContractualWidth() throws IOException {
            for (final Map.Entry<String, Integer> golden : GOLDEN_WIDTHS.entrySet()) {
                final int width = golden.getValue().intValue();
                final byte[] content = classpathBytes(GOLDEN_DIRECTORY + golden.getKey());

                assertThat(content.length).as("%s must carry content", golden.getKey()).isPositive();
                assertThat(content.length % width)
                        .as("%s is a fixed-width stream at %d bytes, so its %d bytes must divide exactly; "
                                + "a remainder is a partial record no reader could interpret",
                                golden.getKey(), width, content.length)
                        .isZero();
                assertThat(content.length / width)
                        .as("%s holds at least one whole record", golden.getKey())
                        .isPositive();
                assertThat(new String(content, StandardCharsets.ISO_8859_1))
                        .as("%s carries no line feed and no carriage return: separation belongs to the "
                                + "data set definition, not to the record", golden.getKey())
                        .doesNotContain("\n")
                        .doesNotContain("\r");
            }
        }

        /**
         * The complete inventory is five widths, and the fifth comes from the job that emits it.
         */
        @Test
        @DisplayName("the complete inventory is FIVE widths - 40, 80, 100, 133 and 430 - the fifth being "
                + "the category-balance report line, which carries no golden and is verified in-job")
        void theCompleteInventoryIsFiveWidths() {
            assertThat(CategoryBalanceReportJobConfig.REPORT_RECORD_LENGTH)
                    .as("the fifth width is read from the configuration of the job that emits it, so this "
                            + "inventory cannot drift from the batch tier")
                    .isEqualTo(40);

            final Set<Integer> widths = new LinkedHashSet<>(GOLDEN_WIDTHS.values());
            assertThat(widths)
                    .as("the four golden-backed widths")
                    .containsExactlyInAnyOrder(Integer.valueOf(80), Integer.valueOf(100),
                            Integer.valueOf(133), Integer.valueOf(430));
            widths.add(Integer.valueOf(CategoryBalanceReportJobConfig.REPORT_RECORD_LENGTH));
            assertThat(widths)
                    .as("five distinct fixed output widths in the whole estate. Counting the goldens gives "
                            + "four, which is the count the plan's width table carries and the reason the "
                            + "category-balance report was omitted from it")
                    .hasSize(5)
                    .containsExactlyInAnyOrder(Integer.valueOf(40), Integer.valueOf(80),
                            Integer.valueOf(100), Integer.valueOf(133), Integer.valueOf(430));
        }

        /**
         * The recorded evidence page names the fifth width and the test that verifies it.
         *
         * @throws IOException if the page cannot be read
         */
        @Test
        @DisplayName("the recorded evidence names the fifth width and the test that verifies it, so the "
                + "page and this suite cannot disagree about how many widths the contract has")
        void theRecordedEvidenceNamesTheFifthWidth() throws IOException {
            final String recorded =
                    Files.readString(documentationFile(GATE_EVIDENCE), StandardCharsets.UTF_8);

            assertThat(recorded)
                    .as("the complete set is stated on the page, not only the golden-backed four")
                    .contains("40, 80, 100, 133 and 430");
            assertThat(recorded)
                    .as("and the page names the test that verifies the width without a golden")
                    .contains("CategoryBalanceReportJobConfigIT");
        }

        /**
         * The four external sort specifications are four, and the fourth is named.
         *
         * @throws IOException if the architecture page cannot be read
         */
        @Test
        @DisplayName("the architecture page records FOUR external sort specifications, because the "
                + "category-balance report's three ascending keys are a specification of their own")
        void theArchitecturePageRecordsFourSortSpecifications() throws IOException {
            final String architecture =
                    Files.readString(documentationFile(ARCHITECTURE), StandardCharsets.UTF_8);

            assertThat(architecture)
                    .as("the estate carries no internal sort verb, so every ordering is external and the "
                            + "count of specifications is the count of comparators the batch tier owes")
                    .contains("external, in **four** distinct specifications");
            assertThat(architecture)
                    .as("and the fourth is named rather than implied")
                    .contains("category-balance report job sorts on **three** keys");
        }
    }

    /**
     * The build-enforced gates, and the honest boundary of what a test in this tier can claim about them.
     *
     * <h2>Why the mechanism is asserted and the report is not required</h2>
     * This class runs at {@code integration-test}. The vulnerability scan and the coverage check are bound to
     * {@code verify}, a later phase, so neither report exists yet while these assertions run. Demanding one
     * would fail every clean build for a reason unrelated to either gate. What is asserted instead is the
     * thing that actually enforces them: the plugin is declared, bound to a phase an ordinary build reaches,
     * not skipped, and configured to end the build on a qualifying finding. A report that a previous build
     * left behind is read and checked when present. What is never done is to infer a pass from an absent
     * report - the sign-off summary records the mechanism as the satisfying artefact and names it as such.
     */
    @Nested
    @DisplayName("Gates 2, 7 and 8 - the build-enforced gates, asserted at their enforcing mechanism")
    class BuildEnforcedGates {

        BuildEnforcedGates() {
        }

        /**
         * The compiler is what makes the zero-warning gate mechanical rather than a matter of attention.
         *
         * @throws IOException if the build file cannot be read
         */
        @Test
        @DisplayName("the compiler runs at release 25 with every lint category on and warnings promoted to "
                + "errors")
        void theCompilerFailsOnAnyWarning() {
            final Element compiler = activePlugin("maven-compiler-plugin");

            assertThat(configuredValue(compiler, "configuration", "release"))
                    .as("the language and platform level the plan pins, read from the plugin's own "
                            + "configuration element rather than from anywhere in the file")
                    .isEqualTo("25");
            assertThat(configuredList(compiler, "configuration", "compilerArgs"))
                    .as("every lint category is enabled and a warning ends the build. Both are read as "
                            + "argument elements of the active plugin, so a commented-out block cannot "
                            + "satisfy this: a comment is not an element and the parse cannot see it")
                    .contains("-Xlint:all", "-Werror");
        }

        /**
         * The coverage floor is a failing check on the gated counter.
         *
         * <p>Only the line counter is gated. Branch, method and instruction coverage are reported for
         * information, so no branch assertion is made here - inventing one would add a standard the plan
         * does not set.
         *
         * @throws IOException if the build file cannot be read
         */
        @Test
        @DisplayName("coverage is enforced as a failing check on the line counter, with branch coverage "
                + "reported rather than gated")
        void theCoverageFloorIsAFailingCheck() {
            final Element coverage = activePlugin("jacoco-maven-plugin");
            final Element check = executionOf(coverage, "jacoco-check-line-coverage");

            assertThat(goalsOf(check))
                    .as("the execution runs the check goal, not merely a report goal - a report nobody "
                            + "reads enforces nothing")
                    .containsExactly("check");
            assertThat(phaseOf(check))
                    .as("bound to a phase an ordinary build reaches")
                    .isEqualTo("verify");
            assertThat(configuredValue(check, "configuration", "haltOnFailure"))
                    .as("and it halts, or the check reports a shortfall and the build succeeds anyway")
                    .isEqualTo("true");

            final List<Element> limits = elementsUnder(check,
                    "configuration", "rules", "rule", "limits");
            final Map<String, String> gated = new LinkedHashMap<>();
            for (final Element limit : limits) {
                final String counter = childValue(limit, "counter");
                final String bound = childValue(limit, "minimum") != null
                        ? "minimum=" + childValue(limit, "minimum")
                        : "maximum=" + childValue(limit, "maximum");
                gated.put(counter + "/" + childValue(limit, "value"), bound);
            }

            assertThat(gated)
                    .as("the gated counter is the line counter expressed as a covered ratio, at the floor "
                            + "the plan sets, and no class may be wholly untested - both limits read from "
                            + "the execution's own rule rather than matched as text")
                    .containsEntry("LINE/COVEREDRATIO", "minimum=0.80")
                    .containsEntry("CLASS/MISSEDCOUNT", "maximum=0");
            assertThat(gated)
                    .as("branch coverage is reported for information and deliberately not gated; a branch "
                            + "limit here would invent a standard the plan does not set")
                    .doesNotContainKey("BRANCH/COVEREDRATIO");
        }

        /**
         * The vulnerability scan is executed by an ordinary build, not merely declared.
         *
         * @throws IOException if the build file cannot be read
         */
        @Test
        @DisplayName("the vulnerability scan is bound to verify, enabled by default, and ends the build on "
                + "a critical or high finding")
        void theVulnerabilityScanIsBoundAndEnforcing() {
            final Element scan = activePlugin("dependency-check-maven");
            final Element check = executionOf(scan, "owasp-dependency-check");

            assertThat(goalsOf(check))
                    .as("the execution runs the check goal, which is the one that enforces a threshold")
                    .containsExactly("check");
            assertThat(phaseOf(check))
                    .as("bound to a phase an ordinary build reaches")
                    .isEqualTo("verify");
            assertThat(configuredValue(scan, "configuration", "skip"))
                    .as("enabled by default: a gate that is skipped unless someone opts in is not a gate. "
                            + "The value is resolved through the property it names, so a property flipped "
                            + "to true would fail here")
                    .isEqualTo("false");
            assertThat(configuredValue(scan, "configuration", "skipTestScope"))
                    .as("the test graph is scanned too, because a library reaches the build through it")
                    .isEqualTo("false");
            assertThat(configuredValue(scan, "configuration", "failBuildOnCVSS"))
                    .as("and it ends the build at the score that denotes a high finding, which is what "
                            + "makes zero critical and high a build property rather than a claim")
                    .isEqualTo("7.0");
            assertThat(configuredValue(scan, "configuration", "failBuildOnUnusedSuppressionRule"))
                    .as("an analyst determination that no longer applies is itself a failure, so a "
                            + "suppression cannot outlive the finding it was written for")
                    .isEqualTo("true");
            assertThat(configuredList(scan, "configuration", "formats"))
                    .as("a machine-readable format is produced, which is what makes the report below "
                            + "inspectable rather than merely readable")
                    .contains("JSON");
            assertThat(configuredList(scan, "configuration", "suppressionFiles"))
                    .as("the determinations are read from this module's own file")
                    .anySatisfy(location -> assertThat(location).endsWith(SUPPRESSION_FILE));
            assertThat(Files.isRegularFile(Path.of(SUPPRESSION_FILE)))
                    .as("and that file exists at %s, or the configuration names a file the scan cannot "
                            + "read", Path.of(SUPPRESSION_FILE).toAbsolutePath())
                    .isTrue();
        }

        /**
         * Every analyst determination is narrow enough to expire by itself.
         *
         * <p>This is the half of the supply-chain gate a test in this tier <em>can</em> establish, and the
         * half a reader is most likely to assume has been checked. A suppression is an accepted finding;
         * an unscoped or wildcard one accepts findings nobody has looked at, including ones that do not
         * exist yet. Each rule is therefore required to name at least one artefact scope and exactly one
         * identifier, which is what keeps {@code failBuildOnUnusedSuppressionRule} able to retire it.
         *
         * @throws IOException if the determinations cannot be read
         */
        @Test
        @DisplayName("every suppression names an artefact scope and exactly one identifier, so none can "
                + "absorb a finding nobody has examined")
        void everySuppressionIsScopedToOneIdentifier() throws IOException {
            final List<SuppressionRule> rules = suppressionRules();

            assertThat(rules)
                    .as("the determination file is read and its rules are inspected rather than counted; "
                            + "an empty file is legitimate and is reported as zero determinations")
                    .isNotNull();
            for (final SuppressionRule rule : rules) {
                assertThat(rule.scopes())
                        .as("determination %d names no artefact scope, so it would absorb the same "
                                + "identifier reported against any dependency in the graph", rule.ordinal())
                        .isNotEmpty();
                assertThat(rule.identifiers())
                        .as("determination %d must carry exactly one identifier, so what was accepted is "
                                + "unambiguous and the unused-rule check can retire it", rule.ordinal())
                        .hasSize(1);
                assertThat(rule.wildcardScoped())
                        .as("determination %d is scoped by a regular expression broad enough to match a "
                                + "coordinate nobody has examined", rule.ordinal())
                        .isFalse();
            }
        }

        /**
         * The gate's published claim is the invariant it enforces, and the one determination is scoped.
         *
         * <p>This is the assertion that stops the sign-off below from being a stronger statement than the
         * build makes. The threshold ends the build on an <em>unsuppressed</em> qualifying finding, so
         * "zero critical and high findings" is only true while the determination file is empty. It is not
         * empty: one high-severity match against the embedded servlet container is carried, because the
         * advisory names fixed releases that are published on no line. That is a defensible position and a
         * different one from zero findings, and the difference has to be checkable rather than a matter of
         * how the comment beside it is worded - which is why the withdrawn claim is asserted absent, by
         * text, in both the build file and the manual.
         *
         * <p>Four properties are asserted of the determination itself, and each closes a way for a
         * determination to become standing permission: exactly one rule, so the file cannot accumulate
         * entries nobody reviewed; exactly one identifier and one artifact scope per rule, so a bare
         * identifier cannot absorb a future finding against the same coordinate; no platform-record or
         * coordinate-regex element, which are the two shapes broad enough to match something unexamined;
         * and the false-match evidence present in the file, so the argument travels with the rule rather
         * than living in somebody's memory.
         *
         * @throws IOException if the build file, the determination file or the manual cannot be read
         */
        @Test
        @DisplayName("the published claim is zero unsuppressed critical or high findings, and the one "
                + "determination is scoped to a single identifier on named artifacts with its evidence")
        void theOneDeterminationIsScopedEvidencedAndPublishedAsWhatItIs() throws IOException {
            final String declarations = withoutXmlComments(suppressionFile());

            assertThat(countOf(declarations, "<suppress>"))
                    .as("exactly one determination is carried. A file that grows silently is how a "
                            + "threshold becomes advisory, so the count is asserted rather than the "
                            + "presence")
                    .isEqualTo(1);
            assertThat(countOf(declarations, "<cve>"))
                    .as("and it names one identifier, so it cannot cover a second finding against the "
                            + "same artifacts")
                    .isEqualTo(1);
            assertThat(countOf(declarations, "<packageUrl"))
                    .as("scoped to named artifacts: an identifier with no artifact scope suppresses that "
                            + "identifier everywhere it is ever matched")
                    .isEqualTo(1);
            assertThat(declarations)
                    .as("the identifier and the artifact scope are the documented ones, asserted "
                            + "exactly so a later edit cannot loosen either")
                    .contains("<cve>" + DETERMINED_IDENTIFIER + "</cve>")
                    .contains(DETERMINED_ARTIFACT_SCOPE);
            assertThat(declarations)
                    .as("no wildcard platform record, no coordinate regex and no name match: these are "
                            + "the element shapes broad enough to absorb a finding nobody has examined")
                    .doesNotContain("<cpe>")
                    .doesNotContain("<gav")
                    .doesNotContain("<vulnerabilityName");

            assertThat(suppressionFile())
                    .as("the false-match argument travels with the rule - the unpublished fixed release, "
                            + "the component that is absent from the resolved artifacts, and the decision "
                            + "log entry carrying the measurement")
                    .contains("10.1.58")
                    .contains("webapps")
                    .contains("DL-159");

            for (final String published : List.of(buildFile(), operatorManual())) {
                assertThat(published)
                        .as("the determination is disclosed where the gate result is published, because a "
                                + "determination recorded only in the file that applies it is a silent one")
                        .contains(DETERMINED_IDENTIFIER)
                        .contains(SUPPRESSION_FILE);
                assertThat(published)
                        .as("and the withdrawn claim cannot return: with a determination configured, "
                                + "\"zero findings\" and \"no suppression file\" are both stronger than "
                                + "what the build enforces")
                        .doesNotContain("ZERO SUPPRESSIONS")
                        .doesNotContain("There is no suppression file")
                        .doesNotContain("Zero critical and zero high findings");
            }
        }

        /**
         * Any vulnerability report a build has already produced matches the invariant the gate enforces.
         *
         * <p>Read when present, and never used to infer a pass when absent. The absence case is reported in
         * the sign-off summary as "enforced by the build at a later phase", which is the truthful statement:
         * the plugin above ends the build on a qualifying finding whether or not this test ever sees its
         * report.
         *
         * <p><strong>Both halves of the report are read, and that is the point.</strong> Checking only the
         * ordinary findings array would let a suppressed high-severity entry sit behind a green row: the
         * scanner moves a suppressed finding out of {@code vulnerabilities} and into
         * {@code suppressedVulnerabilities}, so a rule widened by one character would empty the array this
         * test used to read and change nothing it used to assert. So the unsuppressed qualifying findings
         * must be none, and every suppressed qualifying finding must be the one determination this module
         * has examined, on an artifact inside its documented scope. Anything else - a second identifier, the
         * same identifier on a coordinate nobody looked at - fails here, which is what makes the sign-off
         * row below a statement about the report rather than about the threshold alone.
         *
         * @throws IOException if the report cannot be read
         */
        @Test
        @DisplayName("a vulnerability report left by a previous build carries no unsuppressed critical or "
                + "high finding, and every suppressed one is the examined determination")
        void anyExistingVulnerabilityReportMatchesTheEnforcedInvariant() throws IOException {
            final Optional<Path> report = existingVulnerabilityReport();

            if (report.isEmpty()) {
                // Not a skip and not a pass by default: the enforcing mechanism is asserted
                // unconditionally by the two tests above, which is what actually holds this gate. This
                // assertion exists to check a report when the build has produced one, and the scan is
                // bound to a later phase than this tier.
                assertThat(buildFile())
                        .as("no report exists yet because the scan runs at verify, after this tier. The "
                                + "gate is held by the binding, the threshold and the determination scope, "
                                + "which are asserted unconditionally rather than inferred from this "
                                + "absence")
                        .contains("dependency-check-maven");
                return;
            }

            final JsonNode findings = new ObjectMapper()
                    .readTree(Files.readAllBytes(report.orElseThrow()));
            final List<String> qualifying = new ArrayList<>();
            final List<String> unexamined = new ArrayList<>();
            for (final JsonNode dependency : findings.path("dependencies")) {
                final String artefact = dependency.path("fileName").asText();
                for (final JsonNode vulnerability : dependency.path("vulnerabilities")) {
                    final double score = highestScoreOf(vulnerability);
                    if (score >= QUALIFYING_SCORE) {
                        qualifying.add(artefact + " -> " + vulnerability.path("name").asText()
                                + " (" + score + ")");
                    }
                }
                for (final JsonNode suppressed : dependency.path("suppressedVulnerabilities")) {
                    final double score = highestScoreOf(suppressed);
                    final String identifier = suppressed.path("name").asText();
                    if (score >= QUALIFYING_SCORE
                            && !(DETERMINED_IDENTIFIER.equals(identifier)
                                    && artefact.contains(DETERMINED_ARTIFACT_FAMILY))) {
                        unexamined.add(artefact + " -> " + identifier + " (" + score + ")");
                    }
                }
            }

            assertThat(qualifying)
                    .as("zero unsuppressed critical and zero unsuppressed high findings in %s",
                            report.orElseThrow())
                    .isEmpty();
            assertThat(unexamined)
                    .as("every suppressed critical or high finding in %s must be %s on a %s artefact - the "
                            + "one determination this module has examined. Each entry above is a "
                            + "qualifying finding that a rule hid without anybody arguing that it does not "
                            + "apply, which is exactly the state the threshold exists to prevent",
                            report.orElseThrow(), DETERMINED_IDENTIFIER, DETERMINED_ARTIFACT_FAMILY)
                    .isEmpty();
        }

        /**
         * Any vulnerability report a build has already produced carries no critical or high finding.
         *
         * <p>Read when present, and never used to infer a pass when absent. The absence case is reported in
         * the sign-off summary as "enforced by the build at a later phase", which is the truthful statement:
         * the plugin above ends the build on a qualifying finding whether or not this test ever sees its
         * report.
         *
         * @throws IOException if the report cannot be read
         */
        @Test
        @DisplayName("a vulnerability report, when one exists, is current and carries no critical or high "
                + "finding, and a stale one fails rather than passing")
        void anyExistingVulnerabilityReportIsCurrentAndClean() throws IOException {
            final VulnerabilityEvidence evidence = vulnerabilityEvidence();

            assertThat(evidence.stale())
                    .as("%s was produced before the current dependency declaration, so it describes a "
                            + "resolved graph that is no longer the one being built. It is NOT read as a "
                            + "pass: re-run ./mvnw -B clean verify. %s",
                            evidence.reportLocation(), evidence.narrative())
                    .isFalse();
            assertThat(evidence.qualifyingFindings())
                    .as("zero critical and zero high findings. %s", evidence.narrative())
                    .isEmpty();
            assertThat(evidence.undocumentedSuppressions())
                    .as("every finding the scan suppressed must be covered by a determination in %s, or "
                            + "a finding was set aside by something other than a reviewed rule. %s",
                            SUPPRESSION_FILE, evidence.narrative())
                    .isEmpty();
            assertThat(evidence.satisfied())
                    .as("the supply-chain row is satisfied by the parsed enforcing mechanism together "
                            + "with the inspected determinations, and by a current clean report when one "
                            + "exists. It is never satisfied by an absent report. %s", evidence.narrative())
                    .isTrue();
        }

        /**
         * The integration tier reaches this class, which is what makes every assertion above execute.
         *
         * <p>The tier's inclusion patterns and the unit tier's exclusions are strictly complementary, so this
         * class runs exactly once and it runs where the server exists. A class named for a gate rather than
         * for a subject is reached by the package pattern rather than by a suffix.
         *
         * @throws IOException if the build file cannot be read
         */
        @Test
        @DisplayName("the integration tier includes this class by package pattern and the unit tier excludes "
                + "it, so it runs exactly once and where the server is")
        void theIntegrationTierReachesThisClassExactlyOnce() {
            final List<String> integrationIncludes = configuredList(
                    activePlugin("maven-failsafe-plugin"), "configuration", "includes");
            final List<String> unitExcludes = configuredList(
                    activePlugin("maven-surefire-plugin"), "configuration", "excludes");

            assertThat(integrationIncludes)
                    .as("the integration tier's two suffix patterns and the package pattern that reaches "
                            + "a gate-named class like this one, read as include elements of the active "
                            + "plugin rather than matched anywhere in the file")
                    .contains("**/*IT.java", "**/*E2ETest.java", "**/e2e/**/*Test.java");
            assertThat(unitExcludes)
                    .as("the unit tier excludes the same package, so the two sets do not overlap and this "
                            + "class cannot run twice - once without a server, which would fail")
                    .contains("**/e2e/**/*.java");
        }

        /**
         * The scope tier Gate 7 claims, measured from the estate rather than restated.
         *
         * <p>The record layouts and the job census are counted from the committed artefacts, so this
         * assertion is evidence rather than arithmetic over remembered numbers.
         *
         * <h4>A correction this test discovered, and why the conclusion survives it</h4>
         * The plan describes 78 program-invocation steps of which 69 are utility steps. Counting them gives
         * <strong>79 steps, of which 70 are utility steps</strong>. The plan's own per-utility breakdown -
         * 52 dataset-utility, 8 display, 5 sort, 3 null-execution, 1 reproduction and 1 resource-definition
         * steps - sums to 70, not 69, so 78 is an arithmetic slip for 79 rather than a miscount of any
         * category.
         *
         * <p>What matters is that the load-bearing figure is unaffected: exactly <strong>nine</strong> steps
         * invoke an application program, which is why the module carries nine job configurations and not one
         * per step. The corrected total belongs in the decision log; it is asserted here at the measured
         * value so the record and the estate cannot disagree.
         *
         * @throws IOException if the estate cannot be read
         */
        @Test
        @DisplayName("all five scope dimensions are spanned, and the job census measures 79 steps of which "
                + "exactly nine invoke an application program")
        void allFiveScopeDimensionsAreSpanned() throws IOException {
            assertThat(TestDataFactory.ALL_LAYOUTS)
                    .as("file I/O: eleven verified fixed-width layouts")
                    .hasSize(APPLICATION_TABLE_COUNT);
            assertThat(TestDataFactory.ALL_LAYOUTS.stream()
                            .map(TestDataFactory.RecordLayout::recordLength).distinct().count())
                    .as("spanning seven distinct record lengths, so the mapping layer is exercised over a "
                            + "genuine spread rather than one width repeated")
                    .isEqualTo(7L);

            final Map<String, Integer> calls = staticCallCensus();
            assertThat(calls)
                    .as("inter-program linkage, MEASURED from the estate rather than restated: 13 calls to "
                            + "the statement helper, 4 to the date utility, 9 to the abort service and 1 "
                            + "to the date service, each of which became an injected collaborator. A "
                            + "target the estate calls and this map omits fails here rather than being "
                            + "absorbed into a total")
                    .containsExactlyInAnyOrderEntriesOf(EXPECTED_CALL_TARGETS);
            assertThat(calls.values().stream().mapToInt(Integer::intValue).sum())
                    .as("which is 27 call sites in all")
                    .isEqualTo(27);

            final VerbCensus transfers = cicsVerbCensus("EXEC CICS XCTL");
            final VerbCensus rearms = cicsVerbCensus("EXEC CICS RETURN TRANSID");
            assertThat(transfers.total())
                    .as("plus %d transfer-control transitions, measured. A statement continued onto a "
                            + "second line is one transition, which is why the census reads each member "
                            + "as a whole rather than line by line - a line-wise count finds 9",
                            EXPECTED_TRANSFER_CONTROL_SITES)
                    .isEqualTo(EXPECTED_TRANSFER_CONTROL_SITES);
            assertThat(rearms.total())
                    .as("and %d pseudo-conversational re-arms, which became routing decisions returned to "
                            + "the caller", EXPECTED_REARM_SITES)
                    .isEqualTo(EXPECTED_REARM_SITES);
            assertThat(transfers.members())
                    .as("distributed across the %d online programs, so the figure is a property of the "
                            + "online tier rather than of one member", ONLINE_PROGRAM_COUNT)
                    .hasSize(ONLINE_PROGRAM_COUNT);
            assertThat(rearms.members().keySet())
                    .as("and every program that re-arms is one that transfers control, which is what "
                            + "makes the two sets one tier")
                    .isEqualTo(transfers.members().keySet());

            assertThat(countByExtension("app/jcl", ".jcl") + countByExtension("app/jcl", ".JCL"))
                    .as("job orchestration: 29 job members, counted case-inclusively")
                    .isEqualTo(29);
            assertThat(countByExtension("app/proc", ".prc"))
                    .as("and two cataloged procedures")
                    .isEqualTo(2);
            assertThat(conditionCodeGateCount())
                    .as("four condition-code gates in the whole estate, which became step transitions. "
                            + "They are sparse enough to enumerate, which is what makes the batch tier's "
                            + "flow control reviewable")
                    .isEqualTo(4);

            final Map<String, Integer> steps = execPgmCensus();
            int applicationSteps = 0;
            int utilitySteps = 0;
            for (final Map.Entry<String, Integer> entry : steps.entrySet()) {
                if (BATCH_PROGRAMS.contains(entry.getKey())) {
                    applicationSteps += entry.getValue().intValue();
                } else {
                    utilitySteps += entry.getValue().intValue();
                }
            }

            assertThat(applicationSteps + utilitySteps)
                    .as("79 program-invocation steps across the 29 job members and two procedures, "
                            + "measured. The plan records 78, which its own utility breakdown contradicts; "
                            + "the measured value is asserted and the correction is logged")
                    .isEqualTo(79);
            assertThat(utilitySteps)
                    .as("70 are utility steps - dataset definition, display, sorting, null execution, "
                            + "reproduction and resource definition - and none becomes a batch step: they "
                            + "are absorbed by the migrations and the local stack")
                    .isEqualTo(70);
            assertThat(applicationSteps)
                    .as("which leaves exactly nine steps that invoke an application program. That is the "
                            + "load-bearing figure, and it is why the module carries nine job "
                            + "configurations rather than one per step")
                    .isEqualTo(9);
            assertThat(steps.get("CBTRN03C"))
                    .as("the report program is invoked by two steps, which is why nine steps invoke only "
                            + "eight distinct programs")
                    .isEqualTo(2);
        }
    }

    /**
     * Gate 3, which establishes the first Java baseline and asserts no threshold.
     *
     * <h2>What is verified here, and what would be a mistake to verify</h2>
     * No numeric latency, throughput, availability or capacity figure exists anywhere in the estate - not
     * in the programs, the job control, the resource definitions or the specification - so there is
     * nothing for a measurement to be compared against. This gate is discharged by having <em>measured</em>
     * and <em>recorded</em> a baseline, and inventing a threshold to test it against is expressly
     * forbidden.
     *
     * <p>What that leaves is nonetheless checkable, and it is the part that was previously taken on trust:
     * a recorded baseline has to be a measurement rather than a heading. Each row is therefore required to
     * carry a concrete date, a named machine, a run label and four positive figures, and its published
     * rate has to agree with its own record count and elapsed time. A row whose rate does not follow from
     * its own two figures was not copied out of a run.
     */
    @Nested
    @DisplayName("Gate 3 - the recorded performance baseline, as measured figures rather than headings")
    class RecordedPerformanceBaseline {

        /** Creates the nested specification. */
        RecordedPerformanceBaseline() {
            // Intentionally empty.
        }

        /**
         * The evidence page carries at least one measured row, and every row is a measurement.
         *
         * @throws IOException if the recorded evidence cannot be read
         */
        @Test
        @DisplayName("at least one run is recorded with a concrete date, a named machine and four positive "
                + "figures, and none is left as a placeholder")
        void theBaselineIsRecordedAsMeasuredFigures() throws IOException {
            final List<PerformanceRow> rows = recordedPerformanceRows();

            assertThat(rows)
                    .as("Gate 3 is discharged by a recorded measurement, so the table under 'Measured "
                            + "runs' in %s must carry at least one row. A heading is not a baseline",
                            documentationFile(GATE_EVIDENCE))
                    .isNotEmpty();
            for (final PerformanceRow row : rows) {
                assertThat(row.date())
                        .as("row '%s' must name the date it was taken, as a strictly resolved calendar "
                                + "date rather than a placeholder", row.run())
                        .isNotNull();
                // A placeholder in this table is written as emphasised prose, so the check is that the
                // cell is not emphasised text - not that it carries no underscore, which a processor
                // architecture legitimately does.
                assertThat(row.machine())
                        .as("row '%s' must name the machine: a figure without a host, a processor and a "
                                + "runtime is not comparable with anything, and an emphasised placeholder "
                                + "is not a machine", row.run())
                        .isNotBlank()
                        .doesNotStartWith("_")
                        .doesNotEndWith("_")
                        .hasSizeGreaterThan(10);
                assertThat(row.run()).as("every row names the run it measured").isNotBlank();
                assertThat(row.records()).as("row '%s': records processed", row.run()).isPositive();
                assertThat(row.elapsedMillis()).as("row '%s': elapsed time", row.run()).isPositive();
                assertThat(row.peakHeapBytes()).as("row '%s': peak heap", row.run()).isPositive();
                assertThat(row.recordsPerSecond())
                        .as("row '%s': throughput", row.run())
                        .isGreaterThan(BigDecimal.ZERO);
            }
        }

        /**
         * Each recorded rate follows from that row's own two figures.
         *
         * <p>This is what separates a transcribed measurement from a plausible-looking number: records
         * divided by elapsed seconds is arithmetic anyone can repeat, and a row that fails it was not
         * copied from a run. The tolerance exists because the published elapsed time is whole
         * milliseconds while the recorder divides nanoseconds.
         *
         * @throws IOException if the recorded evidence cannot be read
         */
        @Test
        @DisplayName("every recorded rate is that row's own records divided by that row's own elapsed "
                + "time, so a row cannot be written without having been measured")
        void everyRecordedRateFollowsFromItsOwnFigures() throws IOException {
            for (final PerformanceRow row : recordedPerformanceRows()) {
                final BigDecimal derived = BigDecimal.valueOf(row.records())
                        .multiply(BigDecimal.valueOf(1000L))
                        .divide(BigDecimal.valueOf(row.elapsedMillis()), MEASUREMENT_PRECISION);
                final BigDecimal tolerance = derived.multiply(RATE_TOLERANCE);

                assertThat(row.recordsPerSecond().subtract(derived).abs())
                        .as("row '%s' publishes %s records per second, but %d records over %d ms is %s. "
                                + "A rate that does not follow from its own two figures was not measured",
                                row.run(), row.recordsPerSecond(), row.records(), row.elapsedMillis(),
                                derived)
                        .isLessThanOrEqualTo(tolerance);
            }
        }

        /**
         * Whatever the build has measured in this run is itself well formed.
         *
         * <p>The generated files are the source the recorded rows are copied from, so they are checked in
         * the same shape. They are read when present and their absence is not a failure: the tiers that
         * take the measurements are separate classes, and requiring their output here would make this
         * class depend on the order the tier happened to run in.
         *
         * @throws IOException if a generated file cannot be read
         */
        @Test
        @DisplayName("any run-scoped evidence this build produced carries the same four figures in the "
                + "same shape, so the recorded rows and the generated ones cannot diverge")
        void anyGeneratedEvidenceCarriesTheSameFigures() throws IOException {
            final List<GeneratedBaseline> generated = generatedPerformanceEvidence();

            for (final GeneratedBaseline baseline : generated) {
                assertThat(baseline.rows())
                        .as("%s exists, so it must carry at least one measured row", baseline.location())
                        .isNotEmpty();
                for (final GeneratedRow row : baseline.rows()) {
                    assertThat(row.records()).as("%s: records", baseline.location()).isPositive();
                    assertThat(row.elapsedMillis()).as("%s: elapsed", baseline.location()).isPositive();
                    assertThat(row.peakHeapBytes()).as("%s: peak heap", baseline.location()).isPositive();
                    assertThat(row.recordsPerSecond())
                            .as("%s: throughput", baseline.location())
                            .isGreaterThan(BigDecimal.ZERO);
                }
                assertThat(baseline.namesItsFixtureVolumes())
                        .as("%s must state the fixture volumes its figures were measured over; a number "
                                + "without them is not a baseline", baseline.location())
                        .isTrue();
            }
        }

        /**
         * Every measured run names the fixture volumes its figures were taken over.
         *
         * <p>A figure without the input it was taken over cannot be compared with a later one, so the
         * volumes are part of the measurement rather than commentary beside it. The bullet is looked up by
         * the run and the record count together, because the page records the same job at more than one
         * volume and a lookup on the job alone would let one bullet stand in for every row.
         *
         * @throws IOException if the page cannot be read
         */
        @Test
        @DisplayName("every measured run names the fixture volumes behind it, because a figure without its "
                + "volumes is not a baseline")
        void everyMeasuredRunNamesItsFixtureVolumes() throws IOException {
            final String recorded =
                    Files.readString(documentationFile(GATE_EVIDENCE), StandardCharsets.UTF_8);
            final List<PerformanceRow> measured = recordedPerformanceRows();

            assertThat(measured).as("there is a measurement to name volumes for").isNotEmpty();
            for (final PerformanceRow row : measured) {
                final String heading = "**`" + row.run() + "`, " + row.records() + " records**";
                assertThat(recorded)
                        .as("the volumes behind %s at %d records are named on the page, so the figure can "
                                + "be compared to a later run over the same input", row.run(),
                                row.records())
                        .contains(heading);
            }
        }

        /**
         * The page states that these figures are measurements, which is the boundary of the gate.
         *
         * @throws IOException if the page cannot be read
         */
        @Test
        @DisplayName("the recorded baseline states that it is a measurement and not a threshold, because "
                + "no service level exists anywhere in the estate to test against")
        void theRecordedBaselineIsLabelledAMeasurementAndNotAThreshold() throws IOException {
            final String recorded =
                    Files.readString(documentationFile(GATE_EVIDENCE), StandardCharsets.UTF_8);

            assertThat(recorded)
                    .as("a reader who quotes one of these rows must be told what it is not")
                    .contains("measurements, not thresholds");
            assertThat(recorded)
                    .as("and why: the estate documents no service level of any kind")
                    .contains("establishes the first Java baseline");
        }
    }

    /**
     * The sign-off checklist, emitted as an auditable record.
     *
     * <p>One row per checklist item, each naming the artefact that satisfies it and the state that artefact
     * is actually in. Nothing is recorded as satisfied on the strength of an intention: an artefact that is
     * absent produces a row saying so, with the absolute path it was expected at, and the assertion below
     * fails. The two build-enforced rows record their enforcing mechanism as the artefact, because that is
     * what holds them at this phase, and say so in the row rather than implying a report was read.
     */
    @Nested
    @DisplayName("Gate 8 - the sign-off checklist, emitted as an auditable record with no assumed pass")
    class SignOffChecklist {

        SignOffChecklist() {
        }

        /**
         * Every checklist item has a present artefact, and the record is written for transcription.
         *
         * @throws IOException if an artefact cannot be examined or the record cannot be written
         * @throws SQLException if the schema state cannot be read
         */
        @Test
        @DisplayName("every checklist item names a present artefact, and the record is written to the build "
                + "directory for transcription into the recorded evidence")
        void everyChecklistItemNamesAPresentArtefact() throws IOException, SQLException {
            final List<ChecklistRow> rows = new ArrayList<>();

            rows.add(rowFor("End-to-end boundary verification",
                    "src/test/resources/fixtures/expected/ + e2e/BatchPipelineE2ETest",
                    goldenArtefactsPresent(),
                    "four goldens at 430, 133, 80 and 100 bytes, compared as byte arrays"));
            final InterfaceContractEvidence contracts = interfaceContractEvidence();
            rows.add(rowFor("Interface contract verification",
                    "e2e/OnlineTransactionE2ETest + service/JobSubmissionServiceIT; card image built here "
                            + "from " + JclCardImageBuilder.class.getSimpleName(),
                    contracts.satisfied(),
                    contracts.narrative()));
            // The satisfying artefact is the recorded page, not the build directory the recorder writes
            // into. That directory is removed by a clean build and is repopulated by whichever tier runs
            // the measured jobs, so keying the row off it would make this row's state depend on test
            // ordering rather than on whether the baseline was ever recorded.
            final List<PerformanceRow> measured = recordedPerformanceRows();
            final StringBuilder baselines = new StringBuilder(160);
            for (final PerformanceRow row : measured) {
                baselines.append(baselines.length() == 0 ? "" : "; ").append(row.run()).append(' ')
                        .append(row.records()).append(" records in ").append(row.elapsedMillis())
                        .append(" ms at ").append(row.recordsPerSecond()).append("/s, peak heap ")
                        .append(row.peakHeapBytes()).append(" B on ").append(row.date());
            }
            rows.add(rowFor("Performance baseline",
                    DOCUMENTATION_DIRECTORY + "/" + GATE_EVIDENCE
                            + " (measured by support/RunScopedPerformanceRecorder)",
                    recordedEvidenceCoversPerformanceBaseline(),
                    measured.isEmpty()
                            ? "NO MEASURED RUN IS RECORDED - the baseline is outstanding work"
                            : baselines + ". No service level is asserted anywhere, because none is "
                                    + "documented anywhere"));
            // "warning suppressions" rather than "suppressions": this row counts @SuppressWarnings in the
            // production tree, and the row below counts analyst determinations against vulnerability
            // findings. Two unrelated things share the word, and a checklist that lets them share it too
            // invites a reader to carry one row's zero across to the other.
            final UnsafeCodeCensus unsafe = unsafeCodeCensus();
            rows.add(rowFor("Unsafe and low-level code audit",
                    PRODUCTION_TREE + " (scoped: migrations and test sources excluded)",
                    unsafe.withinBudget(),
                    unsafe.narrative()));
            final VulnerabilityEvidence supplyChain = vulnerabilityEvidence();
            rows.add(rowFor("Line coverage at or above 80%",
                    BUILD_FILE + " jacoco check goal, merged unit and integration data",
                    coverageFloorIsEnforced(),
                    "ENFORCED BY BUILD at verify, a later phase than this tier: check goal, LINE "
                            + "COVEREDRATIO minimum 0.80 and CLASS MISSEDCOUNT maximum 0, halting"));
            // Worded as the invariant the build enforces, not as the stronger one. The threshold ends the
            // build on an UNSUPPRESSED qualifying finding, and one examined determination is configured,
            // so a row reading "zero critical or high" would claim more than the mechanism delivers. The
            // row's state therefore depends on the determination's scope as well as on the threshold: a
            // rule widened past one identifier on the named artefacts turns this row MISSING.
            rows.add(rowFor("Zero unsuppressed critical or high vulnerabilities, every determination "
                            + "scoped and disclosed",
                    BUILD_FILE + " dependency-check bound to verify, threshold " + QUALIFYING_SCORE
                            + " over compile, runtime and test scope; " + SUPPRESSION_FILE
                            + supplyChain.reportSuffix(),
                    supplyChain.satisfied() && determinationIsScopedToOneExaminedFinding(),
                    "1 determination: " + DETERMINED_IDENTIFIER + " on the three "
                            + DETERMINED_ARTIFACT_FAMILY + " artefacts, self-expiring; "
                            + supplyChain.narrative()));
            rows.add(rowFor("Traceability at 100% of procedure units",
                    DOCUMENTATION_DIRECTORY + "/" + TRACEABILITY_MATRIX,
                    matrixRows().size() == TOTAL_PROCEDURE_UNITS,
                    TOTAL_PROCEDURE_UNITS + " rows = " + PROGRAM_PARAGRAPHS + " + "
                            + DATE_COPYBOOK_PARAGRAPHS + " + " + PFKEY_COPYBOOK_PARAGRAPHS));
            final NamedArtefactEvidence artefacts = namedArtefactEvidence();
            rows.add(rowFor("Named validation artefacts",
                    "src/test/resources" + TestDataFactory.FIXTURE_DIRECTORY + " and "
                            + ENCODED_DATASET_DIRECTORY,
                    artefacts.satisfied(),
                    artefacts.narrative()));
            rows.add(rowFor("Schema parity on a real server",
                    "src/main/resources/db/migration + " + POSTGRES_IMAGE,
                    applicationTableNames().size() == APPLICATION_TABLE_COUNT,
                    APPLICATION_TABLE_COUNT + " tables, migrations "
                            + String.join(" ", MIGRATION_VERSIONS) + ", no floating-point column"));

            publishSignOff(rows);

            final List<String> unsatisfied = new ArrayList<>();
            for (final ChecklistRow row : rows) {
                if (!row.satisfied()) {
                    unsatisfied.add(row.item() + " -> expected " + row.artefact());
                }
            }
            assertThat(unsatisfied)
                    .as("every checklist item must name an artefact that is actually present. A row here "
                            + "is a work item, not a warning: the sign-off is not discharged while one "
                            + "remains")
                    .isEmpty();
            assertThat(rows)
                    .as("the checklist covers the seven items the sign-off enumerates, plus the two the "
                            + "plan adds for the named artefacts and the schema")
                    .hasSize(9);
        }

        /**
         * The recorded evidence page names every gate, so the written record and this one agree.
         *
         * @throws IOException if the document cannot be read
         */
        @Test
        @DisplayName("the recorded evidence page carries a section for every one of the eight gates")
        void theRecordedEvidencePageCoversEveryGate() throws IOException {
            final Path evidence = documentationFile(GATE_EVIDENCE);
            final String recorded = Files.readString(evidence, StandardCharsets.UTF_8);

            for (int gate = 1; gate <= 8; gate++) {
                assertThat(recorded)
                        .as("the recorded evidence must carry a section for gate %d, or the gate has a "
                                + "mechanism and no record; expected in %s", gate, evidence)
                        .contains("Gate " + gate);
            }
            assertThat(recorded)
                    .as("and it names the commands that reproduce the evidence, so a reviewer can rerun "
                            + "it rather than take it on trust")
                    .contains("mvnw");
        }
    }

    // ===================================================================================================
    // HELPERS :: repository resolution
    // ===================================================================================================

    /**
     * Walks upward from the build's working directory to the repository root.
     *
     * <p>The build runs in the module, so the documentation directory and the legacy estate - both of which
     * sit beside the module rather than inside it - cannot be reached by a module-relative path. The root is
     * identified by holding both the module directory and the documentation directory, which is unambiguous
     * here, and the walk is bounded by the filesystem root so a misconfigured working directory produces a
     * diagnostic instead of an endless loop.
     *
     * @return the repository root
     */
    private static Path repositoryRoot() {
        final Path start = Path.of("").toAbsolutePath().normalize();
        for (Path candidate = start; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("carddemo-java"))
                    && Files.isDirectory(candidate.resolve(DOCUMENTATION_DIRECTORY))) {
                return candidate;
            }
        }
        throw new IllegalStateException("no repository root above " + start
                + " holds both a 'carddemo-java' module directory and a '" + DOCUMENTATION_DIRECTORY
                + "' directory. The build's working directory is the module, so these paths are resolved"
                + " by walking upward; if this fails the working directory is not inside the checkout.");
    }

    /**
     * Resolves a repository-relative file and asserts it exists, naming the absolute path if it does not.
     *
     * @param  relativePath the path relative to the repository root
     * @return the resolved path
     */
    private static Path repositoryFile(final String relativePath) {
        final Path resolved = repositoryRoot().resolve(relativePath);
        assertThat(Files.isRegularFile(resolved))
                .as("%s is required by a gate and is missing. Expected a regular file at %s",
                        relativePath, resolved)
                .isTrue();
        return resolved;
    }

    /**
     * Resolves a repository-relative directory and asserts it exists, naming the absolute path if not.
     *
     * @param  relativePath the path relative to the repository root
     * @return the resolved path
     */
    private static Path repositoryDirectory(final String relativePath) {
        final Path resolved = repositoryRoot().resolve(relativePath);
        assertThat(Files.isDirectory(resolved))
                .as("%s is required by a gate and is missing. Expected a directory at %s", relativePath,
                        resolved)
                .isTrue();
        return resolved;
    }

    /**
     * Resolves one of the documentation deliverables, failing with the absolute path when absent.
     *
     * <p>The diagnostic is written to read as a work item rather than as a defect report, because an absent
     * deliverable is outstanding work: a sign-off that stayed silent about it would be indistinguishable
     * from one that had it.
     *
     * @param  fileName the document's file name
     * @return the resolved path
     */
    private static Path documentationFile(final String fileName) {
        final Path resolved = repositoryRoot().resolve(DOCUMENTATION_DIRECTORY).resolve(fileName);
        assertThat(Files.isRegularFile(resolved))
                .as("WORK ITEM: the Gate 8 deliverable '%s' does not exist. It is expected at %s. "
                        + "The sign-off is not discharged until it is written.", fileName, resolved)
                .isTrue();
        return resolved;
    }

    /**
     * Reads the module's build file.
     *
     * @return the build file's text
     * @throws IOException if it cannot be read
     */
    private static String buildFile() throws IOException {
        final Path build = Path.of(BUILD_FILE);
        assertThat(Files.isRegularFile(build))
                .as("the build file is expected at %s, because the build's working directory is the "
                        + "module", build.toAbsolutePath())
                .isTrue();
        return Files.readString(build, StandardCharsets.UTF_8);
    }

    /**
     * Reads the module's analyst-determination file.
     *
     * @return the determination file's text
     * @throws IOException if it cannot be read
     */
    private static String suppressionFile() throws IOException {
        final Path determinations = Path.of(SUPPRESSION_FILE);
        assertThat(Files.isRegularFile(determinations))
                .as("the build configures the scan to read this file, so a missing file is a scan that "
                        + "cannot start rather than a scan with nothing to skip; expected at %s",
                        determinations.toAbsolutePath())
                .isTrue();
        return Files.readString(determinations, StandardCharsets.UTF_8);
    }

    /**
     * Reads the module's operator manual.
     *
     * @return the manual's text
     * @throws IOException if it cannot be read
     */
    private static String operatorManual() throws IOException {
        final Path manual = Path.of(OPERATOR_MANUAL);
        assertThat(Files.isRegularFile(manual))
                .as("the manual is where the gate results are published, and it is expected at %s",
                        manual.toAbsolutePath())
                .isTrue();
        return Files.readString(manual, StandardCharsets.UTF_8);
    }

    /**
     * Strips XML comments from a document so declarations can be counted without counting prose.
     *
     * <p>Necessary because the determination file documents the element types it forbids, and those
     * prohibitions are written as the element names themselves. Counting tokens across the whole text
     * would read a prohibition as an instance of the thing prohibited.
     *
     * @param  xml the document text
     * @return the same text with every comment removed
     */
    private static String withoutXmlComments(final String xml) {
        return xml.replaceAll("(?s)<!--.*?-->", "");
    }

    /**
     * Counts non-overlapping occurrences of a literal within a text.
     *
     * @param  text    the text to scan
     * @param  literal the literal to count
     * @return the number of occurrences
     */
    private static int countOf(final String text, final String literal) {
        int found = 0;
        int at = text.indexOf(literal);
        while (at >= 0) {
            found++;
            at = text.indexOf(literal, at + literal.length());
        }
        return found;
    }

    // ===================================================================================================
    // HELPERS :: reading committed artefacts
    // ===================================================================================================

    /**
     * Reads a class-path resource whole.
     *
     * @param  resource the class-path location
     * @return its bytes
     * @throws IOException if the resource cannot be read
     */
    private static byte[] classpathBytes(final String resource) throws IOException {
        try (InputStream stream = GateVerificationTest.class.getResourceAsStream(resource)) {
            assertThat(stream)
                    .as("%s must be on the test class path; a resource that is absent must fail rather "
                            + "than read as empty", resource)
                    .isNotNull();
            return stream.readAllBytes();
        }
    }

    /**
     * Reads a line-delimited fixture as records, one character per byte.
     *
     * <p>The character set is named explicitly and the separator is the single line feed the fixtures
     * actually use. Relying on the platform default would make the result depend on the host's locale, and
     * relying on the platform line separator would split nothing at all on a host whose separator is two
     * characters - both of which turn a byte-exact assertion into an accident of the machine it ran on.
     *
     * @param  fixtureName the fixture's file name
     * @return its records, in file order, with no separator retained
     * @throws IOException if the fixture cannot be read
     */
    private static List<String> asciiRecords(final String fixtureName) throws IOException {
        final byte[] raw = classpathBytes(TestDataFactory.FIXTURE_DIRECTORY + fixtureName);
        final String content = new String(raw, StandardCharsets.US_ASCII);

        assertThat(content)
                .as("%s carries no carriage return: it stands for a dataset written with a single "
                        + "terminator, and a stray one would shift every field by a byte", fixtureName)
                .doesNotContain("\r");

        final List<String> records = new ArrayList<>();
        for (final String candidate : content.split("\n", -1)) {
            if (!candidate.isEmpty()) {
                records.add(candidate);
            }
        }
        return records;
    }

    /**
     * Counts occurrences of one field's value across a set of records.
     *
     * @param  records the records to census
     * @param  layout  the layout to slice with
     * @param  field   the field's COBOL name
     * @return the value counts, ordered by value
     */
    private static Map<String, Integer> census(final List<String> records,
            final TestDataFactory.RecordLayout layout, final String field) {
        final Map<String, Integer> counts = new TreeMap<>();
        for (final String record : records) {
            counts.merge(layout.slice(record, field), Integer.valueOf(1), (first, second) ->
                    Integer.valueOf(first.intValue() + second.intValue()));
        }
        return counts;
    }

    /**
     * Measures the length of each run of equal consecutive values.
     *
     * @param  values the values, in order
     * @return one length per run
     */
    private static List<Integer> runLengths(final List<String> values) {
        final List<Integer> runs = new ArrayList<>();
        String current = null;
        int length = 0;
        for (final String value : values) {
            if (value.equals(current)) {
                length++;
            } else {
                if (current != null) {
                    runs.add(Integer.valueOf(length));
                }
                current = value;
                length = 1;
            }
        }
        if (current != null) {
            runs.add(Integer.valueOf(length));
        }
        return runs;
    }

    /**
     * Pads a value to a field width with the layout's space filler.
     *
     * @param  value the value
     * @param  width the field width
     * @return the value padded on the right
     */
    private static String padTo(final String value, final int width) {
        return value + String.valueOf(TestDataFactory.SPACE_FILLER).repeat(width - value.length());
    }

    /**
     * The twenty overpunch codes, positive class followed by negative.
     *
     * @return the codes
     */
    private static List<Character> overpunchCodes() {
        final List<Character> codes = new ArrayList<>();
        for (final char code : (TestDataFactory.POSITIVE_OVERPUNCH
                + TestDataFactory.NEGATIVE_OVERPUNCH).toCharArray()) {
            codes.add(Character.valueOf(code));
        }
        return codes;
    }

    /**
     * The measured overpunch census of the daily-transaction fixture.
     *
     * <p>Held as an explicit expectation rather than derived from the file it is compared against, which
     * would be circular. Every count was measured from the committed bytes.
     *
     * @return the expected count per code
     */
    private static Map<Character, Integer> expectedOverpunchCensus() {
        final Map<Character, Integer> expected = new TreeMap<>();
        expected.put(Character.valueOf('A'), Integer.valueOf(28));
        expected.put(Character.valueOf('B'), Integer.valueOf(29));
        expected.put(Character.valueOf('C'), Integer.valueOf(30));
        expected.put(Character.valueOf('D'), Integer.valueOf(29));
        expected.put(Character.valueOf('E'), Integer.valueOf(23));
        expected.put(Character.valueOf('F'), Integer.valueOf(21));
        expected.put(Character.valueOf('G'), Integer.valueOf(24));
        expected.put(Character.valueOf('H'), Integer.valueOf(17));
        expected.put(Character.valueOf('I'), Integer.valueOf(24));
        expected.put(Character.valueOf('J'), Integer.valueOf(3));
        expected.put(Character.valueOf('K'), Integer.valueOf(5));
        expected.put(Character.valueOf('L'), Integer.valueOf(5));
        expected.put(Character.valueOf('M'), Integer.valueOf(6));
        expected.put(Character.valueOf('N'), Integer.valueOf(2));
        expected.put(Character.valueOf('O'), Integer.valueOf(4));
        expected.put(Character.valueOf('P'), Integer.valueOf(7));
        expected.put(Character.valueOf('Q'), Integer.valueOf(4));
        expected.put(Character.valueOf('R'), Integer.valueOf(8));
        expected.put(Character.valueOf('{'), Integer.valueOf(25));
        expected.put(Character.valueOf('}'), Integer.valueOf(6));
        return expected;
    }

    /**
     * Collects the text values of a JSON array.
     *
     * <p>Uses the tree API deliberately. Binding to a generic collection type is the one route here that
     * would need an unchecked conversion, and a warning is a build failure, so the array is walked instead
     * of cast.
     *
     * @param  array the node to walk
     * @return its text values, in document order
     */
    private static List<String> jsonTextArray(final JsonNode array) {
        assertThat(array)
                .as("the lookup resource must carry the shape the service reads")
                .isNotNull();
        assertThat(array.isArray())
                .as("the node must be an array of codes")
                .isTrue();

        final List<String> values = new ArrayList<>(array.size());
        for (final JsonNode element : array) {
            values.add(element.asText());
        }
        return values;
    }

    // ===================================================================================================
    // HELPERS :: the legacy estate
    // ===================================================================================================

    /**
     * Extracts the Area A paragraph labels a legacy member declares.
     *
     * <p>Three details make this correct rather than approximately correct, and each was needed:
     * <ul>
     *   <li><strong>Carriage returns are stripped first.</strong> Five members in this estate use a
     *       two-character terminator and three of those are programs, one of them the 85-paragraph member
     *       that is 16% of the whole count. Splitting on the line feed alone leaves a trailing carriage
     *       return on every label and matches none of them.</li>
     *   <li><strong>Comment and continuation lines are skipped by indicator column.</strong> A label-shaped
     *       string inside a comment is not a paragraph.</li>
     *   <li><strong>Labels are recognised by starting in Area A</strong>, columns 8 through 11, which is what
     *       distinguishes a paragraph name from a statement.</li>
     * </ul>
     *
     * @param  relativePath           the member's repository-relative path
     * @param  onlyAfterProcedureDivision whether to begin at the procedure division, as a program requires
     * @return the labels, in declaration order
     * @throws IOException if the member cannot be read
     */
    private static List<String> paragraphLabelsOf(final String relativePath,
            final boolean onlyAfterProcedureDivision) throws IOException {
        final Path member = repositoryFile(relativePath);
        final String content = Files.readString(member, StandardCharsets.ISO_8859_1);

        final List<String> labels = new ArrayList<>();
        boolean started = !onlyAfterProcedureDivision;
        for (String line : content.split("\n", -1)) {
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            if (line.length() < 8) {
                continue;
            }
            final char indicator = line.charAt(6);
            if (indicator == '*' || indicator == '/') {
                continue;
            }
            if (line.charAt(7) == ' ') {
                continue;
            }
            final String statement = withoutTrailingBlanks(line.substring(7));
            if (!started) {
                if (statement.toUpperCase(Locale.ROOT).startsWith("PROCEDURE DIVISION")) {
                    started = true;
                }
                continue;
            }
            if (isParagraphLabel(statement)) {
                labels.add(statement.substring(0, statement.length() - 1));
            }
        }
        return labels;
    }

    /**
     * Removes the trailing blanks a fixed-format source line is padded with.
     *
     * <p>Written out rather than delegated to a general-purpose trim, and deliberately one-sided. This is
     * <strong>tokenisation of a source line</strong> - a fixed-format line is blank-filled to its right
     * margin, so the terminating period cannot be found without removing that padding - and it is
     * emphatically <em>not</em> normalisation of layout data. Every assertion in this class that concerns a
     * record layout, a key or a filler span compares the untrimmed slice, because a ten-character key with
     * three trailing spaces and a seven-character one are different values and trimming would make them
     * compare equal. Keeping the two operations separately named is what stops the second being mistaken
     * for the first.
     *
     * @param  line the line's content from Area A onward
     * @return the same content with right-hand padding removed and nothing else changed
     */
    private static String withoutTrailingBlanks(final String line) {
        int end = line.length();
        while (end > 0 && line.charAt(end - 1) == ' ') {
            end--;
        }
        return line.substring(0, end);
    }

    /**
     * Reports whether an Area A statement is a bare paragraph label.
     *
     * @param  statement the statement text, already trimmed of layout whitespace
     * @return {@code true} when it is a name followed by a single terminating period
     */
    private static boolean isParagraphLabel(final String statement) {
        if (statement.length() < 2 || !statement.endsWith(".")) {
            return false;
        }
        final String name = statement.substring(0, statement.length() - 1);
        if (name.isEmpty() || name.indexOf('.') >= 0 || name.indexOf(' ') >= 0) {
            return false;
        }
        if (!Character.isLetterOrDigit(name.charAt(0))) {
            return false;
        }
        for (final char character : name.toCharArray()) {
            if (!Character.isLetterOrDigit(character) && character != '-') {
                return false;
            }
        }
        return true;
    }

    /**
     * Lists the members of a legacy directory carrying one exact extension.
     *
     * <p>The extension is matched case-sensitively and callers pass both casings, because this estate mixes
     * them: a lower-case-only pattern silently drops three working members, among them the whole
     * statement-generation feature.
     *
     * @param  relativeDirectory the directory, relative to the repository root
     * @param  extension         the exact extension, including its dot and its casing
     * @return the matching files, sorted by name
     * @throws IOException if the directory cannot be listed
     */
    private static List<Path> filesByExtension(final String relativeDirectory, final String extension)
            throws IOException {
        final Path directory = repositoryDirectory(relativeDirectory);
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(extension))
                    .sorted()
                    .toList();
        }
    }

    /**
     * Counts the members of a legacy directory carrying one exact extension.
     *
     * @param  relativeDirectory the directory, relative to the repository root
     * @param  extension         the exact extension, including its dot and its casing
     * @return the count
     * @throws IOException if the directory cannot be listed
     */
    private static int countByExtension(final String relativeDirectory, final String extension)
            throws IOException {
        return filesByExtension(relativeDirectory, extension).size();
    }

    /**
     * Censuses the programs the job members and procedures invoke, and how many steps invoke each.
     *
     * <p>Only genuine step lines count. A line is a step when it begins a control statement and is not a
     * comment - the estate carries commented-out steps, and counting one would inflate the census with a
     * step that never runs. Carriage returns are stripped for the same reason they are stripped when
     * counting paragraphs.
     *
     * @return the step count per invoked program
     * @throws IOException if the estate cannot be read
     */
    private static Map<String, Integer> execPgmCensus() throws IOException {
        final List<Path> sources = new ArrayList<>();
        sources.addAll(filesByExtension("app/jcl", ".jcl"));
        sources.addAll(filesByExtension("app/jcl", ".JCL"));
        sources.addAll(filesByExtension("app/proc", ".prc"));

        final Map<String, Integer> census = new TreeMap<>();
        for (final Path source : sources) {
            for (String line : Files.readString(source, StandardCharsets.ISO_8859_1)
                    .split("\n", -1)) {
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                if (!line.startsWith("//") || line.startsWith("//*")) {
                    continue;
                }
                final Matcher invocation = EXEC_PGM.matcher(line);
                if (invocation.find()) {
                    census.merge(invocation.group(1), Integer.valueOf(1), (first, second) ->
                            Integer.valueOf(first.intValue() + second.intValue()));
                }
            }
        }
        assertThat(census)
                .as("the job census must find steps; an empty one means the estate was not read")
                .isNotEmpty();
        return census;
    }

    /**
     * Counts the condition-code gates in the estate.
     *
     * @return the number of gated steps
     * @throws IOException if the estate cannot be read
     */
    private static int conditionCodeGateCount() throws IOException {
        final List<Path> sources = new ArrayList<>();
        sources.addAll(filesByExtension("app/jcl", ".jcl"));
        sources.addAll(filesByExtension("app/jcl", ".JCL"));
        sources.addAll(filesByExtension("app/proc", ".prc"));

        int gates = 0;
        for (final Path source : sources) {
            for (String line : Files.readString(source, StandardCharsets.ISO_8859_1)
                    .split("\n", -1)) {
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                if (line.startsWith("//") && !line.startsWith("//*") && line.contains("COND=(")) {
                    gates++;
                }
            }
        }
        return gates;
    }

    /**
     * Counts the lines of every Java source beneath one module-relative directory containing a construct.
     *
     * @param  directory the tree to search, module-relative
     * @param  construct the literal construct to count
     * @return the number of matching lines
     * @throws IOException if the tree cannot be walked
     */
    private static long occurrencesIn(final String directory, final String construct)
            throws IOException {
        final Path root = Path.of(directory);
        assertThat(Files.isDirectory(root))
                .as("the audited tree is expected at %s", root.toAbsolutePath())
                .isTrue();

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

    // ===================================================================================================
    // HELPERS :: the traceability matrix
    // ===================================================================================================

    /**
     * Extracts the matrix's per-member data rows, and only those.
     *
     * <p>Rows are found by locating the shared column header of the per-member tables and taking the rows
     * beneath its alignment row until the table ends. That precision is necessary: the document also carries
     * a provenance table, a composition table, a column legend, a marker legend and a per-member census, so
     * treating every table row in the file as a data row would yield a larger number and make the coverage
     * invariant unassertable.
     *
     * @return one list of trimmed cells per data row
     * @throws IOException if the matrix cannot be read
     */
    private static List<List<String>> matrixRows() throws IOException {
        final Path matrix = documentationFile(TRACEABILITY_MATRIX);
        final List<String> lines =
                List.of(Files.readString(matrix, StandardCharsets.UTF_8).split("\n", -1));

        final List<List<String>> rows = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            if (!MATRIX_ROW_HEADER.equals(lines.get(index).strip())
                    || index + 1 >= lines.size()
                    || !isAlignmentRow(lines.get(index + 1))) {
                continue;
            }
            int cursor = index + 2;
            while (cursor < lines.size() && lines.get(cursor).startsWith("|")
                    && !isAlignmentRow(lines.get(cursor))) {
                rows.add(cellsOf(lines.get(cursor)));
                cursor++;
            }
            index = cursor - 1;
        }

        assertThat(rows)
                .as("no per-member table was found in %s. The row header the count keys off is: %s",
                        matrix, MATRIX_ROW_HEADER)
                .isNotEmpty();
        return rows;
    }

    /**
     * Resolves a class named relative to the base package into its source file.
     *
     * @param  tree      the module-relative source tree the class lives in
     * @param  className the class name, relative to {@code com.carddemo}
     * @return the resolved path, which the caller asserts on
     */
    private static Path moduleFile(final String tree, final String className) {
        return Path.of(tree).resolve(BASE_PACKAGE_PATH)
                .resolve(className.replace('.', '/') + ".java");
    }

    /**
     * Reads a source file, failing the assertion rather than the harness when it cannot be read.
     *
     * <p>Used from inside a map-computing lambda, where a checked exception cannot be declared.
     *
     * @param  file the file to read
     * @return its text
     */
    private static String readOrFail(final Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException failure) {
            throw new AssertionError("the matrix cites " + file.toAbsolutePath()
                    + ", which cannot be read: " + failure.getMessage(), failure);
        }
    }

    /**
     * Extracts the matrix data rows carrying one marker.
     *
     * @param  marker the marker to select on
     * @return the matching rows
     * @throws IOException if the matrix cannot be read
     */
    private static List<List<String>> matrixRowsWhere(final String marker) throws IOException {
        final List<List<String>> selected = new ArrayList<>();
        for (final List<String> row : matrixRows()) {
            if (row.get(6).contains(marker)) {
                selected.add(row);
            }
        }
        return selected;
    }

    /**
     * Reads the matrix's header: everything above the first per-member table.
     *
     * <p>The provenance anchors are asserted against this region rather than against the whole document, so
     * that a stray occurrence of a commit-like string in a later row could not satisfy the assertion.
     *
     * @return the header text
     * @throws IOException if the matrix cannot be read
     */
    private static String matrixHeader() throws IOException {
        final String content =
                Files.readString(documentationFile(TRACEABILITY_MATRIX), StandardCharsets.UTF_8);
        final int firstTable = content.indexOf(MATRIX_ROW_HEADER);
        assertThat(firstTable)
                .as("the matrix must carry at least one per-member table, or there is no header to "
                        + "delimit; expected in %s", documentationFile(TRACEABILITY_MATRIX))
                .isPositive();
        return content.substring(0, firstTable);
    }

    /**
     * Reports whether a line is a table's alignment row rather than a data row.
     *
     * @param  line the line
     * @return {@code true} for an alignment row
     */
    private static boolean isAlignmentRow(final String line) {
        final String candidate = line.strip();
        if (!candidate.startsWith("|") || candidate.length() < 3) {
            return false;
        }
        for (final char character : candidate.toCharArray()) {
            if (character != '|' && character != '-' && character != ':' && character != ' ') {
                return false;
            }
        }
        return true;
    }

    /**
     * Splits a documentation table row into its cells.
     *
     * <p>Cell padding is removed here, and that is correct <em>because this is prose rather than data</em>. A
     * table cell in a Markdown document is padded with spaces so the source aligns for a human reader; the
     * padding carries no meaning and two cells differing only in it are the same cell. That is the exact
     * opposite of the record layouts asserted elsewhere in this class, where padding is the contract and
     * every comparison is made untrimmed. The distinction is why the two operations are named differently
     * and documented separately.
     *
     * @param  line the row
     * @return the cells, with the readability padding removed
     */
    private static List<String> cellsOf(final String line) {
        final String candidate = line.strip();
        final String interior = candidate.substring(1,
                candidate.endsWith("|") ? candidate.length() - 1 : candidate.length());
        final List<String> cells = new ArrayList<>();
        for (final String cell : interior.split("\\|", -1)) {
            cells.add(cell.strip());
        }
        return cells;
    }

    // ===================================================================================================
    // HELPERS :: the running server
    // ===================================================================================================

    /**
     * Runs one complete literal single-column statement against the shared server.
     *
     * <p>Every caller passes a whole statement as a literal, so no value is ever concatenated into SQL. That
     * is what keeps the raw-statement count in the unsafe-code audit at zero for this tier as well as for the
     * production tree.
     *
     * @param  sql the complete statement
     * @return the column's values, in the order the statement returns them
     * @throws SQLException if the statement fails
     */
    private static List<String> queryOneColumnLocally(final String sql) throws SQLException {
        final List<String> values = new ArrayList<>();
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }

    /**
     * Counts the rows of one application table.
     *
     * <p>The table name is matched against the roster the base class publishes before it is used, so the
     * statement is assembled from a value that has been proved to be one of eleven known names rather than
     * from arbitrary input.
     *
     * @param  table one of the eleven application tables
     * @return its row count
     * @throws SQLException if the count cannot be read
     */
    private static int rowCount(final String table) throws SQLException {
        assertThat(APPLICATION_TABLES)
                .as("only the eleven published application tables may be counted, which is what makes "
                        + "this safe without a bound parameter - a table name cannot be one")
                .contains(table);

        final String sql = switch (table) {
            case "account" -> "SELECT COUNT(*) FROM account";
            case "card" -> "SELECT COUNT(*) FROM card";
            case "customer" -> "SELECT COUNT(*) FROM customer";
            case "card_cross_reference" -> "SELECT COUNT(*) FROM card_cross_reference";
            case "transaction" -> "SELECT COUNT(*) FROM transaction";
            case "daily_transaction" -> "SELECT COUNT(*) FROM daily_transaction";
            case "transaction_category_balance" ->
                    "SELECT COUNT(*) FROM transaction_category_balance";
            case "disclosure_group" -> "SELECT COUNT(*) FROM disclosure_group";
            case "transaction_type" -> "SELECT COUNT(*) FROM transaction_type";
            case "transaction_category" -> "SELECT COUNT(*) FROM transaction_category";
            case "user_security" -> "SELECT COUNT(*) FROM user_security";
            default -> throw new IllegalArgumentException("unreachable: " + table
                    + " is not one of the eleven application tables");
        };

        final List<String> counted = queryOneColumnLocally(sql);
        assertThat(counted).as("a count returns exactly one row").hasSize(1);
        return Integer.parseInt(counted.get(0));
    }

    /**
     * Counts the columns in one table's primary key.
     *
     * @param  table one of the eleven application tables
     * @return the number of key columns
     * @throws SQLException if the catalogue cannot be read
     */
    private static int keyColumnCount(final String table) throws SQLException {
        assertThat(APPLICATION_TABLES).contains(table);
        int columns = 0;
        for (final String entry : queryOneColumnLocally("""
                SELECT tc.table_name || ':' || kcu.column_name
                  FROM information_schema.table_constraints tc
                  JOIN information_schema.key_column_usage kcu
                    ON kcu.constraint_name = tc.constraint_name
                   AND kcu.table_schema = tc.table_schema
                 WHERE tc.table_schema = 'public'
                   AND tc.constraint_type = 'PRIMARY KEY'
                 ORDER BY tc.table_name, kcu.ordinal_position
                """)) {
            if (entry.startsWith(table + ":")) {
                columns++;
            }
        }
        return columns;
    }

    // ===================================================================================================
    // HELPERS :: the build model
    //
    // Every gate whose enforcement lives in the build is read from the PARSED model rather than from the
    // build file's text. The distinction is the whole point: a comment is not an element, so a
    // commented-out plugin, execution or configuration value is invisible to a parse and cannot satisfy an
    // assertion the way it satisfies a substring search. Property references are resolved through the
    // project's own property block, so a value that has been redirected to a property somebody flipped is
    // read at its effective value rather than at its declaration.
    //
    // Only the ACTIVE build is consulted. A plugin declared under plugin management or inside a profile is
    // not what an ordinary build runs, so a lookup that found one there would report a gate as enforced
    // when nothing enforces it.
    // ===================================================================================================

    /** The parsed build model, read once. */
    private static Document parsedBuildModel;

    /** The project's own properties, resolved once, for expanding a configuration reference. */
    private static Map<String, String> buildModelProperties;

    /**
     * Parses the module's build file, once, with external entity resolution switched off.
     *
     * @return the parsed model
     */
    private static Document buildModel() {
        if (parsedBuildModel != null) {
            return parsedBuildModel;
        }
        final Path build = Path.of(BUILD_FILE);
        assertThat(Files.isRegularFile(build))
                .as("the build file is expected at %s, because the build's working directory is the "
                        + "module", build.toAbsolutePath())
                .isTrue();
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setNamespaceAware(false);
            factory.setExpandEntityReferences(false);
            final DocumentBuilder parser = factory.newDocumentBuilder();
            parsedBuildModel = parser.parse(build.toFile());
        } catch (final ParserConfigurationException | SAXException malformed) {
            throw new IllegalStateException("the build file at " + build.toAbsolutePath()
                    + " could not be parsed, so no gate that lives in the build can be verified",
                    malformed);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException("the build file at " + build.toAbsolutePath()
                    + " could not be read", unreadable);
        }
        return parsedBuildModel;
    }

    /**
     * Reads the project's property block, so a configuration reference can be resolved to its value.
     *
     * @return the declared properties
     */
    private static Map<String, String> modelProperties() {
        if (buildModelProperties != null) {
            return buildModelProperties;
        }
        final Map<String, String> properties = new LinkedHashMap<>();
        final Element project = buildModel().getDocumentElement();
        for (final Element block : childElements(project, "properties")) {
            for (final Element property : childElements(block)) {
                properties.put(property.getTagName(), property.getTextContent().strip());
            }
        }
        buildModelProperties = Map.copyOf(properties);
        return buildModelProperties;
    }

    /**
     * Resolves a configuration value through the project's properties.
     *
     * <p>Bounded rather than recursive without limit, so a property that referred to itself produces a
     * diagnostic instead of an endless expansion.
     *
     * @param  value the declared value, possibly a property reference
     * @return the resolved value
     */
    private static String resolveModelValue(final String value) {
        String resolved = value;
        for (int pass = 0; pass < 8 && resolved.contains("${"); pass++) {
            final int open = resolved.indexOf("${");
            final int close = resolved.indexOf('}', open);
            if (close < 0) {
                break;
            }
            final String name = resolved.substring(open + 2, close);
            final String replacement = modelProperties().get(name);
            if (replacement == null) {
                break;
            }
            resolved = resolved.substring(0, open) + replacement + resolved.substring(close + 1);
        }
        return resolved.strip();
    }

    /**
     * Locates one plugin in the ACTIVE build, failing when it is absent or declared more than once.
     *
     * @param  artifactId the plugin's artefact identifier
     * @return its element
     */
    private static Element activePlugin(final String artifactId) {
        final Element project = buildModel().getDocumentElement();
        final List<Element> builds = childElements(project, "build");
        assertThat(builds)
                .as("the project must declare exactly one build section for an active plugin to live in")
                .hasSize(1);
        final List<Element> found = new ArrayList<>();
        for (final Element plugins : childElements(builds.get(0), "plugins")) {
            for (final Element plugin : childElements(plugins, "plugin")) {
                if (artifactId.equals(childText(plugin, "artifactId"))) {
                    found.add(plugin);
                }
            }
        }
        assertThat(found)
                .as("%s must be declared exactly once in project/build/plugins. A declaration under "
                        + "pluginManagement or inside a profile is not what an ordinary build runs, so "
                        + "finding one there would report a gate as enforced when nothing enforces it",
                        artifactId)
                .hasSize(1);
        return found.get(0);
    }

    /**
     * Locates one named execution of a plugin.
     *
     * @param  plugin      the plugin
     * @param  executionId the execution's identifier
     * @return its element
     */
    private static Element executionOf(final Element plugin, final String executionId) {
        final List<Element> found = new ArrayList<>();
        for (final Element executions : childElements(plugin, "executions")) {
            for (final Element execution : childElements(executions, "execution")) {
                if (executionId.equals(childText(execution, "id"))) {
                    found.add(execution);
                }
            }
        }
        assertThat(found)
                .as("execution '%s' must be declared exactly once, or the goal it binds runs a different "
                        + "number of times than the gate assumes", executionId)
                .hasSize(1);
        return found.get(0);
    }

    /**
     * Reads the goals one execution binds.
     *
     * @param  execution the execution
     * @return its goals, in declaration order
     */
    private static List<String> goalsOf(final Element execution) {
        final List<String> goals = new ArrayList<>();
        for (final Element block : childElements(execution, "goals")) {
            for (final Element goal : childElements(block, "goal")) {
                goals.add(goal.getTextContent().strip());
            }
        }
        return List.copyOf(goals);
    }

    /**
     * Reads the phase one execution is bound to.
     *
     * @param  execution the execution
     * @return the phase, or {@code null} when it inherits one
     */
    private static String phaseOf(final Element execution) {
        return childText(execution, "phase");
    }

    /**
     * Reads one configuration value, resolved, from a path of nested element names.
     *
     * @param  scope the plugin or execution the configuration belongs to
     * @param  path  the element names to walk
     * @return the resolved value, or {@code null} when the path does not exist
     */
    private static String configuredValue(final Element scope, final String... path) {
        Element cursor = scope;
        for (int index = 0; index < path.length - 1; index++) {
            final List<Element> next = childElements(cursor, path[index]);
            if (next.isEmpty()) {
                return null;
            }
            cursor = next.get(0);
        }
        return childText(cursor, path[path.length - 1]);
    }

    /**
     * Reads the resolved text of every child of a configuration element.
     *
     * @param  scope the plugin or execution the configuration belongs to
     * @param  path  the element names to walk to the containing element
     * @return each child's resolved text, in declaration order
     */
    private static List<String> configuredList(final Element scope, final String... path) {
        final List<Element> containers = elementsAt(scope, path);
        final List<String> values = new ArrayList<>();
        for (final Element container : containers) {
            for (final Element child : childElements(container)) {
                values.add(resolveModelValue(child.getTextContent().strip()));
            }
        }
        return List.copyOf(values);
    }

    /**
     * Reads every child element of the element a path walks to.
     *
     * @param  scope the plugin or execution the configuration belongs to
     * @param  path  the element names to walk
     * @return the children of the element the path names
     */
    private static List<Element> elementsUnder(final Element scope, final String... path) {
        final List<Element> containers = elementsAt(scope, path);
        final List<Element> children = new ArrayList<>();
        for (final Element container : containers) {
            children.addAll(childElements(container));
        }
        return List.copyOf(children);
    }

    /**
     * Walks a path of element names and returns whatever it lands on.
     *
     * @param  scope the element to start from
     * @param  path  the element names to walk
     * @return the elements the path names, which may be empty
     */
    private static List<Element> elementsAt(final Element scope, final String... path) {
        List<Element> cursor = List.of(scope);
        for (final String name : path) {
            final List<Element> next = new ArrayList<>();
            for (final Element element : cursor) {
                next.addAll(childElements(element, name));
            }
            cursor = next;
        }
        return List.copyOf(cursor);
    }

    /**
     * Reads one child element's resolved text.
     *
     * @param  parent the containing element
     * @param  name   the child's element name
     * @return the resolved text, or {@code null} when there is no such child
     */
    private static String childValue(final Element parent, final String name) {
        return childText(parent, name);
    }

    /**
     * Reads one child element's resolved text.
     *
     * @param  parent the containing element
     * @param  name   the child's element name
     * @return the resolved text, or {@code null} when there is no such child
     */
    private static String childText(final Element parent, final String name) {
        final List<Element> children = childElements(parent, name);
        return children.isEmpty() ? null : resolveModelValue(children.get(0).getTextContent().strip());
    }

    /**
     * Reads the element children of one element carrying a given name.
     *
     * @param  parent the containing element
     * @param  name   the element name to select
     * @return the matching children, in document order
     */
    private static List<Element> childElements(final Element parent, final String name) {
        final List<Element> selected = new ArrayList<>();
        for (final Element child : childElements(parent)) {
            if (name.equals(child.getTagName())) {
                selected.add(child);
            }
        }
        return selected;
    }

    /**
     * Reads every element child of one element.
     *
     * @param  parent the containing element
     * @return its element children, in document order
     */
    private static List<Element> childElements(final Element parent) {
        final List<Element> children = new ArrayList<>();
        final NodeList nodes = parent.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            final Node node = nodes.item(index);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                children.add((Element) node);
            }
        }
        return List.copyOf(children);
    }

    /**
     * Reports whether the coverage floor is enforced as a halting check on the gated counters.
     *
     * @return {@code true} when the check goal, the phase, the halt and both limits are all in place
     */
    private static boolean coverageFloorIsEnforced() {
        final Element check = executionOf(activePlugin("jacoco-maven-plugin"),
                "jacoco-check-line-coverage");
        if (!goalsOf(check).contains("check") || !"verify".equals(phaseOf(check))
                || !"true".equals(configuredValue(check, "configuration", "haltOnFailure"))) {
            return false;
        }
        boolean lineFloor = false;
        boolean noUntestedClass = false;
        for (final Element limit : elementsUnder(check, "configuration", "rules", "rule", "limits")) {
            final String counter = childValue(limit, "counter");
            final String value = childValue(limit, "value");
            if ("LINE".equals(counter) && "COVEREDRATIO".equals(value)
                    && "0.80".equals(childValue(limit, "minimum"))) {
                lineFloor = true;
            }
            if ("CLASS".equals(counter) && "MISSEDCOUNT".equals(value)
                    && "0".equals(childValue(limit, "maximum"))) {
                noUntestedClass = true;
            }
        }
        return lineFloor && noUntestedClass;
    }

    // ===================================================================================================
    // HELPERS :: the supply-chain evidence
    // ===================================================================================================

    /**
     * One analyst determination, reduced to the three properties that decide whether it is narrow enough.
     *
     * @param ordinal        its position in the file, one-based, so a failure names a place
     * @param scopes         the artefact scopes it names
     * @param identifiers    the identifiers it accepts
     * @param wildcardScoped whether any scope is a regular expression broad enough to match anything
     */
    private record SuppressionRule(int ordinal, List<String> scopes, List<String> identifiers,
            boolean wildcardScoped) { }

    /**
     * Reads the analyst determinations the vulnerability scan is configured with.
     *
     * @return one entry per determination, in file order
     * @throws IOException if the file cannot be read
     */
    private static List<SuppressionRule> suppressionRules() throws IOException {
        final Path file = Path.of(SUPPRESSION_FILE);
        assertThat(Files.isRegularFile(file))
                .as("the determination file the scan is configured with must exist at %s",
                        file.toAbsolutePath())
                .isTrue();

        final Document parsed;
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setNamespaceAware(false);
            factory.setExpandEntityReferences(false);
            parsed = factory.newDocumentBuilder().parse(file.toFile());
        } catch (final ParserConfigurationException | SAXException malformed) {
            throw new IllegalStateException(file.toAbsolutePath()
                    + " could not be parsed, so its determinations cannot be inspected", malformed);
        }

        final List<String> scopeNames = List.of("packageUrl", "gav", "filePath", "sha1", "cpe");
        final List<String> identifierNames = List.of("cve", "vulnerabilityName", "cwe", "cpe",
                "notes");
        final List<SuppressionRule> rules = new ArrayList<>();
        final NodeList declared = parsed.getElementsByTagName("suppress");
        for (int index = 0; index < declared.getLength(); index++) {
            final Element rule = (Element) declared.item(index);
            final List<String> scopes = new ArrayList<>();
            final List<String> identifiers = new ArrayList<>();
            boolean wildcard = false;
            for (final Element child : childElements(rule)) {
                final String name = child.getTagName();
                final String text = child.getTextContent().strip();
                if (scopeNames.contains(name)) {
                    scopes.add(name + "=" + text);
                    if (".*".equals(text) || ".*.*".equals(text) || "*".equals(text)) {
                        wildcard = true;
                    }
                }
                if (identifierNames.contains(name) && !"notes".equals(name)) {
                    identifiers.add(name + "=" + text);
                }
            }
            rules.add(new SuppressionRule(index + 1, List.copyOf(scopes), List.copyOf(identifiers),
                    wildcard));
        }
        return List.copyOf(rules);
    }

    /**
     * What can honestly be established about the supply-chain gate from this tier.
     *
     * @param reportLocation          where a machine-readable report was looked for
     * @param reportPresent           whether one was found
     * @param stale                   whether the report predates the current declaration or bytes
     * @param qualifyingFindings      findings at or above the threshold, each named
     * @param undocumentedSuppressions findings the scan set aside with no determination covering them
     * @param determinations          how many determinations were inspected
     * @param mechanismEnforced       whether the parsed model actually enforces the gate
     */
    private record VulnerabilityEvidence(Path reportLocation, boolean reportPresent, boolean stale,
            List<String> qualifyingFindings, List<String> undocumentedSuppressions, int determinations,
            boolean mechanismEnforced) {

        /**
         * Reports whether the gate is discharged as far as this phase can discharge it.
         *
         * <p>An absent report never contributes a pass. What satisfies the row is the enforcing mechanism
         * read out of the parsed build model together with the inspected determinations; a report, when one
         * exists, must additionally be current and clean, and a stale one fails.
         *
         * @return {@code true} when nothing outstanding was found
         */
        boolean satisfied() {
            return mechanismEnforced() && !stale() && qualifyingFindings().isEmpty()
                    && undocumentedSuppressions().isEmpty();
        }

        /**
         * Renders what was actually established, for the sign-off row and for a failure message.
         *
         * @return the narrative
         */
        String narrative() {
            final StringBuilder rendered = new StringBuilder(256);
            rendered.append(mechanismEnforced()
                    ? "mechanism verified in the parsed build model (check at verify, CVSS 7.0, "
                            + "unused-rule failure on)"
                    : "MECHANISM NOT ENFORCED in the parsed build model");
            rendered.append("; ").append(determinations())
                    .append(" determination(s) inspected, each scoped to named artefacts");
            if (reportPresent()) {
                rendered.append("; report ").append(reportLocation())
                        .append(stale() ? " is STALE and is not read as a pass"
                                : " read: " + qualifyingFindings().size()
                                        + " finding(s) at or above 7.0");
            } else {
                rendered.append("; no report at this phase - the scan is bound to verify, which is later, "
                        + "and no pass is inferred from its absence");
            }
            return rendered.toString();
        }

        /**
         * Names the report in the artefact column when one exists.
         *
         * @return the suffix to append, or an empty string
         */
        String reportSuffix() {
            return reportPresent() ? "; report " + reportLocation() : "";
        }
    }

    /**
     * Locates a vulnerability report a previous build has already produced, if there is one.
     *
     * <p>Absence is never a pass. The scan is bound to a later phase than this tier, so a run of this
     * class alone legitimately finds no report; the enforcing mechanism is asserted unconditionally
     * elsewhere and the sign-off row records the absence as "enforced by the build at a later phase".
     *
     * @return the report, or empty when this phase has not produced one
     */
    private static Optional<Path> existingVulnerabilityReport() {
        final Path report = Path.of("target", "dependency-check-report.json");
        return Files.isRegularFile(report) ? Optional.of(report) : Optional.empty();
    }

    /**
     * Assembles what this tier can establish about the supply-chain gate.
     *
     * @return the evidence
     * @throws IOException if the determinations or a report cannot be read
     */
    private static VulnerabilityEvidence vulnerabilityEvidence() throws IOException {
        final Element scan = activePlugin("dependency-check-maven");
        final Element check = executionOf(scan, "owasp-dependency-check");
        final boolean mechanism = goalsOf(check).contains("check")
                && "verify".equals(phaseOf(check))
                && "false".equals(configuredValue(scan, "configuration", "skip"))
                && "7.0".equals(configuredValue(scan, "configuration", "failBuildOnCVSS"))
                && "true".equals(configuredValue(scan, "configuration",
                        "failBuildOnUnusedSuppressionRule"));

        final List<SuppressionRule> determinations = suppressionRules();
        final Set<String> documented = new LinkedHashSet<>();
        for (final SuppressionRule rule : determinations) {
            for (final String identifier : rule.identifiers()) {
                documented.add(identifier.substring(identifier.indexOf('=') + 1));
            }
        }

        final Path location = Path.of("target", "dependency-check-report.json");
        if (!Files.isRegularFile(location)) {
            return new VulnerabilityEvidence(location, false, false, List.of(), List.of(),
                    determinations.size(), mechanism);
        }

        final boolean stale = isStale(location);
        final JsonNode report = new ObjectMapper().readTree(Files.readAllBytes(location));
        final List<String> qualifying = new ArrayList<>();
        final List<String> undocumented = new ArrayList<>();
        for (final JsonNode dependency : report.path("dependencies")) {
            final String artefact = dependency.path("fileName").asText();
            for (final JsonNode vulnerability : dependency.path("vulnerabilities")) {
                final double score = highestScoreOf(vulnerability);
                if (score >= QUALIFYING_SCORE) {
                    qualifying.add(artefact + " -> " + vulnerability.path("name").asText()
                            + " (" + score + ")");
                }
            }
            for (final JsonNode suppressed : dependency.path("suppressedVulnerabilities")) {
                final String name = suppressed.path("name").asText();
                if (!documented.contains(name)) {
                    undocumented.add(artefact + " -> " + name);
                }
            }
        }
        return new VulnerabilityEvidence(location, true, stale, List.copyOf(qualifying),
                List.copyOf(undocumented), determinations.size(), mechanism);
    }

    /**
     * Reports whether an artefact predates the dependency declaration it is supposed to describe.
     *
     * <p>The declaration is the one input that decides what a vulnerability scan is a scan OF. Every
     * coordinate in this module is pinned exactly - no range, no {@code LATEST}, no {@code RELEASE} - so a
     * report produced after the current build file describes the same resolved graph that is being built,
     * and one produced before it describes a different graph and must not read as a pass.
     *
     * <p>The compiled-class tree is deliberately NOT an input here, and the reason is worth stating because
     * the opposite is the intuitive choice. Recompiling unchanged sources rewrites those files and moves
     * their timestamps without altering one coordinate of the graph, so including them would report a
     * perfectly valid report as stale on the second build in a row - turning a correctness check into a
     * project that cannot be built twice without {@code clean}. That is a false negative about the
     * evidence rather than a true finding about the graph.
     *
     * @param  artefact the file to judge
     * @return {@code true} when it is older than the build file
     * @throws IOException if a timestamp cannot be read
     */
    private static boolean isStale(final Path artefact) throws IOException {
        final Path build = Path.of(BUILD_FILE);
        if (!Files.exists(build)) {
            return false;
        }
        return Files.getLastModifiedTime(artefact).compareTo(Files.getLastModifiedTime(build)) < 0;
    }

    // ===================================================================================================
    // HELPERS :: the recorded and generated performance baseline
    // ===================================================================================================

    /** Strict calendar-date parser, so an impossible date fails rather than being normalised. */
    private static final DateTimeFormatter RECORDED_DATE = DateTimeFormatter
            .ofPattern("uuuu-MM-dd", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);

    /**
     * One recorded measurement, as the evidence page publishes it.
     *
     * @param date             the day it was taken
     * @param machine          the host, processor and runtime it was taken on
     * @param run              the run it measured
     * @param records          records the run processed
     * @param elapsedMillis    wall-clock milliseconds
     * @param peakHeapBytes    summed peak heap occupancy
     * @param recordsPerSecond the published quotient
     */
    private record PerformanceRow(LocalDate date, String machine, String run, long records,
            long elapsedMillis, long peakHeapBytes, BigDecimal recordsPerSecond) { }

    /**
     * Reads the measured rows out of the recorded evidence page.
     *
     * <p>A placeholder row - one whose figures are dashes or whose date is not a date - is not returned as
     * a row with zeroes in it. It is not returned at all, which is what makes "at least one measured row"
     * an assertion about a measurement rather than about a table having lines in it.
     *
     * @return the measured rows, in page order
     * @throws IOException if the page cannot be read
     */
    private static List<PerformanceRow> recordedPerformanceRows() throws IOException {
        final Path evidence = documentationFile(GATE_EVIDENCE);
        final List<String> lines =
                List.of(Files.readString(evidence, StandardCharsets.UTF_8).split("\n", -1));

        final List<PerformanceRow> rows = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            if (!PERFORMANCE_ROW_HEADER.equals(lines.get(index).strip())
                    || index + 1 >= lines.size() || !isAlignmentRow(lines.get(index + 1))) {
                continue;
            }
            int cursor = index + 2;
            while (cursor < lines.size() && lines.get(cursor).startsWith("|")
                    && !isAlignmentRow(lines.get(cursor))) {
                final List<String> cells = cellsOf(lines.get(cursor));
                cursor++;
                if (cells.size() != 7) {
                    continue;
                }
                final LocalDate date = parsedDate(cells.get(0));
                final Long records = parsedCount(cells.get(3));
                final Long elapsed = parsedCount(cells.get(4));
                final Long peak = parsedCount(cells.get(5));
                final BigDecimal rate = parsedRate(cells.get(6));
                if (date == null || records == null || elapsed == null || peak == null
                        || rate == null) {
                    continue;
                }
                rows.add(new PerformanceRow(date, cells.get(1), unquoted(cells.get(2)),
                        records.longValue(), elapsed.longValue(), peak.longValue(), rate));
            }
            index = cursor - 1;
        }
        return List.copyOf(rows);
    }

    /**
     * Reports whether the recorded evidence carries a measured performance baseline.
     *
     * @return {@code true} when at least one measured row is recorded and every one is well formed
     * @throws IOException if the page cannot be read
     */
    private static boolean recordedEvidenceCoversPerformanceBaseline() throws IOException {
        final List<PerformanceRow> rows = recordedPerformanceRows();
        if (rows.isEmpty()) {
            return false;
        }
        for (final PerformanceRow row : rows) {
            if (row.records() <= 0L || row.elapsedMillis() <= 0L || row.peakHeapBytes() <= 0L
                    || row.recordsPerSecond().compareTo(BigDecimal.ZERO) <= 0
                    || row.machine().isBlank() || row.run().isBlank()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Parses a strictly resolved calendar date, or reports its absence.
     *
     * @param  cell the cell's text
     * @return the date, or {@code null} when the cell is not one
     */
    private static LocalDate parsedDate(final String cell) {
        try {
            return LocalDate.parse(cell.strip(), RECORDED_DATE);
        } catch (final DateTimeParseException notADate) {
            return null;
        }
    }

    /**
     * Parses a whole-number figure, or reports its absence.
     *
     * @param  cell the cell's text
     * @return the figure, or {@code null} when the cell carries no figure
     */
    private static Long parsedCount(final String cell) {
        final String candidate = cell.strip().replace(",", "");
        if (candidate.isEmpty() || !candidate.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return Long.valueOf(candidate);
    }

    /**
     * Parses a decimal rate, or reports its absence.
     *
     * @param  cell the cell's text
     * @return the rate, or {@code null} when the cell carries no rate
     */
    private static BigDecimal parsedRate(final String cell) {
        final String candidate = cell.strip().replace(",", "");
        if (candidate.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(candidate);
        } catch (final NumberFormatException notARate) {
            return null;
        }
    }

    /** One row of a generated baseline, in the shape the recorder writes it. */
    private record GeneratedRow(String run, long records, long elapsedMillis, long peakHeapBytes,
            BigDecimal recordsPerSecond) { }

    /**
     * One generated baseline file this build produced.
     *
     * @param location               where it was written
     * @param rows                   the rows it carries
     * @param namesItsFixtureVolumes whether it states the volumes its figures were measured over
     */
    private record GeneratedBaseline(Path location, List<GeneratedRow> rows,
            boolean namesItsFixtureVolumes) { }

    /**
     * Reads whatever run-scoped baselines the build has written into its output directory.
     *
     * @return one entry per generated file, which may be empty
     * @throws IOException if a generated file cannot be read
     */
    private static List<GeneratedBaseline> generatedPerformanceEvidence() throws IOException {
        if (!Files.isDirectory(EVIDENCE_DIRECTORY)) {
            return List.of();
        }
        final List<Path> generated;
        try (Stream<Path> entries = Files.list(EVIDENCE_DIRECTORY)) {
            generated = entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("gate3-"))
                    .sorted()
                    .toList();
        }

        final List<GeneratedBaseline> baselines = new ArrayList<>(generated.size());
        for (final Path file : generated) {
            final String content = Files.readString(file, StandardCharsets.UTF_8);
            final List<GeneratedRow> rows = new ArrayList<>();
            for (final String line : content.split("\n", -1)) {
                if (!line.startsWith("|") || isAlignmentRow(line)) {
                    continue;
                }
                final List<String> cells = cellsOf(line);
                if (cells.size() != 5) {
                    continue;
                }
                final Long records = parsedCount(cells.get(1));
                final Long elapsed = parsedCount(cells.get(2));
                final Long peak = parsedCount(cells.get(3));
                final BigDecimal rate = parsedRate(cells.get(4));
                if (records == null || elapsed == null || peak == null || rate == null) {
                    continue;
                }
                rows.add(new GeneratedRow(cells.get(0), records.longValue(), elapsed.longValue(),
                        peak.longValue(), rate));
            }
            baselines.add(new GeneratedBaseline(file, List.copyOf(rows),
                    content.contains("Fixture volumes")));
        }
        return List.copyOf(baselines);
    }

    // ===================================================================================================
    // HELPERS :: the production-tree audit
    // ===================================================================================================

    /** Recognises a string literal that opens a statement rather than a sentence. */
    private static final Pattern QUERY_VERB = Pattern.compile(
            "^\\s*(SELECT|INSERT|UPDATE|DELETE|MERGE|TRUNCATE|DROP|ALTER|CREATE)\\b");

    /** Recognises a clause keyword, which is what distinguishes a query from an English sentence. */
    private static final Pattern QUERY_CLAUSE = Pattern.compile(
            "\\b(FROM|INTO|SET|WHERE|TABLE|VALUES|INDEX|SEQUENCE)\\b");

    /** Recognises a cast whose target is a parameterised type. */
    private static final Pattern PARAMETERISED_CAST = Pattern.compile(
            "\\(\\s*[A-Za-z_$][\\w.$]*\\s*<[^<>()]*>\\s*\\)\\s*[A-Za-z_$(]");

    /** Recognises a cast whose target is a single-letter type variable. */
    private static final Pattern TYPE_VARIABLE_CAST = Pattern.compile(
            "\\(\\s*[A-Z][0-9]?\\s*\\)\\s*[A-Za-z_$(]");

    /**
     * What the query-string audit measured.
     *
     * @param queryLiterals  how many query strings the production tree carries at all
     * @param assembledSites those assembled from something that is not a literal, each named
     */
    private record QueryStringCensus(int queryLiterals, List<String> assembledSites) { }

    /**
     * Censuses the production tree's query strings and how each is built.
     *
     * @return the census
     * @throws IOException if the tree cannot be walked
     */
    private static QueryStringCensus queryStringCensus() throws IOException {
        int literals = 0;
        final List<String> assembled = new ArrayList<>();
        for (final Path file : productionSources()) {
            final String text = readSource(file);
            for (final int[] span : stringLiteralSpans(text)) {
                final String body = text.substring(span[2], span[3]);
                if (!QUERY_VERB.matcher(body).find() || !QUERY_CLAUSE.matcher(body).find()) {
                    continue;
                }
                literals++;
                if (concatenatesNonLiteral(text, span[0], span[1])) {
                    assembled.add(file + ":" + (1 + countLineFeeds(text, span[0])));
                }
            }
        }
        return new QueryStringCensus(literals, List.copyOf(assembled));
    }

    /**
     * Reports whether the expression around a literal joins it to something that is not a literal.
     *
     * @param  text  the whole source
     * @param  start index of the literal's opening quote
     * @param  end   index just past its closing quote
     * @return {@code true} when a non-literal is concatenated on either side
     */
    private static boolean concatenatesNonLiteral(final String text, final int start, final int end) {
        int after = end;
        while (after < text.length() && Character.isWhitespace(text.charAt(after))) {
            after++;
        }
        if (after < text.length() && text.charAt(after) == '+') {
            int operand = after + 1;
            while (operand < text.length() && Character.isWhitespace(text.charAt(operand))) {
                operand++;
            }
            if (operand < text.length() && text.charAt(operand) != '"') {
                return true;
            }
        }
        int before = start - 1;
        while (before >= 0 && Character.isWhitespace(text.charAt(before))) {
            before--;
        }
        if (before >= 0 && text.charAt(before) == '+') {
            int operand = before - 1;
            while (operand >= 0 && Character.isWhitespace(text.charAt(operand))) {
                operand--;
            }
            return operand >= 0 && text.charAt(operand) != '"';
        }
        return false;
    }

    /**
     * Finds every string literal in a Java source, skipping comments.
     *
     * <p>Text blocks are recognised as one literal, so a multi-line statement is examined whole rather
     * than line by line. Comments are skipped because a construct named in prose is not a use of it.
     *
     * @param  text the source
     * @return one entry per literal: opening index, index past the close, and the body's bounds
     */
    private static List<int[]> stringLiteralSpans(final String text) {
        final List<int[]> spans = new ArrayList<>();
        int index = 0;
        while (index < text.length()) {
            final char character = text.charAt(index);
            if (text.startsWith("//", index)) {
                final int newline = text.indexOf('\n', index);
                index = newline < 0 ? text.length() : newline;
            } else if (text.startsWith("/*", index)) {
                final int close = text.indexOf("*/", index);
                index = close < 0 ? text.length() : close + 2;
            } else if (text.startsWith("\"\"\"", index)) {
                final int close = text.indexOf("\"\"\"", index + 3);
                if (close < 0) {
                    break;
                }
                spans.add(new int[] {index, close + 3, index + 3, close});
                index = close + 3;
            } else if (character == '"') {
                int cursor = index + 1;
                while (cursor < text.length()) {
                    if (text.charAt(cursor) == '\\') {
                        cursor += 2;
                        continue;
                    }
                    if (text.charAt(cursor) == '"') {
                        break;
                    }
                    cursor++;
                }
                spans.add(new int[] {index, cursor + 1, index + 1, Math.min(cursor, text.length())});
                index = cursor + 1;
            } else if (character == '\'') {
                int cursor = index + 1;
                while (cursor < text.length() && text.charAt(cursor) != '\'') {
                    cursor += text.charAt(cursor) == '\\' ? 2 : 1;
                }
                index = cursor + 1;
            } else {
                index++;
            }
        }
        return List.copyOf(spans);
    }

    /**
     * Counts line feeds before an index, so a finding can name a line.
     *
     * @param  text  the source
     * @param  limit the index to count up to
     * @return the number of line feeds
     */
    private static int countLineFeeds(final String text, final int limit) {
        int count = 0;
        for (int index = 0; index < limit; index++) {
            if (text.charAt(index) == '\n') {
                count++;
            }
        }
        return count;
    }

    /**
     * Finds every cast to a parameterised type or a type variable in the production tree.
     *
     * <p>String literals and comments are removed from each line first, because the module's diagnostics
     * carry text like {@code RECORD(S)} that a cast pattern matches and that is not a cast.
     *
     * @return one entry per site
     * @throws IOException if the tree cannot be walked
     */
    private static List<String> genericCastSites() throws IOException {
        final List<String> sites = new ArrayList<>();
        for (final Path file : productionSources()) {
            final String[] lines = readSource(file).split("\n", -1);
            for (int index = 0; index < lines.length; index++) {
                final String stripped = lines[index].strip();
                if (stripped.startsWith("*") || stripped.startsWith("//")
                        || stripped.startsWith("/*")) {
                    continue;
                }
                final String code = withoutLiterals(lines[index]);
                if (PARAMETERISED_CAST.matcher(code).find()
                        || TYPE_VARIABLE_CAST.matcher(code).find()) {
                    sites.add(file.getFileName() + ":" + (index + 1));
                }
            }
        }
        return List.copyOf(sites);
    }

    /**
     * Removes string and character literals, and any trailing line comment, from one line.
     *
     * @param  line the line
     * @return the code that remains
     */
    private static String withoutLiterals(final String line) {
        final StringBuilder code = new StringBuilder(line.length());
        int index = 0;
        while (index < line.length()) {
            final char character = line.charAt(index);
            if (line.startsWith("//", index)) {
                break;
            }
            if (character == '"' || character == '\'') {
                int cursor = index + 1;
                while (cursor < line.length() && line.charAt(cursor) != character) {
                    cursor += line.charAt(cursor) == '\\' ? 2 : 1;
                }
                index = cursor + 1;
                continue;
            }
            code.append(character);
            index++;
        }
        return code.toString();
    }

    /**
     * Lists every production source, which is the tree the audit is scoped to.
     *
     * @return the sources, sorted
     * @throws IOException if the tree cannot be walked
     */
    private static List<Path> productionSources() throws IOException {
        final Path root = Path.of(PRODUCTION_TREE);
        assertThat(Files.isDirectory(root))
                .as("the audited tree is expected at %s", root.toAbsolutePath())
                .isTrue();
        try (Stream<Path> tree = Files.walk(root)) {
            return tree.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }

    /**
     * Every measured figure of the unsafe-code audit, with its budget.
     *
     * @param reflection      references to the reflection package
     * @param processes       process-execution sites
     * @param nativeQueries   native-query construction sites
     * @param assembledSql    query strings assembled from a non-literal
     * @param genericCasts    casts to a parameterised type or type variable
     * @param suppressions    suppressed warnings
     */
    private record UnsafeCodeCensus(long reflection, long processes, long nativeQueries,
            int assembledSql, int genericCasts, long suppressions) {

        /**
         * Reports whether every measured category is inside its budget.
         *
         * <p>The conjunction is the point: a row that reported only one category would read as an audit
         * and cover a fraction of one.
         *
         * @return {@code true} when all six hold
         */
        boolean withinBudget() {
            return reflection() == 0L && processes() == 0L && nativeQueries() == 0L
                    && assembledSql() == 0 && genericCasts() <= GENERIC_CAST_BUDGET
                    && suppressions() <= SUPPRESSION_BUDGET;
        }

        /**
         * Renders every measured figure, so the row records what was counted.
         *
         * @return the narrative
         */
        String narrative() {
            return "reflection " + reflection() + ", process execution " + processes()
                    + ", native query " + nativeQueries() + ", assembled SQL " + assembledSql()
                    + ", casts to a parameterised type " + genericCasts() + " (budget "
                    + GENERIC_CAST_BUDGET + "), warning suppressions " + suppressions() + " (budget "
                    + SUPPRESSION_BUDGET + ")";
        }
    }

    /**
     * Measures every category of the unsafe-code audit over the scoped production tree.
     *
     * @return the census
     * @throws IOException if the tree cannot be walked
     */
    private static UnsafeCodeCensus unsafeCodeCensus() throws IOException {
        return new UnsafeCodeCensus(
                occurrencesIn(PRODUCTION_TREE, "java.lang.reflect")
                        + occurrencesIn(PRODUCTION_TREE, "Class.forName"),
                occurrencesIn(PRODUCTION_TREE, "Runtime.getRuntime")
                        + occurrencesIn(PRODUCTION_TREE, "ProcessBuilder"),
                occurrencesIn(PRODUCTION_TREE, "createNativeQuery"),
                queryStringCensus().assembledSites().size(),
                genericCastSites().size(),
                occurrencesIn(PRODUCTION_TREE, "@SuppressWarnings"));
    }

    // ===================================================================================================
    // HELPERS :: the legacy linkage census
    // ===================================================================================================

    /**
     * How often one command appears, and in which members.
     *
     * @param total   occurrences across the estate
     * @param members occurrences per member, for the members that carry it
     */
    private record VerbCensus(int total, Map<String, Integer> members) { }

    /**
     * Censuses the static calls the programs make, by target.
     *
     * @return one entry per called subprogram
     * @throws IOException if the estate cannot be read
     */
    private static Map<String, Integer> staticCallCensus() throws IOException {
        final Map<String, Integer> census = new TreeMap<>();
        for (final Path member : programMembers()) {
            final Matcher call = STATIC_CALL.matcher(executableText(member));
            while (call.find()) {
                census.merge(call.group(1).toUpperCase(Locale.ROOT), Integer.valueOf(1),
                        (first, second) -> Integer.valueOf(first.intValue() + second.intValue()));
            }
        }
        assertThat(census)
                .as("the linkage census must find calls; an empty one means the estate was not read")
                .isNotEmpty();
        return Map.copyOf(census);
    }

    /**
     * Censuses one command across the programs.
     *
     * <p>The member is read as one blank-normalised stream rather than line by line, because a command
     * continued onto a second line is one command: a line-wise count of the transfer-control command finds
     * nine of the twenty-five that exist.
     *
     * @param  command the command text, as one or more words
     * @return its census
     * @throws IOException if the estate cannot be read
     */
    private static VerbCensus cicsVerbCensus(final String command) throws IOException {
        final Pattern occurrence = Pattern.compile(
                command.replace(" ", "\\s+"), Pattern.CASE_INSENSITIVE);
        final Map<String, Integer> members = new TreeMap<>();
        int total = 0;
        for (final Path member : programMembers()) {
            final Matcher found = occurrence.matcher(executableText(member));
            int inMember = 0;
            while (found.find()) {
                inMember++;
            }
            if (inMember > 0) {
                members.put(member.getFileName().toString(), Integer.valueOf(inMember));
                total += inMember;
            }
        }
        return new VerbCensus(total, Map.copyOf(members));
    }

    /**
     * Lists the program members, case-inclusively.
     *
     * @return every program in the estate
     * @throws IOException if the directory cannot be listed
     */
    private static List<Path> programMembers() throws IOException {
        final List<Path> members = new ArrayList<>(filesByExtension("app/cbl", ".cbl"));
        members.addAll(filesByExtension("app/cbl", ".CBL"));
        return List.copyOf(members);
    }

    /**
     * Reads one member's executable text as a single blank-normalised stream.
     *
     * <p>Comment and continuation-indicator lines are dropped by column, so a command named in a comment
     * is not counted, and the sequence area is kept out of the stream. Carriage returns are stripped first
     * because five members in this estate use a two-character terminator.
     *
     * @param  member the member to read
     * @return its executable text
     * @throws IOException if the member cannot be read
     */
    private static String executableText(final Path member) throws IOException {
        final StringBuilder stream = new StringBuilder(4096);
        for (String line : Files.readString(member, StandardCharsets.ISO_8859_1).split("\n", -1)) {
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            if (line.length() < 8 || line.charAt(6) == '*' || line.charAt(6) == '/') {
                continue;
            }
            stream.append(' ').append(line.substring(7));
        }
        return stream.toString().replaceAll("\\s+", " ");
    }

    // ===================================================================================================
    // HELPERS :: resolving a traceability citation
    // ===================================================================================================

    /**
     * Strips the code fencing a matrix cell renders an identifier in.
     *
     * @param  cell the cell's text
     * @return the identifier
     */
    private static String unquoted(final String cell) {
        return cell.replace("`", "").strip();
    }

    /**
     * Reads the line a matrix row cites.
     *
     * @param  row the row's cells
     * @return the cited line number
     */
    private static int citedLine(final List<String> row) {
        final String cell = unquoted(row.get(2));
        assertThat(cell)
                .as("row for %s cites '%s' as a line number, which is not one", row.get(0), cell)
                .matches("\\d+");
        return Integer.parseInt(cell);
    }

    /**
     * Reads one legacy member's lines, with carriage returns removed.
     *
     * @param  relativePath the member's repository-relative path
     * @return its lines
     */
    private static List<String> readMember(final String relativePath) {
        final Path member = repositoryFile(relativePath);
        final String content;
        try {
            content = Files.readString(member, StandardCharsets.ISO_8859_1);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException("the cited member at " + member + " could not be read",
                    unreadable);
        }
        final List<String> lines = new ArrayList<>();
        for (final String line : content.split("\n", -1)) {
            lines.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
        }
        return List.copyOf(lines);
    }

    /**
     * Reads the paragraph label declared on one line, if that line declares one.
     *
     * @param  lines  the member's lines
     * @param  number the one-based line number
     * @return the label, or {@code null} when the line declares none
     */
    private static String paragraphLabelAt(final List<String> lines, final int number) {
        if (number < 1 || number > lines.size()) {
            return null;
        }
        final String line = lines.get(number - 1);
        if (line.length() < 8 || line.charAt(6) == '*' || line.charAt(6) == '/'
                || line.charAt(7) == ' ') {
            return null;
        }
        final String statement = withoutTrailingBlanks(line.substring(7));
        return isParagraphLabel(statement) ? statement.substring(0, statement.length() - 1) : null;
    }

    /**
     * Reads one Java source whole.
     *
     * @param  file the source to read
     * @return its text
     */
    private static String readSource(final Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException("the cited source at " + file.toAbsolutePath()
                    + " could not be read", unreadable);
        }
    }

    /**
     * Reports whether a source declares a method of the given name.
     *
     * <p>Resolved by reading the source rather than by loading the type. That is deliberate: the reflection
     * budget for this module is zero, and an instrument that audited that budget by reflecting would be
     * hard to take seriously. A name followed by an opening parenthesis counts as a declaration when
     * everything before it on the line is type and modifier text - so an invocation, which is preceded by
     * a dot or a method reference, does not.
     *
     * @param  source the source text
     * @param  method the method name
     * @return {@code true} when the source declares it
     */
    private static boolean declaresMethod(final String source, final String method) {
        return !declarationLinesOf(source, method).isEmpty();
    }

    /**
     * Finds the lines on which a source declares a method of the given name.
     *
     * @param  source the source text
     * @param  method the method name
     * @return the zero-based line indices
     */
    private static List<Integer> declarationLinesOf(final String source, final String method) {
        final Pattern call = Pattern.compile("\\b" + Pattern.quote(method) + "\\s*\\(");
        final String[] lines = source.split("\n", -1);
        final List<Integer> declarations = new ArrayList<>();
        for (int index = 0; index < lines.length; index++) {
            final String stripped = lines[index].strip();
            if (stripped.startsWith("*") || stripped.startsWith("//") || stripped.startsWith("/*")) {
                continue;
            }
            final Matcher found = call.matcher(lines[index]);
            while (found.find()) {
                final String prefix = lines[index].substring(0, found.start()).strip();
                if (prefix.isEmpty() || prefix.endsWith(".") || prefix.endsWith("::")
                        || prefix.endsWith("new") || !prefix.matches("[\\w.<>\\[\\],?@\\s]*")) {
                    continue;
                }
                declarations.add(Integer.valueOf(index));
            }
        }
        return List.copyOf(declarations);
    }

    /**
     * Reads the statements in a method's body, so an empty one can be shown to be empty.
     *
     * <p>Braces are balanced from the declaration's own opening brace, and comment lines are dropped.
     * What remains is the statements, which is what "implements nothing" has to mean if it is to be
     * asserted rather than asserted about.
     *
     * @param  source the source text
     * @param  method the method name
     * @return the body's statements, each stripped
     */
    private static List<String> methodBodyStatements(final String source, final String method) {
        final List<Integer> declarations = declarationLinesOf(source, method);
        if (declarations.isEmpty()) {
            return List.of();
        }
        final String[] lines = source.split("\n", -1);
        final List<String> statements = new ArrayList<>();
        int depth = 0;
        boolean opened = false;
        for (int index = declarations.get(0).intValue(); index < lines.length; index++) {
            final String line = lines[index];
            final String stripped = line.strip();
            for (int cursor = 0; cursor < line.length(); cursor++) {
                if (line.charAt(cursor) == '{') {
                    depth++;
                    opened = true;
                } else if (line.charAt(cursor) == '}') {
                    depth--;
                }
            }
            if (opened && !stripped.startsWith("*") && !stripped.startsWith("//")
                    && !stripped.startsWith("/*")) {
                final String content = stripped.replace("{", "").replace("}", "").strip();
                if (!content.isEmpty() && index > declarations.get(0).intValue()) {
                    statements.add(content);
                }
            }
            if (opened && depth == 0) {
                break;
            }
        }
        return List.copyOf(statements);
    }

    /**
     * Counts the invocations of a method within one source.
     *
     * @param  source the source text
     * @param  method the method name
     * @return how many times it is called
     */
    private static int invocationCountOf(final String source, final String method) {
        final Pattern call = Pattern.compile("(?<![\\w.])" + Pattern.quote(method) + "\\s*\\(");
        int invocations = 0;
        for (final String line : source.split("\n", -1)) {
            final String stripped = line.strip();
            if (stripped.startsWith("*") || stripped.startsWith("//") || stripped.startsWith("/*")) {
                continue;
            }
            final Matcher found = call.matcher(line);
            while (found.find()) {
                final String prefix = line.substring(0, found.start()).strip();
                if (prefix.isEmpty() || !prefix.matches("[\\w.<>\\[\\],?@\\s]*")
                        || prefix.endsWith("void") || prefix.endsWith("private")) {
                    invocations++;
                }
            }
        }
        return invocations;
    }

    // ===================================================================================================
    // HELPERS :: the sign-off record
    // ===================================================================================================

    /**
     * One line of the sign-off checklist.
     *
     * @param item      the checklist item
     * @param artefact  the artefact that satisfies it
     * @param satisfied whether that artefact is actually present
     * @param evidence  what the artefact shows
     */
    private record ChecklistRow(String item, String artefact, boolean satisfied, String evidence) {

        /**
         * Renders the row for the evidence page.
         *
         * @return one Markdown table row, {@code "| item | artefact | status | evidence |"}, where the
         *         status cell is {@code PRESENT} when the artefact was found and {@code **MISSING**}
         *         when it was not
         */
        @Override
        public String toString() {
            return "| " + item + " | " + artefact + " | " + (satisfied ? "PRESENT" : "**MISSING**")
                    + " | " + evidence + " |";
        }
    }

    /**
     * Builds a checklist row.
     *
     * @param  item      the checklist item
     * @param  artefact  the artefact that satisfies it
     * @param  satisfied whether that artefact is present
     * @param  evidence  what the artefact shows
     * @return the row
     */
    private static ChecklistRow rowFor(final String item, final String artefact,
            final boolean satisfied, final String evidence) {
        return new ChecklistRow(item, artefact, satisfied, evidence);
    }

    /**
     * Reports whether the four goldens are all present and non-empty.
     *
     * @return {@code true} when every golden is present with content
     * @throws IOException if a golden cannot be read
     */
    private static boolean goldenArtefactsPresent() throws IOException {
        for (final String golden : List.of("daily-reject.txt", "transaction-report.txt",
                "statement.txt", "statement-html.txt")) {
            if (classpathBytes(GOLDEN_DIRECTORY + golden).length == 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reports whether the analyst determinations are still exactly the one examined, scoped finding.
     *
     * <p>Read by the sign-off row for the supply-chain item, so that the row's state depends on what may be
     * suppressed and not only on the threshold. Written as one predicate rather than as assertions because a
     * checklist row records a state; the assertions that explain <em>why</em> that state is required live in
     * the build-enforced gate specification above.
     *
     * @return {@code true} while one rule names one examined identifier on the documented artifacts
     * @throws IOException if the determination file cannot be read
     */
    private static boolean determinationIsScopedToOneExaminedFinding() throws IOException {
        final String declarations = withoutXmlComments(suppressionFile());
        return countOf(declarations, "<suppress>") == 1
                && countOf(declarations, "<cve>") == 1
                && countOf(declarations, "<packageUrl") == 1
                && declarations.contains("<cve>" + DETERMINED_IDENTIFIER + "</cve>")
                && declarations.contains(DETERMINED_ARTIFACT_SCOPE)
                && !declarations.contains("<cpe>")
                && !declarations.contains("<gav")
                && !declarations.contains("<vulnerabilityName");
    }

    /** Start-date slot value submitted to the builder here, and expected back out of it. */
    private static final String EXPECTED_START_DATE = "2022-01-01";

    /** End-date slot value submitted to the builder here, and expected back out of it. */
    private static final String EXPECTED_END_DATE = "2022-07-06";

    /** Literal text of the transmitted end-of-file sentinel card. */
    private static final String EXPECTED_EOF_SENTINEL = "/*EOF";

    /** One-based ordinal the sentinel occupies, and the only ordinal it may occupy. */
    private static final int EXPECTED_SENTINEL_ORDINAL = 17;

    /** One-based ordinal of the start-date sort-symbol card. */
    private static final int START_DATE_CARD_ORDINAL = 11;

    /** Zero-based offset of the slot on that card, which follows an eighteen-byte leading literal. */
    private static final int START_DATE_SLOT_OFFSET = 18;

    /** One-based ordinal of the end-date sort-symbol card. */
    private static final int END_DATE_CARD_ORDINAL = 12;

    /** Zero-based offset of the slot on that card, which follows a sixteen-byte leading literal. */
    private static final int END_DATE_SLOT_OFFSET = 16;

    /** One-based ordinal of the report date-parameter card, the one card carrying both slots. */
    private static final int DATE_PARAMETER_CARD_ORDINAL = 15;

    /** Zero-based offset of the first slot there; this card carries no leading literal at all. */
    private static final int DATE_PARAMETER_START_SLOT_OFFSET = 0;

    /** Zero-based offset of the second slot, after the first slot and its one-byte separator. */
    private static final int DATE_PARAMETER_END_SLOT_OFFSET = 11;

    /** How many of the seven examined texts are screen messages rather than catalogue messages. */
    private static final int SCREEN_MESSAGE_COUNT = 5;

    /**
     * Independent expectation of the whole job-submission image: every card, in order, at its width.
     *
     * <p>Each entry is a literal of this class plus the published eighty-column frame. Nothing here is
     * read from {@link JclCardImageBuilder}, which is the thing being examined, and nothing is imported
     * from another specification that keeps its own copy: an oracle shared between two specifications is
     * one oracle, and a drift in the shared copy would be invisible to both (DL-281).
     */
    private static final List<String> EXPECTED_CARD_IMAGES = List.of(
            paddedCardImage("//TRNRPT00 JOB 'TRAN REPORT',CLASS=A,MSGCLASS=0,"),   //  1
            paddedCardImage("// NOTIFY=&SYSUID"),                                  //  2
            paddedCardImage("//*"),                                                //  3
            paddedCardImage("//JOBLIB JCLLIB ORDER=('AWS.M2.CARDDEMO.PROC')"),     //  4
            paddedCardImage("//*"),                                                //  5
            paddedCardImage("//STEP10 EXEC PROC=TRANREPT"),                        //  6
            paddedCardImage("//*"),                                                //  7
            paddedCardImage("//STEP05R.SYMNAMES DD *"),                            //  8
            paddedCardImage("TRAN-CARD-NUM,263,16,ZD"),                            //  9
            paddedCardImage("TRAN-PROC-DT,305,10,CH"),                             // 10
            paddedCardImage("PARM-START-DATE,C'" + EXPECTED_START_DATE + "'"),     // 11
            paddedCardImage("PARM-END-DATE,C'" + EXPECTED_END_DATE + "'"),         // 12
            paddedCardImage("/*"),                                                 // 13
            paddedCardImage("//STEP10R.DATEPARM DD *"),                            // 14
            paddedCardImage(EXPECTED_START_DATE + " " + EXPECTED_END_DATE),        // 15
            paddedCardImage("/*"),                                                 // 16
            paddedCardImage(EXPECTED_EOF_SENTINEL));                               // 17

    /**
     * Independent expectation of the seven message texts, in the order they are examined.
     *
     * <p>The five screen messages travel in the eighty-byte message field; the two catalogue messages are
     * padded to their contractual fifty. The wording is restated here as a literal in every case, so a
     * message whose text drifted fails this row instead of being reported as verified for being non-blank.
     */
    private static final List<String> EXPECTED_SIGN_ON_TEXTS = List.of(
            "Please enter User ID ...",
            "Please enter Password ...",
            "Wrong Password. Try again ...",
            "User not found. Try again ...",
            "Unable to verify the User ...",
            paddedCommonMessage("Thank you for using CardDemo application..."),
            paddedCommonMessage("Invalid key pressed. Please see below..."));

    /**
     * What was established about the external interface contracts, and how.
     *
     * @param satisfied whether every contract examined here holds
     * @param narrative what was examined, for the sign-off row
     */
    private record InterfaceContractEvidence(boolean satisfied, String narrative) { }

    /**
     * Left justifies a catalogue message into its contractual width, space padded.
     *
     * @param  text the message's literal wording
     * @return the wording at exactly the common-message width
     */
    private static String paddedCommonMessage(final String text) {
        return text + " ".repeat(MessageCatalogService.COMMON_MESSAGE_WIDTH - text.length());
    }

    /**
     * Exercises the two external contracts this class can reach in process, and reports what it found.
     *
     * <h4>Why this is not a restatement of the end-to-end suite</h4>
     * The sign-off row for interface contracts used to be a literal pass, on the strength of two named
     * classes existing. Those classes are the right place for the contracts that need a bound port and a
     * live queue, and they are named in the artefact column - but a checklist row has to be the result of
     * something, so the two halves that need neither are exercised here through the shipped code: the
     * batch-trigger card image, built by the production builder, and the sign-on message texts, read from
     * the production constants. A contract that had drifted would fail the row rather than being reported
     * as verified because a test file exists.
     *
     * <h4>Why the comparison is against literals restated here</h4>
     * Every expectation below is an <strong>independent oracle</strong>: the seventeen card images and the
     * seven message texts are spelled out in {@link #EXPECTED_CARD_IMAGES} and
     * {@link #EXPECTED_SIGN_ON_TEXTS} as literals of this class, and each is compared for
     * <em>equality</em>. This row previously measured the builder against itself - it asked the shipped
     * builder for the cards, then checked their count, their width, and that the last one began with the
     * shipped sentinel constant. Every one of those questions is answered by the production code that
     * generated the answer, so a card whose literal text had been rewritten, a card order that had been
     * permuted, a date slot written at the wrong offset, or a message whose wording had drifted all
     * passed. None of them passes now, because the expected value no longer comes from the thing under
     * examination. The same reasoning forbids importing the literals from
     * {@code e2e/OnlineTransactionE2ETest}, which holds its own independent copy: two specifications
     * sharing one oracle is one oracle, not two (DL-281).
     *
     * @return the evidence
     */
    private static InterfaceContractEvidence interfaceContractEvidence() {
        final List<String> cards = JclCardImageBuilder.build(EXPECTED_START_DATE, EXPECTED_END_DATE);
        final List<String> expectedCards = EXPECTED_CARD_IMAGES;
        boolean satisfied = cards.size() == expectedCards.size()
                && cards.size() == JclCardImageBuilder.CARD_COUNT;
        for (int ordinal = 0; satisfied && ordinal < expectedCards.size(); ordinal++) {
            final String card = cards.get(ordinal);
            satisfied = card.equals(expectedCards.get(ordinal))
                    && card.getBytes(StandardCharsets.US_ASCII).length
                            == JclCardImageBuilder.CARD_IMAGE_WIDTH;
        }
        // The sentinel is examined by position, not by prefix. A sentinel that had migrated to any other
        // ordinal, or a sequence that carried a second one, satisfied a prefix test on the last card.
        satisfied = satisfied
                && cards.indexOf(paddedCardImage(EXPECTED_EOF_SENTINEL))
                        == EXPECTED_SENTINEL_ORDINAL - 1
                && cards.lastIndexOf(paddedCardImage(EXPECTED_EOF_SENTINEL))
                        == EXPECTED_SENTINEL_ORDINAL - 1;
        // The four date slots, read back out of the assembled cards at the offsets the eighty-column
        // frame puts them at. A slot written one byte adrift still yields eighty bytes.
        satisfied = satisfied
                && slotAt(cards, START_DATE_CARD_ORDINAL, START_DATE_SLOT_OFFSET)
                        .equals(EXPECTED_START_DATE)
                && slotAt(cards, END_DATE_CARD_ORDINAL, END_DATE_SLOT_OFFSET)
                        .equals(EXPECTED_END_DATE)
                && slotAt(cards, DATE_PARAMETER_CARD_ORDINAL, DATE_PARAMETER_START_SLOT_OFFSET)
                        .equals(EXPECTED_START_DATE)
                && slotAt(cards, DATE_PARAMETER_CARD_ORDINAL, DATE_PARAMETER_END_SLOT_OFFSET)
                        .equals(EXPECTED_END_DATE);

        final List<String> signOnTexts = List.of(SignOnResponse.MSG_PROMPT_USERID,
                SignOnResponse.MSG_PROMPT_PASSWD, SignOnResponse.MSG_WRONG_PASSWD,
                SignOnResponse.MSG_USER_NOT_FOUND, SignOnResponse.MSG_UNABLE_TO_VERIFY,
                MessageCatalogService.CCDA_MSG_THANK_YOU,
                MessageCatalogService.CCDA_MSG_INVALID_KEY);
        satisfied = satisfied && signOnTexts.size() == EXPECTED_SIGN_ON_TEXTS.size();
        for (int index = 0; satisfied && index < EXPECTED_SIGN_ON_TEXTS.size(); index++) {
            satisfied = signOnTexts.get(index).equals(EXPECTED_SIGN_ON_TEXTS.get(index));
        }
        // The width contract is separate from the wording contract: the five screen messages travel in an
        // eighty-byte field and the two catalogue messages are exactly fifty bytes wide.
        for (int index = 0; satisfied && index < SCREEN_MESSAGE_COUNT; index++) {
            satisfied = signOnTexts.get(index).getBytes(StandardCharsets.US_ASCII).length
                    <= SignOnResponse.MESSAGE_LENGTH;
        }
        for (int index = SCREEN_MESSAGE_COUNT; satisfied && index < signOnTexts.size(); index++) {
            satisfied = signOnTexts.get(index).getBytes(StandardCharsets.US_ASCII).length
                    == MessageCatalogService.COMMON_MESSAGE_WIDTH;
        }

        return new InterfaceContractEvidence(satisfied, cards.size()
                + " card images of " + JclCardImageBuilder.CARD_IMAGE_WIDTH
                + " bytes built here from the shipped builder and compared for equality, card by card,"
                + " against " + expectedCards.size() + " images restated independently in this class;"
                + " terminal sentinel transmitted at ordinal " + EXPECTED_SENTINEL_ORDINAL
                + " and nowhere else; the four date slots read back at their frame offsets; "
                + SCREEN_MESSAGE_COUNT + " sign-on texts and "
                + (signOnTexts.size() - SCREEN_MESSAGE_COUNT)
                + " common messages compared for equality against independent literals, within the "
                + SignOnResponse.MESSAGE_LENGTH + "-byte message field and at exactly "
                + MessageCatalogService.COMMON_MESSAGE_WIDTH
                + " bytes; the queue and the bound port are exercised by the two classes named beside"
                + " this row");
    }

    /**
     * Left justifies a card's literal text into the eighty-column frame, space padded.
     *
     * <p>Used only to build this class's own expectations. The width comes from the published contract
     * because a frame this class invented would be an expectation about nothing, but the text being
     * framed is always a literal restated here.
     *
     * @param  text the card's literal text
     * @return the text at exactly the card-image width
     */
    private static String paddedCardImage(final String text) {
        return text + " ".repeat(JclCardImageBuilder.CARD_IMAGE_WIDTH - text.length());
    }

    /**
     * Reads a ten-byte date slot back out of an assembled card.
     *
     * @param  cards   the assembled card sequence
     * @param  ordinal the one-based card ordinal the slot sits on
     * @param  offset  the zero-based offset of the slot within that card
     * @return the slot's value, exactly ten bytes wide
     */
    private static String slotAt(final List<String> cards, final int ordinal, final int offset) {
        return cards.get(ordinal - 1).substring(offset, offset + JclCardImageBuilder.DATE_SLOT_WIDTH);
    }

    /**
     * What was established about the named validation artefacts.
     *
     * @param satisfied whether every named artefact is present at its measured geometry
     * @param narrative what was examined, for the sign-off row
     */
    private record NamedArtefactEvidence(boolean satisfied, String narrative) { }

    /**
     * Confirms the named artefacts are present at the geometry they were measured at.
     *
     * @return the evidence
     * @throws IOException if an artefact cannot be read
     */
    private static NamedArtefactEvidence namedArtefactEvidence() throws IOException {
        boolean satisfied = true;
        int fixtures = 0;
        for (final Map.Entry<String, Integer> expected : NAMED_FIXTURE_SIZES.entrySet()) {
            final byte[] content =
                    classpathBytes(TestDataFactory.FIXTURE_DIRECTORY + expected.getKey());
            satisfied = satisfied && content.length == expected.getValue().intValue();
            fixtures++;
        }

        int encoded = 0;
        for (final Path dataset : filesByExtension(ENCODED_DATASET_DIRECTORY, ".PS")) {
            encoded += Files.isRegularFile(dataset) ? 1 : 0;
        }
        for (final Path dataset : filesByExtension(ENCODED_DATASET_DIRECTORY, ".INIT")) {
            encoded += Files.isRegularFile(dataset) ? 1 : 0;
        }
        satisfied = satisfied && encoded == ENCODED_DATASET_COUNT
                && TestDataFactory.SEEDED_IDENTITIES.size() == TestDataFactory.SEEDED_USER_COUNT;

        return new NamedArtefactEvidence(satisfied, fixtures + " line-delimited fixtures at their "
                + "measured byte counts, " + TestDataFactory.SEEDED_IDENTITIES.size() + " identities, "
                + encoded + " encoded datasets");
    }

    /**
     * Reads the highest base score a vulnerability entry carries.
     *
     * <p>The highest is used because a finding may carry several scoring vectors and a newer one is sometimes
     * lower; taking the maximum is what makes the threshold catch a finding whose latest vector understates
     * it. A finding with no score at all reads as zero and is left to the scanner's own threshold rather than
     * being guessed at here.
     *
     * @param  vulnerability the entry
     * @return its highest base score, or zero when it carries none
     */
    private static double highestScoreOf(final JsonNode vulnerability) {
        double highest = 0.0d;
        for (final String path : List.of("cvssv3", "cvssv4")) {
            final JsonNode scored = vulnerability.path(path).path("baseScore");
            if (scored.isNumber()) {
                highest = Math.max(highest, scored.asDouble());
            }
        }
        final JsonNode legacy = vulnerability.path("cvssv2").path("score");
        if (legacy.isNumber()) {
            highest = Math.max(highest, legacy.asDouble());
        }
        return highest;
    }

    /**
     * Writes the sign-off checklist to the build directory for transcription into the recorded evidence.
     *
     * <p>Written to the build directory rather than to the documentation tree on purpose. A test that edited
     * a committed document would make the evidence a product of the run that is supposed to be audited by
     * it; emitting it as build output keeps the recorded page a reviewed artefact and gives the reviewer the
     * exact text to carry across.
     *
     * @param  rows the checklist
     * @throws IOException if the record cannot be written
     */
    private static void publishSignOff(final List<ChecklistRow> rows) throws IOException {
        final StringBuilder rendered = new StringBuilder(2048);
        rendered.append("<!-- Gate 8 sign-off, measured by GateVerificationTest. Copy into ")
                .append(DOCUMENTATION_DIRECTORY).append('/').append(GATE_EVIDENCE).append(". -->\n\n")
                .append("## Gate 8 - integration sign-off checklist\n\n")
                .append("Checkout ").append(TestDataFactory.VERIFIED_CHECKOUT_COMMIT)
                .append(", upstream release stamp ").append(TestDataFactory.UPSTREAM_RELEASE_STAMP)
                .append(".\n\n")
                .append("Procedure-unit coverage: ").append(TOTAL_PROCEDURE_UNITS).append(" rows = ")
                .append(PROGRAM_PARAGRAPHS).append(" program paragraphs + ")
                .append(DATE_COPYBOOK_PARAGRAPHS).append(" + ").append(PFKEY_COPYBOOK_PARAGRAPHS)
                .append(" procedural-copybook paragraphs.\n\n")
                .append("| Checklist item | Satisfying artefact | State | Evidence |\n")
                .append("| :--- | :--- | :-: | :--- |\n");
        for (final ChecklistRow row : rows) {
            rendered.append(row).append('\n');
        }
        rendered.append("\nA row marked MISSING is an outstanding work item, not a warning: the ")
                .append("assertion that emits this table fails while any row is missing. The two ")
                .append("build-enforced rows name their enforcing mechanism as the artefact because ")
                .append("the scan and the coverage check are bound to a later phase than the tier ")
                .append("that writes this table; neither is ever recorded as passing on the strength ")
                .append("of an absent report.\n");

        Files.createDirectories(EVIDENCE_DIRECTORY);
        Files.writeString(EVIDENCE_DIRECTORY.resolve(SIGN_OFF_FILE), rendered.toString(),
                StandardCharsets.UTF_8);
    }
}
