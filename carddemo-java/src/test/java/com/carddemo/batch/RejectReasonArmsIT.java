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
package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.carddemo.batch.step.AdvisoryGenerationPublicationLock;
import com.carddemo.batch.step.FixedWidthFlatFileReaderFactory;
import com.carddemo.batch.step.RejectRecordWriter;
import com.carddemo.batch.step.StagedGenerationStore;
import com.carddemo.batch.step.TransactionValidationProcessor;
import com.carddemo.config.AwsProperties;
import com.carddemo.config.BatchConfig;
import com.carddemo.domain.Account;
import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.enums.RejectReason;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.RecordWriter;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.AbendService;
import com.carddemo.service.PostingRecordTransactionBoundary;
import com.carddemo.service.TransactionPostingService;
import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.support.LegacyRejectReasons;
import com.carddemo.support.TestDataFactory;
import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.sns.core.SnsOperations;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.MeterNotFoundException;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.tracing.prometheus.PrometheusExemplarsAutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Proves that <strong>all five</strong> reject reasons of the daily posting program are reachable, that each
 * emits the four digits and the seventy-six characters the legacy source sets, and that the fifth one is
 * inert exactly as the source leaves it.
 *
 * <h2>The gap this specification closes</h2>
 *
 * <p>Before it, only three of the five reasons were ever observed end to end. The other two were argued for
 * in prose and asserted by absence:
 *
 * <ul>
 *   <li><strong>{@code 0101}</strong>, the account-read refusal, was recorded as unreachable because the
 *       cross-reference table's foreign key to the account table forbids the dangling row the reason needs.
 *       That reasoning about the SCHEMA is correct, and it does not follow that the CODE PATH is unreachable:
 *       the path is entered when the account read reports nothing, and a read can report nothing for reasons
 *       other than an absent row. Nothing had ever made it do so, so nothing had ever seen the four digits
 *       {@code 0101} leave the system.</li>
 *   <li><strong>{@code 0109}</strong>, the account-rewrite refusal, is inert by legacy design - and its
 *       inertness was asserted only negatively, by observing that no reject record in some other run carried
 *       it. An absence is equally consistent with the arm never being entered at all, which is exactly what
 *       was happening.</li>
 * </ul>
 *
 * <p>Both are now entered, through the one seam that can enter them without inventing persisted state the
 * record image cannot carry: the account repository is a Mockito spy, and one method of it is made to report
 * what the legacy file would have reported. Everything else - the schema, the migrations, the reader, the
 * validation cascade, the posting mainline, the reject writer, the generation store - is the shipped
 * article running against a real PostgreSQL server. The seam is deliberately as narrow as one method call.
 *
 * <h2>Where the expected bytes come from</h2>
 *
 * <p>From {@link LegacyRejectReasons}, a hand transcription of the legacy source that imports nothing from
 * the shipped types. They are NOT read from {@code RejectReason}: an expectation taken from the enumeration
 * under test agrees with it by construction, which is what allowed two of these five reasons to go unobserved
 * while every assertion about them passed.
 *
 * <p>Why the seam is legitimate, and why the byte-parity gate is not weakened to carry these two
 * arms, are recorded as DL-279 in {@code docs/decision-log.md}.
 *
 * <p>Provenance: {@code app/cbl/CBTRN02C.cbl}, read as read-only reference at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 */
@SpringBootTest(classes = RejectReasonArmsIT.JobContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.flyway.enabled=false", "spring.main.banner-mode=off",
                "spring.jpa.hibernate.ddl-auto=none",
                "management.endpoint.health.validate-group-membership=false",
                "management.tracing.enabled=false",
                RejectReasonArmsIT.STAGING_DIRECTORY_SETTING,
                RejectReasonArmsIT.DALYTRAN_DATASET_SETTING,
                RejectReasonArmsIT.DALYREJS_DATASET_BASE_SETTING})
@DisplayName("RejectReasonArmsIT - all five reject reasons reached, and the fifth one inert")
class RejectReasonArmsIT extends AbstractPostgresIT {

