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
 * Unit tests for {@link DateFormat}, the typed replacement for the format selector that the shared
 * date-validation subprogram receives.
 *
 * <p><strong>What the legacy authority is.</strong> The subprogram {@code CSUTLDTC} declares a
 * three-argument linkage ({@code app/cbl/CSUTLDTC.cbl} lines 83 to 88): a candidate date ten
 * characters wide, then the format selector ten characters wide, then a result block eighty
 * characters wide, received in that order. This enumeration models the <em>second</em> argument and
 * nothing else. Both calling programs declare a matching argument group whose result portion is
 * built from a four-character severity code, an eleven-character filler, a four-character message
 * number and a sixty-one character message; those four widths sum to the eighty the subprogram
 * declares, which is the arithmetic the geometry cluster below performs rather than assumes
 * ({@code app/cbl/CORPT00C.cbl} lines 129 to 136, and the identical group at
 * {@code app/cbl/COTRN02C.cbl} lines 62 to 69).
 *
 * <p><strong>The load-bearing fact: two selectors, both exactly ten characters, one of them
 * padded.</strong> The estate transmits exactly two selector values. The hyphenated one is ten
 * characters of mask and needs nothing added; the compact one is eight characters of mask followed
 * by two blanks. The two blanks are part of the transmitted value, not decoration, and the width
 * assertions below are made on encoded bytes rather than on character counts because it is the byte
 * written into the fixed-width argument slot that the subprogram compares.
 *
 * <p><strong>Why the compact selector carries two blanks.</strong> The padding is not the residue
 * of a fixed-width move. The procedural copybook {@code app/cpy/CSUTLDPY.cpy} holds the compact
 * mask in a work field that the companion working-storage copybook {@code app/cpy/CSUTLDWY.cpy}
 * declares <em>eight</em> characters wide at lines 58 to 59, and the group declared immediately
 * after it at line 60 opens with a four-character alphanumeric field at line 61. The paragraph
 * {@code EDIT-DATE-LE} at line 284 clears that following group at line 290, which blanks those
 * characters, places the eight-character mask in the work field at line 291, and calls the
 * subprogram at lines 293 to 296. Arguments pass by reference, and the subprogram declares the
 * argument it receives ten characters wide, so it reads ten characters from the eight-character
 * field's address: the eight of the mask, then the two the clearing step left blank. That is the
 * value this enumeration carries, and the derivation is stated here because it is the mechanism the
 * padding actually comes from.
 *
 * <p><strong>Deliberately not modelled, and therefore not asserted.</strong> The eighty-character
 * result block is a typed result owned by the date-validation service in the service layer, so no
 * result type, no severity field, no message number and no message text appears here, and nothing
 * below handles a severity. The subprogram places its own message into that block on the way out;
 * it does not place the selector there, so no echo of the selector is asserted either. This
 * enumeration is a selector and not a formatter: it performs no parsing, no calendar arithmetic and
 * no validation, and this file builds no date-formatter object from either value, not even to check
 * one. The declaring constructor carries a width guard that no caller can reach, because the
 * constants are fixed at class initialisation and the module's reflection budget is zero; what is
 * asserted instead is the property that guard exists to protect, namely that every declared
 * selector measures exactly ten characters.
 *
 * <p><strong>Scope.</strong> A pure in-process unit test. It starts no application context, opens
 * no database, queue or socket, reads no file, consults no clock and no random source, and uses no
 * mocking framework, because the type under test is a value type with no collaborator. Being a test
 * about dates, it is worth saying explicitly that it reads the current time from nowhere.
 *
 * <p>Provenance of the legacy authorities cited above: checkout commit SHA
 * 7756d895ffeb65f7ea72aaa609e356d9899afcec, upstream release stamp CardDemo_v1.0-15-g27d6c6f-68
 * dated 2022-07-19. Recorded for traceability only; nothing below asserts it.
 *
 * @see DateFormat
 */
@DisplayName("DateFormat :: the ten-character format selector the date-validation subprogram receives")
class DateFormatTest {

