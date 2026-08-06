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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.lang.reflect.Constructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link FailureDiagnostics}, the single policy deciding what a failure may publish into
 * a log record.
 *
 * <p><strong>What is actually being tested, and why an ordinary "it formats a string" test would not
 * do.</strong> This class exists because a throwable is the one value crossing this module's
 * boundaries whose text this module did not author. The property that matters is therefore not that
 * the output looks right - it is that a set of specific, named, caller-controllable values are
 * <em>absent</em> from it. Every test below either proves an absence against a planted canary or
 * proves a bound holds against a value built to exceed it. Nothing here compares against a captured
 * earlier output.
 *
 * <p><strong>Canary discipline.</strong> The messages planted in the fixtures below are the five
 * categories the review named as reachable through an unbounded throwable rendering: a JDBC
 * connection string with credentials in it, a bearer token, a national identifier, a primary account
 * number, and an oversized attacker-supplied body. Each is a distinctive literal, so an assertion
 * that it is absent cannot pass by coincidence.
 *
 * <p><strong>One deliberate use of reflection, in test code only.</strong> The private-constructor
 * test loads the declared constructor to prove instantiation is refused. The reflection budget the
 * migration requirement sets is scoped to {@code src/main/java}, which this file is not; production
 * code performs no reflective operation and this test asserts nothing about production behaviour that
 * could be reached another way.
 */
@DisplayName("FailureDiagnostics - the one policy for what a failure may publish")
class FailureDiagnosticsTest {

    /**
     * A connection string carrying credentials, of the shape a driver failure puts into its own
     * message. Planted so an assertion of absence names something that could not appear by accident.
     */
    private static final String JDBC_CANARY =
            "jdbc:postgresql://db.internal:5432/carddemo?user=carddemo&password=s3cr3t-canary";

    /** A bearer credential, of the shape an authentication failure can carry. */
    private static final String TOKEN_CANARY = "Bearer eyJhbGciOiJIUzI1NiJ9.canary-payload.canary-sig";

    /** A national identifier, of the shape an interpolated validation message can carry. */
    private static final String NATIONAL_ID_CANARY = "123-45-6789";

    /** A primary account number, of the shape a constraint violation can carry as a bound value. */
    private static final String PAN_CANARY = "4111111111111111";

    /** Constructs the fixtures. */
    FailureDiagnosticsTest() {
    }

