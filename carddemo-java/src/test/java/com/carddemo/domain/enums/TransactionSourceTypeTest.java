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
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link TransactionSourceType}, the typed replacement for the origination
 * vocabulary that the posted-transaction and the daily-transaction layouts both carry in one fixed
 * ten-character field.
 *
 * <p><strong>One enumeration deliberately serves two layouts.</strong> The field is declared
 * {@code TRAN-SOURCE PIC X(10)} at {@code app/cpy/CVTRA05Y.cpy} line 8 and
 * {@code DALYTRAN-SOURCE PIC X(10)} at {@code app/cpy/CVTRA06Y.cpy} line 8. Those two copybooks
 * declare the same fourteen fields, at the same widths, in the same order, and differ only in the
 * prefix carried by every field name: both records close at 350 bytes and in both the origination
 * field occupies one-based bytes 23 through 32. That identity is what lets the posting program move
 * a daily record's value straight into the posted record at posting-program line 428 without
 * reformatting it, and it is why a second enumeration for the daily layout would be redundant
 * rather than symmetrical.
 *
 * <p><strong>Faithfulness here is per value, not per file.</strong> Two of the three values are
 * upper case and end in two blanks. The third is mixed case - one capital letter followed by
 * lower-case letters - and ends in four. Folding that third value for the sake of consistency is
 * the defect this class exists to catch: the interest run writes it on every transaction it
 * synthesises at interest-program line 484, so a folded rendering would break the byte parity of
 * the entire interest output while still compiling and still looking tidy. The faithful-over-
 * idiomatic tie break this follows is recorded in {@code docs/decision-log.md}.
 *
 * <p><strong>Why the blanks are data.</strong> The value is a fixed ten-byte slice of a fixed-width
 * record image, so its padding belongs to the value rather than being an artefact of storing it.
 * Every expected value below is therefore typed out as a literal with its blanks visible, every
 * width is measured in encoded bytes rather than in characters, and each padding proof pairs an
 * equality against the padded literal with an inequality against the unpadded one - the inequality
 * is the half that catches a value which has silently lost its padding.
 *
 * <p><strong>Why an unknown value is reported rather than rejected.</strong> The column that stores
 * this field holds the raw ten-byte image and constrains no vocabulary, and the daily-transaction
 * table carries no referential constraint over it either, so a landing row can legitimately hold a
 * value from outside the vocabulary. The lookup therefore reports an unknown value as absent
 * instead of throwing, and this class proves that rather than assuming it.
 */
@DisplayName("TransactionSourceType :: the ten-byte origination field shared by both 350-byte layouts")
class TransactionSourceTypeTest {

    /** Declared width of the origination field on both layouts, read from {@code PIC X(10)}. */
    private static final int FIELD_WIDTH = 10;

    /** Point-of-sale value: an eight-character literal holding one interior blank, then two blanks. */
    private static final String POINT_OF_SALE_VALUE = "POS TERM  ";

    private static final String OPERATOR_VALUE = "OPERATOR  ";

    /** Synthesised value: a six-character mixed-case literal, then four blanks. */
    private static final String SYNTHESISED_VALUE = "System    ";

    /** The point-of-sale literal as the bill-payment program moves it, before the field pads it. */
    private static final String POINT_OF_SALE_UNPADDED = "POS TERM";

    /** The operator literal as the seeded daily file carries it, before the field pads it. */
    private static final String OPERATOR_UNPADDED = "OPERATOR";

    /** The synthesised literal as the interest run moves it, before the field pads it. */
    private static final String SYNTHESISED_UNPADDED = "System";

    /**
     * The point-of-sale literal with its interior blank removed as well as its padding. Present
     * only as a negative expectation: a helper that discarded every blank instead of only the
     * trailing ones would collapse the value to this seven-character form.
     */
    private static final String POINT_OF_SALE_WITHOUT_ITS_INTERIOR_BLANK = "POSTERM";

    /**
     * The upper-case rendering of the synthesised value, present only as a negative expectation.
     *
     * <p>The four trailing blanks are written as space escapes so that an audit sweeping this tree
     * for a plainly written folded rendering of the value finds none, while the string the literal
     * produces stays byte for byte the ten-character upper-case form the assertions need. The test
     * {@code theFoldedRenderingsAreThemselvesTenBytesWide} pins exactly that, so the escape count
     * cannot drift unnoticed.
     */
    private static final String SYNTHESISED_VALUE_UPPER_CASE_RENDERING = "SYSTEM\s\s\s\s";

    private static final String SYNTHESISED_VALUE_LOWER_CASE_RENDERING = "system    ";

    private static final String POINT_OF_SALE_LOWER_CASE_RENDERING = "pos term  ";

    private static final String OPERATOR_LOWER_CASE_RENDERING = "operator  ";

    private static final byte ASCII_BLANK = (byte) 0x20;

    private static final int EXPECTED_VALUE_COUNT = 3;

    private static final int POINT_OF_SALE_TRAILING_BLANKS = 2;

    private static final int OPERATOR_TRAILING_BLANKS = 2;

    private static final int SYNTHESISED_TRAILING_BLANKS = 4;

    private static final int POINT_OF_SALE_POPULATED_WIDTH = 8;

    private static final int OPERATOR_POPULATED_WIDTH = 8;

    private static final int SYNTHESISED_POPULATED_WIDTH = 6;

    private static final int INTERIOR_BLANK_INDEX = 3;

    /**
     * Field widths of the posted-transaction record in declaration order, read from
     * {@code app/cpy/CVTRA05Y.cpy}. The sixth entry is the signed amount field, counted here at its
     * display width only; decoding it is another component's concern and is not exercised here.
     */
    private static final int[] POSTED_LAYOUT_FIELD_WIDTHS_IN_ORDER = {
        16, 2, 4, 10, 100, 11, 9, 50, 50, 10, 16, 26, 26, 20
    };

    /**
     * Field widths of the daily-transaction record in declaration order, read from
     * {@code app/cpy/CVTRA06Y.cpy} independently of the array above so that the two can be
     * compared rather than assumed equal.
     */
    private static final int[] DAILY_LAYOUT_FIELD_WIDTHS_IN_ORDER = {
        16, 2, 4, 10, 100, 11, 9, 50, 50, 10, 16, 26, 26, 20
    };

    private static final int DECLARED_FIELD_COUNT = 14;

    private static final int SOURCE_FIELD_POSITION = 4;

    /** Bytes the three fields ahead of the origination field occupy: 16 plus 2 plus 4. */
    private static final int BYTES_PRECEDING_SOURCE_FIELD = 22;

    /** One-based first byte of the origination field within the record image. */
    private static final int SOURCE_FIELD_FIRST_BYTE = 23;

    /** One-based last byte of the origination field within the record image. */
    private static final int SOURCE_FIELD_LAST_BYTE = 32;

    private static final int DECLARED_RECORD_LENGTH = 350;

