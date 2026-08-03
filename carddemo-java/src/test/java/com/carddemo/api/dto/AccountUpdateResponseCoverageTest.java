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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Contract tests for {@link AccountUpdateResponse}.
 *
 * <h2>What is under test</h2>
 *
 * <p>The declared shape of the response to the largest transaction in the estate: its fifty-seven
 * components and their order, the forty-five widths it measures and the twelve components it leaves
 * unmeasured, the twenty-seven published message fragments with their four deliberate quirks, the
 * one collection and five amounts its compact constructor normalises or verifies, and a diagnostic
 * rendering that is a whitelist rather than a set of substitutions.
 *
 * <h2>Fifty-one map families plus five control components, and the arithmetic is checkable</h2>
 *
 * <p>The symbolic map declares fifty-four field families. Forty-three are operator-editable and come
 * back as resulting values; six are screen metadata and two are message slots, all eight protected
 * on the mapset; and three are function-key legends that are deliberately not carried. That gives
 * fifty-one carried families, to which five control components are added: the explicit error
 * indicator, the focus hint, the declarative route, the echoed conversation state and the per-field
 * error collection. Fifty-six in all, and nothing after them - in particular no optimistic-lock,
 * version or entity-tag value, because concurrency is a persistence concern carried by the entity's
 * own version attribute and a detected conflict reaches a client as a summary text plus field
 * errors. The tests below assert that arithmetic component by component rather than trusting the
 * prose.
 *
 * <h2>Two components must carry no constraint at all, not even a maximum length</h2>
 *
 * <p>The middle name and the second address line are decorated by the legacy macro but never
 * validated by it, as two source comments state directly. Both are therefore declared with no
 * constraint annotation of any kind, and the request contract does the same. Attaching a bound to
 * either would reject input the legacy accepts, so its absence is asserted here as a headline fact
 * and cross-checked against the request contract, which the type's documentation requires to agree.
 *
 * <h2>The rendering is a whitelist, so almost nothing is even named</h2>
 *
 * <p>Every other redacting type in this package substitutes a placeholder per withheld component,
 * which leaves the component names visible. This one does not: it renders five control values, a
 * count in place of the field-error entries, and one collective placeholder standing for all
 * fifty remaining components. Fifty component names therefore never appear at all, and the
 * diagnostic's size cannot grow with the number of mistakes an operator made. Both properties are
 * asserted.
 *
 * <h2>One summary message, any number of independent field errors</h2>
 *
 * <p>A first-error-wins gate means a submission with five bad fields yields one summary text and
 * five independently flagged fields. The explicit error indicator is a third, separate fact and is
 * never derived from either. All three combinations that this independence makes legal are exercised
 * as explicit shapes.
 *
 * <h2>How the wire form is observed, and what that does and does not prove</h2>
 *
 * <p>Payloads come from {@link JsonContractSupport#declaredSettingsMapper()}, a mapper carrying the
 * four serialisation settings this module declares, written out by hand in one place rather than
 * copied into every suite. That evidences the shape this type takes <em>under those settings</em>,
 * and nothing more. It is not evidence about the mapper a deployed instance holds, and no assertion
 * below is worded as though it were; {@code ApplicationJsonContractTest} is the in-boundary evidence
 * for the deployed object.
 *
 * <h2>Provenance</h2>
 *
 * <p>Legacy antecedents cited by the type under test: program {@code app/cbl/COACTUPC.cbl},
 * symbolic map {@code app/cpy-bms/COACTUP.CPY}, mapset {@code app/bms/COACTUP.bms}, record layouts
 * {@code app/cpy/CVACT01Y.cpy} and {@code app/cpy/CVCUS01Y.cpy}, and decoration macro
 * {@code app/cpy/CSSETATY.cpy}. Checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec};
 * upstream release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source
 * text is reproduced here.
 */
@DisplayName("AccountUpdateResponse :: response contract of legacy transaction CAUP")
class AccountUpdateResponseCoverageTest {

    /** The fifty-seven components, in the order the record declares them. */
    private static final List<String> EXPECTED_COMPONENTS = List.of(
            "transactionName",
            "title01",
            "currentDate",
            "programName",
            "title02",
            "currentTime",
            "accountId",
            "accountStatus",
            "openYear",
            "openMonth",
            "openDay",
            "creditLimit",
            "expiryYear",
            "expiryMonth",
            "expiryDay",
            "cashCreditLimit",
            "reissueYear",
            "reissueMonth",
            "reissueDay",
            "currentBalance",
            "currentCycleCredit",
            "accountGroupId",
            "currentCycleDebit",
            "customerId",
            "ssnPart1",
            "ssnPart2",
            "ssnPart3",
            "dateOfBirthYear",
            "dateOfBirthMonth",
            "dateOfBirthDay",
            "ficoScore",
            "firstName",
            "middleName",
            "lastName",
            "addressLine1",
            "stateCode",
            "addressLine2",
            "zipCode",
            "city",
            "countryCode",
            "phone1AreaCode",
            "phone1Prefix",
            "phone1LineNumber",
            "governmentIssuedId",
            "phone2AreaCode",
            "phone2Prefix",
            "phone2LineNumber",
            "eftAccountId",
            "primaryCardHolderIndicator",
            "infoMessage",
            "errorMessage",
            "error",
            "focusScreenFieldId",
            "nextRoute",
            "navigationContext",
            "fieldErrors");

    /** The six screen-metadata components, protected on the mapset. */
    private static final List<String> METADATA_COMPONENTS = List.of(
            "transactionName", "title01", "currentDate", "programName", "title02", "currentTime");

    /** The forty-three resulting value components. */
    private static final List<String> VALUE_COMPONENTS = List.of(
            "accountId",
            "accountStatus",
            "openYear",
            "openMonth",
            "openDay",
            "creditLimit",
            "expiryYear",
            "expiryMonth",
            "expiryDay",
            "cashCreditLimit",
            "reissueYear",
            "reissueMonth",
            "reissueDay",
            "currentBalance",
            "currentCycleCredit",
            "accountGroupId",
            "currentCycleDebit",
            "customerId",
            "ssnPart1",
            "ssnPart2",
            "ssnPart3",
            "dateOfBirthYear",
            "dateOfBirthMonth",
            "dateOfBirthDay",
            "ficoScore",
            "firstName",
            "middleName",
            "lastName",
            "addressLine1",
            "stateCode",
            "addressLine2",
            "zipCode",
            "city",
            "countryCode",
            "phone1AreaCode",
            "phone1Prefix",
            "phone1LineNumber",
            "governmentIssuedId",
            "phone2AreaCode",
            "phone2Prefix",
            "phone2LineNumber",
            "eftAccountId",
            "primaryCardHolderIndicator");

    /** The two message components, protected on the mapset. */
    private static final List<String> MESSAGE_COMPONENTS = List.of("infoMessage", "errorMessage");

    /** The five control components, which carry no legacy field value. */
    private static final List<String> CONTROL_COMPONENTS = List.of(
            "error", "focusScreenFieldId", "nextRoute", "navigationContext", "fieldErrors");

    /** The forty-five components that carry a declared maximum length. */
    private static final List<String> BOUNDED_COMPONENTS = List.of(
            "transactionName",
            "title01",
            "currentDate",
            "programName",
            "title02",
            "currentTime",
            "accountId",
            "accountStatus",
            "openYear",
            "openMonth",
            "openDay",
            "expiryYear",
            "expiryMonth",
            "expiryDay",
            "reissueYear",
            "reissueMonth",
            "reissueDay",
            "accountGroupId",
            "customerId",
            "ssnPart1",
            "ssnPart2",
            "ssnPart3",
            "dateOfBirthYear",
            "dateOfBirthMonth",
            "dateOfBirthDay",
            "ficoScore",
            "firstName",
            "lastName",
            "addressLine1",
            "stateCode",
            "zipCode",
            "city",
            "countryCode",
            "phone1AreaCode",
            "phone1Prefix",
            "phone1LineNumber",
            "governmentIssuedId",
            "phone2AreaCode",
            "phone2Prefix",
            "phone2LineNumber",
            "eftAccountId",
            "primaryCardHolderIndicator",
            "infoMessage",
            "errorMessage",
            "focusScreenFieldId");

    /** The twelve components that carry no declared maximum length. */
    private static final List<String> UNBOUNDED_COMPONENTS = List.of(
            "creditLimit",
            "cashCreditLimit",
            "currentBalance",
            "currentCycleCredit",
            "currentCycleDebit",
            "middleName",
            "addressLine2",
            "error",
            "nextRoute",
            "navigationContext",
            "fieldErrors");

    /** The five monetary components, each an exact decimal. */
    private static final List<String> MONETARY_COMPONENTS = List.of(
            "creditLimit",
            "cashCreditLimit",
            "currentBalance",
            "currentCycleCredit",
            "currentCycleDebit");

    /**
     * The two components that must carry no constraint annotation of any kind, because the legacy
     * codes no edits for either.
     */
    private static final List<String> UNVALIDATED_COMPONENTS = List.of("middleName", "addressLine2");

    /** The four editable components the decoration macro is never expanded for. */
    private static final List<String> UNDECORATED_VALUE_COMPONENTS =
            List.of("accountId", "accountGroupId", "customerId", "governmentIssuedId");

    /**
     * The fifty components the diagnostic rendering never names, standing collectively behind one
     * placeholder.
     */
    private static final List<String> UNNAMED_IN_RENDERING = Stream.concat(
                    Stream.concat(METADATA_COMPONENTS.stream(), VALUE_COMPONENTS.stream()),
                    Stream.of("infoMessage"))
            .toList();

    /** The seven keys the diagnostic rendering does name. */
    private static final List<String> NAMED_IN_RENDERING = List.of(
            "error",
            "errorMessage",
            "focusScreenFieldId",
            "fieldErrorCount",
            "nextRoute",
            "navigationContext",
            "values");

    /** The collective stand-in the rendering substitutes for every value. */
    private static final String PLACEHOLDER = "***REDACTED***";

    /** Eleven-character account identifier. */
    private static final String ACCOUNT_ID = "00000000011";

    /** One-character active status. */
    private static final String ACCOUNT_STATUS = "Y";

    /** Four-character open year. */
    private static final String OPEN_YEAR = "2019";

    /** Two-character open month. */
    private static final String OPEN_MONTH = "07";

    /** Two-character open day. */
    private static final String OPEN_DAY = "22";

    /** Four-character expiry year. */
    private static final String EXPIRY_YEAR = "2027";

    /** Two-character expiry month. */
    private static final String EXPIRY_MONTH = "11";

    /** Two-character expiry day. */
    private static final String EXPIRY_DAY = "30";

    /** Four-character reissue year. */
    private static final String REISSUE_YEAR = "2024";

    /** Two-character reissue month. */
    private static final String REISSUE_MONTH = "05";

    /** Two-character reissue day. */
    private static final String REISSUE_DAY = "09";

    /** Ten-character account group identifier. */
    private static final String ACCOUNT_GROUP_ID = "PLATINUM01";

    /** Nine-character customer identifier. */
    private static final String CUSTOMER_ID = "000000011";

    /** First social-security part, three characters. */
    private static final String SSN_PART_1 = "731";

    /** Second social-security part, two characters. */
    private static final String SSN_PART_2 = "52";

    /** Third social-security part, four characters. */
    private static final String SSN_PART_3 = "9846";

    /** Four-character birth year. */
    private static final String DATE_OF_BIRTH_YEAR = "1974";

    /** Two-character birth month. */
    private static final String DATE_OF_BIRTH_MONTH = "03";

    /** Two-character birth day. */
    private static final String DATE_OF_BIRTH_DAY = "18";

    /** Three-character credit score. */
    private static final String FICO_SCORE = "742";

    /** Distinctive first name, used as a non-disclosure oracle. */
    private static final String FIRST_NAME = "Marguerite";

    /** Distinctive middle name, used as a non-disclosure oracle. */
    private static final String MIDDLE_NAME = "Ottoline";

    /** Distinctive last name, used as a non-disclosure oracle. */
    private static final String LAST_NAME = "Fairweather";

    /** Distinctive first address line, used as a non-disclosure oracle. */
    private static final String ADDRESS_LINE_1 = "417 Kingsway Terrace";

    /** Two-character state code. */
    private static final String STATE_CODE = "WA";

    /** Distinctive second address line, used as a non-disclosure oracle. */
    private static final String ADDRESS_LINE_2 = "Unit 6B";

    /** Five-character postal code. */
    private static final String ZIP_CODE = "98225";

    /** Distinctive city, used as a non-disclosure oracle. */
    private static final String CITY = "Bellingham";

    /** Three-character country code. */
    private static final String COUNTRY_CODE = "USA";

    /** First telephone area code. */
    private static final String PHONE_1_AREA_CODE = "206";

    /** First telephone prefix. */
    private static final String PHONE_1_PREFIX = "555";

    /** First telephone line number. */
    private static final String PHONE_1_LINE_NUMBER = "0142";

    /** Distinctive government-issued identifier, used as a non-disclosure oracle. */
    private static final String GOVERNMENT_ISSUED_ID = "GOVT-4471902856";

    /** Second telephone area code. */
    private static final String PHONE_2_AREA_CODE = "360";

    /** Second telephone prefix. */
    private static final String PHONE_2_PREFIX = "555";

    /** Second telephone line number. */
    private static final String PHONE_2_LINE_NUMBER = "0873";

    /** Distinctive transfer-account identifier, used as a non-disclosure oracle. */
    private static final String EFT_ACCOUNT_ID = "EFT7719024";

    /** One-character primary-cardholder indicator. */
    private static final String PRIMARY_CARD_HOLDER_INDICATOR = "Y";

    /** Four-character transaction name. */
    private static final String TRANSACTION_NAME = "CAUP";

    /** First title line. */
    private static final String TITLE_01 = "AWS Mainframe Modernization";

