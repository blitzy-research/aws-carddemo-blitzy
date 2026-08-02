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

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
 * Unit test for {@link CardUpdateRequest}, the inbound contract of legacy transaction {@code CCUP}.
 *
 * <h2>What is actually at risk in a request of this shape</h2>
 *
 * <p>Four properties matter, and none of them is "the accessor returns what the constructor stored",
 * though that is asserted too because ten same-typed components in one canonical constructor make a
 * transposition a real and silent defect.</p>
 *
 * <p>The first is <strong>byte-for-byte transport</strong>. The legacy screen fields are fixed-width and
 * space-significant, and the change comparison at {@code app/cbl/COCRDUPC.cbl} lines 1503 to 1508 makes
 * a case-only edit indistinguishable from no change, so this contract must not trim, pad, fold or
 * normalise anything on the way through.</p>
 *
 * <p>The second is <strong>diagnostic silence</strong>. The component set carries a primary account
 * number at full width beside the name it is embossed with, plus a live integrity credential. A record's
 * generated rendering would print all of them, so the rendering is asserted to disclose nothing - and
 * asserted negatively, by walking every component's value and requiring that none of them appears.</p>
 *
 * <p>The third is <strong>the concurrency proof</strong>. It is not a map field; it is the sealed
 * counterpart of the program work area the legacy transaction returns with the screen at line 550, and
 * it exists so that the two protected values and the fetched image reach the service from somewhere the
 * client cannot rewrite. What is asserted here is that the component exists, carries no constraint, and
 * round-trips untouched - never that it has any particular shape, because it is opaque to this
 * contract.</p>
 *
 * <p>The fourth is <strong>bound placement</strong>. Two components must carry no bound at all: the
 * expiry day, because {@code app/bms/COCRDUP.bms} line 142 declares it dark, field-set and protected so
 * the operator can never type it, and the proof, because a width rule would couple this contract to the
 * sealing envelope's encoding. Every other map component must carry exactly its measured map width and
 * nothing else, and the echoed navigation state must cascade.</p>
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No COBOL statement is transcribed.</p>
 */
@DisplayName("CardUpdateRequest - the CCUP inbound contract")
class CardUpdateRequestSecurityTest {

    /** The ten components in declaration order. A change here is a change to the REST contract. */
    private static final List<String> COMPONENTS_IN_ORDER = List.of(
            "accountId", "cardNumber", "embossedName", "activeStatus", "expiryMonth", "expiryYear",
            "expiryDay", "keyAction", "navigationContext", "concurrencyToken");

    /**
     * The measured map widths of the six bounded components, read from
     * {@code app/cpy-bms/COCRDUP.CPY} lines 60, 66, 72, 78, 84 and 90 rather than from the class under
     * test, so a width edited on the request alone fails here instead of agreeing with itself.
     */
    private static final List<Integer> BOUNDED_WIDTHS = List.of(11, 16, 50, 1, 2, 4);

    /** The bounded components, paired positionally with {@link #BOUNDED_WIDTHS}. */
    private static final List<String> BOUNDED_COMPONENTS = List.of(
            "accountId", "cardNumber", "embossedName", "activeStatus", "expiryMonth", "expiryYear");

    /** The components that must carry no constraint annotation of any kind. */
    private static final Set<String> UNCONSTRAINED_COMPONENTS =
            Set.of("expiryDay", "concurrencyToken");

    /** A primary account number at full width. A documentation value no issuer routes. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** The owning account identifier, protected on the mapset at line 84. */
    private static final String ACCOUNT_ID = "00000000011";

    /** An embossed name carrying an embedded space, which the legacy alphabetic rule admits. */
    private static final String EMBOSSED_NAME = "MARY ANN";

    /** The hidden protected carry-through day value. */
    private static final String EXPIRY_DAY = "31";

    /** A stand-in for a sealed proof. Deliberately not a realistic envelope: this type never reads it. */
    private static final String SEALED_TOKEN = "CCUP1-sealed-proof-stand-in";