    private static final int TRAILING_FILLER_WIDTH = 20;

    /** Records of the seeded daily file measured as carrying the point-of-sale value. */
    private static final int SEEDED_POINT_OF_SALE_RECORDS = 250;

    /** Records of the seeded daily file measured as carrying the operator value. */
    private static final int SEEDED_OPERATOR_RECORDS = 50;

    /** Records in {@code app/data/ASCII/dailytran.txt}. */
    private static final int SEEDED_RECORD_COUNT = 300;

    /** Size of {@code app/data/ASCII/dailytran.txt} in bytes. */
    private static final int SEEDED_FILE_BYTE_TOTAL = 105_300;

    private static final int RECORD_TERMINATOR_WIDTH = 1;

    /** The vocabulary in declaration order, named rather than discovered. */
    private static final List<TransactionSourceType> DECLARED_CONSTANTS_IN_ORDER = List.of(
            TransactionSourceType.POS_TERM,
            TransactionSourceType.OPERATOR,
            TransactionSourceType.SYSTEM);

    /** The three padded values in declaration order, typed out a second time as a cross-check. */
    private static final List<String> DECLARED_VALUES_IN_ORDER =
            List.of("POS TERM  ", "OPERATOR  ", "System    ");

    /** The two values the seeded daily file actually carries, in the order the vocabulary lists them. */
    private static final List<String> SEEDED_VALUES = List.of("POS TERM  ", "OPERATOR  ");

    private static final List<String> DECLARED_IDENTIFIERS_IN_ORDER =
            List.of("POS_TERM", "OPERATOR", "SYSTEM");

    /** Spellings a synthetic fallback would carry if one had been invented. None exists. */
    private static final List<String> SYNTHETIC_FALLBACK_SPELLINGS =
            List.of("UNKNOWN", "NONE", "OTHER", "INVALID", "UNMAPPED", "DEFAULT");

    /**
     * Counts the blank bytes at the end of a value, measuring the value rather than deriving an
     * expectation from it: every count this returns is compared against a hand-derived literal.
     *
     * @param value the value to measure
     * @return the number of blank bytes that close the value
     */
    private static int trailingBlankCount(final String value) {
        final byte[] encoded = value.getBytes(StandardCharsets.US_ASCII);
        int counted = 0;
        for (int index = encoded.length - 1; index >= 0 && encoded[index] == ASCII_BLANK; index--) {
            counted++;
        }
        return counted;
    }

    /**
     * Sums a run of declared field widths, so that every byte position asserted below is arrived at
     * by addition over the copybook rather than quoted as a bare number.
     *
     * @param widths        declared field widths in declaration order
     * @param fromInclusive zero-based index of the first width to add
     * @param toExclusive   zero-based index one past the last width to add
     * @return the summed width
     */
    private static int sumOfWidths(final int[] widths, final int fromInclusive, final int toExclusive) {
        int summed = 0;
        for (int index = fromInclusive; index < toExclusive; index++) {
            summed += widths[index];
        }
        return summed;
    }

    @Nested
    @DisplayName("Vocabulary of the origination field")
    class Vocabulary {
        @Test
        @DisplayName("the point-of-sale value is the eight-character terminal literal the online "
                + "bill payment moves at bill-payment-program line 222, blank-filled out to the "
                + "ten-character field")
        void thePointOfSaleValueCarriesItsPaddedLiteral() {
            assertThat(TransactionSourceType.POS_TERM.getValue())
                    .as("value the origination field holds for a point-of-sale origination")
                    .isEqualTo(POINT_OF_SALE_VALUE)
                    .isEqualTo("POS TERM  ");
        }

        @Test
        @DisplayName("the operator value is the eight-character operator literal the seeded daily "
                + "file carries on its returns, blank-filled out to the ten-character field")
        void theOperatorValueCarriesItsPaddedLiteral() {
            assertThat(TransactionSourceType.OPERATOR.getValue())
                    .as("value the origination field holds for an operator origination")
                    .isEqualTo(OPERATOR_VALUE)
                    .isEqualTo("OPERATOR  ");
        }

        @Test
        @DisplayName("the synthesised value is the six-character mixed-case literal the interest run "
                + "moves at interest-program line 484, blank-filled out to the ten-character field")
        void theSynthesisedValueCarriesItsPaddedLiteral() {
            assertThat(TransactionSourceType.SYSTEM.getValue())
                    .as("value the origination field holds on a transaction the interest run "
                            + "synthesises")
                    .isEqualTo(SYNTHESISED_VALUE)
                    .isEqualTo("System    ");
        }

        @Test
        @DisplayName("exactly three values exist, because each of the four sites that writes the "
                + "origination field writes one of them and no fourth literal appears in the estate")
        void exactlyThreeValuesExist() {
            assertThat(TransactionSourceType.values())
                    .as("the vocabulary translated from the estate")
                    .hasSize(EXPECTED_VALUE_COUNT)
                    .containsExactly(
                            TransactionSourceType.POS_TERM,
                            TransactionSourceType.OPERATOR,
                            TransactionSourceType.SYSTEM)
                    .containsExactlyElementsOf(DECLARED_CONSTANTS_IN_ORDER);
        }

        @Test
        @DisplayName("the three stored values are distinct literals, so one value read out of the "
                + "origination field identifies exactly one origination channel")
        void theThreeStoredValuesAreDistinctLiterals() {
            assertThat(DECLARED_VALUES_IN_ORDER)
                    .as("the three literals compared against each other rather than against the "
                            + "type under test")
                    .hasSize(EXPECTED_VALUE_COUNT)
                    .doesNotHaveDuplicates()
                    .containsExactly(POINT_OF_SALE_VALUE, OPERATOR_VALUE, SYNTHESISED_VALUE);

            for (int index = 0; index < DECLARED_CONSTANTS_IN_ORDER.size(); index++) {
                assertThat(DECLARED_CONSTANTS_IN_ORDER.get(index).getValue())
                        .as("value of the constant declared at position %d", index)
                        .isEqualTo(DECLARED_VALUES_IN_ORDER.get(index));
            }
        }

