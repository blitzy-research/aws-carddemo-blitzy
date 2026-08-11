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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.domain.Transaction;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.support.TestDataFactory;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Unit test for {@link TransactionViewService}, the translation of transaction {@code CT01} carried by
 * {@code app/cbl/COTRN01C.cbl} &mdash; 330 lines and <strong>9 paragraphs</strong>, each of which is
 * named by a test in {@link ParagraphTraceability} so all nine rows of the paragraph traceability
 * matrix are attributable to this class.
 *
 * <p><strong>What is under test.</strong> One pseudo-conversational turn of a screen whose whole job is
 * a single keyed read of the 350-byte transaction record. The service decides, from the echoed
 * navigation state and the decoded attention key alone, whether to transfer away without sending,
 * present an empty screen on a first entry, follow an identifier the transaction-list screen carried
 * forward, or receive a submitted identifier and look it up.
 *
 * <p><strong>The decisive assertions are about representation, not control flow.</strong> This is the
 * smallest online member in the package and its risk is quiet type drift: an identifier parsed to a
 * number, an amount rounded rather than truncated, a timestamp normalised by a date-time library, a
 * fixed-width code trimmed. Every one of those would compile, would read plausibly, and would break
 * byte parity. Four assertions are therefore written to fail if the corresponding shortcut is ever
 * taken:
 *
 * <ul>
 *   <li>a zero-filled sixteen-character identifier reads a row while its unpadded form reads nothing,
 *       which is the assertion that catches a numeric or trimmed key &mdash; see
 *       {@link TransactionIdentifierKeyFidelity};</li>
 *   <li>a stored online-form stamp and a stored batch-form stamp both come back byte for byte and are
 *       still unequal to one another, which is the assertion that catches a unification of the two
 *       26-character forms &mdash; see {@link TimestampFidelity};</li>
 *   <li>a space-padded source code keeps its trailing spaces, which is the assertion that catches a
 *       trim &mdash; see {@link FixedWidthFidelity};</li>
 *   <li>a surplus fractional digit is truncated toward zero and never rounded up, which is the
 *       assertion that catches the conventional rounding mode.</li>
 * </ul>
 *
 * <p><strong>Two 26-character timestamp forms coexist in the estate and are never unified.</strong> The
 * online writers produce a form with a space at position eleven, colons between the time parts and a
 * fraction that is invariably all zeros; the batch writers produce a form with a hyphen before the
 * hour, dots between the time parts and two hundredths digits followed by four literal zeros. Both are
 * twenty-six characters, both live in the same column at record offsets 278 and 304, and a row written
 * by either writer is displayed by this screen. Both literals are authored character for character
 * below, so an assertion that they are unchanged compares against characters this file owns rather than
 * against anything the code under test could also have altered.
 *
 * <p><strong>Every collaborator is a test double and the expected values are this file's own.</strong>
 * The repository is the data boundary, and the message catalogue and the navigation rules are doubled
 * as well so that a pass-through assertion proves pass-through: a padded message asserted against a
 * value this file both supplied and expected cannot be satisfied by the service substituting a value of
 * its own. No expected value anywhere in this class is produced by the class under test or by any
 * production formatter, codec, template or record mapper. The clock is fixed so the rendered header is
 * reproducible.
 *
 * <p><strong>Collaborators this member does not have.</strong> There is no abend service and no
 * attention-key translator, because {@code COTRN01C} declares no abend handler and includes no
 * attention-key copybook &mdash; that family is {@code COACTUPC}, {@code COACTVWC}, {@code COCRDLIC},
 * {@code COCRDSLC} and {@code COCRDUPC}. Neither type is mocked, imported or referenced here.
 *
 * <p><strong>This is a surefire unit test.</strong> No container, no application context, no database
 * connection, no bound port, no network call and no filesystem access outside the classpath.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionViewService - the transaction-view screen, transaction CT01")
class TransactionViewServiceTest {

    // ==============================================================================================
    // Independent oracles. Every expected value below is authored here, never derived from production
    // code, so an assertion cannot be satisfied by the same defect it is meant to catch.
    // ==============================================================================================

    /**
     * A stored identifier at its full sixteen characters, zero filled exactly as the estate stores it.
     *
     * <p>Written out as a literal rather than formatted from a number, because formatting would make
     * this file agree with a numeric reading of the key rather than test against one.
     */
    private static final String PADDED_ID = "0000000000000001";

    /**
     * The same identifier with its zero fill stripped: a <em>different</em> key, and one that reads
     * nothing. A translation that parses the key, trims it, or compares it numerically resolves this
     * value, and only this file's assertions would notice.
     */
    private static final String UNPADDED_ID = "1";

    /** A second stored identifier, for the tests that need two distinct rows. */
    private static final String OTHER_PADDED_ID = "0000000000000002";

    /** Seventeen characters: one wider than the key space, so it can name no row at all. */
    private static final String OVERLONG_ID = "00000000000000012";

    /**
     * A sixteen-character identifier carrying letters. The record key is alphanumeric with no
     * character-class constraint anywhere in the source, so this value must be accepted and looked up
     * rather than rejected by an invented digit test.
     */
    private static final String ALPHANUMERIC_ID = "ABCDEF0123456789";

    /**
     * The online 26-character timestamp form: a space at position eleven, colons between the time parts
     * and an all-zero fraction.
     */
    private static final String ONLINE_TIMESTAMP = "2022-06-10 19:27:53.000000";

    /**
     * The batch 26-character timestamp form: a hyphen before the hour, dots between the time parts, and
     * two hundredths digits followed by four literal zeros.
     */
    private static final String BATCH_TIMESTAMP = "2022-01-02-03.04.05.060000";

    /**
     * An entirely blank processing timestamp, which is the state of a transaction that has not been
     * processed. This is a live production shape rather than an edge case: every record of the
     * daily-transaction fixture carries one.
     */
    private static final String BLANK_TIMESTAMP = " ".repeat(26);

    /** {@code TRAN-SOURCE} as stored: ten characters, so an eight-character value carries two spaces. */
    private static final String PADDED_SOURCE = "POS TERM" + " ".repeat(2);

    /**
     * The common invalid-key message at its contractual width: forty visible characters followed by ten
     * trailing spaces. The padding is written as an explicit repeat count so it cannot be mistaken for
     * counted whitespace, and it is never trimmed.
     */
    private static final String INVALID_KEY_MESSAGE =
            "Invalid key pressed. Please see below..." + " ".repeat(10);

    /** The first screen title at its contractual width: centred within a forty-character field. */
    private static final String SCREEN_TITLE_01 =
            " ".repeat(6) + "AWS Mainframe Modernization" + " ".repeat(7);

    /** The second screen title at its contractual width: centred within a forty-character field. */
    private static final String SCREEN_TITLE_02 = " ".repeat(14) + "CardDemo" + " ".repeat(18);

    /** The emptiness message of the enter-key cascade, byte for byte. */
    private static final String MSG_TRAN_ID_EMPTY = "Tran ID can NOT be empty...";

    /** The record-absent message of the read, byte for byte. */
    private static final String MSG_TRAN_ID_NOT_FOUND = "Transaction ID NOT found...";

    /** The lookup-failure message of the read, byte for byte. */
    private static final String MSG_LOOKUP_FAILED = "Unable to lookup Transaction...";

    /** The route token this screen re-presents itself on, authored rather than imported. */
    private static final String ROUTE_TRANSACTION_VIEW = "transaction-view";

    /** This screen's own transaction identifier, which the pseudo-conversational return re-arms. */
    private static final String THIS_TRANSACTION_ID = "CT01";

    /** This screen's own program name, which a transfer stamps as the originator. */
    private static final String THIS_PROGRAM_NAME = "COTRN01C";

    /**
     * The CICS program that is declared and bound in the resource definition but has no source member:
     * declared at {@code app/csd/CARDDEMO.CSD} line 211 and bound at line 390. No route may resolve to
     * it, because there is nothing to route to.
     */
    private static final String DANGLING_CSD_PROGRAM = "COCRDSEC";

    /** The only screen field this member ever positions the cursor on. */
    private static final String CURSOR_FIELD = "TRNIDIN";

    /** The property name the per-field detail carries for that field. */
    private static final String CURSOR_PROPERTY = "transactionId";

    // ==============================================================================================
    // Contractual widths. Measured on encoded bytes throughout, never on character counts.
    // ==============================================================================================

    /** 16: the width of the transaction key, declared three times over in the source. */
    private static final int KEY_BYTES = 16;

    /** 26: the width of each of the two timestamp fields, at record offsets 278 and 304. */
    private static final int TIMESTAMP_BYTES = 26;

    /** 10: the width of the source code, whose trailing spaces are significant. */
    private static final int SOURCE_BYTES = 10;

    /** 50: the width of a common message. */
    private static final int COMMON_MESSAGE_BYTES = 50;

    /** 40: the width of a screen title. */
    private static final int SCREEN_TITLE_BYTES = 40;

    /** 2: the scale every monetary value in the estate carries. */
    private static final int MONETARY_SCALE = 2;

    /** 100: the width of the description in the record, against 60 on the screen. */
    private static final int DESCRIPTION_BYTES = 100;

    // ==============================================================================================
    // The fixed clock and the header it renders to
    // ==============================================================================================

    /** The instant the fixed clock reports, chosen so the rendered header is unambiguous. */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-19T14:23:45.123456Z");

    /** The header date the fixed instant renders to, with the year reduced to two digits. */
    private static final String EXPECTED_HEADER_DATE = "07/19/22";

    /** The header time the fixed instant renders to, on a 24-hour clock. */
    private static final String EXPECTED_HEADER_TIME = "14:23:45";

    // ==============================================================================================
    // Doubles. The repository is the data boundary; the catalogue and the navigation rules are doubled
    // so that a pass-through assertion genuinely proves pass-through.
    // ==============================================================================================

    /** The transaction file. Only the inherited single-key lookup may ever be reached. */
    @Mock
    private TransactionRepository transactionRepository;

    /** The common-message catalogue, supplying the padded invalid-key text and the two titles. */
    @Mock
    private MessageCatalogService messageCatalogService;

    /** The navigation rules, which every destination on every transfer path must be resolved from. */
    @Mock
    private NavigationService navigationService;

    /** The service under test, rebuilt for every test so no turn can observe another. */
    private TransactionViewService service;

