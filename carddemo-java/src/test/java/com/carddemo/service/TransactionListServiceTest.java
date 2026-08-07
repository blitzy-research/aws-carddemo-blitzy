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
package com.carddemo.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.domain.Limit;

import com.carddemo.domain.Transaction;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.repository.TransactionScanRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TransactionListService}, the Java translation of legacy CICS transaction
 * {@code CT00} whose sole implementing member is {@code app/cbl/COTRN00C.cbl} - 699 lines and
 * <strong>16 {@code PROCEDURE DIVISION} paragraphs</strong>. Provenance: the legacy estate is
 * read-only reference at commit SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream
 * release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is
 * transcribed here; behaviour is cited by paragraph name and line number only.
 *
 * <h2>The contract this class exists to defend</h2>
 *
 * <p><strong>A backward page is read descending and presented ascending, so ordering - not
 * membership - is the contract.</strong> The backward paragraph at line 333 seeds its row index with
 * <em>ten</em> at line 349 and <em>decrements</em> it through the fill loop at lines 351-357, so the
 * first record read lands in the bottom slot and the last read lands in the top one. A translation
 * that returned the descending read order unchanged would present a backward page upside down
 * relative to the legacy screen and would still pass every membership assertion ever written against
 * it. The decisive test therefore pages forward, forward, then backward and requires the third result
 * to be <em>ordered-equal</em> to the first. Every ordering assertion in this class is made with an
 * exact-sequence comparison; an order-insensitive containment check and a set comparison are both
 * unusable for this contract and neither is used anywhere below.
 *
 * <p><strong>The page is ten rows and the figure comes from loop bounds alone.</strong> Uniquely
 * among the estate's three paginated screens this member declares no occurrence table for its screen
 * rows: the blanking loop at line 290 varies an index from one until it exceeds ten, the index is
 * reset to one at line 295, and the fill loop at line 297 runs until the index reaches eleven. Ten is
 * consequently a legacy behavioural contract, never a tuning parameter, and it is deliberately not
 * shared with the card list's seven or the administrative user list's ten - the equality with the
 * latter is an accident of two unrelated layouts.
 *
 * <h2>The oracle is independent of the code it judges</h2>
 *
 * <p>Every expected identifier, message, padding width, timestamp, amount and route token below is a
 * literal declared in this class. No expected value is produced by calling the service under test, the
 * message catalogue, the navigation service, the decimal codec, a template class or a record mapper.
 * The ordered identifier lists are written out in full, fixed-width padding is written as an explicit
 * repeat count so a reviewer can count it, amounts are built from decimal string literals, and the two
 * timestamp forms are written character for character.
 *
 * <h2>What this class deliberately does not touch</h2>
 *
 * <p>There is <strong>no abend service and no attention-key translator</strong> here, because there is
 * none in the production collaborator set: this member is not one of the five programs that include the
 * attention-key copybook, so it decodes its key inline and folds nothing. Neither type is mocked,
 * imported or referenced.
 *
 * <p>The two twenty-six-character timestamp forms in the estate are <strong>never unified</strong>. The
 * online form carries a space before the hour, colons between the time parts and an all-zero fraction;
 * the batch form carries a hyphen before the hour, dots between the time parts and two hundredths digits
 * followed by four literal zeros. Both are asserted to cross this service byte for byte and to remain
 * unequal to one another. For the same reason the posted record's <strong>unprefixed</strong> merchant
 * property names are asserted as such and are never conflated with the prefixed names of the
 * byte-identical daily-transaction record.
 *
 * <h2>Paragraph traceability - all 16 units</h2>
 *
 * <ul>
 *   <li>{@code MAIN-PARA} line 95 - {@code AttentionKeyDispatch}, {@code Routing},
 *       {@code NullAndBoundaryInput}</li>
 *   <li>{@code PROCESS-ENTER-KEY} line 146 - {@code SelectionAndFilter}</li>
 *   <li>{@code PROCESS-PF7-KEY} line 234 - {@code AttentionKeyGuards}, {@code BackwardPagingOrder}</li>
 *   <li>{@code PROCESS-PF8-KEY} line 257 - {@code AttentionKeyGuards}, {@code PageMetadataFidelity}</li>
 *   <li>{@code PROCESS-PAGE-FORWARD} line 279 - {@code PageAssembly}</li>
 *   <li>{@code PROCESS-PAGE-BACKWARD} line 333 - {@code BackwardPagingOrder}</li>
 *   <li>{@code POPULATE-TRAN-DATA} line 381 - {@code PageAssembly}, {@code FieldFidelity},
 *       {@code PageMetadataFidelity}</li>
 *   <li>{@code INITIALIZE-TRAN-DATA} line 450 - {@code PageAssembly}</li>
 *   <li>{@code RETURN-TO-PREV-SCREEN} line 510 - {@code Routing}</li>
 *   <li>{@code SEND-TRNLST-SCREEN} line 527 - {@code ScreenLifecycleAndFailureArms}</li>
 *   <li>{@code RECEIVE-TRNLST-SCREEN} line 554 - {@code SelectionAndFilter}</li>
 *   <li>{@code POPULATE-HEADER-INFO} line 567 - {@code ScreenLifecycleAndFailureArms}</li>
 *   <li>{@code STARTBR-TRANSACT-FILE} line 591 - {@code PageAssembly},
 *       {@code ScreenLifecycleAndFailureArms}</li>
 *   <li>{@code READNEXT-TRANSACT-FILE} line 624 - {@code PageAssembly},
 *       {@code ScreenLifecycleAndFailureArms}</li>
 *   <li>{@code READPREV-TRANSACT-FILE} line 658 - {@code BackwardPagingOrder},
 *       {@code ScreenLifecycleAndFailureArms}</li>
 *   <li>{@code ENDBR-TRANSACT-FILE} line 692 - {@code RepositoryOwnership}</li>
 * </ul>
 *
 * <h2>Harness</h2>
 *
 * <p>A surefire unit test: no container, no application context, no database connection, no port and no
 * network. Every collaborator is a Mockito mock and the clock is fixed, so the screen header is
 * deterministic. Strict stubbing is in force; the ordered-cluster emulation used by the multi-turn tests
 * is registered leniently because a single turn legitimately exercises only some of the four bounded
 * reads, and every targeted stub is registered strictly.
 *
 * @see TransactionListService
 * @see TransactionScanRepository
 * @since 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionListService: the CT00 browse, read descending and presented ascending")
final class TransactionListServiceTest {

    // ------------------------------------------------------------------------------------------
    // Independent oracle: identifiers
    // ------------------------------------------------------------------------------------------

    /**
     * The twenty-five fixture identifiers, written out as the sixteen zero-padded digit characters the
     * column stores rather than produced by a formatter, so the leading zeros are visible to a reviewer
     * and the ordering the store applies is the ordering a reader can check by eye.
     */
    private static final String ID_01 = "0000000000000001";

    private static final String ID_02 = "0000000000000002";

    private static final String ID_03 = "0000000000000003";

    private static final String ID_04 = "0000000000000004";

    private static final String ID_05 = "0000000000000005";

    private static final String ID_06 = "0000000000000006";

    private static final String ID_07 = "0000000000000007";

    private static final String ID_08 = "0000000000000008";

    private static final String ID_09 = "0000000000000009";

    private static final String ID_10 = "0000000000000010";

    private static final String ID_11 = "0000000000000011";

    private static final String ID_12 = "0000000000000012";

    private static final String ID_13 = "0000000000000013";

    private static final String ID_14 = "0000000000000014";

    private static final String ID_15 = "0000000000000015";

    private static final String ID_16 = "0000000000000016";

    private static final String ID_17 = "0000000000000017";

    private static final String ID_18 = "0000000000000018";

    private static final String ID_19 = "0000000000000019";

    private static final String ID_20 = "0000000000000020";

    private static final String ID_21 = "0000000000000021";

    private static final String ID_22 = "0000000000000022";

    private static final String ID_23 = "0000000000000023";

    private static final String ID_24 = "0000000000000024";

    private static final String ID_25 = "0000000000000025";

    /** An identifier above every stored one, so a browse cannot be positioned on it. */
    private static final String ID_ABOVE_EVERY_STORED_KEY = "9999999999999999";

    /**
     * The first page in presentation order. Written out rather than sliced from a generated list,
     * because this exact ordered list is what the backward page must reproduce.
     */
    private static final List<String> EXPECTED_FIRST_PAGE = List.of(
            ID_01, ID_02, ID_03, ID_04, ID_05, ID_06, ID_07, ID_08, ID_09, ID_10);

    /** The second page in presentation order, on the same terms. */
    private static final List<String> EXPECTED_SECOND_PAGE = List.of(
            ID_11, ID_12, ID_13, ID_14, ID_15, ID_16, ID_17, ID_18, ID_19, ID_20);

    /**
     * The order a backward read hands rows back in: the descending sequence the legacy walks with its
     * backward read verb. Declared explicitly so that the stub which feeds the decisive test is visibly
     * descending, and the ascending result therefore visibly a reversal.
     */
    private static final List<String> BACKWARD_READ_ORDER = List.of(
            ID_11, ID_10, ID_09, ID_08, ID_07, ID_06, ID_05, ID_04, ID_03, ID_02, ID_01);

    /**
     * The order a forward read hands rows back in when the browse is repositioned on the tenth key: the
     * ascending sequence beginning with the positioned record itself, which the forward guard discards.
     */
    private static final List<String> FORWARD_READ_ORDER_FROM_TEN = List.of(
            ID_10, ID_11, ID_12, ID_13, ID_14, ID_15, ID_16, ID_17, ID_18, ID_19, ID_20);

    /** The ten screen slot indices a full page occupies, in presentation order. */
    private static final List<Integer> EXPECTED_FULL_PAGE_SLOTS =
            List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

    // ------------------------------------------------------------------------------------------
    // Independent oracle: contract widths and screen values
    // ------------------------------------------------------------------------------------------

    /**
     * Rows a page carries. Asserted against the service's behaviour rather than imported from it, and
     * deliberately declared here rather than shared with the card-list or user-list figure.
     */
    private static final int EXPECTED_PAGE_SIZE = 10;

    /**
     * Rows one bounded read asks the store for: a page plus the single further read the legacy itself
     * performs to discover whether another page follows, at line 305 forward and line 360 backward. It is
     * a consequence of the paragraph's own read count and not a fetch-tuning figure.
     */
    private static final int EXPECTED_READ_WINDOW_ROWS = EXPECTED_PAGE_SIZE + 1;

    /** Rows the single-row resolution read asks for when a backward browse must find the low end. */
    private static final int LOW_END_RESOLUTION_ROWS = 1;

    /** Declared width of a common message field, in characters. */
    private static final int COMMON_MESSAGE_WIDTH = 50;

    /** Declared width of a stored timestamp, in characters. */
    private static final int TIMESTAMP_WIDTH = 26;

    /** Declared width of the source code field, in characters. */
    private static final int SOURCE_WIDTH = 10;

    /** Scale of every stored monetary amount. */
    private static final int MONETARY_SCALE = 2;

    /** The bound a browse of the low end of the cluster declares: blank, below every stored key. */
    private static final String LOW_END_BOUND = "";

    /** The screen field this member places the cursor on, and on nothing else. */
    private static final String EXPECTED_FOCUS_FIELD = "TRNIDIN";

    /** Property name a field error on the filter reports. */
    private static final String EXPECTED_FILTER_PROPERTY = "transactionIdFilter";

    /** Property name a field error on a row selector reports. */
    private static final String EXPECTED_SELECTOR_PROPERTY = "rowSelectors";

    /** Screen field name of the first row's selector. */
    private static final String EXPECTED_FIRST_SELECTOR_FIELD = "SEL0001";

    /** Screen field name of the third row's selector. */
    private static final String EXPECTED_THIRD_SELECTOR_FIELD = "SEL0003";

    /** This screen's own legacy transaction identifier, stamped into the header at line 573. */
    private static final String EXPECTED_TRANSACTION_NAME = "CT00";

    /** This screen's own legacy program name, stamped into the header at line 574. */
    private static final String EXPECTED_PROGRAM_NAME = "COTRN00C";

    /** Legacy program name a valid row selection nominates at line 188. */
    private static final String EXPECTED_VIEW_PROGRAM = "COTRN01C";

    /** Legacy program name the third program function key nominates at line 123. */
    private static final String EXPECTED_USER_MENU_PROGRAM = "COMEN01C";

    /** Legacy program name a turn carrying no navigation state nominates at line 108. */
    private static final String EXPECTED_SIGN_ON_PROGRAM = "COSGN00C";

    /** Wire token of this screen's own route, which a turn staying on the list returns. */
    private static final String EXPECTED_LIST_ROUTE_VALUE = "transaction-list";

    /** Wire token of the transaction-view route. */
    private static final String EXPECTED_VIEW_ROUTE_VALUE = "transaction-view";

    /** Wire token of the user main menu route. */
    private static final String EXPECTED_USER_MENU_ROUTE_VALUE = "user-menu";

    /** Wire token of the sign-on route. */
    private static final String EXPECTED_SIGN_ON_ROUTE_VALUE = "sign-on";

    /** Fixed stand-in every carried shape emits in place of a record key. */
    private static final String EXPECTED_REDACTION = "***REDACTED***";

    // ------------------------------------------------------------------------------------------
    // Independent oracle: message texts, reproduced verbatim
    // ------------------------------------------------------------------------------------------

    /** Visible portion of the common catalogue's invalid-key text, before its fixed-width padding. */
    private static final String INVALID_KEY_VISIBLE = "Invalid key pressed. Please see below...";

