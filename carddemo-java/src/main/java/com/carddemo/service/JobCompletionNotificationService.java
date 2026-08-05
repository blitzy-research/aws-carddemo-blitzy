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
 */
@Service
public final class JobCompletionNotificationService
        implements ApplicationListener<JobCompletionEvent> {

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

    private static final Logger LOGGER =
            LoggerFactory.getLogger(JobCompletionNotificationService.class);

    private final SnsOperations snsOperations;

    private final String topic;

    private final ObservationRegistry observationRegistry;

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
    }

    /**
     * Receives the parameter-free completion snapshot published by the shared batch listener.
     *
     * @param event completed job snapshot
     */
    @Override
    public void onApplicationEvent(final JobCompletionEvent event) {
        publishCompletion(event);
    }

    /**
     * Publishes one completion notification without allowing a notification failure to escape.
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
