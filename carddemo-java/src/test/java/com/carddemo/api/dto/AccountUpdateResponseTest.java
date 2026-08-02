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

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AccountUpdateResponse}, the response body of legacy transaction
 * {@code CAUP}.
 *
 * <p>A pure unit test: it starts no application context, opens no connection and launches no
 * container. Where the wire shape is what is under test it serialises with a locally built mapper
 * configured to match the settings the module declares in {@code application.yml}, so an asserted
 * payload is the payload a client receives.
 *
 * <p>Two properties dominate what is checked here, because both were absent and both are
 * load-bearing.
 *
 * <p>The first is the conversation token. {@code app/cbl/COACTUPC.cbl} declares a program
 * communication-area extension at line 652 carrying the old record image, hands it back with the
 * screen at lines 1010 to 1018, slices it off again at lines 888 to 892 and compares it in
 * paragraph {@code 9700-CHECK-CHANGE-IN-REC} at line 4109. {@link AccountUpdateRequest} already
 * declares the returning half of that round trip; these tests assert that the outbound half exists
 * here, since {@code AccountConcurrencyTokenService} treats an absent token as a conflict and the
 * confirming turn would otherwise be unreachable.
 *
 * <p>The second is the decimal shape of the five monetary components, whose record counterparts are
 * the signed zoned decimals at {@code app/cpy/CVACT01Y.cpy} lines 7, 8, 9, 13 and 14 - ten integer
 * digits and two decimal places each.
 *
 * @since 1.0.0
 */
@DisplayName("AccountUpdateResponse")
class AccountUpdateResponseTest {

    /** The component order the map and this record share, restated as an independent oracle. */
    private static final List<String> COMPONENTS_IN_MAP_ORDER = List.of(
            "transactionName", "title01", "currentDate", "programName", "title02", "currentTime",
            "accountId", "accountStatus", "openYear", "openMonth", "openDay", "creditLimit",
            "expiryYear", "expiryMonth", "expiryDay", "cashCreditLimit", "reissueYear",
            "reissueMonth", "reissueDay", "currentBalance", "currentCycleCredit", "accountGroupId",
            "currentCycleDebit", "customerId", "ssnPart1", "ssnPart2", "ssnPart3",
            "dateOfBirthYear", "dateOfBirthMonth", "dateOfBirthDay", "ficoScore", "firstName",
            "middleName", "lastName", "addressLine1", "stateCode", "addressLine2", "zipCode",
            "city", "countryCode", "phone1AreaCode", "phone1Prefix", "phone1LineNumber",
            "governmentIssuedId", "phone2AreaCode", "phone2Prefix", "phone2LineNumber",
            "eftAccountId", "primaryCardHolderIndicator", "infoMessage", "errorMessage", "error",
            "focusScreenFieldId", "nextRoute", "navigationContext", "fieldErrors",
            "concurrencyToken");

    /** The five components whose record counterparts are signed two-decimal zoned decimals. */
    private static final List<String> MONETARY_COMPONENTS = List.of(
            "creditLimit", "cashCreditLimit", "currentBalance", "currentCycleCredit",
            "currentCycleDebit");

    /** Stand-in for a sealed token: opaque to this test, exactly as it is to a client. */
    private static final String TOKEN = "ACUP1.c2VhbGVkLWRpZ2VzdC1wYWly.9f2c7ab1";

    private static final BigDecimal LIMIT = new BigDecimal("5000.00");
    private static final BigDecimal CASH_LIMIT = new BigDecimal("1500.00");
    private static final BigDecimal BALANCE = new BigDecimal("-247.83");
    private static final BigDecimal CYCLE_CREDIT = new BigDecimal("0.00");
    private static final BigDecimal CYCLE_DEBIT = new BigDecimal("312.45");

