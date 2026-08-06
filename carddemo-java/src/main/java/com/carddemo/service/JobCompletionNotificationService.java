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

import com.carddemo.util.FailureDiagnostics;
import io.awspring.cloud.sns.core.SnsNotification;
import io.awspring.cloud.sns.core.SnsOperations;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Service;

/**
 * Publishes a bounded, versioned and parameter-free notification for every completed batch job.
 *
 * <p>SNS has no legacy resource to reproduce; it is the operational fan-out required by the
 * migration plan. The payload therefore states only what an operator needs to correlate an outcome
 * with the Spring Batch repository: job name, framework identifiers, terminal status and exit code,
 * step count, and the framework's start/end timestamps. It never includes job parameters, execution
 * context, exit description, exception text or a stack trace.
 *
 * <p>Notification is best-effort. A topic refusal is observed and logged with a bounded failure chain
 * and then absorbed, so an optional operational channel cannot turn a completed job into a failed one.
 *
 * <h2>Best-effort has to mean not blocking, not merely not failing</h2>
 *
 * <p>Absorbing the failure was only half of the contract, and the missing half made the other half
 * misleading. The event boundary Spring publishes on is synchronous, so this listener used to run
 * <em>inside</em> the batch job-boundary listener's {@code afterJob} callback, and the outbound publish
 * ran there with it. A topic that accepted a connection and then stopped answering therefore held job
 * finalization open for as long as the transport allowed - the verdict was already decided and the
 * durable artifacts were already published, and the job still could not finish. The errors were
 * swallowed exactly as documented; what was not documented is that the job waited for them to be
 * swallowed. A dependency described as optional cannot delay the thing it is optional to.
 *
 * <p>{@link #onApplicationEvent(JobCompletionEvent)} therefore hands the snapshot to a bounded worker
 * and returns, so {@code afterJob} is released immediately. {@link
 * #publishCompletion(JobCompletionEvent)} remains synchronous and keeps returning its outcome, because
 * a caller that invokes it directly has asked for the publish rather than for the fan-out. Two entry
 * points, one for each caller, rather than one entry point that serves neither well.
 *
 * <p>The handoff is bounded in both dimensions that can grow. One worker, so a slow topic cannot
 * multiply into a thread per completed job; and a bounded queue, so a topic that is slow for longer than
 * the batch tier takes to produce work sheds the oldest pending notification with a warning instead of
 * accumulating snapshots until memory runs out. Shedding is the correct behaviour for this channel and
 * the wrong behaviour for the queue bridge, which is why only this one does it: the job-submission queue
 * carries work that must happen, while this carries an operational notice that something already has.
 *
 * <h2>The dependency is optional on every surface, not only on this one</h2>
 *
 * <p>A dependency cannot be optional here and required elsewhere, and it was: this class documented
 * best effort while {@code application.yml} listed the topic contributor in the <em>required</em>
 * readiness group, so an absent topic took the whole instance out of service - which is the treatment
 * given to a dependency whose absence prevents work, and the topic's absence prevents none. The
 * contributor is still published, so a deployment can see the topic's state at any time; it no longer
 * decides whether this instance may receive traffic. The contrast that settles it is the
 * job-submission queue, which stays in the group: a report request that cannot reach the queue never
 * runs its job, so an instance that cannot reach the queue genuinely cannot serve.
 *
 * <p><strong>The batch verdict is never a function of this channel.</strong> A notification that is
 * dropped, shed, refused or times out leaves the job's status and exit code exactly as the framework
 * recorded them. That is the whole contract, stated here because it is the property a reader needs and
 * the one an implementation could quietly break.
 */
