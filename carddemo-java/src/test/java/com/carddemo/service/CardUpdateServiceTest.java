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

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import com.carddemo.domain.enums.CardStatus;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.exception.AbendException;
import com.carddemo.exception.OptimisticLockConflictException;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.CardRepository;
import com.carddemo.support.SensitiveValues;
import com.carddemo.support.TestDataFactory;
import com.carddemo.support.TraceabilityMatrixCensus;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Unit test for {@link CardUpdateService}, the translation of the card-update transaction {@code CCUP}
 * carried by {@code app/cbl/COCRDUPC.cbl}.
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
 * program cannot be resolved, and that raise is asserted to propagate - and, because the graph emits its
 * diagnostic before it raises, the emit-then-raise ordering the legacy's send-before-abend sequence
 * mandates is asserted there by reading the captured log at the moment the raise surfaces.
 *
 * <h2>Independent oracle</h2>
 *
 * <p>No expected value in this class is produced by a production artefact. Every folded string, every
 * operator message and every fixed-width padding is a literal or a {@code " ".repeat(n)} constant declared
 * here, so a defect in the artefact under test cannot make its own assertion agree with it. That rule is
 * strictest for the fold: the expected folded text is a declared constant, never the output of the module's
 * fold primitive and never the output of the locale-aware library method, which appears nowhere in this
 * file in either of its two forms.
 *
 * <h2>Paragraph traceability</h2>
 *
 * <p>{@code app/cbl/COCRDUPC.cbl} contributes <b>45</b> paragraph units, being its
 * {@code PROCEDURE DIVISION} paragraphs, and 45 is the figure the action plan records and the
 * traceability matrix carries rows for. Its three {@code IDENTIFICATION DIVISION} entries -
 * {@code PROGRAM-ID} at line 23, {@code DATE-WRITTEN} at line 25 and {@code DATE-COMPILED} at line 27 -
 * are program metadata rather than procedure units, and <strong>the matrix owes them no row</strong>:
 * publishing 48 from here would redefine the frozen 544-row model from inside a test. They are still
 * asserted, as the member and transaction names the screen header publishes. The unit-to-test inventory
 * is held once in {@code docs/traceability-matrix.md}.
 */
@DisplayName("CardUpdateService - card-update transaction CCUP, app/cbl/COCRDUPC.cbl")
class CardUpdateServiceTest {

    private static final String CARD_NUMBER = "4111111111111111";

    private static final String ACCOUNT_ID = "00000000001";

    /**
     * The verification code the suite round-trips.
     *
     * <p>Chosen for its leading zeros: a translation that parsed it into a number would round-trip it as
     * {@code "7"}, and one that logged it would leak it.
     */
    private static final String VERIFICATION_CODE = "007";

    private static final String EXPIRATION_DATE = "2028-07-31";

    /** The stored embossed name, already upper case, as row 0 of the ASCII card fixture resembles. */
    private static final String STORED_NAME_FOLDED = "ANIYA VON";

    /** The same name as an operator would type it: different in letter case only. */
    private static final String STORED_NAME_LOWER = "aniya von";

    // ==============================================================================================
    // Independent oracle: every expected value below is declared here, never derived from production
    // ==============================================================================================

    /**
     * The charset the module declares, which is the one a non-ASCII fold probe must be measured in.
     *
     * <p>A probe that is representable in seven bits is measured in {@link StandardCharsets#US_ASCII}
     * instead, so that a width assertion is made on <em>encoded bytes</em> rather than on a character count.
     * The two differ for exactly the values this suite probes with, which is why the distinction is drawn.
     */
    private static final Charset MODULE_CHARSET = StandardCharsets.UTF_8;

    /**
     * The fold probe: a name carrying a lower-case Latin letter with a diacritic and a lower-case letter
     * whose locale-aware upper-casing is two characters rather than one.
     *
     * <p>Neither probe character appears in the legacy's twenty-six-character source table, so the table
     * leaves both untouched. The locale-aware library method transforms both, and for the second it also
     * <em>changes the string's length</em>, which would additionally break the fifty-character field.
     */
    private static final String FOLD_PROBE = "Ren\u00e9e Wei\u00df";

    /**
     * The declared expectation for {@link #FOLD_PROBE}: the twenty-four positions holding {@code a} to
     * {@code z} are folded and the two probe characters are carried through unchanged.
     *
     * <p>A literal, deliberately. Producing it by calling the module's fold primitive, or the library
     * method, would make this assertion agree with whatever the implementation happens to do.
     */
    private static final String FOLD_PROBE_EXPECTED = "REN\u00e9E WEI\u00df";

    /** An all-ASCII probe, so the discriminating half of the fold assertion is measured in seven bits. */
    private static final String ASCII_FOLD_PROBE = "mary ann";

    private static final String ASCII_FOLD_PROBE_EXPECTED = "MARY ANN";

    private static final int COMMON_MESSAGE_BYTE_WIDTH = 50;

    /**
     * The declared width of the summary-message field the file-error text is moved into.
     *
     * <p>The assembled structure is eighty characters wide and the destination field is seventy-five, so the
     * trailing five-character filler never survives the move. Declared here rather than read back from the
     * artefact under test, and asserted on encoded bytes.
     */
    private static final int RETURN_MESSAGE_BYTE_WIDTH = 75;

    private static final String INVALID_KEY_VISIBLE_TEXT = "Invalid key pressed. Please see below...";

    /** The padding the invalid-key message carries: ten positions, and it is never trimmed. */
    private static final String INVALID_KEY_PADDING = " ".repeat(10);

    /** The common invalid-key message at its contractual width, declared rather than read back. */
    private static final String EXPECTED_INVALID_KEY_MESSAGE =
            INVALID_KEY_VISIBLE_TEXT + INVALID_KEY_PADDING;

    /**
     * The concurrent-change text, verbatim. <b>"some one" is two words</b> in the legacy and stays two
     * words here.
     */
    private static final String EXPECTED_MSG_RECORD_CHANGED =
            "Record changed by some one else. Please review";

    private static final String EXPECTED_MSG_UPDATE_FAILED = "Update of record failed";

    /** The account lock-failure text, verbatim: the arm's default for every entity but the customer. */
    private static final String EXPECTED_MSG_COULD_NOT_LOCK_ACCOUNT =
            "Could not lock account record for update";

    /** The customer lock-failure text, verbatim: the one text of the four that varies by entity. */
    private static final String EXPECTED_MSG_COULD_NOT_LOCK_CUSTOMER =
            "Could not lock customer record for update";

    /** This member's own lock-failure text, which is one word shorter than the account program's. */
    private static final String EXPECTED_MSG_COULD_NOT_LOCK_RECORD = "Could not lock record for update";

    /** The terminal online abend code, declared rather than read back from the exception type. */
    private static final String EXPECTED_ONLINE_ABEND_CODE = "9999";

    private static final String UNRESOLVABLE_PROGRAM = "NOSUCHPG";

    /**
     * The four verified widths of the abend context, and the offsets they place each field at.
     *
     * <p>Code four, culprit eight, reason fifty and operator message seventy-two, so the assembled image is
     * a hundred and thirty-four bytes with its fields at {@code [0,4)}, {@code [4,12)}, {@code [12,62)} and
     * {@code [62,134)}. Declared here so the offsets are asserted against the contract rather than against
     * whatever the artefact happens to publish.
     */
    private static final int ABEND_CODE_BYTE_WIDTH = 4;

    private static final int ABEND_CULPRIT_BYTE_WIDTH = 8;

    private static final int ABEND_REASON_BYTE_WIDTH = 50;

    private static final int ABEND_MESSAGE_BYTE_WIDTH = 72;

    private static final int ABEND_CONTEXT_BYTE_WIDTH = ABEND_CODE_BYTE_WIDTH
            + ABEND_CULPRIT_BYTE_WIDTH + ABEND_REASON_BYTE_WIDTH + ABEND_MESSAGE_BYTE_WIDTH;

