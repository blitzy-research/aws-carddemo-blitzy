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
package com.carddemo.api.dto;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import com.carddemo.domain.enums.KeyAction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link ScreenWorkArea}, the replacement for the per-screen scratch area that five of the online
 * programs shared.
 *
 * <p><strong>What it replaces.</strong> {@code app/cpy/CVCRD01Y.cpy} declares a work area holding the
 * attention identifier the terminal last raised, the next program, mapset and map to display, an error
 * message, a return message, and three identifier fields. Only the five-program family that included this
 * copybook used it, which is why the three identifier fields appear here and not on the wider
 * communication area.
 *
 * <p><strong>The three redefinitions are the interesting part.</strong> Each identifier is declared twice
 * in the copybook: once as a blank-initialised character field and once, over the same storage, as an
 * unsigned numeric field. The character view is what a screen sends and what a blank field contains; the
 * numeric view is only meaningful when every byte happens to be a digit. Reading the numeric view of a
 * blank or partially alphabetic field on the mainframe yields whatever the bytes happen to mean rather than
 * a number, so the Java replacement reports absence instead. This suite pins that: a value is numeric only
 * when every single character is a digit, and everything else — blanks, embedded blanks, signs, decimal
 * points, and any letter — reports absence rather than guessing.
 *
 * <p><strong>Why absence rather than zero.</strong> Zero is a legitimate identifier value in an unsigned
 * digit field, so a non-numeric field that reported zero would be indistinguishable from a real zero. The
 * suite asserts an all-zero field reports zero and a blank field reports absence, which are two different
 * answers.
 */
@DisplayName("ScreenWorkArea — the shared per-screen work area")
class ScreenWorkAreaTest {

    /** The nine copybook widths in declaration order. */
    private static final List<Integer> COPYBOOK_WIDTHS = List.of(5, 8, 7, 7, 75, 75, 11, 16, 9);

    /** Summed width of the whole work area. */
    private static final int WORK_AREA_WIDTH = 213;

    /**
     * Builds a work area whose three identifiers all carry the supplied text, so one assertion can cover
     * all three redefinitions at once.
     *
     * @param identifierText the text to place in all three identifier components
     * @return a populated work area
     */
    private static ScreenWorkArea withIdentifiers(final String identifierText) {
        return new ScreenWorkArea(
                KeyAction.ENTER,
                "COACTUPC",
                "COACTUP",
                "CACTUPA",
                "Account not found",
                "Update successful",
                identifierText,
                identifierText,
                identifierText);
    }

    /**
     * Builds a work area carrying the supplied attention key and nothing else.
     *
     * @param keyAction the attention key, possibly {@code null}
     * @return a work area whose only populated component is the attention key
     */
    private static ScreenWorkArea withKey(final KeyAction keyAction) {
        return new ScreenWorkArea(keyAction, null, null, null, null, null, null, null, null);
    }

    // COPYBOOK GEOMETRY

    /**
     * Verifies the declared widths against the work area they reproduce.
     */
    @Nested
    @DisplayName("copybook geometry")
    class CopybookGeometry {

        @Test
        @DisplayName("the nine declared widths sum to the work area's own width")
        void theWidthsSumToTheWorkAreaWidth() {
            assertThat(COPYBOOK_WIDTHS).hasSize(9);
            assertThat(COPYBOOK_WIDTHS.stream().mapToInt(Integer::intValue).sum())
                    .isEqualTo(WORK_AREA_WIDTH);
        }

