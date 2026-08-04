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
package com.carddemo.api;

import com.carddemo.batch.BackupTransactionJobConfig;
import com.carddemo.batch.CategoryBalanceReportJobConfig;
import com.carddemo.batch.CombineTransactionsJobConfig;
import com.carddemo.batch.CreateStatementJobConfig;
import com.carddemo.batch.DailyTransactionReadJobConfig;
import com.carddemo.batch.FileProbeJobConfig;
import com.carddemo.batch.InterestCalculationJobConfig;
import com.carddemo.batch.JobParameterValidators;
import com.carddemo.batch.PostTransactionJobConfig;
import com.carddemo.batch.TransactionReportJobConfig;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
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
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobInstanceAlreadyExistsException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.NoSuchJobException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Contract test for {@link BatchJobController}, the on-demand launch and status surface of the nine
 * registered batch jobs.
 *
 * <p><strong>What this proves.</strong> The controller is a boundary over the framework's own batch
 * interfaces, so every assertion here is about the boundary's properties rather than about what any job
 * does - job behaviour belongs to the nine job configurations and to their own suites. Six properties
 * matter and each has a nest below: the delivered operation inventory is exactly two documented
 * operations and nothing else; the launch inventory is closed at nine names read from the configurations
 * that publish them; a launch delegates once, verbatim, and adds nothing that would break idempotency; a
 * refused launch is translated without echoing what was sent; a status answer carries four safe members
 * and never the framework's exit description; and the timers stay bounded whatever a caller sends.
 *
 * <p>The collaborators are the framework's four interfaces, stubbed. Nothing here boots a context, opens a
 * database or launches a real job: what is under test is the adapting, and a real launch is measured by
 * the batch tier's own integration suites.
 */
class BatchJobControllerTest {

    /** An execution identifier a stubbed launch answers with. */
    private static final Long EXECUTION_ID = 4271L;

    /**
     * A parameter value with surrounding spaces, used to prove nothing is trimmed.
     *
     * <p>Two of the parameters the jobs declare are fixed width, and one becomes the leading characters of
     * synthesised identifiers, so a value that arrives padded must reach the framework padded.
     */
    private static final String UNTRIMMED_VALUE = " 2022-07-19 ";

    /** Registry resolving a stable name to a registered job. */
    private JobRegistry jobRegistry;

    /** Launcher the resolved job is started through. */
    private JobOperator jobOperator;

    /** Metadata reader one execution is reported from. */
    private JobExplorer jobExplorer;

    /** A real registry, so the tags a request produces can be read back. */
    private MeterRegistry meterRegistry;

    /** The controller under test. */
    private BatchJobController controller;

    /** Assembles the controller over the four stubbed framework interfaces. */
    @BeforeEach
    void setUp() {
        jobRegistry = mock(JobRegistry.class);
        jobOperator = mock(JobOperator.class);
        jobExplorer = mock(JobExplorer.class);
        meterRegistry = new SimpleMeterRegistry();
        controller = new BatchJobController(jobRegistry, jobOperator, jobExplorer, meterRegistry);
    }

