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

import com.carddemo.batch.step.AdvisoryGenerationPublicationLock;
import com.carddemo.batch.step.StagedGenerationStore;
import com.carddemo.batch.step.TransactionValidationProcessor;
import com.carddemo.config.AwsConfig;
import com.carddemo.config.AwsProperties;
import com.carddemo.config.BatchConfig;
import com.carddemo.config.BatchConfig.ConditionCodeGate;
import com.carddemo.domain.Transaction;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.TransactionPostingService;
import com.carddemo.support.AbstractPostgresAndLocalStackIT;
import com.carddemo.support.IsolatedStagingRoot;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.flow.FlowExecutionStatus;
import org.springframework.batch.core.job.flow.FlowJob;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.step.StepLocator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.actuate.autoconfigure.tracing.prometheus.PrometheusExemplarsAutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;

/**
 * Integration specification for {@link BackupTransactionJobConfig}, driven against a real
 * PostgreSQL 16 server carrying the module's own migrations and a real object store, because this
 * job is the only one in the module that reads the master through the repository and stages its
 * output into a bucket.
 *
 * <h2>Why both a database and an object store, and no stand-in for either</h2>
 *
 * <p>The archive is an external artifact whose bytes the next job in the cycle reads back, so the
 * acceptance criterion is that the bytes come out <em>of a store</em>. Asserting against the
 * argument a mocked client received proves the caller intended to write something; it does not prove
 * the service accepted it or preserved it. This class therefore extends the composed base that owns
 * both containers and declares neither of them itself: the two servers are static, shared and
 * started once for the whole integration phase, and a subclass that declared its own would multiply
 * them and let one specification observe or destroy another's state.
 *
 * <p>The unit specification beside this one drives both steps against mocked collaborators, which is
 * where the offset-level and abend-level properties belong. What only a real execution can reach is
 * what this class asserts: registration under the published name, the framework actually routing the
 * flow, the archive arriving in a bucket as the exact bytes an independent oracle predicts, the
 * master genuinely emptied through the repository, the schema untouched, and a second execution
 * neither failing nor replacing the first execution's archive.
 *
 * <h2>The measured legacy job stream: three steps, one gate, one absorbed half</h2>
 *
 * <ol>
 *   <li><strong>{@code STEP05R}</strong> at {@code app/jcl/TRANBKP.jcl:23}, ungated, reaching the
 *       cataloged unload wrapper {@code app/proc/REPROC.prc} whose one executable control statement
 *       lives in {@code app/ctl/REPROCT.ctl}. Its output is a new generation of the transaction
 *       backup generation base, fixed-length blocked at 350 bytes per record. Here it is one
 *       ordinary read-and-write step: no shell-out, no process spawn, no external tool.</li>
 *   <li><strong>{@code STEP05}</strong> at {@code app/jcl/TRANBKP.jcl:37}, ungated, deleting the
 *       cluster and then its alternate index and resetting its own condition code after each of the
 *       two deletions. Here it is the clear, and the purpose of those resets is why the clear must be
 *       idempotent.</li>
 *   <li><strong>{@code STEP10}</strong> at {@code app/jcl/TRANBKP.jcl:51}, gated, re-defining the
 *       cluster with its data and index components. <strong>The provisioning half is absorbed</strong>
 *       by the four flat migrations, so the gate is what carries across and it is applied to the
 *       clear - re-provisioning is what made the master usable again. The re-definition corroborates
 *       the physical contract independently of the copybook: the key is 16 characters long at
 *       offset 0 and the record size is a fixed 350 bytes.</li>
 * </ol>
 *
 * <p>Three legacy steps therefore become the two steps the configuration declares, and this
 * specification asserts the two it actually declares rather than a third that provisioning would
 * have needed.
 *
 * <h2>&#9733; The gate, its divergent literal, and why one ceiling stands here</h2>
 *
 * <p>Condition codes appear on four steps in the whole estate. Three are in the statement job and
 * demand a prior code of exactly zero. <strong>The literal on this member is spelled differently</strong>
 * - read on its own it bypasses its step only when a prior code exceeds four, so it would admit a
 * warning - and that measured divergence is recorded here as metadata about the estate. It is not
 * expressed as behaviour: the migration plan is the frozen contract for how all four gates translate
 * and it defines all four as the strict form, {@code docs/decision-log.md} entry DL-145 records that
 * a two-ceiling revision was implemented once and withdrawn for exactly that reason, and
 * {@link ConditionCodeGate} consequently carries one constant that no second ceiling may be added
 * beside. A suite that asserted the tolerance would not expose the departure from the plan, it would
 * protect it, which is the one failure mode a migration cannot recover from on its own.
 *
 * <p>So the ceiling is asserted where it is observable, across all three outcomes a prior step can
 * produce: a prior step that completed cleanly permits the clear and the clear runs; a prior step
 * that <em>completed</em> while stating a code of its own - the posting tier's rejects-present code
 * of four - is refused by the ceiling; and a prior step that did not complete at all contributes the
 * error code and is refused too. The refusal <strong>ends</strong> the flow rather than failing it,
 * which is what keeps a bypassed clear distinguishable from a failed one, exactly as the legacy job
 * stream reported a bypassed step and still completed.
 *
 * <p><strong>A gate reads only the steps of the execution it sits inside.</strong> That is why the
 * posting job's own rejects-present code - the only return code that tier assigns, and the code it
 * carries whenever it refused at least one record - cannot reach this job's gate whatever the ceiling
 * is, and why a posting run that produced rejects still lets this job's gated transition proceed.
 * This specification demonstrates that with a real posting execution recorded in the shared job
 * repository.
 *
 * <h2>&#9733; The pipeline relationship, so the clear is not read as a defect</h2>
 *
 * <p>The reference seed inserts no transaction at all, so the posting job is what populates the
 * master and it runs as <strong>setup</strong> for the cycle rather than as part of this job. This job
 * then archives the master and <strong>empties it</strong>, and the combine job later
 * <strong>reloads</strong> it by merging this archived generation with the synthesized-interest
 * generation. The clear is deliberate and complementary, not data loss: without it the master would
 * accumulate and the combine job's load would produce duplicates. The two jobs remain independently
 * launchable, because the estate has no master orchestrator and its pipeline order is an operational
 * convention.
 *
 * <p>An emptied master is also exactly the state the reference seed leaves behind, which is why this
 * specification restores only the rows it inserted itself and never re-seeds a table this job cleared.
 *
 * <h2>The archive destination</h2>
 *
 * <p>The legacy output is a new generation of a generation base declared with a limit of five and
 * scratch-on-roll-off; a separate member re-declares the report base of that same set with a limit of
 * ten and no scratch keyword, and the module-wide resolution of that conflict is <strong>ten</strong>.
 * Retention here is object <strong>versioning</strong> on the pre-provisioned bucket rather than a
 * lifecycle policy, so nothing configures an expiration, a transition or a storage class, and this
 * specification counts no retained depth.
 *
 * <p>The bucket and the region are read from the injected settings bean bound under
 * {@code carddemo.aws} and are never written into the job, and <strong>no resource is created from
 * application code</strong>: the bucket is provisioned by the emulator bootstrap script for the
 * development stack and by the shared base class for a test run, and the job writes an object into a
 * bucket that already exists.
 *
 * <p>The object name is the generation base and the framework's own execution identifier, which is
 * the faithful reading of the relative generation the job stream asked for and is what makes each
 * execution's archive distinct rather than merely usually distinct. The 26-character batch timestamp
 * form the step template supplies - the year, month and day hyphen-separated, a hyphen before the
 * hour, dots between the hour, minute, second and hundredths, and a fixed four-digit tail - is what
 * that template stamps on a step's own start and completion announcements; it is deliberately not
 * part of the object name, and the online form with its colons and its six-digit fraction appears
 * nowhere at all. Both readings are asserted below: the exact name is predicted independently, and it
 * is checked to carry none of the punctuation the online form would have introduced.
 *
 * <h2>An improvement, labelled as one</h2>
 *
 * <p>Every application file in the legacy region was defined with uncommitted read integrity, no
 * recovery and no journalling, its correctness resting on a locking update model plus each program's
 * own before-and-after image comparison. The read-committed isolation of this database, combined with
 * optimistic version checking on the entities that carry it, is therefore <strong>strictly stronger
 * than that baseline</strong>. It is stated so that a reviewer does not mistake the stronger isolation
 * for a behavioural regression. The version attribute exists on the account and the card entities
 * only, and the transaction master carries none - which is faithful, because the estate's archive and
 * clear read and remove whole records rather than updating one in place.
 *
 * <p>A privacy gap is recorded rather than closed: the card primary account number and the card
 * verification code carry no field-level encryption or masking anywhere in the legacy design and no
 * requirement introduces one, so this specification renders neither in a diagnostic and does not
 * invent a protection the migration was not asked for.
 *
 * <h2>Why nothing here touches the submission queue</h2>
 *
 * <p>This job publishes no message: the estate's single online-to-batch bridge belongs to the report
 * submission path, and the ordered card sequence of that bridge is the end-to-end online
 * specification's contract, not this one's. Nothing here drains, purges or receives from the shared
 * queue either, and that is deliberate on two counts: the drain helpers belong to the object-store
 * base's own surface and are not reachable from this package, and draining a queue this process shares
 * would delete messages a neighbouring specification depends on. The absence of any publication is
 * asserted from the configuration's own text instead, alongside the other postures that are textual
 * by nature.
 *
 * <p>Provenance: the legacy job member, its unload wrapper and control member, the generation-base
 * declarations, the transaction cluster definition, the transaction copybook and the online resource
 * definitions, read at checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream
 * release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. That stamp is a provenance
 * string for the traceability matrix header only - it is not carried by every legacy member, so
 * nothing here asserts it against one - and no legacy source line is reproduced anywhere in this
 * file.
 *
 * @since 1.0.0
 */
