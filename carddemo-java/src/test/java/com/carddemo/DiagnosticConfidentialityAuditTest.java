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
package com.carddemo;

import com.carddemo.util.SensitiveLogRedactor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audits every diagnostic call site in the delivered application for the two things a log record must
 * never carry: a raw throwable, and a primary account number.
 *
 * <h2>Why an audit over the sources rather than a test per call site</h2>
 *
 * <p>Both defects are properties of a <em>call site</em>, not of a behaviour, and both are introduced by
 * writing one ordinary-looking line: a site passing a caught throwable straight to a logger, or one
 * writing a card number into one. A suite of behavioural tests catches neither, and cannot, because
 * them, and could not have, because every one of those lines executes only on a failure path that a test
 * asserting the failure's <em>outcome</em> never inspects the log of. The only instrument that closes a
 * defect of that shape is one that reads the sources and counts.
 *
 * <p>So this file scans {@code src/main/java} and requires the count to be zero. A future edit that
 * reintroduces either pattern fails here, at the point it is written, rather than being discovered by the
 * next review.
 *
 * <h2>What is wrong with a raw throwable</h2>
 *
 * <p>Handing a logger a throwable renders its message and its stack frames. The message is where a data
 * layer puts the connection string it failed on - user and password included - and where a driver puts the
 * statement text and the bound parameters; the frames disclose the internal structure of the deployment.
 * The shipped appender bounds how much of a trace is rendered, which limits the volume and not the
 * category: the first line, which is the message, is always rendered.
 *
 * <p>The module's sanctioned alternative is {@code util.FailureDiagnostics}, which composes the chain of
 * failure <em>types</em> and reads no message, no frame and no suppressed throwable. Every diagnostic in
 * the module reports {@code failureChain=} instead of the throwable, which keeps a failure classifiable
 * without making it quotable.
 *
 * <h2>What is wrong with a card number</h2>
 *
 * <p>It is a primary account number. The legacy programs write it to the console freely, and that was
 * defensible on a mainframe where the console was an operator-only surface inside the same security
 * boundary as the data; an aggregated log is read, forwarded and retained outside that boundary. The
 * module's answer is a fixed stand-in - never a partial mask, because a fragment of a sixteen-character
 * numeric key is recoverable by enumeration - applied while the diagnostic keeps its wording and its field
 * set, so nothing about the shape of the legacy output is lost.
 *
 * <h2>How each detector is kept honest</h2>
 *
 * <p>Every rule below is paired with a self-check that runs the same detector over a planted snippet and
 * requires it to fire. Without those, a detector broken by a later edit would report zero findings and the
 * audit would pass while measuring nothing - which is the failure mode of every source-scanning test.
 *
 * <p>Provenance: this audit has no legacy antecedent - the estate carries no test harness of any kind.
 */
@DisplayName("Diagnostics carry no raw throwable and no primary account number")
class DiagnosticConfidentialityAuditTest {

    /** Root of the delivered application sources, which is the whole of what this audit governs. */
    private static final Path PRODUCTION_SOURCE_ROOT =
            Path.of("src", "main", "java", "com", "carddemo");

    /** Start of a logging call at any level, on either of the two logger field names in use. */
    private static final Pattern LOGGER_CALL =
            Pattern.compile("\\b(LOG|LOGGER)\\.(trace|debug|info|warn|error)\\s*\\(");

    /** A catch clause and the opening brace of its block, capturing the caught variable's name. */
    private static final Pattern CATCH_CLAUSE = Pattern.compile(
            "catch\\s*\\(\\s*(?:final\\s+)?[\\w.]+(?:\\s*\\|\\s*(?:final\\s+)?[\\w.]+)*\\s+(\\w+)\\s*\\)"
                    + "\\s*\\{");

    /**
     * Marks an argument as possibly carrying a card number.
     *
     * <p>Deliberately without word boundaries, so that the camel-cased accessors -
     * {@code getCardNum}, {@code getXrefCardNum}, {@code getDalytranCardNum},
     * {@code xrefCardNumberKey} - are all caught along with a plain {@code cardNumber} local.
     */
    private static final Pattern CARD_NUMBER_BEARING = Pattern.compile("card_?num", Pattern.CASE_INSENSITIVE);

    /** A reference whose final segment is an upper-snake-case name, which in this module is a constant. */
    private static final Pattern CONSTANT_REFERENCE =
            Pattern.compile("(?:[A-Za-z_$][\\w$]*\\.)*[A-Z][A-Z0-9_]*");

