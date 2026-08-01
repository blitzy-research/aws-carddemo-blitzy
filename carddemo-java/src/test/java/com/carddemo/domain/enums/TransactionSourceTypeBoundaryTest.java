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
package com.carddemo.domain.enums;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TransactionSourceType}, the ten-byte origin vocabulary of
 * the transaction record.
 *
 * <h2>What is under test</h2>
 *
 * <p>The transaction copybook {@code app/cpy/CVTRA05Y.cpy} declares the source as a
 * ten-character alphanumeric field of the three-hundred-and-fifty byte record, so
 * every admitted value is space-padded to exactly ten bytes rather than stored
 * short. Two of the three values are present in the shipped daily-transaction
 * fixture {@code app/data/ASCII/dailytran.txt}, whose three hundred records
 * decompose into two hundred and fifty point-of-sale purchases and fifty
 * operator-originated returns, which is what makes both signed directions of the
 * balance computation reachable from seed data alone. The third value is written
 * only by the interest program {@code app/cbl/CBACT04C.cbl}, which synthesises an
 * interest transaction at lines 473 through 500 and stamps it as system-generated.
 * </p>
 *
 * <h2>The letter case of the third value is deliberately inconsistent</h2>
 *
 * <p>The two operator-facing values are upper case while the synthesised value is
 * mixed case, exactly as the legacy programs write them. That inconsistency is
 * preserved rather than normalised, because the value is compared as stored bytes
 * and a case fold would change the emitted record. The resolution is therefore
 * case-sensitive, and this class asserts that an upper-cased spelling of the
 * synthesised value resolves to nothing.</p>
 *
 * <p>Translated from the CardDemo COBOL estate at checkout commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.</p>
 */
@DisplayName("TransactionSourceType: the ten-byte origin vocabulary of the transaction record")
class TransactionSourceTypeBoundaryTest {

    /** Number of origins the estate writes. */
    private static final int ORIGIN_COUNT = 3;