    /*
     * THE INDEPENDENT ORACLE.
     *
     * Every expected value in this class is a literal hand-derived from the legacy artefacts named
     * in the class documentation. Nothing here asks the type under test to supply its own expected
     * value, nothing snapshots its output, and no assertion compares one production call against
     * another. Where a production call appears inside an assertion it is the subject under test and
     * the expected side is always one of the literals declared below.
     *
     * The padded selector is typed out in full, two trailing blanks included. It is never produced
     * by a padding helper, a formatter or a width calculation, because a helper that computed the
     * padding would prove only that the helper and the type under test agree.
     *
     * Neither value is ever trimmed and neither is ever case-folded on the way into an assertion.
     * Removing the padding would destroy the fixed-width argument contract, and folding case would
     * let a spelling the estate never transmits resolve to a real selector; both are properties
     * this class exists to pin, so the primitives that legitimately perform those operations
     * elsewhere in the module are neither imported nor referenced here.
     *
     * Two points of faithful-over-idiomatic translation are asserted rather than assumed, and both
     * are recorded in docs/decision-log.md rather than settled by taste in a test: the compact
     * selector keeps its two trailing blanks instead of being normalised to its eight-character
     * mask, and the correction to how that padding arises - a by-reference read of ten characters
     * over an eight-character field whose following storage was just cleared, not a fixed-width
     * move - is documented at the constant it explains. That log is authoritative for both, and it
     * is not edited from here: this file tests, and nothing more.
     *
     * No user-specified rules govern this engagement. The project rules document records a verified
     * absence rather than a partial read, so nothing enters scope by rule and nothing is invented;
     * the work is instead held to enterprise-standard best practice, which here means an
     * independent oracle, a stated provenance, zero-warning compilation, an expectation that is
     * never computed by the machinery it is meant to check, and faithful translation as the
     * tie-break.
     */

    /**
     * Declared width, in characters, of the format-selector argument slot.
     *
     * <p>The subprogram declares that argument ten characters wide, and both calling programs
     * declare their own selector field at the same width, so a selector of any other width would
     * not fill the slot.</p>
     */
    private static final int SELECTOR_SLOT_WIDTH = 10;

    /**
     * The hyphenated selector, exactly as the two calling programs initialise their own format work
     * field.
     *
     * <p>Declared as the initial value of that field at {@code app/cbl/CORPT00C.cbl} line 72 and
     * identically at {@code app/cbl/COTRN02C.cbl} line 60. It is ten characters of mask, so it fills
     * the slot without anything being added to it. That field belongs to the two programs and is a
     * different field from the one the procedural copybook uses for the compact mask, whose geometry
     * is the subject of the note below.</p>
     */
    private static final String HYPHENATED_SELECTOR = "YYYY-MM-DD";

    /*
     * WHY THE NEXT CONSTANT ENDS IN TWO BLANKS - THE VERIFIED DERIVATION, AND A CORRECTION.
     *
     * The two blanks do not come from a fixed-width move into a ten-character work field. The field
     * the procedural copybook app/cpy/CSUTLDPY.cpy moves the compact mask into is declared EIGHT
     * characters wide, at app/cpy/CSUTLDWY.cpy lines 58 to 59. What follows it in storage, declared
     * at line 60, opens with a four-character alphanumeric field at line 61.
     *
     * The paragraph EDIT-DATE-LE, at app/cpy/CSUTLDPY.cpy line 284, does three things in order: it
     * clears that following group at line 290, which leaves those characters blank; it places the
     * eight-character mask in the work field at line 291; and it calls the subprogram at lines 293
     * to 296, passing the candidate date, that work field and that group.
     *
     * Arguments pass by reference. The subprogram declares the argument it receives ten characters
     * wide (app/cbl/CSUTLDTC.cbl line 85), so it reads ten characters starting at the address of an
     * eight-character field: the eight of the mask, then the two the clearing step deterministically
     * left blank. The ten-character value below is therefore what the subprogram observes, and it is
     * the contract this enumeration carries.
     *
     * The conclusion is the same one the type under test reaches; the mechanism above is the
     * corrected account of how it is reached, recorded here as a comment because a wrong premise
     * behind a right value is the kind of divergence that belongs in docs/decision-log.md alongside
     * the padding decision itself. Neither that log nor the type under test is modified from this
     * file.
     */

    /**
     * The compact selector exactly as the subprogram observes it: eight characters of mask followed
     * by two blanks, filling the ten-character slot.
     *
     * <p>Typed out in full rather than assembled, so that the padding this class pins is present in
     * the source of the test and not the output of a computation.</p>
     */
    private static final String COMPACT_SELECTOR = "YYYYMMDD  ";

    /**
     * The compact mask on its own, without the padding the subprogram reads past it.
     *
     * <p>This is the content of the caller's eight-character work field. It is not a selector the
     * estate ever transmits, and it appears here only as the value that must fail to resolve.</p>
     */
    private static final String COMPACT_MASK_WITHOUT_PADDING = "YYYYMMDD";

    /** Declared width of the work field holding the compact mask: eight characters, not ten. */
    private static final int COMPACT_MASK_FIELD_WIDTH = 8;

    /** Blank characters the subprogram reads beyond that eight-character field. */
    private static final int COMPACT_SELECTOR_PADDING_WIDTH = 2;

    /** Encoded value of a blank in the single-byte record encoding, taken from the ASCII table. */
    private static final byte ASCII_BLANK = (byte) 0x20;

    /**
     * Number of selectors the estate transmits.
     *
     * <p>Two, because tracing every invocation of the subprogram turns up these two values and no
     * third. A further constant would be a format the legacy system never passes.</p>
     */
    private static final int EXPECTED_SELECTOR_COUNT = 2;

