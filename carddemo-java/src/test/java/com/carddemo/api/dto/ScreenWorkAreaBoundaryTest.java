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

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.carddemo.domain.enums.KeyAction;

/**
 * Exercises the screen work area shared by the five-program account and card family.
 *
 * <h2>What is under test</h2>
 * {@link ScreenWorkArea} stands in for {@code app/cpy/CVCRD01Y.cpy}, the {@code CC-WORK-AREAS}
 * structure that five online programs include and no other program does. It carries the attention
 * identifier the terminal sent, the next program, mapset and map the transaction intends to route to,
 * the two message lines, and three keys that the copybook declares twice: once as a character field
 * and once, by redefinition, as a numeric field over the same bytes.
 *
 * <h2>Why the numeric views are asserted to refuse a non-numeric value</h2>
 * A COBOL {@code REDEFINES} does not convert. It reinterprets the same bytes under a second
 * description, and a numeric description over bytes that are not digits is undefined behaviour that a
 * legacy program guarded against before reading. This record therefore exposes each numeric view as
 * an optional that is present only when every byte really is a digit, and the tests assert the
 * refusal cases — a partially filled key, an embedded space, a sign, a decimal point — because a
 * translation that parsed leniently would accept keys the legacy program rejected before it ever
 * looked at the file.
 *
 * <h2>Why the attention identifier is typed rather than carried as text</h2>
 * The copybook declares the identifier as a five-byte field and then declares sixteen condition names
 * over it. Carrying the text alone would let a caller submit a value that matches none of them; the
 * typed form makes that impossible while still exposing the underlying five-byte text, because the
 * text is what a fixed-width record stores. Both are asserted: the type for validity, the text for
 * width.
 *
 * <p>No legacy source text is reproduced.</p>
 */
@DisplayName("ScreenWorkArea - the CVCRD01Y CC-WORK-AREAS structure")
class ScreenWorkAreaBoundaryTest {

    /** Eleven-digit account key, at the legacy field's full width. */
    private static final String ACCOUNT_ID = "00000000011";

    /** Sixteen-digit card key, at the legacy field's full width. */
    private static final String CARD_NUMBER = "4444333322221111";

    /** Nine-digit customer key, at the legacy field's full width. */
    private static final String CUSTOMER_ID = "000000011";

    /**
     * Returns a work area carrying every field populated with a well-formed value.
     *
     * @param keyAction the attention identifier the terminal sent, or {@code null} for none
     * @return a fully populated work area
     */
    private static ScreenWorkArea referenceArea(KeyAction keyAction) {
        return new ScreenWorkArea(
                keyAction,
                "COACTUPC",
                "COACTUP",
                "CACTUPA",
                "Account Status must be supplied",
                "Thank you for using CardDemo application...",
                ACCOUNT_ID,
                CARD_NUMBER,
                CUSTOMER_ID);
    }

    @Nested
    @DisplayName("attention identifier")
    class AttentionIdentifier {

        @ParameterizedTest
        @EnumSource(KeyAction.class)
        @DisplayName("a supplied action is reported as present and as itself")
        void aSuppliedActionIsReportedAsItself(KeyAction keyAction) {
            assertThat(referenceArea(keyAction).attentionKey()).contains(keyAction);
        }

        @ParameterizedTest
        @EnumSource(KeyAction.class)
        @DisplayName("the identifier text is the action's own five-byte field value")
        void theIdentifierTextIsTheActionsFieldValue(KeyAction keyAction) {
            assertThat(referenceArea(keyAction).attentionIdText())
                    .contains(keyAction.getAid());
        }

        @ParameterizedTest
        @EnumSource(KeyAction.class)
        @DisplayName("the identifier text fills the five-byte legacy field exactly")
        void theIdentifierTextFillsTheField(KeyAction keyAction) {
            assertThat(referenceArea(keyAction).attentionIdText())
                    .get()
                    .asString()
                    .hasSize(ScreenWorkArea.ATTENTION_ID_LENGTH);
        }

        @Test
        @DisplayName("no action means no key and no text, not a blank key")
        void noActionMeansNoKeyAndNoText() {
            ScreenWorkArea area = referenceArea(null);

            assertThat(area.attentionKey()).isEmpty();
            assertThat(area.attentionIdText()).isEmpty();
        }
    }

    @Nested
    @DisplayName("numeric redefinitions of the three keys")
    class NumericRedefinitions {

        @Test
        @DisplayName("a fully numeric account key is readable through its numeric redefinition")
        void aNumericAccountKeyIsReadable() {
            assertThat(referenceArea(KeyAction.ENTER).accountIdNumeric())
                    .contains(new BigInteger(ACCOUNT_ID));
        }

        @Test
        @DisplayName("a fully numeric card key is readable through its numeric redefinition")
        void aNumericCardKeyIsReadable() {
            assertThat(referenceArea(KeyAction.ENTER).cardNumberNumeric())
                    .contains(new BigInteger(CARD_NUMBER));
        }

        @Test
        @DisplayName("a fully numeric customer key is readable through its numeric redefinition")
        void aNumericCustomerKeyIsReadable() {
            assertThat(referenceArea(KeyAction.ENTER).customerIdNumeric())
                    .contains(new BigInteger(CUSTOMER_ID));
        }

        @Test
        @DisplayName("a leading-zero key keeps its value, because the digits are what matter")
        void aLeadingZeroKeyKeepsItsValue() {
            ScreenWorkArea area = new ScreenWorkArea(
                    KeyAction.ENTER, null, null, null, null, null,
                    "00000000000", null, null);

            assertThat(area.accountIdNumeric()).contains(BigInteger.ZERO);
        }

