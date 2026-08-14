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
package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.service.JobCompletionEvent;
import com.carddemo.service.JobCompletionNotificationService;
import com.carddemo.support.AbstractLocalStackIT;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sns.core.CachingTopicArnResolver;
import io.awspring.cloud.sns.core.SnsOperations;
import io.awspring.cloud.sns.core.SnsTemplate;
import io.awspring.cloud.sns.core.TopicsListingTopicArnResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.SetQueueAttributesRequest;

/**
 * Drives a completion event through the real SNS transport and an SQS subscription in LocalStack.
 */
@DisplayName("JobCompletionNotificationPublisherIT - pre-provisioned SNS topic delivery")
final class JobCompletionNotificationPublisherIT extends AbstractLocalStackIT {

    /** JSON codec used for the queue policy and delivered SNS envelope. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Assigned execution identifier carried through the real transport. */
    private static final long EXECUTION_ID = 73L;

    /** Job name carried through the real transport. */
    private static final String JOB_NAME = "localStackCompletionJob";

    /** Notification client bound to the shared emulator. */
    private SnsClient snsClient;

    /** Topic created by the test before the publisher runs. */
    private String topicArn;

    /** Queue subscribed to the topic. */
    private String queueUrl;

    /** Subscription created by the test. */
    private String subscriptionArn;

    @AfterEach
    void removeTestResources() {
        if (this.snsClient != null) {
            if (this.subscriptionArn != null && !this.subscriptionArn.isBlank()) {
                this.snsClient.unsubscribe(request ->
                        request.subscriptionArn(this.subscriptionArn));
            }
            if (this.topicArn != null) {
                this.snsClient.deleteTopic(request -> request.topicArn(this.topicArn));
            }
            this.snsClient.close();
        }
        if (this.queueUrl != null) {
            sqsAsyncClient().deleteQueue(DeleteQueueRequest.builder()
                    .queueUrl(this.queueUrl)
                    .build()).join();
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a completed job publishes one safe event to the configured existing topic")
    void completedJobPublishesThroughTheExistingTopic() throws Exception {
        final String suffix = UUID.randomUUID().toString();
        final String topicName = "carddemo-completion-" + suffix;
        final String queueName = "carddemo-completion-" + suffix;
        this.snsClient = buildSnsClient();
        this.topicArn = this.snsClient.createTopic(request -> request.name(topicName)).topicArn();
        this.queueUrl = sqsAsyncClient().createQueue(CreateQueueRequest.builder()
                .queueName(queueName)
                .build()).join().queueUrl();
        final String queueArn = sqsAsyncClient().getQueueAttributes(
                GetQueueAttributesRequest.builder()
                        .queueUrl(this.queueUrl)
                        .attributeNames(QueueAttributeName.QUEUE_ARN)
                        .build()).join().attributes().get(QueueAttributeName.QUEUE_ARN);
        installTopicDeliveryPolicy(queueArn);
        this.subscriptionArn = this.snsClient.subscribe(request -> request
                .topicArn(this.topicArn)
                .protocol("sqs")
                .endpoint(queueArn)).subscriptionArn();

        final long topicsBefore = this.snsClient.listTopics().topics().size();
        final JobCompletionNotificationService service =
                new JobCompletionNotificationService(snsOperations(), topicName,
                        ObservationRegistry.create(), new SimpleMeterRegistry());
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.addApplicationListener(service);
            context.refresh();
            final JobCompletionNotificationPublisher publisher =
                    new JobCompletionNotificationPublisher(context);
            publisher.publishCompletion(new JobCompletionEvent(
                    JOB_NAME, 19L, EXECUTION_ID, BatchStatus.COMPLETED,
                    ExitStatus.COMPLETED.getExitCode(), 1, null, null));
        }

        assertThat(this.snsClient.listTopics().topics())
                .as("publication resolves the pre-provisioned topic and creates no second topic")
                .hasSize((int) topicsBefore)
                .extracting(software.amazon.awssdk.services.sns.model.Topic::topicArn)
                .contains(this.topicArn);
        final List<Message> delivered = drainQueue(this.queueUrl, 1);
        assertThat(delivered).hasSize(1);
        final JsonNode envelope = JSON.readTree(delivered.getFirst().body());
        assertThat(envelope.path("TopicArn").asText()).isEqualTo(this.topicArn);
        assertThat(envelope.path("Subject").asText())
                .isEqualTo(JobCompletionNotificationService.SUBJECT);

        final JsonNode payload = JSON.readTree(envelope.path("Message").asText());
        assertThat(payload.path("eventType").asText())
                .isEqualTo(JobCompletionNotificationService.EVENT_TYPE);
        assertThat(payload.path("schemaVersion").asInt())
                .isEqualTo(JobCompletionNotificationService.SCHEMA_VERSION);
        assertThat(payload.path("jobName").asText()).isEqualTo(JOB_NAME);
        assertThat(payload.path("jobInstanceId").asLong()).isEqualTo(19L);
        assertThat(payload.path("jobExecutionId").asLong()).isEqualTo(EXECUTION_ID);
        assertThat(payload.path("status").asText()).isEqualTo(BatchStatus.COMPLETED.name());
        assertThat(payload.path("exitCode").asText())
                .isEqualTo(ExitStatus.COMPLETED.getExitCode());
        assertThat(payload.path("stepsExecuted").asInt()).isOne();
        assertThat(payload.has("parameters")).isFalse();
        assertThat(payload.has("exceptions")).isFalse();
        assertThat(payload.has("exitDescription")).isFalse();
    }

    /**
     * Builds the notification client bound to the shared LocalStack instance.
     *
     * @return synchronous notification client
     */
    private static SnsClient buildSnsClient() {
        return SnsClient.builder()
                .endpointOverride(URI.create(emulatorEndpoint()))
                .region(Region.of(emulatorRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        emulatorAccessKey(), emulatorSecretKey())))
                .build();
    }

    /**
     * Builds the same listing-based operations path the application auto-configuration assembles.
     *
     * @return notification operations over the test client
     */
    private SnsOperations snsOperations() {
        final MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setSerializedPayloadClass(String.class);
        return new SnsTemplate(this.snsClient,
                new CachingTopicArnResolver(
                        new TopicsListingTopicArnResolver(this.snsClient)),
                converter);
    }

    /**
     * Allows only the created topic to send to the test queue.
     *
     * @param queueArn queue resource identifier
     * @throws Exception if the policy cannot be serialized
     */
    private void installTopicDeliveryPolicy(final String queueArn) throws Exception {
        final Map<String, Object> statement = Map.of(
                "Effect", "Allow",
                "Principal", Map.of("Service", "sns.amazonaws.com"),
                "Action", "sqs:SendMessage",
                "Resource", queueArn,
                "Condition", Map.of("ArnEquals", Map.of("aws:SourceArn", this.topicArn)));
        final String policy = JSON.writeValueAsString(Map.of(
                "Version", "2012-10-17",
                "Statement", List.of(statement)));
        sqsAsyncClient().setQueueAttributes(SetQueueAttributesRequest.builder()
                .queueUrl(this.queueUrl)
                .attributes(Map.of(QueueAttributeName.POLICY, policy))
                .build()).join();
    }
}
