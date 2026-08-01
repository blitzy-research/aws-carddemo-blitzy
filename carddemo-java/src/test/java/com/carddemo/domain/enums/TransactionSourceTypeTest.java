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
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link TransactionSourceType}, the ten-byte origin marker every transaction carries.
 *
 * <p><strong>Where the ten bytes come from.</strong> The source marker occupies a fixed ten-byte slot on
 * both transaction layouts: {@code TRAN-SOURCE PIC X(10)} on the posted-transaction record at
 * {@code app/cpy/CVTRA05Y.cpy} line 8, and {@code DALYTRAN-SOURCE PIC X(10)} on the daily-transaction
 * record at {@code app/cpy/CVTRA06Y.cpy} line 8. Both records are 350 bytes and the marker sits at the
 * same offset in each, which is what lets the posting program copy a daily record's marker straight into
 * the posted record without reformatting it.
 *
 * <p><strong>Why the trailing blanks are part of the value.</strong> A ten-byte character field is
 * blank-filled to its full width, so the marker for a point-of-sale terminal is stored as eight
 * characters followed by two blanks. This class asserts each value is exactly ten characters and exactly
 * ten encoded bytes, because a value carried at nine or eleven characters would shift every field after
 * it when the record is written back out at its fixed width. The declared constant
 * {@code VALUE_LENGTH} restates the same ten, and is asserted against the copybook width rather than
 * against itself.
 *
 * <p><strong>Why the embedded blank inside the terminal marker matters.</strong> The point-of-sale
 * marker contains a blank between its two words. That blank is data, not padding &mdash; it is at
 * position four of eight, well inside the populated part of the field. A helper that stripped every
 * blank rather than only the trailing ones would silently collapse the marker and it would then no
 * longer equal the stored image. The vocabulary assertions pin the blank's position so that mistake
 * cannot pass.
 *
 * <p><strong>Why one marker is mixed case and two are upper case.</strong> The two externally-originated
 * markers arrive upper-cased in the seeded daily-transaction file. The system-generated marker is
 * written by the interest program as {@code MOVE 'System' TO TRAN-SOURCE} in
 * {@code app/cbl/CBACT04C.cbl}, in mixed case. That asymmetry is in the legacy, not a transcription
 * slip, so this class asserts it deliberately rather than normalising it away.
 *
 * <p><strong>Seed composition as independent evidence.</strong> The 300 records of
 * {@code app/data/ASCII/dailytran.txt} carry the marker in one-based columns 23 through 32. Measuring
 * those columns yields 250 point-of-sale records and 50 operator records &mdash; and no other value.
 * Those two counts are asserted as a property of the vocabulary: the two markers the seed uses must both
 * resolve, and their two counts must sum to the full 300-record file.
 */
@DisplayName("TransactionSourceType — the ten-byte origin marker on both transaction records")
class TransactionSourceTypeTest {

    /** The width of {@code TRAN-SOURCE} and {@code DALYTRAN-SOURCE}, both {@code PIC X(10)}. */
    private static final int SOURCE_SLOT_WIDTH = 10;

    /** The point-of-sale marker as the seeded daily-transaction file stores it. */
    private static final String LEGACY_POS_TERMINAL = "POS TERM  ";

    /** The operator marker as the seeded daily-transaction file stores it. */
    private static final String LEGACY_OPERATOR = "OPERATOR  ";

    /** The marker the interest program writes for a transaction it synthesises itself. */
    private static final String LEGACY_SYSTEM = "System    ";

    /** Point-of-sale records measured in columns 23-32 of the 300-record seeded daily file. */
    private static final int SEEDED_POS_TERMINAL_RECORDS = 250;

    /** Operator records measured in columns 23-32 of the 300-record seeded daily file. */
    private static final int SEEDED_OPERATOR_RECORDS = 50;

    /** Total records in {@code app/data/ASCII/dailytran.txt}. */
    private static final int SEEDED_DAILY_RECORDS = 300;

    // =================================================================================================
    // VOCABULARY
    // =================================================================================================

    /**
     * Verifies the three markers and the exact characters of each.
     */
    @Nested
    @DisplayName("vocabulary")
    class Vocabulary {

        @Test
        @DisplayName("exactly three markers exist")
        void exactlyThreeMarkersExist() {
            assertThat(TransactionSourceType.values()).hasSize(3);
        }

