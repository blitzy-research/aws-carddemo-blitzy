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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link CardDetailResponse} cannot disclose a regulated value through its stringified
 * form, while still carrying every one of them on the wire.
 *
 * <p>The risk is the same one every response contract in this package faces: a record's generated
 * rendering prints all of its components, and a response object reaches a log or an exception message
 * by being interpolated into a string without anyone intending it. Here that would mean a full
 * sixteen-character card number, the eleven-character account identifier and the cardholder's embossed
 * name in a single line.
 *
 * <p>One group below is about consistency rather than about this type alone. The withheld set is
 * deliberately identical to the one {@link CardUpdateResponse} withholds, because the two card screens
 * describe the same record: a value that is unsafe to print from the update screen is not made safe by
 * having been reached through the detail screen. That kind of divergence survives review by looking
 * local to one file, so it is asserted here rather than left to inspection.
 */
@DisplayName("CardDetailResponse - diagnostic rendering safety for legacy transaction CCDL")
class CardDetailResponseSecurityTest {

    private static final String PLACEHOLDER = "***REDACTED***";

    private static final String ACCOUNT_ID = "78412590063";
    private static final String CARD_NUMBER = "4532015112830366";
    private static final String EMBOSSED_NAME = "MARY ANN OSULLIVAN";
    private static final String EXPIRY_MONTH = "07";
    private static final String EXPIRY_YEAR = "2027";

    /** The five components the renderer withholds, in declaration order. */
    private static final List<String> WITHHELD_COMPONENTS =
            List.of("accountId", "cardNumber", "embossedName", "expiryMonth", "expiryYear");

    private static final List<String> REGULATED_VALUES =
            List.of(ACCOUNT_ID, CARD_NUMBER, EMBOSSED_NAME);

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

    private static CardDetailResponse populated() {
        return populatedWith(null);
    }

    private static CardDetailResponse populatedWith(NavigationContext navigation) {
        return new CardDetailResponse(
                "CCDL", "TITLE ONE", "07/19/22", "COCRDSLC", "TITLE TWO", "14:23:07",
                ACCOUNT_ID, CARD_NUMBER, EMBOSSED_NAME,
                "Y", EXPIRY_MONTH, EXPIRY_YEAR,
                "INFORMATION LINE", "ERROR LINE", true, "CARDSID", "route/next", navigation);
    }

    private static CardDetailResponse empty() {
        return new CardDetailResponse(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                false, null, null, null);
    }

    private static int occurrencesOf(String haystack, String needle) {
        int count = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            count++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return count;
    }

    private static JsonNode payloadOf(CardDetailResponse response) throws Exception {
        return moduleEquivalentMapper().readTree(moduleEquivalentMapper().writeValueAsString(response));
    }

    @Nested
    @DisplayName("No regulated value survives the rendering")
    class NoRegulatedValueSurvives {

        @Test
        @DisplayName("every regulated value is absent from the rendering in whole")
        void everyRegulatedValueIsAbsentInWhole() {
            assertThat(populated().toString()).doesNotContain(REGULATED_VALUES);
        }

        @Test
        @DisplayName("no six-character run of any regulated value survives, so nothing is disclosed "
                + "in part")
        void noSixCharacterRunSurvives() {
            String rendered = populated().toString();
            for (String value : REGULATED_VALUES) {
                for (int start = 0; start + 6 <= value.length(); start++) {
                    String fragment = value.substring(start, start + 6);
                    assertThat(rendered)
                            .withFailMessage("fragment %s of %s must not appear in %s",
                                    fragment, value, rendered)
                            .doesNotContain(fragment);
                }
            }
        }

        @Test
        @DisplayName("neither the issuer prefix nor the trailing digits of the card number appear, so "
                + "the two fragments a masked rendering conventionally keeps are both withheld")
        void neitherIssuerPrefixNorTrailingDigitsAppear() {
            String rendered = populated().toString();
            assertThat(rendered)
                    .doesNotContain(CARD_NUMBER.substring(0, 4))
                    .doesNotContain(CARD_NUMBER.substring(CARD_NUMBER.length() - 4));
        }

