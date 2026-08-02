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
 * Unit test for {@link BillPaymentRequest}, the inbound contract of the {@code CB00} bill-payment screen.
 *
 * <h2>What is actually at risk in a request of this shape</h2>
 *
 * <p>Only four components cross this boundary, which makes it the smallest request in the package and the
 * easiest to under-protect. Both carried values are regulated. The account identifier keys a balance-
 * clearing payment, and the confirmation answer is the single character that decides whether that payment
 * is applied - so a rendering carrying either is a rendering that describes a financial instruction.</p>
 *
 * <p>An earlier revision of the contract asserted that neither carried value was sensitive and that the
 * generated rendering could therefore stand. That assessment was wrong about the identifier and, more
 * tellingly, disagreed with the outbound contract for this very screen, which already withholds both.
 * These tests hold the corrected position: both are withheld, and the confirmation answer specifically,
 * because a rejection must never echo the value it rejected. That is the same rule the response side
 * records, and the two halves of one screen now agree.</p>
 *
 * <p>The remaining risk is the echoed navigation state, which declares widths of its own that nothing
 * evaluates unless this contract cascades into it. The cascade is asserted by observing a nested property
 * path rather than by reading the annotation alone, because an annotation present but not honoured would
 * satisfy the weaker check.</p>
 *
 * <p>Everything else is deliberately absent and asserted absent: no balance, no payment amount and no
 * transaction identifier is accepted, because the payment amount is the stored balance and the new key is
 * derived from the highest existing one. Accepting any of the three from a client would let a caller
 * choose how much to clear or which key to occupy.</p>
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No COBOL statement is transcribed.</p>
 */
@DisplayName("BillPaymentRequest - the CB00 inbound contract")
class BillPaymentRequestSecurityTest {

    /** The four components in declaration order. A change here is a change to the REST contract. */
    private static final List<String> COMPONENTS_IN_ORDER =
            List.of("accountId", "confirm", "keyAction", "navigationContext");

    /** The fixed stand-in the rendering must emit in place of a withheld value. */
    private static final String REDACTION_PLACEHOLDER_TEXT = "***REDACTED***";

    /**
     * An eleven-character account identifier, deliberately every-digit-distinct rather than zero padded
     * so the fragment scan measures disclosure of this value rather than an accidental run of zeros.
     */
    private static final String ACCOUNT_ID = "78412590063";

    /** A realistic confirmed payment submission. */
    private static BillPaymentRequest populated() {
        return new BillPaymentRequest(ACCOUNT_ID, "Y", KeyAction.ENTER,
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

    private static Set<ConstraintViolation<BillPaymentRequest>> violationsOf(
            BillPaymentRequest request) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(request);
        }
    }

    @Nested
    @DisplayName("The component set is one key, one answer and conversation state")
    class TheComponentSetIsOneKeyOneAnswerAndState {

        @Test
        @DisplayName("four components are declared in order")
        void fourComponentsAreDeclaredInOrder() {
            List<String> declared = Arrays.stream(BillPaymentRequest.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared).containsExactlyElementsOf(COMPONENTS_IN_ORDER).hasSize(4);
        }

        @Test
        @DisplayName("no amount or balance is declared, because the payment clears the stored balance "
                + "and a client-supplied figure would let a caller choose how much to clear")
        void noAmountOrBalanceIsDeclared() {
            List<String> lowerCased = Arrays.stream(BillPaymentRequest.class.getRecordComponents())
                    .map(component -> component.getName().toLowerCase(Locale.ROOT))
                    .toList();

            assertThat(lowerCased)
                    .noneMatch(name -> name.contains("amount"))
                    .noneMatch(name -> name.contains("balance"))
                    .noneMatch(name -> name.contains("payment"));
        }

