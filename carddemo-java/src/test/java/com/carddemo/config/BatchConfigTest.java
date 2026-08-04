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

import com.carddemo.config.BatchConfig.ConditionCodeGate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit contract for {@link BatchConfig}, the shared infrastructure configuration used by the migrated
 * batch jobs. Its load-bearing guarantee is negative: importing the configuration refreshes and closes a
 * Spring context without launching, scheduling or otherwise starting a job. Jobs remain on-demand
 * operations exposed by {@code com.carddemo.api.BatchJobController}.
 *
 * <p><strong>Startup and framework boundary.</strong> The production configuration contributes only its
 * shared {@link JobExecutionListener} and {@link JobParametersIncrementer}. The repository, transaction
 * manager, launcher and operator are supplied as mocks in the behavioural slice so refresh and shutdown
 * can prove that the launch surface remains untouched. Framework-owned repository infrastructure is not
 * redeclared. The secondary annotation guard records an important Spring Boot 3.x rule:
 * {@link EnableBatchProcessing} would make batch auto-configuration back off and remove the wiring it
 * appears to enable.</p>
 *
 * <p><strong>Schema boundary.</strong> Spring Batch provisions its own {@code BATCH_*} metadata objects.
 * This test therefore verifies the real configuration values and the static exclusion rule: the eleven
 * application table names contain neither a case-insensitive {@code batch_} prefix nor
 * {@code flyway_schema_history}, and the migration text creates no prefixed table. The runtime table
 * census against PostgreSQL belongs to {@code CardDemoApplicationIT}; {@link BatchConfigIT} owns the
 * runtime wiring and no-auto-execution checks. Migration inventory, layout, indexes and foreign keys
 * remain the responsibility of {@link FlywayConfigTest}.</p>
 *
 * <p><strong>Legacy job inventory.</strong> Across 29 JCL members (28 {@code .jcl} and one
 * {@code .JCL}) and two cataloged procedures there are 79 {@code EXEC PGM=} lines. Only nine invoke
 * application programs; the utility histogram is IDCAMS 52, SDSF 8, SORT 5, IEFBR14 3, IEBGENER 1 and
 * DFHCSDUP 1. Those categories total 70 utility invocations, correcting the source plan's inconsistent
 * arithmetic. They map to schema migration, in-job comparison and managed-service concerns rather than
 * extra Spring Batch steps, which explains why the migration has nine job configuration classes rather
 * than 79.</p>
 *
 * <ul>
 *   <li>{@code app/jcl/INTCALC.jcl:22 STEP15 -> CBACT04C}</li>
 *   <li>{@code app/jcl/POSTTRAN.jcl:23 STEP15 -> CBTRN02C}</li>
 *   <li>{@code app/jcl/READACCT.jcl:22 STEP05 -> CBACT01C}</li>
 *   <li>{@code app/jcl/READCARD.jcl:22 STEP05 -> CBACT02C}</li>
 *   <li>{@code app/jcl/READCUST.jcl:6 STEP05 -> CBCUS01C}</li>
 *   <li>{@code app/jcl/READXREF.jcl:22 STEP05 -> CBACT03C}</li>
 *   <li>{@code app/jcl/TRANREPT.jcl:59 STEP10R -> CBTRN03C}</li>
 *   <li>{@code app/proc/TRANREPT.prc:57 STEP10R -> CBTRN03C}</li>
 *   <li>{@code app/jcl/CREASTMT.JCL:79 STEP040 -> CBSTM03A}, gated by
 *       {@code COND=(0,NE)}</li>
 * </ul>
 *
 * <p><strong>Condition-code correction.</strong> Six {@code COND=} occurrences exist, but only four are
 * step gates. {@code CREASTMT.JCL} STEP020, STEP030 and STEP040 use {@code COND=(0,NE)};
 * {@code app/jcl/TRANBKP.jcl:51} STEP10 uses {@code COND=(4,LT)} and therefore tolerates warnings. The
 * occurrences at {@code app/jcl/TRANREPT.jcl:47} and {@code app/proc/TRANREPT.prc:45} are DFSORT
 * {@code INCLUDE COND=} record filters. {@code app/jcl/PRTCATBL.jcl} has no application invocation and
 * is not CBACT03C-driven; CBACT03C is invoked only by {@code READXREF.jcl}. The estate carries four
 * distinct DFSORT specifications, not three.</p>
 *
 * <p><strong>Deliberate exclusions and ordering.</strong>
 * {@code com.carddemo.batch.DailyTransactionReadJobConfig} represents
 * {@code app/cbl/CBTRN01C.cbl}, a complete 491-line, 18-paragraph program that no JCL member, procedure
 * or CSD entry invokes. It is tested while remaining outside the default pipeline, and the estate has no
 * master scheduler. The CLOSEFIL, OPENFIL and CBADMCDJ members are intentionally not migrated and no
 * artifact is expected for them. Parallel and partitioned execution stay absent because the 80-, 100-,
 * 133- and 430-byte fixed-width outputs depend on deterministic record order for byte parity. The exact
 * two-bean inventory exposes no execution-sizing or fault-tolerance value; job-specific configuration
 * remains outside this shared class and is not inspected through private implementation details.</p>
 *
 * <p><strong>Duplication boundary.</strong> Execution sequencing, transitions, rejection output and
 * fixed-width rendering belong to the batch integration tier; the launch and status endpoint contract
 * belongs to API tests. Logger and metric contracts remain in {@link ObservabilityConfigTest}. This class
 * uses independent expectations and never asks production formatters, mappers or writers to generate
 * expected values.</p>
 */
@DisplayName("batch infrastructure: two shared beans, two gates that must not be collapsed, and an inert start-up")
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
