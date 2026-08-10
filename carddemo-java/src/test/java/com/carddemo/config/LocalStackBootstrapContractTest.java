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

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.util.SqsNamingRules;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

/**
 * Verifies the local AWS bootstrap contract: the three resources the emulator hook provisions, the
 * attributes that make each of them behave the way the legacy resource behaved, and the agreement
 * between every file that names them.
 *
 * <h2>What this test guards</h2>
 *
 * <p>{@code localstack/init/01-create-aws-resources.sh} provisions the whole AWS surface this module
 * uses - a first-in-first-out queue, an object-store bucket with versioning, and a notification topic
 * - and the eight validation gates all run against the stack it produces. Two properties of that
 * script are behavioural rather than cosmetic, and both are invisible at run time until they are
 * already wrong:
 *
 * <ul>
 *   <li><strong>The resource names are duplicated across seven files.</strong> They appear in the
 *       script, in {@code docker-compose.yml} which passes them into the container, and in four
 *       profile overlays plus the test overlay which bind them into the application. Nothing
 *       reconciles those copies. The script's own header records why that is dangerous: the legacy
 *       queue write was defined errors-ignored, so the publisher logs a failure and carries on -
 *       meaning a disagreement produces a stack that starts perfectly cleanly and then silently
 *       publishes nowhere, with nothing in the start-up log naming the cause.</li>
 *   <li><strong>Content-based deduplication must be off.</strong> One submitted job image is
 *       seventeen fixed-width cards, but only fourteen of the seventeen bodies are distinct - three
 *       are the same comment delimiter and two more the same in-stream delimiter. With content-based
 *       deduplication enabled, seventeen published cards would arrive as fourteen, and the symptom
 *       would be an arbitrary-looking message count with no diagnostic trail.</li>
 * </ul>
 *
 * <h2>Why the script is read as text</h2>
 *
 * <p>A shell script is not loadable as a bean, so the only way to hold it to a contract inside the
 * build is to read it and assert on what it says. That has a real limitation and this suite does not
 * pretend otherwise: reading text proves the script <em>declares</em> the right thing, not that the
 * emulator then does it. The companion integration test executes this same file against a live
 * emulator and asserts the resources it actually produces. The two together are what discharge the
 * contract; this half is the one that runs without a container, and it is the only half that can
 * check the cross-file agreement, because six of the seven files are never executed at all.
 *
 * <h2>How the evidence is obtained, and what that does and does not prove</h2>
 *
 * <p>Every expected string in this class is written out by hand - the three resource names, the
 * message group, the region, the two queue attributes, the versioning state, the enabled service
 * list, the mount target and the pinned image tag. None is read from the script and then compared
 * against itself. The assertions are deliberately made against the file on disk rather than against a
 * copy on the classpath, because the file on disk is what the container mounts.
 *
 * <p>Why this tier exists alongside {@link LocalStackBootstrapIT}, rather than either standing alone,
 * is reasoned in {@code docs/decision-log.md} DL-114 - together with the production overlay's
 * deliberate refusal to default the queue name, and the reason a census here judges a binding by what
 * it points at rather than by the property name it is bound under. The deduplication attribute is the
 * resource-side half of DL-043, the queue's name is DL-045, and the publisher's ignore-on-error posture
 * is DL-044.
 *
 * <p><strong>Provenance.</strong> Checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream
 * release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The legacy queue's
 * resource-definition attribute names are cited as metadata; no legacy source statement is
 * reproduced.</p>
 */
@DisplayName("AWS bootstrap: three resources, their behavioural attributes, and seven files agreeing")
final class LocalStackBootstrapContractTest {

    /** The bootstrap hook, relative to the module directory. */
    private static final String SCRIPT_PATH = "localstack/init/01-create-aws-resources.sh";

    /** The directory the emulator mounts as its ready hook. */
    private static final String SCRIPT_DIRECTORY = "localstack/init";

    /** The local stack definition that passes the names into the container. */
    private static final String COMPOSE_PATH = "docker-compose.yml";

    /** The canonical object-store bucket. */
    private static final String BUCKET = "carddemo-batch-staging";

    /** The canonical queue. The suffix is required by the service, not decoration. */
    private static final String QUEUE = "JOBS.fifo";

    /** The suffix a first-in-first-out queue name must carry. */
    private static final String FIFO_SUFFIX = ".fifo";

    /** The canonical notification topic. */
    private static final String TOPIC = "carddemo-job-notifications";

    /** The single stable message group that preserves append order. */
    private static final String MESSAGE_GROUP = "carddemo-job-submission";

    /** The canonical region. */
    private static final String REGION = "us-east-1";

    /** The readable emulator tag shared with the Testcontainers harness. */
    private static final String EMULATOR_IMAGE_TAG = "localstack/localstack:4.14.0";

    /** The immutable image reference used by Compose. */
    private static final String EMULATOR_IMAGE = EMULATOR_IMAGE_TAG
            + "@sha256:3ebc37595918b8accb852f8048fef2aff047d465167edd655528065b07bc364a";

    /** The exact attribute pair the queue is created with. */
    private static final String QUEUE_ATTRIBUTES = "FifoQueue=true,ContentBasedDeduplication=false";

    /** The exact versioning state applied to the bucket. */
    private static final String VERSIONING_STATE = "Status=Enabled";

    /** The exact service list the emulator is started with. */
    private static final String ENABLED_SERVICES = "s3,sqs,sns";

    /** The mount that makes the script a ready hook, read-only. */
    private static final String READY_HOOK_MOUNT =
            "./localstack/init:/etc/localstack/init/ready.d:ro";

    /** The strict-shell directive that makes a provisioning failure loud. */
    private static final String STRICT_SHELL_DIRECTIVE = "set -eu";

    /** How many resources the script provisions. */
    private static final int PROVISIONED_RESOURCE_COUNT = 3;

    /**
     * One row of the header's resource-to-key table: a value, a resource description, then an arrow and
     * the configuration key path the application binds that resource from.
     *
     * <p>Anchored on the arrow rather than on the key, so that prose mentioning a key path - including
     * the two spellings the header records as withdrawn - is not read as a table row.
     */
    private static final Pattern KEY_TABLE_ROW =
            Pattern.compile("^#\\s{2,}\\S+\\s{2,}(.+?)\\s+->\\s+(carddemo\\.\\S+)\\s*$");

    /** Any configuration key path under this module's own prefix, wherever it occurs in the script. */
    private static final Pattern CONFIGURATION_KEY = Pattern.compile("carddemo\\.aws\\.[a-z0-9.-]+");

    /** The profile overlays that bind these names, plus the test overlay. */
    private static final List<String> OVERLAYS_BINDING_THE_NAMES = List.of(
            "src/main/resources/application.yml",
            "src/main/resources/application-local.yml",
            "src/main/resources/application-test.yml",
            "src/main/resources/application-prod.yml",
            "src/test/resources/application-test.yml");

    /**
     * Reads a file from the module directory in full.
     *
     * @param relativePath the path relative to the module directory
     * @return the file's content as text
     * @throws IOException if the file cannot be read
     */
    private static String read(final String relativePath) throws IOException {
        final Path path = Paths.get(relativePath);
        assertThat(Files.isRegularFile(path))
                .as("%s must exist in the module", relativePath)
                .isTrue();
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /**
     * Returns only the executable lines of the bootstrap script, with comments and blank lines
     * removed.
     *
     * <p>The script is heavily commented, and several of the strings this suite asserts on also occur
     * in those comments. Judging the comments would let a contract be satisfied by a sentence
     * describing it rather than by a command performing it, so every attribute assertion below is made
     * against this reduced view.
     *
     * @return the script's executable lines, in file order
     * @throws IOException if the script cannot be read
     */
    private static List<String> executableLines() throws IOException {
        final List<String> lines = new ArrayList<>();
        for (final String line : read(SCRIPT_PATH).split("\n", -1)) {
            final String trimmed = line.strip();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                lines.add(trimmed);
            }
        }
        return lines;
    }

