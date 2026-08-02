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

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import com.carddemo.service.MessageCatalogService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit tests for {@link TransactionViewResponse}, the response body of legacy transaction
 * {@code CT01}.
 *
 * <h2>What is under test</h2>
 *
 * <p>The transport contract of the transaction-view screen: the twenty-five components in the order
 * the screen presents them, the twenty map width bounds written as literals of this record's own plus
 * the focus hint's published width, the three published message texts, the decimal amount's wire form
 * and the record shape its constructor holds that amount to, the wire shape under the module's declared
 * serialisation settings, which eleven of its components the diagnostic rendering withholds, and the
 * value semantics of a display response that defaults nothing and normalises nothing.
 *
 * <h2>Two sixteen-character identifiers, and merging them would destroy the not-found contract</h2>
 *
 * <p>One identifier is what the operator asked for and the other is what was actually found. The
 * program blanks every retrieved display field before it reads and does not blank the search key, so
 * the record-absent shape is a populated search key beside blank retrieved fields - a state one
 * component cannot express. Collapsing the pair looks like obvious de-duplication, which is exactly
 * why a test below constructs that shape and asserts both halves of it.
 *
 * <h2>Four widths diverge from their neighbours and none may be normalised</h2>
 *
 * <p>The description is sixty here against a hundred in the underlying record and twenty-six on the
 * transaction-list screen; the merchant name is thirty and the merchant city twenty-five against fifty
 * each in the record; and both transaction dates are ten here against eight on the list screen. Every
 * one of those numbers is correct for its own surface, so this record declares its widths as literals
 * of its own and shares none with a sibling. Tests below pin each divergence as an assertion across
 * the two types, so unifying them fails here rather than in an output comparison.
 *
 * <h2>The eight-character widths on this record are clock values, not transaction dates</h2>
 *
 * <p>Three components are eight characters wide - the header date, the header time and the program
 * name - and none of them is a transaction date. A reader who saw an eight beside a date-shaped name
 * could easily conclude the transaction dates were eight too, so the two groups are asserted
 * separately and against each other.
 *
 * <h2>The amount is a fixed-scale decimal and nothing here scales it</h2>
 *
 * <p>The record field is a zoned decimal of scale two, so the component is a {@link BigDecimal} whose
 * scale is two by contract and which this type never re-scales. The estate contains no rounding clause
 * anywhere, so a store into a two-place field discards the surplus digits downward, and the single
 * decimal codec in the utility layer is the one place that applies it. Letting a data-transfer type
 * re-scale a value it received would be the easiest way to reintroduce half-even rounding by accident,
 * so a test below proves the component declares no bound, no serializer and no serialisation
 * annotation, and further tests observe the emitted characters rather than a re-parsed tree.
 *
 * <p>The canonical constructor does one thing to the amount, and it is a refusal rather than a repair:
 * a value whose scale is not two, or which needs more than the nine integer digits the record field
 * holds, cannot be what that field contained, so it is rejected instead of reshaped. Refusing and
 * rescaling are different acts and only the second would change the bytes the screen presents, so the
 * refusal is what keeps the no-rescaling guarantee above honest rather than contradicting it. The
 * refusal text names the offending scale or digit count and never the amount, so a rejected value
 * cannot reach a log through the diagnostic that reports it.
 *
 * <h2>The message line is seventy-eight characters, and it carries fifty-character shared text</h2>
 *
 * <p>Seventy-eight, not the eighty of the card-detail screen. The same single component also carries
 * the shared common messages, which are fifty characters wide, and those are emphatically not the
 * forty-character thank-you value in the screen-title catalogue. Tests below assert the width
 * asymmetry across the two response types and assert that a fifty-character shared message fits this
 * component while remaining distinct from the forty-character title value.
 *
 * <h2>Eleven of the twenty-five components are withheld from the diagnostic rendering</h2>
 *
 * <p>The legacy design applies no field-level protection to a card number anywhere and that gap is
 * recorded in {@code docs/decision-log.md} - but the gap is about what the screen displays and what
 * the payload carries, and the diagnostic rendering is a third channel that no operator authorisation
 * governs. A generated rendering would have emitted a full primary account number beside the amount,
 * the description, the merchant and both stamps: a complete transaction record in a single log line,
 * reachable from any structured logger, framework diagnostic, failed assertion or string interpolation
 * that touched the response.
 *
 * <p>The withholding is per component, so the rendering keeps its shape: every component still appears
 * under its declared name and the fourteen that identify nobody - the screen furniture, the clock
 * values, the type and category codes, the source, the error text and indicator, the focus hint, the
 * route and the navigation state - still show their values. It is confined to {@code toString()}, so
 * every accessor returns its component unaltered and the payload is untouched, which is what lets the
 * parity requirement and the disclosure control hold at once. The nested {@link NavigationContext}
 * withholds its own identifying components independently, so neither level relies on the other.
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
@DisplayName("TransactionViewResponse :: transaction-view response contract of legacy transaction "
        + "CT01")
class TransactionViewResponseCoverageTest {

    /** The twenty-five components in declaration order. */
    private static final List<String> EXPECTED_COMPONENTS = List.of(
            "transactionName", "title01", "currentDate", "programName", "title02",
            "currentTime", "searchTransactionId", "transactionId", "cardNumber", "typeCode",
            "categoryCode", "source", "description", "amount", "originationDate", "processingDate",
            "merchantId", "merchantName", "merchantCity", "merchantZip", "errorMessage",
            "generalError", "focusScreenFieldId", "nextRoute", "navigationContext");

    /** The twenty-one components that declare a width bound. */
    private static final List<String> BOUNDED_COMPONENTS = List.of(
            "transactionName", "title01", "currentDate", "programName", "title02",
            "currentTime", "searchTransactionId", "transactionId", "cardNumber", "typeCode",
            "categoryCode", "source", "description", "originationDate", "processingDate",
            "merchantId", "merchantName", "merchantCity", "merchantZip", "errorMessage",
            "focusScreenFieldId");

    /** The thirteen retrieved record fields the program blanks before every read. */
    private static final List<String> RETRIEVED_RECORD_FIELDS = List.of(
            "transactionId", "cardNumber", "typeCode", "categoryCode", "source", "description",
            "amount", "originationDate", "processingDate", "merchantId", "merchantName",
            "merchantCity", "merchantZip");

    /** Transaction name shown in the screen header. */
    private static final String TRANSACTION_NAME = "CT01";

    /** Program name shown in the screen header. */
    private static final String PROGRAM_NAME = "COTRN01C";

    /** First title line, at the declared forty characters. */
    private static final String TITLE_LINE_1 = "      AWS Mainframe Modernization       ";

    /** Second title line, at the declared forty characters. */
    private static final String TITLE_LINE_2 = "              CardDemo                  ";

    /** Header date, at the declared eight characters. */
    private static final String CURRENT_DATE = "08/02/26";

    /** Header time, at the declared eight characters. */
    private static final String CURRENT_TIME = "14:35:07";

    /** The identifier the operator asked for, at the declared sixteen characters. */
    private static final String SEARCH_TRANSACTION_ID = "0000000000000001";

    /** The identifier of the record that was found, at the declared sixteen characters. */
    private static final String TRANSACTION_ID = "0000000000000001";

    /** Sixteen-character card number whose leading zeros are contract. */
    private static final String CARD_NUMBER = "0000000000000002";

    /** Two-character transaction type code. */
    private static final String TYPE_CODE = "01";

    /** Four-character transaction category code whose leading zeros are contract. */
    private static final String CATEGORY_CODE = "0002";

    /** Ten-character transaction source, space padded exactly as read. */
    private static final String SOURCE = "POS TERM  ";

    /** Transaction description, inside the sixty characters this screen shows. */
    private static final String DESCRIPTION = "Sample transaction description";

    /** Two-place transaction amount. */
    private static final BigDecimal AMOUNT = new BigDecimal("1234.56");

    /** Ten leading characters of the origination stamp. */
    private static final String ORIGINATION_DATE = "2022-01-01";

    /** Ten leading characters of the processing stamp. */
    private static final String PROCESSING_DATE = "2022-01-02";

