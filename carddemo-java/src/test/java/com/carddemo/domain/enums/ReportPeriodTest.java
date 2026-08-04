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
 * Unit tests for {@link ReportPeriod}, the typed replacement for the period discriminator the
 * report-request program of transaction {@code CR00} writes into its report-name work field.
 *
 * <p><strong>Why these three values are bare, and why that is not an oversight.</strong> The work
 * field is declared ten characters wide and blank-filled at {@code app/cbl/CORPT00C.cbl} line 58,
 * so a reader who stops at the declaration expects ten-character values. Tracing every one of the
 * six references to that field settles the question the other way. Three are writes, at lines 214,
 * 240 and 433, and they store literals of seven, six and six characters. The remaining two are
 * reads, at line 449 and inside the submission paragraph at line 468, and both consume the field
 * delimited by space, so they stop at the first blank and the fill can never reach an output. No
 * reference anywhere compares the field against a literal, so no branch in the program is sensitive
 * to its width either. The observable value of a period is therefore the bare literal, and this
 * class proves it by pinning the encoded width of each value at seven, six and six bytes and by
 * proving each value is not its ten-character rendering.
 *
 * <p><strong>The asymmetry against the padded enumerations in this same package, stated
 * explicitly.</strong> {@link KeyAction} pads every value to five characters, and both
 * {@link TransactionSourceType} and {@link DateFormat} pad every value to ten, because in each of
 * those cases the fill is genuinely transmitted: the value is compared verbatim, or is read across
 * a fixed-width linkage slot, so removing the fill would change what a caller observes. Nothing of
 * that kind holds here, which is why this enumeration alone carries bare values. Padding them for
 * consistency with its siblings would fabricate three values that no legacy code path can either
 * produce or observe, and would additionally make the ten-character rendering resolve through the
 * lookup, which is the defect the padded-form probes in this class are written to catch. The
 * governing decision is recorded as DL-035 in {@code docs/decision-log.md}: a padded value and a
 * bare value are different values, and no accessor trims, pads or normalises a fixed-width value.
 *
 * <p><strong>The casing is data, not presentation.</strong> Each literal carries a capital initial
 * letter followed by lower-case letters, and it is the only enumeration in this package whose
 * values are spelled that way. The literal reaches the operator through the two delimited reads, so
 * re-casing it - upwards to match the constant identifier, or downwards for tidiness - would change
 * a value that leaves the system. Every expectation in this class is therefore a hand-typed literal
 * in the legacy spelling, and no folding operation is applied anywhere, which also makes the whole
 * class immune to the default locale.
 *
 * <p><strong>Context that is deliberately not asserted here.</strong> The program tests its three
 * screen selections in a fixed order, the current-month selection first at line 213, the
 * current-year selection second at line 239 and the operator-supplied selection third at line 256,
 * and it stops at the first selection that is set; its catch-all at line 437 raises a validation
 * message and stores nothing, so it is not a fourth period. That ordering, the date pair
 * the operator supplies for the third selection, the submission confirmation gate and the error
 * flag are all the report-request service's contract rather than this type's, so none of them is
 * asserted in this class and no clock, date type or screen message appears in it.
 *
 * <p>Traced from the checkout at commit 7756d895ffeb65f7ea72aaa609e356d9899afcec, whose report
 * program carries the upstream release stamp CardDemo_v1.0-15-g27d6c6f-68 dated 2022-07-19.
 */
@DisplayName("ReportPeriod :: the three bare period literals the report-request program writes")
class ReportPeriodTest {

    /**
     * Declared width of the report-name work field, {@code PIC X(10)} at report-program line 58.
     * Layout evidence, not a threshold: it is the width the values are proved <em>not</em> to fill.
     */
    private static final int REPORT_NAME_FIELD_WIDTH = 10;

    /** One period per selection branch the program offers, and the catch-all adds none. */
    private static final int EXPECTED_PERIOD_COUNT = 3;

    /** Literal stored by the current-month branch at report-program line 214. */
    private static final String CURRENT_MONTH_VALUE = "Monthly";

    /** Literal stored by the current-year branch at report-program line 240. */
    private static final String CURRENT_YEAR_VALUE = "Yearly";

    /** Literal stored by the operator-supplied branch at report-program line 433. */
    private static final String OPERATOR_SUPPLIED_VALUE = "Custom";

