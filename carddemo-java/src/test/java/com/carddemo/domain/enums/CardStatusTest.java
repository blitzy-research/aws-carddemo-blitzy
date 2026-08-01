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
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link CardStatus}, the card availability flag.
 *
 * <p><strong>What the legacy authority is.</strong> The field is
 * {@code CARD-ACTIVE-STATUS PIC X(01)} declared at line 10 of {@code app/cpy/CVACT02Y.cpy}, the
 * sixth of seven fields in the 150-byte card record. The widths declared there &mdash; sixteen for
 * the card number, eleven for the account identifier, three for the verification code, fifty for the
 * embossed name, ten for the expiry date, one for this status and fifty-nine of trailing filler
 * &mdash; sum to the 150 bytes the cluster declares, which is how the status byte's position is
 * established.
 *
 * <p><strong>Why the constants are named after their codes.</strong> This enumeration names its two
 * constants {@code Y} and {@code N}, so the constant name and the stored byte coincide. That is
 * unusual for a Java enumeration and is deliberate: it keeps the mapping between the record image and
 * the type impossible to get wrong by a single character. This class pins that coincidence, because a
 * later rename to a more descriptive pair of names would break any caller that resolves a constant by
 * name from a record image.
 *
 * <p><strong>Why one byte matters.</strong> The status occupies exactly one byte in a fixed-width
 * record. A code that encoded to two bytes would push every following field one position right and
 * corrupt the fifty-nine-byte filler boundary, so this class asserts the single-byte encoding of both
 * codes rather than assuming it.
 *
 * <p><strong>Why lookup is tolerant but not lenient.</strong> The batch readers hand this type raw
 * bytes taken straight from a record with no prior validation, so an unmapped byte must produce an
 * empty result rather than an exception &mdash; a corrupt byte in one record must not abort a
 * sequential read of fifty. Tolerant is not the same as lenient, though: no case folding is applied,
 * no trimming is applied, and a value that is not exactly one character long is rejected outright
 * rather than truncated to its first byte.
 *
 * <p><strong>Deliberately not asserted.</strong> There is no constant for a blank or absent status.
 * The legacy record carries no third code, and inventing one would be feature expansion. There is no
 * conversion between this type and the account status flag: the two happen to share the codes
 * {@code Y} and {@code N} but they are separate fields in separate records, and conflating them would
 * let a card status satisfy an account check.
 */
@DisplayName("CardStatus — the one-byte card availability flag")
class CardStatusTest {

    /** Width of the status field, from the copybook. */
    private static final int STATUS_WIDTH = 1;

    /** The seven declared field widths of the card record, in copybook order. */
    private static final int[] CARD_RECORD_FIELD_WIDTHS = {16, 11, 3, 50, 10, 1, 59};

    /** The record width the cluster definition declares. */
    private static final int DECLARED_RECORD_WIDTH = 150;

    /** The one-based offset at which the status byte begins. */
    private static final int STATUS_ONE_BASED_OFFSET = 91;

    // =================================================================================================
    // VOCABULARY
    // =================================================================================================

    /**
     * Verifies the vocabulary the copybook admits.
     */
    @Nested
    @DisplayName("vocabulary")
    class Vocabulary {

        @Test
        @DisplayName("exactly two constants exist, because the record admits exactly two codes")
        void exactlyTwoConstantsExist() {
            assertThat(CardStatus.values()).hasSize(2);
        }

        @Test
        @DisplayName("the two constants carry the codes Y and N")
        void theConstantsCarryTheirCodes() {
            assertThat(CardStatus.Y.getCode()).isEqualTo('Y');
            assertThat(CardStatus.N.getCode()).isEqualTo('N');
        }

        @Test
        @DisplayName("each constant is named after the byte it stores, so a record image and a constant "
                + "name cannot drift apart")
        void eachConstantIsNamedAfterItsByte() {
            for (final CardStatus status : CardStatus.values()) {
                assertThat(status.name())
                        .as("constant name against the stored byte")
                        .isEqualTo(String.valueOf(status.getCode()));
            }
        }

        @Test
        @DisplayName("the active code is declared before the inactive one")
        void theActiveCodeIsDeclaredFirst() {
            assertThat(CardStatus.values()).containsExactly(CardStatus.Y, CardStatus.N);
        }

        @Test
        @DisplayName("the two codes are distinct, so no record byte resolves ambiguously")
        void theTwoCodesAreDistinct() {
            assertThat(CardStatus.Y.getCode()).isNotEqualTo(CardStatus.N.getCode());
        }
    }

    // =================================================================================================
    // BYTE GEOMETRY
    // =================================================================================================

    /**
     * Verifies that each code fills the one-byte field exactly, inside a fully accounted-for record.
     */
    @Nested
    @DisplayName("byte geometry inside the 150-byte card record")
    class ByteGeometry {

        @Test
        @DisplayName("each code encodes to exactly one byte, so no code can displace the fields after it")
        void eachCodeEncodesToOneByte() {
            for (final CardStatus status : CardStatus.values()) {
                assertThat(String.valueOf(status.getCode()).getBytes(StandardCharsets.US_ASCII))
                        .as("encoded width of the code carried by %s", status.name())
                        .hasSize(STATUS_WIDTH);
            }
        }

        @Test
        @DisplayName("the seven declared field widths sum to the 150 bytes the cluster declares")
        void theDeclaredWidthsSumToTheRecordWidth() {
            int summed = 0;
            for (final int width : CARD_RECORD_FIELD_WIDTHS) {
                summed += width;
            }

            assertThat(CARD_RECORD_FIELD_WIDTHS).hasSize(7);
            assertThat(summed)
                    .as("summed declared field widths against RECORDSIZE(150 150)")
                    .isEqualTo(DECLARED_RECORD_WIDTH);
        }

