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
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.sns.core.SnsOperations;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.ListTopicsResponse;
import software.amazon.awssdk.services.sns.model.Topic;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;

/**
 * Verifies that AWS readiness checks observe the frozen resource inventory without provisioning it.
 */
@DisplayName("AWS resource readiness is read-only, bounded and separated from liveness")
class AwsResourceHealthConfigTest {

    private static final String BUCKET = "carddemo-batch-staging";
    private static final String QUEUE = "JOBS.fifo";
    private static final String QUEUE_URL =
            "http://localhost:4566/000000000000/JOBS.fifo";
    private static final String QUEUE_ARN =
            "arn:aws:sqs:us-east-1:000000000000:JOBS.fifo";
    private static final String TOPIC = "carddemo-job-notifications";
    private static final String TOPIC_ARN =
            "arn:aws:sns:us-east-1:000000000000:carddemo-job-notifications";

    /**
     * The probe interval the container image asks at, in seconds.
     *
     * <p>Named here and read out of the image itself by the specification below, so the claim that the
     * freshness window is shorter than the probe cadence is measured against the shipped document rather
     * than against a remembered number.
     */
    private static final long CONTAINER_PROBE_INTERVAL_SECONDS = 10L;

    /** A wait no correct run ever reaches, so a hung run fails rather than hanging the suite. */
    private static final long FAR_LONGER_THAN_ANY_WAIT_SECONDS = 5L;

    private S3Operations s3Operations;
    private SqsAsyncClient sqsAsyncClient;
    private SnsClient snsClient;
    private SnsOperations snsOperations;
    private AwsResourceHealthConfig configuration;
    private ExecutorService workers;

    /**
     * The elapsed-time source the contributors read their freshness window against.
     *
     * <p>Moved deliberately rather than waited out, so an expiry is asserted at the instant it happens
     * instead of approximated by sleeping past it.
     */
    private MovableTicker ticker;

    /**
     * The registry a probe's observation is carried from onto its worker.
     *
     * <p>Recording, because "the context was carried" is only assertable as "the worker's call was seen
     * as a child". A plain registry would let a lost context pass unnoticed.
     */
    private ObservationRegistry observationRegistry;

    /** Every observation stopped during a test, in stop order. */
    private final List<Observation.Context> observed =
            java.util.Collections.synchronizedList(new ArrayList<>());

