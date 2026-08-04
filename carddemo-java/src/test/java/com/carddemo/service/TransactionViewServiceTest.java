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
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.api.dto.NavigationContext;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.TransactionRepository;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Unit test for {@link TransactionViewService}, the translation of the transaction-view transaction
 * {@code CT01} carried by {@code app/cbl/COTRN01C.cbl}.
 *
 * <p><strong>What is under test.</strong> One pseudo-conversational turn of a screen whose whole job is
 * a single keyed read. The service decides, from the navigation state and the attention key alone,
 * whether to route away without a screen, send an empty screen for a first entry, follow a selection
 * carried forward from the transaction list, or receive and process a submitted identifier.
 *
 * <p><strong>The interesting assertions are about representation, not about control flow.</strong> This
 * member is the smallest online translation in the package and its risk is quiet type drift: an
 * identifier parsed to a number, an amount rounded instead of truncated, a timestamp reformatted by a
 * date-time library, a padded code trimmed. Each of those would compile, would look right, and would
 * break byte parity. The {@code TypeFidelity} group below is therefore the core of this suite, and
 * every one of its assertions is written to fail if the corresponding shortcut is ever taken:
 *
 * <ul>
 *   <li>a zero-padded identifier reads a row while its unpadded form reads nothing, which is the
 *       assertion that catches a numeric key;</li>
 *   <li>a stored online-format stamp and a stored batch-format stamp both come back byte for byte,
 *       which is the assertion that catches a temporal conversion;</li>
 *   <li>a space-padded source code keeps its padding, which is the assertion that catches a trim;</li>
 *   <li>a surplus fractional digit is truncated and never rounded up, which is the assertion that
 *       catches the conventional rounding mode.</li>
 * </ul>
 *
 * <p><strong>Collaborators.</strong> The message catalogue and the navigation rules are the real
 * classes, because each is a pure decision this service must agree with rather than a boundary to be
 * stubbed - substituting them would let this suite pass while the module disagreed with itself. Only
 * the repository is a test double, because it is the data boundary. The clock is fixed so the rendered
 * header is deterministic.
 *
 * <p><strong>The two 26-character timestamp forms are authored here rather than imported.</strong> The
 * online form carries a space between date and time, colons inside the time and a six-digit fraction;
 * the batch form carries a hyphen before the hour, dots between the time parts and two hundredths
 * digits followed by four literal zeros, which is the layout
 * {@code app/cbl/CBTRN02C.cbl} lines 159 to 174 declares. Writing them out means an assertion that
 * they are unchanged compares against characters this file owns, not against anything the production
 * code could also have altered.
 */
@DisplayName("TransactionViewService - transaction-view screen CT01")
class TransactionViewServiceTest {

    /** A stored identifier at its full sixteen characters, zero padded exactly as the estate stores it. */
    private static final String PADDED_ID = "0000000000000001";

    /** The same identifier with its padding stripped: a different key, and one that reads nothing. */
    private static final String UNPADDED_ID = "1";

    /** A second stored identifier, used where a test needs two distinct rows. */
    private static final String OTHER_ID = "0000000000000002";

    /** Seventeen characters: one wider than the key space, so it can name no row. */
    private static final String OVERLONG_ID = "00000000000000012";

    /**
     * The online 26-character timestamp form: space separator, colons in the time, six fractional
     * digits.
     */
    private static final String ONLINE_TIMESTAMP = "2022-04-15 11:22:33.123456";

    /**
     * The batch 26-character timestamp form: hyphen before the hour, dots between the time parts, two
     * hundredths digits then four literal zeros.
     */
    private static final String BATCH_TIMESTAMP = "2022-01-02-03.04.05.060000";

    /** {@code TRAN-SOURCE} as stored: ten characters, so a six-character value carries four spaces. */
    private static final String PADDED_SOURCE = "POS TERM  ";

    /** The instant the fixed clock reports, chosen so the rendered header is unambiguous. */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-19T14:23:45.123456Z");

    /** The header date the fixed instant renders to, in {@code MM/DD/YY} form. */
    private static final String EXPECTED_HEADER_DATE = "07/19/22";

    /** The header time the fixed instant renders to, in {@code HH:MM:SS} form. */
    private static final String EXPECTED_HEADER_TIME = "14:23:45";

    /** The screen field every cursor position in this member targets. */
    private static final String CURSOR_FIELD = "TRNIDIN";

    /** The property name the per-field detail carries. */
    private static final String CURSOR_PROPERTY = "transactionId";

    /** {@code 'Tran ID can NOT be empty...'} at {@code app/cbl/COTRN01C.cbl} line 149. */
    private static final String MSG_EMPTY = "Tran ID can NOT be empty...";

    /** {@code 'Transaction ID NOT found...'} at {@code app/cbl/COTRN01C.cbl} line 285. */
    private static final String MSG_NOT_FOUND = "Transaction ID NOT found...";

    /** {@code 'Unable to lookup Transaction...'} at {@code app/cbl/COTRN01C.cbl} line 292. */
    private static final String MSG_LOOKUP_FAILED = "Unable to lookup Transaction...";

    /** The contractual width of the common invalid-key message. */
    private static final int COMMON_MESSAGE_BYTES = 50;

    private TransactionRepository repository;

    private TransactionViewService service;