    /**
     * Builds a fully populated response with the supplied token and monetary values.
     *
     * <p>Every text component carries a value so that a rendering test can prove none of them
     * escapes, and the space-padded entries are padded on purpose: a fixed-width value that came
     * back trimmed would be a parity defect.
     *
     * @param token the conversation token to carry, or {@code null} for none
     * @param creditLimit the credit limit to carry
     * @param cashCreditLimit the cash credit limit to carry
     * @param currentBalance the current balance to carry
     * @param currentCycleCredit the current cycle credit to carry
     * @param currentCycleDebit the current cycle debit to carry
     * @return a populated response
     */
    private static AccountUpdateResponse populated(String token,
            BigDecimal creditLimit,
            BigDecimal cashCreditLimit,
            BigDecimal currentBalance,
            BigDecimal currentCycleCredit,
            BigDecimal currentCycleDebit) {
        return new AccountUpdateResponse(
                "CAUP", "Account Update", "08/02/26", "COACTUPC", "CardDemo", "10:15:30",
                "00000000011", "Y", "2020", "01", "15", creditLimit,
                "2028", "12", "31", cashCreditLimit, "2024", "06", "30",
                currentBalance, currentCycleCredit, "          ", currentCycleDebit,
                "000000011", "123", "45", "6789", "1985", "03", "22", "001",
                "Mary                     ", "Ann                      ",
                "Vandelay                 ",
                "1 Corporate Way                                   ", "NY",
                "Suite 400                                         ", "10118",
                "New York                                          ", "USA",
                "212", "555", "0143", "P1234567890123456789",
                "347", "555", "0199", "EFT0000001", "Y",
                "Enter changes and confirm                    ",
                AccountUpdateResponse.MSG_LOOKS_GOOD_SO_FAR,
                false, "ACSTTUS", "/api/accounts/update",
                NavigationContext.empty(),
                List.of(new ErrorResponse.FieldError("stateCode", "ACSSTTE",
                        ErrorResponse.FieldState.INVALID,
                        AccountUpdateResponse.SUFFIX_STATE_NOT_VALID)),
                token);
    }

    /** Builds the canonical populated response: a sealed token and five in-contract amounts. */
    private static AccountUpdateResponse populated() {
        return populated(TOKEN, LIMIT, CASH_LIMIT, BALANCE, CYCLE_CREDIT, CYCLE_DEBIT);
    }

    /**
     * Mirrors the serialisation settings the module declares, so an asserted payload is the payload
     * a client actually receives.
     *
     * @return a mapper configured like the module's own
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

    private static JsonNode payloadOf(AccountUpdateResponse response) throws JsonProcessingException {
        ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(response));
    }

    private static List<String> componentNames() {
        return Arrays.stream(AccountUpdateResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    private static RecordComponent componentNamed(String name) {
        return Arrays.stream(AccountUpdateResponse.class.getRecordComponents())
                .filter(component -> component.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no component named " + name));
    }

    /**
     * Returns the annotations a component actually carries at run time.
     *
     * <p>Read from the backing field rather than from the record component, which is not the same
     * surface and is easy to get wrong. Neither the length constraint nor the schema annotation
     * declares {@code RECORD_COMPONENT} among its targets, so the compiler propagates each to the
     * field, the accessor and the constructor parameter but records none of them against the
     * component itself: asking the component yields an empty array for every component on this
     * type, and an assertion phrased that way would pass without testing anything.
     *
     * @param name the component name
     * @return the annotations present on the backing field
     */
    private static java.lang.annotation.Annotation[] annotationsOn(String name) {
        try {
            return AccountUpdateResponse.class.getDeclaredField(name).getAnnotations();
        } catch (NoSuchFieldException absent) {
            throw new AssertionError("no component named " + name, absent);
        }
    }

    private static Schema schemaOn(String name) {
        try {
            return AccountUpdateResponse.class.getDeclaredField(name).getAnnotation(Schema.class);
        } catch (NoSuchFieldException absent) {
            throw new AssertionError("no component named " + name, absent);
        }
    }

    @Nested
    @DisplayName("component inventory")
    class ComponentInventory {

        @Test
        @DisplayName("declares the map order followed by the control components and the token")
        void declaresTheExpectedComponentsInOrder() {
            assertThat(componentNames()).containsExactlyElementsOf(COMPONENTS_IN_MAP_ORDER);
        }

        @Test
        @DisplayName("carries no numeric identifier, so a leading-zero value survives")
        void carriesNoNumericIdentifier() {
            assertThat(Arrays.stream(AccountUpdateResponse.class.getRecordComponents())
                    .map(component -> component.getType().getName())
                    .filter(type -> type.equals("int") || type.equals("long")
                            || type.equals("java.lang.Integer") || type.equals("java.lang.Long")))
                    .isEmpty();

            assertThat(populated().ficoScore()).isEqualTo("001");
            assertThat(populated().accountId()).isEqualTo("00000000011");
        }

