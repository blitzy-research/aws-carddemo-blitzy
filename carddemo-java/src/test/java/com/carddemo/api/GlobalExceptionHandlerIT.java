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
package com.carddemo.api;

import com.carddemo.api.dto.AccountUpdateRequest;
import com.carddemo.api.dto.ErrorResponse;
import com.carddemo.config.JwtProperties;
import com.carddemo.config.JwtTokenProvider;
import com.carddemo.config.SecurityConfig;
import com.carddemo.config.WebMvcConfig;
import com.carddemo.domain.UserSecurity;
import com.carddemo.domain.enums.FileStatus;
import com.carddemo.domain.enums.UserType;
import com.carddemo.exception.AbendException;
import com.carddemo.exception.FileStatusException;
import com.carddemo.exception.JobSubmissionException;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.UserSecurityRepository;
import com.carddemo.service.BatchJobLaunchService;
import com.carddemo.service.BatchLaunchGateway;
import com.carddemo.service.CardConcurrencyTokenService;
import com.carddemo.service.CardDetailService;
import com.carddemo.service.CardListService;
import com.carddemo.service.CardUpdateService;
import com.carddemo.service.MessageCatalogService;
import com.carddemo.service.NavigationService;
import com.carddemo.service.ReportRequestService;
import com.carddemo.service.SensitiveFieldEncryptionService;
import com.carddemo.service.SignOnStateService;
import com.carddemo.support.AbstractPostgresIT;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.tracing.prometheus.PrometheusExemplarsAutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.batch.BatchAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;

/**
 * The six-handler error contract of {@link GlobalExceptionHandler}, exercised over real routes, the real
 * dispatch, the real security filter chain and a real PostgreSQL 16 server.
 *
 * <h2>What this adds that the unit tier cannot</h2>
 *
 * <p>Five unit classes already assert the advice by handing it a constructed exception, or by driving a
 * standalone dispatcher carrying a probe controller. Both are statements about the advice in isolation, and
 * both are true regardless of whether any delivered route can actually reach it. Three premises they cannot
 * establish are established here: that a <em>delivered</em> controller raises the carrier the advice
 * declares, that the carrier survives the whole servlet dispatch including the bearer-token filter chain and
 * the message converters, and that the sanitized body is what a client on the wire actually receives. No
 * probe controller, no test-only route and no test-only profile is introduced - a test-only surface would
 * make this class assert about a boundary no deployment has.
 *
 * <h2>The six mappings, and how each is reached</h2>
 *
 * <p>Three reach the advice through delivered module code with only a framework port stubbed:
 *
 * <ul>
 *   <li><strong>Not found.</strong> The batch-status operation asks the framework's own metadata for an
 *       execution it does not hold and raises the carrier itself. Only the metadata reader is stubbed, and
 *       its default answer - no such execution - is exactly the condition under test.</li>
 *   <li><strong>Validation.</strong> The launch operation's own parameter cascade refuses a parameter the
 *       addressed job does not declare. The refusal is composed as an <em>argument</em> to the launch call,
 *       so it is raised before anything is started and no job runs.</li>
 *   <li><strong>Conflict.</strong> The card-update turn opens the conversation state a client echoed. A
 *       state this server did not seal is refused by the delivered token service, on the card record, which
 *       is one of the two the migrated schema guards with a version column.</li>
 * </ul>
 *
 * <p>Three have no practical route: the abend and file-status carriers are raised by the batch tier and by
 * layers below the screen services, and the job-submission carrier is caught inside the report service and
 * never leaves it - which is itself the non-fatal contract. Each is therefore raised by a Mockito mock of
 * the collaborating service the delivered controller already calls, so the carrier crosses the advice by the
 * same path it would in production.
 *
 * <h2>What is asserted</h2>
 *
 * <p>Every status, body component and message asserted below was read out of
 * {@link GlobalExceptionHandler} rather than assumed, because three of the six are deliberately surprising:
 * two discard the carrier's own message and substitute a fixed one, and the third answers a failure with a
 * success status. The four headline properties are the six-way mapping, the non-leakage guarantee applied
 * uniformly through one helper, the two-state per-field validation detail, and the non-fatal
 * job-submission mapping.
 *
 * <h2>Determinism</h2>
 *
 * <p>The container, the migrated schema and the pinned clock all come from {@link AbstractPostgresIT}: this
 * class declares no container, no data-source property source and no context-discarding annotation, and it
 * reads no system clock. The session token every request presents is minted by the delivered token provider
 * against a seeded identity, so no credential value is named, read or rendered anywhere in this file.
 *
 * <p>Provenance: the contract texts asserted here originate in the common-message copybook
 * {@code app/cpy/CSMSG01Y.cpy}, the abend structure {@code app/cpy/CSMSG02Y.cpy}, the two-level file-status
 * normalisation of {@code app/cbl/CBACT01C.cbl} lines 90 to 114, the screen-decoration macro expanded 39
 * times in {@code app/cbl/COACTUPC.cbl} lines 3208 to 3432, the queue-write paragraph of
 * {@code app/cbl/CORPT00C.cbl} lines 515 to 535, the terminal abend text of {@code app/cbl/COCRDSLC.cbl}
 * and {@code app/cbl/COCRDUPC.cbl}, the reject reason codes of {@code app/cbl/CBTRN02C.cbl}, and the queue
 * attributes of {@code app/csd/CARDDEMO.CSD}; all read as read-only reference at checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source line is transcribed.
 */
@SpringBootTest(classes = GlobalExceptionHandlerIT.BoundaryContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"spring.flyway.enabled=false", "spring.main.banner-mode=off",
                "spring.jpa.hibernate.ddl-auto=none", "spring.batch.job.enabled=false",
                "management.endpoint.health.validate-group-membership=false",
                "management.tracing.enabled=false",
                // The dispatch is driven in-process, so there is no wire for a token to be observed on
                // and the transport requirement is relaxed exactly as the suite profile relaxes it.
                "carddemo.security.require-https=false"})
@AutoConfigureMockMvc
@DisplayName("the REST failure boundary: six carriers, six mappings, and nothing disclosed")
final class GlobalExceptionHandlerIT extends AbstractPostgresIT {


    // ---------------------------------------------------------------------------------------------
    // THE CONTRACT, RESTATED HERE RATHER THAN IMPORTED FROM THE SUBJECT.
    //
    // Every text below is written out as its own literal. An expectation that borrows the constant the
    // subject publishes proves only that the subject agrees with itself, and every one of these is an
    // operator-facing or client-facing string whose bytes are the contract.
    // ---------------------------------------------------------------------------------------------

    /**
     * The one terminal text the boundary publishes for an unrecoverable failure.
     *
     * <p>Present in the card-detail and card-update programs as the text their abend routine sends to the
     * terminal, and substituted by the abend carrier whenever no operator message was supplied. Note the
     * closing full stop and the absence of any trailing space: both are part of it.
     */
    private static final String TERMINAL_ABEND_TEXT = "UNEXPECTED ABEND OCCURRED.";

    /** The neutral summary a keyed read that resolved to no record answers with. */
    private static final String RECORD_NOT_FOUND_TEXT = "Record not found";

    /** The verbatim legacy text for a record that moved between the read and the write. */
    private static final String RECORD_CHANGED_TEXT = "Record changed by some one else. Please review";

    /**
     * The frozen failure literal of the online-to-batch queue write.
     *
     * <p>Capital {@code U} and {@code W}, {@code TDQ} in capitals, one space, {@code (JOBS)}
     * parenthesised, then three separate ASCII full stops rather than one ellipsis character, and nothing
     * after them.
     */
    private static final String JOB_SUBMISSION_FAILURE_TEXT = "Unable to Write TDQ (JOBS)...";

    /** The launch cascade's refusal for a parameter the addressed job does not declare. */
    private static final String UNKNOWN_PARAMETER_TEXT =
            "The request supplied a parameter this job does not declare.";

    /** The neutral summary for a request body the message converter could not read. */
    private static final String MALFORMED_REQUEST_BODY_TEXT = "Request body could not be read";

    /** The neutral summary for a request value that would not bind to the operation. */
    private static final String REQUEST_BINDING_FAILED_TEXT = "Request value could not be bound";

    /** The neutral summary for a representation the caller declared it would not accept. */
    private static final String REPRESENTATION_NOT_AVAILABLE_TEXT =
            "Requested representation is not available";

    /** Visible portion of the program-exit common message, before its padding. */
    private static final String THANK_YOU_VISIBLE_TEXT = "Thank you for using CardDemo application...";

    /** Visible portion of the unmapped-key common message, before its padding. */
    private static final String INVALID_KEY_VISIBLE_TEXT = "Invalid key pressed. Please see below...";

    /** Declared width of both common messages. */
    private static final int COMMON_MESSAGE_WIDTH = 50;

    /** Declared width of each screen title. */
    private static final int SCREEN_TITLE_WIDTH = 40;

    /** Total width of the legacy abend context: 4 + 8 + 50 + 72. */
    private static final int ABEND_CONTEXT_WIDTH = 134;

    // ---------------------------------------------------------------------------------------------
    // Routes, expressed as literals for the same reason.
    // ---------------------------------------------------------------------------------------------

    /** The batch-status operation, which raises the not-found carrier for an execution it does not own. */
    private static final String BATCH_EXECUTION_ROUTE = "/api/batch/jobs/executions/";

    /** Leading segment of the batch-launch operation, whose own cascade raises the validation carrier. */
    private static final String BATCH_LAUNCH_ROUTE_PREFIX = "/api/batch/jobs/";

    /** Trailing segment of the batch-launch operation. */
    private static final String BATCH_LAUNCH_ROUTE_SUFFIX = "/launch";

    /** The card-list turn, the route the three mock-raised carriers cross the advice on. */
    private static final String CARD_LIST_ROUTE = "/api/cards/list";

    /** The card-update turn, whose conversation-state check raises the conflict carrier. */
    private static final String CARD_UPDATE_ROUTE = "/api/cards/update";