    private CardRepository cardRepository;

    private AbendService abendService;

    /**
     * The write primitive, held rather than inlined so a test can assert the rollback mark.
     *
     * <p>A failed rewrite leaves the transaction unable to continue, and the only observable evidence that
     * the turn recognised that - rather than committing a half-written row - is the mark on this
     * collaborator.
     */
    private CardUpdateService service;

    private Logger serviceLogger;

    /**
     * The dispatch graph's logger, captured alongside the service's own.
     *
     * <p>The one abend reachable through this screen's public surface is raised by the graph, and the
     * emit-then-raise ordering the legacy mandates is only observable if the record the graph emits before
     * raising is captured. Attaching the same appender to both loggers is also what lets the
     * verification-code assertion cover every record the turn produced rather than only the ones this class
     * emitted.
     */
    private Logger navigationLogger;

    /** The centralised abend category, which carries the online abend record. */
    private Logger abendLogger;

    /** That category's level before this class pinned it, restored in teardown. */
    private Level originalAbendLevel;

    private ListAppender<ILoggingEvent> capturedLog;

    @BeforeEach
    void setUp() {
        this.cardRepository = Mockito.mock(CardRepository.class);
        this.abendService = Mockito.mock(AbendService.class);
        this.service = new CardUpdateService(this.cardRepository, this.abendService,
                new MessageCatalogService(), new NavigationService(), new OnlineTransactionBoundary(),
                Clock.fixed(Instant.parse("2024-03-14T15:09:26Z"), ZoneOffset.UTC));

        this.capturedLog = new ListAppender<>();
        this.capturedLog.start();
        this.serviceLogger = (Logger) LoggerFactory.getLogger(CardUpdateService.class);
        this.serviceLogger.addAppender(this.capturedLog);
        this.serviceLogger.setLevel(Level.TRACE);
        this.navigationLogger = (Logger) LoggerFactory.getLogger(NavigationService.class);
        this.navigationLogger.addAppender(this.capturedLog);
        this.navigationLogger.setLevel(Level.TRACE);
        // The abend this class reaches - an unresolvable navigation target - now records through the
        // centralised online abend diagnostic, which writes under the abend category rather than under the
        // navigating service's own. Captured by the same appender so the emit-then-raise assertion below
        // keeps reading the record that actually carries the abend. See docs/decision-log.md entry DL-312.
        this.abendLogger = (Logger) LoggerFactory.getLogger(AbendService.class);
        this.originalAbendLevel = this.abendLogger.getLevel();
        this.abendLogger.addAppender(this.capturedLog);
        this.abendLogger.setLevel(Level.TRACE);
    }

    @AfterEach
    void tearDown() {
        this.serviceLogger.detachAppender(this.capturedLog);
        this.navigationLogger.detachAppender(this.capturedLog);
        this.abendLogger.detachAppender(this.capturedLog);
        // The level is put back as well as the appender. This category is shared with every other suite
        // that reads an abend record, so a pinned level left behind here decides what a later suite sees -
        // which is how a class asserting an INHERITED level fails for a reason that has nothing to do with
        // its subject. The two categories above belong to this class alone and need no such care.
        this.abendLogger.setLevel(this.originalAbendLevel);
        this.capturedLog.stop();
    }

    /**
     * A stored card row, built through the shared fixture factory so the hundred-and-fifty-byte layout is
     * described in one place.
     *
     * <p>The name is supplied through the verbatim setter rather than the folding one: a fixture that folded
     * its own input would be deriving an expected value from a fold implementation, which is exactly what
     * the fold assertions exist to test independently.
     *
     * @param embossedName the embossed name the row holds
     * @return a fresh row, never {@code null}
     */
    private static Card storedCard(final String embossedName) {
        return TestDataFactory.card()
                .cardNumber(CARD_NUMBER)
                .accountId(ACCOUNT_ID)
                .verificationCode(VERIFICATION_CODE)
                .embossedName(embossedName)
                .expirationDate(EXPIRATION_DATE)
                .activeStatus("Y")
                .build();
    }

    /**
     * The width of a value in encoded bytes, which is the only measure a fixed-width field contract can be
     * asserted on.
     *
     * <p>A character count is not that measure: the two disagree for every value carrying a character
     * outside the seven-bit range, and disagreeing there is the whole point of the fold probes.
     *
     * @param value the value to measure; must not be {@code null}
     * @param charset the encoding to measure in
     * @return the encoded length in bytes
     */
    private static int encodedByteWidth(final String value, final Charset charset) {
        return value.getBytes(charset).length;
    }

    private static CardUpdateService.CarriedCardImage carriedImage(final String embossedName) {
        return new CardUpdateService.CarriedCardImage(ACCOUNT_ID, CARD_NUMBER, VERIFICATION_CODE,
                embossedName, "2028", "07", "31", "Y");
    }

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

    private String renderedLog() {
        final StringBuilder rendered = new StringBuilder();
        for (final ILoggingEvent event : this.capturedLog.list) {
            rendered.append(event.getFormattedMessage()).append('\n');
        }
        return rendered.toString();
    }

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

        /**
         * The presented and carried value is folded; the written value is the submitted text verbatim.
         *
         * <p><strong>Which of the two is folded is read off the source, not assumed.</strong> The two
         * {@code CONVERTING} operations at lines 1356 to 1358 and 1499 to 1501 both act on the card record
         * area the read filled - the working-storage copy - and the capture at line 1360 and the comparison
         * at lines 1503 to 1508 both read that folded copy. The rewrite at line 1466 moves a
         * <em>different</em> field: the new-value group the receive paragraph filled from the transmitted
         * screen at lines 607 to 612, which no {@code CONVERTING} operation ever touches. So the store is
         * verbatim and the presentation is folded, and asserting the store folded would assert a byte the
         * legacy never writes.
         *
         * <p>That is coherent rather than accidental, and the coherence is the contract: because the
         * comparison folds both of its operands, a difference that is only one of letter case is never
         * detected as a change and so is never written at all. A lower-case name therefore only ever reaches
         * the file when some other field changed with it.
         */
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

            // The capture at line 1360 reads the field the fold at line 1357 has already rewritten, so both
            // the carried image and the projection carry the DECLARED folded literal.
            assertThat(fetched.carriedImage().embossedName()).isEqualTo(STORED_NAME_FOLDED);
            assertThat(fetched.card()).isNotNull();
            assertThat(fetched.card().embossedName()).isEqualTo(STORED_NAME_FOLDED);
            assertThat(fetched.changeAction())
                    .isEqualTo(CardUpdateService.ChangeAction.SHOW_DETAILS);

            // The stored row is NOT mutated by the fold: the source folds a working-storage copy filled
            // by READ ... INTO, and the rewrite writes a separately built image.
            assertThat(stored.getCardEmbossedName()).isEqualTo(STORED_NAME_LOWER);

            // And the write path stores the submitted text verbatim, per line 1466. Captured rather than
            // inferred, and compared against a declared literal.
            final Card storedForWrite = storedCard(STORED_NAME_FOLDED);
            Mockito.when(CardUpdateServiceTest.this.cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(storedForWrite));
            Mockito.when(CardUpdateServiceTest.this.cardRepository.saveAndFlush(Mockito.any()))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            CardUpdateServiceTest.this.service.processCardUpdate(
                    confirmingTurn("DFHPF5", ASCII_FOLD_PROBE, "N", carriedImage(STORED_NAME_FOLDED)));

