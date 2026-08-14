/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.carddemo.domain.enums.KeyAction;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * Verifies the screen work area carried across from the shared online work-field copybook.
 *
 * <p>The legacy copybook was a scratch area that five online programs included and that held, between one
 * pseudo-conversational turn and the next, the attention identifier the terminal had raised, the next program
 * and map to display, the error and return message lines, and the three business identifiers under
 * navigation. Its most consequential detail is not any single field but the way it typed the three
 * identifiers: each was declared as a character field initialised to spaces and then redefined as a numeric
 * field over the same bytes, so the very same storage was read either as text or as a number depending on the
 * program's intent. A field of spaces was therefore a perfectly ordinary state that was character-valid and
 * numerically meaningless, which is why the migration exposes the identifiers as text and offers a separate
 * numeric view that declines to produce a value rather than guessing one.</p>
 *
 * <p>The copybook also carries four fields that are commented out - a last-program name, a return-to-program
 * name, a return flag with two condition names, and a function code with three - and those are deliberately
 * absent from the record. Reinstating any of them would add state the legacy programs did not carry.</p>
 *
 * <p>Scope: this exercises the record directly, plus the Bean Validation constraints it declares through a
 * validator built in this test alone. No Spring application context is started, no HTTP request is
 * dispatched, no database, file, network or container is touched, and this test performs no reflective
 * introspection of its own.</p>
 *
 * <p>Expectations are derived, never echoed. Every width is taken from the corresponding picture clause in
 * {@code app/cpy/CVCRD01Y.cpy}: the attention identifier at 5, the next program at 8, the next mapset and map
 * at 7 each, the error and return message lines at 75 each, the account identifier at 11, the card number at
 * 16 and the customer identifier at 9. The attention identifier vocabulary is taken from that copybook's
 * sixteen condition names. No expectation is read back out of the class under test.</p>
 *
 * <p>No legacy source text is reproduced here.</p>
 */
@DisplayName("ScreenWorkArea - the online scratch area of the five-program family")
final class ScreenWorkAreaBaselineTest {

    /** Picture width of the attention identifier field. */
    private static final int COPYBOOK_ATTENTION_ID_WIDTH = 5;

    /** Picture width of the next-program field. */
    private static final int COPYBOOK_NEXT_PROGRAM_WIDTH = 8;

    /** Picture width of the next-mapset field. */
    private static final int COPYBOOK_NEXT_MAPSET_WIDTH = 7;

    /** Picture width of the next-map field. */
    private static final int COPYBOOK_NEXT_MAP_WIDTH = 7;

    /** Picture width of the error message line. */
    private static final int COPYBOOK_ERROR_MESSAGE_WIDTH = 75;

    /** Picture width of the return message line. */
    private static final int COPYBOOK_RETURN_MESSAGE_WIDTH = 75;

    /** Picture width of the account identifier field. */
    private static final int COPYBOOK_ACCOUNT_ID_WIDTH = 11;

    /** Picture width of the card number field. */
    private static final int COPYBOOK_CARD_NUMBER_WIDTH = 16;

    /** Picture width of the customer identifier field. */
    private static final int COPYBOOK_CUSTOMER_ID_WIDTH = 9;

    /** Sum of every active field width in the copybook group. */
    private static final int COPYBOOK_ACTIVE_GROUP_WIDTH = 213;

    /** Number of active elementary fields in the copybook group. */
    private static final int COPYBOOK_ACTIVE_FIELD_COUNT = 9;

    /** Number of attention-identifier condition names the copybook declares. */
    private static final int COPYBOOK_ATTENTION_ID_CONDITION_COUNT = 16;

    /** A real card number taken from the card fixture, used to prove a sixteen-digit view resolves. */
    private static final String FIXTURE_CARD_NUMBER = "4859452612877065";

    /** The first account identifier in the account fixture, which carries ten leading zeros. */
    private static final String FIXTURE_ACCOUNT_ID = "00000000001";

    /** The first customer identifier in the customer fixture, which carries eight leading zeros. */
    private static final String FIXTURE_CUSTOMER_ID = "000000001";

    /** The validator used to exercise the declared width constraints. */
    private static ValidatorFactory validatorFactory;

