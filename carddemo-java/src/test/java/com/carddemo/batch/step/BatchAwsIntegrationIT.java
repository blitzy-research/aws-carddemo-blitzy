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
package com.carddemo.batch.step;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.carddemo.batch.JobCompletionNotificationPublisher;
import io.awspring.cloud.s3.InMemoryBufferingS3OutputStreamProvider;
import io.awspring.cloud.s3.Jackson2JsonS3ObjectConverter;
import io.awspring.cloud.s3.PropertiesS3ObjectContentTypeResolver;
import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.s3.S3Template;
import io.awspring.cloud.sns.core.SnsOperations;
import io.awspring.cloud.sns.core.SnsTemplate;
import io.micrometer.observation.ObservationRegistry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;

import com.carddemo.config.BatchConfig;
import com.carddemo.service.JobCompletionEvent;
import com.carddemo.service.JobCompletionNotificationService;
import com.carddemo.support.AbstractLocalStackIT;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.sns.model.CreateTopicRequest;
import software.amazon.awssdk.services.sns.model.DeleteTopicRequest;
import software.amazon.awssdk.services.sns.model.SubscribeRequest;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

/**
 * Exercises the two new AWS boundaries against real LocalStack Community services.
 *
 * <p>The object-store test reads bytes back from S3 and observes the service-side retained key set; no
 * recording double participates in the successful path. The notification test delivers the listener's
 * structured event through SNS into an SQS subscription and asserts the body received from that queue.
 * Mocks are used only for Spring's optional-provider wrappers, never for either external service.
 */
@DisplayName("batch AWS integration: durable generations and terminal notifications")
class BatchAwsIntegrationIT extends AbstractLocalStackIT {

    private static final String GENERATION_BASE = "AWS.M2.CARDDEMO.DALYREJS";
    private static final String NOTIFICATION_TOPIC_STEM = "carddemo-job-completion-it-";
    private static final String NOTIFICATION_QUEUE_STEM = "carddemo-job-completion-it-";

    @TempDir
    private Path stagingDirectory;

    @Test
    @DisplayName("completed generations are stored byte-for-byte and the service retains the newest five")
    void completedGenerationsReachS3WithMeasuredRetention() throws Exception {
        final String bucket = "carddemo-generation-it-" + UUID.randomUUID();
        s3Client().createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        try (S3Presigner presigner = presigner()) {
            final S3Operations operations = new S3Template(
                    s3Client(),
                    new InMemoryBufferingS3OutputStreamProvider(
                            s3Client(), new PropertiesS3ObjectContentTypeResolver()),
                    new Jackson2JsonS3ObjectConverter(new ObjectMapper()),
                    presigner);
            // This test provisions LocalStack only and has no database, so the production advisory lock
            // cannot be used here. Running each publication directly is faithful for this test because it
            // drives seven publications from one thread in sequence: there is nothing to serialize. The
            // lock's own behaviour is asserted where it can be - in the store's unit test, which proves
            // the store hands it the right bases and publishes inside it.
            final StagedGenerationStore store =
                    new StagedGenerationStore(operations, bucket,
                            (bases, publication) -> publication.run());

            for (long executionId = 1; executionId <= 7; executionId++) {
                final JobExecution execution = completedJob(executionId);
                final StepExecution step = execution.createStepExecution("publishArtifact");
                final Path generation = StagedGenerationStore.generationPath(
                        this.stagingDirectory, GENERATION_BASE, executionId);
                Files.writeString(generation, "generation-" + executionId,
                        StandardCharsets.US_ASCII);
                StagedGenerationStore.register(step, GENERATION_BASE, generation,
                        StagedGenerationStore.STANDARD_RETENTION_LIMIT);

                store.publishRegistered(execution);
            }

            final List<String> keys = s3Client().listObjectsV2(ListObjectsV2Request.builder()
                            .bucket(bucket)
                            .prefix(GENERATION_BASE + "/")
                            .build())
                    .contents()
                    .stream()
                    .map(object -> object.key())
                    .toList();
            assertThat(keys).containsExactlyInAnyOrder(
                    GENERATION_BASE + "/G0000000003V00",
                    GENERATION_BASE + "/G0000000004V00",
                    GENERATION_BASE + "/G0000000005V00",
                    GENERATION_BASE + "/G0000000006V00",
                    GENERATION_BASE + "/G0000000007V00");

            final byte[] newest = s3Client().getObjectAsBytes(GetObjectRequest.builder()
                            .bucket(bucket)
                            .key(GENERATION_BASE + "/G0000000007V00")
                            .build())
                    .asByteArray();
            assertThat(newest).isEqualTo("generation-7".getBytes(StandardCharsets.US_ASCII));
        } finally {
            deleteBucketAndContents(bucket);
        }
    }

