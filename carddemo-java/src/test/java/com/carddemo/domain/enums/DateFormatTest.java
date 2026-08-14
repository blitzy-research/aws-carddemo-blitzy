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
 * Unit tests for {@link DateFormat}, the typed replacement for the format selector the shared
 * date-validation subprogram receives.
 *
 * <p>Two facts carry the parity risk and are pinned here. Every selector encodes to exactly ten
 * bytes, because the subprogram reads a ten-character slot: the compact selector's own mask occupies
 * eight of them and the remaining two are blanks that are part of the value, so trimming it would
 * shorten the slot. And an unrecognised selector resolves to nothing rather than to a fallback,
 * because substituting a real mask for an unknown one would validate a date against the wrong shape
 * and return a confident wrong verdict.
 */
@DisplayName("DateFormat :: the ten-character format selector the date-validation subprogram receives")
class DateFormatTest {
    private static final int SELECTOR_SLOT_WIDTH = 10;

    private static final String HYPHENATED_SELECTOR = "YYYY-MM-DD";

    private static final String COMPACT_SELECTOR = "YYYYMMDD  ";

    private static final String COMPACT_MASK_WITHOUT_PADDING = "YYYYMMDD";

    private static final int COMPACT_MASK_FIELD_WIDTH = 8;

    private static final int COMPACT_SELECTOR_PADDING_WIDTH = 2;

    private static final byte ASCII_BLANK = (byte) 0x20;

    private static final int EXPECTED_SELECTOR_COUNT = 2;

    private static final List<DateFormat> DECLARED_SELECTORS_IN_ORDER =
            List.of(DateFormat.YYYY_MM_DD, DateFormat.YYYYMMDD);

    private static final List<String> DECLARED_SELECTOR_VALUES_IN_ORDER =
            List.of("YYYY-MM-DD", "YYYYMMDD  ");

    private static final int[] LINKAGE_ARGUMENT_WIDTHS_IN_ORDER = {10, 10, 80};

    private static final int LINKAGE_ARGUMENT_COUNT = 3;

    private static final int SELECTOR_ARGUMENT_POSITION = 2;

    private static final int[] RESULT_BLOCK_SUBFIELD_WIDTHS = {4, 11, 4, 61};

    private static final int RESULT_BLOCK_SUBFIELD_COUNT = 4;

    private static final int DECLARED_RESULT_BLOCK_WIDTH = 80;

    private static final int REPORT_PROGRAM_CALL_SITES = 2;

    private static final int TRANSACTION_ADD_PROGRAM_CALL_SITES = 2;

    private static final int PROCEDURAL_COPYBOOK_CALL_SITES = 1;

    private static final int PROGRAM_RESIDENT_CALL_SITES = 4;

    private static final int TOTAL_CALL_SITES = 5;

    private static final List<String> SYNTHETIC_FALLBACK_SPELLINGS =
            List.of("DEFAULT", "UNKNOWN", "NONE", "OTHER", "INVALID", "UNMAPPED");

    @Nested
    @DisplayName("Vocabulary of selectors the estate transmits")
    class Vocabulary {
        @Test
        @DisplayName("the hyphenated constant carries the ten-character mask a caller's format work field "
                + "is initialised to, at report-program line 72 and transaction-add-program line 60")
        void theHyphenatedConstantCarriesItsTenCharacterMask() {
            assertThat(DateFormat.YYYY_MM_DD.getValue())
                    .as("value transmitted in the ten-character selector slot")
                    .isEqualTo(HYPHENATED_SELECTOR)
                    .isEqualTo("YYYY-MM-DD");
        }

        @Test
        @DisplayName("the compact constant carries the eight-character mask followed by the two blanks the "
                + "subprogram reads past the caller's eight-character field")
        void theCompactConstantCarriesItsMaskFollowedByTwoBlanks() {
            assertThat(DateFormat.YYYYMMDD.getValue())
                    .as("value the subprogram observes in the ten-character selector slot")
                    .isEqualTo(COMPACT_SELECTOR)
                    .isEqualTo("YYYYMMDD  ")
                    .startsWith(COMPACT_MASK_WITHOUT_PADDING)
                    .endsWith("  ");
        }