@SpringBootTest(classes = BackupTransactionJobConfigIT.JobContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.flyway.enabled=false", "spring.main.banner-mode=off",
                "spring.jpa.hibernate.ddl-auto=none",
                "management.endpoint.health.validate-group-membership=false",
                // The archive is composed in a file before it is published, so the job needs a staging
                // root. It is registered from registerIsolatedStagingDirectory rather than named here:
                // the value is read while the context is built, and the root has to carry this process's
                // own identity, which no compile-time constant can.
                "management.tracing.enabled=false"})
@DisplayName("BackupTransactionJobConfigIT - archives the master into a real store, clears it "
        + "idempotently, and changes no schema")
class BackupTransactionJobConfigIT extends AbstractPostgresAndLocalStackIT {

    /**
     * This specification's label within this process's private staging namespace.
     *
     * <p>It was namespaced to this class, which kept a neighbouring specification out but not another run
     * of this one and not a sibling clone: every one of them resolved the same absolute path beneath the
     * platform temporary directory. It is now one segment beneath a namespace unique to this process. The
     * job removes each published generation from the root, so nothing accumulates there either way. See
     * {@link IsolatedStagingRoot}.
     */
    static final String STAGING_LABEL = "backup-transaction-it";

    /** The key the job reads its staging root from. */
    private static final String STAGING_DIRECTORY_PROPERTY =
            "carddemo.batch.backup-transaction.staging-directory";

    /**
     * Binds the staging directory to a root private to this process, before the context is created.
     *
     * <p>A property callback rather than an entry in the annotation above, because the value cannot be a
     * compile-time constant: it carries the process identifier so that no other run of this specification,
     * and no sibling clone sharing this host, resolves the same absolute path.
     *
     * @param registry the registry the framework supplies
     */
    @DynamicPropertySource
    static void registerIsolatedStagingDirectory(final DynamicPropertyRegistry registry) {
        registry.add(STAGING_DIRECTORY_PROPERTY, () -> IsolatedStagingRoot.pathFor(STAGING_LABEL));
    }

    /**
     * Removes this specification's own staging root once the class is done with it.
     *
     * <p>The job removes each generation it publishes, so this is the belt to that brace rather than the
     * only cleanup - and it removes a tree this process owns rather than sweeping anything shared.
     */
    @AfterAll
    static void discardIsolatedStagingDirectory() {
        IsolatedStagingRoot.discard(IsolatedStagingRoot.forSpecification(STAGING_LABEL));
    }

    /**
     * The bucket every archive of this specification is expected to reach.
     *
     * <p>Stated here as the canonical name the module ships, and compared against the value the
     * injected settings bean resolves. The comparison is the point: the job reads the name from that
     * bean, this constant is what every profile document and the emulator bootstrap declare, and a
     * divergence between the two is the one thing an assertion on either alone cannot catch.
     */
    private static final String CANONICAL_BUCKET = "carddemo-batch-staging";

    /** The region the module ships and the emulator reports, compared the same way and for the same reason. */
    private static final String CANONICAL_REGION = "us-east-1";

    /**
     * The legacy generation base every archive of this job is named under.
     *
     * <p>Stated independently and compared against the published constant, for the same reason the
     * bucket is: the name is what an operator looks the archive up by, so the two spellings of it must
     * be shown to agree rather than assumed to.
     */
    private static final String CANONICAL_ARCHIVE_BASE = "AWS.M2.CARDDEMO.TRANSACT.BKUP";

    /**
     * How a generation number is rendered into an object name: the recognisable generation marker, the
     * number at a ten-digit minimum width, and the version marker.
     *
     * <p>Stated here so the expected name is composed by this specification rather than obtained from
     * the code that composes the real one. Ten digits is wide enough that an execution identifier is
     * never wrapped or truncated into another execution's name.
     */
    private static final String GENERATION_TOKEN_FORMAT = "G%010dV00";

    /**
     * The record width the archive is framed at, in encoded bytes.
     *
     * <p>Declared here rather than read from the production mapper on purpose. This specification
     * verifies what that mapper produced, so taking the width from it would let the expectation agree
     * with a wrong value; an independent statement of the contract is what makes a disagreement
     * visible. The value is the record length the legacy unload's output declares and the record size
     * the cluster re-definition declares, arrived at twice from the source.
     */
    private static final int RECORD_LENGTH = 350;

    /**
     * The distance from the start of one archived record to the start of the next, in encoded bytes.
     *
     * <p>Record-format-blocked data carries no line terminator, so the stride is the record width
     * itself and an archive is an exact multiple of it. Naming it separately is what lets an assertion
     * say "a whole number of records" rather than restate a number.
     */
    private static final int RECORD_STRIDE = RECORD_LENGTH;

    /** Width of the transaction identifier, the 16-character key at offset 0. */
    private static final int ID_WIDTH = 16;

    /** Width of the type code at offset 16. */
    private static final int TYPE_CODE_WIDTH = 2;

    /** Width of the category code at offset 18, an unsigned numeric field. */
    private static final int CATEGORY_CODE_WIDTH = 4;

    /** Width of the source at offset 22. */
    private static final int SOURCE_WIDTH = 10;

    /** Width of the description at offset 32. */
    private static final int DESCRIPTION_WIDTH = 100;

    /**
     * Width of the amount at offset 132: nine integer digits and two decimal places, with the sign
     * overpunched into the final byte rather than occupying one of its own.
     */
    private static final int AMOUNT_WIDTH = 11;

    /** Width of the merchant identifier at offset 143, an unsigned numeric field. */
    private static final int MERCHANT_ID_WIDTH = 9;

    /** Width of the merchant name at offset 152. */
    private static final int MERCHANT_NAME_WIDTH = 50;

    /** Width of the merchant city at offset 202. */
    private static final int MERCHANT_CITY_WIDTH = 50;

    /** Width of the merchant postal code at offset 252. */
    private static final int MERCHANT_ZIP_WIDTH = 10;

    /** Width of the card number at offset 262, the field the ordering specifications address. */
    private static final int CARD_NUMBER_WIDTH = 16;

    /** Width of the origination timestamp at offset 278. */
    private static final int ORIGINAL_TIMESTAMP_WIDTH = 26;

    /** Width of the processing timestamp at offset 304, which the delivered fixture leaves blank. */
    private static final int PROCESSING_TIMESTAMP_WIDTH = 26;

    /** Width of the trailing filler run at offset 330, which the layout maps to no field. */
    private static final int FILLER_WIDTH = 20;

    /**
     * The overpunched final byte of a non-negative zoned-decimal image, indexed by its final digit.
     *
     * <p>Stated independently of the production codec, because this specification checks what that
     * codec produced. Position zero encodes a final digit of zero, position nine a final digit of
     * nine.
     */
    private static final String POSITIVE_OVERPUNCH = "{ABCDEFGHI";

    /** The same table for a negative image, which the credit record below exercises for real. */
    private static final String NEGATIVE_OVERPUNCH = "}JKLMNOPQR";

    /**
     * A card the reference seed already carries.
     *
     * <p>The master constrains every posted transaction to name an existing card, so a fixture that
     * invented a card number would fail on a foreign key rather than on anything asserted here.
     */
    private static final String SEEDED_CARD = "0500024453765740";