    @BeforeEach
    void createService() {
        this.service = new TransactionViewService(this.transactionRepository,
                this.messageCatalogService,
                this.navigationService,
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
    }

    // ==============================================================================================
    // Fixtures. Built with the entity's own constructor wherever a value must reach the service exactly
    // as authored, because the shared factory normalises an amount to the record scale on its way in
    // and that normalisation would hide the very truncation this file has to prove.
    // ==============================================================================================

    /**
     * Builds the canonical stored row: the padded identifier, the online form in the origination stamp,
     * the batch form in the processing stamp, a padded source code and an amount already at the
     * record's scale.
     *
     * @return the row every retrieval test reads
     */
    private static Transaction storedRow() {
        return new Transaction(PADDED_ID,
                "01",
                "0005",
                PADDED_SOURCE,
                "AMAZON.COM PURCHASE",
                new BigDecimal("1234.56"),
                "123456789",
                "AMAZON RETAIL LLC",
                "SEATTLE",
                "98109",
                "4111111111111111",
                ONLINE_TIMESTAMP,
                BATCH_TIMESTAMP);
    }

    /**
     * Builds a stored row carrying the given amount and nothing else of interest.
     *
     * @param amount the amount to store, which may be at a scale the record cannot hold
     * @return a row whose amount is exactly the value supplied
     */
    private static Transaction rowWithAmount(final BigDecimal amount) {
        return new Transaction(PADDED_ID, "01", "0005", PADDED_SOURCE, "PURCHASE", amount,
                "123456789", "MERCHANT", "CITY", "98109", "4111111111111111",
                ONLINE_TIMESTAMP, BATCH_TIMESTAMP);
    }

    /**
     * Builds a stored row carrying the two timestamps supplied and nothing else of interest.
     *
     * @param originationTimestamp the 26-character origination stamp to store
     * @param processingTimestamp  the 26-character processing stamp to store
     * @return a row whose stamps are exactly the values supplied
     */
    private static Transaction rowWithTimestamps(final String originationTimestamp,
            final String processingTimestamp) {
        return new Transaction(PADDED_ID, "01", "0005", PADDED_SOURCE, "PURCHASE",
                new BigDecimal("1234.56"), "123456789", "MERCHANT", "CITY", "98109",
                "4111111111111111", originationTimestamp, processingTimestamp);
    }

    /**
     * Builds the navigation state of a turn that has already presented the screen once, which is the
     * state every attention key is dispatched from.
     *
     * @param fromProgram the originating-program component, which back-navigation reads
     * @return a re-entered navigation state
     */
    private static ScreenNavigationState reEnteredState(final String fromProgram) {
        return new ScreenNavigationState(null, fromProgram, null, null, "USER0001", "U",
                ScreenNavigationState.ProgramContext.REENTER,
                null, null, null, null, null, null, null, null, null);
    }

    /**
     * Builds the navigation state of a turn arriving at the screen for the first time.
     *
     * @return a first-entry navigation state
     */
    private static ScreenNavigationState firstEntryState() {
        return new ScreenNavigationState(null, null, null, null, "USER0001", "U",
                ScreenNavigationState.ProgramContext.ENTER,
                null, null, null, null, null, null, null, null, null);
    }

    /**
     * Submits a re-entered turn carrying the given search field and attention key.
     *
     * @param submittedId the transmitted search field, which may be {@code null}
     * @param keyAction   the decoded attention key, which may be {@code null}
     * @return the outcome of the turn
     */
    private TransactionViewService.TransactionViewResult submit(final String submittedId,
            final KeyAction keyAction) {
        return this.service.viewTransaction(new TransactionViewService.TransactionViewInput(
                submittedId, null, keyAction, reEnteredState(null)));
    }

    /**
     * Measures a value in encoded bytes, which is the only width a fixed-width contract is expressed
     * in. Character counts are never used for a width assertion in this class.
     *
     * @param value the value to measure
     * @return the number of US-ASCII bytes the value encodes to
     */
    private static int encodedByteLength(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    // ==============================================================================================
    // Construction and the entry contract
    // ==============================================================================================

    @Nested
    @DisplayName("Construction and the entry contract")
    class ConstructionAndEntryContract {

        @Test
        @DisplayName("rejects an absent transaction file")
        void rejectsAbsentRepository() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new TransactionViewService(null, messageCatalogService,
                            navigationService, Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)));
        }

