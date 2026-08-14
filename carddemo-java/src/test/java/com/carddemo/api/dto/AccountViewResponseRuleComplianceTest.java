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
import java.util.Optional;

import com.carddemo.domain.enums.AccountStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AccountViewResponse}, the response body of legacy transaction {@code CAVW}
 * implemented by {@code app/cbl/COACTVWC.cbl} over screen {@code app/cpy-bms/COACTVW.CPY}.
 *
 * <p><strong>Why the component order is asserted.</strong> The declared order is not alphabetical and
 * not grouped by entity - it is the field order of the 3270 map, which interleaves account and customer
 * fields down the screen and places the credit limit before the expiration date. Sorting the components
 * into something tidier would compile, pass every other test here, and quietly renumber the payload a
 * client reads positionally. The order is therefore pinned.
 *
 * <p><strong>Five monetary components.</strong> Every one is a {@link BigDecimal} because the legacy
 * fields are zoned decimal at {@code PIC S9(10)V99}, and the plan forbids floating-point substitution.
 * The scale is asserted to survive both construction and the payload, because a scale silently
 * normalised to one digit would render {@code 1000.00} as {@code 1000.0}.
 *
 * @see AccountViewResponse
 */
@DisplayName("AccountViewResponse - the CAVW account view response contract")
class AccountViewResponseRuleComplianceTest {

    /** The account identifier used throughout, eleven digits as the legacy key is. */
    private static final String ACCOUNT_ID = "00000000011";

