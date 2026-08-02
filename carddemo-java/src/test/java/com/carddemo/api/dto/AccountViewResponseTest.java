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
import java.math.BigDecimal;
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
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AccountViewResponse}, the display-only response body of legacy transaction
 * {@code CAVW}.
 *
 * <p>A pure unit test: no application context, no connection, no container. Where the wire shape is
 * what is under test it serialises with a locally built mapper configured to match the settings the
 * module declares in {@code application.yml}.
 *
 * <p>Three properties dominate what is checked, because all three were wrong and each is
 * independently observable.
 *
 * <p>The first is disclosure. This record assembles an unusually complete picture of one person onto
 * a single object, and its generated rendering emitted all of it. The tests here prove the values are
 * still transported untouched - which the source requires, since the screen displayed them - while
 * the diagnostic path withholds them.
 *
 * <p>The second is the decimal contract. The five monetary items carry the screen's numeric-edited
 * picture on the map, and their record counterparts are the signed zoned decimals at
 * {@code app/cpy/CVACT01Y.cpy} lines 7, 8, 9, 13 and 14: ten integer digits and two decimal places.
 *
 * <p>The third is the focus identifier's width. No {@code DFHMDF} field name in any of the estate's
 * seventeen mapsets exceeds seven characters, because the map generator reserves the eighth position
 * of a symbolic name for the suffix it appends; the widest names in this mapset are exactly seven.
 * A bound of eight admitted values that could never name a field.
 *
 * @since 1.0.0
 */
@DisplayName("AccountViewResponse")
class AccountViewResponseTest {

    /** The map order this record follows, restated as an independent oracle. */
    private static final List<String> COMPONENTS_IN_MAP_ORDER = List.of(
            "transactionName", "title01", "currentDate", "programName", "title02", "currentTime",
            "accountId", "accountStatus", "openDate", "creditLimit", "expirationDate",
            "cashCreditLimit", "reissueDate", "currentBalance", "currentCycleCredit",
            "accountGroupId", "currentCycleDebit", "customerId", "ssn", "dateOfBirth", "ficoScore",
            "firstName", "middleName", "lastName", "addressLine1", "stateCode", "addressLine2",
            "zipCode", "city", "countryCode", "phoneNumber1", "governmentIssuedId", "phoneNumber2",
            "eftAccountId", "primaryCardHolderIndicator", "infoMessage", "errorMessage",
            "inputError", "focusScreenFieldId", "nextRoute", "navigationContext");

    /** The five components whose record counterparts are signed two-decimal zoned decimals. */
    private static final List<String> MONETARY_COMPONENTS = List.of(
            "creditLimit", "cashCreditLimit", "currentBalance", "currentCycleCredit",
            "currentCycleDebit");

    /** The account group id really is ten spaces in all fifty seeded rows, and that is the value. */
    private static final String TEN_SPACES = "          ";

    private static final BigDecimal LIMIT = new BigDecimal("5000.00");
    private static final BigDecimal CASH_LIMIT = new BigDecimal("1500.00");
    private static final BigDecimal BALANCE = new BigDecimal("-247.83");
    private static final BigDecimal CYCLE_CREDIT = new BigDecimal("0.00");
    private static final BigDecimal CYCLE_DEBIT = new BigDecimal("312.45");

    /**
     * Builds a fully populated response with the supplied monetary values and focus identifier.
     *
     * <p>Space padding on the name and address entries is deliberate: those map items are
     * fixed-width and space-significant, so a value that came back shortened would be a defect.
     *
     * @param focusScreenFieldId the focus hint to carry
     * @param creditLimit the credit limit to carry
     * @param cashCreditLimit the cash credit limit to carry
     * @param currentBalance the current balance to carry
     * @param currentCycleCredit the current cycle credit to carry
     * @param currentCycleDebit the current cycle debit to carry
     * @return a populated response
     */
    private static AccountViewResponse populated(String focusScreenFieldId,
            BigDecimal creditLimit,
            BigDecimal cashCreditLimit,
            BigDecimal currentBalance,
            BigDecimal currentCycleCredit,
            BigDecimal currentCycleDebit) {
        return new AccountViewResponse(
                "CAVW", "Account View", "08/02/26", "COACTVWC", "CardDemo", "10:15:30",
                "00000000011", "Y", "2020-01-15", creditLimit, "2028-12-31", cashCreditLimit,
                "2024-06-30", currentBalance, currentCycleCredit, TEN_SPACES, currentCycleDebit,
                "000000011", "123-45-6789", "1985-03-22", "001",
                "Mary                     ", "Ann                      ",
                "Vandelay                 ",
                "1 Corporate Way                                   ", "NY",
                "Suite 400                                         ", "10118",
                "New York                                          ", "USA",
                "(212)555-0143", "P1234567890123456789", "(347)555-0199", "EFT0000001", "Y",
                "Press PF3 to return                          ",
                "Account:00000000011 not found in Cross ref file.",
                true, focusScreenFieldId, "/api/accounts/view", NavigationContext.empty());
    }