    /** Encoded width of the current-month literal, counted character by character. */
    private static final int CURRENT_MONTH_VALUE_WIDTH = 7;

    /** Encoded width of the current-year literal, counted character by character. */
    private static final int CURRENT_YEAR_VALUE_WIDTH = 6;

    /** Encoded width of the operator-supplied literal, counted character by character. */
    private static final int OPERATOR_SUPPLIED_VALUE_WIDTH = 6;

    /** Blanks the ten-character field retains after the current-month literal is stored. */
    private static final int CURRENT_MONTH_FILL_WIDTH = 3;

    /** Blanks the ten-character field retains after the current-year literal is stored. */
    private static final int CURRENT_YEAR_FILL_WIDTH = 4;

    /** Blanks the ten-character field retains after the operator-supplied literal is stored. */
    private static final int OPERATOR_SUPPLIED_FILL_WIDTH = 4;

    /**
     * The current-month literal rendered across the whole ten-character field, typed out with its
     * trailing blanks visible. A negative expectation only: both reads stop at the first blank, so
     * this rendering is never observable and must never be a value or a resolvable key.
     */
    private static final String CURRENT_MONTH_FIELD_RENDERING = "Monthly   ";

    /** The current-year literal rendered across the whole field. Negative expectation only. */
    private static final String CURRENT_YEAR_FIELD_RENDERING = "Yearly    ";

    /** The operator-supplied literal rendered across the whole field. Negative expectation only. */
    private static final String OPERATOR_SUPPLIED_FIELD_RENDERING = "Custom    ";

    /**
     * All-capitals rendering of the current-month literal, hand-typed rather than folded. A
     * negative expectation only, and the spelling a caller would send if it mistook the constant
     * identifier for the transmitted value.
     */
    private static final String CURRENT_MONTH_ALL_CAPITALS = "MONTHLY";

    /** All-lower-case rendering of the current-month literal. Negative expectation only. */
    private static final String CURRENT_MONTH_NO_CAPITALS = "monthly";

    /** All-capitals rendering of the current-year literal. Negative expectation only. */
    private static final String CURRENT_YEAR_ALL_CAPITALS = "YEARLY";

    /** All-lower-case rendering of the current-year literal. Negative expectation only. */
    private static final String CURRENT_YEAR_NO_CAPITALS = "yearly";

    /** All-capitals rendering of the operator-supplied literal. Negative expectation only. */
    private static final String OPERATOR_SUPPLIED_ALL_CAPITALS = "CUSTOM";

    /** All-lower-case rendering of the operator-supplied literal. Negative expectation only. */
    private static final String OPERATOR_SUPPLIED_NO_CAPITALS = "custom";

    /**
     * Width of one screen selection flag, {@code PIC X(1)} in the symbolic map. Layout evidence
     * used to show that no period value is a selection flag in disguise.
     */
    private static final int SCREEN_SELECTION_FLAG_WIDTH = 1;

    /** ASCII blank, the byte the field fill is made of. */
    private static final byte ASCII_BLANK = (byte) 0x20;

    /** The three literals, hand-typed, as the set membership oracle for the whole class. */
    private static final List<String> LEGACY_LITERALS =
            List.of("Monthly", "Yearly", "Custom");

    /** Spellings a synthetic fallback constant would plausibly carry. None may resolve. */
    private static final List<String> SYNTHETIC_FALLBACK_SPELLINGS =
            List.of("DEFAULT", "Default", "UNKNOWN", "Unknown", "NONE", "None", "OTHER", "Other",
                    "INVALID", "Invalid", "UNMAPPED", "Unmapped");

    @Nested
    @DisplayName("Vocabulary the report program writes into the report-name field")
    class Vocabulary {

        @Test
        @DisplayName("the current-month constant carries the seven-character literal stored at "
                + "report-program line 214, spelled exactly as the program spells it")
        void theCurrentMonthConstantCarriesItsLegacyLiteral() {
            assertThat(ReportPeriod.MONTHLY.getValue())
                    .as("literal the current-month branch stores in the report-name field")
                    .isEqualTo(CURRENT_MONTH_VALUE)
                    .isEqualTo("Monthly");
        }