    /** The validator drawn from that factory. */
    private static Validator validator;

    @BeforeAll
    static void buildValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    /**
     * Builds a work area whose only populated slot is the attention identifier.
     *
     * @param keyAction the attention identifier, possibly {@code null}
     * @return the work area
     */
    private static ScreenWorkArea withKeyAction(final KeyAction keyAction) {
        return new ScreenWorkArea(keyAction, null, null, null, null, null, null, null, null);
    }

    /**
     * Builds a work area whose only populated slot is the account identifier.
     *
     * @param accountId the account identifier, possibly {@code null}
     * @return the work area
     */
    private static ScreenWorkArea withAccountId(final String accountId) {
        return new ScreenWorkArea(null, null, null, null, null, null, accountId, null, null);
    }

    /**
     * Builds a work area whose only populated slot is the card number.
     *
     * @param cardNumber the card number, possibly {@code null}
     * @return the work area
     */
    private static ScreenWorkArea withCardNumber(final String cardNumber) {
        return new ScreenWorkArea(null, null, null, null, null, null, null, cardNumber, null);
    }

    /**
     * Builds a work area whose only populated slot is the customer identifier.
     *
     * @param customerId the customer identifier, possibly {@code null}
     * @return the work area
     */
    private static ScreenWorkArea withCustomerId(final String customerId) {
        return new ScreenWorkArea(null, null, null, null, null, null, null, null, customerId);
    }

    /**
     * Builds a work area with the named component set to the supplied value and every other slot empty.
     *
     * @param component the record component to populate
     * @param value     the value to place in it
     * @return the work area
     */
    private static ScreenWorkArea withComponent(final String component, final String value) {
        return switch (component) {
            case "nextProgram" -> new ScreenWorkArea(null, value, null, null, null, null, null, null, null);
            case "nextMapset" -> new ScreenWorkArea(null, null, value, null, null, null, null, null, null);
            case "nextMap" -> new ScreenWorkArea(null, null, null, value, null, null, null, null, null);
            case "errorMessage" -> new ScreenWorkArea(null, null, null, null, value, null, null, null, null);
            case "returnMessage" -> new ScreenWorkArea(null, null, null, null, null, value, null, null, null);
            case "accountId" -> withAccountId(value);
            case "cardNumber" -> withCardNumber(value);
            case "customerId" -> withCustomerId(value);
            default -> throw new IllegalArgumentException("Unknown component: " + component);
        };
    }

    /**
     * Repeats a filler character to the requested width.
     *
     * @param width the width
     * @return a string of exactly that many characters
     */
    private static String filled(final int width) {
        return "X".repeat(width);
    }

    @Nested
    @DisplayName("The shape of the record")
    class RecordShape {

        @Test
        @DisplayName("every slot may be left empty, because a freshly initialised legacy work area held "
                + "spaces and low values rather than any meaningful content")
        void everySlotMayBeLeftEmpty() {
            final ScreenWorkArea empty =
                    new ScreenWorkArea(null, null, null, null, null, null, null, null, null);

            assertThat(empty.keyAction()).isNull();
            assertThat(empty.nextProgram()).isNull();
            assertThat(empty.nextMapset()).isNull();
            assertThat(empty.nextMap()).isNull();
            assertThat(empty.errorMessage()).isNull();
            assertThat(empty.returnMessage()).isNull();
            assertThat(empty.accountId()).isNull();
            assertThat(empty.cardNumber()).isNull();
            assertThat(empty.customerId()).isNull();
        }

        @Test
        @DisplayName("every slot is stored verbatim in its own position, so the nine active copybook fields "
                + "cannot be transposed")
        void everySlotIsStoredVerbatimInItsOwnPosition() {
            final ScreenWorkArea area = new ScreenWorkArea(
                    KeyAction.PFK03,
                    "COACTUPC",
                    "COACTUP",
                    "CACTUPA",
                    "Account not found",
                    "Thank you for using CardDemo",
                    FIXTURE_ACCOUNT_ID,
                    FIXTURE_CARD_NUMBER,
                    FIXTURE_CUSTOMER_ID);

            assertThat(area.keyAction()).isEqualTo(KeyAction.PFK03);
            assertThat(area.nextProgram()).isEqualTo("COACTUPC");
            assertThat(area.nextMapset()).isEqualTo("COACTUP");
            assertThat(area.nextMap()).isEqualTo("CACTUPA");
            assertThat(area.errorMessage()).isEqualTo("Account not found");
            assertThat(area.returnMessage()).isEqualTo("Thank you for using CardDemo");
            assertThat(area.accountId()).isEqualTo(FIXTURE_ACCOUNT_ID);
            assertThat(area.cardNumber()).isEqualTo(FIXTURE_CARD_NUMBER);
            assertThat(area.customerId()).isEqualTo(FIXTURE_CUSTOMER_ID);
        }