        @Test
        @DisplayName("a single digit is a valid numeric view, short of the declared width")
        void aSingleDigitIsAValidNumericView() {
            ScreenWorkArea area = new ScreenWorkArea(
                    KeyAction.ENTER, null, null, null, null, null, "7", null, null);

            assertThat(area.accountIdNumeric()).contains(BigInteger.valueOf(7L));
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "0000000001A", "  000000011", "000000011  ", "-0000000011",
            "0000000.011", "0000+000011", "abcdefghijk", "0000000 011"
        })
        @DisplayName("a key that is not wholly digits has no numeric view")
        void aNonNumericKeyHasNoNumericView(String malformed) {
            ScreenWorkArea area = new ScreenWorkArea(
                    KeyAction.ENTER, null, null, null, null, null, malformed, null, null);

            assertThat(area.accountIdNumeric()).isEmpty();
        }

        @Test
        @DisplayName("an absent key has no numeric view")
        void anAbsentKeyHasNoNumericView() {
            ScreenWorkArea area = new ScreenWorkArea(
                    KeyAction.ENTER, null, null, null, null, null, null, null, null);

            assertThat(area.accountIdNumeric()).isEmpty();
            assertThat(area.cardNumberNumeric()).isEmpty();
            assertThat(area.customerIdNumeric()).isEmpty();
        }

        @Test
        @DisplayName("an empty key has no numeric view, distinct from a zero-filled one")
        void anEmptyKeyHasNoNumericView() {
            ScreenWorkArea area = new ScreenWorkArea(
                    KeyAction.ENTER, null, null, null, null, null, "", "", "");

            assertThat(area.accountIdNumeric()).isEmpty();
            assertThat(area.cardNumberNumeric()).isEmpty();
            assertThat(area.customerIdNumeric()).isEmpty();
        }

        @Test
        @DisplayName("each numeric view reads only its own key")
        void eachNumericViewReadsOnlyItsOwnKey() {
            ScreenWorkArea area = new ScreenWorkArea(
                    KeyAction.ENTER, null, null, null, null, null,
                    ACCOUNT_ID, "NOT-A-CARD-NUMBR", CUSTOMER_ID);

            assertThat(area.accountIdNumeric()).isPresent();
            assertThat(area.cardNumberNumeric()).isEmpty();
            assertThat(area.customerIdNumeric()).isPresent();
        }
    }

    @Nested
    @DisplayName("carried fields and declared widths")
    class CarriedFields {

        @Test
        @DisplayName("every carried field is returned exactly as it was supplied")
        void everyCarriedFieldIsReturnedAsSupplied() {
            ScreenWorkArea area = referenceArea(KeyAction.PFK03);

            assertThat(area.nextProgram()).isEqualTo("COACTUPC");
            assertThat(area.nextMapset()).isEqualTo("COACTUP");
            assertThat(area.nextMap()).isEqualTo("CACTUPA");
            assertThat(area.errorMessage()).isEqualTo("Account Status must be supplied");
            assertThat(area.returnMessage())
                    .isEqualTo("Thank you for using CardDemo application...");
            assertThat(area.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(area.cardNumber()).isEqualTo(CARD_NUMBER);
            assertThat(area.customerId()).isEqualTo(CUSTOMER_ID);
        }

        @Test
        @DisplayName("the declared widths match the legacy copybook picture clauses")
        void theDeclaredWidthsMatchTheCopybook() {
            assertThat(ScreenWorkArea.ATTENTION_ID_LENGTH).isEqualTo(5);
            assertThat(ScreenWorkArea.NEXT_PROGRAM_LENGTH).isEqualTo(8);
            assertThat(ScreenWorkArea.NEXT_MAPSET_LENGTH).isEqualTo(7);
            assertThat(ScreenWorkArea.NEXT_MAP_LENGTH).isEqualTo(7);
            assertThat(ScreenWorkArea.ERROR_MESSAGE_LENGTH).isEqualTo(75);
            assertThat(ScreenWorkArea.RETURN_MESSAGE_LENGTH).isEqualTo(75);
            assertThat(ScreenWorkArea.ACCOUNT_ID_LENGTH).isEqualTo(11);
            assertThat(ScreenWorkArea.CARD_NUMBER_LENGTH).isEqualTo(16);
            assertThat(ScreenWorkArea.CUSTOMER_ID_LENGTH).isEqualTo(9);
        }

        @Test
        @DisplayName("the reference keys are supplied at the widths the copybook declares")
        void theReferenceKeysAreAtTheDeclaredWidths() {
            assertThat(ACCOUNT_ID).hasSize(ScreenWorkArea.ACCOUNT_ID_LENGTH);
            assertThat(CARD_NUMBER).hasSize(ScreenWorkArea.CARD_NUMBER_LENGTH);
            assertThat(CUSTOMER_ID).hasSize(ScreenWorkArea.CUSTOMER_ID_LENGTH);
        }

        @Test
        @DisplayName("two work areas carrying the same fields are equal")
        void twoWorkAreasCarryingTheSameFieldsAreEqual() {
            assertThat(referenceArea(KeyAction.ENTER))
                    .isEqualTo(referenceArea(KeyAction.ENTER))
                    .hasSameHashCodeAs(referenceArea(KeyAction.ENTER));
        }

        @Test
        @DisplayName("two work areas differing only in the attention key are not equal")
        void twoWorkAreasDifferingInTheKeyAreNotEqual() {
            assertThat(referenceArea(KeyAction.ENTER))
                    .isNotEqualTo(referenceArea(KeyAction.PFK03));
        }
    }
}