    /**
     * The common catalogue's invalid-key text at its declared fifty-character width: forty characters of
     * visible text followed by <strong>ten</strong> trailing spaces, written as an explicit repeat count.
     * The trailing spaces are part of the screen contract and are never trimmed.
     */
    private static final String EXPECTED_INVALID_KEY_MESSAGE =
            INVALID_KEY_VISIBLE + " ".repeat(10);

    /** Visible portion of the catalogue's other common message, before its fixed-width padding. */
    private static final String THANK_YOU_VISIBLE = "Thank you for using CardDemo application...";

    /**
     * The other common message the catalogue carries at the same width: forty-three visible characters
     * followed by <strong>seven</strong> trailing spaces. Declared so that the two padding counts are
     * stated side by side and neither can be quietly turned into the other, and used to prove that this
     * service passes a catalogue value through whatever its padding count happens to be.
     */
    private static final String EXPECTED_THANK_YOU_MESSAGE = THANK_YOU_VISIBLE + " ".repeat(7);

    /** Message for a row selected with anything other than the view action, from lines 198-200. */
    private static final String EXPECTED_INVALID_SELECTION = "Invalid selection. Valid value is S";

    /** Message for a filter that fails the numeric class test, from lines 213-215. */
    private static final String EXPECTED_NOT_NUMERIC = "Tran ID must be Numeric ...";

    /** Message for a backward request already on the first page, from lines 248-249. */
    private static final String EXPECTED_ALREADY_AT_TOP = "You are already at the top of the page...";

    /** Message for a forward request already on the last page, from lines 270-271. */
    private static final String EXPECTED_ALREADY_AT_BOTTOM =
            "You are already at the bottom of the page...";

    /** Message for a browse that could not be positioned at all, from lines 608-609. */
    private static final String EXPECTED_AT_TOP = "You are at the top of the page...";

    /** Message for a forward read that reached the end of the cluster, from lines 642-643. */
    private static final String EXPECTED_REACHED_BOTTOM =
            "You have reached the bottom of the page...";

    /** Message for a backward read that reached the start of the cluster, from lines 676-677. */
    private static final String EXPECTED_REACHED_TOP = "You have reached the top of the page...";

    /** Message all three browse paragraphs emit on their catch-all arm, from lines 615, 649 and 683. */
    private static final String EXPECTED_UNABLE_TO_LOOKUP = "Unable to lookup transaction...";

    /** The blank a screen field holds when nothing is displayed in it. */
    private static final String BLANK = "";

    // ------------------------------------------------------------------------------------------
    // Independent oracle: record field values
    // ------------------------------------------------------------------------------------------

    /** Visible portion of the source code, before its fixed-width padding. */
    private static final String SOURCE_VISIBLE = "POS TERM";

    /**
     * The source code at its stored ten-character width, trailing spaces included and written as an
     * explicit repeat count. Never trimmed and never mapped onto an enumerated type at this layer.
     */
    private static final String PADDED_SOURCE = SOURCE_VISIBLE + " ".repeat(2);

    /** Visible portion of the operator-originated source code, whose returns carry negative amounts. */
    private static final String RETURN_SOURCE_VISIBLE = "OPERATOR";

    /** That source code at its stored ten-character width. */
    private static final String PADDED_RETURN_SOURCE = RETURN_SOURCE_VISIBLE + " ".repeat(2);

    /** A description at its stored width, wider than the column the screen shows. */
    private static final String DESCRIPTION = "PURCHASE AT A MERCHANT OF SOME KIND";

    /** The two-character transaction type code. */
    private static final String TYPE_CODE = "01";

    /** The four-character transaction category code, whose leading zero is contractual. */
    private static final String CATEGORY_CODE = "0005";

    /** The nine-digit merchant identifier, read through this record's unprefixed property. */
    private static final String MERCHANT_ID = "000000123";

    /** The merchant name, read through this record's unprefixed property. */
    private static final String MERCHANT_NAME = "A MERCHANT OF SOME KIND";

    /** The merchant city, read through this record's unprefixed property. */
    private static final String MERCHANT_CITY = "SPRINGFIELD";

    /** The merchant postal code, read through this record's unprefixed property. */
    private static final String MERCHANT_ZIP = "62704-0001";

    /** The sixteen-character card number the fixture rows were made on. */
    private static final String CARD_NUMBER = "4111111111111111";

    /**
     * The <strong>online</strong> twenty-six-character timestamp form: a space before the hour, colons
     * between the time parts, and a fraction that is invariably all zeros. Written character for
     * character and never derived from a formatter.
     */
    private static final String ONLINE_TIMESTAMP = "2022-07-19 23:12:34.000000";

    /**
     * The <strong>batch</strong> twenty-six-character timestamp form: a hyphen before the hour, dots
     * between the time parts, and two hundredths digits followed by four literal zeros. It is a
     * different external contract from the online form and the two are never unified - no shared
     * top-level timestamp formatter exists in this module, by design.
     */
    private static final String BATCH_TIMESTAMP = "2022-07-06-14.23.41.870000";

    /** A stored amount at the column's own scale of two. */
    private static final BigDecimal AMOUNT_AT_SCALE_TWO = new BigDecimal("123.45");

    /**
     * An amount a rounding step would alter: truncating toward zero yields {@code 123.45} and rounding
     * half up yields {@code 123.46}, so a service that rescaled at all could not return this value
     * unchanged. Used to prove that this class performs no scaling of its own.
     */
    private static final BigDecimal AMOUNT_A_ROUNDING_STEP_WOULD_ALTER = new BigDecimal("123.455");

    /** A negative amount, the shape an operator-originated return carries. */
    private static final BigDecimal NEGATIVE_AMOUNT = new BigDecimal("-45.67");

    /** Screen header date the fixed instant must render to, written independently of the service. */
    private static final String EXPECTED_HEADER_DATE = "07/19/22";

    /** Screen header time the same instant must render to. */
    private static final String EXPECTED_HEADER_TIME = "23:12:34";

    /** First screen title as the catalogue is stubbed to supply it. */
    private static final String STUBBED_SCREEN_TITLE_01 = "AWS Mainframe Modernization";

    /** Second screen title as the catalogue is stubbed to supply it. */
    private static final String STUBBED_SCREEN_TITLE_02 = "CardDemo";

    /** Displayed page indicator for the first page: eight zero-filled digits. */
    private static final String PAGE_INDICATOR_ONE = "00000001";

    /** Displayed page indicator for the second page. */
    private static final String PAGE_INDICATOR_TWO = "00000002";

    /** Displayed page indicator for the third page. */
    private static final String PAGE_INDICATOR_THREE = "00000003";

    /** Displayed page indicator for a turn that never settled a page at all. */
    private static final String PAGE_INDICATOR_ZERO = "00000000";

    /**
     * Instant the screen header is rendered from: the stamp instant of the legacy member, fixed so the
     * header is deterministic without any tolerance in the assertion.
     */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2022-07-19T23:12:34Z"), ZoneOffset.UTC);

    // ------------------------------------------------------------------------------------------
    // Collaborators
    // ------------------------------------------------------------------------------------------

    /**
     * The bounded ordered-read view the browse walks. Its four reads are the whole persistence surface
     * this service has.
     */
    @Mock
    private TransactionScanRepository transactionScanRepository;

    /** The common message and screen title catalogue. */
    @Mock
    private MessageCatalogService messageCatalogService;

    /** The route resolver every transfer this screen performs consults. */
    @Mock
    private NavigationService navigationService;

    /**
     * The transaction master's own repository, mocked and <strong>deliberately not wired into the
     * service</strong>.
     *
     * <p>Its highest-identifier query belongs to the bill-payment screen exclusively, and it declares no
     * date-range query at all - the reporting job serves its inclusive window through its own bounded
     * reader. Holding the mock here lets the ownership negative be asserted directly rather than argued:
     * the browse cannot reach either surface, and the assertion says so.
     */
    @Mock
    private TransactionRepository transactionRepository;

    private TransactionListService service;

    @BeforeEach
    void constructServiceOverMockedCollaborators() {
        this.service = new TransactionListService(this.transactionScanRepository,
                this.messageCatalogService, this.navigationService, FIXED_CLOCK);
    }

    // ------------------------------------------------------------------------------------------
    // Fixtures: records
    // ------------------------------------------------------------------------------------------

    /**
     * Builds one posted transaction directly through the entity's own all-arguments constructor, which
     * assigns every value verbatim.
     *
     * <p>The entity is constructed here rather than through the shared test data builder on purpose: that
     * builder routes an amount through the module's scaling helper, which would rescale a fixture before
     * the service ever saw it and would silently defeat the assertion that this service performs no
     * scaling of its own.
     *
     * @param identifier the sixteen-character business key
     * @return a transaction carrying the standard fixture values
     */
    private static Transaction transaction(final String identifier) {
        return transaction(identifier, PADDED_SOURCE, AMOUNT_AT_SCALE_TWO, ONLINE_TIMESTAMP,
                ONLINE_TIMESTAMP);
    }

    /**
     * Builds one posted transaction with the field values a fidelity assertion needs to control.
     *
     * @param identifier          the sixteen-character business key
     * @param source              the source code at its stored ten-character width
     * @param amount              the amount, stored exactly as supplied
     * @param originationTimestamp the twenty-six-character origination timestamp
     * @param processingTimestamp  the twenty-six-character processing timestamp
     * @return a transaction carrying those values and the standard fixture values elsewhere
     */
    private static Transaction transaction(final String identifier, final String source,
            final BigDecimal amount, final String originationTimestamp,
            final String processingTimestamp) {
        return new Transaction(
                identifier,
                TYPE_CODE,
                CATEGORY_CODE,
                source,
                DESCRIPTION,
                amount,
                MERCHANT_ID,
                MERCHANT_NAME,
                MERCHANT_CITY,
                MERCHANT_ZIP,
                CARD_NUMBER,
                originationTimestamp,
                processingTimestamp);
    }

    /**
     * The twenty-five-row cluster the multi-turn tests walk, in ascending key order.
     *
     * @return twenty-five transactions whose identifiers are the declared literals
     */
    private static List<Transaction> twentyFiveRowCluster() {
        return List.of(
                transaction(ID_01), transaction(ID_02), transaction(ID_03), transaction(ID_04),
                transaction(ID_05), transaction(ID_06), transaction(ID_07), transaction(ID_08),
                transaction(ID_09), transaction(ID_10), transaction(ID_11), transaction(ID_12),
                transaction(ID_13), transaction(ID_14), transaction(ID_15), transaction(ID_16),
                transaction(ID_17), transaction(ID_18), transaction(ID_19), transaction(ID_20),
                transaction(ID_21), transaction(ID_22), transaction(ID_23), transaction(ID_24),
                transaction(ID_25));
    }

    /**
     * A cluster of exactly four rows, so a page that cannot fill can be observed.
     *
     * @return four transactions in ascending key order
     */
    private static List<Transaction> fourRowCluster() {
        return List.of(transaction(ID_01), transaction(ID_02), transaction(ID_03),
                transaction(ID_04));
    }

    /**
     * Renders the given identifiers as transactions, in the order supplied, so an explicitly ordered
     * stub response can be declared from an explicitly ordered identifier list.
     *
     * @param identifiers the identifiers, in the order the response must carry them
     * @return one transaction per identifier, in the same order
     */
    private static List<Transaction> rowsFor(final List<String> identifiers) {
        final List<Transaction> rows = new ArrayList<>(identifiers.size());
        for (final String identifier : identifiers) {
            rows.add(transaction(identifier));
        }
        return List.copyOf(rows);
    }

    // ------------------------------------------------------------------------------------------
    // Fixtures: the ordered cluster the four bounded reads are stubbed over
    // ------------------------------------------------------------------------------------------

    /**
     * Registers all four bounded reads over an in-memory cluster that honours the bound, the
     * inclusiveness, the direction and the limit exactly as the four derived query names declare them.
     *
     * <p>Getting the emulation right is what gives the assertions their force. A stub that read
     * inclusively where the contract is strict would hide a boundary row delivered twice, and one that
     * ordered ascending where the contract is descending would hide a backward page presented in the
     * wrong sequence - the single defect these tests exist to catch.
     *
     * <p>Registered leniently because one turn legitimately exercises only some of the four reads: a
     * first forward turn issues the inclusive ascending read alone, and a backward turn issues neither
     * ascending read. Strict stubbing remains in force for every targeted stub elsewhere in this class.
     *
     * @param ascendingRows the cluster's rows; order of the argument is irrelevant because the emulation
     *                      sorts before applying the bound
     */
    private void stubOrderedCluster(final List<Transaction> ascendingRows) {
        Mockito.lenient()
                .when(this.transactionScanRepository
                        .findByTranIdGreaterThanEqualOrderByTranIdAsc(any(), any()))
                .thenAnswer(invocation -> orderedWindow(ascendingRows,
                        invocation.getArgument(0, String.class),
                        invocation.getArgument(1, Limit.class), true, true));
        Mockito.lenient()
                .when(this.transactionScanRepository
                        .findByTranIdGreaterThanOrderByTranIdAsc(any(), any()))
                .thenAnswer(invocation -> orderedWindow(ascendingRows,
                        invocation.getArgument(0, String.class),
                        invocation.getArgument(1, Limit.class), true, false));
        Mockito.lenient()
                .when(this.transactionScanRepository
                        .findByTranIdLessThanEqualOrderByTranIdDesc(any(), any()))
                .thenAnswer(invocation -> orderedWindow(ascendingRows,
                        invocation.getArgument(0, String.class),
                        invocation.getArgument(1, Limit.class), false, true));
        Mockito.lenient()
                .when(this.transactionScanRepository
                        .findByTranIdLessThanOrderByTranIdDesc(any(), any()))
                .thenAnswer(invocation -> orderedWindow(ascendingRows,
                        invocation.getArgument(0, String.class),
                        invocation.getArgument(1, Limit.class), false, false));
    }