        @Test
        @DisplayName("no single name part of the embossed name survives, so splitting on the space "
                + "does not recover it")
        void noNamePartOfTheEmbossedNameSurvives() {
            String rendered = populated().toString();
            for (String part : EMBOSSED_NAME.split(" ")) {
                assertThat(rendered)
                        .withFailMessage("name part %s must not appear in %s", part, rendered)
                        .doesNotContain(part);
            }
        }
    }

    @Nested
    @DisplayName("The stand-in is constant and reveals nothing about what it replaced")
    class TheStandInIsConstant {

        @Test
        @DisplayName("each withheld component renders as the same fixed stand-in")
        void eachWithheldComponentRendersAsTheFixedStandIn() {
            String rendered = populated().toString();
            WITHHELD_COMPONENTS.forEach(name ->
                    assertThat(rendered).contains(name + "=" + PLACEHOLDER));
        }

        @Test
        @DisplayName("the stand-in appears exactly five times, one per withheld component")
        void theStandInAppearsExactlyOncePerWithheldComponent() {
            assertThat(occurrencesOf(populated().toString(), PLACEHOLDER))
                    .isEqualTo(WITHHELD_COMPONENTS.size());
        }

        @Test
        @DisplayName("a withheld component renders identically whether it held a value or nothing, so "
                + "the rendering does not disclose presence")
        void aWithheldComponentRendersIdenticallyWhetherPresentOrAbsent() {
            String withValues = populated().toString();
            String withoutValues = empty().toString();
            for (String name : WITHHELD_COMPONENTS) {
                assertThat(withValues).contains(name + "=" + PLACEHOLDER);
                assertThat(withoutValues).contains(name + "=" + PLACEHOLDER);
            }
        }

        @Test
        @DisplayName("two responses differing only in their regulated values render identically, which "
                + "is presence and length disclosure ruled out together")
        void responsesDifferingOnlyInRegulatedValuesRenderIdentically() {
            CardDetailResponse other = new CardDetailResponse(
                    "CCDL", "TITLE ONE", "07/19/22", "COCRDSLC", "TITLE TWO", "14:23:07",
                    "00000000001", "5500005555555559", "A", "Y", "11", "2031",
                    "INFORMATION LINE", "ERROR LINE", true, "CARDSID", "route/next", null);
            assertThat(other.toString()).isEqualTo(populated().toString());
        }
    }

    @Nested
    @DisplayName("The withheld set matches the card-update contract")
    class TheWithheldSetMatchesTheCardUpdateContract {

        @Test
        @DisplayName("the same component names are withheld here as on the update screen, so the "
                + "two views of one card record cannot disagree about what is safe to print")
        void theSameComponentNamesAreWithheldAsOnTheUpdateScreen() {
            String rendered = populated().toString();
            assertThat(rendered)
                    .contains("accountId=" + PLACEHOLDER)
                    .contains("cardNumber=" + PLACEHOLDER)
                    .contains("embossedName=" + PLACEHOLDER)
                    .contains("expiryMonth=" + PLACEHOLDER)
                    .contains("expiryYear=" + PLACEHOLDER);
            assertThat(occurrencesOf(rendered, PLACEHOLDER)).isEqualTo(5);
        }

        @Test
        @DisplayName("the expiry parts go with the card number rather than with the screen furniture, "
                + "because month and year reconstruct an authentication factor")
        void theExpiryPartsAreWithheldAndTheStatusCodeIsRetained() {
            assertThat(populated().toString())
                    .contains("cardActiveStatus=Y")
                    .doesNotContain("expiryMonth=" + EXPIRY_MONTH)
                    .doesNotContain("expiryYear=" + EXPIRY_YEAR);
        }
    }