            final ArgumentCaptor<Card> written = ArgumentCaptor.forClass(Card.class);
            Mockito.verify(CardUpdateServiceTest.this.cardRepository, Mockito.times(1))
                    .saveAndFlush(written.capture());
            assertThat(written.getValue().getCardEmbossedName())
                    .as("line 1466 moves the new-value field, which no CONVERTING operation touches")
                    .isEqualTo(ASCII_FOLD_PROBE)
                    .isNotEqualTo(ASCII_FOLD_PROBE_EXPECTED);
            assertThat(SensitiveValues.fingerprint(written.getValue().getCardCvvCd()))
                    .isEqualTo(SensitiveValues.fingerprint(VERIFICATION_CODE));
            assertThat(SensitiveValues.fingerprint(written.getValue().getCardNum()))
                    .isEqualTo(SensitiveValues.fingerprint(CARD_NUMBER));
        }

        @Test
        @DisplayName("(c) the fold is a 26-character ASCII table: a diacritic-bearing letter and a "
                + "length-changing letter are both left UNCHANGED, byte for byte")
        void foldIsAnAsciiTableAndNotALocaleOperation() {
            // Both probe characters are transformed by Java's Unicode-aware upper-casing and both are
            // absent from the 26-character legacy source table, so the table carries them through
            // untouched. The second probe also CHANGES LENGTH under that method, which would corrupt a
            // value written back into a fixed fifty-byte field on top of changing its content.
            final Card stored = storedCard(FOLD_PROBE);
            Mockito.when(CardUpdateServiceTest.this.cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(stored));

            final ScreenNavigationState fromCardList = new ScreenNavigationState("CCLI", "COCRDLIC", null, null,
                    "USER0001", "U", ScreenNavigationState.ProgramContext.ENTER, "000000001", "RENEE", null,
                    "WEISS", ACCOUNT_ID, "Y", CARD_NUMBER, "CCRDSLA", "COCRDLI");
            final CardUpdateService.CardUpdateResult fetched = CardUpdateServiceTest.this.service
                    .processCardUpdate(new CardUpdateService.CardUpdateScreenInput(null, null, null,
                            null, null, null, null, "DFHENTER", fromCardList, null, null));

            final String presented = fetched.carriedImage().embossedName();

            assertThat(presented)
                    .as("a character absent from the twenty-six-character source table is carried through")
                    .isEqualTo(FOLD_PROBE_EXPECTED);
            assertThat(presented)
                    .as("the diacritic-bearing letter survives at its own code point")
                    .contains("\u00e9")
                    .as("the length-changing letter survives at its own code point")
                    .contains("\u00df");

            // Width: measured on ENCODED BYTES in the charset the module declares, never on a character
            // count. This is the half of the assertion that catches the fixed-width defect specifically.
            assertThat(encodedByteWidth(presented, MODULE_CHARSET))
                    .as("the fold is width preserving in encoded bytes")
                    .isEqualTo(encodedByteWidth(FOLD_PROBE, MODULE_CHARSET));

            // The row itself is untouched: the source folds the working-storage copy the read filled.
            assertThat(stored.getCardEmbossedName()).isEqualTo(FOLD_PROBE);
        }

        @Test
        @DisplayName("(c, companion) the same fold DOES transform every plain lower-case letter, so the "
                + "unchanged-probe assertion is discriminating rather than vacuous")
        void foldStillTransformsPlainLowerCaseLetters() {
            final Card stored = storedCard(ASCII_FOLD_PROBE);
            Mockito.when(CardUpdateServiceTest.this.cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(stored));

            final ScreenNavigationState fromCardList = new ScreenNavigationState("CCLI", "COCRDLIC", null, null,
                    "USER0001", "U", ScreenNavigationState.ProgramContext.ENTER, "000000001", "MARY", null,
                    "ANN", ACCOUNT_ID, "Y", CARD_NUMBER, "CCRDSLA", "COCRDLI");
            final CardUpdateService.CardUpdateResult fetched = CardUpdateServiceTest.this.service
                    .processCardUpdate(new CardUpdateService.CardUpdateScreenInput(null, null, null,
                            null, null, null, null, "DFHENTER", fromCardList, null, null));

            final String presented = fetched.carriedImage().embossedName();

            // Every one of the twenty-six table positions is exercised by the two words, and the embedded
            // space - which is not in the table either - is carried through in place.
            assertThat(presented)
                    .as("a value of plain lower-case letters is folded, so the probe test cannot pass "
                            + "merely because the fold does nothing")
                    .isEqualTo(ASCII_FOLD_PROBE_EXPECTED)
                    .isNotEqualTo(ASCII_FOLD_PROBE);
            assertThat(encodedByteWidth(presented, StandardCharsets.US_ASCII))
                    .isEqualTo(encodedByteWidth(ASCII_FOLD_PROBE, StandardCharsets.US_ASCII));
            assertThat(stored.getCardEmbossedName()).isEqualTo(ASCII_FOLD_PROBE);
        }
    }

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

    @Nested
    @DisplayName("the write path, line 1420, and the backward jump at line 1518")
    class WritePath {

        @Test
        @DisplayName("(e) a row-version conflict is reported on the screen the operator is holding, is "
                + "NOT raised past it, and NEVER abends")
        void versionConflictIsReportedOnTheScreenAndNeverAbends() {
            // COCRDUPC lines 1487 to 1490 answer any non-normal rewrite response by raising the
            // screen-level locked-but-update-failed state and nothing else: the paragraph falls through
            // its own exit and the turn composes a normal screen response. A raised conflict would
            // replace that screen - and the keyed changes on it - with a transport-level error the
            // legacy has no way to produce.
            Mockito.when(CardUpdateServiceTest.this.cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(storedCard(STORED_NAME_FOLDED)));
            Mockito.when(CardUpdateServiceTest.this.cardRepository.saveAndFlush(Mockito.any()))
                    .thenThrow(new OptimisticLockingFailureException("row version disagreed"));

            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(confirmingTurn("DFHPF5", "MARY ANN", "N",
                            carriedImage(STORED_NAME_FOLDED)));

            assertThat(result.writeOutcome())
                    .isEqualTo(CardUpdateService.WriteOutcome.UPDATE_FAILED_AFTER_LOCK);
            assertThat(result.changeAction())
                    .as("the state machine reads the outcome and settles on the failed-after-lock state, "
                            + "which is what composes the notice")
                    .isEqualTo(CardUpdateService.ChangeAction.CHANGES_OKAYED_BUT_FAILED);
            assertThat(result.message())
                    .as("the verbatim write-failure text, declared here rather than read back")
                    .isEqualTo(EXPECTED_MSG_UPDATE_FAILED);
            assertThat(result.updateCommitted()).isFalse();

            assertThat(result.writeOutcome().conflictKind())
                    .isEqualTo(OptimisticLockConflictException.ConflictKind.UPDATE_FAILED_AFTER_LOCK);
            assertThatExceptionOfType(OptimisticLockConflictException.class)
                    .isThrownBy(() -> {
                        throw new OptimisticLockConflictException(
                                result.writeOutcome().conflictKind(), "Card", CARD_NUMBER);
                    })
                    .withMessage(EXPECTED_MSG_UPDATE_FAILED)
                    .satisfies(conflict -> {
                        assertThat(conflict.conflictKind()).isEqualTo(
                                OptimisticLockConflictException.ConflictKind.UPDATE_FAILED_AFTER_LOCK);
                        assertThat(conflict.entityName()).isEqualTo("Card");
                    });

            // The rewrite was attempted exactly once. A retry would show as a second interaction, and the
            // count - not elapsed time - is what proves there was none.
            Mockito.verify(CardUpdateServiceTest.this.cardRepository, Mockito.times(1))
                    .saveAndFlush(Mockito.any());
            Mockito.verifyNoInteractions(CardUpdateServiceTest.this.abendService);
        }

        @Test
        @DisplayName("(e, contract) the shared conflict exception publishes exactly the four verbatim "
                + "legacy texts and refuses any wording an operator has never seen")
        void theFourConflictTextsAreVerbatimAndClosed() {
            // Byte-for-byte against literals declared in this class. "some one" is TWO WORDS.
            assertThat(OptimisticLockConflictException.ConflictKind.RECORD_CHANGED_BEFORE_UPDATE
                    .defaultMessage()).isEqualTo(EXPECTED_MSG_RECORD_CHANGED);
            assertThat(EXPECTED_MSG_RECORD_CHANGED).contains("some one else");
            assertThat(OptimisticLockConflictException.ConflictKind.UPDATE_FAILED_AFTER_LOCK
                    .defaultMessage()).isEqualTo(EXPECTED_MSG_UPDATE_FAILED);
            assertThat(OptimisticLockConflictException.ConflictKind.LOCK_NOT_ACQUIRED
                    .defaultMessage()).isEqualTo(EXPECTED_MSG_COULD_NOT_LOCK_ACCOUNT);
            assertThat(OptimisticLockConflictException.ConflictKind.LOCK_NOT_ACQUIRED
                    .defaultMessage("Customer")).isEqualTo(EXPECTED_MSG_COULD_NOT_LOCK_CUSTOMER);
            assertThat(OptimisticLockConflictException.ConflictKind.LOCK_NOT_ACQUIRED
                    .defaultMessage("Card")).isEqualTo(EXPECTED_MSG_COULD_NOT_LOCK_ACCOUNT);

            // Three arms, and the three write outcomes of this member map onto them one for one.
            assertThat(OptimisticLockConflictException.ConflictKind.values()).hasSize(3);

            // Wording cannot be composed at a call site, so a screen cannot drift from the estate's text.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new OptimisticLockConflictException(
                            OptimisticLockConflictException.ConflictKind.UPDATE_FAILED_AFTER_LOCK,
                            "Card", CARD_NUMBER, "Update of the record failed", null));
        }

        @Test
        @DisplayName("(f) the backward jump is bounded by the source's own condition, with no delay: "
                + "the record is read exactly once and nothing is written")
        void backwardJumpIsBoundedAndWritesNothing() {
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
            assertThat(result.message()).isEqualTo(EXPECTED_MSG_RECORD_CHANGED);
            assertThat(result.writeOutcome().conflictKind())
                    .isEqualTo(
                            OptimisticLockConflictException.ConflictKind.RECORD_CHANGED_BEFORE_UPDATE);
            // Lines 997 to 998 return the screen to the display state, so the conflict is recoverable.
            assertThat(result.changeAction())
                    .isEqualTo(CardUpdateService.ChangeAction.SHOW_DETAILS);
            // Lines 1512 to 1517 refresh the carried image with what the record now holds. That refresh is
            // the loop's own progress condition: a further pass would compare against the refreshed image
            // and find it agrees, so the source bounds its own backward jump.
            assertThat(result.carriedImage().activeStatus()).isEqualTo("N");

            // The loop is bounded and it TERMINATED: exactly one read, and nothing written. Counting
            // interactions rather than measuring time is the assertion that catches an unbounded or a
            // delayed retry - and there is no delay to measure, because the translation introduces none.
            Mockito.verify(CardUpdateServiceTest.this.cardRepository, Mockito.times(1))
                    .findById(CARD_NUMBER);
            Mockito.verify(CardUpdateServiceTest.this.cardRepository, Mockito.never())
                    .saveAndFlush(Mockito.any());
            Mockito.verifyNoMoreInteractions(CardUpdateServiceTest.this.cardRepository);
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

            assertThat(result.message()).isEqualTo(EXPECTED_MSG_COULD_NOT_LOCK_RECORD);
            assertThat(result.message())
                    .as("this member's own text names no entity; the account program's names one")
                    .isNotEqualTo(EXPECTED_MSG_COULD_NOT_LOCK_ACCOUNT)
                    .isNotEqualTo(EXPECTED_MSG_COULD_NOT_LOCK_CUSTOMER);
            assertThat(result.writeOutcome())
                    .isEqualTo(CardUpdateService.WriteOutcome.LOCK_NOT_ACQUIRED);
            assertThat(result.writeOutcome().conflictKind())
                    .isEqualTo(OptimisticLockConflictException.ConflictKind.LOCK_NOT_ACQUIRED);
            assertThat(result.changeAction())
                    .isEqualTo(CardUpdateService.ChangeAction.CHANGES_OKAYED_LOCK_ERROR);
            assertThat(result.infoMessage()).isEqualTo("Changes unsuccessful. Please try again");
            // No row was locked, so no rewrite was attempted and the loop made exactly one pass.
            Mockito.verify(CardUpdateServiceTest.this.cardRepository, Mockito.times(1))
                    .findById(CARD_NUMBER);
            Mockito.verify(CardUpdateServiceTest.this.cardRepository, Mockito.never())
                    .saveAndFlush(Mockito.any());
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

            assertThat(result.message()).isEqualTo(EXPECTED_MSG_UPDATE_FAILED);
            assertThat(result.writeOutcome())
                    .isEqualTo(CardUpdateService.WriteOutcome.UPDATE_FAILED_AFTER_LOCK);
            assertThat(result.changeAction())
                    .isEqualTo(CardUpdateService.ChangeAction.CHANGES_OKAYED_BUT_FAILED);
            // Attempted once and not retried: a write failure is answered with a screen, not another write.
            Mockito.verify(CardUpdateServiceTest.this.cardRepository, Mockito.times(1))
                    .saveAndFlush(Mockito.any());
            Mockito.verifyNoInteractions(CardUpdateServiceTest.this.abendService);
        }

        @Test
        @DisplayName("a completed rewrite is attempted exactly once and commits")
        void aCompletedRewriteIsAttemptedExactlyOnce() {
            Mockito.when(CardUpdateServiceTest.this.cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(storedCard(STORED_NAME_FOLDED)));
            Mockito.when(CardUpdateServiceTest.this.cardRepository.saveAndFlush(Mockito.any()))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(confirmingTurn("DFHPF5", "MARY ANN", "N",
                            carriedImage(STORED_NAME_FOLDED)));

            assertThat(result.updateCommitted()).isTrue();
            assertThat(result.writeOutcome()).isEqualTo(CardUpdateService.WriteOutcome.COMMITTED);
            // One read for update and one rewrite. The bound is the source's own, and it is proven by
            // interaction counts rather than by any elapsed-time or timeout construct.
            Mockito.verify(CardUpdateServiceTest.this.cardRepository, Mockito.times(1))
                    .findById(CARD_NUMBER);
            Mockito.verify(CardUpdateServiceTest.this.cardRepository, Mockito.times(1))
                    .saveAndFlush(Mockito.any());
            Mockito.verifyNoMoreInteractions(CardUpdateServiceTest.this.cardRepository);
            Mockito.verifyNoInteractions(CardUpdateServiceTest.this.abendService);
        }
    }

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
                            carriedImage(STORED_NAME_FOLDED)));

            // The write path sources the code from a field the member never writes, so the stored value
            // is preserved rather than blanked. It is still the three-character STRING and is not
            // normalised: a translation that had parsed it into a number would round-trip it as "7".
            final ArgumentCaptor<Card> written = ArgumentCaptor.forClass(Card.class);
            Mockito.verify(CardUpdateServiceTest.this.cardRepository).saveAndFlush(written.capture());
            assertThat(SensitiveValues.fingerprint(written.getValue().getCardCvvCd())).isEqualTo(SensitiveValues.fingerprint(VERIFICATION_CODE));
            assertThat(encodedByteWidth(written.getValue().getCardCvvCd(), StandardCharsets.US_ASCII))
                    .isEqualTo(3);

            // The carried image still carries it, because the comparison at line 1503 needs it.
            assertThat(SensitiveValues.fingerprint(result.carriedImage().verificationCode()))
                    .isEqualTo(SensitiveValues.fingerprint(VERIFICATION_CODE));
            // And nothing about it reaches a log, on this path or any other. The appender is attached to
            // both this member's logger and the dispatch graph's, so this covers every record the turn
            // produced rather than only the ones this class emitted.
            assertThat(CardUpdateServiceTest.this.capturedLog.list)
                    .as("no captured event mentions the verification code")
                    .isNotEmpty()
                    .noneMatch(event -> event.getFormattedMessage().contains(VERIFICATION_CODE));
            // Asserted through a predicate: doesNotContain prints the needle and the haystack, so the
            // assertion proving the verification code is not logged would have logged it itself.
            assertThat(SensitiveValues.absentFrom(CardUpdateServiceTest.this.renderedLog(), VERIFICATION_CODE)).isTrue();
            assertThat(SensitiveValues.absentFrom(result.carriedImage().toString(), VERIFICATION_CODE)).isTrue();
            assertThat(result.card()).isNotNull();
            assertThat(SensitiveValues.absentFrom(result.card().toString(), CARD_NUMBER)).isTrue();
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
            assertThat(SensitiveValues.fingerprint(projection.cardNumber())).isEqualTo(SensitiveValues.fingerprint(CARD_NUMBER));
            assertThat(SensitiveValues.absentFrom(projection.toString(), VERIFICATION_CODE)).isTrue();
            assertThat(SensitiveValues.absentFrom(projection.toString(), CARD_NUMBER)).isTrue();
            assertThatNullPointerException()
                    .isThrownBy(() -> CardUpdateService.CardProjection.of(null, null));
        }
    }

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
                    .as("the upper key resolves to the SAME action constant as the lower one, so the "
                            + "seventeenth key is not a distinct action from the fifth")
                    .isEqualTo(viaFifth.workArea().keyAction())
                    .isEqualTo(KeyAction.PFK05);
            assertThat(viaSeventeenth.updateCommitted()).isTrue();
            assertThat(viaFifth.updateCommitted()).isTrue();
            // The fold collapses the twenty-eight legacy identifiers onto sixteen outcomes, so the action
            // enum declares sixteen constants and no unknown member.
            assertThat(KeyAction.values()).hasSize(16);
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
            // Content: the declared fifty-character value, taken from the catalog UNTRIMMED. The trailing
            // padding is part of the contract because the legacy field it is moved into is fixed width.
            assertThat(result.message()).isEqualTo(EXPECTED_INVALID_KEY_MESSAGE);
            assertThat(result.message()).startsWith(INVALID_KEY_VISIBLE_TEXT);
            assertThat(result.message())
                    .as("the padding survives; nothing trims a fixed-width field")
                    .endsWith(INVALID_KEY_PADDING);
            // Width: measured on ENCODED BYTES, never on a character count.
            assertThat(encodedByteWidth(result.message(), StandardCharsets.US_ASCII))
                    .isEqualTo(COMMON_MESSAGE_BYTE_WIDTH);
            assertThat(result.route()).isEqualTo(NavigationService.Route.CARD_UPDATE);
            assertThat(result.reArmedTransactionId()).isEqualTo("CCUP");
            // Lines 422 to 424 coerce every key that is not permitted at this point to the enter key,
            // and an identifier that did not decode leaves no key at all, so it is not permitted. The
            // decode failure is therefore reported on its own flag, never by the decoded key.
            assertThat(result.workArea().keyAction()).isEqualTo(KeyAction.ENTER);
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

            assertThat(result.workArea().keyAction()).isEqualTo(KeyAction.ENTER);
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

    @Nested
    @DisplayName("dispatch, the filter edits, and the abend contract")
    class DispatchAndAbend {

        @Test
        @DisplayName("(i) the exit arm propagates the dispatch graph's abend when the nominated program "
                + "cannot be resolved, and nothing is written")
        void exitArmPropagatesAbend() {
            final ScreenNavigationState unresolvable = new ScreenNavigationState("XXXX",
                    UNRESOLVABLE_PROGRAM, null, null,
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
                            .findFirstByCardAcctIdOrderByCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.empty());

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
                    .findFirstByCardAcctIdOrderByCardNumAsc(ACCOUNT_ID);
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
            assertThat(SensitiveValues.fingerprint(result.navigationContext().cardNumber()))
                    .isEqualTo(SensitiveValues.fingerprint("0000000000000000"));
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
            assertThatNullPointerException().isThrownBy(() -> new CardUpdateService(null,
                    CardUpdateServiceTest.this.abendService, new MessageCatalogService(),
                    new NavigationService(), new OnlineTransactionBoundary(), clock));
            assertThatNullPointerException().isThrownBy(() -> new CardUpdateService(
                    CardUpdateServiceTest.this.cardRepository, null, new MessageCatalogService(),
                    new NavigationService(), new OnlineTransactionBoundary(), clock));
            assertThatNullPointerException().isThrownBy(() -> new CardUpdateService(
                    CardUpdateServiceTest.this.cardRepository,
                    CardUpdateServiceTest.this.abendService, null, new NavigationService(),
                    new OnlineTransactionBoundary(), clock));
            assertThatNullPointerException().isThrownBy(() -> new CardUpdateService(
                    CardUpdateServiceTest.this.cardRepository,
                    CardUpdateServiceTest.this.abendService, new MessageCatalogService(), null,
                    new OnlineTransactionBoundary(), clock));
            assertThatNullPointerException().isThrownBy(() -> new CardUpdateService(
                    CardUpdateServiceTest.this.cardRepository,
                    CardUpdateServiceTest.this.abendService, new MessageCatalogService(),
                    new NavigationService(), null, clock));
            assertThatNullPointerException().isThrownBy(() -> new CardUpdateService(
                    CardUpdateServiceTest.this.cardRepository,
                    CardUpdateServiceTest.this.abendService, new MessageCatalogService(),
                    new NavigationService(), new OnlineTransactionBoundary(), null));
        }
    }

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

    }

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
            assertThat(encodedByteWidth(result.message(), StandardCharsets.US_ASCII))
                    .isEqualTo(RETURN_MESSAGE_BYTE_WIDTH);
            assertThat(result.message()).startsWith("File Error: READ");
            assertThat(result.message()).contains(" on CARDDAT");
            assertThat(result.message()).contains("returned RESP");
            assertThat(result.errorFlag()).isTrue();
            assertThat(result.changeAction())
                    .isEqualTo(CardUpdateService.ChangeAction.DETAILS_NOT_FETCHED);
            assertThat(result.card()).isNull();

            assertThat(renderedLog()).contains("Card file read failed");
            assertThat(renderedLog()).contains("resource=CARDDAT");
            assertThat(SensitiveValues.absentFrom(renderedLog(), VERIFICATION_CODE)).isTrue();
            assertThat(SensitiveValues.absentFrom(renderedLog(), CARD_NUMBER)).isTrue();
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
                            .findFirstByCardAcctIdOrderByCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.of(storedCard(STORED_NAME_FOLDED)));

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
                    .findFirstByCardAcctIdOrderByCardNumAsc(ACCOUNT_ID);
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
                            .findFirstByCardAcctIdOrderByCardNumAsc(ACCOUNT_ID))
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

            assertThat(encodedByteWidth(result.message(), StandardCharsets.US_ASCII))
                    .isEqualTo(RETURN_MESSAGE_BYTE_WIDTH);
            assertThat(result.message()).contains(" on CARDAIX");
            assertThat(renderedLog()).contains("resource=CARDAIX");
        }
    }

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

    /**
     * The three observable states the macro copybook's two nested conditions produce.
     *
     * <p>{@code app/cpy/CSSETATY.cpy} colours a field when its flag is not-OK <em>or</em> blank <b>and</b>
     * the program is on re-entry, and it <em>additionally</em> writes a marker when the flag is
     * specifically blank. Two nested conditions over three flag states therefore yield three outcomes and
     * not two, and the outer re-entry gate is what makes the first of them exist at all:
     *
     * <ul>
     *   <li>first submission, whatever the flag - <b>undecorated</b>, and so no field error either</li>
     *   <li>re-entry with a supplied value that failed its edit - <b>decorated without a marker</b>, which
     *       the response publishes as INVALID</li>
     *   <li>re-entry with no value supplied - <b>decorated with a marker</b>, which the response publishes
     *       as MISSING</li>
     * </ul>
     *
     * <p>Collapsing the two error states into a boolean would erase the distinction the legacy screen draws
     * between a field an operator left empty and one they filled in wrongly, which is exactly the
     * distinction the marker exists to draw.
     */
    @Nested
    @DisplayName("the two-state field-error contract, and the re-entry gate that precedes it")
    class TwoStateDecoration {

        /**
         * A submitted turn whose echoed state reports a FIRST entry rather than a re-submission.
         *
         * <p>Nothing has been fetched, so this is the state the operator's very first arrival at the screen
         * carries, and the filter values are supplied for the assertion's benefit rather than because a
         * first turn would carry any.
         *
         * @param accountId the submitted account filter
         * @param cardNumber the submitted card filter
         * @return the transmitted screen, never {@code null}
         */
        private CardUpdateService.CardUpdateScreenInput firstSubmission(final String accountId,
                final String cardNumber) {
            final ScreenNavigationState firstEntry = new ScreenNavigationState("CCUP", "COCRDUPC", null,
                    null, "USER0001", "U", ScreenNavigationState.ProgramContext.ENTER, "000000001",
                    "ANIYA", null, "VON", ACCOUNT_ID, "Y", CARD_NUMBER, "CCRDUPA", "COCRDUP");
            return new CardUpdateService.CardUpdateScreenInput(accountId, cardNumber, null, null, null,
                    null, null, "DFHENTER", firstEntry,
                    CardUpdateService.ChangeAction.DETAILS_NOT_FETCHED,
                    CardUpdateService.CarriedCardImage.empty());
        }

        /**
         * A re-submission of the same two filter values, so the pair of assertions differs in exactly one
         * variable: the state of the re-entry gate.
         *
         * @param accountId the submitted account filter
         * @param cardNumber the submitted card filter
         * @return the transmitted screen, never {@code null}
         */
        private CardUpdateService.CardUpdateScreenInput reSubmission(final String accountId,
                final String cardNumber) {
            return new CardUpdateService.CardUpdateScreenInput(accountId, cardNumber, null, null, null,
                    null, null, "DFHENTER", reEntryContext(),
                    CardUpdateService.ChangeAction.DETAILS_NOT_FETCHED,
                    CardUpdateService.CarriedCardImage.empty());
        }

        @Test
        @DisplayName("first submission: nothing is decorated, no marker is written and no field error is "
                + "published, however the fields were filled")
        void firstSubmissionLeavesAFailingFieldUndecorated() {
            // Blank filters on a first arrival, and then malformed ones: neither is decorated, because the
            // outer gate is closed for the whole of the decoration block.
            final CardUpdateService.CardUpdateResult blankOnFirstEntry =
                    CardUpdateServiceTest.this.service.processCardUpdate(firstSubmission(null, null));
            final CardUpdateService.CardUpdateResult malformedOnFirstEntry =
                    CardUpdateServiceTest.this.service.processCardUpdate(firstSubmission("123", "456"));

            for (final CardUpdateService.CardUpdateResult result :
                    List.of(blankOnFirstEntry, malformedOnFirstEntry)) {
                assertThat(result.decoration()).isNotNull();
                assertThat(result.decoration().isEmpty()).isTrue();
                assertThat(result.decoration().markedFields()).isEmpty();
                assertThat(result.fieldErrors()).isNotNull().isEmpty();
                assertThat(result.screen().accountId())
                        .as("no marker is written into an undecorated field")
                        .isNotEqualTo(CardUpdateService.BLANK_FIELD_MARKER);
                assertThat(result.screen().cardNumber())
                        .isNotEqualTo(CardUpdateService.BLANK_FIELD_MARKER);
                assertThat(result.infoMessage())
                        .isEqualTo(CardUpdateService.INFO_PROMPT_FOR_SEARCH_KEYS);
                assertThat(result.changeAction())
                        .isEqualTo(CardUpdateService.ChangeAction.DETAILS_NOT_FETCHED);
            }

            // Nothing was read either: a first arrival asks for the keys, it does not act on them.
            Mockito.verifyNoInteractions(CardUpdateServiceTest.this.cardRepository);
            Mockito.verifyNoInteractions(CardUpdateServiceTest.this.abendService);

            // And the assertion is discriminating: the SAME two values on a re-submission ARE decorated,
            // so the undecorated outcome above is the gate's doing rather than the fields being acceptable.
            final CardUpdateService.CardUpdateResult blankOnReEntry =
                    CardUpdateServiceTest.this.service.processCardUpdate(reSubmission(null, null));
            final CardUpdateService.CardUpdateResult malformedOnReEntry =
                    CardUpdateServiceTest.this.service.processCardUpdate(reSubmission("123", "456"));
            assertThat(blankOnReEntry.fieldErrors()).isNotEmpty();
            assertThat(malformedOnReEntry.fieldErrors()).isNotEmpty();
        }

        @Test
        @DisplayName("re-entry, value supplied and rejected: decorated WITHOUT a marker, published as "
                + "INVALID")
        void reEntryWithARejectedValueIsInvalidAndCarriesNoMarker() {
            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(new CardUpdateService.CardUpdateScreenInput("123", CARD_NUMBER,
                            null, null, null, null, null, "DFHENTER", reEntryContext(),
                            CardUpdateService.ChangeAction.DETAILS_NOT_FETCHED, null));

            assertThat(result.reEntry()).isTrue();
            assertThat(result.decoration().markedFields())
                    .as("a supplied value that failed its edit is coloured")
                    .isNotEmpty()
                    .extracting(FieldErrorMarks.MarkedField::flagState)
                    .containsOnly(FieldErrorMarks.FlagState.NOT_OK);
            assertThat(result.fieldErrors())
                    .isNotEmpty()
                    .allSatisfy(error -> assertThat(error.state())
                            .isEqualTo(ValidationException.FieldState.INVALID));
            // No marker: the inner condition tests the BLANK state specifically, and this one is not blank.
            assertThat(result.screen().accountId())
                    .as("the marker is written only for the blank state")
                    .isNotEqualTo(CardUpdateService.BLANK_FIELD_MARKER);
        }

        @Test
        @DisplayName("re-entry, no value supplied: decorated WITH a marker, published as MISSING")
        void reEntryWithNoValueSuppliedIsMissingAndCarriesTheMarker() {
            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(new CardUpdateService.CardUpdateScreenInput(null, null, null,
                            null, null, null, null, "DFHENTER", reEntryContext(),
                            CardUpdateService.ChangeAction.DETAILS_NOT_FETCHED, null));

            assertThat(result.reEntry()).isTrue();
            assertThat(result.decoration().markedFields())
                    .isNotEmpty()
                    .extracting(FieldErrorMarks.MarkedField::flagState)
                    .containsOnly(FieldErrorMarks.FlagState.BLANK);
            assertThat(result.fieldErrors())
                    .isNotEmpty()
                    .allSatisfy(error -> assertThat(error.state())
                            .isEqualTo(ValidationException.FieldState.MISSING));
            // The marker replaces the field's value, which is what the legacy move does.
            assertThat(result.screen().accountId()).isEqualTo(CardUpdateService.BLANK_FIELD_MARKER);
            assertThat(result.screen().cardNumber()).isEqualTo(CardUpdateService.BLANK_FIELD_MARKER);
        }

        @Test
        @DisplayName("the field state has EXACTLY TWO constants, so the marker distinction cannot be "
                + "collapsed into a boolean")
        void theFieldStateHasExactlyTwoConstants() {
            assertThat(ValidationException.FieldState.values())
                    .as("MISSING for a field not supplied, INVALID for one supplied wrongly, and nothing "
                            + "else: a field that passed its edits produces no entry at all")
                    .hasSize(2)
                    .containsExactly(ValidationException.FieldState.MISSING,
                            ValidationException.FieldState.INVALID);
            // The two decoration flag states map onto them one for one, with no third outcome.
            assertThat(FieldErrorMarks.FlagState.values()).hasSize(2);
        }

        @Test
        @DisplayName("the field-error list is never null and is UNMODIFIABLE: a mutation attempt throws")
        void theFieldErrorListIsNonNullAndUnmodifiable() {
            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(new CardUpdateService.CardUpdateScreenInput(null, null, null,
                            null, null, null, null, "DFHENTER", reEntryContext(),
                            CardUpdateService.ChangeAction.DETAILS_NOT_FETCHED, null));

            final List<ValidationException.FieldError> published = result.fieldErrors();
            assertThat(published).isNotNull().isNotEmpty();

            final ValidationException.FieldError intruder = new ValidationException.FieldError(
                    CardUpdateService.FIELD_EMBOSSED_NAME, CardUpdateService.BMS_EMBOSSED_NAME,
                    ValidationException.FieldState.INVALID, "injected");
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .as("a caller cannot append to the published detail")
                    .isThrownBy(() -> published.add(intruder));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .as("nor remove from it")
                    .isThrownBy(() -> published.remove(0));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .as("nor clear it")
                    .isThrownBy(published::clear);

            // The same guarantee on the shared exception the mapper publishes, including for a mutable
            // list handed to its constructor.
            final List<ValidationException.FieldError> mutable = new ArrayList<>(published);
            final ValidationException raised =
                    new ValidationException(CardUpdateService.MSG_NO_INPUT_RECEIVED, mutable);
            assertThat(raised.hasFieldErrors()).isTrue();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> raised.fieldErrors().add(intruder));
            assertThat(new ValidationException(CardUpdateService.MSG_NO_INPUT_RECEIVED).fieldErrors())
                    .as("an exception carrying no detail reports an empty list rather than null")
                    .isNotNull()
                    .isEmpty();
        }

        @Test
        @DisplayName("the active-status edit accepts exactly the two declared card-status codes and "
                + "publishes the source's verbatim text for anything else")
        void theStatusEditAcceptsExactlyTheTwoDeclaredCodes() {
            assertThat(CardStatus.values())
                    .as("the layout declares two states and no third")
                    .hasSize(2);
            assertThat(CardStatus.values())
                    .extracting(CardStatus::getCode)
                    .containsExactly('Y', 'N');
            assertThat(CardStatus.Y.isActive()).isTrue();
            assertThat(CardStatus.N.isActive()).isFalse();
            assertThat(CardStatus.fromCode('Q')).isEmpty();

            Mockito.when(CardUpdateServiceTest.this.cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(storedCard(STORED_NAME_FOLDED)));

            final CardUpdateService.CardUpdateResult rejected = CardUpdateServiceTest.this.service
                    .processCardUpdate(reviewingTurn(STORED_NAME_FOLDED, "Q",
                            carriedImage(STORED_NAME_FOLDED)));

            assertThat(rejected.message()).isEqualTo(CardUpdateService.MSG_STATUS_MUST_BE_YES_NO);
            assertThat(rejected.fieldErrors()).singleElement()
                    .satisfies(error -> {
                        assertThat(error.field()).isEqualTo(CardUpdateService.FIELD_ACTIVE_STATUS);
                        assertThat(error.bmsFieldId()).isEqualTo(CardUpdateService.BMS_ACTIVE_STATUS);
                        assertThat(error.state()).isEqualTo(ValidationException.FieldState.INVALID);
                    });
        }
    }

    /**
     * The boundary inputs, none of which may escape as an unhandled runtime failure.
     *
     * <p>A 3270 field the terminal did not transmit arrives as low values rather than as spaces, and an
     * omitted request component is the same state, so {@code null} is an ordinary value on this surface
     * rather than a programming error. The one input that is <em>not</em> optional is the request itself.
     */
    @Nested
    @DisplayName("null, blank and wrong-length input")
    class NullAndBoundaryInput {

        @Test
        @DisplayName("a null card number is not supplied rather than a failure, and attempts no read")
        void aNullCardNumberIsNotSupplied() {
            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(new CardUpdateService.CardUpdateScreenInput(ACCOUNT_ID, null,
                            null, null, null, null, null, "DFHENTER", reEntryContext(),
                            CardUpdateService.ChangeAction.DETAILS_NOT_FETCHED, null));

            assertThat(result).isNotNull();
            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).isEqualTo(CardUpdateService.MSG_CARD_NOT_PROVIDED);
            assertThat(result.fieldErrors()).extracting(ValidationException.FieldError::field)
                    .containsExactly(CardUpdateService.FIELD_CARD_NUMBER);
            Mockito.verifyNoInteractions(CardUpdateServiceTest.this.cardRepository);
        }

        @ParameterizedTest
        @CsvSource({"'                ', spaces fill the whole field",
                    "'411111111111111', one position short",
                    "'4111-1111-1111-1', punctuated rather than numeric",
                    "'0000000000000000', zeros fill the whole field"})
        @DisplayName("a card filter that is not sixteen digits across the whole field is refused with the "
                + "source's verbatim text, and no read is attempted")
        void aWrongLengthCardFilterIsRefused(final String submitted, final String why) {
            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(new CardUpdateService.CardUpdateScreenInput(ACCOUNT_ID, submitted,
                            null, null, null, null, null, "DFHENTER", reEntryContext(),
                            CardUpdateService.ChangeAction.DETAILS_NOT_FETCHED, null));

            assertThat(result.errorFlag()).as(why).isTrue();
            assertThat(result.message()).as(why)
                    .isIn(CardUpdateService.MSG_CARD_FILTER_SIXTEEN_DIGITS,
                            CardUpdateService.MSG_CARD_NOT_PROVIDED,
                            CardUpdateService.MSG_NO_INPUT_RECEIVED);
            Mockito.verify(CardUpdateServiceTest.this.cardRepository, Mockito.never())
                    .findById(Mockito.anyString());
        }

        @Test
        @DisplayName("a card filter LONGER than its declared width passes the class test, because that "
                + "test examines the sixteen declared positions and no more")
        void anOverLongCardFilterIsClassTestedAcrossItsDeclaredWidthOnly() {
            // The legacy field is sixteen bytes and a longer value cannot reach it, so the class test at
            // line 784 inspects exactly the declared positions. Sixteen digits followed by a seventeenth
            // therefore satisfies it, the fetch is attempted, and an absent row is the not-found outcome
            // rather than an escaping failure. Recorded here because a translation that class-tested the
            // whole string instead would refuse a value the fixed-field test accepts.
            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(new CardUpdateService.CardUpdateScreenInput(ACCOUNT_ID,
                            CARD_NUMBER + "1", null, null, null, null, null, "DFHENTER",
                            reEntryContext(), CardUpdateService.ChangeAction.DETAILS_NOT_FETCHED, null));

            assertThat(result).isNotNull();
            assertThat(result.message()).isEqualTo(CardUpdateService.MSG_NO_CARD_FOR_SEARCH);
            assertThat(result.errorFlag()).isTrue();
            assertThat(result.card()).isNull();
            Mockito.verify(CardUpdateServiceTest.this.cardRepository, Mockito.never())
                    .saveAndFlush(Mockito.any());
        }

        @Test
        @DisplayName("a null embossed name on a submitted screen is MISSING rather than a failure")
        void aNullEmbossedNameIsMissing() {
            Mockito.when(CardUpdateServiceTest.this.cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(storedCard(STORED_NAME_FOLDED)));

            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(reviewingTurn(null, "Y", carriedImage(STORED_NAME_FOLDED)));

            assertThat(result).isNotNull();
            assertThat(result.message()).isEqualTo(CardUpdateService.MSG_NAME_NOT_PROVIDED);
            assertThat(result.fieldErrors()).isNotEmpty()
                    .first()
                    .satisfies(error -> {
                        assertThat(error.field()).isEqualTo(CardUpdateService.FIELD_EMBOSSED_NAME);
                        assertThat(error.state()).isEqualTo(ValidationException.FieldState.MISSING);
                    });
            Mockito.verify(CardUpdateServiceTest.this.cardRepository, Mockito.never())
                    .saveAndFlush(Mockito.any());
        }

        @Test
        @DisplayName("a null navigation state resets the conversation and presents the empty screen")
        void aNullNavigationStateResetsTheConversation() {
            final CardUpdateService.CardUpdateResult result = CardUpdateServiceTest.this.service
                    .processCardUpdate(new CardUpdateService.CardUpdateScreenInput(null, null, null,
                            null, null, null, null, null, null, null, null));

            assertThat(result).isNotNull();
            assertThat(result.navigationContext()).isNotNull();
            assertThat(result.route()).isEqualTo(NavigationService.Route.CARD_UPDATE);
            assertThat(result.changeAction())
                    .isEqualTo(CardUpdateService.ChangeAction.DETAILS_NOT_FETCHED);
            assertThat(result.carriedImage()).isNotNull();
            assertThat(result.carriedImage().absent()).isTrue();
            assertThat(result.fieldErrors()).isNotNull().isEmpty();
            // A null attention identifier is the unmapped-key case, not a failure.
            assertThat(result.attentionKeyUnmapped()).isTrue();
            assertThat(result.workArea().keyAction()).isEqualTo(KeyAction.ENTER);
            Mockito.verifyNoInteractions(CardUpdateServiceTest.this.cardRepository);
            Mockito.verifyNoInteractions(CardUpdateServiceTest.this.abendService);
        }

        @Test
        @DisplayName("the not-found status the read reports is the shared one, and its exception declares "
                + "a no-argument constructor")
        void theNotFoundContractIsTheSharedOne() {
            assertThat(RecordNotFoundException.STATUS_RECORD_NOT_FOUND).isEqualTo("23");
            assertThat(new RecordNotFoundException()).isNotNull();
        }
    }


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

        /**
         * The abend contract of this screen: <b>emit first, then raise</b>, carrying the program-name
         * culprit and the terminal online code.
         *
         * <p>The reachable abend here is the transfer the exit arm attempts: the dispatch graph raises for
         * a nominated program it cannot resolve, exactly as the legacy transfer at lines 469 to 475 would
         * have abended on a program name the region cannot resolve. The graph emits its diagnostic on the
         * statement before the raise, with no branch between the two, so the record is <em>already
         * captured</em> at the moment the exception surfaces - which is what makes the ordering assertable
         * rather than merely documented. That ordering is the whole point of the legacy's send-before-abend
         * sequence at lines 1539 to 1552: a diagnostic emitted after the abend would arrive after the thing
         * it describes had already terminated.
         *
         * <p>This member's OWN abend paragraph at line 1531 performs the same ordering and cannot be
         * reached through the public surface, because the only arm that performs it is the catch-all for a
         * corrupt state byte and the state is a typed enum here - which is what the lock-error test above
         * pins down. Reaching it would require reflection, and the reflection budget is zero.
         */
        @Test
        @DisplayName("(i) an abend LOGS ITS DIAGNOSTIC BEFORE IT RAISES, propagates unswallowed, carries "
                + "the program-name culprit and the terminal online code, and leaks nothing sensitive")
        void anAbendLogsBeforeItRaisesAndCarriesTheTerminalCode() {
            final ScreenNavigationState unresolvableCaller = new ScreenNavigationState("XXXX",
                    UNRESOLVABLE_PROGRAM, null,
                    null, "USER0001", "U", ScreenNavigationState.ProgramContext.REENTER, "000000001",
                    "ANIYA", null, "VON", ACCOUNT_ID, "Y", CARD_NUMBER, "CCRDUPA", "COCRDUP");

            assertThatExceptionOfType(AbendException.class).isThrownBy(() ->
                    CardUpdateServiceTest.this.service.processCardUpdate(
                            new CardUpdateService.CardUpdateScreenInput(ACCOUNT_ID, CARD_NUMBER,
                                    STORED_NAME_FOLDED, "Y", "07", "2028", "31", "DFHPF3",
                                    unresolvableCaller, CardUpdateService.ChangeAction.SHOW_DETAILS,
                                    carriedImage(STORED_NAME_FOLDED))))
                    .satisfies(raised -> {
                        // The terminal online code, as a declared literal rather than read back.
                        assertThat(raised.code()).isEqualTo(EXPECTED_ONLINE_ABEND_CODE);
                        // The culprit is the PROGRAM NAME, bounded to the legacy field width.
                        assertThat(raised.culprit()).isEqualTo(UNRESOLVABLE_PROGRAM);
                        assertThat(encodedByteWidth(raised.culprit(), StandardCharsets.US_ASCII))
                                .isEqualTo(ABEND_CULPRIT_BYTE_WIDTH);
                        assertThat(raised.reason()).isNotBlank();
                        // The fixed-width context is assembled at the four verified offsets: a hundred and
                        // thirty-four bytes carrying the code, the culprit, the reason and the message.
                        final String context = raised.toFixedWidthContext();
                        assertThat(encodedByteWidth(context, StandardCharsets.US_ASCII))
                                .isEqualTo(ABEND_CONTEXT_BYTE_WIDTH);
                        assertThat(context.substring(0, ABEND_CODE_BYTE_WIDTH))
                                .isEqualTo(EXPECTED_ONLINE_ABEND_CODE);
                        assertThat(context.substring(ABEND_CODE_BYTE_WIDTH,
                                ABEND_CODE_BYTE_WIDTH + ABEND_CULPRIT_BYTE_WIDTH))
                                .isEqualTo(UNRESOLVABLE_PROGRAM);
                        assertThat(encodedByteWidth(context.substring(ABEND_CODE_BYTE_WIDTH
                                + ABEND_CULPRIT_BYTE_WIDTH, ABEND_CODE_BYTE_WIDTH
                                + ABEND_CULPRIT_BYTE_WIDTH + ABEND_REASON_BYTE_WIDTH),
                                StandardCharsets.US_ASCII)).isEqualTo(ABEND_REASON_BYTE_WIDTH);
                        assertThat(encodedByteWidth(context.substring(ABEND_CODE_BYTE_WIDTH
                                + ABEND_CULPRIT_BYTE_WIDTH + ABEND_REASON_BYTE_WIDTH),
                                StandardCharsets.US_ASCII)).isEqualTo(ABEND_MESSAGE_BYTE_WIDTH);
                    });

            // EMIT-THEN-RAISE. The diagnostic is already in the appender now that the raise has surfaced,
            // which is only possible if it was written before control left the raising statement.
            assertThat(CardUpdateServiceTest.this.capturedLog.list)
                    .as("the diagnostic was emitted before the abend was raised")
                    .isNotEmpty()
                    .anyMatch(event -> event.getLevel() == Level.ERROR
                            && event.getFormattedMessage().contains("ABENDING PROGRAM"));

            assertThat(renderedLog())
                    .doesNotContain(VERIFICATION_CODE)
                    .doesNotContain(CARD_NUMBER);
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
            assertThat(result.message()).isEqualTo(EXPECTED_MSG_RECORD_CHANGED);
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
                    .isEqualTo(KeyAction.PFK03);
        }
    }

    @Nested
    @DisplayName("paragraph traceability: the 45 units this member contributes to the matrix")
    class ParagraphTraceability {

        /** Creates the nested specification. */
        ParagraphTraceability() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("the unit count is the 45 the published matrix carries, read from the matrix rather "
                + "than restated, so the three identification entries cannot creep back into it")
        void theUnitCountIsTheOneTheMatrixCarries() {
            assertThat(TraceabilityMatrixCensus.unitsOf("COCRDUPC.cbl"))
                    .as("the figure is taken from the matrix's census subtotal, its section declaration "
                            + "and its rows, which the reader requires to agree; this suite's heading "
                            + "once published 48 by counting identification-division metadata as units")
                    .isEqualTo(45);
        }
    }
}