    @Test
    @DisplayName("the shared listener publishes one structured terminal event through SNS")
    void terminalOutcomeTravelsThroughTheNotificationTopic() throws Exception {
        final String suffix = UUID.randomUUID().toString();
        final String topicArn = snsClient().createTopic(CreateTopicRequest.builder()
                        .name(NOTIFICATION_TOPIC_STEM + suffix)
                        .build())
                .topicArn();
        final String queueUrl = sqsClient().createQueue(CreateQueueRequest.builder()
                        .queueName(NOTIFICATION_QUEUE_STEM + suffix)
                        .build())
                .queueUrl();
        try {
            final String queueArn = sqsClient().getQueueAttributes(
                            GetQueueAttributesRequest.builder()
                                    .queueUrl(queueUrl)
                                    .attributeNames(QueueAttributeName.QUEUE_ARN)
                                    .build())
                    .attributes()
                    .get(QueueAttributeName.QUEUE_ARN);
            snsClient().subscribe(SubscribeRequest.builder()
                    .topicArn(topicArn)
                    .protocol("sqs")
                    .endpoint(queueArn)
                    .attributes(Map.of("RawMessageDelivery", "true"))
                    .build());

            final MappingJackson2MessageConverter converter =
                    new MappingJackson2MessageConverter();
            converter.setSerializedPayloadClass(String.class);
            final SnsOperations notifications = new SnsTemplate(snsClient(), converter);
            final JobCompletionNotificationService service =
                    new JobCompletionNotificationService(notifications, topicArn,
                            ObservationRegistry.create());
            final JobCompletionNotificationPublisher publisher =
                    new JobCompletionNotificationPublisher(event ->
                            service.onApplicationEvent((JobCompletionEvent) event));
            final JobExecutionListener listener = new BatchConfig().batchJobBoundaryListener(
                    providerOf(null), providerOf(publisher),
                    System.getProperty("java.io.tmpdir"));
            final JobExecution execution = completedJob(22);
            final StepExecution step = execution.createStepExecution("completedStep");
            step.setStatus(BatchStatus.COMPLETED);
            step.setExitStatus(ExitStatus.COMPLETED);
            execution.setExitStatus(ExitStatus.COMPLETED);

            listener.afterJob(execution);

            final String body = receiveOne(queueUrl);
            final JsonNode payload = new ObjectMapper().readTree(body);
            assertThat(payload.path("eventType").asText())
                    .isEqualTo(JobCompletionNotificationService.EVENT_TYPE);
            assertThat(payload.path("schemaVersion").asInt())
                    .isEqualTo(JobCompletionNotificationService.SCHEMA_VERSION);
            assertThat(payload.path("jobName").asText()).isEqualTo("integrationJob");
            assertThat(payload.path("jobExecutionId").asLong()).isEqualTo(22L);
            assertThat(payload.path("status").asText()).isEqualTo(BatchStatus.COMPLETED.name());
            assertThat(payload.path("exitCode").asText())
                    .isEqualTo(ExitStatus.COMPLETED.getExitCode());
            assertThat(payload.path("stepsExecuted").asInt()).isOne();
            assertThat(payload.has("parameters")).isFalse();
            assertThat(payload.has("exitDescription")).isFalse();
        } finally {
            sqsClient().deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build());
            snsClient().deleteTopic(DeleteTopicRequest.builder().topicArn(topicArn).build());
        }
    }

    private static JobExecution completedJob(final long executionId) {
        final JobExecution execution = new JobExecution(
                new JobInstance(executionId + 1_000, "integrationJob"),
                executionId,
                new JobParameters());
        execution.setStatus(BatchStatus.COMPLETED);
        return execution;
    }

    private static S3Presigner presigner() {
        return S3Presigner.builder()
                .endpointOverride(java.net.URI.create(emulatorEndpoint()))
                .region(Region.of(emulatorRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        emulatorAccessKey(), emulatorSecretKey())))
                .build();
    }

    private static <T> ObjectProvider<T> providerOf(final T value) {
        final ObjectProvider<T> provider = mock();
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static String receiveOne(final String queueUrl) {
        for (int attempt = 0; attempt < 5; attempt++) {
            final var response = sqsClient().receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(1)
                    .waitTimeSeconds(1)
                    .build());
            if (!response.messages().isEmpty()) {
                return response.messages().getFirst().body();
            }
        }
        throw new AssertionError("the subscribed queue received no terminal job notification");
    }

    private static void deleteBucketAndContents(final String bucket) {
        final var objects = s3Client().listObjectsV2(
                ListObjectsV2Request.builder().bucket(bucket).build()).contents();
        for (final var object : objects) {
            s3Client().deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(object.key())
                    .build());
        }
        s3Client().deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build());
    }
}