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

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What one batch job execution reports: its identifier, the stable job name, the batch status and the exit
 * code. Four members, and no fifth.
 *
 * <p><strong>What is deliberately absent is the substance of this contract.</strong> The framework's exit
 * <em>description</em> is not a member, because the framework writes a rendered stack trace into it on a
 * failed run and publishing it through a field that looks like a status would leak internals. Neither the
 * parameter set, the step detail, the failure chain nor any framework domain object is a member either.
 *
 * <p>Typed rather than a map so that the published interface description names the four members and their
 * types, and so that a fifth cannot be added by accident on one code path and not another.
 *
 * @param executionId the execution identifier that was asked after, never {@code null}
 * @param jobName the stable job name, always one of the nine the surface owns, never {@code null}
 * @param status the framework's batch status by name, or its own unknown status when none is recorded
 * @param exitCode the framework's exit code, or its own unknown code when none is recorded
 * @since 1.0.0
 */
@Schema(description = "The reported state of one batch job execution: identifier, stable job name, batch "
        + "status and exit code. The framework's exit description, parameter set and step detail are "
        + "deliberately not published.")
public record BatchJobExecutionResponse(

        @Schema(description = "Identifier of the execution being reported.")
        Long executionId,

        @Schema(description = "Stable name of the job the execution belongs to.")
        String jobName,

        @Schema(description = "The framework's batch status by name.")
        String status,

        @Schema(description = "The framework's exit code.")
        String exitCode) {
}
