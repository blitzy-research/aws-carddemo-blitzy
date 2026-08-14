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

import com.carddemo.domain.enums.UserType;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Guards the one claim {@link SignOnResponse} makes that no other response contract in this package can
 * make: that every one of its components is safe to print, so it needs no withholding rendering.
 *
 * <p>That claim is true today, and it is the kind of claim that quietly stops being true. It rests
 * entirely on the component set: fifteen components, of which nine are server-supplied screen text, one
 * is a role, one is a route label, one is a screen-field identifier, one is an error flag, one is the
 * sign-on identifier, and one is the nested navigation state that withholds its own identifying values.
 * The sign-on identifier is safe to print here for a reason worth restating: the nested navigation state
 * carries the same value from the same communication-area item and renders it in the clear, so it
 * already reaches this rendering and its appearance as a component of its own discloses nothing new.
 * Add a card number, an account identifier or a bearer token to that set and the generated rendering
 * starts disclosing it, silently, with no test failing and the file's own documentation still asserting
 * the opposite.
 *
 * <p>This suite is therefore written as a canary rather than as a conventional redaction test. It pins
 * the component set exactly and rejects any component whose name suggests a regulated concept, so that
 * the act of adding one fails here and forces the author either to add a withholding rendering or to
 * justify the addition. A test that merely asserted "no regulated value appears in the rendering" would
 * pass vacuously forever, because there is no regulated value in the fixture to look for - which is
 * precisely the trap this file is built to avoid.
 *
 * <p>The credential prohibition is asserted the same structural way: not by checking that a credential is
 * absent from a rendering, but by checking that no component exists in which one could travel.
 */
@DisplayName("SignOnResponse - the one response contract that is safe to print by design")
class SignOnResponseSecurityTest {

    /** The exact component set the type documents itself as carrying, in declaration order. */
    private static final List<String> COMPONENTS_IN_ORDER = List.of(
            "message", "generalError", "focusScreenFieldId", "nextRoute", "navigationContext",
            "userId", "userType", "transactionName", "programName", "title01", "title02",
            "currentDate", "currentTime", "applicationId", "systemId");

    /** The sign-on identifier the fixture carries; also carried by the nested navigation state. */
    private static final String USER_ID = "USER0001";

    /**
     * Name fragments that would indicate a regulated concept had been introduced.
     *
     * <p>Matched case-insensitively against every component name. The list covers the four categories
     * the review names - identifiers, monetary values, personal data and credentials - plus the bearer
     * artefacts this contract specifically undertakes never to carry.
     */
    private static final List<String> REGULATED_NAME_FRAGMENTS = List.of(
            "card", "pan", "account", "customer", "transaction", "ssn", "govt", "government",
            "balance", "limit", "amount", "credit", "debit", "fico", "birth", "dob", "address",
            "phone", "zip", "city", "state", "firstname", "lastname", "middlename", "surname",
            "password", "passwd", "secret", "token", "bearer", "jwt", "credential", "salt", "hash");

    /**
     * Component names that match a regulated fragment but are known safe, listed by exact name.
     *
     * <p>{@code transactionName} matches the {@code transaction} fragment and is nonetheless furniture:
     * it carries the four-character CICS transaction identifier {@code CC00}, a server-supplied label
     * naming which screen the operator is on. It is not a reference to a financial transaction record,
     * and no financial transaction exists at sign-on time for it to refer to.
     *
     * <p>The exemption is deliberately keyed to the exact name rather than granted to the fragment. A
     * component added later as {@code transactionId}, {@code transactionAmount} or
     * {@code searchTransactionId} matches the same fragment, is not on this list, and so still fails -
     * which is the whole point of catching the collision this way instead of narrowing the fragment.
     */
    private static final List<String> SAFE_DESPITE_MATCHING_FRAGMENT = List.of("transactionName");

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