    /** Clock date as the screen renders it. */
    private static final String CURRENT_DATE = "08/02/26";

    /** Program name this screen displays in its header. */
    private static final String PROGRAM_NAME = "COACTUPC";

    /** Second title line. */
    private static final String TITLE_02 = "CardDemo";

    /** Clock time as the screen renders it. */
    private static final String CURRENT_TIME = "14:35:07";

    /** Seven-character map field identity to focus. */
    private static final String FOCUS_SCREEN_FIELD_ID = "ACSTFCO";

    /** Declarative next route, deliberately unbounded. */
    private static final String NEXT_ROUTE = "/api/accounts/update";

    /** Credit limit, two decimal places. */
    private static final BigDecimal CREDIT_LIMIT = new BigDecimal("15000.00");

    /** Cash credit limit, two decimal places. */
    private static final BigDecimal CASH_CREDIT_LIMIT = new BigDecimal("3000.00");

    /** Current balance, negative and two decimal places. */
    private static final BigDecimal CURRENT_BALANCE = new BigDecimal("-2417.63");

    /** Current cycle credit, two decimal places. */
    private static final BigDecimal CURRENT_CYCLE_CREDIT = new BigDecimal("891.25");

    /** Current cycle debit, a two-decimal zero. */
    private static final BigDecimal CURRENT_CYCLE_DEBIT = new BigDecimal("0.00");

    /** A field error whose state reports a blank field. */
    private static final ErrorResponse.FieldError MISSING_STATE = new ErrorResponse.FieldError(
            "stateCode", "ACSSTTE", ErrorResponse.FieldState.MISSING, "State must be supplied");

    /** A field error whose state reports a badly filled field. */
    private static final ErrorResponse.FieldError INVALID_FICO = new ErrorResponse.FieldError(
            "ficoScore",
            "ACSTFCO",
            ErrorResponse.FieldState.INVALID,
            AccountUpdateResponse.SUFFIX_FICO_OUT_OF_RANGE);

    /** Bean Validation factory, opened once for the class and closed after it. */
    private static ValidatorFactory validatorFactory;

    /** Validator obtained from {@link #validatorFactory}. */
    private static Validator validator;

    /** Opens the Bean Validation factory used by the bound assertions. */
    @BeforeAll
    static void openValidatorFactory() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    /** Closes the Bean Validation factory opened for this class. */
    @AfterAll
    static void closeValidatorFactory() {
        if (validatorFactory != null) {
            validatorFactory.close();
        }
    }

    /**
     * Supplies each of the twelve label suffixes alongside an independently written expectation.
     *
     * @return the published constant, the hand-written expectation and the expected length
     */
    static Stream<Arguments> publishedSuffixes() {
        return Stream.of(
                Arguments.of(
                        AccountUpdateResponse.SUFFIX_FICO_OUT_OF_RANGE,
                        ": should be between 300 and 850",
                        31),
                Arguments.of(
                        AccountUpdateResponse.SUFFIX_STATE_NOT_VALID,
                        ": is not a valid state code",
                        27),
                Arguments.of(
                        AccountUpdateResponse.SUFFIX_AREA_CODE_REQUIRED,
                        ": Area code must be supplied.",
                        29),
                Arguments.of(
                        AccountUpdateResponse.SUFFIX_AREA_CODE_NOT_3_DIGITS,
                        ": Area code must be A 3 digit number.",
                        37),
                Arguments.of(
                        AccountUpdateResponse.SUFFIX_AREA_CODE_ZERO,
                        ": Area code cannot be zero",
                        26),
                Arguments.of(
                        AccountUpdateResponse.SUFFIX_AREA_CODE_NOT_GENERAL_PURPOSE,
                        ": Not valid North America general purpose area code",
                        51),
                Arguments.of(
                        AccountUpdateResponse.SUFFIX_PREFIX_REQUIRED,
                        ": Prefix code must be supplied.",
                        31),
                Arguments.of(
                        AccountUpdateResponse.SUFFIX_PREFIX_NOT_3_DIGITS,
                        ": Prefix code must be A 3 digit number.",
                        39),
                Arguments.of(
                        AccountUpdateResponse.SUFFIX_PREFIX_ZERO,
                        ": Prefix code cannot be zero",
                        28),
                Arguments.of(
                        AccountUpdateResponse.SUFFIX_LINE_NUMBER_REQUIRED,
                        ": Line number code must be supplied.",
                        36),
                Arguments.of(
                        AccountUpdateResponse.SUFFIX_LINE_NUMBER_NOT_4_DIGITS,
                        ": Line number code must be A 4 digit number.",
                        44),
                Arguments.of(
                        AccountUpdateResponse.SUFFIX_LINE_NUMBER_ZERO,
                        ": Line number code cannot be zero",
                        33));
    }

    /**
     * Supplies each of the fifteen whole message texts alongside an independently written
     * expectation.
     *
     * @return the published constant, the hand-written expectation and the expected length
     */
    static Stream<Arguments> publishedMessages() {
        return Stream.of(
                Arguments.of(
                        AccountUpdateResponse.MSG_INVALID_ZIP_FOR_STATE,
                        "Invalid zip code for state",
                        26),
                Arguments.of(
                        AccountUpdateResponse.MSG_ACCOUNT_NUMBER_NOT_USABLE,
                        "Account number must be a non zero 11 digit number",
                        49),
                Arguments.of(
                        AccountUpdateResponse.MSG_ACCOUNT_STATUS_MUST_BE_YES_NO,
                        "Account Active Status must be Y or N",
                        36),
                Arguments.of(
                        AccountUpdateResponse.MSG_CREDIT_LIMIT_REQUIRED,
                        "Credit Limit must be supplied",
                        29),
                Arguments.of(
                        AccountUpdateResponse.MSG_CREDIT_LIMIT_NOT_VALID,
                        "Credit Limit is not valid",
                        25),
                Arguments.of(
                        AccountUpdateResponse.MSG_EXPIRY_MONTH_NOT_VALID,
                        "Card expiry month must be between 1 and 12",
                        42),
                Arguments.of(
                        AccountUpdateResponse.MSG_EXPIRY_YEAR_NOT_VALID,
                        "Invalid card expiry year",
                        24),
                Arguments.of(
                        AccountUpdateResponse.MSG_ACCOUNT_NOT_IN_CARD_DATABASE,
                        "Did not find this account in cards database",
                        43),
                Arguments.of(
                        AccountUpdateResponse.MSG_NO_CARDS_FOR_SEARCH_CONDITION,
                        "Did not find cards for this search condition",
                        44),
                Arguments.of(
                        AccountUpdateResponse.MSG_CARD_DATA_READ_ERROR,
                        "Error reading Card Data File",
                        28),
                Arguments.of(
                        AccountUpdateResponse.MSG_LOOKS_GOOD_SO_FAR, "Looks Good.... so far", 21),
                Arguments.of(
                        AccountUpdateResponse.MSG_COULD_NOT_HOLD_ACCOUNT_FOR_UPDATE,
                        "Could not lock account record for update",
                        40),
                Arguments.of(
                        AccountUpdateResponse.MSG_COULD_NOT_HOLD_CUSTOMER_FOR_UPDATE,
                        "Could not lock customer record for update",
                        41),
                Arguments.of(
                        AccountUpdateResponse.MSG_RECORD_CHANGED_BEFORE_UPDATE,
                        "Record changed by some one else. Please review",
                        46),
                Arguments.of(
                        AccountUpdateResponse.MSG_UPDATE_OF_RECORD_FAILED,
                        "Update of record failed",
                        23));
    }

    /**
     * Supplies the three digit-count suffixes whose article the legacy capitalises.
     *
     * @return the three texts carrying a capitalised article
     */
    static Stream<Arguments> capitalisedArticleSuffixes() {
        return Stream.of(
                Arguments.of(AccountUpdateResponse.SUFFIX_AREA_CODE_NOT_3_DIGITS),
                Arguments.of(AccountUpdateResponse.SUFFIX_PREFIX_NOT_3_DIGITS),
                Arguments.of(AccountUpdateResponse.SUFFIX_LINE_NUMBER_NOT_4_DIGITS));
    }

    /**
     * Supplies the distinctive fixture values that must never reach a diagnostic rendering.
     *
     * @return the values used as non-disclosure oracles
     */
    static Stream<Arguments> distinctiveValues() {
        return Stream.of(
                Arguments.of("first name", FIRST_NAME),
                Arguments.of("middle name", MIDDLE_NAME),
                Arguments.of("last name", LAST_NAME),
                Arguments.of("first address line", ADDRESS_LINE_1),
                Arguments.of("second address line", ADDRESS_LINE_2),
                Arguments.of("city", CITY),
                Arguments.of("postal code", ZIP_CODE),
                Arguments.of("government-issued identifier", GOVERNMENT_ISSUED_ID),
                Arguments.of("transfer-account identifier", EFT_ACCOUNT_ID),
                Arguments.of("credit score", FICO_SCORE),
                Arguments.of("third social-security part", SSN_PART_3),
                Arguments.of("account group identifier", ACCOUNT_GROUP_ID),
                Arguments.of("account identifier", ACCOUNT_ID),
                Arguments.of("customer identifier", CUSTOMER_ID),
                Arguments.of("credit limit", CREDIT_LIMIT.toPlainString()),
                Arguments.of("cash credit limit", CASH_CREDIT_LIMIT.toPlainString()),
                Arguments.of("current balance", CURRENT_BALANCE.toPlainString()),
                Arguments.of("current cycle credit", CURRENT_CYCLE_CREDIT.toPlainString()),
                Arguments.of("informational message", AccountUpdateResponse.MSG_LOOKS_GOOD_SO_FAR));
    }

    /**
     * Counts non-overlapping occurrences of a token inside a rendering.
     *
     * @param rendering the text to scan
     * @param token the token to count
     * @return the number of occurrences
     */
    private static int countOccurrences(String rendering, String token) {
        int count = 0;
        int index = rendering.indexOf(token);
        while (index >= 0) {
            count++;
            index = rendering.indexOf(token, index + token.length());
        }
        return count;
    }

    /**
     * Builds a response from the supplied components, leaving every unnamed component absent.
     *
     * @param text the text components to populate, keyed by declared component name
     * @param monetary the monetary components to populate, keyed by declared component name
     * @param error whether the response reports a failed submission
     * @param fieldErrors the per-field errors to carry, which may be {@code null}
     * @param navigation the echoed conversation state, which may be {@code null}
     * @return a response carrying exactly the supplied components
     */
    private static AccountUpdateResponse build(
            Map<String, String> text,
            Map<String, BigDecimal> monetary,
            boolean error,
            List<ErrorResponse.FieldError> fieldErrors,
            NavigationContext navigation) {
        return new AccountUpdateResponse(
                text.get("transactionName"),
                text.get("title01"),
                text.get("currentDate"),
                text.get("programName"),
                text.get("title02"),
                text.get("currentTime"),
                text.get("accountId"),
                text.get("accountStatus"),
                text.get("openYear"),
                text.get("openMonth"),
                text.get("openDay"),
                monetary.get("creditLimit"),
                text.get("expiryYear"),
                text.get("expiryMonth"),
                text.get("expiryDay"),
                monetary.get("cashCreditLimit"),
                text.get("reissueYear"),
                text.get("reissueMonth"),
                text.get("reissueDay"),
                monetary.get("currentBalance"),
                monetary.get("currentCycleCredit"),
                text.get("accountGroupId"),
                monetary.get("currentCycleDebit"),
                text.get("customerId"),
                text.get("ssnPart1"),
                text.get("ssnPart2"),
                text.get("ssnPart3"),
                text.get("dateOfBirthYear"),
                text.get("dateOfBirthMonth"),
                text.get("dateOfBirthDay"),
                text.get("ficoScore"),
                text.get("firstName"),
                text.get("middleName"),
                text.get("lastName"),
                text.get("addressLine1"),
                text.get("stateCode"),
                text.get("addressLine2"),
                text.get("zipCode"),
                text.get("city"),
                text.get("countryCode"),
                text.get("phone1AreaCode"),
                text.get("phone1Prefix"),
                text.get("phone1LineNumber"),
                text.get("governmentIssuedId"),
                text.get("phone2AreaCode"),
                text.get("phone2Prefix"),
                text.get("phone2LineNumber"),
                text.get("eftAccountId"),
                text.get("primaryCardHolderIndicator"),
                text.get("infoMessage"),
                text.get("errorMessage"),
                error,
                text.get("focusScreenFieldId"),
                text.get("nextRoute"),
                navigation,
                fieldErrors);
    }

    /**
     * Builds a response carrying exactly one text component.
     *
     * @param component the component to populate
     * @param value the value to place in that component
     * @return a response carrying only the named component
     */
    private static AccountUpdateResponse carrying(String component, String value) {
        Map<String, String> text = new HashMap<>();
        text.put(component, value);
        return build(text, Map.of(), false, null, null);
    }

    /**
     * Builds a response carrying exactly one monetary component.
     *
     * @param component the component to populate
     * @param value the amount to place in that component
     * @return a response carrying only the named component
     */
    private static AccountUpdateResponse carryingAmount(String component, BigDecimal value) {
        Map<String, BigDecimal> monetary = new HashMap<>();
        monetary.put(component, value);
        return build(Map.of(), monetary, false, null, null);
    }

    /**
     * Builds the empty response: no component populated, no failure and no field error.
     *
     * @return a response with every optional component absent
     */
    private static AccountUpdateResponse empty() {
        return build(Map.of(), Map.of(), false, null, null);
    }