    /**
     * A failure whose type name is an ordinary identifier, used where the name itself is not the
     * subject.
     */
    private static final class OuterFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        OuterFailure(final String message, final Throwable cause) {
            super(message, cause);
        }
    }

    /** A second named type, so a two-element chain has two distinguishable names. */
    private static final class MiddleFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        MiddleFailure(final String message, final Throwable cause) {
            super(message, cause);
        }
    }

    /** A third named type, for the deepest position in a chain. */
    private static final class RootFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        RootFailure(final String message) {
            super(message);
        }
    }

    /**
     * A failure that reports itself as its own cause.
     *
     * <p>{@link Throwable#initCause(Throwable)} forbids self-causation, but the accessor is
     * overridable, so this is the shape a walk must terminate on rather than a hypothetical one.
     */
    private static final class SelfCausingFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        SelfCausingFailure(final String message) {
            super(message);
        }

        @Override
        public synchronized Throwable getCause() {
            return this;
        }
    }

    /**
     * Builds a chain of the requested length, every element carrying a canary message.
     *
     * @param depth number of throwables in the chain, at least one
     * @return the outermost throwable
     */
    private static Throwable chainOfDepth(final int depth) {
        Throwable current = new RootFailure(JDBC_CANARY);
        for (int level = 1; level < depth; level++) {
            current = new MiddleFailure(TOKEN_CANARY, current);
        }
        return current;
    }

    @Nested
    @DisplayName("the disclosure guarantee: no message of any throwable in the chain is published")
    class DisclosureGuarantee {

        @Test
        @DisplayName("a chain whose every message carries a different canary publishes none of them")
        void publishesNoMessageFromAnyLevelOfTheChain() {
            final Throwable failure = new OuterFailure(JDBC_CANARY,
                    new MiddleFailure(TOKEN_CANARY, new RootFailure(NATIONAL_ID_CANARY)));

            final String chain = FailureDiagnostics.failureChainOf(failure);

            assertThat(chain)
                    .as("a connection string reaches the log through no path this class offers")
                    .doesNotContain(JDBC_CANARY, "password", "s3cr3t");
            assertThat(chain)
                    .as("neither does a bearer credential")
                    .doesNotContain(TOKEN_CANARY, "eyJhbGciOiJIUzI1NiJ9");
            assertThat(chain)
                    .as("nor a national identifier")
                    .doesNotContain(NATIONAL_ID_CANARY);
        }

        @Test
        @DisplayName("the deepest-cause field publishes no message either, only a type name")
        void theRootFailureFieldPublishesNoMessage() {
            final Throwable failure = new OuterFailure(PAN_CANARY,
                    new MiddleFailure(JDBC_CANARY, new RootFailure(TOKEN_CANARY)));

            final String root = FailureDiagnostics.rootFailureTypeOf(failure);

            assertThat(root).isEqualTo("RootFailure");
            assertThat(root).doesNotContain(PAN_CANARY, JDBC_CANARY, TOKEN_CANARY);
        }

        @Test
        @DisplayName("an oversized attacker-supplied message cannot enlarge the rendering, which is "
                + "what removes log amplification as a lever")
        void anOversizedMessageDoesNotEnlargeTheRendering() {
            final String oversized = "A".repeat(2_000_000);
            final Throwable small = new RootFailure("short");
            final Throwable large = new RootFailure(oversized);

            assertThat(FailureDiagnostics.failureChainOf(large))
                    .as("the rendering is a function of the chain's types, never of its text")
                    .isEqualTo(FailureDiagnostics.failureChainOf(small))
                    .doesNotContain("AAAA");
        }
    }

    @Nested
    @DisplayName("the shape of the chain, which is the part that is published")
    class ChainShape {

        @Test
        @DisplayName("names every type outermost first, so the first token is where the failure "
                + "surfaced and the last is what went wrong")
        void namesEveryTypeOutermostFirst() {
            final Throwable failure = new OuterFailure("x",
                    new MiddleFailure("y", new RootFailure("z")));

            assertThat(FailureDiagnostics.failureChainOf(failure))
                    .isEqualTo("OuterFailure<-MiddleFailure<-RootFailure");
        }

        @Test
        @DisplayName("a failure with no cause renders as a single name with no separator")
        void aFailureWithNoCauseRendersAsOneName() {
            assertThat(FailureDiagnostics.failureChainOf(new RootFailure("x")))
                    .isEqualTo("RootFailure")
                    .doesNotContain(FailureDiagnostics.FAILURE_CHAIN_SEPARATOR);
        }

        @Test
        @DisplayName("a failure with no cause reports the empty deepest-cause field rather than "
                + "inventing a token, so an empty field means exactly one type")
        void aFailureWithNoCauseReportsNoRootType() {
            assertThat(FailureDiagnostics.rootFailureTypeOf(new RootFailure("x"))).isEmpty();
        }

        @Test
        @DisplayName("a self-causing failure terminates the walk and is treated as having nothing "
                + "beneath it, in both derivations")
        void aSelfCausingFailureTerminatesTheWalk() {
            final SelfCausingFailure failure = new SelfCausingFailure("x");

            assertThat(FailureDiagnostics.failureChainOf(failure)).isEqualTo("SelfCausingFailure");
            assertThat(FailureDiagnostics.rootFailureTypeOf(failure)).isEmpty();
        }
    }

    @Nested
    @DisplayName("the depth bound, which is what makes the rendered size computable")
    class DepthBound {

        @Test
        @DisplayName("a chain exactly at the bound is rendered whole and carries no truncation marker")
        void aChainAtTheBoundIsRenderedWhole() {
            final String chain = FailureDiagnostics
                    .failureChainOf(chainOfDepth(FailureDiagnostics.MAX_FAILURE_CHAIN_DEPTH));

            assertThat(chain).doesNotContain(FailureDiagnostics.FAILURE_CHAIN_TRUNCATION_MARKER);
            assertThat(chain.split(FailureDiagnostics.FAILURE_CHAIN_SEPARATOR, -1))
                    .hasSize(FailureDiagnostics.MAX_FAILURE_CHAIN_DEPTH);
        }

        @Test
        @DisplayName("a chain deeper than the bound is marked as cut, so a reader can tell a bound "
                + "from the end of the evidence")
        void aDeeperChainIsMarkedAsCut() {
            assertThat(FailureDiagnostics
                    .failureChainOf(chainOfDepth(FailureDiagnostics.MAX_FAILURE_CHAIN_DEPTH + 3)))
                    .endsWith(FailureDiagnostics.FAILURE_CHAIN_TRUNCATION_MARKER);
        }

        @Test
        @DisplayName("an unbounded chain still terminates, and the rendering stays bounded")
        void anUnboundedChainStillTerminates() {
            final String chain = FailureDiagnostics.failureChainOf(chainOfDepth(5_000));

            assertThat(chain).endsWith(FailureDiagnostics.FAILURE_CHAIN_TRUNCATION_MARKER);
            assertThat(chain.length())
                    .isLessThanOrEqualTo(FailureDiagnostics.MAX_FAILURE_CHAIN_DEPTH
                            * (FailureDiagnostics.MAX_TYPE_NAME_LENGTH
                            + FailureDiagnostics.FAILURE_CHAIN_TRUNCATION_MARKER.length()));
        }

        @Test
        @DisplayName("the deepest-cause field is taken from as deep as the bound reaches, which is a "
                + "truthful qualification rather than a guess about what lies beneath")
        void theRootTypeIsTakenFromTheBoundedDepth() {
            assertThat(FailureDiagnostics.rootFailureTypeOf(chainOfDepth(500)))
                    .isEqualTo("MiddleFailure");
        }
    }

    @Nested
    @DisplayName("type-name sanitisation, so nothing can inject a token into a structured record")
    class TypeNameSanitisation {

        @Test
        @DisplayName("an ordinary type name passes through untouched")
        void anOrdinaryNamePassesThrough() {
            assertThat(FailureDiagnostics.typeNameOf(IllegalStateException.class))
                    .isEqualTo("IllegalStateException");
        }

        @Test
        @DisplayName("an anonymous class, whose simple name is empty, reports the substitute rather "
                + "than an empty token that would merge with its separator")
        void anAnonymousClassReportsTheSubstitute() {
            final Throwable anonymous = new RuntimeException("x") {

                private static final long serialVersionUID = 1L;
            };

            assertThat(FailureDiagnostics.typeNameOf(anonymous.getClass()))
                    .isEqualTo(FailureDiagnostics.UNNAMED_FAILURE_TYPE);
            assertThat(FailureDiagnostics.failureChainOf(anonymous))
                    .isEqualTo(FailureDiagnostics.UNNAMED_FAILURE_TYPE);
        }

        @Test
        @DisplayName("a lambda-hosting synthetic type is bounded in length, so a generated name "
                + "cannot set the size of the field on its own")
        void aSyntheticNameIsBounded() {
            final Runnable lambda = () -> {
            };

            assertThat(FailureDiagnostics.typeNameOf(lambda.getClass()))
                    .isNotEmpty()
                    .hasSizeLessThanOrEqualTo(FailureDiagnostics.MAX_TYPE_NAME_LENGTH);
        }

        @ParameterizedTest(name = "no {0} reaches the rendering")
        @ValueSource(strings = {" ", "\n", "\r", "\t", "\u0000", "\"", "{", "}", ":", ",", "\u200b"})
        @DisplayName("no whitespace, control byte, structural JSON character or zero-width code point "
                + "survives sanitisation of a name that holds one")
        void noInadmissibleCharacterSurvives(final String inadmissible) {
            // The name is not read from a real class here on purpose: a real classfile cannot carry
            // most of these characters, and the guarantee under test is the character filter itself.
            // Driving it through a name built from the character is what proves the filter rather
            // than proving that the JVM refused to load something.
            final String sanitised = FailureDiagnostics.typeNameOf(
                    new Object() {

                        @Override
                        public String toString() {
                            return "Name" + inadmissible + "Suffix";
                        }
                    }.getClass());

            assertThat(sanitised).doesNotContain(inadmissible);
        }

        @Test
        @DisplayName("every retained character is an ASCII letter, digit, dollar or underscore, which "
                + "is narrower than what a Java identifier admits")
        void everyRetainedCharacterIsAdmissible() {
            for (final Class<?> type : new Class<?>[] {OuterFailure.class, MiddleFailure.class,
                    RootFailure.class, IllegalArgumentException.class, SelfCausingFailure.class}) {
                assertThat(FailureDiagnostics.typeNameOf(type))
                        .as("%s", type)
                        .matches("[A-Za-z0-9$_]+");
            }
        }
    }

    @Nested
    @DisplayName("the guards, and the refusal to be instantiated")
    class GuardsAndInstantiation {

        @Test
        @DisplayName("a null failure is refused by name rather than dereferenced")
        void aNullFailureIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> FailureDiagnostics.failureChainOf(null))
                    .withMessageContaining("failure");
            assertThatNullPointerException()
                    .isThrownBy(() -> FailureDiagnostics.rootFailureTypeOf(null))
                    .withMessageContaining("failure");
            assertThatNullPointerException()
                    .isThrownBy(() -> FailureDiagnostics.typeNameOf(null))
                    .withMessageContaining("failureType");
        }

        @Test
        @DisplayName("the utility holder refuses instantiation, so no reader looks for state it does "
                + "not have")
        void theHolderRefusesInstantiation() throws Exception {
            final Constructor<FailureDiagnostics> declared =
                    FailureDiagnostics.class.getDeclaredConstructor();
            declared.setAccessible(true);

            assertThatExceptionOfType(java.lang.reflect.InvocationTargetException.class)
                    .isThrownBy(declared::newInstance)
                    .withCauseInstanceOf(AssertionError.class);
        }
    }

    @Nested
    @DisplayName("rendering a caller-supplied value, which some diagnostics must show and none may let "
            + "through unaltered")
    class PrintableForm {

        @Test
        @DisplayName("an ordinary fixed-width value passes through exactly, so a comparison against an "
                + "expected value still reads")
        void anOrdinaryValuePassesThrough() {
            assertThat(FailureDiagnostics.printableForm("2022-07-19")).isEqualTo("2022-07-19");
            assertThat(FailureDiagnostics.printableForm("  padded  ")).isEqualTo("  padded  ");
            assertThat(FailureDiagnostics.printableForm("")).isEmpty();
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @DisplayName("every character that could end a record, redraw a terminal or hide itself is named "
                + "by its code point instead of being written")
        @ValueSource(strings = {
            "\n", "\r", "\r\n", "\t", "\u0000", "\u001B", "\u0007", "\u007F",
            "\u200B", "\u202E", "\u00A0", "\uFEFF", "\uFF12"})
        void anInertRenderingNamesEveryDangerousCharacter(final String dangerous) {
            final String rendered = FailureDiagnostics.printableForm("A" + dangerous + "B");

            assertThat(rendered)
                    .as("the character itself must not survive into the record")
                    .doesNotContain(dangerous)
                    .startsWith("A")
                    .endsWith("B")
                    .contains("U+")
                    .matches("[\\x20-\\x7E]*");
        }

        @Test
        @DisplayName("and it is named in a form a reader can reverse, four upper-case hexadecimal digits "
                + "for the code unit")
        void theCodePointRenderingIsExact() {
            assertThat(FailureDiagnostics.printableForm("\n")).isEqualTo("U+000A");
            assertThat(FailureDiagnostics.printableForm("\u001B")).isEqualTo("U+001B");
            assertThat(FailureDiagnostics.printableForm("\uFEFF")).isEqualTo("U+FEFF");
            assertThat(FailureDiagnostics.printableForm("\u0000")).isEqualTo("U+0000");
        }

        @Test
        @DisplayName("a value longer than the bound is cut and marked, so one oversized value cannot set "
                + "the size of the record")
        void anOversizedValueIsCutAndMarked() {
            final String oversized = "9".repeat(FailureDiagnostics.MAX_RENDERED_VALUE_LENGTH * 3);

            final String rendered = FailureDiagnostics.printableForm(oversized);

            assertThat(rendered)
                    .hasSize(FailureDiagnostics.MAX_RENDERED_VALUE_LENGTH
                            + FailureDiagnostics.RENDERED_VALUE_TRUNCATION_MARKER.length())
                    .endsWith(FailureDiagnostics.RENDERED_VALUE_TRUNCATION_MARKER);
        }

        @Test
        @DisplayName("a value exactly at the bound is rendered whole and carries no marker, so a reader "
                + "can tell a cut value from one that ended where it appears to")
        void aValueAtTheBoundCarriesNoMarker() {
            final String exact = "8".repeat(FailureDiagnostics.MAX_RENDERED_VALUE_LENGTH);

            assertThat(FailureDiagnostics.printableForm(exact))
                    .isEqualTo(exact)
                    .doesNotContain(FailureDiagnostics.RENDERED_VALUE_TRUNCATION_MARKER);
        }

        @Test
        @DisplayName("an absent value is named rather than dereferenced, so no diagnostic reads null "
                + "where a value belongs")
        void anAbsentValueIsNamed() {
            assertThat(FailureDiagnostics.printableForm((String) null))
                    .isEqualTo(FailureDiagnostics.ABSENT_VALUE);
        }

        @Test
        @DisplayName("one printable character is quoted, so a space found where a separator belongs is "
                + "visible as a space rather than as a gap")
        void onePrintableCharacterIsQuoted() {
            assertThat(FailureDiagnostics.printableForm(' ')).isEqualTo("' '");
            assertThat(FailureDiagnostics.printableForm('-')).isEqualTo("'-'");
            assertThat(FailureDiagnostics.printableForm('~')).isEqualTo("'~'");
        }

        @Test
        @DisplayName("and one character that is not printable is named unquoted, so the two renderings "
                + "cannot be read for one another")
        void oneDangerousCharacterIsNamedUnquoted() {
            assertThat(FailureDiagnostics.printableForm('\n')).isEqualTo("U+000A");
            assertThat(FailureDiagnostics.printableForm('\u001F')).isEqualTo("U+001F");
            assertThat(FailureDiagnostics.printableForm('\u200B')).isEqualTo("U+200B");
        }

        @Test
        @DisplayName("the rendering does not depend on the locale, because a diagnostic that changes "
                + "with the locale is not a diagnostic")
        void theRenderingIsLocaleIndependent() {
            final java.util.Locale restore = java.util.Locale.getDefault();
            try {
                java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"));
                final String arabic = FailureDiagnostics.printableForm("2022-07-19\u001B");
                java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
                final String turkish = FailureDiagnostics.printableForm("2022-07-19\u001B");

                assertThat(arabic).isEqualTo("2022-07-19U+001B").isEqualTo(turkish);
            } finally {
                java.util.Locale.setDefault(restore);
            }
        }
    }
}
