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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link TransactionSourceType}, the {@code TRAN-SOURCE} channel declared
 * {@code PIC X(10)} in copybook member {@code CVTRA05Y}.
 *
 * <h2>The padding is the contract, and mixed casing is part of it</h2>
 *
 * <p>All three images are ten characters wide because the underlying field is. Two of them are
 * upper case and space padded - {@code POS TERM} and {@code OPERATOR} - and the third is the
 * mixed-case literal {@code System} that {@code CBACT04C} writes when it synthesises an interest
 * transaction. That mixed casing is not a tidiness defect to be normalised: it is the byte image
 * the estate writes into a 350-byte record, and Gate 1 compares those bytes. The assertions below
 * therefore pin each image exactly and prove that neither an upper-cased nor a trimmed variant
 * resolves.</p>
 *
 * <h2>No synthetic fallback constant exists</h2>
 *
 * <p>A live data set can hold an image the estate never writes, and such an image must pass through
 * unharmed rather than being coerced into a catch-all. Absence is therefore modelled by an empty
 * {@link java.util.Optional} and the lookup is total: it never throws, including for {@code null},
 * because the unmodifiable index would reject a null key.</p>
 */
@DisplayName("TransactionSourceType - the TRAN-SOURCE channel from CVTRA05Y")
class TransactionSourceTypeSecurityTest {

    @Nested
    @DisplayName("Vocabulary recovered from the three images the estate writes")
    class Vocabulary {

        @Test
        @DisplayName("exactly three constants are declared, one per channel the estate writes")
        void exactlyThreeConstantsAreDeclared() {
            assertThat(TransactionSourceType.values()).hasSize(3);
        }

        @Test
        @DisplayName("the constants are POS_TERM, OPERATOR and SYSTEM in that order")
        void theConstantsAreDeclaredInSourceOrder() {
            assertThat(Arrays.stream(TransactionSourceType.values()).map(Enum::name).toList())
                    .containsExactly("POS_TERM", "OPERATOR", "SYSTEM");
        }

        @Test
        @DisplayName("no synthetic fallback constant exists, because an image the estate never writes must pass "
                + "through unharmed rather than be coerced")
        void noSyntheticFallbackConstantExists() {
            assertThat(Arrays.stream(TransactionSourceType.values()).map(Enum::name).toList())
                    .doesNotContain("UNKNOWN", "OTHER", "NONE", "DEFAULT", "INVALID", "UNMAPPED");
        }

