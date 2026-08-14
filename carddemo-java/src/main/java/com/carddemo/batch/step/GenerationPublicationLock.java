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

import java.util.List;

/**
 * Serializes the publication of one or more generation bases so that no two publications can interleave
 * their reads and their deletions of the same base.
 *
 * <h2>The interleaving this exists to prevent</h2>
 *
 * <p>Retention is a read-decide-delete sequence: list the objects beneath a base, sort them by execution
 * identifier, and delete everything past the declared depth. Without mutual exclusion the decision is
 * taken against a set another publication is still changing, which is the classic
 * time-of-check-to-time-of-use defect. Two publications that each upload a generation and then each list a
 * set not yet containing the other's upload both conclude nothing has rolled off, and the base is left one
 * generation deeper than its declared limit - permanently, because the next publication measures depth from
 * the state it inherits. Holding the base for the whole of upload-then-retention removes the window rather
 * than narrowing it.
 *
 * <h2>Why a base and not a job</h2>
 *
 * <p>{@code BatchLaunchCoordinator} already serializes launches, but it keys its lock on the job name,
 * which is the wrong granularity here: two <em>differently named</em> jobs publish to the same logical base
 * - the transaction-backup base is written both by the backup job and by the transaction-report job's
 * unload step - so a per-job lock lets exactly the pair that shares a base run concurrently. The unit of
 * exclusion has to be the thing being counted, which is the base.
 *
 * <h2>Contract</h2>
 *
 * <p>An implementation must hold every named base for the whole of {@code publication}, must release every
 * base however {@code publication} ends, and must acquire multiple bases in an order that does not depend
 * on the caller, so two publications naming the same two bases cannot deadlock by acquiring them in
 * opposite orders. It must not swallow a failure raised by {@code publication}, because the publication
 * boundary decides the job's verdict and cannot do so if the failure is absorbed here.
 *
 * <p>Failure to acquire is a failure to publish, deliberately: publishing without the lock is the defect
 * this interface exists to close, so an implementation that cannot acquire must raise rather than proceed
 * unserialized. Acquisition must also be <strong>bounded</strong> - a publication may wait its turn, but a
 * holder that has stopped making progress must surface as one failed job rather than as a batch tier that
 * never finishes.
 *
 * <p><strong>Failure to release, after {@code publication} has returned normally, is not a failure to
 * publish.</strong> By that point the publication has passed its own commit point - its objects are
 * durable, its fixed-name views name them, and its retention deletions cannot be undone - so a fault in
 * the release path is evidence about the implementation and none about the publication. Such a fault is
 * reported as an operational alert and the method returns normally; the alternative marks FAILED a job
 * whose output is correct, which causes a re-run that publishes the same generations a second time.
 *
 * <p>See {@code docs/decision-log.md} entries DL-181 and DL-304.
 */
@FunctionalInterface
public interface GenerationPublicationLock {

    /**
     * Runs one publication with every named base held.
     *
     * @param logicalBases distinct generation bases the publication touches; must not be {@code null}
     *                     and must contain no {@code null} element. An empty list is permitted and means
     *                     the publication touches no base, in which case the body still runs.
     * @param publication the publication to run while the bases are held; must not be {@code null}
     * @throws RuntimeException propagated unchanged from {@code publication}, or raised by the
     *                          implementation when the bases cannot be acquired. Never raised for a
     *                          release that failed after {@code publication} returned normally.
     */
    void whileHolding(List<String> logicalBases, Runnable publication);
}