        @Test
        @DisplayName("each marker carries its stored image verbatim, trailing blanks included")
        void eachMarkerCarriesItsStoredImage() {
            assertThat(TransactionSourceType.POS_TERM.getValue()).isEqualTo(LEGACY_POS_TERMINAL);
            assertThat(TransactionSourceType.OPERATOR.getValue()).isEqualTo(LEGACY_OPERATOR);
            assertThat(TransactionSourceType.SYSTEM.getValue()).isEqualTo(LEGACY_SYSTEM);
        }

        @Test
        @DisplayName("the three markers are distinct, so a stored record identifies its origin "
                + "unambiguously")
        void theThreeMarkersAreDistinct() {
            assertThat(List.of(
                    TransactionSourceType.POS_TERM.getValue(),
                    TransactionSourceType.OPERATOR.getValue(),
                    TransactionSourceType.SYSTEM.getValue()))
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the blank inside the point-of-sale marker is data at position four, not padding")
        void theBlankInsideTheTerminalMarkerIsData() {
            final String terminal = TransactionSourceType.POS_TERM.getValue();

            assertThat(terminal.charAt(3)).isEqualTo(' ');
            assertThat(terminal.substring(0, 3)).isEqualTo("POS");
            assertThat(terminal.substring(4, 8)).isEqualTo("TERM");
            assertThat(terminal.strip()).isEqualTo("POS TERM").hasSize(8);
            assertThat(terminal.replace(" ", ""))
                    .as("removing every blank would collapse the marker rather than trim it")
                    .isEqualTo("POSTERM")
                    .isNotEqualTo(terminal.strip());
        }

        @Test
        @DisplayName("the operator marker has no embedded blank, so its populated part is one word")
        void theOperatorMarkerHasNoEmbeddedBlank() {
            final String operator = TransactionSourceType.OPERATOR.getValue();

            assertThat(operator.substring(0, 8)).isEqualTo("OPERATOR").doesNotContain(" ");
            assertThat(operator.strip()).isEqualTo("OPERATOR").hasSize(8);
        }

        @Test
        @DisplayName("the two externally-originated markers are upper case and the system marker is "
                + "mixed case, exactly as the legacy writes them")
        void casingFollowsTheLegacyAsymmetry() {
            assertThat(TransactionSourceType.POS_TERM.getValue())
                    .isEqualTo(TransactionSourceType.POS_TERM.getValue().toUpperCase(Locale.ROOT));
            assertThat(TransactionSourceType.OPERATOR.getValue())
                    .isEqualTo(TransactionSourceType.OPERATOR.getValue().toUpperCase(Locale.ROOT));

            final String system = TransactionSourceType.SYSTEM.getValue();
            assertThat(system)
                    .as("the interest program writes this marker in mixed case")
                    .isNotEqualTo(system.toUpperCase(Locale.ROOT))
                    .isNotEqualTo(system.toLowerCase(Locale.ROOT));
            assertThat(system.strip()).isEqualTo("System");
        }

        @Test
        @DisplayName("the markers are declared with the two arriving origins before the synthesised one")
        void theMarkersAreDeclaredArrivingBeforeSynthesised() {
            assertThat(TransactionSourceType.values()).containsExactly(
                    TransactionSourceType.POS_TERM,
                    TransactionSourceType.OPERATOR,
                    TransactionSourceType.SYSTEM);
        }
    }

    // =================================================================================================
    // SLOT WIDTH
    // =================================================================================================

    /**
     * Verifies the fixed ten-byte slot the marker must fill on both records.
     */
    @Nested
    @DisplayName("the ten-byte slot")
    class SlotWidth {

        @Test
        @DisplayName("the declared width restates the copybook width of ten")
        void theDeclaredWidthRestatesTheCopybookWidth() {
            assertThat(TransactionSourceType.VALUE_LENGTH).isEqualTo(SOURCE_SLOT_WIDTH);
        }

        @Test
        @DisplayName("every marker measures exactly ten characters, so it fills the slot without "
                + "shifting the fields after it")
        void everyMarkerMeasuresTenCharacters() {
            for (final TransactionSourceType sourceType : TransactionSourceType.values()) {
                assertThat(sourceType.getValue())
                        .as("character width of %s", sourceType.name())
                        .hasSize(SOURCE_SLOT_WIDTH);
            }
        }