        @Test
        @DisplayName("no transaction identifier is declared, because the new key is derived from the "
                + "highest existing one rather than accepted from a client")
        void noTransactionIdentifierIsDeclared() {
            List<String> lowerCased = Arrays.stream(BillPaymentRequest.class.getRecordComponents())
                    .map(component -> component.getName().toLowerCase(Locale.ROOT))
                    .toList();

            assertThat(lowerCased)
                    .noneMatch(name -> name.contains("transaction"))
                    .noneMatch(name -> name.contains("sequence"));
        }

        @Test
        @DisplayName("both carried values are characters, so a leading zero in the identifier is not "
                + "lost and the answer stays one character rather than becoming a two-state flag")
        void bothCarriedValuesAreCharacters() {
            for (RecordComponent component : BillPaymentRequest.class.getRecordComponents()) {
                switch (component.getName()) {
                    case "keyAction" -> assertThat(component.getType()).isEqualTo(KeyAction.class);
                    case "navigationContext" ->
                            assertThat(component.getType()).isEqualTo(NavigationContext.class);
                    default -> assertThat(component.getType())
                            .as("component %s", component.getName())
                            .isEqualTo(String.class);
                }
            }
        }

        @Test
        @DisplayName("every accessor returns exactly what it was constructed with")
        void everyAccessorReturnsExactlyWhatItWasConstructedWith() {
            BillPaymentRequest request = populated();

            assertThat(request.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(request.confirm()).isEqualTo("Y");
            assertThat(request.keyAction()).isEqualTo(KeyAction.ENTER);
            assertThat(request.navigationContext().programContext())
                    .isEqualTo(NavigationContext.ProgramContext.REENTER);
        }
    }

    @Nested
    @DisplayName("The rendering discloses nothing while the wire carries everything")
    class TheRenderingDisclosesNothingWhileTheWireCarriesEverything {

        @Test
        @DisplayName("the rendering is exactly the two retained values plus two placeholders")
        void theRenderingIsExactlyTheTwoRetainedValuesPlusTwoPlaceholders() {
            assertThat(populated()).hasToString("BillPaymentRequest["
                    + "accountId=" + REDACTION_PLACEHOLDER_TEXT
                    + ", confirm=" + REDACTION_PLACEHOLDER_TEXT
                    + ", keyAction=" + KeyAction.ENTER
                    + ", navigationContext=" + NavigationContext.empty().withReEntry()
                    + "]");
        }

        @Test
        @DisplayName("the account identifier appears nowhere in the rendering")
        void theAccountIdentifierAppearsNowhere() {
            assertThat(populated().toString()).doesNotContain(ACCOUNT_ID);
        }

        @Test
        @DisplayName("no six-character run of the account identifier survives")
        void noSixCharacterRunOfTheAccountIdentifierSurvives() {
            String rendered = populated().toString();

            IntStream.rangeClosed(0, ACCOUNT_ID.length() - 6)
                    .mapToObj(start -> ACCOUNT_ID.substring(start, start + 6))
                    .forEach(fragment -> assertThat(rendered)
                            .as("fragment %s must not appear", fragment)
                            .doesNotContain(fragment));
        }

        @ParameterizedTest
        @ValueSource(strings = {"Y", "N", "y", "n", " ", "Q"})
        @DisplayName("the confirmation answer is withheld whatever it was, because a rejection must "
                + "never echo the value it rejected")
        void theConfirmationAnswerIsWithheldWhateverItWas(String answer) {
            BillPaymentRequest request = new BillPaymentRequest(ACCOUNT_ID, answer, KeyAction.ENTER,
                    null);

            assertThat(request.toString()).contains("confirm=" + REDACTION_PLACEHOLDER_TEXT);
            assertThat(request.toString()).doesNotContain("confirm=" + answer);
        }

        @Test
        @DisplayName("two submissions with opposite answers on the same account render identically, so "
                + "the rendering cannot be read to recover the instruction")
        void twoSubmissionsWithOppositeAnswersRenderIdentically() {
            BillPaymentRequest accepted = new BillPaymentRequest(ACCOUNT_ID, "Y", KeyAction.ENTER,
                    null);
            BillPaymentRequest declined = new BillPaymentRequest("00000000011", "N", KeyAction.ENTER,
                    null);

            assertThat(accepted).hasToString(declined.toString());
        }