    /**
     * Builds a registry that records what it observed.
     *
     * @return a recording registry
     */
    private ObservationRegistry recordingRegistry() {
        final ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public void onStop(final Observation.Context context) {
                observed.add(context);
            }

            @Override
            public boolean supportsContext(final Observation.Context context) {
                return true;
            }
        });
        return registry;
    }

    @BeforeEach
    void setUp() {
        s3Operations = mock(S3Operations.class);
        sqsAsyncClient = mock(SqsAsyncClient.class);
        snsClient = mock(SnsClient.class);
        snsOperations = mock(SnsOperations.class);
        observed.clear();
        observationRegistry = recordingRegistry();
        configuration = new AwsResourceHealthConfig(settings(), observationRegistry);
        workers = configuration.awsHealthCheckExecutor();
        ticker = new MovableTicker();
    }

    @AfterEach
    void tearDown() {
        workers.shutdownNow();
    }

    @Test
    @DisplayName("requires the validated AWS inventory")
    void requiresTheAwsInventory() {
        assertThatNullPointerException()
                .isThrownBy(() -> new AwsResourceHealthConfig(null, ObservationRegistry.create()))
                .withMessageContaining("awsProperties");
        assertThatNullPointerException()
                .as("without a registry a worker's provider call becomes a detached one-span trace, so "
                        + "the registry is as mandatory as the inventory")
                .isThrownBy(() -> new AwsResourceHealthConfig(settings(), null))
                .withMessageContaining("observationRegistry");
    }

    @Test
    @DisplayName("publishes three contributors without touching any client while the context starts")
    void publishesThreeLazyContributors() {
        new ApplicationContextRunner()
                .withUserConfiguration(AwsResourceHealthConfig.class)
                .withBean(AwsProperties.class, AwsResourceHealthConfigTest::settings)
                // Registered explicitly: this slice registers beans directly rather than reading the
                // shipped documents, so the observation registry the auto-configuration would supply is
                // not present and the configuration requires one.
                .withBean(ObservationRegistry.class, ObservationRegistry::create)
                .withBean(S3Operations.class, () -> s3Operations)
                .withBean(SqsAsyncClient.class, () -> sqsAsyncClient)
                .withBean(SnsClient.class, () -> snsClient)
                .withBean(SnsOperations.class, () -> snsOperations)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(HealthIndicator.class))
                            .containsOnlyKeys("awsS3HealthIndicator", "awsSqsHealthIndicator",
                                    "awsSnsHealthIndicator");
                    verifyNoInteractions(s3Operations, sqsAsyncClient, snsClient, snsOperations);
                });
    }

    @Test
    @DisplayName("reports the staging bucket up only when the non-creating existence check succeeds")
    void checksTheBucketWithoutCreatingIt() {
        when(s3Operations.bucketExists(BUCKET)).thenReturn(true, false);
        final HealthIndicator indicator =
                configuration.awsS3HealthIndicator(s3Operations, workers, ticker);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        // Past the freshness window, so the second answer below is a second evaluation of the bucket
        // rather than the first one repeated. Both outcomes are the point of this specification, and
        // inside the window there would only ever be one of them.
        ticker.advanceBeyondTheWindow();
        final Health absent = indicator.health();
        assertThat(absent.getStatus()).isEqualTo(Status.DOWN);
        assertThat(absent.getDetails()).containsEntry("reason", "resource-not-found");
    }

    @Test
    @DisplayName("reduces an object-store failure to its bounded type chain")
    void boundsBucketFailureDetails() {
        when(s3Operations.bucketExists(BUCKET))
                .thenThrow(new IllegalStateException("provider path /secret must not escape"));

        final Health health = configuration.awsS3HealthIndicator(s3Operations, workers).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("failureChain").toString())
                .contains("IllegalStateException")
                .doesNotContain("provider path", "/secret");
    }

    @Test
    @DisplayName("resolves the FIFO queue with FAIL strategy and never invokes createQueue")
    void checksTheQueueWithoutCreatingIt() {
        when(sqsAsyncClient.getQueueUrl(any(GetQueueUrlRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        GetQueueUrlResponse.builder().queueUrl(QUEUE_URL).build()));
        when(sqsAsyncClient.getQueueAttributes(
                org.mockito.ArgumentMatchers
                        .<Consumer<GetQueueAttributesRequest.Builder>>any()))
                .thenReturn(CompletableFuture.completedFuture(
                        GetQueueAttributesResponse.builder()
                                .attributes(Map.of(QueueAttributeName.QUEUE_ARN, QUEUE_ARN))
                                .build()));

        final Health health = configuration.awsSqsHealthIndicator(sqsAsyncClient).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        verify(sqsAsyncClient).getQueueUrl(any(GetQueueUrlRequest.class));
        verify(sqsAsyncClient).getQueueAttributes(
                org.mockito.ArgumentMatchers
                        .<Consumer<GetQueueAttributesRequest.Builder>>any());
        verifyNoMoreInteractions(sqsAsyncClient);
    }

    @Test
    @DisplayName("a configured queue URL is verified with a read rather than accepted by syntax")
    void verifiesAConfiguredQueueUrl() {
        final AwsResourceHealthConfig urlConfiguration =
                new AwsResourceHealthConfig(settings(QUEUE_URL), observationRegistry);
        when(sqsAsyncClient.getQueueAttributes(
                org.mockito.ArgumentMatchers
                        .<Consumer<GetQueueAttributesRequest.Builder>>any()))
                .thenReturn(CompletableFuture.failedFuture(
                        QueueDoesNotExistException.builder().message("missing").build()));

        final Health health = urlConfiguration.awsSqsHealthIndicator(sqsAsyncClient).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        verify(sqsAsyncClient, never()).getQueueUrl(any(GetQueueUrlRequest.class));
        verify(sqsAsyncClient).getQueueAttributes(
                org.mockito.ArgumentMatchers
                        .<Consumer<GetQueueAttributesRequest.Builder>>any());
        verifyNoMoreInteractions(sqsAsyncClient);
    }

    @Test
    @DisplayName("reports a missing queue down without copying the provider message")
    void reportsMissingQueueDown() {
        when(sqsAsyncClient.getQueueUrl(any(GetQueueUrlRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        QueueDoesNotExistException.builder()
                                .message("secret provider detail")
                                .build()));

        final Health health = configuration.awsSqsHealthIndicator(sqsAsyncClient).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("failureChain").toString())
                .contains("Queue")
                .doesNotContain("secret provider detail");
        verify(sqsAsyncClient).getQueueUrl(any(GetQueueUrlRequest.class));
        verifyNoMoreInteractions(sqsAsyncClient);
    }

    @Test
    @DisplayName("lists and verifies the configured topic rather than invoking the creating resolver")
    void checksTheTopicWithoutCreatingIt() {
        when(snsClient.listTopics()).thenReturn(ListTopicsResponse.builder()
                .topics(Topic.builder().topicArn(TOPIC_ARN).build())
                .build());
        when(snsOperations.topicExists(TOPIC_ARN)).thenReturn(true);

        final Health health =
                configuration.awsSnsHealthIndicator(snsClient, snsOperations, workers).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        verify(snsClient).listTopics();
        verify(snsOperations).topicExists(TOPIC_ARN);
        verify(snsClient, never()).createTopic(any(
                software.amazon.awssdk.services.sns.model.CreateTopicRequest.class));
    }

    @Test
    @DisplayName("reports a missing topic down and never creates it")
    void reportsMissingTopicDown() {
        when(snsClient.listTopics()).thenReturn(ListTopicsResponse.builder().build());

        final Health health =
                configuration.awsSnsHealthIndicator(snsClient, snsOperations, workers).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        verifyNoInteractions(snsOperations);
        verify(snsClient, never()).createTopic(any(
                software.amazon.awssdk.services.sns.model.CreateTopicRequest.class));
    }

    @Test
    @DisplayName("the shipped groups keep the blocking dependencies in readiness and out of liveness")
    void configuresReadinessWithoutChangingLiveness() throws IOException {
        final String shared = new ClassPathResource("application.yml").getContentAsString(
                StandardCharsets.UTF_8);

        assertThat(shared)
                .contains("liveness:\n          include: livenessState")
                .contains("readiness:\n"
                        + "          include: readinessState,db,awsS3,awsSqs");
    }

    @Test
    @DisplayName("the notification topic is published as a component but is NOT in the readiness "
            + "group, because its absence loses an operational notice rather than preventing work")
    void keepsTheOptionalTopicOutOfReadiness() throws IOException {
        final String shared = new ClassPathResource("application.yml").getContentAsString(
                StandardCharsets.UTF_8);
        final int readinessAt = shared.indexOf("readiness:\n          include:");
        final int endOfInclude = shared.indexOf('\n', shared.indexOf("include:", readinessAt));

        assertThat(shared.substring(readinessAt, endOfInclude))
                .as("a dependency described as best-effort by its own service must not be able to take "
                        + "the whole instance out of service")
                .doesNotContain(AwsResourceHealthConfig.COMPONENT_SNS);
        assertThat(shared)
                .as("the queue stays required: a request that cannot reach it never runs its job")
                .contains(AwsResourceHealthConfig.COMPONENT_SQS);
    }

    @Test
    @DisplayName("the probe's observation is carried onto the worker, so the provider call is not a "
            + "detached root")
    void theProbesObservationIsCarriedOntoTheWorker() {
        // The gap this closes: a plain executor hands a worker a thread with nothing current, so the
        // provider call the worker makes appears in a trace of its own with no link to the probe that
        // asked for it. The pool is untouched - its direct handoff and its rejecting handler are what keep
        // the deadline honest - and only the submitted work is wrapped.
        final AtomicReference<Observation> onTheWorker = new AtomicReference<>();
        when(s3Operations.bucketExists(BUCKET)).thenAnswer(invocation -> {
            onTheWorker.set(observationRegistry.getCurrentObservation());
            return Boolean.TRUE;
        });
        final Observation probe = Observation.createNotStarted("probe", observationRegistry).start();
        final Observation.Scope scope = probe.openScope();
        try {
            configuration.awsS3HealthIndicator(s3Operations, workers).health();
        } finally {
            scope.close();
            probe.stop();
        }

        assertThat(onTheWorker.get())
                .as("the worker ran inside the probe's own observation; a null here is exactly the "
                        + "detached-root state this closes")
                .isSameAs(probe);
    }

    @Test
    @DisplayName("a probe with no observation of its own still runs, because an unobserved caller is "
            + "an ordinary case")
    void anUnobservedProbeStillRuns() {
        when(s3Operations.bucketExists(BUCKET)).thenReturn(true);

        assertThat(configuration.awsS3HealthIndicator(s3Operations, workers).health().getStatus())
                .isEqualTo(Status.UP);
    }

    @Nested
    @DisplayName("Every check carries its own deadline, so no probe waits on a silent provider")
    class EveryCheckIsBounded {

        /** Long enough that an unbounded check would blow any probe budget, short enough to test. */
        private static final long FAR_BEYOND_THE_DEADLINE_MILLIS =
                AwsResourceHealthConfig.CHECK_DEADLINE_MILLIS * 8;

        @Test
        @DisplayName("the per-check deadline and the stated total budget agree, and the total leaves "
                + "room for the data source inside the container probe's five-second timeout")
        void theBudgetArithmeticHolds() {
            assertThat(AwsResourceHealthConfig.TOTAL_AWS_BUDGET_MILLIS)
                    .as("three checks at the per-check deadline")
                    .isEqualTo(3 * AwsResourceHealthConfig.CHECK_DEADLINE_MILLIS)
                    .as("the container HEALTHCHECK allows five seconds and the data-source "
                            + "contributor runs alongside these three")
                    .isLessThan(5_000L);
        }

        @Test
        @DisplayName("an object-store call that never answers is abandoned at the deadline rather "
                + "than holding the probe open")
        void aSilentObjectStoreIsAbandoned() {
            final CountDownLatch released = new CountDownLatch(1);
            when(s3Operations.bucketExists(BUCKET)).thenAnswer(invocation -> {
                released.await(FAR_BEYOND_THE_DEADLINE_MILLIS, TimeUnit.MILLISECONDS);
                return true;
            });

            final long startedAt = System.nanoTime();
            final Health health =
                    configuration.awsS3HealthIndicator(s3Operations, workers).health();
            final long elapsedMillis =
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            released.countDown();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails())
                    .containsEntry("reason", AwsResourceHealthConfig.DEADLINE_EXCEEDED);
            assertThat(elapsedMillis)
                    .as("the probe must be released at the deadline, not at the provider's leisure")
                    .isLessThan(FAR_BEYOND_THE_DEADLINE_MILLIS);
        }

        @Test
        @DisplayName("a topic call that never answers is abandoned at the deadline too")
        void aSilentTopicIsAbandoned() {
            final CountDownLatch released = new CountDownLatch(1);
            when(snsClient.listTopics()).thenAnswer(invocation -> {
                released.await(FAR_BEYOND_THE_DEADLINE_MILLIS, TimeUnit.MILLISECONDS);
                return ListTopicsResponse.builder().build();
            });

            final long startedAt = System.nanoTime();
            final Health health = configuration
                    .awsSnsHealthIndicator(snsClient, snsOperations, workers).health();
            final long elapsedMillis =
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            released.countDown();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails())
                    .containsEntry("reason", AwsResourceHealthConfig.DEADLINE_EXCEEDED);
            assertThat(elapsedMillis).isLessThan(FAR_BEYOND_THE_DEADLINE_MILLIS);
        }

        @Test
        @DisplayName("a queue resolution that never completes is abandoned at the deadline, which is "
                + "the defect an unbounded join produced")
        void aQueueResolutionThatNeverCompletesIsAbandoned() {
            when(sqsAsyncClient.getQueueUrl(any(GetQueueUrlRequest.class)))
                    .thenReturn(new CompletableFuture<>());

            final long startedAt = System.nanoTime();
            final Health health = configuration.awsSqsHealthIndicator(sqsAsyncClient).health();
            final long elapsedMillis =
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails())
                    .containsEntry("reason", AwsResourceHealthConfig.DEADLINE_EXCEEDED);
            assertThat(elapsedMillis)
                    .as("an unbounded join would never have returned at all")
                    .isLessThan(FAR_BEYOND_THE_DEADLINE_MILLIS);
        }

        @Test
        @DisplayName("a provider failure delivered inside the deadline keeps its own type in the "
                + "bounded chain rather than being reported as a timeout")
        void aFailureInsideTheDeadlineIsNotATimeout() {
            when(s3Operations.bucketExists(BUCKET))
                    .thenThrow(new IllegalStateException("provider path /secret must not escape"));

            final Health health =
                    configuration.awsS3HealthIndicator(s3Operations, workers).health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).doesNotContainKey("reason");
            assertThat(health.getDetails().get("failureChain").toString())
                    .contains("IllegalStateException")
                    .doesNotContain("provider path", "/secret");
        }

        @Test
        @DisplayName("the worker pool hands off directly and never queues, because time spent waiting "
                + "for a worker is time the deadline is already counting")
        void theWorkerPoolNeverQueues() {
            assertThat(workers).isInstanceOf(ThreadPoolExecutor.class);
            final ThreadPoolExecutor pool = (ThreadPoolExecutor) workers;

            assertThat(pool.getQueue().remainingCapacity())
                    .as("a direct-handoff queue has no capacity to wait in")
                    .isZero();
            assertThat(pool.getCorePoolSize()).isZero();
            assertThat(pool.getMaximumPoolSize())
                    .as("two slots for each of the two synchronous checks")
                    .isEqualTo(4);
        }

        @Test
        @DisplayName("the pool's threads are daemons, so a stuck provider call cannot keep the JVM "
                + "alive at shutdown")
        void theWorkerThreadsAreDaemons() throws Exception {
            final CompletableFuture<Boolean> observed = new CompletableFuture<>();
            workers.submit(() -> observed.complete(Thread.currentThread().isDaemon()));

            assertThat(observed.get(5, TimeUnit.SECONDS)).isTrue();
        }

        @Test
        @DisplayName("with no worker free the check is answered rather than queued, which is the same "
                + "bounded DOWN a deadline gives")
        void anExhaustedPoolIsAnsweredNotQueued() {
            final ExecutorService noCapacity = new ThreadPoolExecutor(0, 1, 1L,
                    TimeUnit.MILLISECONDS, new SynchronousQueue<>(),
                    (runnable, pool) -> {
                        throw new RejectedExecutionException("no worker");
                    });
            final CountDownLatch occupied = new CountDownLatch(1);
            final CountDownLatch released = new CountDownLatch(1);
            try {
                noCapacity.submit(() -> {
                    occupied.countDown();
                    released.await(FAR_BEYOND_THE_DEADLINE_MILLIS, TimeUnit.MILLISECONDS);
                    return null;
                });
                assertThat(occupied.await(5, TimeUnit.SECONDS)).isTrue();

                final Health health =
                        configuration.awsS3HealthIndicator(s3Operations, noCapacity).health();

                assertThat(health.getStatus()).isEqualTo(Status.DOWN);
                assertThat(health.getDetails()).containsEntry("reason",
                        AwsResourceHealthConfig.CHECK_CAPACITY_EXHAUSTED);
                verifyNoInteractions(s3Operations);
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            } finally {
                released.countDown();
                noCapacity.shutdownNow();
            }
        }

        @Test
        @DisplayName("each contributor requires its worker pool by name rather than dereferencing it")
        void eachContributorRequiresItsPool() {
            assertThatNullPointerException()
                    .isThrownBy(() -> configuration.awsS3HealthIndicator(s3Operations, null))
                    .withMessageContaining("workers");
            assertThatNullPointerException()
                    .isThrownBy(() -> configuration
                            .awsSnsHealthIndicator(snsClient, snsOperations, null))
                    .withMessageContaining("workers");
        }
    }

    @Nested
    @DisplayName("An absorbed failure is logged, because production renders no component detail")
    class AbsorbedFailuresAreLogged {

        private ListAppender<ILoggingEvent> recorded;
        private Logger contributorLogger;

        @BeforeEach
        void startRecording() {
            contributorLogger =
                    (Logger) LoggerFactory.getLogger(AwsResourceHealthConfig.class);
            recorded = new ListAppender<>();
            recorded.start();
            contributorLogger.addAppender(recorded);
        }

        @AfterEach
        void stopRecording() {
            contributorLogger.detachAppender(recorded);
            recorded.stop();
        }

        @Test
        @DisplayName("the move to DOWN is logged at warn, naming the component and the bounded cause")
        void theMoveToDownIsLogged() {
            when(s3Operations.bucketExists(BUCKET))
                    .thenThrow(new IllegalStateException("provider path /secret must not escape"));

            configuration.awsS3HealthIndicator(s3Operations, workers).health();

            assertThat(recorded.list).hasSize(1);
            final ILoggingEvent transition = recorded.list.getFirst();
            assertThat(transition.getLevel()).isEqualTo(Level.WARN);
            assertThat(transition.getFormattedMessage())
                    .contains(AwsResourceHealthConfig.COMPONENT_S3)
                    .contains("DOWN")
                    .contains("IllegalStateException");
        }

        @Test
        @DisplayName("a resource that is absent rather than failing is not silent either")
        void anAbsentResourceIsNotSilent() {
            when(s3Operations.bucketExists(BUCKET)).thenReturn(false);

            configuration.awsS3HealthIndicator(s3Operations, workers).health();

            assertThat(recorded.list).hasSize(1);
            assertThat(recorded.list.getFirst().getFormattedMessage())
                    .contains(AwsResourceHealthConfig.COMPONENT_S3)
                    .contains("resource-not-found");
        }

        @Test
        @DisplayName("only transitions are logged, so a probe every ten seconds cannot bury the "
                + "change that matters")
        void onlyTransitionsAreLogged() {
            when(s3Operations.bucketExists(BUCKET)).thenReturn(false, false, false, true, true);
            final HealthIndicator indicator =
                    configuration.awsS3HealthIndicator(s3Operations, workers, ticker);

            for (int evaluation = 0; evaluation < 5; evaluation++) {
                indicator.health();
                // One window per evaluation, so these are five evaluations of the bucket rather than one
                // evaluation repeated five times - which is what makes the count of transitions below a
                // statement about the resource's state changing.
                ticker.advanceBeyondTheWindow();
            }

            assertThat(recorded.list)
                    .as("three DOWN evaluations then two UP evaluations is two transitions")
                    .hasSize(2);
            assertThat(recorded.list.get(0).getLevel()).isEqualTo(Level.WARN);
            assertThat(recorded.list.get(1).getLevel()).isEqualTo(Level.INFO);
            assertThat(recorded.list.get(1).getFormattedMessage())
                    .contains(AwsResourceHealthConfig.COMPONENT_S3)
                    .contains("recovered");
        }

        @Test
        @DisplayName("a change of reason while still down is reported, because the resource moved from "
                + "absent to unreachable and an operator acts on those differently")
        void aChangeOfReasonWhileDownIsReported() {
            when(s3Operations.bucketExists(BUCKET))
                    .thenReturn(false)
                    .thenThrow(new IllegalStateException("unreachable"));
            final HealthIndicator indicator =
                    configuration.awsS3HealthIndicator(s3Operations, workers, ticker);

            indicator.health();
            ticker.advanceBeyondTheWindow();
            indicator.health();

            assertThat(recorded.list).hasSize(2);
            assertThat(recorded.list.get(1).getFormattedMessage())
                    .contains("resource-not-found")
                    .contains("IllegalStateException");
        }

        @Test
        @DisplayName("a healthy component logs nothing at all, so the channel carries only changes")
        void aHealthyComponentIsQuiet() {
            when(s3Operations.bucketExists(BUCKET)).thenReturn(true);
            final HealthIndicator indicator =
                    configuration.awsS3HealthIndicator(s3Operations, workers, ticker);

            indicator.health();
            ticker.advanceBeyondTheWindow();
            indicator.health();

            assertThat(recorded.list).isEmpty();
        }

        @Test
        @DisplayName("no line carries a credential, an endpoint, a configured resource name, a "
                + "provider message or a stack trace")
        void noLineCarriesAnythingSensitive() {
            when(s3Operations.bucketExists(BUCKET)).thenThrow(new IllegalStateException(
                    "connect https://s3.eu-west-2.amazonaws.com key=AKIAEXAMPLE denied"));

            configuration.awsS3HealthIndicator(s3Operations, workers).health();

            assertThat(recorded.list).hasSize(1);
            final ILoggingEvent transition = recorded.list.getFirst();
            assertThat(transition.getFormattedMessage())
                    .doesNotContain("https://", "amazonaws.com", "AKIAEXAMPLE", "denied")
                    .doesNotContain(BUCKET);
            assertThat(transition.getThrowableProxy())
                    .as("a stack trace would carry the provider message the detail withholds")
                    .isNull();
        }

        @Test
        @DisplayName("each contributor reports its own component, so one DOWN cannot be mistaken for "
                + "another resource's")
        void eachContributorReportsItsOwnComponent() {
            when(s3Operations.bucketExists(BUCKET)).thenReturn(false);
            when(snsClient.listTopics()).thenReturn(ListTopicsResponse.builder().build());

            configuration.awsS3HealthIndicator(s3Operations, workers).health();
            configuration.awsSnsHealthIndicator(snsClient, snsOperations, workers).health();

            assertThat(recorded.list).hasSize(2);
            assertThat(recorded.list.get(0).getFormattedMessage())
                    .contains(AwsResourceHealthConfig.COMPONENT_S3);
            assertThat(recorded.list.get(1).getFormattedMessage())
                    .contains(AwsResourceHealthConfig.COMPONENT_SNS);
        }
    }

    @Nested
    @DisplayName("Probe volume does not set provider call volume, because the callers include anonymous "
            + "ones")
    class ProbeVolumeIsNotProviderVolume {

        /** How many probes a burst is represented by; any number above one would show the defect. */
        private static final int PROBES_IN_A_BURST = 6;

        @Test
        @DisplayName("so repeated probes inside one window ask the object store exactly once, which is "
                + "the amplification an unauthenticated caller could otherwise drive")
        void repeatedProbesInsideOneWindowAskTheObjectStoreOnce() {
            when(s3Operations.bucketExists(BUCKET)).thenReturn(true);
            final HealthIndicator indicator =
                    configuration.awsS3HealthIndicator(s3Operations, workers, ticker);

            for (int probe = 0; probe < PROBES_IN_A_BURST; probe++) {
                assertThat(indicator.health().getStatus())
                        .as("every probe is answered, and answered the same way")
                        .isEqualTo(Status.UP);
            }

            verify(s3Operations, times(1)).bucketExists(BUCKET);
            verifyNoMoreInteractions(s3Operations);
        }

        @Test
        @DisplayName("and the resource is asked again once the window has closed, so readiness keeps "
                + "tracking the resource rather than a memory of it")
        void theResourceIsAskedAgainOnceTheWindowHasClosed() {
            when(s3Operations.bucketExists(BUCKET)).thenReturn(true, false);
            final HealthIndicator indicator =
                    configuration.awsS3HealthIndicator(s3Operations, workers, ticker);

            assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
            ticker.advanceBeyondTheWindow();
            assertThat(indicator.health().getStatus())
                    .as("a bucket that has gone away must be reported, and within one window of going")
                    .isEqualTo(Status.DOWN);

            verify(s3Operations, times(2)).bucketExists(BUCKET);
        }

        @Test
        @DisplayName("a DOWN answer is reused too, because an absent or unreachable resource is exactly "
                + "the state in which re-asking every probe hurts most")
        void aDownAnswerIsReusedToo() {
            when(s3Operations.bucketExists(BUCKET)).thenReturn(false);
            final HealthIndicator indicator =
                    configuration.awsS3HealthIndicator(s3Operations, workers, ticker);

            for (int probe = 0; probe < PROBES_IN_A_BURST; probe++) {
                final Health repeated = indicator.health();
                assertThat(repeated.getStatus()).isEqualTo(Status.DOWN);
                assertThat(repeated.getDetails()).containsEntry("reason", "resource-not-found");
            }

            verify(s3Operations, times(1)).bucketExists(BUCKET);
        }

        @Test
        @DisplayName("a failing resource is not re-asked per probe either, so a provider that refuses "
                + "cannot be asked to refuse once per request")
        void aFailingResourceIsNotReAskedPerProbe() {
            when(s3Operations.bucketExists(BUCKET))
                    .thenThrow(new IllegalStateException("provider refused"));
            final HealthIndicator indicator =
                    configuration.awsS3HealthIndicator(s3Operations, workers, ticker);

            for (int probe = 0; probe < PROBES_IN_A_BURST; probe++) {
                final Health repeated = indicator.health();
                assertThat(repeated.getStatus()).isEqualTo(Status.DOWN);
                assertThat(repeated.getDetails().get("failureChain").toString())
                        .contains("IllegalStateException")
                        .doesNotContain("provider refused");
            }

            verify(s3Operations, times(1)).bucketExists(BUCKET);
        }

        @Test
        @DisplayName("simultaneous probes that all find the window closed share one evaluation, which "
                + "reuse alone cannot do because every member of a burst misses the same window")
        void simultaneousProbesShareOneEvaluation() throws Exception {
            final CountDownLatch insideTheProvider = new CountDownLatch(1);
            final CountDownLatch releaseTheProvider = new CountDownLatch(1);
            final AtomicInteger providerCalls = new AtomicInteger();
            when(s3Operations.bucketExists(BUCKET)).thenAnswer(invocation -> {
                providerCalls.incrementAndGet();
                insideTheProvider.countDown();
                releaseTheProvider.await(FAR_LONGER_THAN_ANY_WAIT_SECONDS, TimeUnit.SECONDS);
                return Boolean.TRUE;
            });
            final HealthIndicator indicator =
                    configuration.awsS3HealthIndicator(s3Operations, workers, ticker);
            final ExecutorService probes = Executors.newFixedThreadPool(PROBES_IN_A_BURST);
            try {
                final List<Future<Status>> answers = new ArrayList<>();
                answers.add(probes.submit(() -> indicator.health().getStatus()));
                assertThat(insideTheProvider.await(FAR_LONGER_THAN_ANY_WAIT_SECONDS, TimeUnit.SECONDS))
                        .as("the first probe must be inside the provider call before the others arrive")
                        .isTrue();
                for (int probe = 1; probe < PROBES_IN_A_BURST; probe++) {
                    answers.add(probes.submit(() -> indicator.health().getStatus()));
                }
                releaseTheProvider.countDown();
                for (final Future<Status> answer : answers) {
                    assertThat(answer.get(FAR_LONGER_THAN_ANY_WAIT_SECONDS, TimeUnit.SECONDS))
                            .as("every probe is answered rather than refused for being concurrent")
                            .isNotNull();
                }

                assertThat(providerCalls.get())
                        .as("one evaluation for the whole burst; one per probe is the defect")
                        .isEqualTo(1);
            } finally {
                releaseTheProvider.countDown();
                probes.shutdownNow();
            }
        }

        @Test
        @DisplayName("the queue contributor reuses its answer too, so the resolver is not run per probe")
        void theQueueContributorReusesItsAnswer() {
            when(sqsAsyncClient.getQueueUrl(any(GetQueueUrlRequest.class)))
                    .thenReturn(CompletableFuture.completedFuture(
                            GetQueueUrlResponse.builder().queueUrl(QUEUE_URL).build()));
            when(sqsAsyncClient.getQueueAttributes(
                    org.mockito.ArgumentMatchers
                            .<Consumer<GetQueueAttributesRequest.Builder>>any()))
                    .thenReturn(CompletableFuture.completedFuture(
                            GetQueueAttributesResponse.builder()
                                    .attributes(Map.of(QueueAttributeName.QUEUE_ARN, QUEUE_ARN))
                                    .build()));
            final HealthIndicator indicator =
                    configuration.awsSqsHealthIndicator(sqsAsyncClient, ticker);

            for (int probe = 0; probe < PROBES_IN_A_BURST; probe++) {
                assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
            }

            verify(sqsAsyncClient, times(1)).getQueueUrl(any(GetQueueUrlRequest.class));
            verify(sqsAsyncClient, times(1)).getQueueAttributes(
                    org.mockito.ArgumentMatchers
                            .<Consumer<GetQueueAttributesRequest.Builder>>any());
            verifyNoMoreInteractions(sqsAsyncClient);
        }

        @Test
        @DisplayName("the topic contributor reuses its answer too, so a listing is not run per probe")
        void theTopicContributorReusesItsAnswer() {
            when(snsClient.listTopics()).thenReturn(ListTopicsResponse.builder()
                    .topics(Topic.builder().topicArn(TOPIC_ARN).build())
                    .build());
            when(snsOperations.topicExists(TOPIC_ARN)).thenReturn(true);
            final HealthIndicator indicator =
                    configuration.awsSnsHealthIndicator(snsClient, snsOperations, workers, ticker);

            for (int probe = 0; probe < PROBES_IN_A_BURST; probe++) {
                assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
            }

            verify(snsClient, times(1)).listTopics();
            verify(snsOperations, times(1)).topicExists(TOPIC_ARN);
        }

        @Test
        @DisplayName("every published contributor carries the window, so none of the three is left as "
                + "the one an anonymous caller can amplify")
        void everyPublishedContributorCarriesTheWindow() {
            when(s3Operations.bucketExists(BUCKET)).thenReturn(true);
            when(sqsAsyncClient.getQueueUrl(any(GetQueueUrlRequest.class)))
                    .thenReturn(CompletableFuture.completedFuture(
                            GetQueueUrlResponse.builder().queueUrl(QUEUE_URL).build()));
            when(sqsAsyncClient.getQueueAttributes(
                    org.mockito.ArgumentMatchers
                            .<Consumer<GetQueueAttributesRequest.Builder>>any()))
                    .thenReturn(CompletableFuture.completedFuture(
                            GetQueueAttributesResponse.builder()
                                    .attributes(Map.of(QueueAttributeName.QUEUE_ARN, QUEUE_ARN))
                                    .build()));
            when(snsClient.listTopics()).thenReturn(ListTopicsResponse.builder()
                    .topics(Topic.builder().topicArn(TOPIC_ARN).build())
                    .build());
            when(snsOperations.topicExists(TOPIC_ARN)).thenReturn(true);
            // The BEAN methods, not the clock-carrying ones: this is the composition the context
            // publishes, so it is the composition that must carry the window. Reading them twice in
            // immediate succession is inside any window a monotonic clock could report.
            final List<HealthIndicator> published = List.of(
                    configuration.awsS3HealthIndicator(s3Operations, workers),
                    configuration.awsSqsHealthIndicator(sqsAsyncClient),
                    configuration.awsSnsHealthIndicator(snsClient, snsOperations, workers));

            for (final HealthIndicator indicator : published) {
                assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
                assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
            }

            verify(s3Operations, times(1)).bucketExists(BUCKET);
            verify(sqsAsyncClient, times(1)).getQueueUrl(any(GetQueueUrlRequest.class));
            verify(snsClient, times(1)).listTopics();
        }

        @Test
        @DisplayName("the window outlasts one check deadline and is shorter than the probe interval the "
                + "container image actually asks at")
        void theWindowSitsBetweenTheDeadlineAndTheProbeInterval() throws IOException {
            assertThat(AwsResourceHealthConfig.RESULT_FRESHNESS_MILLIS)
                    .as("a window shorter than a deadline could close before the evaluation that "
                            + "opened it was even allowed to finish, which would reuse nothing")
                    .isGreaterThan(AwsResourceHealthConfig.CHECK_DEADLINE_MILLIS);

            final String dockerfile = Files.readString(Path.of("Dockerfile"), StandardCharsets.UTF_8);
            assertThat(dockerfile)
                    .as("the interval the claim below is measured against is read from the image rather "
                            + "than assumed, so a changed probe cadence fails here")
                    .contains("--interval=" + CONTAINER_PROBE_INTERVAL_SECONDS + "s");
            assertThat(AwsResourceHealthConfig.RESULT_FRESHNESS_MILLIS)
                    .as("shorter than the documented probe interval, so a probe arriving on that cadence "
                            + "still causes a fresh evaluation")
                    .isLessThan(TimeUnit.SECONDS.toMillis(CONTAINER_PROBE_INTERVAL_SECONDS));
        }

        @Test
        @DisplayName("and the endpoint's own cache is set to the same window, so the two bounds cannot "
                + "drift into disagreeing about how stale an answer may be")
        void theEndpointCacheIsSetToTheSameWindow() throws IOException {
            final String shared = new ClassPathResource("application.yml").getContentAsString(
                    StandardCharsets.UTF_8);

            assertThat(shared)
                    .as("the aggregate answer is the one an anonymous caller reaches most cheaply, so it "
                            + "carries a window of its own as well")
                    .contains("cache:\n        time-to-live: "
                            + TimeUnit.MILLISECONDS.toSeconds(
                                    AwsResourceHealthConfig.RESULT_FRESHNESS_MILLIS)
                            + "s");
        }
    }

    /**
     * An elapsed-nanosecond source this specification moves rather than waits out.
     *
     * <p>The contributors read their freshness window through a supplied clock precisely so that an
     * expiry can be asserted at the instant it happens. Sleeping past a two-second window instead would
     * add two seconds to every specification that needs a second evaluation and would still only
     * approximate the boundary.
     *
     * <p>It starts at zero, which is a legitimate reading for a monotonic source: the contributors
     * compare readings by difference and never against an absolute origin.
     */
    private static final class MovableTicker implements LongSupplier {

        /** The reading this ticker reports. */
        private final AtomicLong reading = new AtomicLong();

        @Override
        public long getAsLong() {
            return this.reading.get();
        }

        /**
         * Moves past the freshness window, so the next probe must ask its resource again.
         */
        void advanceBeyondTheWindow() {
            this.reading.addAndGet(TimeUnit.MILLISECONDS.toNanos(
                    AwsResourceHealthConfig.RESULT_FRESHNESS_MILLIS) + 1L);
        }
    }

    private static AwsProperties settings() {
        return settings(QUEUE);
    }

    private static AwsProperties settings(final String queueName) {
        return new AwsProperties(
                "us-east-1",
                null,
                new AwsProperties.S3(BUCKET),
                new AwsProperties.Sqs(queueName, "carddemo-job-submission"),
                new AwsProperties.Sns(TOPIC));
    }
}