    private static List<String> componentNames() {
        return Arrays.stream(SignOnResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    private static SignOnResponse populated() {
        return populatedWith(null);
    }

    private static SignOnResponse populatedWith(NavigationContext navigation) {
        return new SignOnResponse(
                SignOnResponse.MSG_WRONG_PASSWD, true, "PASSWD", "route/next", navigation,
                USER_ID, UserType.ADMIN.getCode(), SignOnResponse.TRANSACTION_NAME,
                SignOnResponse.PROGRAM_NAME, "TITLE ONE", "TITLE TWO", "07/19/22", "14:23:07",
                "CARDDEMO", "SYS00001");
    }

    @Nested
    @DisplayName("The component set is pinned, so widening it cannot pass unnoticed")
    class TheComponentSetIsPinned {

        @Test
        @DisplayName("the type carries exactly the fifteen documented components, in the documented "
                + "order")
        void theTypeCarriesExactlyTheFifteenDocumentedComponents() {
            assertThat(componentNames()).containsExactlyElementsOf(COMPONENTS_IN_ORDER);
        }

        @Test
        @DisplayName("no component name suggests a regulated concept, which is what makes the "
                + "safe-to-print claim true rather than merely untested")
        void noComponentNameSuggestsARegulatedConcept() {
            for (String component : componentNames()) {
                if (SAFE_DESPITE_MATCHING_FRAGMENT.contains(component)) {
                    continue;
                }
                String lower = component.toLowerCase(Locale.ROOT);
                for (String fragment : REGULATED_NAME_FRAGMENTS) {
                    assertThat(lower)
                            .withFailMessage(
                                    "component '%s' matches the regulated fragment '%s'. If this "
                                            + "component genuinely belongs on the sign-on response, "
                                            + "SignOnResponse must gain a withholding toString() and "
                                            + "its class documentation must stop claiming it needs "
                                            + "none.",
                                    component, fragment)
                            .doesNotContain(fragment);
                }
            }
        }

        @Test
        @DisplayName("the one fragment exemption is honest: it names a component that actually exists, "
                + "and it does not shelter the near-miss names it would be abused to shelter")
        void theOneFragmentExemptionIsHonest() {
            assertThat(SAFE_DESPITE_MATCHING_FRAGMENT)
                    .as("an exemption that names no real component is stale and must be removed")
                    .allSatisfy(exempt -> assertThat(componentNames()).contains(exempt));

            assertThat(SAFE_DESPITE_MATCHING_FRAGMENT)
                    .as("widening the exemption list is a contract decision, not a test detail")
                    .containsExactly("transactionName");

            for (String wouldBeRegulated : List.of("transactionId", "searchTransactionId",
                    "transactionAmount", "transactionCardNumber")) {
                assertThat(SAFE_DESPITE_MATCHING_FRAGMENT)
                        .withFailMessage(
                                "'%s' must never be exempt - it is the exact kind of component this "
                                        + "canary exists to catch",
                                wouldBeRegulated)
                        .doesNotContain(wouldBeRegulated);
            }
        }

        @Test
        @DisplayName("no component exists in which a credential could travel, which is the strongest "
                + "form the prohibition can take")
        void noComponentExistsInWhichACredentialCouldTravel() {
            assertThat(componentNames())
                    .noneMatch(name -> {
                        String lower = name.toLowerCase(Locale.ROOT);
                        return lower.contains("password") || lower.contains("passwd")
                                || lower.contains("credential") || lower.contains("token")
                                || lower.contains("secret") || lower.contains("hash");
                    });
        }
    }

    @Nested
    @DisplayName("The default rendering is safe, and stays informative")
    class TheDefaultRenderingIsSafe {

        @Test
        @DisplayName("the rendering shows the screen text, the role, the route and the focus field, "
                + "because none of them identifies anyone")
        void theRenderingShowsItsSafeComponents() {
            String rendered = populated().toString();
            assertThat(rendered)
                    .startsWith("SignOnResponse[")
                    .endsWith("]")
                    .contains("transactionName=" + SignOnResponse.TRANSACTION_NAME)
                    .contains("programName=" + SignOnResponse.PROGRAM_NAME)
                    .contains("userType=" + UserType.ADMIN.getCode())
                    .contains("userId=" + USER_ID)
                    .contains("nextRoute=route/next")
                    .contains("focusScreenFieldId=PASSWD")
                    .contains("generalError=true")
                    .contains("applicationId=CARDDEMO")
                    .contains("systemId=SYS00001");
        }

        @Test
        @DisplayName("the sign-on message renders verbatim, because the message text is external "
                + "contract and reveals nothing about the credential that was submitted")
        void theSignOnMessageRendersVerbatim() {
            assertThat(populated().toString())
                    .contains("message=" + SignOnResponse.MSG_WRONG_PASSWD);
        }

        @Test
        @DisplayName("no stand-in appears anywhere in the rendering, because there is nothing to "
                + "withhold - the absence is a finding, not an omission")
        void noStandInAppearsAnywhere() {
            assertThat(populated().toString()).doesNotContain("***REDACTED***");
        }
    }

    @Nested
    @DisplayName("Delegating to the navigation state does not widen disclosure")
    class DelegationDoesNotWidenDisclosure {

        @Test
        @DisplayName("the nested navigation state withholds its own six identifying values, so the one "
                + "component that could carry identity discloses none of it")
        void theNestedNavigationStateWithholdsItsOwnIdentifyingValues() {
            NavigationContext navigation = new NavigationContext(
                    "CC00", "COSGN00C", "CM00", "COMEN01C", USER_ID, "A",
                    NavigationContext.ProgramContext.ENTER,
                    "123456789", "MARIANNE", "THEODORA", "OSULLIVAN",
                    "78412590063", "Y", "5555444433332222", "COSGN0A", "COSGN00");

            String rendered = populatedWith(navigation).toString();
            assertThat(rendered)
                    .doesNotContain("123456789")
                    .doesNotContain("78412590063")
                    .doesNotContain("5555444433332222")
                    .doesNotContain("MARIANNE")
                    .doesNotContain("THEODORA")
                    .doesNotContain("OSULLIVAN");
            assertThat(rendered).contains("NavigationContext[");
        }
    }

    @Nested
    @DisplayName("An entirely absent response renders safely")
    class AnEntirelyAbsentResponseRendersSafely {

        @Test
        @DisplayName("a response with every component absent renders without throwing")
        void anAbsentResponseRendersWithoutThrowing() {
            SignOnResponse absent = new SignOnResponse(
                    null, false, null, null, null, null, null, null, null, null, null, null, null,
                    null, null);
            assertThat(absent.toString())
                    .startsWith("SignOnResponse[")
                    .endsWith("]");
        }
    }

    @Nested
    @DisplayName("No credential reaches the wire, because none exists to serialise")
    class NoCredentialReachesTheWire {

        @Test
        @DisplayName("the serialized payload has no credential or bearer property under any of the "
                + "names one would conventionally take")
        void theSerializedPayloadHasNoCredentialProperty() throws Exception {
            var payload = moduleEquivalentMapper()
                    .readTree(moduleEquivalentMapper().writeValueAsString(populated()));
            for (String name : List.of("password", "passwd", "credential", "token", "accessToken",
                    "refreshToken", "jwt", "bearer", "secret", "passwordHash", "salt")) {
                assertThat(payload.has(name))
                        .withFailMessage("serialized payload must not carry a '%s' property", name)
                        .isFalse();
            }
        }
    }
}
