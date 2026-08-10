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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.carddemo.config.AwsConfig;
import com.carddemo.config.AwsProperties;
import com.carddemo.config.JwtTokenProvider;
import com.carddemo.domain.enums.UserType;
import com.carddemo.service.DateValidationService;
import com.carddemo.service.JobSubmissionCoordinator;
import com.carddemo.service.JobSubmissionService;
import com.carddemo.service.MessageCatalogService;
import com.carddemo.service.NavigationService;
import com.carddemo.service.ReportRequestService;
import com.carddemo.support.AbstractLocalStackIT;
import com.carddemo.support.TestDataFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.autoconfigure.core.AwsAutoConfiguration;
import io.awspring.cloud.autoconfigure.core.CredentialsProviderAutoConfiguration;
import io.awspring.cloud.autoconfigure.core.RegionProviderAutoConfiguration;
import io.awspring.cloud.autoconfigure.sqs.SqsAutoConfiguration;
import io.awspring.cloud.sqs.listener.QueueNotFoundStrategy;
import io.awspring.cloud.sqs.operations.SqsOperations;
import io.awspring.cloud.sqs.operations.SqsSendOptions;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.SqsException;

/**
 * Gate 5 executed: the estate's single online-to-batch bridge, verified end to end against a real
 * object store, a real first-in-first-out queue and a real notification service.
 *
 * <h2>What this file owns</h2>
 * The report-request transaction is the only place in nineteen thousand lines of legacy source that
 * writes to the transient data queue - one queue-write paragraph, reached from one emitting loop - and
 * that single write becomes a queue publish of seventeen fixed eighty-character card images. This file
 * drives the migrated <em>endpoint</em> and then reads the cards back <em>out of the queue</em>, which is
 * the whole point: an assertion made against what a builder returned proves that the builder agrees with
 * itself, whereas an assertion made against messages the queue service actually delivered proves the
 * contract. Nothing here is mocked that the gate requires to be real - the queue, the bucket and the
 * topic are all served by the emulator this run started.
 *
 * <h2>Why the seventeen expected cards are written out by hand</h2>
 * <strong>Every expected card below is authored in this file as its own literal and no card is obtained
 * from the production card builder, from a record mapper, from a codec or from a formatter.</strong> An
 * expectation that asks the subject for the answer cannot fail when the subject is wrong; an
 * independently written expectation can, and that is the only reason to write one. The three composite
 * cards are assembled from their own declared component widths - an eighteen-character lead, a ten
 * character slot and a fifty-two character tail on one; a sixteen, ten and fifty-four split on the next;
 * and a ten, one, ten, fifty-nine split on the third - so a shift of a single byte anywhere in the card
 * fails here rather than passing quietly.
 *
 * <h2>Why the sentinel card must arrive, and why sixteen is the failure this file exists to catch</h2>
 * The emitting loop moves each card into the record area, tests it against the end-of-stream sentinel,
 * <em>sets the loop terminator</em> and only then performs the write. The sentinel is therefore
 * transmitted rather than consumed as a guard, so a complete submission is seventeen messages. An
 * implementation that treats the sentinel as a loop condition publishes sixteen and looks entirely
 * correct until a consumer waits forever for an end of stream that never came. The count, the sentinel's
 * position and the absence of an eighteenth message are all asserted separately for that reason.
 *
 * <h2>Why the repeated card bodies are called out</h2>
 * Three of the seventeen cards carry one identical comment body and two more carry another, so five of
 * the seventeen are duplicates by content. A queue that deduplicated on content would silently deliver
 * twelve or thirteen messages and report every send as accepted. The queue is therefore created with
 * content-based deduplication switched off and each card carries its own deduplication identifier, and
 * this file proves the arrangement works the only way it can be proved - by draining and counting.
 *
 * <h2>Why a failed publish must not fail the request</h2>
 * The queue definition tolerates a write error rather than raising it, and the legacy program behaves
 * accordingly: it records the response and reason codes on its diagnostic channel, puts a fixed failure
 * text on the screen and returns control to the operator. The error flag it raises is also the emitting
 * loop's terminator, so the cards after the refused one are never written while the task itself completes
 * normally. Both halves of that shape are asserted, together with the absence of the diagnostic codes
 * from anything the caller can read.
 *
 * <h2>What this file deliberately does not do</h2>
 * It declares no container, no data source and no property source of its own: the emulator, its three
 * provisioned resources and every published address belong to {@link AbstractLocalStackIT}, which is
 * extended once and is the only base class in play. It touches no table, no repository, no entity and no
 * migration. It asserts no elapsed time, no throughput and no capacity figure, and it never sleeps to
 * synchronise - every read is a bounded long poll owned by that base class.
 *
 * <p><strong>It also asserts nothing about who may submit.</strong> The boundary is driven through a
 * standalone servlet harness and handed a {@code Principal} directly, which is the right shape for this
 * subject - the cards a submission carries do not depend on who asked for them, so installing a filter
 * chain here would add machinery to a specification about bytes. The consequence is that a handed-in
 * principal is an assumption: it establishes that the boundary uses the identity it is given, and not that
 * the delivered route establishes one, that a caller presenting nothing is refused before the queue is
 * touched, or that two callers are kept in separate deduplication namespaces. Those are security
 * properties and they need the chain that mints and verifies a credential to actually run, so they are
 * asserted by {@link ReportSubmissionAuthorizationIT} over the same graph and the same emulator with the
 * real chain in front of it.
 *
 * <p>Provenance: {@code app/cbl/CORPT00C.cbl} lines 81 to 127 and 462 to 535, the queue definition at
 * {@code app/csd/CARDDEMO.CSD} lines 499 to 505, the sort symbol declarations in
 * {@code app/proc/TRANREPT.prc} and {@code app/jcl/TRANREPT.jcl}, the generation limits in
 * {@code app/jcl/DEFGDGB.jcl} and {@code app/jcl/REPTFILE.jcl}, and the symbolic map
 * {@code app/cpy-bms/CORPT00.CPY}; all read as read-only reference. Card images, widths, offsets and
 * message texts are external contract, which is metadata; no legacy source line is transcribed.
 */
