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
import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.sns.core.SnsOperations;
import io.awspring.cloud.sns.core.TopicArnResolver;
import io.awspring.cloud.sns.core.TopicsListingTopicArnResolver;
import io.awspring.cloud.sqs.QueueAttributesResolver;
import io.awspring.cloud.sqs.listener.QueueNotFoundStrategy;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
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
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
public final class AwsResourceHealthConfig {

    /** Detail value used when an existence operation completed and reported absence. */
    private static final String RESOURCE_NOT_FOUND = "resource-not-found";

    /** Bound resource names and endpoint settings. */
    private final AwsProperties awsProperties;

    /**
     * Creates the readiness configuration over the already validated AWS settings.
     *
     * @param awsProperties the bound AWS resource inventory; must not be {@code null}
     */
    public AwsResourceHealthConfig(final AwsProperties awsProperties) {
        this.awsProperties = Objects.requireNonNull(awsProperties,
                "awsProperties must not be null");
    }

    /**
     * Contributes the {@code awsS3} readiness component.
     *
     * @param s3Operations the auto-configured object-store facade
     * @return a read-only bucket existence check
     */
    @Bean
    public HealthIndicator awsS3HealthIndicator(final S3Operations s3Operations) {
        Objects.requireNonNull(s3Operations, "s3Operations must not be null");
        final String bucket = this.awsProperties.s3().batchStagingBucket();
        return existenceIndicator(() -> s3Operations.bucketExists(bucket));
    }

    /**
     * Contributes the {@code awsSqs} readiness component.
     *
     * <p>{@link QueueAttributesResolver} accepts the same three destination forms as the publisher:
     * queue name, queue URL and queue ARN. Resolving with {@link QueueNotFoundStrategy#FAIL} is the
     * load-bearing part of this method: the alternate strategy creates a queue and is prohibited for a
     * health check.
     *
     * @param sqsAsyncClient the auto-configured queue client
     * @return a read-only queue resolution check
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
        return existenceIndicator(() -> {
            resolver.resolveQueueAttributes().join();
            return true;
        });
    }

    /**
     * Contributes the {@code awsSns} readiness component.
     *
     * <p>The default topic resolver creates a topic when given a name, so it is intentionally not used
     * here. {@link TopicsListingTopicArnResolver} lists and matches instead. An already configured ARN
     * is still verified through {@link SnsOperations#topicExists(String)} rather than accepted on
     * syntax alone.
     *
     * @param snsClient the auto-configured notification client used only for topic listing
     * @param snsOperations the auto-configured notification facade used for the attribute check
     * @return a read-only topic existence check
     */
    @Bean
    public HealthIndicator awsSnsHealthIndicator(final SnsClient snsClient,
            final SnsOperations snsOperations) {
        Objects.requireNonNull(snsClient, "snsClient must not be null");
        Objects.requireNonNull(snsOperations, "snsOperations must not be null");
        final TopicArnResolver resolver = new TopicsListingTopicArnResolver(snsClient);
        final String topic = this.awsProperties.sns().jobNotificationTopic();
        return existenceIndicator(() -> {
            final String topicArn = resolver.resolveTopicArn(topic).toString();
            return snsOperations.topicExists(topicArn);
        });
    }

    /**
     * Turns one read-only existence probe into a bounded Actuator health result.
     *
     * @param exists the operation that reports whether its resource exists
     * @return a health contributor that never throws provider failures through the endpoint
     */
    private static HealthIndicator existenceIndicator(final BooleanSupplier exists) {
        Objects.requireNonNull(exists, "exists must not be null");
        return () -> {
            try {
                if (exists.getAsBoolean()) {
                    return Health.up().build();
                }
                return Health.down()
                        .withDetail("reason", RESOURCE_NOT_FOUND)
                        .build();
            } catch (final RuntimeException healthFailure) {
                return Health.down()
                        .withDetail("failureChain",
                                FailureDiagnostics.failureChainOf(healthFailure))
                        .build();
            }
        };
    }
}