        @Test
        @DisplayName("every published width constant matches its copybook field")
        void everyPublishedWidthMatchesItsCopybookField() {
            assertThat(ScreenWorkArea.ATTENTION_ID_LENGTH).isEqualTo(COPYBOOK_WIDTHS.get(0));
            assertThat(ScreenWorkArea.NEXT_PROGRAM_LENGTH).isEqualTo(COPYBOOK_WIDTHS.get(1));
            assertThat(ScreenWorkArea.NEXT_MAPSET_LENGTH).isEqualTo(COPYBOOK_WIDTHS.get(2));
            assertThat(ScreenWorkArea.NEXT_MAP_LENGTH).isEqualTo(COPYBOOK_WIDTHS.get(3));
            assertThat(ScreenWorkArea.ERROR_MESSAGE_LENGTH).isEqualTo(COPYBOOK_WIDTHS.get(4));
            assertThat(ScreenWorkArea.RETURN_MESSAGE_LENGTH).isEqualTo(COPYBOOK_WIDTHS.get(5));
            assertThat(ScreenWorkArea.ACCOUNT_ID_LENGTH).isEqualTo(COPYBOOK_WIDTHS.get(6));
            assertThat(ScreenWorkArea.CARD_NUMBER_LENGTH).isEqualTo(COPYBOOK_WIDTHS.get(7));
            assertThat(ScreenWorkArea.CUSTOMER_ID_LENGTH).isEqualTo(COPYBOOK_WIDTHS.get(8));
        }

        @Test
        @DisplayName("the two messages share one width, so neither can be told from the other by length")
        void theTwoMessagesShareOneWidth() {
            assertThat(ScreenWorkArea.ERROR_MESSAGE_LENGTH)
                    .isEqualTo(ScreenWorkArea.RETURN_MESSAGE_LENGTH)
                    .isEqualTo(75);
        }

        @Test
        @DisplayName("a mapset name and a map name are both seven characters, one shorter than a program "
                + "name")
        void theMapNamesAreSevenAndTheProgramNameIsEight() {
            assertThat(ScreenWorkArea.NEXT_MAPSET_LENGTH).isEqualTo(ScreenWorkArea.NEXT_MAP_LENGTH);
            assertThat(ScreenWorkArea.NEXT_PROGRAM_LENGTH)
                    .isEqualTo(ScreenWorkArea.NEXT_MAP_LENGTH + 1);
        }

        @Test
        @DisplayName("the three identifier widths are all different, so the three redefinitions cannot be "
                + "interchanged")
        void theThreeIdentifierWidthsAreAllDifferent() {
            assertThat(List.of(
                    ScreenWorkArea.ACCOUNT_ID_LENGTH,
                    ScreenWorkArea.CARD_NUMBER_LENGTH,
                    ScreenWorkArea.CUSTOMER_ID_LENGTH))
                    .doesNotHaveDuplicates()
                    .containsExactly(11, 16, 9);
        }
    }

    // THE ATTENTION KEY

    /**
     * Verifies the attention identifier the terminal last raised.
     */
    @Nested
    @DisplayName("the attention key")
    class AttentionKey {

        @Test
        @DisplayName("a present key is reported together with its five-character identifier")
        void aPresentKeyIsReportedWithItsIdentifier() {
            final ScreenWorkArea area = withKey(KeyAction.PFK03);

            assertThat(area.attentionKey()).contains(KeyAction.PFK03);
            assertThat(area.attentionIdText()).contains(KeyAction.PFK03.getAid());
        }

        @Test
        @DisplayName("an absent key reports absence for both the key and its identifier, rather than a "
                + "blank identifier")
        void anAbsentKeyReportsAbsenceForBoth() {
            final ScreenWorkArea area = withKey(null);

            assertThat(area.attentionKey()).isEmpty();
            assertThat(area.attentionIdText()).isEmpty();
        }

        @Test
        @DisplayName("every declared key reports an identifier of exactly the copybook width")
        void everyDeclaredKeyReportsAnIdentifierOfTheCopybookWidth() {
            for (final KeyAction key : KeyAction.values()) {
                assertThat(withKey(key).attentionIdText()).as("key %s", key).isPresent();
                assertThat(withKey(key).attentionIdText().orElseThrow())
                        .as("key %s", key)
                        .hasSize(ScreenWorkArea.ATTENTION_ID_LENGTH);
            }
        }

        @Test
        @DisplayName("the reported identifier round-trips back to the same key for every declared key")
        void theReportedIdentifierRoundTripsBackToTheSameKey() {
            for (final KeyAction key : KeyAction.values()) {
                final String identifier = withKey(key).attentionIdText().orElseThrow();

                assertThat(KeyAction.fromAid(identifier)).as("key %s", key).contains(key);
            }
        }