@SpringBootTest(classes = AwsIntegrationIT.ReportBridgeContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("Gate 5 executed :: the online-to-batch bridge over a real queue, bucket and topic")
public class AwsIntegrationIT extends AbstractLocalStackIT {

    // ===============================================================================================
    // THE CONTRACT, RESTATED HERE RATHER THAN IMPORTED
    //
    // Every width, count, offset, type code and text below is written out as this file's own literal.
    // None is read from the production type that also declares it, because an expectation borrowed from
    // the subject proves only that the subject agrees with itself.
    // ===============================================================================================

    /** Cards one complete submission transmits, the end-of-stream card included. */
    private static final int CARD_COUNT = 17;

    /** Characters in one card, fixed by the queue definition's record size. */
    private static final int CARD_WIDTH = 80;

    /** Characters in one substituted date slot. */
    private static final int DATE_SLOT_WIDTH = 10;

    /** Characters the date-parameter card's two-slot payload occupies: ten, a separator and ten. */
    private static final int DATEPARM_PAYLOAD_WIDTH = 21;

    /** One-based ordinal of the card carrying the first substituted start-date slot. */
    private static final int ORDINAL_START_DATE_SYMBOL = 11;

    /** One-based ordinal of the card carrying the first substituted end-date slot. */
    private static final int ORDINAL_END_DATE_SYMBOL = 12;

    /** One-based ordinal of the card carrying the second start-date and end-date slots. */
    private static final int ORDINAL_DATE_PARAMETER = 15;

    /** One-based ordinal of the card carrying the card-number sort symbol. */
    private static final int ORDINAL_CARD_NUMBER_SYMBOL = 9;

    /** One-based ordinal of the card carrying the processing-date sort symbol. */
    private static final int ORDINAL_PROCESS_DATE_SYMBOL = 10;

    /** Declared lead of the start-date symbol card: the symbol name and the opening constant quote. */
    private static final String START_SYMBOL_LEAD = "PARM-START-DATE,C'";

    /** Declared lead of the end-date symbol card, two characters shorter than its sibling. */
    private static final String END_SYMBOL_LEAD = "PARM-END-DATE,C'";

    /** The closing constant quote that follows a substituted slot on a symbol card. */
    private static final String SYMBOL_CLOSING_QUOTE = "'";

    /** Spaces following the closing quote of the start-date symbol card. */
    private static final int START_SYMBOL_TRAILING_SPACES = 51;

    /** Spaces following the closing quote of the end-date symbol card. */
    private static final int END_SYMBOL_TRAILING_SPACES = 53;

    /** The single space separating the two slots of the date-parameter card. */
    private static final String DATEPARM_SEPARATOR = " ";

    /** Spaces following the second slot of the date-parameter card. */
    private static final int DATEPARM_TRAILING_SPACES = 59;

    /** Card 1: the job card. */
    private static final String CARD_JOB = "//TRNRPT00 JOB 'TRAN REPORT',CLASS=A,MSGCLASS=0,";

    /** Card 2: the notification continuation of the job card. */
    private static final String CARD_NOTIFY = "// NOTIFY=&SYSUID";

    /** Cards 3, 5 and 7: the comment card, which is why three of the seventeen bodies are identical. */
    private static final String CARD_COMMENT = "//*";

    /** Card 4: the procedure-library card. */
    private static final String CARD_JOBLIB = "//JOBLIB JCLLIB ORDER=('AWS.M2.CARDDEMO.PROC')";

    /** Card 6: the step card that invokes the report procedure. */
    private static final String CARD_EXEC_PROC = "//STEP10 EXEC PROC=TRANREPT";

    /** Card 8: the card that opens the in-stream sort symbol declarations. */
    private static final String CARD_SYMNAMES_DD = "//STEP05R.SYMNAMES DD *";

    /**
     * Card 9: the card-number sort symbol, offset 263, length 16, zoned decimal.
     *
     * <p>The type code is the contract and not an implementation detail. The same field is declared
     * zoned decimal by this job and character by another, which is exactly why an ordering comparator
     * belongs to one job rather than being shared between them. This file asserts the zoned-decimal
     * declaration because this is the job that makes it.
     */
    private static final String CARD_SYMBOL_CARD_NUMBER = "TRAN-CARD-NUM,263,16,ZD";

    /** Card 10: the processing-date sort symbol, offset 305, length 10, character. */
    private static final String CARD_SYMBOL_PROCESS_DATE = "TRAN-PROC-DT,305,10,CH";

    /** Cards 13 and 16: the in-stream terminator, the second pair of identical bodies. */
    private static final String CARD_IN_STREAM_TERMINATOR = "/*";

    /** Card 14: the card that opens the in-stream date parameter. */
    private static final String CARD_DATEPARM_DD = "//STEP10R.DATEPARM DD *";

    /** Card 17: the end-of-stream card, which is transmitted rather than consumed as a guard. */
    private static final String CARD_EOF_SENTINEL = "/*EOF";

    /** Offset of the card-number sort field, asserted as part of the symbol declaration. */
    private static final String SORT_OFFSET_CARD_NUMBER = "263";

    /** Length of the card-number sort field. */
    private static final String SORT_LENGTH_CARD_NUMBER = "16";

    /** Type code the card-number sort field carries in this job: zoned decimal. */
    private static final String SORT_TYPE_ZONED_DECIMAL = "ZD";

    /** Offset of the processing-date sort field. */
    private static final String SORT_OFFSET_PROCESS_DATE = "305";

    /** Length of the processing-date sort field. */
    private static final String SORT_LENGTH_PROCESS_DATE = "10";

    /** Type code the processing-date sort field carries: character. */
    private static final String SORT_TYPE_CHARACTER = "CH";

    /** Separator between the components of a sort symbol declaration. */
    private static final String SORT_SYMBOL_SEPARATOR = ",";

    // -----------------------------------------------------------------------------------------------
    // The submitted range. Supplied as six screen parts and expected back as two ten-character slots.
    // -----------------------------------------------------------------------------------------------

    /** Start month, as the screen collects it. */
    private static final String START_MONTH = "01";

    /** Start day, as the screen collects it. */
    private static final String START_DAY = "01";

    /** Start year, as the screen collects it. */
    private static final String START_YEAR = "2022";

    /** End month, as the screen collects it. */
    private static final String END_MONTH = "07";

    /** End day, as the screen collects it. */
    private static final String END_DAY = "06";

    /** End year, as the screen collects it. */
    private static final String END_YEAR = "2022";

    /** The ten-character start-date slot the six parts assemble into. */
    private static final String START_DATE_SLOT = START_YEAR + "-" + START_MONTH + "-" + START_DAY;

    /** The ten-character end-date slot the six parts assemble into. */
    private static final String END_DATE_SLOT = END_YEAR + "-" + END_MONTH + "-" + END_DAY;

    /** The report name the operator-supplied range resolves to, unpadded and mixed case. */
    private static final String REPORT_NAME_CUSTOM = "Custom";

    /** The acknowledgement a submitted request returns, assembled from this file's own literals. */
    private static final String ACKNOWLEDGEMENT =
            REPORT_NAME_CUSTOM + " report submitted for printing ...";

    /**
     * The text a refused queue write puts on the screen, authored here rather than borrowed.
     *
     * <p>Three dots and no space before them, and the queue named as the resource definition names it.
     * The response is read back and compared against this whole string untrimmed, because a text that is
     * merely recognisable is not the contract.
     */
    private static final String QUEUE_WRITE_REFUSED = "Unable to Write TDQ (JOBS)...";

    // -----------------------------------------------------------------------------------------------
    // The bridge's own configuration, restated
    // -----------------------------------------------------------------------------------------------

    /** The route the report-request transaction is published at. */
    private static final String REPORT_REQUEST_ROUTE = "/api/reports/request";

    /** Region every client in this run resolves its endpoints in. */
    private static final String EXPECTED_REGION = "us-east-1";

    /** The object-store bucket batch artefacts are staged in. */
    private static final String EXPECTED_BUCKET = "carddemo-batch-staging";

    /** The submission queue, named as the resource definition names it and suffixed as a FIFO queue. */
    private static final String EXPECTED_QUEUE = "JOBS.fifo";

    /** The single message group that turns the queue's ordering guarantee into a total order. */
    private static final String EXPECTED_MESSAGE_GROUP = "carddemo-job-submission";

    /** The topic terminal job events are announced on. */
    private static final String EXPECTED_TOPIC = "carddemo-job-notifications";

    /** Message attribute naming the submission a delivered card belongs to. */
    private static final String ATTRIBUTE_SUBMISSION_ID = "carddemo-submission-id";

    /** Message attribute naming a delivered card's one-based position in its submission. */
    private static final String ATTRIBUTE_CARD_ORDINAL = "carddemo-card-ordinal";

    /** Message attribute naming how many cards the submission holds. */
    private static final String ATTRIBUTE_CARD_COUNT = "carddemo-card-count";

    /**
     * A well-formed queue name that names no queue on this run's emulator.
     *
     * <p>Fixed rather than randomised: the emulator is started by this JVM and only ever has the one
     * submission queue provisioned on it, so a name that is not that one is absent by construction and
     * needs no entropy to stay absent. It is used to reach the tolerated-failure path through the queue
     * service's own refusal, with nothing injected and no production file edited.
     */
    private static final String ABSENT_QUEUE = "JOBS-absent-awsintegrationit.fifo";

    /** Cards accepted before the injected refusal, chosen so the sentinel is never reached. */
    private static final int CARDS_BEFORE_REFUSAL = 11;

    /**
     * The pinned instant every clock in this run reads, taken from the shared fixture constant.
     *
     * <p>Parsed strictly and with an explicit locale so the reading cannot depend on the ambient default
     * of the host, and offset to UTC because the fixture field carries no zone and the build pins the
     * test virtual machine to UTC. Nothing in this file reads a system clock.
     */
    private static final Clock PINNED_CLOCK = Clock.fixed(
            LocalDateTime.parse(TestDataFactory.SEEDED_ORIGINAL_TIMESTAMP,
                            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS", Locale.ROOT)
                                    .withResolverStyle(ResolverStyle.STRICT))
                    .toInstant(ZoneOffset.UTC),
            ZoneOffset.UTC);

    /** Reads replies without binding to a published contract type. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The identity the presented credential names. */
    private static final String AUTHENTICATED_USER_ID = "ADMIN001";

    /** The report-request boundary, wired by the context to the real bridge over this run's emulator. */
    @Autowired
    private ReportController reportController;

    /** The shipped translation of a raised failure into a reply. */
    @Autowired
    private GlobalExceptionHandler globalExceptionHandler;

    /** The bound settings type a component asks for a resource name, rather than asking a client. */
    @Autowired
    private AwsProperties awsProperties;

    /** Creates the specification. */
    public AwsIntegrationIT() {
        super();
    }

    /**
     * Empties the shared submission queue before every test, and proves it started empty.
     *
     * <p>The after-each reset below cannot establish this on its own, and the gap is not theoretical. It
     * covers only what <em>this</em> class leaves behind: a class that ran earlier in this JVM and failed
     * part-way through a submission leaves messages on the shared queue, and the first exact-count or
     * ordering assertion here would drain them and report a card count it cannot explain. The mirror case
     * is worse - a first test that publishes nothing would quietly erase that evidence in its own
     * after-each and pass, taking the only trace of the earlier failure with it.
     *
     * <p>Asserting the count rather than discarding it is what turns "the queue was clean" from an
     * assumption into a fact, and it names the right culprit when it is not: a non-zero count here fails
     * before a single assertion about this class's own submission has been made.
     */
    @BeforeEach
    void startFromAnEmptySubmissionQueue() {
        assertThat(resetJobSubmissionQueue())
                .as("the shared submission queue must be empty before a card-count or ordering "
                        + "assertion; a message left by an earlier specification would be drained here "
                        + "and counted against this one")
                .isZero();
    }

    /**
     * Empties the shared submission queue after every test, whatever the outcome.
     *
     * <p>Receives and deletes through the base class's deterministic reset rather than the queue
     * service's asynchronous bulk purge, so the next test genuinely starts from an empty queue instead of
     * probably starting from one. A message left behind by a partial submission is a message the next
     * ordering assertion would drain and could not explain.
     */
    @AfterEach
    void emptyTheSubmissionQueue() {
        resetJobSubmissionQueue();
    }

    /**
     * Lists every version and delete marker the object store holds under one key prefix.
     *
     * @param  keyPrefix the prefix to list under
     * @return the listing, carrying both the versions and the delete markers
     */
    private static ListObjectVersionsResponse versionsUnder(final String keyPrefix) {
        return s3Client().listObjectVersions(ListObjectVersionsRequest.builder()
                .bucket(stagingBucket())
                .prefix(keyPrefix)
                .build());
    }

    /**
     * Removes every version and every delete marker under one key prefix, by identifier.
     *
     * <p>An unqualified delete against a versioned bucket writes a delete marker and keeps the versions,
     * so nothing short of a per-version delete actually removes anything. Delete markers are versions too
     * and are removed the same way, which is what leaves the prefix genuinely unlisted afterwards.
     *
     * @param keyPrefix the prefix to empty
     */
    private static void deleteEveryVersionUnder(final String keyPrefix) {
        final ListObjectVersionsResponse listed = versionsUnder(keyPrefix);
        final List<DeleteTarget> doomed = new ArrayList<>();
        listed.versions().forEach(version ->
                doomed.add(new DeleteTarget(version.key(), version.versionId())));
        listed.deleteMarkers().forEach(marker ->
                doomed.add(new DeleteTarget(marker.key(), marker.versionId())));
        for (final DeleteTarget target : doomed) {
            s3Client().deleteObject(DeleteObjectRequest.builder()
                    .bucket(stagingBucket())
                    .key(target.key())
                    .versionId(target.versionId())
                    .build());
        }
    }

    /** One version of one key, named so a delete can address it exactly. */
    private record DeleteTarget(String key, String versionId) {
    }

    // ===============================================================================================
    // THE RESOURCES THE SETTINGS NAME ARE THE ONES THIS RUN PROVISIONED
    // ===============================================================================================

    @Nested
    @DisplayName("the emulator this run provisioned is the one the settings address")
    class TheProvisionedResources {

        /** Creates the nested specification. */
        TheProvisionedResources() {
            // Intentionally empty: a nested specification contributes tests, not state.
        }

        @Test
        @DisplayName("all six settings resolve to the provisioned region, endpoint, bucket, queue, "
                + "message group and topic")
        void allSixSettingsResolveToTheProvisionedResources() {
            final AwsProperties settings = AwsIntegrationIT.this.awsProperties;

            assertThat(settings.region())
                    .as("the region every client resolves its endpoints in")
                    .isEqualTo(EXPECTED_REGION);
            assertThat(settings.endpointOverride())
                    .as("the endpoint must be this run's ephemeral emulator address and not a fixed"
                            + " port, or a client could address a real account")
                    .isEqualTo(emulatorEndpoint());
            assertThat(settings.s3().batchStagingBucket()).isEqualTo(EXPECTED_BUCKET);
            assertThat(settings.sqs().jobQueue()).isEqualTo(EXPECTED_QUEUE);
            assertThat(settings.sqs().messageGroupId()).isEqualTo(EXPECTED_MESSAGE_GROUP);
            assertThat(settings.sns().jobNotificationTopic()).isEqualTo(EXPECTED_TOPIC);
        }

        @Test
        @DisplayName("the submission queue really is first-in-first-out, which is what preserves the "
                + "append order of the cards")
        void theSubmissionQueueIsFirstInFirstOut() {
            assertThat(isFifoQueue(jobSubmissionQueueUrl()))
                    .as("a standard queue would silently reorder the cards of a submission")
                    .isTrue();
        }

        @Test
        @DisplayName("and content-based deduplication is switched off, or the repeated card bodies "
                + "would collapse into one message each")
        void contentBasedDeduplicationIsSwitchedOff() {
            final Map<QueueAttributeName, String> attributes =
                    queueAttributes(jobSubmissionQueueUrl());

            assertThat(attributes.get(QueueAttributeName.CONTENT_BASED_DEDUPLICATION))
                    .as("three cards share one body and two more share another; deduplicating on "
                            + "content would drop five of the seventeen and report every send accepted")
                    .isEqualTo("false");
        }

        @Test
        @DisplayName("the staging bucket has object versioning enabled, which is what carries the "
                + "retained-generation semantics forward")
        void theStagingBucketIsVersioned() {
            assertThat(bucketVersioningStatus(stagingBucket()))
                    .as("an unversioned bucket accepts every staged object and keeps only the newest,"
                            + " losing the retention contract with nothing failing")
                    .isEqualTo(BucketVersioningStatus.ENABLED);
        }
    }

    // ===============================================================================================
    // THE SEVENTEEN CARDS, READ BACK OUT OF THE QUEUE
    // ===============================================================================================

    @Nested
    @DisplayName("the submission the endpoint published, drained from the real queue")
    class TheDrainedSubmission {

        /** Creates the nested specification. */
        TheDrainedSubmission() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("the endpoint accepts the confirmed request and answers with the acknowledgement, "
                + "so the cards below were published by a request that succeeded")
        void theEndpointAcceptsTheConfirmedRequest() throws Exception {
            // CORPT00C line 449: the acceptance text is the report name up to its first space followed
            // by the fixed fragment, which is why the period vocabulary carries unpadded values.
            final JsonNode reply = submitConfirmedRangeReport(AwsIntegrationIT.this.wiredBoundary());

            assertThat(reply.get("submissionAccepted").asBoolean())
                    .as("a complete submission is every card of the canonical image reaching the queue")
                    .isTrue();
            assertThat(messageOf(reply))
                    .as("the acknowledgement, character for character")
                    .isEqualTo(ACKNOWLEDGEMENT);
            assertThat(reply.get("reportPeriod").asText())
                    .as("the ordered evaluation resolved the operator-supplied range")
                    .isEqualTo("CUSTOM");
        }

        @Test
        @DisplayName("delivers exactly seventeen messages, and no eighteenth follows them")
        void deliversExactlySeventeenMessages() throws Exception {
            // CORPT00C lines 498 to 508: the loop terminator is set before the write, so the
            // end-of-stream card is transmitted and the count is seventeen rather than sixteen.
            submitConfirmedRangeReport(AwsIntegrationIT.this.wiredBoundary());

            // Asked for one more than the contract allows: the read stops as soon as the queue reports
            // nothing further, so a surplus message would be returned here and a shortfall would be too.
            assertThat(deliveredBodies(CARD_COUNT + 1))
                    .as("sixteen would mean the sentinel was consumed as a loop guard rather than"
                            + " transmitted; eighteen would mean something was published twice")
                    .hasSize(CARD_COUNT);
        }

        @Test
        @DisplayName("delivers the seventeen bodies in emission order, byte for byte and untrimmed")
        void deliversTheSeventeenBodiesInEmissionOrder() throws Exception {
            submitConfirmedRangeReport(AwsIntegrationIT.this.wiredBoundary());

            assertThat(deliveredBodies(CARD_COUNT))
                    .as("compared against seventeen expectations written out in this file, in order,"
                            + " each padded to the full record width")
                    .containsExactlyElementsOf(expectedCards(START_DATE_SLOT, END_DATE_SLOT));
        }

        @Test
        @DisplayName("delivers every body at exactly eighty characters, and eighty encoded bytes")
        void deliversEveryBodyAtExactlyEightyCharacters() throws Exception {
            // The record size the queue definition fixes at app/csd/CARDDEMO.CSD line 502.
            submitConfirmedRangeReport(AwsIntegrationIT.this.wiredBoundary());

            final List<String> bodies = deliveredBodies(CARD_COUNT);

            assertThat(bodies).hasSize(CARD_COUNT);
            for (int ordinal = 1; ordinal <= CARD_COUNT; ordinal++) {
                final String body = bodies.get(ordinal - 1);
                assertThat(body.length())
                        .as("character width of delivered card %d", ordinal)
                        .isEqualTo(CARD_WIDTH);
                assertThat(encodedWidth(body))
                        .as("encoded byte width of delivered card %d, which must equal the character"
                                + " width or the card is not a single-byte image", ordinal)
                        .isEqualTo(CARD_WIDTH);
            }
        }

        @Test
        @DisplayName("transmits the end-of-stream card as the seventeenth message, padded to the "
                + "record width like every other card")
        void transmitsTheEndOfStreamCardAsTheSeventeenthMessage() throws Exception {
            // CORPT00C lines 502 to 507: SET the terminator, THEN perform the write.
            submitConfirmedRangeReport(AwsIntegrationIT.this.wiredBoundary());

            final List<String> bodies = deliveredBodies(CARD_COUNT);

            assertThat(bodies).hasSize(CARD_COUNT);
            assertThat(bodies.get(CARD_COUNT - 1))
                    .as("the sentinel is a payload, not a loop condition, so it must arrive")
                    .isEqualTo(padded(CARD_EOF_SENTINEL));
        }

        @Test
        @DisplayName("carries all four substituted ten-character date slots into the delivered cards")
        void carriesAllFourSubstitutedDateSlots() throws Exception {
            // CORPT00C lines 106, 111, 118 and 120: four PIC X(10) slots across three cards, filled
            // from two values so the two occurrences of a date can never diverge.
            submitConfirmedRangeReport(AwsIntegrationIT.this.wiredBoundary());

            final List<String> bodies = deliveredBodies(CARD_COUNT);

            assertThat(START_DATE_SLOT.length())
                    .as("a slot is ten characters wide, and a value of any other width is a different"
                            + " card")
                    .isEqualTo(DATE_SLOT_WIDTH);
            assertThat(END_DATE_SLOT.length()).isEqualTo(DATE_SLOT_WIDTH);

            assertThat(bodies.get(ORDINAL_START_DATE_SYMBOL - 1))
                    .as("card 11 carries the first start-date slot inside a sort constant")
                    .isEqualTo(expectedStartSymbolCard(START_DATE_SLOT));
            assertThat(bodies.get(ORDINAL_END_DATE_SYMBOL - 1))
                    .as("card 12 carries the first end-date slot, behind a lead two characters"
                            + " shorter than card 11's")
                    .isEqualTo(expectedEndSymbolCard(END_DATE_SLOT));
            assertThat(bodies.get(ORDINAL_DATE_PARAMETER - 1))
                    .as("card 15 carries the second occurrence of both slots")
                    .isEqualTo(expectedDateParameterCard(START_DATE_SLOT, END_DATE_SLOT));
        }

        @Test
        @DisplayName("shapes the date-parameter card as ten characters, one separator and ten "
                + "characters, then padding")
        void shapesTheDateParameterCardAsATwentyOneBytePayload() throws Exception {
            // CORPT00C lines 117 to 121: X(10) + X(1) space + X(10) + X(59) spaces.
            submitConfirmedRangeReport(AwsIntegrationIT.this.wiredBoundary());

            final String card = deliveredBodies(CARD_COUNT).get(ORDINAL_DATE_PARAMETER - 1);
            final String payload = card.substring(0, DATEPARM_PAYLOAD_WIDTH);

            assertThat(payload)
                    .as("the whole payload is the two dates and the single separator between them")
                    .isEqualTo(START_DATE_SLOT + DATEPARM_SEPARATOR + END_DATE_SLOT);
            assertThat(payload.substring(0, DATE_SLOT_WIDTH)).isEqualTo(START_DATE_SLOT);
            assertThat(payload.substring(DATE_SLOT_WIDTH, DATE_SLOT_WIDTH + 1))
                    .as("exactly one separator, not two and not a comma")
                    .isEqualTo(DATEPARM_SEPARATOR);
            assertThat(payload.substring(DATE_SLOT_WIDTH + 1)).isEqualTo(END_DATE_SLOT);
            assertThat(card.substring(DATEPARM_PAYLOAD_WIDTH))
                    .as("and the remaining fifty-nine characters are padding")
                    .isEqualTo(spaces(DATEPARM_TRAILING_SPACES));
        }

        @Test
        @DisplayName("declares the card-number sort symbol as zoned decimal at offset 263 for 16, and "
                + "the processing-date symbol as character at offset 305 for 10")
        void declaresBothSortSymbolsWithTheirOwnTypeCodes() throws Exception {
            // The two type codes are different for two different fields, and the card-number field is
            // typed differently again by another job - which is why an ordering comparator is per job
            // and never shared. This job declares it zoned decimal.
            submitConfirmedRangeReport(AwsIntegrationIT.this.wiredBoundary());

            final List<String> bodies = deliveredBodies(CARD_COUNT);

            assertThat(bodies.get(ORDINAL_CARD_NUMBER_SYMBOL - 1))
                    .isEqualTo(padded("TRAN-CARD-NUM" + SORT_SYMBOL_SEPARATOR + SORT_OFFSET_CARD_NUMBER
                            + SORT_SYMBOL_SEPARATOR + SORT_LENGTH_CARD_NUMBER + SORT_SYMBOL_SEPARATOR
                            + SORT_TYPE_ZONED_DECIMAL));
            assertThat(bodies.get(ORDINAL_PROCESS_DATE_SYMBOL - 1))
                    .isEqualTo(padded("TRAN-PROC-DT" + SORT_SYMBOL_SEPARATOR + SORT_OFFSET_PROCESS_DATE
                            + SORT_SYMBOL_SEPARATOR + SORT_LENGTH_PROCESS_DATE + SORT_SYMBOL_SEPARATOR
                            + SORT_TYPE_CHARACTER));
            assertThat(SORT_TYPE_ZONED_DECIMAL)
                    .as("the two codes must differ, or one field's typing has been copied onto the"
                            + " other")
                    .isNotEqualTo(SORT_TYPE_CHARACTER);
        }

        @Test
        @DisplayName("delivers all five repeated card bodies rather than collapsing them, so no card "
                + "is lost to deduplication")
        void deliversAllFiveRepeatedCardBodies() throws Exception {
            // Three comment cards carry one identical body and two terminator cards carry another. A
            // content-deduplicating queue would deliver thirteen messages and report seventeen sends
            // accepted, which is why this is proved by draining and counting.
            submitConfirmedRangeReport(AwsIntegrationIT.this.wiredBoundary());

            final List<String> bodies = deliveredBodies(CARD_COUNT);

            assertThat(bodies).hasSize(CARD_COUNT);
            assertThat(bodies)
                    .as("the comment card appears three times over")
                    .filteredOn(padded(CARD_COMMENT)::equals)
                    .hasSize(3);
            assertThat(bodies)
                    .as("and the in-stream terminator twice")
                    .filteredOn(padded(CARD_IN_STREAM_TERMINATOR)::equals)
                    .hasSize(2);
        }

        @Test
        @DisplayName("places every card of the submission in the single declared message group, which "
                + "is what turns the queue's ordering guarantee into a total order")
        void placesEveryCardInTheSingleDeclaredMessageGroup() throws Exception {
            // DISPOSITION(MOD) on the queue definition: the cards are appended, so the drained order
            // must equal the emission order, and one stable group is what makes that true.
            submitConfirmedRangeReport(AwsIntegrationIT.this.wiredBoundary());

            final List<Message> messages = delivered(CARD_COUNT);

            assertThat(messages).hasSize(CARD_COUNT);
            assertThat(messages)
                    .extracting(message ->
                            message.attributes().get(MessageSystemAttributeName.MESSAGE_GROUP_ID))
                    .as("one group for the whole submission, and the group the settings name")
                    .containsOnly(EXPECTED_MESSAGE_GROUP);
        }

        @Test
        @DisplayName("stamps each delivered card with its own ordinal and the submission total, so a "
                + "consumer can restore the job stream")
        void stampsEachDeliveredCardWithItsOrdinalAndTheTotal() throws Exception {
            submitConfirmedRangeReport(AwsIntegrationIT.this.wiredBoundary());

            final List<Message> messages = delivered(CARD_COUNT);

            assertThat(messages).hasSize(CARD_COUNT);
            final List<String> ordinals = new ArrayList<>(messages.size());
            for (int ordinal = 1; ordinal <= CARD_COUNT; ordinal++) {
                final Message message = messages.get(ordinal - 1);
                ordinals.add(message.messageAttributes().get(ATTRIBUTE_CARD_ORDINAL).stringValue());
                assertThat(message.messageAttributes().get(ATTRIBUTE_CARD_COUNT).stringValue())
                        .as("every card names the same submission total")
                        .isEqualTo(Integer.toString(CARD_COUNT));
                assertThat(message.messageAttributes().get(ATTRIBUTE_SUBMISSION_ID).stringValue())
                        .as("every card names the submission it belongs to")
                        .isNotBlank();
            }

            assertThat(ordinals)
                    .as("one ordinal per card, ascending and without a gap or a repeat")
                    .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12",
                            "13", "14", "15", "16", "17");
        }
    }

    // ===============================================================================================
    // ONE MESSAGE PER CARD: THE FIXED UNBLOCKED RECORD FORMAT ADMITS NO AGGREGATION
    // ===============================================================================================

    @Nested
    @DisplayName("one message per card, because a card is a record and not part of an aggregate")
    class OneMessagePerCard {

        /** Creates the nested specification. */
        OneMessagePerCard() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("the bridge issues seventeen single-message publishes and never one batch publish")
        void issuesSeventeenSinglePublishesAndNoBatchPublish() throws Exception {
            // RECORDFORMAT(FIXED) BLOCKFORMAT(UNBLOCKED) at app/csd/CARDDEMO.CSD lines 503 and 504.
            final SqsOperations client = recordingClient();

            submitConfirmedRangeReport(boundaryPublishingThrough(client, EXPECTED_QUEUE));

            verify(client, times(CARD_COUNT)).<String>send(any());
            verify(client, never()).sendMany(any(), any());
            assertThat(deliveredBodies(CARD_COUNT))
                    .as("and the cards the single publishes carried really did arrive, in order")
                    .containsExactlyElementsOf(expectedCards(START_DATE_SLOT, END_DATE_SLOT));
        }

        @Test
        @DisplayName("so no delivered body ever carries two cards, which is what aggregation would "
                + "look like on the wire")
        void noDeliveredBodyCarriesTwoCards() throws Exception {
            submitConfirmedRangeReport(AwsIntegrationIT.this.wiredBoundary());

            assertThat(deliveredBodies(CARD_COUNT))
                    .hasSize(CARD_COUNT)
                    .allSatisfy(body -> assertThat(body.length())
                            .as("a body of one hundred and sixty characters would be two cards in one"
                                    + " message")
                            .isEqualTo(CARD_WIDTH));
        }
    }

    // ===============================================================================================
    // A REFUSED WRITE IS TOLERATED: THE QUEUE DEFINITION IGNORES IT AND THE CALLER CONTINUES
    // ===============================================================================================

    @Nested
    @DisplayName("a refused write, which the queue definition tolerates rather than raises")
    class ARefusedWrite {

        /** Creates the nested specification. */
        ARefusedWrite() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("answers the caller successfully rather than with a server error, because the "
                + "request completed on the mainframe and so completes here")
        void answersTheCallerSuccessfullyRatherThanWithAServerError() throws Exception {
            // ERROROPTION(IGNORE) at app/csd/CARDDEMO.CSD line 501, and CORPT00C lines 528 to 534:
            // the failure is displayed and put on the screen, and control returns to the operator.
            final int status =
                    submitAndReadStatus(boundaryPublishingThrough(refusingClient(), ABSENT_QUEUE));

            assertThat(status)
                    .as("turning a tolerated write error into a failed request would be a behavioural"
                            + " regression rather than a stricter contract")
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("puts the fixed failure text on the reply, untrimmed and character for character")
        void putsTheFixedFailureTextOnTheReply() throws Exception {
            final JsonNode reply =
                    submitConfirmedRangeReport(boundaryPublishingThrough(refusingClient(),
                            ABSENT_QUEUE));

            assertThat(messageOf(reply))
                    .as("compared against a literal written out in this file, whole and untrimmed")
                    .isEqualTo(QUEUE_WRITE_REFUSED);
            assertThat(reply.get("submissionAccepted").asBoolean())
                    .as("nothing was published, so the request was not accepted for printing")
                    .isFalse();
            assertThat(reply.get("generalError").asBoolean())
                    .as("the error flag the queue-write paragraph raises")
                    .isTrue();
        }

        @Test
        @DisplayName("leaves the cards after the refused one unsent while the caller still completes")
        void leavesTheCardsAfterTheRefusedOneUnsent() throws Exception {
            // The error flag the refusal raises is also the emitting loop's own terminator at CORPT00C
            // line 499, so the remaining cards are never written. The task itself ends normally.
            final JsonNode reply = submitConfirmedRangeReport(
                    boundaryPublishingThrough(refusingAfter(CARDS_BEFORE_REFUSAL), EXPECTED_QUEUE));

            assertThat(messageOf(reply)).isEqualTo(QUEUE_WRITE_REFUSED);
            assertThat(reply.get("submissionAccepted").asBoolean()).isFalse();

            final List<String> delivered = deliveredBodies(CARD_COUNT);

            assertThat(delivered)
                    .as("the queue holds the cards written before the refusal and none after it")
                    .containsExactlyElementsOf(
                            expectedCards(START_DATE_SLOT, END_DATE_SLOT)
                                    .subList(0, CARDS_BEFORE_REFUSAL));
            assertThat(delivered)
                    .as("the end-of-stream card is never reached, so a consumer sees no end of stream")
                    .doesNotContain(padded(CARD_EOF_SENTINEL));
        }

        @Test
        @DisplayName("says nothing about the diagnostic codes, the carrier or the job stream in "
                + "anything the caller can read")
        void saysNothingAboutTheDiagnosticCodesOrTheJobStream() throws Exception {
            // CORPT00C line 529 displays the response and reason codes on the diagnostic channel and
            // line 531 puts only the fixed text on the screen. The reply must reproduce that division.
            final String reply = boundaryPublishingThrough(refusingClient(), ABSENT_QUEUE)
                    .perform(post(REPORT_REQUEST_ROUTE)
                            .principal(verifiedPrincipal())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(confirmedRangeRequest()))
                    .andReturn()
                    .getResponse()
                    .getContentAsString(StandardCharsets.UTF_8);

            assertThat(reply)
                    .as("the reply carries the operator's text and nothing the operator was never"
                            + " shown: no diagnostic code, no carrier detail, no stack frame and no"
                            + " job-stream text")
                    .contains(QUEUE_WRITE_REFUSED)
                    .doesNotContain("RESP:")
                    .doesNotContain("REAS:")
                    .doesNotContain("SqsException")
                    .doesNotContain("QueueDoesNotExist")
                    .doesNotContain("software.amazon")
                    .doesNotContain("Exception")
                    .doesNotContain("Caused by")
                    .doesNotContain("\tat ")
                    .doesNotContain(ABSENT_QUEUE)
                    .doesNotContain(emulatorEndpoint())
                    .doesNotContain(emulatorAccessKey())
                    .doesNotContain(emulatorSecretKey())
                    .doesNotContain(CARD_JOB)
                    .doesNotContain(CARD_JOBLIB)
                    .doesNotContain(CARD_EOF_SENTINEL);
        }
    }

    // ===============================================================================================
    // OBJECT STAGING: EXACT BYTES, AND A PRIOR VERSION THAT SURVIVES AN OVERWRITE
    // ===============================================================================================

    @Nested
    @DisplayName("batch artefact staging, against the real object store")
    class BatchArtefactStaging {

        /**
         * Key prefix every staged artefact in this group is written under.
         *
         * <p>Scoped so no neighbouring test lists it, and completed per test below so that no version any
         * other test wrote can appear in a listing this group makes.
         */
        private static final String STAGED_KEY_PREFIX = "gate5-aws-integration/transaction-report-";

        /** The first generation's exact bytes. */
        private static final String FIRST_GENERATION = "first staged generation";

        /** The second generation's exact bytes, deliberately a different length. */
        private static final String SECOND_GENERATION = "second staged generation, longer";

        /**
         * The key this test writes under, unique to it.
         *
         * <p>The bucket keeps versions, so a key shared between tests accumulates them: a listing would
         * then return a predecessor's version, and an assertion that a prior generation survived an
         * overwrite could be satisfied by a version this test never wrote - including in a bucket that
         * had stopped keeping versions altogether. A key nothing else has ever written makes the listing
         * a statement about this test's own puts and nothing else.
         */
        private final String stagedKey =
                STAGED_KEY_PREFIX + UUID.randomUUID().toString().replace("-", "") + ".txt";

        /** Creates the nested specification. */
        BatchArtefactStaging() {
            // Intentionally empty.
        }

        /**
         * Removes every version and every delete marker under this test's key, whatever the outcome.
         *
         * <p>A versioned bucket does not forget: an unqualified delete writes a delete marker and leaves
         * the versions in place, so the removal is by version identifier, one call per version and one per
         * marker. The listing is then repeated and required to be empty, because a version left behind is
         * exactly the defect this per-test key exists to prevent.
         */
        @AfterEach
        void removeEveryVersionOfThisTestsKey() {
            deleteEveryVersionUnder(this.stagedKey);

            final ListObjectVersionsResponse remaining = versionsUnder(this.stagedKey);
            assertThat(remaining.versions())
                    .as("no version of %s may outlive the test that wrote it", this.stagedKey)
                    .isEmpty();
            assertThat(remaining.deleteMarkers())
                    .as("and no delete marker either, because a marker is itself a version", this.stagedKey)
                    .isEmpty();
        }

        @Test
        @DisplayName("a staged artefact is returned byte-identical, compared as bytes and never as a "
                + "trimmed string")
        void aStagedArtefactIsReturnedByteIdentical() {
            final byte[] staged = FIRST_GENERATION.getBytes(StandardCharsets.US_ASCII);

            putObject(stagingBucket(), this.stagedKey, staged);

            assertThat(objectBytes(stagingBucket(), this.stagedKey))
                    .as("a fixed-width artefact differs from a correct one by a trailing space, so the"
                            + " comparison is over bytes")
                    .isEqualTo(staged);
            assertThat(objectExists(stagingBucket(), this.stagedKey)).isTrue();
            assertThat(versionsUnder(this.stagedKey).versions())
                    .as("one put under a key nothing else has written is exactly one version")
                    .hasSize(1);
        }

        @Test
        @DisplayName("overwriting a staged artefact keeps the prior version retrievable, which is what "
                + "carries the retained-generation semantics forward")
        void overwritingKeepsThePriorVersionRetrievable() {
            // The legacy output data sets were retained generations, and the two job streams that
            // declare a limit for the report base disagree - one says five and the other ten - which is
            // resolved to ten in the decision log. No retention COUNT is asserted here: what the contract
            // needs is that a prior generation survives an overwrite at all.
            final byte[] first = FIRST_GENERATION.getBytes(StandardCharsets.US_ASCII);
            final byte[] second = SECOND_GENERATION.getBytes(StandardCharsets.US_ASCII);

            putObject(stagingBucket(), this.stagedKey, first);
            putObject(stagingBucket(), this.stagedKey, second);

            assertThat(objectBytes(stagingBucket(), this.stagedKey))
                    .as("the newest generation is what an unqualified read returns")
                    .isEqualTo(second);

            final List<ObjectVersion> versions = versionsUnder(this.stagedKey).versions();

            // Exactly two, not merely more than one. The key is this test's own, so two puts can produce
            // two versions and nothing else can contribute one: a bucket that had stopped keeping
            // versions would report one here and could not be covered by a predecessor's leftover.
            assertThat(versions)
                    .as("two puts under a key nothing else has written are two versions; an unversioned"
                            + " bucket would report one and the prior generation would be gone")
                    .hasSize(2);

            final List<ObjectVersion> superseded = versions.stream()
                    .filter(version -> !Boolean.TRUE.equals(version.isLatest()))
                    .toList();
            assertThat(superseded)
                    .as("of this test's two versions exactly one is superseded, and it is the one the"
                            + " first put wrote")
                    .hasSize(1);

            assertThat(readVersion(this.stagedKey, superseded.get(0).versionId()))
                    .as("and the prior generation still reads back byte-identical")
                    .isEqualTo(first);
        }

        /**
         * Reads one nominated version of a staged artefact as exact bytes.
         *
         * @param  key       the key to read under
         * @param  versionId the version to read
         * @return that version's bytes
         * @throws AssertionError if the version cannot be read
         */
        private static byte[] readVersion(final String key, final String versionId) {
            try (ResponseInputStream<GetObjectResponse> body = s3Client().getObject(
                    GetObjectRequest.builder()
                            .bucket(stagingBucket())
                            .key(key)
                            .versionId(versionId)
                            .build())) {
                return body.readAllBytes();
            } catch (final java.io.IOException unreadable) {
                throw new AssertionError("version " + versionId + " of " + key
                        + " could not be read", unreadable);
            }
        }

        /**
         * Removes every version and every delete marker beneath one key prefix.
         *
         * <p>Both collections are enumerated, because they are reported separately and a bucket can hold a
         * delete marker for a key that has no remaining version. Deleting by version identifier is what
         * actually removes bytes; a delete without one would add yet another marker.
         *
         * @param prefix the key prefix to clear; nothing outside it is touched
         */
        private static void removeAllVersionsUnder(final String prefix) {
            final ListObjectVersionsResponse listed = s3Client().listObjectVersions(
                    ListObjectVersionsRequest.builder()
                            .bucket(stagingBucket())
                            .prefix(prefix)
                            .build());
            for (final ObjectVersion version : listed.versions()) {
                deleteVersion(version.key(), version.versionId());
            }
            for (final DeleteMarkerEntry marker : listed.deleteMarkers()) {
                deleteVersion(marker.key(), marker.versionId());
            }
        }

        /**
         * Removes one nominated version of one key.
         *
         * @param key       the key to remove a version of
         * @param versionId the version to remove
         */
        private static void deleteVersion(final String key, final String versionId) {
            s3Client().deleteObject(DeleteObjectRequest.builder()
                    .bucket(stagingBucket())
                    .key(key)
                    .versionId(versionId)
                    .build());
        }
    }

    // ===============================================================================================
    // TERMINAL JOB EVENTS: A REAL PUBLISH, FAN-OUT ONLY
    // ===============================================================================================

    @Nested
    @DisplayName("terminal job notification, against the real notification service")
    class TerminalJobNotification {

        /** Creates the nested specification. */
        TerminalJobNotification() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("a publish to the provisioned topic is accepted, and the announcement is fan-out "
                + "with no subscriber the estate ever had")
        void aPublishToTheProvisionedTopicIsAccepted() {
            final PublishResponse published = snsClient().publish(PublishRequest.builder()
                    .topicArn(jobNotificationTopicArn())
                    .message("transaction report job reached a terminal state")
                    .build());

            assertThat(published.messageId())
                    .as("the notification service accepted the announcement")
                    .isNotBlank();
            assertThat(jobNotificationTopicArn())
                    .as("and it was published to the topic the settings name")
                    .endsWith(EXPECTED_TOPIC);
        }
    }

    // ===============================================================================================
    // THE INDEPENDENT ORACLE: seventeen cards, assembled from this file's own literals, in order
    // ===============================================================================================

    /**
     * The seventeen card images one submission of the given range transmits, in publication order.
     *
     * <p>Assembled from the literals declared above and from nothing else. The fourteen fixed cards are
     * padded to the record width here rather than being written with their trailing spaces, because a
     * literal carrying eighty characters of which sixty are invisible is a literal nobody can review; the
     * three composite cards are built from their declared component widths so a one-byte shift inside a
     * card is caught rather than absorbed by the padding.
     *
     * @param  startDate the ten-character start-date slot the submission carries
     * @param  endDate the ten-character end-date slot the submission carries
     * @return the seventeen expected bodies, each exactly {@link #CARD_WIDTH} characters
     */
    private static List<String> expectedCards(final String startDate, final String endDate) {
        return List.of(
                padded(CARD_JOB),
                padded(CARD_NOTIFY),
                padded(CARD_COMMENT),
                padded(CARD_JOBLIB),
                padded(CARD_COMMENT),
                padded(CARD_EXEC_PROC),
                padded(CARD_COMMENT),
                padded(CARD_SYMNAMES_DD),
                padded(CARD_SYMBOL_CARD_NUMBER),
                padded(CARD_SYMBOL_PROCESS_DATE),
                expectedStartSymbolCard(startDate),
                expectedEndSymbolCard(endDate),
                padded(CARD_IN_STREAM_TERMINATOR),
                padded(CARD_DATEPARM_DD),
                expectedDateParameterCard(startDate, endDate),
                padded(CARD_IN_STREAM_TERMINATOR),
                padded(CARD_EOF_SENTINEL));
    }

    /**
     * Card eleven: an eighteen-character lead, the substituted slot, the closing quote and its padding.
     *
     * @param  startDate the ten-character start-date slot
     * @return the expected eighty-character body
     */
    private static String expectedStartSymbolCard(final String startDate) {
        return START_SYMBOL_LEAD + startDate + SYMBOL_CLOSING_QUOTE
                + spaces(START_SYMBOL_TRAILING_SPACES);
    }

    /**
     * Card twelve: a sixteen-character lead, the substituted slot, the closing quote and its padding.
     *
     * <p>The lead is two characters shorter than card eleven's and the padding two characters longer, so
     * the two cards are not variants of one shape and cannot be derived from one another.
     *
     * @param  endDate the ten-character end-date slot
     * @return the expected eighty-character body
     */
    private static String expectedEndSymbolCard(final String endDate) {
        return END_SYMBOL_LEAD + endDate + SYMBOL_CLOSING_QUOTE + spaces(END_SYMBOL_TRAILING_SPACES);
    }

    /**
     * Card fifteen: the two remaining substituted slots, separated by one space and then padded.
     *
     * @param  startDate the ten-character start-date slot
     * @param  endDate the ten-character end-date slot
     * @return the expected eighty-character body
     */
    private static String expectedDateParameterCard(final String startDate, final String endDate) {
        return startDate + DATEPARM_SEPARATOR + endDate + spaces(DATEPARM_TRAILING_SPACES);
    }

    /**
     * Right-pads a card's content to the fixed record width.
     *
     * @param  content the card's visible content
     * @return the content followed by spaces to {@link #CARD_WIDTH}
     */
    private static String padded(final String content) {
        return content + spaces(CARD_WIDTH - content.length());
    }

    /**
     * @param  count how many spaces are wanted
     * @return a run of that many spaces
     */
    private static String spaces(final int count) {
        return " ".repeat(count);
    }

    // ===============================================================================================
    // DRIVING THE ENDPOINT, AND READING THE QUEUE
    // ===============================================================================================

    /**
     * Posts one confirmed operator-range report request to the report-request route and returns the
     * parsed reply.
     *
     * <p>The request is presented with a verified credential carrying the authority the security layer
     * grants a signed-on identity, because the route admits any signed-on caller and is not one of the
     * administratively gated ones. The confirmation character is supplied, so the confirmation gate is
     * satisfied and the submission proceeds rather than stopping at the prompt.
     *
     * @param  boundary the servlet harness over the boundary under test
     * @return the parsed reply body
     * @throws Exception if the request cannot be dispatched or the reply cannot be read
     */
    private static JsonNode submitConfirmedRangeReport(final MockMvc boundary) throws Exception {
        final String reply = boundary.perform(post(REPORT_REQUEST_ROUTE)
                        .principal(verifiedPrincipal())
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(confirmedRangeRequest()))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(reply);
    }

    /**
     * Posts the same request and returns the whole servlet reply, for a test that reads the status.
     *
     * @param  boundary the servlet harness over the boundary under test
     * @return the status the boundary answered with
     * @throws Exception if the request cannot be dispatched
     */
    private static int submitAndReadStatus(final MockMvc boundary) throws Exception {
        return boundary.perform(post(REPORT_REQUEST_ROUTE)
                        .principal(verifiedPrincipal())
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(confirmedRangeRequest()))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    /**
     * The transmitted screen: the operator-range marker, the six date parts and the confirmation.
     *
     * @return the request body
     */
    private static String confirmedRangeRequest() {
        return "{\"customSelection\":\"S\","
                + "\"startMonth\":\"" + START_MONTH + "\","
                + "\"startDay\":\"" + START_DAY + "\","
                + "\"startYear\":\"" + START_YEAR + "\","
                + "\"endMonth\":\"" + END_MONTH + "\","
                + "\"endDay\":\"" + END_DAY + "\","
                + "\"endYear\":\"" + END_YEAR + "\","
                + "\"confirm\":\"Y\","
                + "\"keyAction\":\"ENTER\","
                + "\"navigationContext\":{\"fromTransactionId\":\"CR00\","
                + "\"programContext\":\"REENTER\"}}";
    }

    /**
     * A principal carrying the authority a verified credential establishes.
     *
     * <p>The authority string is taken from the single mapping the security layer applies rather than
     * written out, because it is an <em>input</em> to this specification and not one of its expectations:
     * an authority guessed here would not be the one a signed-on caller actually holds.
     *
     * @return the established identity
     */
    private static Principal verifiedPrincipal() {
        return new PreAuthenticatedAuthenticationToken(AUTHENTICATED_USER_ID, null,
                List.of(new SimpleGrantedAuthority(JwtTokenProvider.authorityOf(UserType.ADMIN))));
    }

    /**
     * Reads messages the queue service actually delivered, in delivery order.
     *
     * @param  expected how many to stop after; the read also stops as soon as the queue reports nothing
     *                 further, which is what makes a short delivery observable rather than a hang
     * @return the delivered messages
     */
    private static List<Message> delivered(final int expected) {
        return drainQueue(jobSubmissionQueueUrl(), expected);
    }

    /**
     * Reads the delivered bodies, in delivery order and untrimmed.
     *
     * @param  expected how many to stop after
     * @return the delivered bodies exactly as the queue returned them
     */
    private static List<String> deliveredBodies(final int expected) {
        final List<Message> messages = delivered(expected);
        final List<String> bodies = new ArrayList<>(messages.size());
        for (final Message message : messages) {
            bodies.add(message.body());
        }
        return bodies;
    }

    /**
     * @param  value the text to measure
     * @return how many bytes the text occupies in the single-byte encoding a card image is defined in
     */
    private static int encodedWidth(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Recovers the summary text a reply carries.
     *
     * @param  body the parsed reply
     * @return the text, or the empty string when the reply carries none
     */
    private static String messageOf(final JsonNode body) {
        final JsonNode message = body.get("message");
        return (message == null || !message.isTextual()) ? "" : message.asText();
    }

    /**
     * The servlet harness over the boundary the context wired to this run's emulator.
     *
     * <p>Assembled per test from the context's own controller and its own advice, so the graph under test
     * is the shipped one: the real contract conversion, the real report-request transaction, the real
     * queue bridge and the real messaging client the settings addressed at the emulator.
     *
     * @return the harness
     */
    private MockMvc wiredBoundary() {
        return MockMvcBuilders.standaloneSetup(this.reportController)
                .setControllerAdvice(this.globalExceptionHandler)
                .build();
    }

    /**
     * A boundary assembled over a nominated messaging client, for the tolerated-failure specifications.
     *
     * <p>Everything except the client is the shipped collaborator, and the clock is the pinned one. No
     * container is declared, no property is overridden and no production file is edited: only the client
     * the bridge publishes through differs, which is where the failure being reproduced originates.
     *
     * @param  operations the messaging client this boundary publishes through
     * @param  queueName the destination the bridge is configured with
     * @return a harness over a boundary wired to that client
     */
    private static MockMvc boundaryPublishingThrough(final SqsOperations operations,
            final String queueName) {
        final JobSubmissionService bridge = new JobSubmissionService(operations, queueName,
                EXPECTED_MESSAGE_GROUP, submission -> submission.get(), ObservationRegistry.NOOP);
        final ReportRequestService transaction = new ReportRequestService(new DateValidationService(),
                bridge, new MessageCatalogService(), new NavigationService(), PINNED_CLOCK);
        final ReportController boundary = new ReportController(transaction,
                new ReportContractAdapter(new ConversationStateAdapter(new NavigationService())),
                new SimpleMeterRegistry());
        return MockMvcBuilders.standaloneSetup(boundary)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /**
     * A messaging client bound to this run's emulator that refuses a destination it cannot resolve.
     *
     * <p>The messaging library's own default for an unresolvable destination is to <em>create</em> it,
     * which would turn an absent queue into a successful publish and never reach the tolerated-failure
     * path at all. The shipped configuration fixes the strategy to refusal, so this client is configured
     * the way the application's is and the refusal that follows is the queue service's own rather than
     * anything this file injected.
     *
     * @return a refusing client
     */
    private static SqsTemplate refusingClient() {
        return SqsTemplate.builder()
                .sqsAsyncClient(sqsAsyncClient())
                .configure(options -> options.queueNotFoundStrategy(QueueNotFoundStrategy.FAIL))
                .build();
    }

    /**
     * A messaging client that publishes to the real queue up to an ordinal and then refuses.
     *
     * <p>The accepted cards are published for real and are read back out of the real queue; only the
     * refusal is introduced, which is the one part of the contract an emulator cannot be asked to produce
     * on demand. That is what makes the partial-publish shape observable rather than merely asserted.
     *
     * @param  lastAcceptedOrdinal the last one-based ordinal that reaches the queue
     * @return a client that refuses every publish after that ordinal
     */
    private static SqsOperations refusingAfter(final int lastAcceptedOrdinal) {
        final SqsTemplate real = SqsTemplate.builder().sqsAsyncClient(sqsAsyncClient()).build();
        final SqsOperations client = mock(SqsOperations.class);
        final AtomicInteger attempts = new AtomicInteger();
        when(client.<String>send(any())).thenAnswer(invocation -> {
            if (attempts.incrementAndGet() > lastAcceptedOrdinal) {
                throw SqsException.builder()
                        .message("the queue service refused this publish")
                        .build();
            }
            final Consumer<SqsSendOptions<String>> options = invocation.getArgument(0);
            return real.<String>send(options);
        });
        return client;
    }

    /**
     * A messaging client that publishes every card to the real queue and records how it was asked to.
     *
     * <p>Used only where the question is <em>how</em> the cards were published rather than what arrived:
     * the single-message operation is delegated to the real client, and the batch operation is left
     * unstubbed so that a call to it can be shown never to have happened.
     *
     * @return a recording client over the real one
     */
    private static SqsOperations recordingClient() {
        final SqsTemplate real = SqsTemplate.builder().sqsAsyncClient(sqsAsyncClient()).build();
        final SqsOperations client = mock(SqsOperations.class);
        when(client.<String>send(any())).thenAnswer(invocation -> {
            final Consumer<SqsSendOptions<String>> options = invocation.getArgument(0);
            return real.<String>send(options);
        });
        return client;
    }

    /**
     * The report-request surface and the queue bridge behind it, assembled explicitly.
     *
     * <p>Listed rather than scanned so a reader can see in one place exactly what took part.
     * <strong>Nothing on the path from the endpoint to the queue is stubbed:</strong> the contract
     * conversion, the report-request transaction, the shared date subprogram, the message catalogue, the
     * navigation rules, the queue bridge and the messaging client the settings addressed at this run's
     * emulator are all the shipped ones.
     *
     * <p>Only the four auto-configurations the queue path needs are imported, and no application
     * auto-configuration at all. That is what keeps this specification free of a data source, a schema
     * migration and a persistence unit it has no business owning: this class extends the emulator base
     * alone, so a component that reached for a database here would have none, and importing the whole
     * application would have been the same as extending both bases.
     *
     * <p>Three beans are contributed rather than imported, and each for a reason:
     * <ul>
     *   <li>The clock is the pinned instant, so a derived period means the same thing on every run and on
     *       every host.</li>
     *   <li>The metrics registry is a simple in-memory one, because the boundary times every turn and the
     *       exposition of those timings is another specification's subject.</li>
     *   <li>The serialization boundary is an in-process one. The shipped implementation coordinates
     *       through the database that every replica of a deployment shares, and there is no database
     *       here; a single test virtual machine publishing one submission at a time needs nothing more
     *       than to run the work, and the boundary's own contract is that the work returns the outcome.
     *       This is composition rather than a stub of anything the gate measures - the queue on the far
     *       side of it is real.</li>
     * </ul>
     *
     * <p>The notification client is the one the emulator base built, so the topic-resolving bean the
     * shipped configuration declares resolves against the topic this run actually provisioned.
     */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({CredentialsProviderAutoConfiguration.class,
            RegionProviderAutoConfiguration.class, AwsAutoConfiguration.class,
            SqsAutoConfiguration.class})
    @Import({AwsConfig.class, ReportController.class, ReportContractAdapter.class,
            ConversationStateAdapter.class, GlobalExceptionHandler.class, ReportRequestService.class,
            JobSubmissionService.class, DateValidationService.class, MessageCatalogService.class,
            NavigationService.class})
    static class ReportBridgeContext {

        /** Creates the configuration. */
        ReportBridgeContext() {
            // Intentionally empty: this slice contributes beans, not state.
        }

        /**
         * The pinned clock every collaborator in the graph reads.
         *
         * @return the shared fixed clock
         */
        @Bean
        Clock fixedClock() {
            return PINNED_CLOCK;
        }

        /**
         * The registry the boundary's turn timer is registered against.
         *
         * @return an in-memory registry
         */
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        /**
         * The registry the outbound publish observation is recorded against.
         *
         * @return a no-op registry, because this specification asserts messages and not observations
         */
        @Bean
        ObservationRegistry observationRegistry() {
            return ObservationRegistry.NOOP;
        }

        /**
         * The serialization boundary one card stream is published inside.
         *
         * @return an in-process boundary that runs the work and returns its outcome
         */
        @Bean
        JobSubmissionCoordinator submissionCoordinator() {
            return submission -> submission.get();
        }

        /**
         * The notification client, taken from the emulator this run started.
         *
         * @return the shared client bound to the running emulator
         */
        @Bean
        SnsClient notificationClient() {
            return snsClient();
        }
    }
}