    @BeforeEach
    void setUp() {
        this.repository = Mockito.mock(TransactionRepository.class);
        // Every unstubbed key reads nothing, which is the not-found response of the legacy read. The
        // specific stubs below are declared afterwards so they take precedence for their own keys.
        Mockito.when(this.repository.findById(ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());
        Mockito.when(this.repository.findById(PADDED_ID)).thenReturn(Optional.of(storedTransaction()));
        this.service = new TransactionViewService(this.repository,
                new MessageCatalogService(),
                new NavigationService(),
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
    }

    /**
     * Builds the stored row every retrieval test reads, at the record's own widths.
     *
     * @return a transaction carrying the padded identifier, both timestamp forms and a padded source
     */
    private static Transaction storedTransaction() {
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
     * Builds the navigation state of a turn that has already presented the screen once, which is the
     * state every attention key is dispatched from.
     *
     * @param fromProgram the originating-program field, which back-navigation reads
     * @return a re-entered navigation state
     */
    private static NavigationContext reEnteredContext(final String fromProgram) {
        return new NavigationContext(null, fromProgram, null, null, "USER0001", "U",
                NavigationContext.ProgramContext.REENTER,
                null, null, null, null, null, null, null, null, null);
    }

    /**
     * Builds the navigation state of a turn arriving for the first time.
     *
     * @return a first-entry navigation state
     */
    private static NavigationContext firstEntryContext() {
        return new NavigationContext(null, null, null, null, "USER0001", "U",
                NavigationContext.ProgramContext.ENTER,
                null, null, null, null, null, null, null, null, null);
    }

    @Nested
    @DisplayName("Construction and the entry contract")
    class ConstructionContract {

        @Test
        @DisplayName("rejects an absent repository")
        void rejectsAbsentRepository() {
            assertThatNullPointerException().isThrownBy(() -> new TransactionViewService(null,
                    new MessageCatalogService(), new NavigationService(),
                    Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)));
        }

        @Test
        @DisplayName("rejects an absent message catalogue")
        void rejectsAbsentCatalogue() {
            assertThatNullPointerException().isThrownBy(() -> new TransactionViewService(repository,
                    null, new NavigationService(), Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)));
        }

        @Test
        @DisplayName("rejects absent navigation rules")
        void rejectsAbsentNavigation() {
            assertThatNullPointerException().isThrownBy(() -> new TransactionViewService(repository,
                    new MessageCatalogService(), null, Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)));
        }

        @Test
        @DisplayName("rejects an absent clock")
        void rejectsAbsentClock() {
            assertThatNullPointerException().isThrownBy(() -> new TransactionViewService(repository,
                    new MessageCatalogService(), new NavigationService(), null));
        }

        @Test
        @DisplayName("rejects an absent turn")
        void rejectsAbsentInput() {
            assertThatNullPointerException().isThrownBy(() -> service.viewTransaction(null));
        }
    }

    @Nested
    @DisplayName("A turn carrying no navigation state, line 94")
    class AbsentNavigationState {

