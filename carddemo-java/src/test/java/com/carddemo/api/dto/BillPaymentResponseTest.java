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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Unit tests for {@link BillPaymentResponse}, the response contract of legacy transaction
 * {@code CB00} &mdash; the program {@code app/cbl/COBIL00C.cbl}, 572 lines and sixteen paragraphs,
 * driving the screen whose field-level contract is the generated symbolic map
 * {@code app/cpy-bms/COBIL00.CPY} and whose layout is {@code app/bms/COBIL00.bms}.
 *
 * <p>A pure unit test. Nothing here starts an application context, opens a connection, launches a
 * container or touches a file system: every instance is constructed directly, and the JSON shape is
 * checked against a mapper built locally in this class to the same four settings the module
 * publishes in {@code src/main/resources/application.yml} &mdash; absent properties omitted, dates
 * not written as timestamps, unknown incoming properties tolerated, and decimals written plainly.
 *
 * <p><strong>Four properties dominate what is pinned here, and each of the four is a byte of an
 * external contract rather than a stylistic preference.</strong>
 *
 * <p>The first is the <strong>six measured operator texts</strong> the program emits on its failure
 * arms, at lines 161, 187, 201, 237, 536 and 543. They are pinned against literals written
 * independently in this class, at their measured lengths of 27, 40, 26, 33, 24 and 37 characters,
 * and each is driven through the one message component and back. A compiler cannot see a stray edit
 * to a string literal, so gate 5 depends on an assertion of exactly this kind.
 *
 * <p>The second is the <strong>composed success message, which carries two consecutive spaces</strong>.
 * The program assembles it from four pieces at lines 527 to 530: a first fragment ending in a space,
 * a second beginning and ending in a space, the sixteen-character identifier of the posted
 * transaction, and a period. Because the first fragment ends with a space and the second begins with
 * one, the assembled text contains a two-space run after its first period. That run is the exact byte
 * sequence the legacy screen displayed, it is reproduced rather than tidied, and the expectation here
 * is built by concatenating the four pieces in this class so that no production formatter is ever its
 * own oracle.
 *
 * <p>The third is the <strong>balance's decimal shape</strong>. It derives from
 * {@code ACCT-CURR-BAL} on the 300-byte account record at {@code app/cpy/CVACT01Y.cpy} line 7 &mdash;
 * ten integer digits and two decimal places, so total precision twelve &mdash; one of the five money
 * fields of that record. The scale is asserted explicitly as exactly two rather than through a
 * comparison that ignores scale, because a comparison of numeric value alone would let a scale drift
 * pass unnoticed. The contract <em>refuses</em> a misshapen value instead of repairing it, and that
 * distinction is asserted directly: refusing changes nothing, whereas rescaling at this boundary
 * would silently alter a value carried from a zoned-decimal field. No rescaling call appears in this
 * package at all, and the truncating rounding policy the estate requires belongs to one class in the
 * utility layer, which this test never touches.
 *
 * <p>The fourth is the <strong>success-is-not-an-error asymmetry</strong>. The success arm and the
 * confirmation prompt each write a message while leaving the error flag off, so a populated message
 * does not imply a failure. The flag is asserted to be an independent component: a non-empty message
 * is shown coexisting with a flag that is off, and an absent message with a flag that is on, which
 * together make it impossible for the flag to have been derived from the message.
 *
 * <p><strong>Structural claims are made behaviourally, with no run-time metadata inspection.</strong>
 * That the contract publishes exactly sixteen components, and therefore carries no merchant, no
 * description and no second balance, is asserted by serialising a fully populated instance and
 * pinning the resulting key set exactly &mdash; a key set of exactly sixteen known names admits no
 * seventeenth serialisable component. That each text component is bounded, and bounded at the width
 * the legacy field declares, is asserted by driving a validator at the bound and one character past
 * it. Both are stronger than reading an annotation, because they check the behaviour a client
 * actually meets.
 *
 * <p>Provenance for every citation, width and count below: repository checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec} and the upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19, which is the stamp this member carries in
 * its own trailer comment at line 571. No COBOL, screen-map or job-control source text appears
 * anywhere in this file: the legacy estate is cited by member name, line number, field width and
 * contract text only.
 */
@DisplayName("BillPaymentResponse :: response contract of legacy bill-payment transaction CB00")
class BillPaymentResponseTest {

    // -----------------------------------------------------------------------------------------
    // The four widths the output half of the symbolic map declares, at lines 122, 128, 134 and 140.
    // Held as expectations of this test rather than read from the contract, so that a change to
    // either side has to be made deliberately on both.
    // -----------------------------------------------------------------------------------------

    /** Width of the echoed account-identifier field, from the map at line 122. */
    private static final int ACCOUNT_ID_WIDTH = 11;

    /** Width of the echoed confirmation-answer field, from the map at line 134. */
    private static final int CONFIRM_WIDTH = 1;

    /**
     * Width of the message field, from the map at line 140.
     *
     * <p>Seventy-eight here and not eighty. The program's own message work area is eighty characters
     * wide, so the map field is the narrower of the two and is therefore the binding external width.
     * Thirteen of the seventeen screens in the estate declare this field at seventy-eight and only
     * the two card screens declare it at eighty; the widths are never normalised to one another.
     */
    private static final int MESSAGE_WIDTH = 78;

    /** Width of the posted transaction's identifier, from the transaction record layout. */
    private static final int TRANSACTION_ID_WIDTH = 16;

    /** Ceiling a generated symbolic map imposes on a field name, and so on the focus hint. */
    private static final int FOCUS_HINT_WIDTH = 7;

    // -----------------------------------------------------------------------------------------
    // The six measured failure texts, transcribed from the cited source lines and written here
    // independently of the contract so that this test is a genuine oracle for them.
    // -----------------------------------------------------------------------------------------

    /**
     * Source line 161, twenty-seven characters.
     *
     * <p>Two details are deliberate and neither is a defect. The word for an account is
     * <strong>abbreviated</strong>, whereas the not-found text of lines 361, 392 and 425 spells it
     * out in this very same program; and the negative is <strong>capitalised</strong>. Both spellings
     * are contract text that an operator procedure or a downstream monitor may match on exactly, so
     * harmonising them would break gate 5 rather than tidy anything.
     */
    private static final String ACCT_ID_EMPTY_TEXT = "Acct ID can NOT be empty...";

    /** Source line 187, forty characters. The accepted answers are parenthesised as the screen shows. */
    private static final String INVALID_CONFIRM_TEXT = "Invalid value. Valid values are (Y/N)...";

    /**
     * Source line 201, twenty-six characters.
     *
     * <p>The rejection the program answers with when the balance test at line 198 finds the balance
     * is not positive, which is why a zero balance has to be carriable alongside this text.
     */
    private static final String NOTHING_TO_PAY_TEXT = "You have nothing to pay...";

    /** Source line 237, thirty-three characters. A prompt rather than a failure; the flag stays off. */
    private static final String CONFIRM_PROMPT_TEXT = "Confirm to make a bill payment...";

    /**
     * Source line 536, twenty-four characters.
     *
     * <p>The word for the posted record is <strong>abbreviated</strong> here and the verb is in the
     * agreement form the source uses rather than the grammatically expected one. Both are reproduced.
     */
    private static final String DUPLICATE_TRAN_ID_TEXT = "Tran ID already exist...";

    /**
     * Source line 543, thirty-seven characters.
     *
     * <p>The two words naming the payment appear in mixed case with the second beginning in lower
     * case, a form that occurs nowhere else in the program. It is reproduced exactly.
     */
    private static final String UNABLE_TO_ADD_TEXT = "Unable to Add Bill pay Transaction...";

    // -----------------------------------------------------------------------------------------
    // The four pieces of the composed success message, from source lines 527, 528, 529 and 530.
    // Written here as literals so the expectation is independent of any production formatter.
    // -----------------------------------------------------------------------------------------

    /** First piece, line 527, twenty characters, <strong>ending in a space</strong>. */
    private static final String SUCCESS_PIECE_ONE = "Payment successful. ";

    /**
     * Second piece, line 528, twenty-four characters, <strong>beginning and ending in a space</strong>.
     *
     * <p>Its leading space pairs with the trailing space of {@link #SUCCESS_PIECE_ONE} to produce the
     * two-space run; its trailing space separates the fragment from the identifier that follows.
     */
    private static final String SUCCESS_PIECE_TWO = " Your Transaction ID is ";

    /** Fourth piece, line 530, one character, terminating the assembled text. */
    private static final String SUCCESS_PIECE_FOUR = ".";

    /**
     * The wording the transaction-add screen composes for the structurally identical message.
     *
     * <p>Present only so that the two can be asserted <strong>different</strong>. That screen
     * abbreviates the word for the posted record where this one spells it out, and its first fragment
     * is thirty-two characters against twenty here. Sharing one text between the two screens would
     * silently break whichever of them did not own it, so they are never unified or templated together.
     */
    private static final String TRANSACTION_ADD_PIECE_ONE = "Transaction added successfully. ";

    /** The transaction-add second fragment, seventeen characters, abbreviating where this does not. */
    private static final String TRANSACTION_ADD_PIECE_TWO = " Your Tran ID is ";

    // -----------------------------------------------------------------------------------------
    // Fixture values. Every one is a fixed literal: nothing here reads a clock, draws a random
    // number or derives a value, so a failure is always reproducible.
    // -----------------------------------------------------------------------------------------

    /** An eleven-character account key whose leading zeros are contractual. */
    private static final String ACCOUNT_ID = "00000000001";

    /** The identifier the first payment against an empty table obtains, seeded rather than sequenced. */
    private static final String FIRST_TRANSACTION_ID = "0000000000000001";

    /** A balance at the contractual scale of two. */
    private static final BigDecimal BALANCE = new BigDecimal("1234.56");

    /** Zero at the contractual scale, which the nothing-to-pay arm has to be able to carry. */
    private static final BigDecimal ZERO_BALANCE = new BigDecimal("0.00");

    /** A negative balance, which the record field is signed to hold. */
    private static final BigDecimal NEGATIVE_BALANCE = new BigDecimal("-1234.56");

    /** The widest balance the record field holds: ten integer digits and two decimal places. */
    private static final BigDecimal WIDEST_BALANCE = new BigDecimal("9999999999.99");

    /** The widest negative balance, whose plain rendering is fourteen characters. */
    private static final BigDecimal WIDEST_NEGATIVE_BALANCE = new BigDecimal("-9999999999.99");

    /** The fixed stand-in the contract's own rendering emits for each withheld component. */
    private static final String REDACTED = "***REDACTED***";

