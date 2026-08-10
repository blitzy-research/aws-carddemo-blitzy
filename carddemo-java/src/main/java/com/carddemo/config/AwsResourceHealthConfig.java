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

import com.carddemo.util.FailureDiagnostics;
import com.carddemo.util.ObservationPropagation;

import io.micrometer.observation.ObservationRegistry;
import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.sns.core.SnsOperations;
import io.awspring.cloud.sns.core.TopicArnResolver;
import io.awspring.cloud.sns.core.TopicsListingTopicArnResolver;
import io.awspring.cloud.sqs.QueueAttributesResolver;
import io.awspring.cloud.sqs.listener.QueueNotFoundStrategy;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Non-creating readiness checks for the three AWS resources the application requires.
 *
 * <p>The LocalStack bootstrap and the deployment own resource creation. A health probe must never
 * repair a missing resource, because doing so would turn a configuration error into an apparently
 * healthy application pointed at an empty bucket, queue or topic. Each contributor therefore performs
 * a read-only existence operation:
 *
 * <ul>
 *   <li>the object-store check asks whether the configured staging bucket exists;</li>
 *   <li>the queue check resolves attributes with {@link QueueNotFoundStrategy#FAIL}, which explicitly
 *       forbids the resolver's create path; and</li>
 *   <li>the notification check resolves a topic name by listing topics, then verifies the resolved
 *       ARN with the notification operations facade.</li>
 * </ul>
 *
 * <p>The contributors are included in the Actuator readiness group by {@code application.yml}; the
 * liveness group contains only the process liveness state. That distinction is deliberate. The legacy
 * queue write is ignore-on-error, so one report request must still return its parity response when a
 * publish fails. Readiness nevertheless becomes down while the destination is absent, preventing new
 * work from being routed to an instance that cannot complete the AWS boundary. Liveness remains up
 * because restarting a healthy process cannot provision an external resource. Decision-log entry
 * DL-155 records that separation.
 *
 * <p>Failures are reduced to a bounded class-name chain. No endpoint, credential, configured resource
 * name, provider message or stack trace is copied into health details. Production withholds component
 * details entirely; the bounded value exists so a local operator can distinguish an absent resource
 * from a client failure without exposing provider diagnostics.
 *
 * <h2>Every check carries its own deadline</h2>
 *
 * <p>An existence operation is a network call, and a network call with no deadline is unbounded. The
 * three operations below are invoked by Actuator <em>sequentially</em> while a probe waits, so without a
 * deadline the readiness endpoint's response time is the sum of three unbounded waits: a provider that
 * accepts a connection and then never answers holds the endpoint open until the client gives up, the
 * next scheduled probe arrives while the previous one is still in flight, and the probes accumulate.
 * The queue check was the clearest instance - {@code resolveQueueAttributes().join()} waits forever by
 * construction - but all three shared the defect.
 *
 * <p>Each check is therefore given {@value #CHECK_DEADLINE_MILLIS} ms of its own, and the arithmetic is
 * the point rather than the number: three checks at that deadline is
 * {@value #TOTAL_AWS_BUDGET_MILLIS} ms, which leaves room for the data-source contributor inside the
 * container probe's five-second timeout even when every AWS check times out at once. A check that
 * exceeds its deadline reports DOWN with a bounded reason rather than making the caller wait, which is
 * the correct answer: a resource that cannot answer in that time cannot serve a request either.
 *
 * <h2>Absorbed failures are logged, not only detailed</h2>
 *
 * <p>Reducing a failure to a bounded detail is right for the response body and insufficient on its own.
 * Production sets both {@code show-details} and {@code show-components} to {@code never}, so the bounded
 * value is never rendered to anybody and an absorbed provider failure previously left no trace at all -
 * the probe simply reported DOWN with no way to learn which resource, or why. Each contributor therefore
 * logs its own <em>state transitions</em>: the move to DOWN at warn with the bounded cause, and the
 * recovery to UP at info. Transitions rather than every evaluation, because a probe runs every ten
 * seconds and logging each one would bury the change that matters.
 *
 * <p>What those lines may contain is deliberately narrow, and it is the same rule the health details
 * follow: a component name authored in this file, the bounded exception-type chain, and nothing else. No
 * endpoint, no credential, no configured bucket, queue or topic name, no provider message and no stack
 * trace. A resource name is withheld because it is the operator's own inventory and appears in the
 * deployment's configuration rather than needing to be echoed into a log an aggregator may forward.
 *
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
public final class AwsResourceHealthConfig {

    /**
     * Deadline applied to one read-only existence operation.
     *
     * <p>Chosen from the probe budget rather than from a latency expectation. The container health check
     * allows five seconds; the three checks here run sequentially alongside the data-source contributor,
     * so each is given a deadline that keeps their worst-case sum comfortably inside it. It is not a
     * service level and asserts nothing about how fast a provider ought to be - it is the point at which
     * waiting longer stops being useful, because a resource that has not answered by then cannot serve a
     * request either.
     */
    static final long CHECK_DEADLINE_MILLIS = 1_200L;

    /** Worst case for all three checks together, stated so the probe budget can be checked by reading. */
    static final long TOTAL_AWS_BUDGET_MILLIS = 3 * CHECK_DEADLINE_MILLIS;

    /**
     * The bean name of the bounded pool the two synchronous probes share.
     *
     * <p>Both probes name the pool rather than resolving it by type. An {@code ExecutorService}
     * parameter resolved by type is satisfied by whichever executor bean the context happens to hold,
     * so a second one appearing anywhere - a task executor added for an unrelated feature, an
     * auto-configured one arriving with a starter - would either make both injections ambiguous or,
     * worse, quietly give the readiness probes somebody else's threads and somebody else's queue. The
     * deadline these probes depend on is a property of this pool's direct-handoff queue, not of any
     * pool, so the pool is addressed by name.
     */
    static final String HEALTH_CHECK_EXECUTOR_BEAN = "awsHealthCheckExecutor";

    /** Component name of the object-store contributor, used only for its own diagnostics. */
    static final String COMPONENT_S3 = "awsS3";

    /** Component name of the queue contributor, used only for its own diagnostics. */
    static final String COMPONENT_SQS = "awsSqs";

    /** Component name of the notification contributor, used only for its own diagnostics. */
    static final String COMPONENT_SNS = "awsSns";

    /** Detail value used when an existence operation completed and reported absence. */
    private static final String RESOURCE_NOT_FOUND = "resource-not-found";

    /** Detail value used when an existence operation did not answer inside its own deadline. */
    static final String DEADLINE_EXCEEDED = "deadline-exceeded";

    /**
     * Diagnostic channel for readiness state transitions.
     *
     * <p>One logger for the whole configuration rather than one per contributor, because the component
     * name is carried on every line and a per-contributor category would only let a deployment silence
     * one resource's transitions while believing it had silenced none.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(AwsResourceHealthConfig.class);

    /**
     * Worker slots for the two checks whose operation is synchronous.
     *
     * <p>Two per such check. A check that expires does not free its worker: the deadline returns control
     * to the probe, while the abandoned worker stays inside the provider call until the SDK's own
     * transport timeout releases it. One slot each would therefore mean the next probe of an already
     * stuck resource had nowhere to run; two means it does, and because a stuck slot is released by the
     * transport rather than held forever the pair does not grow without bound. Exhaustion beyond that is
     * reported as DOWN rather than waited on, which is the same answer the deadline gives.
     */
    private static final int WORKER_SLOTS_PER_SYNCHRONOUS_CHECK = 2;

    /** Number of contributors whose existence operation is synchronous: the object store and the topic. */
    private static final int SYNCHRONOUS_CHECKS = 2;

    /** Detail value used when no worker slot was free, which is answered rather than queued. */
    static final String CHECK_CAPACITY_EXHAUSTED = "check-capacity-exhausted";

    /** Bound resource names and endpoint settings. */
    private final AwsProperties awsProperties;

    /**
     * The registry a probe's own observation is read from so a worker can continue it.
     *
     * <p>Held only to be handed to {@link ObservationPropagation}. Nothing on this class starts, names or
     * stops an observation; the provider calls are observed by the clients that make them, and the point
     * of carrying the context is that those observations attach to the probe that caused them instead of
     * standing alone as one-span traces.
     */
    private final ObservationRegistry observationRegistry;

    /**
     * Creates the readiness configuration over the already validated AWS settings.
     *
     * @param awsProperties the bound AWS resource inventory; must not be {@code null}
     * @param observationRegistry the registry a probe's observation is carried from; must not be
     *                            {@code null}
     */
    public AwsResourceHealthConfig(final AwsProperties awsProperties,
            final ObservationRegistry observationRegistry) {
        this.awsProperties = Objects.requireNonNull(awsProperties,
                "awsProperties must not be null");
        this.observationRegistry = Objects.requireNonNull(observationRegistry,
                "observationRegistry must not be null");
    }

    /**
     * Publishes the worker pool the two synchronous existence checks are bounded on.
     *
     * <h2>Why a pool exists here at all</h2>
     *
     * <p>Two of the three operations are synchronous facade calls, and a synchronous call cannot be
     * abandoned by the thread making it - {@code bucketExists} and {@code topicExists} return when the
     * provider answers or when the transport gives up, and neither instant is under this class's control.
     * The queue check needs no pool because its operation is already a future, so it is bounded by
     * awaiting that future with a deadline and consumes no thread of ours at all. Two mechanisms, each
     * chosen for the shape of the call it bounds, rather than one mechanism forced onto both.
     *
     * <p>The queue is a {@link SynchronousQueue} deliberately: a queue with capacity would let a
     * submission wait for a worker, and time spent waiting for a worker is time the deadline is already
     * meant to be counting. Handing off directly or not at all keeps the deadline honest.
     *
     * <p>The threads are daemons so a stuck provider call cannot keep the JVM alive at shutdown, and the
     * pool is destroyed with the context by {@code shutdownNow} so the same call cannot outlive it.
     *
     * @return a bounded, direct-handoff pool sized from the number of synchronous checks
     */
    @Bean(name = HEALTH_CHECK_EXECUTOR_BEAN, destroyMethod = "shutdownNow")
    public ExecutorService awsHealthCheckExecutor() {
        final AtomicInteger sequence = new AtomicInteger();
        final ThreadPoolExecutor executor = new ThreadPoolExecutor(
                0,
                SYNCHRONOUS_CHECKS * WORKER_SLOTS_PER_SYNCHRONOUS_CHECK,
                CHECK_DEADLINE_MILLIS,
                TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(),
                runnable -> {
                    final Thread worker =
                            new Thread(runnable, "aws-health-" + sequence.incrementAndGet());
                    worker.setDaemon(true);
                    return worker;
                });
        // A submission with no free worker is answered, not queued: the caller is a probe with a
        // deadline, and the honest answer to "no capacity to check" is the same DOWN a deadline gives.
        executor.setRejectedExecutionHandler((runnable, rejectingPool) -> {
            throw new RejectedExecutionException("no AWS readiness check worker was available");
        });
        return executor;
    }

    /**
     * Contributes the {@code awsS3} readiness component.
     *
     * @param s3Operations the auto-configured object-store facade
     * @param workers      the bounded pool this synchronous check is given a deadline on, named rather
     *     than resolved by type so no other executor bean can become the pool these probes run on
     * @return a read-only, deadline-bounded bucket existence check
     */
    @Bean
    public HealthIndicator awsS3HealthIndicator(final S3Operations s3Operations,
            @Qualifier(HEALTH_CHECK_EXECUTOR_BEAN) final ExecutorService workers) {
        Objects.requireNonNull(s3Operations, "s3Operations must not be null");
        Objects.requireNonNull(workers, "workers must not be null");
        final String bucket = this.awsProperties.s3().batchStagingBucket();
        return existenceIndicator(COMPONENT_S3,
                boundedByDeadline(workers, this.observationRegistry,
                        () -> s3Operations.bucketExists(bucket)));
    }

    /**
     * Contributes the {@code awsSqs} readiness component.
     *
     * <p>{@link QueueAttributesResolver} accepts the same three destination forms as the publisher:
     * queue name, queue URL and queue ARN. Resolving with {@link QueueNotFoundStrategy#FAIL} is the
     * load-bearing part of this method: the alternate strategy creates a queue and is prohibited for a
     * health check.
     *
     * <p>The resolution is awaited with an explicit deadline rather than with {@code join()}. That
     * distinction is the whole of this method's contribution to bounding the probe: {@code join()} waits
     * for as long as the provider takes, so a queue endpoint that accepts a connection and then stops
     * answering held the readiness endpoint open indefinitely. {@link CompletableFuture#get(long,
     * java.util.concurrent.TimeUnit)} returns at the deadline instead, and the wrapper below converts the
     * expiry into a bounded DOWN.
     *
     * @param sqsAsyncClient the auto-configured queue client
     * @return a read-only, deadline-bounded queue resolution check
     */
    @Bean
    public HealthIndicator awsSqsHealthIndicator(final SqsAsyncClient sqsAsyncClient) {
        Objects.requireNonNull(sqsAsyncClient, "sqsAsyncClient must not be null");
        final QueueAttributesResolver resolver = QueueAttributesResolver.builder()
                .queueName(this.awsProperties.sqs().jobQueue())
                .sqsAsyncClient(sqsAsyncClient)
                .queueAttributeNames(List.of(QueueAttributeName.QUEUE_ARN))
                .queueNotFoundStrategy(QueueNotFoundStrategy.FAIL)
                .build();
        return existenceIndicator(COMPONENT_SQS,
                () -> awaitWithinDeadline(resolver.resolveQueueAttributes()));
    }

    /**
     * Contributes the {@code awsSns} health component, which is published but deliberately sits OUTSIDE
     * the readiness group.
     *
     * <p>Every other contributor on this class is a readiness contributor; this one is not, and that is
     * the point rather than an omission. Readiness membership means "an instance without this cannot
     * serve", and the topic carries an operational notice that a batch job has <em>already</em> finished -
     * so its absence loses a notice and prevents no work. Publishing it without grouping it is what lets a
     * deployment read the topic's state whenever it wants while that state does not decide whether this
     * instance receives traffic. {@code application.yml}'s health-group block records the reasoning, and
     * the contrast that settles it: the queue stays required, because a request that cannot reach the
     * queue never runs its job.
     *
     * <p><strong>This exclusion is a runtime judgement and not a statement that the topic is dispensable,
     * and the two must not be confused.</strong> A production start-up will not begin without it:
     * {@link AwsResourceTrustVerifier} resolves the configured topic from a listing, requires the resolved
     * locator to be owned by the declared account, requires the topic to answer an attribute read, and
     * <em>aborts the start-up</em> if any of the three fails - and the local stack's emulator does not
     * report healthy until the topic exists, with the application waiting on that health. So the split is
     * between <em>provisioning and ownership</em>, which are mandatory and are settled once before any
     * traffic arrives, and <em>delivery of an individual notice</em>, which is best effort and is what
     * this contributor reports on afterwards. An instance is never made unfit by the second. Recorded as
     * {@code DL-339}.
     *
     * <p>The default topic resolver creates a topic when given a name, so it is intentionally not used
     * here. {@link TopicsListingTopicArnResolver} lists and matches instead. An already configured ARN
     * is still verified through {@link SnsOperations#topicExists(String)} rather than accepted on
     * syntax alone.
     *
     * <p>Both provider calls are made inside one deadline rather than one deadline each, because the pair
     * is one logical existence question and a caller waiting on the component is waiting on the answer to
     * that question, not on either half of it.
     *
     * @param snsClient the auto-configured notification client used only for topic listing
     * @param snsOperations the auto-configured notification facade used for the attribute check
     * @param workers   the bounded pool this synchronous check is given a deadline on, named rather
     *     than resolved by type for the reason given on {@link #HEALTH_CHECK_EXECUTOR_BEAN}
     * @return a read-only, deadline-bounded topic existence check
     */
    @Bean
    public HealthIndicator awsSnsHealthIndicator(final SnsClient snsClient,
            final SnsOperations snsOperations,
            @Qualifier(HEALTH_CHECK_EXECUTOR_BEAN) final ExecutorService workers) {
        Objects.requireNonNull(snsClient, "snsClient must not be null");
        Objects.requireNonNull(snsOperations, "snsOperations must not be null");
        Objects.requireNonNull(workers, "workers must not be null");
        final TopicArnResolver resolver = new TopicsListingTopicArnResolver(snsClient);
        final String topic = this.awsProperties.sns().jobNotificationTopic();
        return existenceIndicator(COMPONENT_SNS,
                boundedByDeadline(workers, this.observationRegistry, () -> {
                    final String topicArn = resolver.resolveTopicArn(topic).toString();
                    return snsOperations.topicExists(topicArn);
                }));
    }

    /**
     * Wraps one synchronous existence operation so the calling probe never waits past a single deadline.
     *
     * <p>The operation still runs to whatever length the provider and the transport take - nothing here
     * can shorten a call already in flight - but it runs on a worker rather than on the probe's own
     * thread, so the probe is released at the deadline while the worker is left to the transport timeout.
     * That is the whole of the distinction: the caller becomes bounded even though the call does not.
     *
     * @param workers the bounded pool to run the operation on
     * @param observationRegistry the registry the probe's own observation is carried from, so the
     *                            provider call the worker makes attaches to the probe instead of standing
     *                            alone as a one-span trace
     * @param exists  the synchronous operation that reports whether its resource exists
     * @return a supplier that either answers inside the deadline or reports why it could not
     */
    private static BooleanSupplier boundedByDeadline(final ExecutorService workers,
            final ObservationRegistry observationRegistry, final BooleanSupplier exists) {
        return () -> {
            final Future<Boolean> answer;
            try {
                // The work is wrapped so it runs inside the probe's own observation. A plain executor
                // hands a worker a thread with nothing current, so the provider call the worker makes was
                // becoming a detached root - a one-span trace with no link to the probe that asked for it.
                // The pool itself is untouched: its direct handoff, its ceiling and its rejecting handler
                // are what keep the deadline honest and none of them is a tracing concern. See
                // docs/decision-log.md entry DL-305.
                answer = workers.submit(ObservationPropagation.callInCurrentObservation(
                        observationRegistry, exists::getAsBoolean));
            } catch (final RejectedExecutionException noCapacity) {
                throw new HealthCheckCapacityExhaustedException(noCapacity);
            }
            try {
                return Boolean.TRUE.equals(answer.get(CHECK_DEADLINE_MILLIS, TimeUnit.MILLISECONDS));
            } catch (final TimeoutException expired) {
                answer.cancel(true);
                throw new HealthCheckDeadlineExceededException(expired);
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                answer.cancel(true);
                throw new HealthCheckDeadlineExceededException(interrupted);
            } catch (final ExecutionException failed) {
                throw unwrapped(failed);
            }
        };
    }

    /**
     * Turns one read-only existence probe into a bounded Actuator health result that reports its own
     * state transitions.
     *
     * <p>Three outcomes, and each is bounded. The resource exists, so the component is UP. The operation
     * completed and reported absence, so the component is DOWN with {@value #RESOURCE_NOT_FOUND} - which
     * is a configuration answer rather than a fault and is logged as such. Or the operation failed or
     * expired, so the component is DOWN carrying only the bounded exception-type chain.
     *
     * <p>The remembered state is held in an {@link AtomicReference} because Actuator may evaluate a
     * contributor from more than one thread - a scheduled probe and an operator request can overlap - and
     * {@code getAndSet} makes "did this evaluation change the state" a single atomic question. Without
     * that, two concurrent evaluations that both observe a failure could both decide they were the
     * transition and log it twice, or each could overwrite the other's write and log it never.
     *
     * @param component the authored component name, carried on this contributor's own diagnostics
     * @param exists    the operation that reports whether its resource exists
     * @return a health contributor that never throws provider failures through the endpoint, never waits
     *         longer than its deadline, and logs each change of state
     */
    private static HealthIndicator existenceIndicator(final String component,
            final BooleanSupplier exists) {
        Objects.requireNonNull(component, "component must not be null");
        Objects.requireNonNull(exists, "exists must not be null");
        final AtomicReference<String> lastReportedReason = new AtomicReference<>(null);
        return () -> {
            try {
                if (exists.getAsBoolean()) {
                    recordTransition(component, lastReportedReason, null);
                    return Health.up().build();
                }
                recordTransition(component, lastReportedReason, RESOURCE_NOT_FOUND);
                return Health.down()
                        .withDetail("reason", RESOURCE_NOT_FOUND)
                        .build();
            } catch (final HealthCheckDeadlineExceededException expired) {
                recordTransition(component, lastReportedReason, DEADLINE_EXCEEDED);
                return Health.down()
                        .withDetail("reason", DEADLINE_EXCEEDED)
                        .build();
            } catch (final HealthCheckCapacityExhaustedException noCapacity) {
                recordTransition(component, lastReportedReason, CHECK_CAPACITY_EXHAUSTED);
                return Health.down()
                        .withDetail("reason", CHECK_CAPACITY_EXHAUSTED)
                        .build();
            } catch (final RuntimeException healthFailure) {
                final String failureChain = FailureDiagnostics.failureChainOf(healthFailure);
                recordTransition(component, lastReportedReason, failureChain);
                return Health.down()
                        .withDetail("failureChain", failureChain)
                        .build();
            }
        };
    }

    /**
     * Logs a change of readiness state for one component, and says nothing when nothing changed.
     *
     * <p>The permitted vocabulary of a line from here is closed: the component name authored on this
     * class, and a reason that is either one of this class's own two tokens or the bounded
     * exception-type chain {@link FailureDiagnostics} produces. A provider message, a stack trace, an
     * endpoint, a credential and the configured bucket, queue or topic name are all excluded by
     * construction rather than by filtering, because none of them is ever passed in.
     *
     * @param component          authored component name
     * @param lastReportedReason the component's remembered reason, {@code null} while it is up
     * @param reason             the new reason, or {@code null} for up
     */
    private static void recordTransition(final String component,
            final AtomicReference<String> lastReportedReason, final String reason) {
        final String previous = lastReportedReason.getAndSet(reason);
        if (Objects.equals(previous, reason)) {
            return;
        }
        if (reason == null) {
            LOGGER.info("AWS readiness component {} recovered to UP", component);
        } else if (previous == null) {
            LOGGER.warn("AWS readiness component {} went DOWN; reason={}", component, reason);
        } else {
            LOGGER.warn("AWS readiness component {} is still DOWN for a new reason; previousReason={}"
                    + " reason={}", component, previous, reason);
        }
    }

    /**
     * Awaits one asynchronous existence operation for no longer than a single check's deadline.
     *
     * <p>The interrupted case restores the interrupt before reporting, because swallowing it would leave
     * a thread that has been asked to stop believing it has not been.
     *
     * @param resolution the operation to await
     * @return {@code true} when the operation completed inside its deadline
     * @throws HealthCheckDeadlineExceededException when the deadline expires or the wait is interrupted
     */
    private static boolean awaitWithinDeadline(final CompletableFuture<?> resolution) {
        try {
            resolution.get(CHECK_DEADLINE_MILLIS, TimeUnit.MILLISECONDS);
            return true;
        } catch (final TimeoutException expired) {
            resolution.cancel(true);
            throw new HealthCheckDeadlineExceededException(expired);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            resolution.cancel(true);
            throw new HealthCheckDeadlineExceededException(interrupted);
        } catch (final ExecutionException failed) {
            throw unwrapped(failed);
        }
    }

    /**
     * Restores a provider failure that completed inside its deadline to its own type.
     *
     * <p>A failure delivered through a future arrives wrapped, and reporting the wrapper would make every
     * provider refusal read as the same framework type in the bounded chain. Unwrapping keeps the chain
     * describing what actually refused. A checked cause - which the operations here do not produce, but
     * which the signature permits - is carried rather than discarded.
     *
     * @param failed the completion failure
     * @return the runtime failure to propagate
     */
    private static RuntimeException unwrapped(final ExecutionException failed) {
        final Throwable cause = failed.getCause();
        if (cause instanceof RuntimeException runtimeCause) {
            return runtimeCause;
        }
        return new IllegalStateException(cause == null ? failed : cause);
    }

    /**
     * Marks an existence operation that did not answer inside its own deadline.
     *
     * <p>Distinct from a provider failure so the contributor can report {@value #DEADLINE_EXCEEDED}
     * rather than a type chain that would name a timeout class and read as though the provider had
     * refused. Private to this class because nothing outside it can raise or handle the condition.
     */
    private static final class HealthCheckDeadlineExceededException extends RuntimeException {

        /** Serialization identity; this type is never serialized but the compiler asks for it. */
        private static final long serialVersionUID = 1L;

        /**
         * @param cause the wait outcome that ended the check
         */
        HealthCheckDeadlineExceededException(final Throwable cause) {
            super("an AWS readiness check exceeded its " + CHECK_DEADLINE_MILLIS
                    + " ms deadline", cause);
        }
    }

    /**
     * Marks an existence operation that could not be started because every worker slot was occupied.
     *
     * <p>Distinct from a deadline expiry so a deployment can tell "the resource did not answer" from
     * "every previous check of it is still stuck", which are the same DOWN but different operational
     * situations. Private for the same reason as its sibling: nothing outside this class can raise it.
     */
    private static final class HealthCheckCapacityExhaustedException extends RuntimeException {

        /** Serialization identity; this type is never serialized but the compiler asks for it. */
        private static final long serialVersionUID = 1L;

        /**
         * @param cause the rejection that ended the check
         */
        HealthCheckCapacityExhaustedException(final Throwable cause) {
            super("no AWS readiness check worker was available inside the probe budget", cause);
        }
    }
}