    /** Staging directory this specification owns, inside the module's own build output. */
    static final String STAGING_DIRECTORY_VALUE = "target/reject-reason-arms-it-staging";

    /** Logical name of the sequential input this run reads, distinct from every other run's. */
    static final String DALYTRAN_DATASET_VALUE = "CARDDEMO.REJARMS.IT.DALYTRAN.PS";

    /** Logical base of the reject generation group this run writes, likewise its own. */
    static final String DALYREJS_DATASET_BASE_VALUE = "CARDDEMO.REJARMS.IT.DALYREJS";

    /** Inline setting binding the staging directory. */
    static final String STAGING_DIRECTORY_SETTING =
            PostTransactionJobConfig.STAGING_DIRECTORY_PROPERTY + "=" + STAGING_DIRECTORY_VALUE;

    /** Inline setting binding the logical name of the sequential input. */
    static final String DALYTRAN_DATASET_SETTING =
            PostTransactionJobConfig.DALYTRAN_DATASET_PROPERTY + "=" + DALYTRAN_DATASET_VALUE;

    /** Inline setting binding the logical base of the reject generation group. */
    static final String DALYREJS_DATASET_BASE_SETTING =
            PostTransactionJobConfig.DALYREJS_DATASET_BASE_PROPERTY + "="
                    + DALYREJS_DATASET_BASE_VALUE;

    /** Width of one landing record, and of the source segment of a reject record. */
    private static final int LANDING_RECORD_WIDTH = 350;

    /** Width of one whole reject record: the landing image, four digits and seventy-six characters. */
    private static final int REJECT_RECORD_WIDTH = 430;

    /** Width of the four-digit numeric reason field that opens the validation trailer. */
    private static final int REASON_CODE_WIDTH = 4;

    /** Width of the description field that follows it. */
    private static final int REASON_DESCRIPTION_WIDTH = 76;

    /**
     * How many leading characters of the origination timestamp the expiry comparison reads.
     *
     * <p>The comparison is lexicographic over exactly this many characters of a zero-padded date, not a
     * comparison of two date values, and no calendar type is constructed on either side of it.
     */
    private static final int EXPIRY_COMPARISON_WIDTH = 10;

    /** A seeded card whose cross-reference resolves to a seeded account, confirmed before every use. */
    private static final String SEEDED_CARD = "4859452612877065";

    /** The account that card resolves to. */
    private static final String SEEDED_ACCOUNT = "00000000007";

    /** A modest amount that no seeded credit limit refuses. */
    private static final BigDecimal MODEST_AMOUNT = new BigDecimal("10.00");

    /** Identifier of the record that carries a card resolving to nothing. */
    private static final String UNRESOLVED_RECORD_ID = "9700000000000100";

    /** Identifier of the record whose account read is made to report nothing. */
    private static final String READ_REFUSED_RECORD_ID = "9700000000000101";

    /** Identifier of the record the seeded credit limit refuses. */
    private static final String OVERLIMIT_RECORD_ID = "9700000000000102";

    /** Identifier of the record that arrives after its account expired. */
    private static final String EXPIRED_RECORD_ID = "9700000000000103";

    /** Identifier of the record whose account rewrite is made to report no row. */
    private static final String REWRITE_REFUSED_RECORD_ID = "9700000000000109";

    /** Parameter key this specification mints a distinct run identity under. */
    private static final String RUN_KEY = "carddemo.test.rejectReasonArmsRun";

    /** Counter the posting step increments once per record, tagged by outcome and reason. */
    private static final String RECORDS_METRIC = "carddemo.batch.posting.records";

    /** Tag naming the outcome a record ended with. */
    private static final String OUTCOME_TAG = "outcome";

    /** Tag naming the four-digit reason field a record carried. */
    private static final String REASON_TAG = "reason";

    /** Outcome tag value of a record that posted. */
    private static final String POSTED = "POSTED";