    /** The fixed stand-in the rendering must emit in place of every component. */
    private static final String REDACTION_PLACEHOLDER_TEXT = "***REDACTED***";

    /**
     * Mirrors the four serialisation settings the module declares in {@code application.yml}, so a
     * payload asserted here is the payload the service actually receives.
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

    /** A realistic confirming submission, with every component populated. */
    private static CardUpdateRequest populated() {
        return new CardUpdateRequest(ACCOUNT_ID, CARD_NUMBER, EMBOSSED_NAME, "Y", "12", "2027",
                EXPIRY_DAY, KeyAction.PFK05, NavigationContext.empty().withReEntry(), SEALED_TOKEN);
    }

    private static Set<ConstraintViolation<CardUpdateRequest>> violationsOf(
            CardUpdateRequest request) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(request);
        }
    }

    @Nested
    @DisplayName("The component set is the map plus conversation state plus one proof")
    class TheComponentSetIsTheMapPlusStatePlusOneProof {

        @Test
        @DisplayName("ten components are declared in order, with the proof last")
        void tenComponentsAreDeclaredInOrderWithTheProofLast() {
            List<String> declared = Arrays.stream(CardUpdateRequest.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared).containsExactlyElementsOf(COMPONENTS_IN_ORDER).hasSize(10);
            assertThat(declared).endsWith("concurrencyToken");
        }

        @Test
        @DisplayName("no readable concurrency value is declared - no version, entity tag, timestamp or "
                + "fetched-image snapshot - because each is a value a client could assert for itself")
        void noReadableConcurrencyValueIsDeclared() {
            List<String> lowerCased = Arrays.stream(CardUpdateRequest.class.getRecordComponents())
                    .map(component -> component.getName().toLowerCase(Locale.ROOT))
                    .toList();

            assertThat(lowerCased)
                    .noneMatch(name -> name.contains("version"))
                    .noneMatch(name -> name.contains("etag"))
                    .noneMatch(name -> name.contains("timestamp"))
                    .noneMatch(name -> name.contains("beforeimage"))
                    .noneMatch(name -> name.contains("oldimage"));
        }

        @Test
        @DisplayName("no screen artefact is declared - no length, flag, attribute, colour, cursor or "
                + "map-coordinate component")
        void noScreenArtefactIsDeclared() {
            List<String> lowerCased = Arrays.stream(CardUpdateRequest.class.getRecordComponents())
                    .map(component -> component.getName().toLowerCase(Locale.ROOT))
                    .toList();

            assertThat(lowerCased)
                    .noneMatch(name -> name.endsWith("len"))
                    .noneMatch(name -> name.contains("attrib"))
                    .noneMatch(name -> name.contains("colour") || name.contains("color"))
                    .noneMatch(name -> name.contains("cursor"))
                    .noneMatch(name -> name.contains("flag"));
        }

        @Test
        @DisplayName("the eight character components are characters and never numbers, so a leading zero "
                + "cannot be lost")
        void theCharacterComponentsAreNeverNumbers() {
            for (RecordComponent component : CardUpdateRequest.class.getRecordComponents()) {
                if ("keyAction".equals(component.getName())) {
                    assertThat(component.getType()).isEqualTo(KeyAction.class);
                } else if ("navigationContext".equals(component.getName())) {
                    assertThat(component.getType()).isEqualTo(NavigationContext.class);
                } else {
                    assertThat(component.getType())
                            .as("component %s", component.getName())
                            .isEqualTo(String.class);
                }
            }
        }

        @Test
        @DisplayName("every accessor returns exactly what it was constructed with, so no two same-typed "
                + "components are transposed")
        void everyAccessorReturnsExactlyWhatItWasConstructedWith() {
            CardUpdateRequest request = populated();

            assertThat(request.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(request.cardNumber()).isEqualTo(CARD_NUMBER);
            assertThat(request.embossedName()).isEqualTo(EMBOSSED_NAME);
            assertThat(request.activeStatus()).isEqualTo("Y");
            assertThat(request.expiryMonth()).isEqualTo("12");
            assertThat(request.expiryYear()).isEqualTo("2027");
            assertThat(request.expiryDay()).isEqualTo(EXPIRY_DAY);
            assertThat(request.keyAction()).isEqualTo(KeyAction.PFK05);
            assertThat(request.navigationContext().programContext())
                    .isEqualTo(NavigationContext.ProgramContext.REENTER);
            assertThat(request.concurrencyToken()).isEqualTo(SEALED_TOKEN);
        }
    }

    @Nested
    @DisplayName("Bounds measure and never alter, and two components carry none at all")
    class BoundsMeasureAndNeverAlter {

        @Test
        @DisplayName("each bounded component carries exactly its measured map width and no other "
                + "constraint")
        void eachBoundedComponentCarriesItsMeasuredMapWidth() throws NoSuchFieldException {
            for (int index = 0; index < BOUNDED_COMPONENTS.size(); index++) {
                String name = BOUNDED_COMPONENTS.get(index);
                Field field = CardUpdateRequest.class.getDeclaredField(name);
                Size size = field.getAnnotation(Size.class);

                assertThat(size).as("component %s must carry a width bound", name).isNotNull();
                assertThat(size.max())
                        .as("component %s width", name)
                        .isEqualTo(BOUNDED_WIDTHS.get(index));
                assertThat(size.min()).as("component %s must declare no minimum", name).isZero();
                // The account identifier carries one further rule and one only: it must be absent on
                // the confirming turn, because the owning account is read back from the sealed proof
                // rather than from the body. An assertion of absence measures nothing and alters
                // nothing, so it does not weaken the "bounds only" property this test exists to hold.
                List<String> declared = Arrays.stream(field.getAnnotations())
                        .map(annotation -> annotation.annotationType().getSimpleName())
                        .sorted()
                        .toList();
                if ("accountId".equals(name)) {
                    assertThat(declared)
                            .as("component %s carries the width bound and the absence rule", name)
                            .containsExactly("Null", "Size");
                } else {
                    assertThat(declared)
                            .as("component %s must carry the width bound and nothing else", name)
                            .containsExactly("Size");
                }
            }
        }

        @Test
        @DisplayName("the expiry day and the proof carry no validation constraint whatsoever, because "
                + "one is a dark protected carry-through and the other is opaque")
        void theTwoUnconstrainedComponentsCarryNoConstraint() throws NoSuchFieldException {
            for (String name : UNCONSTRAINED_COMPONENTS) {
                Field field = CardUpdateRequest.class.getDeclaredField(name);

                assertThat(field.getAnnotation(Size.class))
                        .as("component %s must carry no width bound", name)
                        .isNull();
                assertThat(Arrays.stream(field.getAnnotations())
                        .map(annotation -> annotation.annotationType().getPackageName())
                        .distinct()
                        .toList())
                        .as("component %s must carry no validation constraint at all", name)
                        .doesNotContain("jakarta.validation.constraints");
            }

            // The day carries exactly one annotation, and it is a serialisation binding rather than a
            // rule: the component is published and never accepted, so a caller cannot restate the day
            // the card carries. The proof carries none at all.
            assertThat(Arrays.stream(CardUpdateRequest.class.getDeclaredField("expiryDay")
                            .getAnnotations())
                    .map(annotation -> annotation.annotationType().getSimpleName())
                    .toList())
                    .containsExactly("JsonProperty");
            assertThat(CardUpdateRequest.class.getDeclaredField("concurrencyToken").getAnnotations())
                    .isEmpty();
        }

        @Test
        @DisplayName("an absent proof is not a violation, because its absence is a conflict the service "
                + "reports rather than a binding failure the framework rejects")
        void anAbsentProofIsNotAViolation() {
            CardUpdateRequest withoutProof = new CardUpdateRequest(ACCOUNT_ID, CARD_NUMBER,
                    EMBOSSED_NAME, "Y", "12", "2027", EXPIRY_DAY, null, null, null);

            assertThat(violationsOf(withoutProof)).isEmpty();
            assertThat(withoutProof.concurrencyToken()).isNull();
        }

        @Test
        @DisplayName("a proof far longer than any screen field draws no violation and is carried whole")
        void anArbitrarilyLongProofIsCarriedWhole() {
            String longProof = "E".repeat(4096);
            CardUpdateRequest request = new CardUpdateRequest(ACCOUNT_ID, CARD_NUMBER, EMBOSSED_NAME,
                    "Y", "12", "2027", EXPIRY_DAY, null, null, longProof);

            assertThat(violationsOf(request)).isEmpty();
            assertThat(request.concurrencyToken()).isEqualTo(longProof).hasSize(4096);
        }

        @Test
        @DisplayName("an expiry day far past the declared width draws no violation, because the operator "
                + "can never have typed it")
        void anOverLongExpiryDayDrawsNoViolation() {
            CardUpdateRequest request = new CardUpdateRequest(ACCOUNT_ID, CARD_NUMBER, EMBOSSED_NAME,
                    "Y", "12", "2027", "3131313131", null, null, SEALED_TOKEN);

            assertThat(violationsOf(request)).isEmpty();
            assertThat(request.expiryDay()).isEqualTo("3131313131");
        }

        @Test
        @DisplayName("a bounded component one character too wide is reported while the value itself is "
                + "left exactly as it was supplied")
        void anOverWideBoundedComponentIsReportedWithoutBeingAltered() {
            CardUpdateRequest request = new CardUpdateRequest(ACCOUNT_ID, CARD_NUMBER, EMBOSSED_NAME,
                    "YN", "12", "2027", EXPIRY_DAY, null, null, SEALED_TOKEN);

            Set<ConstraintViolation<CardUpdateRequest>> violations = violationsOf(request);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString("activeStatus");
            assertThat(request.activeStatus()).isEqualTo("YN");
        }

        @Test
        @DisplayName("every component independently tolerates being absent, and a wholly empty "
                + "submission is a real state the legacy screen accepts")
        void aWhollyAbsentSubmissionDrawsNoViolation() {
            CardUpdateRequest empty = new CardUpdateRequest(null, null, null, null, null, null, null,
                    null, null, null);

            assertThat(violationsOf(empty)).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "\t"})
        @DisplayName("a one-character blank in every component is transported rather than rejected, "
                + "because blank is a state the legacy screen prompts against rather than refuses")
        void aBlankValueIsTransportedRatherThanRejected(String blank) {
            CardUpdateRequest request = new CardUpdateRequest(blank, blank, blank, blank, blank,
                    blank, blank, null, null, blank);

            assertThat(violationsOf(request)).isEmpty();
            assertThat(request.accountId()).isEqualTo(blank);
            assertThat(request.activeStatus()).isEqualTo(blank);
            assertThat(request.concurrencyToken()).isEqualTo(blank);
        }

        @Test
        @DisplayName("a submission space-filled to each field's own declared width draws no violation, "
                + "because that is exactly the shape a blank legacy screen transmits")
        void aSpaceFilledSubmissionAtEachDeclaredWidthDrawsNoViolation() {
            CardUpdateRequest spaceFilled = new CardUpdateRequest(" ".repeat(11), " ".repeat(16),
                    " ".repeat(50), " ", " ".repeat(2), " ".repeat(4), " ".repeat(2), null, null,
                    " ".repeat(64));

            assertThat(violationsOf(spaceFilled)).isEmpty();
            assertThat(spaceFilled.accountId()).hasSize(11).isBlank();
            assertThat(spaceFilled.cardNumber()).hasSize(16).isBlank();
            assertThat(spaceFilled.embossedName()).hasSize(50).isBlank();
            assertThat(spaceFilled.expiryYear()).hasSize(4).isBlank();
        }

        @Test
        @DisplayName("a blank wider than a component's own declared width is still reported, so the "
                + "bound measures blanks exactly as it measures anything else")
        void aBlankWiderThanTheDeclaredWidthIsStillReported() {
            CardUpdateRequest tooManySpaces = new CardUpdateRequest(ACCOUNT_ID, CARD_NUMBER,
                    EMBOSSED_NAME, "  ", "12", "2027", EXPIRY_DAY, null, null, SEALED_TOKEN);

            Set<ConstraintViolation<CardUpdateRequest>> violations = violationsOf(tooManySpaces);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString("activeStatus");
            assertThat(tooManySpaces.activeStatus()).isEqualTo("  ");
        }
    }

    @Nested
    @DisplayName("The echoed navigation state is cascaded into")
    class TheEchoedNavigationStateIsCascadedInto {

        @Test
        @DisplayName("the navigation component declares the cascade, so a client-echoed value is "
                + "validated at the boundary rather than in the service")
        void theNavigationComponentDeclaresTheCascade() throws NoSuchFieldException {
            Field field = CardUpdateRequest.class.getDeclaredField("navigationContext");

            assertThat(field.getAnnotation(Valid.class)).isNotNull();
        }

        @Test
        @DisplayName("a violation inside the echoed navigation state is reported under a nested property "
                + "path, which is the observable proof the cascade reaches it")
        void aViolationInsideTheEchoedStateIsReportedUnderANestedPath() {
            // An echoed program name one character wider than the eight-character legacy field it
            // names. Built through the canonical constructor because the type exposes no mutator for
            // it, which is itself the point: the value can only have come from the client.
            NavigationContext overWidth = new NavigationContext(null, "NINECHARS", null, null, null,
                    null, NavigationContext.ProgramContext.REENTER, null, null, null, null, null,
                    null, null, null, null);
            CardUpdateRequest request = new CardUpdateRequest(ACCOUNT_ID, CARD_NUMBER, EMBOSSED_NAME,
                    "Y", "12", "2027", EXPIRY_DAY, null, overWidth, SEALED_TOKEN);

            Set<ConstraintViolation<CardUpdateRequest>> violations = violationsOf(request);

            assertThat(violations).isNotEmpty();
            assertThat(violations)
                    .allSatisfy(violation -> assertThat(violation.getPropertyPath().toString())
                            .startsWith("navigationContext."));
        }

        @Test
        @DisplayName("an absent navigation state is not a violation, because a first entry carries none")
        void anAbsentNavigationStateIsNotAViolation() {
            CardUpdateRequest request = new CardUpdateRequest(ACCOUNT_ID, CARD_NUMBER, EMBOSSED_NAME,
                    "Y", "12", "2027", EXPIRY_DAY, null, null, SEALED_TOKEN);

            assertThat(violationsOf(request)).isEmpty();
            assertThat(request.navigationContext()).isNull();
        }

        @Test
        @DisplayName("a valid echoed navigation state passes the cascade untouched")
        void aValidEchoedNavigationStatePassesTheCascade() {
            assertThat(violationsOf(populated())).isEmpty();
        }
    }

    @Nested
    @DisplayName("The rendering discloses nothing while the wire carries everything")
    class TheRenderingDisclosesNothingWhileTheWireCarriesEverything {

        @Test
        @DisplayName("the rendering names the type and discloses no component value at all")
        void theRenderingNamesTheTypeAndDisclosesNoComponentValue() {
            CardUpdateRequest request = populated();

            String rendered = request.toString();

            assertThat(rendered)
                    .isEqualTo("CardUpdateRequest[" + REDACTION_PLACEHOLDER_TEXT + "]");
            assertThat(rendered).doesNotContain(CARD_NUMBER, ACCOUNT_ID, EMBOSSED_NAME, EXPIRY_DAY,
                    SEALED_TOKEN, "2027", "12", "Y");
        }

        @Test
        @DisplayName("no partial rendering of the card number survives: neither a leading nor a trailing "
                + "fragment, nor a length, appears in the rendered text")
        void noPartialRenderingOfTheCardNumberSurvives() {
            String rendered = populated().toString();

            assertThat(rendered).doesNotContain(CARD_NUMBER.substring(0, 6));
            assertThat(rendered).doesNotContain(CARD_NUMBER.substring(CARD_NUMBER.length() - 4));
            assertThat(rendered).doesNotContain(String.valueOf(CARD_NUMBER.length()));
        }

        @Test
        @DisplayName("the rendering is safe when every component is absent, so a diagnostic on an empty "
                + "submission cannot throw")
        void theRenderingIsSafeWhenEveryComponentIsAbsent() {
            CardUpdateRequest empty = new CardUpdateRequest(null, null, null, null, null, null, null,
                    null, null, null);

            assertThat(empty.toString())
                    .isEqualTo("CardUpdateRequest[" + REDACTION_PLACEHOLDER_TEXT + "]");
        }

        @Test
        @DisplayName("the wire payload carries every component in full, because the service needs the "
                + "key intact and a shortened key selects nothing")
        void theWirePayloadCarriesEveryComponentInFull() throws JsonProcessingException {
            ObjectMapper mapper = moduleEquivalentMapper();
            CardUpdateRequest request = populated();

            JsonNode payload = mapper.readTree(mapper.writeValueAsString(request));

            assertThat(payload.get("cardNumber").asText()).isEqualTo(CARD_NUMBER);
            assertThat(payload.get("accountId").asText()).isEqualTo(ACCOUNT_ID);
            assertThat(payload.get("embossedName").asText()).isEqualTo(EMBOSSED_NAME);
            assertThat(payload.get("expiryDay").asText()).isEqualTo(EXPIRY_DAY);
            assertThat(payload.get("concurrencyToken").asText()).isEqualTo(SEALED_TOKEN);
            assertThat(payload.toString()).doesNotContain(REDACTION_PLACEHOLDER_TEXT);
        }

        @Test
        @DisplayName("equality compares every component by value, so a differing proof yields a "
                + "differing request and nothing is redacted out of the comparison")
        void equalityComparesEveryComponentByValue() {
            CardUpdateRequest first = populated();
            CardUpdateRequest same = populated();
            CardUpdateRequest differentProof = new CardUpdateRequest(ACCOUNT_ID, CARD_NUMBER,
                    EMBOSSED_NAME, "Y", "12", "2027", EXPIRY_DAY, KeyAction.PFK05,
                    NavigationContext.empty().withReEntry(), SEALED_TOKEN + "-other");

            assertThat(first).isEqualTo(same).hasSameHashCodeAs(same);
            assertThat(first).isNotEqualTo(differentProof);
        }
    }

    @Nested
    @DisplayName("Nothing is transformed on the way through")
    class NothingIsTransformedOnTheWayThrough {

        @Test
        @DisplayName("a leading and a trailing space are contractual data and survive both construction "
                + "and a wire round trip")
        void surroundingSpacesSurviveConstructionAndTheWireRoundTrip()
                throws JsonProcessingException {
            String spaced = " MARY ANN  ";
            CardUpdateRequest request = new CardUpdateRequest(ACCOUNT_ID, CARD_NUMBER, spaced, "Y",
                    "12", "2027", EXPIRY_DAY, null, null, SEALED_TOKEN);

            assertThat(request.embossedName()).isEqualTo(spaced).hasSize(11);

            ObjectMapper mapper = moduleEquivalentMapper();
            CardUpdateRequest revived = mapper.readValue(mapper.writeValueAsString(request),
                    CardUpdateRequest.class);

            assertThat(revived.embossedName()).isEqualTo(spaced);
            assertThat(revived.expiryDay())
                    .as("the publish-only day does not travel inbound, which is the binding rather "
                            + "than a transformation of the value")
                    .isNull();
            assertThat(revived).isEqualTo(withoutTheEchoedDay(request));
        }

        @Test
        @DisplayName("letter case is never folded here, because the service performs the legacy table "
                + "fold and a case-only edit must stay visible to it")
        void letterCaseIsNeverFoldedHere() {
            CardUpdateRequest lowerCased = new CardUpdateRequest(ACCOUNT_ID, CARD_NUMBER, "mary ann",
                    "y", "12", "2027", EXPIRY_DAY, null, null, SEALED_TOKEN);

            assertThat(lowerCased.embossedName()).isEqualTo("mary ann");
            assertThat(lowerCased.activeStatus()).isEqualTo("y");
        }

        @Test
        @DisplayName("a leading zero is never collapsed, so an identifier stays the identifier it was")
        void aLeadingZeroIsNeverCollapsed() {
            CardUpdateRequest request = new CardUpdateRequest("00000000001",
                    "0000000000000001", EMBOSSED_NAME, "Y", "01", "2027", "01", null, null,
                    SEALED_TOKEN);

            assertThat(request.accountId()).isEqualTo("00000000001");
            assertThat(request.cardNumber()).isEqualTo("0000000000000001");
            assertThat(request.expiryMonth()).isEqualTo("01");
            assertThat(request.expiryDay()).isEqualTo("01");
        }

        @Test
        @DisplayName("every component binds under its own property name, with no renaming and no "
                + "serialisation annotation of any kind on the type")
        void everyComponentBindsUnderItsOwnPropertyName() throws JsonProcessingException {
            ObjectMapper mapper = moduleEquivalentMapper();

            JsonNode payload = mapper.readTree(mapper.writeValueAsString(populated()));

            for (String name : COMPONENTS_IN_ORDER) {
                assertThat(payload.has(name)).as("property %s must be present", name).isTrue();
            }
            assertThat(Arrays.stream(CardUpdateRequest.class.getAnnotations())
                    .map(annotation -> annotation.annotationType().getSimpleName()).toList())
                    .isEmpty();
        }

        @Test
        @DisplayName("a request survives two consecutive round trips unchanged for every component a "
                + "caller may supply, and the one it may not is dropped on the way in rather than "
                + "altered")
        void aRequestSurvivesTwoConsecutiveRoundTripsUnchanged() throws JsonProcessingException {
            ObjectMapper mapper = moduleEquivalentMapper();
            CardUpdateRequest original = populated();

            CardUpdateRequest once = mapper.readValue(mapper.writeValueAsString(original),
                    CardUpdateRequest.class);
            CardUpdateRequest twice = mapper.readValue(mapper.writeValueAsString(once),
                    CardUpdateRequest.class);

            // The expiry day is published and never accepted, so a document naming it binds nothing.
            // That is the point of the binding rather than a loss: the day the card carries is the one
            // the sealed proof returns, so a caller cannot restate it and cannot alter it either.
            assertThat(once.expiryDay()).isNull();
            assertThat(twice.expiryDay()).isNull();
            assertThat(once).isEqualTo(withoutTheEchoedDay(original));
            assertThat(twice).isEqualTo(withoutTheEchoedDay(original));
            assertThat(mapper.writeValueAsString(twice))
                    .as("every component a caller may supply survives both trips byte for byte")
                    .isEqualTo(mapper.writeValueAsString(withoutTheEchoedDay(original)));
        }

        /**
         * Returns the same request with the publish-only expiry day cleared.
         *
         * <p>Needed because the day is a dark echo: it is rendered onto the screen and returned by the
         * concurrency proof, and it is deliberately not bindable from a request document. A round-trip
         * comparison therefore has to compare against the request as the wire can express it, not
         * against a request carrying a component the wire cannot carry inbound.</p>
         *
         * @param request the request to strip
         * @return the same request with {@code expiryDay} absent
         */
        private static CardUpdateRequest withoutTheEchoedDay(CardUpdateRequest request) {
            return new CardUpdateRequest(request.accountId(), request.cardNumber(),
                    request.embossedName(), request.activeStatus(), request.expiryMonth(),
                    request.expiryYear(), null, request.keyAction(), request.navigationContext(),
                    request.concurrencyToken());
        }
    }
}
