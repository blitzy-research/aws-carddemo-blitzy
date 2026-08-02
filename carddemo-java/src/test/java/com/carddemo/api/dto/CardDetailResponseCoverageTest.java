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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CardDetailResponse}, the response body of legacy transaction {@code CCDL}.
 *
 * <h2>What is under test</h2>
 *
 * <p>The transport contract of the card-detail response: the eighteen components in the order the
 * screen presents them, the fourteen declared widths that bound fifteen of those components, the two
 * screen-field identities, the sixteen single-purpose texts, the wire form under the module's declared
 * serialisation settings, and the value semantics of a record that normalises nothing and computes
 * nothing.
 *
 * <h2>The same wording is declared twice, and collapsing the pair would erase a fact</h2>
 *
 * <p>{@link CardDetailResponse#MSG_SEARCHED_ACCOUNT_ZEROES} and
 * {@link CardDetailResponse#MSG_SEARCHED_ACCOUNT_NOT_NUMERIC} carry byte-identical text. That is not
 * duplication to be tidied away: the legacy program declares the wording twice, once against the
 * all-zeros condition and once against the not-numeric condition, and each constant records one
 * declaration site. Aliasing either to the other, or deleting one, would lose the record of a second
 * declaration while leaving every text assertion passing. Tests below prove the two names both exist
 * as separately declared fields <em>and</em> that they are equal by value, which is the only pair of
 * claims that pins the arrangement.
 *
 * <h2>Three texts carry whitespace and punctuation that must not be repaired</h2>
 *
 * <p>The display message opens with three spaces that are part of its value and is thirty-one
 * characters long. The exit message has no space after its period and closes with fourteen trailing
 * spaces, for thirty-four characters in total. The provisional message spells its ellipsis with four
 * consecutive dots rather than three. Each of the three reads like a typographical accident and each
 * is compared character for character by the interface-contract acceptance criterion, so each is
 * asserted here in the exact form it must keep - trimming, collapsing or correcting any of them is a
 * plausible-looking change that this suite is here to stop.
 *
 * <h2>The informational width is forty here, and is deliberately not the forty-five used next door</h2>
 *
 * <p>{@link CardDetailResponse#INFO_MESSAGE_LENGTH} is declared locally and shared with no other
 * response type precisely so a neighbouring screen's wider informational width cannot silently widen
 * this one. A test below pins it to forty and states the value it must not become.
 *
 * <h2>Five of the eighteen components are withheld from the diagnostic rendering</h2>
 *
 * <p>This type declares a diagnostic override. {@code accountId}, {@code cardNumber},
 * {@code embossedName}, {@code expiryMonth} and {@code expiryYear} are replaced by a fixed stand-in,
 * and the remaining thirteen are rendered under their declared names. The legacy design applied no
 * field-level protection to a primary account number anywhere, which is a gap recorded in
 * {@code docs/decision-log.md} rather than a contract to reproduce: a rendering is a diagnostic
 * channel, not an external interface, so withholding there closes the exposure without altering what
 * the response carries. The accessors and the serialised form still return every value unaltered, and
 * tests below assert both halves of that - the rendering withholds, the contract does not.
 *
 * <p>The withholding is unconditional. The stand-in appears whether or not the component holds a
 * value, because a stand-in that appeared only when a value was present would itself disclose
 * presence. A nested {@link NavigationContext} withholds its own identifying components in addition,
 * so a value that both levels protect appears as a stand-in twice rather than once.
 *
 * <h2>How the wire form is observed, and what that does and does not prove</h2>
 *
 * <p>A pure unit test: no context, no connection, no container. Payloads come from
 * {@link JsonContractSupport#declaredSettingsMapper()}, the single place in this package where the
 * module's four declared serialisation settings are written by hand. That evidences the shape this
 * type takes under those settings and nothing more; it is not evidence about the mapper a deployed
 * instance holds, and no assertion below is worded as though it were.
 * {@link ApplicationJsonContractTest} is the in-boundary evidence for the deployed object.
 *
 * <p>Provenance: checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is reproduced.
 */
@DisplayName("CardDetailResponse :: card-detail response contract of legacy transaction CCDL")
class CardDetailResponseCoverageTest {

    /** The eighteen components in declaration order. */
    private static final List<String> EXPECTED_COMPONENTS = List.of(
            "transactionName", "title01", "currentDate", "programName", "title02",
            "currentTime", "accountId", "cardNumber", "embossedName", "cardActiveStatus",
            "expiryMonth", "expiryYear", "infoMessage", "errorMessage", "generalError",
            "focusScreenFieldId", "nextRoute", "navigationContext");

    /** The fifteen components that declare a width bound. */
    private static final List<String> BOUNDED_COMPONENTS = List.of(
            "transactionName", "title01", "currentDate", "programName", "title02",
            "currentTime", "accountId", "cardNumber", "embossedName", "cardActiveStatus",
            "expiryMonth", "expiryYear", "infoMessage", "errorMessage", "focusScreenFieldId");

    /** Transaction name shown in the screen header. */
    private static final String TRANSACTION_NAME = "CCDL";

    /** Program name shown in the screen header. */
    private static final String PROGRAM_NAME = "COCRDSLC";

    /** First title line, at the declared forty characters. */
    private static final String SCREEN_TITLE_LINE_1 = "      AWS Mainframe Modernization       ";

    /** Second title line, at the declared forty characters. */
    private static final String SCREEN_TITLE_LINE_2 = "              CardDemo                  ";

    /** Header date, at the declared eight characters. */
    private static final String CURRENT_DATE = "08/02/26";

    /** Header time, at the declared eight characters. */
    private static final String CURRENT_TIME = "14:35:07";

    /** Eleven-character account identifier whose leading zeros are contract. */
    private static final String ACCOUNT_ID = "00000000011";

    /** Sixteen-character card number whose leading zeros are contract. */
    private static final String CARD_NUMBER = "0000000000000001";

    /** Embossed name, which the estate accepts with embedded spaces. */
    private static final String EMBOSSED_NAME = "MARY ANN SMITH";

    /** Card status character, held raw rather than typed. */
    private static final String CARD_ACTIVE_STATUS = "Y";

    /** Expiry month part, carried as text. */
    private static final String EXPIRY_MONTH = "08";

    /** Expiry year part, carried as text. */
    private static final String EXPIRY_YEAR = "2026";

    /** Opaque next-call token. */
    private static final String ROUTE = "/api/cards";

    /** Shared validator factory, opened once and closed once. */
    private static ValidatorFactory validatorFactory;

    /** Validator drawn from {@link #validatorFactory}. */
    private static Validator validator;

    /** Opens the validator factory used by the bound assertions. */
    @BeforeAll
    static void openValidatorFactory() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    /** Closes the validator factory opened by {@link #openValidatorFactory()}. */
    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }

    /**
     * The two texts the type's own documentation names as carried into the informational line.
     *
     * @return the informational texts
     */
    static Stream<String> informationalCarriedTexts() {
        return Stream.of(
                CardDetailResponse.MSG_FOUND_CARDS_FOR_ACCOUNT,
                CardDetailResponse.MSG_PROMPT_FOR_INPUT);
    }

    /**
     * The thirteen texts the type's own documentation names as carried into the error line.
     *
     * @return the error texts
     */
    static Stream<String> errorCarriedTexts() {
        return Stream.of(
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
    }

    /**
     * Builds a response carrying only the named text component, so a violation can be attributed to
     * one property rather than inferred from a set.
     *
     * @param component the component to populate
     * @param value the value to place in it
     * @return a response carrying that one value
     */
    private static CardDetailResponse carrying(String component, String value) {
        return new CardDetailResponse(
                "transactionName".equals(component) ? value : null,
                "title01".equals(component) ? value : null,
                "currentDate".equals(component) ? value : null,
                "programName".equals(component) ? value : null,
                "title02".equals(component) ? value : null,
                "currentTime".equals(component) ? value : null,
                "accountId".equals(component) ? value : null,
                "cardNumber".equals(component) ? value : null,
                "embossedName".equals(component) ? value : null,
                "cardActiveStatus".equals(component) ? value : null,
                "expiryMonth".equals(component) ? value : null,
                "expiryYear".equals(component) ? value : null,
                "infoMessage".equals(component) ? value : null,
                "errorMessage".equals(component) ? value : null,
                false,
                "focusScreenFieldId".equals(component) ? value : null,
                "nextRoute".equals(component) ? value : null,
                null);
    }

    /** @return a response in which every component is absent and the error indicator is clear. */
    private static CardDetailResponse empty() {
        return new CardDetailResponse(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, false, null, null, null);
    }

    /**
     * Builds the response a located card produces, with every component populated.
     *
     * @param navigation the navigation state to echo, which may be {@code null}
     * @return a fully populated display response
     */
    private static CardDetailResponse displayed(NavigationContext navigation) {
        return new CardDetailResponse(TRANSACTION_NAME, SCREEN_TITLE_LINE_1, CURRENT_DATE,
                PROGRAM_NAME, SCREEN_TITLE_LINE_2, CURRENT_TIME, ACCOUNT_ID, CARD_NUMBER,
                EMBOSSED_NAME, CARD_ACTIVE_STATUS, EXPIRY_MONTH, EXPIRY_YEAR,
                CardDetailResponse.MSG_FOUND_CARDS_FOR_ACCOUNT, null, false,
                CardDetailResponse.SCREEN_FIELD_ACCOUNT_ID, ROUTE, navigation);
    }

    /**
     * Serialises a response and parses the result back into a tree.
     *
     * @param response the response to render
     * @return the parsed payload
     * @throws JsonProcessingException if rendering or parsing fails
     */
    private static JsonNode payloadOf(CardDetailResponse response) throws JsonProcessingException {
        ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();
        return mapper.readTree(mapper.writeValueAsString(response));
    }

    @Nested
    @DisplayName("Declared contract")
    class DeclaredContract {

        @Test
        @DisplayName("the eighteen components are declared in the order the screen presents them")
        void componentsAreDeclaredInScreenOrder() {
            List<String> declared = Arrays.stream(CardDetailResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared).containsExactlyElementsOf(EXPECTED_COMPONENTS);
        }

        @Test
        @DisplayName("the fourteen published widths equal the widths the map and the program declare")
        void publishedWidthsEqualTheDeclaredWidths() {
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
            assertThat(CardDetailResponse.INFO_MESSAGE_LENGTH).isEqualTo(40);
            assertThat(CardDetailResponse.ERROR_MESSAGE_LENGTH).isEqualTo(80);
            assertThat(CardDetailResponse.FOCUS_SCREEN_FIELD_ID_LENGTH).isEqualTo(7);
        }

        @Test
        @DisplayName("fifteen components are bounded by fourteen widths, because the two title lines "
                + "share one")
        void fifteenBoundedComponentsShareFourteenWidths() throws NoSuchFieldException {
            List<String> bounded = new ArrayList<>();
            for (String component : EXPECTED_COMPONENTS) {
                if (CardDetailResponse.class.getDeclaredField(component)
                        .getAnnotation(Size.class) != null) {
                    bounded.add(component);
                }
            }

            assertThat(bounded).containsExactlyElementsOf(BOUNDED_COMPONENTS);
            assertThat(boundOf("title01"))
                    .isEqualTo(boundOf("title02"))
                    .isEqualTo(CardDetailResponse.SCREEN_TITLE_LENGTH);
        }

        @Test
        @DisplayName("each bounded component declares the width its own constant publishes")
        void eachBoundedComponentDeclaresItsPublishedWidth() throws NoSuchFieldException {
            assertThat(boundOf("transactionName"))
                    .isEqualTo(CardDetailResponse.TRANSACTION_NAME_LENGTH);
            assertThat(boundOf("currentDate")).isEqualTo(CardDetailResponse.CURRENT_DATE_LENGTH);
            assertThat(boundOf("programName")).isEqualTo(CardDetailResponse.PROGRAM_NAME_LENGTH);
            assertThat(boundOf("currentTime")).isEqualTo(CardDetailResponse.CURRENT_TIME_LENGTH);
            assertThat(boundOf("accountId")).isEqualTo(CardDetailResponse.ACCOUNT_ID_LENGTH);
            assertThat(boundOf("cardNumber")).isEqualTo(CardDetailResponse.CARD_NUMBER_LENGTH);
            assertThat(boundOf("embossedName")).isEqualTo(CardDetailResponse.EMBOSSED_NAME_LENGTH);
            assertThat(boundOf("cardActiveStatus"))
                    .isEqualTo(CardDetailResponse.CARD_ACTIVE_STATUS_LENGTH);
            assertThat(boundOf("expiryMonth")).isEqualTo(CardDetailResponse.EXPIRY_MONTH_LENGTH);
            assertThat(boundOf("expiryYear")).isEqualTo(CardDetailResponse.EXPIRY_YEAR_LENGTH);
            assertThat(boundOf("infoMessage")).isEqualTo(CardDetailResponse.INFO_MESSAGE_LENGTH);
            assertThat(boundOf("errorMessage")).isEqualTo(CardDetailResponse.ERROR_MESSAGE_LENGTH);
            assertThat(boundOf("focusScreenFieldId"))
                    .isEqualTo(CardDetailResponse.FOCUS_SCREEN_FIELD_ID_LENGTH);
        }

        @Test
        @DisplayName("the informational width is forty rather than the forty-five a neighbouring "
                + "screen uses, and the two message widths differ from one another")
        void theInformationalWidthIsFortyAndIsNotTheWiderNeighbouringWidth() {
            assertThat(CardDetailResponse.INFO_MESSAGE_LENGTH)
                    .as("declared locally so a neighbouring screen's wider informational width "
                            + "cannot silently widen this one")
                    .isEqualTo(40)
                    .isNotEqualTo(45);
            assertThat(CardDetailResponse.ERROR_MESSAGE_LENGTH)
                    .as("the error line is twice the informational line on this map, and the two "
                            + "are separate widths rather than one shared constant")
                    .isEqualTo(80)
                    .isNotEqualTo(CardDetailResponse.INFO_MESSAGE_LENGTH);
        }

        @Test
        @DisplayName("every identifier and every date part is carried as text, so leading zeros and "
                + "fixed external widths survive")
        void everyIdentifierAndDatePartIsCarriedAsText() {
            for (String component : List.of("accountId", "cardNumber", "expiryMonth", "expiryYear",
                    "currentDate", "currentTime")) {
                assertThat(componentType(component))
                        .as("%s must stay text: an integral or temporal type would drop leading "
                                + "zeros and shorten the external width the byte-equivalence "
                                + "criterion compares", component)
                        .isEqualTo(String.class);
            }
        }

        @Test
        @DisplayName("the card status is held raw rather than typed, so an unrecognised character "
                + "round trips instead of being rejected")
        void theCardStatusIsHeldRaw() {
            assertThat(componentType("cardActiveStatus"))
                    .as("a typed status would have to reject or absorb a character the estate can "
                            + "legitimately hold")
                    .isEqualTo(String.class);
        }

        @Test
        @DisplayName("the error indicator is a primitive and the navigation state is the shared type")
        void theOutcomeComponentsAreTyped() {
            assertThat(componentType("generalError")).isEqualTo(boolean.class);
            assertThat(componentType("navigationContext")).isEqualTo(NavigationContext.class);
        }

        @Test
        @DisplayName("the route carries no width bound, because no legacy field declares a width "
                + "for a route")
        void theRouteCarriesNoWidthBound() throws NoSuchFieldException {
            assertThat(CardDetailResponse.class.getDeclaredField("nextRoute").getAnnotations())
                    .as("the token vocabulary belongs to the navigation service, not to a map item")
                    .isEmpty();
        }

        @Test
        @DisplayName("only the generated canonical constructor exists, so nothing is defaulted, "
                + "normalised or validated on construction")
        void onlyTheGeneratedCanonicalConstructorExists() {
            assertThat(CardDetailResponse.class.getDeclaredConstructors()).hasSize(1);
            assertThat(CardDetailResponse.class.getDeclaredConstructors()[0].getParameterCount())
                    .isEqualTo(EXPECTED_COMPONENTS.size());
        }

        @Test
        @DisplayName("no member is declared beyond the eighteen accessors, so this type computes "
                + "nothing and composes nothing")
        void noMemberIsDeclaredBeyondTheAccessors() {
            List<String> instanceMethods =
                    Arrays.stream(CardDetailResponse.class.getDeclaredMethods())
                            .filter(method -> !Modifier.isStatic(method.getModifiers()))
                            .map(Method::getName)
                            .filter(name -> !List.of("equals", "hashCode", "toString").contains(name))
                            .toList();

            assertThat(instanceMethods)
                    .as("message selection, status resolution and expiry slicing all belong to the "
                            + "card-detail service, which owns the ordering the legacy produced")
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_COMPONENTS);
        }

        /**
         * Reads the declared width bound of a component.
         *
         * @param component the component name
         * @return the maximum length the component declares
         * @throws NoSuchFieldException if the component does not exist
         */
        private static int boundOf(String component) throws NoSuchFieldException {
            return CardDetailResponse.class.getDeclaredField(component)
                    .getAnnotation(Size.class)
                    .max();
        }

        /**
         * Reads the declared type of a component.
         *
         * @param component the component name
         * @return the component's declared type
         */
        private static Class<?> componentType(String component) {
            return Arrays.stream(CardDetailResponse.class.getRecordComponents())
                    .filter(candidate -> candidate.getName().equals(component))
                    .findFirst()
                    .orElseThrow()
                    .getType();
        }
    }

    @Nested
    @DisplayName("Screen field identities")
    class ScreenFieldIdentities {

        @Test
        @DisplayName("the two identities name the two unprotected fields on the mapset")
        void theTwoIdentitiesNameTheTwoUnprotectedFields() {
            assertThat(CardDetailResponse.SCREEN_FIELD_ACCOUNT_ID).isEqualTo("ACCTSID");
            assertThat(CardDetailResponse.SCREEN_FIELD_CARD_NUMBER).isEqualTo("CARDSID");
        }

        @Test
        @DisplayName("the two identities are distinct, so a focus hint names one field rather than "
                + "an ambiguous pair")
        void theTwoIdentitiesAreDistinct() {
            assertThat(CardDetailResponse.SCREEN_FIELD_ACCOUNT_ID)
                    .isNotEqualTo(CardDetailResponse.SCREEN_FIELD_CARD_NUMBER);
        }

        @Test
        @DisplayName("each identity fits the focus-hint width exactly, so neither can be reported "
                + "over-long by the bound that carries it")
        void eachIdentityFitsTheFocusHintWidth() {
            assertThat(CardDetailResponse.SCREEN_FIELD_ACCOUNT_ID)
                    .hasSize(CardDetailResponse.FOCUS_SCREEN_FIELD_ID_LENGTH);
            assertThat(CardDetailResponse.SCREEN_FIELD_CARD_NUMBER)
                    .hasSize(CardDetailResponse.FOCUS_SCREEN_FIELD_ID_LENGTH);
            assertThat(validator.validate(
                            carrying("focusScreenFieldId",
                                    CardDetailResponse.SCREEN_FIELD_ACCOUNT_ID)))
                    .isEmpty();
            assertThat(validator.validate(
                            carrying("focusScreenFieldId",
                                    CardDetailResponse.SCREEN_FIELD_CARD_NUMBER)))
                    .isEmpty();
        }

        @Test
        @DisplayName("an identity carries no attribute, colour or position, because the whole value "
                + "is the field name and nothing else")
        void anIdentityCarriesNothingBesideTheName() {
            assertThat(CardDetailResponse.SCREEN_FIELD_ACCOUNT_ID)
                    .as("the legacy positioned the cursor through a generated length item and a "
                            + "sentinel, none of which appears here")
                    .matches("[A-Z]{7}")
                    .doesNotContain(",")
                    .doesNotContain(" ");
            assertThat(CardDetailResponse.SCREEN_FIELD_CARD_NUMBER)
                    .matches("[A-Z]{7}")
                    .doesNotContain(",")
                    .doesNotContain(" ");
        }
    }

    @Nested
    @DisplayName("Message contract")
    class MessageContract {

        @Test
        @DisplayName("the sixteen message texts and the two field identities are the eighteen "
                + "published text constants, so no text is declared elsewhere")
        void theEighteenPublishedTextConstantsAreTheOnesExpected() {
            List<String> published = Arrays.stream(CardDetailResponse.class.getDeclaredFields())
                    .filter(field -> Modifier.isStatic(field.getModifiers()))
                    .filter(field -> Modifier.isPublic(field.getModifiers()))
                    .filter(field -> field.getType() == String.class)
                    .map(Field::getName)
                    .sorted()
                    .toList();

            assertThat(published).hasSize(18);
            assertThat(published.stream().filter(name -> name.startsWith("MSG_")).toList())
                    .hasSize(16);
            assertThat(published.stream().filter(name -> name.startsWith("SCREEN_FIELD_")).toList())
                    .hasSize(2);
        }

        /**
         * One static text is deliberately not published, and naming it here keeps the count honest.
         *
         * <p>The diagnostic stand-in is a private constant. It is not part of the message contract -
         * no screen ever displays it and no client ever receives it - so publishing it would invite a
         * caller to depend on its spelling. Asserting it exists, is private, and is the only
         * unpublished text keeps the eighteen-published claim above exact rather than approximate: if
         * a nineteenth text were added, one of these two tests fails and names which.</p>
         */
        @Test
        @DisplayName("the diagnostic stand-in is the one static text that is not published")
        void theDiagnosticStandInIsTheOneUnpublishedText() {
            List<String> unpublished = Arrays.stream(CardDetailResponse.class.getDeclaredFields())
                    .filter(field -> Modifier.isStatic(field.getModifiers()))
                    .filter(field -> !Modifier.isPublic(field.getModifiers()))
                    .filter(field -> field.getType() == String.class)
                    .map(Field::getName)
                    .toList();

            assertThat(unpublished).containsExactly("REDACTION_PLACEHOLDER");
        }

        @Test
        @DisplayName("the two informational texts are reproduced character for character")
        void theTwoInformationalTextsAreReproducedVerbatim() {
            assertThat(CardDetailResponse.MSG_FOUND_CARDS_FOR_ACCOUNT)
                    .isEqualTo("   Displaying requested details");
            assertThat(CardDetailResponse.MSG_PROMPT_FOR_INPUT)
                    .isEqualTo("Please enter Account and Card Number");
        }

        @Test
        @DisplayName("the display message keeps its three leading spaces, which are part of the "
                + "value rather than layout to be trimmed")
        void theDisplayMessageKeepsItsThreeLeadingSpaces() {
            String text = CardDetailResponse.MSG_FOUND_CARDS_FOR_ACCOUNT;

            assertThat(text)
                    .as("the legacy field is a fixed-width area in which the indent is how the text "
                            + "was positioned, so removing it changes the rendered contract")
                    .hasSize(31)
                    .startsWith("   ")
                    .doesNotStartWith("    ")
                    .isNotEqualTo(text.stripLeading())
                    .isNotEqualTo(text.strip());
        }

        @Test
        @DisplayName("the exit message keeps its missing space after the period and its fourteen "
                + "trailing spaces")
        void theExitMessageKeepsItsMissingSpaceAndItsTrailingPadding() {
            String text = CardDetailResponse.MSG_EXIT;

            assertThat(text)
                    .as("twenty characters of text followed by fourteen trailing spaces; both "
                            + "peculiarities are contract and neither may be repaired")
                    .hasSize(34)
                    .startsWith("PF03 pressed.Exiting")
                    .doesNotContain("pressed. Exiting")
                    .endsWith("              ")
                    .isNotEqualTo(text.stripTrailing());
            assertThat(text.stripTrailing()).hasSize(20);
        }

        @Test
        @DisplayName("the three absent-input texts are reproduced character for character")
        void theThreeAbsentInputTextsAreReproducedVerbatim() {
            assertThat(CardDetailResponse.MSG_PROMPT_FOR_ACCOUNT)
                    .isEqualTo("Account number not provided");
            assertThat(CardDetailResponse.MSG_PROMPT_FOR_CARD)
                    .isEqualTo("Card number not provided");
            assertThat(CardDetailResponse.MSG_NO_SEARCH_CRITERIA_RECEIVED)
                    .isEqualTo("No input received");
        }

        @Test
        @DisplayName("the account wording is declared under two separate names and the two are "
                + "equal by value, which is the arrangement the estate has")
        void theAccountWordingIsDeclaredTwiceAndTheTwoAreEqualByValue() throws NoSuchFieldException {
            assertThat(CardDetailResponse.class.getDeclaredField("MSG_SEARCHED_ACCOUNT_ZEROES"))
                    .as("one name per legacy declaration site; aliasing the pair would erase the "
                            + "fact that the estate declares the wording twice")
                    .isNotNull();
            assertThat(CardDetailResponse.class.getDeclaredField("MSG_SEARCHED_ACCOUNT_NOT_NUMERIC"))
                    .isNotNull();
            assertThat(CardDetailResponse.MSG_SEARCHED_ACCOUNT_ZEROES)
                    .isEqualTo("Account number must be a non zero 11 digit number")
                    .isEqualTo(CardDetailResponse.MSG_SEARCHED_ACCOUNT_NOT_NUMERIC);
        }

        @Test
        @DisplayName("the sentence-case card wording and its upper-case filter counterpart coexist "
                + "and neither is normalised towards the other")
        void theCardWordingAndItsFilterCounterpartAreKeptDistinct() {
            assertThat(CardDetailResponse.MSG_SEARCHED_CARD_NOT_NUMERIC)
                    .isEqualTo("Card number if supplied must be a 16 digit number")
                    .as("sentence case, and it is not the upper-case filter wording the same "
                            + "program emits from a different point")
                    .isNotEqualTo(CardDetailResponse.MSG_CARD_FILTER_NOT_NUMERIC)
                    .isNotEqualTo(
                            CardDetailResponse.MSG_SEARCHED_CARD_NOT_NUMERIC.toUpperCase(
                                    java.util.Locale.ROOT));
            assertThat(CardDetailResponse.MSG_CARD_FILTER_NOT_NUMERIC)
                    .isEqualTo(CardDetailResponse.MSG_CARD_FILTER_NOT_NUMERIC.toUpperCase(
                            java.util.Locale.ROOT));
        }

        @Test
        @DisplayName("both filter texts are upper case, carry no space after the comma, and read "
                + "\"A\" rather than \"AN\" before the digit count")
        void bothFilterTextsKeepTheirThreePeculiarities() {
            assertThat(CardDetailResponse.MSG_ACCOUNT_FILTER_NOT_NUMERIC)
                    .isEqualTo("ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER")
                    .contains("FILTER,IF")
                    .doesNotContain("FILTER, IF")
                    .contains("A 11 DIGIT")
                    .doesNotContain("AN 11");
            assertThat(CardDetailResponse.MSG_CARD_FILTER_NOT_NUMERIC)
                    .isEqualTo("CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER")
                    .contains("FILTER,IF")
                    .doesNotContain("FILTER, IF")
                    .contains("A 16 DIGIT")
                    .doesNotContain("AN 16");
        }

        @Test
        @DisplayName("the two not-found texts are reproduced character for character and name no "
                + "internal artefact")
        void theTwoNotFoundTextsAreReproducedVerbatim() {
            assertThat(CardDetailResponse.MSG_ACCOUNT_NOT_IN_CARD_CROSS_REFERENCE)
                    .isEqualTo("Did not find this account in cards database");
            assertThat(CardDetailResponse.MSG_NO_CARDS_FOR_SEARCH_CONDITION)
                    .isEqualTo("Did not find cards for this search condition");
        }

        @Test
        @DisplayName("the read-error text stays opaque about its cause, naming no status code, "
                + "exception, table or path")
        void theReadErrorTextStaysOpaqueAboutItsCause() {
            assertThat(CardDetailResponse.MSG_CARD_DATA_READ_ERROR)
                    .as("the legacy assembled a detailed diagnostic naming the failing operation, "
                            + "file and response codes, and that text never reaches this item")
                    .isEqualTo("Error reading Card Data File")
                    .doesNotContainPattern("[0-9]")
                    .doesNotContainIgnoringCase("exception")
                    .doesNotContainIgnoringCase("sql")
                    .doesNotContainIgnoringCase("status")
                    .doesNotContain("/");
        }

        @Test
        @DisplayName("the provisional text keeps its four-dot ellipsis rather than a conventional "
                + "three")
        void theProvisionalTextKeepsItsFourDotEllipsis() {
            assertThat(CardDetailResponse.MSG_CODING_TO_BE_DONE)
                    .as("the acceptance criterion compares this string character for character, so "
                            + "a conventional three-dot ellipsis would fail it")
                    .isEqualTo("Looks Good.... so far")
                    .hasSize(21)
                    .contains("Good....")
                    .doesNotContain("Good... ")
                    .doesNotContain("Good.....");
        }

        @Test
        @DisplayName("the unrecognised-state text is upper case, and the mixed casing across the "
                + "set is preserved rather than unified")
        void theUnrecognisedStateTextIsUpperCase() {
            assertThat(CardDetailResponse.MSG_UNEXPECTED_DATA_SCENARIO)
                    .isEqualTo("UNEXPECTED DATA SCENARIO")
                    .isEqualTo(CardDetailResponse.MSG_UNEXPECTED_DATA_SCENARIO.toUpperCase(
                            java.util.Locale.ROOT));
            assertThat(CardDetailResponse.MSG_PROMPT_FOR_ACCOUNT)
                    .as("its sentence-case neighbour is left as it is, so the set stays mixed")
                    .isNotEqualTo(CardDetailResponse.MSG_PROMPT_FOR_ACCOUNT.toUpperCase(
                            java.util.Locale.ROOT));
        }

        @ParameterizedTest(name = "informational text \"{0}\" fits the informational width")
        @MethodSource("com.carddemo.api.dto.CardDetailResponseCoverageTest#informationalCarriedTexts")
        @DisplayName("each informational text fits the informational width, so no published text "
                + "can be reported over-long by the component that carries it")
        void eachInformationalTextFitsTheInformationalWidth(String text) {
            assertThat(text.length()).isLessThanOrEqualTo(CardDetailResponse.INFO_MESSAGE_LENGTH);
            assertThat(validator.validate(carrying("infoMessage", text))).isEmpty();
        }

        @Test
        @DisplayName("the exit message fits the informational width, which is the narrower of the "
                + "two message widths")
        void theExitMessageFitsTheNarrowerMessageWidth() {
            assertThat(CardDetailResponse.MSG_EXIT.length())
                    .isLessThanOrEqualTo(CardDetailResponse.INFO_MESSAGE_LENGTH);
            assertThat(validator.validate(carrying("infoMessage", CardDetailResponse.MSG_EXIT)))
                    .isEmpty();
        }

        @ParameterizedTest(name = "error text \"{0}\" fits the error width")
        @MethodSource("com.carddemo.api.dto.CardDetailResponseCoverageTest#errorCarriedTexts")
        @DisplayName("each error text fits the error width, so no published text can be reported "
                + "over-long by the component that carries it")
        void eachErrorTextFitsTheErrorWidth(String text) {
            assertThat(text.length()).isLessThanOrEqualTo(CardDetailResponse.ERROR_MESSAGE_LENGTH);
            assertThat(validator.validate(carrying("errorMessage", text))).isEmpty();
        }
    }

    @Nested
    @DisplayName("Validation bounds")
    class ValidationBounds {

        @Test
        @DisplayName("a fully populated display response reports no violation, nested navigation "
                + "state included")
        void aFullyPopulatedDisplayResponseReportsNoViolation() {
            assertThat(validator.validate(displayed(JsonContractSupport.populatedNavigation())))
                    .isEmpty();
        }

        @ParameterizedTest(name = "{0} reports a value one character over {1}")
        @CsvSource({
            "transactionName,4",
            "title01,40",
            "currentDate,8",
            "programName,8",
            "title02,40",
            "currentTime,8",
            "accountId,11",
            "cardNumber,16",
            "embossedName,50",
            "cardActiveStatus,1",
            "expiryMonth,2",
            "expiryYear,4",
            "infoMessage,40",
            "errorMessage,80",
            "focusScreenFieldId,7",
        })
        @DisplayName("each bounded component reports a value one character over its width")
        void eachBoundedComponentReportsAnOverLongValue(String component, int width) {
            Set<ConstraintViolation<CardDetailResponse>> violations =
                    validator.validate(carrying(component, "9".repeat(width + 1)));

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString(component);
        }

        @ParameterizedTest(name = "{0} accepts a value exactly at {1}")
        @CsvSource({
            "transactionName,4",
            "accountId,11",
            "cardNumber,16",
            "embossedName,50",
            "cardActiveStatus,1",
            "expiryMonth,2",
            "infoMessage,40",
            "errorMessage,80",
            "focusScreenFieldId,7",
        })
        @DisplayName("each bounded component accepts a value exactly at its width, so every bound "
                + "is inclusive")
        void eachBoundedComponentAcceptsAValueAtItsWidth(String component, int width) {
            assertThat(validator.validate(carrying(component, "9".repeat(width)))).isEmpty();
        }

        @Test
        @DisplayName("the route is unbounded, so a long opaque token is carried rather than reported")
        void theRouteIsUnbounded() {
            assertThat(validator.validate(carrying("nextRoute", "/".repeat(512)))).isEmpty();
        }

        @Test
        @DisplayName("an entirely empty response reports no violation, because a first entry into "
                + "the screen has nothing to report")
        void anEntirelyEmptyResponseReportsNoViolation() {
            assertThat(validator.validate(empty())).isEmpty();
        }

        @ParameterizedTest(name = "expiry month \"{0}\" is accepted by the boundary")
        @ValueSource(strings = {"", " ", "00", "13", "99", "AB"})
        @DisplayName("a blank or out-of-range expiry month is accepted by the boundary, because the "
                + "range check is a message-bearing stage of the service cascade")
        void aBlankOrOutOfRangeExpiryMonthIsAccepted(String part) {
            assertThat(validator.validate(carrying("expiryMonth", part)))
                    .as("a bound that rejected \"13\" would pre-empt the one message the screen "
                            + "must show for an unusable expiry part")
                    .isEmpty();
        }

        @ParameterizedTest(name = "card status \"{0}\" is accepted by the boundary")
        @ValueSource(strings = {"Y", "N", "y", "Q", "0", " "})
        @DisplayName("any single card-status character is accepted by the boundary, because the "
                + "component is held raw rather than constrained to the codes the estate defines")
        void anySingleCardStatusCharacterIsAccepted(String status) {
            assertThat(validator.validate(carrying("cardActiveStatus", status))).isEmpty();
        }

        @Test
        @DisplayName("an embossed name carrying embedded spaces is accepted, because the estate's "
                + "alphabetic idiom passes a space")
        void anEmbossedNameWithEmbeddedSpacesIsAccepted() {
            assertThat(validator.validate(carrying("embossedName", EMBOSSED_NAME)))
                    .as("the legacy blank-and-trim alphabetic check accepts a name such as this, "
                            + "so a bound that rejected it would refuse existing data")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Wire shape")
    class WireShape {

        @Test
        @DisplayName("a display response renders every populated member under its contract name")
        void aDisplayResponseRendersItsPopulatedMembers() throws JsonProcessingException {
            JsonNode payload = payloadOf(displayed(null));

            assertThat(payload.get("transactionName").asText()).isEqualTo(TRANSACTION_NAME);
            assertThat(payload.get("title01").asText()).isEqualTo(SCREEN_TITLE_LINE_1);
            assertThat(payload.get("currentDate").asText()).isEqualTo(CURRENT_DATE);
            assertThat(payload.get("programName").asText()).isEqualTo(PROGRAM_NAME);
            assertThat(payload.get("title02").asText()).isEqualTo(SCREEN_TITLE_LINE_2);
            assertThat(payload.get("currentTime").asText()).isEqualTo(CURRENT_TIME);
            assertThat(payload.get("embossedName").asText()).isEqualTo(EMBOSSED_NAME);
            assertThat(payload.get("cardActiveStatus").asText()).isEqualTo(CARD_ACTIVE_STATUS);
            assertThat(payload.get("expiryMonth").asText()).isEqualTo(EXPIRY_MONTH);
            assertThat(payload.get("expiryYear").asText()).isEqualTo(EXPIRY_YEAR);
            assertThat(payload.get("focusScreenFieldId").asText())
                    .isEqualTo(CardDetailResponse.SCREEN_FIELD_ACCOUNT_ID);
            assertThat(payload.get("nextRoute").asText()).isEqualTo(ROUTE);
        }

        @Test
        @DisplayName("both title lines cross at their declared forty characters, padding included")
        void bothTitleLinesCrossAtFortyCharacters() throws JsonProcessingException {
            JsonNode payload = payloadOf(displayed(null));

            assertThat(payload.get("title01").asText())
                    .hasSize(CardDetailResponse.SCREEN_TITLE_LENGTH);
            assertThat(payload.get("title02").asText())
                    .hasSize(CardDetailResponse.SCREEN_TITLE_LENGTH);
        }

        @Test
        @DisplayName("the account identifier and the card number cross as text with their leading "
                + "zeros intact")
        void theIdentifiersCrossAsTextWithLeadingZerosIntact() throws JsonProcessingException {
            JsonNode payload = payloadOf(displayed(null));

            assertThat(payload.get("accountId").isTextual())
                    .as("a numeric member would arrive as 11 and lose nine characters of width")
                    .isTrue();
            assertThat(payload.get("accountId").asText())
                    .isEqualTo(ACCOUNT_ID)
                    .hasSize(CardDetailResponse.ACCOUNT_ID_LENGTH);
            assertThat(payload.get("cardNumber").isTextual()).isTrue();
            assertThat(payload.get("cardNumber").asText())
                    .isEqualTo(CARD_NUMBER)
                    .hasSize(CardDetailResponse.CARD_NUMBER_LENGTH);
        }

        @Test
        @DisplayName("the card number crosses whole, neither shortened nor partially hidden, which "
                + "is the delivered contract rather than an accident")
        void theCardNumberCrossesWhole() throws JsonProcessingException {
            String rendered = JsonContractSupport.declaredSettingsMapper()
                    .writeValueAsString(displayed(null));

            assertThat(JsonContractSupport.renderedValueToken(rendered, "cardNumber"))
                    .as("the legacy applies no field-level protection to a primary account number "
                            + "anywhere; the gap is recorded in docs/decision-log.md and closing it "
                            + "here would change the response contract as unrequested work")
                    .isEqualTo("\"" + CARD_NUMBER + "\"");
            assertThat(rendered).doesNotContain("****");
        }

        @Test
        @DisplayName("the informational text crosses with its three leading spaces, so the indent "
                + "survives serialisation")
        void theInformationalTextCrossesWithItsIndent() throws JsonProcessingException {
            JsonNode payload = payloadOf(displayed(null));

            assertThat(payload.get("infoMessage").asText())
                    .isEqualTo(CardDetailResponse.MSG_FOUND_CARDS_FOR_ACCOUNT)
                    .startsWith("   ")
                    .hasSize(31);
        }

        @Test
        @DisplayName("the exit text crosses with its full thirty-four characters, trailing padding "
                + "included")
        void theExitTextCrossesWithItsFullLength() throws JsonProcessingException {
            JsonNode payload = payloadOf(carrying("infoMessage", CardDetailResponse.MSG_EXIT));

            assertThat(payload.get("infoMessage").asText())
                    .as("trimming it on the way out would change an externally observable string")
                    .isEqualTo(CardDetailResponse.MSG_EXIT)
                    .hasSize(34)
                    .endsWith("              ");
        }

        @Test
        @DisplayName("an absent member is omitted while the error indicator is always written, "
                + "because a primitive has no absent state")
        void absentMembersAreOmittedAndTheIndicatorIsAlwaysWritten() throws JsonProcessingException {
            JsonNode payload = payloadOf(empty());

            assertThat(payload.size()).isEqualTo(1);
            assertThat(payload.get("generalError").asBoolean()).isFalse();
            for (String component : EXPECTED_COMPONENTS) {
                if (!"generalError".equals(component)) {
                    assertThat(payload.has(component))
                            .as("%s is absent rather than written as null", component)
                            .isFalse();
                }
            }
        }

        @Test
        @DisplayName("an explicitly blanked message is written while an absent one is omitted, so "
                + "the two states stay distinguishable on the wire")
        void aBlankedMessageIsWrittenWhileAnAbsentOneIsOmitted() throws JsonProcessingException {
            JsonNode payload = payloadOf(carrying("errorMessage", ""));

            assertThat(payload.get("errorMessage").asText()).isEmpty();
            assertThat(payload.has("infoMessage")).isFalse();
        }

        @Test
        @DisplayName("the error indicator is written independently of the error text, so a screen "
                + "carrying only an informational line is not reported as an error")
        void theErrorIndicatorIsIndependentOfTheErrorText() throws JsonProcessingException {
            JsonNode informationalOnly = payloadOf(displayed(null));
            JsonNode flaggedWithoutText = payloadOf(new CardDetailResponse(null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, true, null, null,
                    null));

            assertThat(informationalOnly.get("generalError").asBoolean())
                    .as("an informational line is not an error")
                    .isFalse();
            assertThat(informationalOnly.has("errorMessage")).isFalse();
            assertThat(flaggedWithoutText.get("generalError").asBoolean())
                    .as("the flag and the text are values the program sets independently")
                    .isTrue();
            assertThat(flaggedWithoutText.has("errorMessage")).isFalse();
        }

        @Test
        @DisplayName("a fully populated response round trips unchanged, nested navigation state "
                + "included")
        void aFullyPopulatedResponseRoundTripsUnchanged() throws JsonProcessingException {
            CardDetailResponse response = displayed(JsonContractSupport.populatedNavigation());
            ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();

            CardDetailResponse returned = mapper.readValue(
                    mapper.writeValueAsString(response), CardDetailResponse.class);

            assertThat(returned).isEqualTo(response);
            assertThat(returned.cardNumber()).isEqualTo(CARD_NUMBER);
            assertThat(returned.navigationContext())
                    .isEqualTo(JsonContractSupport.populatedNavigation());
        }

        @Test
        @DisplayName("an unknown member is ignored rather than rejected, so a client may echo the "
                + "response back without being refused")
        void anUnknownMemberIsIgnored() throws JsonProcessingException {
            String payload = "{\"cardNumber\":\"" + CARD_NUMBER
                    + "\",\"cvvCode\":\"123\",\"rows\":[]}";

            CardDetailResponse returned = JsonContractSupport.declaredSettingsMapper()
                    .readValue(payload, CardDetailResponse.class);

            assertThat(returned.cardNumber()).isEqualTo(CARD_NUMBER);
            assertThat(returned.accountId()).isNull();
        }
    }

    @Nested
    @DisplayName("Diagnostic rendering")
    class DiagnosticRendering {

        /** The fixed stand-in the override writes in place of a withheld value. */
        private static final String PLACEHOLDER = "***REDACTED***";

        /** The five components the override withholds, in declaration order. */
        private static final List<String> WITHHELD_BY_THIS_TYPE = List.of(
                "accountId", "cardNumber", "embossedName", "expiryMonth", "expiryYear");

        @Test
        @DisplayName("the rendering names every component in declaration order and writes the "
                + "stand-in for exactly the five withheld ones")
        void theRenderingIsExactlyTheGeneratedForm() {
            List<String> renderedValues = List.of(TRANSACTION_NAME, SCREEN_TITLE_LINE_1,
                    CURRENT_DATE, PROGRAM_NAME, SCREEN_TITLE_LINE_2, CURRENT_TIME, ACCOUNT_ID,
                    CARD_NUMBER, EMBOSSED_NAME, CARD_ACTIVE_STATUS, EXPIRY_MONTH, EXPIRY_YEAR,
                    CardDetailResponse.MSG_FOUND_CARDS_FOR_ACCOUNT, "null", "false",
                    CardDetailResponse.SCREEN_FIELD_ACCOUNT_ID, ROUTE, "null");
            StringBuilder expected = new StringBuilder("CardDetailResponse[");
            for (int index = 0; index < EXPECTED_COMPONENTS.size(); index++) {
                if (index > 0) {
                    expected.append(", ");
                }
                String component = EXPECTED_COMPONENTS.get(index);
                expected.append(component)
                        .append('=')
                        .append(WITHHELD_BY_THIS_TYPE.contains(component)
                                ? PLACEHOLDER
                                : renderedValues.get(index));
            }

            assertThat(displayed(null).toString())
                    .as("the override is written by hand, so the rendering is pinned literally and "
                            + "any reordering, renaming or change of stand-in fails here")
                    .isEqualTo(expected.append(']').toString());
        }

        @Test
        @DisplayName("exactly five components are replaced by the stand-in and the remaining "
                + "thirteen are rendered")
        void exactlyTheFiveIdentifyingComponentsAreWithheld() {
            String rendered = displayed(null).toString();

            List<String> withheld = EXPECTED_COMPONENTS.stream()
                    .filter(component -> rendered.contains(component + "=" + PLACEHOLDER))
                    .toList();

            assertThat(withheld).containsExactlyElementsOf(WITHHELD_BY_THIS_TYPE);
            assertThat(rendered)
                    .as("a withheld value must not reach the text by any other spelling")
                    .doesNotContain(CARD_NUMBER)
                    .doesNotContain(ACCOUNT_ID)
                    .doesNotContain(EMBOSSED_NAME);
        }

        /**
         * The stand-in appears whether or not the component holds a value.
         *
         * <p>A conditional substitution would leak one bit: a reader could tell a populated card
         * number from an absent one by whether the stand-in appeared. Comparing a response that
         * carries all five values against one that carries none proves the rendering is identical in
         * the withheld positions, so presence itself is not disclosed.</p>
         */
        @Test
        @DisplayName("the stand-in is written for an absent value as well as a present one, so "
                + "presence is not disclosed either")
        void theWithholdingIsUnconditional() {
            String populated = displayed(null).toString();
            String empty = new CardDetailResponse(TRANSACTION_NAME, SCREEN_TITLE_LINE_1,
                    CURRENT_DATE, PROGRAM_NAME, SCREEN_TITLE_LINE_2, CURRENT_TIME, null, null, null,
                    CARD_ACTIVE_STATUS, null, null,
                    CardDetailResponse.MSG_FOUND_CARDS_FOR_ACCOUNT, null, false,
                    CardDetailResponse.SCREEN_FIELD_ACCOUNT_ID, ROUTE, null).toString();

            assertThat(empty).isEqualTo(populated);
            for (String component : WITHHELD_BY_THIS_TYPE) {
                assertThat(empty)
                        .as("%s is withheld even when it holds nothing", component)
                        .contains(component + "=" + PLACEHOLDER);
            }
        }

        /**
         * Withholding is confined to the rendering; the contract itself is unchanged.
         *
         * <p>This is the assertion that keeps the protection from becoming a behavioural regression.
         * The screen still receives the card number, the accessors still return it, and only the
         * diagnostic channel declines to repeat it.</p>
         */
        @Test
        @DisplayName("every withheld component is still returned unaltered by its accessor")
        void theWithholdingIsConfinedToTheRendering() {
            CardDetailResponse response = displayed(null);

            assertThat(response.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(response.cardNumber()).isEqualTo(CARD_NUMBER);
            assertThat(response.embossedName()).isEqualTo(EMBOSSED_NAME);
            assertThat(response.expiryMonth()).isEqualTo(EXPIRY_MONTH);
            assertThat(response.expiryYear()).isEqualTo(EXPIRY_YEAR);
        }

        @Test
        @DisplayName("a nested navigation state withholds its own identifying values, so this type "
                + "cannot become the path by which they surface")
        void aNestedNavigationStateWithholdsItsOwnValues() {
            String rendered = displayed(JsonContractSupport.populatedNavigation()).toString();

            assertThat(rendered)
                    .contains("navigationContext=NavigationContext[")
                    .doesNotContain(JsonContractSupport.NAV_CARD_NUMBER)
                    .doesNotContain(JsonContractSupport.NAV_ACCOUNT_ID)
                    .doesNotContain(JsonContractSupport.NAV_CUSTOMER_ID)
                    .doesNotContain(JsonContractSupport.NAV_FIRST_NAME)
                    .doesNotContain(JsonContractSupport.NAV_MIDDLE_NAME)
                    .doesNotContain(JsonContractSupport.NAV_LAST_NAME);
        }
    }

    @Nested
    @DisplayName("Value semantics")
    class ValueSemantics {

        @Test
        @DisplayName("equality compares every component by value and equal instances share a hash")
        void equalityComparesEveryComponentByValue() {
            CardDetailResponse first = displayed(JsonContractSupport.populatedNavigation());
            CardDetailResponse second = displayed(JsonContractSupport.populatedNavigation());

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
            assertThat(first).isNotEqualTo(displayed(null));
        }

        @Test
        @DisplayName("the card number participates in equality, so two responses differing only in "
                + "the card they describe are distinguishable")
        void theCardNumberParticipatesInEquality() {
            assertThat(carrying("cardNumber", CARD_NUMBER))
                    .isNotEqualTo(carrying("cardNumber", "0000000000000002"));
        }

        @Test
        @DisplayName("the error indicator participates in equality, so a flagged and a clear "
                + "response carrying the same text are distinguishable")
        void theErrorIndicatorParticipatesInEquality() {
            CardDetailResponse flagged = new CardDetailResponse(null, null, null, null, null, null,
                    null, null, null, null, null, null, null, "same", true, null, null, null);
            CardDetailResponse clear = new CardDetailResponse(null, null, null, null, null, null,
                    null, null, null, null, null, null, null, "same", false, null, null, null);

            assertThat(flagged).isNotEqualTo(clear);
        }

        @Test
        @DisplayName("a blank text and an absent one do not compare equal, because the screen "
                + "reports on the difference")
        void aBlankTextIsNotAnAbsentOne() {
            assertThat(carrying("errorMessage", "")).isNotEqualTo(carrying("errorMessage", null));
        }
    }
}