    /**
     * Registers the single inclusive ascending read a browse of the low end of the cluster issues, with an
     * explicitly ordered response.
     *
     * <p>Registered strictly rather than leniently, because a turn that browses the low end and fits
     * inside one window issues exactly this read and no other: the response carries fewer rows than the
     * window asked for, which is how the store says the sequence has ended, so no continuation read
     * follows and no other stub could be realised.
     *
     * @param rows the rows the read returns, in the order it returns them
     */
    private void stubLowEndPage(final List<Transaction> rows) {
        when(this.transactionScanRepository
                .findByTranIdGreaterThanEqualOrderByTranIdAsc(eq(LOW_END_BOUND), any()))
                .thenReturn(rows);
    }

    /**
     * Returns one bounded ordered window, applying the direction, the key bound and the limit the way the
     * store would.
     *
     * @param clusterRows the cluster's rows
     * @param bound       the key bound the read declared
     * @param limit       the row limit the read declared
     * @param ascending   whether the read orders the key sequence upward
     * @param inclusive   whether the bound itself qualifies, which is the difference between the
     *                    positioning read and a continuation read
     * @return the qualifying rows, ordered as the read declared and truncated to its limit
     */
    private static List<Transaction> orderedWindow(final List<Transaction> clusterRows,
            final String bound, final Limit limit, final boolean ascending,
            final boolean inclusive) {
        final List<Transaction> ordered = new ArrayList<>(clusterRows);
        ordered.sort(Comparator.comparing(Transaction::getTranId));
        if (!ascending) {
            Collections.reverse(ordered);
        }
        final List<Transaction> selected = new ArrayList<>(limit.max());
        for (final Transaction candidate : ordered) {
            if (selected.size() >= limit.max()) {
                break;
            }
            if (qualifies(candidate.getTranId(), bound, ascending, inclusive)) {
                selected.add(candidate);
            }
        }
        return List.copyOf(selected);
    }

    /**
     * Applies one read's key bound to one row, comparing as characters because the stored identifiers are
     * zero-padded digits and character order therefore coincides with numeric order.
     *
     * @param key       the row's identifier
     * @param bound     the bound the read declared
     * @param ascending whether the read orders upward
     * @param inclusive whether the bound itself qualifies
     * @return {@code true} when the row qualifies for the read
     */
    private static boolean qualifies(final String key, final String bound, final boolean ascending,
            final boolean inclusive) {
        final int comparison = key.compareTo(bound);
        if (ascending) {
            return inclusive ? comparison >= 0 : comparison > 0;
        }
        return inclusive ? comparison <= 0 : comparison < 0;
    }

    // ------------------------------------------------------------------------------------------
    // Fixtures: commands
    // ------------------------------------------------------------------------------------------

    /**
     * A first entry: a navigation state that is present but has not yet been re-entered, arriving on the
     * enter key with nothing submitted.
     *
     * @return the command a first entry into this screen carries
     */
    private static TransactionListService.TransactionListCommand firstEntry() {
        return firstEntryWith(null, List.of(), List.of());
    }

    /**
     * A first entry that nonetheless submits screen fields, so the re-entry gate can be observed
     * discarding them: the map is never received on this path, and the output group redefines the input
     * group, so line 114 blanks both.
     *
     * @param filter       the filter field the caller submitted
     * @param selectors    the row selectors the caller submitted
     * @param displayedIds the row identifiers the caller echoed
     * @return the command
     */
    private static TransactionListService.TransactionListCommand firstEntryWith(final String filter,
            final List<String> selectors, final List<String> displayedIds) {
        return new TransactionListService.TransactionListCommand(
                KeyAction.ENTER,
                ScreenNavigationState.empty().withFirstEntry(),
                filter,
                selectors,
                displayedIds,
                null,
                false,
                0);
    }

    /**
     * A re-entry carrying an attention key and the paging state a previous response reported.
     *
     * @param keyAction         the attention key
     * @param previousCursorKey the first boundary key the previous page retained
     * @param nextCursorKey     the last boundary key the previous page retained
     * @param nextPageAvailable whether the previous turn found a page beyond the one it displayed
     * @param currentPageNumber the page number the previous turn settled on
     * @return the command
     */
    private static TransactionListService.TransactionListCommand reEntry(final KeyAction keyAction,
            final String previousCursorKey, final String nextCursorKey,
            final boolean nextPageAvailable, final int currentPageNumber) {
        return new TransactionListService.TransactionListCommand(
                keyAction,
                ScreenNavigationState.empty().withReEntry(),
                null,
                List.of(),
                List.of(),
                new BrowseWindow.CursorRequest(previousCursorKey, nextCursorKey, null),
                nextPageAvailable,
                currentPageNumber);
    }

    /**
     * A re-entry on the enter key carrying submitted screen fields.
     *
     * @param filter       the filter field
     * @param selectors    the row selectors
     * @param displayedIds the echoed row identifiers
     * @return the command
     */
    private static TransactionListService.TransactionListCommand submittedEnter(final String filter,
            final List<String> selectors, final List<String> displayedIds) {
        return new TransactionListService.TransactionListCommand(
                KeyAction.ENTER,
                ScreenNavigationState.empty().withReEntry(),
                filter,
                selectors,
                displayedIds,
                null,
                false,
                0);
    }

    /**
     * Builds the next command from the window a previous turn reported, so a multi-turn walk echoes
     * exactly what the client would echo.
     *
     * @param keyAction the attention key for the next turn
     * @param reported  the window the previous turn reported
     * @return the command for the next turn
     */
    private static TransactionListService.TransactionListCommand nextTurn(final KeyAction keyAction,
            final BrowseWindow reported) {
        return new TransactionListService.TransactionListCommand(
                keyAction,
                ScreenNavigationState.empty().withReEntry(),
                null,
                List.of(),
                List.of(),
                new BrowseWindow.CursorRequest(reported.previousCursorKey(),
                        reported.nextCursorKey(), reported.direction()),
                reported.hasMorePages(),
                Integer.parseInt(reported.displayedPageNumber()));
    }

    // ------------------------------------------------------------------------------------------
    // Fixtures: projections of a result
    // ------------------------------------------------------------------------------------------

    /**
     * Extracts a result's row identifiers <strong>in presentation order</strong>, which is the value every
     * ordering assertion in this class is made against.
     *
     * @param result the result
     * @return the identifiers, in the order the page presents them
     */
    private static List<String> identifiersOf(
            final TransactionListService.TransactionListResult result) {
        final List<String> identifiers = new ArrayList<>(result.rows().size());
        for (final TransactionListService.TransactionListRow row : result.rows()) {
            identifiers.add(row.tranId());
        }
        return List.copyOf(identifiers);
    }

    /**
     * Extracts a result's screen slot indices in presentation order, which is what makes the backward
     * fill from the bottom slot upward observable.
     *
     * @param result the result
     * @return the slot indices, in presentation order
     */
    private static List<Integer> slotsOf(
            final TransactionListService.TransactionListResult result) {
        final List<Integer> slots = new ArrayList<>(result.rows().size());
        for (final TransactionListService.TransactionListRow row : result.rows()) {
            slots.add(row.screenRow());
        }
        return List.copyOf(slots);
    }

