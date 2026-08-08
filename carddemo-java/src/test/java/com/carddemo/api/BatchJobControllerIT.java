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

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.domain.UserSecurity;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.repository.UserSecurityRepository;
import com.carddemo.service.CredentialDigestService;
import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.support.TestDataFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestClassOrder;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The operational batch-control contract of {@link BatchJobController}, exercised over the shipped
 * servlet boundary and the shipped filter chain, against a real PostgreSQL 16 server carrying the
 * migrated schema and the framework's own job-repository metadata.
 *
 * <h2>Why this specification boots the whole delivered application</h2>
 *
 * <p>Every other integration specification in this module assembles an explicit slice, and each of them
 * is right to: a slice makes the graph legible and keeps a scan from sweeping in what a reader cannot
 * see. This one is the exception, and the reason is the single question it exists to answer. The
 * headline property of the batch-control surface is that the inventory of launchable work is
 * <strong>closed at nine</strong>. A slice cannot answer that, because a slice registers whichever job
 * configurations it happens to name - so an assertion that "exactly nine are registered" would be an
 * assertion about the import list at the bottom of this file rather than about the delivered
 * application. Booting the application is what makes the number a measurement.
 *
 * <p>The precedent is {@code com.carddemo.CardDemoApplicationIT}, which starts the same application
 * under the same profile against the same shared server for the same reason: some properties are only
 * true of the whole.
 *
 * <h2>What is asserted, and why each item is contract rather than detail</h2>
 * <ul>
 *   <li><strong>Nine launchable names and no tenth.</strong> Of the seventy-eight program-execution
 *       steps across the twenty-nine job members and two cataloged procedures of the estate, only nine
 *       invoke an application program; the remaining sixty-nine are data-set utilities absorbed by the
 *       migrations, by comparators inside the job that needs them, and by the local service
 *       composition. Nine job configurations is therefore the measured answer, and the closure is a
 *       security property rather than tidiness: without it the launch path would be "start whatever the
 *       caller names".</li>
 *   <li><strong>A name outside the nine reaches no framework call and discloses nothing.</strong> The
 *       refusal carries one neutral summary; it never names the value that arrived, a bean, a type, a
 *       failure chain, a query or a location on disk.</li>
 *   <li><strong>Nothing runs because a context started.</strong> Launch-on-start is disabled by the
 *       shipped configuration document, so every execution is on demand through this surface.</li>
 *   <li><strong>The recorded orphan is launchable by name and is part of no sequence.</strong> The
 *       daily-transaction extract translates a complete four-hundred-and-ninety-one line program that
 *       no job member, cataloged procedure or online resource definition invokes - verified by
 *       searching the whole job, procedure and resource-definition tree for its name and finding
 *       nothing. It is registered so that it stays exercisable rather than becoming dead code.</li>
 *   <li><strong>A launch is repeatable, and the parameters it records are the caller's own plus exactly
 *       one server-minted run identity.</strong> See the section below, which is the one place where
 *       this specification asserts something a casual reading of the requirement would get backwards.
 *       </li>
 *   <li><strong>Parameter shape belongs to the jobs' own validators</strong> - the ten-character
 *       all-digit accrual parameter, the inclusive report window, and the probe mode that collapses
 *       four legacy read streams into one parameterised job - and a refusal arrives at this boundary as
 *       a bad request rather than as an internal failure.</li>
 *   <li><strong>The status surface is keyed by execution identifier and carries four values.</strong>
 *       Never the framework's exit description, which holds a rendered stack trace.</li>
 *   <li><strong>Both operations require the administrative authority</strong> under a rule of the
 *       chain's own, and the surface is deliberately absent from the eighteen-transaction route table
 *       because not one of those eighteen registered transactions starts a job.</li>
 *   <li><strong>There is no third operation.</strong> No run-everything, no pipeline, no next-in-line,
 *       no schedule, no bulk start. Adding one would invent an ordering guarantee the estate never
 *       made: it held no master orchestrator, and the sequence in which work was submitted was an
 *       operational convention held by whoever ran it.</li>
 * </ul>
 *
 * <h2>The repeat contract, stated precisely, because it reads backwards at first glance</h2>
 *
 * <p>A framework job identity is its name plus its identifying parameters, and the framework refuses a
 * second instance of an identity it has already recorded. On the estate that refusal has no
 * counterpart: a job member could be submitted again with an identical parameter set and would simply
 * run again - the posting job on the same processing date, the accrual run with the same run date, the
 * backup after a failed cycle. Left alone, this surface would have made the first submission of any
 * parameter set the only one, which is a behavioural regression and not an idempotency guarantee.
 *
 * <p>So the delivered launch mints <strong>one</strong> identifying value on the server, through the
 * shared parameter incrementer that {@code config/BatchConfig} publishes and all nine configurations
 * attach, seeded from the parameters of that job's most recent instance. What this specification
 * therefore proves is the honest form of "no injected uniquifier":
 * <ol>
 *   <li>the recorded parameters are exactly the submitted names and values, byte for byte, plus
 *       <strong>exactly one</strong> further key;</li>
 *   <li>that key is the framework's own run-identifier key and nothing else - there is no timestamp
 *       key, no unique-identifier key, no random value, no clock reading and no current-date key;</li>
 *   <li>its value is a positive whole number that advances by <strong>exactly one</strong> between two
 *       consecutive launches of the same job, which is what a counter seeded from stored metadata does
 *       and what a clock or a random source demonstrably does not;</li>
 *   <li>a caller can neither supply it nor collide with it, because it is outside the closed set of
 *       names the addressed job declares.</li>
 * </ol>
 * A run identity minted from stored metadata is the faithful translation; a wall-clock or random
 * uniquifier would be the convenience shortcut, and asserting its absence is the point.
 *
 * <h2>Where the expectations come from</h2>
 *
 * <p>Routes, path variables, media types and status codes are taken from the shipped boundary and its
 * published contract types, because those are the shape this specification has to speak to. Everything
 * this specification <em>claims</em> - the nine names, the four parameter keys, the four probe modes,
 * the run-identifier key, every operator text - is written out here as its own literal rather than read
 * from the component that also publishes it. An expectation that borrows its subject's own constant
 * proves only that the subject agrees with itself.
 *
 * <h2>Server ownership, determinism and reset</h2>
 *
 * <p>The database server belongs to {@link AbstractPostgresIT} and is shared by every integration
 * specification in the run, so this class declares no server of its own, no server extension, no
 * data-source property source and no context-discarding annotation. Time comes from that class's pinned
 * instant, so nothing here reads a wall clock and no assertion anywhere depends on an elapsed time, a
 * throughput figure, a record rate or a memory figure. The framework's own job-repository tables are
 * read and never written directly, and they are excluded from every application-row count, because they
 * are the framework's and not one of the eleven migrated record layouts.
 *
 * <p>The delivered state is restored before the first method and after the last, because a launch
 * commits on its own connections and no transactional rollback can reach it. The two sign-on identities
 * this specification owns live in a reserved eight-character range the credential seed never occupies.
 *
 * <h2>Credential handling</h2>
 *
 * <p>The legacy record held an eight-character cleartext credential and the legacy sign-on path compared
 * it directly; the target hashes with BCrypt, which is a recorded parity exception. <strong>The legacy
 * cleartext value appears nowhere in this file</strong> - not in a literal, a constant, a comment, an
 * identifier, a display name or an assertion message. Where a credential is needed it is recovered at
 * run time, by offset, from the class-path fixture through {@link TestDataFactory}, folded with the same
 * fold the shipped path applies, handed to the shipped hashing service, and overwritten. No signing
 * secret is written here either: the active profile supplies the suite fixture.
 *
 * <h2>The object store is never asserted on, so there is nothing to isolate</h2>
 *
 * <p>Two of the nine jobs publish a durable artefact to the object store when they complete cleanly.
 * This specification asserts a <em>launch</em> and a <em>status shape</em> and never a clean completion
 * of either, so no assertion here depends on the object store answering; whole-pipeline parity, at the
 * four contractual widths, belongs to the end-to-end tier and is asserted there. Nothing in this file
 * stands up an emulator, names a queue, a bucket, a topic, a region or an endpoint, or reaches a cloud
 * software-development kit.
 *
 * <p>Provenance: the twenty-nine job members and two cataloged procedures of {@code app/jcl} and
 * {@code app/proc} - among them {@code POSTTRAN.jcl}, {@code INTCALC.jcl}, {@code COMBTRAN.jcl},
 * {@code CREASTMT.JCL}, {@code TRANREPT.jcl} with {@code TRANREPT.prc}, {@code TRANBKP.jcl},
 * {@code PRTCATBL.jcl} and the four read streams {@code READACCT.jcl}, {@code READCARD.jcl},
 * {@code READCUST.jcl} and {@code READXREF.jcl} - the orphaned extract {@code app/cbl/CBTRN01C.cbl},
 * and the resource definitions of {@code app/csd/CARDDEMO.CSD}; all read as read-only reference at the
 * checkout the migration's traceability matrix records in its header, which is where commit-level
 * provenance belongs rather than restated in every file. Step names, program names, parameter
 * widths, counts and condition-code forms are metadata and are cited; no job-control statement,
 * ordering-utility control statement, procedure-division fragment, screen map or resource definition is
 * transcribed, and nothing in the legacy tree is read at run time.
 *
 * <p>No user-specified rules govern this file - the project's rules document reports that none were
 * provided - so the work is held to the enterprise standards the migration plan substitutes for them,
 * and where faithful translation and idiomatic Java diverge, faithful wins.
 */