    /**
     * Assembles every text component of the every-component fixture.
     *
     * @return the text components keyed by declared component name
     */
    private static Map<String, String> populatedText() {
        Map<String, String> text = new HashMap<>();
        text.put("transactionName", TRANSACTION_NAME);
        text.put("title01", TITLE_01);
        text.put("currentDate", CURRENT_DATE);
        text.put("programName", PROGRAM_NAME);
        text.put("title02", TITLE_02);
        text.put("currentTime", CURRENT_TIME);
        text.put("accountId", ACCOUNT_ID);
        text.put("accountStatus", ACCOUNT_STATUS);
        text.put("openYear", OPEN_YEAR);
        text.put("openMonth", OPEN_MONTH);
        text.put("openDay", OPEN_DAY);
        text.put("expiryYear", EXPIRY_YEAR);
        text.put("expiryMonth", EXPIRY_MONTH);
        text.put("expiryDay", EXPIRY_DAY);
        text.put("reissueYear", REISSUE_YEAR);
        text.put("reissueMonth", REISSUE_MONTH);
        text.put("reissueDay", REISSUE_DAY);
        text.put("accountGroupId", ACCOUNT_GROUP_ID);
        text.put("customerId", CUSTOMER_ID);
        text.put("ssnPart1", SSN_PART_1);
        text.put("ssnPart2", SSN_PART_2);
        text.put("ssnPart3", SSN_PART_3);
        text.put("dateOfBirthYear", DATE_OF_BIRTH_YEAR);
        text.put("dateOfBirthMonth", DATE_OF_BIRTH_MONTH);
        text.put("dateOfBirthDay", DATE_OF_BIRTH_DAY);
        text.put("ficoScore", FICO_SCORE);
        text.put("firstName", FIRST_NAME);
        text.put("middleName", MIDDLE_NAME);
        text.put("lastName", LAST_NAME);
        text.put("addressLine1", ADDRESS_LINE_1);
        text.put("stateCode", STATE_CODE);
        text.put("addressLine2", ADDRESS_LINE_2);
        text.put("zipCode", ZIP_CODE);
        text.put("city", CITY);
        text.put("countryCode", COUNTRY_CODE);
        text.put("phone1AreaCode", PHONE_1_AREA_CODE);
        text.put("phone1Prefix", PHONE_1_PREFIX);
        text.put("phone1LineNumber", PHONE_1_LINE_NUMBER);
        text.put("governmentIssuedId", GOVERNMENT_ISSUED_ID);
        text.put("phone2AreaCode", PHONE_2_AREA_CODE);
        text.put("phone2Prefix", PHONE_2_PREFIX);
        text.put("phone2LineNumber", PHONE_2_LINE_NUMBER);
        text.put("eftAccountId", EFT_ACCOUNT_ID);
        text.put("primaryCardHolderIndicator", PRIMARY_CARD_HOLDER_INDICATOR);
        text.put("infoMessage", AccountUpdateResponse.MSG_LOOKS_GOOD_SO_FAR);
        text.put("errorMessage", AccountUpdateResponse.MSG_ACCOUNT_NUMBER_NOT_USABLE);
        text.put("focusScreenFieldId", FOCUS_SCREEN_FIELD_ID);
        text.put("nextRoute", NEXT_ROUTE);
        return text;
    }

    /**
     * Assembles every monetary component of the every-component fixture.
     *
     * @return the monetary components keyed by declared component name
     */
    private static Map<String, BigDecimal> populatedMonetary() {
        Map<String, BigDecimal> monetary = new HashMap<>();
        monetary.put("creditLimit", CREDIT_LIMIT);
        monetary.put("cashCreditLimit", CASH_CREDIT_LIMIT);
        monetary.put("currentBalance", CURRENT_BALANCE);
        monetary.put("currentCycleCredit", CURRENT_CYCLE_CREDIT);
        monetary.put("currentCycleDebit", CURRENT_CYCLE_DEBIT);
        return monetary;
    }

    /**
     * Builds the every-component fixture, optionally carrying the echoed conversation state.
     *
     * @param navigation the echoed conversation state, which may be {@code null}
     * @return a response carrying every component
     */
    private static AccountUpdateResponse populated(NavigationContext navigation) {
        return build(
                populatedText(),
                populatedMonetary(),
                true,
                List.of(MISSING_STATE, INVALID_FICO),
                navigation);
    }

    /**
     * Serialises the supplied response with the module's declared settings.
     *
     * @param response the response to serialise
     * @return the emitted JSON text
     * @throws JsonProcessingException if serialisation fails, which fails the calling test
     */
    private static String payloadOf(AccountUpdateResponse response) throws JsonProcessingException {
        return JsonContractSupport.declaredSettingsMapper().writeValueAsString(response);
    }

    /** The declared shape of the record: components, order, widths and declared surface. */
    @Nested
    @DisplayName("Declared contract")
    class DeclaredContract {

        /**
         * Reads the declared maximum length of a component.
         *
         * @param component the component name
         * @return the declared maximum length
         * @throws NoSuchFieldException if the component does not exist, failing the calling test
         */
        private int boundOf(String component) throws NoSuchFieldException {
            Size size =
                    AccountUpdateResponse.class.getDeclaredField(component).getAnnotation(Size.class);
            assertThat(size)
                    .as("component %s must declare a maximum length", component)
                    .isNotNull();
            return size.max();
        }

        /** The fifty-six components appear in map declaration order followed by the controls. */
        @Test
        @DisplayName("declares fifty-six components in the documented order")
        void theComponentsAreDeclaredInTheDocumentedOrder() {
            List<String> declared =
                    Arrays.stream(AccountUpdateResponse.class.getRecordComponents())
                            .map(RecordComponent::getName)
                            .toList();

            assertThat(declared).containsExactlyElementsOf(EXPECTED_COMPONENTS);
            assertThat(declared).hasSize(56);
        }

        /**
         * Fifty-one carried map families plus five control components account for every component,
         * and the fifty-four declared families minus the three function-key legends give the
         * fifty-one.
         */
        @Test
        @DisplayName("accounts for fifty-one map families and five control components")
        void theCarriedFamiliesAndControlsAccountForEveryComponent() {
            assertThat(VALUE_COMPONENTS).hasSize(43);
            assertThat(METADATA_COMPONENTS).hasSize(6);
            assertThat(MESSAGE_COMPONENTS).hasSize(2);
            assertThat(CONTROL_COMPONENTS).hasSize(5);

            int carriedFamilies =
                    VALUE_COMPONENTS.size() + METADATA_COMPONENTS.size() + MESSAGE_COMPONENTS.size();

            assertThat(carriedFamilies).as("fifty-four declared less three legends").isEqualTo(51);
            assertThat(carriedFamilies + CONTROL_COMPONENTS.size())
                    .isEqualTo(EXPECTED_COMPONENTS.size());

            List<String> partition = new ArrayList<>(METADATA_COMPONENTS);
            partition.addAll(VALUE_COMPONENTS);
            partition.addAll(MESSAGE_COMPONENTS);
            partition.addAll(CONTROL_COMPONENTS);

            assertThat(partition).containsExactlyInAnyOrderElementsOf(EXPECTED_COMPONENTS);
        }

        /** The six metadata components lead, in map declaration order. */
        @Test
        @DisplayName("declares the six metadata components first")
        void theMetadataComponentsAreDeclaredFirst() {
            List<String> declared =
                    Arrays.stream(AccountUpdateResponse.class.getRecordComponents())
                            .map(RecordComponent::getName)
                            .toList();

            assertThat(declared.subList(0, 6)).containsExactlyElementsOf(METADATA_COMPONENTS);
        }

        /** The five control components trail, after both message slots, and nothing follows them. */
        @Test
        @DisplayName("declares the five control components last, with nothing after them")
        void theControlComponentsAreDeclaredLast() {
            List<String> declared =
                    Arrays.stream(AccountUpdateResponse.class.getRecordComponents())
                            .map(RecordComponent::getName)
                            .toList();

            assertThat(declared.subList(51, 56)).containsExactlyElementsOf(CONTROL_COMPONENTS);
            assertThat(declared).last().isEqualTo("fieldErrors");
        }

        /**
         * No component stands for an optimistic-lock, version or entity-tag value.
         *
         * <p>The legacy detected a concurrent change by comparing the record image it carried across
         * the pseudo-conversational turn against the records it re-read before writing. None of that
         * crosses this boundary: the version attribute lives on the entity, the comparison lives in
         * the update service, and a conflict reaches the client as a summary text plus field errors.
         * The whole declared set is screened rather than a handful of spellings, because a
         * differently named component is precisely how such a value reappears.
         */
        @Test
        @DisplayName("declares no optimistic-lock, version or entity-tag component")
        void declaresNoOptimisticLockComponent() {
            List<String> declared =
                    Arrays.stream(AccountUpdateResponse.class.getRecordComponents())
                            .map(RecordComponent::getName)
                            .toList();

            assertThat(declared).doesNotContain("concurrencyToken", "version", "rowVersion",
                    "recordVersion", "lockVersion", "etag", "eTag", "optimisticLock", "revision",
                    "oldImage", "beforeImage", "recordImage", "snapshot");
            assertThat(declared).allSatisfy(name -> {
                String lowered = name.toLowerCase(Locale.ROOT);
                assertThat(lowered).doesNotContain("concurrency").doesNotContain("version")
                        .doesNotContain("etag").doesNotContain("revision").doesNotContain("token")
                        .doesNotContain("lock").doesNotContain("stamp").doesNotContain("image")
                        .doesNotContain("snapshot").doesNotContain("digest").doesNotContain("seal");
            });
        }

        /** Each bounded component declares the width the symbolic map declares. */
        @ParameterizedTest(name = "{0} is bounded at {1}")
        @CsvSource({
            "transactionName,4",
            "title01,40",
            "currentDate,8",
            "programName,8",
            "title02,40",
            "currentTime,8",
            "accountId,11",
            "accountStatus,1",
            "openYear,4",
            "openMonth,2",
            "openDay,2",
            "expiryYear,4",
            "expiryMonth,2",
            "expiryDay,2",
            "reissueYear,4",
            "reissueMonth,2",
            "reissueDay,2",
            "accountGroupId,10",
            "customerId,9",
            "ssnPart1,3",
            "ssnPart2,2",
            "ssnPart3,4",
            "dateOfBirthYear,4",
            "dateOfBirthMonth,2",
            "dateOfBirthDay,2",
            "ficoScore,3",
            "firstName,25",
            "lastName,25",
            "addressLine1,50",
            "stateCode,2",
            "zipCode,5",
            "city,50",
            "countryCode,3",
            "phone1AreaCode,3",
            "phone1Prefix,3",
            "phone1LineNumber,4",
            "governmentIssuedId,20",
            "phone2AreaCode,3",
            "phone2Prefix,3",
            "phone2LineNumber,4",
            "eftAccountId,10",
            "primaryCardHolderIndicator,1",
            "infoMessage,45",
            "errorMessage,78",
            "focusScreenFieldId,7"
        })
        @DisplayName("declares each map width")
        void eachBoundedComponentDeclaresItsMapWidth(String component, int width)
                throws NoSuchFieldException {
            assertThat(boundOf(component)).isEqualTo(width);
        }

        /** Every component is accounted for as bounded or unbounded. */
        @Test
        @DisplayName("accounts for every component as bounded or unbounded")
        void everyComponentIsAccountedFor() {
            List<String> partition = new ArrayList<>(BOUNDED_COMPONENTS);
            partition.addAll(UNBOUNDED_COMPONENTS);

            assertThat(partition).containsExactlyInAnyOrderElementsOf(EXPECTED_COMPONENTS);
            assertThat(BOUNDED_COMPONENTS).hasSize(45);
            assertThat(UNBOUNDED_COMPONENTS).hasSize(11);
        }

        /** The unbounded components declare no maximum length. */
        @ParameterizedTest(name = "{0} declares no width")
        @ValueSource(
                strings = {
                    "creditLimit",
                    "cashCreditLimit",
                    "currentBalance",
                    "currentCycleCredit",
                    "currentCycleDebit",
                    "middleName",
                    "addressLine2",
                    "error",
                    "nextRoute",
                    "navigationContext",
                    "fieldErrors"
                })
        @DisplayName("leaves the amounts, unvalidated fields, indicator, route and state unbounded")
        void theUnboundedComponentsDeclareNoWidth(String component) throws NoSuchFieldException {
            assertThat(
                            AccountUpdateResponse.class
                                    .getDeclaredField(component)
                                    .getAnnotation(Size.class))
                    .isNull();
        }

        /** Each monetary component is an exact decimal and never a floating-point type. */
        @ParameterizedTest(name = "{0} is an exact decimal")
        @ValueSource(
                strings = {
                    "creditLimit",
                    "cashCreditLimit",
                    "currentBalance",
                    "currentCycleCredit",
                    "currentCycleDebit"
                })
        @DisplayName("declares each monetary component as an exact decimal")
        void eachMonetaryComponentIsAnExactDecimal(String component) throws NoSuchFieldException {
            assertThat(AccountUpdateResponse.class.getDeclaredField(component).getType())
                    .isEqualTo(BigDecimal.class);
        }

        /** No component is a floating-point, primitive numeric or temporal type. */
        @Test
        @DisplayName("declares no floating-point, numeric or temporal component")
        void noFloatingPointNumericOrTemporalComponentIsDeclared() {
            assertThat(AccountUpdateResponse.class.getRecordComponents())
                    .allSatisfy(
                            component -> {
                                assertThat(component.getType())
                                        .isNotEqualTo(double.class)
                                        .isNotEqualTo(Double.class)
                                        .isNotEqualTo(float.class)
                                        .isNotEqualTo(Float.class)
                                        .isNotEqualTo(int.class)
                                        .isNotEqualTo(Integer.class)
                                        .isNotEqualTo(long.class);
                                assertThat(component.getType().getName())
                                        .doesNotStartWith("java.time");
                            });
        }

        /**
         * Every identifier and code stays a bounded string, so a credit score of three characters
         * survives the round trip rather than arriving as an integer.
         */
        @ParameterizedTest(name = "{0} is text")
        @ValueSource(strings = {"accountId", "customerId", "ficoScore", "zipCode", "countryCode"})
        @DisplayName("keeps every identifier and code as text")
        void everyIdentifierAndCodeIsText(String component) throws NoSuchFieldException {
            assertThat(AccountUpdateResponse.class.getDeclaredField(component).getType())
                    .isEqualTo(String.class);
        }

