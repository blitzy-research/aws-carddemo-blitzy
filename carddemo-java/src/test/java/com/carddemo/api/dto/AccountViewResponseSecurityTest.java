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
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link AccountViewResponse} cannot disclose a regulated value through its stringified
 * form, while still carrying every one of them on the wire.
 *
 * <p>This is the widest response in the module and the one carrying the most regulated data:
 * twenty-three of its forty-one components are withheld. They span four kinds of disclosure - durable
 * record keys, monetary and credit-score data, personal identity including a tax identifier and a
 * government-issued identifier, and address and telephone data. A single accidental interpolation of
 * this object into a log line would disclose an identified person's full financial position, home
 * address, date of birth and two identity numbers at once, which is why the withheld set here is drawn
 * wider than on any sibling contract.
 *
 * <p>Two of the twenty-three are withheld more conservatively than a narrow reading would require. A
 * two-character state code and a three-character country code identify nobody on their own, and they
 * are withheld anyway: the review that required this rendering names addresses as regulated without
 * carving out their coarser components, and the diagnostic value of a state code is close to nil. The
 * test below pins that decision so a later reader does not "optimise" it back.
 *
 * <p>Short regulated values are asserted at component level as well as by whole-value scan, because a
 * two-character value can appear inside an unrelated retained value by coincidence and a scan alone
 * would then be proving the wrong thing. The component-level assertion is the definitive one.
 */
@DisplayName("AccountViewResponse - diagnostic rendering safety for legacy transaction CAVW")
class AccountViewResponseSecurityTest {

    private static final String PLACEHOLDER = "***REDACTED***";

    private static final String ACCOUNT_ID = "78412590063";
    private static final BigDecimal CREDIT_LIMIT = new BigDecimal("15000.00");
    private static final BigDecimal CASH_CREDIT_LIMIT = new BigDecimal("3500.00");
    private static final BigDecimal CURRENT_BALANCE = new BigDecimal("1284.57");
    private static final BigDecimal CURRENT_CYCLE_CREDIT = new BigDecimal("642.19");
    private static final BigDecimal CURRENT_CYCLE_DEBIT = new BigDecimal("973.68");
    private static final String CUSTOMER_ID = "529174836";
    private static final String SSN = "612-84-9370";
    private static final String DATE_OF_BIRTH = "1974-08-22";
    private static final String FICO_SCORE = "742";
    private static final String FIRST_NAME = "MARIANNE";
    private static final String MIDDLE_NAME = "THEODORA";
    private static final String LAST_NAME = "OSULLIVAN";
    private static final String ADDRESS_LINE_1 = "1427 MULBERRY TERRACE";
    private static final String STATE_CODE = "IL";
    private static final String ADDRESS_LINE_2 = "APARTMENT 6B REAR";
    private static final String ZIP_CODE = "60613";
    private static final String CITY = "NORTHBROOK";
    private static final String COUNTRY_CODE = "USA";
    private static final String PHONE_NUMBER_1 = "(312)555-8841";
    private static final String GOVERNMENT_ISSUED_ID = "IL-DL-K8842176";
    private static final String PHONE_NUMBER_2 = "(847)555-2290";
    private static final String EFT_ACCOUNT_ID = "9938271465";

    /**
     * The twenty-five components the renderer withholds, in declaration order.
     *
     * <p>The last two are the message lines. They are withheld because this program composes its texts
     * around the business key rather than around a field label: the not-found text built at
     * {@code app/cbl/COACTVWC.cbl} lines 747 to 757 concatenates the account identifier into the
     * message itself, and the two sibling texts at 796 to 806 and 846 to 856 are built the same way,
     * so rendering a message line would reintroduce exactly what withholding the identifiers removed.
     */
    private static final List<String> WITHHELD_COMPONENTS = List.of(
            "accountId", "creditLimit", "cashCreditLimit", "currentBalance", "currentCycleCredit",
            "currentCycleDebit", "customerId", "ssn", "dateOfBirth", "ficoScore", "firstName",
            "middleName", "lastName", "addressLine1", "stateCode", "addressLine2", "zipCode", "city",
            "countryCode", "phoneNumber1", "governmentIssuedId", "phoneNumber2", "eftAccountId",
            "infoMessage", "errorMessage");