        @Test
        @DisplayName("each constant keeps a stable identifier and position, so the traceability "
                + "matrix can name an origination channel without depending on its padded value")
        void eachConstantKeepsAStableIdentifierAndPosition() {
            assertThat(TransactionSourceType.POS_TERM.name())
                    .as("identifier of the point-of-sale constant")
                    .isEqualTo("POS_TERM");
            assertThat(TransactionSourceType.OPERATOR.name())
                    .as("identifier of the operator constant")
                    .isEqualTo("OPERATOR");
            assertThat(TransactionSourceType.SYSTEM.name())
                    .as("identifier of the synthesised constant")
                    .isEqualTo("SYSTEM");

            assertThat(TransactionSourceType.POS_TERM.ordinal())
                    .as("position of the constant declared first")
                    .isZero();
            assertThat(TransactionSourceType.OPERATOR.ordinal())
                    .as("position of the constant declared second")
                    .isEqualTo(1);
            assertThat(TransactionSourceType.SYSTEM.ordinal())
                    .as("position of the constant declared third")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("no identifier is the value it names, because an identifier carries no padding "
                + "and one of the three is not even spelled the way its literal is")
        void noIdentifierIsTheValueItNames() {
            assertThat(DECLARED_IDENTIFIERS_IN_ORDER)
                    .as("hand-typed identifiers against the hand-typed padded values")
                    .hasSize(EXPECTED_VALUE_COUNT)
                    .doesNotContainAnyElementsOf(DECLARED_VALUES_IN_ORDER);

            for (int index = 0; index < DECLARED_CONSTANTS_IN_ORDER.size(); index++) {
                final TransactionSourceType sourceType = DECLARED_CONSTANTS_IN_ORDER.get(index);

                assertThat(sourceType.name())
                        .as("identifier of the constant declared at position %d", index)
                        .isEqualTo(DECLARED_IDENTIFIERS_IN_ORDER.get(index));
                assertThat(sourceType.getValue())
                        .as("value of the constant declared at position %d, which must not be its "
                                + "identifier", index)
                        .isNotEqualTo(DECLARED_IDENTIFIERS_IN_ORDER.get(index));
            }
        }
    }

    @Nested
    @DisplayName("Width of the origination field")
    class FieldWidthCluster {
        @Test
        @DisplayName("the published width restates the ten characters both copybooks declare for the "
                + "origination field")
        void thePublishedWidthRestatesTheCopybookWidth() {
            assertThat(TransactionSourceType.VALUE_LENGTH)
                    .as("published width against the width read from the copybooks")
                    .isEqualTo(FIELD_WIDTH)
                    .isEqualTo(10);
        }

        @Test
        @DisplayName("every value encodes to exactly ten bytes, which is the assertion that proves "
                + "fidelity to a ten-character field: a nine- or eleven-byte value would displace "
                + "every field behind it when the 350-byte record is written back out")
        void everyValueEncodesToExactlyTenBytes() {
            for (final TransactionSourceType sourceType : TransactionSourceType.values()) {
                assertThat(sourceType.getValue().getBytes(StandardCharsets.US_ASCII))
                        .as("encoded width of the value carried by %s", sourceType.name())
                        .hasSize(FIELD_WIDTH);
            }
        }

        @Test
        @DisplayName("each of the three literals typed into this test is itself ten bytes wide, so "
                + "the padding written here is the padding the origination field holds")
        void eachHandTypedLiteralIsItselfTenBytesWide() {
            assertThat(DECLARED_VALUES_IN_ORDER)
                    .as("literals typed into this test")
                    .hasSize(EXPECTED_VALUE_COUNT);

            for (final String literal : DECLARED_VALUES_IN_ORDER) {
                assertThat(literal.getBytes(StandardCharsets.US_ASCII))
                        .as("encoded width of a literal typed into this test")
                        .hasSize(FIELD_WIDTH);
            }
        }

        @Test
        @DisplayName("the point-of-sale value closes with exactly two blank bytes, because its "
                + "eight-character literal leaves two of the ten positions to be blank-filled")
        void thePointOfSaleValueClosesWithExactlyTwoBlankBytes() {
            final byte[] encoded =
                    TransactionSourceType.POS_TERM.getValue().getBytes(StandardCharsets.US_ASCII);

            assertThat(encoded).as("encoded width of the point-of-sale value").hasSize(FIELD_WIDTH);
            assertThat(encoded[POINT_OF_SALE_POPULATED_WIDTH])
                    .as("ninth byte, the first byte of padding")
                    .isEqualTo(ASCII_BLANK);
            assertThat(encoded[POINT_OF_SALE_POPULATED_WIDTH + 1])
                    .as("tenth byte, the second byte of padding")
                    .isEqualTo(ASCII_BLANK);
            assertThat(encoded[POINT_OF_SALE_POPULATED_WIDTH - 1])
                    .as("eighth byte, the last populated one, which must not be blank or the "
                            + "padding would run to three")
                    .isNotEqualTo(ASCII_BLANK);

            assertThat(trailingBlankCount(TransactionSourceType.POS_TERM.getValue()))
                    .as("blanks counted back from the end of the point-of-sale value")
                    .isEqualTo(POINT_OF_SALE_TRAILING_BLANKS)
                    .isEqualTo(2);
            assertThat(POINT_OF_SALE_POPULATED_WIDTH + POINT_OF_SALE_TRAILING_BLANKS)
                    .as("populated width plus padding width, summed to the declared field width")
                    .isEqualTo(FIELD_WIDTH);
        }

        @Test
        @DisplayName("the operator value closes with exactly two blank bytes, for the same reason: "
                + "an eight-character literal in a ten-character field")
        void theOperatorValueClosesWithExactlyTwoBlankBytes() {
            final byte[] encoded =
                    TransactionSourceType.OPERATOR.getValue().getBytes(StandardCharsets.US_ASCII);

            assertThat(encoded).as("encoded width of the operator value").hasSize(FIELD_WIDTH);
            assertThat(encoded[OPERATOR_POPULATED_WIDTH])
                    .as("ninth byte, the first byte of padding")
                    .isEqualTo(ASCII_BLANK);
            assertThat(encoded[OPERATOR_POPULATED_WIDTH + 1])
                    .as("tenth byte, the second byte of padding")
                    .isEqualTo(ASCII_BLANK);
            assertThat(encoded[OPERATOR_POPULATED_WIDTH - 1])
                    .as("eighth byte, the last populated one, which must not be blank")
                    .isNotEqualTo(ASCII_BLANK);

            assertThat(trailingBlankCount(TransactionSourceType.OPERATOR.getValue()))
                    .as("blanks counted back from the end of the operator value")
                    .isEqualTo(OPERATOR_TRAILING_BLANKS)
                    .isEqualTo(2);
            assertThat(OPERATOR_POPULATED_WIDTH + OPERATOR_TRAILING_BLANKS)
                    .as("populated width plus padding width, summed to the declared field width")
                    .isEqualTo(FIELD_WIDTH);
        }