    /**
     * The identifier this specification expects to be archived first, being the lowest of its three.
     *
     * <p>The three identifiers occupy a range no other specification reserves, so a row that escaped
     * the restoration below could not be mistaken for another suite's fixture.
     */
    private static final String FIRST_ID = "9880000000000001";

    /** The identifier expected second, seeded out of order so the ascending read has something to do. */
    private static final String SECOND_ID = "9880000000000003";

    /** The identifier expected last. */
    private static final String THIRD_ID = "9880000000000005";

    /** The whole of the range this specification reserves in the shared master. */
    private static final List<String> RESERVED_IDS = List.of(FIRST_ID, SECOND_ID, THIRD_ID);

    /** The timer both steps of this job are recorded under, named independently of the configuration. */
    private static final String STEP_TIMER = "carddemo.batch.job.step";

    /** Tag naming the job a timed step belongs to. */
    private static final String TAG_JOB = "batchJob";

    /** Tag naming the step being timed. */
    private static final String TAG_STEP = "step";

    /** The versions the delivered migration set applies, in the order it applies them. */
    private static final List<String> DELIVERED_MIGRATION_VERSIONS = List.of("1", "2", "3", "4");

    /** The configuration's own source, read for the postures that are textual by nature. */
    private static final Path CONFIGURATION_SOURCE = Path.of("src", "main", "java", "com",
            "carddemo", "batch", "BackupTransactionJobConfig.java");

    /** The framework's own identifying parameter, contributed by the shared incrementer. */
    private static final String RUN_IDENTIFIER_PARAMETER = "run.id";

    /**
     * The parameter the recorded posting run is identified by.
     *
     * <p>Namespaced to this specification, so the recorded run cannot be mistaken for a real one and
     * cannot collide with the parameter names the posting job's own validators own.
     */
    private static final String POSTING_PROBE_PARAMETER = "carddemo.it.backup-transaction.prior-run";

    /**
     * The parameter the execution that runs its two steps around a concurrent commit is identified by.
     *
     * <p>Namespaced to this specification for the same reason as the posting probe: it must not collide
     * with a launched execution or with a validator's own parameter names.
     */
    private static final String LATE_COMMIT_PROBE_PARAMETER =
            "carddemo.it.backup-transaction.late-commit";

    /**
     * Identifier of the record committed between the archive and the clear.
     *
     * <p>Above the fixture's own identifiers so the ordering of the archive is unaffected, and outside
     * the reserved set so the shared clean-up does not remove it before the assertions run.
     */
    private static final String LATE_ARRIVAL_ID = "0000000000009999";

    /**
     * The identifier the unpersisted execution the ceiling is asked about carries.
     *
     * <p>A gate's verdict is a function of the step outcomes an execution has accumulated, so that
     * execution needs an identifier and nothing else. It is never recorded, so it cannot collide with a
     * launched execution.
     */
    private static final long PROBE_EXECUTION_ID = 1L;

    /**
     * The origination timestamp every fixture record carries, in the twenty-six character batch form.
     *
     * <p>The pinned instant's own date and time, so the fixture agrees with the clock the context reads
     * rather than drifting away from it one run at a time. Its shape is the batch rendering: the year,
     * month and day hyphen-separated, a hyphen before the hour, and dots between the time components -
     * never the online form's space and colons.
     */
    private static final String FIXTURE_ORIGINAL_TIMESTAMP = "2022-06-10-19.27.53.000000";

    /**
     * A blank processing timestamp, which is what the delivered fixture carries for an unposted record
     * and what the archive must reproduce in full rather than trim.
     */
    private static final String BLANK_TIMESTAMP = " ".repeat(PROCESSING_TIMESTAMP_WIDTH);

    /** The surface a caller launches this job through, populated by the framework. */
    @Autowired
    private JobRegistry jobRegistry;

    /** Launches this job by name and applies the shared incrementer, which is how it ever starts. */
    @Autowired
    private JobOperator jobOperator;

    /** Reads a launched execution back, including its step executions. */
    @Autowired
    private JobExplorer jobExplorer;

    /** Used to record one prior posting execution, and to remove it again. */
    @Autowired
    private JobRepository jobRepository;

    /** The job under test, injected by the name the configuration publishes. */
    @Autowired
    private Job backupTransactionJob;

    /** The master, seeded before a run and read back after it. */
    @Autowired
    private TransactionRepository transactionRepository;

    /** The already-registered settings bean the destination is resolved through. */
    @Autowired
    private AwsProperties awsProperties;

    /** The registry both steps are timed on. */
    @Autowired
    private MeterRegistry meterRegistry;

    /** Used to observe that the context carries nothing that launches a job when it starts. */
    @Autowired
    private ApplicationContext applicationContext;

    /** Used to observe the settings that keep the context inert. */
    @Autowired
    private Environment environment;

    /**
     * Whether this test has launched the job, so the archive generations it published are removed.
     *
     * <p>A recorded list of predicted keys is no longer possible: the store allocates the generation
     * number when it publishes (DL-210). The clean-up therefore removes every generation beneath the
     * canonical base, which is safe because the reference seed publishes none.
     */
    private boolean hasPublished;

    // ----------------------------------------------------------------------------------------
    // Registration, shape and inertness
    // ----------------------------------------------------------------------------------------

    @Test
    @DisplayName("is registered under its published name, holds the two steps the configuration "
            + "declares, and the context carries nothing that launches it at start-up")
    void isRegisteredHoldsItsTwoDeclaredStepsAndFiresNothingAtStartUp() {
        assertThat(this.jobRegistry.getJobNames())
                .as("the job is launched by name through the registry, so it must be registered"
                        + " under that name")
                .contains(BackupTransactionJobConfig.JOB_NAME);

        assertThat(this.backupTransactionJob.getName())
                .isEqualTo(BackupTransactionJobConfig.JOB_NAME);
        assertThat(this.backupTransactionJob)
                .as("a gated job is a flow job: the condition-code decision is a transition in a"
                        + " flow, and a plain sequence could not express one")
                .isInstanceOf(FlowJob.class);

        final FlowJob flowJob = (FlowJob) this.backupTransactionJob;
        assertThat(flowJob.getStepNames())
                .as("three legacy steps become two: the unload and the clear. The provisioning half"
                        + " of the third is absorbed by the migrations, so no step of this job"
                        + " provisions anything")
                .containsExactlyInAnyOrder(BackupTransactionJobConfig.ARCHIVE_STEP_NAME,
                        BackupTransactionJobConfig.RESET_STEP_NAME);
        assertThat(BackupTransactionJobConfig.ARCHIVE_STEP_NAME)
                .as("the two names are distinct, so either step can be located and run on its own")
                .isNotEqualTo(BackupTransactionJobConfig.RESET_STEP_NAME);
        assertThat(flowJob.getStep(BackupTransactionJobConfig.ARCHIVE_STEP_NAME)).isNotNull();
        assertThat(flowJob.getStep(BackupTransactionJobConfig.RESET_STEP_NAME)).isNotNull();

        assertThat(this.environment.getProperty("spring.batch.job.enabled"))
                .as("a legacy job was submitted deliberately; bringing an application up never"
                        + " triggered one")
                .isEqualTo("false");
        assertThat(this.environment.getProperty("spring.batch.job.name"))
                .as("naming a job to run at start-up would launch one on every boot")
                .isNull();
        assertThat(this.applicationContext.getBeansOfType(ApplicationRunner.class))
                .as("no runner may launch a job when the context starts")
                .isEmpty();
        assertThat(this.applicationContext.getBeansOfType(CommandLineRunner.class))
                .as("nor may a command-line runner")
                .isEmpty();
    }

    // ----------------------------------------------------------------------------------------
    // The archive and the clear, end to end
    // ----------------------------------------------------------------------------------------

    @Test
    @DisplayName("archives every record of the master as its exact fixed-width image, into the "
            + "configured bucket, and then empties the master")
    void archivesEveryRecordAsAnExactImageThenEmptiesTheMaster() throws Exception {
        final List<ArchiveRecord> master = master();
        seed(master);
        assertThat(this.transactionRepository.count())
                .as("the fixture is in place before the unload reads it")
                .isEqualTo(master.size());

        final JobExecution execution = runTheJob();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getStepExecutions()).extracting(StepExecution::getStepName)
                .as("both steps ran, in the legacy order: the unload is ungated and the clear"
                        + " follows it behind the gate")
                .containsExactly(BackupTransactionJobConfig.ARCHIVE_STEP_NAME,
                        BackupTransactionJobConfig.RESET_STEP_NAME);