        @Test
        @DisplayName("the current-year constant carries the six-character literal stored at "
                + "report-program line 240, spelled exactly as the program spells it")
        void theCurrentYearConstantCarriesItsLegacyLiteral() {
            assertThat(ReportPeriod.YEARLY.getValue())
                    .as("literal the current-year branch stores in the report-name field")
                    .isEqualTo(CURRENT_YEAR_VALUE)
                    .isEqualTo("Yearly");
        }

        @Test
        @DisplayName("the operator-supplied constant carries the six-character literal stored at "
                + "report-program line 433, spelled exactly as the program spells it")
        void theOperatorSuppliedConstantCarriesItsLegacyLiteral() {
            assertThat(ReportPeriod.CUSTOM.getValue())
                    .as("literal the operator-supplied branch stores in the report-name field")
                    .isEqualTo(OPERATOR_SUPPLIED_VALUE)
                    .isEqualTo("Custom");
        }

        @Test
        @DisplayName("exactly three periods exist, because the report program writes the field from "
                + "three branches and its catch-all at line 437 writes nothing")
        void exactlyThreePeriodsExist() {
            assertThat(ReportPeriod.values())
                    .as("the whole constant set, one member per branch that writes the field")
                    .hasSize(EXPECTED_PERIOD_COUNT)
                    .containsExactlyInAnyOrder(
                            ReportPeriod.MONTHLY, ReportPeriod.YEARLY, ReportPeriod.CUSTOM);
        }

        @Test
        @DisplayName("the three literals are distinct, so one field value can never name two periods")
        void theThreeLiteralsAreDistinct() {
            assertThat(LEGACY_LITERALS)
                    .as("the three hand-typed literals, compared with each other rather than with "
                            + "any answer from the type under test")
                    .hasSize(EXPECTED_PERIOD_COUNT)
                    .doesNotHaveDuplicates();

            assertThat(ReportPeriod.MONTHLY.getValue())
                    .as("the current-month literal must collide with neither of the others")
                    .isNotEqualTo(CURRENT_YEAR_VALUE)
                    .isNotEqualTo(OPERATOR_SUPPLIED_VALUE);
            assertThat(ReportPeriod.YEARLY.getValue())
                    .as("the current-year literal must collide with neither of the others")
                    .isNotEqualTo(CURRENT_MONTH_VALUE)
                    .isNotEqualTo(OPERATOR_SUPPLIED_VALUE);
            assertThat(ReportPeriod.CUSTOM.getValue())
                    .as("the operator-supplied literal must collide with neither of the others")
                    .isNotEqualTo(CURRENT_MONTH_VALUE)
                    .isNotEqualTo(CURRENT_YEAR_VALUE);
        }

        @Test
        @DisplayName("every declared value is one of the three literals the program writes, so no "
                + "constant carries a value the report program never stores")
        void everyDeclaredValueIsOneOfTheThreeLiterals() {
            for (final ReportPeriod period : ReportPeriod.values()) {
                assertThat(period.getValue())
                        .as("value carried by %s", period.name())
                        .isIn(LEGACY_LITERALS);
            }
        }

        @Test
        @DisplayName("each constant keeps a stable identifier that is not the transmitted value, so "
                + "naming a period never means re-casing what the program wrote")
        void eachConstantKeepsAnIdentifierDistinctFromItsValue() {
            assertThat(ReportPeriod.MONTHLY.name())
                    .as("identifier of the current-month constant")
                    .isEqualTo("MONTHLY")
                    .isNotEqualTo(CURRENT_MONTH_VALUE);
            assertThat(ReportPeriod.YEARLY.name())
                    .as("identifier of the current-year constant")
                    .isEqualTo("YEARLY")
                    .isNotEqualTo(CURRENT_YEAR_VALUE);
            assertThat(ReportPeriod.CUSTOM.name())
                    .as("identifier of the operator-supplied constant")
                    .isEqualTo("CUSTOM")
                    .isNotEqualTo(OPERATOR_SUPPLIED_VALUE);
        }
    }

    @Nested
    @DisplayName("Bare values inside the ten-character report-name field")
    class BareValueWidth {