    /** The sixteen component names the screen contract publishes, in declaration order. */
    private static final List<String> WIRE_KEYS_IN_DECLARATION_ORDER = List.of(
            "accountId", "currentBalance", "confirm", "newTransactionId", "transactionName",
            "title01", "currentDate", "programName", "title02", "currentTime", "errorMessage",
            "paymentAccepted", "generalError", "focusScreenFieldId", "nextRoute",
            "navigationContext");

    /**
     * The complete set of texts the contract publishes, used where a property has to hold for all of
     * them rather than for one.
     */
    private static final List<String> EVERY_PUBLISHED_TEXT = List.of(
            BillPaymentResponse.MSG_ACCT_ID_EMPTY,
            BillPaymentResponse.MSG_INVALID_CONFIRM_VALUE,
            BillPaymentResponse.MSG_NOTHING_TO_PAY,
            BillPaymentResponse.MSG_CONFIRM_BILL_PAYMENT,
            BillPaymentResponse.MSG_ACCOUNT_ID_NOT_FOUND,
            BillPaymentResponse.MSG_UNABLE_TO_LOOKUP_ACCOUNT,
            BillPaymentResponse.MSG_UNABLE_TO_UPDATE_ACCOUNT,
            BillPaymentResponse.MSG_UNABLE_TO_LOOKUP_XREF_AIX,
            BillPaymentResponse.MSG_TRANSACTION_ID_NOT_FOUND,
            BillPaymentResponse.MSG_UNABLE_TO_LOOKUP_TRANSACTION,
            BillPaymentResponse.MSG_TRAN_ID_ALREADY_EXIST,
            BillPaymentResponse.MSG_UNABLE_TO_ADD_BILL_PAY_TRANSACTION);

    /** The six measured failure texts of the cited source lines, in source order. */
    private static final List<String> THE_SIX_FAILURE_TEXTS = List.of(
            ACCT_ID_EMPTY_TEXT, INVALID_CONFIRM_TEXT, NOTHING_TO_PAY_TEXT, CONFIRM_PROMPT_TEXT,
            DUPLICATE_TRAN_ID_TEXT, UNABLE_TO_ADD_TEXT);

    /**
     * The assembled success message, built by concatenating the four pieces in the order the source
     * assembles them and by nothing else.
     *
     * <p>No production formatter, codec, template holder or record mapper contributes to this value:
     * an oracle that called the code under test would agree with it by construction and would prove
     * nothing.
     */
    private static String assembledSuccessMessage() {
        return SUCCESS_PIECE_ONE + SUCCESS_PIECE_TWO + FIRST_TRANSACTION_ID + SUCCESS_PIECE_FOUR;
    }

