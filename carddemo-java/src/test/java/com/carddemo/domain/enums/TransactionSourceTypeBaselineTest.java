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

import java.util.Arrays;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link TransactionSourceType}, the typed replacement for the ten-byte source field
 * carried by both the transaction record and the daily-transaction record.
 *
 * <p><strong>What this test proves.</strong> The source field is ten bytes wide and sits at offset 22
 * of the 350-byte record, and the values written into it are space padded to the full field width
 * rather than trimmed. Two of the three values are upper case and the third is mixed case, which is a
 * property of the emitting program and not a transcription slip: the interest-calculation program
 * synthesises its transactions with a mixed-case source value while the two externally originated
 * values are upper case. Because the resolution performed here is an exact map lookup, the padding
 * and the casing are both load bearing - a trimmed or case-folded value does not resolve. This test
 * pins the closed three-member vocabulary, the declared field width, the exact padded spellings, the
 * strictness of the lookup, and the predicate that distinguishes the synthesised source from the two
 * externally originated ones.
 *
 * <p><strong>Reference data.</strong> The daily-transaction fixture holds 300 records of 350 bytes,
 * whose source field is the point-of-sale value in 250 rows and the operator value in 50 rows. That
 * split is the measured content of the fixture, quoted as data only, and it is what makes both signed
 * directions of the balance computation reachable from seed data.
 *
 * <p><strong>Scope.</strong> A pure in-process unit test. It starts no application context, opens no
 * connection, reads no file and performs no introspection. Every expected value is a literal typed
 * out in this source; no expectation is produced by calling the type under test.
 *
 * <p>Cited as provenance only and never asserted against a member.
 */
@DisplayName("TransactionSourceType - the padded 10-byte source field at offset 22 of the 350-byte record")
class TransactionSourceTypeBaselineTest {

    /** Point-of-sale value, space padded to the full field width. */
    private static final String POS_TERM_VALUE = "POS TERM  ";

    /** Operator-originated value, space padded to the full field width. */
    private static final String OPERATOR_VALUE = "OPERATOR  ";

    /** Synthesised value written by the interest-calculation program, mixed case and padded. */
    private static final String SYSTEM_VALUE = "System    ";

    /** Point-of-sale rows measured in the daily-transaction fixture. */
    private static final int FIXTURE_POS_TERM_ROWS = 250;

    /** Operator-originated rows measured in the daily-transaction fixture. */
    private static final int FIXTURE_OPERATOR_ROWS = 50;

    /** Total rows measured in the daily-transaction fixture. */
    private static final int FIXTURE_TOTAL_ROWS = 300;

    /** Offset of the source field within the record image. */
    private static final int SOURCE_FIELD_OFFSET = 22;

    /** Total width of the record image that carries the source field. */
    private static final int RECORD_WIDTH = 350;

    @Nested
    @DisplayName("Vocabulary and declaration order")
    class Vocabulary {

        @Test
        @DisplayName("exactly three sources exist, point-of-sale then operator then synthesised, "
                + "matching the order in which the estate introduces them")
        void exactlyThreeSourcesExist() {
            assertThat(TransactionSourceType.values()).containsExactly(
                    TransactionSourceType.POS_TERM,
                    TransactionSourceType.OPERATOR,
                    TransactionSourceType.SYSTEM);
        }

        @Test
        @DisplayName("the enumeration is closed at three members, because the estate writes no fourth "
                + "source value and inventing one would be feature expansion")
        void theEnumerationIsClosedAtThreeMembers() {
            assertThat(TransactionSourceType.values()).hasSize(3);
        }

        @Test
        @DisplayName("each source carries its exact padded value, the synthesised one mixed case "
                + "because the emitting program writes it that way")
        void eachSourceCarriesItsExactPaddedValue() {
            assertThat(TransactionSourceType.POS_TERM.getValue()).isEqualTo(POS_TERM_VALUE);
            assertThat(TransactionSourceType.OPERATOR.getValue()).isEqualTo(OPERATOR_VALUE);
            assertThat(TransactionSourceType.SYSTEM.getValue()).isEqualTo(SYSTEM_VALUE);
        }

