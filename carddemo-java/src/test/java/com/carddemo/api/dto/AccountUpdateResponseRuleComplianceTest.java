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

import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit tests for {@link AccountUpdateResponse}, the response body of legacy transaction {@code CAUP}
 * implemented by {@code app/cbl/COACTUPC.cbl} - the largest program in the estate at 4,236 lines and 85
 * paragraphs - over screen {@code app/cpy-bms/COACTUP.CPY}.
 *
 * <p><strong>Provenance.</strong> Read from the mainframe estate at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * <p><strong>Two components deliberately carry no width, and that is this class's most important
 * assertion.</strong> The legacy program decorates thirty-nine fields for error display through the
 * {@code CSSETATY} macro, but two of the thirty-nine are decorated and never actually validated - the
 * source says "no edits coded" for the middle name at {@code app/cbl/COACTUPC.cbl:3346} and "NO EDITS
 * CODED AS YET" for the second address line at {@code :3370}. Attaching a constraint to either would
 * reject input the legacy accepts, which is the feature expansion the plan forbids. The absence is
 * therefore asserted here rather than left as an inconsistency a later reader might tidy away.
 *
 * <p><strong>Dates and telephone numbers arrive in pieces.</strong> The screen collects three date
 * triples and two telephone triples as separate fields because a 3270 map has no composite field type,
 * and the legacy validation cascade reports against the individual piece - an area code that is blank is
 * a different failure from one that is not three digits, and both name the area code rather than the
 * whole number. The pieces are therefore kept separate and separately bounded.
 *
 * <p><strong>The rendering withholds every value.</strong> This response carries a social security
 * number in three pieces, a date of birth, a government-issued identifier and five monetary amounts, so
 * its {@code toString} publishes only the failure shape and substitutes a single placeholder for the
 * values. That is asserted positively - the placeholder is present - and negatively, by checking that no
 * populated value reaches the rendering.
 *
 * @see AccountUpdateResponse
 */
@DisplayName("AccountUpdateResponse - the CAUP account update response contract")
class AccountUpdateResponseRuleComplianceTest {

    /** The number of components the response declares. */
    private static final int DECLARED_COMPONENT_COUNT = 57;

    /**
     * The number of components that correspond to a screen field.
     *
     * <p>Every component up to this index is a map field in screen order. The one that follows is not:
     * it is the sealed description of the account as it stood when the screen was sent, which the client
     * echoes back on the confirming turn.
     */
    private static final int SCREEN_FIELD_COMPONENT_COUNT = 56;