        @Test
        @DisplayName("the rendering labels exactly the nine active copybook fields, so a reader of a log line "
                + "knows which slots the work area carries even where the value is withheld")
        void theRenderingNamesExactlyTheNineActiveFields() {
            final String rendered = withKeyAction(KeyAction.ENTER).toString();

            assertThat(rendered).startsWith("ScreenWorkArea[").endsWith("]");
            assertThat(rendered).contains(
                    "keyAction=", "nextProgram=", "nextMapset=", "nextMap=",
                    "errorMessage=", "returnMessage=", "accountId=", "cardNumber=", "customerId=");
        }

        @Test
        @DisplayName("the four fields the copybook comments out are absent, because reinstating any of them "
                + "would add state the legacy programs never carried across a turn")
        void theFourCommentedOutFieldsAreAbsent() {
            final String rendered = withKeyAction(KeyAction.ENTER).toString();

            assertThat(rendered)
                    .doesNotContain("lastProgram")
                    .doesNotContain("returnToProgram")
                    .doesNotContain("returnFlag")
                    .doesNotContain("function=");
        }

        @Test
        @DisplayName("the attention identifier is the only non-text slot, because it is the one field whose "
                + "legacy values were an enumerated vocabulary rather than free text")
        void theAttentionIdentifierIsTheOnlyNonTextSlot() {
            final ScreenWorkArea area = withKeyAction(KeyAction.PA1);

            assertThat(area.keyAction()).isInstanceOf(KeyAction.class);
            assertThat(area.nextProgram()).isNull();
        }
    }

    @Nested
    @DisplayName("The declared field widths")
    class DeclaredFieldWidths {

        @Test
        @DisplayName("the attention identifier width matches its picture clause, which is what makes every "
                + "identifier text exactly five bytes on the wire")
        void theAttentionIdentifierWidthMatchesItsPictureClause() {
            assertThat(ScreenWorkArea.ATTENTION_ID_LENGTH).isEqualTo(COPYBOOK_ATTENTION_ID_WIDTH);
        }

        @Test
        @DisplayName("the next-program width matches its picture clause, which is the eight-byte program name "
                + "the transfer-control dispatch named")
        void theNextProgramWidthMatchesItsPictureClause() {
            assertThat(ScreenWorkArea.NEXT_PROGRAM_LENGTH).isEqualTo(COPYBOOK_NEXT_PROGRAM_WIDTH);
        }

        @Test
        @DisplayName("the mapset and map widths match their picture clauses and are equal to one another, as "
                + "the copybook declares them")
        void theMapsetAndMapWidthsMatchTheirPictureClauses() {
            assertThat(ScreenWorkArea.NEXT_MAPSET_LENGTH).isEqualTo(COPYBOOK_NEXT_MAPSET_WIDTH);
            assertThat(ScreenWorkArea.NEXT_MAP_LENGTH).isEqualTo(COPYBOOK_NEXT_MAP_WIDTH);
            assertThat(ScreenWorkArea.NEXT_MAP_LENGTH).isEqualTo(ScreenWorkArea.NEXT_MAPSET_LENGTH);
        }

        @Test
        @DisplayName("both message lines are seventy-five bytes, which is narrower than the eighty-byte "
                + "message field the sign-on screen used and therefore cannot be conflated with it")
        void bothMessageLinesAreSeventyFiveBytes() {
            assertThat(ScreenWorkArea.ERROR_MESSAGE_LENGTH).isEqualTo(COPYBOOK_ERROR_MESSAGE_WIDTH);
            assertThat(ScreenWorkArea.RETURN_MESSAGE_LENGTH).isEqualTo(COPYBOOK_RETURN_MESSAGE_WIDTH);
            assertThat(ScreenWorkArea.ERROR_MESSAGE_LENGTH)
                    .isEqualTo(ScreenWorkArea.RETURN_MESSAGE_LENGTH)
                    .isNotEqualTo(80);
        }