    /**
     * Registers a job under the given name so the registry resolves it.
     *
     * @param jobName the stable job name to register
     * @throws NoSuchJobException never; declared because the stubbed method declares it
     */
    private void registerJob(final String jobName) throws NoSuchJobException {
        final Job job = mock(Job.class);
        when(job.getName()).thenReturn(jobName);
        when(jobRegistry.getJob(jobName)).thenReturn(job);
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

    /**
     * Every handler method the controller maps.
     *
     * @return the mapped methods, in declaration order
     */
    private static List<Method> mappedMethods() {
        final List<Method> mapped = new ArrayList<>();
        for (final Method method : BatchJobController.class.getDeclaredMethods()) {
            if (method.getAnnotation(PostMapping.class) != null
                    || method.getAnnotation(GetMapping.class) != null) {
                mapped.add(method);
            }
        }
        return mapped;
    }

    // ==================================================================================================

    @Nested
    @DisplayName("The delivered operation inventory")
    class TheDeliveredOperationInventory {

        @Test
        @DisplayName("is a REST controller mapped at the batch route, outside the administrative prefix, "
                + "so the surface is scanned and authenticated by the chain's closing rule")
        void isARestControllerMappedAtTheBatchRoute() {
            assertThat(BatchJobController.class.getAnnotation(RestController.class)).isNotNull();
            assertThat(BatchJobController.class.getAnnotation(RequestMapping.class).value())
                    .containsExactly(BatchJobController.BATCH_JOBS_PATH);
            assertThat(BatchJobController.BATCH_JOBS_PATH)
                    .as("a path beneath the administrative prefix would invent a sixth gate")
                    .startsWith("/api/")
                    .doesNotStartWith("/api/admin")
                    .doesNotStartWith("/actuator")
                    .doesNotStartWith("/v3/api-docs");
        }

        @Test
        @DisplayName("is exactly two documented operations - one launch and one status read - so no "
                + "run-everything, schedule, stop, restart, abandon or metadata-management surface exists")
        void isExactlyTwoDocumentedOperations() {
            final List<Method> mapped = mappedMethods();

            assertThat(mapped).hasSize(2);
            assertThat(mapped).allSatisfy(method -> {
                assertThat(method.getAnnotation(Operation.class))
                        .as("an undocumented operation publishes an unlabelled path, %s", method.getName())
                        .isNotNull();
                assertThat(method.getAnnotation(ApiResponses.class)).isNotNull();
            });
            assertThat(mapped).filteredOn(method -> method.getAnnotation(PostMapping.class) != null)
                    .hasSize(1);
            assertThat(mapped).filteredOn(method -> method.getAnnotation(GetMapping.class) != null)
                    .hasSize(1);
            assertThat(mapped).extracting(Method::getName)
                    .containsExactlyInAnyOrder("launchJob", "readJobExecution");
        }

        @Test
        @DisplayName("names no operation that would orchestrate, schedule or manage the jobs, because the "
                + "estate has no master scheduler to translate")
        void namesNoOrchestratingOperation() {
            final List<String> declared = new ArrayList<>();
            for (final Method method : BatchJobController.class.getDeclaredMethods()) {
                declared.add(method.getName().toLowerCase(Locale.ROOT));
            }

            assertThat(declared).noneSatisfy(name -> assertThat(name)
                    .containsAnyOf("runall", "pipeline", "master", "nextjob", "schedule", "stop",
                            "restart", "abandon", "delete"));
        }

        @Test
        @DisplayName("declares no type of its own, so no request record, response record or nested "
                + "carrier was added alongside the two operations")
        void declaresNoTypeOfItsOwn() {
            assertThat(BatchJobController.class.getDeclaredClasses()).isEmpty();
        }

        @Test
        @DisplayName("is a final class whose every field is final, so the singleton carries no mutable "
                + "state and is safe for concurrent use")
        void isFinalAndHoldsOnlyFinalFields() {
            assertThat(Modifier.isFinal(BatchJobController.class.getModifiers())).isTrue();
            for (final Field field : BatchJobController.class.getDeclaredFields()) {
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("field %s must be final", field.getName())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("takes every collaborator through the constructor and refuses a missing one, so no "
                + "field or setter injection can leave one unset")
        void takesEveryCollaboratorThroughTheConstructor() {
            assertThat(BatchJobController.class.getDeclaredConstructors()).hasSize(1);

            assertThatNullPointerException().isThrownBy(() ->
                    new BatchJobController(null, jobOperator, jobExplorer, meterRegistry));
            assertThatNullPointerException().isThrownBy(() ->
                    new BatchJobController(jobRegistry, null, jobExplorer, meterRegistry));
            assertThatNullPointerException().isThrownBy(() ->
                    new BatchJobController(jobRegistry, jobOperator, null, meterRegistry));
            assertThatNullPointerException().isThrownBy(() ->
                    new BatchJobController(jobRegistry, jobOperator, jobExplorer, null));
        }
    }

    // ==================================================================================================

    @Nested
    @DisplayName("The closed launch inventory")
    class TheClosedLaunchInventory {

        @Test
        @DisplayName("holds exactly the nine names the nine job configurations publish, so no literal can "
                + "drift from the name the framework registered")
        void holdsExactlyTheNinePublishedNames() {
            assertThat(BatchJobController.LAUNCHABLE_JOB_NAMES)
                    .containsExactlyInAnyOrder(
                            PostTransactionJobConfig.JOB_NAME,
                            InterestCalculationJobConfig.JOB_NAME,
                            CombineTransactionsJobConfig.JOB_NAME,
                            CreateStatementJobConfig.JOB_NAME,
                            TransactionReportJobConfig.JOB_NAME,
                            BackupTransactionJobConfig.JOB_NAME,
                            CategoryBalanceReportJobConfig.JOB_NAME,
                            FileProbeJobConfig.FILE_PROBE_JOB_NAME,
                            DailyTransactionReadJobConfig.JOB_NAME)
                    .hasSize(9);
        }

        @Test
        @DisplayName("is immutable, so a caller can read the inventory and no caller can widen it")
        void isImmutable() {
            assertThatThrownBy(() -> BatchJobController.LAUNCHABLE_JOB_NAMES.add("anotherJob"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> BatchJobController.LAUNCHABLE_JOB_NAMES
                    .remove(PostTransactionJobConfig.JOB_NAME))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("includes the deliberately unwired daily-read job, so the orphan stays launchable by "
                + "name rather than becoming unreachable code")
        void includesTheUnwiredDailyReadJob() {
            assertThat(BatchJobController.LAUNCHABLE_JOB_NAMES)
                    .contains(DailyTransactionReadJobConfig.JOB_NAME);
        }

        @Test
        @DisplayName("rejects a name outside the inventory as an absent resource and reaches no framework "
                + "call at all, so nothing is resolved and no metadata is written")
        void rejectsANameOutsideTheInventory() {
            assertThatExceptionOfType(RecordNotFoundException.class)
                    .isThrownBy(() -> controller.launchJob("someOtherJob", Map.of()));

            verifyNoInteractions(jobRegistry);
            verifyNoInteractions(jobOperator);
        }

        @Test
        @DisplayName("does not disclose the name it rejected, in the answer or in the carrier's message, "
                + "so a probe learns nothing from a rejection")
        void doesNotDiscloseTheNameItRejected() {
            assertThatThrownBy(() -> controller.launchJob("probe-value", Map.of()))
                    .isInstanceOf(RecordNotFoundException.class)
                    .hasMessageNotContaining("probe-value");
        }
    }

    // ==================================================================================================

    @Nested
    @DisplayName("A launch")
    class ALaunch {

        @Test
        @DisplayName("resolves the name through the registry and starts the job the registry produced, "
                + "answering the execution identifier and the stable job name")
        void resolvesThroughTheRegistryAndStartsTheResolvedJob() throws Exception {
            final String jobName = PostTransactionJobConfig.JOB_NAME;
            registerJob(jobName);
            when(jobOperator.start(eq(jobName), any(Properties.class))).thenReturn(EXECUTION_ID);

            final ResponseEntity<Map<String, Object>> answer =
                    controller.launchJob(jobName, Map.of());

            verify(jobRegistry).getJob(jobName);
            verify(jobOperator).start(eq(jobName), any(Properties.class));
            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(answer.getBody())
                    .containsExactlyInAnyOrderEntriesOf(Map.of(
                            BatchJobController.FIELD_EXECUTION_ID, EXECUTION_ID,
                            BatchJobController.FIELD_JOB_NAME, jobName));
        }

        @Test
        @DisplayName("hands the framework the supplied parameters unchanged and adds nothing, so the same "
                + "request twice is the same job identity and stays idempotent")
        void handsTheSuppliedParametersOverUnchanged() throws Exception {
            final String jobName = InterestCalculationJobConfig.JOB_NAME;
            registerJob(jobName);
            when(jobOperator.start(anyString(), any(Properties.class))).thenReturn(EXECUTION_ID);
            final Map<String, String> supplied = new LinkedHashMap<>();
            supplied.put(JobParameterValidators.INTEREST_PARM_DATE_KEY, "2022071900");
            supplied.put(JobParameterValidators.REPORT_START_DATE_KEY, UNTRIMMED_VALUE);

            controller.launchJob(jobName, supplied);

            final Properties handedOver = capturedLaunchParameters(jobName);
            assertThat(handedOver.stringPropertyNames())
                    .as("a generated run identifier, timestamp or unique value would break idempotency")
                    .containsExactlyInAnyOrder(JobParameterValidators.INTEREST_PARM_DATE_KEY,
                            JobParameterValidators.REPORT_START_DATE_KEY);
            assertThat(handedOver.getProperty(JobParameterValidators.INTEREST_PARM_DATE_KEY))
                    .isEqualTo("2022071900");
            assertThat(handedOver.getProperty(JobParameterValidators.REPORT_START_DATE_KEY))
                    .as("a fixed-width value must arrive exactly as it was sent")
                    .isEqualTo(UNTRIMMED_VALUE);
        }

        @Test
        @DisplayName("launches with no parameters when none were supplied, and tolerates an absent map, "
                + "so a job that declares none needs none")
        void launchesWithNoParametersWhenNoneWereSupplied() throws Exception {
            final String jobName = BackupTransactionJobConfig.JOB_NAME;
            registerJob(jobName);
            when(jobOperator.start(anyString(), any(Properties.class))).thenReturn(EXECUTION_ID);

            controller.launchJob(jobName, null);

            assertThat(capturedLaunchParameters(jobName)).isEmpty();
        }

        @Test
        @DisplayName("translates the framework's refusal of an already-used job identity without echoing "
                + "the job name or the parameters the framework's own message carries")
        void translatesAnAlreadyUsedJobIdentity() throws Exception {
            final String jobName = CreateStatementJobConfig.JOB_NAME;
            registerJob(jobName);
            when(jobOperator.start(anyString(), any(Properties.class)))
                    .thenThrow(new JobInstanceAlreadyExistsException(
                            "Cannot start a job instance that already exists with name=" + jobName
                                    + " and parameters={secretValue}"));

            assertThatThrownBy(() -> controller.launchJob(jobName,
                    Map.of(JobParameterValidators.REPORT_START_DATE_KEY, "secretValue")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageNotContaining("secretValue")
                    .hasMessageNotContaining(jobName);
        }

        @Test
        @DisplayName("translates the job's own parameter refusal without restating the rule the job's "
                + "validator owns")
        void translatesTheJobsOwnParameterRefusal() throws Exception {
            final String jobName = FileProbeJobConfig.FILE_PROBE_JOB_NAME;
            registerJob(jobName);
            when(jobOperator.start(anyString(), any(Properties.class)))
                    .thenThrow(new JobParametersInvalidException("rejected mode=probeValue"));

            assertThatThrownBy(() -> controller.launchJob(jobName,
                    Map.of(JobParameterValidators.FILE_PROBE_MODE_KEY, "probeValue")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageNotContaining("probeValue");
        }

        @Test
        @DisplayName("reports an allow-listed job the registry does not hold as a wiring fault rather "
                + "than as a caller fault, because the name passed the inventory")
        void reportsAnUnregisteredAllowListedJobAsAWiringFault() throws Exception {
            final String jobName = CombineTransactionsJobConfig.JOB_NAME;
            when(jobRegistry.getJob(jobName)).thenThrow(new NoSuchJobException(jobName));

            assertThatThrownBy(() -> controller.launchJob(jobName, Map.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasCauseInstanceOf(NoSuchJobException.class);
            verify(jobOperator, never()).start(anyString(), any(Properties.class));
        }

        @Test
        @DisplayName("reports the same wiring fault when the launcher itself cannot find the job the "
                + "registry had just resolved, so the race the framework declares is not misreported")
        void reportsTheSameWiringFaultWhenTheLauncherCannotFindTheJob() throws Exception {
            final String jobName = CategoryBalanceReportJobConfig.JOB_NAME;
            registerJob(jobName);
            when(jobOperator.start(anyString(), any(Properties.class)))
                    .thenThrow(new NoSuchJobException(jobName));

            assertThatThrownBy(() -> controller.launchJob(jobName, Map.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasCauseInstanceOf(NoSuchJobException.class);
        }

        @Test
        @DisplayName("skips a parameter the carrier cannot hold rather than failing the request, because "
                + "no job declares a parameter that could arrive without a name or a value")
        void skipsAParameterTheCarrierCannotHold() throws Exception {
            final String jobName = TransactionReportJobConfig.JOB_NAME;
            registerJob(jobName);
            when(jobOperator.start(anyString(), any(Properties.class))).thenReturn(EXECUTION_ID);
            final Map<String, String> supplied = new LinkedHashMap<>();
            supplied.put(JobParameterValidators.REPORT_START_DATE_KEY, "2022-07-01");
            supplied.put(JobParameterValidators.REPORT_END_DATE_KEY, null);
            supplied.put(null, "2022-07-31");

            controller.launchJob(jobName, supplied);

            assertThat(capturedLaunchParameters(jobName).stringPropertyNames())
                    .containsExactly(JobParameterValidators.REPORT_START_DATE_KEY);
        }

        /**
         * Reads back the parameter carrier the controller handed to the framework.
         *
         * @param jobName the name the launch was issued against
         * @return the carrier as it was handed over
         * @throws Exception if the stubbed launch signature reports a failure
         */
        private Properties capturedLaunchParameters(final String jobName) throws Exception {
            final ArgumentCaptor<Properties> captor = ArgumentCaptor.forClass(Properties.class);
            verify(jobOperator).start(eq(jobName), captor.capture());
            return captor.getValue();
        }
    }

    // ==================================================================================================

    @Nested
    @DisplayName("A status read")
    class AStatusRead {

        @Test
        @DisplayName("carries the execution identifier, the stable job name, the batch status and the exit "
                + "code, and carries nothing else")
        void carriesFourSafeMembersAndNothingElse() {
            final String jobName = TransactionReportJobConfig.JOB_NAME;
            final JobExecution execution =
                    executionOf(jobName, BatchStatus.COMPLETED, ExitStatus.COMPLETED);
            when(jobExplorer.getJobExecution(EXECUTION_ID)).thenReturn(execution);

            final ResponseEntity<Map<String, Object>> answer =
                    controller.readJobExecution(EXECUTION_ID);

            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(answer.getBody()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    BatchJobController.FIELD_EXECUTION_ID, EXECUTION_ID,
                    BatchJobController.FIELD_JOB_NAME, jobName,
                    BatchJobController.FIELD_STATUS, BatchStatus.COMPLETED.name(),
                    BatchJobController.FIELD_EXIT_CODE, ExitStatus.COMPLETED.getExitCode()));
        }

        @Test
        @DisplayName("never reads the framework's exit description, because it carries a rendered stack "
                + "trace on a failed run")
        void neverReadsTheExitDescription() {
            final ExitStatus failedWithTrace = ExitStatus.FAILED
                    .addExitDescription("java.lang.IllegalStateException: internal detail at line 42");
            final JobExecution execution = executionOf(CategoryBalanceReportJobConfig.JOB_NAME,
                    BatchStatus.FAILED, failedWithTrace);
            when(jobExplorer.getJobExecution(EXECUTION_ID)).thenReturn(execution);

            final Map<String, Object> body = controller.readJobExecution(EXECUTION_ID).getBody();

            assertThat(body).containsEntry(BatchJobController.FIELD_EXIT_CODE,
                    ExitStatus.FAILED.getExitCode());
            assertThat(body.values()).noneSatisfy(value -> assertThat(String.valueOf(value))
                    .contains("internal detail"));
            verify(execution, never()).getJobParameters();
        }

        @Test
        @DisplayName("answers an unknown status and an unknown exit code with the framework's own unknown "
                + "values, so a member is never absent from the answer")
        void answersUnknownStatusAndExitCodeWithTheFrameworksUnknownValues() {
            final JobExecution execution =
                    executionOf(CreateStatementJobConfig.JOB_NAME, null, null);
            when(jobExplorer.getJobExecution(EXECUTION_ID)).thenReturn(execution);

            final Map<String, Object> body = controller.readJobExecution(EXECUTION_ID).getBody();

            assertThat(body).containsEntry(BatchJobController.FIELD_STATUS, BatchStatus.UNKNOWN.name())
                    .containsEntry(BatchJobController.FIELD_EXIT_CODE,
                            ExitStatus.UNKNOWN.getExitCode());
        }

        @Test
        @DisplayName("answers an exit status carrying no code with the framework's own unknown code, so a "
                + "member is never null in the answer")
        void answersAnExitStatusWithNoCodeWithTheUnknownCode() {
            final JobExecution execution = executionOf(FileProbeJobConfig.FILE_PROBE_JOB_NAME,
                    BatchStatus.COMPLETED, new ExitStatus(null, ""));
            when(jobExplorer.getJobExecution(EXECUTION_ID)).thenReturn(execution);

            assertThat(controller.readJobExecution(EXECUTION_ID).getBody())
                    .containsEntry(BatchJobController.FIELD_EXIT_CODE, ExitStatus.UNKNOWN.getExitCode());
        }

        @Test
        @DisplayName("answers an execution the metadata does not hold as an absent resource")
        void answersAnAbsentExecutionAsAnAbsentResource() {
            when(jobExplorer.getJobExecution(EXECUTION_ID)).thenReturn(null);

            assertThatExceptionOfType(RecordNotFoundException.class)
                    .isThrownBy(() -> controller.readJobExecution(EXECUTION_ID));
        }

        @Test
        @DisplayName("answers an execution of a job outside the inventory as absent, so this is a status "
                + "view over nine jobs rather than a reader of every execution the framework holds")
        void answersAnExecutionOfAnUnownedJobAsAbsent() {
            final JobExecution execution =
                    executionOf("someOtherJob", BatchStatus.COMPLETED, ExitStatus.COMPLETED);
            when(jobExplorer.getJobExecution(EXECUTION_ID)).thenReturn(execution);

            assertThatExceptionOfType(RecordNotFoundException.class)
                    .isThrownBy(() -> controller.readJobExecution(EXECUTION_ID));
        }

        @Test
        @DisplayName("answers an execution carrying no instance as absent rather than reporting a job it "
                + "cannot name")
        void answersAnInstancelessExecutionAsAbsent() {
            final JobExecution execution =
                    executionOf(null, BatchStatus.COMPLETED, ExitStatus.COMPLETED);
            when(jobExplorer.getJobExecution(EXECUTION_ID)).thenReturn(execution);

            assertThatExceptionOfType(RecordNotFoundException.class)
                    .isThrownBy(() -> controller.readJobExecution(EXECUTION_ID));
        }
    }

    // ==================================================================================================

    @Nested
    @DisplayName("The operation timers")
    class TheOperationTimers {

        @Test
        @DisplayName("time a launch and tag it with the allow-listed job name and the outcome it reached")
        void timeALaunchWithBoundedTags() throws Exception {
            final String jobName = PostTransactionJobConfig.JOB_NAME;
            registerJob(jobName);
            when(jobOperator.start(anyString(), any(Properties.class))).thenReturn(EXECUTION_ID);

            controller.launchJob(jobName, Map.of());

            final Timer timer = meterRegistry.find("carddemo.batch.joblaunch.request")
                    .tag("job", jobName).tag("outcome", "launched").timer();
            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(1L);
        }

        @Test
        @DisplayName("time a rejected launch under a fixed placeholder rather than the value that "
                + "arrived, so a caller cannot mint unbounded time series")
        void timeARejectedLaunchUnderAFixedPlaceholder() {
            assertThatExceptionOfType(RecordNotFoundException.class)
                    .isThrownBy(() -> controller.launchJob("caller-chosen-value", Map.of()));

            assertThat(meterRegistry.find("carddemo.batch.joblaunch.request")
                    .tag("job", "caller-chosen-value").timer())
                    .as("no tag value may come from a caller")
                    .isNull();
            assertThat(meterRegistry.find("carddemo.batch.joblaunch.request")
                    .tag("job", "unrecognised").tag("outcome", "absent").timer())
                    .isNotNull();
        }

        @Test
        @DisplayName("time a status read and tag it with the job it reported")
        void timeAStatusRead() {
            final String jobName = InterestCalculationJobConfig.JOB_NAME;
            final JobExecution execution =
                    executionOf(jobName, BatchStatus.STARTED, ExitStatus.EXECUTING);
            when(jobExplorer.getJobExecution(EXECUTION_ID)).thenReturn(execution);

            controller.readJobExecution(EXECUTION_ID);

            assertThat(meterRegistry.find("carddemo.batch.jobstatus.request")
                    .tag("job", jobName).tag("outcome", "reported").timer())
                    .isNotNull();
        }

        @Test
        @DisplayName("time a status read that found nothing, so an absent execution is measured too")
        void timeAStatusReadThatFoundNothing() {
            when(jobExplorer.getJobExecution(EXECUTION_ID)).thenReturn(null);

            assertThatExceptionOfType(RecordNotFoundException.class)
                    .isThrownBy(() -> controller.readJobExecution(EXECUTION_ID));

            assertThat(meterRegistry.find("carddemo.batch.jobstatus.request")
                    .tag("job", "unrecognised").tag("outcome", "absent").timer())
                    .isNotNull();
        }

        @Test
        @DisplayName("record every launch outcome under one of a bounded set of names, so the label set "
                + "cannot grow with traffic")
        void recordEveryOutcomeUnderABoundedName() throws Exception {
            final String jobName = CategoryBalanceReportJobConfig.JOB_NAME;
            registerJob(jobName);
            when(jobOperator.start(anyString(), any(Properties.class)))
                    .thenThrow(new JobInstanceAlreadyExistsException("already exists"));

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> controller.launchJob(jobName, Map.of()));

            assertThat(meterRegistry.find("carddemo.batch.joblaunch.request")
                    .tag("job", jobName).tag("outcome", "refused").timer())
                    .isNotNull();
        }
    }
}