        @Test
        @DisplayName("an absent withheld value still renders as the placeholder, so absence and presence "
                + "are indistinguishable in a diagnostic")
        void anAbsentWithheldValueStillRendersAsThePlaceholder() {
            BillPaymentRequest sparse = new BillPaymentRequest(null, null, null, null);

            assertThat(sparse.toString())
                    .contains("accountId=" + REDACTION_PLACEHOLDER_TEXT)
                    .contains("confirm=" + REDACTION_PLACEHOLDER_TEXT)
                    .doesNotContain("accountId=null")
                    .doesNotContain("confirm=null");
        }

        @Test
        @DisplayName("the attention key is retained, because it is the only value that tells a reader "
                + "which branch the screen took and it names no account and no instruction")
        void theAttentionKeyIsRetained() {
            assertThat(populated().toString()).contains("keyAction=" + KeyAction.ENTER);
            assertThat(new BillPaymentRequest(null, null, KeyAction.PFK03, null).toString())
                    .contains("keyAction=" + KeyAction.PFK03);
        }

        @Test
        @DisplayName("the nested navigation state redacts its own identifying values, so printing it by "
                + "delegation discloses nothing further")
        void theNestedNavigationStateRedactsItsOwn() {
            NavigationContext identifying = new NavigationContext(null, null, null, null, "USER0001",
                    "U", NavigationContext.ProgramContext.REENTER, "000000007", "GRACE", null,
                    "HOPPER", ACCOUNT_ID, "Y", "4532015112830366", null, null);
            BillPaymentRequest request = new BillPaymentRequest(ACCOUNT_ID, "Y", KeyAction.ENTER,
                    identifying);

            String rendered = request.toString();

            assertThat(rendered)
                    .doesNotContain(ACCOUNT_ID)
                    .doesNotContain("4532015112830366")
                    .doesNotContain("GRACE")
                    .doesNotContain("HOPPER");
        }

        @Test
        @DisplayName("the rendering is safe when every component is absent")
        void theRenderingIsSafeWhenEveryComponentIsAbsent() {
            BillPaymentRequest empty = new BillPaymentRequest(null, null, null, null);

            assertThatCode(empty::toString).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the wire still carries both withheld values, because the rendering is a diagnostic "
                + "channel and not the transport")
        void theWireStillCarriesBothWithheldValues() throws JsonProcessingException {
            ObjectMapper mapper = moduleEquivalentMapper();

            JsonNode payload = mapper.readTree(mapper.writeValueAsString(populated()));

            assertThat(payload.get("accountId").asText()).isEqualTo(ACCOUNT_ID);
            assertThat(payload.get("confirm").asText()).isEqualTo("Y");
        }
    }

    @Nested
    @DisplayName("Bounds measure and never alter")
    class BoundsMeasureAndNeverAlter {

        @Test
        @DisplayName("the identifier carries the account key's own width and nothing else")
        void theIdentifierCarriesTheAccountKeysOwnWidth() throws NoSuchFieldException {
            Field field = BillPaymentRequest.class.getDeclaredField("accountId");

            Size size = field.getAnnotation(Size.class);

            assertThat(size).isNotNull();
            assertThat(size.max()).isEqualTo(BillPaymentRequest.ACCOUNT_ID_LENGTH).isEqualTo(11);
            assertThat(size.min()).isZero();
            assertThat(field.getAnnotations()).hasSize(1);
        }