        @Test
        @DisplayName("the declared field width is published as ten, matching the PIC X(10) clause on TRAN-SOURCE")
        void theDeclaredFieldWidthIsTen() {
            assertThat(TransactionSourceType.VALUE_LENGTH).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("Ten-character images, padding and casing included")
    class ImageWidthAndCasing {

        @ParameterizedTest
        @EnumSource(TransactionSourceType.class)
        @DisplayName("every image is exactly ten characters wide, matching the published field width")
        void everyImageIsExactlyTenCharactersWide(final TransactionSourceType source) {
            assertThat(source.getValue()).hasSize(TransactionSourceType.VALUE_LENGTH);
        }

        @ParameterizedTest
        @EnumSource(TransactionSourceType.class)
        @DisplayName("every image encodes to exactly ten bytes, so it fills the fixed-width field in the record")
        void everyImageEncodesToExactlyTenBytes(final TransactionSourceType source) {
            assertThat(source.getValue().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(TransactionSourceType.VALUE_LENGTH);
        }

        @Test
        @DisplayName("the point-of-sale image is POS TERM with two trailing spaces, embedded space included")
        void thePointOfSaleImageIsExact() {
            assertThat(TransactionSourceType.POS_TERM.getValue()).isEqualTo("POS TERM  ");
        }

        @Test
        @DisplayName("the operator image is OPERATOR with two trailing spaces")
        void theOperatorImageIsExact() {
            assertThat(TransactionSourceType.OPERATOR.getValue()).isEqualTo("OPERATOR  ");
        }

        @Test
        @DisplayName("the system image is the mixed-case literal System with four trailing spaces, exactly as "
                + "CBACT04C writes it, and is not upper cased")
        void theSystemImageKeepsItsMixedCasing() {
            assertThat(TransactionSourceType.SYSTEM.getValue()).isEqualTo("System    ");
            assertThat(TransactionSourceType.SYSTEM.getValue()).isNotEqualTo("SYSTEM    ");
        }

        @Test
        @DisplayName("all three images are distinct, so the reverse index cannot collide")
        void allThreeImagesAreDistinct() {
            assertThat(Arrays.stream(TransactionSourceType.values())
                    .map(TransactionSourceType::getValue).distinct().count()).isEqualTo(3L);
        }
    }

    @Nested
    @DisplayName("Total lookup from a raw field image")
    class ImageLookup {

        @ParameterizedTest
        @EnumSource(TransactionSourceType.class)
        @DisplayName("every image round-trips through the lookup back to the channel that carries it")
        void everyImageRoundTrips(final TransactionSourceType source) {
            assertThat(TransactionSourceType.fromValue(source.getValue())).contains(source);
        }

        @ParameterizedTest
        @NullSource
        @DisplayName("an absent image yields an empty result rather than throwing, which is what makes the lookup "
                + "total against an unmodifiable index that rejects a null key")
        void anAbsentImageYieldsAnEmptyResult(final String value) {
            assertThat(TransactionSourceType.fromValue(value)).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"POS TERM", "OPERATOR", "System", "SYSTEM    ", "system    ",
                "pos term  ", "POSTERM   ", "POS TERM   ", " POS TERM ", "", " ", "          ",
                "BATCH     ", "ATM       ", "0"})
        @DisplayName("a trimmed, re-cased, differently padded or unknown image yields an empty result, because no "
                + "normalisation is applied before the lookup")
        void aNonExactImageYieldsAnEmptyResult(final String value) {
            assertThat(TransactionSourceType.fromValue(value)).isEmpty();
        }

        @Test
        @DisplayName("an over-length image is reported absent rather than truncated to a match")
        void anOverLengthImageIsNotTruncated() {
            assertThat(TransactionSourceType.fromValue("POS TERM  X")).isEmpty();
        }
    }

    @Nested
    @DisplayName("System-generated predicate")
    class SystemGeneratedPredicate {

        @Test
        @DisplayName("only the system channel is system generated, because only the interest-calculation batch "
                + "program synthesises transactions of its own accord")
        void onlyTheSystemChannelIsSystemGenerated() {
            assertThat(TransactionSourceType.SYSTEM.isSystemGenerated()).isTrue();
        }

        @Test
        @DisplayName("the two externally originated channels are not system generated")
        void theTwoExternalChannelsAreNotSystemGenerated() {
            assertThat(TransactionSourceType.POS_TERM.isSystemGenerated()).isFalse();
            assertThat(TransactionSourceType.OPERATOR.isSystemGenerated()).isFalse();
        }

        @Test
        @DisplayName("exactly one of the three channels reports itself system generated, so the predicate cannot "
                + "have been widened without this test failing")
        void exactlyOneChannelIsSystemGenerated() {
            assertThat(Arrays.stream(TransactionSourceType.values())
                    .filter(TransactionSourceType::isSystemGenerated).count()).isEqualTo(1L);
        }

        @ParameterizedTest
        @EnumSource(TransactionSourceType.class)
        @DisplayName("the predicate answers for every declared channel without throwing, so its exhaustive switch "
                + "genuinely covers the vocabulary")
        void thePredicateAnswersForEveryChannel(final TransactionSourceType source) {
            assertThat(source.isSystemGenerated())
                    .isEqualTo(source == TransactionSourceType.SYSTEM);
        }
    }
}
