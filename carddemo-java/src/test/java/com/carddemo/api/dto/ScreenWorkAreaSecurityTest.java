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

import com.carddemo.domain.enums.KeyAction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link ScreenWorkArea}, the Java realisation of the {@code CC-WORK-AREAS} structure
 * declared by copybook member {@code CVCRD01Y} and included by the five-program card-and-account family.
 *
 * <h2>The numeric views are redefinitions, and that word carries a specific obligation</h2>
 *
 * <p>The legacy structure declares three unsigned numeric aliases over the same storage as the three
 * textual identifiers. A redefinition is not a second field: both views describe one storage area, so
 * they cannot disagree. The Java type honours that by deriving each numeric view from its text on every
 * call rather than storing it, and the assertions below pin the consequence - the text stays
 * authoritative and keeps its leading zeros, while the numeric view reflects whatever the text currently
 * is.</p>
 *
 * <p>The subtler obligation is what happens when the bytes are <em>not</em> a number. All three
 * identifiers are initialised to spaces in the legacy structure, so blank is an ordinary state rather
 * than a parse failure, and a partially filled or left-justified field likewise does not hold a valid
 * unsigned number. Every such case is reported as absent, never as a thrown exception and never as a
 * silently trimmed value - which is why the digit test is an explicit ASCII range check rather than the
 * library predicate, since the library predicate accepts digit code points a single-byte fixed-width
 * field cannot hold.</p>
 *
 * <h2>Redaction, and why a partial mask was refused</h2>
 *
 * <p>Three of the nine components are business keys carried on every turn of five screens, one of them a
 * primary account number. The rendering replaces each with a fixed placeholder rather than a truncation
 * or a digest, because a truncated primary account number is still cardholder data and a digest of a
 * nine-character numeric key is trivially reversible by enumeration. The assertions prove the
 * placeholder is constant across differing values, so nothing about a redacted key - not even its length
 * - survives.</p>
 *
 * <p>The two message slots are withheld on the same terms, and the reason is in the source rather than in
 * a preference. {@code CCARD-ERROR-MSG} is loaded from {@code WS-RETURN-MSG}, which
 * {@code app/cbl/COACTUPC.cbl} assembles with 32 {@code STRING} statements: three concatenate an account
 * or customer identifier into the text with the raw CICS response codes, and three more move in a
 * file-error message naming the internal VSAM resource. Nothing distinguishes those from the cases where
 * the slot holds a plain sentence, and this boundary type additionally accepts whatever a client echoes
 * back into either slot. The assertions below plant exactly the text the legacy builds and prove none of
 * it reaches a rendering, while the accessors still answer byte for byte because the operator-facing
 * message is part of the screen contract. Decision log entry DL-082 records the arrangement.</p>
 */
@DisplayName("ScreenWorkArea - the CVCRD01Y screen work area")
class ScreenWorkAreaSecurityTest {

    private static final String NEXT_PROGRAM = "COACTUPC";
    private static final String NEXT_MAPSET = "CACTUPS";
    private static final String NEXT_MAP = "CACTUPA";
    private static final String ERROR_MESSAGE = "Account Status must be Y or N";
    private static final String RETURN_MESSAGE = "Update successful";
    private static final String ACCOUNT_ID = "00000000011";
    private static final String CARD_NUMBER = "4111111111111111";
    private static final String CUSTOMER_ID = "000000011";

    /** A fully populated work area at the widths the copybook declares. */
    private static ScreenWorkArea workArea() {
        return new ScreenWorkArea(KeyAction.PFK03, NEXT_PROGRAM, NEXT_MAPSET, NEXT_MAP,
                ERROR_MESSAGE, RETURN_MESSAGE, ACCOUNT_ID, CARD_NUMBER, CUSTOMER_ID);
    }

    /** A work area with every component absent, the state the legacy structure starts in. */
    private static ScreenWorkArea blank() {
        return new ScreenWorkArea(null, null, null, null, null, null, null, null, null);
    }

