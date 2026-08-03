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

import com.carddemo.domain.enums.TransactionSourceType;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link TransactionAddResponse}, the response body of legacy CICS transaction
 * {@code CT02}, whose behaviour lives in {@code app/cbl/COTRN02C.cbl} (783 lines, 18 paragraphs) and
 * whose field contract lives in the generated symbolic map {@code app/cpy-bms/COTRN02.CPY}.
 *
 * <p>A pure unit test. No application context, no servlet environment, no container, no database and
 * no mocking: the type under test is a record, so every assertion below constructs one directly. The
 * serialized-form assertions use a locally built mapper configured from
 * {@code src/main/resources/application.yml} rather than an injected one, so no context has to start
 * for the wire shape to be checked.
 *
 * <p><strong>Every name used here was read out of the production sources.</strong> The twenty-seven
 * component names and their accessors come from {@link TransactionAddResponse}; the two per-field
 * states come from {@link ErrorResponse.FieldState}; the first-entry and re-entry states come from
 * {@link NavigationContext.ProgramContext}; and the three ten-character channel labels come from
 * {@link TransactionSourceType}. Nothing is inferred from a naming pattern.
 *
 * <p><strong>Nothing here inspects the type at run time.</strong> Where a claim is about a static
 * type - that the amount is an exact decimal, that a date is opaque text, that the channel label is
 * a raw string rather than an enumerated value - the claim is proved by assigning the accessor's
 * result to a local variable of that declared type, so the compiler enforces it and the assertion
 * cannot drift away from the contract. No run-time type inspection, no component enumeration and no
 * field or annotation lookup appears anywhere in this file: a check performed that way would pass
 * against a contract the compiler had never agreed to. Immutability is likewise demonstrated by
 * construction: state is supplied, operated on and then re-read.
 *
 * <h2>What this file pins</h2>
 *
 * <p><strong>The fourteen measured message texts.</strong> Each is transcribed from the screen text
 * the program emits at the cited line of {@code app/cbl/COTRN02C.cbl} and is checked at its measured
 * length, byte for byte, untrimmed and un-case-folded. The transcriptions are held independently of
 * the production constants and then compared against them, so a drift on either side fails.
 *
 * <p><strong>The composed success text and its two consecutive spaces.</strong> The success arm
 * clears the screen, marks the message line as favourable and then joins three literals around the
 * new identifier. The first literal ends with a space and the second begins with one, so the joined
 * text carries two adjacent spaces after the first full stop. That is the exact byte sequence the
 * terminal displayed and it is contract, not defect. The expectation is built here by concatenating
 * the four pieces in order; no production formatter, mapper or codec is used as an oracle, and no
 * whitespace is collapsed, trimmed or re-spaced anywhere.
 *
 * <p><strong>The success-is-not-an-error asymmetry.</strong> The success arm marks the message line
 * favourably while every failure arm marks it adversely, so a populated message does not imply a
 * failure. The general-error indicator is therefore its own explicit component, and the tests prove
 * it is not derived from the message's presence, emptiness or length, nor from the presence of
 * per-field errors.
 *
 * <h2>Widths that must never be unified</h2>
 *
 * <p>The description is sixty characters on this map, against twenty-six on the transaction-list map
 * and one hundred in the persisted record at {@code app/cpy/CVTRA05Y.cpy} line 9; the merchant name
 * is thirty against fifty; the merchant city twenty-five against fifty; the two dates ten characters
 * against twenty-six-character stamps. The summary message is seventy-eight characters here, where
 * only the two card maps use eighty. Each divergence is asserted at this map's own width.
 *
 * <p>Provenance for every citation: repository checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Recorded here as documentation only; no
 * identifier is embedded as a constant, and no line of legacy source is reproduced - only widths,
 * counts, line citations and the operator-visible contract texts, which are interface contract
 * rather than source text.
 */
@DisplayName("TransactionAddResponse :: response contract of legacy transaction CT02")
class TransactionAddResponseTest {

    // -------------------------------------------------------------------------------------------
    // Values at the exact measured widths of app/cpy-bms/COTRN02.CPY. Each is the width of the
    // output item at the cited line, which mirrors the input item field for field.
    // -------------------------------------------------------------------------------------------

    /**
     * Sixteen characters, the generated key produced as the highest existing key plus one.
     *
     * <p>Deliberately chosen so that no other fixture's digits appear inside it. A key whose tail
     * happened to repeat the merchant identifier would make the withholding assertion below report a
     * disclosure that had not occurred, and the assertion is more valuable kept strict than loosened.
     */
    private static final String TRANSACTION_ID = "0000000000000315";

    /** The very first key the system can ever issue: sixteen characters, fifteen leading zeros. */
    private static final String FIRST_EVER_TRANSACTION_ID = "0000000000000001";

    /** Eleven characters - ACTIDINO at COTRN02.CPY:188. Leading zeros are part of the value. */
    private static final String ACCOUNT_ID = "00000000001";

    /** Sixteen characters - CARDNINO at COTRN02.CPY:194. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** Two characters - TTYPCDO at COTRN02.CPY:200. */
    private static final String TYPE_CODE = "01";

    /** Four characters - TCATCDO at COTRN02.CPY:206. Never the single digit two. */
    private static final String CATEGORY_CODE = "0002";

    /** Ten characters - TRNSRCO at COTRN02.CPY:212. Raw and space padded. */
    private static final String SOURCE_POS_TERMINAL = "POS TERM  ";

    /** Sixty characters - TDESCO at COTRN02.CPY:218. This map's width, not the record's hundred. */
    private static final String DESCRIPTION_AT_FULL_WIDTH =
            "GROCERIES AT STORE 42 WITH TRAILING FILLER TO SIXTY CHARS   ";

    /** Ten characters - TORIGDTO at COTRN02.CPY:230. Opaque text, never a date type. */
    private static final String ORIGINATION_DATE = "2022-06-10";

    /** Ten characters - TPROCDTO at COTRN02.CPY:236. Opaque text. */
    private static final String PROCESSING_DATE = "2022-06-11";

    /** Ten spaces: a legitimate value for either date field that must survive unchanged. */
    private static final String ALL_SPACE_DATE = "          ";

    /** Nine characters - MIDO at COTRN02.CPY:242. Leading zeros are part of the value. */
    private static final String MERCHANT_ID = "000000042";

    /** Thirty characters - MNAMEO at COTRN02.CPY:248. This map's width, not the record's fifty. */
    private static final String MERCHANT_NAME_AT_FULL_WIDTH = "SMITH HARDWARE AND SUPPLY CO. ";

    /** Twenty-five characters - MCITYO at COTRN02.CPY:254. This map's width, not fifty. */
    private static final String MERCHANT_CITY_AT_FULL_WIDTH = "SEATTLE WASHINGTON       ";

    /** Ten characters - MZIPO at COTRN02.CPY:260. */
    private static final String MERCHANT_ZIP = "98101-0042";

    /** One character - CONFIRMO at COTRN02.CPY:266. Lower case, and it stays lower case. */
    private static final String CONFIRMATION_LOWER_CASE = "y";

    /** Four characters - TRNNAMEO at COTRN02.CPY:152. An identifier despite the item's name. */
    private static final String TRANSACTION_NAME = "CT02";

    /** Forty characters - TITLE01O at COTRN02.CPY:158, carried space padded to its full width. */
    private static final String TITLE_LINE_ONE = "AWS Mainframe Modernization             ";

    /** Eight characters - CURDATEO at COTRN02.CPY:164. Opaque text in the header's own shape. */
    private static final String CURRENT_DATE = "06/10/22";

    /** Eight characters - PGMNAMEO at COTRN02.CPY:170. */
    private static final String PROGRAM_NAME = "COTRN02C";

    /** Forty characters - TITLE02O at COTRN02.CPY:176. */
    private static final String TITLE_LINE_TWO = "CardDemo                                ";

    /** Eight characters - CURTIMEO at COTRN02.CPY:182. Opaque text; never a time type. */
    private static final String CURRENT_TIME = "19:27:53";

    /** Seven characters, the widest of the field identifiers this map declares. */
    private static final String FOCUS_FIELD_ID = "ACTIDIN";

    /** The route is a REST path carried as opaque data, with no legacy fixed width. */
    private static final String NEXT_ROUTE = "/api/transactions";

    /** Scale two, negative: the seeded data holds operator-originated returns as well as sales. */
    private static final BigDecimal AMOUNT_NEGATIVE = new BigDecimal("-123.45");

    /** Scale two, positive. */
    private static final BigDecimal AMOUNT_POSITIVE = new BigDecimal("123.45");

    /** Scale two, zero: distinct from an absent amount and it stays at scale two. */
    private static final BigDecimal AMOUNT_ZERO = new BigDecimal("0.00");

    /** The widest value the record field can hold: nine integer digits and two decimals. */
    private static final BigDecimal AMOUNT_WIDEST = new BigDecimal("999999999.99");

    /** The fixed stand-in the diagnostic rendering writes over each withheld component. */
    private static final String REDACTED = "***REDACTED***";

    /**
     * The two adjacent spaces the composed success text carries after its first full stop. Held as
     * its own constant so the assertion that looks for them cannot be misread as a typographic slip.
     */
    private static final String TWO_CONSECUTIVE_SPACES = "  ";

    /**
     * The whole composed success text for the first key the system can ever issue, written out as a
     * single literal. This is the independent oracle: it is not assembled by any production type,
     * and it is not derived from the fragment constants either, so a change to any fragment fails
     * here rather than silently agreeing with itself.
     */
    private static final String FIRST_EVER_SUCCESS_TEXT =
            "Transaction added successfully.  Your Tran ID is 0000000000000001.";

    /**
     * The same text with its two adjacent spaces collapsed into one. Never a valid value: it exists
     * only so a test can assert the carried text is not this.
     */
    private static final String WHITESPACE_COLLAPSED_SUCCESS_TEXT =
            "Transaction added successfully. Your Tran ID is 0000000000000001.";

    // -------------------------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------------------------

    /**
     * One of the fourteen operator-visible texts the program emits, transcribed independently of the
     * production constant it is then compared against.
     *
     * @param sourceLine         the line of {@code app/cbl/COTRN02C.cbl} that emits the text
     * @param measuredLength     the measured character length of the text
     * @param transcribedText    the text as transcribed from that line
     * @param productionConstant the constant {@link TransactionAddResponse} publishes for it
     */
    private record MessageLiteral(int sourceLine,
                                  int measuredLength,
                                  String transcribedText,
                                  String productionConstant) {

        @Override
        public String toString() {
            return "COTRN02C line " + sourceLine + ", " + measuredLength + " characters";
        }
    }