        @Test
        @DisplayName("the answer carries a one-character width, because it is one screen character and "
                + "not a boolean")
        void theAnswerCarriesAOneCharacterWidth() throws NoSuchFieldException {
            Field field = BillPaymentRequest.class.getDeclaredField("confirm");

            Size size = field.getAnnotation(Size.class);

            assertThat(size).isNotNull();
            assertThat(size.max()).isEqualTo(BillPaymentRequest.CONFIRM_LENGTH).isEqualTo(1);
            assertThat(field.getAnnotations()).hasSize(1);
        }

        @Test
        @DisplayName("no presence, pattern, digit or assertion constraint appears on any component, "
                + "because the payment program owns its own ordered checks")
        void noPresenceOrFormatConstraintAppears() throws NoSuchFieldException {
            for (String name : COMPONENTS_IN_ORDER) {
                Field field = BillPaymentRequest.class.getDeclaredField(name);

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
        @DisplayName("a blank in both carried components transports rather than being rejected, because "
                + "the payment screen prompts against a blank rather than refusing it")
        void aBlankValueTransportsRatherThanBeingRejected(String blank) {
            BillPaymentRequest request = new BillPaymentRequest(blank, blank, null, null);

            assertThat(violationsOf(request)).isEmpty();
            assertThat(request.accountId()).isEqualTo(blank);
            assertThat(request.confirm()).isEqualTo(blank);
        }

        @Test
        @DisplayName("a submission space-filled to each declared width draws no violation")
        void aSpaceFilledSubmissionDrawsNoViolation() {
            BillPaymentRequest spaceFilled = new BillPaymentRequest(" ".repeat(11), " ", null, null);

            assertThat(violationsOf(spaceFilled)).isEmpty();
            assertThat(spaceFilled.accountId()).hasSize(11).isBlank();
        }

        @Test
        @DisplayName("an identifier one character over its width is reported and never trimmed")
        void anIdentifierOneCharacterOverItsWidthIsReported() {
            BillPaymentRequest tooWide = new BillPaymentRequest("1".repeat(12), null, null, null);

            Set<ConstraintViolation<BillPaymentRequest>> violations = violationsOf(tooWide);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString("accountId");
            assertThat(tooWide.accountId()).hasSize(12);
        }

        @Test
        @DisplayName("an answer of two characters is reported, because the screen field is one character "
                + "wide and a second character is data the screen could not have produced")
        void anAnswerOfTwoCharactersIsReported() {
            BillPaymentRequest tooWide = new BillPaymentRequest(null, "YN", null, null);

            Set<ConstraintViolation<BillPaymentRequest>> violations = violationsOf(tooWide);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString("confirm");
        }

        @Test
        @DisplayName("a fully populated valid submission draws no violation at all")
        void aFullyPopulatedValidSubmissionDrawsNoViolation() {
            assertThat(violationsOf(populated())).isEmpty();
        }
    }

    @Nested
    @DisplayName("The echoed navigation state is cascaded into")
    class TheEchoedNavigationStateIsCascadedInto {

        @Test
        @DisplayName("the navigation component declares the cascade")
        void theNavigationComponentDeclaresTheCascade() throws NoSuchFieldException {
            Field field = BillPaymentRequest.class.getDeclaredField("navigationContext");

            assertThat(field.getAnnotation(Valid.class)).isNotNull();
        }

        @Test
        @DisplayName("a violation inside the echoed navigation state is reported under a nested property "
                + "path, which is the observable proof the cascade reaches it")
        void aViolationInsideTheEchoedStateIsReportedUnderANestedPath() {
            NavigationContext overWidth = new NavigationContext(null, "NINECHARS", null, null, null,
                    null, NavigationContext.ProgramContext.REENTER, null, null, null, null, null,
                    null, null, null, null);
            BillPaymentRequest request = new BillPaymentRequest(ACCOUNT_ID, "Y", KeyAction.ENTER,
                    overWidth);

            Set<ConstraintViolation<BillPaymentRequest>> violations = violationsOf(request);

            assertThat(violations).isNotEmpty();
            assertThat(violations)
                    .allSatisfy(violation -> assertThat(violation.getPropertyPath().toString())
                            .startsWith("navigationContext."));
        }

        @Test
        @DisplayName("an over-width echoed card number inside the navigation state is reported too, so "
                + "the cascade covers every nested component rather than the first one")
        void anOverWidthEchoedCardNumberIsReportedToo() {
            NavigationContext overWidth = new NavigationContext(null, null, null, null, null, null,
                    NavigationContext.ProgramContext.REENTER, null, null, null, null, null, null,
                    "1".repeat(NavigationContext.CARD_NUMBER_LENGTH + 1), null, null);
            BillPaymentRequest request = new BillPaymentRequest(ACCOUNT_ID, "Y", null, overWidth);

            Set<ConstraintViolation<BillPaymentRequest>> violations = violationsOf(request);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath())
                    .hasToString("navigationContext.cardNumber");
        }

        @Test
        @DisplayName("an absent navigation state is not a violation, because a first entry carries none")
        void anAbsentNavigationStateIsNotAViolation() {
            BillPaymentRequest request = new BillPaymentRequest(ACCOUNT_ID, "Y", KeyAction.ENTER,
                    null);

            assertThat(violationsOf(request)).isEmpty();
            assertThat(request.navigationContext()).isNull();
        }
    }

