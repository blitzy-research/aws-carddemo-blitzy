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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.NoSuchJobException;

/**
 * Unit tests for {@link BatchJobLaunchService}, the service that owns the two batch operations and the
 * closed inventory they are bounded by.
 *
 * <p><strong>What this file is really guarding.</strong> Four properties, each of which a plausible
 * refactor would break silently. First, that nothing is added to the parameters a launch is issued with,
 * because appending a run identifier is exactly how "launch once" quietly becomes "launch again". Second,
 * that no parameter value is trimmed, padded or reformatted, because the interest run's ten-character
 * parameter becomes the literal leading characters of every transaction identifier that run synthesises
 * and the report range is read as a fixed-width layout. Third, that the framework's three refusal
 * conditions stay distinguishable at the signature, so the boundary above can render each one. Fourth,
 * that a status report carries four scalars and never the framework's exit description, which holds a
 * rendered stack trace.
 *
 * <p>The three framework interfaces are stubbed, so the assertions measure exactly what this service
 * hands them and exactly what it makes of their answers.
 *
 * <p>A pure unit test: no Spring context, no connection, no container.
 *
 * <p>Provenance: the resource definitions of {@code app/csd/CARDDEMO.CSD} establish that no legacy
 * transaction starts a job; read as read-only reference at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * @since 1.0.0
 */
@DisplayName("BatchJobLaunchService :: nine names, one launch, one status, nothing added")
final class BatchJobLaunchServiceTest {

    /** A stable name the stubbed registry resolves. */
    private static final String JOB_NAME = BatchJobCatalog.POST_TRANSACTION_JOB_NAME;

    /** The execution identifier the stubbed launch answers with. */
    private static final Long EXECUTION_ID = 8813L;

    /** A parameter value with surrounding spaces, used to prove nothing is trimmed. */
    private static final String UNTRIMMED_VALUE = " 2022-07-19 ";

    /** Registry resolving a stable name to a registered job. */
    private JobRegistry jobRegistry;

    /** Guarded launch boundary implemented by the batch tier. */
    private BatchLaunchGateway batchLaunchGateway;

    /** Metadata reader one execution is reported from. */
    private JobExplorer jobExplorer;

    /** The service under test. */
    private BatchJobLaunchService service;

    /** Assembles the service over the three stubbed framework interfaces. */
    @BeforeEach
    void setUp() {
        jobRegistry = mock(JobRegistry.class);
        batchLaunchGateway = mock(BatchLaunchGateway.class);
        jobExplorer = mock(JobExplorer.class);
        service = new BatchJobLaunchService(jobRegistry, batchLaunchGateway, jobExplorer);
    }

    /**
     * An execution of the given job carrying the given status and exit code.
     *
     * @param jobName  the job the execution belongs to, or {@code null} for an execution with no instance
     * @param status   the batch status to report, which may be {@code null}
     * @param exitCode the exit status to report, which may be {@code null}
     * @return the execution
     */
    private static JobExecution executionOf(final String jobName, final BatchStatus status,
            final ExitStatus exitCode) {
        final JobExecution execution = mock(JobExecution.class);
        when(execution.getJobInstance())
                .thenReturn(jobName == null ? null : new JobInstance(1L, jobName));
        when(execution.getStatus()).thenReturn(status);
        when(execution.getExitStatus()).thenReturn(exitCode);
        return execution;
    }

    @Nested
    @DisplayName("Construction and shape")
    class ConstructionAndShape {

        @Test
        @DisplayName("takes every collaborator through the constructor and refuses a missing one")
        void refusesAMissingCollaborator() {
            assertThatNullPointerException().isThrownBy(() ->
                    new BatchJobLaunchService(null, batchLaunchGateway, jobExplorer));
            assertThatNullPointerException().isThrownBy(() ->
                    new BatchJobLaunchService(jobRegistry, null, jobExplorer));
            assertThatNullPointerException().isThrownBy(() ->
                    new BatchJobLaunchService(jobRegistry, batchLaunchGateway, null));
        }

        @Test
        @DisplayName("takes three collaborators and no operator, because the delivered surface offers no "
                + "repeat and no resume for an operator to serve")
        void takesThreeCollaboratorsAndNoOperator() {
            assertThat(BatchJobLaunchService.class.getDeclaredConstructors()).hasSize(1);
            assertThat(BatchJobLaunchService.class.getDeclaredConstructors()[0].getParameterTypes())
                    .as("a collaborator held for an operation nobody can reach is not a dependency")
                    .containsExactly(JobRegistry.class, BatchLaunchGateway.class, JobExplorer.class);
            assertThat(Arrays.stream(BatchJobLaunchService.class.getDeclaredMethods())
                    .map(Method::getName)
                    .toList())
                    .as("neither the repeat nor the resume seam survives, so neither can acquire a "
                            + "caller by accident")
                    .doesNotContain("startNextInstance", "restart", "registeredNameOf");
        }