@SpringBootTest(classes = BatchJobControllerIT.DeliveredGraph.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            // The shared server was migrated to the head of the delivered set by the base class, so a
            // second migration from this context would be redundant work with no new state to apply.
            "spring.flyway.enabled=false",
            // validate, never none and never any generating value. The shipped profile declares
            // validate and this context keeps it: a mapping that had drifted from the migrated schema
            // must fail at refresh rather than be silently reconciled, and validate emits no schema
            // statement of its own.
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.main.banner-mode=off"})
@AutoConfigureMockMvc
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
@DisplayName("BatchJobControllerIT - on-demand launch and status for the nine wired jobs: a closed "
        + "inventory, nothing running at start-up, the recorded orphan launchable by name, and a "
        + "repeat that mints one server run identity and nothing else")
public class BatchJobControllerIT extends AbstractPostgresIT {

    // =================================================================================================
    // THE CONTRACT, WRITTEN OUT HERE RATHER THAN IMPORTED
    // =================================================================================================

    /**
     * The nine launchable job names, in the order an operator reading the estate meets them.
     *
     * <p>Written out as literals on purpose. The delivered inventory publishes the same nine, and the
     * whole value of this list is that it was authored independently of it: an expectation that read the
     * inventory would agree with a renamed, a dropped or an added job without noticing.
     *
     * <p>Each name and the legacy work it translates: daily transaction posting
     * ({@code app/jcl/POSTTRAN.jcl}); interest and fee accrual ({@code app/jcl/INTCALC.jcl});
     * transaction consolidation ({@code app/jcl/COMBTRAN.jcl}); customer statement generation
     * ({@code app/jcl/CREASTMT.JCL}); transaction reporting ({@code app/jcl/TRANREPT.jcl} with
     * {@code app/proc/TRANREPT.prc}); transaction-master backup ({@code app/jcl/TRANBKP.jcl}); category
     * balance reporting ({@code app/jcl/PRTCATBL.jcl}); the file probe, which collapses the four
     * sequential-read verification streams into one parameterised job; and the recorded orphan.
     */
    private static final List<String> NINE_LAUNCHABLE_JOB_NAMES = List.of(
            "postTransactionJob",
            "interestCalculationJob",
            "combineTransactionsJob",
            "createStatementJob",
            "transactionReportJob",
            "backupTransactionJob",
            "categoryBalanceReportJob",
            "fileProbeJob",
            "dailyTransactionReadJob");

    /** The accrual job, whose step carries the one ten-character program parameter of the estate. */
    private static final String INTEREST_JOB = "interestCalculationJob";

    /** The reporting job, whose in-stream parameter card carries the inclusive window as two dates. */
    private static final String REPORT_JOB = "transactionReportJob";

    /** The one parameterised probe job the four legacy read streams collapse into. */
    private static final String FILE_PROBE_JOB = "fileProbeJob";

    /**
     * How many jobs the delivered application registers, stated once as its own figure.
     *
     * <p>Nine, because nine of the seventy-eight program-execution steps invoke an application program.
     * Sixty-nine invoked a data-set utility and became a migration, a comparator or a local service, so
     * they are not steps of a job at all.
     */
    private static final int LAUNCHABLE_JOB_COUNT = 9;

    /** The recorded orphan: registered, launchable by name, and part of no sequence. */
    private static final String ORPHAN_JOB = "dailyTransactionReadJob";

    /** A job whose declared parameter set is empty, used to prove an empty set refuses every name. */
    private static final String NO_PARAMETER_JOB = "postTransactionJob";

    /**
     * The four parameter names the nine jobs read between them, and nothing else.
     *
     * <p>Six of the nine read none at all, so four names across nine jobs is the whole schema.
     */
    private static final String INTEREST_PARM_DATE_KEY = "interestParmDate";

    /** Inclusive lower bound of the reporting window, ten characters. */
    private static final String REPORT_START_DATE_KEY = "reportStartDate";

    /** Inclusive upper bound of the reporting window, ten characters. */
    private static final String REPORT_END_DATE_KEY = "reportEndDate";

    /** Selects which of the four collapsed read streams the probe performs. */
    private static final String FILE_PROBE_MODE_KEY = "fileProbeMode";

    /**
     * The framework's own run-identifier parameter key: the single identifying value the server mints.
     *
     * <p>Written out rather than imported, for the same reason every other expectation here is, and
     * because importing it would reach from the boundary tier into the batch tier - a direction the
     * module's layering does not open even for a test.
     */
    private static final String SERVER_RUN_IDENTITY_KEY = "run.id";

    /**
     * The four legal probe modes, case sensitive, in declaration order.
     *
     * <p>One per legacy read stream: the account master, the card master, the customer master and the
     * card cross-reference. The mode value has no legacy antecedent literal, because the legacy
     * distinction was the identity of the job member rather than the content of a parameter.
     */
    private static final List<String> LEGAL_PROBE_MODES =
            List.of("account", "card", "customer", "crossReference");

    /** A probe mode the job never declared, for the refusal arm. */
    private static final String UNKNOWN_PROBE_MODE = "statement";

    /**
     * An execution identifier the framework's metadata will not hold.
     *
     * <p>Far above anything a suite could allocate and still a well-formed whole number, so the refusal is
     * genuinely about the execution being absent rather than about the value failing to convert.
     */
    private static final long ABSENT_EXECUTION_ID = 9_000_000_000_000L;

    /** A path segment that is not a whole number, for the conversion arm of the status operation. */
    private static final String NON_NUMERIC_EXECUTION_ID = "not-a-number";

    /**
     * The measured shape of the accrual parameter: ten characters, every one a digit.
     *
     * <p>The value is reused verbatim as the leading characters of every transaction identifier the
     * accrual run synthesises, which is why nothing may trim, pad or reformat it and why its width is
     * exact rather than a maximum.
     *
     * <p><strong>The two-digit tail is owned by this specification and must not be changed to the
     * suffix any other specification uses.</strong> That is a correctness requirement rather than a
     * stylistic one, and it follows from how the launch identity is composed. The server mints the run
     * identity by asking the job's incrementer for the value after the one recorded on the
     * <em>most recently created instance of that same job</em>, and a specification that launches
     * through the framework directly records no run identity at all. Once such an instance is the
     * newest, the seed collapses back to the first value, so any launch this operation admits is offered
     * the identity {@code {this value, first run identity}}. A specification that runs earlier against
     * the same server and seeds its own incrementer from its own in-memory state — rather than from the
     * recorded metadata — permanently occupies exactly that pair for whatever accrual value it chose.
     * Reusing its value here would hand this operation an identity the metadata already holds, and the
     * launch guard would answer the duplicate outcome instead of admitting the launch. The batch
     * metadata is shared across every specification in the phase and is never truncated, so the only
     * sound defence is for the caller-visible half of the identity to be unique to this file.
     *
     * <p>The leading eight positions remain the shared pinned business date, so the value is still
     * deterministic and host independent, and they satisfy the validator's requirement that those
     * positions name a real day. Only the free two-digit tail is reserved here.
     */
    private static final String VALID_INTEREST_PARM_DATE = "2022061041";

    /** One character short of the declared width, and otherwise perfectly well formed. */
    private static final String NINE_CHARACTER_INTEREST_PARM_DATE = "202206104";

    /** One character beyond the declared width. */
    private static final String ELEVEN_CHARACTER_INTEREST_PARM_DATE = "20220610411";

    /** Exactly ten characters, one of which is not a digit. */
    private static final String NON_DIGIT_INTEREST_PARM_DATE = "2022-06-10";

    /** A ten-digit value whose leading eight positions name no day in any calendar. */
    private static final String IMPOSSIBLE_CALENDAR_INTEREST_PARM_DATE = "2022023000";

    /** A window bound that carries the right shape and names no day in any calendar. */
    private static final String IMPOSSIBLE_CALENDAR_DATE = "2022-02-30";

    /** A window bound whose shape the published contract refuses before any job sees it. */
    private static final String MALFORMED_SHAPE_DATE = "10 JUN 22";

    /**
     * Names the delivered surface never answers under, probed to prove there is no third operation.
     *
     * <p>Every one of them is a shape an orchestrating surface would have taken: start everything, start
     * the next one, resume one, put one on a timetable. None is mapped, and none may become mapped,
     * because the estate held no orchestrator to translate.
     */
    private static final List<String> UNMAPPED_OPERATION_SUBPATHS = List.of(
            "/run-all",
            "/start-all",
            "/pipeline",
            "/master",
            "/default",
            "/schedule",
            "/next-in-line");

    /**
     * Job-member names that were deliberately not migrated, probed to prove neither is launchable.
     *
     * <p>Two of them toggled the availability of an online file and the third drove the resource
     * definition utility. None has a runtime equivalent once the indexed files are replaced by tables,
     * so each is recorded as intentionally unmigrated rather than dropped.
     */
    private static final List<String> INTENTIONALLY_UNMIGRATED_MEMBERS =
            List.of("CLOSEFIL", "OPENFIL", "CBADMCDJ");

    /**
     * Utility program names the sixty-nine absorbed steps invoked, probed to prove none is launchable.
     *
     * <p>They defined, deleted, copied and toggled data sets. The migrations, the comparators inside the
     * job that needs them and the local service composition absorbed all of them, which is why the
     * launchable inventory is nine and not seventy-eight.
     */
    private static final List<String> ABSORBED_UTILITY_PROGRAMS =
            List.of("IDCAMS", "SDSF", "SORT", "IEFBR14", "IEBGENER", "DFHCSDUP");

    // =================================================================================================
    // OPERATOR TEXT AND SETTINGS THIS SPECIFICATION ASSERTS
    // =================================================================================================

    /** The one neutral summary a keyed read that resolved to nothing answers with. */
    private static final String RECORD_NOT_FOUND_MESSAGE = "Record not found";

    /** The fixed refusal text for a parameter name the addressed job does not declare. */
    private static final String UNDECLARED_PARAMETER_MESSAGE =
            "The request supplied a parameter this job does not declare.";

    /** The fixed refusal text for a parameter set the job's own validator rejected. */
    private static final String PARAMETERS_INVALID_MESSAGE =
            "The job parameters supplied were rejected by this job. Correct them and submit again.";

    /** The neutral summary the published contract's own declarative checks answer with. */
    private static final String DECLARATIVE_VALIDATION_MESSAGE = "Submitted data failed validation";

    /**
     * The neutral summary a document the boundary could not read at all answers with.
     *
     * <p>Reached when a caller names a property the closed typed request does not declare, because the
     * request refuses an unknown property rather than ignoring it. No field detail accompanies it: a
     * document that could not be parsed has no field to attribute anything to, and the parse diagnostic
     * would quote the submitted fragment.
     */
    private static final String MALFORMED_REQUEST_BODY_MESSAGE = "Request body could not be read";

    /** The neutral summary a request value that would not convert to its declared type carries. */
    private static final String REQUEST_BINDING_FAILED_MESSAGE = "Request value could not be bound";

    /** Summary a refusal for want of a session carries. */
    private static final String AUTHENTICATION_REQUIRED = "Authentication required";

    /** Summary a refusal for want of an entitlement carries. */
    private static final String ACCESS_DENIED = "Access denied";

    /** Presentation prefix an issued session travels behind. */
    private static final String BEARER_PREFIX = "Bearer ";

    /** The setting that decides whether work runs as a side effect of a context refreshing. */
    private static final String BATCH_AUTO_RUN_PROPERTY = "spring.batch.job.enabled";

    /** The value that setting must resolve to for this surface to be the only way work begins. */
    private static final String AUTO_RUN_DISABLED = "false";

    /**
     * The setting that would name one job for the framework's start-up runner to resolve.
     *
     * <p>Asserted absent rather than set to a value: naming a job here is the other half of auto-run,
     * and an explicit empty value would still leave a maintainer a switch to flip.
     */
    private static final String BATCH_AUTO_RUN_JOB_NAME_PROPERTY = "spring.batch.job.name";

    // =================================================================================================
    // THE TWO IDENTITIES THIS SPECIFICATION OWNS
    // =================================================================================================

    /**
     * Prefix of the eight-character identifier range this specification reserves.
     *
     * <p>The credential seed applies under this profile, so the identity table is not empty when a method
     * starts: it holds the ten delivered identities. Everything written here is keyed inside a range the
     * seed never occupies, and the cleanup is scoped to that range, so nothing here deletes, counts or
     * mutates a delivered row - several specifications in this module assert against those rows on the
     * same shared server.
     */
    private static final String RESERVED_PREFIX = "ITBATC";

    /** The administrative identity, which the batch-control rule admits. */
    private static final String ADMIN_IDENTITY = RESERVED_PREFIX + "01";

    /** The ordinary identity, which the same rule refuses although its session verifies. */
    private static final String ORDINARY_IDENTITY = RESERVED_PREFIX + "02";

    /** The role code the administrative arm carries. */
    private static final String ADMIN_ROLE_CODE = "A";

    /** The role code an ordinary identity carries. */
    private static final String ORDINARY_ROLE_CODE = "U";

    /** Given name both owned identities carry; identity, never a secret. */
    private static final String RESERVED_FIRST_NAME = "INTEGRATION";

    /** Family name both owned identities carry. */
    private static final String RESERVED_LAST_NAME = "BATCHCONTROL";

    // =================================================================================================
    // FIXED STATEMENTS AND FIXTURES
    // =================================================================================================

    /**
     * Counts the recorded executions of one job out of the framework's own metadata.
     *
     * <p>A complete literal statement with the job name bound as a parameter, so no value is ever
     * assembled into it. It is read-only: nothing in this file writes, alters or empties a
     * job-repository table, because those belong to the framework and not to the eleven migrated record
     * layouts.
     */
    private static final String EXECUTION_COUNT_SQL = """
            SELECT COUNT(*) FROM batch_job_execution execution
              JOIN batch_job_instance instance
                ON instance.job_instance_id = execution.job_instance_id
             WHERE instance.job_name = ?
            """;

    /**
     * Reports whether the framework has provisioned its own metadata tables yet.
     *
     * <p>They are created by the framework's schema initialiser the first time a context carrying the
     * batch infrastructure starts, which may be this specification's own context. Asking first is what
     * lets the start-up baseline be read before any context exists: no table means no recorded
     * execution, which is precisely the baseline of zero.
     */
    private static final String METADATA_PRESENT_SQL = """
            SELECT COUNT(*) FROM information_schema.tables
             WHERE table_schema = 'public'
               AND table_name = 'batch_job_instance'
            """;

    /** Reads and writes request and response documents without binding to a published contract type. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The shipped hashing service, held here so the owned digest is produced once per run.
     *
     * <p>Instantiated directly rather than injected because it is used during class initialisation,
     * before any context exists. It needs no configuration: a digest carries its own salt and the type
     * fixes its own cost. The injected instance is used for nothing else, so no verifier other than the
     * boundary's own ever sees the owned digest.
     */
    private static final CredentialDigestService OWNED_DIGEST_SOURCE = new CredentialDigestService();

    /**
     * Digest of the folded contents of the class-path fixture's credential window.
     *
     * <p>Folded because the shipped sign-on path folds every submission before it compares, so a digest
     * of the unfolded characters could never be matched by any submission. The window value itself is
     * read by offset at run time, used here, and overwritten; it is never returned, logged, named or
     * asserted on.
     *
     * <p>The delivered digests cannot be used instead: they were produced from the legacy provisioning
     * value, which this module deliberately does not hold, so no delivered identity can be signed on
     * with from inside this module. Owning two identities is the honest consequence.
     */
    private static final String OWNED_DIGEST = digestOfFoldedCredentialWindow();

    /**
     * The number of executions of the nine jobs recorded before this specification's context existed.
     *
     * <p>Captured by a static callback, which the test framework runs before it prepares the first test
     * instance and therefore before the application context is created. Comparing it against the same
     * count taken after the context has started is what makes "nothing runs because a context started" a
     * measurement rather than an assurance.
     */
    private static long executionsBeforeContextStarted;

    // =================================================================================================
    // INJECTED COLLABORATORS
    // =================================================================================================

    /** The shipped servlet boundary, with the shipped filter chain in front of it. */
    @Autowired
    private MockMvc mockMvc;

    /** The framework's own registry, which is the authority on what the application registered. */
    @Autowired
    private JobRegistry jobRegistry;

    /** The framework's own metadata reader, which is the authority on what ran and with what. */
    @Autowired
    private JobExplorer jobExplorer;

    /** The refreshed context, read for the beans that would make work start on their own. */
    @Autowired
    private ApplicationContext applicationContext;

    /** The resolved environment, read for the two settings that govern auto-run. */
    @Autowired
    private Environment environment;

    /** The identity table, for writing and removing the two identities this specification owns. */
    @Autowired
    private UserSecurityRepository users;

    /** The administrative session this instance signs on for, obtained once and reused. */
    private String administrativeSession;

    /** The ordinary session this instance signs on for, obtained once and reused. */
    private String ordinarySession;

    /** Creates the specification. */
    public BatchJobControllerIT() {
        super();
    }

    // =================================================================================================
    // LIFECYCLE
    // =================================================================================================

    /**
     * Returns the shared server to its delivered state and records the start-up baseline.
     *
     * <p>Both halves run before the application context is created, which is what the baseline depends
     * on. The restore is needed because a launch commits on its own connections: no transactional
     * rollback can reach what a job wrote, so the delivered state has to be re-established explicitly
     * however the run that preceded this one ended.
     *
     * @throws SQLException if the delivered state cannot be restored or the metadata cannot be read
     */
    @BeforeAll
    static void restoreDeliveredStateAndRecordBaseline() throws SQLException {
        restoreSeededState();
        executionsBeforeContextStarted = recordedExecutionsOfTheNineJobs();
    }

    /**
     * Returns the shared server to its delivered state after the last method.
     *
     * <p>The accrual launch below writes transactions and moves balances, and the two owned identities
     * live in the identity table, so every specification that runs after this one must find the delivered
     * rows and nothing else.
     *
     * @throws SQLException if the delivered state cannot be restored
     */
    @AfterAll
    static void restoreDeliveredState() throws SQLException {
        restoreSeededState();
    }

    /**
     * Writes the two identities this specification owns, immediately before every method.
     *
     * <p>Writing rather than reusing is deliberate: a method that removed a row would otherwise change
     * what the next method finds. The digest was produced once at class initialisation, so this is two
     * cheap merges rather than two hashes.
     */
    @BeforeEach
    void writeOwnedIdentities() {
        writeOwnedIdentity(ADMIN_IDENTITY, ADMIN_ROLE_CODE);
        writeOwnedIdentity(ORDINARY_IDENTITY, ORDINARY_ROLE_CODE);
    }

    // =================================================================================================
    // HELPERS - IDENTITY AND SESSION
    // =================================================================================================

    /**
     * Writes one owned identity through the shipped identity table.
     *
     * <p>Assembled through the shared factory, so the stored value is a digest by construction: the
     * entity refuses anything that is not shaped like one, which makes it impossible for this
     * specification to store a cleartext value even by accident.
     *
     * @param userId   the reserved identifier
     * @param roleCode the one-character role code to store
     */
    private void writeOwnedIdentity(final String userId, final String roleCode) {
        final UserSecurity identity = TestDataFactory.userSecurity()
                .userId(userId)
                .firstName(RESERVED_FIRST_NAME)
                .lastName(RESERVED_LAST_NAME)
                .userTypeCode(roleCode)
                .storedDigest(OWNED_DIGEST)
                .build();
        this.users.save(identity);
    }

    /**
     * Produces a digest of the folded contents of the class-path fixture's credential window.
     *
     * <p>The window is read by offset, folded with the same fold the shipped path applies, handed to the
     * shipped hashing service and then overwritten. Neither the window value nor its folded form is
     * returned, logged, printed or asserted on anywhere.
     *
     * @return a digest the shipped verifier accepts for a folded submission of the window value
     */
    private static String digestOfFoldedCredentialWindow() {
        final char[] window = TestDataFactory.fixtureCredentialWindow();
        try {
            return OWNED_DIGEST_SOURCE.encode(
                    TestDataFactory.asciiUpperFold(new String(window)));
        } finally {
            Arrays.fill(window, ' ');
        }
    }

    /**
     * Returns the contents of the credential window as an operator would key them.
     *
     * <p><strong>The return value stands in for a credential and is treated as one:</strong> it is passed
     * straight into a request document and is never logged, printed, named or asserted on.
     *
     * @return the folded contents of the fixture's credential window
     */
    private static String keyedCredential() {
        final char[] window = TestDataFactory.fixtureCredentialWindow();
        try {
            return TestDataFactory.asciiUpperFold(new String(window));
        } finally {
            Arrays.fill(window, ' ');
        }
    }

    /**
     * Signs on as the owned administrative identity, once per test instance.
     *
     * <p>A real sign-on rather than a fabricated session, so the entitlement this surface requires is one
     * the shipped path actually granted from a stored digest the shipped hashing service produced. The
     * verification is genuine work, so the result is held for the length of one method rather than
     * repeated for every request that method makes.
     *
     * @return the value the authorization header must carry, presentation prefix included
     * @throws Exception if the boundary cannot be reached
     */
    private String administrativeSession() throws Exception {
        if (this.administrativeSession == null) {
            this.administrativeSession = BEARER_PREFIX + sessionTokenFor(ADMIN_IDENTITY);
        }
        return this.administrativeSession;
    }

    /**
     * Signs on as the owned ordinary identity, once per test instance.
     *
     * @return the value the authorization header must carry, presentation prefix included
     * @throws Exception if the boundary cannot be reached
     */
    private String ordinarySession() throws Exception {
        if (this.ordinarySession == null) {
            this.ordinarySession = BEARER_PREFIX + sessionTokenFor(ORDINARY_IDENTITY);
        }
        return this.ordinarySession;
    }

    /**
     * Submits one sign-on turn and returns the session it was issued.
     *
     * @param  userId the owned identity to sign on as
     * @return the bare session token, without its presentation prefix
     * @throws Exception if the boundary cannot be reached
     */
    private String sessionTokenFor(final String userId) throws Exception {
        final Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        // The property name is the published contract type's own component name: a client speaks the name
        // the boundary declares, and inventing one would make this specification pass against a contract
        // nothing ships.
        payload.put("password", keyedCredential());
        payload.put("keyAction", KeyAction.ENTER.name());
        final MvcResult result = this.mockMvc.perform(
                        MockMvcRequestBuilders.post(AuthController.SIGN_ON_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .characterEncoding(StandardCharsets.UTF_8)
                                .accept(MediaType.APPLICATION_JSON)
                                .content(JSON.writeValueAsString(payload)))
                .andReturn();
        final String header = result.getResponse().getHeader(HttpHeaders.AUTHORIZATION);
        assertThat(header)
                .as("an admitted sign-on turn issues a session in the authorization header; %s was not "
                        + "admitted", userId)
                .isNotNull()
                .startsWith(BEARER_PREFIX);
        return header.substring(BEARER_PREFIX.length());
    }

    // =================================================================================================
    // HELPERS - THE TWO OPERATIONS
    // =================================================================================================

    /**
     * Submits one launch of one job with one parameter document, behind a given session.
     *
     * <p>The route is assembled from the boundary's own published constants and expanded by the request
     * builder, so the address this specification speaks to cannot drift from the one the boundary maps.
     *
     * @param  session   the value for the authorization header, or {@code null} to present none
     * @param  jobName   the name to address
     * @param  parameters the parameter document to submit
     * @return the completed exchange
     * @throws Exception if the boundary cannot be reached
     */
    private MvcResult launchWithSession(final String session, final String jobName,
            final Map<String, String> parameters) throws Exception {
        var request = MockMvcRequestBuilders.post(
                        BatchJobController.BATCH_JOBS_PATH + BatchJobController.LAUNCH_SUBPATH, jobName)
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding(StandardCharsets.UTF_8)
                .accept(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(parameters));
        if (session != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, session);
        }
        return this.mockMvc.perform(request).andReturn();
    }

    /**
     * Submits one launch behind the administrative session.
     *
     * @param  jobName    the name to address
     * @param  parameters the parameter document to submit
     * @return the completed exchange
     * @throws Exception if the boundary cannot be reached
     */
    private MvcResult launch(final String jobName, final Map<String, String> parameters)
            throws Exception {
        return launchWithSession(administrativeSession(), jobName, parameters);
    }

    /**
     * Submits one launch that must be admitted, and answers the execution identifier it was given.
     *
     * @param  jobName    the name to address
     * @param  parameters the parameter document to submit
     * @return the execution identifier the framework assigned
     * @throws Exception if the boundary cannot be reached
     */
    private long launchAdmitted(final String jobName, final Map<String, String> parameters)
            throws Exception {
        final MvcResult result = launch(jobName, parameters);
        assertThat(result.getResponse().getStatus())
                .as("an admitted launch of %s answers 200 and carries the execution it started", jobName)
                .isEqualTo(200);
        final JsonNode body = jsonOf(result);
        assertThat(textOf(body, "jobName"))
                .as("the answer names the stable job the launch was issued against")
                .isEqualTo(jobName);
        final JsonNode executionId = body.get("executionId");
        assertThat(executionId)
                .as("the answer carries the execution identifier a caller asks after with")
                .isNotNull();
        return executionId.asLong();
    }

    /**
     * Submits one launch that must be refused, and answers the summary the refusal carried.
     *
     * @param  jobName        the name to address
     * @param  parameters     the parameter document to submit
     * @param  expectedStatus the status the refusal must carry
     * @return the summary text of the refusal
     * @throws Exception if the boundary cannot be reached
     */
    private String launchRefused(final String jobName, final Map<String, String> parameters,
            final int expectedStatus) throws Exception {
        final MvcResult result = launch(jobName, parameters);
        assertThat(result.getResponse().getStatus())
                .as("the launch of %s with %s must be refused with %s", jobName, parameters.keySet(),
                        Integer.valueOf(expectedStatus))
                .isEqualTo(expectedStatus);
        assertNoInternalDisclosure(bodyOf(result));
        return textOf(jsonOf(result), "message");
    }

    /**
     * Reads one execution's status behind a given session.
     *
     * @param  session     the value for the authorization header, or {@code null} to present none
     * @param  executionId the execution identifier to ask after
     * @return the completed exchange
     * @throws Exception if the boundary cannot be reached
     */
    private MvcResult readStatusWithSession(final String session, final long executionId)
            throws Exception {
        var request = MockMvcRequestBuilders.get(
                        BatchJobController.BATCH_JOBS_PATH + BatchJobController.EXECUTION_SUBPATH,
                        Long.valueOf(executionId))
                .accept(MediaType.APPLICATION_JSON);
        if (session != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, session);
        }
        return this.mockMvc.perform(request).andReturn();
    }

    /**
     * Reads one execution's status behind the administrative session.
     *
     * @param  executionId the execution identifier to ask after
     * @return the completed exchange
     * @throws Exception if the boundary cannot be reached
     */
    private MvcResult readStatus(final long executionId) throws Exception {
        return readStatusWithSession(administrativeSession(), executionId);
    }

    /**
     * A parameter document carrying nothing: the launch of a job that declares no parameter.
     *
     * @return an empty parameter document
     */
    private static Map<String, String> noParameters() {
        return Map.of();
    }

    /**
     * Submits a request to one concrete address behind the administrative session.
     *
     * <p>Used only by the group that proves an address is <em>not</em> answered, which is why it takes a
     * whole address rather than composing one from a route template.
     *
     * @param  post    {@code true} to submit, {@code false} to read
     * @param  address the concrete address to reach
     * @return the completed exchange
     * @throws Exception if the boundary cannot be reached
     */
    private MvcResult probe(final boolean post, final String address) throws Exception {
        var request = post
                ? MockMvcRequestBuilders.post(address)
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding(StandardCharsets.UTF_8)
                        .content("{}")
                : MockMvcRequestBuilders.get(address);
        request = request.accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, administrativeSession());
        return this.mockMvc.perform(request).andReturn();
    }

    /**
     * Asserts that one concrete address is answered with one status and one neutral summary.
     *
     * <p>Applicable only where the refusal came out of a resolved operation, because the module's advice is
     * scoped to this package's controllers and therefore only renders a document when a controller was
     * reached. {@link #assertNoOperationAt} is the counterpart for an address that resolves to no operation
     * at all.
     *
     * @param  post    {@code true} to submit, {@code false} to read
     * @param  address the concrete address to reach
     * @param  status  the status the answer must carry
     * @param  summary the summary the answer must carry
     * @throws Exception if the boundary cannot be reached
     */
    private void assertAnswered(final boolean post, final String address, final int status,
            final String summary) throws Exception {
        final MvcResult result = probe(post, address);
        assertThat(result.getResponse().getStatus())
                .as("%s must answer %s", address, Integer.valueOf(status))
                .isEqualTo(status);
        final String body = bodyOf(result);
        assertNoInternalDisclosure(body);
        assertThat(textOf(JSON.readTree(body), "message"))
                .as("%s answers the neutral summary its status is called by", address)
                .isEqualTo(summary);
    }

    /**
     * Asserts that one concrete address resolves to no operation, and discloses nothing in saying so.
     *
     * <p><strong>The status is the whole of the evidence, and deliberately so.</strong> A refusal decided
     * before any operation is resolved never reaches this package's controllers, so the module's own advice
     * - which is scoped to them - does not render a document for it; the neutral summary such a refusal
     * carries in a deployed container is produced on the error dispatch, and what that dispatch publishes is
     * asserted by the specifications that own it rather than borrowed here. What this group claims is that
     * the address answers no operation, and the status is exactly that claim. Whatever document does
     * accompany it is still checked for disclosure, because an absent operation must not become a way to
     * learn what is present.
     *
     * @param  post    {@code true} to submit, {@code false} to read
     * @param  address the concrete address to reach
     * @param  status  the status the answer must carry
     * @throws Exception if the boundary cannot be reached
     */
    private void assertNoOperationAt(final boolean post, final String address, final int status)
            throws Exception {
        final MvcResult result = probe(post, address);
        assertThat(result.getResponse().getStatus())
                .as("%s resolves to no operation, so it must answer %s", address,
                        Integer.valueOf(status))
                .isEqualTo(status);
        assertNoInternalDisclosure(bodyOf(result));
        for (final String registered : this.jobRegistry.getJobNames()) {
            assertThat(bodyOf(result))
                    .as("an unmapped address must not become an inventory, and %s is part of that "
                            + "inventory", registered)
                    .doesNotContain(registered);
        }
    }

    // =================================================================================================
    // HELPERS - READING THE ANSWER
    // =================================================================================================

    /**
     * Reads a completed exchange's document as text.
     *
     * @param  result the completed exchange
     * @return the document, decoded as the boundary encoded it
     * @throws Exception if the document cannot be read
     */
    private static String bodyOf(final MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * Parses a completed exchange's document.
     *
     * @param  result the completed exchange
     * @return the parsed document
     * @throws Exception if the document cannot be read or parsed
     */
    private static JsonNode jsonOf(final MvcResult result) throws Exception {
        final String body = bodyOf(result);
        assertThat(body)
                .as("every answer of this surface carries the module's own document rather than an empty "
                        + "body")
                .isNotEmpty();
        return JSON.readTree(body);
    }

    /**
     * Reads one text member of a document, requiring it to be present.
     *
     * @param  document the parsed document
     * @param  member   the member name to read
     * @return the member's text
     */
    private static String textOf(final JsonNode document, final String member) {
        final JsonNode node = document.get(member);
        assertThat(node)
                .as("the published contract declares the %s member, so a document that omits it is a "
                        + "contract change rather than an empty value", member)
                .isNotNull();
        return node.asText();
    }

    /**
     * Asserts that a refusal document discloses nothing about the condition that produced it.
     *
     * <p>Six families are checked, each of which a framework or driver message would have carried: a
     * package name from the framework, a package name from this module, the name of a failure type, a
     * chained-cause marker, a source-file name, and the name of a metadata table or a query verb.
     *
     * @param body the refusal document as text
     */
    private static void assertNoInternalDisclosure(final String body) {
        assertThat(body)
                .as("a refusal document discloses no internal detail: no framework package, no module "
                        + "package, no failure type, no chained cause, no source file and no query or "
                        + "metadata table")
                .doesNotContain("org.springframework")
                .doesNotContain("com.carddemo")
                .doesNotContain("Exception")
                .doesNotContain("Caused by")
                .doesNotContain(".java")
                .doesNotContain("batch_job")
                .doesNotContain("SELECT");
    }

    // =================================================================================================
    // HELPERS - READING THE FRAMEWORK'S OWN METADATA
    // =================================================================================================

    /**
     * Counts every recorded execution of the nine launchable jobs.
     *
     * <p>Nine small parameter-bound reads rather than one assembled statement, so no value is ever placed
     * into a query. Read-only throughout.
     *
     * @return the total number of recorded executions across the nine names
     * @throws SQLException if the metadata cannot be read
     */
    private static long recordedExecutionsOfTheNineJobs() throws SQLException {
        if (!batchMetadataPresent()) {
            // The framework provisions its own metadata the first time a context carrying the batch
            // infrastructure starts. No table means no recorded execution, which is the baseline of zero
            // rather than an error.
            return 0L;
        }
        long total = 0L;
        try (Connection connection = connect();
                PreparedStatement count = connection.prepareStatement(EXECUTION_COUNT_SQL)) {
            for (final String jobName : NINE_LAUNCHABLE_JOB_NAMES) {
                count.setString(1, jobName);
                try (ResultSet rows = count.executeQuery()) {
                    if (rows.next()) {
                        total += rows.getLong(1);
                    }
                }
            }
        }
        return total;
    }

    /**
     * Counts every recorded execution of one named job.
     *
     * @param  jobName the stable job name to count executions of
     * @return the number of recorded executions of that job
     * @throws SQLException if the metadata cannot be read
     */
    private static long recordedExecutionsOf(final String jobName) throws SQLException {
        if (!batchMetadataPresent()) {
            return 0L;
        }
        try (Connection connection = connect();
                PreparedStatement count = connection.prepareStatement(EXECUTION_COUNT_SQL)) {
            count.setString(1, jobName);
            try (ResultSet rows = count.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0L;
            }
        }
    }

    /**
     * Reports whether the framework's own metadata tables exist on the shared server yet.
     *
     * @return {@code true} once the framework has provisioned them
     * @throws SQLException if the catalogue cannot be read
     */
    private static boolean batchMetadataPresent() throws SQLException {
        try (Connection connection = connect();
                PreparedStatement present = connection.prepareStatement(METADATA_PRESENT_SQL);
                ResultSet rows = present.executeQuery()) {
            return rows.next() && rows.getLong(1) > 0L;
        }
    }

    /**
     * Reads one execution out of the framework's own metadata, requiring it to be held.
     *
     * @param  executionId the execution identifier a launch answered with
     * @return the recorded execution
     */
    private JobExecution recordedExecution(final long executionId) {
        final JobExecution execution = this.jobExplorer.getJobExecution(executionId);
        assertThat(execution)
                .as("the framework's own metadata is the single authority on what ran, so an execution "
                        + "the launch answered with must be held there")
                .isNotNull();
        return execution;
    }

    /**
     * Reads the parameter names one execution was recorded with.
     *
     * @param  executionId the execution identifier a launch answered with
     * @return every parameter name the framework recorded, in no particular order
     */
    private Set<String> recordedParameterNames(final long executionId) {
        return recordedExecution(executionId).getJobParameters().getParameters().keySet();
    }

    /**
     * Reads the value one execution was recorded with, under one parameter name.
     *
     * @param  executionId the execution identifier a launch answered with
     * @param  name        the parameter name to read
     * @return the recorded value as the framework holds it
     */
    private String recordedParameterValue(final long executionId, final String name) {
        return recordedExecution(executionId).getJobParameters().getString(name);
    }

    /**
     * Reads the server-minted run identity one execution was recorded with.
     *
     * @param  executionId the execution identifier a launch answered with
     * @return the run identity, which the launch guard requires to be a positive whole number
     */
    private Long recordedRunIdentity(final long executionId) {
        final Long runIdentity =
                recordedExecution(executionId).getJobParameters().getLong(SERVER_RUN_IDENTITY_KEY);
        assertThat(runIdentity)
                .as("the one key the server contributes is a whole number, which is what makes it a "
                        + "counter over stored metadata rather than a clock reading or a random value")
                .isNotNull();
        return runIdentity;
    }

    /**
     * Reads the job instance one execution belongs to.
     *
     * @param  executionId the execution identifier a launch answered with
     * @return the instance identifier the framework recorded
     */
    private Long recordedInstanceId(final long executionId) {
        return recordedExecution(executionId).getJobInstance().getInstanceId();
    }

    // =================================================================================================
    // GROUP 1 - NOTHING RUNS BECAUSE A CONTEXT STARTED
    // =================================================================================================

    /**
     * The invariant every other group depends on: launch-on-start is off, so this surface is the only way
     * work begins.
     *
     * <p>Ordered first among the groups because its first assertion compares against a figure taken
     * before the application context existed, and any launch anywhere would move it.
     */
    @Nested
    @Order(1)
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    @DisplayName("No auto-run at start-up: the shipped configuration disables it, so nothing runs until "
            + "this surface is called")
    class NoAutoRunAtStartUp {

        /** Creates the nested specification. */
        NoAutoRunAtStartUp() {
            // Intentionally empty: the enclosing instance holds every collaborator.
        }

        @Test
        @Order(1)
        @DisplayName("starting the application recorded no execution of any of the nine jobs")
        void startingTheApplicationRecordedNoExecution() throws SQLException {
            // The estate had no resident process to start: online work was reached through the eighteen
            // registered transactions and batch work by submitting a job member. Nothing therefore ran
            // because something came up, and the migration keeps that exactly.
            assertThat(recordedExecutionsOfTheNineJobs())
                    .as("the count taken before this context existed and the count taken after it started "
                            + "must be the same figure; a difference means a job ran as a side effect of a "
                            + "refresh")
                    .isEqualTo(executionsBeforeContextStarted);
        }

        @Test
        @Order(2)
        @DisplayName("the shipped configuration document resolves auto-run to false and names no job for "
                + "a start-up runner to resolve")
        void theShippedConfigurationDisablesAutoRun() {
            // Deliberately NOT declared on this specification's own annotation. Asserting a value this
            // file had just set would prove only that the file agrees with itself; read from the resolved
            // environment, it is an assertion about what the module ships.
            assertThat(environment.getProperty(BATCH_AUTO_RUN_PROPERTY))
                    .as("%s is the setting that decides whether work runs as a side effect of a refresh, "
                            + "and the shipped profile closes it", BATCH_AUTO_RUN_PROPERTY)
                    .isEqualTo(AUTO_RUN_DISABLED);
            assertThat(environment.getProperty(BATCH_AUTO_RUN_JOB_NAME_PROPERTY))
                    .as("%s is the other half of auto-run: with no job named there is nothing for a "
                            + "start-up runner to resolve", BATCH_AUTO_RUN_JOB_NAME_PROPERTY)
                    .isNull();
        }

        @Test
        @Order(3)
        @DisplayName("the context holds no start-up runner at all, which is the mechanism rather than the "
                + "symptom")
        void theContextHoldsNoStartUpRunner() {
            // The framework's launch-on-start participant is a start-up runner, and with the setting above
            // closed it is never registered. Asserting the type is absent proves the mechanism; the count
            // assertion above covers a participant of any other kind, including one this module might
            // have contributed itself. This module contributes none, and this specification introduces
            // none.
            assertThat(applicationContext.getBeanNamesForType(ApplicationRunner.class))
                    .as("a start-up runner is the only thing that can launch work without a request, so "
                            + "the delivered graph holds none")
                    .isEmpty();
        }

        @Test
        @Order(4)
        @DisplayName("no execution of any of the nine jobs is left in flight, so nothing was started and "
                + "abandoned")
        void noExecutionOfTheNineIsInFlight() {
            for (final String jobName : NINE_LAUNCHABLE_JOB_NAMES) {
                assertThat(jobExplorer.findRunningJobExecutions(jobName))
                        .as("%s has nothing running: a refresh started nothing, and the launch guard "
                                + "refuses an overlapping start", jobName)
                        .isEmpty();
            }
        }

        @Test
        @Order(5)
        @DisplayName("one launch call creates exactly one execution - never none and never two")
        void oneLaunchCallCreatesExactlyOneExecution() throws Exception {
            // The probe job is the one that reads through the repositories and touches nothing else, so
            // the count moves by exactly the launch and by nothing the launch caused.
            final long before = recordedExecutionsOfTheNineJobs();

            final long executionId = launchAdmitted(FILE_PROBE_JOB,
                    Map.of(FILE_PROBE_MODE_KEY, LEGAL_PROBE_MODES.get(0)));

            assertThat(recordedExecutionsOfTheNineJobs())
                    .as("a launch is one execution: the guard reserves one, runs it, and answers with its "
                            + "identifier")
                    .isEqualTo(before + 1L);
            assertThat(Long.valueOf(executionId))
                    .as("the identifier answered is the one the framework assigned, so it is positive")
                    .isGreaterThan(Long.valueOf(0L));
        }
    }

    // =================================================================================================
    // GROUP 2 - THE CLOSED INVENTORY OF NINE
    // =================================================================================================

    /**
     * The inventory of launchable work is closed at nine, and closed is the security property.
     *
     * <p>Without the closure the launch path would be "start whatever the caller names", and the
     * framework's registry holds whatever it was given. The list this group asserts against was authored
     * from the estate rather than read from the inventory the boundary publishes.
     */
    @Nested
    @Order(2)
    @DisplayName("Nine-job closed allow-list: exactly nine names, no tenth, and nothing the absorbed "
            + "utility steps or the unmigrated members would have named")
    class NineJobClosedAllowList {

        /** Creates the nested specification. */
        NineJobClosedAllowList() {
            // Intentionally empty: the enclosing instance holds every collaborator.
        }

        @Test
        @DisplayName("the authored expectation is itself nine names, all distinct")
        void theAuthoredExpectationIsNineDistinctNames() {
            assertThat(NINE_LAUNCHABLE_JOB_NAMES)
                    .as("the figure is stated once and the list is checked against it, so a name added "
                            + "here without a matching count change fails immediately")
                    .hasSize(LAUNCHABLE_JOB_COUNT)
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the delivered application registers exactly those nine jobs and no tenth")
        void theApplicationRegistersExactlyThoseNineJobs() {
            // This is the assertion a sliced graph cannot make, and the reason this specification starts
            // the whole application: the registry holds what the application registered rather than what
            // a test named.
            assertThat(jobRegistry.getJobNames())
                    .as("nine of the seventy-eight program-execution steps invoked an application "
                            + "program, so nine job configurations is the measured answer and a tenth "
                            + "would be an invented one")
                    .containsExactlyInAnyOrderElementsOf(NINE_LAUNCHABLE_JOB_NAMES)
                    .hasSize(LAUNCHABLE_JOB_COUNT);
        }

        @Test
        @DisplayName("the boundary's own gate names the same nine, so the gate and the registry cannot "
                + "disagree")
        void theBoundaryGateNamesTheSameNine() {
            assertThat(BatchJobController.LAUNCHABLE_JOB_NAMES)
                    .as("the gate is read from one declaring authority and the registry is populated from "
                            + "the same one, so a renamed job moves both ends together")
                    .containsExactlyInAnyOrderElementsOf(NINE_LAUNCHABLE_JOB_NAMES)
                    .hasSize(LAUNCHABLE_JOB_COUNT);
        }

        @Test
        @DisplayName("every one of the nine resolves through the framework's registry under its own name")
        void everyOneOfTheNineResolvesUnderItsOwnName() throws Exception {
            for (final String jobName : NINE_LAUNCHABLE_JOB_NAMES) {
                assertThat(jobRegistry.getJob(jobName).getName())
                        .as("an allow-listed name that did not resolve would be a wiring fault rather "
                                + "than a caller fault, and %s resolves to itself", jobName)
                        .isEqualTo(jobName);
            }
        }

        @Test
        @DisplayName("none of the six absorbed utility programs is launchable, because none of them was "
                + "an application program")
        void noAbsorbedUtilityProgramIsLaunchable() throws Exception {
            // Fifty-two data-set utility steps, eight display steps, five ordering steps, three
            // no-operation steps, one copy step and one resource-definition step. Every one became a
            // migration, a comparator inside the job that needs it, or a local service definition.
            for (final String utility : ABSORBED_UTILITY_PROGRAMS) {
                assertThat(launchRefused(utility, noParameters(), 404))
                        .as("%s named a utility rather than an application program, so it is not work "
                                + "this surface starts", utility)
                        .isEqualTo(RECORD_NOT_FOUND_MESSAGE);
            }
        }

        @Test
        @DisplayName("none of the three intentionally unmigrated members is launchable")
        void noIntentionallyUnmigratedMemberIsLaunchable() throws Exception {
            // Two toggled the availability of an online file and the third drove the resource-definition
            // utility. Once the indexed files are replaced by tables there is nothing for either to do,
            // which is why they are recorded as intentionally unmigrated rather than quietly dropped.
            for (final String member : INTENTIONALLY_UNMIGRATED_MEMBERS) {
                assertThat(launchRefused(member, noParameters(), 404))
                        .as("%s has no runtime equivalent, so no route and no launchable name exists for "
                                + "it", member)
                        .isEqualTo(RECORD_NOT_FOUND_MESSAGE);
            }
        }

        @Test
        @DisplayName("a tenth name that reads exactly like one of the nine is still refused")
        void aTenthNameThatReadsLikeTheOthersIsStillRefused() throws Exception {
            // Plausibility is not membership. Each of these follows the module's own naming convention
            // and names work the estate does not contain.
            for (final String plausible
                    : List.of("statementReportJob", "cardFileJob", "closeFileJob", "postTransactionsJob")) {
                assertThat(launchRefused(plausible, noParameters(), 404))
                        .as("the inventory is a fixed set of nine operations and not a naming convention, "
                                + "so %s is absent", plausible)
                        .isEqualTo(RECORD_NOT_FOUND_MESSAGE);
            }
        }
    }

    // =================================================================================================
    // GROUP 3 - A NAME OUTSIDE THE NINE DISCLOSES NOTHING
    // =================================================================================================

    /**
     * A refusal is an answer and not a diagnostic channel.
     *
     * <p>The refused name reaches no framework call, writes no metadata, and is echoed nowhere - not in
     * the summary, not in a per-field entry, and not in a chained failure. The inventory is the thing a
     * caller must not be able to enumerate, because enumerating it is the first step of using it.
     */
    @Nested
    @Order(3)
    @DisplayName("Unknown job rejected without leakage: an absent resource, one neutral summary, and no "
            + "inventory to enumerate")
    class UnknownJobRejectedWithoutLeakage {

        /** Creates the nested specification. */
        UnknownJobRejectedWithoutLeakage() {
            // Intentionally empty: the enclosing instance holds every collaborator.
        }

        @Test
        @DisplayName("an unknown name is answered as an absent resource with the one neutral summary")
        void anUnknownNameIsAnAbsentResource() throws Exception {
            assertThat(launchRefused("noSuchJob", noParameters(), 404))
                    .as("the summary is fixed and names no record type, no key and no resource")
                    .isEqualTo(RECORD_NOT_FOUND_MESSAGE);
        }

        @Test
        @DisplayName("the refusal does not echo the name that was refused, so a caller learns nothing "
                + "from its own input coming back")
        void theRefusalDoesNotEchoTheNameThatWasRefused() throws Exception {
            final String probe = "aVeryDistinctiveAbsentJobName";

            final MvcResult result = launch(probe, noParameters());

            assertThat(result.getResponse().getStatus()).isEqualTo(404);
            assertThat(bodyOf(result))
                    .as("an answer that repeated the value back would confirm which spellings the surface "
                            + "recognises by the shape of what it repeats")
                    .doesNotContain(probe);
        }

        @Test
        @DisplayName("the refusal discloses no bean name, no type, no failure chain and no location")
        void theRefusalDisclosesNothingInternal() throws Exception {
            final MvcResult result = launch("notAJob", noParameters());

            final String body = bodyOf(result);
            assertNoInternalDisclosure(body);
            for (final String registered : jobRegistry.getJobNames()) {
                assertThat(body)
                        .as("the registry's contents are exactly what a refusal must not reveal, and %s is "
                                + "one of them", registered)
                        .doesNotContain(registered);
            }
        }

        @Test
        @DisplayName("a name differing from an allow-listed one only by letter case is refused, because "
                + "the gate matches exactly")
        void aNameDifferingOnlyByCaseIsRefused() throws Exception {
            // The gate is an exact set membership test rather than a normalising lookup. A near miss is a
            // caller error worth reporting rather than something to guess at.
            final String misCased = FILE_PROBE_JOB.toUpperCase(Locale.ROOT);

            assertThat(launchRefused(misCased, noParameters(), 404))
                    .as("%s is not %s, and the gate does not fold either of them", misCased, FILE_PROBE_JOB)
                    .isEqualTo(RECORD_NOT_FOUND_MESSAGE);
        }

        @Test
        @DisplayName("a refused launch records no execution, so the refusal reached no framework call")
        void aRefusedLaunchRecordsNoExecution() throws Exception {
            final long before = recordedExecutionsOfTheNineJobs();

            launchRefused("stillNotAJob", noParameters(), 404);

            assertThat(recordedExecutionsOfTheNineJobs())
                    .as("the gate sits ahead of the registry and ahead of the launch guard, so a refused "
                            + "name writes nothing at all")
                    .isEqualTo(before);
        }
    }

    // =================================================================================================
    // GROUP 4 - THE RECORDED ORPHAN
    // =================================================================================================

    /**
     * The daily-transaction extract: defined, launchable by name, and part of no sequence.
     *
     * <p>It translates a complete four-hundred-and-ninety-one line program that no job member, cataloged
     * procedure or online resource definition invokes - verified by searching the whole job, procedure and
     * resource-definition tree for its name and finding nothing. Migrating it as a registered job keeps it
     * exercisable rather than letting it become dead code, and keeping it out of every sequence keeps the
     * migration honest about what the estate actually wired.
     */
    @Nested
    @Order(4)
    @DisplayName("Orphan daily-transaction job launchable by name but sequenced nowhere: both halves of a "
            + "recorded decision")
    class OrphanJobLaunchableByNameAndSequencedNowhere {

        /** Creates the nested specification. */
        OrphanJobLaunchableByNameAndSequencedNowhere() {
            // Intentionally empty: the enclosing instance holds every collaborator.
        }

        @Test
        @DisplayName("the orphan is one of the nine the application registers, so it did not become dead "
                + "code")
        void theOrphanIsOneOfTheNineRegistered() {
            assertThat(jobRegistry.getJobNames())
                    .as("the whole point of registering a job no job member invoked is that it stays "
                            + "reachable; dropping it would have removed the coverage with the code")
                    .contains(ORPHAN_JOB);
            assertThat(BatchJobController.LAUNCHABLE_JOB_NAMES)
                    .as("and the boundary's gate admits it, which is what makes it launchable by a test "
                            + "as well as by an operator")
                    .contains(ORPHAN_JOB);
        }

        @Test
        @DisplayName("launching the orphan by name succeeds and produces an execution")
        void launchingTheOrphanByNameProducesAnExecution() throws Exception {
            // It declares no parameter, because the program it translates read its input from data
            // definitions rather than from a submitted value.
            final long executionId = launchAdmitted(ORPHAN_JOB, noParameters());

            assertThat(recordedExecution(executionId).getJobInstance().getJobName())
                    .as("the execution belongs to the orphan's own instance rather than to some other "
                            + "job's")
                    .isEqualTo(ORPHAN_JOB);
        }

        @Test
        @DisplayName("launching another job does not start the orphan, because nothing chains to it")
        void launchingAnotherJobDoesNotStartTheOrphan() throws Exception {
            final long orphanExecutionsBefore = recordedExecutionsOf(ORPHAN_JOB);

            launchAdmitted(FILE_PROBE_JOB, Map.of(FILE_PROBE_MODE_KEY, LEGAL_PROBE_MODES.get(1)));

            assertThat(recordedExecutionsOf(ORPHAN_JOB))
                    .as("the estate held no orchestrator, so a launch chains to nothing and the orphan in "
                            + "particular is never selected implicitly")
                    .isEqualTo(orphanExecutionsBefore);
        }

        @Test
        @DisplayName("the orphan accepts no parameter, so it cannot be repurposed through one")
        void theOrphanAcceptsNoParameter() throws Exception {
            assertThat(launchRefused(ORPHAN_JOB, Map.of(FILE_PROBE_MODE_KEY, LEGAL_PROBE_MODES.get(0)),
                            400))
                    .as("an empty declared set refuses every name, which is what stops a caller minting a "
                            + "distinct identity of a job that was deliberately already run")
                    .isEqualTo(UNDECLARED_PARAMETER_MESSAGE);
        }
    }

    // =================================================================================================
    // GROUP 5 - THE REPEAT CONTRACT
    // =================================================================================================

    /**
     * A launch records the caller's own parameters plus exactly one server-minted run identity.
     *
     * <p>This is the group the class documentation develops at length, and it is where a convenience
     * shortcut would be most tempting and most damaging. The assertions below separate the two things
     * that look alike: a monotonic run identity minted from stored metadata, which is the faithful
     * translation of a job member that could simply be submitted again, and a wall-clock or random
     * uniquifier, which would be a shortcut and is proven absent.
     */
    @Nested
    @Order(5)
    @DisplayName("Repeatable launch: the caller's parameters byte for byte plus one server-minted run "
            + "identity, and no timestamp, identifier or random uniquifier anywhere")
    class RepeatableLaunchWithoutAnInjectedUniquifier {

        /** Creates the nested specification. */
        RepeatableLaunchWithoutAnInjectedUniquifier() {
            // Intentionally empty: the enclosing instance holds every collaborator.
        }

        @Test
        @DisplayName("the recorded parameters are exactly what was submitted plus one further key")
        void theRecordedParametersAreTheSubmittedOnesPlusOneFurtherKey() throws Exception {
            final Map<String, String> submitted =
                    Map.of(FILE_PROBE_MODE_KEY, LEGAL_PROBE_MODES.get(2));

            final long executionId = launchAdmitted(FILE_PROBE_JOB, submitted);

            assertThat(recordedParameterNames(executionId))
                    .as("one key beyond the submitted set, and that key is the framework's own run "
                            + "identifier")
                    .containsExactlyInAnyOrder(FILE_PROBE_MODE_KEY, SERVER_RUN_IDENTITY_KEY);
        }

        @Test
        @DisplayName("no timestamp, unique-identifier, random or current-date key is recorded")
        void noTimestampIdentifierOrRandomKeyIsRecorded() throws Exception {
            final long executionId =
                    launchAdmitted(FILE_PROBE_JOB, Map.of(FILE_PROBE_MODE_KEY, LEGAL_PROBE_MODES.get(3)));

            // Every shape a convenience uniquifier takes, checked by name rather than by count, so a
            // second injected key would be reported for what it is rather than only as an arity change.
            for (final String recorded : recordedParameterNames(executionId)) {
                final String folded = recorded.toLowerCase(Locale.ROOT);
                assertThat(folded)
                        .as("%s must not be a clock reading, a generated identifier or a random value: "
                                + "those make every submission a new identity and turn 'run this' into "
                                + "'run it again' without saying so", recorded)
                        .doesNotContain("time")
                        .doesNotContain("stamp")
                        .doesNotContain("uuid")
                        .doesNotContain("guid")
                        .doesNotContain("random")
                        .doesNotContain("nano")
                        .doesNotContain("milli")
                        .doesNotContain("clock")
                        .doesNotContain("now");
            }
        }

        @Test
        @DisplayName("the submitted value is recorded byte for byte, neither trimmed, padded nor "
                + "reformatted")
        void theSubmittedValueIsRecordedByteForByte() throws Exception {
            // Non-negotiable for two of the four declared parameters: the accrual value becomes the literal
            // leading characters of every identifier that run synthesises, and the report window is read as
            // a fixed-width layout, so a reformatted value would change output compared byte for byte.
            final long executionId =
                    launchAdmitted(INTEREST_JOB, Map.of(INTEREST_PARM_DATE_KEY, VALID_INTEREST_PARM_DATE));

            assertThat(recordedParameterValue(executionId, INTEREST_PARM_DATE_KEY))
                    .as("the value arrives, is screened, and is handed on unchanged")
                    .isEqualTo(VALID_INTEREST_PARM_DATE)
                    .hasSize(VALID_INTEREST_PARM_DATE.length());
        }

        @Test
        @DisplayName("the same job with the same parameters twice starts two distinct instances rather "
                + "than being answered out of metadata")
        void theSameParametersTwiceStartTwoDistinctInstances() throws Exception {
            // The faithful reading. A job member submitted again with an identical parameter set simply ran
            // again - the posting job on the same processing date, the accrual run with the same run date,
            // the backup after a failed cycle. Refusing the second submission would be a behavioural
            // regression dressed up as an idempotency guarantee.
            final Map<String, String> submitted =
                    Map.of(FILE_PROBE_MODE_KEY, LEGAL_PROBE_MODES.get(0));

            final long first = launchAdmitted(FILE_PROBE_JOB, submitted);
            final long second = launchAdmitted(FILE_PROBE_JOB, submitted);

            assertThat(Long.valueOf(second))
                    .as("two submissions, two executions")
                    .isNotEqualTo(Long.valueOf(first));
            assertThat(recordedInstanceId(second))
                    .as("and two instances, because the identity moved on rather than being reused")
                    .isNotEqualTo(recordedInstanceId(first));
            assertThat(recordedParameterValue(second, FILE_PROBE_MODE_KEY))
                    .as("the caller's own value is untouched by the identity moving on")
                    .isEqualTo(recordedParameterValue(first, FILE_PROBE_MODE_KEY));
        }

        @Test
        @DisplayName("the server-minted run identity advances by exactly one, which a clock or a random "
                + "source could not do")
        void theServerMintedRunIdentityAdvancesByExactlyOne() throws Exception {
            final Map<String, String> submitted =
                    Map.of(FILE_PROBE_MODE_KEY, LEGAL_PROBE_MODES.get(1));

            final long first = launchAdmitted(FILE_PROBE_JOB, submitted);
            final long second = launchAdmitted(FILE_PROBE_JOB, submitted);

            final Long firstIdentity = recordedRunIdentity(first);
            final Long secondIdentity = recordedRunIdentity(second);
            assertThat(firstIdentity)
                    .as("the guard refuses anything that is not a positive whole number")
                    .isGreaterThanOrEqualTo(Long.valueOf(1L));
            assertThat(secondIdentity)
                    .as("a counter seeded from the previous instance's recorded parameters advances by one; "
                            + "a clock reading would jump by the elapsed interval and a random value would "
                            + "not be ordered at all")
                    .isEqualTo(Long.valueOf(firstIdentity.longValue() + 1L));
        }

        @Test
        @DisplayName("a caller cannot even name the server-minted key on the wire, so it can neither be "
                + "pinned nor collided with")
        void aCallerCannotSupplyTheServerMintedKey() throws Exception {
            // The typed request declares four properties and refuses an unknown one rather than ignoring
            // it, so the run identity is not merely outside the addressed job's declared set - it is
            // outside the wire vocabulary altogether, and the refusal happens before the document is even
            // bound. That is what keeps the identity the server's to mint.
            final long before = recordedExecutionsOfTheNineJobs();

            assertThat(launchRefused(FILE_PROBE_JOB,
                            Map.of(FILE_PROBE_MODE_KEY, LEGAL_PROBE_MODES.get(0),
                                    SERVER_RUN_IDENTITY_KEY, "1"), 400))
                    .as("a caller that could pin the run identity could freeze the surface at one instance "
                            + "per parameter set")
                    .isEqualTo(MALFORMED_REQUEST_BODY_MESSAGE);
            assertThat(recordedExecutionsOfTheNineJobs())
                    .as("and the refusal reached no framework call, so nothing was recorded")
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("a job that declares no parameter refuses every name, so no identity can be minted "
                + "through one")
        void aJobThatDeclaresNoParameterRefusesEveryName() throws Exception {
            // Six of the nine declare an empty set, and the emptiness is deliberate rather than missing: a
            // job left out of the schema entirely would be indistinguishable from an oversight. A caller
            // free to add a name the job never reads could mint an unbounded number of distinct identities
            // for the same work, which is the same defeat the closed name gate exists to prevent.
            final long before = recordedExecutionsOfTheNineJobs();

            assertThat(launchRefused(NO_PARAMETER_JOB,
                            Map.of(INTEREST_PARM_DATE_KEY, VALID_INTEREST_PARM_DATE), 400))
                    .as("the name is declared by another job and is still refused here, because the schema "
                            + "is per job rather than module-wide")
                    .isEqualTo(UNDECLARED_PARAMETER_MESSAGE);
            assertThat(recordedExecutionsOfTheNineJobs())
                    .as("and the screen sits ahead of the launch guard, so nothing was started")
                    .isEqualTo(before);
        }
    }

    // =================================================================================================
    // GROUP 6 - THE ACCRUAL PARAMETER SHAPE
    // =================================================================================================

    /**
     * The accrual run's one parameter: exactly ten characters, every one a digit.
     *
     * <p>The measured value the job member supplied is an eight-digit calendar date followed by two
     * further digits filling the remaining positions of the ten-character field the program receives it
     * into. It carries no separators and is not a hyphenated date. Its width is exact rather than a
     * maximum, because the ten characters are reused verbatim as the leading characters of every
     * sixteen-character transaction identifier the run synthesises.
     *
     * <p>The rules belong to the job's own validator, which runs inside the framework's launch; this group
     * asserts what a caller of this boundary observes when the validator refuses.
     */
    @Nested
    @Order(6)
    @DisplayName("Accrual parameter shape: exactly ten digits accepted, nine refused, eleven refused, a "
            + "non-digit refused")
    class AccrualParameterShape {

        /** Creates the nested specification. */
        AccrualParameterShape() {
            // Intentionally empty: the enclosing instance holds every collaborator.
        }

        @Test
        @DisplayName("the ten-character all-digit value is accepted and reaches the run unchanged")
        void theTenCharacterAllDigitValueIsAccepted() throws Exception {
            final long executionId =
                    launchAdmitted(INTEREST_JOB, Map.of(INTEREST_PARM_DATE_KEY, VALID_INTEREST_PARM_DATE));

            assertThat(recordedParameterValue(executionId, INTEREST_PARM_DATE_KEY))
                    .as("ten characters, and the same ten the caller submitted")
                    .isEqualTo(VALID_INTEREST_PARM_DATE);
        }

        @Test
        @DisplayName("a nine-character value is refused, because the width is exact and not a maximum")
        void aNineCharacterValueIsRefused() throws Exception {
            assertThat(launchRefused(INTEREST_JOB,
                            Map.of(INTEREST_PARM_DATE_KEY, NINE_CHARACTER_INTEREST_PARM_DATE), 400))
                    .as("a short value would still parse as a calendar date and would then produce "
                            + "identifiers one character narrower than every other run's")
                    .isEqualTo(PARAMETERS_INVALID_MESSAGE);
        }

        @Test
        @DisplayName("an eleven-character value is refused at the published contract's own width bound")
        void anElevenCharacterValueIsRefused() throws Exception {
            // Refused by the declarative width on the typed request, before the job's validator is
            // reached - two independent bounds on the same value, which is the point.
            assertThat(launchRefused(INTEREST_JOB,
                            Map.of(INTEREST_PARM_DATE_KEY, ELEVEN_CHARACTER_INTEREST_PARM_DATE), 400))
                    .as("the transport bound and the job's own validator both name ten, so an over-long "
                            + "value cannot slip past either")
                    .isEqualTo(DECLARATIVE_VALIDATION_MESSAGE);
        }

        @Test
        @DisplayName("a value of the right width carrying a non-digit is refused")
        void aNonDigitValueIsRefused() throws Exception {
            // The hyphenated form is the trap: it is the same width and it is a perfectly good date in the
            // OTHER of the module's two date contracts. Accepting it here would put separators into every
            // synthesised identifier.
            assertThat(launchRefused(INTEREST_JOB,
                            Map.of(INTEREST_PARM_DATE_KEY, NON_DIGIT_INTEREST_PARM_DATE), 400))
                    .as("ten characters is necessary and not sufficient: every one of them must be a digit")
                    .isEqualTo(DECLARATIVE_VALIDATION_MESSAGE);
        }

        @Test
        @DisplayName("ten digits naming no day in any calendar are refused, because the date resolves "
                + "strictly")
        void tenDigitsNamingNoRealDayAreRefused() throws Exception {
            // Strict resolution rather than lenient normalisation: an impossible day fails instead of being
            // silently rolled forward into the following month.
            assertThat(launchRefused(INTEREST_JOB,
                            Map.of(INTEREST_PARM_DATE_KEY, IMPOSSIBLE_CALENDAR_INTEREST_PARM_DATE), 400))
                    .as("a lenient reading would accept this and run the accrual under a date that does "
                            + "not exist")
                    .isEqualTo(PARAMETERS_INVALID_MESSAGE);
        }

        @Test
        @DisplayName("the accrual job refuses a launch carrying no parameter at all")
        void theAccrualJobRefusesALaunchCarryingNoParameter() throws Exception {
            assertThat(launchRefused(INTEREST_JOB, noParameters(), 400))
                    .as("the parameter is required rather than defaulted, because a default would run the "
                            + "accrual under a date nobody chose")
                    .isEqualTo(PARAMETERS_INVALID_MESSAGE);
        }
    }

    // =================================================================================================
    // GROUP 7 - THE REPORT WINDOW
    // =================================================================================================

    /**
     * The reporting window: two ten-character hyphenated dates, both bounds inclusive.
     *
     * <p>Inclusivity is measured rather than chosen. The ordering utility tests the record's
     * processing-date field with greater-or-equal against the start value and less-or-equal against the
     * end value, and the report program applies the same pair of inclusive comparisons, so a window whose
     * start equals its end selects that one day rather than nothing. The submission-side layout is
     * twenty-one significant characters - ten, one separator, ten - which is why each bound is exactly ten
     * and neither may be widened.
     *
     * <p><strong>Measured caveat, and the reason no assertion here filters seeded data.</strong> Across
     * all three hundred records of the delivered daily-transaction fixture the twenty-six character
     * processing-timestamp field is entirely blank; only the original timestamp carries a value. A window
     * applied to the processing date therefore selects nothing from seeded data, and a test written that
     * way would appear to pass while filtering nothing. Record-level inclusion is asserted in the batch
     * tier against a record built for it; what this boundary can answer, and does answer below, is that a
     * single-day window is a legal window and that an inverted one is not.
     */
    @Nested
    @Order(7)
    @DisplayName("Report window inclusivity and rejection: a single-day window is legal, an inverted one "
            + "is not, and neither bound may be malformed or omitted")
    class ReportWindowInclusivityAndRejection {

        /** Creates the nested specification. */
        ReportWindowInclusivityAndRejection() {
            // Intentionally empty: the enclosing instance holds every collaborator.
        }

        @Test
        @DisplayName("the pinned window is accepted and both bounds reach the run unchanged")
        void thePinnedWindowIsAccepted() throws Exception {
            final String start = PINNED_WINDOW_START_DATE.toString();
            final String end = PINNED_WINDOW_END_DATE.toString();

            final long executionId = launchAdmitted(REPORT_JOB,
                    Map.of(REPORT_START_DATE_KEY, start, REPORT_END_DATE_KEY, end));

            assertThat(recordedParameterValue(executionId, REPORT_START_DATE_KEY))
                    .as("the lower bound is carried through as the fixed-width layout requires")
                    .isEqualTo(start);
            assertThat(recordedParameterValue(executionId, REPORT_END_DATE_KEY))
                    .as("and so is the upper bound")
                    .isEqualTo(end);
        }

        @Test
        @DisplayName("a window whose start equals its end is accepted, which is the inclusive reading of "
                + "both bounds")
        void aSingleDayWindowIsAccepted() throws Exception {
            // The boundary evidence available at this surface. Under an exclusive reading of either bound a
            // one-day window would select nothing and an ordering check written for it would have to refuse
            // start equal to end. It does not, because both comparisons are inclusive.
            final String oneDay = PINNED_BUSINESS_DATE.toString();

            final long executionId = launchAdmitted(REPORT_JOB,
                    Map.of(REPORT_START_DATE_KEY, oneDay, REPORT_END_DATE_KEY, oneDay));

            assertThat(recordedParameterValue(executionId, REPORT_START_DATE_KEY))
                    .as("one day is a window, and it is the day the caller named")
                    .isEqualTo(oneDay)
                    .isEqualTo(recordedParameterValue(executionId, REPORT_END_DATE_KEY));
        }

        @Test
        @DisplayName("an inverted window is refused, because the start may not follow the end")
        void anInvertedWindowIsRefused() throws Exception {
            assertThat(launchRefused(REPORT_JOB,
                            Map.of(REPORT_START_DATE_KEY, PINNED_WINDOW_END_DATE.toString(),
                                    REPORT_END_DATE_KEY, PINNED_WINDOW_START_DATE.toString()), 400))
                    .as("the ordering test is a character comparison, exactly as the legacy filter's was, "
                            + "and it refuses a window that cannot select anything")
                    .isEqualTo(PARAMETERS_INVALID_MESSAGE);
        }

        @Test
        @DisplayName("a bound naming no day in any calendar is refused, because the date resolves strictly")
        void aBoundNamingNoRealDayIsRefused() throws Exception {
            assertThat(launchRefused(REPORT_JOB,
                            Map.of(REPORT_START_DATE_KEY, PINNED_WINDOW_START_DATE.toString(),
                                    REPORT_END_DATE_KEY, IMPOSSIBLE_CALENDAR_DATE), 400))
                    .as("the shape is right and the day does not exist, so only a strict resolution can "
                            + "refuse it")
                    .isEqualTo(PARAMETERS_INVALID_MESSAGE);
        }

        @Test
        @DisplayName("a bound of the wrong shape is refused by the published contract before any job sees "
                + "it")
        void aBoundOfTheWrongShapeIsRefused() throws Exception {
            assertThat(launchRefused(REPORT_JOB,
                            Map.of(REPORT_START_DATE_KEY, MALFORMED_SHAPE_DATE,
                                    REPORT_END_DATE_KEY, PINNED_WINDOW_END_DATE.toString()), 400))
                    .as("the hyphenated shape is declared on the transport contract, so a value of another "
                            + "shape never reaches the window comparison at all")
                    .isEqualTo(DECLARATIVE_VALIDATION_MESSAGE);
        }

        @Test
        @DisplayName("half a window is refused: both bounds are required, and neither is defaulted")
        void halfAWindowIsRefused() throws Exception {
            assertThat(launchRefused(REPORT_JOB,
                            Map.of(REPORT_START_DATE_KEY, PINNED_WINDOW_START_DATE.toString()), 400))
                    .as("defaulting the missing bound would report a period the caller never asked for")
                    .isEqualTo(PARAMETERS_INVALID_MESSAGE);
            assertThat(launchRefused(REPORT_JOB,
                            Map.of(REPORT_END_DATE_KEY, PINNED_WINDOW_END_DATE.toString()), 400))
                    .as("and the same holds for the other bound")
                    .isEqualTo(PARAMETERS_INVALID_MESSAGE);
        }
    }

    // =================================================================================================
    // GROUP 8 - ONE PARAMETERISED PROBE JOB, NOT FOUR ROUTES
    // =================================================================================================

    /**
     * The four sequential-read verification streams collapse into one parameterised job.
     *
     * <p>Four legacy job members each read one file to verify it. The mode value therefore has no legacy
     * antecedent literal: the legacy distinction was the identity of the job member, not the content of a
     * parameter. One job with four legal modes is the translation, and four routes would have been a
     * transliteration of the job stream rather than of the work.
     */
    @Nested
    @Order(8)
    @DisplayName("File-probe mode parameterisation: one job, four legal modes through one route, an "
            + "unknown mode refused")
    class FileProbeModeParameterisation {

        /** Creates the nested specification. */
        FileProbeModeParameterisation() {
            // Intentionally empty: the enclosing instance holds every collaborator.
        }

        @Test
        @DisplayName("each of the four legal modes is accepted through the one route and is recorded "
                + "verbatim")
        void eachOfTheFourLegalModesIsAccepted() throws Exception {
            final List<Long> executionIds = new ArrayList<>();
            for (final String mode : LEGAL_PROBE_MODES) {
                final long executionId =
                        launchAdmitted(FILE_PROBE_JOB, Map.of(FILE_PROBE_MODE_KEY, mode));
                assertThat(recordedParameterValue(executionId, FILE_PROBE_MODE_KEY))
                        .as("the mode selects which file is read, so it is carried through exactly as it "
                                + "arrived")
                        .isEqualTo(mode);
                executionIds.add(Long.valueOf(executionId));
            }

            assertThat(executionIds)
                    .as("four modes, four executions, all distinct - one job answered all four rather than "
                            + "four routes answering one each")
                    .hasSize(LEGAL_PROBE_MODES.size())
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("a mode the job never declared is refused")
        void anUndeclaredModeIsRefused() throws Exception {
            assertThat(launchRefused(FILE_PROBE_JOB, Map.of(FILE_PROBE_MODE_KEY, UNKNOWN_PROBE_MODE), 400))
                    .as("the enumeration of readable files belongs to the probe job, and a value outside "
                            + "it selects a file this job has no business opening")
                    .isEqualTo(PARAMETERS_INVALID_MESSAGE);
        }

        @Test
        @DisplayName("matching is case sensitive, so a mode differing only by letter case is refused")
        void modeMatchingIsCaseSensitive() throws Exception {
            // The legal values are symbolic names rather than free text, so a near miss is a launch error
            // worth reporting rather than something to guess at.
            final String misCased = LEGAL_PROBE_MODES.get(0).toUpperCase(Locale.ROOT);

            assertThat(launchRefused(FILE_PROBE_JOB, Map.of(FILE_PROBE_MODE_KEY, misCased), 400))
                    .as("%s is not %s", misCased, LEGAL_PROBE_MODES.get(0))
                    .isEqualTo(PARAMETERS_INVALID_MESSAGE);
        }

        @Test
        @DisplayName("the probe job refuses a launch carrying no mode, because the mode is required")
        void theProbeJobRefusesALaunchCarryingNoMode() throws Exception {
            assertThat(launchRefused(FILE_PROBE_JOB, noParameters(), 400))
                    .as("without a mode there is no file to read, and defaulting one would verify a file "
                            + "nobody asked about")
                    .isEqualTo(PARAMETERS_INVALID_MESSAGE);
        }

        @Test
        @DisplayName("no per-file route exists, so the four legacy streams did not become four operations")
        void noPerFileRouteExists() throws Exception {
            // The four collapsed members named their own programs. None of those names is launchable, and
            // no route beneath the probe job names a file either.
            for (final String legacyProgram : List.of("CBACT01C", "CBACT02C", "CBCUS01C", "CBACT03C")) {
                assertThat(launchRefused(legacyProgram, noParameters(), 404))
                        .as("%s is the program one collapsed stream ran, not a job this surface starts",
                                legacyProgram)
                        .isEqualTo(RECORD_NOT_FOUND_MESSAGE);
            }
        }
    }

    // =================================================================================================
    // GROUP 9 - THE STATUS SURFACE
    // =================================================================================================

    /**
     * The status operation: keyed by execution identifier, four values and no more.
     *
     * <p>The framework's exit <em>description</em> is deliberately not among them, because it carries a
     * rendered stack trace on a failed run; and neither is the parameter set, the step detail or any
     * framework object. Nothing in this group asserts an elapsed time, a duration, a record rate or a
     * memory figure - only presence and shape, which is the whole of what a status view owes a caller.
     */
    @Nested
    @Order(9)
    @DisplayName("Status by execution identifier: four members, no exit description, and an absent "
            + "execution answered without leakage")
    class StatusByExecutionIdentifier {

        /** Creates the nested specification. */
        StatusByExecutionIdentifier() {
            // Intentionally empty: the enclosing instance holds every collaborator.
        }

        @Test
        @DisplayName("a known execution is reported with exactly four members and nothing else")
        void aKnownExecutionIsReportedWithExactlyFourMembers() throws Exception {
            final long executionId =
                    launchAdmitted(FILE_PROBE_JOB, Map.of(FILE_PROBE_MODE_KEY, LEGAL_PROBE_MODES.get(2)));

            final MvcResult result = readStatus(executionId);

            assertThat(result.getResponse().getStatus())
                    .as("an execution this surface owns is reported rather than hidden")
                    .isEqualTo(200);
            final JsonNode body = jsonOf(result);
            final List<String> members = new ArrayList<>();
            body.fieldNames().forEachRemaining(members::add);
            assertThat(members)
                    .as("the published contract declares four members; a fifth would be a contract change "
                            + "and is where internal detail would arrive")
                    .containsExactlyInAnyOrder("executionId", "jobName", "status", "exitCode");
            assertThat(body.get("executionId").asLong())
                    .as("the identifier reported is the one asked after")
                    .isEqualTo(executionId);
            assertThat(textOf(body, "jobName"))
                    .as("and the stable job name is the one the launch was issued against")
                    .isEqualTo(FILE_PROBE_JOB);
        }

        @Test
        @DisplayName("the reported status and exit code are present and shaped, and neither is timed")
        void theReportedStatusAndExitCodeArePresentAndShaped() throws Exception {
            final long executionId =
                    launchAdmitted(FILE_PROBE_JOB, Map.of(FILE_PROBE_MODE_KEY, LEGAL_PROBE_MODES.get(3)));

            final JsonNode body = jsonOf(readStatus(executionId));

            // Presence and shape only. No elapsed time, no duration, no record rate and no memory figure is
            // asserted anywhere in this file: this module ships no such service level, and the estate
            // documented none to compare against.
            assertThat(textOf(body, "status"))
                    .as("the framework's own status name, which is upper case and never blank")
                    .isNotBlank()
                    .isEqualTo(textOf(body, "status").toUpperCase(Locale.ROOT));
            assertThat(textOf(body, "exitCode"))
                    .as("the exit code, which is always recorded even when a run had nothing to say")
                    .isNotBlank();
        }

        @Test
        @DisplayName("the reported answer never carries the framework's exit description")
        void theReportedAnswerNeverCarriesTheExitDescription() throws Exception {
            // The framework writes a rendered stack trace into the exit description of a failed run, so
            // carrying it would publish internals through a value that looks like a status.
            final long executionId = launchAdmitted(ORPHAN_JOB, noParameters());

            final MvcResult result = readStatus(executionId);

            assertThat(bodyOf(result))
                    .as("no exit description, and no fragment of one")
                    .doesNotContain("exitDescription")
                    .doesNotContain("\tat ");
            assertNoInternalDisclosure(bodyOf(result));
        }

        @Test
        @DisplayName("an execution the metadata does not hold is answered as an absent resource")
        void anAbsentExecutionIsAnAbsentResource() throws Exception {
            final MvcResult result = readStatus(ABSENT_EXECUTION_ID);

            assertThat(result.getResponse().getStatus())
                    .as("a caller cannot tell an execution that never existed from one belonging to a job "
                            + "this surface does not own, which is what stops identifiers being enumerated")
                    .isEqualTo(404);
            final String body = bodyOf(result);
            assertNoInternalDisclosure(body);
            assertThat(textOf(JSON.readTree(body), "message"))
                    .as("the same neutral summary a keyed read that found nothing answers with")
                    .isEqualTo(RECORD_NOT_FOUND_MESSAGE);
        }

        @Test
        @DisplayName("an execution identifier that is not a whole number is refused at the binding")
        void aNonNumericExecutionIdentifierIsRefused() throws Exception {
            // The identifier is declared as a whole number on the operation, so a value that will not
            // convert is a bad request rather than an absent resource - and the refusal names neither the
            // value nor the type it would not convert to.
            assertAnswered(false,
                    BatchJobController.BATCH_JOBS_PATH + "/executions/" + NON_NUMERIC_EXECUTION_ID,
                    400, REQUEST_BINDING_FAILED_MESSAGE);
        }
    }

    // =================================================================================================
    // GROUP 10 - BOTH OPERATIONS REQUIRE THE ADMINISTRATIVE AUTHORITY
    // =================================================================================================

    /**
     * Batch control is gated to the administrative authority by a rule of the chain's own.
     *
     * <p>The gate is not read out of the eighteen-transaction route table, and that is the point: not one
     * of those eighteen registered transactions starts a job, so an entry with no legacy counterpart would
     * corrupt an audit whose entire value is that it matches the resource definitions exactly. Nor is the
     * address placed beneath the administrative prefix, because the administratively gated transactions are
     * exactly five and a sixth entry there would misreport an entitlement the estate defines precisely.
     *
     * <p>The entitlement is nevertheless the same one those five carry. Relying on the chain's closing rule
     * would have been insufficient rather than merely lax: that rule requires only an established identity,
     * and one of the nine jobs clears the transaction master outright, so a launch is irreversible in a way
     * no transaction was.
     */
    @Nested
    @Order(10)
    @DisplayName("Security: both operations require the administrative authority, an ordinary session is "
            + "refused, and no refusal discloses why")
    class Security {

        /** Creates the nested specification. */
        Security() {
            // Intentionally empty: the enclosing instance holds every collaborator.
        }

        @Test
        @DisplayName("a caller presenting no session is refused on the launch operation")
        void noSessionIsRefusedOnTheLaunchOperation() throws Exception {
            final MvcResult result = launchWithSession(null, FILE_PROBE_JOB,
                    Map.of(FILE_PROBE_MODE_KEY, LEGAL_PROBE_MODES.get(0)));

            assertThat(result.getResponse().getStatus())
                    .as("the one anonymous address of this application is the sign-on route, and this is "
                            + "not it")
                    .isEqualTo(401);
            assertThat(textOf(jsonOf(result), "message")).isEqualTo(AUTHENTICATION_REQUIRED);
            assertNoInternalDisclosure(bodyOf(result));
        }

        @Test
        @DisplayName("a caller presenting no session is refused on the status operation")
        void noSessionIsRefusedOnTheStatusOperation() throws Exception {
            final MvcResult result = readStatusWithSession(null, ABSENT_EXECUTION_ID);

            assertThat(result.getResponse().getStatus())
                    .as("what has run and how it ended is the same operational detail as starting it, so "
                            + "the two halves of the surface cannot disagree about who may use it")
                    .isEqualTo(401);
            assertThat(textOf(jsonOf(result), "message")).isEqualTo(AUTHENTICATION_REQUIRED);
        }

        @Test
        @DisplayName("an ordinary session verifies and is still refused on the launch operation")
        void anOrdinarySessionIsRefusedOnTheLaunchOperation() throws Exception {
            final MvcResult result = launchWithSession(ordinarySession(), FILE_PROBE_JOB,
                    Map.of(FILE_PROBE_MODE_KEY, LEGAL_PROBE_MODES.get(0)));

            assertThat(result.getResponse().getStatus())
                    .as("the closing rule of the chain would have admitted this caller, which is exactly "
                            + "why batch control carries a rule of its own")
                    .isEqualTo(403);
            assertThat(textOf(jsonOf(result), "message")).isEqualTo(ACCESS_DENIED);
            assertNoInternalDisclosure(bodyOf(result));
        }

        @Test
        @DisplayName("an ordinary session verifies and is still refused on the status operation")
        void anOrdinarySessionIsRefusedOnTheStatusOperation() throws Exception {
            final MvcResult result = readStatusWithSession(ordinarySession(), ABSENT_EXECUTION_ID);

            assertThat(result.getResponse().getStatus())
                    .as("an ordinary signed-on caller was never able to ask what batch work had run")
                    .isEqualTo(403);
            assertThat(textOf(jsonOf(result), "message")).isEqualTo(ACCESS_DENIED);
        }

        @Test
        @DisplayName("a refused launch starts nothing, so authorization is decided before the operation "
                + "runs")
        void aRefusedLaunchStartsNothing() throws Exception {
            final long before = recordedExecutionsOfTheNineJobs();

            launchWithSession(null, ORPHAN_JOB, noParameters());
            launchWithSession(ordinarySession(), ORPHAN_JOB, noParameters());

            assertThat(recordedExecutionsOfTheNineJobs())
                    .as("the chain answers before the dispatch, so neither refusal reached the launch guard")
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("the administrative session is admitted on both operations")
        void theAdministrativeSessionIsAdmittedOnBothOperations() throws Exception {
            final long executionId =
                    launchAdmitted(FILE_PROBE_JOB, Map.of(FILE_PROBE_MODE_KEY, LEGAL_PROBE_MODES.get(1)));

            assertThat(readStatus(executionId).getResponse().getStatus())
                    .as("one entitlement admits both halves of the surface")
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("the surface lives outside the administrative prefix, so the five gated transactions "
                + "are not widened to six")
        void theSurfaceLivesOutsideTheAdministrativePrefix() {
            assertThat(BatchJobController.BATCH_CONTROL_PATH_PREFIX)
                    .as("a sixth entry beneath the administrative prefix would misreport an entitlement "
                            + "the resource definitions state precisely")
                    .isEqualTo("/api/batch")
                    .doesNotStartWith("/api/admin");
            assertThat(BatchJobController.BATCH_JOBS_PATH)
                    .as("and every operation is assembled beneath that prefix by construction, so a "
                            + "mapping cannot drift out from under the rule that gates it")
                    .startsWith(BatchJobController.BATCH_CONTROL_PATH_PREFIX);
        }
    }

    // =================================================================================================
    // GROUP 11 - THERE IS NO THIRD OPERATION
    // =================================================================================================

    /**
     * The surface is two operations, and the absence of a third is contract rather than omission.
     *
     * <p>The estate held no master orchestrator: the sequence in which work was submitted was an
     * operational convention held by whoever ran it, not a dependency encoded in a job member. A
     * run-everything, a next-in-line, a resume or a timetable would therefore invent an ordering guarantee
     * the estate never made, and the nine jobs are independently launchable in whatever order an operator
     * chooses.
     */
    @Nested
    @Order(11)
    @DisplayName("No second surface: no start-everything, no pipeline, no next-in-line, no resume and no "
            + "timetable, and neither operation answers another method")
    class NoThirdOperation {

        /** Creates the nested specification. */
        NoThirdOperation() {
            // Intentionally empty: the enclosing instance holds every collaborator.
        }

        @Test
        @DisplayName("no orchestrating operation is mapped beneath the batch address")
        void noOrchestratingOperationIsMapped() throws Exception {
            for (final String subpath : UNMAPPED_OPERATION_SUBPATHS) {
                assertNoOperationAt(true,
                        BatchJobController.BATCH_JOBS_PATH + "/" + FILE_PROBE_JOB + subpath, 404);
            }
        }

        @Test
        @DisplayName("no operation repeats a run without restating its parameters, and none resumes one")
        void noRepeatAndNoResumeOperationIsMapped() throws Exception {
            // Both would be defensible operations and neither is delivered, so neither may be assumed by a
            // caller or by a test. A repeat is expressed by launching again - which the estate's own
            // resubmission did - and a resume has no legacy counterpart at all.
            assertNoOperationAt(true,
                    BatchJobController.BATCH_JOBS_PATH + "/" + FILE_PROBE_JOB + "/next-instance", 404);
            assertNoOperationAt(true,
                    BatchJobController.BATCH_JOBS_PATH + "/executions/" + ABSENT_EXECUTION_ID + "/restart",
                    404);
        }

        @Test
        @DisplayName("the base address answers no operation of its own, so there is no inventory to read "
                + "and no bulk start")
        void theBaseAddressAnswersNoOperationOfItsOwn() throws Exception {
            // Publishing the inventory over the wire would hand a caller the enumeration the closed gate
            // exists to withhold, and a submission to the base address is the shape a bulk start would take.
            assertNoOperationAt(false, BatchJobController.BATCH_JOBS_PATH, 404);
            assertNoOperationAt(true, BatchJobController.BATCH_JOBS_PATH, 404);
            assertNoOperationAt(false, BatchJobController.BATCH_CONTROL_PATH_PREFIX, 404);
        }

        @Test
        @DisplayName("the launch operation is a submission only, so reading it is refused as an "
                + "unsupported method")
        void theLaunchOperationIsASubmissionOnly() throws Exception {
            assertNoOperationAt(false,
                    BatchJobController.BATCH_JOBS_PATH + "/" + FILE_PROBE_JOB + "/launch", 405);
        }

        @Test
        @DisplayName("the status operation is a read only, so submitting to it is refused as an "
                + "unsupported method")
        void theStatusOperationIsAReadOnly() throws Exception {
            assertNoOperationAt(true,
                    BatchJobController.BATCH_JOBS_PATH + "/executions/" + ABSENT_EXECUTION_ID, 405);
        }

        @Test
        @DisplayName("no request contract type of the two forbidden spellings exists, so the boundary "
                + "grew no parallel document")
        void noForbiddenContractTypeExists() {
            // Checked by asking the class path whether the compiled resource is present, which is a
            // resource lookup and not an inspection of any type. The delivered contract is the launch
            // request and the two answers it and the status operation return; a second pair under these
            // spellings would be a duplicate vocabulary for the same operations.
            for (final String forbidden : List.of("BatchJobRequest", "BatchJobResponse")) {
                assertThat(BatchJobControllerIT.class.getClassLoader()
                                .getResource("com/carddemo/api/dto/" + forbidden + ".class"))
                        .as("%s must not exist: the boundary publishes one typed request and two typed "
                                + "answers, and a parallel spelling would leave a caller guessing which "
                                + "one is the contract", forbidden)
                        .isNull();
            }
        }
    }

    // =================================================================================================
    // THE GRAPH UNDER TEST
    // =================================================================================================

    /**
     * The delivered graph: every production component of the eight layered packages, and nothing else.
     *
     * <p><strong>Why a scan of the layered packages rather than a hand-written import list.</strong> The
     * headline property this specification exists to assert is that the launchable inventory is closed at
     * nine, and an import list cannot answer that: it would make the number a property of the bottom of
     * this file rather than of the delivered application. Scanning the production packages makes the number
     * a measurement, exactly as starting the application would, and every other component the boundary
     * needs - the security chain, the sign-on path, the refusal renderer, the nine job configurations and
     * everything they depend on - arrives because production declares it rather than because this file
     * remembered to name it.
     *
     * <p><strong>Why the test tree is excluded by name, and why that is not a convenience.</strong> The
     * test output tree sits under the same base package as the production tree, and several
     * specifications in it carry a nested configuration of their own. Those configurations are plain
     * configurations - their enclosing classes carry the test annotations, they do not - so the test
     * framework's own exclusion, which recognises a test class by its annotated methods, does not reach
     * them. Left in, two of them contribute a bean under the same name and the graph refuses to assemble.
     * The alternative of permitting bean definitions to be overridden was rejected: it would let a test
     * double silently replace a production component and would leave this specification asserting about a
     * graph no deployment builds. Excluding by name removes the test tree and changes nothing about
     * production, because production never sees a test class.
     *
     * <p>The eight packages are the module's own layers, named individually rather than as one base
     * package so that the root package - whose only member is the application entry point, which carries
     * a scan of its own - is deliberately not swept in. Nothing production declares is filtered.
     *
     * <p>The entity and repository scans are named rather than inferred: the automatic configuration takes
     * its base package from the class that enables it, and this class lives in the boundary package rather
     * than at the root.
     *
     * <p><strong>Nothing is stubbed and nothing is mocked.</strong> The registry, the launch guard, the
     * metadata reader, the parameter validators, the security chain, the hashing service and the nine job
     * configurations are all the shipped ones. The one bean declared here is the shared pinned clock, and
     * it is marked the preferred candidate rather than replacing the production auditing configuration -
     * that configuration belongs to the delivered graph and is scanned whole.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @ComponentScan(
            basePackages = {"com.carddemo.api", "com.carddemo.batch", "com.carddemo.config",
                "com.carddemo.domain", "com.carddemo.exception", "com.carddemo.repository",
                "com.carddemo.service", "com.carddemo.util"},
            excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                    pattern = {"com\\.carddemo\\..*IT", "com\\.carddemo\\..*IT\\$.*",
                        "com\\.carddemo\\..*Test", "com\\.carddemo\\..*Test\\$.*"}))
    @EnableJpaRepositories(basePackageClasses = UserSecurityRepository.class)
    @EntityScan(basePackageClasses = UserSecurity.class)
    static class DeliveredGraph {

        /** Creates the configuration. */
        DeliveredGraph() {
            // Intentionally empty: this graph contributes one bean, and no state.
        }

        /**
         * The pinned clock every collaborator in the graph reads.
         *
         * @return the shared base's clock, frozen at the instant the delivered fixtures carry
         */
        @Bean
        @Primary
        Clock pinnedClock() {
            return FIXED_CLOCK;
        }
    }
}