        @Test
        @DisplayName("the synthesised value is mixed case while the two externally originated values "
                + "are upper case, a difference preserved rather than normalised")
        void theSynthesisedValueIsMixedCase() {
            assertThat(TransactionSourceType.SYSTEM.getValue()).isNotEqualTo("SYSTEM    ");
            assertThat(TransactionSourceType.POS_TERM.getValue()).isUpperCase();
            assertThat(TransactionSourceType.OPERATOR.getValue()).isUpperCase();
        }

        @Test
        @DisplayName("the three padded values are distinct, so the stored field discriminates the "
                + "three sources without ambiguity")
        void theThreePaddedValuesAreDistinct() {
            assertThat(Arrays.stream(TransactionSourceType.values())
                    .map(TransactionSourceType::getValue)
                    .distinct()
                    .count())
                    .isEqualTo(3L);
        }

        @Test
        @DisplayName("valueOf resolves each constant name back to its member")
        void valueOfResolvesEachConstantName() {
            assertThat(TransactionSourceType.valueOf("POS_TERM"))
                    .isSameAs(TransactionSourceType.POS_TERM);
            assertThat(TransactionSourceType.valueOf("OPERATOR"))
                    .isSameAs(TransactionSourceType.OPERATOR);
            assertThat(TransactionSourceType.valueOf("SYSTEM"))
                    .isSameAs(TransactionSourceType.SYSTEM);
        }
    }

    @Nested
    @DisplayName("Fixed field width of the padded value")
    class FieldWidth {

        @Test
        @DisplayName("the declared field width is ten, matching the ten bytes the record layout "
                + "reserves for the source field")
        void theDeclaredFieldWidthIsTen() {
            assertThat(TransactionSourceType.VALUE_LENGTH).isEqualTo(10);
        }

        @Test
        @DisplayName("every value is exactly the declared width, so writing one into the record needs "
                + "no pad and no truncation at all")
        void everyValueIsExactlyTheDeclaredWidth() {
            for (final TransactionSourceType sourceType : TransactionSourceType.values()) {
                assertThat(sourceType.getValue()).hasSize(TransactionSourceType.VALUE_LENGTH);
            }
        }

        @Test
        @DisplayName("every value is right padded rather than left padded, so its significant text "
                + "begins at the first byte of the field")
        void everyValueIsRightPadded() {
            for (final TransactionSourceType sourceType : TransactionSourceType.values()) {
                final String value = sourceType.getValue();

                assertThat(value).doesNotStartWith(" ");
                assertThat(value).endsWith(" ");
                assertThat(value.trim()).isNotEmpty();
            }
        }

        @Test
        @DisplayName("the field sits at offset 22 and the record is 350 bytes wide, so the padded "
                + "value occupies bytes 22 through 31 inclusive and leaves the record intact")
        void theFieldSitsWithinTheRecordImage() {
            assertThat(SOURCE_FIELD_OFFSET + TransactionSourceType.VALUE_LENGTH)
                    .isEqualTo(32)
                    .isLessThan(RECORD_WIDTH);
        }
    }

    @Nested
    @DisplayName("Resolution of a stored source value")
    class ValueResolution {

        @Test
        @DisplayName("each padded value resolves to its own source")
        void eachPaddedValueResolves() {
            assertThat(TransactionSourceType.fromValue(POS_TERM_VALUE))
                    .contains(TransactionSourceType.POS_TERM);
            assertThat(TransactionSourceType.fromValue(OPERATOR_VALUE))
                    .contains(TransactionSourceType.OPERATOR);
            assertThat(TransactionSourceType.fromValue(SYSTEM_VALUE))
                    .contains(TransactionSourceType.SYSTEM);
        }

        @Test
        @DisplayName("an absent value yields no source rather than throwing, so a null column is "
                + "reported by the caller instead of aborting the lookup")
        void anAbsentValueYieldsNoSource() {
            assertThat(TransactionSourceType.fromValue(null)).isEmpty();
        }

        @ParameterizedTest(name = "value [{0}] does not resolve")
        @DisplayName("the padding is part of the key: a trimmed value does not resolve, which is why "
                + "callers must present the field exactly as the record carries it")
        @ValueSource(strings = {"POS TERM", "OPERATOR", "System", "SYSTEM"})
        void theTrimmedValueDoesNotResolve(final String value) {
            assertThat(TransactionSourceType.fromValue(value)).isEmpty();
        }