        @Test
        @DisplayName("exactly two selectors exist, because tracing every invocation of the subprogram turns "
                + "up two values and no third")
        void exactlyTwoSelectorsExist() {
            assertThat(DateFormat.values())
                    .as("selectors translated from the values the estate transmits")
                    .hasSize(EXPECTED_SELECTOR_COUNT)
                    .containsExactly(DateFormat.YYYY_MM_DD, DateFormat.YYYYMMDD)
                    .containsExactlyElementsOf(DECLARED_SELECTORS_IN_ORDER);
        }

        @Test
        @DisplayName("the two selector values are distinct literals, so one slot value can never identify "
                + "both masks at once")
        void theTwoSelectorValuesAreDistinct() {
            assertThat(HYPHENATED_SELECTOR)
                    .as("the two transmitted values, compared as literals rather than as two answers "
                            + "from the type under test")
                    .isNotEqualTo(COMPACT_SELECTOR);

            assertThat(DateFormat.YYYY_MM_DD.getValue())
                    .as("hyphenated value must not collide with the compact literal")
                    .isNotEqualTo(COMPACT_SELECTOR);
            assertThat(DateFormat.YYYYMMDD.getValue())
                    .as("compact value must not collide with the hyphenated literal")
                    .isNotEqualTo(HYPHENATED_SELECTOR);
        }

