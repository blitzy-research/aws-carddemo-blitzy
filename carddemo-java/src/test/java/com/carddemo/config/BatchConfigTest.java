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
package com.carddemo.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersIncrementer;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.flow.FlowExecutionStatus;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationListener;
import org.springframework.context.Lifecycle;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.carddemo.CardDemoApplication;
import com.carddemo.config.BatchConfig.ConditionCodeGate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Verifies the batch infrastructure configuration: the two collaborators it shares with the nine job
 * configurations, the two condition-code step gates it must never collapse, and the far larger set of
 * things it must <em>not</em> do.
 *
 * <h2>Why the negative assertions carry most of the weight</h2>
 *
 * <p>Three reflexes damage a batch configuration under this framework generation, and none of them
 * produces a compile error, so only a test catches them.</p>
 *
 * <ul>
 *   <li>The enabling annotation. The batch auto-configuration backs off when it is present, so adding it
 *       switches off the job repository wiring it appears to switch on. Asserted absent both here and on
 *       the application entry point.</li>
 *   <li>Something that fires at start-up. A legacy job was submitted deliberately; bringing an
 *       application up never triggered one. Asserted by contributing no runner, no lifecycle participant,
 *       no initialising callback, no event listener and no post-construct method, and by reading the
 *       shipped configuration document's own launch-on-start setting.</li>
 *   <li>Redeclaring what the framework already publishes. The job repository, launcher, explorer,
 *       registry and operator all arrive from the auto-configuration; a second definition of any of them
 *       either fails the context or shadows the framework's wiring. Asserted by publishing exactly two
 *       beans and nothing else.</li>
 * </ul>
 *
 * <h2>The correction this suite exists to hold</h2>
 *
 * <p>The migration plan recorded all four condition-code step gates in the estate as demanding a prior
 * return code of exactly zero. Three do, on STEP020, STEP030 and STEP040 of
 * {@code app/jcl/CREASTMT.JCL} at lines 56, 66 and 79; the fourth, on STEP10 of
 * {@code app/jcl/TRANBKP.jcl:51}, tolerates a warning. Collapsing the two would abort the backup job on
 * an outcome the estate expects, so the two ceilings are asserted to differ and each is asserted at its
 * own boundary.</p>
 *
 * <p>Provenance: no legacy antecedent; the migrated estate carries no test harness. Guards the batch
 * infrastructure of the checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.</p>
 */
@DisplayName("batch infrastructure: two shared beans, two gates that must not be collapsed, and an inert start-up")
class BatchConfigTest {

    /** The bean name the shared job-boundary listener is published under. */
    private static final String LISTENER_BEAN_NAME = "batchJobBoundaryListener";

    /** The bean name the shared parameter incrementer is published under. */
    private static final String INCREMENTER_BEAN_NAME = "batchJobRunIncrementer";

    /** The configuration's own bean name, which the context registers alongside its beans. */
    private static final String CONFIGURATION_BEAN_NAME = "batchConfig";

    /** Bean-name prefix of everything the framework itself contributes to a bare runner context. */
    private static final String FRAMEWORK_BEAN_PREFIX = "org.springframework.";

    /**
     * Every role by which a bean can act without being asked to, so none of them may be contributed.
     *
     * <p>A legacy job was submitted deliberately; bringing an application up never triggered one.</p>
     */
    private static final List<Class<?>> SELF_STARTING_ROLES = List.of(
            ApplicationRunner.class, CommandLineRunner.class, Lifecycle.class, SmartLifecycle.class,
            InitializingBean.class, ApplicationListener.class);

    /** The shipped configuration document every profile inherits. */
    private static final String SHARED_CONFIGURATION = "application.yml";

    /** The setting that keeps the framework's start-up runner out of the context. */
    private static final String KEY_BATCH_JOB_ENABLED = "spring.batch.job.enabled";

