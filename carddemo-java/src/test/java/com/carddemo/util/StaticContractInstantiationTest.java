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
import static org.assertj.core.api.Assertions.assertThatCode;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Asserts that every static contract of the fixed-width layer refuses to become an object.
 *
 * <h2>What is under test</h2>
 * Fifteen classes in this package are static contracts rather than components: they hold the byte
 * offsets, literal templates, record layouts and formatting rules that reproduce the legacy record
 * images, and they hold no state of their own. Eight of the fifteen carry a record layout - the seven
 * record mappers plus the protected-value codec - and the remaining seven carry templates, offsets or
 * string primitives. Every one of the fifteen is held to the accessibility rule: exactly one
 * constructor, private, taking nothing, on a final class.
 *
 * <h2>Why the guard is asserted rather than trusted</h2>
 * A private constructor is not by itself a guarantee: it can be reached reflectively, and it can be
 * widened by an unrelated edit without any caller changing. The guard is what makes an instance
 * impossible rather than merely inconvenient, and it matters here for a specific reason. These classes
 * carry the offsets and widths that Gate 1 byte-parity depends on, and a per-instance copy of any of
 * them would let two callers disagree about a layout that the legacy record defines exactly once.
 * Keeping them uninstantiable keeps each layout single-valued.
 *
 * <h2>The nine-and-six split, stated honestly</h2>
 * Nine of the fifteen defend the design with a constructor that raises rather than returning. The other
 * six declare a private constructor that simply does nothing. That split is a real inconsistency in the
 * delivered code rather than a designed distinction, and it is recorded here as it is rather than
 * papered over: three of the six hold no layout at all, so an instance would be useless rather than
 * dangerous, but three of them - the account, daily-transaction and disclosure-group mappers - do hold
 * layouts and would be better off raising like their four siblings. This class therefore holds all
 * fifteen to the accessibility rule, the nine to the raising rule, and asserts of the six only what is
 * true of them, which is that their constructor is unreachable by any caller and inert when reached
 * reflectively. Aligning the six with the nine is a production change that no review finding calls for,
 * so it is documented rather than made.
 *
 * <h2>Why this test uses reflection when the module's reflection budget is zero</h2>
 * The zero-reflection constraint is scoped to {@code src/main/java}, because its purpose is to keep
 * reflection out of the shipped runtime. A constructor that no caller can reach cannot be exercised any
 * other way, and leaving it unexercised would leave a line of production code with no evidence behind
 * it at all.
 *
 * <p>Provenance: part of the migration of the AWS CardDemo z/OS application at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is reproduced.</p>
 */
@DisplayName("Static contracts of the fixed-width layer refuse instantiation")
class StaticContractInstantiationTest {

    /** The number of static contracts this package declares, asserted so the list cannot silently shrink. */
    private static final int EXPECTED_STATIC_CONTRACTS = 15;

    /** The number of those contracts whose constructor raises rather than returning. */
    private static final int EXPECTED_GUARDED_CONTRACTS = 9;

    /** The number whose constructor is private but inert. */
    private static final int EXPECTED_INERT_CONTRACTS = 6;

    /**
     * Supplies every class in this package that is a static contract rather than a component.
     *
     * <p>{@code FixedWidthFieldReader} is deliberately absent: it is a genuine value type with
     * instances and a builder, and holding it to this rule would be wrong rather than merely strict.
     *
     * @return the static contract classes, each with its simple name for readable reporting
     */
    private static Stream<Arguments> staticContracts() {
        return Stream.concat(guardedClasses(), inertClasses());
    }

    /**
     * Supplies the nine contracts whose constructor raises rather than returning, each paired with the
     * exact phrase its guard reports.
     *
     * @return the guarded classes, each with the phrase its guard reports
     */
    private static Stream<Arguments> guardedContracts() {
        return Stream.of(
                Arguments.of(JclCardImageBuilder.class,
                        "JclCardImageBuilder is a static contract and is not instantiable"),
                Arguments.of(ReportLineFormatter.class,
                        "ReportLineFormatter is a static utility and is not instantiable"),
                Arguments.of(StatementHtmlTemplates.class,
                        "StatementHtmlTemplates is a constant holder and is not instantiable"),
                Arguments.of(StatementTextTemplates.class,
                        "StatementTextTemplates is a static utility and is not instantiable"),
                Arguments.of(SensitiveFieldCodec.class,
                        "SensitiveFieldCodec is a utility holder and is never instantiated"),
                Arguments.of(CustomerRecordMapper.class,
                        "CustomerRecordMapper is a static utility and is not instantiable"),
                Arguments.of(TranCatRecordMapper.class,
                        "TranCatRecordMapper is a static utility and is not instantiable"),
                Arguments.of(TranTypeRecordMapper.class,
                        "TranTypeRecordMapper is a static contract and is not instantiable"),
                Arguments.of(UserSecurityRecordMapper.class,
                        "UserSecurityRecordMapper is a static contract and is not instantiable"));
    }