    /**
     * The fourteen measured texts, each paired with the constant the response publishes for it.
     *
     * <p>The transcriptions are literals here rather than references to the production constants, so
     * the comparison between the two columns is a genuine cross-check: a drift in either the
     * transcription or the constant fails, where a single shared source would agree with itself.
     *
     * @return the fourteen texts in the order their emitting lines appear in the program
     */
    private static Stream<MessageLiteral> measuredMessageLiterals() {
        return Stream.of(
                new MessageLiteral(184, 40, "Invalid value. Valid values are (Y/N)...",
                        TransactionAddResponse.MESSAGE_CONFIRM_INVALID),
                new MessageLiteral(199, 29, "Account ID must be Numeric...",
                        TransactionAddResponse.MESSAGE_ACCOUNT_ID_NOT_NUMERIC),
                new MessageLiteral(213, 30, "Card Number must be Numeric...",
                        TransactionAddResponse.MESSAGE_CARD_NUMBER_NOT_NUMERIC),
                new MessageLiteral(226, 41, "Account or Card Number must be entered...",
                        TransactionAddResponse.MESSAGE_KEY_NOT_ENTERED),
                new MessageLiteral(254, 27, "Type CD can NOT be empty...",
                        TransactionAddResponse.MESSAGE_TYPE_CODE_EMPTY),
                new MessageLiteral(278, 26, "Amount can NOT be empty...",
                        TransactionAddResponse.MESSAGE_AMOUNT_EMPTY),
                new MessageLiteral(314, 32, "Merchant Zip can NOT be empty...",
                        TransactionAddResponse.MESSAGE_MERCHANT_ZIP_EMPTY),
                new MessageLiteral(325, 26, "Type CD must be Numeric...",
                        TransactionAddResponse.MESSAGE_TYPE_CODE_NOT_NUMERIC),
                new MessageLiteral(345, 39, "Amount should be in format -99999999.99",
                        TransactionAddResponse.MESSAGE_AMOUNT_FORMAT),
                new MessageLiteral(360, 40, "Orig Date should be in format YYYY-MM-DD",
                        TransactionAddResponse.MESSAGE_ORIGINATION_DATE_FORMAT),
                new MessageLiteral(401, 31, "Orig Date - Not a valid date...",
                        TransactionAddResponse.MESSAGE_ORIGINATION_DATE_INVALID),
                new MessageLiteral(421, 31, "Proc Date - Not a valid date...",
                        TransactionAddResponse.MESSAGE_PROCESSING_DATE_INVALID),
                new MessageLiteral(593, 23, "Account ID NOT found...",
                        TransactionAddResponse.MESSAGE_ACCOUNT_ID_NOT_FOUND),
                new MessageLiteral(626, 24, "Card Number NOT found...",
                        TransactionAddResponse.MESSAGE_CARD_NUMBER_NOT_FOUND));
    }

    /** The three ten-character channel labels, each carried raw and space padded. */
    private static Stream<TransactionSourceType> channelLabels() {
        return Stream.of(TransactionSourceType.POS_TERM, TransactionSourceType.OPERATOR,
                TransactionSourceType.SYSTEM);
    }

    /** The re-entry navigation state: the only state in which field decoration applies. */
    private static NavigationContext reEntryNavigation() {
        return new NavigationContext(TRANSACTION_NAME, PROGRAM_NAME, TRANSACTION_NAME, PROGRAM_NAME,
                "USER0001", "U", NavigationContext.ProgramContext.REENTER, "000000042", "MARY",
                "ANN", "SMITH", ACCOUNT_ID, "Y", CARD_NUMBER, "COTRN2A", "COTRN02");
    }

    /** The first-entry navigation state, which suppresses field decoration. */
    private static NavigationContext firstEntryNavigation() {
        return reEntryNavigation().withFirstEntry();
    }

    /**
     * A response carrying every echoed value at its exact measured width.
     *
     * @return a fully populated response reporting no failure
     */
    private static TransactionAddResponse fullyPopulated() {
        return new TransactionAddResponse(TRANSACTION_ID, ACCOUNT_ID, CARD_NUMBER, TYPE_CODE,
                CATEGORY_CODE, SOURCE_POS_TERMINAL, DESCRIPTION_AT_FULL_WIDTH, AMOUNT_NEGATIVE,
                ORIGINATION_DATE, PROCESSING_DATE, MERCHANT_ID, MERCHANT_NAME_AT_FULL_WIDTH,
                MERCHANT_CITY_AT_FULL_WIDTH, MERCHANT_ZIP, CONFIRMATION_LOWER_CASE,
                TRANSACTION_NAME, TITLE_LINE_ONE, CURRENT_DATE, PROGRAM_NAME, TITLE_LINE_TWO,
                CURRENT_TIME, TransactionAddResponse.MESSAGE_CONFIRM_PROMPT, false, List.of(),
                FOCUS_FIELD_ID, NEXT_ROUTE, reEntryNavigation());
    }

    /**
     * A response carrying one summary text and one general-error polarity, with everything else
     * absent - the shape of every arm of the write-outcome branch.
     *
     * @param message      the single summary text, or {@code null} when there is none
     * @param generalError whether the response reports a failure of the request as a whole
     * @return the response
     */
    private static TransactionAddResponse withMessage(String message, boolean generalError) {
        return new TransactionAddResponse(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, message,
                generalError, List.of(), null, null, null);
    }

    /**
     * A response carrying one amount and nothing else, for the decimal-shape assertions.
     *
     * @param amount the amount, or {@code null}
     * @return the response
     */
    private static TransactionAddResponse withAmount(BigDecimal amount) {
        return new TransactionAddResponse(null, null, null, null, null, null, null, amount, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, false,
                List.of(), null, null, null);
    }

    /**
     * A response carrying one field-error collection and nothing else.
     *
     * @param fieldErrors the per-field errors, or {@code null} to exercise the normalisation
     * @return the response
     */
    private static TransactionAddResponse withFieldErrors(List<ErrorResponse.FieldError>
            fieldErrors) {
        return new TransactionAddResponse(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, false,
                fieldErrors, null, null, null);
    }

    /**
     * A response carrying one navigation state and nothing else.
     *
     * @param navigationContext the echoed navigation state, or {@code null}
     * @return the response
     */
    private static TransactionAddResponse withNavigation(NavigationContext navigationContext) {
        return new TransactionAddResponse(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, false,
                List.of(), null, null, navigationContext);
    }

    /** A response with every reference component absent, for the validation-policy assertions. */
    private static TransactionAddResponse allAbsent() {
        return withMessage(null, false);
    }