        @Test
        @DisplayName("routes to sign-on and re-arms nothing when the state is absent")
        void routesAbsentStateToSignOn() {
            final TransactionViewService.TransactionViewResult result =
                    service.viewTransaction(new TransactionViewService.TransactionViewInput(
                            PADDED_ID, null, KeyAction.ENTER, null));

            assertThat(result.route()).isEqualTo(NavigationService.Route.SIGN_ON);
            assertThat(result.reArmedTransactionId()).isEmpty();
            assertThat(result.header()).isNull();
            assertThat(result.transaction()).isNull();
            assertThat(result.errorFlag()).isFalse();
            assertThat(result.fieldErrors()).isEmpty();
            assertThat(result.navigationContext().fromProgram()).isEqualTo("COTRN01C");
            assertThat(result.navigationContext().fromTransactionId()).isEqualTo("CT01");
            assertThat(result.navigationContext().firstEntry()).isTrue();
            Mockito.verify(repository, Mockito.never()).findById(ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("treats a wholly empty state as absent, exactly as a zero-length area")
        void routesEmptyStateToSignOn() {
            final TransactionViewService.TransactionViewResult result =
                    service.viewTransaction(new TransactionViewService.TransactionViewInput(
                            PADDED_ID, PADDED_ID, KeyAction.ENTER, NavigationContext.empty()));

            assertThat(result.route()).isEqualTo(NavigationService.Route.SIGN_ON);
            assertThat(result.reArmedTransactionId()).isEmpty();
            Mockito.verify(repository, Mockito.never()).findById(ArgumentMatchers.anyString());
        }
    }

    @Nested
    @DisplayName("First entry, lines 99 to 109")
    class FirstEntry {

        @Test
        @DisplayName("sends an empty screen and reads nothing when no selection was carried")
        void sendsEmptyScreenWithoutSelection() {
            final TransactionViewService.TransactionViewResult result =
                    service.viewTransaction(new TransactionViewService.TransactionViewInput(
                            null, null, null, firstEntryContext()));

            assertThat(result.route()).isEqualTo(NavigationService.Route.TRANSACTION_VIEW);
            assertThat(result.reArmedTransactionId()).isEqualTo("CT01");
            assertThat(result.reEntry()).isTrue();
            assertThat(result.searchTransactionId()).isEmpty();
            assertThat(result.transaction()).isNull();
            assertThat(result.retrievedTransaction()).isEmpty();
            assertThat(result.message()).isEmpty();
            assertThat(result.focusField()).isEqualTo(CURSOR_FIELD);
            assertThat(result.errorFlag()).isFalse();
            assertThat(result.fieldErrors()).isEmpty();
            Mockito.verify(repository, Mockito.never()).findById(ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("ignores a transmitted search field on a first entry, because no map is received")
        void ignoresTransmittedFieldOnFirstEntry() {
            final TransactionViewService.TransactionViewResult result =
                    service.viewTransaction(new TransactionViewService.TransactionViewInput(
                            PADDED_ID, null, null, firstEntryContext()));

            assertThat(result.searchTransactionId()).isEmpty();
            assertThat(result.transaction()).isNull();
            assertThat(result.message()).isEmpty();
            Mockito.verify(repository, Mockito.never()).findById(ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("follows a carried selection and publishes the row on the very first turn")
        void followsCarriedSelection() {
            final TransactionViewService.TransactionViewResult result =
                    service.viewTransaction(new TransactionViewService.TransactionViewInput(
                            null, PADDED_ID, null, firstEntryContext()));

            assertThat(result.searchTransactionId()).isEqualTo(PADDED_ID);
            assertThat(result.retrievedTransaction()).isPresent();
            assertThat(result.transaction().transactionId()).isEqualTo(PADDED_ID);
            assertThat(result.message()).isEmpty();
            assertThat(result.errorFlag()).isFalse();
            assertThat(result.route()).isEqualTo(NavigationService.Route.TRANSACTION_VIEW);
            Mockito.verify(repository).findById(PADDED_ID);
        }

        @Test
        @DisplayName("the carried selection takes precedence over a transmitted search field")
        void carriedSelectionWinsOverTransmittedField() {
            final TransactionViewService.TransactionViewResult result =
                    service.viewTransaction(new TransactionViewService.TransactionViewInput(
                            OTHER_ID, PADDED_ID, null, firstEntryContext()));

            assertThat(result.searchTransactionId()).isEqualTo(PADDED_ID);
            assertThat(result.retrievedTransaction()).isPresent();
            Mockito.verify(repository).findById(PADDED_ID);
            Mockito.verify(repository, Mockito.never()).findById(OTHER_ID);
        }

        @Test
        @DisplayName("reports the record-absent message when a carried selection names no row")
        void reportsAbsentRowForCarriedSelection() {
            final TransactionViewService.TransactionViewResult result =
                    service.viewTransaction(new TransactionViewService.TransactionViewInput(
                            null, OTHER_ID, null, firstEntryContext()));

            assertThat(result.message()).isEqualTo(MSG_NOT_FOUND);
            assertThat(result.errorFlag()).isTrue();
            assertThat(result.transaction()).isNull();
            assertThat(result.searchTransactionId()).isEqualTo(OTHER_ID);
            assertThat(result.focusField()).isEqualTo(CURSOR_FIELD);
            assertThat(result.fieldErrors()).isEmpty();
        }

        @ParameterizedTest(name = "a selection of [{0}] counts as no selection")
        @ValueSource(strings = {"", "  ", "                ", "\u0000", "\u0000\u0000\u0000"})
        @DisplayName("treats a padding-only selection as absent, per the combined relation at line 103")
        void treatsPaddingOnlySelectionAsAbsent(final String selection) {
            final TransactionViewService.TransactionViewResult result =
                    service.viewTransaction(new TransactionViewService.TransactionViewInput(
                            null, selection, null, firstEntryContext()));

            assertThat(result.message()).isEmpty();
            assertThat(result.errorFlag()).isFalse();
            assertThat(result.transaction()).isNull();
            Mockito.verify(repository, Mockito.never()).findById(ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("raises the re-enter gate before the selection is read, per line 100 against line 107")
        void raisesGateBeforeReadingSelection() {
            final TransactionViewService.TransactionViewResult result =
                    service.viewTransaction(new TransactionViewService.TransactionViewInput(
                            null, PADDED_ID, null, firstEntryContext()));

            // Per-field detail can only accompany a raised gate, and the gate is raised on this path too.
            assertThat(result.reEntry()).isTrue();
            assertThat(result.navigationContext().reEntry()).isTrue();
        }
    }

    @Nested
    @DisplayName("The attention-key evaluation, lines 112 to 132")
    class AttentionKeys {

        @Test
        @DisplayName("the enter key reads the transmitted search field")
        void enterKeyReadsTransmittedField() {
            final TransactionViewService.TransactionViewResult result =
                    service.viewTransaction(new TransactionViewService.TransactionViewInput(
                            PADDED_ID, null, KeyAction.ENTER, reEnteredContext(null)));

            assertThat(result.retrievedTransaction()).isPresent();
            assertThat(result.searchTransactionId()).isEqualTo(PADDED_ID);
            assertThat(result.errorFlag()).isFalse();
            Mockito.verify(repository).findById(PADDED_ID);
        }

        @Test
        @DisplayName("the enter key ignores a selection on a re-entry, because the source does not re-read it")
        void enterKeyIgnoresSelectionOnReEntry() {
            final TransactionViewService.TransactionViewResult result =
                    service.viewTransaction(new TransactionViewService.TransactionViewInput(
                            OTHER_ID, PADDED_ID, KeyAction.ENTER, reEnteredContext(null)));

            assertThat(result.searchTransactionId()).isEqualTo(OTHER_ID);
            assertThat(result.message()).isEqualTo(MSG_NOT_FOUND);
            Mockito.verify(repository).findById(OTHER_ID);
            Mockito.verify(repository, Mockito.never()).findById(PADDED_ID);
        }

        @Test
        @DisplayName("the third function key returns to this screen's own default when nothing originated it")
        void backNavigationFallsBackToUserMenu() {
            final TransactionViewService.TransactionViewResult result =
                    service.viewTransaction(new TransactionViewService.TransactionViewInput(
                            null, null, KeyAction.PFK03, reEnteredContext("        ")));

            assertThat(result.route()).isEqualTo(NavigationService.Route.USER_MENU);
            assertThat(result.reArmedTransactionId()).isEmpty();
            assertThat(result.navigationContext().fromProgram()).isEqualTo("COTRN01C");
            assertThat(result.navigationContext().firstEntry()).isTrue();
            assertThat(result.header()).isNull();
        }

        @Test
        @DisplayName("the third function key prefers the originating program when the state names one")
        void backNavigationPrefersOriginatingProgram() {
            final TransactionViewService.TransactionViewResult result =
                    service.viewTransaction(new TransactionViewService.TransactionViewInput(
                            null, null, KeyAction.PFK03, reEnteredContext("COTRN00C")));

            assertThat(result.route()).isEqualTo(NavigationService.Route.TRANSACTION_LIST);
            assertThat(result.reArmedTransactionId()).isEmpty();
        }

        @Test
        @DisplayName("the fourth function key clears every field including the search field, line 312")
        void clearKeyBlanksEveryField() {
            final TransactionViewService.TransactionViewResult result =
                    service.viewTransaction(new TransactionViewService.TransactionViewInput(
                            PADDED_ID, null, KeyAction.PFK04, reEnteredContext("COTRN00C")));

            assertThat(result.searchTransactionId()).isEmpty();
            assertThat(result.transaction()).isNull();
            assertThat(result.message()).isEmpty();
            assertThat(result.errorFlag()).isFalse();
            assertThat(result.focusField()).isEqualTo(CURSOR_FIELD);
            assertThat(result.route()).isEqualTo(NavigationService.Route.TRANSACTION_VIEW);
            assertThat(result.reArmedTransactionId()).isEqualTo("CT01");
            assertThat(result.header()).isNotNull();
            Mockito.verify(repository, Mockito.never()).findById(ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("the fifth function key returns to the transaction list, line 126")
        void fifthFunctionKeyReturnsToList() {
            final TransactionViewService.TransactionViewResult result =
                    service.viewTransaction(new TransactionViewService.TransactionViewInput(
                            null, null, KeyAction.PFK05, reEnteredContext(null)));

            assertThat(result.route()).isEqualTo(NavigationService.Route.TRANSACTION_LIST);
            assertThat(result.reArmedTransactionId()).isEmpty();
        }

        @ParameterizedTest(name = "{0} is unmapped and produces the invalid-key message")
        @EnumSource(value = KeyAction.class,
                names = {"CLEAR", "PA1", "PA2", "PFK01", "PFK02", "PFK06", "PFK07", "PFK08",
                        "PFK09", "PFK10", "PFK11", "PFK12"})
        @DisplayName("every key without an arm of its own reaches the catch-all, lines 128 to 131")
        void unmappedKeysReachCatchAll(final KeyAction keyAction) {
            final TransactionViewService.TransactionViewResult result =
                    service.viewTransaction(new TransactionViewService.TransactionViewInput(
                            PADDED_ID, null, keyAction, reEnteredContext(null)));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).isEqualTo(new MessageCatalogService().invalidKeyMessage());
            assertThat(result.focusField()).isEqualTo(CURSOR_FIELD);
            assertThat(result.route()).isEqualTo(NavigationService.Route.TRANSACTION_VIEW);
            assertThat(result.fieldErrors()).isEmpty();
            Mockito.verify(repository, Mockito.never()).findById(ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("an undecoded key reaches the catch-all, because it is none of the four named")
        void undecodedKeyReachesCatchAll() {
            final TransactionViewService.TransactionViewResult result =
                    service.viewTransaction(new TransactionViewService.TransactionViewInput(
                            PADDED_ID, null, null, reEnteredContext(null)));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).isEqualTo(new MessageCatalogService().invalidKeyMessage());
        }

        @Test
        @DisplayName("the invalid-key message is exactly 50 encoded bytes and is never trimmed")
        void invalidKeyMessageKeepsItsContractualWidth() {
            final TransactionViewService.TransactionViewResult result =
                    service.viewTransaction(new TransactionViewService.TransactionViewInput(
                            PADDED_ID, null, KeyAction.PFK01, reEnteredContext(null)));

            assertThat(result.message().getBytes(StandardCharsets.UTF_8))
                    .hasSize(COMMON_MESSAGE_BYTES);
            assertThat(result.message()).hasSize(COMMON_MESSAGE_BYTES).endsWith(" ");
        }
    }

    @Nested
    @DisplayName("The enter-key edits, lines 146 to 156")
    class EnterKeyEdits {

        @ParameterizedTest(name = "an identifier of [{0}] is reported as not supplied")
        @ValueSource(strings = {"", " ", "                ", "\u0000", "  \u0000  "})
        @DisplayName("a padding-only identifier is MISSING and reads nothing, lines 147 to 152")
        void blankIdentifierIsMissing(final String submitted) {
            final TransactionViewService.TransactionViewResult result =
                    service.viewTransaction(new TransactionViewService.TransactionViewInput(
                            submitted, null, KeyAction.ENTER, reEnteredContext(null)));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).isEqualTo(MSG_EMPTY);
            assertThat(result.focusField()).isEqualTo(CURSOR_FIELD);
            assertThat(result.transaction()).isNull();
            assertThat(result.fieldErrors()).singleElement()
                    .satisfies(error -> {
                        assertThat(error.field()).isEqualTo(CURSOR_PROPERTY);
                        assertThat(error.bmsFieldId()).isEqualTo(CURSOR_FIELD);
                        assertThat(error.state()).isEqualTo(ValidationException.FieldState.MISSING);
                        assertThat(error.message()).isEqualTo(MSG_EMPTY);
                    });
            Mockito.verify(repository, Mockito.never()).findById(ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("an absent identifier is MISSING, because an untransmitted field is blank")
        void absentIdentifierIsMissing() {
            final TransactionViewService.TransactionViewResult result =
                    service.viewTransaction(new TransactionViewService.TransactionViewInput(
                            null, null, KeyAction.ENTER, reEnteredContext(null)));

            assertThat(result.message()).isEqualTo(MSG_EMPTY);
            assertThat(result.fieldErrors()).singleElement()
                    .extracting(ValidationException.FieldError::state)
                    .isEqualTo(ValidationException.FieldState.MISSING);
        }

        @Test
        @DisplayName("an identifier wider than the key space is INVALID and is refused, never cut")
        void overlongIdentifierIsInvalidAndRefused() {
            final TransactionViewService.TransactionViewResult result =
                    service.viewTransaction(new TransactionViewService.TransactionViewInput(
                            OVERLONG_ID, null, KeyAction.ENTER, reEnteredContext(null)));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).isEqualTo(MSG_NOT_FOUND);
            assertThat(result.transaction()).isNull();
            assertThat(result.fieldErrors()).singleElement()
                    .extracting(ValidationException.FieldError::state)
                    .isEqualTo(ValidationException.FieldState.INVALID);

            // The whole point of refusing: cutting to sixteen characters would have matched the stored
            // row and returned a DIFFERENT transaction than the one asked for.
            assertThat(OVERLONG_ID).startsWith(PADDED_ID);
            Mockito.verify(repository, Mockito.never()).findById(ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("an identifier at exactly the key width is accepted")
        void identifierAtKeyWidthIsAccepted() {
            final TransactionViewService.TransactionViewResult result =
                    service.viewTransaction(new TransactionViewService.TransactionViewInput(
                            PADDED_ID, null, KeyAction.ENTER, reEnteredContext(null)));

            assertThat(PADDED_ID).hasSize(16);
            assertThat(result.errorFlag()).isFalse();
            assertThat(result.fieldErrors()).isEmpty();
            assertThat(result.retrievedTransaction()).isPresent();
        }

        @Test
        @DisplayName("no character-class edit is applied, because the key is alphanumeric")
        void noCharacterClassEditIsApplied() {
            final TransactionViewService.TransactionViewResult result =
                    service.viewTransaction(new TransactionViewService.TransactionViewInput(
                            "ABC-def_1234/x!", null, KeyAction.ENTER, reEnteredContext(null)));

            // Refused by no edit: it is looked up and simply names no row, exactly as the legacy would.
            assertThat(result.message()).isEqualTo(MSG_NOT_FOUND);
            assertThat(result.fieldErrors()).isEmpty();
            Mockito.verify(repository).findById("ABC-def_1234/x!");
        }

        @Test
        @DisplayName("an identifier that names no row is an outcome and adds no per-field detail")
        void absentRowAddsNoFieldDetail() {
            final TransactionViewService.TransactionViewResult result =
                    service.viewTransaction(new TransactionViewService.TransactionViewInput(
                            OTHER_ID, null, KeyAction.ENTER, reEnteredContext(null)));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).isEqualTo(MSG_NOT_FOUND);
            assertThat(result.fieldErrors()).isEmpty();
            assertThat(result.searchTransactionId()).isEqualTo(OTHER_ID);
            assertThat(result.focusField()).isEqualTo(CURSOR_FIELD);
        }

        @Test
        @DisplayName("an absent row is reported and never thrown, since this member has no abend path")
        void absentRowNeverPropagates() {
            assertThat(service.viewTransaction(new TransactionViewService.TransactionViewInput(
                    OTHER_ID, null, KeyAction.ENTER, reEnteredContext(null))))
                    .isNotNull()
                    .extracting(TransactionViewService.TransactionViewResult::message)
                    .isEqualTo(MSG_NOT_FOUND);
        }

        @Test
        @DisplayName("a failure of the lookup itself reaches the catch-all arm, lines 289 to 295")
        void lookupFailureReachesCatchAll() {
            Mockito.when(repository.findById(OTHER_ID))
                    .thenThrow(new DataAccessResourceFailureException("connection lost"));

            final TransactionViewService.TransactionViewResult result =
                    service.viewTransaction(new TransactionViewService.TransactionViewInput(
                            OTHER_ID, null, KeyAction.ENTER, reEnteredContext(null)));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).isEqualTo(MSG_LOOKUP_FAILED);
            assertThat(result.transaction()).isNull();
            assertThat(result.focusField()).isEqualTo(CURSOR_FIELD);
            assertThat(result.fieldErrors()).isEmpty();
            assertThat(result.route()).isEqualTo(NavigationService.Route.TRANSACTION_VIEW);
        }

        @Test
        @DisplayName("the first failure wins the summary message")
        void firstFailureWinsTheSummary() {
            final TransactionViewService.TransactionViewResult result =
                    service.viewTransaction(new TransactionViewService.TransactionViewInput(
                            "   ", null, KeyAction.ENTER, reEnteredContext(null)));

            // The blank arm is reached first, so its text stands and the key-width text never appears.
            assertThat(result.message()).isEqualTo(MSG_EMPTY).isNotEqualTo(MSG_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("Type fidelity - the four representations a plausible translation would break")
    class TypeFidelity {

        @Test
        @DisplayName("a zero-padded identifier reads a row while its unpadded form reads nothing")
        void zeroPaddingIsSignificant() {
            final TransactionViewService.TransactionViewResult padded =
                    service.viewTransaction(new TransactionViewService.TransactionViewInput(
                            PADDED_ID, null, KeyAction.ENTER, reEnteredContext(null)));
            final TransactionViewService.TransactionViewResult unpadded =
                    service.viewTransaction(new TransactionViewService.TransactionViewInput(
                            UNPADDED_ID, null, KeyAction.ENTER, reEnteredContext(null)));

            assertThat(padded.retrievedTransaction()).isPresent();
            assertThat(padded.transaction().transactionId()).isEqualTo(PADDED_ID);

            assertThat(unpadded.retrievedTransaction()).isEmpty();
            assertThat(unpadded.message()).isEqualTo(MSG_NOT_FOUND);

            // The keys reached the repository as characters, not as a parsed number.
            Mockito.verify(repository).findById(PADDED_ID);
            Mockito.verify(repository).findById(UNPADDED_ID);
        }

        @Test
        @DisplayName("both 26-character timestamp forms come back byte for byte")
        void bothTimestampFormsAreUnchanged() {
            final TransactionViewService.TransactionViewResult result =
                    service.viewTransaction(new TransactionViewService.TransactionViewInput(
                            PADDED_ID, null, KeyAction.ENTER, reEnteredContext(null)));

            final TransactionViewService.TransactionProjection projection = result.transaction();
            assertThat(projection.originationTimestamp()).isEqualTo(ONLINE_TIMESTAMP).hasSize(26);
            assertThat(projection.processingTimestamp()).isEqualTo(BATCH_TIMESTAMP).hasSize(26);
            assertThat(projection.originationTimestamp().getBytes(StandardCharsets.UTF_8))
                    .isEqualTo(ONLINE_TIMESTAMP.getBytes(StandardCharsets.UTF_8));
            assertThat(projection.processingTimestamp().getBytes(StandardCharsets.UTF_8))
                    .isEqualTo(BATCH_TIMESTAMP.getBytes(StandardCharsets.UTF_8));

            // The two forms remain distinguishable, which is what "not unified" means.
            assertThat(projection.originationTimestamp()).contains(" ").contains(":");
            assertThat(projection.processingTimestamp()).doesNotContain(" ").doesNotContain(":")
                    .endsWith("0000");
        }

        @Test
        @DisplayName("an all-blank processing stamp is carried as it is stored")
        void blankProcessingStampIsCarried() {
            final String blankStamp = " ".repeat(26);
            Mockito.when(repository.findById(OTHER_ID)).thenReturn(Optional.of(new Transaction(
                    OTHER_ID, "01", "0005", PADDED_SOURCE, "PENDING", BigDecimal.ZERO,
                    "123456789", "M", "C", "00000", "4111111111111111",
                    ONLINE_TIMESTAMP, blankStamp)));

            final TransactionViewService.TransactionViewResult result =
                    service.viewTransaction(new TransactionViewService.TransactionViewInput(
                            OTHER_ID, null, KeyAction.ENTER, reEnteredContext(null)));

            assertThat(result.transaction().processingTimestamp()).isEqualTo(blankStamp).hasSize(26);
        }

        @Test
        @DisplayName("the source code keeps its trailing spaces")
        void sourceKeepsItsPadding() {
            final TransactionViewService.TransactionViewResult result =
                    service.viewTransaction(new TransactionViewService.TransactionViewInput(
                            PADDED_ID, null, KeyAction.ENTER, reEnteredContext(null)));

            assertThat(result.transaction().source()).isEqualTo(PADDED_SOURCE)
                    .hasSize(10)
                    .endsWith("  ");
        }

        @Test
        @DisplayName("the amount is a BigDecimal at scale two and is never rounded up")
        void amountIsTruncatedNeverRounded() {
            final TransactionViewService.TransactionViewResult result =
                    service.viewTransaction(new TransactionViewService.TransactionViewInput(
                            PADDED_ID, null, KeyAction.ENTER, reEnteredContext(null)));

            assertThat(result.transaction().amount())
                    .isInstanceOf(BigDecimal.class)
                    .isEqualByComparingTo("1234.56");
            assertThat(result.transaction().amount().scale()).isEqualTo(2);
        }

        @ParameterizedTest(name = "a stored {0} is published as {1}")
        @CsvSource({
            "1234.56, 1234.56",
            "100.5,   100.50",
            "100.559, 100.55",
            "100.999, 100.99",
            "-100.999, -100.99",
            "-0.005,  0.00",
            "0,       0.00"
        })
        @DisplayName("the store into the two-decimal receiving field truncates toward zero")
        void storeTruncatesTowardZero(final String stored, final String published) {
            Mockito.when(repository.findById(OTHER_ID)).thenReturn(Optional.of(new Transaction(
                    OTHER_ID, "01", "0005", PADDED_SOURCE, "D", new BigDecimal(stored),
                    "123456789", "M", "C", "00000", "4111111111111111",
                    ONLINE_TIMESTAMP, BATCH_TIMESTAMP)));

            final TransactionViewService.TransactionViewResult result =
                    service.viewTransaction(new TransactionViewService.TransactionViewInput(
                            OTHER_ID, null, KeyAction.ENTER, reEnteredContext(null)));

            assertThat(result.transaction().amount()).isEqualTo(new BigDecimal(published));
            assertThat(result.transaction().amount().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("an absent amount is carried as absent rather than rejected")
        void absentAmountIsCarried() {
            Mockito.when(repository.findById(OTHER_ID)).thenReturn(Optional.of(new Transaction(
                    OTHER_ID, "01", "0005", PADDED_SOURCE, "D", null,
                    "123456789", "M", "C", "00000", "4111111111111111",
                    ONLINE_TIMESTAMP, BATCH_TIMESTAMP)));

            final TransactionViewService.TransactionViewResult result =
                    service.viewTransaction(new TransactionViewService.TransactionViewInput(
                            OTHER_ID, null, KeyAction.ENTER, reEnteredContext(null)));

            assertThat(result.transaction().amount()).isNull();
            assertThat(result.errorFlag()).isFalse();
        }

        @Test
        @DisplayName("the merchant values are the unprefixed record properties, not the daily-transaction forms")
        void merchantValuesComeFromTheUnprefixedProperties() {
            final Transaction stored = storedTransaction();
            final TransactionViewService.TransactionViewResult result =
                    service.viewTransaction(new TransactionViewService.TransactionViewInput(
                            PADDED_ID, null, KeyAction.ENTER, reEnteredContext(null)));

            final TransactionViewService.TransactionProjection projection = result.transaction();
            assertThat(projection.merchantId()).isEqualTo(stored.getMerchantId());
            assertThat(projection.merchantName()).isEqualTo(stored.getMerchantName());
            assertThat(projection.merchantCity()).isEqualTo(stored.getMerchantCity());
            assertThat(projection.merchantZip()).isEqualTo(stored.getMerchantZip());
        }

        @Test
        @DisplayName("every remaining value crosses unchanged, at the record's widths and not the screen's")
        void remainingValuesCrossUnchanged() {
            final Transaction stored = storedTransaction();
            final TransactionViewService.TransactionViewResult result =
                    service.viewTransaction(new TransactionViewService.TransactionViewInput(
                            PADDED_ID, null, KeyAction.ENTER, reEnteredContext(null)));

            final TransactionViewService.TransactionProjection projection = result.transaction();
            assertThat(projection.transactionId()).isEqualTo(stored.getTranId());
            assertThat(projection.cardNumber()).isEqualTo(stored.getTranCardNum());
            assertThat(projection.typeCode()).isEqualTo(stored.getTranTypeCd());
            assertThat(projection.categoryCode()).isEqualTo(stored.getTranCatCd());
            assertThat(projection.description()).isEqualTo(stored.getTranDesc());
        }

        @Test
        @DisplayName("a description longer than the screen field is not cut by this layer")
        void descriptionIsNotCutToTheScreenWidth() {
            final String longDescription = "D".repeat(100);
            Mockito.when(repository.findById(OTHER_ID)).thenReturn(Optional.of(new Transaction(
                    OTHER_ID, "01", "0005", PADDED_SOURCE, longDescription, BigDecimal.ONE,
                    "123456789", "N".repeat(50), "C".repeat(50), "00000", "4111111111111111",
                    ONLINE_TIMESTAMP, BATCH_TIMESTAMP)));

            final TransactionViewService.TransactionViewResult result =
                    service.viewTransaction(new TransactionViewService.TransactionViewInput(
                            OTHER_ID, null, KeyAction.ENTER, reEnteredContext(null)));

            assertThat(result.transaction().description()).hasSize(100);
            assertThat(result.transaction().merchantName()).hasSize(50);
            assertThat(result.transaction().merchantCity()).hasSize(50);
        }
    }

    @Nested
    @DisplayName("The screen header, lines 243 to 262")
    class ScreenHeaderRendering {

        @Test
        @DisplayName("renders the titles, the identity and the clock in the legacy shapes")
        void rendersTheHeader() {
            final TransactionViewService.TransactionViewResult result =
                    service.viewTransaction(new TransactionViewService.TransactionViewInput(
                            PADDED_ID, null, KeyAction.ENTER, reEnteredContext(null)));

            final TransactionViewService.ScreenHeader header = result.header();
            assertThat(header).isNotNull();
            assertThat(header.title01()).isEqualTo(new MessageCatalogService().screenTitle01());
            assertThat(header.title02()).isEqualTo(new MessageCatalogService().screenTitle02());
            assertThat(header.transactionName()).isEqualTo("CT01");
            assertThat(header.programName()).isEqualTo("COTRN01C");
            assertThat(header.currentDate()).isEqualTo(EXPECTED_HEADER_DATE);
            assertThat(header.currentTime()).isEqualTo(EXPECTED_HEADER_TIME);
        }

        @ParameterizedTest(name = "{0} renders as {1} and {2}")
        @CsvSource({
            "2022-07-19T14:23:45Z, 07/19/22, 14:23:45",
            "2000-01-01T00:00:00Z, 01/01/00, 00:00:00",
            "1999-12-31T23:59:59Z, 12/31/99, 23:59:59",
            "2100-03-04T05:06:07Z, 03/04/00, 05:06:07",
            "2009-09-09T09:09:09Z, 09/09/09, 09:09:09"
        })
        @DisplayName("the two-digit year is the low-order two digits, as the legacy reference slice is")
        void rendersTwoDigitComponents(final String instant, final String date, final String time) {
            final TransactionViewService fixed = new TransactionViewService(repository,
                    new MessageCatalogService(), new NavigationService(),
                    Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));

            final TransactionViewService.TransactionViewResult result =
                    fixed.viewTransaction(new TransactionViewService.TransactionViewInput(
                            PADDED_ID, null, KeyAction.ENTER, reEnteredContext(null)));

            assertThat(result.header().currentDate()).isEqualTo(date);
            assertThat(result.header().currentTime()).isEqualTo(time);
        }

        @Test
        @DisplayName("a turn that transferred control reports no header, because it sent no screen")
        void transferredTurnReportsNoHeader() {
            final TransactionViewService.TransactionViewResult result =
                    service.viewTransaction(new TransactionViewService.TransactionViewInput(
                            null, null, KeyAction.PFK03, reEnteredContext("COTRN00C")));

            assertThat(result.header()).isNull();
        }
    }

    @Nested
    @DisplayName("The outcome contract")
    class OutcomeContract {

        @Test
        @DisplayName("the vestigial modified flag is always clear, as line 89 leaves it")
        void modifiedFlagIsAlwaysClear() {
            for (final KeyAction keyAction : KeyAction.values()) {
                assertThat(service.viewTransaction(new TransactionViewService.TransactionViewInput(
                        PADDED_ID, null, keyAction, reEnteredContext("COTRN00C")))
                        .userModified()).isFalse();
            }
        }

        @Test
        @DisplayName("the per-field detail is unmodifiable and never absent")
        void fieldDetailIsUnmodifiable() {
            final TransactionViewService.TransactionViewResult result =
                    service.viewTransaction(new TransactionViewService.TransactionViewInput(
                            "", null, KeyAction.ENTER, reEnteredContext(null)));

            assertThat(result.fieldErrors()).hasSize(1);
            assertThatThrownBy(() -> result.fieldErrors().add(new ValidationException.FieldError(
                    "x", "Y", ValidationException.FieldState.INVALID, "z")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("an absent per-field list is normalised to an empty one")
        void absentFieldListIsNormalised() {
            final TransactionViewService.TransactionViewResult built =
                    new TransactionViewService.TransactionViewResult(
                            NavigationService.Route.TRANSACTION_VIEW, NavigationContext.empty(),
                            "CT01", "", null, "", "", false, false, false, null, null);

            assertThat(built.fieldErrors()).isNotNull().isEmpty();
            assertThat(built.retrievedTransaction()).isEmpty();
        }

        @Test
        @DisplayName("the rendering of an outcome withholds every regulated value")
        void outcomeRenderingWithholdsRegulatedValues() {
            final TransactionViewService.TransactionViewResult result =
                    service.viewTransaction(new TransactionViewService.TransactionViewInput(
                            PADDED_ID, null, KeyAction.ENTER, reEnteredContext(null)));

            final String rendered = result.toString();
            assertThat(rendered).contains("***REDACTED***")
                    .doesNotContain(PADDED_ID)
                    .doesNotContain("4111111111111111")
                    .doesNotContain("1234.56")
                    .doesNotContain(ONLINE_TIMESTAMP)
                    .doesNotContain(BATCH_TIMESTAMP)
                    .doesNotContain("AMAZON RETAIL LLC")
                    .doesNotContain("SEATTLE");

            // What survives is what a reader needs to diagnose the turn: the destination, the cursor
            // position, the flags and the header. The destination renders as its enum constant, which
            // names the destination unambiguously without publishing a route token here.
            assertThat(rendered).contains("route=TRANSACTION_VIEW")
                    .contains("focusField=" + CURSOR_FIELD)
                    .contains("errorFlag=false")
                    .contains("reEntry=true");
        }

        @Test
        @DisplayName("the rendering of a projection keeps only the reference codes")
        void projectionRenderingKeepsOnlyReferenceCodes() {
            final TransactionViewService.TransactionViewResult result =
                    service.viewTransaction(new TransactionViewService.TransactionViewInput(
                            PADDED_ID, null, KeyAction.ENTER, reEnteredContext(null)));

            final String rendered = result.transaction().toString();
            assertThat(rendered).contains("typeCode=01")
                    .contains("categoryCode=0005")
                    .contains("source=" + PADDED_SOURCE)
                    .doesNotContain(PADDED_ID)
                    .doesNotContain("98109");
        }

        @Test
        @DisplayName("the outcome is a value, and two turns of the same shape agree")
        void outcomeIsAValue() {
            final TransactionViewService.TransactionViewInput turn =
                    new TransactionViewService.TransactionViewInput(
                            PADDED_ID, null, KeyAction.ENTER, reEnteredContext(null));

            assertThat(service.viewTransaction(turn)).isEqualTo(service.viewTransaction(turn));
            assertThat(service.viewTransaction(turn)).hasSameHashCodeAs(service.viewTransaction(turn));
        }

        @Test
        @DisplayName("nothing is written on any path")
        void nothingIsWritten() {
            for (final KeyAction keyAction : KeyAction.values()) {
                service.viewTransaction(new TransactionViewService.TransactionViewInput(
                        PADDED_ID, null, keyAction, reEnteredContext("COTRN00C")));
            }
            service.viewTransaction(new TransactionViewService.TransactionViewInput(
                    null, PADDED_ID, null, firstEntryContext()));

            Mockito.verify(repository, Mockito.never()).save(ArgumentMatchers.any());
            Mockito.verify(repository, Mockito.never()).saveAll(ArgumentMatchers.any());
            Mockito.verify(repository, Mockito.never()).delete(ArgumentMatchers.any());
            Mockito.verify(repository, Mockito.never()).deleteById(ArgumentMatchers.anyString());
            Mockito.verify(repository, Mockito.never()).flush();
            Mockito.verify(repository, Mockito.never()).findMaxId();
            Mockito.verify(repository, Mockito.never()).findByProcessingDateRange(
                    ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                    ArgumentMatchers.any());
        }

        @Test
        @DisplayName("the input record carries its four components verbatim")
        void inputCarriesItsComponents() {
            final NavigationContext context = reEnteredContext("COTRN00C");
            final TransactionViewService.TransactionViewInput turn =
                    new TransactionViewService.TransactionViewInput(
                            PADDED_ID, OTHER_ID, KeyAction.PFK03, context);

            assertThat(turn.transactionIdInput()).isEqualTo(PADDED_ID);
            assertThat(turn.selectedTransactionId()).isEqualTo(OTHER_ID);
            assertThat(turn.keyAction()).isEqualTo(KeyAction.PFK03);
            assertThat(turn.navigationContext()).isSameAs(context);
        }
    }

    @Nested
    @DisplayName("The legacy two-state flags")
    class LegacyFlags {

        @Test
        @DisplayName("the error flag reports both of its states")
        void errorFlagReportsBothStates() {
            assertThat(TransactionViewService.ErrorFlag.OFF.isOff()).isTrue();
            assertThat(TransactionViewService.ErrorFlag.OFF.isOn()).isFalse();
            assertThat(TransactionViewService.ErrorFlag.ON.isOn()).isTrue();
            assertThat(TransactionViewService.ErrorFlag.ON.isOff()).isFalse();
            assertThat(TransactionViewService.ErrorFlag.values()).hasSize(2);
            assertThat(TransactionViewService.ErrorFlag.valueOf("ON"))
                    .isEqualTo(TransactionViewService.ErrorFlag.ON);
        }

        @Test
        @DisplayName("the modified flag declares the legacy two-state domain")
        void modifiedFlagDeclaresBothStates() {
            assertThat(TransactionViewService.UserModifiedFlag.NO.isYes()).isFalse();
            assertThat(TransactionViewService.UserModifiedFlag.YES.isYes()).isTrue();
            assertThat(TransactionViewService.UserModifiedFlag.values()).hasSize(2);
            assertThat(TransactionViewService.UserModifiedFlag.valueOf("NO"))
                    .isEqualTo(TransactionViewService.UserModifiedFlag.NO);
        }

        @Test
        @DisplayName("the header record carries its six components verbatim")
        void headerCarriesItsComponents() {
            final TransactionViewService.ScreenHeader header =
                    new TransactionViewService.ScreenHeader("a", "b", "CT01", "COTRN01C", "07/19/22",
                            "14:23:45");

            assertThat(List.of(header.title01(), header.title02(), header.transactionName(),
                    header.programName(), header.currentDate(), header.currentTime()))
                    .containsExactly("a", "b", "CT01", "COTRN01C", "07/19/22", "14:23:45");
        }
    }
}
