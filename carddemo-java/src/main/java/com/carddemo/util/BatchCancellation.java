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
package com.carddemo.util;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobInterruptedException;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.scope.context.ChunkContext;

/**
 * Converts Spring Batch stop state and thread interruption into one cooperative cancellation probe.
 *
 * <p>The helper lives below both batch packages so job configurations and step implementations can use
 * the same stop semantics without creating a package cycle between {@code batch} and {@code batch.step}.
 */
public final class BatchCancellation {

    private static final String STOP_MESSAGE = "the batch step received a cooperative stop request";

    private BatchCancellation() {
        throw new AssertionError("BatchCancellation is a static utility");
    }

    /**
     * Creates a stop probe for the step execution carried by a chunk context.
     *
     * @param chunkContext the current chunk context
     * @return a probe that observes interruption and Spring Batch stop state
     */
    public static BooleanSupplier requestedBy(final ChunkContext chunkContext) {
        Objects.requireNonNull(chunkContext, "chunkContext must not be null");
        return requestedBy(chunkContext.getStepContext().getStepExecution());
    }

    /**
     * Creates a stop probe for one step execution.
     *
     * @param stepExecution the current step execution
     * @return a probe that observes interruption and Spring Batch stop state
     */
    public static BooleanSupplier requestedBy(final StepExecution stepExecution) {
        Objects.requireNonNull(stepExecution, "stepExecution must not be null");
        return () -> Thread.currentThread().isInterrupted()
                || stepExecution.isTerminateOnly()
                || stepExecution.getStatus() == BatchStatus.STOPPING
                || stepExecution.getStatus() == BatchStatus.STOPPED;
    }

    /**
     * Raises the internal cancellation signal when a cooperative stop has been requested.
     *
     * @param stopRequested the probe to evaluate
     * @throws CancellationException when the probe reports a stop
     */
    public static void checkpoint(final BooleanSupplier stopRequested) {
        Objects.requireNonNull(stopRequested, "stopRequested must not be null");
        if (stopRequested.getAsBoolean()) {
            throw new CancellationException(STOP_MESSAGE);
        }
    }

    /**
     * Converts the internal unchecked signal to Spring Batch's checked interruption contract.
     *
     * @param stopped the cancellation signal to preserve as the cause
     * @return a checked interruption carrying the stopped batch status
     */
    public static JobInterruptedException interrupted(final CancellationException stopped) {
        Objects.requireNonNull(stopped, "stopped must not be null");
        final JobInterruptedException interrupted =
                new JobInterruptedException(STOP_MESSAGE, BatchStatus.STOPPED);
        interrupted.initCause(stopped);
        return interrupted;
    }
}