    /** Outcome tag value of a record that was refused. */
    private static final String REJECTED = "REJECTED";

    /** Distinct run identity per launch, so each launch is its own job instance. */
    private static final AtomicLong RUN_IDENTITY = new AtomicLong(9_700_000L);

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private Job postTransactionJob;

    @Autowired
    private PostTransactionJobConfig config;

    @Autowired
    private CardCrossReferenceRepository crossReferenceRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    /**
     * The account repository, as a spy over the real bean.
     *
     * <p>The ONLY seam in this specification, and the narrowest one that reaches the two arms no persisted
     * state can reach. Every other collaborator, the schema and the server are the shipped article. The
     * override resets after each test method, so a stub cannot leak into a neighbouring one.
     */
    @MockitoSpyBean
    private AccountRepository accountRepository;

    /** Creates the specification. */
    RejectReasonArmsIT() {
    }

    /**
     * Restores the seeded state and clears any generation a previous method left behind.
     *
     * @throws IOException  if the staging directory cannot be cleared
     * @throws SQLException if the seeded state cannot be restored
     */
    @BeforeEach
    void restoreAndClear() throws IOException, SQLException {
        restoreSeededState();
        clearStagingArea();
    }

    /**
     * Leaves no artefact behind for a neighbouring specification to trip over.
     *
     * @throws IOException if the staging directory cannot be cleared
     */
    @AfterEach
    void clearAfterwards() throws IOException {
        clearStagingArea();
    }

    // ---------------------------------------------------------------------------------------------
    // Staging, launching, and reading back what a launch produced.
    // ---------------------------------------------------------------------------------------------

    /**
     * Removes every file this specification's staging directory holds.
     *
     * @throws IOException if the directory cannot be listed or a file cannot be removed
     */
    private void clearStagingArea() throws IOException {
        final Path directory = this.config.dalytranInput().getParent();
        if (directory == null || !Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> contents = Files.list(directory)) {
            for (final Path artefact : contents.toList()) {
                if (Files.isRegularFile(artefact)) {
                    Files.deleteIfExists(artefact);
                }
            }
        }
    }

    /**
     * Writes the sequential input this run reads, one landing record per line, at its declared width.
     *
     * @param  images the landing record images, in the order the run must read them
     * @throws IOException if the input cannot be written
     */
    private void stageInput(final List<String> images) throws IOException {
        for (final String image : images) {
            assertThat(encodedWidth(image))
                    .as("a staged landing record is %d bytes wide", LANDING_RECORD_WIDTH)
                    .isEqualTo(LANDING_RECORD_WIDTH);
        }
        final Path input = this.config.dalytranInput();
        final Path directory = input.getParent();
        if (directory != null) {
            Files.createDirectories(directory);
        }
        Files.write(input, images, StandardCharsets.US_ASCII);
    }

    /**
     * Launches the job under a distinct run identity.
     *
     * @return the terminal job execution
     * @throws Exception if the launcher refuses the launch
     */
    private JobExecution launch() throws Exception {
        final JobParameters parameters = new JobParametersBuilder()
                .addLong(RUN_KEY, RUN_IDENTITY.incrementAndGet())
                .toJobParameters();
        return this.jobLauncher.run(this.postTransactionJob, parameters);
    }

