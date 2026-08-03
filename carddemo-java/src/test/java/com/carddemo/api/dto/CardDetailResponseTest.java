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

import com.carddemo.domain.enums.CardStatus;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link CardDetailResponse}, the response body of legacy transaction {@code CCDL}.
 *
 * <p>A pure unit test throughout: no application context, no web layer, no connection, no container and
 * no mock. Every instance is built through the record's canonical constructor and every assertion is made
 * either against a value an accessor returned, against a payload this test serialized itself, or against a
 * constraint violation set a locally built validator produced. Nothing here inspects the declaration of the
 * type under test, so no claim below can be satisfied by an annotation that the runtime ignores.
 *
 * <h2>Why the widths are the subject rather than a detail</h2>
 *
 * <p>The screen this response replaces declares its two message lines at widths that are unique to the two
 * card maps in the whole estate. The informational line is 40 characters here and 45 on the account maps;
 * the error line is 80 characters here and 78 on the thirteen other symbolic maps. A third width exists in
 * the same estate, the 80-character working message field the sign-on and account-update programs carry,
 * and it coincides with this one by accident rather than by relation. None of the three is normalised
 * towards another, so the tests below pin 40 and 80 explicitly and assert that a value at each of those
 * exact widths survives a round trip untouched. Narrowing the error line to 78 or widening the
 * informational line to 45 would each be a silent contract change that no compiler could catch, which is
 * why both are asserted rather than assumed.
 *
 * <h2>Why the absences are asserted</h2>
 *
 * <p>Two things the neighbouring card screen has are deliberately missing here, and an absence is only
 * durable if something fails when it disappears. The detail map carries an expiry month and an expiry year
 * and no expiry day at all; the update map adds a day. The detail map also declares a 75-character
 * function-key legend, which is a printed caption rather than card data and is therefore not modelled. Both
 * absences are asserted on the wire, so a later widening of this response towards the update surface breaks
 * a test instead of quietly enlarging a published contract.
 *
 * <h2>Why the message text is compared character for character</h2>
 *
 * <p>The messages this screen emits are external contract: operators read them and tooling matches on them.
 * The legacy text is irregular in ways that look like defects and are not. One message has four consecutive
 * dots. One has no space after its full stop and carries fourteen trailing spaces that are part of its
 * value. Two spell the constraint as two words. Two more are upper case, omit the space after a comma, and
 * read "A 11" and "A 16" where English wants "AN 11" and "A 16". The messages on this screen are mixed
 * case while the card-list program's are upper case, and that difference is contract too. Every one of them
 * is asserted at its measured length and never trimmed, because a tidied message is a behavioural change.
 *
 * <h2>Provenance</h2>
 *
 * <p>Every width, line citation and message length below was read from the read-only legacy tree at
 * repository checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, whose members carry the upstream
 * release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The field contract is the generated
 * symbolic map {@code app/cpy-bms/COCRDSL.CPY}, the layout authority is {@code app/bms/COCRDSL.bms}, the
 * message text and the program behaviour are {@code app/cbl/COCRDSLC.cbl}, and the underlying 150-byte
 * record layout is {@code app/cpy/CVACT02Y.cpy}. No legacy source text is reproduced here: the citations
 * are references, and the message strings are the externally observable contract rather than program source.
 */
@DisplayName("CardDetailResponse :: card-detail response contract of legacy transaction CCDL")
class CardDetailResponseTest {

    // ------------------------------------------------------------------------------------------------
    // Values at the exact declared widths. Each is measured by a test rather than trusted, so a mistyped
    // fixture fails loudly instead of weakening the assertion that consumes it.
    // ------------------------------------------------------------------------------------------------

    /** Eleven characters, carrying the contractual leading zeros that must never be dropped. */
    private static final String ACCOUNT_ID = "00000000011";

    /** Sixteen characters. A fictional test value; no issuable card number appears in this module. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** Sixteen characters whose value is one, written the way the contract requires it to travel. */
    private static final String CARD_NUMBER_ALL_BUT_ONE_LEADING_ZERO = "0000000000000001";

    /** Eleven characters whose value is one, written the way the contract requires it to travel. */
    private static final String ACCOUNT_ID_ALL_BUT_ONE_LEADING_ZERO = "00000000001";

    /**
     * Fifty characters: twenty-five of mixed-case text, a space, a hyphen, then twenty-five spaces of
     * padding. Mixed case on purpose - the card-update path folds an embossed name through a 26-character
     * table, and this display path folds nothing, so a lower-case letter here must still be lower case
     * after a round trip. The trailing padding is the legacy record's own space filling and is part of the
     * value.
     */
    private static final String EMBOSSED_NAME =
            "Mary Ann de la Cruz-Smith                         ";

    /** Two characters, retaining the leading zero that a numeric type would discard. */
    private static final String EXPIRY_MONTH = "01";

    /** Four characters. */
    private static final String EXPIRY_YEAR = "2027";

    /**
     * Forty characters: the found-details message followed by nine spaces of field padding. Forty is this
     * map's informational width and is deliberately not the forty-five the account maps use.
     */
    private static final String INFO_MESSAGE_AT_FULL_WIDTH =
            "   Displaying requested details" + " ".repeat(9);

    /**
     * Eighty characters: the account-number diagnostic followed by thirty-one spaces of field padding.
     * Eighty is this map's error width and is deliberately not the seventy-eight almost every other
     * symbolic map uses.
     */
    private static final String ERROR_MESSAGE_AT_FULL_WIDTH =
            "Account number must be a non zero 11 digit number" + " ".repeat(31);

    /** The fixed stand-in the rendering substitutes for each regulated value. */
    private static final String REDACTED = "***REDACTED***";

    /** The map's informational width. Forty here; forty-five on the account maps. */
    private static final int INFO_WIDTH_ON_THIS_MAP = 40;

    /** The map's error width. Eighty here; seventy-eight on the other thirteen symbolic maps. */
    private static final int ERROR_WIDTH_ON_THIS_MAP = 80;

    /** The informational width used by the account maps, which this map must never be widened to. */
    private static final int INFO_WIDTH_ON_THE_ACCOUNT_MAPS = 45;

    /** The error width used by the other symbolic maps, which this map must never be narrowed to. */
    private static final int ERROR_WIDTH_ON_THE_OTHER_MAPS = 78;

    /** Every published JSON key, in the order the symbolic map declares the fields they carry. */
    private static final List<String> PUBLISHED_KEYS_IN_MAP_ORDER = List.of(
            "transactionName", "title01", "currentDate", "programName", "title02", "currentTime",
            "accountId", "cardNumber", "embossedName", "cardActiveStatus", "expiryMonth",
            "expiryYear", "infoMessage", "errorMessage", "generalError", "focusScreenFieldId",
            "nextRoute", "navigationContext");

    /**
     * Keys that must never appear. The first six are the expiry-day family the update map adds and this
     * map does not have, in every spelling a later change might reach for; the next five are the
     * function-key legend; the last four are the status-pointer family that no card map declares at all.
     */
    private static final List<String> KEYS_THAT_MUST_NOT_EXIST = List.of(
            "expiryDay", "expDay", "expday", "expiryDate", "expiry", "validThru",
            "fkeys", "fKeys", "functionKeys", "functionKeyLegend", "keyLegend",
            "crdstp", "crdStp", "cardStatusPointer", "statusPointer");

    /** The five member names an RFC-7807 problem document publishes. This response is not one. */
    private static final List<String> PROBLEM_DOCUMENT_MEMBERS =
            List.of("type", "title", "status", "detail", "instance");

    private static ValidatorFactory validatorFactory;

    private static Validator validator;

