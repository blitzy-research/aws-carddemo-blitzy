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
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import org.springframework.batch.core.partition.PartitionHandler;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.batch.JobLauncherApplicationRunner;
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
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.transaction.PlatformTransactionManager;

import com.carddemo.batch.step.StagedGenerationStore;

import com.carddemo.config.BatchConfig.ConditionCodeGate;
import com.carddemo.service.JobCompletionEvent;
import com.carddemo.service.JobCompletionEventPublisher;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.mockito.InOrder;

/**
 * Unit contract for {@link BatchConfig}, the shared infrastructure configuration used by the migrated
 * batch jobs. Its load-bearing guarantee is negative: importing the configuration refreshes and closes a
 * Spring context without launching, scheduling or otherwise starting a job. Jobs remain on-demand
 * operations exposed by {@code com.carddemo.api.BatchJobController}.
 *
 * <p><strong>What this class asserts.</strong></p>
 *
 * <ul>
 *   <li>The published inventory is exactly two beans - the shared {@link JobExecutionListener} and the
 *       shared {@link JobParametersIncrementer} - and no job repository, launcher, explorer, registry or
 *       operator, which is why an isolated context refreshes with no data source at all.</li>
 *   <li>Nothing fires when that context starts: no runner, no self-starting lifecycle participant, no
 *       application-event listener, no scheduler and no parallel-execution infrastructure, and both the
 *       shared document and the suite overlay independently disable launch-on-start and name no job for
 *       automatic launch. The behavioural slice supplies the repository, transaction manager, launcher
 *       and operator as mocks purely so refresh and shutdown can prove the launch surface is untouched.</li>
 *   <li>{@link EnableBatchProcessing} stays absent, because under Spring Boot 3.x declaring it makes
 *       batch auto-configuration back off and removes the wiring it appears to enable.</li>
 *   <li>The metadata-table ownership boundary holds: Spring Batch provisions its own {@code BATCH_*}
 *       objects, the eleven application table names carry neither a case-insensitive {@code batch_}
 *       prefix nor {@code flyway_schema_history}, and the migration text creates no prefixed table.</li>
 *   <li>Exactly one condition-code gate constant exists and it admits nothing above zero, so the strict
 *       rule the migration plan freezes for all four gates cannot be loosened, together with the rules
 *       for reading a completion code off a step - a clean read for a step that did nothing or ended
 *       without an exit status, a face-value read for a step that states its own code, a failure code
 *       for one that did not complete, nothing at all for one that has not ended, and the highest code
 *       across steps deciding.</li>
 *   <li>The shared job-boundary diagnostic announces parameter KEYS and never a parameter VALUE, because
 *       the log stream leaves the process, and reports each terminal outcome at its own level.</li>
 * </ul>
 *
 * <p><strong>Condition-code inventory, recorded as metadata.</strong> Six {@code COND=} occurrences
 * exist, but only four are step gates: {@code CREASTMT.JCL} STEP020, STEP030 and STEP040, and
 * {@code app/jcl/TRANBKP.jcl:51} STEP10. The occurrences at {@code app/jcl/TRANREPT.jcl:47} and
 * {@code app/proc/TRANREPT.prc:45} are DFSORT {@code INCLUDE COND=} record filters. The migration plan
 * is the frozen contract for how the four gates translate and it defines <strong>all four</strong> as
 * the strict form - run the guarded step only when every earlier step returned exactly zero - which is
 * the single rule asserted below. The fourth member's literal is spelled differently from the other
 * three and read on its own would admit a prior warning; that observation is recorded in
 * {@code docs/decision-log.md} entry DL-145 and is deliberately <em>not</em> asserted as behaviour
 * here, because a test that protected the looser reading would make a departure from the plan look
 * correct. {@code app/jcl/PRTCATBL.jcl} has no application invocation and is not CBACT03C-driven;
 * CBACT03C is invoked only by {@code READXREF.jcl}. The estate carries four distinct DFSORT
 * specifications, not three.</p>
 *
 * <p><strong>Boundaries.</strong> {@link BatchConfigIT} owns the runtime table census against PostgreSQL
 * as well as the runtime wiring and no-auto-execution checks. Migration inventory, layout, indexes and
 * foreign keys belong to {@link FlywayConfigTest}. Execution sequencing, step transitions, rejection
 * output and fixed-width rendering belong to the batch integration tier, and the launch and status
 * endpoint contract to the API tests. Logger and metric contracts belong to
 * {@link ObservabilityConfigTest}. This class uses independent expectations throughout and never asks a
 * production formatter, mapper or writer to generate the value it then verifies, nor reaches into private
 * implementation details of a job-specific configuration.
 *
 * <p><strong>Deliberate absences, and why they are absences rather than gaps.</strong>
 * {@code com.carddemo.batch.DailyTransactionReadJobConfig} stands for {@code app/cbl/CBTRN01C.cbl}, a
 * complete 491-line, 18-paragraph program that no JCL member, procedure or CSD entry invokes; it is
 * therefore exercised by tests while staying outside the default pipeline, and the estate carries no
 * master scheduler for it to join. The CLOSEFIL, OPENFIL and CBADMCDJ members are intentionally not
 * migrated, so no artifact is expected for them. Parallel and partitioned execution stay absent because
 * the 80-, 100-, 133- and 430-byte fixed-width outputs depend on deterministic record order for byte
 * parity, and the two-bean inventory deliberately exposes no execution-sizing or fault-tolerance value
 * that could reintroduce either.
 */