    /** A string literal, escapes included, so one can be removed from an argument without truncating it. */
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"");

    /**
     * Accessors that read a throwable's own narrative, which is the part that quotes a connection string,
     * a statement or a bound parameter. None may appear in a diagnostic anywhere.
     */
    private static final List<String> NARRATIVE_ACCESSORS =
            List.of("getStackTrace(", "printStackTrace(", "getLocalizedMessage(", "getSuppressed(");

    /**
     * Arguments that name a card field without carrying its value, enrolled by exact text.
     *
     * <p>Enrolment is by exact argument text and each entry carries its reason here, so that adding a
     * <em>different</em> card-bearing argument still fails. This is the deliberate alternative to
     * loosening {@link #CARD_NUMBER_BEARING}: a detector narrowed to keep one honest call site quiet stops
     * being able to see the dishonest ones.
     *
     * <ul>
     *   <li>{@code !isBlankScreenField(state.ridCardNumber)} - the card-list browse reports
     *       <em>whether</em> a start key was supplied. The argument is a negated presence predicate whose
     *       value is a boolean; the key itself never reaches the record.</li>
     * </ul>
     */
    private static final Set<String> ENROLLED_CARD_FIELD_ARGUMENTS = Set.of(
            "!isBlankScreenField(state.ridCardNumber)",
            "redactedCardNumber(cardNumber, REDACTED_CARD_NUMBER)",
            "redactedCardNumber(resolved.getXrefCardNum(), REDACTED_CARD_NUMBER)",
            "redactedCardNumber(dailyTransaction.getDalytranCardNum(), REDACTED_CARD_NUMBER)",
            "redactedCardNumber(run.xrefCardNumberKey(), REDACTED_CARD_NUMBER)");

    /**
     * Fewest diagnostics that must report a failure chain.
     *
     * <p>The two rules above are satisfied trivially by a module with no failure diagnostics at all, so
     * this floor asserts that the sanctioned form is genuinely in use and that a future edit cannot satisfy
     * the audit by deleting the reporting rather than sanitising it. The figure is a count of call sites and
     * not a coverage, a threshold or a service level.
     */
    private static final int MINIMUM_FAILURE_CHAIN_SITES = 30;

    /** One diagnostic call site: where it is, and the whole of the call. */
    private record Diagnostic(Path file, int line, String text) {

        /** @return a short description naming the file and line, for a failure message */
        String location() {
            return this.file.getFileName() + ":" + this.line;
        }
    }

    /**
     * Every delivered application source.
     *
     * @return the file paths, ordered arbitrarily
     */
    private static List<Path> productionSources() {
        try (Stream<Path> tree = Files.walk(PRODUCTION_SOURCE_ROOT)) {
            return tree.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .toList();
        } catch (final IOException unreadable) {
            throw new UncheckedIOException("the production source tree could not be read", unreadable);
        }
    }

    /**
     * Reads one source file.
     *
     * @param file the file to read
     * @return its text
     */
    private static String textOf(final Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException("source could not be read: " + file, unreadable);
        }
    }

    /**
     * Finds the index one past the closing parenthesis that matches the one at {@code openIndex}.
     *
     * <p>String and character literals are tracked, including escapes, so a parenthesis or a comma inside
     * a message literal is never mistaken for structure. That is what makes the argument split below
     * reliable on this module's multi-line, concatenated message literals.
     *
     * @param source    the file text
     * @param openIndex index of the opening parenthesis
     * @return the index just past the matching close, or the source length when unbalanced
     */
    private static int endOfCall(final String source, final int openIndex) {
        int depth = 0;
        boolean inString = false;
        boolean inChar = false;
        for (int index = openIndex; index < source.length(); index++) {
            final char current = source.charAt(index);
            if ((inString || inChar) && current == '\\') {
                index++;
                continue;
            }
            if (inString) {
                inString = current != '"';
                continue;
            }
            if (inChar) {
                inChar = current != '\'';
                continue;
            }
            switch (current) {
                case '"' -> inString = true;
                case '\'' -> inChar = true;
                case '(' -> depth++;
                case ')' -> {
                    depth--;
                    if (depth == 0) {
                        return index + 1;
                    }
                }
                default -> {
                    // Any other character is content and carries no structure.
                }
            }
        }
        return source.length();
    }

    /**
     * Collects every diagnostic call in one source.
     *
     * @param file   the file the source came from
     * @param source the file text
     * @return the calls it contains, in source order
     */
    private static List<Diagnostic> diagnosticsIn(final Path file, final String source) {
        final List<Diagnostic> found = new ArrayList<>();
        final Matcher call = LOGGER_CALL.matcher(source);
        while (call.find()) {
            final int open = call.end() - 1;
            final int end = endOfCall(source, open);
            found.add(new Diagnostic(file, lineOf(source, call.start()), source.substring(call.start(), end)));
        }
        return found;
    }

    /**
     * Counts lines up to an offset, so a finding can name a line.
     *
     * @param source the file text
     * @param offset the offset to locate
     * @return the one-based line number
     */
    private static int lineOf(final String source, final int offset) {
        int line = 1;
        for (int index = 0; index < offset; index++) {
            if (source.charAt(index) == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * Splits a call's arguments at the commas that separate them.
     *
     * @param call the whole call text, from the logger name to its closing parenthesis
     * @return the arguments, each trimmed and with its internal line breaks collapsed
     */
    private static List<String> argumentsOf(final String call) {
        final int open = call.indexOf('(');
        final String inside = call.substring(open + 1, call.length() - 1);
        final List<String> arguments = new ArrayList<>();
        final StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean inString = false;
        boolean inChar = false;
        for (int index = 0; index < inside.length(); index++) {
            final char character = inside.charAt(index);
            if ((inString || inChar) && character == '\\') {
                current.append(character).append(index + 1 < inside.length() ? inside.charAt(index + 1) : ' ');
                index++;
                continue;
            }
            if (inString) {
                inString = character != '"';
                current.append(character);
                continue;
            }
            if (inChar) {
                inChar = character != '\'';
                current.append(character);
                continue;
            }
            switch (character) {
                case '"' -> {
                    inString = true;
                    current.append(character);
                }
                case '\'' -> {
                    inChar = true;
                    current.append(character);
                }
                case '(', '[', '{' -> {
                    depth++;
                    current.append(character);
                }
                case ')', ']', '}' -> {
                    depth--;
                    current.append(character);
                }
                case ',' -> {
                    if (depth == 0) {
                        arguments.add(collapse(current.toString()));
                        current.setLength(0);
                    } else {
                        current.append(character);
                    }
                }
                default -> current.append(character);
            }
        }
        if (!current.toString().isBlank()) {
            arguments.add(collapse(current.toString()));
        }
        return arguments;
    }

    /**
     * Collapses the whitespace a wrapped argument carries, so an argument reads as one token sequence.
     *
     * @param argument the raw argument text
     * @return the collapsed text
     */
    private static String collapse(final String argument) {
        return argument.replaceAll("\\s+", " ").trim();
    }

    /**
     * Finds every diagnostic that passes a caught throwable, or reads its narrative, inside the block that
     * caught it.
     *
     * @param file   the file the source came from
     * @param source the file text
     * @return the offending call sites
     */
    private static List<String> rawThrowableFindings(final Path file, final String source) {
        final List<String> findings = new ArrayList<>();
        final Matcher caught = CATCH_CLAUSE.matcher(source);
        while (caught.find()) {
            final String variable = caught.group(1);
            final int blockStart = source.indexOf('{', caught.start());
            final int blockEnd = endOfBlock(source, blockStart);
            for (final Diagnostic diagnostic : diagnosticsIn(file, source.substring(blockStart, blockEnd))) {
                final int line = lineOf(source, blockStart) + diagnostic.line() - 1;
                final String at = file.getFileName() + ":" + line;
                if (argumentsOf(diagnostic.text()).stream().anyMatch(variable::equals)) {
                    findings.add(at + " passes the caught throwable [" + variable + "] to a logger");
                }
                if (diagnostic.text().contains(variable + ".getMessage(")) {
                    findings.add(at + " logs the narrative of the caught throwable [" + variable + "]");
                }
            }
        }
        return findings;
    }

    /**
     * Finds the index one past the brace that matches the one at {@code openIndex}.
     *
     * @param source    the file text
     * @param openIndex index of the opening brace
     * @return the index just past the matching close, or the source length when unbalanced
     */
    private static int endOfBlock(final String source, final int openIndex) {
        int depth = 0;
        boolean inString = false;
        boolean inChar = false;
        for (int index = openIndex; index < source.length(); index++) {
            final char current = source.charAt(index);
            if ((inString || inChar) && current == '\\') {
                index++;
                continue;
            }
            if (inString) {
                inString = current != '"';
                continue;
            }
            if (inChar) {
                inChar = current != '\'';
                continue;
            }
            switch (current) {
                case '"' -> inString = true;
                case '\'' -> inChar = true;
                case '{' -> depth++;
                case '}' -> {
                    depth--;
                    if (depth == 0) {
                        return index + 1;
                    }
                }
                default -> {
                    // Content.
                }
            }
        }
        return source.length();
    }

    /**
     * Reports whether an argument is built entirely from literal text.
     *
     * <p>A message template is the one place a card field's <em>name</em> legitimately appears - the
     * thirteen-field rejected-record event names {@code cardNum={}} and supplies the stand-in at that
     * position - and a literal cannot hold a runtime value, so it is never a disclosure. The check strips
     * every string literal and the concatenation operators between them and asks whether anything
     * substantive is left; an argument that mixes a literal with an expression therefore still reports as
     * value-bearing and is still examined.
     *
     * @param argument the argument text
     * @return {@code true} when the argument is literal text and nothing else
     */
    private static boolean carriesNoRuntimeValue(final String argument) {
        return STRING_LITERAL.matcher(argument).replaceAll("").replace("+", "").isBlank();
    }

    /**
     * Finds every diagnostic argument that could carry a card number.
     *
     * <p>Three kinds of argument are not findings, each for a reason that holds by construction rather
     * than by convention. An argument built only from literal text names a field and cannot hold its value
     * ({@link #carriesNoRuntimeValue}). An argument whose final segment is upper-snake-case is a constant
     * reference in this module - a legacy field name, a sort position, a width, a label, or the redaction
     * stand-in itself - and so is fixed at compile time. Everything else that names a card number is a
     * finding, unless it is enrolled by exact text in {@link #ENROLLED_CARD_FIELD_ARGUMENTS}.
     *
     * @param file   the file the source came from
     * @param source the file text
     * @return the offending call sites
     */
    private static List<String> cardNumberFindings(final Path file, final String source) {
        final List<String> findings = new ArrayList<>();
        for (final Diagnostic diagnostic : diagnosticsIn(file, source)) {
            for (final String argument : argumentsOf(diagnostic.text())) {
                if (!CARD_NUMBER_BEARING.matcher(argument).find()
                        || carriesNoRuntimeValue(argument)
                        || CONSTANT_REFERENCE.matcher(argument).matches()
                        || ENROLLED_CARD_FIELD_ARGUMENTS.contains(argument)) {
                    continue;
                }
                findings.add(diagnostic.location() + " logs a card-bearing argument [" + argument + "]");
            }
        }
        return findings;
    }

    @Nested
    @DisplayName("the detectors themselves are sound, so a misread cannot pass as compliance")
    class TheDetectorsAreSound {

        /** A planted source carrying both defects, used to prove each detector fires. */
        private static final String PLANTED = """
                class Planted {
                    void read() {
                        try {
                            probe();
                        } catch (final RuntimeException unreadable) {
                            LOG.error("read failed", unreadable);
                            LOG.warn("read failed: {}", unreadable.getMessage());
                        }
                        LOG.info("card={}", record.getXrefCardNum());
                        LOG.info("card={}", cardNumber);
                        LOG.info("cardNum={}", REDACTED_CARD_NUMBER);
                        LOG.info("cardNum=" + record.getCardNum());
                    }
                }
                """;

        @Test
        @DisplayName("the raw-throwable detector fires on a planted throwable and on a planted narrative")
        void theRawThrowableDetectorFires() {
            final List<String> findings = rawThrowableFindings(Path.of("Planted.java"), PLANTED);

            assertThat(findings).hasSize(2);
            assertThat(findings.get(0)).contains("passes the caught throwable [unreadable]");
            assertThat(findings.get(1)).contains("logs the narrative of the caught throwable");
        }

        @Test
        @DisplayName("the card-number detector fires on a planted accessor, a planted local and a planted "
                + "concatenation")
        void theCardNumberDetectorFires() {
            final List<String> findings = cardNumberFindings(Path.of("Planted.java"), PLANTED);

            assertThat(findings).hasSize(3);
            assertThat(findings.get(0)).contains("record.getXrefCardNum()");
            assertThat(findings.get(1)).contains("cardNumber");
            assertThat(findings.get(2)).contains("\"cardNum=\" + record.getCardNum()");
        }

        @Test
        @DisplayName("and stays silent on the planted template that names the field and supplies the "
                + "stand-in, which is the shape the fixed diagnostics take")
        void andStaysSilentOnATemplateThatNamesTheField() {
            assertThat(carriesNoRuntimeValue("\"DALYTRAN cardNum={} procTs={}\"")).isTrue();
            assertThat(carriesNoRuntimeValue("\"cardNum={}\" + \" origTs={}\"")).isTrue();
            assertThat(carriesNoRuntimeValue("\"cardNum=\" + cardNumber")).isFalse();
            assertThat(carriesNoRuntimeValue("cardNumber")).isFalse();
            assertThat(cardNumberFindings(Path.of("Planted.java"), PLANTED))
                    .noneMatch(finding -> finding.contains("REDACTED_CARD_NUMBER"));
        }

        @Test
        @DisplayName("the call reader keeps a message literal whole, so a comma inside one is not read as "
                + "an argument boundary")
        void theCallReaderKeepsALiteralWhole() {
            final String call = "LOG.warn(\"a, b, c {}\", value)";

            assertThat(argumentsOf(call)).containsExactly("\"a, b, c {}\"", "value");
        }

        @Test
        @DisplayName("the call reader follows a concatenated multi-line literal to its real end")
        void theCallReaderFollowsAConcatenatedLiteral() {
            final String call = "LOG.warn(\"first {}\"\n        + \" second {}\", one,\n        two)";

            assertThat(argumentsOf(call)).containsExactly("\"first {}\" + \" second {}\"", "one", "two");
        }

        @Test
        @DisplayName("the constant rule recognises a qualified upper-snake reference and rejects a call")
        void theConstantRuleRecognisesAConstant() {
            assertThat(CONSTANT_REFERENCE.matcher("FIELD_TRAN_CARD_NUM").matches()).isTrue();
            assertThat(CONSTANT_REFERENCE.matcher("Processor.FIELD_TRAN_CARD_NUM").matches()).isTrue();
            assertThat(CONSTANT_REFERENCE.matcher("record.getXrefCardNum()").matches()).isFalse();
            assertThat(CONSTANT_REFERENCE.matcher("cardNumber").matches()).isFalse();
        }

        @Test
        @DisplayName("the audit reads a non-trivial number of diagnostics, so an empty scan cannot pass")
        void theAuditReadsTheSources() {
            final long diagnostics = productionSources().stream()
                    .mapToLong(file -> diagnosticsIn(file, textOf(file)).size())
                    .sum();

            assertThat(productionSources()).hasSizeGreaterThan(100);
            assertThat(diagnostics)
                    .as("the estate's console diagnostics became structured events; a scan finding almost "
                            + "none of them is a scan that is not working")
                    .isGreaterThan(200L);
        }
    }

    @Nested
    @DisplayName("no diagnostic hands a logger a raw throwable")
    class NoRawThrowableReachesALogger {

        @Test
        @DisplayName("nowhere in the delivered application, because a throwable's message is where a "
                + "connection string and a statement appear")
        void noRawThrowableAnywhereInTheDeliveredApplication() {
            final List<String> findings = new ArrayList<>();
            for (final Path file : productionSources()) {
                findings.addAll(rawThrowableFindings(file, textOf(file)));
            }

            assertThat(findings)
                    .as("report the classified failure through FailureDiagnostics.failureChainOf instead")
                    .isEmpty();
        }

        @Test
        @DisplayName("and no diagnostic reads a throwable's frames or its suppressed failures either")
        void andNoDiagnosticReadsFramesOrSuppressed() {
            final List<String> findings = new ArrayList<>();
            for (final Path file : productionSources()) {
                final String source = textOf(file);
                for (final Diagnostic diagnostic : diagnosticsIn(file, source)) {
                    for (final String accessor : NARRATIVE_ACCESSORS) {
                        if (diagnostic.text().contains(accessor)) {
                            findings.add(diagnostic.location() + " reads " + accessor + ')');
                        }
                    }
                }
            }

            assertThat(findings).isEmpty();
        }

        @Test
        @DisplayName("and the sanctioned form is genuinely in use, so the rule above is not satisfied by "
                + "having stopped reporting failures")
        void andTheSanctionedFormIsInUse() {
            final long reportingSites = productionSources().stream()
                    .mapToLong(file -> diagnosticsIn(file, textOf(file)).stream()
                            .filter(diagnostic -> diagnostic.text().contains("failureChain="))
                            .count())
                    .sum();

            assertThat(reportingSites).isGreaterThanOrEqualTo(MINIMUM_FAILURE_CHAIN_SITES);
        }
    }

    @Nested
    @DisplayName("no diagnostic carries a primary account number")
    class NoCardNumberReachesALogger {

        @Test
        @DisplayName("nowhere in the delivered application, whether as an accessor, a local or a field")
        void noCardNumberAnywhereInTheDeliveredApplication() {
            final List<String> findings = new ArrayList<>();
            for (final Path file : productionSources()) {
                findings.addAll(cardNumberFindings(file, textOf(file)));
            }

            assertThat(findings)
                    .as("emit the fixed stand-in instead; a partial mask is not an alternative, because a "
                            + "fragment of a sixteen-character numeric key is recoverable by enumeration")
                    .isEmpty();
        }

        @Test
        @DisplayName("and the two batch programs that displayed one still write every diagnostic, now "
                + "emitting the stand-in, so only the value is withheld and not the record")
        void andTheStandInIsGenuinelyEmitted() {
            assertThat(standInDiagnosticsIn("DailyTransactionReadService.java"))
                    .as("the unverified-card warning, the resolved-card trace, and the thirteen-field "
                            + "rejected-record event that the legacy program's DISPLAY statements write")
                    .isEqualTo(3);
            assertThat(standInDiagnosticsIn("TransactionReportService.java"))
                    .as("the unresolvable-cross-reference diagnostic")
                    .isEqualTo(1);
        }

        /**
         * Counts the diagnostics in one service that emit the card-number stand-in.
         *
         * <p>Counts <em>call sites</em> rather than textual occurrences, so the constant's own declaration
         * and the javadoc that cross-references it do not inflate the figure.
         *
         * @param fileName simple file name of the service, which lives under {@code service}
         * @return how many of its diagnostics emit the stand-in
         */
        private static long standInDiagnosticsIn(final String fileName) {
            final Path file = PRODUCTION_SOURCE_ROOT.resolve(Path.of("service", fileName));
            return diagnosticsIn(file, textOf(file)).stream()
                    .filter(diagnostic -> diagnostic.text().contains("REDACTED_CARD_NUMBER"))
                    .count();
        }

        @Test
        @DisplayName("and the stand-in is spelled exactly as the screen services already spell it, so one "
                + "search finds every value the module withholds")
        void andTheStandInIsSpelledIdentically() {
            final Pattern declaration = Pattern.compile(
                    "\\b(?:REDACTED_CARD_NUMBER|REDACTION_PLACEHOLDER)\\s*=\\s*\"([^\"]*)\"");
            final List<String> spellings = new ArrayList<>();
            for (final Path file : productionSources()) {
                final Matcher declared = declaration.matcher(textOf(file));
                while (declared.find()) {
                    spellings.add(declared.group(1));
                }
            }

            assertThat(spellings)
                    .as("a second spelling would leave one of the two unsearchable")
                    .hasSizeGreaterThan(20)
                    .allSatisfy(spelling -> assertThat(spelling).isEqualTo("***REDACTED***"));
        }
    }

    @Nested
    @DisplayName("no diagnostic on the batch tier's record paths carries a raw record identifier")
    class RecordIdentifierAudit {

        /**
         * The four sources whose diagnostics stand between the fixed-width datasets and the log.
         *
         * <p>These are the paths that read a record straight out of a 350-byte or 50-byte image and then
         * report on it. That makes their diagnostics doubly exposed: the identifier they would name
         * belongs to a cardholder's transaction or account, and its bytes are whatever the record held,
         * so a corrupt image could carry a line terminator, a delimiter or a terminal control sequence
         * into a log record through them.
         *
         * <p>Scoped to these four rather than to the whole module, because the module's other tiers
         * legitimately key on an account identifier - a browse reports which page it served, a keyed read
         * reports which key it was given - and a detector wide enough to cover them would need an
         * enrolment set large enough to hide a real leak inside.
         */
        private static final List<Path> RECORD_PATH_SOURCES = List.of(
                PRODUCTION_SOURCE_ROOT.resolve(Path.of("batch", "step",
                        "TransactionValidationProcessor.java")),
                PRODUCTION_SOURCE_ROOT.resolve(Path.of("batch", "step",
                        "CombineTransactionsProcessor.java")),
                PRODUCTION_SOURCE_ROOT.resolve(Path.of("batch", "step",
                        "InterestCalculationProcessor.java")),
                PRODUCTION_SOURCE_ROOT.resolve(Path.of("service", "InterestCalculationService.java")));

        /**
         * The expressions that carry a record identifier on those four paths.
         *
         * <p>Written as an alternation of the exact forms the sources use, so a rename that keeps the
         * value still has to be enrolled here rather than slipping past a heuristic. The composite key is
         * included because its own {@code toString} renders the account identifier as its first
         * component.
         */
        private static final String RECORD_IDENTIFIERS =
                "tranId|accountId|rowKey|transactionId|result\\.accountId\\(\\)"
                        + "|row\\.getTrancatAcctId\\(\\)|item\\.getDalytranId\\(\\)"
                        + "|source\\.getDalytranId\\(\\)|\\w*\\.getTranId\\(\\)"
                        + "|\\w*\\.getAcctId\\(\\)|\\w*\\.getCustId\\(\\)"
                        + "|\\w*\\.getDalytranId\\(\\)|\\w*\\.getTrancatAcctId\\(\\)";

        /** One of those expressions rendered into a message by concatenation, either side of the plus. */
        private static final Pattern IDENTIFIER_CONCATENATED = Pattern.compile(
                "(?<![\\w.])(?:" + RECORD_IDENTIFIERS + ")\\s*\\+"
                        + "|\\+\\s*(?:" + RECORD_IDENTIFIERS + ")(?![\\w(.])");

        /** One of those expressions handed to a logger as an argument in its own right. */
        private static final Pattern IDENTIFIER_ARGUMENT =
                Pattern.compile("(?<![\\w.])(?:" + RECORD_IDENTIFIERS + ")(?![\\w(.])");

        /** The sanctioned form, whose whole call is removed before either detector reads the source. */
        private static final String REDACTION_CALL = "SensitiveLogRedactor.redact(";

        /**
         * Fewest redaction sites the four sources must hold between them.
         *
         * <p>Both rules below are satisfied by deleting the diagnostics rather than sanitising them. This
         * floor closes that route: the identifier has to still be reported, as a reference. The figure is
         * a count of call sites and is not a coverage or a threshold.
         */
        private static final int MINIMUM_IDENTIFIER_REDACTION_SITES = 13;

        /**
         * Removes every redaction call, whole, so what remains is what the source renders as itself.
         *
         * <p>The call is removed rather than its argument, and the argument is removed with it: a value
         * inside {@code redact(...)} never reaches the message, so leaving the argument behind would make
         * the sanctioned form indistinguishable from the leak it replaces. The scan is balanced-paren
         * aware, so a nested accessor call inside the argument is removed with the rest of it.
         *
         * @param  source the file text
         * @return the text with every redaction call replaced by a placeholder token
         */
        private static String withoutRedactionCalls(final String source) {
            final StringBuilder kept = new StringBuilder(source.length());
            int cursor = 0;
            while (true) {
                final int call = source.indexOf(REDACTION_CALL, cursor);
                if (call < 0) {
                    kept.append(source, cursor, source.length());
                    return kept.toString();
                }
                kept.append(source, cursor, call).append("REDACTED_REFERENCE");
                cursor = endOfCall(source, call + REDACTION_CALL.length() - 1);
            }
        }

        /**
         * Reports where a pattern still matches, after the sanctioned form has been removed.
         *
         * @param  detector the pattern to apply
         * @param  wholeCallsOnly whether to read only the text of diagnostic calls
         * @return one description per finding, naming the file, the line and the text
         */
        private static List<String> findings(final Pattern detector, final boolean wholeCallsOnly) {
            final List<String> found = new ArrayList<>();
            for (final Path file : RECORD_PATH_SOURCES) {
                final String source = withoutRedactionCalls(textOf(file));
                if (wholeCallsOnly) {
                    for (final Diagnostic diagnostic : diagnosticsIn(file, source)) {
                        final String structure =
                                STRING_LITERAL.matcher(diagnostic.text()).replaceAll("\"\"");
                        final Matcher leak = detector.matcher(structure);
                        while (leak.find()) {
                            found.add(diagnostic.location() + " -> " + leak.group());
                        }
                    }
                    continue;
                }
                final Matcher leak = detector.matcher(source);
                while (leak.find()) {
                    found.add(file.getFileName() + ":" + lineOf(source, leak.start())
                            + " -> " + leak.group().trim());
                }
            }
            return found;
        }

        @Test
        @DisplayName("not concatenated into an exception message, which is where the identifier used to "
                + "travel on every postcondition these paths state")
        void noIdentifierIsConcatenatedIntoAMessage() {
            assertThat(findings(IDENTIFIER_CONCATENATED, false))
                    .as("an identifier read out of a fixed-width image, rendered into a message: the "
                            + "value identifies a cardholder's record and its bytes are the record's, so "
                            + "a corrupt image could carry a control byte into the message with it")
                    .isEmpty();
        }

        @Test
        @DisplayName("and not handed to a logger as an argument either, so a structured log record cannot "
                + "carry one in a field of its own")
        void noIdentifierReachesALoggerAsAnArgument() {
            assertThat(findings(IDENTIFIER_ARGUMENT, true))
                    .as("an identifier as a logging argument is the same disclosure as one inside a "
                            + "message, and in a structured record it is the more searchable of the two")
                    .isEmpty();
        }

        @Test
        @DisplayName("and the identifier is still reported, as a reference, so the audit is not satisfied "
                + "by a path that says nothing when it fails")
        void theIdentifierIsStillReportedAsAReference() {
            final long sites = RECORD_PATH_SOURCES.stream()
                    .map(DiagnosticConfidentialityAuditTest::textOf)
                    .mapToLong(source -> countOccurrences(source, REDACTION_CALL))
                    .sum();

            assertThat(sites)
                    .as("a record that fails a postcondition still has to be findable through a "
                            + "re-presented chunk, which is what the reference is for")
                    .isGreaterThanOrEqualTo(MINIMUM_IDENTIFIER_REDACTION_SITES);
        }

        /**
         * Counts non-overlapping occurrences of a fixed string.
         *
         * @param  source the text to search
         * @param  fragment the fragment to count
         * @return how many times it occurs
         */
        private static long countOccurrences(final String source, final String fragment) {
            long total = 0;
            int cursor = source.indexOf(fragment);
            while (cursor >= 0) {
                total++;
                cursor = source.indexOf(fragment, cursor + fragment.length());
            }
            return total;
        }

        @Test
        @DisplayName("and both detectors fire on a planted leak, so a clean scan is a finding and not a "
                + "blind spot")
        void bothDetectorsFireOnAPlantedLeak() {
            final String plantedConcatenation =
                    "throw new IllegalStateException(\"record \" + tranId + \" is malformed\");";
            final String plantedArgument =
                    "LOGGER.warn(\"refused {}\", accountId);";
            final String sanctioned =
                    "LOGGER.warn(\"refused {}\", SensitiveLogRedactor.redact(accountId));";

            assertThat(IDENTIFIER_CONCATENATED.matcher(plantedConcatenation).find())
                    .as("the concatenation detector must see a plain identifier beside a plus")
                    .isTrue();
            assertThat(IDENTIFIER_ARGUMENT.matcher(
                    STRING_LITERAL.matcher(plantedArgument).replaceAll("\"\"")).find())
                    .as("the argument detector must see a plain identifier as an argument")
                    .isTrue();
            assertThat(IDENTIFIER_ARGUMENT.matcher(STRING_LITERAL
                    .matcher(withoutRedactionCalls(sanctioned)).replaceAll("\"\"")).find())
                    .as("and must stay silent on the sanctioned form, whose argument never reaches the "
                            + "record")
                    .isFalse();
        }

        @Test
        @DisplayName("and the reference is stable within a run, so the several messages one failing "
                + "record produces still name the same record")
        void theReferenceIsStableWithinARun() {
            final String first = SensitiveLogRedactor.redact("00000000001");
            final String second = SensitiveLogRedactor.redact("00000000001");
            final String other = SensitiveLogRedactor.redact("00000000002");

            assertThat(first)
                    .as("the value itself is never rendered")
                    .doesNotContain("00000000001")
                    .startsWith(SensitiveLogRedactor.REDACTED)
                    .isEqualTo(second)
                    .isNotEqualTo(other);
        }
    }

    /**
     * No confidentiality-critical suite hands a protected value to an assertion as an operand.
     *
     * <h2>The second channel, which the rules above do not reach</h2>
     *
     * <p>Everything above governs {@code src/main/java}: what the application writes to a log at runtime.
     * There is a second channel with the same consequence and none of the same scrutiny - what a
     * <em>test</em> writes to a build log when it fails. An AssertJ failure prints both operands. So
     * {@code assertThat(rendered).doesNotContain(TOKEN_CANARY)} discloses twice over at the moment the
     * leak it guards against occurs: once through the canary it names, and once through the record that
     * now contains it. Four suites carried that shape over eleven assertions, between them naming a
     * bearer token, a national identifier, a primary account number, a connection string with its
     * password, an AES-256 fixture key, fifty sealed envelopes, their decrypted values, BCrypt digests, a
     * cleartext credential and progressively longer prefixes of it.
     *
     * <p>A CI log outlives the run that produced it and is read, forwarded and retained by more people
     * than the run was, so the disclosure is durable in a way a passing test never hints at.
     *
     * <p>{@code support.SensitiveValues} exists for exactly this: {@code absentFrom} for containment,
     * {@code fingerprint} for equality, {@code describe} for identification in a description, and
     * {@code fingerprints} for a collection. The remedy is always to assert the predicate or the
     * fingerprint and to identify the value in the description, never to hand it to a matcher.
     *
     * <h2>Scope, stated rather than implied</h2>
     *
     * <p>The scan is confined to the suites that actually hold protected values, enrolled below by name.
     * Scanning all five hundred test sources for sensitively-named operands would report a false positive
     * for every test whose subject merely happens to be called {@code key} or {@code token}, and a rule
     * that cries wolf is a rule that gets loosened. Two complementary checks then run over that scope: an
     * exact-name rule for the values known to be there, and a naming-convention rule that catches a
     * newly-introduced constant which follows the module's own naming.
     */
    @Nested
    @DisplayName("no confidentiality-critical suite hands a protected value to an assertion")
    class NoAssertionOperandCarriesAProtectedValue {

        /**
         * The suites that hold protected values, and are therefore the scope of this rule.
         *
         * <p>Enrolled by name, with the value each one holds, so a reader can see what is being protected
         * rather than trusting a pattern. Every entry is asserted to exist, so a renamed or deleted suite
         * fails here instead of silently narrowing the scan.
         */
        private final Map<Path, String> criticalSources = Map.of(
                Path.of("src", "test", "java", "com", "carddemo", "config",
                        "ProductionLogAppenderConfidentialityTest.java"),
                "a bearer token, a national identifier, a primary account number and a connection "
                        + "string carrying its password, all planted as canaries",
                Path.of("src", "test", "java", "com", "carddemo", "service",
                        "SeededProtectedIdentifierIT.java"),
                "the AES-256 fixture key, fifty sealed envelopes and the identifiers they open to",
                Path.of("src", "test", "java", "com", "carddemo", "service",
                        "UserSecurityCredentialIT.java"),
                "BCrypt digests and the cleartext credential they are made from",
                Path.of("src", "test", "java", "com", "carddemo", "e2e", "BatchPipelineE2ETest.java"),
                "production-representative transaction, statement and report records");

        /**
         * The exact operands that must never be handed to a matcher, with what each one is.
         *
         * <p>By exact text rather than by pattern, so the rule has no false positives and a reader can see
         * the inventory. The convention rule below is what covers a value this list does not yet know.
         */
        private final Map<String, String> protectedOperands = Map.of(
                "TOKEN_CANARY", "a bearer credential",
                "NATIONAL_ID_CANARY", "a national identifier",
                "PAN_CANARY", "a primary account number",
                "JDBC_CANARY", "a connection string carrying its password",
                "CONFIGURED_KEY", "AES-256 key material",
                "DOCUMENTED_FIXTURE_KEY", "AES-256 key material",
                "CREDENTIAL", "a cleartext credential");

        /**
         * Matchers that print their operand, and the subject they are applied to, when they fail.
         *
         * <p>{@code as} and {@code describedAs} are absent on purpose: a description is exactly where a
         * protected value's fingerprint <em>should</em> be named, so an operand there is the remedy rather
         * than the defect.
         */
        private final List<String> disclosingMatchers = List.of("isEqualTo", "isNotEqualTo",
                "doesNotContain", "contains", "hasSize", "isSameAs", "startsWith", "endsWith");

        /** Creates the nest. */
        NoAssertionOperandCarriesAProtectedValue() {
            // Intentionally empty: this nest contributes tests, not state.
        }

        /**
         * The enrolled suites exist, so the scan below is over something.
         */
        @Test
        @DisplayName("every enrolled suite exists, so a renamed or deleted one cannot silently narrow the "
                + "scan to nothing")
        void everyEnrolledSuiteExists() {
            assertThat(this.criticalSources.keySet())
                    .as("the scope is enrolled by name; a path that no longer resolves is excusing a file "
                            + "that is not there")
                    .isNotEmpty()
                    .allSatisfy(source -> assertThat(source)
                            .as("%s holds %s", source, this.criticalSources.get(source))
                            .isRegularFile());
        }

        /**
         * No enrolled suite hands one of the known protected values to a disclosing matcher.
         */
        @Test
        @DisplayName("★ no known protected value is an operand of a matcher that would print it")
        void noKnownProtectedValueIsAMatcherOperand() {
            final List<String> findings = new ArrayList<>();

            for (final Path source : this.criticalSources.keySet()) {
                final String text = textOf(source);
                for (final Map.Entry<String, String> operand : this.protectedOperands.entrySet()) {
                    for (final String matcher : this.disclosingMatchers) {
                        findings.addAll(occurrencesOf(text, source, matcher, operand));
                    }
                    findings.addAll(occurrencesOf(text, source, "assertThat", operand));
                }
            }

            assertThat(findings)
                    .as("an AssertJ failure prints both operands, so each of these publishes a protected "
                            + "value into a build log at the exact moment the property it guards is "
                            + "broken. Assert SensitiveValues.absentFrom for containment or compare "
                            + "SensitiveValues.fingerprint for equality, and name the value through "
                            + "SensitiveValues.describe in the description instead. Offending: %s",
                            findings)
                    .isEmpty();
        }

        /**
         * No enrolled suite applies an assertion directly to a sensitively-named reference.
         *
         * <p>The convention rule. The exact list above cannot know about a constant added tomorrow, but
         * this module names such a constant for what it is - a canary, a credential, a secret, a key, a
         * digest, some cleartext - and this catches the shape rather than the name.
         */
        @Test
        @DisplayName("★ and no assertion is applied directly to a sensitively-named reference, which "
                + "catches a protected value the inventory above does not yet know about")
        void noAssertionSubjectIsASensitivelyNamedReference() {
            final Pattern sensitivelyNamed = Pattern.compile(
                    "assertThat\\(\\s*((?:[A-Za-z_$][\\w$]*\\.)*"
                            + "[A-Za-z_$][\\w$]*(?:CANARY|CREDENTIAL|CLEARTEXT|_SECRET|_KEY|_DIGEST"
                            + "|_SSN|_PAN)[\\w$]*)\\s*\\)");
            final List<String> findings = new ArrayList<>();

            for (final Path source : this.criticalSources.keySet()) {
                final String text = textOf(source);
                final Matcher applied = sensitivelyNamed.matcher(text);
                while (applied.find()) {
                    findings.add(source.getFileName() + ":" + lineOf(text, applied.start())
                            + " asserts directly on " + applied.group(1));
                }
            }

            assertThat(findings)
                    .as("a value named for what it is must not be an assertion's subject either: whatever "
                            + "the matcher, the subject is printed on failure. Assert a predicate or a "
                            + "fingerprint of it instead. Offending: %s", findings)
                    .isEmpty();
        }

        /**
         * Both detectors fire on a planted operand, so a clean scan means something.
         */
        @Test
        @DisplayName("and both detectors fire on a planted leak, so a clean scan is evidence rather than a "
                + "pattern that stopped matching")
        void bothDetectorsFireOnAPlantedLeak() {
            final String planted = String.join("\n",
                    "assertThat(rendered).doesNotContain(TOKEN_CANARY);",
                    "assertThat(CONFIGURED_KEY).isEqualTo(DOCUMENTED_FIXTURE_KEY);",
                    "assertThat(SensitiveValues.absentFrom(rendered, TOKEN_CANARY)).isTrue();",
                    "assertThat(row.length()).as(\"%s\", describe(TOKEN_CANARY)).isEqualTo(60);");
            final Path illustration = Path.of("Planted.java");

            // The SAME rule set the real scan applies, assertThat included. A self-check that exercised
            // a subset would attest to a detector nobody runs.
            final List<String> operandFindings = new ArrayList<>();
            for (final Map.Entry<String, String> operand : this.protectedOperands.entrySet()) {
                for (final String matcher : this.disclosingMatchers) {
                    operandFindings.addAll(occurrencesOf(planted, illustration, matcher, operand));
                }
                operandFindings.addAll(occurrencesOf(planted, illustration, "assertThat", operand));
            }
            final Pattern sensitivelyNamed = Pattern.compile(
                    "assertThat\\(\\s*((?:[A-Za-z_$][\\w$]*\\.)*"
                            + "[A-Za-z_$][\\w$]*(?:CANARY|CREDENTIAL|CLEARTEXT|_SECRET|_KEY|_DIGEST"
                            + "|_SSN|_PAN)[\\w$]*)\\s*\\)");

            assertThat(operandFindings)
                    .as("the first two planted lines hand a protected value to a matcher and must be "
                            + "reported; the third and fourth are the sanctioned forms - a predicate, and "
                            + "a value named only inside a description - and must not be")
                    .hasSize(3)
                    .allSatisfy(finding -> assertThat(finding).doesNotContain("absentFrom"));
            assertThat(sensitivelyNamed.matcher(planted).results().count())
                    .as("and the convention detector finds the one line that asserts directly on a "
                            + "sensitively-named reference")
                    .isEqualTo(1L);
        }

        /**
         * Locates every place one protected operand is passed to one matcher <em>as a whole argument</em>.
         *
         * <h2>Why the whole argument and not a substring</h2>
         *
         * <p>The value appearing <em>anywhere</em> inside the argument list is the wrong test, and
         * measurably so: {@code assertThat(SensitiveValues.absentFrom(rendered, TOKEN_CANARY))} and
         * {@code isEqualTo(SensitiveValues.fingerprint(CONFIGURED_KEY))} both mention the value and both
         * are the <em>remedy</em>. A first version of this rule flagged fourteen such sites, every one of
         * them already safe, which is exactly how a rule earns a blanket suppression.
         *
         * <p>What discloses is the value being an argument <em>on its own</em>, because that is the form
         * AssertJ prints. So the argument list is split at top-level commas - through the same splitter
         * the throwable rules use, which tracks string and character literals - and an argument is a
         * finding only when, trimmed, it is exactly the operand. A value wrapped in a fingerprint, a
         * predicate or a description is untouched.
         *
         * @param  text    the source text to scan
         * @param  source  the file, for the report
         * @param  matcher the matcher name
         * @param  operand the protected operand and what it is
         * @return one entry per occurrence
         */
        private List<String> occurrencesOf(final String text, final Path source, final String matcher,
                final Map.Entry<String, String> operand) {
            final Pattern call = Pattern.compile("(?<![\\w$.])" + Pattern.quote(matcher) + "\\s*\\(|\\."
                    + Pattern.quote(matcher) + "\\s*\\(");
            final List<String> found = new ArrayList<>();
            final Matcher calls = call.matcher(text);
            while (calls.find()) {
                final int open = text.indexOf('(', calls.start());
                final int close = endOfCall(text, open);
                if (close <= open) {
                    continue;
                }
                for (final String argument : argumentsOf(text.substring(open, close))) {
                    if (argument.strip().equals(operand.getKey())) {
                        found.add(source.getFileName() + ":" + lineOf(text, calls.start()) + " passes "
                                + operand.getKey() + " (" + operand.getValue() + ") to " + matcher
                                + "() as a whole argument");
                    }
                }
            }
            return found;
        }

        /**
         * Reports the one-based line an offset falls on.
         *
         * @param  text   the source text
         * @param  offset the offset
         * @return the line number
         */
        private int lineOf(final String text, final int offset) {
            return (int) text.substring(0, offset).chars().filter(character -> character == '\n').count()
                    + 1;
        }
    }
}
