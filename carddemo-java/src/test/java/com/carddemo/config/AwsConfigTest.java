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
package com.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.service.JobSubmissionService;
import io.awspring.cloud.autoconfigure.s3.S3ClientCustomizer;
import io.awspring.cloud.autoconfigure.sns.SnsClientCustomizer;
import io.awspring.cloud.autoconfigure.sqs.SqsAsyncClientCustomizer;
import io.awspring.cloud.sqs.annotation.SqsListenerAnnotationBeanPostProcessor;
import io.awspring.cloud.sqs.config.MessageListenerContainerFactory;
import io.awspring.cloud.sqs.listener.MessageListenerContainer;
import io.awspring.cloud.sqs.operations.SqsOperations;
import java.net.URI;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.SmartLifecycle;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.client.config.SdkAdvancedClientOption;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.SnsClientBuilder;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClientBuilder;
import software.amazon.awssdk.services.sqs.batchmanager.SqsAsyncBatchManager;

/**
 * Verifies {@link AwsConfig}: the client-configuration contract the estate's single online-to-batch
 * bridge imposes, and the guarantee that starting this application creates nothing.
 *
 * <h2>The one bridge behind every assertion here</h2>
 *
 * <p>{@code app/csd/CARDDEMO.CSD} defines one transient-data queue, at lines 499 to 505, in the
 * {@code CARDDEMO} group and described there as submitting jobs from the online region. Exactly
 * <strong>one</strong> queue write exists in the estate's 19,254 lines of COBOL: the reporting
 * program's submission paragraph at {@code app/cbl/CORPT00C.cbl} lines 515 to 535, which writes one
 * card, inspects the response immediately, and on anything other than a normal response records
 * diagnostics, raises the program's error flag, sets the operator-visible failure message naming the
 * queue and re-sends the screen. That paragraph's name is misspelled in the source - it reads
 * {@code WIRTE-JOBSUB-TDQ} rather than {@code WRITE-JOBSUB-TDQ}, recorded as source anomaly 6 - while
 * the Java method that replaces it is spelled correctly and the traceability row carries the original
 * spelling so the mapping stays findable.
 *
 * <p>Five of that definition's attributes are behavioural rather than decorative. Each has a
 * first-in-first-out realisation, and each is the subject of one test below.
 *
 * <table class="striped">
 *   <caption>The queue definition's five load-bearing attributes and what this file asserts of each</caption>
 *   <tr><th>Attribute</th><th>What is asserted here</th></tr>
 *   <tr>
 *     <td>{@code RECORDSIZE(80)} with {@code RECORDFORMAT(FIXED)}</td>
 *     <td>nothing this class applies to the queue client batches, aggregates or collects messages, so
 *         one card stays one message</td>
 *   </tr>
 *   <tr>
 *     <td>{@code DISPOSITION(MOD)}</td>
 *     <td>the append order rests on one stable message group identifier, bound from
 *         {@link AwsProperties.Sqs#MESSAGE_GROUP_ID_PROPERTY}, not on a value derived per message</td>
 *   </tr>
 *   <tr>
 *     <td>{@code ERROROPTION(IGNORE)}</td>
 *     <td>nothing in the client configuration turns a refused publish into a start-up failure or into
 *         anything the caller cannot absorb</td>
 *   </tr>
 *   <tr>
 *     <td>{@code OPENTIME(INITIAL)}</td>
 *     <td>no resource is created while the context starts - no bucket, no queue, no topic, and no call
 *         of any kind against any client</td>
 *   </tr>
 *   <tr>
 *     <td>{@code TYPEFILE(OUTPUT)}</td>
 *     <td>the publishing surface is aimed and no consumer, listener container or annotation-driven
 *         receive path is configured</td>
 *   </tr>
 * </table>
 *
 * <h2>The subtlest failure mode in this whole area</h2>
 *
 * <p>A submission is seventeen cards, and several of them - comment cards and in-stream delimiters -
 * have <strong>byte-identical</strong> eighty-character bodies. A first-in-first-out queue with
 * content-based deduplication switched on discards a duplicate body received inside the deduplication
 * interval, silently. Seventeen cards published would then be fewer than seventeen delivered: a job
 * stream shortened to something a reader still accepts and a scheduler misreads, with no error
 * anywhere. Nothing fails, and the symptom appears far from the cause.
 *
 * <p>Two mechanisms close that hazard and either one is sufficient: the queue is created with
 * content-based deduplication <em>off</em>, or the publisher supplies an explicit per-message
 * deduplication identifier derived from each card's ordinal position. This estate uses both, and
 * neither belongs to the class under test - so what is asserted here is that <strong>{@link AwsConfig}
 * neither enables content-based deduplication nor takes the per-card identifier away from the
 * publisher</strong>, and the diagnostic on that test names both mechanisms and both owners.
 *
 * <h2>Why absence is what several assertions look for</h2>
 *
 * <p>{@link AwsConfig} builds no client. It contributes three customizers that the cloud integration
 * applies to the builders it owns, so there is no batching switch to read back and no client instance
 * to interrogate for one. The faithful assertion is therefore a <em>closed world</em> over a doubled
 * builder: the customizer is applied, every expected call is verified, and
 * {@code verifyNoMoreInteractions} then requires that nothing else was touched at all. A batching
 * decorator, a deduplication switch, a credential provider or an interceptor added later cannot pass
 * that check, which is what makes an absence assertion here stronger than a property read-back.
 *
 * <p>The same reasoning settles how the no-resource-creation guarantee is proved. Reading the class's
 * annotations for an initialisation hook would need hand-rolled reflection, which this module
 * prohibits, and would also prove less: an initialiser that created nothing would fail an annotation
 * check while an ordinary bean that quietly created a queue would pass one. So the <em>effect</em> is
 * asserted instead - doubles for the three clients are placed in the context, the context is refreshed,
 * and every one of them is required to have received no call whatsoever.
 *
 * <h2>Tier, and what deliberately is not asserted here</h2>
 *
 * <p>This is a unit test and it starts no container, needs no emulator and makes no network call. Two
 * container-free mechanisms carry every assertion: the class is instantiated directly over settings
 * built in this file, and a context runner refreshes a slice containing only the class under test. A
 * client is built twice, to read the region and the endpoint redirection back through the software
 * development kit's own configuration accessor, and no operation is ever invoked on it.
 *
 * <p>The job image itself is not this file's subject. The seventeen cards and their order, the four
 * date substitution slots, the transmitted end-of-stream sentinel card, the eighty-character bodies,
 * the drain that counts what survived deduplication, and the log-and-continue behaviour of a refused
 * publish all belong to {@code AwsIntegrationIT} and {@code e2e/OnlineTransactionE2ETest}, which have a
 * real queue to observe. The key paths themselves, their canonical values, the first-in-first-out
 * suffix guard and the blank-tolerant endpoint redirection belong to {@code AwsPropertiesTest}; here
 * only the way this class <em>consumes</em> them is asserted. Provisioning belongs to the emulator
 * bootstrap script and its own tests. No expected value in this file is produced by calling the class
 * under test or any other production helper: every expectation is a literal declared here or a
 * read-back from the software development kit or the context.
 */
