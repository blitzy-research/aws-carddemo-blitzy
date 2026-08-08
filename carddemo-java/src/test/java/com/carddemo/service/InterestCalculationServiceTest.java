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
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.domain.Pageable;

import com.carddemo.domain.Account;
import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.DisclosureGroup;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.TransactionCategoryBalance;
import com.carddemo.domain.enums.FileStatus;
import com.carddemo.domain.id.DisclosureGroupId;
import com.carddemo.domain.id.TransactionCategoryBalanceId;
import com.carddemo.exception.AbendException;
import com.carddemo.exception.FileStatusException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.DisclosureGroupRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.support.SensitiveValues;
import com.carddemo.support.TestDataFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Parity tests for {@link InterestCalculationService}, the Java realisation of the batch interest
 * calculator {@code app/cbl/CBACT04C.cbl} - 652 lines, 22 procedure-division paragraphs - read as
 * read-only reference at checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream
 * release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is
 * transcribed here: widths, offsets, status codes, paragraph names and contract literals are cited as
 * metadata only.
 *
 * <h2>Why every expected value below is a hand-written literal</h2>
 *
 * <p><strong>No expected value in this class is produced by a production artefact.</strong> Not by the
 * service under test, not by {@code ZonedDecimalCodec}, not by {@code CobolStringUtils}, not by any
 * record mapper. Every interest amount was computed by hand from the source expression and written as a
 * {@code new BigDecimal("...")} literal; every padded literal is assembled from a visible prefix and an
 * explicit {@code " ".repeat(n)}. Computing an expectation with the same expression the implementation
 * uses would make the truncation assertion agree with the implementation instead of with the source,
 * which is precisely the failure mode these tests exist to catch.
 *
 * <h2>The determinism this class relies on, twice over</h2>
 *
 * <p>The timestamp comes from an injected {@link Clock#fixed}, and the identifier prefix comes from a
 * <strong>pinned run-date job parameter</strong>. Both are needed: the timestamp would otherwise move
 * with the wall clock and the identifier would otherwise move with the calendar. No ambient time source
 * is consulted anywhere in this file.
 *
 * <h2>Harness shape</h2>
 *
 * <p>A surefire unit test: no container, no application context, no batch wiring, no database, no
 * network and no filesystem. The four repositories are Mockito mocks; the abend collaborator is a spy
 * over the real instance so that the emit-then-raise ordering can be asserted rather than assumed; the
 * group commit boundary is the real pass-through instance, because it is a two-line delegation whose
 * proxying belongs to the integration tier.
 *
 * <p>Three families of import beyond the test frameworks are present, and each is here because binding
 * to the real contract requires it rather than as a convenience. The logging facade and the logback
 * capture appender are the mechanism by which the emit-then-raise ordering is observed. The data-access
 * failure types and the page request type are the collaborators' <em>own</em> declared types, so a mock
 * cannot be given a failure or a captured argument without them. The decimal, character-set, time and
 * collection types are the shapes the service's own signatures are expressed in.
 *
 * <p>Two collaborators in this file's dependency set are deliberately <strong>not</strong> imported.
 * The shared decimal codec and the shared string utilities would each turn an assertion into agreement
 * with the implementation rather than with the source, so every value they would have produced is
 * written here as a literal instead.
 *
 * <h2>Two places where this class asserts the implementation's behaviour and records a divergence</h2>
 *
 * <ol>
 *   <li>The source expresses the end-of-file control break as the {@code ELSE} arm of a test-before
 *       loop, which makes the arm unreachable, so the run's last account is never rewritten while its
 *       interest records are still written. The implementation reproduces both halves; this class
 *       asserts that the final group is accrued and written and that only key-change breaks rewrite an
 *       account. See {@code docs/decision-log.md} entry DL-207.</li>
 *   <li>The description is a bounded 24-character write into a 100-character field. The implementation
 *       carries the 24-character value and leaves the fixed-width rendering to the record mapper, so the
 *       bounded write is asserted on the production value and the residue mechanism is proved with this
 *       class's own 100-character overlay oracle.</li>
 * </ol>
 *
 * <h2>Paragraph traceability: all 22 units, and where each is exercised</h2>
 *
 * <p>{@code docs/traceability-matrix.md} carries the row-per-paragraph inventory and is owned
 * elsewhere; nothing here writes it. The mapping this class covers is:
 *
 * <table>
 *   <caption>The member's 22 procedure-division paragraphs and their covering tests</caption>
 *   <tr><th>Paragraph</th><th>Covered by</th></tr>
 *   <tr><td>{@code 0000-TCATBALF-OPEN}</td>
 *       <td rowspan="5">{@code everyOpenAndCloseParagraphIsInvokedInSourceOrder}</td></tr>
 *   <tr><td>{@code 0100-XREFFILE-OPEN}</td></tr>
 *   <tr><td>{@code 0200-DISCGRP-OPEN}</td></tr>
 *   <tr><td>{@code 0300-ACCTFILE-OPEN}</td></tr>
 *   <tr><td>{@code 0400-TRANFILE-OPEN}</td></tr>
 *   <tr><td>{@code 1000-TCATBALF-GET-NEXT}</td>
 *       <td>{@code theWholeMasterScanIsAscendingOnTheCompositeKey},
 *           {@code anUnreadableMasterIsTheErrorArm}</td></tr>
 *   <tr><td>{@code 1050-UPDATE-ACCOUNT}</td>
 *       <td>the whole control-break nest; its <strong>second, unreachable invocation site</strong> is
 *           covered by {@code theSecondUpdateSiteNeverBecomesASecondRewrite}</td></tr>
 *   <tr><td>{@code 1100-GET-ACCT-DATA}</td><td>{@code aMissingAccountAbends},
 *       {@code theKeyedReadsHappenOncePerGroup}</td></tr>
 *   <tr><td>{@code 1110-GET-XREF-DATA}</td><td>{@code anAbsentCrossReferenceIsTheNotFoundPath},
 *       {@code theCardNumberComesFromTheResolvedFirstRow}</td></tr>
 *   <tr><td>{@code 1200-GET-INTEREST-RATE}</td><td>the rate-lookup nest</td></tr>
 *   <tr><td>{@code 1200-A-GET-DEFAULT-INT-RATE}</td><td>{@code aMissFallsBackExactlyOnce},
 *       {@code aSecondMissAbends}, {@code theFallbackProbeKeyIsThePaddedTenByteLiteral}</td></tr>
 *   <tr><td>{@code 1300-COMPUTE-INTEREST}</td><td>the interest-expression nest</td></tr>
 *   <tr><td>{@code 1300-B-WRITE-TX}</td><td>the synthesized-record nest and the description nest</td></tr>
 *   <tr><td>{@code 1400-COMPUTE-FEES}</td>
 *       <td><strong>a documented non-implementation</strong>: empty in the source and genuinely
 *           invoked. {@code theFeeParagraphContributesNothingOnTheAccruingPath},
 *           {@code theFeeParagraphTouchesNoCollaborator},
 *           {@code aZeroRateSkipsBothTheComputationAndTheFeeInvocation}</td></tr>
 *   <tr><td>{@code 9000-TCATBALF-CLOSE}</td>
 *       <td rowspan="5">{@code everyOpenAndCloseParagraphIsInvokedInSourceOrder}</td></tr>
 *   <tr><td>{@code 9100-XREFFILE-CLOSE}</td></tr>
 *   <tr><td>{@code 9200-DISCGRP-CLOSE}</td></tr>
 *   <tr><td>{@code 9300-ACCTFILE-CLOSE}</td></tr>
 *   <tr><td>{@code 9400-TRANFILE-CLOSE}</td></tr>
 *   <tr><td>{@code Z-GET-DB2-FORMAT-TIMESTAMP}</td>
 *       <td>{@code theTwoTimestampsAreByteIdenticalAndCarryTheBatchForm},
 *           {@code theHundredthsFieldIsTwoDigits}</td></tr>
 *   <tr><td>{@code 9910-DISPLAY-IO-STATUS}</td><td>{@code theStatusDisplayPrecedesTheAbendCall}</td></tr>
 *   <tr><td>{@code 9999-ABEND-PROGRAM}</td><td>{@code theAbendPathEmitsBeforeItRaises},
 *       {@code theAbendContextCarriesTheDeclaredFieldWidths}</td></tr>
 * </table>
 *
 * @see InterestCalculationService
 * @see AbendService
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InterestCalculationService: the interest accrual run, truncating and operand-ordered")
final class InterestCalculationServiceTest {

    /* ============================================================================================ */
    /* PINNED DETERMINISM                                                                            */
    /* ============================================================================================ */

    /**
     * The module's pinned local instant. Every batch timestamp this class asserts is assembled from
     * exactly this reading, so nothing here depends on when the suite runs.
     */
    private static final Instant PINNED_INSTANT = Instant.parse("2022-06-10T19:27:53Z");

    /**
     * The batch 26-character timestamp the pinned instant must assemble to, character for character.
     *
     * <p>Written out rather than formatted: a hyphen before the hour, dots between the time parts, a
     * two-digit hundredths field and a literal four-character tail.
     */
    private static final String ORACLE_BATCH_TIMESTAMP = "2022-06-10-19.27.53.000000";

    /**
     * The <em>online</em> tier's 26-character form of the same instant, declared here only so that a
     * test can prove this service does not emit it. A space before the time and colons inside it: the
     * same length, wrong in three character positions. The two forms are never unified.
     */
    private static final String ONLINE_FORM_OF_PINNED_INSTANT = "2022-06-10 19:27:53.000000";

    /** A second pinned instant whose hundredths are non-zero, proving the two-digit field is two. */
    private static final Instant PINNED_INSTANT_WITH_HUNDREDTHS =
            Instant.parse("2022-06-10T19:27:53.070Z");

    /** The timestamp that second instant must assemble to. */
    private static final String ORACLE_BATCH_TIMESTAMP_WITH_HUNDREDTHS =
            "2022-06-10-19.27.53.070000";

    /**
     * The pinned run-date job parameter: exactly ten characters, and the first ten characters of every
     * identifier the run mints. <strong>Load-bearing.</strong> An ambient date here would make the
     * identifiers non-deterministic and this class's identifier assertions unwritable.
     */
    private static final String PINNED_RUN_DATE = "2022-07-19";

    /* ============================================================================================ */
    /* KEY MATERIAL                                                                                  */
    /* ============================================================================================ */

    /** The account the fixtures post to: eleven digits, as the record key declares. */
    private static final String ACCOUNT_ID = "00000000011";

    /** A second, numerically higher account, so a run can carry two groups in ascending key order. */
    private static final String HIGHER_ACCOUNT_ID = "00000000022";

    /**
     * The type code the fixtures key on. Deliberately different from the category code so that a key
     * built with its arguments transposed cannot accidentally resolve.
     */
    private static final String ROW_TYPE_CD = "03";

    /** The category code the fixtures key on: four characters, and never equal to the type code. */
    private static final String ROW_CAT_CD = "0007";

    /** A second type code, so an ascending scan can be observed advancing within one account. */
    private static final String SECOND_ROW_TYPE_CD = "04";

    /** A ten-character group identifier that resolves on the first probe. */
    private static final String DIRECT_GROUP_ID = TestDataFactory.DIRECT_HIT_DISCLOSURE_GROUP_ID;

    /**
     * The group identifier every one of the fifty seeded accounts actually carries: ten spaces.
     *
     * <p>Measured, not assumed. The account's group field occupies 1-based positions 113 to 122 and is
     * blank on all fifty rows; the value that reads like a group identifier sits at positions 103 to
     * 112, which is the postcode. The consequence is that the seeded estate reaches the rate only
     * through the default-group fallback.
     */
    private static final String SEEDED_BLANK_GROUP_ID = TestDataFactory.SEEDED_ACCOUNT_GROUP_ID;

    /**
     * The default-group probe key: the seven-character literal followed by <strong>three
     * spaces</strong>, because the receiving field is ten characters wide. Assembled from a visible
     * literal and an explicit repeat so the padding survives any tool that strips trailing whitespace.
     */
    private static final String PADDED_DEFAULT_GROUP_ID = "DEFAULT" + " ".repeat(3);

    /** The unpadded form, which resolves nothing and must never be used as a probe key. */
    private static final String UNPADDED_DEFAULT_GROUP_ID = "DEFAULT";

    /** The declared width of the group identifier, in character positions. */
    private static final int GROUP_ID_WIDTH = 10;

    /* ============================================================================================ */
    /* SYNTHESIZED-RECORD CONTRACT LITERALS                                                          */
    /* ============================================================================================ */

    /** The interest transaction's two-character type code. */
    private static final String INTEREST_TYPE_CD = "01";

    /**
     * The interest transaction's category code. A two-character literal moved into a four-digit field
     * stores as four characters: it is neither the number five nor the string {@code "5"}.
     */
    private static final String INTEREST_CAT_CD = "0005";

    /**
     * The interest transaction's source: a six-character literal in a ten-character field, so it
     * carries <strong>four trailing spaces</strong> and is never trimmed.
     */
    private static final String INTEREST_SOURCE = "System" + " ".repeat(4);

    /** The description literal, whose <strong>trailing space is part of it</strong>. */
    private static final String DESCRIPTION_PREFIX = "Int. for a/c ";

    /**
     * The merchant identifier: numeric zero moved into a nine-digit field, so it is nine zero
     * characters as text and never the number zero.
     */
    private static final String MERCHANT_ID = "000000000";

    /** The merchant name field's declared width; spaces are moved into all fifty positions. */
    private static final int MERCHANT_NAME_WIDTH = 50;

    /** The merchant city field's declared width. */
    private static final int MERCHANT_CITY_WIDTH = 50;

    /** The merchant postcode field's declared width. */
    private static final int MERCHANT_ZIP_WIDTH = 10;

    /** The identifier's total width: a ten-character date plus a six-digit suffix. */
    private static final int TRAN_ID_WIDTH = 16;

    /** Both timestamps' declared width. */
    private static final int TIMESTAMP_WIDTH = 26;

    /** The description field's declared width, into which a 24-character write lands. */
    private static final int TRAN_DESC_WIDTH = 100;

    /** The width the description write actually occupies: a 13-character prefix plus eleven digits. */
    private static final int DESCRIPTION_WRITE_WIDTH = 24;

    /** The card number the cross-reference supplies, which the synthesized record must carry. */
    private static final String XREF_CARD_NUM = "4111111111111111";

    /** A second card number, used to prove the first cross-reference row as supplied wins. */
    private static final String SECOND_XREF_CARD_NUM = "4222222222222222";

    /** The nine-digit customer identifier the cross-reference carries. */
    private static final String XREF_CUST_ID = "000000011";

    /* ============================================================================================ */
    /* HAND-COMPUTED INTEREST ORACLES                                                                */
    /* ============================================================================================ */

    /**
     * The decisive fixture's balance. Paired with {@link #DECISIVE_RATE} it makes four candidate
     * implementations produce four different answers, so one assertion discriminates all of them.
     */
    private static final BigDecimal DECISIVE_BALANCE = new BigDecimal("50.20");

    /** The decisive fixture's rate, at the rate field's two decimals. */
    private static final BigDecimal DECISIVE_RATE = new BigDecimal("15.00");

    /**
     * The only correct answer for the decisive fixture: multiply first, divide second, truncate toward
     * zero. Computed by hand - the product is 753.0000 and the exact quotient is 0.6275.
     */
    private static final BigDecimal DECISIVE_INTEREST = new BigDecimal("0.62");

    /** What a half-even or half-up store would produce for the same fixture, and must not. */
    private static final BigDecimal DECISIVE_INTEREST_IF_ROUNDED = new BigDecimal("0.63");

    /** What dividing the balance by the annualising divisor first would produce, and must not. */
    private static final BigDecimal DECISIVE_INTEREST_IF_BALANCE_DIVIDED_FIRST =
            new BigDecimal("0.60");

    /** What dividing the rate by the annualising divisor first would produce, and must not. */
    private static final BigDecimal DECISIVE_INTEREST_IF_RATE_DIVIDED_FIRST = new BigDecimal("0.50");

    /** A second fixture's balance, whose quotient truncates in a different place. */
    private static final BigDecimal SECOND_BALANCE = new BigDecimal("1234.56");

    /** A second fixture's rate. */
    private static final BigDecimal SECOND_RATE = new BigDecimal("7.00");

    /** Hand-computed: the product is 8641.9200 and the exact quotient is 7.2016. */
    private static final BigDecimal SECOND_INTEREST = new BigDecimal("7.20");

    /** What dividing the balance first would produce for the second fixture, and must not. */
    private static final BigDecimal SECOND_INTEREST_IF_BALANCE_DIVIDED_FIRST =
            new BigDecimal("7.14");

    /** A negative balance, to prove truncation is toward zero rather than downward. */
    private static final BigDecimal NEGATIVE_BALANCE = new BigDecimal("-50.20");

    /** Hand-computed: the exact quotient is -0.6275, and truncation toward zero keeps -0.62. */
    private static final BigDecimal NEGATIVE_INTEREST = new BigDecimal("-0.62");

    /** What a half-even store would produce for the negative fixture, and must not. */
    private static final BigDecimal NEGATIVE_INTEREST_IF_ROUNDED = new BigDecimal("-0.63");

    /**
     * The sum of two decisive rows, each truncated before it is added: 0.62 twice.
     *
     * <p>Truncating the exact sum instead - 1.2550 - would give 1.25. The difference is the whole point:
     * the source truncates every row into a two-decimal field and only then adds.
     */
    private static final BigDecimal TWO_DECISIVE_ROWS_ACCRUED = new BigDecimal("1.24");

    /** What truncating the exact sum of the same two rows would give, and must not. */
    private static final BigDecimal TWO_DECISIVE_ROWS_IF_SUM_TRUNCATED_ONCE = new BigDecimal("1.25");

    /** A rate whose quotient has no terminating decimal expansion, paired with a round balance. */
    private static final BigDecimal NON_TERMINATING_RATE = new BigDecimal("25.00");

    /** The balance paired with the non-terminating rate. */
    private static final BigDecimal NON_TERMINATING_BALANCE = new BigDecimal("100.00");

    /** Hand-computed: 2500.0000 divided by the annualising divisor is 2.0833..., truncating to 2.08. */
    private static final BigDecimal NON_TERMINATING_INTEREST = new BigDecimal("2.08");

    /** The smallest rate the four-digit-plus-two-decimal rate field can hold above zero. */
    private static final BigDecimal SMALLEST_NON_ZERO_RATE = new BigDecimal("0.01");

    /** Zero at the monetary scale, which is what a move of zero into a two-decimal field stores. */
    private static final BigDecimal ZERO_MONETARY = new BigDecimal("0.00");

    /** A rate of exactly zero, at the monetary scale. */
    private static final BigDecimal ZERO_RATE = new BigDecimal("0.00");

    /** A rate of zero written at a different scale, which must still read as zero. */
    private static final BigDecimal ZERO_RATE_AT_ANOTHER_SCALE = new BigDecimal("0.0000");

    /** The monetary scale every amount this member stores must carry. */
    private static final int MONETARY_SCALE = 2;

    /** An opening balance for the account the control break rewrites. */
    private static final BigDecimal OPENING_BALANCE = new BigDecimal("500.00");

    /** Hand-computed: the opening balance plus the decisive fixture's interest. */
    private static final BigDecimal BALANCE_AFTER_DECISIVE_INTEREST = new BigDecimal("500.62");

    /** A non-zero cycle credit accumulator, so zeroing it is observable rather than vacuous. */
    private static final BigDecimal OPENING_CYCLE_CREDIT = new BigDecimal("123.45");

    /** A non-zero cycle debit accumulator, so zeroing it is observable rather than vacuous. */
    private static final BigDecimal OPENING_CYCLE_DEBIT = new BigDecimal("678.90");

    /* ============================================================================================ */
    /* STATUS AND ABEND CONTRACT VALUES                                                               */
    /* ============================================================================================ */

    /** The legacy member name this service reports as the abend culprit. */
    private static final String PROGRAM_NAME = "CBACT04C";

    /** The dataset name the disclosure-group reads report. */
    private static final String RESOURCE_DISCGRP = "DISCGRP";

    /** The dataset name the account reads and the account rewrite report. */
    private static final String RESOURCE_ACCTFILE = "ACCTFILE";

    /** The dataset name the cross-reference read reports. */
    private static final String RESOURCE_XREFFILE = "XREFFILE";

    /** The operation name a failed read reports. */
    private static final String OPERATION_READ = "READ";

    /** The operation name a failed account rewrite reports. */
    private static final String OPERATION_REWRITE = "REWRITE";

    /** The three raw status literals the whole estate actually compares, and no others. */
    private static final String STATUS_SUCCESS = "00";

    /** End of file: how a sequential read loop terminates normally. Never an error. */
    private static final String STATUS_END_OF_FILE = "10";

    /** Record not found: acceptable to the rate paragraph and the trigger for its single fallback. */
    private static final String STATUS_RECORD_NOT_FOUND = "23";

    /* ============================================================================================ */
    /* COLLABORATORS                                                                                 */
    /* ============================================================================================ */

    @Mock
    private TransactionCategoryBalanceRepository categoryBalanceRepository;

    @Mock
    private DisclosureGroupRepository disclosureGroupRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CardCrossReferenceRepository crossReferenceRepository;

    /**
     * The real abend collaborator, wrapped in a spy.
     *
     * <p>Real rather than mocked so that an abend genuinely terminates the run instead of returning;
     * spied so that the order of its diagnostic emission relative to the raise can be asserted.
     */
    private AbendService abendService;

    /** The real pass-through commit boundary; its proxying belongs to the integration tier. */
    private InterestGroupTransactionBoundary groupTransactionBoundary;

    /** The service under test, built on the pinned clock. */
    private InterestCalculationService service;

    /** Every record the service handed to the writer it was given, in the order it handed them over. */
    private final List<Transaction> writtenRecords = new ArrayList<>();

    /** Every account group the run closed, in the order its control break closed them. */
    private final List<InterestCalculationService.GroupInterestResult> closedGroups =
            new ArrayList<>();

    /** The service's own logger, so the emit-then-raise ordering can be observed. */
    private Logger serviceLogger;

    /** The level the service's logger carried before this test lowered it. */
    private Level originalServiceLevel;

    /** The captured log events. */
    private ListAppender<ILoggingEvent> logCapture;

    @BeforeEach
    void attachHarness() {
        this.writtenRecords.clear();
        this.closedGroups.clear();
        this.abendService = spy(new AbendService());
        this.groupTransactionBoundary = new InterestGroupTransactionBoundary();
        this.service = serviceOn(PINNED_INSTANT);
        this.serviceLogger = (Logger) LoggerFactory.getLogger(InterestCalculationService.class);
        this.originalServiceLevel = this.serviceLogger.getLevel();
        this.serviceLogger.setLevel(Level.DEBUG);
        this.logCapture = new ListAppender<>();
        this.logCapture.setContext(this.serviceLogger.getLoggerContext());
        this.logCapture.start();
        this.serviceLogger.addAppender(this.logCapture);
    }

    @AfterEach
    void detachHarness() {
        this.serviceLogger.detachAppender(this.logCapture);
        this.logCapture.stop();
        this.serviceLogger.setLevel(this.originalServiceLevel);
    }

    /**
     * Builds the service on a clock fixed to one instant.
     *
     * @param instant the instant the clock must report
     * @return a service whose timestamps are entirely determined by that instant
     */
    private InterestCalculationService serviceOn(final Instant instant) {
        return new InterestCalculationService(this.categoryBalanceRepository,
                this.disclosureGroupRepository,
                this.accountRepository,
                this.crossReferenceRepository,
                this.groupTransactionBoundary,
                this.abendService,
                Clock.fixed(instant, ZoneOffset.UTC));
    }

    /* ============================================================================================ */
    /* FIXTURES AND WIRING HELPERS                                                                   */
    /* ============================================================================================ */

    /** @return the writer the group operation is given, which records what it was handed and when */
    private Consumer<Transaction> recordSink() {
        return this.writtenRecords::add;
    }

    /** @return the destination each closed account group is offered to */
    private Consumer<InterestCalculationService.GroupInterestResult> groupSink() {
        return this.closedGroups::add;
    }

    /**
     * A category-balance row on the fixture key.
     *
     * @param accountId the eleven-digit account identifier
     * @param balance   the row's balance at the monetary scale
     * @return the row
     */
    private static TransactionCategoryBalance categoryBalance(final String accountId,
            final BigDecimal balance) {
        return categoryBalance(accountId, ROW_TYPE_CD, balance);
    }

    /**
     * A category-balance row on a chosen type code, so an ascending scan can advance within one account.
     *
     * @param accountId the eleven-digit account identifier
     * @param typeCode  the two-character type code
     * @param balance   the row's balance at the monetary scale
     * @return the row
     */
    private static TransactionCategoryBalance categoryBalance(final String accountId,
            final String typeCode, final BigDecimal balance) {
        return TestDataFactory.transactionCategoryBalance()
                .accountId(accountId)
                .typeCode(typeCode)
                .categoryCode(ROW_CAT_CD)
                .balance(balance)
                .build();
    }

    /**
     * An account carrying non-zero cycle accumulators, so that zeroing them is observable.
     *
     * @param accountId      the eleven-digit account identifier
     * @param groupId        the ten-character group identifier
     * @param currentBalance the opening balance
     * @return the account
     */
    private static Account account(final String accountId, final String groupId,
            final BigDecimal currentBalance) {
        return TestDataFactory.account()
                .acctId(accountId)
                .currentBalance(currentBalance)
                .cycleCredit(OPENING_CYCLE_CREDIT)
                .cycleDebit(OPENING_CYCLE_DEBIT)
                .addressZip(TestDataFactory.SEEDED_ACCOUNT_ADDRESS_ZIP)
                .groupId(groupId)
                .build();
    }

    /**
     * A cross-reference row supplying a card number for one account.
     *
     * @param accountId  the eleven-digit account identifier
     * @param cardNumber the sixteen-character card number
     * @return the row
     */
    private static CardCrossReference crossReference(final String accountId,
            final String cardNumber) {
        return TestDataFactory.cardCrossReference()
                .cardNumber(cardNumber)
                .customerId(XREF_CUST_ID)
                .accountId(accountId)
                .build();
    }

    /**
     * A disclosure-group row on the fixture key.
     *
     * @param groupId the ten-character group identifier, never trimmed
     * @param rate    the interest rate at the rate field's two decimals
     * @return the row
     */
    private static DisclosureGroup disclosureGroup(final String groupId, final BigDecimal rate) {
        return disclosureGroup(groupId, ROW_TYPE_CD, rate);
    }

    /**
     * A disclosure-group row on a chosen type code.
     *
     * @param groupId  the ten-character group identifier, never trimmed
     * @param typeCode the two-character type code
     * @param rate     the interest rate at the rate field's two decimals
     * @return the row
     */
    private static DisclosureGroup disclosureGroup(final String groupId, final String typeCode,
            final BigDecimal rate) {
        return TestDataFactory.disclosureGroup()
                .groupId(groupId)
                .typeCode(typeCode)
                .categoryCode(ROW_CAT_CD)
                .interestRate(rate)
                .build();
    }

    /** Wires the two keyed reads a group performs once, before any row of it. */
    private void givenGroupReadsResolve(final Account existing) {
        when(this.accountRepository.findById(existing.getAcctId()))
                .thenReturn(Optional.of(existing));
        when(this.crossReferenceRepository
                .findFirstByXrefAcctIdOrderByXrefCardNumAsc(existing.getAcctId()))
                .thenReturn(Optional.of(crossReference(existing.getAcctId(), XREF_CARD_NUM)));
    }

    /** Wires the account rewrite the control break performs, echoing back exactly what was saved. */
    private void givenAccountRewriteEchoes() {
        when(this.accountRepository.save(any(Account.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    /** Wires every disclosure probe, whatever its key, to resolve at one rate. */
    private void givenEveryProbeResolvesAt(final BigDecimal rate) {
        when(this.disclosureGroupRepository.findById(any()))
                .thenReturn(Optional.of(disclosureGroup(DIRECT_GROUP_ID, rate)));
    }

    /**
     * Drives one account group of a single row and returns what the group produced.
     *
     * @param accountId the eleven-digit account identifier the group keys on
     * @param balance   the single row's balance
     * @return the closed group
     */
    private InterestCalculationService.GroupInterestResult accrueSingleRow(final String accountId,
            final BigDecimal balance) {
        return this.service.calculateGroupInterest(PINNED_RUN_DATE, accountId,
                List.of(categoryBalance(accountId, balance)), 0L, recordSink());
    }

    /** @return the formatted text of every captured log event, in emission order */
    private List<String> loggedMessages() {
        return this.logCapture.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * The number of bytes a fixed-width field occupies, measured on the encoded form.
     *
     * <p>Never {@code String.length()}: a fixed-width external contract is a byte count, and measuring
     * characters would silently accept a value that cannot be written at its declared width.
     *
     * @param value the field value
     * @return its encoded byte count
     */
    private static int encodedBytes(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * The test's own oracle for the bounded description write.
     *
     * <p>This overlays a sending value onto the left of a receiving field of a stated width and leaves
     * <strong>everything beyond the sender untouched</strong>, which is what the source's concatenation
     * does when it is given no pointer, no overflow clause and no prior initialisation. It is
     * deliberately written here rather than borrowed from a production helper: an oracle that shares the
     * implementation's code cannot detect the implementation blanking the field.
     *
     * @param sender   the value written at the left of the receiver
     * @param receiver the receiver's prior contents, exactly its declared width
     * @return the receiver's contents after the bounded write
     */
    private static String overlayLeft(final String sender, final String receiver) {
        return sender + receiver.substring(encodedBytes(sender));
    }

    /* ============================================================================================ */

    /**
     * Paragraph {@code 1300-COMPUTE-INTEREST}: the interest expression itself.
     *
     * <p>Every expectation in this nest is a hand-computed literal. Nothing here asks a production class
     * what the answer should be.
     */
    @Nested
    @DisplayName("1300-COMPUTE-INTEREST: multiply first, divide second, truncate toward zero")
    class TheInterestExpression {

        /** Creates the nest. */
        TheInterestExpression() {
        }

        @Test
        @DisplayName("truncates toward zero: a half-even or half-up implementation FAILS here, because "
                + "the exact quotient 0.6275 must store as 0.62 and never as 0.63")
        void truncatesRatherThanRounding() {
            givenGroupReadsResolve(account(ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE));
            givenAccountRewriteEchoes();
            givenEveryProbeResolvesAt(DECISIVE_RATE);

            final InterestCalculationService.GroupInterestResult closed = accrueSingleRow(ACCOUNT_ID,
                    DECISIVE_BALANCE);

            final BigDecimal computed = closed.categoryInterests().get(0).monthlyInterest();
            assertAll(
                    () -> assertThat(computed)
                            .as("the estate carries no rounding clause at all, so the store truncates")
                            .isEqualTo(DECISIVE_INTEREST),
                    () -> assertThat(computed)
                            .as("a half-even store would produce this, and must not")
                            .isNotEqualTo(DECISIVE_INTEREST_IF_ROUNDED),
                    () -> assertThat(computed.scale())
                            .as("the receiving field carries two decimals")
                            .isEqualTo(MONETARY_SCALE));
        }

        @Test
        @DisplayName("multiplies before dividing: dividing either operand by the annualising divisor "
                + "first moves the truncation point and produces a different amount")
        void multipliesBeforeDividing() {
            givenGroupReadsResolve(account(ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE));
            givenAccountRewriteEchoes();
            givenEveryProbeResolvesAt(DECISIVE_RATE);

            final InterestCalculationService.GroupInterestResult closed = accrueSingleRow(ACCOUNT_ID,
                    DECISIVE_BALANCE);

            final BigDecimal computed = closed.categoryInterests().get(0).monthlyInterest();
            assertAll(
                    () -> assertThat(computed).isEqualTo(DECISIVE_INTEREST),
                    () -> assertThat(computed)
                            .as("dividing the balance first would give this")
                            .isNotEqualTo(DECISIVE_INTEREST_IF_BALANCE_DIVIDED_FIRST),
                    () -> assertThat(computed)
                            .as("dividing the rate first would give this")
                            .isNotEqualTo(DECISIVE_INTEREST_IF_RATE_DIVIDED_FIRST));
        }

        @Test
        @DisplayName("a second fixture confirms the operand order independently: 8641.9200 over the "
                + "divisor truncates to 7.20, where dividing the balance first would give 7.14")
        void multipliesBeforeDividingOnASecondFixture() {
            givenGroupReadsResolve(account(ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE));
            givenAccountRewriteEchoes();
            givenEveryProbeResolvesAt(SECOND_RATE);

            final InterestCalculationService.GroupInterestResult closed = accrueSingleRow(ACCOUNT_ID,
                    SECOND_BALANCE);

            final BigDecimal computed = closed.categoryInterests().get(0).monthlyInterest();
            assertAll(
                    () -> assertThat(computed).isEqualTo(SECOND_INTEREST),
                    () -> assertThat(computed)
                            .isNotEqualTo(SECOND_INTEREST_IF_BALANCE_DIVIDED_FIRST),
                    () -> assertThat(computed.scale()).isEqualTo(MONETARY_SCALE));
        }

        @Test
        @DisplayName("truncation is toward zero, not downward: a negative balance keeps -0.62 where a "
                + "half-even store would give -0.63 and a floor would give -0.63")
        void truncatesTowardZeroOnANegativeAmount() {
            givenGroupReadsResolve(account(ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE));
            givenAccountRewriteEchoes();
            givenEveryProbeResolvesAt(DECISIVE_RATE);

            final InterestCalculationService.GroupInterestResult closed = accrueSingleRow(ACCOUNT_ID,
                    NEGATIVE_BALANCE);

            final BigDecimal computed = closed.categoryInterests().get(0).monthlyInterest();
            assertAll(
                    () -> assertThat(computed).isEqualTo(NEGATIVE_INTEREST),
                    () -> assertThat(computed).isNotEqualTo(NEGATIVE_INTEREST_IF_ROUNDED),
                    () -> assertThat(computed.scale()).isEqualTo(MONETARY_SCALE));
        }

        @Test
        @DisplayName("the running total truncates every row BEFORE adding it, so two rows accrue 1.24 "
                + "and never the 1.25 that truncating the exact sum once would give")
        void theRunningTotalTruncatesEachRowBeforeAdding() {
            final Account existing = account(ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE);
            givenGroupReadsResolve(existing);
            givenAccountRewriteEchoes();
            givenEveryProbeResolvesAt(DECISIVE_RATE);

            final InterestCalculationService.GroupInterestResult closed =
                    InterestCalculationServiceTest.this.service.calculateGroupInterest(
                            PINNED_RUN_DATE, ACCOUNT_ID,
                            List.of(categoryBalance(ACCOUNT_ID, DECISIVE_BALANCE),
                                    categoryBalance(ACCOUNT_ID, SECOND_ROW_TYPE_CD,
                                            DECISIVE_BALANCE)),
                            0L, recordSink());

            assertAll(
                    () -> assertThat(closed.totalInterest()).isEqualTo(TWO_DECISIVE_ROWS_ACCRUED),
                    () -> assertThat(closed.totalInterest())
                            .as("truncating the exact sum once would give this")
                            .isNotEqualTo(TWO_DECISIVE_ROWS_IF_SUM_TRUNCATED_ONCE),
                    () -> assertThat(closed.totalInterest().scale()).isEqualTo(MONETARY_SCALE),
                    () -> assertThat(closed.interestTransactions())
                            .extracting(Transaction::getTranAmt)
                            .containsExactly(DECISIVE_INTEREST, DECISIVE_INTEREST));
        }

        @Test
        @DisplayName("a quotient with no terminating decimal expansion is truncated, not refused: "
                + "2500.0000 over the divisor is 2.0833... and stores as 2.08")
        void aNonTerminatingQuotientIsTruncatedRatherThanRefused() {
            givenGroupReadsResolve(account(ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE));
            givenAccountRewriteEchoes();
            givenEveryProbeResolvesAt(NON_TERMINATING_RATE);

            final InterestCalculationService.GroupInterestResult closed = accrueSingleRow(ACCOUNT_ID,
                    NON_TERMINATING_BALANCE);

            assertThat(closed.categoryInterests().get(0).monthlyInterest())
                    .isEqualTo(NON_TERMINATING_INTEREST);
        }

        @ParameterizedTest(name = "balance {0} at rate {1} accrues exactly {2}")
        @CsvSource({
            "50.20, 15.00, 0.62",
            "1234.56, 7.00, 7.20",
            "-50.20, 15.00, -0.62",
            "100.00, 25.00, 2.08",
            "0.00, 15.00, 0.00",
            "100.00, 0.01, 0.00",
        })
        @DisplayName("every hand-computed pairing accrues its literal amount at scale two")
        void everyHandComputedPairingAccruesItsLiteralAmount(final String balance, final String rate,
                final String expectedInterest) {
            givenGroupReadsResolve(account(ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE));
            givenAccountRewriteEchoes();
            givenEveryProbeResolvesAt(new BigDecimal(rate));

            final InterestCalculationService.GroupInterestResult closed = accrueSingleRow(ACCOUNT_ID,
                    new BigDecimal(balance));

            final BigDecimal computed = closed.categoryInterests().get(0).monthlyInterest();
            assertAll(
                    () -> assertThat(computed).isEqualTo(new BigDecimal(expectedInterest)),
                    () -> assertThat(computed.scale()).isEqualTo(MONETARY_SCALE));
        }

        @Test
        @DisplayName("the smallest representable non-zero rate still passes the gate and still accrues "
                + "a two-decimal amount, which for this balance truncates to zero")
        void theSmallestNonZeroRateStillAccrues() {
            givenGroupReadsResolve(account(ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE));
            givenAccountRewriteEchoes();
            givenEveryProbeResolvesAt(SMALLEST_NON_ZERO_RATE);

            final InterestCalculationService.GroupInterestResult closed = accrueSingleRow(ACCOUNT_ID,
                    NON_TERMINATING_BALANCE);

            assertAll(
                    () -> assertThat(closed.categoryInterests().get(0).rateGateSkipped())
                            .as("the gate tests the rate, not the amount it produces")
                            .isFalse(),
                    () -> assertThat(closed.categoryInterests().get(0).monthlyInterest())
                            .isEqualTo(ZERO_MONETARY),
                    () -> assertThat(closed.interestTransactions()).hasSize(1));
        }

        @Test
        @DisplayName("every monetary value the run exposes is a BigDecimal: no approximate binary "
                + "numeric type appears anywhere on the money path")
        void everyMonetaryValueIsABigDecimal() {
            givenGroupReadsResolve(account(ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE));
            givenAccountRewriteEchoes();
            givenEveryProbeResolvesAt(DECISIVE_RATE);

            final InterestCalculationService.GroupInterestResult closed =
                    accrueSingleRow(ACCOUNT_ID, DECISIVE_BALANCE);

            final InterestCalculationService.CategoryInterest row = closed.categoryInterests().get(0);
            assertAll(
                    () -> assertThat(closed.totalInterest()).isInstanceOf(BigDecimal.class),
                    () -> assertThat(row.monthlyInterest()).isInstanceOf(BigDecimal.class),
                    () -> assertThat(row.categoryBalance()).isInstanceOf(BigDecimal.class),
                    () -> assertThat(row.disclosedRate()).isInstanceOf(BigDecimal.class),
                    () -> assertThat(closed.interestTransactions().get(0).getTranAmt())
                            .isInstanceOf(BigDecimal.class),
                    () -> assertThat(closed.updatedAccount().getAcctCurrBal())
                            .isInstanceOf(BigDecimal.class),
                    () -> assertThat(closed.updatedAccount().getAcctCurrCycCredit())
                            .isInstanceOf(BigDecimal.class),
                    () -> assertThat(closed.updatedAccount().getAcctCurrCycDebit())
                            .isInstanceOf(BigDecimal.class));
        }
    }

    /* ============================================================================================ */

    /**
     * Paragraphs {@code 1200-GET-INTEREST-RATE} and {@code 1200-A-GET-DEFAULT-INT-RATE}: the rate
     * lookup, its single fallback, and the composite key both probes are built from.
     */
    @Nested
    @DisplayName("1200-GET-INTEREST-RATE and 1200-A-GET-DEFAULT-INT-RATE: one probe, one retry, no more")
    class TheRateLookupAndItsSingleFallback {

        /** Creates the nest. */
        TheRateLookupAndItsSingleFallback() {
        }

        @Test
        @DisplayName("the composite key is built group, TYPE, CATEGORY - the record-key order, never "
                + "the order the source's three moves happen to appear in")
        void theCompositeKeyIsBuiltInGroupTypeCategoryOrder() {
            givenGroupReadsResolve(account(ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE));
            givenAccountRewriteEchoes();
            givenEveryProbeResolvesAt(DECISIVE_RATE);

            accrueSingleRow(ACCOUNT_ID, DECISIVE_BALANCE);

            final ArgumentCaptor<DisclosureGroupId> probed =
                    ArgumentCaptor.forClass(DisclosureGroupId.class);
            verify(InterestCalculationServiceTest.this.disclosureGroupRepository)
                    .findById(probed.capture());
            final DisclosureGroupId key = probed.getValue();
            assertAll(
                    () -> assertThat(key.getDisAcctGroupId())
                            .as("first component is the group identifier")
                            .isEqualTo(DIRECT_GROUP_ID),
                    () -> assertThat(key.getDisTranTypeCd())
                            .as("second component is the TYPE code, not the category code")
                            .isEqualTo(ROW_TYPE_CD),
                    () -> assertThat(key.getDisTranCatCd())
                            .as("third component is the CATEGORY code, not the type code")
                            .isEqualTo(ROW_CAT_CD));
        }

        @Test
        @DisplayName("the category-balance key is built account, TYPE, CATEGORY, so a transposed "
                + "constructor call cannot silently mis-key the scan")
        void theCategoryBalanceKeyIsBuiltInAccountTypeCategoryOrder() {
            final TransactionCategoryBalance row =
                    categoryBalance(ACCOUNT_ID, ROW_TYPE_CD, DECISIVE_BALANCE);

            final TransactionCategoryBalanceId key = row.toId();

            assertAll(
                    () -> assertThat(key.getTrancatAcctId()).isEqualTo(ACCOUNT_ID),
                    () -> assertThat(key.getTrancatTypeCd()).isEqualTo(ROW_TYPE_CD),
                    () -> assertThat(key.getTrancatCd()).isEqualTo(ROW_CAT_CD),
                    () -> assertThat(key)
                            .as("the declared order is account, type, category")
                            .isEqualTo(new TransactionCategoryBalanceId(ACCOUNT_ID, ROW_TYPE_CD,
                                    ROW_CAT_CD)),
                    () -> assertThat(key)
                            .as("a transposed key must not compare equal")
                            .isNotEqualTo(new TransactionCategoryBalanceId(ACCOUNT_ID, ROW_CAT_CD,
                                    ROW_TYPE_CD)));
        }

        @Test
        @DisplayName("a direct hit performs NO fallback probe at all: exactly one lookup, on the "
                + "account's own group identifier")
        void aDirectHitPerformsNoFallbackProbe() {
            givenGroupReadsResolve(account(ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE));
            givenAccountRewriteEchoes();
            when(InterestCalculationServiceTest.this.disclosureGroupRepository.findById(
                    eq(new DisclosureGroupId(DIRECT_GROUP_ID, ROW_TYPE_CD, ROW_CAT_CD))))
                    .thenReturn(Optional.of(disclosureGroup(DIRECT_GROUP_ID, DECISIVE_RATE)));

            final InterestCalculationService.GroupInterestResult closed =
                    accrueSingleRow(ACCOUNT_ID, DECISIVE_BALANCE);

            assertAll(
                    () -> assertThat(closed.defaultGroupUsed())
                            .as("the first probe resolved, so no fallback happened")
                            .isFalse(),
                    () -> verify(InterestCalculationServiceTest.this.disclosureGroupRepository,
                            times(1))
                            .findById(new DisclosureGroupId(DIRECT_GROUP_ID, ROW_TYPE_CD,
                                    ROW_CAT_CD)),
                    () -> verify(InterestCalculationServiceTest.this.disclosureGroupRepository,
                            never())
                            .findById(new DisclosureGroupId(PADDED_DEFAULT_GROUP_ID, ROW_TYPE_CD,
                                    ROW_CAT_CD)),
                    () -> verifyNoMoreInteractions(
                            InterestCalculationServiceTest.this.disclosureGroupRepository));
        }

        @Test
        @DisplayName("a miss falls back EXACTLY ONCE, and the fallback carries the same type and "
                + "category codes with only the group identifier replaced")
        void aMissFallsBackExactlyOnce() {
            givenGroupReadsResolve(account(ACCOUNT_ID, SEEDED_BLANK_GROUP_ID, OPENING_BALANCE));
            givenAccountRewriteEchoes();
            when(InterestCalculationServiceTest.this.disclosureGroupRepository.findById(
                    eq(new DisclosureGroupId(SEEDED_BLANK_GROUP_ID, ROW_TYPE_CD, ROW_CAT_CD))))
                    .thenReturn(Optional.empty());
            when(InterestCalculationServiceTest.this.disclosureGroupRepository.findById(
                    eq(new DisclosureGroupId(PADDED_DEFAULT_GROUP_ID, ROW_TYPE_CD, ROW_CAT_CD))))
                    .thenReturn(Optional.of(
                            disclosureGroup(PADDED_DEFAULT_GROUP_ID, DECISIVE_RATE)));

            final InterestCalculationService.GroupInterestResult closed =
                    accrueSingleRow(ACCOUNT_ID, DECISIVE_BALANCE);

            assertAll(
                    () -> assertThat(closed.defaultGroupUsed())
                            .as("the fallback supplied the rate")
                            .isTrue(),
                    () -> assertThat(closed.categoryInterests().get(0).monthlyInterest())
                            .isEqualTo(DECISIVE_INTEREST),
                    () -> verify(InterestCalculationServiceTest.this.disclosureGroupRepository,
                            times(1))
                            .findById(new DisclosureGroupId(SEEDED_BLANK_GROUP_ID, ROW_TYPE_CD,
                                    ROW_CAT_CD)),
                    () -> verify(InterestCalculationServiceTest.this.disclosureGroupRepository,
                            times(1))
                            .findById(new DisclosureGroupId(PADDED_DEFAULT_GROUP_ID, ROW_TYPE_CD,
                                    ROW_CAT_CD)),
                    () -> verifyNoMoreInteractions(
                            InterestCalculationServiceTest.this.disclosureGroupRepository));
        }

        @Test
        @DisplayName("the fallback probe key carries the PADDED ten-byte literal with its three "
                + "trailing spaces intact, measured on encoded bytes and never trimmed")
        void theFallbackProbeKeyIsThePaddedTenByteLiteral() {
            givenGroupReadsResolve(account(ACCOUNT_ID, SEEDED_BLANK_GROUP_ID, OPENING_BALANCE));
            givenAccountRewriteEchoes();
            when(InterestCalculationServiceTest.this.disclosureGroupRepository.findById(any()))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(
                            disclosureGroup(PADDED_DEFAULT_GROUP_ID, DECISIVE_RATE)));

            accrueSingleRow(ACCOUNT_ID, DECISIVE_BALANCE);

            final ArgumentCaptor<DisclosureGroupId> probed =
                    ArgumentCaptor.forClass(DisclosureGroupId.class);
            verify(InterestCalculationServiceTest.this.disclosureGroupRepository, times(2))
                    .findById(probed.capture());
            final String fallbackGroupId = probed.getAllValues().get(1).getDisAcctGroupId();
            assertAll(
                    () -> assertThat(encodedBytes(fallbackGroupId))
                            .as("the receiving group field is ten characters wide")
                            .isEqualTo(GROUP_ID_WIDTH),
                    () -> assertThat(fallbackGroupId)
                            .as("the padded literal, spaces intact")
                            .isEqualTo(PADDED_DEFAULT_GROUP_ID),
                    () -> assertThat(fallbackGroupId)
                            .as("the trailing spaces are never trimmed away")
                            .isNotEqualTo(UNPADDED_DEFAULT_GROUP_ID)
                            .endsWith(" ".repeat(3)),
                    () -> assertThat(encodedBytes(UNPADDED_DEFAULT_GROUP_ID))
                            .as("the unpadded form is three bytes short of the field")
                            .isEqualTo(GROUP_ID_WIDTH - 3));
        }

        @Test
        @DisplayName("a stub keyed on the SEVEN-character literal is never matched, which is what "
                + "proves the three-space padding is load-bearing rather than cosmetic")
        void theSevenCharacterFormResolvesNothing() {
            givenGroupReadsResolve(account(ACCOUNT_ID, SEEDED_BLANK_GROUP_ID, OPENING_BALANCE));
            // A lookup table that answers the SEVEN-character form and nothing else. If the padding
            // were cosmetic this table would resolve the fallback; because the padding is part of the
            // key, it resolves nothing and the run abends.
            when(InterestCalculationServiceTest.this.disclosureGroupRepository.findById(any()))
                    .thenAnswer(invocation -> {
                        final DisclosureGroupId probedKey = invocation.getArgument(0);
                        if (UNPADDED_DEFAULT_GROUP_ID.equals(probedKey.getDisAcctGroupId())) {
                            return Optional.of(
                                    disclosureGroup(UNPADDED_DEFAULT_GROUP_ID, DECISIVE_RATE));
                        }
                        return Optional.empty();
                    });

            assertThatExceptionOfType(AbendException.class)
                    .as("the unpadded stub is not the key the service probes, so the fallback misses")
                    .isThrownBy(() -> accrueSingleRow(ACCOUNT_ID, DECISIVE_BALANCE));

            assertAll(
                    () -> verify(InterestCalculationServiceTest.this.disclosureGroupRepository,
                            never())
                            .findById(new DisclosureGroupId(UNPADDED_DEFAULT_GROUP_ID, ROW_TYPE_CD,
                                    ROW_CAT_CD)),
                    () -> verify(InterestCalculationServiceTest.this.disclosureGroupRepository)
                            .findById(new DisclosureGroupId(PADDED_DEFAULT_GROUP_ID, ROW_TYPE_CD,
                                    ROW_CAT_CD)),
                    () -> verify(InterestCalculationServiceTest.this.accountRepository, never())
                            .save(any(Account.class)));
        }

        @Test
        @DisplayName("a second miss ABENDS: there is no third attempt, no loop and no retry count")
        void aSecondMissAbends() {
            givenGroupReadsResolve(account(ACCOUNT_ID, SEEDED_BLANK_GROUP_ID, OPENING_BALANCE));
            when(InterestCalculationServiceTest.this.disclosureGroupRepository.findById(any()))
                    .thenReturn(Optional.empty());

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> accrueSingleRow(ACCOUNT_ID, DECISIVE_BALANCE))
                    .satisfies(abend -> assertAll(
                            () -> assertThat(abend.code())
                                    .as("the batch abort code the source moves before the abort call")
                                    .isEqualTo(AbendException.BATCH_ABEND_CODE),
                            () -> assertThat(abend.culprit())
                                    .as("the culprit is the legacy program name")
                                    .isEqualTo(PROGRAM_NAME),
                            () -> assertThat(abend.reason())
                                    .as("the reason carries the raw two-byte status")
                                    .contains(STATUS_RECORD_NOT_FOUND)));

            assertAll(
                    () -> verify(InterestCalculationServiceTest.this.disclosureGroupRepository,
                            times(2)).findById(any()),
                    () -> verifyNoMoreInteractions(
                            InterestCalculationServiceTest.this.disclosureGroupRepository),
                    () -> verify(InterestCalculationServiceTest.this.accountRepository, never())
                            .save(any(Account.class)));
        }

        @Test
        @DisplayName("an unreadable disclosure group is the ERROR arm rather than the fallback arm, so "
                + "it abends without ever probing the default group")
        void anUnreadableDisclosureGroupIsTheErrorArm() {
            givenGroupReadsResolve(account(ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE));
            when(InterestCalculationServiceTest.this.disclosureGroupRepository.findById(any()))
                    .thenThrow(new QueryTimeoutException("the disclosure group is unreadable"));

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> accrueSingleRow(ACCOUNT_ID, DECISIVE_BALANCE));

            assertAll(
                    () -> verify(InterestCalculationServiceTest.this.disclosureGroupRepository,
                            times(1)).findById(any()),
                    () -> assertThat(loggedMessages())
                            .as("a read failure is not a missing record")
                            .noneMatch(message -> message.contains("TRY WITH DEFAULT GROUP CODE")));
        }

        @Test
        @DisplayName("the rate resolved by the fallback is reported as the DEFAULT group's rate, which "
                + "is the only path the fifty seeded accounts can reach")
        void theSeededEstateAlwaysReachesTheRateThroughTheFallback() {
            assertThat(SEEDED_BLANK_GROUP_ID)
                    .as("the measured group field of all fifty seeded accounts is ten spaces")
                    .isEqualTo(" ".repeat(GROUP_ID_WIDTH))
                    .isNotEqualTo(TestDataFactory.SEEDED_ACCOUNT_ADDRESS_ZIP);

            givenGroupReadsResolve(account(ACCOUNT_ID, SEEDED_BLANK_GROUP_ID, OPENING_BALANCE));
            givenAccountRewriteEchoes();
            when(InterestCalculationServiceTest.this.disclosureGroupRepository.findById(any()))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(
                            disclosureGroup(PADDED_DEFAULT_GROUP_ID, DECISIVE_RATE)));

            final InterestCalculationService.GroupInterestResult closed =
                    accrueSingleRow(ACCOUNT_ID, DECISIVE_BALANCE);

            assertAll(
                    () -> assertThat(closed.defaultGroupUsed()).isTrue(),
                    () -> assertThat(closed.categoryInterests().get(0).disclosedRate())
                            .isEqualTo(DECISIVE_RATE),
                    () -> assertThat(TestDataFactory.FALLBACK_DISCLOSURE_GROUP_ID)
                            .as("the seeded fixture's own fallback key is the padded form")
                            .isEqualTo(PADDED_DEFAULT_GROUP_ID));
        }
    }

    /* ============================================================================================ */

    /**
     * The rate gate, and the empty fee paragraph it encloses.
     *
     * <p>The gate tests that the rate is not zero and its matching end sits after the fee invocation, so
     * <strong>both</strong> the computation and the fee call are inside it.
     *
     * <h2>Why the fee invocation is asserted by its absence of effect</h2>
     *
     * <p>Paragraph {@code 1400-COMPUTE-FEES} is <strong>empty in the source and genuinely invoked</strong>
     * from inside the gate. Its Java counterpart is a private method with no body, so no spy and no mock
     * can observe the call: an empty method has no observable effect <em>by construction</em>, and that
     * emptiness is itself the contract. What can be asserted - and is asserted below - is the whole of
     * what the paragraph is allowed to do: nothing. The amount carried is exactly the computed interest
     * with no fee component, the balance moves by exactly the accrued interest, exactly one record is
     * written per accruing row, and no collaborator is touched beyond the ones the accrual itself needs.
     *
     * <p><strong>Inventing fee logic here is prohibited.</strong> It would be feature expansion, it would
     * change what an interest run outputs, and it would break byte parity against the documented
     * baseline. If fees are ever wanted they belong in a change that states the requirement.
     */
    @Nested
    @DisplayName("1400-COMPUTE-FEES and its enclosing rate gate: an invoked no-op, skipped with the "
            + "computation when the rate is zero")
    class TheRateGateAndTheEmptyFeeParagraph {

        /** Creates the nest. */
        TheRateGateAndTheEmptyFeeParagraph() {
        }

        @Test
        @DisplayName("on the non-zero-rate path the fee paragraph runs once per row and contributes "
                + "NOTHING: the amount is exactly the interest, with no fee added anywhere")
        void theFeeParagraphContributesNothingOnTheAccruingPath() {
            givenGroupReadsResolve(account(ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE));
            givenAccountRewriteEchoes();
            givenEveryProbeResolvesAt(DECISIVE_RATE);

            final InterestCalculationService.GroupInterestResult closed =
                    accrueSingleRow(ACCOUNT_ID, DECISIVE_BALANCE);

            assertAll(
                    () -> assertThat(closed.interestTransactions())
                            .as("one accruing row writes exactly one record - no fee record")
                            .hasSize(1),
                    () -> assertThat(closed.interestTransactions().get(0).getTranAmt())
                            .as("the amount is the interest itself, carrying no fee component")
                            .isEqualTo(DECISIVE_INTEREST),
                    () -> assertThat(closed.totalInterest())
                            .as("the group total is the interest alone")
                            .isEqualTo(DECISIVE_INTEREST),
                    () -> assertThat(closed.updatedAccount().getAcctCurrBal())
                            .as("the balance moved by exactly the interest and by nothing else")
                            .isEqualTo(BALANCE_AFTER_DECISIVE_INTEREST),
                    () -> assertThat(InterestCalculationServiceTest.this.writtenRecords)
                            .as("the writer received exactly one record")
                            .hasSize(1));
        }

        @Test
        @DisplayName("the fee paragraph touches no collaborator: across a two-row accruing group the "
                + "only interactions are the group's own reads and its single rewrite")
        void theFeeParagraphTouchesNoCollaborator() {
            givenGroupReadsResolve(account(ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE));
            givenAccountRewriteEchoes();
            givenEveryProbeResolvesAt(DECISIVE_RATE);

            InterestCalculationServiceTest.this.service.calculateGroupInterest(PINNED_RUN_DATE,
                    ACCOUNT_ID,
                    List.of(categoryBalance(ACCOUNT_ID, DECISIVE_BALANCE),
                            categoryBalance(ACCOUNT_ID, SECOND_ROW_TYPE_CD, DECISIVE_BALANCE)),
                    0L, recordSink());

            assertAll(
                    () -> verify(InterestCalculationServiceTest.this.accountRepository)
                            .findById(ACCOUNT_ID),
                    () -> verify(InterestCalculationServiceTest.this.accountRepository, times(1))
                            .save(any(Account.class)),
                    () -> verifyNoMoreInteractions(
                            InterestCalculationServiceTest.this.accountRepository),
                    () -> verify(InterestCalculationServiceTest.this.disclosureGroupRepository,
                            times(2)).findById(any()),
                    () -> verifyNoMoreInteractions(
                            InterestCalculationServiceTest.this.disclosureGroupRepository),
                    () -> verify(InterestCalculationServiceTest.this.crossReferenceRepository)
                            .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID),
                    () -> verifyNoMoreInteractions(
                            InterestCalculationServiceTest.this.crossReferenceRepository),
                    () -> verifyNoInteractions(
                            InterestCalculationServiceTest.this.categoryBalanceRepository));
        }

        @Test
        @DisplayName("a ZERO rate skips BOTH halves of the gate: nothing is computed, nothing is "
                + "written, the running total is untouched and the fee paragraph is never reached")
        void aZeroRateSkipsBothTheComputationAndTheFeeInvocation() {
            givenGroupReadsResolve(account(ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE));
            givenAccountRewriteEchoes();
            givenEveryProbeResolvesAt(ZERO_RATE);

            final InterestCalculationService.GroupInterestResult closed =
                    accrueSingleRow(ACCOUNT_ID, DECISIVE_BALANCE);

            assertAll(
                    () -> assertThat(closed.rateGateSkipped())
                            .as("the gate closed on a zero rate")
                            .isTrue(),
                    () -> assertThat(closed.categoryInterests().get(0).producedTransaction())
                            .as("the computation half was skipped, so no record exists")
                            .isFalse(),
                    () -> assertThat(closed.categoryInterests().get(0).monthlyInterest())
                            .as("no interest was computed")
                            .isEqualTo(ZERO_MONETARY),
                    () -> assertThat(closed.interestTransactions()).isEmpty(),
                    () -> assertThat(InterestCalculationServiceTest.this.writtenRecords)
                            .as("the writer was never handed anything")
                            .isEmpty(),
                    () -> assertThat(closed.totalInterest())
                            .as("the running total is unchanged")
                            .isEqualTo(ZERO_MONETARY),
                    () -> assertThat(closed.updatedAccount().getAcctCurrBal())
                            .as("the balance is unchanged, because nothing accrued")
                            .isEqualTo(OPENING_BALANCE),
                    () -> assertThat(closed.updatedAccount().getAcctCurrBal())
                            .as("and in particular no fee was deducted, because the fee half of the "
                                    + "gate was skipped with the computation half")
                            .isNotEqualTo(BALANCE_AFTER_DECISIVE_INTEREST));
        }

        @Test
        @DisplayName("a zero rate written at another scale still reads as zero, so the gate closes by "
                + "value rather than by scale and no record is minted for it")
        void aZeroRateAtAnotherScaleStillClosesTheGate() {
            givenGroupReadsResolve(account(ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE));
            givenAccountRewriteEchoes();
            givenEveryProbeResolvesAt(ZERO_RATE_AT_ANOTHER_SCALE);

            final InterestCalculationService.GroupInterestResult closed =
                    accrueSingleRow(ACCOUNT_ID, DECISIVE_BALANCE);

            assertAll(
                    () -> assertThat(closed.rateGateSkipped()).isTrue(),
                    () -> assertThat(closed.interestTransactions()).isEmpty(),
                    () -> assertThat(ZERO_RATE_AT_ANOTHER_SCALE.scale())
                            .as("the fixture really does carry a different scale")
                            .isNotEqualTo(MONETARY_SCALE));
        }

        @Test
        @DisplayName("a group of only zero-rate rows still rewrites its account exactly once, because "
                + "the control break is unconditional even when nothing accrued")
        void aGroupOfOnlySkippedRowsStillRewritesItsAccountOnce() {
            givenGroupReadsResolve(account(ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE));
            givenAccountRewriteEchoes();
            givenEveryProbeResolvesAt(ZERO_RATE);

            InterestCalculationServiceTest.this.service.calculateGroupInterest(PINNED_RUN_DATE,
                    ACCOUNT_ID,
                    List.of(categoryBalance(ACCOUNT_ID, DECISIVE_BALANCE),
                            categoryBalance(ACCOUNT_ID, SECOND_ROW_TYPE_CD, SECOND_BALANCE)),
                    0L, recordSink());

            assertAll(
                    () -> assertThat(InterestCalculationServiceTest.this.writtenRecords).isEmpty(),
                    () -> verify(InterestCalculationServiceTest.this.accountRepository, times(1))
                            .save(any(Account.class)));
        }

        @Test
        @DisplayName("a ZERO BALANCE with a NON-ZERO rate is the live seeded shape - all fifty seeded "
                + "rows carry a zero balance - and it still passes the gate and still mints a record")
        void aZeroBalanceWithANonZeroRateStillMintsARecord() {
            givenGroupReadsResolve(account(ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE));
            givenAccountRewriteEchoes();
            givenEveryProbeResolvesAt(DECISIVE_RATE);

            final InterestCalculationService.GroupInterestResult closed =
                    accrueSingleRow(ACCOUNT_ID, ZERO_MONETARY);

            assertAll(
                    () -> assertThat(closed.rateGateSkipped())
                            .as("the gate tests the RATE, so a zero balance does not close it")
                            .isFalse(),
                    () -> assertThat(closed.categoryInterests().get(0).producedTransaction())
                            .as("a record is minted even though the amount it carries is zero")
                            .isTrue(),
                    () -> assertThat(closed.interestTransactions()).hasSize(1),
                    () -> assertThat(closed.interestTransactions().get(0).getTranAmt())
                            .isEqualTo(ZERO_MONETARY),
                    () -> assertThat(closed.totalInterest()).isEqualTo(ZERO_MONETARY),
                    () -> assertThat(closed.updatedAccount().getAcctCurrBal())
                            .as("the balance is unmoved because zero accrued, not because it skipped")
                            .isEqualTo(OPENING_BALANCE));
        }
    }

    /* ============================================================================================ */

    /**
     * Paragraph {@code 1050-UPDATE-ACCOUNT}: the account control break.
     *
     * <p>The paragraph adds the running total to the current balance and then zeroes <strong>both</strong>
     * cycle accumulators before rewriting the account. Zeroing only one would leave a half-closed cycle
     * that every later run would compound, which is why the two are asserted separately below.
     *
     * <h2>The second invocation site, and why it never runs</h2>
     *
     * <p>The source invokes this paragraph from two places. The first is the key-change control break.
     * The second sits in the {@code ELSE} arm of an end-of-file test that is itself inside a loop whose
     * termination condition is evaluated <em>before</em> each iteration, so the arm is unreachable: the
     * read paragraph raises the flag and the loop ends before the arm can be taken.
     *
     * <p>The implementation reproduces that. The run's final group is still accrued - its rows resolve
     * their rates, its records are written and its total is reported - but paragraph
     * {@code 1050-UPDATE-ACCOUNT} does not run for it, so the last account keeps its balance and keeps
     * both cycle accumulators. The module's own expected-output fixtures encode that unposted balance.
     * This nest asserts both halves: the final group <em>is</em> accrued and written, and it is
     * <strong>never</strong> rewritten. See {@code docs/decision-log.md} entry DL-207.
     */
    @Nested
    @DisplayName("1050-UPDATE-ACCOUNT: the control break folds the interest in and zeroes BOTH cycle "
            + "accumulators, once per group")
    class TheAccountControlBreak {

        /** Creates the nest. */
        TheAccountControlBreak() {
        }

        @Test
        @DisplayName("the accrued total reaches the balance AND both cycle accumulators are zeroed - "
                + "each asserted individually, because zeroing only one is the likely defect")
        void bothCycleAccumulatorsAreZeroedAndTheTotalReachesTheBalance() {
            givenGroupReadsResolve(account(ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE));
            givenAccountRewriteEchoes();
            givenEveryProbeResolvesAt(DECISIVE_RATE);

            accrueSingleRow(ACCOUNT_ID, DECISIVE_BALANCE);

            final ArgumentCaptor<Account> rewritten = ArgumentCaptor.forClass(Account.class);
            verify(InterestCalculationServiceTest.this.accountRepository)
                    .save(rewritten.capture());
            final Account posted = rewritten.getValue();
            assertAll(
                    () -> assertThat(posted.getAcctCurrBal())
                            .as("the opening balance plus exactly the accrued interest")
                            .isEqualTo(BALANCE_AFTER_DECISIVE_INTEREST),
                    () -> assertThat(posted.getAcctCurrBal().scale()).isEqualTo(MONETARY_SCALE),
                    () -> assertThat(posted.getAcctCurrCycCredit())
                            .as("the cycle CREDIT accumulator is zeroed")
                            .isEqualTo(ZERO_MONETARY),
                    () -> assertThat(posted.getAcctCurrCycCredit().scale())
                            .isEqualTo(MONETARY_SCALE),
                    () -> assertThat(posted.getAcctCurrCycDebit())
                            .as("the cycle DEBIT accumulator is zeroed as well - both, never one")
                            .isEqualTo(ZERO_MONETARY),
                    () -> assertThat(posted.getAcctCurrCycDebit().scale())
                            .isEqualTo(MONETARY_SCALE));
        }

        @Test
        @DisplayName("the accumulators really were non-zero before the break, so their zeroing is an "
                + "observed change rather than a value that happened to already be zero")
        void theAccumulatorsWereNonZeroBeforeTheBreak() {
            final Account before = account(ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE);

            assertAll(
                    () -> assertThat(before.getAcctCurrCycCredit())
                            .isEqualTo(OPENING_CYCLE_CREDIT)
                            .isNotEqualTo(ZERO_MONETARY),
                    () -> assertThat(before.getAcctCurrCycDebit())
                            .isEqualTo(OPENING_CYCLE_DEBIT)
                            .isNotEqualTo(ZERO_MONETARY));
        }

        @Test
        @DisplayName("the running total resets for every group, so one account's interest can never be "
                + "posted to the next")
        void theRunningTotalResetsForEveryGroup() {
            final Account first = account(ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE);
            final Account second = account(HIGHER_ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE);
            givenGroupReadsResolve(first);
            givenGroupReadsResolve(second);
            givenAccountRewriteEchoes();
            givenEveryProbeResolvesAt(DECISIVE_RATE);

            InterestCalculationServiceTest.this.service.calculateInterest(PINNED_RUN_DATE,
                    List.of(categoryBalance(ACCOUNT_ID, DECISIVE_BALANCE),
                            categoryBalance(HIGHER_ACCOUNT_ID, DECISIVE_BALANCE)),
                    recordSink(), groupSink());

            assertAll(
                    () -> assertThat(InterestCalculationServiceTest.this.closedGroups)
                            .as("two key changes, two closed groups")
                            .hasSize(2),
                    () -> assertThat(InterestCalculationServiceTest.this.closedGroups)
                            .extracting(
                                    InterestCalculationService.GroupInterestResult::totalInterest)
                            .as("each group carries its own interest, not a running sum")
                            .containsExactly(DECISIVE_INTEREST, DECISIVE_INTEREST),
                    () -> assertThat(InterestCalculationServiceTest.this.closedGroups)
                            .extracting(group -> group.updatedAccount().getAcctCurrBal())
                            .as("the key-change group carries its interest; the final group's balance "
                                    + "is left as read, because its break is the unreachable arm")
                            .containsExactly(BALANCE_AFTER_DECISIVE_INTEREST, OPENING_BALANCE));
        }

        @Test
        @DisplayName("the FINAL group at end of file is still accrued - its interest is computed, its "
                + "record is written and its total includes it - but its balance does NOT carry it and "
                + "its cycle accumulators are NOT reset, because the arm is unreachable")
        void theFinalGroupAtEndOfFileIsAccruedButNeverRewritten() {
            final Account first = account(ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE);
            final Account highest = account(HIGHER_ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE);
            givenGroupReadsResolve(first);
            givenGroupReadsResolve(highest);
            givenAccountRewriteEchoes();
            givenEveryProbeResolvesAt(DECISIVE_RATE);

            final InterestCalculationService.InterestRunResult run =
                    InterestCalculationServiceTest.this.service.calculateInterest(PINNED_RUN_DATE,
                            List.of(categoryBalance(ACCOUNT_ID, DECISIVE_BALANCE),
                                    categoryBalance(HIGHER_ACCOUNT_ID, DECISIVE_BALANCE)),
                            recordSink(), groupSink());

            final InterestCalculationService.GroupInterestResult last =
                    InterestCalculationServiceTest.this.closedGroups.get(1);
            assertAll(
                    () -> assertThat(run.groupCount())
                            .as("the highest-key group is closed, not dropped at end of file")
                            .isEqualTo(2),
                    () -> assertThat(run.transactionCount()).isEqualTo(2),
                    () -> assertThat(run.recordCount()).isEqualTo(2),
                    () -> assertThat(last.accountId()).isEqualTo(HIGHER_ACCOUNT_ID),
                    () -> assertThat(last.interestTransactions())
                            .as("the highest account's record IS written")
                            .hasSize(1),
                    () -> assertThat(last.totalInterest())
                            .as("and IS included in that group's interest total")
                            .isEqualTo(DECISIVE_INTEREST),
                    () -> assertThat(last.accountRewritten())
                            .as("the end-of-file arm is unreachable, so 1050-UPDATE-ACCOUNT does not "
                                    + "run for the final group")
                            .isFalse(),
                    () -> assertThat(last.updatedAccount().getAcctCurrBal())
                            .as("so the highest account's balance still EXCLUDES the interest it "
                                    + "accrued - see decision-log entry DL-207")
                            .isEqualTo(OPENING_BALANCE),
                    () -> assertThat(last.updatedAccount().getAcctCurrCycCredit())
                            .as("and its cycle CREDIT accumulator is left exactly as it was read")
                            .isEqualTo(OPENING_CYCLE_CREDIT),
                    () -> assertThat(last.updatedAccount().getAcctCurrCycDebit())
                            .as("and its cycle DEBIT accumulator is left exactly as it was read")
                            .isEqualTo(OPENING_CYCLE_DEBIT),
                    () -> assertThat(InterestCalculationServiceTest.this.writtenRecords).hasSize(2));
        }

        @Test
        @DisplayName("the second update site stays unreachable: only the accounts a KEY CHANGE closed "
                + "are rewritten, so the run's last account is never rewritten at all")
        void onlyKeyChangeControlBreaksRewriteAnAccount() {
            final Account first = account(ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE);
            final Account highest = account(HIGHER_ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE);
            givenGroupReadsResolve(first);
            givenGroupReadsResolve(highest);
            givenAccountRewriteEchoes();
            givenEveryProbeResolvesAt(DECISIVE_RATE);

            InterestCalculationServiceTest.this.service.calculateInterest(PINNED_RUN_DATE,
                    List.of(categoryBalance(ACCOUNT_ID, DECISIVE_BALANCE),
                            categoryBalance(ACCOUNT_ID, SECOND_ROW_TYPE_CD, DECISIVE_BALANCE),
                            categoryBalance(HIGHER_ACCOUNT_ID, DECISIVE_BALANCE)),
                    recordSink(), groupSink());

            final ArgumentCaptor<Account> rewritten = ArgumentCaptor.forClass(Account.class);
            verify(InterestCalculationServiceTest.this.accountRepository, times(1))
                    .save(rewritten.capture());
            assertAll(
                    () -> assertThat(rewritten.getAllValues())
                            .extracting(Account::getAcctId)
                            .as("one key change, one rewrite - the last account's break is the "
                                    + "unreachable end-of-file arm")
                            .containsExactly(ACCOUNT_ID),
                    () -> assertThat(InterestCalculationServiceTest.this.closedGroups)
                            .extracting(
                                    InterestCalculationService.GroupInterestResult::accountRewritten)
                            .as("the key-change group is rewritten, the final group is not")
                            .containsExactly(true, false),
                    () -> assertThat(InterestCalculationServiceTest.this.closedGroups)
                            .extracting(
                                    InterestCalculationService.GroupInterestResult::totalInterest)
                            .as("the two-row group accrues twice, the one-row group once")
                            .containsExactly(TWO_DECISIVE_ROWS_ACCRUED, DECISIVE_INTEREST));
        }

        @Test
        @DisplayName("every record of a group reaches the writer BEFORE that group's account rewrite, "
                + "which is where the source's write sits relative to its control break")
        void everyRecordIsWrittenBeforeTheRewrite() {
            final List<String> sequence = new ArrayList<>();
            givenGroupReadsResolve(account(ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE));
            givenEveryProbeResolvesAt(DECISIVE_RATE);
            when(InterestCalculationServiceTest.this.accountRepository.save(any(Account.class)))
                    .thenAnswer(invocation -> {
                        sequence.add("rewrite");
                        return invocation.getArgument(0);
                    });

            InterestCalculationServiceTest.this.service.calculateGroupInterest(PINNED_RUN_DATE,
                    ACCOUNT_ID,
                    List.of(categoryBalance(ACCOUNT_ID, DECISIVE_BALANCE),
                            categoryBalance(ACCOUNT_ID, SECOND_ROW_TYPE_CD, DECISIVE_BALANCE)),
                    0L, synthesized -> sequence.add("write:" + synthesized.getTranId()));

            assertThat(sequence)
                    .containsExactly("write:" + PINNED_RUN_DATE + "000001",
                            "write:" + PINNED_RUN_DATE + "000002",
                            "rewrite");
        }

        @Test
        @DisplayName("a writer that fails stops the group before the account is rewritten, so a "
                + "hardened balance can never outlive the records that hardened it")
        void aFailingWriterPreventsTheRewrite() {
            givenGroupReadsResolve(account(ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE));
            givenEveryProbeResolvesAt(DECISIVE_RATE);
            final AbendException writeFailure =
                    new AbendException(PROGRAM_NAME, "ERROR WRITING TRANSACTION RECORD");

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> InterestCalculationServiceTest.this.service
                            .calculateGroupInterest(PINNED_RUN_DATE, ACCOUNT_ID,
                                    List.of(categoryBalance(ACCOUNT_ID, DECISIVE_BALANCE)), 0L,
                                    synthesized -> {
                                        throw writeFailure;
                                    }))
                    .isSameAs(writeFailure);

            verify(InterestCalculationServiceTest.this.accountRepository, never())
                    .save(any(Account.class));
        }

        @Test
        @DisplayName("an unwritable account master is the rewrite ERROR arm: it reports the rewrite "
                + "operation on the account dataset and abends")
        void anUnwritableAccountMasterAbends() {
            givenGroupReadsResolve(account(ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE));
            givenEveryProbeResolvesAt(DECISIVE_RATE);
            when(InterestCalculationServiceTest.this.accountRepository.save(any(Account.class)))
                    .thenThrow(new QueryTimeoutException("the account master is unwritable"));

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> accrueSingleRow(ACCOUNT_ID, DECISIVE_BALANCE))
                    .satisfies(abend -> assertThat(abend.culprit()).isEqualTo(PROGRAM_NAME));

            verify(InterestCalculationServiceTest.this.abendService)
                    .abendOnFileStatus(eq(PROGRAM_NAME), any(String.class), eq(OPERATION_REWRITE),
                            any(String.class));
        }

        @Test
        @DisplayName("a concurrent modification of the account propagates untranslated, because this "
                + "member declares no conflict-handling arm at all")
        void aConcurrentModificationPropagatesUntranslated() {
            givenGroupReadsResolve(account(ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE));
            givenEveryProbeResolvesAt(DECISIVE_RATE);
            final OptimisticLockingFailureException conflict =
                    new OptimisticLockingFailureException("the account changed underneath the run");
            when(InterestCalculationServiceTest.this.accountRepository.save(any(Account.class)))
                    .thenThrow(conflict);

            assertThatExceptionOfType(OptimisticLockingFailureException.class)
                    .isThrownBy(() -> accrueSingleRow(ACCOUNT_ID, DECISIVE_BALANCE))
                    .isSameAs(conflict);

            verify(InterestCalculationServiceTest.this.abendService, never())
                    .abendOnFileStatus(any(String.class), any(String.class), any(String.class),
                            any(String.class));
        }

        @Test
        @DisplayName("an empty source reads nothing, closes no group and touches no account")
        void anEmptySourceClosesNoGroup() {
            final InterestCalculationService.InterestRunResult run =
                    InterestCalculationServiceTest.this.service.calculateInterest(PINNED_RUN_DATE,
                            List.of(), recordSink(), groupSink());

            assertAll(
                    () -> assertThat(run.groupCount()).isZero(),
                    () -> assertThat(run.transactionCount()).isZero(),
                    () -> assertThat(run.recordCount()).isZero(),
                    () -> assertThat(run.lastTranIdSuffix()).isZero(),
                    () -> assertThat(run.rateGateSkipped()).isFalse(),
                    () -> assertThat(run.defaultGroupUsed()).isFalse(),
                    () -> assertThat(InterestCalculationServiceTest.this.closedGroups).isEmpty(),
                    () -> verifyNoInteractions(
                            InterestCalculationServiceTest.this.accountRepository),
                    () -> verifyNoInteractions(
                            InterestCalculationServiceTest.this.crossReferenceRepository),
                    () -> verifyNoInteractions(
                            InterestCalculationServiceTest.this.disclosureGroupRepository));
        }
    }

    /* ============================================================================================ */

    /**
     * Paragraph {@code 1300-B-WRITE-TX}: the synthesized interest transaction, field by field.
     *
     * <p>Every literal asserted here is declared at the top of this class as a hand-written constant with
     * its padding spelled out, so no production formatter, codec or mapper contributes an expectation.
     */
    @Nested
    @DisplayName("1300-B-WRITE-TX: the synthesized record, every field at its declared width")
    class TheSynthesizedInterestTransaction {

        /** Creates the nest. */
        TheSynthesizedInterestTransaction() {
        }

        /**
         * Drives one accruing row and returns the record it synthesized.
         *
         * @return the single synthesized record
         */
        private Transaction singleSynthesizedRecord() {
            givenGroupReadsResolve(account(ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE));
            givenAccountRewriteEchoes();
            givenEveryProbeResolvesAt(DECISIVE_RATE);
            accrueSingleRow(ACCOUNT_ID, DECISIVE_BALANCE);
            assertThat(InterestCalculationServiceTest.this.writtenRecords).hasSize(1);
            return InterestCalculationServiceTest.this.writtenRecords.get(0);
        }

        @Test
        @DisplayName("the type code is the two-character interest literal and the category code is the "
                + "four-character zero-filled form - never the number five and never a bare \"5\"")
        void theTypeAndCategoryCodesCarryTheirLiterals() {
            final Transaction synthesized = singleSynthesizedRecord();

            assertAll(
                    () -> assertThat(synthesized.getTranTypeCd()).isEqualTo(INTEREST_TYPE_CD),
                    () -> assertThat(encodedBytes(synthesized.getTranTypeCd())).isEqualTo(2),
                    () -> assertThat(synthesized.getTranCatCd())
                            .as("a two-character literal moved into a four-digit field stores as four")
                            .isEqualTo(INTEREST_CAT_CD)
                            .isNotEqualTo("5")
                            .isNotEqualTo("05"),
                    () -> assertThat(encodedBytes(synthesized.getTranCatCd())).isEqualTo(4),
                    () -> assertThat(synthesized.getTranCatCd())
                            .as("it is text, so it keeps its leading zeros")
                            .startsWith("00"));
        }

        @Test
        @DisplayName("the source field carries the system literal WITH its four trailing spaces, ten "
                + "encoded bytes, untrimmed")
        void theSourceFieldCarriesItsFourTrailingSpaces() {
            final Transaction synthesized = singleSynthesizedRecord();

            assertAll(
                    () -> assertThat(synthesized.getTranSource()).isEqualTo(INTEREST_SOURCE),
                    () -> assertThat(encodedBytes(synthesized.getTranSource()))
                            .as("the receiving field is ten characters wide")
                            .isEqualTo(10),
                    () -> assertThat(synthesized.getTranSource())
                            .as("the padding is never trimmed away")
                            .endsWith(" ".repeat(4))
                            .isNotEqualTo("System"));
        }

        @Test
        @DisplayName("the amount is exactly the computed interest, and the merchant identifier is the "
                + "nine-character zero form rather than the number zero")
        void theAmountAndMerchantIdentifierCarryTheirContractForms() {
            final Transaction synthesized = singleSynthesizedRecord();

            assertAll(
                    () -> assertThat(synthesized.getTranAmt()).isEqualTo(DECISIVE_INTEREST),
                    () -> assertThat(synthesized.getTranAmt().scale()).isEqualTo(MONETARY_SCALE),
                    () -> assertThat(synthesized.getMerchantId())
                            .as("numeric zero moved into a nine-digit field stores as nine zeros")
                            .isEqualTo(MERCHANT_ID)
                            .isNotEqualTo("0"),
                    () -> assertThat(encodedBytes(synthesized.getMerchantId())).isEqualTo(9));
        }

        @Test
        @DisplayName("the merchant name, city and postcode are SPACES at their declared widths - not "
                + "null, not empty strings and not trimmed away")
        void theMerchantNameCityAndPostcodeAreSpacesAtTheirDeclaredWidths() {
            final Transaction synthesized = singleSynthesizedRecord();

            assertAll(
                    () -> assertThat(synthesized.getMerchantName())
                            .isNotNull()
                            .isNotEmpty()
                            .isEqualTo(" ".repeat(MERCHANT_NAME_WIDTH)),
                    () -> assertThat(encodedBytes(synthesized.getMerchantName()))
                            .isEqualTo(MERCHANT_NAME_WIDTH),
                    () -> assertThat(synthesized.getMerchantCity())
                            .isNotNull()
                            .isNotEmpty()
                            .isEqualTo(" ".repeat(MERCHANT_CITY_WIDTH)),
                    () -> assertThat(encodedBytes(synthesized.getMerchantCity()))
                            .isEqualTo(MERCHANT_CITY_WIDTH),
                    () -> assertThat(synthesized.getMerchantZip())
                            .isNotNull()
                            .isNotEmpty()
                            .isEqualTo(" ".repeat(MERCHANT_ZIP_WIDTH)),
                    () -> assertThat(encodedBytes(synthesized.getMerchantZip()))
                            .isEqualTo(MERCHANT_ZIP_WIDTH));
        }

        @Test
        @DisplayName("the card number comes from the cross-reference the group read, at its full "
                + "sixteen encoded bytes")
        void theCardNumberComesFromTheCrossReference() {
            final Transaction synthesized = singleSynthesizedRecord();

            assertAll(
                    () -> assertThat(SensitiveValues.fingerprint(synthesized.getTranCardNum()))
                            .isEqualTo(SensitiveValues.fingerprint(XREF_CARD_NUM)),
                    () -> assertThat(encodedBytes(synthesized.getTranCardNum())).isEqualTo(16),
                    () -> assertThat(synthesized.getTranCardNum())
                            .as("it is the cross-reference's card number, not the account identifier")
                            .isNotEqualTo(ACCOUNT_ID));
        }

        @Test
        @DisplayName("the origination and processing timestamps are BYTE-IDENTICAL to each other, each "
                + "exactly twenty-six encoded bytes, and carry the BATCH form and not the online one")
        void theTwoTimestampsAreByteIdenticalAndCarryTheBatchForm() {
            final Transaction synthesized = singleSynthesizedRecord();

            assertAll(
                    () -> assertThat(synthesized.getTranOrigTs())
                            .as("assembled once and moved into both fields")
                            .isEqualTo(synthesized.getTranProcTs()),
                    () -> assertThat(synthesized.getTranOrigTs().getBytes(StandardCharsets.US_ASCII))
                            .isEqualTo(synthesized.getTranProcTs()
                                    .getBytes(StandardCharsets.US_ASCII)),
                    () -> assertThat(encodedBytes(synthesized.getTranOrigTs()))
                            .isEqualTo(TIMESTAMP_WIDTH),
                    () -> assertThat(encodedBytes(synthesized.getTranProcTs()))
                            .isEqualTo(TIMESTAMP_WIDTH),
                    () -> assertThat(synthesized.getTranOrigTs())
                            .as("the batch form the pinned instant must assemble to")
                            .isEqualTo(ORACLE_BATCH_TIMESTAMP),
                    () -> assertThat(synthesized.getTranOrigTs())
                            .as("never the online form, which is the same length and wrong in three "
                                    + "character positions")
                            .isNotEqualTo(ONLINE_FORM_OF_PINNED_INSTANT),
                    () -> assertThat(synthesized.getTranOrigTs().charAt(10))
                            .as("the batch form separates the date from the time with a hyphen")
                            .isEqualTo('-'),
                    () -> assertThat(synthesized.getTranOrigTs().charAt(13))
                            .as("and separates the time's parts with dots, never colons")
                            .isEqualTo('.'),
                    () -> assertThat(synthesized.getTranOrigTs().charAt(16)).isEqualTo('.'),
                    () -> assertThat(synthesized.getTranOrigTs().charAt(19)).isEqualTo('.'),
                    () -> assertThat(synthesized.getTranOrigTs())
                            .as("and ends with the literal four-character tail")
                            .endsWith("0000"));
        }

        @Test
        @DisplayName("the hundredths field really is two digits: a clock reading of seventy "
                + "milliseconds renders as 07 rather than as a truncated fraction")
        void theHundredthsFieldIsTwoDigits() {
            InterestCalculationServiceTest.this.service =
                    InterestCalculationServiceTest.this.serviceOn(PINNED_INSTANT_WITH_HUNDREDTHS);
            final Transaction synthesized = singleSynthesizedRecord();

            assertAll(
                    () -> assertThat(synthesized.getTranOrigTs())
                            .isEqualTo(ORACLE_BATCH_TIMESTAMP_WITH_HUNDREDTHS),
                    () -> assertThat(encodedBytes(synthesized.getTranOrigTs()))
                            .isEqualTo(TIMESTAMP_WIDTH),
                    () -> assertThat(synthesized.getTranOrigTs().substring(20, 22))
                            .as("two hundredths digits, then the literal tail")
                            .isEqualTo("07"));
        }

        @Test
        @DisplayName("the identifier is exactly sixteen encoded bytes: the ten-character pinned run "
                + "date followed by a SIX-DIGIT zero-padded suffix, and it is a String")
        void theIdentifierIsSixteenBytesOfPinnedDateAndZeroPaddedSuffix() {
            final Transaction synthesized = singleSynthesizedRecord();

            final String tranId = synthesized.getTranId();
            assertAll(
                    () -> assertThat(encodedBytes(tranId)).isEqualTo(TRAN_ID_WIDTH),
                    () -> assertThat(tranId.substring(0, 10))
                            .as("the first ten characters are the pinned run-date parameter, verbatim")
                            .isEqualTo(PINNED_RUN_DATE),
                    () -> assertThat(tranId.substring(10))
                            .as("a suffix of one renders as six characters, not one")
                            .isEqualTo("000001")
                            .hasSize(6),
                    () -> assertThat(tranId).isEqualTo(PINNED_RUN_DATE + "000001"),
                    () -> assertThat(tranId)
                            .as("the identifier is text, never a numeric type")
                            .isInstanceOf(String.class));
        }

        @Test
        @DisplayName("consecutive identifiers within one run are DISTINCT and their suffix advances by "
                + "one, because the suffix belongs to the run rather than to a group")
        void consecutiveIdentifiersAdvanceTheirSuffix() {
            final Account first = account(ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE);
            final Account highest = account(HIGHER_ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE);
            givenGroupReadsResolve(first);
            givenGroupReadsResolve(highest);
            givenAccountRewriteEchoes();
            givenEveryProbeResolvesAt(DECISIVE_RATE);

            final InterestCalculationService.InterestRunResult run =
                    InterestCalculationServiceTest.this.service.calculateInterest(PINNED_RUN_DATE,
                            List.of(categoryBalance(ACCOUNT_ID, DECISIVE_BALANCE),
                                    categoryBalance(ACCOUNT_ID, SECOND_ROW_TYPE_CD,
                                            DECISIVE_BALANCE),
                                    categoryBalance(HIGHER_ACCOUNT_ID, DECISIVE_BALANCE)),
                            recordSink(), groupSink());

            assertAll(
                    () -> assertThat(InterestCalculationServiceTest.this.writtenRecords)
                            .extracting(Transaction::getTranId)
                            .containsExactly(PINNED_RUN_DATE + "000001",
                                    PINNED_RUN_DATE + "000002",
                                    PINNED_RUN_DATE + "000003")
                            .doesNotHaveDuplicates(),
                    () -> assertThat(InterestCalculationServiceTest.this.writtenRecords)
                            .allSatisfy(record -> assertThat(encodedBytes(record.getTranId()))
                                    .isEqualTo(TRAN_ID_WIDTH)),
                    () -> assertThat(run.lastTranIdSuffix())
                            .as("the suffix crossed the group boundary rather than restarting")
                            .isEqualTo(3L));
        }

        @Test
        @DisplayName("no database sequence is consulted: the identifier is derived from the pinned "
                + "parameters alone, so an identical run mints identical identifiers")
        void noDatabaseSequenceIsConsulted() {
            givenGroupReadsResolve(account(ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE));
            givenAccountRewriteEchoes();
            givenEveryProbeResolvesAt(DECISIVE_RATE);

            final InterestCalculationService.GroupInterestResult firstRun =
                    accrueSingleRow(ACCOUNT_ID, DECISIVE_BALANCE);
            final InterestCalculationService.GroupInterestResult repeatedRun =
                    accrueSingleRow(ACCOUNT_ID, DECISIVE_BALANCE);

            assertAll(
                    () -> assertThat(firstRun.interestTransactions().get(0).getTranId())
                            .as("a sequence would have advanced between the two runs")
                            .isEqualTo(repeatedRun.interestTransactions().get(0).getTranId())
                            .isEqualTo(PINNED_RUN_DATE + "000001"),
                    () -> verifyNoInteractions(
                            InterestCalculationServiceTest.this.categoryBalanceRepository));
        }

        @Test
        @DisplayName("a caller-supplied starting suffix is continued rather than reset, which is how a "
                + "run threads one six-digit counter through every group it closes")
        void aCallerSuppliedStartingSuffixIsContinued() {
            givenGroupReadsResolve(account(ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE));
            givenAccountRewriteEchoes();
            givenEveryProbeResolvesAt(DECISIVE_RATE);

            final InterestCalculationService.GroupInterestResult closed =
                    InterestCalculationServiceTest.this.service.calculateGroupInterest(
                            PINNED_RUN_DATE, ACCOUNT_ID,
                            List.of(categoryBalance(ACCOUNT_ID, DECISIVE_BALANCE)), 41L,
                            recordSink());

            assertAll(
                    () -> assertThat(closed.interestTransactions().get(0).getTranId())
                            .isEqualTo(PINNED_RUN_DATE + "000042"),
                    () -> assertThat(encodedBytes(closed.interestTransactions().get(0).getTranId()))
                            .as("a wider suffix still lands in six positions")
                            .isEqualTo(TRAN_ID_WIDTH),
                    () -> assertThat(closed.lastTranIdSuffix()).isEqualTo(42L));
        }
    }

    /* ============================================================================================ */

    /**
     * The description: a bounded 24-character write into a 100-character field.
     *
     * <p>The source assembles it with a concatenation that has <strong>no pointer, no overflow clause and
     * no prior initialisation</strong>, so the write lands at the left of the field and positions 25
     * through 100 are left exactly as they were.
     *
     * <h2>Where the seam is, and what is therefore asserted</h2>
     *
     * <p>The implementation carries the <strong>24-character sending value</strong> and leaves the
     * fixed-width rendering to the record mapper that writes the 350-byte image, so there is no
     * production seam on this service through which a pre-populated 100-character receiver could be
     * supplied. Two things are asserted instead, and together they cover the mechanism rather than only
     * the happy-path output:
     *
     * <ol>
     *   <li>the production value is exactly the 24-character bounded write - the 13-character prefix,
     *       trailing space included, followed by the eleven-digit account identifier - and is
     *       <strong>not</strong> padded, blanked or rebuilt to the field's full width; and</li>
     *   <li>overlaying that value onto a 100-character receiver with this class's own oracle leaves the
     *       receiver's tail untouched, and on a freshly blank receiver produces 24 characters followed by
     *       76 spaces at exactly 100 encoded bytes.</li>
     * </ol>
     *
     * <p>An implementation that space-filled the value to 100, or that rebuilt the whole field, would fail
     * the first assertion; one that wrote past position 24 would fail the second.
     */
    @Nested
    @DisplayName("the description is a bounded 24-character write into a 100-character field, leaving "
            + "positions 25 to 100 untouched")
    class TheDescriptionResidue {

        /** Creates the nest. */
        TheDescriptionResidue() {
        }

        /**
         * Drives one accruing row and returns the description it wrote.
         *
         * @return the description exactly as the service assembled it
         */
        private String writtenDescription() {
            givenGroupReadsResolve(account(ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE));
            givenAccountRewriteEchoes();
            givenEveryProbeResolvesAt(DECISIVE_RATE);
            accrueSingleRow(ACCOUNT_ID, DECISIVE_BALANCE);
            return InterestCalculationServiceTest.this.writtenRecords.get(0).getTranDesc();
        }

        @Test
        @DisplayName("the write is exactly 24 characters: the 13-character prefix INCLUDING its trailing "
                + "space, then the account identifier at its full eleven digits")
        void theWriteIsExactlyTwentyFourCharacters() {
            final String description = writtenDescription();

            assertAll(
                    () -> assertThat(description).isEqualTo(DESCRIPTION_PREFIX + ACCOUNT_ID),
                    () -> assertThat(encodedBytes(description))
                            .as("the sender occupies 24 of the field's 100 positions")
                            .isEqualTo(DESCRIPTION_WRITE_WIDTH),
                    () -> assertThat(encodedBytes(DESCRIPTION_PREFIX))
                            .as("the literal's trailing space is part of it")
                            .isEqualTo(13),
                    () -> assertThat(DESCRIPTION_PREFIX).endsWith(" "),
                    () -> assertThat(description.substring(13))
                            .as("the identifier is rendered at its full eleven digits, zero-filled")
                            .isEqualTo(ACCOUNT_ID)
                            .hasSize(11));
        }

        @Test
        @DisplayName("the write does NOT pad, blank or rebuild the 100-character field: the value the "
                + "service carries stops at position 24, which is what leaves a residue possible")
        void theWriteDoesNotPadOrRebuildTheWholeField() {
            final String description = writtenDescription();

            assertAll(
                    () -> assertThat(encodedBytes(description))
                            .as("an implementation that space-filled to the field width would fail here")
                            .isNotEqualTo(TRAN_DESC_WIDTH)
                            .isEqualTo(DESCRIPTION_WRITE_WIDTH),
                    () -> assertThat(description)
                            .as("nothing was appended beyond the account identifier")
                            .doesNotEndWith(" ")
                            .isEqualTo(DESCRIPTION_PREFIX + ACCOUNT_ID));
        }

        @Test
        @DisplayName("overlaid onto a PRE-POPULATED 100-character receiver the tail is RETAINED, which "
                + "is the residue the source's uninitialised concatenation leaves behind")
        void aPrePopulatedTailIsRetained() {
            final String prePopulated = "Z".repeat(TRAN_DESC_WIDTH);
            final String description = writtenDescription();

            final String receiver = overlayLeft(description, prePopulated);

            assertAll(
                    () -> assertThat(encodedBytes(receiver)).isEqualTo(TRAN_DESC_WIDTH),
                    () -> assertThat(receiver.substring(0, DESCRIPTION_WRITE_WIDTH))
                            .as("positions 1 to 24 carry the write")
                            .isEqualTo(DESCRIPTION_PREFIX + ACCOUNT_ID),
                    () -> assertThat(receiver.substring(DESCRIPTION_WRITE_WIDTH))
                            .as("positions 25 to 100 are untouched - not blanked and not rebuilt")
                            .isEqualTo("Z".repeat(TRAN_DESC_WIDTH - DESCRIPTION_WRITE_WIDTH)));
        }

        @Test
        @DisplayName("overlaid onto a freshly blank 100-character receiver the observable output is 24 "
                + "characters followed by 76 spaces, at exactly 100 encoded bytes")
        void aFreshlyBlankReceiverYieldsTwentyFourThenSeventySixSpaces() {
            final String freshlyBlank = " ".repeat(TRAN_DESC_WIDTH);
            final String description = writtenDescription();

            final String receiver = overlayLeft(description, freshlyBlank);

            assertAll(
                    () -> assertThat(encodedBytes(receiver)).isEqualTo(TRAN_DESC_WIDTH),
                    () -> assertThat(receiver)
                            .isEqualTo(DESCRIPTION_PREFIX + ACCOUNT_ID
                                    + " ".repeat(TRAN_DESC_WIDTH - DESCRIPTION_WRITE_WIDTH)),
                    () -> assertThat(receiver.substring(DESCRIPTION_WRITE_WIDTH))
                            .isEqualTo(" ".repeat(76))
                            .hasSize(76));
        }

        @Test
        @DisplayName("the higher account's description carries ITS own identifier, so the write is "
                + "per-group rather than assembled once and reused")
        void eachGroupWritesItsOwnIdentifierIntoTheDescription() {
            final Account first = account(ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE);
            final Account highest = account(HIGHER_ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE);
            givenGroupReadsResolve(first);
            givenGroupReadsResolve(highest);
            givenAccountRewriteEchoes();
            givenEveryProbeResolvesAt(DECISIVE_RATE);

            InterestCalculationServiceTest.this.service.calculateInterest(PINNED_RUN_DATE,
                    List.of(categoryBalance(ACCOUNT_ID, DECISIVE_BALANCE),
                            categoryBalance(HIGHER_ACCOUNT_ID, DECISIVE_BALANCE)),
                    recordSink(), groupSink());

            assertThat(InterestCalculationServiceTest.this.writtenRecords)
                    .extracting(Transaction::getTranDesc)
                    .containsExactly(DESCRIPTION_PREFIX + ACCOUNT_ID,
                            DESCRIPTION_PREFIX + HIGHER_ACCOUNT_ID);
        }
    }

    /* ============================================================================================ */

    /**
     * Paragraphs {@code 9910-DISPLAY-IO-STATUS} and {@code 9999-ABEND-PROGRAM}, and the two-level status
     * model the whole batch tier branches on.
     *
     * <p>Two levels, not one. A raw two-byte status is normalised first into a coarse success,
     * end-of-file or error outcome, and the program then branches on the coarse value. The coarse level is
     * a private nested type of the service that owns it, so it is exercised <strong>through that
     * owner</strong>: a success continues, an end-of-file terminates the scan normally, and an error
     * abends. Only three raw literals are compared anywhere in the estate, and no test here depends on
     * any other.
     */
    @Nested
    @DisplayName("9910-DISPLAY-IO-STATUS and 9999-ABEND-PROGRAM: two status levels, three raw literals, "
            + "and emit before raise")
    class TheStatusModelAndTheAbendPath {

        /** Creates the nest. */
        TheStatusModelAndTheAbendPath() {
        }

        @ParameterizedTest(name = "raw status {0} is part of the estate's compared vocabulary")
        @ValueSource(strings = {STATUS_SUCCESS, STATUS_END_OF_FILE, STATUS_RECORD_NOT_FOUND})
        @DisplayName("the three raw literals the estate actually compares each resolve to a declared "
                + "constant, and the enumeration answers an unknown code without throwing")
        void theThreeComparedRawLiteralsResolve(final String rawStatus) {
            assertAll(
                    () -> assertThat(FileStatus.fromCode(rawStatus))
                            .as("the raw code is declared")
                            .isPresent(),
                    () -> assertThat(FileStatus.fromCode(rawStatus).orElseThrow().getCode())
                            .isEqualTo(rawStatus),
                    () -> assertThat(encodedBytes(rawStatus))
                            .as("a raw status is two bytes and keeps its leading zero")
                            .isEqualTo(FileStatusException.CODE_LENGTH));
        }

        @Test
        @DisplayName("success and end-of-file are distinguished at the raw level, because folding them "
                + "together would turn the normal end of a scan into a failure")
        void successAndEndOfFileAreDistinguishedAtTheRawLevel() {
            assertAll(
                    () -> assertThat(FileStatus.SUCCESS.getCode()).isEqualTo(STATUS_SUCCESS),
                    () -> assertThat(FileStatus.SUCCESS.isSuccess()).isTrue(),
                    () -> assertThat(FileStatus.SUCCESS.isEndOfFile()).isFalse(),
                    () -> assertThat(FileStatus.END_OF_FILE.getCode())
                            .isEqualTo(STATUS_END_OF_FILE),
                    () -> assertThat(FileStatus.END_OF_FILE.isEndOfFile()).isTrue(),
                    () -> assertThat(FileStatus.END_OF_FILE.isSuccess()).isFalse(),
                    () -> assertThat(FileStatus.RECORD_NOT_FOUND.getCode())
                            .isEqualTo(STATUS_RECORD_NOT_FOUND),
                    () -> assertThat(FileStatus.RECORD_NOT_FOUND.isSuccess()).isFalse(),
                    () -> assertThat(FileStatus.RECORD_NOT_FOUND.isEndOfFile())
                            .as("a missing record is neither a success nor the end of a scan")
                            .isFalse());
        }

        @Test
        @DisplayName("the COARSE outcome is exercised through its owner: a resolved read continues, an "
                + "exhausted source terminates the scan normally, and a failure abends")
        void theCoarseOutcomeIsExercisedThroughItsOwner() {
            givenGroupReadsResolve(account(ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE));
            // No account rewrite is stubbed, for the reason given on aRunCarriesNoCollection.
            givenEveryProbeResolvesAt(DECISIVE_RATE);

            final InterestCalculationService.InterestRunResult run =
                    InterestCalculationServiceTest.this.service.calculateInterest(PINNED_RUN_DATE,
                            List.of(categoryBalance(ACCOUNT_ID, DECISIVE_BALANCE)),
                            recordSink(), groupSink());

            assertAll(
                    () -> assertThat(run.recordCount())
                            .as("the success outcome let the row through")
                            .isEqualTo(1),
                    () -> assertThat(run.groupCount())
                            .as("the end-of-file outcome ended the scan normally and closed the group, "
                                    + "so it was never mistaken for the error outcome")
                            .isEqualTo(1),
                    () -> assertThat(loggedMessages())
                            .as("the run reported its normal end rather than an abend")
                            .contains("END OF EXECUTION OF PROGRAM CBACT04C"));
        }

        @Test
        @DisplayName("an exhausted source is NOT an error: a run over an empty source terminates "
                + "normally and never reaches the abend collaborator")
        void anExhaustedSourceIsNotAnError() {
            InterestCalculationServiceTest.this.service.calculateInterest(PINNED_RUN_DATE, List.of(),
                    recordSink(), groupSink());

            assertAll(
                    () -> assertThat(loggedMessages())
                            .contains("START OF EXECUTION OF PROGRAM CBACT04C",
                                    "END OF EXECUTION OF PROGRAM CBACT04C"),
                    () -> verifyNoInteractions(
                            InterestCalculationServiceTest.this.abendService));
        }

        @Test
        @DisplayName("the error carrier models the ERROR arm alone: constructing it with success or "
                + "with end-of-file is refused, because neither is an error")
        void theErrorCarrierRefusesSuccessAndEndOfFile() {
            assertAll(
                    () -> assertThatIllegalArgumentException()
                            .as("success is not an error")
                            .isThrownBy(() -> new FileStatusException(STATUS_SUCCESS, OPERATION_READ,
                                    RESOURCE_DISCGRP)),
                    () -> assertThatIllegalArgumentException()
                            .as("end of file is how a scan ends normally")
                            .isThrownBy(() -> new FileStatusException(STATUS_END_OF_FILE,
                                    OPERATION_READ, RESOURCE_DISCGRP)),
                    () -> assertThat(new FileStatusException(STATUS_RECORD_NOT_FOUND, OPERATION_READ,
                            RESOURCE_DISCGRP))
                            .as("a missing record IS an error and is accepted")
                            .satisfies(failure -> assertAll(
                                    () -> assertThat(failure.code())
                                            .isEqualTo(STATUS_RECORD_NOT_FOUND),
                                    () -> assertThat(failure.firstByte()).isEqualTo('2'),
                                    () -> assertThat(failure.secondByte()).isEqualTo('3'),
                                    () -> assertThat(failure.operation()).isEqualTo(OPERATION_READ),
                                    () -> assertThat(failure.resourceName())
                                            .isEqualTo(RESOURCE_DISCGRP))),
                    () -> assertThat(FileStatusException.DISPLAY_PREFIX)
                            .as("the fixed display prefix the status paragraph writes")
                            .isEqualTo("FILE STATUS IS: NNNN"));
        }

        @Test
        @DisplayName("nothing here depends on the two documented-but-never-compared codes: this class's "
                + "whole status vocabulary is the three literals the estate actually compares")
        void noAssertionDependsOnTheDocumentedButUnexercisedCodes() {
            final List<String> vocabularyUsedHere =
                    List.of(STATUS_SUCCESS, STATUS_END_OF_FILE, STATUS_RECORD_NOT_FOUND);

            assertAll(
                    () -> assertThat(vocabularyUsedHere)
                            .as("three literals, and no fourth")
                            .hasSize(3)
                            .doesNotHaveDuplicates(),
                    () -> assertThat(vocabularyUsedHere)
                            .as("the duplicate-key and file-not-found codes are declared for "
                                    + "documentation reach and are compared nowhere in the estate, so "
                                    + "no behaviour here may turn on either")
                            .doesNotContain(FileStatus.DUPLICATE_KEY.getCode(),
                                    FileStatus.FILE_NOT_FOUND.getCode()),
                    () -> assertThat(vocabularyUsedHere)
                            .allSatisfy(rawStatus -> assertThat(encodedBytes(rawStatus))
                                    .isEqualTo(FileStatusException.CODE_LENGTH)));
        }

        @Test
        @DisplayName("the abend path EMITS BEFORE IT RAISES: the raw two-byte status is already in the "
                + "log when the abend leaves the service")
        void theAbendPathEmitsBeforeItRaises() {
            givenGroupReadsResolve(account(ACCOUNT_ID, SEEDED_BLANK_GROUP_ID, OPENING_BALANCE));
            when(InterestCalculationServiceTest.this.disclosureGroupRepository.findById(any()))
                    .thenReturn(Optional.empty());

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> accrueSingleRow(ACCOUNT_ID, DECISIVE_BALANCE))
                    .satisfies(abend -> assertAll(
                            () -> assertThat(loggedMessages())
                                    .as("the diagnostic is ALREADY captured at the moment the abend "
                                            + "arrives, so nothing outran its own report")
                                    .contains("ERROR READING DEFAULT DISCLOSURE GROUP"),
                            () -> assertThat(abend.reason())
                                    .as("the abend carries the raw two-byte status")
                                    .contains(STATUS_RECORD_NOT_FOUND),
                            () -> assertThat(abend.culprit())
                                    .as("the culprit is the PROGRAM NAME, at the culprit field's width")
                                    .isEqualTo(PROGRAM_NAME)
                                    .hasSize(AbendException.CULPRIT_LENGTH),
                            () -> assertThat(abend.code())
                                    .as("the BATCH abend code, never the online one")
                                    .isEqualTo(AbendException.BATCH_ABEND_CODE)
                                    .isNotEqualTo(AbendException.ONLINE_ABEND_CODE),
                            () -> assertThat(abend.getMessage())
                                    .isEqualTo(AbendException.DEFAULT_MESSAGE),
                            () -> assertThat(abend.toFixedWidthContext())
                                    .as("the abend context is rendered at its declared total width")
                                    .hasSize(AbendException.CONTEXT_LENGTH)));
        }

        @Test
        @DisplayName("the status display happens before the abend call, in that order, and the status "
                + "reported is the raw two-byte code on the failing dataset")
        void theStatusDisplayPrecedesTheAbendCall() {
            givenGroupReadsResolve(account(ACCOUNT_ID, SEEDED_BLANK_GROUP_ID, OPENING_BALANCE));
            when(InterestCalculationServiceTest.this.disclosureGroupRepository.findById(any()))
                    .thenReturn(Optional.empty());

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> accrueSingleRow(ACCOUNT_ID, DECISIVE_BALANCE));

            final InOrder emitThenRaise = inOrder(InterestCalculationServiceTest.this.abendService);
            emitThenRaise.verify(InterestCalculationServiceTest.this.abendService)
                    .displayIoStatus(STATUS_RECORD_NOT_FOUND, OPERATION_READ, RESOURCE_DISCGRP);
            emitThenRaise.verify(InterestCalculationServiceTest.this.abendService)
                    .abendOnFileStatus(PROGRAM_NAME, STATUS_RECORD_NOT_FOUND, OPERATION_READ,
                            RESOURCE_DISCGRP);
            assertAll(
                    () -> verify(InterestCalculationServiceTest.this.abendService, never())
                            .abendOnline(any(String.class), any(String.class)),
                    () -> verify(InterestCalculationServiceTest.this.abendService, never())
                            .abendBatch(any(String.class), any(String.class)));
        }

        @Test
        @DisplayName("the abend context's four fields sit at their declared widths, so a Java log line "
                + "and a mainframe abend name the same culprit at the same length")
        void theAbendContextCarriesTheDeclaredFieldWidths() {
            final AbendException abend = new AbendException(PROGRAM_NAME, "FILE STATUS 23");

            final String context = abend.toFixedWidthContext();
            assertAll(
                    () -> assertThat(encodedBytes(context))
                            .isEqualTo(AbendException.CONTEXT_LENGTH),
                    () -> assertThat(AbendException.CONTEXT_LENGTH)
                            .as("the total is the sum of the four declared field widths")
                            .isEqualTo(AbendException.CODE_LENGTH + AbendException.CULPRIT_LENGTH
                                    + AbendException.REASON_LENGTH
                                    + AbendException.MESSAGE_LENGTH),
                    () -> assertThat(context.substring(0, AbendException.CODE_LENGTH))
                            .as("the code field is left-justified in four positions")
                            .isEqualTo(AbendException.BATCH_ABEND_CODE + " "),
                    () -> assertThat(context.substring(AbendException.CODE_LENGTH,
                            AbendException.CODE_LENGTH + AbendException.CULPRIT_LENGTH))
                            .as("the culprit field carries the program name")
                            .isEqualTo(PROGRAM_NAME));
        }
    }

    /* ============================================================================================ */

    /**
     * Paragraphs {@code 1000-TCATBALF-GET-NEXT}, {@code 1100-GET-ACCT-DATA} and
     * {@code 1110-GET-XREF-DATA}: the sequential scan and the two keyed reads.
     *
     * <p>The scan's <strong>order comes from the service</strong>, not from the repository: the repository
     * imposes none of its own, so the service advances an exclusive composite-key cursor and the rows
     * arrive account first, then type code, then category code, ascending. That order is what makes the
     * control break group an account's rows together instead of breaking on almost every row.
     */
    @Nested
    @DisplayName("1000-TCATBALF-GET-NEXT, 1100-GET-ACCT-DATA and 1110-GET-XREF-DATA: the scan order is "
            + "the service's, and a missing key abends")
    class TheSequentialScanAndTheKeyedReads {

        /** Creates the nest. */
        TheSequentialScanAndTheKeyedReads() {
        }

        @Test
        @DisplayName("the whole-master scan asks for ASCENDING composite-key order and advances an "
                + "exclusive cursor, starting from the unconstrained low-values key")
        void theWholeMasterScanIsAscendingOnTheCompositeKey() {
            final Account first = account(ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE);
            final Account highest = account(HIGHER_ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE);
            givenGroupReadsResolve(first);
            givenGroupReadsResolve(highest);
            givenAccountRewriteEchoes();
            givenEveryProbeResolvesAt(DECISIVE_RATE);
            when(InterestCalculationServiceTest.this.categoryBalanceRepository
                    .findAfterKey(any(), any(), any(), any(Pageable.class)))
                    .thenReturn(List.of(categoryBalance(ACCOUNT_ID, ROW_TYPE_CD, DECISIVE_BALANCE),
                            categoryBalance(ACCOUNT_ID, SECOND_ROW_TYPE_CD, DECISIVE_BALANCE),
                            categoryBalance(HIGHER_ACCOUNT_ID, ROW_TYPE_CD, DECISIVE_BALANCE)))
                    .thenReturn(List.of());

            final InterestCalculationService.InterestRunResult run =
                    InterestCalculationServiceTest.this.service.calculateInterest(PINNED_RUN_DATE,
                            recordSink(), groupSink());

            final ArgumentCaptor<String> accountCursor = ArgumentCaptor.forClass(String.class);
            final ArgumentCaptor<String> typeCursor = ArgumentCaptor.forClass(String.class);
            final ArgumentCaptor<String> categoryCursor = ArgumentCaptor.forClass(String.class);
            final ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
            verify(InterestCalculationServiceTest.this.categoryBalanceRepository, times(2))
                    .findAfterKey(accountCursor.capture(), typeCursor.capture(),
                            categoryCursor.capture(), page.capture());
            assertAll(
                    () -> assertThat(accountCursor.getAllValues().get(0))
                            .as("the first probe starts below every key, unconstrained")
                            .isEmpty(),
                    () -> assertThat(typeCursor.getAllValues().get(0)).isEmpty(),
                    () -> assertThat(categoryCursor.getAllValues().get(0)).isEmpty(),
                    () -> assertThat(accountCursor.getAllValues().get(1) + typeCursor
                            .getAllValues().get(1) + categoryCursor.getAllValues().get(1))
                            .as("the cursor advanced to the last delivered key, strictly ascending")
                            .isEqualTo(HIGHER_ACCOUNT_ID + ROW_TYPE_CD + ROW_CAT_CD)
                            .isGreaterThan(ACCOUNT_ID + ROW_TYPE_CD + ROW_CAT_CD),
                    () -> assertThat(page.getAllValues())
                            .allSatisfy(requested -> assertAll(
                                    () -> assertThat(requested.getPageNumber())
                                            .as("the key predicate advances the scan, not an offset")
                                            .isZero(),
                                    () -> assertThat(requested.isPaged())
                                            .as("the page is bounded rather than unbounded")
                                            .isTrue())),
                    () -> assertThat(run.recordCount()).isEqualTo(3),
                    () -> assertThat(run.groupCount())
                            .as("ascending order grouped the first account's two rows together")
                            .isEqualTo(2),
                    () -> assertThat(InterestCalculationServiceTest.this.closedGroups)
                            .extracting(
                                    InterestCalculationService.GroupInterestResult::recordCount)
                            .containsExactly(2, 1));
        }

        @Test
        @DisplayName("an unreadable master is the ERROR arm: it is never mistaken for the end of the "
                + "scan, and it abends")
        void anUnreadableMasterIsTheErrorArm() {
            when(InterestCalculationServiceTest.this.categoryBalanceRepository
                    .findAfterKey(any(), any(), any(), any(Pageable.class)))
                    .thenThrow(new QueryTimeoutException("the category-balance master is unreadable"));

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> InterestCalculationServiceTest.this.service
                            .calculateInterest(PINNED_RUN_DATE, recordSink(), groupSink()))
                    .satisfies(abend -> assertThat(abend.culprit()).isEqualTo(PROGRAM_NAME));

            assertAll(
                    () -> assertThat(InterestCalculationServiceTest.this.closedGroups)
                            .as("no group was closed, so the failure was not read as end of file")
                            .isEmpty(),
                    () -> assertThat(loggedMessages())
                            .anyMatch(message -> message
                                    .startsWith("ERROR READING TRANSACTION CATEGORY FILE")),
                    () -> assertThat(loggedMessages())
                            .noneMatch(message -> message.contains(
                                    "END OF EXECUTION OF PROGRAM CBACT04C")));
        }

        @Test
        @DisplayName("a missing account ABENDS rather than being skipped, because the keyed read accepts "
                + "success alone and has no end-of-file arm")
        void aMissingAccountAbends() {
            when(InterestCalculationServiceTest.this.accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.empty());

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> accrueSingleRow(ACCOUNT_ID, DECISIVE_BALANCE));

            assertAll(
                    () -> assertThat(loggedMessages())
                            .contains("ACCOUNT NOT FOUND", "ERROR READING ACCOUNT FILE"),
                    () -> verify(InterestCalculationServiceTest.this.abendService)
                            .abendOnFileStatus(PROGRAM_NAME, STATUS_RECORD_NOT_FOUND, OPERATION_READ,
                                    RESOURCE_ACCTFILE),
                    () -> verifyNoInteractions(
                            InterestCalculationServiceTest.this.disclosureGroupRepository),
                    () -> verify(InterestCalculationServiceTest.this.accountRepository, never())
                            .save(any(Account.class)));
        }

        @Test
        @DisplayName("an ABSENT cross-reference is the not-found path and abends cleanly: no "
                + "index-out-of-bounds and no no-such-element escapes")
        void anAbsentCrossReferenceIsTheNotFoundPath() {
            when(InterestCalculationServiceTest.this.accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account(ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE)));
            when(InterestCalculationServiceTest.this.crossReferenceRepository
                    .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.empty());

            assertThatExceptionOfType(AbendException.class)
                    .as("the legacy outcome is an abend, not a runtime accessor failure")
                    .isThrownBy(() -> accrueSingleRow(ACCOUNT_ID, DECISIVE_BALANCE))
                    .satisfies(abend -> assertAll(
                            () -> assertThat(abend.culprit()).isEqualTo(PROGRAM_NAME),
                            () -> assertThat(abend.reason())
                                    .contains(STATUS_RECORD_NOT_FOUND),
                            () -> assertThat(abend.getCause())
                                    .as("the abend carries the raw status rather than losing it")
                                    .isInstanceOf(FileStatusException.class),
                            () -> assertThat(((FileStatusException) abend.getCause()).code())
                                    .isEqualTo(STATUS_RECORD_NOT_FOUND),
                            () -> assertThat(((FileStatusException) abend.getCause()).operation())
                                    .isEqualTo(OPERATION_READ),
                            () -> assertThat(((FileStatusException) abend.getCause())
                                    .resourceName()).isEqualTo(RESOURCE_XREFFILE)));

            assertThat(loggedMessages()).contains("ERROR READING XREF FILE");
        }

        @Test
        @DisplayName("an EMPTY cross-reference list is the same not-found path on the list-returning "
                + "finder, and a multi-element list yields its FIRST element as supplied, unsorted")
        void anEmptyListIsNotFoundAndAMultiElementListYieldsItsFirstElement() {
            final CardCrossReference firstAsSupplied =
                    crossReference(ACCOUNT_ID, SECOND_XREF_CARD_NUM);
            final CardCrossReference secondAsSupplied = crossReference(ACCOUNT_ID, XREF_CARD_NUM);
            when(InterestCalculationServiceTest.this.crossReferenceRepository
                    .findByXrefAcctId(ACCOUNT_ID))
                    .thenReturn(List.of())
                    .thenReturn(List.of(firstAsSupplied, secondAsSupplied));

            final List<CardCrossReference> absent = InterestCalculationServiceTest.this
                    .crossReferenceRepository.findByXrefAcctId(ACCOUNT_ID);
            final List<CardCrossReference> several = InterestCalculationServiceTest.this
                    .crossReferenceRepository.findByXrefAcctId(ACCOUNT_ID);

            assertAll(
                    () -> assertThat(absent)
                            .as("an empty list is the not-found path, never a null and never a throw")
                            .isNotNull()
                            .isEmpty(),
                    () -> assertThat(several)
                            .as("the rows arrive in the order supplied, with no re-sorting")
                            .containsExactly(firstAsSupplied, secondAsSupplied),
                    () -> assertThat(several.get(0).getXrefCardNum())
                            .as("the FIRST element as supplied wins, even though it sorts second")
                            .isEqualTo(SECOND_XREF_CARD_NUM));
        }

        @Test
        @DisplayName("the card number a group carries comes from the first cross-reference row it "
                + "resolved, whichever row that is")
        void theCardNumberComesFromTheResolvedFirstRow() {
            when(InterestCalculationServiceTest.this.accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account(ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE)));
            when(InterestCalculationServiceTest.this.crossReferenceRepository
                    .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.of(crossReference(ACCOUNT_ID, SECOND_XREF_CARD_NUM)));
            givenAccountRewriteEchoes();
            givenEveryProbeResolvesAt(DECISIVE_RATE);

            final InterestCalculationService.GroupInterestResult closed =
                    accrueSingleRow(ACCOUNT_ID, DECISIVE_BALANCE);

            assertThat(closed.interestTransactions().get(0).getTranCardNum())
                    .isEqualTo(SECOND_XREF_CARD_NUM);
        }

        @Test
        @DisplayName("the account and the cross-reference are read ONCE per group, before any row of it, "
                + "however many rows the group carries")
        void theKeyedReadsHappenOncePerGroup() {
            givenGroupReadsResolve(account(ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE));
            givenAccountRewriteEchoes();
            givenEveryProbeResolvesAt(DECISIVE_RATE);

            InterestCalculationServiceTest.this.service.calculateGroupInterest(PINNED_RUN_DATE,
                    ACCOUNT_ID,
                    List.of(categoryBalance(ACCOUNT_ID, DECISIVE_BALANCE),
                            categoryBalance(ACCOUNT_ID, SECOND_ROW_TYPE_CD, DECISIVE_BALANCE)),
                    0L, recordSink());

            assertAll(
                    () -> verify(InterestCalculationServiceTest.this.accountRepository, times(1))
                            .findById(ACCOUNT_ID),
                    () -> verify(InterestCalculationServiceTest.this.crossReferenceRepository,
                            times(1)).findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID),
                    // The rate, by contrast, is resolved per row rather than once per group.
                    () -> verify(InterestCalculationServiceTest.this.disclosureGroupRepository,
                            times(2)).findById(any()));
        }
    }

    /* ============================================================================================ */

    /**
     * The open and close paragraphs, the job parameter, and the boundary inputs each public operation
     * declares.
     *
     * <p>The five open paragraphs and the five close paragraphs have no dataset to act on once the store
     * is relational - the connection belongs to the container and the unit of work to the group's commit
     * boundary - so each survives as a named method the driver genuinely invokes, which is what keeps the
     * paragraph-level mapping total. Their invocation is observable in the run's own diagnostics, which is
     * what the first test below asserts.
     */
    @Nested
    @DisplayName("the open and close paragraphs, the ten-character job parameter, and every boundary the "
            + "public operations declare")
    class TheDriverParagraphsAndTheBoundaries {

        /** Creates the nest. */
        TheDriverParagraphsAndTheBoundaries() {
        }

        @Test
        @DisplayName("all five open paragraphs and all five close paragraphs are invoked, in source "
                + "order, around the read loop - and the run brackets itself with its two banners")
        void everyOpenAndCloseParagraphIsInvokedInSourceOrder() {
            InterestCalculationServiceTest.this.service.calculateInterest(PINNED_RUN_DATE, List.of(),
                    recordSink(), groupSink());

            final List<String> messages = loggedMessages();
            assertAll(
                    () -> assertThat(messages)
                            .as("the five opens, then the five closes, in the source's own order")
                            .containsSubsequence("START OF EXECUTION OF PROGRAM CBACT04C",
                                    "open resource=TCATBALF access=INPUT owner=container",
                                    "open resource=XREFFILE access=INPUT owner=container",
                                    "open resource=DISCGRP access=INPUT owner=container",
                                    "open resource=ACCTFILE access=I-O owner=container",
                                    "open resource=TRANSACT access=OUTPUT owner=container",
                                    "close resource=TCATBALF owner=container",
                                    "close resource=XREFFILE owner=container",
                                    "close resource=DISCGRP owner=container",
                                    "close resource=ACCTFILE owner=container",
                                    "close resource=TRANSACT owner=container",
                                    "END OF EXECUTION OF PROGRAM CBACT04C"),
                    () -> assertThat(messages)
                            .as("the container owns the connection, so no open reported a failure")
                            .noneMatch(message -> message.startsWith("ERROR OPENING")));
        }

        @ParameterizedTest(name = "a run date of \"{0}\" is refused")
        @ValueSource(strings = {"", "2022-07-1", "2022-07-190", "2022/07/19/", "  "})
        @DisplayName("the run date must be exactly ten characters, because an identifier is assembled "
                + "from it positionally and any other length mints the wrong width")
        void theRunDateMustBeExactlyTenCharacters(final String candidate) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> InterestCalculationServiceTest.this.service.calculateInterest(
                            candidate, List.of(), recordSink(), groupSink()));
        }

        @Test
        @DisplayName("a run date of exactly ten characters is accepted whatever it spells, because the "
                + "parameter is carried as text and never parsed as a date")
        void aTenCharacterRunDateIsAcceptedAsText() {
            givenGroupReadsResolve(account(ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE));
            givenAccountRewriteEchoes();
            givenEveryProbeResolvesAt(DECISIVE_RATE);
            final String tenCharactersOfText = "XXXXXXXXXX";

            final InterestCalculationService.GroupInterestResult closed =
                    InterestCalculationServiceTest.this.service.calculateGroupInterest(
                            tenCharactersOfText, ACCOUNT_ID,
                            List.of(categoryBalance(ACCOUNT_ID, DECISIVE_BALANCE)), 0L,
                            recordSink());

            assertAll(
                    () -> assertThat(closed.interestTransactions().get(0).getTranId())
                            .isEqualTo(tenCharactersOfText + "000001"),
                    () -> assertThat(encodedBytes(closed.interestTransactions().get(0).getTranId()))
                            .isEqualTo(TRAN_ID_WIDTH));
        }

        @Test
        @DisplayName("an ABSENT run date is refused with a diagnostic rather than escaping as a bare "
                + "null-pointer failure, on both public operations")
        void anAbsentRunDateIsRefusedOnBothOperations() {
            assertAll(
                    () -> assertThatIllegalArgumentException()
                            .isThrownBy(() -> InterestCalculationServiceTest.this.service
                                    .calculateInterest(null, List.of(), recordSink(), groupSink())),
                    () -> assertThatIllegalArgumentException()
                            .isThrownBy(() -> InterestCalculationServiceTest.this.service
                                    .calculateGroupInterest(null, ACCOUNT_ID,
                                            List.of(categoryBalance(ACCOUNT_ID, DECISIVE_BALANCE)),
                                            0L, recordSink())),
                    () -> assertThatIllegalArgumentException()
                            .isThrownBy(() -> InterestCalculationServiceTest.this.service
                                    .calculateInterest(null, recordSink(), groupSink())));
        }

        @Test
        @DisplayName("an ABSENT source, destination or writer is refused: an absent source is not an "
                + "empty source, and there is no path that mints a record with nowhere to write it")
        void absentCollaboratorArgumentsAreRefused() {
            assertAll(
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> InterestCalculationServiceTest.this.service
                                    .calculateInterest(PINNED_RUN_DATE, null, recordSink(),
                                            groupSink())),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> InterestCalculationServiceTest.this.service
                                    .calculateInterest(PINNED_RUN_DATE, List.of(), null,
                                            groupSink())),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> InterestCalculationServiceTest.this.service
                                    .calculateInterest(PINNED_RUN_DATE, List.of(), recordSink(),
                                            null)),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> InterestCalculationServiceTest.this.service
                                    .calculateGroupInterest(PINNED_RUN_DATE, ACCOUNT_ID,
                                            List.of(categoryBalance(ACCOUNT_ID, DECISIVE_BALANCE)),
                                            0L, null)));
        }

        @Test
        @DisplayName("a group must be non-empty, non-negative in its suffix, keyed on an account, and "
                + "homogeneous in that key, so one account's interest cannot post to another")
        void aGroupMustBeWellFormed() {
            final List<TransactionCategoryBalance> oneRow =
                    List.of(categoryBalance(ACCOUNT_ID, DECISIVE_BALANCE));
            assertAll(
                    () -> assertThatIllegalArgumentException()
                            .as("the control break only closes a group the read loop opened")
                            .isThrownBy(() -> InterestCalculationServiceTest.this.service
                                    .calculateGroupInterest(PINNED_RUN_DATE, ACCOUNT_ID, List.of(),
                                            0L, recordSink())),
                    () -> assertThatIllegalArgumentException()
                            .as("the suffix counts upward from zero")
                            .isThrownBy(() -> InterestCalculationServiceTest.this.service
                                    .calculateGroupInterest(PINNED_RUN_DATE, ACCOUNT_ID, oneRow, -1L,
                                            recordSink())),
                    () -> assertThatIllegalArgumentException()
                            .as("a mixed group would post one account's interest to another")
                            .isThrownBy(() -> InterestCalculationServiceTest.this.service
                                    .calculateGroupInterest(PINNED_RUN_DATE, HIGHER_ACCOUNT_ID,
                                            oneRow, 0L, recordSink())),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .as("an absent account key is refused")
                            .isThrownBy(() -> InterestCalculationServiceTest.this.service
                                    .calculateGroupInterest(PINNED_RUN_DATE, null, oneRow, 0L,
                                            recordSink())),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .as("an absent row list is refused")
                            .isThrownBy(() -> InterestCalculationServiceTest.this.service
                                    .calculateGroupInterest(PINNED_RUN_DATE, ACCOUNT_ID, null, 0L,
                                            recordSink())));
        }

        @Test
        @DisplayName("a BLANK ten-character group identifier is a legitimate key rather than an error: "
                + "it simply misses and takes the fallback, which is the seeded estate's own shape")
        void aBlankGroupIdentifierTakesTheFallbackRatherThanFailing() {
            givenGroupReadsResolve(account(ACCOUNT_ID, SEEDED_BLANK_GROUP_ID, OPENING_BALANCE));
            givenAccountRewriteEchoes();
            when(InterestCalculationServiceTest.this.disclosureGroupRepository.findById(any()))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(
                            disclosureGroup(PADDED_DEFAULT_GROUP_ID, DECISIVE_RATE)));

            final InterestCalculationService.GroupInterestResult closed =
                    accrueSingleRow(ACCOUNT_ID, DECISIVE_BALANCE);

            assertAll(
                    () -> assertThat(closed.defaultGroupUsed()).isTrue(),
                    () -> assertThat(closed.categoryInterests().get(0).monthlyInterest())
                            .isEqualTo(DECISIVE_INTEREST));
        }

        @Test
        @DisplayName("an ABSENT group identifier on the account still probes and still falls back, so a "
                + "null group is never a bare null-pointer failure")
        void anAbsentGroupIdentifierStillFallsBack() {
            final Account withoutGroup = account(ACCOUNT_ID, null, OPENING_BALANCE);
            givenGroupReadsResolve(withoutGroup);
            givenAccountRewriteEchoes();
            when(InterestCalculationServiceTest.this.disclosureGroupRepository.findById(any()))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(
                            disclosureGroup(PADDED_DEFAULT_GROUP_ID, DECISIVE_RATE)));

            final InterestCalculationService.GroupInterestResult closed =
                    accrueSingleRow(ACCOUNT_ID, DECISIVE_BALANCE);

            assertAll(
                    () -> assertThat(closed.defaultGroupUsed()).isTrue(),
                    () -> assertThat(closed.categoryInterests().get(0).monthlyInterest())
                            .isEqualTo(DECISIVE_INTEREST));
        }

        @Test
        @DisplayName("an ABSENT rate on the resolved disclosure row is refused as a bad row rather than "
                + "silently accruing nothing")
        void anAbsentRateIsRefused() {
            givenGroupReadsResolve(account(ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE));
            final DisclosureGroup withoutRate =
                    new DisclosureGroup(DIRECT_GROUP_ID, ROW_TYPE_CD, ROW_CAT_CD, null);
            when(InterestCalculationServiceTest.this.disclosureGroupRepository.findById(any()))
                    .thenReturn(Optional.of(withoutRate));

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> accrueSingleRow(ACCOUNT_ID, DECISIVE_BALANCE));

            verify(InterestCalculationServiceTest.this.accountRepository, never())
                    .save(any(Account.class));
        }

        @Test
        @DisplayName("an ABSENT balance on a category-balance row is refused as a bad row rather than "
                + "being read as a zero balance")
        void anAbsentBalanceIsRefused() {
            givenGroupReadsResolve(account(ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE));
            givenEveryProbeResolvesAt(DECISIVE_RATE);
            final TransactionCategoryBalance withoutBalance =
                    new TransactionCategoryBalance(ACCOUNT_ID, ROW_TYPE_CD, ROW_CAT_CD, null);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> InterestCalculationServiceTest.this.service
                            .calculateGroupInterest(PINNED_RUN_DATE, ACCOUNT_ID,
                                    List.of(withoutBalance), 0L, recordSink()));

            verify(InterestCalculationServiceTest.this.accountRepository, never())
                    .save(any(Account.class));
        }

        @Test
        @DisplayName("an ABSENT row inside a group is refused before the group's reads are issued")
        void anAbsentRowInsideAGroupIsRefused() {
            final List<TransactionCategoryBalance> withAbsentRow = new ArrayList<>();
            withAbsentRow.add(categoryBalance(ACCOUNT_ID, DECISIVE_BALANCE));
            withAbsentRow.add(null);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> InterestCalculationServiceTest.this.service
                            .calculateGroupInterest(PINNED_RUN_DATE, ACCOUNT_ID, withAbsentRow, 0L,
                                    recordSink()));

            verifyNoInteractions(InterestCalculationServiceTest.this.accountRepository);
        }

        @Test
        @DisplayName("every collaborator is required at construction, because this member reads four "
                + "masters, rewrites one and assembles an output record with no degraded mode")
        void everyCollaboratorIsRequiredAtConstruction() {
            final Clock pinned = Clock.fixed(PINNED_INSTANT, ZoneOffset.UTC);
            assertAll(
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> new InterestCalculationService(null,
                                    InterestCalculationServiceTest.this.disclosureGroupRepository,
                                    InterestCalculationServiceTest.this.accountRepository,
                                    InterestCalculationServiceTest.this.crossReferenceRepository,
                                    InterestCalculationServiceTest.this.groupTransactionBoundary,
                                    InterestCalculationServiceTest.this.abendService, pinned)),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> new InterestCalculationService(
                                    InterestCalculationServiceTest.this.categoryBalanceRepository,
                                    null,
                                    InterestCalculationServiceTest.this.accountRepository,
                                    InterestCalculationServiceTest.this.crossReferenceRepository,
                                    InterestCalculationServiceTest.this.groupTransactionBoundary,
                                    InterestCalculationServiceTest.this.abendService, pinned)),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> new InterestCalculationService(
                                    InterestCalculationServiceTest.this.categoryBalanceRepository,
                                    InterestCalculationServiceTest.this.disclosureGroupRepository,
                                    null,
                                    InterestCalculationServiceTest.this.crossReferenceRepository,
                                    InterestCalculationServiceTest.this.groupTransactionBoundary,
                                    InterestCalculationServiceTest.this.abendService, pinned)),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> new InterestCalculationService(
                                    InterestCalculationServiceTest.this.categoryBalanceRepository,
                                    InterestCalculationServiceTest.this.disclosureGroupRepository,
                                    InterestCalculationServiceTest.this.accountRepository, null,
                                    InterestCalculationServiceTest.this.groupTransactionBoundary,
                                    InterestCalculationServiceTest.this.abendService, pinned)),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> new InterestCalculationService(
                                    InterestCalculationServiceTest.this.categoryBalanceRepository,
                                    InterestCalculationServiceTest.this.disclosureGroupRepository,
                                    InterestCalculationServiceTest.this.accountRepository,
                                    InterestCalculationServiceTest.this.crossReferenceRepository,
                                    null,
                                    InterestCalculationServiceTest.this.abendService, pinned)),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> new InterestCalculationService(
                                    InterestCalculationServiceTest.this.categoryBalanceRepository,
                                    InterestCalculationServiceTest.this.disclosureGroupRepository,
                                    InterestCalculationServiceTest.this.accountRepository,
                                    InterestCalculationServiceTest.this.crossReferenceRepository,
                                    InterestCalculationServiceTest.this.groupTransactionBoundary,
                                    null, pinned)),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> new InterestCalculationService(
                                    InterestCalculationServiceTest.this.categoryBalanceRepository,
                                    InterestCalculationServiceTest.this.disclosureGroupRepository,
                                    InterestCalculationServiceTest.this.accountRepository,
                                    InterestCalculationServiceTest.this.crossReferenceRepository,
                                    InterestCalculationServiceTest.this.groupTransactionBoundary,
                                    InterestCalculationServiceTest.this.abendService, null)));
        }

        @Test
        @DisplayName("a run result refuses a count it could not have reached, and reports the counters "
                + "the legacy program maintained and nothing else")
        void theRunResultRefusesAnImpossibleCount() {
            assertAll(
                    () -> assertThatIllegalArgumentException()
                            .isThrownBy(() -> new InterestCalculationService.InterestRunResult(-1, 0,
                                    0, false, false, 0L)),
                    () -> assertThatIllegalArgumentException()
                            .isThrownBy(() -> new InterestCalculationService.InterestRunResult(0, -1,
                                    0, false, false, 0L)),
                    () -> assertThatIllegalArgumentException()
                            .isThrownBy(() -> new InterestCalculationService.InterestRunResult(0, 0,
                                    -1, false, false, 0L)),
                    () -> assertThat(new InterestCalculationService.InterestRunResult(1, 1, 1, true,
                            true, 1L))
                            .satisfies(result -> assertAll(
                                    () -> assertThat(result.groupCount()).isEqualTo(1),
                                    () -> assertThat(result.transactionCount()).isEqualTo(1),
                                    () -> assertThat(result.recordCount()).isEqualTo(1),
                                    () -> assertThat(result.rateGateSkipped()).isTrue(),
                                    () -> assertThat(result.defaultGroupUsed()).isTrue(),
                                    () -> assertThat(result.lastTranIdSuffix()).isEqualTo(1L))));
        }

        @Test
        @DisplayName("a closed group defends its two collections against later mutation, so a result "
                + "handed to a caller cannot change underneath it")
        void aClosedGroupDefendsItsCollections() {
            givenGroupReadsResolve(account(ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE));
            givenAccountRewriteEchoes();
            givenEveryProbeResolvesAt(DECISIVE_RATE);

            final InterestCalculationService.GroupInterestResult closed =
                    accrueSingleRow(ACCOUNT_ID, DECISIVE_BALANCE);

            assertAll(
                    () -> assertThatExceptionOfType(UnsupportedOperationException.class)
                            .isThrownBy(() -> closed.categoryInterests().clear()),
                    () -> assertThatExceptionOfType(UnsupportedOperationException.class)
                            .isThrownBy(() -> closed.interestTransactions().clear()),
                    () -> assertThat(closed.recordCount()).isEqualTo(1),
                    () -> assertThat(closed.accountId()).isEqualTo(ACCOUNT_ID));
        }

        @Test
        @DisplayName("a run carries no collection at all: the closed groups and the written records "
                + "reached their destinations as they were produced, so a run holds one group's state")
        void aRunCarriesNoCollection() {
            givenGroupReadsResolve(account(ACCOUNT_ID, DIRECT_GROUP_ID, OPENING_BALANCE));
            // No account rewrite is stubbed: this run holds one account, so its only group closes at
            // the unreachable end-of-file arm and nothing is rewritten.
            givenEveryProbeResolvesAt(DECISIVE_RATE);

            final InterestCalculationService.InterestRunResult run =
                    InterestCalculationServiceTest.this.service.calculateInterest(PINNED_RUN_DATE,
                            List.of(categoryBalance(ACCOUNT_ID, DECISIVE_BALANCE)),
                            recordSink(), groupSink());

            assertAll(
                    () -> assertThat(run.transactionCount())
                            .as("the run counts what passed through rather than holding it")
                            .isEqualTo(InterestCalculationServiceTest.this.writtenRecords.size()),
                    () -> assertThat(run.groupCount())
                            .isEqualTo(InterestCalculationServiceTest.this.closedGroups.size()),
                    () -> assertThat(InterestCalculationServiceTest.this.closedGroups)
                            .singleElement()
                            .satisfies(group -> assertThat(group.categoryInterests())
                                    .singleElement()
                                    .satisfies(row -> assertAll(
                                            () -> assertThat(row.accountId()).isEqualTo(ACCOUNT_ID),
                                            () -> assertThat(row.tranTypeCd())
                                                    .isEqualTo(ROW_TYPE_CD),
                                            () -> assertThat(row.tranCatCd()).isEqualTo(ROW_CAT_CD),
                                            () -> assertThat(row.categoryBalance())
                                                    .isEqualTo(DECISIVE_BALANCE),
                                            () -> assertThat(row.disclosedRate())
                                                    .isEqualTo(DECISIVE_RATE),
                                            () -> assertThat(row.producedTransaction()).isTrue(),
                                            () -> assertThat(row.interestTransaction())
                                                    .isNotNull()))));
        }
    }
}