    @Nested
    @DisplayName("Nothing is transformed on the way through")
    class NothingIsTransformedOnTheWayThrough {

        @Test
        @DisplayName("a leading-zero identifier survives, because it mirrors a fixed-width character "
                + "field rather than a number")
        void aLeadingZeroIdentifierSurvives() {
            BillPaymentRequest request = new BillPaymentRequest("00000000011", null, null, null);

            assertThat(request.accountId()).isEqualTo("00000000011");
        }

        @Test
        @DisplayName("a lower-case answer is not folded, because accept, silent reset and quoted-back "
                + "are three distinct outcomes the program distinguishes by the character itself")
        void aLowerCaseAnswerIsNotFolded() {
            BillPaymentRequest request = new BillPaymentRequest(ACCOUNT_ID, "y", null, null);

            assertThat(violationsOf(request)).isEmpty();
            assertThat(request.confirm()).isEqualTo("y");
        }

        @Test
        @DisplayName("an out-of-vocabulary answer round-trips, because the program quotes an unexpected "
                + "character back rather than the boundary refusing the submission")
        void anOutOfVocabularyAnswerRoundTrips() {
            BillPaymentRequest request = new BillPaymentRequest(ACCOUNT_ID, "Q", null, null);

            assertThat(violationsOf(request)).isEmpty();
            assertThat(request.confirm()).isEqualTo("Q");
        }

        @Test
        @DisplayName("equality compares every component including the withheld ones, because an "
                + "in-memory comparison emits nothing")
        void equalityComparesEveryComponentIncludingTheWithheldOnes() {
            BillPaymentRequest first = populated();
            BillPaymentRequest same = populated();
            BillPaymentRequest declined = new BillPaymentRequest(ACCOUNT_ID, "N", KeyAction.ENTER,
                    NavigationContext.empty().withReEntry());

            assertThat(first).isEqualTo(same).hasSameHashCodeAs(same);
            assertThat(first).isNotEqualTo(declined);
        }

        @Test
        @DisplayName("a document naming every component deserializes with each value intact, so the "
                + "replaced rendering did not disturb the wire contract")
        void aDocumentNamingEveryComponentDeserializesIntact() throws JsonProcessingException {
            String document = "{\"accountId\":\"" + ACCOUNT_ID + "\",\"confirm\":\"Y\","
                    + "\"keyAction\":\"PFK04\"}";

            BillPaymentRequest request =
                    moduleEquivalentMapper().readValue(document, BillPaymentRequest.class);

            assertThat(request.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(request.confirm()).isEqualTo("Y");
            assertThat(request.keyAction()).isEqualTo(KeyAction.PFK04);
        }
    }
}