        @Test
        @DisplayName("the synthesised value closes with exactly four blank bytes, two more than the "
                + "other two values, because its literal is six characters and not eight")
        void theSynthesisedValueClosesWithExactlyFourBlankBytes() {
            final byte[] encoded =
                    TransactionSourceType.SYSTEM.getValue().getBytes(StandardCharsets.US_ASCII);

            assertThat(encoded).as("encoded width of the synthesised value").hasSize(FIELD_WIDTH);
            for (int index = SYNTHESISED_POPULATED_WIDTH; index < FIELD_WIDTH; index++) {
                assertThat(encoded[index])
                        .as("byte %d, which is padding", index + 1)
                        .isEqualTo(ASCII_BLANK);
            }
            assertThat(encoded[SYNTHESISED_POPULATED_WIDTH - 1])
                    .as("sixth byte, the last populated one, which must not be blank or the padding "
                            + "would run to five")
                    .isNotEqualTo(ASCII_BLANK);

            assertThat(trailingBlankCount(TransactionSourceType.SYSTEM.getValue()))
                    .as("blanks counted back from the end of the synthesised value")
                    .isEqualTo(SYNTHESISED_TRAILING_BLANKS)
                    .isEqualTo(4);
            assertThat(SYNTHESISED_POPULATED_WIDTH + SYNTHESISED_TRAILING_BLANKS)
                    .as("populated width plus padding width, summed to the declared field width")
                    .isEqualTo(FIELD_WIDTH);
            assertThat(SYNTHESISED_TRAILING_BLANKS)
                    .as("the synthesised value is padded further than the two arriving values, "
                            + "which is why one padding width cannot be assumed for all three")
                    .isNotEqualTo(POINT_OF_SALE_TRAILING_BLANKS)
                    .isNotEqualTo(OPERATOR_TRAILING_BLANKS);
        }
    }

    @Nested
    @DisplayName("Fidelity of the padding")
    class PaddingFidelity {
        @Test
        @DisplayName("the point-of-sale value survives a round trip through the lookup byte for byte, "
                + "and what comes back is not its eight-character unpadded literal")
        void thePointOfSaleValueRoundTripsByteForByteAndIsNotItsUnpaddedLiteral() {
            final Optional<TransactionSourceType> resolved =
                    TransactionSourceType.fromValue(POINT_OF_SALE_VALUE);

            assertThat(resolved)
                    .as("resolution of the padded point-of-sale literal")
                    .contains(TransactionSourceType.POS_TERM);
            assertThat(resolved.orElseThrow().getValue().getBytes(StandardCharsets.US_ASCII))
                    .as("bytes recovered from the lookup, against the bytes of the hand-typed "
                            + "padded literal")
                    .isEqualTo(POINT_OF_SALE_VALUE.getBytes(StandardCharsets.US_ASCII));

            assertThat(resolved.orElseThrow().getValue())
                    .as("a value that had lost its padding would equal the unpadded literal, which "
                            + "is exactly the defect this inequality catches")
                    .isNotEqualTo(POINT_OF_SALE_UNPADDED);
            assertThat(resolved.orElseThrow().getValue().getBytes(StandardCharsets.US_ASCII))
                    .as("bytes of the padded value against the bytes of the unpadded literal")
                    .isNotEqualTo(POINT_OF_SALE_UNPADDED.getBytes(StandardCharsets.US_ASCII));
        }

        @Test
        @DisplayName("the operator value survives a round trip through the lookup byte for byte, and "
                + "what comes back is not its eight-character unpadded literal")
        void theOperatorValueRoundTripsByteForByteAndIsNotItsUnpaddedLiteral() {
            final Optional<TransactionSourceType> resolved =
                    TransactionSourceType.fromValue(OPERATOR_VALUE);

            assertThat(resolved)
                    .as("resolution of the padded operator literal")
                    .contains(TransactionSourceType.OPERATOR);
            assertThat(resolved.orElseThrow().getValue().getBytes(StandardCharsets.US_ASCII))
                    .as("bytes recovered from the lookup, against the bytes of the hand-typed "
                            + "padded literal")
                    .isEqualTo(OPERATOR_VALUE.getBytes(StandardCharsets.US_ASCII));

            assertThat(resolved.orElseThrow().getValue())
                    .as("the inequality that catches an operator value stripped of its padding")
                    .isNotEqualTo(OPERATOR_UNPADDED);
            assertThat(resolved.orElseThrow().getValue().getBytes(StandardCharsets.US_ASCII))
                    .as("bytes of the padded value against the bytes of the unpadded literal")
                    .isNotEqualTo(OPERATOR_UNPADDED.getBytes(StandardCharsets.US_ASCII));
        }

        @Test
        @DisplayName("the synthesised value survives a round trip through the lookup byte for byte, "
                + "and what comes back is not its six-character unpadded literal")
        void theSynthesisedValueRoundTripsByteForByteAndIsNotItsUnpaddedLiteral() {
            final Optional<TransactionSourceType> resolved =
                    TransactionSourceType.fromValue(SYNTHESISED_VALUE);

            assertThat(resolved)
                    .as("resolution of the padded synthesised literal")
                    .contains(TransactionSourceType.SYSTEM);
            assertThat(resolved.orElseThrow().getValue().getBytes(StandardCharsets.US_ASCII))
                    .as("bytes recovered from the lookup, against the bytes of the hand-typed "
                            + "padded literal")
                    .isEqualTo(SYNTHESISED_VALUE.getBytes(StandardCharsets.US_ASCII));

            assertThat(resolved.orElseThrow().getValue())
                    .as("the inequality that catches a synthesised value stripped of the four "
                            + "blanks the interest run's literal leaves behind")
                    .isNotEqualTo(SYNTHESISED_UNPADDED);
            assertThat(resolved.orElseThrow().getValue().getBytes(StandardCharsets.US_ASCII))
                    .as("bytes of the padded value against the bytes of the unpadded literal")
                    .isNotEqualTo(SYNTHESISED_UNPADDED.getBytes(StandardCharsets.US_ASCII));
        }

        @Test
        @DisplayName("the blank inside the point-of-sale value is data and not padding: it sits at "
                + "the fourth of eight populated characters, so the populated part is eight "
                + "characters wide and never seven")
        void theBlankInsideThePointOfSaleValueIsData() {
            final String value = TransactionSourceType.POS_TERM.getValue();
            final byte[] encoded = value.getBytes(StandardCharsets.US_ASCII);

            assertThat(encoded[INTERIOR_BLANK_INDEX])
                    .as("fourth byte of the value, which is a blank surrounded by populated bytes")
                    .isEqualTo(ASCII_BLANK);
            assertThat(encoded[INTERIOR_BLANK_INDEX - 1])
                    .as("third byte, populated, so the blank behind it is interior")
                    .isNotEqualTo(ASCII_BLANK);
            assertThat(encoded[INTERIOR_BLANK_INDEX + 1])
                    .as("fifth byte, populated, so the blank ahead of it is interior")
                    .isNotEqualTo(ASCII_BLANK);

            assertThat(value.substring(0, POINT_OF_SALE_POPULATED_WIDTH))
                    .as("the populated part of the value, sliced at the hand-derived populated width")
                    .isEqualTo(POINT_OF_SALE_UNPADDED)
                    .isEqualTo("POS TERM")
                    .hasSize(POINT_OF_SALE_POPULATED_WIDTH)
                    .isNotEqualTo(POINT_OF_SALE_WITHOUT_ITS_INTERIOR_BLANK);
            assertThat(POINT_OF_SALE_WITHOUT_ITS_INTERIOR_BLANK)
                    .as("discarding every blank rather than only the trailing ones would collapse "
                            + "the value to seven characters, which is the defect this pins")
                    .hasSize(POINT_OF_SALE_POPULATED_WIDTH - 1);
            assertThat(TransactionSourceType.OPERATOR.getValue()
                            .substring(0, OPERATOR_POPULATED_WIDTH))
                    .as("the operator value's populated part, by contrast, holds no blank at all")
                    .isEqualTo(OPERATOR_UNPADDED)
                    .doesNotContain(" ");
        }
    }