    /**
     * Joins the script's executable lines into one searchable body.
     *
     * @return the executable portion of the script as a single string
     * @throws IOException if the script cannot be read
     */
    private static String executableBody() throws IOException {
        return String.join("\n", executableLines());
    }

    @Nested
    @DisplayName("the hook itself")
    final class TheHookItself {

        @Test
        @DisplayName("is the only script in the mounted directory, executable, and strict-shell")
        void isTheOnlyScriptInTheMountedDirectoryAndStrict() throws IOException {
            // The directory is mounted wholesale and the emulator runs everything executable it finds
            // there, in lexical order. An unaccounted-for second script would therefore run in
            // production-like local stacks without anything naming it, so the count is pinned.
            final Path directory = Paths.get(SCRIPT_DIRECTORY);
            assertThat(Files.isDirectory(directory)).isTrue();

            final List<String> entries;
            try (var stream = Files.list(directory)) {
                entries = stream.map(path -> path.getFileName().toString()).sorted().toList();
            }
            assertThat(entries)
                    .as("the mounted hook directory must hold exactly the one known script")
                    .containsExactly("01-create-aws-resources.sh");

            assertThat(Files.isExecutable(Paths.get(SCRIPT_PATH)))
                    .as("the hook must be executable or the emulator will skip it silently")
                    .isTrue();
            assertThat(read(SCRIPT_PATH))
                    .as("the hook must declare an interpreter")
                    .startsWith("#!/usr/bin/env bash");
            assertThat(executableLines())
                    .as("the hook must fail loudly: -e so a provisioning error surfaces, -u so a "
                            + "mistyped variable is an error rather than an empty resource name")
                    .contains(STRICT_SHELL_DIRECTIVE);
        }

        @Test
        @DisplayName("carries the licence header every module artefact carries")
        void carriesTheLicenceHeader() throws IOException {
            assertThat(read(SCRIPT_PATH))
                    .contains("Copyright Amazon.com, Inc. or its affiliates.")
                    .contains("Licensed under the Apache License, Version 2.0");
        }

        @Test
        @DisplayName("contains no credential literal and no numeric service level")
        void containsNoCredentialLiteralAndNoServiceLevel() throws IOException {
            // Two separate constraints, both stated by the plan. The emulator tool resolves its own
            // throwaway sign-in values internally, so no key belongs in this file; and no numeric
            // service level is documented anywhere in the legacy estate, so the performance gate
            // measures a baseline rather than testing a threshold - a timeout or capacity figure here
            // would invent one.
            final String body = executableBody();

            assertThat(body.toLowerCase(Locale.ROOT))
                    .as("no sign-in value may appear in the bootstrap")
                    .doesNotContain("aws_access_key_id")
                    .doesNotContain("aws_secret_access_key")
                    .doesNotContain("--profile");
            assertThat(body)
                    .as("no endpoint flag is needed: the tool resolves the edge endpoint itself")
                    .doesNotContain("--endpoint-url");
            assertThat(body)
                    .as("no retention, encryption or capacity setting may be configured here")
                    .doesNotContain("put-bucket-lifecycle")
                    .doesNotContain("put-bucket-encryption")
                    .doesNotContain("object-lock")
                    .doesNotContain("VisibilityTimeout")
                    .doesNotContain("MessageRetentionPeriod");
        }

        @Test
        @DisplayName("provisions exactly three resources and says so on completion")
        void provisionsExactlyThreeResources() throws IOException {
            final String body = executableBody();

            assertThat(body)
                    .as("one create call per resource, and no more")
                    .containsOnlyOnce("sqs create-queue")
                    .containsOnlyOnce("s3api create-bucket")
                    .containsOnlyOnce("sns create-topic");
            assertThat(body)
                    .as("the completion line is what the gate runbook reads back out of the log, "
                            + "and it claims verification rather than mere readiness because every "
                            + "read-back above it ends the script instead of warning")
                    .contains(PROVISIONED_RESOURCE_COUNT + " of " + PROVISIONED_RESOURCE_COUNT
                            + " resources verified");
            assertThat(body)
                    .as("the weaker wording must not reappear: a line saying the resources are "
                            + "ready would be true of a run that created them without checking one")
                    .doesNotContain("resources ready");
        }
    }

    @Nested
    @DisplayName("the canonical names, and their redirection")
    final class TheCanonicalNames {

        @ParameterizedTest(name = "{0} defaults to {1}")
        @CsvSource({
            "AWS_DEFAULT_REGION,            us-east-1",
            "CARDDEMO_S3_BUCKET,            carddemo-batch-staging",
            "CARDDEMO_SQS_QUEUE,            JOBS.fifo",
            "CARDDEMO_SQS_MESSAGE_GROUP_ID, carddemo-job-submission",
            "CARDDEMO_SNS_TOPIC,            carddemo-job-notifications",
        })
        @DisplayName("each name is redirectable by environment yet defaults to the canonical value")
        void eachNameIsRedirectableAndDefaultsToTheCanonicalValue(final String variable,
                final String canonicalValue) throws IOException {
            // Both halves matter. The indirection is what lets the stack definition pass names in, and
            // the default is what makes an unset environment still produce the agreed stack rather
            // than an empty resource name. The exact substitution form is asserted, because the
            // colon-dash form supplies a default for unset AND empty, whereas the bare dash form
            // would leave an explicitly empty variable empty.
            assertThat(executableBody())
                    .as("%s must be read with a default-substitution that supplies %s",
                            variable, canonicalValue)
                    .contains("${" + variable + ":-" + canonicalValue + "}");
        }

        @Test
        @DisplayName("and the property path the script names for each resource is the property path the "
                + "application actually binds")
        void theScriptNamesThePropertyPathsTheApplicationBinds() throws IOException {
            // WHY A COMMENT IS ASSERTED HERE, WHEN EVERY OTHER ASSERTION IN THIS CLASS DELIBERATELY READS
            // ONLY THE EXECUTABLE LINES. This script creates the resources and binds none of them, so the
            // mapping from a created resource to the setting the application reads it under exists ONLY as
            // this header table. That makes the table the sole navigational route from a resource that is
            // wrong to the configuration key that would have to change - and a wrong route costs a reader
            // real time, because a property path that does not exist cannot be grepped to nothing useful.
            //
            // It had drifted: the table named carddemo.aws.s3.bucket and
            // carddemo.aws.sqs.job-submission-queue, neither of which any file declares. The keys the
            // application binds are the constants below, so they are compared against the constants rather
            // than against transcriptions of them, and a rename of either now fails here instead of
            // silently stranding the table again.
            final String header = read(SCRIPT_PATH);

            assertThat(header)
                    .as("the object-store bucket's setting, as AwsProperties declares it")
                    .contains(AwsProperties.S3.BATCH_STAGING_BUCKET_PROPERTY)
                    .as("the submission queue's setting")
                    .contains(AwsProperties.Sqs.JOB_QUEUE_PROPERTY)
                    .as("the message group the publisher stamps on every card")
                    .contains(AwsProperties.Sqs.MESSAGE_GROUP_ID_PROPERTY)
                    .as("the notification topic")
                    .contains(AwsProperties.Sns.JOB_NOTIFICATION_TOPIC_PROPERTY)
                    .as("and the region every destination is resolved against")
                    .contains(AwsProperties.REGION_PROPERTY);
            assertThat(header)
                    .as("while the two paths that never existed must not come back; each reads as "
                            + "plausible, which is exactly why a reader trusted them")
                    .doesNotContain("carddemo.aws.s3.bucket")
                    .doesNotContain("carddemo.aws.sqs.job-submission-queue");
        }