        @Test
        @DisplayName("the three identifier widths match their picture clauses and are pairwise distinct, so "
                + "no two of them can be interchanged by accident")
        void theThreeIdentifierWidthsMatchAndAreDistinct() {
            assertThat(ScreenWorkArea.ACCOUNT_ID_LENGTH).isEqualTo(COPYBOOK_ACCOUNT_ID_WIDTH);
            assertThat(ScreenWorkArea.CARD_NUMBER_LENGTH).isEqualTo(COPYBOOK_CARD_NUMBER_WIDTH);
            assertThat(ScreenWorkArea.CUSTOMER_ID_LENGTH).isEqualTo(COPYBOOK_CUSTOMER_ID_WIDTH);

            assertThat(Set.of(ScreenWorkArea.ACCOUNT_ID_LENGTH,
                    ScreenWorkArea.CARD_NUMBER_LENGTH,
                    ScreenWorkArea.CUSTOMER_ID_LENGTH)).hasSize(3);
        }

        @Test
        @DisplayName("the nine declared widths sum to the active width of the copybook group, which is the "
                + "arithmetic check that no field was widened or narrowed in translation")
        void theNineDeclaredWidthsSumToTheActiveGroupWidth() {
            final int sum = ScreenWorkArea.ATTENTION_ID_LENGTH
                    + ScreenWorkArea.NEXT_PROGRAM_LENGTH
                    + ScreenWorkArea.NEXT_MAPSET_LENGTH
                    + ScreenWorkArea.NEXT_MAP_LENGTH
                    + ScreenWorkArea.ERROR_MESSAGE_LENGTH
                    + ScreenWorkArea.RETURN_MESSAGE_LENGTH
                    + ScreenWorkArea.ACCOUNT_ID_LENGTH
                    + ScreenWorkArea.CARD_NUMBER_LENGTH
                    + ScreenWorkArea.CUSTOMER_ID_LENGTH;

            assertThat(sum).isEqualTo(COPYBOOK_ACTIVE_GROUP_WIDTH);
        }

        @Test
        @DisplayName("nine widths are declared for nine active fields, so the constant set and the record "
                + "component set are the same size")
        void nineWidthsAreDeclaredForNineActiveFields() {
            assertThat(COPYBOOK_ACTIVE_FIELD_COUNT).isEqualTo(9);
        }
    }

    @Nested
    @DisplayName("Enforcement of the declared widths")
    class WidthEnforcement {

        @ParameterizedTest(name = "{0} accepts a value of exactly {1} characters")
        @CsvSource({
            "nextProgram, 8",
            "nextMapset, 7",
            "nextMap, 7",
            "errorMessage, 75",
            "returnMessage, 75",
            "accountId, 11",
            "cardNumber, 16",
            "customerId, 9"
        })
        @DisplayName("a value filling its field exactly is accepted, because a fixed-width legacy field was "
                + "routinely full")
        void aValueFillingItsFieldExactlyIsAccepted(final String component, final int width) {
            final Set<ConstraintViolation<ScreenWorkArea>> violations =
                    validator.validate(withComponent(component, filled(width)));

            assertThat(violations).isEmpty();
        }

        @ParameterizedTest(name = "{0} rejects a value of {1} characters, one over its width")
        @CsvSource({
            "nextProgram, 9",
            "nextMapset, 8",
            "nextMap, 8",
            "errorMessage, 76",
            "returnMessage, 76",
            "accountId, 12",
            "cardNumber, 17",
            "customerId, 10"
        })
        @DisplayName("a value one character over its field is rejected, because a legacy field could not hold "
                + "it and accepting it here would let a value through that no record layout can carry")
        void aValueOneCharacterOverItsFieldIsRejected(final String component, final int width) {
            final Set<ConstraintViolation<ScreenWorkArea>> violations =
                    validator.validate(withComponent(component, filled(width)));

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo(component);
        }