    /**
     * Reads the sealed reject generation one execution owns, as whole records.
     *
     * <p>The format is fixed-length <strong>unblocked</strong>, so a total that is not an exact multiple of
     * the record width means a separator was written or a record was not its declared width, and both are
     * byte-parity defects rather than cosmetic ones.
     *
     * @param  execution the completed job execution
     * @return its reject records, in the order they were written, or an empty list when it sealed none
     * @throws IOException if the generation cannot be read
     */
    private List<byte[]> rejectRecords(final JobExecution execution) throws IOException {
        final Path generation = this.config.rejectGeneration(execution.getId().longValue());
        if (!Files.isRegularFile(generation)) {
            return List.of();
        }
        final byte[] artefact = Files.readAllBytes(generation);
        assertThat(artefact.length % REJECT_RECORD_WIDTH)
                .as("%s is fixed-length UNBLOCKED, so the artefact is an exact multiple of %d bytes with"
                        + " nothing appended and no separator anywhere, but it measures %d",
                        TransactionPostingService.DALYREJS_DD, REJECT_RECORD_WIDTH, artefact.length)
                .isZero();
        final List<byte[]> records = new ArrayList<>(artefact.length / REJECT_RECORD_WIDTH);
        for (int offset = 0; offset < artefact.length; offset += REJECT_RECORD_WIDTH) {
            records.add(Arrays.copyOfRange(artefact, offset, offset + REJECT_RECORD_WIDTH));
        }
        return records;
    }

    /**
     * The encoded width of a value, which is the only width a fixed-width contract is measured in.
     *
     * @param  value the value to measure
     * @return its length in encoded US-ASCII bytes
     */
    private static int encodedWidth(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Asserts that one produced reject record is the landing image followed by the transcribed trailer.
     *
     * <p>Byte equality over the whole four hundred and thirty bytes, then the two segments named
     * separately so a failure says which one moved. The expected trailer comes from the transcription of
     * the legacy source, never from the enumeration the run used to produce it.
     *
     * @param produced     the whole produced record
     * @param landingImage the landing record image as it was staged
     * @param transcribed  the transcribed reason the record must carry
     */
    private static void assertRejectRecord(final byte[] produced, final String landingImage,
            final LegacyRejectReasons.Reason transcribed) {
        final String expected = landingImage + transcribed.trailer();
        assertThat(produced)
                .as("all %d bytes: the %d-byte landing image copied verbatim, then %s, then the"
                        + " description the legacy source moves at %s blank-padded to %d",
                        REJECT_RECORD_WIDTH, LANDING_RECORD_WIDTH, transcribed.fourDigitCode(),
                        transcribed.sourceLocation(), REASON_DESCRIPTION_WIDTH)
                .isEqualTo(expected.getBytes(StandardCharsets.US_ASCII));

        assertThat(Arrays.copyOfRange(produced, 0, LANDING_RECORD_WIDTH))
                .as("bytes 1 to %d are the source record copied UNCHANGED - not re-rendered, not"
                        + " re-padded, not normalised", LANDING_RECORD_WIDTH)
                .isEqualTo(landingImage.getBytes(StandardCharsets.US_ASCII));
        assertThat(new String(produced, LANDING_RECORD_WIDTH, REASON_CODE_WIDTH,
                StandardCharsets.US_ASCII))
                .as("the four-digit reason field, zero-filled on the left because the field is numeric")
                .isEqualTo(transcribed.fourDigitCode());
        assertThat(new String(produced, LANDING_RECORD_WIDTH + REASON_CODE_WIDTH,
                REASON_DESCRIPTION_WIDTH, StandardCharsets.US_ASCII))
                .as("the seventy-six character description field, blank-padded on the right and never"
                        + " trimmed, because the field is fixed width")
                .isEqualTo(transcribed.paddedDescription())
                .hasSize(REASON_DESCRIPTION_WIDTH);
    }

    /**
     * How many records one run counted against the outcome and reason a transcribed reason implies.
     *
     * <p>Reads the counter the posting step publishes rather than inspecting a log line, and reports an
     * unregistered counter as zero: a reason that was never reached registers no counter at all under this
     * registry, and that is an absence rather than an error.
     *
     * @param  transcribed the transcribed reason
     * @param  outcome     the outcome tag value expected for it
     * @return the counter's current total
     */
    private double recordsCounted(final LegacyRejectReasons.Reason transcribed, final String outcome) {
        try {
            return this.meterRegistry.get(RECORDS_METRIC)
                    .tag(OUTCOME_TAG, outcome)
                    .tag(REASON_TAG, transcribed.fourDigitCode())
                    .counter()
                    .count();
        } catch (final MeterNotFoundException absent) {
            return 0.0d;
        }
    }

    /**
     * Confirms the cross-reference this specification's expectations rest on, so a changed seed says which
     * card disagreed rather than failing somewhere downstream.
     *
     * @param cardNumber      the card
     * @param expectedAccount the account it must resolve to
     */
    private void requireCrossReference(final String cardNumber, final String expectedAccount) {
        final Optional<CardCrossReference> resolved =
                this.crossReferenceRepository.findById(cardNumber);
        assertThat(resolved)
                .as("%s: card %s must resolve, because this specification's expectations rest on it",
                        TransactionPostingService.XREFFILE_DD, cardNumber)
                .isPresent();
        assertThat(resolved.orElseThrow().getXrefAcctId()).isEqualTo(expectedAccount);
    }

    /**
     * The seeded account as it now stands, insisting the row is there.
     *
     * <p>Reads through the shipped finder deliberately, so the row this asserts about is the row the run
     * would have seen. When a spy is in force the caller arranges the stub AFTER calling this.
     *
     * @param  accountId the account identifier
     * @return the account
     */
    private Account accountNow(final String accountId) {
        return this.accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalStateException(
                        TransactionPostingService.ACCTFILE_DD + ": account " + accountId
                                + " must exist for this specification to mean anything"));
    }