        final String bucket = this.awsProperties.s3().batchStagingBucket();
        final String key = newestArchiveKey(bucket);
        assertGenerationKeyShape(key);
        assertThat(stagedObjectExists(bucket, key))
                .as("the archive reached the store under the generation the store allocated for it:"
                        + " %s", key)
                .isTrue();

        final byte[] archived = stagedObjectBytes(bucket, key);
        final byte[] expected = expectedArchive(master);
        assertThat(archived.length)
                .as("record-format-blocked data carries no terminator, so the artifact is an exact"
                        + " multiple of the %d-byte stride", RECORD_STRIDE)
                .isEqualTo(master.size() * RECORD_STRIDE);
        assertThat(archived.length % RECORD_STRIDE)
                .as("an artifact that is not a whole number of records cannot be read back")
                .isZero();
        assertThat(archived)
                .as("byte for byte the images an independent oracle renders, in ascending"
                        + " business-key order - not a semantic comparison, so a trimmed space run"
                        + " or an overpunched sign byte in the wrong place fails here")
                .isEqualTo(expected);

        for (int ordinal = 0; ordinal < master.size(); ordinal++) {
            final byte[] record = Arrays.copyOfRange(archived, ordinal * RECORD_STRIDE,
                    (ordinal + 1) * RECORD_STRIDE);
            assertThat(record)
                    .as("record %d occupies exactly its declared width in encoded bytes", ordinal)
                    .hasSize(RECORD_LENGTH);
            assertThat(new String(record, StandardCharsets.US_ASCII))
                    .as("record %d ends in the blank processing timestamp and the unmapped filler"
                            + " run, both carried in full: nothing on this path trims a"
                            + " contractual space", ordinal)
                    .endsWith(" ".repeat(PROCESSING_TIMESTAMP_WIDTH + FILLER_WIDTH));
        }