    /** Nine-character merchant identifier whose leading zeros are contract. */
    private static final String MERCHANT_ID = "000999999";

    /** Merchant name, inside the thirty characters this screen shows. */
    private static final String MERCHANT_NAME = "Sample Merchant";

    /** Merchant city, inside the twenty-five characters this screen shows. */
    private static final String MERCHANT_CITY = "Sample City";

    /** Merchant postal code. */
    private static final String MERCHANT_ZIP = "12345-6789";

    /** Identity of the one enterable field on this screen. */
    private static final String FOCUS_SCREEN_FIELD_ID = "TRNIDIN";

    /** Opaque next-call token. */
    private static final String NEXT_ROUTE = "/api/transactions";

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
     * The three published message texts and the exact characters each must carry.
     *
     * @return pairs of published constant and expected text
     */
    static Stream<org.junit.jupiter.params.provider.Arguments> publishedMessages() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(
                        TransactionViewResponse.EMPTY_TRANSACTION_ID_MESSAGE,
                        "Tran ID can NOT be empty..."),
                org.junit.jupiter.params.provider.Arguments.of(
                        TransactionViewResponse.TRANSACTION_NOT_FOUND_MESSAGE,
                        "Transaction ID NOT found..."),
                org.junit.jupiter.params.provider.Arguments.of(
                        TransactionViewResponse.TRANSACTION_LOOKUP_FAILED_MESSAGE,
                        "Unable to lookup Transaction..."));
    }

    /**
     * The three published message texts.
     *
     * @return the message texts
     */
    static Stream<String> messageTexts() {
        return Stream.of(
                TransactionViewResponse.EMPTY_TRANSACTION_ID_MESSAGE,
                TransactionViewResponse.TRANSACTION_NOT_FOUND_MESSAGE,
                TransactionViewResponse.TRANSACTION_LOOKUP_FAILED_MESSAGE);
    }

    /**
     * Builds a response carrying only the named text component, so a violation can be attributed to
     * one property rather than inferred from a set.
     *
     * @param component the component to populate
     * @param value the value to place in it
     * @return a response carrying that one value
     */
    private static TransactionViewResponse carrying(String component, String value) {
        return new TransactionViewResponse(
                "transactionName".equals(component) ? value : null,
                "title01".equals(component) ? value : null,
                "currentDate".equals(component) ? value : null,
                "programName".equals(component) ? value : null,
                "title02".equals(component) ? value : null,
                "currentTime".equals(component) ? value : null,
                "searchTransactionId".equals(component) ? value : null,
                "transactionId".equals(component) ? value : null,
                "cardNumber".equals(component) ? value : null,
                "typeCode".equals(component) ? value : null,
                "categoryCode".equals(component) ? value : null,
                "source".equals(component) ? value : null,
                "description".equals(component) ? value : null,
                null,
                "originationDate".equals(component) ? value : null,
                "processingDate".equals(component) ? value : null,
                "merchantId".equals(component) ? value : null,
                "merchantName".equals(component) ? value : null,
                "merchantCity".equals(component) ? value : null,
                "merchantZip".equals(component) ? value : null,
                "errorMessage".equals(component) ? value : null,
                false,
                "focusScreenFieldId".equals(component) ? value : null,
                "nextRoute".equals(component) ? value : null,
                null);
    }

    /**
     * Builds a response carrying only the amount, so decimal behaviour can be observed alone.
     *
     * @param amount the amount to carry
     * @return a response carrying that amount
     */
    private static TransactionViewResponse withAmount(BigDecimal amount) {
        return new TransactionViewResponse(null, null, null, null, null, null, null, null, null, null,
                null, null, null, amount, null, null, null, null, null, null, null, false, null, null,
                null);
    }

    /**
     * Builds the response a located transaction produces, with every component populated.
     *
     * @param navigation the navigation state to echo, which may be {@code null}
     * @return a fully populated display response
     */
    private static TransactionViewResponse displayed(NavigationContext navigation) {
        return new TransactionViewResponse(TRANSACTION_NAME, TITLE_LINE_1, CURRENT_DATE, PROGRAM_NAME,
                TITLE_LINE_2, CURRENT_TIME, SEARCH_TRANSACTION_ID, TRANSACTION_ID, CARD_NUMBER,
                TYPE_CODE, CATEGORY_CODE, SOURCE, DESCRIPTION, AMOUNT, ORIGINATION_DATE,
                PROCESSING_DATE, MERCHANT_ID, MERCHANT_NAME, MERCHANT_CITY, MERCHANT_ZIP, null, false,
                FOCUS_SCREEN_FIELD_ID, NEXT_ROUTE, navigation);
    }

    /**
     * Builds the record-absent shape: the search key echoed back, every retrieved field absent, and
     * the error indicator raised.
     *
     * @return the not-found response
     */
    private static TransactionViewResponse notFound() {
        return new TransactionViewResponse(TRANSACTION_NAME, TITLE_LINE_1, CURRENT_DATE, PROGRAM_NAME,
                TITLE_LINE_2, CURRENT_TIME, SEARCH_TRANSACTION_ID, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                TransactionViewResponse.TRANSACTION_NOT_FOUND_MESSAGE, true, FOCUS_SCREEN_FIELD_ID,
                NEXT_ROUTE, null);
    }

    /**
     * Serialises a response and parses the result back into a tree.
     *
     * @param response the response to render
     * @return the parsed payload
     * @throws JsonProcessingException if rendering or parsing fails
     */
    private static JsonNode payloadOf(TransactionViewResponse response)
            throws JsonProcessingException {
        ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();
        return mapper.readTree(mapper.writeValueAsString(response));
    }

    @Nested
    @DisplayName("Declared contract")
    class DeclaredContract {

        @Test
        @DisplayName("the twenty-five components are declared in the order the screen presents them")
        void componentsAreDeclaredInScreenOrder() {
            List<String> declared =
                    Arrays.stream(TransactionViewResponse.class.getRecordComponents())
                            .map(RecordComponent::getName)
                            .toList();

            assertThat(declared).containsExactlyElementsOf(EXPECTED_COMPONENTS);
        }

        /**
         * Twenty-one components are bounded and the remaining four are unbounded.
         *
         * <p>The focus hint moved into the bounded set. It is not a legacy map value, which is why it
         * was originally left unmeasured, but it does carry the <em>name</em> of a legacy map field and
         * this screen's widest field name is seven characters. Leaving it unmeasured admitted a name no
         * field on this screen can have; bounding it at the screen's own widest name is the narrower and
         * therefore the correct statement. The amount, the indicator, the route and the navigation state
         * remain unbounded, each for a reason of its own: a decimal has no character width, a primitive
         * boolean has no length, the route is a service-owned identifier and the navigation state is a
         * nested record whose own components carry their own bounds.</p>
         *
         * <p>Unboundedness is asserted here as the absence of a {@link Size} bound rather than as the
         * absence of every annotation. The amount does carry an annotation: a {@code @Schema} recording
         * which legacy record field it reproduces and what that field's precision and scale are. That
         * annotation publishes the shape into the OpenAPI document; it does not constrain the value, and
         * the compact constructor rather than the validator is what refuses an out-of-shape amount.
         * Asserting that the field carries nothing at all would fail for a reason unrelated to bounding,
         * and would make documenting a component indistinguishable from constraining it.</p>
         */
        @Test
        @DisplayName("twenty-one components are bounded and the remaining four are the amount, the "
                + "indicator, the route and the navigation state")
        void theBoundedComponentsAreTheOnesExpected() throws NoSuchFieldException {
            List<String> bounded = EXPECTED_COMPONENTS.stream()
                    .filter(component -> {
                        try {
                            return TransactionViewResponse.class.getDeclaredField(component)
                                    .getAnnotation(Size.class) != null;
                        } catch (NoSuchFieldException problem) {
                            throw new AssertionError(problem);
                        }
                    })
                    .toList();

            assertThat(bounded).containsExactlyElementsOf(BOUNDED_COMPONENTS);
            for (String component : List.of("amount", "generalError", "nextRoute",
                    "navigationContext")) {
                assertThat(TransactionViewResponse.class.getDeclaredField(component)
                                .getAnnotation(Size.class))
                        .as("%s declares no character bound", component)
                        .isNull();
            }
            for (String component : List.of("generalError", "nextRoute", "navigationContext")) {
                assertThat(TransactionViewResponse.class.getDeclaredField(component)
                                .getAnnotations())
                        .as("%s declares nothing at all", component)
                        .isEmpty();
            }
        }

         /**
         * The amount's single annotation documents the record field rather than constraining it.
         *
         * <p>Separating this from the bound assertion above keeps the two statements independent: one
         * says the amount carries no character width, this one says the only thing it does carry is a
         * description. If a later change attached a numeric constraint to the amount, this test is where
         * that would surface, and the failure would name the constraint instead of reporting an
         * unexplained non-empty annotation array.</p>
         */
        @Test
        @DisplayName("the amount's only annotation is the description naming its record field")
        void theAmountCarriesDocumentationRatherThanAConstraint() throws NoSuchFieldException {
            Annotation[] declared =
                    TransactionViewResponse.class.getDeclaredField("amount").getAnnotations();

            assertThat(Arrays.stream(declared).map(a -> a.annotationType().getSimpleName()).toList())
                    .containsExactly("Schema");

            Schema description = TransactionViewResponse.class.getDeclaredField("amount")
                    .getAnnotation(Schema.class);
            assertThat(description.description())
                    .contains("TRAN-AMT")
                    .contains("nine integer digits")
                    .contains("two decimal places");
        }

        @ParameterizedTest(name = "{0} declares a bound of {1}")
        @CsvSource({
            "transactionName,4",
            "title01,40",
            "currentDate,8",
            "programName,8",
            "title02,40",
            "currentTime,8",
            "searchTransactionId,16",
            "transactionId,16",
            "cardNumber,16",
            "typeCode,2",
            "categoryCode,4",
            "source,10",
            "description,60",
            "originationDate,10",
            "processingDate,10",
            "merchantId,9",
            "merchantName,30",
            "merchantCity,25",
            "merchantZip,10",
            "errorMessage,78",
            "focusScreenFieldId,7",
        })
        @DisplayName("each bounded component declares the width its own surface uses")
        void eachBoundedComponentDeclaresItsSurfaceWidth(String component, int width)
                throws NoSuchFieldException {
            assertThat(TransactionViewResponse.class.getDeclaredField(component)
                            .getAnnotation(Size.class)
                            .max())
                    .isEqualTo(width);
        }

        /**
         * No <em>map</em> width is declared as a constant: every one of the twenty map bounds is an
         * inline literal on its own component.
         *
         * <p>That is the claim worth defending, because a shared map width would let one screen's layout
         * silently change what another screen's endpoint returns. Three integer constants do exist and
         * none of them is a map width. Two publish the decimal shape of the amount, which is a property
         * of the transaction record shared by every screen that shows it rather than of any one map item.
         * The third publishes the focus hint's width, which is not a map value at all - it is the width
         * of the widest field <em>name</em> on this screen - and it is published because a client that
         * must place a cursor needs to know how wide the name it receives can be.</p>
         */
        @Test
        @DisplayName("declares no map width as a constant, so no sibling screen can share a width "
                + "with this one")
        void noWidthConstantIsDeclared() {
            List<Field> staticFields = Arrays.stream(TransactionViewResponse.class
                            .getDeclaredFields())
                    .filter(field -> Modifier.isStatic(field.getModifiers()))
                    .filter(field -> !field.isSynthetic())
                    .toList();

            assertThat(staticFields)
                    .as("a shared map width would let one screen's layout silently change what "
                            + "another screen's endpoint returns")
                    .extracting(Field::getName)
                    .noneMatch(name -> BOUNDED_COMPONENTS.stream()
                            .anyMatch(component -> !"focusScreenFieldId".equals(component)
                                    && name.equalsIgnoreCase(component + "_LENGTH")));

            assertThat(staticFields.stream()
                            .filter(field -> field.getType() == int.class)
                            .map(Field::getName)
                            .toList())
                    .as("the three integer constants are the record shape and the hint width")
                    .containsExactlyInAnyOrder("AMOUNT_SCALE", "AMOUNT_INTEGER_DIGITS",
                            "SCREEN_FIELD_ID_LENGTH");
        }

        /**
         * The decimal shape of the amount and the width of the focus hint are published, and each is
         * the figure the type actually holds itself to.
         *
         * <p>Nine integer digits and two decimal places is {@code TRAN-AMT PIC S9(09)V99}, and the
         * canonical constructor refuses an amount that contradicts it, so a producer needs to be able to
         * read the rule. Seven is the width of the focus hint's bound, and the constant and the
         * annotation are asserted to agree - a published figure that disagreed with the enforced one
         * would be worse than no figure at all.</p>
         *
         * @throws NoSuchFieldException if the focus hint is not declared, failing this test
         */
        @Test
        @DisplayName("publishes the amount's record shape and the focus hint's width")
        void thePublishedIntegerConstantsAreTheOnesEnforced() throws NoSuchFieldException {
            assertThat(TransactionViewResponse.AMOUNT_SCALE).isEqualTo(2);
            assertThat(TransactionViewResponse.AMOUNT_INTEGER_DIGITS).isEqualTo(9);
            assertThat(TransactionViewResponse.SCREEN_FIELD_ID_LENGTH).isEqualTo(7);
            assertThat(TransactionViewResponse.class.getDeclaredField("focusScreenFieldId")
                            .getAnnotation(Size.class)
                            .max())
                    .as("the published width is the enforced width")
                    .isEqualTo(TransactionViewResponse.SCREEN_FIELD_ID_LENGTH);
        }

        @Test
        @DisplayName("the two identifiers are separate components, so the record-absent shape is "
                + "expressible")
        void theTwoIdentifiersAreSeparateComponents() {
            assertThat(EXPECTED_COMPONENTS)
                    .as("collapsing the pair looks like de-duplication and would destroy the "
                            + "not-found contract")
                    .contains("searchTransactionId", "transactionId");
            assertThat(componentType("searchTransactionId")).isEqualTo(String.class);
            assertThat(componentType("transactionId")).isEqualTo(String.class);
        }

        @Test
        @DisplayName("every identifier and code is text, so leading zeros and fixed external widths "
                + "survive")
        void everyIdentifierAndCodeIsText() {
            for (String component : List.of("searchTransactionId", "transactionId", "cardNumber",
                    "typeCode", "categoryCode", "merchantId", "merchantZip")) {
                assertThat(componentType(component))
                        .as("%s must stay text: a numeric type would discard the leading zeros and "
                                + "shorten the external width the acceptance criterion compares",
                                component)
                        .isEqualTo(String.class);
            }
        }

        @Test
        @DisplayName("the source stays raw text rather than the domain enumeration, so a value "
                + "outside the declared set survives the round trip")
        void theSourceStaysRawText() {
            assertThat(componentType("source"))
                    .as("the stored column has no membership check, and the declared values are "
                            + "themselves padded to the full width, so an enumeration would buy no "
                            + "normalisation while adding a conversion that can throw")
                    .isEqualTo(String.class);
        }

        @Test
        @DisplayName("no temporal type appears anywhere, because neither stamp shape is canonical "
                + "and parsing either would reject the other")
        void noTemporalTypeAppearsAnywhere() {
            for (RecordComponent component : TransactionViewResponse.class.getRecordComponents()) {
                assertThat(component.getType().getName())
                        .as("%s must not be a temporal type", component.getName())
                        .doesNotStartWith("java.time")
                        .isNotEqualTo("java.util.Date");
            }
            assertThat(componentType("originationDate")).isEqualTo(String.class);
            assertThat(componentType("processingDate")).isEqualTo(String.class);
        }

        @Test
        @DisplayName("the amount is a decimal, the indicator is a primitive and the navigation state "
                + "is the shared type")
        void theRemainingComponentsAreTyped() {
            assertThat(componentType("amount"))
                    .as("every binary approximation of a fraction is excluded outright, because no "
                            + "such representation holds a two-place amount exactly")
                    .isEqualTo(BigDecimal.class);
            assertThat(componentType("generalError")).isEqualTo(boolean.class);
            assertThat(componentType("navigationContext")).isEqualTo(NavigationContext.class);
        }

        @Test
        @DisplayName("no serialisation annotation appears on any component, because rendering is "
                + "already settled centrally")
        void noSerialisationAnnotationAppearsOnAnyComponent() throws NoSuchFieldException {
            for (String component : EXPECTED_COMPONENTS) {
                Annotation[] annotations =
                        TransactionViewResponse.class.getDeclaredField(component).getAnnotations();
                for (Annotation annotation : annotations) {
                    assertThat(annotation.annotationType().getName())
                            .as("%s declares no serializer and no serialisation annotation",
                                    component)
                            .doesNotStartWith("com.fasterxml.jackson");
                }
            }
        }

        @Test
        @DisplayName("only the generated canonical constructor exists, because there is nothing for "
                + "a compact one to do")
        void onlyTheGeneratedCanonicalConstructorExists() {
            assertThat(TransactionViewResponse.class.getDeclaredConstructors()).hasSize(1);
            assertThat(TransactionViewResponse.class.getDeclaredConstructors()[0]
                            .getParameterCount())
                    .isEqualTo(EXPECTED_COMPONENTS.size());
        }

        @Test
        @DisplayName("no member is declared beyond the twenty-five accessors, so this type parses "
                + "nothing, scales nothing and dispatches nothing")
        void noMemberIsDeclaredBeyondTheAccessors() {
            List<String> instanceMethods =
                    Arrays.stream(TransactionViewResponse.class.getDeclaredMethods())
                            .filter(method -> !Modifier.isStatic(method.getModifiers()))
                            .map(Method::getName)
                            .filter(name -> !List.of("equals", "hashCode", "toString")
                                    .contains(name))
                            .toList();

            assertThat(instanceMethods)
                    .as("no route table, no route enumeration, no dispatch method and no scaling "
                            + "operation belongs in a data-transfer type")
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_COMPONENTS);
        }

        /**
         * Reads the declared type of a component.
         *
         * @param component the component name
         * @return the component's declared type
         */
        private static Class<?> componentType(String component) {
            return Arrays.stream(TransactionViewResponse.class.getRecordComponents())
                    .filter(candidate -> candidate.getName().equals(component))
                    .findFirst()
                    .orElseThrow()
                    .getType();
        }
    }

    @Nested
    @DisplayName("Width divergence from neighbouring surfaces")
    class WidthDivergence {

        @Test
        @DisplayName("the description is sixty here, neither the record's hundred nor the list "
                + "screen's twenty-six")
        void theDescriptionWidthDivergesFromBothNeighbours() throws NoSuchFieldException {
            int bound = boundOf("description");

            assertThat(bound)
                    .as("every one of the three numbers is correct for its own surface, and a "
                            + "shared width would let one change another")
                    .isEqualTo(60)
                    .isNotEqualTo(100)
                    .isNotEqualTo(TransactionListResponse.DESCRIPTION_LENGTH);
            assertThat(TransactionListResponse.DESCRIPTION_LENGTH).isEqualTo(26);
        }

        @Test
        @DisplayName("the merchant name is thirty and the merchant city twenty-five, against fifty "
                + "each in the underlying record")
        void theMerchantWidthsDivergeFromTheRecord() throws NoSuchFieldException {
            assertThat(boundOf("merchantName")).isEqualTo(30).isNotEqualTo(50);
            assertThat(boundOf("merchantCity")).isEqualTo(25).isNotEqualTo(50);
        }

        @Test
        @DisplayName("both transaction dates are ten characters, and the eights on this record "
                + "belong to the clock values and the program name instead")
        void theTransactionDatesAreTenAndTheEightsAreClockValues() throws NoSuchFieldException {
            assertThat(boundOf("originationDate")).isEqualTo(10);
            assertThat(boundOf("processingDate")).isEqualTo(10);
            assertThat(boundOf("currentDate"))
                    .as("a reader who saw an eight beside a date-shaped name could wrongly conclude "
                            + "the transaction dates were eight too")
                    .isEqualTo(8);
            assertThat(boundOf("currentTime")).isEqualTo(8);
            assertThat(boundOf("programName")).isEqualTo(8);
            assertThat(boundOf("originationDate")).isNotEqualTo(boundOf("currentDate"));
        }

        @Test
        @DisplayName("the message line is seventy-eight here, not the eighty of the card-detail "
                + "screen")
        void theMessageLineIsSeventyEightRatherThanEighty() throws NoSuchFieldException {
            assertThat(boundOf("errorMessage"))
                    .isEqualTo(78)
                    .isNotEqualTo(CardDetailResponse.ERROR_MESSAGE_LENGTH);
            assertThat(CardDetailResponse.ERROR_MESSAGE_LENGTH).isEqualTo(80);
        }

        @Test
        @DisplayName("the sixteen-character identifier widths do agree with the card-detail screen, "
                + "so the divergence is confined to the surfaces that genuinely differ")
        void theIdentifierWidthsAgreeWithTheCardDetailScreen() throws NoSuchFieldException {
            assertThat(boundOf("cardNumber")).isEqualTo(CardDetailResponse.CARD_NUMBER_LENGTH);
            assertThat(boundOf("searchTransactionId")).isEqualTo(boundOf("transactionId"));
        }

        /**
         * Reads the declared width bound of a component.
         *
         * @param component the component name
         * @return the maximum length the component declares
         * @throws NoSuchFieldException if the component does not exist
         */
        private static int boundOf(String component) throws NoSuchFieldException {
            return TransactionViewResponse.class.getDeclaredField(component)
                    .getAnnotation(Size.class)
                    .max();
        }
    }

    @Nested
    @DisplayName("Message contract")
    class MessageContract {

        @ParameterizedTest(name = "the published text is \"{1}\"")
        @MethodSource("com.carddemo.api.dto.TransactionViewResponseCoverageTest#publishedMessages")
        @DisplayName("each of the three published texts is reproduced character for character")
        void eachPublishedTextIsReproducedVerbatim(String published, String expected) {
            assertThat(published).isEqualTo(expected);
        }

        @ParameterizedTest(name = "\"{0}\" ends in exactly three dots with no space before them")
        @MethodSource("com.carddemo.api.dto.TransactionViewResponseCoverageTest#messageTexts")
        @DisplayName("every published text ends in exactly three trailing dots and carries no space "
                + "before them")
        void everyPublishedTextEndsInExactlyThreeDots(String text) {
            assertThat(text)
                    .as("three details are contract rather than accident: the capitalised word, "
                            + "exactly three dots, and no space before them")
                    .endsWith("...")
                    .doesNotEndWith("....")
                    .doesNotContain(" ...");
        }

        @Test
        @DisplayName("the two emptiness and absence texts capitalise the word NOT, which is the "
                + "estate's house style")
        void theTwoTextsCapitaliseTheWordNot() {
            assertThat(TransactionViewResponse.EMPTY_TRANSACTION_ID_MESSAGE)
                    .contains(" NOT ")
                    .doesNotContain(" not ");
            assertThat(TransactionViewResponse.TRANSACTION_NOT_FOUND_MESSAGE)
                    .contains(" NOT ")
                    .doesNotContain(" not ");
        }

        @Test
        @DisplayName("the lookup-failure text stays unspecific, naming no response code, reason "
                + "code, exception, table or path")
        void theLookupFailureTextStaysUnspecific() {
            assertThat(TransactionViewResponse.TRANSACTION_LOOKUP_FAILED_MESSAGE)
                    .as("the program writes the raw response and reason codes to the operator "
                            + "console instead, and that detail stays out of this contract")
                    .isEqualTo("Unable to lookup Transaction...")
                    .doesNotContainPattern("[0-9]")
                    .doesNotContainIgnoringCase("exception")
                    .doesNotContainIgnoringCase("sql")
                    .doesNotContainIgnoringCase("resp")
                    .doesNotContain("/");
        }

        @ParameterizedTest(name = "\"{0}\" fits the message line")
        @MethodSource("com.carddemo.api.dto.TransactionViewResponseCoverageTest#messageTexts")
        @DisplayName("every published text fits the single message component")
        void everyPublishedTextFitsTheMessageComponent(String text) {
            assertThat(validator.validate(carrying("errorMessage", text))).isEmpty();
        }

        @Test
        @DisplayName("a fifty-character shared common message fits the message component at its "
                + "full width")
        void aFiftyCharacterSharedMessageFitsTheMessageComponent() {
            assertThat(MessageCatalogService.CCDA_MSG_INVALID_KEY)
                    .as("a forty-nine character literal in a fifty-character field plus one pad "
                            + "space, which must arrive untouched at that full width")
                    .hasSize(MessageCatalogService.COMMON_MESSAGE_WIDTH)
                    .hasSize(50);
            assertThat(validator.validate(
                            carrying("errorMessage", MessageCatalogService.CCDA_MSG_INVALID_KEY)))
                    .isEmpty();
        }

        @Test
        @DisplayName("the fifty-character shared message is not the forty-character thank-you "
                + "title value, and the two are never conflated")
        void theSharedMessageIsNotTheThankYouTitleValue() {
            assertThat(MessageCatalogService.CCDA_MSG_THANK_YOU)
                    .as("a different value at a different width for a different purpose")
                    .hasSize(50)
                    .isNotEqualTo(MessageCatalogService.CCDA_THANK_YOU);
            assertThat(MessageCatalogService.CCDA_THANK_YOU)
                    .hasSize(MessageCatalogService.SCREEN_TITLE_WIDTH)
                    .hasSize(40);
        }

        /**
         * The three published texts are the only <em>text</em> constants this type publishes, and the
         * full static inventory is stated so an addition cannot pass unnoticed.
         *
         * <p>Seven statics exist: the three message texts, three integers covering the amount's record
         * shape and the focus hint's width, and the placeholder the diagnostic rendering substitutes.
         * The placeholder is the only one that is not public, because it is an implementation detail of
         * that rendering rather than part of the published contract.</p>
         */
        @Test
        @DisplayName("the three published texts are the only text constants this type declares")
        void theThreePublishedTextsAreTheOnlyConstants() {
            List<Field> statics = Arrays.stream(TransactionViewResponse.class.getDeclaredFields())
                    .filter(field -> Modifier.isStatic(field.getModifiers()))
                    .filter(field -> !field.isSynthetic())
                    .toList();

            assertThat(statics.stream()
                            .filter(field -> Modifier.isPublic(field.getModifiers()))
                            .filter(field -> field.getType() == String.class)
                            .map(Field::getName)
                            .toList())
                    .containsExactlyInAnyOrder("EMPTY_TRANSACTION_ID_MESSAGE",
                            "TRANSACTION_NOT_FOUND_MESSAGE", "TRANSACTION_LOOKUP_FAILED_MESSAGE");

            assertThat(statics).extracting(Field::getName)
                    .containsExactlyInAnyOrder("EMPTY_TRANSACTION_ID_MESSAGE",
                            "TRANSACTION_NOT_FOUND_MESSAGE", "TRANSACTION_LOOKUP_FAILED_MESSAGE",
                            "AMOUNT_SCALE", "AMOUNT_INTEGER_DIGITS", "SCREEN_FIELD_ID_LENGTH",
                            "REDACTION_PLACEHOLDER");

            assertThat(statics.stream()
                            .filter(field -> !Modifier.isPublic(field.getModifiers()))
                            .map(Field::getName)
                            .toList())
                    .as("only the rendering's placeholder is unpublished")
                    .containsExactly("REDACTION_PLACEHOLDER");
        }
    }

    @Nested
    @DisplayName("Record-absent shape")
    class RecordAbsentShape {

        @Test
        @DisplayName("the search key is echoed back while every retrieved field stays absent, which "
                + "one component could not express")
        void theSearchKeyIsEchoedWhileEveryRetrievedFieldStaysAbsent() {
            TransactionViewResponse response = notFound();

            assertThat(response.searchTransactionId())
                    .as("the program does not blank the search key before it reads, and only the "
                            + "clear-screen path blanks it as a separate act")
                    .isEqualTo(SEARCH_TRANSACTION_ID);
            assertThat(response.transactionId()).isNull();
            assertThat(response.cardNumber()).isNull();
            assertThat(response.amount()).isNull();
            assertThat(response.errorMessage())
                    .isEqualTo(TransactionViewResponse.TRANSACTION_NOT_FOUND_MESSAGE);
            assertThat(response.generalError()).isTrue();
        }

        @Test
        @DisplayName("the record-absent payload carries the search key and the message while every "
                + "retrieved member is omitted")
        void theRecordAbsentPayloadOmitsEveryRetrievedMember() throws JsonProcessingException {
            JsonNode payload = payloadOf(notFound());

            assertThat(payload.get("searchTransactionId").asText())
                    .isEqualTo(SEARCH_TRANSACTION_ID);
            assertThat(payload.get("generalError").asBoolean()).isTrue();
            for (String component : RETRIEVED_RECORD_FIELDS) {
                assertThat(payload.has(component))
                        .as("%s is omitted on the record-absent path, which is how a client tells "
                                + "\"this is what you asked for\" from \"this is what was found\"",
                                component)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("a found record populates both identifiers, so the two shapes are "
                + "distinguishable without inspecting the message")
        void aFoundRecordPopulatesBothIdentifiers() {
            TransactionViewResponse response = displayed(null);

            assertThat(response.searchTransactionId()).isEqualTo(SEARCH_TRANSACTION_ID);
            assertThat(response.transactionId()).isEqualTo(TRANSACTION_ID);
            assertThat(response.errorMessage()).isNull();
            assertThat(response.generalError()).isFalse();
        }

        @Test
        @DisplayName("a raised indicator beside a blank message is a reachable state, so the two "
                + "are independent facts")
        void aRaisedIndicatorBesideABlankMessageIsReachable() throws JsonProcessingException {
            TransactionViewResponse response = new TransactionViewResponse(null, null, null, null,
                    null, null, SEARCH_TRANSACTION_ID, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, true, null, null, null);

            assertThat(response.generalError()).isTrue();
            assertThat(response.errorMessage()).isNull();
            assertThat(payloadOf(response).has("errorMessage"))
                    .as("a client that inferred the flag from the message would read the wrong "
                            + "signal")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("Decimal wire form")
    class DecimalWireForm {

        @Test
        @DisplayName("a two-place amount crosses with both decimal places intact")
        void aTwoPlaceAmountCrossesWithBothPlacesIntact() throws JsonProcessingException {
            String rendered = JsonContractSupport.declaredSettingsMapper()
                    .writeValueAsString(withAmount(new BigDecimal("1234.56")));

            assertThat(JsonContractSupport.renderedValueToken(rendered, "amount"))
                    .isEqualTo("1234.56");
        }

        @Test
        @DisplayName("a trailing-zero amount keeps its scale on the wire, because the scale is "
                + "contract rather than presentation")
        void aTrailingZeroAmountKeepsItsScale() throws JsonProcessingException {
            String rendered = JsonContractSupport.declaredSettingsMapper()
                    .writeValueAsString(withAmount(new BigDecimal("0.00")));

            assertThat(JsonContractSupport.renderedValueToken(rendered, "amount"))
                    .as("the assertion reads the emitted characters rather than a re-parsed tree, "
                            + "which would report this value back as 0.0")
                    .isEqualTo("0.00");
        }

        @ParameterizedTest(name = "\"{0}\" crosses as those exact characters")
        @ValueSource(strings = {"0.00", "0.01", "-0.01", "1.20", "-1234.56", "999999999.99",
            "-999999999.99"})
        @DisplayName("each amount crosses as the exact characters it declares, sign and scale "
                + "included")
        void eachAmountCrossesAsItsExactCharacters(String amount) throws JsonProcessingException {
            String rendered = JsonContractSupport.declaredSettingsMapper()
                    .writeValueAsString(withAmount(new BigDecimal(amount)));

            assertThat(JsonContractSupport.renderedValueToken(rendered, "amount"))
                    .isEqualTo(amount);
        }

        @Test
        @DisplayName("a nine-digit amount is never rendered in exponent form, so the emitted "
                + "characters stay comparable byte for byte")
        void aLargeAmountIsNeverRenderedInExponentForm() throws JsonProcessingException {
            String rendered = JsonContractSupport.declaredSettingsMapper()
                    .writeValueAsString(withAmount(new BigDecimal("123456789.12")));

            assertThat(JsonContractSupport.renderedValueToken(rendered, "amount"))
                    .as("the exponent check is scoped to the extracted number, because a whole "
                            + "payload check would match the letter E inside a member name")
                    .isEqualTo("123456789.12")
                    .doesNotContain("E")
                    .doesNotContain("e");
        }

        @Test
        @DisplayName("an absent amount is omitted rather than written as zero, so \"no amount\" and "
                + "\"zero\" stay distinguishable")
        void anAbsentAmountIsOmittedRatherThanWrittenAsZero() throws JsonProcessingException {
            assertThat(payloadOf(withAmount(null)).has("amount")).isFalse();
            assertThat(payloadOf(withAmount(new BigDecimal("0.00"))).has("amount")).isTrue();
        }

        @Test
        @DisplayName("an amount round trips with its scale preserved, because a lost scale would "
                + "make the value compare unequal")
        void anAmountRoundTripsWithItsScalePreserved() throws JsonProcessingException {
            ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();
            TransactionViewResponse response = withAmount(new BigDecimal("1.20"));

            TransactionViewResponse returned = mapper.readValue(
                    mapper.writeValueAsString(response), TransactionViewResponse.class);

            assertThat(returned.amount()).isEqualTo(new BigDecimal("1.20"));
            assertThat(returned.amount().scale()).isEqualTo(2);
            assertThat(returned).isEqualTo(response);
        }

        /**
         * This type applies no scaling of its own: an amount at the record scale is carried exactly as
         * supplied, including the trailing zero a normalising layer would strip.
         *
         * <p>An earlier revision made the point with a four-place amount and asserted its scale
         * survived. That amount is now refused outright, and the two behaviours are not in tension:
         * refusing an amount whose scale contradicts the record field, and rescaling one it accepts, are
         * different acts, and only the second would change the bytes the screen presents. Scaling still
         * belongs to the single decimal codec in the utility layer, which applies the estate's downward
         * mode in exactly one place; this type neither rescales nor rounds anything it carries.</p>
         */
        @Test
        @DisplayName("this type applies no scaling of its own, so a record-scaled amount is carried "
                + "exactly as supplied")
        void thisTypeAppliesNoScalingOfItsOwn() {
            assertThat(withAmount(new BigDecimal("1.20")).amount())
                    .as("scaling belongs to the single decimal codec in the utility layer, which "
                            + "applies the estate's downward mode in exactly one place")
                    .isEqualTo(new BigDecimal("1.20"));
            assertThat(withAmount(new BigDecimal("1.20")).amount().toPlainString())
                    .isEqualTo("1.20");
            assertThat(withAmount(new BigDecimal("1.20")).amount().scale())
                    .isEqualTo(TransactionViewResponse.AMOUNT_SCALE);
        }

        /**
         * An amount whose decimal shape contradicts the record field is refused rather than rescaled,
         * and the refusal names the offending figure without echoing the amount.
         *
         * <p>Both failure modes are covered: a scale other than two, in either direction, and a
         * magnitude needing more than nine integer digits. Refusing is what keeps the no-rescaling
         * guarantee above honest - a value the constructor cannot represent faithfully is rejected at the
         * boundary where the producer can still be identified, rather than quietly reshaped into
         * something the client will read as authoritative.</p>
         */
        @ParameterizedTest(name = "an amount of scale {1} is refused")
        @CsvSource({"1,0", "1.5,1", "1.234,3", "1.23456,5"})
        @DisplayName("refuses an amount off the record scale rather than rescaling it")
        void anAmountOffTheRecordScaleIsRefused(String literal, int scale) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> withAmount(new BigDecimal(literal)))
                    .withMessageContaining("must carry scale 2")
                    .withMessageContaining("its scale is " + scale);
        }

        @Test
        @DisplayName("refuses an amount wider than the record field")
        void anAmountWiderThanTheRecordFieldIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> withAmount(new BigDecimal("1234567890.12")))
                    .withMessageContaining("must fit 9 integer digits")
                    .withMessageContaining("it needs 10")
                    .satisfies(thrown -> assertThat(thrown.getMessage())
                            .as("the refusal never echoes the amount")
                            .doesNotContain("1234567890.12"));
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

        @Test
        @DisplayName("the record-absent shape reports no violation, because it is the ordinary "
                + "not-found response rather than an anomaly")
        void theRecordAbsentShapeReportsNoViolation() {
            assertThat(validator.validate(notFound())).isEmpty();
        }

        @ParameterizedTest(name = "{0} reports a value one character over {1}")
        @CsvSource({
            "transactionName,4",
            "title01,40",
            "currentDate,8",
            "programName,8",
            "title02,40",
            "currentTime,8",
            "searchTransactionId,16",
            "transactionId,16",
            "cardNumber,16",
            "typeCode,2",
            "categoryCode,4",
            "source,10",
            "description,60",
            "originationDate,10",
            "processingDate,10",
            "merchantId,9",
            "merchantName,30",
            "merchantCity,25",
            "merchantZip,10",
            "errorMessage,78",
            "focusScreenFieldId,7",
        })
        @DisplayName("each bounded component reports a value one character over its width")
        void eachBoundedComponentReportsAnOverLongValue(String component, int width) {
            Set<ConstraintViolation<TransactionViewResponse>> violations =
                    validator.validate(carrying(component, "9".repeat(width + 1)));

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString(component);
        }

        @ParameterizedTest(name = "{0} accepts a value exactly at {1}")
        @CsvSource({
            "searchTransactionId,16",
            "transactionId,16",
            "cardNumber,16",
            "typeCode,2",
            "categoryCode,4",
            "source,10",
            "description,60",
            "merchantId,9",
            "merchantName,30",
            "merchantCity,25",
            "errorMessage,78",
            "focusScreenFieldId,7",
        })
        @DisplayName("each bounded component accepts a value exactly at its width, so every bound "
                + "is inclusive")
        void eachBoundedComponentAcceptsAValueAtItsWidth(String component, int width) {
            assertThat(validator.validate(carrying(component, "9".repeat(width)))).isEmpty();
        }

        /**
         * The route is unbounded and the focus hint is bounded at the width of the widest field name on
         * this screen, which is the narrower of the two defensible readings.
         *
         * <p>Neither is a legacy screen <em>value</em>, which is why both were originally left
         * unmeasured. They differ in what they name. The route is a service-owned identifier whose length
         * is the service's business and no screen's, so measuring it would couple this contract to a
         * routing decision it does not make. The hint names a field on this screen, and the widest of
         * those is seven characters, so leaving it unmeasured admitted a name no field here can
         * have.</p>
         */
        @Test
        @DisplayName("the route is unbounded and the focus hint is bounded at this screen's widest "
                + "field name")
        void theFocusHintAndTheRouteAreUnbounded() {
            assertThat(validator.validate(carrying("nextRoute", "/".repeat(512)))).isEmpty();

            Set<ConstraintViolation<TransactionViewResponse>> violations =
                    validator.validate(carrying("focusScreenFieldId", "X".repeat(8)));

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath())
                    .hasToString("focusScreenFieldId");
            assertThat(validator.validate(carrying("focusScreenFieldId", "X".repeat(7)))).isEmpty();
        }

        @Test
        @DisplayName("an entirely empty response reports no violation, because every component may "
                + "legitimately be absent")
        void anEntirelyEmptyResponseReportsNoViolation() {
            assertThat(validator.validate(withAmount(null))).isEmpty();
        }

        @ParameterizedTest(name = "source \"{0}\" is accepted at its declared width")
        @ValueSource(strings = {"POS TERM  ", "OPERATOR  ", "System    ", "UNKNOWN   ",
            "          "})
        @DisplayName("a space-padded source is accepted at its full width and is neither trimmed "
                + "nor resolved")
        void aSpacePaddedSourceIsAcceptedAtItsFullWidth(String source) {
            TransactionViewResponse response = carrying("source", source);

            assertThat(validator.validate(response)).isEmpty();
            assertThat(response.source())
                    .as("the value is passed through exactly as read: never trimmed, never "
                            + "re-cased, never resolved")
                    .isEqualTo(source)
                    .hasSize(10);
        }

        @Test
        @DisplayName("a twenty-six-space stamp is a real value, and a ten-character slice of it is "
                + "accepted rather than rejected")
        void aBlankStampSliceIsAccepted() {
            assertThat(validator.validate(carrying("originationDate", " ".repeat(10))))
                    .as("a stamp of twenty-six spaces is a real value and must survive byte for "
                            + "byte, so its ten-character slice must too")
                    .isEmpty();
        }

        /**
         * Every amount the record field can hold is accepted by the validator, because the component
         * declares no bound and no digit budget.
         *
         * <p>The extremes chosen are the widest positive and widest negative values the field holds, not
         * arbitrarily large ones. A wider amount never reaches the validator at all - the canonical
         * constructor refuses it - so asserting that the validator tolerated one would assert nothing
         * about the validator. The two mechanisms are not alternatives: the constructor decides which
         * decimal shapes exist, and Bean Validation measures character widths on the components that
         * carry text.</p>
         */
        @Test
        @DisplayName("every amount the record field can hold is accepted, because the component "
                + "declares no bound and no digit budget")
        void anAmountOfAnyMagnitudeIsAccepted() {
            assertThat(validator.validate(withAmount(new BigDecimal("999999999.99")))).isEmpty();
            assertThat(validator.validate(withAmount(new BigDecimal("-999999999.99")))).isEmpty();
            assertThat(validator.validate(withAmount(new BigDecimal("0.00")))).isEmpty();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a wider amount is refused before a validator could see it")
                    .isThrownBy(() -> withAmount(new BigDecimal("99999999999999.99")));
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
            assertThat(payload.get("title01").asText()).isEqualTo(TITLE_LINE_1);
            assertThat(payload.get("currentDate").asText()).isEqualTo(CURRENT_DATE);
            assertThat(payload.get("programName").asText()).isEqualTo(PROGRAM_NAME);
            assertThat(payload.get("title02").asText()).isEqualTo(TITLE_LINE_2);
            assertThat(payload.get("currentTime").asText()).isEqualTo(CURRENT_TIME);
            assertThat(payload.get("typeCode").asText()).isEqualTo(TYPE_CODE);
            assertThat(payload.get("description").asText()).isEqualTo(DESCRIPTION);
            assertThat(payload.get("merchantName").asText()).isEqualTo(MERCHANT_NAME);
            assertThat(payload.get("merchantCity").asText()).isEqualTo(MERCHANT_CITY);
            assertThat(payload.get("merchantZip").asText()).isEqualTo(MERCHANT_ZIP);
            assertThat(payload.get("focusScreenFieldId").asText())
                    .isEqualTo(FOCUS_SCREEN_FIELD_ID);
            assertThat(payload.get("nextRoute").asText()).isEqualTo(NEXT_ROUTE);
        }

        @Test
        @DisplayName("every identifier and code crosses as text with its leading zeros intact")
        void everyIdentifierAndCodeCrossesAsTextWithLeadingZeros() throws JsonProcessingException {
            JsonNode payload = payloadOf(displayed(null));

            assertThat(payload.get("categoryCode").isTextual())
                    .as("a four-character category code must be rendered as \"0002\" and never as 2")
                    .isTrue();
            assertThat(payload.get("categoryCode").asText()).isEqualTo("0002");
            assertThat(payload.get("merchantId").isTextual()).isTrue();
            assertThat(payload.get("merchantId").asText()).isEqualTo(MERCHANT_ID).hasSize(9);
            assertThat(payload.get("transactionId").isTextual()).isTrue();
            assertThat(payload.get("transactionId").asText()).isEqualTo(TRANSACTION_ID).hasSize(16);
        }

        @Test
        @DisplayName("the card number crosses whole, neither shortened nor partially hidden, which "
                + "is the delivered contract rather than an accident")
        void theCardNumberCrossesWhole() throws JsonProcessingException {
            String rendered = JsonContractSupport.declaredSettingsMapper()
                    .writeValueAsString(displayed(null));

            assertThat(JsonContractSupport.renderedValueToken(rendered, "cardNumber"))
                    .as("the legacy applies no field-level protection to a card number anywhere; "
                            + "the gap is recorded in docs/decision-log.md and closing it here "
                            + "would change the response contract as unrequested work")
                    .isEqualTo("\"" + CARD_NUMBER + "\"");
            assertThat(rendered).doesNotContain("****");
        }

        @Test
        @DisplayName("the source crosses with its padding intact, so a ten-character value stays "
                + "ten characters")
        void theSourceCrossesWithItsPaddingIntact() throws JsonProcessingException {
            JsonNode payload = payloadOf(displayed(null));

            assertThat(payload.get("source").asText()).isEqualTo(SOURCE).hasSize(10);
        }

        @Test
        @DisplayName("both dates cross as ten characters of text, with no reformatting of either "
                + "stamp shape")
        void bothDatesCrossAsTenCharactersOfText() throws JsonProcessingException {
            JsonNode payload = payloadOf(displayed(null));

            assertThat(payload.get("originationDate").isTextual()).isTrue();
            assertThat(payload.get("originationDate").asText())
                    .isEqualTo(ORIGINATION_DATE)
                    .hasSize(10);
            assertThat(payload.get("processingDate").asText())
                    .isEqualTo(PROCESSING_DATE)
                    .hasSize(10);
        }

        @Test
        @DisplayName("an absent member is omitted while the error indicator is always written, "
                + "because a primitive has no absent state")
        void absentMembersAreOmittedAndTheIndicatorIsAlwaysWritten() throws JsonProcessingException {
            JsonNode payload = payloadOf(withAmount(null));

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
        @DisplayName("an explicitly blanked field is written while an absent one is omitted, so the "
                + "blanked display area stays distinguishable from an unset one")
        void aBlankedFieldIsWrittenWhileAnAbsentOneIsOmitted() throws JsonProcessingException {
            JsonNode payload = payloadOf(carrying("description", ""));

            assertThat(payload.get("description").asText()).isEmpty();
            assertThat(payload.has("merchantName")).isFalse();
        }

        @Test
        @DisplayName("a fully populated response round trips unchanged, amount scale and nested "
                + "navigation state included")
        void aFullyPopulatedResponseRoundTripsUnchanged() throws JsonProcessingException {
            TransactionViewResponse response = displayed(JsonContractSupport.populatedNavigation());
            ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();

            TransactionViewResponse returned = mapper.readValue(
                    mapper.writeValueAsString(response), TransactionViewResponse.class);

            assertThat(returned).isEqualTo(response);
            assertThat(returned.amount()).isEqualTo(AMOUNT);
            assertThat(returned.source()).isEqualTo(SOURCE);
            assertThat(returned.navigationContext())
                    .isEqualTo(JsonContractSupport.populatedNavigation());
        }

        @Test
        @DisplayName("an unknown member is ignored rather than rejected, so a client may echo the "
                + "response back without being refused")
        void anUnknownMemberIsIgnored() throws JsonProcessingException {
            String payload = "{\"transactionId\":\"" + TRANSACTION_ID
                    + "\",\"respCode\":12,\"reasonCode\":\"0000\"}";

            TransactionViewResponse returned = JsonContractSupport.declaredSettingsMapper()
                    .readValue(payload, TransactionViewResponse.class);

            assertThat(returned.transactionId()).isEqualTo(TRANSACTION_ID);
            assertThat(returned.errorMessage())
                    .as("no response code and no reason code is a member of this contract")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("Diagnostic rendering")
    class DiagnosticRendering {

        /** The eleven components this type replaces with a fixed placeholder in its rendering. */
        private static final List<String> WITHHELD_BY_THIS_TYPE = List.of(
                "searchTransactionId", "transactionId", "cardNumber", "description", "amount",
                "originationDate", "processingDate", "merchantId", "merchantName", "merchantCity",
                "merchantZip");

        /**
         * The rendering keeps the generated shape and replaces eleven of the twenty-five component
         * values with a fixed placeholder.
         *
         * <p>The legacy design applies no field-level protection to a card number anywhere, and that
         * gap is recorded in {@code docs/decision-log.md} - but the gap is about what the screen
         * <em>displays</em> and what the payload carries, not about what a log line carries. This
         * rendering is a third channel, reachable from any structured logger, framework diagnostic,
         * failed assertion or string interpolation that touches the response, and a single stringified
         * instance would otherwise have emitted a full primary account number beside the amount, the
         * merchant and the two timestamps: a complete transaction record in one line, in a place no
         * operator authorisation governs.</p>
         *
         * <p><strong>Why the fourteen are retained.</strong> The screen furniture, the clock values, the
         * type and category codes, the source, the error text and indicator, the focus hint, the route
         * and the navigation state identify nobody on their own. They are the part of a response worth
         * seeing in a diagnostic, and a rendering that withheld everything would be one nobody could
         * use.</p>
         *
         * <p><strong>Withholding is confined to this method.</strong> Every accessor returns its
         * component unaltered and the serialized payload is unaffected, which is asserted where the wire
         * shape is exercised.</p>
         */
        @Test
        @DisplayName("the rendering keeps the generated shape and replaces eleven component values "
                + "with a placeholder")
        void theRenderingIsTheGeneratedOne() {
            String rendered = displayed(null).toString();

            assertThat(rendered).startsWith("TransactionViewResponse[").endsWith("]");
            assertThat(WITHHELD_BY_THIS_TYPE).hasSize(11);
            assertThat(EXPECTED_COMPONENTS).containsAll(WITHHELD_BY_THIS_TYPE);

            assertThat(rendered)
                    .as("no primary account number, amount, description, merchant or stamp reaches "
                            + "the diagnostic channel")
                    .doesNotContain(CARD_NUMBER)
                    .doesNotContain("1234.56")
                    .doesNotContain(DESCRIPTION)
                    .doesNotContain(MERCHANT_ID)
                    .doesNotContain(MERCHANT_NAME)
                    .doesNotContain(MERCHANT_CITY)
                    .doesNotContain(MERCHANT_ZIP)
                    .doesNotContain(ORIGINATION_DATE)
                    .doesNotContain(PROCESSING_DATE);
            assertThat(rendered)
                    .as("the non-identifying values are retained")
                    .contains("source=" + SOURCE)
                    .contains("typeCode=" + TYPE_CODE)
                    .contains("categoryCode=" + CATEGORY_CODE);

            for (String component : EXPECTED_COMPONENTS) {
                assertThat(rendered)
                        .as("%s still appears under its declared name", component)
                        .contains(component + "=");
                if (WITHHELD_BY_THIS_TYPE.contains(component)) {
                    assertThat(rendered)
                            .as("%s carries regulated content and is withheld", component)
                            .contains(component + "=***REDACTED***");
                } else {
                    assertThat(rendered)
                            .as("%s identifies nobody and is rendered rather than replaced",
                                    component)
                            .doesNotContain(component + "=***REDACTED***");
                }
            }
        }

        /**
         * Every accessor still returns its component unaltered, so the withholding above is confined to
         * the diagnostic channel and changes nothing a client receives.
         *
         * <p>This is the half of the claim that keeps parity intact: the legacy screen shows the card
         * number, the amount, the description, the merchant and both stamps in full, so a control that
         * altered any of them on the way to the client would change what the screen shows. Only the
         * text rendering withholds them.</p>
         */
        @Test
        @DisplayName("the withholding is confined to the rendering, so every accessor is unaltered")
        void theWithholdingIsConfinedToTheRendering() {
            TransactionViewResponse response = displayed(null);

            assertThat(response.cardNumber()).isEqualTo(CARD_NUMBER);
            assertThat(response.transactionId()).isEqualTo(TRANSACTION_ID);
            assertThat(response.searchTransactionId()).isEqualTo(SEARCH_TRANSACTION_ID);
            assertThat(response.description()).isEqualTo(DESCRIPTION);
            assertThat(response.amount()).isEqualTo(AMOUNT);
            assertThat(response.originationDate()).isEqualTo(ORIGINATION_DATE);
            assertThat(response.processingDate()).isEqualTo(PROCESSING_DATE);
            assertThat(response.merchantId()).isEqualTo(MERCHANT_ID);
            assertThat(response.merchantName()).isEqualTo(MERCHANT_NAME);
            assertThat(response.merchantCity()).isEqualTo(MERCHANT_CITY);
            assertThat(response.merchantZip()).isEqualTo(MERCHANT_ZIP);
        }

        @Test
        @DisplayName("the rendering carries no logger output, response code or internal artefact "
                + "name, because this type holds no logger and writes to no stream")
        void theRenderingCarriesNoInternalArtefact() {
            assertThat(displayed(null).toString())
                    .doesNotContainIgnoringCase("exception")
                    .doesNotContainIgnoringCase("select ")
                    .doesNotContainIgnoringCase("respcode")
                    .doesNotContainIgnoringCase("schema");
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
            TransactionViewResponse first = displayed(JsonContractSupport.populatedNavigation());
            TransactionViewResponse second = displayed(JsonContractSupport.populatedNavigation());

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
            assertThat(first).isNotEqualTo(displayed(null));
        }

        @Test
        @DisplayName("the two identifiers participate in equality independently, so a found shape "
                + "and a not-found shape are distinguishable")
        void theTwoIdentifiersParticipateInEqualityIndependently() {
            assertThat(carrying("searchTransactionId", SEARCH_TRANSACTION_ID))
                    .isNotEqualTo(carrying("transactionId", TRANSACTION_ID));
        }

        /**
         * An amount's scale participates in equality, and a scale-shifted amount cannot be constructed
         * at all, so a lost scale is caught twice over.
         *
         * <p>Equality is the weaker of the two guarantees: it makes a dropped decimal place
         * <em>detectable</em> by a caller that thinks to compare. The constructor's refusal is the
         * stronger one: the shifted value never crosses the boundary. The equality claim is still worth
         * making, because it is what makes a scale lost in <em>transit</em> - between two record-shaped
         * amounts of different value - fail rather than pass unnoticed.</p>
         */
        @Test
        @DisplayName("an amount's scale participates in equality, and a scale-shifted amount cannot "
                + "be constructed")
        void anAmountScaleParticipatesInEquality() {
            assertThat(withAmount(new BigDecimal("1.20")))
                    .as("decimal equality compares value and scale, so a value silently altered in "
                            + "transit fails here rather than passing unnoticed")
                    .isNotEqualTo(withAmount(new BigDecimal("1.21")));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("the scale-shifted amount is refused rather than merely unequal")
                    .isThrownBy(() -> withAmount(new BigDecimal("1.2")));
        }

        @Test
        @DisplayName("a space-padded source is not equal to its trimmed form, because the padding "
                + "is part of the value")
        void aSpacePaddedSourceIsNotItsTrimmedForm() {
            assertThat(carrying("source", SOURCE))
                    .isNotEqualTo(carrying("source", SOURCE.strip()));
        }

        @Test
        @DisplayName("the error indicator participates in equality, so a flagged and a clear "
                + "response carrying the same text are distinguishable")
        void theErrorIndicatorParticipatesInEquality() {
            TransactionViewResponse flagged = new TransactionViewResponse(null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, "same", true, null, null, null);
            TransactionViewResponse clear = new TransactionViewResponse(null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, "same", false, null, null, null);

            assertThat(flagged).isNotEqualTo(clear);
        }

        @Test
        @DisplayName("a blank text and an absent one do not compare equal, because the blanked "
                + "display area is a state the program reaches")
        void aBlankTextIsNotAnAbsentOne() {
            assertThat(carrying("description", "")).isNotEqualTo(carrying("description", null));
        }

        @Test
        @DisplayName("a value's surrounding spaces survive validation and equality, which is what a "
                + "fixed-width estate requires")
        void surroundingSpacesSurviveValidationAndEquality() {
            String padded = "  " + Locale.ROOT.getLanguage() + "  ";

            assertThat(validator.validate(carrying("description", padded))).isEmpty();
            assertThat(carrying("description", padded).description()).isEqualTo(padded);
        }
    }
}