    // =============================================================================================
    // THE THREE ARMS THE VALIDATION PARAGRAPH REACHES FROM DATA ALONE
    // =============================================================================================

    @Nested
    @DisplayName("the three reasons a shaped input record reaches on its own")
    final class ReachableFromDataAlone {

        /** Creates the nest. */
        ReachableFromDataAlone() {
        }

        @Test
        @DisplayName("a card that resolves to nothing is refused with 0100 and the full 430-byte record "
                + "carries the transcribed description")
        void anUnresolvableCardIsRefusedWithOneHundred() throws Exception {
            assertThat(crossReferenceRepository.findById(TestDataFactory.UNKNOWN_CARD_NUMBER))
                    .as("%s: the card this reason depends on must resolve to nothing",
                            TransactionPostingService.XREFFILE_DD)
                    .isEmpty();

            final String image = TestDataFactory.dailyTransaction()
                    .id(UNRESOLVED_RECORD_ID)
                    .cardNumber(TestDataFactory.UNKNOWN_CARD_NUMBER)
                    .amount(MODEST_AMOUNT)
                    .image();
            stageInput(List.of(image));

            final JobExecution execution = launch();
            final List<byte[]> produced = rejectRecords(execution);

            assertThat(produced)
                    .as("one input record, one refusal, one reject record")
                    .hasSize(1);
            assertRejectRecord(produced.get(0), image, LegacyRejectReasons.requireByCode(
                    LegacyRejectReasons.INVALID_CARD_NUMBER_CODE));
            assertThat(transactionRepository.findById(UNRESOLVED_RECORD_ID))
                    .as("%s: a refused record writes no posted row",
                            TransactionPostingService.TRANFILE_DD)
                    .isEmpty();
        }

        @Test
        @DisplayName("an amount the seeded credit limit cannot carry is refused with 0102 and the full "
                + "430-byte record carries the transcribed description")
        void anOverLimitAmountIsRefusedWithOneHundredAndTwo() throws Exception {
            requireCrossReference(SEEDED_CARD, SEEDED_ACCOUNT);
            assertThat(accountNow(SEEDED_ACCOUNT).getAcctCreditLimit())
                    .as("the seeded limit must be below the amount staged below, or this record would"
                            + " post rather than being refused")
                    .isLessThan(TestDataFactory.OVERLIMIT_AMOUNT);

            final String image = TestDataFactory.dailyTransaction()
                    .id(OVERLIMIT_RECORD_ID)
                    .cardNumber(SEEDED_CARD)
                    .amount(TestDataFactory.OVERLIMIT_AMOUNT)
                    .image();
            stageInput(List.of(image));

            final JobExecution execution = launch();
            final List<byte[]> produced = rejectRecords(execution);

            assertThat(produced).hasSize(1);
            assertRejectRecord(produced.get(0), image, LegacyRejectReasons.requireByCode(
                    LegacyRejectReasons.OVERLIMIT_TRANSACTION_CODE));
            assertThat(transactionRepository.findById(OVERLIMIT_RECORD_ID)).isEmpty();
        }