    /**
     * A sealed description of the account as it stood when the screen was sent.
     *
     * <p>Opaque here on purpose: minting and verifying it belongs to the service, and this contract only
     * has to carry it out and back unchanged.
     */
    private static final String CONCURRENCY_TOKEN =
            "v1.YWNjdDowMDAwMDAwMDAxMQ==.Y3VzdDowMDAwMDAwMTE=.c2lnbmF0dXJl";

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
    private static JsonNode payloadOf(final AccountUpdateResponse response)
            throws JsonProcessingException {
        final ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(response));
    }

    /**
     * Builds a response with every component populated at or inside its declared width.
     *
     * @param fieldErrors the per-field errors to publish, possibly {@code null}
     * @return the populated response
     */
    private static AccountUpdateResponse aFullyPopulatedResponse(
            final List<ErrorResponse.FieldError> fieldErrors) {

        return new AccountUpdateResponse(
                "CAUP", "AWS Mainframe Modernization", "01/15/22", "COACTUPC", "CardDemo",
                "10:30:00", "00000000011", "Y", "2020", "01",
                "15", new BigDecimal("5000.00"), "2025", "12", "31",
                new BigDecimal("1000.00"), "2023", "06", "30", new BigDecimal("1234.56"),
                new BigDecimal("100.00"), "GROUP-A", new BigDecimal("50.00"), "000000011", "123",
                "45", "6789", "1980", "06", "30",
                "720", "JOHN", "QUINCY", "PUBLIC", "1 MAIN ST",
                "NY", "APT 2", "10001", "NEW YORK", "USA",
                "212", "555", "0100", "DL-987654321", "212",
                "555", "0101", "EFT0000001", "Y", AccountUpdateResponse.MSG_LOOKS_GOOD_SO_FAR,
                null, false, "ACSTTUS", "account-update", NavigationContext.empty(),
                fieldErrors, CONCURRENCY_TOKEN);
    }

    /**
     * Builds a response carrying only the named components, every other component absent.
     *
     * <p>Declared once so that a single positional constructor call over fifty-seven components has to
     * be right, rather than a dozen near-identical walls of nulls any one of which could drift a
     * component out of line without the compiler noticing - most components are the same nullable type.
     *
     * <p>The concurrency token is left absent throughout. It is not a screen field, so no assertion
     * about a screen field's width, rendering or absence has anything to say about it, and the tests
     * that are about the token populate it through {@link #aFullyPopulatedResponse} instead.
     *
     * @param accountId          the account identifier, or {@code null}
     * @param ficoScore          the credit score, or {@code null}
     * @param middleName         the middle name, or {@code null}
     * @param addressLine2       the second address line, or {@code null}
     * @param infoMessage        the information message, or {@code null}
     * @param errorMessage       the error message, or {@code null}
     * @param error              the general error flag
     * @param focusScreenFieldId the field to place the cursor in, or {@code null}
     * @param nextRoute          the route the client should call next, or {@code null}
     * @param navigationContext  the navigation state to echo, or {@code null}
     * @param fieldErrors        the per-field errors, or {@code null}
     * @return the sparse response
     */
    private static AccountUpdateResponse aSparseResponse(final String accountId,
            final String ficoScore, final String middleName, final String addressLine2,
            final String infoMessage, final String errorMessage, final boolean error,
            final String focusScreenFieldId, final String nextRoute,
            final NavigationContext navigationContext,
            final List<ErrorResponse.FieldError> fieldErrors) {

        return new AccountUpdateResponse(
                null, null, null, null, null,
                null, accountId, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null,
                ficoScore, null, middleName, null, null,
                null, addressLine2, null, null, null,
                null, null, null, null, null,
                null, null, null, null, infoMessage,
                errorMessage, error, focusScreenFieldId, nextRoute, navigationContext,
                fieldErrors, null);
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
        final Size size = AccountUpdateResponse.class.getDeclaredMethod(componentName)
                .getAnnotation(Size.class);
        assertThat(size).as("component %s declares no upper bound", componentName).isNotNull();
        return size.max();
    }

    /**
     * Every twelve validation-message suffix the response publishes.
     *
     * @return the suffixes in declaration order
     */
    private static List<String> everySuffix() {
        return List.of(AccountUpdateResponse.SUFFIX_FICO_OUT_OF_RANGE,
                AccountUpdateResponse.SUFFIX_STATE_NOT_VALID,
                AccountUpdateResponse.SUFFIX_AREA_CODE_REQUIRED,
                AccountUpdateResponse.SUFFIX_AREA_CODE_NOT_3_DIGITS,
                AccountUpdateResponse.SUFFIX_AREA_CODE_ZERO,
                AccountUpdateResponse.SUFFIX_AREA_CODE_NOT_GENERAL_PURPOSE,
                AccountUpdateResponse.SUFFIX_PREFIX_REQUIRED,
                AccountUpdateResponse.SUFFIX_PREFIX_NOT_3_DIGITS,
                AccountUpdateResponse.SUFFIX_PREFIX_ZERO,
                AccountUpdateResponse.SUFFIX_LINE_NUMBER_REQUIRED,
                AccountUpdateResponse.SUFFIX_LINE_NUMBER_NOT_4_DIGITS,
                AccountUpdateResponse.SUFFIX_LINE_NUMBER_ZERO);
    }

    /**
     * Every whole operator message the response publishes.
     *
     * @return the messages in declaration order
     */
    private static List<String> everyWholeMessage() {
        return List.of(AccountUpdateResponse.MSG_INVALID_ZIP_FOR_STATE,
                AccountUpdateResponse.MSG_ACCOUNT_NUMBER_NOT_USABLE,
                AccountUpdateResponse.MSG_ACCOUNT_STATUS_MUST_BE_YES_NO,
                AccountUpdateResponse.MSG_CREDIT_LIMIT_REQUIRED,
                AccountUpdateResponse.MSG_CREDIT_LIMIT_NOT_VALID,
                AccountUpdateResponse.MSG_EXPIRY_MONTH_NOT_VALID,
                AccountUpdateResponse.MSG_EXPIRY_YEAR_NOT_VALID,
                AccountUpdateResponse.MSG_ACCOUNT_NOT_IN_CARD_DATABASE,
                AccountUpdateResponse.MSG_NO_CARDS_FOR_SEARCH_CONDITION,
                AccountUpdateResponse.MSG_CARD_DATA_READ_ERROR,
                AccountUpdateResponse.MSG_LOOKS_GOOD_SO_FAR,
                AccountUpdateResponse.MSG_COULD_NOT_HOLD_ACCOUNT_FOR_UPDATE,
                AccountUpdateResponse.MSG_COULD_NOT_HOLD_CUSTOMER_FOR_UPDATE,
                AccountUpdateResponse.MSG_RECORD_CHANGED_BEFORE_UPDATE,
                AccountUpdateResponse.MSG_UPDATE_OF_RECORD_FAILED);
    }

    // =============================================================================================

    @Nested
    @DisplayName("the two documented no-validation fields")
    class TheTwoDocumentedNoValidationFields {

        @Test
        @DisplayName("the middle name carries no upper bound, because the legacy source records that no "
                + "edits are coded for it")
        void theMiddleNameCarriesNoUpperBound() throws NoSuchMethodException {
            assertThat(AccountUpdateResponse.class.getDeclaredMethod("middleName")
                    .getAnnotation(Size.class))
                    .as("attaching a width here would reject input the legacy accepts")
                    .isNull();
        }

        @Test
        @DisplayName("the second address line carries no upper bound, for the same recorded reason")
        void theSecondAddressLineCarriesNoUpperBound() throws NoSuchMethodException {
            assertThat(AccountUpdateResponse.class.getDeclaredMethod("addressLine2")
                    .getAnnotation(Size.class))
                    .as("attaching a width here would reject input the legacy accepts")
                    .isNull();
        }

        @Test
        @DisplayName("they are the only two name or address components without a bound, so the exception "
                + "is exactly two fields wide and has not spread")
        void theyAreTheOnlyTwoNameOrAddressComponentsWithoutABound()
                throws NoSuchMethodException {

            for (final String bounded : List.of("firstName", "lastName", "addressLine1", "city",
                    "stateCode", "zipCode", "countryCode")) {
                assertThat(AccountUpdateResponse.class.getDeclaredMethod(bounded)
                        .getAnnotation(Size.class))
                        .as("component %s must keep its bound", bounded)
                        .isNotNull();
            }
        }

        @ParameterizedTest(name = "a {0}-character value")
        @ValueSource(ints = {1, 25, 26, 200})
        @DisplayName("a middle name of any length passes validation, including one longer than the first "
                + "and last names accept")
        void aMiddleNameOfAnyLengthPassesValidation(final int length) {
            final AccountUpdateResponse response = aSparseResponse(null, null, "X".repeat(length),
                    null, null, null, false, null, null, null, null);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(response)).isEmpty();
            }
        }

        @ParameterizedTest(name = "a {0}-character value")
        @ValueSource(ints = {1, 50, 51, 200})
        @DisplayName("a second address line of any length passes validation, including one longer than "
                + "the first address line accepts")
        void aSecondAddressLineOfAnyLengthPassesValidation(final int length) {
            final AccountUpdateResponse response = aSparseResponse(null, null, null,
                    "X".repeat(length), null, null, false, null, null, null, null);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(response)).isEmpty();
            }
        }

        @Test
        @DisplayName("a first address line one character over its width is still reported, so the "
                + "exception really is confined to the second line")
        void aFirstAddressLineOverItsWidthIsStillReported() throws NoSuchMethodException {
            final int bound = declaredMaximumLength("addressLine1");
            final AccountUpdateResponse overBound = new AccountUpdateResponse(
                    null, null, null, null, null,
                    null, null, null, null, null,
                    null, null, null, null, null,
                    null, null, null, null, null,
                    null, null, null, null, null,
                    null, null, null, null, null,
                    null, null, null, null, "X".repeat(bound + 1),
                    null, "X".repeat(bound + 1), null, null, null,
                    null, null, null, null, null,
                    null, null, null, null, null,
                    null, false, null, null, null,
                    null, null);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(overBound))
                        .extracting(violation -> violation.getPropertyPath().toString())
                        .containsExactly("addressLine1");
            }
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the twelve validation-message suffixes")
    class TheTwelveValidationMessageSuffixes {

        @Test
        @DisplayName("the credit-score suffix names the legacy range of three hundred to eight hundred "
                + "and fifty")
        void theCreditScoreSuffixNamesTheLegacyRange() {
            assertThat(AccountUpdateResponse.SUFFIX_FICO_OUT_OF_RANGE)
                    .isEqualTo(": should be between 300 and 850");
        }

        @Test
        @DisplayName("the state suffix carries the legacy text")
        void theStateSuffixCarriesTheLegacyText() {
            assertThat(AccountUpdateResponse.SUFFIX_STATE_NOT_VALID)
                    .isEqualTo(": is not a valid state code");
        }

        @Test
        @DisplayName("the four area-code suffixes carry the legacy text, capital A included")
        void theFourAreaCodeSuffixesCarryTheLegacyText() {
            assertThat(AccountUpdateResponse.SUFFIX_AREA_CODE_REQUIRED)
                    .isEqualTo(": Area code must be supplied.");
            assertThat(AccountUpdateResponse.SUFFIX_AREA_CODE_NOT_3_DIGITS)
                    .isEqualTo(": Area code must be A 3 digit number.");
            assertThat(AccountUpdateResponse.SUFFIX_AREA_CODE_ZERO)
                    .isEqualTo(": Area code cannot be zero");
            assertThat(AccountUpdateResponse.SUFFIX_AREA_CODE_NOT_GENERAL_PURPOSE)
                    .isEqualTo(": Not valid North America general purpose area code");
        }

        @Test
        @DisplayName("the three prefix suffixes carry the legacy text, capital A included")
        void theThreePrefixSuffixesCarryTheLegacyText() {
            assertThat(AccountUpdateResponse.SUFFIX_PREFIX_REQUIRED)
                    .isEqualTo(": Prefix code must be supplied.");
            assertThat(AccountUpdateResponse.SUFFIX_PREFIX_NOT_3_DIGITS)
                    .isEqualTo(": Prefix code must be A 3 digit number.");
            assertThat(AccountUpdateResponse.SUFFIX_PREFIX_ZERO)
                    .isEqualTo(": Prefix code cannot be zero");
        }

        @Test
        @DisplayName("the three line-number suffixes carry the legacy text, capital A included")
        void theThreeLineNumberSuffixesCarryTheLegacyText() {
            assertThat(AccountUpdateResponse.SUFFIX_LINE_NUMBER_REQUIRED)
                    .isEqualTo(": Line number code must be supplied.");
            assertThat(AccountUpdateResponse.SUFFIX_LINE_NUMBER_NOT_4_DIGITS)
                    .isEqualTo(": Line number code must be A 4 digit number.");
            assertThat(AccountUpdateResponse.SUFFIX_LINE_NUMBER_ZERO)
                    .isEqualTo(": Line number code cannot be zero");
        }

        @Test
        @DisplayName("the ungrammatical capital A is preserved in all three not-a-number suffixes, "
                + "because it is what the operator sees")
        void theUngrammaticalCapitalAIsPreserved() {
            assertThat(AccountUpdateResponse.SUFFIX_AREA_CODE_NOT_3_DIGITS).contains(" A 3 digit ");
            assertThat(AccountUpdateResponse.SUFFIX_PREFIX_NOT_3_DIGITS).contains(" A 3 digit ");
            assertThat(AccountUpdateResponse.SUFFIX_LINE_NUMBER_NOT_4_DIGITS)
                    .contains(" A 4 digit ");
        }

        @Test
        @DisplayName("every suffix opens with a colon and a space, because each is appended to a field "
                + "name to make a whole message")
        void everySuffixOpensWithAColonAndASpace() {
            assertThat(everySuffix()).allSatisfy(suffix -> assertThat(suffix).startsWith(": "));
        }

        @Test
        @DisplayName("the required and not-a-number suffixes end in a full stop and the cannot-be-zero "
                + "suffixes do not, which is the legacy's own inconsistency")
        void theSuffixesDisagreeAboutTheirFinalFullStop() {
            assertThat(AccountUpdateResponse.SUFFIX_AREA_CODE_REQUIRED).endsWith(".");
            assertThat(AccountUpdateResponse.SUFFIX_PREFIX_REQUIRED).endsWith(".");
            assertThat(AccountUpdateResponse.SUFFIX_LINE_NUMBER_REQUIRED).endsWith(".");
            assertThat(AccountUpdateResponse.SUFFIX_AREA_CODE_NOT_3_DIGITS).endsWith(".");
            assertThat(AccountUpdateResponse.SUFFIX_AREA_CODE_ZERO).doesNotEndWith(".");
            assertThat(AccountUpdateResponse.SUFFIX_PREFIX_ZERO).doesNotEndWith(".");
            assertThat(AccountUpdateResponse.SUFFIX_LINE_NUMBER_ZERO).doesNotEndWith(".");
        }

        @Test
        @DisplayName("the twelve suffixes are all distinct, so no two field failures read alike")
        void theTwelveSuffixesAreAllDistinct() {
            assertThat(everySuffix()).hasSize(12).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("a field name and its suffix together fit the error field, so a composed message "
                + "cannot overflow the screen field that renders it")
        void aComposedMessageFitsTheErrorField() throws NoSuchMethodException {
            final int errorFieldWidth = declaredMaximumLength("errorMessage");

            assertThat(everySuffix()).allSatisfy(suffix ->
                    assertThat(("Phone Number 1 Area Code" + suffix).length())
                            .as("composed from suffix [%s]", suffix)
                            .isLessThanOrEqualTo(errorFieldWidth));
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the fifteen whole operator messages")
    class TheFifteenWholeOperatorMessages {

        @Test
        @DisplayName("the account and status messages carry the legacy text")
        void theAccountAndStatusMessagesCarryTheLegacyText() {
            assertThat(AccountUpdateResponse.MSG_ACCOUNT_NUMBER_NOT_USABLE)
                    .isEqualTo("Account number must be a non zero 11 digit number");
            assertThat(AccountUpdateResponse.MSG_ACCOUNT_STATUS_MUST_BE_YES_NO)
                    .isEqualTo("Account Active Status must be Y or N");
        }

        @Test
        @DisplayName("the credit-limit and expiry messages carry the legacy text")
        void theCreditLimitAndExpiryMessagesCarryTheLegacyText() {
            assertThat(AccountUpdateResponse.MSG_CREDIT_LIMIT_REQUIRED)
                    .isEqualTo("Credit Limit must be supplied");
            assertThat(AccountUpdateResponse.MSG_CREDIT_LIMIT_NOT_VALID)
                    .isEqualTo("Credit Limit is not valid");
            assertThat(AccountUpdateResponse.MSG_EXPIRY_MONTH_NOT_VALID)
                    .isEqualTo("Card expiry month must be between 1 and 12");
            assertThat(AccountUpdateResponse.MSG_EXPIRY_YEAR_NOT_VALID)
                    .isEqualTo("Invalid card expiry year");
        }

        @Test
        @DisplayName("the zip and lookup messages carry the legacy text")
        void theZipAndLookupMessagesCarryTheLegacyText() {
            assertThat(AccountUpdateResponse.MSG_INVALID_ZIP_FOR_STATE)
                    .isEqualTo("Invalid zip code for state");
            assertThat(AccountUpdateResponse.MSG_ACCOUNT_NOT_IN_CARD_DATABASE)
                    .isEqualTo("Did not find this account in cards database");
            assertThat(AccountUpdateResponse.MSG_NO_CARDS_FOR_SEARCH_CONDITION)
                    .isEqualTo("Did not find cards for this search condition");
            assertThat(AccountUpdateResponse.MSG_CARD_DATA_READ_ERROR)
                    .isEqualTo("Error reading Card Data File");
        }

        @Test
        @DisplayName("the three concurrency messages carry the legacy text, and the changed-record one "
                + "keeps its two-word spelling of someone")
        void theThreeConcurrencyMessagesCarryTheLegacyText() {
            assertThat(AccountUpdateResponse.MSG_COULD_NOT_HOLD_ACCOUNT_FOR_UPDATE)
                    .isEqualTo("Could not lock account record for update");
            assertThat(AccountUpdateResponse.MSG_COULD_NOT_HOLD_CUSTOMER_FOR_UPDATE)
                    .isEqualTo("Could not lock customer record for update");
            assertThat(AccountUpdateResponse.MSG_RECORD_CHANGED_BEFORE_UPDATE)
                    .isEqualTo("Record changed by some one else. Please review")
                    .contains("some one");
            assertThat(AccountUpdateResponse.MSG_UPDATE_OF_RECORD_FAILED)
                    .isEqualTo("Update of record failed");
        }

        @Test
        @DisplayName("the progress message matches the card-detail screen's own word for word, four full "
                + "stops included")
        void theProgressMessageMatchesTheCardDetailScreen() {
            assertThat(AccountUpdateResponse.MSG_LOOKS_GOOD_SO_FAR)
                    .isEqualTo("Looks Good.... so far")
                    .isEqualTo(CardDetailResponse.MSG_CODING_TO_BE_DONE);
        }

        @Test
        @DisplayName("the two lookup-failure messages match the card-detail screen's own, because the two "
                + "screens read the same file and report the same way")
        void theTwoLookupFailureMessagesMatchTheCardDetailScreen() {
            assertThat(AccountUpdateResponse.MSG_ACCOUNT_NOT_IN_CARD_DATABASE)
                    .isEqualTo(CardDetailResponse.MSG_ACCOUNT_NOT_IN_CARD_CROSS_REFERENCE);
            assertThat(AccountUpdateResponse.MSG_NO_CARDS_FOR_SEARCH_CONDITION)
                    .isEqualTo(CardDetailResponse.MSG_NO_CARDS_FOR_SEARCH_CONDITION);
            assertThat(AccountUpdateResponse.MSG_CARD_DATA_READ_ERROR)
                    .isEqualTo(CardDetailResponse.MSG_CARD_DATA_READ_ERROR);
        }

        @Test
        @DisplayName("the fifteen messages are all distinct, so no two situations read alike")
        void theFifteenMessagesAreAllDistinct() {
            assertThat(everyWholeMessage()).hasSize(15).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("every whole message fits the error field, so none can overflow the field that "
                + "renders it")
        void everyWholeMessageFitsTheErrorField() throws NoSuchMethodException {
            final int errorFieldWidth = declaredMaximumLength("errorMessage");

            assertThat(everyWholeMessage()).allSatisfy(message ->
                    assertThat(message.length()).isLessThanOrEqualTo(errorFieldWidth));
        }

        @Test
        @DisplayName("the progress message fits the narrower information field too, because it is the one "
                + "message this screen shows on success")
        void theProgressMessageFitsTheInformationField() throws NoSuchMethodException {
            assertThat(AccountUpdateResponse.MSG_LOOKS_GOOD_SO_FAR.length())
                    .isLessThanOrEqualTo(declaredMaximumLength("infoMessage"));
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the field-error list and its reading")
    class TheFieldErrorListAndItsReading {

        @Test
        @DisplayName("an absent list becomes an empty one, so a client never has to distinguish absent "
                + "from empty before counting errors")
        void anAbsentListBecomesAnEmptyOne() {
            final AccountUpdateResponse response = aFullyPopulatedResponse(null);

            assertThat(response.fieldErrors()).isNotNull().isEmpty();
            assertThat(response.hasFieldErrors()).isFalse();
        }

        @Test
        @DisplayName("a populated list is reported as present, and the reading agrees with the list read "
                + "independently")
        void aPopulatedListIsReportedAsPresent() {
            final AccountUpdateResponse response = aFullyPopulatedResponse(List.of(
                    new ErrorResponse.FieldError("accountStatus", "ACSTTUS",
                            ErrorResponse.FieldState.INVALID,
                            AccountUpdateResponse.MSG_ACCOUNT_STATUS_MUST_BE_YES_NO)));

            assertThat(response.hasFieldErrors()).isTrue();
            assertThat(response.fieldErrors()).hasSize(1);
            assertThat(response.hasFieldErrors()).isEqualTo(!response.fieldErrors().isEmpty());
        }

        @Test
        @DisplayName("a supplied list is copied, so a later change to the caller's list cannot reach "
                + "inside the response")
        void aSuppliedListIsCopied() {
            final List<ErrorResponse.FieldError> mutable = new ArrayList<>();
            mutable.add(new ErrorResponse.FieldError("ficoScore", "ACSTFCO",
                    ErrorResponse.FieldState.INVALID,
                    "FICO Score" + AccountUpdateResponse.SUFFIX_FICO_OUT_OF_RANGE));

            final AccountUpdateResponse response = aFullyPopulatedResponse(mutable);
            mutable.clear();

            assertThat(response.fieldErrors()).hasSize(1);
            assertThat(response.hasFieldErrors()).isTrue();
        }

        @Test
        @DisplayName("the copy is unmodifiable, so nothing downstream can add an error to a published "
                + "response")
        void theCopyIsUnmodifiable() {
            final AccountUpdateResponse response = aFullyPopulatedResponse(List.of(
                    new ErrorResponse.FieldError("stateCode", "ACSTTE",
                            ErrorResponse.FieldState.INVALID, null)));

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> response.fieldErrors().clear());
        }

        @Test
        @DisplayName("a list containing an absent error is rejected, because a null error is one a client "
                + "could not render")
        void aListContainingAnAbsentErrorIsRejected() {
            final List<ErrorResponse.FieldError> withNull = new ArrayList<>();
            withNull.add(null);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> aFullyPopulatedResponse(withNull));
        }

        @Test
        @DisplayName("the list preserves the order it was given, because the legacy cascade reports "
                + "fields in screen order and the cursor lands on the first")
        void theListPreservesTheOrderItWasGiven() {
            final ErrorResponse.FieldError first = new ErrorResponse.FieldError("accountStatus",
                    "ACSTTUS", ErrorResponse.FieldState.MISSING, null);
            final ErrorResponse.FieldError second = new ErrorResponse.FieldError("ficoScore",
                    "ACSTFCO", ErrorResponse.FieldState.INVALID, null);

            assertThat(aFullyPopulatedResponse(List.of(first, second)).fieldErrors())
                    .containsExactly(first, second);
        }

        @Test
        @DisplayName("the two field states are carried distinctly, because a blank field and an invalid "
                + "one are decorated differently by the legacy macro")
        void theTwoFieldStatesAreCarriedDistinctly() {
            final AccountUpdateResponse response = aFullyPopulatedResponse(List.of(
                    new ErrorResponse.FieldError("accountStatus", "ACSTTUS",
                            ErrorResponse.FieldState.MISSING, null),
                    new ErrorResponse.FieldError("ficoScore", "ACSTFCO",
                            ErrorResponse.FieldState.INVALID, null)));

            assertThat(response.fieldErrors())
                    .extracting(ErrorResponse.FieldError::state)
                    .containsExactly(ErrorResponse.FieldState.MISSING,
                            ErrorResponse.FieldState.INVALID);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the withholding rendering")
    class TheWithholdingRendering {

        @Test
        @DisplayName("no populated value reaches the rendering, so the response can be logged whole")
        void noPopulatedValueReachesTheRendering() {
            final String rendered = aFullyPopulatedResponse(null).toString();

            assertThat(rendered).doesNotContain("00000000011");
            assertThat(rendered).doesNotContain("000000011");
            assertThat(rendered).doesNotContain("123");
            assertThat(rendered).doesNotContain("6789");
            assertThat(rendered).doesNotContain("720");
            assertThat(rendered).doesNotContain("PUBLIC");
            assertThat(rendered).doesNotContain("1 MAIN ST");
            assertThat(rendered).doesNotContain("DL-987654321");
            assertThat(rendered).doesNotContain("5000.00");
            assertThat(rendered).doesNotContain("1980");
        }

        @Test
        @DisplayName("the rendering publishes the failure shape, so a diagnostic still says what went "
                + "wrong and where the cursor went")
        void theRenderingPublishesTheFailureShape() {
            final String rendered = aSparseResponse(null, null, null, null, null,
                    AccountUpdateResponse.MSG_UPDATE_OF_RECORD_FAILED, true, "ACSTTUS",
                    "account-update", null, List.of(new ErrorResponse.FieldError("accountStatus",
                            "ACSTTUS", ErrorResponse.FieldState.INVALID, null))).toString();

            assertThat(rendered).contains("error=true");
            assertThat(rendered).contains(
                    "errorMessage=" + AccountUpdateResponse.MSG_UPDATE_OF_RECORD_FAILED);
            assertThat(rendered).contains("focusScreenFieldId=ACSTTUS");
            assertThat(rendered).contains("fieldErrorCount=1");
            assertThat(rendered).contains("nextRoute=account-update");
        }

        @Test
        @DisplayName("the rendering reports a count rather than the errors themselves, because a field "
                + "error carries a field name and a message and not a value")
        void theRenderingReportsACountRatherThanTheErrors() {
            final String rendered = aSparseResponse(null, null, null, null, null, null, true, null,
                    null, null, List.of(
                            new ErrorResponse.FieldError("a", "AAAAAAA",
                                    ErrorResponse.FieldState.MISSING, null),
                            new ErrorResponse.FieldError("b", "BBBBBBB",
                                    ErrorResponse.FieldState.INVALID, null))).toString();

            assertThat(rendered).contains("fieldErrorCount=2");
            assertThat(rendered).doesNotContain("AAAAAAA");
            assertThat(rendered).doesNotContain("BBBBBBB");
        }

        @Test
        @DisplayName("the rendering substitutes the placeholder exactly once, standing in for every "
                + "value the response carries")
        void theRenderingSubstitutesThePlaceholderExactlyOnce() {
            // The navigation context redacts components of its own, so it is left absent here in order
            // that the count measures this record's own redaction rather than the sum of two records'.
            final String rendered = aSparseResponse("00000000011", "720", "QUINCY", "APT 2", null,
                    null, false, null, null, null, null).toString();

            assertThat(rendered).contains("values=***REDACTED***");
            assertThat(rendered.split("\\*\\*\\*REDACTED\\*\\*\\*", -1)).hasSize(2);
        }

        @Test
        @DisplayName("the rendering opens with the type name and closes with a bracket, matching the "
                + "record rendering it replaces")
        void theRenderingKeepsTheRecordShape() {
            assertThat(aFullyPopulatedResponse(null).toString())
                    .startsWith("AccountUpdateResponse[")
                    .endsWith("]");
        }

        @Test
        @DisplayName("a response carrying nothing at all still renders a zero count rather than failing, "
                + "because the constructor has already replaced the absent list")
        void aResponseCarryingNothingStillRendersAZeroCount() {
            assertThat(aSparseResponse(null, null, null, null, null, null, false, null, null, null,
                    null).toString()).contains("fieldErrorCount=0");
        }

        @Test
        @DisplayName("the redaction placeholder is declared private, because a caller has no reason to "
                + "read it and every reason not to compare against it")
        void theRedactionPlaceholderIsDeclaredPrivate() {
            assertThat(Arrays.stream(AccountUpdateResponse.class.getDeclaredFields())
                    .filter(field -> "REDACTION_PLACEHOLDER".equals(field.getName())).toList())
                    .singleElement()
                    .satisfies(field -> assertThat(Modifier.isPrivate(field.getModifiers()))
                            .isTrue());
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the declared shape and validation bounds")
    class TheDeclaredShapeAndValidationBounds {

        /**
         * Fifty-six components are screen fields in screen order; the fifty-seventh is not.
         *
         * <p>The concurrency token is the sealed counterpart of the program work area
         * {@code app/cbl/COACTUPC.cbl} carries across the pseudo-conversational turn, and it replaces the
         * before-and-after image comparison the legacy program performs before it writes. It is declared
         * last, after the whole screen and after the error block, precisely because it is not part of
         * either: nothing on the mapset renders it and no operator types it.
         *
         * <p>Its position is asserted rather than assumed. Inserting it anywhere among the screen fields
         * would silently reorder a fifty-seven-argument positional constructor whose arguments are nearly
         * all the same nullable type, which is the one drift the compiler cannot catch.
         */
        @Test
        @DisplayName("the response declares fifty-seven components - fifty-six screen fields in screen "
                + "order, then the concurrency token, which is not a screen field")
        void theResponseDeclaresFiftySevenComponents() {
            final List<String> declared =
                    Arrays.stream(AccountUpdateResponse.class.getRecordComponents())
                            .map(RecordComponent::getName).toList();

            assertThat(declared).hasSize(DECLARED_COMPONENT_COUNT);
            assertThat(declared.subList(0, 12)).containsExactly("transactionName", "title01",
                    "currentDate", "programName", "title02", "currentTime", "accountId",
                    "accountStatus", "openYear", "openMonth", "openDay", "creditLimit");
            assertThat(declared.subList(50, SCREEN_FIELD_COMPONENT_COUNT)).containsExactly(
                    "errorMessage", "error", "focusScreenFieldId", "nextRoute", "navigationContext",
                    "fieldErrors");
            assertThat(declared).last().isEqualTo("concurrencyToken");
        }

        /**
         * The token is carried out and back unchanged, and it declares no width.
         *
         * <p>It is opaque and unbounded by design: a bound would be a statement about the service's
         * minting format, which no screen field constrains and which this contract has no business
         * asserting. Its absence is likewise not a binding failure - a missing token is a conflict for
         * the service to report on the confirming turn, not something the framework should reject at the
         * edge.
         */
        @Test
        @DisplayName("the concurrency token round-trips unchanged, declares no width, and may be absent")
        void theConcurrencyTokenRoundTripsAndDeclaresNoWidth() throws NoSuchMethodException {
            assertThat(aFullyPopulatedResponse(null).concurrencyToken())
                    .isEqualTo(CONCURRENCY_TOKEN);
            assertThat(AccountUpdateResponse.class.getDeclaredMethod("concurrencyToken")
                    .getAnnotation(Size.class))
                    .as("an opaque server-minted value carries no screen width")
                    .isNull();

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(aSparseResponse(null, null, null, null,
                        null, null, false, null, null, null, null)))
                        .as("an absent token is not a binding failure")
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("the three date triples are separate components, so a partly filled date reaches "
                + "the legacy cascade intact")
        void theThreeDateTriplesAreSeparateComponents() {
            assertThat(Arrays.stream(AccountUpdateResponse.class.getRecordComponents())
                    .map(RecordComponent::getName).toList())
                    .contains("openYear", "openMonth", "openDay", "expiryYear", "expiryMonth",
                            "expiryDay", "reissueYear", "reissueMonth", "reissueDay",
                            "dateOfBirthYear", "dateOfBirthMonth", "dateOfBirthDay");
        }

        @Test
        @DisplayName("the two telephone numbers and the social security number arrive in pieces, so the "
                + "cascade can report against the piece that failed")
        void theTelephoneAndSocialSecurityValuesArriveInPieces() {
            assertThat(Arrays.stream(AccountUpdateResponse.class.getRecordComponents())
                    .map(RecordComponent::getName).toList())
                    .contains("phone1AreaCode", "phone1Prefix", "phone1LineNumber", "phone2AreaCode",
                            "phone2Prefix", "phone2LineNumber", "ssnPart1", "ssnPart2", "ssnPart3");
        }

        @Test
        @DisplayName("exactly five components are monetary and every one is a BigDecimal rather than a "
                + "binary floating-point type")
        void exactlyFiveComponentsAreMonetary() {
            assertThat(Arrays.stream(AccountUpdateResponse.class.getRecordComponents())
                    .filter(component -> component.getType() == BigDecimal.class)
                    .map(RecordComponent::getName).toList())
                    .containsExactly("creditLimit", "cashCreditLimit", "currentBalance",
                            "currentCycleCredit", "currentCycleDebit");
            assertThat(Arrays.stream(AccountUpdateResponse.class.getRecordComponents())
                    .map(RecordComponent::getType))
                    .doesNotContain(double.class, float.class, Double.class, Float.class);
        }

        @Test
        @DisplayName("the only primitive component is the general-error flag")
        void theOnlyPrimitiveComponentIsTheGeneralErrorFlag() {
            assertThat(Arrays.stream(AccountUpdateResponse.class.getRecordComponents())
                    .filter(component -> component.getType().isPrimitive())
                    .map(RecordComponent::getName).toList())
                    .containsExactly("error");
        }

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
            "openYear, 4",
            "openMonth, 2",
            "openDay, 2",
            "expiryYear, 4",
            "expiryMonth, 2",
            "expiryDay, 2",
            "reissueYear, 4",
            "reissueMonth, 2",
            "reissueDay, 2",
            "accountGroupId, 10",
            "customerId, 9",
            "ssnPart1, 3",
            "ssnPart2, 2",
            "ssnPart3, 4",
            "dateOfBirthYear, 4",
            "dateOfBirthMonth, 2",
            "dateOfBirthDay, 2",
            "ficoScore, 3",
            "firstName, 25",
            "lastName, 25",
            "addressLine1, 50",
            "stateCode, 2",
            "zipCode, 5",
            "city, 50",
            "countryCode, 3",
            "phone1AreaCode, 3",
            "phone1Prefix, 3",
            "phone1LineNumber, 4",
            "governmentIssuedId, 20",
            "phone2AreaCode, 3",
            "phone2Prefix, 3",
            "phone2LineNumber, 4",
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
        @DisplayName("the two telephone triples are bounded identically, so the second number cannot "
                + "accept a value the first rejects")
        void theTwoTelephoneTriplesAreBoundedIdentically() throws NoSuchMethodException {
            assertThat(declaredMaximumLength("phone1AreaCode"))
                    .isEqualTo(declaredMaximumLength("phone2AreaCode"));
            assertThat(declaredMaximumLength("phone1Prefix"))
                    .isEqualTo(declaredMaximumLength("phone2Prefix"));
            assertThat(declaredMaximumLength("phone1LineNumber"))
                    .isEqualTo(declaredMaximumLength("phone2LineNumber"));
        }

        @Test
        @DisplayName("the three social security pieces sum to the nine digits of the legacy field")
        void theThreeSocialSecurityPiecesSumToNineDigits() throws NoSuchMethodException {
            assertThat(declaredMaximumLength("ssnPart1") + declaredMaximumLength("ssnPart2")
                    + declaredMaximumLength("ssnPart3")).isEqualTo(9);
        }

        /**
         * The focus field is bounded by what it holds, and what it holds is the same on both screens.
         *
         * <p>The component does not carry a value an operator typed - it carries the <em>name</em> of the
         * map item the cursor is to be placed on, so its bound is the width of a generated map item name
         * and not the width of any field on the screen. That width is a property of the map generator
         * rather than of either screen, so the account update and account view screens necessarily agree
         * on it, and the two are asserted together here so that a change to one without the other is
         * caught.</p>
         *
         * <p>The bound is deliberately not stated as a literal in the second assertion: it is read from
         * the view screen's own declaration, so this test says the two agree rather than saying what they
         * agree on, which is the claim worth making.</p>
         */
        @Test
        @DisplayName("the focus field carries a map item name rather than screen content, so its bound is "
                + "the same here as on the account view screen")
        void theFocusFieldIsBoundedTheSameAsOnTheViewScreen() throws NoSuchMethodException {
            final int viewScreenBound = AccountViewResponse.class
                    .getDeclaredMethod("focusScreenFieldId").getAnnotation(Size.class).max();

            assertThat(declaredMaximumLength("focusScreenFieldId"))
                    .isEqualTo(7)
                    .as("both screens name items generated by the same map generator")
                    .isEqualTo(viewScreenBound);
        }

        @Test
        @DisplayName("a fully populated response passes validation")
        void aFullyPopulatedResponsePassesValidation() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(aFullyPopulatedResponse(null))).isEmpty();
            }
        }

        @Test
        @DisplayName("a response with nothing supplied passes validation and still publishes an empty "
                + "error list")
        void anEmptyResponsePassesValidationAndPublishesAnEmptyList() {
            final AccountUpdateResponse empty = aSparseResponse(null, null, null, null, null, null,
                    false, null, null, null, null);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(empty)).isEmpty();
            }
            assertThat(empty.fieldErrors()).isEmpty();
        }

        @Test
        @DisplayName("several components over their bounds are all reported, so a client sees every "
                + "offending field rather than the first")
        void severalComponentsOverTheirBoundsAreAllReported() {
            final AccountUpdateResponse overBound = aSparseResponse("0".repeat(12), "7200", null,
                    null, "X".repeat(46), null, false, null, null, null, null);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(overBound))
                        .extracting(violation -> violation.getPropertyPath().toString())
                        .containsExactlyInAnyOrder("accountId", "ficoScore", "infoMessage");
            }
        }

        @Test
        @DisplayName("a field error over the response's own bounds is not reported by validating the "
                + "response, because the error list carries no cascade marker")
        void aFieldErrorIsNotCascadedInto() {
            final AccountUpdateResponse response = aFullyPopulatedResponse(List.of(
                    new ErrorResponse.FieldError("X".repeat(200), "Y".repeat(200),
                            ErrorResponse.FieldState.INVALID, "Z".repeat(200))));

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(response)).isEmpty();
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
            assertThat(aFullyPopulatedResponse(null)).isEqualTo(aFullyPopulatedResponse(null))
                    .hasSameHashCodeAs(aFullyPopulatedResponse(null));
        }

        @Test
        @DisplayName("a response built with an absent error list equals one built with an empty list, "
                + "because the constructor has already made them the same")
        void anAbsentListEqualsAnEmptyList() {
            assertThat(aFullyPopulatedResponse(null))
                    .isEqualTo(aFullyPopulatedResponse(List.of()));
        }

        @Test
        @DisplayName("a difference in one component makes two responses unequal, even a component the "
                + "rendering withholds")
        void aDifferenceInAWithheldComponentMakesTwoResponsesUnequal() {
            final AccountUpdateResponse first = aSparseResponse("00000000011", null, null, null,
                    null, null, false, null, null, null, null);
            final AccountUpdateResponse second = aSparseResponse("00000000012", null, null, null,
                    null, null, false, null, null, null, null);

            assertThat(first).isNotEqualTo(second);
            assertThat(first).hasToString(second.toString());
        }

        @Test
        @DisplayName("an absent component is omitted from the payload, and the error list and flag are "
                + "always present because neither is ever absent")
        void anAbsentComponentIsOmittedAndTheListAndFlagAreAlwaysPresent()
                throws JsonProcessingException {

            final JsonNode payload = payloadOf(aFullyPopulatedResponse(null));

            assertThat(payload.has("errorMessage")).isFalse();
            assertThat(payload.get("fieldErrors").isArray()).isTrue();
            assertThat(payload.get("fieldErrors")).isEmpty();
            assertThat(payload.get("error").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("the derived reading is not published, because it is a convenience for a server-side "
                + "caller rather than part of the wire contract")
        void theDerivedReadingIsNotPublished() throws JsonProcessingException {
            final JsonNode payload = payloadOf(aFullyPopulatedResponse(List.of(
                    new ErrorResponse.FieldError("accountStatus", "ACSTTUS",
                            ErrorResponse.FieldState.MISSING, null))));

            assertThat(payload.has("hasFieldErrors")).isFalse();
            assertThat(payload.get("fieldErrors")).hasSize(1);
        }

        @Test
        @DisplayName("a monetary amount renders in plain notation with its scale intact, so a cent "
                + "boundary survives the wire")
        void aMonetaryAmountRendersInPlainNotationWithItsScale() throws JsonProcessingException {
            final String wire = moduleEquivalentMapper()
                    .writeValueAsString(aFullyPopulatedResponse(null));

            assertThat(wire).contains("\"creditLimit\":5000.00");
            assertThat(wire).contains("\"currentBalance\":1234.56");
            assertThat(wire).doesNotContain("E3").doesNotContain("E9");
        }

        @Test
        @DisplayName("each published field error renders its own components, and an absent message is "
                + "omitted from the error rather than rendered as null")
        void eachPublishedFieldErrorRendersItsOwnComponents() throws JsonProcessingException {
            final JsonNode firstError = payloadOf(aFullyPopulatedResponse(List.of(
                    new ErrorResponse.FieldError("ficoScore", "ACSTFCO",
                            ErrorResponse.FieldState.INVALID))))
                    .get("fieldErrors").get(0);

            assertThat(firstError.get("fieldName").asText()).isEqualTo("ficoScore");
            assertThat(firstError.get("screenFieldId").asText()).isEqualTo("ACSTFCO");
            assertThat(firstError.get("state").asText()).isEqualTo("INVALID");
            assertThat(firstError.has("message")).isFalse();
        }

        @Test
        @DisplayName("a response survives a round trip through the module-equivalent mapper unchanged, "
                + "monetary scale and error order included")
        void aResponseSurvivesARoundTrip() throws JsonProcessingException {
            final ObjectMapper mapper = moduleEquivalentMapper();
            final AccountUpdateResponse original = aFullyPopulatedResponse(List.of(
                    new ErrorResponse.FieldError("accountStatus", "ACSTTUS",
                            ErrorResponse.FieldState.MISSING, null),
                    new ErrorResponse.FieldError("ficoScore", "ACSTFCO",
                            ErrorResponse.FieldState.INVALID,
                            "FICO" + AccountUpdateResponse.SUFFIX_FICO_OUT_OF_RANGE)));

            final AccountUpdateResponse restored = mapper.readValue(
                    mapper.writeValueAsString(original), AccountUpdateResponse.class);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.creditLimit().scale()).isEqualTo(2);
            assertThat(restored.fieldErrors()).hasSize(2);
            assertThat(restored.fieldErrors().get(0).state())
                    .isEqualTo(ErrorResponse.FieldState.MISSING);
            assertThat(restored.hasFieldErrors()).isTrue();
        }

        @Test
        @DisplayName("a restored response's error list is unmodifiable too, so the defensive copy "
                + "survives deserialisation rather than being bypassed by it")
        void aRestoredResponsesErrorListIsUnmodifiable() throws JsonProcessingException {
            final ObjectMapper mapper = moduleEquivalentMapper();
            final AccountUpdateResponse restored = mapper.readValue(
                    mapper.writeValueAsString(aFullyPopulatedResponse(List.of(
                            new ErrorResponse.FieldError("stateCode", "ACSTTE",
                                    ErrorResponse.FieldState.INVALID, null)))),
                    AccountUpdateResponse.class);

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> restored.fieldErrors().clear());
        }
    }
}