    /**
     * A mapper configured exactly as {@code application.yml} configures the application's own.
     *
     * @return the module-equivalent mapper
     */
    private static ObjectMapper moduleEquivalentMapper() {
        return JsonMapper.builder()
                .defaultPropertyInclusion(JsonInclude.Value.construct(
                        JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .build();
    }

    /**
     * Serializes a response and reads the result back as a tree.
     *
     * @param response the response to render
     * @return the rendered payload
     * @throws JsonProcessingException when the payload cannot be produced
     */
    private static JsonNode payloadOf(final AccountViewResponse response)
            throws JsonProcessingException {
        final ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(response));
    }

    /**
     * Serializes a response to the exact characters that go on the wire.
     *
     * <p>Read as text rather than as a tree whenever the assertion is about how a number is
     * <em>written</em>. Parsing the payload back into a tree hands the question to the reader, which
     * binds a JSON float to a double by default and renders it in scientific notation on the way out -
     * so a tree-based assertion would report {@code 1.00000000005E9} for a payload that in fact carries
     * {@code 1000000000.05}, failing a contract the module actually honours.
     *
     * @param response the response to render
     * @return the rendered payload, verbatim
     * @throws JsonProcessingException when the payload cannot be produced
     */
    private static String wirePayloadOf(final AccountViewResponse response)
            throws JsonProcessingException {
        return moduleEquivalentMapper().writeValueAsString(response);
    }

    /**
     * Builds a fully populated response, every component at or inside its declared width.
     *
     * @param accountStatus the one-character account status to carry
     * @return the populated response
     */
    private static AccountViewResponse aResponse(final String accountStatus) {
        return new AccountViewResponse("CAVW", "View Account", "01/15/22", "COACTVWC",
                "AWS CardDemo", "10:30:00", ACCOUNT_ID, accountStatus, "2020-01-15",
                new BigDecimal("5000.00"), "2025-01-15", new BigDecimal("1000.00"), "2023-01-15",
                new BigDecimal("1234.56"), new BigDecimal("100.00"), "GROUP-A",
                new BigDecimal("50.00"), "000000011", "123-45-6789", "1980-06-30", "720", "JOHN",
                "Q", "PUBLIC", "1 MAIN ST", "NY", "APT 2", "10001", "NEW YORK", "USA",
                "212-555-0100", "DL-987654321", "212-555-0101", "EFT0000001", "Y",
                "Displaying account", null, false, List.of(), "ACCTSID", "account-view",
                NavigationContext.empty());
    }

    /**
     * Builds a response carrying only the named components, every other component absent.
     *
     * <p>Declared once so that a single positional constructor call has to be right, rather than four
     * near-identical walls of nulls each of which could drift a component out of line without the
     * compiler noticing - every component after the first is nullable and most are the same type.
     *
     * @param transactionName the transaction name to carry, or {@code null}
     * @param accountId       the account identifier to carry, or {@code null}
     * @param accountStatus   the account status code to carry, or {@code null}
     * @param ficoScore       the credit score to carry, or {@code null}
     * @param creditLimit     the credit limit to carry, or {@code null}
     * @param currentBalance  the current balance to carry, or {@code null}
     * @return the sparse response
     */
    private static AccountViewResponse aSparseResponse(final String transactionName,
            final String accountId, final String accountStatus, final String ficoScore,
            final BigDecimal creditLimit, final BigDecimal currentBalance) {

        return new AccountViewResponse(
                transactionName, null, null, null, null,
                null, accountId, accountStatus, null, creditLimit,
                null, null, null, currentBalance, null,
                null, null, null, null, null,
                ficoScore, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, false, List.of(), null, null,
                null);
    }

    /**
     * Reads the declared upper bound of a named component's accessor.
     *
     * @param componentName the record component whose accessor carries the annotation
     * @return the declared maximum length
     * @throws NoSuchMethodException when no such accessor is declared
     */
    private static int declaredMaximumLength(final String componentName)
            throws NoSuchMethodException {
        final Size size = AccountViewResponse.class.getDeclaredMethod(componentName)
                .getAnnotation(Size.class);
        assertThat(size).as("component %s declares no upper bound", componentName).isNotNull();
        return size.max();
    }

    // =============================================================================================

    @Nested
    @DisplayName("the declared shape, in screen order")
    class TheDeclaredShapeInScreenOrder {

        @Test
        @DisplayName("the response declares forty-two components in the map's own field order")
        void theResponseDeclaresFortyTwoComponentsInMapOrder() {
            final List<String> declared =
                    Arrays.stream(AccountViewResponse.class.getRecordComponents())
                            .map(RecordComponent::getName).toList();

            assertThat(declared).containsExactly("transactionName", "title01", "currentDate",
                    "programName", "title02", "currentTime", "accountId", "accountStatus",
                    "openDate", "creditLimit", "expirationDate", "cashCreditLimit", "reissueDate",
                    "currentBalance", "currentCycleCredit", "accountGroupId", "currentCycleDebit",
                    "customerId", "ssn", "dateOfBirth", "ficoScore", "firstName", "middleName",
                    "lastName", "addressLine1", "stateCode", "addressLine2", "zipCode", "city",
                    "countryCode", "phoneNumber1", "governmentIssuedId", "phoneNumber2",
                    "eftAccountId", "primaryCardHolderIndicator", "infoMessage", "errorMessage",
                    "inputError", "fieldErrors", "focusScreenFieldId", "nextRoute",
                    "navigationContext");
            assertThat(declared).hasSize(42);
        }

        @Test
        @DisplayName("exactly five components are monetary, and every one is a BigDecimal rather than a "
                + "binary floating-point type")
        void exactlyFiveComponentsAreMonetaryAndAllAreBigDecimal() {
            final List<String> monetary =
                    Arrays.stream(AccountViewResponse.class.getRecordComponents())
                            .filter(component -> component.getType() == BigDecimal.class)
                            .map(RecordComponent::getName).toList();

            assertThat(monetary).containsExactly("creditLimit", "cashCreditLimit", "currentBalance",
                    "currentCycleCredit", "currentCycleDebit");
            assertThat(Arrays.stream(AccountViewResponse.class.getRecordComponents())
                    .map(RecordComponent::getType))
                    .doesNotContain(double.class, float.class, Double.class, Float.class);
        }

        @Test
        @DisplayName("the only primitive component is the input-error flag, because the screen has one "
                + "boolean and every other field is text or an amount")
        void theOnlyPrimitiveComponentIsTheInputErrorFlag() {
            assertThat(Arrays.stream(AccountViewResponse.class.getRecordComponents())
                    .filter(component -> component.getType().isPrimitive())
                    .map(RecordComponent::getName).toList())
                    .containsExactly("inputError");
        }

        @Test
        @DisplayName("every component round-trips through its own accessor unchanged")
        void everyComponentRoundTripsThroughItsAccessor() {
            final AccountViewResponse response = aResponse("Y");

            assertThat(response.transactionName()).isEqualTo("CAVW");
            assertThat(response.title01()).isEqualTo("View Account");
            assertThat(response.currentDate()).isEqualTo("01/15/22");
            assertThat(response.programName()).isEqualTo("COACTVWC");
            assertThat(response.title02()).isEqualTo("AWS CardDemo");
            assertThat(response.currentTime()).isEqualTo("10:30:00");
            assertThat(response.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(response.accountStatus()).isEqualTo("Y");
            assertThat(response.openDate()).isEqualTo("2020-01-15");
            assertThat(response.expirationDate()).isEqualTo("2025-01-15");
            assertThat(response.reissueDate()).isEqualTo("2023-01-15");
            assertThat(response.accountGroupId()).isEqualTo("GROUP-A");
            assertThat(response.customerId()).isEqualTo("000000011");
            assertThat(response.ssn()).isEqualTo("123-45-6789");
            assertThat(response.dateOfBirth()).isEqualTo("1980-06-30");
            assertThat(response.ficoScore()).isEqualTo("720");
            assertThat(response.firstName()).isEqualTo("JOHN");
            assertThat(response.middleName()).isEqualTo("Q");
            assertThat(response.lastName()).isEqualTo("PUBLIC");
            assertThat(response.addressLine1()).isEqualTo("1 MAIN ST");
            assertThat(response.stateCode()).isEqualTo("NY");
            assertThat(response.addressLine2()).isEqualTo("APT 2");
            assertThat(response.zipCode()).isEqualTo("10001");
            assertThat(response.city()).isEqualTo("NEW YORK");
            assertThat(response.countryCode()).isEqualTo("USA");
            assertThat(response.phoneNumber1()).isEqualTo("212-555-0100");
            assertThat(response.governmentIssuedId()).isEqualTo("DL-987654321");
            assertThat(response.phoneNumber2()).isEqualTo("212-555-0101");
            assertThat(response.eftAccountId()).isEqualTo("EFT0000001");
            assertThat(response.primaryCardHolderIndicator()).isEqualTo("Y");
            assertThat(response.infoMessage()).isEqualTo("Displaying account");
            assertThat(response.errorMessage()).isNull();
            assertThat(response.inputError()).isFalse();
            assertThat(response.focusScreenFieldId()).isEqualTo("ACCTSID");
            assertThat(response.nextRoute()).isEqualTo("account-view");
            assertThat(response.navigationContext()).isEqualTo(NavigationContext.empty());
        }

        @Test
        @DisplayName("the five monetary accessors return the amounts they were given, scale included")
        void theMonetaryAccessorsReturnTheirAmounts() {
            final AccountViewResponse response = aResponse("Y");

            assertThat(response.creditLimit()).isEqualByComparingTo("5000.00");
            assertThat(response.cashCreditLimit()).isEqualByComparingTo("1000.00");
            assertThat(response.currentBalance()).isEqualByComparingTo("1234.56");
            assertThat(response.currentCycleCredit()).isEqualByComparingTo("100.00");
            assertThat(response.currentCycleDebit()).isEqualByComparingTo("50.00");
            assertThat(response.creditLimit().scale()).isEqualTo(2);
            assertThat(response.currentBalance().scale()).isEqualTo(2);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the two derived status readings")
    class TheTwoDerivedStatusReadings {

        @Test
        @DisplayName("Y resolves to active and reports active")
        void yResolvesToActiveAndReportsActive() {
            final AccountViewResponse response = aResponse("Y");

            assertThat(response.resolvedAccountStatus()).contains(AccountStatus.ACTIVE);
            assertThat(response.active()).isTrue();
        }

        @Test
        @DisplayName("N resolves to inactive and reports not active, which is a resolution rather than a "
                + "failure to resolve")
        void nResolvesToInactiveAndReportsNotActive() {
            final AccountViewResponse response = aResponse("N");

            assertThat(response.resolvedAccountStatus()).contains(AccountStatus.INACTIVE);
            assertThat(response.active()).isFalse();
        }

        @ParameterizedTest(name = "status [{0}]")
        @ValueSource(strings = {"y", "n", "A", "0", " ", "YN", "  ", "\u0000"})
        @DisplayName("an unrecognised status resolves to nothing and reports not active, because the "
                + "legacy code is a single uppercase character and nothing is folded on the way in")
        void anUnrecognisedStatusResolvesToNothing(final String status) {
            final AccountViewResponse response = aResponse(status);

            assertThat(response.resolvedAccountStatus()).isEmpty();
            assertThat(response.active()).isFalse();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("an absent or empty status resolves to nothing and reports not active, so a screen "
                + "that never populated the field cannot read as active")
        void anAbsentStatusResolvesToNothing(final String status) {
            final AccountViewResponse response = aResponse(status);

            assertThat(response.resolvedAccountStatus()).isEmpty();
            assertThat(response.active()).isFalse();
        }

        @Test
        @DisplayName("the two readings agree with the enumeration read independently, so the derivation "
                + "adds no interpretation of its own")
        void theTwoReadingsAgreeWithTheEnumerationReadIndependently() {
            for (final String code : List.of("Y", "N", "X", "")) {
                final Optional<AccountStatus> expected = AccountStatus.fromCode(code);
                final AccountViewResponse response = aResponse(code);

                assertThat(response.resolvedAccountStatus()).isEqualTo(expected);
                assertThat(response.active())
                        .isEqualTo(expected.isPresent() && expected.get().isActive());
            }
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the declared validation bounds")
    class TheDeclaredValidationBounds {

        @ParameterizedTest(name = "{0} bounded at {1}")
        @CsvSource({
            "transactionName, 4",
            "title01, 40",
            "currentDate, 8",
            "programName, 8",
            "title02, 40",
            "currentTime, 8",
            "accountId, 11",
            "accountStatus, 1",
            "openDate, 10",
            "expirationDate, 10",
            "reissueDate, 10",
            "accountGroupId, 10",
            "customerId, 9",
            "ssn, 12",
            "dateOfBirth, 10",
            "ficoScore, 3",
            "firstName, 25",
            "middleName, 25",
            "lastName, 25",
            "addressLine1, 50",
            "stateCode, 2",
            "addressLine2, 50",
            "zipCode, 5",
            "city, 50",
            "countryCode, 3",
            "phoneNumber1, 13",
            "governmentIssuedId, 20",
            "phoneNumber2, 13",
            "eftAccountId, 10",
            "primaryCardHolderIndicator, 1",
            "infoMessage, 45",
            "errorMessage, 78",
            "focusScreenFieldId, 7"
        })
        @DisplayName("each bounded component carries the legacy field width")
        void eachBoundedComponentCarriesTheLegacyWidth(final String componentName,
                final int expectedMaximum) throws NoSuchMethodException {

            assertThat(declaredMaximumLength(componentName)).isEqualTo(expectedMaximum);
        }

        @Test
        @DisplayName("the next route carries no upper bound, because it is a server-chosen token and not "
                + "a screen field")
        void theNextRouteCarriesNoUpperBound() throws NoSuchMethodException {
            assertThat(AccountViewResponse.class.getDeclaredMethod("nextRoute")
                    .getAnnotation(Size.class)).isNull();
        }

        @Test
        @DisplayName("a fully populated response passes validation")
        void aFullyPopulatedResponsePassesValidation() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(aResponse("Y"))).isEmpty();
            }
        }

        @Test
        @DisplayName("a response with every component absent passes validation, because a length bound "
                + "says nothing about presence")
        void anEmptyResponsePassesValidation() {
            final AccountViewResponse empty = aSparseResponse(null, null, null, null, null, null);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(empty)).isEmpty();
            }
        }

        @Test
        @DisplayName("one character over a bound is reported against that component and nothing else")
        void oneCharacterOverABoundIsReported() throws NoSuchMethodException {
            final int bound = declaredMaximumLength("accountId");
            final AccountViewResponse overBound = aSparseResponse("CAVW", "0".repeat(bound + 1), null,
                    null, null, null);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(overBound))
                        .extracting(violation -> violation.getPropertyPath().toString())
                        .containsExactly("accountId");
            }
        }

        @Test
        @DisplayName("a monetary component carries no length bound at all, because an amount is bounded "
                + "by its scale and precision rather than by a character count")
        void aMonetaryComponentCarriesNoLengthBound() throws NoSuchMethodException {
            for (final String monetary : List.of("creditLimit", "cashCreditLimit", "currentBalance",
                    "currentCycleCredit", "currentCycleDebit")) {
                assertThat(AccountViewResponse.class.getDeclaredMethod(monetary)
                        .getAnnotation(Size.class))
                        .as("component %s", monetary)
                        .isNull();
            }
        }

        @Test
        @DisplayName("several components over their bounds are all reported, so a client sees every "
                + "offending field rather than the first")
        void severalComponentsOverTheirBoundsAreAllReported() {
            final AccountViewResponse overBound = aSparseResponse("CAVW0", null, "YY", "7200", null,
                    null);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(overBound))
                        .extracting((ConstraintViolation<AccountViewResponse> violation)
                                -> violation.getPropertyPath().toString())
                        .containsExactlyInAnyOrder("transactionName", "accountStatus", "ficoScore");
            }
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("value semantics and the published payload")
    class ValueSemanticsAndThePublishedPayload {