    /** Builds the canonical populated response. */
    private static AccountViewResponse populated() {
        return populated("ACCTSID", LIMIT, CASH_LIMIT, BALANCE, CYCLE_CREDIT, CYCLE_DEBIT);
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

    private static JsonNode payloadOf(AccountViewResponse response) throws JsonProcessingException {
        ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(response));
    }

    private static List<String> componentNames() {
        return Arrays.stream(AccountViewResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    /**
     * Returns the annotations a component actually carries at run time.
     *
     * <p>Read from the backing field rather than from the record component. Neither the length
     * constraint nor the schema annotation declares {@code RECORD_COMPONENT} among its targets, so
     * the compiler propagates each to the field, the accessor and the constructor parameter but
     * records none against the component itself: asking the component yields an empty array for
     * every component here, and an assertion phrased that way would pass without testing anything.
     *
     * @param name the component name
     * @return the annotations present on the backing field
     */
    private static Annotation[] annotationsOn(String name) {
        try {
            return AccountViewResponse.class.getDeclaredField(name).getAnnotations();
        } catch (NoSuchFieldException absent) {
            throw new AssertionError("no component named " + name, absent);
        }
    }

    private static <A extends Annotation> A annotationOn(String name, Class<A> type) {
        try {
            return AccountViewResponse.class.getDeclaredField(name).getAnnotation(type);
        } catch (NoSuchFieldException absent) {
            throw new AssertionError("no component named " + name, absent);
        }
    }

    private static Set<ConstraintViolation<AccountViewResponse>> violationsOf(
            AccountViewResponse response) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(response);
        }
    }

    @Nested
    @DisplayName("component inventory")
    class ComponentInventory {

        @Test
        @DisplayName("declares the thirty-seven map items followed by the four control components")
        void declaresTheExpectedComponentsInOrder() {
            assertThat(componentNames()).containsExactlyElementsOf(COMPONENTS_IN_MAP_ORDER);
            assertThat(componentNames()).hasSize(41);
        }

        @Test
        @DisplayName("names both title lines for the map items they carry")
        void namesBothTitleLinesForTheMapItems() {
            assertThat(componentNames())
                    .contains("title01", "title02")
                    .doesNotContain("screenTitle1", "screenTitle2", "screenTitleLine1",
                            "screenTitleLine2", "titleLine1", "titleLine2");
        }

        @Test
        @DisplayName("uses the canonical spelling for every control component")
        void usesTheCanonicalControlSpellings() {
            assertThat(componentNames())
                    .contains("focusScreenFieldId", "nextRoute", "navigationContext")
                    .doesNotContain("fieldToFocus", "focusField", "focusFieldName", "route",
                            "navigation");
        }

        @Test
        @DisplayName("agrees with the sibling update response on every shared control name")
        void agreesWithTheUpdateResponse() {
            List<String> updateComponents =
                    Arrays.stream(AccountUpdateResponse.class.getRecordComponents())
                            .map(RecordComponent::getName)
                            .toList();

            assertThat(updateComponents).containsAll(
                    List.of("title01", "title02", "focusScreenFieldId", "nextRoute",
                            "navigationContext", "transactionName", "currentDate", "programName",
                            "currentTime"));
        }

