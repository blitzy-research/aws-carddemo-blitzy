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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.sns.core.SnsOperations;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

    private S3Operations s3Operations;
    private SqsAsyncClient sqsAsyncClient;
    private SnsClient snsClient;
    private SnsOperations snsOperations;
    private AwsResourceHealthConfig configuration;

    @BeforeEach
    void setUp() {
        s3Operations = mock(S3Operations.class);
        sqsAsyncClient = mock(SqsAsyncClient.class);
        snsClient = mock(SnsClient.class);
        snsOperations = mock(SnsOperations.class);
        configuration = new AwsResourceHealthConfig(settings());
    }

    @Test
    @DisplayName("requires the validated AWS inventory")
    void requiresTheAwsInventory() {
        assertThatNullPointerException()
                .isThrownBy(() -> new AwsResourceHealthConfig(null))
                .withMessageContaining("awsProperties");
    }

    @Test
    @DisplayName("publishes three contributors without touching any client while the context starts")
    void publishesThreeLazyContributors() {
        new ApplicationContextRunner()
                .withUserConfiguration(AwsResourceHealthConfig.class)
                .withBean(AwsProperties.class, AwsResourceHealthConfigTest::settings)
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
        final HealthIndicator indicator = configuration.awsS3HealthIndicator(s3Operations);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        final Health absent = indicator.health();
        assertThat(absent.getStatus()).isEqualTo(Status.DOWN);
        assertThat(absent.getDetails()).containsEntry("reason", "resource-not-found");
    }

    @Test
    @DisplayName("reduces an object-store failure to its bounded type chain")
    void boundsBucketFailureDetails() {
        when(s3Operations.bucketExists(BUCKET))
                .thenThrow(new IllegalStateException("provider path /secret must not escape"));

        final Health health = configuration.awsS3HealthIndicator(s3Operations).health();

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
                new AwsResourceHealthConfig(settings(QUEUE_URL));
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
                configuration.awsSnsHealthIndicator(snsClient, snsOperations).health();

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
                configuration.awsSnsHealthIndicator(snsClient, snsOperations).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        verifyNoInteractions(snsOperations);
        verify(snsClient, never()).createTopic(any(
                software.amazon.awssdk.services.sns.model.CreateTopicRequest.class));
    }

    @Test
    @DisplayName("the shipped groups keep AWS in readiness and out of liveness")
    void configuresReadinessWithoutChangingLiveness() throws IOException {
        final String shared = new ClassPathResource("application.yml").getContentAsString(
                StandardCharsets.UTF_8);

        assertThat(shared)
                .contains("liveness:\n          include: livenessState")
                .contains("readiness:\n"
                        + "          include: readinessState,db,awsS3,awsSqs,awsSns");
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