        @Test
        @DisplayName("a record originating after its account expired is refused with 0103 and the full "
                + "430-byte record carries the transcribed forty-two character description")
        void anArrivalAfterExpiryIsRefusedWithOneHundredAndThree() throws Exception {
            requireCrossReference(SEEDED_CARD, SEEDED_ACCOUNT);
            assertThat(accountNow(SEEDED_ACCOUNT).getAcctExpirationDate())
                    .as("the seeded expiry must precede the origination date staged below, compared"
                            + " LEXICOGRAPHICALLY over the leading ten characters exactly as the"
                            + " validation block compares them - no calendar type is constructed on"
                            + " either side of that comparison")
                    .isLessThan(TestDataFactory.POST_EXPIRY_TIMESTAMP.substring(0,
                            EXPIRY_COMPARISON_WIDTH));

            final String image = TestDataFactory.dailyTransaction()
                    .id(EXPIRED_RECORD_ID)
                    .cardNumber(SEEDED_CARD)
                    .amount(MODEST_AMOUNT)
                    .originalTimestamp(TestDataFactory.POST_EXPIRY_TIMESTAMP)
                    .image();
            stageInput(List.of(image));

            final JobExecution execution = launch();
            final List<byte[]> produced = rejectRecords(execution);

            assertThat(produced).hasSize(1);
            assertRejectRecord(produced.get(0), image, LegacyRejectReasons.requireByCode(
                    LegacyRejectReasons.TRANSACTION_AFTER_EXPIRATION_CODE));
            assertThat(transactionRepository.findById(EXPIRED_RECORD_ID)).isEmpty();
        }
    }

    // =============================================================================================
    // THE TWO ARMS THAT NEEDED A SEAM
    // =============================================================================================

    @Nested
    @DisplayName("the two reasons no persisted state can reach")
    final class ReachableOnlyThroughTheSeam {

        /** Creates the nest. */
        ReachableOnlyThroughTheSeam() {
        }

        @Test
        @DisplayName("0101: when the account read reports nothing the record is REFUSED, a real 430-byte "
                + "reject record is written, and nothing is posted")
        void anAccountReadReportingNothingIsRefusedWithOneHundredAndOne() throws Exception {
            requireCrossReference(SEEDED_CARD, SEEDED_ACCOUNT);
            final Account seeded = accountNow(SEEDED_ACCOUNT);
            final BigDecimal balanceBefore = seeded.getAcctCurrBal();
            final BigDecimal cycleCreditBefore = seeded.getAcctCurrCycCredit();

            final String image = TestDataFactory.dailyTransaction()
                    .id(READ_REFUSED_RECORD_ID)
                    .cardNumber(SEEDED_CARD)
                    .amount(MODEST_AMOUNT)
                    .image();
            stageInput(List.of(image));

            // THE SEAM, AND ONLY THIS. The row is present and the foreign key is satisfied - which is why
            // no persisted state can produce this outcome - so the arm is entered by making the read
            // itself report the absence the legacy file's INVALID KEY arm reported. One method, one
            // argument, everything else shipped.
            doReturn(Optional.empty()).when(accountRepository).findById(SEEDED_ACCOUNT);

            final JobExecution execution = launch();
            final List<byte[]> produced = rejectRecords(execution);

            final LegacyRejectReasons.Reason transcribed = LegacyRejectReasons.requireByCode(
                    LegacyRejectReasons.ACCOUNT_NOT_FOUND_ON_READ_CODE);
            assertThat(produced)
                    .as("the arm writes a real reject record: this is the first time these four digits"
                            + " have left the system in this estate's test evidence")
                    .hasSize(1);
            assertRejectRecord(produced.get(0), image, transcribed);
            assertThat(recordsCounted(transcribed, REJECTED))
                    .as("and the record is counted as REFUSED, which is the classification that decides"
                            + " whether a reject record is written at all")
                    .isPositive();

            assertThat(transactionRepository.findById(READ_REFUSED_RECORD_ID))
                    .as("%s: the posting mainline runs only on a zero reason code, so a refused record"
                            + " writes no posted row", TransactionPostingService.TRANFILE_DD)
                    .isEmpty();
        }