        @Test
        @DisplayName("rejects an absent message catalogue")
        void rejectsAbsentCatalogue() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new TransactionViewService(transactionRepository, null,
                            navigationService, Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)));
        }

        @Test
        @DisplayName("rejects absent navigation rules")
        void rejectsAbsentNavigationRules() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new TransactionViewService(transactionRepository,
                            messageCatalogService, null,
                            Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)));
        }

        @Test
        @DisplayName("rejects an absent clock")
        void rejectsAbsentClock() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new TransactionViewService(transactionRepository,
                            messageCatalogService, navigationService, null));
        }

        @Test
        @DisplayName("rejects an absent turn, which is the one contract violation it will not absorb")
        void rejectsAbsentTurn() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.viewTransaction(null));

            verifyNoInteractions(transactionRepository);
            verifyNoInteractions(messageCatalogService);
            verifyNoInteractions(navigationService);
        }

        @Test
        @DisplayName("the inbound record carries its four components verbatim")
        void inboundRecordCarriesItsComponentsVerbatim() {
            final ScreenNavigationState carried = reEnteredState(THIS_PROGRAM_NAME);

            final TransactionViewService.TransactionViewInput input =
                    new TransactionViewService.TransactionViewInput(PADDED_ID, OTHER_PADDED_ID,
                            KeyAction.ENTER, carried);

            assertAll(() -> assertThat(input.transactionIdInput()).isEqualTo(PADDED_ID),
                    () -> assertThat(input.selectedTransactionId()).isEqualTo(OTHER_PADDED_ID),
                    () -> assertThat(input.keyAction()).isEqualTo(KeyAction.ENTER),
                    () -> assertThat(input.navigationContext()).isSameAs(carried));
        }
    }

    // ==============================================================================================
    // THE DECISIVE GROUP. The transaction key is sixteen alphanumeric characters whose zero fill is
    // part of the key, and nothing on this path may parse it, trim it or compare it numerically.
    // ==============================================================================================

    @Nested
    @DisplayName("The sixteen-character key, whose zero fill is significant")
    class TransactionIdentifierKeyFidelity {

        /**
         * The decisive assertion of this file. One stubbing, two submissions, opposite outcomes: the
         * sixteen-character zero-filled form reads the row, and the same identifier with its zero fill
         * stripped reads nothing at all.
         *
         * <p>Any translation that parses the identifier to a numeric type, trims its leading zeros, or
         * compares it numerically would resolve the unpadded form as well, and would pass every other
         * assertion in this class. This test is the one that fails.
         */
        @Test
        @DisplayName("the zero-filled form reads the row while the unpadded form reads nothing")
        void zeroFillIsSignificantToTheKey() {
            when(transactionRepository.findById(PADDED_ID)).thenReturn(Optional.of(storedRow()));

            final TransactionViewService.TransactionViewResult resolved =
                    submit(PADDED_ID, KeyAction.ENTER);
            final TransactionViewService.TransactionViewResult unresolved =
                    submit(UNPADDED_ID, KeyAction.ENTER);

            assertAll(
                    () -> assertThat(resolved.retrievedTransaction()).isPresent(),
                    () -> assertThat(resolved.transaction().transactionId()).isEqualTo(PADDED_ID),
                    () -> assertThat(resolved.message()).isEmpty(),
                    () -> assertThat(resolved.errorFlag()).isFalse(),
                    () -> assertThat(unresolved.retrievedTransaction()).isEmpty(),
                    () -> assertThat(unresolved.transaction()).isNull(),
                    () -> assertThat(unresolved.message()).isEqualTo(MSG_TRAN_ID_NOT_FOUND),
                    () -> assertThat(unresolved.errorFlag()).isTrue());
        }

        /**
         * Captures the key the repository was actually asked for and proves it crossed the boundary
         * untouched: sixteen encoded bytes, zero fill intact, no trim and no reformatting.
         */
        @Test
        @DisplayName("the key reaches the file as sixteen encoded bytes with its zero fill intact")
        void keyReachesTheFileUntrimmedAndUnparsed() {
            when(transactionRepository.findById(PADDED_ID)).thenReturn(Optional.of(storedRow()));

            submit(PADDED_ID, KeyAction.ENTER);

            final ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            verify(transactionRepository).findById(keyCaptor.capture());
            final String submittedKey = keyCaptor.getValue();

            assertAll(() -> assertThat(submittedKey).isEqualTo(PADDED_ID),
                    () -> assertThat(encodedByteLength(submittedKey)).isEqualTo(KEY_BYTES),
                    () -> assertThat(submittedKey).startsWith("000000000000000"),
                    () -> assertThat(submittedKey).isNotEqualTo(UNPADDED_ID),
                    () -> assertThat(submittedKey).isNotEqualTo(submittedKey.replace("0", "")),
                    () -> assertThat(submittedKey).doesNotContain(" "));
        }

        /**
         * The unpadded submission is passed on exactly as submitted, at one encoded byte. A translation
         * that padded the key on the way out would resolve a row the legacy screen does not resolve,
         * which is the mirror image of the trimming defect.
         */
        @Test
        @DisplayName("an unpadded key is passed on unpadded, at its own encoded width")
        void unpaddedKeyIsPassedOnUnpadded() {
            submit(UNPADDED_ID, KeyAction.ENTER);

            final ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            verify(transactionRepository).findById(keyCaptor.capture());

            assertAll(() -> assertThat(keyCaptor.getValue()).isEqualTo(UNPADDED_ID),
                    () -> assertThat(encodedByteLength(keyCaptor.getValue())).isEqualTo(1));
        }

        @ParameterizedTest(name = "[{0}] is a key in its own right and is passed on verbatim")
        @ValueSource(strings = {"1", "01", "0000000000000001", "0000000000000010",
            "1000000000000000", "ABCDEF0123456789", "000000000000000A"})
        @DisplayName("each form of an identifier is its own key, because none of them is a number")
        void everyFormIsItsOwnKey(final String submitted) {
            submit(submitted, KeyAction.ENTER);

            final ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            verify(transactionRepository).findById(keyCaptor.capture());

            assertThat(keyCaptor.getValue()).isEqualTo(submitted);
        }

        @Test
        @DisplayName("no character-class edit is applied, because the key is alphanumeric")
        void noCharacterClassEditIsApplied() {
            when(transactionRepository.findById(ALPHANUMERIC_ID))
                    .thenReturn(Optional.of(storedRow()));

            final TransactionViewService.TransactionViewResult result =
                    submit(ALPHANUMERIC_ID, KeyAction.ENTER);

            assertAll(() -> assertThat(result.retrievedTransaction()).isPresent(),
                    () -> assertThat(result.errorFlag()).isFalse(),
                    () -> assertThat(result.fieldErrors()).isEmpty(),
                    () -> assertThat(result.searchTransactionId()).isEqualTo(ALPHANUMERIC_ID));
        }

        @Test
        @DisplayName("an identifier at exactly the key width is accepted and looked up")
        void identifierAtTheKeyWidthIsAccepted() {
            when(transactionRepository.findById(PADDED_ID)).thenReturn(Optional.of(storedRow()));

            final TransactionViewService.TransactionViewResult result =
                    submit(PADDED_ID, KeyAction.ENTER);

            assertAll(() -> assertThat(encodedByteLength(PADDED_ID)).isEqualTo(KEY_BYTES),
                    () -> assertThat(result.retrievedTransaction()).isPresent(),
                    () -> assertThat(result.fieldErrors()).isEmpty());
        }

        /**
         * An identifier wider than the key space is refused outright and is never cut to sixteen
         * characters, because cutting could return a different transaction than the one asked for.
         */
        @Test
        @DisplayName("an over-wide identifier is refused, never cut, and reaches no lookup")
        void overWideIdentifierIsRefusedRatherThanCut() {
            final TransactionViewService.TransactionViewResult result =
                    submit(OVERLONG_ID, KeyAction.ENTER);

            assertAll(
                    () -> assertThat(encodedByteLength(OVERLONG_ID)).isEqualTo(KEY_BYTES + 1),
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.message()).isEqualTo(MSG_TRAN_ID_NOT_FOUND),
                    () -> assertThat(result.transaction()).isNull(),
                    () -> assertThat(result.searchTransactionId()).isEqualTo(OVERLONG_ID),
                    () -> assertThat(result.focusField()).isEqualTo(CURSOR_FIELD),
                    () -> assertThat(result.fieldErrors()).singleElement()
                            .satisfies(entry -> assertAll(
                                    () -> assertThat(entry.field()).isEqualTo(CURSOR_PROPERTY),
                                    () -> assertThat(entry.bmsFieldId()).isEqualTo(CURSOR_FIELD),
                                    () -> assertThat(entry.state())
                                            .isEqualTo(ValidationException.FieldState.INVALID),
                                    () -> assertThat(entry.message())
                                            .isEqualTo(MSG_TRAN_ID_NOT_FOUND))));
            verifyNoInteractions(transactionRepository);
        }
    }


    // ==============================================================================================
    // The record-absent and lookup-failure arms of the read. Both are screen outcomes, not exceptions.
    // ==============================================================================================

    @Nested
    @DisplayName("The record-absent and lookup-failure arms of the read")
    class RecordAbsentAndLookupFailure {

        /**
         * The online contract for a missing transaction is a screen message. The production class
         * declares no exception on this path, and the record-absent constant it emits is asserted byte
         * for byte here. {@code RecordNotFoundException} exists in the module for the batch paths that
         * abend; it is deliberately not on this path, and the turn completing normally with a populated
         * outcome is the proof that nothing of the kind escapes.
         */
        @Test
        @DisplayName("a missing identifier yields the legacy message and no escaping exception")
        void missingIdentifierYieldsTheLegacyMessage() {
            final TransactionViewService.TransactionViewResult result =
                    submit(PADDED_ID, KeyAction.ENTER);

            assertAll(() -> assertThat(result).isNotNull(),
                    () -> assertThat(result.message()).isEqualTo(MSG_TRAN_ID_NOT_FOUND),
                    () -> assertThat(encodedByteLength(result.message()))
                            .isEqualTo(encodedByteLength(MSG_TRAN_ID_NOT_FOUND)),
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.transaction()).isNull(),
                    () -> assertThat(result.retrievedTransaction()).isEmpty(),
                    () -> assertThat(result.focusField()).isEqualTo(CURSOR_FIELD),
                    () -> assertThat(result.searchTransactionId()).isEqualTo(PADDED_ID),
                    () -> assertThat(result.reArmedTransactionId()).isEqualTo(THIS_TRANSACTION_ID),
                    () -> assertThat(result.route())
                            .isEqualTo(NavigationService.Route.TRANSACTION_VIEW),
                    () -> assertThat(result.navigationContext()).isNotNull());
        }

        /**
         * An identifier that names no row is an outcome rather than a malformed field, so it produces
         * the message without adding per-field detail. The distinction is what tells a consumer whether
         * to ask the operator to correct a field or to accept that the record simply is not there.
         */
        @Test
        @DisplayName("a record-absent turn adds no per-field detail, because the field was well formed")
        void recordAbsentTurnAddsNoFieldDetail() {
            final TransactionViewService.TransactionViewResult result =
                    submit(PADDED_ID, KeyAction.ENTER);

            assertAll(() -> assertThat(result.fieldErrors()).isEmpty(),
                    () -> assertThat(result.message()).isEqualTo(MSG_TRAN_ID_NOT_FOUND));
        }

        /**
         * The transaction table starts empty after the reference-data seed, so a screen opened before
         * the first posting run reads nothing at all. The record-absent arm is therefore a reachable
         * production state rather than a defensive branch.
         */
        @Test
        @DisplayName("an empty file is the reachable production state the seed leaves behind")
        void anEmptyFileIsAReachableState() {
            when(transactionRepository.findById(PADDED_ID)).thenReturn(Optional.empty());

            final TransactionViewService.TransactionViewResult result =
                    submit(PADDED_ID, KeyAction.ENTER);

            assertAll(() -> assertThat(result.retrievedTransaction()).isEmpty(),
                    () -> assertThat(result.message()).isEqualTo(MSG_TRAN_ID_NOT_FOUND),
                    () -> assertThat(result.errorFlag()).isTrue());
        }

        @Test
        @DisplayName("a failure of the lookup itself is reported on the screen and never propagated")
        void lookupFailureIsReportedAndNeverPropagated() {
            when(transactionRepository.findById(PADDED_ID))
                    .thenThrow(new DataAccessResourceFailureException("the file is unavailable"));

            final TransactionViewService.TransactionViewResult result =
                    submit(PADDED_ID, KeyAction.ENTER);

            assertAll(() -> assertThat(result).isNotNull(),
                    () -> assertThat(result.message()).isEqualTo(MSG_LOOKUP_FAILED),
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.transaction()).isNull(),
                    () -> assertThat(result.fieldErrors()).isEmpty(),
                    () -> assertThat(result.focusField()).isEqualTo(CURSOR_FIELD),
                    () -> assertThat(result.reArmedTransactionId()).isEqualTo(THIS_TRANSACTION_ID));
        }

        /**
         * A first entry that carried no selection looks nothing up and reports nothing: absence of a
         * record is not a failure on that path, so the operator is shown an empty screen to key into
         * rather than a message about a record that was never asked for.
         */
        @Test
        @DisplayName("a first entry carrying no selection reports no message and reads nothing")
        void firstEntryWithoutSelectionReportsNoMessage() {
            final TransactionViewService.TransactionViewResult result = service.viewTransaction(
                    new TransactionViewService.TransactionViewInput(null, null, null,
                            firstEntryState()));

            assertAll(() -> assertThat(result.message()).isEmpty(),
                    () -> assertThat(result.errorFlag()).isFalse(),
                    () -> assertThat(result.retrievedTransaction()).isEmpty(),
                    () -> assertThat(result.focusField()).isEqualTo(CURSOR_FIELD));
            verifyNoInteractions(transactionRepository);
        }

        @Test
        @DisplayName("a blank identifier is reported as not supplied, with the emptiness text")
        void blankIdentifierIsReportedAsNotSupplied() {
            final TransactionViewService.TransactionViewResult result = submit("", KeyAction.ENTER);

            assertAll(() -> assertThat(result.message()).isEqualTo(MSG_TRAN_ID_EMPTY),
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.focusField()).isEqualTo(CURSOR_FIELD),
                    () -> assertThat(result.fieldErrors()).singleElement()
                            .satisfies(entry -> assertThat(entry.state())
                                    .isEqualTo(ValidationException.FieldState.MISSING)));
            verifyNoInteractions(transactionRepository);
        }
    }

    // ==============================================================================================
    // The two 26-character timestamp forms, which are never unified and never normalised
    // ==============================================================================================

    @Nested
    @DisplayName("The two 26-character timestamp forms, at record offsets 278 and 304")
    class TimestampFidelity {

        /**
         * The stored origination stamp is in the online form and the stored processing stamp is in the
         * batch form. Both come back byte for byte at twenty-six encoded bytes, and they remain unequal
         * to one another &mdash; which is the assertion a unification into a single form would fail.
         */
        @Test
        @DisplayName("both forms come back byte for byte and remain unequal to one another")
        void bothFormsComeBackUnchangedAndStillDiffer() {
            when(transactionRepository.findById(PADDED_ID)).thenReturn(Optional.of(storedRow()));

            final TransactionViewService.TransactionViewResult result =
                    submit(PADDED_ID, KeyAction.ENTER);
            final TransactionViewService.TransactionProjection published = result.transaction();

            assertAll(
                    () -> assertThat(published.originationTimestamp()).isEqualTo(ONLINE_TIMESTAMP),
                    () -> assertThat(published.processingTimestamp()).isEqualTo(BATCH_TIMESTAMP),
                    () -> assertThat(encodedByteLength(published.originationTimestamp()))
                            .isEqualTo(TIMESTAMP_BYTES),
                    () -> assertThat(encodedByteLength(published.processingTimestamp()))
                            .isEqualTo(TIMESTAMP_BYTES),
                    () -> assertThat(published.originationTimestamp())
                            .isNotEqualTo(published.processingTimestamp()));
        }

        /**
         * The structural difference between the two forms survives, character position by character
         * position: the online form carries a space at position eleven and colons inside the time, and
         * the batch form carries a hyphen there and dots inside the time.
         */
        @Test
        @DisplayName("the structural difference between the forms survives, position by position")
        void structuralDifferenceBetweenTheFormsSurvives() {
            when(transactionRepository.findById(PADDED_ID)).thenReturn(Optional.of(storedRow()));

            final TransactionViewService.TransactionProjection published =
                    submit(PADDED_ID, KeyAction.ENTER).transaction();

            assertAll(
                    () -> assertThat(published.originationTimestamp().charAt(10)).isEqualTo(' '),
                    () -> assertThat(published.originationTimestamp()).contains(":"),
                    () -> assertThat(published.originationTimestamp()).endsWith("000000"),
                    () -> assertThat(published.processingTimestamp().charAt(10)).isEqualTo('-'),
                    () -> assertThat(published.processingTimestamp()).contains("."),
                    () -> assertThat(published.processingTimestamp()).doesNotContain(":"),
                    () -> assertThat(published.processingTimestamp()).endsWith("0000"));
        }

        /**
         * A blank processing stamp is a live production shape: every record of the daily-transaction
         * fixture carries one. It comes back as twenty-six spaces and is never collapsed to an absent
         * value or to an empty string.
         */
        @Test
        @DisplayName("an all-blank processing stamp comes back as 26 spaces, never absent and never empty")
        void blankProcessingStampComesBackAsTwentySixSpaces() {
            when(transactionRepository.findById(PADDED_ID))
                    .thenReturn(Optional.of(rowWithTimestamps(ONLINE_TIMESTAMP, BLANK_TIMESTAMP)));

            final TransactionViewService.TransactionProjection published =
                    submit(PADDED_ID, KeyAction.ENTER).transaction();

            assertAll(() -> assertThat(published.processingTimestamp()).isEqualTo(BLANK_TIMESTAMP),
                    () -> assertThat(published.processingTimestamp()).isNotNull(),
                    () -> assertThat(published.processingTimestamp()).isNotEmpty(),
                    () -> assertThat(published.processingTimestamp()).isBlank(),
                    () -> assertThat(encodedByteLength(published.processingTimestamp()))
                            .isEqualTo(TIMESTAMP_BYTES));
        }

        /**
         * The shared fixture builder produces exactly that shape by default, which is how the estate's
         * own sample data is laid out: an online-form origination stamp and a wholly blank processing
         * stamp. Driving the service from it proves the two agree.
         */
        @Test
        @DisplayName("the shared fixture's own shape - online origination, blank processing - is carried")
        void sharedFixtureShapeIsCarried() {
            when(transactionRepository.findById(PADDED_ID))
                    .thenReturn(Optional.of(TestDataFactory.transaction().id(PADDED_ID).build()));

            final TransactionViewService.TransactionProjection published =
                    submit(PADDED_ID, KeyAction.ENTER).transaction();

            assertAll(
                    () -> assertThat(encodedByteLength(published.originationTimestamp()))
                            .isEqualTo(TIMESTAMP_BYTES),
                    () -> assertThat(published.originationTimestamp().charAt(10)).isEqualTo(' '),
                    () -> assertThat(published.processingTimestamp()).isEqualTo(BLANK_TIMESTAMP));
        }

        @ParameterizedTest(name = "a stored stamp of [{0}] is republished unchanged")
        @ValueSource(strings = {"2022-06-10 19:27:53.000000", "2022-01-02-03.04.05.060000",
            "1999-12-31 23:59:59.000000", "2100-02-28-00.00.00.000000"})
        @DisplayName("whichever form wrote the row, the stamp is republished exactly as stored")
        void everyStoredFormIsRepublishedUnchanged(final String stored) {
            when(transactionRepository.findById(PADDED_ID))
                    .thenReturn(Optional.of(rowWithTimestamps(stored, stored)));

            final TransactionViewService.TransactionProjection published =
                    submit(PADDED_ID, KeyAction.ENTER).transaction();

            assertAll(() -> assertThat(published.originationTimestamp()).isEqualTo(stored),
                    () -> assertThat(published.processingTimestamp()).isEqualTo(stored),
                    () -> assertThat(encodedByteLength(published.originationTimestamp()))
                            .isEqualTo(TIMESTAMP_BYTES));
        }

        /**
         * The stamps are carried at the record's width of twenty-six and not at the screen's width of
         * ten. Bounding a value to a screen field is the presentation layer's step, and a ten-character
         * stamp could not satisfy the byte-for-byte guarantee above.
         */
        @Test
        @DisplayName("the stamps are carried at the record's width of 26, not the screen's width of 10")
        void stampsAreCarriedAtTheRecordWidth() {
            when(transactionRepository.findById(PADDED_ID)).thenReturn(Optional.of(storedRow()));

            final TransactionViewService.TransactionProjection published =
                    submit(PADDED_ID, KeyAction.ENTER).transaction();

            assertAll(
                    () -> assertThat(encodedByteLength(published.originationTimestamp()))
                            .isEqualTo(TIMESTAMP_BYTES),
                    () -> assertThat(encodedByteLength(published.processingTimestamp()))
                            .isEqualTo(TIMESTAMP_BYTES));
        }
    }


    // ==============================================================================================
    // Fixed-width and decimal fidelity: padding survives, scale is exactly two, truncation is toward
    // zero, and every one of the thirteen published values is read from the row
    // ==============================================================================================

    @Nested
    @DisplayName("Fixed-width and decimal fidelity of the thirteen published values")
    class FixedWidthFidelity {

        @Test
        @DisplayName("the source code keeps its trailing spaces, measured on encoded bytes")
        void sourceKeepsItsTrailingSpaces() {
            when(transactionRepository.findById(PADDED_ID)).thenReturn(Optional.of(storedRow()));

            final TransactionViewService.TransactionProjection published =
                    submit(PADDED_ID, KeyAction.ENTER).transaction();

            assertAll(() -> assertThat(published.source()).isEqualTo(PADDED_SOURCE),
                    () -> assertThat(published.source()).endsWith(" "),
                    () -> assertThat(published.source()).isNotEqualTo(PADDED_SOURCE.strip()),
                    () -> assertThat(encodedByteLength(published.source())).isEqualTo(SOURCE_BYTES));
        }

        @Test
        @DisplayName("a stored amount already at the record's scale is republished unaltered")
        void storedAmountAtRecordScaleIsUnaltered() {
            when(transactionRepository.findById(PADDED_ID)).thenReturn(Optional.of(storedRow()));

            final BigDecimal published = submit(PADDED_ID, KeyAction.ENTER).transaction().amount();

            assertAll(() -> assertThat(published).isEqualTo(new BigDecimal("1234.56")),
                    () -> assertThat(published.scale()).isEqualTo(MONETARY_SCALE),
                    () -> assertThat(published).isInstanceOf(BigDecimal.class));
        }

        /**
         * The store into the two-decimal receiving field truncates toward zero. The keyword that would
         * request rounding appears nowhere in the estate, so a conventional half-up or half-even mode
         * would differ from the legacy by one cent on half of all values, in both directions of sign.
         */
        @ParameterizedTest(name = "a stored {0} is published as {1}, truncated and never rounded")
        @CsvSource({"1234.567, 1234.56",
            "1234.565, 1234.56",
            "1234.569, 1234.56",
            "0.999, 0.99",
            "-1234.567, -1234.56",
            "-1234.565, -1234.56",
            "-0.999, -0.99"})
        @DisplayName("the store into the two-decimal field truncates toward zero, never rounds")
        void storeTruncatesTowardZero(final String stored, final String expected) {
            when(transactionRepository.findById(PADDED_ID))
                    .thenReturn(Optional.of(rowWithAmount(new BigDecimal(stored))));

            final BigDecimal published = submit(PADDED_ID, KeyAction.ENTER).transaction().amount();

            assertAll(() -> assertThat(published).isEqualTo(new BigDecimal(expected)),
                    () -> assertThat(published.scale()).isEqualTo(MONETARY_SCALE));
        }

        /**
         * A negative amount is a live production shape rather than an edge case: the daily-transaction
         * fixture carries fifty operator-originated returns alongside two hundred and fifty
         * point-of-sale purchases, so both directions of sign reach this screen.
         */
        @Test
        @DisplayName("a negative amount keeps its sign and its scale")
        void negativeAmountKeepsItsSign() {
            when(transactionRepository.findById(PADDED_ID))
                    .thenReturn(Optional.of(rowWithAmount(new BigDecimal("-45.99"))));

            final BigDecimal published = submit(PADDED_ID, KeyAction.ENTER).transaction().amount();

            assertAll(() -> assertThat(published).isEqualTo(new BigDecimal("-45.99")),
                    () -> assertThat(published).isNegative(),
                    () -> assertThat(published.signum()).isEqualTo(-1),
                    () -> assertThat(published.scale()).isEqualTo(MONETARY_SCALE));
        }

        @Test
        @DisplayName("a zero amount is published at the record's scale rather than as a bare zero")
        void zeroAmountKeepsTheRecordScale() {
            when(transactionRepository.findById(PADDED_ID))
                    .thenReturn(Optional.of(rowWithAmount(new BigDecimal("0.00"))));

            final BigDecimal published = submit(PADDED_ID, KeyAction.ENTER).transaction().amount();

            assertAll(() -> assertThat(published).isEqualTo(new BigDecimal("0.00")),
                    () -> assertThat(published.scale()).isEqualTo(MONETARY_SCALE),
                    () -> assertThat(published.signum()).isZero());
        }

        /**
         * An absent amount is carried through as absent rather than handed to the codec, which rejects
         * one. The column forbids a null, so this guards a hand-built record rather than a stored row.
         */
        @Test
        @DisplayName("an absent amount is carried as absent rather than rejected")
        void absentAmountIsCarriedAsAbsent() {
            when(transactionRepository.findById(PADDED_ID))
                    .thenReturn(Optional.of(rowWithAmount(null)));

            final TransactionViewService.TransactionProjection published =
                    submit(PADDED_ID, KeyAction.ENTER).transaction();

            assertAll(() -> assertThat(published).isNotNull(),
                    () -> assertThat(published.amount()).isNull(),
                    () -> assertThat(published.transactionId()).isEqualTo(PADDED_ID));
        }

        /**
         * Every one of the thirteen values the screen publishes is read from the row, none silently
         * dropped. The merchant components are read through the entity's <em>unprefixed</em> properties,
         * which is the posted-transaction convention; the daily-transaction entity prefixes its own, and
         * the two conventions are never unified.
         */
        @Test
        @DisplayName("every one of the thirteen published values is read from the row")
        void everyPublishedValueIsReadFromTheRow() {
            final Transaction row = new Transaction("0000000000000042",
                    "07",
                    "0099",
                    "OPERATOR  ",
                    "DISTINCT DESCRIPTION VALUE",
                    new BigDecimal("77.01"),
                    "900000001",
                    "DISTINCT MERCHANT NAME",
                    "DISTINCT MERCHANT CITY",
                    "0000012345",
                    "4111222233334444",
                    ONLINE_TIMESTAMP,
                    BATCH_TIMESTAMP);
            when(transactionRepository.findById("0000000000000042")).thenReturn(Optional.of(row));

            final TransactionViewService.TransactionProjection published =
                    submit("0000000000000042", KeyAction.ENTER).transaction();

            // Every expected value is the literal this file authored into the row above, so no
            // assertion here can be satisfied by asking the code under test what it thinks it stored.
            assertAll(
                    () -> assertThat(published.transactionId()).isEqualTo("0000000000000042"),
                    () -> assertThat(published.cardNumber()).isEqualTo("4111222233334444"),
                    () -> assertThat(published.typeCode()).isEqualTo("07"),
                    () -> assertThat(published.categoryCode()).isEqualTo("0099"),
                    () -> assertThat(published.source()).isEqualTo("OPERATOR  "),
                    () -> assertThat(published.amount()).isEqualTo(new BigDecimal("77.01")),
                    () -> assertThat(published.description()).isEqualTo("DISTINCT DESCRIPTION VALUE"),
                    () -> assertThat(published.originationTimestamp()).isEqualTo(ONLINE_TIMESTAMP),
                    () -> assertThat(published.processingTimestamp()).isEqualTo(BATCH_TIMESTAMP),
                    () -> assertThat(published.merchantId()).isEqualTo("900000001"),
                    () -> assertThat(published.merchantName()).isEqualTo("DISTINCT MERCHANT NAME"),
                    () -> assertThat(published.merchantCity()).isEqualTo("DISTINCT MERCHANT CITY"),
                    () -> assertThat(published.merchantZip()).isEqualTo("0000012345"));
        }

        /**
         * The merchant components are proven to come from the unprefixed properties by their values
         * being distinct from one another: a translation that read the wrong property, or that mapped
         * the prefixed daily-transaction convention onto this record, could not produce all four.
         */
        @Test
        @DisplayName("the merchant values come from the unprefixed properties of the posted record")
        void merchantValuesComeFromTheUnprefixedProperties() {
            final Transaction row = new Transaction(PADDED_ID, "01", "0005", PADDED_SOURCE,
                    "PURCHASE", new BigDecimal("1.00"), "111111111", "MERCHANT NAME VALUE",
                    "MERCHANT CITY VALUE", "MZIP000001", "4111111111111111",
                    ONLINE_TIMESTAMP, BATCH_TIMESTAMP);
            when(transactionRepository.findById(PADDED_ID)).thenReturn(Optional.of(row));

            final TransactionViewService.TransactionProjection published =
                    submit(PADDED_ID, KeyAction.ENTER).transaction();

            assertAll(() -> assertThat(published.merchantId()).isEqualTo("111111111"),
                    () -> assertThat(published.merchantName()).isEqualTo("MERCHANT NAME VALUE"),
                    () -> assertThat(published.merchantCity()).isEqualTo("MERCHANT CITY VALUE"),
                    () -> assertThat(published.merchantZip()).isEqualTo("MZIP000001"),
                    () -> assertThat(published.merchantName())
                            .isNotEqualTo(published.merchantCity()));
        }

        /**
         * The description is carried at the record's hundred characters and not cut to the screen's
         * sixty. Applying a screen bound is the presentation layer's step, and doing it here would make
         * the byte-for-byte guarantee unsatisfiable for the five values whose widths differ.
         */
        @Test
        @DisplayName("a description longer than the screen field is not cut by this layer")
        void descriptionIsNotCutToTheScreenWidth() {
            final String longDescription = "D".repeat(DESCRIPTION_BYTES);
            final Transaction row = new Transaction(PADDED_ID, "01", "0005", PADDED_SOURCE,
                    longDescription, new BigDecimal("1.00"), "111111111", "N".repeat(50),
                    "C".repeat(50), "98109", "4111111111111111", ONLINE_TIMESTAMP, BATCH_TIMESTAMP);
            when(transactionRepository.findById(PADDED_ID)).thenReturn(Optional.of(row));

            final TransactionViewService.TransactionProjection published =
                    submit(PADDED_ID, KeyAction.ENTER).transaction();

            assertAll(() -> assertThat(published.description()).isEqualTo(longDescription),
                    () -> assertThat(encodedByteLength(published.description()))
                            .isEqualTo(DESCRIPTION_BYTES),
                    () -> assertThat(encodedByteLength(published.merchantName())).isEqualTo(50),
                    () -> assertThat(encodedByteLength(published.merchantCity())).isEqualTo(50));
        }

        /**
         * The card number crosses as a sixteen-character scalar and not as a related entity. The record
         * carries no relationship of any kind, so there is nothing to traverse and nothing to fetch
         * eagerly or lazily on this path.
         */
        @Test
        @DisplayName("the card number crosses as a 16-character scalar, not as a related entity")
        void cardNumberCrossesAsAScalar() {
            when(transactionRepository.findById(PADDED_ID)).thenReturn(Optional.of(storedRow()));

            final TransactionViewService.TransactionProjection published =
                    submit(PADDED_ID, KeyAction.ENTER).transaction();

            assertAll(() -> assertThat(published.cardNumber()).isInstanceOf(String.class),
                    () -> assertThat(published.cardNumber()).isEqualTo("4111111111111111"),
                    () -> assertThat(encodedByteLength(published.cardNumber())).isEqualTo(KEY_BYTES));
        }
    }


    // ==============================================================================================
    // The message contract: a padded common message crosses at its contractual width, never trimmed
    // ==============================================================================================

    @Nested
    @DisplayName("The common-message contract at its contractual width")
    class CommonMessageContract {

        /**
         * The catch-all arm of the attention-key evaluation carries the common invalid-key message at
         * its full width of fifty characters, ten of which are trailing spaces. The value is supplied by
         * this file and expected by this file, so a service that substituted a message of its own, or
         * trimmed the one it was given, would fail here.
         */
        @Test
        @DisplayName("the invalid-key message crosses at exactly 50 encoded bytes, untrimmed")
        void invalidKeyMessageCrossesAtFiftyEncodedBytes() {
            when(messageCatalogService.invalidKeyMessage()).thenReturn(INVALID_KEY_MESSAGE);

            final TransactionViewService.TransactionViewResult result =
                    submit(PADDED_ID, KeyAction.PFK07);

            assertAll(() -> assertThat(result.message()).isEqualTo(INVALID_KEY_MESSAGE),
                    () -> assertThat(encodedByteLength(result.message()))
                            .isEqualTo(COMMON_MESSAGE_BYTES),
                    () -> assertThat(result.message()).endsWith(" ".repeat(10)),
                    () -> assertThat(result.message()).isNotEqualTo(INVALID_KEY_MESSAGE.strip()),
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.focusField()).isEqualTo(CURSOR_FIELD),
                    () -> assertThat(result.fieldErrors()).isEmpty(),
                    () -> assertThat(result.transaction()).isNull());
            verifyNoInteractions(transactionRepository);
        }

        @ParameterizedTest(name = "{0} has no arm of its own and reaches the catch-all")
        @EnumSource(value = KeyAction.class,
                names = {"ENTER", "PFK03", "PFK04", "PFK05"},
                mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("every key without an arm of its own reaches the catch-all, clear key included")
        void everyUnmappedKeyReachesTheCatchAll(final KeyAction unmapped) {
            when(messageCatalogService.invalidKeyMessage()).thenReturn(INVALID_KEY_MESSAGE);

            final TransactionViewService.TransactionViewResult result = submit(PADDED_ID, unmapped);

            assertAll(() -> assertThat(result.message()).isEqualTo(INVALID_KEY_MESSAGE),
                    () -> assertThat(result.errorFlag()).isTrue());
            verifyNoInteractions(transactionRepository);
        }

        @Test
        @DisplayName("an undecoded key reaches the catch-all, because it is none of the four named")
        void undecodedKeyReachesTheCatchAll() {
            when(messageCatalogService.invalidKeyMessage()).thenReturn(INVALID_KEY_MESSAGE);

            final TransactionViewService.TransactionViewResult result = submit(PADDED_ID, null);

            assertAll(() -> assertThat(result.message()).isEqualTo(INVALID_KEY_MESSAGE),
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(encodedByteLength(result.message()))
                            .isEqualTo(COMMON_MESSAGE_BYTES));
        }

        /**
         * The two screen titles cross at their own contractual width of forty characters, with the
         * leading and trailing spaces that centre them within the field left intact.
         */
        @Test
        @DisplayName("both screen titles cross at exactly 40 encoded bytes, centring intact")
        void screenTitlesCrossAtFortyEncodedBytes() {
            when(transactionRepository.findById(PADDED_ID)).thenReturn(Optional.of(storedRow()));
            when(messageCatalogService.screenTitle01()).thenReturn(SCREEN_TITLE_01);
            when(messageCatalogService.screenTitle02()).thenReturn(SCREEN_TITLE_02);

            final TransactionViewService.ScreenHeader header =
                    submit(PADDED_ID, KeyAction.ENTER).header();

            assertAll(() -> assertThat(header).isNotNull(),
                    () -> assertThat(header.title01()).isEqualTo(SCREEN_TITLE_01),
                    () -> assertThat(header.title02()).isEqualTo(SCREEN_TITLE_02),
                    () -> assertThat(encodedByteLength(header.title01()))
                            .isEqualTo(SCREEN_TITLE_BYTES),
                    () -> assertThat(encodedByteLength(header.title02()))
                            .isEqualTo(SCREEN_TITLE_BYTES),
                    () -> assertThat(header.title01()).startsWith(" "),
                    () -> assertThat(header.title01()).endsWith(" "));
        }

        @Test
        @DisplayName("the header carries this screen's own identity and the fixed clock's reading")
        void headerCarriesTheIdentityAndTheClock() {
            when(transactionRepository.findById(PADDED_ID)).thenReturn(Optional.of(storedRow()));

            final TransactionViewService.ScreenHeader header =
                    submit(PADDED_ID, KeyAction.ENTER).header();

            assertAll(
                    () -> assertThat(header.transactionName()).isEqualTo(THIS_TRANSACTION_ID),
                    () -> assertThat(header.programName()).isEqualTo(THIS_PROGRAM_NAME),
                    () -> assertThat(header.currentDate()).isEqualTo(EXPECTED_HEADER_DATE),
                    () -> assertThat(header.currentTime()).isEqualTo(EXPECTED_HEADER_TIME));
        }

        @ParameterizedTest(name = "{0} renders as {1} and {2}")
        @CsvSource({"2022-07-19T14:23:45Z, 07/19/22, 14:23:45",
            "1999-01-02T03:04:05Z, 01/02/99, 03:04:05",
            "2000-12-31T23:59:59Z, 12/31/00, 23:59:59",
            "2100-02-28T00:00:00Z, 02/28/00, 00:00:00"})
        @DisplayName("the two-digit year is the low-order two digits, as the legacy reference slice is")
        void twoDigitComponentsAreRenderedAsTheLegacySlices(final String instant,
                final String expectedDate, final String expectedTime) {
            final TransactionViewService dated = new TransactionViewService(transactionRepository,
                    messageCatalogService, navigationService,
                    Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));

            final TransactionViewService.ScreenHeader header = dated.viewTransaction(
                    new TransactionViewService.TransactionViewInput(null, null, null,
                            firstEntryState())).header();

            assertAll(() -> assertThat(header.currentDate()).isEqualTo(expectedDate),
                    () -> assertThat(header.currentTime()).isEqualTo(expectedTime));
        }
    }

    // ==============================================================================================
    // Route fidelity: destinations are route constants resolved from the navigation rules, and no
    // server-side forwarding takes place on any path
    // ==============================================================================================

    @Nested
    @DisplayName("Route fidelity: route constants out, no server-side forwarding")
    class RouteFidelity {

        /**
         * A turn that re-presented the screen carries this screen's own route constant, because the
         * pseudo-conversational return re-arms this same transaction. The client drives the next call:
         * the outcome names the re-armed transaction and nothing was dispatched on the server.
         */
        @Test
        @DisplayName("a re-presented turn returns this screen's own route constant and re-arms it")
        void rePresentedTurnReturnsThisScreensRouteConstant() {
            when(transactionRepository.findById(PADDED_ID)).thenReturn(Optional.of(storedRow()));

            final TransactionViewService.TransactionViewResult result =
                    submit(PADDED_ID, KeyAction.ENTER);

            assertAll(() -> assertThat(result.route())
                            .isEqualTo(NavigationService.Route.TRANSACTION_VIEW),
                    () -> assertThat(result.route().getRouteValue())
                            .isEqualTo(ROUTE_TRANSACTION_VIEW),
                    () -> assertThat(result.reArmedTransactionId()).isEqualTo(THIS_TRANSACTION_ID));
            verifyNoInteractions(navigationService);
        }

        /**
         * The destination of a transfer is whatever the navigation rules resolve, not a name this
         * service holds. The rules are stubbed to resolve a destination the source would never nominate
         * from this screen, and the outcome carries it &mdash; which a hardcoded destination could not
         * do.
         */
        @Test
        @DisplayName("a transfer destination is whatever the navigation rules resolve, not a literal")
        void transferDestinationComesFromTheNavigationRules() {
            when(navigationService.resolveNominatedDestination(any(),
                    eq(NavigationService.Route.SIGN_ON)))
                    .thenReturn(NavigationService.Route.ADMIN_MENU);

            final TransactionViewService.TransactionViewResult result =
                    submit(PADDED_ID, KeyAction.PFK05);

            assertAll(() -> assertThat(result.route())
                            .isEqualTo(NavigationService.Route.ADMIN_MENU),
                    () -> assertThat(result.navigationContext().toProgram()).isEqualTo("COADM01C"),
                    () -> assertThat(result.navigationContext().fromProgram())
                            .isEqualTo(THIS_PROGRAM_NAME),
                    () -> assertThat(result.navigationContext().fromTransactionId())
                            .isEqualTo(THIS_TRANSACTION_ID),
                    () -> assertThat(result.navigationContext().firstEntry()).isTrue(),
                    () -> assertThat(result.reArmedTransactionId()).isEmpty(),
                    () -> assertThat(result.header()).isNull());
            verify(navigationService).resolveNominatedDestination(any(),
                    eq(NavigationService.Route.SIGN_ON));
            verifyNoInteractions(transactionRepository);
            verifyNoInteractions(messageCatalogService);
        }

        @Test
        @DisplayName("back navigation is resolved by the rules, with this screen's own default passed in")
        void backNavigationIsResolvedByTheRules() {
            when(navigationService.resolveBackNavigation(any(),
                    eq(NavigationService.Route.USER_MENU)))
                    .thenReturn(NavigationService.Route.CARD_LIST);
            when(navigationService.resolveNominatedDestination(any(),
                    eq(NavigationService.Route.SIGN_ON)))
                    .thenReturn(NavigationService.Route.CARD_LIST);

            final TransactionViewService.TransactionViewResult result =
                    submit(PADDED_ID, KeyAction.PFK03);

            assertAll(() -> assertThat(result.route())
                            .isEqualTo(NavigationService.Route.CARD_LIST),
                    () -> assertThat(result.navigationContext().toProgram()).isEqualTo("COCRDLIC"),
                    () -> assertThat(result.reArmedTransactionId()).isEmpty());
            verify(navigationService).resolveBackNavigation(any(),
                    eq(NavigationService.Route.USER_MENU));
            verify(navigationService).resolveNominatedDestination(any(),
                    eq(NavigationService.Route.SIGN_ON));
            verifyNoMoreInteractions(navigationService);
        }

        @Test
        @DisplayName("a turn carrying no navigation state is routed by the rules and reads nothing")
        void absentNavigationStateIsRoutedByTheRules() {
            when(navigationService.resolveAbsentContextRoute())
                    .thenReturn(NavigationService.Route.SIGN_ON);
            when(navigationService.resolveNominatedDestination(any(),
                    eq(NavigationService.Route.SIGN_ON)))
                    .thenReturn(NavigationService.Route.SIGN_ON);

            final TransactionViewService.TransactionViewResult result = service.viewTransaction(
                    new TransactionViewService.TransactionViewInput(PADDED_ID, OTHER_PADDED_ID,
                            KeyAction.ENTER, null));

            assertAll(() -> assertThat(result.route())
                            .isEqualTo(NavigationService.Route.SIGN_ON),
                    () -> assertThat(result.navigationContext().toProgram()).isEqualTo("COSGN00C"),
                    () -> assertThat(result.reArmedTransactionId()).isEmpty(),
                    () -> assertThat(result.header()).isNull(),
                    () -> assertThat(result.transaction()).isNull(),
                    () -> assertThat(result.errorFlag()).isFalse());
            verify(navigationService).resolveAbsentContextRoute();
            verifyNoInteractions(transactionRepository);
        }

        @Test
        @DisplayName("a wholly empty navigation state is treated as absent, as a zero-length area is")
        void whollyEmptyNavigationStateIsTreatedAsAbsent() {
            when(navigationService.resolveAbsentContextRoute())
                    .thenReturn(NavigationService.Route.SIGN_ON);
            when(navigationService.resolveNominatedDestination(any(),
                    eq(NavigationService.Route.SIGN_ON)))
                    .thenReturn(NavigationService.Route.SIGN_ON);

            final TransactionViewService.TransactionViewResult result = service.viewTransaction(
                    new TransactionViewService.TransactionViewInput(PADDED_ID, PADDED_ID,
                            KeyAction.ENTER, ScreenNavigationState.empty()));

            assertAll(() -> assertThat(result.route())
                            .isEqualTo(NavigationService.Route.SIGN_ON),
                    () -> assertThat(result.reArmedTransactionId()).isEmpty());
            verifyNoInteractions(transactionRepository);
        }

        /**
         * No route in the navigation vocabulary resolves to the program that the CICS resource
         * definition declares at line 211 and binds at line 390 but for which no source member exists.
         * There is nothing to route to, so nothing routes to it.
         */
        @Test
        @DisplayName("no route resolves to the dangling CICS program that has no source member")
        void noRouteResolvesToTheDanglingCsdProgram() {
            assertThat(NavigationService.Route.values())
                    .allSatisfy(route -> assertThat(route.getLegacyProgramName())
                            .isNotEqualTo(DANGLING_CSD_PROGRAM));
        }

        @Test
        @DisplayName("the route a turn emits never names the dangling CICS program either")
        void emittedRouteNeverNamesTheDanglingCsdProgram() {
            when(transactionRepository.findById(PADDED_ID)).thenReturn(Optional.of(storedRow()));

            final TransactionViewService.TransactionViewResult result =
                    submit(PADDED_ID, KeyAction.ENTER);

            assertAll(() -> assertThat(result.route().getLegacyProgramName())
                            .isNotEqualTo(DANGLING_CSD_PROGRAM),
                    () -> assertThat(result.route().getLegacyProgramName())
                            .isEqualTo(THIS_PROGRAM_NAME),
                    () -> assertThat(result.navigationContext().toProgram())
                            .isNotEqualTo(DANGLING_CSD_PROGRAM));
        }
    }

    // ==============================================================================================
    // The repository contract: the inherited single-key lookup only, exactly once, and nothing else
    // ==============================================================================================

    @Nested
    @DisplayName("The repository contract: one keyed read and nothing else")
    class RepositoryContract {

        /**
         * The two members this repository declares beyond the inherited surface belong to other
         * services: the highest-key probe is the bill-payment identifier allocator's, and the insert is
         * the same service's write half. This screen generates no identifier and writes nothing, so
         * neither may be reached, and the final check proves no other interaction of any kind occurred.
         */
        @Test
        @DisplayName("only the inherited keyed read is reached; no declared member is invoked")
        void onlyTheInheritedKeyedReadIsReached() {
            when(transactionRepository.findById(PADDED_ID)).thenReturn(Optional.of(storedRow()));

            submit(PADDED_ID, KeyAction.ENTER);

            verify(transactionRepository, times(1)).findById(any());
            verify(transactionRepository, never()).findMaxId();
            verify(transactionRepository, never()).insertAndFlush(any());
            verify(transactionRepository, never()).findAll();
            verify(transactionRepository, never()).count();
            verify(transactionRepository, never()).flush();
            verify(transactionRepository, never()).deleteAll();
            verifyNoMoreInteractions(transactionRepository);
        }

        @Test
        @DisplayName("a record-absent turn writes nothing and probes no maximum either")
        void recordAbsentTurnWritesNothing() {
            submit(PADDED_ID, KeyAction.ENTER);

            verify(transactionRepository, times(1)).findById(any());
            verify(transactionRepository, never()).findMaxId();
            verify(transactionRepository, never()).insertAndFlush(any());
            verify(transactionRepository, never()).flush();
            verifyNoMoreInteractions(transactionRepository);
        }

        /**
         * Nothing is obtained by traversing a relationship, because there is no relationship to
         * traverse: the record declares no association and the transaction tables carry no foreign key
         * in any migration version, deliberately, so the legacy reject codes stay reachable. Where this
         * module does need related data it takes an explicit second lookup; this screen needs none, so
         * the file is touched exactly once and every published value comes from the one row read.
         */
        @Test
        @DisplayName("related data is never obtained by traversal: exactly one lookup, and no more")
        void relatedDataIsNeverObtainedByTraversal() {
            when(transactionRepository.findById(PADDED_ID)).thenReturn(Optional.of(storedRow()));

            final TransactionViewService.TransactionProjection published =
                    submit(PADDED_ID, KeyAction.ENTER).transaction();

            assertAll(() -> assertThat(published.cardNumber()).isEqualTo("4111111111111111"),
                    () -> assertThat(published.merchantId()).isEqualTo("123456789"));
            verify(transactionRepository, times(1)).findById(PADDED_ID);
            verifyNoMoreInteractions(transactionRepository);
        }

        @Test
        @DisplayName("a refused identifier reaches the file not at all")
        void refusedIdentifierReachesTheFileNotAtAll() {
            submit(OVERLONG_ID, KeyAction.ENTER);

            verifyNoInteractions(transactionRepository);
        }
    }


    // ==============================================================================================
    // Boundary and absent input. Every shape below completes normally: no runtime failure escapes.
    // ==============================================================================================

    @Nested
    @DisplayName("Boundary and absent input, none of which lets a runtime failure escape")
    class BoundaryAndAbsentInput {

        @Test
        @DisplayName("an absent identifier is reported as not supplied, since an untransmitted field is blank")
        void absentIdentifierIsReportedAsNotSupplied() {
            final TransactionViewService.TransactionViewResult result =
                    submit(null, KeyAction.ENTER);

            assertAll(() -> assertThat(result).isNotNull(),
                    () -> assertThat(result.message()).isEqualTo(MSG_TRAN_ID_EMPTY),
                    () -> assertThat(result.searchTransactionId()).isEmpty(),
                    () -> assertThat(result.fieldErrors()).singleElement()
                            .satisfies(entry -> assertThat(entry.state())
                                    .isEqualTo(ValidationException.FieldState.MISSING)));
            verifyNoInteractions(transactionRepository);
        }

        @ParameterizedTest(name = "padding-only case {index} is reported as not supplied")
        @ValueSource(strings = {"", " ", "          ", "                ", "\u0000",
            "\u0000\u0000\u0000", "  \u0000  "})
        @DisplayName("a padding-only identifier is not supplied, spaces and low values alike")
        void paddingOnlyIdentifierIsNotSupplied(final String submitted) {
            final TransactionViewService.TransactionViewResult result =
                    submit(submitted, KeyAction.ENTER);

            assertAll(() -> assertThat(result.message()).isEqualTo(MSG_TRAN_ID_EMPTY),
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.fieldErrors()).hasSize(1));
            verifyNoInteractions(transactionRepository);
        }

        /**
         * A field carrying a tab or a line feed is <em>supplied</em> as far as this screen is concerned,
         * because a COBOL field is blank only when every position holds a space or a low value. Such a
         * value is looked up and names no row, which is a different outcome from being unsupplied.
         */
        @ParameterizedTest(name = "white space other than a space is supplied, case {index}")
        @ValueSource(strings = {"\t", "\n", " \t "})
        @DisplayName("white space other than a space or a low value counts as supplied and is looked up")
        void otherWhiteSpaceCountsAsSupplied(final String submitted) {
            final TransactionViewService.TransactionViewResult result =
                    submit(submitted, KeyAction.ENTER);

            assertAll(() -> assertThat(result.message()).isEqualTo(MSG_TRAN_ID_NOT_FOUND),
                    () -> assertThat(result.fieldErrors()).isEmpty());
            verify(transactionRepository).findById(submitted);
        }

        @ParameterizedTest(name = "a short identifier of [{0}] is looked up as submitted")
        @ValueSource(strings = {"1", "42", "000000000000001"})
        @DisplayName("an identifier shorter than the key width is looked up exactly as submitted")
        void shortIdentifierIsLookedUpAsSubmitted(final String submitted) {
            final TransactionViewService.TransactionViewResult result =
                    submit(submitted, KeyAction.ENTER);

            assertAll(() -> assertThat(result.message()).isEqualTo(MSG_TRAN_ID_NOT_FOUND),
                    () -> assertThat(result.searchTransactionId()).isEqualTo(submitted),
                    () -> assertThat(result.fieldErrors()).isEmpty());
            verify(transactionRepository).findById(submitted);
        }

        @ParameterizedTest(name = "a long identifier of [{0}] is refused without a lookup")
        @ValueSource(strings = {"00000000000000012", "0000000000000001 ",
            "00000000000000000000000000000000"})
        @DisplayName("an identifier longer than the key width is refused without a lookup")
        void longIdentifierIsRefusedWithoutALookup(final String submitted) {
            final TransactionViewService.TransactionViewResult result =
                    submit(submitted, KeyAction.ENTER);

            assertAll(() -> assertThat(result.message()).isEqualTo(MSG_TRAN_ID_NOT_FOUND),
                    () -> assertThat(result.fieldErrors()).singleElement()
                            .satisfies(entry -> assertThat(entry.state())
                                    .isEqualTo(ValidationException.FieldState.INVALID)));
            verifyNoInteractions(transactionRepository);
        }

        @Test
        @DisplayName("an absent navigation state routes away rather than failing on it")
        void absentNavigationStateRoutesAwayRatherThanFailing() {
            when(navigationService.resolveAbsentContextRoute())
                    .thenReturn(NavigationService.Route.SIGN_ON);
            when(navigationService.resolveNominatedDestination(any(),
                    eq(NavigationService.Route.SIGN_ON)))
                    .thenReturn(NavigationService.Route.SIGN_ON);

            final TransactionViewService.TransactionViewResult result = service.viewTransaction(
                    new TransactionViewService.TransactionViewInput(null, null, null, null));

            assertAll(() -> assertThat(result).isNotNull(),
                    () -> assertThat(result.route())
                            .isEqualTo(NavigationService.Route.SIGN_ON),
                    () -> assertThat(result.navigationContext()).isNotNull(),
                    () -> assertThat(result.fieldErrors()).isEmpty(),
                    () -> assertThat(result.message()).isEmpty());
        }

        @Test
        @DisplayName("an absent carried selection on a first entry is simply no selection")
        void absentCarriedSelectionIsSimplyNoSelection() {
            final TransactionViewService.TransactionViewResult result = service.viewTransaction(
                    new TransactionViewService.TransactionViewInput(null, null, KeyAction.ENTER,
                            firstEntryState()));

            assertAll(() -> assertThat(result).isNotNull(),
                    () -> assertThat(result.searchTransactionId()).isEmpty(),
                    () -> assertThat(result.message()).isEmpty());
            verifyNoInteractions(transactionRepository);
        }

        @ParameterizedTest(name = "padding-only selection case {index} counts as no selection")
        @ValueSource(strings = {"", "  ", "                ", "\u0000", "\u0000\u0000\u0000"})
        @DisplayName("a padding-only carried selection counts as no selection at all")
        void paddingOnlyCarriedSelectionCountsAsNone(final String selection) {
            final TransactionViewService.TransactionViewResult result = service.viewTransaction(
                    new TransactionViewService.TransactionViewInput(null, selection, null,
                            firstEntryState()));

            assertAll(() -> assertThat(result.searchTransactionId()).isEmpty(),
                    () -> assertThat(result.message()).isEmpty(),
                    () -> assertThat(result.errorFlag()).isFalse());
            verifyNoInteractions(transactionRepository);
        }

        @Test
        @DisplayName("an absent stored value in a published component is carried as absent")
        void absentStoredValueIsCarriedAsAbsent() {
            when(transactionRepository.findById(PADDED_ID)).thenReturn(Optional.of(
                    new Transaction(PADDED_ID, null, null, null, null, null, null, null, null, null,
                            null, null, null)));

            final TransactionViewService.TransactionProjection published =
                    submit(PADDED_ID, KeyAction.ENTER).transaction();

            assertAll(() -> assertThat(published).isNotNull(),
                    () -> assertThat(published.transactionId()).isEqualTo(PADDED_ID),
                    () -> assertThat(published.source()).isNull(),
                    () -> assertThat(published.amount()).isNull(),
                    () -> assertThat(published.originationTimestamp()).isNull(),
                    () -> assertThat(published.processingTimestamp()).isNull());
        }
    }

    // ==============================================================================================
    // The outcome contract and the two legacy flags
    // ==============================================================================================

    @Nested
    @DisplayName("The outcome contract and the two legacy two-state flags")
    class OutcomeContract {

        @Test
        @DisplayName("the per-field detail is unmodifiable and never absent")
        void perFieldDetailIsUnmodifiableAndNeverAbsent() {
            final TransactionViewService.TransactionViewResult result = submit("", KeyAction.ENTER);

            assertThat(result.fieldErrors()).isNotNull().hasSize(1);
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> result.fieldErrors()
                            .add(new ValidationException.FieldError(CURSOR_PROPERTY, CURSOR_FIELD,
                                    ValidationException.FieldState.MISSING, MSG_TRAN_ID_EMPTY)));
        }

        @Test
        @DisplayName("an absent per-field list is normalised to an empty one")
        void absentPerFieldListIsNormalisedToAnEmptyOne() {
            final TransactionViewService.TransactionViewResult built =
                    new TransactionViewService.TransactionViewResult(
                            NavigationService.Route.TRANSACTION_VIEW,
                            ScreenNavigationState.empty(), THIS_TRANSACTION_ID, PADDED_ID, null,
                            "", CURSOR_FIELD, false, false, true, null, null);

            assertAll(() -> assertThat(built.fieldErrors()).isNotNull().isEmpty(),
                    () -> assertThat(built.retrievedTransaction()).isEmpty(),
                    () -> assertThat(built.header()).isNull());
        }

        @Test
        @DisplayName("a supplied per-field list is copied, so a caller cannot mutate the outcome")
        void suppliedPerFieldListIsCopied() {
            final List<ValidationException.FieldError> supplied = List.of(
                    new ValidationException.FieldError(CURSOR_PROPERTY, CURSOR_FIELD,
                            ValidationException.FieldState.INVALID, MSG_TRAN_ID_NOT_FOUND));

            final TransactionViewService.TransactionViewResult built =
                    new TransactionViewService.TransactionViewResult(
                            NavigationService.Route.TRANSACTION_VIEW,
                            ScreenNavigationState.empty(), THIS_TRANSACTION_ID, PADDED_ID, null,
                            MSG_TRAN_ID_NOT_FOUND, CURSOR_FIELD, true, false, true, supplied, null);

            assertAll(() -> assertThat(built.fieldErrors()).hasSize(1),
                    () -> assertThat(built.fieldErrors().get(0).state())
                            .isEqualTo(ValidationException.FieldState.INVALID));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> built.fieldErrors().clear());
        }

        @Test
        @DisplayName("the retrieved record is offered both as a component and as an optional")
        void retrievedRecordIsOfferedBothWays() {
            when(transactionRepository.findById(PADDED_ID)).thenReturn(Optional.of(storedRow()));

            final TransactionViewService.TransactionViewResult result =
                    submit(PADDED_ID, KeyAction.ENTER);

            assertAll(() -> assertThat(result.retrievedTransaction()).isPresent(),
                    () -> assertThat(result.retrievedTransaction()).contains(result.transaction()),
                    () -> assertThat(result.transaction()).isNotNull());
        }

        /**
         * The rendering of an outcome withholds every regulated value. The record this screen displays is
         * cardholder data in its entirety, so a stray interpolation of an outcome into a diagnostic must
         * not disclose an identifier, a card number, an amount or a merchant.
         */
        @Test
        @DisplayName("the rendering of an outcome withholds every regulated value")
        void renderingOfAnOutcomeWithholdsRegulatedValues() {
            when(transactionRepository.findById(PADDED_ID)).thenReturn(Optional.of(storedRow()));

            final String rendered = submit(PADDED_ID, KeyAction.ENTER).toString();

            assertAll(() -> assertThat(rendered).doesNotContain(PADDED_ID),
                    () -> assertThat(rendered).doesNotContain("4111111111111111"),
                    () -> assertThat(rendered).doesNotContain("1234.56"),
                    () -> assertThat(rendered).doesNotContain("AMAZON"),
                    () -> assertThat(rendered).doesNotContain(ONLINE_TIMESTAMP),
                    () -> assertThat(rendered).doesNotContain(BATCH_TIMESTAMP),
                    () -> assertThat(rendered).contains("route="),
                    () -> assertThat(rendered).contains("TRANSACTION_VIEW"));
        }

        @Test
        @DisplayName("the rendering of a projection keeps only the reference codes")
        void renderingOfAProjectionKeepsOnlyTheReferenceCodes() {
            when(transactionRepository.findById(PADDED_ID)).thenReturn(Optional.of(storedRow()));

            final String rendered =
                    submit(PADDED_ID, KeyAction.ENTER).transaction().toString();

            assertAll(() -> assertThat(rendered).contains("01"),
                    () -> assertThat(rendered).contains("0005"),
                    () -> assertThat(rendered).contains(PADDED_SOURCE),
                    () -> assertThat(rendered).doesNotContain(PADDED_ID),
                    () -> assertThat(rendered).doesNotContain("4111111111111111"),
                    () -> assertThat(rendered).doesNotContain("98109"));
        }

        @Test
        @DisplayName("the vestigial modified flag is always clear, as the source leaves it")
        void vestigialModifiedFlagIsAlwaysClear() {
            when(transactionRepository.findById(PADDED_ID)).thenReturn(Optional.of(storedRow()));

            assertThat(submit(PADDED_ID, KeyAction.ENTER).userModified()).isFalse();
        }

        @Test
        @DisplayName("the error flag reports both of its two states")
        void errorFlagReportsBothOfItsStates() {
            assertAll(
                    () -> assertThat(TransactionViewService.ErrorFlag.ON.isOn()).isTrue(),
                    () -> assertThat(TransactionViewService.ErrorFlag.ON.isOff()).isFalse(),
                    () -> assertThat(TransactionViewService.ErrorFlag.OFF.isOn()).isFalse(),
                    () -> assertThat(TransactionViewService.ErrorFlag.OFF.isOff()).isTrue(),
                    () -> assertThat(TransactionViewService.ErrorFlag.values()).hasSize(2));
        }

        @Test
        @DisplayName("the modified flag declares the legacy two-state domain, raised state included")
        void modifiedFlagDeclaresTheLegacyTwoStateDomain() {
            assertAll(
                    () -> assertThat(TransactionViewService.UserModifiedFlag.YES.isYes()).isTrue(),
                    () -> assertThat(TransactionViewService.UserModifiedFlag.NO.isYes()).isFalse(),
                    () -> assertThat(TransactionViewService.UserModifiedFlag.values()).hasSize(2));
        }

        @Test
        @DisplayName("the header record carries its six components verbatim")
        void headerRecordCarriesItsSixComponentsVerbatim() {
            final TransactionViewService.ScreenHeader header =
                    new TransactionViewService.ScreenHeader(SCREEN_TITLE_01, SCREEN_TITLE_02,
                            THIS_TRANSACTION_ID, THIS_PROGRAM_NAME, EXPECTED_HEADER_DATE,
                            EXPECTED_HEADER_TIME);

            assertAll(() -> assertThat(header.title01()).isEqualTo(SCREEN_TITLE_01),
                    () -> assertThat(header.title02()).isEqualTo(SCREEN_TITLE_02),
                    () -> assertThat(header.transactionName()).isEqualTo(THIS_TRANSACTION_ID),
                    () -> assertThat(header.programName()).isEqualTo(THIS_PROGRAM_NAME),
                    () -> assertThat(header.currentDate()).isEqualTo(EXPECTED_HEADER_DATE),
                    () -> assertThat(header.currentTime()).isEqualTo(EXPECTED_HEADER_TIME));
        }

        @Test
        @DisplayName("two turns of the same shape agree, because the outcome is a value")
        void twoTurnsOfTheSameShapeAgree() {
            when(transactionRepository.findById(PADDED_ID)).thenReturn(Optional.of(storedRow()));

            final TransactionViewService.TransactionViewResult first =
                    submit(PADDED_ID, KeyAction.ENTER);
            final TransactionViewService.TransactionViewResult second =
                    submit(PADDED_ID, KeyAction.ENTER);

            assertAll(() -> assertThat(first).isEqualTo(second),
                    () -> assertThat(first.transaction()).isEqualTo(second.transaction()));
        }
    }


    // ==============================================================================================
    // Paragraph traceability. One test per paragraph of the legacy member, in source order, so all
    // nine rows of the paragraph traceability matrix are attributable to a named test here.
    // ==============================================================================================

    @Nested
    @DisplayName("Paragraph traceability - all nine paragraphs of the legacy member, in source order")
    class ParagraphTraceability {

        /**
         * Paragraph 1 of 9, {@code MAIN-PARA} at line 86: both flags are cleared and both message fields
         * blanked, and the turn then branches on the re-enter gate. The gate is raised before anything
         * else happens on a first entry, which is why per-field detail can only ever accompany a raised
         * gate.
         */
        @Test
        @DisplayName("paragraph 1 of 9, MAIN-PARA at line 86: clears both flags and raises the gate")
        void mainParaClearsBothFlagsAndRaisesTheGate() {
            final TransactionViewService.TransactionViewResult result = service.viewTransaction(
                    new TransactionViewService.TransactionViewInput(null, null, null,
                            firstEntryState()));

            assertAll(() -> assertThat(result.errorFlag()).isFalse(),
                    () -> assertThat(result.userModified()).isFalse(),
                    () -> assertThat(result.message()).isEmpty(),
                    () -> assertThat(result.reEntry()).isTrue(),
                    () -> assertThat(result.focusField()).isEqualTo(CURSOR_FIELD));
        }

        /**
         * Paragraph 1 of 9 again, for the precedence rule the first-entry branch carries: an identifier
         * the transaction-list screen handed forward wins over the transmitted search field, because the
         * source moves the selection into that field before running the enter-key path.
         */
        @Test
        @DisplayName("paragraph 1 of 9, MAIN-PARA: a carried selection wins over the transmitted field")
        void mainParaPrefersTheCarriedSelection() {
            when(transactionRepository.findById(OTHER_PADDED_ID))
                    .thenReturn(Optional.of(storedRow()));

            final TransactionViewService.TransactionViewResult result = service.viewTransaction(
                    new TransactionViewService.TransactionViewInput(PADDED_ID, OTHER_PADDED_ID, null,
                            firstEntryState()));

            assertAll(() -> assertThat(result.searchTransactionId()).isEqualTo(OTHER_PADDED_ID),
                    () -> assertThat(result.retrievedTransaction()).isPresent(),
                    () -> assertThat(result.reEntry()).isTrue());
            verify(transactionRepository).findById(OTHER_PADDED_ID);
            verify(transactionRepository, never()).findById(PADDED_ID);
        }

        /**
         * Paragraph 2 of 9, {@code PROCESS-ENTER-KEY} at line 144: three sentences, each gated on the
         * error flag. A blank identifier stops at the edit; a well-formed identifier that names no row
         * reaches the read and stops there; a well-formed identifier that names a row reaches the
         * publish.
         */
        @Test
        @DisplayName("paragraph 2 of 9, PROCESS-ENTER-KEY at line 144: edit, then read, then publish")
        void processEnterKeyEditsThenReadsThenPublishes() {
            when(transactionRepository.findById(PADDED_ID)).thenReturn(Optional.of(storedRow()));
            when(transactionRepository.findById(OTHER_PADDED_ID)).thenReturn(Optional.empty());

            final TransactionViewService.TransactionViewResult stoppedAtEdit =
                    submit("", KeyAction.ENTER);
            final TransactionViewService.TransactionViewResult stoppedAtRead =
                    submit(OTHER_PADDED_ID, KeyAction.ENTER);
            final TransactionViewService.TransactionViewResult published =
                    submit(PADDED_ID, KeyAction.ENTER);

            assertAll(
                    () -> assertThat(stoppedAtEdit.message()).isEqualTo(MSG_TRAN_ID_EMPTY),
                    () -> assertThat(stoppedAtEdit.fieldErrors()).hasSize(1),
                    () -> assertThat(stoppedAtRead.message()).isEqualTo(MSG_TRAN_ID_NOT_FOUND),
                    () -> assertThat(stoppedAtRead.transaction()).isNull(),
                    () -> assertThat(stoppedAtRead.fieldErrors()).isEmpty(),
                    () -> assertThat(published.message()).isEmpty(),
                    () -> assertThat(published.transaction()).isNotNull(),
                    () -> assertThat(published.errorFlag()).isFalse());
        }

        /**
         * Paragraph 3 of 9, {@code RETURN-TO-PREV-SCREEN} at line 197: the transfer stamps this screen as
         * the originator, resets the program context so the destination sees a first entry, and re-arms no
         * transaction, because a transfer leaves the program.
         */
        @Test
        @DisplayName("paragraph 3 of 9, RETURN-TO-PREV-SCREEN at line 197: stamps and transfers")
        void returnToPrevScreenStampsTheOriginatorAndTransfers() {
            when(navigationService.resolveNominatedDestination(any(),
                    eq(NavigationService.Route.SIGN_ON)))
                    .thenReturn(NavigationService.Route.TRANSACTION_LIST);

            final TransactionViewService.TransactionViewResult result =
                    submit(PADDED_ID, KeyAction.PFK05);

            assertAll(() -> assertThat(result.navigationContext().fromProgram())
                            .isEqualTo(THIS_PROGRAM_NAME),
                    () -> assertThat(result.navigationContext().fromTransactionId())
                            .isEqualTo(THIS_TRANSACTION_ID),
                    () -> assertThat(result.navigationContext().toProgram()).isEqualTo("COTRN00C"),
                    () -> assertThat(result.navigationContext().firstEntry()).isTrue(),
                    () -> assertThat(result.reArmedTransactionId()).isEmpty(),
                    () -> assertThat(result.focusField()).isEmpty());
        }

        /**
         * Paragraph 4 of 9, {@code SEND-TRNVIEW-SCREEN} at line 213: every send populates the header
         * first, so a turn that sent carries one and a turn that transferred without sending carries
         * none.
         */
        @Test
        @DisplayName("paragraph 4 of 9, SEND-TRNVIEW-SCREEN at line 213: a send populates the header")
        void sendTrnviewScreenPopulatesTheHeader() {
            when(navigationService.resolveNominatedDestination(any(),
                    eq(NavigationService.Route.SIGN_ON)))
                    .thenReturn(NavigationService.Route.TRANSACTION_LIST);

            final TransactionViewService.TransactionViewResult sent = service.viewTransaction(
                    new TransactionViewService.TransactionViewInput(null, null, null,
                            firstEntryState()));
            final TransactionViewService.TransactionViewResult transferred =
                    submit(PADDED_ID, KeyAction.PFK05);

            assertAll(() -> assertThat(sent.header()).isNotNull(),
                    () -> assertThat(sent.header().currentDate()).isEqualTo(EXPECTED_HEADER_DATE),
                    () -> assertThat(transferred.header()).isNull());
        }

        /**
         * Paragraph 5 of 9, {@code RECEIVE-TRNVIEW-SCREEN} at line 230: the transmitted search field is
         * taken on a re-entry and is not consulted at all on a first entry, because the source receives a
         * map only on a re-entry.
         */
        @Test
        @DisplayName("paragraph 5 of 9, RECEIVE-TRNVIEW-SCREEN at line 230: takes the transmitted field")
        void receiveTrnviewScreenTakesTheTransmittedField() {
            final TransactionViewService.TransactionViewResult onReEntry =
                    submit(PADDED_ID, KeyAction.ENTER);
            final TransactionViewService.TransactionViewResult onFirstEntry =
                    service.viewTransaction(new TransactionViewService.TransactionViewInput(
                            PADDED_ID, null, null, firstEntryState()));

            assertAll(() -> assertThat(onReEntry.searchTransactionId()).isEqualTo(PADDED_ID),
                    () -> assertThat(onFirstEntry.searchTransactionId()).isEmpty(),
                    () -> assertThat(onFirstEntry.message()).isEmpty());
            verify(transactionRepository, times(1)).findById(PADDED_ID);
        }

        /**
         * Paragraph 6 of 9, {@code POPULATE-HEADER-INFO} at line 243: one clock reading yields both
         * derived components, so the rendered date and time always describe the same instant rather than
         * straddling a second boundary.
         */
        @Test
        @DisplayName("paragraph 6 of 9, POPULATE-HEADER-INFO at line 243: one reading, both components")
        void populateHeaderInfoTakesOneClockReading() {
            when(messageCatalogService.screenTitle01()).thenReturn(SCREEN_TITLE_01);
            when(messageCatalogService.screenTitle02()).thenReturn(SCREEN_TITLE_02);

            final TransactionViewService.ScreenHeader header = service.viewTransaction(
                    new TransactionViewService.TransactionViewInput(null, null, null,
                            firstEntryState())).header();

            assertAll(() -> assertThat(header.currentDate()).isEqualTo(EXPECTED_HEADER_DATE),
                    () -> assertThat(header.currentTime()).isEqualTo(EXPECTED_HEADER_TIME),
                    () -> assertThat(header.title01()).isEqualTo(SCREEN_TITLE_01),
                    () -> assertThat(header.title02()).isEqualTo(SCREEN_TITLE_02),
                    () -> assertThat(header.transactionName()).isEqualTo(THIS_TRANSACTION_ID),
                    () -> assertThat(header.programName()).isEqualTo(THIS_PROGRAM_NAME));
        }

        /**
         * Paragraph 7 of 9, {@code READ-TRANSACT-FILE} at line 267: one keyed read and a three-armed
         * evaluation of its outcome, in the source's own clause order. The normal arm carries the record
         * forward, the not-found arm reports the record-absent text, and the catch-all reports the
         * lookup-failure text.
         */
        @Test
        @DisplayName("paragraph 7 of 9, READ-TRANSACT-FILE at line 267: three arms, in clause order")
        void readTransactFileHasThreeOutcomeArms() {
            when(transactionRepository.findById(PADDED_ID)).thenReturn(Optional.of(storedRow()));
            when(transactionRepository.findById(OTHER_PADDED_ID)).thenReturn(Optional.empty());
            when(transactionRepository.findById(ALPHANUMERIC_ID))
                    .thenThrow(new DataAccessResourceFailureException("the file is unavailable"));

            final TransactionViewService.TransactionViewResult normal =
                    submit(PADDED_ID, KeyAction.ENTER);
            final TransactionViewService.TransactionViewResult notFound =
                    submit(OTHER_PADDED_ID, KeyAction.ENTER);
            final TransactionViewService.TransactionViewResult failed =
                    submit(ALPHANUMERIC_ID, KeyAction.ENTER);

            assertAll(() -> assertThat(normal.retrievedTransaction()).isPresent(),
                    () -> assertThat(normal.message()).isEmpty(),
                    () -> assertThat(notFound.retrievedTransaction()).isEmpty(),
                    () -> assertThat(notFound.message()).isEqualTo(MSG_TRAN_ID_NOT_FOUND),
                    () -> assertThat(failed.retrievedTransaction()).isEmpty(),
                    () -> assertThat(failed.message()).isEqualTo(MSG_LOOKUP_FAILED));
        }

        /**
         * Paragraph 8 of 9, {@code CLEAR-CURRENT-SCREEN} at line 301: two performed statements, a reset
         * and a send, reached from the fourth program-function key alone.
         */
        @Test
        @DisplayName("paragraph 8 of 9, CLEAR-CURRENT-SCREEN at line 301: resets, then sends")
        void clearCurrentScreenResetsThenSends() {
            final TransactionViewService.TransactionViewResult result =
                    submit(PADDED_ID, KeyAction.PFK04);

            assertAll(() -> assertThat(result.header()).isNotNull(),
                    () -> assertThat(result.searchTransactionId()).isEmpty(),
                    () -> assertThat(result.transaction()).isNull(),
                    () -> assertThat(result.route())
                            .isEqualTo(NavigationService.Route.TRANSACTION_VIEW),
                    () -> assertThat(result.reArmedTransactionId()).isEqualTo(THIS_TRANSACTION_ID));
            verifyNoInteractions(transactionRepository);
            verifyNoInteractions(navigationService);
        }

        /**
         * Paragraph 9 of 9, {@code INITIALIZE-ALL-FIELDS} at line 309: the cursor is positioned on the
         * search field and the search field, the thirteen display fields and the message work field are
         * all blanked &mdash; which is what leaves the clear key with a screen carrying no text at all,
         * even after a turn that had been reporting a failure.
         */
        @Test
        @DisplayName("paragraph 9 of 9, INITIALIZE-ALL-FIELDS at line 309: blanks every field and the text")
        void initializeAllFieldsBlanksEveryFieldAndTheText() {
            final TransactionViewService.TransactionViewResult result =
                    submit(OVERLONG_ID, KeyAction.PFK04);

            assertAll(() -> assertThat(result.searchTransactionId()).isEmpty(),
                    () -> assertThat(result.message()).isEmpty(),
                    () -> assertThat(result.transaction()).isNull(),
                    () -> assertThat(result.retrievedTransaction()).isEmpty(),
                    () -> assertThat(result.focusField()).isEqualTo(CURSOR_FIELD),
                    () -> assertThat(result.errorFlag()).isFalse(),
                    () -> assertThat(result.fieldErrors()).isEmpty());
        }
    }

}
