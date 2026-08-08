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
import static org.assertj.core.api.Assertions.assertThatCode;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.IntStream;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.carddemo.domain.enums.KeyAction;

/**
 * Unit test for {@link TransactionAddRequest}, the inbound contract of the {@code CT02} add screen.
 *
 * <h2>What is actually at risk in a request of this shape</h2>
 *
 * <p>This is the widest regulated surface of any request in the package. One submission carries a primary
 * account number at full sixteen characters, an account identifier, a monetary amount, and four merchant
 * components including a free-text name the operator typed. A generated record rendering places every one
 * of them into each log line, exception message and debugger frame the request passes through, and the
 * add screen is the one screen an operator retries, so a rejected submission is exactly the submission
 * most likely to be rendered.</p>
 *
 * <p>An earlier revision of the contract recorded that the generated rendering should stand, reasoning
 * that the legacy design applies no field-level protection to these values anyway. That reasoning is
 * rejected here and the rejection is what these tests hold in place: the absence of encryption at rest is
 * a separate documented gap, and it is not a licence to widen the gap by copying the same values into
 * every diagnostic sink. The response this request is answered by already withholds the identical eight
 * components, so leaving the request open would also have made the two halves of one screen disagree.</p>
 *
 * <p>The tests therefore assert the eight withheld components negatively - absent in whole, and absent in
 * every fragment long enough to identify the value, including the six-character issuer prefix and the
 * four-character tail a partial disclosure usually leaks - and assert the eight retained components
 * positively, because a rendering that withheld everything would protect nothing that matters and destroy
 * the diagnostic value that does.</p>
 *
 * <p>Two further risks are covered. The echoed navigation state declares widths that nothing evaluates
 * unless this contract cascades into it, so the cascade is asserted by observing a nested property path.
 * And every one of the eleven emptiness checks the add program runs, in its own order, must remain the
 * program's to run: the boundary measures width and nothing else, so a blank, a wrong shape and an
 * out-of-vocabulary code all transport intact rather than being refused here.</p>
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No COBOL statement is transcribed.</p>
 */
@DisplayName("TransactionAddRequest - the CT02 inbound contract")
class TransactionAddRequestSecurityTest {

    /** The sixteen components in declaration order. A change here is a change to the REST contract. */
    private static final List<String> COMPONENTS_IN_ORDER = List.of(
            "accountId", "cardNumber", "typeCode", "categoryCode", "transactionSource", "description",
            "amount", "originationDate", "processingDate", "merchantId", "merchantName",
            "merchantCity", "merchantZip", "confirm", "keyAction", "navigationContext");

    /** The eight components the rendering must withhold. */
    private static final List<String> WITHHELD_COMPONENTS = List.of(
            "accountId", "cardNumber", "description", "amount", "merchantId", "merchantName",
            "merchantCity", "merchantZip");

    /** The thirteen bounded text components, paired positionally with {@link #BOUNDED_WIDTHS}. */
    private static final List<String> BOUNDED_COMPONENTS = List.of(
            "accountId", "cardNumber", "typeCode", "categoryCode", "transactionSource", "description",
            "amount", "originationDate", "processingDate", "merchantId", "merchantName",
            "merchantCity", "merchantZip", "confirm");

    /**
     * The measured widths of those fourteen components, read from {@code app/cpy-bms/COTRN02.CPY} rather
     * than from the class under test, so a width edited on the request alone fails here instead of
     * agreeing with itself. The amount's twelve is the screen field's own width, which is what bounds it
     * now that the amount travels as the typed lexeme rather than as a parsed decimal.
     */
    private static final List<Integer> BOUNDED_WIDTHS =
            List.of(11, 16, 2, 4, 10, 60, 12, 10, 10, 9, 30, 25, 10, 1);

    /** The fixed stand-in the rendering must emit in place of a withheld value. */
    private static final String REDACTION_PLACEHOLDER_TEXT = "***REDACTED***";

    /**
     * An eleven-character account identifier, deliberately every-digit-distinct rather than zero padded
     * so the fragment scans measure disclosure of this value rather than an accidental run of zeros
     * shared with a retained value.
     */
    private static final String ACCOUNT_ID = "78412590063";

