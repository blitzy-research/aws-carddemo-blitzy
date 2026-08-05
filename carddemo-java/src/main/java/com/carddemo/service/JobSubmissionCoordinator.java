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

import java.io.Serial;
import java.util.function.Supplier;

/**
 * Deployment-wide serialization boundary for one complete job-submission card stream.
 *
 * <p>The legacy transient-data queue was appended by one transaction task at a time. A migrated
 * deployment can run several application replicas, so a lock owned by one JVM cannot preserve that
 * property. Implementations coordinate through infrastructure shared by every replica and hold the
 * coordination boundary from the first card through the transmitted end-of-stream card.
 *
 * <p>The work returns the submission result rather than exposing lock and unlock operations. That
 * shape makes releasing the coordination boundary an implementation responsibility and prevents a
 * caller from returning, throwing or forgetting a cleanup path while the deployment-wide guard is
 * still held.
 */
public interface JobSubmissionCoordinator {

    /**
     * Runs one complete card-stream publication as the only such publication in the deployment.
     *
     * @param  submission the validated publication work; must not be {@code null}
     * @return the publication outcome; never {@code null}
     * @throws CoordinationFailure when the shared coordination boundary cannot be acquired or
     *                            completed
     */
    JobSubmissionService.SubmissionResult serialize(
            Supplier<JobSubmissionService.SubmissionResult> submission);

    /**
     * Reports failure to acquire or complete the deployment-wide serialization boundary.
     */
    final class CoordinationFailure extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * @param message bounded explanation of the coordination operation that failed
         * @param cause infrastructure failure that prevented coordination
         */
        public CoordinationFailure(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