        @Test
        @DisplayName("the current-month value encodes to exactly seven bytes, the width of the "
                + "literal at report-program line 214 rather than the width of the field")
        void theCurrentMonthValueEncodesToSevenBytes() {
            final byte[] encoded =
                    ReportPeriod.MONTHLY.getValue().getBytes(StandardCharsets.US_ASCII);

            assertThat(encoded.length)
                    .as("encoded width of the current-month value")
                    .isEqualTo(CURRENT_MONTH_VALUE_WIDTH);
            assertThat(encoded)
                    .as("bytes of the current-month value, against the hand-typed literal's bytes")
                    .isEqualTo("Monthly".getBytes(StandardCharsets.US_ASCII));
        }

        @Test
        @DisplayName("the current-year value encodes to exactly six bytes, the width of the literal "
                + "at report-program line 240 rather than the width of the field")
        void theCurrentYearValueEncodesToSixBytes() {
            final byte[] encoded =
                    ReportPeriod.YEARLY.getValue().getBytes(StandardCharsets.US_ASCII);

            assertThat(encoded.length)
                    .as("encoded width of the current-year value")
                    .isEqualTo(CURRENT_YEAR_VALUE_WIDTH);
            assertThat(encoded)
                    .as("bytes of the current-year value, against the hand-typed literal's bytes")
                    .isEqualTo("Yearly".getBytes(StandardCharsets.US_ASCII));
        }

        @Test
        @DisplayName("the operator-supplied value encodes to exactly six bytes, the width of the "
                + "literal at report-program line 433 rather than the width of the field")
        void theOperatorSuppliedValueEncodesToSixBytes() {
            final byte[] encoded =
                    ReportPeriod.CUSTOM.getValue().getBytes(StandardCharsets.US_ASCII);

            assertThat(encoded.length)
                    .as("encoded width of the operator-supplied value")
                    .isEqualTo(OPERATOR_SUPPLIED_VALUE_WIDTH);
            assertThat(encoded)
                    .as("bytes of the operator-supplied value, against the hand-typed literal's "
                            + "bytes")
                    .isEqualTo("Custom".getBytes(StandardCharsets.US_ASCII));
        }

        @Test
        @DisplayName("no value is its ten-character field rendering, because both reads consume the "
                + "field delimited by space at lines 449 and 468 and stop at the first blank")
        void noValueIsItsTenCharacterFieldRendering() {
            assertThat(ReportPeriod.MONTHLY.getValue())
                    .as("the current-month value must not carry the field fill")
                    .isNotEqualTo(CURRENT_MONTH_FIELD_RENDERING)
                    .isNotEqualTo("Monthly   ");
            assertThat(ReportPeriod.YEARLY.getValue())
                    .as("the current-year value must not carry the field fill")
                    .isNotEqualTo(CURRENT_YEAR_FIELD_RENDERING)
                    .isNotEqualTo("Yearly    ");
            assertThat(ReportPeriod.CUSTOM.getValue())
                    .as("the operator-supplied value must not carry the field fill")
                    .isNotEqualTo(OPERATOR_SUPPLIED_FIELD_RENDERING)
                    .isNotEqualTo("Custom    ");
        }

        @Test
        @DisplayName("no value encodes to the ten-byte width of the field, which is the byte-level "
                + "form of the same proof that the fill at lines 449 and 468 is never observable")
        void noValueEncodesToTheFieldWidth() {
            for (final ReportPeriod period : ReportPeriod.values()) {
                final byte[] encoded = period.getValue().getBytes(StandardCharsets.US_ASCII);

                assertThat(encoded.length)
                        .as("encoded width of the value carried by %s", period.name())
                        .isNotEqualTo(REPORT_NAME_FIELD_WIDTH)
                        .isLessThan(REPORT_NAME_FIELD_WIDTH);
            }

            assertThat(ReportPeriod.MONTHLY.getValue().getBytes(StandardCharsets.US_ASCII))
                    .as("bytes of the current-month value against the field rendering's bytes")
                    .isNotEqualTo(CURRENT_MONTH_FIELD_RENDERING
                            .getBytes(StandardCharsets.US_ASCII));
            assertThat(ReportPeriod.YEARLY.getValue().getBytes(StandardCharsets.US_ASCII))
                    .as("bytes of the current-year value against the field rendering's bytes")
                    .isNotEqualTo(CURRENT_YEAR_FIELD_RENDERING
                            .getBytes(StandardCharsets.US_ASCII));
            assertThat(ReportPeriod.CUSTOM.getValue().getBytes(StandardCharsets.US_ASCII))
                    .as("bytes of the operator-supplied value against the field rendering's bytes")
                    .isNotEqualTo(OPERATOR_SUPPLIED_FIELD_RENDERING
                            .getBytes(StandardCharsets.US_ASCII));
        }

