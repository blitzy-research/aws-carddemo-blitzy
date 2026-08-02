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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards the local emulator's bootstrap script against log forging.
 *
 * <p>{@code localstack/init/01-create-aws-resources.sh} provisions the three AWS resources the module
 * expects locally, and it has no instrumentation other than the lines it prints. The Gate 5 runbook in
 * {@code carddemo-java/README.md} reads those lines back out of the emulator's container log, which makes
 * them evidence rather than decoration - and the most valuable line to forge is the completion record at
 * the end of the file, which an operator reads as proof that all three resources exist. Five of the names
 * the script prints may be redirected by the environment, so before the fix a value such as
 * {@code CARDDEMO_S3_BUCKET='x\n[carddemo-init] AWS resource bootstrap complete: ...'} produced exactly
 * that record from a run that provisioned nothing.
 *
 * <p>Two independent properties close it, and both are asserted here. The first is ordering: every name is
 * checked <em>before</em> the first line is printed, because validating afterwards would leave the opening
 * line - which interpolates four of the five names - permanently unguarded. That property is structural and
 * is proved by reading the script, with no shell involved, so it holds even where the behavioural arms are
 * skipped. The second is behaviour: a rejected name produces exactly one line, on the error stream, naming
 * the variable and never the value.
 *
 * <p>The script is executed rather than paraphrased. Its guard is a dozen lines of POSIX shell, and a test
 * that asserted on the text of the guard instead of its effect would keep passing through a rewrite that
 * read the same and behaved differently. Executing it is safe and offline in both directions: a rejection
 * exits before the first {@code awslocal} call, and the admission arm runs with {@code PATH} pointing at an
 * empty directory, so {@code awslocal} is unreachable by construction and the script dies at its first
 * invocation having printed only its opening records. No container, no network and no credential is
 * involved, on any arm.
 *
 * <p>The interpreter is named by absolute path even though the script carries a {@code #!/usr/bin/env bash}
 * shebang, because {@code env} resolves {@code bash} through {@code PATH} and these tests deliberately empty
 * {@code PATH} to make the tooling unreachable. Naming the interpreter directly keeps both properties at
 * once. That the shebang and the executable bit are nonetheless intact - which is how the emulator's hook
 * runner actually starts the file - is asserted separately.
 *
 * <p>This is the only {@link ProcessBuilder} in the module, and it is in a test. The Gate 6 audit that
 * commits to a count of zero is scoped to {@code src/main/java}, where no process is spawned and none is
 * needed; the count there is unaffected by this file. Spawning one here is what makes a shell guard
 * testable at all.
 */
@DisplayName("LocalStack bootstrap script :: the provisioning log cannot be forged")
class LocalStackBootstrapScriptSecurityTest {

    /** The script under test, resolved relative to the module directory the test runner works in. */
    private static final String SCRIPT_PATH = "localstack/init/01-create-aws-resources.sh";

    /** The interpreter the script's shebang declares, named absolutely so an empty PATH cannot hide it. */
    private static final String BASH_INTERPRETER = "/bin/bash";

    /** The shebang line the emulator's hook runner uses to start the file. */
    private static final String EXPECTED_SHEBANG = "#!/usr/bin/env bash";

    /** How long the script is given before the run is treated as hung. It normally finishes in milliseconds. */
    private static final long SCRIPT_TIMEOUT_SECONDS = 30L;

    /** The prefix every emitted line carries so it can be found in a shared container log. */
    private static final String LOG_PREFIX = "[carddemo-init] ";

    /** The start of the opening record, which interpolates four of the five environment-controlled names. */
    private static final String OPENING_RECORD_PREFIX = LOG_PREFIX + "bootstrap starting: ";

    /**
     * The record an operator reads as proof the stack is ready, and therefore the one worth forging.
     *
     * <p>It is the closing line of the script verbatim. That matters on both arms: a forgery is only
     * interesting if it targets the exact text a reviewer greps for, and the admission arm asserts this
     * text is absent from a run that provisioned nothing, which a paraphrase could never have detected.
     */
    private static final String COMPLETION_RECORD = LOG_PREFIX
            + "AWS resource bootstrap complete: 3 of 3 resources verified (queue, bucket, topic)";

    /** The marker every refusal carries. */
    private static final String FATAL_MARKER = "FATAL:";

    /** The five environment variables that may redirect a printed name, in the order the script reads them. */
    private static final List<String> GUARDED_VARIABLES = List.of(
            "AWS_DEFAULT_REGION",
            "CARDDEMO_S3_BUCKET",
            "CARDDEMO_SQS_QUEUE",
            "CARDDEMO_SQS_MESSAGE_GROUP_ID",
            "CARDDEMO_SNS_TOPIC");

    /** The canonical opening record, byte for byte, when nothing redirects a name. */
    private static final String CANONICAL_OPENING_RECORD = OPENING_RECORD_PREFIX
            + "region=us-east-1 bucket=carddemo-batch-staging queue=JOBS.fifo "
            + "topic=carddemo-job-notifications";

    /** The accepted character set, quoted in the refusal so an operator knows what to correct to. */
    private static final String ACCEPTED_SET = "[A-Za-z0-9._-]";

    /**
     * A distinctive payload that appears in no diagnostic this script can emit. Every six-character window
     * of it contains at least one letter of the marker, so a fragment of it turning up in the output is
     * unambiguously an echo of the rejected value and never a coincidence with the message text.
     */
    private static final String HOSTILE_MARKER = "QAMARKFORGEDADMIN";

    /** A line feed: ends a log record, so text after it appears to be a separate entry. */
    private static final String LINE_FEED = "\n";

    /** A carriage return: returns the cursor to column one, so it can overwrite a record already written. */
    private static final String CARRIAGE_RETURN = "\r";

    /** A tab: shifts a reader's column alignment and can imitate a field separator. */
    private static final String TAB = "\t";

    /** An escape: begins a terminal control sequence, which can rewrite lines a reader has already seen. */
    private static final String ESCAPE = "\u001B";

    /** A delete: not whitespace and not printable, and invisible in most log viewers. */
    private static final String DELETE = "\u007F";

    /** A NUL: terminates a C string, so a consumer written in C sees a truncated record. */
    private static final String NUL = "\u0000";

    /** Locates every {@code ${NAME:-default}} expansion, which is how the script reads the environment. */
    private static final Pattern ENVIRONMENT_DEFAULT = Pattern.compile("\\$\\{([A-Z0-9_]+):-");

    /** Locates every invocation of the guard, capturing the variable name it reports. */
    private static final Pattern GUARD_CALL = Pattern.compile("^require_safe_name\\s+([A-Za-z0-9_]+)\\s");

    /**
     * An empty directory used as the sole {@code PATH} entry, so {@code awslocal} cannot be found however the
     * host is provisioned. This is what keeps the admission arm offline and its outcome identical everywhere.
     */
    @TempDir
    static Path emptyToolDirectory;

    /** The outcome of one run: the exit status and each output stream, captured separately. */
    private record ScriptRun(int exitCode, String standardOutput, String standardError) {

        /** Both streams together, for assertions that do not care which one carried the text. */
        String everything() {
            return standardOutput + standardError;
        }
    }

    /**
     * Runs the script with a cleared environment plus the supplied overrides.
     *
     * <p>Both streams are redirected to files rather than read from pipes, so no assertion depends on the
     * child's output being small enough to fit a pipe buffer.
     */
    private static ScriptRun run(Map<String, String> overrides) throws IOException, InterruptedException {
        Assumptions.assumeTrue(Files.isExecutable(Path.of(BASH_INTERPRETER)),
                BASH_INTERPRETER + " is not executable here, so the shell guard cannot be exercised");
        Path outFile = Files.createTempFile("carddemo-bootstrap-stdout", ".log");
        Path errFile = Files.createTempFile("carddemo-bootstrap-stderr", ".log");
        try {
            ProcessBuilder builder = new ProcessBuilder(BASH_INTERPRETER, SCRIPT_PATH);
            builder.environment().clear();
            builder.environment().put("PATH", emptyToolDirectory.toString());
            builder.environment().putAll(overrides);
            builder.redirectOutput(outFile.toFile());
            builder.redirectError(errFile.toFile());
            Process process = builder.start();
            if (!process.waitFor(SCRIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("The bootstrap script did not finish within "
                        + SCRIPT_TIMEOUT_SECONDS + " seconds");
            }
            return new ScriptRun(process.exitValue(),
                    Files.readString(outFile, StandardCharsets.UTF_8),
                    Files.readString(errFile, StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(outFile);
            Files.deleteIfExists(errFile);
        }
    }

    /** Runs the script with one environment variable redirected to a hostile value. */
    private static ScriptRun runWith(String variable, String value) throws IOException, InterruptedException {
        return run(Map.of(variable, value));
    }

    /** The script's source, read as UTF-8. */
    private static List<String> scriptLines() throws IOException {
        return Files.readAllLines(Path.of(SCRIPT_PATH), StandardCharsets.UTF_8);
    }

    /** True when a line is a shell comment, which the structural assertions must not read as code. */
    private static boolean isComment(String line) {
        return line.stripLeading().startsWith("#");
    }

    @Nested
    @DisplayName("the guard runs before anything is printed")
    class TheGuardPrecedesEveryLogLine {

        @Test
        @DisplayName("the script is an executable bash program, started by its shebang in the container")
        void theScriptIsAnExecutableBashProgram() throws IOException {
            Path script = Path.of(SCRIPT_PATH);
            assertThat(script).isRegularFile();
            assertThat(Files.isExecutable(script))
                    .as("the emulator's hook runner executes the file directly, so the mode bit is contractual")
                    .isTrue();
            assertThat(scriptLines().getFirst()).isEqualTo(EXPECTED_SHEBANG);
        }

        @Test
        @DisplayName("every name check precedes the first log invocation, not merely the resource calls")
        void everyNameCheckPrecedesTheFirstLogInvocation() throws IOException {
            List<String> lines = scriptLines();
            int firstLogInvocation = -1;
            for (int index = 0; index < lines.size() && firstLogInvocation < 0; index++) {
                String line = lines.get(index);
                if (!isComment(line) && line.stripLeading().startsWith("log ")) {
                    firstLogInvocation = index;
                }
            }
            assertThat(firstLogInvocation)
                    .as("the script must still print something, or there is nothing to protect")
                    .isGreaterThan(0);

            for (int index = 0; index < lines.size(); index++) {
                Matcher matcher = GUARD_CALL.matcher(lines.get(index));
                if (!isComment(lines.get(index)) && matcher.find()) {
                    assertThat(index)
                            .as("the check of %s must precede the opening record at line %d",
                                    matcher.group(1), firstLogInvocation + 1)
                            .isLessThan(firstLogInvocation);
                }
            }
        }

        @Test
        @DisplayName("every environment-controlled name is checked, so a sixth cannot be added unguarded")
        void everyEnvironmentControlledNameIsChecked() throws IOException {
            Set<String> readFromEnvironment = new LinkedHashSet<>();
            Set<String> checked = new LinkedHashSet<>();
            for (String line : scriptLines()) {
                if (isComment(line)) {
                    continue;
                }
                Matcher expansion = ENVIRONMENT_DEFAULT.matcher(line);
                while (expansion.find()) {
                    readFromEnvironment.add(expansion.group(1));
                }
                Matcher call = GUARD_CALL.matcher(line);
                if (call.find()) {
                    checked.add(call.group(1));
                }
            }
            assertThat(readFromEnvironment).containsExactlyElementsOf(GUARDED_VARIABLES);
            assertThat(checked)
                    .as("a name the script prints but does not check is the whole defect, restored")
                    .containsExactlyElementsOf(readFromEnvironment);
        }

        @Test
        @DisplayName("the refusal cannot echo the rejected value, because the guard never reads it out")
        void theRefusalCannotEchoTheRejectedValue() throws IOException {
            List<String> lines = scriptLines();
            int bodyStart = -1;
            int bodyEnd = -1;
            for (int index = 0; index < lines.size(); index++) {
                if (lines.get(index).startsWith("require_safe_name() {")) {
                    bodyStart = index;
                } else if (bodyStart >= 0 && bodyEnd < 0 && lines.get(index).equals("}")) {
                    bodyEnd = index;
                }
            }
            assertThat(bodyStart).as("the guard function must exist").isNotNegative();
            assertThat(bodyEnd).as("the guard function must be closed").isGreaterThan(bodyStart);

            List<String> body = lines.subList(bodyStart, bodyEnd);
            List<String> printfLines = body.stream().filter(line -> line.contains("printf")).toList();
            assertThat(printfLines).as("both refusal branches must report something").hasSize(2);
            for (String line : body) {
                if (isComment(line) || line.contains("case \"$2\" in")) {
                    continue;
                }
                assertThat(line)
                        .as("the value is the one thing that must not reach the diagnostic")
                        .doesNotContain("$2");
            }
        }
    }

    @Nested
    @DisplayName("the recorded exploit")
    class TheRecordedExploit {

        @Test
        @DisplayName("refuses the forged completion record and emits exactly one line")
        void refusesTheForgedCompletionRecordAndEmitsExactlyOneLine() throws IOException, InterruptedException {
            ScriptRun result = runWith("CARDDEMO_S3_BUCKET", "x" + LINE_FEED + COMPLETION_RECORD);

            assertThat(result.exitCode()).isEqualTo(1);
            assertThat(result.everything().lines())
                    .as("one rejection must produce one record; two would be the forgery succeeding")
                    .hasSize(1);
            assertThat(result.everything())
                    .contains(FATAL_MARKER)
                    .contains("CARDDEMO_S3_BUCKET")
                    .doesNotContain(COMPLETION_RECORD)
                    .doesNotContain(OPENING_RECORD_PREFIX);
        }

        @Test
        @DisplayName("prints nothing at all on the evidence stream, so the log stays empty rather than clean")
        void printsNothingOnTheEvidenceStream() throws IOException, InterruptedException {
            ScriptRun result = runWith("CARDDEMO_SNS_TOPIC", "topic" + LINE_FEED + COMPLETION_RECORD);

            assertThat(result.standardOutput())
                    .as("every provisioning record goes to standard output, and none was earned here")
                    .isEmpty();
            assertThat(result.standardError().lines()).hasSize(1);
            assertThat(result.standardError()).contains("CARDDEMO_SNS_TOPIC");
        }
    }

    @Nested
    @DisplayName("every environment-controlled name")
    class EveryEnvironmentControlledName {

        @Test
        @DisplayName("refuses a line feed in each of the five names, reporting that name")
        void refusesALineFeedInEachOfTheFiveNames() throws IOException, InterruptedException {
            for (String variable : GUARDED_VARIABLES) {
                ScriptRun result = runWith(variable, "safe" + LINE_FEED + COMPLETION_RECORD);

                assertThat(result.exitCode()).as("%s must be refused", variable).isEqualTo(1);
                assertThat(result.everything())
                        .as("the refusal must name %s so an operator knows which value to correct", variable)
                        .contains(variable)
                        .doesNotContain(COMPLETION_RECORD);
                assertThat(result.everything().lines()).as("%s produced more than one record", variable)
                        .hasSize(1);
            }
        }

        @Test
        @DisplayName("refuses every control character the environment is able to carry")
        void refusesEveryControlCharacterTheEnvironmentCanCarry() throws IOException, InterruptedException {
            for (String control : List.of(LINE_FEED, CARRIAGE_RETURN, TAB, ESCAPE, DELETE)) {
                ScriptRun result = runWith("CARDDEMO_SQS_QUEUE", "JOBS" + control + ".fifo");

                assertThat(result.exitCode())
                        .as("code point %d must be refused", (int) control.charAt(0))
                        .isEqualTo(1);
                assertThat(result.everything())
                        .contains("CARDDEMO_SQS_QUEUE")
                        .doesNotContain(OPENING_RECORD_PREFIX);
            }
        }

        @Test
        @DisplayName("a NUL cannot even reach the script through the environment, and is still refused")
        void aNulCannotReachTheScriptThroughTheEnvironment() throws IOException {
            ProcessBuilder builder = new ProcessBuilder(BASH_INTERPRETER, SCRIPT_PATH);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a process environment is a list of NUL-terminated strings, so it cannot carry one")
                    .isThrownBy(() -> builder.environment().put("CARDDEMO_S3_BUCKET", "a" + NUL + "b"));

            // The behavioural arms above cannot reach this branch, so the structural one stands in for it:
            // the guard is written as a negated glob class rather than as a list of forbidden characters,
            // which is what makes it refuse a NUL - and everything else outside the set - by construction
            // rather than by enumeration. A rewrite into a denylist would pass every other test here.
            String allowlist = ACCEPTED_SET.substring(1, ACCEPTED_SET.length() - 1);
            String negatedGlob = "*[!" + allowlist + "]*)";
            assertThat(scriptLines())
                    .as("the guard must be an allowlist, so an unforeseen character is refused by default")
                    .anyMatch(line -> !isComment(line) && line.contains(negatedGlob));
        }
    }

    @Nested
    @DisplayName("the rejected value")
    class TheRejectedValueIsWithheld {

        @Test
        @DisplayName("is withheld in whole and in every six-character fragment")
        void isWithheldInWholeAndInEveryFragment() throws IOException, InterruptedException {
            String hostile = HOSTILE_MARKER + " " + HOSTILE_MARKER;
            ScriptRun result = runWith("CARDDEMO_S3_BUCKET", hostile);

            assertThat(result.exitCode()).isEqualTo(1);
            String emitted = result.everything();
            assertThat(emitted).doesNotContain(hostile).doesNotContain(HOSTILE_MARKER);
            for (int start = 0; start + 6 <= hostile.length(); start++) {
                assertThat(emitted)
                        .as("a fragment of the rejected value at offset %d reached the diagnostic", start)
                        .doesNotContain(hostile.substring(start, start + 6));
            }
            assertThat(emitted).contains("CARDDEMO_S3_BUCKET");
        }

        @Test
        @DisplayName("is refused for a printable character too, because the check is an allowlist")
        void isRefusedForAPrintableCharacterToo() throws IOException, InterruptedException {
            for (String hostile : List.of("bucket;rm -rf /", "bucket/path", "bucket$name", "bucket name",
                    "bucket'quote", "bucket\"quote", "bucket`tick", "bucket*glob")) {
                ScriptRun result = runWith("CARDDEMO_S3_BUCKET", hostile);

                assertThat(result.exitCode())
                        .as("a control-character denylist would admit this value; an allowlist must not")
                        .isEqualTo(1);
                assertThat(result.everything()).doesNotContain(OPENING_RECORD_PREFIX);
            }
        }

        @Test
        @DisplayName("is replaced by an actionable diagnostic, not merely suppressed")
        void isReplacedByAnActionableDiagnostic() throws IOException, InterruptedException {
            ScriptRun result = runWith("CARDDEMO_SQS_MESSAGE_GROUP_ID", "group" + CARRIAGE_RETURN + "id");

            String emitted = result.everything();
            assertThat(emitted)
                    .as("withholding the value is only safe if the operator can still act on the message")
                    .contains("CARDDEMO_SQS_MESSAGE_GROUP_ID")
                    .contains(ACCEPTED_SET)
                    .contains("not repeated");
        }
    }

    @Nested
    @DisplayName("the canonical stack")
    class TheCanonicalStackIsStillAdmitted {

        @Test
        @DisplayName("is admitted, and the opening record is emitted byte for byte")
        void isAdmittedAndTheOpeningRecordIsEmittedByteForByte() throws IOException, InterruptedException {
            ScriptRun result = run(Map.of());

            assertThat(result.everything())
                    .as("a guard that refused the agreed names would pass every forging test and break the "
                            + "stack")
                    .doesNotContain(FATAL_MARKER);
            assertThat(result.standardOutput()).contains(CANONICAL_OPENING_RECORD);
            assertThat(result.standardOutput())
                    .as("no resource was provisioned, so the completion record must not appear")
                    .doesNotContain(COMPLETION_RECORD);
        }

        /**
         * Each of the three characters is exercised where a real redirection may legitimately carry it. The
         * dot is carried by the queue name, which is where it is contractually required - a first-in
         * first-out queue name ends in {@code .fifo} - and deliberately not by the topic name, because the
         * notification service admits only letters, digits, hyphens and underscores in a topic name and the
         * script refuses a dotted topic on that ground, ahead of the service. The printing guard admits the
         * dot for every name; the per-service checks then narrow each one to what its own service accepts,
         * and a fixture has to satisfy both to prove the canonical stack still comes up.
         */
        @Test
        @DisplayName("still admits the dot, the underscore and the hyphen a legal redirection needs")
        void stillAdmitsTheDotUnderscoreAndHyphen() throws IOException, InterruptedException {
            ScriptRun result = run(Map.of(
                    "AWS_DEFAULT_REGION", "eu-west-1",
                    "CARDDEMO_S3_BUCKET", "carddemo-batch-staging-2",
                    "CARDDEMO_SQS_QUEUE", "JOBS_2.fifo",
                    "CARDDEMO_SQS_MESSAGE_GROUP_ID", "carddemo_job_submission",
                    "CARDDEMO_SNS_TOPIC", "carddemo_job-notifications-2"));

            assertThat(result.everything()).doesNotContain(FATAL_MARKER);
            assertThat(result.standardOutput()).contains(OPENING_RECORD_PREFIX
                    + "region=eu-west-1 bucket=carddemo-batch-staging-2 queue=JOBS_2.fifo "
                    + "topic=carddemo_job-notifications-2");
            assertThat(result.standardOutput()).contains("carddemo_job_submission");
        }
    }
}
