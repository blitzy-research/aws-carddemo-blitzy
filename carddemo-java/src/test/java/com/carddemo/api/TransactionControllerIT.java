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

import com.carddemo.api.dto.ErrorResponse;
import com.carddemo.api.dto.PageMetadata;
import com.carddemo.api.dto.TransactionAddResponse;
import com.carddemo.api.dto.TransactionListRequest;
import com.carddemo.api.dto.TransactionListResponse;
import com.carddemo.api.dto.TransactionViewResponse;
import com.carddemo.config.JwtProperties;
import com.carddemo.config.JwtTokenProvider;
import com.carddemo.config.SecurityConfig;
import com.carddemo.config.WebMvcConfig;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.enums.UserType;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.DateValidationService;
import com.carddemo.service.MessageCatalogService;
import com.carddemo.service.NavigationService;
import com.carddemo.service.OnlineTransactionBoundary;
import com.carddemo.service.SensitiveFieldEncryptionService;
import com.carddemo.service.SignOnStateService;
import com.carddemo.service.TransactionAddService;
import com.carddemo.service.TransactionListPageTokenService;
import com.carddemo.service.TransactionListService;
import com.carddemo.service.TransactionViewService;
import com.carddemo.util.SensitiveFieldCodec;
import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.support.SensitiveValues;
import com.carddemo.support.TestDataFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.tracing.prometheus.PrometheusExemplarsAutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Interface-contract specification for the three transaction screens over the shipped REST boundary:
 * {@code CT00} the transaction list and search, {@code CT01} the transaction detail view and
 * {@code CT02} the transaction add.
 *
 * <h2>What this specification proves, and why it needs a real server</h2>
 * The three contracts asserted here are externally observable, so they are exercised through the
 * shipped servlet boundary with the shipped filter chain in front of it and a real PostgreSQL 16
 * server behind it. Nothing is mocked and nothing is self-certified: every message text, every row
 * count, every row order and every generated identifier below is read out of a response the module
 * actually composed from rows a real store actually held.
 *
 * <p>Three behaviours carry the weight, and each is a place where a plausible implementation would
 * compile, pass a mocked test and still be a behavioural regression:
 * <ul>
 *   <li><strong>The page is exactly ten rows, and the ten comes from loop bounds alone.</strong>
 *       {@code app/cbl/COTRN00C.cbl} declares no row table for this screen - the only table in the
 *       member is the conversation area at line 89 - so the count is established by the clear loop at
 *       line 290, the index reset at line 295 and the fill loop at line 297. It is published as
 *       {@link PageMetadata#TRANSACTION_LIST_PAGE_SIZE} and is a separate constant from the card
 *       screen's seven and the user screen's ten.</li>
 *   <li><strong>A backward page is filled from the bottom row upward.</strong> The backward paragraph
 *       at line 333 seeds its index to ten at line 349 and walks down to one at lines 351 to 357,
 *       reading in reverse. The published page therefore has to arrive in presentation order with the
 *       highest key on the bottom row, and the assertion below is an ordered one for exactly that
 *       reason: a membership-only assertion passes against a wrong-direction implementation.</li>
 *   <li><strong>The add screen stops at its first failure.</strong> Its cascade is ordered, and the
 *       send paragraph at {@code app/cbl/COTRN02C.cbl} line 516 ends with a return to the transaction
 *       manager, so the first arm that reports also ends the turn. Seven ordering assertions below
 *       each submit a turn that is faulty in two places and require the earlier text.</li>
 * </ul>
 *
 * <h2>Ordering of the add cascade: source order and execution order are not the same</h2>
 * Read as text, the confirmation arm of {@code PROCESS-ENTER-KEY} appears at lines 169 to 188, ahead
 * of the two validation paragraphs at lines 193 and 235. Read as execution, the paragraph performs
 * both validations first and evaluates the confirmation last. Because the send paragraph returns to
 * the transaction manager rather than falling through, a turn that is faulty in a key field never
 * reaches the confirmation arm at all. Both facts are asserted below - the confirmation texts are
 * pinned on turns where the confirmation is the only fault, and the precedence assertion requires the
 * key-field text when a turn is faulty in both places. Neither fact is corrected in either direction.
 *
 * <h2>Container ownership</h2>
 * The database is owned entirely by {@link AbstractPostgresIT}, which starts one server for the whole
 * module and migrates it. This class declares no container, no container type, no data-source property
 * source, no context-dirtying annotation and no active profile: doing any of those would either compete
 * with what the base centralises or stop the shared server underneath every sibling specification. The
 * clock is the base's pinned instant, so no assertion here can depend on a wall clock.
 *
 * <h2>Reserved data range</h2>
 * The delivered reference seed applies no {@code transaction} row at all, so the delivered state of
 * that table is empty. Every row this class needs is written under the reserved identifier prefix
 * {@value #RESERVED_KEY_PREFIX} and removed again afterwards, which is what lets the generated-identifier
 * assertions read a genuinely empty table and lets the paging assertions know the whole ordered
 * sequence they are walking. No delivered row of any other table is written, counted or removed.
 *
 * <h2>No credential is read, held or rendered</h2>
 * A session is minted by asking the shipped token provider directly, which is what the sign-on path
 * itself does once it has verified a credential. The legacy cleartext value appears nowhere in this
 * file in any form.
 *
 * <p>Provenance: {@code app/cbl/COTRN00C.cbl}, {@code app/cbl/COTRN01C.cbl},
 * {@code app/cbl/COTRN02C.cbl}, {@code app/cpy-bms/COTRN00.CPY}, {@code app/cpy-bms/COTRN01.CPY},
 * {@code app/cpy-bms/COTRN02.CPY}, {@code app/cpy/CVTRA05Y.cpy}, {@code app/cpy/CSMSG01Y.cpy},
 * {@code app/cpy/COCOM01Y.cpy} and {@code app/cbl/CSUTLDTC.cbl}, all read as read-only reference.
 * Message texts, field widths, record offsets, counts, program names and paragraph line numbers are
 * contract and metadata; no source line of any legacy member is transcribed, and nothing in the legacy
 * tree is read at run time.
 */
@SpringBootTest(classes = TransactionControllerIT.TransactionScreensContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            // The shared server was migrated to the head of the delivered set by the base class, so a
            // second migration from this context would be redundant work with no new state to apply.
            "spring.flyway.enabled=false",
            // validate, never none and never a generating value: a mapping that had drifted from the
            // migrated schema must fail this specification at refresh rather than be reconciled behind
            // it, and validate cannot emit any schema statement of its own.
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.batch.job.enabled=false",
            "spring.main.banner-mode=off",
            "management.endpoint.health.validate-group-membership=false",
            "management.tracing.enabled=false",
            // The boundary is a mock servlet inside this process, so there is no wire for a session to
            // be observed on and the transport requirement is relaxed exactly as the suite profile
            // relaxes it. Nothing else is relaxed: the anonymous set and the administrative gate are
            // the shipped ones, which is the whole point of asserting against them.
            "carddemo.security.require-https=false",
            "carddemo.security.jwt.issuer=carddemo-java",
            "carddemo.security.jwt.expiration=PT15M"})
@AutoConfigureMockMvc
@DisplayName("Gate 5: the transaction list, view and add screens of CT00, CT01 and CT02")
public class TransactionControllerIT extends AbstractPostgresIT {

    // ===============================================================================================
    // THE CONTRACT OF THE LIST SCREEN, CT00
    // ===============================================================================================

    /**
     * Rejection of a row action the screen does not offer, from {@code app/cbl/COTRN00C.cbl} line 199.
     *
     * <p>The noun is <strong>singular</strong>. The administrative user-list screen carries a twin text
     * whose noun is plural because that screen offers two actions; the two are never interchangeable and
     * are never shared, which is why this value is written out here in full rather than derived.
     */
    private static final String INVALID_SELECTION = "Invalid selection. Valid value is S";

    /**
     * Rejection of a filter that is not all digits, from line 214.
     *
     * <p>The only one of this screen's six texts that carries a <strong>space before its dots</strong>.
     * Asserted separately from the five below for that reason alone.
     */
    private static final String FILTER_NOT_NUMERIC = "Tran ID must be Numeric ...";

    /** Backward paging refused because the browse is on its first page, from line 248. */
    private static final String ALREADY_AT_TOP = "You are already at the top of the page...";

    /** Forward paging refused because the previous turn found no further page, from line 270. */
    private static final String ALREADY_AT_BOTTOM = "You are already at the bottom of the page...";

    /** The browse could not be positioned at all, from line 608. */
    private static final String AT_TOP = "You are at the top of the page...";

    /** A forward read reached the end of the sequence, from line 642. */
    private static final String REACHED_BOTTOM = "You have reached the bottom of the page...";

    /** A backward read reached the start of the sequence, from line 676. */
    private static final String REACHED_TOP = "You have reached the top of the page...";

    // ===============================================================================================
    // THE CONTRACT OF THE VIEW SCREEN, CT01
    // ===============================================================================================

    /**
     * The search field arrived blank, from {@code app/cbl/COTRN01C.cbl} line 149.
     *
     * <p>The negation is <strong>capitalised</strong>, there is no space before the dots, and there are
     * exactly three of them.
     */
    private static final String VIEW_TRAN_ID_EMPTY = "Tran ID can NOT be empty...";

    /** The keyed read matched no row, from line 285. */
    private static final String VIEW_TRAN_ID_NOT_FOUND = "Transaction ID NOT found...";

    // ===============================================================================================
    // THE TWO SHARED TEXTS, AT THEIR FULL STORED WIDTH
    // ===============================================================================================

    /**
     * The shared courtesy text the exit key composes, at its full stored width.
     *
     * <p>{@code app/cpy/CSMSG01Y.cpy} declares a fifty-character field and writes a forty-nine
     * character literal into it, so the stored value is the forty-three character text followed by
     * exactly seven spaces. Written out in full, trailing spaces included, and asserted at full width.
     */
    private static final String THANK_YOU_MESSAGE_50 =
            "Thank you for using CardDemo application...       ";

    /**
     * The shared text any unmapped attention key composes, at its full stored width from the same
     * fifty-character field: the forty character text followed by exactly ten spaces.
     */
    private static final String INVALID_KEY_MESSAGE_50 =
            "Invalid key pressed. Please see below...          ";

    /** Width of the two shared texts, as their common field declares it. */
    private static final int COMMON_MESSAGE_WIDTH = 50;

    // ===============================================================================================
    // WIDTHS THIS SPECIFICATION PINS, EACH BELONGING TO ONE SCREEN
    // ===============================================================================================

    /** Width of the description column on the list screen. Twenty-six here, and only here. */
    private static final int LIST_DESCRIPTION_WIDTH = 26;

    /** Width of the description item on the view screen. Sixty, and never the list's twenty-six. */
    private static final int VIEW_DESCRIPTION_WIDTH = 60;

    /** Width of the date column on the list screen. */
    private static final int LIST_DATE_WIDTH = 8;

    /** Width of either date item on the view screen. Ten, and never the list column's eight. */
    private static final int VIEW_DATE_WIDTH = 10;

    /** Width of the page indicator on the list screen. The card screen's own indicator is three. */
    private static final int LIST_PAGE_INDICATOR_WIDTH = 8;

    /** Width of the page indicator on the card list screen, named only to be required to differ. */
    private static final int CARD_PAGE_INDICATOR_WIDTH = 3;

    /** Width of the summary message item on all three of these screens. */
    private static final int SCREEN_MESSAGE_WIDTH = 78;

    /** Width of a stored timestamp column, and of the value a blank one holds. */
    private static final int STORED_TIMESTAMP_WIDTH = 26;

    /** Width of the generated transaction identifier and of the key space it is drawn from. */
    private static final int TRANSACTION_ID_WIDTH = 16;

    // ===============================================================================================
    // THE RESERVED RANGE THIS SPECIFICATION OWNS
    // ===============================================================================================

    /**
     * Prefix of the identifier range this specification writes into.
     *
     * <p>Four digits, chosen high so the whole range sorts above anything a sibling specification is
     * likely to write, and matched by the schema's own rule that an identifier is sixteen digits. Every
     * row written here carries it, and the cleanup removes the table's contents back to the delivered
     * empty state, so no delivered row of any table is touched.
     */
    private static final String RESERVED_KEY_PREFIX = "9911";

    /** Number of rows the ordered fixture holds: two full pages and one row beyond them. */
    private static final int ORDERED_FIXTURE_ROWS = 21;

    /** The card number every reserved row carries, taken from the delivered cross-reference seed. */
    private static final String SEEDED_CARD_NUMBER = "0500024453765740";

    /** The account identifier that cross-reference row carries, at its stored width. */
    private static final String SEEDED_ACCOUNT_ID = "00000000050";

    /** An account identifier inside the eleven-digit space that no delivered row carries. */
    private static final String ABSENT_ACCOUNT_ID = "00000000999";

    /** A card number inside the sixteen-digit space that no delivered row carries. */
    private static final String ABSENT_CARD_NUMBER = "9999999999999999";

    /**
     * Description stored on the one reserved row that exists to prove two different screen widths.
     *
     * <p>Eighty characters: longer than the view screen's sixty and much longer than the list column's
     * twenty-six, and shorter than the hundred the record stores, so one stored value proves both bounds
     * and proves they are not the same bound.
     */
    private static final String OVERLONG_DESCRIPTION =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGH";

    /** Identifier of the reserved row carrying {@link #OVERLONG_DESCRIPTION}. */
    private static final String WIDE_DESCRIPTION_ROW_ID = RESERVED_KEY_PREFIX + "500000000001";

    /**
     * Amount stored on the widths row: nine integer digits and two decimals, the widest the record
     * field admits, so a producer that reached for an exponent form would be caught by the raw body.
     */
    private static final String WIDEST_AMOUNT_TEXT = "100000000.00";

    /** The two delivered identities a session is minted for. */
    private static final String SEEDED_ADMIN_ID = "ADMIN001";

    /** The delivered ordinary identity, which these three routes admit exactly as they admit the other. */
    private static final String SEEDED_USER_ID = "USER0001";

    /** Presentation prefix a minted session travels behind. */
    private static final String BEARER_PREFIX = "Bearer ";

    /** Summary a refusal for want of a session carries. */
    private static final String AUTHENTICATION_REQUIRED = "Authentication required";

    /** Reads and writes JSON without binding to a published contract type. */
    private static final ObjectMapper JSON = new ObjectMapper();

    // ===============================================================================================
    // COLLABORATORS
    // ===============================================================================================

    /** The shipped servlet boundary, with the shipped filter chain in front of it. */
    @Autowired
    private MockMvc mockMvc;

    /** The shipped repository, used to write the reserved rows and to read back what a turn stored. */
    @Autowired
    private TransactionRepository transactions;

    /** The shipped session token provider; nothing here re-implements it. */
    @Autowired
    private JwtTokenProvider tokenProvider;

    /** The shipped shared-message catalogue, for the assertions that are about the catalogue itself. */
    @Autowired
    private MessageCatalogService messages;

    /** Creates the specification. */
    public TransactionControllerIT() {
        super();
    }

    // ===============================================================================================
    // LIFECYCLE
    // ===============================================================================================

    /**
     * Returns the shared server to its delivered state, so the ten delivered identities and the whole
     * reference seed are present however the run that preceded this one ended.
     *
     * @throws SQLException if the delivered state cannot be restored
     */
    @BeforeAll
    static void restoreDeliveredState() throws SQLException {
        restoreSeededState();
    }

    /**
     * Empties the transaction table before every method.
     *
     * <p>This restores the delivered state of that one table rather than departing from it: the
     * reference seed applies no transaction row, so an empty table is exactly what a fresh migration
     * leaves. Every method therefore starts from a known sequence - either empty, or exactly the rows it
     * wrote for itself - which is what makes both the paging order and the first generated identifier
     * assertable at all. It is a single statement rather than a per-row delete.
     */
    @BeforeEach
    void emptyTheTransactionTable() {
        this.transactions.deleteAllInBatch();
    }

    /** Leaves the transaction table in the delivered empty state for whatever runs next. */
    @AfterEach
    void clearReservedRows() {
        this.transactions.deleteAllInBatch();
    }

    // ===============================================================================================
    // FIXTURE
    // ===============================================================================================

    /**
     * Writes twenty-one reserved rows whose identifiers ascend by one, and answers them in that order.
     *
     * <p>Twenty-one is chosen so the sequence is two full pages of ten plus one row beyond them: the
     * extra row is what lets a forward turn discover that a further page exists, and the two full pages
     * are what let a backward turn be walked back over a page it did not itself assemble.
     *
     * <p>Every row carries a distinct amount and a distinct description, so an assertion on the ordered
     * page is an assertion about which row landed in which slot rather than merely about how many
     * landed. The stored timestamps come from the shared factory, which means the processing timestamp
     * is the twenty-six blanks every delivered daily record carries.
     *
     * @return the written rows, in ascending identifier order
     */
    private List<Transaction> writeOrderedFixture() {
        final List<Transaction> ordered = new ArrayList<>(ORDERED_FIXTURE_ROWS);
        for (int row = 1; row <= ORDERED_FIXTURE_ROWS; row++) {
            ordered.add(TestDataFactory.transaction()
                    .id(reservedIdentifier(row))
                    .cardNumber(SEEDED_CARD_NUMBER)
                    .description("Reserved row " + row)
                    .amount(new BigDecimal(Integer.toString(100 + row) + ".00"))
                    .build());
        }
        this.transactions.saveAll(ordered);
        this.transactions.flush();
        return List.copyOf(ordered);
    }

    /**
     * Writes the one reserved row whose stored values are wider than two of the screens display.
     *
     * @return the written row
     */
    private Transaction writeWideValuesRow() {
        final Transaction wide = TestDataFactory.transaction()
                .id(WIDE_DESCRIPTION_ROW_ID)
                .cardNumber(SEEDED_CARD_NUMBER)
                .description(OVERLONG_DESCRIPTION)
                .amount(new BigDecimal(WIDEST_AMOUNT_TEXT))
                .build();
        this.transactions.saveAll(List.of(wide));
        this.transactions.flush();
        return wide;
    }

    /**
     * Builds the reserved identifier for one row of the ordered fixture.
     *
     * <p>Sixteen digits with the reserved prefix in front, which satisfies the schema's own rule that
     * every identifier is exactly sixteen digits and keeps character order and numeric order in
     * agreement across the whole fixture.
     *
     * @param  row the one-based row number
     * @return the identifier
     */
    private static String reservedIdentifier(final int row) {
        return RESERVED_KEY_PREFIX
                + String.format(Locale.ROOT, "%012d", row);
    }

    /**
     * Mints a session for a delivered identity, without naming a credential.
     *
     * <p>The shipped provider is asked directly, which is what the sign-on path itself does once it has
     * verified a credential. No cleartext value is read, held or rendered anywhere in this class. The
     * provider refuses to mint for an identity no record carries, so a token existing at all is evidence
     * that the credential seed applied, and both the issue and the later verification read the pinned
     * clock, so the session neither expires nor depends on when the suite runs.
     *
     * @param  userId   the delivered identifier to mint for
     * @param  userType the role the record carries
     * @return the value of the authorization header, including its presentation prefix
     */
    private String sessionFor(final String userId, final UserType userType) {
        return BEARER_PREFIX + this.tokenProvider.issue(userId, userType, userType.getCode());
    }

    /**
     * Mints the administrative session most methods here use.
     *
     * <p>The role is immaterial to these three routes, which are answered by the chain's closing
     * authenticated rule rather than by an entitlement, and one method below asserts exactly that by
     * repeating a turn under the ordinary identity.
     *
     * @return the value of the authorization header
     */
    private String session() {
        return sessionFor(SEEDED_ADMIN_ID, UserType.ADMIN);
    }

    // ===============================================================================================
    // TURN SUBMISSION
    // ===============================================================================================

    /**
     * Submits one turn of the list screen and returns the whole result.
     *
     * @param  body the request payload, as the published contract's own member names
     * @return the completed exchange, so both the body and the status can be inspected
     * @throws Exception if the boundary cannot be reached
     */
    private MvcResult postList(final Map<String, Object> body) throws Exception {
        return this.mockMvc.perform(MockMvcRequestBuilders
                        .post(TransactionController.TRANSACTION_PATH + TransactionController.LIST_PATH)
                        .header(HttpHeaders.AUTHORIZATION, session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding(StandardCharsets.UTF_8)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(body)))
                .andReturn();
    }

    /**
     * Submits one turn of the view screen and returns the whole result.
     *
     * @param  typedIdentifier  the identifier typed into the search field, or {@code null} to omit it
     * @param  handedIdentifier the identifier a calling screen handed over, or {@code null} to omit it
     * @param  keyAction        the attention key, or {@code null} to omit it
     * @param  navigation       the echoed navigation record, or {@code null} to send no body at all
     * @return the completed exchange
     * @throws Exception if the boundary cannot be reached
     */
    private MvcResult postView(final String typedIdentifier, final String handedIdentifier,
            final String keyAction, final Map<String, Object> navigation) throws Exception {
        final var request = MockMvcRequestBuilders
                .post(TransactionController.TRANSACTION_PATH + TransactionController.VIEW_PATH)
                .header(HttpHeaders.AUTHORIZATION, session())
                .characterEncoding(StandardCharsets.UTF_8)
                .accept(MediaType.APPLICATION_JSON);
        if (typedIdentifier != null) {
            request.param("transactionId", typedIdentifier);
        }
        if (handedIdentifier != null) {
            request.param("selectedTransactionId", handedIdentifier);
        }
        if (keyAction != null) {
            request.param("keyAction", keyAction);
        }
        if (navigation != null) {
            request.contentType(MediaType.APPLICATION_JSON)
                    .content(JSON.writeValueAsString(navigation));
        }
        return this.mockMvc.perform(request).andReturn();
    }

    /**
     * Submits one turn of the add screen and returns the whole result.
     *
     * @param  body the request payload, as the published contract's own member names
     * @return the completed exchange
     * @throws Exception if the boundary cannot be reached
     */
    private MvcResult postAdd(final Map<String, Object> body) throws Exception {
        return this.mockMvc.perform(MockMvcRequestBuilders
                        .post(TransactionController.TRANSACTION_PATH + TransactionController.ADD_PATH)
                        .header(HttpHeaders.AUTHORIZATION, session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding(StandardCharsets.UTF_8)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(body)))
                .andReturn();
    }

    /**
     * Parses the body of a completed turn, having first required the status the contract fixes.
     *
     * @param  result the completed exchange
     * @return the parsed body
     * @throws Exception if the body cannot be parsed
     */
    private static JsonNode bodyOf(final MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as("every outcome of these three transactions is a screen the legacy program composed "
                        + "and sent, rejections included, so the turn completes with the same status in "
                        + "all of them")
                .isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /**
     * Reads a textual component of a response body, distinguishing absent from present-and-null.
     *
     * @param  body the parsed body
     * @param  name the component name, as the published contract type declares it
     * @return the value, or {@code null} when the component is absent or null
     */
    private static String textOf(final JsonNode body, final String name) {
        final JsonNode node = body.get(name);
        return node == null || node.isNull() ? null : node.asText();
    }

    /**
     * Reads the error switch, which is a component of its own and is never inferred from anything else.
     *
     * @param  body the parsed body
     * @param  name the component name the screen declares for it
     * @return the value the boundary published
     */
    private static boolean switchOf(final JsonNode body, final String name) {
        final JsonNode node = body.get(name);
        assertThat(node)
                .as("the error switch is an explicit component of the published contract, so a body "
                        + "that omitted it would leave every caller inferring it from a message being "
                        + "present")
                .isNotNull();
        return node.asBoolean();
    }

    /**
     * Reads one numeric member's literal characters straight out of a response body.
     *
     * <p>A parsed number has already been re-typed by whatever read it, which loses the very property
     * under assertion: a decimal of scale two and a floating value of the same magnitude parse to the same
     * number and reach the wire as different bytes. Reading the characters is therefore the only way to
     * assert what the boundary actually wrote.
     *
     * @param  rawBody the response body, exactly as it was written
     * @param  member  the member name whose literal is wanted
     * @return the characters between the member's colon and the value's terminator
     */
    private static String numberLiteralOf(final String rawBody, final String member) {
        final String marker = "\"" + member + "\":";
        final int markerAt = rawBody.indexOf(marker);
        assertThat(markerAt)
                .as("the body carries the member whose wire form is under assertion")
                .isNotNegative();
        final int from = markerAt + marker.length();
        int to = from;
        while (to < rawBody.length() && rawBody.charAt(to) != ',' && rawBody.charAt(to) != '}') {
            to++;
        }
        return rawBody.substring(from, to);
    }

    /**
     * Reads the identifiers of the published page, in the order the page published them.
     *
     * @param  body the parsed list-screen body
     * @return the identifiers, in published order
     */
    private static List<String> publishedIdentifiers(final JsonNode body) {
        final JsonNode rows = body.get("rows");
        assertThat(rows).as("the page is a component of its own and is never absent").isNotNull();
        final List<String> identifiers = new ArrayList<>(rows.size());
        for (final JsonNode row : rows) {
            identifiers.add(textOf(row, "transactionId"));
        }
        return List.copyOf(identifiers);
    }

    /**
     * Reads the slot indices of the published page, in the order the page published them.
     *
     * @param  body the parsed list-screen body
     * @return the one-based slot indices, in published order
     */
    private static List<Integer> publishedSlots(final JsonNode body) {
        final JsonNode rows = body.get("rows");
        assertThat(rows).as("the page is a component of its own and is never absent").isNotNull();
        final List<Integer> slots = new ArrayList<>(rows.size());
        for (final JsonNode row : rows) {
            slots.add(row.get("screenRow").asInt());
        }
        return List.copyOf(slots);
    }

    /**
     * Builds the echoed navigation record that marks a turn as a re-submission.
     *
     * <p>Without it a turn is a first entry, and the legacy first-entry path discards every submitted
     * screen field before it runs - so a filter, a row selector or a paging key would never be read. The
     * assertions that exercise those three all send this.
     *
     * @return the navigation payload
     */
    private static Map<String, Object> reEntry() {
        final Map<String, Object> navigation = new LinkedHashMap<>();
        navigation.put("programContext", "REENTER");
        return navigation;
    }

    /**
     * Builds a positional selector sequence of ten slots with one slot marked.
     *
     * @param  markedSlot the one-based slot to mark
     * @param  marker     the character to place in it
     * @return ten entries, blank in every slot but the marked one
     */
    private static List<String> selectorsWith(final int markedSlot, final String marker) {
        final List<String> selectors = new ArrayList<>(TransactionListRequest.ROW_SELECTOR_COUNT);
        for (int slot = 1; slot <= TransactionListRequest.ROW_SELECTOR_COUNT; slot++) {
            selectors.add(slot == markedSlot ? marker : " ");
        }
        return List.copyOf(selectors);
    }

    /**
     * Builds a positional selector sequence of ten slots with two slots marked.
     *
     * @param  firstSlot   the lower one-based slot to mark
     * @param  secondSlot  the higher one-based slot to mark
     * @param  marker      the character to place in both
     * @return ten entries, blank in every slot but the two marked ones
     */
    private static List<String> selectorsWithBoth(final int firstSlot, final int secondSlot,
            final String marker) {
        final List<String> selectors = new ArrayList<>(TransactionListRequest.ROW_SELECTOR_COUNT);
        for (int slot = 1; slot <= TransactionListRequest.ROW_SELECTOR_COUNT; slot++) {
            selectors.add(slot == firstSlot || slot == secondSlot ? marker : " ");
        }
        return List.copyOf(selectors);
    }

    /**
     * Builds the browse state a client echoes from a page it was served.
     *
     * <p>The whole of it is published by the turn that served the page, so a client never assembles
     * cross-turn state of its own. It carries the two boundary keys, the direction the page was
     * assembled in, the page label and whether a further page follows - and <strong>no transaction
     * identifier of a displayed row</strong>. These keys position the page that follows; they do not
     * name a marked row. A marking turn establishes the marked row's identity from the sealed slot
     * snapshot the same response published, which callers echo alongside this payload under
     * {@code rowSnapshotToken}, so an edited boundary key cannot move which row a mark resolves to.</p>
     *
     * @param  served the body of the page being echoed
     * @return the paging payload in the shape the request accepts inbound
     */
    private static Map<String, Object> pagingStateFrom(final JsonNode served) {
        final JsonNode published = served.get("pageMetadata");
        assertThat(published)
                .as("the browse state a client echoes is published by the turn that served the page")
                .isNotNull();
        final Map<String, Object> paging = new LinkedHashMap<>();
        paging.put("previousCursorKey", textOf(published, "previousCursorKey"));
        paging.put("nextCursorKey", textOf(published, "nextCursorKey"));
        paging.put("direction", textOf(published, "direction"));
        paging.put("displayedPageNumber", textOf(published, "displayedPageNumber"));
        paging.put("nextPageIndicated", published.get("hasMorePages").asBoolean());
        return paging;
    }

    /**
     * The same browse state with one byte of its forward boundary key altered.
     *
     * @param  served the body of the page being echoed
     * @return the paging payload, naming a position the served page did not begin at
     */
    private static Map<String, Object> pagingStateWithATamperedForwardKey(final JsonNode served) {
        final Map<String, Object> paging = pagingStateFrom(served);
        final String issued = (String) paging.get("previousCursorKey");
        assertThat(issued).as("the served page names its own first slot").isNotNull();
        final char lastByte = issued.charAt(issued.length() - 1);
        final char altered = lastByte == '9' ? '8' : (char) (lastByte + 1);
        paging.put("previousCursorKey", issued.substring(0, issued.length() - 1) + altered);
        return paging;
    }

    /**
     * Names the identifiers of an inclusive slice of the ordered fixture, in ascending order.
     *
     * @param  ordered  the fixture, in the order it was written
     * @param  firstRow the one-based first row of the slice
     * @param  lastRow  the one-based last row of the slice
     * @return the identifiers of that slice, ascending
     */
    private static List<String> identifiersOf(final List<Transaction> ordered, final int firstRow,
            final int lastRow) {
        final List<String> identifiers = new ArrayList<>((lastRow - firstRow) + 1);
        for (int row = firstRow; row <= lastRow; row++) {
            identifiers.add(ordered.get(row - 1).getTranId());
        }
        return List.copyOf(identifiers);
    }

    /**
     * Starts a list-screen payload carrying one attention key.
     *
     * @param  keyAction the attention key, by the domain constant's own name
     * @return a mutable payload the caller adds its own members to
     */
    private static Map<String, Object> listBody(final String keyAction) {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("keyAction", keyAction);
        return body;
    }

    /**
     * Builds an add-screen payload that is valid in every one of the twenty-three checked places.
     *
     * <p>Every value here is the shape the screen requires, so a method that wants to exercise one arm
     * of the cascade replaces exactly one member and nothing else can be the reason the turn is refused.
     * The amount is the twelve-character edited form the screen declares, the two dates are ten
     * characters each, the merchant identifier is nine digits, and the account identifier is one the
     * delivered cross-reference seed carries.
     *
     * @param  confirmation the confirmation answer to submit
     * @return a mutable payload the caller adjusts
     */
    private static Map<String, Object> validAddBody(final String confirmation) {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountId", SEEDED_ACCOUNT_ID);
        body.put("typeCode", "01");
        body.put("categoryCode", "0001");
        body.put("transactionSource", "POS TERM");
        body.put("description", "Reserved contract row");
        body.put("amount", "-00000100.00");
        body.put("originationDate", "2022-06-10");
        body.put("processingDate", "2022-06-10");
        body.put("merchantId", "800000000");
        body.put("merchantName", "Abshire-Lowe");
        body.put("merchantCity", "North Enoshaven");
        body.put("merchantZip", "72112");
        body.put("confirm", confirmation);
        body.put("keyAction", "ENTER");
        body.put("navigationContext", reEntry());
        return body;
    }

    /**
     * Submits an add turn built from the valid payload with one member replaced, and answers the
     * summary text the turn reported.
     *
     * @param  overrides the members to replace, in the order they are applied
     * @return the summary message the screen composed
     * @throws Exception if the boundary cannot be reached
     */
    private String addSummaryWith(final Map<String, Object> overrides) throws Exception {
        final Map<String, Object> body = validAddBody("Y");
        body.putAll(overrides);
        return textOf(bodyOf(postAdd(body)), "message");
    }

    /**
     * Builds a one-member override map.
     *
     * @param  member the member name, as the published contract declares it
     * @param  value  the value to submit
     * @return the override map
     */
    private static Map<String, Object> override(final String member, final Object value) {
        final Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put(member, value);
        return overrides;
    }

    /**
     * Builds a two-member override map, which is how a turn is made faulty in two ordered places at
     * once.
     *
     * @param  firstMember  the member of the earlier stage
     * @param  firstValue   the value that faults it
     * @param  secondMember the member of the later stage
     * @param  secondValue  the value that faults it
     * @return the override map
     */
    private static Map<String, Object> override(final String firstMember, final Object firstValue,
            final String secondMember, final Object secondValue) {
        final Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put(firstMember, firstValue);
        overrides.put(secondMember, secondValue);
        return overrides;
    }

    // ===============================================================================================
    // CT00 :: THE PAGE IS TEN ROWS, AND THE TEN COMES FROM LOOP BOUNDS
    // ===============================================================================================

    /** The page size, and the fact that it is this screen's own figure and nobody else's. */
    @Nested
    @DisplayName("List - page size 10 from loop bounds")
    class ListPageSize {

        /** Creates the group. */
        ListPageSize() {
            // Intentionally empty: the group contributes assertions, not state.
        }

        /**
         * A browse with more than ten rows available publishes exactly ten.
         *
         * <p>Traceability: the fill loop at {@code app/cbl/COTRN00C.cbl} line 297 stops once the index
         * passes ten, having been reset to one at line 295.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("a full page carries exactly ten rows even though twenty-one are available")
        void aFullPageCarriesExactlyTenRows() throws Exception {
            writeOrderedFixture();

            final JsonNode body = bodyOf(postList(listBody("ENTER")));

            assertThat(publishedIdentifiers(body))
                    .as("the screen has ten row slots and the fill loop stops after the tenth, so a "
                            + "browse holding twenty-one rows still publishes ten")
                    .hasSize(PageMetadata.TRANSACTION_LIST_PAGE_SIZE)
                    .hasSize(10);
        }

        /**
         * The ten slots are published as slots one to ten, in that order.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("the ten rows carry slot indices one to ten in order")
        void theTenRowsCarrySlotIndicesOneToTenInOrder() throws Exception {
            writeOrderedFixture();

            final JsonNode body = bodyOf(postList(listBody("ENTER")));

            assertThat(publishedSlots(body))
                    .as("a row's slot is what aligns it with the positional selector sequence, so the "
                            + "slots are published rather than left to be inferred from list position")
                    .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        }

        /**
         * The published paging metadata declares this screen's own page size.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("the paging metadata declares a page size of ten")
        void thePagingMetadataDeclaresAPageSizeOfTen() throws Exception {
            writeOrderedFixture();

            final JsonNode paging = bodyOf(postList(listBody("ENTER"))).get("pageMetadata");

            assertThat(paging)
                    .as("the paging state is a component of its own rather than something a client "
                            + "reconstructs from the row count")
                    .isNotNull();
            assertThat(paging.get("pageSize").asInt())
                    .as("the page size the metadata declares is the screen's own row count")
                    .isEqualTo(PageMetadata.TRANSACTION_LIST_PAGE_SIZE);
        }

        /**
         * The three screen page sizes are three separately named figures.
         *
         * <p>The card screen's seven and this screen's ten differ outright. This screen's ten and the
         * user screen's ten agree in value and are still two figures: each is declared under its own
         * screen's name, and the transaction page is required below to be published from the
         * transaction-named one. Nothing derives either from the other, and nothing derives either from
         * the largest-screen bound, which exists only to bound the published value declaratively.
         */
        @Test
        @DisplayName("card seven, transaction ten and user ten are three separately named figures")
        void theThreeScreenPageSizesAreSeparatelyNamed() {
            assertThat(PageMetadata.CARD_LIST_PAGE_SIZE)
                    .as("the card list screen fills seven row slots")
                    .isEqualTo(7)
                    .isNotEqualTo(PageMetadata.TRANSACTION_LIST_PAGE_SIZE);
            assertThat(PageMetadata.TRANSACTION_LIST_PAGE_SIZE)
                    .as("the transaction list screen fills ten row slots, established by loop bounds")
                    .isEqualTo(10);
            assertThat(PageMetadata.USER_LIST_PAGE_SIZE)
                    .as("the administrative user list screen fills ten row slots, established by its "
                            + "own row table and not by this screen's loop bounds")
                    .isEqualTo(10);
            assertThat(PageMetadata.LARGEST_SCREEN_PAGE_SIZE)
                    .as("the declarative bound on a published page size is the largest of the three "
                            + "and is not itself any screen's size")
                    .isEqualTo(10);
        }

        /**
         * The paging metadata is cursor-based, and it publishes nothing beyond the seven members that
         * make it so.
         *
         * <p>The absence assertion is made by fixing the whole published member set rather than by naming
         * a handful of forbidden members. That is the stronger form and it is the honest one: the legacy
         * browse never counts its cluster and discovers the existence of a further page one read at a
         * time, so any total, page count, offset or element count would be a figure no legacy turn ever
         * produced - and enumerating a few such names would leave every name nobody thought of admissible.
         * Fixing the set forbids all of them at once.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("the paging metadata publishes exactly seven cursor-based members and nothing else")
        void thePagingMetadataIsCursorBased() throws Exception {
            writeOrderedFixture();

            final JsonNode paging = bodyOf(postList(listBody("ENTER"))).get("pageMetadata");

            final List<String> published = new ArrayList<>();
            for (final Map.Entry<String, JsonNode> member : paging.properties()) {
                published.add(member.getKey());
            }
            assertThat(published)
                    .as("two cursor keys drive the two directions, two switches report the two ends, and "
                            + "the page size, the direction and the operator-visible indicator complete "
                            + "the set - nothing counts, totals or offsets anything")
                    .containsExactlyInAnyOrder("pageSize", "previousCursorKey", "nextCursorKey",
                            "direction", "hasMorePages", "hasPreviousPages", "displayedPageNumber")
                    .hasSize(7);
            assertThat(textOf(paging, "previousCursorKey"))
                    .as("a backward turn is driven by the key the page started at")
                    .isNotNull();
            assertThat(textOf(paging, "nextCursorKey"))
                    .as("a forward turn is driven by the key the page ended at")
                    .isNotNull();
        }

        /**
         * The two paging indicators are two separate switches rather than one tri-state value.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("has-more and has-previous are two separate switches")
        void hasMoreAndHasPreviousAreTwoSeparateSwitches() throws Exception {
            writeOrderedFixture();

            final JsonNode paging = bodyOf(postList(listBody("ENTER"))).get("pageMetadata");

            assertThat(switchOf(paging, "hasMorePages"))
                    .as("twenty-one rows were available and ten were shown, so a further page exists "
                            + "and the forward guard must find it")
                    .isTrue();
            assertThat(switchOf(paging, "hasPreviousPages"))
                    .as("the first page has nothing before it, which is a different fact from a "
                            + "further page existing and therefore a different switch")
                    .isFalse();
        }

        /**
         * The published page indicator is this screen's eight-character field.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("the page indicator is eight characters wide, never the card screen's three")
        void thePageIndicatorIsEightCharactersWide() throws Exception {
            writeOrderedFixture();

            final JsonNode body = bodyOf(postList(listBody("ENTER")));

            assertThat(textOf(body, "displayedPageNumber"))
                    .as("the indicator is a character field with significant leading zeros, so the "
                            + "first page is published as eight characters and never as the number one")
                    .isEqualTo("00000001")
                    .hasSize(LIST_PAGE_INDICATOR_WIDTH)
                    .hasSize(TransactionListResponse.DISPLAYED_PAGE_NUMBER_LENGTH);
            assertThat(LIST_PAGE_INDICATOR_WIDTH)
                    .as("the card list screen's own indicator is three characters, so the two are "
                            + "never one shared width")
                    .isNotEqualTo(CARD_PAGE_INDICATOR_WIDTH);
        }

        /**
         * A second forward page advances the indicator and reports a preceding page.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("a forward page publishes the second indicator and reports a preceding page")
        void aForwardPagePublishesTheSecondIndicator() throws Exception {
            final List<Transaction> ordered = writeOrderedFixture();
            final JsonNode firstPage = bodyOf(postList(listBody("ENTER")));

            final Map<String, Object> forward = listBody("PFK08");
            forward.put("navigationContext", reEntry());
            forward.put("pageMetadata", pagingStateFrom(firstPage));
            forward.put("rowSnapshotToken", textOf(firstPage, "rowSnapshotToken"));
            final JsonNode secondPage = bodyOf(postList(forward));

            assertThat(publishedIdentifiers(secondPage))
                    .as("the second page is the next ten identifiers of the ascending sequence, "
                            + "continuing from the key the first page ended at")
                    .containsExactlyElementsOf(identifiersOf(ordered, 11, 20));
            assertThat(textOf(secondPage, "displayedPageNumber"))
                    .as("the indicator advances by one page and keeps its eight characters")
                    .isEqualTo("00000002");
            assertThat(switchOf(secondPage.get("pageMetadata"), "hasPreviousPages"))
                    .as("a second page has a page before it")
                    .isTrue();
        }
    }

    // ===============================================================================================
    // CT00 :: A BACKWARD PAGE IS FILLED FROM THE BOTTOM ROW UPWARD
    // ===============================================================================================

    /**
     * The direction a backward page is filled in, asserted as an order rather than as a membership.
     *
     * <p>Every assertion in this group is an ordered one on purpose. The legacy backward paragraph reads
     * the cluster in reverse and places what it reads into the bottom slot first, so the page it sends
     * has the highest key on the bottom row and the lowest on the top row. A membership-only assertion
     * holds just as well against an implementation that placed the same ten rows in the opposite slots,
     * and that implementation would present an operator with a page in the wrong sequence.
     */
    @Nested
    @DisplayName("List - backward fill 10 down to 1 (ordered)")
    class ListBackwardFill {

        /** Creates the group. */
        ListBackwardFill() {
            // Intentionally empty: the group contributes assertions, not state.
        }

        /**
         * Paging backward off the second page republishes the first page in its original order.
         *
         * <p>Traceability: the backward paragraph at {@code app/cbl/COTRN00C.cbl} line 333 seeds its
         * index to ten at line 349 and decrements to one at lines 351 to 357.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("a backward page publishes the ten rows in ascending order, bottom slot highest")
        void aBackwardPagePublishesTheTenRowsInAscendingOrder() throws Exception {
            final List<Transaction> ordered = writeOrderedFixture();
            final JsonNode firstPage = bodyOf(postList(listBody("ENTER")));
            final Map<String, Object> forward = listBody("PFK08");
            forward.put("navigationContext", reEntry());
            forward.put("pageMetadata", pagingStateFrom(firstPage));
            forward.put("rowSnapshotToken", textOf(firstPage, "rowSnapshotToken"));
            final JsonNode secondPage = bodyOf(postList(forward));

            final Map<String, Object> backward = listBody("PFK07");
            backward.put("navigationContext", reEntry());
            backward.put("pageMetadata", pagingStateFrom(secondPage));
            backward.put("rowSnapshotToken", textOf(secondPage, "rowSnapshotToken"));
            final JsonNode backwardPage = bodyOf(postList(backward));

            assertThat(publishedIdentifiers(backwardPage))
                    .as("the backward walk reads rows one, two and three of the sequence last, so the "
                            + "page it sends carries them in ascending order with the highest key on "
                            + "the bottom slot - the exact order, not merely the same ten rows")
                    .containsExactlyElementsOf(identifiersOf(ordered, 1, 10));
            assertThat(publishedSlots(backwardPage))
                    .as("the fill starts at the bottom slot and works upward, so all ten slots are "
                            + "occupied and are published in slot order")
                    .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        }

        /**
         * The bottom slot of a backward page carries the highest key of that page and the top slot the
         * lowest, which is the direction statement itself.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("the bottom slot of a backward page holds the highest key and the top slot the lowest")
        void theBottomSlotHoldsTheHighestKey() throws Exception {
            final List<Transaction> ordered = writeOrderedFixture();
            final JsonNode firstPage = bodyOf(postList(listBody("ENTER")));
            final Map<String, Object> forward = listBody("PFK08");
            forward.put("navigationContext", reEntry());
            forward.put("pageMetadata", pagingStateFrom(firstPage));
            forward.put("rowSnapshotToken", textOf(firstPage, "rowSnapshotToken"));
            final JsonNode secondPage = bodyOf(postList(forward));

            final Map<String, Object> backward = listBody("PFK07");
            backward.put("navigationContext", reEntry());
            backward.put("pageMetadata", pagingStateFrom(secondPage));
            backward.put("rowSnapshotToken", textOf(secondPage, "rowSnapshotToken"));
            final List<String> published = publishedIdentifiers(bodyOf(postList(backward)));

            assertThat(published.get(0))
                    .as("the first published row is the top slot, which the backward walk fills last "
                            + "and which therefore carries the lowest key of the page")
                    .isEqualTo(ordered.get(0).getTranId());
            assertThat(published.get(published.size() - 1))
                    .as("the last published row is the bottom slot, which the backward walk fills "
                            + "first and which therefore carries the highest key of the page")
                    .isEqualTo(ordered.get(9).getTranId());
            assertThat(published)
                    .as("nothing on either side of the boundary reversed or re-sorted the page, so the "
                            + "published sequence is strictly ascending")
                    .isSorted();
        }

        /**
         * The backward turn's page indicator returns to the page it walked back to.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("a backward page returns the indicator to the preceding page")
        void aBackwardPageReturnsTheIndicator() throws Exception {
            writeOrderedFixture();
            final JsonNode firstPage = bodyOf(postList(listBody("ENTER")));
            final Map<String, Object> forward = listBody("PFK08");
            forward.put("navigationContext", reEntry());
            forward.put("pageMetadata", pagingStateFrom(firstPage));
            forward.put("rowSnapshotToken", textOf(firstPage, "rowSnapshotToken"));
            final JsonNode secondPage = bodyOf(postList(forward));

            final Map<String, Object> backward = listBody("PFK07");
            backward.put("navigationContext", reEntry());
            backward.put("pageMetadata", pagingStateFrom(secondPage));
            backward.put("rowSnapshotToken", textOf(secondPage, "rowSnapshotToken"));
            final JsonNode backwardPage = bodyOf(postList(backward));

            assertThat(textOf(backwardPage, "displayedPageNumber"))
                    .as("the counter is decremented rather than recomputed, so the walk back from the "
                            + "second page reports the first")
                    .isEqualTo("00000001");
            assertThat(backwardPage.get("pageMetadata").get("direction").asText())
                    .as("the direction the page was assembled in is published, because a client that "
                            + "echoes a cursor pair has to know which of the two it came from")
                    .isEqualTo(PageMetadata.PagingDirection.BACKWARD.name());
        }

        /**
         * A forward page reports the forward direction, so the two are genuinely distinguished.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("a forward page reports the forward direction")
        void aForwardPageReportsTheForwardDirection() throws Exception {
            writeOrderedFixture();

            final JsonNode paging = bodyOf(postList(listBody("ENTER"))).get("pageMetadata");

            assertThat(paging.get("direction").asText())
                    .isEqualTo(PageMetadata.PagingDirection.FORWARD.name());
        }
    }

    // ===============================================================================================
    // CT00 :: FIVE DISTINCT END-OF-BROWSE TEXTS, PLUS THE ONE THAT PUNCTUATES DIFFERENTLY
    // ===============================================================================================

    /**
     * The six texts this screen composes about the extent of a browse.
     *
     * <p>Five of them report a boundary and are reached from five different places: two from the paging
     * guards that refuse to move, and three from the browse itself when it cannot position or runs out of
     * records in one direction or the other. They are not two texts with variations - collapsing any pair
     * of them would tell an operator that a browse ended for a reason other than the one it ended for.
     * The sixth is the filter's class test, and it is the only one of the six with a space in front of
     * its dots.
     */
    @Nested
    @DisplayName("List - five boundary texts")
    class ListBoundaryTexts {

        /** Creates the group. */
        ListBoundaryTexts() {
            // Intentionally empty: the group contributes assertions, not state.
        }

        /**
         * Refusing to page backward off the first page reports the first of the five.
         *
         * <p>Traceability: {@code app/cbl/COTRN00C.cbl} line 248.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("paging backward from the first page reports it is already at the top")
        void pagingBackwardFromTheFirstPageReportsAlreadyAtTheTop() throws Exception {
            writeOrderedFixture();
            final JsonNode firstPage = bodyOf(postList(listBody("ENTER")));

            final Map<String, Object> backward = listBody("PFK07");
            backward.put("navigationContext", reEntry());
            backward.put("pageMetadata", pagingStateFrom(firstPage));
            backward.put("rowSnapshotToken", textOf(firstPage, "rowSnapshotToken"));
            final JsonNode refused = bodyOf(postList(backward));

            assertThat(textOf(refused, "message"))
                    .as("the guard tests the page counter, and the first page has nothing before it")
                    .isEqualTo(ALREADY_AT_TOP)
                    .isEqualTo("You are already at the top of the page...");
            assertThat(switchOf(refused, "preserveDisplayedPage"))
                    .as("the refusal is sent without erasing, so the client keeps the page it is "
                            + "displaying rather than blanking it")
                    .isTrue();
        }

        /**
         * Refusing to page forward past the end reports the second of the five.
         *
         * <p>Traceability: line 270.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("paging forward past the last page reports it is already at the bottom")
        void pagingForwardPastTheLastPageReportsAlreadyAtTheBottom() throws Exception {
            writeOrderedFixture();
            final JsonNode lastPage = walkToTheShortFinalPage();

            final Map<String, Object> forward = listBody("PFK08");
            forward.put("navigationContext", reEntry());
            forward.put("pageMetadata", pagingStateFrom(lastPage));
            forward.put("rowSnapshotToken", textOf(lastPage, "rowSnapshotToken"));
            final JsonNode refused = bodyOf(postList(forward));

            assertThat(textOf(refused, "message"))
                    .as("the previous turn discovered no further page, so the forward guard refuses "
                            + "rather than re-reading the cluster")
                    .isEqualTo(ALREADY_AT_BOTTOM)
                    .isEqualTo("You are already at the bottom of the page...");
        }

        /**
         * A browse that cannot be positioned at all reports the third of the five.
         *
         * <p>Traceability: line 608. The delivered reference seed applies no transaction row, so an
         * unfiltered browse of the delivered state has nowhere to position, which is the same outcome the
         * legacy reports when its positioning read finds nothing.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("a browse that cannot be positioned reports it is at the top of the page")
        void aBrowseThatCannotBePositionedReportsAtTheTop() throws Exception {
            final JsonNode body = bodyOf(postList(listBody("ENTER")));

            assertThat(textOf(body, "message"))
                    .as("positioning found nothing, which is its own report and not either of the two "
                            + "guard refusals")
                    .isEqualTo(AT_TOP)
                    .isEqualTo("You are at the top of the page...");
            assertThat(publishedIdentifiers(body))
                    .as("a browse that never positioned placed no row")
                    .isEmpty();
            assertThat(switchOf(body, "error"))
                    .as("the source leaves the error switch down on this arm, so it is an outcome the "
                            + "screen reports rather than an edit failure")
                    .isFalse();
        }

        /**
         * A forward read that runs out reports the fourth of the five.
         *
         * <p>Traceability: line 642.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("a forward read that exhausts the sequence reports reaching the bottom")
        void aForwardReadThatExhaustsTheSequenceReportsReachingTheBottom() throws Exception {
            final List<Transaction> ordered = writeOrderedFixture();

            final JsonNode shortPage = walkToTheShortFinalPage();

            assertThat(textOf(shortPage, "message"))
                    .as("the read that follows the last record ends the sequence, and that report is "
                            + "distinct from the guard's refusal to move")
                    .isEqualTo(REACHED_BOTTOM)
                    .isEqualTo("You have reached the bottom of the page...");
            assertThat(publishedIdentifiers(shortPage))
                    .as("a short page publishes the rows it placed and is never padded out to ten")
                    .containsExactly(ordered.get(ORDERED_FIXTURE_ROWS - 1).getTranId());
        }

        /**
         * A backward read that runs out reports the fifth of the five.
         *
         * <p>Traceability: line 676.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("a backward read that exhausts the sequence reports reaching the top")
        void aBackwardReadThatExhaustsTheSequenceReportsReachingTheTop() throws Exception {
            writeOrderedFixture();
            final JsonNode firstPage = bodyOf(postList(listBody("ENTER")));
            final Map<String, Object> forward = listBody("PFK08");
            forward.put("navigationContext", reEntry());
            forward.put("pageMetadata", pagingStateFrom(firstPage));
            forward.put("rowSnapshotToken", textOf(firstPage, "rowSnapshotToken"));
            final JsonNode secondPage = bodyOf(postList(forward));

            final Map<String, Object> backward = listBody("PFK07");
            backward.put("navigationContext", reEntry());
            backward.put("pageMetadata", pagingStateFrom(secondPage));
            backward.put("rowSnapshotToken", textOf(secondPage, "rowSnapshotToken"));
            final JsonNode backwardPage = bodyOf(postList(backward));

            assertThat(textOf(backwardPage, "message"))
                    .as("the read that follows the first record of the cluster ends the sequence in the "
                            + "backward direction, and that report is its own text")
                    .isEqualTo(REACHED_TOP)
                    .isEqualTo("You have reached the top of the page...");
        }

        /**
         * The five boundary texts are five different values, none a prefix substitute for another.
         */
        @Test
        @DisplayName("the five boundary texts are five distinct values, each ending in three dots")
        void theFiveBoundaryTextsAreDistinct() {
            assertThat(List.of(ALREADY_AT_TOP, ALREADY_AT_BOTTOM, AT_TOP, REACHED_BOTTOM, REACHED_TOP))
                    .as("five places report a boundary and each reports it in its own words")
                    .doesNotHaveDuplicates()
                    .hasSize(5)
                    .allSatisfy(text -> assertThat(text)
                            .endsWith("...")
                            .doesNotEndWith(" ...")
                            .doesNotContain("  "));
        }

        /**
         * The filter's class test is the one text that keeps a space in front of its dots.
         *
         * <p>Traceability: line 214.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("the filter class test is the only text with a space before its dots")
        void theFilterClassTestKeepsItsSpaceBeforeTheDots() throws Exception {
            writeOrderedFixture();

            final Map<String, Object> turn = listBody("ENTER");
            turn.put("navigationContext", reEntry());
            turn.put("transactionIdFilter", "NOTNUMERIC000000");
            final JsonNode body = bodyOf(postList(turn));

            assertThat(textOf(body, "message"))
                    .as("this text is punctuated differently from the five boundary reports and is "
                            + "reproduced exactly as the member writes it")
                    .isEqualTo(FILTER_NOT_NUMERIC)
                    .isEqualTo("Tran ID must be Numeric ...")
                    .endsWith(" ...");
            assertThat(FILTER_NOT_NUMERIC)
                    .as("the five boundary texts end their sentence immediately before the dots, so "
                            + "this one is never asserted interchangeably with any of them")
                    .isNotIn(ALREADY_AT_TOP, ALREADY_AT_BOTTOM, AT_TOP, REACHED_BOTTOM, REACHED_TOP);
            assertThat(switchOf(body, "error"))
                    .as("a class test that fails is an edit failure and raises the switch, unlike the "
                            + "boundary reports")
                    .isTrue();
        }

        /**
         * The rejected filter is reported per field as an invalid value rather than a missing one.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("the rejected filter is reported per field on the filter item as invalid")
        void theRejectedFilterIsReportedPerField() throws Exception {
            writeOrderedFixture();

            final Map<String, Object> turn = listBody("ENTER");
            turn.put("navigationContext", reEntry());
            turn.put("transactionIdFilter", "NOTNUMERIC000000");
            final JsonNode body = bodyOf(postList(turn));

            final JsonNode fieldErrors = body.get("fieldErrors");
            assertThat(fieldErrors).isNotNull();
            assertThat(fieldErrors.size())
                    .as("the cascade stops at its first failure, so exactly one field is named")
                    .isEqualTo(1);
            assertThat(textOf(fieldErrors.get(0), "fieldName"))
                    .isEqualTo(TransactionListService.TRANSACTION_ID_FILTER_PROPERTY);
            assertThat(textOf(fieldErrors.get(0), "state"))
                    .as("a value was supplied and was of the wrong class, which is the invalid state "
                            + "and not the missing one")
                    .isEqualTo(ErrorResponse.FieldState.INVALID.name());
        }

        /**
         * An identifier that is not all digits is still a legitimate key on the view screen, which has
         * no class test at all - so the numeric wording of the list screen's filter text is about the
         * filter and never about the key space.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("the sixteen-character key space is alphanumeric, despite the filter text's wording")
        void theKeySpaceIsAlphanumericDespiteTheFilterWording() throws Exception {
            final JsonNode body =
                    bodyOf(postView("ABCDEF9876543210", null, "ENTER", reEntry()));

            assertThat(textOf(body, "errorMessage"))
                    .as("the view screen tests the key for emptiness and for width and never for class, "
                            + "so a key carrying letters is looked up and reported absent rather than "
                            + "refused as non-numeric")
                    .isEqualTo(VIEW_TRAN_ID_NOT_FOUND);
            assertThat(textOf(body, "errorMessage"))
                    .as("no numeric-class complaint reaches this screen")
                    .isNotEqualTo(FILTER_NOT_NUMERIC);
            assertThat(textOf(body, "searchTransactionId"))
                    .as("the key is echoed exactly as submitted, letters included and at its full "
                            + "sixteen characters")
                    .isEqualTo("ABCDEF9876543210")
                    .hasSize(TRANSACTION_ID_WIDTH);
        }

        /**
         * Walks the browse forward to the short final page of the ordered fixture.
         *
         * <p>Twenty-one rows make two full pages and a third page of one row, and it is that third turn -
         * the one whose read runs off the end of the sequence - that reports reaching the bottom and
         * that leaves no further page for the guard to find.
         *
         * @return the body of the short final page
         * @throws Exception if the boundary cannot be reached
         */
        private JsonNode walkToTheShortFinalPage() throws Exception {
            final JsonNode firstPage = bodyOf(postList(listBody("ENTER")));
            final Map<String, Object> toSecond = listBody("PFK08");
            toSecond.put("navigationContext", reEntry());
            toSecond.put("pageMetadata", pagingStateFrom(firstPage));
            toSecond.put("rowSnapshotToken", textOf(firstPage, "rowSnapshotToken"));
            final JsonNode secondPage = bodyOf(postList(toSecond));

            final Map<String, Object> toThird = listBody("PFK08");
            toThird.put("navigationContext", reEntry());
            toThird.put("pageMetadata", pagingStateFrom(secondPage));
            toThird.put("rowSnapshotToken", textOf(secondPage, "rowSnapshotToken"));
            return bodyOf(postList(toThird));
        }
    }

    // ===============================================================================================
    // CT00 :: TEN POSITIONAL SELECTORS, FIRST NONBLANK WINS
    // ===============================================================================================

    /**
     * The row selectors, which are ten positions rather than a set of choices.
     *
     * <p>The legacy walks slots one to ten and stops at the first slot that is not blank, taking the
     * identifier the same slot displayed. Two properties follow and both are asserted: the scan direction
     * decides which of two marked rows wins, and the sequence has to stay positional end to end - a blank
     * slot is a slot, so nothing may compact, filter, sort or key the sequence by anything but position.
     */
    @Nested
    @DisplayName("List - first-nonblank selector precedence")
    class ListSelectorPrecedence {

        /** Creates the group. */
        ListSelectorPrecedence() {
            // Intentionally empty: the group contributes assertions, not state.
        }

        /**
         * One marked row in the seventh slot nominates the identifier the seventh row displayed.
         *
         * <p>Traceability: the selector scan at {@code app/cbl/COTRN00C.cbl} lines 148 to 182 and the
         * dispatch at lines 185 to 195.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("a mark in the seventh slot nominates the seventh row and routes to the view screen")
        void aMarkInTheSeventhSlotNominatesTheSeventhRow() throws Exception {
            final List<Transaction> ordered = writeOrderedFixture();
            final JsonNode page = bodyOf(postList(listBody("ENTER")));

            final Map<String, Object> selection = listBody("ENTER");
            selection.put("navigationContext", reEntry());
            selection.put("pageMetadata", pagingStateFrom(page));
            selection.put("rowSnapshotToken", textOf(page, "rowSnapshotToken"));
            selection.put("rowSelectors", selectorsWith(7, "S"));
            final JsonNode body = bodyOf(postList(selection));

            assertThat(textOf(body, "selectedTransactionId"))
                    .as("the nominated identifier is read from the slot the mark sits on, so a mark in "
                            + "the seventh slot names the seventh row and not the first")
                    .isEqualTo(ordered.get(6).getTranId());
            assertThat(textOf(body, "nextRoute"))
                    .as("the legacy transferred control to the detail program; the route travels as a "
                            + "value and the client drives the next call")
                    .isEqualTo(NavigationService.Route.TRANSACTION_VIEW.getRouteValue());
        }

        /**
         * The lower-case marker reaches the same arm as the upper-case one.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("the lower-case marker nominates a row exactly as the upper-case one does")
        void theLowerCaseMarkerNominatesARow() throws Exception {
            final List<Transaction> ordered = writeOrderedFixture();
            final JsonNode page = bodyOf(postList(listBody("ENTER")));

            final Map<String, Object> selection = listBody("ENTER");
            selection.put("navigationContext", reEntry());
            selection.put("pageMetadata", pagingStateFrom(page));
            selection.put("rowSnapshotToken", textOf(page, "rowSnapshotToken"));
            selection.put("rowSelectors", selectorsWith(2, "s"));
            final JsonNode body = bodyOf(postList(selection));

            assertThat(textOf(body, "selectedTransactionId"))
                    .isEqualTo(ordered.get(1).getTranId());
            assertThat(textOf(body, "nextRoute"))
                    .isEqualTo(NavigationService.Route.TRANSACTION_VIEW.getRouteValue());
        }

        /**
         * With two rows marked, the earlier slot wins because the scan runs from the first slot down.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("with the third and ninth slots both marked, the third wins")
        void withTwoSlotsMarkedTheEarlierOneWins() throws Exception {
            final List<Transaction> ordered = writeOrderedFixture();
            final JsonNode page = bodyOf(postList(listBody("ENTER")));

            final Map<String, Object> selection = listBody("ENTER");
            selection.put("navigationContext", reEntry());
            selection.put("pageMetadata", pagingStateFrom(page));
            selection.put("rowSnapshotToken", textOf(page, "rowSnapshotToken"));
            selection.put("rowSelectors", selectorsWithBoth(3, 9, "S"));
            final JsonNode body = bodyOf(postList(selection));

            assertThat(textOf(body, "selectedTransactionId"))
                    .as("the scan stops at the first nonblank slot, so the third row is nominated and "
                            + "the ninth is never consulted")
                    .isEqualTo(ordered.get(2).getTranId())
                    .isNotEqualTo(ordered.get(8).getTranId());
        }

        /**
         * A marker the screen does not offer is refused in the singular, and the refusal names the slot.
         *
         * <p>Traceability: line 199. The administrative user-list screen carries the same shape of text in
         * the plural because it offers two actions; the two are never shared.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("an unoffered marker is refused with the singular text and names its own slot")
        void anUnofferedMarkerIsRefusedInTheSingular() throws Exception {
            writeOrderedFixture();
            final JsonNode page = bodyOf(postList(listBody("ENTER")));

            final Map<String, Object> selection = listBody("ENTER");
            selection.put("navigationContext", reEntry());
            selection.put("pageMetadata", pagingStateFrom(page));
            selection.put("rowSnapshotToken", textOf(page, "rowSnapshotToken"));
            selection.put("rowSelectors", selectorsWith(4, "X"));
            final JsonNode body = bodyOf(postList(selection));

            assertThat(textOf(body, "message"))
                    .as("the noun is singular because this screen offers exactly one row action")
                    .isEqualTo(INVALID_SELECTION)
                    .isEqualTo("Invalid selection. Valid value is S")
                    .doesNotContain("values")
                    .doesNotContain(" and ");
            final JsonNode fieldErrors = body.get("fieldErrors");
            assertThat(fieldErrors.size()).isEqualTo(1);
            assertThat(textOf(fieldErrors.get(0), "screenFieldId"))
                    .as("the refusal names the marked slot's own item, so a client can put the cursor "
                            + "back on the row the operator marked")
                    .isEqualTo("SEL0004");
            assertThat(textOf(fieldErrors.get(0), "fieldName"))
                    .isEqualTo(TransactionListService.ROW_SELECTOR_PROPERTY);
            assertThat(textOf(fieldErrors.get(0), "state"))
                    .isEqualTo(ErrorResponse.FieldState.INVALID.name());
        }

        /**
         * With two unoffered markers, the refusal names the earlier slot.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("with two unoffered markers the refusal names the earlier slot")
        void withTwoUnofferedMarkersTheEarlierSlotIsNamed() throws Exception {
            writeOrderedFixture();
            final JsonNode page = bodyOf(postList(listBody("ENTER")));

            final Map<String, Object> selection = listBody("ENTER");
            selection.put("navigationContext", reEntry());
            selection.put("pageMetadata", pagingStateFrom(page));
            selection.put("rowSnapshotToken", textOf(page, "rowSnapshotToken"));
            selection.put("rowSelectors", selectorsWithBoth(3, 9, "X"));
            final JsonNode body = bodyOf(postList(selection));

            assertThat(textOf(body.get("fieldErrors").get(0), "screenFieldId"))
                    .as("first nonblank wins on the refusal arm exactly as it wins on the dispatch arm")
                    .isEqualTo("SEL0003");
        }

        /**
         * The echoed selector sequence stays positional: ten slots, blanks preserved, nothing compacted.
         *
         * <p>An unoffered marker is used because that arm re-sends the page rather than transferring, so
         * the whole ten-slot echo is observable in one response.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("the echoed selectors stay positional with every blank slot preserved")
        void theEchoedSelectorsStayPositional() throws Exception {
            writeOrderedFixture();
            final JsonNode page = bodyOf(postList(listBody("ENTER")));

            final Map<String, Object> selection = listBody("ENTER");
            selection.put("navigationContext", reEntry());
            selection.put("pageMetadata", pagingStateFrom(page));
            selection.put("rowSnapshotToken", textOf(page, "rowSnapshotToken"));
            selection.put("rowSelectors", selectorsWith(4, "X"));
            final JsonNode body = bodyOf(postList(selection));

            final List<String> echoed = new ArrayList<>();
            for (final JsonNode row : body.get("rows")) {
                echoed.add(textOf(row, "selection"));
            }
            assertThat(echoed)
                    .as("the marked slot keeps its marker and every other slot keeps its blank, because "
                            + "a compacted, filtered or re-keyed sequence would re-point the next turn's "
                            + "selection at a different row than the operator marked")
                    .containsExactly(" ", " ", " ", "X", " ", " ", " ", " ", " ", " ")
                    .hasSize(TransactionListRequest.ROW_SELECTOR_COUNT);
        }

        /**
         * A page publishes its two boundary keys and its direction, and no identifier list at all.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("a page publishes the two boundary keys and the direction, and no identifier list "
                + "for the next turn to be told to trust")
        void aPagePublishesItsBoundaryKeysAndNoIdentifierList() throws Exception {
            final List<Transaction> ordered = writeOrderedFixture();

            final JsonNode body = bodyOf(postList(listBody("ENTER")));
            final JsonNode published = body.get("pageMetadata");

            assertThat(body.has("continuation"))
                    .as("a submission body is not a trusted echo channel, so no continuation object "
                            + "carrying displayed identifiers in the clear is published for one to be "
                            + "echoed back")
                    .isFalse();
            assertThat(body.toString())
                    .as("and no member anywhere in the response is an identifier list")
                    .doesNotContain("displayedTransactionIds");
            assertThat(textOf(body, "rowSnapshotToken"))
                    .as("what is published instead is one sealed snapshot of the slot map, which is the "
                            + "channel the legacy screen map was")
                    .isNotBlank();
            assertThat(SensitiveFieldCodec.hasEnvelopeShape(textOf(body, "rowSnapshotToken")))
                    .as("and it is an authenticated envelope rather than text a client could read")
                    .isTrue();
            assertThat(textOf(body, "rowSnapshotToken"))
                    .as("no displayed identifier is legible inside it")
                    .doesNotContain(ordered.get(0).getTranId())
                    .doesNotContain(ordered.get(9).getTranId());
            assertThat(textOf(published, "previousCursorKey"))
                    .as("the forward key names the first presented slot, which is where an upward fill "
                            + "began and therefore what reproduces the page")
                    .isEqualTo(ordered.get(0).getTranId());
            assertThat(textOf(published, "nextCursorKey"))
                    .as("the backward key names the tenth presented slot, which is where a downward "
                            + "fill would have begun")
                    .isEqualTo(ordered.get(9).getTranId());
            assertThat(textOf(published, "direction"))
                    .as("the direction says which of the two keys reproduces this page")
                    .isEqualTo("FORWARD");
        }

        /**
         * A cursor altered by one byte no longer changes which row a mark resolves to, because the
         * marked slot is resolved against the sealed page rather than against a second read.
         *
         * <p>This is the inverse of what the earlier read-based arrangement asserted here, and the
         * inversion is the point: under that arrangement the boundary key decided which page was
         * re-read, so editing it moved the marked slot onto a different row. The sealed snapshot carries
         * the page itself, so the cursor decides nothing about identity and only decides where the next
         * page begins.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("a boundary key altered by one byte does not change which row a mark resolves to, "
                + "because identity comes from the sealed page and not from the cursor")
        void aTamperedBoundaryKeyDoesNotChangeWhichRowAMarkResolves() throws Exception {
            final List<Transaction> ordered = writeOrderedFixture();
            final JsonNode page = bodyOf(postList(listBody("ENTER")));

            final Map<String, Object> honest = listBody("ENTER");
            honest.put("navigationContext", reEntry());
            honest.put("pageMetadata", pagingStateFrom(page));
            honest.put("rowSnapshotToken", textOf(page, "rowSnapshotToken"));
            honest.put("rowSelectors", selectorsWith(1, "S"));

            final Map<String, Object> tampered = listBody("ENTER");
            tampered.put("navigationContext", reEntry());
            tampered.put("pageMetadata", pagingStateWithATamperedForwardKey(page));
            tampered.put("rowSnapshotToken", textOf(page, "rowSnapshotToken"));
            tampered.put("rowSelectors", selectorsWith(1, "S"));

            final Map<String, Object> withATamperedSnapshot = listBody("ENTER");
            withATamperedSnapshot.put("navigationContext", reEntry());
            withATamperedSnapshot.put("pageMetadata", pagingStateFrom(page));
            withATamperedSnapshot.put("rowSnapshotToken",
                    withOneCharacterChanged(textOf(page, "rowSnapshotToken")));
            withATamperedSnapshot.put("rowSelectors", selectorsWith(1, "S"));

            final String honestly = textOf(bodyOf(postList(honest)), "selectedTransactionId");
            final String afterCursorTamper =
                    textOf(bodyOf(postList(tampered)), "selectedTransactionId");
            final JsonNode afterSnapshotTamper = bodyOf(postList(withATamperedSnapshot));

            assertThat(honestly)
                    .as("an honest echo resolves the row the marked slot displayed")
                    .isEqualTo(ordered.get(0).getTranId());
            assertThat(afterCursorTamper)
                    .as("editing the boundary key changes nothing about identity: the sealed page still "
                            + "says which row stood in the marked slot")
                    .isEqualTo(honestly);
            assertThat(textOf(afterSnapshotTamper, "selectedTransactionId"))
                    .as("editing the sealed page instead makes it unopenable, and an unopenable page "
                            + "names no row - the outcome a blank echoed identifier reaches")
                    .isEmpty();
            assertThat(textOf(afterSnapshotTamper, "nextRoute"))
                    .as("so the turn re-arms on its own screen rather than transferring")
                    .isNotEqualTo(NavigationService.Route.TRANSACTION_VIEW.getRouteValue());
        }

        /**
         * One character of a sealed snapshot changed, so it can no longer be opened.
         *
         * @param  snapshot the snapshot a response published
         * @return the same snapshot with its final character altered
         */
        private String withOneCharacterChanged(final String snapshot) {
            final char[] characters = snapshot.toCharArray();
            final int last = characters.length - 1;
            characters[last] = characters[last] == 'A' ? 'B' : 'A';
            return new String(characters);
        }

        /**
         * A short forward page names its only row at the forward boundary and leaves the backward one
         * unset, which is the legacy assignment reproduced and the reason each direction reads its own
         * key.
         *
         * <p>{@code app/cbl/COTRN00C.cbl} writes the first-row key only in the clause for slot one at
         * lines 392-393 and the last-row key only in the clause for slot ten at lines 438-439. A forward
         * fill always reaches slot one and reaches slot ten only on a full page, so the backward key of a
         * short forward page is unset; a reverse fill always reaches slot ten, so the backward key of a
         * short <em>backward</em> page is always set. Re-reading a forward page from its forward key and
         * a backward page from its backward key therefore always reads the key that is present, and a
         * page carrying neither selects nothing rather than guessing.</p>
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("a short forward page names its only row at the forward boundary, leaves the "
                + "backward one unset, and a mark on an unfilled slot selects nothing")
        void aShortPageNamesItsOnlyRowAtBothBoundaries() throws Exception {
            final List<Transaction> single = List.of(TestDataFactory.transaction()
                    .id(reservedIdentifier(1))
                    .cardNumber(SEEDED_CARD_NUMBER)
                    .build());
            transactions.saveAll(single);
            transactions.flush();

            final JsonNode body = bodyOf(postList(listBody("ENTER")));

            assertThat(publishedIdentifiers(body))
                    .as("one row was available, so one row is published and the page is not padded")
                    .containsExactly(single.get(0).getTranId());
            assertThat(textOf(body.get("pageMetadata"), "previousCursorKey"))
                    .as("the forward fill reached slot one, so the forward key names that row and the "
                            + "page is reproducible from it")
                    .isEqualTo(single.get(0).getTranId());
            assertThat(textOf(body.get("pageMetadata"), "nextCursorKey"))
                    .as("the fill never reached slot ten, and only the tenth slot's clause assigns the "
                            + "backward key, so it stays unset exactly as the legacy leaves it")
                    .isNull();

            final Map<String, Object> markOnAnUnfilledSlot = listBody("ENTER");
            markOnAnUnfilledSlot.put("navigationContext", reEntry());
            markOnAnUnfilledSlot.put("pageMetadata", pagingStateFrom(body));
            markOnAnUnfilledSlot.put("rowSnapshotToken", textOf(body, "rowSnapshotToken"));
            markOnAnUnfilledSlot.put("rowSelectors", selectorsWith(4, "S"));

            final JsonNode marked = bodyOf(postList(markOnAnUnfilledSlot));

            assertThat(textOf(marked, "selectedTransactionId"))
                    .as("the fill never reached the fourth slot, so it displayed no row and marking it "
                            + "names none")
                    .isEmpty();
            assertThat(textOf(marked, "nextRoute"))
                    .as("nothing was nominated, so the turn re-arms on its own screen")
                    .isNotEqualTo(NavigationService.Route.TRANSACTION_VIEW.getRouteValue());
        }
    }

    // ===============================================================================================
    // CT00 :: THE ROW HAS FIVE SCREEN ITEMS, AT WIDTHS THAT BELONG TO THIS SCREEN ALONE
    // ===============================================================================================

    /**
     * The shape of one published row, and the widths that shape is measured in.
     *
     * <p>Five screen items and nothing else. The record behind the row carries a card number, a type
     * code, a category code, a source, a merchant block and two timestamps, and none of them belongs on
     * this screen - publishing any of them would widen an external contract. The two widths this screen
     * declares for a value it shares with another screen are both asserted here against the value the
     * other screen publishes for the same stored row, which is what proves they are two widths rather
     * than one.
     */
    @Nested
    @DisplayName("List - five-component row and unique widths")
    class ListRowShape {

        /** Creates the group. */
        ListRowShape() {
            // Intentionally empty: the group contributes assertions, not state.
        }

        /**
         * A row publishes the five screen items plus its slot, and nothing else at all.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("a row publishes five screen items and its slot, and no record item beyond them")
        void aRowPublishesFiveScreenItemsAndItsSlot() throws Exception {
            writeOrderedFixture();
            final JsonNode page = bodyOf(postList(listBody("ENTER")));
            final Map<String, Object> resubmitted = listBody("ENTER");
            resubmitted.put("navigationContext", reEntry());
            resubmitted.put("pageMetadata", pagingStateFrom(page));
            resubmitted.put("rowSnapshotToken", textOf(page, "rowSnapshotToken"));
            resubmitted.put("rowSelectors", selectorsWith(4, "X"));

            final JsonNode row = bodyOf(postList(resubmitted)).get("rows").get(0);

            assertThat(row.has("selection")).as("the marker column").isTrue();
            assertThat(row.has("transactionId")).as("the identifier column").isTrue();
            assertThat(row.has("displayedDate")).as("the date column").isTrue();
            assertThat(row.has("description")).as("the description column").isTrue();
            assertThat(row.has("amount")).as("the amount column").isTrue();
            assertThat(row.has("screenRow"))
                    .as("the slot index, which is protocol rather than a screen item and which the "
                            + "selector sequence is aligned by")
                    .isTrue();
            assertThat(List.of("cardNumber", "typeCode", "categoryCode", "source", "merchantId",
                            "merchantName", "merchantCity", "merchantZip", "originationTimestamp",
                            "processingTimestamp", "originationDate", "processingDate"))
                    .as("the record carries all of these and this screen displays none of them, so a "
                            + "row that published one would widen the contract")
                    .allSatisfy(absent -> assertThat(row.has(absent)).isFalse());
            assertThat(List.of("attribute", "colour", "color", "highlight", "marker", "cursor",
                            "screenWorkArea", "workArea", "filler", "mask"))
                    .as("no map attribute, colour, marker byte, cursor coordinate, filler or edited "
                            + "mask crosses this boundary, and the transaction screens are not members "
                            + "of the family that carries a screen work area at all")
                    .allSatisfy(absent -> assertThat(row.has(absent)).isFalse());
        }

        /**
         * The description column is twenty-six characters here and sixty on the view screen, proved from
         * one stored value on one stored row.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("one stored description is bounded to twenty-six here and to sixty on the view screen")
        void oneStoredDescriptionIsBoundedToTwoDifferentWidths() throws Exception {
            final Transaction wide = writeWideValuesRow();

            final JsonNode listed = bodyOf(postList(listBody("ENTER"))).get("rows").get(0);
            final JsonNode viewed = bodyOf(postView(wide.getTranId(), null, "ENTER", reEntry()));

            assertThat(textOf(listed, "description"))
                    .as("the list column keeps the column's worth of leading characters, exactly as the "
                            + "legacy assignment into a narrower item keeps them")
                    .isEqualTo(OVERLONG_DESCRIPTION.substring(0, LIST_DESCRIPTION_WIDTH))
                    .hasSize(LIST_DESCRIPTION_WIDTH)
                    .hasSize(TransactionListResponse.DESCRIPTION_LENGTH);
            assertThat(textOf(viewed, "description"))
                    .as("the view item is wider, so the same stored value reaches the operator at sixty "
                            + "characters on that screen - two widths, never one shared constant")
                    .isEqualTo(OVERLONG_DESCRIPTION.substring(0, VIEW_DESCRIPTION_WIDTH))
                    .hasSize(VIEW_DESCRIPTION_WIDTH);
            assertThat(LIST_DESCRIPTION_WIDTH)
                    .as("twenty-six and sixty are different figures and the record's own hundred is a "
                            + "third; none of the three is derived from another")
                    .isNotEqualTo(VIEW_DESCRIPTION_WIDTH);
        }

        /**
         * The date column is eight characters here and ten on the view screen.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("the date column is eight characters here and ten on the view screen")
        void theDateColumnIsEightCharactersHere() throws Exception {
            final Transaction wide = writeWideValuesRow();

            final JsonNode listed = bodyOf(postList(listBody("ENTER"))).get("rows").get(0);
            final JsonNode viewed = bodyOf(postView(wide.getTranId(), null, "ENTER", reEntry()));

            assertThat(textOf(listed, "displayedDate"))
                    .as("the column is the month, the day and the low-order two digits of the year, "
                            + "rendered from the stored origination timestamp at this screen's width")
                    .isEqualTo("06/10/22")
                    .hasSize(LIST_DATE_WIDTH)
                    .hasSize(TransactionListResponse.DISPLAYED_DATE_LENGTH);
            assertThat(textOf(viewed, "originationDate"))
                    .as("the view screen shows ten characters of the same stored timestamp, which is a "
                            + "different width and a different rendering")
                    .isEqualTo(TestDataFactory.SEEDED_ORIGINAL_TIMESTAMP
                            .substring(0, VIEW_DATE_WIDTH))
                    .hasSize(VIEW_DATE_WIDTH);
            assertThat(LIST_DATE_WIDTH).isNotEqualTo(VIEW_DATE_WIDTH);
        }

        /**
         * A short page publishes only the rows it placed; the response refuses a page longer than the
         * screen and pads nothing.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("a page is never padded out to ten rows")
        void aPageIsNeverPaddedOutToTenRows() throws Exception {
            writeWideValuesRow();

            final JsonNode body = bodyOf(postList(listBody("ENTER")));

            assertThat(publishedIdentifiers(body))
                    .as("one row was available, so one row is published: a blank filler row would be a "
                            + "row on the wire that the browse never read")
                    .hasSize(1);
            assertThat(publishedSlots(body)).containsExactly(1);
        }

        /**
         * The summary message item is seventy-eight characters on this screen.
         */
        @Test
        @DisplayName("the summary message item is seventy-eight characters wide")
        void theSummaryMessageItemIsSeventyEightCharactersWide() {
            assertThat(TransactionListResponse.MESSAGE_LENGTH)
                    .as("the map declares seventy-eight for the message item on all three of these "
                            + "screens, which is narrower than the eighty two card screens use")
                    .isEqualTo(SCREEN_MESSAGE_WIDTH)
                    .isEqualTo(78);
        }

        /**
         * The amount is an exact decimal of scale two, rendered plainly by the shared configuration.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("the amount is an exact decimal of scale two, rendered without an exponent")
        void theAmountIsAnExactDecimalRenderedPlainly() throws Exception {
            writeWideValuesRow();

            final MvcResult result = postList(listBody("ENTER"));
            final String rawBody = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            final JsonNode row = bodyOf(result).get("rows").get(0);

            assertThat(row.get("amount").decimalValue())
                    .as("the value crosses unaltered, and no rounding mode is applied on the way "
                            + "through")
                    .isEqualByComparingTo(new BigDecimal(WIDEST_AMOUNT_TEXT));
            final String amountLiteral = numberLiteralOf(rawBody, "amount");
            assertThat(amountLiteral)
                    .as("the wire form is what fixes the scale, so it is asserted on the bytes rather "
                            + "than on a value a reader has already re-typed")
                    .isEqualTo(WIDEST_AMOUNT_TEXT);
            assertThat(amountLiteral.substring(amountLiteral.indexOf('.') + 1))
                    .as("two decimal places reach the wire, which is the scale the record's picture "
                            + "clause declares")
                    .hasSize(TransactionListResponse.AMOUNT_SCALE)
                    .hasSize(2);
            assertThat(applicationTextOf(rawBody))
                    .as("the shared configuration writes a decimal plainly, so nine integer digits "
                            + "reach the wire as digits and never as an exponent form")
                    .doesNotContain("E+")
                    .doesNotContain("e+");
            assertThat(rawBody)
                    .as("the screen's edited presentation form is a display mask and never a value or "
                            + "a declared bound on the wire")
                    .doesNotContain("-99999999.99");
        }

        /**
         * A stored timestamp of twenty-six blanks survives the whole turn byte for byte.
         *
         * <p>Every delivered daily record carries exactly that value in its processing timestamp, so it is
         * a real stored value rather than a contrived one. Three things are required of it: the view screen
         * publishes it at the screen's own ten characters without trimming those characters away, the
         * stored value is still twenty-six blanks after the turn, and no temporal type appeared anywhere
         * on the way through - which would have rejected a blank outright.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("a stored timestamp of twenty-six blanks round-trips byte for byte")
        void aStoredTimestampOfTwentySixBlanksRoundTrips() throws Exception {
            final Transaction wide = writeWideValuesRow();
            assertThat(wide.getTranProcTs())
                    .as("the fixture stores the same blank processing timestamp the delivered daily "
                            + "records carry")
                    .isEqualTo(TestDataFactory.BLANK_PROCESSING_TIMESTAMP)
                    .hasSize(STORED_TIMESTAMP_WIDTH);

            final JsonNode viewed = bodyOf(postView(wide.getTranId(), null, "ENTER", reEntry()));

            assertThat(textOf(viewed, "processingDate"))
                    .as("the screen item is ten characters and the value is blank, so ten blanks reach "
                            + "the operator - not an absent member, not an empty string and not a "
                            + "substituted date")
                    .isEqualTo(" ".repeat(VIEW_DATE_WIDTH))
                    .hasSize(VIEW_DATE_WIDTH);
            assertThat(transactions.findById(wide.getTranId()).orElseThrow().getTranProcTs())
                    .as("the stored value is unchanged after the turn, at its full twenty-six "
                            + "characters, so nothing on the read path trimmed, normalised or "
                            + "reinterpreted it")
                    .isEqualTo(TestDataFactory.BLANK_PROCESSING_TIMESTAMP)
                    .hasSize(STORED_TIMESTAMP_WIDTH);
        }

        /**
         * The two timestamp shapes the estate uses stay distinct: the stored online value carries a space
         * and a decimal point, and never the batch form's separators.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("the online timestamp shape is never converted into the batch timestamp shape")
        void theOnlineTimestampShapeIsNeverConvertedToTheBatchShape() throws Exception {
            final Transaction wide = writeWideValuesRow();

            final MvcResult result = postView(wide.getTranId(), null, "ENTER", reEntry());
            final String rawBody = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

            assertThat(TestDataFactory.SEEDED_ORIGINAL_TIMESTAMP)
                    .as("the online form separates the date from the time with a space and the seconds "
                            + "from the fraction with a point")
                    .isEqualTo("2022-06-10 19:27:53.000000")
                    .hasSize(STORED_TIMESTAMP_WIDTH);
            assertThat(rawBody)
                    .as("the batch form joins its parts with hyphens and points, and no value on this "
                            + "screen was reshaped into it")
                    .doesNotContain("2022-06-10-19.27.53");
        }
    }

    // ===============================================================================================
    // CT01 :: TWO DISTINCT SIXTEEN-CHARACTER IDENTIFIER ITEMS
    // ===============================================================================================

    /**
     * The two identifier items of the view screen, which are two items and not one.
     *
     * <p>One is the key the operator typed or the calling screen handed over, and it belongs to the input
     * side of the map. The other is the identifier of the record that was actually retrieved, and it
     * belongs to the display side. On a turn that retrieved a record both hold the same characters, which
     * is exactly why the not-found turn matters: there the key is still on the screen and every display
     * item is blank, and a contract that had merged the two could not express that state at all.
     */
    @Nested
    @DisplayName("View - two distinct id fields")
    class ViewIdentifierItems {

        /** Creates the group. */
        ViewIdentifierItems() {
            // Intentionally empty: the group contributes assertions, not state.
        }

        /**
         * A retrieved record publishes both identifier items, independently and at full width.
         *
         * <p>Traceability: the search item at {@code app/cbl/COTRN01C.cbl} line 60 with its display
         * counterpart at line 188, and the record's own item at line 66 with its counterpart at line 194.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("a retrieved record publishes the echoed key and the record's own identifier")
        void aRetrievedRecordPublishesBothIdentifierItems() throws Exception {
            final Transaction stored = writeWideValuesRow();

            final JsonNode body = bodyOf(postView(stored.getTranId(), null, "ENTER", reEntry()));

            assertThat(textOf(body, "searchTransactionId"))
                    .as("the key stays on the screen after the read, which is what lets an operator see "
                            + "what was looked up")
                    .isEqualTo(stored.getTranId())
                    .hasSize(TRANSACTION_ID_WIDTH);
            assertThat(textOf(body, "transactionId"))
                    .as("the retrieved record's own identifier is a separate item on the display side "
                            + "of the map and is published separately")
                    .isEqualTo(stored.getTranId())
                    .hasSize(TRANSACTION_ID_WIDTH);
            assertThat(body.has("searchTransactionId") && body.has("transactionId"))
                    .as("both items exist independently; merging them would break the contract")
                    .isTrue();
        }

        /**
         * The record items are published at the widths this screen declares for them.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("the record items are published at this screen's own widths")
        void theRecordItemsArePublishedAtThisScreensWidths() throws Exception {
            final Transaction stored = writeWideValuesRow();

            final JsonNode body = bodyOf(postView(stored.getTranId(), null, "ENTER", reEntry()));

            assertThat(textOf(body, "cardNumber"))
                    .as("sixteen characters, from the delivered cross-reference seed")
                    .isEqualTo(SEEDED_CARD_NUMBER)
                    .hasSize(16);
            assertThat(textOf(body, "typeCode")).hasSize(2);
            assertThat(textOf(body, "categoryCode")).hasSize(4);
            assertThat(textOf(body, "merchantId")).hasSize(9);
            assertThat(textOf(body, "originationDate")).hasSize(VIEW_DATE_WIDTH);
            assertThat(textOf(body, "processingDate")).hasSize(VIEW_DATE_WIDTH);
            assertThat(textOf(body, "description")).hasSize(VIEW_DESCRIPTION_WIDTH);
        }

        /**
         * The source item is the raw stored value, never an enumerated substitution.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("the source item is the raw stored value and never an enumerated substitution")
        void theSourceItemIsTheRawStoredValue() throws Exception {
            final Transaction stored = writeWideValuesRow();

            final JsonNode body = bodyOf(postView(stored.getTranId(), null, "ENTER", reEntry()));

            assertThat(textOf(body, "source"))
                    .as("the item is ten characters of stored text, published exactly as stored, so a "
                            + "value the estate never declared would still reach an operator rather "
                            + "than being refused or renamed on the way out")
                    .isEqualTo(stored.getTranSource());
            assertThat(textOf(body, "source"))
                    .as("no constant name of any enumerated type is substituted for the stored text")
                    .isNotEqualTo("POS_TERM");
        }

        /**
         * A retrieved record's amount is the exact decimal the record stores.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("the amount is the exact stored decimal, written plainly")
        void theAmountIsTheExactStoredDecimal() throws Exception {
            final Transaction stored = writeWideValuesRow();

            final MvcResult result = postView(stored.getTranId(), null, "ENTER", reEntry());
            final String rawBody = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

            assertThat(numberLiteralOf(rawBody, "amount"))
                    .as("the value reaches the wire at the record's own scale and without an exponent")
                    .isEqualTo(WIDEST_AMOUNT_TEXT);
            assertThat(rawBody)
                    .as("the screen's edited display form is a mask and never crosses the boundary")
                    .doesNotContain("-99999999.99");
        }

        /**
         * A key handed over by the calling screen is used on a first entry, without the operator typing
         * anything.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("a key handed over by the list screen is read on a first entry")
        void aKeyHandedOverIsReadOnAFirstEntry() throws Exception {
            final Transaction stored = writeWideValuesRow();

            final JsonNode body = bodyOf(postView(null, stored.getTranId(), "ENTER", null));

            assertThat(textOf(body, "searchTransactionId"))
                    .as("the carried value is moved into the search item before the read, so an operator "
                            + "arriving from the list sees the record on the very first turn")
                    .isEqualTo(stored.getTranId());
            assertThat(textOf(body, "transactionId")).isEqualTo(stored.getTranId());
        }
    }

    // ===============================================================================================
    // CT01 :: A NOT-FOUND TURN ECHOES THE KEY OVER A BLANK RECORD
    // ===============================================================================================

    /** What the screen shows when the key names no record, and when it names nothing at all. */
    @Nested
    @DisplayName("View - not-found echoes key with blank record")
    class ViewNotFound {

        /** Creates the group. */
        ViewNotFound() {
            // Intentionally empty: the group contributes assertions, not state.
        }

        /**
         * The key is echoed and every record item is blank.
         *
         * <p>Traceability: the display items are blanked before the read, and the read's absent arm at
         * {@code app/cbl/COTRN01C.cbl} line 285 leaves them blanked.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("the key echoes back while every record item stays blank")
        void theKeyEchoesBackWhileEveryRecordItemStaysBlank() throws Exception {
            final String absentKey = reservedIdentifier(999);

            final JsonNode body = bodyOf(postView(absentKey, null, "ENTER", reEntry()));

            assertThat(textOf(body, "searchTransactionId"))
                    .as("the key stays on the screen so the operator can see what was looked up")
                    .isEqualTo(absentKey);
            assertThat(textOf(body, "errorMessage")).isEqualTo(VIEW_TRAN_ID_NOT_FOUND);
            assertThat(switchOf(body, "generalError")).isTrue();
            assertThat(List.of("transactionId", "cardNumber", "typeCode", "categoryCode", "source",
                            "description", "amount", "originationDate", "processingDate", "merchantId",
                            "merchantName", "merchantCity", "merchantZip"))
                    .as("the screen blanks its whole display area before the read, so a turn that "
                            + "retrieved nothing carries no record item at all - which is the state a "
                            + "merged identifier contract could not express")
                    .allSatisfy(blank -> assertThat(textOf(body, blank)).isNull());
        }

        /**
         * A blank key is refused with this screen's own emptiness text.
         *
         * <p>Traceability: line 149. The negation is capitalised and there is no space before the dots.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("a blank key is refused with the capitalised emptiness text")
        void aBlankKeyIsRefusedWithTheCapitalisedEmptinessText() throws Exception {
            final JsonNode body = bodyOf(postView("   ", null, "ENTER", reEntry()));

            assertThat(textOf(body, "errorMessage"))
                    .as("the negation is capitalised, there are exactly three dots and there is no space "
                            + "in front of them")
                    .isEqualTo(VIEW_TRAN_ID_EMPTY)
                    .isEqualTo("Tran ID can NOT be empty...")
                    .contains(" NOT ")
                    .doesNotContain(" not ")
                    .doesNotEndWith(" ...");
            assertThat(textOf(body, "errorMessage"))
                    .as("this text is the published contract constant, not a second copy of it")
                    .isEqualTo(TransactionViewResponse.EMPTY_TRANSACTION_ID_MESSAGE);
            assertThat(switchOf(body, "generalError")).isTrue();
            assertThat(textOf(body, "focusScreenFieldId"))
                    .as("the cursor returns to the search item")
                    .isEqualTo("TRNIDIN");
        }

        /**
         * The blank key is reported per field as missing rather than as invalid.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("a blank key is reported per field as missing, and an absent record adds no entry")
        void aBlankKeyIsReportedAsMissingAndAnAbsentRecordAddsNoEntry() throws Exception {
            final JsonNode blank = bodyOf(postView("   ", null, "ENTER", reEntry()));
            final JsonNode absent =
                    bodyOf(postView(reservedIdentifier(998), null, "ENTER", reEntry()));

            assertThat(textOf(blank.get("fieldErrors").get(0), "state"))
                    .as("nothing was supplied, which is the missing state and not the invalid one")
                    .isEqualTo(ErrorResponse.FieldState.MISSING.name());
            assertThat(absent.get("fieldErrors").size())
                    .as("a key that names no record is an outcome the screen reports rather than an edit "
                            + "failure, so it names no field")
                    .isZero();
        }

        /**
         * No raw response code, exception name, statement text, path or file status reaches the wire.
         *
         * <p>Traceability: the legacy writes a pair of raw response codes to the operator's screen at
         * line 290; that disclosure is deliberately not reproduced.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("no raw response code, exception name or file status reaches the wire")
        void noRawResponseCodeReachesTheWire() throws Exception {
            final MvcResult result =
                    postView(reservedIdentifier(997), null, "ENTER", reEntry());
            final String rawBody = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

            assertThat(applicationTextOf(rawBody))
                    .as("the operator gets a sentence, never a diagnostic; the three-letter prefix "
                            + "covers every symbolic transaction-manager and map constant at once, "
                            + "response codes and attribute names alike")
                    .doesNotContain("RESP")
                    .doesNotContain("DFH")
                    .doesNotContain("Exception")
                    .doesNotContain("org.springframework")
                    .doesNotContain("com.carddemo")
                    .doesNotContain("SELECT ")
                    .doesNotContain("jdbc:")
                    .doesNotContain("stackTrace")
                    .doesNotContain("fileStatus")
                    .doesNotContain("EXEC CICS");
        }
    }

    // ===============================================================================================
    // CT02 :: NO IDENTIFIER INPUT, AND THE FIRST IDENTIFIER IS SIXTEEN CHARACTERS
    // ===============================================================================================

    /**
     * Where the identifier of a new transaction comes from.
     *
     * <p>Not from the operator: the map has no input item for it. Not from a sequence, an identity column
     * or a random value either - the legacy browses to the highest key present, adds one and stores the
     * result back into a sixteen-digit field. That last part is what makes the first identifier on an
     * empty cluster sixteen characters rather than one character, and the derivation from the current
     * maximum is what keeps the sequence gapless after a turn that rolled back, which a sequence could
     * not do.
     */
    @Nested
    @DisplayName("Add - no id input, first id is 0000000000000001")
    class AddIdentifierAllocation {

        /** Creates the group. */
        AddIdentifierAllocation() {
            // Intentionally empty: the group contributes assertions, not state.
        }

        /**
         * The first identifier written into an empty cluster is sixteen zero-padded characters.
         *
         * <p>The delivered reference seed applies no transaction row, so the empty cluster this assertion
         * needs is the delivered state itself rather than a contrivance.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("the first identifier on an empty cluster is 0000000000000001, not 1")
        void theFirstIdentifierOnAnEmptyClusterIsSixteenCharacters() throws Exception {
            assertThat(transactions.count())
                    .as("the delivered reference seed applies no transaction row, so this starts from a "
                            + "genuinely empty cluster")
                    .isZero();

            final JsonNode body = bodyOf(postAdd(validAddBody("Y")));

            assertThat(textOf(body, "newTransactionId"))
                    .as("the derived value is stored back into a sixteen-digit field, which zero fills "
                            + "on the left, so the very first identifier is sixteen characters wide")
                    .isEqualTo("0000000000000001")
                    .hasSize(TRANSACTION_ID_WIDTH);
            assertThat(textOf(body, "newTransactionId"))
                    .as("it is a bounded string that keeps its leading zeros and never an integral "
                            + "number, which would have published the single character one")
                    .isNotEqualTo("1");
        }

        /**
         * The identifier is the current maximum plus one, so two consecutive turns ascend by one.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("two consecutive turns mint the maximum plus one, ascending by exactly one")
        void twoConsecutiveTurnsMintTheMaximumPlusOne() throws Exception {
            final String first = textOf(bodyOf(postAdd(validAddBody("Y"))), "newTransactionId");
            final String second = textOf(bodyOf(postAdd(validAddBody("Y"))), "newTransactionId");

            assertThat(first).isEqualTo("0000000000000001");
            assertThat(second)
                    .as("the second turn reads the maximum the first turn left and adds one to it")
                    .isEqualTo("0000000000000002");
            assertThat(transactions.findMaxId())
                    .as("the maximum the store now holds is the identifier the second turn minted, "
                            + "which is the value the next turn will derive from")
                    .contains(second);
            assertThat(transactions.count()).isEqualTo(2);
        }

        /**
         * The maximum is read from whatever is present, not from a counter of its own.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("the derivation reads the highest key actually present, whatever it is")
        void theDerivationReadsTheHighestKeyActuallyPresent() throws Exception {
            transactions.saveAll(List.of(TestDataFactory.transaction()
                    .id("0000000000000005")
                    .cardNumber(SEEDED_CARD_NUMBER)
                    .build()));
            transactions.flush();

            final JsonNode body = bodyOf(postAdd(validAddBody("Y")));

            assertThat(textOf(body, "newTransactionId"))
                    .as("the highest key present is five, so the derived identifier is six - a sequence "
                            + "or an identity column would answer one here and would then diverge "
                            + "permanently from the cluster")
                    .isEqualTo("0000000000000006");
        }

        /**
         * The screen has no identifier input at all, so a client that sends one is not obeyed.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("an identifier a client tries to supply is not used; the derivation still decides")
        void anIdentifierAClientTriesToSupplyIsNotUsed() throws Exception {
            transactions.saveAll(List.of(TestDataFactory.transaction()
                    .id("0000000000000005")
                    .cardNumber(SEEDED_CARD_NUMBER)
                    .build()));
            transactions.flush();
            final Map<String, Object> body = validAddBody("Y");
            body.put("transactionId", "0000000000000099");
            body.put("newTransactionId", "0000000000000099");

            final JsonNode response = bodyOf(postAdd(body));

            assertThat(textOf(response, "newTransactionId"))
                    .as("the map declares no input item for the identifier, so nothing a client names "
                            + "reaches the allocation and the derived value still decides")
                    .isEqualTo("0000000000000006");
            assertThat(transactions.findById("0000000000000099"))
                    .as("the identifier the client asked for was never written")
                    .isEmpty();
        }

        /**
         * The identifier the maximum-plus-one rule cannot honour is refused rather than replaced.
         *
         * <p>Storing the increment back into a sixteen-digit field keeps its low-order sixteen digits, so a
         * cluster whose highest key is all nines derives an identifier of all zeros. When that identifier
         * is already present the turn reports the duplicate rather than minting something else, and the
         * report keeps the legacy's singular verb.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("a derived identifier already present is reported with the singular verb")
        void aDerivedIdentifierAlreadyPresentIsReported() throws Exception {
            transactions.saveAll(List.of(
                    TestDataFactory.transaction().id("0000000000000000")
                            .cardNumber(SEEDED_CARD_NUMBER).build(),
                    TestDataFactory.transaction().id("9999999999999999")
                            .cardNumber(SEEDED_CARD_NUMBER).build()));
            transactions.flush();

            final MvcResult result = postAdd(validAddBody("Y"));
            final JsonNode body = bodyOf(result);

            assertThat(textOf(body, "message"))
                    .as("the verb is singular in the legacy text and stays singular here")
                    .isEqualTo(TransactionAddResponse.MESSAGE_DUPLICATE_TRANSACTION_ID)
                    .isEqualTo("Tran ID already exist...")
                    .doesNotContain("exists");
            assertThat(switchOf(body, "generalError")).isTrue();
            assertThat(textOf(body, "newTransactionId"))
                    .as("no record was written, so no identifier is published")
                    .isNull();
            assertThat(textOf(body, "focusScreenFieldId"))
                    .as("the duplicate arm returns the cursor to the account identifier item")
                    .isEqualTo("ACTIDIN");
            assertThat(applicationTextOf(result.getResponse().getContentAsString(StandardCharsets.UTF_8)))
                    .as("the legacy writes a response code and a reason code to the screen on this arm; "
                            + "neither reaches the wire here")
                    .doesNotContain("RESP")
                    .doesNotContain("reasonCode")
                    .doesNotContain("Exception");
            assertThat(transactions.count())
                    .as("the two seeded rows are all that remain")
                    .isEqualTo(2);
        }

        /**
         * The failure text the write's catch-all arm reports is reproduced exactly, and neither it nor the
         * duplicate text is a paraphrase of the other.
         */
        @Test
        @DisplayName("the write-failure text is its own value, distinct from the duplicate text")
        void theWriteFailureTextIsItsOwnValue() {
            assertThat(TransactionAddResponse.MESSAGE_ADD_FAILED)
                    .isEqualTo("Unable to Add Transaction...")
                    .isNotEqualTo(TransactionAddResponse.MESSAGE_DUPLICATE_TRANSACTION_ID);
        }
    }

    // ===============================================================================================
    // CT02 :: THE CASCADE STOPS AT ITS FIRST FAILURE, AND THE ORDER IS THE CONTRACT
    // ===============================================================================================

    /**
     * The ordered cascade of the add screen.
     *
     * <p>Every assertion here submits a turn that is faulty in <em>two</em> ordered places and requires
     * the earlier of the two texts. Requiring only that each text can be produced would pass against an
     * implementation that had reordered the stages, and a reordered cascade reports a different fault to
     * an operator than the legacy reported for the same submission.
     *
     * <p>The execution order is the paragraph's, not the listing's: the two validation paragraphs run
     * first and the confirmation is evaluated last, even though the confirmation arm appears above both
     * paragraphs in the member. Because the send paragraph ends by returning to the transaction manager,
     * the first arm that reports also ends the turn.
     */
    @Nested
    @DisplayName("Add - 23-stage ordering")
    class AddCascadeOrdering {

        /** Creates the group. */
        AddCascadeOrdering() {
            // Intentionally empty: the group contributes assertions, not state.
        }

        /**
         * Ordering one: a key field outranks the confirmation, even though the confirmation arm is written
         * above the key-field paragraph.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("a faulty key field outranks a faulty confirmation value")
        void aFaultyKeyFieldOutranksAFaultyConfirmation() throws Exception {
            assertThat(addSummaryWith(override("accountId", "NOTNUMERIC1", "confirm", "Q")))
                    .as("both validation paragraphs are performed before the confirmation is evaluated, "
                            + "and the send that reports the key fault ends the turn, so the "
                            + "confirmation arm is never reached")
                    .isEqualTo(TransactionAddResponse.MESSAGE_ACCOUNT_ID_NOT_NUMERIC)
                    .isEqualTo("Account ID must be Numeric...")
                    .isNotEqualTo(TransactionAddResponse.MESSAGE_CONFIRM_INVALID);
        }

        /**
         * Ordering two: a key field outranks every emptiness check.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("a faulty key field outranks the eleven emptiness checks")
        void aFaultyKeyFieldOutranksTheEmptinessChecks() throws Exception {
            final Map<String, Object> overrides = new LinkedHashMap<>();
            overrides.put("accountId", "NOTNUMERIC1");
            overrides.put("typeCode", "  ");
            overrides.put("categoryCode", "    ");
            overrides.put("merchantZip", "          ");

            assertThat(addSummaryWith(overrides))
                    .as("the key-field paragraph is performed first, so its fault is the one reported "
                            + "even though four later stages would also have faulted")
                    .isEqualTo(TransactionAddResponse.MESSAGE_ACCOUNT_ID_NOT_NUMERIC);
        }

        /**
         * Ordering three: the account key is consulted before the card key.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("the account key is consulted before the card key")
        void theAccountKeyIsConsultedBeforeTheCardKey() throws Exception {
            assertThat(addSummaryWith(override("accountId", ABSENT_ACCOUNT_ID,
                            "cardNumber", ABSENT_CARD_NUMBER)))
                    .as("the first arm of the key evaluation tests the account item, so an account that "
                            + "resolves nothing is reported and the card item is never looked up")
                    .isEqualTo(TransactionAddResponse.MESSAGE_ACCOUNT_ID_NOT_FOUND)
                    .isEqualTo("Account ID NOT found...")
                    .isNotEqualTo(TransactionAddResponse.MESSAGE_CARD_NUMBER_NOT_FOUND);
        }

        /**
         * Ordering four: the eleven emptiness checks run in their declared order.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("the eleven emptiness checks run in their declared order")
        void theElevenEmptinessChecksRunInTheirDeclaredOrder() throws Exception {
            final List<String> members = List.of("typeCode", "categoryCode", "transactionSource",
                    "description", "amount", "originationDate", "processingDate", "merchantId",
                    "merchantName", "merchantCity", "merchantZip");
            final List<String> texts = List.of(
                    TransactionAddResponse.MESSAGE_TYPE_CODE_EMPTY,
                    TransactionAddResponse.MESSAGE_CATEGORY_CODE_EMPTY,
                    TransactionAddResponse.MESSAGE_SOURCE_EMPTY,
                    TransactionAddResponse.MESSAGE_DESCRIPTION_EMPTY,
                    TransactionAddResponse.MESSAGE_AMOUNT_EMPTY,
                    TransactionAddResponse.MESSAGE_ORIGINATION_DATE_EMPTY,
                    TransactionAddResponse.MESSAGE_PROCESSING_DATE_EMPTY,
                    TransactionAddResponse.MESSAGE_MERCHANT_ID_EMPTY,
                    TransactionAddResponse.MESSAGE_MERCHANT_NAME_EMPTY,
                    TransactionAddResponse.MESSAGE_MERCHANT_CITY_EMPTY,
                    TransactionAddResponse.MESSAGE_MERCHANT_ZIP_EMPTY);

            // Blank the members from the last one backwards. At each step every member from the current
            // one to the end is blank, so the text that comes back names the earliest of them - which is
            // an assertion about the order of the eleven arms and not merely about the eleven texts.
            final Map<String, Object> blanked = new LinkedHashMap<>();
            for (int stage = members.size() - 1; stage >= 0; stage--) {
                blanked.put(members.get(stage), "");
                assertThat(addSummaryWith(blanked))
                        .as("with every member from stage %d to the end blank, the earliest of them is "
                                + "the one reported", stage + 1)
                        .isEqualTo(texts.get(stage));
            }
            assertThat(texts)
                    .as("each of the eleven carries the capitalised negation, three dots and no space "
                            + "before them")
                    .hasSize(11)
                    .doesNotHaveDuplicates()
                    .allSatisfy(text -> assertThat(text)
                            .contains(" can NOT be empty...")
                            .doesNotContain(" can not be empty")
                            .doesNotEndWith(" ..."));
        }

        /**
         * Ordering five: every emptiness check outranks every class check.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("an emptiness check outranks a class check on an earlier item")
        void anEmptinessCheckOutranksAClassCheck() throws Exception {
            assertThat(addSummaryWith(override("typeCode", "AB", "categoryCode", "    ")))
                    .as("the emptiness evaluation runs to completion before the class evaluation begins, "
                            + "so a blank category outranks a non-numeric type even though the type item "
                            + "is declared first")
                    .isEqualTo(TransactionAddResponse.MESSAGE_CATEGORY_CODE_EMPTY)
                    .isNotEqualTo(TransactionAddResponse.MESSAGE_TYPE_CODE_NOT_NUMERIC);
        }

        /**
         * Ordering six: a class check outranks a shape check.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("a class check outranks the amount shape check")
        void aClassCheckOutranksTheShapeCheck() throws Exception {
            // The malformed amount is exactly twelve characters, so it is inside the width the map
            // declares and reaches the cascade: a wider value would be refused declaratively before the
            // handler ran and would prove nothing about the order of the two stages.
            assertThat(addSummaryWith(override("typeCode", "AB", "amount", "X00000100.00")))
                    .as("the class evaluation is performed before the shape test, so the non-numeric "
                            + "type code is reported and the malformed amount is not")
                    .isEqualTo(TransactionAddResponse.MESSAGE_TYPE_CODE_NOT_NUMERIC)
                    .isEqualTo("Type CD must be Numeric...")
                    .isNotEqualTo(TransactionAddResponse.MESSAGE_AMOUNT_FORMAT);
        }

        /**
         * Ordering seven: both shape tests outrank the calendar checks the date utility performs.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("a date shape test outranks the calendar check the date utility performs")
        void aDateShapeTestOutranksTheCalendarCheck() throws Exception {
            assertThat(addSummaryWith(override("originationDate", "2022-02-30",
                            "processingDate", "2022/06/10")))
                    .as("the origination date is well shaped and names no calendar day, and the "
                            + "processing date is badly shaped; the shape tests run first, so the badly "
                            + "shaped one is reported and the utility is never called")
                    .isEqualTo(TransactionAddResponse.MESSAGE_PROCESSING_DATE_FORMAT)
                    .isEqualTo("Proc Date should be in format YYYY-MM-DD")
                    .isNotEqualTo(TransactionAddResponse.MESSAGE_ORIGINATION_DATE_INVALID);
        }

        /**
         * Ordering eight: the calendar checks outrank the merchant-identifier class check, which is the
         * last stage of the data paragraph.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("a calendar check outranks the merchant identifier class check")
        void aCalendarCheckOutranksTheMerchantIdentifierCheck() throws Exception {
            assertThat(addSummaryWith(override("originationDate", "2022-02-30",
                            "merchantId", "NOTNUM123")))
                    .as("the utility is called before the merchant identifier is tested, so the invalid "
                            + "calendar day is reported first")
                    .isEqualTo(TransactionAddResponse.MESSAGE_ORIGINATION_DATE_INVALID)
                    .isEqualTo("Orig Date - Not a valid date...")
                    .isNotEqualTo(TransactionAddResponse.MESSAGE_MERCHANT_ID_NOT_NUMERIC);
        }

        /**
         * The origination date is checked before the processing date by the utility, and both texts keep
         * their lower-case noun and their spaced separator.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("the two calendar texts keep their lower-case noun and spaced separator")
        void theTwoCalendarTextsKeepTheirLowerCaseNoun() throws Exception {
            assertThat(addSummaryWith(override("originationDate", "2022-02-30",
                            "processingDate", "2022-02-31")))
                    .as("the origination date is examined first, so its text is the one reported")
                    .isEqualTo(TransactionAddResponse.MESSAGE_ORIGINATION_DATE_INVALID);
            assertThat(addSummaryWith(override("processingDate", "2022-02-31")))
                    .as("the processing date has its own text, which is never the origination one")
                    .isEqualTo(TransactionAddResponse.MESSAGE_PROCESSING_DATE_INVALID)
                    .isEqualTo("Proc Date - Not a valid date...");
            assertThat(List.of(TransactionAddResponse.MESSAGE_ORIGINATION_DATE_INVALID,
                            TransactionAddResponse.MESSAGE_PROCESSING_DATE_INVALID))
                    .as("both carry a spaced separator and the lower-case noun, unlike the capitalised "
                            + "negation of the eleven emptiness texts")
                    .allSatisfy(text -> assertThat(text)
                            .contains(" - Not a valid date...")
                            .doesNotContain("Not a valid Date"));
        }

        /**
         * A turn that supplies neither key is refused with the text that names both items.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("a turn supplying neither key is refused with the text naming both items")
        void aTurnSupplyingNeitherKeyIsRefused() throws Exception {
            final Map<String, Object> body = validAddBody("Y");
            body.remove("accountId");

            assertThat(textOf(bodyOf(postAdd(body)), "message"))
                    .as("the catch-all arm of the key evaluation is reached only when both items are "
                            + "absent, and it is a missing-value report rather than a class complaint")
                    .isEqualTo(TransactionAddResponse.MESSAGE_KEY_NOT_ENTERED)
                    .isEqualTo("Account or Card Number must be entered...");
        }

        /**
         * A card number that resolves nothing is reported when the account item is absent.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("a card number that resolves nothing is reported on its own item")
        void aCardNumberThatResolvesNothingIsReported() throws Exception {
            final Map<String, Object> body = validAddBody("Y");
            body.remove("accountId");
            body.put("cardNumber", ABSENT_CARD_NUMBER);

            assertThat(textOf(bodyOf(postAdd(body)), "message"))
                    .isEqualTo(TransactionAddResponse.MESSAGE_CARD_NUMBER_NOT_FOUND)
                    .isEqualTo("Card Number NOT found...");
        }

        /**
         * A non-numeric card number is refused on its own class test when the account item is absent.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("a non-numeric card number is refused on its own class test")
        void aNonNumericCardNumberIsRefused() throws Exception {
            final Map<String, Object> body = validAddBody("Y");
            body.remove("accountId");
            body.put("cardNumber", "NOTNUMERIC000000");

            assertThat(textOf(bodyOf(postAdd(body)), "message"))
                    .isEqualTo(TransactionAddResponse.MESSAGE_CARD_NUMBER_NOT_NUMERIC)
                    .isEqualTo("Card Number must be Numeric...");
        }

        /**
         * The account item wins when both keys are supplied, and the card item is filled in from the
         * cross-reference the account resolved.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("when both keys are supplied the account wins and resolves the card")
        void whenBothKeysAreSuppliedTheAccountWins() throws Exception {
            final Map<String, Object> body = validAddBody("Y");
            body.put("cardNumber", ABSENT_CARD_NUMBER);

            final JsonNode response = bodyOf(postAdd(body));

            assertThat(textOf(response, "newTransactionId"))
                    .as("the account resolved, so the turn wrote a record: the card item the client sent "
                            + "was replaced rather than looked up")
                    .isEqualTo("0000000000000001");
            assertThat(transactions.findById("0000000000000001").orElseThrow().getTranCardNum())
                    .as("the written record carries the card the cross-reference gave for the account, "
                            + "not the one the client submitted")
                    .isEqualTo(SEEDED_CARD_NUMBER)
                    .isNotEqualTo(ABSENT_CARD_NUMBER);
        }

        /**
         * The remaining class and shape texts are each reachable and each exact.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("the category class, amount shape and origination shape texts are each exact")
        void theRemainingClassAndShapeTextsAreExact() throws Exception {
            assertThat(addSummaryWith(override("categoryCode", "AB12")))
                    .isEqualTo(TransactionAddResponse.MESSAGE_CATEGORY_CODE_NOT_NUMERIC)
                    .isEqualTo("Category CD must be Numeric...");
            assertThat(addSummaryWith(override("amount", "100.00")))
                    .as("the item is twelve characters of edited text, so a short value does not have "
                            + "the shape the screen declares")
                    .isEqualTo(TransactionAddResponse.MESSAGE_AMOUNT_FORMAT)
                    .isEqualTo("Amount should be in format -99999999.99");
            assertThat(addSummaryWith(override("originationDate", "10/06/2022")))
                    .isEqualTo(TransactionAddResponse.MESSAGE_ORIGINATION_DATE_FORMAT)
                    .isEqualTo("Orig Date should be in format YYYY-MM-DD");
            assertThat(addSummaryWith(override("merchantId", "NOTNUM123")))
                    .isEqualTo(TransactionAddResponse.MESSAGE_MERCHANT_ID_NOT_NUMERIC)
                    .isEqualTo("Merchant ID must be Numeric...");
        }

        /**
         * The two lookup-failure texts of the final stage keep their abbreviated forms.
         *
         * <p>These two arms are reached only when the cross-reference lookup itself fails rather than
         * finding nothing, which is a fault of the store and not of a submission, so they are pinned as
         * the published contract values they are. Both abbreviate a word the not-found texts spell out,
         * and the card one carries a number sign - reproducing them as tidier English would change an
         * operator-visible sentence.
         */
        @Test
        @DisplayName("the two lookup-failure texts keep their abbreviations and their number sign")
        void theTwoLookupFailureTextsKeepTheirAbbreviations() {
            assertThat(TransactionAddResponse.MESSAGE_ACCOUNT_XREF_LOOKUP_FAILED)
                    .as("the account arm abbreviates the noun and names the alternate-index path")
                    .isEqualTo("Unable to lookup Acct in XREF AIX file...")
                    .isNotEqualTo(TransactionAddResponse.MESSAGE_ACCOUNT_ID_NOT_FOUND);
            assertThat(TransactionAddResponse.MESSAGE_CARD_XREF_LOOKUP_FAILED)
                    .as("the card arm carries a number sign and names the base path rather than the "
                            + "alternate index")
                    .isEqualTo("Unable to lookup Card # in XREF file...")
                    .isNotEqualTo(TransactionAddResponse.MESSAGE_CARD_NUMBER_NOT_FOUND);
            assertThat(List.of(TransactionAddResponse.MESSAGE_ACCOUNT_ID_NOT_FOUND,
                            TransactionAddResponse.MESSAGE_ACCOUNT_XREF_LOOKUP_FAILED,
                            TransactionAddResponse.MESSAGE_CARD_NUMBER_NOT_FOUND,
                            TransactionAddResponse.MESSAGE_CARD_XREF_LOOKUP_FAILED))
                    .as("four outcomes of the key stage, four texts, none a substitute for another")
                    .doesNotHaveDuplicates()
                    .hasSize(4);
        }

        /**
         * A date the utility accepts on its tolerated message number is accepted by the caller too.
         *
         * <p>The caller tests the utility's severity first and, when it is not clear, tests the message
         * number and ignores exactly one value. That asymmetry is legacy behaviour and is preserved rather
         * than tidied: a well-shaped date inside the calendar reaches the write, which is what the
         * successful turn below demonstrates for both date items at once.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("a well-formed calendar date passes both utility calls and reaches the write")
        void aWellFormedCalendarDatePassesBothUtilityCalls() throws Exception {
            final Map<String, Object> body = validAddBody("Y");
            body.put("originationDate", "2020-02-29");
            body.put("processingDate", "2000-02-29");

            final JsonNode response = bodyOf(postAdd(body));

            assertThat(textOf(response, "newTransactionId"))
                    .as("two leap days, one in a century year, are both accepted, so neither utility "
                            + "call reported a fault the caller acted on")
                    .isEqualTo("0000000000000001");
            assertThat(switchOf(response, "generalError")).isFalse();
        }
    }

    // ===============================================================================================
    // CT02 :: THE SUCCESS TEXT HAS TWO CONSECUTIVE SPACES, AND THE SCREEN IS CLEARED
    // ===============================================================================================

    /**
     * What a successful turn reports and what it leaves on the screen.
     *
     * <p>The text is composed from two fragments, one of which ends in a space and the other of which
     * begins with one, so the composed sentence carries two consecutive spaces. That is not a defect to be
     * tidied: the text is an external interface that an operator and any downstream tooling match on
     * character for character, so the fragments are declared separately here as well and the double space
     * is asserted explicitly.
     */
    @Nested
    @DisplayName("Add - success double space and cleared inputs")
    class AddSuccess {

        /** Creates the group. */
        AddSuccess() {
            // Intentionally empty: the group contributes assertions, not state.
        }

        /**
         * The composed success text is byte-exact, double space included.
         *
         * <p>Traceability: the two fragments at {@code app/cbl/COTRN02C.cbl} lines 724 and 730.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("the success text is byte-exact and carries two consecutive spaces")
        void theSuccessTextIsByteExactWithTwoConsecutiveSpaces() throws Exception {
            final JsonNode body = bodyOf(postAdd(validAddBody("Y")));

            assertThat(textOf(body, "message"))
                    .as("the whole sentence, exactly as the two fragments and the identifier compose it")
                    .isEqualTo("Transaction added successfully.  Your Tran ID is 0000000000000001.");
            assertThat(textOf(body, "message"))
                    .as("the double space is where the first fragment's trailing space meets the "
                            + "second's leading one, and it is never collapsed")
                    .contains(".  Your")
                    .contains("  ");
            assertThat(switchOf(body, "generalError"))
                    .as("a successful turn raises no error switch")
                    .isFalse();
        }

        /**
         * The two fragments are two separate values and are never pre-joined.
         */
        @Test
        @DisplayName("the two success fragments are declared separately, each keeping its own spaces")
        void theTwoSuccessFragmentsAreDeclaredSeparately() {
            assertThat(TransactionAddResponse.MESSAGE_SUCCESS_PREFIX)
                    .as("the first fragment ends with a space")
                    .isEqualTo("Transaction added successfully. ")
                    .endsWith(" ");
            assertThat(TransactionAddResponse.MESSAGE_SUCCESS_ID_LABEL)
                    .as("the second fragment begins and ends with a space")
                    .isEqualTo(" Your Tran ID is ")
                    .startsWith(" ")
                    .endsWith(" ");
            assertThat(TransactionAddResponse.MESSAGE_SUCCESS_TERMINATOR).isEqualTo(".");
            assertThat(TransactionAddResponse.MESSAGE_SUCCESS_PREFIX
                            + TransactionAddResponse.MESSAGE_SUCCESS_ID_LABEL)
                    .as("joining the two is what produces the double space, so a single pre-joined "
                            + "constant would have hidden the one property that matters about them")
                    .contains("  ");
        }

        /**
         * A successful turn clears every input item, so blank echoes are the expected outcome.
         *
         * <p>Traceability: the reset performed immediately before the text is composed, around line 724.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("a successful turn clears every input item to blanks of the declared width")
        void aSuccessfulTurnClearsEveryInputItem() throws Exception {
            final JsonNode body = bodyOf(postAdd(validAddBody("Y")));

            assertThat(textOf(body, "accountId")).isEqualTo(" ".repeat(11));
            assertThat(textOf(body, "cardNumber")).isEqualTo(" ".repeat(16));
            assertThat(textOf(body, "typeCode")).isEqualTo(" ".repeat(2));
            assertThat(textOf(body, "categoryCode")).isEqualTo(" ".repeat(4));
            assertThat(textOf(body, "source")).isEqualTo(" ".repeat(10));
            assertThat(textOf(body, "description")).isEqualTo(" ".repeat(60));
            assertThat(textOf(body, "amountEntered")).isEqualTo(" ".repeat(12));
            assertThat(textOf(body, "originationDate")).isEqualTo(" ".repeat(10));
            assertThat(textOf(body, "processingDate")).isEqualTo(" ".repeat(10));
            assertThat(textOf(body, "merchantId")).isEqualTo(" ".repeat(9));
            assertThat(textOf(body, "merchantName")).isEqualTo(" ".repeat(30));
            assertThat(textOf(body, "merchantCity")).isEqualTo(" ".repeat(25));
            assertThat(textOf(body, "merchantZip")).isEqualTo(" ".repeat(10));
            assertThat(textOf(body, "confirmationFlag"))
                    .as("the confirmation item is cleared with the rest, so a second turn is not armed "
                            + "to write again")
                    .isEqualTo(" ");
            assertThat(textOf(body, "focusScreenFieldId"))
                    .as("the cursor returns to the first input item of the cleared screen")
                    .isEqualTo("ACTIDIN");
        }

        /**
         * The stored amount is published only after a write, and the submitted text is a separate item.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("the stored amount appears only after a write; the submitted text is its own item")
        void theStoredAmountAppearsOnlyAfterAWrite() throws Exception {
            final JsonNode written = bodyOf(postAdd(validAddBody("Y")));
            final JsonNode refused = bodyOf(postAdd(validAddBody("N")));

            assertThat(written.get("amount").decimalValue())
                    .as("the stored value is the exact decimal the record holds, negative because the "
                            + "submitted edited value carried a minus sign")
                    .isEqualByComparingTo(new BigDecimal("-100.00"));
            assertThat(refused.has("amount"))
                    .as("a turn that wrote nothing has no stored amount to publish")
                    .isFalse();
            assertThat(textOf(refused, "amountEntered"))
                    .as("the submitted text is a separate item and is redisplayed exactly as typed, at "
                            + "the twelve characters the screen declares")
                    .isEqualTo("-00000100.00")
                    .hasSize(12);
        }

        /**
         * The written record carries the values the turn was given, at the record's own widths.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("the written record carries the submitted values at the record's own widths")
        void theWrittenRecordCarriesTheSubmittedValues() throws Exception {
            bodyOf(postAdd(validAddBody("Y")));

            final Transaction stored = transactions.findById("0000000000000001").orElseThrow();

            assertThat(stored.getTranTypeCd()).isEqualTo("01");
            assertThat(stored.getTranCatCd()).isEqualTo("0001");
            assertThat(stored.getTranAmt()).isEqualByComparingTo(new BigDecimal("-100.00"));
            assertThat(SensitiveValues.fingerprint(stored.getTranCardNum())).isEqualTo(SensitiveValues.fingerprint(SEEDED_CARD_NUMBER));
            assertThat(stored.getTranDesc())
                    .as("the description is stored at the record's hundred characters, space filled to "
                            + "the right of the sixty the screen accepted")
                    .hasSize(100)
                    .startsWith("Reserved contract row");
            assertThat(stored.getTranOrigTs())
                    .as("a ten-character date is left justified into a twenty-six character timestamp "
                            + "and space filled, and the trailing spaces are not trimmed away")
                    .isEqualTo("2022-06-10" + " ".repeat(16))
                    .hasSize(STORED_TIMESTAMP_WIDTH);
            assertThat(stored.getTranProcTs())
                    .isEqualTo("2022-06-10" + " ".repeat(16))
                    .hasSize(STORED_TIMESTAMP_WIDTH);
        }

        /**
         * No colour, highlight or attribute reaches the wire, although the legacy recolours the message
         * item on this one arm.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("the successful turn publishes no colour, highlight or attribute")
        void theSuccessfulTurnPublishesNoColour() throws Exception {
            final MvcResult result = postAdd(validAddBody("Y"));
            final String rawBody = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

            assertThat(applicationTextOf(rawBody))
                    .as("the legacy sets the message item green here and red on every failure arm; a "
                            + "colour is a terminal attribute and has no place in a machine contract")
                    .doesNotContain("GREEN")
                    .doesNotContain("green")
                    .doesNotContain("RED")
                    .doesNotContain("colour")
                    .doesNotContain("color")
                    .doesNotContain("highlight")
                    .doesNotContain("attribute");
        }

        /**
         * The route a successful turn resolves is the add screen itself, which is where the legacy
         * re-armed.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("a successful turn resolves back to the add screen")
        void aSuccessfulTurnResolvesBackToTheAddScreen() throws Exception {
            final JsonNode body = bodyOf(postAdd(validAddBody("Y")));

            assertThat(textOf(body, "nextRoute"))
                    .as("the legacy re-armed its own transaction rather than transferring, so the route "
                            + "the client is given next is this screen")
                    .isEqualTo(NavigationService.Route.TRANSACTION_ADD.getRouteValue());
        }
    }

    // ===============================================================================================
    // CT02 :: THREE CONFIRMATION ARMS
    // ===============================================================================================

    /**
     * The confirmation item, which is one character and answers three ways.
     *
     * <p>An affirmative answer writes, a negative or unanswered one prompts, and anything else is refused
     * in terms that quote the two values the screen accepts. Three outcomes is why the item crosses the
     * boundary as a one-character value rather than as a two-state flag: a flag could not carry the third
     * answer back to the caller at all.
     */
    @Nested
    @DisplayName("Add - confirmation arms")
    class AddConfirmation {

        /** Creates the group. */
        AddConfirmation() {
            // Intentionally empty: the group contributes assertions, not state.
        }

        /**
         * An affirmative answer writes, in either case.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("an affirmative answer writes, in upper case and in lower case alike")
        void anAffirmativeAnswerWrites() throws Exception {
            final JsonNode upper = bodyOf(postAdd(validAddBody("Y")));
            final JsonNode lower = bodyOf(postAdd(validAddBody("y")));

            assertThat(textOf(upper, "newTransactionId")).isEqualTo("0000000000000001");
            assertThat(textOf(lower, "newTransactionId"))
                    .as("the lower-case answer reaches the same arm, so the second turn writes too")
                    .isEqualTo("0000000000000002");
            assertThat(transactions.count()).isEqualTo(2);
        }

        /**
         * A negative or unanswered confirmation prompts and writes nothing.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("a negative, a lower-case negative and an unanswered item all prompt")
        void aNegativeOrUnansweredConfirmationPrompts() throws Exception {
            final Map<String, Object> unanswered = validAddBody("Y");
            unanswered.remove("confirm");

            assertThat(textOf(bodyOf(postAdd(validAddBody("N"))), "message"))
                    .isEqualTo(TransactionAddResponse.MESSAGE_CONFIRM_PROMPT)
                    .isEqualTo("Confirm to add this transaction...");
            assertThat(textOf(bodyOf(postAdd(validAddBody("n"))), "message"))
                    .isEqualTo(TransactionAddResponse.MESSAGE_CONFIRM_PROMPT);
            assertThat(textOf(bodyOf(postAdd(unanswered)), "message"))
                    .as("a declined confirmation and an unanswered one take the same arm and produce the "
                            + "same prompt, which is the member's own conflation and is preserved")
                    .isEqualTo(TransactionAddResponse.MESSAGE_CONFIRM_PROMPT);
            assertThat(transactions.count())
                    .as("none of the three arms wrote anything")
                    .isZero();
        }

        /**
         * Any other single character is refused in the screen's own words.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("a third value is refused with the text quoting the two accepted values")
        void aThirdValueIsRefused() throws Exception {
            final JsonNode body = bodyOf(postAdd(validAddBody("Q")));

            assertThat(textOf(body, "message"))
                    .as("the parentheses, the solidus and the three dots are all part of the text")
                    .isEqualTo(TransactionAddResponse.MESSAGE_CONFIRM_INVALID)
                    .isEqualTo("Invalid value. Valid values are (Y/N)...");
            assertThat(textOf(body, "confirmationFlag"))
                    .as("the item is one character of text, so the unrecognised answer is quoted back to "
                            + "the caller - which a two-state flag could not have done")
                    .isEqualTo("Q");
            assertThat(switchOf(body, "generalError")).isTrue();
            assertThat(textOf(body, "focusScreenFieldId")).isEqualTo("CONFIRM");
            assertThat(transactions.count()).isZero();
        }

        /**
         * A prompted turn redisplays every submitted value, so the operator confirms what they typed.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("a prompted turn redisplays the submitted values rather than clearing them")
        void aPromptedTurnRedisplaysTheSubmittedValues() throws Exception {
            final JsonNode body = bodyOf(postAdd(validAddBody("N")));

            assertThat(textOf(body, "accountId"))
                    .as("the account item is redisplayed zero filled to its eleven characters, which is "
                            + "what the member's numeric store leaves in it")
                    .isEqualTo(SEEDED_ACCOUNT_ID);
            assertThat(textOf(body, "typeCode")).isEqualTo("01");
            assertThat(textOf(body, "amountEntered")).isEqualTo("-00000100.00");
            assertThat(textOf(body, "confirmationFlag")).isEqualTo("N");
            assertThat(textOf(body, "cardNumber"))
                    .as("the card item is filled in from the cross-reference the account resolved, so "
                            + "the operator sees which card the turn would post to")
                    .isEqualTo(SEEDED_CARD_NUMBER);
        }
    }

    // ===============================================================================================
    // THE TWO SHARED TEXTS, NEVER TRIMMED
    // ===============================================================================================

    /**
     * The two texts these screens share with the other fourteen online transactions.
     *
     * <p>Both are stored in a fifty-character field and both are shorter than fifty, so both carry
     * trailing spaces that are part of the stored value. They are asserted at full width against an
     * expected value written out in full, and never with a whitespace-tolerant comparison: an assertion
     * that trimmed would pass just as well against an implementation that had trimmed, and the width is
     * the part of the contract most easily lost.
     */
    @Nested
    @DisplayName("Common-message fidelity")
    class CommonMessages {

        /** Creates the group. */
        CommonMessages() {
            // Intentionally empty: the group contributes assertions, not state.
        }

        /**
         * An unmapped attention key on the list screen composes the shared text at its full width.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("an unmapped key on the list screen composes the shared text at fifty characters")
        void anUnmappedKeyOnTheListScreenComposesTheSharedText() throws Exception {
            writeOrderedFixture();
            final Map<String, Object> turn = listBody("PFK09");
            turn.put("navigationContext", reEntry());

            final JsonNode body = bodyOf(postList(turn));

            assertThat(textOf(body, "message"))
                    .as("the stored value is the forty character text followed by exactly ten spaces, "
                            + "and it reaches the wire untrimmed")
                    .isEqualTo(INVALID_KEY_MESSAGE_50)
                    .hasSize(COMMON_MESSAGE_WIDTH);
            assertThat(switchOf(body, "error")).isTrue();
        }

        /**
         * An unmapped attention key on the view screen composes the same shared text at the same width.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("an unmapped key on the view screen composes the same text at the same width")
        void anUnmappedKeyOnTheViewScreenComposesTheSameText() throws Exception {
            final JsonNode body = bodyOf(postView("0000000000000001", null, "PFK09", reEntry()));

            assertThat(textOf(body, "errorMessage"))
                    .isEqualTo(INVALID_KEY_MESSAGE_50)
                    .hasSize(COMMON_MESSAGE_WIDTH);
        }

        /**
         * The shipped catalogue holds both texts at their full stored width.
         */
        @Test
        @DisplayName("the catalogue holds both texts at fifty characters, trailing spaces included")
        void theCatalogueHoldsBothTextsAtFiftyCharacters() {
            assertThat(messages.invalidKeyMessage())
                    .as("forty characters of text and exactly ten spaces")
                    .isEqualTo(INVALID_KEY_MESSAGE_50)
                    .isEqualTo("Invalid key pressed. Please see below...          ")
                    .hasSize(COMMON_MESSAGE_WIDTH)
                    .endsWith("          ");
            assertThat(messages.thankYouMessage())
                    .as("forty-three characters of text and exactly seven spaces")
                    .isEqualTo(THANK_YOU_MESSAGE_50)
                    .isEqualTo("Thank you for using CardDemo application...       ")
                    .hasSize(COMMON_MESSAGE_WIDTH)
                    .endsWith("       ");
            assertThat(messages.invalidKeyMessage())
                    .as("the two are different texts of the same width and are never interchangeable")
                    .isNotEqualTo(messages.thankYouMessage());
        }

        /**
         * The padding of each text is examined positionally, never by stripping it.
         *
         * <p>Each value is cut at the character position where its sentence ends and both halves are named
         * exactly: the sentence on the left and the space run on the right. Nothing anywhere in this group
         * removes, tolerates or normalises a space - a comparison that stripped would pass just as well
         * against an implementation that had stripped, which is the one failure this whole group exists to
         * catch.
         */
        @Test
        @DisplayName("the padding of each text is examined positionally, never stripped")
        void thePaddingOfEachTextIsExaminedPositionally() {
            assertThat(THANK_YOU_MESSAGE_50.substring(0, 43))
                    .as("the sentence occupies the first forty-three characters")
                    .isEqualTo("Thank you for using CardDemo application...");
            assertThat(THANK_YOU_MESSAGE_50.substring(43))
                    .as("exactly seven spaces follow it, and they are part of the stored value")
                    .isEqualTo("       ")
                    .hasSize(7);
            assertThat(INVALID_KEY_MESSAGE_50.substring(0, 40))
                    .as("the sentence occupies the first forty characters")
                    .isEqualTo("Invalid key pressed. Please see below...");
            assertThat(INVALID_KEY_MESSAGE_50.substring(40))
                    .as("exactly ten spaces follow it, and they are part of the stored value")
                    .isEqualTo("          ")
                    .hasSize(10);
            assertThat(COMMON_MESSAGE_WIDTH)
                    .as("both are stored in the same fifty-character field")
                    .isEqualTo(THANK_YOU_MESSAGE_50.length())
                    .isEqualTo(INVALID_KEY_MESSAGE_50.length());
        }
    }

    // ===============================================================================================
    // SECURITY
    // ===============================================================================================

    /**
     * What the three routes require, and what a refusal discloses.
     *
     * <p>All three are answered by the filter chain's closing authenticated rule rather than by an
     * entitlement, which is the shipped arrangement for the twelve ordinary transactions. The five
     * administrative transactions live behind their own prefix and are not widened here.
     */
    @Nested
    @DisplayName("Security negatives")
    class SecurityNegatives {

        /** Creates the group. */
        SecurityNegatives() {
            // Intentionally empty: the group contributes assertions, not state.
        }

        /**
         * A turn carrying no session is refused, on all three routes.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("a turn carrying no session is refused on all three routes")
        void aTurnCarryingNoSessionIsRefused() throws Exception {
            for (final String route : List.of(TransactionController.LIST_PATH,
                    TransactionController.VIEW_PATH, TransactionController.ADD_PATH)) {
                final MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                                .post(TransactionController.TRANSACTION_PATH + route)
                                .contentType(MediaType.APPLICATION_JSON)
                                .characterEncoding(StandardCharsets.UTF_8)
                                .accept(MediaType.APPLICATION_JSON)
                                .content("{}"))
                        .andReturn();

                assertThat(result.getResponse().getStatus())
                        .as("no session was presented for route %s", route)
                        .isEqualTo(401);
                assertThat(textOf(JSON.readTree(
                                result.getResponse().getContentAsString(StandardCharsets.UTF_8)),
                                "message"))
                        .as("the refusal carries the module's own neutral summary")
                        .isEqualTo(AUTHENTICATION_REQUIRED);
            }
        }

        /**
         * An ordinary identity is admitted, so these routes are not administratively gated.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("an ordinary identity is admitted; these three are not administratively gated")
        void anOrdinaryIdentityIsAdmitted() throws Exception {
            writeOrderedFixture();

            final MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                            .post(TransactionController.TRANSACTION_PATH
                                    + TransactionController.LIST_PATH)
                            .header(HttpHeaders.AUTHORIZATION,
                                    sessionFor(SEEDED_USER_ID, UserType.USER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .characterEncoding(StandardCharsets.UTF_8)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(JSON.writeValueAsString(listBody("ENTER"))))
                    .andReturn();

                assertThat(result.getResponse().getStatus())
                        .as("the twelve ordinary transactions are answered by the closing authenticated "
                                + "rule, which every signed-on cardholder satisfies")
                        .isEqualTo(200);
            assertThat(publishedIdentifiers(bodyOf(result)))
                    .hasSize(PageMetadata.TRANSACTION_LIST_PAGE_SIZE);
            assertThat(textOf(bodyOf(result).get("navigationContext"), "userType"))
                    .as("the echoed navigation record carries the authenticated role rather than "
                            + "whatever a client claimed")
                    .isEqualTo(UserType.USER.getCode());
        }

        /**
         * A submission wider than a map item is refused before the handler runs, and the refusal
         * discloses nothing.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("a submission wider than a map item is refused without disclosing anything")
        void aSubmissionWiderThanAMapItemIsRefused() throws Exception {
            final Map<String, Object> body = validAddBody("Y");
            body.put("typeCode", "0123456789");

            final MvcResult result = postAdd(body);
            final String rawBody = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

            assertThat(result.getResponse().getStatus())
                    .as("a malformed request is a different matter from a screen outcome, so it is "
                            + "refused declaratively rather than answered with a screen")
                    .isEqualTo(400);
            assertThat(applicationTextOf(rawBody))
                    .as("a refusal names the item and nothing about the process serving it")
                    .doesNotContain("Exception")
                    .doesNotContain("org.springframework")
                    .doesNotContain("com.carddemo")
                    .doesNotContain("stackTrace")
                    .doesNotContain("SELECT ")
                    .doesNotContain("jdbc:")
                    .doesNotContain("/tmp")
                    .doesNotContain("carddemo-java/src");
            assertThat(transactions.count())
                    .as("a refused submission wrote nothing")
                    .isZero();
        }

        /**
         * A rejected screen turn discloses no diagnostic either.
         *
         * @throws Exception if the boundary cannot be reached
         */
        @Test
        @DisplayName("a rejected screen turn discloses no diagnostic and no legacy artefact")
        void aRejectedScreenTurnDisclosesNoDiagnostic() throws Exception {
            final MvcResult result = postAdd(validAddBody("Q"));
            final String rawBody = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

            assertThat(applicationTextOf(rawBody))
                    .as("no diagnostic, no statement text, no secret and no legacy artefact of any kind")
                    .doesNotContain("Exception")
                    .doesNotContain("stackTrace")
                    .doesNotContain("SELECT ")
                    .doesNotContain("INSERT INTO")
                    .doesNotContain("jdbc:")
                    .doesNotContain("EXEC CICS")
                    .doesNotContain("DFH")
                    .doesNotContain("PIC X(")
                    .doesNotContain("//STEP")
                    .doesNotContain("fileStatus")
                    .doesNotContain("secret")
                    .doesNotContain("credential");
        }

        /**
         * The administrative prefix these routes are deliberately not part of stays as it is.
         */
        @Test
        @DisplayName("the three transaction routes sit outside the administrative prefix")
        void theThreeRoutesSitOutsideTheAdministrativePrefix() {
            assertThat(TransactionController.TRANSACTION_PATH)
                    .as("widening the administrative gate to cover an ordinary transaction, or narrowing "
                            + "it away from one of the five it covers, would both be contract changes")
                    .isEqualTo("/api/transactions")
                    .doesNotStartWith(SecurityConfig.ADMIN_PATH_PREFIX);
        }
    }

    // ===============================================================================================
    // THE GRAPH UNDER TEST
    // ===============================================================================================

    /**
     * The three transaction screens: the shipped boundary, the shipped filter chain, the three screen
     * services and the rows they read and write.
     *
     * <p>Assembled explicitly rather than by scanning, which is this module's established practice for a
     * context-booting specification: a scan of the base package would sweep the test tree's own
     * configuration classes into the graph, and an explicit list lets a reader see in one place exactly
     * what took part.
     *
     * <p><strong>Nothing is stubbed.</strong> The three screen services, the wire-to-service screen
     * adapter, the date utility, the shared message catalogue, the navigation vocabulary, the independent
     * write boundary the identifier allocation runs inside, the failure advice, the neutral refusal
     * contract, the filter chain and the session token provider are all the shipped ones, and every row
     * comes off a real server.
     *
     * <p>The clock is the pinned instant the shared base publishes, so the header values a turn composes
     * and a session's issued-at and expiry images mean the same thing on every run.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = PrometheusExemplarsAutoConfiguration.class)
    @Import({TransactionController.class, ModuleErrorController.class, ScreenStateAdapter.class,
        GlobalExceptionHandler.class, JsonRefusalBodyRenderer.class, TransactionListService.class,
        TransactionViewService.class, TransactionAddService.class, DateValidationService.class,
        TransactionListPageTokenService.class, SensitiveFieldEncryptionService.class,
        MessageCatalogService.class, NavigationService.class, OnlineTransactionBoundary.class,
        SignOnStateService.class, SecurityConfig.class, JwtTokenProvider.class, WebMvcConfig.class})
    @EnableConfigurationProperties(JwtProperties.class)
    @EnableJpaRepositories(basePackageClasses = TransactionRepository.class)
    @EntityScan(basePackageClasses = Transaction.class)
    static class TransactionScreensContext {

        /** Creates the slice. */
        TransactionScreensContext() {
            // Intentionally empty: this slice contributes beans, not state.
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

    /**
     * Matches one sealed envelope literal, marker and Base64 body together, including its quotes.
     *
     * <p>The character class is the standard Base64 alphabet plus its padding, which is the whole of what
     * the codec emits, so the match ends at the closing quote of the value and reaches no further.
     */
    private static final Pattern SEALED_VALUE = Pattern.compile("\"ENC1:[A-Za-z0-9+/=]*\"");

    /**
     * Returns one response body with every sealed opaque value replaced by a fixed placeholder.
     *
     * <p><strong>Why a scan of a raw body needs this.</strong> Several assertions in this class read the
     * whole body as text and require that a short literal does not appear anywhere in it - a
     * transaction-manager prefix, a terminal colour name, an exponent marker. That is the right question
     * to ask of text the application composed. It is the wrong question to ask of a page token, whose
     * value is Base64 over a fresh initialisation vector and an authentication tag, so its characters are
     * drawn from {@code A-Za-z0-9+/=} at random on every call. A two-character literal made of those
     * characters therefore appears inside a token roughly two and a half times in a hundred, and a
     * three-character one about once in two thousand five hundred: not never, and not reproducibly.
     *
     * <p>Scanning the raw body was sound while no body carried ciphertext, and it produced exactly the
     * failure this predicts once one did - {@code "E+"} matched inside {@code ...aUCzE+Kjh...}. Eliding
     * the sealed value asks the intended question of the intended subject and removes the dependence on
     * random bytes altogether, rather than weakening any assertion: every literal each caller forbids is
     * still forbidden everywhere the application's own text appears.
     *
     * @param  body the response body as transmitted
     * @return the same text with each sealed value replaced by a placeholder carrying none of its bytes
     */
    private static String applicationTextOf(final String body) {
        return SEALED_VALUE.matcher(body).replaceAll("\"<sealed>\"");
    }

}
