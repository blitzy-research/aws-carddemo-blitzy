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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.carddemo.config.JwtProperties;
import com.carddemo.config.JwtTokenProvider;
import com.carddemo.config.SecurityConfig;
import com.carddemo.config.WebMvcConfig;
import com.carddemo.domain.UserSecurity;
import com.carddemo.domain.enums.ReportPeriod;
import com.carddemo.exception.JobSubmissionException;
import com.carddemo.repository.UserSecurityRepository;
import com.carddemo.service.DateValidationService;
import com.carddemo.service.JobSubmissionService;
import com.carddemo.service.MessageCatalogService;
import com.carddemo.service.NavigationService;
import com.carddemo.service.ReportRequestService;
import com.carddemo.service.SignOnStateService;
import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.support.TestDataFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
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
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The transaction-report request screen of legacy transaction {@code CR00}, driven over the shipped REST
 * boundary against a real relational server.
 *
 * <h2>What this specification is about</h2>
 * One endpoint, and four behaviours that a plausible, compiling, tidied-up implementation gets wrong:
 * <ul>
 *   <li><strong>A period vocabulary whose carried values are not its member names.</strong> The three
 *       members carry the bare mixed-case forms the operator reads back, seven, six and six characters
 *       long, and they are neither upper-cased nor padded out to the ten characters of the legacy work
 *       item that holds them. The wire discriminator and the operator-facing value are therefore two
 *       different strings, and both are asserted.</li>
 *   <li><strong>A fourteen-stage ordered validation carrying three distinct casing patterns.</strong>
 *       Six absence stages spell the negation word entirely in capitals; six range stages capitalise
 *       the calendar-unit word; two closing stages spell the calendar word entirely in lower case. The
 *       order is the contract - the legacy evaluates top down, sends, and ends the task at the first
 *       failure - so submissions invalid in two places are used to prove that only the earlier stage
 *       answers.</li>
 *   <li><strong>A silent refusal.</strong> A negative confirmation resets the screen and raises the
 *       error flag <em>with no message text at all</em>, so an error flag beside an empty message is a
 *       valid state and the flag can never be derived from a message being present. This is the single
 *       most likely place for an implementation to invent a cancellation message, so it has a test of
 *       its own that asserts the flag and the absent text in one block.</li>
 *   <li><strong>A non-fatal hand-over failure.</strong> The legacy queue is defined ignore-on-error, so
 *       a refused publish is reported to the operator and control returns normally. Both shapes are
 *       exercised - the bridge reporting the refusal as a value and the bridge raising - and both answer
 *       a completed exchange carrying the frozen failure text rather than a server failure.</li>
 * </ul>
 *
 * <h2>The scope boundary with the queue specification</h2>
 * This file asserts the <strong>request, validation, confirmation and acknowledgement</strong> contract.
 * The seventeen-card, byte-level, drain-the-real-queue contract belongs to {@code AwsIntegrationIT},
 * which extends the emulator-backed base and reads messages back out of the queue. Nothing here asserts
 * a card image, a card count, a substitution slot, a record width, an end-of-stream sentinel or any
 * resource name, and <strong>the production card builder is never called</strong>: an expectation
 * generated by the subject cannot fail when the subject is wrong. The hand-over is isolated behind a
 * mocked bridge, which is legitimate precisely because the real-queue obligation is discharged in the
 * file that owns it - and the bridge's accepting answer is derived from the very card stream it was
 * handed, so this file needs to know nothing about that stream to describe a complete publish.
 *
 * <h2>Where the expectations come from</h2>
 * Every operator-facing text, screen-field identifier, route name, blank width and header stamp below is
 * written out as this file's own literal rather than read from the component that also publishes it: an
 * expectation which borrows its subject's constant proves only that the subject agrees with itself.
 * Routes, verbs, media types, status codes, JSON property names and enumeration member names are taken
 * from the shipped boundary and its published contract types, because those are the shape this
 * specification has to speak to rather than claims it is making.
 *
 * <h2>Container ownership, determinism and credentials</h2>
 * The database server belongs to {@link AbstractPostgresIT} and is shared by every integration
 * specification in the module, so this class declares no container, no container annotation, no
 * data-source property source and no context-discarding annotation. Time comes from that class's pinned
 * clock, so the header stamp is the same on every run, nothing here reads a wall clock, and no assertion
 * depends on elapsed time, throughput or memory. No table is written, so no reset is needed.
 *
 * <p>No credential is read, held or rendered anywhere. A session is minted by asking the shipped token
 * provider directly, which is what the sign-on path itself does once it has verified a credential; the
 * provider refuses to mint for an identity no record carries, so a session existing at all is evidence
 * that the credential seed applied. The identity used throughout is a seeded <em>ordinary</em> one,
 * because the route is classified as reachable by any signed-on caller and an administrative session
 * would not prove that.
 *
 * <p>Provenance: {@code app/cbl/CORPT00C.cbl} with its symbolic map {@code app/cpy-bms/CORPT00.CPY} and
 * mapset {@code app/bms/CORPT00.bms}, the shared date subprogram {@code app/cbl/CSUTLDTC.cbl}, the
 * cataloged procedure {@code app/proc/TRANREPT.prc}, the {@code CR00} transaction and queue definitions
 * in {@code app/csd/CARDDEMO.CSD}, and the communication area {@code app/cpy/COCOM01Y.cpy} - all read as
 * read-only reference. Message texts, widths, field names, paragraph names and line numbers are external
 * contract and metadata; no legacy source line is transcribed, and nothing under {@code app/} is read at
 * run time.
 */
@SpringBootTest(classes = ReportControllerIT.ReportRequestContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            // The shared server was migrated to the head of the delivered set by the base class, so a
            // second migration from this context would be redundant work with no new state to apply.
            "spring.flyway.enabled=false",
            // validate, never none and never a generating value: a mapping that had drifted from the
            // migrated schema must fail this specification at refresh rather than be reconciled quietly.
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.batch.job.enabled=false",
            "spring.main.banner-mode=off",
            "management.endpoint.health.validate-group-membership=false",
            "management.tracing.enabled=false",
            // The boundary here is a mock servlet inside this process, so there is no wire for a session
            // to be observed on and the transport requirement is relaxed exactly as the suite profile
            // relaxes it. Nothing else is relaxed: the anonymous set and the authenticated catch-all are
            // the shipped ones, which is the whole point of asserting against them.
            "carddemo.security.require-https=false",
            "carddemo.security.jwt.issuer=carddemo-java",
            "carddemo.security.jwt.expiration=PT15M"})
@AutoConfigureMockMvc
@DisplayName("Transaction CR00 :: the report-request screen over the shipped boundary")
public class ReportControllerIT extends AbstractPostgresIT {

    // ===============================================================================================
    // THE CONTRACT, RESTATED HERE RATHER THAN IMPORTED FROM THE SUBJECT
    //
    // Every literal below is written out here. Each is an operator-facing string, a screen-field name or
    // a fixed width whose bytes are the contract, and an expectation that borrowed the constant the
    // subject publishes would pass even when the subject's own value had drifted.
    // ===============================================================================================

    /** First absence stage, {@code app/cbl/CORPT00C.cbl} line 261. The negation word is capitalised. */
    private static final String MSG_START_MONTH_EMPTY = "Start Date - Month can NOT be empty...";

    /** Second absence stage, line 268. */
    private static final String MSG_START_DAY_EMPTY = "Start Date - Day can NOT be empty...";

    /** Third absence stage, line 275. */
    private static final String MSG_START_YEAR_EMPTY = "Start Date - Year can NOT be empty...";

    /** Fourth absence stage, line 282. */
    private static final String MSG_END_MONTH_EMPTY = "End Date - Month can NOT be empty...";

    /** Fifth absence stage, line 289. */
    private static final String MSG_END_DAY_EMPTY = "End Date - Day can NOT be empty...";

    /** Sixth absence stage, line 296. */
    private static final String MSG_END_YEAR_EMPTY = "End Date - Year can NOT be empty...";

    /** First range stage, line 331. The calendar-unit word is capitalised and the negation is not. */
    private static final String MSG_START_MONTH_INVALID = "Start Date - Not a valid Month...";

    /** Second range stage, line 340. */
    private static final String MSG_START_DAY_INVALID = "Start Date - Not a valid Day...";

    /** Third range stage, line 348. */
    private static final String MSG_START_YEAR_INVALID = "Start Date - Not a valid Year...";

    /** Fourth range stage, line 357. */
    private static final String MSG_END_MONTH_INVALID = "End Date - Not a valid Month...";

    /** Fifth range stage, line 366. */
    private static final String MSG_END_DAY_INVALID = "End Date - Not a valid Day...";

    /** Sixth range stage, line 374. */
    private static final String MSG_END_YEAR_INVALID = "End Date - Not a valid Year...";

    /** First closing stage, line 400. The calendar word is entirely lower case here. */
    private static final String MSG_START_DATE_INVALID = "Start Date - Not a valid date...";

    /** Second closing stage, line 420. */
    private static final String MSG_END_DATE_INVALID = "End Date - Not a valid date...";

    /** The catch-all of the ordered report-type evaluation, line 438. */
    private static final String MSG_SELECT_REPORT_TYPE = "Select a report type to print report...";

    /** Leading fragment of the confirmation prompt, line 466. It ends with a separating space. */
    private static final String CONFIRM_PROMPT_PREFIX = "Please confirm to print the ";

    /**
     * Trailing fragment of the confirmation prompt, line 469: a leading space and <em>no</em> space
     * before its three dots. One byte apart from {@link #SUBMITTED_SUFFIX} and never interchangeable.
     */
    private static final String CONFIRM_PROMPT_SUFFIX = " report...";

    /**
     * Trailing fragment of the acknowledgement, lines 449 and 450: a leading space <em>and</em> a space
     * before its three dots.
     */
    private static final String SUBMITTED_SUFFIX = " report submitted for printing ...";

    /** Opening fragment of the unrecognised-confirmation text, line 486: one straight double quote. */
    private static final String INVALID_CONFIRM_PREFIX = "\"";

    /** Closing fragment of the unrecognised-confirmation text, line 488. */
    private static final String INVALID_CONFIRM_SUFFIX = "\" is not a valid value to confirm...";

    /**
     * The month-to-date report name, moved into the ten-character report-name work item at line 214.
     * Seven characters, capital initial, lower-case remainder, and <strong>not</strong> padded out to
     * the width of the item that holds it, because both read sites consume it up to its first space.
     */
    private static final String PERIOD_VALUE_MONTHLY = "Monthly";