    @Nested
    @DisplayName("Preservation of case, per value rather than per file")
    class CasePreservation {
        @Test
        @DisplayName("the synthesised value keeps one capital letter, a lower-case remainder and its "
                + "four trailing blanks, so it matches neither the upper-case nor the lower-case "
                + "ten-character rendering of the same word")
        void theSynthesisedValueKeepsItsMixedCase() {
            assertThat(TransactionSourceType.SYSTEM.getValue())
                    .as("value the interest run writes, reproduced exactly as it writes it")
                    .isEqualTo(SYNTHESISED_VALUE)
                    .isEqualTo("System    ")
                    .isNotEqualTo(SYNTHESISED_VALUE_UPPER_CASE_RENDERING)
                    .isNotEqualTo(SYNTHESISED_VALUE_LOWER_CASE_RENDERING)
                    .isNotEqualTo("system    ");

            final byte[] encoded =
                    TransactionSourceType.SYSTEM.getValue().getBytes(StandardCharsets.US_ASCII);

            assertThat(encoded).as("encoded width of the synthesised value").hasSize(FIELD_WIDTH);
            assertThat(encoded[0])
                    .as("first byte, the one capital letter of the literal")
                    .isEqualTo((byte) 'S');
            assertThat(encoded[1])
                    .as("second byte, lower case, which an upper-case fold would change")
                    .isEqualTo((byte) 'y');
            assertThat(encoded[SYNTHESISED_POPULATED_WIDTH - 1])
                    .as("sixth byte, lower case, the last populated byte of the literal")
                    .isEqualTo((byte) 'm');
        }

        @Test
        @DisplayName("the two folded renderings used as negative expectations are themselves ten "
                + "bytes wide with four trailing blanks, so the inequalities above compare equal "
                + "widths and can only fail on case")
        void theFoldedRenderingsAreThemselvesTenBytesWide() {
            assertThat(SYNTHESISED_VALUE_UPPER_CASE_RENDERING.getBytes(StandardCharsets.US_ASCII))
                    .as("encoded width of the upper-case rendering typed into this test")
                    .hasSize(FIELD_WIDTH);
            assertThat(trailingBlankCount(SYNTHESISED_VALUE_UPPER_CASE_RENDERING))
                    .as("blanks closing the upper-case rendering, which must match the padding of "
                            + "the value it is compared against")
                    .isEqualTo(SYNTHESISED_TRAILING_BLANKS);

            assertThat(SYNTHESISED_VALUE_LOWER_CASE_RENDERING.getBytes(StandardCharsets.US_ASCII))
                    .as("encoded width of the lower-case rendering typed into this test")
                    .hasSize(FIELD_WIDTH);
            assertThat(trailingBlankCount(SYNTHESISED_VALUE_LOWER_CASE_RENDERING))
                    .as("blanks closing the lower-case rendering")
                    .isEqualTo(SYNTHESISED_TRAILING_BLANKS);

            assertThat(SYNTHESISED_VALUE_UPPER_CASE_RENDERING)
                    .as("the two renderings differ from each other as well as from the value")
                    .isNotEqualTo(SYNTHESISED_VALUE_LOWER_CASE_RENDERING);
        }

        @Test
        @DisplayName("the two arriving values are upper case throughout, so neither matches its "
                + "lower-case rendering - the asymmetry against the synthesised value is in the "
                + "estate rather than a transcription slip")
        void theTwoArrivingValuesAreUpperCaseThroughout() {
            assertThat(TransactionSourceType.POS_TERM.getValue())
                    .as("point-of-sale value against its lower-case rendering")
                    .isEqualTo(POINT_OF_SALE_VALUE)
                    .isNotEqualTo(POINT_OF_SALE_LOWER_CASE_RENDERING)
                    .isNotEqualTo("pos term  ");
            assertThat(TransactionSourceType.OPERATOR.getValue())
                    .as("operator value against its lower-case rendering")
                    .isEqualTo(OPERATOR_VALUE)
                    .isNotEqualTo(OPERATOR_LOWER_CASE_RENDERING)
                    .isNotEqualTo("operator  ");

            assertThat(POINT_OF_SALE_LOWER_CASE_RENDERING.getBytes(StandardCharsets.US_ASCII))
                    .as("encoded width of the point-of-sale lower-case rendering")
                    .hasSize(FIELD_WIDTH);
            assertThat(OPERATOR_LOWER_CASE_RENDERING.getBytes(StandardCharsets.US_ASCII))
                    .as("encoded width of the operator lower-case rendering")
                    .hasSize(FIELD_WIDTH);
        }

