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

import com.carddemo.service.JobCompletionEvent;
import com.carddemo.service.JobCompletionEventPublisher;
import java.util.Objects;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Publishes the parameter-free application event for one terminal batch execution.
 *
 * <p>The shared job-boundary listener calls this component only after required durable artifacts have
 * been published and the final batch verdict is known. The application event is then consumed by
 * {@code JobCompletionNotificationService}, which is the one and only SNS producer. Keeping this
 * adapter free of SNS dependencies prevents a second listener or transport path from emitting a
 * duplicate notification for the same execution.
 */
@Component
public final class JobCompletionNotificationPublisher implements JobCompletionEventPublisher {

    /** Spring's in-process event boundary. */
    private final ApplicationEventPublisher events;

    /**
     * Creates the event publisher.
     *
     * @param events application event boundary; must not be {@code null}
     */
    public JobCompletionNotificationPublisher(final ApplicationEventPublisher events) {
        this.events = Objects.requireNonNull(events, "events must not be null");
    }

    /**
     * Publishes one immutable terminal snapshot.
     *
     * @param event completed execution snapshot; must not be {@code null}
     */
    @Override
    public void publishCompletion(final JobCompletionEvent event) {
        this.events.publishEvent(Objects.requireNonNull(event, "event must not be null"));
    }
}