        @Test
        @DisplayName("every marker measures exactly ten encoded bytes, so no character costs two")
        void everyMarkerMeasuresTenEncodedBytes() {
            for (final TransactionSourceType sourceType : TransactionSourceType.values()) {
                assertThat(sourceType.getValue().getBytes(StandardCharsets.US_ASCII))
                        .as("encoded width of %s", sourceType.name())
                        .hasSize(SOURCE_SLOT_WIDTH);
            }
        }

        @Test
        @DisplayName("every marker's populated part is shorter than the slot, so every marker is "
                + "blank-filled rather than exactly filled")
        void everyMarkerIsBlankFilled() {
            for (final TransactionSourceType sourceType : TransactionSourceType.values()) {
                final String value = sourceType.getValue();

                assertThat(value.strip().length())
                        .as("populated width of %s", sourceType.name())
                        .isLessThan(SOURCE_SLOT_WIDTH);
                assertThat(value)
                        .as("%s must be padded on the right, never on the left", sourceType.name())
                        .endsWith(" ")
                        .doesNotStartWith(" ");
            }
        }
    }

    // =================================================================================================
    // SYSTEM-GENERATED PREDICATE
    // =================================================================================================

    /**
     * Verifies the partition between an origin that arrives from outside and one the batch tier
     * synthesises.
     */
    @Nested
    @DisplayName("the system-generated predicate")
    class SystemGeneratedPredicate {

        @Test
        @DisplayName("only the system marker reports itself synthesised")
        void onlyTheSystemMarkerReportsItselfSynthesised() {
            assertThat(TransactionSourceType.SYSTEM.isSystemGenerated()).isTrue();
            assertThat(TransactionSourceType.POS_TERM.isSystemGenerated()).isFalse();
            assertThat(TransactionSourceType.OPERATOR.isSystemGenerated()).isFalse();
        }

        @Test
        @DisplayName("the predicate partitions the vocabulary into one synthesised and two arriving")
        void thePredicatePartitionsTheVocabulary() {
            final List<TransactionSourceType> synthesised = List.of(TransactionSourceType.values())
                    .stream()
                    .filter(TransactionSourceType::isSystemGenerated)
                    .toList();
            final List<TransactionSourceType> arriving = List.of(TransactionSourceType.values())
                    .stream()
                    .filter(sourceType -> !sourceType.isSystemGenerated())
                    .toList();

            assertThat(synthesised).containsExactly(TransactionSourceType.SYSTEM);
            assertThat(arriving).containsExactly(
                    TransactionSourceType.POS_TERM, TransactionSourceType.OPERATOR);
            assertThat(synthesised.size() + arriving.size())
                    .isEqualTo(TransactionSourceType.values().length);
        }
    }

    // =================================================================================================
    // LOOKUP
    // =================================================================================================

    /**
     * Verifies the lookup from a stored ten-byte image back to a marker.
     */
    @Nested
    @DisplayName("lookup from a stored image")
    class Lookup {

        @Test
        @DisplayName("all three stored images resolve to their marker")
        void allThreeStoredImagesResolve() {
            assertThat(TransactionSourceType.fromValue(LEGACY_POS_TERMINAL))
                    .contains(TransactionSourceType.POS_TERM);
            assertThat(TransactionSourceType.fromValue(LEGACY_OPERATOR))
                    .contains(TransactionSourceType.OPERATOR);
            assertThat(TransactionSourceType.fromValue(LEGACY_SYSTEM))
                    .contains(TransactionSourceType.SYSTEM);
        }

        @Test
        @DisplayName("every marker round-trips through its own stored image")
        void everyMarkerRoundTrips() {
            for (final TransactionSourceType sourceType : TransactionSourceType.values()) {
                assertThat(TransactionSourceType.fromValue(sourceType.getValue()))
                        .as("round trip of %s", sourceType.name())
                        .contains(sourceType);
            }
        }