        @Test
        @DisplayName("0109: when the account rewrite reports no row the reason is set and then NEVER "
                + "read - the record POSTS, no reject record is written, and the run counts it as posted")
        void anAccountRewriteReportingNoRowIsInertAndTheRecordStillPosts() throws Exception {
            requireCrossReference(SEEDED_CARD, SEEDED_ACCOUNT);
            accountNow(SEEDED_ACCOUNT);

            final String image = TestDataFactory.dailyTransaction()
                    .id(REWRITE_REFUSED_RECORD_ID)
                    .cardNumber(SEEDED_CARD)
                    .amount(MODEST_AMOUNT)
                    .image();
            stageInput(List.of(image));

            // THE SEAM. Two arrangements, because the arm asks two questions and both answers are needed
            // to reach the inert reason rather than the version-race refusal beside it: the rewrite must
            // report no row, and the held read must confirm the row is genuinely absent rather than
            // merely re-versioned. That second question is exactly how the translation tells the legacy
            // INVALID KEY condition apart from a concurrency event the legacy file could not observe.
            doReturn(Integer.valueOf(0)).when(accountRepository).rewritePostingBalances(
                    eq(SEEDED_ACCOUNT), anyLong(), any(BigDecimal.class), any(BigDecimal.class),
                    any(BigDecimal.class));
            doReturn(Optional.empty()).when(accountRepository).findByIdForUpdate(SEEDED_ACCOUNT);

            final JobExecution execution = launch();

            final LegacyRejectReasons.Reason transcribed = LegacyRejectReasons.requireByCode(
                    LegacyRejectReasons.ACCOUNT_NOT_FOUND_ON_REWRITE_CODE);
            assertThat(transcribed.producesRecord())
                    .as("the transcription records this reason as producing NO record, which is the"
                            + " claim the rest of this test measures")
                    .isFalse();

            assertThat(rejectRecords(execution))
                    .as("NO REJECT RECORD. The reason is set inside the account-rewrite paragraph, which"
                            + " runs during POSTING - after the reject decision has already sent this"
                            + " record down the posting path - and nothing re-tests it afterwards. A"
                            + " sixth reject case here would be a behaviour the legacy does not have")
                    .isEmpty();
            assertThat(transactionRepository.findById(REWRITE_REFUSED_RECORD_ID))
                    .as("%s: and the record POSTED, which is the other half of the same statement -"
                            + " inertness means the value is set and ignored, not that the record is"
                            + " dropped", TransactionPostingService.TRANFILE_DD)
                    .isPresent();
            assertThat(recordsCounted(transcribed, POSTED))
                    .as("the run counts it under the POSTED outcome, so the inertness is visible in the"
                            + " shape of the metric and not only in prose")
                    .isPositive();
            assertThat(recordsCounted(transcribed, REJECTED))
                    .as("and never under the REFUSED outcome")
                    .isZero();
        }
    }

    // =============================================================================================
    // THE TRAILER OF ALL FIVE, PRODUCTION AGAINST TRANSCRIPTION
    // =============================================================================================

    @Nested
    @DisplayName("the trailer the production assembler emits for every one of the five")
    final class EveryTrailer {

        /** Creates the nest. */
        EveryTrailer() {
        }