    /** The setting that hands the framework's metadata tables to the framework rather than a migration. */
    private static final String KEY_BATCH_INITIALIZE_SCHEMA = "spring.batch.jdbc.initialize-schema";

    /** The metadata-table prefix a table census must exclude. */
    private static final String KEY_BATCH_TABLE_PREFIX = "spring.batch.jdbc.table-prefix";

    /** The setting that would name a single job to run on start; it must be absent entirely. */
    private static final String KEY_BATCH_JOB_NAME = "spring.batch.job.name";

    /** A job name for the assembled executions below. */
    private static final String JOB_NAME = "postTransactionJob";

    /** A step name for the assembled step executions below. */
    private static final String STEP_NAME = "postDailyTransactionsStep";

    /** A second step name, so a highest-of-several assertion has more than one contributor. */
    private static final String OTHER_STEP_NAME = "writeRejectRecordsStep";

    /** The parameter key whose value must never reach a log line. */
    private static final String PARAMETER_KEY = "reportStartDate";

    /** The parameter value that must never reach a log line. */
    private static final String PARAMETER_VALUE = "2022-01-01";

    /** A second parameter key, declared out of order so the reported order is provably sorted. */
    private static final String EARLIER_PARAMETER_KEY = "interestParmDate";

    /** The runner used for every context assertion: the real configuration and nothing else. */
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(BatchConfig.class);

    /**
     * Assembles a job execution carrying an instance, an identifier and a parameter set.
     *
     * @return a job execution ready to have step executions added to it
     */
    private static JobExecution jobExecution() {
        JobParameters parameters = new JobParametersBuilder()
                .addString(PARAMETER_KEY, PARAMETER_VALUE)
                .addString(EARLIER_PARAMETER_KEY, "2022071800")
                .toJobParameters();
        return new JobExecution(new JobInstance(11L, JOB_NAME), 22L, parameters);
    }

    /**
     * Adds one step execution to a job execution in a given terminal state.
     *
     * @param jobExecution the execution to add to
     * @param stepName the step's name
     * @param status the batch status the step ended in
     * @param exitStatus the exit status the step reported, possibly {@code null}
     * @return the added step execution
     */
    private static StepExecution stepEndedWith(final JobExecution jobExecution, final String stepName,
            final BatchStatus status, final ExitStatus exitStatus) {
        StepExecution stepExecution = jobExecution.createStepExecution(stepName);
        stepExecution.setStatus(status);
        if (exitStatus != null) {
            stepExecution.setExitStatus(exitStatus);
        }
        return stepExecution;
    }