    /**
     * Builds a mapper configured exactly as the module configures its own, reading the six settings
     * from {@code src/main/resources/application.yml} lines 101 to 119.
     *
     * <p>Local rather than injected on purpose: this is a unit test, so no application context
     * starts, and a mapper built here cannot pick up an incidental customisation from one.
     *
     * @return a mapper whose wire behaviour matches the running module's
     */
    private static ObjectMapper moduleEquivalentMapper() {
        return JsonMapper.builder()
                .defaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL,
                        JsonInclude.Include.NON_NULL))
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .build();
    }

    /**
     * Validates a response with the platform's own default validator, not a framework-managed one.
     *
     * @param response the response to validate
     * @return every constraint violation the declared annotations produce
     */
    private static Set<ConstraintViolation<TransactionAddResponse>> violationsOf(
            TransactionAddResponse response) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(response);
        }
    }

    /**
     * Writes a response and reads it back through the module-equivalent mapper.
     *
     * @param response the response to round trip
     * @return the response as the wire produced and then reproduced it
     * @throws JsonProcessingException if the mapper rejects the payload, which is itself a failure
     */
    private static TransactionAddResponse jsonRoundTrip(TransactionAddResponse response)
            throws JsonProcessingException {
        ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readValue(mapper.writeValueAsString(response), TransactionAddResponse.class);
    }

    // ===========================================================================================
    // THE FOURTEEN MEASURED MESSAGE TEXTS
    //
    // Each is an external interface contract: operators read them and downstream tooling matches
    // on them, so the punctuation, the capitalisation and the trailing dots are all load bearing.
    // ===========================================================================================

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("measuredMessageLiterals")
    @DisplayName("each measured text is exactly as long as it was measured")
    void eachMeasuredTextIsExactlyAsLongAsItWasMeasured(MessageLiteral literal) {
        assertThat(literal.transcribedText())
                .as("the text emitted at COTRN02C line " + literal.sourceLine())
                .hasSize(literal.measuredLength());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("measuredMessageLiterals")
    @DisplayName("each published constant reproduces its transcribed text byte for byte")
    void eachPublishedConstantReproducesItsTranscribedText(MessageLiteral literal) {
        assertThat(literal.productionConstant())
                .as("the constant published for COTRN02C line " + literal.sourceLine())
                .isEqualTo(literal.transcribedText())
                .hasSize(literal.measuredLength());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("measuredMessageLiterals")
    @DisplayName("each measured text round trips through the summary component untouched")
    void eachMeasuredTextRoundTripsThroughTheSummaryComponent(MessageLiteral literal) {
        TransactionAddResponse response = withMessage(literal.transcribedText(), true);

        assertThat(response.message())
                .as("the summary carried for COTRN02C line " + literal.sourceLine())
                .isEqualTo(literal.transcribedText())
                .hasSize(literal.measuredLength());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("measuredMessageLiterals")
    @DisplayName("each measured text survives the wire unchanged, uncollapsed and un-case-folded")
    void eachMeasuredTextSurvivesTheWireUnchanged(MessageLiteral literal)
            throws JsonProcessingException {
        TransactionAddResponse bound = jsonRoundTrip(withMessage(literal.transcribedText(), true));

        assertThat(bound.message())
                .as("the summary reproduced for COTRN02C line " + literal.sourceLine())
                .isEqualTo(literal.transcribedText())
                .hasSize(literal.measuredLength());
    }

    @Test
    @DisplayName("there are exactly fourteen measured texts")
    void thereAreExactlyFourteenMeasuredTexts() {
        assertThat(measuredMessageLiterals()).hasSize(14);
    }

    @Test
    @DisplayName("every measured text fits the seventy-eight-character summary width of this map")
    void everyMeasuredTextFitsTheSummaryWidthOfThisMap() {
        assertThat(measuredMessageLiterals().map(MessageLiteral::transcribedText))
                .as("ERRMSG is X(78) at COTRN02.CPY:272, not the X(80) the two card maps use")
                .allSatisfy(text -> assertThat(text.length()).isLessThanOrEqualTo(78));
        assertThat(violationsOf(withMessage(TransactionAddResponse.MESSAGE_KEY_NOT_ENTERED, true)))
                .as("the longest of the fourteen, at forty-one characters, still validates cleanly")
                .isEmpty();
    }

    @Test
    @DisplayName("the capitalised negation of the emptiness texts is preserved, never normalised")
    void theCapitalisedNegationOfTheEmptinessTextsIsPreserved() {
        assertThat(withMessage(TransactionAddResponse.MESSAGE_TYPE_CODE_EMPTY, true).message())
                .contains("can NOT be")
                .doesNotContain("can not be", "CAN NOT BE");
        assertThat(withMessage(TransactionAddResponse.MESSAGE_AMOUNT_EMPTY, true).message())
                .contains("can NOT be");
        assertThat(withMessage(TransactionAddResponse.MESSAGE_MERCHANT_ZIP_EMPTY, true).message())
                .contains("can NOT be");
    }

    @Test
    @DisplayName("the lower-case word and the spaced separator of the date-validity texts survive")
    void theLowerCaseWordAndSpacedSeparatorOfTheDateValidityTextsSurvive() {
        assertThat(withMessage(TransactionAddResponse.MESSAGE_ORIGINATION_DATE_INVALID, true)
                .message())
                .isEqualTo("Orig Date - Not a valid date...")
                .contains(" - ")
                .contains("valid date")
                .doesNotContain("Valid Date");
        assertThat(withMessage(TransactionAddResponse.MESSAGE_PROCESSING_DATE_INVALID, true)
                .message())
                .isEqualTo("Proc Date - Not a valid date...");
    }

    @Test
    @DisplayName("the display shape inside the amount text is carried as text, never as a pattern")
    void theDisplayShapeInsideTheAmountTextIsCarriedAsText() {
        TransactionAddResponse response =
                withMessage(TransactionAddResponse.MESSAGE_AMOUNT_FORMAT, true);

        assertThat(response.message())
                .as("the shape named in the text is part of the text and of nothing else")
                .isEqualTo("Amount should be in format -99999999.99")
                .hasSize(39);
        assertThat(response.amount())
                .as("naming a display shape does not populate, format or imply an amount")
                .isNull();
    }

    // ===========================================================================================
    // EXACTLY ONE SUMMARY TEXT - the legacy cascade is ordered and stops at the first failure
    // ===========================================================================================

    @Test
    @DisplayName("carries one summary text and not a collection of them")
    void carriesOneSummaryTextAndNotACollectionOfThem() {
        String onlySummary = fullyPopulated().message();

        assertThat(onlySummary)
                .as("the component's declared type is a single text, enforced by this assignment")
                .isEqualTo(TransactionAddResponse.MESSAGE_CONFIRM_PROMPT);
    }

    @Test
    @DisplayName("several field errors still yield exactly one summary text")
    void severalFieldErrorsStillYieldExactlyOneSummaryText() {
        List<ErrorResponse.FieldError> threeErrors = List.of(
                new ErrorResponse.FieldError("typeCode", "TTYPCD",
                        ErrorResponse.FieldState.MISSING),
                new ErrorResponse.FieldError("categoryCode", "TCATCD",
                        ErrorResponse.FieldState.INVALID),
                new ErrorResponse.FieldError("merchantId", "MID", ErrorResponse.FieldState.MISSING));

        TransactionAddResponse response = new TransactionAddResponse(null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, TransactionAddResponse.MESSAGE_TYPE_CODE_EMPTY, true, threeErrors,
                "TTYPCD", null, reEntryNavigation());

        String onlySummary = response.message();

        assertThat(onlySummary).isEqualTo("Type CD can NOT be empty...");
        assertThat(response.fieldErrors()).hasSize(3);
        assertThat(response.hasFieldErrors()).isTrue();
    }

    @Test
    @DisplayName("three of the fourteen texts each round trip byte for byte as the one summary")
    void threeOfTheFourteenTextsEachRoundTripAsTheOneSummary() {
        assertThat(withMessage(TransactionAddResponse.MESSAGE_KEY_NOT_ENTERED, true).message())
                .isEqualTo("Account or Card Number must be entered...")
                .hasSize(41);
        assertThat(withMessage(TransactionAddResponse.MESSAGE_ACCOUNT_ID_NOT_FOUND, true).message())
                .isEqualTo("Account ID NOT found...")
                .hasSize(23);
        assertThat(withMessage(TransactionAddResponse.MESSAGE_CARD_NUMBER_NOT_FOUND, true).message())
                .isEqualTo("Card Number NOT found...")
                .hasSize(24);
    }

    // ===========================================================================================
    // THE COMPOSED SUCCESS TEXT AND ITS TWO CONSECUTIVE SPACES
    //
    // The success arm of app/cbl/COTRN02C.cbl clears the fourteen echoed fields, marks the message
    // line favourably and then joins four pieces: the prefix at line 728, the identifier label at
    // line 730, the new identifier at line 731 and the closing full stop at line 732. Because the
    // prefix ends with a space and the label begins with one, the joined text carries two adjacent
    // spaces after the first full stop. Every expectation below is built by concatenating those
    // pieces here; no production formatter, codec, mapper or builder is used as an oracle.
    // ===========================================================================================

    @Test
    @DisplayName("the success prefix carries exactly one trailing space after its full stop")
    void theSuccessPrefixCarriesExactlyOneTrailingSpace() {
        String prefix = TransactionAddResponse.MESSAGE_SUCCESS_PREFIX;

        assertThat(prefix)
                .isEqualTo("Transaction added successfully. ")
                .hasSize(32)
                .endsWith(". ")
                .doesNotEndWith(".  ")
                .doesNotEndWith(".");
    }

    @Test
    @DisplayName("the identifier label carries one leading and one trailing space")
    void theIdentifierLabelCarriesOneLeadingAndOneTrailingSpace() {
        String label = TransactionAddResponse.MESSAGE_SUCCESS_ID_LABEL;

        assertThat(label)
                .isEqualTo(" Your Tran ID is ")
                .hasSize(17)
                .startsWith(" Y")
                .doesNotStartWith("  ")
                .endsWith("is ")
                .doesNotEndWith("is  ");
    }

    @Test
    @DisplayName("the closing piece is the single full stop that ends the sentence")
    void theClosingPieceIsTheSingleFullStop() {
        assertThat(TransactionAddResponse.MESSAGE_SUCCESS_TERMINATOR).isEqualTo(".").hasSize(1);
    }

    @Test
    @DisplayName("the composed success text carries two consecutive spaces after the first full stop")
    void theComposedSuccessTextCarriesTwoConsecutiveSpaces() {
        String composed = TransactionAddResponse.MESSAGE_SUCCESS_PREFIX
                + TransactionAddResponse.MESSAGE_SUCCESS_ID_LABEL
                + FIRST_EVER_TRANSACTION_ID
                + TransactionAddResponse.MESSAGE_SUCCESS_TERMINATOR;

        TransactionAddResponse response = withMessage(composed, false);

        assertThat(response.message())
                .as("the exact byte sequence the terminal displayed on a successful add")
                .isEqualTo(FIRST_EVER_SUCCESS_TEXT)
                .contains(TWO_CONSECUTIVE_SPACES)
                .contains("successfully." + TWO_CONSECUTIVE_SPACES + "Your")
                .hasSize(66);
    }

    @Test
    @DisplayName("the two consecutive spaces sit between the first full stop and the label")
    void theTwoConsecutiveSpacesSitBetweenTheFullStopAndTheLabel() {
        String composed = TransactionAddResponse.MESSAGE_SUCCESS_PREFIX
                + TransactionAddResponse.MESSAGE_SUCCESS_ID_LABEL
                + FIRST_EVER_TRANSACTION_ID
                + TransactionAddResponse.MESSAGE_SUCCESS_TERMINATOR;

        assertThat(withMessage(composed, false).message())
                .as("the prefix is thirty-two characters, so the pair starts at offset thirty-one")
                .startsWith("Transaction added successfully." + TWO_CONSECUTIVE_SPACES + "Your Tran"
                        + " ID is ");
    }

    @Test
    @DisplayName("the composed success text is never whitespace collapsed, trimmed or re-spaced")
    void theComposedSuccessTextIsNeverWhitespaceCollapsed() {
        String composed = TransactionAddResponse.MESSAGE_SUCCESS_PREFIX
                + TransactionAddResponse.MESSAGE_SUCCESS_ID_LABEL
                + FIRST_EVER_TRANSACTION_ID
                + TransactionAddResponse.MESSAGE_SUCCESS_TERMINATOR;

        TransactionAddResponse response = withMessage(composed, false);

        assertThat(response.message())
                .as("collapsing the pair would shorten the contract text by one byte")
                .isNotEqualTo(WHITESPACE_COLLAPSED_SUCCESS_TEXT)
                .hasSize(WHITESPACE_COLLAPSED_SUCCESS_TEXT.length() + 1);
        assertThat(WHITESPACE_COLLAPSED_SUCCESS_TEXT).doesNotContain(TWO_CONSECUTIVE_SPACES);
    }

    @Test
    @DisplayName("the composed success text survives the wire with both spaces intact")
    void theComposedSuccessTextSurvivesTheWireWithBothSpacesIntact()
            throws JsonProcessingException {
        String composed = TransactionAddResponse.MESSAGE_SUCCESS_PREFIX
                + TransactionAddResponse.MESSAGE_SUCCESS_ID_LABEL
                + FIRST_EVER_TRANSACTION_ID
                + TransactionAddResponse.MESSAGE_SUCCESS_TERMINATOR;

        TransactionAddResponse bound = jsonRoundTrip(withMessage(composed, false));

        assertThat(bound.message())
                .isEqualTo(FIRST_EVER_SUCCESS_TEXT)
                .contains(TWO_CONSECUTIVE_SPACES);
    }

    @Test
    @DisplayName("the composed success text ends with the full stop supplied by the closing piece")
    void theComposedSuccessTextEndsWithTheClosingFullStop() {
        String composed = TransactionAddResponse.MESSAGE_SUCCESS_PREFIX
                + TransactionAddResponse.MESSAGE_SUCCESS_ID_LABEL
                + FIRST_EVER_TRANSACTION_ID
                + TransactionAddResponse.MESSAGE_SUCCESS_TERMINATOR;

        assertThat(withMessage(composed, false).message())
                .endsWith(FIRST_EVER_TRANSACTION_ID + TransactionAddResponse
                        .MESSAGE_SUCCESS_TERMINATOR)
                .endsWith(".");
    }

    @Test
    @DisplayName("a sixteen-character identifier contributes whole, with no internal blank")
    void aSixteenCharacterIdentifierContributesWhole() {
        String composed = TransactionAddResponse.MESSAGE_SUCCESS_PREFIX
                + TransactionAddResponse.MESSAGE_SUCCESS_ID_LABEL
                + FIRST_EVER_TRANSACTION_ID
                + TransactionAddResponse.MESSAGE_SUCCESS_TERMINATOR;

        assertThat(withMessage(composed, false).message())
                .as("an all-digit key has no blank to be delimited at, so all sixteen reach the text")
                .contains("is " + FIRST_EVER_TRANSACTION_ID + ".")
                .doesNotContain(FIRST_EVER_TRANSACTION_ID + " ");
    }

    @Test
    @DisplayName("a shorter identifier contributes space delimited, without its trailing padding")
    void aShorterIdentifierContributesSpaceDelimited() {
        String paddedField = "42              ";
        String delimitedContribution = "42";
        String composed = TransactionAddResponse.MESSAGE_SUCCESS_PREFIX
                + TransactionAddResponse.MESSAGE_SUCCESS_ID_LABEL
                + delimitedContribution
                + TransactionAddResponse.MESSAGE_SUCCESS_TERMINATOR;

        TransactionAddResponse response = new TransactionAddResponse(paddedField, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, composed, false, List.of(), null, null, null);

        assertThat(paddedField)
                .as("the field itself stays sixteen characters wide")
                .hasSize(16);
        assertThat(response.newTransactionId())
                .as("the key component keeps the padding the fixed-width field carries")
                .isEqualTo(paddedField)
                .hasSize(16);
        assertThat(response.message())
                .as("the text takes the key only up to its first blank")
                .isEqualTo("Transaction added successfully.  Your Tran ID is 42.")
                .contains(TWO_CONSECUTIVE_SPACES)
                .doesNotContain("42 ")
                .endsWith("42.");
    }

    @Test
    @DisplayName("the composed success text fits the seventy-eight-character summary width")
    void theComposedSuccessTextFitsTheSummaryWidth() {
        String composed = TransactionAddResponse.MESSAGE_SUCCESS_PREFIX
                + TransactionAddResponse.MESSAGE_SUCCESS_ID_LABEL
                + FIRST_EVER_TRANSACTION_ID
                + TransactionAddResponse.MESSAGE_SUCCESS_TERMINATOR;

        assertThat(composed.length()).isLessThanOrEqualTo(78);
        assertThat(violationsOf(withMessage(composed, false))).isEmpty();
    }

    @Test
    @DisplayName("the three success pieces are published separately and are never pre-joined")
    void theThreeSuccessPiecesArePublishedSeparately() {
        assertThat(TransactionAddResponse.MESSAGE_SUCCESS_PREFIX)
                .as("joining the pieces into one constant would hide the space pair at their join")
                .doesNotContain("Your Tran ID");
        assertThat(TransactionAddResponse.MESSAGE_SUCCESS_ID_LABEL)
                .doesNotContain("Transaction added");
        assertThat(TransactionAddResponse.MESSAGE_SUCCESS_PREFIX
                + TransactionAddResponse.MESSAGE_SUCCESS_ID_LABEL)
                .as("the pair appears only once the first two pieces meet")
                .contains(TWO_CONSECUTIVE_SPACES)
                .hasSize(49);
    }

    // ===========================================================================================
    // THE SUCCESS-IS-NOT-AN-ERROR ASYMMETRY
    //
    // The success arm marks the message line favourably; every failure arm marks it adversely. The
    // terminal attribute that expressed that difference is not modelled here in any form - the
    // distinction is realised solely as an explicit indicator, which is why a populated message
    // says nothing about success or failure on its own.
    // ===========================================================================================

    @Test
    @DisplayName("the composed success text coexists with an indicator of false")
    void theComposedSuccessTextCoexistsWithAnIndicatorOfFalse() {
        String composed = TransactionAddResponse.MESSAGE_SUCCESS_PREFIX
                + TransactionAddResponse.MESSAGE_SUCCESS_ID_LABEL
                + FIRST_EVER_TRANSACTION_ID
                + TransactionAddResponse.MESSAGE_SUCCESS_TERMINATOR;

        TransactionAddResponse response = new TransactionAddResponse(FIRST_EVER_TRANSACTION_ID,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                TRANSACTION_NAME, TITLE_LINE_ONE, CURRENT_DATE, PROGRAM_NAME, TITLE_LINE_TWO,
                CURRENT_TIME, composed, false, List.of(), FOCUS_FIELD_ID, NEXT_ROUTE,
                firstEntryNavigation());

        assertThat(response.message()).isEqualTo(FIRST_EVER_SUCCESS_TEXT).isNotEmpty();
        assertThat(response.generalError())
                .as("a populated summary does not imply a failure")
                .isFalse();
        assertThat(response.newTransactionId()).isEqualTo(FIRST_EVER_TRANSACTION_ID);
        assertThat(response.accountId())
                .as("the success arm clears the fourteen echoed fields before redisplaying")
                .isNull();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("measuredMessageLiterals")
    @DisplayName("each of the fourteen failure texts coexists with an indicator of true")
    void eachFailureTextCoexistsWithAnIndicatorOfTrue(MessageLiteral literal) {
        TransactionAddResponse response = withMessage(literal.transcribedText(), true);

        assertThat(response.message()).isEqualTo(literal.transcribedText());
        assertThat(response.generalError())
                .as("the text emitted at COTRN02C line " + literal.sourceLine())
                .isTrue();
    }

    @Test
    @DisplayName("the same text carries either polarity, so the indicator is not derived from it")
    void theSameTextCarriesEitherPolarity() {
        String sameText = TransactionAddResponse.MESSAGE_DUPLICATE_TRANSACTION_ID;

        assertThat(withMessage(sameText, true).generalError()).isTrue();
        assertThat(withMessage(sameText, false).generalError())
                .as("one text, two polarities: nothing about the text decides the indicator")
                .isFalse();
        assertThat(withMessage(sameText, true).message())
                .isEqualTo(withMessage(sameText, false).message());
    }

    @Test
    @DisplayName("an absent text carries an indicator of true, so absence does not mean success")
    void anAbsentTextCarriesAnIndicatorOfTrue() {
        TransactionAddResponse response = withMessage(null, true);

        assertThat(response.message()).isNull();
        assertThat(response.generalError()).isTrue();
    }

    @Test
    @DisplayName("an empty text carries an indicator of true, so emptiness does not mean success")
    void anEmptyTextCarriesAnIndicatorOfTrue() {
        TransactionAddResponse response = withMessage("", true);

        assertThat(response.message()).isEmpty();
        assertThat(response.generalError()).isTrue();
    }

    @Test
    @DisplayName("a blank text carries an indicator of true and keeps every space it was given")
    void aBlankTextCarriesAnIndicatorOfTrue() {
        TransactionAddResponse response = withMessage(ALL_SPACE_DATE, true);

        assertThat(response.message()).isEqualTo(ALL_SPACE_DATE).hasSize(10);
        assertThat(response.generalError()).isTrue();
    }

    @Test
    @DisplayName("the indicator is not derived from the presence of per-field errors either")
    void theIndicatorIsNotDerivedFromThePresenceOfFieldErrors() {
        List<ErrorResponse.FieldError> oneError = List.of(new ErrorResponse.FieldError("typeCode",
                "TTYPCD", ErrorResponse.FieldState.MISSING));

        TransactionAddResponse errorsWithoutFailure = new TransactionAddResponse(null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, false, oneError, null, null, null);
        TransactionAddResponse failureWithoutErrors = withMessage(
                TransactionAddResponse.MESSAGE_ADD_FAILED, true);

        assertThat(errorsWithoutFailure.hasFieldErrors()).isTrue();
        assertThat(errorsWithoutFailure.generalError()).isFalse();
        assertThat(failureWithoutErrors.hasFieldErrors()).isFalse();
        assertThat(failureWithoutErrors.generalError())
                .as("every arm of the write-outcome branch fails with no per-field error at all")
                .isTrue();
    }

    @Test
    @DisplayName("the confirmation prompt is a re-prompt and not a failure")
    void theConfirmationPromptIsARePromptAndNotAFailure() {
        TransactionAddResponse response = fullyPopulated();

        assertThat(TransactionAddResponse.MESSAGE_CONFIRM_PROMPT)
                .isEqualTo("Confirm to add this transaction...")
                .hasSize(34);
        assertThat(response.message()).isEqualTo(TransactionAddResponse.MESSAGE_CONFIRM_PROMPT);
        assertThat(response.generalError())
                .as("a negative, blank or absent answer is not confirmed yet, not a failure")
                .isFalse();
    }

    @Test
    @DisplayName("the indicator survives the wire at both polarities")
    void theIndicatorSurvivesTheWireAtBothPolarities() throws JsonProcessingException {
        String composed = TransactionAddResponse.MESSAGE_SUCCESS_PREFIX
                + TransactionAddResponse.MESSAGE_SUCCESS_ID_LABEL
                + FIRST_EVER_TRANSACTION_ID
                + TransactionAddResponse.MESSAGE_SUCCESS_TERMINATOR;

        assertThat(jsonRoundTrip(withMessage(composed, false)).generalError()).isFalse();
        assertThat(jsonRoundTrip(withMessage(TransactionAddResponse.MESSAGE_ADD_FAILED, true))
                .generalError()).isTrue();
        assertThat(moduleEquivalentMapper().writeValueAsString(withMessage(composed, false)))
                .as("a primitive indicator is always present, never omitted as absent")
                .contains("\"generalError\":false");
    }

    // ===========================================================================================
    // PER-FIELD ERRORS: THE TWO-STATE CONTRACT
    //
    // The parameterised validation macro at app/cpy/CSSETATY.cpy distinguishes a field left blank
    // from a field filled in wrongly, and its guard also requires the screen to have been
    // re-submitted, so a first submission decorates nothing at all.
    // ===========================================================================================

    @Test
    @DisplayName("the two states are distinguishable and are not two values of one flag")
    void theTwoStatesAreDistinguishableAndNotBooleanBased() {
        ErrorResponse.FieldState missing = ErrorResponse.FieldState.MISSING;
        ErrorResponse.FieldState invalid = ErrorResponse.FieldState.INVALID;

        assertThat(missing)
                .as("a blank field and a badly filled field need different remedies")
                .isNotEqualTo(invalid);
        assertThat(ErrorResponse.FieldState.values())
                .as("exactly two states: a field with no error simply has no entry")
                .containsExactly(missing, invalid)
                .hasSize(2);
        assertThat(missing.name()).isEqualTo("MISSING");
        assertThat(invalid.name()).isEqualTo("INVALID");
    }

    @Test
    @DisplayName("a blank field is never conflated with a badly filled one")
    void aBlankFieldIsNeverConflatedWithABadlyFilledOne() {
        ErrorResponse.FieldError blank = new ErrorResponse.FieldError("categoryCode", "TCATCD",
                ErrorResponse.FieldState.MISSING,
                TransactionAddResponse.MESSAGE_CATEGORY_CODE_EMPTY);
        ErrorResponse.FieldError badlyFilled = new ErrorResponse.FieldError("categoryCode",
                "TCATCD", ErrorResponse.FieldState.INVALID,
                TransactionAddResponse.MESSAGE_CATEGORY_CODE_NOT_NUMERIC);

        TransactionAddResponse response = withFieldErrors(List.of(blank, badlyFilled));

        assertThat(response.fieldErrors())
                .as("the same field in both states stays two distinct entries")
                .containsExactly(blank, badlyFilled)
                .hasSize(2);
        assertThat(response.fieldErrors().get(0).state()).isEqualTo(
                ErrorResponse.FieldState.MISSING);
        assertThat(response.fieldErrors().get(1).state()).isEqualTo(
                ErrorResponse.FieldState.INVALID);
        assertThat(blank).isNotEqualTo(badlyFilled);
    }

    @Test
    @DisplayName("a first submission carries no per-field error at all")
    void aFirstSubmissionCarriesNoFieldError() {
        TransactionAddResponse response = new TransactionAddResponse(null, ACCOUNT_ID, CARD_NUMBER,
                null, null, null, null, null, null, null, null, null, null, null, null,
                TRANSACTION_NAME, TITLE_LINE_ONE, CURRENT_DATE, PROGRAM_NAME, TITLE_LINE_TWO,
                CURRENT_TIME, TransactionAddResponse.MESSAGE_TYPE_CODE_EMPTY, true, List.of(), null,
                null, firstEntryNavigation());

        assertThat(response.navigationContext().firstEntry())
                .as("the macro's guard requires the re-entry state before it decorates anything")
                .isTrue();
        assertThat(response.navigationContext().reEntry()).isFalse();
        assertThat(response.fieldErrors())
                .as("an undecorated first submission still shows its one summary line")
                .isEmpty();
        assertThat(response.hasFieldErrors()).isFalse();
        assertThat(response.message()).isEqualTo("Type CD can NOT be empty...");
    }

    @Test
    @DisplayName("a re-submission may carry per-field errors alongside the one summary")
    void aReSubmissionMayCarryFieldErrors() {
        TransactionAddResponse response = new TransactionAddResponse(null, ACCOUNT_ID, CARD_NUMBER,
                null, CATEGORY_CODE, null, null, null, null, null, null, null, null, null, null,
                TRANSACTION_NAME, TITLE_LINE_ONE, CURRENT_DATE, PROGRAM_NAME, TITLE_LINE_TWO,
                CURRENT_TIME, TransactionAddResponse.MESSAGE_TYPE_CODE_EMPTY, true,
                List.of(new ErrorResponse.FieldError("typeCode", "TTYPCD",
                        ErrorResponse.FieldState.MISSING)),
                "TTYPCD", null, reEntryNavigation());

        assertThat(response.navigationContext().reEntry()).isTrue();
        assertThat(response.fieldErrors()).hasSize(1);
        assertThat(response.fieldErrors().get(0).fieldName()).isEqualTo("typeCode");
        assertThat(response.fieldErrors().get(0).screenFieldId()).isEqualTo("TTYPCD");
        assertThat(response.fieldErrors().get(0).message()).isNull();
        assertThat(response.focusScreenFieldId()).isEqualTo("TTYPCD");
    }

    @Test
    @DisplayName("the focus hint travels independently of the field the summary is about")
    void theFocusHintTravelsIndependentlyOfTheFailingField() {
        TransactionAddResponse response = new TransactionAddResponse(TRANSACTION_ID, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, TransactionAddResponse.MESSAGE_DUPLICATE_TRANSACTION_ID,
                true, List.of(), FOCUS_FIELD_ID, null, null);

        assertThat(response.message())
                .as("the singular verb is a deviation from ordinary English and is contract")
                .isEqualTo("Tran ID already exist...");
        assertThat(response.focusScreenFieldId())
                .as("the duplicate-key arm reports the key yet focuses the account-id input")
                .isEqualTo("ACTIDIN")
                .hasSize(7);
    }

    // ===========================================================================================
    // THE FIELD-ERROR COLLECTION IS IMMUTABLE AND NULL TOLERANT
    // ===========================================================================================

    @Test
    @DisplayName("an absent collection becomes an empty immutable one, never null and never a throw")
    void anAbsentCollectionBecomesAnEmptyImmutableOne() {
        assertThatNoException().isThrownBy(() -> withFieldErrors(null));

        List<ErrorResponse.FieldError> normalised = withFieldErrors(null).fieldErrors();

        assertThat(normalised).isNotNull().isEmpty();
        assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(() ->
                normalised.add(new ErrorResponse.FieldError("typeCode", "TTYPCD",
                        ErrorResponse.FieldState.MISSING)));
    }

    @Test
    @DisplayName("the accessor returns an unmodifiable collection")
    void theAccessorReturnsAnUnmodifiableCollection() {
        List<ErrorResponse.FieldError> carried = withFieldErrors(List.of(
                new ErrorResponse.FieldError("merchantId", "MID",
                        ErrorResponse.FieldState.INVALID))).fieldErrors();

        assertThat(carried).hasSize(1);
        assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(() ->
                carried.add(new ErrorResponse.FieldError("merchantName", "MNAME",
                        ErrorResponse.FieldState.MISSING)));
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(carried::clear);
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> carried.remove(0));
    }

    @Test
    @DisplayName("a supplied collection is copied defensively, so later mutation cannot reach it")
    void aSuppliedCollectionIsCopiedDefensively() {
        List<ErrorResponse.FieldError> caller = new ArrayList<>();
        caller.add(new ErrorResponse.FieldError("amount", "TRNAMT",
                ErrorResponse.FieldState.MISSING, TransactionAddResponse.MESSAGE_AMOUNT_EMPTY));

        TransactionAddResponse response = withFieldErrors(caller);
        caller.clear();
        caller.add(new ErrorResponse.FieldError("typeCode", "TTYPCD",
                ErrorResponse.FieldState.INVALID));

        assertThat(response.fieldErrors())
                .as("copy semantics: it holds what it was given, not what the caller now holds")
                .hasSize(1);
        assertThat(response.fieldErrors().get(0).fieldName()).isEqualTo("amount");
        assertThat(response.fieldErrors().get(0).message())
                .isEqualTo("Amount can NOT be empty...");
    }

    @Test
    @DisplayName("a null element is rejected rather than silently dropped")
    void aNullElementIsRejected() {
        List<ErrorResponse.FieldError> withNullElement = new ArrayList<>();
        withNullElement.add(null);

        assertThatNullPointerException()
                .as("an entry with no state is meaningless and hiding it would hide an error")
                .isThrownBy(() -> withFieldErrors(withNullElement));
    }

    @Test
    @DisplayName("the collection is always present on the wire, emitted empty rather than omitted")
    void theCollectionIsAlwaysPresentOnTheWire() throws JsonProcessingException {
        String json = moduleEquivalentMapper().writeValueAsString(withFieldErrors(null));

        assertThat(json)
                .as("a client never has to test the collection for absence")
                .contains("\"fieldErrors\":[]");
    }

    @Test
    @DisplayName("field errors survive the wire with their state and screen identifier intact")
    void fieldErrorsSurviveTheWireIntact() throws JsonProcessingException {
        TransactionAddResponse bound = jsonRoundTrip(withFieldErrors(List.of(
                new ErrorResponse.FieldError("merchantCity", "MCITY",
                        ErrorResponse.FieldState.MISSING,
                        TransactionAddResponse.MESSAGE_MERCHANT_CITY_EMPTY))));

        assertThat(bound.fieldErrors()).hasSize(1);
        assertThat(bound.fieldErrors().get(0).state()).isEqualTo(ErrorResponse.FieldState.MISSING);
        assertThat(bound.fieldErrors().get(0).screenFieldId()).isEqualTo("MCITY");
        assertThat(bound.fieldErrors().get(0).message())
                .isEqualTo("Merchant City can NOT be empty...");
    }

    // ===========================================================================================
    // THE AMOUNT IS AN EXACT DECIMAL AT THE RECORD'S OWN SCALE
    //
    // TRAN-AMT at app/cpy/CVTRA05Y.cpy line 10 is a signed zoned quantity with nine integer digits
    // and two decimals. The estate declares no rounding anywhere, so the single place a surplus
    // digit may be discarded is the utility layer's codec - never here. This type refuses a
    // misshapen value instead of repairing one, which is what keeps that place single.
    // ===========================================================================================

    @Test
    @DisplayName("the amount is an exact decimal, never a floating-point value")
    void theAmountIsAnExactDecimal() {
        BigDecimal carried = fullyPopulated().amount();

        assertThat(carried)
                .as("the declared type is enforced by this assignment, not by inspection")
                .isEqualTo(AMOUNT_NEGATIVE);
        assertThat(carried.scale()).isEqualTo(2);
        assertThat(TransactionAddResponse.AMOUNT_SCALE).isEqualTo(2);
        assertThat(TransactionAddResponse.AMOUNT_INTEGER_DIGITS).isEqualTo(9);
    }

    @Test
    @DisplayName("the amount keeps a scale of exactly two across a wire round trip")
    void theAmountKeepsAScaleOfExactlyTwoAcrossTheWire() throws JsonProcessingException {
        BigDecimal bound = jsonRoundTrip(withAmount(AMOUNT_NEGATIVE)).amount();

        assertThat(bound.scale())
                .as("scale is asserted explicitly, because numeric equality would not see it")
                .isEqualTo(TransactionAddResponse.AMOUNT_SCALE);
        assertThat(bound).isEqualTo(AMOUNT_NEGATIVE);
    }

    @Test
    @DisplayName("the amount serialises in plain notation and never in scientific form")
    void theAmountSerialisesInPlainNotation() throws JsonProcessingException {
        ObjectMapper mapper = moduleEquivalentMapper();

        assertThat(mapper.writeValueAsString(withAmount(AMOUNT_WIDEST)))
                .contains("\"amount\":999999999.99")
                .doesNotContain("E+", "e+", "E9");
        assertThat(mapper.writeValueAsString(withAmount(new BigDecimal("10.00"))))
                .as("the trailing zero the record scale requires is not dropped")
                .contains("\"amount\":10.00");
    }

    @Test
    @DisplayName("both signed directions round trip, because the seeded data holds sales and returns")
    void bothSignedDirectionsRoundTrip() throws JsonProcessingException {
        assertThat(jsonRoundTrip(withAmount(AMOUNT_POSITIVE)).amount()).isEqualTo(AMOUNT_POSITIVE);
        assertThat(jsonRoundTrip(withAmount(AMOUNT_NEGATIVE)).amount()).isEqualTo(AMOUNT_NEGATIVE);
        assertThat(jsonRoundTrip(withAmount(AMOUNT_NEGATIVE)).amount().signum()).isNegative();
        assertThat(moduleEquivalentMapper().writeValueAsString(withAmount(AMOUNT_NEGATIVE)))
                .contains("\"amount\":-123.45")
                .doesNotContain("\"amount\":+");
    }

    @Test
    @DisplayName("a scaled zero round trips at scale two and is not normalised away")
    void aScaledZeroRoundTripsAtScaleTwo() throws JsonProcessingException {
        BigDecimal bound = jsonRoundTrip(withAmount(AMOUNT_ZERO)).amount();

        assertThat(bound).isEqualTo(AMOUNT_ZERO);
        assertThat(bound.scale()).isEqualTo(2);
        assertThat(moduleEquivalentMapper().writeValueAsString(withAmount(AMOUNT_ZERO)))
                .contains("\"amount\":0.00");
    }

    @Test
    @DisplayName("an accepted amount is carried through untouched, with no rescaling of any kind")
    void anAcceptedAmountIsCarriedThroughUntouched() {
        BigDecimal supplied = new BigDecimal("-123.45");

        assertThat(withAmount(supplied).amount())
                .as("refusing a misshapen value is not the same as normalising one")
                .isSameAs(supplied);
        assertThat(withAmount(AMOUNT_WIDEST).amount()).isSameAs(AMOUNT_WIDEST);
    }

    @Test
    @DisplayName("a misshapen scale is refused rather than rounded or truncated into shape")
    void aMisshapenScaleIsRefusedRatherThanRepaired() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> withAmount(new BigDecimal("123.4")))
                .withMessageContaining("must carry scale 2")
                .withMessageContaining("its scale is 1");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("a third decimal is rejected, never rounded away")
                .isThrownBy(() -> withAmount(new BigDecimal("123.456")))
                .withMessageContaining("its scale is 3");
    }

    @Test
    @DisplayName("a tenth integer digit is refused, because the record field holds nine")
    void aTenthIntegerDigitIsRefused() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> withAmount(new BigDecimal("1000000000.00")))
                .withMessageContaining("must fit 9 integer digits")
                .withMessageContaining("it needs 10");
        assertThatNoException()
                .as("the widest value the field can hold is accepted")
                .isThrownBy(() -> withAmount(AMOUNT_WIDEST));
        assertThatNoException()
                .as("and so is its negative counterpart")
                .isThrownBy(() -> withAmount(new BigDecimal("-999999999.99")));
    }

    @Test
    @DisplayName("an absent amount is accepted, because the success path blanks every echoed value")
    void anAbsentAmountIsAccepted() throws JsonProcessingException {
        assertThatNoException().isThrownBy(() -> withAmount(null));
        assertThat(withAmount(null).amount()).isNull();
        assertThat(moduleEquivalentMapper().writeValueAsString(withAmount(null)))
                .doesNotContain("\"amount\"");
    }

    // ===========================================================================================
    // THE ECHOED VALUES KEEP THEIR EXACT MEASURED WIDTHS
    //
    // Widths are those of the output items of app/cpy-bms/COTRN02.CPY, which mirror the input items
    // field for field. Four of them deliberately differ from the same logical field elsewhere in
    // the estate, and those divergences are contract rather than defect.
    // ===========================================================================================

    @Test
    @DisplayName("every echoed value round trips at its exact measured width")
    void everyEchoedValueRoundTripsAtItsExactMeasuredWidth() {
        TransactionAddResponse response = fullyPopulated();

        assertThat(response.accountId()).isEqualTo(ACCOUNT_ID).hasSize(11);
        assertThat(response.cardNumber()).isEqualTo(CARD_NUMBER).hasSize(16);
        assertThat(response.typeCode()).isEqualTo(TYPE_CODE).hasSize(2);
        assertThat(response.categoryCode()).isEqualTo(CATEGORY_CODE).hasSize(4);
        assertThat(response.source()).isEqualTo(SOURCE_POS_TERMINAL).hasSize(10);
        assertThat(response.description()).isEqualTo(DESCRIPTION_AT_FULL_WIDTH).hasSize(60);
        assertThat(response.originationDate()).isEqualTo(ORIGINATION_DATE).hasSize(10);
        assertThat(response.processingDate()).isEqualTo(PROCESSING_DATE).hasSize(10);
        assertThat(response.merchantId()).isEqualTo(MERCHANT_ID).hasSize(9);
        assertThat(response.merchantName()).isEqualTo(MERCHANT_NAME_AT_FULL_WIDTH).hasSize(30);
        assertThat(response.merchantCity()).isEqualTo(MERCHANT_CITY_AT_FULL_WIDTH).hasSize(25);
        assertThat(response.merchantZip()).isEqualTo(MERCHANT_ZIP).hasSize(10);
        assertThat(response.confirmationFlag()).isEqualTo(CONFIRMATION_LOWER_CASE).hasSize(1);
        assertThat(response.newTransactionId()).isEqualTo(TRANSACTION_ID).hasSize(16);
    }

    @Test
    @DisplayName("the four divergent widths are this map's own and are never unified with any other")
    void theFourDivergentWidthsAreThisMapsOwn() {
        TransactionAddResponse response = fullyPopulated();

        assertThat(response.description())
                .as("sixty here, twenty-six on the list map, one hundred in the record")
                .hasSize(60);
        assertThat(response.merchantName())
                .as("thirty here against fifty in the record")
                .hasSize(30);
        assertThat(response.merchantCity())
                .as("twenty-five here against fifty in the record")
                .hasSize(25);
        assertThat(response.originationDate())
                .as("ten here against the record's twenty-six-character stamps")
                .hasSize(10);
        assertThat(violationsOf(response))
                .as("every value sits exactly on its bound, so nothing is rejected")
                .isEmpty();
    }

    @Test
    @DisplayName("the summary width is seventy-eight on this map and not the card maps' eighty")
    void theSummaryWidthIsSeventyEightOnThisMap() {
        String seventyEight = "x".repeat(78);
        String seventyNine = "x".repeat(79);

        assertThat(violationsOf(withMessage(seventyEight, true)))
                .as("ERRMSG is X(78) at COTRN02.CPY:272")
                .isEmpty();

        Set<ConstraintViolation<TransactionAddResponse>> beyondTheWidth =
                violationsOf(withMessage(seventyNine, true));

        assertThat(beyondTheWidth)
                .as("a seventy-ninth character is beyond this map's own width")
                .hasSize(1);
        assertThat(beyondTheWidth.iterator().next().getPropertyPath().toString())
                .isEqualTo("message");
    }

    @Test
    @DisplayName("a short value is never padded up and trailing spaces are never trimmed away")
    void aShortValueIsNeverPaddedUpAndTrailingSpacesAreNeverTrimmed() {
        String shortDescription = "COFFEE";
        String trailingSpaces = "COFFEE    ";

        TransactionAddResponse padded = new TransactionAddResponse(null, null, null, null, null,
                null, trailingSpaces, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, false, List.of(), null, null, null);
        TransactionAddResponse unpadded = new TransactionAddResponse(null, null, null, null, null,
                null, shortDescription, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, false, List.of(), null, null, null);

        assertThat(unpadded.description())
                .as("six characters stay six; nothing pads a value up to its bound")
                .isEqualTo("COFFEE")
                .hasSize(6);
        assertThat(padded.description())
                .as("ten characters stay ten; the four trailing spaces are part of the value")
                .isEqualTo(trailingSpaces)
                .hasSize(10)
                .isNotEqualTo(shortDescription);
    }

    @Test
    @DisplayName("the confirmation answer is never case folded")
    void theConfirmationAnswerIsNeverCaseFolded() {
        TransactionAddResponse lowerCase = fullyPopulated();

        assertThat(lowerCase.confirmationFlag())
                .as("a lower-case answer stays lower case; nothing folds it")
                .isEqualTo("y")
                .isNotEqualTo("Y")
                .hasSize(1);
    }

    @Test
    @DisplayName("the confirmation answer is one character and not a two-valued flag")
    void theConfirmationAnswerIsOneCharacterAndNotAFlag() {
        String neitherValue = "Q";

        TransactionAddResponse response = new TransactionAddResponse(null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, neitherValue, null, null,
                null, null, null, null, TransactionAddResponse.MESSAGE_CONFIRM_INVALID, true,
                List.of(), "CONFIRM", null, null);

        assertThat(response.confirmationFlag())
                .as("a third outcome exists, so a two-valued flag could not carry this")
                .isEqualTo("Q");
        assertThat(response.message()).isEqualTo("Invalid value. Valid values are (Y/N)...");
        assertThat(violationsOf(response)).isEmpty();
    }

    // ===========================================================================================
    // CODED VALUES ARE TEXT, SO LEADING ZEROS SURVIVE
    // ===========================================================================================

    @Test
    @DisplayName("every coded value keeps its leading zeros and never becomes a bare digit")
    void everyCodedValueKeepsItsLeadingZeros() {
        TransactionAddResponse response = new TransactionAddResponse(FIRST_EVER_TRANSACTION_ID,
                ACCOUNT_ID, null, null, CATEGORY_CODE, null, null, null, null, null, MERCHANT_ID,
                null, null, null, null, null, null, null, null, null, null, null, false, List.of(),
                null, null, null);

        assertThat(response.accountId()).isEqualTo("00000000001").hasSize(11).isNotEqualTo("1");
        assertThat(response.categoryCode()).isEqualTo("0002").hasSize(4).isNotEqualTo("2");
        assertThat(response.merchantId()).isEqualTo("000000042").hasSize(9).isNotEqualTo("42");
        assertThat(response.newTransactionId())
                .as("the first key the system can ever issue is sixteen characters, not one")
                .isEqualTo("0000000000000001")
                .hasSize(16)
                .isNotEqualTo("1");
    }

    @Test
    @DisplayName("coded values survive the wire as text, quoted and with their zeros intact")
    void codedValuesSurviveTheWireAsText() throws JsonProcessingException {
        TransactionAddResponse response = new TransactionAddResponse(FIRST_EVER_TRANSACTION_ID,
                ACCOUNT_ID, null, null, CATEGORY_CODE, null, null, null, null, null, MERCHANT_ID,
                null, null, null, null, null, null, null, null, null, null, null, false, List.of(),
                null, null, null);

        String json = moduleEquivalentMapper().writeValueAsString(response);

        assertThat(json)
                .contains("\"newTransactionId\":\"0000000000000001\"")
                .contains("\"accountId\":\"00000000001\"")
                .contains("\"categoryCode\":\"0002\"")
                .contains("\"merchantId\":\"000000042\"");
        assertThat(json)
                .as("a bare number would discard the zeros the external width depends on")
                .doesNotContain("\"categoryCode\":2")
                .doesNotContain("\"merchantId\":42")
                .doesNotContain("\"accountId\":1");

        TransactionAddResponse bound = jsonRoundTrip(response);

        assertThat(bound.categoryCode()).isEqualTo("0002");
        assertThat(bound.merchantId()).isEqualTo("000000042");
        assertThat(bound.newTransactionId()).isEqualTo("0000000000000001");
    }

    @Test
    @DisplayName("the identifier is carried as data only; nothing here generates or advances it")
    void theIdentifierIsCarriedAsDataOnly() {
        TransactionAddResponse first = new TransactionAddResponse(FIRST_EVER_TRANSACTION_ID, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, false, List.of(), null, null, null);
        TransactionAddResponse second = new TransactionAddResponse(FIRST_EVER_TRANSACTION_ID, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, false, List.of(), null, null, null);

        assertThat(first.newTransactionId())
                .as("constructing twice issues nothing: the key is supplied, never derived")
                .isEqualTo(second.newTransactionId())
                .isEqualTo(FIRST_EVER_TRANSACTION_ID);
        assertThat(withAmount(null).newTransactionId())
                .as("no key appears where none was supplied, so nothing defaults one in")
                .isNull();
    }

    // ===========================================================================================
    // THE TWO TEN-CHARACTER DATES ARE OPAQUE TEXT
    // ===========================================================================================

    @Test
    @DisplayName("both dates are opaque text carried at ten characters")
    void bothDatesAreOpaqueTextAtTenCharacters() {
        TransactionAddResponse response = fullyPopulated();

        String origination = response.originationDate();
        String processing = response.processingDate();

        assertThat(origination)
                .as("the declared type is text, enforced by this assignment")
                .isEqualTo(ORIGINATION_DATE)
                .hasSize(10);
        assertThat(processing).isEqualTo(PROCESSING_DATE).hasSize(10);
    }

    @Test
    @DisplayName("an all-space date round trips unchanged, never null, never empty, never trimmed")
    void anAllSpaceDateRoundTripsUnchanged() throws JsonProcessingException {
        TransactionAddResponse response = new TransactionAddResponse(null, null, null, null, null,
                null, null, null, ALL_SPACE_DATE, ALL_SPACE_DATE, null, null, null, null, null,
                null, null, null, null, null, null, null, false, List.of(), null, null, null);

        assertThat(response.originationDate())
                .isEqualTo(ALL_SPACE_DATE)
                .hasSize(10)
                .isNotNull()
                .isNotEmpty();
        assertThat(response.processingDate()).isEqualTo(ALL_SPACE_DATE).hasSize(10);

        TransactionAddResponse bound = jsonRoundTrip(response);

        assertThat(bound.originationDate()).isEqualTo(ALL_SPACE_DATE).hasSize(10);
        assertThat(bound.processingDate()).isEqualTo(ALL_SPACE_DATE).hasSize(10);
    }

    @Test
    @DisplayName("a date the calendar rejects is carried unchanged, because nothing here parses one")
    void aDateTheCalendarRejectsIsCarriedUnchanged() {
        String impossible = "2022-02-31";

        TransactionAddResponse response = new TransactionAddResponse(null, null, null, null, null,
                null, null, null, impossible, impossible, null, null, null, null, null, null, null,
                null, null, null, null, TransactionAddResponse.MESSAGE_ORIGINATION_DATE_INVALID,
                true, List.of(), "TORIGDT", null, null);

        assertThat(response.originationDate())
                .as("calendar validity is the shared date utility's business, never this type's")
                .isEqualTo(impossible)
                .hasSize(10);
        assertThat(response.message()).isEqualTo("Orig Date - Not a valid date...");
        assertThat(violationsOf(response))
                .as("no pattern, no digit check and no calendar check fires here")
                .isEmpty();
    }

    // ===========================================================================================
    // THE CHANNEL LABEL IS A RAW PADDED VALUE, NOT AN ENUMERATED ONE
    // ===========================================================================================

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("channelLabels")
    @DisplayName("each ten-character channel label round trips raw and untrimmed")
    void eachChannelLabelRoundTripsRawAndUntrimmed(TransactionSourceType label) {
        String padded = label.getValue();

        TransactionAddResponse response = new TransactionAddResponse(null, null, null, null, null,
                padded, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, false, List.of(), null, null, null);

        String carried = response.source();

        assertThat(carried)
                .as("the declared type is text: the padding is part of the record image")
                .isEqualTo(padded)
                .hasSize(TransactionSourceType.VALUE_LENGTH)
                .hasSize(10);
        assertThat(violationsOf(response)).isEmpty();
    }

    @Test
    @DisplayName("the three channel labels are exactly the padded values the domain declares")
    void theThreeChannelLabelsAreExactlyThePaddedDomainValues() {
        assertThat(TransactionSourceType.POS_TERM.getValue()).isEqualTo("POS TERM  ").hasSize(10);
        assertThat(TransactionSourceType.OPERATOR.getValue()).isEqualTo("OPERATOR  ").hasSize(10);
        assertThat(TransactionSourceType.SYSTEM.getValue())
                .as("the mixed case of the system label and its four trailing spaces are contract")
                .isEqualTo("System    ")
                .hasSize(10)
                .isNotEqualTo("SYSTEM    ");
    }

    @Test
    @DisplayName("the point-of-sale label survives the wire with its two trailing spaces")
    void thePointOfSaleLabelSurvivesTheWireWithItsTrailingSpaces()
            throws JsonProcessingException {
        TransactionAddResponse bound = jsonRoundTrip(fullyPopulated());

        assertThat(bound.source()).isEqualTo("POS TERM  ").hasSize(10).isNotEqualTo("POS TERM");
        assertThat(moduleEquivalentMapper().writeValueAsString(fullyPopulated()))
                .contains("\"source\":\"POS TERM  \"");
    }

    // ===========================================================================================
    // THE NAVIGATION STATE IS CARRIED, NOT RE-IMPLEMENTED
    // ===========================================================================================

    @Test
    @DisplayName("the navigation state is carried whole and unchanged")
    void theNavigationStateIsCarriedWholeAndUnchanged() {
        NavigationContext supplied = reEntryNavigation();

        TransactionAddResponse response = withNavigation(supplied);
        NavigationContext carried = response.navigationContext();

        assertThat(carried)
                .as("echoed client state, carried across rather than rebuilt")
                .isSameAs(supplied)
                .isEqualTo(supplied);
        assertThat(carried.accountId())
                .as("a leading-zero identifier survives the carry unchanged")
                .isEqualTo("00000000001")
                .hasSize(11);
        assertThat(carried.cardNumber()).isEqualTo(CARD_NUMBER).hasSize(16);
        assertThat(carried.userId()).isEqualTo("USER0001").hasSize(8);
        assertThat(carried.fromTransactionId()).isEqualTo("CT02");
        assertThat(carried.lastMap()).isEqualTo("COTRN2A");
        assertThat(carried.lastMapset()).isEqualTo("COTRN02");
        assertThat(carried.programContext())
                .isEqualTo(NavigationContext.ProgramContext.REENTER);
    }

    @Test
    @DisplayName("the navigation state survives the wire with its identifiers intact")
    void theNavigationStateSurvivesTheWire() throws JsonProcessingException {
        TransactionAddResponse bound = jsonRoundTrip(withNavigation(reEntryNavigation()));

        assertThat(bound.navigationContext()).isEqualTo(reEntryNavigation());
        assertThat(bound.navigationContext().accountId()).isEqualTo("00000000001");
        assertThat(bound.navigationContext().reEntry()).isTrue();
    }

    @Test
    @DisplayName("an absent navigation state is accepted and omitted rather than defaulted")
    void anAbsentNavigationStateIsAcceptedAndOmitted() throws JsonProcessingException {
        TransactionAddResponse response = withNavigation(null);

        assertThat(response.navigationContext())
                .as("nothing substitutes the wholly empty state for an absent one")
                .isNull();
        assertThat(moduleEquivalentMapper().writeValueAsString(response))
                .doesNotContain("\"navigationContext\"");
    }

    @Test
    @DisplayName("the wholly empty navigation state is carried as a first entry")
    void theWhollyEmptyNavigationStateIsCarriedAsAFirstEntry() {
        TransactionAddResponse response = withNavigation(NavigationContext.empty());

        assertThat(response.navigationContext().firstEntry()).isTrue();
        assertThat(response.fieldErrors())
                .as("no decoration applies before a screen has been re-submitted")
                .isEmpty();
    }

    // ===========================================================================================
    // VALIDATION POLICY: A MAXIMUM LENGTH IS ESSENTIALLY THE ONLY CONSTRAINT
    //
    // The legacy cascade is ordered, message bearing and first-error-wins, which unordered
    // constraint validation cannot express. Reproducing it declaratively would change which single
    // text an operator sees, so nothing here is mandatory, pattern matched, digit checked or range
    // bounded. A maximum length measures and never alters, so spaces and blanks survive it.
    // ===========================================================================================

    @Test
    @DisplayName("a wholly absent response produces no constraint violation at all")
    void aWhollyAbsentResponseProducesNoViolation() {
        assertThat(violationsOf(allAbsent()))
                .as("nothing is mandatory: the success path deliberately blanks every echoed value")
                .isEmpty();
    }

    @Test
    @DisplayName("an empty text produces no violation, so no non-blank or non-empty rule exists")
    void anEmptyTextProducesNoViolation() {
        TransactionAddResponse allEmpty = new TransactionAddResponse("", "", "", "", "", "", "",
                null, "", "", "", "", "", "", "", "", "", "", "", "", "", "", false, List.of(), "",
                "", null);

        assertThat(violationsOf(allEmpty)).isEmpty();
        assertThat(allEmpty.message()).isEmpty();
        assertThat(allEmpty.accountId()).isEmpty();
    }

    @Test
    @DisplayName("a blank-filled value produces no violation and keeps every space it carries")
    void aBlankFilledValueProducesNoViolation() {
        TransactionAddResponse blankFilled = new TransactionAddResponse(null, null, null, null,
                null, null, null, null, ALL_SPACE_DATE, ALL_SPACE_DATE, null, null, null,
                ALL_SPACE_DATE, " ", null, null, null, null, null, null, null, false, List.of(),
                null, null, null);

        assertThat(violationsOf(blankFilled))
                .as("a length constraint measures and never alters")
                .isEmpty();
        assertThat(blankFilled.merchantZip()).isEqualTo(ALL_SPACE_DATE).hasSize(10);
        assertThat(blankFilled.confirmationFlag()).isEqualTo(" ").hasSize(1);
    }

    @Test
    @DisplayName("a non-numeric coded value produces no violation, so no pattern rule exists")
    void aNonNumericCodedValueProducesNoViolation() {
        TransactionAddResponse nonNumeric = new TransactionAddResponse("NOT-A-KEY-16CHRS",
                "ABCDEFGHIJK", "NOTACARDNUMBER!!", "AB", "WXYZ", null, null, null, "NOT-A-DATE",
                "??????????", "ABCDEFGHI", null, null, "ZZZZZZZZZZ", "?", null, null, null, null,
                null, null, null, false, List.of(), null, null, null);

        assertThat(violationsOf(nonNumeric))
                .as("the ordered, message-bearing cascade belongs to the service, not to annotations")
                .isEmpty();
        assertThat(nonNumeric.accountId()).isEqualTo("ABCDEFGHIJK").hasSize(11);
        assertThat(nonNumeric.categoryCode()).isEqualTo("WXYZ").hasSize(4);
    }

    @Test
    @DisplayName("a negative amount produces no violation, so no minimum or maximum rule exists")
    void aNegativeAmountProducesNoViolation() {
        assertThat(violationsOf(withAmount(new BigDecimal("-999999999.99"))))
                .as("a refund is legitimately negative")
                .isEmpty();
        assertThat(violationsOf(withAmount(AMOUNT_ZERO))).isEmpty();
        assertThat(violationsOf(withAmount(AMOUNT_WIDEST))).isEmpty();
    }

    @Test
    @DisplayName("only a value beyond its own measured width is rejected, and only that value")
    void onlyAValueBeyondItsMeasuredWidthIsRejected() {
        TransactionAddResponse tooWide = new TransactionAddResponse(null, "x".repeat(12), null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, false, List.of(), null, null, null);

        Set<ConstraintViolation<TransactionAddResponse>> violations = violationsOf(tooWide);

        assertThat(violations)
                .as("ACTIDIN is X(11) at COTRN02.CPY:188, so a twelfth character is beyond it")
                .hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString())
                .isEqualTo("accountId");
        assertThat(violationsOf(fullyPopulated()))
                .as("every value sitting exactly on its bound is accepted")
                .isEmpty();
    }

    @Test
    @DisplayName("the route carries no width bound, because no legacy width exists to bound it by")
    void theRouteCarriesNoWidthBound() {
        String longRoute = "/api/transactions/" + "segment/".repeat(40);

        TransactionAddResponse response = new TransactionAddResponse(null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, false, List.of(), null, longRoute, null);

        assertThat(response.nextRoute()).isEqualTo(longRoute);
        assertThat(violationsOf(response))
                .as("any bound here would be an invented limit rather than a measured one")
                .isEmpty();
    }

    // ===========================================================================================
    // THE ROUTE OUTCOME IS DECLARATIVE DATA
    // ===========================================================================================

    @Test
    @DisplayName("the next route is carried as opaque data and nothing here dispatches on it")
    void theNextRouteIsCarriedAsOpaqueData() throws JsonProcessingException {
        String route = response(NEXT_ROUTE).nextRoute();

        assertThat(route)
                .as("the declared type is text, so no route vocabulary is expressible here")
                .isEqualTo("/api/transactions");
        assertThat(response("anything-at-all").nextRoute())
                .as("an unrecognised route is carried, not rejected: there is no route table")
                .isEqualTo("anything-at-all");
        assertThat(response(null).nextRoute())
                .as("no route is nominated when none was supplied, and none is defaulted in")
                .isNull();
        assertThat(moduleEquivalentMapper().writeValueAsString(response(null)))
                .doesNotContain("\"nextRoute\"");
    }

    /**
     * A response carrying one route and nothing else.
     *
     * @param nextRoute the route the client should call next, or {@code null}
     * @return the response
     */
    private static TransactionAddResponse response(String nextRoute) {
        return new TransactionAddResponse(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, false,
                List.of(), null, nextRoute, null);
    }

    // ===========================================================================================
    // WIRE CONTRACT
    // ===========================================================================================

    @Test
    @DisplayName("absent components are omitted from the payload rather than written as null")
    void absentComponentsAreOmittedFromThePayload() throws JsonProcessingException {
        String json = moduleEquivalentMapper().writeValueAsString(allAbsent());

        assertThat(json)
                .doesNotContain("\"newTransactionId\"")
                .doesNotContain("\"accountId\"")
                .doesNotContain("\"cardNumber\"")
                .doesNotContain("\"message\"")
                .doesNotContain("\"focusScreenFieldId\"")
                .doesNotContain("null");
        assertThat(json)
                .as("the collection and the primitive indicator are always present")
                .contains("\"generalError\":false")
                .contains("\"fieldErrors\":[]");
    }

    @Test
    @DisplayName("an unknown incoming property is tolerated rather than rejected")
    void anUnknownIncomingPropertyIsTolerated() throws JsonProcessingException {
        String payloadWithExtra = "{\"message\":\"Account ID NOT found...\",\"generalError\":true,"
                + "\"aPropertyThisContractDoesNotDeclare\":\"ignored\"}";

        TransactionAddResponse bound = moduleEquivalentMapper()
                .readValue(payloadWithExtra, TransactionAddResponse.class);

        assertThat(bound.message()).isEqualTo("Account ID NOT found...").hasSize(23);
        assertThat(bound.generalError()).isTrue();
        assertThat(bound.fieldErrors())
                .as("the absent collection still normalises to an empty immutable one")
                .isEmpty();
    }

    @Test
    @DisplayName("the payload carries this contract's own property names")
    void thePayloadCarriesThisContractsOwnPropertyNames() throws JsonProcessingException {
        String json = moduleEquivalentMapper().writeValueAsString(fullyPopulated());

        assertThat(json).contains("\"newTransactionId\":", "\"accountId\":", "\"cardNumber\":",
                "\"typeCode\":", "\"categoryCode\":", "\"source\":", "\"description\":",
                "\"amount\":", "\"originationDate\":", "\"processingDate\":", "\"merchantId\":",
                "\"merchantName\":", "\"merchantCity\":", "\"merchantZip\":",
                "\"confirmationFlag\":");
        assertThat(json).contains("\"transactionName\":", "\"title01\":", "\"currentDate\":",
                "\"programName\":", "\"title02\":", "\"currentTime\":", "\"message\":",
                "\"generalError\":", "\"fieldErrors\":", "\"focusScreenFieldId\":",
                "\"nextRoute\":", "\"navigationContext\":");
    }

    @Test
    @DisplayName("the whole response round trips through the wire unchanged")
    void theWholeResponseRoundTripsThroughTheWireUnchanged() throws JsonProcessingException {
        TransactionAddResponse original = fullyPopulated();

        assertThat(jsonRoundTrip(original))
                .as("value equality across the wire, component by component")
                .isEqualTo(original);
    }

    @Test
    @DisplayName("the payload is not a problem document and declares no problem-document member")
    void thePayloadIsNotAProblemDocument() throws JsonProcessingException {
        String json = moduleEquivalentMapper().writeValueAsString(fullyPopulated());

        assertThat(json)
                .as("the standard problem representation is deliberately off for this module")
                .doesNotContain("\"type\":")
                .doesNotContain("\"title\":")
                .doesNotContain("\"status\":")
                .doesNotContain("\"detail\":")
                .doesNotContain("\"instance\":")
                .doesNotContain("application/problem+json");
        assertThat(json)
                .as("the two screen titles are this map's own items and are not problem members")
                .contains("\"title01\":")
                .contains("\"title02\":");
    }

    @Test
    @DisplayName("no row-version, entity-tag or optimistic-lock member is carried")
    void noRowVersionOrEntityTagMemberIsCarried() throws JsonProcessingException {
        String json = moduleEquivalentMapper().writeValueAsString(fullyPopulated());

        assertThat(json)
                .as("the add path performs no before-and-after image comparison")
                .doesNotContain("\"version\":")
                .doesNotContain("\"etag\":")
                .doesNotContain("\"eTag\":")
                .doesNotContain("\"rowVersion\":")
                .doesNotContain("\"lockVersion\":");
    }

    @Test
    @DisplayName("no verification-code member is carried and the card number is never obscured")
    void noVerificationCodeMemberIsCarriedAndTheCardNumberIsNeverObscured()
            throws JsonProcessingException {
        String json = moduleEquivalentMapper().writeValueAsString(fullyPopulated());

        assertThat(json)
                .as("the legacy screen has no verification code, and adding one would be new work")
                .doesNotContain("\"cvv\"")
                .doesNotContain("\"cardVerification");
        assertThat(json)
                .as("the value a client receives is the value the legacy screen showed")
                .contains("\"cardNumber\":\"4111111111111111\"")
                .doesNotContain("****");
    }

    // ===========================================================================================
    // IMMUTABILITY, EQUALITY AND THE DIAGNOSTIC RENDERING
    // ===========================================================================================

    @Test
    @DisplayName("an instance is immutable, demonstrated by constructing and then re-reading it")
    void anInstanceIsImmutable() throws JsonProcessingException {
        List<ErrorResponse.FieldError> caller = new ArrayList<>();
        caller.add(new ErrorResponse.FieldError("amount", "TRNAMT",
                ErrorResponse.FieldState.INVALID, TransactionAddResponse.MESSAGE_AMOUNT_FORMAT));

        TransactionAddResponse response = new TransactionAddResponse(TRANSACTION_ID, ACCOUNT_ID,
                CARD_NUMBER, TYPE_CODE, CATEGORY_CODE, SOURCE_POS_TERMINAL,
                DESCRIPTION_AT_FULL_WIDTH, AMOUNT_NEGATIVE, ORIGINATION_DATE, PROCESSING_DATE,
                MERCHANT_ID, MERCHANT_NAME_AT_FULL_WIDTH, MERCHANT_CITY_AT_FULL_WIDTH, MERCHANT_ZIP,
                CONFIRMATION_LOWER_CASE, TRANSACTION_NAME, TITLE_LINE_ONE, CURRENT_DATE,
                PROGRAM_NAME, TITLE_LINE_TWO, CURRENT_TIME,
                TransactionAddResponse.MESSAGE_AMOUNT_FORMAT, true, caller, "TRNAMT", NEXT_ROUTE,
                reEntryNavigation());

        caller.clear();
        response.toString();
        response.hasFieldErrors();
        moduleEquivalentMapper().writeValueAsString(response);

        assertThat(response.newTransactionId()).isEqualTo(TRANSACTION_ID);
        assertThat(response.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(response.amount()).isEqualTo(AMOUNT_NEGATIVE);
        assertThat(response.message()).isEqualTo("Amount should be in format -99999999.99");
        assertThat(response.generalError()).isTrue();
        assertThat(response.fieldErrors())
                .as("no operation on the instance, and no mutation of the caller's list, changed it")
                .hasSize(1);
        assertThat(response.navigationContext()).isEqualTo(reEntryNavigation());
    }

    @Test
    @DisplayName("every one of the twenty-seven components is readable through its own accessor")
    void everyComponentIsReadableThroughItsOwnAccessor() {
        TransactionAddResponse response = fullyPopulated();

        assertThat(response.newTransactionId()).isEqualTo(TRANSACTION_ID);
        assertThat(response.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(response.cardNumber()).isEqualTo(CARD_NUMBER);
        assertThat(response.typeCode()).isEqualTo(TYPE_CODE);
        assertThat(response.categoryCode()).isEqualTo(CATEGORY_CODE);
        assertThat(response.source()).isEqualTo(SOURCE_POS_TERMINAL);
        assertThat(response.description()).isEqualTo(DESCRIPTION_AT_FULL_WIDTH);
        assertThat(response.amount()).isEqualTo(AMOUNT_NEGATIVE);
        assertThat(response.originationDate()).isEqualTo(ORIGINATION_DATE);
        assertThat(response.processingDate()).isEqualTo(PROCESSING_DATE);
        assertThat(response.merchantId()).isEqualTo(MERCHANT_ID);
        assertThat(response.merchantName()).isEqualTo(MERCHANT_NAME_AT_FULL_WIDTH);
        assertThat(response.merchantCity()).isEqualTo(MERCHANT_CITY_AT_FULL_WIDTH);
        assertThat(response.merchantZip()).isEqualTo(MERCHANT_ZIP);
        assertThat(response.confirmationFlag()).isEqualTo(CONFIRMATION_LOWER_CASE);
        assertThat(response.transactionName()).isEqualTo(TRANSACTION_NAME).hasSize(4);
        assertThat(response.title01()).isEqualTo(TITLE_LINE_ONE).hasSize(40);
        assertThat(response.currentDate()).isEqualTo(CURRENT_DATE).hasSize(8);
        assertThat(response.programName()).isEqualTo(PROGRAM_NAME).hasSize(8);
        assertThat(response.title02()).isEqualTo(TITLE_LINE_TWO).hasSize(40);
        assertThat(response.currentTime()).isEqualTo(CURRENT_TIME).hasSize(8);
        assertThat(response.message()).isEqualTo(TransactionAddResponse.MESSAGE_CONFIRM_PROMPT);
        assertThat(response.generalError()).isFalse();
        assertThat(response.fieldErrors()).isEmpty();
        assertThat(response.focusScreenFieldId()).isEqualTo(FOCUS_FIELD_ID);
        assertThat(response.nextRoute()).isEqualTo(NEXT_ROUTE);
        assertThat(response.navigationContext()).isEqualTo(reEntryNavigation());
        assertThat(response.hasFieldErrors()).isFalse();
    }

    @Test
    @DisplayName("equality compares every component by value")
    void equalityComparesEveryComponentByValue() {
        assertThat(fullyPopulated())
                .isEqualTo(fullyPopulated())
                .hasSameHashCodeAs(fullyPopulated());
        assertThat(fullyPopulated()).isNotEqualTo(allAbsent());
    }

    @Test
    @DisplayName("a differing indicator alone makes two responses unequal")
    void aDifferingIndicatorAloneMakesTwoResponsesUnequal() {
        TransactionAddResponse reportingFailure =
                withMessage(TransactionAddResponse.MESSAGE_ADD_FAILED, true);
        TransactionAddResponse notReportingFailure =
                withMessage(TransactionAddResponse.MESSAGE_ADD_FAILED, false);

        assertThat(reportingFailure)
                .as("the indicator is a component in its own right, so it participates in equality")
                .isNotEqualTo(notReportingFailure);
        assertThat(reportingFailure.message()).isEqualTo(notReportingFailure.message());
    }

    @Test
    @DisplayName("the diagnostic rendering withholds the cardholder-bearing values")
    void theDiagnosticRenderingWithholdsTheCardholderBearingValues() {
        String rendered = fullyPopulated().toString();

        assertThat(rendered)
                .as("withheld with a fixed stand-in: never truncated, never masked, never hashed")
                .contains("accountId=" + REDACTED)
                .contains("cardNumber=" + REDACTED)
                .contains("description=" + REDACTED)
                .contains("amount=" + REDACTED)
                .contains("merchantId=" + REDACTED)
                .contains("merchantName=" + REDACTED)
                .contains("merchantCity=" + REDACTED)
                .contains("merchantZip=" + REDACTED);
        assertThat(rendered)
                .doesNotContain(CARD_NUMBER, DESCRIPTION_AT_FULL_WIDTH, MERCHANT_ID,
                        MERCHANT_NAME_AT_FULL_WIDTH, MERCHANT_CITY_AT_FULL_WIDTH, MERCHANT_ZIP,
                        "-123.45");
        assertThat(rendered)
                .as("no withheld component appears under its own label either")
                .doesNotContain("accountId=" + ACCOUNT_ID)
                .doesNotContain("cardNumber=" + CARD_NUMBER)
                .doesNotContain("merchantId=" + MERCHANT_ID)
                .doesNotContain("amount=" + AMOUNT_NEGATIVE);
    }

    @Test
    @DisplayName("the diagnostic rendering retains what a diagnostic actually needs")
    void theDiagnosticRenderingRetainsWhatADiagnosticNeeds() {
        String rendered = fullyPopulated().toString();

        assertThat(rendered)
                .startsWith("TransactionAddResponse[")
                .endsWith("]")
                .contains("newTransactionId=" + TRANSACTION_ID)
                .contains("typeCode=" + TYPE_CODE)
                .contains("categoryCode=" + CATEGORY_CODE)
                .contains("source=" + SOURCE_POS_TERMINAL)
                .contains("originationDate=" + ORIGINATION_DATE)
                .contains("processingDate=" + PROCESSING_DATE)
                .contains("confirmationFlag=" + CONFIRMATION_LOWER_CASE)
                .contains("transactionName=" + TRANSACTION_NAME)
                .contains("currentDate=" + CURRENT_DATE)
                .contains("programName=" + PROGRAM_NAME)
                .contains("currentTime=" + CURRENT_TIME)
                .contains("generalError=false")
                .contains("focusScreenFieldId=" + FOCUS_FIELD_ID)
                .contains("nextRoute=" + NEXT_ROUTE)
                .contains("message=" + TransactionAddResponse.MESSAGE_CONFIRM_PROMPT);
    }

    @Test
    @DisplayName("the diagnostic rendering changes nothing an accessor returns")
    void theDiagnosticRenderingChangesNothingAnAccessorReturns() {
        TransactionAddResponse response = fullyPopulated();

        response.toString();

        assertThat(response.cardNumber())
                .as("withholding affects the rendering only, never the carried value")
                .isEqualTo(CARD_NUMBER)
                .hasSize(16);
        assertThat(response.amount()).isEqualTo(AMOUNT_NEGATIVE);
        assertThat(response.merchantName()).isEqualTo(MERCHANT_NAME_AT_FULL_WIDTH);
    }

    @Test
    @DisplayName("a wholly absent response renders without failing")
    void aWhollyAbsentResponseRendersWithoutFailing() {
        assertThat(allAbsent().toString())
                .startsWith("TransactionAddResponse[")
                .contains("generalError=false")
                .contains("fieldErrors=[]")
                .contains("navigationContext=null");
    }

    @Test
    @DisplayName("the transcription record identifies its own source line in a diagnostic")
    void theTranscriptionRecordIdentifiesItsOwnSourceLine() {
        MessageLiteral first = measuredMessageLiterals().findFirst().orElseThrow(() ->
                new AssertionError("the fourteen measured texts must not be empty"));

        assertThat(first.toString()).isEqualTo("COTRN02C line 184, 40 characters");
        assertThat(first.sourceLine()).isEqualTo(184);
        assertThat(first.measuredLength()).isEqualTo(40);
    }
}