    /** The report-request turn, the route the non-fatal submission failure crosses. */
    private static final String REPORT_REQUEST_ROUTE = "/api/reports/request";

    /** A job that declares no parameter at all, so any parameter supplied to it is refused. */
    private static final String JOB_ACCEPTING_NO_PARAMETER = "postTransactionJob";

    /** A job that declares exactly the interest-run parameter. */
    private static final String JOB_ACCEPTING_INTEREST_PARAMETER = "interestCalculationJob";

    /** An execution identifier the framework holds no metadata for. */
    private static final long ABSENT_EXECUTION_ID = 909_090_909L;

    /** An administrative identity the delivered seed applies, used to mint a session. */
    private static final String SEEDED_ADMIN_ID = "ADMIN001";

    /** A card the reference seed applies, read back to prove a refused turn wrote nothing. */
    private static final String SEEDED_CARD_NUMBER = "0500024453765740";

    /** Presentation prefix the session token is carried behind. */
    private static final String BEARER_PREFIX = "Bearer ";

    /** The media type every mapped body must be served as, and the only one. */
    private static final String JSON_CONTENT_TYPE = "application/json";

    /** The representation this module deliberately does not produce. */
    private static final String PROBLEM_JSON_CONTENT_TYPE = "application/problem+json";

    /** The four members of the standard problem-detail envelope, none of which may appear. */
    private static final List<String> PROBLEM_DETAIL_MEMBERS =
            List.of("\"type\"", "\"title\"", "\"detail\"", "\"instance\"");

    /**
     * Members a framework or problem-detail representation would carry and this contract must not.
     *
     * <p>Quoted, so a match is a JSON member name rather than a coincidence inside a message. The error
     * body carries exactly three components - the summary, the per-field list and the focus hint - and any
     * of these appearing would mean a second error shape had been introduced beside it.
     */
    private static final List<String> FRAMEWORK_BODY_MEMBERS =
            List.of("\"status\"", "\"timestamp\"", "\"path\"", "\"error\"", "\"trace\"",
                    "\"exception\"", "\"stackTrace\"", "\"cause\"", "\"errors\"", "\"violations\"",
                    "\"properties\"");

    /** Reads back the seeded card image, so a refused turn can be shown to have written nothing. */
    private static final String SELECT_CARD_IMAGE = """
            SELECT card_acct_id, card_cvv_cd, card_embossed_name, card_expiration_date,
                   card_active_status, version
              FROM card WHERE card_num = ?""";

    /**
     * The migrated record tables, one per verified legacy record layout.
     *
     * <p>The claim about version guards is a claim about <em>migrated records</em>, so it is asked of
     * exactly these eleven tables and of nothing else. The database this test shares with its siblings
     * also accumulates tables that no record layout produced - the migration tool's own history and the
     * batch runtime's execution metadata - and at least one of those carries a non-null integral column
     * also called {@code version}. Those are framework bookkeeping rather than migrated records, so
     * they are outside the claim entirely; scoping by name is what keeps the claim true regardless of
     * which siblings have already run and what they left behind.
     */
    private static final List<String> MIGRATED_RECORD_TABLES = List.of(
            "account", "card", "card_cross_reference", "customer", "daily_transaction",
            "disclosure_group", "transaction", "transaction_category",
            "transaction_category_balance", "transaction_type", "user_security");

    /**
     * Reads back which of the migrated record tables carry an optimistic-locking version guard.
     *
     * <p>The guard is identified by its shape as well as its name, because a column called
     * {@code version} is not necessarily a lock guard: a character column of that name is a label, not
     * a counter. A guard that backs a provider version check is a non-null integral counter, which is
     * what the two type predicates select and what the schema declares for both guarded records.
     */
    private static final String SELECT_VERSION_GUARDED_RECORD_TABLES = """
            SELECT table_name FROM information_schema.columns
             WHERE table_schema = 'public' AND table_name = ANY (?)
               AND column_name = 'version'
               AND data_type = 'bigint' AND is_nullable = 'NO'
             ORDER BY table_name""";

    /** Reads back which of the migrated record tables the live catalogue actually holds. */
    private static final String SELECT_EXISTING_RECORD_TABLES = """
            SELECT table_name FROM information_schema.tables
             WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
               AND table_name = ANY (?)
             ORDER BY table_name""";

    /**
     * Every substring that must never appear anywhere in a served body or header, upper-cased.
     *
     * <p>Comparison is made against an upper-cased copy of the whole served surface, so a marker cannot be
     * evaded by casing. The list is deliberately made of shapes rather than of words: a bare SQL verb would
     * match the legacy text "Update of record failed", which is a contract message and not a disclosure,
     * whereas a clause fragment, a schema-qualified column prefix or a driver type name cannot occur in any
     * operator text this module publishes.
     */
    private static final List<String> FORBIDDEN_MARKERS = List.of(
            // A rendered stack, in any of the three shapes a trace takes.
            "\tAT ", "\n\tAT", "STACKTRACE", "CAUSED BY", "SUPPRESSED:",
            // A failure type name, its package, or the package of any layer beneath the boundary.
            "EXCEPTION", "THROWABLE", "COM.CARDDEMO", "ORG.SPRINGFRAMEWORK", "ORG.HIBERNATE",
            "ORG.POSTGRESQL", "JAVA.LANG.", "JAVA.SQL.", "JAKARTA.",
            // Raw SQL, a schema object, an SQL state or a driver message.
            "SELECT ", " FROM ", " WHERE ", "INSERT INTO", "DELETE FROM", "SQLSTATE", "JDBC:",
            "PSQL", "USER_SECURITY", "CARD_CROSS_REFERENCE", "DAILY_TRANSACTION", "DISCLOSURE_GROUP",
            "TRANSACTION_CATEGORY", "FLYWAY_SCHEMA_HISTORY", "CARD_NUM", "ACCT_ID", "CUST_ID",
            // A file-system path, a class-path entry or an archive name.
            "FILE:/", "/USR/", "/HOME/", "/TMP/", "TARGET/CLASSES", ".JAR",
            // Job-control text, a card image or the stream sentinel.
            "/*EOF", "//SYSIN", "EXEC PGM=", "DD DSN=", "AWS.M2.CARDDEMO", "SYMNAMES",
            // Screen control bytes, a map name or a terminal attribute.
            "DFHRED", "DFHGREEN", "DFHBMASB", "DFHMSD", "DFHMDF", "CACTUPA", "CCRDUPA", "CCRDLIA",
            // Fixed-width record storage, which the wire contract replaces rather than carries.
            "PIC X(", "PIC 9(", "PIC S9", " OCCURS ", "REDEFINES", "FILE STATUS IS",
            // The raw response and reason codes the legacy queue write displayed to its console.
            "RESP:", "REAS:", "DFHRESP", "EIBRESP", "NOTFND", "DUPREC", "LENGERR");

    // ---------------------------------------------------------------------------------------------
    // The boundary under test, and the collaborators the delivered controllers already call.
    // ---------------------------------------------------------------------------------------------

    /** The dispatch, with the delivered filter chain in front of it. */
    @Autowired
    private MockMvc client;

    /** The delivered token provider, used to mint a session without naming a credential. */
    @Autowired
    private JwtTokenProvider tokenProvider;

    /** The validator the boundary itself binds request bodies with. */
    @Autowired
    private LocalValidatorFactoryBean boundaryValidator;

    /** The resolved environment, read so that a secret can be asserted absent without being written. */
    @Autowired
    private Environment environment;

    /** Raises the abend, file-status and per-field validation carriers on the card-list turn. */
    @MockitoBean
    private CardListService cardListService;

    /** Present because the card boundary declares it; never stubbed. */
    @MockitoBean
    private CardDetailService cardDetailService;

    /** Present because the card boundary declares it; never stubbed. */
    @MockitoBean
    private CardUpdateService cardUpdateService;

    /** Raises the non-fatal job-submission carrier on the report turn. */
    @MockitoBean
    private ReportRequestService reportRequestService;

    /** The framework's job registry. Its answer is irrelevant: the cascade refuses before the launch. */
    @MockitoBean
    private JobRegistry jobRegistry;

    /**
     * The framework's metadata reader. Left unstubbed on purpose - its default answer is "no such
     * execution", which is exactly the condition the not-found mapping exists for.
     */
    @MockitoBean
    private JobExplorer jobExplorer;

    /** Present because the launch service declares it; never stubbed. */
    @MockitoBean
    private JobOperator jobOperator;

    /** The launch port. Stubbed nowhere, so no test in this class can start a job. */
    @MockitoBean
    private BatchLaunchGateway batchLaunchGateway;

    /** Creates the specification. */
    GlobalExceptionHandlerIT() {
        super();
    }


    // =============================================================================================
    // SHARED MACHINERY
    // =============================================================================================

    /**
     * Mints a session for a seeded administrative identity, without naming a credential.
     *
     * <p>The delivered token provider is asked directly, which is what the sign-on path itself does once it
     * has verified a credential. No cleartext value is read, held or rendered anywhere in this class, and
     * the administrative authority is used throughout because it satisfies both the plain
     * authenticated rule and the batch-control rule - one session therefore reaches every route below.
     *
     * <p>The provider refuses to mint for an identity no record carries, so a token existing at all is
     * evidence that the seed applied. Both the issue and the later verification read the pinned clock, so
     * the session neither expires nor depends on when the suite runs.
     *
     * @return the value of the authorization header, including its presentation prefix
     */
    private String administrativeSession() {
        return BEARER_PREFIX + this.tokenProvider.issue(SEEDED_ADMIN_ID, UserType.ADMIN,
                UserType.ADMIN.getCode());
    }

    /**
     * Assembles the launch route for one job.
     *
     * @param jobName the stable job name the launch addresses
     * @return the full request path
     */
    private static String launchRoute(final String jobName) {
        return BATCH_LAUNCH_ROUTE_PREFIX + jobName + BATCH_LAUNCH_ROUTE_SUFFIX;
    }

