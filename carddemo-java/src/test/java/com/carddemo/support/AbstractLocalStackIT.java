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
package com.carddemo.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Timeout;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.PutBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.VersioningConfiguration;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.CreateTopicRequest;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.PurgeQueueRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * Shared base for every integration test that needs a real message-queue service rather than a
 * stand-in for one.
 *
 * <h2>What this provides</h2>
 * One LocalStack Community emulator with the object-store, queue and notification services enabled;
 * one client per service built against it; <strong>the three resources the migrated module names,
 * already provisioned</strong>; and the queue operations an interface test needs - create a
 * first-in-first-out queue, publish a fixed-width record, drain the queue in delivery order, and
 * empty it between tests. Nothing about the migrated application is configured here; a subclass
 * wires whatever component it is exercising against {@link #sqsAsyncClient()}.
 *
 * <h2>The three resources are provisioned HERE, not by a bootstrap script</h2>
 * {@code carddemo-java/docker-compose.yml} bind-mounts
 * {@code carddemo-java/localstack/init/01-create-aws-resources.sh} into the <em>development stack's</em>
 * emulator, and that hook provisions the same three resources for a developer running the stack by
 * hand. A Testcontainers-managed emulator receives no such mount, so a test that assumed the hook had
 * run would address resources that do not exist - and for a queue defined errors-ignored that is not an
 * error but a silent short delivery. This class therefore creates all three itself, idempotently, in
 * the same static initialisation that starts the emulator, so that
 * {@link #jobSubmissionQueueUrl()} and {@link #jobNotificationTopicArn()} are usable by the time any
 * subclass runs. Re-creating a resource that already exists is a normal outcome and is tolerated per
 * resource, which is what lets a subclass provision by name without checking first.
 *
 * <table>
 *   <caption>The three resources, and the settings key each is published on</caption>
 *   <tr><th>Resource</th><th>Name</th><th>Settings key</th></tr>
 *   <tr><td>object-store bucket, object versioning enabled</td><td>{@value #BATCH_STAGING_BUCKET}</td>
 *       <td>{@code carddemo.aws.s3.batch-staging-bucket}</td></tr>
 *   <tr><td>first-in-first-out submission queue</td><td>{@value #JOB_SUBMISSION_QUEUE}</td>
 *       <td>{@code carddemo.aws.sqs.job-queue}</td></tr>
 *   <tr><td>notification topic</td><td>{@value #JOB_NOTIFICATION_TOPIC}</td>
 *       <td>{@code carddemo.aws.sns.job-notification-topic}</td></tr>
 * </table>
 *
 * <h2>Why object versioning on the bucket is mandatory rather than decorative</h2>
 * Versioning is what carries the retained-generation semantics of the legacy output data sets, whose
 * provisioning stream declared six generation bases with a retention limit of five while a second
 * member re-declared one of the same six - the transaction-report base - with a limit of ten. The
 * conflict is resolved in favour of ten as the later and more specific declaration, recorded at
 * {@code docs/decision-log.md} D-43, and versioning is that retention's replacement, recorded at
 * DL-043. What matters here is the consequence: an unversioned bucket would accept every staged object
 * and silently retain only the newest, so the retention contract would be gone with nothing failing.
 * {@link #createBucketIfAbsent(String)} therefore applies versioning on the single creation path rather
 * than at each call site, and {@link #bucketVersioningStatus(String)} exists so a test can assert the
 * state rather than assume it.
 *
 * <h2>The queue contract this emulator makes testable</h2>
 * The estate's entire online-to-batch bridge is one transient-data-queue write, in {@code CORPT00C},
 * against the queue the CICS resource definition names {@code JOBS}. Five of that definition's
 * attributes are behavioural rather than cosmetic, and each maps onto something a test can observe
 * here. The attribute names below are cited as metadata; no resource-definition text is reproduced.
 *
 * <ul>
 *   <li><strong>{@code RECORDSIZE(80)}</strong> - every message body is exactly
 *       {@value #RECORD_SIZE} characters, space-padded where the card is short.
 *       {@link #publishFixedWidthRecord(String, String, String)} refuses any other width.</li>
 *   <li><strong>{@code RECORDFORMAT(FIXED)} with {@code BLOCKFORMAT(UNBLOCKED)}</strong> - one message
 *       per card, never an aggregate. Seventeen cards are seventeen messages, which is why the publish
 *       helper sends singly and why nothing here batches.</li>
 *   <li><strong>{@code DISPOSITION(MOD)}</strong> - append ordering, which is a first-in-first-out
 *       queue and one stable message group. {@link #drainQueue(String, int)} returns delivery order, so
 *       an assertion compares sequences rather than sets.</li>
 *   <li><strong>{@code ERROROPTION(IGNORE)}</strong> - a failed publish is logged and the caller
 *       continues, recorded at {@code docs/decision-log.md} DL-044. A test of the failure path asserts
 *       <em>continuation</em>; asserting that an exception escapes the caller would assert the opposite
 *       of the legacy behaviour.</li>
 *   <li><strong>{@code TYPEFILE(OUTPUT)} with {@code OPENTIME(INITIAL)}</strong> - publish-only, and
 *       the queue exists before first use. The application never receives; only a test drains. That is
 *       why the queue is provisioned in this initialiser rather than lazily by its first publisher.</li>
 * </ul>
 *
 * <p>Deduplication deserves its own note because it fails silently. A first-in-first-out queue with
 * content-based deduplication switched on collapses two messages carrying identical bytes into one
 * inside its deduplication window, and several of the submission cards are fixed literals that repeat.
 * The queue is therefore created with content-based deduplication switched <em>off</em> and each
 * message carries its own deduplication identifier - the resource-side half of
 * {@code docs/decision-log.md} DL-043 - so every card survives. The count is what proves it, which is
 * why {@link #drainQueue(String, int)} reports what it actually received.</p>
 *
 * <h2>Why an emulator rather than a stand-in</h2>
 * The queue is an external interface contract, and the acceptance criterion for that contract is
 * that the ordered sequence of fixed-width records is read back <em>out of a queue</em>. Asserting
 * against the return value of a builder, or against a mock's recorded arguments, proves that the
 * caller intended to publish something; it does not prove the service accepted it, preserved its
 * order, or preserved its bytes. Only a real queue proves that, which is why this base exists and
 * why the interface test that uses it declares no stand-in for the successful path.
 *
 * <h2>THE SUBCLASS CONTRACT - what a subclass MUST NOT declare</h2>
 * This type is the single owner of the AWS emulator for the whole module - it and
 * {@link AbstractPostgresIT} are the only two container owners in the test tree - so a subclass
 * <strong>must not</strong> declare any of the following. Each either competes with what is centralised
 * here or silently breaks sharing for every other subclass:
 *
 * <ul>
 *   <li>{@code @Testcontainers} - the extension's per-class {@code afterAll} would stop the shared
 *       emulator after the first subclass finished, leaving every later subclass on a dead endpoint.</li>
 *   <li>a {@code @Container} field - same per-class lifecycle, same consequence.</li>
 *   <li>a {@code LocalStackContainer} of its own - a second emulator per class, which multiplies
 *       start-up cost and lets one test observe or purge another's messages.</li>
 *   <li>its own {@code @DynamicPropertySource} for the AWS settings - the keys published by
 *       {@link #registerAwsProperties(DynamicPropertyRegistry)} are the contract, and a competing source
 *       makes which endpoint wins depend on declaration order.</li>
 *   <li>{@code @DirtiesContext} - it discards the Spring context and defeats the reuse that keeps the
 *       integration phase cheap. Use {@link #resetJobSubmissionQueue()} or
 *       {@link #purgeQueue(String)} instead; supplying a cheaper deterministic alternative is precisely
 *       why those exist.</li>
 * </ul>
 *
 * <p>A subclass declares only what is specific to itself: its own {@code @SpringBootTest} - so it may
 * choose its own web environment, which is why this base deliberately carries none - its own fixtures,
 * and its own assertions. The {@code test} profile is inherited from this class and needs no
 * restatement.</p>
 *
 * <h2>How a subclass reaches BOTH the emulator and the database</h2>
 * Java admits one superclass and both container owners are classes, so the two cannot be reached by
 * inheritance at once. Composition resolves it: {@link AbstractPostgresAndLocalStackIT} extends
 * {@link AbstractPostgresIT} - inheriting its server, its published data-source properties and its reset
 * contract unchanged - and delegates to this class's static members for the emulator. A test that needs
 * both extends that type; a test that needs only the emulator extends this one.
 *
 * <p>Delegating to {@link #registerAwsProperties(DynamicPropertyRegistry)} is also what <em>starts</em>
 * the emulator, because the container lives in a static field and touching any static member of this
 * class initialises it. So one registration call starts the emulator, provisions the three resources and
 * publishes the address, and none of the three can happen without the others. Both containers remain
 * single per-JVM instances shared by every subclass of either base, so the start-up cost of each is paid
 * once for the whole integration phase however many classes take part.</p>
 *
 * <h2>Why the emulator is started in a static initialiser</h2>
 * For the same lifecycle reason recorded on {@link AbstractPostgresIT}: the JUnit integration behind
 * {@code @Container} runs its teardown once per test <em>class</em>, so a static container inherited
 * by several classes is stopped after the first of them. Starting once here, outside that lifecycle,
 * lets every subclass share one emulator, and the Testcontainers resource reaper removes it when the
 * JVM exits. This class carries neither {@code @Testcontainers} nor {@code @Container} so no
 * subclass can reintroduce per-class teardown by inheritance.
 *
 * <p>The four service clients are held as static members for the same reason and share that lifetime:
 * they are bound to the one emulator, they are stateless and thread-safe, and they are released when the
 * JVM that owns the emulator exits. A per-test client would open a connection pool per test class for no
 * gain, and closing a shared client in a per-class callback would break every later subclass exactly as
 * a per-class container teardown would.</p>
 *
 * <h2>Why the image tag is pinned, and pinned to this tag specifically</h2>
 * Tags on the 2026 line perform licence activation at start-up and exit rather than serve traffic
 * without an authorisation token, so a floating tag would make every queue test fail for a reason
 * unrelated to the code under test. The pinned tag is a Community tag that starts token-free, and it
 * is the same tag {@code carddemo-java/docker-compose.yml} pins, so a local run and a
 * continuous-integration run exercise the same emulator.
 *
 * <h2>Why this emulator is not the one the local stack already runs</h2>
 * The container started here listens on an ephemeral port of its own and is reaped with the test
 * JVM. It deliberately does not reuse a long-running emulator on a fixed port, because a shared
 * emulator would let one test observe another's messages and would let a test purge a queue another
 * process depends on.
 *
 * <h2>How a subclass that boots a Spring context reaches the same emulator</h2>
 * {@link #registerAwsProperties(DynamicPropertyRegistry)} publishes this emulator's endpoint, region
 * and throwaway credentials as {@code spring.cloud.aws.*} properties, so a framework-created client
 * inside such a context addresses the container this class started rather than anything a file
 * declared.
 *
 * <p>An AWS endpoint is not like a database address, and the difference is why this registration
 * matters more than it looks. An absent datasource URL fails closed. An absent AWS endpoint does
 * <em>not</em>: the SDK falls back to the region's real public endpoint, so a client with no endpoint
 * set addresses a real account on whatever credentials it happens to find, and does so silently.
 * Both copies of {@code application-test.yml} therefore also declare the emulator endpoint
 * explicitly, as a floor - this method raises the value from that fixed floor to the ephemeral
 * address of the container actually running, and neither path can reach a real account.</p>
 *
 * <p>The queue tests in this module build their own client against {@link #sqsAsyncClient()} rather
 * than resolving one from a context, because a directly-built client is the cheapest way to assert a
 * transport contract. This registration is what makes a context-booting test correct by construction
 * instead of by remembering to wire it, and it is verified from both ends:
 * {@code LocalStackPropertyRegistrationIT} calls it with a recording registry and asserts that each key
 * resolves to the running emulator's own value, and {@code ContextInheritsContainerAddressesIT} boots a
 * real context and asserts that the endpoint it observes is this emulator's ephemeral one rather than
 * the fixed floor the profile documents declare - which is what demonstrates that a dynamic property
 * source really does outrank a property file. See {@code docs/decision-log.md} DL-104.</p>
 *
 * <h2>Every call across this boundary is bounded, and in two independent places</h2>
 * A test that reaches a transport can stall on it, and a stall is not a failure: nothing reports it and
 * nothing ends it. The asynchronous operations below are consumed with {@code join}, which does not
 * respond to interruption, and the {@value #DRAIN_DEADLINE_SECONDS}-second drain deadline is evaluated
 * <em>between</em> calls, so it cannot end a single call that never returns. Left unbounded, one stalled
 * socket holds the whole integration phase until the continuous-integration job's own ceiling fires,
 * hours later, against a run whose diagnostic is by then long gone.
 *
 * <p>Two bounds close that, and they are deliberately at different levels because neither alone is
 * sufficient:</p>
 *
 * <ul>
 *   <li><strong>A finite call budget on every client.</strong> Each of the four clients carries
 *       {@link #API_CALL_TIMEOUT} for a whole call and {@link #API_CALL_ATTEMPT_TIMEOUT} for one attempt
 *       of it, so a stalled call completes its future exceptionally rather than never - which is what
 *       makes a {@code join} finite. Both are set strictly below the drain deadline, so no single call
 *       can outlive the loop deadline it is measured against.</li>
 *   <li><strong>A finite budget on every test method.</strong> {@code @Timeout} on this class bounds
 *       every test method of every subclass, and of every nested group within one, at
 *       {@value #EXTERNAL_BOUNDARY_TIMEOUT_SECONDS} seconds. It is the backstop for a stall that is not
 *       an SDK call at all, and a method that needs longer overrides it locally.</li>
 * </ul>
 *
 * <p><strong>These budgets are the test harness's own and are not a statement about the migrated
 * application.</strong> The queue definition this module reproduces is defined errors-ignored, and the
 * shipped publisher carries no call budget of any kind by design - {@code com.carddemo.config.AwsConfig}
 * sets a single attempt and nothing else, which {@code AwsConfigTest} asserts by requiring both budgets
 * to be absent there. Bounding the harness therefore changes no production posture: it bounds the
 * clients this class builds for its own assertions, and leaves the client under test exactly as it
 * ships.</p>
 *
 * <p>What the method bound does <em>not</em> cover is worth stating, because the omission is
 * deliberate. A class-level {@code @Timeout} applies to testable methods, not to lifecycle methods, and
 * the emulator starts in this class's static initialiser rather than in either - so container start-up
 * is governed by Testcontainers' own wait strategy and is not competing with a per-test budget that
 * would have to be inflated to accommodate it.</p>
 *
 * <p>Provenance: this support type has no legacy antecedent - the legacy estate carries no test
 * harness of any kind. It exists to serve tests of the queue that replaces
 * {@code TDQUEUE(JOBS)} as defined in {@code app/csd/CARDDEMO.CSD}, taken from checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. That stamp is a provenance string for the
 * traceability matrix header only: it is not carried by every legacy member, so nothing here asserts it
 * against one.</p>
 */
@ActiveProfiles("test")
@Timeout(value = AbstractLocalStackIT.EXTERNAL_BOUNDARY_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
public abstract class AbstractLocalStackIT {

    /**
     * The pinned emulator image. Held as a constant so a subclass can assert against it rather than
     * restating the tag.
     */
    protected static final String LOCALSTACK_IMAGE = "localstack/localstack:4.14.0";

    /** Suffix the queue service requires on the name of a first-in-first-out queue. */
    protected static final String FIFO_SUFFIX = ".fifo";

    /**
     * The region every client resolves its endpoints in, and the region the emulator reports.
     *
     * <p>Held as a literal as well as read back from {@link #emulatorRegion()} so the two can be
     * compared: the value below is what every profile document declares, and a divergence between the
     * document and the running emulator is the one thing an assertion on either alone cannot catch.</p>
     */
    protected static final String REGION = "us-east-1";

    /**
     * Name of the object-store bucket batch input and output are staged in.
     *
     * <p>The name is mandated rather than chosen: the same string is bound in every profile document, in
     * {@code carddemo-java/docker-compose.yml} and in the development stack's provisioning hook, and
     * {@code com.carddemo.config.AwsProperties} binds it from
     * {@code carddemo.aws.s3.batch-staging-bucket}.</p>
     */
    protected static final String BATCH_STAGING_BUCKET = "carddemo-batch-staging";

    /**
     * Name of the first-in-first-out queue that replaces the estate's single online-to-batch bridge.
     *
     * <p><strong>The stem is the name the CICS resource definition gives the transient data queue -
     * {@code JOBS} - and the suffix is mandatory rather than stylistic:</strong> the queue service
     * refuses to create a first-in-first-out queue whose name omits it, and a standard queue would
     * silently reorder the cards of a submission. It is composed from {@link #FIFO_SUFFIX} rather than
     * written out, so the suffix cannot be dropped from this constant without dropping it from the
     * refusal in {@link #createFifoQueue(String)} at the same time.</p>
     *
     * <p>Keeping the legacy stem rather than a module-namespaced alternative is the decision recorded at
     * {@code docs/decision-log.md} DL-092, which withdrew {@code carddemo-jobs.fifo} on the finding that
     * the queue is the <em>one</em> resource the migration plan names - it prescribes the queue
     * {@code JOBS} wherever the resource appears, while asking only for "an S3 staging bucket", for
     * message-group ordering and for "an SNS topic" without naming those three, which is why the other
     * three constants here are this module's own choices and stand unchanged. The reason it matters
     * operationally is fail-fast: this one string is bound byte-identically in both profile overlays, in
     * the shared baseline, in the production overlay, in {@code carddemo-java/docker-compose.yml}, in the
     * development stack's provisioning hook and in {@code carddemo-java/README.md}, and a queue write
     * defined errors-ignored gives a disagreement no failure signal at all - the stack would start
     * cleanly and come up short at the first submission with nothing naming the cause. Restating a
     * different name here would create exactly that silence for every context-booting subclass.</p>
     */
    protected static final String JOB_SUBMISSION_QUEUE = "JOBS" + FIFO_SUFFIX;

    /**
     * The one message group every submission card is published under.
     *
     * <p>A first-in-first-out queue orders within a group, so one stable group id is what turns the
     * queue's ordering guarantee into a strict total order over the cards of a submission - which is the
     * append disposition of the legacy queue definition. It provisions nothing: the publisher puts it on
     * each message.</p>
     */
    protected static final String MESSAGE_GROUP_ID = "carddemo-job-submission";

    /** Name of the topic terminal job events are announced on. */
    protected static final String JOB_NOTIFICATION_TOPIC = "carddemo-job-notifications";

    /**
     * The fixed width of one queue record, in characters.
     *
     * <p>Declared here rather than read from the production publisher on purpose. This class serves the
     * tests that verify that publisher, so taking the width from it would let a test agree with a wrong
     * value; an independent statement of the contract is what makes disagreement visible. The value is
     * the record size the CICS resource definition fixes for the queue.</p>
     */
    protected static final int RECORD_SIZE = 80;

    /**
     * Seconds any one test method of this tier may take before it is failed as stalled.
     *
     * <p>Applied by the {@code @Timeout} on this class, so it covers every test method of every subclass
     * and of every nested group within one. The figure is several times the slowest method measured in
     * this tier - the whole-pipeline end-to-end case, at eighteen seconds on a host running dozens of
     * builds at once - so what it fails is a stall rather than a slow machine, and a method with a
     * genuinely longer budget, such as the refused-transport cases that wait on a socket which never
     * answers, states its own and overrides this one.</p>
     *
     * <p>Declared as a constant rather than written into the annotation because the annotation is on this
     * class and {@link AbstractPostgresAndLocalStackIT} carries the same bound: one figure, referenced
     * twice, cannot drift between the two entry points into this emulator.</p>
     */
    protected static final long EXTERNAL_BOUNDARY_TIMEOUT_SECONDS = 120L;

    /** Seconds a drain will keep asking before it gives up, as a figure the class documentation cites. */
    private static final long DRAIN_DEADLINE_SECONDS = 30L;

    /**
     * Whole-call budget for a client this class builds, retries included.
     *
     * <p>Below {@link #DRAIN_DEADLINE_SECONDS} on purpose: a drain checks its deadline between calls, so a
     * call permitted to run longer than the deadline would let a loop that has already expired sit in a
     * single request. Being below it means every iteration observes the deadline it is measured against.
     * Wide enough for two full attempts, so a single slow response is retried rather than failed.</p>
     */
    private static final Duration API_CALL_TIMEOUT = Duration.ofSeconds(25);

    /**
     * Budget for one attempt of a call a client this class builds makes.
     *
     * <p>Every operation this class performs is one small request against an emulator on this same host -
     * the largest body it ever sends is a single {@value #RECORD_SIZE}-character record - so ten seconds
     * is a very wide margin, and wide enough to absorb the {@value #RECEIVE_WAIT_SECONDS}-second poll a
     * receive call asks the service to hold its connection for.</p>
     */
    private static final Duration API_CALL_ATTEMPT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * The wildcard that asks the receive operation for every user message attribute.
     *
     * <p>A literal the service defines, and the only value that returns attributes a test did not name
     * in advance - which is what an assertion about the <em>set</em> of published attributes needs.
     */
    private static final String ALL_MESSAGE_ATTRIBUTES = "All";

    /** Largest batch the receive operation will return in one call. */
    private static final int RECEIVE_BATCH_SIZE = 10;

    /** Seconds a receive call waits for a message before returning empty. */
    private static final int RECEIVE_WAIT_SECONDS = 1;

    /**
     * Upper bound on how long a drain will keep asking before giving up.
     *
     * <p>Composed from {@link #DRAIN_DEADLINE_SECONDS} rather than restating the figure, because the two
     * call budgets above are set relative to this deadline and the relation has to survive a later edit
     * to either.</p>
     */
    private static final Duration DRAIN_DEADLINE = Duration.ofSeconds(DRAIN_DEADLINE_SECONDS);

    /** The one emulator every subclass shares. */
    private static final LocalStackContainer LOCALSTACK = startEmulator();

    /** The one client every subclass shares, bound to the emulator's ephemeral endpoint. */
    private static final SqsAsyncClient SQS_ASYNC_CLIENT = buildSqsAsyncClient();

    /** Synchronous queue client used by cross-service notification tests. */
    private static final SqsClient SQS_CLIENT = buildSqsClient();

    /** Object-store client used by durable batch-artifact integration tests. */
    private static final S3Client S3_CLIENT = buildS3Client();

    /** Notification client used by terminal job-event integration tests. */
    private static final SnsClient SNS_CLIENT = buildSnsClient();

    /**
     * The staging bucket, created with object versioning enabled before any subclass runs.
     *
     * <p>Held as a field rather than created in a bare static block so that the three provisioning steps
     * read as three declarations in the order they must happen, each after the clients they use. The
     * value is the bucket's own name, so a subclass can take it from here or from
     * {@link #BATCH_STAGING_BUCKET} and be certain the two agree.</p>
     */
    private static final String STAGING_BUCKET = provisionStagingBucket();

    /** The submission queue's URL, provisioned before any subclass runs. */
    private static final String JOB_SUBMISSION_QUEUE_URL = createFifoQueue(JOB_SUBMISSION_QUEUE);

    /** The notification topic's ARN, provisioned before any subclass runs. */
    private static final String JOB_NOTIFICATION_TOPIC_ARN =
            createTopicIfAbsent(JOB_NOTIFICATION_TOPIC);

    /**
     * Restricts construction to subclasses. A test class extends this type; nothing instantiates it
     * directly.
     */
    protected AbstractLocalStackIT() {
        // Intentionally empty: this base holds no per-instance state.
    }

    /**
     * Starts the emulator with the queue and notification services enabled.
     *
     * @return the started emulator
     */
    private static LocalStackContainer startEmulator() {
        final LocalStackContainer container =
                new LocalStackContainer(DockerImageName.parse(LOCALSTACK_IMAGE))
                        .withServices(LocalStackContainer.Service.S3,
                                LocalStackContainer.Service.SQS,
                                LocalStackContainer.Service.SNS);
        container.start();
        return container;
    }

    /**
     * The call budgets every client this class builds carries.
     *
     * <p>Built per client rather than held as a shared instance only because the configuration object is
     * cheap and building it beside each client keeps the four builders reading identically. What matters
     * is that all four go through this one method, so no client can be added later that reaches the
     * emulator without a budget: an unbudgeted call is the failure this exists to prevent, and it is
     * invisible when it happens.</p>
     *
     * <p>This is the harness's own posture and says nothing about the shipped publisher, which carries no
     * budget deliberately. See the class documentation.</p>
     *
     * @return the override configuration to apply to a client this class builds
     */
    private static ClientOverrideConfiguration boundedCallConfiguration() {
        return ClientOverrideConfiguration.builder()
                .apiCallTimeout(API_CALL_TIMEOUT)
                .apiCallAttemptTimeout(API_CALL_ATTEMPT_TIMEOUT)
                .build();
    }

    /**
     * Builds the client the tests publish and receive through.
     *
     * @return a client bound to the running emulator
     */
    private static SqsAsyncClient buildSqsAsyncClient() {
        return SqsAsyncClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .overrideConfiguration(boundedCallConfiguration())
                .build();
    }

    /** Builds the synchronous queue client used where a second service delivers into a queue. */
    private static SqsClient buildSqsClient() {
        return SqsClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .overrideConfiguration(boundedCallConfiguration())
                .build();
    }

    /** Builds the object-store client used to verify uploaded bytes and retention. */
    private static S3Client buildS3Client() {
        return S3Client.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .forcePathStyle(true)
                .overrideConfiguration(boundedCallConfiguration())
                .build();
    }

    /** Builds the notification client used to verify terminal job events. */
    private static SnsClient buildSnsClient() {
        return SnsClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .overrideConfiguration(boundedCallConfiguration())
                .build();
    }

    /**
     * Creates the staging bucket with object versioning enabled, and proves the versioning took.
     *
     * <p>The read-back is not ceremony. Versioning is the retained-generation replacement, and applying
     * it is the one provisioning step whose failure is invisible afterwards: an unversioned bucket
     * accepts every staged object and quietly keeps only the newest. Failing here, while the cause is
     * still known, is far better than failing later in a retention assertion that names the wrong
     * culprit.</p>
     *
     * @return the bucket's name
     */
    private static String provisionStagingBucket() {
        createBucketIfAbsent(BATCH_STAGING_BUCKET);
        final BucketVersioningStatus status = bucketVersioningStatus(BATCH_STAGING_BUCKET);
        if (status != BucketVersioningStatus.ENABLED) {
            throw new IllegalStateException("the staging bucket " + BATCH_STAGING_BUCKET
                    + " reports object versioning " + status + " but it must be "
                    + BucketVersioningStatus.ENABLED
                    + ". Versioning is what carries the retained-generation semantics of the legacy"
                    + " output data sets, so an unversioned bucket would retain only the newest staged"
                    + " object and lose the retention contract without failing.");
        }
        return BATCH_STAGING_BUCKET;
    }

    /**
     * Publishes the running emulator's endpoint, region and credentials into the environment of any
     * subclass that boots a Spring context.
     *
     * <p>The global endpoint is registered and then restated for each of the three services. That
     * redundancy is the point: it means no client can fall through to a real AWS endpoint if the
     * global setting is ever dropped, or if one client's auto-configuration stops consulting it. The
     * failure being guarded against is silent success against the wrong target, not an error.</p>
     *
     * <p>The credentials published here are the emulator's own throwaway pair. They authenticate
     * nothing: the emulator accepts any non-empty pair and verifies neither. They are registered so
     * that a context never reaches the default credentials chain, which on a developer's machine or a
     * build agent could find real ones.</p>
     *
     * <p><strong>The module's own endpoint key is raised alongside the integration's four.</strong>
     * {@code carddemo.aws.endpoint-override} is the key {@code com.carddemo.config.AwsProperties} binds
     * and the form a consumer of that type reads, and both copies of the test profile floor it at the
     * emulator's fixed port. Raising only the {@code spring.cloud.aws} settings would leave a context
     * in which the clients address the container this JVM started while the settings type still reports
     * the floor - two answers to one question, and the wrong one belonging to whichever component asked
     * the type rather than the client.</p>
     *
     * <p><strong>All six keys this module's own settings type binds are published, not only the
     * endpoint.</strong> {@code com.carddemo.config.AwsProperties} binds a region, an endpoint override
     * and the four resource names, and a component asks that type rather than the client for a name. The
     * four names are published because this class has just <em>provisioned</em> those resources in this
     * emulator: publishing them is what makes the guarantee "the resource your settings name exists and
     * is reachable" true by construction. Registering the endpoint alone would leave a context whose
     * clients address the emulator this JVM started while the names came from a document, and the
     * mismatch would surface as an absent queue rather than as a configuration error.</p>
     *
     * <p>Each of the four is registered at the value the profile documents already declare, so the lift
     * confirms the floor rather than contradicting it. That is deliberate: a base class that quietly
     * substituted different names would make the suite pass against a configuration the module does not
     * ship.</p>
     *
     * @param registry the registry the Spring TestContext Framework supplies; must not be null
     */
    @DynamicPropertySource
    protected static void registerAwsProperties(final DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.aws.region.static", AbstractLocalStackIT::emulatorRegion);
        registry.add("spring.cloud.aws.credentials.access-key", AbstractLocalStackIT::emulatorAccessKey);
        registry.add("spring.cloud.aws.credentials.secret-key", AbstractLocalStackIT::emulatorSecretKey);
        registry.add("spring.cloud.aws.endpoint", AbstractLocalStackIT::emulatorEndpoint);
        registry.add("spring.cloud.aws.s3.endpoint", AbstractLocalStackIT::emulatorEndpoint);
        registry.add("spring.cloud.aws.sqs.endpoint", AbstractLocalStackIT::emulatorEndpoint);
        registry.add("spring.cloud.aws.sns.endpoint", AbstractLocalStackIT::emulatorEndpoint);
        registry.add("carddemo.aws.region", AbstractLocalStackIT::emulatorRegion);
        registry.add("carddemo.aws.endpoint-override", AbstractLocalStackIT::emulatorEndpoint);
        registry.add("carddemo.aws.s3.batch-staging-bucket", AbstractLocalStackIT::stagingBucket);
        registry.add("carddemo.aws.sqs.job-queue", () -> JOB_SUBMISSION_QUEUE);
        registry.add("carddemo.aws.sqs.message-group-id", () -> MESSAGE_GROUP_ID);
        registry.add("carddemo.aws.sns.job-notification-topic", () -> JOB_NOTIFICATION_TOPIC);
    }

    /**
     * Returns the shared client bound to the running emulator.
     *
     * @return the queue client
     */
    protected static SqsAsyncClient sqsAsyncClient() {
        return SQS_ASYNC_CLIENT;
    }

    /** @return the shared synchronous queue client */
    protected static SqsClient sqsClient() {
        return SQS_CLIENT;
    }

    /** @return the shared object-store client */
    protected static S3Client s3Client() {
        return S3_CLIENT;
    }

    /** @return the shared notification client */
    protected static SnsClient snsClient() {
        return SNS_CLIENT;
    }

    /**
     * Returns the staging bucket this class provisioned, with object versioning already enabled.
     *
     * @return the bucket's name
     */
    protected static String stagingBucket() {
        return STAGING_BUCKET;
    }

    /**
     * Returns the URL of the submission queue this class provisioned.
     *
     * <p>A subclass takes the URL from here rather than creating a queue of its own. The queue already
     * exists - which is what the legacy definition's open-at-initialisation attribute amounts to - so a
     * publisher never has to create it and a test never has to guess whether it was created.</p>
     *
     * @return the queue's URL
     */
    protected static String jobSubmissionQueueUrl() {
        return JOB_SUBMISSION_QUEUE_URL;
    }

    /**
     * Returns the ARN of the notification topic this class provisioned.
     *
     * @return the topic's ARN
     */
    protected static String jobNotificationTopicArn() {
        return JOB_NOTIFICATION_TOPIC_ARN;
    }

    /**
     * Returns the emulator's endpoint, including its ephemeral port.
     *
     * @return the endpoint as text
     */
    protected static String emulatorEndpoint() {
        return LOCALSTACK.getEndpoint().toString();
    }

    /**
     * Returns the region the emulator reports.
     *
     * @return the region identifier
     */
    protected static String emulatorRegion() {
        return LOCALSTACK.getRegion();
    }

    /**
     * Returns the emulator's throwaway access key.
     *
     * <p>This is not a credential of anything: the emulator accepts any non-empty pair and verifies
     * neither value, and the pair never leaves the test JVM.</p>
     *
     * @return the access key the emulator reports
     */
    protected static String emulatorAccessKey() {
        return LOCALSTACK.getAccessKey();
    }

    /**
     * Returns the emulator's throwaway secret key.
     *
     * <p>See {@link #emulatorAccessKey()}: this authenticates nothing and never leaves the test
     * JVM.</p>
     *
     * @return the secret key the emulator reports
     */
    protected static String emulatorSecretKey() {
        return LOCALSTACK.getSecretKey();
    }

    /**
     * Creates a first-in-first-out queue, or returns the existing one if the name is already taken.
     *
     * <p>Content-based deduplication is switched off deliberately: the publisher supplies an explicit
     * deduplication identifier per message, and enabling the content-based alternative would let two
     * cards that happen to carry identical bytes collapse into one message.</p>
     *
     * @param queueName the queue name, which must end in the first-in-first-out suffix
     * @return the queue's URL
     */
    protected static String createFifoQueue(final String queueName) {
        if (!queueName.endsWith(FIFO_SUFFIX)) {
            throw new IllegalArgumentException("the queue name " + queueName + " does not end in "
                    + FIFO_SUFFIX + ". The queue service will not create a first-in-first-out queue"
                    + " without that suffix, and a standard queue would silently reorder the cards of a"
                    + " submission.");
        }
        final Map<QueueAttributeName, String> attributes = new LinkedHashMap<>();
        attributes.put(QueueAttributeName.FIFO_QUEUE, "true");
        attributes.put(QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "false");
        return SQS_ASYNC_CLIENT.createQueue(CreateQueueRequest.builder()
                        .queueName(queueName)
                        .attributes(attributes)
                        .build())
                .join()
                .queueUrl();
    }

    /**
     * Creates a notification topic, or returns the existing one if the name is already taken.
     *
     * <p>The create operation is idempotent for a topic: asking twice for the same name returns the same
     * ARN rather than failing, so no existence check is needed and a second provisioning run is a
     * no-op.</p>
     *
     * @param  topicName the topic name; must not be null
     * @return the topic's ARN
     */
    protected static String createTopicIfAbsent(final String topicName) {
        return SNS_CLIENT.createTopic(CreateTopicRequest.builder().name(topicName).build()).topicArn();
    }

    /**
     * Reads back the attributes a queue reports, so a test can assert its shape rather than assume it.
     *
     * <p>The two that matter are the first-in-first-out flag and the content-based deduplication flag:
     * the first is what preserves the append ordering of the legacy queue, and the second must be off or
     * two cards carrying identical bytes collapse into one message.</p>
     *
     * @param  queueUrl the queue to inspect; must not be null
     * @return the attributes the service reports, keyed as the service names them
     */
    protected static Map<QueueAttributeName, String> queueAttributes(final String queueUrl) {
        return Map.copyOf(SQS_ASYNC_CLIENT.getQueueAttributes(GetQueueAttributesRequest.builder()
                        .queueUrl(queueUrl)
                        .attributeNames(QueueAttributeName.ALL)
                        .build())
                .join()
                .attributes());
    }

    /**
     * Reports whether a queue is a first-in-first-out queue, as the service itself sees it.
     *
     * @param  queueUrl the queue to inspect; must not be null
     * @return {@code true} when the service reports the first-in-first-out attribute as set
     */
    protected static boolean isFifoQueue(final String queueUrl) {
        return Boolean.parseBoolean(queueAttributes(queueUrl).get(QueueAttributeName.FIFO_QUEUE));
    }

    /**
     * Publishes one fixed-width record as one message, under the shared message group.
     *
     * <p>Three properties of the legacy queue definition are enforced here rather than left to a caller.
     * The body must be exactly {@value #RECORD_SIZE} characters, which is the record size the definition
     * fixes; one call publishes exactly one message, because the definition's fixed unblocked record
     * format means a card is a record rather than part of an aggregate; and the deduplication identifier
     * is supplied explicitly, so two cards carrying identical bytes both survive - which they would not
     * if the queue deduplicated on content.</p>
     *
     * <p>The width is stated in characters, and the body must also be <em>representable</em> in US-ASCII,
     * which is the encoding a fixed-width card image is defined in. Representability is tested with an
     * encoder rather than by comparing the encoded byte count against the character count, and the
     * distinction matters: {@code String.getBytes(US_ASCII)} does not fail on a character outside the
     * range and does not widen the result either - it substitutes a single replacement byte - so a
     * byte-count comparison would report eighty bytes for a body that had silently lost a character. An
     * encoder reports the character as unmappable, which is the question actually being asked.</p>
     *
     * <p>A fresh encoder is obtained per call because an encoder carries state and is not safe to share
     * between threads, and this method is reachable from a parallel test execution.</p>
     *
     * @param  queueUrl        the queue to publish to; must not be null
     * @param  body            the record, which must be exactly {@value #RECORD_SIZE} characters
     * @param  deduplicationId an identifier unique to this record within the deduplication window
     * @return the identifier the service assigned the message
     */
    protected static String publishFixedWidthRecord(final String queueUrl, final String body,
            final String deduplicationId) {
        if (body.length() != RECORD_SIZE) {
            throw new IllegalArgumentException("a queue record must be exactly " + RECORD_SIZE
                    + " characters but this one was " + body.length()
                    + ". The record size is fixed by the queue definition the migrated bridge"
                    + " reproduces, so a shorter card is space-padded and a longer one is a different"
                    + " record format.");
        }
        if (!StandardCharsets.US_ASCII.newEncoder().canEncode(body)) {
            throw new IllegalArgumentException("a queue record must be representable in US-ASCII, and"
                    + " this one carried a character that is not. A fixed-width card image is defined in"
                    + " that encoding, so an unmappable character would be replaced rather than"
                    + " transmitted and the record would reach the queue having silently lost it.");
        }
        return SQS_ASYNC_CLIENT.sendMessage(SendMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .messageBody(body)
                        .messageGroupId(MESSAGE_GROUP_ID)
                        .messageDeduplicationId(deduplicationId)
                        .build())
                .join()
                .messageId();
    }

    /**
     * Reads messages back in delivery order, deleting each as it is read so that the next messages of
     * the same group become visible.
     *
     * <p>Reading stops as soon as the expected count is reached, or as soon as the service reports
     * nothing further, or when the deadline expires - whichever happens first. Stopping on an empty
     * response rather than only on the expected count is what lets a caller assert that <em>fewer</em>
     * messages than requested were published.</p>
     *
     * <p>Both every system attribute and every user message attribute are requested, because the
     * service does not return either kind unless it is asked for them by name. Without the second
     * request a test asserting on a published message attribute would observe an empty map and could
     * not tell an attribute that was never set from one that was simply not fetched.</p>
     *
     * @param queueUrl              the queue to read
     * @param expectedMessageCount  how many messages to stop after; must not be negative
     * @return the messages read, in the order the service delivered them
     */
    protected static List<Message> drainQueue(final String queueUrl, final int expectedMessageCount) {
        if (expectedMessageCount < 0) {
            throw new IllegalArgumentException(
                    "expectedMessageCount must not be negative but was " + expectedMessageCount);
        }
        final List<Message> drained = new ArrayList<>(expectedMessageCount);
        final Instant deadline = Instant.now().plus(DRAIN_DEADLINE);
        while (drained.size() < expectedMessageCount && Instant.now().isBefore(deadline)) {
            final ReceiveMessageResponse response = SQS_ASYNC_CLIENT.receiveMessage(
                            ReceiveMessageRequest.builder()
                                    .queueUrl(queueUrl)
                                    .maxNumberOfMessages(RECEIVE_BATCH_SIZE)
                                    .waitTimeSeconds(RECEIVE_WAIT_SECONDS)
                                    .messageSystemAttributeNames(MessageSystemAttributeName.ALL)
                                    .messageAttributeNames(ALL_MESSAGE_ATTRIBUTES)
                                    .build())
                    .join();
            if (!response.hasMessages() || response.messages().isEmpty()) {
                break;
            }
            for (final Message message : response.messages()) {
                drained.add(message);
                SQS_ASYNC_CLIENT.deleteMessage(DeleteMessageRequest.builder()
                                .queueUrl(queueUrl)
                                .receiptHandle(message.receiptHandle())
                                .build())
                        .join();
            }
        }
        return List.copyOf(drained);
    }

    /**
     * Removes every message from a queue so the next test starts from an empty one.
     *
     * @param queueUrl the queue to empty
     */
    protected static void purgeQueue(final String queueUrl) {
        SQS_ASYNC_CLIENT.purgeQueue(PurgeQueueRequest.builder().queueUrl(queueUrl).build()).join();
    }

    /**
     * Empties the shared submission queue so the next test starts from an empty one, and reports how
     * many messages it removed.
     *
     * <p>The emulator is shared, so a message one test leaves behind is a message the next test drains
     * and cannot explain. This is the cheap deterministic alternative that lets a subclass stay composable
     * without {@code @DirtiesContext} and without an emulator of its own; a subclass calls it from a
     * callback that runs whatever its test's outcome, because a half-drained queue disrupts a
     * neighbouring specification as surely as a full one and the failure then belongs to a test that
     * already passed.</p>
     *
     * <p><strong>It receives and deletes rather than purging, and the difference is the whole point.</strong>
     * The purge operation is asynchronous by specification - a message already in flight may still be
     * delivered after it returns - so a purge in a set-up callback does not establish "the queue is
     * empty", it only makes it likely. Deleting each message by receipt handle does establish it, and
     * returning the count lets a caller assert that it started from nothing rather than assume it.
     * {@link #purgeQueue(String)} remains available for a caller that wants the bulk operation on a
     * queue of its own.</p>
     *
     * @return the number of messages removed, which is zero when the queue was already empty
     */
    protected static int resetJobSubmissionQueue() {
        return drainToEmpty(JOB_SUBMISSION_QUEUE_URL);
    }

    /**
     * Receives and deletes until a queue reports nothing further, returning how many it removed.
     *
     * <p>Bounded by the same deadline a drain uses, so a queue being filled faster than it can be
     * emptied fails with a diagnostic rather than looping without end.</p>
     *
     * @param  queueUrl the queue to empty; must not be null
     * @return the number of messages removed
     */
    private static int drainToEmpty(final String queueUrl) {
        int removed = 0;
        final Instant deadline = Instant.now().plus(DRAIN_DEADLINE);
        while (Instant.now().isBefore(deadline)) {
            final ReceiveMessageResponse response = SQS_ASYNC_CLIENT.receiveMessage(
                            ReceiveMessageRequest.builder()
                                    .queueUrl(queueUrl)
                                    .maxNumberOfMessages(RECEIVE_BATCH_SIZE)
                                    .waitTimeSeconds(RECEIVE_WAIT_SECONDS)
                                    .build())
                    .join();
            if (!response.hasMessages() || response.messages().isEmpty()) {
                return removed;
            }
            for (final Message message : response.messages()) {
                SQS_ASYNC_CLIENT.deleteMessage(DeleteMessageRequest.builder()
                                .queueUrl(queueUrl)
                                .receiptHandle(message.receiptHandle())
                                .build())
                        .join();
                removed++;
            }
        }
        throw new IllegalStateException("the queue " + queueUrl + " still reported messages after "
                + DRAIN_DEADLINE + " of receiving and deleting, having removed " + removed
                + ". Either a publisher is still writing to the shared queue or a delete is not taking"
                + " effect; a reset cannot establish an empty queue while either is true.");
    }

    /**
     * Creates the bucket with object versioning enabled, unless another test already created it.
     *
     * <p>Versioning is applied on this single creation path rather than at each call site, and it is
     * applied unconditionally rather than only when the bucket was new. Both choices are deliberate. The
     * operation that sets it is idempotent, so re-applying it to an existing bucket is a no-op rather
     * than a change; and doing it here means no caller can obtain a bucket from this class that lacks the
     * retained-generation semantics versioning carries. An unversioned bucket would accept every staged
     * object and keep only the newest, losing the retention contract with nothing failing.</p>
     *
     * @param bucket the bucket name; must not be null
     */
    protected static void createBucketIfAbsent(final String bucket) {
        try {
            S3_CLIENT.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        } catch (final BucketAlreadyExistsException | BucketAlreadyOwnedByYouException alreadyThere) {
            // The requested postcondition already holds.
        }
        S3_CLIENT.putBucketVersioning(PutBucketVersioningRequest.builder()
                .bucket(bucket)
                .versioningConfiguration(VersioningConfiguration.builder()
                        .status(BucketVersioningStatus.ENABLED)
                        .build())
                .build());
    }

    /**
     * Reads back the object versioning state a bucket reports, so a test can assert it.
     *
     * <p>A bucket that has never had versioning configured reports no status at all rather than a
     * disabled one, and the absent case is reported as
     * {@link BucketVersioningStatus#UNKNOWN_TO_SDK_VERSION} here so that a caller comparing against
     * {@link BucketVersioningStatus#ENABLED} gets a definite answer either way instead of a null.</p>
     *
     * @param  bucket the bucket to inspect; must not be null
     * @return the state the service reports
     */
    protected static BucketVersioningStatus bucketVersioningStatus(final String bucket) {
        final BucketVersioningStatus status = S3_CLIENT.getBucketVersioning(
                GetBucketVersioningRequest.builder().bucket(bucket).build()).status();
        return status == null ? BucketVersioningStatus.UNKNOWN_TO_SDK_VERSION : status;
    }

    /**
     * Stores one exact byte image, with no transformation of the content.
     *
     * @param bucket  the bucket to store into; must not be {@code null}
     * @param key     the object key to store under; must not be {@code null}
     * @param content the exact bytes to store; must not be {@code null}
     */
    protected static void putObject(final String bucket, final String key, final byte[] content) {
        S3_CLIENT.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromBytes(content));
    }

    /**
     * Removes one object. The service treats an absent key as success, so this is idempotent.
     *
     * @param bucket the bucket to remove from; must not be {@code null}
     * @param key    the object key to remove; must not be {@code null}
     */
    protected static void deleteObject(final String bucket, final String key) {
        S3_CLIENT.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    /**
     * Reads one object back as exact bytes.
     *
     * @param  bucket the bucket to read from; must not be {@code null}
     * @param  key    the object key to read; must not be {@code null}
     * @return the object's exact bytes, unmodified
     * @throws java.io.UncheckedIOException if the object body cannot be read
     */
    protected static byte[] objectBytes(final String bucket, final String key) {
        try (ResponseInputStream<GetObjectResponse> body = S3_CLIENT.getObject(
                GetObjectRequest.builder().bucket(bucket).key(key).build())) {
            return body.readAllBytes();
        } catch (final IOException failure) {
            throw new UncheckedIOException("the staged object " + key + " could not be read", failure);
        }
    }

    /**
     * Lists every object key beneath one prefix, in the store's own lexicographic order.
     *
     * <p>Needed because a durable generation number is allocated by the store at publication time
     * against what the base already holds (DL-210), so a key cannot be predicted from anything a test
     * knows beforehand. Asking the store what it holds is the only honest way to name it.
     *
     * @param  bucket the bucket to list; must not be {@code null}
     * @param  prefix the key prefix to list beneath; must not be {@code null}
     * @return the matching keys, ascending
     */
    protected static List<String> objectKeysUnder(final String bucket, final String prefix) {
        return S3_CLIENT.listObjectsV2(
                        ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build())
                .contents().stream()
                .map(S3Object::key)
                .sorted()
                .toList();
    }

    /**
     * Reports whether one object exists, without reading its body.
     *
     * @param  bucket the bucket to inspect; must not be {@code null}
     * @param  key    the object key to look for; must not be {@code null}
     * @return whether the store holds an object under that key
     */
    protected static boolean objectExists(final String bucket, final String key) {
        try {
            S3_CLIENT.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (final NoSuchKeyException absent) {
            return false;
        }
    }
}
