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
package com.carddemo.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Enforces, by reading the production sources, the two halves of this module's bounded-diagnostics
 * policy: no log site hands a throwable to an appender, and no diagnostic publishes a physical location.
 *
 * <h2>Why this is a test and not a review note</h2>
 *
 * <p>A throwable is the one value crossing this module's boundaries whose textual content this module
 * does not author. Its message and the message of every cause beneath it are composed by whoever raised
 * them, and in practice they carry material that must not reach a collector: a driver failure carries the
 * connection string it could not open, a constraint violation carries the bound values it rejected, an
 * interpolated validation message carries whatever a caller submitted, and a failure provoked by an
 * oversized input carries that input. Passing the object to a logging call publishes all of it verbatim,
 * indefinitely, to storage searchable by more people than the caller who caused it - and it hands a caller
 * who can provoke such a failure control over how many bytes each of their requests writes.
 *
 * <p>{@link FailureDiagnostics} is the module's answer: it renders the <em>shape</em> of the chain, which
 * type wrapped which, and reads no message, no localised message, no suppressed throwable and no stack
 * frame. The shape is frequently the whole diagnosis.
 *
 * <p>A physical location is the second such value. Every batch input and output in this module is a
 * logical resource resolved from configuration or from a job parameter into a host path, and every
 * diagnostic that needs to name one already carries the logical name - the legacy DD name, or the role the
 * stream plays in its job. Publishing the resolved path beside it adds nothing to the diagnosis and
 * discloses the deployment's filesystem layout to everyone who can read centralised logging or the
 * persisted step-failure record.
 *
 * <p>Both halves applied in some classes and absent in others is the failure mode a policy that lives
 * only in prose always has. Stating it as an executable rule is what makes it hold for the next class
 * as well as for the ones already written.
 *
 * <h2>What the rules are, exactly</h2>
 *
 * <p><strong>Throwables.</strong> The final argument of an SLF4J logging call may not be a bare reference
 * to a throwable. SLF4J treats a trailing {@code Throwable} specially - it is rendered by the appender's
 * exception converter rather than consumed by a {@code {}} placeholder - so a trailing bare reference to a
 * caught failure is precisely the shape that publishes an object rather than a value. A throwable is
 * recognised without resolving types: an identifier bound by a {@code catch} clause, or declared with a
 * type whose simple name ends in {@code Exception}, {@code Error} or {@code Throwable}. A trailing
 * {@code FailureDiagnostics.failureChainOf(...)}, a counter, a status code, a logical resource name or an
 * enum constant are all fine, because each is a value this module composed. Constructing an exception
 * <em>with</em> a cause is likewise fine and is not inspected: a chained cause is how a failure is carried
 * to whoever handles it, and this module's appender configuration bounds what a framework renders.
 *
 * <p><strong>Locations.</strong> Neither a logging call nor the message of a thrown exception may publish
 * a value obtained from a path or location accessor, nor the description of a Spring {@code Resource}. The
 * receiver of a {@code getDescription()} call is recognised by looking for the identifier's declaration as
 * a {@code Resource} in the same compilation unit, so a reject reason's description - a legacy literal,
 * and a value this module composed - is not caught by this rule.
 */
@DisplayName("bounded diagnostics :: no log site publishes a throwable or a physical location")
final class BoundedFailureDiagnosticPolicyTest {

    /** Root of the production sources, relative to the module directory the build runs in. */
    private static final Path PRODUCTION_SOURCE_ROOT = Path.of("src", "main", "java");

    /**
     * Lower bound on the number of sources the scan must reach.
     *
     * <p>Without it a broken path would make the scan pass by inspecting nothing, which is the one way a
     * source-reading test can be vacuously green.
     */
    private static final int MINIMUM_PRODUCTION_SOURCES = 100;

    /** Opening of an SLF4J logging call, whatever the logger field is named. */
    private static final Pattern LOG_CALL = Pattern.compile(
            "\\bLOG(?:GER)?\\.(?:trace|debug|info|warn|error)\\s*\\(");

    /** Opening of an exception construction, whose message reaches a persisted failure record. */
    private static final Pattern EXCEPTION_CONSTRUCTION = Pattern.compile(
            "\\bnew\\s+\\w*(?:Exception|Error)\\s*\\(");

    /** A bare local or parameter reference: a lower-camel identifier and nothing else. */
    private static final Pattern BARE_IDENTIFIER = Pattern.compile("[a-z][A-Za-z0-9]*");