    /** The year-to-date report name, moved at line 240. Six characters, on the same terms. */
    private static final String PERIOD_VALUE_YEARLY = "Yearly";

    /** The operator-range report name, moved at line 433. Six characters, on the same terms. */
    private static final String PERIOD_VALUE_CUSTOM = "Custom";

    /**
     * The wire discriminator a resolved month-to-date period is published under, which is the member's
     * own identifier and is deliberately <em>not</em> the value above. Written out as a literal so that
     * the difference between the two is asserted rather than assumed.
     */
    private static final String PERIOD_TOKEN_MONTHLY = "MONTHLY";

    /** The wire discriminator of a resolved year-to-date period. */
    private static final String PERIOD_TOKEN_YEARLY = "YEARLY";

    /** The wire discriminator of a resolved operator-range period. */
    private static final String PERIOD_TOKEN_CUSTOM = "CUSTOM";

    /** Members the period vocabulary declares: three, with none standing for absence. */
    private static final int PERIOD_MEMBER_COUNT = 3;

    /** Width of the report-name work item the three values are held in, which they are not padded to. */
    private static final int REPORT_NAME_ITEM_WIDTH = 10;

    /** The first report-type position, and the field the cursor returns to on a whole-screen fault. */
    private static final String FIELD_MONTHLY = "MONTHLY";

    /** The start-date month position. */
    private static final String FIELD_START_MONTH = "SDTMM";

    /** The start-date day position. */
    private static final String FIELD_START_DAY = "SDTDD";

    /** The start-date year position. */
    private static final String FIELD_START_YEAR = "SDTYYYY";

    /** The end-date month position. */
    private static final String FIELD_END_MONTH = "EDTMM";

    /** The end-date day position. */
    private static final String FIELD_END_DAY = "EDTDD";

    /** The end-date year position. */
    private static final String FIELD_END_YEAR = "EDTYYYY";

    /** The confirmation position. */
    private static final String FIELD_CONFIRM = "CONFIRM";

    /** Bound property of the report-type evaluation's own fault. */
    private static final String PROPERTY_REPORT_TYPE = "reportType";

    /** Bound property of the start-date month position. */
    private static final String PROPERTY_START_MONTH = "startMonth";

    /** Bound property of the assembled start date. */
    private static final String PROPERTY_START_DATE = "startDate";

    /** A position the operator left blank: highlighted and marked. */
    private static final String STATE_MISSING = "MISSING";

    /** A position the operator supplied that failed its edit: highlighted only. */
    private static final String STATE_INVALID = "INVALID";

    /** A report-type or confirmation position as the screen re-presents it when cleared: one space. */
    private static final String BLANK_SELECTION = " ";

    /** A month or day position as the screen re-presents it when cleared: two spaces. */
    private static final String BLANK_MONTH_DAY = "  ";

    /** A year position as the screen re-presents it when cleared: four spaces. */
    private static final String BLANK_YEAR = "    ";

    /** The destination this screen re-arms, since every re-presentation returns to the same screen. */
    private static final String REPORT_REQUEST_ROUTE_VALUE = "report-request";

    /**
     * Where a turn carrying no conversation state at all is sent, from the arm at lines 172 to 174: the
     * screen's own default destination when the communication area is empty.
     */
    private static final String SIGN_ON_ROUTE_VALUE = "sign-on";

    /** The transaction identifier stamped into the header, from the CICS definition at line 409. */
    private static final String TRANSACTION_ID = "CR00";

    /** The program name stamped into the header. */
    private static final String PROGRAM_NAME = "CORPT00C";

    /** The header date the pinned clock produces, in the screen's own month-day-year form. */
    private static final String PINNED_HEADER_DATE = "06/10/22";

    /** The header time the pinned clock produces. */
    private static final String PINNED_HEADER_TIME = "19:27:53";

    /** Width of the outbound message field the eighty-character work field is moved into at line 560. */
    private static final int OUTBOUND_MESSAGE_WIDTH = 78;

    /** Any non-blank character marks a report-type position, because only non-blankness is tested. */
    private static final String MARK = "S";

    /** The affirmative confirmation, upper case, line 478. */
    private static final String CONFIRM_YES = "Y";

    /** The affirmative confirmation, lower case, line 478. */
    private static final String CONFIRM_YES_LOWER = "y";

    /** The negative confirmation, upper case, line 480 - the silent refusal. */
    private static final String CONFIRM_NO = "N";

    /** The negative confirmation, lower case, line 480. */
    private static final String CONFIRM_NO_LOWER = "n";

    /**
     * A deliberately odd third confirmation value: neither affirmative nor negative in either case, and
     * not a letter at all, so the catch-all arm at line 484 is reached for a reason no fold could
     * explain away.
     */
    private static final String CONFIRM_ODD = "?";

    /** Presentation prefix of a session on the authorization header. */
    private static final String BEARER_PREFIX = "Bearer ";

    /** The attention key that drives the request, from the key evaluation at line 184. */
    private static final String KEY_ENTER = "ENTER";

    /** The program-context member standing for a re-entered turn, which opens the validation gate. */
    private static final String CONTEXT_REENTER = "REENTER";

    /** The program-context member standing for a first entry, which serves the screen and nothing else. */
    private static final String CONTEXT_ENTER = "ENTER";

    // Request property names, taken from the shipped request contract rather than restated as claims.
    private static final String P_MONTHLY = "monthlySelection";
    private static final String P_YEARLY = "yearlySelection";
    private static final String P_CUSTOM = "customSelection";
    private static final String P_START_MONTH = "startMonth";
    private static final String P_START_DAY = "startDay";
    private static final String P_START_YEAR = "startYear";
    private static final String P_END_MONTH = "endMonth";
    private static final String P_END_DAY = "endDay";
    private static final String P_END_YEAR = "endYear";
    private static final String P_CONFIRM = "confirm";
    private static final String P_KEY_ACTION = "keyAction";
    private static final String P_NAVIGATION = "navigationContext";
    private static final String P_PROGRAM_CONTEXT = "programContext";
    private static final String P_FROM_TRANSACTION = "fromTransactionId";
    private static final String P_FROM_PROGRAM = "fromProgram";

    // Response property names, likewise taken from the shipped response contract.
    private static final String R_REPORT_PERIOD = "reportPeriod";
    private static final String R_MESSAGE = "message";
    private static final String R_GENERAL_ERROR = "generalError";
    private static final String R_SUBMISSION_ACCEPTED = "submissionAccepted";
    private static final String R_FOCUS_FIELD = "focusScreenFieldId";
    private static final String R_NEXT_ROUTE = "nextRoute";
    private static final String R_FIELD_ERRORS = "fieldErrors";
    private static final String R_ERROR_MESSAGE = "errorMessage";
    private static final String R_TRANSACTION_NAME = "transactionName";
    private static final String R_PROGRAM_NAME = "programName";
    private static final String R_CURRENT_DATE = "currentDate";
    private static final String R_CURRENT_TIME = "currentTime";