@DisplayName("AWS configuration: the queue definition's five attributes, the deduplication hazard, "
        + "and where the three clients are aimed")
class AwsConfigTest {

    /**
     * The attempt count the legacy write performs: the write itself, and nothing after it.
     *
     * <p>A parity figure rather than a tuning threshold. It states what the legacy program did - one
     * queue write, its response inspected, no second try - and it is asserted as an equality for that
     * reason. No latency, throughput, capacity or time budget is asserted anywhere in this file.</p>
     */
    private static final int LEGACY_ATTEMPTS_PER_WRITE = 1;

    /** A recognisable advanced option, standing in for whatever the library sets for itself. */
    private static final String SEEDED_USER_AGENT = "carddemo-preserved-user-agent";

    /** The staging bucket the configuration documents declare. */
    private static final String BUCKET = "carddemo-batch-staging";

    /** The job-submission queue the configuration documents declare. */
    private static final String QUEUE = "carddemo-jobs.fifo";

    /**
     * The queue named without the first-in-first-out suffix the queue service requires.
     *
     * <p>This is the only place in this file where the queue appears without its suffix, and it appears
     * that way so that start-up can be required to <em>refuse</em> it. Every other occurrence carries
     * the suffix, which is mandatory rather than decorative: append ordering - and therefore the order
     * a submitted job stream is read in - survives only on a first-in-first-out queue.</p>
     */
    private static final String QUEUE_WITHOUT_FIFO_SUFFIX = "carddemo-jobs";

    /** The single message group the configuration documents declare. */
    private static final String MESSAGE_GROUP = "carddemo-job-submission";

    /** The notification topic the configuration documents declare. */
    private static final String TOPIC = "carddemo-job-notifications";

    /** The region the configuration documents declare. */
    private static final String REGION = "us-east-1";

    /**
     * A second region, named nowhere in the shipped documents.
     *
     * <p>Its only purpose is to prove that the region reaching a client builder is the configured one
     * rather than a value pinned in code: an implementation that hardcoded the canonical region would
     * satisfy every other region assertion in this file and fail this one.</p>
     */
    private static final String UNCONFIGURED_ELSEWHERE_REGION = "eu-west-1";

    /**
     * An emulator edge endpoint, standing in for whatever a local or test profile declares.
     *
     * <p>It is a loopback address and a port, and it is not a credential of anything: nothing
     * authenticates against it and it never leaves this test.</p>
     */
    private static final String EMULATOR_ENDPOINT = "http://127.0.0.1:4566";

    /**
     * A marker value supplied under key paths this namespace does not own.
     *
     * <p>It is deliberately not credential-shaped. The point of the test that uses it is that the
     * settings type binds <em>nothing</em> from such a key, so the value only has to be recognisable
     * enough to be searched for afterwards.</p>
     */
    private static final String NOTHING_BINDS_THIS = "nothing-binds-this-value";