@Service
public final class JobCompletionNotificationService
        implements ApplicationListener<JobCompletionEvent>, AutoCloseable {

    public static final String TOPIC_PROPERTY =
            "carddemo.aws.sns.job-notification-topic";

    public static final int SCHEMA_VERSION = 1;

    public static final String EVENT_TYPE = "carddemo.batch.job-completion";

    public static final String OBSERVATION_NAME = "carddemo.job.completion.publish";

    public static final String TAG_SYSTEM = "system";

    public static final String TAG_OPERATION = "operation";

    public static final String TAG_EVENT_TYPE = "eventType";

    public static final String TAG_TOPIC = "topic";

    public static final String TAG_JOB = "job";

    public static final String TAG_EXECUTION = "jobExecutionId";

    public static final String SYSTEM_SNS = "sns";

    public static final String OPERATION_PUBLISH = "publish";

    public static final String SUBJECT = "CardDemo batch job completion";

    public static final int MAX_PAYLOAD_BYTES = 512;

    private static final int MAX_TOPIC_LENGTH = 512;

    private static final int MAX_JOB_NAME_LENGTH = 100;

    private static final char MIN_PRINTABLE_US_ASCII = 0x20;

    private static final char MAX_PRINTABLE_US_ASCII = 0x7E;

    private static final String UNKNOWN_TOKEN = "UNKNOWN";

    private static final String OTHER_EXIT_CODE = "OTHER";

    private static final Set<String> SAFE_EXIT_CODES = Set.of(
            "COMPLETED", "EXECUTING", "NOOP", "FAILED", "STOPPED", UNKNOWN_TOKEN);

    /**
     * Snapshots that may wait for the single worker before the oldest is shed.
     *
     * <p>Sized for a burst rather than for a backlog. The batch tier completes jobs in ones, not
     * hundreds, so a depth of this order absorbs every realistic burst while still being a bound: a
     * topic that is slow for long enough to fill it is a topic this channel should be shedding to,
     * because each snapshot describes a job that has already finished and whose verdict is already
     * recorded. An unbounded queue would trade a delay for a leak.
     */
    static final int PENDING_NOTIFICATION_CAPACITY = 64;

    /** How long {@link #close()} lets an in-flight publish finish before abandoning it. */
    static final long SHUTDOWN_GRACE_MILLIS = 2_000L;

    private static final Logger LOGGER =
            LoggerFactory.getLogger(JobCompletionNotificationService.class);

    private final SnsOperations snsOperations;

    private final String topic;

    private final ObservationRegistry observationRegistry;

    /**
     * The single worker the listener path hands off to.
     *
     * <p>Created here rather than injected because its bound is part of this class's contract rather
     * than a deployment choice - a caller that could supply an unbounded executor could reintroduce
     * exactly the defect this exists to close. It costs nothing while unused: the core size is zero, so
     * no thread exists until the first notification is actually handed off, which keeps a directly
     * constructed instance thread-free.
     */
    private final ThreadPoolExecutor deliveryWorker;

    /**
     * Creates the producer over the configured, pre-provisioned topic.
     *
     * @param snsOperations       framework SNS publishing boundary
     * @param topic               configured topic name or ARN
     * @param observationRegistry registry for the actual outbound publish
     */
    public JobCompletionNotificationService(
            final SnsOperations snsOperations,
            @Value("${" + TOPIC_PROPERTY + "}") final String topic,
            final ObservationRegistry observationRegistry) {
        this.snsOperations = Objects.requireNonNull(
                snsOperations, "snsOperations must not be null");
        this.topic = requireTopic(topic);
        this.observationRegistry = Objects.requireNonNull(
                observationRegistry, "observationRegistry must not be null");
        this.deliveryWorker = new ThreadPoolExecutor(
                0, 1,
                SHUTDOWN_GRACE_MILLIS, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(PENDING_NOTIFICATION_CAPACITY),
                runnable -> {
                    final Thread worker = new Thread(runnable, "job-completion-notifier");
                    worker.setDaemon(true);
                    return worker;
                },
                new ThreadPoolExecutor.DiscardOldestPolicy());
        // With a maximum of one thread and a core of zero, the worker must be allowed to be created on
        // demand and to retire when idle; without this the pool would keep no thread at all.
        this.deliveryWorker.allowCoreThreadTimeOut(true);
    }

    /**
     * Receives the parameter-free completion snapshot published by the shared batch listener and returns
     * without waiting for the topic.
     *
     * <p>This is the boundary that makes "best effort" true. Spring's event boundary is synchronous, so
     * whatever this method does, the batch job-boundary listener's {@code afterJob} callback does too -
     * and it did the outbound publish, which meant a slow topic delayed job finalization even though
     * every failure was already being absorbed. Handing the snapshot to the bounded worker and returning
     * releases {@code afterJob} at once.
     *
     * <p>A snapshot that cannot even be queued is shed rather than published inline, because publishing
     * it inline is the behaviour being removed. The shed is logged so an operator can see that a
     * notification was lost, and it names only authored identifiers.
     *
     * @param event completed job snapshot
     */
    @Override
    public void onApplicationEvent(final JobCompletionEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        final String jobName = token(event.jobName(), MAX_JOB_NAME_LENGTH, UNKNOWN_TOKEN);
        final String executionId =
                event.jobExecutionId() == null ? UNKNOWN_TOKEN : event.jobExecutionId().toString();
        final int pendingBefore = this.deliveryWorker.getQueue().size();
        try {
            this.deliveryWorker.execute(() -> publishCompletion(event));
        } catch (final RejectedExecutionException shuttingDown) {
            LOGGER.warn("Job-completion notification was not handed off because the notifier is"
                            + " closed: job={} jobExecutionId={} topic={}",
                    jobName, executionId, this.topic);
            return;
        }
        if (pendingBefore >= PENDING_NOTIFICATION_CAPACITY) {
            // DiscardOldestPolicy accepted this snapshot by dropping the head of a full queue. Reported
            // because a silently shed notification is indistinguishable from one that was never
            // produced, and an operator reading the topic would draw the wrong conclusion.
            LOGGER.warn("The job-completion notifier queue was full, so an older notification was shed"
                            + " to accept this one: job={} jobExecutionId={} topic={} capacity={}",
                    jobName, executionId, this.topic, PENDING_NOTIFICATION_CAPACITY);
        }
    }

    /**
     * Stops accepting notifications and lets an in-flight publish finish, briefly.
     *
     * <p>Called by the container as this bean's inferred destroy method. The grace period exists so a
     * notification already on the wire is not cut off by an orderly shutdown, and it is short because the
     * alternative to finishing it is losing an operational notice about a job that has already
     * completed - which is exactly the trade this whole channel is defined to make.
     */
    @Override
    public void close() {
        this.deliveryWorker.shutdown();
        try {
            if (!this.deliveryWorker.awaitTermination(SHUTDOWN_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                final int abandoned = this.deliveryWorker.shutdownNow().size();
                if (abandoned > 0) {
                    LOGGER.warn("{} job-completion notification(s) were abandoned at shutdown;"
                            + " topic={}", abandoned, this.topic);
                }
            }
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            this.deliveryWorker.shutdownNow();
        }
    }

    /**
     * Publishes one completion notification without allowing a notification failure to escape.
     *
     * <p>Synchronous, and deliberately still so. This is the entry point for a caller that has asked for
     * the publish itself and wants its outcome - which is why it returns one. The listener path above
     * does not call it directly; it hands the snapshot to the bounded worker, which then calls this. So
     * the network wait lives on the worker's thread rather than on the batch job's, and a caller that
     * genuinely wants to wait still can.
     *
     * @param event completed job snapshot
     * @return {@code true} when SNS accepted the notification, otherwise {@code false}
     */
    public boolean publishCompletion(final JobCompletionEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        final String jobName = token(event.jobName(), MAX_JOB_NAME_LENGTH, UNKNOWN_TOKEN);
        final String executionId =
                event.jobExecutionId() == null ? UNKNOWN_TOKEN : event.jobExecutionId().toString();
        try {
            final String payload = payload(event, jobName);
            final SnsNotification<String> notification =
                    SnsNotification.builder(payload).subject(SUBJECT).build();
            Observation.createNotStarted(OBSERVATION_NAME, this.observationRegistry)
                    .lowCardinalityKeyValue(TAG_SYSTEM, SYSTEM_SNS)
                    .lowCardinalityKeyValue(TAG_OPERATION, OPERATION_PUBLISH)
                    .lowCardinalityKeyValue(TAG_EVENT_TYPE, EVENT_TYPE)
                    .highCardinalityKeyValue(TAG_TOPIC, this.topic)
                    .highCardinalityKeyValue(TAG_JOB, jobName)
                    .highCardinalityKeyValue(TAG_EXECUTION, executionId)
                    .observe(() -> this.snsOperations.sendNotification(this.topic, notification));
            LOGGER.info("Job-completion notification published: job={} jobExecutionId={} topic={}",
                    jobName, executionId, this.topic);
            return true;
        } catch (final RuntimeException publishFailure) {
            LOGGER.warn("Job-completion notification was not published: job={} jobExecutionId={}"
                            + " topic={} failureChain={}",
                    jobName, executionId, this.topic,
                    FailureDiagnostics.failureChainOf(publishFailure));
            return false;
        }
    }

    static String payload(final JobCompletionEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        return payload(event, token(event.jobName(), MAX_JOB_NAME_LENGTH, UNKNOWN_TOKEN));
    }

    private static String payload(final JobCompletionEvent event, final String jobName) {
        final String status = event.status() == null ? UNKNOWN_TOKEN : event.status().name();
        final String exitCode = exitCode(event.exitCode());
        final String payload = "{"
                + "\"schemaVersion\":" + SCHEMA_VERSION
                + ",\"eventType\":\"" + EVENT_TYPE + "\""
                + ",\"jobName\":\"" + jobName + "\""
                + ",\"jobInstanceId\":" + number(event.jobInstanceId())
                + ",\"jobExecutionId\":" + number(event.jobExecutionId())
                + ",\"status\":\"" + status + "\""
                + ",\"exitCode\":\"" + exitCode + "\""
                + ",\"stepsExecuted\":" + event.stepsExecuted()
                + ",\"startedAt\":" + timestamp(event.startedAt())
                + ",\"endedAt\":" + timestamp(event.endedAt())
                + "}";
        final int payloadBytes = payload.getBytes(StandardCharsets.US_ASCII).length;
        if (payloadBytes > MAX_PAYLOAD_BYTES) {
            throw new IllegalStateException("the bounded job-completion payload is " + payloadBytes
                    + " bytes, exceeding its " + MAX_PAYLOAD_BYTES + "-byte contract");
        }
        return payload;
    }

    private static String number(final Long value) {
        return value == null ? "null" : value.toString();
    }

    private static String timestamp(final LocalDateTime value) {
        if (value == null) {
            return "null";
        }
        return "\"" + DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(value) + "\"";
    }

    private static String exitCode(final String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN_TOKEN;
        }
        if (SAFE_EXIT_CODES.contains(value)) {
            return value;
        }
        if (value.length() <= 4) {
            for (int index = 0; index < value.length(); index++) {
                final char character = value.charAt(index);
                if (character < '0' || character > '9') {
                    return OTHER_EXIT_CODE;
                }
            }
            return value;
        }
        return OTHER_EXIT_CODE;
    }

    private static String token(
            final String value, final int maximumLength, final String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        final int length = Math.min(value.length(), maximumLength);
        final StringBuilder result = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            final char character = value.charAt(index);
            if ((character >= 'A' && character <= 'Z')
                    || (character >= 'a' && character <= 'z')
                    || (character >= '0' && character <= '9')
                    || character == '.' || character == '_' || character == '-') {
                result.append(character);
            } else {
                result.append('_');
            }
        }
        return result.toString();
    }

    private static String requireTopic(final String topic) {
        Objects.requireNonNull(topic, "topic must not be null");
        if (topic.isBlank()) {
            throw new IllegalArgumentException(
                    "property " + TOPIC_PROPERTY + " must not be blank");
        }
        if (topic.length() > MAX_TOPIC_LENGTH) {
            throw new IllegalArgumentException("property " + TOPIC_PROPERTY + " must be at most "
                    + MAX_TOPIC_LENGTH + " characters");
        }
        for (int index = 0; index < topic.length(); index++) {
            final char character = topic.charAt(index);
            if (character < MIN_PRINTABLE_US_ASCII || character > MAX_PRINTABLE_US_ASCII) {
                throw new IllegalArgumentException("property " + TOPIC_PROPERTY
                        + " must contain printable US-ASCII only; character at zero-based position "
                        + index + " is code point " + (int) character);
            }
        }
        return topic;
    }
}
