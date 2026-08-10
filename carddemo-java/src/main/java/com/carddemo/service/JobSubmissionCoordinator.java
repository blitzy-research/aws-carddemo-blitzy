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
     * <p><strong>Waiting for the boundary is bounded, and an expired wait is not fatal.</strong> An
     * implementation may block a caller while another submission holds the boundary, but must not block
     * without limit: the caller is an online request thread, and an unbounded wait makes one stuck holder
     * able to consume every request thread and every pooled connection behind it. A wait that expires
     * raises {@link CoordinationFailure}, which the calling service reports as a refused submission
     * carrying zero cards published - the migrated form of the legacy queue's ignore-on-error contract.
     *
     * <p><strong>Releasing the boundary is not evidence about the submission.</strong> Once the supplied
     * work has published cards, those messages exist and no coordination operation can retract them. An
     * implementation that fails to release its boundary afterwards must therefore report that fault as an
     * operational alert and <em>return the outcome the work produced</em>. It must not raise
     * {@link CoordinationFailure}, because a caller reading that would report zero cards published for a
     * job stream that is queued and about to run, and the natural response - resubmitting - runs the job
     * twice.
     *
     * @param  submission the validated publication work; must not be {@code null}
     * @return the publication outcome; never {@code null}
     * @throws CoordinationFailure when the shared coordination boundary cannot be acquired, or fails
     *                            before the supplied work produced an outcome
     */
    JobSubmissionService.SubmissionResult serialize(
            Supplier<JobSubmissionService.SubmissionResult> submission);

    /**
     * Reports that the deployment-wide serialization boundary could not be established, and that
     * consequently <strong>no card reached the queue</strong>.
     *
     * <p>The second half of that sentence is the load-bearing half. This exception is a caller's licence
     * to report zero cards published, so it is raised only while that remains true: a boundary that could
     * not be acquired, or that failed before the guarded work produced an outcome. A boundary that could
     * not be <em>released</em> after the work completed is reported by its implementation as an
     * operational alert instead, because the messages exist either way.
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