    @Nested
    @DisplayName("The declared widths, taken straight from the copybook")
    class DeclaredWidths {

        @Test
        @DisplayName("the nine published widths match the copybook: 5, 8, 7, 7, 75, 75, 11, 16 and 9")
        void theNinePublishedWidthsMatchTheCopybook() {
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
        @DisplayName("the fixtures sit at the declared widths for the three business keys")
        void theFixturesSitAtTheDeclaredWidths() {
            assertThat(ACCOUNT_ID).hasSize(ScreenWorkArea.ACCOUNT_ID_LENGTH);
            assertThat(CARD_NUMBER).hasSize(ScreenWorkArea.CARD_NUMBER_LENGTH);
            assertThat(CUSTOMER_ID).hasSize(ScreenWorkArea.CUSTOMER_ID_LENGTH);
        }

        @Test
        @DisplayName("the two message widths are equal, which is why one length is not reused for the other")
        void theTwoMessageWidthsAreEqual() {
            assertThat(ScreenWorkArea.ERROR_MESSAGE_LENGTH)
                    .isEqualTo(ScreenWorkArea.RETURN_MESSAGE_LENGTH);
        }
    }

    @Nested
    @DisplayName("Construction, with no compact constructor and therefore no normalisation")
    class Construction {

        @Test
        @DisplayName("all nine components land on their own accessors, so none of the seven same-typed string "
                + "arguments is transposed")
        void allNineComponentsLandOnTheirOwnAccessors() {
            final ScreenWorkArea subject = workArea();
            assertThat(subject.keyAction()).isEqualTo(KeyAction.PFK03);
            assertThat(subject.nextProgram()).isEqualTo(NEXT_PROGRAM);
            assertThat(subject.nextMapset()).isEqualTo(NEXT_MAPSET);
            assertThat(subject.nextMap()).isEqualTo(NEXT_MAP);
            assertThat(subject.errorMessage()).isEqualTo(ERROR_MESSAGE);
            assertThat(subject.returnMessage()).isEqualTo(RETURN_MESSAGE);
            assertThat(subject.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(subject.cardNumber()).isEqualTo(CARD_NUMBER);
            assertThat(subject.customerId()).isEqualTo(CUSTOMER_ID);
        }

        @Test
        @DisplayName("a blank value is stored exactly as supplied rather than defaulted, because there is no compact "
                + "constructor and no normalisation")
        void aBlankValueIsStoredExactlyAsSupplied() {
            final ScreenWorkArea subject = new ScreenWorkArea(null, "   ", "", "  x  ",
                    "   ", "", "           ", "", "         ");
            assertThat(subject.nextProgram()).isEqualTo("   ");
            assertThat(subject.nextMapset()).isEmpty();
            assertThat(subject.nextMap()).isEqualTo("  x  ");
            assertThat(subject.accountId()).isEqualTo("           ");
        }

        @Test
        @DisplayName("every component may be absent, which is the state the legacy structure starts in")
        void everyComponentMayBeAbsent() {
            final ScreenWorkArea subject = blank();
            assertThat(subject.keyAction()).isNull();
            assertThat(subject.nextProgram()).isNull();
            assertThat(subject.nextMapset()).isNull();
            assertThat(subject.nextMap()).isNull();
            assertThat(subject.errorMessage()).isNull();
            assertThat(subject.returnMessage()).isNull();
            assertThat(subject.accountId()).isNull();
            assertThat(subject.cardNumber()).isNull();
            assertThat(subject.customerId()).isNull();
        }

        @Test
        @DisplayName("the nine string fixtures are distinguishable, so a transposition would be caught")
        void theStringFixturesAreDistinguishable() {
            assertThat(java.util.Set.of(NEXT_PROGRAM, NEXT_MAPSET, NEXT_MAP, ERROR_MESSAGE,
                    RETURN_MESSAGE, ACCOUNT_ID, CARD_NUMBER, CUSTOMER_ID)).hasSize(8);
        }
    }