        @Test
        @DisplayName("the field is ten characters wide at report-program line 58 while its three "
                + "values are seven, six and six bytes wide: the width belongs to the field and the "
                + "value keeps only its own bytes")
        void theFieldIsTenCharactersWideWhileNoValueIs() {
            assertThat(REPORT_NAME_FIELD_WIDTH)
                    .as("declared width of the report-name work field, as layout evidence")
                    .isEqualTo(10)
                    .isGreaterThan(CURRENT_MONTH_VALUE_WIDTH)
                    .isGreaterThan(CURRENT_YEAR_VALUE_WIDTH)
                    .isGreaterThan(OPERATOR_SUPPLIED_VALUE_WIDTH);

            assertThat(ReportPeriod.MONTHLY.getValue()
                    .getBytes(StandardCharsets.US_ASCII).length)
                    .as("current-month value width against the field width")
                    .isEqualTo(CURRENT_MONTH_VALUE_WIDTH)
                    .isLessThan(REPORT_NAME_FIELD_WIDTH);
            assertThat(ReportPeriod.YEARLY.getValue()
                    .getBytes(StandardCharsets.US_ASCII).length)
                    .as("current-year value width against the field width")
                    .isEqualTo(CURRENT_YEAR_VALUE_WIDTH)
                    .isLessThan(REPORT_NAME_FIELD_WIDTH);
            assertThat(ReportPeriod.CUSTOM.getValue()
                    .getBytes(StandardCharsets.US_ASCII).length)
                    .as("operator-supplied value width against the field width")
                    .isEqualTo(OPERATOR_SUPPLIED_VALUE_WIDTH)
                    .isLessThan(REPORT_NAME_FIELD_WIDTH);
        }

        @Test
        @DisplayName("each value plus the blanks the field keeps sums to the ten characters declared "
                + "at report-program line 58, which is where the fill lives instead of in the value")
        void eachValuePlusItsFieldFillSumsToTheDeclaredFieldWidth() {
            assertThat(CURRENT_MONTH_VALUE_WIDTH + CURRENT_MONTH_FILL_WIDTH)
                    .as("current-month value width plus retained blanks")
                    .isEqualTo(REPORT_NAME_FIELD_WIDTH);
            assertThat(CURRENT_YEAR_VALUE_WIDTH + CURRENT_YEAR_FILL_WIDTH)
                    .as("current-year value width plus retained blanks")
                    .isEqualTo(REPORT_NAME_FIELD_WIDTH);
            assertThat(OPERATOR_SUPPLIED_VALUE_WIDTH + OPERATOR_SUPPLIED_FILL_WIDTH)
                    .as("operator-supplied value width plus retained blanks")
                    .isEqualTo(REPORT_NAME_FIELD_WIDTH);

            assertThat(CURRENT_MONTH_FIELD_RENDERING.getBytes(StandardCharsets.US_ASCII).length)
                    .as("width of the current-month field rendering, as a check on the fill count")
                    .isEqualTo(REPORT_NAME_FIELD_WIDTH);
            assertThat(CURRENT_YEAR_FIELD_RENDERING.getBytes(StandardCharsets.US_ASCII).length)
                    .as("width of the current-year field rendering, as a check on the fill count")
                    .isEqualTo(REPORT_NAME_FIELD_WIDTH);
            assertThat(OPERATOR_SUPPLIED_FIELD_RENDERING
                    .getBytes(StandardCharsets.US_ASCII).length)
                    .as("width of the operator-supplied field rendering, as a check on the fill "
                            + "count")
                    .isEqualTo(REPORT_NAME_FIELD_WIDTH);
        }

        @Test
        @DisplayName("no value carries a blank in any position, so nothing in it could ever be "
                + "consumed as fill by a read delimited by space")
        void noValueCarriesABlankInAnyPosition() {
            for (final ReportPeriod period : ReportPeriod.values()) {
                final byte[] encoded = period.getValue().getBytes(StandardCharsets.US_ASCII);

                assertThat(encoded)
                        .as("bytes of the value carried by %s", period.name())
                        .doesNotContain(ASCII_BLANK);
                assertThat(period.getValue())
                        .as("value carried by %s, read as text", period.name())
                        .doesNotContain(" ");
            }
        }
    }