        @Test
        @DisplayName("carries no numeric identifier, so a leading-zero value survives")
        void carriesNoNumericIdentifier() {
            assertThat(Arrays.stream(AccountViewResponse.class.getRecordComponents())
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
            assertThat(Arrays.stream(AccountViewResponse.class.getRecordComponents())
                    .filter(component -> component.getType() == BigDecimal.class)
                    .map(RecordComponent::getName))
                    .containsExactlyElementsOf(MONETARY_COMPONENTS);

            assertThat(Arrays.stream(AccountViewResponse.class.getRecordComponents())
                    .map(component -> component.getType().getName())
                    .filter(type -> type.contains("ouble") || type.contains("loat")))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("the focus identifier width")
    class FocusIdentifierWidth {

        @Test
        @DisplayName("is bounded at seven, the widest field name any mapset declares")
        void isBoundedAtSeven() {
            assertThat(annotationOn("focusScreenFieldId", Size.class).max()).isEqualTo(7);
        }

        @Test
        @DisplayName("admits a seven-character identifier, which is what this screen nominates")
        void admitsSevenCharacters() {
            assertThat(violationsOf(populated("ACCTSID", LIMIT, CASH_LIMIT, BALANCE, CYCLE_CREDIT,
                    CYCLE_DEBIT))).isEmpty();
        }

        @Test
        @DisplayName("rejects an eighth character, which could never name a field")
        void rejectsAnEighthCharacter() {
            Set<ConstraintViolation<AccountViewResponse>> violations = violationsOf(
                    populated("ACCTSIDX", LIMIT, CASH_LIMIT, BALANCE, CYCLE_CREDIT, CYCLE_DEBIT));

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath())
                    .hasToString("focusScreenFieldId");
        }

        @Test
        @DisplayName("carries the same bound the other screen contracts carry")
        void carriesTheSharedBound() throws NoSuchFieldException {
            int updateResponseBound = AccountUpdateResponse.class
                    .getDeclaredField("focusScreenFieldId").getAnnotation(Size.class).max();

            assertThat(annotationOn("focusScreenFieldId", Size.class).max())
                    .isEqualTo(updateResponseBound)
                    .isEqualTo(MenuResponse.SCREEN_FIELD_ID_WIDTH)
                    .isEqualTo(SignOnResponse.SCREEN_FIELD_ID_LENGTH);
        }

        @Test
        @DisplayName("shares the name with the foundation error carrier, which bounds nothing")
        void sharesTheNameWithTheErrorCarrier() throws NoSuchFieldException {
            // ErrorResponse names the same concept and deliberately leaves it unbounded, and that
            // is the reviewed state of a foundation type this change does not touch. What has to
            // agree across the package is the name, so a client reads one concept and not five.
            assertThat(ErrorResponse.class.getDeclaredField("focusScreenFieldId")
                    .getAnnotation(Size.class)).isNull();
            assertThat(componentNames()).contains("focusScreenFieldId");
        }

        @Test
        @DisplayName("leaves the route unbounded, because a route has no legacy width")
        void leavesTheRouteUnbounded() {
            assertThat(annotationsOn("nextRoute")).isEmpty();
        }
    }

    @Nested
    @DisplayName("the decimal contract")
    class DecimalContract {

        @Test
        @DisplayName("states the record's scale and integer-digit budget as constants")
        void statesTheRecordShape() {
            assertThat(AccountViewResponse.MONEY_SCALE).isEqualTo(2);
            assertThat(AccountViewResponse.MONEY_INTEGER_DIGITS).isEqualTo(10);
            assertThat(AccountViewResponse.MONEY_SCALE)
                    .isEqualTo(AccountUpdateResponse.MONEY_SCALE);
            assertThat(AccountViewResponse.MONEY_INTEGER_DIGITS)
                    .isEqualTo(AccountUpdateResponse.MONEY_INTEGER_DIGITS);
        }

        @Test
        @DisplayName("publishes the record field, precision and scale on every monetary component")
        void publishesTheDecimalMetadata() {
            for (String name : MONETARY_COMPONENTS) {
                Schema schema = annotationOn(name, Schema.class);

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
            assertThat(annotationOn("accountId", Schema.class)).isNull();
            assertThat(annotationOn("ficoScore", Schema.class)).isNull();
        }

        @Test
        @DisplayName("never reproduces the screen's numeric-edited picture")
        void neverReproducesTheScreenEdit() throws JsonProcessingException {
            String payload = moduleEquivalentMapper().writeValueAsString(populated());

            assertThat(payload).doesNotContain("ZZZ").doesNotContain("+5,000");
            assertThat(payload).contains("\"creditLimit\":5000.00");
        }

        @Test
        @DisplayName("accepts every in-contract amount, including zero and a negative balance")
        void acceptsInContractAmounts() {
            AccountViewResponse response = populated();

            assertThat(response.currentBalance()).isEqualTo(new BigDecimal("-247.83"));
            assertThat(response.currentCycleCredit()).isEqualTo(new BigDecimal("0.00"));
            assertThat(response.cashCreditLimit().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("accepts the widest amount the record field can hold")
        void acceptsTheWidestRecordValue() {
            BigDecimal widest = new BigDecimal("-9999999999.99");

            assertThat(populated("ACCTSID", LIMIT, CASH_LIMIT, widest, CYCLE_CREDIT, CYCLE_DEBIT)
                    .currentBalance())
                    .isEqualTo(widest);
        }

        @Test
        @DisplayName("accepts a null amount, which is how a blank monetary field is displayed")
        void acceptsANullAmount() {
            assertThat(populated("ACCTSID", null, null, null, null, null).creditLimit()).isNull();
        }

        @Test
        @DisplayName("refuses an unscaled amount rather than re-scaling it")
        void refusesAnUnscaledAmount() {
            assertThatThrownBy(() -> populated("ACCTSID", BigDecimal.TEN, CASH_LIMIT, BALANCE,
                    CYCLE_CREDIT, CYCLE_DEBIT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("creditLimit")
                    .hasMessageContaining("scale 2");
        }

        @Test
        @DisplayName("refuses an over-scaled amount, naming the component that failed")
        void refusesAnOverScaledAmount() {
            assertThatThrownBy(() -> populated("ACCTSID", LIMIT, new BigDecimal("1500.000"),
                    BALANCE, CYCLE_CREDIT, CYCLE_DEBIT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cashCreditLimit")
                    .hasMessageContaining("scale is 3");
        }

        @Test
        @DisplayName("refuses an amount too wide for the record field")
        void refusesAnOverWideAmount() {
            assertThatThrownBy(() -> populated("ACCTSID", LIMIT, CASH_LIMIT, BALANCE, CYCLE_CREDIT,
                    new BigDecimal("12345678901.00")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("currentCycleDebit")
                    .hasMessageContaining("10 integer digits")
                    .hasMessageContaining("needs 11");
        }

        @Test
        @DisplayName("never discloses the offending amount in the failure text")
        void neverDisclosesTheOffendingAmount() {
            assertThatThrownBy(() -> populated("ACCTSID", new BigDecimal("98765432109.87"),
                    CASH_LIMIT, BALANCE, CYCLE_CREDIT, CYCLE_DEBIT))
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
                    populated("ACCTSID", new BigDecimal("9999999999.99"), CASH_LIMIT, BALANCE,
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
        @DisplayName("withholds the national identifier and the government-issued identifier")
        void withholdsTheRegulatedIdentifiers() {
            String rendered = populated().toString();

            assertThat(rendered)
                    .doesNotContain("123-45-6789")
                    .doesNotContain("6789")
                    .doesNotContain("P1234567890123456789");
        }

        @Test
        @DisplayName("withholds the names, the address, both telephone numbers and the birth date")
        void withholdsThePersonalPayload() {
            String rendered = populated().toString();

            assertThat(rendered)
                    .doesNotContain("Vandelay")
                    .doesNotContain("Mary")
                    .doesNotContain("Corporate Way")
                    .doesNotContain("New York")
                    .doesNotContain("10118")
                    .doesNotContain("(212)555-0143")
                    .doesNotContain("(347)555-0199")
                    .doesNotContain("1985-03-22")
                    .doesNotContain("001");
        }

        @Test
        @DisplayName("withholds every monetary value and the transfer-account identifier")
        void withholdsTheFinancialPayload() {
            String rendered = populated().toString();

            assertThat(rendered)
                    .doesNotContain("5000.00")
                    .doesNotContain("1500.00")
                    .doesNotContain("247.83")
                    .doesNotContain("312.45")
                    .doesNotContain("EFT0000001");
        }

        @Test
        @DisplayName("withholds the join keys, so two log lines cannot reassemble the subject")
        void withholdsTheJoinKeys() {
            String rendered = populated().toString();

            assertThat(rendered).doesNotContain("00000000011").doesNotContain("000000011");
        }

        @Test
        @DisplayName("withholds both message lines, because this program builds keys into them")
        void withholdsBothMessageLines() {
            // COACTVWC.cbl 747-757 concatenates the account identifier into the not-found text,
            // and the two sibling texts at 796-806 and 846-856 are built the same way. Rendering a
            // message line would put back exactly what withholding the identifiers removed.
            String rendered = populated().toString();

            assertThat(rendered)
                    .doesNotContain("not found in Cross ref file")
                    .doesNotContain("Press PF3 to return");
        }

        @Test
        @DisplayName("retains the control state a reader needs")
        void retainsTheControlState() {
            String rendered = populated().toString();

            assertThat(rendered)
                    .startsWith("AccountViewResponse[")
                    .contains("inputError=true")
                    .contains("focusScreenFieldId=ACCTSID")
                    .contains("nextRoute=/api/accounts/view")
                    .contains("***REDACTED***");
        }

        @Test
        @DisplayName("changes nothing that is transported: every accessor still returns the value")
        void changesNothingTransported() {
            AccountViewResponse response = populated();

            assertThat(response.ssn()).isEqualTo("123-45-6789");
            assertThat(response.governmentIssuedId()).isEqualTo("P1234567890123456789");
            assertThat(response.eftAccountId()).isEqualTo("EFT0000001");
            assertThat(response.errorMessage())
                    .isEqualTo("Account:00000000011 not found in Cross ref file.");
        }

        @Test
        @DisplayName("changes nothing on the wire either: the payload still carries every value")
        void changesNothingOnTheWire() throws JsonProcessingException {
            JsonNode payload = payloadOf(populated());

            assertThat(payload.get("ssn").asText()).isEqualTo("123-45-6789");
            assertThat(payload.get("governmentIssuedId").asText())
                    .isEqualTo("P1234567890123456789");
            assertThat(payload.get("accountId").asText()).isEqualTo("00000000011");
            assertThat(payload.get("ficoScore").asText()).isEqualTo("001");
        }
    }

    @Nested
    @DisplayName("display-only obligations")
    class DisplayOnlyObligations {

        @Test
        @DisplayName("places no range constraint on the credit score, which really can be 001")
        void placesNoRangeConstraintOnTheCreditScore() {
            assertThat(Arrays.stream(annotationsOn("ficoScore"))
                    .map(annotation -> annotation.annotationType().getSimpleName()))
                    .containsExactly("Size");

            assertThat(violationsOf(populated())).isEmpty();
        }

        @Test
        @DisplayName("keeps the width bounds on the two components the update side leaves unedited")
        void keepsTheWidthBoundsOnTheUneditedComponents() {
            // A width measurement on a display-only response is not a business edit, and this is
            // the state the review accepted: the prohibition is on presence, pattern and format
            // constraints on the request side, where both components really are unannotated.
            assertThat(annotationOn("middleName", Size.class).max()).isEqualTo(25);
            assertThat(annotationOn("addressLine2", Size.class).max()).isEqualTo(50);

            assertThat(AccountUpdateRequest.class.getRecordComponents()).isNotEmpty();
            assertThat(annotationsOn("middleName")).hasSize(1);
            assertThat(annotationsOn("addressLine2")).hasSize(1);
        }

        @Test
        @DisplayName("declares no presence, pattern or bound constraint anywhere")
        void declaresNoPresenceOrPatternConstraint() {
            Set<String> permitted = Set.of("Size", "Schema");

            assertThat(Arrays.stream(AccountViewResponse.class.getDeclaredFields())
                    .flatMap(field -> Arrays.stream(field.getAnnotations()))
                    .map(annotation -> annotation.annotationType().getSimpleName())
                    .distinct())
                    .allSatisfy(name -> assertThat(permitted).contains(name));
        }

        @Test
        @DisplayName("keeps the ten-space group id exactly as stored, spaces and all")
        void keepsTheTenSpaceGroupId() throws JsonProcessingException {
            assertThat(populated().accountGroupId()).isEqualTo(TEN_SPACES).hasSize(10);
            assertThat(payloadOf(populated()).get("accountGroupId").asText()).isEqualTo(TEN_SPACES);
        }

        @Test
        @DisplayName("keeps every space-padded name and address value at its declared width")
        void keepsEverySpacePaddedValue() {
            AccountViewResponse response = populated();

            assertThat(response.firstName()).hasSize(25).endsWith(" ");
            assertThat(response.lastName()).hasSize(25);
            assertThat(response.addressLine1()).hasSize(50);
            assertThat(response.city()).hasSize(50);
        }

        @Test
        @DisplayName("resolves an undeclared status character to nothing rather than throwing")
        void resolvesAnUndeclaredStatusWithoutThrowing() {
            AccountViewResponse response = new AccountViewResponse(
                    null, null, null, null, null, null, null, "Q", null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, false, null,
                    null, null);

            assertThat(response.accountStatus()).isEqualTo("Q");
            assertThat(response.resolvedAccountStatus()).isEmpty();
            assertThat(response.active()).isFalse();
        }

        @Test
        @DisplayName("compares by value and round-trips through the wire unchanged")
        void comparesByValueAndRoundTrips() throws JsonProcessingException {
            ObjectMapper mapper = moduleEquivalentMapper();
            AccountViewResponse original = populated();

            AccountViewResponse revived = mapper.readValue(
                    mapper.writeValueAsString(original), AccountViewResponse.class);

            assertThat(revived).isEqualTo(original);
            assertThat(revived).hasSameHashCodeAs(original);
        }
    }
}