    /** A sixteen-character primary account number. A synthetic test value, not an issued card. */
    private static final String CARD_NUMBER = "4532015112830366";

    /**
     * A monetary amount, as the operator typed it.
     *
     * <p>A string rather than a decimal, because the screen field is twelve characters of text and the
     * add program tests its shape itself. Withheld from the rendering, so no digit of it may reach a
     * diagnostic.</p>
     */
    private static final String AMOUNT = "1234.56";

    /** Free-text the operator typed. Withheld, because free text is where anything can end up. */
    private static final String DESCRIPTION = "MONTHLY SUBSCRIPTION RENEWAL";

    /** A nine-character merchant identifier. */
    private static final String MERCHANT_ID = "938271465";

    /** A merchant name. */
    private static final String MERCHANT_NAME = "ACME HARDWARE SUPPLY";

    /** A merchant city. */
    private static final String MERCHANT_CITY = "SPRINGFIELD";

    /** A merchant postal code. */
    private static final String MERCHANT_ZIP = "62704-5581";

    /** A realistic confirmed add submission with every component populated. */
    private static TransactionAddRequest populated() {
        return new TransactionAddRequest(ACCOUNT_ID, CARD_NUMBER, "01", "0005", "POS TERM",
                DESCRIPTION, AMOUNT, "2024-01-15", "2024-01-16", MERCHANT_ID, MERCHANT_NAME,
                MERCHANT_CITY, MERCHANT_ZIP, "Y", KeyAction.ENTER,
                NavigationContext.empty().withReEntry());
    }