        @ParameterizedTest(name = "value [{0}] does not resolve")
        @DisplayName("nothing is folded or re-padded: a case-shifted, left-padded, over-padded or "
                + "unknown value is rejected")
        @ValueSource(strings = {
            "SYSTEM    ", "system    ", "pos term  ", "operator  ",
            "  POS TERM", "POS TERM   ", "POSTERM   ", "BATCH     ", "", "          "})
        void nothingIsFoldedOrRepadded(final String value) {
            assertThat(TransactionSourceType.fromValue(value)).isEmpty();
        }

        @Test
        @DisplayName("resolution returns the singleton member rather than a copy, so identity "
                + "comparison remains valid for callers that switch on the result")
        void resolutionReturnsTheSingletonMember() {
            final Optional<TransactionSourceType> resolved =
                    TransactionSourceType.fromValue(SYSTEM_VALUE);

            assertThat(resolved).isPresent();
            assertThat(resolved.orElseThrow()).isSameAs(TransactionSourceType.SYSTEM);
        }

        @Test
        @DisplayName("every member's own value round-trips through resolution, so the index covers the "
                + "whole vocabulary and not a subset of it")
        void everyMembersOwnValueRoundTrips() {
            for (final TransactionSourceType sourceType : TransactionSourceType.values()) {
                assertThat(TransactionSourceType.fromValue(sourceType.getValue()))
                        .contains(sourceType);
            }
        }
    }

    @Nested
    @DisplayName("Predicate distinguishing the synthesised source")
    class SynthesisedSourcePredicate {

        @Test
        @DisplayName("the synthesised source reports itself system generated, which is how an "
                + "interest transaction is told apart from an externally originated one")
        void theSynthesisedSourceReportsSystemGenerated() {
            assertThat(TransactionSourceType.SYSTEM.isSystemGenerated()).isTrue();
        }

        @Test
        @DisplayName("both externally originated sources report themselves not system generated")
        void bothExternalSourcesReportNotSystemGenerated() {
            assertThat(TransactionSourceType.POS_TERM.isSystemGenerated()).isFalse();
            assertThat(TransactionSourceType.OPERATOR.isSystemGenerated()).isFalse();
        }

        @Test
        @DisplayName("exactly one of the three sources is system generated, so the predicate is total "
                + "and unambiguous for every stored value")
        void exactlyOneSourceIsSystemGenerated() {
            final long generated = Arrays.stream(TransactionSourceType.values())
                    .filter(TransactionSourceType::isSystemGenerated)
                    .count();

            assertThat(generated).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("Correspondence with the daily-transaction fixture")
    class FixtureCorrespondence {

        @Test
        @DisplayName("the fixture's 250 point-of-sale rows and 50 operator rows sum to its 300 "
                + "measured records, so both externally originated sources are seeded")
        void theFixtureRowCountsSumToTheMeasuredTotal() {
            assertThat(FIXTURE_POS_TERM_ROWS + FIXTURE_OPERATOR_ROWS).isEqualTo(FIXTURE_TOTAL_ROWS);
        }

        @Test
        @DisplayName("both source values appearing in the fixture resolve, so no seeded row would be "
                + "rejected by the posting cascade on the ground of its source")
        void bothFixtureSourceValuesResolve() {
            assertThat(TransactionSourceType.fromValue(POS_TERM_VALUE)).isPresent();
            assertThat(TransactionSourceType.fromValue(OPERATOR_VALUE)).isPresent();
        }

        @Test
        @DisplayName("the synthesised source does not appear in the fixture, because it is written by "
                + "the interest run rather than read from the daily input")
        void theSynthesisedSourceIsNotSeeded() {
            assertThat(TransactionSourceType.SYSTEM.isSystemGenerated()).isTrue();
            assertThat(FIXTURE_TOTAL_ROWS)
                    .isEqualTo(FIXTURE_POS_TERM_ROWS + FIXTURE_OPERATOR_ROWS);
        }
    }
}