@DisplayName("batch infrastructure: two shared beans, one strict condition-code gate, and an inert start-up")
public final class BatchConfigTest {

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

    /** The suite overlay that must repeat the launch and metadata safeguards explicitly. */
    private static final String TEST_CONFIGURATION = "application-test.yml";

    /** Every application configuration name, including the test name resolved from both classpath roots. */
    private static final List<String> PROFILE_CONFIGURATIONS = List.of(
            SHARED_CONFIGURATION, "application-local.yml", TEST_CONFIGURATION, "application-prod.yml");

    /** The setting that keeps the framework's start-up runner out of the context. */
    private static final String KEY_BATCH_JOB_ENABLED = "spring.batch.job.enabled";

    /** The setting that hands the framework's metadata tables to the framework rather than a migration. */
    private static final String KEY_BATCH_INITIALIZE_SCHEMA = "spring.batch.jdbc.initialize-schema";

    /** The metadata-table prefix a table census must exclude. */
    private static final String KEY_BATCH_TABLE_PREFIX = "spring.batch.jdbc.table-prefix";

    /** The setting that would name a single job to run on start; it must be absent entirely. */
    private static final String KEY_BATCH_JOB_NAME = "spring.batch.job.name";

    /** The complete application-table census to which the metadata exclusion rule is applied. */
    private static final Set<String> APPLICATION_TABLES = Set.of(
            "account",
            "card",
            "customer",
            "card_cross_reference",
            "transaction",
            "daily_transaction",
            "transaction_category_balance",
            "disclosure_group",
            "transaction_type",
            "transaction_category",
            "user_security");

    /** Every migration visible to the module, independent of its current inventory or folder. */
    private static final String MIGRATION_PATTERN = "classpath*:db/migration/**/*.sql";

    /** SQL comments are removed before object names are inspected, preventing comment-only false hits. */
    private static final Pattern SQL_COMMENTS =
            Pattern.compile("(?s)/\\*.*?\\*/|(?m:--[^\\r\\n]*)");

    /** Captures the unqualified table identifier of a create-table statement. */
    private static final Pattern CREATED_TABLE = Pattern.compile(
            "\\bCREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?"
                    + "(?:[\"`]?\\w+[\"`]?\\.)?[\"`]?([A-Z_][A-Z0-9_]*)[\"`]?",
            Pattern.CASE_INSENSITIVE);

    /** A job name for the assembled executions below. */
    private static final String JOB_NAME = "postTransactionJob";

    /** A step name for the assembled step executions below. */
    private static final String STEP_NAME = "postDailyTransactionsStep";

    /** A second step name, so a highest-of-several assertion has more than one contributor. */
    private static final String OTHER_STEP_NAME = "writeRejectRecordsStep";

    /** The generation base the reject writer of the posting job registers under. */
    private static final String REJECT_GENERATION_BASE = "AWS.M2.CARDDEMO.DALYREJS";

    /** The parameter key whose value must never reach a log line. */
    private static final String PARAMETER_KEY = "reportStartDate";

    /** The parameter value that must never reach a log line. */
    private static final String PARAMETER_VALUE = "2022-01-01";

    /** A second parameter key, declared out of order so the reported order is provably sorted. */
    private static final String EARLIER_PARAMETER_KEY = "interestParmDate";

    /** Repository collaborator supplied only to make the isolated launch boundary explicit. */
    private final JobRepository jobRepository = mock(JobRepository.class);

    /** Transaction collaborator supplied only to make the isolated launch boundary explicit. */
    private final PlatformTransactionManager transactionManager =
            mock(PlatformTransactionManager.class);

    /** Launch collaborator whose untouched invocation ledger proves that no job starts. */
    private final JobLauncher jobLauncher = mock(JobLauncher.class);

    /** Operational collaborator whose untouched invocation ledger proves that no job starts. */
    private final JobOperator jobOperator = mock(JobOperator.class);

    /** The runner used for contribution assertions: the real configuration and nothing else. */
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(BatchConfig.class);

    /** The behavioural slice, containing only the configuration and the four inert collaborators. */
    private final ApplicationContextRunner collaboratorRunner = new ApplicationContextRunner()
            .withUserConfiguration(BatchConfig.class)
            .withBean("jobRepository", JobRepository.class, () -> jobRepository)
            .withBean("transactionManager", PlatformTransactionManager.class, () -> transactionManager)
            .withBean("jobLauncher", JobLauncher.class, () -> jobLauncher)
            .withBean("jobOperator", JobOperator.class, () -> jobOperator);