        @Test
        @DisplayName("carries exactly five decimal components and no binary numeric type")
        void carriesExactlyFiveDecimalComponents() {
            assertThat(Arrays.stream(AccountUpdateResponse.class.getRecordComponents())
                    .filter(component -> component.getType() == BigDecimal.class)
                    .map(RecordComponent::getName))
                    .containsExactlyElementsOf(MONETARY_COMPONENTS);

            assertThat(Arrays.stream(AccountUpdateResponse.class.getRecordComponents())
                    .map(component -> component.getType().getName())
                    .filter(type -> type.contains("ouble") || type.contains("loat")))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("the conversation token")
    class ConversationToken {

        @Test
        @DisplayName("is declared, which is what makes the confirming turn reachable")
        void isDeclared() {
            assertThat(componentNames()).contains("concurrencyToken");
            assertThat(componentNamed("concurrencyToken").getType()).isEqualTo(String.class);
        }

        @Test
        @DisplayName("names the component identically to the returning half on the request")
        void agreesWithTheRequestHalf() {
            List<String> requestComponents =
                    Arrays.stream(AccountUpdateRequest.class.getRecordComponents())
                            .map(RecordComponent::getName)
                            .toList();

            assertThat(requestComponents).contains("concurrencyToken");
            assertThat(requestComponents.get(requestComponents.size() - 1))
                    .isEqualTo("concurrencyToken");
            assertThat(componentNames().get(componentNames().size() - 1))
                    .isEqualTo("concurrencyToken");
        }

        @Test
        @DisplayName("survives the response-to-request hand-off unchanged")
        void survivesTheHandOffUnchanged() throws JsonProcessingException {
            ObjectMapper mapper = moduleEquivalentMapper();

            String outbound = mapper.writeValueAsString(populated());
            String echoed = mapper.readValue(outbound, AccountUpdateResponse.class)
                    .concurrencyToken();

            AccountUpdateRequest returned = mapper.readValue(
                    "{\"accountId\":\"00000000011\",\"concurrencyToken\":\"" + echoed + "\"}",
                    AccountUpdateRequest.class);

            assertThat(echoed).isEqualTo(TOKEN);
            assertThat(returned.concurrencyToken()).isEqualTo(TOKEN);
        }

        @Test
        @DisplayName("is published on the wire under its own name")
        void isPublishedOnTheWire() throws JsonProcessingException {
            assertThat(payloadOf(populated()).get("concurrencyToken").asText()).isEqualTo(TOKEN);
        }

        @Test
        @DisplayName("is absent rather than empty when the response presents nothing to confirm")
        void isOmittedWhenAbsent() throws JsonProcessingException {
            AccountUpdateResponse withoutToken =
                    populated(null, LIMIT, CASH_LIMIT, BALANCE, CYCLE_CREDIT, CYCLE_DEBIT);

            assertThat(withoutToken.concurrencyToken()).isNull();
            assertThat(payloadOf(withoutToken).has("concurrencyToken")).isFalse();
        }

        @Test
        @DisplayName("carries no length constraint, because it is not a legacy field")
        void carriesNoLengthConstraint() {
            assertThat(annotationsOn("concurrencyToken")).isEmpty();
            assertThat(annotationsOn("accountId")).isNotEmpty();
        }

        @Test
        @DisplayName("is accepted at any length, so a sealed value is never truncated by contract")
        void isAcceptedAtAnyLength() {
            String longToken = "A".repeat(4096);

            assertThat(populated(longToken, LIMIT, CASH_LIMIT, BALANCE, CYCLE_CREDIT, CYCLE_DEBIT)
                    .concurrencyToken())
                    .hasSize(4096);
        }
    }

    @Nested
    @DisplayName("the decimal contract")
    class DecimalContract {

        @Test
        @DisplayName("states the record's scale and integer-digit budget as constants")
        void statesTheRecordShape() {
            assertThat(AccountUpdateResponse.MONEY_SCALE).isEqualTo(2);
            assertThat(AccountUpdateResponse.MONEY_INTEGER_DIGITS).isEqualTo(10);
            assertThat(AccountUpdateResponse.MONEY_INTEGER_DIGITS
                    + AccountUpdateResponse.MONEY_SCALE).isEqualTo(12);
        }

        @Test
        @DisplayName("publishes the record field, precision and scale on every monetary component")
        void publishesTheDecimalMetadata() {
            for (String name : MONETARY_COMPONENTS) {
                Schema schema = schemaOn(name);

                assertThat(schema)
                        .describedAs("schema documentation on %s", name)
                        .isNotNull();
                assertThat(schema.description())
                        .describedAs("description on %s", name)
                        .contains("CVACT01Y.cpy")
                        .contains("precision 12")
                        .contains("scale exactly 2");
            }
        }

        @Test
        @DisplayName("publishes no such documentation on a component that is not monetary")
        void publishesNoDecimalMetadataElsewhere() {
            assertThat(schemaOn("accountId")).isNull();
            assertThat(schemaOn("ficoScore")).isNull();
        }

        @Test
        @DisplayName("accepts every in-contract amount, including zero and a negative balance")
        void acceptsInContractAmounts() {
            AccountUpdateResponse response = populated();

            assertThat(response.creditLimit()).isEqualTo(new BigDecimal("5000.00"));
            assertThat(response.currentBalance().signum()).isNegative();
            assertThat(response.currentCycleCredit()).isEqualTo(new BigDecimal("0.00"));
            assertThat(response.creditLimit().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("accepts the widest amount the record field can hold")
        void acceptsTheWidestRecordValue() {
            BigDecimal widest = new BigDecimal("9999999999.99");

            assertThat(populated(TOKEN, widest, CASH_LIMIT, BALANCE, CYCLE_CREDIT, CYCLE_DEBIT)
                    .creditLimit())
                    .isEqualTo(widest);
            assertThat(widest.precision() - widest.scale())
                    .isEqualTo(AccountUpdateResponse.MONEY_INTEGER_DIGITS);
        }

        @Test
        @DisplayName("accepts a null amount, which is how a blank monetary field arrives")
        void acceptsANullAmount() {
            assertThat(populated(TOKEN, null, null, null, null, null).creditLimit()).isNull();
        }

        @Test
        @DisplayName("refuses an unscaled amount rather than re-scaling it")
        void refusesAnUnscaledAmount() {
            assertThatThrownBy(() -> populated(TOKEN, BigDecimal.ZERO, CASH_LIMIT, BALANCE,
                    CYCLE_CREDIT, CYCLE_DEBIT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("creditLimit")
                    .hasMessageContaining("scale 2");
        }

        @Test
        @DisplayName("refuses an over-scaled amount, naming the component that failed")
        void refusesAnOverScaledAmount() {
            assertThatThrownBy(() -> populated(TOKEN, LIMIT, CASH_LIMIT, BALANCE, CYCLE_CREDIT,
                    new BigDecimal("312.4567")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("currentCycleDebit")
                    .hasMessageContaining("scale is 4");
        }

        @Test
        @DisplayName("refuses an amount too wide for the record field")
        void refusesAnOverWideAmount() {
            assertThatThrownBy(() -> populated(TOKEN, LIMIT, CASH_LIMIT,
                    new BigDecimal("12345678901.00"), CYCLE_CREDIT, CYCLE_DEBIT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("currentBalance")
                    .hasMessageContaining("10 integer digits")
                    .hasMessageContaining("needs 11");
        }

        @Test
        @DisplayName("never discloses the offending amount in the failure text")
        void neverDisclosesTheOffendingAmount() {
            assertThatThrownBy(() -> populated(TOKEN, new BigDecimal("98765432109.87"), CASH_LIMIT,
                    BALANCE, CYCLE_CREDIT, CYCLE_DEBIT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining("98765432109");
        }

        @Test
        @DisplayName("renders a scale-two amount plainly, never in scientific notation")
        void rendersAmountsPlainly() throws JsonProcessingException {
            // Asserted against the serialised text rather than a re-parsed tree: reading the
            // payload back turns a decimal literal into a binary floating-point node, whose own
            // rendering is exponential, which would test the parser instead of the contract.
            String payload = moduleEquivalentMapper().writeValueAsString(
                    populated(TOKEN, new BigDecimal("9999999999.99"), CASH_LIMIT, BALANCE,
                            CYCLE_CREDIT, CYCLE_DEBIT));

            assertThat(payload).contains("\"creditLimit\":9999999999.99");
            assertThat(payload).contains("\"currentCycleCredit\":0.00");
            assertThat(payload.toLowerCase(Locale.ROOT)).doesNotContain("e+");
        }
    }

    @Nested
    @DisplayName("diagnostic rendering")
    class DiagnosticRendering {

        @Test
        @DisplayName("withholds the conversation token, which is a capability and not a value")
        void withholdsTheToken() {
            assertThat(populated().toString()).doesNotContain(TOKEN);
        }

        @Test
        @DisplayName("withholds every regulated value and every monetary amount")
        void withholdsEveryValue() {
            String rendered = populated().toString();

            assertThat(rendered)
                    .doesNotContain("Vandelay")
                    .doesNotContain("6789")
                    .doesNotContain("P1234567890123456789")
                    .doesNotContain("EFT0000001")
                    .doesNotContain("5000.00")
                    .doesNotContain("247.83")
                    .doesNotContain("00000000011")
                    .doesNotContain("000000011")
                    .doesNotContain("Corporate Way");
        }

        @Test
        @DisplayName("retains the control state a reader needs, and the flagged-field count")
        void retainsTheControlState() {
            String rendered = populated().toString();

            assertThat(rendered)
                    .startsWith("AccountUpdateResponse[")
                    .contains("error=false")
                    .contains("focusScreenFieldId=ACSTTUS")
                    .contains("fieldErrorCount=1")
                    .contains("***REDACTED***");
        }
    }

    @Nested
    @DisplayName("unchanged obligations")
    class UnchangedObligations {

        @Test
        @DisplayName("leaves the two decorated-but-unvalidated components unconstrained")
        void leavesTheUneditedComponentsUnconstrained() {
            // The source says so outright: "no edits coded" at COACTUPC:3345 for the middle name
            // and "NO EDITS CODED AS YET" at 3369 for the second address line. Both are decorated
            // for display and neither is ever checked, so constraining either would reject input
            // the legacy accepts.
            assertThat(annotationsOn("middleName")).isEmpty();
            assertThat(annotationsOn("addressLine2")).isEmpty();
            assertThat(annotationsOn("firstName")).isNotEmpty();
        }

        @Test
        @DisplayName("places no range constraint on the credit score, which really can be 001")
        void placesNoRangeConstraintOnTheCreditScore() {
            assertThat(Arrays.stream(annotationsOn("ficoScore"))
                    .map(annotation -> annotation.annotationType().getSimpleName()))
                    .containsExactly("Size");
        }

        @Test
        @DisplayName("normalises an absent field-error collection to an empty immutable one")
        void normalisesTheFieldErrorCollection() {
            AccountUpdateResponse response = new AccountUpdateResponse(
                    null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, false, null, null, null, null, null);

            assertThat(response.fieldErrors()).isEmpty();
            assertThat(response.hasFieldErrors()).isFalse();
            assertThatThrownBy(() -> response.fieldErrors().add(null))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("keeps the quirky message vocabulary byte-exact")
        void keepsTheMessageVocabularyByteExact() {
            assertThat(AccountUpdateResponse.MSG_LOOKS_GOOD_SO_FAR).isEqualTo("Looks Good.... so far");
            assertThat(AccountUpdateResponse.MSG_RECORD_CHANGED_BEFORE_UPDATE)
                    .isEqualTo("Record changed by some one else. Please review");
            assertThat(AccountUpdateResponse.SUFFIX_STATE_NOT_VALID)
                    .isEqualTo(": is not a valid state code");
            assertThat(AccountUpdateResponse.MSG_INVALID_ZIP_FOR_STATE)
                    .isEqualTo("Invalid zip code for state");
        }

        @Test
        @DisplayName("uses the canonical wire vocabulary and no superseded spelling")
        void usesTheCanonicalWireVocabulary() {
            assertThat(componentNames())
                    .contains("title01", "title02", "nextRoute", "navigationContext",
                            "focusScreenFieldId")
                    .doesNotContain("screenTitle1", "screenTitle2", "screenTitleLine1",
                            "titleLine1", "route", "navigation", "fieldToFocus", "focusField");
        }

        @Test
        @DisplayName("compares by value and round-trips through the wire unchanged")
        void comparesByValueAndRoundTrips() throws JsonProcessingException {
            ObjectMapper mapper = moduleEquivalentMapper();
            AccountUpdateResponse original = populated();

            AccountUpdateResponse revived = mapper.readValue(
                    mapper.writeValueAsString(original), AccountUpdateResponse.class);

            assertThat(revived).isEqualTo(original);
            assertThat(revived).hasSameHashCodeAs(original);
        }
    }
}