    @Nested
    @DisplayName("Casing of the literals, which is data rather than presentation")
    class LiteralCasing {

        @Test
        @DisplayName("the current-month value keeps the program's own spelling and is neither the "
                + "all-capitals nor the all-lower-case rendering of it")
        void theCurrentMonthValueKeepsItsProgramSpelling() {
            assertThat(ReportPeriod.MONTHLY.getValue())
                    .as("current-month value against the three hand-typed spellings")
                    .isEqualTo(CURRENT_MONTH_VALUE)
                    .isNotEqualTo(CURRENT_MONTH_ALL_CAPITALS)
                    .isNotEqualTo(CURRENT_MONTH_NO_CAPITALS);
        }

        @Test
        @DisplayName("the current-year value keeps the program's own spelling and is neither the "
                + "all-capitals nor the all-lower-case rendering of it")
        void theCurrentYearValueKeepsItsProgramSpelling() {
            assertThat(ReportPeriod.YEARLY.getValue())
                    .as("current-year value against the three hand-typed spellings")
                    .isEqualTo(CURRENT_YEAR_VALUE)
                    .isNotEqualTo(CURRENT_YEAR_ALL_CAPITALS)
                    .isNotEqualTo(CURRENT_YEAR_NO_CAPITALS);
        }

        @Test
        @DisplayName("the operator-supplied value keeps the program's own spelling and is neither "
                + "the all-capitals nor the all-lower-case rendering of it")
        void theOperatorSuppliedValueKeepsItsProgramSpelling() {
            assertThat(ReportPeriod.CUSTOM.getValue())
                    .as("operator-supplied value against the three hand-typed spellings")
                    .isEqualTo(OPERATOR_SUPPLIED_VALUE)
                    .isNotEqualTo(OPERATOR_SUPPLIED_ALL_CAPITALS)
                    .isNotEqualTo(OPERATOR_SUPPLIED_NO_CAPITALS);
        }

        @Test
        @DisplayName("every value has a capital initial letter followed by lower-case letters, which "
                + "is the shape all three literals share and no sibling enumeration in this package "
                + "uses")
        void everyValueHasACapitalInitialAndALowerCaseRemainder() {
            for (final ReportPeriod period : ReportPeriod.values()) {
                final byte[] encoded = period.getValue().getBytes(StandardCharsets.US_ASCII);

                assertThat(Character.isUpperCase((char) encoded[0]))
                        .as("initial byte of the value carried by %s is a capital letter",
                                period.name())
                        .isTrue();

                for (int index = 1; index < encoded.length; index++) {
                    assertThat(Character.isLowerCase((char) encoded[index]))
                            .as("byte %d of the value carried by %s is a lower-case letter",
                                    index, period.name())
                            .isTrue();
                }
            }
        }
    }

    @Nested
    @DisplayName("Resolution of a value read back out of the report-name field")
    class Resolution {

        @Test
        @DisplayName("each of the three bare literals resolves to the period whose branch wrote it")
        void eachBareLiteralResolvesToItsPeriod() {
            assertThat(ReportPeriod.fromValue(CURRENT_MONTH_VALUE))
                    .as("resolution of the current-month literal")
                    .contains(ReportPeriod.MONTHLY);
            assertThat(ReportPeriod.fromValue(CURRENT_YEAR_VALUE))
                    .as("resolution of the current-year literal")
                    .contains(ReportPeriod.YEARLY);
            assertThat(ReportPeriod.fromValue(OPERATOR_SUPPLIED_VALUE))
                    .as("resolution of the operator-supplied literal")
                    .contains(ReportPeriod.CUSTOM);
        }