    /**
     * Builds a mapper configured exactly as the module configures its own, and shared with nothing.
     *
     * <p>The four settings mirror {@code spring.jackson} in {@code application.yml}: absent
     * properties are omitted rather than sent as null, dates are not written as epoch numbers,
     * unknown incoming properties are tolerated so a client may echo a property this contract does
     * not consume, and decimals are written plainly because scientific notation would corrupt a
     * fixed-scale value carried from a zoned-decimal field. No serialiser, deserialiser, mix-in or
     * per-property override is registered, because the contract declares none.
     *
     * @return a mapper equivalent to the module's own
     */
    private static ObjectMapper moduleEquivalentMapper() {
        return JsonMapper.builder()
                .defaultPropertyInclusion(JsonInclude.Value.construct(
                        JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .build();
    }

    /**
     * A navigation state carrying identifiers whose leading zeros are contractual.
     *
     * @return an echoed navigation state with every identifier populated
     */
    private static NavigationContext navigation() {
        return new NavigationContext("CB00", "COBIL00C", "CB00", "COBIL00C", "USERID01", "U",
                NavigationContext.ProgramContext.REENTER, "000000011", "MARY", null, "SMITH",
                ACCOUNT_ID, "Y", "0000111122223333", "COBIL0A", "COBIL00");
    }

    /**
     * A response with all sixteen components populated, used wherever the wire shape or the
     * rendering is under test.
     *
     * <p>The message is the confirmation prompt and both flags are off, which is the state the
     * program is in at line 237: a message is displayed and nothing has failed.
     *
     * @return a fully populated response
     */
    private static BillPaymentResponse populated() {
        return new BillPaymentResponse(ACCOUNT_ID, BALANCE, "y", FIRST_TRANSACTION_ID, "CB00",
                "CardDemo bill payment", "06/10/22", "COBIL00C", "Pay the full balance", "19:27:53",
                BillPaymentResponse.MSG_CONFIRM_BILL_PAYMENT, false, false,
                BillPaymentResponse.CONFIRM_FIELD_ID, "/api/menu", navigation());
    }

    /**
     * A response in which every optional component is absent and both flags are off.
     *
     * <p>A real state rather than a placeholder: the success arm blanks every echoed field at line
     * 524 before redisplaying the screen, so no component may be required to be present.
     *
     * @return a wholly absent response
     */
    private static BillPaymentResponse allAbsent() {
        return new BillPaymentResponse(null, null, null, null, null, null, null, null, null, null,
                null, false, false, null, null, null);
    }

    /**
     * A response carrying one message and one error flag, with everything else absent.
     *
     * @param message      the single summary message, which may be {@code null}
     * @param generalError whether this response reports a failure
     * @return a response carrying only that message and that flag
     */
    private static BillPaymentResponse withMessage(String message, boolean generalError) {
        return new BillPaymentResponse(null, null, null, null, null, null, null, null, null, null,
                message, false, generalError, null, null, null);
    }

    /**
     * A response carrying one balance and nothing else.
     *
     * @param balance the balance to carry, which may be {@code null}
     * @return a response carrying only that balance
     */
    private static BillPaymentResponse withBalance(BigDecimal balance) {
        return new BillPaymentResponse(null, balance, null, null, null, null, null, null, null, null,
                null, false, false, null, null, null);
    }

    /**
     * Serialises a response and reads it straight back, so that every assertion about a value
     * surviving unchanged is made across a real encode and decode rather than on the same object.
     *
     * @param response the response to send through the mapper
     * @return the response as a client would receive and rebuild it
     */
    private static BillPaymentResponse roundTrip(BillPaymentResponse response) {
        ObjectMapper mapper = moduleEquivalentMapper();
        try {
            return mapper.readValue(mapper.writeValueAsString(response), BillPaymentResponse.class);
        } catch (JsonProcessingException failure) {
            throw new AssertionError("the response did not survive a JSON round trip; the mapper "
                    + "rejected either the written form or the read form of " + response, failure);
        }
    }

    /**
     * Serialises a response to its JSON text.
     *
     * @param response the response to serialise
     * @return the JSON text a client would receive
     */
    private static String jsonOf(BillPaymentResponse response) {
        try {
            return moduleEquivalentMapper().writeValueAsString(response);
        } catch (JsonProcessingException failure) {
            throw new AssertionError("the response could not be serialised: " + response, failure);
        }
    }

    /**
     * Serialises a response and parses the result back into a tree, for assertions about the shape
     * of the payload rather than about the values it carries.
     *
     * @param response the response to serialise
     * @return the parsed payload
     */
    private static JsonNode payloadOf(BillPaymentResponse response) {
        try {
            return moduleEquivalentMapper().readTree(jsonOf(response));
        } catch (JsonProcessingException failure) {
            throw new AssertionError("the serialised response could not be parsed back: "
                    + response, failure);
        }
    }

    /**
     * The property names a serialised response actually publishes at its top level.
     *
     * <p>This is how the component inventory is established without inspecting metadata at run time:
     * a payload whose key set is exactly the sixteen known names cannot have a seventeenth
     * serialisable component, so every absence the contract claims is asserted by this one list.
     *
     * @param response the response to serialise
     * @return the top-level property names, in the order the payload carries them
     */
    private static List<String> wireKeysOf(BillPaymentResponse response) {
        List<String> keys = new ArrayList<>();
        payloadOf(response).fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    /**
     * Validates a response with a validator built from the Bean Validation bootstrap directly, not
     * from a framework-managed one, so the result is the contract's own annotations and nothing else.
     *
     * @param response the response to validate
     * @return every violation the contract reports for it
     */
    private static Set<ConstraintViolation<BillPaymentResponse>> violationsOf(
            BillPaymentResponse response) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(response);
        }
    }

    /**
     * The names of the properties a response violates, sorted so an assertion reads deterministically.
     *
     * @param response the response to validate
     * @return the violated property names
     */
    private static List<String> violatedPropertiesOf(BillPaymentResponse response) {
        return violationsOf(response).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .sorted()
                .toList();
    }

    /**
     * A string of a given length, for probing a bound at and past its limit.
     *
     * @param length how many characters to produce
     * @return a string of exactly that length
     */
    private static String textOfLength(int length) {
        return "x".repeat(length);
    }

    @Nested
    @DisplayName("the six measured failure texts are reproduced byte for byte")
    class TheSixMeasuredFailureTexts {

        @Test
        @DisplayName("reproduces the empty-account-identifier text of line 161 at 27 characters")
        void reproducesTheEmptyAccountIdentifierTextOfLine161() {
            assertThat(BillPaymentResponse.MSG_ACCT_ID_EMPTY)
                    .isEqualTo(ACCT_ID_EMPTY_TEXT)
                    .hasSize(27);
            assertThat(roundTrip(withMessage(ACCT_ID_EMPTY_TEXT, true)).errorMessage())
                    .isEqualTo(ACCT_ID_EMPTY_TEXT)
                    .hasSize(27);
        }

        @Test
        @DisplayName("reproduces the unaccepted-confirmation text of line 187 at 40 characters")
        void reproducesTheUnacceptedConfirmationTextOfLine187() {
            assertThat(BillPaymentResponse.MSG_INVALID_CONFIRM_VALUE)
                    .isEqualTo(INVALID_CONFIRM_TEXT)
                    .hasSize(40);
            assertThat(roundTrip(withMessage(INVALID_CONFIRM_TEXT, true)).errorMessage())
                    .isEqualTo(INVALID_CONFIRM_TEXT)
                    .hasSize(40);
        }

        @Test
        @DisplayName("reproduces the nothing-to-pay text of line 201 at 26 characters")
        void reproducesTheNothingToPayTextOfLine201() {
            assertThat(BillPaymentResponse.MSG_NOTHING_TO_PAY)
                    .isEqualTo(NOTHING_TO_PAY_TEXT)
                    .hasSize(26);
            assertThat(roundTrip(withMessage(NOTHING_TO_PAY_TEXT, true)).errorMessage())
                    .isEqualTo(NOTHING_TO_PAY_TEXT)
                    .hasSize(26);
        }

        @Test
        @DisplayName("reproduces the confirmation prompt of line 237 at 33 characters")
        void reproducesTheConfirmationPromptOfLine237() {
            assertThat(BillPaymentResponse.MSG_CONFIRM_BILL_PAYMENT)
                    .isEqualTo(CONFIRM_PROMPT_TEXT)
                    .hasSize(33);
            assertThat(roundTrip(withMessage(CONFIRM_PROMPT_TEXT, false)).errorMessage())
                    .isEqualTo(CONFIRM_PROMPT_TEXT)
                    .hasSize(33);
        }

        @Test
        @DisplayName("reproduces the duplicate-transaction text of line 536 at 24 characters")
        void reproducesTheDuplicateTransactionTextOfLine536() {
            assertThat(BillPaymentResponse.MSG_TRAN_ID_ALREADY_EXIST)
                    .isEqualTo(DUPLICATE_TRAN_ID_TEXT)
                    .hasSize(24);
            assertThat(roundTrip(withMessage(DUPLICATE_TRAN_ID_TEXT, true)).errorMessage())
                    .isEqualTo(DUPLICATE_TRAN_ID_TEXT)
                    .hasSize(24);
        }

        @Test
        @DisplayName("reproduces the unable-to-add text of line 543 at 37 characters")
        void reproducesTheUnableToAddTextOfLine543() {
            assertThat(BillPaymentResponse.MSG_UNABLE_TO_ADD_BILL_PAY_TRANSACTION)
                    .isEqualTo(UNABLE_TO_ADD_TEXT)
                    .hasSize(37);
            assertThat(roundTrip(withMessage(UNABLE_TO_ADD_TEXT, true)).errorMessage())
                    .isEqualTo(UNABLE_TO_ADD_TEXT)
                    .hasSize(37);
        }

        @Test
        @DisplayName("keeps the abbreviated account word that the same program elsewhere spells out")
        void keepsTheAbbreviatedAccountWordTheSameProgramElsewhereSpellsOut() {
            // The inconsistency between these two texts is CONTRACT, NOT A DEFECT. Line 161
            // abbreviates the word for an account and capitalises the negative; lines 361, 392 and
            // 425 spell the word out. Either may be matched on exactly by an operator procedure or a
            // downstream monitor, so regularising them would break the interface contract that
            // gate 5 verifies rather than improve anything.
            assertThat(BillPaymentResponse.MSG_ACCT_ID_EMPTY)
                    .startsWith("Acct ")
                    .contains(" NOT ")
                    .endsWith("...")
                    .doesNotContain("Account");
            assertThat(BillPaymentResponse.MSG_ACCOUNT_ID_NOT_FOUND)
                    .startsWith("Account ")
                    .contains(" NOT ");
            assertThat(BillPaymentResponse.MSG_ACCT_ID_EMPTY)
                    .isNotEqualTo("Account ID can NOT be empty...")
                    .isNotEqualTo("Acct ID can not be empty...");
        }

        @Test
        @DisplayName("keeps every failure text un-case-folded across a round trip")
        void keepsEveryFailureTextUnCaseFolded() {
            // The expected forms are written out as literals rather than derived by folding the
            // actual value, so the assertion cannot be weakened by the locale the suite happens to
            // run under and no case transformation appears in this test at all.
            assertThat(roundTrip(withMessage(ACCT_ID_EMPTY_TEXT, true)).errorMessage())
                    .isEqualTo("Acct ID can NOT be empty...")
                    .isNotEqualTo("ACCT ID CAN NOT BE EMPTY...")
                    .isNotEqualTo("acct id can not be empty...");
            assertThat(roundTrip(withMessage(UNABLE_TO_ADD_TEXT, true)).errorMessage())
                    .isEqualTo("Unable to Add Bill pay Transaction...")
                    .isNotEqualTo("Unable to add bill pay transaction...")
                    .isNotEqualTo("UNABLE TO ADD BILL PAY TRANSACTION...");
            assertThat(THE_SIX_FAILURE_TEXTS).allSatisfy(text ->
                    assertThat(roundTrip(withMessage(text, true)).errorMessage()).isEqualTo(text));
        }

        @Test
        @DisplayName("carries all six texts through the one message component, never a list")
        void carriesAllSixTextsThroughTheOneMessageComponent() {
            // The legacy cascade is ordered and stops at the first failure, so exactly one summary
            // text is ever outstanding. The contract therefore carries one message component and not
            // a collection, and all seven shapes - these six plus the composed success text - travel
            // through that same single component.
            for (String text : THE_SIX_FAILURE_TEXTS) {
                JsonNode message = payloadOf(withMessage(text, true)).get("errorMessage");

                assertThat(message.isTextual()).isTrue();
                assertThat(message.isArray()).isFalse();
                assertThat(message.asText()).isEqualTo(text);
            }

            JsonNode success = payloadOf(withMessage(assembledSuccessMessage(), false))
                    .get("errorMessage");

            assertThat(success.isTextual()).isTrue();
            assertThat(success.isArray()).isFalse();
        }

        @Test
        @DisplayName("keeps every one of the six inside the map's message width of 78")
        void keepsEveryOneOfTheSixInsideTheMapMessageWidth() {
            assertThat(THE_SIX_FAILURE_TEXTS)
                    .allSatisfy(text -> assertThat(text.length())
                            .isLessThanOrEqualTo(MESSAGE_WIDTH));
            assertThat(violatedPropertiesOf(withMessage(UNABLE_TO_ADD_TEXT, true))).isEmpty();
        }

        @Test
        @DisplayName("publishes all fourteen texts the program can emit, each within the map width")
        void publishesAllFourteenTextsWithinTheMapWidth() {
            // Twelve distinct whole texts plus the two success fragments, which are published
            // unjoined because each contributes a space at the join.
            assertThat(EVERY_PUBLISHED_TEXT).hasSize(12).doesNotHaveDuplicates();
            assertThat(EVERY_PUBLISHED_TEXT)
                    .allSatisfy(text -> assertThat(text.length())
                            .isLessThanOrEqualTo(MESSAGE_WIDTH));
            assertThat(EVERY_PUBLISHED_TEXT).allSatisfy(text ->
                    assertThat(roundTrip(withMessage(text, true)).errorMessage()).isEqualTo(text));
        }
    }

    @Nested
    @DisplayName("the composed success message carries two consecutive spaces")
    class TheComposedSuccessMessage {

        @Test
        @DisplayName("assembles the four pieces in the order the source assembles them")
        void assemblesTheFourPiecesInSourceOrder() {
            assertThat(BillPaymentResponse.MSG_PAYMENT_SUCCESSFUL_PREFIX)
                    .isEqualTo(SUCCESS_PIECE_ONE)
                    .hasSize(20);
            assertThat(BillPaymentResponse.MSG_TRANSACTION_ID_FRAGMENT)
                    .isEqualTo(SUCCESS_PIECE_TWO)
                    .hasSize(24);
            assertThat(assembledSuccessMessage())
                    .isEqualTo("Payment successful.  Your Transaction ID is 0000000000000001.")
                    .hasSize(20 + 24 + TRANSACTION_ID_WIDTH + 1);
        }

        @Test
        @DisplayName("carries exactly two consecutive spaces after its first period")
        void carriesTwoConsecutiveSpacesAfterItsFirstPeriod() {
            // THIS IS THE CONTRACT, NOT A DEFECT. The first piece ends with a space and the second
            // begins with one, so the join produces a two-space run. It is the exact byte sequence
            // the legacy screen displayed, gate 1 compares those exact characters, and the same
            // pattern occurs on the transaction-add screen - which makes it an estate idiom rather
            // than a local slip.
            String assembled = assembledSuccessMessage();

            assertThat(assembled).contains("successful.  Your");
            assertThat(assembled).contains("  ");
            assertThat(assembled).doesNotContain("   ");
            assertThat(assembled.indexOf("  ")).isEqualTo(19);
            assertThat(assembled.lastIndexOf("  ")).isEqualTo(19);
        }

        @Test
        @DisplayName("keeps the trailing space of the first piece rather than trimming it")
        void keepsTheTrailingSpaceOfTheFirstPiece() {
            assertThat(BillPaymentResponse.MSG_PAYMENT_SUCCESSFUL_PREFIX)
                    .endsWith(". ")
                    .isNotEqualTo("Payment successful.")
                    .isNotEqualTo("Payment successful");
        }

        @Test
        @DisplayName("keeps both the leading and the trailing space of the second piece")
        void keepsBothSpacesOfTheSecondPiece() {
            assertThat(BillPaymentResponse.MSG_TRANSACTION_ID_FRAGMENT)
                    .startsWith(" ")
                    .endsWith(" ")
                    .isNotEqualTo("Your Transaction ID is")
                    .isNotEqualTo(" Your Transaction ID is")
                    .isNotEqualTo("Your Transaction ID is ");
        }

        @Test
        @DisplayName("keeps the sixteen-character identifier unshortened, leading zeros and all")
        void keepsTheSixteenCharacterIdentifierUnshortened() {
            String assembled = assembledSuccessMessage();

            assertThat(FIRST_TRANSACTION_ID).hasSize(TRANSACTION_ID_WIDTH);
            assertThat(assembled).contains(FIRST_TRANSACTION_ID);
            assertThat(assembled).contains("is 0000000000000001.");
            assertThat(assembled).doesNotContain("is 1.");
        }

        @Test
        @DisplayName("ends with the period of the fourth piece")
        void endsWithThePeriodOfTheFourthPiece() {
            assertThat(SUCCESS_PIECE_FOUR).hasSize(1);
            assertThat(assembledSuccessMessage()).endsWith(SUCCESS_PIECE_FOUR).endsWith("1.");
        }

        @Test
        @DisplayName("is never whitespace-collapsed, trimmed or re-spaced")
        void isNeverWhitespaceCollapsedTrimmedOrRespaced() {
            String assembled = assembledSuccessMessage();

            assertThat(assembled)
                    .isNotEqualTo("Payment successful. Your Transaction ID is 0000000000000001.")
                    .isNotEqualTo("Payment successful.Your Transaction ID is 0000000000000001.")
                    .isNotEqualTo("Payment successful.  Your Transaction ID is 0000000000000001. ");
            assertThat(assembled).hasSize(61);
        }

        @Test
        @DisplayName("is never unified with the transaction-add success wording")
        void isNeverUnifiedWithTheTransactionAddWording() {
            // The transaction-add screen abbreviates the word for the posted record where this screen
            // spells it out, and its first fragment is thirty-two characters against twenty here.
            // Sharing one text between the two screens would silently break whichever did not own
            // it, so the two are never unified or templated together.
            assertThat(TRANSACTION_ADD_PIECE_ONE).hasSize(32);
            assertThat(TRANSACTION_ADD_PIECE_TWO).hasSize(17);
            assertThat(BillPaymentResponse.MSG_PAYMENT_SUCCESSFUL_PREFIX)
                    .isNotEqualTo(TRANSACTION_ADD_PIECE_ONE);
            assertThat(BillPaymentResponse.MSG_TRANSACTION_ID_FRAGMENT)
                    .isNotEqualTo(TRANSACTION_ADD_PIECE_TWO)
                    .contains("Transaction ID")
                    .doesNotContain("Tran ID");
            // The abbreviation is not expanded either, in the one text of this program that uses it.
            assertThat(BillPaymentResponse.MSG_TRAN_ID_ALREADY_EXIST)
                    .contains("Tran ID")
                    .doesNotContain("Transaction ID");
        }

        @Test
        @DisplayName("survives a JSON round trip with both spaces of the run intact")
        void survivesAJsonRoundTripWithBothSpacesIntact() {
            String assembled = assembledSuccessMessage();
            BillPaymentResponse carried = roundTrip(new BillPaymentResponse(null, null, null,
                    FIRST_TRANSACTION_ID, null, null, null, null, null, null, assembled, true,
                    false, BillPaymentResponse.ACCOUNT_ID_FIELD_ID, "/api/menu", null));

            assertThat(carried.errorMessage()).isEqualTo(assembled).hasSize(61);
            assertThat(carried.errorMessage()).contains("successful.  Your");
            assertThat(carried.newTransactionId()).isEqualTo(FIRST_TRANSACTION_ID);
            assertThat(jsonOf(withMessage(assembled, false)))
                    .contains("Payment successful.  Your Transaction ID is 0000000000000001.");
        }

        @Test
        @DisplayName("fits inside the map's message width of 78 with room to spare")
        void fitsInsideTheMapMessageWidth() {
            assertThat(assembledSuccessMessage().length()).isLessThanOrEqualTo(MESSAGE_WIDTH);
            assertThat(violatedPropertiesOf(withMessage(assembledSuccessMessage(), false)))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("the balance is a fixed-point decimal at scale two that nothing here rescales")
    class TheBalanceDecimalContract {

        @Test
        @DisplayName("carries the balance as a fixed-point decimal, never a binary approximation")
        void carriesTheBalanceAsAFixedPointDecimal() {
            // The assignment below is the proof of the static type: it compiles only because the
            // accessor is declared to return a fixed-point decimal, and the scale enquiry that
            // follows exists on no binary floating-point type, so neither a primitive nor a boxed
            // approximate type could satisfy either line. Precision here has to be exact - the
            // underlying field stores ten integer digits and two decimal places - and an approximate
            // binary type cannot represent one cent exactly.
            BigDecimal carried = withBalance(BALANCE).currentBalance();

            assertThat(carried).isExactlyInstanceOf(BigDecimal.class);
            assertThat(carried.scale()).isEqualTo(BillPaymentResponse.BALANCE_SCALE);
            assertThat(carried).isEqualTo(new BigDecimal("1234.56"));
        }

        @Test
        @DisplayName("publishes the scale and integer digits its record field declares")
        void publishesTheRecordFieldFigures() {
            assertThat(BillPaymentResponse.BALANCE_SCALE).isEqualTo(2);
            assertThat(BillPaymentResponse.BALANCE_INTEGER_DIGITS).isEqualTo(10);
            // Total precision twelve, which is what the relational column declares. Ten integer
            // digits and not nine: the account balance is a wider field than a transaction amount,
            // and reusing the narrower figure would refuse balances the record can hold.
            assertThat(BillPaymentResponse.BALANCE_INTEGER_DIGITS
                    + BillPaymentResponse.BALANCE_SCALE).isEqualTo(12);
        }

        @Test
        @DisplayName("keeps the scale at exactly two across a JSON round trip")
        void keepsTheScaleAtExactlyTwoAcrossAJsonRoundTrip() {
            // Asserted on the scale itself and not merely on numeric value. A comparison that
            // ignores scale would accept a value that had drifted to one or three decimal places,
            // which is exactly the drift this assertion exists to catch.
            BigDecimal carried = roundTrip(withBalance(BALANCE)).currentBalance();

            assertThat(carried.scale()).isEqualTo(2);
            assertThat(carried).isEqualTo(BALANCE);
            assertThat(carried.toPlainString()).isEqualTo("1234.56");
        }

        @Test
        @DisplayName("writes the balance in plain notation rather than scientific")
        void writesTheBalanceInPlainNotation() {
            assertThat(jsonOf(withBalance(BALANCE)))
                    .contains("\"currentBalance\":1234.56")
                    .doesNotContain("E+")
                    .doesNotContain("e+");
            assertThat(jsonOf(withBalance(WIDEST_BALANCE)))
                    .contains("\"currentBalance\":9999999999.99")
                    .doesNotContain("E+");
        }

        @Test
        @DisplayName("writes a value that would otherwise render as 1E+2 in plain form")
        void writesAValueThatWouldOtherwiseRenderScientificallyInPlainForm()
                throws JsonProcessingException {
            // The contract itself refuses this value, because its scale is minus two rather than two,
            // so the mapper setting is proven on the mapper directly. Written through a mapper
            // WITHOUT the plain-decimal setting the same value renders in scientific notation; the
            // module's setting is what turns it into plain digits, and a fixed-scale value carried
            // from a zoned-decimal field would be corrupted by the scientific form.
            BigDecimal wouldRenderScientifically = new BigDecimal("1E+2");

            assertThat(wouldRenderScientifically.scale()).isEqualTo(-2);
            assertThat(wouldRenderScientifically.toString()).isEqualTo("1E+2");
            assertThat(jsonOf(withBalance(BALANCE))).doesNotContain("E+");

            assertThat(moduleEquivalentMapper().writeValueAsString(wouldRenderScientifically))
                    .isEqualTo("100")
                    .doesNotContain("E+");
            assertThat(JsonMapper.builder().build().writeValueAsString(wouldRenderScientifically))
                    .isEqualTo("1E+2");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> withBalance(wouldRenderScientifically));
        }

        @Test
        @DisplayName("reads a plain decimal string back at scale two")
        void readsAPlainDecimalStringBackAtScaleTwo() throws JsonProcessingException {
            ObjectMapper mapper = moduleEquivalentMapper();

            BigDecimal fromWholeAmount = mapper.readValue(
                    "{\"currentBalance\":100.00}", BillPaymentResponse.class).currentBalance();

            assertThat(fromWholeAmount.scale()).isEqualTo(2);
            assertThat(fromWholeAmount).isEqualTo(new BigDecimal("100.00"));

            BigDecimal fromSmallAmount = mapper.readValue(
                    "{\"currentBalance\":0.10}", BillPaymentResponse.class).currentBalance();

            assertThat(fromSmallAmount.scale()).isEqualTo(2);
            assertThat(fromSmallAmount).isEqualTo(new BigDecimal("0.10"));
        }

        @Test
        @DisplayName("round-trips a negative balance, because the record field is signed")
        void roundTripsANegativeBalance() {
            BigDecimal carried = roundTrip(withBalance(NEGATIVE_BALANCE)).currentBalance();

            assertThat(carried.scale()).isEqualTo(2);
            assertThat(carried).isEqualTo(NEGATIVE_BALANCE);
            assertThat(carried.signum()).isEqualTo(-1);
            assertThat(jsonOf(withBalance(NEGATIVE_BALANCE)))
                    .contains("\"currentBalance\":-1234.56");
        }

        @Test
        @DisplayName("round-trips a zero balance beside the text that rejects it")
        void roundTripsAZeroBalanceBesideItsRejectionText() {
            // Load-bearing: the balance test at line 198 rejects a payment when the balance is not
            // positive and answers with the twenty-six-character text of line 201, so a zero balance
            // has to be carriable alongside that very text.
            BillPaymentResponse carried = roundTrip(new BillPaymentResponse(ACCOUNT_ID,
                    ZERO_BALANCE, null, null, null, null, null, null, null, null,
                    NOTHING_TO_PAY_TEXT, false, true, BillPaymentResponse.ACCOUNT_ID_FIELD_ID, null,
                    null));

            assertThat(carried.currentBalance().scale()).isEqualTo(2);
            assertThat(carried.currentBalance()).isEqualTo(ZERO_BALANCE);
            assertThat(carried.currentBalance().signum()).isZero();
            assertThat(carried.errorMessage()).isEqualTo(NOTHING_TO_PAY_TEXT).hasSize(26);
            assertThat(carried.generalError()).isTrue();
            assertThat(carried.paymentAccepted()).isFalse();
            assertThat(jsonOf(withBalance(ZERO_BALANCE))).contains("\"currentBalance\":0.00");
        }

        @Test
        @DisplayName("round-trips the widest balance the record field holds, in both signs")
        void roundTripsTheWidestBalanceInBothSigns() {
            assertThat(WIDEST_BALANCE.precision() - WIDEST_BALANCE.scale())
                    .isEqualTo(BillPaymentResponse.BALANCE_INTEGER_DIGITS);
            assertThat(roundTrip(withBalance(WIDEST_BALANCE)).currentBalance())
                    .isEqualTo(WIDEST_BALANCE);
            assertThat(roundTrip(withBalance(WIDEST_BALANCE)).currentBalance().scale()).isEqualTo(2);
            assertThat(roundTrip(withBalance(WIDEST_NEGATIVE_BALANCE)).currentBalance())
                    .isEqualTo(WIDEST_NEGATIVE_BALANCE);
            // Fourteen rendered characters, which is the display width of the map's balance field -
            // and the widest value that width could ever have shown.
            assertThat(WIDEST_NEGATIVE_BALANCE.toPlainString()).hasSize(14);
        }

        @Test
        @DisplayName("returns an accepted balance as the very same object, so nothing is rescaled")
        void returnsAnAcceptedBalanceAsTheVerySameObject() {
            BigDecimal supplied = new BigDecimal("1234.56");

            assertThat(withBalance(supplied).currentBalance()).isSameAs(supplied);
            assertThat(populated().currentBalance()).isSameAs(BALANCE);
        }

        @Test
        @DisplayName("refuses one decimal place rather than padding it out to two")
        void refusesOneDecimalPlaceRatherThanPaddingIt() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> withBalance(new BigDecimal("1234.5")))
                    .withMessageContaining("must carry scale 2")
                    .withMessageContaining("its scale is 1");
        }

        @Test
        @DisplayName("refuses three decimal places rather than truncating them to two")
        void refusesThreeDecimalPlacesRatherThanTruncatingThem() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> withBalance(new BigDecimal("1234.567")))
                    .withMessageContaining("must carry scale 2")
                    .withMessageContaining("its scale is 3");
        }

        @Test
        @DisplayName("refuses an integral value rather than scaling it up to two places")
        void refusesAnIntegralValueRatherThanScalingItUp() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> withBalance(new BigDecimal("1234")))
                    .withMessageContaining("its scale is 0");
        }

        @Test
        @DisplayName("refuses eleven integer digits, which the record field cannot hold")
        void refusesElevenIntegerDigits() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> withBalance(new BigDecimal("12345678901.00")))
                    .withMessageContaining("must fit 10 integer digits")
                    .withMessageContaining("it needs 11");
        }

        @Test
        @DisplayName("names neither the offending value nor the account in a refusal")
        void namesNeitherTheValueNorTheAccountInARefusal() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> withBalance(new BigDecimal("1234.567")))
                    .withMessageNotContaining("1234.567")
                    .withMessageNotContaining(ACCOUNT_ID);
        }

        @Test
        @DisplayName("accepts an absent balance, since several arms read no account at all")
        void acceptsAnAbsentBalance() {
            assertThatNoException().isThrownBy(() -> withBalance(null));
            assertThat(withBalance(null).currentBalance()).isNull();
            assertThat(roundTrip(allAbsent()).currentBalance()).isNull();
        }

        @Test
        @DisplayName("keeps the trailing zero of a small balance rather than collapsing it")
        void keepsTheTrailingZeroOfASmallBalance() {
            BigDecimal tenCents = new BigDecimal("0.10");

            assertThat(jsonOf(withBalance(tenCents))).contains("\"currentBalance\":0.10");
            assertThat(roundTrip(withBalance(tenCents)).currentBalance().toPlainString())
                    .isEqualTo("0.10");
            assertThat(roundTrip(withBalance(tenCents)).currentBalance().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("applies no grouping, no leading plus and no currency symbol")
        void appliesNoGroupingNoLeadingPlusAndNoCurrencySymbol() {
            String json = jsonOf(withBalance(WIDEST_BALANCE));

            assertThat(json).contains("9999999999.99");
            assertThat(json).doesNotContain("9,999,999,999.99");
            assertThat(json).doesNotContain("+9999999999.99");
            assertThat(json).doesNotContain("$");
            assertThat(json).doesNotContain("USD");
            // The balance travels as a JSON number, so it is never quoted as display text either.
            assertThat(json).doesNotContain("\"currentBalance\":\"");
        }
    }

    @Nested
    @DisplayName("a populated message does not imply a failure")
    class TheSuccessIsNotAnErrorAsymmetry {

        @Test
        @DisplayName("carries the composed success message with the error flag off")
        void carriesTheComposedSuccessMessageWithTheErrorFlagOff() {
            // The success arm sets a different screen attribute from every failure arm, and it writes
            // a message while leaving the error flag off. No attribute, colour or terminal value
            // appears in the contract or in this test: where severity has to travel it travels as
            // the semantic flag below.
            BillPaymentResponse carried = roundTrip(new BillPaymentResponse(null, null, null,
                    FIRST_TRANSACTION_ID, null, null, null, null, null, null,
                    assembledSuccessMessage(), true, false, null, "/api/menu", null));

            assertThat(carried.errorMessage()).isEqualTo(assembledSuccessMessage()).isNotEmpty();
            assertThat(carried.generalError()).isFalse();
            assertThat(carried.paymentAccepted()).isTrue();
        }

        @Test
        @DisplayName("carries the confirmation prompt with the error flag off")
        void carriesTheConfirmationPromptWithTheErrorFlagOff() {
            // The second of the two proofs that the flag cannot be inferred: asking the operator to
            // confirm is not a failure, so line 237 writes a message and leaves the flag off.
            BillPaymentResponse carried = roundTrip(withMessage(CONFIRM_PROMPT_TEXT, false));

            assertThat(carried.errorMessage()).isEqualTo(CONFIRM_PROMPT_TEXT).isNotEmpty();
            assertThat(carried.generalError()).isFalse();
            assertThat(carried.paymentAccepted()).isFalse();
        }

        @Test
        @DisplayName("carries every one of the six failure texts with the error flag on")
        void carriesEveryFailureTextWithTheErrorFlagOn() {
            assertThat(THE_SIX_FAILURE_TEXTS).allSatisfy(text -> {
                BillPaymentResponse carried = roundTrip(withMessage(text, true));

                assertThat(carried.errorMessage()).isEqualTo(text);
                assertThat(carried.generalError()).isTrue();
                assertThat(carried.paymentAccepted()).isFalse();
            });
        }

        @Test
        @DisplayName("does not derive the error flag from a message being present")
        void doesNotDeriveTheErrorFlagFromAMessageBeingPresent() {
            // The decisive pair. The same non-empty text is carried once with the flag off and once
            // with it on, so no function of the message alone could produce both answers.
            assertThat(withMessage(CONFIRM_PROMPT_TEXT, false).generalError()).isFalse();
            assertThat(withMessage(CONFIRM_PROMPT_TEXT, true).generalError()).isTrue();
            assertThat(roundTrip(withMessage(CONFIRM_PROMPT_TEXT, false)).generalError()).isFalse();
            assertThat(roundTrip(withMessage(CONFIRM_PROMPT_TEXT, true)).generalError()).isTrue();
        }

        @Test
        @DisplayName("does not derive the error flag from an absent or blank message either")
        void doesNotDeriveTheErrorFlagFromAnAbsentOrBlankMessage() {
            assertThat(withMessage(null, true).generalError()).isTrue();
            assertThat(withMessage(null, true).errorMessage()).isNull();
            assertThat(withMessage("", true).generalError()).isTrue();
            assertThat(withMessage("", true).errorMessage()).isEmpty();
            assertThat(withMessage(" ", false).generalError()).isFalse();
            assertThat(roundTrip(withMessage(" ", true)).errorMessage()).isEqualTo(" ");
            assertThat(roundTrip(withMessage(" ", true)).generalError()).isTrue();
        }

        @Test
        @DisplayName("keeps the acceptance flag independent of the confirmation answer")
        void keepsTheAcceptanceFlagIndependentOfTheConfirmationAnswer() {
            // An affirmative answer only selects the posting path; every step after it can still
            // fail, so acceptance is true only once the write has returned normally.
            BillPaymentResponse affirmativeButFailed = new BillPaymentResponse(ACCOUNT_ID, BALANCE,
                    "y", null, null, null, null, null, null, null, UNABLE_TO_ADD_TEXT, false, true,
                    BillPaymentResponse.ACCOUNT_ID_FIELD_ID, null, null);

            assertThat(affirmativeButFailed.confirm()).isEqualTo("y");
            assertThat(affirmativeButFailed.paymentAccepted()).isFalse();
            assertThat(affirmativeButFailed.generalError()).isTrue();

            BillPaymentResponse acceptedWithBlankedEchoes = new BillPaymentResponse(null, null, null,
                    FIRST_TRANSACTION_ID, null, null, null, null, null, null,
                    assembledSuccessMessage(), true, false, null, null, null);

            assertThat(acceptedWithBlankedEchoes.confirm()).isNull();
            assertThat(acceptedWithBlankedEchoes.paymentAccepted()).isTrue();
            assertThat(acceptedWithBlankedEchoes.generalError()).isFalse();
        }

        @Test
        @DisplayName("publishes both flags as independent booleans on the wire")
        void publishesBothFlagsAsIndependentBooleansOnTheWire() {
            JsonNode accepted = payloadOf(new BillPaymentResponse(null, null, null, null, null, null,
                    null, null, null, null, assembledSuccessMessage(), true, false, null, null,
                    null));

            assertThat(accepted.get("paymentAccepted").isBoolean()).isTrue();
            assertThat(accepted.get("paymentAccepted").asBoolean()).isTrue();
            assertThat(accepted.get("generalError").asBoolean()).isFalse();

            JsonNode failed = payloadOf(withMessage(UNABLE_TO_ADD_TEXT, true));

            assertThat(failed.get("paymentAccepted").asBoolean()).isFalse();
            assertThat(failed.get("generalError").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("carries no colour, attribute or terminal value alongside the flag")
        void carriesNoColourAttributeOrTerminalValueAlongsideTheFlag() {
            String json = jsonOf(populated());

            assertThat(json)
                    .doesNotContain("\"colour\"")
                    .doesNotContain("\"color\"")
                    .doesNotContain("\"attribute\"")
                    .doesNotContain("\"highlight\"")
                    .doesNotContain("\"severity\"")
                    .doesNotContain("\"cursorRow\"")
                    .doesNotContain("\"cursorColumn\"");
        }
    }

    @Nested
    @DisplayName("the echoed screen values are text and survive byte for byte")
    class TheEchoedScreenValues {

        @Test
        @DisplayName("round-trips the eleven-character account key with its leading zeros")
        void roundTripsTheElevenCharacterAccountKeyWithItsLeadingZeros() {
            assertThat(ACCOUNT_ID).hasSize(ACCOUNT_ID_WIDTH);

            BillPaymentResponse carried = roundTrip(populated());

            assertThat(carried.accountId()).isEqualTo(ACCOUNT_ID).hasSize(ACCOUNT_ID_WIDTH);
            assertThat(payloadOf(populated()).get("accountId").isTextual()).isTrue();
            assertThat(payloadOf(populated()).get("accountId").asText()).isEqualTo(ACCOUNT_ID);
        }

        @Test
        @DisplayName("never shortens the account key to its numeric value")
        void neverShortensTheAccountKeyToItsNumericValue() {
            // Text and never a numeric type: an integral component would drop the leading zeros that
            // the byte-equivalence criterion compares directly, turning eleven characters into one.
            BillPaymentResponse carried = roundTrip(populated());

            assertThat(carried.accountId()).isNotEqualTo("1").isNotEqualTo("00000000001 ");
            assertThat(jsonOf(populated()))
                    .contains("\"accountId\":\"00000000001\"")
                    .doesNotContain("\"accountId\":1");
        }

        @Test
        @DisplayName("round-trips an account key untrimmed, padding and all")
        void roundTripsAnAccountKeyUntrimmed() {
            // Legacy space padding is the value, so a padded echo crosses the boundary untouched. A
            // bound measures without altering: it never trims, pads, re-cases or normalises.
            String padded = "  padded   ";

            assertThat(padded).hasSize(ACCOUNT_ID_WIDTH);
            assertThat(roundTrip(new BillPaymentResponse(padded, null, null, null, null, null, null,
                    null, null, null, "  message  ", false, false, null, null, null))
                    .accountId()).isEqualTo(padded);
            assertThat(roundTrip(new BillPaymentResponse(padded, null, null, null, null, null, null,
                    null, null, null, "  message  ", false, false, null, null, null))
                    .errorMessage()).isEqualTo("  message  ");
        }

        @Test
        @DisplayName("round-trips the confirmation answer without folding its case")
        void roundTripsTheConfirmationAnswerWithoutFoldingItsCase() {
            // One character of text rather than a boolean: the program distinguishes seven cases in a
            // single ordered evaluation and only the last is rejected, so a value that is neither an
            // affirmative, a negative nor a blank has to stay reportable - and which letter case the
            // operator typed has to survive.
            assertThat(roundTrip(populated()).confirm()).isEqualTo("y").isNotEqualTo("Y");
            assertThat(withConfirm("Y").confirm()).isEqualTo("Y").isNotEqualTo("y");
            assertThat(roundTrip(withConfirm("n")).confirm()).isEqualTo("n").isNotEqualTo("N");
            assertThat(roundTrip(withConfirm("Q")).confirm()).isEqualTo("Q");
        }

        @Test
        @DisplayName("round-trips a blank and an empty confirmation answer unchanged")
        void roundTripsABlankAndAnEmptyConfirmationAnswer() {
            assertThat(roundTrip(withConfirm(" ")).confirm()).isEqualTo(" ").hasSize(CONFIRM_WIDTH);
            assertThat(roundTrip(withConfirm("")).confirm()).isEqualTo("").isEmpty();
            assertThat(roundTrip(withConfirm(null)).confirm()).isNull();
            // No derived boolean stands beside it: the answer travels only as this one character.
            assertThat(wireKeysOf(withConfirm("y")))
                    .containsExactly("confirm", "paymentAccepted", "generalError");
        }

        @Test
        @DisplayName("round-trips the sixteen-character transaction identifier as text")
        void roundTripsTheSixteenCharacterTransactionIdentifierAsText() {
            // Produced server-side as the highest existing key plus one, browsed backward from the
            // high value and seeded at line 488 so that the first identifier issued against an empty
            // table is sixteen characters of leading zeros ending in one. No database sequence and no
            // surrogate key: a sequence diverges permanently after the first gap, and a rolled-back
            // payment guarantees a gap.
            assertThat(FIRST_TRANSACTION_ID).hasSize(TRANSACTION_ID_WIDTH);

            BillPaymentResponse carried = roundTrip(populated());

            assertThat(carried.newTransactionId())
                    .isEqualTo(FIRST_TRANSACTION_ID)
                    .hasSize(TRANSACTION_ID_WIDTH)
                    .isNotEqualTo("1");
            assertThat(payloadOf(populated()).get("newTransactionId").isTextual()).isTrue();
            assertThat(jsonOf(populated()))
                    .contains("\"newTransactionId\":\"0000000000000001\"")
                    .doesNotContain("\"newTransactionId\":1");
        }

        @Test
        @DisplayName("round-trips the four header values the screen showed, as characters only")
        void roundTripsTheFourHeaderValuesAsCharactersOnly() {
            // The date and the time are carried as the characters the screen displayed. No date or
            // time type and no formatting appears anywhere in the contract or in this test.
            BillPaymentResponse carried = roundTrip(populated());

            assertThat(carried.transactionName()).isEqualTo("CB00");
            assertThat(carried.programName()).isEqualTo("COBIL00C");
            assertThat(carried.title01()).isEqualTo("CardDemo bill payment");
            assertThat(carried.title02()).isEqualTo("Pay the full balance");
            assertThat(carried.currentDate()).isEqualTo("06/10/22");
            assertThat(carried.currentTime()).isEqualTo("19:27:53");
            assertThat(payloadOf(populated()).get("currentDate").isTextual()).isTrue();
            assertThat(payloadOf(populated()).get("currentTime").isTextual()).isTrue();
        }

        @Test
        @DisplayName("carries the two focus-field identities the program actually focuses")
        void carriesTheTwoFocusFieldIdentities() {
            // An identity only: not a cursor row, not a column, not a negative sentinel and not an
            // attribute. It travels independently of which check failed, because the program decides
            // it per arm.
            assertThat(BillPaymentResponse.ACCOUNT_ID_FIELD_ID)
                    .isEqualTo("ACTIDIN")
                    .hasSize(FOCUS_HINT_WIDTH);
            assertThat(BillPaymentResponse.CONFIRM_FIELD_ID)
                    .isEqualTo("CONFIRM")
                    .hasSize(FOCUS_HINT_WIDTH);
            assertThat(roundTrip(populated()).focusScreenFieldId())
                    .isEqualTo(BillPaymentResponse.CONFIRM_FIELD_ID);
            assertThat(roundTrip(withMessage(NOTHING_TO_PAY_TEXT, true)).focusScreenFieldId())
                    .isNull();
        }

        @Test
        @DisplayName("carries no synthesised description, merchant, type, category or source value")
        void carriesNoSynthesisedDescriptionMerchantTypeCategoryOrSourceValue() {
            // The program stamps a description, a merchant identity, a type, a category and a source
            // channel into the record it posts. Those are properties of the posted record, not of
            // this response, and their texts belong to the bill-payment service. None of them may
            // appear here, and none of their literals is written into this test either.
            assertThat(wireKeysOf(populated()))
                    .containsExactlyInAnyOrderElementsOf(WIRE_KEYS_IN_DECLARATION_ORDER);
            assertThat(jsonOf(populated()))
                    .doesNotContain("\"description\"")
                    .doesNotContain("\"transactionDescription\"")
                    .doesNotContain("\"merchantName\"")
                    .doesNotContain("\"merchantId\"")
                    .doesNotContain("\"merchantCity\"")
                    .doesNotContain("\"merchantZip\"")
                    .doesNotContain("\"transactionType\"")
                    .doesNotContain("\"transactionCategory\"")
                    .doesNotContain("\"source\"")
                    .doesNotContain("\"transactionSource\"")
                    .doesNotContain("\"transactionAmount\"")
                    .doesNotContain("\"originTimestamp\"")
                    .doesNotContain("\"processedTimestamp\"");
        }

        @Test
        @DisplayName("carries no second balance, no card verification value and no lock token")
        void carriesNoSecondBalanceNoCardVerificationValueAndNoLockToken() {
            // One balance, because the screen has one balance field and the amount paid is always the
            // whole of it. Optimistic locking lives on the entity and never on a contract, and the
            // legacy design has no card verification value or masking to reproduce.
            assertThat(jsonOf(populated()))
                    .doesNotContain("\"resultingBalance\"")
                    .doesNotContain("\"newBalance\"")
                    .doesNotContain("\"remainingBalance\"")
                    .doesNotContain("\"paymentBalance\"")
                    .doesNotContain("\"cvv\"")
                    .doesNotContain("\"cardVerification\"")
                    .doesNotContain("\"version\"")
                    .doesNotContain("\"etag\"")
                    .doesNotContain("\"rowVersion\"")
                    .doesNotContain("\"lockToken\"");
        }

        /**
         * A response carrying one confirmation answer and nothing else.
         *
         * @param confirm the operator's answer, which may be {@code null}, blank or empty
         * @return a response carrying only that answer
         */
        private BillPaymentResponse withConfirm(String confirm) {
            return new BillPaymentResponse(null, null, confirm, null, null, null, null, null, null,
                    null, null, false, false, null, null, null);
        }
    }

    @Nested
    @DisplayName("a maximum length is the only constraint, and nothing else ever fires")
    class TheValidationPolicy {

        @Test
        @DisplayName("reports no violation for a wholly absent response")
        void reportsNoViolationForAWhollyAbsentResponse() {
            // No presence, blank, pattern, digit or range constraint exists anywhere, and each would
            // be wrong for a verified reason: every echoed field is deliberately blanked on success at
            // line 524, so a required component would refuse the very state the legacy produces.
            assertThat(violationsOf(allAbsent())).isEmpty();
            assertThat(violatedPropertiesOf(allAbsent())).isEmpty();
        }

        @Test
        @DisplayName("reports no violation for a zero balance")
        void reportsNoViolationForAZeroBalance() {
            assertThat(violatedPropertiesOf(withBalance(ZERO_BALANCE))).isEmpty();
        }

        @Test
        @DisplayName("reports no violation for a negative balance")
        void reportsNoViolationForANegativeBalance() {
            assertThat(violatedPropertiesOf(withBalance(NEGATIVE_BALANCE))).isEmpty();
            assertThat(violatedPropertiesOf(withBalance(WIDEST_NEGATIVE_BALANCE))).isEmpty();
        }

        @Test
        @DisplayName("places no character or numeric bound on the balance at all")
        void placesNoCharacterOrNumericBoundOnTheBalance() {
            // The map's balance display width is terminal geometry, not a scale, a precision or a
            // formatting hint, so no length constraint models it. The widest negative balance renders
            // as fourteen characters and still reports nothing, which is what proves the absence.
            assertThat(WIDEST_NEGATIVE_BALANCE.toPlainString()).hasSize(14);
            assertThat(violatedPropertiesOf(withBalance(WIDEST_NEGATIVE_BALANCE))).isEmpty();
            assertThat(violatedPropertiesOf(withBalance(WIDEST_BALANCE))).isEmpty();
            assertThat(violatedPropertiesOf(withBalance(new BigDecimal("0.01")))).isEmpty();
        }

        @Test
        @DisplayName("reports no violation for a fully populated response")
        void reportsNoViolationForAFullyPopulatedResponse() {
            assertThat(violatedPropertiesOf(populated())).isEmpty();
        }

        @Test
        @DisplayName("reports no violation for any of the fourteen published texts")
        void reportsNoViolationForAnyPublishedText() {
            assertThat(EVERY_PUBLISHED_TEXT).allSatisfy(text ->
                    assertThat(violatedPropertiesOf(withMessage(text, true))).isEmpty());
            assertThat(violatedPropertiesOf(withMessage(assembledSuccessMessage(), false)))
                    .isEmpty();
        }

        @Test
        @DisplayName("bounds the message at the 78 characters the map declares")
        void boundsTheMessageAtSeventyEight() {
            assertThat(violatedPropertiesOf(withMessage(textOfLength(MESSAGE_WIDTH), true)))
                    .isEmpty();
            assertThat(violatedPropertiesOf(withMessage(textOfLength(MESSAGE_WIDTH + 1), true)))
                    .containsExactly("errorMessage");
        }

        @Test
        @DisplayName("bounds the account key at the 11 characters the map declares")
        void boundsTheAccountKeyAtEleven() {
            assertThat(violatedPropertiesOf(withAccountId(textOfLength(ACCOUNT_ID_WIDTH))))
                    .isEmpty();
            assertThat(violatedPropertiesOf(withAccountId(textOfLength(ACCOUNT_ID_WIDTH + 1))))
                    .containsExactly("accountId");
        }

        @Test
        @DisplayName("bounds the confirmation answer at the single character the map declares")
        void boundsTheConfirmationAnswerAtOne() {
            assertThat(violatedPropertiesOf(new BillPaymentResponse(null, null,
                    textOfLength(CONFIRM_WIDTH), null, null, null, null, null, null, null, null,
                    false, false, null, null, null))).isEmpty();
            assertThat(violatedPropertiesOf(new BillPaymentResponse(null, null,
                    textOfLength(CONFIRM_WIDTH + 1), null, null, null, null, null, null, null, null,
                    false, false, null, null, null))).containsExactly("confirm");
        }

        @Test
        @DisplayName("bounds the transaction identifier at the 16 characters the record declares")
        void boundsTheTransactionIdentifierAtSixteen() {
            assertThat(violatedPropertiesOf(new BillPaymentResponse(null, null, null,
                    textOfLength(TRANSACTION_ID_WIDTH), null, null, null, null, null, null, null,
                    false, false, null, null, null))).isEmpty();
            assertThat(violatedPropertiesOf(new BillPaymentResponse(null, null, null,
                    textOfLength(TRANSACTION_ID_WIDTH + 1), null, null, null, null, null, null, null,
                    false, false, null, null, null))).containsExactly("newTransactionId");
        }

        @Test
        @DisplayName("bounds the focus hint at the seven characters a generated map name allows")
        void boundsTheFocusHintAtSeven() {
            assertThat(violatedPropertiesOf(withFocusHint(textOfLength(FOCUS_HINT_WIDTH))))
                    .isEmpty();
            assertThat(violatedPropertiesOf(withFocusHint(textOfLength(FOCUS_HINT_WIDTH + 1))))
                    .containsExactly("focusScreenFieldId");
            // Both published identities fit inside that ceiling.
            assertThat(violatedPropertiesOf(
                    withFocusHint(BillPaymentResponse.ACCOUNT_ID_FIELD_ID))).isEmpty();
            assertThat(violatedPropertiesOf(
                    withFocusHint(BillPaymentResponse.CONFIRM_FIELD_ID))).isEmpty();
        }

        @Test
        @DisplayName("leaves the next route unbounded, because the route vocabulary is not its own")
        void leavesTheNextRouteUnbounded() {
            BillPaymentResponse longRoute = new BillPaymentResponse(null, null, null, null, null,
                    null, null, null, null, null, null, false, false, null, textOfLength(512), null);

            assertThat(violatedPropertiesOf(longRoute)).isEmpty();
            assertThat(roundTrip(longRoute).nextRoute()).hasSize(512);
        }

        @Test
        @DisplayName("reports every over-long component at once rather than stopping at the first")
        void reportsEveryOverLongComponentAtOnce() {
            // Which is precisely why no validation constraint may replace the program's own cascade:
            // that cascade is ordered and stops at the first failure, whereas these are reported
            // together and in no defined order. The one summary message is selected by the service.
            BillPaymentResponse overLong = new BillPaymentResponse(
                    textOfLength(ACCOUNT_ID_WIDTH + 1), null, textOfLength(CONFIRM_WIDTH + 1),
                    textOfLength(TRANSACTION_ID_WIDTH + 1), null, null, null, null, null, null,
                    textOfLength(MESSAGE_WIDTH + 1), false, false,
                    textOfLength(FOCUS_HINT_WIDTH + 1), null, null);

            assertThat(violatedPropertiesOf(overLong)).containsExactly(
                    "accountId", "confirm", "errorMessage", "focusScreenFieldId",
                    "newTransactionId");
        }

        /**
         * A response carrying one account key and nothing else.
         *
         * @param accountId the echoed account key
         * @return a response carrying only that key
         */
        private BillPaymentResponse withAccountId(String accountId) {
            return new BillPaymentResponse(accountId, null, null, null, null, null, null, null, null,
                    null, null, false, false, null, null, null);
        }

        /**
         * A response carrying one focus hint and nothing else.
         *
         * @param focusScreenFieldId the identity of the field focus belongs on
         * @return a response carrying only that hint
         */
        private BillPaymentResponse withFocusHint(String focusScreenFieldId) {
            return new BillPaymentResponse(null, null, null, null, null, null, null, null, null,
                    null, null, false, false, focusScreenFieldId, null, null);
        }
    }

    @Nested
    @DisplayName("the wire shape publishes exactly the screen contract")
    class TheWireShape {

        @Test
        @DisplayName("publishes exactly the sixteen components, each under its own name")
        void publishesExactlyTheSixteenComponents() {
            // This one assertion is the component inventory. A payload whose top-level key set is
            // exactly these sixteen names cannot carry a seventeenth serialisable component, so every
            // absence asserted elsewhere in this class rests on it - and it is established without
            // any run-time metadata enquiry.
            assertThat(wireKeysOf(populated()))
                    .hasSize(16)
                    .containsExactlyElementsOf(WIRE_KEYS_IN_DECLARATION_ORDER);
        }

        @Test
        @DisplayName("omits an absent component rather than publishing a null")
        void omitsAnAbsentComponentRatherThanPublishingNull() {
            assertThat(wireKeysOf(allAbsent())).containsExactly("paymentAccepted", "generalError");
            assertThat(jsonOf(allAbsent())).doesNotContain("null");
        }

        @Test
        @DisplayName("publishes both flags even when both are false")
        void publishesBothFlagsEvenWhenBothAreFalse() {
            JsonNode payload = payloadOf(allAbsent());

            assertThat(payload.get("paymentAccepted").asBoolean()).isFalse();
            assertThat(payload.get("generalError").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("tolerates an unknown incoming property")
        void toleratesAnUnknownIncomingProperty() throws JsonProcessingException {
            // A client may echo a property this contract does not consume, so an unknown name is
            // ignored rather than rejected.
            BillPaymentResponse read = moduleEquivalentMapper().readValue(
                    "{\"accountId\":\"00000000001\",\"aPropertyThisContractDoesNotDeclare\":\"x\"}",
                    BillPaymentResponse.class);

            assertThat(read.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(read.currentBalance()).isNull();
            assertThat(read.generalError()).isFalse();
        }

        @Test
        @DisplayName("publishes the message as one textual value rather than a list")
        void publishesTheMessageAsOneTextualValueRatherThanAList() {
            JsonNode message = payloadOf(populated()).get("errorMessage");

            assertThat(message.isTextual()).isTrue();
            assertThat(message.isArray()).isFalse();
            assertThat(message.isObject()).isFalse();
            assertThat(jsonOf(populated()))
                    .doesNotContain("\"errorMessages\"")
                    .doesNotContain("\"messages\"")
                    .doesNotContain("\"fieldErrors\"");
        }

        @Test
        @DisplayName("carries the next route as opaque text it never interprets")
        void carriesTheNextRouteAsOpaqueTextItNeverInterprets() {
            // Declarative data only. The client drives the next call and the server forwards nothing,
            // so the route vocabulary belongs to the navigation service: no route table, no route
            // enumeration and no dispatch appears here, which is why a value that names no real route
            // is carried unchanged rather than refused.
            String notARoute = "this-names-no-route-at-all";

            assertThat(roundTrip(populated()).nextRoute()).isEqualTo("/api/menu");
            assertThat(roundTrip(withNextRoute(notARoute)).nextRoute()).isEqualTo(notARoute);
            assertThat(payloadOf(withNextRoute(notARoute)).get("nextRoute").isTextual()).isTrue();
            assertThat(violatedPropertiesOf(withNextRoute(notARoute))).isEmpty();
        }

        @Test
        @DisplayName("publishes none of the superseded component spellings")
        void publishesNoneOfTheSupersededComponentSpellings() {
            assertThat(jsonOf(populated()))
                    .doesNotContain("\"route\":")
                    .doesNotContain("\"navigation\":")
                    .doesNotContain("\"fieldToFocus\"")
                    .doesNotContain("\"screenTitle1\"")
                    .doesNotContain("\"screenTitle2\"")
                    .doesNotContain("\"titleLine1\"")
                    .doesNotContain("\"titleLine2\"");
        }

        @Test
        @DisplayName("is a plain contract body and not a problem document")
        void isAPlainContractBodyAndNotAProblemDocument() {
            // The richer machine-readable problem shape is deliberately off for this contract: a
            // failure travels as the one summary message plus the explicit flag, exactly as the
            // screen presented it.
            String json = jsonOf(withMessage(UNABLE_TO_ADD_TEXT, true));

            assertThat(json)
                    .doesNotContain("\"type\":")
                    .doesNotContain("\"title\":")
                    .doesNotContain("\"status\":")
                    .doesNotContain("\"detail\":")
                    .doesNotContain("\"instance\":");
            assertThat(wireKeysOf(withMessage(UNABLE_TO_ADD_TEXT, true)))
                    .containsExactly("errorMessage", "paymentAccepted", "generalError");
        }

        @Test
        @DisplayName("carries no platform response code, reason code or diagnostic trace")
        void carriesNoPlatformResponseCodeReasonCodeOrDiagnosticTrace() {
            // The legacy writes a platform response and reason code to its diagnostic channel on six
            // failure arms. A contract body carries none of that, nor a trace, a failure class name,
            // an internal path or a schema name.
            String json = jsonOf(withMessage(UNABLE_TO_ADD_TEXT, true));

            assertThat(json)
                    .doesNotContain("\"responseCode\"")
                    .doesNotContain("\"reasonCode\"")
                    .doesNotContain("\"stackTrace\"")
                    .doesNotContain("\"exception\"")
                    .doesNotContain("\"cause\"")
                    .doesNotContain("\"sqlState\"");
        }

        /**
         * A response carrying one next route and nothing else.
         *
         * @param nextRoute the declarative next route
         * @return a response carrying only that route
         */
        private BillPaymentResponse withNextRoute(String nextRoute) {
            return new BillPaymentResponse(null, null, null, null, null, null, null, null, null,
                    null, null, false, false, null, nextRoute, null);
        }
    }

    @Nested
    @DisplayName("the navigation state is carried, not re-implemented")
    class TheNavigationStateIsCarried {

        @Test
        @DisplayName("carries the echoed navigation state as its own declared type")
        void carriesTheEchoedNavigationStateAsItsOwnDeclaredType() {
            // The assignment is the proof: the component is declared as the navigation contract
            // itself, so none of its sixteen fields is restated here. Its own accessors are used for
            // every assertion, because its rendering withholds the identifiers it carries.
            NavigationContext carried = roundTrip(populated()).navigationContext();

            assertThat(carried).isEqualTo(navigation());
            assertThat(payloadOf(populated()).get("navigationContext").isObject()).isTrue();
        }

        @Test
        @DisplayName("round-trips the navigation identifiers with their leading zeros")
        void roundTripsTheNavigationIdentifiersWithTheirLeadingZeros() {
            NavigationContext carried = roundTrip(populated()).navigationContext();

            assertThat(carried.accountId())
                    .isEqualTo(ACCOUNT_ID)
                    .hasSize(NavigationContext.ACCOUNT_ID_LENGTH)
                    .isNotEqualTo("1");
            assertThat(carried.customerId())
                    .isEqualTo("000000011")
                    .hasSize(NavigationContext.CUSTOMER_ID_LENGTH)
                    .isNotEqualTo("11");
            assertThat(carried.cardNumber()).hasSize(NavigationContext.CARD_NUMBER_LENGTH);
            assertThat(carried.userId()).isEqualTo("USERID01");
        }

        @Test
        @DisplayName("carries the re-entry program context, which gates field-level decoration")
        void carriesTheReEntryProgramContext() {
            NavigationContext carried = roundTrip(populated()).navigationContext();

            assertThat(carried.programContext())
                    .isEqualTo(NavigationContext.ProgramContext.REENTER);
            assertThat(carried.reEntry()).isTrue();
            assertThat(carried.firstEntry()).isFalse();
        }

        @Test
        @DisplayName("carries the transaction and program names of the screen it came from")
        void carriesTheTransactionAndProgramNamesOfTheScreenItCameFrom() {
            NavigationContext carried = roundTrip(populated()).navigationContext();

            assertThat(carried.fromTransactionId())
                    .isEqualTo("CB00")
                    .hasSize(NavigationContext.TRANSACTION_ID_LENGTH);
            assertThat(carried.fromProgram()).isEqualTo("COBIL00C");
            assertThat(carried.lastMap()).isEqualTo("COBIL0A");
            assertThat(carried.lastMapset()).isEqualTo("COBIL00");
        }

        @Test
        @DisplayName("accepts a wholly absent navigation state, which routes to sign-on")
        void acceptsAWhollyAbsentNavigationState() {
            assertThat(allAbsent().navigationContext()).isNull();
            assertThat(roundTrip(withNavigation(NavigationContext.empty())).navigationContext())
                    .isEqualTo(NavigationContext.empty());
            assertThat(violatedPropertiesOf(withNavigation(NavigationContext.empty()))).isEmpty();
        }

        /**
         * A response carrying one navigation state and nothing else.
         *
         * @param navigationContext the echoed navigation state
         * @return a response carrying only that state
         */
        private BillPaymentResponse withNavigation(NavigationContext navigationContext) {
            return new BillPaymentResponse(null, null, null, null, null, null, null, null, null,
                    null, null, false, false, null, null, navigationContext);
        }
    }

    @Nested
    @DisplayName("the rendering withholds what it must, and the value semantics are the record's")
    class TheRenderingAndValueSemantics {

        @Test
        @DisplayName("names the type so a diagnostic identifies what it is looking at")
        void namesTheTypeSoADiagnosticIdentifiesIt() {
            assertThat(populated().toString())
                    .startsWith("BillPaymentResponse[")
                    .endsWith("]");
        }

        @Test
        @DisplayName("withholds the account key, the balance and the confirmation answer")
        void withholdsTheAccountKeyTheBalanceAndTheConfirmationAnswer() {
            String rendered = populated().toString();

            assertThat(rendered)
                    .contains("accountId=" + REDACTED)
                    .contains("currentBalance=" + REDACTED)
                    .contains("confirm=" + REDACTED)
                    .doesNotContain("accountId=" + ACCOUNT_ID)
                    .doesNotContain("currentBalance=1234.56")
                    .doesNotContain("confirm=y")
                    .doesNotContain("1234.56");
        }

        @Test
        @DisplayName("withholds the account key from the whole rendering, not only from its label")
        void withholdsTheAccountKeyFromTheWholeRendering() {
            // Asserted here against identifiers that cannot overlap, so the absence holds over the
            // entire string rather than only under the label.
            //
            // Why a second fixture is needed at all: the identifier issued for the first payment
            // against an empty table is fifteen zeros and a one, and an account key of ten zeros and
            // a one is a character sequence inside it. The rendering legitimately retains that
            // identifier and legitimately withholds that account key, so a whole-string search for
            // the key would find the retained identifier and report a disclosure that has not
            // happened. That is a coincidence of two digit strings, not a leak, and the response
            // below removes it by pairing a key ending in two ones with an identifier ending in a
            // four and a two.
            BillPaymentResponse distinctIdentifiers = new BillPaymentResponse("00000000011",
                    BALANCE, "y", "0000000000000042", "CB00", "CardDemo bill payment", "06/10/22",
                    "COBIL00C", "Pay the full balance", "19:27:53", CONFIRM_PROMPT_TEXT, false,
                    false, BillPaymentResponse.CONFIRM_FIELD_ID, "/api/menu", null);

            assertThat("0000000000000042").doesNotContain("00000000011");
            assertThat(distinctIdentifiers.toString())
                    .contains("accountId=" + REDACTED)
                    .contains("newTransactionId=0000000000000042")
                    .doesNotContain("00000000011")
                    .doesNotContain("1234.56");
        }

        @Test
        @DisplayName("retains what a diagnostic needs to locate the same attempt again")
        void retainsWhatADiagnosticNeedsToLocateTheSameAttempt() {
            String rendered = populated().toString();

            assertThat(rendered)
                    .contains("newTransactionId=" + FIRST_TRANSACTION_ID)
                    .contains("paymentAccepted=false")
                    .contains("generalError=false")
                    .contains("focusScreenFieldId=" + BillPaymentResponse.CONFIRM_FIELD_ID)
                    .contains("nextRoute=/api/menu")
                    .contains("errorMessage=" + CONFIRM_PROMPT_TEXT)
                    .contains("transactionName=CB00")
                    .contains("programName=COBIL00C");
        }

        @Test
        @DisplayName("withholds the navigation state rather than relying on its own rendering")
        void withholdsTheNavigationStateRatherThanRelyingOnItsOwnRendering() {
            assertThat(populated().toString())
                    .contains("navigationContext=" + REDACTED)
                    .doesNotContain("0000111122223333")
                    .doesNotContain("000000011");
        }

        @Test
        @DisplayName("renders safely when every optional value is absent")
        void rendersSafelyWhenEveryOptionalValueIsAbsent() {
            assertThat(allAbsent().toString())
                    .startsWith("BillPaymentResponse[accountId=" + REDACTED)
                    .contains("currentBalance=" + REDACTED)
                    .contains("navigationContext=" + REDACTED)
                    .contains("errorMessage=null");
        }

        @Test
        @DisplayName("compares every component by value and hashes consistently with that")
        void comparesEveryComponentByValueAndHashesConsistently() {
            assertThat(populated()).isEqualTo(populated()).hasSameHashCodeAs(populated());
            assertThat(allAbsent()).isEqualTo(allAbsent()).hasSameHashCodeAs(allAbsent());
            assertThat(populated()).isNotEqualTo(allAbsent());
            assertThat(populated()).isNotEqualTo(null);
            assertThat(populated()).isNotEqualTo("BillPaymentResponse");
        }

        @Test
        @DisplayName("distinguishes two responses that differ only by their error flag")
        void distinguishesTwoResponsesThatDifferOnlyByTheirErrorFlag() {
            assertThat(withMessage(CONFIRM_PROMPT_TEXT, false))
                    .isNotEqualTo(withMessage(CONFIRM_PROMPT_TEXT, true));
            assertThat(withBalance(BALANCE)).isNotEqualTo(withBalance(ZERO_BALANCE));
            assertThat(withMessage(ACCT_ID_EMPTY_TEXT, true))
                    .isNotEqualTo(withMessage(NOTHING_TO_PAY_TEXT, true));
        }

        @Test
        @DisplayName("changes nothing it transports, however often it is read")
        void changesNothingItTransportsHoweverOftenItIsRead() {
            // Immutability shown by construction rather than by any metadata enquiry: every component
            // is read, the instance is rendered, hashed, compared and serialised, and every component
            // is then read again and found identical. A record's components cannot be reassigned, so
            // there is no setter for a mutation to travel through.
            BillPaymentResponse response = populated();
            String accountId = response.accountId();
            BigDecimal balance = response.currentBalance();
            String confirm = response.confirm();
            String transactionId = response.newTransactionId();
            String message = response.errorMessage();
            String focus = response.focusScreenFieldId();
            String route = response.nextRoute();
            NavigationContext navigation = response.navigationContext();

            response.toString();
            response.hashCode();
            assertThat(response).isEqualTo(populated());
            jsonOf(response);
            roundTrip(response);

            assertThat(response.accountId()).isEqualTo(accountId).isSameAs(accountId);
            assertThat(response.currentBalance()).isEqualTo(balance).isSameAs(balance);
            assertThat(response.currentBalance().scale()).isEqualTo(2);
            assertThat(response.confirm()).isEqualTo(confirm).isSameAs(confirm);
            assertThat(response.newTransactionId()).isEqualTo(transactionId);
            assertThat(response.errorMessage()).isEqualTo(message);
            assertThat(response.transactionName()).isEqualTo("CB00");
            assertThat(response.title01()).isEqualTo("CardDemo bill payment");
            assertThat(response.currentDate()).isEqualTo("06/10/22");
            assertThat(response.programName()).isEqualTo("COBIL00C");
            assertThat(response.title02()).isEqualTo("Pay the full balance");
            assertThat(response.currentTime()).isEqualTo("19:27:53");
            assertThat(response.paymentAccepted()).isFalse();
            assertThat(response.generalError()).isFalse();
            assertThat(response.focusScreenFieldId()).isEqualTo(focus);
            assertThat(response.nextRoute()).isEqualTo(route);
            assertThat(response.navigationContext()).isEqualTo(navigation).isSameAs(navigation);
        }

        @Test
        @DisplayName("changes nothing on the wire, where every withheld value still travels")
        void changesNothingOnTheWireWhereEveryWithheldValueStillTravels() {
            // The rendering withholds; the contract does not. A client still receives the account key,
            // the balance, the answer and the navigation state in full.
            JsonNode payload = payloadOf(populated());

            assertThat(payload.get("accountId").asText()).isEqualTo(ACCOUNT_ID);
            assertThat(payload.get("confirm").asText()).isEqualTo("y");
            assertThat(payload.get("navigationContext").isObject()).isTrue();
            assertThat(payload.toString()).doesNotContain(REDACTED);
            assertThat(jsonOf(populated())).contains("1234.56");
        }
    }
}