    /**
     * Performs one JSON turn against a route behind the delivered filter chain.
     *
     * @param route the request path
     * @param body  the request body, exactly as it should reach the message converter
     * @return the completed result, for status, body and header assertions
     * @throws Exception when the dispatch itself cannot be performed
     */
    private MvcResult postJson(final String route, final String body) throws Exception {
        return this.client.perform(MockMvcRequestBuilders.post(route)
                        .header(HttpHeaders.AUTHORIZATION, administrativeSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    /**
     * Performs one read against a route behind the delivered filter chain.
     *
     * @param route the request path
     * @return the completed result
     * @throws Exception when the dispatch itself cannot be performed
     */
    private MvcResult get(final String route) throws Exception {
        return this.client.perform(MockMvcRequestBuilders.get(route)
                        .header(HttpHeaders.AUTHORIZATION, administrativeSession()))
                .andReturn();
    }

    /**
     * Returns the served body as text, decoded one character per byte of the served payload.
     *
     * @param result the completed result
     * @return the body exactly as a client would read it
     * @throws Exception when the body cannot be read
     */
    private static String bodyOf(final MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * Renders the served headers as text, so one assertion can cover the whole response surface.
     *
     * @param result the completed result
     * @return every header name and value, one per line
     */
    private static String headersOf(final MvcResult result) {
        final StringBuilder rendered = new StringBuilder(256);
        for (final String name : result.getResponse().getHeaderNames()) {
            rendered.append(name).append('=').append(result.getResponse().getHeaderValues(name))
                    .append('\n');
        }
        return rendered.toString();
    }

    /**
     * THE NON-LEAKAGE GUARANTEE, applied identically to every one of the six mappings.
     *
     * <p>This is the single most important assertion set in this class, and it is written once and invoked
     * from each mapping so that no mapping can be added or changed without being held to it. Both the body
     * and the headers are covered, because a leak in a header is a leak.
     *
     * <p>Three categories are checked. The fixed marker list covers a rendered stack, a failure type name
     * or package, raw SQL and schema objects, a file-system or archive path, job-control text, screen
     * control bytes, fixed-width record storage, and the raw response and reason codes the legacy queue
     * write displayed. The configured-secret set is resolved from the running environment rather than
     * written here, so a signing secret, a field-encryption key and an operator token can be asserted
     * absent without any of them appearing in this file. Finally the session the request presented is
     * itself a credential, and is asserted absent for the same reason.
     *
     * @param result the completed result whose whole served surface must disclose nothing
     * @throws Exception when the body cannot be read
     */
    private void assertNothingIsDisclosed(final MvcResult result) throws Exception {
        final String body = bodyOf(result);
        final String surface = body + '\n' + headersOf(result);
        final String folded = surface.toUpperCase(Locale.ROOT);

        for (final String marker : FORBIDDEN_MARKERS) {
            assertThat(folded)
                    .as("the served surface discloses %s, which no operator text in this module carries",
                            marker)
                    .doesNotContain(marker);
        }
        for (final String member : FRAMEWORK_BODY_MEMBERS) {
            assertThat(body)
                    .as("the error body carries the framework member %s beside its own three components",
                            member)
                    .doesNotContain(member);
        }
        for (final String secret : configuredSecrets()) {
            assertThat(body)
                    .as("a configured secret reached the served body")
                    .doesNotContain(secret);
            assertThat(headersOf(result))
                    .as("a configured secret reached a served header")
                    .doesNotContain(secret);
        }
        assertThat(body)
                .as("the session the request presented was echoed back into the body")
                .doesNotContain(administrativeSession());
        assertThat(body)
                .as("the boundary serves its own error contract, never a problem-detail document")
                .doesNotContain(PROBLEM_DETAIL_MEMBERS.toArray(new String[0]));
    }

    /**
     * Collects the secrets the running profile configures, so each can be asserted absent from a body
     * without ever being written into this file.
     *
     * <p>The container's own throwaway database password is deliberately not included: the support base
     * documents it as a value no user of the system authenticates with, and it is a common enough word that
     * asserting it absent would fail on a coincidence rather than on a disclosure. The three collected here
     * are genuine: they sign sessions, open sealed fields and admit an operator to the management surface.
     *
     * @return every configured secret that resolves in this environment, never {@code null}
     */
    private List<String> configuredSecrets() {
        final List<String> resolved = new ArrayList<>(3);
        addIfPresent(resolved, "carddemo.security.jwt.secret");
        addIfPresent(resolved, "carddemo.security.field-encryption.key");
        addIfPresent(resolved, "carddemo.security.management.token");
        return List.copyOf(resolved);
    }

    /**
     * Adds one resolved property value to the collected set when the environment carries a usable one.
     *
     * @param collected the accumulating list
     * @param name      the property to resolve
     */
    private void addIfPresent(final List<String> collected, final String name) {
        final String value = this.environment.getProperty(name);
        if (value != null && !value.isBlank()) {
            collected.add(value);
        }
    }

    /**
     * Asserts the four properties every mapped response shares, whatever its status.
     *
     * <p>The served representation is this module's own JSON error contract and never the standard
     * problem-detail document, the body carries the summary the caller is entitled to, and nothing is
     * disclosed. Invoking this from each mapping is what makes the six uniform rather than six separately
     * plausible answers.
     *
     * @param result          the completed result
     * @param expectedStatus  the status the boundary declares for this carrier
     * @param expectedMessage the exact summary text, asserted byte for byte
     * @throws Exception when the body cannot be read
     */
    private void assertMappedAs(final MvcResult result, final HttpStatus expectedStatus,
            final String expectedMessage) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as("the status the boundary declares for this carrier")
                .isEqualTo(expectedStatus.value());
        assertThat(result.getResponse().getContentType())
                .as("the served representation")
                .isNotNull()
                .startsWith(JSON_CONTENT_TYPE)
                .doesNotContain(PROBLEM_JSON_CONTENT_TYPE);
        assertThat(summaryOf(result))
                .as("the summary text, byte for byte and untrimmed")
                .isEqualTo(expectedMessage);
        assertNothingIsDisclosed(result);
    }

    /**
     * Reads the summary component out of a served error body.
     *
     * <p>Extracted by locating the member rather than by parsing, so an added or renamed member cannot make
     * this read the wrong value silently, and so that trailing space inside the value survives - the legacy
     * fields these texts derive from are space-significant and nothing here may trim.
     *
     * @param result the completed result
     * @return the summary exactly as served, or {@code null} when the body carries none
     * @throws Exception when the body cannot be read
     */
    private static String summaryOf(final MvcResult result) throws Exception {
        return memberOf(bodyOf(result), "message");
    }

    /**
     * Reads one string-valued top-level member out of a served JSON body.
     *
     * @param body   the served body
     * @param member the member name
     * @return the member's value exactly as served, or {@code null} when the body carries no such member
     */
    private static String memberOf(final String body, final String member) {
        final String marker = '"' + member + "\":\"";
        final int opens = body.indexOf(marker);
        if (opens < 0) {
            return null;
        }
        final int from = opens + marker.length();
        final int closes = body.indexOf('"', from);
        if (closes < 0) {
            return null;
        }
        return body.substring(from, closes);
    }


    // =============================================================================================
    // MAPPING 1 OF 6 - a keyed read that resolved to no record
    // =============================================================================================

    @Nested
    @DisplayName("RecordNotFound mapping")
    class RecordNotFoundMapping {

        /** Creates the group. */
        RecordNotFoundMapping() {
            super();
        }

        // The condition the estate reports as file status 23 on an indexed read; the batch-status
        // operation raises the carrier itself when the framework holds no such execution.
        @Test
        @DisplayName("an execution the surface does not hold is answered not found with a neutral summary")
        void aKeyedReadThatResolvedToNoRecordIsAnsweredNotFound() throws Exception {
            final MvcResult result = get(BATCH_EXECUTION_ROUTE + ABSENT_EXECUTION_ID);

            assertMappedAs(result, HttpStatus.NOT_FOUND, RECORD_NOT_FOUND_TEXT);
            assertThat(bodyOf(result))
                    .as("a not-found carries no per-field detail, so the list is served empty")
                    .contains("\"fieldErrors\":[]");
        }

        // The carrier's own detail message names its type and the resource it searched; the boundary
        // publishes one neutral summary instead, so neither reaches a client.
        @Test
        @DisplayName("the body names neither the record type, the searched key nor the carrier's own type")
        void theBodyCarriesNoRecordIdentity() throws Exception {
            final String body = bodyOf(get(BATCH_EXECUTION_ROUTE + ABSENT_EXECUTION_ID));

            assertThat(body)
                    .doesNotContain("BatchJobExecution")
                    .doesNotContain("RecordNotFound")
                    .doesNotContain(Long.toString(ABSENT_EXECUTION_ID));
        }

        // Absent optional members are omitted rather than rendered null: a not-found offers no focus
        // hint at all, so the component disappears from the payload.
        @Test
        @DisplayName("the absent focus hint is omitted from the payload rather than rendered null")
        void theAbsentFocusHintIsOmitted() throws Exception {
            final String body = bodyOf(get(BATCH_EXECUTION_ROUTE + ABSENT_EXECUTION_ID));

            assertThat(body)
                    .doesNotContain("focusScreenFieldId")
                    .doesNotContain("null");
        }
    }

    // =============================================================================================
    // MAPPING 2 OF 6 - a field-level validation failure, with the two legacy states preserved
    // =============================================================================================

    @Nested
    @DisplayName("Validation mapping - MISSING vs INVALID and first-error-wins")
    class ValidationMapping {

        /** A screen field label the first fixture entry decorates, and the focus hint it implies. */
        private static final String FIRST_SCREEN_FIELD = "CARDSID";

        /** A screen field label the second fixture entry decorates. */
        private static final String SECOND_SCREEN_FIELD = "ACCTSID";

        /** The summary the fixture carrier publishes, passed through by the boundary unchanged. */
        private static final String FIXTURE_SUMMARY = "Please correct the highlighted fields";

        /** Creates the group. */
        ValidationMapping() {
            super();
        }

        // The launch cascade of the batch-control surface, which stops at the first parameter it
        // refuses and publishes one summary with no per-field detail.
        @Test
        @DisplayName("a service-raised validation failure is answered bad request with its own summary")
        void aServiceRaisedValidationFailureIsAnsweredBadRequest() throws Exception {
            final MvcResult result = postJson(launchRoute(JOB_ACCEPTING_NO_PARAMETER),
                    "{\"interestParmDate\":\"2022061012\"}");

            assertMappedAs(result, HttpStatus.BAD_REQUEST, UNKNOWN_PARAMETER_TEXT);
        }

        // The legacy decoration macro fires only when the re-entry indicator is set, so a submission
        // that has not been re-presented carries no field state at all. That shape is what a
        // summary-only carrier produces here: an empty list and no focus hint.
        @Test
        @DisplayName("an undecorated submission carries an empty field list and no focus hint")
        void anUndecoratedSubmissionCarriesNoFieldState() throws Exception {
            final String body = bodyOf(postJson(launchRoute(JOB_ACCEPTING_NO_PARAMETER),
                    "{\"interestParmDate\":\"2022061012\"}"));

            assertThat(body)
                    .as("the first-submission shape: a summary line and every field undecorated")
                    .contains("\"fieldErrors\":[]")
                    .doesNotContain("focusScreenFieldId")
                    .doesNotContain("MISSING")
                    .doesNotContain("INVALID");
        }

        // The decoration macro marks a blank field as well as highlighting it, and highlights a
        // wrongly filled field without marking it. A value that arrived blank is therefore the
        // not-supplied state.
        @Test
        @DisplayName("a field that arrived blank is reported MISSING")
        void aBlankFieldIsReportedMissing() throws Exception {
            final MvcResult result = postJson(launchRoute(JOB_ACCEPTING_INTEREST_PARAMETER),
                    "{\"interestParmDate\":\" \"}");

            assertThat(result.getResponse().getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
            assertThat(bodyOf(result))
                    .contains("\"fieldName\":\"interestParmDate\"")
                    .contains("\"state\":\"MISSING\"")
                    .doesNotContain("\"state\":\"INVALID\"");
            assertNothingIsDisclosed(result);
        }

        // The companion state: a value arrived and failed its edit, so the remedy is a correction
        // rather than a value.
        @Test
        @DisplayName("a field that arrived and failed its edit is reported INVALID")
        void aSuppliedButUnusableFieldIsReportedInvalid() throws Exception {
            final MvcResult result = postJson(launchRoute(JOB_ACCEPTING_INTEREST_PARAMETER),
                    "{\"interestParmDate\":\"2022061X\"}");

            assertThat(result.getResponse().getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
            assertThat(bodyOf(result))
                    .contains("\"fieldName\":\"interestParmDate\"")
                    .contains("\"state\":\"INVALID\"")
                    .doesNotContain("\"state\":\"MISSING\"");
            assertNothingIsDisclosed(result);
        }

        // A single boolean would tell a client that something is wrong without telling it whether to
        // ask the operator for a value or for a correction, which is the distinction the legacy screen
        // drew. The same field in the two states must therefore read differently.
        @Test
        @DisplayName("the two states are distinguishable on the same field and are never conflated")
        void theTwoStatesAreNeverConflated() throws Exception {
            final String blank = bodyOf(postJson(launchRoute(JOB_ACCEPTING_INTEREST_PARAMETER),
                    "{\"interestParmDate\":\" \"}"));
            final String unusable = bodyOf(postJson(launchRoute(JOB_ACCEPTING_INTEREST_PARAMETER),
                    "{\"interestParmDate\":\"2022061X\"}"));

            assertThat(blank).isNotEqualTo(unusable);
            assertThat(blank).contains("\"state\":\"MISSING\"");
            assertThat(unusable).contains("\"state\":\"INVALID\"");
            assertThat(blank)
                    .as("no boolean stands in for the two states")
                    .doesNotContain("\"valid\"")
                    .doesNotContain("\"invalid\":")
                    .doesNotContain("\"hasFieldErrors\"");
        }

        // The carrier arm, where a service assembled the per-field detail itself. Order is the service's
        // and no sequence is imposed, and the cursor returns to the first field that failed.
        @Test
        @DisplayName("per-field detail survives in producer order, with the focus hint from the first entry")
        void perFieldDetailSurvivesInProducerOrder() throws Exception {
            willThrow(twoFieldFailure()).given(cardListService).processCardList(any());

            final MvcResult result = postJson(CARD_LIST_ROUTE, "{}");

            assertMappedAs(result, HttpStatus.BAD_REQUEST, FIXTURE_SUMMARY);
            final String body = bodyOf(result);
            assertThat(body.indexOf(FIRST_SCREEN_FIELD))
                    .as("the entry the service assembled first is served first")
                    .isLessThan(body.indexOf(SECOND_SCREEN_FIELD));
            assertThat(body)
                    .contains("\"focusScreenFieldId\":\"" + FIRST_SCREEN_FIELD + '"')
                    .contains("\"state\":\"MISSING\"")
                    .contains("\"state\":\"INVALID\"");
        }

        // Ordering is the contract. The legacy cascade stops at the first failure, so two unacceptable
        // parameters still produce exactly one summary and no second one is appended.
        @Test
        @DisplayName("only the earlier failure is reported when two parameters are unacceptable")
        void onlyTheEarlierFailureIsReported() throws Exception {
            final MvcResult result = postJson(launchRoute(JOB_ACCEPTING_NO_PARAMETER),
                    "{\"interestParmDate\":\"2022061012\",\"reportStartDate\":\"2022-06-01\"}");

            assertMappedAs(result, HttpStatus.BAD_REQUEST, UNKNOWN_PARAMETER_TEXT);
            final String body = bodyOf(result);
            assertThat(body.indexOf(UNKNOWN_PARAMETER_TEXT))
                    .as("the refusal appears once, so the cascade stopped rather than accumulating")
                    .isEqualTo(body.lastIndexOf(UNKNOWN_PARAMETER_TEXT));
            assertThat(body).contains("\"fieldErrors\":[]");
        }

        /**
         * The two-entry fixture: one field not supplied and one supplied and unusable, in that order.
         *
         * @return a carrier holding both legacy states, in the order a cascade would have found them
         */
        private ValidationException twoFieldFailure() {
            return new ValidationException(FIXTURE_SUMMARY, List.of(
                    new ValidationException.FieldError("cardNumberFilter", FIRST_SCREEN_FIELD,
                            ValidationException.FieldState.MISSING, "A card number is required"),
                    new ValidationException.FieldError("accountIdFilter", SECOND_SCREEN_FIELD,
                            ValidationException.FieldState.INVALID, "Eleven digits are expected")));
        }
    }

    // =============================================================================================
    // The two fields the estate decorates for display and never edits
    // =============================================================================================

    @Nested
    @DisplayName("Unvalidated middle name and address line 2")
    class UnvalidatedFields {

        /** Content wider than any neighbouring field's bound, and not text a constrained field admits. */
        private static final String UNRESTRICTED_CONTENT =
                "a value far wider than any neighbouring bound, carrying punctuation - and digits 12345";

        /** Creates the group. */
        UnvalidatedFields() {
            super();
        }

        // The source states in place that neither of these two is edited, so the request contract carries
        // no constraint on either and none may be added: doing so would reject input the legacy accepts.
        @Test
        @DisplayName("arbitrary content in the two unedited fields is accepted by the boundary validator")
        void arbitraryContentInTheTwoUneditedFieldsIsAccepted() {
            final Set<ConstraintViolation<AccountUpdateRequest>> violations =
                    boundaryValidator.validate(
                            accountUpdate(UNRESTRICTED_CONTENT, UNRESTRICTED_CONTENT, null));

            assertThat(violations)
                    .as("a constraint on either field would refuse a submission the legacy admits")
                    .isEmpty();
        }

        // Non-vacuous: the same validator, over the same request, still refuses a neighbouring field that
        // exceeds the width its screen field declares.
        @Test
        @DisplayName("the same validator still refuses a constrained neighbour that is too wide")
        void theSameValidatorStillRefusesAConstrainedNeighbour() {
            final Set<ConstraintViolation<AccountUpdateRequest>> violations =
                    boundaryValidator.validate(accountUpdate(null, null, "TOOWIDE"));

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath().toString())
                    .isEqualTo("accountStatus");
        }

        // The provider-raised form of that refusal, carried across the real advice: the field that failed
        // is named and reported INVALID because a value arrived, and the two unedited fields are absent.
        @Test
        @DisplayName("a provider-raised constraint failure is answered bad request naming only that field")
        void aProviderRaisedConstraintFailureNamesOnlyTheFailedField() throws Exception {
            final Set<ConstraintViolation<AccountUpdateRequest>> violations =
                    boundaryValidator.validate(
                            accountUpdate(UNRESTRICTED_CONTENT, UNRESTRICTED_CONTENT, "TOOWIDE"));
            willThrow(new ConstraintViolationException(violations))
                    .given(cardListService).processCardList(any());

            final MvcResult result = postJson(CARD_LIST_ROUTE, "{}");

            assertThat(result.getResponse().getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
            assertThat(bodyOf(result))
                    .contains("\"fieldName\":\"accountStatus\"")
                    .contains("\"state\":\"INVALID\"")
                    .doesNotContain("middleName")
                    .doesNotContain("addressLine2");
            assertNothingIsDisclosed(result);
        }

        /**
         * Builds one update submission with only the components this group cares about.
         *
         * <p>The canonical constructor is invoked in the symbolic map's declaration order, which
         * interleaves the account group id between two monetary components and places the three
         * date-of-birth parts before the credit score. Every other component is left absent, which every
         * declared bound admits.
         *
         * @param middleName    content for the first of the two unedited fields
         * @param addressLine2  content for the second
         * @param accountStatus content for one constrained neighbour, or {@code null} to leave it absent
         * @return the submission to validate
         */
        private AccountUpdateRequest accountUpdate(final String middleName, final String addressLine2,
                final String accountStatus) {
            return new AccountUpdateRequest(
                    null, accountStatus, null, null, null,
                    null,
                    null, null, null,
                    null,
                    null, null, null,
                    null, null, null, null,
                    null, null, null, null,
                    null, null, null,
                    null,
                    null, middleName, null, null, null, addressLine2,
                    null, null, null,
                    null, null, null,
                    null,
                    null, null, null,
                    null, null,
                    null, null, null);
        }
    }


    // =============================================================================================
    // MAPPING 3 OF 6 - a concurrent-update conflict on a version-guarded record
    // =============================================================================================

    @Nested
    @DisplayName("OptimisticLockConflict mapping and rollback")
    class OptimisticLockConflictMapping {

        /** A conversation state this server did not seal, standing in for a screen that has moved on. */
        private static final String FOREIGN_CONVERSATION_STATE = "a-state-this-server-never-sealed";

        /** Creates the group. */
        OptimisticLockConflictMapping() {
            super();
        }

        // The legacy write path detected a moved record by re-reading it and comparing it against the image
        // it had presented; the migrated boundary carries that image as sealed state and refuses one it did
        // not seal. The record is the card, one of the two the schema guards with a version column.
        @Test
        @DisplayName("a conversation state this server did not seal is answered conflict with the legacy text")
        void aRefusedConversationStateIsAnsweredConflict() throws Exception {
            final MvcResult result = postJson(CARD_UPDATE_ROUTE,
                    "{\"concurrencyToken\":\"" + FOREIGN_CONVERSATION_STATE + "\"}");

            assertMappedAs(result, HttpStatus.CONFLICT, RECORD_CHANGED_TEXT);
            assertThat(bodyOf(result)).contains("\"fieldErrors\":[]");
        }

        // Recoverable rather than terminal: the legacy re-displayed the record for review and never abended,
        // so the conflict must not read as the terminal failure and must not borrow its text.
        @Test
        @DisplayName("the conflict is reported as recoverable rather than as the terminal failure")
        void theConflictIsNotReportedAsTheTerminalFailure() throws Exception {
            final String body = bodyOf(postJson(CARD_UPDATE_ROUTE,
                    "{\"concurrencyToken\":\"" + FOREIGN_CONVERSATION_STATE + "\"}"));

            assertThat(body).doesNotContain(TERMINAL_ABEND_TEXT);
            assertThat(RECORD_CHANGED_TEXT).isNotEqualTo(TERMINAL_ABEND_TEXT);
        }

        // A refused turn must leave the record exactly as it found it, and no stale value may be written
        // behind the refusal. The whole stored image and its version are compared before and after.
        @Test
        @DisplayName("the refused turn writes nothing: the stored image and its version are unchanged")
        void theRefusedTurnWritesNothing() throws Exception {
            final List<String> before = storedCardImage();

            final MvcResult result = postJson(CARD_UPDATE_ROUTE,
                    "{\"cardNumber\":\"" + SEEDED_CARD_NUMBER + "\",\"embossedName\":\"OVERWRITTEN\","
                            + "\"concurrencyToken\":\"" + FOREIGN_CONVERSATION_STATE + "\"}");

            assertThat(result.getResponse().getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
            assertThat(storedCardImage())
                    .as("a refused turn that had written anything would have moved the version too")
                    .isEqualTo(before);
            assertThat(before).isNotEmpty();
        }

        // The version guard exists on exactly two of the eleven migrated records, so a conflict may only
        // ever be provoked on one of those two, and this turn provoked it on the card. Read back from the
        // live catalogue of the migrated database rather than assumed, matched on the guard's shape - a
        // non-null integral counter - and asked only of migrated record tables, because the shared
        // database also holds framework bookkeeping that no record layout produced.
        @Test
        @DisplayName("of the eleven migrated records exactly account and card carry a version counter")
        void versionGuardsExistOnAccountAndCardOnly() throws Exception {
            assertThat(queryMigratedRecordTables(SELECT_EXISTING_RECORD_TABLES))
                    .as("the eleven-name inventory must match the migrated schema, never drift from it")
                    .containsExactlyElementsOf(MIGRATED_RECORD_TABLES.stream().sorted().toList());

            assertThat(queryMigratedRecordTables(SELECT_VERSION_GUARDED_RECORD_TABLES))
                    .containsExactly("account", "card");
        }
    }

    // =============================================================================================
    // MAPPING 4 OF 6 - an unhandled file operation failure, and the two-level status model
    // =============================================================================================

    @Nested
    @DisplayName("FileStatus mapping - raw code and the two-level model")
    class FileStatusMapping {

        /** The one status literal the source genuinely compares in a status test besides success and end. */
        private static final String DEFAULT_GROUP_FALLBACK_STATUS = "23";

        /** The legacy resource name the carrier is given, which must not reach a client. */
        private static final String LEGACY_RESOURCE = "DISCGRP";

        /** The operation the carrier is given, which must not reach a client either. */
        private static final String LEGACY_OPERATION = "READ";

        /** Creates the group. */
        FileStatusMapping() {
            super();
        }

        // Reaching the boundary means no layer below absorbed the failure, which in the legacy design is the
        // path that ends in an abend - diagnostic first, raw status second, abend third - so the operator
        // sees the terminal text and the status stays on the diagnostic channel.
        @Test
        @DisplayName("an unhandled file operation failure is answered with the terminal text")
        void anUnhandledFileOperationFailureIsAnsweredWithTheTerminalText() throws Exception {
            willThrow(new FileStatusException(DEFAULT_GROUP_FALLBACK_STATUS, LEGACY_OPERATION,
                    LEGACY_RESOURCE)).given(cardListService).processCardList(any());

            final MvcResult result = postJson(CARD_LIST_ROUTE, "{}");

            assertMappedAs(result, HttpStatus.INTERNAL_SERVER_ERROR, TERMINAL_ABEND_TEXT);
        }

        // The carrier carries the raw two-byte status in a controlled form, and the boundary publishes none
        // of it: not the code, not the operation, not the resource, and not the composed diagnostic.
        @Test
        @DisplayName("the raw two-byte status is carried by the exception and withheld from the body")
        void theRawStatusIsCarriedAndWithheld() throws Exception {
            final FileStatusException carrier = new FileStatusException(
                    DEFAULT_GROUP_FALLBACK_STATUS, LEGACY_OPERATION, LEGACY_RESOURCE);
            willThrow(carrier).given(cardListService).processCardList(any());

            final String body = bodyOf(postJson(CARD_LIST_ROUTE, "{}"));

            assertThat(carrier.code())
                    .as("the controlled path to the raw status is the carrier, not the response")
                    .isEqualTo(DEFAULT_GROUP_FALLBACK_STATUS);
            assertThat(carrier.firstByte()).isEqualTo('2');
            assertThat(carrier.secondByte()).isEqualTo('3');
            assertThat(body)
                    .doesNotContain(DEFAULT_GROUP_FALLBACK_STATUS)
                    .doesNotContain(LEGACY_RESOURCE)
                    .doesNotContain(LEGACY_OPERATION)
                    .doesNotContain(carrier.getMessage())
                    .doesNotContain(FileStatusException.DISPLAY_PREFIX);
        }

        // END OF FILE IS NEVER COLLAPSED INTO ERROR, and the carrier itself is what guarantees it: neither
        // success nor end of file can be raised as a failure at all. Nine of the ten batch programs
        // terminate their read loop on end of file, so erasing the distinction would turn successful jobs
        // into abends.
        @Test
        @DisplayName("neither success nor end of file can be raised as a failure")
        void endOfFileIsNeverCollapsedIntoError() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new FileStatusException(FileStatusException.STATUS_SUCCESS,
                            LEGACY_OPERATION, LEGACY_RESOURCE));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new FileStatusException(FileStatusException.STATUS_END_OF_FILE,
                            LEGACY_OPERATION, LEGACY_RESOURCE));
        }

        // The two levels: a raw code enumeration that classifies nothing, and the coarse outcome the
        // programs actually branch on. End of file stays separable from every error code through the raw
        // enumeration's own predicate.
        @Test
        @DisplayName("the two outcomes stay separable: end of file is not any error code")
        void theTwoOutcomesStaySeparable() {
            assertThat(FileStatus.fromCode(FileStatusException.STATUS_END_OF_FILE))
                    .containsSame(FileStatus.END_OF_FILE);
            assertThat(FileStatus.END_OF_FILE.isEndOfFile()).isTrue();
            assertThat(FileStatus.END_OF_FILE.isSuccess()).isFalse();
            assertThat(FileStatus.RECORD_NOT_FOUND.isEndOfFile()).isFalse();
            assertThat(FileStatus.PERMANENT_ERROR.isEndOfFile()).isFalse();
            assertThat(FileStatus.SUCCESS.isEndOfFile()).isFalse();
            assertThat(FileStatus.RECORD_NOT_FOUND.getCode())
                    .as("the not-found carrier and the raw enumeration name the same legacy status")
                    .isEqualTo(RecordNotFoundException.STATUS_RECORD_NOT_FOUND);
        }

        // Every literal the source compares resolves, including the one status test that occurs exactly
        // once - the disclosure-group default-group fallback.
        @Test
        @DisplayName("every status literal the source compares resolves to a declared constant")
        void everyStatusLiteralTheSourceComparesResolves() {
            for (final String code
                    : List.of("00", "01", "02", "04", "05", "10", "12", "23", "31")) {
                assertThat(FileStatus.fromCode(code))
                        .as("status %s is compared in the source and must be declared", code)
                        .isPresent();
            }
            assertThat(FileStatus.fromCode(DEFAULT_GROUP_FALLBACK_STATUS))
                    .containsSame(FileStatus.RECORD_NOT_FOUND);
        }

        // Two codes are cited by prior specification text and compared nowhere in the source. They may be
        // declared, but nothing may branch on them: a carrier holding either is answered exactly as a
        // carrier holding a genuinely observed error code is.
        @Test
        @DisplayName("no behaviour depends on the two documented but unexercised status codes")
        void noBehaviourDependsOnTheUnexercisedCodes() throws Exception {
            final String observed = mappedBodyForFileStatus(DEFAULT_GROUP_FALLBACK_STATUS);
            final String duplicateKey = mappedBodyForFileStatus(FileStatus.DUPLICATE_KEY.getCode());
            final String fileNotFound = mappedBodyForFileStatus(FileStatus.FILE_NOT_FOUND.getCode());

            assertThat(duplicateKey)
                    .as("a documented-but-unexercised code must not be special-cased")
                    .isEqualTo(observed);
            assertThat(fileNotFound).isEqualTo(observed);
        }

        /**
         * Drives one file-status failure through the boundary and returns the served body.
         *
         * @param code the raw two-byte status the carrier holds
         * @return the served body
         * @throws Exception when the dispatch cannot be performed
         */
        private String mappedBodyForFileStatus(final String code) throws Exception {
            willThrow(new FileStatusException(code, LEGACY_OPERATION, LEGACY_RESOURCE))
                    .given(cardListService).processCardList(any());
            return bodyOf(postJson(CARD_LIST_ROUTE, "{}"));
        }
    }


    // =============================================================================================
    // MAPPING 5 OF 6 - the controlled terminal path
    // =============================================================================================

    @Nested
    @DisplayName("Abend mapping - controlled message, no payload")
    class AbendMapping {

        /** The originating component the carrier names, which belongs on the diagnostic channel. */
        private static final String CULPRIT = "COCRDLIC";

        /** The failure reason the carrier names, which belongs on the diagnostic channel. */
        private static final String REASON = "BROWSE OF CARD FILE FAILED";

        /** Operator text the online routine sent to a terminal, so it may cross the boundary. */
        private static final String OPERATOR_TEXT = "CARD BROWSE ENDED UNEXPECTEDLY";

        /** Creates the group. */
        AbendMapping() {
            super();
        }

        // The batch sites call the abort routine with a code and no message and write their diagnostic to
        // the job log, so the text a batch abend composed is withheld and the frozen terminal literal is
        // published in its place. The classification is fail-closed: only the online code publishes text.
        @Test
        @DisplayName("a batch abend publishes the frozen terminal literal and withholds its own diagnostic")
        void aBatchAbendPublishesTheTerminalLiteral() throws Exception {
            willThrow(new AbendException(AbendException.BATCH_ABEND_CODE, CULPRIT, REASON, OPERATOR_TEXT))
                    .given(cardListService).processCardList(any());

            final MvcResult result = postJson(CARD_LIST_ROUTE, "{}");

            assertMappedAs(result, HttpStatus.INTERNAL_SERVER_ERROR, TERMINAL_ABEND_TEXT);
            assertThat(bodyOf(result))
                    .as("a diagnostic composed for a job log must not be republished to a client")
                    .doesNotContain(OPERATOR_TEXT);
        }

        // The online routine sent its abend area to a terminal before abending, so text carried under the
        // online code was written to be read by a person and crosses the boundary as supplied.
        @Test
        @DisplayName("an online abend publishes its operator message exactly as supplied")
        void anOnlineAbendPublishesItsOperatorMessage() throws Exception {
            willThrow(new AbendException(AbendException.ONLINE_ABEND_CODE, CULPRIT, REASON, OPERATOR_TEXT))
                    .given(cardListService).processCardList(any());

            final MvcResult result = postJson(CARD_LIST_ROUTE, "{}");

            assertMappedAs(result, HttpStatus.INTERNAL_SERVER_ERROR, OPERATOR_TEXT);
        }

        // An abend that supplied no operator message at all substitutes the frozen literal, so the body is
        // never blank and never the word null.
        @Test
        @DisplayName("an abend that carries no operator message substitutes the frozen literal")
        void anAbendWithNoMessageSubstitutesTheFrozenLiteral() throws Exception {
            willThrow(new AbendException(CULPRIT, REASON))
                    .given(cardListService).processCardList(any());

            final MvcResult result = postJson(CARD_LIST_ROUTE, "{}");

            assertMappedAs(result, HttpStatus.INTERNAL_SERVER_ERROR, TERMINAL_ABEND_TEXT);
        }

        // The fixed-width abend context is the one thing that may never be rendered: the code, the
        // originating component and the reason are diagnostics and go to the log, and the image of all four
        // is never requested at all.
        @Test
        @DisplayName("the raw abend context, its width and its three diagnostic fields never reach the body")
        void theRawAbendContextNeverReachesTheBody() throws Exception {
            final AbendException carrier = new AbendException(AbendException.ONLINE_ABEND_CODE, CULPRIT,
                    REASON, OPERATOR_TEXT);
            willThrow(carrier).given(cardListService).processCardList(any());

            final String body = bodyOf(postJson(CARD_LIST_ROUTE, "{}"));

            assertThat(carrier.toFixedWidthContext())
                    .as("the legacy context is four fields wide in total")
                    .hasSize(ABEND_CONTEXT_WIDTH);
            assertThat(AbendException.CONTEXT_LENGTH).isEqualTo(ABEND_CONTEXT_WIDTH);
            assertThat(body)
                    .doesNotContain(carrier.toFixedWidthContext())
                    .doesNotContain(CULPRIT)
                    .doesNotContain(REASON)
                    .doesNotContain(AbendException.ONLINE_ABEND_CODE)
                    .doesNotContain(AbendException.BATCH_ABEND_CODE);
        }
    }

    // =============================================================================================
    // MAPPING 6 OF 6 - the non-fatal one
    // =============================================================================================

    @Nested
    @DisplayName("JobSubmission mapping - non-fatal")
    class JobSubmissionMapping {

        /** A response code the legacy queue write displayed to its console. */
        private static final String RESPONSE_CODE = "0016";

        /** A reason code the legacy queue write displayed alongside it. */
        private static final String REASON_CODE = "0002";

        /** The one-based ordinal of the card whose publish failed. */
        private static final int FAILED_CARD_ORDINAL = 12;

        /** Creates the group. */
        JobSubmissionMapping() {
            super();
        }

        // THE SURPRISING MAPPING, AND THE REASON IT IS CORRECT. The legacy destination is declared with
        // errors ignored: the program recorded the codes on its diagnostic channel, put the failure literal
        // on the screen, and returned control. The request completed on the mainframe, so it completes here,
        // and converting it into a server error would be a behavioural regression.
        @Test
        @DisplayName("a failed publish does not turn an accepted request into an HTTP failure")
        void aFailedPublishDoesNotBecomeAnHttpFailure() throws Exception {
            willThrow(submissionFailure())
                    .given(reportRequestService).processReportRequest(any(), any(), any());

            final MvcResult result = postJson(REPORT_REQUEST_ROUTE, "{}");

            assertMappedAs(result, HttpStatus.OK, JOB_SUBMISSION_FAILURE_TEXT);
        }

        // The carrier must not escape the request either: the caller sees a completed exchange, so the
        // dispatch resolved the failure rather than propagating it out.
        @Test
        @DisplayName("the carrier is resolved inside the dispatch and does not propagate out of the request")
        void theCarrierDoesNotPropagateOutOfTheRequest() throws Exception {
            willThrow(submissionFailure())
                    .given(reportRequestService).processReportRequest(any(), any(), any());

            final MvcResult result = postJson(REPORT_REQUEST_ROUTE, "{}");

            assertThat(result.getResolvedException())
                    .as("the boundary resolved the carrier rather than letting it escape")
                    .isInstanceOf(JobSubmissionException.class);
            assertThat(result.getResponse().getStatus())
                    .as("and the caller still sees a completed exchange")
                    .isEqualTo(HttpStatus.OK.value());
        }

        // The frozen literal, byte for byte: three separate full stops rather than an ellipsis, the queue
        // name parenthesised, and nothing appended. Nothing is trimmed and nothing is padded.
        @Test
        @DisplayName("the body carries the frozen failure literal byte for byte and untrimmed")
        void theBodyCarriesTheFrozenLiteralByteForByte() throws Exception {
            willThrow(submissionFailure())
                    .given(reportRequestService).processReportRequest(any(), any(), any());

            final String served = summaryOf(postJson(REPORT_REQUEST_ROUTE, "{}"));

            assertThat(served).isEqualTo(JOB_SUBMISSION_FAILURE_TEXT);
            assertThat(served)
                    .as("the literal ends in three separate full stops and carries no trailing space")
                    .endsWith("...")
                    .isEqualTo(served.strip())
                    .isEqualTo(JobSubmissionException.DEFAULT_MESSAGE);
        }

        // The carrier composes its own message as a diagnostic, appending the response and reason codes and
        // the failing card ordinal under the labels the legacy console used. Those belong on the log line,
        // and the boundary publishes the frozen literal alone instead.
        @Test
        @DisplayName("the response and reason codes the legacy displayed are absent from the body")
        void theDiagnosticCodesAreAbsentFromTheBody() throws Exception {
            final JobSubmissionException carrier = submissionFailure();
            willThrow(carrier).given(reportRequestService).processReportRequest(any(), any(), any());

            final String body = bodyOf(postJson(REPORT_REQUEST_ROUTE, "{}"));

            assertThat(carrier.getMessage())
                    .as("the carrier does compose them, which is why the boundary must not copy it")
                    .contains(RESPONSE_CODE, REASON_CODE);
            assertThat(body)
                    .doesNotContain(carrier.getMessage())
                    .doesNotContain(RESPONSE_CODE)
                    .doesNotContain(REASON_CODE)
                    .doesNotContain(Integer.toString(FAILED_CARD_ORDINAL))
                    .doesNotContain("RESP:")
                    .doesNotContain("REAS:")
                    .doesNotContain("CARD:");
        }

        /**
         * Builds the publish failure in its fullest form, so every diagnostic the boundary must withhold is
         * actually present on the carrier.
         *
         * @return the carrier, holding the queue, both codes and the failing card ordinal
         */
        private JobSubmissionException submissionFailure() {
            return new JobSubmissionException(JobSubmissionException.DEFAULT_QUEUE_NAME, RESPONSE_CODE,
                    REASON_CODE, FAILED_CARD_ORDINAL, null);
        }
    }


    // =============================================================================================
    // CROSS-CUTTING - the guarantee that has to hold for every one of the six
    // =============================================================================================

    @Nested
    @DisplayName("Non-leakage across all six")
    class NonLeakageAcrossAllSix {

        /** Creates the group. */
        NonLeakageAcrossAllSix() {
            super();
        }

        // The six mappings driven in one place, so a mapping cannot be added or changed without being held
        // to the guarantee. Each of the six is reached exactly as its own group reaches it, and every served
        // surface goes through the one helper.
        @Test
        @DisplayName("no mapping of the six discloses a stack, a type, SQL, a secret, a path or record bytes")
        void noMappingOfTheSixDisclosesAnything() throws Exception {
            for (final MvcResult served : everySurfacedMapping()) {
                assertNothingIsDisclosed(served);
            }
        }

        // The two carriers whose detail message is a diagnostic rather than operator text: the file-status
        // carrier renders the raw status with the legacy resource name, and the not-found carrier renders
        // its own type name. Neither message may ever be copied into a body.
        @Test
        @DisplayName("no mapping echoes a carrier's own diagnostic detail message")
        void noMappingEchoesACarriersDiagnosticMessage() throws Exception {
            final FileStatusException fileStatus = new FileStatusException("31", "OPEN", "TRANSACT");
            willThrow(fileStatus).given(cardListService).processCardList(any());
            assertThat(bodyOf(postJson(CARD_LIST_ROUTE, "{}")))
                    .doesNotContain(fileStatus.getMessage());

            final RecordNotFoundException notFound =
                    new RecordNotFoundException("Card", SEEDED_CARD_NUMBER, "CARDDAT");
            assertThat(bodyOf(get(BATCH_EXECUTION_ROUTE + ABSENT_EXECUTION_ID)))
                    .doesNotContain(notFound.getMessage());
        }

        // A business key can be a primary account number and the estate provides no field-level masking for
        // one, so no mapping may render one even when the carrier holds it.
        @Test
        @DisplayName("no mapping renders a business key, even one the carrier carries")
        void noMappingRendersABusinessKey() throws Exception {
            for (final MvcResult served : everySurfacedMapping()) {
                assertThat(bodyOf(served))
                        .doesNotContain(SEEDED_CARD_NUMBER)
                        .doesNotContain(SEEDED_ADMIN_ID);
            }
        }

        /**
         * Drives all six mappings and returns what each served.
         *
         * <p>Each is reached the way its own group reaches it, so this is the same six surfaces rather than a
         * second, easier set. The order is the order the class documents them in.
         *
         * @return one completed result per mapping, six in all
         * @throws Exception when a dispatch cannot be performed
         */
        private List<MvcResult> everySurfacedMapping() throws Exception {
            final List<MvcResult> served = new ArrayList<>(6);
            served.add(get(BATCH_EXECUTION_ROUTE + ABSENT_EXECUTION_ID));
            served.add(postJson(launchRoute(JOB_ACCEPTING_NO_PARAMETER),
                    "{\"interestParmDate\":\"2022061012\"}"));
            served.add(postJson(CARD_UPDATE_ROUTE,
                    "{\"concurrencyToken\":\"a-state-this-server-never-sealed\"}"));

            willThrow(new FileStatusException("31", "OPEN", "TRANSACT"))
                    .given(cardListService).processCardList(any());
            served.add(postJson(CARD_LIST_ROUTE, "{}"));

            willThrow(new AbendException("CBACT01C", "ACCOUNT FILE COULD NOT BE OPENED"))
                    .given(cardListService).processCardList(any());
            served.add(postJson(CARD_LIST_ROUTE, "{}"));

            willThrow(new JobSubmissionException(JobSubmissionException.DEFAULT_QUEUE_NAME, "0016",
                    "0002", 12, null))
                    .given(reportRequestService).processReportRequest(any(), any(), any());
            served.add(postJson(REPORT_REQUEST_ROUTE, "{}"));
            return served;
        }
    }

    @Nested
    @DisplayName("No problem-detail envelope")
    class NoProblemDetailEnvelope {

        /** Creates the group. */
        NoProblemDetailEnvelope() {
            super();
        }

        // The standard problem-detail representation is deliberately not enabled for this module, and the
        // advice does not inherit from the framework's own exception-handling base class, so nothing can
        // introduce one. The served representation is this module's own error contract and nothing else.
        @Test
        @DisplayName("every mapped body is this module's error contract, not a problem-detail document")
        void everyMappedBodyIsTheModulesOwnContract() throws Exception {
            for (final MvcResult served : threeRepresentativeMappings()) {
                assertThat(served.getResponse().getContentType())
                        .isNotNull()
                        .startsWith(JSON_CONTENT_TYPE)
                        .doesNotContain(PROBLEM_JSON_CONTENT_TYPE);
                assertThat(bodyOf(served))
                        .doesNotContain(PROBLEM_DETAIL_MEMBERS.toArray(new String[0]))
                        .contains("\"fieldErrors\"");
            }
        }

        // Absent optional members are omitted rather than rendered null, which is the module-wide
        // serialization posture and not a per-field override: the per-field list is always present and is
        // served as an empty array, so a client never has to test it for null.
        @Test
        @DisplayName("absent members are omitted while the per-field list is always served")
        void absentMembersAreOmittedAndTheFieldListIsAlwaysServed() throws Exception {
            final String body = bodyOf(get(BATCH_EXECUTION_ROUTE + ABSENT_EXECUTION_ID));

            assertThat(body)
                    .contains("\"fieldErrors\":[]")
                    .doesNotContain("\"focusScreenFieldId\"")
                    .doesNotContain(": null")
                    .doesNotContain(":null");
        }

        // The two-constant state enumeration is what a client binds to, so its names are part of the
        // contract and must be served as declared.
        @Test
        @DisplayName("the two field states are served under the names the contract declares")
        void theTwoFieldStatesAreServedUnderTheirDeclaredNames() throws Exception {
            willThrow(new ValidationException("Please correct the highlighted fields", List.of(
                    new ValidationException.FieldError("accountIdFilter", "ACCTSID",
                            ValidationException.FieldState.MISSING, null),
                    new ValidationException.FieldError("cardNumberFilter", "CARDSID",
                            ValidationException.FieldState.INVALID, null))))
                    .given(cardListService).processCardList(any());

            final String body = bodyOf(postJson(CARD_LIST_ROUTE, "{}"));

            assertThat(body)
                    .contains('"' + ErrorResponse.FieldState.MISSING.name() + '"')
                    .contains('"' + ErrorResponse.FieldState.INVALID.name() + '"');
            assertThat(ErrorResponse.FieldState.values())
                    .as("exactly two states, because the legacy screen told exactly two mistakes apart")
                    .hasSize(2);
        }

        /**
         * Three mappings that between them cover the three status classes the boundary answers with.
         *
         * @return one completed result per representative mapping
         * @throws Exception when a dispatch cannot be performed
         */
        private List<MvcResult> threeRepresentativeMappings() throws Exception {
            final List<MvcResult> served = new ArrayList<>(3);
            served.add(get(BATCH_EXECUTION_ROUTE + ABSENT_EXECUTION_ID));
            served.add(postJson(CARD_UPDATE_ROUTE,
                    "{\"concurrencyToken\":\"a-state-this-server-never-sealed\"}"));
            willThrow(new AbendException("CBACT01C", "ACCOUNT FILE COULD NOT BE OPENED"))
                    .given(cardListService).processCardList(any());
            served.add(postJson(CARD_LIST_ROUTE, "{}"));
            return served;
        }
    }

    @Nested
    @DisplayName("Padded catalogue messages not trimmed")
    class PaddedCatalogueMessages {

        /** Creates the group. */
        PaddedCatalogueMessages() {
            super();
        }

        // The two common messages are full space-padded field values, and the padding is contract rather
        // than incidental whitespace: the legacy moved a fixed-width field into a fixed-width screen field.
        @Test
        @DisplayName("the program-exit message is 43 visible characters and 7 trailing spaces, 50 in all")
        void theProgramExitMessageKeepsItsSevenTrailingSpaces() {
            final String padded = MessageCatalogService.CCDA_MSG_THANK_YOU;

            assertThat(padded).hasSize(COMMON_MESSAGE_WIDTH);
            assertThat(THANK_YOU_VISIBLE_TEXT).hasSize(43);
            assertThat(padded).isEqualTo(THANK_YOU_VISIBLE_TEXT + " ".repeat(7));
            assertThat(padded)
                    .as("trimming would change a fixed-width external value")
                    .isNotEqualTo(padded.strip());
        }

        // The companion message, at the same declared width with a different visible length, which is why
        // the two pad counts differ and neither may be derived from the other.
        @Test
        @DisplayName("the unmapped-key message is 40 visible characters and 10 trailing spaces, 50 in all")
        void theUnmappedKeyMessageKeepsItsTenTrailingSpaces() {
            final String padded = MessageCatalogService.CCDA_MSG_INVALID_KEY;

            assertThat(padded).hasSize(COMMON_MESSAGE_WIDTH);
            assertThat(INVALID_KEY_VISIBLE_TEXT).hasSize(40);
            assertThat(padded).isEqualTo(INVALID_KEY_VISIBLE_TEXT + " ".repeat(10));
            assertThat(padded).isNotEqualTo(padded.strip());
        }

        // The screen titles are a different group at a different width and must never be merged with the
        // messages above: one of them is a near-twin of the program-exit message with a different product
        // token and a different field width.
        @Test
        @DisplayName("the three screen titles are 40 characters each, distinct, and never trimmed")
        void theThreeScreenTitlesKeepTheirDeclaredWidth() {
            final List<String> titles = List.of(MessageCatalogService.CCDA_TITLE01,
                    MessageCatalogService.CCDA_TITLE02, MessageCatalogService.CCDA_THANK_YOU);

            assertThat(titles).allSatisfy(title ->
                    assertThat(title).hasSize(SCREEN_TITLE_WIDTH));
            assertThat(titles).doesNotHaveDuplicates();
            assertThat(MessageCatalogService.CCDA_THANK_YOU)
                    .as("the 40-character title and the 50-character message are separate values")
                    .isNotEqualTo(MessageCatalogService.CCDA_MSG_THANK_YOU);
        }

        // The catalogue values are ordinary screen results - a program exit and an unmapped key - and not
        // failures, so no mapping of the six may borrow one. Borrowing would report a screen outcome for a
        // failure and would also be the one place a padded value could reach a client trimmed.
        @Test
        @DisplayName("no mapping of the six borrows a catalogue message, padded or trimmed")
        void noMappingBorrowsACatalogueMessage() throws Exception {
            final MvcResult notFound = get(BATCH_EXECUTION_ROUTE + ABSENT_EXECUTION_ID);
            willThrow(new AbendException("CBACT01C", "ACCOUNT FILE COULD NOT BE OPENED"))
                    .given(cardListService).processCardList(any());
            final MvcResult abend = postJson(CARD_LIST_ROUTE, "{}");

            for (final MvcResult served : List.of(notFound, abend)) {
                assertThat(bodyOf(served))
                        .doesNotContain(MessageCatalogService.CCDA_MSG_THANK_YOU)
                        .doesNotContain(MessageCatalogService.CCDA_MSG_INVALID_KEY)
                        .doesNotContain(THANK_YOU_VISIBLE_TEXT)
                        .doesNotContain(INVALID_KEY_VISIBLE_TEXT);
            }
        }
    }

    @Nested
    @DisplayName("No seventh handler")
    class NoSeventhHandler {

        /** Creates the group. */
        NoSeventhHandler() {
            super();
        }

        // Six carriers and no seventh: a failure the boundary does not name must not be given one of the six
        // statuses. It reaches the terminal handler, which answers with the one terminal literal so that the
        // boundary has exactly one terminal text rather than one per cause.
        @Test
        @DisplayName("a failure the boundary does not name is not given any of the six mapped statuses")
        void anUnnamedFailureIsNotGivenAMappedStatus() throws Exception {
            willThrow(new IllegalStateException("a failure this boundary does not name"))
                    .given(cardListService).processCardList(any());

            final MvcResult result = postJson(CARD_LIST_ROUTE, "{}");

            assertMappedAs(result, HttpStatus.INTERNAL_SERVER_ERROR, TERMINAL_ABEND_TEXT);
            assertThat(result.getResponse().getStatus())
                    .isNotIn(HttpStatus.OK.value(), HttpStatus.BAD_REQUEST.value(),
                            HttpStatus.NOT_FOUND.value(), HttpStatus.CONFLICT.value());
        }

        // The terminal handler is not a catch-all that flattens everything into a server error. A framework
        // fault that classifies itself keeps the status it declared, with a neutral summary accurate for that
        // status, so a client fault is never reported as a server fault.
        @Test
        @DisplayName("a framework fault keeps the status it declared rather than being flattened to 500")
        void aFrameworkFaultKeepsItsOwnStatus() throws Exception {
            willThrow(new ResponseStatusException(HttpStatus.NOT_ACCEPTABLE))
                    .given(cardListService).processCardList(any());

            final MvcResult result = postJson(CARD_LIST_ROUTE, "{}");

            assertMappedAs(result, HttpStatus.NOT_ACCEPTABLE, REPRESENTATION_NOT_AVAILABLE_TEXT);
            assertThat(summaryOf(result))
                    .as("the summary is accurate for the status rather than fixed")
                    .isNotEqualTo(TERMINAL_ABEND_TEXT)
                    .isNotEqualTo(MALFORMED_REQUEST_BODY_TEXT);
        }

        // Each caller-caused rejection is answered by a handler naming its own concrete framework type, so a
        // body that could not be parsed reports exactly that rather than a generic refusal - and the parse
        // diagnostic, which quotes the offending fragment of the payload, is never surfaced.
        @Test
        @DisplayName("an unreadable body is answered by its own named handler and quotes no payload")
        void anUnreadableBodyIsAnsweredByItsOwnHandler() throws Exception {
            final MvcResult result = postJson(CARD_LIST_ROUTE, "{\"accountIdFilter\":");

            assertMappedAs(result, HttpStatus.BAD_REQUEST, MALFORMED_REQUEST_BODY_TEXT);
            assertThat(bodyOf(result))
                    .as("a body with no fields to attribute an error to reports none")
                    .contains("\"fieldErrors\":[]");
        }

        // A request value that will not convert names the parameter, because the name is part of this
        // module's own published interface, and reports the supplied-and-unusable state. The value that
        // failed to convert and the Java type it failed to convert to are both withheld.
        @Test
        @DisplayName("a value that will not convert names the parameter and withholds the value and its type")
        void aValueThatWillNotConvertNamesOnlyTheParameter() throws Exception {
            final MvcResult result = get(BATCH_EXECUTION_ROUTE + "not-a-number");

            assertMappedAs(result, HttpStatus.BAD_REQUEST, REQUEST_BINDING_FAILED_TEXT);
            assertThat(bodyOf(result))
                    .contains("\"fieldName\":\"executionId\"")
                    .contains("\"state\":\"INVALID\"")
                    .doesNotContain("not-a-number")
                    .doesNotContain("long");
        }
    }

    // =============================================================================================
    // READING THE SERVER BACK
    // =============================================================================================

    /**
     * Reads the stored image of the seeded card, so a refused turn can be shown to have written nothing.
     *
     * <p>Every mapped column is projected, including the optimistic-locking version, because a write that
     * changed one field would also have moved the version. The statement is a complete literal and the key
     * travels as a bound parameter.
     *
     * @return the projected column values as text, in projection order
     * @throws SQLException when the row cannot be read
     */
    private static List<String> storedCardImage() throws SQLException {
        final List<String> image = new ArrayList<>(6);
        try (Connection connection = connect();
                PreparedStatement query = connection.prepareStatement(SELECT_CARD_IMAGE)) {
            query.setString(1, SEEDED_CARD_NUMBER);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    for (int column = 1; column <= 6; column++) {
                        image.add(rows.getString(column));
                    }
                }
            }
        }
        return List.copyOf(image);
    }

    /**
     * Reads back which tables the migrated schema guards with an optimistic-locking version column.
     *
     * <p>Read from the live catalogue rather than restated, so a version column added to a third table
     * would fail here instead of quietly widening where a conflict can be provoked.
     *
     * @return the table names carrying a version column, in name order
     * @throws SQLException when the catalogue cannot be read
     */
    private static List<String> queryMigratedRecordTables(final String statement) throws SQLException {
        final List<String> names = new ArrayList<>(MIGRATED_RECORD_TABLES.size());
        try (Connection connection = connect();
                PreparedStatement query = connection.prepareStatement(statement)) {
            query.setArray(1, connection.createArrayOf("text",
                    MIGRATED_RECORD_TABLES.toArray(new String[0])));
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    names.add(rows.getString(1));
                }
            }
        }
        return List.copyOf(names);
    }

    // =============================================================================================
    // THE BOUNDARY UNDER TEST, ASSEMBLED EXPLICITLY
    // =============================================================================================

    /**
     * The failure boundary and the three delivered controllers that reach it, assembled by name.
     *
     * <p><strong>Explicit rather than scanned, and that is a correctness requirement.</strong> A component
     * scan of the base package reaches both compiled trees, so it would sweep the suite's own probe
     * controllers and slice configurations into the context beside the delivered ones. The graph below is
     * therefore stated in one place, and a reader can see exactly what took part.
     *
     * <p>Nothing about the boundary itself is stubbed: the advice, the neutral refusal contract, the request
     * validator with its fixed-locale interpolator, the security filter chain, the session token provider,
     * the card conversation-state service with its real field encryption, and the batch launch service are
     * all the shipped ones, and the identities come off a real server. What is stubbed is only what would
     * otherwise require half the application to be assembled: the three card screen services, the report
     * transaction, and the framework's own batch registry, metadata reader, operator and launch port. The
     * launch port is deliberately never stubbed to succeed, so no test in this class can start a job.
     *
     * <p>Batch auto-configuration is excluded so the four framework batch collaborators are exactly the
     * four supplied here rather than a mixture of supplied and auto-configured ones. The clock is the shared
     * pinned instant, so a session's issued-at and expiry images mean the same thing on every run.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {PrometheusExemplarsAutoConfiguration.class,
            BatchAutoConfiguration.class})
    @Import({GlobalExceptionHandler.class, JsonRefusalBodyRenderer.class, ModuleErrorController.class,
            BatchJobController.class, CardController.class, ReportController.class,
            ScreenStateAdapter.class, ConversationStateAdapter.class, ReportContractAdapter.class,
            BatchJobLaunchService.class, CardConcurrencyTokenService.class,
            SensitiveFieldEncryptionService.class, NavigationService.class, SignOnStateService.class,
            SecurityConfig.class, JwtTokenProvider.class, WebMvcConfig.class})
    @EnableConfigurationProperties(JwtProperties.class)
    @EnableJpaRepositories(basePackageClasses = UserSecurityRepository.class)
    @EntityScan(basePackageClasses = UserSecurity.class)
    static class BoundaryContext {

        /** Creates the slice. */
        BoundaryContext() {
            super();
        }

        /**
         * The pinned clock every collaborator in the graph reads.
         *
         * @return the shared fixed clock
         */
        @Bean
        Clock fixedClock() {
            return FIXED_CLOCK;
        }
    }
}

