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

import com.carddemo.domain.enums.AccountStatus;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Contract tests for {@link AccountViewResponse}.
 *
 * <h2>What is under test</h2>
 *
 * <p>The declared shape of the account-view response: its forty-one components and their order, the
 * thirty-three screen widths it bounds and the eight components it deliberately leaves unbounded, the
 * five monetary values, the decimal shape its constructor holds them to and the wire form they take,
 * the two derived projections over the raw status character, and which twenty-five of its components
 * its hand-written text rendering withholds.
 *
 * <h2>Thirty-seven map items and four components that are not map items</h2>
 *
 * <p>The symbolic map contributes thirty-seven value items and they occupy the first thirty-seven
 * components in map order. The remaining four — the rejection indicator, the focus hint, the next
 * route and the echoed navigation state — have no map counterpart and are declared last. The order
 * is the contract, so it is asserted position by position rather than as a set.
 *
 * <h2>This response validates nothing beyond measuring its widths, and the credit score proves it</h2>
 *
 * <p>The screen is a display, so the only constraint anywhere on this type is a maximum length, which
 * measures and never alters. The decisive case is the credit score: it is three characters wide and
 * carries <strong>no range constraint at all</strong>, because the seeded reference data holds scores
 * outside any plausible range and rejecting them here would make legitimate rows undisplayable. A
 * range check on that component is therefore asserted absent, not merely unmentioned.
 *
 * <h2>Five monetary values, and the screen edit that is deliberately not reproduced</h2>
 *
 * <p>Five map items declare a numeric-edited picture with a sign position, comma-grouped
 * zero-suppressed digits and two fixed decimals. That edit is 3270 presentation and is not
 * reproduced: the five components are exact decimals at a contractual scale of two, and this type
 * applies no scaling, rounding, negation, grouping or sign formatting of any kind. Both halves of
 * that claim are asserted — the values cross at the scale they were given, and no formatted or masked
 * string is ever emitted.
 *
 * <h2>Two derived projections that report and never alter</h2>
 *
 * <p>The status character is carried raw, and two methods project over it. Both tolerate an absent,
 * empty, over-wide or undeclared value without throwing, because a value outside the declared
 * vocabulary genuinely reaches this screen in the legacy system. Neither changes what the raw
 * accessor returns, and that non-interference is asserted alongside the projections themselves.
 *
 * <h2>Twenty-five of the forty-one components are withheld from the diagnostic rendering</h2>
 *
 * <p>This type declares a rendering of its own rather than accepting the generated one. It is the
 * widest disclosure in the estate - one instance joins an account row to its customer row, carrying a
 * national identifier, a date of birth, a credit score, a full name, a full postal address, two
 * telephone numbers, a government-issued identifier, an external account identifier and five money
 * balances - and a generated rendering would have put the whole join into a single log line. That an
 * operator is authorised to see the screen says nothing about who may read that line.
 *
 * <p>The withholding is per component rather than wholesale, so the rendering keeps its shape: every
 * component still appears under its declared name and in declaration order, and the sixteen that
 * identify nobody still show their values. It is also unconditional, so an absent value renders
 * exactly as a populated one and the placeholder reports nothing about what the account held. And it
 * is confined to {@code toString()}: every accessor returns its component unaltered and the serialized
 * payload is untouched, which is what lets the parity requirement - the screen displays these values
 * in full - and the disclosure control hold at the same time.
 *
 * <h2>The constructor refuses an out-of-shape amount, and refusing is not repairing</h2>
 *
 * <p>All five monetary components carry {@code PIC S9(10)V99}, so the canonical constructor refuses an
 * amount whose scale is not two or which needs more than ten integer digits. It refuses rather than
 * rescales: rescaling would hand the client a plausible value whose precision silently contradicted
 * the published schema, whereas refusing names the offending component and figure at the boundary
 * where the producer that assembled it can still be identified. Nothing it accepts is altered in any
 * way.
 *
 * <h2>How the wire form is observed, and what that does and does not prove</h2>
 *
 * <p>Payloads come from {@link JsonContractSupport#declaredSettingsMapper()}, a mapper carrying the
 * four serialisation settings this module declares, written out by hand in one place rather than
 * copied into every suite. That evidences the shape this type takes <em>under those settings</em>,
 * and nothing more. It is not evidence about the mapper a deployed instance holds, and no assertion
 * below is worded as though it were; {@code ApplicationJsonContractTest} is the in-boundary evidence
 * for the deployed object. Decimal assertions are made against the emitted characters through {@link
 * JsonContractSupport#renderedValueToken(String, String)} and never against a re-parsed tree, because
 * re-parsing a JSON number loses the scale the contract is about.
 *
 * <h2>Provenance</h2>
 *
 * <p>Legacy antecedents cited by the type under test: program {@code app/cbl/COACTVWC.cbl}, symbolic
 * map {@code app/cpy-bms/COACTVW.CPY}, mapset {@code app/bms/COACTVW.bms} and record layouts {@code
 * app/cpy/CVACT01Y.cpy} and {@code app/cpy/CVCUS01Y.cpy}. Checkout SHA {@code
 * 7756d895ffeb65f7ea72aaa609e356d9899afcec}; upstream release stamp {@code
 * CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is reproduced here.
 */
@DisplayName("AccountViewResponse :: response contract of legacy transaction CAVW")
class AccountViewResponseCoverageTest {

    /** The forty-one components, in the order the record declares them. */
    private static final List<String> EXPECTED_COMPONENTS = List.of(
            "transactionName",
            "title01",
            "currentDate",
            "programName",
            "title02",
            "currentTime",
            "accountId",
            "accountStatus",
            "openDate",
            "creditLimit",
            "expirationDate",
            "cashCreditLimit",
            "reissueDate",
            "currentBalance",
            "currentCycleCredit",
            "accountGroupId",
            "currentCycleDebit",
            "customerId",
            "ssn",
            "dateOfBirth",
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
            "phoneNumber1",
            "governmentIssuedId",
            "phoneNumber2",
            "eftAccountId",
            "primaryCardHolderIndicator",
            "infoMessage",
            "errorMessage",
            "inputError",
            "focusScreenFieldId",
            "nextRoute",
            "navigationContext");

    /** The thirty-three components that carry a declared maximum length. */
    private static final List<String> BOUNDED_COMPONENTS = List.of(
            "transactionName",
            "title01",
            "currentDate",
            "programName",
            "title02",
            "currentTime",
            "accountId",
            "accountStatus",
            "openDate",
            "expirationDate",
            "reissueDate",
            "accountGroupId",
            "customerId",
            "ssn",
            "dateOfBirth",
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
            "phoneNumber1",
            "governmentIssuedId",
            "phoneNumber2",
            "eftAccountId",
            "primaryCardHolderIndicator",
            "infoMessage",
            "errorMessage",
            "focusScreenFieldId");

    /** The eight components that carry no declared maximum length. */
    private static final List<String> UNBOUNDED_COMPONENTS = List.of(
            "creditLimit",
            "cashCreditLimit",
            "currentBalance",
            "currentCycleCredit",
            "currentCycleDebit",
            "inputError",
            "nextRoute",
            "navigationContext");

    /** The five monetary components, in declaration order. */
    private static final List<String> MONETARY_COMPONENTS = List.of(
            "creditLimit",
            "cashCreditLimit",
            "currentBalance",
            "currentCycleCredit",
            "currentCycleDebit");

    /** Transaction identifier this screen displays in its header. */
    private static final String TRANSACTION_NAME = "CAVW";

    /** Program name this screen displays in its header. */
    private static final String PROGRAM_NAME = "COACTVWC";

    /** First screen title line, within the forty-character bound. */
    private static final String SCREEN_TITLE_1 = "AWS Mainframe Modernization";

    /** Second screen title line, within the forty-character bound. */
    private static final String SCREEN_TITLE_2 = "CardDemo";

    /** Clock date as the screen renders it: eight characters of text. */
    private static final String CURRENT_DATE = "08/02/26";

    /** Clock time as the screen renders it: eight characters of text. */
    private static final String CURRENT_TIME = "14:35:07";

    /** Account identifier, eleven characters with contractual leading zeros. */
    private static final String ACCOUNT_ID = "00000000011";

    /** Raw status character; the active code of the declared vocabulary. */
    private static final String ACCOUNT_STATUS = "Y";

    /** Account open date as the screen renders it: ten characters of text. */
    private static final String OPEN_DATE = "2020-01-01";

    /** Account expiration date as the screen renders it. */
    private static final String EXPIRATION_DATE = "2025-12-31";

    /** Account reissue date as the screen renders it. */
    private static final String REISSUE_DATE = "2023-06-15";

    /** Account group identifier; ten spaces in every seeded row, so a blank is ordinary. */
    private static final String ACCOUNT_GROUP_ID = "          ";

    /** Customer identifier, nine characters with contractual leading zeros. */
    private static final String CUSTOMER_ID = "000000011";

    /** National identifier in its displayed hyphenated form: twelve characters. */
    private static final String SSN = "123-45-6789";

    /** Customer date of birth as the screen renders it. */
    private static final String DATE_OF_BIRTH = "1980-05-20";

    /** Credit score as three characters; deliberately not range-checked. */
    private static final String FICO_SCORE = "750";

    /** Customer forename. */
    private static final String FIRST_NAME = "MARY";

    /** Customer middle name. */
    private static final String MIDDLE_NAME = "ANN";

    /** Customer surname. */
    private static final String LAST_NAME = "SMITH";

    /** First address line. */
    private static final String ADDRESS_LINE_1 = "100 MAIN STREET";

    /** State code, two characters. */
    private static final String STATE_CODE = "WA";

    /** Second address line. */
    private static final String ADDRESS_LINE_2 = "SUITE 200";

    /** Postal code, five characters projected from a wider stored value. */
    private static final String ZIP_CODE = "98101";

    /** City, populated from the third stored address line. */
    private static final String CITY = "SEATTLE";

    /** Country code, three characters. */
    private static final String COUNTRY_CODE = "USA";

    /** Primary telephone number, already formatted upstream. */
    private static final String PHONE_NUMBER_1 = "(206)555-0100";

    /** Government-issued identifier, twenty characters. */
    private static final String GOVERNMENT_ISSUED_ID = "GOVID-0000000000011";

    /** Secondary telephone number, already formatted upstream. */
    private static final String PHONE_NUMBER_2 = "(206)555-0199";

    /** Electronic funds transfer account identifier, ten characters. */
    private static final String EFT_ACCOUNT_ID = "EFT0000011";

    /** Primary card holder indicator, one raw character. */
    private static final String PRIMARY_CARD_HOLDER_INDICATOR = "Y";

    /** Informational line, within the forty-five-character map width. */
    private static final String INFO_MESSAGE = "Displaying requested account details";

    /** Error line, within the seventy-eight-character map width. */
    private static final String ERROR_MESSAGE = "Account number must be a non zero 11 digit number";

    /** Map field name the client should place the cursor in. */
    private static final String FOCUS_SCREEN_FIELD_ID = "ACCTSID";

    /** Declarative next route; deliberately unbounded because it is service-owned. */
    private static final String NEXT_ROUTE = "/api/accounts";

    /** Credit limit at the contractual scale of two. */
    private static final BigDecimal CREDIT_LIMIT = new BigDecimal("5000.00");

    /** Cash credit limit at the contractual scale of two. */
    private static final BigDecimal CASH_CREDIT_LIMIT = new BigDecimal("1000.00");

    /** Current balance at the contractual scale of two; legitimately negative in general. */
    private static final BigDecimal CURRENT_BALANCE = new BigDecimal("-123.45");

    /** Current cycle credit at the contractual scale of two. */
    private static final BigDecimal CURRENT_CYCLE_CREDIT = new BigDecimal("250.75");

    /** Current cycle debit at the contractual scale of two. */
    private static final BigDecimal CURRENT_CYCLE_DEBIT = new BigDecimal("0.00");

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
     * Builds a response from the supplied text components, leaving every unnamed component absent.
     *
     * @param text the text components to populate, keyed by declared component name
     * @param status the raw status character to carry, which may be {@code null}
     * @param monetary the monetary components to populate, keyed by declared component name
     * @param inputError whether the response reports a rejected request
     * @param navigation the echoed navigation state, which may be {@code null}
     * @return a response carrying exactly the supplied components
     */
    private static AccountViewResponse build(
            Map<String, String> text,
            String status,
            Map<String, BigDecimal> monetary,
            boolean inputError,
            NavigationContext navigation) {
        return new AccountViewResponse(
                text.get("transactionName"),
                text.get("title01"),
                text.get("currentDate"),
                text.get("programName"),
                text.get("title02"),
                text.get("currentTime"),
                text.get("accountId"),
                status,
                text.get("openDate"),
                monetary.get("creditLimit"),
                text.get("expirationDate"),
                monetary.get("cashCreditLimit"),
                text.get("reissueDate"),
                monetary.get("currentBalance"),
                monetary.get("currentCycleCredit"),
                text.get("accountGroupId"),
                monetary.get("currentCycleDebit"),
                text.get("customerId"),
                text.get("ssn"),
                text.get("dateOfBirth"),
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
                text.get("phoneNumber1"),
                text.get("governmentIssuedId"),
                text.get("phoneNumber2"),
                text.get("eftAccountId"),
                text.get("primaryCardHolderIndicator"),
                text.get("infoMessage"),
                text.get("errorMessage"),
                inputError,
                text.get("focusScreenFieldId"),
                text.get("nextRoute"),
                navigation);
    }

    /**
     * Builds a response carrying exactly one component.
     *
     * @param component the component to populate; it must be one of the text components
     * @param value the value to place in that component
     * @return a response carrying only the named component
     */
    private static AccountViewResponse carrying(String component, String value) {
        Map<String, String> text = new HashMap<>();
        if (!"accountStatus".equals(component)) {
            text.put(component, value);
        }
        return build(
                text,
                "accountStatus".equals(component) ? value : null,
                Map.of(),
                false,
                null);
    }

    /**
     * Builds a response carrying exactly one monetary component.
     *
     * @param component the monetary component to populate
     * @param value the decimal to place in that component
     * @return a response carrying only the named monetary component
     */
    private static AccountViewResponse carryingAmount(String component, BigDecimal value) {
        return build(Map.of(), null, Map.of(component, value), false, null);
    }

    /**
     * Builds the empty response: no component populated and the indicator clear.
     *
     * @return a response with every optional component absent
     */
    private static AccountViewResponse empty() {
        return build(Map.of(), null, Map.of(), false, null);
    }

    /**
     * Builds the every-component fixture, optionally carrying the echoed navigation state.
     *
     * @param navigation the echoed navigation state, which may be {@code null}
     * @return a response carrying every component
     */
    private static AccountViewResponse displayed(NavigationContext navigation) {
        Map<String, String> text = new HashMap<>();
        text.put("transactionName", TRANSACTION_NAME);
        text.put("title01", SCREEN_TITLE_1);
        text.put("currentDate", CURRENT_DATE);
        text.put("programName", PROGRAM_NAME);
        text.put("title02", SCREEN_TITLE_2);
        text.put("currentTime", CURRENT_TIME);
        text.put("accountId", ACCOUNT_ID);
        text.put("openDate", OPEN_DATE);
        text.put("expirationDate", EXPIRATION_DATE);
        text.put("reissueDate", REISSUE_DATE);
        text.put("accountGroupId", ACCOUNT_GROUP_ID);
        text.put("customerId", CUSTOMER_ID);
        text.put("ssn", SSN);
        text.put("dateOfBirth", DATE_OF_BIRTH);
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
        text.put("phoneNumber1", PHONE_NUMBER_1);
        text.put("governmentIssuedId", GOVERNMENT_ISSUED_ID);
        text.put("phoneNumber2", PHONE_NUMBER_2);
        text.put("eftAccountId", EFT_ACCOUNT_ID);
        text.put("primaryCardHolderIndicator", PRIMARY_CARD_HOLDER_INDICATOR);
        text.put("infoMessage", INFO_MESSAGE);
        text.put("errorMessage", ERROR_MESSAGE);
        text.put("focusScreenFieldId", FOCUS_SCREEN_FIELD_ID);
        text.put("nextRoute", NEXT_ROUTE);

        Map<String, BigDecimal> monetary = new HashMap<>();
        monetary.put("creditLimit", CREDIT_LIMIT);
        monetary.put("cashCreditLimit", CASH_CREDIT_LIMIT);
        monetary.put("currentBalance", CURRENT_BALANCE);
        monetary.put("currentCycleCredit", CURRENT_CYCLE_CREDIT);
        monetary.put("currentCycleDebit", CURRENT_CYCLE_DEBIT);

        return build(text, ACCOUNT_STATUS, monetary, true, navigation);
    }

    /**
     * Builds a response carrying only the raw status character.
     *
     * @param status the raw status character, which may be {@code null}
     * @return a response carrying only the status character
     */
    private static AccountViewResponse withStatus(String status) {
        return build(Map.of(), status, Map.of(), false, null);
    }

    /**
     * Serialises the supplied response with the module's declared settings.
     *
     * @param response the response to serialise
     * @return the emitted JSON text
     * @throws JsonProcessingException if serialisation fails, which fails the calling test
     */
    private static String payloadOf(AccountViewResponse response) throws JsonProcessingException {
        return JsonContractSupport.declaredSettingsMapper().writeValueAsString(response);
    }

    /** The declared shape of the record: components, order, widths and absent members. */
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
                    AccountViewResponse.class.getDeclaredField(component).getAnnotation(Size.class);
            assertThat(size)
                    .as("component %s must declare a maximum length", component)
                    .isNotNull();
            return size.max();
        }

        /** The forty-one components appear in the documented order. */
        @Test
        @DisplayName("declares forty-one components in the documented order")
        void theComponentsAreDeclaredInTheDocumentedOrder() {
            List<String> declared =
                    Arrays.stream(AccountViewResponse.class.getRecordComponents())
                            .map(RecordComponent::getName)
                            .toList();

            assertThat(declared).containsExactlyElementsOf(EXPECTED_COMPONENTS);
        }

        /** The thirty-seven map items occupy the first thirty-seven positions. */
        @Test
        @DisplayName("places the four non-map components last")
        void theFourNonMapComponentsAreDeclaredLast() {
            List<String> declared =
                    Arrays.stream(AccountViewResponse.class.getRecordComponents())
                            .map(RecordComponent::getName)
                            .toList();

            assertThat(declared.subList(37, 41))
                    .containsExactly(
                            "inputError", "focusScreenFieldId", "nextRoute", "navigationContext");
            assertThat(declared.subList(0, 37))
                    .as("the first thirty-seven components are the map's own value items")
                    .hasSize(37);
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
            "openDate,10",
            "expirationDate,10",
            "reissueDate,10",
            "accountGroupId,10",
            "customerId,9",
            "ssn,12",
            "dateOfBirth,10",
            "ficoScore,3",
            "firstName,25",
            "middleName,25",
            "lastName,25",
            "addressLine1,50",
            "stateCode,2",
            "addressLine2,50",
            "zipCode,5",
            "city,50",
            "countryCode,3",
            "phoneNumber1,13",
            "governmentIssuedId,20",
            "phoneNumber2,13",
            "eftAccountId,10",
            "primaryCardHolderIndicator,1",
            "infoMessage,45",
            "errorMessage,78",
            "focusScreenFieldId,7"
        })
        @DisplayName("declares each screen width")
        void eachBoundedComponentDeclaresItsDocumentedWidth(String component, int width)
                throws NoSuchFieldException {
            assertThat(boundOf(component)).isEqualTo(width);
        }

        /** The five decimals, the indicator, the route and the navigation state carry no width. */
        @ParameterizedTest(name = "{0} declares no width")
        @ValueSource(
                strings = {
                    "creditLimit",
                    "cashCreditLimit",
                    "currentBalance",
                    "currentCycleCredit",
                    "currentCycleDebit",
                    "inputError",
                    "nextRoute",
                    "navigationContext"
                })
        @DisplayName("leaves the decimals, indicator, route and navigation state unbounded")
        void theUnboundedComponentsDeclareNoWidth(String component) throws NoSuchFieldException {
            assertThat(
                            AccountViewResponse.class
                                    .getDeclaredField(component)
                                    .getAnnotation(Size.class))
                    .isNull();
        }

        /** Every component is accounted for as bounded or unbounded. */
        @Test
        @DisplayName("accounts for every component as bounded or unbounded")
        void everyComponentIsAccountedFor() {
            List<String> partition = new ArrayList<>(BOUNDED_COMPONENTS);
            partition.addAll(UNBOUNDED_COMPONENTS);

            assertThat(partition).containsExactlyInAnyOrderElementsOf(EXPECTED_COMPONENTS);
            assertThat(BOUNDED_COMPONENTS).hasSize(33);
            assertThat(UNBOUNDED_COMPONENTS).hasSize(8);
        }

        /**
         * No width constant is declared. Every bound is an inline literal on its own component,
         * because each width belongs to one map item and sharing a constant across items would
         * couple screens that the estate keeps independent.
         *
         * <p>Three statics do exist and none of them is a width. Two publish the decimal shape of the
         * monetary components, which is a property of the account record rather than of any map item and
         * is shared by all five of them, so stating it once is the accurate thing to do. The third is
         * the placeholder the diagnostic rendering substitutes, which is an implementation detail and is
         * accordingly not public. The distinction the original claim was defending - that no screen
         * width is hoisted into a shared constant - is asserted directly instead of by counting.</p>
         */
        @Test
        @DisplayName("declares no width constant and exactly three non-width statics")
        void noStaticFieldIsDeclared() {
            List<Field> statics =
                    Arrays.stream(AccountViewResponse.class.getDeclaredFields())
                            .filter(field -> Modifier.isStatic(field.getModifiers()))
                            .filter(field -> !field.isSynthetic())
                            .toList();

            assertThat(statics).hasSize(3);
            assertThat(statics)
                    .extracting(Field::getName)
                    .containsExactlyInAnyOrder(
                            "MONEY_SCALE", "MONEY_INTEGER_DIGITS", "REDACTION_PLACEHOLDER");
            assertThat(statics)
                    .as("no map width is hoisted into a shared constant")
                    .extracting(Field::getName)
                    .noneMatch(name -> name.endsWith("_LENGTH") || name.endsWith("_WIDTH"));

            assertThat(
                            statics.stream()
                                    .filter(field -> Modifier.isPublic(field.getModifiers()))
                                    .map(Field::getName)
                                    .toList())
                    .containsExactlyInAnyOrder("MONEY_SCALE", "MONEY_INTEGER_DIGITS");
        }

        /**
         * The decimal shape shared by the five monetary components is published once.
         *
         * <p>Ten integer digits and two decimal places is {@code PIC S9(10)V99}, the picture every one
         * of the five account money fields carries, so the two constants together restate that field's
         * precision of twelve. They are published rather than private because the canonical constructor
         * refuses an amount that contradicts them and a producer needs to be able to read the rule it
         * will be held to.</p>
         */
        @Test
        @DisplayName("publishes the decimal shape shared by the five monetary components")
        void theRecordShapeOfTheMonetaryComponentsIsPublished() {
            assertThat(AccountViewResponse.MONEY_SCALE).isEqualTo(2);
            assertThat(AccountViewResponse.MONEY_INTEGER_DIGITS).isEqualTo(10);
            assertThat(AccountViewResponse.MONEY_INTEGER_DIGITS + AccountViewResponse.MONEY_SCALE)
                    .as("ten integer digits and two decimal places is a precision of twelve")
                    .isEqualTo(12);
        }

        /** The five monetary components are exact decimals. */
        @ParameterizedTest(name = "{0} is an exact decimal")
        @ValueSource(
                strings = {
                    "creditLimit",
                    "cashCreditLimit",
                    "currentBalance",
                    "currentCycleCredit",
                    "currentCycleDebit"
                })
        @DisplayName("carries each monetary value as an exact decimal")
        void eachMonetaryComponentIsAnExactDecimal(String component) throws NoSuchFieldException {
            assertThat(AccountViewResponse.class.getDeclaredField(component).getType())
                    .isEqualTo(BigDecimal.class);
        }

        /** Exactly five components are decimals, and every other value component is text. */
        @Test
        @DisplayName("declares exactly five decimal components")
        void exactlyFiveComponentsAreDecimals() {
            List<String> decimals =
                    Arrays.stream(AccountViewResponse.class.getRecordComponents())
                            .filter(component -> component.getType() == BigDecimal.class)
                            .map(RecordComponent::getName)
                            .toList();

            assertThat(decimals).containsExactlyElementsOf(MONETARY_COMPONENTS);
        }

        /** Every identifier is text, never a numeric type. */
        @ParameterizedTest(name = "{0} is text")
        @ValueSource(
                strings = {
                    "accountId",
                    "customerId",
                    "eftAccountId",
                    "accountGroupId",
                    "ficoScore",
                    "governmentIssuedId"
                })
        @DisplayName("carries every identifier as text")
        void everyIdentifierIsText(String component) throws NoSuchFieldException {
            assertThat(AccountViewResponse.class.getDeclaredField(component).getType())
                    .isEqualTo(String.class);
        }

        /** Every date is text, never a temporal type. */
        @ParameterizedTest(name = "{0} is text")
        @ValueSource(strings = {"openDate", "expirationDate", "reissueDate", "dateOfBirth"})
        @DisplayName("carries every date as text")
        void everyDateIsText(String component) throws NoSuchFieldException {
            assertThat(AccountViewResponse.class.getDeclaredField(component).getType())
                    .isEqualTo(String.class);
        }

        /** The rejection indicator is a primitive boolean, stated explicitly. */
        @Test
        @DisplayName("carries the rejection indicator as a primitive boolean")
        void theRejectionIndicatorIsAPrimitiveBoolean() throws NoSuchFieldException {
            assertThat(AccountViewResponse.class.getDeclaredField("inputError").getType())
                    .isEqualTo(boolean.class);
        }

        /**
         * The declared surface beyond the accessors is the two derived projections and one private
         * helper.
         *
         * <p>The projections are published because a client reads them. The helper is the one thing the
         * canonical constructor does: it confirms that a monetary value has the decimal shape of the
         * record field it represents, and it is private, static and void because it decides nothing and
         * returns nothing - it either accepts an amount or refuses it. Its exact modifiers are asserted,
         * because a helper that became public or began returning a value would be new published surface
         * rather than an implementation detail.</p>
         *
         * @throws NoSuchMethodException if the helper is not declared, failing this test
         */
        @Test
        @DisplayName("declares two derived projections and one private helper")
        void theDeclaredSurfaceBeyondTheAccessorsIsTwoProjections()
                throws NoSuchMethodException {
            List<String> declared =
                    Arrays.stream(AccountViewResponse.class.getDeclaredMethods())
                            .filter(method -> !method.isSynthetic())
                            .map(Method::getName)
                            .filter(
                                    name ->
                                            !"equals".equals(name)
                                                    && !"hashCode".equals(name)
                                                    && !"toString".equals(name))
                            .filter(name -> !EXPECTED_COMPONENTS.contains(name))
                            .toList();

            assertThat(declared)
                    .containsExactlyInAnyOrder(
                            "resolvedAccountStatus", "active", "requireRecordShape");

            Method helper =
                    AccountViewResponse.class.getDeclaredMethod(
                            "requireRecordShape", String.class, BigDecimal.class);

            assertThat(Modifier.isPrivate(helper.getModifiers())).isTrue();
            assertThat(Modifier.isStatic(helper.getModifiers())).isTrue();
            assertThat(helper.getReturnType()).isEqualTo(void.class);
        }

        /** Only the generated canonical constructor exists, taking all forty-one components. */
        @Test
        @DisplayName("keeps only the generated canonical constructor")
        void onlyTheGeneratedCanonicalConstructorExists() {
            assertThat(AccountViewResponse.class.getDeclaredConstructors()).hasSize(1);
            assertThat(
                            AccountViewResponse.class
                                    .getDeclaredConstructors()[0]
                                    .getParameterCount())
                    .isEqualTo(EXPECTED_COMPONENTS.size());
        }

        /** No serialisation annotation appears on any component. */
        @Test
        @DisplayName("declares no serialisation annotation on any component")
        void noSerialisationAnnotationAppearsOnAnyComponent() {
            List<Annotation> annotations = new ArrayList<>();
            for (Field field : AccountViewResponse.class.getDeclaredFields()) {
                annotations.addAll(Arrays.asList(field.getAnnotations()));
            }

            assertThat(annotations)
                    .as("this type declares no serialisation behaviour of its own")
                    .noneMatch(
                            annotation ->
                                    annotation
                                            .annotationType()
                                            .getName()
                                            .startsWith("com.fasterxml.jackson"));
        }

        /**
         * No constraint other than a maximum length appears anywhere on this type: no presence,
         * pattern, digit, range or sign constraint exists, because the screen is a display.
         */
        @Test
        @DisplayName("declares no constraint other than a maximum length")
        void noConstraintOtherThanAMaximumLengthIsDeclared() {
            List<Annotation> constraints = new ArrayList<>();
            for (Field field : AccountViewResponse.class.getDeclaredFields()) {
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
                    .as("a display screen validates nothing beyond measuring its widths")
                    .allSatisfy(
                            annotation ->
                                    assertThat(annotation.annotationType()).isEqualTo(Size.class));
            assertThat(constraints).hasSize(BOUNDED_COMPONENTS.size());
        }
    }

    /** The widths that diverge from their neighbours or from the program's own working fields. */
    @Nested
    @DisplayName("Width divergence")
    class WidthDivergence {

        /**
         * Reads the declared maximum length of a component of the supplied record type.
         *
         * @param type the record type to inspect
         * @param component the component name
         * @return the declared maximum length
         * @throws NoSuchFieldException if the component does not exist, failing the calling test
         */
        private int boundOf(Class<?> type, String component) throws NoSuchFieldException {
            return type.getDeclaredField(component).getAnnotation(Size.class).max();
        }

        /**
         * The message widths follow the map rather than the program's narrower working fields, so a
         * value wider than the working field is never shortened here.
         */
        @Test
        @DisplayName("follows the map widths of forty-five and seventy-eight")
        void theMessageWidthsFollowTheMap() throws NoSuchFieldException {
            assertThat(boundOf(AccountViewResponse.class, "infoMessage")).isEqualTo(45);
            assertThat(boundOf(AccountViewResponse.class, "errorMessage")).isEqualTo(78);

            String fortyThree = "A".repeat(43);

            assertThat(carrying("infoMessage", fortyThree).infoMessage())
                    .as("a value wider than the program's forty-character field is not shortened")
                    .isEqualTo(fortyThree);
        }

        /** The informational width here is forty-five, not the forty of the card-detail screen. */
        @Test
        @DisplayName("declares an informational width of forty-five, not the card-detail forty")
        void theInformationalWidthIsFortyFiveAndNotForty() throws NoSuchFieldException {
            assertThat(boundOf(AccountViewResponse.class, "infoMessage")).isEqualTo(45);
            assertThat(CardDetailResponse.INFO_MESSAGE_LENGTH).isEqualTo(40);
        }

        /** The error width here is seventy-eight, not the eighty of the card-detail screen. */
        @Test
        @DisplayName("declares an error width of seventy-eight, not the card-detail eighty")
        void theErrorWidthIsSeventyEightAndNotEighty() throws NoSuchFieldException {
            assertThat(boundOf(AccountViewResponse.class, "errorMessage")).isEqualTo(78);
            assertThat(CardDetailResponse.ERROR_MESSAGE_LENGTH).isEqualTo(80);
        }

        /**
         * The clock time is eight characters on this map, measured rather than inherited from a
         * neighbouring map.
         */
        @Test
        @DisplayName("declares an eight-character clock time")
        void theClockTimeIsEightCharacters() throws NoSuchFieldException {
            assertThat(boundOf(AccountViewResponse.class, "currentTime")).isEqualTo(8);
            assertThat(boundOf(AccountViewResponse.class, "currentDate")).isEqualTo(8);
        }

        /**
         * The national identifier is twelve characters because it is the displayed hyphenated form,
         * which is wider than the nine digits the record stores.
         */
        @Test
        @DisplayName("declares a twelve-character national identifier")
        void theNationalIdentifierIsTwelveCharacters() throws NoSuchFieldException {
            assertThat(boundOf(AccountViewResponse.class, "ssn")).isEqualTo(12);
            assertThat(SSN).hasSize(11).contains("-");
        }

        /**
         * The postal code is five characters, which is narrower than the ten the record stores; the
         * narrowing happens where the value is projected and never here.
         */
        @Test
        @DisplayName("declares a five-character postal code")
        void thePostalCodeIsFiveCharacters() throws NoSuchFieldException {
            assertThat(boundOf(AccountViewResponse.class, "zipCode")).isEqualTo(5);
        }

        /**
         * The focus hint is bounded at seven, the widest map field name this screen declares.
         *
         * <p>Eight is the width the map generator forms its names at, and it would have been the
         * defensible bound if the hint could name any field on any screen. It cannot: it names a field
         * on <em>this</em> screen, and the widest of those is seven characters. Bounding at the width
         * the generator happens to use would admit a name no field here can have, so the bound is the
         * screen's own widest name and the sibling screens' hints are bounded independently at
         * theirs.</p>
         */
        @Test
        @DisplayName("bounds the focus hint at seven")
        void theFocusHintIsBoundedAtEight() throws NoSuchFieldException {
            assertThat(boundOf(AccountViewResponse.class, "focusScreenFieldId")).isEqualTo(7);
        }
    }

    /** The two derived projections over the raw status character. */
    @Nested
    @DisplayName("Status projections")
    class StatusProjections {

        /** The active code resolves to the active constant and answers the predicate. */
        @Test
        @DisplayName("resolves the active code")
        void theActiveCodeResolves() {
            AccountViewResponse response = withStatus("Y");

            assertThat(response.resolvedAccountStatus()).contains(AccountStatus.ACTIVE);
            assertThat(response.active()).isTrue();
        }

        /** The inactive code resolves to the inactive constant and fails the predicate. */
        @Test
        @DisplayName("resolves the inactive code")
        void theInactiveCodeResolves() {
            AccountViewResponse response = withStatus("N");

            assertThat(response.resolvedAccountStatus()).contains(AccountStatus.INACTIVE);
            assertThat(response.active()).isFalse();
        }

        /** An absent or empty status yields an empty result rather than throwing. */
        @ParameterizedTest(name = "[{index}] absent or empty yields empty")
        @NullAndEmptySource
        @DisplayName("yields an empty result for an absent or empty status")
        void anAbsentOrEmptyStatusYieldsAnEmptyResult(String status) {
            AccountViewResponse response = withStatus(status);

            assertThat(response.resolvedAccountStatus()).isEmpty();
            assertThat(response.active()).isFalse();
        }

        /**
         * A status outside the declared vocabulary yields an empty result rather than throwing,
         * because such a value genuinely reaches this screen in the legacy system.
         */
        @ParameterizedTest(name = "{0} yields an empty result")
        @ValueSource(strings = {"y", "n", "Z", "0", " ", "YY", "?"})
        @DisplayName("yields an empty result for an undeclared status")
        void anUndeclaredStatusYieldsAnEmptyResult(String status) {
            AccountViewResponse response = withStatus(status);

            assertThat(response.resolvedAccountStatus()).isEmpty();
            assertThat(response.active()).isFalse();
        }

        /** No case folding is applied, because the legacy editor tests the raw character. */
        @Test
        @DisplayName("applies no case folding")
        void noCaseFoldingIsApplied() {
            assertThat(withStatus("y").resolvedAccountStatus()).isEmpty();
            assertThat(withStatus("Y").resolvedAccountStatus()).contains(AccountStatus.ACTIVE);
        }

        /** Neither projection alters what the raw accessor returns. */
        @ParameterizedTest(name = "{0} survives the projection")
        @ValueSource(strings = {"Y", "N", "y", "Z", " ", "YY"})
        @DisplayName("leaves the raw accessor unchanged")
        void neitherProjectionAltersTheRawAccessor(String status) {
            AccountViewResponse response = withStatus(status);

            response.resolvedAccountStatus();
            response.active();

            assertThat(response.accountStatus()).isEqualTo(status);
        }

        /** The projections agree with the domain enumeration they delegate to. */
        @Test
        @DisplayName("agrees with the domain enumeration")
        void theProjectionsAgreeWithTheDomainEnumeration() {
            for (AccountStatus status : AccountStatus.values()) {
                AccountViewResponse response = withStatus(String.valueOf(status.getCode()));

                assertThat(response.resolvedAccountStatus()).contains(status);
                assertThat(response.active())
                        .isEqualTo(AccountStatus.isActiveCode(String.valueOf(status.getCode())));
            }
        }
    }

    /** The wire form the five monetary values take under the module's declared settings. */
    @Nested
    @DisplayName("Decimal wire form")
    class DecimalWireForm {

        /**
         * Extracts the characters emitted for the named monetary component.
         *
         * @param component the monetary component to populate and read back
         * @param amount the decimal to carry
         * @return the emitted characters of that component
         * @throws JsonProcessingException if serialisation fails, failing the calling test
         */
        private String emitted(String component, BigDecimal amount) throws JsonProcessingException {
            return JsonContractSupport.renderedValueToken(
                    payloadOf(carryingAmount(component, amount)), component);
        }

        /** Each monetary component is emitted in plain decimal form at its constructed scale. */
        @ParameterizedTest(name = "{0} emits {1} plainly")
        @CsvSource({
            "creditLimit,5000.00",
            "cashCreditLimit,1000.00",
            "currentBalance,-123.45",
            "currentCycleCredit,250.75",
            "currentCycleDebit,0.00"
        })
        @DisplayName("emits each monetary value in plain decimal form")
        void eachMonetaryValueIsEmittedPlainly(String component, String literal)
                throws JsonProcessingException {
            assertThat(emitted(component, new BigDecimal(literal))).isEqualTo(literal);
        }

        /** A large amount is never emitted in exponential form. */
        @ParameterizedTest(name = "{0} avoids exponential form")
        @ValueSource(
                strings = {
                    "creditLimit",
                    "cashCreditLimit",
                    "currentBalance",
                    "currentCycleCredit",
                    "currentCycleDebit"
                })
        @DisplayName("never emits a monetary value in exponential form")
        void noMonetaryValueIsEmittedInExponentialForm(String component)
                throws JsonProcessingException {
            String token = emitted(component, new BigDecimal("9999999999.99"));

            assertThat(token).doesNotContain("E").doesNotContain("e");
            assertThat(token).isEqualTo("9999999999.99");
        }

        /** A trailing zero in the scale survives to the wire. */
        @Test
        @DisplayName("keeps a trailing zero in the emitted scale")
        void aTrailingZeroSurvivesToTheWire() throws JsonProcessingException {
            assertThat(emitted("creditLimit", new BigDecimal("1.20"))).isEqualTo("1.20");
            assertThat(emitted("currentBalance", new BigDecimal("0.00"))).isEqualTo("0.00");
        }

        /**
         * The screen edit is not reproduced: no sign glyph, group separator or suppressed leading
         * zero appears in the emitted value.
         */
        @Test
        @DisplayName("reproduces no part of the screen edit")
        void theScreenEditIsNotReproduced() throws JsonProcessingException {
            String token = emitted("creditLimit", new BigDecimal("1234567.89"));

            assertThat(token)
                    .as("no comma grouping, no sign glyph, no zero suppression")
                    .isEqualTo("1234567.89")
                    .doesNotContain(",")
                    .doesNotContain("+")
                    .doesNotContain("$");
        }

        /** A negative amount is carried, because a balance is legitimately negative. */
        @Test
        @DisplayName("carries a negative amount")
        void aNegativeAmountIsCarried() throws JsonProcessingException {
            assertThat(emitted("currentBalance", new BigDecimal("-0.01"))).isEqualTo("-0.01");
        }

        /**
         * No amount is rescaled, rounded, negated or absolute-valued on the way through.
         *
         * <p>Every literal is at the record scale, and every one is a value a normalising layer would
         * be tempted to alter: a trailing zero it would strip, a negative zero it would collapse, a
         * signed value smaller than one cent it would round away, and the widest magnitude the field
         * holds. A literal off the record scale is not a weaker case of the same test - it is a value
         * the constructor refuses, and that refusal is asserted separately below where it cannot be
         * mistaken for a formatting outcome.</p>
         */
        @ParameterizedTest(name = "scale of {0} survives")
        @ValueSource(strings = {"1.20", "0.00", "-0.01", "-9999999999.99"})
        @DisplayName("neither rescales nor rounds any amount")
        void noAmountIsRescaledOrRounded(String literal) {
            BigDecimal supplied = new BigDecimal(literal);
            AccountViewResponse response = carryingAmount("creditLimit", supplied);

            assertThat(response.creditLimit()).isEqualTo(supplied);
            assertThat(response.creditLimit().scale()).isEqualTo(AccountViewResponse.MONEY_SCALE);
            assertThat(response.creditLimit().signum()).isEqualTo(supplied.signum());
            assertThat(response.creditLimit().toPlainString()).isEqualTo(literal);
        }

        /**
         * An amount off the record scale is refused, on every one of the five monetary components, and
         * the refusal names the component.
         *
         * <p>Both scale directions are covered, because a value carrying too few decimal places and one
         * carrying too many are separate mistakes and the refusal reports each with its own count. The
         * component name is in the message because five components share one rule, and a refusal that
         * did not say which of them broke it would be unactionable.</p>
         */
        @ParameterizedTest(name = "{0} refuses an off-scale amount")
        @ValueSource(
                strings = {
                    "creditLimit",
                    "cashCreditLimit",
                    "currentBalance",
                    "currentCycleCredit",
                    "currentCycleDebit"
                })
        @DisplayName("refuses an amount off the record scale")
        void anAmountOffTheRecordScaleIsRefused(String component) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> carryingAmount(component, new BigDecimal("1.5")))
                    .withMessageContaining(component)
                    .withMessageContaining("must carry scale 2")
                    .withMessageContaining("its scale is 1");
        }

        /**
         * An amount wider than the record field is refused rather than carried, so it cannot reach the
         * wire in any form.
         */
        @Test
        @DisplayName("refuses an amount wider than the record field")
        void anAmountWiderThanTheRecordFieldIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(
                            () -> carryingAmount("creditLimit", new BigDecimal("99999999999.99")))
                    .withMessageContaining("must fit 10 integer digits")
                    .withMessageContaining("it needs 11");
        }

        /**
         * An amount at exactly the record width is carried, so the width check is an upper bound rather
         * than an off-by-one refusal of the widest legitimate value.
         */
        @Test
        @DisplayName("carries an amount at exactly the record width")
        void anAmountAtExactlyTheRecordWidthIsCarried() {
            assertThat(carryingAmount("creditLimit", new BigDecimal("9999999999.99")).creditLimit())
                    .isEqualTo(new BigDecimal("9999999999.99"));
        }

        /**
         * Two responses differing only in amount value are not equal, so a substituted amount is
         * detectable even when both amounts are record-shaped.
         *
         * <p>An earlier revision made this point with two amounts differing only in scale. That case no
         * longer exists to be distinguished, because the scale-shifted amount cannot be constructed at
         * all - a refusal is a stronger guarantee than an inequality a caller has to think to check.</p>
         */
        @Test
        @DisplayName("distinguishes two amounts that differ only in value")
        void twoResponsesDifferingOnlyInAmountScaleAreNotEqual() {
            assertThat(carryingAmount("creditLimit", new BigDecimal("1.20")))
                    .isNotEqualTo(carryingAmount("creditLimit", new BigDecimal("1.21")));
        }

        /** An absent amount is omitted rather than emitted as a zero. */
        @ParameterizedTest(name = "{0} is omitted when absent")
        @ValueSource(
                strings = {
                    "creditLimit",
                    "cashCreditLimit",
                    "currentBalance",
                    "currentCycleCredit",
                    "currentCycleDebit"
                })
        @DisplayName("omits an absent amount")
        void anAbsentAmountIsOmitted(String component) throws JsonProcessingException {
            assertThat(payloadOf(empty())).doesNotContain("\"" + component + "\"");
        }

        /** No formatted or masked monetary string is ever emitted. */
        @Test
        @DisplayName("emits no formatted or masked monetary string")
        void noFormattedMonetaryStringIsEmitted() throws JsonProcessingException {
            String payload = payloadOf(displayed(null));

            for (String component : MONETARY_COMPONENTS) {
                assertThat(payload)
                        .as("component %s must not be emitted as a quoted string", component)
                        .doesNotContain("\"" + component + "\":\"");
            }
        }
    }

    /** The JSON form each component takes under the module's declared settings. */
    @Nested
    @DisplayName("Wire shape")
    class WireShape {

        /** Every populated component appears under the name the record declares. */
        @Test
        @DisplayName("emits every populated component under its declared name")
        void everyPopulatedComponentAppearsUnderItsDeclaredName() throws JsonProcessingException {
            JsonNode tree =
                    JsonContractSupport.declaredSettingsMapper()
                            .readTree(
                                    payloadOf(
                                            displayed(
                                                    JsonContractSupport.populatedNavigation())));

            assertThat(tree.fieldNames())
                    .toIterable()
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_COMPONENTS);
        }

        /** An absent component is omitted rather than emitted as a null. */
        @Test
        @DisplayName("omits an absent component")
        void anAbsentComponentIsOmitted() throws JsonProcessingException {
            String payload = payloadOf(carrying("accountId", ACCOUNT_ID));

            assertThat(payload).contains("\"accountId\":\"" + ACCOUNT_ID + "\"");
            for (String component : EXPECTED_COMPONENTS) {
                if (!"accountId".equals(component) && !"inputError".equals(component)) {
                    assertThat(payload)
                            .as("absent component %s must be omitted", component)
                            .doesNotContain("\"" + component + "\"");
                }
            }
        }

        /** An empty response carries only the indicator, which cannot be absent. */
        @Test
        @DisplayName("emits only the indicator when nothing is populated")
        void anEmptyResponseCarriesOnlyTheIndicator() throws JsonProcessingException {
            JsonNode tree =
                    JsonContractSupport.declaredSettingsMapper().readTree(payloadOf(empty()));

            assertThat(tree.fieldNames()).toIterable().containsExactly("inputError");
        }

        /** Every identifier is emitted as text with its leading zeros intact. */
        @Test
        @DisplayName("emits identifiers as text with leading zeros intact")
        void everyIdentifierIsEmittedAsText() throws JsonProcessingException {
            String payload = payloadOf(displayed(null));

            assertThat(payload).contains("\"accountId\":\"" + ACCOUNT_ID + "\"");
            assertThat(payload).contains("\"customerId\":\"" + CUSTOMER_ID + "\"");
            assertThat(payload).contains("\"ficoScore\":\"" + FICO_SCORE + "\"");
        }

        /** Every date is emitted as text rather than as a temporal value. */
        @Test
        @DisplayName("emits dates as text")
        void everyDateIsEmittedAsText() throws JsonProcessingException {
            String payload = payloadOf(displayed(null));

            assertThat(payload).contains("\"openDate\":\"" + OPEN_DATE + "\"");
            assertThat(payload).contains("\"expirationDate\":\"" + EXPIRATION_DATE + "\"");
            assertThat(payload).contains("\"reissueDate\":\"" + REISSUE_DATE + "\"");
            assertThat(payload).contains("\"dateOfBirth\":\"" + DATE_OF_BIRTH + "\"");
        }

        /** The derived projections are not emitted, because they are methods and not components. */
        @Test
        @DisplayName("emits neither derived projection")
        void neitherDerivedProjectionIsEmitted() throws JsonProcessingException {
            String payload = payloadOf(displayed(null));

            assertThat(payload)
                    .doesNotContain("\"resolvedAccountStatus\"")
                    .doesNotContain("\"active\"");
        }

        /** Space-significant values survive serialisation untouched. */
        @Test
        @DisplayName("preserves a space-significant value")
        void aSpaceSignificantValueSurvives() throws JsonProcessingException {
            assertThat(payloadOf(carrying("accountGroupId", ACCOUNT_GROUP_ID)))
                    .contains("\"accountGroupId\":\"" + ACCOUNT_GROUP_ID + "\"");
        }

        /** An unknown property is tolerated on read under the module's declared settings. */
        @Test
        @DisplayName("tolerates an unknown property on read")
        void anUnknownPropertyIsToleratedOnRead() {
            ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();
            String payload = "{\"accountId\":\"00000000011\",\"inputError\":false,\"x\":1}";

            assertThatNoException()
                    .isThrownBy(() -> mapper.readValue(payload, AccountViewResponse.class));
        }

        /** A round trip preserves every component. */
        @Test
        @DisplayName("preserves every component across a round trip")
        void aRoundTripPreservesEveryComponent() throws JsonProcessingException {
            ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();
            AccountViewResponse original =
                    displayed(JsonContractSupport.populatedNavigation());

            AccountViewResponse restored =
                    mapper.readValue(
                            mapper.writeValueAsString(original), AccountViewResponse.class);

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
            assertThat(validator.validate(displayed(JsonContractSupport.populatedNavigation())))
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
            "openDate,10",
            "expirationDate,10",
            "reissueDate,10",
            "accountGroupId,10",
            "customerId,9",
            "ssn,12",
            "dateOfBirth,10",
            "ficoScore,3",
            "firstName,25",
            "middleName,25",
            "lastName,25",
            "addressLine1,50",
            "stateCode,2",
            "addressLine2,50",
            "zipCode,5",
            "city,50",
            "countryCode,3",
            "phoneNumber1,13",
            "governmentIssuedId,20",
            "phoneNumber2,13",
            "eftAccountId,10",
            "primaryCardHolderIndicator,1",
            "infoMessage,45",
            "errorMessage,78",
            "focusScreenFieldId,7"
        })
        @DisplayName("reports an over-long value")
        void anOverLongValueIsReported(String component, int width) {
            Set<ConstraintViolation<AccountViewResponse>> violations =
                    validator.validate(carrying(component, "A".repeat(width + 1)));

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString(component);
        }

        /** A value exactly at a bound is accepted. */
        @ParameterizedTest(name = "{0} at {1} is accepted")
        @CsvSource({
            "transactionName,4",
            "title01,40",
            "currentDate,8",
            "programName,8",
            "title02,40",
            "currentTime,8",
            "accountId,11",
            "accountStatus,1",
            "openDate,10",
            "expirationDate,10",
            "reissueDate,10",
            "accountGroupId,10",
            "customerId,9",
            "ssn,12",
            "dateOfBirth,10",
            "ficoScore,3",
            "firstName,25",
            "middleName,25",
            "lastName,25",
            "addressLine1,50",
            "stateCode,2",
            "addressLine2,50",
            "zipCode,5",
            "city,50",
            "countryCode,3",
            "phoneNumber1,13",
            "governmentIssuedId,20",
            "phoneNumber2,13",
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

        /** The route is not measured, because it is a service-owned identifier. */
        @Test
        @DisplayName("does not measure the route")
        void theRouteIsNotMeasured() {
            assertThat(validator.validate(carrying("nextRoute", "/".repeat(4096)))).isEmpty();
        }

        /**
         * The credit score carries no range constraint. The seeded reference data holds scores
         * outside any plausible range, so rejecting one here would make a legitimate row
         * undisplayable.
         */
        @ParameterizedTest(name = "score {0} is accepted")
        @ValueSource(strings = {"000", "001", "299", "300", "850", "851", "999", "ABC", "  0", "-1"})
        @DisplayName("applies no range constraint to the credit score")
        void theCreditScoreCarriesNoRangeConstraint(String score) {
            assertThat(validator.validate(carrying("ficoScore", score)))
                    .as("a score outside any plausible range genuinely reaches this screen")
                    .isEmpty();
        }

        /**
         * No monetary value is measured by Bean Validation, at either end of the range the record field
         * can hold.
         *
         * <p>The extremes chosen are the widest positive and the widest negative values the field holds,
         * not arbitrarily large ones: a wider value never reaches the validator because the canonical
         * constructor refuses it, so asserting that the validator tolerates one would assert nothing
         * about the validator. What is shown here is that within the shape the constructor admits, no
         * bound, digit count, range or decimal constraint is applied on top.</p>
         */
        @ParameterizedTest(name = "{0} is not measured")
        @ValueSource(
                strings = {
                    "creditLimit",
                    "cashCreditLimit",
                    "currentBalance",
                    "currentCycleCredit",
                    "currentCycleDebit"
                })
        @DisplayName("does not measure any monetary value")
        void noMonetaryValueIsMeasured(String component) {
            assertThat(validator.validate(carryingAmount(component, new BigDecimal("9999999999.99"))))
                    .isEmpty();
            assertThat(
                            validator.validate(
                                    carryingAmount(component, new BigDecimal("-9999999999.99"))))
                    .isEmpty();
        }

        /**
         * An amount outside the record field's shape is refused by the constructor and therefore never
         * reaches the validator at all.
         *
         * <p>This is the reason the assertion above uses the field's own extremes. The two mechanisms
         * are not alternatives: the constructor decides which decimal shapes exist, and Bean Validation
         * measures character widths on the components that carry text. Neither one measures an
         * amount.</p>
         */
        @Test
        @DisplayName("refuses an out-of-shape amount before the validator could see it")
        void anOutOfShapeAmountNeverReachesTheValidator() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(
                            () ->
                                    carryingAmount(
                                            "creditLimit",
                                            new BigDecimal(
                                                    "123456789012345678901234567890.123456789")));
        }

        /**
         * The national identifier is the one intentionally nullable column in the schema and is left
         * unset by the reference seed, so an absent value is accepted with no constraint rejecting
         * it.
         */
        @Test
        @DisplayName("accepts an absent national identifier")
        void anAbsentNationalIdentifierIsAccepted() {
            assertThat(validator.validate(carrying("ssn", null))).isEmpty();
            assertThat(carrying("ssn", null).ssn()).isNull();
        }

        /** A blank or space-valued component is an ordinary state and is not reported. */
        @Test
        @DisplayName("accepts blank and space-padded values")
        void aBlankOrSpacePaddedValueIsAccepted() {
            assertThat(validator.validate(carrying("infoMessage", ""))).isEmpty();
            assertThat(validator.validate(carrying("accountGroupId", ACCOUNT_GROUP_ID))).isEmpty();
            assertThat(validator.validate(carrying("accountStatus", " "))).isEmpty();
        }

        /** No presence constraint exists, so an entirely absent response is valid. */
        @Test
        @DisplayName("requires no component to be present")
        void noComponentIsRequired() {
            Set<ConstraintViolation<AccountViewResponse>> violations = validator.validate(empty());

            assertThat(violations).isEmpty();
        }
    }

    /** What the generated text rendering reveals, and what the nested record withholds. */
    @Nested
    @DisplayName("Diagnostic rendering")
    class DiagnosticRendering {

        /** The twenty-five components this type replaces with a fixed placeholder in its rendering. */
        private static final List<String> WITHHELD_BY_THIS_TYPE =
                List.of(
                        "accountId",
                        "creditLimit",
                        "cashCreditLimit",
                        "currentBalance",
                        "currentCycleCredit",
                        "currentCycleDebit",
                        "customerId",
                        "ssn",
                        "dateOfBirth",
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
                        "phoneNumber1",
                        "governmentIssuedId",
                        "phoneNumber2",
                        "eftAccountId",
                        "infoMessage",
                        "errorMessage");

        /**
         * The rendering is the hand-written withholding form and not the generated record form.
         *
         * <p>The expected text is assembled here from the declared component order and an
         * independently written list of rendered values, so that a change to either the order or a
         * value fails this test. Every component still appears, under its declared name and in
         * declaration order; twenty-five of them appear as a fixed placeholder instead of as their
         * value. Keeping the shape and replacing only the values is what makes the rendering useful for
         * diagnosis while carrying nothing that identifies the cardholder.</p>
         */
        @Test
        @DisplayName("renders the withholding form and not the generated record form")
        void theRenderingIsExactlyTheGeneratedForm() {
            String withheld = "***REDACTED***";
            List<String> renderedValues =
                    List.of(
                            TRANSACTION_NAME,
                            SCREEN_TITLE_1,
                            CURRENT_DATE,
                            PROGRAM_NAME,
                            SCREEN_TITLE_2,
                            CURRENT_TIME,
                            withheld,
                            ACCOUNT_STATUS,
                            OPEN_DATE,
                            withheld,
                            EXPIRATION_DATE,
                            withheld,
                            REISSUE_DATE,
                            withheld,
                            withheld,
                            ACCOUNT_GROUP_ID,
                            withheld,
                            withheld,
                            withheld,
                            withheld,
                            withheld,
                            withheld,
                            withheld,
                            withheld,
                            withheld,
                            withheld,
                            withheld,
                            withheld,
                            withheld,
                            withheld,
                            withheld,
                            withheld,
                            withheld,
                            withheld,
                            PRIMARY_CARD_HOLDER_INDICATOR,
                            withheld,
                            withheld,
                            "true",
                            FOCUS_SCREEN_FIELD_ID,
                            NEXT_ROUTE,
                            "null");

            StringBuilder expected = new StringBuilder("AccountViewResponse[");
            for (int index = 0; index < EXPECTED_COMPONENTS.size(); index++) {
                if (index > 0) {
                    expected.append(", ");
                }
                expected.append(EXPECTED_COMPONENTS.get(index))
                        .append('=')
                        .append(renderedValues.get(index));
            }
            expected.append(']');

            assertThat(displayed(null)).hasToString(expected.toString());
        }

        /**
         * A response carrying no navigation state still substitutes every one of its own withheld
         * components, so the placeholders present are this type's own and not a nested record's.
         *
         * <p>Substitution is unconditional here: it does not depend on a value being present, so a
         * component that was never populated renders identically to one that was. That matters, because
         * a placeholder that appeared only when a value existed would itself report whether the account
         * had a national identifier on file, and a diagnostic that leaks one bit is still a diagnostic
         * that leaks. The navigation state is printed by delegation instead, so its absence remains
         * visible as {@code null} - safe precisely because that state withholds its own values.</p>
         */
        @Test
        @DisplayName("substitutes its own withheld components even with no navigation state")
        void aResponseCarryingNoNavigationStateContainsNoPlaceholder() {
            String rendered = displayed(null).toString();
            String absent = empty().toString();

            assertThat(rendered).contains("ssn=***REDACTED***");
            assertThat(absent)
                    .as("an absent value renders identically to a populated one")
                    .contains("ssn=***REDACTED***")
                    .contains("governmentIssuedId=***REDACTED***");
            assertThat(rendered).contains("navigationContext=null");
        }

        /**
         * Exactly twenty-five components of this type are withheld and the remaining sixteen are
         * retained.
         *
         * <p>This screen is the widest disclosure in the estate: it joins an account row to its customer
         * row, so one instance carries a national identifier, a date of birth, a credit score, a full
         * name, a full postal address, two telephone numbers, a government-issued identifier, an
         * external account identifier and five money balances. That an operator is authorised to
         * <em>see</em> the screen says nothing about who may read a log line, and a record's generated
         * rendering would have put the whole join into one - from any structured logger, framework
         * diagnostic, failed assertion or string interpolation that touched the response.</p>
         *
         * <p><strong>Why the sixteen are retained.</strong> The screen furniture, the clock values, the
         * status character, the three lifecycle dates, the group identifier, the cardholder indicator,
         * the rejection indicator, the focus hint, the route and the navigation state identify nobody
         * on their own, and they are the part of a response worth seeing in a diagnostic. A rendering
         * that withheld everything would be one nobody could use, which is how withholding gets
         * removed.</p>
         *
         * <p>The fixture deliberately carries no navigation state, because two of this type's own
         * component names - the account and customer identifiers - are also names the nested navigation
         * record withholds, and rendering with the nested record present would make the two levels'
         * placeholders indistinguishable.</p>
         */
        @Test
        @DisplayName("withholds exactly twenty-five components of its own")
        void noComponentOfThisTypeIsWithheld() {
            String rendered = displayed(null).toString();

            assertThat(WITHHELD_BY_THIS_TYPE).hasSize(25);
            assertThat(EXPECTED_COMPONENTS).containsAll(WITHHELD_BY_THIS_TYPE);

            for (String component : WITHHELD_BY_THIS_TYPE) {
                assertThat(rendered)
                        .as("component %s carries regulated content and is withheld", component)
                        .contains(component + "=***REDACTED***");
            }

            List<String> retained = new ArrayList<>(EXPECTED_COMPONENTS);
            retained.removeAll(WITHHELD_BY_THIS_TYPE);

            assertThat(retained).hasSize(16);
            for (String component : retained) {
                assertThat(rendered)
                        .as(
                                "component %s identifies nobody and is the part of a response worth"
                                        + " seeing in a diagnostic",
                                component)
                        .doesNotContain(component + "=***REDACTED***");
            }
        }

        /**
         * Two component names are shared with the nested navigation record, and both levels withhold
         * them, so neither copy of either identifier reaches the rendering.
         *
         * <p>The collision used to matter: when this type rendered its own identifiers in full, a reader
         * had to know which level a placeholder belonged to in order to know whether a value had leaked.
         * With both levels withholding, the question does not arise - each identifier appears twice and
         * both times as a placeholder, which is asserted by counting rather than by finding, because a
         * single occurrence would mean one of the two levels had stopped withholding.</p>
         */
        @Test
        @DisplayName("withholds its own identifiers at both levels where the nested names collide")
        void theCollidingIdentifiersAreStillRenderedInFull() {
            String rendered = displayed(JsonContractSupport.populatedNavigation()).toString();

            assertThat(rendered)
                    .as("neither this type's value nor the nested record's reaches the text")
                    .doesNotContain(ACCOUNT_ID)
                    .doesNotContain(CUSTOMER_ID);
            assertThat(rendered).contains("navigationContext=NavigationContext[");
            assertThat(rendered.split("accountId=\\*\\*\\*REDACTED\\*\\*\\*", -1))
                    .as("both levels withhold the account identifier")
                    .hasSize(3);
            assertThat(rendered.split("customerId=\\*\\*\\*REDACTED\\*\\*\\*", -1))
                    .as("both levels withhold the customer identifier")
                    .hasSize(3);
        }

        /**
         * The sensitive values this screen displays are transported unaltered and withheld from the
         * rendering, which are two different things and both are asserted here.
         *
         * <p>Transported unaltered is a parity requirement: the legacy screen shows the national
         * identifier, the date of birth and the government-issued identifier in full, so obscuring one
         * on the way to the client would change what the screen shows. Withheld from the rendering is a
         * disclosure control on a different channel entirely - the accessors and the serialized payload
         * carry the values, and only the diagnostic text does not. Confining the withholding to
         * {@code toString()} is what lets both hold at once; the wider field-level encryption gap is
         * recorded in the decision log and is deliberately not closed here.</p>
         */
        @Test
        @DisplayName("transports the displayed sensitive values unaltered and withholds them from the rendering")
        void theDisplayedSensitiveValuesAreTransportedUnaltered() {
            AccountViewResponse response = displayed(null);

            assertThat(response.ssn()).isEqualTo(SSN);
            assertThat(response.governmentIssuedId()).isEqualTo(GOVERNMENT_ISSUED_ID);
            assertThat(response.dateOfBirth()).isEqualTo(DATE_OF_BIRTH);
            assertThat(response.toString())
                    .as("the diagnostic channel carries none of the three")
                    .doesNotContain(SSN)
                    .doesNotContain(GOVERNMENT_ISSUED_ID)
                    .doesNotContain(DATE_OF_BIRTH)
                    .contains("ssn=***REDACTED***")
                    .contains("governmentIssuedId=***REDACTED***")
                    .contains("dateOfBirth=***REDACTED***");
        }

        /** This response carries neither a card number nor a card verification code. */
        @Test
        @DisplayName("carries neither a card number nor a verification code")
        void neitherACardNumberNorAVerificationCodeIsCarried() {
            List<String> declared =
                    Arrays.stream(AccountViewResponse.class.getRecordComponents())
                            .map(RecordComponent::getName)
                            .toList();

            assertThat(declared).doesNotContain("cardNumber", "cvvCode", "cardCvvCode");
        }

        /**
         * A nested navigation state withholds its own identifying components, so none of its
         * distinctive values reaches this rendering.
         */
        @Test
        @DisplayName("relies on the navigation state to withhold its identifying components")
        void aNestedNavigationStateWithholdsItsIdentifyingComponents() {
            String rendered = displayed(JsonContractSupport.populatedNavigation()).toString();

            assertThat(rendered).contains("navigationContext=NavigationContext[");
            assertThat(rendered)
                    .doesNotContain(JsonContractSupport.NAV_CUSTOMER_ID)
                    .doesNotContain(JsonContractSupport.NAV_FIRST_NAME)
                    .doesNotContain(JsonContractSupport.NAV_MIDDLE_NAME)
                    .doesNotContain(JsonContractSupport.NAV_LAST_NAME)
                    .doesNotContain(JsonContractSupport.NAV_ACCOUNT_ID)
                    .doesNotContain(JsonContractSupport.NAV_CARD_NUMBER);
        }
    }

    /** Equality, hashing, absence tolerance and the indicator's independence. */
    @Nested
    @DisplayName("Value semantics")
    class ValueSemantics {

        /** Two responses carrying equal components are equal. */
        @Test
        @DisplayName("treats equal components as equal values")
        void twoResponsesWithEqualComponentsAreEqual() {
            assertThat(displayed(null)).isEqualTo(displayed(null));
            assertThat(displayed(JsonContractSupport.populatedNavigation()))
                    .isEqualTo(displayed(JsonContractSupport.populatedNavigation()));
        }

        /** Hashing is stable across equal instances. */
        @Test
        @DisplayName("hashes equal values alike")
        void hashCodeIsStableAcrossEqualInstances() {
            assertThat(displayed(null)).hasSameHashCodeAs(displayed(null));
        }

        /** A difference in any component is observed. */
        @Test
        @DisplayName("observes a difference in any component")
        void aDifferenceInAnyComponentIsObserved() {
            assertThat(carrying("accountId", ACCOUNT_ID))
                    .isNotEqualTo(carrying("accountId", "00000000012"));
            assertThat(displayed(null))
                    .isNotEqualTo(displayed(JsonContractSupport.populatedNavigation()));
        }

        /** Every component tolerates an absent value. */
        @Test
        @DisplayName("tolerates an absent value in every component")
        void everyComponentToleratesAnAbsentValue() {
            AccountViewResponse response = empty();

            assertThat(response.transactionName()).isNull();
            assertThat(response.accountId()).isNull();
            assertThat(response.accountStatus()).isNull();
            assertThat(response.creditLimit()).isNull();
            assertThat(response.currentBalance()).isNull();
            assertThat(response.ssn()).isNull();
            assertThat(response.infoMessage()).isNull();
            assertThat(response.errorMessage()).isNull();
            assertThat(response.focusScreenFieldId()).isNull();
            assertThat(response.nextRoute()).isNull();
            assertThat(response.navigationContext()).isNull();
        }

        /**
         * The rejection indicator is stated explicitly and never inferred from the presence of an
         * error message, so the two are able to disagree in both directions.
         */
        @Test
        @DisplayName("states the indicator independently of the error message")
        void theIndicatorIsStatedIndependentlyOfTheErrorMessage() {
            AccountViewResponse messageWithoutFlag =
                    build(Map.of("errorMessage", ERROR_MESSAGE), null, Map.of(), false, null);
            AccountViewResponse flagWithoutMessage =
                    build(Map.of(), null, Map.of(), true, null);

            assertThat(messageWithoutFlag.errorMessage()).isEqualTo(ERROR_MESSAGE);
            assertThat(messageWithoutFlag.inputError()).isFalse();
            assertThat(flagWithoutMessage.errorMessage()).isNull();
            assertThat(flagWithoutMessage.inputError()).isTrue();
        }

        /** Every accessor returns exactly what was supplied. */
        @Test
        @DisplayName("returns from every accessor exactly what was supplied")
        void everyAccessorReturnsWhatWasSupplied() {
            AccountViewResponse response =
                    displayed(JsonContractSupport.populatedNavigation());

            assertThat(response.transactionName()).isEqualTo(TRANSACTION_NAME);
            assertThat(response.title01()).isEqualTo(SCREEN_TITLE_1);
            assertThat(response.currentDate()).isEqualTo(CURRENT_DATE);
            assertThat(response.programName()).isEqualTo(PROGRAM_NAME);
            assertThat(response.title02()).isEqualTo(SCREEN_TITLE_2);
            assertThat(response.currentTime()).isEqualTo(CURRENT_TIME);
            assertThat(response.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(response.accountStatus()).isEqualTo(ACCOUNT_STATUS);
            assertThat(response.openDate()).isEqualTo(OPEN_DATE);
            assertThat(response.creditLimit()).isEqualTo(CREDIT_LIMIT);
            assertThat(response.expirationDate()).isEqualTo(EXPIRATION_DATE);
            assertThat(response.cashCreditLimit()).isEqualTo(CASH_CREDIT_LIMIT);
            assertThat(response.reissueDate()).isEqualTo(REISSUE_DATE);
            assertThat(response.currentBalance()).isEqualTo(CURRENT_BALANCE);
            assertThat(response.currentCycleCredit()).isEqualTo(CURRENT_CYCLE_CREDIT);
            assertThat(response.accountGroupId()).isEqualTo(ACCOUNT_GROUP_ID);
            assertThat(response.currentCycleDebit()).isEqualTo(CURRENT_CYCLE_DEBIT);
            assertThat(response.customerId()).isEqualTo(CUSTOMER_ID);
            assertThat(response.ssn()).isEqualTo(SSN);
            assertThat(response.dateOfBirth()).isEqualTo(DATE_OF_BIRTH);
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
            assertThat(response.phoneNumber1()).isEqualTo(PHONE_NUMBER_1);
            assertThat(response.governmentIssuedId()).isEqualTo(GOVERNMENT_ISSUED_ID);
            assertThat(response.phoneNumber2()).isEqualTo(PHONE_NUMBER_2);
            assertThat(response.eftAccountId()).isEqualTo(EFT_ACCOUNT_ID);
            assertThat(response.primaryCardHolderIndicator())
                    .isEqualTo(PRIMARY_CARD_HOLDER_INDICATOR);
            assertThat(response.infoMessage()).isEqualTo(INFO_MESSAGE);
            assertThat(response.errorMessage()).isEqualTo(ERROR_MESSAGE);
            assertThat(response.inputError()).isTrue();
            assertThat(response.focusScreenFieldId()).isEqualTo(FOCUS_SCREEN_FIELD_ID);
            assertThat(response.nextRoute()).isEqualTo(NEXT_ROUTE);
            assertThat(response.navigationContext())
                    .isEqualTo(JsonContractSupport.populatedNavigation());
        }

        /** A space-significant value survives construction untouched. */
        @Test
        @DisplayName("preserves a space-significant value")
        void aSpaceSignificantValueSurvives() {
            String padded = "  padded value   ";

            assertThat(carrying("city", padded).city()).isEqualTo(padded);
            assertThat(carrying("accountGroupId", ACCOUNT_GROUP_ID).accountGroupId())
                    .isEqualTo(ACCOUNT_GROUP_ID)
                    .hasSize(10);
        }
    }
}