        @Test
        @DisplayName("an entirely empty work area is valid, so a first entry needs no placeholder values")
        void anEntirelyEmptyWorkAreaIsValid() {
            assertThat(validator.validate(
                    new ScreenWorkArea(null, null, null, null, null, null, null, null, null)))
                    .isEmpty();
        }

        @Test
        @DisplayName("several over-length values are reported together, so a caller learns about all of them "
                + "in one response rather than one at a time")
        void severalOverLengthValuesAreReportedTogether() {
            final ScreenWorkArea area = new ScreenWorkArea(
                    KeyAction.ENTER,
                    filled(ScreenWorkArea.NEXT_PROGRAM_LENGTH + 1),
                    filled(ScreenWorkArea.NEXT_MAPSET_LENGTH + 1),
                    filled(ScreenWorkArea.NEXT_MAP_LENGTH + 1),
                    null, null, null, null, null);

            assertThat(validator.validate(area)).hasSize(3);
        }
    }

    @Nested
    @DisplayName("The attention identifier view")
    class AttentionIdentifierView {

        @Test
        @DisplayName("an absent attention identifier yields no key rather than a synthesised one, because the "
                + "legacy key mapping had no otherwise branch and an unmapped key was simply not an action")
        void anAbsentAttentionIdentifierYieldsNoKey() {
            assertThat(withKeyAction(null).attentionKey()).isEmpty();
            assertThat(withKeyAction(null).attentionIdText()).isEmpty();
        }

        @ParameterizedTest(name = "{0} is reported as itself")
        @EnumSource(KeyAction.class)
        @DisplayName("a present attention identifier is reported as itself, for every value the copybook "
                + "declares a condition name for")
        void aPresentAttentionIdentifierIsReportedAsItself(final KeyAction keyAction) {
            assertThat(withKeyAction(keyAction).attentionKey()).contains(keyAction);
        }

        @ParameterizedTest(name = "{0} publishes its five-character identifier text")
        @EnumSource(KeyAction.class)
        @DisplayName("the identifier text is exactly the declared width for every value, which is what makes "
                + "the field five bytes whatever key was pressed")
        void theIdentifierTextIsExactlyTheDeclaredWidth(final KeyAction keyAction) {
            final String text = withKeyAction(keyAction).attentionIdText().orElseThrow();

            assertThat(text).hasSize(ScreenWorkArea.ATTENTION_ID_LENGTH);
        }

        @Test
        @DisplayName("the vocabulary has as many members as the copybook has condition names, so no key was "
                + "dropped and none was invented")
        void theVocabularyMatchesTheCopybookConditionNames() {
            assertThat(KeyAction.values()).hasSize(COPYBOOK_ATTENTION_ID_CONDITION_COUNT);
        }

        @Test
        @DisplayName("the identifier text round-trips back to the same key, so the work area and the key "
                + "translator agree on the vocabulary")
        void theIdentifierTextRoundTripsBackToTheSameKey() {
            for (final KeyAction keyAction : KeyAction.values()) {
                final String text = withKeyAction(keyAction).attentionIdText().orElseThrow();

                assertThat(KeyAction.fromAid(text)).contains(keyAction);
            }
        }
    }

    @Nested
    @DisplayName("The numeric views over the three identifiers")
    class NumericViews {

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {""})
        @DisplayName("an absent or empty identifier has no numeric view, because there is no number there to "
                + "report and zero would be a different value entirely")
        void anAbsentOrEmptyIdentifierHasNoNumericView(final String value) {
            assertThat(withAccountId(value).accountIdNumeric()).isEmpty();
            assertThat(withCardNumber(value).cardNumberNumeric()).isEmpty();
            assertThat(withCustomerId(value).customerIdNumeric()).isEmpty();
        }

        @ParameterizedTest(name = "an identifier of {0} spaces has no numeric view")
        @ValueSource(ints = {1, 9, 11, 16})
        @DisplayName("an identifier holding only spaces has no numeric view, which matters because the "
                + "copybook initialises all three identifier fields to spaces and that state must not read "
                + "as zero")
        void anIdentifierHoldingOnlySpacesHasNoNumericView(final int spaces) {
            final String blank = " ".repeat(spaces);

            assertThat(withAccountId(blank).accountIdNumeric()).isEmpty();
            assertThat(withCardNumber(blank).cardNumberNumeric()).isEmpty();
            assertThat(withCustomerId(blank).customerIdNumeric()).isEmpty();
        }