    /** An identifier bound by a {@code catch} clause, with or without the {@code final} modifier. */
    private static final Pattern CAUGHT_IDENTIFIER = Pattern.compile(
            "catch\\s*\\(\\s*(?:final\\s+)?[\\w.]+(?:\\s*\\|\\s*[\\w.]+)*\\s+([a-z][A-Za-z0-9]*)\\s*\\)");

    /** An identifier declared with a type whose simple name names a failure. */
    private static final Pattern DECLARED_THROWABLE = Pattern.compile(
            "\\b\\w*(?:Exception|Error|Throwable)\\s+([a-z][A-Za-z0-9]*)\\s*(?:[=,;)]|\\bthrows\\b)");

    /** An identifier declared as a Spring {@code Resource}, whose description is a physical location. */
    private static final Pattern DECLARED_RESOURCE = Pattern.compile(
            "\\bResource\\s+([a-z][A-Za-z0-9]*)\\s*[=,;)]");

    /**
     * Accessors that yield a physical location whatever the receiver is.
     *
     * <p>{@code getDescription()} is deliberately absent: it is resolved against a receiver declared as a
     * {@code Resource}, because the same method name on a reject reason returns a legacy literal.
     */
    private static final List<String> LOCATION_ACCESSORS = List.of(
            "getAbsolutePath()",
            "getCanonicalPath()",
            "getFilename()",
            "getURI()",
            "getURL()",
            "toAbsolutePath()");

    @Test
    @DisplayName("the throwable recogniser reads both binding forms, so the scan cannot pass vacuously")
    void theThrowableRecogniserReadsBothBindingForms() {
        final String bindings = """
                void sample() {
                    try {
                        act();
                    } catch (final IOException unreadable) {
                        LOG.warn("x", unreadable);
                    } catch (RuntimeException secondary) {
                        LOG.warn("y", secondary);
                    }
                    final DataAccessException declared = compose();
                    IllegalStateException bare = compose();
                }
                """;

        assertThat(throwableNamesIn(bindings))
                .as("a catch parameter with and without final, and a declared failure type, are all "
                        + "bindings this scan must see; if any is missed the rule stops enforcing")
                .containsExactlyInAnyOrder("unreadable", "secondary", "declared", "bare");
    }

    @Test
    @DisplayName("every production log site publishes composed values only, never a throwable")
    void noProductionLogSitePublishesAThrowable() {
        final List<Path> sources = productionSources();

        assertThat(sources)
                .as("the scan must reach the production sources; an empty scan would pass vacuously")
                .hasSizeGreaterThanOrEqualTo(MINIMUM_PRODUCTION_SOURCES);

        final List<String> offending = new ArrayList<>();
        for (final Path source : sources) {
            final String body = read(source);
            final Set<String> throwables = throwableNamesIn(body);
            for (final String trailing : trailingArgumentsOf(body, LOG_CALL)) {
                if (BARE_IDENTIFIER.matcher(trailing).matches() && throwables.contains(trailing)) {
                    final String site = fileNameOf(source) + '#' + trailing;
                    if (!offending.contains(site)) {
                        offending.add(site);
                    }
                }
            }
        }

        assertThat(offending)
                .as("each site below ends a logging call with a caught or declared failure, which the "
                        + "appender renders in full. Wrap it in FailureDiagnostics.failureChainOf so the "
                        + "chain of type names is published and no message is.")
                .isEmpty();
    }

    @Test
    @DisplayName("no diagnostic publishes a resolved path, URI or resource description")
    void noDiagnosticPublishesAPhysicalLocation() {
        final List<Path> sources = productionSources();

        assertThat(sources)
                .as("the scan must reach the production sources; an empty scan would pass vacuously")
                .hasSizeGreaterThanOrEqualTo(MINIMUM_PRODUCTION_SOURCES);

        final List<String> offending = new ArrayList<>();
        for (final Path source : sources) {
            final String body = read(source);
            final List<String> published = new ArrayList<>(argumentsOf(body, LOG_CALL));
            published.addAll(argumentsOf(body, EXCEPTION_CONSTRUCTION));

            final List<String> disclosures = new ArrayList<>(LOCATION_ACCESSORS);
            for (final String resource : namesMatching(DECLARED_RESOURCE, body)) {
                disclosures.add(resource + ".getDescription()");
            }

            for (final String arguments : published) {
                for (final String disclosure : disclosures) {
                    if (arguments.contains(disclosure)) {
                        final String site = fileNameOf(source) + '#' + disclosure;
                        if (!offending.contains(site)) {
                            offending.add(site);
                        }
                    }
                }
            }
        }

        assertThat(offending)
                .as("each site below publishes a resolved physical location into centralised logging or "
                        + "into a persisted failure record. Name the logical resource instead - the "
                        + "legacy DD name, or the role the stream plays in its job.")
                .isEmpty();
    }