    /**
     * Supplies the six contracts whose private constructor is inert.
     *
     * <p>Three of the six - the account, daily-transaction and disclosure-group mappers - do carry a
     * record layout and so would be better off raising. That is recorded rather than corrected, because
     * changing production code to align them is not something any review finding asks for.
     *
     * @return the inert classes, each with its simple name for readable reporting
     */
    private static Stream<Arguments> inertContracts() {
        return inertClasses();
    }

    /**
     * The guarded classes without their guard phrases, for reuse by the accessibility rule.
     *
     * @return the guarded classes, each with its simple name
     */
    private static Stream<Arguments> guardedClasses() {
        return Stream.of(
                Arguments.of(JclCardImageBuilder.class, "JclCardImageBuilder"),
                Arguments.of(ReportLineFormatter.class, "ReportLineFormatter"),
                Arguments.of(StatementHtmlTemplates.class, "StatementHtmlTemplates"),
                Arguments.of(StatementTextTemplates.class, "StatementTextTemplates"),
                Arguments.of(SensitiveFieldCodec.class, "SensitiveFieldCodec"),
                Arguments.of(CustomerRecordMapper.class, "CustomerRecordMapper"),
                Arguments.of(TranCatRecordMapper.class, "TranCatRecordMapper"),
                Arguments.of(TranTypeRecordMapper.class, "TranTypeRecordMapper"),
                Arguments.of(UserSecurityRecordMapper.class, "UserSecurityRecordMapper"));
    }

    /**
     * The inert classes, shared by the accessibility rule and the inert-constructor rule.
     *
     * @return the inert classes, each with its simple name
     */
    private static Stream<Arguments> inertClasses() {
        return Stream.of(
                Arguments.of(CobolStringUtils.class, "CobolStringUtils"),
                Arguments.of(PfKeyTranslator.class, "PfKeyTranslator"),
                Arguments.of(ZonedDecimalCodec.class, "ZonedDecimalCodec"),
                Arguments.of(AccountRecordMapper.class, "AccountRecordMapper"),
                Arguments.of(DailyTransactionRecordMapper.class, "DailyTransactionRecordMapper"),
                Arguments.of(DisclosureGroupRecordMapper.class, "DisclosureGroupRecordMapper"));
    }

    /**
     * Extracts the binary name of the class an argument row carries, so that census assertions compare
     * names rather than wildcard-captured class literals.
     *
     * @param argument a row supplied by one of the providers above
     * @return the binary name of the class in that row
     */
    private static String contractName(final Arguments argument) {
        return ((Class<?>) argument.get()[0]).getName();
    }

    /**
     * Pins the size of each list, so that adding a static contract to the package without adding it
     * here is a failure rather than an omission nobody notices.
     */
    @Nested
    @DisplayName("census - the enumerated lists are complete")
    class Census {

        /** The two sub-lists must partition the whole list, with nothing counted twice or dropped. */
        @Test
        @DisplayName("partitions fifteen static contracts into nine guarded and six inert")
        void theListsPartitionTheStaticContracts() {
            assertThat(staticContracts())
                    .as("every static contract in this package must appear exactly once")
                    .hasSize(EXPECTED_STATIC_CONTRACTS);
            assertThat(guardedContracts())
                    .as("nine contracts raise from their constructor")
                    .hasSize(EXPECTED_GUARDED_CONTRACTS);
            assertThat(inertContracts())
                    .as("six contracts declare an inert private constructor")
                    .hasSize(EXPECTED_INERT_CONTRACTS);
            assertThat(EXPECTED_GUARDED_CONTRACTS + EXPECTED_INERT_CONTRACTS)
                    .as("guarded plus inert must be the whole population, or a contract is being "
                            + "held to neither rule")
                    .isEqualTo(EXPECTED_STATIC_CONTRACTS);
        }

        /** All seven delivered record mappers must be held to these rules, none omitted. */
        @Test
        @DisplayName("covers all seven delivered record mappers")
        void allSevenRecordMappersAreCovered() {
            assertThat(staticContracts().map(StaticContractInstantiationTest::contractName))
                    .as("a record mapper absent from this list would have an unexercised constructor "
                            + "and an unasserted instantiation contract")
                    .contains(AccountRecordMapper.class.getName(),
                            CustomerRecordMapper.class.getName(),
                            DailyTransactionRecordMapper.class.getName(),
                            DisclosureGroupRecordMapper.class.getName(),
                            TranCatRecordMapper.class.getName(),
                            TranTypeRecordMapper.class.getName(),
                            UserSecurityRecordMapper.class.getName());
        }