    /**
     * The diagnostic every deduplication assertion carries.
     *
     * <p>It names both acceptable mechanisms and where each is enforced, because the actionable fact
     * for whoever reads a failure here is not that this class did something wrong in isolation - it is
     * which of the two protections has been lost and who owns it.</p>
     */
    private static final String DEDUPLICATION_DIAGNOSTIC =
            "several of a submission's seventeen cards share a byte-identical eighty-character body, "
                    + "so content-based deduplication would discard them and shorten the job stream "
                    + "silently. Exactly one of two mechanisms must remain in force: EITHER the queue "
                    + "is created with content-based deduplication switched off, which the emulator "
                    + "bootstrap script at localstack/init/01-create-aws-resources.sh does and its own "
                    + "test asserts, OR the publisher supplies an explicit per-message deduplication "
                    + "identifier derived from each card's ordinal position, which "
                    + "com.carddemo.service.JobSubmissionService does and its own test asserts. This "
                    + "class must enable neither deduplication nor anything that takes the per-card "
                    + "identifier away from the publisher";

    /**
     * Every key path the registered settings require, at the values the shared baseline declares.
     *
     * <p>Each entry names its key path through the settings type's own published constant rather than
     * through a literal, so a key path that moved would fail to compile here instead of leaving this
     * runner quietly supplying a path nothing binds.</p>
     */
    private static final String[] REQUIRED_SETTINGS = {
        AwsProperties.REGION_PROPERTY + "=" + REGION,
        AwsProperties.S3.BATCH_STAGING_BUCKET_PROPERTY + "=" + BUCKET,
        AwsProperties.Sqs.JOB_QUEUE_PROPERTY + "=" + QUEUE,
        AwsProperties.Sqs.MESSAGE_GROUP_ID_PROPERTY + "=" + MESSAGE_GROUP,
        AwsProperties.Sns.JOB_NOTIFICATION_TOPIC_PROPERTY + "=" + TOPIC,
    };

    /**
     * Runner over the class under test, carrying the settings its registration binds.
     *
     * <p>The values are supplied because this class registers the settings type, so an empty
     * environment is no longer a neutral one: it is a deployment missing every resource name, and the
     * context is expected to refuse it. That refusal is itself asserted below; here the complete set
     * is supplied so the customizer assertions observe a context that started for the right reason.</p>
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AwsConfig.class)
            .withPropertyValues(REQUIRED_SETTINGS);

    /**
     * Builds the class under test over settings that declare no endpoint redirection.
     *
     * <p>This is the shape a production deployment has, and it is the shape every attempt-count
     * assertion wants: the customizer then applies only the region, so nothing it does to a builder can
     * be confused with a redirection.</p>
     *
     * @return the configuration, over settings carrying no endpoint redirection
     */
    private static AwsConfig configuration() {
        return new AwsConfig(settings(null));
    }

    /**
     * Builds the bound settings at the values the shipped configuration documents declare.
     *
     * @param endpointOverride an endpoint redirection to declare, or {@code null} or blank for none
     * @return settings naming the four canonical resources and the canonical region
     */
    private static AwsProperties settings(final String endpointOverride) {
        return settings(REGION, endpointOverride);
    }

    /**
     * Builds the bound settings over an arbitrary region.
     *
     * @param region           the region to declare
     * @param endpointOverride an endpoint redirection to declare, or {@code null} or blank for none
     * @return settings naming the four canonical resources and the given region
     */
    private static AwsProperties settings(final String region, final String endpointOverride) {
        return new AwsProperties(
                region,
                endpointOverride,
                new AwsProperties.S3(BUCKET),
                new AwsProperties.Sqs(QUEUE, MESSAGE_GROUP),
                new AwsProperties.Sns(TOPIC));
    }

    /**
     * Applies all three customizers, so one assertion can speak about all three clients.
     *
     * <p>The queue customizer reads the builder's current override configuration before extending it,
     * so a bare double would hand it {@code null}. Seeding an empty configuration lets the queue client
     * be observed on the same terms as the other two.</p>
     *
     * @param configuration the class under test
     * @param objectStore   a double for the object-store client builder
     * @param queue         a double for the queue client builder
     * @param notifications a double for the notification client builder
     */
    private static void aimEveryClient(final AwsConfig configuration,
            final S3ClientBuilder objectStore,
            final SqsAsyncClientBuilder queue,
            final SnsClientBuilder notifications) {
        when(queue.overrideConfiguration()).thenReturn(ClientOverrideConfiguration.builder().build());
        configuration.batchStagingS3ClientCustomizer().customize(objectStore);
        configuration.singleAttemptSqsClientCustomizer().customize(queue);
        configuration.jobNotificationSnsClientCustomizer().customize(notifications);
    }

    /**
     * Gathers the three doubled builders so an assertion can be made of each in turn.
     *
     * @param objectStore   a double for the object-store client builder
     * @param queue         a double for the queue client builder
     * @param notifications a double for the notification client builder
     * @return the three builders, in the order the three clients are named throughout this module
     */
    private static AwsClientBuilder<?, ?>[] everyClient(final S3ClientBuilder objectStore,
            final SqsAsyncClientBuilder queue,
            final SnsClientBuilder notifications) {
        return new AwsClientBuilder<?, ?>[] {objectStore, queue, notifications};
    }

    @Nested
    @DisplayName("The publish attempt count")
    class PublishAttemptCount {

        @Test
        @DisplayName("is reduced to the single attempt the legacy queue write made")
        void isReducedToASingleAttempt() {
            final SqsAsyncClientBuilder builder = SqsAsyncClient.builder();

            configuration().singleAttemptSqsClientCustomizer().customize(builder);

            assertThat(builder.overrideConfiguration().retryStrategy())
                    .as("an unset strategy leaves the client on its own default, which retries")
                    .isPresent()
                    .get()
                    .extracting(strategy -> strategy.maxAttempts())
                    .as("more than one attempt would let a refused publish succeed on a later try, "
                            + "which the legacy write had no way of doing")
                    .isEqualTo(LEGACY_ATTEMPTS_PER_WRITE);
        }