        @Test
        @DisplayName("each of the five is exactly the four transcribed digits then the transcribed "
                + "description padded to seventy-six, measured in encoded bytes")
        void everyReasonEmitsTheTranscribedTrailer() {
            assertThat(LegacyRejectReasons.REASONS)
                    .as("the legacy source sets five reasons")
                    .hasSize(5);

            for (final LegacyRejectReasons.Reason transcribed : LegacyRejectReasons.REASONS) {
                final RejectReason shipped = RejectReason.byReasonCode(transcribed.code())
                        .orElseThrow(() -> new AssertionError("the legacy source sets reason "
                                + transcribed.fourDigitCode() + " at " + transcribed.sourceLocation()
                                + ", but the shipped enumeration recognises no such code"));
                assertThat(shipped.name())
                        .as("%s must be the reason that arises when %s: the two account-not-found"
                                + " reasons share one description and are told apart by nothing but"
                                + " these four digits", transcribed.fourDigitCode(), transcribed.role())
                        .isEqualTo(transcribed.shippedConstantName());

                assertThat(RejectRecordWriter.failReasonField(shipped))
                        .as("the production reason field for %s", transcribed.fourDigitCode())
                        .isEqualTo(transcribed.fourDigitCode())
                        .hasSize(REASON_CODE_WIDTH);
                assertThat(RejectRecordWriter.failReasonDescriptionField(shipped))
                        .as("the production description field for %s, blank-padded to %d and never"
                                + " trimmed", transcribed.fourDigitCode(), REASON_DESCRIPTION_WIDTH)
                        .isEqualTo(transcribed.paddedDescription())
                        .hasSize(REASON_DESCRIPTION_WIDTH);
                assertThat(encodedWidth(RejectRecordWriter.validationTrailer(shipped)))
                        .as("and the whole trailer is eighty ENCODED bytes, which is the only width a"
                                + " fixed-width contract is measured in")
                        .isEqualTo(REASON_CODE_WIDTH + REASON_DESCRIPTION_WIDTH);
                assertThat(RejectRecordWriter.validationTrailer(shipped))
                        .isEqualTo(transcribed.trailer());
            }
        }
    }

    /**
     * The pinned clock the shared base class owns, handed to the slice through the enclosing class.
     *
     * @return the clock frozen at the fixture's own instant
     */
    static Clock pinnedClock() {
        return FIXED_CLOCK;
    }

    /**
     * The narrowest context the posting job needs, identical in shape to the job's own specification so
     * that what runs here is what runs there.
     *
     * @see PostTransactionJobConfigIT
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = PrometheusExemplarsAutoConfiguration.class)
    @Import({PostTransactionJobConfig.class, BatchConfig.class, BatchStagingArea.class,
            StagedGenerationStore.class, AdvisoryGenerationPublicationLock.class,
            FixedWidthFlatFileReaderFactory.class, TransactionPostingService.class,
            PostingRecordTransactionBoundary.class, RecordWriter.class, AbendService.class})
    @EnableConfigurationProperties(AwsProperties.class)
    @EnableJpaRepositories(basePackageClasses = AccountRepository.class)
    @EntityScan(basePackageClasses = Account.class)
    static class JobContext {

        /** Creates the context. */
        JobContext() {
        }

        /**
         * The clock the processing timestamp is regenerated from, pinned rather than a system one.
         *
         * @return the clock frozen at the fixture's own instant
         */
        @Bean
        Clock pinnedBatchClock() {
            return pinnedClock();
        }

        /**
         * Keeps this PostgreSQL-focused specification deterministic at the object-store edge.
         *
         * @return an object-store edge that accepts uploads and reports no older generations
         */
        @Bean
        S3Operations objectStore() {
            final S3Operations objectStore = mock(S3Operations.class);
            when(objectStore.listObjects(anyString(), anyString())).thenReturn(List.of());
            return objectStore;
        }

        /**
         * Prevents an unrelated notification endpoint from becoming a prerequisite here.
         *
         * @return a notification edge that accepts whatever it is offered
         */
        @Bean
        SnsOperations notifications() {
            return mock(SnsOperations.class);
        }
    }
}