        @Test
        @DisplayName("the queue name carries the suffix the service requires of a fifo queue")
        void theQueueNameCarriesTheRequiredSuffix() throws IOException {
            // Not decoration: the service rejects a first-in-first-out queue whose name omits the
            // suffix, which would be a start-up failure.
            assertThat(QUEUE).endsWith(FIFO_SUFFIX);
            assertThat(QUEUE.substring(0, QUEUE.length() - FIFO_SUFFIX.length()))
                    .as("the stem ahead of the suffix is the name the migration plan MANDATES, and it "
                            + "is module-namespaced like the other three resources rather than the "
                            + "legacy transient-data name. An intermediate revision made this stem the "
                            + "bare legacy 'JOBS'; that is withdrawn - see docs/decision-log.md DL-092 "
                            + "- because what the plan freezes for this resource is the target name it "
                            + "prescribes, while the legacy name is carried by the operator-visible "
                            + "failure message, which is the contract actually compared byte for byte")
                    .isEqualTo("JOBS");
            assertThat(executableBody()).contains(QUEUE);
        }
    }

    @Nested
    @DisplayName("the queue's behavioural attributes")
    final class TheQueuesBehaviouralAttributes {

        @Test
        @DisplayName("the queue is created fifo with content-based deduplication explicitly off")
        void theQueueIsCreatedFifoWithDeduplicationOff() throws IOException {
            // The single most consequential line in the script. Both attributes are asserted as one
            // exact pair rather than as two independent substrings, because the pair is what is
            // passed and a partially-correct pair is the failure this guards.
            assertThat(executableBody())
                    .as("the queue must be created with exactly the contractual attribute pair")
                    .contains("--attributes " + QUEUE_ATTRIBUTES);
            assertThat(QUEUE_ATTRIBUTES)
                    .as("ordering is guaranteed by the fifo attribute")
                    .contains("FifoQueue=true")
                    .as("and no card of a submission may be silently discarded")
                    .contains("ContentBasedDeduplication=false");
        }

        /**
         * Deduplication may be forced off after creation; it may never be switched on.
         *
         * <p>The negative form of the assertion above, and it has to be stated as a direction rather
         * than as an absence. The script does write the attribute after creation, but only ever to
         * {@code false}, and only when the read-back showed something else: the attribute is mutable, so
         * a queue left in the wrong state by anything other than this hook can be repaired in place
         * instead of merely reported. What must never appear is a write that enables deduplication,
         * because three of the seventeen cards of a submission share a body with another card and a
         * deduplicating queue would accept seventeen and deliver fourteen.</p>
         *
         * <p>Asserting that no write exists at all - which would be the simpler statement - would
         * forbid the repair and leave the only response to a wrong value being to refuse the stack.
         * Asserting the direction instead keeps the repair available while still failing on the edit
         * that matters, so every occurrence of the attribute in the script is checked to be the safe
         * value.</p>
         */
        @Test
        @DisplayName("content-based deduplication is only ever written as false, so the repair path "
                + "cannot become the path that enables it")
        void deduplicationIsOnlyEverWrittenAsFalse() throws IOException {
            final String body = executableBody();

            assertThat(body)
                    .as("nothing in the script may enable content-based deduplication")
                    .doesNotContain("ContentBasedDeduplication=true");
            assertThat(body)
                    .as("the one attribute write present sets it to the safe value")
                    .contains("--attributes ContentBasedDeduplication=false");

            assertThat(body)
                    .as("the creation call sets both attributes together, so a queue this hook "
                            + "creates is never briefly deduplicating")
                    .contains("--attributes FifoQueue=true,ContentBasedDeduplication=false");

            final int writes = body.split("--attributes ", -1).length - 1;
            assertThat(writes)
                    .as("there are exactly two attribute writes - the creation call and the repair - "
                            + "so no third write can undo either")
                    .isEqualTo(2);
        }

        /**
         * Both attributes are read back, one call at a time, and both are compared rather than printed.
         *
         * <p>The read-back is the script's own evidence, printed into the container log, that ordering
         * is guaranteed and that no card can be dropped, and the gate runbook reads those lines. Each
         * attribute is fetched by a single-attribute helper rather than by one call naming both, so that
         * every comparison is against one unambiguous value instead of a positional pair a reader has to
         * line up by eye - and so that a mismatch names which attribute was wrong.</p>
         */
        @Test
        @DisplayName("both queue attributes are read back one at a time and compared, not assumed")
        void theQueuesAttributesAreReadBack() throws IOException {
            final String body = executableBody();

            assertThat(body)
                    .contains("sqs get-queue-attributes")
                    .as("one attribute per call, parameterised by the helper's argument")
                    .contains("--attribute-names \"$1\"")
                    .contains("--query \"Attributes.$1\"");
            assertThat(body)
                    .as("and both attributes are actually fetched through it")
                    .contains("queue_attribute FifoQueue")
                    .contains("queue_attribute ContentBasedDeduplication");
            assertThat(body)
                    .as("each is compared against the value the contract requires, so a wrong value "
                            + "stops the hook instead of being printed and passed over")
                    .contains("!= 'true'")
                    .contains("!= 'false'");
        }

        @Test
        @DisplayName("nothing consumes from the queue or the topic: the bridge is publish-only")
        void nothingConsumesFromTheQueueOrTopic() throws IOException {
            // The legacy definition was output-only, so no receive path, no dead-letter queue, no
            // access policy, no permission grant and no subscription is created. Each absence is a
            // decision, so each is pinned.
            final String body = executableBody();

            assertThat(body)
                    .doesNotContain("receive-message")
                    .doesNotContain("RedrivePolicy")
                    .doesNotContain("add-permission")
                    .doesNotContain("sns subscribe")
                    .doesNotContain("Policy=");
            // The attribute write the script does perform is deliberately not listed above. It forces
            // content-based deduplication off and is a property of the publish path rather than a
            // consume path, so bundling it into this claim would have made a statement about who reads
            // the queue fail for a reason that has nothing to do with reading it. Its direction is
            // pinned by deduplicationIsOnlyEverWrittenAsFalse instead.
        }
    }

    @Nested
    @DisplayName("the bucket's versioning")
    final class TheBucketsVersioning {

        @Test
        @DisplayName("versioning is enabled, and applied outside the existence guard so a rerun re-applies it")
        void versioningIsEnabledAndAppliedUnconditionally() throws IOException {
            // Versioning is the generation-data-group replacement, so it is the one setting the bucket
            // must carry. Applying it outside the existence guard matters: a bucket that already
            // existed WITHOUT versioning - created by anything other than this script - would keep
            // that state forever if the call sat inside the else branch.
            final List<String> lines = executableLines();

            assertThat(executableBody())
                    .as("versioning must be enabled on the bucket")
                    .contains("s3api put-bucket-versioning")
                    .contains("--versioning-configuration " + VERSIONING_STATE);

            final int guardIndex = indexOfLineContaining(lines, "s3api head-bucket");
            final int createIndex = indexOfLineContaining(lines, "s3api create-bucket");
            final int versioningIndex = indexOfLineContaining(lines, "s3api put-bucket-versioning");
            final int guardEndIndex = indexOfLineFrom(lines, createIndex, "fi");

            assertThat(guardIndex).as("the existence guard must precede creation").isLessThan(createIndex);
            assertThat(guardEndIndex)
                    .as("the guard must be closed before versioning is applied")
                    .isLessThan(versioningIndex);
        }

        @Test
        @DisplayName("the versioning state is read back rather than assumed")
        void theVersioningStateIsReadBack() throws IOException {
            assertThat(executableBody()).contains("s3api get-bucket-versioning");
        }