    /**
     * Opens a validator from the Bean Validation provider on the test classpath.
     *
     * <p>Built here rather than injected, because a framework-managed validator would drag in an
     * application context and this is a unit test. The provider is discovered through the standard
     * bootstrap, so the constraints exercised below are evaluated by the same engine that evaluates them
     * at runtime.
     */
    @BeforeAll
    static void openValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    /**
     * Closes the validator factory, tolerating the case where it was never opened.
     *
     * <p>The guard is not decoration: this method also runs when {@link #openValidator()} threw, and an
     * unguarded call would raise a second failure that hid the first.
     */
    @AfterAll
    static void closeValidator() {
        if (validatorFactory != null) {
            validatorFactory.close();
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Construction helpers. Each names the group of components it populates and leaves every other
    // component absent, so a failing assertion points at one group rather than at an eighteen-argument
    // call. All of them use the record's canonical constructor, so every instance below is built the way
    // a caller builds one: nothing is generated, and nothing is read out of the type's declaration.
    // ------------------------------------------------------------------------------------------------

    /** A response with nothing populated, which is a real state: the screen prompting for input. */
    private static CardDetailResponse empty() {
        return new CardDetailResponse(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, false, null, null, null);
    }

    /** A response whose every text component is present but empty. */
    private static CardDetailResponse blankFilled() {
        return new CardDetailResponse("", "", "", "", "", "", "", "", "", "", "", "", "", "", false,
                "", "", null);
    }

    /** A response carrying only the six card values the screen displays. */
    private static CardDetailResponse card(String accountId, String cardNumber, String embossedName,
            String cardActiveStatus, String expiryMonth, String expiryYear) {
        return new CardDetailResponse(null, null, null, null, null, null, accountId, cardNumber,
                embossedName, cardActiveStatus, expiryMonth, expiryYear, null, null, false, null,
                null, null);
    }

    /** A response carrying only the six header items the screen prints around the card values. */
    private static CardDetailResponse furniture(String transactionName, String title01,
            String currentDate, String programName, String title02, String currentTime) {
        return new CardDetailResponse(transactionName, title01, currentDate, programName, title02,
                currentTime, null, null, null, null, null, null, null, null, false, null, null,
                null);
    }

    /** A response carrying only the two message lines and the error indicator. */
    private static CardDetailResponse messages(String infoMessage, String errorMessage,
            boolean generalError) {
        return new CardDetailResponse(null, null, null, null, null, null, null, null, null, null,
                null, null, infoMessage, errorMessage, generalError, null, null, null);
    }

    /** A response carrying only the focus hint, the next route and the echoed navigation state. */
    private static CardDetailResponse routing(String focusScreenFieldId, String nextRoute,
            NavigationContext navigationContext) {
        return new CardDetailResponse(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, false, focusScreenFieldId, nextRoute, navigationContext);
    }

    /** A fully populated response, used where every component must be present at once. */
    private static CardDetailResponse populated() {
        return new CardDetailResponse("CCDL", "AWS Mainframe Modernization", "08/02/26", "COCRDSLC",
                "CardDemo", "14:30:00", ACCOUNT_ID, CARD_NUMBER, EMBOSSED_NAME, "Y", EXPIRY_MONTH,
                EXPIRY_YEAR, INFO_MESSAGE_AT_FULL_WIDTH, ERROR_MESSAGE_AT_FULL_WIDTH, true,
                CardDetailResponse.SCREEN_FIELD_ACCOUNT_ID, "/api/cards/detail", navigation());
    }

    /** Echoed navigation state carrying identifiers whose leading zeros must survive the round trip. */
    private static NavigationContext navigation() {
        return new NavigationContext("CCDL", "COCRDSLC", "CCDL", "COCRDSLC", "OPERATOR", "A",
                NavigationContext.ProgramContext.REENTER, "000000011", "Mary", "Ann", "de la Cruz",
                ACCOUNT_ID, "Y", CARD_NUMBER, "CCRDSLA", "COCRDSL");
    }

    /**
     * Builds a mapper configured exactly as the module configures its own.
     *
     * <p>Local and plain, matching the four settings the application configuration declares that this
     * contract depends on: absent values are omitted, temporal values are never written as numbers,
     * unknown incoming properties are tolerated, and a decimal is written in plain notation. Built per
     * call so no test can be affected by another's use of it.
     *
     * @return a mapper equivalent to the module's own
     */
    private static ObjectMapper moduleEquivalentMapper() {
        return JsonMapper.builder()
                .defaultPropertyInclusion(
                        JsonInclude.Value.construct(JsonInclude.Include.NON_NULL,
                                JsonInclude.Include.NON_NULL))
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .build();
    }

    /**
     * Serializes a response and parses the result back into a tree.
     *
     * @param response the response to publish
     * @return the published payload
     * @throws JsonProcessingException if the payload cannot be written or re-read
     */
    private static JsonNode payloadOf(CardDetailResponse response) throws JsonProcessingException {
        ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(response));
    }

    /**
     * Binds a JSON body to a response.
     *
     * @param body the body to bind
     * @return the bound response
     * @throws JsonProcessingException if the body cannot be bound
     */
    private static CardDetailResponse bind(String body) throws JsonProcessingException {
        return moduleEquivalentMapper().readValue(body, CardDetailResponse.class);
    }

    /**
     * Collects the keys a payload publishes, in the order it publishes them.
     *
     * @param payload a published payload
     * @return the key names in publication order
     */
    private static List<String> keysOf(JsonNode payload) {
        List<String> keys = new ArrayList<>();
        payload.fieldNames().forEachRemaining(keys::add);
        return List.copyOf(keys);
    }

    /**
     * Validates a response with the locally built validator.
     *
     * @param response the response to validate
     * @return every violation the engine reported
     */
    private static Set<ConstraintViolation<CardDetailResponse>> violationsOf(
            CardDetailResponse response) {
        return validator.validate(response);
    }

    /**
     * Names the components a violation set reports against.
     *
     * @param violations a violation set
     * @return the reported component names
     */
    private static List<String> violatingComponents(
            Set<ConstraintViolation<CardDetailResponse>> violations) {
        return violations.stream()
                .map(violation -> violation.getPropertyPath().toString())
                .toList();
    }

    @Nested
    @DisplayName("published contract - the keys a client can rely on, and the keys it must never see")
    class PublishedContract {

        @Test
        @DisplayName("publishes exactly eighteen keys, in the order the symbolic map declares its fields")
        void publishesExactlyEighteenKeysInMapOrder() throws JsonProcessingException {
            assertThat(keysOf(payloadOf(populated())))
                    .as("a record publishes exactly its components, so the payload is the inventory")
                    .containsExactlyElementsOf(PUBLISHED_KEYS_IN_MAP_ORDER)
                    .hasSize(18);
        }

        @Test
        @DisplayName("publishes the canonical spelling of each name a sibling contract spells differently")
        void publishesTheCanonicalSpellings() throws JsonProcessingException {
            List<String> keys = keysOf(payloadOf(populated()));

            assertThat(keys).contains("title01", "title02", "nextRoute", "focusScreenFieldId");
            assertThat(keys).doesNotContain("screenTitleLine1", "screenTitleLine2", "route",
                    "focusField", "fieldToFocus");
        }

        @Test
        @DisplayName("carries no expiry-day key, because this map declares a month and a year only")
        void carriesNoExpiryDayKey() throws JsonProcessingException {
            // Measured rather than assumed: the day family occurs nowhere in this map's copybook, while
            // the card-update map declares it explicitly. The update surface is deliberately the wider
            // of the two, and this response must not drift towards it.
            List<String> keys = keysOf(payloadOf(populated()));

            assertThat(keys).contains("expiryMonth", "expiryYear");
            assertThat(keys).doesNotContain("expiryDay", "expDay", "expday");
        }

        @Test
        @DisplayName("carries no function-key legend, which is a printed caption rather than card data")
        void carriesNoFunctionKeyLegend() throws JsonProcessingException {
            assertThat(keysOf(payloadOf(populated())))
                    .doesNotContain("fkeys", "fKeys", "functionKeys", "functionKeyLegend",
                            "keyLegend");
        }

        @Test
        @DisplayName("carries none of the keys that belong to another map or to no map at all")
        void carriesNoneOfTheForbiddenKeys() throws JsonProcessingException {
            List<String> keys = keysOf(payloadOf(populated()));

            assertThat(KEYS_THAT_MUST_NOT_EXIST)
                    .allSatisfy(forbidden -> assertThat(keys)
                            .as("key that must never be published: %s", forbidden)
                            .doesNotContain(forbidden));
        }

        @Test
        @DisplayName("carries no terminal plumbing: no control byte, no coordinate, no attribute")
        void carriesNoTerminalPlumbing() throws JsonProcessingException {
            // The symbolic map generates a length, flag and attribute item beside every value item, plus
            // colour, highlight, protection and validation items on the output side, plus a leading
            // filler. None of it is card data and none of it is published.
            List<String> keys = keysOf(payloadOf(populated()));

            assertThat(keys).doesNotContain("length", "flag", "attribute", "colour", "color",
                    "highlight", "protection", "filler", "row", "column", "cursor", "tioa");
        }

        @Test
        @DisplayName("is not a problem document, because that representation is deliberately not in use")
        void isNotAProblemDocument() throws JsonProcessingException {
            List<String> keys = keysOf(payloadOf(populated()));

            assertThat(PROBLEM_DOCUMENT_MEMBERS)
                    .allSatisfy(member -> assertThat(keys)
                            .as("problem-document member that must be absent: %s", member)
                            .doesNotContain(member));
        }

        @Test
        @DisplayName("names the two focusable screen fields exactly as the mapset declares them")
        void namesTheTwoFocusableScreenFields() {
            assertThat(CardDetailResponse.SCREEN_FIELD_ACCOUNT_ID).isEqualTo("ACCTSID");
            assertThat(CardDetailResponse.SCREEN_FIELD_CARD_NUMBER).isEqualTo("CARDSID");
            assertThat(CardDetailResponse.FOCUS_SCREEN_FIELD_ID_LENGTH).isEqualTo(7);
            assertThat(CardDetailResponse.SCREEN_FIELD_ACCOUNT_ID)
                    .hasSize(CardDetailResponse.FOCUS_SCREEN_FIELD_ID_LENGTH);
            assertThat(CardDetailResponse.SCREEN_FIELD_CARD_NUMBER)
                    .hasSize(CardDetailResponse.FOCUS_SCREEN_FIELD_ID_LENGTH);
        }

        @Test
        @DisplayName("publishes the widths the map and the record declare")
        void publishesTheDeclaredWidths() {
            assertThat(CardDetailResponse.TRANSACTION_NAME_LENGTH).isEqualTo(4);
            assertThat(CardDetailResponse.SCREEN_TITLE_LENGTH).isEqualTo(40);
            assertThat(CardDetailResponse.CURRENT_DATE_LENGTH).isEqualTo(8);
            assertThat(CardDetailResponse.PROGRAM_NAME_LENGTH).isEqualTo(8);
            assertThat(CardDetailResponse.CURRENT_TIME_LENGTH).isEqualTo(8);
            assertThat(CardDetailResponse.ACCOUNT_ID_LENGTH).isEqualTo(11);
            assertThat(CardDetailResponse.CARD_NUMBER_LENGTH).isEqualTo(16);
            assertThat(CardDetailResponse.EMBOSSED_NAME_LENGTH).isEqualTo(50);
            assertThat(CardDetailResponse.CARD_ACTIVE_STATUS_LENGTH).isEqualTo(1);
            assertThat(CardDetailResponse.EXPIRY_MONTH_LENGTH).isEqualTo(2);
            assertThat(CardDetailResponse.EXPIRY_YEAR_LENGTH).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("every component round-trips at its exact width, untrimmed and unpadded")
    class ExactWidthRoundTrip {

        @Test
        @DisplayName("the fixtures themselves sit at the widths they claim")
        void theFixturesSitAtTheWidthsTheyClaim() {
            // A fixture that drifted would weaken every assertion that consumes it, so each is measured
            // here before any of them is used as evidence.
            assertThat(ACCOUNT_ID).hasSize(CardDetailResponse.ACCOUNT_ID_LENGTH);
            assertThat(ACCOUNT_ID_ALL_BUT_ONE_LEADING_ZERO)
                    .hasSize(CardDetailResponse.ACCOUNT_ID_LENGTH);
            assertThat(CARD_NUMBER).hasSize(CardDetailResponse.CARD_NUMBER_LENGTH);
            assertThat(CARD_NUMBER_ALL_BUT_ONE_LEADING_ZERO)
                    .hasSize(CardDetailResponse.CARD_NUMBER_LENGTH);
            assertThat(EMBOSSED_NAME).hasSize(CardDetailResponse.EMBOSSED_NAME_LENGTH);
            assertThat(EXPIRY_MONTH).hasSize(CardDetailResponse.EXPIRY_MONTH_LENGTH);
            assertThat(EXPIRY_YEAR).hasSize(CardDetailResponse.EXPIRY_YEAR_LENGTH);
            assertThat(INFO_MESSAGE_AT_FULL_WIDTH).hasSize(CardDetailResponse.INFO_MESSAGE_LENGTH);
            assertThat(ERROR_MESSAGE_AT_FULL_WIDTH).hasSize(CardDetailResponse.ERROR_MESSAGE_LENGTH);
        }

        @Test
        @DisplayName("an eleven-character account identifier reads back byte for byte")
        void anElevenCharacterAccountIdentifierReadsBackByteForByte() {
            assertThat(card(ACCOUNT_ID, null, null, null, null, null).accountId())
                    .isEqualTo(ACCOUNT_ID)
                    .hasSize(11);
        }

        @Test
        @DisplayName("a sixteen-character card number reads back byte for byte, in full and unmasked")
        void aSixteenCharacterCardNumberReadsBackInFull() {
            // The legacy design applies no masking, truncation or field-level protection to a primary
            // account number anywhere, and no requirement introduces one, so the value travels whole.
            String read = card(null, CARD_NUMBER, null, null, null, null).cardNumber();

            assertThat(read).isEqualTo(CARD_NUMBER).hasSize(16);
            assertThat(read).doesNotContain("*").doesNotContain("X").doesNotContain("#");
        }

        @Test
        @DisplayName("a fifty-character embossed name reads back with its padding intact")
        void aFiftyCharacterEmbossedNameReadsBackWithItsPaddingIntact() {
            assertThat(card(null, null, EMBOSSED_NAME, null, null, null).embossedName())
                    .isEqualTo(EMBOSSED_NAME)
                    .hasSize(50)
                    .endsWith(" ");
        }

        @Test
        @DisplayName("a single-character card status reads back byte for byte")
        void aSingleCharacterCardStatusReadsBackByteForByte() {
            assertThat(card(null, null, null, "Y", null, null).cardActiveStatus())
                    .isEqualTo("Y")
                    .hasSize(1);
            assertThat(card(null, null, null, "N", null, null).cardActiveStatus()).isEqualTo("N");
        }

        @Test
        @DisplayName("a two-character month and a four-character year read back byte for byte")
        void theExpiryPartsReadBackByteForByte() {
            CardDetailResponse response = card(null, null, null, null, EXPIRY_MONTH, EXPIRY_YEAR);

            assertThat(response.expiryMonth()).isEqualTo(EXPIRY_MONTH).hasSize(2);
            assertThat(response.expiryYear()).isEqualTo(EXPIRY_YEAR).hasSize(4);
        }

        @Test
        @DisplayName("a forty-character informational message reads back untrimmed at forty")
        void aFortyCharacterInformationalMessageReadsBackUntrimmed() {
            assertThat(messages(INFO_MESSAGE_AT_FULL_WIDTH, null, false).infoMessage())
                    .isEqualTo(INFO_MESSAGE_AT_FULL_WIDTH)
                    .hasSize(INFO_WIDTH_ON_THIS_MAP)
                    .endsWith(" ");
        }

        @Test
        @DisplayName("an eighty-character error message reads back untrimmed at eighty")
        void anEightyCharacterErrorMessageReadsBackUntrimmed() {
            assertThat(messages(null, ERROR_MESSAGE_AT_FULL_WIDTH, true).errorMessage())
                    .isEqualTo(ERROR_MESSAGE_AT_FULL_WIDTH)
                    .hasSize(ERROR_WIDTH_ON_THIS_MAP)
                    .endsWith(" ");
        }

        @Test
        @DisplayName("the six header items read back byte for byte at their declared widths")
        void theHeaderItemsReadBackByteForByte() {
            CardDetailResponse response =
                    furniture("CCDL", "AWS Mainframe Modernization", "08/02/26", "COCRDSLC",
                            "CardDemo", "14:30:00");

            assertThat(response.transactionName()).isEqualTo("CCDL").hasSize(4);
            assertThat(response.title01()).isEqualTo("AWS Mainframe Modernization");
            assertThat(response.currentDate()).isEqualTo("08/02/26").hasSize(8);
            assertThat(response.programName()).isEqualTo("COCRDSLC").hasSize(8);
            assertThat(response.title02()).isEqualTo("CardDemo");
            assertThat(response.currentTime()).isEqualTo("14:30:00").hasSize(8);
        }

        @Test
        @DisplayName("a value shorter than its width is never padded up to it")
        void aShortValueIsNeverPaddedUp() {
            CardDetailResponse response = card("7", "1", "A", "Y", "1", "9");

            assertThat(response.accountId()).isEqualTo("7").hasSize(1);
            assertThat(response.cardNumber()).isEqualTo("1").hasSize(1);
            assertThat(response.embossedName()).isEqualTo("A").hasSize(1);
            assertThat(response.expiryMonth()).isEqualTo("1").hasSize(1);
            assertThat(response.expiryYear()).isEqualTo("9").hasSize(1);
        }

        @Test
        @DisplayName("leading and trailing spaces survive, because legacy space filling is the value")
        void surroundingSpacesSurvive() {
            CardDetailResponse response = card("  7        ", "  1             ", "  A", " ", " 1",
                    " 9  ");

            assertThat(response.accountId()).isEqualTo("  7        ");
            assertThat(response.cardNumber()).isEqualTo("  1             ");
            assertThat(response.embossedName()).isEqualTo("  A");
            assertThat(response.cardActiveStatus()).isEqualTo(" ");
            assertThat(response.expiryMonth()).isEqualTo(" 1");
            assertThat(response.expiryYear()).isEqualTo(" 9  ");
        }

        @Test
        @DisplayName("every exact-width value survives serialization and binding unchanged")
        void everyExactWidthValueSurvivesTheWire() throws JsonProcessingException {
            CardDetailResponse bound = bind(moduleEquivalentMapper()
                    .writeValueAsString(populated()));

            assertThat(bound.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(bound.cardNumber()).isEqualTo(CARD_NUMBER);
            assertThat(bound.embossedName()).isEqualTo(EMBOSSED_NAME).hasSize(50);
            assertThat(bound.cardActiveStatus()).isEqualTo("Y");
            assertThat(bound.expiryMonth()).isEqualTo(EXPIRY_MONTH);
            assertThat(bound.expiryYear()).isEqualTo(EXPIRY_YEAR);
            assertThat(bound.infoMessage()).isEqualTo(INFO_MESSAGE_AT_FULL_WIDTH).hasSize(40);
            assertThat(bound.errorMessage()).isEqualTo(ERROR_MESSAGE_AT_FULL_WIDTH).hasSize(80);
        }
    }


    @Nested
    @DisplayName("the two message widths unique to the card maps are never normalised")
    class MessageWidthAnomalies {

        @Test
        @DisplayName("the error line is eighty here, and is never narrowed to the seventy-eight "
                + "almost every other symbolic map uses")
        void theErrorLineIsEightyAndNeverSeventyEight() {
            assertThat(CardDetailResponse.ERROR_MESSAGE_LENGTH)
                    .as("the error width declared by this map and by the card-update map")
                    .isEqualTo(ERROR_WIDTH_ON_THIS_MAP)
                    .isNotEqualTo(ERROR_WIDTH_ON_THE_OTHER_MAPS);
        }

        @Test
        @DisplayName("the informational line is forty here, and is never widened to the forty-five "
                + "the account maps use")
        void theInformationalLineIsFortyAndNeverFortyFive() {
            assertThat(CardDetailResponse.INFO_MESSAGE_LENGTH)
                    .as("the informational width declared by this map and by the card-update map")
                    .isEqualTo(INFO_WIDTH_ON_THIS_MAP)
                    .isNotEqualTo(INFO_WIDTH_ON_THE_ACCOUNT_MAPS);
        }

        @Test
        @DisplayName("an eighty-character error message is accepted whole, so eighty is genuinely "
                + "the bound rather than merely the published number")
        void anEightyCharacterErrorMessageIsAcceptedWhole() {
            assertThat(violationsOf(messages(null, ERROR_MESSAGE_AT_FULL_WIDTH, true)))
                    .as("a value at exactly the declared width is within bounds")
                    .isEmpty();
        }

        @Test
        @DisplayName("a seventy-nine and an eighty character error message are both accepted, which "
                + "would be impossible had the bound been narrowed to seventy-eight")
        void theErrorBoundIsNotSeventyEight() {
            assertThat(violationsOf(messages(null, "e".repeat(79), true))).isEmpty();
            assertThat(violationsOf(messages(null, "e".repeat(80), true))).isEmpty();
        }

        @Test
        @DisplayName("an eighty-one character error message is reported, so the bound is exactly eighty")
        void theErrorBoundIsExactlyEighty() {
            Set<ConstraintViolation<CardDetailResponse>> violations =
                    violationsOf(messages(null, "e".repeat(81), true));

            assertThat(violations).hasSize(1);
            assertThat(violatingComponents(violations)).containsExactly("errorMessage");
        }

        @Test
        @DisplayName("a forty-character informational message is accepted and a forty-one character "
                + "one is reported, so the bound is exactly forty and not forty-five")
        void theInformationalBoundIsExactlyForty() {
            assertThat(violationsOf(messages(INFO_MESSAGE_AT_FULL_WIDTH, null, false))).isEmpty();
            assertThat(violationsOf(messages("i".repeat(40), null, false))).isEmpty();

            Set<ConstraintViolation<CardDetailResponse>> violations =
                    violationsOf(messages("i".repeat(41), null, false));

            assertThat(violations).hasSize(1);
            assertThat(violatingComponents(violations)).containsExactly("infoMessage");
            assertThat(violationsOf(messages("i".repeat(45), null, false)))
                    .as("forty-five is the account maps' width and must be rejected here")
                    .hasSize(1);
        }

        @Test
        @DisplayName("the two message widths differ from each other, which is the whole point of "
                + "declaring them separately")
        void theTwoMessageWidthsDifferFromEachOther() {
            assertThat(CardDetailResponse.ERROR_MESSAGE_LENGTH)
                    .isGreaterThan(CardDetailResponse.INFO_MESSAGE_LENGTH);
        }

        @Test
        @DisplayName("the informational width is not tied to the title width it happens to equal")
        void theInformationalWidthIsNotTiedToTheTitleWidth() {
            // Both are forty. They coincide by accident: a header caption and an operator message are
            // unrelated fields, so neither may be derived from the other.
            assertThat(CardDetailResponse.SCREEN_TITLE_LENGTH)
                    .isEqualTo(CardDetailResponse.INFO_MESSAGE_LENGTH);
            assertThat(violationsOf(messages("i".repeat(41), null, false))).hasSize(1);
            assertThat(violationsOf(furniture(null, "t".repeat(40), null, null, "t".repeat(40),
                    null))).isEmpty();
        }
    }

    @Nested
    @DisplayName("identifiers are bounded text, so a leading zero is never lost")
    class LeadingZeroPreservation {

        @Test
        @DisplayName("an account identifier of one keeps its ten leading zeros and never becomes one")
        void anAccountIdentifierKeepsItsLeadingZeros() {
            String read = card(ACCOUNT_ID_ALL_BUT_ONE_LEADING_ZERO, null, null, null, null, null)
                    .accountId();

            assertThat(read).isEqualTo("00000000001").hasSize(11).isNotEqualTo("1");
            assertThat(read).startsWith("0000000000");
        }

        @Test
        @DisplayName("a card number of one keeps its fifteen leading zeros and never becomes one")
        void aCardNumberKeepsItsLeadingZeros() {
            String read = card(null, CARD_NUMBER_ALL_BUT_ONE_LEADING_ZERO, null, null, null, null)
                    .cardNumber();

            assertThat(read).isEqualTo("0000000000000001").hasSize(16).isNotEqualTo("1");
            assertThat(read).startsWith("000000000000000");
        }

        @Test
        @DisplayName("an expiry month of one keeps its leading zero and never becomes one")
        void anExpiryMonthKeepsItsLeadingZero() {
            String read = card(null, null, null, null, "01", null).expiryMonth();

            assertThat(read).isEqualTo("01").hasSize(2).isNotEqualTo("1");
        }

        @Test
        @DisplayName("an all-zero identifier stays eleven zeros rather than collapsing to one zero")
        void anAllZeroIdentifierStaysAtFullWidth() {
            assertThat(card("00000000000", "0000000000000000", null, null, "00", "0000").accountId())
                    .isEqualTo("00000000000")
                    .hasSize(11)
                    .isNotEqualTo("0");
        }

        @Test
        @DisplayName("the leading zeros survive serialization as a JSON string, never as a number")
        void theLeadingZerosSurviveTheWireAsAString() throws JsonProcessingException {
            JsonNode payload = payloadOf(card(ACCOUNT_ID_ALL_BUT_ONE_LEADING_ZERO,
                    CARD_NUMBER_ALL_BUT_ONE_LEADING_ZERO, null, null, "01", "2027"));

            assertThat(payload.get("accountId").isTextual())
                    .as("a numeric JSON form could not carry a leading zero at all")
                    .isTrue();
            assertThat(payload.get("cardNumber").isTextual()).isTrue();
            assertThat(payload.get("expiryMonth").isTextual()).isTrue();
            assertThat(payload.get("expiryYear").isTextual()).isTrue();
            assertThat(payload.get("accountId").asText()).isEqualTo("00000000001");
            assertThat(payload.get("cardNumber").asText()).isEqualTo("0000000000000001");
            assertThat(payload.get("expiryMonth").asText()).isEqualTo("01");
        }

        @Test
        @DisplayName("a non-numeric identifier is carried unchanged, so the type is genuinely text")
        void aNonNumericIdentifierIsCarriedUnchanged() {
            // A numeric component could not hold any of these, so accepting them proves the component is
            // text rather than a number that happens to print with padding.
            CardDetailResponse response = card("ABCDEFGHIJK", "NOT-A-CARD-NUMB", "X", "?", "ab",
                    "cdef");

            assertThat(response.accountId()).isEqualTo("ABCDEFGHIJK");
            assertThat(response.cardNumber()).isEqualTo("NOT-A-CARD-NUMB");
            assertThat(response.expiryMonth()).isEqualTo("ab");
            assertThat(response.expiryYear()).isEqualTo("cdef");
        }
    }

    @Nested
    @DisplayName("the expiry month and year stay two separate text components")
    class SplitExpiryParts {

        @Test
        @DisplayName("the two parts are published under two keys and are never merged into one")
        void theTwoPartsArePublishedSeparately() throws JsonProcessingException {
            JsonNode payload = payloadOf(card(null, null, null, null, EXPIRY_MONTH, EXPIRY_YEAR));

            assertThat(payload.get("expiryMonth").asText()).isEqualTo("01");
            assertThat(payload.get("expiryYear").asText()).isEqualTo("2027");
            assertThat(keysOf(payload)).doesNotContain("expiryDate", "expiry", "validThru",
                    "expirationDate", "yearMonth");
        }

        @Test
        @DisplayName("neither part is concatenated into the other")
        void neitherPartIsConcatenatedIntoTheOther() {
            CardDetailResponse response = card(null, null, null, null, EXPIRY_MONTH, EXPIRY_YEAR);

            assertThat(response.expiryMonth()).doesNotContain(EXPIRY_YEAR);
            assertThat(response.expiryYear()).doesNotContain(EXPIRY_MONTH);
            assertThat(response.expiryMonth()).isNotEqualTo("012027").isNotEqualTo("2027-01");
        }

        @Test
        @DisplayName("a month outside one to twelve is carried unchanged, because no temporal type "
                + "and no formatter stands between the screen and the client")
        void anImpossibleMonthIsCarriedUnchanged() {
            // A date type would reject or rewrite each of these. The legacy screen displays whatever the
            // record holds, and the month range rule belongs to the card-update path.
            assertThat(card(null, null, null, null, "00", "0000").expiryMonth()).isEqualTo("00");
            assertThat(card(null, null, null, null, "13", "0000").expiryMonth()).isEqualTo("13");
            assertThat(card(null, null, null, null, "99", "9999").expiryMonth()).isEqualTo("99");
            assertThat(card(null, null, null, null, "  ", "    ").expiryMonth()).isEqualTo("  ");
        }

        @Test
        @DisplayName("a partially filled year is carried unchanged")
        void aPartiallyFilledYearIsCarriedUnchanged() {
            assertThat(card(null, null, null, null, null, "20").expiryYear()).isEqualTo("20");
            assertThat(card(null, null, null, null, null, "0000").expiryYear()).isEqualTo("0000");
            assertThat(card(null, null, null, null, null, " 27 ").expiryYear()).isEqualTo(" 27 ");
        }

        @Test
        @DisplayName("an impossible expiry raises no constraint violation, because the range rule "
                + "lives in the update service rather than in this response")
        void anImpossibleExpiryRaisesNoViolation() {
            assertThat(violationsOf(card(null, null, null, null, "99", "0000"))).isEmpty();
            assertThat(violationsOf(card(null, null, null, null, "00", "9999"))).isEmpty();
        }
    }

    @Nested
    @DisplayName("the embossed name is never case folded on this screen")
    class EmbossedNameIsNeverFolded {

        @Test
        @DisplayName("a mixed-case fifty-character name reads back byte for byte")
        void aMixedCaseNameReadsBackByteForByte() {
            // The card-update program folds an embossed name through a strict twenty-six character
            // substitution table. This display response performs no folding at all, so a lower-case
            // letter that arrives lower case leaves lower case.
            String read = card(null, null, EMBOSSED_NAME, null, null, null).embossedName();

            assertThat(read).isEqualTo(EMBOSSED_NAME).hasSize(50);
            assertThat(read).contains("ary Ann de la Cruz-Smith");
            assertThat(read).isNotEqualTo("MARY ANN DE LA CRUZ-SMITH" + " ".repeat(25));
        }

        @Test
        @DisplayName("a wholly lower-case name stays wholly lower case")
        void aWhollyLowerCaseNameStaysLowerCase() {
            assertThat(card(null, null, "mary ann", null, null, null).embossedName())
                    .isEqualTo("mary ann");
        }

        @Test
        @DisplayName("a name with embedded spaces is accepted, because the legacy alphabetic check "
                + "blanks letters and then measures what is left")
        void aNameWithEmbeddedSpacesIsAccepted() {
            assertThat(violationsOf(card(null, null, "Mary Ann de la Cruz", null, null, null)))
                    .isEmpty();
            assertThat(card(null, null, "Mary Ann de la Cruz", null, null, null).embossedName())
                    .isEqualTo("Mary Ann de la Cruz");
        }

        @Test
        @DisplayName("the name survives the wire with its case and its padding intact")
        void theNameSurvivesTheWireUnfolded() throws JsonProcessingException {
            JsonNode payload = payloadOf(card(null, null, EMBOSSED_NAME, null, null, null));

            assertThat(payload.get("embossedName").asText()).isEqualTo(EMBOSSED_NAME).hasSize(50);
        }
    }


    @Nested
    @DisplayName("the operator messages reproduce the legacy text character for character")
    class OperatorMessageLiterals {

        @Test
        @DisplayName("the input prompt is the thirty-six character legacy text")
        void theInputPromptIsThirtySixCharacters() {
            assertThat(CardDetailResponse.MSG_PROMPT_FOR_INPUT)
                    .isEqualTo("Please enter Account and Card Number")
                    .hasSize(36);
        }

        @Test
        @DisplayName("the exit message is thirty-four characters and keeps its fourteen trailing spaces")
        void theExitMessageKeepsItsFourteenTrailingSpaces() {
            // Twenty characters of text followed by fourteen spaces. The padding is part of the value:
            // the field it is written into is fixed width, so trimming it would change what the screen
            // rendered and what the interface-contract criterion compares.
            assertThat(CardDetailResponse.MSG_EXIT)
                    .isEqualTo("PF03 pressed.Exiting              ")
                    .hasSize(34);
            assertThat(CardDetailResponse.MSG_EXIT).endsWith("g" + " ".repeat(14));
            assertThat(CardDetailResponse.MSG_EXIT.stripTrailing()).hasSize(20);
        }

        @Test
        @DisplayName("the exit message carries no space after its full stop")
        void theExitMessageHasNoSpaceAfterItsFullStop() {
            assertThat(CardDetailResponse.MSG_EXIT)
                    .contains("pressed.Exiting")
                    .doesNotContain("pressed. Exiting");
        }

        @Test
        @DisplayName("the account diagnostic is forty-nine characters and spells the constraint as "
                + "two words")
        void theAccountDiagnosticSpellsTheConstraintAsTwoWords() {
            assertThat(CardDetailResponse.MSG_SEARCHED_ACCOUNT_ZEROES)
                    .isEqualTo("Account number must be a non zero 11 digit number")
                    .hasSize(49);
            assertThat(CardDetailResponse.MSG_SEARCHED_ACCOUNT_ZEROES)
                    .contains("a non zero 11")
                    .doesNotContain("nonzero")
                    .doesNotContain("non-zero");
        }

        @Test
        @DisplayName("the card diagnostic is forty-nine characters and reads exactly as declared")
        void theCardDiagnosticIsFortyNineCharacters() {
            assertThat(CardDetailResponse.MSG_SEARCHED_CARD_NOT_NUMERIC)
                    .isEqualTo("Card number if supplied must be a 16 digit number")
                    .hasSize(49);
        }

        @Test
        @DisplayName("the accepted-criteria message is twenty-one characters and keeps four dots")
        void theAcceptedCriteriaMessageKeepsFourDots() {
            assertThat(CardDetailResponse.MSG_CODING_TO_BE_DONE)
                    .isEqualTo("Looks Good.... so far")
                    .hasSize(21);
            assertThat(CardDetailResponse.MSG_CODING_TO_BE_DONE)
                    .contains("Good.... so")
                    .doesNotContain(".....");
            assertThat(CardDetailResponse.MSG_CODING_TO_BE_DONE.chars().filter(c -> c == '.')
                    .count())
                    .as("four consecutive dots, not the conventional three")
                    .isEqualTo(4L);
        }

        @Test
        @DisplayName("the found-details message opens with exactly three spaces")
        void theFoundDetailsMessageOpensWithThreeSpaces() {
            assertThat(CardDetailResponse.MSG_FOUND_CARDS_FOR_ACCOUNT)
                    .isEqualTo("   Displaying requested details")
                    .hasSize(31);
            assertThat(CardDetailResponse.MSG_FOUND_CARDS_FOR_ACCOUNT.stripLeading()).hasSize(28);
        }

        @Test
        @DisplayName("the two not-provided prompts and the no-input message read exactly as declared")
        void thePromptsReadExactlyAsDeclared() {
            assertThat(CardDetailResponse.MSG_PROMPT_FOR_ACCOUNT)
                    .isEqualTo("Account number not provided")
                    .hasSize(27);
            assertThat(CardDetailResponse.MSG_PROMPT_FOR_CARD)
                    .isEqualTo("Card number not provided")
                    .hasSize(24);
            assertThat(CardDetailResponse.MSG_NO_SEARCH_CRITERIA_RECEIVED)
                    .isEqualTo("No input received")
                    .hasSize(17);
        }

        @Test
        @DisplayName("the two not-found messages and the read-failure message read exactly as declared")
        void theNotFoundMessagesReadExactlyAsDeclared() {
            assertThat(CardDetailResponse.MSG_ACCOUNT_NOT_IN_CARD_CROSS_REFERENCE)
                    .isEqualTo("Did not find this account in cards database")
                    .hasSize(43);
            assertThat(CardDetailResponse.MSG_NO_CARDS_FOR_SEARCH_CONDITION)
                    .isEqualTo("Did not find cards for this search condition")
                    .hasSize(44);
            assertThat(CardDetailResponse.MSG_CARD_DATA_READ_ERROR)
                    .isEqualTo("Error reading Card Data File")
                    .hasSize(28);
        }

        @Test
        @DisplayName("the read-failure message discloses no status code, table name or internal path")
        void theReadFailureMessageDisclosesNothingInternal() {
            assertThat(CardDetailResponse.MSG_CARD_DATA_READ_ERROR)
                    .doesNotContain("SQL")
                    .doesNotContain("Exception")
                    .doesNotContain("/")
                    .doesNotContain("carddemo");
        }

        @Test
        @DisplayName("the unexpected-state message is upper case, which the mixed-case set retains")
        void theUnexpectedStateMessageIsUpperCase() {
            assertThat(CardDetailResponse.MSG_UNEXPECTED_DATA_SCENARIO)
                    .isEqualTo("UNEXPECTED DATA SCENARIO")
                    .hasSize(24);
        }

        @Test
        @DisplayName("the two filter messages keep their upper case, their missing space after the "
                + "comma and their ungrammatical article")
        void theFilterMessagesKeepTheirOddities() {
            // Three peculiarities, all contract: upper case where the rest of this screen is mixed case,
            // no space after the comma, and "A 11" where English wants "AN 11".
            assertThat(CardDetailResponse.MSG_ACCOUNT_FILTER_NOT_NUMERIC)
                    .isEqualTo("ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER")
                    .hasSize(52);
            assertThat(CardDetailResponse.MSG_CARD_FILTER_NOT_NUMERIC)
                    .isEqualTo("CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER")
                    .hasSize(52);
            assertThat(CardDetailResponse.MSG_ACCOUNT_FILTER_NOT_NUMERIC)
                    .contains("FILTER,IF")
                    .doesNotContain("FILTER, IF")
                    .contains("A 11")
                    .doesNotContain("AN 11");
            assertThat(CardDetailResponse.MSG_CARD_FILTER_NOT_NUMERIC)
                    .contains("FILTER,IF")
                    .doesNotContain("FILTER, IF")
                    .contains("A 16");
        }

        @Test
        @DisplayName("the mixed-case diagnostics are kept distinct from their upper-case counterparts, "
                + "because the casing difference is contract on this screen")
        void theMixedCaseDiagnosticsStayDistinct() {
            assertThat(CardDetailResponse.MSG_SEARCHED_ACCOUNT_NOT_NUMERIC)
                    .isNotEqualTo(CardDetailResponse.MSG_ACCOUNT_FILTER_NOT_NUMERIC);
            assertThat(CardDetailResponse.MSG_SEARCHED_CARD_NOT_NUMERIC)
                    .isNotEqualTo(CardDetailResponse.MSG_CARD_FILTER_NOT_NUMERIC);
            assertThat(CardDetailResponse.MSG_SEARCHED_CARD_NOT_NUMERIC).contains("Card number if");
            assertThat(CardDetailResponse.MSG_CARD_FILTER_NOT_NUMERIC).contains("CARD ID FILTER");
        }

        @Test
        @DisplayName("the two constants that share one legacy text remain two constants")
        void theTwoConstantsSharingOneTextRemainTwo() {
            // The program declares the same wording twice, against the all-zeros condition and against
            // the not-numeric condition. Both declarations are carried so the duplication stays visible.
            assertThat(CardDetailResponse.MSG_SEARCHED_ACCOUNT_ZEROES)
                    .isEqualTo(CardDetailResponse.MSG_SEARCHED_ACCOUNT_NOT_NUMERIC)
                    .hasSize(49);
        }

        @Test
        @DisplayName("every message fits the field it is written into, and each of the five the "
                + "contract singles out carries its measured length")
        void everyMessageFitsItsFieldAndTheFiveCarryTheirLengths() {
            List<String> everyMessage = List.of(
                    CardDetailResponse.MSG_FOUND_CARDS_FOR_ACCOUNT,
                    CardDetailResponse.MSG_PROMPT_FOR_INPUT,
                    CardDetailResponse.MSG_EXIT,
                    CardDetailResponse.MSG_PROMPT_FOR_ACCOUNT,
                    CardDetailResponse.MSG_PROMPT_FOR_CARD,
                    CardDetailResponse.MSG_NO_SEARCH_CRITERIA_RECEIVED,
                    CardDetailResponse.MSG_SEARCHED_ACCOUNT_ZEROES,
                    CardDetailResponse.MSG_SEARCHED_ACCOUNT_NOT_NUMERIC,
                    CardDetailResponse.MSG_SEARCHED_CARD_NOT_NUMERIC,
                    CardDetailResponse.MSG_ACCOUNT_NOT_IN_CARD_CROSS_REFERENCE,
                    CardDetailResponse.MSG_NO_CARDS_FOR_SEARCH_CONDITION,
                    CardDetailResponse.MSG_CARD_DATA_READ_ERROR,
                    CardDetailResponse.MSG_CODING_TO_BE_DONE,
                    CardDetailResponse.MSG_UNEXPECTED_DATA_SCENARIO,
                    CardDetailResponse.MSG_ACCOUNT_FILTER_NOT_NUMERIC,
                    CardDetailResponse.MSG_CARD_FILTER_NOT_NUMERIC);

            assertThat(everyMessage).hasSize(16);
            assertThat(everyMessage).allSatisfy(text -> assertThat(text.length())
                    .as("message that must fit the eighty-character error field: %s", text)
                    .isLessThanOrEqualTo(CardDetailResponse.ERROR_MESSAGE_LENGTH));

            assertThat(List.of(
                    CardDetailResponse.MSG_PROMPT_FOR_INPUT.length(),
                    CardDetailResponse.MSG_EXIT.length(),
                    CardDetailResponse.MSG_SEARCHED_ACCOUNT_ZEROES.length(),
                    CardDetailResponse.MSG_SEARCHED_CARD_NOT_NUMERIC.length(),
                    CardDetailResponse.MSG_CODING_TO_BE_DONE.length()))
                    .as("the five measured lengths the contract calls out")
                    .containsExactly(36, 34, 49, 49, 21);
        }

        @Test
        @DisplayName("every message travels through the response untrimmed and un-case-folded")
        void everyMessageTravelsThroughTheResponseUntouched() throws JsonProcessingException {
            List<String> carried = List.of(
                    CardDetailResponse.MSG_PROMPT_FOR_INPUT,
                    CardDetailResponse.MSG_EXIT,
                    CardDetailResponse.MSG_SEARCHED_ACCOUNT_ZEROES,
                    CardDetailResponse.MSG_SEARCHED_CARD_NOT_NUMERIC,
                    CardDetailResponse.MSG_CODING_TO_BE_DONE);

            for (String text : carried) {
                assertThat(messages(null, text, true).errorMessage())
                        .as("carried through the error line: %s", text)
                        .isEqualTo(text)
                        .hasSize(text.length());
                assertThat(payloadOf(messages(null, text, true)).get("errorMessage").asText())
                        .as("published on the error line: %s", text)
                        .isEqualTo(text)
                        .hasSize(text.length());
            }
        }

        @Test
        @DisplayName("the exit message keeps every trailing space through serialization and binding")
        void theExitMessageKeepsItsPaddingAcrossTheWire() throws JsonProcessingException {
            CardDetailResponse bound = bind(moduleEquivalentMapper()
                    .writeValueAsString(messages(null, CardDetailResponse.MSG_EXIT, true)));

            assertThat(bound.errorMessage())
                    .isEqualTo("PF03 pressed.Exiting              ")
                    .hasSize(34);
            assertThat(bound.errorMessage().stripTrailing()).hasSize(20);
        }

        @Test
        @DisplayName("the informational line carries the two informational messages the screen chooses "
                + "between, each within the forty-character field")
        void theInformationalLineCarriesItsTwoMessages() {
            assertThat(messages(CardDetailResponse.MSG_FOUND_CARDS_FOR_ACCOUNT, null, false)
                    .infoMessage())
                    .isEqualTo("   Displaying requested details");
            assertThat(messages(CardDetailResponse.MSG_PROMPT_FOR_INPUT, null, false).infoMessage())
                    .isEqualTo("Please enter Account and Card Number");
            assertThat(violationsOf(
                    messages(CardDetailResponse.MSG_FOUND_CARDS_FOR_ACCOUNT, null, false)))
                    .isEmpty();
            assertThat(violationsOf(messages(CardDetailResponse.MSG_PROMPT_FOR_INPUT, null, false)))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("the card status vocabulary is two codes, and a miss is never absorbed")
    class CardStatusVocabulary {

        @Test
        @DisplayName("declares exactly two constants")
        void declaresExactlyTwoConstants() {
            assertThat(CardStatus.values())
                    .as("the active flag has two states and the estate defines no third")
                    .containsExactly(CardStatus.Y, CardStatus.N)
                    .hasSize(2);
        }

        @Test
        @DisplayName("declares no synthetic fallback constant of any name")
        void declaresNoSyntheticFallbackConstant() {
            // A catch-all constant would be a value the estate never produces, so an unmapped code must
            // yield an empty result instead of being absorbed into one.
            List<String> names = List.of(CardStatus.Y.name(), CardStatus.N.name());

            assertThat(names).containsExactly("Y", "N");
            assertThat(names).doesNotContain("UNKNOWN", "NONE", "OTHER", "INVALID", "DEFAULT",
                    "UNSPECIFIED", "BLANK");
        }

        @Test
        @DisplayName("carries the raw code each constant is stored as")
        void carriesTheRawCodeOfEachConstant() {
            assertThat(CardStatus.Y.getCode()).isEqualTo('Y');
            assertThat(CardStatus.N.getCode()).isEqualTo('N');
        }

        @Test
        @DisplayName("reports active only for the active constant")
        void reportsActiveOnlyForTheActiveConstant() {
            assertThat(CardStatus.Y.isActive()).isTrue();
            assertThat(CardStatus.N.isActive()).isFalse();
        }

        @Test
        @DisplayName("resolves each declared code from a character")
        void resolvesEachDeclaredCodeFromACharacter() {
            assertThat(CardStatus.fromCode('Y')).contains(CardStatus.Y);
            assertThat(CardStatus.fromCode('N')).contains(CardStatus.N);
        }

        @Test
        @DisplayName("resolves each declared code from a one-character string")
        void resolvesEachDeclaredCodeFromAString() {
            assertThat(CardStatus.fromCode("Y")).contains(CardStatus.Y);
            assertThat(CardStatus.fromCode("N")).contains(CardStatus.N);
        }

        @Test
        @DisplayName("yields an empty result for an unrecognised character without throwing")
        void yieldsAnEmptyResultForAnUnrecognisedCharacter() {
            assertThatCode(() -> CardStatus.fromCode('Q')).doesNotThrowAnyException();

            assertThat(CardStatus.fromCode('Q')).isEmpty();
            assertThat(CardStatus.fromCode(' ')).isEmpty();
            assertThat(CardStatus.fromCode('*')).isEmpty();
        }

        @Test
        @DisplayName("applies no case fold, so a lower-case code does not resolve")
        void appliesNoCaseFold() {
            assertThat(CardStatus.fromCode('y')).isEmpty();
            assertThat(CardStatus.fromCode('n')).isEmpty();
            assertThat(CardStatus.fromCode("y")).isEmpty();
            assertThat(CardStatus.fromCode("n")).isEmpty();
        }

        @Test
        @DisplayName("treats an absent, padded or over-long string as unresolvable rather than failing")
        void treatsAnUnusableStringAsUnresolvable() {
            // Declared rather than cast at the call site: a typed local selects the string overload
            // without a redundant cast, which the module's zero-warning setting would reject.
            String absentCode = null;

            assertThatCode(() -> CardStatus.fromCode(absentCode)).doesNotThrowAnyException();

            assertThat(CardStatus.fromCode(absentCode)).isEmpty();
            assertThat(CardStatus.fromCode("")).isEmpty();
            assertThat(CardStatus.fromCode(" Y")).isEmpty();
            assertThat(CardStatus.fromCode("Y ")).isEmpty();
            assertThat(CardStatus.fromCode("YN")).isEmpty();
        }

        @Test
        @DisplayName("admits neither validation-flag state, because neither is a card status")
        void admitsNeitherValidationFlagState() {
            // The account-update program declares a not-OK state and a blank state beside the pair. Both
            // are states of the validation flag that drives field-level error display, never values of
            // the stored status, so neither may resolve here.
            assertThat(CardStatus.fromCode('0')).isEmpty();
            assertThat(CardStatus.fromCode('B')).isEmpty();
            assertThat(CardStatus.fromCode("0")).isEmpty();
            assertThat(CardStatus.fromCode("B")).isEmpty();
        }

        @Test
        @DisplayName("composes to a boolean in which an unresolvable code answers not active")
        void composesToABooleanForAnUnresolvableCode() {
            String absentCode = null;

            assertThat(CardStatus.fromCode("Y").map(CardStatus::isActive).orElse(false)).isTrue();
            assertThat(CardStatus.fromCode("N").map(CardStatus::isActive).orElse(false)).isFalse();
            assertThat(CardStatus.fromCode("?").map(CardStatus::isActive).orElse(false)).isFalse();
            assertThat(CardStatus.fromCode(absentCode).map(CardStatus::isActive).orElse(false))
                    .isFalse();
        }

        @Test
        @DisplayName("the response carries the raw character rather than the resolved constant, so an "
                + "unrecognised status round-trips instead of being rejected")
        void theResponseCarriesTheRawCharacter() throws JsonProcessingException {
            // The column replacing the record byte carries no check constraint and the batch programs
            // never validate it, so a value outside the two codes must survive the round trip. Resolving
            // it is the service layer's decision, and this response performs no lookup.
            CardDetailResponse response = card(null, null, null, "Q", null, null);

            assertThat(response.cardActiveStatus()).isEqualTo("Q").hasSize(1);
            assertThat(violationsOf(response)).isEmpty();
            assertThat(payloadOf(response).get("cardActiveStatus").isTextual()).isTrue();
            assertThat(payloadOf(response).get("cardActiveStatus").asText()).isEqualTo("Q");
            assertThat(CardStatus.fromCode(response.cardActiveStatus()))
                    .as("interpretation is the caller's, and it yields nothing for this code")
                    .isEmpty();
        }

        @Test
        @DisplayName("a status the response carries resolves through the enum when it is one of the two")
        void aCarriedStatusResolvesWhenItIsOneOfTheTwo() {
            Optional<CardStatus> resolved =
                    CardStatus.fromCode(card(null, null, null, "Y", null, null).cardActiveStatus());

            assertThat(resolved).contains(CardStatus.Y);
            assertThat(resolved.map(CardStatus::getCode)).contains('Y');
        }
    }


    @Nested
    @DisplayName("the navigation state is carried, never re-implemented")
    class NavigationStateCarriage {

        @Test
        @DisplayName("carries the shared navigation type rather than a private copy of its fields")
        void carriesTheSharedNavigationType() throws JsonProcessingException {
            NavigationContext carried = navigation();

            assertThat(routing(null, null, carried).navigationContext()).isSameAs(carried);
            assertThat(keysOf(payloadOf(populated())))
                    .as("one nested object, not sixteen flattened keys")
                    .contains("navigationContext")
                    .doesNotContain("userId", "fromProgram", "toProgram", "programContext",
                            "customerId", "lastMap", "lastMapset");
        }

        @Test
        @DisplayName("the carried identifiers round-trip unchanged, leading zeros included")
        void theCarriedIdentifiersRoundTripUnchanged() {
            NavigationContext carried = routing(null, null, navigation()).navigationContext();

            assertThat(carried.accountId()).isEqualTo(ACCOUNT_ID).hasSize(11);
            assertThat(carried.cardNumber()).isEqualTo(CARD_NUMBER).hasSize(16);
            assertThat(carried.customerId())
                    .as("nine characters whose value is eleven, with its leading zeros intact")
                    .isEqualTo("000000011")
                    .hasSize(9)
                    .isNotEqualTo("11");
        }

        @Test
        @DisplayName("the carried state survives serialization and binding intact")
        void theCarriedStateSurvivesTheWire() throws JsonProcessingException {
            CardDetailResponse bound = bind(moduleEquivalentMapper()
                    .writeValueAsString(routing(null, null, navigation())));
            NavigationContext carried = bound.navigationContext();

            assertThat(carried).isNotNull();
            assertThat(carried.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(carried.cardNumber()).isEqualTo(CARD_NUMBER);
            assertThat(carried.customerId()).isEqualTo("000000011");
            assertThat(carried.userId()).isEqualTo("OPERATOR");
            assertThat(carried.programContext())
                    .isEqualTo(NavigationContext.ProgramContext.REENTER);
        }

        @Test
        @DisplayName("carries the empty navigation state, which is the first-entry condition")
        void carriesTheEmptyNavigationState() {
            NavigationContext carried =
                    routing(null, null, NavigationContext.empty()).navigationContext();

            assertThat(carried).isEqualTo(NavigationContext.empty());
            assertThat(carried.accountId()).isNull();
        }

        @Test
        @DisplayName("tolerates an absent navigation state, which is the state before anything is carried")
        void toleratesAnAbsentNavigationState() throws JsonProcessingException {
            assertThat(routing(null, null, null).navigationContext()).isNull();
            assertThat(violationsOf(routing(null, null, null))).isEmpty();
            assertThat(keysOf(payloadOf(routing(null, null, null))))
                    .doesNotContain("navigationContext");
        }

        @Test
        @DisplayName("re-implements nothing the navigation state already models")
        void reImplementsNothingTheNavigationStateModels() throws JsonProcessingException {
            // The screen's own account identifier and card number are the displayed values; the echoed
            // state's are the carried ones. They are separate components on separate types and neither is
            // derived from the other, which is why both may legitimately hold the same value at once.
            CardDetailResponse response = populated();

            assertThat(response.accountId()).isEqualTo(response.navigationContext().accountId());
            assertThat(keysOf(payloadOf(response))).contains("accountId", "cardNumber",
                    "navigationContext");
            assertThat(payloadOf(response).get("navigationContext").get("accountId").asText())
                    .isEqualTo(ACCOUNT_ID);
        }
    }

    @Nested
    @DisplayName("the only declarative constraint is a length bound, so no rule fires out of order")
    class DeclarativeConstraintsAreLengthBoundsOnly {

        @Test
        @DisplayName("a wholly absent response raises no violation at all")
        void aWhollyAbsentResponseRaisesNoViolation() {
            // The screen prompting for input has no card loaded, so an empty response is a real state. A
            // presence constraint anywhere would make that state unrepresentable.
            assertThat(violationsOf(empty()))
                    .as("no presence constraint fires anywhere on this contract")
                    .isEmpty();
        }

        @Test
        @DisplayName("a wholly blank response raises no violation at all")
        void aWhollyBlankResponseRaisesNoViolation() {
            assertThat(violationsOf(blankFilled()))
                    .as("no not-blank and no not-empty constraint fires anywhere")
                    .isEmpty();
        }

        @Test
        @DisplayName("an account identifier that is not eleven digits raises no violation here, "
                + "because that rule is an ordered service check carrying its own message")
        void anAccountIdentifierThatIsNotElevenDigitsRaisesNoViolation() {
            assertThat(violationsOf(card("7", null, null, null, null, null))).isEmpty();
            assertThat(violationsOf(card("ABCDEFGHIJK", null, null, null, null, null))).isEmpty();
            assertThat(violationsOf(card("00000000000", null, null, null, null, null))).isEmpty();
            assertThat(violationsOf(card("   ", null, null, null, null, null))).isEmpty();
        }

        @Test
        @DisplayName("a card number that is not sixteen digits raises no violation here, for the "
                + "same reason")
        void aCardNumberThatIsNotSixteenDigitsRaisesNoViolation() {
            assertThat(violationsOf(card(null, "12345", null, null, null, null))).isEmpty();
            assertThat(violationsOf(card(null, "NOT-A-CARD-NUMB", null, null, null, null)))
                    .isEmpty();
            assertThat(violationsOf(card(null, " ", null, null, null, null))).isEmpty();
        }

        @Test
        @DisplayName("the messages naming those two rules exist, which is where the rules actually live")
        void theMessagesNamingThoseTwoRulesExist() {
            assertThat(CardDetailResponse.MSG_SEARCHED_ACCOUNT_ZEROES).hasSize(49);
            assertThat(CardDetailResponse.MSG_SEARCHED_CARD_NOT_NUMERIC).hasSize(49);
        }

        @Test
        @DisplayName("an over-long value is reported once and names the component it came from")
        void anOverLongValueIsReportedOnceAndNamesItsComponent() {
            Set<ConstraintViolation<CardDetailResponse>> violations =
                    violationsOf(card("0".repeat(12), null, null, null, null, null));

            assertThat(violations).hasSize(1);
            assertThat(violatingComponents(violations)).containsExactly("accountId");
        }

        @Test
        @DisplayName("each bounded component is measured at its own width, one component at a time")
        void eachBoundedComponentIsMeasuredAtItsOwnWidth() {
            assertThat(violationsOf(card("0".repeat(11), null, null, null, null, null))).isEmpty();
            assertThat(violatingComponents(
                    violationsOf(card(null, "0".repeat(17), null, null, null, null))))
                    .containsExactly("cardNumber");
            assertThat(violationsOf(card(null, "0".repeat(16), null, null, null, null))).isEmpty();
            assertThat(violatingComponents(
                    violationsOf(card(null, null, "n".repeat(51), null, null, null))))
                    .containsExactly("embossedName");
            assertThat(violationsOf(card(null, null, "n".repeat(50), null, null, null))).isEmpty();
            assertThat(violatingComponents(
                    violationsOf(card(null, null, null, "YN", null, null))))
                    .containsExactly("cardActiveStatus");
            assertThat(violatingComponents(
                    violationsOf(card(null, null, null, null, "013", null))))
                    .containsExactly("expiryMonth");
            assertThat(violatingComponents(
                    violationsOf(card(null, null, null, null, null, "20277"))))
                    .containsExactly("expiryYear");
        }

        @Test
        @DisplayName("the header items and the focus hint are bounded at their own widths too")
        void theHeaderItemsAndFocusHintAreBoundedToo() {
            assertThat(violationsOf(furniture("CCDL", null, null, null, null, null))).isEmpty();
            assertThat(violatingComponents(
                    violationsOf(furniture("CCDL0", null, null, null, null, null))))
                    .containsExactly("transactionName");
            assertThat(violatingComponents(
                    violationsOf(furniture(null, "t".repeat(41), null, null, null, null))))
                    .containsExactly("title01");
            assertThat(violatingComponents(
                    violationsOf(furniture(null, null, null, null, "t".repeat(41), null))))
                    .containsExactly("title02");
            assertThat(violatingComponents(
                    violationsOf(furniture(null, null, "080226266", null, null, null))))
                    .containsExactly("currentDate");
            assertThat(violatingComponents(
                    violationsOf(furniture(null, null, null, "COCRDSLC0", null, null))))
                    .containsExactly("programName");
            assertThat(violatingComponents(
                    violationsOf(furniture(null, null, null, null, null, "14:30:001"))))
                    .containsExactly("currentTime");
            assertThat(violatingComponents(violationsOf(routing("ACCTSID0", null, null))))
                    .containsExactly("focusScreenFieldId");
            assertThat(violationsOf(routing(CardDetailResponse.SCREEN_FIELD_CARD_NUMBER, null,
                    null))).isEmpty();
        }

        @Test
        @DisplayName("a length bound measures and never alters, so surrounding spaces survive validation")
        void aLengthBoundMeasuresAndNeverAlters() {
            CardDetailResponse response =
                    messages(null, CardDetailResponse.MSG_EXIT, true);

            assertThat(violationsOf(response)).isEmpty();
            assertThat(response.errorMessage()).hasSize(34).endsWith(" ");
        }

        @Test
        @DisplayName("a fully populated response at every declared width raises no violation")
        void aFullyPopulatedResponseRaisesNoViolation() {
            assertThat(violationsOf(populated())).isEmpty();
        }

        @Test
        @DisplayName("the two decorated-but-unchecked fields of the account screen have no analogue "
                + "here, and no character-class rule is applied to any name")
        void noCharacterClassRuleIsAppliedToAnyName() {
            // The legacy alphabetic check blanks every letter and then measures the remainder, so a value
            // with embedded spaces passes it. A character-class constraint would reject input the legacy
            // accepts.
            assertThat(violationsOf(card(null, null, "Mary Ann", null, null, null))).isEmpty();
            assertThat(violationsOf(card(null, null, "  ", null, null, null))).isEmpty();
            assertThat(violationsOf(card(null, null, "O'Neill-Smith Jr", null, null, null)))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("the route is declarative data and never a decision")
    class RouteIsDeclarativeData {

        @Test
        @DisplayName("carries any token unchanged, so nothing is resolved against a route table")
        void carriesAnyTokenUnchanged() {
            // The legacy transferred control between programs; this response merely names where the client
            // may go next. The token vocabulary belongs to the navigation service, so an unrecognisable
            // token must be carried rather than rejected or rewritten.
            assertThat(routing(null, "/api/cards/detail", null).nextRoute())
                    .isEqualTo("/api/cards/detail");
            assertThat(routing(null, "not-a-route-at-all", null).nextRoute())
                    .isEqualTo("not-a-route-at-all");
            assertThat(routing(null, "", null).nextRoute()).isEmpty();
            assertThat(routing(null, "   ", null).nextRoute()).isEqualTo("   ");
        }

        @Test
        @DisplayName("leaves the route unbounded, because no legacy field declares a width for one")
        void leavesTheRouteUnbounded() {
            assertThat(violationsOf(routing(null, "/".repeat(4096), null)))
                    .as("a route is an endpoint path invented here, not a screen field")
                    .isEmpty();
        }

        @Test
        @DisplayName("publishes the route as a plain string, never as an enumerated value")
        void publishesTheRouteAsAPlainString() throws JsonProcessingException {
            JsonNode published = payloadOf(routing(null, "/api/cards/detail", null)).get("nextRoute");

            assertThat(published.isTextual()).isTrue();
            assertThat(published.asText()).isEqualTo("/api/cards/detail");
        }

        @Test
        @DisplayName("publishes no route table, route registry or route enumeration of any kind")
        void publishesNoRouteTable() throws JsonProcessingException {
            List<String> keys = keysOf(payloadOf(populated()));

            assertThat(keys).contains("nextRoute");
            assertThat(keys).doesNotContain("routes", "routeTable", "routeRegistry",
                    "availableRoutes", "allowedRoutes", "transferProgram", "xctlProgram");
        }

        @Test
        @DisplayName("the focus hint is an identity and carries no position or attribute")
        void theFocusHintIsAnIdentityOnly() throws JsonProcessingException {
            CardDetailResponse response =
                    routing(CardDetailResponse.SCREEN_FIELD_CARD_NUMBER, null, null);

            assertThat(response.focusScreenFieldId()).isEqualTo("CARDSID").hasSize(7);
            assertThat(keysOf(payloadOf(response)))
                    .doesNotContain("cursorRow", "cursorColumn", "cursorOffset", "row", "column");
        }

        @Test
        @DisplayName("the focus hint may be absent, leaving placement to the client")
        void theFocusHintMayBeAbsent() throws JsonProcessingException {
            assertThat(routing(null, null, null).focusScreenFieldId()).isNull();
            assertThat(keysOf(payloadOf(routing(null, null, null))))
                    .doesNotContain("focusScreenFieldId");
        }

        @Test
        @DisplayName("the error indicator is its own fact, not a shadow of the error message")
        void theErrorIndicatorIsItsOwnFact() {
            // The legacy flag and the legacy message are separate values the program sets independently.
            // A caller that inferred one from the other would report an error for a screen carrying only
            // an informational line.
            assertThat(messages(null, null, true).generalError()).isTrue();
            assertThat(messages(null, null, true).errorMessage()).isNull();
            assertThat(messages(null, ERROR_MESSAGE_AT_FULL_WIDTH, false).generalError()).isFalse();
            assertThat(messages(CardDetailResponse.MSG_PROMPT_FOR_INPUT, null, false).generalError())
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("nullability, immutability and the shape of the published payload")
    class NullabilityImmutabilityAndJsonShape {

        @Test
        @DisplayName("every text component tolerates an absent value")
        void everyTextComponentToleratesAnAbsentValue() {
            CardDetailResponse response = empty();

            assertThat(response.transactionName()).isNull();
            assertThat(response.title01()).isNull();
            assertThat(response.currentDate()).isNull();
            assertThat(response.programName()).isNull();
            assertThat(response.title02()).isNull();
            assertThat(response.currentTime()).isNull();
            assertThat(response.accountId()).isNull();
            assertThat(response.cardNumber()).isNull();
            assertThat(response.embossedName()).isNull();
            assertThat(response.cardActiveStatus()).isNull();
            assertThat(response.expiryMonth()).isNull();
            assertThat(response.expiryYear()).isNull();
            assertThat(response.infoMessage()).isNull();
            assertThat(response.errorMessage()).isNull();
            assertThat(response.focusScreenFieldId()).isNull();
            assertThat(response.nextRoute()).isNull();
            assertThat(response.navigationContext()).isNull();
        }

        @Test
        @DisplayName("the error indicator is a primitive, so it is always answered")
        void theErrorIndicatorIsAlwaysAnswered() throws JsonProcessingException {
            assertThat(empty().generalError()).isFalse();
            assertThat(keysOf(payloadOf(empty()))).containsExactly("generalError");
        }

        @Test
        @DisplayName("an absent value is omitted from the payload rather than published as a null")
        void anAbsentValueIsOmittedRatherThanPublishedAsNull() throws JsonProcessingException {
            JsonNode payload = payloadOf(empty());

            assertThat(payload.size())
                    .as("only the primitive indicator remains when nothing else is populated")
                    .isEqualTo(1);
            assertThat(payload.has("cardNumber")).isFalse();
            assertThat(payload.has("accountId")).isFalse();
            assertThat(payload.has("navigationContext")).isFalse();
            assertThat(payload.toString()).doesNotContain("null");
        }

        @Test
        @DisplayName("a present but empty value is published, because absent and empty are different")
        void aPresentButEmptyValueIsPublished() throws JsonProcessingException {
            JsonNode payload = payloadOf(card("", null, null, null, null, null));

            assertThat(payload.has("accountId")).isTrue();
            assertThat(payload.get("accountId").asText()).isEmpty();
            assertThat(payload.has("cardNumber")).isFalse();
        }

        @Test
        @DisplayName("an unknown incoming property is tolerated rather than rejected")
        void anUnknownIncomingPropertyIsTolerated() throws JsonProcessingException {
            CardDetailResponse bound = bind("{\"accountId\":\"" + ACCOUNT_ID + "\","
                    + "\"expiryDay\":\"15\",\"fkeys\":\"F3=Exit\",\"somethingNobodyDeclared\":1}");

            assertThat(bound.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(bound.cardNumber()).isNull();
        }

        @Test
        @DisplayName("an unknown incoming property is not echoed back on the way out")
        void anUnknownIncomingPropertyIsNotEchoedBack() throws JsonProcessingException {
            CardDetailResponse bound = bind("{\"accountId\":\"" + ACCOUNT_ID + "\","
                    + "\"expiryDay\":\"15\",\"fkeys\":\"F3=Exit\"}");

            assertThat(keysOf(payloadOf(bound)))
                    .doesNotContain("expiryDay", "fkeys")
                    .containsExactly("accountId", "generalError");
        }

        @Test
        @DisplayName("the type is immutable: construction is the only way to set a value")
        void theTypeIsImmutable() {
            // Demonstrated by construction. Two instances built from the same values are equal and
            // neither can be altered afterwards, because the record exposes accessors only; there is no
            // setter, no wither and no mutable member to reach.
            CardDetailResponse first = populated();
            CardDetailResponse second = populated();

            assertThat(first).isEqualTo(second).isNotSameAs(second);
            assertThat(first.cardNumber()).isEqualTo(CARD_NUMBER);
            assertThat(second.cardNumber()).isEqualTo(CARD_NUMBER);
        }

        @Test
        @DisplayName("reading a component repeatedly returns the identical value every time")
        void readingAComponentRepeatedlyReturnsTheIdenticalValue() {
            CardDetailResponse response = populated();

            assertThat(response.cardNumber()).isSameAs(response.cardNumber());
            assertThat(response.embossedName()).isSameAs(response.embossedName());
            assertThat(response.navigationContext()).isSameAs(response.navigationContext());
        }

        @Test
        @DisplayName("no monetary value appears, so no scaling decision belongs to this contract")
        void noMonetaryValueAppears() throws JsonProcessingException {
            List<String> keys = keysOf(payloadOf(populated()));

            assertThat(keys).doesNotContain("amount", "balance", "creditLimit", "currentBalance",
                    "interestRate", "cycleCredit", "cycleDebit");
        }

        @Test
        @DisplayName("no verification code and no security code appears anywhere")
        void noVerificationCodeAppears() throws JsonProcessingException {
            // The card record holds a three-digit verification code beside the number. This screen never
            // displayed it and this response never carries it.
            List<String> keys = keysOf(payloadOf(populated()));

            assertThat(keys).doesNotContain("cvv", "cvvCode", "cardCvvCode", "securityCode",
                    "verificationCode", "pin");
        }
    }

    @Nested
    @DisplayName("the diagnostic rendering withholds the payment instrument and nothing else")
    class DiagnosticRendering {

        /**
         * A populated response whose navigation state is absent.
         *
         * <p>Used wherever an assertion must be about this record's own rendering. The nested navigation
         * contract prints values of its own and names some of the same components, and building without
         * it keeps the delegated segment to a single literal, so the rendering can be asserted whole
         * rather than sliced.
         *
         * @return a populated response carrying no navigation state
         */
        private CardDetailResponse withoutNavigation() {
            return new CardDetailResponse("CCDL", "AWS Mainframe Modernization", "08/02/26",
                    "COCRDSLC", "CardDemo", "14:30:00", ACCOUNT_ID, CARD_NUMBER, EMBOSSED_NAME, "Y",
                    EXPIRY_MONTH, EXPIRY_YEAR, INFO_MESSAGE_AT_FULL_WIDTH,
                    ERROR_MESSAGE_AT_FULL_WIDTH, true, CardDetailResponse.SCREEN_FIELD_ACCOUNT_ID,
                    "/api/cards/detail", null);
        }

        @Test
        @DisplayName("withholds the account identifier, the card number, the name and both expiry parts")
        void withholdsTheWholePaymentInstrument() {
            String rendered = withoutNavigation().toString();

            assertThat(rendered).contains("accountId=" + REDACTED, "cardNumber=" + REDACTED,
                    "embossedName=" + REDACTED, "expiryMonth=" + REDACTED,
                    "expiryYear=" + REDACTED);
            assertThat(rendered).doesNotContain(ACCOUNT_ID);
            assertThat(rendered).doesNotContain(CARD_NUMBER);
            assertThat(rendered).doesNotContain("Mary Ann de la Cruz-Smith");
            assertThat(rendered).doesNotContain(EXPIRY_YEAR);
        }

        @Test
        @DisplayName("substitutes one fixed stand-in for each of exactly five components, and for no "
                + "other component")
        void substitutesTheStandInForExactlyFiveComponents() {
            // Named rather than counted, so the assertion pins which five are withheld and which twelve
            // are not. A stand-in is a constant and never a transformation of the value, so neither the
            // length nor a prefix nor a digest of a withheld component can be recovered from a rendering.
            String rendered = withoutNavigation().toString();

            assertThat(rendered).containsSubsequence("accountId=" + REDACTED,
                    "cardNumber=" + REDACTED, "embossedName=" + REDACTED,
                    "expiryMonth=" + REDACTED, "expiryYear=" + REDACTED);
            assertThat(rendered).doesNotContain("transactionName=" + REDACTED,
                    "title01=" + REDACTED, "currentDate=" + REDACTED, "programName=" + REDACTED,
                    "title02=" + REDACTED, "currentTime=" + REDACTED,
                    "cardActiveStatus=" + REDACTED, "infoMessage=" + REDACTED,
                    "errorMessage=" + REDACTED, "generalError=" + REDACTED,
                    "focusScreenFieldId=" + REDACTED, "nextRoute=" + REDACTED,
                    "navigationContext=" + REDACTED);
        }

        @Test
        @DisplayName("renders a withheld component identically whether it held a value or nothing")
        void rendersAWithheldComponentIdenticallyWhetherPopulatedOrNot() {
            assertThat(empty().toString()).contains("cardNumber=" + REDACTED);
            assertThat(withoutNavigation().toString()).contains("cardNumber=" + REDACTED);
        }

        @Test
        @DisplayName("renders two responses differing only in their regulated values identically")
        void rendersTwoResponsesDifferingOnlyInRegulatedValuesIdentically() {
            CardDetailResponse one = card(ACCOUNT_ID, CARD_NUMBER, EMBOSSED_NAME, "Y",
                    EXPIRY_MONTH, EXPIRY_YEAR);
            CardDetailResponse other = card(ACCOUNT_ID_ALL_BUT_ONE_LEADING_ZERO,
                    CARD_NUMBER_ALL_BUT_ONE_LEADING_ZERO, "Someone Else", "Y", "12", "2099");

            assertThat(one).isNotEqualTo(other);
            assertThat(one.toString()).isEqualTo(other.toString());
        }

        @Test
        @DisplayName("retains the screen furniture, the status, the messages, the flag and the route")
        void retainsEverythingThatIdentifiesNobody() {
            String rendered = withoutNavigation().toString();

            assertThat(rendered).contains("transactionName=CCDL",
                    "title01=AWS Mainframe Modernization", "currentDate=08/02/26",
                    "programName=COCRDSLC", "title02=CardDemo", "currentTime=14:30:00",
                    "cardActiveStatus=Y", "generalError=true", "focusScreenFieldId=ACCTSID",
                    "nextRoute=/api/cards/detail");
            assertThat(rendered).contains("Displaying requested details");
        }

        @Test
        @DisplayName("is bracketed by the type name and closes the navigation state by delegation")
        void isBracketedByTheTypeName() {
            assertThat(withoutNavigation().toString())
                    .startsWith("CardDetailResponse[")
                    .endsWith("navigationContext=null]");
            assertThat(populated().toString())
                    .startsWith("CardDetailResponse[")
                    .contains(", navigationContext=NavigationContext[")
                    .endsWith("]");
        }

        @Test
        @DisplayName("delegates to the navigation state, which withholds its own identifying values")
        void delegatesToTheNavigationState() {
            String rendered = populated().toString();

            assertThat(rendered).doesNotContain(ACCOUNT_ID);
            assertThat(rendered).doesNotContain(CARD_NUMBER);
            assertThat(rendered).doesNotContain("000000011");
            assertThat(rendered).contains("userId=OPERATOR", "programContext=REENTER");
        }

        @Test
        @DisplayName("changes nothing an accessor returns")
        void changesNothingAnAccessorReturns() {
            CardDetailResponse response = withoutNavigation();
            String ignoredRendering = response.toString();

            assertThat(ignoredRendering).isNotEmpty();
            assertThat(response.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(response.cardNumber()).isEqualTo(CARD_NUMBER);
            assertThat(response.embossedName()).isEqualTo(EMBOSSED_NAME);
            assertThat(response.expiryMonth()).isEqualTo(EXPIRY_MONTH);
            assertThat(response.expiryYear()).isEqualTo(EXPIRY_YEAR);
        }

        @Test
        @DisplayName("changes nothing on the wire, where the values are the contract")
        void changesNothingOnTheWire() throws JsonProcessingException {
            JsonNode payload = payloadOf(withoutNavigation());

            assertThat(payload.get("accountId").asText()).isEqualTo(ACCOUNT_ID);
            assertThat(payload.get("cardNumber").asText()).isEqualTo(CARD_NUMBER);
            assertThat(payload.get("embossedName").asText()).isEqualTo(EMBOSSED_NAME);
            assertThat(payload.get("expiryMonth").asText()).isEqualTo(EXPIRY_MONTH);
            assertThat(payload.get("expiryYear").asText()).isEqualTo(EXPIRY_YEAR);
            assertThat(payload.toString()).doesNotContain(REDACTED);
        }

        @Test
        @DisplayName("tolerates a response with nothing populated")
        void toleratesAResponseWithNothingPopulated() {
            assertThat(empty().toString())
                    .startsWith("CardDetailResponse[")
                    .contains("transactionName=null", "cardActiveStatus=null",
                            "generalError=false", "navigationContext=null")
                    .endsWith("]");
        }
    }

    @Nested
    @DisplayName("value semantics")
    class ValueSemantics {

        @Test
        @DisplayName("two responses built from the same values are equal and share a hash")
        void twoResponsesFromTheSameValuesAreEqual() {
            CardDetailResponse first = populated();
            CardDetailResponse second = populated();

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("a response differing in one component is unequal, regulated or not")
        void aResponseDifferingInOneComponentIsUnequal() {
            assertThat(card(ACCOUNT_ID, null, null, null, null, null))
                    .isNotEqualTo(card(ACCOUNT_ID_ALL_BUT_ONE_LEADING_ZERO, null, null, null, null,
                            null));
            assertThat(messages(null, null, true)).isNotEqualTo(messages(null, null, false));
            assertThat(furniture("CCDL", null, null, null, null, null))
                    .isNotEqualTo(furniture("CCUP", null, null, null, null, null));
        }

        @Test
        @DisplayName("two absent responses are equal, so the empty state is a value like any other")
        void twoAbsentResponsesAreEqual() {
            assertThat(empty()).isEqualTo(empty()).hasSameHashCodeAs(empty());
            assertThat(empty()).isNotEqualTo(blankFilled());
        }

        @Test
        @DisplayName("equality compares the whole payload, including the echoed navigation state")
        void equalityComparesTheEchoedNavigationState() {
            assertThat(routing(null, null, navigation()))
                    .isEqualTo(routing(null, null, navigation()));
            assertThat(routing(null, null, navigation()))
                    .isNotEqualTo(routing(null, null, NavigationContext.empty()));
            assertThat(routing(null, null, null))
                    .isNotEqualTo(routing(null, null, NavigationContext.empty()));
        }

        @Test
        @DisplayName("a response is unequal to an unrelated value and to nothing at all")
        void aResponseIsUnequalToAnUnrelatedValue() {
            assertThat(empty()).isNotEqualTo(null).isNotEqualTo("CardDetailResponse[]");
        }

        @Test
        @DisplayName("every accessor on a fully populated response answers the value it was built with")
        void everyAccessorAnswersTheValueItWasBuiltWith() {
            CardDetailResponse response = populated();

            assertThat(response.transactionName()).isEqualTo("CCDL");
            assertThat(response.title01()).isEqualTo("AWS Mainframe Modernization");
            assertThat(response.currentDate()).isEqualTo("08/02/26");
            assertThat(response.programName()).isEqualTo("COCRDSLC");
            assertThat(response.title02()).isEqualTo("CardDemo");
            assertThat(response.currentTime()).isEqualTo("14:30:00");
            assertThat(response.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(response.cardNumber()).isEqualTo(CARD_NUMBER);
            assertThat(response.embossedName()).isEqualTo(EMBOSSED_NAME);
            assertThat(response.cardActiveStatus()).isEqualTo("Y");
            assertThat(response.expiryMonth()).isEqualTo(EXPIRY_MONTH);
            assertThat(response.expiryYear()).isEqualTo(EXPIRY_YEAR);
            assertThat(response.infoMessage()).isEqualTo(INFO_MESSAGE_AT_FULL_WIDTH);
            assertThat(response.errorMessage()).isEqualTo(ERROR_MESSAGE_AT_FULL_WIDTH);
            assertThat(response.generalError()).isTrue();
            assertThat(response.focusScreenFieldId()).isEqualTo("ACCTSID");
            assertThat(response.nextRoute()).isEqualTo("/api/cards/detail");
            assertThat(response.navigationContext()).isEqualTo(navigation());
        }
    }

}