    /** Reader for the served bodies. Owned here so no test parses a payload by hand. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * A seeded identity carrying the ordinary role code, chosen from the delivered ten by role rather
     * than by name so that the choice cannot silently become an administrative one.
     */
    private static final TestDataFactory.SeededIdentity ORDINARY_IDENTITY =
            TestDataFactory.SEEDED_IDENTITIES.stream()
                    .filter(identity -> !identity.isAdministrator())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "the delivered identities carry no ordinary role code"));

    /** The shipped boundary, driven in process. */
    @Autowired
    private MockMvc client;

    /** The shipped session issuer, asked directly so that no credential is needed. */
    @Autowired
    private JwtTokenProvider tokenProvider;

    /**
     * The hand-over boundary, and the only collaborator that is not the shipped one.
     *
     * <p>Publishing is a boundary rather than a decision, and the obligation to exercise it against a
     * real queue belongs to the specification that extends the emulator-backed base. Here it is isolated
     * so that the request, validation, confirmation and acknowledgement contract can be driven without
     * standing up a queue - and its accepting answer is derived from the card stream it is handed, so
     * this file holds no card count, no card image and no resource name of any kind.
     */
    @MockitoBean
    private JobSubmissionService jobSubmissionService;

    /** Creates the specification. */
    public ReportControllerIT() {
        super();
    }

    /**
     * Lets the hand-over accept whatever it is handed, so that every test which is not about a refused
     * publish observes a complete submission.
     *
     * <p>The answer reports the queue as having accepted exactly the number of cards it was given,
     * which is what a complete publish is, and it reads that number off the invocation rather than
     * restating it. Nothing about the stream's content, width or ordering is read, asserted or required.
     */
    @BeforeEach
    void acceptEveryHandOver() {
        given(this.jobSubmissionService.submitCanonicalJobImage(anyString(), anyList()))
                .willAnswer(invocation -> {
                    final int handedOver = ((List<?>) invocation.getArguments()[1]).size();
                    return new JobSubmissionService.SubmissionResult(handedOver, handedOver, false, "");
                });
    }

    // ===============================================================================================
    // SHARED MACHINERY
    // ===============================================================================================

    /**
     * Mints a session for the seeded ordinary identity, without naming a credential.
     *
     * <p>The delivered provider is asked directly, which is what the sign-on path does once it has
     * verified a credential. The provider refuses to mint for an identity no record carries, so a session
     * existing at all is evidence that the credential seed applied; both the issue and the later
     * verification read the pinned clock, so the session neither expires nor depends on when this runs.
     *
     * @return the value of the authorization header, including its presentation prefix
     */
    private String ordinarySession() {
        return BEARER_PREFIX + this.tokenProvider.issue(ORDINARY_IDENTITY.userId(),
                ORDINARY_IDENTITY.userType(), ORDINARY_IDENTITY.userTypeCode());
    }

    /**
     * Submits one screen over the boundary as the ordinary signed-on caller.
     *
     * @param  screen the transmitted screen, absent positions simply left out
     * @return the completed exchange
     * @throws Exception if the boundary cannot be reached
     */
    private MvcResult submit(final Map<String, Object> screen) throws Exception {
        return this.client.perform(MockMvcRequestBuilders.post(ReportController.REPORT_REQUEST_PATH)
                        .header(HttpHeaders.AUTHORIZATION, ordinarySession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(screen)))
                .andReturn();
    }

    /**
     * Submits one screen with the served body parsed, having first insisted that the exchange completed.
     *
     * <p>Every outcome this screen can reach is a screen the legacy composed and sent, so a status other
     * than a completed exchange is itself the failure and is reported here rather than in each test.
     *
     * @param  screen the transmitted screen
     * @return the parsed screen the turn produced
     * @throws Exception if the boundary cannot be reached or the body cannot be parsed
     */
    private JsonNode servedScreen(final Map<String, Object> screen) throws Exception {
        final MvcResult result = submit(screen);
        assertThat(result.getResponse().getStatus())
                .as("every screen this transaction composes is a completed exchange, so the outcome is "
                        + "read from the body exactly as an operator read it from the screen")
                .isEqualTo(HttpStatus.OK.value());
        return JSON.readTree(bodyOf(result));
    }

    /**
     * @param  result the completed exchange
     * @return the served body, decoded as text
     * @throws Exception if the body cannot be read
     */
    private static String bodyOf(final MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * Reads one text component, treating an absent and a null component alike as no text at all.
     *
     * <p>Absence and emptiness are deliberately not distinguished here: the module omits null components,
     * so the silent refusal can publish either an empty text or none, and both are "no message".
     *
     * @param  screen   the parsed screen
     * @param  property the component to read
     * @return the text, or the empty string when the component carries none
     */
    private static String textOf(final JsonNode screen, final String property) {
        final JsonNode component = screen.get(property);
        return component == null || component.isNull() ? "" : component.asText();
    }

    /**
     * Reads one flag, which must be published explicitly rather than inferred.
     *
     * @param  screen   the parsed screen
     * @param  property the flag to read
     * @return the value the boundary published
     */
    private static boolean flagOf(final JsonNode screen, final String property) {
        assertThat(screen.hasNonNull(property))
                .as("the %s flag is a component of its own and is never inferred from anything else",
                        property)
                .isTrue();
        return screen.get(property).asBoolean();
    }

    /**
     * Starts a re-entered turn driven by the enter key, which is the state in which the legacy validates
     * and submits: the re-enter gate at lines 177 to 183 admits the enter-key paragraph only on a turn
     * that carries a re-entered program context.
     *
     * @return a mutable screen carrying the attention key and the echoed context
     */
    private static Map<String, Object> reEnteredTurn() {
        final Map<String, Object> screen = new LinkedHashMap<>();
        screen.put(P_KEY_ACTION, KEY_ENTER);
        screen.put(P_NAVIGATION, Map.of(P_PROGRAM_CONTEXT, CONTEXT_REENTER,
                P_FROM_TRANSACTION, TRANSACTION_ID, P_FROM_PROGRAM, PROGRAM_NAME));
        return screen;
    }

    /**
     * A first-entry turn carrying conversation state: the screen has been reached, but the program-context
     * flag is not yet set, which is the state the gate at line 177 answers by serving the screen.
     *
     * <p>The routing members are carried because a state whose every member is unset is not a first entry
     * at all - it is the empty communication area the arm at lines 172 to 174 sends back to sign-on, which
     * is a different behaviour and has its own test.
     *
     * @return a mutable screen carrying the attention key and a first-entry context
     */
    private static Map<String, Object> firstEntryTurn() {
        final Map<String, Object> screen = new LinkedHashMap<>();
        screen.put(P_KEY_ACTION, KEY_ENTER);
        screen.put(P_NAVIGATION, Map.of(P_PROGRAM_CONTEXT, CONTEXT_ENTER,
                P_FROM_TRANSACTION, TRANSACTION_ID, P_FROM_PROGRAM, PROGRAM_NAME));
        return screen;
    }

    /**
     * A re-entered turn marking the month-to-date position, which validates nothing and derives its own
     * range from the current date at lines 215 to 236.
     *
     * @param  confirm the confirmation character, or {@code null} for a position not transmitted
     * @return the screen
     */
    private static Map<String, Object> monthlyTurn(final String confirm) {
        final Map<String, Object> screen = reEnteredTurn();
        screen.put(P_MONTHLY, MARK);
        putWhenTransmitted(screen, P_CONFIRM, confirm);
        return screen;
    }

    /**
     * A re-entered turn marking the year-to-date position, lines 239 to 255.
     *
     * @param  confirm the confirmation character, or {@code null} for a position not transmitted
     * @return the screen
     */
    private static Map<String, Object> yearlyTurn(final String confirm) {
        final Map<String, Object> screen = reEnteredTurn();
        screen.put(P_YEARLY, MARK);
        putWhenTransmitted(screen, P_CONFIRM, confirm);
        return screen;
    }

    /**
     * A re-entered turn marking the operator-range position, which is the only arm that validates.
     *
     * <p>Each date part is carried exactly as given, and {@code null} means the position was not
     * transmitted at all - which the legacy absence test treats identically to a position holding
     * spaces.
     *
     * @param  startMonth the start month, or {@code null}
     * @param  startDay   the start day, or {@code null}
     * @param  startYear  the start year, or {@code null}
     * @param  endMonth   the end month, or {@code null}
     * @param  endDay     the end day, or {@code null}
     * @param  endYear    the end year, or {@code null}
     * @param  confirm    the confirmation character, or {@code null}
     * @return the screen
     */
    private static Map<String, Object> customTurn(final String startMonth, final String startDay,
            final String startYear, final String endMonth, final String endDay, final String endYear,
            final String confirm) {
        final Map<String, Object> screen = reEnteredTurn();
        screen.put(P_CUSTOM, MARK);
        putWhenTransmitted(screen, P_START_MONTH, startMonth);
        putWhenTransmitted(screen, P_START_DAY, startDay);
        putWhenTransmitted(screen, P_START_YEAR, startYear);
        putWhenTransmitted(screen, P_END_MONTH, endMonth);
        putWhenTransmitted(screen, P_END_DAY, endDay);
        putWhenTransmitted(screen, P_END_YEAR, endYear);
        putWhenTransmitted(screen, P_CONFIRM, confirm);
        return screen;
    }

    /**
     * A complete, well-formed operator-supplied range, used wherever the range itself is not the subject.
     *
     * @param  confirm the confirmation character, or {@code null}
     * @return the screen
     */
    private static Map<String, Object> validCustomTurn(final String confirm) {
        return customTurn("06", "01", "2022", "06", "30", "2022", confirm);
    }

    /**
     * Records a transmitted position, leaving the component out entirely when nothing was transmitted.
     *
     * @param screen   the screen under assembly
     * @param property the component
     * @param value    the transmitted value, or {@code null}
     */
    private static void putWhenTransmitted(final Map<String, Object> screen, final String property,
            final String value) {
        if (value != null) {
            screen.put(property, value);
        }
    }

    /**
     * Insists that nothing was handed to the queue bridge on this turn.
     *
     * <p>Used wherever the legacy skips the emitting loop entirely: a rejected date part, an unmarked
     * report type, a blank confirmation, a declined confirmation and an unrecognised one all reach the
     * loop guard with the error flag already raised, so not one card is written.
     */
    private void assertNothingWasHandedOver() {
        verify(this.jobSubmissionService, never()).submitCanonicalJobImage(anyString(), anyList());
    }

    /**
     * Insists that a served body discloses nothing beyond what the screen itself says.
     *
     * <p>The legacy writes the raw response and reason codes of a refused queue write to its diagnostic
     * channel; neither may reach a caller, and neither may any framework, provider or schema detail.
     *
     * @param body the served body
     */
    private static void assertNothingIsDisclosed(final String body) {
        assertThat(body)
                .as("no failure type, stack frame, provider or schema detail reaches a caller")
                .doesNotContain("Exception")
                .doesNotContain("at com.carddemo")
                .doesNotContain("org.springframework")
                .doesNotContain("org.postgresql")
                .doesNotContain("user_security")
                .doesNotContain("select ");
        assertThat(body)
                .as("nor the raw response and reason codes the legacy writes to its diagnostic channel")
                .doesNotContain("RESP")
                .doesNotContain("REAS")
                .doesNotContain("DFHRESP");
        assertThat(body.toLowerCase(Locale.ROOT))
                .as("and nothing resembling a stored credential or a signing secret")
                .doesNotContain("password")
                .doesNotContain("secret")
                .doesNotContain("$2a$")
                .doesNotContain("$2b$");
    }

    // ===============================================================================================
    // GROUP 1 :: THE PERIOD VALUES ARE NOT THE MEMBER NAMES
    // ===============================================================================================

    /**
     * The three report names the legacy moves into its ten-character report-name work item, and the fact
     * that they are neither the member identifiers nor padded to that item's width.
     */
    @Nested
    @DisplayName("Period enum values are not names")
    class PeriodValuesAreNotNames {

        /** Creates the group. */
        PeriodValuesAreNotNames() {
            super();
        }

        @Test
        @DisplayName("each period carries the bare mixed-case report name, not its member identifier and "
                + "not a value padded to the work item's width")
        void eachPeriodCarriesTheBareMixedCaseReportName() {
            // Lines 214, 240 and 433 move these three literals into a ten-character work item; both read
            // sites, lines 449 and 468, consume it up to its first space, so the padding never reaches an
            // operator while the casing always does.
            assertThat(ReportPeriod.MONTHLY.getValue())
                    .as("the month-to-date report name as the operator reads it back")
                    .isEqualTo(PERIOD_VALUE_MONTHLY)
                    .hasSize(7)
                    .isNotEqualTo(PERIOD_TOKEN_MONTHLY)
                    .doesNotContain(BLANK_SELECTION)
                    .hasSizeLessThan(REPORT_NAME_ITEM_WIDTH);
            assertThat(ReportPeriod.YEARLY.getValue())
                    .isEqualTo(PERIOD_VALUE_YEARLY)
                    .hasSize(6)
                    .isNotEqualTo(PERIOD_TOKEN_YEARLY)
                    .doesNotContain(BLANK_SELECTION)
                    .hasSizeLessThan(REPORT_NAME_ITEM_WIDTH);
            assertThat(ReportPeriod.CUSTOM.getValue())
                    .isEqualTo(PERIOD_VALUE_CUSTOM)
                    .hasSize(6)
                    .isNotEqualTo(PERIOD_TOKEN_CUSTOM)
                    .doesNotContain(BLANK_SELECTION)
                    .hasSizeLessThan(REPORT_NAME_ITEM_WIDTH);
        }

        @Test
        @DisplayName("the vocabulary declares three members and none of them stands for an unmarked screen")
        void theVocabularyDeclaresThreeMembersAndNoneForAbsence() {
            // The catch-all arm at line 437 is an input-validation outcome, not a fourth report type, so
            // an unmarked screen is represented by absence and never by a synthetic member.
            assertThat(ReportPeriod.values()).hasSize(PERIOD_MEMBER_COUNT);
            assertThat(ReportPeriod.fromValue(""))
                    .as("an empty report name resolves to nothing rather than to a member")
                    .isEmpty();
            assertThat(ReportPeriod.fromValue(PERIOD_TOKEN_MONTHLY))
                    .as("the member identifier is not one of the carried values, so it resolves to "
                            + "nothing - which is the whole of the distinction this group exists for")
                    .isEmpty();
        }

        @Test
        @DisplayName("the acknowledgement is composed from the carried value, so the operator reads the "
                + "mixed-case name and never the member identifier")
        void theAcknowledgementIsComposedFromTheCarriedValue() throws Exception {
            final JsonNode screen = servedScreen(monthlyTurn(CONFIRM_YES));

            assertThat(textOf(screen, R_MESSAGE))
                    .isEqualTo(PERIOD_VALUE_MONTHLY + SUBMITTED_SUFFIX)
                    .doesNotContain(PERIOD_TOKEN_MONTHLY);
        }

        @Test
        @DisplayName("the resolved period is published under its member identifier while the same turn's "
                + "message carries the mixed-case value, so the two are provably different strings")
        void theWireDiscriminatorAndTheCarriedValueAreDifferentStrings() throws Exception {
            final JsonNode screen = servedScreen(yearlyTurn(CONFIRM_YES));

            assertThat(textOf(screen, R_REPORT_PERIOD))
                    .as("the discriminator a client switches on")
                    .isEqualTo(PERIOD_TOKEN_YEARLY);
            assertThat(textOf(screen, R_MESSAGE))
                    .as("the text an operator reads, composed from the carried value")
                    .isEqualTo(PERIOD_VALUE_YEARLY + SUBMITTED_SUFFIX);
            assertThat(textOf(screen, R_REPORT_PERIOD))
                    .isNotEqualTo(ReportPeriod.YEARLY.getValue());
        }

        @Test
        @DisplayName("a turn that marked no report type publishes no period at all")
        void aTurnThatMarkedNoReportTypePublishesNoPeriod() throws Exception {
            final JsonNode screen = servedScreen(reEnteredTurn());

            assertThat(screen.has(R_REPORT_PERIOD))
                    .as("absence is published as absence, since the module omits null components")
                    .isFalse();
            assertThat(textOf(screen, R_MESSAGE)).isEqualTo(MSG_SELECT_REPORT_TYPE);
        }
    }

    // ===============================================================================================
    // GROUP 2 :: THE SIX ABSENCE STAGES, IN ORDER, WITH THE NEGATION WORD CAPITALISED
    // ===============================================================================================

    /**
     * The first six stages of the ordered validation at lines 258 to 303. One evaluation, six arms,
     * evaluated top down and stopping at the first match, so at most one of the six ever answers - and
     * every one of the six spells the negation word entirely in capitals.
     */
    @Nested
    @DisplayName("Emptiness cascade order")
    class EmptinessCascadeOrder {

        /** Creates the group. */
        EmptinessCascadeOrder() {
            super();
        }

        @Test
        @DisplayName("a first entry validates nothing and submits nothing, because the re-enter gate at "
                + "line 177 admits the enter-key paragraph only on a re-entered turn")
        void aFirstEntryValidatesNothingAndSubmitsNothing() throws Exception {
            // The operator-range position is marked and every date position is blank, which on a
            // re-entered turn is the first absence stage. On a first entry the gate is closed, so the
            // screen is simply served: no stage runs, no text is composed and nothing is handed over.
            final Map<String, Object> firstEntry = firstEntryTurn();
            firstEntry.put(P_CUSTOM, MARK);

            final JsonNode screen = servedScreen(firstEntry);

            assertThat(textOf(screen, R_MESSAGE))
                    .as("the screen is served with nothing to say")
                    .isEmpty();
            assertThat(textOf(screen, R_MESSAGE))
                    .as("and in particular the first absence stage did not run")
                    .isNotEqualTo(MSG_START_MONTH_EMPTY);
            assertThat(flagOf(screen, R_GENERAL_ERROR)).isFalse();
            assertThat(textOf(screen, R_FOCUS_FIELD))
                    .as("the cursor is placed on the first report-type position, per line 180")
                    .isEqualTo(FIELD_MONTHLY);
            assertThat(textOf(screen, R_NEXT_ROUTE))
                    .as("and the turn stays on this screen")
                    .isEqualTo(REPORT_REQUEST_ROUTE_VALUE);
            assertNothingWasHandedOver();
        }

        @Test
        @DisplayName("a turn carrying no conversation state at all is sent back to sign-on rather than "
                + "validated, which is the empty-communication-area arm at line 172")
        void aTurnCarryingNoConversationStateIsSentBackToSignOn() throws Exception {
            final Map<String, Object> stateless = new LinkedHashMap<>();
            stateless.put(P_KEY_ACTION, KEY_ENTER);
            stateless.put(P_CUSTOM, MARK);

            final JsonNode screen = servedScreen(stateless);

            assertThat(textOf(screen, R_NEXT_ROUTE)).isEqualTo(SIGN_ON_ROUTE_VALUE);
            assertThat(textOf(screen, R_MESSAGE)).isEmpty();
            assertThat(flagOf(screen, R_GENERAL_ERROR)).isFalse();
            assertNothingWasHandedOver();
        }

        @Test
        @DisplayName("with every date part blank the start month answers first, and it answers as the "
                + "position the operator did not supply")
        void theStartMonthAbsenceAnswersFirst() throws Exception {
            final JsonNode screen =
                    servedScreen(customTurn(null, null, null, null, null, null, CONFIRM_YES));

            assertThat(textOf(screen, R_MESSAGE)).isEqualTo(MSG_START_MONTH_EMPTY);
            assertThat(flagOf(screen, R_GENERAL_ERROR)).isTrue();
            assertThat(textOf(screen, R_FOCUS_FIELD)).isEqualTo(FIELD_START_MONTH);
            assertThat(screen.get(R_FIELD_ERRORS).size()).isEqualTo(1);
            final JsonNode faulted = screen.get(R_FIELD_ERRORS).get(0);
            assertThat(textOf(faulted, "fieldName")).isEqualTo(PROPERTY_START_MONTH);
            assertThat(textOf(faulted, "screenFieldId")).isEqualTo(FIELD_START_MONTH);
            assertThat(textOf(faulted, "state"))
                    .as("a position that was never supplied is distinguished from one supplied wrongly")
                    .isEqualTo(STATE_MISSING);
            assertNothingWasHandedOver();
        }

        @Test
        @DisplayName("a position transmitted as spaces is as absent as one not transmitted at all")
        void aPositionTransmittedAsSpacesIsEquallyAbsent() throws Exception {
            // The absence test at line 259 compares against spaces as well as low values, so a field the
            // terminal sent full of blanks and a field it did not send are the same state.
            final JsonNode screen = servedScreen(
                    customTurn(BLANK_MONTH_DAY, "01", "2022", "06", "30", "2022", CONFIRM_YES));

            assertThat(textOf(screen, R_MESSAGE)).isEqualTo(MSG_START_MONTH_EMPTY);
            assertNothingWasHandedOver();
        }

        @Test
        @DisplayName("the start day answers once the start month is supplied")
        void theStartDayAbsenceAnswersSecond() throws Exception {
            final JsonNode screen =
                    servedScreen(customTurn("06", null, null, null, null, null, CONFIRM_YES));

            assertThat(textOf(screen, R_MESSAGE)).isEqualTo(MSG_START_DAY_EMPTY);
            assertThat(textOf(screen, R_FOCUS_FIELD)).isEqualTo(FIELD_START_DAY);
            assertNothingWasHandedOver();
        }

        @Test
        @DisplayName("the start year answers once the start day is supplied")
        void theStartYearAbsenceAnswersThird() throws Exception {
            final JsonNode screen =
                    servedScreen(customTurn("06", "01", null, null, null, null, CONFIRM_YES));

            assertThat(textOf(screen, R_MESSAGE)).isEqualTo(MSG_START_YEAR_EMPTY);
            assertThat(textOf(screen, R_FOCUS_FIELD)).isEqualTo(FIELD_START_YEAR);
            assertNothingWasHandedOver();
        }

        @Test
        @DisplayName("the end month answers once the whole start date is supplied")
        void theEndMonthAbsenceAnswersFourth() throws Exception {
            final JsonNode screen =
                    servedScreen(customTurn("06", "01", "2022", null, null, null, CONFIRM_YES));

            assertThat(textOf(screen, R_MESSAGE)).isEqualTo(MSG_END_MONTH_EMPTY);
            assertThat(textOf(screen, R_FOCUS_FIELD)).isEqualTo(FIELD_END_MONTH);
            assertNothingWasHandedOver();
        }

        @Test
        @DisplayName("the end day answers once the end month is supplied")
        void theEndDayAbsenceAnswersFifth() throws Exception {
            final JsonNode screen =
                    servedScreen(customTurn("06", "01", "2022", "06", null, null, CONFIRM_YES));

            assertThat(textOf(screen, R_MESSAGE)).isEqualTo(MSG_END_DAY_EMPTY);
            assertThat(textOf(screen, R_FOCUS_FIELD)).isEqualTo(FIELD_END_DAY);
            assertNothingWasHandedOver();
        }

        @Test
        @DisplayName("the end year answers last of the six")
        void theEndYearAbsenceAnswersSixth() throws Exception {
            final JsonNode screen =
                    servedScreen(customTurn("06", "01", "2022", "06", "30", null, CONFIRM_YES));

            assertThat(textOf(screen, R_MESSAGE)).isEqualTo(MSG_END_YEAR_EMPTY);
            assertThat(textOf(screen, R_FOCUS_FIELD)).isEqualTo(FIELD_END_YEAR);
            assertNothingWasHandedOver();
        }

        @Test
        @DisplayName("an earlier absence wins over a later one, so a screen blank in two places answers "
                + "only the earlier stage")
        void anEarlierAbsenceWinsOverALaterOne() throws Exception {
            // Blank in the first and the last of the six. The legacy evaluation stops at the first match
            // and its send ends the task, so the later stage is never reached.
            final JsonNode screen =
                    servedScreen(customTurn(null, "01", "2022", "06", "30", null, CONFIRM_YES));

            assertThat(textOf(screen, R_MESSAGE))
                    .isEqualTo(MSG_START_MONTH_EMPTY)
                    .isNotEqualTo(MSG_END_YEAR_EMPTY);
            assertThat(screen.get(R_FIELD_ERRORS).size())
                    .as("one failure is reported, because one is all the legacy screen could carry")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("marking no report type at all answers the catch-all text and faults the first "
                + "report-type position")
        void markingNoReportTypeAnswersTheCatchAllText() throws Exception {
            final JsonNode screen = servedScreen(reEnteredTurn());

            assertThat(textOf(screen, R_MESSAGE)).isEqualTo(MSG_SELECT_REPORT_TYPE);
            assertThat(flagOf(screen, R_GENERAL_ERROR)).isTrue();
            assertThat(textOf(screen, R_FOCUS_FIELD)).isEqualTo(FIELD_MONTHLY);
            final JsonNode faulted = screen.get(R_FIELD_ERRORS).get(0);
            assertThat(textOf(faulted, "fieldName")).isEqualTo(PROPERTY_REPORT_TYPE);
            assertThat(textOf(faulted, "screenFieldId")).isEqualTo(FIELD_MONTHLY);
            assertThat(textOf(faulted, "state")).isEqualTo(STATE_MISSING);
            assertNothingWasHandedOver();
        }
    }

    // ===============================================================================================
    // GROUP 3 :: THE SIX RANGE STAGES, IN ORDER, WITH THE CALENDAR-UNIT WORD CAPITALISED
    // ===============================================================================================

    /**
     * The middle six stages, at lines 329 to 379. Written as six independent statements, but each ends in
     * a send and a send ends the task, so only the first to fire is ever reached - and each capitalises
     * the calendar-unit word while leaving the negation word alone, which is a different pattern from
     * both the absence stages above and the closing stages below.
     */
    @Nested
    @DisplayName("Range cascade order and casing")
    class RangeCascadeOrderAndCasing {

        /** Creates the group. */
        RangeCascadeOrderAndCasing() {
            super();
        }

        @Test
        @DisplayName("a start month above the twelfth is refused, and refused as a position that was "
                + "supplied wrongly rather than one left blank")
        void aStartMonthAboveTheTwelfthIsRefused() throws Exception {
            final JsonNode screen =
                    servedScreen(customTurn("13", "01", "2022", "06", "30", "2022", CONFIRM_YES));

            assertThat(textOf(screen, R_MESSAGE)).isEqualTo(MSG_START_MONTH_INVALID);
            assertThat(flagOf(screen, R_GENERAL_ERROR)).isTrue();
            assertThat(textOf(screen, R_FOCUS_FIELD)).isEqualTo(FIELD_START_MONTH);
            assertThat(textOf(screen.get(R_FIELD_ERRORS).get(0), "state")).isEqualTo(STATE_INVALID);
            assertNothingWasHandedOver();
        }

        @Test
        @DisplayName("a start day above the thirty-first is refused")
        void aStartDayAboveTheThirtyFirstIsRefused() throws Exception {
            final JsonNode screen =
                    servedScreen(customTurn("06", "32", "2022", "06", "30", "2022", CONFIRM_YES));

            assertThat(textOf(screen, R_MESSAGE)).isEqualTo(MSG_START_DAY_INVALID);
            assertThat(textOf(screen, R_FOCUS_FIELD)).isEqualTo(FIELD_START_DAY);
            assertNothingWasHandedOver();
        }

        @Test
        @DisplayName("a start year that is not digits is refused, and no upper bound is applied to a year")
        void aStartYearThatIsNotDigitsIsRefused() throws Exception {
            // The year stage tests only that the position is numeric: the legacy declares no year bound,
            // so a far-future year is accepted and only a non-numeric one is refused.
            final JsonNode screen =
                    servedScreen(customTurn("06", "01", "20X2", "06", "30", "2022", CONFIRM_YES));

            assertThat(textOf(screen, R_MESSAGE)).isEqualTo(MSG_START_YEAR_INVALID);
            assertThat(textOf(screen, R_FOCUS_FIELD)).isEqualTo(FIELD_START_YEAR);
            assertNothingWasHandedOver();
        }

        @Test
        @DisplayName("an end month above the twelfth is refused")
        void anEndMonthAboveTheTwelfthIsRefused() throws Exception {
            final JsonNode screen =
                    servedScreen(customTurn("06", "01", "2022", "13", "30", "2022", CONFIRM_YES));

            assertThat(textOf(screen, R_MESSAGE)).isEqualTo(MSG_END_MONTH_INVALID);
            assertThat(textOf(screen, R_FOCUS_FIELD)).isEqualTo(FIELD_END_MONTH);
            assertNothingWasHandedOver();
        }

        @Test
        @DisplayName("an end day above the thirty-first is refused")
        void anEndDayAboveTheThirtyFirstIsRefused() throws Exception {
            final JsonNode screen =
                    servedScreen(customTurn("06", "01", "2022", "06", "32", "2022", CONFIRM_YES));

            assertThat(textOf(screen, R_MESSAGE)).isEqualTo(MSG_END_DAY_INVALID);
            assertThat(textOf(screen, R_FOCUS_FIELD)).isEqualTo(FIELD_END_DAY);
            assertNothingWasHandedOver();
        }

        @Test
        @DisplayName("an end year that is not digits is refused")
        void anEndYearThatIsNotDigitsIsRefused() throws Exception {
            final JsonNode screen =
                    servedScreen(customTurn("06", "01", "2022", "06", "30", "2O22", CONFIRM_YES));

            assertThat(textOf(screen, R_MESSAGE)).isEqualTo(MSG_END_YEAR_INVALID);
            assertThat(textOf(screen, R_FOCUS_FIELD)).isEqualTo(FIELD_END_YEAR);
            assertNothingWasHandedOver();
        }

        @Test
        @DisplayName("an absence anywhere is reported before any bound, so a screen that is blank in one "
                + "place and out of range in another answers the absence")
        void anAbsenceIsReportedBeforeAnyBound() throws Exception {
            // Two failures on one screen, one in each stage. The absence stages run first, so the bound
            // is never compared and the capitalised-unit text is never composed.
            final JsonNode screen =
                    servedScreen(customTurn(null, "01", "2022", "13", "30", "2022", CONFIRM_YES));

            assertThat(textOf(screen, R_MESSAGE))
                    .isEqualTo(MSG_START_MONTH_EMPTY)
                    .isNotEqualTo(MSG_END_MONTH_INVALID);
            assertThat(textOf(screen, R_FOCUS_FIELD)).isEqualTo(FIELD_START_MONTH);
        }

        @Test
        @DisplayName("a start bound is reported before the matching end bound")
        void aStartBoundIsReportedBeforeTheMatchingEndBound() throws Exception {
            final JsonNode screen =
                    servedScreen(customTurn("13", "01", "2022", "13", "30", "2022", CONFIRM_YES));

            assertThat(textOf(screen, R_MESSAGE))
                    .isEqualTo(MSG_START_MONTH_INVALID)
                    .isNotEqualTo(MSG_END_MONTH_INVALID);
            assertThat(textOf(screen, R_FOCUS_FIELD)).isEqualTo(FIELD_START_MONTH);
        }

        @Test
        @DisplayName("a short numeric position is widened to its declared width before the bound is "
                + "compared, and comes back widened")
        void aShortNumericPositionIsWidenedBeforeTheBoundIsCompared() throws Exception {
            // Lines 305 to 327 rewrite each position through a fixed-width numeric edit, which zero-fills
            // a short value; the later end-month bound then fails, so the widened start month is visible
            // on the re-presented screen instead of being cleared by a successful submission.
            final JsonNode screen =
                    servedScreen(customTurn("6", "1", "2022", "13", "30", "2022", CONFIRM_YES));

            assertThat(textOf(screen, R_MESSAGE)).isEqualTo(MSG_END_MONTH_INVALID);
            assertThat(textOf(screen, P_START_MONTH))
                    .as("the leading zero is contractual, so the position comes back two characters wide")
                    .isEqualTo("06");
            assertThat(textOf(screen, P_START_DAY)).isEqualTo("01");
        }
    }

    // ===============================================================================================
    // GROUP 4 :: THE TWO CLOSING STAGES, WITH THE CALENDAR WORD ENTIRELY IN LOWER CASE
    // ===============================================================================================

    /**
     * The two closing stages at lines 388 to 426, each decided by the shared date subprogram under a
     * two-level acceptance test: an accepted severity passes outright, and otherwise a message number
     * that is <em>not</em> the tolerated one is refused. Both levels are load bearing, and the second is
     * the asymmetry this group exists to pin down.
     *
     * <p>These stages are also where the three-part to single-value date conversion becomes observable.
     * The screen collects a month, a day and a year, while the subprogram is handed one ten-character
     * value assembled from them; that assembled value never crosses the response contract, so the only
     * legitimate evidence that the conversion happened - and happened without normalising anything - is
     * this stage's verdict on a pair of parts that are each individually in range. Reaching into the
     * hand-over payload for the converted value would assert the queue contract, which belongs to the
     * specification that drains a real queue.
     */
    @Nested
    @DisplayName("Composite date cascade and lower-case date")
    class CompositeDateCascade {

        /** Creates the group. */
        CompositeDateCascade() {
            super();
        }

        @Test
        @DisplayName("a start date whose parts are each in range but which names no calendar day is "
                + "refused, and refused with the calendar word entirely in lower case")
        void aStartDateThatNamesNoCalendarDayIsRefused() throws Exception {
            // Every part passes its own bound - a second month and a thirtieth day - so this stage is the
            // only one that can refuse the pair, and its text spells "date" in lower case where the six
            // stages before it capitalise the unit word.
            final JsonNode screen =
                    servedScreen(customTurn("02", "30", "2022", "06", "30", "2022", CONFIRM_YES));

            assertThat(textOf(screen, R_MESSAGE))
                    .isEqualTo(MSG_START_DATE_INVALID)
                    .isNotEqualTo(MSG_START_MONTH_INVALID)
                    .isNotEqualTo(MSG_START_DAY_INVALID);
            assertThat(flagOf(screen, R_GENERAL_ERROR)).isTrue();
            assertThat(textOf(screen, R_FOCUS_FIELD)).isEqualTo(FIELD_START_MONTH);
            final JsonNode faulted = screen.get(R_FIELD_ERRORS).get(0);
            assertThat(textOf(faulted, "fieldName"))
                    .as("the fault is against the assembled date rather than one of its parts")
                    .isEqualTo(PROPERTY_START_DATE);
            assertThat(textOf(faulted, "state")).isEqualTo(STATE_INVALID);
            assertNothingWasHandedOver();
        }

        @Test
        @DisplayName("the refused calendar date comes back exactly as it was typed, never rolled forward "
                + "into the following month")
        void theRefusedCalendarDateIsEchoedBackUnrolled() throws Exception {
            // Strict resolution refuses rather than normalises. An implementation that let the platform
            // roll a thirtieth of the second month into the third would accept the date and change which
            // range was reported, so the untouched echo is the observable evidence that it did not.
            final JsonNode screen =
                    servedScreen(customTurn("02", "30", "2022", "06", "30", "2022", CONFIRM_YES));

            assertThat(textOf(screen, P_START_MONTH)).isEqualTo("02");
            assertThat(textOf(screen, P_START_DAY)).isEqualTo("30");
            assertThat(textOf(screen, P_START_YEAR)).isEqualTo("2022");
        }

        @Test
        @DisplayName("an end date that names no calendar day is refused with its own lower-case text")
        void anEndDateThatNamesNoCalendarDayIsRefused() throws Exception {
            final JsonNode screen =
                    servedScreen(customTurn("06", "01", "2022", "02", "30", "2022", CONFIRM_YES));

            assertThat(textOf(screen, R_MESSAGE))
                    .isEqualTo(MSG_END_DATE_INVALID)
                    .isNotEqualTo(MSG_END_MONTH_INVALID);
            assertThat(textOf(screen, R_FOCUS_FIELD)).isEqualTo(FIELD_END_MONTH);
            assertNothingWasHandedOver();
        }

        @Test
        @DisplayName("a bound is reported before any calendar test, so a screen out of range in one place "
                + "and impossible in another answers the bound")
        void aBoundIsReportedBeforeAnyCalendarTest() throws Exception {
            final JsonNode screen =
                    servedScreen(customTurn("13", "01", "2022", "02", "30", "2022", CONFIRM_YES));

            assertThat(textOf(screen, R_MESSAGE))
                    .isEqualTo(MSG_START_MONTH_INVALID)
                    .isNotEqualTo(MSG_END_DATE_INVALID);
        }

        @Test
        @DisplayName("the start date is offered to the subprogram before the end date, so a screen with "
                + "two impossible dates answers only the start date")
        void theStartDateIsTestedBeforeTheEndDate() throws Exception {
            // The two calls are written as independent statement groups, but the first group's refusal
            // sends and the send ends the task, so the end date is never offered to the subprogram at all.
            final JsonNode screen =
                    servedScreen(customTurn("02", "30", "2022", "02", "30", "2022", CONFIRM_YES));

            assertThat(textOf(screen, R_MESSAGE))
                    .isEqualTo(MSG_START_DATE_INVALID)
                    .isNotEqualTo(MSG_END_DATE_INVALID);
            assertThat(screen.get(R_FIELD_ERRORS).size()).isEqualTo(1);
        }

        @Test
        @DisplayName("a date the subprogram flags with the one tolerated message number is still "
                + "accepted, because the caller ignores that number - the legacy asymmetry, preserved")
        void aDateFlaggedWithTheToleratedMessageNumberIsStillAccepted() throws Exception {
            // A calendar day that resolves strictly yet falls before the first day the legacy date
            // services cover: the subprogram reports a non-accepted severity carrying the one message
            // number the caller exempts, so the two-level test at lines 396 to 406 admits it. Collapsing
            // those two levels into a single verdict would refuse this screen, which is the defect this
            // asserts against - it is not corrected here.
            final JsonNode screen =
                    servedScreen(customTurn("10", "01", "1582", "12", "31", "1582", CONFIRM_YES));

            assertThat(textOf(screen, R_MESSAGE))
                    .as("the turn is acknowledged rather than refused")
                    .isEqualTo(PERIOD_VALUE_CUSTOM + SUBMITTED_SUFFIX)
                    .isNotEqualTo(MSG_START_DATE_INVALID);
            assertThat(flagOf(screen, R_GENERAL_ERROR)).isFalse();
            assertThat(flagOf(screen, R_SUBMISSION_ACCEPTED)).isTrue();
            assertThat(textOf(screen, R_REPORT_PERIOD))
                    .as("the operator-range arm ran to its end, so both subprogram calls were made and "
                            + "the acceptance is the tolerance rather than a skipped validation")
                    .isEqualTo(PERIOD_TOKEN_CUSTOM);
        }
    }

    // ===============================================================================================
    // GROUP 5 :: THE CONFIRMATION GATE, INCLUDING THE SILENT REFUSAL
    // ===============================================================================================

    /**
     * The gate at lines 464 to 494: a prompt when the position is blank, acceptance in either case, a
     * silent reset on refusal, and anything else quoted back inside its own text. Clause order is
     * contractual and the refusing arms publish nothing at all.
     */
    @Nested
    @DisplayName("Confirmation - prompt, Y, silent N, invalid third value")
    class ConfirmationGate {

        /** Creates the group. */
        ConfirmationGate() {
            super();
        }

        @Test
        @DisplayName("a blank confirmation raises the prompt, composed from the leading fragment, the "
                + "report name and the trailing fragment with nothing inserted between them")
        void aBlankConfirmationRaisesThePrompt() throws Exception {
            final JsonNode screen = servedScreen(monthlyTurn(null));

            assertThat(textOf(screen, R_MESSAGE))
                    .isEqualTo(CONFIRM_PROMPT_PREFIX + PERIOD_VALUE_MONTHLY + CONFIRM_PROMPT_SUFFIX)
                    .isEqualTo("Please confirm to print the Monthly report...");
            assertThat(flagOf(screen, R_GENERAL_ERROR)).isTrue();
            assertThat(flagOf(screen, R_SUBMISSION_ACCEPTED)).isFalse();
            assertThat(textOf(screen, R_FOCUS_FIELD))
                    .as("the cursor returns to the position the operator must answer")
                    .isEqualTo(FIELD_CONFIRM);
            assertNothingWasHandedOver();
        }

        @Test
        @DisplayName("a confirmation transmitted as a space is a blank one, so it prompts rather than "
                + "being read as an unrecognised value")
        void aConfirmationTransmittedAsASpaceIsBlank() throws Exception {
            final JsonNode screen = servedScreen(monthlyTurn(BLANK_SELECTION));

            assertThat(textOf(screen, R_MESSAGE))
                    .isEqualTo(CONFIRM_PROMPT_PREFIX + PERIOD_VALUE_MONTHLY + CONFIRM_PROMPT_SUFFIX);
            assertNothingWasHandedOver();
        }

        @Test
        @DisplayName("an affirmative confirmation proceeds, in upper case and in lower case alike")
        void anAffirmativeConfirmationProceedsInEitherCase() throws Exception {
            final JsonNode upperCase = servedScreen(monthlyTurn(CONFIRM_YES));
            final JsonNode lowerCase = servedScreen(monthlyTurn(CONFIRM_YES_LOWER));

            assertThat(flagOf(upperCase, R_SUBMISSION_ACCEPTED)).isTrue();
            assertThat(flagOf(lowerCase, R_SUBMISSION_ACCEPTED)).isTrue();
            assertThat(textOf(lowerCase, R_MESSAGE))
                    .as("both spellings reach the same acknowledgement")
                    .isEqualTo(textOf(upperCase, R_MESSAGE));
        }

        @Test
        @DisplayName("a negative confirmation is a SILENT refusal: the error flag is raised and there is "
                + "no message text whatsoever")
        void aNegativeConfirmationIsASilentRefusal() throws Exception {
            // Lines 480 to 483 reset the screen - which blanks the message work field at line 646 - raise
            // the error flag and send, and nothing afterwards writes any text. The flag and the absent
            // text are asserted together because that pairing is the contract: the flag is a component of
            // its own and must never be derived from a message being present, and inventing a
            // cancellation message here would be output the legacy never produced.
            final JsonNode screen = servedScreen(monthlyTurn(CONFIRM_NO));

            assertThat(flagOf(screen, R_GENERAL_ERROR))
                    .as("the refusal raises the flag")
                    .isTrue();
            assertThat(textOf(screen, R_MESSAGE))
                    .as("and says nothing at all while doing so")
                    .isEmpty();
            assertThat(textOf(screen, R_ERROR_MESSAGE))
                    .as("the outbound message field carries only blanks, untrimmed, at its width")
                    .isEqualTo(BLANK_SELECTION.repeat(OUTBOUND_MESSAGE_WIDTH));
            assertThat(flagOf(screen, R_SUBMISSION_ACCEPTED)).isFalse();
            assertThat(screen.get(R_FIELD_ERRORS).size())
                    .as("a silent refusal faults no individual position either")
                    .isEqualTo(0);
            assertNothingWasHandedOver();
        }

        @Test
        @DisplayName("a lower-case negative confirmation is equally silent")
        void aLowerCaseNegativeConfirmationIsEquallySilent() throws Exception {
            final JsonNode screen = servedScreen(monthlyTurn(CONFIRM_NO_LOWER));

            assertThat(flagOf(screen, R_GENERAL_ERROR)).isTrue();
            assertThat(textOf(screen, R_MESSAGE)).isEmpty();
            assertNothingWasHandedOver();
        }

        @Test
        @DisplayName("a negative confirmation clears the screen it refuses, so every position comes back "
                + "blank at its declared width")
        void aNegativeConfirmationClearsTheScreen() throws Exception {
            final JsonNode screen = servedScreen(validCustomTurn(CONFIRM_NO));

            assertThat(textOf(screen, P_CUSTOM)).isEqualTo(BLANK_SELECTION);
            assertThat(textOf(screen, P_START_MONTH)).isEqualTo(BLANK_MONTH_DAY);
            assertThat(textOf(screen, P_START_YEAR)).isEqualTo(BLANK_YEAR);
            assertThat(textOf(screen, P_CONFIRM)).isEqualTo(BLANK_SELECTION);
        }

        @Test
        @DisplayName("any other confirmation value is quoted back inside its own text, between straight "
                + "double quotes")
        void anyOtherConfirmationValueIsQuotedBack() throws Exception {
            final JsonNode screen = servedScreen(monthlyTurn(CONFIRM_ODD));

            assertThat(textOf(screen, R_MESSAGE))
                    .isEqualTo(INVALID_CONFIRM_PREFIX + CONFIRM_ODD + INVALID_CONFIRM_SUFFIX)
                    .isEqualTo("\"?\" is not a valid value to confirm...");
            assertThat(flagOf(screen, R_GENERAL_ERROR)).isTrue();
            assertThat(textOf(screen, R_FOCUS_FIELD)).isEqualTo(FIELD_CONFIRM);
            assertNothingWasHandedOver();
        }

        @Test
        @DisplayName("the confirmation gate runs after the date validation, so an invalid range answers "
                + "before the confirmation is ever read")
        void theGateRunsAfterTheDateValidation() throws Exception {
            // The gate sits inside the submission paragraph, which the operator-range arm reaches only
            // once every date stage has passed - so an unanswered confirmation on an invalid screen
            // reports the date, not the confirmation.
            final JsonNode screen =
                    servedScreen(customTurn("13", "01", "2022", "06", "30", "2022", null));

            assertThat(textOf(screen, R_MESSAGE))
                    .isEqualTo(MSG_START_MONTH_INVALID)
                    .doesNotContain(CONFIRM_PROMPT_PREFIX);
        }
    }

    // ===============================================================================================
    // GROUP 6 :: THE ACKNOWLEDGEMENT, THE CLEARED SCREEN, AND THE TWO FRAGMENTS THAT LOOK ALIKE
    // ===============================================================================================

    /**
     * Lines 445 to 456: the screen is cleared, the message field is recoloured and the acknowledgement is
     * composed from the space-delimited report name. The trailing fragment differs from the confirmation
     * prompt's by one byte, and the two are never interchangeable.
     */
    @Nested
    @DisplayName("Success acknowledgement and cleared inputs")
    class SuccessAcknowledgement {

        /** Creates the group. */
        SuccessAcknowledgement() {
            super();
        }

        @Test
        @DisplayName("an accepted month-to-date submission answers the acknowledgement at its exact "
                + "bytes, with the period published and the cursor back on the first report-type position")
        void anAcceptedSubmissionAnswersTheAcknowledgement() throws Exception {
            final JsonNode screen = servedScreen(monthlyTurn(CONFIRM_YES));

            assertThat(textOf(screen, R_MESSAGE))
                    .isEqualTo(PERIOD_VALUE_MONTHLY + SUBMITTED_SUFFIX)
                    .isEqualTo("Monthly report submitted for printing ...");
            assertThat(flagOf(screen, R_SUBMISSION_ACCEPTED)).isTrue();
            assertThat(flagOf(screen, R_GENERAL_ERROR)).isFalse();
            assertThat(textOf(screen, R_FOCUS_FIELD)).isEqualTo(FIELD_MONTHLY);
            assertThat(textOf(screen, R_REPORT_PERIOD)).isEqualTo(PERIOD_TOKEN_MONTHLY);
            assertThat(textOf(screen, R_NEXT_ROUTE))
                    .as("the turn re-arms this same screen, exactly as the legacy return does")
                    .isEqualTo(REPORT_REQUEST_ROUTE_VALUE);
            assertThat(screen.get(R_FIELD_ERRORS).size()).isEqualTo(0);
        }

        @Test
        @DisplayName("a successful submission clears every input position, so blank echoed values are the "
                + "expected outcome rather than lost input")
        void aSuccessfulSubmissionClearsEveryInputPosition() throws Exception {
            // The reset at line 447 blanks all ten positions before the acknowledgement is composed, so a
            // client re-presenting this screen shows an empty form beside the acknowledgement. The widths
            // are asserted untrimmed, because a cleared position is spaces at its declared width and not
            // an empty string.
            final JsonNode screen = servedScreen(validCustomTurn(CONFIRM_YES));

            assertThat(textOf(screen, P_MONTHLY)).isEqualTo(BLANK_SELECTION);
            assertThat(textOf(screen, P_YEARLY)).isEqualTo(BLANK_SELECTION);
            assertThat(textOf(screen, P_CUSTOM)).isEqualTo(BLANK_SELECTION);
            assertThat(textOf(screen, P_START_MONTH)).isEqualTo(BLANK_MONTH_DAY);
            assertThat(textOf(screen, P_START_DAY)).isEqualTo(BLANK_MONTH_DAY);
            assertThat(textOf(screen, P_START_YEAR)).isEqualTo(BLANK_YEAR);
            assertThat(textOf(screen, P_END_MONTH)).isEqualTo(BLANK_MONTH_DAY);
            assertThat(textOf(screen, P_END_DAY)).isEqualTo(BLANK_MONTH_DAY);
            assertThat(textOf(screen, P_END_YEAR)).isEqualTo(BLANK_YEAR);
            assertThat(textOf(screen, P_CONFIRM)).isEqualTo(BLANK_SELECTION);
        }

        @Test
        @DisplayName("the acknowledgement fragment and the confirmation-prompt fragment are two different "
                + "strings, one byte apart, and neither is derived from the other")
        void theTwoReportFragmentsAreDifferentStrings() throws Exception {
            final String acknowledgement = textOf(servedScreen(monthlyTurn(CONFIRM_YES)), R_MESSAGE);
            final String prompt = textOf(servedScreen(monthlyTurn(null)), R_MESSAGE);

            assertThat(acknowledgement)
                    .as("the acknowledgement's fragment has a leading space AND a space before its dots")
                    .endsWith(SUBMITTED_SUFFIX)
                    .endsWith(" report submitted for printing ...");
            assertThat(prompt)
                    .as("the prompt's fragment has the leading space and NO space before its dots")
                    .endsWith(CONFIRM_PROMPT_SUFFIX)
                    .endsWith(" report...")
                    .doesNotEndWith(" report ...");
            assertThat(SUBMITTED_SUFFIX)
                    .as("the two fragments must never be unified, shared or trimmed into one another")
                    .isNotEqualTo(CONFIRM_PROMPT_SUFFIX);
            assertThat(acknowledgement).isNotEqualTo(prompt);
        }

        @Test
        @DisplayName("a year-to-date submission is acknowledged with its own report name")
        void aYearToDateSubmissionIsAcknowledgedWithItsOwnName() throws Exception {
            final JsonNode screen = servedScreen(yearlyTurn(CONFIRM_YES));

            assertThat(textOf(screen, R_MESSAGE)).isEqualTo(PERIOD_VALUE_YEARLY + SUBMITTED_SUFFIX);
            assertThat(flagOf(screen, R_SUBMISSION_ACCEPTED)).isTrue();
        }

        @Test
        @DisplayName("an operator-supplied range that passes every stage is acknowledged with its own "
                + "report name")
        void anOperatorSuppliedRangeIsAcknowledgedWithItsOwnName() throws Exception {
            final JsonNode screen = servedScreen(validCustomTurn(CONFIRM_YES));

            assertThat(textOf(screen, R_MESSAGE)).isEqualTo(PERIOD_VALUE_CUSTOM + SUBMITTED_SUFFIX);
            assertThat(textOf(screen, R_REPORT_PERIOD)).isEqualTo(PERIOD_TOKEN_CUSTOM);
            assertThat(flagOf(screen, R_SUBMISSION_ACCEPTED)).isTrue();
        }

        @Test
        @DisplayName("the outbound message field carries the acknowledgement at its declared width, "
                + "space-filled and untrimmed")
        void theOutboundMessageFieldCarriesTheTextAtItsDeclaredWidth() throws Exception {
            // The move at line 560 puts the eighty-character work field into a narrower outbound field,
            // so the served value is the text followed by blanks to that width.
            final String acknowledgement = PERIOD_VALUE_MONTHLY + SUBMITTED_SUFFIX;
            final JsonNode screen = servedScreen(monthlyTurn(CONFIRM_YES));

            assertThat(textOf(screen, R_ERROR_MESSAGE))
                    .hasSize(OUTBOUND_MESSAGE_WIDTH)
                    .isEqualTo(acknowledgement + BLANK_SELECTION.repeat(
                            OUTBOUND_MESSAGE_WIDTH - acknowledgement.length()));
        }

        @Test
        @DisplayName("the header is stamped from the pinned clock, so the turn is reproducible and reads "
                + "no wall clock")
        void theHeaderIsStampedFromThePinnedClock() throws Exception {
            final JsonNode screen = servedScreen(monthlyTurn(CONFIRM_YES));

            assertThat(textOf(screen, R_CURRENT_DATE)).isEqualTo(PINNED_HEADER_DATE);
            assertThat(textOf(screen, R_CURRENT_TIME)).isEqualTo(PINNED_HEADER_TIME);
            assertThat(textOf(screen, R_TRANSACTION_NAME)).isEqualTo(TRANSACTION_ID);
            assertThat(textOf(screen, R_PROGRAM_NAME)).isEqualTo(PROGRAM_NAME);
        }
    }

    // ===============================================================================================
    // GROUP 7 :: A REFUSED HAND-OVER IS NON-FATAL
    // ===============================================================================================

    /**
     * Lines 515 to 535, under the queue's own ignore-on-error definition: a refused write is recorded on
     * the diagnostic channel, reported to the operator as a fixed text, and control returns normally.
     * There is no abend and no re-raise, so an accepted report request never becomes a failed exchange.
     */
    @Nested
    @DisplayName("Non-fatal submission failure")
    class NonFatalSubmissionFailure {

        /** Creates the group. */
        NonFatalSubmissionFailure() {
            super();
        }

        @Test
        @DisplayName("a hand-over that raises does not propagate: the exchange completes and the turn "
                + "answers its own screen carrying the frozen failure text")
        void aHandOverThatRaisesDoesNotPropagate() throws Exception {
            willThrow(new JobSubmissionException(new IllegalStateException("the bridge refused")))
                    .given(ReportControllerIT.this.jobSubmissionService)
                    .submitCanonicalJobImage(anyString(), anyList());

            final MvcResult result = submit(monthlyTurn(CONFIRM_YES));

            assertThat(result.getResponse().getStatus())
                    .as("the transaction completed on the mainframe, so the request completes here")
                    .isEqualTo(HttpStatus.OK.value());
            final JsonNode screen = JSON.readTree(bodyOf(result));
            assertThat(screen.has(R_GENERAL_ERROR)).isTrue();
            assertThat(screen.has(R_SUBMISSION_ACCEPTED)).isTrue();
            assertThat(screen.has(R_NEXT_ROUTE))
                    .as("the served body is the screen contract and not the module's failure envelope, "
                            + "which is how this asserts that the carrier never left the transaction")
                    .isTrue();
            assertThat(textOf(screen, R_MESSAGE))
                    .as("the operator-facing text is the carrier's own frozen default")
                    .isEqualTo(JobSubmissionException.DEFAULT_MESSAGE);
            assertThat(flagOf(screen, R_GENERAL_ERROR)).isTrue();
            assertThat(flagOf(screen, R_SUBMISSION_ACCEPTED)).isFalse();
            assertThat(textOf(screen, R_FOCUS_FIELD))
                    .as("the cursor is repositioned on the first report-type position, per line 533")
                    .isEqualTo(FIELD_MONTHLY);
        }

        @Test
        @DisplayName("a hand-over that reports its refusal as a value reaches the same outcome as one "
                + "that raises")
        void aHandOverThatReportsItsRefusalReachesTheSameOutcome() throws Exception {
            given(ReportControllerIT.this.jobSubmissionService
                    .submitCanonicalJobImage(anyString(), anyList()))
                    .willAnswer(invocation -> new JobSubmissionService.SubmissionResult(
                            ((List<?>) invocation.getArguments()[1]).size(), 0, true, ""));

            final JsonNode screen = servedScreen(validCustomTurn(CONFIRM_YES));

            assertThat(textOf(screen, R_MESSAGE)).isEqualTo(JobSubmissionException.DEFAULT_MESSAGE);
            assertThat(flagOf(screen, R_GENERAL_ERROR)).isTrue();
            assertThat(flagOf(screen, R_SUBMISSION_ACCEPTED)).isFalse();
            assertThat(textOf(screen, R_FOCUS_FIELD)).isEqualTo(FIELD_MONTHLY);
        }

        @Test
        @DisplayName("the failure text is the whole of what a refused hand-over discloses: no failure "
                + "type, no stack frame and none of the raw codes the legacy writes to its own channel")
        void theFailureTextIsTheWholeOfWhatIsDisclosed() throws Exception {
            willThrow(new JobSubmissionException(new IllegalStateException("the bridge refused")))
                    .given(ReportControllerIT.this.jobSubmissionService)
                    .submitCanonicalJobImage(anyString(), anyList());

            final String body = bodyOf(submit(monthlyTurn(CONFIRM_YES)));

            assertNothingIsDisclosed(body);
            assertThat(body)
                    .as("nor the name of the carrier itself, nor the cause this test supplied")
                    .doesNotContain("JobSubmission")
                    .doesNotContain("the bridge refused");
            assertThat(body)
                    .as("the operator text is present, so absence of detail is not absence of a report")
                    .contains(JobSubmissionException.DEFAULT_MESSAGE);
        }

        @Test
        @DisplayName("a refused hand-over is neither a client failure nor a server failure, and the "
                + "message field still carries the text at its declared width")
        void aRefusedHandOverIsNeitherAClientNorAServerFailure() throws Exception {
            given(ReportControllerIT.this.jobSubmissionService
                    .submitCanonicalJobImage(anyString(), anyList()))
                    .willAnswer(invocation -> new JobSubmissionService.SubmissionResult(
                            ((List<?>) invocation.getArguments()[1]).size(), 0, true, ""));

            final MvcResult result = submit(monthlyTurn(CONFIRM_YES));

            assertThat(result.getResponse().getStatus())
                    .as("the completed exchange the module's failure policy also maps this carrier to")
                    .isEqualTo(HttpStatus.OK.value());
            assertThat(HttpStatus.valueOf(result.getResponse().getStatus()).isError())
                    .as("so nothing about a tolerated hand-over failure reads as an error to a client "
                            + "that inspects only the status line")
                    .isFalse();
            assertThat(textOf(JSON.readTree(bodyOf(result)), R_ERROR_MESSAGE))
                    .hasSize(OUTBOUND_MESSAGE_WIDTH)
                    .startsWith(JobSubmissionException.DEFAULT_MESSAGE);
        }
    }

    // ===============================================================================================
    // GROUP 8 :: ONE ENDPOINT, AND NOTHING BESIDE IT
    // ===============================================================================================

    /**
     * The screen is one transaction with one submitted map, so the boundary publishes exactly one
     * operation. Nothing previews the request, inspects the hand-over, renders its payload, reports its
     * progress or retries it - the legacy has no such screen, and adding one would be feature expansion.
     */
    @Nested
    @DisplayName("Single endpoint - no preview/status/retry")
    class SingleEndpoint {

        /** Creates the group. */
        SingleEndpoint() {
            super();
        }

        @Test
        @DisplayName("the one published route accepts a submitted screen and refuses every other method")
        void theOnePublishedRouteAcceptsOnlyASubmittedScreen() throws Exception {
            final MvcResult read = ReportControllerIT.this.client
                    .perform(MockMvcRequestBuilders.get(ReportController.REPORT_REQUEST_PATH)
                            .header(HttpHeaders.AUTHORIZATION, ordinarySession())
                            .accept(MediaType.APPLICATION_JSON))
                    .andReturn();

            assertThat(read.getResponse().getStatus())
                    .as("a screen is submitted, never fetched")
                    .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED.value());
            assertNothingIsDisclosed(bodyOf(read));
        }

        @Test
        @DisplayName("no preview, hand-over inspection, payload rendering, progress or retry operation "
                + "exists beside it")
        void noSiblingOperationIsPublished() throws Exception {
            final List<String> absentOperations = List.of(
                    ReportController.REPORT_REQUEST_PATH + "/preview",
                    ReportController.REPORT_REQUEST_PATH + "/status",
                    ReportController.REPORT_REQUEST_PATH + "/retry",
                    ReportController.REPORT_REQUEST_PATH + "/render",
                    ReportController.REPORT_REQUEST_PATH + "/submission",
                    "/api/reports");

            for (final String absent : absentOperations) {
                final MvcResult probe = ReportControllerIT.this.client
                        .perform(MockMvcRequestBuilders.post(absent)
                                .header(HttpHeaders.AUTHORIZATION, ordinarySession())
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON)
                                .content("{}"))
                        .andReturn();

                assertThat(probe.getResponse().getStatus())
                        .as("%s names no operation this boundary publishes", absent)
                        .isEqualTo(HttpStatus.NOT_FOUND.value());
                assertThat(bodyOf(probe))
                        .as("and the refusal echoes neither the path nor anything else about it")
                        .doesNotContain(absent);
            }
            assertNothingWasHandedOver();
        }
    }

    // ===============================================================================================
    // GROUP 9 :: WHO MAY REACH THE SCREEN, AND WHAT A REPLY MAY CARRY
    // ===============================================================================================

    /**
     * The route is classified as reachable by any signed-on caller: it sits outside the administrative
     * prefix, so the closing authenticated rule answers it, and no reply carries anything about an
     * identity beyond what the screen itself needs.
     */
    @Nested
    @DisplayName("Security and leakage negatives")
    class SecurityAndLeakageNegatives {

        /** Creates the group. */
        SecurityAndLeakageNegatives() {
            super();
        }

        @Test
        @DisplayName("a submission carrying no session is refused before the transaction runs, and "
                + "nothing is handed over")
        void aSubmissionCarryingNoSessionIsRefused() throws Exception {
            final MvcResult result = ReportControllerIT.this.client
                    .perform(MockMvcRequestBuilders.post(ReportController.REPORT_REQUEST_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(JSON.writeValueAsString(monthlyTurn(CONFIRM_YES))))
                    .andReturn();

            assertThat(result.getResponse().getStatus())
                    .isEqualTo(HttpStatus.UNAUTHORIZED.value());
            assertNothingIsDisclosed(bodyOf(result));
            assertNothingWasHandedOver();
        }

        @Test
        @DisplayName("an ordinary signed-on caller is admitted, because the screen is a registered "
                + "transaction and not an administrative one")
        void anOrdinarySignedOnCallerIsAdmitted() throws Exception {
            // Every test in this class uses an ordinary session, which is the point: an administrative
            // session would pass the authenticated rule as well and would prove nothing about the gate.
            final MvcResult result = submit(monthlyTurn(CONFIRM_YES));

            assertThat(result.getResponse().getStatus())
                    .as("neither the administrative prefix nor the batch-control rule covers this route")
                    .isEqualTo(HttpStatus.OK.value());
            assertThat(result.getResponse().getStatus())
                    .isNotEqualTo(HttpStatus.FORBIDDEN.value());
        }

        @Test
        @DisplayName("a position wider than the map declares is refused before the transaction runs, "
                + "which is a different outcome from any screen the transaction composes")
        void aPositionWiderThanTheMapDeclaresIsRefused() throws Exception {
            // The year positions are four characters on the map, so a five-character value is malformed
            // rather than merely wrong: declarative validation answers it and no stage of the ordered
            // cascade is entered.
            final MvcResult result =
                    submit(customTurn("06", "01", "20221", "06", "30", "2022", CONFIRM_YES));

            assertThat(result.getResponse().getStatus())
                    .isEqualTo(HttpStatus.BAD_REQUEST.value());
            assertNothingIsDisclosed(bodyOf(result));
            assertNothingWasHandedOver();
        }

        @Test
        @DisplayName("an acknowledged turn discloses nothing about the identity that made it beyond the "
                + "screen state the conversation carries")
        void anAcknowledgedTurnDisclosesNothingAboutTheIdentity() throws Exception {
            final String body = bodyOf(submit(monthlyTurn(CONFIRM_YES)));

            assertNothingIsDisclosed(body);
            assertThat(body)
                    .as("no session is echoed back into a screen payload")
                    .doesNotContain(BEARER_PREFIX)
                    .doesNotContain("eyJ");
        }
    }

    // ===============================================================================================
    // THE GRAPH UNDER TEST
    // ===============================================================================================

    /**
     * The report-request surface: the shipped boundary, the shipped transaction, the shipped filter chain
     * and the credential master the sessions are minted against.
     *
     * <p>Assembled explicitly rather than by scanning, which is this module's established practice for a
     * context-booting specification: a scan of the base package reaches the test tree as well, so it
     * would sweep this suite's own slice configurations into the graph beside the delivered ones.
     *
     * <p><strong>Only the hand-over is stubbed.</strong> The boundary, the contract adapters, the ordered
     * validation, the shared date subprogram, the navigation vocabulary, the message catalogue, the
     * failure policy, the neutral refusal contract, the security chain and the session provider are all
     * the shipped ones, and the identities come off a real server. Batch auto-configuration is excluded
     * because this screen launches nothing, and the tracing exemplar configuration because tracing is
     * off here.
     *
     * <p>The clock is the pinned instant the shared base publishes, so the derived month-to-date and
     * year-to-date ranges and the header stamp mean the same thing on every run.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {PrometheusExemplarsAutoConfiguration.class,
        BatchAutoConfiguration.class})
    @Import({ReportController.class, ReportContractAdapter.class, ConversationStateAdapter.class,
        ScreenStateAdapter.class, GlobalExceptionHandler.class, JsonRefusalBodyRenderer.class,
        ModuleErrorController.class, ReportRequestService.class, DateValidationService.class,
        MessageCatalogService.class, NavigationService.class, SignOnStateService.class,
        SecurityConfig.class, JwtTokenProvider.class, WebMvcConfig.class})
    @EnableConfigurationProperties(JwtProperties.class)
    @EnableJpaRepositories(basePackageClasses = UserSecurityRepository.class)
    @EntityScan(basePackageClasses = UserSecurity.class)
    static class ReportRequestContext {

        /** Creates the slice. */
        ReportRequestContext() {
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