        @Test
        @DisplayName("no placeholder object is created for any key prefix")
        void noPlaceholderObjectIsCreatedForAnyPrefix() throws IOException {
            // Key prefixes are not resources. A marker object would be returned to any reader listing
            // that prefix, and would gain a new version on every rerun of the hook - so the absence is
            // deliberate and is pinned.
            assertThat(executableBody())
                    .doesNotContain("s3api put-object")
                    .doesNotContain("s3 cp")
                    .doesNotContain("s3api put-bucket-lifecycle-configuration");
        }
    }

    @Nested
    @DisplayName("idempotency, because a ready hook reruns on every container start")
    final class Idempotency {

        @Test
        @DisplayName("the queue is guarded by an existence check before it is created")
        void theQueueIsGuardedBeforeCreation() throws IOException {
            final List<String> lines = executableLines();
            final int guardIndex = indexOfLineContaining(lines, "sqs get-queue-url --queue-name");
            final int createIndex = indexOfLineContaining(lines, "sqs create-queue");

            assertThat(guardIndex)
                    .as("an existence check must precede queue creation")
                    .isGreaterThanOrEqualTo(0)
                    .isLessThan(createIndex);
            assertThat(lines.get(guardIndex))
                    .as("the guard must discard its output and its error, so a miss is not a failure "
                            + "under the strict-shell directive")
                    .contains(">/dev/null 2>&1")
                    .startsWith("if ");
        }

        @Test
        @DisplayName("the bucket is guarded by an existence check before it is created")
        void theBucketIsGuardedBeforeCreation() throws IOException {
            final List<String> lines = executableLines();
            final int guardIndex = indexOfLineContaining(lines, "s3api head-bucket");
            final int createIndex = indexOfLineContaining(lines, "s3api create-bucket");

            assertThat(guardIndex)
                    .as("an existence check must precede bucket creation")
                    .isGreaterThanOrEqualTo(0)
                    .isLessThan(createIndex);
            assertThat(lines.get(guardIndex))
                    .contains(">/dev/null 2>&1")
                    .startsWith("if ");
        }

        @Test
        @DisplayName("an already-present resource is reported and left alone, never recreated")
        void anAlreadyPresentResourceIsLeftAlone() throws IOException {
            // "Already there" is a normal outcome for a hook that reruns, so it is tolerated
            // explicitly on each guarded resource rather than allowed to abort the script.
            final String body = executableBody();

            assertThat(body)
                    .as("both guarded resources report the already-present outcome")
                    .contains("queue ${QUEUE} already present")
                    .contains("bucket ${BUCKET} already present");
            assertThat(body)
                    .as("no resource is deleted and recreated to reach a known state")
                    .doesNotContain("delete-queue")
                    .doesNotContain("delete-bucket")
                    .doesNotContain("delete-topic")
                    .doesNotContain("purge-queue");
        }

        @Test
        @DisplayName("the topic needs no guard, because creating it by name is itself idempotent")
        void theTopicNeedsNoGuard() throws IOException {
            // Asserted rather than left implicit: the asymmetry between the three resources is
            // intentional, and a reader who did not know that would read the missing guard as an
            // oversight.
            final List<String> lines = executableLines();
            final int createIndex = indexOfLineContaining(lines, "sns create-topic");

            assertThat(createIndex).isGreaterThanOrEqualTo(0);
            assertThat(lines.get(createIndex))
                    .as("the topic is created unconditionally, capturing its identifier either way")
                    .doesNotStartWith("if ")
                    .contains("--name");
            assertThat(executableBody())
                    .as("and no existence check is performed for it: it is never enumerated to find "
                            + "out whether it is already there")
                    .doesNotContain("sns list-topics");
            // sns get-topic-attributes IS present, and is deliberately not forbidden here. It is not an
            // existence check: it runs AFTER the unconditional create and resolves the identifier that
            // create returned back to itself, so that an identifier which is malformed or names some
            // other topic is caught rather than reported ready. Forbidding it would have forced the
            // topic to be the one resource whose identifier is echoed without being verified.
            assertThat(executableBody())
                    .as("the identifier the create call returned is resolved back to itself")
                    .contains("sns get-topic-attributes")
                    .contains("RESOLVED_TOPIC_ARN");
        }
    }

