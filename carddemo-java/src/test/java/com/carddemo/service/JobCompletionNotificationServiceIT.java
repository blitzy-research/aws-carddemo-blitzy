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

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.support.AbstractLocalStackIT;
import io.awspring.cloud.sns.core.SnsTemplate;
import io.micrometer.observation.ObservationRegistry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.batch.core.BatchStatus;
import software.amazon.awssdk.services.sns.model.CreateTopicRequest;
import software.amazon.awssdk.services.sns.model.DeleteTopicRequest;
import software.amazon.awssdk.services.sns.model.SetSubscriptionAttributesRequest;
import software.amazon.awssdk.services.sns.model.SubscribeRequest;
import software.amazon.awssdk.services.sns.model.UnsubscribeRequest;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * Exercises the completion producer through a real LocalStack SNS topic and SQS subscription.
 */
@DisplayName("job-completion notification, verified through real SNS fan-out")
class JobCompletionNotificationServiceIT extends AbstractLocalStackIT {

    private static final LocalDateTime STARTED = LocalDateTime.of(2022, 7, 19, 10, 11, 12);

    private static final LocalDateTime ENDED = LocalDateTime.of(2022, 7, 19, 10, 12, 13);

    private String topicArn;

    private String topicName;

    private String queueUrl;

    private String subscriptionArn;

    private JobCompletionNotificationService service;

    @BeforeEach
    void createFanOut() {
        final String suffix = UUID.randomUUID().toString();
        this.topicName = "carddemo-job-completion-" + suffix;
        this.topicArn = snsClient().createTopic(CreateTopicRequest.builder()
                        .name(this.topicName)
                        .build())
                .topicArn();
        this.queueUrl = sqsAsyncClient().createQueue(CreateQueueRequest.builder()
                        .queueName("carddemo-job-completion-" + suffix)
                        .build())
                .join()
                .queueUrl();
        final String queueArn = sqsAsyncClient().getQueueAttributes(
                        GetQueueAttributesRequest.builder()
                                .queueUrl(this.queueUrl)
                                .attributeNames(QueueAttributeName.QUEUE_ARN)
                                .build())
                .join()
                .attributes()
                .get(QueueAttributeName.QUEUE_ARN);
        this.subscriptionArn = snsClient().subscribe(SubscribeRequest.builder()
                        .topicArn(this.topicArn)
                        .protocol("sqs")
                        .endpoint(queueArn)
                        .build())
                .subscriptionArn();
        snsClient().setSubscriptionAttributes(SetSubscriptionAttributesRequest.builder()
                .subscriptionArn(this.subscriptionArn)
                .attributeName("RawMessageDelivery")
                .attributeValue("true")
                .build());
        this.service = new JobCompletionNotificationService(
                new SnsTemplate(snsClient()), this.topicName, ObservationRegistry.NOOP);
    }

    @AfterEach
    void removeFanOut() {
        if (this.subscriptionArn != null) {
            snsClient().unsubscribe(UnsubscribeRequest.builder()
                    .subscriptionArn(this.subscriptionArn)
                    .build());
        }
        if (this.topicArn != null) {
            snsClient().deleteTopic(DeleteTopicRequest.builder().topicArn(this.topicArn).build());
        }
        if (this.queueUrl != null) {
            sqsAsyncClient().deleteQueue(DeleteQueueRequest.builder().queueUrl(this.queueUrl).build())
                    .join();
        }
    }

    @ParameterizedTest
    @EnumSource(value = BatchStatus.class, names = {"COMPLETED", "FAILED"})
    @DisplayName("fans both successful and failed terminal outcomes out as the bounded raw payload")
    void fansOutTerminalOutcomes(final BatchStatus status) {
        final JobCompletionEvent event = new JobCompletionEvent(
                "postTransactionJob",
                11L,
                22L,
                status,
                status.name(),
                3,
                STARTED,
                ENDED);

        assertThat(this.service.publishCompletion(event)).isTrue();

        final List<Message> messages = drainQueue(this.queueUrl, 1);
        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().body())
                .isEqualTo("{\"schemaVersion\":1,"
                        + "\"eventType\":\"carddemo.batch.job-completion\","
                        + "\"jobName\":\"postTransactionJob\","
                        + "\"jobInstanceId\":11,"
                        + "\"jobExecutionId\":22,"
                        + "\"status\":\"" + status.name() + "\","
                        + "\"exitCode\":\"" + status.name() + "\","
                        + "\"stepsExecuted\":3,"
                        + "\"startedAt\":\"2022-07-19T10:11:12\","
                        + "\"endedAt\":\"2022-07-19T10:12:13\"}");
    }
}