        @Test
        @DisplayName("holds only final fields, so the singleton carries no mutable state")
        void holdsOnlyFinalFields() {
            for (final Field field : BatchJobLaunchService.class.getDeclaredFields()) {
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("field %s must be final", field.getName())
                        .isTrue();
            }
        }
    }

    @Nested
    @DisplayName("The closed inventory")
    class TheClosedInventory {

        @Test
        @DisplayName("is the catalogue's nine names, unmodifiable, and the gate over it answers a name "
                + "without touching the framework")
        void isTheCataloguesNineNames() throws NoSuchJobException {
            assertThat(service.launchableJobNames())
                    .isEqualTo(BatchJobCatalog.LAUNCHABLE_JOB_NAMES)
                    .hasSize(9)
                    .isUnmodifiable();

            assertThat(service.launchable(JOB_NAME)).isTrue();
            assertThat(service.launchable("anotherJob")).isFalse();
            assertThat(service.launchable(null)).isFalse();
            verify(jobRegistry, never()).getJob(anyString());
        }
    }

    @Nested
    @DisplayName("Launching")
    class Launching {

        @Test
        @DisplayName("hands the framework exactly the names and values supplied, adding nothing, so a "
                + "repeated launch is the same job identity")
        void handsTheFrameworkExactlyWhatWasSupplied() throws Exception {
            final String jobName = BatchJobCatalog.TRANSACTION_REPORT_JOB_NAME;
            final Job job = mock(Job.class);
            when(jobRegistry.getJob(jobName)).thenReturn(job);
            when(batchLaunchGateway.start(eq(job), anyMap())).thenReturn(EXECUTION_ID);

            final Map<String, String> supplied = new LinkedHashMap<>();
            supplied.put(BatchJobCatalog.REPORT_START_DATE_PARAMETER, "2022-07-01");
            supplied.put(BatchJobCatalog.REPORT_END_DATE_PARAMETER, UNTRIMMED_VALUE);

            assertThat(service.launch(jobName, supplied)).isEqualTo(EXECUTION_ID);

            final ArgumentCaptor<Map<String, String>> handed = ArgumentCaptor.captor();
            verify(batchLaunchGateway).start(eq(job), handed.capture());
            assertThat(handed.getValue())
                    .as("the service hands the guard only caller-owned names and values")
                    .hasSize(2);
            assertThat(handed.getValue().get(BatchJobCatalog.REPORT_START_DATE_PARAMETER))
                    .isEqualTo("2022-07-01");
            assertThat(handed.getValue().get(BatchJobCatalog.REPORT_END_DATE_PARAMETER))
                    .as("a padded value reaches the framework padded")
                    .isEqualTo(UNTRIMMED_VALUE);
        }

        @Test
        @DisplayName("launches with no parameters at all when none is supplied")
        void launchesWithNoParametersWhenNoneIsSupplied() throws Exception {
            final Job job = mock(Job.class);
            when(jobRegistry.getJob(JOB_NAME)).thenReturn(job);
            when(batchLaunchGateway.start(eq(job), anyMap())).thenReturn(EXECUTION_ID);

            assertThat(service.launch(JOB_NAME, null)).isEqualTo(EXECUTION_ID);
            assertThat(service.launch(JOB_NAME, Map.of())).isEqualTo(EXECUTION_ID);

            final ArgumentCaptor<Map<String, String>> handed = ArgumentCaptor.captor();
            verify(batchLaunchGateway, org.mockito.Mockito.times(2))
                    .start(eq(job), handed.capture());
            assertThat(handed.getAllValues()).allSatisfy(parameters ->
                    assertThat(parameters.isEmpty()).isTrue());
        }

        @Test
        @DisplayName("builds a fresh carrier per launch, so no launch can observe or alter another's "
                + "parameters")
        void buildsAFreshCarrierPerLaunch() throws Exception {
            final String jobName = BatchJobCatalog.TRANSACTION_REPORT_JOB_NAME;
            final Job job = mock(Job.class);
            when(jobRegistry.getJob(jobName)).thenReturn(job);
            when(batchLaunchGateway.start(eq(job), anyMap())).thenReturn(EXECUTION_ID);

            service.launch(jobName,
                    Map.of(BatchJobCatalog.REPORT_START_DATE_PARAMETER, "2022-07-01"));
            service.launch(jobName,
                    Map.of(BatchJobCatalog.REPORT_END_DATE_PARAMETER, "2022-07-31"));

            final ArgumentCaptor<Map<String, String>> handed = ArgumentCaptor.captor();
            verify(batchLaunchGateway, org.mockito.Mockito.times(2))
                    .start(eq(job), handed.capture());
            assertThat(handed.getAllValues().get(0))
                    .containsOnlyKeys(BatchJobCatalog.REPORT_START_DATE_PARAMETER);
            assertThat(handed.getAllValues().get(1))
                    .containsOnlyKeys(BatchJobCatalog.REPORT_END_DATE_PARAMETER);
            assertThat(handed.getAllValues().get(0))
                    .isNotSameAs(handed.getAllValues().get(1));
        }