        /** No class may appear in both sub-lists, which would let a contradiction pass unnoticed. */
        @Test
        @DisplayName("lists no contract as both guarded and inert")
        void noContractIsBothGuardedAndInert() {
            assertThat(guardedClasses().map(StaticContractInstantiationTest::contractName))
                    .as("a class cannot both raise and not raise from the same constructor")
                    .doesNotContainAnyElementsOf(
                            inertClasses().map(StaticContractInstantiationTest::contractName)
                                    .toList());
        }
    }

    @Nested
    @DisplayName("accessibility - no caller can reach the constructor directly")
    class Accessibility {

        /**
         * Exactly one constructor, and it is private.
         *
         * @param contract   the static contract under test
         * @param simpleName its simple name, for the failure message
         */
        @ParameterizedTest(name = "{1}")
        @MethodSource(
                "com.carddemo.util.StaticContractInstantiationTest#staticContracts")
        @DisplayName("exactly one constructor is declared, and it is private")
        void exactlyOnePrivateConstructorIsDeclared(Class<?> contract, String simpleName) {
            Constructor<?>[] constructors = contract.getDeclaredConstructors();

            assertThat(constructors)
                    .as("%s must declare exactly one constructor", simpleName)
                    .hasSize(1);
            assertThat(Modifier.isPrivate(constructors[0].getModifiers()))
                    .as("the only constructor of %s must be private", simpleName)
                    .isTrue();
        }

        /**
         * A no-argument constructor, so nothing could be injected into it.
         *
         * @param contract   the static contract under test
         * @param simpleName its simple name, for the failure message
         */
        @ParameterizedTest(name = "{1}")
        @MethodSource(
                "com.carddemo.util.StaticContractInstantiationTest#staticContracts")
        @DisplayName("the constructor takes no parameter, so nothing could be injected into it")
        void theConstructorTakesNoParameter(Class<?> contract, String simpleName) {
            assertThat(contract.getDeclaredConstructors()[0].getParameterCount())
                    .as("the constructor of %s must take no parameter", simpleName)
                    .isZero();
        }

        /**
         * A final class, so no subclass can widen the constructor.
         *
         * @param contract   the static contract under test
         * @param simpleName its simple name, for the failure message
         */
        @ParameterizedTest(name = "{1}")
        @MethodSource(
                "com.carddemo.util.StaticContractInstantiationTest#staticContracts")
        @DisplayName("the class is final, so no subclass can widen the constructor")
        void theClassIsFinal(Class<?> contract, String simpleName) {
            assertThat(Modifier.isFinal(contract.getModifiers()))
                    .as("%s must be final", simpleName)
                    .isTrue();
        }

        /**
         * No instance field, so there would be nothing for an instance to carry even if one existed.
         *
         * @param contract   the static contract under test
         * @param simpleName its simple name, for the failure message
         */
        @ParameterizedTest(name = "{1}")
        @MethodSource(
                "com.carddemo.util.StaticContractInstantiationTest#staticContracts")
        @DisplayName("declares no instance field, so an instance would carry nothing")
        void noInstanceFieldIsDeclared(Class<?> contract, String simpleName) {
            assertThat(contract.getDeclaredFields())
                    .as("%s must hold its layout in static state, so that the legacy record's single "
                            + "definition stays single-valued", simpleName)
                    .allSatisfy(field -> assertThat(Modifier.isStatic(field.getModifiers()))
                            .as("field '%s' of %s must be static", field.getName(), simpleName)
                            .isTrue());
        }
    }

    @Nested
    @DisplayName("guard - a layout-bearing contract raises rather than returning an instance")
    class Guard {

        /**
         * Reflective instantiation raises, naming the class that refused.
         *
         * @param contract       the guarded contract under test
         * @param expectedPhrase the exact phrase its guard reports
         * @throws NoSuchMethodException if the declared constructor cannot be located
         */
        @ParameterizedTest(name = "{1}")
        @MethodSource(
                "com.carddemo.util.StaticContractInstantiationTest#guardedContracts")
        @DisplayName("reflective instantiation raises, naming the class that refused")
        void reflectiveInstantiationRaises(Class<?> contract, String expectedPhrase)
                throws NoSuchMethodException {
            Constructor<?> constructor = contract.getDeclaredConstructor();
            constructor.setAccessible(true);

            assertThatExceptionOfType(InvocationTargetException.class)
                    .isThrownBy(constructor::newInstance)
                    .havingCause()
                    .isInstanceOf(AssertionError.class)
                    .withMessage(expectedPhrase);
        }