    /**
     * Measures a value in legacy single-byte characters.
     *
     * @param value the value to measure
     * @return the encoded byte count
     */
    private static int encodedWidth(String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    @Nested
    @DisplayName("the written vocabulary")
    class WrittenVocabulary {

        @Test
        @DisplayName("exactly three origins are defined, matching the three the estate writes")
        void exactlyThreeOriginsAreDefined() {
            assertThat(TransactionSourceType.values()).hasSize(ORIGIN_COUNT);
        }

        @Test
        @DisplayName("the point-of-sale origin carries its legacy value, trailing spaces included")
        void thePointOfSaleOriginCarriesItsLegacyValue() {
            assertThat(TransactionSourceType.POS_TERM.getValue()).isEqualTo("POS TERM  ");
        }

        @Test
        @DisplayName("the operator origin carries its legacy value, trailing spaces included")
        void theOperatorOriginCarriesItsLegacyValue() {
            assertThat(TransactionSourceType.OPERATOR.getValue()).isEqualTo("OPERATOR  ");
        }

        @Test
        @DisplayName("the synthesised origin carries its legacy mixed-case value, spaces included")
        void theSynthesisedOriginCarriesItsLegacyValue() {
            assertThat(TransactionSourceType.SYSTEM.getValue()).isEqualTo("System    ");
        }

        @Test
        @DisplayName("the point-of-sale value keeps its embedded space, so it is not a single token")
        void thePointOfSaleValueKeepsItsEmbeddedSpace() {
            assertThat(TransactionSourceType.POS_TERM.getValue()).startsWith("POS TERM");
        }

        @Test
        @DisplayName("every value is distinct, so a stored source identifies one origin")
        void everyValueIsDistinct() {
            assertThat(Arrays.stream(TransactionSourceType.values())
                    .map(TransactionSourceType::getValue)).doesNotHaveDuplicates();
        }
    }

    @Nested
    @DisplayName("the ten-byte field invariant")
    class FieldWidthInvariant {

        @Test
        @DisplayName("the published width constant is the record field width")
        void thePublishedWidthConstantIsTheFieldWidth() {
            assertThat(TransactionSourceType.VALUE_LENGTH).isEqualTo(10);
        }

        @ParameterizedTest(name = "{0} fills the field to exactly ten bytes")
        @ValueSource(strings = {"POS_TERM", "OPERATOR", "SYSTEM"})
        @DisplayName("every value measures exactly the field width in encoded bytes")
        void everyValueMeasuresTheFieldWidth(String constantName) {
            TransactionSourceType sourceType = TransactionSourceType.valueOf(constantName);

            assertThat(encodedWidth(sourceType.getValue()))
                    .isEqualTo(TransactionSourceType.VALUE_LENGTH);
        }

        @ParameterizedTest(name = "{0} is representable in the legacy character set")
        @ValueSource(strings = {"POS_TERM", "OPERATOR", "SYSTEM"})
        @DisplayName("no value carries a byte outside printable single-byte range")
        void noValueCarriesAnUnprintableByte(String constantName) {
            TransactionSourceType sourceType = TransactionSourceType.valueOf(constantName);

            for (byte encoded : sourceType.getValue().getBytes(StandardCharsets.US_ASCII)) {
                assertThat(encoded).isBetween((byte) 0x20, (byte) 0x7E);
            }
        }

        @Test
        @DisplayName("every value is right-padded rather than left-padded, matching an alphanumeric move")
        void everyValueIsRightPaddedRatherThanLeftPadded() {
            for (TransactionSourceType sourceType : TransactionSourceType.values()) {
                assertThat(sourceType.getValue()).doesNotStartWith(" ");
            }
        }
    }

    @Nested
    @DisplayName("the system-generated predicate")
    class SystemGeneratedPredicate {

        @Test
        @DisplayName("the synthesised origin reports itself as system generated")
        void theSynthesisedOriginReportsItself() {
            assertThat(TransactionSourceType.SYSTEM.isSystemGenerated()).isTrue();
        }

        @ParameterizedTest(name = "{0} does not report itself as system generated")
        @ValueSource(strings = {"POS_TERM", "OPERATOR"})
        @DisplayName("neither operator-facing origin reports itself as system generated")
        void neitherOperatorFacingOriginReportsItself(String constantName) {
            assertThat(TransactionSourceType.valueOf(constantName).isSystemGenerated()).isFalse();
        }

        @Test
        @DisplayName("exactly one origin is system generated, so the predicate partitions the vocabulary")
        void exactlyOneOriginIsSystemGenerated() {
            assertThat(Arrays.stream(TransactionSourceType.values())
                    .filter(TransactionSourceType::isSystemGenerated))
                    .containsExactly(TransactionSourceType.SYSTEM);
        }
    }

    @Nested
    @DisplayName("resolution from a stored value")
    class ResolutionFromValue {

        @ParameterizedTest(name = "the value [{0}] resolves to the origin carrying it")
        @ValueSource(strings = {"POS TERM  ", "OPERATOR  ", "System    "})
        @DisplayName("every written value resolves to its own origin")
        void everyWrittenValueResolves(String value) {
            Optional<TransactionSourceType> resolved = TransactionSourceType.fromValue(value);

            assertThat(resolved).isPresent();
            assertThat(resolved.orElseThrow().getValue()).isEqualTo(value);
        }

        @ParameterizedTest(name = "the value [{0}] resolves to nothing")
        @ValueSource(strings = {
            "POS TERM", "OPERATOR", "System", "SYSTEM    ", "system    ", "POSTERM   ",
            "pos term  ", " POS TERM ", "OPERATOR   ", "BATCH     ", "          ", "MERCHANT  "})
        @DisplayName("an unpadded, case-shifted or foreign value resolves to nothing")
        void anUnpaddedOrForeignValueResolvesToNothing(String value) {
            assertThat(TransactionSourceType.fromValue(value)).isEmpty();
        }

        @ParameterizedTest
        @NullSource
        @DisplayName("a null value resolves to an empty result rather than throwing")
        void aNullValueResolvesToNothing(String value) {
            assertThat(TransactionSourceType.fromValue(value)).isEmpty();
        }

        @ParameterizedTest
        @EmptySource
        @DisplayName("an empty value resolves to an empty result rather than throwing")
        void anEmptyValueResolvesToNothing(String value) {
            assertThat(TransactionSourceType.fromValue(value)).isEmpty();
        }

        @Test
        @DisplayName("resolution is case-sensitive, so the synthesised value keeps its mixed case")
        void resolutionIsCaseSensitive() {
            assertThat(TransactionSourceType.fromValue("System    "))
                    .containsSame(TransactionSourceType.SYSTEM);
            assertThat(TransactionSourceType.fromValue("SYSTEM    ")).isEmpty();
            assertThat(TransactionSourceType.fromValue("system    ")).isEmpty();
        }

        @Test
        @DisplayName("resolution covers every declared origin, so no origin is unreachable")
        void resolutionCoversEveryDeclaredOrigin() {
            for (TransactionSourceType sourceType : TransactionSourceType.values()) {
                assertThat(TransactionSourceType.fromValue(sourceType.getValue()))
                        .containsSame(sourceType);
            }
        }
    }
}
