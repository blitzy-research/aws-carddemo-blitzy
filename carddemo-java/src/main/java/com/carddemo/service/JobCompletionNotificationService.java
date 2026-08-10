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
import com.carddemo.util.ObservationPropagation;
import io.awspring.cloud.sns.core.SnsNotification;
import io.awspring.cloud.sns.core.SnsOperations;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
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
 * <h2>Every notification this channel loses is counted, by the code that loses it</h2>
 *
 * <p>Shedding is only defensible if it is visible, and it was not. The pool's own
 * {@code DiscardOldestPolicy} discards without reporting, and after {@link #close()} it discards
 * <em>silently and unconditionally</em> - it checks whether the pool is shut down and, if it is, does
 * nothing at all - while {@code execute} raises nothing, so the caller could not tell either. The loss
 * was inferred instead from a queue-depth sample read before the hand-off, which is a different moment
 * from the rejection and disagrees with it under concurrency in both directions: it reports a shed that
 * did not happen when the worker drains between the sample and the offer, and misses one that did when
 * another producer fills the queue in the same interval.
 *
 * <p>{@link #shedRatherThanQueue} replaces it. It runs <em>inside</em> the rejection, which is the only
 * moment at which a loss is a fact rather than an estimate, and it counts exactly the notifications
 * actually lost on {@link #SHED_METER_NAME}, tagged with why. {@link #close()} counts what it abandons
 * on the same meter, on both of its paths - the grace period expiring and the wait being interrupted -
 * so one number answers "how many completion notices did this instance lose, and to what".
 *
 * <p>None of this changes the verdict contract below, and that is the point: the meter exists so that a
 * channel permitted to lose messages cannot lose them unaccountably. See {@code docs/decision-log.md}
 * entry DL-306.
 *
 * <h2>The dependency has two stages, and only the second one is best effort</h2>
 *
 * <p>"Optional" on its own is not a contract, and reading it as one produced a genuine contradiction:
 * this class documented an optional dependency while a production start-up <em>refuses to begin</em>
 * without the topic. Both are true of different stages, and the distinction is what a reader needs.
 *
 * <p><strong>Stage one - provisioning and ownership are MANDATORY, and they are checked before this
 * class publishes anything.</strong> On a production start-up
 * {@code config/AwsResourceTrustVerifier} resolves the configured topic from a listing rather than by
 * creating it, requires the resolved locator to be owned by the account the deployment declares, and
 * requires the topic itself to answer an attribute read; any of the three failing <em>aborts the
 * start-up</em>. In the local validation stack the same requirement is expressed by the emulator's
 * health check, which does not report healthy until the topic exists, and the application container
 * waits on that health rather than on the emulator merely having started. So there is no supported
 * deployment in which this channel's destination is absent or belongs to somebody else.
 *
 * <p><strong>Stage two - after start-up, DELIVERY is best effort, and nothing downstream waits on
 * it.</strong> A notification that is dropped, shed, refused or timed out leaves the job's status and
 * exit code exactly as the framework recorded them, is counted on the shed meter above, and does not
 * take the instance out of service: {@code application.yml} publishes the topic's health contributor
 * but deliberately leaves it OUT of the readiness group, because the topic carries a notice that a
 * batch job has <em>already</em> finished, so its absence loses a notice and prevents no work. An
 * earlier revision did place it in the required group, which took a whole instance out of service for
 * a lost notice - the treatment owed to a dependency whose absence prevents work, and this one
 * prevents none. The contrast that settles the readiness question is the job-submission queue, which
 * stays in the group: a report request that cannot reach the queue never runs its job, so an instance
 * that cannot reach the queue genuinely cannot serve.
 *
 * <p>The two stages are not in tension. Stage one is about whether the destination this deployment was
 * configured for is the one it will reach; stage two is about whether an individual notice arrives.
 * Recorded as {@code DL-339}.
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

    /**
     * Version of the JSON body below, incremented when the body's shape or meaning changes.
     *
     * <p>Version 2 replaced the two zone-less local date-times with UTC instants rendered as ISO-8601
     * with a trailing {@code Z}. Version 1's timestamps could not be ordered across two instances
     * configured in different zones and were ambiguous either side of a daylight-saving transition, so
     * the change is to the <em>meaning</em> of two fields and not only to their formatting - which is
     * exactly what a subscriber needs the version to tell it.
     */
    public static final int SCHEMA_VERSION = 2;

    public static final String EVENT_TYPE = "carddemo.batch.job-completion";

    public static final String OBSERVATION_NAME = "carddemo.job.completion.publish";

    public static final String TAG_SYSTEM = "system";

    public static final String TAG_OPERATION = "operation";

    public static final String TAG_EVENT_TYPE = "eventType";

    public static final String TAG_TOPIC = "topic";

    /**
     * Tag naming the job a completion notification belongs to.
     *
     * <p><strong>Deliberately not {@code job}.</strong> Prometheus stamps its own {@code job} label onto
     * every series it collects, naming the scrape target rather than the application dimension. Two
     * labels of one name cannot coexist, so the exporter's value is renamed to {@code exported_job} on
     * collection and every query grouping by {@code job} collapses to the single scrape target -
     * silently, with the panel still rendering. Recorded as {@code DL-338}.
     */
    public static final String TAG_JOB = "batchJob";

    public static final String TAG_EXECUTION = "jobExecutionId";

    public static final String SYSTEM_SNS = "sns";

    public static final String OPERATION_PUBLISH = "publish";

    public static final String SUBJECT = "CardDemo batch job completion";

    public static final int MAX_PAYLOAD_BYTES = 512;

    /**
     * Counter of completion notifications this channel lost rather than delivered.
     *
     * <p>A count and not a gauge, because the quantity that matters is cumulative loss rather than a
     * present depth, and because the events being counted are individually irrecoverable. Read against
     * the delivery count published by {@link #OBSERVATION_NAME}, it answers the only question a shedding
     * channel owes an operator: what fraction of what happened was announced.
     */
    public static final String SHED_METER_NAME = "carddemo.job.completion.shed";

    /** Description of {@link #SHED_METER_NAME}, stated once. */
    private static final String SHED_METER_DESCRIPTION =
            "CardDemo batch job-completion notifications lost rather than delivered, by reason";

    /** Tag naming why a notification was lost. */
    public static final String TAG_REASON = "reason";

    /** {@link #TAG_REASON} value for a notification displaced from a full pending queue. */
    public static final String REASON_QUEUE_FULL = "QUEUE_FULL";

    /** {@link #TAG_REASON} value for a notification offered after the notifier was closed. */
    public static final String REASON_NOTIFIER_CLOSED = "NOTIFIER_CLOSED";

    /** {@link #TAG_REASON} value for a notification still pending when shutdown gave up waiting. */
    public static final String REASON_SHUTDOWN = "SHUTDOWN";

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

    /** Where every lost notification is counted. */
    private final MeterRegistry meterRegistry;

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
     * @param meterRegistry       registry the lost-notification count is published on
     */
    public JobCompletionNotificationService(
            final SnsOperations snsOperations,
            @Value("${" + TOPIC_PROPERTY + "}") final String topic,
            final ObservationRegistry observationRegistry,
            final MeterRegistry meterRegistry) {
        this.snsOperations = Objects.requireNonNull(
                snsOperations, "snsOperations must not be null");
        this.topic = requireTopic(topic);
        this.observationRegistry = Objects.requireNonNull(
                observationRegistry, "observationRegistry must not be null");
        this.meterRegistry = Objects.requireNonNull(
                meterRegistry, "meterRegistry must not be null");
        this.deliveryWorker = new ThreadPoolExecutor(
                0, 1,
                SHUTDOWN_GRACE_MILLIS, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(PENDING_NOTIFICATION_CAPACITY),
                runnable -> {
                    final Thread worker = new Thread(runnable, "job-completion-notifier");
                    worker.setDaemon(true);
                    return worker;
                },
                // The pool's own DiscardOldestPolicy is replaced rather than wrapped: it is the component
                // that discards silently, and after shutdown it discards without even the courtesy of a
                // rejection. Everything else about this pool - one thread, this queue depth, this shed
                // direction - is retained exactly. Only the reporting changes.
                this::shedRatherThanQueue);
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
     * it inline is the behaviour being removed. Reporting the shed belongs to
     * {@link #shedRatherThanQueue}, not here: this method cannot observe a rejection it does not receive,
     * and the queue-depth sample it used to read instead was taken at a different moment from the one it
     * described. Nothing is logged here about a loss, and nothing needs to be.
     *
     * @param event completed job snapshot
     */
    @Override
    public void onApplicationEvent(final JobCompletionEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        try {
            // Wrapped so the publish runs inside whatever observation is current HERE. Without it the
            // worker thread starts with nothing current and the SNS publish becomes a detached root - a
            // one-span trace with no link to the launch that produced the job. The job's own span cannot
            // be that parent: the framework stops it before the terminal callbacks, so what is carried is
            // the context enclosing the launch, which is the strongest link still available and strictly
            // better than dropping it. The executor itself is untouched: its queue, its ceiling and its
            // rejection behaviour are all load-bearing and none of them is a tracing concern. See
            // docs/decision-log.md entry DL-305.
            this.deliveryWorker.execute(ObservationPropagation.inCurrentObservation(
                    this.observationRegistry, () -> publishCompletion(event)));
        } catch (final RuntimeException handOffFailure) {
            // The headline contract of this class is that the batch verdict is never a function of this
            // channel, and this is the boundary where that is enforced. The rejection handler below is
            // written not to throw, so nothing is expected here; the guard is kept because it is the only
            // thing standing between an unexpected fault in this optional channel and a completed job's
            // afterJob callback, and it is answered with a diagnostic naming no caller content.
            LOGGER.warn("A job-completion notification could not be handed off: topic={}"
                            + " failureChain={}",
                    this.topic, FailureDiagnostics.failureChainOf(handOffFailure));
        }
    }

    /**
     * Sheds a notification the bounded worker cannot take, and counts the loss from inside the rejection.
     *
     * <p>Three situations reach this method and they are not the same event, which is why the meter is
     * tagged rather than plain:
     *
     * <ol>
     *   <li><strong>The notifier is closed.</strong> The pool takes nothing more, so the arriving
     *       snapshot is lost. This is the case the pool's own policy handled by doing literally nothing,
     *       and it is the reason a shed after shutdown left no trace anywhere.</li>
     *   <li><strong>The queue is full.</strong> The head is displaced so the arriving snapshot can take
     *       its place - the same direction the previous policy chose, and the right one, because every
     *       snapshot here describes a job that has already finished and an operator needs the most recent
     *       completions rather than the oldest. The displaced snapshot is the loss.</li>
     *   <li><strong>The queue refilled between the two operations.</strong> Another producer took the
     *       space, so the arriving snapshot is lost too. It is not published inline as a consolation:
     *       publishing inline on the caller's thread is precisely the behaviour this hand-off exists to
     *       remove.</li>
     * </ol>
     *
     * <p>Each branch counts only what it actually lost. A displacement that finds the queue already
     * drained counts nothing, because nothing was lost - the case the discarded queue-depth sample used
     * to report as a shed.
     *
     * <p>No identifier from the snapshot appears in any message here. The {@link Runnable} handed to the
     * pool is an opaque unit of work by then, and reaching back into it for a job name would mean
     * carrying the snapshot alongside the task purely to name it in a log. The topic, the capacity and
     * the reason are enough to act on, and the meter carries the count.
     *
     * @param arriving the snapshot's delivery task, never {@code null}
     * @param executor the pool that refused it, never {@code null}
     */
    private void shedRatherThanQueue(final Runnable arriving, final ThreadPoolExecutor executor) {
        if (executor.isShutdown()) {
            countShed(REASON_NOTIFIER_CLOSED, 1);
            LOGGER.warn("A job-completion notification was not handed off because the notifier is"
                    + " closed: topic={} reason={}", this.topic, REASON_NOTIFIER_CLOSED);
            return;
        }
        if (executor.getQueue().poll() != null) {
            countShed(REASON_QUEUE_FULL, 1);
            LOGGER.warn("The job-completion notifier queue was full, so the oldest pending notification"
                            + " was shed to accept a newer one: topic={} capacity={} reason={}",
                    this.topic, PENDING_NOTIFICATION_CAPACITY, REASON_QUEUE_FULL);
        }
        // Re-offered onto the queue rather than re-submitted through execute(). Submitting would re-enter
        // this handler if the queue filled again, which is a recursion the previous policy carried and
        // this one does not need: a rejection with a single-thread ceiling means exactly one worker is
        // running, the pool re-creates a worker whenever it exits with a non-empty queue, and a worker
        // never retires while the queue holds anything - so a queued task is always picked up.
        if (!executor.getQueue().offer(arriving)) {
            countShed(REASON_QUEUE_FULL, 1);
            LOGGER.warn("The job-completion notifier queue refilled while a notification was being"
                            + " shed, so the arriving notification was shed too: topic={} capacity={}"
                            + " reason={}",
                    this.topic, PENDING_NOTIFICATION_CAPACITY, REASON_QUEUE_FULL);
        }
    }

    /**
     * Records lost notifications on the one meter that counts them.
     *
     * <p>Registered on first use for each reason, which is how every other counter in this module is
     * published. A meter fault is absorbed at debug: this method is called from a rejection handler and
     * from shutdown, and neither is a place where a telemetry problem may become the caller's problem.
     *
     * @param reason the {@link #TAG_REASON} value
     * @param lost   how many notifications were lost, always positive at every call site
     */
    private void countShed(final String reason, final int lost) {
        try {
            Counter.builder(SHED_METER_NAME)
                    .description(SHED_METER_DESCRIPTION)
                    .tag(TAG_REASON, reason)
                    .register(this.meterRegistry)
                    .increment(lost);
        } catch (final RuntimeException meterFailure) {
            LOGGER.debug("Could not count {} shed job-completion notification(s) with reason {}:"
                            + " failureType={}",
                    lost, reason, meterFailure.getClass().getSimpleName());
        }
    }

    /**
     * Stops accepting notifications and lets an in-flight publish finish, briefly.
     *
     * <p>Called by the container as this bean's inferred destroy method. The grace period exists so a
     * notification already on the wire is not cut off by an orderly shutdown, and it is short because the
     * alternative to finishing it is losing an operational notice about a job that has already
     * completed - which is exactly the trade this whole channel is defined to make.
     *
     * <p>Both ways out of the wait abandon pending work and both now account for it. The interrupted path
     * previously called {@code shutdownNow()} and discarded its answer, so an interrupted shutdown - the
     * one that happens when a container is being torn down under pressure, and therefore the one most
     * likely to be holding a backlog - lost notifications without recording that it had. A promise of
     * diagnostics that only holds on the path that was not in a hurry is not a promise.
     */
    @Override
    public void close() {
        this.deliveryWorker.shutdown();
        try {
            if (!this.deliveryWorker.awaitTermination(SHUTDOWN_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                reportAbandoned(this.deliveryWorker.shutdownNow().size(),
                        "the grace period expired");
            }
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            reportAbandoned(this.deliveryWorker.shutdownNow().size(),
                    "the shutdown wait was interrupted");
        }
    }

    /**
     * Records notifications abandoned by a shutdown that stopped waiting.
     *
     * <p>Counted on the same meter as every other loss, because an operator asking how many completion
     * notices went unannounced does not care which mechanism dropped them, and would be misled by a
     * number that covered only some of them.
     *
     * @param abandoned how many pending notifications were discarded, possibly zero
     * @param cause     why the shutdown stopped waiting, for the diagnostic
     */
    private void reportAbandoned(final int abandoned, final String cause) {
        if (abandoned <= 0) {
            return;
        }
        countShed(REASON_SHUTDOWN, abandoned);
        LOGGER.warn("{} job-completion notification(s) were abandoned at shutdown because {}: topic={}"
                + " reason={}", abandoned, cause, this.topic, REASON_SHUTDOWN);
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

    /**
     * Renders one boundary as an ISO-8601 instant in UTC, or as JSON {@code null}.
     *
     * <p>{@code ISO_INSTANT} always ends in {@code Z}, so a subscriber cannot read the value as local
     * time by mistake, and two notices from two instances are directly comparable. An absent boundary
     * stays absent rather than becoming an epoch or a placeholder, because the framework genuinely
     * records no end time for an execution abandoned before it finished.
     *
     * @param  value the boundary, possibly {@code null}
     * @return the quoted ISO-8601 UTC image, or the bare token {@code null}
     */
    private static String timestamp(final Instant value) {
        if (value == null) {
            return "null";
        }
        return "\"" + DateTimeFormatter.ISO_INSTANT.format(value) + "\"";
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