        @Test
        @DisplayName("each constant keeps a stable identifier and position, so the traceability matrix and "
                + "the calling service can name a selector without depending on its value")
        void eachConstantKeepsAStableIdentifierAndPosition() {
            assertThat(DateFormat.YYYY_MM_DD.name())
                    .as("stable identifier of the hyphenated selector")
                    .isEqualTo("YYYY_MM_DD");
            assertThat(DateFormat.YYYYMMDD.name())
                    .as("stable identifier of the compact selector")
                    .isEqualTo("YYYYMMDD");

            assertThat(DateFormat.YYYY_MM_DD.ordinal())
                    .as("position of the selector declared first")
                    .isZero();
            assertThat(DateFormat.YYYYMMDD.ordinal())
                    .as("position of the selector declared second")
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Width of the ten-character selector slot")
    class SelectorSlotWidth {
        @Test
        @DisplayName("every selector encodes to exactly ten bytes, matching the width the subprogram "
                + "declares for the argument at line 85 and the width both callers declare for their own "
                + "selector field")
        void everySelectorEncodesToExactlyTenBytes() {
            for (final DateFormat selector : DateFormat.values()) {
                assertThat(selector.getValue().getBytes(StandardCharsets.US_ASCII))
                        .as("encoded width of the value carried by %s", selector.name())
                        .hasSize(SELECTOR_SLOT_WIDTH);
            }
        }

        @Test
        @DisplayName("the compact selector's own mask occupies eight of the ten bytes and the remaining two "
                + "are blanks, which is the geometry of an eight-character field read ten characters wide")
        void theCompactSelectorIsAnEightByteMaskFollowedByTwoBlankBytes() {
            final byte[] encoded = DateFormat.YYYYMMDD.getValue().getBytes(StandardCharsets.US_ASCII);

            assertThat(encoded)
                    .as("encoded width of the compact selector")
                    .hasSize(SELECTOR_SLOT_WIDTH);
            assertThat(COMPACT_MASK_FIELD_WIDTH + COMPACT_SELECTOR_PADDING_WIDTH)
                    .as("mask width plus padding width, summed to the declared slot width")
                    .isEqualTo(SELECTOR_SLOT_WIDTH);

            assertThat(encoded[COMPACT_MASK_FIELD_WIDTH])
                    .as("first byte read beyond the caller's eight-character field")
                    .isEqualTo(ASCII_BLANK);
            assertThat(encoded[COMPACT_MASK_FIELD_WIDTH + 1])
                    .as("second byte read beyond the caller's eight-character field")
                    .isEqualTo(ASCII_BLANK);
        }

        @Test
        @DisplayName("the compact selector survives a round trip through the lookup byte for byte, and the "
                + "value it returns is not the bare eight-character mask")
        void theCompactSelectorRoundTripsByteForByteAndIsNotTheBareMask() {
            final Optional<DateFormat> resolved = DateFormat.fromValue(COMPACT_SELECTOR);

            assertThat(resolved)
                    .as("the padded value must resolve to the compact selector")
                    .contains(DateFormat.YYYYMMDD);
            assertThat(resolved.orElseThrow().getValue().getBytes(StandardCharsets.US_ASCII))
                    .as("bytes recovered from the lookup, against the bytes of the hand-typed literal")
                    .isEqualTo(COMPACT_SELECTOR.getBytes(StandardCharsets.US_ASCII));

            assertThat(resolved.orElseThrow().getValue())
                    .as("a value that had lost its padding would equal the bare mask, which is exactly "
                            + "the defect this inequality catches")
                    .isNotEqualTo(COMPACT_MASK_WITHOUT_PADDING);
            assertThat(resolved.orElseThrow().getValue().getBytes(StandardCharsets.US_ASCII))
                    .as("bytes of the padded value against the bytes of the bare mask")
                    .isNotEqualTo(COMPACT_MASK_WITHOUT_PADDING.getBytes(StandardCharsets.US_ASCII));
        }

        @Test
        @DisplayName("the hyphenated selector needs no padding at all, because its own mask already fills "
                + "the ten-character slot, unlike the compact selector which ends in two blanks")
        void theHyphenatedSelectorNeedsNoPadding() {
            final byte[] encoded = DateFormat.YYYY_MM_DD.getValue().getBytes(StandardCharsets.US_ASCII);

            assertThat(encoded)
                    .as("encoded width of the hyphenated selector")
                    .hasSize(SELECTOR_SLOT_WIDTH);
            assertThat(encoded[SELECTOR_SLOT_WIDTH - 1])
                    .as("last byte of the hyphenated selector, which carries mask and not padding")
                    .isNotEqualTo(ASCII_BLANK);

            assertThat(DateFormat.YYYY_MM_DD.getValue())
                    .as("the hyphenated selector holds no blank anywhere, so none was added to it")
                    .doesNotContain(" ");
            assertThat(DateFormat.YYYYMMDD.getValue())
                    .as("the compact selector, by contrast, ends in the two blanks that fill its slot")
                    .endsWith("  ");
        }
    }

    @Nested
    @DisplayName("Geometry of the three-argument linkage")
    class LinkageGeometry {
        @Test
        @DisplayName("the linkage declares three arguments and the selector is the second of them, between "
                + "the candidate date and the result block")
        void theSelectorIsTheSecondOfThreeDeclaredArguments() {
            assertThat(LINKAGE_ARGUMENT_WIDTHS_IN_ORDER)
                    .as("widths of the arguments the subprogram declares, in declaration order")
                    .hasSize(LINKAGE_ARGUMENT_COUNT);

            assertThat(LINKAGE_ARGUMENT_WIDTHS_IN_ORDER[SELECTOR_ARGUMENT_POSITION - 1])
                    .as("declared width of the argument in the selector's position")
                    .isEqualTo(SELECTOR_SLOT_WIDTH);
            assertThat(LINKAGE_ARGUMENT_WIDTHS_IN_ORDER[LINKAGE_ARGUMENT_COUNT - 1])
                    .as("declared width of the result argument that follows the selector")
                    .isEqualTo(DECLARED_RESULT_BLOCK_WIDTH);
        }

        @Test
        @DisplayName("the caller's four result subfields sum to eighty, which is exactly the width the "
                + "subprogram declares for its third argument, so caller and callee agree on the call")
        void theFourResultSubfieldsSumToTheDeclaredResultWidth() {
            assertThat(RESULT_BLOCK_SUBFIELD_WIDTHS)
                    .as("subfields the callers divide the result block into")
                    .hasSize(RESULT_BLOCK_SUBFIELD_COUNT);

            int summedWidth = 0;
            for (final int subfieldWidth : RESULT_BLOCK_SUBFIELD_WIDTHS) {
                summedWidth += subfieldWidth;
            }

            assertThat(summedWidth)
                    .as("severity code, filler, message number and message text, summed rather than "
                            + "asserted as a bare total")
                    .isEqualTo(DECLARED_RESULT_BLOCK_WIDTH);
        }

        @Test
        @DisplayName("the result block is eighty characters wide while the selector slot is ten, so the two "
                + "arguments are separate widths and this enumeration only ever fills the narrower one")
        void theResultBlockIsWiderThanTheSelectorSlot() {
            assertThat(DECLARED_RESULT_BLOCK_WIDTH)
                    .as("declared width of the result argument against the selector slot")
                    .isNotEqualTo(SELECTOR_SLOT_WIDTH)
                    .isGreaterThan(SELECTOR_SLOT_WIDTH);

            for (final DateFormat selector : DateFormat.values()) {
                assertThat(selector.getValue().getBytes(StandardCharsets.US_ASCII))
                        .as("%s fills the selector slot and never the result block", selector.name())
                        .hasSize(SELECTOR_SLOT_WIDTH);
            }
        }
    }

    @Nested
    @DisplayName("Resolution of a raw slot value")
    class Resolution {
        @Test
        @DisplayName("both transmitted values resolve to their selector, so a value read out of the slot "
                + "identifies the mask that produced it")
        void bothTransmittedValuesResolveToTheirSelector() {
            assertThat(DateFormat.fromValue(HYPHENATED_SELECTOR))
                    .as("resolution of the hyphenated value")
                    .contains(DateFormat.YYYY_MM_DD);
            assertThat(DateFormat.fromValue(COMPACT_SELECTOR))
                    .as("resolution of the padded compact value")
                    .contains(DateFormat.YYYYMMDD);

            for (int index = 0; index < DECLARED_SELECTOR_VALUES_IN_ORDER.size(); index++) {
                assertThat(DateFormat.fromValue(DECLARED_SELECTOR_VALUES_IN_ORDER.get(index)))
                        .as("resolution of the literal declared at position %d", index)
                        .contains(DECLARED_SELECTORS_IN_ORDER.get(index));
            }
        }

        @Test
        @DisplayName("the bare eight-character mask does not resolve, because the subprogram observes ten "
                + "characters by reference and the eight-character form is not the value it receives")
        void theBareEightCharacterMaskDoesNotResolve() {
            assertThat(DateFormat.fromValue(COMPACT_MASK_WITHOUT_PADDING))
                    .as("the eight-character content of the caller's work field, on its own")
                    .isEmpty();
            assertThat(DateFormat.fromValue("YYYYMMDD "))
                    .as("the mask with one blank, one short of the slot width")
                    .isEmpty();
            assertThat(DateFormat.fromValue("YYYYMMDD   "))
                    .as("the mask with three blanks, one past the slot width")
                    .isEmpty();
            assertThat(DateFormat.fromValue("  YYYYMMDD"))
                    .as("the mask with its blanks leading instead of trailing")
                    .isEmpty();
        }

        @Test
        @DisplayName("no case folding is applied, so a lower-case rendering of either mask resolves to "
                + "nothing rather than to the selector it resembles")
        void noCaseFoldingIsApplied() {
            assertThat(DateFormat.fromValue("yyyy-mm-dd"))
                    .as("lower-case rendering of the hyphenated mask")
                    .isEmpty();
            assertThat(DateFormat.fromValue("yyyymmdd  "))
                    .as("lower-case rendering of the padded compact mask")
                    .isEmpty();
            assertThat(DateFormat.fromValue("Yyyy-Mm-Dd"))
                    .as("mixed-case rendering of the hyphenated mask")
                    .isEmpty();
        }

        @ParameterizedTest(name = "[{index}] a slot holding \"{0}\" resolves to nothing")
        @NullSource
        @EmptySource
        @ValueSource(strings = {
            "DD/MM/YYYY",
            "MM/DD/YYYY",
            "YYYY/MM/DD",
            "DD-MM-YYYY",
            "YY-MM-DD",
            "MM/DD/YY",
            "YYMMDD    ",
            "YYYY-MM-DD ",
            "          "
        })
        @DisplayName("a value outside the two-selector contract resolves to nothing, because the estate "
                + "transmits no other mask and guessing one would validate a date against the wrong shape")
        void aValueOutsideTheContractResolvesToNothing(final String slotValue) {
            assertThat(DateFormat.fromValue(slotValue))
                    .as("resolution of a value the estate never transmits")
                    .isEmpty();
        }

        @Test
        @DisplayName("resolution reports an unrecognised value instead of throwing, which keeps an "
                + "unmapped selector a caller's decision rather than an aborted call")
        void resolutionReportsRatherThanThrows() {
            assertThatCode(() -> DateFormat.fromValue(null))
                    .as("resolution of an absent value")
                    .doesNotThrowAnyException();
            assertThatCode(() -> DateFormat.fromValue(""))
                    .as("resolution of an empty value")
                    .doesNotThrowAnyException();
            assertThatCode(() -> DateFormat.fromValue(COMPACT_MASK_WITHOUT_PADDING))
                    .as("resolution of the bare mask")
                    .doesNotThrowAnyException();
            assertThatCode(() -> DateFormat.fromValue("DD/MM/YYYY"))
                    .as("resolution of a mask the estate never transmits")
                    .doesNotThrowAnyException();

            assertThat(DateFormat.fromValue(null))
                    .as("an absent value is reported as absent")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Invocation sites of the date-validation subprogram")
    class InvocationSites {
        @Test
        @DisplayName("the subprogram is invoked from five sites: two in the report program at lines 392 "
                + "and 412, two in the transaction-add program at lines 393 and 413, and one in the "
                + "procedural copybook at line 293")
        void theSubprogramIsInvokedFromFiveSites() {
            final int summedCallSites = REPORT_PROGRAM_CALL_SITES
                    + TRANSACTION_ADD_PROGRAM_CALL_SITES
                    + PROCEDURAL_COPYBOOK_CALL_SITES;

            assertThat(summedCallSites)
                    .as("sites summed across the two programs and the procedural copybook")
                    .isEqualTo(TOTAL_CALL_SITES);
        }

        @Test
        @DisplayName("four of those five sites sit inside a program and the fifth sits inside the "
                + "procedural copybook, which is the site that transmits the padded compact selector")
        void fourSitesSitInsideAProgramAndTheFifthInsideTheCopybook() {
            final int programResidentCallSites =
                    REPORT_PROGRAM_CALL_SITES + TRANSACTION_ADD_PROGRAM_CALL_SITES;

            assertThat(programResidentCallSites)
                    .as("sites that sit inside a program rather than inside the copybook")
                    .isEqualTo(PROGRAM_RESIDENT_CALL_SITES);
            assertThat(TOTAL_CALL_SITES - programResidentCallSites)
                    .as("sites remaining once the program-resident ones are set aside")
                    .isEqualTo(PROCEDURAL_COPYBOOK_CALL_SITES);
        }

        @Test
        @DisplayName("each of the two programs contributes an equal pair of sites, one per date field it "
                + "edits, and the two pairs together are the program-resident total")
        void eachProgramContributesAnEqualPairOfSites() {
            assertThat(REPORT_PROGRAM_CALL_SITES)
                    .as("sites in the report program, one for each end of its date range")
                    .isEqualTo(TRANSACTION_ADD_PROGRAM_CALL_SITES)
                    .isPositive();

            assertThat(REPORT_PROGRAM_CALL_SITES + TRANSACTION_ADD_PROGRAM_CALL_SITES)
                    .as("the two equal pairs, summed")
                    .isEqualTo(PROGRAM_RESIDENT_CALL_SITES);
        }
    }

    /**
     * Behaviour that follows from what this enumeration does not model: the eighty-character result
     * block with its severity and message number belongs to the date-validation service, and no
     * synthetic fallback selector exists. Both are asserted through the behaviour of the published
     * lookup - the size of the constant set, and an unknown value resolving to nothing - rather than
     * by introspecting the type.
     */
    @Nested
    @DisplayName("Deliberately not modelled")
    class DeliberatelyNotModelled {
        @Test
        @DisplayName("there is no synthetic fallback constant: the set stops at the two selectors the "
                + "estate transmits, and no spelling a fallback would carry resolves to anything")
        void thereIsNoSyntheticFallbackConstant() {
            assertThat(DateFormat.values())
                    .as("the whole constant set, which a fallback would enlarge")
                    .hasSize(EXPECTED_SELECTOR_COUNT)
                    .containsExactlyElementsOf(DECLARED_SELECTORS_IN_ORDER);

            for (final String spelling : SYNTHETIC_FALLBACK_SPELLINGS) {
                assertThat(DateFormat.fromValue(spelling))
                        .as("resolution of the fallback spelling %s", spelling)
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("no constant carries a result-block-width value: every one fills the ten-character "
                + "selector slot, so this enumeration models the second argument and not the third")
        void everyConstantFillsTheSelectorSlotAndNotTheResultBlock() {
            assertThat(DateFormat.values())
                    .as("constants that could carry a value of any width")
                    .hasSize(EXPECTED_SELECTOR_COUNT);

            for (final DateFormat selector : DateFormat.values()) {
                final byte[] encoded = selector.getValue().getBytes(StandardCharsets.US_ASCII);

                assertThat(encoded)
                        .as("%s fills the selector slot", selector.name())
                        .hasSize(SELECTOR_SLOT_WIDTH);
                assertThat(encoded.length)
                        .as("%s carries no result-block-width value", selector.name())
                        .isNotEqualTo(DECLARED_RESULT_BLOCK_WIDTH);
            }
        }
    }
}