        @Test
        @DisplayName("a resolved period hands back the same bare literal byte for byte, so the "
                + "lookup neither pads nor shortens what it returns")
        void aResolvedPeriodHandsBackTheSameBareLiteral() {
            final Optional<ReportPeriod> resolved = ReportPeriod.fromValue("Monthly");

            assertThat(resolved)
                    .as("resolution of the hand-typed current-month literal")
                    .contains(ReportPeriod.MONTHLY);
            assertThat(resolved.orElseThrow().getValue().getBytes(StandardCharsets.US_ASCII))
                    .as("bytes recovered through the lookup against the hand-typed literal's bytes")
                    .isEqualTo("Monthly".getBytes(StandardCharsets.US_ASCII))
                    .hasSize(CURRENT_MONTH_VALUE_WIDTH);
            assertThat(resolved.orElseThrow().getValue())
                    .as("a value that had acquired the field fill would equal this rendering, which "
                            + "is exactly the defect this inequality catches")
                    .isNotEqualTo(CURRENT_MONTH_FIELD_RENDERING);
        }

        @Test
        @DisplayName("no ten-character field rendering resolves, because the fill the field keeps is "
                + "consumed by the delimited reads at lines 449 and 468 and is never a key")
        void noFieldRenderingResolves() {
            assertThat(ReportPeriod.fromValue(CURRENT_MONTH_FIELD_RENDERING))
                    .as("resolution of the current-month field rendering")
                    .isEmpty();
            assertThat(ReportPeriod.fromValue(CURRENT_YEAR_FIELD_RENDERING))
                    .as("resolution of the current-year field rendering")
                    .isEmpty();
            assertThat(ReportPeriod.fromValue(OPERATOR_SUPPLIED_FIELD_RENDERING))
                    .as("resolution of the operator-supplied field rendering")
                    .isEmpty();
        }

        @Test
        @DisplayName("nothing is trimmed, so a literal carrying one blank too many, or a blank "
                + "before it instead of after, resolves to nothing")
        void nothingIsTrimmedFromAProbe() {
            assertThat(ReportPeriod.fromValue("Monthly "))
                    .as("current-month literal with a single trailing blank")
                    .isEmpty();
            assertThat(ReportPeriod.fromValue(" Monthly"))
                    .as("current-month literal with a single leading blank")
                    .isEmpty();
            assertThat(ReportPeriod.fromValue("Yearly "))
                    .as("current-year literal with a single trailing blank")
                    .isEmpty();
            assertThat(ReportPeriod.fromValue(" Custom"))
                    .as("operator-supplied literal with a single leading blank")
                    .isEmpty();
        }

        @Test
        @DisplayName("no case folding is applied in either direction, so neither the all-capitals "
                + "nor the all-lower-case rendering of any literal resolves")
        void noCaseFoldingIsAppliedToAProbe() {
            assertThat(ReportPeriod.fromValue(CURRENT_MONTH_ALL_CAPITALS))
                    .as("all-capitals rendering of the current-month literal")
                    .isEmpty();
            assertThat(ReportPeriod.fromValue(CURRENT_MONTH_NO_CAPITALS))
                    .as("all-lower-case rendering of the current-month literal")
                    .isEmpty();
            assertThat(ReportPeriod.fromValue(CURRENT_YEAR_ALL_CAPITALS))
                    .as("all-capitals rendering of the current-year literal")
                    .isEmpty();
            assertThat(ReportPeriod.fromValue(CURRENT_YEAR_NO_CAPITALS))
                    .as("all-lower-case rendering of the current-year literal")
                    .isEmpty();
            assertThat(ReportPeriod.fromValue(OPERATOR_SUPPLIED_ALL_CAPITALS))
                    .as("all-capitals rendering of the operator-supplied literal")
                    .isEmpty();
            assertThat(ReportPeriod.fromValue(OPERATOR_SUPPLIED_NO_CAPITALS))
                    .as("all-lower-case rendering of the operator-supplied literal")
                    .isEmpty();
        }

        @ParameterizedTest(name = "[{index}] a report-name value of \"{0}\" resolves to nothing")
        @NullSource
        @EmptySource
        @ValueSource(strings = {
            "Weekly",
            "Daily",
            "Quarterly",
            "Annual",
            "Fortnightly",
            "Monthl",
            "Monthlyy",
            "Yearl",
            "Custo",
            "          "
        })
        @DisplayName("a value outside the three-literal vocabulary resolves to nothing, because the "
                + "program writes the field from three branches and no fourth period exists")
        void aValueOutsideTheVocabularyResolvesToNothing(final String reportNameValue) {
            assertThat(ReportPeriod.fromValue(reportNameValue))
                    .as("resolution of a value the report program never stores")
                    .isEmpty();
        }

