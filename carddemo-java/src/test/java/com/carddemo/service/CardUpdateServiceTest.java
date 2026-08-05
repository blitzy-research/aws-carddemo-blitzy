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
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.RecoverableDataAccessException;

import com.carddemo.domain.Card;
import com.carddemo.exception.AbendException;
import com.carddemo.exception.OptimisticLockConflictException;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.RecordWriter;
import com.carddemo.repository.CardRepository;
import com.carddemo.support.RecordWriterDoubles;
import com.carddemo.util.CobolStringUtils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Unit test for {@link CardUpdateService}, the translation of the card-update transaction {@code CCUP}
 * carried by {@code app/cbl/COCRDUPC.cbl} at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} (2022-07-19).
 *
 * <p><strong>What this suite is for.</strong> Three behaviours of that member are counter-intuitive
 * enough that a plausible, compiling, wrong translation passes any test written under the same
 * misunderstanding. This suite is written to fail such a translation. It asserts the <em>consequences</em>
 * of the two in-place upper folds rather than the folds themselves, it asserts that the fold is a
 * character-table operation by choosing a value on which the table and the locale-aware library method
 * disagree, and it asserts that the backward jump at line 1518 is bounded by counting reads rather than by
 * measuring elapsed time.
 *
 * <p><strong>What is deliberately not asserted, and why.</strong> The abend routine at line 1531 is
 * reachable in the legacy only from the catch-all arm of the action decision at lines 1019 to 1026, which
 * exists to catch a corrupt state byte in a {@code PIC X(1)} field. The Java state is an enum, so no value
 * outside the seven declared ones can exist and the arm is unreachable by construction - a strictly safer
 * position than the legacy's, and one that cannot be exercised through the public surface without
 * reflection. The abend contract of this screen is instead asserted where it <em>is</em> reachable: the
 * exit arm resolves its destination through the module's dispatch graph, which raises when the nominated
 * program cannot be resolved, and that raise is asserted to propagate.
 */
@DisplayName("CardUpdateService - card-update transaction CCUP, app/cbl/COCRDUPC.cbl")
class CardUpdateServiceTest {

    /** The sixteen-character key every scenario reads and writes. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** The eleven-character account identifier every scenario carries. */
    private static final String ACCOUNT_ID = "00000000001";

    /**
     * The verification code the suite round-trips.
     *
     * <p>Chosen for its leading zeros: a translation that parsed it into a number would round-trip it as
     * {@code "7"}, and one that logged it would leak it.
     */
    private static final String VERIFICATION_CODE = "007";

    /** The stored expiration date, in the layout the write path assembles at lines 1467 to 1474. */
    private static final String EXPIRATION_DATE = "2028-07-31";

    /** The stored embossed name, already upper case, as row 0 of the ASCII card fixture resembles. */
    private static final String STORED_NAME_FOLDED = "ANIYA VON";

    /** The same name as an operator would type it: different in letter case only. */
    private static final String STORED_NAME_LOWER = "aniya von";

    private CardRepository cardRepository;

    private AbendService abendService;

    private CardUpdateService service;

    private Logger serviceLogger;

    private ListAppender<ILoggingEvent> capturedLog;

    @BeforeEach
    void setUp() {
        this.cardRepository = Mockito.mock(CardRepository.class);
        this.abendService = Mockito.mock(AbendService.class);
        this.service = new CardUpdateService(this.cardRepository, this.abendService,
                new MessageCatalogService(), new NavigationService(), new OnlineTransactionBoundary(),
                RecordWriterDoubles.passthrough(),
                Clock.fixed(Instant.parse("2024-03-14T15:09:26Z"), ZoneOffset.UTC));

        this.capturedLog = new ListAppender<>();
        this.capturedLog.start();
        this.serviceLogger = (Logger) LoggerFactory.getLogger(CardUpdateService.class);
        this.serviceLogger.addAppender(this.capturedLog);
        this.serviceLogger.setLevel(Level.TRACE);
    }

    @AfterEach
    void tearDown() {
        this.serviceLogger.detachAppender(this.capturedLog);
        this.capturedLog.stop();
    }

    // ==============================================================================================
    // Fixtures
    // ==============================================================================================

    /**
     * A stored card row.
     *
     * @param embossedName the embossed name the row holds
     * @return a fresh row, never {@code null}
     */
    private static Card storedCard(final String embossedName) {
        return new Card(CARD_NUMBER, ACCOUNT_ID, VERIFICATION_CODE, embossedName, EXPIRATION_DATE, "Y");
    }

    /**
     * The fetched image the previous turn returned, matching the stored row.
     *
     * @param embossedName the embossed name the image carries, already folded at capture
     * @return the carried image, never {@code null}
     */
    private static CardUpdateService.CarriedCardImage carriedImage(final String embossedName) {
        return new CardUpdateService.CarriedCardImage(ACCOUNT_ID, CARD_NUMBER, VERIFICATION_CODE,
                embossedName, "2028", "07", "31", "Y");
    }

    /**
     * Navigation state for a turn that is a re-entry from this screen itself.
     *
     * @return the echoed state, never {@code null}
     */
    private static ScreenNavigationState reEntryContext() {
        return new ScreenNavigationState("CCUP", "COCRDUPC", null, null, "USER0001", "U",
                ScreenNavigationState.ProgramContext.REENTER, "000000001", "ANIYA", null, "VON",
                ACCOUNT_ID, "Y", CARD_NUMBER, "CCRDUPA", "COCRDUP");
    }

    /**
     * A submitted turn on the confirmation path: the changes were validated on the previous turn and the
     * operator has pressed the save key.
     *
     * @param attentionKeyIdentifier the raw attention identifier the terminal reported
     * @param embossedName the submitted embossed name
     * @param activeStatus the submitted active status
     * @param carried the fetched image the previous turn returned
     * @return the transmitted screen, never {@code null}
     */
    private static CardUpdateService.CardUpdateScreenInput confirmingTurn(
            final String attentionKeyIdentifier, final String embossedName, final String activeStatus,
            final CardUpdateService.CarriedCardImage carried) {
        return new CardUpdateService.CardUpdateScreenInput(ACCOUNT_ID, CARD_NUMBER, embossedName,
                activeStatus, "07", "2028", "31", attentionKeyIdentifier, reEntryContext(),
                CardUpdateService.ChangeAction.CHANGES_OK_NOT_CONFIRMED, carried);
    }

    /**
     * A submitted turn on the review path: a card is on display and the operator has pressed enter.
     *
     * @param embossedName the submitted embossed name
     * @param activeStatus the submitted active status
     * @param carried the fetched image the previous turn returned
     * @return the transmitted screen, never {@code null}
     */
    private static CardUpdateService.CardUpdateScreenInput reviewingTurn(final String embossedName,
            final String activeStatus, final CardUpdateService.CarriedCardImage carried) {
        return new CardUpdateService.CardUpdateScreenInput(ACCOUNT_ID, CARD_NUMBER, embossedName,
                activeStatus, "07", "2028", "31", "DFHENTER", reEntryContext(),
                CardUpdateService.ChangeAction.SHOW_DETAILS, carried);
    }

    /**
     * Every formatted message and every argument the service logged during a scenario.
     *
     * @return the rendered log, never {@code null}
     */
    private String renderedLog() {
        final StringBuilder rendered = new StringBuilder();
        for (final ILoggingEvent event : this.capturedLog.list) {
            rendered.append(event.getFormattedMessage()).append('\n');
        }
        return rendered.toString();
    }

    // ==============================================================================================
    // Assertion (a) and (b): the consequence of the two in-place folds
    // ==============================================================================================

    @Nested
    @DisplayName("the two in-place upper folds, lines 1357 with 1360 and 1499 with 1503-1508")
    class Folding {

        @Test
        @DisplayName("(a) an edit differing ONLY in letter case is NOT detected as a change")
        void caseOnlyEditIsNotAChange() {
            Mockito.when(CardUpdateServiceTest.this.cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(storedCard(STORED_NAME_FOLDED)));

            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(reviewingTurn(STORED_NAME_LOWER, "Y",
                            carriedImage(STORED_NAME_FOLDED)));

            // The comparison at lines 680 to 681 folds both operands, so the group compares equal and the
            // no-change message of lines 187 to 188 is raised verbatim.
            assertThat(result.message())
                    .isEqualTo("No change detected with respect to values fetched.");
            // Clause three of the action decision at lines 971 to 977 therefore does nothing, so the
            // screen stays in the display state and no write is attempted.
            assertThat(result.changeAction())
                    .isEqualTo(CardUpdateService.ChangeAction.SHOW_DETAILS);
            assertThat(result.writeOutcome())
                    .isEqualTo(CardUpdateService.WriteOutcome.NOT_ATTEMPTED);
            Mockito.verify(CardUpdateServiceTest.this.cardRepository, Mockito.never())
                    .saveAndFlush(Mockito.any());
        }

        @Test
        @DisplayName("a genuine change IS detected and is carried through to the write")
        void genuineChangeIsDetected() {
            final Card stored = storedCard(STORED_NAME_FOLDED);
            Mockito.when(CardUpdateServiceTest.this.cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(stored));
            Mockito.when(CardUpdateServiceTest.this.cardRepository.saveAndFlush(Mockito.any()))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(confirmingTurn("DFHPF5", "MARY ANN", "N",
                            carriedImage(STORED_NAME_FOLDED)));

            assertThat(result.writeOutcome()).isEqualTo(CardUpdateService.WriteOutcome.COMMITTED);
            assertThat(result.updateCommitted()).isTrue();
            assertThat(result.changeAction())
                    .isEqualTo(CardUpdateService.ChangeAction.CHANGES_OKAYED_AND_DONE);
            assertThat(result.infoMessage()).isEqualTo("Changes committed to database");
        }