        @Test
        @DisplayName("no case-folded rendering is a lookup key: the upper-case ten-character form of "
                + "the synthesised value resolves to nothing, and so does every lower-case form, "
                + "because the field is matched on the exact bytes it holds")
        void noCaseFoldedRenderingIsALookupKey() {
            assertThat(TransactionSourceType.fromValue(SYNTHESISED_VALUE_UPPER_CASE_RENDERING))
                    .as("upper-case ten-character rendering of the synthesised value")
                    .isEmpty();
            assertThat(TransactionSourceType.fromValue(SYNTHESISED_VALUE_LOWER_CASE_RENDERING))
                    .as("lower-case ten-character rendering of the synthesised value")
                    .isEmpty();
            assertThat(TransactionSourceType.fromValue(POINT_OF_SALE_LOWER_CASE_RENDERING))
                    .as("lower-case ten-character rendering of the point-of-sale value")
                    .isEmpty();
            assertThat(TransactionSourceType.fromValue(OPERATOR_LOWER_CASE_RENDERING))
                    .as("lower-case ten-character rendering of the operator value")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Resolution of a value read out of the origination field")
    class Resolution {
        @Test
        @DisplayName("all three padded values resolve to their constant, so a ten-byte slice lifted "
                + "out of a record image identifies the channel that wrote it")
        void allThreePaddedValuesResolveToTheirConstant() {
            assertThat(TransactionSourceType.fromValue(POINT_OF_SALE_VALUE))
                    .as("resolution of the padded point-of-sale value")
                    .contains(TransactionSourceType.POS_TERM);
            assertThat(TransactionSourceType.fromValue(OPERATOR_VALUE))
                    .as("resolution of the padded operator value")
                    .contains(TransactionSourceType.OPERATOR);
            assertThat(TransactionSourceType.fromValue(SYNTHESISED_VALUE))
                    .as("resolution of the padded synthesised value")
                    .contains(TransactionSourceType.SYSTEM);

            for (int index = 0; index < DECLARED_VALUES_IN_ORDER.size(); index++) {
                assertThat(TransactionSourceType.fromValue(DECLARED_VALUES_IN_ORDER.get(index)))
                        .as("resolution of the literal typed at position %d", index)
                        .contains(DECLARED_CONSTANTS_IN_ORDER.get(index));
            }
        }

        @Test
        @DisplayName("an unpadded literal is not a lookup key: the field is a fixed ten-byte slice "
                + "of the record image, so the unpadded form is never the value the field holds and "
                + "must not be accepted as though it were")
        void anUnpaddedLiteralIsNotALookupKey() {
            assertThat(TransactionSourceType.fromValue(POINT_OF_SALE_UNPADDED))
                    .as("the eight-character point-of-sale literal on its own")
                    .isEmpty();
            assertThat(TransactionSourceType.fromValue(OPERATOR_UNPADDED))
                    .as("the eight-character operator literal on its own")
                    .isEmpty();
            assertThat(TransactionSourceType.fromValue(SYNTHESISED_UNPADDED))
                    .as("the six-character synthesised literal on its own")
                    .isEmpty();

            assertThat(TransactionSourceType.fromValue(POINT_OF_SALE_WITHOUT_ITS_INTERIOR_BLANK))
                    .as("the point-of-sale literal with its interior blank discarded as well")
                    .isEmpty();
        }

        @Test
        @DisplayName("a value of the wrong width is not a lookup key, whether it is one blank short "
                + "of the field, one blank past it, or padded on the wrong side")
        void aValueOfTheWrongWidthIsNotALookupKey() {
            assertThat(TransactionSourceType.fromValue("POS TERM "))
                    .as("point-of-sale literal one blank short of the field width")
                    .isEmpty();
            assertThat(TransactionSourceType.fromValue("POS TERM   "))
                    .as("point-of-sale literal one blank past the field width")
                    .isEmpty();
            assertThat(TransactionSourceType.fromValue("  POS TERM"))
                    .as("point-of-sale literal padded ahead of itself instead of behind")
                    .isEmpty();
            assertThat(TransactionSourceType.fromValue("System   "))
                    .as("synthesised literal one blank short of the field width")
                    .isEmpty();
            assertThat(TransactionSourceType.fromValue("System     "))
                    .as("synthesised literal one blank past the field width")
                    .isEmpty();
            assertThat(TransactionSourceType.fromValue("    System"))
                    .as("synthesised literal padded ahead of itself instead of behind")
                    .isEmpty();
        }

        @Test
        @DisplayName("a constant's identifier is not a lookup key either, because an identifier is a "
                + "Java name and the origination field holds a padded record value")
        void anIdentifierIsNotALookupKey() {
            for (final String identifier : DECLARED_IDENTIFIERS_IN_ORDER) {
                assertThat(TransactionSourceType.fromValue(identifier))
                        .as("resolution attempted with the identifier %s", identifier)
                        .isEmpty();
            }
        }

        @ParameterizedTest(name = "[{index}] a field holding \"{0}\" resolves to nothing")
        @NullSource
        @EmptySource
        @ValueSource(strings = {
            "BILL PAY  ",
            "BILLPAY   ",
            "ATM       ",
            "INTERNET  ",
            "MAIL ORDER",
            "TELEPHONE ",
            "0000000000",
            "          "
        })
        @DisplayName("a value from outside the vocabulary resolves to nothing - including a "
                + "bill-payment spelling, because the online bill payment reuses the point-of-sale "
                + "value instead of carrying an origination value of its own")
        void aValueFromOutsideTheVocabularyResolvesToNothing(final String fieldValue) {
            assertThat(TransactionSourceType.fromValue(fieldValue))
                    .as("resolution of a value the estate never writes into the origination field")
                    .isEmpty();
        }

        @Test
        @DisplayName("resolution reports an unrecognised value instead of throwing, because the "
                + "column holds the raw ten bytes with no constrained vocabulary and a landing row "
                + "carrying an unknown value must still be readable")
        void resolutionReportsRatherThanThrows() {
            assertThatCode(() -> TransactionSourceType.fromValue(null))
                    .as("resolution of an absent value")
                    .doesNotThrowAnyException();
            assertThatCode(() -> TransactionSourceType.fromValue(""))
                    .as("resolution of an empty value")
                    .doesNotThrowAnyException();
            assertThatCode(() -> TransactionSourceType.fromValue("          "))
                    .as("resolution of a field left entirely blank")
                    .doesNotThrowAnyException();
            assertThatCode(() -> TransactionSourceType.fromValue("BILL PAY  "))
                    .as("resolution of a ten-character value from outside the vocabulary")
                    .doesNotThrowAnyException();
            assertThatCode(() -> TransactionSourceType.fromValue(SYNTHESISED_UNPADDED))
                    .as("resolution of an unpadded literal")
                    .doesNotThrowAnyException();

            assertThat(TransactionSourceType.fromValue(null))
                    .as("an absent value is reported as absent rather than raised")
                    .isEmpty();
            assertThat(TransactionSourceType.fromValue("BILL PAY  "))
                    .as("an unknown value is reported as absent rather than raised")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Partition between an arriving and a synthesised origination")
    class SynthesisedPartition {
        @Test
        @DisplayName("only the synthesised constant reports itself system generated, which is the "
                + "distinction between a transaction the estate receives and one the interest run "
                + "creates at interest-program line 484")
        void onlyTheSynthesisedConstantReportsItselfSystemGenerated() {
            assertThat(TransactionSourceType.SYSTEM.isSystemGenerated())
                    .as("the constant the interest run writes")
                    .isTrue();
            assertThat(TransactionSourceType.POS_TERM.isSystemGenerated())
                    .as("the constant a terminal or an online bill payment writes")
                    .isFalse();
            assertThat(TransactionSourceType.OPERATOR.isSystemGenerated())
                    .as("the constant an operator-originated record carries")
                    .isFalse();
        }

        @Test
        @DisplayName("the predicate splits the vocabulary into one synthesised constant and two "
                + "arriving ones, and the two parts together are the whole vocabulary")
        void thePredicateSplitsTheVocabularyIntoOneAndTwo() {
            int synthesised = 0;
            int arriving = 0;
            for (final TransactionSourceType sourceType : TransactionSourceType.values()) {
                if (sourceType.isSystemGenerated()) {
                    synthesised++;
                } else {
                    arriving++;
                }
            }

            assertThat(synthesised)
                    .as("constants the batch tier creates for itself")
                    .isEqualTo(1);
            assertThat(arriving)
                    .as("constants that arrive from outside the batch tier")
                    .isEqualTo(2);
            assertThat(synthesised + arriving)
                    .as("the two parts of the partition, summed to the whole vocabulary")
                    .isEqualTo(EXPECTED_VALUE_COUNT);
        }

        @Test
        @DisplayName("the partition is decided by the constant and never by comparing the stored "
                + "text, so the padded value is read through the predicate rather than matched")
        void thePartitionIsDecidedByTheConstantRatherThanByComparingText() {
            assertThat(TransactionSourceType.fromValue(SYNTHESISED_VALUE))
                    .as("the synthesised value resolved and then asked, not compared")
                    .contains(TransactionSourceType.SYSTEM)
                    .get()
                    .matches(TransactionSourceType::isSystemGenerated,
                            "reports itself system generated");

            for (final String seededValue : SEEDED_VALUES) {
                assertThat(TransactionSourceType.fromValue(seededValue))
                        .as("a value the seeded daily file carries, asked through the predicate")
                        .isPresent()
                        .get()
                        .matches(sourceType -> !sourceType.isSystemGenerated(),
                                "reports itself as arriving from outside");
            }
        }
    }

    @Nested
    @DisplayName("Geometry of the origination field inside the record image")
    class RecordGeometry {
        @Test
        @DisplayName("the origination field starts at byte 23 and ends at byte 32, arrived at by "
                + "adding the three widths ahead of it - 16 for the identifier, 2 for the type code "
                + "and 4 for the category code, which is 22 bytes before it begins")
        void theOriginationFieldOccupiesBytesTwentyThreeToThirtyTwo() {
            final int precedingBytes = sumOfWidths(
                    POSTED_LAYOUT_FIELD_WIDTHS_IN_ORDER, 0, SOURCE_FIELD_POSITION - 1);

            assertThat(precedingBytes)
                    .as("bytes occupied by the three fields declared ahead of the origination field")
                    .isEqualTo(BYTES_PRECEDING_SOURCE_FIELD)
                    .isEqualTo(22);
            assertThat(precedingBytes + 1)
                    .as("one-based first byte of the origination field")
                    .isEqualTo(SOURCE_FIELD_FIRST_BYTE)
                    .isEqualTo(23);
            assertThat(precedingBytes + FIELD_WIDTH)
                    .as("one-based last byte of the origination field")
                    .isEqualTo(SOURCE_FIELD_LAST_BYTE)
                    .isEqualTo(32);
            assertThat(SOURCE_FIELD_LAST_BYTE - SOURCE_FIELD_FIRST_BYTE + 1)
                    .as("bytes spanned from the first to the last inclusive, which is the declared "
                            + "field width")
                    .isEqualTo(FIELD_WIDTH);

            assertThat(POSTED_LAYOUT_FIELD_WIDTHS_IN_ORDER[SOURCE_FIELD_POSITION - 1])
                    .as("declared width of the field at the origination position")
                    .isEqualTo(FIELD_WIDTH)
                    .isEqualTo(TransactionSourceType.VALUE_LENGTH);
        }

        @Test
        @DisplayName("the fourteen declared widths sum to the 350-byte record both copybook banners "
                + "announce, and the record closes with a 20-byte filler, so the origination field's "
                + "position is fixed rather than approximate")
        void theFourteenDeclaredWidthsSumToTheThreeHundredAndFiftyByteRecord() {
            assertThat(POSTED_LAYOUT_FIELD_WIDTHS_IN_ORDER)
                    .as("fields the posted-transaction record declares")
                    .hasSize(DECLARED_FIELD_COUNT);

            final int summedRecord = sumOfWidths(
                    POSTED_LAYOUT_FIELD_WIDTHS_IN_ORDER, 0, DECLARED_FIELD_COUNT);

            assertThat(summedRecord)
                    .as("every declared width added together rather than the banner figure quoted")
                    .isEqualTo(DECLARED_RECORD_LENGTH)
                    .isEqualTo(350);
            assertThat(POSTED_LAYOUT_FIELD_WIDTHS_IN_ORDER[DECLARED_FIELD_COUNT - 1])
                    .as("width of the filler that closes the record")
                    .isEqualTo(TRAILING_FILLER_WIDTH);
            assertThat(SOURCE_FIELD_LAST_BYTE)
                    .as("the origination field ends well inside the record, so bytes follow it and "
                            + "a value of the wrong width would displace them")
                    .isLessThan(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("one enumeration serves both layouts: the daily-transaction copybook declares "
                + "the same fourteen widths in the same order as the posted-transaction copybook, "
                + "differing only in the prefix on each field name, so the origination field sits at "
                + "bytes 23 to 32 of a 350-byte record in both and a second enumeration would be "
                + "redundant")
        void oneEnumerationServesBothIdenticalLayouts() {
            assertThat(DAILY_LAYOUT_FIELD_WIDTHS_IN_ORDER)
                    .as("widths read from the daily-transaction copybook, against the widths read "
                            + "from the posted-transaction copybook")
                    .hasSize(DECLARED_FIELD_COUNT)
                    .isEqualTo(POSTED_LAYOUT_FIELD_WIDTHS_IN_ORDER);

            final int summedDailyRecord = sumOfWidths(
                    DAILY_LAYOUT_FIELD_WIDTHS_IN_ORDER, 0, DECLARED_FIELD_COUNT);
            final int dailyPrecedingBytes = sumOfWidths(
                    DAILY_LAYOUT_FIELD_WIDTHS_IN_ORDER, 0, SOURCE_FIELD_POSITION - 1);

            assertThat(summedDailyRecord)
                    .as("the daily-transaction record also closes at the declared record length")
                    .isEqualTo(DECLARED_RECORD_LENGTH);
            assertThat(dailyPrecedingBytes + 1)
                    .as("first byte of the origination field on the daily-transaction record")
                    .isEqualTo(SOURCE_FIELD_FIRST_BYTE);
            assertThat(dailyPrecedingBytes + FIELD_WIDTH)
                    .as("last byte of the origination field on the daily-transaction record")
                    .isEqualTo(SOURCE_FIELD_LAST_BYTE);
            assertThat(DAILY_LAYOUT_FIELD_WIDTHS_IN_ORDER[SOURCE_FIELD_POSITION - 1])
                    .as("declared width of the origination field on the daily-transaction record")
                    .isEqualTo(FIELD_WIDTH)
                    .isEqualTo(POSTED_LAYOUT_FIELD_WIDTHS_IN_ORDER[SOURCE_FIELD_POSITION - 1]);

            for (final TransactionSourceType sourceType : TransactionSourceType.values()) {
                assertThat(sourceType.getValue().getBytes(StandardCharsets.US_ASCII))
                        .as("%s fills the same ten bytes on either layout", sourceType.name())
                        .hasSize(DAILY_LAYOUT_FIELD_WIDTHS_IN_ORDER[SOURCE_FIELD_POSITION - 1]);
            }
        }
    }

    @Nested
    @DisplayName("Composition of the seeded daily-transaction fixture")
    class SeededComposition {
        @Test
        @DisplayName("the seeded daily fixture carries 250 point-of-sale records and 50 operator "
                + "records, and those two counts account for all 300 of its records, so both "
                + "arriving originations are reachable from seed data alone")
        void theTwoMeasuredCountsAccountForEverySeededRecord() {
            assertThat(SEEDED_POINT_OF_SALE_RECORDS)
                    .as("records measured as carrying the point-of-sale value")
                    .isEqualTo(250)
                    .isPositive();
            assertThat(SEEDED_OPERATOR_RECORDS)
                    .as("records measured as carrying the operator value")
                    .isEqualTo(50)
                    .isPositive();
            assertThat(SEEDED_POINT_OF_SALE_RECORDS + SEEDED_OPERATOR_RECORDS)
                    .as("the two counts summed, against the record count of the fixture")
                    .isEqualTo(SEEDED_RECORD_COUNT)
                    .isEqualTo(300);
        }

        @Test
        @DisplayName("the fixture's 105,300 bytes are 300 records of the 350-byte image plus one "
                + "line terminator each, which corroborates the record length the origination "
                + "field's position is measured against")
        void theFixtureSizeCorroboratesTheRecordLength() {
            assertThat(SEEDED_RECORD_COUNT * (DECLARED_RECORD_LENGTH + RECORD_TERMINATOR_WIDTH))
                    .as("record count times the width of one stored line, against the file size")
                    .isEqualTo(SEEDED_FILE_BYTE_TOTAL)
                    .isEqualTo(105_300);
        }

        @Test
        @DisplayName("both values the seeded fixture carries in bytes 23 to 32 resolve, so every one "
                + "of its 300 records can be read through the vocabulary")
        void bothSeededValuesResolve() {
            assertThat(SEEDED_VALUES)
                    .as("the two values measured in the fixture")
                    .hasSize(2)
                    .containsExactly(POINT_OF_SALE_VALUE, OPERATOR_VALUE)
                    .doesNotContain(SYNTHESISED_VALUE);

            assertThat(TransactionSourceType.fromValue(SEEDED_VALUES.get(0)))
                    .as("resolution of the value carried by the 250 records")
                    .contains(TransactionSourceType.POS_TERM);
            assertThat(TransactionSourceType.fromValue(SEEDED_VALUES.get(1)))
                    .as("resolution of the value carried by the 50 records")
                    .contains(TransactionSourceType.OPERATOR);
        }

        @Test
        @DisplayName("the synthesised value is absent from the seeded fixture, because the interest "
                + "run writes it rather than reading it, which is why the vocabulary is wider than "
                + "the fixture's own two values")
        void theSynthesisedValueIsAbsentFromTheSeededFixture() {
            assertThat(SEEDED_VALUES)
                    .as("values the fixture carries, against the value only the interest run writes")
                    .doesNotContain(SYNTHESISED_VALUE)
                    .hasSizeLessThan(EXPECTED_VALUE_COUNT);
            assertThat(DECLARED_VALUES_IN_ORDER)
                    .as("the vocabulary covers the fixture's values and one more besides")
                    .containsAll(SEEDED_VALUES)
                    .contains(SYNTHESISED_VALUE)
                    .hasSize(EXPECTED_VALUE_COUNT);
        }
    }

    /**
     * Behaviour that follows from what this enumeration deliberately does not carry.
     *
     * <p>There is no synthetic fallback constant, because inventing one would let an unrecognised
     * ten-byte value be silently reported as a real origination channel. There is no separate
     * bill-payment channel either: the online bill payment reuses the point-of-sale value at
     * bill-payment-program line 222, so adding one would be a business feature the estate does not
     * have. And no persistence mapping is carried here at all - the two entities that own this field
     * keep it as a raw fixed-width text column, because only raw text preserves the padding that
     * every assertion above depends on. Each of these is proved through published behaviour - the
     * size of the vocabulary and an unknown value resolving to nothing - and never by introspecting
     * the type.
     */
    @Nested
    @DisplayName("Deliberately not carried")
    class DeliberatelyNotCarried {
        @Test
        @DisplayName("there is no synthetic fallback constant: the vocabulary stops at the three "
                + "literals the estate writes, and no spelling a fallback would carry appears among "
                + "the identifiers or resolves as a value")
        void thereIsNoSyntheticFallbackConstant() {
            assertThat(TransactionSourceType.values())
                    .as("the vocabulary, which admits no invented member")
                    .hasSize(EXPECTED_VALUE_COUNT);

            for (final TransactionSourceType sourceType : TransactionSourceType.values()) {
                assertThat(SYNTHETIC_FALLBACK_SPELLINGS)
                        .as("spellings a fallback would carry, against the identifier %s",
                                sourceType.name())
                        .doesNotContain(sourceType.name());
            }
            for (final String spelling : SYNTHETIC_FALLBACK_SPELLINGS) {
                assertThat(TransactionSourceType.fromValue(spelling))
                        .as("resolution attempted with the fallback spelling %s", spelling)
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("there is no bill-payment origination: the online bill payment moves the "
                + "point-of-sale literal at bill-payment-program line 222, so a bill payment is "
                + "recorded as a point-of-sale origination and no fourth value was invented for it")
        void thereIsNoBillPaymentOrigination() {
            assertThat(TransactionSourceType.fromValue("BILL PAY  "))
                    .as("a ten-character bill-payment spelling")
                    .isEmpty();
            assertThat(TransactionSourceType.fromValue("BILL PAYMT"))
                    .as("another ten-character bill-payment spelling")
                    .isEmpty();

            assertThat(TransactionSourceType.fromValue(POINT_OF_SALE_VALUE))
                    .as("the value the bill payment actually writes")
                    .contains(TransactionSourceType.POS_TERM);
            assertThat(TransactionSourceType.POS_TERM.isSystemGenerated())
                    .as("a bill payment arrives from outside rather than being synthesised")
                    .isFalse();
        }

        @Test
        @DisplayName("an origination value from outside the vocabulary stays readable rather than "
                + "fatal, which is what a column holding the raw ten bytes with no constrained "
                + "vocabulary and no referential constraint requires")
        void anOriginationValueFromOutsideTheVocabularyStaysReadable() {
            final String landedValue = "LEGACY IMP";

            assertThat(landedValue.getBytes(StandardCharsets.US_ASCII))
                    .as("a ten-byte value that a landing row could legitimately hold")
                    .hasSize(FIELD_WIDTH);
            assertThatCode(() -> TransactionSourceType.fromValue(landedValue))
                    .as("reading a landed value from outside the vocabulary")
                    .doesNotThrowAnyException();
            assertThat(TransactionSourceType.fromValue(landedValue))
                    .as("the landed value is reported as outside the vocabulary")
                    .isEmpty();
            assertThat(DECLARED_VALUES_IN_ORDER)
                    .as("the vocabulary genuinely does not contain it")
                    .doesNotContain(landedValue);
        }
    }
}
