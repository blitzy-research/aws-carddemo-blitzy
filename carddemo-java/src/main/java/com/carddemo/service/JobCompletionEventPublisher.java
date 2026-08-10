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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.service;

/**
 * Service-owned port for publishing one terminal batch-execution event.
 *
 * <p>The batch adapter implements this contract and the configuration layer depends only on the
 * port, preserving a one-way package graph while the notification service remains the sole SNS
 * producer.
 */
public interface JobCompletionEventPublisher {

    /**
     * Publishes one immutable terminal execution snapshot.
     *
     * @param event completed execution snapshot
     */
    void publishCompletion(JobCompletionEvent event);
}