        @Test
        @DisplayName("(b) the fetched value is presented and carried FOLDED, and the write stores the "
                + "submitted text verbatim per line 1466")
        void fetchedValueIsFoldedAndWriteStoresSubmittedText() {
            final Card stored = storedCard(STORED_NAME_LOWER);
            Mockito.when(CardUpdateServiceTest.this.cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(stored));

            // Arrival from the card-list screen runs the fetch path at lines 482 to 496.
            final ScreenNavigationState fromCardList = new ScreenNavigationState("CCLI", "COCRDLIC", null, null,
                    "USER0001", "U", ScreenNavigationState.ProgramContext.ENTER, "000000001", "ANIYA", null,
                    "VON", ACCOUNT_ID, "Y", CARD_NUMBER, "CCRDSLA", "COCRDLI");
            final CardUpdateService.CardUpdateResult fetched = CardUpdateServiceTest.this.service
                    .processCardUpdate(new CardUpdateService.CardUpdateScreenInput(null, null, null,
                            null, null, null, null, "DFHENTER", fromCardList, null, null));

            // The capture at line 1360 reads the field the fold at line 1357 has already rewritten.
            assertThat(fetched.carriedImage().embossedName()).isEqualTo("ANIYA VON");
            assertThat(fetched.card()).isNotNull();
            assertThat(fetched.card().embossedName()).isEqualTo("ANIYA VON");
            assertThat(fetched.changeAction())
                    .isEqualTo(CardUpdateService.ChangeAction.SHOW_DETAILS);

            // The stored row is NOT mutated by the fold: the source folds a working-storage copy filled
            // by READ ... INTO, and the rewrite writes a separately built image.
            assertThat(stored.getCardEmbossedName()).isEqualTo(STORED_NAME_LOWER);

            // And the write path stores the submitted text verbatim, per line 1466.
            final Card storedForWrite = storedCard(STORED_NAME_FOLDED);
            Mockito.when(CardUpdateServiceTest.this.cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(storedForWrite));
            Mockito.when(CardUpdateServiceTest.this.cardRepository.saveAndFlush(Mockito.any()))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            CardUpdateServiceTest.this.service.processCardUpdate(
                    confirmingTurn("DFHPF5", "mary ann", "N", carriedImage(STORED_NAME_FOLDED)));

            final ArgumentCaptor<Card> written = ArgumentCaptor.forClass(Card.class);
            Mockito.verify(CardUpdateServiceTest.this.cardRepository).saveAndFlush(written.capture());
            assertThat(written.getValue().getCardEmbossedName()).isEqualTo("mary ann");
        }

        @Test
        @DisplayName("(c) the fold is a 26-character ASCII table: a value the locale-aware library "
                + "method would transform is left UNCHANGED")
        void foldIsAnAsciiTableAndNotALocaleOperation() {
            // Both of these are transformed by Java's Unicode-aware upper-casing and are absent from the
            // 26-character legacy table. The sharp s also CHANGES LENGTH under that method, which would
            // corrupt a value written back into a fixed fifty-byte field.
            final String nonAsciiName = "Ren\u00e9e Wei\u00df";
            assertThat(CobolStringUtils.asciiUpperFold(nonAsciiName)).isEqualTo("REN\u00e9E WEI\u00df");

            final Card stored = storedCard(nonAsciiName);
            Mockito.when(CardUpdateServiceTest.this.cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(stored));

            final ScreenNavigationState fromCardList = new ScreenNavigationState("CCLI", "COCRDLIC", null, null,
                    "USER0001", "U", ScreenNavigationState.ProgramContext.ENTER, "000000001", "RENEE", null,
                    "WEISS", ACCOUNT_ID, "Y", CARD_NUMBER, "CCRDSLA", "COCRDLI");
            final CardUpdateService.CardUpdateResult fetched = CardUpdateServiceTest.this.service
                    .processCardUpdate(new CardUpdateService.CardUpdateScreenInput(null, null, null,
                            null, null, null, null, "DFHENTER", fromCardList, null, null));

            // Every character absent from the FROM table survives, and the length is unchanged - which is
            // the assertion that catches a substitution of the locale-aware method.
            assertThat(fetched.carriedImage().embossedName()).isEqualTo("REN\u00e9E WEI\u00df");
            assertThat(fetched.carriedImage().embossedName()).hasSameSizeAs(nonAsciiName);
        }
    }

    // ==============================================================================================
    // Assertion (d): the blank-and-trim alphabetic check at line 824
    // ==============================================================================================

    @Nested
    @DisplayName("the alphabetic check at line 824 - embedded spaces PASS")
    class AlphabeticCheck {

        @ParameterizedTest
        @CsvSource({"MARY ANN", "Aniya Von", "ANIYA VON", "Mary"})
        @DisplayName("(d) a name of letters and spaces is accepted")
        void namesOfLettersAndSpacesAreAccepted(final String submittedName) {
            Mockito.when(CardUpdateServiceTest.this.cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(storedCard(STORED_NAME_FOLDED)));

            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(reviewingTurn(submittedName, "N",
                            carriedImage(STORED_NAME_FOLDED)));

            assertThat(result.errorFlag()).isFalse();
            assertThat(result.fieldErrors()).isEmpty();
            assertThat(result.message()).isEmpty();
            assertThat(result.changeAction())
                    .isEqualTo(CardUpdateService.ChangeAction.CHANGES_OK_NOT_CONFIRMED);
            assertThat(result.awaitingConfirmation()).isTrue();
            assertThat(result.infoMessage()).isEqualTo("Changes validated.Press F5 to save");
        }

        @Test
        @DisplayName("a name carrying a digit is rejected with the source's verbatim text")
        void nameCarryingADigitIsRejected() {
            Mockito.when(CardUpdateServiceTest.this.cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(storedCard(STORED_NAME_FOLDED)));

            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(reviewingTurn("MARY 2ND", "N", carriedImage(STORED_NAME_FOLDED)));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message())
                    .isEqualTo("Card name can only contain alphabets and spaces");
            assertThat(result.changeAction())
                    .isEqualTo(CardUpdateService.ChangeAction.CHANGES_NOT_OK);
            assertThat(result.focusField()).isEqualTo("embossedName");
            assertThat(result.fieldErrors()).singleElement()
                    .satisfies(error -> {
                        assertThat(error.field()).isEqualTo("embossedName");
                        assertThat(error.bmsFieldId()).isEqualTo("CRDNAME");
                        assertThat(error.state()).isEqualTo(ValidationException.FieldState.INVALID);
                    });
            assertThat(result.decoration().markedFields()).singleElement()
                    .satisfies(marked -> assertThat(marked.flagState())
                            .isEqualTo(FieldErrorMarks.FlagState.NOT_OK));
        }

        @Test
        @DisplayName("a cleared name is MISSING, is marked, and blanks the outbound field")
        void clearedNameIsMissing() {
            Mockito.when(CardUpdateServiceTest.this.cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(storedCard(STORED_NAME_FOLDED)));

            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(reviewingTurn("*", "N", carriedImage(STORED_NAME_FOLDED)));

            assertThat(result.message()).isEqualTo("Card name not provided");
            assertThat(result.fieldErrors()).singleElement()
                    .satisfies(error -> assertThat(error.state())
                            .isEqualTo(ValidationException.FieldState.MISSING));
            assertThat(result.decoration().markedFields()).singleElement()
                    .satisfies(marked -> assertThat(marked.flagState())
                            .isEqualTo(FieldErrorMarks.FlagState.BLANK));
            assertThat(result.screen().embossedName()).isEqualTo("*");
        }