    @Nested
    @DisplayName("The attention key, whose absence is a real legacy state")
    class AttentionKey {

        @Test
        @DisplayName("a present key action is reported present")
        void aPresentKeyActionIsReportedPresent() {
            assertThat(workArea().attentionKey()).contains(KeyAction.PFK03);
        }

        @Test
        @DisplayName("an absent key action is reported absent rather than substituted, because no constant of the "
                + "key-action vocabulary represents unrecognised and none may be invented")
        void anAbsentKeyActionIsReportedAbsent() {
            assertThat(blank().attentionKey()).isEmpty();
            assertThat(blank().attentionIdText()).isEmpty();
        }

        @ParameterizedTest
        @EnumSource(KeyAction.class)
        @DisplayName("every declared key action is carried through and yields a five-character identifier")
        void everyDeclaredKeyActionIsCarriedThrough(final KeyAction action) {
            final ScreenWorkArea subject = new ScreenWorkArea(action, null, null, null, null,
                    null, null, null, null);
            assertThat(subject.attentionKey()).contains(action);
            assertThat(subject.attentionIdText()).isPresent();
            assertThat(subject.attentionIdText().orElseThrow())
                    .hasSize(ScreenWorkArea.ATTENTION_ID_LENGTH);
        }

        @Test
        @DisplayName("the identifier text is handed back padded, exactly as the vocabulary holds it, because the "
                + "padding is real data at real positions in a fixed-width area")
        void theIdentifierTextIsHandedBackPadded() {
            final ScreenWorkArea subject = new ScreenWorkArea(KeyAction.PA1, null, null, null,
                    null, null, null, null, null);
            assertThat(subject.attentionIdText()).contains("PA1  ");
        }

        @Test
        @DisplayName("the identifier text agrees with the vocabulary's own accessor, so the two cannot drift")
        void theIdentifierTextAgreesWithTheVocabulary() {
            assertThat(workArea().attentionIdText()).contains(KeyAction.PFK03.getAid());
        }
    }

    @Nested
    @DisplayName("The three numeric redefinitions, derived rather than stored")
    class NumericRedefinitions {

        @Test
        @DisplayName("each numeric view reads the whole of its own textual identifier")
        void eachNumericViewReadsItsOwnIdentifier() {
            final ScreenWorkArea subject = workArea();
            assertThat(subject.accountIdNumeric()).contains(new BigInteger("11"));
            assertThat(subject.cardNumberNumeric())
                    .contains(new BigInteger("4111111111111111"));
            assertThat(subject.customerIdNumeric()).contains(new BigInteger("11"));
        }

        @Test
        @DisplayName("the textual view keeps its leading zeros while the numeric view drops them, which is exactly "
                + "what a redefinition over one storage area produces")
        void theTextualViewKeepsItsLeadingZeros() {
            final ScreenWorkArea subject = workArea();
            assertThat(subject.accountId()).isEqualTo("00000000011");
            assertThat(subject.accountIdNumeric().orElseThrow().toString()).isEqualTo("11");
        }

        @Test
        @DisplayName("the numeric view is derived on every call, so it can never disagree with the text it is "
                + "derived from")
        void theNumericViewIsDerivedOnEveryCall() {
            final ScreenWorkArea subject = workArea();
            assertThat(subject.accountIdNumeric()).isEqualTo(subject.accountIdNumeric());
            assertThat(subject.accountIdNumeric().orElseThrow())
                    .isEqualTo(new BigInteger(subject.accountId()));
        }