        /** The error indicator is a primitive boolean, stated explicitly. */
        @Test
        @DisplayName("declares the error indicator as a primitive boolean")
        void theErrorIndicatorIsAPrimitiveBoolean() throws NoSuchFieldException {
            assertThat(AccountUpdateResponse.class.getDeclaredField("error").getType())
                    .isEqualTo(boolean.class);
        }

        /**
         * The declared surface beyond the accessors is the single presence test plus the private
         * record-shape verification the compact constructor delegates to. The verification is not
         * behaviour a caller can reach: it is private, static, and either returns or throws.
         */
        @Test
        @DisplayName("declares one presence test and one private shape verification and nothing "
                + "else")
        void theDeclaredSurfaceBeyondTheAccessorsIsOnePresenceTestAndOneVerification()
                throws NoSuchMethodException {
            List<String> declared =
                    Arrays.stream(AccountUpdateResponse.class.getDeclaredMethods())
                            .filter(method -> !method.isSynthetic())
                            .map(Method::getName)
                            .filter(
                                    name ->
                                            !"equals".equals(name)
                                                    && !"hashCode".equals(name)
                                                    && !"toString".equals(name))
                            .filter(name -> !EXPECTED_COMPONENTS.contains(name))
                            .toList();

            assertThat(declared).containsExactlyInAnyOrder("hasFieldErrors", "requireRecordShape");
            assertThat(AccountUpdateResponse.class.getDeclaredMethod(
                            "requireRecordShape", String.class, java.math.BigDecimal.class))
                    .satisfies(method -> {
                        assertThat(Modifier.isPrivate(method.getModifiers())).isTrue();
                        assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
                        assertThat(method.getReturnType()).isEqualTo(void.class);
                    });
        }

        /** Only the compact canonical constructor exists, taking all fifty-seven components. */
        @Test
        @DisplayName("keeps only the compact canonical constructor")
        void onlyTheCompactCanonicalConstructorExists() {
            assertThat(AccountUpdateResponse.class.getDeclaredConstructors()).hasSize(1);
            assertThat(AccountUpdateResponse.class.getDeclaredConstructors()[0].getParameterCount())
                    .isEqualTo(EXPECTED_COMPONENTS.size());
        }

        /** No serialisation annotation appears on any component. */
        @Test
        @DisplayName("declares no serialisation annotation on any component")
        void noSerialisationAnnotationAppearsOnAnyComponent() {
            List<Annotation> annotations = new ArrayList<>();
            for (Field field : AccountUpdateResponse.class.getDeclaredFields()) {
                annotations.addAll(Arrays.asList(field.getAnnotations()));
            }

            assertThat(annotations)
                    .noneMatch(
                            annotation ->
                                    annotation
                                            .annotationType()
                                            .getName()
                                            .startsWith("com.fasterxml.jackson"));
        }

        /** No constraint other than a maximum length is declared anywhere. */
        @Test
        @DisplayName("declares no constraint other than a maximum length")
        void noConstraintOtherThanAMaximumLengthIsDeclared() {
            List<Annotation> constraints = new ArrayList<>();
            for (Field field : AccountUpdateResponse.class.getDeclaredFields()) {
                Arrays.stream(field.getAnnotations())
                        .filter(
                                annotation ->
                                        annotation
                                                .annotationType()
                                                .getName()
                                                .startsWith("jakarta.validation"))
                        .forEach(constraints::add);
            }

            assertThat(constraints)
                    .allSatisfy(
                            annotation ->
                                    assertThat(annotation.annotationType()).isEqualTo(Size.class));
            assertThat(constraints).hasSize(BOUNDED_COMPONENTS.size());
        }

        /**
         * The static surface is twelve suffixes, fifteen texts, the two record-shape constants of the
         * monetary components and one internal placeholder.
         */
        @Test
        @DisplayName("publishes twelve suffixes, fifteen texts and two record-shape constants, and "
                + "keeps one placeholder private")
        void theStaticSurfaceIsTwelveSuffixesFifteenTextsTwoShapeConstantsAndOnePlaceholder() {
            List<Field> statics =
                    Arrays.stream(AccountUpdateResponse.class.getDeclaredFields())
                            .filter(field -> Modifier.isStatic(field.getModifiers()))
                            .filter(field -> !field.isSynthetic())
                            .toList();

            List<String> published =
                    statics.stream()
                            .filter(field -> Modifier.isPublic(field.getModifiers()))
                            .map(Field::getName)
                            .toList();
            List<String> internal =
                    statics.stream()
                            .filter(field -> Modifier.isPrivate(field.getModifiers()))
                            .map(Field::getName)
                            .toList();

            assertThat(published.stream().filter(name -> name.startsWith("SUFFIX_")).toList())
                    .hasSize(12);
            assertThat(published.stream().filter(name -> name.startsWith("MSG_")).toList())
                    .hasSize(15);
            assertThat(published.stream().filter(name -> name.startsWith("MONEY_")).toList())
                    .as("the two record-shape constants of the monetary components, which are the "
                            + "only published numbers on the type")
                    .containsExactlyInAnyOrder("MONEY_SCALE", "MONEY_INTEGER_DIGITS");
            assertThat(published).hasSize(29);
            assertThat(internal).containsExactly("REDACTION_PLACEHOLDER");
            assertThat(statics).hasSize(30);
            assertThat(statics)
                    .filteredOn(field -> !field.getName().startsWith("MONEY_"))
                    .allSatisfy(field -> assertThat(field.getType()).isEqualTo(String.class));
            assertThat(statics)
                    .filteredOn(field -> field.getName().startsWith("MONEY_"))
                    .allSatisfy(field -> assertThat(field.getType()).isEqualTo(int.class));
        }

        /**
         * The two published numbers restate the persisted monetary field rather than a screen width:
         * the five amount components stand for record fields of {@code PIC S9(10)V99}, so ten integer
         * digits and a scale of exactly two, which is a total precision of twelve.
         */
        @Test
        @DisplayName("publishes the record shape of the monetary components")
        void theRecordShapeOfTheMonetaryComponentsIsPublished() {
            assertThat(AccountUpdateResponse.MONEY_SCALE).isEqualTo(2);
            assertThat(AccountUpdateResponse.MONEY_INTEGER_DIGITS).isEqualTo(10);
            assertThat(AccountUpdateResponse.MONEY_INTEGER_DIGITS + AccountUpdateResponse.MONEY_SCALE)
                    .as("total precision of the persistence column")
                    .isEqualTo(12);
        }

        /**
         * No screen-width constant is declared; each map width sits inline on the component it
         * bounds. The two published numbers are the record shape of the monetary components, which is
         * a property of the persisted field rather than of a screen item, so they are named
         * explicitly here instead of being admitted by a loosened rule.
         */
        @Test
        @DisplayName("declares no screen-width constant")
        void noScreenWidthConstantIsDeclared() {
            assertThat(AccountUpdateResponse.class.getDeclaredFields())
                    .filteredOn(field -> Modifier.isStatic(field.getModifiers()))
                    .filteredOn(field -> field.getType() == int.class)
                    .extracting(Field::getName)
                    .containsExactlyInAnyOrder("MONEY_SCALE", "MONEY_INTEGER_DIGITS");
            assertThat(AccountUpdateResponse.class.getDeclaredFields())
                    .filteredOn(field -> Modifier.isStatic(field.getModifiers()))
                    .extracting(Field::getName)
                    .as("a name ending in a length or width would mean a map width had been lifted "
                            + "off the component it bounds")
                    .noneMatch(name -> name.endsWith("_LENGTH") || name.endsWith("_WIDTH"));
        }
    }

    /**
     * The two components the legacy decorates but never validates, whose constraint absence is the
     * contract.
     */
    @Nested
    @DisplayName("Unvalidated components")
    class UnvalidatedComponents {

        /**
         * Neither component carries a constraint annotation of any kind. Attaching one would reject
         * input the legacy accepts, because the source records that no edits are coded for either.
         */
        @ParameterizedTest(name = "{0} carries no constraint at all")
        @ValueSource(strings = {"middleName", "addressLine2"})
        @DisplayName("declares no constraint at all on either component")
        void neitherComponentCarriesAnyConstraint(String component) throws NoSuchFieldException {
            Annotation[] annotations =
                    AccountUpdateResponse.class.getDeclaredField(component).getAnnotations();

            assertThat(annotations)
                    .as("component %s must carry no constraint, not even a maximum length", component)
                    .noneMatch(
                            annotation ->
                                    annotation
                                            .annotationType()
                                            .getName()
                                            .startsWith("jakarta.validation"));
            assertThat(
                            AccountUpdateResponse.class
                                    .getDeclaredField(component)
                                    .getAnnotation(Size.class))
                    .isNull();
        }

        /** Both are still declared as ordinary text components carried on the response. */
        @ParameterizedTest(name = "{0} is still carried")
        @ValueSource(strings = {"middleName", "addressLine2"})
        @DisplayName("still carries both components as text")
        void bothComponentsAreStillCarried(String component) throws NoSuchFieldException {
            assertThat(AccountUpdateResponse.class.getDeclaredField(component).getType())
                    .isEqualTo(String.class);
            assertThat(EXPECTED_COMPONENTS).contains(component);
        }

        /**
         * The request contract makes the same choice, and the two must agree: were either side to
         * acquire a bound, a value the other accepts would start being rejected.
         */
        @ParameterizedTest(name = "{0} agrees with the request contract")
        @ValueSource(strings = {"middleName", "addressLine2"})
        @DisplayName("agrees with the request contract on both components")
        void bothComponentsAgreeWithTheRequestContract(String component)
                throws NoSuchFieldException {
            assertThat(
                            AccountUpdateRequest.class
                                    .getDeclaredField(component)
                                    .getAnnotation(Size.class))
                    .as("the request side must be unannotated for the same reason")
                    .isNull();
            assertThat(
                            AccountUpdateResponse.class
                                    .getDeclaredField(component)
                                    .getAnnotation(Size.class))
                    .isNull();
        }

        /** An arbitrarily long value in either component produces no violation. */
        @ParameterizedTest(name = "{0} accepts any length")
        @ValueSource(strings = {"middleName", "addressLine2"})
        @DisplayName("accepts a value of any length in either component")
        void anyLengthIsAcceptedInEitherComponent(String component) {
            assertThat(validator.validate(carrying(component, "Z".repeat(4096)))).isEmpty();
            assertThat(carrying(component, "Z".repeat(4096)))
                    .extracting(component)
                    .asString()
                    .hasSize(4096);
        }

        /** Both are among the thirty-nine decorated fields, so both are structurally flaggable. */
        @Test
        @DisplayName("keeps both components structurally flaggable")
        void bothComponentsRemainStructurallyFlaggable() {
            ErrorResponse.FieldError middleNameError =
                    new ErrorResponse.FieldError(
                            "middleName",
                            "ACSMNAM",
                            ErrorResponse.FieldState.INVALID,
                            "structurally representable");

            AccountUpdateResponse response =
                    build(Map.of(), Map.of(), true, List.of(middleNameError), null);

            assertThat(response.fieldErrors()).containsExactly(middleNameError);
            assertThat(response.hasFieldErrors()).isTrue();
        }

        /**
         * The four editable-but-undecorated components are a different set entirely, and none of
         * them is one of the two unvalidated components.
         */
        @Test
        @DisplayName("keeps the undecorated set distinct from the unvalidated pair")
        void theUndecoratedSetIsDistinctFromTheUnvalidatedPair() {
            assertThat(UNDECORATED_VALUE_COMPONENTS).hasSize(4).doesNotContainAnyElementsOf(
                    UNVALIDATED_COMPONENTS);
            assertThat(VALUE_COMPONENTS.size() - UNDECORATED_VALUE_COMPONENTS.size())
                    .as("thirty-nine decoration sites over forty-three values")
                    .isEqualTo(39);
            assertThat(VALUE_COMPONENTS).containsAll(UNDECORATED_VALUE_COMPONENTS);
        }

        /** Each undecorated component still declares its map width. */
        @ParameterizedTest(name = "{0} is bounded even though undecorated")
        @CsvSource({
            "accountId,11",
            "accountGroupId,10",
            "customerId,9",
            "governmentIssuedId,20"
        })
        @DisplayName("bounds each undecorated component at its map width")
        void eachUndecoratedComponentIsStillBounded(String component, int width)
                throws NoSuchFieldException {
            assertThat(
                            AccountUpdateResponse.class
                                    .getDeclaredField(component)
                                    .getAnnotation(Size.class)
                                    .max())
                    .isEqualTo(width);
        }
    }

    /** The twenty-seven published fragments, their punctuation and their four deliberate quirks. */
    @Nested
    @DisplayName("Message vocabulary")
    class MessageVocabulary {

        /** Each label suffix matches its independently written expectation exactly. */
        @ParameterizedTest(name = "[{index}] {1}")
        @MethodSource("com.carddemo.api.dto.AccountUpdateResponseCoverageTest#publishedSuffixes")
        @DisplayName("publishes each label suffix exactly")
        void eachSuffixMatchesItsExpectation(String actual, String expected, int length) {
            assertThat(actual).isEqualTo(expected).hasSize(length);
        }

        /** Each whole message matches its independently written expectation exactly. */
        @ParameterizedTest(name = "[{index}] {1}")
        @MethodSource("com.carddemo.api.dto.AccountUpdateResponseCoverageTest#publishedMessages")
        @DisplayName("publishes each whole message exactly")
        void eachMessageMatchesItsExpectation(String actual, String expected, int length) {
            assertThat(actual).isEqualTo(expected).hasSize(length);
        }