    /**
     * Reads one key out of the shipped configuration document, so an assertion tracks the real file.
     *
     * @param key the property key to resolve
     * @return the value the document declares, or {@code null} when it declares none
     */
    private static Object shippedProperty(final String key) {
        try {
            List<PropertySource<?>> documents =
                    new YamlPropertySourceLoader().load(SHARED_CONFIGURATION,
                            new ClassPathResource(SHARED_CONFIGURATION));
            for (PropertySource<?> document : documents) {
                if (document instanceof EnumerablePropertySource<?> enumerable
                        && enumerable.containsProperty(key)) {
                    return enumerable.getProperty(key);
                }
            }
            return null;
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the shipped configuration document must be readable",
                    unreadable);
        }
    }

    @Nested
    @DisplayName("What the configuration publishes")
    class PublishedBeans {

        @Test
        @DisplayName("exactly two beans, the shared listener and the shared incrementer, and nothing else")
        void publishesExactlyTheTwoSharedCollaborators() {
            runner.run(context -> {
                assertThat(context).hasSingleBean(JobExecutionListener.class);
                assertThat(context).hasSingleBean(JobParametersIncrementer.class);
                assertThat(context.getBeanDefinitionNames())
                        .filteredOn(name -> !name.startsWith(FRAMEWORK_BEAN_PREFIX))
                        .as("a third bean here would be job-specific work in the infrastructure layer")
                        .containsExactlyInAnyOrder(CONFIGURATION_BEAN_NAME, LISTENER_BEAN_NAME,
                                INCREMENTER_BEAN_NAME);
            });
        }

        @Test
        @DisplayName("the incrementer is the framework's run-identifier incrementer, so it cannot collide "
                + "with a validated parameter key")
        void theIncrementerIsTheFrameworkRunIdentifierIncrementer() {
            runner.run(context -> assertThat(context.getBean(JobParametersIncrementer.class))
                    .isInstanceOf(RunIdIncrementer.class));
        }

        @Test
        @DisplayName("the incrementer advances a parameter set rather than replacing it, so an identical "
                + "resubmission becomes a new instance")
        void theIncrementerAdvancesRatherThanReplaces() {
            runner.run(context -> {
                JobParametersIncrementer incrementer = context.getBean(JobParametersIncrementer.class);
                JobParameters first = incrementer.getNext(new JobParametersBuilder()
                        .addString(PARAMETER_KEY, PARAMETER_VALUE).toJobParameters());
                JobParameters second = incrementer.getNext(first);

                assertThat(first.getString(PARAMETER_KEY))
                        .as("the caller's own parameters survive the advance")
                        .isEqualTo(PARAMETER_VALUE);
                assertThat(second).isNotEqualTo(first);
            });
        }

        @Test
        @DisplayName("no job repository, launcher, explorer, registry or operator, because the framework "
                + "already publishes all five")
        void publishesNothingTheFrameworkAlreadyPublishes() {
            runner.run(context -> {
                assertThat(context).doesNotHaveBean(JobRepository.class);
                assertThat(context).doesNotHaveBean(JobLauncher.class);
                assertThat(context).doesNotHaveBean(JobExplorer.class);
                assertThat(context).doesNotHaveBean(JobRegistry.class);
                assertThat(context).doesNotHaveBean(JobOperator.class);
            });
        }

        @Test
        @DisplayName("the context starts without a data source, which is only possible because nothing "
                + "here reaches for one")
        void theContextStartsWithoutADataSource() {
            runner.run(context -> assertThat(context).hasNotFailed());
        }
    }

    @Nested
    @DisplayName("Nothing fires when the context starts")
    class InertStartUp {

        @Test
        @DisplayName("no start-up runner of any kind reaches the context")
        void contributesNoStartUpRunner() {
            runner.run(context -> {
                assertThat(context).doesNotHaveBean(ApplicationRunner.class);
                assertThat(context).doesNotHaveBean(CommandLineRunner.class);
            });
        }

        @Test
        @DisplayName("nothing the configuration contributes is a lifecycle participant, an initialising "
                + "callback or an event listener, so importing it starts nothing")
        void contributesNothingThatRunsOnItsOwn() {
            runner.run(context -> {
                // Scoped to the bean DEFINITIONS, which is what this configuration contributes. The
                // container registers its own lifecycle processor as a pre-built singleton rather than a
                // definition, and asserting over every bean by type would fail on that machinery instead
                // of on anything written here.
                for (String name : context.getBeanDefinitionNames()) {
                    if (name.startsWith(FRAMEWORK_BEAN_PREFIX)) {
                        continue;
                    }
                    Class<?> type = context.getType(name);
                    assertThat(type).as("bean %s must be resolvable", name).isNotNull();
                    for (Class<?> selfStarting : SELF_STARTING_ROLES) {
                        assertThat(selfStarting.isAssignableFrom(type))
                                .as("bean %s of type %s must not be a %s", name, type.getName(),
                                        selfStarting.getSimpleName())
                                .isFalse();
                    }
                }
            });
        }

        @Test
        @DisplayName("the configuration declares no post-construct or pre-destroy method, so importing it "
                + "cannot start anything")
        void declaresNoLifecycleCallbackMethod() {
            for (Method declared : BatchConfig.class.getDeclaredMethods()) {
                assertThat(declared.isAnnotationPresent(PostConstruct.class))
                        .as("%s must not run on construction", declared.getName())
                        .isFalse();
                assertThat(declared.isAnnotationPresent(PreDestroy.class))
                        .as("%s must not run on shutdown", declared.getName())
                        .isFalse();
            }
        }

        @Test
        @DisplayName("neither the configuration nor the application entry point carries an enabling "
                + "annotation, because the batch auto-configuration backs off when one is present")
        void carriesNoEnablingAnnotation() {
            for (Class<?> candidate : List.of(BatchConfig.class, CardDemoApplication.class)) {
                MergedAnnotations annotations =
                        MergedAnnotations.from(candidate, SearchStrategy.TYPE_HIERARCHY);

                assertThat(annotations.isPresent(EnableBatchProcessing.class))
                        .as("%s must not switch off the auto-configuration it depends on",
                                candidate.getSimpleName())
                        .isFalse();
                assertThat(annotations.isPresent(EnableScheduling.class))
                        .as("%s must not enable a trigger that could launch a job unattended",
                                candidate.getSimpleName())
                        .isFalse();
            }
        }

        @Test
        @DisplayName("the shipped configuration document disables launch-on-start and names no single job, "
                + "which is what keeps the framework's own runner out of the context")
        void theShippedDocumentDisablesLaunchOnStart() {
            assertThat(shippedProperty(KEY_BATCH_JOB_ENABLED))
                    .as("restarting a deployment must not fire a posting run")
                    .isEqualTo(Boolean.FALSE);
            assertThat(shippedProperty(KEY_BATCH_JOB_NAME))
                    .as("naming one job here would make that job the thing a restart runs")
                    .isNull();
        }

        @Test
        @DisplayName("the framework provisions its own metadata tables under the prefix a table census "
                + "must exclude, so no migration may define them")
        void theFrameworkOwnsItsOwnMetadataTables() {
            assertThat(shippedProperty(KEY_BATCH_INITIALIZE_SCHEMA)).isEqualTo("always");
            assertThat(shippedProperty(KEY_BATCH_TABLE_PREFIX)).isEqualTo("BATCH_");
        }
    }

    @Nested
    @DisplayName("The two condition-code step gates")
    class StepGates {

        @Test
        @DisplayName("there are exactly two, and their ceilings differ, so the two forms are not collapsed")
        void thereAreExactlyTwoGatesWithDifferentCeilings() {
            assertThat(ConditionCodeGate.values()).containsExactly(
                    ConditionCodeGate.ALL_PRIOR_STEPS_ZERO, ConditionCodeGate.WARNINGS_TOLERATED);
            assertThat(ConditionCodeGate.ALL_PRIOR_STEPS_ZERO.highestToleratedReturnCode())
                    .as("the statement-job gate, cited at app/jcl/CREASTMT.JCL lines 56, 66 and 79")
                    .isZero();
            assertThat(ConditionCodeGate.WARNINGS_TOLERATED.highestToleratedReturnCode())
                    .as("the backup-job gate, cited at app/jcl/TRANBKP.jcl:51, tolerates a warning")
                    .isEqualTo(4);
            assertThat(ConditionCodeGate.ALL_PRIOR_STEPS_ZERO.highestToleratedReturnCode())
                    .isNotEqualTo(ConditionCodeGate.WARNINGS_TOLERATED.highestToleratedReturnCode());
        }

        @ParameterizedTest(name = "the strict gate {1} a highest prior return code of {0}")
        @CsvSource({"0, admits", "1, refuses", "4, refuses", "5, refuses", "12, refuses"})
        @DisplayName("the strict gate admits nothing above zero")
        void theStrictGateAdmitsNothingAboveZero(final int returnCode, final String expectation) {
            assertThat(ConditionCodeGate.ALL_PRIOR_STEPS_ZERO.permits(returnCode))
                    .isEqualTo("admits".equals(expectation));
        }

        @ParameterizedTest(name = "the tolerant gate {1} a highest prior return code of {0}")
        @CsvSource({"0, admits", "1, admits", "4, admits", "5, refuses", "12, refuses"})
        @DisplayName("the tolerant gate admits up to and including four, which is the whole correction")
        void theTolerantGateAdmitsUpToAndIncludingFour(final int returnCode, final String expectation) {
            assertThat(ConditionCodeGate.WARNINGS_TOLERATED.permits(returnCode))
                    .isEqualTo("admits".equals(expectation));
        }

        @ParameterizedTest(name = "{0} is rejected as a return code")
        @ValueSource(ints = {-1, -4, Integer.MIN_VALUE})
        @DisplayName("a negative code is refused outright, because no completion code is negative")
        void aNegativeCodeIsRefusedOutright(final int negative) {
            for (ConditionCodeGate gate : ConditionCodeGate.values()) {
                assertThatExceptionOfType(IllegalArgumentException.class)
                        .isThrownBy(() -> gate.permits(negative))
                        .withMessageContaining(String.valueOf(negative));
            }
        }

        @Test
        @DisplayName("with no step yet run both gates permit, because there is no earlier code to exceed "
                + "either ceiling")
        void withNoStepYetRunBothGatesPermit() {
            JobExecution execution = jobExecution();
            for (ConditionCodeGate gate : ConditionCodeGate.values()) {
                assertThat(gate.decide(execution, null).getName())
                        .isEqualTo(ConditionCodeGate.PERMITTED);
            }
        }

        @Test
        @DisplayName("a step that completed carrying a framework exit code contributes a clean code, so "
                + "both gates permit")
        void aCleanCompletionLetsBothGatesPermit() {
            JobExecution execution = jobExecution();
            StepExecution completed = stepEndedWith(execution, STEP_NAME, BatchStatus.COMPLETED,
                    ExitStatus.COMPLETED);

            for (ConditionCodeGate gate : ConditionCodeGate.values()) {
                assertThat(gate.decide(execution, completed).getName())
                        .isEqualTo(ConditionCodeGate.PERMITTED);
            }
        }

        @Test
        @DisplayName("a step that did nothing is read as clean, not as a remark, even though its exit code "
                + "is short enough to be mistaken for a completion code")
        void aStepThatDidNothingIsReadAsClean() {
            JobExecution execution = jobExecution();
            StepExecution didNothing = stepEndedWith(execution, STEP_NAME, BatchStatus.COMPLETED,
                    ExitStatus.NOOP);

            for (ConditionCodeGate gate : ConditionCodeGate.values()) {
                assertThat(gate.decide(execution, didNothing).getName())
                        .as("gate %s must admit a step that had nothing to do", gate.name())
                        .isEqualTo(ConditionCodeGate.PERMITTED);
            }
        }

        @Test
        @DisplayName("a step that completed with no exit status at all is read as clean rather than as a "
                + "failure")
        void aCompletionWithoutAnExitStatusIsReadAsClean() {
            JobExecution execution = jobExecution();
            StepExecution completed = stepEndedWith(execution, STEP_NAME, BatchStatus.COMPLETED,
                    new ExitStatus(""));

            assertThat(ConditionCodeGate.ALL_PRIOR_STEPS_ZERO.decide(execution, completed).getName())
                    .isEqualTo(ConditionCodeGate.PERMITTED);
        }

        @Test
        @DisplayName("a step that states its own completion code has it taken at face value, and the two "
                + "gates then disagree - which is exactly why both forms exist")
        void aStatedCompletionCodeIsTakenAtFaceValue() {
            JobExecution execution = jobExecution();
            StepExecution warned = stepEndedWith(execution, STEP_NAME, BatchStatus.COMPLETED,
                    new ExitStatus("4"));

            assertThat(ConditionCodeGate.ALL_PRIOR_STEPS_ZERO.decide(execution, warned).getName())
                    .as("the statement job stops on a warning")
                    .isEqualTo(ConditionCodeGate.REFUSED);
            assertThat(ConditionCodeGate.WARNINGS_TOLERATED.decide(execution, warned).getName())
                    .as("the backup job runs through a warning")
                    .isEqualTo(ConditionCodeGate.PERMITTED);
        }

        @Test
        @DisplayName("a step that completed carrying an exit code of its own, not written as digits, is "
                + "read as a warning rather than as clean")
        void aNonFrameworkExitCodeIsReadAsAWarning() {
            JobExecution execution = jobExecution();
            StepExecution flagged = stepEndedWith(execution, STEP_NAME, BatchStatus.COMPLETED,
                    new ExitStatus("COMPLETED WITH A REMARK"));

            assertThat(ConditionCodeGate.ALL_PRIOR_STEPS_ZERO.decide(execution, flagged).getName())
                    .isEqualTo(ConditionCodeGate.REFUSED);
            assertThat(ConditionCodeGate.WARNINGS_TOLERATED.decide(execution, flagged).getName())
                    .isEqualTo(ConditionCodeGate.PERMITTED);
        }

        @ParameterizedTest(name = "a step that ended {0} makes every gate refuse")
        @CsvSource({"FAILED", "ABANDONED", "STOPPED", "UNKNOWN"})
        @DisplayName("a step that did not complete contributes a failure code, above every ceiling")
        void aStepThatDidNotCompleteMakesEveryGateRefuse(final BatchStatus status) {
            JobExecution execution = jobExecution();
            StepExecution ended = stepEndedWith(execution, STEP_NAME, status, ExitStatus.FAILED);

            for (ConditionCodeGate gate : ConditionCodeGate.values()) {
                assertThat(gate.decide(execution, ended).getName())
                        .as("gate %s must refuse after a %s step", gate.name(), status)
                        .isEqualTo(ConditionCodeGate.REFUSED);
            }
        }

        @ParameterizedTest(name = "a step still {0} contributes nothing")
        @CsvSource({"STARTING", "STARTED", "STOPPING"})
        @DisplayName("a step that has not ended contributes nothing, because no completion code exists "
                + "for it yet")
        void aStepThatHasNotEndedContributesNothing(final BatchStatus running) {
            JobExecution execution = jobExecution();
            StepExecution inFlight = stepEndedWith(execution, STEP_NAME, running, null);

            assertThat(ConditionCodeGate.ALL_PRIOR_STEPS_ZERO.decide(execution, inFlight).getName())
                    .isEqualTo(ConditionCodeGate.PERMITTED);
        }

        @Test
        @DisplayName("an exit code longer than a completion code is not read as one, so it cannot overflow "
                + "the parse")
        void anOverlongDigitRunIsNotReadAsACompletionCode() {
            JobExecution execution = jobExecution();
            StepExecution completed = stepEndedWith(execution, STEP_NAME, BatchStatus.COMPLETED,
                    new ExitStatus("99999999999999999999"));

            assertThat(ConditionCodeGate.WARNINGS_TOLERATED.decide(execution, completed).getName())
                    .as("read as a remark rather than as a number, and the tolerant gate admits a remark")
                    .isEqualTo(ConditionCodeGate.PERMITTED);
            assertThat(ConditionCodeGate.ALL_PRIOR_STEPS_ZERO.decide(execution, completed).getName())
                    .isEqualTo(ConditionCodeGate.REFUSED);
        }

        @Test
        @DisplayName("the highest code across several steps decides, not the latest one")
        void theHighestCodeAcrossSeveralStepsDecides() {
            JobExecution execution = jobExecution();
            stepEndedWith(execution, STEP_NAME, BatchStatus.COMPLETED, new ExitStatus("4"));
            StepExecution latest = stepEndedWith(execution, OTHER_STEP_NAME, BatchStatus.COMPLETED,
                    ExitStatus.COMPLETED);

            assertThat(ConditionCodeGate.ALL_PRIOR_STEPS_ZERO.decide(execution, latest).getName())
                    .as("the earlier warning still counts even though the latest step was clean")
                    .isEqualTo(ConditionCodeGate.REFUSED);
        }

        @Test
        @DisplayName("the two published outcome names are distinct and are not framework status names")
        void theTwoOutcomeNamesAreDistinctAndNotFrameworkNames() {
            assertThat(ConditionCodeGate.PERMITTED).isNotEqualTo(ConditionCodeGate.REFUSED);
            assertThat(List.of(ConditionCodeGate.PERMITTED, ConditionCodeGate.REFUSED))
                    .doesNotContain(FlowExecutionStatus.COMPLETED.getName(),
                            FlowExecutionStatus.FAILED.getName(),
                            FlowExecutionStatus.STOPPED.getName(),
                            FlowExecutionStatus.UNKNOWN.getName());
        }

        @Test
        @DisplayName("a null job execution is refused rather than gated on")
        void aNullJobExecutionIsRefusedRatherThanGatedOn() {
            for (ConditionCodeGate gate : ConditionCodeGate.values()) {
                assertThatExceptionOfType(NullPointerException.class)
                        .isThrownBy(() -> gate.decide(null, null));
            }
        }
    }

    @Nested
    @DisplayName("The shared job-boundary diagnostic")
    class BoundaryDiagnostic {

        /** The configuration's own logger, captured so each diagnostic can be read back. */
        private Logger logger;

        /** Records every event the listener emits during one test. */
        private ListAppender<ILoggingEvent> recorder;

        /** The level the logger held before this group touched it. */
        private Level originalLevel;

        /** The listener under test, obtained the way a job configuration obtains it. */
        private JobExecutionListener listener;

        @BeforeEach
        void attachRecorder() {
            listener = new BatchConfig().batchJobBoundaryListener();
            logger = (Logger) LoggerFactory.getLogger(BatchConfig.class);
            originalLevel = logger.getLevel();
            // Pinned rather than inherited, so this group asserts the listener and not the ambient setup.
            logger.setLevel(Level.INFO);
            recorder = new ListAppender<>();
            recorder.setContext(logger.getLoggerContext());
            recorder.start();
            logger.addAppender(recorder);
        }

        @AfterEach
        void detachRecorder() {
            logger.detachAppender(recorder);
            recorder.stop();
            logger.setLevel(originalLevel);
        }

        /**
         * The one event the listener emitted, formatted.
         *
         * @return the rendered message of the single recorded event
         */
        private String onlyMessage() {
            assertThat(recorder.list).hasSize(1);
            return recorder.list.get(0).getFormattedMessage();
        }

        /**
         * The level of the one event the listener emitted.
         *
         * @return the level of the single recorded event
         */
        private Level onlyLevel() {
            assertThat(recorder.list).hasSize(1);
            return recorder.list.get(0).getLevel();
        }

        @Test
        @DisplayName("the start announcement names the job and its parameter KEYS, in a stable order")
        void theStartAnnouncementNamesTheJobAndItsParameterKeys() {
            listener.beforeJob(jobExecution());

            assertThat(onlyLevel()).isEqualTo(Level.INFO);
            assertThat(onlyMessage())
                    .contains("START OF JOB " + JOB_NAME)
                    .contains("jobInstanceId=11")
                    .contains("jobExecutionId=22")
                    .as("sorted, so two runs of one job produce comparable lines")
                    .contains("parameterKeys=[" + EARLIER_PARAMETER_KEY + ", " + PARAMETER_KEY + "]");
        }

        @Test
        @DisplayName("no parameter VALUE reaches the log line, because the log stream leaves the process")
        void noParameterValueReachesTheLogLine() {
            listener.beforeJob(jobExecution());

            assertThat(onlyMessage())
                    .as("the values remain readable in the framework's own parameter table instead")
                    .doesNotContain(PARAMETER_VALUE);
        }

        @Test
        @DisplayName("a clean completion is announced informationally, with the exit code and step count")
        void aCleanCompletionIsAnnouncedInformationally() {
            JobExecution execution = jobExecution();
            stepEndedWith(execution, STEP_NAME, BatchStatus.COMPLETED, ExitStatus.COMPLETED);
            execution.setStatus(BatchStatus.COMPLETED);
            execution.setExitStatus(ExitStatus.COMPLETED);

            listener.afterJob(execution);

            assertThat(onlyLevel()).isEqualTo(Level.INFO);
            assertThat(onlyMessage())
                    .contains("END OF JOB " + JOB_NAME)
                    .contains("status=COMPLETED")
                    .contains("exitCode=COMPLETED")
                    .contains("stepsExecuted=1");
        }

        @Test
        @DisplayName("an unsuccessful job is an error and names the steps responsible, without rendering "
                + "a throwable or the framework's exit description")
        void anUnsuccessfulJobIsAnErrorThatNamesTheStepsResponsible() {
            JobExecution execution = jobExecution();
            stepEndedWith(execution, STEP_NAME, BatchStatus.COMPLETED, ExitStatus.COMPLETED);
            stepEndedWith(execution, OTHER_STEP_NAME, BatchStatus.FAILED, ExitStatus.FAILED);
            execution.setStatus(BatchStatus.FAILED);
            execution.setExitStatus(ExitStatus.FAILED.addExitDescription(
                    new IllegalStateException("a rejected daily transaction record")));
            execution.addFailureException(new IllegalStateException("a rejected daily transaction record"));

            listener.afterJob(execution);

            assertThat(onlyLevel()).isEqualTo(Level.ERROR);
            assertThat(onlyMessage())
                    .contains("JOB " + JOB_NAME + " TERMINATED ABNORMALLY")
                    .contains("status=FAILED")
                    .contains("unsuccessfulSteps=[" + OTHER_STEP_NAME + "=FAILED]")
                    .as("the clean step is not named as responsible")
                    .doesNotContain(STEP_NAME + "=")
                    .contains("failureCount=1");
            assertThat(recorder.list.get(0).getThrowableProxy())
                    .as("no throwable is ever handed to the logging framework")
                    .isNull();
            assertThat(onlyMessage())
                    .as("the framework renders a stack trace into its exit description, so it is withheld")
                    .doesNotContain("IllegalStateException");
        }

        @Test
        @DisplayName("a job that stopped without failing is a warning, because work remains undone with "
                + "nothing having gone wrong")
        void aJobThatStoppedWithoutFailingIsAWarning() {
            JobExecution execution = jobExecution();
            execution.setStatus(BatchStatus.STOPPED);
            execution.setExitStatus(ExitStatus.STOPPED);

            listener.afterJob(execution);

            assertThat(onlyLevel()).isEqualTo(Level.WARN);
            assertThat(onlyMessage())
                    .contains("JOB " + JOB_NAME + " ENDED WITHOUT COMPLETING")
                    .contains("status=STOPPED")
                    .contains("stepsExecuted=0");
        }

        @Test
        @DisplayName("an execution assembled without an instance is still describable, so a diagnostic "
                + "never fails on the value it was written to explain")
        void anExecutionWithoutAnInstanceIsStillDescribable() {
            JobExecution instanceless = new JobExecution(33L);
            instanceless.setStatus(BatchStatus.COMPLETED);
            instanceless.setExitStatus(ExitStatus.COMPLETED);

            listener.afterJob(instanceless);

            assertThat(onlyMessage())
                    .contains("(unnamed)")
                    .contains("jobExecutionId=33");
        }

        @Test
        @DisplayName("both callbacks reject a null execution, because a null there is a caller defect")
        void bothCallbacksRejectANullExecution() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> listener.beforeJob(null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> listener.afterJob(null));
            assertThat(recorder.list)
                    .as("a rejected call emits nothing")
                    .isEmpty();
        }
    }
}