        @Test
        @DisplayName("the card number is arbitrary precision, so sixteen digits cannot silently overflow a narrower "
                + "type")
        void theCardNumberIsArbitraryPrecision() {
            final ScreenWorkArea subject = new ScreenWorkArea(null, null, null, null, null, null,
                    null, "9999999999999999", null);
            assertThat(subject.cardNumberNumeric())
                    .contains(new BigInteger("9999999999999999"));
            assertThat(subject.cardNumberNumeric().orElseThrow())
                    .isGreaterThan(BigInteger.valueOf(Integer.MAX_VALUE));
            assertThat(subject.cardNumberNumeric().orElseThrow().toString())
                    .hasSize(ScreenWorkArea.CARD_NUMBER_LENGTH);
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"", "   ", "           ", "0000000001 ", " 0000000001",
                "0000000-01", "000000001A", "ABCDEFGHIJK", "1.0", "+1", "-1"})
        @DisplayName("a value that is absent, blank, partially filled or not wholly ASCII digits is reported absent "
                + "rather than trimmed or thrown, because the numeric alias over those bytes holds no valid "
                + "unsigned number")
        void aNonNumericValueIsReportedAbsent(final String candidate) {
            final ScreenWorkArea subject = new ScreenWorkArea(null, null, null, null, null, null,
                    candidate, candidate, candidate);
            assertThat(subject.accountIdNumeric()).isEmpty();
            assertThat(subject.cardNumberNumeric()).isEmpty();
            assertThat(subject.customerIdNumeric()).isEmpty();
        }

        @Test
        @DisplayName("a non-ASCII digit code point is NOT read as a number, which is why an explicit range test is "
                + "used in preference to the library digit predicate")
        void aNonAsciiDigitCodePointIsNotReadAsANumber() {
            final String arabicIndicDigits = "\u0661\u0662\u0663";
            assertThat(Character.isDigit(arabicIndicDigits.charAt(0)))
                    .as("the library predicate accepts this code point, which is the hazard")
                    .isTrue();
            final ScreenWorkArea subject = new ScreenWorkArea(null, null, null, null, null, null,
                    arabicIndicDigits, null, null);
            assertThat(subject.accountIdNumeric()).isEmpty();
        }

        @Test
        @DisplayName("an all-zero identifier reads as zero rather than as absent, which is the value the legacy "
                + "programs test the alias against")
        void anAllZeroIdentifierReadsAsZero() {
            final ScreenWorkArea subject = new ScreenWorkArea(null, null, null, null, null, null,
                    "00000000000", "0000000000000000", "000000000");
            assertThat(subject.accountIdNumeric()).contains(BigInteger.ZERO);
            assertThat(subject.cardNumberNumeric()).contains(BigInteger.ZERO);
            assertThat(subject.customerIdNumeric()).contains(BigInteger.ZERO);
        }

        @Test
        @DisplayName("the three numeric views are independent, so a readable identifier is still readable when a "
                + "sibling identifier is blank")
        void theThreeNumericViewsAreIndependent() {
            final ScreenWorkArea subject = new ScreenWorkArea(null, null, null, null, null, null,
                    ACCOUNT_ID, "    ", null);
            assertThat(subject.accountIdNumeric()).isPresent();
            assertThat(subject.cardNumberNumeric()).isEmpty();
            assertThat(subject.customerIdNumeric()).isEmpty();
        }