        @Test
        @DisplayName("is set by extending the configuration already applied, not by replacing it")
        void isSetByExtendingTheExistingConfiguration() {
            final SqsAsyncClientBuilder builder = SqsAsyncClient.builder()
                    .overrideConfiguration(ClientOverrideConfiguration.builder()
                            .putAdvancedOption(SdkAdvancedClientOption.USER_AGENT_PREFIX,
                                    SEEDED_USER_AGENT)
                            .build());

            configuration().singleAttemptSqsClientCustomizer().customize(builder);

            final ClientOverrideConfiguration applied = builder.overrideConfiguration();
            assertThat(applied.advancedOption(SdkAdvancedClientOption.USER_AGENT_PREFIX))
                    .as("the messaging library installs its own override configuration before "
                            + "customizers run, so a customizer that built a fresh configuration "
                            + "would silently discard it while still passing the attempt assertion")
                    .contains(SEEDED_USER_AGENT);
            assertThat(applied.retryStrategy())
                    .isPresent()
                    .get()
                    .extracting(strategy -> strategy.maxAttempts())
                    .isEqualTo(LEGACY_ATTEMPTS_PER_WRITE);
        }

        @Test
        @DisplayName("is applied idempotently, so ordering among customizers cannot change it")
        void isAppliedIdempotently() {
            final SqsAsyncClientBuilder builder = SqsAsyncClient.builder();
            final SqsAsyncClientCustomizer customizer =
                    configuration().singleAttemptSqsClientCustomizer();

            customizer.customize(builder);
            customizer.customize(builder);

            assertThat(builder.overrideConfiguration().retryStrategy())
                    .isPresent()
                    .get()
                    .extracting(strategy -> strategy.maxAttempts())
                    .isEqualTo(LEGACY_ATTEMPTS_PER_WRITE);
        }
    }

    @Nested
    @DisplayName("The queue definition's five load-bearing attributes")
    class QueueDefinitionAttributes {