        @Test
        @DisplayName("resolution reports an unrecognised value as absent instead of throwing, so an "
                + "unmapped value stays the caller's decision rather than an aborted call")
        void resolutionReportsRatherThanThrows() {
            assertThatCode(() -> ReportPeriod.fromValue(null))
                    .as("resolution of an absent value")
                    .doesNotThrowAnyException();
            assertThatCode(() -> ReportPeriod.fromValue(""))
                    .as("resolution of an empty value")
                    .doesNotThrowAnyException();
            assertThatCode(() -> ReportPeriod.fromValue(CURRENT_MONTH_FIELD_RENDERING))
                    .as("resolution of a ten-character field rendering")
                    .doesNotThrowAnyException();
            assertThatCode(() -> ReportPeriod.fromValue("Weekly"))
                    .as("resolution of a period the program does not offer")
                    .doesNotThrowAnyException();

            assertThat(ReportPeriod.fromValue(null))
                    .as("an absent value is reported as absent rather than as a period")
                    .isEmpty();
        }
    }

    /**
     * Behaviour that follows from what this enumeration deliberately does not model. The catch-all
     * branch at report-program line 437 stores nothing into the report-name field, so it contributes
     * no constant; and the date pair, the submission confirmation gate and the error flag are the
     * report-request service's state rather than this type's. Both facts are proved through the
     * published surface - the size of the constant set and an unrecognised value resolving to
     * nothing - and by this class never referencing a date, a gate or a flag at all.
     */
    @Nested
    @DisplayName("Deliberately not modelled")
    class DeliberatelyNotModelled {

        @Test
        @DisplayName("no synthetic fallback constant absorbs the catch-all branch at line 437, "
                + "because selecting no report type is an input-validation outcome and not a period")
        void noSyntheticFallbackConstantExists() {
            assertThat(ReportPeriod.values())
                    .as("the whole constant set, which a fallback constant would enlarge")
                    .hasSize(EXPECTED_PERIOD_COUNT);

            for (final String spelling : SYNTHETIC_FALLBACK_SPELLINGS) {
                assertThat(ReportPeriod.fromValue(spelling))
                        .as("resolution of the fallback spelling %s", spelling)
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("there is no fourth period: the constant set is closed at the three branches "
                + "that write the field, and every member carries one of the three literals")
        void thereIsNoFourthPeriod() {
            assertThat(ReportPeriod.values())
                    .as("constant set closed at the number of writing branches")
                    .hasSize(EXPECTED_PERIOD_COUNT);
            assertThat(LEGACY_LITERALS)
                    .as("hand-typed literal set, the same size as the constant set")
                    .hasSize(EXPECTED_PERIOD_COUNT);

            for (final ReportPeriod period : ReportPeriod.values()) {
                assertThat(period.getValue())
                        .as("value carried by %s is one of the three literals", period.name())
                        .isIn(LEGACY_LITERALS);
            }
        }

        @Test
        @DisplayName("no constant carries date-shaped or range-shaped content, so the date pair the "
                + "operator supplies for the third selection is modelled elsewhere and not here")
        void noConstantCarriesDateShapedContent() {
            for (final ReportPeriod period : ReportPeriod.values()) {
                final byte[] encoded = period.getValue().getBytes(StandardCharsets.US_ASCII);

                assertThat(period.getValue())
                        .as("value carried by %s carries no date separator", period.name())
                        .doesNotContain("-")
                        .doesNotContain("/");

                for (int index = 0; index < encoded.length; index++) {
                    assertThat(Character.isDigit((char) encoded[index]))
                            .as("byte %d of the value carried by %s is not a digit",
                                    index, period.name())
                            .isFalse();
                }
            }
        }

        @Test
        @DisplayName("no constant carries a one-character selection or flag value, so neither the "
                + "screen selection flags nor the error flag is modelled by this type")
        void noConstantCarriesAFlagWidthValue() {
            for (final ReportPeriod period : ReportPeriod.values()) {
                assertThat(period.getValue().getBytes(StandardCharsets.US_ASCII).length)
                        .as("encoded width of the value carried by %s", period.name())
                        .isNotEqualTo(SCREEN_SELECTION_FLAG_WIDTH)
                        .isGreaterThan(SCREEN_SELECTION_FLAG_WIDTH);
            }
        }
    }
}