    /**
     * Regulated values long and distinctive enough for a whole-value and fragment scan to be
     * meaningful. The three short codes are excluded here and asserted at component level instead.
     */
    private static final List<String> SCANNABLE_REGULATED_VALUES = List.of(
            ACCOUNT_ID, CREDIT_LIMIT.toPlainString(), CASH_CREDIT_LIMIT.toPlainString(),
            CURRENT_BALANCE.toPlainString(), CURRENT_CYCLE_CREDIT.toPlainString(),
            CURRENT_CYCLE_DEBIT.toPlainString(), CUSTOMER_ID, SSN, DATE_OF_BIRTH, FIRST_NAME,
            MIDDLE_NAME, LAST_NAME, ADDRESS_LINE_1, ADDRESS_LINE_2, ZIP_CODE, CITY, PHONE_NUMBER_1,
            GOVERNMENT_ISSUED_ID, PHONE_NUMBER_2, EFT_ACCOUNT_ID);

    /** The three short regulated codes, definitively covered by component-level assertions. */
    private static final List<String> SHORT_REGULATED_COMPONENTS =
            List.of("ficoScore", "stateCode", "countryCode");

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

    private static AccountViewResponse populated() {
        return populatedWith(null);
    }

    private static AccountViewResponse populatedWith(NavigationContext navigation) {
        return new AccountViewResponse(
                "CAVW", "TITLE ONE", "07/19/22", "COACTVWC", "TITLE TWO", "14:23:07",
                ACCOUNT_ID, "Y", "2019-03-14", CREDIT_LIMIT, "2027-03-31", CASH_CREDIT_LIMIT,
                "2024-11-05", CURRENT_BALANCE, CURRENT_CYCLE_CREDIT, "GOLD001", CURRENT_CYCLE_DEBIT,
                CUSTOMER_ID, SSN, DATE_OF_BIRTH, FICO_SCORE, FIRST_NAME, MIDDLE_NAME, LAST_NAME,
                ADDRESS_LINE_1, STATE_CODE, ADDRESS_LINE_2, ZIP_CODE, CITY, COUNTRY_CODE,
                PHONE_NUMBER_1, GOVERNMENT_ISSUED_ID, PHONE_NUMBER_2, EFT_ACCOUNT_ID, "Y",
                "INFORMATION LINE", "ERROR LINE", true, "ACCTSID", "route/next", navigation);
    }

    /** The same response shape carrying entirely different regulated values. */
    private static AccountViewResponse alternate() {
        return new AccountViewResponse(
                "CAVW", "TITLE ONE", "07/19/22", "COACTVWC", "TITLE TWO", "14:23:07",
                "00000000001", "Y", "2019-03-14", new BigDecimal("99999999.99"),
                "2027-03-31", new BigDecimal("0.00"), "2024-11-05", new BigDecimal("-500.25"),
                new BigDecimal("0.02"), "GOLD001", new BigDecimal("0.03"), "111111111",
                "000-00-0000", "2001-01-01", "300", "A", "B", "C", "X", "CA", "Y", "90210",
                "LOS ANGELES", "CAN", "(555)000-0000", "CA-DL-000001", "(555)111-1111", "1",
                "Y", "INFORMATION LINE", "ERROR LINE", true, "ACCTSID", "route/next", null);
    }