        @Test
        @DisplayName("two responses built the same way are equal and share a hash code")
        void twoIdenticalResponsesAreEqual() {
            assertThat(aResponse("Y")).isEqualTo(aResponse("Y")).hasSameHashCodeAs(aResponse("Y"));
        }

        @Test
        @DisplayName("a difference in one component makes two responses unequal")
        void aDifferenceInOneComponentMakesTwoResponsesUnequal() {
            assertThat(aResponse("Y")).isNotEqualTo(aResponse("N"));
        }

        @Test
        @DisplayName("an absent component is omitted from the payload, and the boolean is always present "
                + "because a primitive is never absent")
        void anAbsentComponentIsOmittedAndTheBooleanIsAlwaysPresent()
                throws JsonProcessingException {

            final JsonNode payload = payloadOf(aResponse("Y"));

            assertThat(payload.has("errorMessage")).isFalse();
            assertThat(payload.has("inputError")).isTrue();
            assertThat(payload.get("inputError").asBoolean()).isFalse();
            assertThat(payload.get("accountId").asText()).isEqualTo(ACCOUNT_ID);
        }

        @Test
        @DisplayName("a monetary amount renders in plain notation with its scale intact, so a cent "
                + "boundary survives the wire")
        void aMonetaryAmountRendersInPlainNotationWithItsScale() throws JsonProcessingException {
            final String wire = wirePayloadOf(aSparseResponse(null, ACCOUNT_ID, "Y", null,
                    new BigDecimal("1000000000.05"), new BigDecimal("-0.01")));

            assertThat(wire).contains("\"creditLimit\":1000000000.05");
            assertThat(wire).contains("\"currentBalance\":-0.01");
            assertThat(wire).doesNotContain("E9").doesNotContain("e9");
        }