        @Test
        @DisplayName("the status begins at byte 91, immediately after the ten-byte expiry date")
        void theStatusBeginsAtByteNinetyOne() {
            int bytesPreceding = 0;
            for (int field = 0; field < 5; field++) {
                bytesPreceding += CARD_RECORD_FIELD_WIDTHS[field];
            }

            assertThat(bytesPreceding + 1)
                    .as("one-based offset derived by summing the widths declared ahead of the status")
                    .isEqualTo(STATUS_ONE_BASED_OFFSET);
            assertThat(CARD_RECORD_FIELD_WIDTHS[5])
                    .as("width of the status field itself")
                    .isEqualTo(STATUS_WIDTH);
        }

        @Test
        @DisplayName("the fifty-nine-byte filler closes the record exactly, so the status sits inside a "
                + "layout with no unaccounted bytes")
        void theFillerClosesTheRecordExactly() {
            final int throughStatus = STATUS_ONE_BASED_OFFSET - 1 + STATUS_WIDTH;

            assertThat(throughStatus + CARD_RECORD_FIELD_WIDTHS[6])
                    .isEqualTo(DECLARED_RECORD_WIDTH);
        }
    }

    // =================================================================================================
    // ACTIVE PREDICATE
    // =================================================================================================

    /**
     * Verifies the availability predicate.
     */
    @Nested
    @DisplayName("availability predicate")
    class AvailabilityPredicate {

        @Test
        @DisplayName("only the Y constant reports itself active")
        void onlyTheYConstantIsActive() {
            assertThat(CardStatus.Y.isActive()).isTrue();
            assertThat(CardStatus.N.isActive()).isFalse();
        }

        @Test
        @DisplayName("the predicate partitions the vocabulary, so exactly one constant is active")
        void thePredicatePartitionsTheVocabulary() {
            long active = 0;
            for (final CardStatus status : CardStatus.values()) {
                if (status.isActive()) {
                    active++;
                }
            }

            assertThat(active).isEqualTo(1);
        }
    }

    // =================================================================================================
    // LOOKUP
    // =================================================================================================

    /**
     * Verifies the tolerant lookup from a raw record byte to a constant.
     */
    @Nested
    @DisplayName("lookup from a raw record byte")
    class Lookup {

        @Test
        @DisplayName("both codes resolve from a character")
        void bothCodesResolveFromACharacter() {
            assertThat(CardStatus.fromCode('Y')).contains(CardStatus.Y);
            assertThat(CardStatus.fromCode('N')).contains(CardStatus.N);
        }

        @Test
        @DisplayName("both codes resolve from a one-character column value")
        void bothCodesResolveFromAColumnValue() {
            assertThat(CardStatus.fromCode("Y")).contains(CardStatus.Y);
            assertThat(CardStatus.fromCode("N")).contains(CardStatus.N);
        }

        @Test
        @DisplayName("every constant round-trips through its own code by both lookup forms")
        void everyConstantRoundTrips() {
            for (final CardStatus status : CardStatus.values()) {
                assertThat(CardStatus.fromCode(status.getCode()))
                        .as("character round trip of %s", status.name())
                        .contains(status);
                assertThat(CardStatus.fromCode(String.valueOf(status.getCode())))
                        .as("column round trip of %s", status.name())
                        .contains(status);
            }
        }

        @Test
        @DisplayName("a byte outside the vocabulary yields an empty result rather than an exception, so "
                + "one corrupt record cannot abort a sequential read")
        void anUnmappedByteYieldsAnEmptyResult() {
            assertThat(CardStatus.fromCode('X')).isEmpty();
            assertThat(CardStatus.fromCode(' ')).isEmpty();
            assertThat(CardStatus.fromCode('0')).isEmpty();
            assertThat(CardStatus.fromCode('\u0000')).isEmpty();
        }

        @Test
        @DisplayName("no case folding is applied, so a lowercase y is not an available card")
        void noCaseFoldingIsApplied() {
            assertThat(CardStatus.fromCode('y')).isEmpty();
            assertThat(CardStatus.fromCode("y")).isEmpty();
            assertThat(CardStatus.fromCode("n")).isEmpty();
        }

        @Test
        @DisplayName("an over-length column value is rejected outright rather than truncated to its "
                + "first byte")
        void anOverLengthValueIsRejectedRatherThanTruncated() {
            assertThat(CardStatus.fromCode("YY")).isEmpty();
            assertThat(CardStatus.fromCode("Y ")).isEmpty();
            assertThat(CardStatus.fromCode(" Y")).isEmpty();
        }

        @Test
        @DisplayName("an empty or absent column value yields an empty result")
        void anEmptyOrAbsentValueYieldsAnEmptyResult() {
            assertThat(CardStatus.fromCode("")).isEmpty();
            assertThat(CardStatus.fromCode((String) null)).isEmpty();
        }

        @Test
        @DisplayName("lookup never returns an absent reference for any input, so a caller can chain "
                + "without a null check")
        void lookupNeverReturnsNull() {
            final Optional<CardStatus> mapped = CardStatus.fromCode("Y");
            final Optional<CardStatus> unmapped = CardStatus.fromCode("?");

            assertThat(mapped).isNotNull().isPresent();
            assertThat(unmapped).isNotNull().isEmpty();
            assertThat(CardStatus.fromCode((String) null)).isNotNull();
        }
    }
}