    /**
     * Mirrors the four serialisation settings the module declares in {@code application.yml}, so a
     * payload asserted here is the payload the service actually emits and receives.
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

    private static Set<ConstraintViolation<TransactionAddRequest>> violationsOf(
            TransactionAddRequest request) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(request);
        }
    }

    @Nested
    @DisplayName("The component set is the add map plus conversation state")
    class TheComponentSetIsTheAddMap {

        @Test
        @DisplayName("sixteen components are declared in order")
        void sixteenComponentsAreDeclaredInOrder() {
            List<String> declared = Arrays.stream(TransactionAddRequest.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared).containsExactlyElementsOf(COMPONENTS_IN_ORDER).hasSize(16);
        }

        @Test
        @DisplayName("no identifier for the transaction being created is declared, because the add "
                + "operation derives the new key itself rather than accepting one")
        void noTransactionIdentifierIsDeclared() {
            List<String> lowerCased = Arrays.stream(TransactionAddRequest.class.getRecordComponents())
                    .map(component -> component.getName().toLowerCase(Locale.ROOT))
                    .toList();

            assertThat(lowerCased)
                    .noneMatch(name -> name.contains("transactionid"))
                    .noneMatch(name -> name.contains("newtransaction"))
                    .noneMatch(name -> name.contains("sequence"));
        }

        @Test
        @DisplayName("no screen-attribute, length or cursor component is declared")
        void noScreenAttributeComponentIsDeclared() {
            List<String> lowerCased = Arrays.stream(TransactionAddRequest.class.getRecordComponents())
                    .map(component -> component.getName().toLowerCase(Locale.ROOT))
                    .toList();

            assertThat(lowerCased)
                    .noneMatch(name -> name.contains("attrib"))
                    .noneMatch(name -> name.contains("colour") || name.contains("color"))
                    .noneMatch(name -> name.contains("cursor"))
                    .noneMatch(name -> name.endsWith("len") || name.endsWith("length"));
        }

        @Test
        @DisplayName("every submitted value including the amount is characters and only the attention "
                + "key is typed, so no leading zero is lost and nothing is parsed at the boundary")
        void everySubmittedValueIsCharactersIncludingTheAmount() {
            for (RecordComponent component : TransactionAddRequest.class.getRecordComponents()) {
                switch (component.getName()) {
                    case "keyAction" -> assertThat(component.getType()).isEqualTo(KeyAction.class);
                    case "navigationContext" ->
                            assertThat(component.getType()).isEqualTo(NavigationContext.class);
                    default -> assertThat(component.getType())
                            .as("component %s", component.getName())
                            .isEqualTo(String.class);
                }
            }

            assertThat(Arrays.stream(TransactionAddRequest.class.getRecordComponents())
                    .map(RecordComponent::getType)
                    .toList())
                    .as("no component parses the amount at the boundary, because a parse there would "
                            + "reject a malformed value the add screen must report on itself")
                    .doesNotContain(BigDecimal.class);
        }

        @Test
        @DisplayName("every accessor returns exactly what it was constructed with, so no two of the "
                + "thirteen same-typed components are transposed")
        void everyAccessorReturnsExactlyWhatItWasConstructedWith() {
            TransactionAddRequest request = populated();

            assertThat(request.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(request.cardNumber()).isEqualTo(CARD_NUMBER);
            assertThat(request.typeCode()).isEqualTo("01");
            assertThat(request.categoryCode()).isEqualTo("0005");
            assertThat(request.transactionSource()).isEqualTo("POS TERM");
            assertThat(request.description()).isEqualTo(DESCRIPTION);
            assertThat(request.amount()).isEqualTo(AMOUNT);
            assertThat(request.originationDate()).isEqualTo("2024-01-15");
            assertThat(request.processingDate()).isEqualTo("2024-01-16");
            assertThat(request.merchantId()).isEqualTo(MERCHANT_ID);
            assertThat(request.merchantName()).isEqualTo(MERCHANT_NAME);
            assertThat(request.merchantCity()).isEqualTo(MERCHANT_CITY);
            assertThat(request.merchantZip()).isEqualTo(MERCHANT_ZIP);
            assertThat(request.confirm()).isEqualTo("Y");
            assertThat(request.keyAction()).isEqualTo(KeyAction.ENTER);
        }
    }

    @Nested
    @DisplayName("The rendering discloses nothing while the wire carries everything")
    class TheRenderingDisclosesNothingWhileTheWireCarriesEverything {

        @Test
        @DisplayName("the rendering is exactly the eight retained values plus eight placeholders")
        void theRenderingIsExactlyTheEightRetainedValuesPlusEightPlaceholders() {
            assertThat(populated()).hasToString("TransactionAddRequest["
                    + "accountId=" + REDACTION_PLACEHOLDER_TEXT
                    + ", cardNumber=" + REDACTION_PLACEHOLDER_TEXT
                    + ", typeCode=01"
                    + ", categoryCode=0005"
                    + ", transactionSource=POS TERM"
                    + ", description=" + REDACTION_PLACEHOLDER_TEXT
                    + ", amount=" + REDACTION_PLACEHOLDER_TEXT
                    + ", originationDate=2024-01-15"
                    + ", processingDate=2024-01-16"
                    + ", merchantId=" + REDACTION_PLACEHOLDER_TEXT
                    + ", merchantName=" + REDACTION_PLACEHOLDER_TEXT
                    + ", merchantCity=" + REDACTION_PLACEHOLDER_TEXT
                    + ", merchantZip=" + REDACTION_PLACEHOLDER_TEXT
                    + ", confirm=Y"
                    + ", keyAction=" + KeyAction.ENTER
                    + ", navigationContext=" + NavigationContext.empty().withReEntry()
                    + "]");
        }

        @Test
        @DisplayName("the withheld set is exactly eight of the sixteen components, so the split between "
                + "protected and diagnostic is stated rather than implied")
        void theWithheldSetIsExactlyEightOfTheSixteenComponents() {
            assertThat(WITHHELD_COMPONENTS).hasSize(8).isSubsetOf(COMPONENTS_IN_ORDER);

            String rendered = populated().toString();

            WITHHELD_COMPONENTS.forEach(name -> assertThat(rendered)
                    .as("component %s must render as the placeholder", name)
                    .contains(name + "=" + REDACTION_PLACEHOLDER_TEXT));
        }

        @Test
        @DisplayName("every withheld value appears nowhere in the rendering, in whole")
        void everyWithheldValueAppearsNowhereInWhole() {
            String rendered = populated().toString();

            assertThat(rendered)
                    .doesNotContain(ACCOUNT_ID)
                    .doesNotContain(CARD_NUMBER)
                    .doesNotContain(DESCRIPTION)
                    .doesNotContain(AMOUNT)
                    .doesNotContain(MERCHANT_ID)
                    .doesNotContain(MERCHANT_NAME)
                    .doesNotContain(MERCHANT_CITY)
                    .doesNotContain(MERCHANT_ZIP);
        }

        @Test
        @DisplayName("neither the issuer prefix nor the four-character tail of the card number survives, "
                + "which is the partial disclosure a masked rendering would have left")
        void neitherThePrefixNorTheTailOfTheCardNumberSurvives() {
            String rendered = populated().toString();

            assertThat(rendered).doesNotContain(CARD_NUMBER.substring(0, 6));
            assertThat(rendered).doesNotContain(CARD_NUMBER.substring(CARD_NUMBER.length() - 4));
        }

        @Test
        @DisplayName("no six-character run of the card number survives")
        void noSixCharacterRunOfTheCardNumberSurvives() {
            String rendered = populated().toString();

            IntStream.rangeClosed(0, CARD_NUMBER.length() - 6)
                    .mapToObj(start -> CARD_NUMBER.substring(start, start + 6))
                    .forEach(fragment -> assertThat(rendered)
                            .as("card fragment %s must not appear", fragment)
                            .doesNotContain(fragment));
        }

        @Test
        @DisplayName("no six-character run of the account identifier survives")
        void noSixCharacterRunOfTheAccountIdentifierSurvives() {
            String rendered = populated().toString();

            IntStream.rangeClosed(0, ACCOUNT_ID.length() - 6)
                    .mapToObj(start -> ACCOUNT_ID.substring(start, start + 6))
                    .forEach(fragment -> assertThat(rendered)
                            .as("account fragment %s must not appear", fragment)
                            .doesNotContain(fragment));
        }

        @Test
        @DisplayName("no digit run of the amount survives and its length is not disclosed either")
        void noDigitRunOfTheAmountSurvives() {
            String rendered = populated().toString();

            assertThat(rendered)
                    .doesNotContain("1234.56")
                    .doesNotContain("1234")
                    .doesNotContain("123456")
                    .doesNotContain(".56");
        }

        @Test
        @DisplayName("no word of the free-text description or the merchant name survives, because a "
                + "word-level leak of free text is still a leak")
        void noWordOfTheFreeTextSurvives() {
            String rendered = populated().toString();

            Arrays.stream((DESCRIPTION + " " + MERCHANT_NAME).split(" "))
                    .forEach(word -> assertThat(rendered)
                            .as("word %s must not appear", word)
                            .doesNotContain(word));
        }

        @Test
        @DisplayName("two instances differing only in the eight withheld values render identically, so "
                + "the placeholders are constants rather than transformations")
        void twoInstancesDifferingOnlyInTheWithheldValuesRenderIdentically() {
            TransactionAddRequest first = populated();
            TransactionAddRequest second = new TransactionAddRequest("00000000001",
                    "9999888877776666", "01", "0005", "POS TERM", "SOMETHING ELSE ENTIRELY",
                    "0.01", "2024-01-15", "2024-01-16", "111111111", "OTHER SHOP",
                    "ELSEWHERE", "00000-0000", "Y", KeyAction.ENTER,
                    NavigationContext.empty().withReEntry());

            assertThat(first).hasToString(second.toString());
        }

        @Test
        @DisplayName("an absent withheld value still renders as the placeholder, so absence and presence "
                + "are indistinguishable in a diagnostic")
        void anAbsentWithheldValueStillRendersAsThePlaceholder() {
            TransactionAddRequest sparse = new TransactionAddRequest(null, null, "01", null, null,
                    null, null, null, null, null, null, null, null, null, null, null);

            String rendered = sparse.toString();

            assertThat(rendered)
                    .contains("cardNumber=" + REDACTION_PLACEHOLDER_TEXT)
                    .contains("amount=" + REDACTION_PLACEHOLDER_TEXT)
                    .doesNotContain("cardNumber=null")
                    .doesNotContain("amount=null");
        }

        @Test
        @DisplayName("the eight retained values are retained, because withholding them would remove the "
                + "rendering's diagnostic value without protecting anything")
        void theEightRetainedValuesAreRetained() {
            String rendered = populated().toString();

            assertThat(rendered)
                    .contains("typeCode=01")
                    .contains("categoryCode=0005")
                    .contains("transactionSource=POS TERM")
                    .contains("originationDate=2024-01-15")
                    .contains("processingDate=2024-01-16")
                    .contains("confirm=Y")
                    .contains("keyAction=" + KeyAction.ENTER)
                    .contains("navigationContext=");
        }

        @Test
        @DisplayName("the confirmation answer is retained here, matching the response for this same "
                + "screen, which reports the confirmation state it acted on")
        void theConfirmationAnswerIsRetainedHere() {
            TransactionAddRequest declined = new TransactionAddRequest(null, null, null, null, null,
                    null, null, null, null, null, null, null, null, "N", null, null);

            assertThat(declined.toString()).contains("confirm=N");
        }

        @Test
        @DisplayName("the nested navigation state redacts its own identifying values, so printing it by "
                + "delegation discloses nothing further")
        void theNestedNavigationStateRedactsItsOwn() {
            NavigationContext identifying = new NavigationContext(null, null, null, null, "USER0001",
                    "U", NavigationContext.ProgramContext.REENTER, "000000007", "GRACE", null,
                    "HOPPER", ACCOUNT_ID, "Y", CARD_NUMBER, null, null);
            TransactionAddRequest request = new TransactionAddRequest(ACCOUNT_ID, CARD_NUMBER, null,
                    null, null, null, null, null, null, null, null, null, null, null, null,
                    identifying);

            String rendered = request.toString();

            assertThat(rendered)
                    .doesNotContain(CARD_NUMBER)
                    .doesNotContain(ACCOUNT_ID)
                    .doesNotContain("GRACE")
                    .doesNotContain("HOPPER");
        }

        @Test
        @DisplayName("the rendering is safe when every component is absent")
        void theRenderingIsSafeWhenEveryComponentIsAbsent() {
            TransactionAddRequest empty = new TransactionAddRequest(null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null);

            assertThatCode(empty::toString).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the wire still carries every withheld value at full width and full scale, because "
                + "the rendering is a diagnostic channel and not the transport")
        void theWireStillCarriesEveryWithheldValue() throws JsonProcessingException {
            ObjectMapper mapper = moduleEquivalentMapper();

            JsonNode payload = mapper.readTree(mapper.writeValueAsString(populated()));

            assertThat(payload.get("cardNumber").asText()).isEqualTo(CARD_NUMBER).hasSize(16);
            assertThat(payload.get("accountId").asText()).isEqualTo(ACCOUNT_ID);
            assertThat(payload.get("description").asText()).isEqualTo(DESCRIPTION);
            assertThat(payload.get("amount").asText()).isEqualTo(AMOUNT);
            assertThat(payload.get("merchantName").asText()).isEqualTo(MERCHANT_NAME);
        }
    }

    @Nested
    @DisplayName("Bounds measure and never alter")
    class BoundsMeasureAndNeverAlter {

        @Test
        @DisplayName("each bounded component carries exactly its screen field's measured width and no "
                + "other constraint")
        void eachBoundedComponentCarriesItsMeasuredWidth() throws NoSuchFieldException {
            for (int index = 0; index < BOUNDED_COMPONENTS.size(); index++) {
                String name = BOUNDED_COMPONENTS.get(index);
                Field field = TransactionAddRequest.class.getDeclaredField(name);
                Size size = field.getAnnotation(Size.class);

                assertThat(size).as("component %s must carry a width bound", name).isNotNull();
                assertThat(size.max()).as("component %s width", name)
                        .isEqualTo(BOUNDED_WIDTHS.get(index));
                assertThat(size.min()).as("component %s must declare no minimum", name).isZero();
                assertThat(Arrays.stream(field.getAnnotations())
                        .filter(annotation -> annotation.annotationType().getPackageName()
                                .startsWith("jakarta.validation"))
                        .toList())
                        .as("component %s carries one constraint and no other", name)
                        .hasSize(1);
            }
        }

        @Test
        @DisplayName("the amount carries its screen width and no numeric bound at all, because the add "
                + "program tests its shape itself and a boundary rejection would pre-empt the message "
                + "the screen must show")
        void theAmountCarriesItsWidthAndNoNumericBound() throws NoSuchFieldException {
            Field field = TransactionAddRequest.class.getDeclaredField("amount");

            assertThat(Arrays.stream(field.getAnnotations())
                    .filter(annotation -> annotation.annotationType().getPackageName()
                            .startsWith("jakarta.validation"))
                    .map(annotation -> annotation.annotationType().getSimpleName())
                    .toList())
                    .as("the width is the only rule; a numeric bound here would refuse a value the add"
                            + " screen must report on in its own words")
                    .containsExactly("Size")
                    .doesNotContain("Digits", "DecimalMin", "DecimalMax", "Min", "Max", "Positive",
                            "PositiveOrZero", "NotNull", "Pattern");
            assertThat(field.getAnnotation(Size.class).max()).isEqualTo(12);
        }

        @Test
        @DisplayName("no presence, pattern or assertion constraint appears on any component, because "
                + "each of the eleven emptiness checks belongs to the add program in its own order")
        void noPresenceOrFormatConstraintAppears() throws NoSuchFieldException {
            for (String name : COMPONENTS_IN_ORDER) {
                Field field = TransactionAddRequest.class.getDeclaredField(name);

                assertThat(Arrays.stream(field.getAnnotations())
                        .map(annotation -> annotation.annotationType().getSimpleName())
                        .toList())
                        .as("component %s", name)
                        .doesNotContain("NotNull", "NotBlank", "NotEmpty", "Pattern", "Digits",
                                "Min", "Max", "Positive", "AssertTrue");
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "\t"})
        @DisplayName("a blank in every bounded component transports rather than being rejected, because "
                + "blank is the state the add screen prompts against rather than refuses")
        void aBlankValueTransportsRatherThanBeingRejected(String blank) {
            TransactionAddRequest request = new TransactionAddRequest(blank, blank, blank, blank,
                    blank, blank, blank, blank, blank, blank, blank, blank, blank, blank, null, null);

            assertThat(violationsOf(request)).isEmpty();
            assertThat(request.cardNumber()).isEqualTo(blank);
            assertThat(request.amount()).isEqualTo(blank);
            assertThat(request.confirm()).isEqualTo(blank);
        }

        @Test
        @DisplayName("a submission space-filled to each component's own declared width draws no "
                + "violation, because that is the shape a blank add screen transmits")
        void aSpaceFilledSubmissionAtEachDeclaredWidthDrawsNoViolation() {
            TransactionAddRequest spaceFilled = new TransactionAddRequest(" ".repeat(11),
                    " ".repeat(16), " ".repeat(2), " ".repeat(4), " ".repeat(10), " ".repeat(60),
                    " ".repeat(12), " ".repeat(10), " ".repeat(10), " ".repeat(9), " ".repeat(30),
                    " ".repeat(25), " ".repeat(10), " ", null, null);

            assertThat(violationsOf(spaceFilled)).isEmpty();
            assertThat(spaceFilled.description()).hasSize(60).isBlank();
            assertThat(spaceFilled.cardNumber()).hasSize(16).isBlank();
            assertThat(spaceFilled.amount()).hasSize(12).isBlank();
        }

        @Test
        @DisplayName("each component one character over its own width is reported exactly once and never "
                + "trimmed")
        void eachComponentOneCharacterOverItsWidthIsReportedExactlyOnce() {
            for (int index = 0; index < BOUNDED_COMPONENTS.size(); index++) {
                String name = BOUNDED_COMPONENTS.get(index);
                String overWidth = "X".repeat(BOUNDED_WIDTHS.get(index) + 1);
                TransactionAddRequest request = requestWithOnly(name, overWidth);

                Set<ConstraintViolation<TransactionAddRequest>> violations = violationsOf(request);

                assertThat(violations).as("component %s", name).hasSize(1);
                assertThat(violations.iterator().next().getPropertyPath())
                        .as("component %s", name)
                        .hasToString(name);
            }
        }

        @Test
        @DisplayName("a fully populated valid submission draws no violation at all")
        void aFullyPopulatedValidSubmissionDrawsNoViolation() {
            assertThat(violationsOf(populated())).isEmpty();
        }

        private static TransactionAddRequest requestWithOnly(String component, String value) {
            return new TransactionAddRequest(
                    "accountId".equals(component) ? value : null,
                    "cardNumber".equals(component) ? value : null,
                    "typeCode".equals(component) ? value : null,
                    "categoryCode".equals(component) ? value : null,
                    "transactionSource".equals(component) ? value : null,
                    "description".equals(component) ? value : null,
                    "amount".equals(component) ? value : null,
                    "originationDate".equals(component) ? value : null,
                    "processingDate".equals(component) ? value : null,
                    "merchantId".equals(component) ? value : null,
                    "merchantName".equals(component) ? value : null,
                    "merchantCity".equals(component) ? value : null,
                    "merchantZip".equals(component) ? value : null,
                    "confirm".equals(component) ? value : null,
                    null, null);
        }
    }

    @Nested
    @DisplayName("The echoed navigation state is cascaded into")
    class TheEchoedNavigationStateIsCascadedInto {

        @Test
        @DisplayName("the navigation component declares the cascade")
        void theNavigationComponentDeclaresTheCascade() throws NoSuchFieldException {
            Field field = TransactionAddRequest.class.getDeclaredField("navigationContext");

            assertThat(field.getAnnotation(Valid.class)).isNotNull();
        }

        @Test
        @DisplayName("a violation inside the echoed navigation state is reported under a nested property "
                + "path, which is the observable proof the cascade reaches it")
        void aViolationInsideTheEchoedStateIsReportedUnderANestedPath() {
            NavigationContext overWidth = new NavigationContext(null, "NINECHARS", null, null, null,
                    null, NavigationContext.ProgramContext.REENTER, null, null, null, null, null,
                    null, null, null, null);
            TransactionAddRequest request = new TransactionAddRequest(null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, overWidth);

            Set<ConstraintViolation<TransactionAddRequest>> violations = violationsOf(request);

            assertThat(violations).isNotEmpty();
            assertThat(violations)
                    .allSatisfy(violation -> assertThat(violation.getPropertyPath().toString())
                            .startsWith("navigationContext."));
        }

        @Test
        @DisplayName("an absent navigation state is not a violation, because a first entry carries none")
        void anAbsentNavigationStateIsNotAViolation() {
            TransactionAddRequest request = new TransactionAddRequest(ACCOUNT_ID, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null);

            assertThat(violationsOf(request)).isEmpty();
            assertThat(request.navigationContext()).isNull();
        }
    }

    @Nested
    @DisplayName("Nothing is transformed on the way through")
    class NothingIsTransformedOnTheWayThrough {

        @Test
        @DisplayName("the amount lexeme is carried exactly as typed, so a trailing zero is not "
                + "renormalised away and a zero-padded submission keeps its padding")
        void theAmountLexemeIsCarriedExactly() {
            TransactionAddRequest twoDecimals = new TransactionAddRequest(null, null, null, null,
                    null, null, "100.00", null, null, null, null, null, null, null, null, null);
            TransactionAddRequest zeroPadded = new TransactionAddRequest(null, null, null, null,
                    null, null, "000000100.00", null, null, null, null, null, null, null, null,
                    null);

            assertThat(violationsOf(twoDecimals)).isEmpty();
            assertThat(violationsOf(zeroPadded)).isEmpty();
            assertThat(twoDecimals.amount()).isEqualTo("100.00");
            assertThat(zeroPadded.amount()).isEqualTo("000000100.00").hasSize(12);
        }

        @Test
        @DisplayName("a negative amount transports intact whichever end its sign is typed at, because a "
                + "return is a negative amount and the add program decides what a sign means")
        void aNegativeAmountTransportsIntact() {
            TransactionAddRequest leadingSign = new TransactionAddRequest(null, null, null, null,
                    null, null, "-45.99", null, null, null, null, null, null, null, null, null);
            TransactionAddRequest trailingSign = new TransactionAddRequest(null, null, null, null,
                    null, null, "45.99-", null, null, null, null, null, null, null, null, null);

            assertThat(violationsOf(leadingSign)).isEmpty();
            assertThat(violationsOf(trailingSign)).isEmpty();
            assertThat(leadingSign.amount()).isEqualTo("-45.99");
            assertThat(trailingSign.amount())
                    .as("a trailing sign is a shape the add program reports on rather than one the "
                            + "boundary refuses, and a parse here would have refused it")
                    .isEqualTo("45.99-");
        }

        @Test
        @DisplayName("leading zeros survive in every code and identifier, because each mirrors a "
                + "fixed-width character field rather than a number")
        void leadingZerosSurvive() {
            TransactionAddRequest request = new TransactionAddRequest("00000000011", null, "01",
                    "0005", null, null, null, null, null, "000000001", null, null, "00000", null,
                    null, null);

            assertThat(request.accountId()).isEqualTo("00000000011");
            assertThat(request.typeCode()).isEqualTo("01");
            assertThat(request.categoryCode()).isEqualTo("0005");
            assertThat(request.merchantId()).isEqualTo("000000001");
            assertThat(request.merchantZip()).isEqualTo("00000");
        }

        @Test
        @DisplayName("case is never folded and an out-of-vocabulary source round-trips, because the add "
                + "program tests the value and the boundary does not")
        void caseIsNeverFoldedAndAnOutOfVocabularySourceRoundTrips() {
            TransactionAddRequest request = new TransactionAddRequest(null, null, null, null,
                    "unknown", null, null, null, null, null, null, null, null, "y", null, null);

            assertThat(violationsOf(request)).isEmpty();
            assertThat(request.transactionSource()).isEqualTo("unknown");
            assertThat(request.confirm()).isEqualTo("y");
        }

        @Test
        @DisplayName("a malformed date transports intact, because the shape check belongs to the add "
                + "program and its own message must be the one the operator sees")
        void aMalformedDateTransportsIntact() {
            TransactionAddRequest request = new TransactionAddRequest(null, null, null, null, null,
                    null, null, "2024-13-45", "NOT-A-DATE", null, null, null, null, null, null,
                    null);

            assertThat(violationsOf(request)).isEmpty();
            assertThat(request.originationDate()).isEqualTo("2024-13-45");
            assertThat(request.processingDate()).isEqualTo("NOT-A-DATE");
        }

        @Test
        @DisplayName("equality compares every component including the withheld ones, because an "
                + "in-memory comparison emits nothing")
        void equalityComparesEveryComponentIncludingTheWithheldOnes() {
            TransactionAddRequest first = populated();
            TransactionAddRequest same = populated();
            TransactionAddRequest differentCard = new TransactionAddRequest(ACCOUNT_ID,
                    "4532015112830367", "01", "0005", "POS TERM", DESCRIPTION, AMOUNT, "2024-01-15",
                    "2024-01-16", MERCHANT_ID, MERCHANT_NAME, MERCHANT_CITY, MERCHANT_ZIP, "Y",
                    KeyAction.ENTER, NavigationContext.empty().withReEntry());

            assertThat(first).isEqualTo(same).hasSameHashCodeAs(same);
            assertThat(first).isNotEqualTo(differentCard);
        }

        @Test
        @DisplayName("a document naming every component deserializes with each value intact, so the "
                + "replaced rendering did not disturb the wire contract")
        void aDocumentNamingEveryComponentDeserializesIntact() throws JsonProcessingException {
            String document = "{\"accountId\":\"" + ACCOUNT_ID + "\",\"cardNumber\":\"" + CARD_NUMBER
                    + "\",\"typeCode\":\"01\",\"categoryCode\":\"0005\","
                    + "\"transactionSource\":\"POS TERM\",\"description\":\"" + DESCRIPTION + "\","
                    + "\"amount\":\"" + AMOUNT + "\",\"merchantZip\":\"" + MERCHANT_ZIP + "\","
                    + "\"confirm\":\"Y\",\"keyAction\":\"ENTER\"}";

            TransactionAddRequest request =
                    moduleEquivalentMapper().readValue(document, TransactionAddRequest.class);

            assertThat(request.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(request.cardNumber()).isEqualTo(CARD_NUMBER);
            assertThat(request.amount()).isEqualTo(AMOUNT);
            assertThat(request.merchantZip()).isEqualTo(MERCHANT_ZIP);
            assertThat(request.keyAction()).isEqualTo(KeyAction.ENTER);
        }
    }
}