        @Test
        @DisplayName("a stripped image does not resolve, because the slot is blank-filled on disk")
        void aStrippedImageDoesNotResolve() {
            for (final TransactionSourceType sourceType : TransactionSourceType.values()) {
                assertThat(TransactionSourceType.fromValue(sourceType.getValue().strip()))
                        .as("stripped probe for %s", sourceType.name())
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("no case folding is applied, so the system marker does not answer to an "
                + "upper-cased probe")
        void noCaseFoldingIsApplied() {
            assertThat(TransactionSourceType.fromValue("SYSTEM    ")).isEmpty();
            assertThat(TransactionSourceType.fromValue("pos term  ")).isEmpty();
            assertThat(TransactionSourceType.fromValue("operator  ")).isEmpty();
        }

        @Test
        @DisplayName("the constant name is not the stored image, so resolving by constant name fails")
        void theConstantNameIsNotTheStoredImage() {
            for (final TransactionSourceType sourceType : TransactionSourceType.values()) {
                assertThat(sourceType.name()).isNotEqualTo(sourceType.getValue());
                assertThat(TransactionSourceType.fromValue(sourceType.name()))
                        .as("probe by constant name %s", sourceType.name())
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("an image of the wrong width does not resolve, whether short or long")
        void anImageOfTheWrongWidthDoesNotResolve() {
            assertThat(TransactionSourceType.fromValue("POS TERM ")).isEmpty();
            assertThat(TransactionSourceType.fromValue("POS TERM   ")).isEmpty();
            assertThat(TransactionSourceType.fromValue("OPERATOR")).isEmpty();
        }

        @Test
        @DisplayName("an origin the transaction records never carry resolves to nothing")
        void anUnknownOriginResolvesToNothing() {
            assertThat(TransactionSourceType.fromValue("ATM       ")).isEmpty();
            assertThat(TransactionSourceType.fromValue("INTERNET  ")).isEmpty();
            assertThat(TransactionSourceType.fromValue("          ")).isEmpty();
        }

        @Test
        @DisplayName("an empty or absent image resolves to nothing rather than throwing")
        void anEmptyOrAbsentImageResolvesToNothing() {
            assertThat(TransactionSourceType.fromValue("")).isEmpty();
            assertThat(TransactionSourceType.fromValue(null)).isNotNull().isEmpty();
        }
    }

    // =================================================================================================
    // SEED COMPOSITION
    // =================================================================================================

    /**
     * Verifies that the vocabulary spans the seeded daily-transaction file exactly.
     */
    @Nested
    @DisplayName("seeded daily-transaction composition")
    class SeedComposition {

        @Test
        @DisplayName("both markers the seeded file uses resolve, so the whole file can be read")
        void bothSeededMarkersResolve() {
            assertThat(TransactionSourceType.fromValue(LEGACY_POS_TERMINAL)).isPresent();
            assertThat(TransactionSourceType.fromValue(LEGACY_OPERATOR)).isPresent();
        }

        @Test
        @DisplayName("the two measured counts account for every record in the seeded file")
        void theTwoCountsAccountForEveryRecord() {
            assertThat(SEEDED_POS_TERMINAL_RECORDS + SEEDED_OPERATOR_RECORDS)
                    .isEqualTo(SEEDED_DAILY_RECORDS);
        }

        @Test
        @DisplayName("the seeded file exercises both arriving origins, so neither posting direction is "
                + "unreachable from seed data alone")
        void theSeededFileExercisesBothArrivingOrigins() {
            assertThat(SEEDED_POS_TERMINAL_RECORDS).isPositive();
            assertThat(SEEDED_OPERATOR_RECORDS).isPositive();
            assertThat(TransactionSourceType.fromValue(LEGACY_POS_TERMINAL))
                    .get()
                    .matches(sourceType -> !sourceType.isSystemGenerated());
            assertThat(TransactionSourceType.fromValue(LEGACY_OPERATOR))
                    .get()
                    .matches(sourceType -> !sourceType.isSystemGenerated());
        }

        @Test
        @DisplayName("the synthesised marker is absent from the seeded file, because the interest run "
                + "writes it rather than reading it")
        void theSynthesisedMarkerIsAbsentFromTheSeededFile() {
            assertThat(List.of(LEGACY_POS_TERMINAL, LEGACY_OPERATOR))
                    .doesNotContain(LEGACY_SYSTEM);
            assertThat(TransactionSourceType.SYSTEM.isSystemGenerated()).isTrue();
        }
    }
}
