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
package com.carddemo.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the two closed batch response carriers at the typed HTTP boundary.
 */
@DisplayName("typed batch responses expose only the published launch and execution fields")
final class BatchJobResponseTest {

    /** Creates the test class. */
    BatchJobResponseTest() {
    }

    @Test
    @DisplayName("launch and execution responses retain their four published values by record access")
    void responsesRetainOnlyTheirPublishedValues() {
        final BatchJobLaunchResponse launch =
                new BatchJobLaunchResponse(41L, "transactionReportJob");
        final BatchJobExecutionResponse execution = new BatchJobExecutionResponse(
                41L, "transactionReportJob", "COMPLETED", "COMPLETED");

        assertThat(launch.executionId()).isEqualTo(41L);
        assertThat(launch.jobName()).isEqualTo("transactionReportJob");
        assertThat(execution.executionId()).isEqualTo(41L);
        assertThat(execution.jobName()).isEqualTo("transactionReportJob");
        assertThat(execution.status()).isEqualTo("COMPLETED");
        assertThat(execution.exitCode()).isEqualTo("COMPLETED");
    }
}