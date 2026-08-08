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

import com.carddemo.api.dto.BatchJobExecutionResponse;
import com.carddemo.api.dto.BatchJobLaunchResponse;
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
import com.carddemo.service.BatchLaunchGateway;
import com.carddemo.service.BatchJobLaunchService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.UnexpectedJobExecutionException;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.NoSuchJobException;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRestartException;
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
     * The two unmapped methods the mapped operations delegate their implementation to.
     *
     * <p>Named here because they are the one legitimate case of an unmapped method in this class: each is
     * invoked by the request-mapped typed operation immediately above it, so neither is a surface a caller
     * addresses and neither can answer not-found. Any <em>other</em> unmapped method carrying published
     * interface metadata is an API-shaped operation the router does not serve, which is what
     * {@link TheDeliveredOperationInventory#declaresNoUnmappedDocumentedOperation()} refuses.
     */
    private static final Set<String> DELEGATED_IMPLEMENTATIONS =
            Set.of("launchJob", "readJobExecution");

    /**
     * A parameter value with surrounding spaces, used to prove nothing is trimmed.
     *
     * <p>Two of the parameters the jobs declare are fixed width, and one becomes the leading characters of
     * synthesised identifiers, so a value that arrives padded must reach the framework padded.
     */
    private static final String UNTRIMMED_VALUE = " 2022-07-19 ";

    /** Registry resolving a stable name to a registered job. */
    private JobRegistry jobRegistry;

    /** Launcher the resolved job is started through, against typed parameters. */
    private JobLauncher jobLauncher;

    /** Metadata reader one execution is reported from. */
    private JobExplorer jobExplorer;

    /** A real registry, so the tags a request produces can be read back. */
    private MeterRegistry meterRegistry;

    /** The controller under test. */
    private BatchJobController controller;

    /**
     * Assembles the controller over the real batch operation service, itself over the three stubbed
     * framework interfaces.
     *
     * <p>The service is the genuine collaborator rather than a stub of it, so every assertion below still
     * measures what actually reaches the registry, the launcher and the metadata reader. That is the point
     * of the seam: the controller no longer names the framework, and the framework interactions it drives
     * are still observed here through the same three mocks.
     */
    @BeforeEach
    void setUp() {
        jobRegistry = mock(JobRegistry.class);
        jobLauncher = mock(JobLauncher.class);
        jobExplorer = mock(JobExplorer.class);
        meterRegistry = new SimpleMeterRegistry();
        controller = new BatchJobController(
                new BatchJobLaunchService(jobRegistry, BatchLaunchGateway.from(jobLauncher),
                        jobExplorer),
                meterRegistry);
    }

    /**
     * Registers a job under the given name so the registry resolves it.
     *
     * @param jobName the stable job name to register
     * @throws NoSuchJobException never; declared because the stubbed method declares it
     */
    private Job registerJob(final String jobName) throws NoSuchJobException {
        final Job job = mock(Job.class);
        when(job.getName()).thenReturn(jobName);
        when(jobRegistry.getJob(jobName)).thenReturn(job);
        return job;
    }

    /**
     * Registers a job and stubs a successful launch of it, answering the registered job.
     *
     * @param jobName the stable job name to register
     * @return the registered job, which is the instance the launch must be issued against
     * @throws Exception if a stubbed signature reports a failure
     */
    private Job registerLaunchableJob(final String jobName) throws Exception {
        final Job job = registerJob(jobName);
        final JobExecution started = mock(JobExecution.class);
        when(started.getId()).thenReturn(EXECUTION_ID);
        when(jobLauncher.run(eq(job), any(JobParameters.class))).thenReturn(started);
        return job;
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
        @DisplayName("is exactly two documented operations - launch and status read")
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
                    .as("launch is the one operation that changes something")
                    .hasSize(1);
            assertThat(mapped).filteredOn(method -> method.getAnnotation(GetMapping.class) != null)
                    .as("reading a status changes nothing, so it is the only get")
                    .hasSize(1);
            assertThat(mapped).extracting(Method::getName)
                    .containsExactlyInAnyOrder("launchTypedJob", "readTypedJobExecution");
        }

        @Test
        @DisplayName("declares no unmapped operation carrying published interface metadata, so the "
                + "surface a reader sees is the surface the router serves")
        void declaresNoUnmappedDocumentedOperation() {
            // An @Operation-annotated public method with no request mapping reads as an endpoint,
            // publishes nothing and answers not-found at runtime. Two such methods - a next-instance
            // start and a restart - were declared here and were unreachable; the batch surface is
            // launch and status, and the repeat that remains is the launch's own advanced instance.
            // Whatever the framework's operator can still do beyond those two lives on
            // BatchJobLaunchService, which carries no interface metadata at all.
            final List<Method> documentedButUnmapped =
                    Arrays.stream(BatchJobController.class.getDeclaredMethods())
                            .filter(method -> method.getAnnotation(Operation.class) != null)
                            .filter(method -> method.getAnnotation(PostMapping.class) == null
                                    && method.getAnnotation(GetMapping.class) == null)
                            .filter(method -> !DELEGATED_IMPLEMENTATIONS.contains(method.getName()))
                            .toList();

            assertThat(documentedButUnmapped)
                    .as("each of these presents an API-shaped operation the router does not publish")
                    .isEmpty();
            // A repeat and a resume would both be defensible operations and neither is delivered: the
            // integration contract asserts that a next-instance address and a restart address answer as
            // absent. An implementation kept behind an unmapped method would still be described by its
            // own operation metadata and would still be reachable from any code in this package, so the
            // methods are absent rather than merely unmapped.
            assertThat(Arrays.stream(BatchJobController.class.getDeclaredMethods())
                    .map(Method::getName)
                    .toList())
                    .as("the removed pair may not return under either name")
                    .doesNotContain("startNextJobInstance", "restartJobExecution");
        }

        @Test
        @DisplayName("names no operation that would orchestrate, schedule or manage the jobs, because the "
                + "estate has no master scheduler to translate")
        void namesNoOrchestratingOperation() {
            final List<String> declared = new ArrayList<>();
            for (final Method method : BatchJobController.class.getDeclaredMethods()) {
                declared.add(method.getName().toLowerCase(Locale.ROOT));
            }

            // "nextinstance" and "restart" are on the forbidden list with the rest: the delivered surface
            // is the launch and the status read, so a method named for repeating or resuming a run would
            // be an operation this class does not offer however it were mapped.
            assertThat(declared).noneSatisfy(name -> assertThat(name)
                    .containsAnyOf("runall", "runeverything", "pipeline", "master", "schedule", "stop",
                            "abandon", "delete", "nextinstance", "nextjob", "restart"));
            assertThat(mappedMethods()).extracting(Method::getName)
                    .as("no mapped operation chains one job to another")
                    .doesNotContain("startNextJob", "runPipeline", "runAll");
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

            final BatchJobLaunchService operations =
                    new BatchJobLaunchService(jobRegistry, BatchLaunchGateway.from(jobLauncher),
                            jobExplorer);
            assertThatNullPointerException().isThrownBy(() ->
                    new BatchJobController(null, meterRegistry));
            assertThatNullPointerException().isThrownBy(() ->
                    new BatchJobController(operations, null));
        }

        @Test
        @DisplayName("names no class from the batch package, because the operational surface reaches the "
                + "framework through the service layer and not across the tier boundary")
        void namesNoClassFromTheBatchPackage() {
            for (final Class<?> parameter
                    : BatchJobController.class.getDeclaredConstructors()[0].getParameterTypes()) {
                assertThat(parameter.getName())
                        .as("constructor parameter %s must not come from the batch tier",
                                parameter.getName())
                        .doesNotStartWith("com.carddemo.batch");
            }
            for (final Field field : BatchJobController.class.getDeclaredFields()) {
                assertThat(field.getType().getName())
                        .as("field %s must not be typed from the batch tier", field.getName())
                        .doesNotStartWith("com.carddemo.batch");
            }
        }

        @Test
        @DisplayName("composes every answer as a named record and never as a keyed map, so no value this "
                + "controller produced is read back out of it by a cast")
        void composesEveryAnswerAsANamedRecord() {
            final List<Method> answering = Arrays.stream(BatchJobController.class.getDeclaredMethods())
                    .filter(method -> ResponseEntity.class.equals(method.getReturnType()))
                    .toList();

            assertThat(answering)
                    .as("the two published operations plus the helpers they delegate to")
                    .isNotEmpty();
            assertThat(answering).allSatisfy(method -> {
                final java.lang.reflect.Type carried = ((java.lang.reflect.ParameterizedType)
                        method.getGenericReturnType()).getActualTypeArguments()[0];
                assertThat(carried.getTypeName())
                        .as("%s answers a named record from the published contract", method.getName())
                        .startsWith("com.carddemo.api.dto.");
                assertThat(((Class<?>) carried).isRecord())
                        .as("%s answers a record, whose components are read by name", method.getName())
                        .isTrue();
            });
            assertThat(answering).extracting(method -> ((Class<?>) ((java.lang.reflect.ParameterizedType)
                            method.getGenericReturnType()).getActualTypeArguments()[0]).getSimpleName())
                    .as("only the two transport records the batch surface publishes")
                    .containsOnly("BatchJobLaunchResponse", "BatchJobExecutionResponse");
        }

        @Test
        @DisplayName("declares no map key of its own, because a key is only needed by an answer assembled "
                + "as a map and there is no longer one to assemble")
        void declaresNoMapKeyOfItsOwn() {
            for (final Field field : BatchJobController.class.getDeclaredFields()) {
                assertThat(field.getName())
                        .as("field %s reads as a map key rather than a route or a collaborator",
                                field.getName())
                        .doesNotStartWith("FIELD_");
            }
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
            verifyNoInteractions(jobLauncher);
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
            final Job registered = registerLaunchableJob(jobName);

            final ResponseEntity<BatchJobLaunchResponse> answer =
                    controller.launchJob(jobName, Map.of());

            verify(jobRegistry).getJob(jobName);
            verify(jobLauncher).run(eq(registered), any(JobParameters.class));
            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(answer.getBody())
                    .as("the typed answer carries both values by name, so neither is read back by a cast")
                    .isEqualTo(new BatchJobLaunchResponse(EXECUTION_ID, jobName));
        }

        @Test
        @DisplayName("hands the framework the supplied values unchanged and adds nothing, so the same "
                + "request twice is the same job identity and stays idempotent")
        void handsTheSuppliedParametersOverUnchanged() throws Exception {
            final String jobName = TransactionReportJobConfig.JOB_NAME;
            final Job registered = registerLaunchableJob(jobName);
            final Map<String, String> supplied = new LinkedHashMap<>();
            supplied.put(JobParameterValidators.REPORT_START_DATE_KEY, "2022-07-01");
            supplied.put(JobParameterValidators.REPORT_END_DATE_KEY, UNTRIMMED_VALUE);

            controller.launchJob(jobName, supplied);

            final JobParameters handedOver = capturedLaunchParameters(registered);
            assertThat(handedOver.getParameters().keySet())
                    .as("a generated run identifier, timestamp or unique value would break idempotency")
                    .containsExactlyInAnyOrder(JobParameterValidators.REPORT_START_DATE_KEY,
                            JobParameterValidators.REPORT_END_DATE_KEY);
            assertThat(handedOver.getString(JobParameterValidators.REPORT_START_DATE_KEY))
                    .isEqualTo("2022-07-01");
            assertThat(handedOver.getString(JobParameterValidators.REPORT_END_DATE_KEY))
                    .as("a fixed-width value must arrive exactly as it was sent")
                    .isEqualTo(UNTRIMMED_VALUE);
        }

        @Test
        @DisplayName("hands over typed string parameters and marks every one identifying, so a caller "
                + "cannot choose a type for the framework to resolve or opt a parameter out of the "
                + "job identity")
        void handsOverTypedIdentifyingParameters() throws Exception {
            final String jobName = InterestCalculationJobConfig.JOB_NAME;
            final Job registered = registerLaunchableJob(jobName);

            controller.launchJob(jobName,
                    Map.of(JobParameterValidators.INTEREST_PARM_DATE_KEY, "2022071900"));

            final JobParameters handedOver = capturedLaunchParameters(registered);
            assertThat(handedOver.getParameters()
                            .get(JobParameterValidators.INTEREST_PARM_DATE_KEY).getType())
                    .as("the type is the boundary's decision, never a value the caller supplied")
                    .isEqualTo(String.class);
            assertThat(handedOver.getParameters()
                            .get(JobParameterValidators.INTEREST_PARM_DATE_KEY).isIdentifying())
                    .as("an opted-out parameter would let a caller mint a fresh instance of a job that "
                            + "had deliberately already been run")
                    .isTrue();
        }

        @Test
        @DisplayName("launches with no parameters when none were supplied, and tolerates an absent map, "
                + "so a job that declares none needs none")
        void launchesWithNoParametersWhenNoneWereSupplied() throws Exception {
            final String jobName = BackupTransactionJobConfig.JOB_NAME;
            final Job registered = registerLaunchableJob(jobName);

            controller.launchJob(jobName, null);

            assertThat(capturedLaunchParameters(registered).isEmpty()).isTrue();
        }

        @Test
        @DisplayName("REFUSES a parameter name the addressed job does not read, so a name outside the "
                + "job's schema cannot reach the framework and cannot become part of a job identity")
        void refusesAParameterNameTheJobDoesNotRead() throws Exception {
            final String jobName = InterestCalculationJobConfig.JOB_NAME;
            registerLaunchableJob(jobName);

            assertThatThrownBy(() -> controller.launchJob(jobName, Map.of("nonce", "1")))
                    .isInstanceOf(ValidationException.class)
                    .as("the refused name is caller-controlled text and is never echoed")
                    .hasMessageNotContaining("nonce")
                    .hasMessage("The request supplied a parameter this job does not declare.");
            verify(jobLauncher, never()).run(any(Job.class), any(JobParameters.class));
        }

        @Test
        @DisplayName("REFUSES any parameter at all for a job whose schema is empty, and says so, because "
                + "six of the nine read none")
        void refusesAnyParameterForAJobThatReadsNone() throws Exception {
            final String jobName = CreateStatementJobConfig.JOB_NAME;
            registerLaunchableJob(jobName);

            assertThatThrownBy(() -> controller.launchJob(jobName,
                    Map.of(JobParameterValidators.REPORT_START_DATE_KEY, "2022-07-01")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("The request supplied a parameter this job does not declare.");
            verify(jobLauncher, never()).run(any(Job.class), any(JobParameters.class));
        }

        @Test
        @DisplayName("REFUSES a value carrying the untyped-grammar separator, so the framework's "
                + "value-type-identifying grammar cannot be expressed through this boundary")
        void refusesAValueCarryingTheUntypedGrammarSeparator() throws Exception {
            final String jobName = FileProbeJobConfig.FILE_PROBE_JOB_NAME;
            registerLaunchableJob(jobName);

            assertThatThrownBy(() -> controller.launchJob(jobName,
                    Map.of(JobParameterValidators.FILE_PROBE_MODE_KEY, "1,java.lang.Integer,true")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageNotContaining("java.lang.Integer");
            verify(jobLauncher, never()).run(any(Job.class), any(JobParameters.class));
        }

        @Test
        @DisplayName("REFUSES a value carrying a control character, which could otherwise forge a line "
                + "in a log record that names the value")
        void refusesAValueCarryingAControlCharacter() throws Exception {
            final String jobName = FileProbeJobConfig.FILE_PROBE_JOB_NAME;
            registerLaunchableJob(jobName);

            assertThatThrownBy(() -> controller.launchJob(jobName,
                    Map.of(JobParameterValidators.FILE_PROBE_MODE_KEY, "probe\nWARN forged")))
                    .isInstanceOf(ValidationException.class);
            verify(jobLauncher, never()).run(any(Job.class), any(JobParameters.class));
        }

        @Test
        @DisplayName("REFUSES a value that is not representable as single-byte printable text, because "
                + "the framework's metadata columns and the launch diagnostic both carry it verbatim")
        void refusesAValueThatIsNotPrintableSingleByteText() throws Exception {
            final String jobName = FileProbeJobConfig.FILE_PROBE_JOB_NAME;
            registerLaunchableJob(jobName);

            assertThatThrownBy(() -> controller.launchJob(jobName,
                    Map.of(JobParameterValidators.FILE_PROBE_MODE_KEY, "prob\u00e9")))
                    .isInstanceOf(ValidationException.class);
            verify(jobLauncher, never()).run(any(Job.class), any(JobParameters.class));
        }

        @Test
        @DisplayName("REFUSES a value longer than the declared bound, and admits one exactly at it, so "
                + "the ceiling is the stated figure rather than an approximation")
        void refusesAnOverLongValueAndAdmitsOneExactlyAtTheBound() throws Exception {
            final String jobName = FileProbeJobConfig.FILE_PROBE_JOB_NAME;
            final Job registered = registerLaunchableJob(jobName);
            final String atTheBound = "a".repeat(BatchJobController.MAX_PARAMETER_VALUE_LENGTH);

            controller.launchJob(jobName,
                    Map.of(JobParameterValidators.FILE_PROBE_MODE_KEY, atTheBound));
            assertThat(capturedLaunchParameters(registered)
                            .getString(JobParameterValidators.FILE_PROBE_MODE_KEY))
                    .isEqualTo(atTheBound);

            assertThatThrownBy(() -> controller.launchJob(jobName,
                    Map.of(JobParameterValidators.FILE_PROBE_MODE_KEY, atTheBound + "a")))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        @DisplayName("translates the framework's refusal of an already-completed job identity without "
                + "echoing the job name or the parameters the framework's own message carries")
        void translatesAnAlreadyCompletedJobIdentity() throws Exception {
            final String jobName = TransactionReportJobConfig.JOB_NAME;
            final Job registered = registerJob(jobName);
            when(jobLauncher.run(eq(registered), any(JobParameters.class)))
                    .thenThrow(new JobInstanceAlreadyCompleteException(
                            "A job instance already exists and is complete for name=" + jobName
                                    + " and parameters={secretValue}"));

            assertThatThrownBy(() -> controller.launchJob(jobName,
                    Map.of(JobParameterValidators.REPORT_START_DATE_KEY, "secretValue")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageNotContaining("secretValue")
                    .hasMessageNotContaining(jobName);
        }

        @Test
        @DisplayName("translates the framework's refusal of an execution already running, because a "
                + "second start of a running job is the same operator mistake")
        void translatesAnExecutionAlreadyRunning() throws Exception {
            final String jobName = PostTransactionJobConfig.JOB_NAME;
            final Job registered = registerJob(jobName);
            when(jobLauncher.run(eq(registered), any(JobParameters.class)))
                    .thenThrow(new JobExecutionAlreadyRunningException(
                            "A job execution for this job is already running: " + jobName));

            assertThatThrownBy(() -> controller.launchJob(jobName, Map.of()))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageNotContaining(jobName);
        }

        @Test
        @DisplayName("translates the framework's refusal to restart, so a job the framework will not "
                + "restart is reported as a refused launch rather than as a server fault")
        void translatesARefusalToRestart() throws Exception {
            final String jobName = CategoryBalanceReportJobConfig.JOB_NAME;
            final Job registered = registerJob(jobName);
            when(jobLauncher.run(eq(registered), any(JobParameters.class)))
                    .thenThrow(new JobRestartException("JobInstance already exists and is not restartable"));

            assertThatThrownBy(() -> controller.launchJob(jobName, Map.of()))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        @DisplayName("translates the job's own parameter refusal without restating the rule the job's "
                + "validator owns")
        void translatesTheJobsOwnParameterRefusal() throws Exception {
            final String jobName = FileProbeJobConfig.FILE_PROBE_JOB_NAME;
            final Job registered = registerJob(jobName);
            when(jobLauncher.run(eq(registered), any(JobParameters.class)))
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
            verify(jobLauncher, never()).run(any(Job.class), any(JobParameters.class));
        }

        @Test
        @DisplayName("refuses a parameter the typed framework carrier cannot hold")
        void skipsAParameterTheParameterSetCannotHold() throws Exception {
            final String jobName = TransactionReportJobConfig.JOB_NAME;
            final Job registered = registerLaunchableJob(jobName);
            final Map<String, String> supplied = new LinkedHashMap<>();
            supplied.put(JobParameterValidators.REPORT_START_DATE_KEY, "2022-07-01");
            supplied.put(JobParameterValidators.REPORT_END_DATE_KEY, null);
            supplied.put(null, "2022-07-31");

            assertThatThrownBy(() -> controller.launchJob(jobName, supplied))
                    .isInstanceOf(ValidationException.class);
            verify(jobLauncher, never()).run(eq(registered), any(JobParameters.class));
        }

        /**
         * Reads back the typed parameters the controller handed to the framework.
         *
         * @param registered the job the launch was issued against
         * @return the parameters as they were handed over
         * @throws Exception if the stubbed launch signature reports a failure
         */
        private JobParameters capturedLaunchParameters(final Job registered) throws Exception {
            final ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
            verify(jobLauncher).run(eq(registered), captor.capture());
            return captor.getValue();
        }
    }

    // ==================================================================================================

    @Nested
    @DisplayName("The closed per-job parameter schema")
    class TheClosedParameterSchema {

        @Test
        @DisplayName("holds one entry for every allow-listed job, so a lookup is never absent and an "
                + "empty entry is a decision rather than an oversight")
        void holdsOneEntryPerAllowListedJob() {
            assertThat(BatchJobController.ACCEPTED_JOB_PARAMETER_NAMES.keySet())
                    .isEqualTo(BatchJobController.LAUNCHABLE_JOB_NAMES);
        }

        @Test
        @DisplayName("names exactly the parameters each job reads, taken from the declaring authority "
                + "rather than repeated, and nothing for the six that read none")
        void namesExactlyTheParametersEachJobReads() {
            final Map<String, Object> expected = Map.of(
                    PostTransactionJobConfig.JOB_NAME, Set.of(),
                    InterestCalculationJobConfig.JOB_NAME,
                            Set.of(JobParameterValidators.INTEREST_PARM_DATE_KEY),
                    CombineTransactionsJobConfig.JOB_NAME, Set.of(),
                    CreateStatementJobConfig.JOB_NAME, Set.of(),
                    TransactionReportJobConfig.JOB_NAME,
                            Set.of(JobParameterValidators.REPORT_START_DATE_KEY,
                                    JobParameterValidators.REPORT_END_DATE_KEY),
                    BackupTransactionJobConfig.JOB_NAME, Set.of(),
                    CategoryBalanceReportJobConfig.JOB_NAME, Set.of(),
                    FileProbeJobConfig.FILE_PROBE_JOB_NAME,
                            Set.of(JobParameterValidators.FILE_PROBE_MODE_KEY),
                    DailyTransactionReadJobConfig.JOB_NAME, Set.of());

            assertThat(BatchJobController.ACCEPTED_JOB_PARAMETER_NAMES)
                    .containsExactlyInAnyOrderEntriesOf(
                            expected.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                                    Map.Entry::getKey,
                                    entry -> castNames(entry.getValue()))));
        }

        @Test
        @DisplayName("is immutable in every respect, so no caller can widen what a job accepts at run "
                + "time")
        void isImmutableInEveryRespect() {
            assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(() ->
                    BatchJobController.ACCEPTED_JOB_PARAMETER_NAMES.put("anotherJob", Set.of()));
            assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(() ->
                    BatchJobController.ACCEPTED_JOB_PARAMETER_NAMES
                            .get(InterestCalculationJobConfig.JOB_NAME).add("nonce"));
        }

        /**
         * Narrows a declared expectation to the set type the schema holds.
         *
         * @param names the expected names
         * @return the same names as a set of strings
         */
        @SuppressWarnings("unchecked")
        private static Set<String> castNames(final Object names) {
            return (Set<String>) names;
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

            final ResponseEntity<BatchJobExecutionResponse> answer =
                    controller.readJobExecution(EXECUTION_ID);

            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(answer.getBody())
                    .as("four named values, each read from its own accessor rather than from a map key")
                    .isEqualTo(new BatchJobExecutionResponse(EXECUTION_ID, jobName,
                            BatchStatus.COMPLETED.name(), ExitStatus.COMPLETED.getExitCode()));
            assertThat(BatchJobExecutionResponse.class.getRecordComponents())
                    .as("and carries nothing else")
                    .hasSize(4);
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

            final BatchJobExecutionResponse body =
                    controller.readJobExecution(EXECUTION_ID).getBody();

            assertThat(body).isNotNull();
            assertThat(body.exitCode()).isEqualTo(ExitStatus.FAILED.getExitCode());
            assertThat(List.of(String.valueOf(body.executionId()), body.jobName(), body.status(),
                    body.exitCode()))
                    .noneSatisfy(value -> assertThat(value).contains("internal detail"));
            verify(execution, never()).getJobParameters();
        }

        @Test
        @DisplayName("answers an unknown status and an unknown exit code with the framework's own unknown "
                + "values, so a member is never absent from the answer")
        void answersUnknownStatusAndExitCodeWithTheFrameworksUnknownValues() {
            final JobExecution execution =
                    executionOf(CreateStatementJobConfig.JOB_NAME, null, null);
            when(jobExplorer.getJobExecution(EXECUTION_ID)).thenReturn(execution);

            final BatchJobExecutionResponse body =
                    controller.readJobExecution(EXECUTION_ID).getBody();

            assertThat(body).isNotNull();
            assertThat(body.status()).isEqualTo(BatchStatus.UNKNOWN.name());
            assertThat(body.exitCode()).isEqualTo(ExitStatus.UNKNOWN.getExitCode());
        }

        @Test
        @DisplayName("answers an exit status carrying no code with the framework's own unknown code, so a "
                + "member is never null in the answer")
        void answersAnExitStatusWithNoCodeWithTheUnknownCode() {
            final JobExecution execution = executionOf(FileProbeJobConfig.FILE_PROBE_JOB_NAME,
                    BatchStatus.COMPLETED, new ExitStatus(null, ""));
            when(jobExplorer.getJobExecution(EXECUTION_ID)).thenReturn(execution);

            assertThat(controller.readJobExecution(EXECUTION_ID).getBody())
                    .extracting(BatchJobExecutionResponse::exitCode)
                    .isEqualTo(ExitStatus.UNKNOWN.getExitCode());
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
            registerLaunchableJob(jobName);

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
        @DisplayName("registers exactly the two timers the two delivered operations need, so no timer "
                + "survives for an operation this surface no longer publishes")
        void registersOnlyTheTimersTheDeliveredOperationsNeed() throws Exception {
            final String launched = PostTransactionJobConfig.JOB_NAME;
            registerLaunchableJob(launched);
            controller.launchJob(launched, Map.of());
            final String reported = InterestCalculationJobConfig.JOB_NAME;
            final JobExecution running =
                    executionOf(reported, BatchStatus.STARTED, ExitStatus.EXECUTING);
            when(jobExplorer.getJobExecution(EXECUTION_ID)).thenReturn(running);
            controller.readJobExecution(EXECUTION_ID);

            assertThat(meterRegistry.getMeters().stream()
                    .map(meter -> meter.getId().getName())
                    .filter(name -> name.startsWith("carddemo.batch.job"))
                    .distinct()
                    .sorted()
                    .toList())
                    .as("a timer for a next-instance start or a restart would measure an operation the "
                            + "router does not serve, and would report zero for ever")
                    .containsExactly("carddemo.batch.joblaunch.request",
                            "carddemo.batch.jobstatus.request");
        }

        @Test
        @DisplayName("time a refused parameter name under the rejected outcome, so a caller probing the "
                + "parameter surface is visible without any probe value entering a label")
        void timeARefusedParameterNameUnderTheRejectedOutcome() throws Exception {
            final String jobName = CreateStatementJobConfig.JOB_NAME;
            registerJob(jobName);

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> controller.launchJob(jobName, Map.of("probeName", "probeValue")));

            assertThat(meterRegistry.find("carddemo.batch.joblaunch.request")
                    .tag("job", jobName).tag("outcome", "refused").timer())
                    .isNotNull();
            assertThat(meterRegistry.find("carddemo.batch.joblaunch.request")
                    .tag("job", "probeName").timer())
                    .as("no tag value may come from a caller")
                    .isNull();
        }

        @Test
        @DisplayName("record every launch outcome under one of a bounded set of names, so the label set "
                + "cannot grow with traffic")
        void recordEveryOutcomeUnderABoundedName() throws Exception {
            final String jobName = CategoryBalanceReportJobConfig.JOB_NAME;
            final Job registered = registerJob(jobName);
            when(jobLauncher.run(eq(registered), any(JobParameters.class)))
                    .thenThrow(new JobInstanceAlreadyCompleteException("already complete"));

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> controller.launchJob(jobName, Map.of()));

            assertThat(meterRegistry.find("carddemo.batch.joblaunch.request")
                    .tag("job", jobName).tag("outcome", "refused").timer())
                    .isNotNull();
        }
    }
}
