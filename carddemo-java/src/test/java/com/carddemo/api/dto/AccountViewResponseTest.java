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
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AccountViewResponse}, the display-only response body of legacy transaction
 * {@code CAVW} (program {@code app/cbl/COACTVWC.cbl}, map {@code app/cpy-bms/COACTVW.CPY}, mapset
 * {@code app/bms/COACTVW.bms}, layouts {@code app/cpy/CVACT01Y.cpy} and {@code app/cpy/CVCUS01Y.cpy}).
 *
 * <p>Two obligations land here and both are easy to break silently.
 *
 * <p><strong>Exact decimals.</strong> Each of the five monetary components is held to scale two in
 * both directions and to plain rather than exponential rendering, and each is exercised negative and
 * zero: the estate carries negative cycle amounts and the screen's numeric edit reserves a leading
 * sign position, so neither case is hypothetical.
 *
 * <p><strong>No credit-score range on the read path.</strong> Twenty-one of the fifty seeded customer
 * records score below 300 and the lowest is {@code 001}, so a lower bound of 300 here would make
 * forty-two per cent of the reference data unviewable. The 300-to-850 window belongs to the update
 * path and is asserted against the update request type instead.
 *
 * <p>Text components are populated at exactly their map widths, padding included, because the map
 * items are fixed-width and space-significant; where a date or time is needed it is a fixed literal
 * rather than a reading of the system clock, so no assertion here can drift with the calendar.
 *
 * <p>Nothing starts a context, container or database, and nothing inspects an annotation, field or
 * record component at run time - the module's introspection budget is zero. The static types are
 * pinned by assignment ({@link BigDecimal} is final, so a {@code double} component would not
 * compile), the component inventory by comparing the serialised key set against the independently
 * transcribed {@link #COMPONENTS_IN_MAP_ORDER}, and constraint presence behaviourally through a real
 * {@link Validator}. Payloads are produced by a mapper built locally in this file from the settings
 * the module declares in {@code src/main/resources/application.yml}, so an asserted payload is the
 * payload a client receives.
 */
@DisplayName("AccountViewResponse")
class AccountViewResponseTest {
    private static final List<String> COMPONENTS_IN_MAP_ORDER = List.of(
            "transactionName", "title01", "currentDate", "programName", "title02", "currentTime",
            "accountId", "accountStatus", "openDate", "creditLimit", "expirationDate",
            "cashCreditLimit", "reissueDate", "currentBalance", "currentCycleCredit",
            "accountGroupId", "currentCycleDebit", "customerId", "ssn", "dateOfBirth", "ficoScore",
            "firstName", "middleName", "lastName", "addressLine1", "stateCode", "addressLine2",
            "zipCode", "city", "countryCode", "phoneNumber1", "governmentIssuedId", "phoneNumber2",
            "eftAccountId", "primaryCardHolderIndicator", "infoMessage", "errorMessage",
            "inputError", "fieldErrors", "focusScreenFieldId", "nextRoute", "navigationContext");

    private static final String TRANSACTION_NAME = "CAVW";

    private static final String TITLE_01 = "CardDemo Account View                   ";

    private static final String CURRENT_DATE = "06/10/22";

    private static final String PROGRAM_NAME = "COACTVWC";

    private static final String TITLE_02 = "View Account                            ";

    private static final String CURRENT_TIME = "19:27:53";

    private static final String ACCOUNT_ID = "00000000011";

    private static final String ACCOUNT_STATUS = "Y";

    private static final String OPEN_DATE = "2020-01-15";

    private static final String EXPIRATION_DATE = "2028-12-31";

    private static final String REISSUE_DATE = "2024-06-30";

    private static final String ACCOUNT_GROUP_ID = "          ";

    private static final String CUSTOMER_ID = "000000011";

    private static final String SSN = "000-00-0000 ";

    private static final String DATE_OF_BIRTH = "1985-03-22";

    private static final String FICO_SCORE = "001";

    private static final String FIRST_NAME = "Mary                     ";

    private static final String MIDDLE_NAME = "Ann Marie                ";

    private static final String LAST_NAME = "Vandelay                 ";

    private static final String ADDRESS_LINE_1 = "1 Corporate Way                                   ";

    private static final String STATE_CODE = "NY";

    private static final String ADDRESS_LINE_2 = "Suite 400                                         ";

    private static final String ZIP_CODE = "10118";

    private static final String CITY = "New York                                          ";

    private static final String COUNTRY_CODE = "USA";

    private static final String PHONE_NUMBER_1 = "(908)119-8310";

    private static final String GOVERNMENT_ISSUED_ID = "GOVT-ID-0000000011  ";

    private static final String PHONE_NUMBER_2 = "(373)693-8684";

    private static final String EFT_ACCOUNT_ID = "EFT0000001";

    private static final String PRIMARY_CARD_HOLDER_INDICATOR = "Y";

    private static final String INFO_MESSAGE = "Enter account number and press Enter         ";

    private static final String ERROR_MESSAGE =
            "Account:00000000011 not found in Cross ref file.                              ";

    private static final String FOCUS_SCREEN_FIELD_ID = "ACCTSID";

    private static final String NEXT_ROUTE = "/api/accounts/view";

    private static final NavigationContext NAVIGATION_CONTEXT = new NavigationContext(
            "CAVW", "COACTVWC", "CAVW", "COACTVWC", "TESTUSR1", "U",
            NavigationContext.ProgramContext.REENTER, CUSTOMER_ID,
            FIRST_NAME, MIDDLE_NAME, LAST_NAME, ACCOUNT_ID, ACCOUNT_STATUS,
            "0000000000000001", "CACTVWA", "COACTVW");

    private static final BigDecimal CREDIT_LIMIT = new BigDecimal("5000.00");

    private static final BigDecimal CASH_CREDIT_LIMIT = new BigDecimal("1500.00");

    private static final BigDecimal CURRENT_BALANCE = new BigDecimal("-247.83");

    private static final BigDecimal CURRENT_CYCLE_CREDIT = new BigDecimal("0.00");

    private static final BigDecimal CURRENT_CYCLE_DEBIT = new BigDecimal("312.45");

    private static final BigDecimal WIDEST_AMOUNT = new BigDecimal("-9999999999.99");

    private static final BigDecimal TOO_WIDE_AMOUNT = new BigDecimal("12345678901.00");

    private static final BigDecimal EXPONENTIAL_FORM = new BigDecimal("1E+2");

    private static final BigDecimal PLAIN_FORM = new BigDecimal(BigInteger.valueOf(10_000L), 2);

    private record MoneySlot(String jsonKey,
            Function<AccountViewResponse, BigDecimal> accessor,
            Function<BigDecimal, AccountViewResponse> carrying) {
    }

    private static final List<MoneySlot> MONEY_SLOTS = List.of(
            new MoneySlot("creditLimit", AccountViewResponse::creditLimit,
                    amount -> amountsOnly(amount, null, null, null, null)),
            new MoneySlot("cashCreditLimit", AccountViewResponse::cashCreditLimit,
                    amount -> amountsOnly(null, amount, null, null, null)),
            new MoneySlot("currentBalance", AccountViewResponse::currentBalance,
                    amount -> amountsOnly(null, null, amount, null, null)),
            new MoneySlot("currentCycleCredit", AccountViewResponse::currentCycleCredit,
                    amount -> amountsOnly(null, null, null, amount, null)),
            new MoneySlot("currentCycleDebit", AccountViewResponse::currentCycleDebit,
                    amount -> amountsOnly(null, null, null, null, amount)));

    private static AccountViewResponse populated(String accountId,
            String accountStatus,
            String ssn,
            String ficoScore,
            String focusScreenFieldId,
            String nextRoute,
            NavigationContext navigationContext,
            BigDecimal creditLimit,
            BigDecimal cashCreditLimit,
            BigDecimal currentBalance,
            BigDecimal currentCycleCredit,
            BigDecimal currentCycleDebit) {
        return new AccountViewResponse(
                TRANSACTION_NAME, TITLE_01, CURRENT_DATE, PROGRAM_NAME, TITLE_02, CURRENT_TIME,
                accountId, accountStatus, OPEN_DATE, creditLimit, EXPIRATION_DATE, cashCreditLimit,
                REISSUE_DATE, currentBalance, currentCycleCredit, ACCOUNT_GROUP_ID,
                currentCycleDebit,
                CUSTOMER_ID, ssn, DATE_OF_BIRTH, ficoScore, FIRST_NAME, MIDDLE_NAME, LAST_NAME,
                ADDRESS_LINE_1, STATE_CODE, ADDRESS_LINE_2, ZIP_CODE, CITY, COUNTRY_CODE,
                PHONE_NUMBER_1, GOVERNMENT_ISSUED_ID, PHONE_NUMBER_2, EFT_ACCOUNT_ID,
                PRIMARY_CARD_HOLDER_INDICATOR,
                INFO_MESSAGE, ERROR_MESSAGE,
                true, List.of(), focusScreenFieldId, nextRoute, navigationContext);
    }

    private static AccountViewResponse populated() {
        return populated(ACCOUNT_ID, ACCOUNT_STATUS, SSN, FICO_SCORE, FOCUS_SCREEN_FIELD_ID,
                NEXT_ROUTE, NAVIGATION_CONTEXT, CREDIT_LIMIT, CASH_CREDIT_LIMIT, CURRENT_BALANCE,
                CURRENT_CYCLE_CREDIT, CURRENT_CYCLE_DEBIT);
    }

    private static AccountViewResponse allAbsent() {
        return new AccountViewResponse(
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, false, List.of(), null,
                null, null);
    }

    private static AccountViewResponse amountsOnly(BigDecimal creditLimit,
            BigDecimal cashCreditLimit,
            BigDecimal currentBalance,
            BigDecimal currentCycleCredit,
            BigDecimal currentCycleDebit) {
        return new AccountViewResponse(
                null, null, null, null, null, null, null, null, null,
                creditLimit, null, cashCreditLimit, null, currentBalance, currentCycleCredit, null,
                currentCycleDebit,
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                false, List.of(), null, null, null);
    }

    private static AccountViewResponse withAccountId(String accountId) {
        return populated(accountId, ACCOUNT_STATUS, SSN, FICO_SCORE, FOCUS_SCREEN_FIELD_ID,
                NEXT_ROUTE, NAVIGATION_CONTEXT, CREDIT_LIMIT, CASH_CREDIT_LIMIT, CURRENT_BALANCE,
                CURRENT_CYCLE_CREDIT, CURRENT_CYCLE_DEBIT);
    }

    private static AccountViewResponse withAccountStatus(String accountStatus) {
        return populated(ACCOUNT_ID, accountStatus, SSN, FICO_SCORE, FOCUS_SCREEN_FIELD_ID,
                NEXT_ROUTE, NAVIGATION_CONTEXT, CREDIT_LIMIT, CASH_CREDIT_LIMIT, CURRENT_BALANCE,
                CURRENT_CYCLE_CREDIT, CURRENT_CYCLE_DEBIT);
    }

    private static AccountViewResponse withSsn(String ssn) {
        return populated(ACCOUNT_ID, ACCOUNT_STATUS, ssn, FICO_SCORE, FOCUS_SCREEN_FIELD_ID,
                NEXT_ROUTE, NAVIGATION_CONTEXT, CREDIT_LIMIT, CASH_CREDIT_LIMIT, CURRENT_BALANCE,
                CURRENT_CYCLE_CREDIT, CURRENT_CYCLE_DEBIT);
    }

    private static AccountViewResponse withFicoScore(String ficoScore) {
        return populated(ACCOUNT_ID, ACCOUNT_STATUS, SSN, ficoScore, FOCUS_SCREEN_FIELD_ID,
                NEXT_ROUTE, NAVIGATION_CONTEXT, CREDIT_LIMIT, CASH_CREDIT_LIMIT, CURRENT_BALANCE,
                CURRENT_CYCLE_CREDIT, CURRENT_CYCLE_DEBIT);
    }

    private static AccountViewResponse withNextRoute(String nextRoute) {
        return populated(ACCOUNT_ID, ACCOUNT_STATUS, SSN, FICO_SCORE, FOCUS_SCREEN_FIELD_ID,
                nextRoute, NAVIGATION_CONTEXT, CREDIT_LIMIT, CASH_CREDIT_LIMIT, CURRENT_BALANCE,
                CURRENT_CYCLE_CREDIT, CURRENT_CYCLE_DEBIT);
    }

    private static AccountViewResponse withNavigationContext(NavigationContext navigationContext) {
        return populated(ACCOUNT_ID, ACCOUNT_STATUS, SSN, FICO_SCORE, FOCUS_SCREEN_FIELD_ID,
                NEXT_ROUTE, navigationContext, CREDIT_LIMIT, CASH_CREDIT_LIMIT, CURRENT_BALANCE,
                CURRENT_CYCLE_CREDIT, CURRENT_CYCLE_DEBIT);
    }

    private static ObjectMapper moduleEquivalentMapper() {
        return JsonMapper.builder()
                .defaultPropertyInclusion(JsonInclude.Value.construct(
                        JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .build();
    }

    private static ObjectMapper strictUnknownPropertyMapper() {
        return JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    private static JsonNode payloadOf(AccountViewResponse response) throws JsonProcessingException {
        ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(response));
    }

    private static Set<String> keysOf(JsonNode payload) {
        Set<String> keys = new LinkedHashSet<>();
        for (Map.Entry<String, JsonNode> property : payload.properties()) {
            keys.add(property.getKey());
        }
        return keys;
    }

    private static Set<ConstraintViolation<AccountViewResponse>> violationsOf(
            AccountViewResponse response) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(response);
        }
    }

    @Nested
    @DisplayName("the decimal contract, component by component")
    class DecimalContract {
        @Test
        @DisplayName("states the record's scale and integer-digit budget as part of the contract")
        void statesTheRecordShape() {
            assertThat(AccountViewResponse.MONEY_SCALE)
                    .describedAs("two decimal places, from the five signed zoned decimals of "
                            + "CVACT01Y.cpy lines 7, 8, 9, 13 and 14")
                    .isEqualTo(2);
            assertThat(AccountViewResponse.MONEY_INTEGER_DIGITS)
                    .describedAs("ten integer digits, giving the total precision of twelve the "
                            + "relational columns declare")
                    .isEqualTo(10);
        }

        @Test
        @DisplayName("declares all five amounts as exact decimals, never an approximate numeric type")
        void declaresAllFiveAsExactDecimals() {
            AccountViewResponse response = populated();
            BigDecimal creditLimit = response.creditLimit();
            BigDecimal cashCreditLimit = response.cashCreditLimit();
            BigDecimal currentBalance = response.currentBalance();
            BigDecimal currentCycleCredit = response.currentCycleCredit();
            BigDecimal currentCycleDebit = response.currentCycleDebit();

            assertThat(MONEY_SLOTS.stream().map(MoneySlot::jsonKey).toList())
                    .describedAs("the five monetary components of the account body")
                    .containsExactly("creditLimit", "cashCreditLimit", "currentBalance",
                            "currentCycleCredit", "currentCycleDebit");
            assertThat(List.of(creditLimit, cashCreditLimit, currentBalance, currentCycleCredit,
                    currentCycleDebit)).doesNotContainNull();
        }

        @Test
        @DisplayName("preserves scale two on each of the five independently, in both directions")
        void preservesScaleTwoOnEachComponent() throws JsonProcessingException {
            ObjectMapper mapper = moduleEquivalentMapper();

            for (MoneySlot slot : MONEY_SLOTS) {
                BigDecimal supplied = new BigDecimal("1234567890.12");
                AccountViewResponse response = slot.carrying().apply(supplied);
                BigDecimal carried = slot.accessor().apply(response);

                assertThat(carried.scale())
                        .describedAs("scale carried by %s", slot.jsonKey())
                        .isEqualTo(AccountViewResponse.MONEY_SCALE);
                assertThat(carried.toPlainString())
                        .describedAs("plain text carried by %s", slot.jsonKey())
                        .isEqualTo("1234567890.12");

                String wire = mapper.writeValueAsString(response);
                assertThat(wire)
                        .describedAs("wire text for %s", slot.jsonKey())
                        .contains("\"" + slot.jsonKey() + "\":1234567890.12");

                BigDecimal returned =
                        slot.accessor().apply(mapper.readValue(wire, AccountViewResponse.class));
                assertThat(returned.scale())
                        .describedAs("scale surviving the round trip of %s", slot.jsonKey())
                        .isEqualTo(AccountViewResponse.MONEY_SCALE);
                assertThat(returned.toPlainString())
                        .describedAs("plain text surviving the round trip of %s", slot.jsonKey())
                        .isEqualTo("1234567890.12");
            }
        }

        @Test
        @DisplayName("renders each of the five plainly on the wire, never in scientific notation")
        void rendersEachComponentPlainly() throws JsonProcessingException {
            for (MoneySlot slot : MONEY_SLOTS) {
                String payload = moduleEquivalentMapper()
                        .writeValueAsString(slot.carrying().apply(PLAIN_FORM));

                assertThat(payload)
                        .describedAs("wire text for %s", slot.jsonKey())
                        .contains("\"" + slot.jsonKey() + "\":100.00")
                        .doesNotContain("E+")
                        .doesNotContain("e+")
                        .doesNotContain("E-")
                        .doesNotContain("e-");
            }
        }

        @Test
        @DisplayName("binds each of the five back from a plain decimal at scale two")
        void bindsEachComponentBackFromPlainDecimal() throws JsonProcessingException {
            for (MoneySlot slot : MONEY_SLOTS) {
                String body = "{\"" + slot.jsonKey() + "\":-9999999999.99}";

                AccountViewResponse revived =
                        moduleEquivalentMapper().readValue(body, AccountViewResponse.class);
                BigDecimal bound = slot.accessor().apply(revived);

                assertThat(bound.scale())
                        .describedAs("scale bound into %s", slot.jsonKey())
                        .isEqualTo(AccountViewResponse.MONEY_SCALE);
                assertThat(bound.toPlainString())
                        .describedAs("plain text bound into %s", slot.jsonKey())
                        .isEqualTo("-9999999999.99");
            }
        }

        @Test
        @DisplayName("carries a negative amount in each of the five, sign and scale intact")
        void carriesANegativeAmountInEachComponent() throws JsonProcessingException {
            for (MoneySlot slot : MONEY_SLOTS) {
                BigDecimal carried = slot.accessor().apply(slot.carrying().apply(WIDEST_AMOUNT));

                assertThat(carried.signum())
                        .describedAs("sign carried by %s", slot.jsonKey())
                        .isEqualTo(-1);
                assertThat(carried.scale())
                        .describedAs("scale carried by %s", slot.jsonKey())
                        .isEqualTo(AccountViewResponse.MONEY_SCALE);
                assertThat(carried.toPlainString())
                        .describedAs("plain text carried by %s", slot.jsonKey())
                        .isEqualTo("-9999999999.99");
                assertThat(moduleEquivalentMapper()
                        .writeValueAsString(slot.carrying().apply(WIDEST_AMOUNT)))
                        .describedAs("wire text for a negative %s", slot.jsonKey())
                        .contains("\"" + slot.jsonKey() + "\":-9999999999.99");
            }
        }

        @Test
        @DisplayName("carries a zero amount in each of the five without collapsing its decimals")
        void carriesAZeroAmountInEachComponent() throws JsonProcessingException {
            for (MoneySlot slot : MONEY_SLOTS) {
                BigDecimal carried =
                        slot.accessor().apply(slot.carrying().apply(CURRENT_CYCLE_CREDIT));

                assertThat(carried.signum())
                        .describedAs("sign carried by a zero %s", slot.jsonKey())
                        .isZero();
                assertThat(carried.scale())
                        .describedAs("scale carried by a zero %s", slot.jsonKey())
                        .isEqualTo(AccountViewResponse.MONEY_SCALE);
                assertThat(carried.toPlainString())
                        .describedAs("plain text carried by a zero %s", slot.jsonKey())
                        .isEqualTo("0.00");
                assertThat(moduleEquivalentMapper()
                        .writeValueAsString(slot.carrying().apply(CURRENT_CYCLE_CREDIT)))
                        .describedAs("wire text for a zero %s", slot.jsonKey())
                        .contains("\"" + slot.jsonKey() + "\":0.00");
            }
        }

        @Test
        @DisplayName("keeps the exponential form off the wire by refusing it at the door")
        void keepsTheExponentialFormOffTheWire() throws JsonProcessingException {
            assertThat(EXPONENTIAL_FORM.scale()).isEqualTo(-2);
            assertThat(EXPONENTIAL_FORM.toString()).isEqualTo("1E+2");

            for (MoneySlot slot : MONEY_SLOTS) {
                assertThatThrownBy(() -> slot.carrying().apply(EXPONENTIAL_FORM))
                        .describedAs("refusal of an exponential amount in %s", slot.jsonKey())
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining(slot.jsonKey())
                        .hasMessageContaining("scale 2");
            }

            assertThat(moduleEquivalentMapper().writeValueAsString(EXPONENTIAL_FORM))
                    .describedAs("the mapper's own plain-decimal setting")
                    .isEqualTo("100")
                    .doesNotContain("E");

            assertThat(PLAIN_FORM.scale()).isEqualTo(2);
            assertThat(PLAIN_FORM.toPlainString()).isEqualTo("100.00");
            assertThat(moduleEquivalentMapper()
                    .writeValueAsString(amountsOnly(PLAIN_FORM, null, null, null, null)))
                    .describedAs("the accepted scale-two form of the same numeric value")
                    .contains("\"creditLimit\":100.00");
        }

        @Test
        @DisplayName("never re-scales: the scale handed in is the scale handed back")
        void neverReScales() {
            for (MoneySlot slot : MONEY_SLOTS) {
                assertThatThrownBy(() -> slot.carrying().apply(new BigDecimal("100.0")))
                        .describedAs("refusal of a scale-one amount in %s", slot.jsonKey())
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("its scale is 1");
                assertThatThrownBy(() -> slot.carrying().apply(new BigDecimal("100.000")))
                        .describedAs("refusal of a scale-three amount in %s", slot.jsonKey())
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("its scale is 3");
                assertThatThrownBy(() -> slot.carrying().apply(BigDecimal.TEN))
                        .describedAs("refusal of an unscaled amount in %s", slot.jsonKey())
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("its scale is 0");

                BigDecimal accepted = new BigDecimal("100.00");
                assertThat(slot.accessor().apply(slot.carrying().apply(accepted)).scale())
                        .describedAs("scale returned unchanged by %s", slot.jsonKey())
                        .isEqualTo(accepted.scale());
            }
        }

        @Test
        @DisplayName("refuses an amount wider than its record field, and names the component")
        void refusesAnAmountWiderThanTheRecordField() {
            for (MoneySlot slot : MONEY_SLOTS) {
                assertThatThrownBy(() -> slot.carrying().apply(TOO_WIDE_AMOUNT))
                        .describedAs("refusal of an over-wide amount in %s", slot.jsonKey())
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining(slot.jsonKey())
                        .hasMessageContaining("10 integer digits")
                        .hasMessageContaining("needs 11")
                        .hasMessageNotContaining("12345678901");
            }
        }

        @Test
        @DisplayName("accepts an absent amount in each of the five, which is a blank screen field")
        void acceptsAnAbsentAmountInEachComponent() throws JsonProcessingException {
            AccountViewResponse absent = amountsOnly(null, null, null, null, null);

            for (MoneySlot slot : MONEY_SLOTS) {
                assertThat(slot.accessor().apply(absent))
                        .describedAs("absent amount tolerated by %s", slot.jsonKey())
                        .isNull();
                assertThat(keysOf(payloadOf(absent)))
                        .describedAs("absent %s omitted from the payload", slot.jsonKey())
                        .doesNotContain(slot.jsonKey());
            }
        }

        @Test
        @DisplayName("never reproduces the screen's numeric-edited picture")
        void neverReproducesTheScreenEdit() throws JsonProcessingException {
            String payload = moduleEquivalentMapper().writeValueAsString(populated());

            assertThat(payload)
                    .contains("\"creditLimit\":5000.00")
                    .contains("\"cashCreditLimit\":1500.00")
                    .contains("\"currentBalance\":-247.83")
                    .contains("\"currentCycleCredit\":0.00")
                    .contains("\"currentCycleDebit\":312.45")
                    .doesNotContain("ZZZ")
                    .doesNotContain("5,000")
                    .doesNotContain("+5000")
                    .doesNotContain("$");
        }
    }

    @Nested
    @DisplayName("numeric-picture identifiers are bounded text")
    class NumericPictureIdentifiers {
        @Test
        @DisplayName("holds every digit-pictured identifier as text, so leading zeros survive")
        void holdsEveryIdentifierAsText() {
            AccountViewResponse response = populated();
            String accountId = response.accountId();
            String customerId = response.customerId();
            String ssn = response.ssn();
            String ficoScore = response.ficoScore();
            String eftAccountId = response.eftAccountId();

            assertThat(accountId).isEqualTo("00000000011").hasSize(11);
            assertThat(customerId).isEqualTo("000000011").hasSize(9);
            assertThat(ssn).isEqualTo(SSN).hasSize(12);
            assertThat(ficoScore).isEqualTo("001").hasSize(3);
            assertThat(eftAccountId).isEqualTo(EFT_ACCOUNT_ID).hasSize(10);
        }

        @Test
        @DisplayName("never collapses a leading-zero identifier to its numeric value")
        void neverCollapsesALeadingZeroIdentifier() throws JsonProcessingException {
            JsonNode payload = payloadOf(populated());

            assertThat(payload.get("accountId").asText()).isEqualTo("00000000011").isNotEqualTo("11");
            assertThat(payload.get("customerId").asText()).isEqualTo("000000011").isNotEqualTo("11");
            assertThat(payload.get("ficoScore").asText()).isEqualTo("001").isNotEqualTo("1");
            assertThat(moduleEquivalentMapper().writeValueAsString(populated()))
                    .describedAs("identifiers quoted as text on the wire, not emitted as numbers")
                    .contains("\"accountId\":\"00000000011\"")
                    .contains("\"customerId\":\"000000011\"")
                    .contains("\"ficoScore\":\"001\"");
        }

        @Test
        @DisplayName("returns a leading-zero identifier unchanged after a full round trip")
        void returnsALeadingZeroIdentifierUnchanged() throws JsonProcessingException {
            ObjectMapper mapper = moduleEquivalentMapper();

            AccountViewResponse revived = mapper.readValue(
                    mapper.writeValueAsString(populated()), AccountViewResponse.class);

            assertThat(revived.accountId()).isEqualTo("00000000011");
            assertThat(revived.customerId()).isEqualTo("000000011");
            assertThat(revived.ficoScore()).isEqualTo("001");
        }
    }

    @Nested
    @DisplayName("all thirty-seven map items, at their declared widths, untrimmed")
    class MapItemWidths {
        @Test
        @DisplayName("carries the six header items byte for byte")
        void carriesTheHeaderItems() {
            AccountViewResponse response = populated();

            assertThat(response.transactionName()).isEqualTo(TRANSACTION_NAME).hasSize(4);
            assertThat(response.title01()).isEqualTo(TITLE_01).hasSize(40);
            assertThat(response.currentDate()).isEqualTo(CURRENT_DATE).hasSize(8);
            assertThat(response.programName()).isEqualTo(PROGRAM_NAME).hasSize(8);
            assertThat(response.title02()).isEqualTo(TITLE_02).hasSize(40);
            assertThat(response.currentTime())
                    .describedAs("the current time is eight characters on this map, not nine")
                    .isEqualTo(CURRENT_TIME)
                    .hasSize(8);
        }

        @Test
        @DisplayName("carries the six textual account items byte for byte")
        void carriesTheTextualAccountItems() {
            AccountViewResponse response = populated();

            assertThat(response.accountId()).isEqualTo(ACCOUNT_ID).hasSize(11);
            assertThat(response.accountStatus()).isEqualTo(ACCOUNT_STATUS).hasSize(1);
            assertThat(response.openDate()).isEqualTo(OPEN_DATE).hasSize(10);
            assertThat(response.expirationDate()).isEqualTo(EXPIRATION_DATE).hasSize(10);
            assertThat(response.reissueDate()).isEqualTo(REISSUE_DATE).hasSize(10);
            assertThat(response.accountGroupId()).isEqualTo(ACCOUNT_GROUP_ID).hasSize(10);
        }

        @Test
        @DisplayName("carries the eighteen customer items byte for byte")
        void carriesTheCustomerItems() {
            AccountViewResponse response = populated();

            assertThat(response.customerId()).isEqualTo(CUSTOMER_ID).hasSize(9);
            assertThat(response.ssn()).isEqualTo(SSN).hasSize(12);
            assertThat(response.dateOfBirth()).isEqualTo(DATE_OF_BIRTH).hasSize(10);
            assertThat(response.ficoScore()).isEqualTo(FICO_SCORE).hasSize(3);
            assertThat(response.firstName()).isEqualTo(FIRST_NAME).hasSize(25);
            assertThat(response.middleName()).isEqualTo(MIDDLE_NAME).hasSize(25);
            assertThat(response.lastName()).isEqualTo(LAST_NAME).hasSize(25);
            assertThat(response.addressLine1()).isEqualTo(ADDRESS_LINE_1).hasSize(50);
            assertThat(response.stateCode()).isEqualTo(STATE_CODE).hasSize(2);
            assertThat(response.addressLine2()).isEqualTo(ADDRESS_LINE_2).hasSize(50);
            assertThat(response.zipCode()).isEqualTo(ZIP_CODE).hasSize(5);
            assertThat(response.city()).isEqualTo(CITY).hasSize(50);
            assertThat(response.countryCode()).isEqualTo(COUNTRY_CODE).hasSize(3);
            assertThat(response.phoneNumber1()).isEqualTo(PHONE_NUMBER_1).hasSize(13);
            assertThat(response.governmentIssuedId()).isEqualTo(GOVERNMENT_ISSUED_ID).hasSize(20);
            assertThat(response.phoneNumber2()).isEqualTo(PHONE_NUMBER_2).hasSize(13);
            assertThat(response.eftAccountId()).isEqualTo(EFT_ACCOUNT_ID).hasSize(10);
            assertThat(response.primaryCardHolderIndicator())
                    .isEqualTo(PRIMARY_CARD_HOLDER_INDICATOR).hasSize(1);
        }

        @Test
        @DisplayName("carries both message lines at this map's widths, which two card maps do not share")
        void carriesBothMessageLinesAtThisMapsWidths() {
            AccountViewResponse response = populated();

            assertThat(response.infoMessage()).isEqualTo(INFO_MESSAGE).hasSize(45);
            assertThat(response.errorMessage()).isEqualTo(ERROR_MESSAGE).hasSize(78);
        }

        @Test
        @DisplayName("keeps every space-padded value untrimmed on the accessor and on the wire")
        void keepsEverySpacePaddedValueUntrimmed() throws JsonProcessingException {
            JsonNode payload = payloadOf(populated());

            assertThat(payload.get("accountGroupId").asText())
                    .describedAs("ten spaces, as stored in all fifty seeded account records")
                    .isEqualTo(ACCOUNT_GROUP_ID)
                    .hasSize(10)
                    .isNotEmpty()
                    .isNotEqualTo("");
            assertThat(payload.get("title01").asText()).isEqualTo(TITLE_01).endsWith(" ");
            assertThat(payload.get("firstName").asText()).isEqualTo(FIRST_NAME).endsWith(" ");
            assertThat(payload.get("middleName").asText()).isEqualTo(MIDDLE_NAME).endsWith(" ");
            assertThat(payload.get("lastName").asText()).isEqualTo(LAST_NAME).endsWith(" ");
            assertThat(payload.get("addressLine1").asText()).isEqualTo(ADDRESS_LINE_1).hasSize(50);
            assertThat(payload.get("addressLine2").asText()).isEqualTo(ADDRESS_LINE_2).hasSize(50);
            assertThat(payload.get("city").asText()).isEqualTo(CITY).hasSize(50);
            assertThat(payload.get("infoMessage").asText()).isEqualTo(INFO_MESSAGE).hasSize(45);
            assertThat(payload.get("errorMessage").asText()).isEqualTo(ERROR_MESSAGE).hasSize(78);
        }

        @Test
        @DisplayName("carries the pre-formatted telephone numbers exactly, punctuation intact")
        void carriesThePreFormattedTelephoneNumbers() throws JsonProcessingException {
            AccountViewResponse response = populated();
            JsonNode payload = payloadOf(response);

            assertThat(response.phoneNumber1())
                    .isEqualTo("(908)119-8310")
                    .hasSize(13)
                    .startsWith("(")
                    .contains(")")
                    .contains("-");
            assertThat(response.phoneNumber2()).isEqualTo("(373)693-8684").hasSize(13);
            assertThat(payload.get("phoneNumber1").asText()).isEqualTo("(908)119-8310");
            assertThat(payload.get("phoneNumber2").asText()).isEqualTo("(373)693-8684");
        }

        @Test
        @DisplayName("carries a value whole, never slicing it by offset")
        void carriesAValueWholeWithoutSlicing() {
            String longer = "1 Corporate Way, Thirty-Fourth Floor, Suite Four Hundred, New York";

            AccountViewResponse response = new AccountViewResponse(
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, longer, null,
                    null, null, null, null, null, null, null, null, null, null, null, false, List.of(), null,
                    null, null);

            assertThat(response.addressLine1()).isEqualTo(longer).hasSize(longer.length());
        }
    }

    @Nested
    @DisplayName("dates stay split, bounded text")
    class DateComponents {
        @Test
        @DisplayName("carries all four date items as separate ten-character text components")
        void carriesAllFourDatesAsSeparateText() {
            AccountViewResponse response = populated();
            String openDate = response.openDate();
            String expirationDate = response.expirationDate();
            String reissueDate = response.reissueDate();
            String dateOfBirth = response.dateOfBirth();

            assertThat(openDate).isEqualTo(OPEN_DATE).hasSize(10);
            assertThat(expirationDate).isEqualTo(EXPIRATION_DATE).hasSize(10);
            assertThat(reissueDate).isEqualTo(REISSUE_DATE).hasSize(10);
            assertThat(dateOfBirth).isEqualTo(DATE_OF_BIRTH).hasSize(10);
        }

        @Test
        @DisplayName("never merges the four dates into one component or one payload key")
        void neverMergesTheFourDates() throws JsonProcessingException {
            Set<String> keys = keysOf(payloadOf(populated()));

            assertThat(keys)
                    .contains("openDate", "expirationDate", "reissueDate", "dateOfBirth")
                    .doesNotContain("date", "dates", "accountDates", "openedOn", "expiresOn",
                            "reissuedOn", "bornOn", "period", "dateRange");
        }

        @Test
        @DisplayName("carries a value no calendar admits, proving nothing parses it")
        void carriesAValueNoCalendarAdmits() throws JsonProcessingException {
            String impossible = "9999-99-99";
            String blank = "          ";

            AccountViewResponse response = new AccountViewResponse(
                    null, null, null, null, null, null, null, null, impossible, null, impossible,
                    null, blank, null, null, null, null, null, null, impossible, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, false, List.of(), null, null, null);
            JsonNode payload = payloadOf(response);

            assertThat(response.openDate()).isEqualTo(impossible);
            assertThat(response.expirationDate()).isEqualTo(impossible);
            assertThat(response.reissueDate()).isEqualTo(blank).hasSize(10);
            assertThat(response.dateOfBirth()).isEqualTo(impossible);
            assertThat(payload.get("openDate").asText()).isEqualTo(impossible);
            assertThat(payload.get("reissueDate").asText()).isEqualTo(blank);
            assertThat(violationsOf(response))
                    .describedAs("an impossible calendar date is still within its map width")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("the account-status vocabulary")
    class AccountStatusVocabulary {
        @Test
        @DisplayName("declares exactly two constants, for the two codes the legacy editor admits")
        void declaresExactlyTwoConstants() {
            Set<String> names = new LinkedHashSet<>();
            Set<Character> codes = new LinkedHashSet<>();
            for (AccountStatus status : AccountStatus.values()) {
                names.add(status.name());
                codes.add(status.getCode());
            }

            assertThat(AccountStatus.values())
                    .describedAs("the two values the account-update editor admits for this field")
                    .hasSize(2);
            assertThat(names).containsExactly("ACTIVE", "INACTIVE");
            assertThat(codes).containsExactly('Y', 'N');
        }

        @Test
        @DisplayName("declares no synthetic fallback constant")
        void declaresNoSyntheticFallback() {
            Set<String> names = new LinkedHashSet<>();
            for (AccountStatus status : AccountStatus.values()) {
                names.add(status.name());
            }

            assertThat(names)
                    .doesNotContain("UNKNOWN", "NONE", "OTHER", "INVALID", "DEFAULT", "UNSPECIFIED",
                            "BLANK", "NOT_OK");
        }

        @Test
        @DisplayName("excludes the two validation-flag states, which are not account states")
        void excludesTheValidationFlagStates() {
            Set<Character> codes = new LinkedHashSet<>();
            for (AccountStatus status : AccountStatus.values()) {
                codes.add(status.getCode());
            }

            assertThat(codes).doesNotContain('0', 'B');
            assertThat(AccountStatus.fromCode('0')).isEmpty();
            assertThat(AccountStatus.fromCode('B')).isEmpty();
            assertThat(AccountStatus.fromCode("0")).isEmpty();
            assertThat(AccountStatus.fromCode("B")).isEmpty();
        }

        @Test
        @DisplayName("answers the active predicate true for exactly one constant")
        void answersTheActivePredicateForExactlyOneConstant() {
            assertThat(AccountStatus.ACTIVE.isActive()).isTrue();
            assertThat(AccountStatus.INACTIVE.isActive()).isFalse();
            assertThat(AccountStatus.ACTIVE.getCode()).isEqualTo('Y');
            assertThat(AccountStatus.INACTIVE.getCode()).isEqualTo('N');
        }

        @Test
        @DisplayName("resolves a code to an optional result and never throws")
        void resolvesToAnOptionalAndNeverThrows() {
            Optional<AccountStatus> active = AccountStatus.fromCode('Y');
            Optional<AccountStatus> inactive = AccountStatus.fromCode("N");
            Optional<AccountStatus> unmapped = AccountStatus.fromCode("Q");

            assertThat(active).contains(AccountStatus.ACTIVE);
            assertThat(inactive).contains(AccountStatus.INACTIVE);
            assertThat(unmapped)
                    .describedAs("a file-sourced code outside the vocabulary flows through "
                            + "untouched in the legacy system and must here too")
                    .isEmpty();
            assertThat(AccountStatus.fromCode((String) null)).isEmpty();
            assertThat(AccountStatus.fromCode("")).isEmpty();
            assertThat(AccountStatus.fromCode("YY"))
                    .describedAs("an over-length value is refused rather than truncated")
                    .isEmpty();
            assertThatCode(() -> AccountStatus.fromCode("\u0000")).doesNotThrowAnyException();
            assertThatCode(() -> AccountStatus.fromCode((String) null)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("applies no case folding, so a lower-case code is not active")
        void appliesNoCaseFolding() {
            assertThat(AccountStatus.fromCode('y')).isEmpty();
            assertThat(AccountStatus.fromCode("y")).isEmpty();
            assertThat(AccountStatus.fromCode("n")).isEmpty();
            assertThat(AccountStatus.isActiveCode("y")).isFalse();
            assertThat(AccountStatus.isActiveCode("Y")).isTrue();
        }

        @Test
        @DisplayName("carries the status on the response as the raw character, not as the enumeration")
        void carriesTheStatusAsTheRawCharacter() {
            AccountViewResponse response = populated();
            String rawStatus = response.accountStatus();
            Optional<AccountStatus> resolved = response.resolvedAccountStatus();

            assertThat(rawStatus).isEqualTo("Y").hasSize(1);
            assertThat(resolved).contains(AccountStatus.ACTIVE);
            assertThat(response.active()).isTrue();
        }

        @Test
        @DisplayName("carries an unrecognised status character without throwing")
        void carriesAnUnrecognisedStatusWithoutThrowing() throws JsonProcessingException {
            AccountViewResponse response = withAccountStatus("Q");

            assertThat(response.accountStatus()).isEqualTo("Q");
            assertThat(response.resolvedAccountStatus()).isEmpty();
            assertThat(response.active()).isFalse();
            assertThat(payloadOf(response).get("accountStatus").asText()).isEqualTo("Q");
            assertThat(violationsOf(response))
                    .describedAs("the status column has no check constraint, so nothing here rejects "
                            + "an undeclared code")
                    .isEmpty();
        }

        @Test
        @DisplayName("carries an absent status without throwing")
        void carriesAnAbsentStatusWithoutThrowing() {
            AccountViewResponse response = withAccountStatus(null);

            assertThat(response.accountStatus()).isNull();
            assertThat(response.resolvedAccountStatus()).isEmpty();
            assertThat(response.active()).isFalse();
        }
    }

    @Nested
    @DisplayName("the navigation state is carried, never re-implemented")
    class NavigationStateCarriage {
        @Test
        @DisplayName("returns the echoed state unchanged, leading zeros included")
        void returnsTheEchoedStateUnchanged() {
            AccountViewResponse response = populated();
            NavigationContext carried = response.navigationContext();

            assertThat(carried).isSameAs(NAVIGATION_CONTEXT);
            assertThat(carried.accountId()).isEqualTo("00000000011").hasSize(11);
            assertThat(carried.customerId()).isEqualTo("000000011").hasSize(9);
            assertThat(carried.userId()).isEqualTo("TESTUSR1");
            assertThat(carried.programContext())
                    .isEqualTo(NavigationContext.ProgramContext.REENTER);
        }

        @Test
        @DisplayName("round-trips the echoed state through the wire with its identifiers intact")
        void roundTripsTheEchoedState() throws JsonProcessingException {
            ObjectMapper mapper = moduleEquivalentMapper();

            AccountViewResponse revived = mapper.readValue(
                    mapper.writeValueAsString(populated()), AccountViewResponse.class);

            assertThat(revived.navigationContext()).isEqualTo(NAVIGATION_CONTEXT);
            assertThat(revived.navigationContext().accountId()).isEqualTo("00000000011");
            assertThat(revived.navigationContext().customerId()).isEqualTo("000000011");
            assertThat(revived.navigationContext().lastMap()).isEqualTo("CACTVWA");
            assertThat(revived.navigationContext().lastMapset()).isEqualTo("COACTVW");
        }

        @Test
        @DisplayName("carries the wholly empty state, which is a real legacy state")
        void carriesTheWhollyEmptyState() throws JsonProcessingException {
            AccountViewResponse response = withNavigationContext(NavigationContext.empty());

            assertThat(response.navigationContext()).isEqualTo(NavigationContext.empty());
            assertThat(response.navigationContext().accountId()).isNull();
            assertThat(keysOf(payloadOf(response)))
                    .describedAs("the empty state is still a present object, not an absent property")
                    .contains("navigationContext");
            assertThat(violationsOf(response)).isEmpty();
        }

        @Test
        @DisplayName("carries an absent navigation state")
        void carriesAnAbsentNavigationState() throws JsonProcessingException {
            AccountViewResponse response = withNavigationContext(null);

            assertThat(response.navigationContext()).isNull();
            assertThat(keysOf(payloadOf(response))).doesNotContain("navigationContext");
        }
    }

    @Nested
    @DisplayName("the validation surface: a width bound, and nothing else")
    class ValidationSurface {
        @Test
        @DisplayName("reports a violation when a value exceeds its map width")
        void reportsAViolationWhenAValueExceedsItsMapWidth() {
            Set<ConstraintViolation<AccountViewResponse>> violations =
                    violationsOf(withAccountId("000000000110"));

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString("accountId");
            assertThat(violationsOf(withAccountId("00000000011")))
                    .describedAs("eleven characters is within the map width")
                    .isEmpty();
        }

        @Test
        @DisplayName("reports nothing at all when every component is absent")
        void reportsNothingWhenEveryComponentIsAbsent() {
            assertThat(violationsOf(allAbsent())).isEmpty();
        }

        @Test
        @DisplayName("accepts a credit score below the screen's documented range, down to the lowest seeded")
        void acceptsACreditScoreBelowTheDocumentedRange() throws JsonProcessingException {
            for (String score : List.of("001", "042", "299", "300", "850", "851", "999", "000")) {
                assertThat(violationsOf(withFicoScore(score)))
                        .describedAs("credit score %s on the read path", score)
                        .isEmpty();
                assertThat(withFicoScore(score).ficoScore())
                        .describedAs("credit score %s carried verbatim", score)
                        .isEqualTo(score);
            }

            assertThat(payloadOf(withFicoScore("001")).get("ficoScore").asText())
                    .describedAs("the lowest seeded score keeps all three characters")
                    .isEqualTo("001");
        }

        @Test
        @DisplayName("accepts the ten-space account group identifier, untrimmed")
        void acceptsTheTenSpaceAccountGroupIdentifier() {
            AccountViewResponse response = populated();

            assertThat(violationsOf(response)).isEmpty();
            assertThat(response.accountGroupId()).isEqualTo("          ").hasSize(10);
        }

        @Test
        @DisplayName("accepts a pre-formatted telephone number, so no pattern constraint can exist")
        void acceptsAPreFormattedTelephoneNumber() {
            AccountViewResponse response = populated();

            assertThat(violationsOf(response)).isEmpty();
            assertThat(response.phoneNumber1()).isEqualTo("(908)119-8310");
            assertThat(response.phoneNumber2()).isEqualTo("(373)693-8684");
        }

        @Test
        @DisplayName("accepts an unrecognised status character, so no allowed-values constraint exists")
        void acceptsAnUnrecognisedStatusCharacter() {
            for (String status : List.of("Q", "0", "B", " ", "y")) {
                assertThat(violationsOf(withAccountStatus(status)))
                        .describedAs("raw status %s on the read path", status)
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("accepts an absent national identifier, the one nullable column in the schema")
        void acceptsAnAbsentNationalIdentifier() throws JsonProcessingException {
            AccountViewResponse response = withSsn(null);

            assertThat(violationsOf(response)).isEmpty();
            assertThat(response.ssn()).isNull();
            assertThat(keysOf(payloadOf(response)))
                    .describedAs("absent means the key is omitted, never rendered as a null and "
                            + "never defaulted to an empty or zeroed value")
                    .doesNotContain("ssn");
            assertThat(moduleEquivalentMapper().writeValueAsString(response))
                    .doesNotContain("\"ssn\"")
                    .doesNotContain("null");
        }

        @Test
        @DisplayName("accepts a blank national identifier as distinct from an absent one")
        void acceptsABlankNationalIdentifier() throws JsonProcessingException {
            AccountViewResponse response = withSsn("            ");

            assertThat(violationsOf(response)).isEmpty();
            assertThat(response.ssn()).hasSize(12).isNotEmpty();
            assertThat(payloadOf(response).get("ssn").asText()).hasSize(12);
        }

        @Test
        @DisplayName("places no constraint on the two components the update program never edits")
        void placesNoConstraintOnTheUneditedComponents() {
            AccountViewResponse response = populated();

            assertThat(violationsOf(response)).isEmpty();
            assertThat(response.middleName())
                    .describedAs("a space between two letters, which the legacy check admits")
                    .isEqualTo(MIDDLE_NAME)
                    .hasSize(25)
                    .contains("n M");
            assertThat(response.addressLine2())
                    .isEqualTo(ADDRESS_LINE_2)
                    .hasSize(50)
                    .contains("e 4");
        }

        @Test
        @DisplayName("leaves the route unbounded, because a route has no legacy width to bound it by")
        void leavesTheRouteUnbounded() {
            String longRoute = "/api/accounts/00000000011/view?include=customer,card,crossReference"
                    + "&projection=full&locale=en-US&trace=00000000000000000000000000000000";

            assertThat(violationsOf(withNextRoute(longRoute)))
                    .describedAs("no width bound on a component with no measured legacy width")
                    .isEmpty();
            assertThat(withNextRoute(longRoute).nextRoute()).isEqualTo(longRoute);
        }

        @Test
        @DisplayName("reports each over-width component separately, and only the ones that overflow")
        void reportsEachOverWidthComponentSeparately() {
            AccountViewResponse response = populated("000000000110", ACCOUNT_STATUS, SSN,
                    "0012", FOCUS_SCREEN_FIELD_ID, NEXT_ROUTE, NAVIGATION_CONTEXT, CREDIT_LIMIT,
                    CASH_CREDIT_LIMIT, CURRENT_BALANCE, CURRENT_CYCLE_CREDIT, CURRENT_CYCLE_DEBIT);

            Set<String> paths = new LinkedHashSet<>();
            for (ConstraintViolation<AccountViewResponse> violation : violationsOf(response)) {
                paths.add(violation.getPropertyPath().toString());
            }

            assertThat(paths).containsExactlyInAnyOrder("accountId", "ficoScore");
        }
    }

    @Nested
    @DisplayName("absence, immutability and the wire shape")
    class AbsenceImmutabilityAndWireShape {
        @Test
        @DisplayName("tolerates an absent value in every component")
        void toleratesAnAbsentValueInEveryComponent() {
            AccountViewResponse response = allAbsent();

            assertThat(List.of(
                    String.valueOf(response.transactionName()),
                    String.valueOf(response.title01()),
                    String.valueOf(response.currentDate()),
                    String.valueOf(response.programName()),
                    String.valueOf(response.title02()),
                    String.valueOf(response.currentTime()),
                    String.valueOf(response.accountId()),
                    String.valueOf(response.accountStatus()),
                    String.valueOf(response.openDate()),
                    String.valueOf(response.creditLimit()),
                    String.valueOf(response.expirationDate()),
                    String.valueOf(response.cashCreditLimit()),
                    String.valueOf(response.reissueDate()),
                    String.valueOf(response.currentBalance()),
                    String.valueOf(response.currentCycleCredit()),
                    String.valueOf(response.accountGroupId()),
                    String.valueOf(response.currentCycleDebit()),
                    String.valueOf(response.customerId()),
                    String.valueOf(response.ssn()),
                    String.valueOf(response.dateOfBirth()),
                    String.valueOf(response.ficoScore()),
                    String.valueOf(response.firstName()),
                    String.valueOf(response.middleName()),
                    String.valueOf(response.lastName()),
                    String.valueOf(response.addressLine1()),
                    String.valueOf(response.stateCode()),
                    String.valueOf(response.addressLine2()),
                    String.valueOf(response.zipCode()),
                    String.valueOf(response.city()),
                    String.valueOf(response.countryCode()),
                    String.valueOf(response.phoneNumber1()),
                    String.valueOf(response.governmentIssuedId()),
                    String.valueOf(response.phoneNumber2()),
                    String.valueOf(response.eftAccountId()),
                    String.valueOf(response.primaryCardHolderIndicator()),
                    String.valueOf(response.infoMessage()),
                    String.valueOf(response.errorMessage()),
                    String.valueOf(response.focusScreenFieldId()),
                    String.valueOf(response.nextRoute()),
                    String.valueOf(response.navigationContext())))
                    .describedAs("all forty nullable components read back absent")
                    .hasSize(40)
                    .containsOnly("null");
            assertThat(response.inputError())
                    .describedAs("a primitive flag, so its absent form is the accepted state and the "
                                    + "contract has two states rather than three")
                    .isFalse();
        }

        @Test
        @DisplayName("is an immutable record, demonstrated by construction rather than by inspection")
        void isAnImmutableRecord() {
            AccountViewResponse response = populated();
            Record asRecord = response;

            assertThat(asRecord).isSameAs(response);
            assertThat(response.accountId()).isEqualTo(response.accountId());
            assertThat(response.currentBalance()).isSameAs(response.currentBalance());
            assertThat(populated())
                    .isEqualTo(response)
                    .isNotSameAs(response)
                    .hasSameHashCodeAs(response);
        }

        @Test
        @DisplayName("compares every component by value, as a wire contract requires")
        void comparesEveryComponentByValue() {
            AccountViewResponse response = populated();

            assertThat(response).isEqualTo(populated()).isNotEqualTo(allAbsent());
            assertThat(withAccountId("00000000012"))
                    .describedAs("one differing component is enough to make two responses unequal")
                    .isNotEqualTo(response);
            assertThat(withFicoScore("002")).isNotEqualTo(response);
            assertThat(response.hashCode()).isEqualTo(populated().hashCode());
        }

        @Test
        @DisplayName("serialises exactly the thirty-seven map items and the five control components")
        void serialisesExactlyTheDeclaredComponents() throws JsonProcessingException {
            Set<String> keys = keysOf(payloadOf(populated()));

            assertThat(keys)
                    .describedAs("the payload's own property set, in payload order")
                    .containsExactlyElementsOf(COMPONENTS_IN_MAP_ORDER)
                    .hasSize(42);
            assertThat(keys)
                    .doesNotContain("accountIdL", "accountIdF", "accountIdA", "accountIdC",
                            "accountIdP", "accountIdH", "accountIdV", "filler", "row", "column",
                            "cursorPosition", "attribute", "colour", "color", "highlight",
                            "version", "lockVersion", "optimisticLock",
                            "type", "title", "status", "detail", "instance");
        }

        @Test
        @DisplayName("omits an absent property rather than writing it as a null")
        void omitsAnAbsentPropertyRatherThanWritingNull() throws JsonProcessingException {
            String payload = moduleEquivalentMapper().writeValueAsString(allAbsent());

            assertThat(keysOf(payloadOf(allAbsent())))
                    .describedAs("the primitive indicator and the normalised finding list are the two "
                            + "components that have no absent state")
                    .containsExactly("inputError", "fieldErrors");
            assertThat(payload).isEqualTo("{\"inputError\":false,\"fieldErrors\":[]}")
                    .doesNotContain("null");
        }

        @Test
        @DisplayName("tolerates an unknown incoming property, and the type is not what tolerates it")
        void toleratesAnUnknownIncomingProperty() throws JsonProcessingException {
            String bodyWithUnknown =
                    "{\"accountId\":\"00000000011\",\"ficoScore\":\"001\",\"unmappedScreenField\":\"X\"}";

            AccountViewResponse revived = moduleEquivalentMapper()
                    .readValue(bodyWithUnknown, AccountViewResponse.class);

            assertThat(revived.accountId()).isEqualTo("00000000011");
            assertThat(revived.ficoScore()).isEqualTo("001");

            ObjectMapper strict = strictUnknownPropertyMapper();
            assertThatThrownBy(() -> strict.readValue(bodyWithUnknown, AccountViewResponse.class))
                    .isInstanceOf(JsonProcessingException.class)
                    .hasMessageContaining("unmappedScreenField");
        }

        @Test
        @DisplayName("binds back from a payload carrying only some of its components")
        void bindsBackFromAPartialPayload() throws JsonProcessingException {
            AccountViewResponse revived = moduleEquivalentMapper()
                    .readValue("{\"errorMessage\":\"Account not found\",\"inputError\":true}",
                            AccountViewResponse.class);

            assertThat(revived.errorMessage()).isEqualTo("Account not found");
            assertThat(revived.inputError()).isTrue();
            assertThat(revived.accountId()).isNull();
            assertThat(revived.creditLimit()).isNull();
        }
    }

    @Nested
    @DisplayName("the outcome components are declarative data, never logic")
    class OutcomeComponentsAreData {
        @Test
        @DisplayName("carries the next route as an opaque string, with no vocabulary of its own")
        void carriesTheNextRouteAsAnOpaqueString() {
            AccountViewResponse response = populated();
            String nextRoute = response.nextRoute();

            assertThat(nextRoute).isEqualTo(NEXT_ROUTE);
            for (String route : List.of("/api/accounts/view", "", "   ", "not-a-route",
                    "CAVW", "COACTVWC")) {
                assertThat(withNextRoute(route).nextRoute())
                        .describedAs("route %s carried without interpretation", route)
                        .isEqualTo(route);
            }
            assertThat(withNextRoute(null).nextRoute())
                    .describedAs("a response nominating no route")
                    .isNull();
        }

        @Test
        @DisplayName("lets the route vary without changing anything else")
        void letsTheRouteVaryWithoutChangingAnythingElse() {
            AccountViewResponse one = withNextRoute("/api/accounts/view");
            AccountViewResponse other = withNextRoute("/api/accounts/update");

            assertThat(other.accountId()).isEqualTo(one.accountId());
            assertThat(other.inputError()).isEqualTo(one.inputError());
            assertThat(other.focusScreenFieldId()).isEqualTo(one.focusScreenFieldId());
            assertThat(other.errorMessage()).isEqualTo(one.errorMessage());
            assertThat(other.currentBalance()).isEqualTo(one.currentBalance());
        }

        @Test
        @DisplayName("states the rejection explicitly rather than inferring it from message text")
        void statesTheRejectionExplicitly() {
            AccountViewResponse response = populated();

            assertThat(response.inputError()).isTrue();
            assertThat(response.errorMessage()).isEqualTo(ERROR_MESSAGE);
            assertThat(allAbsent().inputError())
                    .describedAs("no message text and no rejection")
                    .isFalse();
        }

        @Test
        @DisplayName("carries the focus hint as the legacy field name, never as a coordinate")
        void carriesTheFocusHintAsTheLegacyFieldName() {
            AccountViewResponse response = populated();
            String focus = response.focusScreenFieldId();

            assertThat(focus)
                    .describedAs("the account-number field, which is the only field this screen "
                            + "nominates, and the widest name any mapset declares")
                    .isEqualTo("ACCTSID")
                    .hasSize(7);
            assertThat(violationsOf(response)).isEmpty();
            assertThat(violationsOf(populated(ACCOUNT_ID, ACCOUNT_STATUS, SSN, FICO_SCORE,
                    "ACCTSIDX", NEXT_ROUTE, NAVIGATION_CONTEXT, CREDIT_LIMIT, CASH_CREDIT_LIMIT,
                    CURRENT_BALANCE, CURRENT_CYCLE_CREDIT, CURRENT_CYCLE_DEBIT)))
                    .describedAs("an eighth character could never name a field on any screen, "
                            + "because the map generator reserves that position for its own suffix")
                    .hasSize(1);
        }

        @Test
        @DisplayName("renders a diagnostic that withholds regulated values but keeps the outcome")
        void rendersADiagnosticThatWithholdsRegulatedValues() {
            AccountViewResponse response = populated();
            String rendered = response.toString();

            assertThat(rendered)
                    .startsWith("AccountViewResponse[")
                    .contains("inputError=true")
                    .contains("focusScreenFieldId=ACCTSID")
                    .contains("nextRoute=" + NEXT_ROUTE)
                    .doesNotContain("000-00-0000")
                    .doesNotContain("Vandelay")
                    .doesNotContain("(908)119-8310")
                    .doesNotContain("5000.00")
                    .doesNotContain("00000000011");
            assertThat(response.ssn())
                    .describedAs("nothing transported is altered by the diagnostic rendering")
                    .isEqualTo(SSN);
            assertThat(response.accountId()).isEqualTo(ACCOUNT_ID);
        }

        /**
         * The invariant: <strong>a route must never embed a business or regulated identifier.</strong>
         * The diagnostic rendering redacts every regulated and business value the record itself holds,
         * but it passes the route through verbatim, so an identifier placed inside a route defeats that
         * redaction and reaches every log, trace and error page the rendering reaches. The route this
         * contract must carry is the key-free form, which the second assertion holds it to.
         *
         * <p>The first assertion is a negative control and its value is a <em>prohibited</em> shape,
         * present only to demonstrate that the redaction does not extend to the route and therefore
         * cannot rescue a route that carries a key. It is not an example to copy.
         */
        @Test
        @DisplayName("retains the route verbatim in the diagnostic, so a route is no place for a key")
        void retainsTheRouteVerbatimInTheDiagnostic() {
            String prohibitedKeyBearingRoute = "/api/accounts/00000000011/view";

            assertThat(withNextRoute(prohibitedKeyBearingRoute).toString())
                    .describedAs("the route is retained exactly as supplied")
                    .contains("nextRoute=" + prohibitedKeyBearingRoute);
            assertThat(populated().toString())
                    .describedAs("a key-free route leaves no identifier anywhere in the rendering")
                    .contains("nextRoute=/api/accounts/view")
                    .doesNotContain("00000000011")
                    .doesNotContain("000000011");
        }
    }
}