        @Test
        @DisplayName("a trailing-zero cent renders as two digits, so a whole-dollar amount is not "
                + "published as though its scale were one digit or none")
        void aTrailingZeroCentRendersAsTwoDigits() throws JsonProcessingException {
            final String wire = wirePayloadOf(aSparseResponse(null, ACCOUNT_ID, "Y", null,
                    new BigDecimal("5000.00"), new BigDecimal("0.00")));

            assertThat(wire).contains("\"creditLimit\":5000.00");
            assertThat(wire).contains("\"currentBalance\":0.00");
        }

        @Test
        @DisplayName("the derived readings are not published, because they are a convenience for a "
                + "server-side caller rather than part of the wire contract")
        void theDerivedReadingsAreNotPublished() throws JsonProcessingException {
            final JsonNode payload = payloadOf(aResponse("Y"));

            assertThat(payload.has("resolvedAccountStatus")).isFalse();
            assertThat(payload.has("active")).isFalse();
            assertThat(payload.has("accountStatus")).isTrue();
        }

        @Test
        @DisplayName("a response survives a round trip through the module-equivalent mapper unchanged, "
                + "monetary scale included")
        void aResponseSurvivesARoundTrip() throws JsonProcessingException {
            final ObjectMapper mapper = moduleEquivalentMapper();
            final AccountViewResponse original = aResponse("N");

            final AccountViewResponse restored = mapper.readValue(
                    mapper.writeValueAsString(original), AccountViewResponse.class);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.creditLimit().scale()).isEqualTo(2);
            assertThat(restored.resolvedAccountStatus()).contains(AccountStatus.INACTIVE);
        }
    }
}
