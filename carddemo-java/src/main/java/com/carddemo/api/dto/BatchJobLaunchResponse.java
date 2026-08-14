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
 * What a started batch job answers with: the execution the framework assigned, and the stable name it was
 * started under. Two members, and no third.
 *
 * <p>Typed rather than a map, so the published interface description carries the member names and their
 * types instead of an open object a client has to guess at. The two members are exactly what a caller
 * needs to ask after the run through {@link BatchJobExecutionResponse}; nothing about the parameters, the
 * steps, the schedule or any framework object is exposed, because none of that is a caller's business and
 * some of it carries internal detail.
 *
 * <p>The job name is always one of the nine the launch surface will start, never the value that arrived,
 * so a caller cannot see an arbitrary string reflected back.
 *
 * @param executionId the execution identifier the framework assigned, never {@code null}
 * @param jobName the stable name the job was started under, never {@code null}
 * @since 1.0.0
 */
@Schema(description = "The execution a batch job launch created, and the stable job name it was started "
        + "under.")
public record BatchJobLaunchResponse(

        @Schema(description = "Identifier of the execution the framework assigned. Ask after the run "
                + "with it.")
        Long executionId,

        @Schema(description = "Stable name the job was started under, always one of the nine registered "
                + "jobs.")
        String jobName) {
}