    /** A detector slice that activates annotation processing without enabling scheduling. */
    private final ApplicationContextRunner scheduledTaskDetector = new ApplicationContextRunner()
            .withUserConfiguration(BatchConfig.class)
            .withBean("scheduledAnnotationBeanPostProcessor",
                    ScheduledAnnotationBeanPostProcessor.class,
                    ScheduledAnnotationBeanPostProcessor::new);

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
     * Reads one key from the first classpath resource carrying a configuration name.
     *
     * <p>The test resource root precedes the production root under surefire, so asking for
     * {@value #TEST_CONFIGURATION} deliberately validates the suite overlay rather than its packaged
     * counterpart.</p>
     *
     * @param document the classpath resource name
     * @param key the property key to resolve
     * @return the textual value the document declares, or {@code null} when it declares none
     */
    private static String classpathProperty(final String document, final String key) {
        return propertyValue(new ClassPathResource(document), key);
    }

    /**
     * Reads one key from every YAML document held by a resource.
     *
     * @param resource the YAML resource to read
     * @param key the property key to resolve
     * @return the textual value the resource declares, or {@code null} when it declares none
     */
    private static String propertyValue(final Resource resource, final String key) {
        try {
            List<PropertySource<?>> documents =
                    new YamlPropertySourceLoader().load(resource.getDescription(), resource);
            for (PropertySource<?> document : documents) {
                if (document instanceof EnumerablePropertySource<?> enumerable
                        && enumerable.containsProperty(key)) {
                    Object value = enumerable.getProperty(key);
                    return value == null ? null : String.valueOf(value);
                }
            }
            return null;
        } catch (IOException unreadable) {
            throw new UncheckedIOException(
                    "configuration resource must be readable: " + resource.getDescription(),
                    unreadable);
        }
    }

    /**
     * Finds every occurrence of one configuration name across the test and production classpath roots.
     *
     * @param document the resource name to find
     * @return all matching resources, never {@code null}
     */
    private static List<Resource> classpathResources(final String document) {
        return resourcesMatching("classpath*:" + document);
    }

    /**
     * Resolves a classpath pattern without leaking checked I/O failures into behavioural tests.
     *
     * @param pattern the resource pattern to resolve
     * @return every matching resource, never {@code null}
     */
    private static List<Resource> resourcesMatching(final String pattern) {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources(pattern);
            return List.of(resources);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(
                    "classpath resources must be discoverable: " + pattern, unreadable);
        }
    }

    /**
     * Reads a classpath resource as UTF-8 text without requiring it to be a filesystem file.
     *
     * @param resource the resource to read
     * @return its complete UTF-8 content
     */
    private static String resourceText(final Resource resource) {
        try (InputStream content = resource.getInputStream()) {
            return new String(content.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(
                    "classpath resource must be readable: " + resource.getDescription(), unreadable);
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
        @DisplayName("the isolated context refreshes and closes without touching either launch surface")
        void mockedCollaboratorsRemainUntouchedAcrossRefreshAndShutdown() {
            collaboratorRunner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(JobRepository.class)).isSameAs(jobRepository);
                assertThat(context.getBean(PlatformTransactionManager.class))
                        .isSameAs(transactionManager);
                assertThat(context.getBean(JobLauncher.class)).isSameAs(jobLauncher);
                assertThat(context.getBean(JobOperator.class)).isSameAs(jobOperator);
            });

            verifyNoInteractions(jobLauncher, jobOperator);
        }