        /** Every one of the twelve suffixes opens with a colon and a space. */
        @ParameterizedTest(name = "[{index}] opens with a colon and a space")
        @MethodSource("com.carddemo.api.dto.AccountUpdateResponseCoverageTest#publishedSuffixes")
        @DisplayName("opens every suffix with a colon and a space")
        void everySuffixOpensWithAColonAndSpace(String actual, String expected, int length) {
            assertThat(actual).startsWith(": ");
        }

        /** No whole message opens with a colon, because none is a fragment. */
        @ParameterizedTest(name = "[{index}] opens without a colon")
        @MethodSource("com.carddemo.api.dto.AccountUpdateResponseCoverageTest#publishedMessages")
        @DisplayName("opens no whole message with a colon")
        void noWholeMessageOpensWithAColon(String actual, String expected, int length) {
            assertThat(actual).doesNotStartWith(":");
        }

        /**
         * The three digit-count suffixes capitalise their article. This looks like a typo and is the
         * legacy text, so it must not be corrected.
         */
        @ParameterizedTest(name = "[{index}] capitalises its article")
        @MethodSource("com.carddemo.api.dto.AccountUpdateResponseCoverageTest#capitalisedArticleSuffixes")
        @DisplayName("capitalises the article in each digit-count suffix")
        void eachDigitCountSuffixCapitalisesItsArticle(String text) {
            assertThat(text).contains("must be A ").doesNotContain("must be a ");
        }

        /** The looks-good text really carries four dots, not three and not an ellipsis character. */
        @Test
        @DisplayName("gives the looks-good text four dots")
        void theLooksGoodTextCarriesFourDots() {
            assertThat(AccountUpdateResponse.MSG_LOOKS_GOOD_SO_FAR)
                    .isEqualTo("Looks Good.... so far")
                    .contains("Good....")
                    .doesNotContain("Good...\u0020")
                    .doesNotContain("\u2026");
            assertThat(
                            AccountUpdateResponse.MSG_LOOKS_GOOD_SO_FAR.chars()
                                    .filter(character -> character == '.')
                                    .count())
                    .isEqualTo(4);
        }

        /** The concurrency text really spells the pronoun as two words. */
        @Test
        @DisplayName("spells the concurrency text's pronoun as two words")
        void theConcurrencyTextSpellsItsPronounAsTwoWords() {
            assertThat(AccountUpdateResponse.MSG_RECORD_CHANGED_BEFORE_UPDATE)
                    .isEqualTo("Record changed by some one else. Please review")
                    .contains("some one")
                    .doesNotContain("someone")
                    .doesNotEndWith(".");
        }

        /** Six suffixes close with a full stop and six do not. */
        @Test
        @DisplayName("closes six suffixes with a full stop and six without")
        void theSuffixesSplitSixAndSixOnTheFullStop() {
            List<String> suffixes =
                    publishedSuffixes().map(arguments -> (String) arguments.get()[0]).toList();

            assertThat(suffixes.stream().filter(text -> text.endsWith(".")).toList()).hasSize(6);
            assertThat(suffixes.stream().filter(text -> !text.endsWith(".")).toList()).hasSize(6);
        }

        /** No whole message closes with a full stop. */
        @ParameterizedTest(name = "[{index}] closes without a full stop")
        @MethodSource("com.carddemo.api.dto.AccountUpdateResponseCoverageTest#publishedMessages")
        @DisplayName("closes no whole message with a full stop")
        void noWholeMessageClosesWithAFullStop(String actual, String expected, int length) {
            assertThat(actual).doesNotEndWith(".");
        }

        /** All twenty-seven published fragments are distinct. */
        @Test
        @DisplayName("keeps all twenty-seven published fragments distinct")
        void allPublishedFragmentsAreDistinct() {
            List<String> fragments =
                    Stream.concat(
                                    publishedSuffixes()
                                            .map(arguments -> (String) arguments.get()[0]),
                                    publishedMessages()
                                            .map(arguments -> (String) arguments.get()[0]))
                            .toList();

            assertThat(fragments).hasSize(27).doesNotHaveDuplicates();
        }

        /** Every published fragment fits the summary bound. */
        @Test
        @DisplayName("keeps every published fragment within the summary bound")
        void everyPublishedFragmentFitsTheSummaryBound() {
            Stream.concat(
                            publishedSuffixes().map(arguments -> (String) arguments.get()[0]),
                            publishedMessages().map(arguments -> (String) arguments.get()[0]))
                    .forEach(text -> assertThat(text).hasSizeLessThanOrEqualTo(78));
        }

        /** This type composes nothing: no suffix is joined to a label here. */
        @Test
        @DisplayName("composes no text")
        void noTextIsComposedByThisType() {
            List<String> fragments =
                    Stream.concat(
                                    publishedSuffixes()
                                            .map(arguments -> (String) arguments.get()[0]),
                                    publishedMessages()
                                            .map(arguments -> (String) arguments.get()[0]))
                            .toList();

            assertThat(fragments)
                    .doesNotContain(
                            "Credit Score" + AccountUpdateResponse.SUFFIX_FICO_OUT_OF_RANGE,
                            "State" + AccountUpdateResponse.SUFFIX_STATE_NOT_VALID);
            assertThat(
                            Arrays.stream(AccountUpdateResponse.class.getDeclaredMethods())
                                    .filter(method -> !method.isSynthetic())
                                    .map(Method::getName)
                                    .toList())
                    .noneMatch(
                            name ->
                                    name.toLowerCase(Locale.ROOT).contains("compose")
                                            || name.toLowerCase(Locale.ROOT).contains("format")
                                            || name.toLowerCase(Locale.ROOT).contains("concat"));
        }

        /**
         * The two lock-failure texts differ only in the record they name, and both are carried
         * because the legacy's two rewrite arms behave asymmetrically.
         */
        @Test
        @DisplayName("keeps the two lock-failure texts distinct")
        void theTwoLockFailureTextsAreDistinct() {
            assertThat(AccountUpdateResponse.MSG_COULD_NOT_HOLD_ACCOUNT_FOR_UPDATE)
                    .contains("account")
                    .isNotEqualTo(AccountUpdateResponse.MSG_COULD_NOT_HOLD_CUSTOMER_FOR_UPDATE);
            assertThat(AccountUpdateResponse.MSG_COULD_NOT_HOLD_CUSTOMER_FOR_UPDATE)
                    .contains("customer");
        }

        /** The informational text fits the narrower informational bound. */
        @Test
        @DisplayName("fits the informational text within the informational bound")
        void theInformationalTextFitsTheInformationalBound() {
            assertThat(AccountUpdateResponse.MSG_LOOKS_GOOD_SO_FAR).hasSizeLessThanOrEqualTo(45);
            assertThat(validator.validate(
                            carrying("infoMessage", AccountUpdateResponse.MSG_LOOKS_GOOD_SO_FAR)))
                    .isEmpty();
        }
    }

    /** The one collection the compact constructor normalises, and everything it leaves alone. */
    @Nested
    @DisplayName("Field-error normalisation")
    class FieldErrorNormalisation {

        /** An absent collection becomes the empty immutable list. */
        @Test
        @DisplayName("substitutes the empty list for an absent collection")
        void anAbsentCollectionBecomesTheEmptyList() {
            AccountUpdateResponse response = build(Map.of(), Map.of(), false, null, null);

            assertThat(response.fieldErrors()).isNotNull().isEmpty();
            assertThat(response.hasFieldErrors()).isFalse();
        }

        /** The collection is detached from the caller. */
        @Test
        @DisplayName("detaches the collection from the caller")
        void theCollectionIsDetachedFromTheCaller() {
            List<ErrorResponse.FieldError> mutable = new ArrayList<>();
            mutable.add(MISSING_STATE);

            AccountUpdateResponse response = build(Map.of(), Map.of(), true, mutable, null);
            mutable.add(INVALID_FICO);

            assertThat(response.fieldErrors()).containsExactly(MISSING_STATE);
        }

        /** The stored collection rejects modification. */
        @Test
        @DisplayName("rejects modification of the stored collection")
        void theStoredCollectionRejectsModification() {
            List<ErrorResponse.FieldError> stored =
                    build(Map.of(), Map.of(), true, List.of(MISSING_STATE), null).fieldErrors();

            assertThatThrownBy(() -> stored.add(INVALID_FICO))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        /** A null element is rejected, because an entry with no state would be meaningless. */
        @Test
        @DisplayName("rejects a null element")
        void aNullElementIsRejected() {
            List<ErrorResponse.FieldError> withNull = new ArrayList<>();
            withNull.add(null);

            assertThatThrownBy(() -> build(Map.of(), Map.of(), true, withNull, null))
                    .isInstanceOf(NullPointerException.class);
        }

        /** Entry order and length are preserved exactly. */
        @Test
        @DisplayName("preserves entry order and length exactly")
        void entryOrderAndLengthArePreservedExactly() {
            assertThat(
                            build(
                                            Map.of(),
                                            Map.of(),
                                            true,
                                            List.of(INVALID_FICO, MISSING_STATE),
                                            null)
                                    .fieldErrors())
                    .containsExactly(INVALID_FICO, MISSING_STATE);
        }

        /** Nothing else is normalised; every other component crosses byte for byte. */
        @Test
        @DisplayName("normalises nothing but the collection")
        void nothingElseIsNormalised() {
            String padded = "  spaced  ";
            AccountUpdateResponse response =
                    build(
                            Map.of("firstName", padded, "errorMessage", padded),
                            Map.of("currentBalance", new BigDecimal("-0.10")),
                            false,
                            null,
                            null);

            assertThat(response.firstName()).isEqualTo(padded);
            assertThat(response.errorMessage()).isEqualTo(padded);
            assertThat(response.currentBalance()).isEqualTo(new BigDecimal("-0.10"));
            assertThat(response.currentBalance().scale()).isEqualTo(2);
        }

        /**
         * No amount is scaled, rounded, negated or reformatted. An amount that already describes its
         * record field crosses byte for byte, trailing zero and sign included, and the two amounts a
         * rounding step would have quietly reshaped are refused instead.
         */
        @Test
        @DisplayName("performs no arithmetic on any amount")
        void noArithmeticIsPerformedOnAnyAmount() {
            BigDecimal trailingZero = new BigDecimal("2.00");
            BigDecimal negative = new BigDecimal("-0.01");

            assertThat(carryingAmount("creditLimit", trailingZero).creditLimit())
                    .isEqualTo(trailingZero);
            assertThat(carryingAmount("creditLimit", trailingZero).creditLimit().scale())
                    .as("stripping the trailing zero would be a reformat")
                    .isEqualTo(2);
            assertThat(carryingAmount("currentBalance", negative).currentBalance())
                    .isEqualTo(negative);
            assertThat(carryingAmount("currentBalance", negative).currentBalance().signum())
                    .isEqualTo(-1);
        }

        /**
         * An amount whose scale is not the record scale is refused rather than rounded to it. This is
         * the point of the verification: correcting the value here would mean taking a rounding
         * decision, and the estate specifies exactly one rounding policy, applied in exactly one
         * place, because no arithmetic statement in the legacy source carries a rounding clause.
         */
        @ParameterizedTest(name = "an amount of \"{0}\" is refused")
        @ValueSource(strings = {"1.5", "2.000", "0", "-99999", "1234567890.1234"})
        @DisplayName("refuses an amount whose scale is not the record scale")
        void anAmountOffTheRecordScaleIsRefused(String literal) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> carryingAmount("creditLimit", new BigDecimal(literal)))
                    .withMessageContaining("must carry scale 2");
        }