        @Test
        @DisplayName("keep one card as one message, because nothing applied here batches, aggregates "
                + "or collects them")
        void keepOneCardAsOneMessage() {
            final SqsAsyncClientBuilder queue = mock(SqsAsyncClientBuilder.class);
            when(queue.overrideConfiguration())
                    .thenReturn(ClientOverrideConfiguration.builder().build());

            configuration().singleAttemptSqsClientCustomizer().customize(queue);

            verify(queue).region(Region.of(REGION));
            verify(queue).overrideConfiguration();
            verify(queue).overrideConfiguration(any(ClientOverrideConfiguration.class));
            verifyNoMoreInteractions(queue);

            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBeanNamesForType(SqsAsyncBatchManager.class))
                        .as("a batch manager collects sends into one request, and the queue "
                                + "definition's fixed eighty-character record format makes one card "
                                + "one message; merging two cards would produce a record the reader "
                                + "cannot parse")
                        .isEmpty();
            });
        }

        @Test
        @DisplayName("rest the append order on one stable message group rather than on a value derived "
                + "per message")
        void restTheAppendOrderOnOneStableMessageGroup() {
            runner.run(context -> {
                assertThat(context).hasNotFailed();

                final AwsProperties first = context.getBean(AwsProperties.class);
                final AwsProperties second = context.getBean(AwsProperties.class);
                assertThat(second)
                        .as("one settings instance serves every publish, so every card of every "
                                + "submission reads the same group")
                        .isSameAs(first);
                assertThat(first.sqs().messageGroupId())
                        .as("the group is bound from " + AwsProperties.Sqs.MESSAGE_GROUP_ID_PROPERTY)
                        .isEqualTo(MESSAGE_GROUP);
                assertThat(second.sqs().messageGroupId())
                        .as("a second read must not produce a second group")
                        .isEqualTo(first.sqs().messageGroupId());
                assertThat(first.sqs().messageGroupId())
                        .as("a group carrying a placeholder or a format marker would be one group per "
                                + "message, and a first-in-first-out queue preserves append order only "
                                + "within a single group")
                        .doesNotContain("{", "}", "%", "$", "#");
            });
        }

        @Test
        @DisplayName("turn a refused publish into nothing fatal, because no interceptor is installed "
                + "and no budget of any kind is set")
        void turnARefusedPublishIntoNothingFatal() {
            final SqsAsyncClientBuilder builder = SqsAsyncClient.builder();

            configuration().singleAttemptSqsClientCustomizer().customize(builder);

            final ClientOverrideConfiguration applied = builder.overrideConfiguration();
            assertThat(applied.executionInterceptors())
                    .as("an interceptor is the only client-level hook that could turn a refused "
                            + "publish into something other than the refusal the publisher reports "
                            + "and logs; this class installs none")
                    .isEmpty();
            assertThat(applied.apiCallTimeout())
                    .as("no call budget is set here, and the absence is the point: the legacy write "
                            + "had none and nothing in this migration establishes one")
                    .isEmpty();
            assertThat(applied.apiCallAttemptTimeout())
                    .as("no per-attempt budget is set here either, for the same reason")
                    .isEmpty();
            assertThat(applied.retryStrategy())
                    .as("what the class does set is the single attempt, so a refusal stays a refusal "
                            + "rather than becoming a later success")
                    .isPresent();
        }

        @Test
        @DisplayName("create no resource while the context starts, since the queue existed before the "
                + "first write and the writing program never made it")
        void createNoResourceWhileTheContextStarts() {
            final S3Client objectStore = mock(S3Client.class);
            final SqsAsyncClient queue = mock(SqsAsyncClient.class);
            final SnsClient notifications = mock(SnsClient.class);

            runner.withBean(S3Client.class, () -> objectStore)
                    .withBean(SqsAsyncClient.class, () -> queue)
                    .withBean(SnsClient.class, () -> notifications)
                    .run(context -> {
                        assertThat(context).hasNotFailed()
                                .hasSingleBean(S3Client.class)
                                .hasSingleBean(SqsAsyncClient.class)
                                .hasSingleBean(SnsClient.class);

                        // A provisioning call of any kind - createBucket, createQueue, createTopic -
                        // would be recorded on one of these three doubles. Requiring zero
                        // interactions across the whole refresh therefore covers every provisioning
                        // path at once, including one hidden behind an initialisation hook, and it
                        // covers calls this file never had to name.
                        verifyNoInteractions(objectStore, queue, notifications);

                        assertThat(context.getBeanNamesForType(CommandLineRunner.class))
                                .as("a runner would execute after refresh and could provision "
                                        + "whatever it liked")
                                .isEmpty();
                        assertThat(context.getBeanNamesForType(ApplicationRunner.class))
                                .as("for the same reason as the command-line runner")
                                .isEmpty();
                        assertThat(context.getBeanNamesForType(SmartLifecycle.class))
                                .as("a lifecycle bean starts with the context and could provision "
                                        + "there instead")
                                .isEmpty();
                        assertThat(context.getBean(AwsConfig.class))
                                .as("nor may the configuration itself carry an initialisation hook")
                                .isNotInstanceOf(InitializingBean.class)
                                .isNotInstanceOf(SmartLifecycle.class);
                    });
        }

        @Test
        @DisplayName("leave the queue published to and never consumed from, so no listener or "
                + "annotation-driven receive path is configured")
        void leaveTheQueuePublishedToAndNeverConsumedFrom() {
            runner.run(context -> {
                assertThat(context)
                        .as("the publishing surface is aimed: the queue client the publisher sends "
                                + "through is the one this customizer configures")
                        .hasNotFailed()
                        .hasSingleBean(SqsAsyncClientCustomizer.class);

                assertThat(context.getBeanNamesForType(MessageListenerContainer.class))
                        .as("this module writes job cards and reads none back; a listener container "
                                + "would consume the very cards the scheduler is meant to collect")
                        .isEmpty();
                assertThat(context.getBeanNamesForType(MessageListenerContainerFactory.class))
                        .as("nor may a factory stand ready to create one")
                        .isEmpty();
                assertThat(context.getBeanNamesForType(SqsListenerAnnotationBeanPostProcessor.class))
                        .as("nor may the annotation-driven receive path be enabled")
                        .isEmpty();
            });
        }
    }

    @Nested
    @DisplayName("Message deduplication")
    class MessageDeduplication {

        @Test
        @DisplayName("stays with the two mechanisms that keep every card, because nothing registered "
                + "here takes the per-card identifier over")
        void staysWithTheTwoMechanismsThatKeepEveryCard() {
            runner.run(context -> {
                assertThat(context)
                        .as("the slice must be live, or the absence asserted next would be the "
                                + "absence of everything")
                        .hasNotFailed()
                        .hasSingleBean(SqsAsyncClientCustomizer.class);
                assertThat(context.getBeanNamesForType(SqsOperations.class))
                        .as(DEDUPLICATION_DIAGNOSTIC + ", and a queue template registered here would "
                                + "do exactly that: it would fix the per-message properties for every "
                                + "send and leave the publisher unable to supply an identifier of its "
                                + "own")
                        .isEmpty();
            });
        }

        @Test
        @DisplayName("is not switched on by anything this class applies to the queue client")
        void isNotSwitchedOnByAnythingAppliedToTheQueueClient() {
            final SqsAsyncClientBuilder queue = mock(SqsAsyncClientBuilder.class);
            when(queue.overrideConfiguration())
                    .thenReturn(ClientOverrideConfiguration.builder().build());

            configuration().singleAttemptSqsClientCustomizer().customize(queue);

            verify(queue).region(Region.of(REGION));
            verify(queue).overrideConfiguration();
            verify(queue).overrideConfiguration(any(ClientOverrideConfiguration.class));
            verifyNoMoreInteractions(queue);

            final SqsAsyncClientBuilder real = SqsAsyncClient.builder();
            configuration().singleAttemptSqsClientCustomizer().customize(real);
            assertThat(real.overrideConfiguration().executionInterceptors())
                    .as(DEDUPLICATION_DIAGNOSTIC + ", and an interceptor could rewrite or drop the "
                            + "identifier the publisher attached without the publisher ever knowing")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Where the three clients are aimed")
    class ClientAiming {

        @Test
        @DisplayName("is the configured region, on every one of the three clients, always")
        void appliesTheConfiguredRegionToEveryClient() {
            final S3ClientBuilder objectStore = mock(S3ClientBuilder.class);
            final SqsAsyncClientBuilder queue = mock(SqsAsyncClientBuilder.class);
            final SnsClientBuilder notifications = mock(SnsClientBuilder.class);

            aimEveryClient(configuration(), objectStore, queue, notifications);

            for (final AwsClientBuilder<?, ?> builder
                    : everyClient(objectStore, queue, notifications)) {
                verify(builder).region(Region.of(REGION));
            }
        }

        @Test
        @DisplayName("is redirected away from that region's endpoint on every client when a "
                + "redirection is configured")
        void appliesAConfiguredRedirectionToEveryClient() {
            final S3ClientBuilder objectStore = mock(S3ClientBuilder.class);
            final SqsAsyncClientBuilder queue = mock(SqsAsyncClientBuilder.class);
            final SnsClientBuilder notifications = mock(SnsClientBuilder.class);

            aimEveryClient(new AwsConfig(settings(EMULATOR_ENDPOINT)),
                    objectStore, queue, notifications);

            final URI expected = URI.create(EMULATOR_ENDPOINT);
            for (final AwsClientBuilder<?, ?> builder
                    : everyClient(objectStore, queue, notifications)) {
                verify(builder).endpointOverride(expected);
            }
        }

        @Test
        @DisplayName("is that region's own endpoint when no redirection is configured, which is the "
                + "state of a deployment")
        void appliesNoRedirectionWhenNoneIsConfigured() {
            assertNoRedirectionIsApplied(null);
        }

        @Test
        @DisplayName("is that region's own endpoint when the redirection is blank, because a blank "
                + "value is an absent one rather than a malformed address")
        void appliesNoRedirectionWhenTheConfiguredValueIsBlank() {
            assertNoRedirectionIsApplied("   ");
        }

        @Test
        @DisplayName("follows whichever region is configured, rather than one pinned in code")
        void followsWhicheverRegionIsConfigured() {
            final S3ClientBuilder objectStore = mock(S3ClientBuilder.class);
            final SqsAsyncClientBuilder queue = mock(SqsAsyncClientBuilder.class);
            final SnsClientBuilder notifications = mock(SnsClientBuilder.class);

            aimEveryClient(new AwsConfig(settings(UNCONFIGURED_ELSEWHERE_REGION, null)),
                    objectStore, queue, notifications);

            for (final AwsClientBuilder<?, ?> builder
                    : everyClient(objectStore, queue, notifications)) {
                verify(builder)
                        .region(Region.of(UNCONFIGURED_ELSEWHERE_REGION));
                verify(builder, never()).region(Region.of(REGION));
            }
        }

        @Test
        @DisplayName("carries no credential provider on any client, so resolution stays with the "
                + "software development kit's own chain")
        void carriesNoCredentialProviderOnAnyClient() {
            final S3ClientBuilder objectStore = mock(S3ClientBuilder.class);
            final SqsAsyncClientBuilder queue = mock(SqsAsyncClientBuilder.class);
            final SnsClientBuilder notifications = mock(SnsClientBuilder.class);

            aimEveryClient(configuration(), objectStore, queue, notifications);

            for (final AwsClientBuilder<?, ?> builder
                    : everyClient(objectStore, queue, notifications)) {
                verify(builder).region(Region.of(REGION));
                verify(builder, never()).credentialsProvider(any(AwsCredentialsProvider.class));
            }
        }

        /**
         * Requires that no client is redirected, while every client is still given its region.
         *
         * <p>The paired region assertion is what stops this from passing vacuously: a customizer that
         * did nothing at all would satisfy the redirection half on its own.</p>
         *
         * @param endpointOverride the redirection to configure, expected to be treated as absent
         */
        private void assertNoRedirectionIsApplied(final String endpointOverride) {
            final S3ClientBuilder objectStore = mock(S3ClientBuilder.class);
            final SqsAsyncClientBuilder queue = mock(SqsAsyncClientBuilder.class);
            final SnsClientBuilder notifications = mock(SnsClientBuilder.class);

            aimEveryClient(new AwsConfig(settings(endpointOverride)),
                    objectStore, queue, notifications);

            for (final AwsClientBuilder<?, ?> builder
                    : everyClient(objectStore, queue, notifications)) {
                verify(builder).region(Region.of(REGION));
                verify(builder, never()).endpointOverride(any());
            }
        }
    }

    @Nested
    @DisplayName("A client built through the customizer")
    class BuiltClient {

        @Test
        @DisplayName("carries the configured region and the configured redirection, read back from the "
                + "kit's own view of the finished client")
        void carriesTheConfiguredRegionAndRedirection() {
            final S3ClientBuilder builder = S3Client.builder();

            new AwsConfig(settings(EMULATOR_ENDPOINT)).batchStagingS3ClientCustomizer()
                    .customize(builder);

            try (S3Client client = builder.build()) {
                assertThat(client.serviceClientConfiguration().region())
                        .as("verifying the call on a double proves the customizer asked; reading the "
                                + "finished client proves the request survived to it")
                        .isEqualTo(Region.of(REGION));
                assertThat(client.serviceClientConfiguration().endpointOverride())
                        .as("a client that dropped the redirection would address the real service "
                                + "from a developer's machine")
                        .contains(URI.create(EMULATOR_ENDPOINT));
            }
        }

        @Test
        @DisplayName("resolves its own regional endpoint and the default credential chain when no "
                + "redirection is configured")
        void resolvesItsOwnEndpointAndTheDefaultCredentialChain() {
            final S3ClientBuilder builder = S3Client.builder();

            configuration().batchStagingS3ClientCustomizer().customize(builder);

            try (S3Client client = builder.build()) {
                assertThat(client.serviceClientConfiguration().region())
                        .as("the region is applied unconditionally, redirection or not")
                        .isEqualTo(Region.of(REGION));
                assertThat(client.serviceClientConfiguration().endpointOverride())
                        .as("an absent redirection must leave the client resolving its region's own "
                                + "endpoint, which is the state a deployment runs in")
                        .isEmpty();
                assertThat(client.serviceClientConfiguration().credentialsProvider())
                        .as("credential resolution belongs to the kit's own provider chain; this "
                                + "module's namespace holds no credential and this class sets none")
                        .isInstanceOf(DefaultCredentialsProvider.class);
            }
        }
    }

    @Nested
    @DisplayName("Container wiring")
    class ContainerWiring {

        @Test
        @DisplayName("contributes one customizer per client, each under the type the "
                + "auto-configuration collects")
        void contributesOneCustomizerPerClientUnderTheTypesTheAutoConfigurationCollects() {
            runner.run(context -> assertThat(context)
                    .as("each of the three clients is aimed by the customizer type its own "
                            + "auto-configuration collects; a customizer registered under any other "
                            + "type is never applied and fails silently")
                    .hasNotFailed()
                    .hasSingleBean(S3ClientCustomizer.class)
                    .hasSingleBean(SqsAsyncClientCustomizer.class)
                    .hasSingleBean(SnsClientCustomizer.class));
        }

        @Test
        @DisplayName("builds no client and reads no credential, leaving the client itself "
                + "auto-configured and its credentials to the provider chain")
        void buildsNoClientOfItsOwn() {
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBeanNamesForType(SqsAsyncClient.class))
                        .as("this class customizes the auto-configured client; publishing one of its "
                                + "own would take over credential resolution and the object store's "
                                + "addressing style from the integration's own settings, leaving two "
                                + "sources of truth for one setting")
                        .isEmpty();
                assertThat(context.getBeanDefinitionNames())
                        .as("three contributions, one per client, and nothing else has crept in")
                        .containsOnlyOnce("batchStagingS3ClientCustomizer")
                        .containsOnlyOnce("singleAttemptSqsClientCustomizer")
                        .containsOnlyOnce("jobNotificationSnsClientCustomizer");
            });
        }
    }

    @Nested
    @DisplayName("The bridge settings this class owns")
    class BridgeSettingsRegistration {

        @Test
        @DisplayName("are registered here, exactly once, so their binding is a live start-up gate "
                + "rather than an unreachable declaration")
        void areRegisteredHereExactlyOnce() {
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context)
                        .as("the settings type carries no stereotype and nothing scans for it, so "
                                + "unregistered it binds nowhere and its constraints never run")
                        .hasSingleBean(AwsProperties.class);

                final AwsProperties bound = context.getBean(AwsProperties.class);
                assertThat(bound.region()).as("bound region").isEqualTo(REGION);
                assertThat(bound.s3().batchStagingBucket()).as("bound staging bucket").isEqualTo(BUCKET);
                assertThat(bound.sqs().jobQueue()).as("bound submission queue")
                        .isEqualTo(QUEUE);
                assertThat(bound.sqs().messageGroupId()).as("bound message group")
                        .isEqualTo(MESSAGE_GROUP);
                assertThat(bound.sns().jobNotificationTopic()).as("bound notification topic")
                        .isEqualTo(TOPIC);
            });
        }

        @Test
        @DisplayName("refuse a deployment that supplies no resource name at all, which is what "
                + "registration buys and what an unregistered declaration could not do")
        void refuseADeploymentThatSuppliesNoResourceName() {
            new ApplicationContextRunner().withUserConfiguration(AwsConfig.class)
                    .run(context -> assertThat(context)
                            .as("a missing queue, bucket, group or topic must stop start-up rather "
                                    + "than surface at the first publish")
                            .hasFailed());
        }

        @Test
        @DisplayName("refuse a queue name that is not a first-in-first-out name, which is the single "
                + "easiest misconfiguration in this namespace")
        void refuseAQueueNameThatIsNotFirstInFirstOut() {
            runner.withPropertyValues(
                    AwsProperties.Sqs.JOB_QUEUE_PROPERTY + "=" + QUEUE_WITHOUT_FIFO_SUFFIX)
                    .run(context -> assertThat(context)
                            .as("append ordering is only preserved by a first-in-first-out queue, so "
                                    + "a plain name must be refused before a card is published")
                            .hasFailed());
        }

        @Test
        @DisplayName("refuse a blanked resource name, so a deployment cannot empty a value the "
                + "baseline defaults")
        void refuseABlankedResourceName() {
            runner.withPropertyValues(AwsProperties.S3.BATCH_STAGING_BUCKET_PROPERTY + "=")
                    .run(context -> assertThat(context)
                            .as("a blank bucket would produce requests against no bucket")
                            .hasFailed());
        }

        @Test
        @DisplayName("carry whichever names are configured, so the four resources are threaded from "
                + "configuration rather than pinned in code")
        void carryWhicheverNamesAreConfigured() {
            final String otherBucket = "carddemo-other-staging";
            final String otherQueue = "carddemo-other-jobs.fifo";
            final String otherGroup = "carddemo-other-submission";
            final String otherTopic = "carddemo-other-notifications";

            runner.withPropertyValues(
                    AwsProperties.S3.BATCH_STAGING_BUCKET_PROPERTY + "=" + otherBucket,
                    AwsProperties.Sqs.JOB_QUEUE_PROPERTY + "=" + otherQueue,
                    AwsProperties.Sqs.MESSAGE_GROUP_ID_PROPERTY + "=" + otherGroup,
                    AwsProperties.Sns.JOB_NOTIFICATION_TOPIC_PROPERTY + "=" + otherTopic)
                    .run(context -> {
                        assertThat(context).hasNotFailed();

                        final AwsProperties bound = context.getBean(AwsProperties.class);
                        assertThat(bound.s3().batchStagingBucket())
                                .as("a bucket named in code would ignore what a deployment declared")
                                .isEqualTo(otherBucket);
                        assertThat(bound.sqs().jobQueue()).isEqualTo(otherQueue);
                        assertThat(bound.sqs().messageGroupId()).isEqualTo(otherGroup);
                        assertThat(bound.sns().jobNotificationTopic()).isEqualTo(otherTopic);
                    });
        }

        @Test
        @DisplayName("bind nothing from a key path this namespace does not own, so no value can arrive "
                + "at a client through a path nothing reads")
        void bindNothingFromAKeyPathThisNamespaceDoesNotOwn() {
            runner.withPropertyValues(
                    AwsProperties.PREFIX + ".access-key-id=" + NOTHING_BINDS_THIS,
                    AwsProperties.PREFIX + ".credentials.access-key=" + NOTHING_BINDS_THIS)
                    .run(context -> {
                        assertThat(context)
                                .as("a key path outside the published set is ignored rather than "
                                        + "refused, so start-up must still succeed")
                                .hasNotFailed();
                        assertThat(context.getBean(AwsProperties.class).toString())
                                .as("the description states what was bound, which is what makes the "
                                        + "search below meaningful rather than a search of nothing")
                                .contains(QUEUE)
                                .as("this namespace publishes five components and none of them is a "
                                        + "credential; a value supplied under an unowned path must "
                                        + "reach none of them, and the kit's own provider chain "
                                        + "remains the only source of a credential")
                                .doesNotContain(NOTHING_BINDS_THIS);
                    });
        }

        @Test
        @DisplayName("are the same key paths the publisher binds: the publisher starts against an "
                + "environment carrying only the paths these settings own")
        void areTheSameKeyPathsThePublisherBinds() {
            // The publisher may not depend on this configuration layer, so it binds the two queue key
            // paths itself. That leaves one thing worth proving: that the paths it binds are the paths
            // these settings own. Supplying ONLY the settings' own published paths and then requiring
            // the publisher to be constructible from that environment proves it - a publisher reading
            // any other path would receive an unresolved placeholder and refuse it at construction.
            runner.withUserConfiguration(JobSubmissionService.class)
                    .withBean(SqsOperations.class, () -> mock(SqsOperations.class))
                    .run(context -> {
                        assertThat(context)
                                .as("the publisher must be satisfied by the very key paths the "
                                        + "settings type publishes, with nothing else supplied")
                                .hasNotFailed();
                        assertThat(context).hasSingleBean(JobSubmissionService.class);
                        assertThat(context).hasSingleBean(AwsProperties.class);
                    });
        }

        @Test
        @DisplayName("and no other name will do: renaming either queue path leaves the publisher "
                + "unsatisfied, so the previous assertion is not vacuous")
        void andNoOtherNameWillDo() {
            for (final String withheld : new String[] {
                AwsProperties.Sqs.JOB_QUEUE_PROPERTY,
                AwsProperties.Sqs.MESSAGE_GROUP_ID_PROPERTY, }) {
                new ApplicationContextRunner().withUserConfiguration(JobSubmissionService.class)
                        .withBean(SqsOperations.class, () -> mock(SqsOperations.class))
                        .withPropertyValues(settingsWithPathRenamed(withheld))
                        .run(context -> assertThat(context)
                                .as("supplying " + withheld + " under any other path must leave the "
                                        + "publisher unsatisfied")
                                .hasFailed());
            }
        }

        /**
         * Returns the required settings with one key path moved to a near-miss name.
         *
         * <p>The value is still present and still correct; only the path it is published under
         * changes, which is exactly the drift this pair of tests is built to detect.</p>
         *
         * @param renamedPath the key path to publish under a different name
         * @return {@code key=value} entries, one per required setting
         */
        private String[] settingsWithPathRenamed(final String renamedPath) {
            return Stream.concat(
                    Arrays.stream(REQUIRED_SETTINGS)
                            .filter(entry -> !entry.startsWith(renamedPath + "=")),
                    Stream.of(renamedPath + "-renamed=" + QUEUE))
                    .toArray(String[]::new);
        }
    }
}
