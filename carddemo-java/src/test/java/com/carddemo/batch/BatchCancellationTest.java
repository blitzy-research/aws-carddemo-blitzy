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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobInterruptedException;
import org.springframework.batch.core.StepExecution;

import com.carddemo.util.BatchCancellation;

final class BatchCancellationTest {

    @Test
    void observesFrameworkStopStateWithoutClearingThreadInterruption() {
        final StepExecution execution = stepExecution();
        final BooleanSupplier requested = BatchCancellation.requestedBy(execution);

        assertThat(requested.getAsBoolean()).isFalse();
        execution.setTerminateOnly();
        assertThat(requested.getAsBoolean()).isTrue();
    }

    @Test
    void checkpointAndTranslationProduceAControlledStoppedInterruption() {
        final CancellationException stopped = org.junit.jupiter.api.Assertions.assertThrows(
                CancellationException.class,
                () -> BatchCancellation.checkpoint(() -> true));

        final JobInterruptedException interrupted = BatchCancellation.interrupted(stopped);

        assertThat(interrupted.getStatus()).isEqualTo(BatchStatus.STOPPED);
        assertThat(interrupted).hasCause(stopped);
    }

    @Test
    void rejectsAbsentCollaborators() {
        assertThatThrownBy(() -> BatchCancellation.requestedBy((StepExecution) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> BatchCancellation.checkpoint(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> BatchCancellation.interrupted(null))
                .isInstanceOf(NullPointerException.class);
    }

    private static StepExecution stepExecution() {
        final JobInstance instance = new JobInstance(1L, "job");
        final JobExecution jobExecution = new JobExecution(instance, 2L, null);
        return new StepExecution("step", jobExecution, 3L);
    }
}