        @Test
        @DisplayName("the summary carries the FIRST error while every failing field is still marked")
        void firstErrorWinsTheSummaryAndEveryFieldIsMarked() {
            Mockito.when(CardUpdateServiceTest.this.cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(storedCard(STORED_NAME_FOLDED)));

            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(new CardUpdateService.CardUpdateScreenInput(ACCOUNT_ID,
                            CARD_NUMBER, "MARY 2ND", "Q", "13", "1900", "31", "DFHENTER",
                            reEntryContext(), CardUpdateService.ChangeAction.SHOW_DETAILS,
                            carriedImage(STORED_NAME_FOLDED)));

            // The name edit runs first, so its text wins the summary.
            assertThat(result.message())
                    .isEqualTo("Card name can only contain alphabets and spaces");
            // All four card-data flags are still set independently, and are marked in source order.
            assertThat(result.fieldErrors()).extracting(ValidationException.FieldError::field)
                    .containsExactly("embossedName", "activeStatus", "expiryMonth", "expiryYear");
            assertThat(result.fieldErrors()).allSatisfy(error -> assertThat(error.state())
                    .isEqualTo(ValidationException.FieldState.INVALID));
        }
    }

    // ==============================================================================================
    // Assertions (e) and (f): the write path
    // ==============================================================================================

    @Nested
    @DisplayName("the write path, line 1420, and the backward jump at line 1518")
    class WritePath {

        @Test
        @DisplayName("(e) a row-version conflict raises the module's conflict exception and NEVER abends")
        void versionConflictRaisesConflictAndNeverAbends() {
            Mockito.when(CardUpdateServiceTest.this.cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(storedCard(STORED_NAME_FOLDED)));
            Mockito.when(CardUpdateServiceTest.this.cardRepository.saveAndFlush(Mockito.any()))
                    .thenThrow(new OptimisticLockingFailureException("row version disagreed"));

            assertThatExceptionOfType(OptimisticLockConflictException.class)
                    .isThrownBy(() -> CardUpdateServiceTest.this.service.processCardUpdate(
                            confirmingTurn("DFHPF5", "MARY ANN", "N",
                                    carriedImage(STORED_NAME_FOLDED))))
                    .satisfies(raised -> {
                        assertThat(raised.conflictKind()).isEqualTo(
                                OptimisticLockConflictException.ConflictKind.UPDATE_FAILED_AFTER_LOCK);
                        assertThat(raised.entityName()).isEqualTo("Card");
                        assertThat(raised.getMessage()).isEqualTo("Update of record failed");
                    });

            Mockito.verifyNoInteractions(CardUpdateServiceTest.this.abendService);
        }

        @Test
        @DisplayName("(f) the backward jump is bounded by the source's own condition, with no delay: "
                + "the record is read exactly once and nothing is written")
        void backwardJumpIsBoundedAndWritesNothing() {
            // The stored row no longer matches the image the screen was built from: a different status.
            final Card diverged = new Card(CARD_NUMBER, ACCOUNT_ID, VERIFICATION_CODE,
                    STORED_NAME_FOLDED, EXPIRATION_DATE, "N");
            Mockito.when(CardUpdateServiceTest.this.cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(diverged));

            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(confirmingTurn("DFHPF5", STORED_NAME_FOLDED, "Y",
                            carriedImage(STORED_NAME_FOLDED)));

            assertThat(result.writeOutcome())
                    .isEqualTo(CardUpdateService.WriteOutcome.RECORD_CHANGED_BEFORE_UPDATE);
            assertThat(result.changeDetected()).isTrue();
            // The verbatim text, with "some one" as TWO WORDS.
            assertThat(result.message()).isEqualTo("Record changed by some one else. Please review");
            // Lines 997 to 998 return the screen to the display state, so the conflict is recoverable.
            assertThat(result.changeAction())
                    .isEqualTo(CardUpdateService.ChangeAction.SHOW_DETAILS);
            // Lines 1512 to 1517 refresh the carried image with what the record now holds.
            assertThat(result.carriedImage().activeStatus()).isEqualTo("N");

            // The loop is bounded: exactly one read, and nothing written. Counting reads rather than
            // measuring time is the assertion that catches an unbounded or delayed retry.
            Mockito.verify(CardUpdateServiceTest.this.cardRepository, Mockito.times(1))
                    .findById(CARD_NUMBER);
            Mockito.verify(CardUpdateServiceTest.this.cardRepository, Mockito.never())
                    .saveAndFlush(Mockito.any());
            Mockito.verifyNoInteractions(CardUpdateServiceTest.this.abendService);
        }

        @Test
        @DisplayName("a row that cannot be read for update raises this program's own lock text, one word "
                + "shorter than the account program's")
        void lockNotAcquiredRaisesThisProgramsText() {
            Mockito.when(CardUpdateServiceTest.this.cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.empty());

            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(confirmingTurn("DFHPF5", "MARY ANN", "N",
                            carriedImage(STORED_NAME_FOLDED)));

            assertThat(result.message()).isEqualTo("Could not lock record for update");
            assertThat(result.message())
                    .isNotEqualTo(OptimisticLockConflictException.MSG_COULD_NOT_LOCK_ACCT_FOR_UPDATE);
            assertThat(result.writeOutcome())
                    .isEqualTo(CardUpdateService.WriteOutcome.LOCK_NOT_ACQUIRED);
            assertThat(result.changeAction())
                    .isEqualTo(CardUpdateService.ChangeAction.CHANGES_OKAYED_LOCK_ERROR);
            assertThat(result.infoMessage()).isEqualTo("Changes unsuccessful. Please try again");
            Mockito.verifyNoInteractions(CardUpdateServiceTest.this.abendService);
        }

        @Test
        @DisplayName("a write that fails for any other reason is reported on the screen and does not "
                + "abend, per lines 1488 to 1492")
        void otherWriteFailureIsReportedOnTheScreen() {
            Mockito.when(CardUpdateServiceTest.this.cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(storedCard(STORED_NAME_FOLDED)));
            Mockito.when(CardUpdateServiceTest.this.cardRepository.saveAndFlush(Mockito.any()))
                    .thenThrow(new RecoverableDataAccessException("card file unavailable"));

            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(confirmingTurn("DFHPF5", "MARY ANN", "N",
                            carriedImage(STORED_NAME_FOLDED)));

            assertThat(result.message()).isEqualTo("Update of record failed");
            assertThat(result.writeOutcome())
                    .isEqualTo(CardUpdateService.WriteOutcome.UPDATE_FAILED_AFTER_LOCK);
            assertThat(result.changeAction())
                    .isEqualTo(CardUpdateService.ChangeAction.CHANGES_OKAYED_BUT_FAILED);
            Mockito.verifyNoInteractions(CardUpdateServiceTest.this.abendService);
        }
    }

    // ==============================================================================================
    // Assertion (g): the verification code
    // ==============================================================================================

    @Nested
    @DisplayName("the verification code - a bounded string, never a number, never logged")
    class VerificationCode {

        @Test
        @DisplayName("(g) a leading-zero code round-trips as a three-character string and never reaches "
                + "a log")
        void leadingZeroCodeRoundTripsAndIsNeverLogged() {
            final Card stored = storedCard(STORED_NAME_FOLDED);
            Mockito.when(CardUpdateServiceTest.this.cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(stored));
            Mockito.when(CardUpdateServiceTest.this.cardRepository.saveAndFlush(Mockito.any()))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(confirmingTurn("DFHPF5", "MARY ANN", "N",
                            carriedImage(VERIFICATION_CODE.equals("007") ? STORED_NAME_FOLDED
                                    : STORED_NAME_FOLDED)));

            // The write path sources the code from a field the member never writes, so the stored value
            // is preserved rather than blanked. It is still three characters and is not normalised.
            final ArgumentCaptor<Card> written = ArgumentCaptor.forClass(Card.class);
            Mockito.verify(CardUpdateServiceTest.this.cardRepository).saveAndFlush(written.capture());
            assertThat(written.getValue().getCardCvvCd()).isEqualTo("007");
            assertThat(written.getValue().getCardCvvCd()).hasSize(3);

            // The carried image still carries it, because the comparison at line 1503 needs it.
            assertThat(result.carriedImage().verificationCode()).isEqualTo("007");
            // And nothing about it reaches a log, on this path or any other.
            assertThat(CardUpdateServiceTest.this.renderedLog()).doesNotContain("007");
            assertThat(result.carriedImage().toString()).doesNotContain("007");
            assertThat(result.card()).isNotNull();
            assertThat(result.card().toString()).doesNotContain(CARD_NUMBER);
        }

        @Test
        @DisplayName("the projection does not expose the verification code at all")
        void projectionDoesNotExposeTheVerificationCode() {
            final CardUpdateService.CardProjection projection = CardUpdateService.CardProjection
                    .of(storedCard(STORED_NAME_FOLDED), "ANIYA VON");

            assertThat(projection.embossedName()).isEqualTo("ANIYA VON");
            assertThat(projection.expirationDate()).isEqualTo(EXPIRATION_DATE);
            assertThat(projection.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(projection.activeStatus()).isEqualTo("Y");
            assertThat(projection.version()).isZero();
            assertThat(projection.cardNumber()).isEqualTo(CARD_NUMBER);
            assertThat(projection.toString()).doesNotContain("007").doesNotContain(CARD_NUMBER);
            assertThatNullPointerException()
                    .isThrownBy(() -> CardUpdateService.CardProjection.of(null, null));
        }
    }

    // ==============================================================================================
    // Assertion (h): the attention keys
    // ==============================================================================================

    @Nested
    @DisplayName("attention keys - PF13 to PF24 fold onto PF1 to PF12")
    class AttentionKeys {

        @Test
        @DisplayName("(h) the seventeenth key behaves identically to the fifth")
        void seventeenthKeyBehavesAsTheFifth() {
            Mockito.when(CardUpdateServiceTest.this.cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(storedCard(STORED_NAME_FOLDED)));
            Mockito.when(CardUpdateServiceTest.this.cardRepository.saveAndFlush(Mockito.any()))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            final CardUpdateService.CardUpdateResult viaFifth = CardUpdateServiceTest.this.service
                    .processCardUpdate(confirmingTurn("DFHPF5", "MARY ANN", "N",
                            carriedImage(STORED_NAME_FOLDED)));

            Mockito.reset(CardUpdateServiceTest.this.cardRepository);
            Mockito.when(CardUpdateServiceTest.this.cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(storedCard(STORED_NAME_FOLDED)));
            Mockito.when(CardUpdateServiceTest.this.cardRepository.saveAndFlush(Mockito.any()))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            final CardUpdateService.CardUpdateResult viaSeventeenth = CardUpdateServiceTest.this.service
                    .processCardUpdate(confirmingTurn("DFHPF17", "MARY ANN", "N",
                            carriedImage(STORED_NAME_FOLDED)));

            assertThat(viaSeventeenth.writeOutcome()).isEqualTo(viaFifth.writeOutcome());
            assertThat(viaSeventeenth.changeAction()).isEqualTo(viaFifth.changeAction());
            assertThat(viaSeventeenth.message()).isEqualTo(viaFifth.message());
            assertThat(viaSeventeenth.workArea().keyAction())
                    .isEqualTo(viaFifth.workArea().keyAction());
            assertThat(viaSeventeenth.updateCommitted()).isTrue();
        }

        @Test
        @DisplayName("an unrecognised identifier raises the fifty-character common invalid-key message, "
                + "untrimmed")
        void unrecognisedIdentifierRaisesTheFixedWidthMessage() {
            Mockito.when(CardUpdateServiceTest.this.cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(storedCard(STORED_NAME_FOLDED)));

            // The submitted status differs from the fetched one, so the unconditional no-change
            // assignment of lines 680 to 683 does not fire and the message the undecodable identifier
            // raised is the one the turn returns.
            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(reviewingTurnWithKey("DFHPF99", STORED_NAME_FOLDED, "N"));

            assertThat(result.attentionKeyUnmapped()).isTrue();
            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).hasSize(MessageCatalogService.COMMON_MESSAGE_WIDTH);
            assertThat(result.message()).isEqualTo(MessageCatalogService.CCDA_MSG_INVALID_KEY);
            assertThat(result.message()).startsWith("Invalid key pressed.");
            // Lines 422 to 424 coerce every key that is not permitted at this point to the enter key,
            // and an identifier that did not decode leaves no key at all, so it is not permitted. The
            // decode failure is therefore reported on its own flag, never by the decoded key.
            assertThat(result.workArea().keyAction()).isEqualTo(
                    com.carddemo.domain.enums.KeyAction.ENTER);
            assertThat(result.writeOutcome())
                    .isEqualTo(CardUpdateService.WriteOutcome.NOT_ATTEMPTED);
        }

        @Test
        @DisplayName("the invalid-key message yields to the unconditional no-change assignment of lines "
                + "680 to 683, and the separate report survives it")
        void unmappedIdentifierReportSurvivesAnOverwrittenMessage() {
            Mockito.when(CardUpdateServiceTest.this.cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(storedCard(STORED_NAME_FOLDED)));

            // Same name in a different letter case and the same status, so the folded group compares
            // equal and the no-change assignment fires. The legacy makes that assignment unconditional,
            // so it overwrites the invalid-key message - which is exactly why the report of the decode
            // failure cannot be carried by the message alone.
            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(reviewingTurnWithKey("DFHPF99", STORED_NAME_LOWER, "Y"));

            assertThat(result.message())
                    .isEqualTo("No change detected with respect to values fetched.");
            assertThat(result.attentionKeyUnmapped()).isTrue();
            assertThat(result.errorFlag()).isTrue();
        }

        @Test
        @DisplayName("a decoded key that is not permitted in the current state is silently treated as "
                + "the enter key, per lines 422 to 424")
        void unpermittedKeyIsSilentlyTreatedAsEnter() {
            Mockito.when(CardUpdateServiceTest.this.cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(storedCard(STORED_NAME_FOLDED)));

            // The save key is only permitted while the changes await confirmation. Here the screen is in
            // the display state, so it is not permitted and no message is raised.
            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(reviewingTurnWithKey("DFHPF5", STORED_NAME_FOLDED, "N"));

            assertThat(result.workArea().keyAction()).isEqualTo(
                    com.carddemo.domain.enums.KeyAction.ENTER);
            assertThat(result.message()).isEmpty();
            assertThat(result.writeOutcome())
                    .isEqualTo(CardUpdateService.WriteOutcome.NOT_ATTEMPTED);
            // A key that decoded but is not permitted is a different outcome from one that did not
            // decode: the legacy says nothing at all about the former.
            assertThat(result.attentionKeyUnmapped()).isFalse();
            assertThat(result.errorFlag()).isFalse();
        }

        /**
         * A reviewing turn with a caller-chosen attention identifier.
         *
         * @param attentionKeyIdentifier the raw identifier
         * @param embossedName the submitted name
         * @param activeStatus the submitted status
         * @return the transmitted screen, never {@code null}
         */
        private CardUpdateService.CardUpdateScreenInput reviewingTurnWithKey(
                final String attentionKeyIdentifier, final String embossedName,
                final String activeStatus) {
            return new CardUpdateService.CardUpdateScreenInput(ACCOUNT_ID, CARD_NUMBER, embossedName,
                    activeStatus, "07", "2028", "31", attentionKeyIdentifier, reEntryContext(),
                    CardUpdateService.ChangeAction.SHOW_DETAILS, carriedImage(STORED_NAME_FOLDED));
        }
    }

    // ==============================================================================================
    // Assertion (i): the abend contract, and the filter edits, the fresh entry and the exit arm
    // ==============================================================================================

    @Nested
    @DisplayName("dispatch, the filter edits, and the abend contract")
    class DispatchAndAbend {

        @Test
        @DisplayName("(i) the exit arm propagates the dispatch graph's abend when the nominated program "
                + "cannot be resolved, and nothing is written")
        void exitArmPropagatesAbend() {
            final ScreenNavigationState unresolvable = new ScreenNavigationState("XXXX", "NOSUCHPG", null, null,
                    "USER0001", "U", ScreenNavigationState.ProgramContext.REENTER, "000000001", "ANIYA",
                    null, "VON", ACCOUNT_ID, "Y", CARD_NUMBER, "CCRDUPA", "COCRDUP");

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> CardUpdateServiceTest.this.service.processCardUpdate(
                            new CardUpdateService.CardUpdateScreenInput(ACCOUNT_ID, CARD_NUMBER,
                                    STORED_NAME_FOLDED, "Y", "07", "2028", "31", "DFHPF3",
                                    unresolvable, CardUpdateService.ChangeAction.SHOW_DETAILS,
                                    carriedImage(STORED_NAME_FOLDED))));

            Mockito.verify(CardUpdateServiceTest.this.cardRepository, Mockito.never())
                    .saveAndFlush(Mockito.any());
        }

        @Test
        @DisplayName("the exit key routes back to the nominated caller and raises no message, because "
                + "the exit text is declared and never set")
        void exitKeyRoutesBackAndRaisesNoMessage() {
            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(new CardUpdateService.CardUpdateScreenInput(ACCOUNT_ID,
                            CARD_NUMBER, STORED_NAME_FOLDED, "Y", "07", "2028", "31", "DFHPF3",
                            reEntryContext(), CardUpdateService.ChangeAction.SHOW_DETAILS,
                            carriedImage(STORED_NAME_FOLDED)));

            assertThat(result.route()).isEqualTo(NavigationService.Route.CARD_UPDATE);
            assertThat(result.message()).isEmpty();
            assertThat(result.navigationContext().fromProgram()).isEqualTo("COCRDUPC");
            assertThat(result.navigationContext().userType()).isEqualTo("U");
            assertThat(result.reArmedTransactionId()).isEqualTo("CCUP");
            assertThat(result.workArea().nextMapset()).isEqualTo("COCRDUP");
            assertThat(result.workArea().nextMap()).isEqualTo("CCRDUPA");
            assertThat(result.workArea().nextProgram()).isEqualTo("COCRDUPC");
        }

        @Test
        @DisplayName("a turn carrying no state at all presents the empty screen and prompts for the keys")
        void turnWithNoStatePresentsTheEmptyScreen() {
            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(new CardUpdateService.CardUpdateScreenInput(null, null, null,
                            null, null, null, null, "DFHENTER", null, null, null));

            assertThat(result.route()).isEqualTo(NavigationService.Route.CARD_UPDATE);
            assertThat(result.infoMessage()).isEqualTo("Please enter Account and Card Number");
            assertThat(result.changeAction())
                    .isEqualTo(CardUpdateService.ChangeAction.DETAILS_NOT_FETCHED);
            assertThat(result.reEntry()).isTrue();
            assertThat(result.card()).isNull();
            assertThat(result.carriedImage().absent()).isTrue();
            assertThat(result.header().transactionName()).isEqualTo("CCUP");
            assertThat(result.header().programName()).isEqualTo("COCRDUPC");
            assertThat(result.header().currentDate()).isEqualTo("03/14/24");
            assertThat(result.header().currentTime()).isEqualTo("15:09:26");
            assertThat(result.header().title01()).isNotEmpty();
            assertThat(result.header().title02()).isNotEmpty();
            assertThat(result.screen().fieldProtection())
                    .isEqualTo(CardUpdateService.FieldProtection.SEARCH_KEYS_OPEN);
            assertThat(result.screen().confirmationKeysHighlighted()).isFalse();
            assertThat(result.screen().expiryDay()).isNull();
        }

        @Test
        @DisplayName("both filters cleared on a re-entry raise the no-input text and mark both fields")
        void bothFiltersClearedRaiseNoInput() {
            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(new CardUpdateService.CardUpdateScreenInput("*", "*", null, null,
                            null, null, null, "DFHENTER", reEntryContext(),
                            CardUpdateService.ChangeAction.DETAILS_NOT_FETCHED, null));

            assertThat(result.message()).isEqualTo("No input received");
            assertThat(result.errorFlag()).isTrue();
            assertThat(result.fieldErrors()).extracting(ValidationException.FieldError::field)
                    .containsExactly("accountId", "cardNumber");
            assertThat(result.fieldErrors()).allSatisfy(error -> assertThat(error.state())
                    .isEqualTo(ValidationException.FieldState.MISSING));
            assertThat(result.screen().accountId()).isEqualTo("*");
            assertThat(result.screen().cardNumber()).isEqualTo("*");
            assertThat(result.focusField()).isEqualTo("accountId");
        }

        @Test
        @DisplayName("a filter shorter than its field is not numeric, because the move left spaces in "
                + "the remaining positions")
        void shortFilterIsNotNumeric() {
            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(new CardUpdateService.CardUpdateScreenInput("123", "456", null,
                            null, null, null, null, "DFHENTER", reEntryContext(),
                            CardUpdateService.ChangeAction.DETAILS_NOT_FETCHED, null));

            assertThat(result.message())
                    .isEqualTo("ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER");
            assertThat(result.fieldErrors()).extracting(ValidationException.FieldError::field)
                    .containsExactly("accountId", "cardNumber");
            assertThat(result.fieldErrors()).allSatisfy(error -> assertThat(error.state())
                    .isEqualTo(ValidationException.FieldState.INVALID));
        }

        @Test
        @DisplayName("an account with no card number resolves through the declared account path at "
                + "line 254, and an absent row is the not-found outcome")
        void accountOnlyResolvesThroughTheAccountPath() {
            Mockito.when(CardUpdateServiceTest.this.cardRepository
                            .findByCardAcctId(ACCOUNT_ID))
                    .thenReturn(List.of());

            final ScreenNavigationState fromCardList = new ScreenNavigationState("CCLI", "COCRDLIC", null, null,
                    "USER0001", "U", ScreenNavigationState.ProgramContext.ENTER, "000000001", "ANIYA", null,
                    "VON", ACCOUNT_ID, "Y", null, "CCRDSLA", "COCRDLI");

            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(new CardUpdateService.CardUpdateScreenInput(null, null, null,
                            null, null, null, null, "DFHENTER", fromCardList, null, null));

            assertThat(result.message()).isEqualTo("Did not find cards for this search condition");
            assertThat(result.errorFlag()).isTrue();
            assertThat(result.card()).isNull();
            Mockito.verify(CardUpdateServiceTest.this.cardRepository)
                    .findByCardAcctId(ACCOUNT_ID);
            Mockito.verify(CardUpdateServiceTest.this.cardRepository, Mockito.never())
                    .findById(Mockito.anyString());
        }

        @Test
        @DisplayName("a completed write resets the conversation and asks for fresh keys")
        void completedWriteResetsTheConversation() {
            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(new CardUpdateService.CardUpdateScreenInput(ACCOUNT_ID,
                            CARD_NUMBER, STORED_NAME_FOLDED, "Y", "07", "2028", "31", "DFHENTER",
                            reEntryContext(),
                            CardUpdateService.ChangeAction.CHANGES_OKAYED_AND_DONE,
                            carriedImage(STORED_NAME_FOLDED)));

            assertThat(result.changeAction())
                    .isEqualTo(CardUpdateService.ChangeAction.DETAILS_NOT_FETCHED);
            assertThat(result.reEntry()).isTrue();
            assertThat(result.navigationContext().accountId()).isEqualTo("00000000000");
            assertThat(result.navigationContext().cardNumber()).isEqualTo("0000000000000000");
            assertThat(result.infoMessage()).isEqualTo("Please enter Account and Card Number");
        }

        @Test
        @DisplayName("an absent request is refused rather than silently accepted")
        void absentRequestIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> CardUpdateServiceTest.this.service.processCardUpdate(null))
                    .withMessageContaining("input must not be null");
        }

        @Test
        @DisplayName("every collaborator is mandatory")
        void everyCollaboratorIsMandatory() {
            final Clock clock = Clock.systemUTC();
            final RecordWriter recordWriter = RecordWriterDoubles.passthrough();
            assertThatNullPointerException().isThrownBy(() -> new CardUpdateService(null,
                    CardUpdateServiceTest.this.abendService, new MessageCatalogService(),
                    new NavigationService(), new OnlineTransactionBoundary(), recordWriter, clock));
            assertThatNullPointerException().isThrownBy(() -> new CardUpdateService(
                    CardUpdateServiceTest.this.cardRepository, null, new MessageCatalogService(),
                    new NavigationService(), new OnlineTransactionBoundary(), recordWriter, clock));
            assertThatNullPointerException().isThrownBy(() -> new CardUpdateService(
                    CardUpdateServiceTest.this.cardRepository,
                    CardUpdateServiceTest.this.abendService, null, new NavigationService(),
                    new OnlineTransactionBoundary(), recordWriter, clock));
            assertThatNullPointerException().isThrownBy(() -> new CardUpdateService(
                    CardUpdateServiceTest.this.cardRepository,
                    CardUpdateServiceTest.this.abendService, new MessageCatalogService(), null,
                    new OnlineTransactionBoundary(), recordWriter, clock));
            assertThatNullPointerException().isThrownBy(() -> new CardUpdateService(
                    CardUpdateServiceTest.this.cardRepository,
                    CardUpdateServiceTest.this.abendService, new MessageCatalogService(),
                    new NavigationService(), null, recordWriter, clock));
            assertThatNullPointerException().isThrownBy(() -> new CardUpdateService(
                    CardUpdateServiceTest.this.cardRepository,
                    CardUpdateServiceTest.this.abendService, new MessageCatalogService(),
                    new NavigationService(), new OnlineTransactionBoundary(), null, clock));
            assertThatNullPointerException().isThrownBy(() -> new CardUpdateService(
                    CardUpdateServiceTest.this.cardRepository,
                    CardUpdateServiceTest.this.abendService, new MessageCatalogService(),
                    new NavigationService(), new OnlineTransactionBoundary(), recordWriter, null));
        }
    }

    // ==============================================================================================
    // The level-88 derived enums, as contract
    // ==============================================================================================

    @Nested
    @DisplayName("the level-88 condition names as enums")
    class ConditionNames {

        @Test
        @DisplayName("the screen state's grouped condition names cover exactly the declared value sets")
        void groupedConditionNamesCoverTheDeclaredSets() {
            assertThat(CardUpdateService.ChangeAction.DETAILS_NOT_FETCHED.getCode()).isEqualTo(' ');
            assertThat(CardUpdateService.ChangeAction.SHOW_DETAILS.getCode()).isEqualTo('S');
            assertThat(CardUpdateService.ChangeAction.CHANGES_NOT_OK.getCode()).isEqualTo('E');
            assertThat(CardUpdateService.ChangeAction.CHANGES_OK_NOT_CONFIRMED.getCode())
                    .isEqualTo('N');
            assertThat(CardUpdateService.ChangeAction.CHANGES_OKAYED_AND_DONE.getCode())
                    .isEqualTo('C');
            assertThat(CardUpdateService.ChangeAction.CHANGES_OKAYED_LOCK_ERROR.getCode())
                    .isEqualTo('L');
            assertThat(CardUpdateService.ChangeAction.CHANGES_OKAYED_BUT_FAILED.getCode())
                    .isEqualTo('F');

            assertThat(List.of(CardUpdateService.ChangeAction.values()))
                    .filteredOn(CardUpdateService.ChangeAction::changesMade)
                    .containsExactly(CardUpdateService.ChangeAction.CHANGES_NOT_OK,
                            CardUpdateService.ChangeAction.CHANGES_OK_NOT_CONFIRMED,
                            CardUpdateService.ChangeAction.CHANGES_OKAYED_AND_DONE,
                            CardUpdateService.ChangeAction.CHANGES_OKAYED_LOCK_ERROR,
                            CardUpdateService.ChangeAction.CHANGES_OKAYED_BUT_FAILED);
            assertThat(List.of(CardUpdateService.ChangeAction.values()))
                    .filteredOn(CardUpdateService.ChangeAction::changesFailed)
                    .containsExactly(CardUpdateService.ChangeAction.CHANGES_OKAYED_LOCK_ERROR,
                            CardUpdateService.ChangeAction.CHANGES_OKAYED_BUT_FAILED);

            assertThat(CardUpdateService.ChangeAction.DETAILS_NOT_FETCHED.detailsNotFetched()).isTrue();
            assertThat(CardUpdateService.ChangeAction.SHOW_DETAILS.showDetails()).isTrue();
            assertThat(CardUpdateService.ChangeAction.CHANGES_NOT_OK.changesNotOk()).isTrue();
            assertThat(CardUpdateService.ChangeAction.CHANGES_OK_NOT_CONFIRMED
                    .changesOkNotConfirmed()).isTrue();
            assertThat(CardUpdateService.ChangeAction.CHANGES_OKAYED_AND_DONE.changesOkayedAndDone())
                    .isTrue();
            assertThat(CardUpdateService.ChangeAction.CHANGES_OKAYED_LOCK_ERROR
                    .changesOkayedLockError()).isTrue();
            assertThat(CardUpdateService.ChangeAction.CHANGES_OKAYED_BUT_FAILED
                    .changesOkayedButFailed()).isTrue();
        }

        @Test
        @DisplayName("the edit flag starts blank and maps its two failing states to MISSING and INVALID")
        void editFlagMapsItsTwoFailingStates() {
            assertThat(CardUpdateService.EditFlag.NOT_OK.getCode()).isEqualTo('0');
            assertThat(CardUpdateService.EditFlag.IS_VALID.getCode()).isEqualTo('1');
            assertThat(CardUpdateService.EditFlag.BLANK.getCode()).isEqualTo(' ');

            assertThat(CardUpdateService.EditFlag.BLANK.blank()).isTrue();
            assertThat(CardUpdateService.EditFlag.NOT_OK.notOk()).isTrue();
            assertThat(CardUpdateService.EditFlag.IS_VALID.valid()).isTrue();
            assertThat(CardUpdateService.EditFlag.IS_VALID.decorated()).isFalse();
            assertThat(CardUpdateService.EditFlag.BLANK.decorated()).isTrue();
            assertThat(CardUpdateService.EditFlag.NOT_OK.decorated()).isTrue();

            assertThat(CardUpdateService.EditFlag.BLANK.decorationFlag())
                    .isEqualTo(FieldErrorMarks.FlagState.BLANK);
            assertThat(CardUpdateService.EditFlag.NOT_OK.decorationFlag())
                    .isEqualTo(FieldErrorMarks.FlagState.NOT_OK);
            assertThat(CardUpdateService.EditFlag.BLANK.fieldState())
                    .isEqualTo(ValidationException.FieldState.MISSING);
            assertThat(CardUpdateService.EditFlag.NOT_OK.fieldState())
                    .isEqualTo(ValidationException.FieldState.INVALID);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(CardUpdateService.EditFlag.IS_VALID::decorationFlag);
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(CardUpdateService.EditFlag.IS_VALID::fieldState);
        }

        @Test
        @DisplayName("the input and attention-key flags expose their declared predicates")
        void inputAndAttentionKeyFlagsExposeTheirPredicates() {
            assertThat(CardUpdateService.InputState.OK.inputOk()).isTrue();
            assertThat(CardUpdateService.InputState.ERROR.inputError()).isTrue();
            assertThat(CardUpdateService.InputState.PENDING.inputPending()).isTrue();
            assertThat(CardUpdateService.InputState.OK.inputError()).isFalse();

            assertThat(CardUpdateService.AttentionKeyState.VALID.permitted()).isTrue();
            assertThat(CardUpdateService.AttentionKeyState.INVALID.permitted()).isFalse();
        }

        @Test
        @DisplayName("the write outcome maps its three failing arms onto the shared conflict kinds")
        void writeOutcomeMapsOntoConflictKinds() {
            assertThat(CardUpdateService.WriteOutcome.COMMITTED.committed()).isTrue();
            assertThat(CardUpdateService.WriteOutcome.NOT_ATTEMPTED.committed()).isFalse();

            assertThat(CardUpdateService.WriteOutcome.LOCK_NOT_ACQUIRED.conflictKind())
                    .isEqualTo(OptimisticLockConflictException.ConflictKind.LOCK_NOT_ACQUIRED);
            assertThat(CardUpdateService.WriteOutcome.RECORD_CHANGED_BEFORE_UPDATE.conflictKind())
                    .isEqualTo(
                            OptimisticLockConflictException.ConflictKind.RECORD_CHANGED_BEFORE_UPDATE);
            assertThat(CardUpdateService.WriteOutcome.UPDATE_FAILED_AFTER_LOCK.conflictKind())
                    .isEqualTo(OptimisticLockConflictException.ConflictKind.UPDATE_FAILED_AFTER_LOCK);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(CardUpdateService.WriteOutcome.COMMITTED::conflictKind);
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(CardUpdateService.WriteOutcome.NOT_ATTEMPTED::conflictKind);

            assertThat(CardUpdateService.FieldProtection.values()).hasSize(3);
        }

        @Test
        @DisplayName("the carried image withholds both sensitive components from diagnostics")
        void carriedImageWithholdsSensitiveComponents() {
            final CardUpdateService.CarriedCardImage empty = CardUpdateService.CarriedCardImage.empty();
            assertThat(empty.absent()).isTrue();
            assertThat(empty.accountId()).isNull();
            assertThat(empty.expiryDay()).isNull();

            final CardUpdateService.CarriedCardImage populated = carriedImage(STORED_NAME_FOLDED);
            assertThat(populated.absent()).isFalse();
            assertThat(populated.expiryYear()).isEqualTo("2028");
            assertThat(populated.expiryMonth()).isEqualTo("07");
            // All three business keys are withheld. The account identifier is withheld here even though
            // the navigation state renders its own copy the same way, because this record is the one that
            // travels nested inside a request whose rendering also redacts: a key left in clear on the
            // nested value would defeat the outer redaction entirely.
            assertThat(populated.toString())
                    .contains("ANIYA VON")
                    .doesNotContain(ACCOUNT_ID)
                    .doesNotContain(CARD_NUMBER)
                    .doesNotContain(VERIFICATION_CODE)
                    .doesNotContain("verificationCode=" + VERIFICATION_CODE);
        }

        @Test
        @DisplayName("the request withholds both identifiers from diagnostics")
        void requestWithholdsBothIdentifiers() {
            final CardUpdateService.CardUpdateScreenInput input =
                    confirmingTurn("DFHPF5", "MARY ANN", "N", carriedImage(STORED_NAME_FOLDED));

            assertThat(input.carriesNoNavigationState()).isFalse();
            assertThat(input.expiryDay()).isEqualTo("31");
            // The nested fetched image is rendered inside the request's own rendering, so leaving a key
            // in clear on the nested value would defeat the outer redaction entirely.
            assertThat(input.toString())
                    .doesNotContain(ACCOUNT_ID)
                    .doesNotContain(CARD_NUMBER)
                    .doesNotContain("verificationCode=" + VERIFICATION_CODE)
                    .contains("MARY ANN");
            assertThat(carriedImage(STORED_NAME_FOLDED).toString())
                    .doesNotContain(ACCOUNT_ID)
                    .doesNotContain(CARD_NUMBER)
                    .doesNotContain("verificationCode=" + VERIFICATION_CODE);

            assertThat(new CardUpdateService.CardUpdateScreenInput(null, null, null, null, null, null,
                    null, null, null, null, null).carriesNoNavigationState()).isTrue();
        }

        @Test
        @DisplayName("the result normalises an absent field-error list rather than exposing null")
        void resultNormalisesAnAbsentFieldErrorList() {
            final CardUpdateService.CardUpdateResult result = new CardUpdateService.CardUpdateResult(
                    NavigationService.Route.CARD_UPDATE, ScreenNavigationState.empty(), "CCUP", null,
                    CardUpdateService.ChangeAction.SHOW_DETAILS, null,
                    CardUpdateService.CarriedCardImage.empty(), "", "", "accountId", false, false,
                    false, false, CardUpdateService.WriteOutcome.NOT_ATTEMPTED, null,
                    FieldErrorMarks.none(), null, null);

            assertThat(result.fieldErrors()).isEmpty();
            assertThat(result.updateCommitted()).isFalse();
            assertThat(result.awaitingConfirmation()).isFalse();
            assertThat(result.decoration().isEmpty()).isTrue();
        }

        @Test
        @DisplayName("the not-found status the read reports is the one the shared exception publishes")
        void notFoundStatusIsTheSharedOne() {
            assertThat(RecordNotFoundException.STATUS_RECORD_NOT_FOUND).isEqualTo("23");
        }
    }

    // ==============================================================================================
    // The filter-fetch path, the file-error path, and the one reachable abend
    // ==============================================================================================

    @Nested
    @DisplayName("the filter fetch at lines 954 to 966, and the read failure at lines 1402 to 1411")
    class FilterFetchAndFileError {

        /**
         * A turn that has fetched nothing yet and is submitting the two filter keys.
         *
         * <p>Not a first entry, so the fresh-entry arm at lines 502 to 505 does not take it, and not from
         * the card-list or menu programs either, so the catch-all arm at lines 535 to 542 runs the edits.
         *
         * @param accountId the submitted account filter
         * @param cardNumber the submitted card filter
         * @return the transmitted screen, never {@code null}
         */
        private CardUpdateService.CardUpdateScreenInput fetchingTurn(final String accountId,
                final String cardNumber) {
            return new CardUpdateService.CardUpdateScreenInput(accountId, cardNumber, null, null, null,
                    null, null, "DFHENTER", reEntryContext(),
                    CardUpdateService.ChangeAction.DETAILS_NOT_FETCHED,
                    CardUpdateService.CarriedCardImage.empty());
        }

        @Test
        @DisplayName("both filters accepted, the card fetched, and the screen moved to the display state")
        void validFiltersFetchTheCardAndShowIt() {
            Mockito.when(CardUpdateServiceTest.this.cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(storedCard(STORED_NAME_FOLDED)));

            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(fetchingTurn(ACCOUNT_ID, CARD_NUMBER));

            // Lines 752 to 754 and 796 to 798 accept both filters, so clause one of the action decision
            // performs the fetch and lines 964 to 965 move the screen to the display state.
            assertThat(result.changeAction())
                    .isEqualTo(CardUpdateService.ChangeAction.SHOW_DETAILS);
            assertThat(result.card()).isNotNull();
            assertThat(result.card().accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(result.infoMessage()).isEqualTo(CardUpdateService.INFO_DETAILS_SHOWN);
            assertThat(result.message()).isEmpty();
            assertThat(result.errorFlag()).isFalse();
            // The fetch keys on the card number, so the primary-key finder is the one that runs.
            Mockito.verify(CardUpdateServiceTest.this.cardRepository).findById(CARD_NUMBER);
        }

        @Test
        @DisplayName("a filter shorter than its declared width is not numeric across the field, per the "
                + "class test at lines 740 and 784")
        void aShortFilterFailsTheClassTest() {
            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(fetchingTurn("123", CARD_NUMBER));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message())
                    .isEqualTo(CardUpdateService.MSG_ACCOUNT_FILTER_ELEVEN_DIGITS);
            assertThat(result.focusField()).isEqualTo(CardUpdateService.FIELD_ACCOUNT_ID);
            // The filters were not both valid, so no fetch was attempted at all.
            Mockito.verify(CardUpdateServiceTest.this.cardRepository, Mockito.never())
                    .findById(Mockito.anyString());
        }

        @Test
        @DisplayName("with the account filter accepted the cursor moves on to the card filter, per the "
                + "clause order at lines 1214 to 1219")
        void aValidAccountWithAnInvalidCardPutsTheCursorOnTheCardFilter() {
            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(fetchingTurn(ACCOUNT_ID, "41111111111111X"));

            assertThat(result.message()).isEqualTo(CardUpdateService.MSG_CARD_FILTER_SIXTEEN_DIGITS);
            assertThat(result.focusField()).isEqualTo(CardUpdateService.FIELD_CARD_NUMBER);
            assertThat(result.errorFlag()).isTrue();
        }

        @Test
        @DisplayName("neither filter supplied raises the no-input message, which the source assigns "
                + "unconditionally at lines 656 to 659")
        void neitherFilterSuppliedRaisesTheNoInputMessage() {
            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(fetchingTurn("           ", "                "));

            // The assignment is NOT gated on the summary being off, so it overwrites the account-filter
            // message that the account edit raised first.
            assertThat(result.message()).isEqualTo(CardUpdateService.MSG_NO_INPUT_RECEIVED);
            assertThat(result.errorFlag()).isTrue();
            assertThat(result.focusField()).isEqualTo(CardUpdateService.FIELD_ACCOUNT_ID);
        }

        @Test
        @DisplayName("an all-zero filter is NOT SUPPLIED rather than supplied-as-zero, which is what the "
                + "numeric redefinition tests at lines 768 to 780")
        void anAllZeroFilterIsTreatedAsNotSupplied() {
            // The reset at lines 733 to 734 and 777 to 778 writes zeros into the shared keys, so the next
            // turn echoes zeros back. Treating them as a supplied value would fetch account zero.
            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(fetchingTurn("00000000000", "0000000000000000"));

            assertThat(result.message()).isEqualTo(CardUpdateService.MSG_NO_INPUT_RECEIVED);
            assertThat(result.errorFlag()).isTrue();
            Mockito.verify(CardUpdateServiceTest.this.cardRepository, Mockito.never())
                    .findById(Mockito.anyString());
        }

        @Test
        @DisplayName("with neither key available the read is not attempted at all and the turn reports "
                + "not-found")
        void withNoKeyAtAllTheReadIsNotAttempted() {
            final ScreenNavigationState fromCardListWithNoKeys = new ScreenNavigationState("CCLI",
                    CardUpdateService.LEGACY_CARD_LIST_PROGRAM, null, null, "USER0001", "U",
                    ScreenNavigationState.ProgramContext.ENTER, "000000001", "ANIYA", null, "VON",
                    null, "Y", null, CardUpdateService.LEGACY_CARD_LIST_MAPSET, "COCRDLIC");

            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(new CardUpdateService.CardUpdateScreenInput(null, null, null,
                            null, null, null, null, "DFHENTER", fromCardListWithNoKeys,
                            CardUpdateService.ChangeAction.DETAILS_NOT_FETCHED,
                            CardUpdateService.CarriedCardImage.empty()));

            assertThat(result.message()).isEqualTo(CardUpdateService.MSG_NO_CARD_FOR_SEARCH);
            assertThat(result.card()).isNull();
            Mockito.verifyNoInteractions(CardUpdateServiceTest.this.cardRepository);
        }

        @Test
        @DisplayName("a non-numeric month fails the class test before the range test is reached")
        void aNonNumericMonthFailsTheClassTestFirst() {
            Mockito.when(CardUpdateServiceTest.this.cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(storedCard(STORED_NAME_FOLDED)));

            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(new CardUpdateService.CardUpdateScreenInput(ACCOUNT_ID,
                            CARD_NUMBER, "MARY ANN", "N", "1X", "2028", "31", "DFHENTER",
                            reEntryContext(), CardUpdateService.ChangeAction.SHOW_DETAILS,
                            carriedImage(STORED_NAME_FOLDED)));

            assertThat(result.message()).isEqualTo(CardUpdateService.MSG_EXPIRY_MONTH_NOT_VALID);
            assertThat(result.focusField()).isEqualTo(CardUpdateService.FIELD_EXPIRY_MONTH);
        }

        @Test
        @DisplayName("a stored row with no expiration date at all yields absent components rather than "
                + "failing")
        void anAbsentStoredExpirationDateYieldsAbsentComponents() {
            final Card noDate = new Card(CARD_NUMBER, ACCOUNT_ID, VERIFICATION_CODE,
                    STORED_NAME_FOLDED, null, "Y");
            Mockito.when(CardUpdateServiceTest.this.cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(noDate));

            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(fetchingTurn(ACCOUNT_ID, CARD_NUMBER));

            assertThat(result.card()).isNotNull();
            assertThat(result.card().expirationDate()).isNull();
            assertThat(result.carriedImage().expiryYear()).isNull();
            assertThat(result.carriedImage().expiryMonth()).isNull();
            assertThat(result.carriedImage().expiryDay()).isNull();
        }

        @Test
        @DisplayName("a read that neither succeeded nor found nothing assembles the file-error text at "
                + "exactly the seventy-five characters the destination field holds")
        void aReadFailureAssemblesTheFixedWidthFileErrorText() {
            Mockito.when(CardUpdateServiceTest.this.cardRepository.findById(CARD_NUMBER))
                    .thenThrow(new RecoverableDataAccessException("card file unavailable"));

            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(fetchingTurn(ACCOUNT_ID, CARD_NUMBER));

            // Line 1411 moves the assembled text unconditionally. The structure at lines 133 to 152
            // declares eighty characters but the destination at line 173 is seventy-five, so the trailing
            // five-character filler never survives the move.
            assertThat(result.message()).hasSize(CardUpdateService.RETURN_MESSAGE_WIDTH);
            assertThat(result.message()).startsWith("File Error: READ");
            assertThat(result.message()).contains(" on CARDDAT");
            assertThat(result.message()).contains("returned RESP");
            assertThat(result.errorFlag()).isTrue();
            // The catch-all arm never reaches the display state, so nothing is presented.
            assertThat(result.changeAction())
                    .isEqualTo(CardUpdateService.ChangeAction.DETAILS_NOT_FETCHED);
            assertThat(result.card()).isNull();

            // The failure is logged with the raw status and the resource, and nothing sensitive appears.
            assertThat(renderedLog()).contains("Card file read failed");
            assertThat(renderedLog()).contains("resource=CARDDAT");
            assertThat(renderedLog()).doesNotContain(VERIFICATION_CODE);
            assertThat(renderedLog()).doesNotContain(CARD_NUMBER);
        }

        @Test
        @DisplayName("a fetch that finds nothing is the not-found outcome and marks both filters")
        void anAbsentRowIsTheNotFoundOutcome() {
            Mockito.when(CardUpdateServiceTest.this.cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.empty());

            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(fetchingTurn(ACCOUNT_ID, CARD_NUMBER));

            assertThat(result.message()).isEqualTo(CardUpdateService.MSG_NO_CARD_FOR_SEARCH);
            assertThat(result.errorFlag()).isTrue();
            assertThat(result.card()).isNull();
        }

        @Test
        @DisplayName("with no card number available the fetch resolves through the non-unique account "
                + "finder, whose name carries the take-the-first-row semantic")
        void theAccountPathResolvesThroughTheNonUniqueFinder() {
            Mockito.when(CardUpdateServiceTest.this.cardRepository
                            .findByCardAcctId(ACCOUNT_ID))
                    .thenReturn(List.of(storedCard(STORED_NAME_FOLDED)));

            // Arrival from the card-list screen takes both keys from the echoed state without editing
            // them, per lines 490 to 491, so a state carrying an account and no card reaches the
            // alternate-index path the legacy declared at line 254 and left unwired.
            final ScreenNavigationState fromCardList = new ScreenNavigationState("CCLI",
                    CardUpdateService.LEGACY_CARD_LIST_PROGRAM, null, null, "USER0001", "U",
                    ScreenNavigationState.ProgramContext.ENTER, "000000001", "ANIYA", null, "VON",
                    ACCOUNT_ID, "Y", null, CardUpdateService.LEGACY_CARD_LIST_MAPSET, "COCRDLIC");

            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(new CardUpdateService.CardUpdateScreenInput(ACCOUNT_ID, null,
                            null, null, null, null, null, "DFHENTER", fromCardList,
                            CardUpdateService.ChangeAction.DETAILS_NOT_FETCHED,
                            CardUpdateService.CarriedCardImage.empty()));

            Mockito.verify(CardUpdateServiceTest.this.cardRepository)
                    .findByCardAcctId(ACCOUNT_ID);
            Mockito.verify(CardUpdateServiceTest.this.cardRepository, Mockito.never())
                    .findById(Mockito.anyString());
            assertThat(result.card()).isNotNull();
            assertThat(result.changeAction())
                    .isEqualTo(CardUpdateService.ChangeAction.SHOW_DETAILS);
            assertThat(result.reEntry()).isTrue();
        }

        @Test
        @DisplayName("a read failure on the account path names the declared account path, not the base "
                + "file")
        void anAccountPathFailureNamesTheAccountPath() {
            Mockito.when(CardUpdateServiceTest.this.cardRepository
                            .findByCardAcctId(ACCOUNT_ID))
                    .thenThrow(new RecoverableDataAccessException("alternate index unavailable"));

            final ScreenNavigationState fromCardList = new ScreenNavigationState("CCLI",
                    CardUpdateService.LEGACY_CARD_LIST_PROGRAM, null, null, "USER0001", "U",
                    ScreenNavigationState.ProgramContext.ENTER, "000000001", "ANIYA", null, "VON",
                    ACCOUNT_ID, "Y", null, CardUpdateService.LEGACY_CARD_LIST_MAPSET, "COCRDLIC");

            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(new CardUpdateService.CardUpdateScreenInput(ACCOUNT_ID, null,
                            null, null, null, null, null, "DFHENTER", fromCardList,
                            CardUpdateService.ChangeAction.DETAILS_NOT_FETCHED,
                            CardUpdateService.CarriedCardImage.empty()));

            assertThat(result.message()).hasSize(CardUpdateService.RETURN_MESSAGE_WIDTH);
            assertThat(result.message()).contains(" on CARDAIX");
            assertThat(renderedLog()).contains("resource=CARDAIX");
        }
    }

    // ==============================================================================================
    // The blank-field cascade, its markers, and where the cursor lands
    // ==============================================================================================

    @Nested
    @DisplayName("the not-supplied branches of the four card-data edits, and the cursor at lines 1211 "
            + "to 1235")
    class BlankFieldCascade {

        /**
         * A reviewing turn with caller-chosen card-data fields.
         *
         * @param embossedName the submitted name
         * @param activeStatus the submitted status
         * @param expiryMonth the submitted month
         * @param expiryYear the submitted year
         * @return the transmitted screen, never {@code null}
         */
        private CardUpdateService.CardUpdateScreenInput reviewingTurnWithFields(
                final String embossedName, final String activeStatus, final String expiryMonth,
                final String expiryYear) {
            return new CardUpdateService.CardUpdateScreenInput(ACCOUNT_ID, CARD_NUMBER, embossedName,
                    activeStatus, expiryMonth, expiryYear, "31", "DFHENTER", reEntryContext(),
                    CardUpdateService.ChangeAction.SHOW_DETAILS, carriedImage(STORED_NAME_FOLDED));
        }

        @Test
        @DisplayName("three blank fields each raise MISSING, the summary keeps the first, and the cursor "
                + "lands on the status field")
        void threeBlankFieldsRaiseMissingAndTheFirstMessageWins() {
            Mockito.when(CardUpdateServiceTest.this.cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(storedCard(STORED_NAME_FOLDED)));

            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(reviewingTurnWithFields("MARY ANN", "  ", "  ", "    "));

            // The four edits run in declared order at lines 698 to 708: the name is accepted, then the
            // status, month and year each take their not-supplied branch. Every flag is set, but only the
            // first message survives the summary gate.
            assertThat(result.message()).isEqualTo(CardUpdateService.MSG_STATUS_MUST_BE_YES_NO);
            assertThat(result.errorFlag()).isTrue();
            assertThat(result.fieldErrors()).hasSize(3);
            assertThat(result.fieldErrors())
                    .allMatch(error -> error.state() == ValidationException.FieldState.MISSING);
            assertThat(result.fieldErrors())
                    .extracting(ValidationException.FieldError::field)
                    .containsExactly(CardUpdateService.FIELD_ACTIVE_STATUS,
                            CardUpdateService.FIELD_EXPIRY_MONTH,
                            CardUpdateService.FIELD_EXPIRY_YEAR);
            // A blank field is marked, so the decorator carries the blank flag state for all three.
            assertThat(result.decoration().markedFields())
                    .extracting(FieldErrorMarks.MarkedField::flagState)
                    .containsOnly(FieldErrorMarks.FlagState.BLANK);
            // Clause order at lines 1211 to 1235: the status flag is the first decorated one.
            assertThat(result.focusField()).isEqualTo(CardUpdateService.FIELD_ACTIVE_STATUS);
            assertThat(result.changeAction())
                    .isEqualTo(CardUpdateService.ChangeAction.CHANGES_NOT_OK);
            assertThat(result.writeOutcome())
                    .isEqualTo(CardUpdateService.WriteOutcome.NOT_ATTEMPTED);
        }

        @Test
        @DisplayName("tabs, line separators and Unicode spaces are supplied invalid characters rather than "
                + "COBOL SPACES, so none is mislabeled MISSING")
        void javaWhitespaceIsInvalidRatherThanMissing() {
            Mockito.when(CardUpdateServiceTest.this.cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(storedCard(STORED_NAME_FOLDED)));

            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(reviewingTurnWithFields(
                            "MARY ANN", "\t", "\n", "\u2003"));

            assertThat(result.fieldErrors()).hasSize(3);
            assertThat(result.fieldErrors())
                    .extracting(ValidationException.FieldError::field)
                    .containsExactly(CardUpdateService.FIELD_ACTIVE_STATUS,
                            CardUpdateService.FIELD_EXPIRY_MONTH,
                            CardUpdateService.FIELD_EXPIRY_YEAR);
            assertThat(result.fieldErrors())
                    .allMatch(error -> error.state() == ValidationException.FieldState.INVALID);
            assertThat(result.decoration().markedFields())
                    .extracting(FieldErrorMarks.MarkedField::flagState)
                    .containsOnly(FieldErrorMarks.FlagState.NOT_OK);
        }

        @Test
        @DisplayName("an all-low-values field is MISSING, but a field mixing spaces and low values is "
                + "supplied and INVALID because neither exact figurative comparison holds")
        void lowValuesAndMixedValuesAreDistinguished() {
            Mockito.when(CardUpdateServiceTest.this.cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(storedCard(STORED_NAME_FOLDED)));
            final String lowValue = String.valueOf('\0');

            final CardUpdateService.CardUpdateResult allLowValues =
                    CardUpdateServiceTest.this.service.processCardUpdate(reviewingTurnWithFields(
                            "MARY ANN", lowValue, lowValue.repeat(2), lowValue.repeat(4)));
            assertThat(allLowValues.fieldErrors()).hasSize(3);
            assertThat(allLowValues.fieldErrors())
                    .allMatch(error -> error.state() == ValidationException.FieldState.MISSING);

            final String mixedMonth = new String(new char[] {' ', '\0'});
            final CardUpdateService.CardUpdateResult mixed =
                    CardUpdateServiceTest.this.service.processCardUpdate(reviewingTurnWithFields(
                            "MARY ANN", "N", mixedMonth, "2028"));
            assertThat(mixed.fieldErrors()).singleElement()
                    .satisfies(error -> {
                        assertThat(error.field()).isEqualTo(CardUpdateService.FIELD_EXPIRY_MONTH);
                        assertThat(error.state()).isEqualTo(ValidationException.FieldState.INVALID);
                    });
        }

        @Test
        @DisplayName("with the status supplied the cursor moves on to the month, and an out-of-range "
                + "month is INVALID rather than MISSING")
        void anOutOfRangeMonthIsInvalidAndTakesTheCursor() {
            Mockito.when(CardUpdateServiceTest.this.cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(storedCard(STORED_NAME_FOLDED)));

            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(reviewingTurnWithFields("MARY ANN", "N", "13", "2028"));

            assertThat(result.message()).isEqualTo(CardUpdateService.MSG_EXPIRY_MONTH_NOT_VALID);
            assertThat(result.fieldErrors()).hasSize(1);
            assertThat(result.fieldErrors().get(0).state())
                    .isEqualTo(ValidationException.FieldState.INVALID);
            assertThat(result.decoration().markedFields())
                    .extracting(FieldErrorMarks.MarkedField::flagState)
                    .containsOnly(FieldErrorMarks.FlagState.NOT_OK);
            assertThat(result.focusField()).isEqualTo(CardUpdateService.FIELD_EXPIRY_MONTH);
        }

        @Test
        @DisplayName("with the status and month supplied the cursor moves on to the year, and a year "
                + "below the declared floor is refused")
        void aYearBelowTheFloorIsRefusedAndTakesTheCursor() {
            Mockito.when(CardUpdateServiceTest.this.cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(storedCard(STORED_NAME_FOLDED)));

            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(reviewingTurnWithFields("MARY ANN", "N", "07", "1949"));

            assertThat(result.message()).isEqualTo(CardUpdateService.MSG_EXPIRY_YEAR_NOT_VALID);
            assertThat(result.focusField()).isEqualTo(CardUpdateService.FIELD_EXPIRY_YEAR);
            assertThat(result.errorFlag()).isTrue();
        }

        @Test
        @DisplayName("a blank name is MISSING and takes the cursor ahead of every later field")
        void aBlankNameIsMissingAndTakesTheCursorFirst() {
            Mockito.when(CardUpdateServiceTest.this.cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(storedCard(STORED_NAME_FOLDED)));

            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(reviewingTurnWithFields("   ", "  ", "07", "2028"));

            assertThat(result.message()).isEqualTo(CardUpdateService.MSG_NAME_NOT_PROVIDED);
            assertThat(result.focusField()).isEqualTo(CardUpdateService.FIELD_EMBOSSED_NAME);
            assertThat(result.fieldErrors())
                    .extracting(ValidationException.FieldError::field)
                    .containsExactly(CardUpdateService.FIELD_EMBOSSED_NAME,
                            CardUpdateService.FIELD_ACTIVE_STATUS);
        }
    }

    // ==============================================================================================
    // The one reachable abend, and the states the dispatch resets
    // ==============================================================================================

    @Nested
    @DisplayName("the catch-all arm at lines 1019 to 1026, reached through an echoed lock-error state")
    class ReachableAbend {

        @Test
        @DisplayName("the lock-error state is one of the TWO values the failed-write condition name spans "
                + "at line 288, so the dispatch takes it and the catch-all never sees it")
        void anEchoedLockErrorStateDoesNotAbend() {
            Mockito.when(CardUpdateServiceTest.this.cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(storedCard(STORED_NAME_FOLDED)));

            // This is the assertion that pins down WHY the catch-all arm at lines 1019 to 1026 is
            // unreachable in the translation. The lock-error state is one this service itself returns, so
            // a client can echo it, and the action decision at lines 954 to 1018 has no clause naming it.
            // It still cannot reach the catch-all, because CCUP-CHANGES-FAILED is declared over TWO state
            // values and arm four of the dispatch at lines 517 to 518 therefore takes both of them.
            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(new CardUpdateService.CardUpdateScreenInput(ACCOUNT_ID,
                            CARD_NUMBER, STORED_NAME_LOWER, "Y", "07", "2028", "31", "DFHENTER",
                            reEntryContext(),
                            CardUpdateService.ChangeAction.CHANGES_OKAYED_LOCK_ERROR,
                            carriedImage(STORED_NAME_FOLDED)));

            assertThat(CardUpdateService.ChangeAction.CHANGES_OKAYED_LOCK_ERROR.changesFailed())
                    .isTrue();
            assertThat(CardUpdateService.ChangeAction.CHANGES_OKAYED_BUT_FAILED.changesFailed())
                    .isTrue();
            assertThat(result.changeAction())
                    .isEqualTo(CardUpdateService.ChangeAction.DETAILS_NOT_FETCHED);
            Mockito.verifyNoInteractions(CardUpdateServiceTest.this.abendService);
        }

        @Test
        @DisplayName("(i) an abend raised on this screen's behalf propagates unswallowed, carries the "
                + "terminal online code, and leaks nothing sensitive")
        void anAbendPropagatesCarryingTheTerminalCode() {
            // The reachable abend on this screen is the transfer the exit arm attempts: the dispatch graph
            // raises for a nominated program it cannot resolve, exactly as the legacy transfer at lines 469
            // to 475 would have abended on a program name the region cannot resolve.
            //
            // The emit-then-raise ordering of this member's OWN abend paragraph cannot be observed through
            // the public surface, because the only arm that performs it is unreachable once the state byte
            // becomes a typed enum - which is what the lock-error test above pins down. The ordering is
            // still guaranteed structurally: the diagnostic is the statement before the delegation, with no
            // branch between them, and the delegate logs before it throws.
            final ScreenNavigationState unresolvableCaller = new ScreenNavigationState("XXXX", "NOSUCHPG", null,
                    null, "USER0001", "U", ScreenNavigationState.ProgramContext.REENTER, "000000001",
                    "ANIYA", null, "VON", ACCOUNT_ID, "Y", CARD_NUMBER, "CCRDUPA", "COCRDUP");

            assertThatExceptionOfType(AbendException.class).isThrownBy(() ->
                    CardUpdateServiceTest.this.service.processCardUpdate(
                            new CardUpdateService.CardUpdateScreenInput(ACCOUNT_ID, CARD_NUMBER,
                                    STORED_NAME_FOLDED, "Y", "07", "2028", "31", "DFHPF3",
                                    unresolvableCaller, CardUpdateService.ChangeAction.SHOW_DETAILS,
                                    carriedImage(STORED_NAME_FOLDED))))
                    .satisfies(raised -> {
                        assertThat(raised.code()).isEqualTo(AbendException.ONLINE_ABEND_CODE);
                        assertThat(raised.culprit()).isEqualTo("NOSUCHPG");
                        assertThat(raised.reason()).isNotBlank();
                    });

            // The screen was never presented, so nothing was written and nothing sensitive was logged.
            assertThat(renderedLog()).doesNotContain(VERIFICATION_CODE);
            Mockito.verify(CardUpdateServiceTest.this.cardRepository, Mockito.never())
                    .saveAndFlush(Mockito.any());
        }

        @Test
        @DisplayName("an echoed failed-write state is taken by the dispatch before the action decision "
                + "sees it, so it never abends")
        void anEchoedFailedWriteStateDoesNotAbend() {
            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(new CardUpdateService.CardUpdateScreenInput(ACCOUNT_ID,
                            CARD_NUMBER, STORED_NAME_FOLDED, "Y", "07", "2028", "31", "DFHENTER",
                            reEntryContext(),
                            CardUpdateService.ChangeAction.CHANGES_OKAYED_BUT_FAILED,
                            carriedImage(STORED_NAME_FOLDED)));

            // Arm four at lines 517 to 528 clears the work area, presents the empty screen and arms the
            // next turn as a re-entry with nothing fetched. The clear at line 519 resets the state byte
            // BEFORE the screen is presented, so the presented screen is the not-fetched one and its
            // prompt is the search-key prompt rather than the failure notice.
            assertThat(result.changeAction())
                    .isEqualTo(CardUpdateService.ChangeAction.DETAILS_NOT_FETCHED);
            assertThat(result.reEntry()).isTrue();
            assertThat(result.infoMessage())
                    .isEqualTo(CardUpdateService.INFO_PROMPT_FOR_SEARCH_KEYS);
            assertThat(result.screen().embossedName()).isNull();
            Mockito.verifyNoInteractions(CardUpdateServiceTest.this.abendService);
            Mockito.verify(CardUpdateServiceTest.this.cardRepository, Mockito.never())
                    .saveAndFlush(Mockito.any());
        }

        @Test
        @DisplayName("a stored expiration date that is absent or malformed yields absent components "
                + "rather than sliced bytes")
        void aMalformedStoredExpirationDateYieldsAbsentComponents() {
            final Card malformed = new Card(CARD_NUMBER, ACCOUNT_ID, VERIFICATION_CODE,
                    STORED_NAME_FOLDED, "2028/07/31", "Y");
            Mockito.when(CardUpdateServiceTest.this.cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(malformed));
            Mockito.when(CardUpdateServiceTest.this.cardRepository.saveAndFlush(Mockito.any()))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            // The separator the write path assembles with at lines 1467 to 1474 is not present, so the
            // three components are absent and the comparison at lines 1503 to 1508 sees a difference.
            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(confirmingTurn("DFHPF5", "MARY ANN", "N",
                            carriedImage(STORED_NAME_FOLDED)));

            assertThat(result.changeDetected()).isTrue();
            assertThat(result.message())
                    .isEqualTo(OptimisticLockConflictException.MSG_DATA_WAS_CHANGED_BEFORE_UPDATE);
            assertThat(result.writeOutcome())
                    .isEqualTo(CardUpdateService.WriteOutcome.RECORD_CHANGED_BEFORE_UPDATE);
        }

        @Test
        @DisplayName("an exit turn with no originating transaction re-arms the menu transaction")
        void anExitTurnWithNoCallerFallsBackToTheMenu() {
            final ScreenNavigationState noCaller = new ScreenNavigationState(null, null, null, null,
                    "USER0001", "U", ScreenNavigationState.ProgramContext.REENTER, "000000001", "ANIYA",
                    null, "VON", ACCOUNT_ID, "Y", CARD_NUMBER, "CCRDUPA", "COCRDUP");

            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(new CardUpdateService.CardUpdateScreenInput(ACCOUNT_ID,
                            CARD_NUMBER, STORED_NAME_FOLDED, "Y", "07", "2028", "31", "DFHPF3",
                            noCaller, CardUpdateService.ChangeAction.SHOW_DETAILS,
                            carriedImage(STORED_NAME_FOLDED)));

            // Lines 442 to 447 default the destination to the menu when the originating fields are blank.
            assertThat(result.route()).isEqualTo(NavigationService.Route.USER_MENU);
            assertThat(result.navigationContext().toTransactionId())
                    .isEqualTo(CardUpdateService.LEGACY_MENU_TRANSACTION_ID);
            assertThat(result.workArea().keyAction())
                    .isEqualTo(com.carddemo.domain.enums.KeyAction.PFK03);
        }
    }
}