    /**
     * Measures a fixed-width value on its <strong>encoded bytes</strong> rather than on its character
     * count, because a record width is a byte contract.
     *
     * @param value the value
     * @return the number of bytes the value encodes to
     */
    private static int encodedByteLength(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    // ------------------------------------------------------------------------------------------
    // Paragraphs PROCESS-PAGE-FORWARD line 279, POPULATE-TRAN-DATA line 381,
    // INITIALIZE-TRAN-DATA line 450, STARTBR line 591, READNEXT line 624
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Page assembly: ten rows from loop bounds alone, and never padded")
    final class PageAssembly {

        @Test
        @DisplayName("a full page holds exactly ten rows, in ascending slot order, filled from slot one"
                + " upward as the forward fill loop of lines 297-303 does")
        void aFullPageHoldsExactlyTenRowsInAscendingSlotOrder() {
            stubOrderedCluster(twentyFiveRowCluster());

            final TransactionListService.TransactionListResult result =
                    service.listTransactions(firstEntry());

            assertAll(
                    () -> assertThat(result.rows()).hasSize(EXPECTED_PAGE_SIZE),
                    () -> assertThat(result.rows()).doesNotContainNull(),
                    () -> assertThat(identifiersOf(result))
                            .containsExactlyElementsOf(EXPECTED_FIRST_PAGE),
                    () -> assertThat(slotsOf(result))
                            .containsExactlyElementsOf(EXPECTED_FULL_PAGE_SLOTS),
                    () -> assertThat(result.pageMetadata().pageSize()).isEqualTo(EXPECTED_PAGE_SIZE),
                    () -> assertThat(result.pageMetadata().displayedPageNumber())
                            .isEqualTo(PAGE_INDICATOR_ONE),
                    () -> assertThat(result.pageMetadata().hasMorePages()).isTrue(),
                    () -> assertThat(result.pageMetadata().hasPreviousPages()).isFalse(),
                    () -> assertThat(result.error()).isFalse(),
                    () -> assertThat(result.message()).isEqualTo(BLANK),
                    () -> assertThat(result.eraseScreen()).isTrue(),
                    () -> assertThat(result.fieldErrors()).isEmpty(),
                    () -> assertThat(result.focusScreenFieldId()).isEqualTo(EXPECTED_FOCUS_FIELD),
                    () -> assertThat(result.transactionName()).isEqualTo(EXPECTED_TRANSACTION_NAME),
                    () -> assertThat(result.programName()).isEqualTo(EXPECTED_PROGRAM_NAME));
        }

        @Test
        @DisplayName("a page of four rows carries four rows with no blank filler and no null entry,"
                + " because an unfilled slot contributes nothing")
        void aPartialPageHoldsFourRowsWithNoPaddingAndNoNullEntries() {
            stubOrderedCluster(fourRowCluster());

            final TransactionListService.TransactionListResult result =
                    service.listTransactions(firstEntry());

            assertAll(
                    () -> assertThat(result.rows()).hasSize(4),
                    () -> assertThat(result.rows()).doesNotContainNull(),
                    () -> assertThat(identifiersOf(result))
                            .containsExactly(ID_01, ID_02, ID_03, ID_04),
                    () -> assertThat(slotsOf(result)).containsExactly(1, 2, 3, 4),
                    () -> assertThat(result.pageMetadata().hasMorePages()).isFalse(),
                    () -> assertThat(result.pageMetadata().displayedPageNumber())
                            .isEqualTo(PAGE_INDICATOR_ONE),
                    () -> assertThat(result.message()).isEqualTo(EXPECTED_REACHED_BOTTOM),
                    () -> assertThat(result.error()).isFalse());
        }

        @Test
        @DisplayName("an empty cluster - the state the reference-data seed leaves the transaction table"
                + " in - yields an empty page, reports no further page, and leaves the error flag off")
        void anEmptyClusterYieldsAnEmptyPageWithNoFurtherPageAndNoError() {
            when(transactionScanRepository
                    .findByTranIdGreaterThanEqualOrderByTranIdAsc(eq(LOW_END_BOUND), any()))
                    .thenReturn(List.of());

            final TransactionListService.TransactionListResult result =
                    service.listTransactions(firstEntry());

            assertAll(
                    () -> assertThat(result.rows()).isEmpty(),
                    () -> assertThat(result.pageMetadata().hasMorePages()).isFalse(),
                    () -> assertThat(result.pageMetadata().hasPreviousPages()).isFalse(),
                    () -> assertThat(result.pageMetadata().previousCursorKey()).isNull(),
                    () -> assertThat(result.pageMetadata().nextCursorKey()).isNull(),
                    () -> assertThat(result.pageMetadata().displayedPageNumber())
                            .isEqualTo(PAGE_INDICATOR_ZERO),
                    () -> assertThat(result.message()).isEqualTo(EXPECTED_AT_TOP),
                    () -> assertThat(result.error()).isFalse());
        }

        @Test
        @DisplayName("the forward open declares the low end of the cluster as its bound and asks for one"
                + " screen plus the single look-ahead row the paragraph itself reads at line 305")
        void theForwardOpenDeclaresTheLowEndBoundAndTheScreenPlusLookAheadWindow() {
            stubOrderedCluster(twentyFiveRowCluster());

            final TransactionListService.TransactionListResult result =
                    service.listTransactions(firstEntry());

            final ArgumentCaptor<String> boundCaptor = ArgumentCaptor.forClass(String.class);
            final ArgumentCaptor<Limit> limitCaptor = ArgumentCaptor.forClass(Limit.class);
            verify(transactionScanRepository).findByTranIdGreaterThanEqualOrderByTranIdAsc(
                    boundCaptor.capture(), limitCaptor.capture());

            assertAll(
                    () -> assertThat(boundCaptor.getValue()).isEqualTo(LOW_END_BOUND),
                    () -> assertThat(limitCaptor.getValue().max())
                            .isEqualTo(EXPECTED_READ_WINDOW_ROWS),
                    () -> assertThat(result.rows()).hasSize(EXPECTED_PAGE_SIZE));
            verify(transactionScanRepository, never())
                    .findByTranIdGreaterThanOrderByTranIdAsc(any(), any());
            verify(transactionScanRepository, never())
                    .findByTranIdLessThanEqualOrderByTranIdDesc(any(), any());
            verify(transactionScanRepository, never())
                    .findByTranIdLessThanOrderByTranIdDesc(any(), any());
        }

        @Test
        @DisplayName("a short final page updates only the boundary key its own slot writes, so the last"
                + " key stays as the caller echoed it while the first key is refreshed")
        void aShortFinalPageUpdatesOnlyTheBoundaryKeyItsOwnSlotWrites() {
            stubOrderedCluster(List.of(transaction(ID_01), transaction(ID_02), transaction(ID_03),
                    transaction(ID_04), transaction(ID_05), transaction(ID_06), transaction(ID_07),
                    transaction(ID_08), transaction(ID_09), transaction(ID_10), transaction(ID_11),
                    transaction(ID_12), transaction(ID_13), transaction(ID_14), transaction(ID_15)));

            final TransactionListService.TransactionListResult firstPage =
                    service.listTransactions(firstEntry());
            final TransactionListService.TransactionListResult secondPage =
                    service.listTransactions(nextTurn(KeyAction.PFK08, firstPage.pageMetadata()));

            assertAll(
                    () -> assertThat(identifiersOf(secondPage))
                            .containsExactly(ID_11, ID_12, ID_13, ID_14, ID_15),
                    () -> assertThat(slotsOf(secondPage)).containsExactly(1, 2, 3, 4, 5),
                    () -> assertThat(secondPage.rows()).doesNotContainNull(),
                    () -> assertThat(secondPage.pageMetadata().previousCursorKey()).isEqualTo(ID_11),
                    () -> assertThat(secondPage.pageMetadata().nextCursorKey()).isEqualTo(ID_10),
                    () -> assertThat(secondPage.pageMetadata().hasMorePages()).isFalse(),
                    () -> assertThat(secondPage.pageMetadata().hasPreviousPages()).isTrue(),
                    () -> assertThat(secondPage.pageMetadata().displayedPageNumber())
                            .isEqualTo(PAGE_INDICATOR_TWO),
                    () -> assertThat(secondPage.message()).isEqualTo(EXPECTED_REACHED_BOTTOM));
        }
    }

    // ------------------------------------------------------------------------------------------
    // Paragraphs PROCESS-PAGE-BACKWARD line 333, PROCESS-PF7-KEY line 234, READPREV line 658
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Backward paging: read descending, presented ascending - ordering is the contract")
    final class BackwardPagingOrder {

        @Test
        @DisplayName("paging forward, forward, then backward returns the first page's ten identifiers in"
                + " the same ascending order - the assertion a dropped reverse fill fails")
        void pagingForwardTwiceThenBackwardOnceReturnsTheFirstPageInTheSameAscendingOrder() {
            stubOrderedCluster(twentyFiveRowCluster());

            final TransactionListService.TransactionListResult firstPage =
                    service.listTransactions(firstEntry());
            final TransactionListService.TransactionListResult secondPage =
                    service.listTransactions(nextTurn(KeyAction.PFK08, firstPage.pageMetadata()));
            final TransactionListService.TransactionListResult backAgain =
                    service.listTransactions(nextTurn(KeyAction.PFK07, secondPage.pageMetadata()));

            assertAll(
                    () -> assertThat(identifiersOf(firstPage))
                            .containsExactlyElementsOf(EXPECTED_FIRST_PAGE),
                    () -> assertThat(identifiersOf(secondPage))
                            .containsExactlyElementsOf(EXPECTED_SECOND_PAGE),
                    () -> assertThat(identifiersOf(backAgain))
                            .containsExactlyElementsOf(EXPECTED_FIRST_PAGE),
                    () -> assertThat(identifiersOf(backAgain)).isEqualTo(identifiersOf(firstPage)),
                    () -> assertThat(slotsOf(backAgain))
                            .containsExactlyElementsOf(EXPECTED_FULL_PAGE_SLOTS),
                    () -> assertThat(backAgain.pageMetadata().direction())
                            .isEqualTo(BrowseWindow.PagingDirection.BACKWARD),
                    () -> assertThat(backAgain.pageMetadata().displayedPageNumber())
                            .isEqualTo(PAGE_INDICATOR_ONE),
                    () -> assertThat(backAgain.message()).isEqualTo(EXPECTED_REACHED_TOP));
        }

        @Test
        @DisplayName("the backward turn issues only the two descending reads and returns the rows"
                + " ascending: the descending read proves the mechanism, the ascending result the fill")
        void theBackwardTurnIssuesOnlyDescendingReadsAndReturnsTheRowsAscending() {
            when(transactionScanRepository
                    .findByTranIdLessThanEqualOrderByTranIdDesc(eq(ID_11), any()))
                    .thenReturn(rowsFor(BACKWARD_READ_ORDER));
            when(transactionScanRepository.findByTranIdLessThanOrderByTranIdDesc(eq(ID_01), any()))
                    .thenReturn(List.of());

            final TransactionListService.TransactionListResult result = service.listTransactions(
                    reEntry(KeyAction.PFK07, ID_11, ID_20, true, 2));

            final ArgumentCaptor<String> boundCaptor = ArgumentCaptor.forClass(String.class);
            final ArgumentCaptor<Limit> limitCaptor = ArgumentCaptor.forClass(Limit.class);
            verify(transactionScanRepository).findByTranIdLessThanEqualOrderByTranIdDesc(
                    boundCaptor.capture(), limitCaptor.capture());

            assertAll(
                    () -> assertThat(BACKWARD_READ_ORDER)
                            .isSortedAccordingTo(Comparator.<String>reverseOrder()),
                    () -> assertThat(identifiersOf(result))
                            .isSortedAccordingTo(Comparator.<String>naturalOrder()),
                    () -> assertThat(identifiersOf(result))
                            .containsExactlyElementsOf(EXPECTED_FIRST_PAGE),
                    () -> assertThat(boundCaptor.getValue()).isEqualTo(ID_11),
                    () -> assertThat(limitCaptor.getValue().max())
                            .isEqualTo(EXPECTED_READ_WINDOW_ROWS),
                    () -> assertThat(result.pageMetadata().direction())
                            .isEqualTo(BrowseWindow.PagingDirection.BACKWARD));
            verify(transactionScanRepository, never())
                    .findByTranIdGreaterThanEqualOrderByTranIdAsc(any(), any());
            verify(transactionScanRepository, never())
                    .findByTranIdGreaterThanOrderByTranIdAsc(any(), any());
        }

        @Test
        @DisplayName("the backward guard of lines 339-341 consumes the positioned record, so the key the"
                + " browse repositioned on is absent from the page it produces")
        void theBackwardGuardConsumesThePositionedRecordSoTheCursorRowIsExcluded() {
            when(transactionScanRepository
                    .findByTranIdLessThanEqualOrderByTranIdDesc(eq(ID_11), any()))
                    .thenReturn(rowsFor(BACKWARD_READ_ORDER));
            when(transactionScanRepository.findByTranIdLessThanOrderByTranIdDesc(eq(ID_01), any()))
                    .thenReturn(List.of());

            final TransactionListService.TransactionListResult result = service.listTransactions(
                    reEntry(KeyAction.PFK07, ID_11, ID_20, true, 2));

            assertAll(
                    () -> assertThat(identifiersOf(result)).doesNotContain(ID_11),
                    () -> assertThat(identifiersOf(result)).first().isEqualTo(ID_01),
                    () -> assertThat(identifiersOf(result)).last().isEqualTo(ID_10));
        }

        @Test
        @DisplayName("a backward page that finds fewer than ten rows is bottom aligned, because the fill"
                + " of line 349 starts at the tenth slot and decrements")
        void aShortBackwardPageIsBottomAligned() {
            when(transactionScanRepository
                    .findByTranIdLessThanEqualOrderByTranIdDesc(eq(ID_05), any()))
                    .thenReturn(rowsFor(List.of(ID_05, ID_04, ID_03, ID_02, ID_01)));

            final TransactionListService.TransactionListResult result = service.listTransactions(
                    reEntry(KeyAction.PFK07, ID_05, ID_14, true, 2));

            assertAll(
                    () -> assertThat(result.rows()).hasSize(4),
                    () -> assertThat(slotsOf(result)).containsExactly(7, 8, 9, 10),
                    () -> assertThat(identifiersOf(result))
                            .containsExactly(ID_01, ID_02, ID_03, ID_04),
                    () -> assertThat(result.pageMetadata().previousCursorKey()).isEqualTo(ID_05),
                    () -> assertThat(result.pageMetadata().nextCursorKey()).isEqualTo(ID_04),
                    () -> assertThat(result.message()).isEqualTo(EXPECTED_REACHED_TOP));
        }

        @Test
        @DisplayName("stepping back from the third page decrements the page number to two rather than"
                + " pinning it to one, because a preceding record exists beyond the first page")
        void steppingBackFromTheThirdPageDecrementsThePageNumber() {
            stubOrderedCluster(twentyFiveRowCluster());

            final TransactionListService.TransactionListResult firstPage =
                    service.listTransactions(firstEntry());
            final TransactionListService.TransactionListResult secondPage =
                    service.listTransactions(nextTurn(KeyAction.PFK08, firstPage.pageMetadata()));
            final TransactionListService.TransactionListResult thirdPage =
                    service.listTransactions(nextTurn(KeyAction.PFK08, secondPage.pageMetadata()));
            final TransactionListService.TransactionListResult backToSecond =
                    service.listTransactions(nextTurn(KeyAction.PFK07, thirdPage.pageMetadata()));

            assertAll(
                    () -> assertThat(identifiersOf(thirdPage))
                            .containsExactly(ID_21, ID_22, ID_23, ID_24, ID_25),
                    () -> assertThat(thirdPage.pageMetadata().displayedPageNumber())
                            .isEqualTo(PAGE_INDICATOR_THREE),
                    () -> assertThat(identifiersOf(backToSecond))
                            .containsExactlyElementsOf(EXPECTED_SECOND_PAGE),
                    () -> assertThat(backToSecond.pageMetadata().displayedPageNumber())
                            .isEqualTo(PAGE_INDICATOR_TWO),
                    () -> assertThat(backToSecond.pageMetadata().hasPreviousPages()).isTrue(),
                    () -> assertThat(backToSecond.pageMetadata().direction())
                            .isEqualTo(BrowseWindow.PagingDirection.BACKWARD),
                    () -> assertThat(backToSecond.message()).isEqualTo(BLANK));
        }

        @Test
        @DisplayName("a backward browse with no retained first key resolves the low end of the cluster"
                + " with a single-row ascending read before walking downward from it")
        void aBackwardBrowseWithNoRetainedKeyResolvesTheLowEndWithASingleRowRead() {
            when(transactionScanRepository
                    .findByTranIdGreaterThanEqualOrderByTranIdAsc(eq(LOW_END_BOUND), any()))
                    .thenReturn(rowsFor(List.of(ID_01)));
            when(transactionScanRepository
                    .findByTranIdLessThanEqualOrderByTranIdDesc(eq(ID_01), any()))
                    .thenReturn(rowsFor(List.of(ID_01)));

            final TransactionListService.TransactionListResult result = service.listTransactions(
                    reEntry(KeyAction.PFK07, null, ID_10, true, 2));

            final ArgumentCaptor<Limit> limitCaptor = ArgumentCaptor.forClass(Limit.class);
            verify(transactionScanRepository)
                    .findByTranIdGreaterThanEqualOrderByTranIdAsc(eq(LOW_END_BOUND),
                            limitCaptor.capture());

            assertAll(
                    () -> assertThat(limitCaptor.getValue().max()).isEqualTo(LOW_END_RESOLUTION_ROWS),
                    () -> assertThat(result.rows()).isEmpty(),
                    () -> assertThat(result.message()).isEqualTo(EXPECTED_REACHED_TOP));
        }
    }

    // ------------------------------------------------------------------------------------------
    // Paragraphs PROCESS-PF7-KEY line 234, PROCESS-PF8-KEY line 257, and the two paging guards
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Attention-key guards: which record a page starts at, and when no read is issued")
    final class AttentionKeyGuards {

        @Test
        @DisplayName("the enter key fails the forward guard of lines 285-287, so the positioned record is"
                + " kept and a supplied filter key appears in its own page")
        void theEnterKeyKeepsThePositionedRecordSoAFilterKeyAppearsInItsOwnPage() {
            stubOrderedCluster(twentyFiveRowCluster());

            final TransactionListService.TransactionListResult result =
                    service.listTransactions(submittedEnter(ID_05, List.of(), List.of()));

            final ArgumentCaptor<String> boundCaptor = ArgumentCaptor.forClass(String.class);
            verify(transactionScanRepository)
                    .findByTranIdGreaterThanEqualOrderByTranIdAsc(boundCaptor.capture(), any());

            assertAll(
                    () -> assertThat(boundCaptor.getValue()).isEqualTo(ID_05),
                    () -> assertThat(identifiersOf(result)).containsExactly(ID_05, ID_06, ID_07, ID_08,
                            ID_09, ID_10, ID_11, ID_12, ID_13, ID_14),
                    () -> assertThat(identifiersOf(result)).first().isEqualTo(ID_05),
                    () -> assertThat(result.pageMetadata().previousCursorKey()).isEqualTo(ID_05),
                    () -> assertThat(result.pageMetadata().nextCursorKey()).isEqualTo(ID_14));
        }

        @Test
        @DisplayName("the eighth key satisfies the forward guard, so the positioned record is discarded"
                + " and the new page starts immediately past the retained last key")
        void theEighthKeyDiscardsThePositionedRecordSoTheNewPageStartsPastTheCursor() {
            stubOrderedCluster(twentyFiveRowCluster());

            final TransactionListService.TransactionListResult result =
                    service.listTransactions(reEntry(KeyAction.PFK08, ID_01, ID_10, true, 1));

            final ArgumentCaptor<String> boundCaptor = ArgumentCaptor.forClass(String.class);
            verify(transactionScanRepository)
                    .findByTranIdGreaterThanEqualOrderByTranIdAsc(boundCaptor.capture(), any());

            assertAll(
                    () -> assertThat(boundCaptor.getValue()).isEqualTo(ID_10),
                    () -> assertThat(identifiersOf(result))
                            .containsExactlyElementsOf(EXPECTED_SECOND_PAGE),
                    () -> assertThat(identifiersOf(result)).doesNotContain(ID_10),
                    () -> assertThat(result.pageMetadata().direction())
                            .isEqualTo(BrowseWindow.PagingDirection.FORWARD));
        }

        @Test
        @DisplayName("the seventh key on the first page reports the top, suppresses every read, keeps the"
                + " screen unerased, and still sets the further-page flag unconditionally at line 242")
        void theSeventhKeyOnTheFirstPageReportsTheTopAndSuppressesEveryRead() {
            final TransactionListService.TransactionListResult result =
                    service.listTransactions(reEntry(KeyAction.PFK07, ID_01, ID_10, false, 1));

            verify(transactionScanRepository, never())
                    .findByTranIdGreaterThanEqualOrderByTranIdAsc(any(), any());
            verify(transactionScanRepository, never())
                    .findByTranIdGreaterThanOrderByTranIdAsc(any(), any());
            verify(transactionScanRepository, never())
                    .findByTranIdLessThanEqualOrderByTranIdDesc(any(), any());
            verify(transactionScanRepository, never())
                    .findByTranIdLessThanOrderByTranIdDesc(any(), any());
            verifyNoInteractions(transactionScanRepository);

            assertAll(
                    () -> assertThat(result.message()).isEqualTo(EXPECTED_ALREADY_AT_TOP),
                    () -> assertThat(result.rows()).isEmpty(),
                    () -> assertThat(result.eraseScreen()).isFalse(),
                    () -> assertThat(result.error()).isFalse(),
                    () -> assertThat(result.pageMetadata().hasMorePages()).isTrue(),
                    () -> assertThat(result.pageMetadata().direction())
                            .isEqualTo(BrowseWindow.PagingDirection.BACKWARD),
                    () -> assertThat(result.pageMetadata().displayedPageNumber())
                            .isEqualTo(PAGE_INDICATOR_ONE));
        }

        @Test
        @DisplayName("the eighth key with no further page reports the bottom, suppresses every read and"
                + " keeps the screen unerased, because its guard at line 267 tests the echoed flag")
        void theEighthKeyWithNoFurtherPageReportsTheBottomAndSuppressesEveryRead() {
            final TransactionListService.TransactionListResult result =
                    service.listTransactions(reEntry(KeyAction.PFK08, ID_01, ID_10, false, 1));

            verifyNoInteractions(transactionScanRepository);

            assertAll(
                    () -> assertThat(result.message()).isEqualTo(EXPECTED_ALREADY_AT_BOTTOM),
                    () -> assertThat(result.rows()).isEmpty(),
                    () -> assertThat(result.eraseScreen()).isFalse(),
                    () -> assertThat(result.error()).isFalse(),
                    () -> assertThat(result.pageMetadata().hasMorePages()).isFalse(),
                    () -> assertThat(result.pageMetadata().direction())
                            .isEqualTo(BrowseWindow.PagingDirection.FORWARD));
        }

        @Test
        @DisplayName("the eighth key with no retained last key positions on a value no key can match, so"
                + " the browse reports not found without a query and the guard read then finds none open")
        void theEighthKeyWithNoRetainedLastKeyReachesTheInvalidRequestArmWithoutAnyQuery() {
            final TransactionListService.TransactionListResult result =
                    service.listTransactions(reEntry(KeyAction.PFK08, ID_01, null, true, 1));

            verifyNoInteractions(transactionScanRepository);

            assertAll(
                    () -> assertThat(result.rows()).isEmpty(),
                    () -> assertThat(result.error()).isTrue(),
                    () -> assertThat(result.message()).isEqualTo(EXPECTED_UNABLE_TO_LOOKUP),
                    () -> assertThat(result.pageMetadata().hasMorePages()).isFalse());
        }

        @Test
        @DisplayName("the third key returns to the user main menu, suppressing every read and sending no"
                + " screen, so the turn carries no message at all")
        void theThirdKeyReturnsToTheUserMainMenuAndSuppressesEveryRead() {
            when(navigationService.resolveNominatedDestination(any(), any()))
                    .thenReturn(NavigationService.Route.USER_MENU);

            final TransactionListService.TransactionListResult result =
                    service.listTransactions(reEntry(KeyAction.PFK03, ID_01, ID_10, true, 1));

            final ArgumentCaptor<ConversationState> carriedCaptor =
                    ArgumentCaptor.forClass(ConversationState.class);
            verify(navigationService).resolveNominatedDestination(carriedCaptor.capture(), any());
            verifyNoInteractions(transactionScanRepository);

            assertAll(
                    () -> assertThat(result.route()).isEqualTo(NavigationService.Route.USER_MENU),
                    () -> assertThat(result.route().getRouteValue())
                            .isEqualTo(EXPECTED_USER_MENU_ROUTE_VALUE),
                    () -> assertThat(result.rows()).isEmpty(),
                    () -> assertThat(result.message()).isEqualTo(BLANK),
                    () -> assertThat(carriedCaptor.getValue().toProgram())
                            .isEqualTo(EXPECTED_USER_MENU_PROGRAM),
                    () -> assertThat(carriedCaptor.getValue().fromProgram())
                            .isEqualTo(EXPECTED_PROGRAM_NAME),
                    () -> assertThat(carriedCaptor.getValue().fromTransactionId())
                            .isEqualTo(EXPECTED_TRANSACTION_NAME));
        }
    }

    // ------------------------------------------------------------------------------------------
    // Paragraph MAIN-PARA line 95: the catch-all arm of the attention-key evaluation
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("The common message catalogue: fifty characters, padded, and never trimmed")
    final class MessageCatalogFidelity {

        @ParameterizedTest(name = "attention key {0} falls to the catch-all arm")
        @EnumSource(value = KeyAction.class, names = {"CLEAR", "PA1", "PA2", "PFK01", "PFK02",
            "PFK04", "PFK05", "PFK06", "PFK09", "PFK10", "PFK11", "PFK12"})
        @DisplayName("an unmapped attention key emits the catalogue's invalid-key text at exactly fifty"
                + " encoded bytes with its ten trailing spaces intact")
        void anUnmappedAttentionKeyEmitsTheFiftyByteCatalogueMessageUntrimmed(
                final KeyAction unmappedKey) {
            when(messageCatalogService.invalidKeyMessage()).thenReturn(EXPECTED_INVALID_KEY_MESSAGE);

            final TransactionListService.TransactionListResult result =
                    service.listTransactions(reEntry(unmappedKey, ID_01, ID_10, true, 1));

            verifyNoInteractions(transactionScanRepository);

            assertAll(
                    () -> assertThat(result.message()).isEqualTo(EXPECTED_INVALID_KEY_MESSAGE),
                    () -> assertThat(encodedByteLength(result.message()))
                            .isEqualTo(COMMON_MESSAGE_WIDTH),
                    () -> assertThat(result.message()).endsWith(" ".repeat(10)),
                    () -> assertThat(result.message()).isNotEqualTo(INVALID_KEY_VISIBLE),
                    () -> assertThat(result.error()).isTrue(),
                    () -> assertThat(result.rows()).isEmpty(),
                    () -> assertThat(result.focusScreenFieldId()).isEqualTo(EXPECTED_FOCUS_FIELD));
        }

        @Test
        @DisplayName("the service passes a catalogue value through byte for byte whatever its padding"
                + " count, so a forty-three-plus-seven value crosses as intact as a forty-plus-ten one")
        void theServicePassesACatalogueValueThroughWhateverItsPaddingCount() {
            when(messageCatalogService.invalidKeyMessage()).thenReturn(EXPECTED_THANK_YOU_MESSAGE);

            final TransactionListService.TransactionListResult result =
                    service.listTransactions(reEntry(KeyAction.PFK05, ID_01, ID_10, true, 1));

            assertAll(
                    () -> assertThat(result.message()).isEqualTo(EXPECTED_THANK_YOU_MESSAGE),
                    () -> assertThat(encodedByteLength(result.message()))
                            .isEqualTo(COMMON_MESSAGE_WIDTH),
                    () -> assertThat(result.message()).endsWith(" ".repeat(7)),
                    () -> assertThat(result.message()).isNotEqualTo(THANK_YOU_VISIBLE));
        }
    }

    // ------------------------------------------------------------------------------------------
    // Repository ownership, and paragraph ENDBR-TRANSACT-FILE line 692
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Repository ownership: only the four bounded ordered reads, and nothing else")
    final class RepositoryOwnership {

        @Test
        @DisplayName("the highest-identifier query is never invoked from this screen, because it belongs"
                + " to the bill-payment screen exclusively")
        void theHighestIdentifierQueryIsNeverInvokedFromThisScreen() {
            stubOrderedCluster(twentyFiveRowCluster());

            final TransactionListService.TransactionListResult result =
                    service.listTransactions(firstEntry());

            verify(transactionRepository, never()).findMaxId();
            verifyNoInteractions(transactionRepository);
            assertThat(result.rows()).hasSize(EXPECTED_PAGE_SIZE);
        }

        @Test
        @DisplayName("a forward, forward, backward walk touches the four bounded ordered reads and no"
                + " other persistence surface, and every browse it opens is closed")
        void theBrowseReadsOnlyThroughTheFourBoundedOrderedReads() {
            stubOrderedCluster(twentyFiveRowCluster());

            final TransactionListService.TransactionListResult firstPage =
                    service.listTransactions(firstEntry());
            final TransactionListService.TransactionListResult secondPage =
                    service.listTransactions(nextTurn(KeyAction.PFK08, firstPage.pageMetadata()));
            final TransactionListService.TransactionListResult backAgain =
                    service.listTransactions(nextTurn(KeyAction.PFK07, secondPage.pageMetadata()));

            verify(transactionScanRepository, times(2))
                    .findByTranIdGreaterThanEqualOrderByTranIdAsc(any(), any());
            verify(transactionScanRepository).findByTranIdGreaterThanOrderByTranIdAsc(any(), any());
            verify(transactionScanRepository).findByTranIdLessThanEqualOrderByTranIdDesc(any(), any());
            verify(transactionScanRepository).findByTranIdLessThanOrderByTranIdDesc(any(), any());
            verifyNoMoreInteractions(transactionScanRepository);
            verifyNoInteractions(transactionRepository);

            assertThat(identifiersOf(backAgain)).containsExactlyElementsOf(EXPECTED_FIRST_PAGE);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Paragraph POPULATE-TRAN-DATA line 381: every value crosses exactly as stored
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Field fidelity: padding, scale, sign and both timestamp forms cross untouched")
    final class FieldFidelity {

        @Test
        @DisplayName("the source code keeps its trailing spaces and measures ten encoded bytes, because a"
                + " fixed-width field is a byte contract and is never trimmed")
        void theSourceCodeKeepsItsTrailingSpacesAtTenEncodedBytes() {
            stubLowEndPage(List.of(transaction(ID_01, PADDED_SOURCE, AMOUNT_AT_SCALE_TWO,
                    ONLINE_TIMESTAMP, ONLINE_TIMESTAMP)));

            final TransactionListService.TransactionListResult result =
                    service.listTransactions(firstEntry());

            final TransactionListService.TransactionListRow row = result.rows().get(0);
            assertAll(
                    () -> assertThat(row.tranSource()).isEqualTo(PADDED_SOURCE),
                    () -> assertThat(encodedByteLength(row.tranSource())).isEqualTo(SOURCE_WIDTH),
                    () -> assertThat(row.tranSource()).endsWith(" ".repeat(2)),
                    () -> assertThat(row.tranSource()).isNotEqualTo(SOURCE_VISIBLE));
        }

        @Test
        @DisplayName("the amount crosses as a decimal at the column's own scale of two, and this class"
                + " applies no scaling of its own")
        void theAmountCrossesAtTheColumnScaleOfTwo() {
            stubLowEndPage(List.of(transaction(ID_01, PADDED_SOURCE, AMOUNT_AT_SCALE_TWO,
                    ONLINE_TIMESTAMP, ONLINE_TIMESTAMP)));

            final TransactionListService.TransactionListResult result =
                    service.listTransactions(firstEntry());

            final TransactionListService.TransactionListRow row = result.rows().get(0);
            assertAll(
                    () -> assertThat(row.tranAmt()).isExactlyInstanceOf(BigDecimal.class),
                    () -> assertThat(row.tranAmt()).isEqualTo(new BigDecimal("123.45")),
                    () -> assertThat(row.tranAmt().scale()).isEqualTo(MONETARY_SCALE));
        }

        @Test
        @DisplayName("an amount that a rounding step would alter crosses unaltered: neither truncated to"
                + " two places nor rounded half up, which proves no rescaling happens here")
        void anAmountARoundingStepWouldAlterCrossesUnaltered() {
            stubLowEndPage(List.of(transaction(ID_01, PADDED_SOURCE,
                    AMOUNT_A_ROUNDING_STEP_WOULD_ALTER, ONLINE_TIMESTAMP, ONLINE_TIMESTAMP)));

            final TransactionListService.TransactionListResult result =
                    service.listTransactions(firstEntry());

            final TransactionListService.TransactionListRow row = result.rows().get(0);
            assertAll(
                    () -> assertThat(row.tranAmt()).isEqualTo(new BigDecimal("123.455")),
                    () -> assertThat(row.tranAmt().scale()).isEqualTo(3),
                    () -> assertThat(row.tranAmt()).isNotEqualTo(new BigDecimal("123.45")),
                    () -> assertThat(row.tranAmt()).isNotEqualTo(new BigDecimal("123.46")));
        }

        @Test
        @DisplayName("a negative amount keeps its sign, which is the shape the operator-originated returns"
                + " in the daily-transaction fixture carry")
        void aNegativeAmountKeepsItsSign() {
            stubLowEndPage(List.of(transaction(ID_01, PADDED_RETURN_SOURCE, NEGATIVE_AMOUNT,
                    ONLINE_TIMESTAMP, ONLINE_TIMESTAMP)));

            final TransactionListService.TransactionListResult result =
                    service.listTransactions(firstEntry());

            final TransactionListService.TransactionListRow row = result.rows().get(0);
            assertAll(
                    () -> assertThat(row.tranAmt()).isEqualTo(new BigDecimal("-45.67")),
                    () -> assertThat(row.tranAmt().signum()).isEqualTo(-1),
                    () -> assertThat(row.tranAmt().scale()).isEqualTo(MONETARY_SCALE),
                    () -> assertThat(row.tranSource()).isEqualTo(PADDED_RETURN_SOURCE),
                    () -> assertThat(encodedByteLength(row.tranSource())).isEqualTo(SOURCE_WIDTH));
        }

        @Test
        @DisplayName("both twenty-six-character timestamp forms cross byte for byte and remain unequal to"
                + " one another, so the online and batch contracts are not unified")
        void bothTimestampFormsCrossByteForByteAndRemainUnequal() {
            stubLowEndPage(List.of(
                    transaction(ID_01, PADDED_SOURCE, AMOUNT_AT_SCALE_TWO, ONLINE_TIMESTAMP,
                            ONLINE_TIMESTAMP),
                    transaction(ID_02, PADDED_SOURCE, AMOUNT_AT_SCALE_TWO, BATCH_TIMESTAMP,
                            BATCH_TIMESTAMP)));

            final TransactionListService.TransactionListResult result =
                    service.listTransactions(firstEntry());

            final TransactionListService.TransactionListRow onlineRow = result.rows().get(0);
            final TransactionListService.TransactionListRow batchRow = result.rows().get(1);
            assertAll(
                    () -> assertThat(result.rows()).hasSize(2),
                    () -> assertThat(onlineRow.tranOrigTs()).isEqualTo(ONLINE_TIMESTAMP),
                    () -> assertThat(onlineRow.tranProcTs()).isEqualTo(ONLINE_TIMESTAMP),
                    () -> assertThat(encodedByteLength(onlineRow.tranOrigTs()))
                            .isEqualTo(TIMESTAMP_WIDTH),
                    () -> assertThat(encodedByteLength(onlineRow.tranProcTs()))
                            .isEqualTo(TIMESTAMP_WIDTH),
                    () -> assertThat(batchRow.tranOrigTs()).isEqualTo(BATCH_TIMESTAMP),
                    () -> assertThat(batchRow.tranProcTs()).isEqualTo(BATCH_TIMESTAMP),
                    () -> assertThat(encodedByteLength(batchRow.tranOrigTs()))
                            .isEqualTo(TIMESTAMP_WIDTH),
                    () -> assertThat(encodedByteLength(batchRow.tranProcTs()))
                            .isEqualTo(TIMESTAMP_WIDTH),
                    () -> assertThat(onlineRow.tranOrigTs()).isNotEqualTo(batchRow.tranOrigTs()),
                    () -> assertThat(onlineRow.tranProcTs()).isNotEqualTo(batchRow.tranProcTs()));
        }

        @Test
        @DisplayName("the four merchant values are read through this record's own unprefixed properties,"
                + " which the byte-identical daily-transaction record does not share")
        void theMerchantValuesAreReadThroughTheUnprefixedProperties() {
            stubLowEndPage(List.of(transaction(ID_01)));

            final TransactionListService.TransactionListResult result =
                    service.listTransactions(firstEntry());

            final TransactionListService.TransactionListRow row = result.rows().get(0);
            assertAll(
                    () -> assertThat(row.merchantId()).isEqualTo(MERCHANT_ID),
                    () -> assertThat(row.merchantName()).isEqualTo(MERCHANT_NAME),
                    () -> assertThat(row.merchantCity()).isEqualTo(MERCHANT_CITY),
                    () -> assertThat(row.merchantZip()).isEqualTo(MERCHANT_ZIP));
        }

        @Test
        @DisplayName("the identifier, the codes, the description and the card number cross at their"
                + " stored widths, leading zeros and full description length included")
        void theRemainingValuesCrossAtTheirStoredWidths() {
            stubLowEndPage(List.of(transaction(ID_01)));

            final TransactionListService.TransactionListResult result =
                    service.listTransactions(firstEntry());

            final TransactionListService.TransactionListRow row = result.rows().get(0);
            assertAll(
                    () -> assertThat(row.tranId()).isEqualTo(ID_01),
                    () -> assertThat(encodedByteLength(row.tranId())).isEqualTo(16),
                    () -> assertThat(row.tranTypeCd()).isEqualTo(TYPE_CODE),
                    () -> assertThat(row.tranCatCd()).isEqualTo(CATEGORY_CODE),
                    () -> assertThat(row.tranDesc()).isEqualTo(DESCRIPTION),
                    () -> assertThat(row.tranCardNum()).isEqualTo(CARD_NUMBER),
                    () -> assertThat(row.screenRow()).isEqualTo(1));
        }
    }

    // ------------------------------------------------------------------------------------------
    // The browse window a turn reports
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Page metadata: the look-ahead, the two cursors, and the direction")
    final class PageMetadataFidelity {

        @Test
        @DisplayName("a cluster of exactly eleven rows reports a further page, because the look-ahead read"
                + " of line 305 genuinely finds one")
        void aClusterOfElevenRowsReportsAFurtherPage() {
            stubLowEndPage(rowsFor(List.of(ID_01, ID_02, ID_03, ID_04, ID_05, ID_06, ID_07, ID_08,
                    ID_09, ID_10, ID_11)));

            final TransactionListService.TransactionListResult result =
                    service.listTransactions(firstEntry());

            assertAll(
                    () -> assertThat(result.rows()).hasSize(EXPECTED_PAGE_SIZE),
                    () -> assertThat(identifiersOf(result))
                            .containsExactlyElementsOf(EXPECTED_FIRST_PAGE),
                    () -> assertThat(result.pageMetadata().hasMorePages()).isTrue(),
                    () -> assertThat(result.message()).isEqualTo(BLANK));
        }

        @Test
        @DisplayName("a cluster of exactly ten rows fills the page and then reports no further page,"
                + " because the same look-ahead read finds none")
        void aClusterOfExactlyTenRowsReportsNoFurtherPage() {
            stubLowEndPage(rowsFor(EXPECTED_FIRST_PAGE));

            final TransactionListService.TransactionListResult result =
                    service.listTransactions(firstEntry());

            assertAll(
                    () -> assertThat(result.rows()).hasSize(EXPECTED_PAGE_SIZE),
                    () -> assertThat(identifiersOf(result))
                            .containsExactlyElementsOf(EXPECTED_FIRST_PAGE),
                    () -> assertThat(result.pageMetadata().hasMorePages()).isFalse(),
                    () -> assertThat(result.message()).isEqualTo(EXPECTED_REACHED_BOTTOM),
                    () -> assertThat(result.pageMetadata().displayedPageNumber())
                            .isEqualTo(PAGE_INDICATOR_ONE));
        }

        @Test
        @DisplayName("a forward page reports the forward direction and both boundary keys, because the"
                + " backward key stays live for the seventh program function key")
        void aForwardPageReportsTheForwardDirectionAndBothBoundaryKeys() {
            stubOrderedCluster(twentyFiveRowCluster());

            final TransactionListService.TransactionListResult result =
                    service.listTransactions(firstEntry());

            assertAll(
                    () -> assertThat(result.pageMetadata().direction())
                            .isEqualTo(BrowseWindow.PagingDirection.FORWARD),
                    () -> assertThat(result.pageMetadata().previousCursorKey()).isEqualTo(ID_01),
                    () -> assertThat(result.pageMetadata().nextCursorKey()).isEqualTo(ID_10),
                    () -> assertThat(result.pageMetadata().pageSize()).isEqualTo(EXPECTED_PAGE_SIZE));
        }

        @Test
        @DisplayName("a backward page reports the backward direction and both boundary keys taken from"
                + " the first and tenth slots the reverse fill populated")
        void aBackwardPageReportsTheBackwardDirectionAndBothBoundaryKeys() {
            stubOrderedCluster(twentyFiveRowCluster());

            final TransactionListService.TransactionListResult secondPage = service.listTransactions(
                    reEntry(KeyAction.PFK08, ID_01, ID_10, true, 1));
            final TransactionListService.TransactionListResult backAgain =
                    service.listTransactions(nextTurn(KeyAction.PFK07, secondPage.pageMetadata()));

            assertAll(
                    () -> assertThat(backAgain.pageMetadata().direction())
                            .isEqualTo(BrowseWindow.PagingDirection.BACKWARD),
                    () -> assertThat(backAgain.pageMetadata().previousCursorKey()).isEqualTo(ID_01),
                    () -> assertThat(backAgain.pageMetadata().nextCursorKey()).isEqualTo(ID_10),
                    () -> assertThat(backAgain.pageMetadata().hasPreviousPages()).isFalse());
        }

        @Test
        @DisplayName("the direction is taken from the attention key and never from the echoed cursor, so"
                + " a submission that asks for backward on the enter key still reports forward")
        void theDirectionIsTakenFromTheAttentionKeyAndNotFromTheEchoedCursor() {
            stubOrderedCluster(twentyFiveRowCluster());

            final TransactionListService.TransactionListCommand command =
                    new TransactionListService.TransactionListCommand(
                            KeyAction.ENTER,
                            ScreenNavigationState.empty().withReEntry(),
                            null,
                            List.of(),
                            List.of(),
                            new BrowseWindow.CursorRequest(ID_11, ID_20,
                                    BrowseWindow.PagingDirection.BACKWARD),
                            true,
                            2);

            final TransactionListService.TransactionListResult result =
                    service.listTransactions(command);

            assertAll(
                    () -> assertThat(result.pageMetadata().direction())
                            .isEqualTo(BrowseWindow.PagingDirection.FORWARD),
                    () -> assertThat(identifiersOf(result))
                            .containsExactlyElementsOf(EXPECTED_FIRST_PAGE),
                    () -> assertThat(result.pageMetadata().displayedPageNumber())
                            .isEqualTo(PAGE_INDICATOR_ONE));
        }
    }

    // ------------------------------------------------------------------------------------------
    // Paragraph RETURN-TO-PREV-SCREEN line 510, and the transfer statement of lines 188-195
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Routing: the destination travels as a value and nothing forwards on the server")
    final class Routing {

        @Test
        @DisplayName("a turn that stays on the list returns this screen's own route and never consults the"
                + " navigation service, reproducing the legacy re-arming its own transaction")
        void aTurnStayingOnTheListReturnsThisScreensOwnRoute() {
            stubOrderedCluster(twentyFiveRowCluster());

            final TransactionListService.TransactionListResult result =
                    service.listTransactions(firstEntry());

            verifyNoInteractions(navigationService);

            assertAll(
                    () -> assertThat(result.route())
                            .isEqualTo(NavigationService.Route.TRANSACTION_LIST),
                    () -> assertThat(result.route().getRouteValue())
                            .isEqualTo(EXPECTED_LIST_ROUTE_VALUE));
        }

        @ParameterizedTest(name = "row action ''{0}'' transfers to the transaction-view route")
        @ValueSource(strings = {"S", "s"})
        @DisplayName("a row selected for viewing transfers to the route the navigation service resolves,"
                + " in either letter case, and issues no read at all")
        void aRowSelectedForViewingTransfersToTheResolvedRoute(final String rowAction) {
            when(navigationService.resolveNominatedDestination(any(), any()))
                    .thenReturn(NavigationService.Route.TRANSACTION_VIEW);

            final TransactionListService.TransactionListResult result = service.listTransactions(
                    submittedEnter(null, List.of(rowAction), List.of(ID_03)));

            final ArgumentCaptor<ConversationState> carriedCaptor =
                    ArgumentCaptor.forClass(ConversationState.class);
            verify(navigationService).resolveNominatedDestination(carriedCaptor.capture(), any());
            verifyNoInteractions(transactionScanRepository);

            assertAll(
                    () -> assertThat(result.route())
                            .isEqualTo(NavigationService.Route.TRANSACTION_VIEW),
                    () -> assertThat(result.route().getRouteValue())
                            .isEqualTo(EXPECTED_VIEW_ROUTE_VALUE),
                    () -> assertThat(result.selectedTransactionId()).isEqualTo(ID_03),
                    () -> assertThat(result.rows()).isEmpty(),
                    () -> assertThat(result.message()).isEqualTo(BLANK),
                    () -> assertThat(carriedCaptor.getValue().toProgram())
                            .isEqualTo(EXPECTED_VIEW_PROGRAM),
                    () -> assertThat(carriedCaptor.getValue().fromProgram())
                            .isEqualTo(EXPECTED_PROGRAM_NAME));
        }

        @Test
        @DisplayName("a turn carrying no navigation state at all routes to sign-on, sends no screen and"
                + " settles no page")
        void aTurnCarryingNoNavigationStateRoutesToSignOn() {
            when(navigationService.resolveNominatedDestination(any(), any()))
                    .thenReturn(NavigationService.Route.SIGN_ON);

            final TransactionListService.TransactionListResult result = service.listTransactions(
                    new TransactionListService.TransactionListCommand(KeyAction.ENTER, null, null,
                            List.of(), List.of(), null, false, 0));

            final ArgumentCaptor<ConversationState> carriedCaptor =
                    ArgumentCaptor.forClass(ConversationState.class);
            verify(navigationService).resolveNominatedDestination(carriedCaptor.capture(), any());
            verifyNoInteractions(transactionScanRepository);

            assertAll(
                    () -> assertThat(result.route()).isEqualTo(NavigationService.Route.SIGN_ON),
                    () -> assertThat(result.route().getRouteValue())
                            .isEqualTo(EXPECTED_SIGN_ON_ROUTE_VALUE),
                    () -> assertThat(result.rows()).isEmpty(),
                    () -> assertThat(result.message()).isEqualTo(BLANK),
                    () -> assertThat(result.pageMetadata().displayedPageNumber())
                            .isEqualTo(PAGE_INDICATOR_ZERO),
                    () -> assertThat(carriedCaptor.getValue().toProgram())
                            .isEqualTo(EXPECTED_SIGN_ON_PROGRAM));
        }

        @Test
        @DisplayName("a wholly empty navigation state is treated as absent, exactly as a zero-length"
                + " communication area is at line 107")
        void aWhollyEmptyNavigationStateIsTreatedAsAbsent() {
            when(navigationService.resolveNominatedDestination(any(), any()))
                    .thenReturn(NavigationService.Route.SIGN_ON);

            final TransactionListService.TransactionListResult result = service.listTransactions(
                    new TransactionListService.TransactionListCommand(KeyAction.ENTER,
                            ScreenNavigationState.empty(), null, List.of(), List.of(), null, false,
                            0));

            verifyNoInteractions(transactionScanRepository);

            assertAll(
                    () -> assertThat(result.route()).isEqualTo(NavigationService.Route.SIGN_ON),
                    () -> assertThat(result.rows()).isEmpty());
        }
    }

    // ------------------------------------------------------------------------------------------
    // Paragraphs PROCESS-ENTER-KEY line 146 and RECEIVE-TRNLST-SCREEN line 554
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Row selection and the transaction-id filter: two paths that fall through on failure")
    final class SelectionAndFilter {

        @Test
        @DisplayName("the first non-blank selector wins in ascending row order, and its screen field name"
                + " names the slot it was found in")
        void theFirstNonBlankSelectorWinsInAscendingRowOrder() {
            stubOrderedCluster(twentyFiveRowCluster());

            final TransactionListService.TransactionListResult result = service.listTransactions(
                    submittedEnter(null, List.of(" ", " ", "X", " ", "S"),
                            List.of(ID_01, ID_02, ID_03, ID_04, ID_05)));

            verifyNoInteractions(navigationService);

            assertAll(
                    () -> assertThat(result.message()).isEqualTo(EXPECTED_INVALID_SELECTION),
                    () -> assertThat(result.fieldErrors()).hasSize(1),
                    () -> assertThat(result.fieldErrors().get(0).field())
                            .isEqualTo(EXPECTED_SELECTOR_PROPERTY),
                    () -> assertThat(result.fieldErrors().get(0).bmsFieldId())
                            .isEqualTo(EXPECTED_THIRD_SELECTOR_FIELD),
                    () -> assertThat(result.fieldErrors().get(0).state())
                            .isEqualTo(ValidationException.FieldState.INVALID),
                    () -> assertThat(result.fieldErrors().get(0).message())
                            .isEqualTo(EXPECTED_INVALID_SELECTION));
        }

        @Test
        @DisplayName("an unsupported row action reports its message and still pages, because the send in"
                + " that arm is commented out at line 202 and execution falls through")
        void anUnsupportedRowActionReportsItsMessageAndStillPages() {
            stubOrderedCluster(twentyFiveRowCluster());

            final TransactionListService.TransactionListResult result = service.listTransactions(
                    submittedEnter(null, List.of("X"), List.of(ID_01)));

            assertAll(
                    () -> assertThat(result.message()).isEqualTo(EXPECTED_INVALID_SELECTION),
                    () -> assertThat(result.rows()).hasSize(EXPECTED_PAGE_SIZE),
                    () -> assertThat(identifiersOf(result))
                            .containsExactlyElementsOf(EXPECTED_FIRST_PAGE),
                    () -> assertThat(result.fieldErrors().get(0).bmsFieldId())
                            .isEqualTo(EXPECTED_FIRST_SELECTOR_FIELD),
                    () -> assertThat(result.error()).isFalse());
        }

        @Test
        @DisplayName("a selector with no identifier beside it selects nothing, because the dispatch at"
                + " lines 183-184 requires both fields to be present")
        void aSelectorWithNoIdentifierBesideItSelectsNothing() {
            stubOrderedCluster(twentyFiveRowCluster());

            final TransactionListService.TransactionListResult result = service.listTransactions(
                    submittedEnter(null, List.of("S"), List.of()));

            verifyNoInteractions(navigationService);

            assertAll(
                    () -> assertThat(result.selectedTransactionId()).isEqualTo(BLANK),
                    () -> assertThat(result.message()).isEqualTo(BLANK),
                    () -> assertThat(result.fieldErrors()).isEmpty(),
                    () -> assertThat(result.rows()).hasSize(EXPECTED_PAGE_SIZE));
        }

        @Test
        @DisplayName("a non-numeric filter is rejected and the paragraph still enters the browse, whose"
                + " opening read runs before the error guard stops the rest")
        void aNonNumericFilterIsRejectedAndTheParagraphStillEntersTheBrowse() {
            stubLowEndPage(rowsFor(EXPECTED_FIRST_PAGE));

            final TransactionListService.TransactionListResult result = service.listTransactions(
                    submittedEnter("ABC", List.of(), List.of()));

            verify(transactionScanRepository)
                    .findByTranIdGreaterThanEqualOrderByTranIdAsc(eq(LOW_END_BOUND), any());
            verifyNoMoreInteractions(transactionScanRepository);

            assertAll(
                    () -> assertThat(result.error()).isTrue(),
                    () -> assertThat(result.message()).isEqualTo(EXPECTED_NOT_NUMERIC),
                    () -> assertThat(result.rows()).isEmpty(),
                    () -> assertThat(result.fieldErrors()).hasSize(1),
                    () -> assertThat(result.fieldErrors().get(0).field())
                            .isEqualTo(EXPECTED_FILTER_PROPERTY),
                    () -> assertThat(result.fieldErrors().get(0).bmsFieldId())
                            .isEqualTo(EXPECTED_FOCUS_FIELD),
                    () -> assertThat(result.fieldErrors().get(0).state())
                            .isEqualTo(ValidationException.FieldState.INVALID),
                    () -> assertThat(result.transactionIdFilterEcho()).isEqualTo("ABC"),
                    () -> assertThat(result.pageMetadata().displayedPageNumber())
                            .isEqualTo(PAGE_INDICATOR_ZERO));
        }

        @Test
        @DisplayName("a numeric filter positions the browse on its characters, so the sixteen leading"
                + " zeros of the key reach the store unparsed")
        void aNumericFilterPositionsTheBrowseOnItsCharacters() {
            stubOrderedCluster(twentyFiveRowCluster());

            final TransactionListService.TransactionListResult result = service.listTransactions(
                    submittedEnter(ID_05, List.of(), List.of()));

            final ArgumentCaptor<String> boundCaptor = ArgumentCaptor.forClass(String.class);
            verify(transactionScanRepository)
                    .findByTranIdGreaterThanEqualOrderByTranIdAsc(boundCaptor.capture(), any());

            assertAll(
                    () -> assertThat(boundCaptor.getValue()).isEqualTo(ID_05),
                    () -> assertThat(encodedByteLength(boundCaptor.getValue())).isEqualTo(16),
                    () -> assertThat(result.error()).isFalse(),
                    () -> assertThat(result.transactionIdFilterEcho()).isEqualTo(BLANK));
        }

        @ParameterizedTest(name = "a filter of [{0}] browses from the low end")
        @ValueSource(strings = {"", " ", "                "})
        @DisplayName("a blank filter browses from the low end of the cluster and the echoed field is"
                + " cleared, because a fixed-width field of spaces is blank")
        void aBlankFilterBrowsesFromTheLowEndAndClearsTheEchoedField(final String blankFilter) {
            stubOrderedCluster(twentyFiveRowCluster());

            final TransactionListService.TransactionListResult result = service.listTransactions(
                    submittedEnter(blankFilter, List.of(), List.of()));

            final ArgumentCaptor<String> boundCaptor = ArgumentCaptor.forClass(String.class);
            verify(transactionScanRepository)
                    .findByTranIdGreaterThanEqualOrderByTranIdAsc(boundCaptor.capture(), any());

            assertAll(
                    () -> assertThat(boundCaptor.getValue()).isEqualTo(LOW_END_BOUND),
                    () -> assertThat(identifiersOf(result))
                            .containsExactlyElementsOf(EXPECTED_FIRST_PAGE),
                    () -> assertThat(result.transactionIdFilterEcho()).isEqualTo(BLANK),
                    () -> assertThat(result.error()).isFalse());
        }

        @Test
        @DisplayName("a first entry discards every submitted screen field, because the map is never"
                + " received on that path and line 114 blanks the shared area")
        void aFirstEntryDiscardsEverySubmittedScreenField() {
            stubOrderedCluster(twentyFiveRowCluster());

            final TransactionListService.TransactionListResult result = service.listTransactions(
                    firstEntryWith("ABC", List.of("X"), List.of(ID_03)));

            verifyNoInteractions(navigationService);

            assertAll(
                    () -> assertThat(identifiersOf(result))
                            .containsExactlyElementsOf(EXPECTED_FIRST_PAGE),
                    () -> assertThat(result.error()).isFalse(),
                    () -> assertThat(result.fieldErrors()).isEmpty(),
                    () -> assertThat(result.message()).isEqualTo(BLANK),
                    () -> assertThat(result.transactionIdFilterEcho()).isEqualTo(BLANK),
                    () -> assertThat(result.selectedTransactionId()).isEqualTo(BLANK),
                    () -> assertThat(result.reEntry()).isTrue());
        }
    }

    // ------------------------------------------------------------------------------------------
    // Paragraphs POPULATE-HEADER-INFO line 567, SEND-TRNLST-SCREEN line 527, and the three
    // catch-all arms of STARTBR line 612, READNEXT line 646 and READPREV line 680
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Screen header, and the failure arm each browse paragraph carries")
    final class ScreenLifecycleAndFailureArms {

        @Test
        @DisplayName("the header carries both catalogue titles, this screen's transaction and program, and"
                + " the injected clock's instant rendered as the map's two eight-character fields")
        void theHeaderCarriesBothCatalogueTitlesAndTheFixedInstant() {
            stubOrderedCluster(twentyFiveRowCluster());
            when(messageCatalogService.screenTitle01()).thenReturn(STUBBED_SCREEN_TITLE_01);
            when(messageCatalogService.screenTitle02()).thenReturn(STUBBED_SCREEN_TITLE_02);

            final TransactionListService.TransactionListResult result =
                    service.listTransactions(firstEntry());

            assertAll(
                    () -> assertThat(result.screenTitle01()).isEqualTo(STUBBED_SCREEN_TITLE_01),
                    () -> assertThat(result.screenTitle02()).isEqualTo(STUBBED_SCREEN_TITLE_02),
                    () -> assertThat(result.currentDate()).isEqualTo(EXPECTED_HEADER_DATE),
                    () -> assertThat(result.currentTime()).isEqualTo(EXPECTED_HEADER_TIME),
                    () -> assertThat(result.transactionName()).isEqualTo(EXPECTED_TRANSACTION_NAME),
                    () -> assertThat(result.programName()).isEqualTo(EXPECTED_PROGRAM_NAME));
        }

        @Test
        @DisplayName("an opening browse that fails raises the error flag with the unable-to-look-up"
                + " message, which is the catch-all arm the three browse paragraphs share")
        void anOpeningBrowseThatFailsRaisesTheErrorFlagWithTheUnableToLookUpMessage() {
            when(transactionScanRepository
                    .findByTranIdGreaterThanEqualOrderByTranIdAsc(eq(LOW_END_BOUND), any()))
                    .thenThrow(new QueryTimeoutException("simulated store failure"));

            final TransactionListService.TransactionListResult result =
                    service.listTransactions(firstEntry());

            assertAll(
                    () -> assertThat(result.error()).isTrue(),
                    () -> assertThat(result.message()).isEqualTo(EXPECTED_UNABLE_TO_LOOKUP),
                    () -> assertThat(result.rows()).isEmpty(),
                    () -> assertThat(result.focusScreenFieldId()).isEqualTo(EXPECTED_FOCUS_FIELD));
        }

        @Test
        @DisplayName("a forward continuation read that fails reaches the forward read's own failure arm,"
                + " leaving the ten rows already assembled in place with the error flag raised")
        void aForwardContinuationReadThatFailsReachesTheForwardReadsFailureArm() {
            when(transactionScanRepository
                    .findByTranIdGreaterThanEqualOrderByTranIdAsc(eq(ID_10), any()))
                    .thenReturn(rowsFor(FORWARD_READ_ORDER_FROM_TEN));
            when(transactionScanRepository.findByTranIdGreaterThanOrderByTranIdAsc(eq(ID_20), any()))
                    .thenThrow(new QueryTimeoutException("simulated store failure"));

            final TransactionListService.TransactionListResult result =
                    service.listTransactions(reEntry(KeyAction.PFK08, ID_01, ID_10, true, 1));

            assertAll(
                    () -> assertThat(identifiersOf(result))
                            .containsExactlyElementsOf(EXPECTED_SECOND_PAGE),
                    () -> assertThat(result.error()).isTrue(),
                    () -> assertThat(result.message()).isEqualTo(EXPECTED_UNABLE_TO_LOOKUP),
                    () -> assertThat(result.pageMetadata().hasMorePages()).isFalse(),
                    () -> assertThat(result.pageMetadata().displayedPageNumber())
                            .isEqualTo(PAGE_INDICATOR_TWO));
        }

        @Test
        @DisplayName("a backward continuation read that fails reaches the backward read's own failure arm,"
                + " and the page number is pinned to one rather than decremented")
        void aBackwardContinuationReadThatFailsReachesTheBackwardReadsFailureArm() {
            when(transactionScanRepository
                    .findByTranIdLessThanEqualOrderByTranIdDesc(eq(ID_11), any()))
                    .thenReturn(rowsFor(BACKWARD_READ_ORDER));
            when(transactionScanRepository.findByTranIdLessThanOrderByTranIdDesc(eq(ID_01), any()))
                    .thenThrow(new QueryTimeoutException("simulated store failure"));

            final TransactionListService.TransactionListResult result =
                    service.listTransactions(reEntry(KeyAction.PFK07, ID_11, ID_20, true, 2));

            assertAll(
                    () -> assertThat(identifiersOf(result))
                            .containsExactlyElementsOf(EXPECTED_FIRST_PAGE),
                    () -> assertThat(result.error()).isTrue(),
                    () -> assertThat(result.message()).isEqualTo(EXPECTED_UNABLE_TO_LOOKUP),
                    () -> assertThat(result.pageMetadata().displayedPageNumber())
                            .isEqualTo(PAGE_INDICATOR_ONE));
        }

        @Test
        @DisplayName("a backward opening browse that fails stops the backward paragraph at its own error"
                + " guard, so no page is assembled and the page number is left where it stood")
        void aBackwardOpeningBrowseThatFailsStopsTheParagraphAtItsErrorGuard() {
            when(transactionScanRepository
                    .findByTranIdLessThanEqualOrderByTranIdDesc(eq(ID_11), any()))
                    .thenThrow(new QueryTimeoutException("simulated store failure"));

            final TransactionListService.TransactionListResult result =
                    service.listTransactions(reEntry(KeyAction.PFK07, ID_11, ID_20, true, 2));

            assertAll(
                    () -> assertThat(result.error()).isTrue(),
                    () -> assertThat(result.message()).isEqualTo(EXPECTED_UNABLE_TO_LOOKUP),
                    () -> assertThat(result.rows()).isEmpty(),
                    () -> assertThat(result.pageMetadata().displayedPageNumber())
                            .isEqualTo(PAGE_INDICATOR_TWO),
                    () -> assertThat(result.pageMetadata().direction())
                            .isEqualTo(BrowseWindow.PagingDirection.BACKWARD));
        }

        @Test
        @DisplayName("a backward request with no retained first key over an empty cluster resolves no low"
                + " end at all, so the browse cannot be positioned and the guard read finds none open")
        void aBackwardRequestOverAnEmptyClusterCannotResolveALowEnd() {
            when(transactionScanRepository
                    .findByTranIdGreaterThanEqualOrderByTranIdAsc(eq(LOW_END_BOUND), any()))
                    .thenReturn(List.of());

            final TransactionListService.TransactionListResult result =
                    service.listTransactions(reEntry(KeyAction.PFK07, null, ID_10, true, 2));

            final ArgumentCaptor<Limit> limitCaptor = ArgumentCaptor.forClass(Limit.class);
            verify(transactionScanRepository).findByTranIdGreaterThanEqualOrderByTranIdAsc(
                    eq(LOW_END_BOUND), limitCaptor.capture());
            verifyNoMoreInteractions(transactionScanRepository);

            assertAll(
                    () -> assertThat(limitCaptor.getValue().max()).isEqualTo(LOW_END_RESOLUTION_ROWS),
                    () -> assertThat(result.rows()).isEmpty(),
                    () -> assertThat(result.error()).isTrue(),
                    () -> assertThat(result.message()).isEqualTo(EXPECTED_UNABLE_TO_LOOKUP),
                    () -> assertThat(result.pageMetadata().displayedPageNumber())
                            .isEqualTo(PAGE_INDICATOR_TWO));
        }

        @Test
        @DisplayName("a filter above every stored key cannot be positioned, so the browse reports the top"
                + " of the cluster with the error flag deliberately left off")
        void aFilterAboveEveryStoredKeyCannotBePositionedAndReportsTheTop() {
            when(transactionScanRepository.findByTranIdGreaterThanEqualOrderByTranIdAsc(
                    eq(ID_ABOVE_EVERY_STORED_KEY), any())).thenReturn(List.of());

            final TransactionListService.TransactionListResult result = service.listTransactions(
                    submittedEnter(ID_ABOVE_EVERY_STORED_KEY, List.of(), List.of()));

            assertAll(
                    () -> assertThat(result.rows()).isEmpty(),
                    () -> assertThat(result.message()).isEqualTo(EXPECTED_AT_TOP),
                    () -> assertThat(result.error()).isFalse(),
                    () -> assertThat(result.pageMetadata().displayedPageNumber())
                            .isEqualTo(PAGE_INDICATOR_ZERO));
        }
    }

    // ------------------------------------------------------------------------------------------
    // The carried shapes, and the boundary values a caller can legitimately submit
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Carried shapes and boundary input: nothing absent escapes as a runtime failure")
    final class CarriedShapesAndBoundaryInput {

        @Test
        @DisplayName("the service refuses a turn with no command at all")
        void theServiceRefusesATurnWithNoCommand() {
            assertThatThrownBy(() -> service.listTransactions(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("command");
        }

        @Test
        @DisplayName("the constructor refuses each absent collaborator, naming the one that is missing")
        void theConstructorRefusesEachAbsentCollaborator() {
            assertAll(
                    () -> assertThatThrownBy(() -> new TransactionListService(null,
                            messageCatalogService, navigationService, FIXED_CLOCK))
                            .isInstanceOf(NullPointerException.class)
                            .hasMessageContaining("transactionScanRepository"),
                    () -> assertThatThrownBy(() -> new TransactionListService(
                            transactionScanRepository, null, navigationService, FIXED_CLOCK))
                            .isInstanceOf(NullPointerException.class)
                            .hasMessageContaining("messageCatalogService"),
                    () -> assertThatThrownBy(() -> new TransactionListService(
                            transactionScanRepository, messageCatalogService, null, FIXED_CLOCK))
                            .isInstanceOf(NullPointerException.class)
                            .hasMessageContaining("navigationService"),
                    () -> assertThatThrownBy(() -> new TransactionListService(
                            transactionScanRepository, messageCatalogService, navigationService,
                            null))
                            .isInstanceOf(NullPointerException.class)
                            .hasMessageContaining("clock"));
        }

        @Test
        @DisplayName("the command refuses an absent attention key, because two paging guards branch on it")
        void theCommandRefusesAnAbsentAttentionKey() {
            assertThatThrownBy(() -> new TransactionListService.TransactionListCommand(null,
                    ScreenNavigationState.empty().withReEntry(), null, List.of(), List.of(), null,
                    false, 0))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("keyAction");
        }

        @Test
        @DisplayName("the command refuses a negative page number, because the legacy field is unsigned")
        void theCommandRefusesANegativePageNumber() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new TransactionListService.TransactionListCommand(
                            KeyAction.ENTER, ScreenNavigationState.empty().withReEntry(), null,
                            List.of(), List.of(), null, false, -1))
                    .withMessageContaining("currentPageNumber");
        }

        @Test
        @DisplayName("the command accepts a page number of zero and normalises its three optional"
                + " aggregates and its cursor, so nothing downstream has to test for absence")
        void theCommandAcceptsAZeroPageNumberAndNormalisesItsOptionalAggregates() {
            final TransactionListService.TransactionListCommand command =
                    new TransactionListService.TransactionListCommand(KeyAction.ENTER, null, null,
                            null, null, null, false, 0);

            assertAll(
                    () -> assertThat(command.currentPageNumber()).isZero(),
                    () -> assertThat(command.navigationContext())
                            .isEqualTo(ScreenNavigationState.empty()),
                    () -> assertThat(command.rowSelectors()).isEmpty(),
                    () -> assertThat(command.displayedTransactionIds()).isEmpty(),
                    () -> assertThat(command.pageCursor()).isNotNull(),
                    () -> assertThat(command.pageCursor().previousCursorKey()).isNull(),
                    () -> assertThat(command.pageCursor().nextCursorKey()).isNull(),
                    () -> assertThat(command.pageCursor().direction()).isNull(),
                    () -> assertThat(command.transactionIdFilter()).isNull());
        }

        @Test
        @DisplayName("a backward request standing at page zero with no retained keys is refused with the"
                + " already-at-the-top report and no read, and raises nothing")
        void aBackwardRequestAtPageZeroIsRefusedWithoutAnyRuntimeFailure() {
            final TransactionListService.TransactionListResult result =
                    service.listTransactions(reEntry(KeyAction.PFK07, null, null, false, 0));

            verifyNoInteractions(transactionScanRepository);

            assertAll(
                    () -> assertThat(result.message()).isEqualTo(EXPECTED_ALREADY_AT_TOP),
                    () -> assertThat(result.rows()).isEmpty(),
                    () -> assertThat(result.eraseScreen()).isFalse(),
                    () -> assertThat(result.error()).isFalse(),
                    () -> assertThat(result.pageMetadata().displayedPageNumber())
                            .isEqualTo(PAGE_INDICATOR_ZERO),
                    () -> assertThat(result.pageMetadata().previousCursorKey()).isNull(),
                    () -> assertThat(result.pageMetadata().nextCursorKey()).isNull());
        }

        @Test
        @DisplayName("the result's two collections are unmodifiable, so a turn's outcome cannot be altered"
                + " after the turn that produced it has ended")
        void theResultCollectionsAreUnmodifiable() {
            stubOrderedCluster(twentyFiveRowCluster());

            final TransactionListService.TransactionListResult result =
                    service.listTransactions(firstEntry());

            assertAll(
                    () -> assertThatThrownBy(() -> result.rows().add(null))
                            .isInstanceOf(UnsupportedOperationException.class),
                    () -> assertThatThrownBy(() -> result.fieldErrors().add(null))
                            .isInstanceOf(UnsupportedOperationException.class),
                    () -> assertThat(result.fieldErrors()).isNotNull());
        }

        @Test
        @DisplayName("the result normalises absent collections into empty unmodifiable views, so a caller"
                + " assembling one by hand cannot hand a null collection downstream")
        void theResultNormalisesAbsentCollectionsIntoEmptyUnmodifiableViews() {
            final TransactionListService.TransactionListResult result =
                    new TransactionListService.TransactionListResult(
                            NavigationService.Route.TRANSACTION_LIST,
                            ScreenNavigationState.empty(),
                            null,
                            BrowseWindow.forward(EXPECTED_PAGE_SIZE, null, null, false, false,
                                    PAGE_INDICATOR_ZERO),
                            BLANK,
                            null,
                            EXPECTED_FOCUS_FIELD,
                            false,
                            false,
                            true,
                            BLANK,
                            BLANK,
                            BLANK,
                            BLANK,
                            BLANK,
                            BLANK,
                            EXPECTED_TRANSACTION_NAME,
                            EXPECTED_PROGRAM_NAME);

            assertAll(
                    () -> assertThat(result.rows()).isNotNull().isEmpty(),
                    () -> assertThat(result.fieldErrors()).isNotNull().isEmpty(),
                    () -> assertThatThrownBy(() -> result.rows().add(null))
                            .isInstanceOf(UnsupportedOperationException.class),
                    () -> assertThatThrownBy(() -> result.fieldErrors().add(null))
                            .isInstanceOf(UnsupportedOperationException.class));
        }

        @Test
        @DisplayName("every carried shape withholds its record keys from its diagnostic rendering, while"
                + " its accessors continue to carry them in full")
        void everyCarriedShapeWithholdsItsRecordKeysFromItsDiagnosticRendering() {
            stubLowEndPage(List.of(transaction(ID_01)));

            final TransactionListService.TransactionListCommand command =
                    submittedEnter(ID_05, List.of("S"), List.of(ID_03));
            final TransactionListService.TransactionListResult result =
                    service.listTransactions(firstEntry());
            final TransactionListService.TransactionListRow row = result.rows().get(0);

            assertAll(
                    () -> assertThat(command).hasToString(command.toString()),
                    () -> assertThat(command.toString()).contains(EXPECTED_REDACTION),
                    () -> assertThat(command.toString()).doesNotContain(ID_05),
                    () -> assertThat(command.toString()).doesNotContain(ID_03),
                    () -> assertThat(command.transactionIdFilter()).isEqualTo(ID_05),
                    () -> assertThat(result.toString()).contains(EXPECTED_REDACTION),
                    () -> assertThat(result.toString()).doesNotContain(ID_01),
                    () -> assertThat(row.toString()).contains(EXPECTED_REDACTION),
                    () -> assertThat(row.toString()).doesNotContain(ID_01),
                    () -> assertThat(row.toString()).doesNotContain(CARD_NUMBER),
                    () -> assertThat(row.toString()).doesNotContain(DESCRIPTION),
                    () -> assertThat(row.tranId()).isEqualTo(ID_01),
                    () -> assertThat(row.tranCardNum()).isEqualTo(CARD_NUMBER));
        }
    }
}
