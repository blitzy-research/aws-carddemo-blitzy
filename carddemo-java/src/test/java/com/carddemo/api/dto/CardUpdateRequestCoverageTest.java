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
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.carddemo.domain.enums.KeyAction;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
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
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CardUpdateRequest}, the request body of legacy transaction {@code CCUP}.
 *
 * <h2>What is under test</h2>
 *
 * <p>The transport contract of the card-update submission: the ten components, the seven that mirror
 * map items and the three that do not, the six declared widths, the one component that deliberately
 * carries no validation constraint at all, the wire form under the module's declared serialisation
 * settings, and the value fidelity the update path depends on. The rules this type delegates - the yes-or-no status check, the inclusive month and year
 * ranges, the alphabetic-with-spaces name rule and the case fold applied to the embossed name - are
 * exercised where they live, in the card-update service, and are deliberately not re-asserted here.
 *
 * <h2>Where the expectations come from</h2>
 *
 * <p>Every width below is restated as a literal in this file from the symbolic map
 * {@code app/cpy-bms/COCRDUP.CPY} and the mapset {@code app/bms/COCRDUP.bms}: 11 for the account
 * identifier, 16 for the card number, 50 for the embossed name, 1 for the active status, 2 for the
 * expiry month and 4 for the expiry year. They are not read out of the class under test, because a
 * test that sources its expectation from the type it is testing asserts only self-consistency.
 *
 * <h2>The seventh component is the interesting one</h2>
 *
 * <p>The expiry-day item is dark on the mapset: field-set, protected and hidden, a carry-through
 * value that is never operator input and is never validated. The record therefore declares
 * <strong>no validation constraint on it whatsoever</strong>, not even a width bound, and the source
 * carries an explicit instruction not to add one. It does carry one serialization directive, which is
 * a different kind of statement: the value is written outbound so the echo survives and discarded
 * inbound so the wire cannot reach the stored expiry date. Three tests below pin all of that: one
 * asserts no validation constraint is declared, one asserts a value far wider than the map's two
 * characters produces no violation, and one asserts a submitted value is discarded while a sibling
 * expiry part still binds. Together they make an addition to that component a test failure rather
 * than a silent behaviour change that would start rejecting a carry-through the legacy screen
 * transmits.
 *
 * <h2>How the wire form is observed, and what that does and does not prove</h2>
 *
 * <p>A pure unit test: no context, no connection, no container. Payloads are produced by
 * {@link JsonContractSupport#declaredSettingsMapper()}, the single place in this package where the
 * module's four declared serialisation settings are written by hand. That evidences the shape this
 * type takes under those settings and makes no claim about the mapper a deployed instance holds;
 * {@link ApplicationJsonContractTest} is the in-boundary evidence for the deployed object and
 * exercises this type through it.
 *
 * <h2>Diagnostic posture</h2>
 *
 * <p>This type replaces the record contract's generated rendering with a single fixed marker for the
 * whole component set. That is the strongest available posture and it is taken because framework
 * binding can stringify a request outside this module's control: every component here is either
 * cardholder data, a record key, or state that names the one card being updated, so there is no
 * subset worth retaining. Tests below pin the marker, pin that no supplied value survives it, and
 * pin that nesting a populated navigation state discloses nothing either.
 *
 * <p>Provenance: checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is reproduced.
 */
@DisplayName("CardUpdateRequest :: card-update request contract of legacy transaction CCUP")
class CardUpdateRequestCoverageTest {

    /**
     * The ten components in the order the symbolic map declares the items they mirror, followed by
     * the three transport components. A change to this list is a change to the REST contract.
     *
     * <p>The last of the three mirrors no map item at all: it is the sealed counterpart of the work
     * area the legacy program carries across a pseudo-conversational turn, and it is what lets a
     * concurrent update be detected rather than silently overwritten.</p>
     */
    private static final List<String> EXPECTED_COMPONENTS = List.of(
            "accountId", "cardNumber", "embossedName", "activeStatus",
            "expiryMonth", "expiryYear", "expiryDay", "keyAction", "navigationContext");

    /**
     * The declared width of each bounded component, restated from the symbolic map. The expiry day
     * is absent from this table on purpose: it carries no bound, and a table entry for it would
     * imply one exists.
     */
    private static final Map<String, Integer> EXPECTED_WIDTHS = Map.of(
            "accountId", 11,
            "cardNumber", 16,
            "embossedName", 50,
            "activeStatus", 1,
            "expiryMonth", 2,
            "expiryYear", 4);

    /** The one component that must carry no constraint of any kind. */
    private static final String UNCONSTRAINED_COMPONENT = "expiryDay";

    /** An account identifier at exactly the declared width. */
    private static final String ACCOUNT_ID = "00000000011";

    /** A card number at exactly the declared width. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** An embossed name containing an embedded space, which the alphabetic rule admits. */
    private static final String EMBOSSED_NAME = "MARY ANN";

    /** The fixed marker the rendering emits in place of the whole component set. */
    private static final String EXPECTED_PLACEHOLDER = "***REDACTED***";

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
     * Builds a request whose every bounded component sits exactly at its declared width.
     *
     * @return a request at the declared widths, with no attention key and no navigation state
     */
    private static CardUpdateRequest atDeclaredWidths() {
        return new CardUpdateRequest(
                ACCOUNT_ID,
                CARD_NUMBER,
                "A".repeat(EXPECTED_WIDTHS.get("embossedName")),
                "Y",
                "12",
                "2099",
                "31",
                null,
                null);
    }

    /**
     * Builds a request carrying only the named component, so a violation can be attributed.
     *
     * @param component the component to populate
     * @param value the value to place in it
     * @return a request carrying that one value
     */
    private static CardUpdateRequest carrying(String component, String value) {
        return new CardUpdateRequest(
                "accountId".equals(component) ? value : null,
                "cardNumber".equals(component) ? value : null,
                "embossedName".equals(component) ? value : null,
                "activeStatus".equals(component) ? value : null,
                "expiryMonth".equals(component) ? value : null,
                "expiryYear".equals(component) ? value : null,
                "expiryDay".equals(component) ? value : null,
                null,
                null);
    }

    /**
     * Serialises a request and parses the result back into a tree.
     *
     * @param request the request to render
     * @return the parsed payload
     * @throws JsonProcessingException if rendering or parsing fails
     */
    private static JsonNode payloadOf(CardUpdateRequest request) throws JsonProcessingException {
        ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();
        return mapper.readTree(mapper.writeValueAsString(request));
    }

    @Nested
    @DisplayName("Declared contract")
    class DeclaredContract {

        @Test
        @DisplayName("the ten components are declared in map order, the expiry month before the "
                + "expiry year as this map declares them")
        void componentsAreDeclaredInMapOrder() {
            List<String> declared = Arrays.stream(CardUpdateRequest.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared).containsExactlyElementsOf(EXPECTED_COMPONENTS);
        }

        @ParameterizedTest(name = "{0} is bounded at {1}")
        @CsvSource({
            "accountId,11",
            "cardNumber,16",
            "embossedName,50",
            "activeStatus,1",
            "expiryMonth,2",
            "expiryYear,4",
        })
        @DisplayName("each bounded component declares exactly the width the symbolic map declares")
        void eachBoundedComponentDeclaresItsMapWidth(String component, int expectedWidth)
                throws NoSuchFieldException {
            Size bound = CardUpdateRequest.class.getDeclaredField(component)
                    .getAnnotation(Size.class);

            assertThat(bound)
                    .as("%s mirrors a fixed-width map item, so it carries a width bound", component)
                    .isNotNull();
            assertThat(bound.max()).isEqualTo(expectedWidth);
            assertThat(bound.min())
                    .as("a bound that also demanded a minimum would reject the blank value the "
                            + "terminal transmits for an untouched item")
                    .isZero();
        }

        @Test
        @DisplayName("the hidden expiry-day carry-through declares no annotation at all - not a "
                + "width bound and not a serialization directive either")
        void theHiddenExpiryDayDeclaresNoConstraint() throws NoSuchFieldException {
            Annotation[] declared = CardUpdateRequest.class
                    .getDeclaredField(UNCONSTRAINED_COMPONENT).getDeclaredAnnotations();

            assertThat(declared)
                    .as("the item is field-set, protected and hidden on the mapset: never operator "
                            + "input and never validated, so a bound here would be an invention. A "
                            + "directional binding would be no better - it would be this transport "
                            + "type deciding a service question, and it would also strip the value "
                            + "from any body that was deserialized and re-serialized. What keeps the "
                            + "wire out of the stored expiry date is that CardUpdateService assembles "
                            + "it from the day it captured at COCRDUPC line 1366")
                    .isEmpty();
        }

        @Test
        @DisplayName("every screen value is carried as characters, so a leading zero and a padded "
                + "blank both survive")
        void everyScreenValueIsCarriedAsCharacters() {
            RecordComponent[] components = CardUpdateRequest.class.getRecordComponents();

            for (int index = 0; index < 7; index++) {
                assertThat(components[index].getType())
                        .as("component %s is a fixed-width screen item, never a number, a date or a "
                                + "two-state flag", components[index].getName())
                        .isEqualTo(String.class);
            }
            assertThat(components[7].getType()).isEqualTo(KeyAction.class);
            assertThat(components[8].getType()).isEqualTo(NavigationContext.class);
            assertThat(components)
                    .as("nine components and no tenth: the seven screen items and the two control "
                            + "components are the whole contract")
                    .hasSize(9);
        }

        @Test
        @DisplayName("no pattern, range or numeric constraint is declared anywhere on the record, "
                + "because every such rule is message-bearing and belongs to the service")
        void noPatternRangeOrNumericConstraintIsDeclared() {
            List<String> permitted = List.of("Size", "Valid");

            for (RecordComponent component : CardUpdateRequest.class.getRecordComponents()) {
                assertThat(Arrays.stream(component.getDeclaredAnnotations())
                                .map(annotation -> annotation.annotationType().getSimpleName()))
                        .as("component %s carries only a width bound, the confirming-turn absence "
                                + "rule, a cascade marker or a serialization directive",
                                component.getName())
                        .allSatisfy(name -> assertThat(permitted).contains(name));
            }
        }

        @Test
        @DisplayName("the account identifier is accepted on every submission shape, because which "
                + "turn may carry it is the service's rule and not a constraint here")
        void theAccountIdentifierIsAcceptedOnEverySubmissionShape() {
            assertThat(validator.validate(carrying("accountId", ACCOUNT_ID)))
                    .as("the operator types the identifier on the searching turn, so nothing here "
                            + "may object to it")
                    .isEmpty();
            assertThat(validator.validate(carrying("accountId", null)))
                    .as("and the confirming turn, which has the item protected and takes the owning "
                            + "account from the freshly loaded record, may omit it")
                    .isEmpty();
            assertThat(validator.validate(carrying("cardNumber", CARD_NUMBER)))
                    .as("the record key the update targets is accepted on the same terms")
                    .isEmpty();
        }

        @Test
        @DisplayName("the navigation state is marked for cascade, so an over-long echoed identifier "
                + "is measured rather than crossing unchecked")
        void theNavigationStateIsMarkedForCascade() throws NoSuchFieldException {
            assertThat(CardUpdateRequest.class.getDeclaredField("navigationContext")
                            .getAnnotation(Valid.class))
                    .as("without the cascade marker Bean Validation stops at this level and the "
                            + "widths declared on the nested contract are never evaluated")
                    .isNotNull();
        }

        @Test
        @DisplayName("no concurrency component is declared at all, so there is no annotation to "
                + "audit and nothing for a client to hold")
        void noConcurrencyComponentIsDeclaredAtAll() {
            assertThat(Arrays.stream(CardUpdateRequest.class.getRecordComponents())
                            .map(RecordComponent::getName)
                            .toList())
                    .as("optimistic locking is reproduced by the version column on the card entity, "
                            + "an entity and service concern; a conflict reaches the operator as the "
                            + "legacy text at COCRDUPC line 208 and in no other form")
                    .containsExactlyElementsOf(EXPECTED_COMPONENTS)
                    .doesNotContain("concurrencyToken");
        }
    }

    @Nested
    @DisplayName("Validation bounds")
    class ValidationBounds {

        @Test
        @DisplayName("a request whose every component sits exactly at its declared width reports no "
                + "violation")
        void aRequestAtEveryDeclaredWidthReportsNoViolation() {
            assertThat(validator.validate(atDeclaredWidths()))
                    .as("a value exactly at its bound is inside the bound")
                    .isEmpty();
        }

        @ParameterizedTest(name = "{0} rejects a value one character over {1}")
        @CsvSource({
            "accountId,11",
            "cardNumber,16",
            "embossedName,50",
            "activeStatus,1",
            "expiryMonth,2",
            "expiryYear,4",
        })
        @DisplayName("each bounded component reports a value one character over its width, and the "
                + "value itself is left exactly as supplied")
        void eachBoundedComponentReportsAnOverLongValue(String component, int width) {
            String tooLong = "9".repeat(width + 1);

            CardUpdateRequest request = carrying(component, tooLong);
            Set<ConstraintViolation<CardUpdateRequest>> violations = validator.validate(request);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString(component);
        }

        @ParameterizedTest(name = "an expiry-day carry-through of {0} characters is accepted")
        @ValueSource(ints = {0, 1, 2, 3, 40})
        @DisplayName("the hidden expiry-day carry-through accepts any width, which is what having no "
                + "bound means behaviourally")
        void theHiddenExpiryDayAcceptsAnyWidth(int width) {
            assertThat(validator.validate(carrying(UNCONSTRAINED_COMPONENT, "7".repeat(width))))
                    .as("adding a bound here would start rejecting a value the legacy screen "
                            + "transmits, so the absence of one is asserted behaviourally as well "
                            + "as declaratively")
                    .isEmpty();
        }

        @Test
        @DisplayName("an entirely absent request reports no violation, because a first entry into "
                + "the screen carries nothing")
        void anEntirelyAbsentRequestReportsNoViolation() {
            assertThat(validator.validate(
                            new CardUpdateRequest(null, null, null, null, null, null, null, null,
                                    null)))
                    .isEmpty();
        }

        @Test
        @DisplayName("an all-blank submission reports no violation, because blank is what the "
                + "terminal transmits for an untouched fixed-width item")
        void anAllBlankSubmissionReportsNoViolation() {
            assertThat(validator.validate(new CardUpdateRequest(
                            " ".repeat(11), " ".repeat(16), " ".repeat(50), " ", "  ", "    ", "  ",
                            null, null)))
                    .isEmpty();
        }

        @ParameterizedTest(name = "status character \"{0}\" is accepted by the boundary")
        @ValueSource(strings = {"Y", "N", "y", "n", "Q", "0", " "})
        @DisplayName("the status bound restricts width only, so a character outside the yes-or-no "
                + "vocabulary reaches the service where the diagnostic that names it lives")
        void theStatusBoundRestrictsWidthOnly(String status) {
            assertThat(validator.validate(carrying("activeStatus", status))).isEmpty();
        }

        @ParameterizedTest(name = "month \"{0}\" and year \"{1}\" are accepted by the boundary")
        @CsvSource({"00,0000", "13,1949", "99,2100", "1,1", "  ,    "})
        @DisplayName("neither expiry bound expresses the inclusive range, because both ranges are "
                + "message-bearing service rules")
        void neitherExpiryBoundExpressesItsRange(String month, String year) {
            CardUpdateRequest request = new CardUpdateRequest(
                    ACCOUNT_ID, CARD_NUMBER, EMBOSSED_NAME, "Y", month, year, null, null, null);

            assertThat(validator.validate(request))
                    .as("the month rule is one to twelve and the year rule is 1950 to 2099, and "
                            + "both diagnostics have exact legacy wording the boundary cannot carry")
                    .isEmpty();
        }

        @Test
        @DisplayName("an embossed name with an embedded space is accepted, matching the legacy "
                + "alphabetic idiom that blanks letters and then tests for emptiness")
        void anEmbossedNameWithAnEmbeddedSpaceIsAccepted() {
            assertThat(validator.validate(carrying("embossedName", EMBOSSED_NAME)))
                    .as("rejecting an embedded space here would refuse a value the legacy system "
                            + "accepts and would break existing cardholder data")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Wire shape")
    class WireShape {

        @Test
        @DisplayName("a fully populated request renders all ten members under their contract names")
        void aFullyPopulatedRequestRendersAllTenMembers() throws JsonProcessingException {
            CardUpdateRequest request = new CardUpdateRequest(
                    ACCOUNT_ID, CARD_NUMBER, EMBOSSED_NAME, "Y", "01", "2026", "31",
                    KeyAction.PFK05, JsonContractSupport.populatedNavigation());

            JsonNode payload = payloadOf(request);

            assertThat(payload.get("accountId").asText()).isEqualTo(ACCOUNT_ID);
            assertThat(payload.get("cardNumber").asText()).isEqualTo(CARD_NUMBER);
            assertThat(payload.get("embossedName").asText()).isEqualTo(EMBOSSED_NAME);
            assertThat(payload.get("activeStatus").asText()).isEqualTo("Y");
            assertThat(payload.get("expiryMonth").asText()).isEqualTo("01");
            assertThat(payload.get("expiryYear").asText()).isEqualTo("2026");
            assertThat(payload.get("expiryDay").asText()).isEqualTo("31");
            assertThat(payload.get("keyAction").asText())
                    .as("the attention key crosses as its constant name rather than its five-byte "
                            + "legacy identifier or an ordinal")
                    .isEqualTo("PFK05");
            assertThat(payload.get("navigationContext").isObject()).isTrue();
            assertThat(payload.has("concurrencyToken"))
                    .as("no concurrency value crosses in either direction: the legacy before-and-after "
                            + "image comparison is reproduced by the version column on the card "
                            + "entity, and a conflict reaches the client only as operator text")
                    .isFalse();
            assertThat(payload.size()).isEqualTo(EXPECTED_COMPONENTS.size());
        }

        @Test
        @DisplayName("absent members are omitted rather than written as null")
        void absentMembersAreOmitted() throws JsonProcessingException {
            JsonNode payload = payloadOf(carrying("cardNumber", CARD_NUMBER));

            assertThat(payload.size()).isEqualTo(1);
            assertThat(payload.get("cardNumber").asText()).isEqualTo(CARD_NUMBER);
            for (String component : EXPECTED_COMPONENTS) {
                if (!"cardNumber".equals(component)) {
                    assertThat(payload.has(component))
                            .as("%s was not supplied, so it must be absent rather than null",
                                    component)
                            .isFalse();
                }
            }
        }

        @Test
        @DisplayName("a leading zero and a trailing space both survive the wire round trip, because "
                + "the update compares the submitted value to the stored record character for "
                + "character")
        void leadingZeroesAndTrailingSpacesSurviveTheWireRoundTrip() throws JsonProcessingException {
            CardUpdateRequest request = new CardUpdateRequest(
                    "00000000011", CARD_NUMBER, "MARY ANN  ", "Y", "01", "2026", "07", null, null);

            ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();
            CardUpdateRequest returned = mapper.readValue(
                    mapper.writeValueAsString(request), CardUpdateRequest.class);

            assertThat(returned.accountId()).isEqualTo("00000000011");
            assertThat(returned.embossedName()).isEqualTo("MARY ANN  ");
            assertThat(returned.expiryMonth()).isEqualTo("01");
            assertThat(returned.expiryDay())
                    .as("the hidden carry-through is written and read alike, because this passive "
                            + "carrier applies no directional rule; the service assembles the stored "
                            + "expiry date from the day it captured itself, never from this body")
                    .isEqualTo("07");
            assertThat(returned)
                    .as("every component survives the round trip, so the record compares equal to "
                            + "the one it was written from")
                    .isEqualTo(request);
        }

        @Test
        @DisplayName("a body that supplies the hidden expiry day has it carried verbatim, leaving the "
                + "decision to disregard it to the service that holds the stored record")
        void aSuppliedHiddenExpiryDayIsCarriedVerbatim() throws JsonProcessingException {
            String body = "{'expiryMonth':'01','expiryDay':'99'}".replace((char) 39, (char) 34);

            CardUpdateRequest bound = JsonContractSupport.declaredSettingsMapper()
                    .readValue(body, CardUpdateRequest.class);

            assertThat(bound.expiryDay())
                    .as("the component carries whatever arrives; CardUpdateService assembles the "
                            + "stored expiry date from the day it captured at COCRDUPC line 1366, so "
                            + "the wire still cannot reach the record through it")
                    .isEqualTo("99");
            assertThat(bound.expiryMonth())
                    .as("positive control: a sibling expiry part still binds")
                    .isEqualTo("01");
        }

        @Test
        @DisplayName("an unknown member a client echoes back is ignored rather than rejected")
        void anUnknownMemberIsIgnored() throws JsonProcessingException {
            String payload = "{\"cardNumber\":\"" + CARD_NUMBER
                    + "\",\"embossedName\":\"" + EMBOSSED_NAME
                    + "\",\"cardCvvCode\":\"123\",\"unheardOf\":{\"nested\":1}}";

            CardUpdateRequest returned = JsonContractSupport.declaredSettingsMapper()
                    .readValue(payload, CardUpdateRequest.class);

            assertThat(returned.cardNumber()).isEqualTo(CARD_NUMBER);
            assertThat(returned.embossedName()).isEqualTo(EMBOSSED_NAME);
            assertThat(returned.activeStatus()).isNull();
        }

        @Test
        @DisplayName("the hidden expiry-day carry-through crosses the wire like any other member, "
                + "because hidden on a 3270 map is not hidden on a REST contract")
        void theHiddenCarryThroughCrossesTheWire() throws JsonProcessingException {
            JsonNode payload = payloadOf(carrying(UNCONSTRAINED_COMPONENT, "31"));

            assertThat(payload.get("expiryDay").asText())
                    .as("the legacy screen transmits it invisibly on every turn; the REST client "
                            + "echoes it explicitly, which is the same carry-through by another "
                            + "mechanism")
                    .isEqualTo("31");
        }
    }

    @Nested
    @DisplayName("Value fidelity and diagnostics")
    class ValueFidelityAndDiagnostics {

        @Test
        @DisplayName("no component is folded, trimmed or normalised on the way in, so a case-only "
                + "edit to the embossed name is still visible at this boundary")
        void noComponentIsFoldedOrTrimmed() {
            CardUpdateRequest request = new CardUpdateRequest(
                    " 0000000011", CARD_NUMBER, "mary ann", "y", " 1", "2026 ", " 7", null, null);

            assertThat(request.accountId()).isEqualTo(" 0000000011");
            assertThat(request.embossedName())
                    .as("the service performs the fold; carrying the value verbatim is what makes "
                            + "the two in-place legacy folds reproducible rather than pre-empted")
                    .isEqualTo("mary ann");
            assertThat(request.activeStatus()).isEqualTo("y");
            assertThat(request.expiryMonth()).isEqualTo(" 1");
            assertThat(request.expiryYear()).isEqualTo("2026 ");
            assertThat(request.expiryDay()).isEqualTo(" 7");
        }

        @Test
        @DisplayName("a name differing only in letter case is a different request, which is what "
                + "makes the case-only edit detectable before the fold is applied")
        void aCaseOnlyDifferenceYieldsADifferentRequest() {
            assertThat(carrying("embossedName", "MARY ANN"))
                    .isNotEqualTo(carrying("embossedName", "mary ann"));
        }

        @Test
        @DisplayName("the whole component set is replaced by one fixed marker, so neither a supplied "
                + "value nor its length survives a stringified instance")
        void theWholeComponentSetIsReplacedByOneMarker() {
            assertThat(atDeclaredWidths().toString())
                    .as("framework binding can stringify a request outside this module's control, "
                            + "and every component here is either cardholder data, a record key or "
                            + "state naming the one card being updated")
                    .isEqualTo("CardUpdateRequest[" + EXPECTED_PLACEHOLDER + "]")
                    .doesNotContain(ACCOUNT_ID)
                    .doesNotContain(CARD_NUMBER);
            assertThat(carrying("cardNumber", "1").toString())
                    .as("the marker does not vary with the value, so neither a length nor a prefix "
                            + "of a withheld component is recoverable")
                    .isEqualTo(carrying("cardNumber", CARD_NUMBER).toString());
        }

        @Test
        @DisplayName("nesting a populated navigation state discloses none of its identifying "
                + "values either, because the marker replaces the component that would print it")
        void nestingTheNavigationStateDisclosesNothingIdentifying() {
            CardUpdateRequest request = new CardUpdateRequest(
                    ACCOUNT_ID, CARD_NUMBER, EMBOSSED_NAME, "Y", "01", "2026", "31",
                    KeyAction.ENTER, JsonContractSupport.populatedNavigation());

            assertThat(request.toString())
                    .doesNotContain(JsonContractSupport.NAV_CARD_NUMBER)
                    .doesNotContain(JsonContractSupport.NAV_ACCOUNT_ID)
                    .doesNotContain(JsonContractSupport.NAV_CUSTOMER_ID)
                    .doesNotContain(JsonContractSupport.NAV_FIRST_NAME)
                    .doesNotContain(JsonContractSupport.NAV_MIDDLE_NAME)
                    .doesNotContain(JsonContractSupport.NAV_LAST_NAME);
        }
    }
}
