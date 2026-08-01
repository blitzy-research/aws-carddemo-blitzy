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

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Asserts that every static contract of the fixed-width layer refuses to become an object.
 *
 * <h2>What is under test</h2>
 * Seven classes in this package are static contracts rather than components: they hold the byte
 * offsets, literal templates and formatting rules that reproduce the legacy record layouts, and they
 * hold no state of their own. Four of the seven defend that design with a constructor that raises
 * rather than returning, and this class asserts both halves of the design — that the constructor is
 * private, and that the four which guard actually do guard.
 *
 * <h2>Why the guard is asserted rather than trusted</h2>
 * A private constructor is not by itself a guarantee: it can be reached reflectively, and it can be
 * widened by an unrelated edit without any caller changing. The guard is what makes an instance
 * impossible rather than merely inconvenient, and it matters here for a specific reason. These classes
 * carry the offsets and widths that Gate 1 byte-parity depends on, and a per-instance copy of any of
 * them would let two callers disagree about a layout that the legacy record defines exactly once.
 * Keeping them uninstantiable keeps each layout single-valued.
 *
 * <h2>Why the private-constructor assertion covers all seven, and the guard only four</h2>
 * Three of the seven declare a private constructor that simply does nothing. That is a deliberate
 * distinction and not an oversight: those three hold no literal template and no offset table, so an
 * instance of one would be useless rather than dangerous, and a raising constructor there would add a
 * throw that no reader benefits from. The tests therefore hold all seven to the accessibility rule and
 * only the four layout-bearing classes to the raising rule, which is exactly what the code does.
 *
 * <p>Provenance: part of the migration of the AWS CardDemo z/OS application at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is reproduced.</p>
 */
@DisplayName("Static contracts of the fixed-width layer refuse instantiation")
class StaticContractInstantiationTest {

    /**
     * Supplies every class in this package that is a static contract rather than a component.
     *
     * @return the static contract classes, each with its simple name for readable reporting
     */
    private static Stream<Arguments> staticContracts() {
        return Stream.of(
                Arguments.of(CobolStringUtils.class, "CobolStringUtils"),
                Arguments.of(JclCardImageBuilder.class, "JclCardImageBuilder"),
                Arguments.of(PfKeyTranslator.class, "PfKeyTranslator"),
                Arguments.of(ReportLineFormatter.class, "ReportLineFormatter"),
                Arguments.of(StatementHtmlTemplates.class, "StatementHtmlTemplates"),
                Arguments.of(StatementTextTemplates.class, "StatementTextTemplates"),
                Arguments.of(ZonedDecimalCodec.class, "ZonedDecimalCodec"));
    }

    /**
     * Supplies the four layout-bearing contracts whose constructor raises rather than returning.
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
                        "StatementTextTemplates is a static utility and is not instantiable"));
    }

    @Nested
    @DisplayName("accessibility - no caller can reach the constructor directly")
    class Accessibility {

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

        @ParameterizedTest(name = "{1}")
        @MethodSource(
                "com.carddemo.util.StaticContractInstantiationTest#staticContracts")
        @DisplayName("the constructor takes no parameter, so nothing could be injected into it")
        void theConstructorTakesNoParameter(Class<?> contract, String simpleName) {
            assertThat(contract.getDeclaredConstructors()[0].getParameterCount())
                    .as("the constructor of %s must take no parameter", simpleName)
                    .isZero();
        }

        @ParameterizedTest(name = "{1}")
        @MethodSource(
                "com.carddemo.util.StaticContractInstantiationTest#staticContracts")
        @DisplayName("the class is final, so no subclass can widen the constructor")
        void theClassIsFinal(Class<?> contract, String simpleName) {
            assertThat(Modifier.isFinal(contract.getModifiers()))
                    .as("%s must be final", simpleName)
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("guard - a layout-bearing contract raises rather than returning an instance")
    class Guard {

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
    }
}