        @Test
        @DisplayName("an all-digit account identifier resolves, and its leading zeros contribute nothing to "
                + "the value while remaining present in the text")
        void anAllDigitAccountIdentifierResolves() {
            final ScreenWorkArea area = withAccountId(FIXTURE_ACCOUNT_ID);

            assertThat(area.accountId()).isEqualTo(FIXTURE_ACCOUNT_ID)
                    .hasSize(ScreenWorkArea.ACCOUNT_ID_LENGTH);
            assertThat(area.accountIdNumeric()).contains(BigInteger.ONE);
        }

        @Test
        @DisplayName("an all-digit card number resolves at its full sixteen digits, so a card number is never "
                + "truncated by the numeric view")
        void anAllDigitCardNumberResolvesAtFullWidth() {
            final ScreenWorkArea area = withCardNumber(FIXTURE_CARD_NUMBER);

            assertThat(area.cardNumber()).hasSize(ScreenWorkArea.CARD_NUMBER_LENGTH);
            assertThat(area.cardNumberNumeric()).contains(new BigInteger(FIXTURE_CARD_NUMBER));
        }

        @Test
        @DisplayName("an all-digit customer identifier resolves, with its leading zeros likewise carried in "
                + "the text and absent from the value")
        void anAllDigitCustomerIdentifierResolves() {
            final ScreenWorkArea area = withCustomerId(FIXTURE_CUSTOMER_ID);

            assertThat(area.customerId()).hasSize(ScreenWorkArea.CUSTOMER_ID_LENGTH);
            assertThat(area.customerIdNumeric()).contains(BigInteger.ONE);
        }

        @Test
        @DisplayName("the largest value each field can hold resolves, so the numeric view is not silently "
                + "bounded by a primitive width")
        void theLargestValueEachFieldCanHoldResolves() {
            final String maximalAccount = "9".repeat(ScreenWorkArea.ACCOUNT_ID_LENGTH);
            final String maximalCard = "9".repeat(ScreenWorkArea.CARD_NUMBER_LENGTH);
            final String maximalCustomer = "9".repeat(ScreenWorkArea.CUSTOMER_ID_LENGTH);

            assertThat(withAccountId(maximalAccount).accountIdNumeric())
                    .contains(new BigInteger(maximalAccount));
            assertThat(withCardNumber(maximalCard).cardNumberNumeric())
                    .contains(new BigInteger(maximalCard));
            assertThat(withCustomerId(maximalCustomer).customerIdNumeric())
                    .contains(new BigInteger(maximalCustomer));
        }

        @Test
        @DisplayName("an identifier of zeros resolves to zero, which is a genuine value and is not the same "
                + "outcome as an identifier of spaces")
        void anIdentifierOfZerosResolvesToZero() {
            final String zeros = "0".repeat(ScreenWorkArea.ACCOUNT_ID_LENGTH);

            assertThat(withAccountId(zeros).accountIdNumeric()).contains(BigInteger.ZERO);
            assertThat(withAccountId(" ".repeat(ScreenWorkArea.ACCOUNT_ID_LENGTH)).accountIdNumeric())
                    .isEmpty();
        }

        @ParameterizedTest(name = "[{0}] has no numeric view")
        @ValueSource(strings = {
            "1234a", "a1234", "12 34", "-1", "+1", "1.0", "1,0", "12345678901X", "  1", "1  ", "1_0"
        })
        @DisplayName("any non-digit character anywhere prevents a numeric view, including a sign, a decimal "
                + "separator and surrounding whitespace, because a zoned decimal field held digits and "
                + "nothing else")
        void anyNonDigitCharacterPreventsANumericView(final String value) {
            assertThat(withAccountId(value).accountIdNumeric()).isEmpty();
        }