    private static AccountViewResponse empty() {
        return new AccountViewResponse(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, false, null, null, null);
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

    private static JsonNode payloadOf(AccountViewResponse response) throws Exception {
        return moduleEquivalentMapper().readTree(moduleEquivalentMapper().writeValueAsString(response));
    }

    /**
     * The serialized JSON text exactly as a client would receive it.
     *
     * <p>Used in preference to a re-parsed tree wherever the assertion is about decimal scale. Reading
     * a value back through {@link ObjectMapper#readTree} coerces a JSON float into a {@code double} by
     * default, so a serialized {@code 15000.00} returns as {@code "15000.0"} and a scale assertion made
     * against the tree would fail even though the emitted bytes are correct. The bytes are what the
     * byte-equivalence criterion cares about, so the bytes are what is asserted.
     */
    private static String rawJsonOf(AccountViewResponse response) throws Exception {
        return moduleEquivalentMapper().writeValueAsString(response);
    }

    @Nested
    @DisplayName("No regulated value survives the rendering")
    class NoRegulatedValueSurvives {

        @Test
        @DisplayName("every scannable regulated value is absent from the rendering in whole")
        void everyScannableRegulatedValueIsAbsentInWhole() {
            assertThat(populated().toString()).doesNotContain(SCANNABLE_REGULATED_VALUES);
        }

        @Test
        @DisplayName("no five-character run of any scannable regulated value survives, so nothing is "
                + "disclosed in part")
        void noFiveCharacterRunSurvives() {
            String rendered = populated().toString();
            for (String value : SCANNABLE_REGULATED_VALUES) {
                for (int start = 0; start + 5 <= value.length(); start++) {
                    String fragment = value.substring(start, start + 5);
                    assertThat(rendered)
                            .withFailMessage("fragment %s of %s must not appear in %s",
                                    fragment, value, rendered)
                            .doesNotContain(fragment);
                }
            }
        }

        @Test
        @DisplayName("the tax identifier does not survive as its bare digits with the separators "
                + "stripped, so removing punctuation does not recover it")
        void theTaxIdentifierDoesNotSurviveWithSeparatorsStripped() {
            assertThat(populated().toString()).doesNotContain(SSN.replace("-", ""));
        }

        @Test
        @DisplayName("the government-issued identifier does not survive in whole or as its serial part")
        void theGovernmentIssuedIdentifierDoesNotSurvive() {
            String rendered = populated().toString();
            assertThat(rendered)
                    .doesNotContain(GOVERNMENT_ISSUED_ID)
                    .doesNotContain("K8842176");
        }

        @Test
        @DisplayName("no monetary value survives in scaled, unscaled or truncated form, so the account "
                + "balance cannot be reconstructed to any precision")
        void noMonetaryValueSurvivesInAnyNumericForm() {
            String rendered = populated().toString();
            for (BigDecimal amount : List.of(CREDIT_LIMIT, CASH_CREDIT_LIMIT, CURRENT_BALANCE,
                    CURRENT_CYCLE_CREDIT, CURRENT_CYCLE_DEBIT)) {
                assertThat(rendered)
                        .doesNotContain(amount.toPlainString())
                        .doesNotContain(amount.unscaledValue().toString())
                        .doesNotContain(amount.setScale(0, java.math.RoundingMode.DOWN)
                                .toPlainString());
            }
        }

        @Test
        @DisplayName("no name part survives, so splitting the identity across three components does "
                + "not leave one of them printable")
        void noNamePartSurvives() {
            String rendered = populated().toString();
            assertThat(rendered)
                    .doesNotContain(FIRST_NAME)
                    .doesNotContain(MIDDLE_NAME)
                    .doesNotContain(LAST_NAME);
        }

        @Test
        @DisplayName("neither telephone number survives in whole or with its formatting removed")
        void neitherTelephoneNumberSurvives() {
            String rendered = populated().toString();
            for (String phone : List.of(PHONE_NUMBER_1, PHONE_NUMBER_2)) {
                assertThat(rendered)
                        .doesNotContain(phone)
                        .doesNotContain(phone.replaceAll("[^0-9]", ""));
            }
        }
    }

    @Nested
    @DisplayName("The stand-in is constant and reveals nothing about what it replaced")
    class TheStandInIsConstant {

        @Test
        @DisplayName("each of the twenty-five withheld components renders as the same fixed stand-in")
        void eachWithheldComponentRendersAsTheFixedStandIn() {
            String rendered = populated().toString();
            assertThat(WITHHELD_COMPONENTS).hasSize(25);
            WITHHELD_COMPONENTS.forEach(name ->
                    assertThat(rendered).contains(name + "=" + PLACEHOLDER));
        }

        @Test
        @DisplayName("the stand-in appears exactly twenty-five times, so no regulated component is "
                + "left rendering itself")
        void theStandInAppearsExactlyOncePerWithheldComponent() {
            assertThat(occurrencesOf(populated().toString(), PLACEHOLDER))
                    .isEqualTo(WITHHELD_COMPONENTS.size());
        }

        @Test
        @DisplayName("the three short codes are withheld at component level, which is the definitive "
                + "check a whole-value scan cannot make for a two-character value")
        void theShortCodesAreWithheldAtComponentLevel() {
            String rendered = populated().toString();
            SHORT_REGULATED_COMPONENTS.forEach(name ->
                    assertThat(rendered).contains(name + "=" + PLACEHOLDER));
        }

        @Test
        @DisplayName("the coarse state and country codes are withheld deliberately, not incidentally, "
                + "so a later reader does not restore them as harmless")
        void theCoarseGeographyCodesAreWithheldDeliberately() {
            String rendered = populated().toString();
            assertThat(rendered)
                    .contains("stateCode=" + PLACEHOLDER)
                    .contains("countryCode=" + PLACEHOLDER)
                    .doesNotContain("stateCode=" + STATE_CODE)
                    .doesNotContain("countryCode=" + COUNTRY_CODE);
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
        @DisplayName("two responses differing only in their twenty-five regulated values render "
                + "identically, which is presence and length disclosure ruled out together")
        void responsesDifferingOnlyInRegulatedValuesRenderIdentically() {
            assertThat(alternate().toString()).isEqualTo(populated().toString());
        }
    }

    @Nested
    @DisplayName("The retained components stay visible, so the rendering remains diagnostic")
    class TheRetainedComponentsStayVisible {

        @Test
        @DisplayName("the status code, the three account calendar dates, the group code and the "
                + "primary-cardholder flag are shown as supplied")
        void theDescriptiveAccountComponentsAreShown() {
            assertThat(populated().toString())
                    .contains("accountStatus=Y")
                    .contains("openDate=2019-03-14")
                    .contains("expirationDate=2027-03-31")
                    .contains("reissueDate=2024-11-05")
                    .contains("accountGroupId=GOLD001")
                    .contains("primaryCardHolderIndicator=Y");
        }

        @Test
        @DisplayName("the screen furniture, the error flag, the focus field and the route are shown, "
                + "so a failure remains diagnosable from the rendering alone")
        void theErrorSurfaceAndFurnitureAreShown() {
            assertThat(populated().toString())
                    .startsWith("AccountViewResponse[")
                    .contains("transactionName=CAVW")
                    .contains("programName=COACTVWC")
                    .contains("currentDate=07/19/22")
                    .contains("currentTime=14:23:07")
                    .contains("inputError=true")
                    .contains("focusScreenFieldId=ACCTSID")
                    .contains("nextRoute=route/next");
        }

        @Test
        @DisplayName("the two message lines are the one part of the error surface that is withheld, "
                + "because this program builds its texts around the account identifier")
        void bothMessageLinesAreWithheld() {
            assertThat(populated().toString())
                    .contains("infoMessage=" + PLACEHOLDER)
                    .contains("errorMessage=" + PLACEHOLDER)
                    .doesNotContain("INFORMATION LINE")
                    .doesNotContain("ERROR LINE");
        }

        @Test
        @DisplayName("an account identifier concatenated into a message line, as COACTVWC composes it, "
                + "does not reach the rendering")
        void anIdentifierEmbeddedInAMessageLineDoesNotReachTheRendering() {
            AccountViewResponse notFound = new AccountViewResponse(
                    "CAVW", "TITLE ONE", "07/19/22", "COACTVWC", "TITLE TWO", "14:23:07",
                    ACCOUNT_ID, "Y", "2019-03-14", CREDIT_LIMIT, "2027-03-31", CASH_CREDIT_LIMIT,
                    "2024-11-05", CURRENT_BALANCE, CURRENT_CYCLE_CREDIT, "GOLD001",
                    CURRENT_CYCLE_DEBIT, CUSTOMER_ID, SSN, DATE_OF_BIRTH, FICO_SCORE, FIRST_NAME,
                    MIDDLE_NAME, LAST_NAME, ADDRESS_LINE_1, STATE_CODE, ADDRESS_LINE_2, ZIP_CODE,
                    CITY, COUNTRY_CODE, PHONE_NUMBER_1, GOVERNMENT_ISSUED_ID, PHONE_NUMBER_2,
                    EFT_ACCOUNT_ID, "Y",
                    "Account " + ACCOUNT_ID + " not found in Cross ref file..",
                    "Account " + ACCOUNT_ID + " not found in Cross ref file..",
                    true, "ACCTSID", "route/next", null);

            assertThat(notFound.toString())
                    .doesNotContain(ACCOUNT_ID)
                    .doesNotContain("not found in Cross ref file")
                    .contains("infoMessage=" + PLACEHOLDER)
                    .contains("errorMessage=" + PLACEHOLDER);
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
                    "CM00", "COMEN01C", "CAVW", "COACTVWC", "USER0001", "U",
                    NavigationContext.ProgramContext.REENTER,
                    CUSTOMER_ID, FIRST_NAME, MIDDLE_NAME, LAST_NAME,
                    ACCOUNT_ID, "Y", "5555444433332222", "COACTVA", "COACTVW");

            String rendered = populatedWith(navigation).toString();
            assertThat(rendered)
                    .doesNotContain(CUSTOMER_ID)
                    .doesNotContain(ACCOUNT_ID)
                    .doesNotContain(LAST_NAME)
                    .doesNotContain("5555444433332222");
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
                    .startsWith("AccountViewResponse[")
                    .endsWith("]")
                    .doesNotContain(SCANNABLE_REGULATED_VALUES);
            assertThat(occurrencesOf(rendered, PLACEHOLDER)).isEqualTo(WITHHELD_COMPONENTS.size());
        }
    }

    @Nested
    @DisplayName("The wire payload still carries every withheld value in full")
    class TheWirePayloadStillCarriesEveryValue {

        @Test
        @DisplayName("the identity and identifier components serialise at their full untouched values")
        void theIdentityComponentsSerialiseInFull() throws Exception {
            JsonNode payload = payloadOf(populated());
            assertThat(payload.get("accountId").asText()).isEqualTo(ACCOUNT_ID);
            assertThat(payload.get("customerId").asText()).isEqualTo(CUSTOMER_ID);
            assertThat(payload.get("ssn").asText()).isEqualTo(SSN);
            assertThat(payload.get("dateOfBirth").asText()).isEqualTo(DATE_OF_BIRTH);
            assertThat(payload.get("firstName").asText()).isEqualTo(FIRST_NAME);
            assertThat(payload.get("middleName").asText()).isEqualTo(MIDDLE_NAME);
            assertThat(payload.get("lastName").asText()).isEqualTo(LAST_NAME);
            assertThat(payload.get("governmentIssuedId").asText()).isEqualTo(GOVERNMENT_ISSUED_ID);
            assertThat(payload.get("eftAccountId").asText()).isEqualTo(EFT_ACCOUNT_ID);
        }

        @Test
        @DisplayName("the address, geography and telephone components serialise in full, including the "
                + "two coarse codes the rendering withholds")
        void theAddressAndGeographyComponentsSerialiseInFull() throws Exception {
            JsonNode payload = payloadOf(populated());
            assertThat(payload.get("addressLine1").asText()).isEqualTo(ADDRESS_LINE_1);
            assertThat(payload.get("addressLine2").asText()).isEqualTo(ADDRESS_LINE_2);
            assertThat(payload.get("city").asText()).isEqualTo(CITY);
            assertThat(payload.get("zipCode").asText()).isEqualTo(ZIP_CODE);
            assertThat(payload.get("stateCode").asText()).isEqualTo(STATE_CODE);
            assertThat(payload.get("countryCode").asText()).isEqualTo(COUNTRY_CODE);
            assertThat(payload.get("phoneNumber1").asText()).isEqualTo(PHONE_NUMBER_1);
            assertThat(payload.get("phoneNumber2").asText()).isEqualTo(PHONE_NUMBER_2);
        }

        @Test
        @DisplayName("every monetary component serialises at its exact two-place scale in the emitted "
                + "bytes, trailing zeros included, and the credit score serialises unchanged")
        void theMonetaryComponentsSerialiseAtExactScale() throws Exception {
            String json = rawJsonOf(populated());
            assertThat(json)
                    .contains("\"creditLimit\":15000.00")
                    .contains("\"cashCreditLimit\":3500.00")
                    .contains("\"currentBalance\":1284.57")
                    .contains("\"currentCycleCredit\":642.19")
                    .contains("\"currentCycleDebit\":973.68");
            assertThat(payloadOf(populated()).get("ficoScore").asText()).isEqualTo(FICO_SCORE);
        }

        @Test
        @DisplayName("the stand-in never leaks into the serialized form, so no client ever receives a "
                + "redacted value in place of a real one")
        void theStandInNeverReachesTheWire() throws Exception {
            assertThat(payloadOf(populated()).toString()).doesNotContain("REDACTED");
        }
    }
}