        @Test
        @DisplayName("no numeric accessor ever throws, whatever the bytes are, because a blank identifier is an "
                + "ordinary state rather than a parse failure")
        void noNumericAccessorEverThrows() {
            final ScreenWorkArea subject = new ScreenWorkArea(null, null, null, null, null, null,
                    "!!!", "\u0000", "  ");
            assertThat(subject.accountIdNumeric()).isEmpty();
            assertThat(subject.cardNumberNumeric()).isEmpty();
            assertThat(subject.customerIdNumeric()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Diagnostic rendering: routing state retained, business keys and message slots withheld")
    class DiagnosticRendering {

        @Test
        @DisplayName("the four routing components are retained, because a fixed vocabulary of key, program, "
                + "mapset and map names cannot carry an identifier")
        void theFourRoutingComponentsAreRetained() {
            final String rendering = workArea().toString();
            assertThat(rendering)
                    .startsWith("ScreenWorkArea[")
                    .contains("keyAction=" + KeyAction.PFK03)
                    .contains("nextProgram=" + NEXT_PROGRAM)
                    .contains("nextMapset=" + NEXT_MAPSET)
                    .contains("nextMap=" + NEXT_MAP)
                    .endsWith("]");
        }

        @Test
        @DisplayName("the two message slots are withheld, because the legacy assembles their text dynamically "
                + "and 3 of the 32 assembly sites concatenate a business key into it")
        void theTwoMessageSlotsAreWithheld() {
            assertThat(workArea().toString())
                    .doesNotContain(ERROR_MESSAGE)
                    .doesNotContain(RETURN_MESSAGE)
                    .contains("errorMessage=***REDACTED***")
                    .contains("returnMessage=***REDACTED***");
        }

        @Test
        @DisplayName("a message slot carrying the text the legacy actually builds - an account identifier, a "
                + "customer identifier or an internal resource name - cannot reach the rendering")
        void aDynamicallyAssembledMessageCannotReachTheRendering() {
            final String accountNotFound =
                    "Account:" + ACCOUNT_ID + " not found in Cross ref file.  Resp:0000000013";
            final String customerNotFound =
                    "CustId:" + CUSTOMER_ID + " not found in customer master.Resp: 0000000013";
            final String fileError = "File Error: READ     on CXACAIX  returned RESP 0000000013";

            final String rendering = new ScreenWorkArea(KeyAction.ENTER, NEXT_PROGRAM, NEXT_MAPSET,
                    NEXT_MAP, accountNotFound, customerNotFound, null, null, null).toString();
            final String returnSlot = new ScreenWorkArea(KeyAction.ENTER, NEXT_PROGRAM, NEXT_MAPSET,
                    NEXT_MAP, null, fileError, null, null, null).toString();

            assertThat(rendering)
                    .doesNotContain(ACCOUNT_ID)
                    .doesNotContain(CUSTOMER_ID)
                    .doesNotContain("Account:")
                    .doesNotContain("CustId:")
                    .doesNotContain("Resp:");
            assertThat(returnSlot)
                    .doesNotContain("CXACAIX")
                    .doesNotContain("File Error");
        }

        @Test
        @DisplayName("none of the three business keys appears in the rendering")
        void noneOfTheThreeBusinessKeysAppears() {
            final String rendering = workArea().toString();
            assertThat(rendering)
                    .doesNotContain(ACCOUNT_ID)
                    .doesNotContain(CARD_NUMBER)
                    .doesNotContain(CUSTOMER_ID)
                    .doesNotContain("4111");
        }

        @Test
        @DisplayName("each withheld component is replaced by a fixed placeholder, so nothing about the value - "
                + "not even its length - survives")
        void eachWithheldComponentIsReplacedByAFixedPlaceholder() {
            final String rendering = workArea().toString();
            assertThat(rendering)
                    .contains("errorMessage=***REDACTED***")
                    .contains("returnMessage=***REDACTED***")
                    .contains("accountId=***REDACTED***")
                    .contains("cardNumber=***REDACTED***")
                    .contains("customerId=***REDACTED***");
        }

        @Test
        @DisplayName("the placeholder is constant across differing key and message values, which is the "
                + "assertion that rules out a partial mask or a digest")
        void thePlaceholderIsConstantAcrossDifferingValues() {
            final String shortValues = new ScreenWorkArea(KeyAction.ENTER, null, null, null, "a",
                    "b", "1", "2", "3").toString();
            final String longValues = new ScreenWorkArea(KeyAction.ENTER, null, null, null,
                    ERROR_MESSAGE, RETURN_MESSAGE, "99999999999", "9999999999999999", "999999999")
                    .toString();
            assertThat(shortValues).isEqualTo(longValues);
        }

        @Test
        @DisplayName("a hostile value planted in any withheld component cannot reach the rendering")
        void aHostileValuePlantedInAWithheldComponentCannotReachTheRendering() {
            final String rendering = new ScreenWorkArea(KeyAction.ENTER, null, null, null,
                    "CANARY-ERR", "CANARY-RET", "CANARY-ACCT", "CANARY-CARD-NUM-", "CANARY-CU")
                    .toString();
            assertThat(rendering).doesNotContain("CANARY");
        }

        @Test
        @DisplayName("the redaction touches the rendering only: both message accessors still answer byte for "
                + "byte, because the operator-facing message is part of the screen contract")
        void theRedactionTouchesTheRenderingOnly() {
            final ScreenWorkArea subject = workArea();
            assertThat(subject.errorMessage()).isEqualTo(ERROR_MESSAGE);
            assertThat(subject.returnMessage()).isEqualTo(RETURN_MESSAGE);
            assertThat(subject.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(subject.cardNumber()).isEqualTo(CARD_NUMBER);
            assertThat(subject.customerId()).isEqualTo(CUSTOMER_ID);
        }

        @Test
        @DisplayName("a wholly blank work area renders without throwing")
        void aWhollyBlankWorkAreaRendersWithoutThrowing() {
            assertThat(blank().toString())
                    .startsWith("ScreenWorkArea[")
                    .contains("keyAction=null")
                    .contains("errorMessage=***REDACTED***")
                    .contains("accountId=***REDACTED***")
                    .endsWith("]");
        }
    }

    @Nested
    @DisplayName("Value semantics, which the record contract generates and the redaction does not disturb")
    class ValueSemantics {

        @Test
        @DisplayName("equality compares every component, including the three the rendering withholds")
        void equalityComparesEveryComponentIncludingTheWithheldOnes() {
            assertThat(workArea()).isEqualTo(workArea()).hasSameHashCodeAs(workArea());
            final ScreenWorkArea differentAccount = new ScreenWorkArea(KeyAction.PFK03,
                    NEXT_PROGRAM, NEXT_MAPSET, NEXT_MAP, ERROR_MESSAGE, RETURN_MESSAGE,
                    "00000000012", CARD_NUMBER, CUSTOMER_ID);
            assertThat(workArea()).isNotEqualTo(differentAccount);
        }

        @Test
        @DisplayName("two work areas that render identically can still be unequal, which is why the rendering must "
                + "never be used as an equality proxy")
        void twoWorkAreasThatRenderIdenticallyCanStillBeUnequal() {
            final ScreenWorkArea left = new ScreenWorkArea(KeyAction.ENTER, null, null, null,
                    null, null, "1", "2", "3");
            final ScreenWorkArea right = new ScreenWorkArea(KeyAction.ENTER, null, null, null,
                    null, null, "9", "8", "7");
            assertThat(left.toString()).isEqualTo(right.toString());
            assertThat(left).isNotEqualTo(right);
        }

        @Test
        @DisplayName("the key action participates in equality individually")
        void theKeyActionParticipatesIndividually() {
            final ScreenWorkArea other = new ScreenWorkArea(KeyAction.PFK04, NEXT_PROGRAM,
                    NEXT_MAPSET, NEXT_MAP, ERROR_MESSAGE, RETURN_MESSAGE, ACCOUNT_ID,
                    CARD_NUMBER, CUSTOMER_ID);
            assertThat(workArea()).isNotEqualTo(other);
        }

        @Test
        @DisplayName("a work area is not equal to null and not equal to a foreign type")
        void aWorkAreaIsNotEqualToNullOrAForeignType() {
            assertThat(workArea()).isNotEqualTo(null);
            assertThat(workArea().equals(ACCOUNT_ID)).isFalse();
        }

        @Test
        @DisplayName("two wholly blank work areas are equal")
        void twoWhollyBlankWorkAreasAreEqual() {
            assertThat(blank()).isEqualTo(blank()).hasSameHashCodeAs(blank());
        }
    }
}