        @Test
        @DisplayName("propagates each guarded-launch refusal unchanged, so the boundary above can "
                + "translate the closed outcomes")
        void propagatesEachRefusalUnchanged() throws Exception {
            final Job job = mock(Job.class);
            when(jobRegistry.getJob(JOB_NAME)).thenReturn(job);
            for (final BatchLaunchGateway.RejectionReason reason
                    : BatchLaunchGateway.RejectionReason.values()) {
                final BatchLaunchGateway.LaunchRejectedException refusal =
                        new BatchLaunchGateway.LaunchRejectedException(reason, null);
                when(batchLaunchGateway.start(eq(job), anyMap())).thenThrow(refusal);

                assertThatExceptionOfType(BatchLaunchGateway.LaunchRejectedException.class)
                        .isThrownBy(() -> service.launch(JOB_NAME, Map.of()))
                        .isSameAs(refusal);
            }
        }
    }

    @Nested
    @DisplayName("Reporting one execution")
    class ReportingOneExecution {

        @Test
        @DisplayName("carries the identifier, the stable name, the status and the exit code, and reads "
                + "the exit description never")
        void carriesFourScalarsAndNoDescription() {
            final JobExecution execution =
                    executionOf(JOB_NAME, BatchStatus.COMPLETED, ExitStatus.COMPLETED);
            when(jobExplorer.getJobExecution(EXECUTION_ID)).thenReturn(execution);

            final BatchJobLaunchService.JobExecutionReport report = service.readExecution(EXECUTION_ID);

            assertThat(report).isNotNull();
            assertThat(report.executionId()).isEqualTo(EXECUTION_ID);
            assertThat(report.jobName()).isEqualTo(JOB_NAME);
            assertThat(report.status()).isEqualTo(BatchStatus.COMPLETED.name());
            assertThat(report.exitCode()).isEqualTo(ExitStatus.COMPLETED.getExitCode());
            verify(execution, never()).getJobParameters();
        }

        @Test
        @DisplayName("falls back to the framework's own unknown status and unknown exit code rather than "
                + "inventing either, when the metadata records neither")
        void fallsBackToTheFrameworksUnknownValues() {
            final JobExecution execution = executionOf(JOB_NAME, null, null);
            when(jobExplorer.getJobExecution(EXECUTION_ID)).thenReturn(execution);

            final BatchJobLaunchService.JobExecutionReport report = service.readExecution(EXECUTION_ID);

            assertThat(report).isNotNull();
            assertThat(report.status()).isEqualTo(BatchStatus.UNKNOWN.name());
            assertThat(report.exitCode()).isEqualTo(ExitStatus.UNKNOWN.getExitCode());
        }

        @Test
        @DisplayName("uses the framework's unknown exit code when the exit status carries no code at all")
        void usesTheUnknownExitCodeForAnEmptyExitStatus() {
            final JobExecution execution =
                    executionOf(JOB_NAME, BatchStatus.FAILED, new ExitStatus(null));
            when(jobExplorer.getJobExecution(EXECUTION_ID)).thenReturn(execution);

            final BatchJobLaunchService.JobExecutionReport report = service.readExecution(EXECUTION_ID);

            assertThat(report).isNotNull();
            assertThat(report.status()).isEqualTo(BatchStatus.FAILED.name());
            assertThat(report.exitCode()).isEqualTo(ExitStatus.UNKNOWN.getExitCode());
        }

        @Test
        @DisplayName("answers nothing for an absent execution, an execution with no instance and an "
                + "execution outside the closed inventory, so the three cannot be told apart")
        void answersNothingForEveryUnreportableCase() {
            final JobExecution withoutInstance =
                    executionOf(null, BatchStatus.COMPLETED, ExitStatus.COMPLETED);
            final JobExecution outsideInventory =
                    executionOf("someOtherJob", BatchStatus.COMPLETED, ExitStatus.COMPLETED);
            when(jobExplorer.getJobExecution(1L)).thenReturn(null);
            when(jobExplorer.getJobExecution(2L)).thenReturn(withoutInstance);
            when(jobExplorer.getJobExecution(3L)).thenReturn(outsideInventory);

            assertThat(service.readExecution(1L)).isNull();
            assertThat(service.readExecution(2L)).isNull();
            assertThat(service.readExecution(3L)).isNull();
        }
    }
}