        assertThat(this.transactionRepository.count())
                .as("the clear emptied the master once it had been archived, which is the half of"
                        + " the cycle the combine job reloads from this very archive")
                .isZero();
        for (final String reserved : RESERVED_IDS) {
            assertThat(this.transactionRepository.findById(reserved))
                    .as("%s was removed through the repository, not by a utility", reserved)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("a transaction committed after the archive was taken survives the clear, because the "
            + "clear removes the archived row set and nothing else")
    void aTransactionCommittedAfterTheArchiveSurvivesTheClear() throws Exception {
        final List<ArchiveRecord> master = master();
        seed(master);

        final JobExecution execution =
                this.jobRepository.createJobExecution(BackupTransactionJobConfig.JOB_NAME,
                        new JobParametersBuilder()
                                .addString(LATE_COMMIT_PROBE_PARAMETER, PINNED_BUSINESS_DATE.toString())
                                .toJobParameters());
        try {
            runStepOf(execution, BackupTransactionJobConfig.ARCHIVE_STEP_NAME);
            this.hasPublished = true;

            // The window the finding named: committed after the archive read its last record and
            // before the gated clear runs. The legacy region had its files closed for the backup
            // window and could not reach this state; a relational master can, on every cycle.
            this.transactionRepository.save(lateArrival().entity());
            assertThat(this.transactionRepository.count())
                    .as("the late arrival is committed and visible before the clear runs")
                    .isEqualTo(master.size() + 1);

            runStepOf(execution, BackupTransactionJobConfig.RESET_STEP_NAME);

            assertThat(this.transactionRepository.findById(LATE_ARRIVAL_ID))
                    .as("THE UNARCHIVED ROW MUST SURVIVE: no copy of it exists in any generation, so"
                            + " deleting it would lose it outright")
                    .isPresent();
            for (final ArchiveRecord archived : master) {
                assertThat(this.transactionRepository.findById(archived.id()))
                        .as("%s was archived, so the clear removed it", archived.id())
                        .isEmpty();
            }
            assertThat(this.transactionRepository.count())
                    .as("exactly the archived set was cleared")
                    .isEqualTo(1L);

            final String bucket = this.awsProperties.s3().batchStagingBucket();
            final byte[] archived = stagedObjectBytes(bucket, newestArchiveKey(bucket));
            assertThat(archived)
                    .as("the generation carries the rows that were archived and not the late arrival")
                    .isEqualTo(expectedArchive(master));
        } finally {
            this.transactionRepository.deleteById(LATE_ARRIVAL_ID);
            forget(execution);
        }
    }

    /**
     * Runs one step of the job as part of a nominated execution, through the framework's own contract.
     *
     * <p>Two steps of <em>one</em> execution are run separately here on purpose: the hand-off from the
     * archive to the clear is what the archived-row-set capture travels through, and a launched job
     * runs them back to back with no window between them for a concurrent commit to land in.
     *
     * @param execution the execution both steps belong to
     * @param stepName  the step to run
     * @throws Exception if the step raised
     */
    private void runStepOf(final JobExecution execution, final String stepName) throws Exception {
        final Step step = ((StepLocator) this.backupTransactionJob).getStep(stepName);
        final StepExecution stepExecution = execution.createStepExecution(stepName);
        this.jobRepository.add(stepExecution);

        step.execute(stepExecution);

        assertThat(stepExecution.getFailureExceptions())
                .as("step %s must complete for the hand-off under test to be exercised", stepName)
                .isEmpty();
        assertThat(stepExecution.getStatus())
                .as("step %s must complete", stepName)
                .isEqualTo(BatchStatus.COMPLETED);
    }

    /**
     * The record that lands between the two steps: a row no generation carries.
     *
     * @return the late arrival, keyed outside the fixture's own identifier range
     */
    private static ArchiveRecord lateArrival() {
        return new ArchiveRecord(LATE_ARRIVAL_ID, "01", "0005", "POS TERM",
                "Committed after the archive was taken", new BigDecimal("12.34"), "000000123",
                "Merchant Name", "Merchant City", "72112", SEEDED_CARD, FIXTURE_ORIGINAL_TIMESTAMP,
                BLANK_TIMESTAMP);
    }

    @Test
    @DisplayName("clears an already-empty master without failing and succeeds when run twice in "
            + "succession, leaving the first execution's archive untouched")
    void clearsAnAlreadyEmptyMasterAndSucceedsWhenRunTwiceInSuccession() throws Exception {
        seed(master());

        final JobExecution first = runTheJob();
        assertThat(first.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(this.transactionRepository.count()).isZero();

        final String bucket = this.awsProperties.s3().batchStagingBucket();
        final String firstKey = newestArchiveKey(bucket);
        assertGenerationKeyShape(firstKey);
        final byte[] firstArchive = stagedObjectBytes(bucket, firstKey);
        assertThat(firstArchive).isEqualTo(expectedArchive(master()));

        // The master is empty now, so this second run is the "nothing to delete" case the legacy
        // step's two condition-code resets existed for: a first run, or a re-run after a partial
        // failure. It must not fail.
        final JobExecution second = runTheJob();

        assertThat(second.getStatus())
                .as("clearing a master that holds nothing removes nothing and reports nothing"
                        + " wrong; an implementation that raised here would fail the second run of"
                        + " any cycle")
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(second.getStepExecutions()).extracting(StepExecution::getStepName)
                .as("both steps ran again, so the clear is repeatable rather than merely tolerated")
                .containsExactly(BackupTransactionJobConfig.ARCHIVE_STEP_NAME,
                        BackupTransactionJobConfig.RESET_STEP_NAME);
        assertThat(this.transactionRepository.count()).isZero();

        final String secondKey = newestArchiveKey(bucket);
        assertGenerationKeyShape(secondKey);
        assertThat(secondKey)
                .as("the generation advances against what the base already holds, so two executions"
                        + " never share a name however close together they run - and a re-created"
                        + " metadata store cannot make the second run reuse the first's name (DL-210)")
                .isNotEqualTo(firstKey)
                .isGreaterThan(firstKey);
        assertThat(stagedObjectBytes(bucket, secondKey))
                .as("an empty master still produces a generation, and an empty generation is a"
                        + " whole number of records: none")
                .isEmpty();
        assertThat(stagedObjectBytes(bucket, firstKey))
                .as("the second execution added an archive rather than replacing the first: an"
                        + " archive that quietly replaced its predecessor would be a lost archive")
                .isEqualTo(firstArchive);

        assertThat(bucketVersioning(bucket))
                .as("retention is object versioning on the pre-provisioned bucket, which is what"
                        + " carries the retained-generation semantics of the legacy output data"
                        + " sets; an unversioned bucket would keep only the newest object and the"
                        + " retention contract would be gone with nothing failing")
                .isEqualTo(BucketVersioningStatus.ENABLED);
    }

    // ----------------------------------------------------------------------------------------
    // The condition-code gate
    // ----------------------------------------------------------------------------------------

    @Test
    @DisplayName("the one ceiling this job selects routes all three prior outcomes, and a refusal "
            + "ends the flow instead of failing it")
    void theCeilingRoutesAllThreePriorOutcomesAndARefusalEndsTheFlow() throws Exception {
        assertThat(ConditionCodeGate.values())
                .as("the estate's four step gates translate as one ceiling, and no second ceiling"
                        + " may be introduced beside it")
                .containsExactly(ConditionCodeGate.ALL_PRIOR_STEPS_ZERO);
        assertThat(ConditionCodeGate.ALL_PRIOR_STEPS_ZERO.highestToleratedReturnCode()).isZero();

        // Outcome one: the prior step completed cleanly. The clear runs.
        assertThat(verdictAfter(BatchStatus.COMPLETED, ExitStatus.COMPLETED))
                .as("a step that reported nothing reported nothing wrong, so the gate permits and"
                        + " the clear follows")
                .isEqualTo(ConditionCodeGate.PERMITTED);

        // Outcome two: the prior step COMPLETED while stating a code of its own - the posting
        // tier's rejects-present code of four. This is where the literal on this member diverges
        // from the estate's other three: read on its own it would admit exactly this. The plan
        // freezes all four gates at zero, so the ceiling refuses it, and the divergence stays
        // recorded metadata rather than becoming behaviour.
        assertThat(verdictAfter(BatchStatus.COMPLETED,
                new ExitStatus(TransactionValidationProcessor.EXIT_CODE_REJECTS_PRESENT)))
                .as("a step that completed while stating %s is not a step that failed, and the"
                        + " gate is what tells the two apart: the framework's own failure"
                        + " transition would have admitted it",
                        TransactionValidationProcessor.EXIT_CODE_REJECTS_PRESENT)
                .isEqualTo(ConditionCodeGate.REFUSED);

        // Outcome three: the prior step did not complete at all. Above every ceiling.
        assertThat(verdictAfter(BatchStatus.FAILED, ExitStatus.FAILED))
                .as("a step that did not complete contributes the error code whatever its exit"
                        + " code happens to say")
                .isEqualTo(ConditionCodeGate.REFUSED);

        final String source = configurationCode();
        assertThat(countOf(source, ".next(ConditionCodeGate."))
                .as("exactly one gated transition exists on this job, because exactly one legacy"
                        + " step of the three carried a condition code")
                .isEqualTo(1);
        assertThat(source)
                .as("the refusing verdict routes to the end of the flow rather than to a failure,"
                        + " so a bypassed clear is not reported as a failed one - the legacy stream"
                        + " reported a bypassed step and still completed")
                .contains(".on(ConditionCodeGate.REFUSED).end()")
                .contains(".on(ConditionCodeGate.PERMITTED).to(")
                .doesNotContain(".on(\"FAILED\")");
    }

    @Test
    @DisplayName("a posting run that rejected records still permits this job's gated transition, "
            + "because a gate reads only the steps of the execution it sits inside")
    void aPostingRunThatRejectedRecordsStillPermitsThisJobsGatedTransition() throws Exception {
        assertThat(TransactionPostingService.RETURN_CODE_REJECTS_PRESENT)
                .as("the posting tier's only return-code assignment, carried whenever it refused at"
                        + " least one record")
                .isEqualTo(4);
        assertThat(TransactionValidationProcessor.EXIT_CODE_REJECTS_PRESENT)
                .as("the textual form of that same code, derived from it rather than restated")
                .isEqualTo(Integer.toString(TransactionPostingService.RETURN_CODE_REJECTS_PRESENT));

        final JobExecution posting = recordPostingRunWithRejects();
        try {
            assertThat(posting.getStepExecutions()).hasSize(1);
            assertThat(posting.getStepExecutions().iterator().next().getExitStatus().getExitCode())
                    .isEqualTo(TransactionValidationProcessor.EXIT_CODE_REJECTS_PRESENT);

            seed(master());
            final JobExecution execution = runTheJob();

            assertThat(execution.getStatus())
                    .as("the recorded posting run states a code of four in the shared repository,"
                            + " and this job still completes")
                    .isEqualTo(BatchStatus.COMPLETED);
            assertThat(execution.getStepExecutions()).extracting(StepExecution::getStepName)
                    .as("the gated clear ran: a gate compares against the steps of its own"
                            + " execution, so another job's completion code cannot reach it"
                            + " whatever the ceiling is")
                    .containsExactly(BackupTransactionJobConfig.ARCHIVE_STEP_NAME,
                            BackupTransactionJobConfig.RESET_STEP_NAME);
            assertThat(ConditionCodeGate.ALL_PRIOR_STEPS_ZERO.decide(execution, null).getName())
                    .as("evaluated against this execution the verdict permits, while the same"
                            + " ceiling applied to the posting execution's own stated four would"
                            + " refuse - which is the whole difference between reading one"
                            + " execution and reading the repository")
                    .isEqualTo(ConditionCodeGate.PERMITTED);
            assertThat(ConditionCodeGate.ALL_PRIOR_STEPS_ZERO.decide(posting, null).getName())
                    .isEqualTo(ConditionCodeGate.REFUSED);
        } finally {
            forget(posting);
        }
    }

    // ----------------------------------------------------------------------------------------
    // What the third legacy step's provisioning half became
    // ----------------------------------------------------------------------------------------

    @Test
    @DisplayName("absorbs the provisioning half of the third legacy step: the schema, its eleven "
            + "application tables and its four migrations are exactly as they were")
    void absorbsTheProvisioningHalfOfTheThirdLegacyStep() throws Exception {
        final List<String> tablesBefore = applicationTableNames();

        seed(master());
        final JobExecution execution = runTheJob();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(applicationTableNames())
                .as("re-defining the cluster with its data and index components is schema"
                        + " provisioning, and schema evolution belongs exclusively to the"
                        + " migrations: this job creates, drops and alters nothing")
                .containsExactlyElementsOf(tablesBefore)
                .containsExactlyInAnyOrderElementsOf(APPLICATION_TABLES);
        assertThat(appliedMigrationVersions())
                .as("the absorbed half adds no migration; the framework's own metadata tables are"
                        + " created by the framework and are excluded from the count above")
                .containsExactlyElementsOf(DELIVERED_MIGRATION_VERSIONS);
    }

    // ----------------------------------------------------------------------------------------
    // The destination
    // ----------------------------------------------------------------------------------------

    @Test
    @DisplayName("resolves its destination through the injected settings bean and provisions "
            + "nothing, and names the archive without a second timestamp rendering")
    void resolvesItsDestinationThroughTheInjectedSettingsAndProvisionsNothing() throws Exception {
        assertThat(this.awsProperties.s3().batchStagingBucket())
                .as("the bucket is read from the settings bean bound under %s and is written into"
                        + " no job", AwsProperties.PREFIX)
                .isEqualTo(CANONICAL_BUCKET);
        assertThat(this.awsProperties.region())
                .as("so is the region, and both agree with what every profile document declares")
                .isEqualTo(CANONICAL_REGION);
        assertThat(BackupTransactionJobConfig.ARCHIVE_DATASET_BASE)
                .as("the object name begins at the legacy generation base, so an operator finds the"
                        + " archive under the name the job stream wrote it under")
                .isEqualTo(CANONICAL_ARCHIVE_BASE);
        assertThat(BackupTransactionJobConfig.ARCHIVE_OBJECT_KEY_PREFIX)
                .isEqualTo(CANONICAL_ARCHIVE_BASE + "/");
        assertThat(BackupTransactionJobConfig.ARCHIVE_RECORD_LENGTH)
                .as("the published record width agrees with the width measured from the source")
                .isEqualTo(RECORD_LENGTH);
        assertThat(BackupTransactionJobConfig.ARCHIVE_RECORD_STRIDE).isEqualTo(RECORD_STRIDE);

        seed(master());
        runTheJob();
        final String key = newestArchiveKey(this.awsProperties.s3().batchStagingBucket());

        assertGenerationKeyShape(key);
        assertThat(stagedObjectExists(this.awsProperties.s3().batchStagingBucket(), key)).isTrue();
        assertThat(key)
                .as("the online timestamp form - a space before the hour, colons between the time"
                        + " components - appears nowhere, so no key-safe re-rendering had to be"
                        + " derived from a name and no second timestamp format was invented to"
                        + " obtain one")
                .doesNotContain(" ")
                .doesNotContain(":");
        assertThat(key)
                .as("every character of the name is one an object name admits unchanged - upper-case"
                        + " letters, digits, dots, hyphens and the one separator - which is the"
                        + " positive form of the same property and leaves no room for a second"
                        + " escaping convention to be needed anywhere")
                .matches("[A-Z0-9./-]+");
    }

    @Test
    @DisplayName("carries no date parameter, and launches with none beyond the identifying one the "
            + "shared incrementer contributes")
    void carriesNoDateParameterAndLaunchesWithNoneBeyondTheIdentifyingOne() throws Exception {
        seed(master());

        final JobExecution execution = runTheJob();

        assertThat(execution.getStatus())
                .as("the legacy member declares no parameter string on any of its three steps, so a"
                        + " launch supplies no date and the job asks for none")
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getJobParameters().getParameters().keySet())
                .as("the only parameter present is the identifying one the shared incrementer"
                        + " mints, which is what lets the job be resubmitted exactly as the legacy"
                        + " member could be")
                .containsExactly(RUN_IDENTIFIER_PARAMETER);
    }

    // ----------------------------------------------------------------------------------------
    // Observability, and the postures that are textual by nature
    // ----------------------------------------------------------------------------------------

    @Test
    @DisplayName("records both steps on the shared step timer, under this job's tag and each step's "
            + "own, and states no target of its own")
    void recordsBothStepsOnTheSharedTimer() throws Exception {
        seed(master());

        runTheJob();

        for (final String step : List.of(BackupTransactionJobConfig.ARCHIVE_STEP_NAME,
                BackupTransactionJobConfig.RESET_STEP_NAME)) {
            final Timer timer = this.meterRegistry.find(STEP_TIMER)
                    .tag(TAG_JOB, BackupTransactionJobConfig.JOB_NAME)
                    .tag(TAG_STEP, step)
                    .timer();
            assertThat(timer)
                    .as("%s is measured, so its elapsed time is readable from the metrics endpoint"
                            + " rather than guessed at", step)
                    .isNotNull();
            assertThat(timer.count())
                    .as("%s recorded the execution that just ran", step)
                    .isPositive();
        }
    }

    @Test
    @DisplayName("spawns no process, assembles no statement text, provisions no resource, "
            + "configures no lifecycle rule and publishes no message")
    void spawnsNothingProvisionsNothingAndPublishesNothing() throws IOException {
        final String source = configurationCode();

        assertThat(source)
                .as("the unload is one ordinary read-and-write step: the legacy step reached a copy"
                        + " utility, and the temptation to reach one here is exactly what the"
                        + " translation must not do")
                .doesNotContain("Runtime.getRuntime")
                .doesNotContain("ProcessBuilder")
                .doesNotContain("exec(")
                .doesNotContain("createNativeQuery");
        assertThat(source)
                .as("the clear is a bulk removal through the repository, so no schema statement is"
                        + " assembled anywhere: the provisioning half of the third legacy step is"
                        + " the migrations' work")
                .doesNotContain("CREATE TABLE")
                .doesNotContain("CREATE INDEX")
                .doesNotContain("ALTER TABLE")
                .doesNotContain("DROP TABLE");
        assertThat(source)
                .as("the platform provisions the bucket; this job writes an object into one that"
                        + " already exists and never creates, configures or probes for one")
                .doesNotContain("createBucket")
                .doesNotContain("bucketExists");
        assertThat(source)
                .as("retention is versioning on that bucket, so no lifecycle rule, expiration,"
                        + " transition or storage class is configured and no retained depth is"
                        + " counted here")
                .doesNotContain("Lifecycle")
                .doesNotContain("StorageClass");
        assertThat(source)
                .as("the estate's single online-to-batch bridge belongs to the report submission"
                        + " path; this job publishes nothing, and the ordered card sequence of that"
                        + " bridge is the end-to-end online specification's contract")
                .doesNotContain("Sqs")
                .doesNotContain("JobSubmission");
        assertThat(source)
                .as("execution is strictly sequential: the archive must be complete and ordered"
                        + " before the clear runs, and concurrency here would risk archiving a"
                        + " partly cleared master")
                .doesNotContain("TaskExecutor")
                .doesNotContain("partition")
                .doesNotContain("split(");
    }

    // ----------------------------------------------------------------------------------------
    // Restoring the shared state
    // ----------------------------------------------------------------------------------------

    /**
     * Returns the shared database and the shared store to the state this specification found them in.
     *
     * <p>Runs whatever the outcome, because a half-seeded master and a leftover archive disrupt a
     * neighbouring specification exactly as a fully seeded one would - and the failure would then
     * belong to a test that had already passed. Removing an absent row and an absent object are both
     * no-operations, so a run that failed before it seeded anything restores cleanly too.
     *
     * <p>Only the rows this specification inserted are removed. The reference seed inserts no
     * transaction at all, so an emptied master <em>is</em> the seeded state for that table and nothing
     * has to be re-seeded after the clear.
     */
    @AfterEach
    void restoreTheSharedState() {
        RESERVED_IDS.forEach(this.transactionRepository::deleteById);
        if (this.hasPublished) {
            final String bucket = this.awsProperties.s3().batchStagingBucket();
            // Every version and every delete marker, not every current key. The staging bucket carries
            // object versioning, so the key-by-key ordinary delete this used to perform removed nothing:
            // it added a marker, which hid the archive from an ordinary listing while leaving its bytes -
            // a whole transaction master, card numbers included - fetchable by version identifier in a
            // bucket every later specification shares, one copy per run of this class. The helper reads
            // the base back afterwards and raises if anything remains, so "restored" is proven rather
            // than assumed. See docs/decision-log.md entry DL-287.
            deleteEveryStagedVersionUnder(bucket, CANONICAL_ARCHIVE_BASE + "/");
            this.hasPublished = false;
        }
    }

    // ----------------------------------------------------------------------------------------
    // Driving the job
    // ----------------------------------------------------------------------------------------

    /**
     * Launches the job by name and reads the execution back.
     *
     * <p>By name through the operator rather than by handing the launcher a job object, because that is
     * the only way this job ever starts: the operator applies the shared incrementer, which is what
     * makes a resubmission with an identical parameter set expressible exactly as it was for the legacy
     * member.
     *
     * @return the completed execution, including its step executions
     * @throws Exception if the launch is refused
     */
    private JobExecution runTheJob() throws Exception {
        final Long executionId =
                this.jobOperator.startNextInstance(BackupTransactionJobConfig.JOB_NAME);
        assertThat(executionId)
                .as("a launched job has a persisted execution identifier before its first step runs,"
                        + " which is what names its local working file; the durable generation number"
                        + " is the store's own and is allocated when it publishes (DL-210)")
                .isNotNull();
        final JobExecution execution = this.jobExplorer.getJobExecution(executionId);
        assertThat(execution).as("the launched execution is readable back by its identifier")
                .isNotNull();
        this.hasPublished = true;
        return execution;
    }

    /**
     * Records one prior posting run that refused at least one record, in the shared job repository.
     *
     * <p>Its step <em>completed</em> and states the posting tier's rejects-present code, which is the
     * one return code that tier assigns. The point of recording it is that it belongs to a different
     * execution: this job's gate compares against the steps of its own execution, so the code is
     * invisible to it, and the run below proves that rather than assuming it.
     *
     * @return the recorded execution, to be handed to {@link #forget(JobExecution)} afterwards
     * @throws Exception if the execution cannot be recorded
     */
    private JobExecution recordPostingRunWithRejects() throws Exception {
        final JobParameters parameters = new JobParametersBuilder()
                .addString(POSTING_PROBE_PARAMETER, PINNED_BUSINESS_DATE.toString())
                .toJobParameters();
        final JobExecution posting =
                this.jobRepository.createJobExecution(PostTransactionJobConfig.JOB_NAME, parameters);
        final LocalDateTime moment = LocalDateTime.ofInstant(PINNED_INSTANT, ZoneOffset.UTC);
        final ExitStatus rejectsPresent =
                new ExitStatus(TransactionValidationProcessor.EXIT_CODE_REJECTS_PRESENT);

        final StepExecution step = posting.createStepExecution(PostTransactionJobConfig.STEP_NAME);
        step.setStartTime(moment);
        step.setEndTime(moment);
        step.setStatus(BatchStatus.COMPLETED);
        step.setExitStatus(rejectsPresent);
        this.jobRepository.add(step);

        posting.setStartTime(moment);
        posting.setEndTime(moment);
        posting.setStatus(BatchStatus.COMPLETED);
        posting.setExitStatus(rejectsPresent);
        this.jobRepository.update(posting);
        return posting;
    }

    /**
     * Removes a recorded execution and its instance again, so the shared repository is left as found.
     *
     * @param execution the execution to remove; its step executions go with it
     */
    private void forget(final JobExecution execution) {
        this.jobRepository.deleteJobExecution(execution);
        this.jobRepository.deleteJobInstance(execution.getJobInstance());
    }

    /**
     * Asks the one ceiling for its verdict on a prior step that ended in a given way.
     *
     * <p>The verdict is a function of the outcomes a job has accumulated, so presenting one step in a
     * chosen state is the whole of the input. It is presented directly because no step of this job
     * produces the middle case - a step that <em>completed</em> while stating a completion code of its
     * own - and that case is precisely the one the divergent legacy literal would have admitted.
     *
     * @param  status     the terminal status the prior step ended with
     * @param  exitStatus the exit status it carried
     * @return the name of the verdict, being either the permitting or the refusing outcome
     */
    private static String verdictAfter(final BatchStatus status, final ExitStatus exitStatus) {
        final JobExecution execution =
                new JobExecution(PROBE_EXECUTION_ID, new JobParametersBuilder().toJobParameters());
        final StepExecution prior =
                execution.createStepExecution(BackupTransactionJobConfig.ARCHIVE_STEP_NAME);
        prior.setStatus(status);
        prior.setExitStatus(exitStatus);

        final FlowExecutionStatus verdict =
                ConditionCodeGate.ALL_PRIOR_STEPS_ZERO.decide(execution, prior);
        assertThat(verdict).as("a gate always answers, so a null verdict is not one of the outcomes")
                .isNotNull();
        return verdict.getName();
    }

    /**
     * Reads back the object-versioning state the bucket reports.
     *
     * <p>A read and nothing else: the bucket, its versioning and every other resource are provisioned
     * before this specification runs, and nothing here creates or configures one. A bucket that has
     * never had versioning configured reports no status at all, and the absent case is reported
     * definitely so that a comparison against the enabled state answers either way rather than
     * against a null.
     *
     * @param  bucket the bucket to inspect
     * @return the state the service reports
     */
    private static BucketVersioningStatus bucketVersioning(final String bucket) {
        final BucketVersioningStatus status = s3Client().getBucketVersioning(
                GetBucketVersioningRequest.builder().bucket(bucket).build()).status();
        return status == null ? BucketVersioningStatus.UNKNOWN_TO_SDK_VERSION : status;
    }

    // ----------------------------------------------------------------------------------------
    // The fixture, and the independent oracle over it
    // ----------------------------------------------------------------------------------------

    /**
     * The three records the master holds before an archive, in the order the archive must carry them.
     *
     * <p>Ascending by business key, because the unload reads the master sequentially in key order.
     * The three deliberately differ in the one field whose image is not a plain justification: the
     * amount. One is positive with a nonzero final digit, one is <strong>negative</strong>, and one is
     * positive with a final digit of zero - so all three of the overpunched final bytes an archive can
     * legitimately carry are produced and compared for real rather than reasoned about.
     *
     * @return the fixture, ascending by identifier
     */
    private static List<ArchiveRecord> master() {
        return List.of(
                new ArchiveRecord(FIRST_ID, "01", "0005", "POS TERM", "Archive fixture purchase",
                        new BigDecimal("123.45"), "000000123", "Merchant Name", "Merchant City",
                        "72112", SEEDED_CARD, FIXTURE_ORIGINAL_TIMESTAMP, BLANK_TIMESTAMP),
                new ArchiveRecord(SECOND_ID, "02", "0005", "OPERATOR", "Archive fixture return",
                        new BigDecimal("-67.80"), "000000123", "Merchant Name", "Merchant City",
                        "72112", SEEDED_CARD, FIXTURE_ORIGINAL_TIMESTAMP, BLANK_TIMESTAMP),
                new ArchiveRecord(THIRD_ID, "01", "0005", "POS TERM", "Archive fixture purchase",
                        new BigDecimal("500.00"), "000000123", "Merchant Name", "Merchant City",
                        "72112", SEEDED_CARD, FIXTURE_ORIGINAL_TIMESTAMP, BLANK_TIMESTAMP));
    }

    /**
     * Writes the fixture into the shared master through the repository.
     *
     * <p>Inserted in the reverse of the order the archive is expected to carry, so that an
     * implementation which wrote rows in insertion order rather than in ascending business-key order
     * would fail the byte comparison rather than pass it by coincidence.
     *
     * @param records the fixture, ascending by identifier
     */
    private void seed(final List<ArchiveRecord> records) {
        for (int ordinal = records.size() - 1; ordinal >= 0; ordinal--) {
            this.transactionRepository.save(records.get(ordinal).entity());
        }
    }

    /**
     * Renders the whole expected archive, independently of everything that produces the real one.
     *
     * <p>Each image is measured in <strong>encoded bytes</strong> before it is accepted into the
     * expectation, because a character count is not a width: a value outside US-ASCII would occupy two
     * bytes and the record would no longer be 350 of them.
     *
     * @param  records the fixture, in the order the archive must carry it
     * @return the exact bytes the store is expected to hold
     */
    private static byte[] expectedArchive(final List<ArchiveRecord> records) {
        final StringBuilder archive = new StringBuilder(records.size() * RECORD_LENGTH);
        for (final ArchiveRecord record : records) {
            final String image = record.image();
            assertThat(image.getBytes(StandardCharsets.US_ASCII))
                    .as("the expected image of %s is itself exactly one record wide, so the"
                            + " expectation cannot drift without saying so", record.id())
                    .hasSize(RECORD_LENGTH);
            archive.append(image);
        }
        return archive.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Names the newest archive generation the store holds, by asking the store.
     *
     * <p>The generation number is allocated by the store at publication time, as one more than the
     * highest generation the base already holds, so it is deliberately not derivable from anything this
     * specification knows beforehand - see {@code docs/decision-log.md} entry DL-210. What this
     * specification can and does assert independently is the <em>shape</em> of the name, which
     * {@link #assertGenerationKeyShape(String)} does from its own constants.
     *
     * @param  bucket the bucket to look in
     * @return the newest generation key beneath the canonical archive base
     */
    private static String newestArchiveKey(final String bucket) {
        final List<String> keys =
                stagedObjectKeysUnder(bucket, CANONICAL_ARCHIVE_BASE + "/");
        assertThat(keys)
                .as("the archive step must have published at least one generation beneath %s",
                        CANONICAL_ARCHIVE_BASE)
                .isNotEmpty();
        return keys.getLast();
    }

    /**
     * Asserts that a published key has the legacy absolute-generation shape.
     *
     * <p>Composed from this specification's own constants, so the expectation is independent of the code
     * that composed the real name: the canonical base, one separator, and a ten-digit generation token.
     *
     * @param key the key the store reported
     */
    private static void assertGenerationKeyShape(final String key) {
        assertThat(key)
                .as("the legacy generation base, a separator and a ten-digit absolute generation")
                .startsWith(CANONICAL_ARCHIVE_BASE + "/")
                .matches(java.util.regex.Pattern.quote(CANONICAL_ARCHIVE_BASE) + "/G\\d{10}V00");
    }

    /**
     * Places an alphanumeric value left-justified and space-padded, as a character field is rendered.
     *
     * <p>A value exactly as wide as its field is placed unchanged, which is what preserves a
     * contractual run of trailing spaces - the blank processing timestamp is twenty-six of them and
     * they are part of the record.
     *
     * @param  value the value to place
     * @param  width the field's width
     * @return the field image
     */
    private static String alphanumeric(final String value, final int width) {
        assertThat(value.length())
                .as("'%s' does not fit a %d-character field, so the fixture is wrong rather than the"
                        + " code under test", value, width)
                .isLessThanOrEqualTo(width);
        return value + " ".repeat(width - value.length());
    }

    /**
     * Places an unsigned numeric value right-justified and zero-padded, as an unsigned field is
     * rendered. Leading zeros are significant, so the value is carried as text and never parsed.
     *
     * @param  value the value to place
     * @param  width the field's width
     * @return the field image
     */
    private static String numeric(final String value, final int width) {
        assertThat(value.length())
                .as("'%s' does not fit a %d-digit field", value, width)
                .isLessThanOrEqualTo(width);
        return "0".repeat(width - value.length()) + value;
    }

    /**
     * Renders a monetary value as its zoned-decimal image, sign overpunched into the final byte.
     *
     * <p>The two declared decimal places are moved into the integer part, which turns the value into
     * its whole number of hundredths <strong>exactly</strong>: every amount in the fixture already
     * carries exactly two of them, so the conversion is refused rather than rounded if one ever does
     * not. No rescaling and no rounding policy is stated anywhere on this path, which is the point -
     * the estate specifies no rounding at all, and an oracle that rounded would agree with a defect.
     *
     * <p>The digits are then zero-filled to the field's width and the last of them is replaced by the
     * character that encodes both it and the sign.
     *
     * @param  amount the value to render
     * @param  width  the field's width, being nine integer digits and two decimal places
     * @return the field image
     */
    private static String zonedAmount(final BigDecimal amount, final int width) {
        final BigInteger hundredths = amount.abs().movePointRight(2).toBigIntegerExact();
        final String digits = String.format(Locale.ROOT, "%0" + width + "d", hundredths);
        assertThat(digits.length())
                .as("%s does not fit a %d-byte zoned field", amount, width)
                .isEqualTo(width);

        final String overpunch = amount.signum() < 0 ? NEGATIVE_OVERPUNCH : POSITIVE_OVERPUNCH;
        final int finalDigit = digits.charAt(width - 1) - '0';
        return digits.substring(0, width - 1) + overpunch.charAt(finalDigit);
    }

    /**
     * Reads the configuration's own source and returns the part of it that is code.
     *
     * <p>Every comment line is dropped first, and that is not tidiness - it is what makes the
     * assertions below mean what they say. A configuration of this kind documents what it deliberately
     * does <em>not</em> do, so its prose contains the very words a posture check looks for: a sentence
     * stating that there is no partitioning would otherwise be read as partitioning, and the check
     * would fail against a file that is entirely correct. A posture is a property of the code, so the
     * code is what is scanned.
     *
     * <p>A line is a comment line when its first non-blank characters open a block comment, continue
     * one, or open a line comment. That is the only comment shape this module's sources use, and a
     * trailing comment after code is dropped with the prose it belongs to only if it stands on its own
     * line - which is the convention every source here follows.
     *
     * @return the executable lines of the configuration under test, newline separated
     * @throws IOException if the source cannot be read
     */
    private static String configurationCode() throws IOException {
        final String source = Files.readString(CONFIGURATION_SOURCE, StandardCharsets.UTF_8);
        final StringBuilder code = new StringBuilder(source.length());
        for (final String line : source.lines().toList()) {
            final String trimmed = line.strip();
            if (trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//")) {
                continue;
            }
            code.append(line).append('\n');
        }
        assertThat(code.length())
                .as("the configuration's source is readable and carries code, so a scan that found"
                        + " nothing cannot pass by reading an empty file")
                .isPositive();
        return code.toString();
    }

    /**
     * Counts the non-overlapping occurrences of a fragment in a text.
     *
     * @param  text     the text to scan
     * @param  fragment the fragment to count
     * @return how many times it occurs
     */
    private static int countOf(final String text, final String fragment) {
        int occurrences = 0;
        int from = text.indexOf(fragment);
        while (from >= 0) {
            occurrences++;
            from = text.indexOf(fragment, from + fragment.length());
        }
        return occurrences;
    }

    /**
     * One record of the fixture, in both of the forms this specification needs it in: the entity the
     * master is seeded with, and the fixed-width image the archive must carry.
     *
     * <p>The two are built from the same thirteen values by two independent routes. The entity goes
     * through the repository and comes back out through the job's own mapping layer; the image is
     * assembled here from justification and padding rules stated in this file. Their agreement is what
     * the byte comparison means.
     *
     * @param id                  the sixteen-character identifier, the business key
     * @param typeCode            the two-character type code
     * @param categoryCode        the four-digit category code, an unsigned numeric field
     * @param source              the source, carried with its trailing spaces
     * @param description         the description
     * @param amount              the amount, at the two decimal places the field declares
     * @param merchantId          the nine-digit merchant identifier, an unsigned numeric field
     * @param merchantName        the merchant name
     * @param merchantCity        the merchant city
     * @param merchantZip         the merchant postal code
     * @param cardNumber          the card number, which must name a card the seed already carries
     * @param originalTimestamp   the twenty-six character origination timestamp
     * @param processingTimestamp the twenty-six character processing timestamp, which may be blank
     */
    private record ArchiveRecord(String id, String typeCode, String categoryCode, String source,
            String description, BigDecimal amount, String merchantId, String merchantName,
            String merchantCity, String merchantZip, String cardNumber, String originalTimestamp,
            String processingTimestamp) {

        /** @return the entity the master is seeded with */
        Transaction entity() {
            return new Transaction(this.id, this.typeCode, this.categoryCode, this.source,
                    this.description, this.amount, this.merchantId, this.merchantName,
                    this.merchantCity, this.merchantZip, this.cardNumber, this.originalTimestamp,
                    this.processingTimestamp);
        }

        /**
         * Renders the thirteen mapped fields and the unmapped filler run, in layout order.
         *
         * @return the 350-character image the archive must carry for this record
         */
        String image() {
            return new StringBuilder(RECORD_LENGTH)
                    .append(alphanumeric(this.id, ID_WIDTH))
                    .append(alphanumeric(this.typeCode, TYPE_CODE_WIDTH))
                    .append(numeric(this.categoryCode, CATEGORY_CODE_WIDTH))
                    .append(alphanumeric(this.source, SOURCE_WIDTH))
                    .append(alphanumeric(this.description, DESCRIPTION_WIDTH))
                    .append(zonedAmount(this.amount, AMOUNT_WIDTH))
                    .append(numeric(this.merchantId, MERCHANT_ID_WIDTH))
                    .append(alphanumeric(this.merchantName, MERCHANT_NAME_WIDTH))
                    .append(alphanumeric(this.merchantCity, MERCHANT_CITY_WIDTH))
                    .append(alphanumeric(this.merchantZip, MERCHANT_ZIP_WIDTH))
                    .append(alphanumeric(this.cardNumber, CARD_NUMBER_WIDTH))
                    .append(alphanumeric(this.originalTimestamp, ORIGINAL_TIMESTAMP_WIDTH))
                    .append(alphanumeric(this.processingTimestamp, PROCESSING_TIMESTAMP_WIDTH))
                    .append(" ".repeat(FILLER_WIDTH))
                    .toString();
        }
    }

    /**
     * The narrowest context this job needs: batch orchestration, persistence, the object-store client
     * and the two collaborators the configuration is built against.
     *
     * <p>The application entry point is deliberately not used. This job touches no endpoint, so booting
     * one would make the specification depend on infrastructure it asserts nothing about. Schema
     * evolution is switched off for the same reason: the shared server this class inherits has already
     * been migrated, so migrating again would be a second opinion about the schema rather than a test of
     * this job - and the migration history is read back through the shared base's own connection, which
     * is what keeps the assertion about the four delivered versions honest.
     *
     * <p>No enabling annotation for batch appears here or anywhere in the module: under this framework
     * generation the batch auto-configuration backs off when that annotation is present, so adding it
     * would switch off the very infrastructure this context depends on.
     *
     * <p>The object-store client is the real one, built by the framework's own auto-configuration
     * against the emulator address the shared base publishes. A stand-in would defeat the purpose of the
     * specification, and the settings type is registered by the AWS configuration class that is imported
     * here - never a second time by this context, which would be a start-up conflict.
     *
     * <p>The publication lock is the production one rather than a direct-run stand-in, because this slice
     * has the server that lock needs: the archive base is shared with the transaction-report job's unload
     * step, so the lock is what keeps two publications from interleaving their retention passes.
     *
     * <p>The shared generation store is imported for the same reason and is likewise the production one.
     * The job reads its retention depth and its local staging root from that store rather than restating
     * either, so the store is a collaborator of the job and not an incidental bean; a slice that left it
     * out would not start, and one that substituted a stand-in would assert a retention depth this module
     * does not publish.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = PrometheusExemplarsAutoConfiguration.class)
    @Import({BackupTransactionJobConfig.class, BatchConfig.class, AwsConfig.class,
            StagedGenerationStore.class, AdvisoryGenerationPublicationLock.class})
    @EnableJpaRepositories(basePackageClasses = TransactionRepository.class)
    @EntityScan(basePackageClasses = Transaction.class)
    static class JobContext {

        JobContext() {
            // Intentionally empty: this context holds no state of its own.
        }

        /**
         * The clock every batch timestamp in this slice is read from.
         *
         * <p>The shared base's fixed clock, so nothing in a run reads a system clock and every derived
         * value means the same thing on every host and on every run.
         *
         * @return the fixed clock
         */
        @Bean
        Clock fixedClock() {
            return FIXED_CLOCK;
        }
    }
}