        @Test
        @DisplayName("the reported identifiers are all distinct, so the raised key is recoverable from the "
                + "text alone")
        void theReportedIdentifiersAreAllDistinct() {
            final List<String> identifiers = new ArrayList<>();
            for (final KeyAction key : KeyAction.values()) {
                identifiers.add(withKey(key).attentionIdText().orElseThrow());
            }

            assertThat(identifiers)
                    .hasSize(KeyAction.values().length)
                    .doesNotHaveDuplicates();
        }
    }

    // THE NUMERIC REDEFINITIONS

    /**
     * Verifies the three numeric views over the three character identifier fields.
     */
    @Nested
    @DisplayName("the numeric redefinitions")
    class NumericRedefinitions {

        @Test
        @DisplayName("an all-digit value is reported as its own magnitude by all three views")
        void anAllDigitValueIsReportedAsItsMagnitude() {
            final ScreenWorkArea area = withIdentifiers("123456789");

            assertThat(area.accountIdNumeric()).contains(new BigInteger("123456789"));
            assertThat(area.cardNumberNumeric()).contains(new BigInteger("123456789"));
            assertThat(area.customerIdNumeric()).contains(new BigInteger("123456789"));
        }

        @Test
        @DisplayName("each view reads only its own component")
        void eachViewReadsOnlyItsOwnComponent() {
            final ScreenWorkArea area = new ScreenWorkArea(
                    KeyAction.ENTER, null, null, null, null, null,
                    "00000000011", "4111111111111111", "000000021");

            assertThat(area.accountIdNumeric()).contains(BigInteger.valueOf(11L));
            assertThat(area.cardNumberNumeric()).contains(new BigInteger("4111111111111111"));
            assertThat(area.customerIdNumeric()).contains(BigInteger.valueOf(21L));
        }

        @Test
        @DisplayName("leading zeros are absorbed by the magnitude but never change it")
        void leadingZerosAreAbsorbedButNeverChangeTheMagnitude() {
            for (final String padded : List.of("11", "011", "0011", "00000000011")) {
                assertThat(withIdentifiers(padded).accountIdNumeric())
                        .as("value %s", padded)
                        .contains(BigInteger.valueOf(11L));
            }
        }

        @Test
        @DisplayName("an all-zero value reports zero, which a blank value must not")
        void anAllZeroValueReportsZero() {
            assertThat(withIdentifiers("00000000000").accountIdNumeric())
                    .contains(BigInteger.ZERO);
            assertThat(withIdentifiers("           ").accountIdNumeric()).isEmpty();
        }

        @Test
        @DisplayName("a blank-initialised field reports absence at every width the copybook declares")
        void aBlankInitialisedFieldReportsAbsence() {
            final ScreenWorkArea area = new ScreenWorkArea(
                    KeyAction.ENTER, null, null, null, null, null,
                    " ".repeat(ScreenWorkArea.ACCOUNT_ID_LENGTH),
                    " ".repeat(ScreenWorkArea.CARD_NUMBER_LENGTH),
                    " ".repeat(ScreenWorkArea.CUSTOMER_ID_LENGTH));

            assertThat(area.accountIdNumeric()).isEmpty();
            assertThat(area.cardNumberNumeric()).isEmpty();
            assertThat(area.customerIdNumeric()).isEmpty();
        }

        @Test
        @DisplayName("an absent component reports absence")
        void anAbsentComponentReportsAbsence() {
            final ScreenWorkArea area = withIdentifiers(null);

            assertThat(area.accountIdNumeric()).isEmpty();
            assertThat(area.cardNumberNumeric()).isEmpty();
            assertThat(area.customerIdNumeric()).isEmpty();
        }

        @Test
        @DisplayName("an empty component reports absence")
        void anEmptyComponentReportsAbsence() {
            final ScreenWorkArea area = withIdentifiers("");

            assertThat(area.accountIdNumeric()).isEmpty();
            assertThat(area.cardNumberNumeric()).isEmpty();
            assertThat(area.customerIdNumeric()).isEmpty();
        }