    @Nested
    @DisplayName("The retained components stay visible, so the rendering remains diagnostic")
    class TheRetainedComponentsStayVisible {

        @Test
        @DisplayName("the screen furniture, both message lines, the error flag, the focus field and "
                + "the route are shown as supplied")
        void theDiagnosticallyUsefulComponentsAreShown() {
            assertThat(populated().toString())
                    .startsWith("CardDetailResponse[")
                    .contains("transactionName=CCDL")
                    .contains("programName=COCRDSLC")
                    .contains("currentDate=07/19/22")
                    .contains("currentTime=14:23:07")
                    .contains("infoMessage=INFORMATION LINE")
                    .contains("errorMessage=ERROR LINE")
                    .contains("generalError=true")
                    .contains("focusScreenFieldId=CARDSID")
                    .contains("nextRoute=route/next");
        }
    }

    @Nested
    @DisplayName("Delegating to the navigation state does not widen disclosure")
    class DelegationDoesNotWidenDisclosure {

        @Test
        @DisplayName("the nested navigation state withholds its own identifying values, so rendering "
                + "it rather than replacing it discloses nothing further")
        void theNestedNavigationStateWithholdsItsOwnIdentifyingValues() {
            NavigationContext navigation = new NavigationContext(
                    "CCLI", "COCRDLIC", "CCDL", "COCRDSLC", "USER0001", "U",
                    NavigationContext.ProgramContext.REENTER,
                    "123456789", "MARY", "ANN", "OSULLIVAN",
                    ACCOUNT_ID, "Y", CARD_NUMBER, "COCRDSA", "COCRDSL");

            String rendered = populatedWith(navigation).toString();
            assertThat(rendered)
                    .doesNotContain("123456789")
                    .doesNotContain(ACCOUNT_ID)
                    .doesNotContain(CARD_NUMBER)
                    .doesNotContain("OSULLIVAN");
            assertThat(rendered).contains("NavigationContext[");
        }
    }

    @Nested
    @DisplayName("An entirely absent response renders safely")
    class AnEntirelyAbsentResponseRendersSafely {

        @Test
        @DisplayName("a response with every component absent renders without throwing and carries no "
                + "regulated value")
        void anAbsentResponseRendersWithoutThrowing() {
            String rendered = empty().toString();
            assertThat(rendered)
                    .startsWith("CardDetailResponse[")
                    .endsWith("]")
                    .doesNotContain(REGULATED_VALUES);
            assertThat(occurrencesOf(rendered, PLACEHOLDER)).isEqualTo(WITHHELD_COMPONENTS.size());
        }
    }

    @Nested
    @DisplayName("The wire payload still carries every withheld value in full")
    class TheWirePayloadStillCarriesEveryValue {

        @Test
        @DisplayName("all three withheld components serialise at their full untouched values, so "
                + "withholding from a log does not withhold from the client")
        void allWithheldComponentsSerialiseInFull() throws Exception {
            JsonNode payload = payloadOf(populated());
            assertThat(payload.get("accountId").asText()).isEqualTo(ACCOUNT_ID);
            assertThat(payload.get("cardNumber").asText()).isEqualTo(CARD_NUMBER);
            assertThat(payload.get("embossedName").asText()).isEqualTo(EMBOSSED_NAME);
        }

        @Test
        @DisplayName("the card number keeps all sixteen characters on the wire, unmasked and "
                + "unshortened, which is the screen contract")
        void theCardNumberKeepsAllSixteenCharactersOnTheWire() throws Exception {
            assertThat(payloadOf(populated()).get("cardNumber").asText()).hasSize(16);
        }

        @Test
        @DisplayName("the stand-in never leaks into the serialized form, so no client ever receives a "
                + "redacted value in place of a real one")
        void theStandInNeverReachesTheWire() throws Exception {
            assertThat(payloadOf(populated()).toString()).doesNotContain("REDACTED");
        }
    }
}