        @Test
        @DisplayName("no runner, self-starting lifecycle or application event listener is contributed")
        void contributesNoAutomaticStartBean() {
            runner.run(context -> {
                assertThat(context).doesNotHaveBean(ApplicationRunner.class);
                assertThat(context).doesNotHaveBean(CommandLineRunner.class);
                assertThat(context).doesNotHaveBean(SmartLifecycle.class);
                assertThat(context).doesNotHaveBean(ApplicationListener.class);
                assertThat(context).doesNotHaveBean(JobLauncherApplicationRunner.class);
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
        @DisplayName("no scheduler or parallel-execution infrastructure is contributed")
        void contributesNoSchedulingOrParallelExecutionInfrastructure() {
            runner.run(context -> {
                assertThat(context).doesNotHaveBean(SchedulingConfigurer.class);
                assertThat(context).doesNotHaveBean(TaskScheduler.class);
                assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
                assertThat(context).doesNotHaveBean(Executor.class);
                assertThat(context).doesNotHaveBean(TaskExecutor.class);
                assertThat(context).doesNotHaveBean(PartitionHandler.class);
                assertThat(context).doesNotHaveBean(Partitioner.class);
            });
        }

        @Test
        @DisplayName("an active scheduled-method detector finds no scheduled task")
        void containsNoScheduledMethodDrivenBean() {
            scheduledTaskDetector.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
                assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class)
                        .getScheduledTasks()).isEmpty();
            });
        }

        /**
         * Keeps annotation inspection secondary to the refresh, bean-role and zero-interaction proofs.
         *
         * <p>Under Spring Boot 3.x, {@link EnableBatchProcessing} makes batch auto-configuration back off.
         * Adding it would therefore remove the repository wiring that it appears to enable. Scheduling is
         * equally opt-in and is excluded because this slice has no unattended trigger.</p>
         */
        @Test
        @DisplayName("the enabling annotations stay absent and the behavioural slice remains inert")
        void enablingAnnotationsRemainAbsentAsASecondaryGuard() {
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(JobRepository.class);
                assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
            });

            MergedAnnotations annotations =
                    MergedAnnotations.from(BatchConfig.class, SearchStrategy.TYPE_HIERARCHY);
            assertThat(annotations.isPresent(EnableBatchProcessing.class))
                    .as("BatchConfig must not switch off the auto-configuration it depends on")
                    .isFalse();
            assertThat(annotations.isPresent(EnableScheduling.class))
                    .as("BatchConfig must not introduce an unattended trigger")
                    .isFalse();
        }

        @Test
        @DisplayName("the shared document disables launch-on-start")
        void sharedDocumentDisablesLaunchOnStart() {
            assertThat(classpathProperty(SHARED_CONFIGURATION, KEY_BATCH_JOB_ENABLED))
                    .as("restarting a deployment must not fire a posting run")
                    .isEqualTo("false");
        }

        @Test
        @DisplayName("the suite overlay independently disables launch-on-start")
        void testOverlayDisablesLaunchOnStart() {
            assertThat(classpathProperty(TEST_CONFIGURATION, KEY_BATCH_JOB_ENABLED))
                    .as("the test classpath must not make context refresh launch a job")
                    .isEqualTo("false");
        }

        @Test
        @DisplayName("no profile document names a job for automatic launch")
        void noProfileDocumentNamesAJob() {
            for (String document : PROFILE_CONFIGURATIONS) {
                List<Resource> resources = classpathResources(document);
                assertThat(resources)
                        .as("%s must resolve from at least one classpath root", document)
                        .isNotEmpty();
                for (Resource resource : resources) {
                    assertThat(propertyValue(resource, KEY_BATCH_JOB_NAME))
                            .as("%s must not select a job", resource.getDescription())
                            .isNull();
                }
            }
        }

        @Test
        @DisplayName("the shared boundary listener performs diagnostics without reaching launch services")
        void sharedListenerCallbacksRemainInert() {
            JobExecution execution = mock(JobExecution.class);
            when(execution.getJobInstance()).thenReturn(new JobInstance(41L, "inertBoundaryJob"));
            when(execution.getJobParameters()).thenReturn(
                    new JobParametersBuilder().toJobParameters());
            when(execution.getStatus()).thenReturn(BatchStatus.COMPLETED);
            when(execution.getExitStatus()).thenReturn(ExitStatus.COMPLETED);
            when(execution.getStepExecutions()).thenReturn(Set.of());
            when(execution.getAllFailureExceptions()).thenReturn(List.of());

            collaboratorRunner.run(context -> {
                JobExecutionListener listener = context.getBean(JobExecutionListener.class);
                assertThatCode(() -> {
                    listener.beforeJob(execution);
                    listener.afterJob(execution);
                }).doesNotThrowAnyException();
            });

            verifyNoInteractions(jobLauncher, jobOperator);
        }
    }

    @Nested
    @DisplayName("The metadata-table ownership boundary")
    class MetadataTableBoundary {

        @Test
        @DisplayName("the shared document delegates metadata schema creation to Spring Batch")
        void sharedDocumentDelegatesMetadataSchemaCreation() {
            assertThat(classpathProperty(SHARED_CONFIGURATION, KEY_BATCH_INITIALIZE_SCHEMA))
                    .isEqualTo("always");
            assertThat(classpathProperty(SHARED_CONFIGURATION, KEY_BATCH_TABLE_PREFIX))
                    .isEqualTo("BATCH_");
        }

        @Test
        @DisplayName("the suite overlay independently delegates metadata schema creation")
        void testOverlayDelegatesMetadataSchemaCreation() {
            assertThat(classpathProperty(TEST_CONFIGURATION, KEY_BATCH_INITIALIZE_SCHEMA))
                    .isEqualTo("always");
        }

        @Test
        @DisplayName("the eleven application tables exclude framework and migration-history tables")
        void applicationTableSetExpressesTheExclusionRule() {
            assertThat(APPLICATION_TABLES).hasSize(11);
            assertThat(APPLICATION_TABLES).allSatisfy(table -> {
                String canonical = table.toLowerCase(Locale.ROOT);
                assertThat(canonical).doesNotStartWith("batch_");
                assertThat(canonical).isNotEqualTo("flyway_schema_history");
            });
        }

        @Test
        @DisplayName("the migrations create no Spring Batch metadata table")
        void migrationsCreateNoBatchMetadataTable() {
            List<Resource> migrations = resourcesMatching(MIGRATION_PATTERN);
            assertThat(migrations)
                    .as("at least one migration must be read so the assertion cannot pass vacuously")
                    .isNotEmpty();

            int createdTableCount = 0;
            for (Resource migration : migrations) {
                String statements = SQL_COMMENTS.matcher(resourceText(migration)).replaceAll("");
                Matcher tableNames = CREATED_TABLE.matcher(statements);
                while (tableNames.find()) {
                    createdTableCount++;
                    String tableName = tableNames.group(1).toLowerCase(Locale.ROOT);
                    assertThat(tableName)
                            .as("%s must not create a framework metadata table",
                                    migration.getDescription())
                            .doesNotStartWith("batch_")
                            .isNotEqualTo("flyway_schema_history");
                }
            }
            assertThat(createdTableCount)
                    .as("the scan must encounter application table creation")
                    .isPositive();
        }
    }

    /**
     * The one condition-code step gate, asserted against the plan's single strict rule.
     *
     * <p>The plan defines every one of the estate's four {@code COND=} step gates as the strict form, so
     * exactly one ceiling exists and it is zero. These assertions therefore refuse every code above zero
     * - one, the four a completion code may state, five and the failure code twelve - and refuse a
     * completed step whose exit code is a remark rather than a number, which is the case a bare
     * framework-status transition would have admitted. The differently spelled literal on
     * {@code app/jcl/TRANBKP.jcl:51} is metadata recorded in the class documentation above and in
     * {@code docs/decision-log.md} entry DL-145; nothing here asserts a tolerance for it.</p>
     */
    @Nested
    @DisplayName("The one condition-code step gate")
    class StepGates {

        @Test
        @DisplayName("there is exactly one, and its ceiling is zero, so no looser form exists to select")
        void thereIsExactlyOneGateWhoseCeilingIsZero() {
            assertThat(ConditionCodeGate.values())
                    .as("a second constant would describe a gate the plan does not define")
                    .containsExactly(ConditionCodeGate.ALL_PRIOR_STEPS_ZERO);
            assertThat(ConditionCodeGate.ALL_PRIOR_STEPS_ZERO.highestToleratedReturnCode())
                    .as("the ceiling of all four gates: app/jcl/CREASTMT.JCL lines 56, 66 and 79 and "
                            + "app/jcl/TRANBKP.jcl:51")
                    .isZero();
        }

        @ParameterizedTest(name = "the gate {1} a highest prior return code of {0}")
        @CsvSource({"0, admits", "1, refuses", "4, refuses", "5, refuses", "12, refuses"})
        @DisplayName("the gate admits nothing above zero, four included")
        void theGateAdmitsNothingAboveZero(final int returnCode, final String expectation) {
            assertThat(ConditionCodeGate.ALL_PRIOR_STEPS_ZERO.permits(returnCode))
                    .isEqualTo("admits".equals(expectation));
        }

        @ParameterizedTest(name = "{0} is rejected as a return code")
        @ValueSource(ints = {-1, -4, Integer.MIN_VALUE})
        @DisplayName("a negative code is refused outright, because no completion code is negative")
        void aNegativeCodeIsRefusedOutright(final int negative) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> ConditionCodeGate.ALL_PRIOR_STEPS_ZERO.permits(negative))
                    .withMessageContaining(String.valueOf(negative));
        }

        @Test
        @DisplayName("with no step yet run the gate permits, because there is no earlier code to exceed "
                + "the ceiling")
        void withNoStepYetRunTheGatePermits() {
            JobExecution execution = jobExecution();

            assertThat(ConditionCodeGate.ALL_PRIOR_STEPS_ZERO.decide(execution, null).getName())
                    .isEqualTo(ConditionCodeGate.PERMITTED);
        }

        @Test
        @DisplayName("a step that completed carrying a framework exit code contributes a clean code, so "
                + "the gate permits")
        void aCleanCompletionLetsTheGatePermit() {
            JobExecution execution = jobExecution();
            StepExecution completed = stepEndedWith(execution, STEP_NAME, BatchStatus.COMPLETED,
                    ExitStatus.COMPLETED);

            assertThat(ConditionCodeGate.ALL_PRIOR_STEPS_ZERO.decide(execution, completed).getName())
                    .isEqualTo(ConditionCodeGate.PERMITTED);
        }

        @Test
        @DisplayName("a step that did nothing is read as clean, not as a remark, even though its exit code "
                + "is short enough to be mistaken for a completion code")
        void aStepThatDidNothingIsReadAsClean() {
            JobExecution execution = jobExecution();
            StepExecution didNothing = stepEndedWith(execution, STEP_NAME, BatchStatus.COMPLETED,
                    ExitStatus.NOOP);

            assertThat(ConditionCodeGate.ALL_PRIOR_STEPS_ZERO.decide(execution, didNothing).getName())
                    .as("the gate must admit a step that had nothing to do")
                    .isEqualTo(ConditionCodeGate.PERMITTED);
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

        @ParameterizedTest(name = "a step stating completion code {0} is refused")
        @CsvSource({"1", "4", "5", "12", "16"})
        @DisplayName("a step that states its own completion code has it taken at face value, and any "
                + "nonzero one is refused - four included")
        void aStatedCompletionCodeIsTakenAtFaceValue(final String statedCode) {
            JobExecution execution = jobExecution();
            StepExecution stated = stepEndedWith(execution, STEP_NAME, BatchStatus.COMPLETED,
                    new ExitStatus(statedCode));

            assertThat(ConditionCodeGate.ALL_PRIOR_STEPS_ZERO.decide(execution, stated).getName())
                    .as("the gate stops on a stated code of %s, because its ceiling is zero", statedCode)
                    .isEqualTo(ConditionCodeGate.REFUSED);
        }

        @Test
        @DisplayName("a step that completed carrying an exit code of its own, not written as digits, is "
                + "read as a remark rather than as clean, and is refused")
        void aNonFrameworkExitCodeIsRefused() {
            JobExecution execution = jobExecution();
            StepExecution flagged = stepEndedWith(execution, STEP_NAME, BatchStatus.COMPLETED,
                    new ExitStatus("COMPLETED WITH A REMARK"));

            assertThat(ConditionCodeGate.ALL_PRIOR_STEPS_ZERO.decide(execution, flagged).getName())
                    .as("a completed step that had something to say did not report zero, and a bare "
                            + "framework-status transition would have let it through")
                    .isEqualTo(ConditionCodeGate.REFUSED);
        }

        @ParameterizedTest(name = "a step that ended {0} makes the gate refuse")
        @CsvSource({"FAILED", "ABANDONED", "STOPPED", "UNKNOWN"})
        @DisplayName("a step that did not complete contributes a failure code, above the ceiling")
        void aStepThatDidNotCompleteMakesTheGateRefuse(final BatchStatus status) {
            JobExecution execution = jobExecution();
            StepExecution ended = stepEndedWith(execution, STEP_NAME, status, ExitStatus.FAILED);

            assertThat(ConditionCodeGate.ALL_PRIOR_STEPS_ZERO.decide(execution, ended).getName())
                    .as("the gate must refuse after a %s step", status)
                    .isEqualTo(ConditionCodeGate.REFUSED);
        }

        @ParameterizedTest(name = "a step that ended {0} while claiming exit code 0 still makes every "
                + "gate refuse")
        @CsvSource({"FAILED", "ABANDONED", "STOPPED", "UNKNOWN"})
        @DisplayName("the terminal status is decisive: an unsuccessful step carrying a numeric exit code "
                + "that reads as clean is still a failure, because a number must never speak for a step "
                + "that did not complete")
        void anUnsuccessfulStepClaimingACleanCodeStillMakesEveryGateRefuse(final BatchStatus status) {
            JobExecution execution = jobExecution();
            StepExecution ended = stepEndedWith(execution, STEP_NAME, status, new ExitStatus("0"));

            for (ConditionCodeGate gate : ConditionCodeGate.values()) {
                assertThat(gate.decide(execution, ended).getName())
                        .as("gate %s must refuse after a %s step whatever its exit code says",
                                gate.name(), status)
                        .isEqualTo(ConditionCodeGate.REFUSED);
            }
        }

        @ParameterizedTest(name = "a step that ended {0} while carrying the framework completion code "
                + "still makes every gate refuse")
        @CsvSource({"FAILED", "ABANDONED", "STOPPED", "UNKNOWN"})
        @DisplayName("an unsuccessful step whose exit status was overwritten with the framework's own "
                + "completion code is still a failure, so an exit status a listener rewrote cannot admit "
                + "a step the estate would have skipped")
        void anUnsuccessfulStepCarryingTheCompletionCodeStillMakesEveryGateRefuse(
                final BatchStatus status) {
            JobExecution execution = jobExecution();
            StepExecution ended = stepEndedWith(execution, STEP_NAME, status, ExitStatus.COMPLETED);

            for (ConditionCodeGate gate : ConditionCodeGate.values()) {
                assertThat(gate.decide(execution, ended).getName())
                        .as("gate %s must refuse after a %s step even when its exit code says COMPLETED",
                                gate.name(), status)
                        .isEqualTo(ConditionCodeGate.REFUSED);
            }
        }

        @ParameterizedTest(name = "a step that ended {0} while claiming a tolerable warning still makes "
                + "every gate refuse")
        @CsvSource({"FAILED", "ABANDONED", "STOPPED", "UNKNOWN"})
        @DisplayName("an unsuccessful step is above every ceiling, so not even the tolerant gate admits "
                + "one that states a code the tolerant gate would otherwise allow")
        void anUnsuccessfulStepClaimingAToleratedCodeStillMakesEveryGateRefuse(
                final BatchStatus status) {
            JobExecution execution = jobExecution();
            StepExecution ended = stepEndedWith(execution, STEP_NAME, status, new ExitStatus("4"));

            for (ConditionCodeGate gate : ConditionCodeGate.values()) {
                assertThat(gate.decide(execution, ended).getName())
                        .as("gate %s must refuse after a %s step, warning ceiling or not",
                                gate.name(), status)
                        .isEqualTo(ConditionCodeGate.REFUSED);
            }
        }

        @Test
        @DisplayName("a step still executing by exit code but completed by status is read as clean, "
                + "because that exit code carries no completion code of its own")
        void aCompletedStepCarryingTheExecutingCodeIsReadAsClean() {
            JobExecution execution = jobExecution();
            StepExecution completed = stepEndedWith(execution, STEP_NAME, BatchStatus.COMPLETED,
                    ExitStatus.EXECUTING);

            for (ConditionCodeGate gate : ConditionCodeGate.values()) {
                assertThat(gate.decide(execution, completed).getName())
                        .as("gate %s must admit a completed step whose exit code states nothing",
                                gate.name())
                        .isEqualTo(ConditionCodeGate.PERMITTED);
            }
        }

        @Test
        @DisplayName("one unsuccessful step dominates a run of clean ones, so a failure earlier in the "
                + "job cannot be averaged away by later successes")
        void oneUnsuccessfulStepDominatesARunOfCleanOnes() {
            JobExecution execution = jobExecution();
            stepEndedWith(execution, STEP_NAME, BatchStatus.FAILED, new ExitStatus("0"));
            StepExecution latest = stepEndedWith(execution, OTHER_STEP_NAME, BatchStatus.COMPLETED,
                    ExitStatus.COMPLETED);

            for (ConditionCodeGate gate : ConditionCodeGate.values()) {
                assertThat(gate.decide(execution, latest).getName())
                        .as("gate %s must refuse: the earlier step failed", gate.name())
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
                + "the parse, and it is refused as a remark")
        void anOverlongDigitRunIsNotReadAsACompletionCode() {
            JobExecution execution = jobExecution();
            StepExecution completed = stepEndedWith(execution, STEP_NAME, BatchStatus.COMPLETED,
                    new ExitStatus("99999999999999999999"));

            assertThatCode(() -> ConditionCodeGate.ALL_PRIOR_STEPS_ZERO.decide(execution, completed))
                    .as("a run of digits too long to be a completion code must not be parsed as one")
                    .doesNotThrowAnyException();
            assertThat(ConditionCodeGate.ALL_PRIOR_STEPS_ZERO.decide(execution, completed).getName())
                    .as("read as a remark rather than as a number, and a remark is above the ceiling")
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
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> ConditionCodeGate.ALL_PRIOR_STEPS_ZERO.decide(null, null));
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

        /**
         * The staging root this nest hands the listener.
         *
         * <p>Its own, and empty: the listener sweeps this root after a job that did not complete, and a
         * unit test must not aim that sweep at a directory it does not own.
         */
        @TempDir
        private Path stagingRoot;

        @BeforeEach
        void attachRecorder() {
            listener = listenerWith(null, null);
            logger = (Logger) LoggerFactory.getLogger(BatchConfig.class);
            originalLevel = logger.getLevel();
            // Pinned rather than inherited, so this group asserts the listener and not the ambient setup.
            logger.setLevel(Level.INFO);
            recorder = new ListAppender<>();
            recorder.setContext(logger.getLoggerContext());
            recorder.start();
            logger.addAppender(recorder);
        }

        /**
         * Builds the listener with exactly the optional collaborators a test needs.
         *
         * <p>The staging root is this nest's own temporary directory, because the listener sweeps that
         * root after a job that did not complete (DL-211) and must never be pointed at a shared one from
         * a unit test.
         */
        private JobExecutionListener listenerWith(final StagedGenerationStore store,
                final JobCompletionEventPublisher completionPublisher) {
            return new BatchConfig().batchJobBoundaryListener(
                    providerOf(store), providerOf(completionPublisher),
                    this.stagingRoot.toString());
        }

        /**
         * Creates an object provider whose optional value is fixed for the life of the test.
         */
        private <T> ObjectProvider<T> providerOf(final T value) {
            final ObjectProvider<T> provider = mock();
            when(provider.getIfAvailable()).thenReturn(value);
            return provider;
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
        @DisplayName("a terminal outcome is published as one parameter-free completion event")
        void aTerminalOutcomeIsPublishedAsACompletionEvent() {
            final JobCompletionEventPublisher publisher = mock();
            listener = listenerWith(null, publisher);
            final JobExecution execution = jobExecution();
            stepEndedWith(execution, STEP_NAME, BatchStatus.COMPLETED, ExitStatus.COMPLETED);
            execution.setStatus(BatchStatus.COMPLETED);
            execution.setExitStatus(ExitStatus.COMPLETED);

            listener.afterJob(execution);

            final ArgumentCaptor<JobCompletionEvent> captured =
                    ArgumentCaptor.forClass(JobCompletionEvent.class);
            verify(publisher).publishCompletion(captured.capture());
            final JobCompletionEvent event = captured.getValue();
            assertThat(event.jobName()).isEqualTo(JOB_NAME);
            assertThat(event.jobInstanceId()).isEqualTo(11L);
            assertThat(event.jobExecutionId()).isEqualTo(22L);
            assertThat(event.status()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(event.exitCode()).isEqualTo(ExitStatus.COMPLETED.getExitCode());
            assertThat(event.stepsExecuted()).isOne();
            assertThat(recorder.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message)
                            .contains("Published terminal completion event"));
        }

        /**
         * Stages one <em>real</em> completed generation and its working sibling in this nest's own root.
         *
         * <p>Real files, because the property under test is a deletion. A registration naming a path that
         * was never created can be published, refused and cleaned up without any of it touching a
         * filesystem, so the assertions such a test can make stop at status and event ordering - which is
         * exactly how a missing cleanup path survived. The working sibling is created alongside the
         * completed file because the discard removes both, and its removal is asserted separately.
         *
         * @param  generation the generation number to name the pair after
         * @return the completed path, which is the one a step registers
         * @throws IOException if either file cannot be written
         */
        private Path stageRealGenerationPair(final long generation) throws IOException {
            final Path completed = StagedGenerationStore.generationPath(
                    this.stagingRoot, REJECT_GENERATION_BASE, generation);
            Files.writeString(completed, "sealed reject records", StandardCharsets.US_ASCII);
            Files.writeString(StagedGenerationStore.workingPath(completed), "partial",
                    StandardCharsets.US_ASCII);
            return completed;
        }

        @Test
        @DisplayName("durable artifacts publish before notification, and a committed publication keeps "
                + "the local generations it published")
        void durablePublicationPrecedesNotification() throws IOException {
            final StagedGenerationStore store = mock();
            final JobCompletionEventPublisher publisher = mock();
            listener = listenerWith(store, publisher);
            final JobExecution execution = jobExecution();
            final StepExecution step =
                    stepEndedWith(execution, STEP_NAME, BatchStatus.COMPLETED, ExitStatus.COMPLETED);
            execution.setStatus(BatchStatus.COMPLETED);
            execution.setExitStatus(ExitStatus.COMPLETED);
            final Path published = stageRealGenerationPair(1);
            StagedGenerationStore.register(step, REJECT_GENERATION_BASE, published,
                    StagedGenerationStore.STANDARD_RETENTION_LIMIT);
            when(store.publishRegistered(execution)).thenReturn(List.of());

            listener.afterJob(execution);

            final InOrder order = inOrder(store, publisher);
            order.verify(store).publishRegistered(execution);
            order.verify(publisher).publishCompletion(argThat(event ->
                    event.jobExecutionId().equals(execution.getId())
                            && event.status() == BatchStatus.COMPLETED));
            assertThat(published)
                    .as("a publication that committed KEEPS its local generation. The local file is the "
                            + "fixed-name view of output that is now durable, and the discard added for "
                            + "the failure arms must not reach this one - deleting it would remove real "
                            + "output from a job that succeeded")
                    .exists();
        }

        @Test
        @DisplayName("a publication that throws fails the job AND discards the local generations it "
                + "orphaned, before the failed terminal event leaves the process")
        void aFailedPublicationDiscardsTheLocalGenerationsItOrphaned() throws IOException {
            // The gap this closes: the discard used to be reached only from the arm taken when the
            // execution ARRIVED here already non-COMPLETED. A completed job whose publication threw was
            // downgraded to FAILED further down, past that branch, and returned - leaving a completed
            // local generation on disk, readable, and resolvable by name to anything that asked the store
            // for the current local generation of its base. See docs/decision-log.md entry DL-290.
            final StagedGenerationStore store = mock();
            final JobCompletionEventPublisher publisher = mock();
            listener = listenerWith(store, publisher);
            final JobExecution execution = jobExecution();
            final StepExecution step =
                    stepEndedWith(execution, STEP_NAME, BatchStatus.COMPLETED, ExitStatus.COMPLETED);
            execution.setStatus(BatchStatus.COMPLETED);
            execution.setExitStatus(ExitStatus.COMPLETED);
            final Path orphaned = stageRealGenerationPair(2);
            final Path workingSibling = StagedGenerationStore.workingPath(orphaned);
            StagedGenerationStore.register(step, REJECT_GENERATION_BASE, orphaned,
                    StagedGenerationStore.STANDARD_RETENTION_LIMIT);
            when(store.publishRegistered(execution))
                    .thenThrow(new IllegalStateException("object store refused publication"));
            // Captured from inside the notification, so the ordering claim is observed rather than
            // inferred: a subscriber must never be told a job FAILED while its local generations are
            // still on disk.
            final boolean[] stillPresentWhenTheEventFired = {true};
            doAnswer(invocation -> {
                stillPresentWhenTheEventFired[0] = Files.exists(orphaned);
                return null;
            }).when(publisher).publishCompletion(argThat(event -> true));

            listener.afterJob(execution);

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getExitStatus().getExitCode())
                    .isEqualTo(ExitStatus.FAILED.getExitCode());
            assertThat(execution.getAllFailureExceptions())
                    .as("the publication failure is preserved as the job's own, and the cleanup adds "
                            + "nothing to it")
                    .singleElement()
                    .isInstanceOf(IllegalStateException.class)
                    .satisfies(failure -> {
                        assertThat(failure.getMessage())
                                .contains("durable batch artifact publication failed");
                        assertThat(failure.getCause())
                                .as("the store's own reason survives as the cause")
                                .hasMessage("object store refused publication");
                    });
            assertThat(orphaned)
                    .as("the completed generation named nothing durable, because nothing was published")
                    .doesNotExist();
            assertThat(workingSibling)
                    .as("and the working sibling the store names for it goes with it")
                    .doesNotExist();
            assertThat(StagedGenerationStore.currentLocalGeneration(
                    this.stagingRoot, REJECT_GENERATION_BASE))
                    .as("nothing resolves as the current local generation of the base any more, which is "
                            + "the question a later component actually asks - a file that merely looks "
                            + "deleted to a directory listing would still answer it")
                    .isEmpty();
            verify(publisher).publishCompletion(argThat(event ->
                    event.jobExecutionId().equals(execution.getId())
                            && event.status() == BatchStatus.FAILED));
            assertThat(stillPresentWhenTheEventFired[0])
                    .as("the discard ran BEFORE the terminal event, not after it")
                    .isFalse();
        }

        @Test
        @DisplayName("a completed job that registered artifacts with no store available fails AND "
                + "discards them too, because that arm also returns without publishing")
        void anAbsentStoreDiscardsTheLocalGenerationsItCannotPublish() throws IOException {
            // The third arm, and the one easiest to overlook: no store, so publishRegistered is never
            // reached and there is no exception to catch, yet the job still ends FAILED with its local
            // generations orphaned exactly as in the throwing case.
            final JobCompletionEventPublisher publisher = mock();
            listener = listenerWith(null, publisher);
            final JobExecution execution = jobExecution();
            final StepExecution step =
                    stepEndedWith(execution, STEP_NAME, BatchStatus.COMPLETED, ExitStatus.COMPLETED);
            execution.setStatus(BatchStatus.COMPLETED);
            execution.setExitStatus(ExitStatus.COMPLETED);
            final Path orphaned = stageRealGenerationPair(3);
            StagedGenerationStore.register(step, REJECT_GENERATION_BASE, orphaned,
                    StagedGenerationStore.STANDARD_RETENTION_LIMIT);

            listener.afterJob(execution);

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getAllFailureExceptions())
                    .singleElement()
                    .satisfies(failure -> assertThat(failure.getCause())
                            .hasMessageContaining("no generation store is"));
            assertThat(orphaned).doesNotExist();
            assertThat(StagedGenerationStore.workingPath(orphaned)).doesNotExist();
            assertThat(StagedGenerationStore.currentLocalGeneration(
                    this.stagingRoot, REJECT_GENERATION_BASE)).isEmpty();
            verify(publisher).publishCompletion(argThat(event ->
                    event.status() == BatchStatus.FAILED));
        }

        @Test
        @DisplayName("notification failure is warned and never rewrites the established batch verdict")
        void notificationFailureIsNonFatal() {
            final JobCompletionEventPublisher publisher = mock();
            listener = listenerWith(null, publisher);
            final JobExecution execution = jobExecution();
            stepEndedWith(execution, STEP_NAME, BatchStatus.COMPLETED, ExitStatus.COMPLETED);
            execution.setStatus(BatchStatus.COMPLETED);
            execution.setExitStatus(ExitStatus.COMPLETED);
            doThrow(new IllegalStateException("topic unavailable"))
                    .when(publisher).publishCompletion(argThat(event -> true));

            listener.afterJob(execution);

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(execution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
            assertThat(recorder.list)
                    .anySatisfy(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.WARN);
                        assertThat(event.getFormattedMessage())
                                .contains("Could not publish terminal completion event")
                                .contains("failureType=IllegalStateException")
                                .doesNotContain("topic unavailable");
                        assertThat(event.getThrowableProxy()).isNull();
                    });
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