    /**
     * Names every identifier in one compilation unit that is bound to a throwable.
     *
     * @param body the source text to inspect
     * @return the identifiers, whether bound by a {@code catch} clause or by a declared failure type
     */
    private static Set<String> throwableNamesIn(final String body) {
        final Set<String> bound = new LinkedHashSet<>(namesMatching(CAUGHT_IDENTIFIER, body));
        bound.addAll(namesMatching(DECLARED_THROWABLE, body));
        return bound;
    }

    /**
     * Collects the first capture group of every match of one pattern.
     *
     * @param pattern the pattern to apply, whose first group captures an identifier
     * @param body    the source text to inspect
     * @return the captured identifiers, in encounter order and without repeats
     */
    private static Set<String> namesMatching(final Pattern pattern, final String body) {
        final Set<String> names = new LinkedHashSet<>();
        final Matcher match = pattern.matcher(body);
        while (match.find()) {
            names.add(match.group(1));
        }
        return names;
    }

    /**
     * Extracts the final argument of every call of one shape in a compilation unit.
     *
     * @param body    the whole source text
     * @param opening the pattern matching the call's name and its opening parenthesis
     * @return the trailing arguments, in encounter order and possibly with repeats
     */
    private static List<String> trailingArgumentsOf(final String body, final Pattern opening) {
        final List<String> trailing = new ArrayList<>();
        for (final String arguments : argumentsOf(body, opening)) {
            trailing.add(lastTopLevelArgument(arguments));
        }
        return trailing;
    }

    /**
     * Extracts the whole argument list of every call of one shape in a compilation unit.
     *
     * @param body    the whole source text
     * @param opening the pattern matching the call's name and its opening parenthesis
     * @return the argument lists, in encounter order
     */
    private static List<String> argumentsOf(final String body, final Pattern opening) {
        final List<String> lists = new ArrayList<>();
        final Matcher call = opening.matcher(body);
        while (call.find()) {
            final int close = matchingParenthesis(body, call.end());
            if (close >= 0) {
                lists.add(body.substring(call.end(), close));
            }
        }
        return lists;
    }

    /**
     * Finds the parenthesis closing a call whose opening one has just been consumed, ignoring parentheses
     * inside string literals.
     *
     * @param body the source text
     * @param from the index immediately after the opening parenthesis
     * @return the index of the closing parenthesis, or {@code -1} when the text is unbalanced
     */
    private static int matchingParenthesis(final String body, final int from) {
        int depth = 1;
        boolean inString = false;
        for (int index = from; index < body.length(); index++) {
            final char current = body.charAt(index);
            if (inString) {
                if (current == '\\') {
                    index++;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
            } else if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }

    /**
     * Reads the last comma-separated argument of an argument list, ignoring commas inside nested calls and
     * inside string literals.
     *
     * @param arguments the text between a call's parentheses
     * @return the final argument, trimmed and with line breaks collapsed
     */
    private static String lastTopLevelArgument(final String arguments) {
        int depth = 0;
        boolean inString = false;
        int start = 0;
        for (int index = 0; index < arguments.length(); index++) {
            final char current = arguments.charAt(index);
            if (inString) {
                if (current == '\\') {
                    index++;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
            } else if (current == '(' || current == '[') {
                depth++;
            } else if (current == ')' || current == ']') {
                depth--;
            } else if (current == ',' && depth == 0) {
                start = index + 1;
            }
        }
        return arguments.substring(start).replace('\n', ' ').trim();
    }

    /**
     * @param source a production source path
     * @return the simple class name, which is the file name without its extension
     */
    private static String fileNameOf(final Path source) {
        final String name = source.getFileName().toString();
        return name.substring(0, name.length() - ".java".length());
    }

    /**
     * @param source the file to read
     * @return its whole text, decoded as UTF-8
     */
    private static String read(final Path source) {
        try {
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException("unable to read " + source, unreadable);
        }
    }

    /**
     * @return every production Java source, in a stable order
     */
    private static List<Path> productionSources() {
        try (Stream<Path> tree = Files.walk(PRODUCTION_SOURCE_ROOT)) {
            return tree.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (final IOException unwalkable) {
            throw new UncheckedIOException("unable to walk " + PRODUCTION_SOURCE_ROOT, unwalkable);
        }
    }
}