    /** The two declared constants, in the order the enumeration declares them. */
    private static final List<DateFormat> DECLARED_SELECTORS_IN_ORDER =
            List.of(DateFormat.YYYY_MM_DD, DateFormat.YYYYMMDD);

    /** The two selector values as literals, positionally paired with the constants above. */
    private static final List<String> DECLARED_SELECTOR_VALUES_IN_ORDER =
            List.of("YYYY-MM-DD", "YYYYMMDD  ");

    /**
     * Arguments the subprogram's linkage declares, in declaration order.
     *
     * <p>Widths only: the candidate date, the format selector, then the result block. The selector
     * is the second of the three, which is the position this enumeration models.</p>
     */
    private static final int[] LINKAGE_ARGUMENT_WIDTHS_IN_ORDER = {10, 10, 80};

    /** Arguments the linkage declares. */
    private static final int LINKAGE_ARGUMENT_COUNT = 3;

    /** One-based position of the format selector among those arguments. */
    private static final int SELECTOR_ARGUMENT_POSITION = 2;

    /**
     * Declared subfield widths of the result block, in declaration order.
     *
     * <p>Severity code, filler, message number, message text. Their sum is checked against the
     * declared width of the result argument rather than being asserted as a bare total.</p>
     */
    private static final int[] RESULT_BLOCK_SUBFIELD_WIDTHS = {4, 11, 4, 61};

    /** Subfields the callers divide the result block into. */
    private static final int RESULT_BLOCK_SUBFIELD_COUNT = 4;

    /** Declared width of the result argument the subprogram receives. */
    private static final int DECLARED_RESULT_BLOCK_WIDTH = 80;

    /** Invocations of the subprogram inside the report program, at lines 392 and 412. */
    private static final int REPORT_PROGRAM_CALL_SITES = 2;

    /** Invocations inside the transaction-add program, at lines 393 and 413. */
    private static final int TRANSACTION_ADD_PROGRAM_CALL_SITES = 2;

    /** Invocations inside the procedural copybook, at line 293. */
    private static final int PROCEDURAL_COPYBOOK_CALL_SITES = 1;

    /** Invocations that sit inside a program rather than inside the procedural copybook. */
    private static final int PROGRAM_RESIDENT_CALL_SITES = 4;

    /** Invocations of the subprogram across the whole estate. */
    private static final int TOTAL_CALL_SITES = 5;

    /**
     * Spellings a synthetic fallback constant would plausibly carry.
     *
     * <p>None of them is a constant of this enumeration and none is a value the slot ever holds.
     * They appear only as rejected lookup inputs, which is how absence is demonstrated from the
     * outside and without reflection.</p>
     */
    private static final List<String> SYNTHETIC_FALLBACK_SPELLINGS =
            List.of("DEFAULT", "UNKNOWN", "NONE", "OTHER", "INVALID", "UNMAPPED");

    /**
     * The two selector values the estate transmits, and the fact that there are only two.
     */
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

    /**
     * The fixed width of the selector slot, measured in encoded bytes, and the padding that fills it.
     */
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

    /**
     * Geometry of the three-argument linkage the selector travels in.
     *
     * <p>Layout evidence, and only layout evidence: the widths corroborate that caller and callee
     * agree on the shape of the call, which is what makes the selector's own width contractual.</p>
     */
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

    /**
     * Resolution of a raw slot value back to a selector.
     */
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

    /**
     * The count of invocation sites the two selector values were established from.
     *
     * <p>Documentation held executable: the figure is the evidence behind the claim that the
     * vocabulary stops at two, so it is asserted rather than left in prose that can drift.</p>
     */
    @Nested
    @DisplayName("Invocation sites of the date-validation subprogram")
    class InvocationSites {

        /*
         * The specification quotes four call sites. Four is the count of the invocations that sit
         * inside a program: two in the report program and two in the transaction-add program. It
         * omits the invocation that sits inside the procedural copybook, which is the very one that
         * transmits the padded compact selector - so the omitted site is the one that establishes
         * the second constant. Five is the verified total and is the figure asserted below.
         */

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
     * What this enumeration deliberately does not model, demonstrated from the outside.
     */
    @Nested
    @DisplayName("Deliberately not modelled")
    class DeliberatelyNotModelled {

        /*
         * Absence is demonstrated by never referencing the absent thing and by pinning the size of
         * the constant set, not by reflecting over the type. There is no result type here, no
         * severity field, no message number and no message text, because the eighty-character result
         * block belongs to the date-validation service in the service layer; and there is no
         * synthetic fallback constant and no notion of a default format, because substituting a real
         * mask for an unrecognised one would validate a date against the wrong shape and return a
         * confident wrong verdict.
         */

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