        @Test
        @DisplayName("a single non-digit anywhere in the value defeats the numeric view, wherever it sits")
        void aSingleNonDigitAnywhereDefeatsTheNumericView() {
            for (final String value : List.of("X23456789", "1234X6789", "12345678X")) {
                assertThat(withIdentifiers(value).accountIdNumeric())
                        .as("value %s", value)
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("an embedded blank defeats the numeric view even when every other byte is a digit")
        void anEmbeddedBlankDefeatsTheNumericView() {
            assertThat(withIdentifiers("1234 6789").accountIdNumeric()).isEmpty();
            assertThat(withIdentifiers("     6789").accountIdNumeric()).isEmpty();
            assertThat(withIdentifiers("1234     ").accountIdNumeric()).isEmpty();
        }

        @Test
        @DisplayName("a sign or a decimal point defeats the numeric view, because the copybook field is "
                + "unsigned and has no decimal places")
        void aSignOrDecimalPointDefeatsTheNumericView() {
            for (final String value : List.of("-123456789", "+123456789", "1234.6789", "1234,6789")) {
                assertThat(withIdentifiers(value).accountIdNumeric())
                        .as("value %s", value)
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("a value at the widest declared width is reported exactly, with no loss")
        void aValueAtTheWidestWidthIsReportedExactly() {
            final String widest = "9".repeat(ScreenWorkArea.CARD_NUMBER_LENGTH);

            assertThat(withIdentifiers(widest).cardNumberNumeric())
                    .contains(new BigInteger(widest));
            assertThat(withIdentifiers(widest).cardNumberNumeric().orElseThrow().toString())
                    .hasSize(ScreenWorkArea.CARD_NUMBER_LENGTH);
        }

        @Test
        @DisplayName("a non-ASCII digit is not accepted, so only the characters the copybook permits count")
        void aNonAsciiDigitIsNotAccepted() {
            assertThat(withIdentifiers("\u0661\u0662\u0663\u0664\u0665\u0666\u0667\u0668\u0669").accountIdNumeric()).isEmpty();
        }
    }

    // VALUE SEMANTICS

    /**
     * Verifies that the work area behaves as a value.
     */
    @Nested
    @DisplayName("value semantics")
    class ValueSemantics {

        @Test
        @DisplayName("two work areas with the same components are equal")
        void twoWorkAreasWithTheSameComponentsAreEqual() {
            assertThat(withIdentifiers("123456789"))
                    .isEqualTo(withIdentifiers("123456789"))
                    .hasSameHashCodeAs(withIdentifiers("123456789"));
        }

        @Test
        @DisplayName("a difference in the raised key alone makes two work areas unequal")
        void aDifferenceInTheRaisedKeyMakesWorkAreasUnequal() {
            assertThat(withKey(KeyAction.ENTER)).isNotEqualTo(withKey(KeyAction.PFK03));
            assertThat(withKey(KeyAction.ENTER)).isNotEqualTo(withKey(null));
        }

        @Test
        @DisplayName("a work area holding the same identifier text is equal even though the three views "
                + "report three different components")
        void identicalTextIsEqualAcrossTheThreeComponents() {
            final ScreenWorkArea first = withIdentifiers("000000011");
            final ScreenWorkArea second = withIdentifiers("000000011");

            assertThat(first).isEqualTo(second);
            assertThat(first.accountIdNumeric()).isEqualTo(second.customerIdNumeric());
        }

        @Test
        @DisplayName("the rendered form names the record and carries the routing components, withholding the "
                + "three identifiers and the two message slots")
        void theRenderedFormNamesTheRecordAndItsComponents() {
            assertThat(withIdentifiers("123456789").toString())
                    .startsWith("ScreenWorkArea[")
                    .endsWith("]")
                    .contains("COACTUPC")
                    .contains("CACTUPA")
                    .doesNotContain("Account not found")
                    .doesNotContain("Update successful")
                    .doesNotContain("123456789");
        }
    }
}