        /** The refusal names the component, so five identically shaped amounts stay tellable apart. */
        @ParameterizedTest(name = "{0} names itself when refused")
        @ValueSource(
                strings = {
                    "creditLimit",
                    "cashCreditLimit",
                    "currentBalance",
                    "currentCycleCredit",
                    "currentCycleDebit"
                })
        @DisplayName("names the refused component in the refusal")
        void theRefusalNamesTheComponent(String component) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> carryingAmount(component, new BigDecimal("1.5")))
                    .withMessageStartingWith(component + " must carry scale");
        }

        /**
         * An amount wider than the record field is refused. Ten integer digits is the field width, so
         * eleven does not describe the field the component stands for.
         */
        @Test
        @DisplayName("refuses an amount wider than the record field")
        void anAmountWiderThanTheRecordFieldIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(
                            () ->
                                    carryingAmount(
                                            "creditLimit", new BigDecimal("99999999999.99")))
                    .withMessageContaining("must fit 10 integer digits");
        }

        /** An amount at exactly the record width is carried, so the bound is inclusive. */
        @Test
        @DisplayName("carries an amount at exactly the record width")
        void anAmountAtExactlyTheRecordWidthIsCarried() {
            assertThat(carryingAmount("creditLimit", new BigDecimal("9999999999.99")).creditLimit())
                    .isEqualTo(new BigDecimal("9999999999.99"));
            assertThat(carryingAmount("creditLimit", new BigDecimal("-9999999999.99")).creditLimit())
                    .isEqualTo(new BigDecimal("-9999999999.99"));
        }

        /**
         * An absent amount is accepted, because the legacy screen leaves a monetary field blank on a
         * submission that never reached the record.
         */
        @Test
        @DisplayName("accepts an absent amount on every monetary component")
        void anAbsentAmountIsAccepted() {
            for (String component : MONETARY_COMPONENTS) {
                assertThat(carryingAmount(component, null))
                        .as("component %s must accept an absent amount", component)
                        .isNotNull();
            }
        }
    }

    /** The three independent facts a submission carries, and the shapes they make legal. */
    @Nested
    @DisplayName("Error independence")
    class ErrorIndependence {

        /** The explicit indicator is never derived from the summary message being present. */
        @Test
        @DisplayName("keeps the indicator independent of the summary message")
        void theIndicatorIsIndependentOfTheSummaryMessage() {
            AccountUpdateResponse messageWithoutError =
                    carrying("errorMessage", AccountUpdateResponse.MSG_LOOKS_GOOD_SO_FAR);

            assertThat(messageWithoutError.errorMessage()).isNotNull();
            assertThat(messageWithoutError.error()).isFalse();
        }

        /** The explicit indicator is never derived from the collection being non-empty. */
        @Test
        @DisplayName("keeps the indicator independent of the collection")
        void theIndicatorIsIndependentOfTheCollection() {
            AccountUpdateResponse fieldErrorsWithoutError =
                    build(Map.of(), Map.of(), false, List.of(MISSING_STATE), null);

            assertThat(fieldErrorsWithoutError.hasFieldErrors()).isTrue();
            assertThat(fieldErrorsWithoutError.error()).isFalse();
        }

        /**
         * A submission can fail with a summary text and no decorated field at all, which is how the
         * whole update-failure family of texts arises.
         */
        @Test
        @DisplayName("reports a failure with a summary text and no decorated field")
        void aFailureCanCarryASummaryTextWithNoDecoratedField() {
            AccountUpdateResponse response =
                    build(
                            Map.of("errorMessage", AccountUpdateResponse.MSG_UPDATE_OF_RECORD_FAILED),
                            Map.of(),
                            true,
                            null,
                            null);

            assertThat(response.error()).isTrue();
            assertThat(response.errorMessage())
                    .isEqualTo(AccountUpdateResponse.MSG_UPDATE_OF_RECORD_FAILED);
            assertThat(response.hasFieldErrors()).isFalse();
            assertThat(response.fieldErrors()).isEmpty();
        }

        /**
         * A first submission carries no field error at all, because the legacy decoration gate fires
         * only on re-entry. That state is fully constructible.
         */
        @Test
        @DisplayName("reports a first submission with no field error at all")
        void aFirstSubmissionCarriesNoFieldError() {
            AccountUpdateResponse response =
                    build(
                            Map.of(
                                    "errorMessage",
                                    AccountUpdateResponse.MSG_ACCOUNT_NUMBER_NOT_USABLE),
                            Map.of(),
                            true,
                            null,
                            NavigationContext.empty());

            assertThat(response.fieldErrors()).isEmpty();
            assertThat(response.error()).isTrue();
            assertThat(validator.validate(response)).isEmpty();
        }

        /**
         * One summary text accompanies many independently flagged fields, which is the shape the
         * first-error-wins gate produces.
         */
        @Test
        @DisplayName("carries one summary text alongside many flagged fields")
        void oneSummaryTextAccompaniesManyFlaggedFields() {
            List<ErrorResponse.FieldError> five = List.of(
                    MISSING_STATE,
                    INVALID_FICO,
                    new ErrorResponse.FieldError(
                            "zipCode",
                            "ACSZIPC",
                            ErrorResponse.FieldState.INVALID,
                            AccountUpdateResponse.MSG_INVALID_ZIP_FOR_STATE),
                    new ErrorResponse.FieldError(
                            "phone1AreaCode",
                            "ACSPH1A",
                            ErrorResponse.FieldState.MISSING,
                            AccountUpdateResponse.SUFFIX_AREA_CODE_REQUIRED),
                    new ErrorResponse.FieldError(
                            "phone1Prefix",
                            "ACSPH1B",
                            ErrorResponse.FieldState.INVALID,
                            AccountUpdateResponse.SUFFIX_PREFIX_NOT_3_DIGITS));

            AccountUpdateResponse response =
                    build(
                            Map.of("errorMessage", AccountUpdateResponse.MSG_INVALID_ZIP_FOR_STATE),
                            Map.of(),
                            true,
                            five,
                            null);

            assertThat(response.fieldErrors()).hasSize(5);
            assertThat(response.errorMessage())
                    .as("one text describes the first failing stage, not the set")
                    .isEqualTo(AccountUpdateResponse.MSG_INVALID_ZIP_FOR_STATE);
        }

        /**
         * Both field states are representable, so a client can tell a blank field from a badly
         * filled one.
         */
        @Test
        @DisplayName("distinguishes a blank field from a badly filled one")
        void bothFieldStatesAreRepresentable() {
            AccountUpdateResponse response =
                    build(Map.of(), Map.of(), true, List.of(MISSING_STATE, INVALID_FICO), null);

            assertThat(response.fieldErrors())
                    .extracting(ErrorResponse.FieldError::state)
                    .containsExactly(
                            ErrorResponse.FieldState.MISSING, ErrorResponse.FieldState.INVALID);
        }

        /** No concurrency component of any kind appears on this response. */
        @Test
        @DisplayName("carries no concurrency component")
        void noConcurrencyComponentIsCarried() {
            List<String> declared =
                    Arrays.stream(AccountUpdateResponse.class.getRecordComponents())
                            .map(RecordComponent::getName)
                            .map(name -> name.toLowerCase(Locale.ROOT))
                            .toList();

            assertThat(declared)
                    .noneMatch(
                            name ->
                                    name.contains("version")
                                            || name.contains("etag")
                                            || name.contains("timestamp")
                                            || name.contains("lock")
                                            || name.contains("beforeimage")
                                            || name.contains("afterimage"));
        }

        /** The collection is not cascaded, so an entry is validated directly. */
        @Test
        @DisplayName("does not cascade validation into the collection")
        void theCollectionIsNotCascaded() throws NoSuchFieldException {
            assertThat(AccountUpdateResponse.class.getDeclaredField("fieldErrors").getAnnotations())
                    .noneMatch(
                            annotation ->
                                    "jakarta.validation.Valid"
                                            .equals(annotation.annotationType().getName()));
        }
    }

    /** The exact characters each amount takes on the wire. */
    @Nested
    @DisplayName("Decimal wire form")
    class DecimalWireForm {

        /** Each amount is emitted with the exact scale it was given. */
        @ParameterizedTest(name = "{0} emits {1}")
        @CsvSource({
            "creditLimit,15000.00",
            "cashCreditLimit,3000.00",
            "currentBalance,-2417.63",
            "currentCycleCredit,891.25",
            "currentCycleDebit,0.00"
        })
        @DisplayName("emits each amount at the scale it was given")
        void eachAmountIsEmittedAtItsGivenScale(String component, String emitted)
                throws JsonProcessingException {
            assertThat(JsonContractSupport.renderedValueToken(payloadOf(populated(null)), component))
                    .isEqualTo(emitted);
        }

        /**
         * A two-decimal zero keeps both decimal places, which a re-parsed tree would have collapsed
         * and which is why the emitted characters are read rather than a parsed node.
         */
        @Test
        @DisplayName("keeps both decimal places on a two-decimal zero")
        void aTwoDecimalZeroKeepsBothPlaces() throws JsonProcessingException {
            String payload = payloadOf(carryingAmount("currentCycleDebit", CURRENT_CYCLE_DEBIT));

            assertThat(JsonContractSupport.renderedValueToken(payload, "currentCycleDebit"))
                    .isEqualTo("0.00")
                    .isNotEqualTo("0.0")
                    .isNotEqualTo("0");
        }

        /** A negative amount keeps its sign. */
        @Test
        @DisplayName("keeps the sign on a negative amount")
        void aNegativeAmountKeepsItsSign() throws JsonProcessingException {
            assertThat(
                            JsonContractSupport.renderedValueToken(
                                    payloadOf(carryingAmount("currentBalance", CURRENT_BALANCE)),
                                    "currentBalance"))
                    .isEqualTo("-2417.63")
                    .startsWith("-");
        }

        /** Each amount is emitted as a bare number and never quoted as text. */
        @ParameterizedTest(name = "{0} is emitted unquoted")
        @ValueSource(
                strings = {
                    "creditLimit",
                    "cashCreditLimit",
                    "currentBalance",
                    "currentCycleCredit",
                    "currentCycleDebit"
                })
        @DisplayName("emits each amount unquoted")
        void eachAmountIsEmittedUnquoted(String component) throws JsonProcessingException {
            assertThat(payloadOf(populated(null)))
                    .doesNotContain("\"" + component + "\":\"");
        }

        /**
         * A ten-integer-digit amount is emitted plainly, never in scientific notation. The exponent
         * check is scoped to the extracted number so that a letter inside a member name cannot
         * satisfy it.
         */
        @Test
        @DisplayName("emits a ten-digit amount plainly")
        void aTenDigitAmountIsEmittedPlainly() throws JsonProcessingException {
            BigDecimal wide = new BigDecimal("9999999999.99");

            String token =
                    JsonContractSupport.renderedValueToken(
                            payloadOf(carryingAmount("creditLimit", wide)), "creditLimit");

            assertThat(token)
                    .isEqualTo("9999999999.99")
                    .doesNotContain("E")
                    .doesNotContain("e")
                    .doesNotContain("+");
        }

        /** An amount round trips with its scale intact. */
        @ParameterizedTest(name = "{0} round trips")
        @ValueSource(
                strings = {
                    "creditLimit",
                    "cashCreditLimit",
                    "currentBalance",
                    "currentCycleCredit",
                    "currentCycleDebit"
                })
        @DisplayName("round trips each amount with its scale intact")
        void eachAmountRoundTripsWithItsScaleIntact(String component)
                throws JsonProcessingException {
            ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();
            AccountUpdateResponse original = carryingAmount(component, new BigDecimal("1.20"));

            AccountUpdateResponse restored =
                    mapper.readValue(
                            mapper.writeValueAsString(original), AccountUpdateResponse.class);

            assertThat(restored).isEqualTo(original);
            assertThat(restored)
                    .extracting(component)
                    .isEqualTo(new BigDecimal("1.20"));
        }

        /** An absent amount is omitted rather than emitted as a zero. */
        @Test
        @DisplayName("omits an absent amount")
        void anAbsentAmountIsOmitted() throws JsonProcessingException {
            String payload = payloadOf(empty());

            for (String component : MONETARY_COMPONENTS) {
                assertThat(payload)
                        .as("absent amount %s must be omitted", component)
                        .doesNotContain("\"" + component + "\"");
            }
        }
    }

    /** The JSON form each component takes under the module's declared settings. */
    @Nested
    @DisplayName("Wire shape")
    class WireShape {

        /**
         * Every populated component appears under the name the record declares, all fifty-six.
         *
         * <p>The assertion is exact in both directions, so a component that stopped crossing would
         * fail it and so would a component that appeared without being declared - which is what makes
         * it the guard against an optimistic-lock or entity-tag value reappearing on the wire.</p>
         */
        @Test
        @DisplayName("emits every populated component under its declared name, and nothing besides")
        void everyPopulatedComponentAppearsUnderItsDeclaredName() throws JsonProcessingException {
            JsonNode tree =
                    JsonContractSupport.declaredSettingsMapper()
                            .readTree(
                                    payloadOf(
                                            populated(JsonContractSupport.populatedNavigation())));

            assertThat(tree.fieldNames())
                    .toIterable()
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_COMPONENTS)
                    .hasSize(56);
        }

        /** The collection is always present and is emitted as an empty array when empty. */
        @Test
        @DisplayName("always emits the collection, as an empty array when empty")
        void theCollectionIsAlwaysEmitted() throws JsonProcessingException {
            assertThat(payloadOf(empty())).contains("\"fieldErrors\":[]");
        }

        /** An empty response carries exactly the two components that cannot be absent. */
        @Test
        @DisplayName("emits only the collection and the indicator when nothing is populated")
        void anEmptyResponseCarriesOnlyTheCollectionAndIndicator()
                throws JsonProcessingException {
            JsonNode tree =
                    JsonContractSupport.declaredSettingsMapper().readTree(payloadOf(empty()));

            assertThat(tree.fieldNames())
                    .toIterable()
                    .containsExactlyInAnyOrder("fieldErrors", "error");
        }

        /** An absent component is omitted rather than emitted as a null. */
        @Test
        @DisplayName("omits an absent component")
        void anAbsentComponentIsOmitted() throws JsonProcessingException {
            String payload = payloadOf(carrying("accountId", ACCOUNT_ID));

            assertThat(payload).contains("\"accountId\":\"" + ACCOUNT_ID + "\"");
            for (String component : EXPECTED_COMPONENTS) {
                if (!"accountId".equals(component)
                        && !"fieldErrors".equals(component)
                        && !"error".equals(component)) {
                    assertThat(payload)
                            .as("absent component %s must be omitted", component)
                            .doesNotContain("\"" + component + "\"");
                }
            }
        }

        /** Each split date part is emitted separately and is never assembled. */
        @Test
        @DisplayName("emits each split date part separately")
        void eachSplitDatePartIsEmittedSeparately() throws JsonProcessingException {
            String payload = payloadOf(populated(null));

            assertThat(payload)
                    .contains("\"openYear\":\"" + OPEN_YEAR + "\"")
                    .contains("\"openMonth\":\"" + OPEN_MONTH + "\"")
                    .contains("\"openDay\":\"" + OPEN_DAY + "\"")
                    .contains("\"dateOfBirthYear\":\"" + DATE_OF_BIRTH_YEAR + "\"")
                    .doesNotContain("\"openDate\"")
                    .doesNotContain("\"dateOfBirth\"");
        }

        /** Each split social-security part is emitted separately and is never assembled. */
        @Test
        @DisplayName("emits each split social-security part separately")
        void eachSplitSocialSecurityPartIsEmittedSeparately() throws JsonProcessingException {
            String payload = payloadOf(populated(null));

            assertThat(payload)
                    .contains("\"ssnPart1\":\"" + SSN_PART_1 + "\"")
                    .contains("\"ssnPart2\":\"" + SSN_PART_2 + "\"")
                    .contains("\"ssnPart3\":\"" + SSN_PART_3 + "\"")
                    .doesNotContain("\"ssn\"");
        }

        /** Each split telephone part is emitted separately and is never assembled. */
        @Test
        @DisplayName("emits each split telephone part separately")
        void eachSplitTelephonePartIsEmittedSeparately() throws JsonProcessingException {
            String payload = payloadOf(populated(null));

            assertThat(payload)
                    .contains("\"phone1AreaCode\":\"" + PHONE_1_AREA_CODE + "\"")
                    .contains("\"phone1LineNumber\":\"" + PHONE_1_LINE_NUMBER + "\"")
                    .contains("\"phone2LineNumber\":\"" + PHONE_2_LINE_NUMBER + "\"")
                    .doesNotContain("\"phoneNumber1\"")
                    .doesNotContain("(" + PHONE_1_AREA_CODE + ")");
        }

        /** The twenty-one split sub-fields are all carried individually. */
        @Test
        @DisplayName("carries all twenty-one split sub-fields individually")
        void allSplitSubFieldsAreCarriedIndividually() {
            List<String> split = List.of(
                    "openYear", "openMonth", "openDay",
                    "expiryYear", "expiryMonth", "expiryDay",
                    "reissueYear", "reissueMonth", "reissueDay",
                    "dateOfBirthYear", "dateOfBirthMonth", "dateOfBirthDay",
                    "ssnPart1", "ssnPart2", "ssnPart3",
                    "phone1AreaCode", "phone1Prefix", "phone1LineNumber",
                    "phone2AreaCode", "phone2Prefix", "phone2LineNumber");

            assertThat(split).hasSize(21);
            assertThat(EXPECTED_COMPONENTS).containsAll(split);
        }

        /** The three function-key legend families are not carried in any form. */
        @Test
        @DisplayName("carries no function-key legend")
        void noFunctionKeyLegendIsCarried() {
            List<String> declared =
                    Arrays.stream(AccountUpdateResponse.class.getRecordComponents())
                            .map(RecordComponent::getName)
                            .map(name -> name.toLowerCase(Locale.ROOT))
                            .toList();

            assertThat(declared)
                    .noneMatch(
                            name ->
                                    name.contains("legend")
                                            || name.contains("pfkey")
                                            || name.contains("functionkey")
                                            || name.contains("keylabel"));
        }

        /** No 3270 sub-item of any kind is carried. */
        @Test
        @DisplayName("carries no terminal sub-item")
        void noTerminalSubItemIsCarried() {
            List<String> declared =
                    Arrays.stream(AccountUpdateResponse.class.getRecordComponents())
                            .map(RecordComponent::getName)
                            .map(name -> name.toLowerCase(Locale.ROOT))
                            .toList();

            assertThat(declared)
                    .noneMatch(
                            name ->
                                    name.contains("attribute")
                                            || name.contains("colour")
                                            || name.contains("color")
                                            || name.contains("highlight")
                                            || name.contains("cursor")
                                            || name.contains("filler")
                                            || name.endsWith("length")
                                            || name.endsWith("flag"));
        }

        /** The presence test is not emitted, because it is a method and not a component. */
        @Test
        @DisplayName("emits no presence test")
        void thePresenceTestIsNotEmitted() throws JsonProcessingException {
            assertThat(payloadOf(populated(null))).doesNotContain("\"hasFieldErrors\"");
        }

        /** A space-significant value survives serialisation untouched. */
        @Test
        @DisplayName("preserves a space-significant value")
        void aSpaceSignificantValueSurvives() throws JsonProcessingException {
            assertThat(payloadOf(carrying("firstName", "Ada  ")))
                    .contains("\"firstName\":\"Ada  \"");
        }

        /** An unknown property is tolerated on read under the module's declared settings. */
        @Test
        @DisplayName("tolerates an unknown property on read")
        void anUnknownPropertyIsToleratedOnRead() {
            ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();
            String payload = "{\"accountId\":\"00000000011\",\"pfKey03\":\"PF03=Back\"}";

            assertThatNoException()
                    .isThrownBy(() -> mapper.readValue(payload, AccountUpdateResponse.class));
        }

        /** A round trip preserves every component. */
        @Test
        @DisplayName("preserves every component across a round trip")
        void aRoundTripPreservesEveryComponent() throws JsonProcessingException {
            ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();
            AccountUpdateResponse original =
                    populated(JsonContractSupport.populatedNavigation());

            AccountUpdateResponse restored =
                    mapper.readValue(
                            mapper.writeValueAsString(original), AccountUpdateResponse.class);

            assertThat(restored).isEqualTo(original);
        }
    }

    /** Bean Validation behaviour at, inside and outside each declared bound. */
    @Nested
    @DisplayName("Validation bounds")
    class ValidationBounds {

        /** A fully populated response reports no violation. */
        @Test
        @DisplayName("reports no violation on a populated response")
        void aPopulatedResponseReportsNoViolation() {
            assertThat(validator.validate(populated(JsonContractSupport.populatedNavigation())))
                    .isEmpty();
        }

        /** An empty response reports no violation, because nothing is required. */
        @Test
        @DisplayName("reports no violation on an empty response")
        void anEmptyResponseReportsNoViolation() {
            assertThat(validator.validate(empty())).isEmpty();
        }

        /** A value one character past a bound is reported against that component. */
        @ParameterizedTest(name = "{0} over {1} is reported")
        @CsvSource({
            "transactionName,4",
            "title01,40",
            "currentDate,8",
            "programName,8",
            "title02,40",
            "currentTime,8",
            "accountId,11",
            "accountStatus,1",
            "openYear,4",
            "openMonth,2",
            "openDay,2",
            "expiryYear,4",
            "expiryMonth,2",
            "expiryDay,2",
            "reissueYear,4",
            "reissueMonth,2",
            "reissueDay,2",
            "accountGroupId,10",
            "customerId,9",
            "ssnPart1,3",
            "ssnPart2,2",
            "ssnPart3,4",
            "dateOfBirthYear,4",
            "dateOfBirthMonth,2",
            "dateOfBirthDay,2",
            "ficoScore,3",
            "firstName,25",
            "lastName,25",
            "addressLine1,50",
            "stateCode,2",
            "zipCode,5",
            "city,50",
            "countryCode,3",
            "phone1AreaCode,3",
            "phone1Prefix,3",
            "phone1LineNumber,4",
            "governmentIssuedId,20",
            "phone2AreaCode,3",
            "phone2Prefix,3",
            "phone2LineNumber,4",
            "eftAccountId,10",
            "primaryCardHolderIndicator,1",
            "infoMessage,45",
            "errorMessage,78",
            "focusScreenFieldId,7"
        })
        @DisplayName("reports an over-long value")
        void anOverLongValueIsReported(String component, int width) {
            Set<ConstraintViolation<AccountUpdateResponse>> violations =
                    validator.validate(carrying(component, "A".repeat(width + 1)));

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString(component);
        }

        /** A value exactly at a bound is accepted. */
        @ParameterizedTest(name = "{0} at {1} is accepted")
        @CsvSource({
            "accountId,11",
            "accountStatus,1",
            "openYear,4",
            "accountGroupId,10",
            "customerId,9",
            "ssnPart1,3",
            "ssnPart2,2",
            "ssnPart3,4",
            "ficoScore,3",
            "firstName,25",
            "lastName,25",
            "addressLine1,50",
            "stateCode,2",
            "zipCode,5",
            "city,50",
            "countryCode,3",
            "phone1AreaCode,3",
            "phone1LineNumber,4",
            "governmentIssuedId,20",
            "eftAccountId,10",
            "primaryCardHolderIndicator,1",
            "infoMessage,45",
            "errorMessage,78",
            "focusScreenFieldId,7"
        })
        @DisplayName("accepts a value at a bound")
        void anAtWidthValueIsAccepted(String component, int width) {
            assertThat(validator.validate(carrying(component, "A".repeat(width)))).isEmpty();
        }

        /**
         * The two message bounds are the map widths, not the narrower working fields the program
         * stages them through and not the sign-on program's wider field.
         */
        @Test
        @DisplayName("bounds the two messages at the map widths, not the staging widths")
        void theMessageBoundsAreTheMapWidths() throws NoSuchFieldException {
            int summaryBound =
                    AccountUpdateResponse.class
                            .getDeclaredField("errorMessage")
                            .getAnnotation(Size.class)
                            .max();
            int informationalBound =
                    AccountUpdateResponse.class
                            .getDeclaredField("infoMessage")
                            .getAnnotation(Size.class)
                            .max();

            assertThat(summaryBound).isEqualTo(78).isNotEqualTo(75).isNotEqualTo(80);
            assertThat(informationalBound).isEqualTo(45).isNotEqualTo(40);
            assertThat(summaryBound).isGreaterThan(informationalBound);
        }

        /** No component is mandatory, because every one can legitimately arrive blank. */
        @Test
        @DisplayName("marks no component mandatory")
        void noComponentIsMandatory() {
            for (String component : BOUNDED_COMPONENTS) {
                assertThat(validator.validate(carrying(component, null)))
                        .as("component %s must tolerate absence", component)
                        .isEmpty();
                assertThat(validator.validate(carrying(component, "")))
                        .as("component %s must tolerate a blank", component)
                        .isEmpty();
            }
        }

        /** No presence, pattern, digit-count or numeric-bound constraint is enforced. */
        @Test
        @DisplayName("enforces no presence, pattern or numeric bound")
        void noPresencePatternOrNumericBoundIsEnforced() {
            assertThat(validator.validate(carrying("ficoScore", "001")))
                    .as("the credit-score range is a service-side cascade rule, not a constraint")
                    .isEmpty();
            assertThat(validator.validate(carrying("accountStatus", "?"))).isEmpty();
            assertThat(validator.validate(carrying("openMonth", "99"))).isEmpty();
            assertThat(validator.validate(carrying("stateCode", "ZZ"))).isEmpty();
            assertThat(validator.validate(carryingAmount("creditLimit", new BigDecimal("-99999.00"))))
                    .as("a negative credit limit is a service-side rule, not a boundary constraint")
                    .isEmpty();
        }

        /** The route is not measured, because it is a service-owned identifier. */
        @Test
        @DisplayName("does not measure the route")
        void theRouteIsNotMeasured() {
            assertThat(validator.validate(carrying("nextRoute", "/".repeat(4096)))).isEmpty();
        }

        /**
         * No amount is measured, because a length constraint does not apply to a decimal. The widest
         * amount the type will carry at all still reports no violation, which is what proves the
         * absence of a boundary constraint; the wider values the type refuses are refused by the
         * compact constructor rather than by the validator, and are asserted where that refusal is
         * documented.
         */
        @Test
        @DisplayName("does not measure any amount")
        void noAmountIsMeasured() {
            assertThat(
                            validator.validate(
                                    carryingAmount(
                                            "creditLimit", new BigDecimal("-9999999999.99"))))
                    .isEmpty();
        }

        /** Every published fragment passes the summary bound. */
        @ParameterizedTest(name = "[{index}] passes the summary bound")
        @MethodSource("com.carddemo.api.dto.AccountUpdateResponseCoverageTest#publishedMessages")
        @DisplayName("accepts every published message in the summary slot")
        void everyPublishedMessagePassesTheSummaryBound(
                String actual, String expected, int length) {
            assertThat(validator.validate(carrying("errorMessage", actual))).isEmpty();
        }
    }

    /** The whitelist rendering, which names seven things and withholds fifty-one. */
    @Nested
    @DisplayName("Diagnostic redaction")
    class DiagnosticRedaction {

        /** The rendering names the record and only the seven permitted keys. */
        @Test
        @DisplayName("names only the seven permitted keys")
        void onlyTheSevenPermittedKeysAreNamed() {
            String rendered = populated(null).toString();

            assertThat(rendered).startsWith("AccountUpdateResponse[").endsWith("]");
            for (String key : NAMED_IN_RENDERING) {
                assertThat(rendered).as("key %s must be named", key).contains(key + "=");
            }
        }

        /**
         * Fifty component names never appear at all. This rendering is a whitelist, so unlike every
         * other redacting type in this package it does not even name what it withholds.
         */
        @Test
        @DisplayName("names none of the fifty withheld components")
        void noneOfTheWithheldComponentsIsNamed() {
            String rendered = populated(null).toString();

            assertThat(UNNAMED_IN_RENDERING).hasSize(50);
            for (String component : UNNAMED_IN_RENDERING) {
                assertThat(rendered)
                        .as("component %s must not be named at all", component)
                        .doesNotContain(component + "=");
            }
        }

        /** No distinctive value reaches the rendering, with or without the conversation state. */
        @ParameterizedTest(name = "the {0} is withheld")
        @MethodSource("com.carddemo.api.dto.AccountUpdateResponseCoverageTest#distinctiveValues")
        @DisplayName("withholds every distinctive value")
        void everyDistinctiveValueIsWithheld(String description, String value) {
            assertThat(populated(null).toString())
                    .as("the %s must not reach a diagnostic", description)
                    .doesNotContain(value);
            assertThat(populated(JsonContractSupport.populatedNavigation()).toString())
                    .as("the %s must not reach a diagnostic", description)
                    .doesNotContain(value);
        }

        /** One collective placeholder stands for every withheld value. */
        @Test
        @DisplayName("substitutes one collective placeholder for every value")
        void oneCollectivePlaceholderStandsForEveryValue() {
            String rendered = populated(null).toString();

            assertThat(rendered).contains("values=" + PLACEHOLDER);
            assertThat(countOccurrences(rendered, PLACEHOLDER))
                    .as("exactly one placeholder, because the values are collective")
                    .isEqualTo(1);
        }

        /**
         * A count is rendered in place of the entries, so a diagnostic's size cannot grow with the
         * number of mistakes an operator made.
         */
        @Test
        @DisplayName("renders a count in place of the field-error entries")
        void aCountIsRenderedInPlaceOfTheEntries() {
            String twoErrors = populated(null).toString();
            String noErrors = build(populatedText(), populatedMonetary(), true, null, null)
                    .toString();

            assertThat(twoErrors).contains("fieldErrorCount=2").doesNotContain("fieldErrors=");
            assertThat(noErrors).contains("fieldErrorCount=0");
            assertThat(twoErrors)
                    .as("the entries themselves never appear")
                    .doesNotContain("ACSSTTE")
                    .doesNotContain("MISSING")
                    .doesNotContain(AccountUpdateResponse.SUFFIX_FICO_OUT_OF_RANGE);
        }

        /**
         * A rendering does not grow with the number of field errors beyond the count's own digits,
         * which is the property the count exists to guarantee.
         */
        @Test
        @DisplayName("does not grow with the number of field errors")
        void theRenderingDoesNotGrowWithTheNumberOfFieldErrors() {
            List<ErrorResponse.FieldError> many = new ArrayList<>();
            for (int index = 0; index < 9; index++) {
                many.add(
                        new ErrorResponse.FieldError(
                                "component" + index,
                                "SCRFLD" + index,
                                ErrorResponse.FieldState.INVALID,
                                "a message long enough to matter if it were rendered"));
            }

            String twoErrors = populated(null).toString();
            String nineErrors =
                    build(populatedText(), populatedMonetary(), true, many, null).toString();

            assertThat(nineErrors).contains("fieldErrorCount=9");
            assertThat(nineErrors.length())
                    .as("only the count's own digits may differ")
                    .isEqualTo(twoErrors.length());
        }

        /** The summary line is retained, because it names a box and not what was typed into it. */
        @Test
        @DisplayName("retains the summary line")
        void theSummaryLineIsRetained() {
            assertThat(populated(null).toString())
                    .contains(
                            "errorMessage=" + AccountUpdateResponse.MSG_ACCOUNT_NUMBER_NOT_USABLE);
        }

        /** The informational line is withheld even though it too is a catalogue text. */
        @Test
        @DisplayName("withholds the informational line")
        void theInformationalLineIsWithheld() {
            assertThat(populated(null).toString())
                    .doesNotContain(AccountUpdateResponse.MSG_LOOKS_GOOD_SO_FAR)
                    .doesNotContain("infoMessage");
        }

        /** The four control values that carry no subject data are retained. */
        @Test
        @DisplayName("retains the control values")
        void theControlValuesAreRetained() {
            assertThat(populated(null).toString())
                    .contains("error=true")
                    .contains("focusScreenFieldId=" + FOCUS_SCREEN_FIELD_ID)
                    .contains("nextRoute=" + NEXT_ROUTE);
        }

        /**
         * The conversation state is delegated to its own renderer, which applies the same protection
         * to its own identifying members.
         */
        @Test
        @DisplayName("delegates the conversation state to its own renderer")
        void theConversationStateIsDelegated() {
            String rendered = populated(JsonContractSupport.populatedNavigation()).toString();

            assertThat(rendered).contains("navigationContext=NavigationContext[");
            assertThat(rendered)
                    .doesNotContain(JsonContractSupport.NAV_CUSTOMER_ID)
                    .doesNotContain(JsonContractSupport.NAV_FIRST_NAME)
                    .doesNotContain(JsonContractSupport.NAV_MIDDLE_NAME)
                    .doesNotContain(JsonContractSupport.NAV_LAST_NAME)
                    .doesNotContain(JsonContractSupport.NAV_ACCOUNT_ID)
                    .doesNotContain(JsonContractSupport.NAV_CARD_NUMBER);
        }

        /** An absent conversation state renders as an absent value rather than failing. */
        @Test
        @DisplayName("renders an absent conversation state without failing")
        void anAbsentConversationStateRendersWithoutFailing() {
            assertThatNoException().isThrownBy(() -> empty().toString());
            assertThat(empty().toString()).contains("navigationContext=null");
        }

        /** The payload still carries every value in full; only the rendering withholds them. */
        @Test
        @DisplayName("still carries every value in the payload")
        void thePayloadStillCarriesEveryValue() throws JsonProcessingException {
            String payload = payloadOf(populated(JsonContractSupport.populatedNavigation()));

            assertThat(payload)
                    .contains(FIRST_NAME)
                    .contains(MIDDLE_NAME)
                    .contains(LAST_NAME)
                    .contains(GOVERNMENT_ISSUED_ID)
                    .contains(EFT_ACCOUNT_ID)
                    .contains(SSN_PART_3)
                    .contains(AccountUpdateResponse.MSG_LOOKS_GOOD_SO_FAR);
        }

        /** Every accessor still returns the value in full; only the rendering withholds it. */
        @Test
        @DisplayName("still returns every value from its accessor")
        void everyAccessorStillReturnsItsValue() {
            AccountUpdateResponse response = populated(null);

            assertThat(response.firstName()).isEqualTo(FIRST_NAME);
            assertThat(response.middleName()).isEqualTo(MIDDLE_NAME);
            assertThat(response.governmentIssuedId()).isEqualTo(GOVERNMENT_ISSUED_ID);
            assertThat(response.ssnPart3()).isEqualTo(SSN_PART_3);
            assertThat(response.creditLimit()).isEqualTo(CREDIT_LIMIT);
            assertThat(response.infoMessage())
                    .isEqualTo(AccountUpdateResponse.MSG_LOOKS_GOOD_SO_FAR);
        }
    }

    /** Equality, hashing, absence tolerance and accessor fidelity. */
    @Nested
    @DisplayName("Value semantics")
    class ValueSemantics {

        /** Two responses carrying equal components are equal. */
        @Test
        @DisplayName("treats equal components as equal values")
        void twoResponsesWithEqualComponentsAreEqual() {
            assertThat(populated(null)).isEqualTo(populated(null));
            assertThat(populated(JsonContractSupport.populatedNavigation()))
                    .isEqualTo(populated(JsonContractSupport.populatedNavigation()));
        }

        /** Hashing is stable across equal instances. */
        @Test
        @DisplayName("hashes equal values alike")
        void hashCodeIsStableAcrossEqualInstances() {
            assertThat(populated(null)).hasSameHashCodeAs(populated(null));
        }

        /**
         * Comparison covers every component, including the ones the rendering withholds: protection
         * belongs on the rendering path alone, because an in-memory comparison discloses nothing.
         */
        @Test
        @DisplayName("compares every component, including the withheld ones")
        void comparisonCoversEveryComponentIncludingTheWithheldOnes() {
            Map<String, String> altered = populatedText();
            altered.put("firstName", "Different");

            assertThat(build(altered, populatedMonetary(), true, List.of(MISSING_STATE, INVALID_FICO), null))
                    .isNotEqualTo(populated(null));
        }

        /**
         * A scale shift cannot be constructed at all, so it can never be the difference between two
         * responses. Equality still compares the amount by value and scale - that is what a record's
         * generated equality does - but the only scale the type admits is the record scale, which
         * removes the whole class of comparison surprise this test used to guard.
         */
        @Test
        @DisplayName("cannot construct a scale-shifted amount to compare")
        void aScaleShiftedAmountCannotBeConstructed() {
            assertThat(carryingAmount("creditLimit", new BigDecimal("100.00")))
                    .isEqualTo(carryingAmount("creditLimit", new BigDecimal("100.00")));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> carryingAmount("creditLimit", new BigDecimal("100.0")));
        }

        /** Two amounts at the record scale that differ in value produce different responses. */
        @Test
        @DisplayName("observes a difference in amount value")
        void aDifferenceInAmountValueIsObserved() {
            assertThat(carryingAmount("creditLimit", new BigDecimal("100.00")))
                    .isNotEqualTo(carryingAmount("creditLimit", new BigDecimal("100.01")));
        }

        /** A difference in the error indicator is observed. */
        @Test
        @DisplayName("observes a difference in the error indicator")
        void aDifferenceInTheErrorIndicatorIsObserved() {
            assertThat(build(Map.of(), Map.of(), true, null, null))
                    .isNotEqualTo(build(Map.of(), Map.of(), false, null, null));
        }

        /** An absent collection and an explicitly empty one produce equal responses. */
        @Test
        @DisplayName("equates an absent collection with an explicitly empty one")
        void anAbsentCollectionEqualsAnExplicitlyEmptyOne() {
            assertThat(build(Map.of(), Map.of(), false, null, null))
                    .isEqualTo(
                            build(Map.of(), Map.of(), false, Collections.emptyList(), null));
        }

        /** Every component tolerates an absent value. */
        @Test
        @DisplayName("tolerates an absent value in every component")
        void everyComponentToleratesAnAbsentValue() {
            AccountUpdateResponse response = empty();

            assertThat(response.accountId()).isNull();
            assertThat(response.middleName()).isNull();
            assertThat(response.addressLine2()).isNull();
            assertThat(response.creditLimit()).isNull();
            assertThat(response.currentCycleDebit()).isNull();
            assertThat(response.infoMessage()).isNull();
            assertThat(response.errorMessage()).isNull();
            assertThat(response.focusScreenFieldId()).isNull();
            assertThat(response.nextRoute()).isNull();
            assertThat(response.navigationContext()).isNull();
            assertThat(response.error()).isFalse();
            assertThat(response.fieldErrors()).isEmpty();
        }

        /** Every accessor returns exactly what was supplied. */
        @Test
        @DisplayName("returns from every accessor exactly what was supplied")
        void everyAccessorReturnsWhatWasSupplied() {
            AccountUpdateResponse response =
                    populated(JsonContractSupport.populatedNavigation());

            assertThat(response.transactionName()).isEqualTo(TRANSACTION_NAME);
            assertThat(response.title01()).isEqualTo(TITLE_01);
            assertThat(response.currentDate()).isEqualTo(CURRENT_DATE);
            assertThat(response.programName()).isEqualTo(PROGRAM_NAME);
            assertThat(response.title02()).isEqualTo(TITLE_02);
            assertThat(response.currentTime()).isEqualTo(CURRENT_TIME);
            assertThat(response.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(response.accountStatus()).isEqualTo(ACCOUNT_STATUS);
            assertThat(response.openYear()).isEqualTo(OPEN_YEAR);
            assertThat(response.openMonth()).isEqualTo(OPEN_MONTH);
            assertThat(response.openDay()).isEqualTo(OPEN_DAY);
            assertThat(response.creditLimit()).isEqualTo(CREDIT_LIMIT);
            assertThat(response.expiryYear()).isEqualTo(EXPIRY_YEAR);
            assertThat(response.cashCreditLimit()).isEqualTo(CASH_CREDIT_LIMIT);
            assertThat(response.reissueYear()).isEqualTo(REISSUE_YEAR);
            assertThat(response.currentBalance()).isEqualTo(CURRENT_BALANCE);
            assertThat(response.currentCycleCredit()).isEqualTo(CURRENT_CYCLE_CREDIT);
            assertThat(response.accountGroupId()).isEqualTo(ACCOUNT_GROUP_ID);
            assertThat(response.currentCycleDebit()).isEqualTo(CURRENT_CYCLE_DEBIT);
            assertThat(response.customerId()).isEqualTo(CUSTOMER_ID);
            assertThat(response.ssnPart1()).isEqualTo(SSN_PART_1);
            assertThat(response.ssnPart2()).isEqualTo(SSN_PART_2);
            assertThat(response.ssnPart3()).isEqualTo(SSN_PART_3);
            assertThat(response.dateOfBirthYear()).isEqualTo(DATE_OF_BIRTH_YEAR);
            assertThat(response.ficoScore()).isEqualTo(FICO_SCORE);
            assertThat(response.firstName()).isEqualTo(FIRST_NAME);
            assertThat(response.middleName()).isEqualTo(MIDDLE_NAME);
            assertThat(response.lastName()).isEqualTo(LAST_NAME);
            assertThat(response.addressLine1()).isEqualTo(ADDRESS_LINE_1);
            assertThat(response.stateCode()).isEqualTo(STATE_CODE);
            assertThat(response.addressLine2()).isEqualTo(ADDRESS_LINE_2);
            assertThat(response.zipCode()).isEqualTo(ZIP_CODE);
            assertThat(response.city()).isEqualTo(CITY);
            assertThat(response.countryCode()).isEqualTo(COUNTRY_CODE);
            assertThat(response.phone1AreaCode()).isEqualTo(PHONE_1_AREA_CODE);
            assertThat(response.phone1Prefix()).isEqualTo(PHONE_1_PREFIX);
            assertThat(response.phone1LineNumber()).isEqualTo(PHONE_1_LINE_NUMBER);
            assertThat(response.governmentIssuedId()).isEqualTo(GOVERNMENT_ISSUED_ID);
            assertThat(response.phone2AreaCode()).isEqualTo(PHONE_2_AREA_CODE);
            assertThat(response.phone2LineNumber()).isEqualTo(PHONE_2_LINE_NUMBER);
            assertThat(response.eftAccountId()).isEqualTo(EFT_ACCOUNT_ID);
            assertThat(response.primaryCardHolderIndicator())
                    .isEqualTo(PRIMARY_CARD_HOLDER_INDICATOR);
            assertThat(response.infoMessage())
                    .isEqualTo(AccountUpdateResponse.MSG_LOOKS_GOOD_SO_FAR);
            assertThat(response.errorMessage())
                    .isEqualTo(AccountUpdateResponse.MSG_ACCOUNT_NUMBER_NOT_USABLE);
            assertThat(response.error()).isTrue();
            assertThat(response.focusScreenFieldId()).isEqualTo(FOCUS_SCREEN_FIELD_ID);
            assertThat(response.nextRoute()).isEqualTo(NEXT_ROUTE);
            assertThat(response.navigationContext())
                    .isEqualTo(JsonContractSupport.populatedNavigation());
            assertThat(response.fieldErrors()).containsExactly(MISSING_STATE, INVALID_FICO);
        }

        /** A space-significant value survives construction untouched. */
        @Test
        @DisplayName("preserves a space-significant value")
        void aSpaceSignificantValueSurvives() {
            String padded = "  padded  ";

            assertThat(carrying("lastName", padded).lastName()).isEqualTo(padded);
            assertThat(carrying("accountStatus", " ").accountStatus()).isEqualTo(" ");
        }
    }
}
