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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carddemo.batch.FileProbeJobConfig.ProbeMode;
import com.carddemo.batch.step.AbstractCobolStep;
import com.carddemo.config.BatchConfig;
import com.carddemo.domain.Account;
import com.carddemo.domain.Card;
import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.Customer;
import com.carddemo.domain.enums.FileStatus;
import com.carddemo.exception.AbendException;
import com.carddemo.exception.FileStatusException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.service.AbendService;
import com.carddemo.service.DateValidationService;
import com.carddemo.service.FileMaintenanceService;
import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.support.TestDataFactory;
import com.carddemo.support.TestDataFactory.FieldSpec;
import com.carddemo.support.TestDataFactory.RecordLayout;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestClassOrder;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.SimpleJob;
import org.springframework.batch.core.job.flow.FlowJob;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.step.tasklet.TaskletStep;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.actuate.autoconfigure.tracing.prometheus.PrometheusExemplarsAutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.batch.JobLauncherApplicationRunner;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Integration specification for the single parameterised file-probe job against the migrated PostgreSQL
 * schema, the real repositories, the real sequential readers and the framework launch-by-name boundary.
 *
 * <p>The source estate has four verification members and four readers, but only one behavioural shape:
 * open an indexed resource, traverse it sequentially, emit each record to the diagnostic channel, reach
 * end of file and close. Their procedure divisions contain 6, 5, 5 and 5 paragraph units respectively,
 * for 21 units represented by this one job.
 *
 * <p>Provenance is the matrix header only: checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68}, dated 2022-07-19. The stamp is not asserted per member because
 * the estate also contains later, differently stamped and unstamped artifacts.
 *
 * <p>The job's product is not a data file. It has no fixed-width writer and no applicable output-record
 * width; its complete observable product is the ordered structured diagnostic stream plus the terminal
 * status. This test consequently measures record references, ordering and clean termination rather than
 * inventing an output artifact.
 *
 * <p>Sequential access over each indexed resource means ascending business-key order. The fixture keys
 * are sliced with the independent test layouts and sorted with {@link String#compareTo(String)}. A live
 * repository scan is checked in both ascending and descending directions to defeat insertion-order luck,
 * and the ascending expectation is then encoded through the module's confidentiality boundary solely so
 * it can be compared with the non-reversible references the diagnostic stream is permitted to expose.
 * That boundary does not choose, sort, map or otherwise generate the expected record sequence.
 *
 * <p>The status contract remains two levels. A raw two-byte status first normalises to the coarse values
 * zero, sixteen or twelve; only then does the coarse value select continue, clean end-of-file or
 * diagnostic-then-abend. The intermediate result is referenced 223 times across the estate, so preserving
 * it is a compatibility requirement rather than an implementation preference. The tri-state is owned as
 * a nested type by {@link AbstractCobolStep}; this test reaches it only through observable behaviour.
 *
 * <p>Only success, end of file and record-not-found are compared by the source estate. Statuses
 * {@code 22} and {@code 35} occur in earlier specification material but are not compared in the source;
 * this discrepancy is a decision-log candidate, and no assertion below depends on either value.
 *
 * <p>The four members all use the same legacy step name. A single stable Java step therefore preserves
 * the source identity rule rather than discarding four distinguishable names. The mode is late-bound in
 * step scope, while both framework names remain configuration-time constants.
 *
 * <p>The customer member carries two anomalies which are recorded and not propagated: its notification
 * keyword contains a misspelt system-user substitution symbol, and its 15 lines carry no licence block
 * while the three siblings each contain 31 lines. No target configuration key is invented for the first,
 * and the Apache header remains unconditional despite the second.
 *
 * <p>The cross-reference has two valid widths serving different representations. Its three data fields
 * occupy 16, 9 and 11 bytes; the mainframe data-set form then carries 14 filler bytes to reach 50, while
 * the delivered ASCII fixture ends after 36 data bytes and must never be padded for comparison.
 *
 * <p>Failure ordering is part of the output contract. The raw status diagnostic is emitted before the
 * abend diagnostic, and both exist before the exception reaches its caller. The exception retains the
 * raw two-byte status as its cause and renders the four-part context through the widths published by
 * {@link AbendException}.
 */
@SpringBootTest(classes = FileProbeJobConfigIT.JobContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.flyway.enabled=false", "spring.main.banner-mode=off",
                "spring.jpa.hibernate.ddl-auto=validate", "spring.batch.job.enabled=false",
                "management.endpoint.health.validate-group-membership=false",
                "management.tracing.enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("FileProbeJobConfigIT - one job, one step, four ordered diagnostic probes")
final class FileProbeJobConfigIT extends AbstractPostgresIT {

    private static final int SEEDED_ROWS = 50;

    private static final String STEP_TIMER = "carddemo.batch.fileprobe.step";

    private static final String RECORD_COUNTER = "carddemo.batch.fileprobe.records";

    private static final String SCOPED_TARGET_PREFIX = "scopedTarget.";

    private static final String UNKNOWN_MODE = "unknownProbe";

    private static final String ERROR_OPERATION = "READING";

    private final Map<ProbeMode, JobExecution> executions = new EnumMap<>(ProbeMode.class);

    private final Map<ProbeMode, List<String>> diagnosticStreams = new EnumMap<>(ProbeMode.class);

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = PrometheusExemplarsAutoConfiguration.class)
    @Import({FileProbeJobConfig.class, BatchConfig.class, JobParameterValidators.class,
            DateValidationService.class, FileMaintenanceService.class, AbendService.class})
    @EnableJpaRepositories(basePackageClasses = AccountRepository.class)
    @EntityScan(basePackageClasses = Account.class)
    static class JobContext {

        @Bean
        Clock batchClock() {
            return FileProbeJobConfigIT.FIXED_CLOCK;
        }
    }

    private JobRegistry jobRegistry;

    private JobExplorer jobExplorer;

    private JobOperator jobOperator;

    private ConfigurableApplicationContext applicationContext;

    private Environment environment;

    private FileProbeJobConfig configuration;

    private FileMaintenanceService fileMaintenanceService;

    private AbendService abendService;

    private AccountRepository accountRepository;

    private CardRepository cardRepository;

    private CustomerRepository customerRepository;

    private CardCrossReferenceRepository crossReferenceRepository;

    private MeterRegistry meterRegistry;

    private Logger serviceLogger;

    private Logger configurationLogger;

    private Logger abendLogger;

    private Level previousServiceLevel;

    private Level previousConfigurationLevel;

    private Level previousAbendLevel;

    private ListAppender<ILoggingEvent> logRecorder;

    @Autowired
    void injectCollaborators(final JobRegistry suppliedJobRegistry,
            final JobExplorer suppliedJobExplorer,
            final JobOperator suppliedJobOperator,
            final ConfigurableApplicationContext suppliedApplicationContext,
            final Environment suppliedEnvironment,
            final FileProbeJobConfig suppliedConfiguration,
            final FileMaintenanceService suppliedFileMaintenanceService,
            final AbendService suppliedAbendService,
            final AccountRepository suppliedAccountRepository,
            final CardRepository suppliedCardRepository,
            final CustomerRepository suppliedCustomerRepository,
            final CardCrossReferenceRepository suppliedCrossReferenceRepository,
            final MeterRegistry suppliedMeterRegistry) {
        this.jobRegistry = suppliedJobRegistry;
        this.jobExplorer = suppliedJobExplorer;
        this.jobOperator = suppliedJobOperator;
        this.applicationContext = suppliedApplicationContext;
        this.environment = suppliedEnvironment;
        this.configuration = suppliedConfiguration;
        this.fileMaintenanceService = suppliedFileMaintenanceService;
        this.abendService = suppliedAbendService;
        this.accountRepository = suppliedAccountRepository;
        this.cardRepository = suppliedCardRepository;
        this.customerRepository = suppliedCustomerRepository;
        this.crossReferenceRepository = suppliedCrossReferenceRepository;
        this.meterRegistry = suppliedMeterRegistry;
    }

    @BeforeEach
    void captureDiagnosticOutput() {
        this.serviceLogger = (Logger) LoggerFactory.getLogger(FileMaintenanceService.class);
        this.configurationLogger = (Logger) LoggerFactory.getLogger(FileProbeJobConfig.class);
        this.abendLogger = (Logger) LoggerFactory.getLogger(AbendService.class);

        this.previousServiceLevel = this.serviceLogger.getLevel();
        this.previousConfigurationLevel = this.configurationLogger.getLevel();
        this.previousAbendLevel = this.abendLogger.getLevel();

        this.serviceLogger.setLevel(Level.DEBUG);
        this.configurationLogger.setLevel(Level.DEBUG);
        this.abendLogger.setLevel(Level.ERROR);

        this.logRecorder = new ListAppender<>();
        this.logRecorder.setContext(this.serviceLogger.getLoggerContext());
        this.logRecorder.start();
        this.serviceLogger.addAppender(this.logRecorder);
        this.configurationLogger.addAppender(this.logRecorder);
        this.abendLogger.addAppender(this.logRecorder);
    }

    @AfterEach
    void releaseDiagnosticOutput() {
        this.serviceLogger.detachAppender(this.logRecorder);
        this.configurationLogger.detachAppender(this.logRecorder);
        this.abendLogger.detachAppender(this.logRecorder);
        this.logRecorder.stop();

        this.serviceLogger.setLevel(this.previousServiceLevel);
        this.configurationLogger.setLevel(this.previousConfigurationLevel);
        this.abendLogger.setLevel(this.previousAbendLevel);
    }

    private record ModeContract(RecordLayout fixtureLayout, RecordLayout declaredLayout,
            String fixtureName, String keyField, String recordType, String programName,
            String resourceName, String businessKeyProperty, Class<?> entityType,
            Class<?> repositoryType, int plainDiagnosticMultiplicity,
            int totalDiagnosticMultiplicity) {
    }

    private record PrimaryKeyColumn(String tableName, String columnName) {
    }

    private static ModeContract contractFor(final ProbeMode mode) {
        return switch (mode) {
            case ACCOUNT -> new ModeContract(
                    TestDataFactory.ACCOUNT,
                    TestDataFactory.ACCOUNT,
                    "acctdata.txt",
                    "ACCT-ID",
                    "account",
                    "CBACT01C",
                    "ACCTFILE",
                    "acctId",
                    Account.class,
                    AccountRepository.class,
                    1,
                    2);
            case CARD -> new ModeContract(
                    TestDataFactory.CARD,
                    TestDataFactory.CARD,
                    "carddata.txt",
                    "CARD-NUM",
                    "card",
                    "CBACT02C",
                    "CARDFILE",
                    "cardNum",
                    Card.class,
                    CardRepository.class,
                    1,
                    1);
            case CUSTOMER -> new ModeContract(
                    TestDataFactory.CUSTOMER,
                    TestDataFactory.CUSTOMER,
                    "custdata.txt",
                    "CUST-ID",
                    "customer",
                    "CBCUS01C",
                    "CUSTFILE",
                    "custId",
                    Customer.class,
                    CustomerRepository.class,
                    2,
                    2);
            case CROSS_REFERENCE -> new ModeContract(
                    TestDataFactory.CARD_CROSS_REFERENCE_FIXTURE,
                    TestDataFactory.CARD_CROSS_REFERENCE_DATASET,
                    "cardxref.txt",
                    "XREF-CARD-NUM",
                    "card-cross-reference",
                    "CBACT03C",
                    "XREFFILE",
                    "xrefCardNum",
                    CardCrossReference.class,
                    CardCrossReferenceRepository.class,
                    2,
                    2);
        };
    }

    private JobExecution launch(final ProbeMode mode) throws Exception {
        final Properties parameters = new Properties();
        parameters.setProperty(JobParameterValidators.FILE_PROBE_MODE_KEY, mode.parameterValue());
        final Long executionId =
                this.jobOperator.start(FileProbeJobConfig.FILE_PROBE_JOB_NAME, parameters);
        return Objects.requireNonNull(this.jobExplorer.getJobExecution(executionId),
                "the operator returned an execution identifier that the explorer could not resolve");
    }

    private List<String> messages() {
        return this.logRecorder.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static List<String> fixtureLines(final ModeContract contract) throws IOException {
        final ClassPathResource resource =
                new ClassPathResource("fixtures/input/" + contract.fixtureName());
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.US_ASCII))) {
            return reader.lines().toList();
        }
    }

    private static List<String> fixtureKeys(final ProbeMode mode) throws IOException {
        final ModeContract contract = contractFor(mode);
        return fixtureLines(contract).stream()
                .map(line -> contract.fixtureLayout().slice(line, contract.keyField()))
                .sorted(String::compareTo)
                .toList();
    }

    private List<?> repositoryRows(final ProbeMode mode, final Sort order) {
        return switch (mode) {
            case ACCOUNT -> this.accountRepository.findAll(order);
            case CARD -> this.cardRepository.findAll(order);
            case CUSTOMER -> this.customerRepository.findAll(order);
            case CROSS_REFERENCE -> this.crossReferenceRepository.findAll(order);
        };
    }

    private List<String> repositoryKeys(final ProbeMode mode, final Sort order) {
        return switch (mode) {
            case ACCOUNT -> this.accountRepository.findAll(order).stream()
                    .map(Account::getAcctId)
                    .toList();
            case CARD -> this.cardRepository.findAll(order).stream()
                    .map(Card::getCardNum)
                    .toList();
            case CUSTOMER -> this.customerRepository.findAll(order).stream()
                    .map(Customer::getCustId)
                    .toList();
            case CROSS_REFERENCE -> this.crossReferenceRepository.findAll(order).stream()
                    .map(CardCrossReference::getXrefCardNum)
                    .toList();
        };
    }

    private Object repositoryBean(final ProbeMode mode) {
        return switch (mode) {
            case ACCOUNT -> this.accountRepository;
            case CARD -> this.cardRepository;
            case CUSTOMER -> this.customerRepository;
            case CROSS_REFERENCE -> this.crossReferenceRepository;
        };
    }

    private static List<String> recordReferences(final List<String> stream,
            final ProbeMode mode, final boolean plainOnly) {
        final String prefix = "recordType=" + contractFor(mode).recordType() + " recordRef=";
        return stream.stream()
                .filter(message -> message.startsWith(prefix))
                .filter(message -> !plainOnly || !message.contains(" status="))
                .map(message -> {
                    final String reference = message.substring(prefix.length());
                    final int statusOffset = reference.indexOf(" status=");
                    return statusOffset < 0 ? reference : reference.substring(0, statusOffset);
                })
                .toList();
    }

    private static List<String> collapseConsecutiveDuplicates(final List<String> values) {
        final List<String> collapsed = new ArrayList<>(values.size());
        String previous = null;
        for (final String value : values) {
            if (!value.equals(previous)) {
                collapsed.add(value);
                previous = value;
            }
        }
        return List.copyOf(collapsed);
    }

    private static List<String> expectedDiagnosticReferences(final ProbeMode mode)
            throws IOException {
        return fixtureKeys(mode).stream()
                .map(com.carddemo.util.SensitiveLogRedactor::redact)
                .toList();
    }

    private static StepExecution onlyStep(final JobExecution execution) {
        assertThat(execution.getStepExecutions()).singleElement();
        return execution.getStepExecutions().iterator().next();
    }

    private static long batchStepExecutionCount() throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT COUNT(*) FROM batch_step_execution");
                ResultSet row = statement.executeQuery()) {
            assertThat(row.next()).isTrue();
            return row.getLong(1);
        }
    }

    private static List<PrimaryKeyColumn> primaryKeyColumns() throws SQLException {
        final List<PrimaryKeyColumn> columns = new ArrayList<>();
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT constraints.table_name, keys.column_name
                          FROM information_schema.table_constraints constraints
                          JOIN information_schema.key_column_usage keys
                            ON keys.constraint_catalog = constraints.constraint_catalog
                           AND keys.constraint_schema = constraints.constraint_schema
                           AND keys.constraint_name = constraints.constraint_name
                         WHERE constraints.table_schema = 'public'
                           AND constraints.constraint_type = 'PRIMARY KEY'
                           AND constraints.table_name IN
                               ('account', 'card', 'card_cross_reference', 'customer')
                         ORDER BY constraints.table_name, keys.ordinal_position
                        """);
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                columns.add(new PrimaryKeyColumn(rows.getString(1), rows.getString(2)));
            }
        }
        return List.copyOf(columns);
    }

    private static List<String> generatedBusinessColumns() throws SQLException {
        final List<String> columns = new ArrayList<>();
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT table_name || '.' || column_name
                          FROM information_schema.columns
                         WHERE table_schema = 'public'
                           AND table_name IN
                               ('account', 'card', 'card_cross_reference', 'customer')
                           AND (is_identity = 'YES'
                                OR is_generated <> 'NEVER'
                                OR COALESCE(column_default, '') LIKE 'nextval(%')
                         ORDER BY table_name, ordinal_position
                        """);
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                columns.add(rows.getString(1));
            }
        }
        return List.copyOf(columns);
    }

    private static List<String> applicationSequences() throws SQLException {
        final List<String> sequences = new ArrayList<>();
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT sequence_name
                          FROM information_schema.sequences
                         WHERE sequence_schema = 'public'
                           AND sequence_name NOT LIKE 'batch\\_%'
                         ORDER BY sequence_name
                        """);
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                sequences.add(rows.getString(1));
            }
        }
        return List.copyOf(sequences);
    }

    @Nested
    @Order(1)
    @DisplayName("job shape and inert context")
    class JobShapeAndInertContext {

        @Test
        @DisplayName("context startup registers one stable job without launching it")
        void contextStartupRegistersOneStableJobWithoutLaunchingIt() throws Exception {
            assertThat(environment.getProperty("spring.batch.job.enabled", Boolean.class))
                    .isFalse();
            assertThat(environment.getProperty("spring.batch.job.name")).isNull();
            assertThat(applicationContext.getBeansOfType(JobLauncherApplicationRunner.class))
                    .isEmpty();
            assertThat(jobExplorer.getJobInstances(
                    FileProbeJobConfig.FILE_PROBE_JOB_NAME, 0, 1))
                    .as("the context must be inert until an operator launches the job")
                    .isEmpty();

            assertThat(jobRegistry.getJobNames())
                    .containsExactly(FileProbeJobConfig.FILE_PROBE_JOB_NAME);
            assertThat(applicationContext.getBeansOfType(Job.class).keySet())
                    .containsExactly(FileProbeJobConfig.FILE_PROBE_JOB_NAME);

            assertThat(configuration).isNotInstanceOf(CommandLineRunner.class);
            assertThat(configuration).isNotInstanceOf(ApplicationRunner.class);
            assertThat(configuration).isNotInstanceOf(SmartLifecycle.class);
            assertThat(configuration).isNotInstanceOf(ApplicationListener.class);
        }

        @Test
        @DisplayName("the registered job is one transition-free tasklet step with no writer")
        void registeredJobIsOneTransitionFreeTaskletStepWithNoWriter() throws Exception {
            final Job job = jobRegistry.getJob(FileProbeJobConfig.FILE_PROBE_JOB_NAME);

            assertThat(job)
                    .isExactlyInstanceOf(SimpleJob.class)
                    .isNotInstanceOf(FlowJob.class);
            final SimpleJob simpleJob = (SimpleJob) job;
            assertThat(simpleJob.getStepNames())
                    .containsExactly(FileProbeJobConfig.FILE_PROBE_STEP_NAME);

            final Step step = simpleJob.getStep(FileProbeJobConfig.FILE_PROBE_STEP_NAME);
            assertThat(step).isExactlyInstanceOf(TaskletStep.class);
            assertThat(((TaskletStep) step).getTasklet()).isNotNull();
            assertThat(applicationContext.getBeanNamesForType(ItemWriter.class)).isEmpty();

            final ConfigurableListableBeanFactory beanFactory =
                    applicationContext.getBeanFactory();
            assertThat(beanFactory.getBeanDefinition(
                    SCOPED_TARGET_PREFIX + FileProbeJobConfig.FILE_PROBE_TASKLET_BEAN_NAME)
                    .getScope()).isEqualTo("step");

            for (final ProbeMode mode : ProbeMode.values()) {
                assertThat(FileProbeJobConfig.FILE_PROBE_JOB_NAME)
                        .doesNotContain(mode.parameterValue());
                assertThat(FileProbeJobConfig.FILE_PROBE_STEP_NAME)
                        .doesNotContain(mode.parameterValue());
            }
        }
    }

    @Nested
    @Order(2)
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    @DisplayName("four modes and step-scoped late binding")
    class FourModesAndLateBinding {

        @ParameterizedTest(name = "{0}")
        @EnumSource(ProbeMode.class)
        @Order(1)
        @DisplayName("each legal mode launches the same job and binds its measured reader")
        void eachLegalModeLaunchesTheSameJobAndBindsItsMeasuredReader(final ProbeMode mode)
                throws Exception {
            final ModeContract contract = contractFor(mode);
            final List<String> fixture = fixtureLines(contract);
            final List<?> rows = repositoryRows(mode, mode.keyOrder());

            assertThat(mode.legacyProgramName()).isEqualTo(contract.programName());
            assertThat(mode.logicalResourceName()).isEqualTo(contract.resourceName());
            assertThat(mode.businessKeyProperty()).isEqualTo(contract.businessKeyProperty());
            assertThat(mode.recordLength()).isEqualTo(contract.declaredLayout().recordLength());
            assertThat(repositoryBean(mode)).isInstanceOf(contract.repositoryType());
            assertThat(rows)
                    .hasSize(SEEDED_ROWS)
                    .allSatisfy(row -> assertThat(row).isInstanceOf(contract.entityType()));
            assertThat(fixture).hasSize(SEEDED_ROWS);
            assertThat(fixture).allSatisfy(line ->
                    assertThat(line.getBytes(StandardCharsets.US_ASCII).length)
                            .isEqualTo(contract.fixtureLayout().recordLength()));

            final JobExecution execution = launch(mode);
            final StepExecution step = onlyStep(execution);
            final List<String> stream = messages();

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(Objects.requireNonNull(execution.getJobInstance()).getJobName())
                    .isEqualTo(FileProbeJobConfig.FILE_PROBE_JOB_NAME);
            assertThat(execution.getJobParameters()
                    .getString(JobParameterValidators.FILE_PROBE_MODE_KEY))
                    .isEqualTo(mode.parameterValue());
            assertThat(step.getStepName()).isEqualTo(FileProbeJobConfig.FILE_PROBE_STEP_NAME);
            assertThat(step.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(step.getWriteCount()).isZero();
            assertThat(step.getWriteSkipCount()).isZero();
            assertThat(step.getProcessSkipCount()).isZero();
            assertThat(step.getReadSkipCount()).isZero();

            assertThat(stream).contains(
                    "FILE PROBE STARTING - mode=" + mode.parameterValue()
                            + " program=" + contract.programName()
                            + " resource=" + contract.resourceName()
                            + " recordLength=" + mode.recordLength(),
                    "FILE PROBE TERMINAL STATUS - mode=" + mode.parameterValue()
                            + " resource=" + contract.resourceName()
                            + " fileStatus=" + FileStatusException.STATUS_END_OF_FILE
                            + " atEndOfFile=true",
                    "FILE PROBE COMPLETED - mode=" + mode.parameterValue()
                            + " program=" + contract.programName()
                            + " resource=" + contract.resourceName()
                            + " recordsRead=" + SEEDED_ROWS);

            executions.put(mode, execution);
            diagnosticStreams.put(mode, List.copyOf(stream));
        }

        @Test
        @Order(2)
        @DisplayName("the four launches are four instances of one named job and one named step")
        void fourLaunchesAreFourInstancesOfOneNamedJobAndOneNamedStep() throws Exception {
            assertThat(executions)
                    .containsOnlyKeys(ProbeMode.values())
                    .hasSize(ProbeMode.values().length);
            assertThat(diagnosticStreams)
                    .containsOnlyKeys(ProbeMode.values())
                    .hasSize(ProbeMode.values().length);

            assertThat(jobExplorer.getJobInstanceCount(
                    FileProbeJobConfig.FILE_PROBE_JOB_NAME))
                    .isEqualTo(ProbeMode.values().length);
            assertThat(jobExplorer.getJobInstances(
                    FileProbeJobConfig.FILE_PROBE_JOB_NAME, 0, ProbeMode.values().length))
                    .hasSize(ProbeMode.values().length)
                    .extracting(JobInstance::getJobName)
                    .containsOnly(FileProbeJobConfig.FILE_PROBE_JOB_NAME);

            assertThat(executions.values())
                    .allSatisfy(execution -> {
                        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
                        assertThat(execution.getStepExecutions())
                                .singleElement()
                                .extracting(StepExecution::getStepName)
                                .isEqualTo(FileProbeJobConfig.FILE_PROBE_STEP_NAME);
                    });
            assertThat(executions.values())
                    .extracting(execution ->
                            Objects.requireNonNull(execution.getJobInstance()).getInstanceId())
                    .doesNotHaveDuplicates();

            assertThat(jobRegistry.getJobNames())
                    .as("late binding must not register four mode-specific jobs")
                    .containsExactly(FileProbeJobConfig.FILE_PROBE_JOB_NAME);
        }
    }

    @Nested
    @Order(3)
    @DisplayName("validation boundary and exhaustive dispatch")
    class ValidationBoundaryAndExhaustiveDispatch {

        @Test
        @DisplayName("an unrecognised mode is rejected before any step execution exists")
        void unrecognisedModeIsRejectedBeforeAnyStepExecutionExists() throws Exception {
            final long stepsBefore = batchStepExecutionCount();
            final long instancesBefore = jobExplorer.getJobInstanceCount(
                    FileProbeJobConfig.FILE_PROBE_JOB_NAME);
            final Properties parameters = new Properties();
            parameters.setProperty(JobParameterValidators.FILE_PROBE_MODE_KEY, UNKNOWN_MODE);

            final JobParametersInvalidException failure = catchThrowableOfType(
                    JobParametersInvalidException.class,
                    () -> jobOperator.start(FileProbeJobConfig.FILE_PROBE_JOB_NAME, parameters));

            assertThat(failure)
                    .isNotNull()
                    .hasMessageContaining(JobParameterValidators.FILE_PROBE_MODE_KEY)
                    .hasMessageContaining(UNKNOWN_MODE);
            assertThat(batchStepExecutionCount()).isEqualTo(stepsBefore);
            assertThat(jobExplorer.getJobInstanceCount(
                    FileProbeJobConfig.FILE_PROBE_JOB_NAME)).isEqualTo(instancesBefore);
            assertThat(messages())
                    .noneMatch(message -> message.startsWith("FILE PROBE STARTING"));
        }

        @Test
        @DisplayName("every enum constant selects exactly one reader and no arm falls through")
        void everyEnumConstantSelectsExactlyOneReaderAndNoArmFallsThrough() {
            assertThat(ProbeMode.values()).containsExactly(
                    ProbeMode.ACCOUNT,
                    ProbeMode.CARD,
                    ProbeMode.CUSTOMER,
                    ProbeMode.CROSS_REFERENCE);

            for (final ProbeMode mode : ProbeMode.values()) {
                assertThat(ProbeMode.ofParameterValue(mode.parameterValue())).isSameAs(mode);
                final String expectedPrefix =
                        "recordType=" + contractFor(mode).recordType() + " recordRef=";
                assertThat(diagnosticStreams.get(mode))
                        .filteredOn(message -> message.startsWith("recordType="))
                        .allMatch(message -> message.startsWith(expectedPrefix));
            }
        }
    }

    @Nested
    @Order(4)
    @DisplayName("ascending repository scans and diagnostic ordering")
    class AscendingRepositoryScansAndDiagnosticOrdering {

        @ParameterizedTest(name = "{0}")
        @EnumSource(ProbeMode.class)
        @DisplayName("each mode publishes one explicit ascending business-key sort")
        void eachModePublishesOneExplicitAscendingBusinessKeySort(final ProbeMode mode)
                throws Exception {
            final ModeContract contract = contractFor(mode);
            final List<String> expectedAscending = fixtureKeys(mode);
            final List<String> expectedDescending = new ArrayList<>(expectedAscending);
            Collections.reverse(expectedDescending);

            assertThat(mode.keyOrder())
                    .containsExactly(Sort.Order.asc(contract.businessKeyProperty()));
            assertThat(repositoryKeys(mode, mode.keyOrder()))
                    .as("the live repository must honour the ascending business-key order")
                    .containsExactlyElementsOf(expectedAscending);
            assertThat(repositoryKeys(mode,
                    Sort.by(Sort.Direction.DESC, contract.businessKeyProperty())))
                    .as("the reverse scan separates an explicit sort from insertion-order luck")
                    .containsExactlyElementsOf(expectedDescending);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(ProbeMode.class)
        @DisplayName("the emitted references follow the independently sorted fixture keys")
        void emittedReferencesFollowTheIndependentlySortedFixtureKeys(final ProbeMode mode)
                throws Exception {
            final ModeContract contract = contractFor(mode);
            final List<String> stream = diagnosticStreams.get(mode);
            final List<String> allReferences = recordReferences(stream, mode, false);
            final List<String> plainReferences = recordReferences(stream, mode, true);
            final List<String> oneReferencePerRecord =
                    collapseConsecutiveDuplicates(plainReferences);

            assertThat(allReferences)
                    .hasSize(SEEDED_ROWS * contract.totalDiagnosticMultiplicity());
            assertThat(plainReferences)
                    .hasSize(SEEDED_ROWS * contract.plainDiagnosticMultiplicity());
            assertThat(oneReferencePerRecord)
                    .as("deliberate duplicate displays collapse to one structured reference per row")
                    .hasSize(SEEDED_ROWS)
                    .containsExactlyElementsOf(expectedDiagnosticReferences(mode));
        }

        @Test
        @DisplayName("card and cross-reference corroborate the same key sequence independently")
        void cardAndCrossReferenceCorroborateTheSameKeySequenceIndependently() throws Exception {
            assertThat(fixtureKeys(ProbeMode.CARD))
                    .containsExactlyElementsOf(fixtureKeys(ProbeMode.CROSS_REFERENCE));
            assertThat(collapseConsecutiveDuplicates(
                    recordReferences(diagnosticStreams.get(ProbeMode.CARD),
                            ProbeMode.CARD, true)))
                    .containsExactlyElementsOf(collapseConsecutiveDuplicates(
                            recordReferences(diagnosticStreams.get(ProbeMode.CROSS_REFERENCE),
                                    ProbeMode.CROSS_REFERENCE, true)));
        }
    }

    @Nested
    @Order(5)
    @DisplayName("cross-reference data and filler decomposition")
    class CrossReferenceDataAndFillerDecomposition {

        @Test
        @DisplayName("the ASCII fixture carries thirty-six data bytes and no data-set filler")
        void asciiFixtureCarriesThirtySixDataBytesAndNoDataSetFiller() throws Exception {
            final RecordLayout fixture = TestDataFactory.CARD_CROSS_REFERENCE_FIXTURE;
            final RecordLayout dataSet = TestDataFactory.CARD_CROSS_REFERENCE_DATASET;
            final FieldSpec cardNumber = fixture.field("XREF-CARD-NUM");
            final FieldSpec customerId = fixture.field("XREF-CUST-ID");
            final FieldSpec accountId = fixture.field("XREF-ACCT-ID");

            assertThat(cardNumber.offset()).isZero();
            assertThat(cardNumber.width()).isEqualTo(16);
            assertThat(customerId.offset()).isEqualTo(cardNumber.endOffset());
            assertThat(customerId.width()).isEqualTo(9);
            assertThat(accountId.offset()).isEqualTo(customerId.endOffset());
            assertThat(accountId.width()).isEqualTo(11);
            assertThat(accountId.endOffset()).isEqualTo(36);

            assertThat(fixture.recordLength()).isEqualTo(36);
            assertThat(fixture.dataLength()).isEqualTo(36);
            assertThat(fixture.fillerLength()).isZero();
            assertThat(fixture.hasFiller()).isFalse();
            assertThat(dataSet.recordLength()).isEqualTo(50);
            assertThat(dataSet.dataLength()).isEqualTo(36);
            assertThat(dataSet.fillerLength()).isEqualTo(14);
            assertThat(dataSet.fillerImage()
                    .getBytes(StandardCharsets.US_ASCII).length).isEqualTo(14);
            assertThat(ProbeMode.CROSS_REFERENCE.recordLength())
                    .isEqualTo(dataSet.recordLength());

            final List<String> fixtureImages =
                    fixtureLines(contractFor(ProbeMode.CROSS_REFERENCE)).stream()
                            .sorted(String::compareTo)
                            .toList();
            final List<String> repositoryImages =
                    crossReferenceRepository.findAll(ProbeMode.CROSS_REFERENCE.keyOrder()).stream()
                            .map(reference -> reference.getXrefCardNum()
                                    + reference.getXrefCustId()
                                    + reference.getXrefAcctId())
                            .toList();

            assertThat(fixtureImages)
                    .hasSize(SEEDED_ROWS)
                    .allSatisfy(image ->
                            assertThat(image.getBytes(StandardCharsets.US_ASCII).length)
                                    .isEqualTo(fixture.recordLength()))
                    .containsExactlyElementsOf(repositoryImages);
            assertThat(fixtureImages)
                    .noneMatch(image -> image.endsWith(dataSet.fillerImage()));
        }
    }

    @Nested
    @Order(6)
    @DisplayName("status level one: raw code to coarse classification")
    class RawCodeToCoarseClassification {

        @Test
        @DisplayName("success, end of file and error remain three distinct raw classifications")
        void successEndOfFileAndErrorRemainThreeDistinctRawClassifications() {
            assertThat(FileStatus.fromCode(FileStatusException.STATUS_SUCCESS))
                    .contains(FileStatus.SUCCESS);
            assertThat(FileStatus.SUCCESS.isSuccess()).isTrue();
            assertThat(FileStatus.SUCCESS.isEndOfFile()).isFalse();

            assertThat(FileStatus.fromCode(FileStatusException.STATUS_END_OF_FILE))
                    .contains(FileStatus.END_OF_FILE);
            assertThat(FileStatus.END_OF_FILE.isSuccess()).isFalse();
            assertThat(FileStatus.END_OF_FILE.isEndOfFile()).isTrue();

            assertThat(FileStatus.fromCode(FileStatus.RECORD_NOT_FOUND.getCode()))
                    .contains(FileStatus.RECORD_NOT_FOUND);
            assertThat(FileStatus.RECORD_NOT_FOUND.isSuccess()).isFalse();
            assertThat(FileStatus.RECORD_NOT_FOUND.isEndOfFile()).isFalse();
        }

        @Test
        @DisplayName("the abend boundary refuses both non-error coarse outcomes")
        void abendBoundaryRefusesBothNonErrorCoarseOutcomes() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> abendService.abendOnFileStatus(
                            ProbeMode.ACCOUNT.legacyProgramName(),
                            FileStatusException.STATUS_SUCCESS,
                            ERROR_OPERATION,
                            ProbeMode.ACCOUNT.logicalResourceName()));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> abendService.abendOnFileStatus(
                            ProbeMode.ACCOUNT.legacyProgramName(),
                            FileStatusException.STATUS_END_OF_FILE,
                            ERROR_OPERATION,
                            ProbeMode.ACCOUNT.logicalResourceName()));
        }
    }

    @Nested
    @Order(7)
    @DisplayName("status level two: coarse classification to branch")
    class CoarseClassificationToBranch {

        @Test
        @DisplayName("all-clear continues through every row and end of file stops cleanly")
        void allClearContinuesAndEndOfFileStopsCleanly() {
            final FileMaintenanceService.FileReadSummary summary =
                    fileMaintenanceService.readAccountFile();

            assertThat(summary.programName()).isEqualTo(ProbeMode.ACCOUNT.legacyProgramName());
            assertThat(summary.resourceName()).isEqualTo(ProbeMode.ACCOUNT.logicalResourceName());
            assertThat(summary.recordsRead()).isEqualTo(SEEDED_ROWS);
            assertThat(summary.terminalFileStatus())
                    .isEqualTo(FileStatusException.STATUS_END_OF_FILE);
            assertThat(summary.endedAtEndOfFile()).isTrue();
            assertThat(messages())
                    .noneMatch(message -> message.startsWith("ABENDING PROGRAM"));

            assertThat(executions.values())
                    .allSatisfy(execution ->
                            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED));
        }

        @Test
        @DisplayName("an error displays its raw status, announces the abend and only then raises")
        void errorDisplaysRawStatusAnnouncesAbendAndOnlyThenRaises() {
            final String rawStatus = FileStatus.RECORD_NOT_FOUND.getCode();
            final String program = ProbeMode.ACCOUNT.legacyProgramName();
            final String resource = ProbeMode.ACCOUNT.logicalResourceName();
            final int eventsBefore = logRecorder.list.size();

            fileMaintenanceService.displayIoStatus(rawStatus, ERROR_OPERATION, resource);
            final AbendException failure = catchThrowableOfType(
                    AbendException.class,
                    () -> abendService.abendOnFileStatus(
                            program, rawStatus, ERROR_OPERATION, resource));

            final List<ILoggingEvent> emitted =
                    List.copyOf(logRecorder.list.subList(eventsBefore, logRecorder.list.size()));
            assertThat(emitted).hasSize(2);
            assertThat(emitted.get(0).getLevel()).isEqualTo(Level.ERROR);
            assertThat(emitted.get(0).getFormattedMessage())
                    .contains(FileStatusException.DISPLAY_PREFIX)
                    .contains("fileStatus=" + rawStatus)
                    .contains(ERROR_OPERATION)
                    .contains(resource);
            assertThat(emitted.get(1).getLevel()).isEqualTo(Level.ERROR);
            assertThat(emitted.get(1).getFormattedMessage())
                    .startsWith("ABENDING PROGRAM")
                    .contains("fileStatus=" + rawStatus)
                    .contains(program)
                    .contains(ERROR_OPERATION)
                    .contains(resource);

            assertThat(failure).isNotNull();
            assertThat(failure.code()).isEqualTo(AbendException.BATCH_ABEND_CODE);
            assertThat(failure.getCause())
                    .isInstanceOf(FileStatusException.class)
                    .extracting(cause -> ((FileStatusException) cause).code())
                    .isEqualTo(rawStatus);
        }
    }

    @Nested
    @Order(8)
    @DisplayName("four-part abend context")
    class FourPartAbendContext {

        @Test
        @DisplayName("the batch exception renders code, culprit, reason and message at 4-8-50-72")
        void batchExceptionRendersTheFourPublishedContextFields() {
            final String rawStatus = FileStatus.RECORD_NOT_FOUND.getCode();
            final String program = ProbeMode.ACCOUNT.legacyProgramName();
            final AbendException failure = catchThrowableOfType(
                    AbendException.class,
                    () -> abendService.abendOnFileStatus(
                            program,
                            rawStatus,
                            ERROR_OPERATION,
                            ProbeMode.ACCOUNT.logicalResourceName()));

            assertThat(failure).isNotNull();
            final String context = failure.toFixedWidthContext();
            final int culpritOffset = AbendException.CODE_LENGTH;
            final int reasonOffset = culpritOffset + AbendException.CULPRIT_LENGTH;
            final int messageOffset = reasonOffset + AbendException.REASON_LENGTH;

            assertThat(AbendException.CODE_LENGTH).isEqualTo(4);
            assertThat(AbendException.CULPRIT_LENGTH).isEqualTo(8);
            assertThat(AbendException.REASON_LENGTH).isEqualTo(50);
            assertThat(AbendException.MESSAGE_LENGTH).isEqualTo(72);
            assertThat(AbendException.CONTEXT_LENGTH).isEqualTo(134);
            assertThat(context.getBytes(StandardCharsets.US_ASCII).length)
                    .isEqualTo(AbendException.CONTEXT_LENGTH);

            assertThat(context.substring(0, culpritOffset))
                    .startsWith(AbendException.BATCH_ABEND_CODE)
                    .hasSize(AbendException.CODE_LENGTH);
            assertThat(context.substring(culpritOffset, reasonOffset))
                    .isEqualTo(program)
                    .hasSize(AbendException.CULPRIT_LENGTH);
            assertThat(context.substring(reasonOffset, messageOffset))
                    .startsWith("FILE STATUS " + rawStatus)
                    .hasSize(AbendException.REASON_LENGTH);
            assertThat(context.substring(messageOffset))
                    .startsWith(AbendException.DEFAULT_MESSAGE)
                    .hasSize(AbendException.MESSAGE_LENGTH);
        }
    }

    @Nested
    @Order(9)
    @DisplayName("business-key primary keys")
    class BusinessKeyPrimaryKeys {

        @Test
        @DisplayName("the four entity tables use their record keys with no surrogate generator")
        void fourEntityTablesUseTheirRecordKeysWithNoSurrogateGenerator() throws Exception {
            assertThat(applicationTableNames())
                    .contains("account", "card", "customer", "card_cross_reference")
                    .noneMatch(name -> name.startsWith("batch_"))
                    .doesNotContain("flyway_schema_history");

            assertThat(primaryKeyColumns()).containsExactly(
                    new PrimaryKeyColumn("account", "acct_id"),
                    new PrimaryKeyColumn("card", "card_num"),
                    new PrimaryKeyColumn("card_cross_reference", "xref_card_num"),
                    new PrimaryKeyColumn("customer", "cust_id"));
            assertThat(generatedBusinessColumns()).isEmpty();
            assertThat(applicationSequences()).isEmpty();
        }
    }

    @Nested
    @Order(10)
    @DisplayName("observability without an invented performance target")
    class ObservabilityWithoutInventedPerformanceTarget {

        @Test
        @DisplayName("each completed mode publishes a tagged timer and record counter")
        void eachCompletedModePublishesATaggedTimerAndRecordCounter() {
            for (final ProbeMode mode : ProbeMode.values()) {
                final ModeContract contract = contractFor(mode);
                final Timer timer = Objects.requireNonNull(meterRegistry.find(STEP_TIMER)
                        .tag("mode", mode.parameterValue())
                        .tag("program", contract.programName())
                        .tag("resource", contract.resourceName())
                        .tag("outcome", "COMPLETED")
                        .timer(), "completed probe timer");
                final Counter counter = Objects.requireNonNull(meterRegistry.find(RECORD_COUNTER)
                        .tag("mode", mode.parameterValue())
                        .tag("program", contract.programName())
                        .tag("resource", contract.resourceName())
                        .counter(), "probe record counter");

                assertThat(timer.getId().getName()).isEqualTo(STEP_TIMER);
                assertThat(timer.getId().getTag("mode")).isEqualTo(mode.parameterValue());
                assertThat(timer.getId().getTag("program")).isEqualTo(contract.programName());
                assertThat(timer.getId().getTag("resource")).isEqualTo(contract.resourceName());
                assertThat(timer.getId().getTag("outcome")).isEqualTo("COMPLETED");
                assertThat(counter.getId().getName()).isEqualTo(RECORD_COUNTER);
                assertThat(counter.getId().getTag("mode")).isEqualTo(mode.parameterValue());
                assertThat(counter.getId().getTag("program")).isEqualTo(contract.programName());
                assertThat(counter.getId().getTag("resource")).isEqualTo(contract.resourceName());
            }

            assertThat(diagnosticStreams.values())
                    .allSatisfy(stream -> assertThat(stream).isNotEmpty());
        }
    }
}