    @Nested
    @DisplayName("agreement across every file that names these resources")
    final class AgreementAcrossEveryFile {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
            "carddemo-batch-staging",
            "JOBS.fifo",
            "carddemo-job-submission",
            "carddemo-job-notifications",
        })
        @DisplayName("each canonical name occurs in the script, the stack definition and every overlay")
        void eachCanonicalNameOccursEverywhereItMust(final String canonicalName) throws IOException {
            // This is the assertion the run time cannot make. Six of the seven files are never
            // executed by any test, and a disagreement between them has no fail-fast signal at all -
            // the stack starts cleanly and the first publish goes nowhere. Absences are collected so a
            // failure names every file that is missing the name rather than only the first.
            final Map<String, Boolean> presence = new LinkedHashMap<>();
            presence.put(SCRIPT_PATH, read(SCRIPT_PATH).contains(canonicalName));
            presence.put(COMPOSE_PATH, read(COMPOSE_PATH).contains(canonicalName));
            for (final String overlay : OVERLAYS_BINDING_THE_NAMES) {
                presence.put(overlay, read(overlay).contains(canonicalName));
            }

            final List<String> missing = presence.entrySet().stream()
                    .filter(entry -> !entry.getValue())
                    .map(Map.Entry::getKey)
                    .toList();

            assertThat(missing)
                    .as("every file that names the AWS resources must name %s identically",
                            canonicalName)
                    .isEmpty();
            assertThat(presence)
                    .as("the reconciliation must span the script, the stack definition and all "
                            + "five overlays")
                    .hasSize(OVERLAYS_BINDING_THE_NAMES.size() + 2);
        }

        @Test
        @DisplayName("the stack definition passes exactly the four redirectable names into the container")
        void theStackDefinitionPassesTheFourNames() throws IOException {
            final String compose = read(COMPOSE_PATH);

            assertThat(compose)
                    .contains("CARDDEMO_S3_BUCKET: ${CARDDEMO_S3_BUCKET:-" + BUCKET + "}")
                    .contains("CARDDEMO_SQS_QUEUE: ${CARDDEMO_SQS_QUEUE:-" + QUEUE + "}")
                    .contains("CARDDEMO_SQS_MESSAGE_GROUP_ID: ${CARDDEMO_SQS_MESSAGE_GROUP_ID:-"
                            + MESSAGE_GROUP + "}")
                    .contains("CARDDEMO_SNS_TOPIC: ${CARDDEMO_SNS_TOPIC:-" + TOPIC + "}")
                    .contains("AWS_DEFAULT_REGION: ${AWS_REGION:-" + REGION + "}");
        }

        @Test
        @DisplayName("the stack definition mounts the hook read-only at the emulator's ready directory")
        void theStackDefinitionMountsTheHookReadOnly() throws IOException {
            // The mount is what makes the script a hook at all. Read-only matters because the
            // container must not be able to alter the provisioning it is given.
            assertThat(read(COMPOSE_PATH))
                    .as("the hook directory must be mounted at the emulator's ready path, read-only")
                    .contains(READY_HOOK_MOUNT);
            assertThat(READY_HOOK_MOUNT).endsWith(":ro");
        }

        @Test
        @DisplayName("the emulator is started with exactly the three services this module uses")
        void theEmulatorEnablesExactlyThreeServices() throws IOException {
            // Enabling more would mean the stack differs from the surface the module actually uses,
            // and the plan records that these three are the whole of it.
            assertThat(read(COMPOSE_PATH))
                    .contains("SERVICES: " + ENABLED_SERVICES);
            assertThat(ENABLED_SERVICES.split(",")).hasSize(PROVISIONED_RESOURCE_COUNT);
        }

        @Test
        @DisplayName("the stack definition, the integration harness and the bootstrap tier all pin the "
                + "same emulator image, digest included")
        void theStackDefinitionAndHarnessPinTheSameImage() throws IOException {
            // The stack definition states this requirement in a comment; here it becomes a check. A
            // harness on a different tag would validate a stack no operator ever runs - and a harness on
            // the same TAG but a different DIGEST would do the same thing without being visible, because a
            // tag is a mutable pointer. The harness pinned tag-only until this was widened, so all three
            // references are now compared as whole references.
            assertThat(read(COMPOSE_PATH))
                    .as("the stack definition must pin the emulator image")
                    .contains("image: " + EMULATOR_IMAGE);
            assertThat(read("src/test/java/com/carddemo/support/AbstractLocalStackIT.java"))
                    .as("the integration harness must pin the same reference, digest included, so a "
                            + "rebuild of the tag upstream cannot move the suite off the image the stack "
                            + "was verified against")
                    .contains("\"" + EMULATOR_IMAGE_TAG + "@sha256:\"")
                    .contains(EMULATOR_IMAGE.substring(EMULATOR_IMAGE.indexOf("@sha256:") + 8));
            assertThat(read("src/test/java/com/carddemo/config/LocalStackBootstrapIT.java"))
                    .as("and so must the bootstrap tier, which starts an emulator of its own with the "
                            + "provisioning hook copied into it")
                    .contains("\"" + EMULATOR_IMAGE_TAG + "@sha256:\"")
                    .contains(EMULATOR_IMAGE.substring(EMULATOR_IMAGE.indexOf("@sha256:") + 8));
        }

        @Test
        @DisplayName("the test overlay binds the names as plain values, needing no environment")
        void theTestOverlayBindsPlainValues() throws IOException {
            // The test overlay is the one profile that must not depend on the environment, because a
            // container-bound integration test supplies its endpoint rather than its resource names.
            final String overlay = read("src/test/resources/application-test.yml");

            assertThat(overlay)
                    .contains("batch-staging-bucket: " + BUCKET)
                    .contains("job-queue: " + QUEUE)
                    .contains("message-group-id: " + MESSAGE_GROUP)
                    .contains("job-notification-topic: " + TOPIC);
            assertThat(overlay)
                    .as("the test overlay must not defer any resource name to the environment")
                    .doesNotContain("${CARDDEMO_S3_BUCKET")
                    .doesNotContain("${CARDDEMO_SQS_QUEUE")
                    .doesNotContain("${CARDDEMO_SNS_TOPIC");
        }

        @Test
        @DisplayName("the base, local and test overlays default all four names to the canonical value")
        void theBaseLocalAndTestOverlaysDefaultAllFourNames() throws IOException {
            // The reverse direction of the reconciliation, and the precise one. The presence check
            // above is satisfied by a name occurring anywhere in a file, including in a comment; this
            // asserts the actual binding line, character for character, so a name that is documented
            // but not bound - or bound to something else - fails. Every binding is collected and
            // compared as a whole map, so a failure names every divergence at once rather than the
            // first.
            final Map<String, String> expectedBindings = new LinkedHashMap<>();
            expectedBindings.put("batch-staging-bucket:",
                    "batch-staging-bucket: ${CARDDEMO_S3_BUCKET:" + BUCKET + "}");
            expectedBindings.put("job-queue:",
                    "job-queue: ${CARDDEMO_SQS_QUEUE:" + QUEUE + "}");
            expectedBindings.put("message-group-id:",
                    "message-group-id: ${CARDDEMO_SQS_MESSAGE_GROUP_ID:" + MESSAGE_GROUP + "}");
            expectedBindings.put("job-notification-topic:",
                    "job-notification-topic: ${CARDDEMO_SNS_TOPIC:" + TOPIC + "}");

            for (final String overlay : List.of(
                    "src/main/resources/application.yml",
                    "src/main/resources/application-local.yml",
                    "src/main/resources/application-test.yml")) {
                final Map<String, String> actual = new LinkedHashMap<>();
                for (final String key : expectedBindings.keySet()) {
                    actual.put(key, bindingLine(overlay, key));
                }
                assertThat(actual)
                        .as("%s must bind every resource name to its canonical default", overlay)
                        .isEqualTo(expectedBindings);
            }
        }

        @Test
        @DisplayName("production defaults three names and leaves the queue deliberately undefaulted")
        void productionLeavesTheQueueDeliberatelyUndefaulted() throws IOException {
            // The one asymmetry in the whole reconciliation, and it is deliberate rather than an
            // omission - the production overlay states the reason in place, and the environment
            // inventory at the head of that file states it again. A defaulted queue in production
            // would be a well-formed name that names nothing: the messaging template would resolve it
            // by creating a queue nothing consumes, so a submission would report complete while the
            // cards sat unread. Every other name is safe to default, because getting the bucket or the
            // topic wrong fails visibly rather than silently.
            final String overlay = "src/main/resources/application-prod.yml";

            assertThat(bindingLine(overlay, "batch-staging-bucket:"))
                    .isEqualTo("batch-staging-bucket: ${CARDDEMO_S3_BUCKET:" + BUCKET + "}");
            assertThat(bindingLine(overlay, "message-group-id:"))
                    .isEqualTo("message-group-id: ${CARDDEMO_SQS_MESSAGE_GROUP_ID:"
                            + MESSAGE_GROUP + "}");
            assertThat(bindingLine(overlay, "job-notification-topic:"))
                    .isEqualTo("job-notification-topic: ${CARDDEMO_SNS_TOPIC:" + TOPIC + "}");

            assertThat(bindingLine(overlay, "job-queue:"))
                    .as("production must require the queue to be supplied explicitly")
                    .isEqualTo("job-queue: ${CARDDEMO_SQS_QUEUE}")
                    .as("and must carry no fallback of any kind, canonical or otherwise")
                    .doesNotContain(":" + QUEUE)
                    .doesNotContain(":-");
        }

        @Test
        @DisplayName("the shell's queue-name bound and SqsNamingRules' bound are the same number")
        void theQueueNameBoundAgreesAcrossBothLanguages() throws IOException {
            // The bootstrap and the producer validate the same queue name in two languages, and
            // nothing but this assertion holds the two numbers together. If one side is relaxed the
            // other silently becomes the only gate, and the gap reopens exactly where it was found:
            // a name the shell refuses to provision that the producer accepts at start-up.
            assertThat(boundedValueLimitFor("the queue name"))
                    .as("the bootstrap's queue-name maximum must be the number "
                            + "SqsNamingRules.QUEUE_NAME_MAX_LENGTH enforces in Java")
                    .isEqualTo(SqsNamingRules.QUEUE_NAME_MAX_LENGTH);
        }

        @Test
        @DisplayName("the shell's message-group bound and SqsNamingRules' bound are the same number")
        void theMessageGroupBoundAgreesAcrossBothLanguages() throws IOException {
            assertThat(boundedValueLimitFor("the message group id"))
                    .as("the bootstrap's message-group maximum must be the number "
                            + "SqsNamingRules.MESSAGE_GROUP_ID_MAX_LENGTH enforces in Java")
                    .isEqualTo(SqsNamingRules.MESSAGE_GROUP_ID_MAX_LENGTH);
        }

        @Test
        @DisplayName("both languages admit the same character set, and refuse the same one")
        void theCharacterSetAgreesAcrossBothLanguages() throws IOException {
            // The shell expresses its rule as a negated glob bracket; Java expresses it as a
            // predicate. They cannot be compared textually, so the bracket is read out of the script
            // and every character it names is put to the Java predicate - and a representative
            // sample of what the bracket excludes is put to it too, so agreement is proved in both
            // directions rather than only on the permitted side.
            final String scriptText = read(SCRIPT_PATH);
            assertThat(scriptText)
                    .as("the script must still express its character rule as the negated set this "
                            + "assertion reads, for both the queue name and the message group id")
                    .contains("*[!A-Za-z0-9._-]*)");

            final String permitted = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
                    + "0123456789._-";
            final List<Character> refusedByJava = new ArrayList<>();
            for (final char candidate : permitted.toCharArray()) {
                if (!SqsNamingRules.isPermittedNameCharacter(candidate)) {
                    refusedByJava.add(candidate);
                }
            }
            assertThat(refusedByJava)
                    .as("every character the bootstrap's [A-Za-z0-9._-] set admits must be admitted "
                            + "by SqsNamingRules.isPermittedNameCharacter")
                    .isEmpty();

            final List<Character> admittedByJava = new ArrayList<>();
            for (final char candidate : " !\"#$%&'()*+,/:;<=>?@[\\]^`{|}~\t\n".toCharArray()) {
                if (SqsNamingRules.isPermittedNameCharacter(candidate)) {
                    admittedByJava.add(candidate);
                }
            }
            assertThat(admittedByJava)
                    .as("no character outside the bootstrap's set may be admitted by "
                            + "SqsNamingRules.isPermittedNameCharacter")
                    .isEmpty();
        }

        @Test
        @DisplayName("both languages require the same .fifo suffix, and the canonical name clears both")
        void theFifoSuffixRequirementAgreesAcrossBothLanguages() throws IOException {
            assertThat(read(SCRIPT_PATH))
                    .as("the bootstrap must still refuse a queue name that omits the suffix")
                    .contains("does not end in .fifo");
            assertThat(SqsNamingRules.FIFO_SUFFIX)
                    .as("Java must require the same suffix literal the bootstrap requires")
                    .isEqualTo(".fifo");

            // The agreement is only worth having if the value both sides actually carry satisfies
            // it. This is the one assertion that puts the canonical name itself through the Java
            // contract, in each of the three destination forms the producer accepts.
            assertThat(SqsNamingRules.requireQueueDestination(QUEUE, "probe"))
                    .as("the canonical bare queue name must satisfy the producer's contract")
                    .isEqualTo(QUEUE);
            assertThat(SqsNamingRules.requireQueueDestination(
                            "http://localhost:4566/000000000000/" + QUEUE, "probe"))
                    .as("the emulator's own URL form of the canonical name must satisfy it too")
                    .endsWith(QUEUE);
            assertThat(SqsNamingRules.requireQueueDestination(
                            "arn:aws:sqs:us-east-1:000000000000:" + QUEUE, "probe"))
                    .as("the ARN form of the canonical name must satisfy it too")
                    .endsWith(QUEUE);
            assertThat(SqsNamingRules.requireMessageGroupId(MESSAGE_GROUP, "probe"))
                    .as("the canonical message group id must satisfy the producer's contract")
                    .isEqualTo(MESSAGE_GROUP);
        }

        /**
         * Reads the length bound the bootstrap applies to one named value out of the script itself.
         *
         * <p>The number is taken from the script rather than restated here, because a restated
         * number agrees with Java while disagreeing with the shell - which is the drift these
         * assertions exist to catch.</p>
         *
         * @param valueLabel the label the script passes to {@code require_bounded_value}
         * @return the bound the script applies to that value
         * @throws IOException if the script cannot be read
         */
        private int boundedValueLimitFor(final String valueLabel) throws IOException {
            final String call = "require_bounded_value '" + valueLabel + "' ";
            for (final String line : read(SCRIPT_PATH).split("\n", -1)) {
                final String trimmed = line.strip();
                if (trimmed.startsWith(call)) {
                    final String[] words = trimmed.split(" ");
                    return Integer.parseInt(words[words.length - 1]);
                }
            }
            throw new AssertionError("the bootstrap no longer bounds " + valueLabel
                    + " with require_bounded_value; the cross-language agreement cannot be checked");
        }

        @Test
        @DisplayName("the canonical queue name is still recorded in production, as documentation")
        void theCanonicalQueueNameIsStillRecordedInProduction() throws IOException {
            // Undefaulted is not the same as unrecorded. A deployment has to be told the value, so the
            // value has to be written down somewhere it will be read - and it is, in the production
            // overlay's own environment inventory. This is what stops the previous assertion from
            // being read as "production does not know the queue name".
            assertThat(read("src/main/resources/application-prod.yml"))
                    .as("production must still document the canonical queue name for a deployer")
                    .contains("CARDDEMO_SQS_QUEUE")
                    .contains(QUEUE);
        }
    }

    @Nested
    @DisplayName("the endpoint and region the resources are reached through")
    final class TheEndpointAndRegionBinding {

        /** The exact endpoint expression the emulator-facing overlays bind. */
        private static final String EMULATOR_ENDPOINT =
                "http://${LOCALSTACK_HOST:localhost}:${LOCALSTACK_PORT:4566}";

        @Test
        @DisplayName("the local overlay redirects the endpoint globally and again for every client")
        void theLocalOverlayRedirectsGloballyAndPerClient() throws IOException {
            // Deliberate belt and braces, and the overlay says so in place: the override is applied
            // globally AND restated for each of the three clients, so that dropping the global setting
            // cannot let a client fall through to a real AWS endpoint. That failure would be
            // particularly bad in this direction - local traffic reaching a real account - so the
            // redundancy is a safety property and all four bindings are pinned.
            // Counted by the value bound rather than by the key name: `endpoint` is also the key of
            // the trace-collector address under an unrelated subsystem in the same file, so a census
            // keyed on the name alone would conflate two different things.
            final List<String> emulatorFacing =
                    bindingLines("src/main/resources/application-local.yml", "endpoint:").stream()
                            .filter(binding -> binding.equals("endpoint: " + EMULATOR_ENDPOINT))
                            .toList();

            assertThat(emulatorFacing)
                    .as("the global override plus one per client for the object store, the queue "
                            + "and the topic")
                    .hasSize(1 + PROVISIONED_RESOURCE_COUNT);
        }

        @Test
        @DisplayName("the emulator host and port are both redirectable, so parallel stacks cannot collide")
        void theEmulatorHostAndPortAreRedirectable() throws IOException {
            // The port has to be movable because more than one stack may run on one host. Both halves
            // of the address are environment-substituted with the canonical default, and the stack
            // definition maps the same variable onto the container's fixed edge port.
            //
            // This expectation used to be the whole mapping, "${LOCALSTACK_PORT:-4566}:4566". That shape
            // published the edge port on EVERY host interface, which handed a network peer an emulator
            // holding this module's staged batch files and its job-submission queue. The mapping now
            // carries a host address as well, so the assertion is written as the two properties it
            // actually cares about - the port is still redirectable through the same variable, and the
            // binding is loopback by default - rather than as one literal that conflates them and goes
            // stale the moment either changes. LocalValidationStackExposureTest owns the binding rule for
            // every service; this asserts it for the one whose endpoint contract lives in this file.
            assertThat(EMULATOR_ENDPOINT)
                    .contains("${LOCALSTACK_HOST:localhost}")
                    .contains("${LOCALSTACK_PORT:4566}");
            assertThat(read(COMPOSE_PATH))
                    .as("the stack definition must publish the edge port through the same variable, "
                            + "bound to the loopback interface by default")
                    .contains("\"${LOCALSTACK_BIND_ADDRESS:-127.0.0.1}:${LOCALSTACK_PORT:-4566}:4566\"");
        }

        @Test
        @DisplayName("the base profile redirects nothing, so only local and test face the emulator")
        void theBaseProfileRedirectsNothing() throws IOException {
            // The important direction. If the base profile pointed at an emulator, every profile would
            // inherit that and a deployment would silently publish nowhere real. The base therefore
            // binds no AWS endpoint override at all, and the emulator address appears only in the two
            // overlays that are meant to face one.
            final String base = read("src/main/resources/application.yml");

            assertThat(base)
                    .as("the base profile must not name the emulator")
                    .doesNotContain("LOCALSTACK_HOST")
                    .doesNotContain("LOCALSTACK_PORT")
                    .doesNotContain(":4566");
            // Judged by what each binding points at, not by the key name. The base legitimately binds a
            // trace-collector address under the same key in an unrelated subsystem, so a census keyed on
            // the name alone would conflate two different things. What must be absent is any binding
            // whose VALUE faces the emulator.
            final Predicate<String> facesTheEmulator =
                    binding -> binding.contains("LOCALSTACK") || binding.contains(":4566");

            assertThat(bindingLines(base.lines().toList(), "endpoint:"))
                    .as("no endpoint the base profile binds may face the emulator")
                    .noneMatch(facesTheEmulator);
            // The predicate above would also be satisfied by a census that found nothing at all, so it
            // is shown to discriminate: the same predicate over the overlay that IS meant to face an
            // emulator matches there.
            assertThat(bindingLines("src/main/resources/application-local.yml", "endpoint:"))
                    .as("the same predicate must match where an emulator is deliberately named")
                    .anyMatch(facesTheEmulator);
        }

        @Test
        @DisplayName("production faces no emulator and defaults no region")
        void productionFacesNoEmulatorAndDefaultsNoRegion() throws IOException {
            // Two properties in one place because they share a reason. A production overlay that named
            // an emulator would be catastrophic, and a defaulted region would silently place every
            // resource in a region nobody chose - so the region follows the same no-fallback discipline
            // as the queue name.
            final String production = read("src/main/resources/application-prod.yml");

            assertThat(production)
                    .as("production must not name the emulator in any form")
                    .doesNotContain("LOCALSTACK")
                    .doesNotContain("localhost:4566")
                    .doesNotContain(":4566");
            assertThat(bindingLine("src/main/resources/application-prod.yml", "region: "))
                    .as("production must require the region to be supplied explicitly, at the key the"
                            + " settings type binds and the start-up guard watches")
                    .isEqualTo("region: ${AWS_REGION}")
                    .doesNotContain(":" + REGION);
            assertThat(bindingLine("src/main/resources/application-prod.yml", "static:"))
                    .as("and the AWS integration's own region setting must DERIVE from that key rather"
                            + " than restate the variable, so one environment variable is read in one"
                            + " place and the two namespaces cannot name different regions")
                    .isEqualTo("static: ${carddemo.aws.region}");
        }

        @Test
        @DisplayName("path-style addressing is on for the emulator and off for production")
        void pathStyleAddressingIsOnForTheEmulatorAndOffForProduction() throws IOException {
            // Not a preference: the emulator does not serve virtual-hosted bucket subdomains, so a
            // client that used them would fail against it, while a real account prefers them. The two
            // settings are therefore opposite by necessity, and getting either backwards breaks only
            // one environment - which is exactly the kind of asymmetry that survives unnoticed.
            assertThat(bindingLine("src/main/resources/application-local.yml",
                    "path-style-access-enabled:"))
                    .isEqualTo("path-style-access-enabled: true");
            assertThat(bindingLine("src/main/resources/application-test.yml",
                    "path-style-access-enabled:"))
                    .isEqualTo("path-style-access-enabled: true");
            assertThat(bindingLine("src/test/resources/application-test.yml",
                    "path-style-access-enabled:"))
                    .isEqualTo("path-style-access-enabled: true");
            assertThat(bindingLine("src/main/resources/application-prod.yml",
                    "path-style-access-enabled:"))
                    .as("production talks to a real endpoint, which serves virtual-hosted subdomains")
                    .isEqualTo("path-style-access-enabled: false");
        }

        @Test
        @DisplayName("the region the bootstrap provisions in is the region every overlay binds")
        void theRegionAgreesBetweenTheBootstrapAndEveryOverlay() throws IOException {
            // The resources are created in one region and looked up in another if these disagree, and
            // the symptom is a resource that "does not exist" while being plainly visible in a console.
            assertThat(executableBody())
                    .as("the bootstrap must default to the canonical region")
                    .contains("${AWS_DEFAULT_REGION:-" + REGION + "}");
            assertThat(read(COMPOSE_PATH))
                    .as("the stack definition must pass the same region into the container")
                    .contains("AWS_DEFAULT_REGION: ${AWS_REGION:-" + REGION + "}");

            assertThat(bindingLine("src/main/resources/application.yml", "region: "))
                    .as("the shared baseline is the one place the region is stated for every profile"
                            + " but production, and it must default to the canonical value")
                    .isEqualTo("region: ${AWS_REGION:" + REGION + "}");
            assertThat(bindingLine("src/test/resources/application-test.yml", "region: "))
                    .as("the suite overlay states the region as a plain value, needing no environment")
                    .isEqualTo("region: " + REGION);

            // Every overlay's AWS-integration region derives from that one statement rather than
            // restating the variable. A restatement is what let an earlier revision hold two
            // independently editable copies of one fact.
            for (final String overlay : List.of(
                    "src/main/resources/application.yml",
                    "src/main/resources/application-local.yml",
                    "src/main/resources/application-test.yml",
                    "src/test/resources/application-test.yml")) {
                assertThat(bindingLine(overlay, "static:"))
                        .as("%s must derive the client region from carddemo.aws.region", overlay)
                        .isEqualTo("static: ${carddemo.aws.region}");
            }
        }
    }

    @Nested
    @DisplayName("the property keys the header's table names")
    final class ThePropertyKeysTheHeaderNames {

        /**
         * The five resources the bootstrap concerns itself with, each paired with the key path the
         * application binds it from.
         *
         * <p>Read from the settings type's own published constants rather than written out as literals.
         * That is the whole mechanism: a rename in {@code AwsProperties} moves these values, the table in
         * the script does not move with them, and this suite fails until someone edits the table. Written
         * as literals, this map would have drifted in exactly the way the table drifted.
         */
        private Map<String, String> boundKeyByResource() {
            final Map<String, String> keys = new LinkedHashMap<>();
            keys.put("object-store bucket", AwsProperties.S3.BATCH_STAGING_BUCKET_PROPERTY);
            keys.put("submission queue", AwsProperties.Sqs.JOB_QUEUE_PROPERTY);
            keys.put("message group id", AwsProperties.Sqs.MESSAGE_GROUP_ID_PROPERTY);
            keys.put("notification topic", AwsProperties.Sns.JOB_NOTIFICATION_TOPIC_PROPERTY);
            keys.put("region", AwsProperties.REGION_PROPERTY);
            return keys;
        }

        @Test
        @DisplayName("the table names the key path of every resource, taken from the settings type")
        void theTableNamesTheKeyPathOfEveryResource() throws IOException {
            final Map<String, String> tabled = tabledKeys();

            assertThat(tabled)
                    .as("the table an operator reads to find out which key to set must name one key per"
                            + " resource and no others")
                    .containsExactlyInAnyOrderEntriesOf(boundKeyByResource());
        }

        @Test
        @DisplayName("every key the table names is a key the shared baseline actually declares")
        void everyTabledKeyIsDeclaredByTheSharedBaseline() throws IOException {
            final Object baseline = new Yaml().load(read("src/main/resources/application.yml"));

            for (final String key : tabledKeys().values()) {
                assertThat(resolves(baseline, key))
                        .as("%s is named in the bootstrap header, so some profile must declare it -"
                                + " and the shared baseline is the document the header calls"
                                + " authoritative", key)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("a key the header names that no profile declares must say on its own line that it "
                + "is withdrawn")
        void aKeyNoProfileDeclaresMustSayItIsWithdrawn() throws IOException {
            final List<Object> profiles = new ArrayList<>();
            for (final String overlay : OVERLAYS_BINDING_THE_NAMES) {
                profiles.add(new Yaml().load(read(overlay)));
            }
            final List<String> unexplained = new ArrayList<>();

            for (final String line : read(SCRIPT_PATH).split("\n", -1)) {
                final Matcher key = CONFIGURATION_KEY.matcher(line);
                while (key.find()) {
                    final String named = key.group();
                    final boolean declared = profiles.stream()
                            .anyMatch(profile -> resolves(profile, named));
                    if (!declared && !line.contains("withdrawn")) {
                        unexplained.add(named + " -> " + line.strip());
                    }
                }
            }

            assertThat(unexplained)
                    .as("naming a key that binds nothing sends a reader to a setting that has no effect;"
                            + " if the header has to mention one for history, the line must say so")
                    .isEmpty();
        }

        @Test
        @DisplayName("the two withdrawn spellings survive only as history, never in the table")
        void theTwoWithdrawnSpellingsSurviveOnlyAsHistory() throws IOException {
            final List<String> withdrawn =
                    List.of("carddemo.aws.s3.bucket", "carddemo.aws.sqs.job-submission-queue");

            for (final String spelling : withdrawn) {
                assertThat(tabledKeys().values())
                        .as("%s named nothing for a whole checkpoint; it must never return to the table",
                                spelling)
                        .doesNotContain(spelling);
            }
        }

        @Test
        @DisplayName("the account and endpoint keys are deliberately outside the table, and the header "
                + "says why")
        void theAccountAndEndpointKeysAreOutsideTheTable() throws IOException {
            final String script = read(SCRIPT_PATH);

            assertThat(tabledKeys().values())
                    .as("neither provisions a resource here, so neither belongs in a resource table")
                    .doesNotContain(AwsProperties.ENDPOINT_OVERRIDE_PROPERTY,
                            AwsResourceTrustVerifier.EXPECTED_ACCOUNT_ID_PROPERTY);
            assertThat(script)
                    .as("a reader who does not find the endpoint override in the table must be told it"
                            + " exists elsewhere rather than left to conclude it was forgotten")
                    .contains(AwsProperties.ENDPOINT_OVERRIDE_PROPERTY)
                    .contains(AwsResourceTrustVerifier.EXPECTED_ACCOUNT_ID_PROPERTY);
        }

        @Test
        @DisplayName("the document the header calls authoritative is the one that declares all five")
        void theAuthoritativeDocumentDeclaresAllFive() throws IOException {
            assertThat(read(SCRIPT_PATH))
                    .as("the local overlay states outright that it inherits the region rather than"
                            + " restating it, so it cannot be the authority for all five")
                    .contains("src/main/resources/application.yml is their authoritative");

            final Object localOverlay = new Yaml().load(read("src/main/resources/application-local.yml"));
            assertThat(resolves(localOverlay, AwsProperties.REGION_PROPERTY))
                    .as("if the local overlay ever does declare the region, this reasoning changes and"
                            + " the header should be revisited rather than quietly left standing")
                    .isFalse();
        }
    }

    /**
     * Reads the resource-to-key table out of the bootstrap header.
     *
     * @return the resource description of each table row, mapped to the key path that row names
     * @throws IOException if the script cannot be read
     */
    private static Map<String, String> tabledKeys() throws IOException {
        final Map<String, String> tabled = new LinkedHashMap<>();
        for (final String line : read(SCRIPT_PATH).split("\n", -1)) {
            final Matcher row = KEY_TABLE_ROW.matcher(line);
            if (row.matches()) {
                tabled.put(row.group(1).strip(), row.group(2).strip());
            }
        }
        assertThat(tabled)
                .as("the header's resource-to-key table must be present and parseable")
                .isNotEmpty();
        return tabled;
    }

    /**
     * Reports whether a dotted key path resolves to a declared entry in a parsed configuration document.
     *
     * @param document the parsed document, or {@code null} for an empty one
     * @param keyPath  the dotted key path to resolve
     * @return {@code true} when every segment resolves and the last one names a value
     */
    private static boolean resolves(final Object document, final String keyPath) {
        Object node = document;
        for (final String segment : keyPath.split("\\.")) {
            if (!(node instanceof Map<?, ?> map) || !map.containsKey(segment)) {
                return false;
            }
            node = map.get(segment);
        }
        return !(node instanceof Map<?, ?>);
    }

    /**
     * Returns every uncommented line in an overlay that begins with the supplied key.
     *
     * @param overlayPath the overlay to search, relative to the module directory
     * @param keyPrefix   the key to find, including its colon
     * @return the trimmed binding lines, in file order
     * @throws IOException if the overlay cannot be read
     */
    private static List<String> bindingLines(final String overlayPath, final String keyPrefix)
            throws IOException {
        return bindingLines(read(overlayPath).lines().toList(), keyPrefix);
    }

    /**
     * Returns every uncommented line among the supplied lines that begins with the supplied key.
     *
     * @param lines     the lines to search
     * @param keyPrefix the key to find, including its colon
     * @return the trimmed binding lines, in order
     */
    private static List<String> bindingLines(final List<String> lines, final String keyPrefix) {
        final List<String> matches = new ArrayList<>();
        for (final String line : lines) {
            final String trimmed = line.strip();
            if (!trimmed.startsWith("#") && trimmed.startsWith(keyPrefix)) {
                matches.add(trimmed);
            }
        }
        return matches;
    }

    /**
     * Returns the single uncommented binding line in an overlay that begins with the supplied key.
     *
     * <p>Comment lines are skipped, because these keys are also discussed in prose in every overlay,
     * and a binding assertion satisfied by a comment would prove nothing. Finding more or fewer than
     * one binding is itself a failure: a duplicated key in a profile is resolved by whichever occurs
     * later, which is not a property any assertion should depend on silently.
     *
     * @param overlayPath the overlay to search, relative to the module directory
     * @param keyPrefix   the key to find, including its colon
     * @return the trimmed binding line
     * @throws IOException if the overlay cannot be read
     */
    private static String bindingLine(final String overlayPath, final String keyPrefix)
            throws IOException {
        final List<String> matches = new ArrayList<>();
        for (final String line : read(overlayPath).split("\n", -1)) {
            final String trimmed = line.strip();
            if (!trimmed.startsWith("#") && trimmed.startsWith(keyPrefix)) {
                matches.add(trimmed);
            }
        }
        assertThat(matches)
                .as("%s must bind %s exactly once outside a comment", overlayPath, keyPrefix)
                .hasSize(1);
        return matches.get(0);
    }

    /**
     * Returns the index of the first line containing the supplied token.
     *
     * @param lines the lines to search
     * @param token the token to find
     * @return the zero-based index, or {@code -1} when no line contains the token
     */
    private static int indexOfLineContaining(final List<String> lines, final String token) {
        return indexOfLineFrom(lines, 0, token);
    }

    /**
     * Returns the index of the first line at or after a starting point that contains the token.
     *
     * @param lines the lines to search
     * @param from  the zero-based index to start from
     * @param token the token to find
     * @return the zero-based index, or {@code -1} when no such line exists
     */
    private static int indexOfLineFrom(final List<String> lines, final int from, final String token) {
        for (int index = Math.max(from, 0); index < lines.size(); index++) {
            if (lines.get(index).contains(token)) {
                return index;
            }
        }
        return -1;
    }
}