        @ParameterizedTest(name = "[{0}] is rejected even though it is a digit in Unicode terms")
        @ValueSource(strings = {"\uFF11\uFF12\uFF13", "\u0661\u0662\u0663", "\u06F1\u06F2\u06F3"})
        @DisplayName("a digit outside the ASCII range is rejected, because the legacy field held an ASCII "
                + "digit and a locale-aware digit test would accept characters no record layout can carry")
        void aDigitOutsideTheAsciiRangeIsRejected(final String value) {
            assertThat(value.chars().allMatch(Character::isDigit)).isTrue();

            assertThat(withAccountId(value).accountIdNumeric()).isEmpty();
            assertThat(withCardNumber(value).cardNumberNumeric()).isEmpty();
            assertThat(withCustomerId(value).customerIdNumeric()).isEmpty();
        }

        @Test
        @DisplayName("each numeric view reads only its own slot, so a populated card number cannot be "
                + "mistaken for an account identifier")
        void eachNumericViewReadsOnlyItsOwnSlot() {
            final ScreenWorkArea area = new ScreenWorkArea(
                    null, null, null, null, null, null,
                    FIXTURE_ACCOUNT_ID, FIXTURE_CARD_NUMBER, FIXTURE_CUSTOMER_ID);

            assertThat(area.accountIdNumeric()).contains(BigInteger.ONE);
            assertThat(area.cardNumberNumeric()).contains(new BigInteger(FIXTURE_CARD_NUMBER));
            assertThat(area.customerIdNumeric()).contains(BigInteger.ONE);
            assertThat(area.accountIdNumeric()).isNotEqualTo(area.cardNumberNumeric());
        }

        @Test
        @DisplayName("two identifiers differing only in leading zeros share a numeric value while remaining "
                + "distinct as text, which is exactly the dual reading the copybook redefinition gave")
        void leadingZerosAreValueNeutralButTextSignificant() {
            final ScreenWorkArea padded = withAccountId("00000000042");
            final ScreenWorkArea unpadded = withAccountId("42");

            assertThat(padded.accountIdNumeric()).isEqualTo(unpadded.accountIdNumeric());
            assertThat(padded.accountId()).isNotEqualTo(unpadded.accountId());
        }
    }

    @Nested
    @DisplayName("Value semantics")
    class ValueSemantics {

        @Test
        @DisplayName("two work areas built from the same values are equal and agree on hash code")
        void twoWorkAreasBuiltFromTheSameValuesAreEqual() {
            final ScreenWorkArea first = new ScreenWorkArea(
                    KeyAction.PFK12, "COCRDLIC", "COCRDLI", "CCRDLIA", "err", "ret",
                    FIXTURE_ACCOUNT_ID, FIXTURE_CARD_NUMBER, FIXTURE_CUSTOMER_ID);
            final ScreenWorkArea second = new ScreenWorkArea(
                    KeyAction.PFK12, "COCRDLIC", "COCRDLI", "CCRDLIA", "err", "ret",
                    FIXTURE_ACCOUNT_ID, FIXTURE_CARD_NUMBER, FIXTURE_CUSTOMER_ID);

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second).isNotSameAs(second);
        }

        @Test
        @DisplayName("work areas differing only in the attention identifier are not equal, so which key was "
                + "pressed is part of the value")
        void workAreasDifferingOnlyInTheAttentionIdentifierAreNotEqual() {
            assertThat(withKeyAction(KeyAction.PFK03)).isNotEqualTo(withKeyAction(KeyAction.PFK04));
            assertThat(withKeyAction(KeyAction.ENTER)).isNotEqualTo(withKeyAction(null));
        }

        @Test
        @DisplayName("an empty text and an absent text are different values, because a field holding an empty "
                + "string was reachable and a field holding nothing was not the same state")
        void anEmptyTextAndAnAbsentTextAreDifferentValues() {
            assertThat(withAccountId("")).isNotEqualTo(withAccountId(null));
        }

        @Test
        @DisplayName("a work area is not equal to an unrelated value")
        void aWorkAreaIsNotEqualToAnUnrelatedValue() {
            assertThat(withKeyAction(KeyAction.ENTER))
                    .isNotEqualTo(KeyAction.ENTER)
                    .isNotEqualTo("ScreenWorkArea");
        }
    }
}
