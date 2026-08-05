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

import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.carddemo.service.JobCompletionEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Verifies the single in-process bridge between the final batch verdict and the SNS listener.
 */
@DisplayName("JobCompletionNotificationPublisher - one terminal snapshot becomes one application event")
final class JobCompletionNotificationPublisherTest {

    /** Creates the test class. */
    JobCompletionNotificationPublisherTest() {
    }

    @Test
    @DisplayName("one completion snapshot is delegated once to the application event boundary")
    void completionIsPublishedExactlyOnce() {
        final ApplicationEventPublisher events = mock();
        final JobCompletionNotificationPublisher publisher =
                new JobCompletionNotificationPublisher(events);
        final JobCompletionEvent event = new JobCompletionEvent(
                "transactionReportJob", 11L, 22L, BatchStatus.COMPLETED,
                "COMPLETED", 1, null, null);

        publisher.publishCompletion(event);

        verify(events).publishEvent(event);
    }

    @Test
    @DisplayName("the event boundary and the completion snapshot are both mandatory")
    void requiredValuesCannotBeAbsent() {
        assertThatNullPointerException()
                .isThrownBy(() -> new JobCompletionNotificationPublisher(null))
                .withMessage("events must not be null");

        final JobCompletionNotificationPublisher publisher =
                new JobCompletionNotificationPublisher(mock());
        assertThatNullPointerException()
                .isThrownBy(() -> publisher.publishCompletion(null))
                .withMessage("event must not be null");
    }
}