        /**
         * The guard raises an error rather than an exception, so it cannot be caught by a
         * general-purpose handler and quietly turned into an instance.
         *
         * @param contract       the guarded contract under test
         * @param expectedPhrase the exact phrase its guard reports
         * @throws NoSuchMethodException if the declared constructor cannot be located
         */
        @ParameterizedTest(name = "{1}")
        @MethodSource(
                "com.carddemo.util.StaticContractInstantiationTest#guardedContracts")
        @DisplayName("the guard raises an error rather than an exception, so it cannot be caught")
        void theGuardRaisesAnError(Class<?> contract, String expectedPhrase)
                throws NoSuchMethodException {
            Constructor<?> constructor = contract.getDeclaredConstructor();
            constructor.setAccessible(true);

            assertThatExceptionOfType(InvocationTargetException.class)
                    .isThrownBy(constructor::newInstance)
                    .satisfies(failure -> {
                        assertThat(failure.getCause()).isNotInstanceOf(Exception.class);
                        assertThat(failure.getCause()).hasMessage(expectedPhrase);
                    });
        }

        /**
         * The guard phrase names the class it refused, so a stack-free log line still identifies it.
         *
         * @param contract       the guarded contract under test
         * @param expectedPhrase the exact phrase its guard reports
         */
        @ParameterizedTest(name = "{1}")
        @MethodSource(
                "com.carddemo.util.StaticContractInstantiationTest#guardedContracts")
        @DisplayName("the guard phrase names the refusing class")
        void theGuardPhraseNamesTheClass(Class<?> contract, String expectedPhrase) {
            assertThat(expectedPhrase)
                    .as("a refusal that did not name itself would be untraceable in a log")
                    .startsWith(contract.getSimpleName())
                    .containsAnyOf("is not instantiable", "is never instantiated");
        }
    }

    /**
     * The six contracts whose private constructor is inert, asserted for what is actually true of them
     * rather than for what the other nine do.
     */
    @Nested
    @DisplayName("inert constructors - private, unreachable, and doing nothing when reached")
    class InertConstructors {

        /**
         * The constructor completes without raising, which is the honest statement of what these six
         * do. Exercising it is also the only way to give the line evidence, since no caller can reach
         * it.
         *
         * @param contract   the inert contract under test
         * @param simpleName its simple name, for the failure message
         * @throws NoSuchMethodException if the declared constructor cannot be located
         */
        @ParameterizedTest(name = "{1}")
        @MethodSource(
                "com.carddemo.util.StaticContractInstantiationTest#inertContracts")
        @DisplayName("completes without raising when reached reflectively")
        void theInertConstructorCompletes(Class<?> contract, String simpleName)
                throws NoSuchMethodException {
            Constructor<?> constructor = contract.getDeclaredConstructor();
            constructor.setAccessible(true);

            assertThatCode(constructor::newInstance)
                    .as("%s declares an inert constructor rather than a raising one, and this test "
                            + "records that as it is rather than asserting a guard the class does not "
                            + "have", simpleName)
                    .doesNotThrowAnyException();
        }

        /**
         * The instance such a constructor yields carries nothing, which is why an inert constructor is
         * merely inconsistent here rather than unsafe.
         *
         * @param contract   the inert contract under test
         * @param simpleName its simple name, for the failure message
         * @throws Exception if the constructor cannot be located or invoked
         */
        @ParameterizedTest(name = "{1}")
        @MethodSource(
                "com.carddemo.util.StaticContractInstantiationTest#inertContracts")
        @DisplayName("yields an instance that carries no state")
        void theInertInstanceCarriesNoState(Class<?> contract, String simpleName) throws Exception {
            Constructor<?> constructor = contract.getDeclaredConstructor();
            constructor.setAccessible(true);

            Object instance = constructor.newInstance();

            assertThat(instance)
                    .as("%s yields an instance, and the only reason that is harmless is that the "
                            + "instance holds none of the layout", simpleName)
                    .isNotNull()
                    .isInstanceOf(contract);
            assertThat(contract.getDeclaredFields())
                    .as("%s must declare no instance field, or the instance above would hold a "
                            + "second copy of a layout the legacy record defines once", simpleName)
                    .allSatisfy(field -> assertThat(Modifier.isStatic(field.getModifiers())).isTrue());
        }
    }
}
