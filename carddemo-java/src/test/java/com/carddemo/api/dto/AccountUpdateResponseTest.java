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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Stream;

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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link AccountUpdateResponse}, the response contract for legacy CICS transaction
 * {@code CAUP}. The authorities are symbolic map {@code app/cpy-bms/COACTUP.CPY}, mapset
 * {@code app/bms/COACTUP.bms}, program {@code app/cbl/COACTUPC.cbl}, decoration macro
 * {@code app/cpy/CSSETATY.cpy} and record layout {@code app/cpy/CVACT01Y.cpy}. Provenance of the
 * read-only legacy tree these citations point into: checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No picture clause, copy directive, job card
 * or procedure-division fragment is transcribed anywhere below; every citation is a line reference.
 *
 * <p>This is a pure unit test. Nothing is started, no context is loaded, no container runs and no
 * database is reached: every instance under test is built by calling the constructor, and the
 * wire-shape assertions use a locally built mapper configured to match
 * {@code carddemo-java/src/main/resources/application.yml} lines 101 to 119.
 *
 * <h2>What this suite owns</h2>
 *
 * <p>The map declares 54 field families and this response carries 51 of them. The mapset declares
 * <strong>43</strong> unprotected fields - the operator-editable set - and the macro is expanded
 * <strong>39</strong> times between {@code COACTUPC} lines 3208 and 3432. The arithmetic behind that
 * difference is worth stating once, because it is the reason two counts appear throughout: the four
 * fields that are editable but never decorated are the account id, the account group id, the
 * customer id and the government-issued id. Every other editable field owns a decoration site, so
 * the field-error universe is 39 entries wide and never 43.
 *
 * <p>The single most consequential assertion here concerns two of those 39. The source states
 * outright that the middle name has "no edits coded" ({@code COACTUPC} line 3345) and that the
 * second address line has "NO EDITS CODED AS YET" (line 3369), so neither is ever validated and
 * neither carries a constraint annotation - not even a length one. It does <em>not</em> follow that
 * neither can ever show an error: both comments sit directly above a live macro expansion, at lines
 * 3347 and 3371 respectively. "Unvalidated" means <em>no rule fires</em>, not <em>no error can be
 * displayed</em>. This suite therefore proves both fields are decoratable in both states while the
 * counterpart request suite proves neither is constrained. That pairing is <strong>contract, not a
 * defect</strong>.
 *
 * <h2>Deliberate non-assertions</h2>
 *
 * <p>Immutability is demonstrated by construction rather than by inspecting the type, and component
 * absence is demonstrated from the serialized key set. Nothing here reads the type's own structure:
 * a test that inspects declarations proves what the compiler already guarantees, whereas a test that
 * serializes proves what a client actually receives.
 *
 * <p>The rollback asymmetry in the legacy write path is <em>not</em> modelled here, because it is not
 * this type's business. The estate's only transaction-abandoning verb sits on the customer-rewrite
 * failure arm ({@code COACTUPC} lines 4095 to 4103, the verb itself at 4099 to 4101) while the
 * account-rewrite failure arm at lines 4076 to 4081 abandons nothing. Both arms raise the same
 * condition, so both surface identically here - as a summary message plus field errors. The
 * asymmetry belongs to the service, and this suite asserts only that no locking, versioning or
 * entity-tag token leaks onto the response to represent it.
 *
 * @since 1.0.0
 */
@DisplayName("AccountUpdateResponse")
class AccountUpdateResponseTest {

    /** Scale every monetary component must carry, from the two decimal places of the record field. */
    private static final int MONEY_SCALE = 2;

    /** Widest integer part the record field admits, from its ten integer digits. */
    private static final int MONEY_INTEGER_DIGITS = 10;

    /** Number of unprotected fields the mapset declares - the operator-editable set. */
    private static final int EDITABLE_FIELD_COUNT = 43;

    /** Number of macro expansions in the program, so the width of the field-error universe. */
    private static final int DECORATED_FIELD_COUNT = 39;

    /**
     * Account identifier carrying a leading-zero run, which is the whole reason identifiers stay
     * strings. Eleven characters, matching the map width.
     */
    private static final String LEADING_ZERO_ACCOUNT_ID = "00000000001";

    /**
     * Credit score of {@code 001}, three characters. An integral type would deliver {@code 1} and
     * the operator would see a different value than the one that was stored.
     */
    private static final String LEADING_ZERO_FICO = "001";

    /**
     * Assembled telephone form the <em>record</em> stores in a fifteen-character field, per the
     * program's own note at {@code COACTUPC} line 2227. It appears here only as a negative oracle:
     * no accessor on this response may ever return it, because assembly is the service's job and
     * this type keeps the three parts apart.
     */
    private static final String ASSEMBLED_PHONE = "(703)555-0199";

    /**
     * Shared factory for the whole class, opened once and closed once. A factory is expensive to
     * build and every group needs the same one; the close is not decoration, because the factory
     * holds resources.
     */
    private static ValidatorFactory validatorFactory;

    /** Validator drawn from {@link #validatorFactory}, used by every constraint assertion. */
    private static Validator validator;

    @BeforeAll
    static void openValidatorFactory() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidatorFactory() {
        if (validatorFactory != null) {
            validatorFactory.close();
        }
    }

    /**
     * Mapper configured to match the module's own serialization settings, built locally rather than
     * borrowed so this suite states the shape it relies on instead of inheriting it.
     *
     * <p>The inclusion setting is expressed through the value-based overload deliberately: the
     * single-argument form is deprecated in the pinned Jackson release, and this build promotes a
     * deprecation warning to a compilation failure.
     *
     * @return a mapper equivalent to the one the running application uses
     */
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

    /**
     * Collects the property names a client actually receives, read from the serialized document
     * rather than from the type's declarations.
     *
     * @param document the serialized response
     * @return the property names present, in a stable order for readable failure output
     */
    private static Set<String> wireKeysOf(final JsonNode document) {
        final Set<String> names = new TreeSet<>();
        for (final Map.Entry<String, JsonNode> property : document.properties()) {
            names.add(property.getKey());
        }
        return names;
    }

    /**
     * Serializes a response and returns its property names.
     *
     * @param response the instance to serialize
     * @return the property names present on the wire
     * @throws JsonProcessingException if the mapper cannot render the instance
     */
    private static Set<String> wireKeysOf(final AccountUpdateResponse response)
            throws JsonProcessingException {
        final ObjectMapper mapper = moduleEquivalentMapper();
        return wireKeysOf(mapper.readTree(mapper.writeValueAsString(response)));
    }

    /**
     * Reports which of the named properties actually reach the wire, so that asserting the result is
     * empty is a statement that none of them exists.
     *
     * @param response   the instance to serialize
     * @param candidates the property names being ruled out
     * @return the subset of {@code candidates} present on the wire
     */
    private static Set<String> wireKeysOf(final AccountUpdateResponse response,
            final String... candidates) {
        final Set<String> present = new LinkedHashSet<>();
        final Set<String> actual;
        try {
            actual = wireKeysOf(response);
        } catch (final JsonProcessingException failure) {
            throw new AssertionError(
                    "the response must serialize so its property names can be inspected", failure);
        }
        for (final String candidate : candidates) {
            if (actual.contains(candidate)) {
                present.add(candidate);
            }
        }
        return present;
    }

    /**
     * Looks up one of the 38 non-monetary components by name.
     *
     * @param name the component's name
     * @return the matching carrier
     */
    private static StringComponent componentNamed(final String name) {
        return echoedStringComponents()
                .filter(component -> component.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the width table must contain a component named " + name
                                + "; if the response was renamed, update the table rather than "
                                + "the response"));
    }

    /**
     * Looks up one of the five monetary components by name.
     *
     * @param name the component's name
     * @return the matching carrier
     */
    private static MoneyComponent componentNamedMoney(final String name) {
        return monetaryComponents()
                .filter(component -> component.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the monetary table must contain a component named " + name));
    }

    /**
     * Builds a string of a chosen length from a repeating alphabet, used where a value has to sit
     * exactly on, or one character past, a declared width.
     *
     * @param length how many characters to produce
     * @return a value of exactly {@code length} characters
     */
    private static String valueOfLength(final int length) {
        final StringBuilder builder = new StringBuilder(length);
        for (int position = 0; position < length; position++) {
            builder.append((char) ('A' + (position % 26)));
        }
        return builder.toString();
    }

    /**
     * Mutable scaffold that turns the response's single 57-argument constructor into something a test
     * can express one component at a time. Fields are assigned directly by the component carriers
     * below, which keeps the width table free of thirty-eight one-line setters while leaving every
     * assignment fully type-checked by the compiler.
     *
     * <p>This scaffold exists only to build instances. It deliberately mirrors nothing about the
     * response's behaviour: {@link #build()} passes the components straight through, so every
     * normalization, guard and rejection observed by the tests is the response's own.
     */
    private static final class ResponseBuilder {

        private String transactionName;
        private String title01;
        private String currentDate;
        private String programName;
        private String title02;
        private String currentTime;

        private String accountId;
        private String accountStatus;
        private String openYear;
        private String openMonth;
        private String openDay;
        private BigDecimal creditLimit;
        private String expiryYear;
        private String expiryMonth;
        private String expiryDay;
        private BigDecimal cashCreditLimit;
        private String reissueYear;
        private String reissueMonth;
        private String reissueDay;
        private BigDecimal currentBalance;
        private BigDecimal currentCycleCredit;
        private String accountGroupId;
        private BigDecimal currentCycleDebit;
        private String customerId;
        private String ssnPart1;
        private String ssnPart2;
        private String ssnPart3;
        private String dateOfBirthYear;
        private String dateOfBirthMonth;
        private String dateOfBirthDay;
        private String ficoScore;
        private String firstName;
        private String middleName;
        private String lastName;
        private String addressLine1;
        private String stateCode;
        private String addressLine2;
        private String zipCode;
        private String city;
        private String countryCode;
        private String phone1AreaCode;
        private String phone1Prefix;
        private String phone1LineNumber;
        private String governmentIssuedId;
        private String phone2AreaCode;
        private String phone2Prefix;
        private String phone2LineNumber;
        private String eftAccountId;
        private String primaryCardHolderIndicator;

        private String infoMessage;
        private String errorMessage;

        private boolean error;
        private String focusScreenFieldId;
        private String nextRoute;
        private NavigationContext navigationContext;
        private List<ErrorResponse.FieldError> fieldErrors;

        ResponseBuilder errorMessage(final String value) {
            this.errorMessage = value;
            return this;
        }

        ResponseBuilder infoMessage(final String value) {
            this.infoMessage = value;
            return this;
        }

        ResponseBuilder error(final boolean value) {
            this.error = value;
            return this;
        }

        ResponseBuilder focusScreenFieldId(final String value) {
            this.focusScreenFieldId = value;
            return this;
        }

        ResponseBuilder nextRoute(final String value) {
            this.nextRoute = value;
            return this;
        }

        ResponseBuilder navigationContext(final NavigationContext value) {
            this.navigationContext = value;
            return this;
        }

        ResponseBuilder fieldErrors(final List<ErrorResponse.FieldError> value) {
            this.fieldErrors = value;
            return this;
        }

        AccountUpdateResponse build() {
            return new AccountUpdateResponse(
                    transactionName, title01, currentDate, programName, title02, currentTime,
                    accountId, accountStatus, openYear, openMonth, openDay, creditLimit,
                    expiryYear, expiryMonth, expiryDay, cashCreditLimit,
                    reissueYear, reissueMonth, reissueDay, currentBalance,
                    currentCycleCredit, accountGroupId, currentCycleDebit, customerId,
                    ssnPart1, ssnPart2, ssnPart3,
                    dateOfBirthYear, dateOfBirthMonth, dateOfBirthDay, ficoScore,
                    firstName, middleName, lastName, addressLine1, stateCode, addressLine2,
                    zipCode, city, countryCode,
                    phone1AreaCode, phone1Prefix, phone1LineNumber, governmentIssuedId,
                    phone2AreaCode, phone2Prefix, phone2LineNumber,
                    eftAccountId, primaryCardHolderIndicator,
                    infoMessage, errorMessage,
                    error, focusScreenFieldId, nextRoute, navigationContext, fieldErrors);
        }
    }

    /**
     * A fresh scaffold with every component absent and the error indicator down: the shape a first,
     * clean presentation of the screen takes.
     *
     * @return a scaffold carrying nothing
     */
    private static ResponseBuilder blank() {
        return new ResponseBuilder();
    }

    /**
     * One of the 38 non-monetary echoed values, paired with the width its map family declares and
     * with the two function references needed to write it and read it back. Carrying the accessor as
     * a method reference is what lets the width table drive assertions without inspecting the type.
     *
     * @param name   the component's name, used as the test display name
     * @param width  the width the map family declares for it
     * @param setter writes a value onto a scaffold
     * @param getter reads the value back off a built response
     */
    private record StringComponent(String name, int width,
                                   BiConsumer<ResponseBuilder, String> setter,
                                   Function<AccountUpdateResponse, String> getter) {

        /**
         * Builds a response carrying only this component.
         *
         * @param value the value to carry
         * @return the built response
         */
        AccountUpdateResponse carrying(final String value) {
            final ResponseBuilder builder = blank();
            setter.accept(builder, value);
            return builder.build();
        }

        /**
         * Reads this component back off a response.
         *
         * @param response the response to read
         * @return the value carried
         */
        String readFrom(final AccountUpdateResponse response) {
            return getter.apply(response);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * One of the five monetary echoed values. All five share a single numeric contract - ten integer
     * digits and exactly two decimal places - so the carrier holds no width, only the accessors.
     *
     * @param name   the component's name, used as the test display name
     * @param setter writes an amount onto a scaffold
     * @param getter reads the amount back off a built response
     */
    private record MoneyComponent(String name,
                                  BiConsumer<ResponseBuilder, BigDecimal> setter,
                                  Function<AccountUpdateResponse, BigDecimal> getter) {

        /**
         * Builds a response carrying only this amount.
         *
         * @param amount the amount to carry
         * @return the built response
         */
        AccountUpdateResponse carrying(final BigDecimal amount) {
            final ResponseBuilder builder = blank();
            setter.accept(builder, amount);
            return builder.build();
        }

        /**
         * Reads this amount back off a response.
         *
         * @param response the response to read
         * @return the amount carried
         */
        BigDecimal readFrom(final AccountUpdateResponse response) {
            return getter.apply(response);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * The 38 non-monetary echoed values with the widths their map families declare, in map
     * declaration order. Verified against {@code app/cpy-bms/COACTUP.CPY}, where the input group
     * begins at line 17 and the output group redefines it at line 343: all 54 families appear on both
     * sides at identical widths, so one figure per component serves both directions.
     *
     * @return the width table
     */
    private static Stream<StringComponent> echoedStringComponents() {
        return Stream.of(
                new StringComponent("accountId", 11,
                        (builder, value) -> builder.accountId = value,
                        AccountUpdateResponse::accountId),
                new StringComponent("accountStatus", 1,
                        (builder, value) -> builder.accountStatus = value,
                        AccountUpdateResponse::accountStatus),
                new StringComponent("openYear", 4,
                        (builder, value) -> builder.openYear = value,
                        AccountUpdateResponse::openYear),
                new StringComponent("openMonth", 2,
                        (builder, value) -> builder.openMonth = value,
                        AccountUpdateResponse::openMonth),
                new StringComponent("openDay", 2,
                        (builder, value) -> builder.openDay = value,
                        AccountUpdateResponse::openDay),
                new StringComponent("expiryYear", 4,
                        (builder, value) -> builder.expiryYear = value,
                        AccountUpdateResponse::expiryYear),
                new StringComponent("expiryMonth", 2,
                        (builder, value) -> builder.expiryMonth = value,
                        AccountUpdateResponse::expiryMonth),
                new StringComponent("expiryDay", 2,
                        (builder, value) -> builder.expiryDay = value,
                        AccountUpdateResponse::expiryDay),
                new StringComponent("reissueYear", 4,
                        (builder, value) -> builder.reissueYear = value,
                        AccountUpdateResponse::reissueYear),
                new StringComponent("reissueMonth", 2,
                        (builder, value) -> builder.reissueMonth = value,
                        AccountUpdateResponse::reissueMonth),
                new StringComponent("reissueDay", 2,
                        (builder, value) -> builder.reissueDay = value,
                        AccountUpdateResponse::reissueDay),
                new StringComponent("accountGroupId", 10,
                        (builder, value) -> builder.accountGroupId = value,
                        AccountUpdateResponse::accountGroupId),
                new StringComponent("customerId", 9,
                        (builder, value) -> builder.customerId = value,
                        AccountUpdateResponse::customerId),
                new StringComponent("ssnPart1", 3,
                        (builder, value) -> builder.ssnPart1 = value,
                        AccountUpdateResponse::ssnPart1),
                new StringComponent("ssnPart2", 2,
                        (builder, value) -> builder.ssnPart2 = value,
                        AccountUpdateResponse::ssnPart2),
                new StringComponent("ssnPart3", 4,
                        (builder, value) -> builder.ssnPart3 = value,
                        AccountUpdateResponse::ssnPart3),
                new StringComponent("dateOfBirthYear", 4,
                        (builder, value) -> builder.dateOfBirthYear = value,
                        AccountUpdateResponse::dateOfBirthYear),
                new StringComponent("dateOfBirthMonth", 2,
                        (builder, value) -> builder.dateOfBirthMonth = value,
                        AccountUpdateResponse::dateOfBirthMonth),
                new StringComponent("dateOfBirthDay", 2,
                        (builder, value) -> builder.dateOfBirthDay = value,
                        AccountUpdateResponse::dateOfBirthDay),
                new StringComponent("ficoScore", 3,
                        (builder, value) -> builder.ficoScore = value,
                        AccountUpdateResponse::ficoScore),
                new StringComponent("firstName", 25,
                        (builder, value) -> builder.firstName = value,
                        AccountUpdateResponse::firstName),
                new StringComponent("middleName", 25,
                        (builder, value) -> builder.middleName = value,
                        AccountUpdateResponse::middleName),
                new StringComponent("lastName", 25,
                        (builder, value) -> builder.lastName = value,
                        AccountUpdateResponse::lastName),
                new StringComponent("addressLine1", 50,
                        (builder, value) -> builder.addressLine1 = value,
                        AccountUpdateResponse::addressLine1),
                new StringComponent("stateCode", 2,
                        (builder, value) -> builder.stateCode = value,
                        AccountUpdateResponse::stateCode),
                new StringComponent("addressLine2", 50,
                        (builder, value) -> builder.addressLine2 = value,
                        AccountUpdateResponse::addressLine2),
                new StringComponent("zipCode", 5,
                        (builder, value) -> builder.zipCode = value,
                        AccountUpdateResponse::zipCode),
                new StringComponent("city", 50,
                        (builder, value) -> builder.city = value,
                        AccountUpdateResponse::city),
                new StringComponent("countryCode", 3,
                        (builder, value) -> builder.countryCode = value,
                        AccountUpdateResponse::countryCode),
                new StringComponent("phone1AreaCode", 3,
                        (builder, value) -> builder.phone1AreaCode = value,
                        AccountUpdateResponse::phone1AreaCode),
                new StringComponent("phone1Prefix", 3,
                        (builder, value) -> builder.phone1Prefix = value,
                        AccountUpdateResponse::phone1Prefix),
                new StringComponent("phone1LineNumber", 4,
                        (builder, value) -> builder.phone1LineNumber = value,
                        AccountUpdateResponse::phone1LineNumber),
                new StringComponent("governmentIssuedId", 20,
                        (builder, value) -> builder.governmentIssuedId = value,
                        AccountUpdateResponse::governmentIssuedId),
                new StringComponent("phone2AreaCode", 3,
                        (builder, value) -> builder.phone2AreaCode = value,
                        AccountUpdateResponse::phone2AreaCode),
                new StringComponent("phone2Prefix", 3,
                        (builder, value) -> builder.phone2Prefix = value,
                        AccountUpdateResponse::phone2Prefix),
                new StringComponent("phone2LineNumber", 4,
                        (builder, value) -> builder.phone2LineNumber = value,
                        AccountUpdateResponse::phone2LineNumber),
                new StringComponent("eftAccountId", 10,
                        (builder, value) -> builder.eftAccountId = value,
                        AccountUpdateResponse::eftAccountId),
                new StringComponent("primaryCardHolderIndicator", 1,
                        (builder, value) -> builder.primaryCardHolderIndicator = value,
                        AccountUpdateResponse::primaryCardHolderIndicator));
    }

    /**
     * The subset of the width table whose components actually declare a length bound: all 38 except
     * the middle name and the second address line.
     *
     * <p>Those two are excluded because the source marks them as having no edits coded
     * ({@code COACTUPC} lines 3345 and 3369) and they therefore carry no constraint of any kind. This
     * stream exists to make the contrast explicit: every <em>other</em> component's bound is real and
     * is reported, which is what gives the two exclusions their meaning rather than leaving them
     * looking like an oversight.
     *
     * @return the 36 length-bounded components
     */
    private static Stream<StringComponent> constrainedStringComponents() {
        return echoedStringComponents()
                .filter(component -> !"middleName".equals(component.name()))
                .filter(component -> !"addressLine2".equals(component.name()));
    }

    /**
     * The five monetary echoed values, whose record counterparts are the signed zoned decimals at
     * {@code app/cpy/CVACT01Y.cpy} lines 7, 8, 9, 13 and 14.
     *
     * @return the monetary components
     */
    private static Stream<MoneyComponent> monetaryComponents() {
        return Stream.of(
                new MoneyComponent("creditLimit",
                        (builder, amount) -> builder.creditLimit = amount,
                        AccountUpdateResponse::creditLimit),
                new MoneyComponent("cashCreditLimit",
                        (builder, amount) -> builder.cashCreditLimit = amount,
                        AccountUpdateResponse::cashCreditLimit),
                new MoneyComponent("currentBalance",
                        (builder, amount) -> builder.currentBalance = amount,
                        AccountUpdateResponse::currentBalance),
                new MoneyComponent("currentCycleCredit",
                        (builder, amount) -> builder.currentCycleCredit = amount,
                        AccountUpdateResponse::currentCycleCredit),
                new MoneyComponent("currentCycleDebit",
                        (builder, amount) -> builder.currentCycleDebit = amount,
                        AccountUpdateResponse::currentCycleDebit));
    }

    /**
     * The 39 decoration identities, each pairing the component name with the legacy screen field the
     * macro token names, in the program's own emission order.
     *
     * <p>That order is irregular and is recorded exactly as found rather than tidied: the state field
     * sits between the two address lines, and the postal code precedes the city and the country. The
     * final pair is the one whose source comments are transposed - the tokens govern, so the
     * primary-cardholder indicator is the expansion at line 3428 and the transfer-account id is the
     * one at 3433.
     *
     * <p>The middle name and the second address line are present and are meant to be. They are the
     * two fields the source marks as having no edits coded, and their presence here is the point of
     * this suite.
     *
     * @return component name to legacy screen field identifier, in emission order
     */
    private static Map<String, String> decorationIdentities() {
        final Map<String, String> identities = new LinkedHashMap<>();
        identities.put("accountStatus", "ACSTTUS");
        identities.put("openYear", "OPNYEAR");
        identities.put("openMonth", "OPNMON");
        identities.put("openDay", "OPNDAY");
        identities.put("creditLimit", "ACRDLIM");
        identities.put("expiryYear", "EXPYEAR");
        identities.put("expiryMonth", "EXPMON");
        identities.put("expiryDay", "EXPDAY");
        identities.put("cashCreditLimit", "ACSHLIM");
        identities.put("reissueYear", "RISYEAR");
        identities.put("reissueMonth", "RISMON");
        identities.put("reissueDay", "RISDAY");
        identities.put("currentBalance", "ACURBAL");
        identities.put("currentCycleCredit", "ACRCYCR");
        identities.put("currentCycleDebit", "ACRCYDB");
        identities.put("ssnPart1", "ACTSSN1");
        identities.put("ssnPart2", "ACTSSN2");
        identities.put("ssnPart3", "ACTSSN3");
        identities.put("dateOfBirthYear", "DOBYEAR");
        identities.put("dateOfBirthMonth", "DOBMON");
        identities.put("dateOfBirthDay", "DOBDAY");
        identities.put("ficoScore", "ACSTFCO");
        identities.put("firstName", "ACSFNAM");
        identities.put("middleName", "ACSMNAM");
        identities.put("lastName", "ACSLNAM");
        identities.put("addressLine1", "ACSADL1");
        identities.put("stateCode", "ACSSTTE");
        identities.put("addressLine2", "ACSADL2");
        identities.put("zipCode", "ACSZIPC");
        identities.put("city", "ACSCITY");
        identities.put("countryCode", "ACSCTRY");
        identities.put("phone1AreaCode", "ACSPH1A");
        identities.put("phone1Prefix", "ACSPH1B");
        identities.put("phone1LineNumber", "ACSPH1C");
        identities.put("phone2AreaCode", "ACSPH2A");
        identities.put("phone2Prefix", "ACSPH2B");
        identities.put("phone2LineNumber", "ACSPH2C");
        identities.put("primaryCardHolderIndicator", "ACSPFLG");
        identities.put("eftAccountId", "ACSEFTC");
        return identities;
    }

    /**
     * A response carrying every component, used wherever a full wire shape or a full accessor sweep
     * is needed. Every value is fictional and every identifier is chosen so none is contained within
     * another, which keeps a non-disclosure assertion from passing or failing by accident.
     *
     * @return a fully populated response
     */
    private static AccountUpdateResponse populated() {
        return new AccountUpdateResponse(
                "CAUP", "Tran Update Account", "06/10/22", "COACTUPC", "Account Update", "19:27:53",
                LEADING_ZERO_ACCOUNT_ID, "Y", "2022", "06", "10", new BigDecimal("5000.00"),
                "2027", "12", "31", new BigDecimal("1500.00"),
                "2024", "03", "15", new BigDecimal("-249.37"),
                new BigDecimal("874.10"), "ZEROAPR", new BigDecimal("312.65"), "917253869",
                "900", "55", "1234",
                "1984", "07", "22", LEADING_ZERO_FICO,
                "Marion", "Wynn Ashby", "Kingsleigh", "148 Corvid Row", "OR", "Building 4 Unit 27",
                "97205", "Portland", "USA",
                "703", "555", "0199", "FICTIONAL-GOVT-ID-01",
                "202", "555", "0187",
                "4471902856", "Y",
                "Enter your changes and press Enter to confirm", AccountUpdateResponse.MSG_LOOKS_GOOD_SO_FAR,
                false, "ACSTTUS", "/api/v1/accounts/update", populatedNavigation(),
                List.of(new ErrorResponse.FieldError("stateCode", "ACSSTTE",
                        ErrorResponse.FieldState.INVALID,
                        "OR" + AccountUpdateResponse.SUFFIX_STATE_NOT_VALID)));
    }

    /**
     * Echoed conversation state carrying a leading-zero account identifier, so the hand-off can be
     * shown to preserve it.
     *
     * @return a populated conversation context in the re-entry state
     */
    private static NavigationContext populatedNavigation() {
        return new NavigationContext(
                "CAUP", "COACTUPC", "CAUP", "COACTUPC", "USER0042", "U",
                NavigationContext.ProgramContext.REENTER,
                "917253869", "Marion", "Wynn Ashby", "Kingsleigh",
                LEADING_ZERO_ACCOUNT_ID, "Y", "4471902856000012", "CACTUPA", "COACTUP");
    }

    /**
     * The two-state per-field error contract, which is the thing this response exists to surface.
     *
     * <p>The macro at {@code app/cpy/CSSETATY.cpy} fires when a field's flag is not-OK <em>or</em>
     * blank, and it does two different things in those two cases: in both it writes a colour into the
     * field's indicator, and in the blank case only it additionally overwrites the displayed value
     * with a marker character (macro lines 21 to 25). Two operator mistakes, told apart by the screen,
     * and the remedies differ - supply a value versus correct one. Both terminal mechanisms are
     * discarded and only the two states survive.
     */
    @Nested
    @DisplayName("the two-state field-error contract")
    class FieldErrorContract {

        @Test
        @DisplayName("distinguishes a blank field from a badly filled one, and never conflates them")
        void distinguishesBlankFromBadlyFilled() {
            final ErrorResponse.FieldError missing = new ErrorResponse.FieldError(
                    "stateCode", "ACSSTTE", ErrorResponse.FieldState.MISSING);
            final ErrorResponse.FieldError invalid = new ErrorResponse.FieldError(
                    "zipCode", "ACSZIPC", ErrorResponse.FieldState.INVALID);

            final AccountUpdateResponse response = blank()
                    .error(true)
                    .fieldErrors(List.of(missing, invalid))
                    .build();

            assertThat(response.fieldErrors())
                    .extracting(ErrorResponse.FieldError::fieldName,
                            ErrorResponse.FieldError::state)
                    .containsExactly(
                            tuple("stateCode",
                                    ErrorResponse.FieldState.MISSING),
                            tuple("zipCode",
                                    ErrorResponse.FieldState.INVALID));
            assertThat(ErrorResponse.FieldState.MISSING)
                    .isNotEqualTo(ErrorResponse.FieldState.INVALID);
        }

        @Test
        @DisplayName("offers exactly two states, so no third outcome can creep in")
        void offersExactlyTwoStates() {
            // A field with no error simply has no entry, so an "OK" or "unknown" constant would be
            // unreachable state that every client would still have to handle.
            assertThat(ErrorResponse.FieldState.values())
                    .containsExactly(ErrorResponse.FieldState.MISSING,
                            ErrorResponse.FieldState.INVALID);
        }

        @Test
        @DisplayName("expresses the state as a named value, never as a boolean")
        void expressesTheStateAsANamedValue() {
            // A boolean cannot carry three facts - no error, blank, badly filled - and the legacy
            // needed all three. Serializing proves what a client receives: a name, not true/false.
            final AccountUpdateResponse response = blank()
                    .error(true)
                    .fieldErrors(List.of(new ErrorResponse.FieldError(
                            "ficoScore", "ACSTFCO", ErrorResponse.FieldState.MISSING)))
                    .build();

            final ObjectMapper mapper = moduleEquivalentMapper();
            final JsonNode state;
            try {
                state = mapper.readTree(mapper.writeValueAsString(response))
                        .get("fieldErrors").get(0).get("state");
            } catch (final JsonProcessingException failure) {
                throw new AssertionError(
                        "the response must serialize so its field-error state can be inspected",
                        failure);
            }

            assertThat(state.isTextual())
                    .withFailMessage("the field-error state must reach the client as a name, "
                            + "but it serialized as %s", state.getNodeType())
                    .isTrue();
            assertThat(state.textValue()).isEqualTo("MISSING");
            assertThat(state.isBoolean()).isFalse();
        }

        @Test
        @DisplayName("carries several independent field errors at once")
        void carriesSeveralIndependentFieldErrorsAtOnce() {
            // Proved twice in the source. The combined state-and-postal-code check sets both flags on
            // one failure (COACTUPC lines 2546 and 2547), and the telephone range always runs all
            // three of its stages (area code 2246, prefix 2316, line number 2370), so three parts of
            // one number can be flagged from a single submission.
            final AccountUpdateResponse response = blank()
                    .error(true)
                    .fieldErrors(List.of(
                            new ErrorResponse.FieldError("stateCode", "ACSSTTE",
                                    ErrorResponse.FieldState.INVALID),
                            new ErrorResponse.FieldError("zipCode", "ACSZIPC",
                                    ErrorResponse.FieldState.INVALID),
                            new ErrorResponse.FieldError("phone1AreaCode", "ACSPH1A",
                                    ErrorResponse.FieldState.MISSING),
                            new ErrorResponse.FieldError("phone1Prefix", "ACSPH1B",
                                    ErrorResponse.FieldState.MISSING),
                            new ErrorResponse.FieldError("phone1LineNumber", "ACSPH1C",
                                    ErrorResponse.FieldState.INVALID)))
                    .build();

            assertThat(response.fieldErrors()).hasSize(5);
            assertThat(response.hasFieldErrors()).isTrue();
            assertThat(response.fieldErrors())
                    .extracting(ErrorResponse.FieldError::screenFieldId)
                    .containsExactly("ACSSTTE", "ACSZIPC", "ACSPH1A", "ACSPH1B", "ACSPH1C");
        }

        @Test
        @DisplayName("keeps each entry's own explanation, so one text is not shared by all")
        void keepsEachEntrysOwnExplanation() {
            final AccountUpdateResponse response = blank()
                    .error(true)
                    .fieldErrors(List.of(
                            new ErrorResponse.FieldError("stateCode", "ACSSTTE",
                                    ErrorResponse.FieldState.INVALID,
                                    "XX" + AccountUpdateResponse.SUFFIX_STATE_NOT_VALID),
                            new ErrorResponse.FieldError("ficoScore", "ACSTFCO",
                                    ErrorResponse.FieldState.INVALID,
                                    "299" + AccountUpdateResponse.SUFFIX_FICO_OUT_OF_RANGE)))
                    .build();

            assertThat(response.fieldErrors())
                    .extracting(ErrorResponse.FieldError::message)
                    .containsExactly("XX: is not a valid state code",
                            "299: should be between 300 and 850");
        }
    }

    /**
     * The middle name and the second address line: decoratable, and never validated.
     *
     * <p>This is the pairing the whole suite is built around, and it is easy to get wrong in either
     * direction. The source marks the middle name "no edits coded" at {@code COACTUPC} line 3345 and
     * the second address line "NO EDITS CODED AS YET" at line 3369, so no rule ever fires on either
     * and neither component carries a constraint - not even a length one. But both comments sit
     * directly above a live macro expansion, at lines 3347 and 3371, so both fields are among the 39
     * decoration targets and both can be shown in error.
     *
     * <p><strong>"Unvalidated" means no rule fires. It does not mean no error can ever be
     * displayed.</strong> Suppressing either field from the error contract would silently drop a
     * state the legacy screen could reach, and constraining either would reject input the legacy
     * accepts. Both behaviours are contract, not defects.
     */
    @Nested
    @DisplayName("the two decorated-but-unvalidated fields")
    class DecoratedButUnvalidatedFields {

        @Test
        @DisplayName("shows the middle name as blank, which is the marker-character state")
        void showsTheMiddleNameAsBlank() {
            final AccountUpdateResponse response = blank()
                    .error(true)
                    .fieldErrors(List.of(new ErrorResponse.FieldError(
                            "middleName", "ACSMNAM", ErrorResponse.FieldState.MISSING)))
                    .build();

            assertThat(response.fieldErrors()).singleElement()
                    .satisfies(entry -> {
                        assertThat(entry.fieldName()).isEqualTo("middleName");
                        assertThat(entry.screenFieldId()).isEqualTo("ACSMNAM");
                        assertThat(entry.state()).isEqualTo(ErrorResponse.FieldState.MISSING);
                    });
        }

        @Test
        @DisplayName("shows the middle name as badly filled, which is the colour-only state")
        void showsTheMiddleNameAsBadlyFilled() {
            final AccountUpdateResponse response = blank()
                    .error(true)
                    .fieldErrors(List.of(new ErrorResponse.FieldError(
                            "middleName", "ACSMNAM", ErrorResponse.FieldState.INVALID)))
                    .build();

            assertThat(response.fieldErrors()).singleElement()
                    .extracting(ErrorResponse.FieldError::state)
                    .isEqualTo(ErrorResponse.FieldState.INVALID);
        }

        @Test
        @DisplayName("shows the second address line as blank")
        void showsTheSecondAddressLineAsBlank() {
            final AccountUpdateResponse response = blank()
                    .error(true)
                    .fieldErrors(List.of(new ErrorResponse.FieldError(
                            "addressLine2", "ACSADL2", ErrorResponse.FieldState.MISSING)))
                    .build();

            assertThat(response.fieldErrors()).singleElement()
                    .satisfies(entry -> {
                        assertThat(entry.fieldName()).isEqualTo("addressLine2");
                        assertThat(entry.screenFieldId()).isEqualTo("ACSADL2");
                        assertThat(entry.state()).isEqualTo(ErrorResponse.FieldState.MISSING);
                    });
        }

        @Test
        @DisplayName("shows the second address line as badly filled")
        void showsTheSecondAddressLineAsBadlyFilled() {
            final AccountUpdateResponse response = blank()
                    .error(true)
                    .fieldErrors(List.of(new ErrorResponse.FieldError(
                            "addressLine2", "ACSADL2", ErrorResponse.FieldState.INVALID)))
                    .build();

            assertThat(response.fieldErrors()).singleElement()
                    .extracting(ErrorResponse.FieldError::state)
                    .isEqualTo(ErrorResponse.FieldState.INVALID);
        }

        @Test
        @DisplayName("carries both fields in both states together, with nothing merged")
        void carriesBothFieldsInBothStatesTogether() {
            final AccountUpdateResponse response = blank()
                    .error(true)
                    .fieldErrors(List.of(
                            new ErrorResponse.FieldError("middleName", "ACSMNAM",
                                    ErrorResponse.FieldState.MISSING),
                            new ErrorResponse.FieldError("addressLine2", "ACSADL2",
                                    ErrorResponse.FieldState.INVALID)))
                    .build();

            assertThat(response.fieldErrors())
                    .extracting(ErrorResponse.FieldError::fieldName,
                            ErrorResponse.FieldError::screenFieldId,
                            ErrorResponse.FieldError::state)
                    .containsExactly(
                            tuple("middleName", "ACSMNAM",
                                    ErrorResponse.FieldState.MISSING),
                            tuple("addressLine2", "ACSADL2",
                                    ErrorResponse.FieldState.INVALID));
        }

        @Test
        @DisplayName("both appear among the 39 decoration identities")
        void bothAppearAmongTheDecorationIdentities() {
            assertThat(decorationIdentities())
                    .containsEntry("middleName", "ACSMNAM")
                    .containsEntry("addressLine2", "ACSADL2");
        }
    }

    /**
     * The first submission, which carries no field errors at all.
     *
     * <p>The macro's whole body is gated on the program-context re-enter condition at
     * {@code app/cpy/CSSETATY.cpy} line 20, so on a first pass nothing is decorated however wrong the
     * data is. This type does not evaluate that gate - the condition travels as echoed client state on
     * {@link NavigationContext} and the service decides - so all that is asserted here is that the
     * type can faithfully represent "no field errors".
     */
    @Nested
    @DisplayName("the first submission")
    class FirstSubmission {

        @Test
        @DisplayName("carries an empty field-error collection, not a null one")
        void carriesAnEmptyFieldErrorCollection() {
            final AccountUpdateResponse response = blank()
                    .navigationContext(NavigationContext.empty().withFirstEntry())
                    .build();

            assertThat(response.fieldErrors()).isNotNull().isEmpty();
            assertThat(response.hasFieldErrors()).isFalse();
        }

        @Test
        @DisplayName("is representable alongside guidance text and no error indicator")
        void isRepresentableAlongsideGuidanceText() {
            // The legacy first pass showed the informational line and left every input undecorated.
            final AccountUpdateResponse response = blank()
                    .infoMessage("Enter your changes and press Enter to confirm")
                    .navigationContext(NavigationContext.empty().withFirstEntry())
                    .build();

            assertThat(response.error()).isFalse();
            assertThat(response.errorMessage()).isNull();
            assertThat(response.fieldErrors()).isEmpty();
            assertThat(response.infoMessage())
                    .isEqualTo("Enter your changes and press Enter to confirm");
        }

        @Test
        @DisplayName("leaves the re-entry decision to the echoed state rather than deciding it")
        void leavesTheReEntryDecisionToTheEchoedState() {
            final AccountUpdateResponse first = blank()
                    .navigationContext(NavigationContext.empty().withFirstEntry())
                    .build();
            final AccountUpdateResponse reEntry = blank()
                    .navigationContext(NavigationContext.empty().withReEntry())
                    .fieldErrors(List.of(new ErrorResponse.FieldError(
                            "middleName", "ACSMNAM", ErrorResponse.FieldState.MISSING)))
                    .build();

            // The type carries whichever state it is handed; it neither infers nor overrides one.
            assertThat(first.navigationContext().reEntry()).isFalse();
            assertThat(first.fieldErrors()).isEmpty();
            assertThat(reEntry.navigationContext().reEntry()).isTrue();
            assertThat(reEntry.fieldErrors()).hasSize(1);
        }

        @Test
        @DisplayName("stays constructible with no echoed state at all")
        void staysConstructibleWithNoEchoedState() {
            final AccountUpdateResponse response = blank().build();

            assertThat(response.navigationContext()).isNull();
            assertThat(response.fieldErrors()).isEmpty();
        }
    }

    /**
     * The single summary message, and its independence from the field errors.
     *
     * <p>{@code COACTUPC} runs a first-error-wins gate: each edit paragraph writes its text only while
     * the summary slot is still empty, so five bad fields still yield one summary text - that of the
     * first failing stage in source order. The mechanical confirmation is that the program writes the
     * summary family exactly once in its whole 4,236 lines, at line 2981, and the informational family
     * at line 2979.
     */
    @Nested
    @DisplayName("the summary message")
    class SummaryMessage {

        @Test
        @DisplayName("is one text, not a list")
        void isOneTextNotAList() throws JsonProcessingException {
            final AccountUpdateResponse response = blank()
                    .error(true)
                    .errorMessage(AccountUpdateResponse.MSG_CREDIT_LIMIT_NOT_VALID)
                    .build();

            final ObjectMapper mapper = moduleEquivalentMapper();
            final JsonNode message = mapper.readTree(mapper.writeValueAsString(response))
                    .get("errorMessage");

            assertThat(message.isTextual()).isTrue();
            assertThat(message.isArray()).isFalse();
            assertThat(response.errorMessage())
                    .isEqualTo(AccountUpdateResponse.MSG_CREDIT_LIMIT_NOT_VALID);
        }

        @Test
        @DisplayName("coexists with many field errors without being multiplied by them")
        void coexistsWithManyFieldErrors() {
            final List<ErrorResponse.FieldError> flagged = new ArrayList<>();
            for (final Map.Entry<String, String> identity : decorationIdentities().entrySet()) {
                flagged.add(new ErrorResponse.FieldError(identity.getKey(), identity.getValue(),
                        ErrorResponse.FieldState.INVALID));
            }

            final AccountUpdateResponse response = blank()
                    .error(true)
                    .errorMessage(AccountUpdateResponse.SUFFIX_AREA_CODE_NOT_3_DIGITS)
                    .fieldErrors(flagged)
                    .build();

            assertThat(response.fieldErrors()).hasSize(DECORATED_FIELD_COUNT);
            assertThat(response.errorMessage())
                    .isEqualTo(AccountUpdateResponse.SUFFIX_AREA_CODE_NOT_3_DIGITS);
        }

        @Test
        @DisplayName("is not synthesised by joining the field-error texts")
        void isNotSynthesisedByJoiningTheFieldErrorTexts() {
            final AccountUpdateResponse response = blank()
                    .error(true)
                    .errorMessage(AccountUpdateResponse.MSG_CREDIT_LIMIT_NOT_VALID)
                    .fieldErrors(List.of(
                            new ErrorResponse.FieldError("creditLimit", "ACRDLIM",
                                    ErrorResponse.FieldState.INVALID, "first per-field text"),
                            new ErrorResponse.FieldError("ficoScore", "ACSTFCO",
                                    ErrorResponse.FieldState.INVALID, "second per-field text")))
                    .build();

            assertThat(response.errorMessage())
                    .isEqualTo("Credit Limit is not valid")
                    .doesNotContain("first per-field text")
                    .doesNotContain("second per-field text");
        }

        @Test
        @DisplayName("is absent, not empty, when nothing failed")
        void isAbsentWhenNothingFailed() throws JsonProcessingException {
            final AccountUpdateResponse response = blank().build();

            assertThat(response.errorMessage()).isNull();
            assertThat(wireKeysOf(response)).doesNotContain("errorMessage");
        }

        @Test
        @DisplayName("survives with its two-word spelling intact, at its measured length")
        void survivesTheTwoWordSpelling() {
            // "some one" is TWO words in the legacy text at COACTUPC line 522, and the sentence ends
            // without a full stop. Fusing the words or adding the stop would change a text operators
            // and downstream tooling match on.
            final String text = AccountUpdateResponse.MSG_RECORD_CHANGED_BEFORE_UPDATE;
            final AccountUpdateResponse response = blank().error(true).errorMessage(text).build();

            assertThat(response.errorMessage())
                    .isEqualTo("Record changed by some one else. Please review")
                    .hasSize(46)
                    .contains("some one")
                    .doesNotContain("someone");
        }

        @Test
        @DisplayName("survives with its four trailing dots intact, at its measured length")
        void survivesTheFourDotSpelling() {
            // Four dots at COACTUPC line 528, not an ellipsis and not a single stop.
            final String text = AccountUpdateResponse.MSG_LOOKS_GOOD_SO_FAR;
            final AccountUpdateResponse response = blank().errorMessage(text).build();

            assertThat(response.errorMessage())
                    .isEqualTo("Looks Good.... so far")
                    .hasSize(21)
                    .contains("Good....")
                    .doesNotContain("Good...\u0020")
                    .doesNotContain("Good.\u0020");
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
            "Credit Limit is not valid",
            "Could not lock account record for update",
            "Could not lock customer record for update",
            "Record changed by some one else. Please review",
            "Looks Good.... so far",
            ": is not a valid state code",
            ": should be between 300 and 850",
            ": Area code must be A 3 digit number.",
            "Invalid zip code for state"})
        @DisplayName("round-trips a contract text byte for byte")
        void roundTripsAContractTextByteForByte(final String text) throws JsonProcessingException {
            final ObjectMapper mapper = moduleEquivalentMapper();
            final AccountUpdateResponse original = blank().error(true).errorMessage(text).build();

            final AccountUpdateResponse revived = mapper.readValue(
                    mapper.writeValueAsString(original), AccountUpdateResponse.class);

            assertThat(revived.errorMessage()).isEqualTo(text);
            assertThat(revived.errorMessage()).hasSameSizeAs(text);
        }

        @Test
        @DisplayName("keeps every message constant at the length the source measures")
        void keepsEveryMessageConstantAtItsMeasuredLength() {
            // Each figure is the character count of the literal in COACTUPC, so a silent edit to any
            // constant - a doubled space, a fused word, a dropped dot - fails here rather than in a
            // byte-comparison gate much later.
            assertThat(AccountUpdateResponse.MSG_CREDIT_LIMIT_NOT_VALID).hasSize(25);
            assertThat(AccountUpdateResponse.MSG_COULD_NOT_HOLD_ACCOUNT_FOR_UPDATE).hasSize(40);
            assertThat(AccountUpdateResponse.MSG_COULD_NOT_HOLD_CUSTOMER_FOR_UPDATE).hasSize(41);
            assertThat(AccountUpdateResponse.MSG_RECORD_CHANGED_BEFORE_UPDATE).hasSize(46);
            assertThat(AccountUpdateResponse.MSG_LOOKS_GOOD_SO_FAR).hasSize(21);
            assertThat(AccountUpdateResponse.SUFFIX_STATE_NOT_VALID).hasSize(27);
            assertThat(AccountUpdateResponse.SUFFIX_FICO_OUT_OF_RANGE).hasSize(31);
            assertThat(AccountUpdateResponse.SUFFIX_AREA_CODE_NOT_3_DIGITS).hasSize(37);
            assertThat(AccountUpdateResponse.MSG_INVALID_ZIP_FOR_STATE).hasSize(26);
        }

        @Test
        @DisplayName("keeps the capital article and trailing stop in the area-code text")
        void keepsTheCapitalArticleInTheAreaCodeText() {
            // COACTUPC line 2272 really does read "must be A 3 digit number." - capital article,
            // trailing stop. Both are external contract and neither is corrected here.
            assertThat(AccountUpdateResponse.SUFFIX_AREA_CODE_NOT_3_DIGITS)
                    .isEqualTo(": Area code must be A 3 digit number.")
                    .endsWith(".")
                    .contains(" A 3 digit ")
                    .doesNotContain(" a 3 digit ");
        }

        @Test
        @DisplayName("keeps the postal-code text bare, with no leading separator")
        void keepsThePostalCodeTextBare() {
            // Every neighbouring text at COACTUPC lines 2503, 2523 and 2272 is a suffix appended to
            // the offending value and so begins with a separator. This one, at line 2550, does not:
            // it is a whole sentence. Prefixing it for consistency would change the wire text.
            assertThat(AccountUpdateResponse.MSG_INVALID_ZIP_FOR_STATE)
                    .isEqualTo("Invalid zip code for state")
                    .doesNotStartWith(":");
            assertThat(AccountUpdateResponse.SUFFIX_STATE_NOT_VALID).startsWith(":");
            assertThat(AccountUpdateResponse.SUFFIX_FICO_OUT_OF_RANGE).startsWith(":");
        }

        @Test
        @DisplayName("distinguishes the account and customer lock texts, which differ by one word")
        void distinguishesTheAccountAndCustomerLockTexts() {
            // Both write-failure arms raise the same condition, and only the customer arm abandons the
            // transaction (COACTUPC 4099-4101 against nothing at 4076-4081). That asymmetry belongs to
            // the service; here the two texts simply must not be collapsed into one.
            assertThat(AccountUpdateResponse.MSG_COULD_NOT_HOLD_ACCOUNT_FOR_UPDATE)
                    .isEqualTo("Could not lock account record for update");
            assertThat(AccountUpdateResponse.MSG_COULD_NOT_HOLD_CUSTOMER_FOR_UPDATE)
                    .isEqualTo("Could not lock customer record for update");
            assertThat(AccountUpdateResponse.MSG_COULD_NOT_HOLD_ACCOUNT_FOR_UPDATE)
                    .isNotEqualTo(AccountUpdateResponse.MSG_COULD_NOT_HOLD_CUSTOMER_FOR_UPDATE);
        }
    }

    /**
     * The 39-site coverage, and the immutability of the collection that carries it.
     */
    @Nested
    @DisplayName("the field-error collection")
    class FieldErrorCollection {

        @Test
        @DisplayName("holds all 39 decoration identities at once with nothing lost")
        void holdsAllDecorationIdentitiesAtOnce() {
            final Map<String, String> identities = decorationIdentities();
            final List<ErrorResponse.FieldError> flagged = new ArrayList<>();
            for (final Map.Entry<String, String> identity : identities.entrySet()) {
                flagged.add(new ErrorResponse.FieldError(identity.getKey(), identity.getValue(),
                        ErrorResponse.FieldState.INVALID,
                        identity.getKey() + " failed its edit"));
            }

            final AccountUpdateResponse response = blank()
                    .error(true)
                    .fieldErrors(flagged)
                    .build();

            assertThat(identities).hasSize(DECORATED_FIELD_COUNT);
            assertThat(response.fieldErrors()).hasSize(DECORATED_FIELD_COUNT);
            // No collision: every component name and every screen field survives exactly once.
            assertThat(response.fieldErrors())
                    .extracting(ErrorResponse.FieldError::fieldName)
                    .doesNotHaveDuplicates()
                    .containsExactlyElementsOf(identities.keySet());
            assertThat(response.fieldErrors())
                    .extracting(ErrorResponse.FieldError::screenFieldId)
                    .doesNotHaveDuplicates()
                    .containsExactlyElementsOf(identities.values());
            assertThat(response.fieldErrors())
                    .extracting(ErrorResponse.FieldError::fieldName)
                    .contains("middleName", "addressLine2");
        }

        @Test
        @DisplayName("never decorates the four editable fields the macro skips")
        void neverDecoratesTheFourSkippedFields() {
            // The mapset declares 43 unprotected fields and the program expands the macro 39 times, so
            // exactly four editable fields own no decoration site: the account id, the account group
            // id, the customer id and the government-issued id. The gap is arithmetic, not oversight.
            assertThat(EDITABLE_FIELD_COUNT - DECORATED_FIELD_COUNT).isEqualTo(4);
            assertThat(decorationIdentities().keySet())
                    .doesNotContain("accountId", "accountGroupId", "customerId",
                            "governmentIssuedId");
        }

        @Test
        @DisplayName("keeps two entries for the same field distinct rather than de-duplicating")
        void keepsTwoEntriesForTheSameFieldDistinct() {
            // Sequencing and de-duplication belong to the service. This type stores what it is given.
            final AccountUpdateResponse response = blank()
                    .error(true)
                    .fieldErrors(List.of(
                            new ErrorResponse.FieldError("zipCode", "ACSZIPC",
                                    ErrorResponse.FieldState.INVALID, "first reason"),
                            new ErrorResponse.FieldError("zipCode", "ACSZIPC",
                                    ErrorResponse.FieldState.INVALID, "second reason")))
                    .build();

            assertThat(response.fieldErrors()).hasSize(2);
            assertThat(response.fieldErrors())
                    .extracting(ErrorResponse.FieldError::message)
                    .containsExactly("first reason", "second reason");
        }

        @Test
        @DisplayName("hands back a collection that cannot be added to")
        void handsBackACollectionThatCannotBeAddedTo() {
            final AccountUpdateResponse response = blank()
                    .fieldErrors(List.of(new ErrorResponse.FieldError(
                            "stateCode", "ACSSTTE", ErrorResponse.FieldState.INVALID)))
                    .build();
            final ErrorResponse.FieldError intruder = new ErrorResponse.FieldError(
                    "city", "ACSCITY", ErrorResponse.FieldState.MISSING);

            assertThatThrownBy(() -> response.fieldErrors().add(intruder))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> response.fieldErrors().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThat(response.fieldErrors()).hasSize(1);
        }

        @Test
        @DisplayName("copies the caller's collection instead of aliasing it")
        void copiesTheCallersCollection() {
            final List<ErrorResponse.FieldError> callerOwned = new ArrayList<>();
            callerOwned.add(new ErrorResponse.FieldError("stateCode", "ACSSTTE",
                    ErrorResponse.FieldState.INVALID));

            final AccountUpdateResponse response = blank().fieldErrors(callerOwned).build();
            callerOwned.add(new ErrorResponse.FieldError("city", "ACSCITY",
                    ErrorResponse.FieldState.MISSING));
            callerOwned.clear();

            assertThat(response.fieldErrors()).hasSize(1);
            assertThat(response.fieldErrors()).singleElement()
                    .extracting(ErrorResponse.FieldError::fieldName)
                    .isEqualTo("stateCode");
        }

        @Test
        @DisplayName("rejects a null entry rather than dropping it silently")
        void rejectsANullEntry() {
            // Silently dropping one would hide an error the client has to show the operator.
            final List<ErrorResponse.FieldError> withNull = new ArrayList<>();
            withNull.add(null);

            assertThatThrownBy(() -> blank().fieldErrors(withNull).build())
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("turns an absent collection into an empty immutable one")
        void turnsAnAbsentCollectionIntoAnEmptyImmutableOne() {
            final AccountUpdateResponse response = blank().fieldErrors(null).build();
            final ErrorResponse.FieldError intruder = new ErrorResponse.FieldError(
                    "city", "ACSCITY", ErrorResponse.FieldState.MISSING);

            assertThat(response.fieldErrors()).isNotNull().isEmpty();
            assertThatThrownBy(() -> response.fieldErrors().add(intruder))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("is always present on the wire, as an empty array when there is nothing to show")
        void isAlwaysPresentOnTheWire() throws JsonProcessingException {
            final ObjectMapper mapper = moduleEquivalentMapper();
            final AccountUpdateResponse response = blank().build();

            final JsonNode document = mapper.readTree(mapper.writeValueAsString(response));

            assertThat(wireKeysOf(document)).contains("fieldErrors");
            assertThat(document.get("fieldErrors").isArray()).isTrue();
            assertThat(document.get("fieldErrors")).isEmpty();
        }
    }

    /**
     * The 38 non-monetary echoed values, carried at their declared widths and never touched.
     *
     * <p>The map's input and output groups have identical widths family for family, which is what
     * makes "echo" the right word: a value comes back shaped exactly as it went out. Legacy screen and
     * record fields are space-significant, so nothing may be trimmed, padded, folded or re-cased.
     */
    @Nested
    @DisplayName("the echoed string values")
    class EchoedStringValues {

        @ParameterizedTest(name = "{0} carries a value at its declared width")
        @MethodSource("com.carddemo.api.dto.AccountUpdateResponseTest#echoedStringComponents")
        @DisplayName("carries a value at its declared width, byte for byte")
        void carriesAValueAtItsDeclaredWidth(final StringComponent component) {
            final String atWidth = valueOfLength(component.width());

            final String readBack = component.readFrom(component.carrying(atWidth));

            assertThat(readBack).isEqualTo(atWidth).hasSize(component.width());
        }

        @ParameterizedTest(name = "{0} keeps a short value short")
        @MethodSource("com.carddemo.api.dto.AccountUpdateResponseTest#echoedStringComponents")
        @DisplayName("never pads a short value up to the declared width")
        void neverPadsAShortValueUp(final StringComponent component) {
            final String single = "A";

            final String readBack = component.readFrom(component.carrying(single));

            assertThat(readBack).isEqualTo(single).hasSize(1);
        }

        @ParameterizedTest(name = "{0} keeps its trailing space")
        @MethodSource("com.carddemo.api.dto.AccountUpdateResponseTest#echoedStringComponents")
        @DisplayName("never trims a trailing space, which the legacy field treats as data")
        void neverTrimsATrailingSpace(final StringComponent component) {
            final String padded = component.width() > 1 ? "A " : " ";

            final String readBack = component.readFrom(component.carrying(padded));

            assertThat(readBack).isEqualTo(padded).hasSameSizeAs(padded);
        }

        @ParameterizedTest(name = "{0} keeps a leading space")
        @MethodSource("com.carddemo.api.dto.AccountUpdateResponseTest#echoedStringComponents")
        @DisplayName("never trims a leading space either")
        void neverTrimsALeadingSpace(final StringComponent component) {
            final String padded = component.width() > 1 ? " A" : " ";

            assertThat(component.readFrom(component.carrying(padded))).isEqualTo(padded);
        }

        @ParameterizedTest(name = "{0} carries an absent value")
        @MethodSource("com.carddemo.api.dto.AccountUpdateResponseTest#echoedStringComponents")
        @DisplayName("tolerates an absent value, which is how a blank screen field arrives")
        void toleratesAnAbsentValue(final StringComponent component) {
            assertThat(component.readFrom(component.carrying(null))).isNull();
        }

        @ParameterizedTest(name = "{0} carries an empty value")
        @MethodSource("com.carddemo.api.dto.AccountUpdateResponseTest#echoedStringComponents")
        @DisplayName("tolerates an empty value and does not turn it into an absent one")
        void toleratesAnEmptyValue(final StringComponent component) {
            final String readBack = component.readFrom(component.carrying(""));

            assertThat(readBack).isNotNull();
            assertThat(readBack).isEmpty();
        }

        @ParameterizedTest(name = "{0} returns an over-width value whole")
        @MethodSource("com.carddemo.api.dto.AccountUpdateResponseTest#echoedStringComponents")
        @DisplayName("returns an over-width value whole, so nothing is sliced to a fixed width")
        void returnsAnOverWidthValueWhole(final StringComponent component) {
            // This is the behavioural proof that no offset arithmetic and no fixed-width parsing
            // happens here. A value one character past the declared width comes back at its own
            // length, not cut down to the map figure. Whether that length is acceptable is a
            // constraint question, answered separately, and it is never answered by truncation.
            final String overWidth = valueOfLength(component.width() + 1);

            final String readBack = component.readFrom(component.carrying(overWidth));

            assertThat(readBack).isEqualTo(overWidth).hasSize(component.width() + 1);
        }

        @ParameterizedTest(name = "{0} keeps mixed case exactly as supplied")
        @MethodSource("com.carddemo.api.dto.AccountUpdateResponseTest#echoedStringComponents")
        @DisplayName("never folds case, because folding is a service concern with its own table")
        void neverFoldsCase(final StringComponent component) {
            // Where the legacy folds case it does so through a strict 26-character table, and that
            // belongs to the service. This type re-cases nothing in either direction.
            final String mixed = component.width() >= 4 ? "aBcD" : "a";

            assertThat(component.readFrom(component.carrying(mixed))).isEqualTo(mixed);
        }
    }

    /**
     * The split fields: four dates, one social-security number and two telephone numbers.
     *
     * <p>Twenty-one sub-fields in total, and every one owns its own decoration site among the 39.
     * Merging any of them would destroy the field-level error contract, because a client told only
     * that "the date of birth is wrong" cannot highlight the month box.
     */
    @Nested
    @DisplayName("the split fields")
    class SplitFields {

        @Test
        @DisplayName("keeps the four dates as twelve separate year, month and day components")
        void keepsTheFourDatesSplit() {
            final AccountUpdateResponse response = populated();

            assertThat(response.openYear()).isEqualTo("2022");
            assertThat(response.openMonth()).isEqualTo("06");
            assertThat(response.openDay()).isEqualTo("10");
            assertThat(response.expiryYear()).isEqualTo("2027");
            assertThat(response.expiryMonth()).isEqualTo("12");
            assertThat(response.expiryDay()).isEqualTo("31");
            assertThat(response.reissueYear()).isEqualTo("2024");
            assertThat(response.reissueMonth()).isEqualTo("03");
            assertThat(response.reissueDay()).isEqualTo("15");
            assertThat(response.dateOfBirthYear()).isEqualTo("1984");
            assertThat(response.dateOfBirthMonth()).isEqualTo("07");
            assertThat(response.dateOfBirthDay()).isEqualTo("22");
        }

        @Test
        @DisplayName("never merges a date into one value, and never interprets one")
        void neverMergesOrInterpretsADate() {
            // No component holds an assembled date and no accessor returns a temporal type. Date
            // interpretation lives in the date-validation service, which reproduces an eleven-paragraph
            // cascade; a response that parsed dates would be answering a question it was not asked.
            final AccountUpdateResponse response = populated();

            assertThat(response.openYear() + "-" + response.openMonth() + "-" + response.openDay())
                    .isEqualTo("2022-06-10");
            assertThat(wireKeysOf(response, "openDate", "dateOfBirth", "expiryDate", "reissueDate"))
                    .isEmpty();
        }

        @Test
        @DisplayName("accepts a month and day the calendar rejects, because it judges nothing")
        void acceptsAMonthAndDayTheCalendarRejects() {
            // A strict temporal type would refuse these. The legacy carried whatever the operator
            // keyed and let the edit cascade decide, so this type must be able to echo it back for
            // the operator to see and correct.
            final AccountUpdateResponse response = blank().build();
            final StringComponent month = componentNamed("dateOfBirthMonth");
            final StringComponent day = componentNamed("dateOfBirthDay");

            assertThat(response.dateOfBirthMonth()).isNull();
            assertThat(month.readFrom(month.carrying("13"))).isEqualTo("13");
            assertThat(day.readFrom(day.carrying("00"))).isEqualTo("00");
        }

        @Test
        @DisplayName("keeps the social-security number as three components of 3, 2 and 4")
        void keepsTheSocialSecurityNumberSplit() {
            final AccountUpdateResponse response = populated();

            assertThat(response.ssnPart1()).isEqualTo("900").hasSize(3);
            assertThat(response.ssnPart2()).isEqualTo("55").hasSize(2);
            assertThat(response.ssnPart3()).isEqualTo("1234").hasSize(4);
            assertThat(wireKeysOf(response, "ssn", "socialSecurityNumber")).isEmpty();
        }

        @Test
        @DisplayName("keeps both telephone numbers as three components each, of 3, 3 and 4")
        void keepsBothTelephoneNumbersSplit() {
            final AccountUpdateResponse response = populated();

            assertThat(response.phone1AreaCode()).isEqualTo("703").hasSize(3);
            assertThat(response.phone1Prefix()).isEqualTo("555").hasSize(3);
            assertThat(response.phone1LineNumber()).isEqualTo("0199").hasSize(4);
            assertThat(response.phone2AreaCode()).isEqualTo("202").hasSize(3);
            assertThat(response.phone2Prefix()).isEqualTo("555").hasSize(3);
            assertThat(response.phone2LineNumber()).isEqualTo("0187").hasSize(4);
        }

        @Test
        @DisplayName("never assembles the parts into the form the record stores")
        void neverAssemblesThePartsIntoTheStoredForm() {
            // The record holds the assembled form in a fifteen-character field, as the program's own
            // note at COACTUPC line 2227 records. Assembly is the service's job; no accessor here may
            // hand a client that form, or the six parts would stop being independently correctable.
            final AccountUpdateResponse response = populated();

            assertThat(response.phone1AreaCode()).isNotEqualTo(ASSEMBLED_PHONE);
            assertThat(response.phone1Prefix()).isNotEqualTo(ASSEMBLED_PHONE);
            assertThat(response.phone1LineNumber()).isNotEqualTo(ASSEMBLED_PHONE);
            assertThat(wireKeysOf(response, "phone1", "phone2", "phoneNumber1", "phoneNumber2"))
                    .isEmpty();
        }

        @Test
        @DisplayName("carries a pre-formatted telephone value verbatim, punctuation and all")
        void carriesAPreFormattedTelephoneValueVerbatim() {
            // If a caller supplies the punctuated form, this type neither strips the parentheses and
            // hyphen nor rewrites the value: it stores bytes. Whether the value fits the component's
            // declared width is a constraint question, and the next group answers it - but the answer
            // is a violation report, never a silent edit.
            final StringComponent areaCode = componentNamed("phone1AreaCode");

            final AccountUpdateResponse response = areaCode.carrying(ASSEMBLED_PHONE);

            assertThat(areaCode.readFrom(response))
                    .isEqualTo(ASSEMBLED_PHONE)
                    .hasSize(13)
                    .contains("(")
                    .contains(")")
                    .contains("-");
        }
    }

    /**
     * Identifiers and the credit score, which stay strings so leading zeros survive.
     */
    @Nested
    @DisplayName("the identifiers")
    class Identifiers {

        @Test
        @DisplayName("keeps a leading-zero account id at its full eleven characters")
        void keepsALeadingZeroAccountId() {
            final StringComponent accountId = componentNamed("accountId");

            final String readBack = accountId.readFrom(accountId.carrying(LEADING_ZERO_ACCOUNT_ID));

            assertThat(readBack).isEqualTo("00000000001").hasSize(11).isNotEqualTo("1");
        }

        @Test
        @DisplayName("keeps a credit score of 001 as three characters")
        void keepsACreditScoreOfOhOhOne() {
            final StringComponent fico = componentNamed("ficoScore");

            final String readBack = fico.readFrom(fico.carrying(LEADING_ZERO_FICO));

            assertThat(readBack).isEqualTo("001").hasSize(3).isNotEqualTo("1");
        }

        @Test
        @DisplayName("carries every identifier through the wire without losing a leading zero")
        void carriesEveryIdentifierThroughTheWire() throws JsonProcessingException {
            final ObjectMapper mapper = moduleEquivalentMapper();
            final AccountUpdateResponse original = populated();

            final JsonNode document = mapper.readTree(mapper.writeValueAsString(original));
            final AccountUpdateResponse revived = mapper.readValue(
                    mapper.writeValueAsString(original), AccountUpdateResponse.class);

            // Textual on the wire is the mechanism: a JSON number would drop the zeros in transit.
            assertThat(document.get("accountId").isTextual()).isTrue();
            assertThat(document.get("ficoScore").isTextual()).isTrue();
            assertThat(document.get("customerId").isTextual()).isTrue();
            assertThat(document.get("accountId").textValue()).isEqualTo("00000000001");
            assertThat(revived.accountId()).isEqualTo("00000000001");
            assertThat(revived.ficoScore()).isEqualTo("001");
            assertThat(revived.customerId()).isEqualTo("917253869");
        }

        @Test
        @DisplayName("accepts a credit score outside the legacy window without complaint")
        void acceptsACreditScoreOutsideTheLegacyWindow() {
            // The inclusive 300-to-850 window is declared at COACTUPC lines 848 and 849 and enforced
            // by the ordered, first-error-wins cascade in the service. A response has to be able to
            // echo the out-of-window value back so the operator can see what was rejected.
            final StringComponent fico = componentNamed("ficoScore");

            assertThat(fico.readFrom(fico.carrying("299"))).isEqualTo("299");
            assertThat(fico.readFrom(fico.carrying("851"))).isEqualTo("851");
            assertThat(fico.readFrom(fico.carrying("300"))).isEqualTo("300");
            assertThat(fico.readFrom(fico.carrying("850"))).isEqualTo("850");
        }
    }

    /**
     * The five monetary components.
     *
     * <p>Their record counterparts are the signed zoned decimals at {@code app/cpy/CVACT01Y.cpy} lines
     * 7, 8, 9, 13 and 14: ten integer digits and two decimal places. The estate carries no rounding
     * clause on any arithmetic statement, so every legacy store into a two-decimal field truncates
     * toward zero - and that truncation is concentrated in one place elsewhere in the module. This type
     * performs no arithmetic, no scaling and no formatting, which is why it <em>refuses</em> a
     * wrongly-shaped amount instead of adjusting one.
     */
    @Nested
    @DisplayName("the monetary values")
    class MonetaryValues {

        @ParameterizedTest(name = "{0} preserves a scale of exactly two")
        @MethodSource("com.carddemo.api.dto.AccountUpdateResponseTest#monetaryComponents")
        @DisplayName("preserves a scale of exactly two")
        void preservesAScaleOfExactlyTwo(final MoneyComponent component) {
            final BigDecimal amount = new BigDecimal("1234.50");

            final BigDecimal readBack = component.readFrom(component.carrying(amount));

            assertThat(readBack).isEqualTo(amount);
            assertThat(readBack.scale()).isEqualTo(MONEY_SCALE);
            assertThat(readBack.unscaledValue()).isEqualTo(amount.unscaledValue());
        }

        @ParameterizedTest(name = "{0} carries zero")
        @MethodSource("com.carddemo.api.dto.AccountUpdateResponseTest#monetaryComponents")
        @DisplayName("carries a zero amount with its scale intact")
        void carriesAZeroAmount(final MoneyComponent component) {
            final BigDecimal zero = new BigDecimal("0.00");

            final BigDecimal readBack = component.readFrom(component.carrying(zero));

            assertThat(readBack).isEqualTo(zero);
            assertThat(readBack.scale()).isEqualTo(MONEY_SCALE);
            // Scale-aware on purpose: 0.00 and 0 compare equal numerically but are not the same value
            // on the wire, and the wire is what a parity gate reads.
            assertThat(readBack.toPlainString()).isEqualTo("0.00");
        }

        @ParameterizedTest(name = "{0} carries a negative amount")
        @MethodSource("com.carddemo.api.dto.AccountUpdateResponseTest#monetaryComponents")
        @DisplayName("carries a negative amount, because the record field is signed")
        void carriesANegativeAmount(final MoneyComponent component) {
            final BigDecimal negative = new BigDecimal("-249.37");

            final BigDecimal readBack = component.readFrom(component.carrying(negative));

            assertThat(readBack).isEqualTo(negative);
            assertThat(readBack.scale()).isEqualTo(MONEY_SCALE);
            assertThat(readBack.signum()).isNegative();
            assertThat(readBack.toPlainString()).isEqualTo("-249.37");
        }

        @ParameterizedTest(name = "{0} carries the widest amount the record admits")
        @MethodSource("com.carddemo.api.dto.AccountUpdateResponseTest#monetaryComponents")
        @DisplayName("carries the widest amount the record field can hold")
        void carriesTheWidestAmountTheRecordAdmits(final MoneyComponent component) {
            final BigDecimal widest = new BigDecimal("9999999999.99");

            final BigDecimal readBack = component.readFrom(component.carrying(widest));

            assertThat(readBack).isEqualTo(widest);
            assertThat(readBack.precision() - readBack.scale()).isEqualTo(MONEY_INTEGER_DIGITS);
        }

        @ParameterizedTest(name = "{0} tolerates an absent amount")
        @MethodSource("com.carddemo.api.dto.AccountUpdateResponseTest#monetaryComponents")
        @DisplayName("tolerates an absent amount, which is how a blank monetary field arrives")
        void toleratesAnAbsentAmount(final MoneyComponent component) {
            assertThat(component.readFrom(component.carrying(null))).isNull();
        }

        @ParameterizedTest(name = "{0} refuses an amount of the wrong scale")
        @MethodSource("com.carddemo.api.dto.AccountUpdateResponseTest#monetaryComponents")
        @DisplayName("refuses a wrongly-scaled amount rather than re-scaling it")
        void refusesAWronglyScaledAmount(final MoneyComponent component) {
            // Re-scaling is a rounding decision, and the module concentrates that decision in one
            // place because the legacy truncates toward zero where idiomatic Java would round to even
            // - a cent of difference on roughly half of all interest results. A response DTO silently
            // adjusting a monetary value would be the worst possible home for it, so a wrongly-shaped
            // amount is a fault in the caller and is reported as one.
            assertThatThrownBy(() -> component.carrying(new BigDecimal("1234.5")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(component.name());
            assertThatThrownBy(() -> component.carrying(new BigDecimal("1234.500")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(component.name());
            assertThatThrownBy(() -> component.carrying(new BigDecimal("1234")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(component.name());
        }

        @ParameterizedTest(name = "{0} refuses an amount too wide for the record field")
        @MethodSource("com.carddemo.api.dto.AccountUpdateResponseTest#monetaryComponents")
        @DisplayName("refuses an amount whose integer part outgrows the record field")
        void refusesAnAmountTooWideForTheRecordField(final MoneyComponent component) {
            assertThatThrownBy(() -> component.carrying(new BigDecimal("99999999999.99")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(component.name());
        }

        @ParameterizedTest(name = "{0} renders plainly")
        @MethodSource("com.carddemo.api.dto.AccountUpdateResponseTest#monetaryComponents")
        @DisplayName("renders as plain decimal text, never in scientific notation")
        void rendersAsPlainDecimalText(final MoneyComponent component) throws JsonProcessingException {
            // A large scale-two value is exactly where a default renderer reaches for an exponent.
            final ObjectMapper mapper = moduleEquivalentMapper();
            final AccountUpdateResponse response =
                    component.carrying(new BigDecimal("9999999999.99"));

            final String document = mapper.writeValueAsString(response);

            assertThat(document)
                    .contains("\"" + component.name() + "\":9999999999.99")
                    .doesNotContain("E+")
                    .doesNotContain("e+");
        }

        @ParameterizedTest(name = "{0} survives the wire with its scale intact")
        @MethodSource("com.carddemo.api.dto.AccountUpdateResponseTest#monetaryComponents")
        @DisplayName("survives serialization and revival with its scale intact")
        void survivesTheWireWithItsScaleIntact(final MoneyComponent component)
                throws JsonProcessingException {
            final ObjectMapper mapper = moduleEquivalentMapper();
            final BigDecimal amount = new BigDecimal("874.10");
            final AccountUpdateResponse original = component.carrying(amount);

            final AccountUpdateResponse revived = mapper.readValue(
                    mapper.writeValueAsString(original), AccountUpdateResponse.class);
            final BigDecimal readBack = component.readFrom(revived);

            // The trailing zero is the point: 874.10 must not arrive as 874.1.
            assertThat(readBack).isEqualTo(amount);
            assertThat(readBack.scale()).isEqualTo(MONEY_SCALE);
            assertThat(readBack.toPlainString()).isEqualTo("874.10");
        }

        @Test
        @DisplayName("publishes the record's scale and integer-digit budget as constants")
        void publishesTheRecordsNumericBudget() {
            assertThat(AccountUpdateResponse.MONEY_SCALE).isEqualTo(MONEY_SCALE);
            assertThat(AccountUpdateResponse.MONEY_INTEGER_DIGITS).isEqualTo(MONEY_INTEGER_DIGITS);
        }

        @Test
        @DisplayName("never repeats the offending amount in the text when it refuses one")
        void neverRepeatsTheOffendingAmountWhenItRefusesOne() {
            // The diagnostic has to name the component and the contract it broke, so a caller can fix
            // the call. It must not echo the value, because a monetary amount is regulated data and a
            // failure text travels into logs.
            assertThatThrownBy(() -> componentNamedMoney("currentBalance")
                    .carrying(new BigDecimal("-8675309.1")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("currentBalance")
                    .hasMessageNotContaining("8675309");
        }

        @Test
        @DisplayName("carries all five amounts independently, with no shared state")
        void carriesAllFiveAmountsIndependently() {
            final AccountUpdateResponse response = populated();

            assertThat(response.creditLimit()).isEqualTo(new BigDecimal("5000.00"));
            assertThat(response.cashCreditLimit()).isEqualTo(new BigDecimal("1500.00"));
            assertThat(response.currentBalance()).isEqualTo(new BigDecimal("-249.37"));
            assertThat(response.currentCycleCredit()).isEqualTo(new BigDecimal("874.10"));
            assertThat(response.currentCycleDebit()).isEqualTo(new BigDecimal("312.65"));
        }

        @Test
        @DisplayName("renders no thousands separator, sign prefix or currency symbol")
        void rendersNoEditedPresentationMask() throws JsonProcessingException {
            // The map's fifteen-character screen fields and the view map's edited mask are 3270
            // artefacts. A machine contract publishes the number, not the picture of it.
            final ObjectMapper mapper = moduleEquivalentMapper();

            final String document = mapper.writeValueAsString(
                    componentNamedMoney("creditLimit").carrying(new BigDecimal("1234567.89")));

            assertThat(document)
                    .contains("\"creditLimit\":1234567.89")
                    .doesNotContain("1,234,567.89")
                    .doesNotContain("+1234567.89")
                    .doesNotContain("$");
        }
    }

    /**
     * The control components: the echoed conversation state, the declarative route, the focus hint,
     * the explicit error indicator and the field-error collection - five in all, and no sixth.
     */
    @Nested
    @DisplayName("the control components")
    class ControlComponents {

        @Test
        @DisplayName("carries the echoed conversation state rather than re-declaring its fields")
        void carriesTheEchoedConversationState() {
            final AccountUpdateResponse response = populated();

            // The state is one component of a declared type, not seventeen loose fields copied in.
            final NavigationContext echoed = response.navigationContext();
            assertThat(echoed).isNotNull();
            assertThat(echoed.accountId()).isEqualTo(LEADING_ZERO_ACCOUNT_ID);
            assertThat(echoed.customerId()).isEqualTo("917253869");
            assertThat(echoed.userId()).isEqualTo("USER0042");
            assertThat(echoed.fromProgram()).isEqualTo("COACTUPC");
            assertThat(echoed.lastMap()).isEqualTo("CACTUPA");
            assertThat(wireKeysOf(response, "userId", "fromProgram", "lastMap", "lastMapset",
                    "programContext")).isEmpty();
        }

        @Test
        @DisplayName("keeps a leading-zero identifier intact through the echoed state on the wire")
        void keepsALeadingZeroIdentifierIntactInTheEchoedState() throws JsonProcessingException {
            final ObjectMapper mapper = moduleEquivalentMapper();
            final AccountUpdateResponse original = populated();

            final AccountUpdateResponse revived = mapper.readValue(
                    mapper.writeValueAsString(original), AccountUpdateResponse.class);

            assertThat(revived.navigationContext()).isEqualTo(original.navigationContext());
            assertThat(revived.navigationContext().accountId()).isEqualTo("00000000001");
            assertThat(revived.navigationContext().programContext())
                    .isEqualTo(NavigationContext.ProgramContext.REENTER);
        }

        @Test
        @DisplayName("carries the next route as opaque data and resolves nothing")
        void carriesTheNextRouteAsOpaqueData() throws JsonProcessingException {
            // The legacy transferred control 25 times and re-armed the next transaction 19 times.
            // Both become a value the client acts on, because there is no server-side forwarding.
            final ObjectMapper mapper = moduleEquivalentMapper();
            final AccountUpdateResponse response = blank()
                    .nextRoute("/api/v1/accounts/update")
                    .build();

            final JsonNode route = mapper.readTree(mapper.writeValueAsString(response))
                    .get("nextRoute");

            assertThat(route.isTextual()).isTrue();
            assertThat(route.isObject()).isFalse();
            assertThat(response.nextRoute()).isEqualTo("/api/v1/accounts/update");
        }

        @Test
        @DisplayName("accepts any route value, which is what proves it is not an enumerated set")
        void acceptsAnyRouteValue() throws JsonProcessingException {
            // An enumerated route would reject an unknown name, and the mapper is configured to refuse
            // an ordinal for an enumerated component. A value no route table could contain therefore
            // both revives cleanly and comes back unchanged only if the component really is a string.
            final ObjectMapper mapper = moduleEquivalentMapper();
            final String unknown = "not-a-route-any-table-could-hold-9137";

            final AccountUpdateResponse revived = mapper.readValue(
                    mapper.writeValueAsString(blank().nextRoute(unknown).build()),
                    AccountUpdateResponse.class);

            assertThat(revived.nextRoute()).isEqualTo(unknown);
        }

        @Test
        @DisplayName("carries the focus hint as a field identity only")
        void carriesTheFocusHintAsAFieldIdentityOnly() {
            // The legacy positioned the cursor by writing a sentinel into a field's generated length
            // sub-item and naming the cursor option on the send. None of that mechanism crosses the
            // boundary: the hint is the identity of the field to focus and nothing else.
            final AccountUpdateResponse response = blank().focusScreenFieldId("ACSTTUS").build();

            assertThat(response.focusScreenFieldId()).isEqualTo("ACSTTUS").hasSize(7);
            assertThat(wireKeysOf(response, "cursorPosition", "cursorRow", "cursorColumn",
                    "row", "column", "length")).isEmpty();
        }

        @Test
        @DisplayName("states the error indicator explicitly rather than deriving it")
        void statesTheErrorIndicatorExplicitly() {
            // The legacy carried a distinct condition for exactly this and consulted it separately.
            // Deriving the flag from the message or the collection would lose the state in which a
            // submission failed with a summary text but no decorated field - which is reachable, since
            // several failures write a text without setting any field flag.
            final AccountUpdateResponse flaggedWithNoFieldError = blank()
                    .error(true)
                    .errorMessage(AccountUpdateResponse.MSG_UPDATE_OF_RECORD_FAILED)
                    .build();
            final AccountUpdateResponse cleanWithGuidance = blank()
                    .error(false)
                    .infoMessage("Enter your changes and press Enter to confirm")
                    .build();

            assertThat(flaggedWithNoFieldError.error()).isTrue();
            assertThat(flaggedWithNoFieldError.fieldErrors()).isEmpty();
            assertThat(flaggedWithNoFieldError.errorMessage())
                    .isEqualTo("Update of record failed");
            assertThat(cleanWithGuidance.error()).isFalse();
            assertThat(cleanWithGuidance.infoMessage()).isNotNull();
        }

        @Test
        @DisplayName("always publishes the error indicator, even when it is down")
        void alwaysPublishesTheErrorIndicator() throws JsonProcessingException {
            // It is a primitive, so absence is not a state it can be in, and a client never has to
            // treat a missing key as "probably fine".
            final ObjectMapper mapper = moduleEquivalentMapper();

            final JsonNode document = mapper.readTree(
                    mapper.writeValueAsString(blank().error(false).build()));

            assertThat(wireKeysOf(document)).contains("error");
            assertThat(document.get("error").isBoolean()).isTrue();
            assertThat(document.get("error").booleanValue()).isFalse();
        }

        @Test
        @DisplayName("carries no sealed old-image token for a client to echo back")
        void carriesNoSealedOldImageToken() {
            // The legacy compared the freshly read records against an old image it had appended to the
            // shared communication area, which the terminal never saw. Nothing of that mechanism
            // crosses this boundary. Optimistic locking lives on the persistent entity as a version
            // attribute, the comparison lives in the update service, and a detected conflict reaches
            // a client as a summary message plus field errors - never as a value to hand back.
            assertThat(wireKeysOf(populated(),
                    "concurrencyToken", "concurrency", "oldImage", "beforeImage", "afterImage",
                    "recordImage", "imageDigest", "sealedImage", "seal", "digest", "snapshot",
                    "changeToken", "conversationToken", "continuationToken", "stateToken"))
                    .isEmpty();
        }

        @Test
        @DisplayName("names no component that could stand for a carried record image")
        void namesNoComponentThatCouldStandForACarriedRecordImage() throws JsonProcessingException {
            // Pinning the whole emitted set rather than a handful of spellings, because a differently
            // named component is exactly how a token of this kind reappears.
            final Set<String> suspects = new TreeSet<>();
            for (final String name : wireKeysOf(populated())) {
                final String lowered = name.toLowerCase(Locale.ROOT);
                if (lowered.contains("concurrency") || lowered.contains("version")
                        || lowered.contains("etag") || lowered.contains("rowversion")
                        || lowered.contains("revision") || lowered.contains("token")
                        || lowered.contains("lock") || lowered.contains("stamp")
                        || lowered.contains("image") || lowered.contains("snapshot")
                        || lowered.contains("digest") || lowered.contains("seal")) {
                    suspects.add(name);
                }
            }

            assertThat(suspects).isEmpty();
        }

        @Test
        @DisplayName("carries the six screen-metadata values the program writes on presentation")
        void carriesTheSixScreenMetadataValues() {
            final AccountUpdateResponse response = populated();

            assertThat(response.transactionName()).isEqualTo("CAUP").hasSize(4);
            assertThat(response.title01()).isEqualTo("Tran Update Account");
            assertThat(response.currentDate()).isEqualTo("06/10/22").hasSize(8);
            assertThat(response.programName()).isEqualTo("COACTUPC").hasSize(8);
            assertThat(response.title02()).isEqualTo("Account Update");
            assertThat(response.currentTime()).isEqualTo("19:27:53").hasSize(8);
        }
    }

    /**
     * What the constraint layer does, and - far more importantly - what it does not do.
     *
     * <p>A length bound is essentially the only constraint this contract admits, and two components do
     * not even carry that. Everything else the legacy checked is an ordered, first-error-wins cascade
     * whose order is itself the contract, and an annotation cannot express an order.
     */
    @Nested
    @DisplayName("the constraint layer")
    class ConstraintLayer {

        @Test
        @DisplayName("draws no violation at all from a wholly absent response")
        void drawsNoViolationFromAWhollyAbsentResponse() {
            // Nothing is mandatory. A blank screen is a legitimate presentation, and the legacy
            // carried whatever the operator had keyed - including nothing.
            final Set<ConstraintViolation<AccountUpdateResponse>> violations =
                    validator.validate(blank().build());

            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("draws no violation from an over-length middle name")
        void drawsNoViolationFromAnOverLengthMiddleName() {
            // "no edits coded" at COACTUPC line 3345. Not one rule fires on this field, so not even a
            // length bound is declared - yet the field is still decoratable, which the dedicated group
            // above proves. Contract, not a defect.
            final StringComponent middleName = componentNamed("middleName");

            final Set<ConstraintViolation<AccountUpdateResponse>> violations =
                    validator.validate(middleName.carrying(valueOfLength(200)));

            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("draws no violation from an over-length second address line")
        void drawsNoViolationFromAnOverLengthSecondAddressLine() {
            // "NO EDITS CODED AS YET" at COACTUPC line 3369, and the same reasoning applies.
            final StringComponent addressLine2 = componentNamed("addressLine2");

            final Set<ConstraintViolation<AccountUpdateResponse>> violations =
                    validator.validate(addressLine2.carrying(valueOfLength(400)));

            assertThat(violations).isEmpty();
        }

        @ParameterizedTest(name = "credit score {0} draws no violation here")
        @ValueSource(strings = {"299", "851", "000", "999", "001", "300", "850"})
        @DisplayName("draws no violation from a credit score outside the legacy window")
        void drawsNoViolationFromACreditScoreOutsideTheWindow(final String score) {
            // The inclusive 300-to-850 window is declared at COACTUPC lines 848 and 849, edited at
            // lines 2514 to 2530 and gated at 1553 to 1554. It is enforced by the ordered cascade,
            // which stops at the first failure and writes one text - behaviour a bound annotation
            // cannot express, and which would report a different message if it tried.
            final StringComponent fico = componentNamed("ficoScore");

            final Set<ConstraintViolation<AccountUpdateResponse>> violations =
                    validator.validate(fico.carrying(score));

            assertThat(violations).isEmpty();
        }

        @ParameterizedTest(name = "{0} draws no violation when absent")
        @MethodSource("com.carddemo.api.dto.AccountUpdateResponseTest#echoedStringComponents")
        @DisplayName("declares nothing mandatory, so no component draws a violation when absent")
        void declaresNothingMandatory(final StringComponent component) {
            assertThat(validator.validate(component.carrying(null))).isEmpty();
        }

        @ParameterizedTest(name = "{0} draws no violation when blank")
        @MethodSource("com.carddemo.api.dto.AccountUpdateResponseTest#echoedStringComponents")
        @DisplayName("declares no non-blank rule, so a whitespace value draws no violation")
        void declaresNoNonBlankRule(final StringComponent component) {
            assertThat(validator.validate(component.carrying(""))).isEmpty();
            assertThat(validator.validate(component.carrying(" "))).isEmpty();
        }

        @ParameterizedTest(name = "{0} draws no violation from punctuation or digits")
        @MethodSource("com.carddemo.api.dto.AccountUpdateResponseTest#echoedStringComponents")
        @DisplayName("declares no pattern rule, so any characters within the width are accepted")
        void declaresNoPatternRule(final StringComponent component) {
            // Two reasons this matters. The legacy alphabetic check blanks every letter and then tests
            // what is left, so a value with embedded spaces passes it - a pattern demanding letters
            // only would reject a name the legacy accepts. And several fields the map types as digits
            // are edited by the cascade, not by shape.
            final String awkward = component.width() >= 3 ? "A 1" : "1";

            assertThat(validator.validate(component.carrying(awkward))).isEmpty();
        }

        @Test
        @DisplayName("accepts a name carrying embedded spaces, which the legacy check lets through")
        void acceptsANameCarryingEmbeddedSpaces() {
            final AccountUpdateResponse response = populated();

            assertThat(response.middleName()).isEqualTo("Wynn Ashby").contains(" ");
            assertThat(validator.validate(response)).isEmpty();
        }

        @ParameterizedTest(name = "{0} reports one violation one character past its width")
        @MethodSource("com.carddemo.api.dto.AccountUpdateResponseTest#constrainedStringComponents")
        @DisplayName("reports a length violation one character past a declared width")
        void reportsALengthViolationOneCharacterPastTheWidth(final StringComponent component) {
            // The contrast that gives the two unconstrained components their meaning: on every other
            // component the bound is real and is reported, and it is reported rather than applied.
            final String overWidth = valueOfLength(component.width() + 1);
            final AccountUpdateResponse response = component.carrying(overWidth);

            final Set<ConstraintViolation<AccountUpdateResponse>> violations =
                    validator.validate(response);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath())
                    .hasToString(component.name());
            // Reported, never applied: the value itself is untouched.
            assertThat(component.readFrom(response)).isEqualTo(overWidth);
        }

        @ParameterizedTest(name = "{0} reports nothing exactly on its width")
        @MethodSource("com.carddemo.api.dto.AccountUpdateResponseTest#constrainedStringComponents")
        @DisplayName("reports nothing for a value sitting exactly on a declared width")
        void reportsNothingExactlyOnTheWidth(final StringComponent component) {
            assertThat(validator.validate(component.carrying(valueOfLength(component.width()))))
                    .isEmpty();
        }

        @Test
        @DisplayName("leaves the two message components bounded at their own map widths")
        void leavesTheTwoMessageComponentsBoundedAtTheirMapWidths() {
            // The map declares the summary family at 78 and the informational family at 45, and those
            // are the wire figures. The program stages each through a narrower working field first -
            // 75 characters for the summary, 40 for the informational - and those are not wire figures.
            assertThat(validator.validate(blank().errorMessage(valueOfLength(78)).build())).isEmpty();
            assertThat(validator.validate(blank().errorMessage(valueOfLength(79)).build())).hasSize(1);
            assertThat(validator.validate(blank().infoMessage(valueOfLength(45)).build())).isEmpty();
            assertThat(validator.validate(blank().infoMessage(valueOfLength(46)).build())).hasSize(1);
        }

        @Test
        @DisplayName("places no bound on the route, which is not a map field")
        void placesNoBoundOnTheRoute() {
            // The route is the one component with no measured map width, because it is not a legacy
            // field at all: the navigation service owns its vocabulary, so a length taken from the
            // mapset would be a bound invented here rather than one the screen contract supplies.
            assertThat(validator.validate(blank().nextRoute(valueOfLength(500)).build())).isEmpty();
            assertThat(validator.validate(blank().nextRoute(valueOfLength(4000)).build())).isEmpty();
        }

        @Test
        @DisplayName("reports every failing component at once rather than stopping at the first")
        void reportsEveryFailingComponentAtOnce() {
            // The first-error-wins gate is the summary message's behaviour, not the bound layer's.
            final ResponseBuilder builder = blank();
            componentNamed("stateCode").setter().accept(builder, valueOfLength(3));
            componentNamed("zipCode").setter().accept(builder, valueOfLength(6));
            componentNamed("countryCode").setter().accept(builder, valueOfLength(4));

            final Set<ConstraintViolation<AccountUpdateResponse>> violations =
                    validator.validate(builder.build());

            assertThat(violations).hasSize(3);
            assertThat(violations)
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactlyInAnyOrder("stateCode", "zipCode", "countryCode");
        }

        @Test
        @DisplayName("draws no violation from a fully populated response")
        void drawsNoViolationFromAFullyPopulatedResponse() {
            assertThat(validator.validate(populated())).isEmpty();
        }
    }

    /**
     * The wire shape: exactly what a client receives, and everything it must never receive.
     *
     * <p>Every assertion in this group reads the serialized document rather than the type's own
     * declarations. That is deliberate: what matters is what crosses the boundary, and a statement
     * about the payload is a statement a client can rely on.
     */
    @Nested
    @DisplayName("the wire shape")
    class WireShape {

        @Test
        @DisplayName("publishes exactly the 56 properties the contract declares and nothing else")
        void publishesExactlyTheContractProperties() throws JsonProcessingException {
            final Set<String> expected = new LinkedHashSet<>();
            // Six screen-metadata families, written when the screen is presented.
            expected.add("transactionName");
            expected.add("title01");
            expected.add("currentDate");
            expected.add("programName");
            expected.add("title02");
            expected.add("currentTime");
            // The 43 echoed values, in map declaration order.
            expected.add("accountId");
            expected.add("accountStatus");
            expected.add("openYear");
            expected.add("openMonth");
            expected.add("openDay");
            expected.add("creditLimit");
            expected.add("expiryYear");
            expected.add("expiryMonth");
            expected.add("expiryDay");
            expected.add("cashCreditLimit");
            expected.add("reissueYear");
            expected.add("reissueMonth");
            expected.add("reissueDay");
            expected.add("currentBalance");
            expected.add("currentCycleCredit");
            expected.add("accountGroupId");
            expected.add("currentCycleDebit");
            expected.add("customerId");
            expected.add("ssnPart1");
            expected.add("ssnPart2");
            expected.add("ssnPart3");
            expected.add("dateOfBirthYear");
            expected.add("dateOfBirthMonth");
            expected.add("dateOfBirthDay");
            expected.add("ficoScore");
            expected.add("firstName");
            expected.add("middleName");
            expected.add("lastName");
            expected.add("addressLine1");
            expected.add("stateCode");
            expected.add("addressLine2");
            expected.add("zipCode");
            expected.add("city");
            expected.add("countryCode");
            expected.add("phone1AreaCode");
            expected.add("phone1Prefix");
            expected.add("phone1LineNumber");
            expected.add("governmentIssuedId");
            expected.add("phone2AreaCode");
            expected.add("phone2Prefix");
            expected.add("phone2LineNumber");
            expected.add("eftAccountId");
            expected.add("primaryCardHolderIndicator");
            // The two message families.
            expected.add("infoMessage");
            expected.add("errorMessage");
            // The five control components.
            expected.add("error");
            expected.add("focusScreenFieldId");
            expected.add("nextRoute");
            expected.add("navigationContext");
            expected.add("fieldErrors");

            assertThat(wireKeysOf(populated()))
                    .containsExactlyInAnyOrderElementsOf(expected);
            assertThat(expected).hasSize(56);
            assertThat(expected)
                    .contains("middleName", "addressLine2")
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("publishes none of the three function-key legends")
        void publishesNoneOfTheThreeFunctionKeyLegends() {
            // The map's last three families, at widths 21, 7 and 10, told an operator which keys the
            // screen honoured. A client learns the same thing from the published interface description,
            // so carrying them would put screen decoration on a machine contract.
            assertThat(wireKeysOf(populated(),
                    "fkeys", "fkey05", "fkey12", "functionKeys", "functionKeyLegend",
                    "keyLegend", "pfKeys", "pfKeyLegend", "fkeyLine"))
                    .isEmpty();
        }

        @Test
        @DisplayName("publishes no terminal plumbing of any kind")
        void publishesNoTerminalPlumbing() {
            // The generated symbolic map gives every field a length, flag, attribute, colour,
            // highlight, protection and validation sub-item, and prefixes the whole group with a
            // twelve-byte terminal input-output area filler. None of it has remote meaning, and
            // modelling any of it would leak a presentation mechanism into a data contract.
            assertThat(wireKeysOf(populated(),
                    "filler", "tioa", "tioaFiller", "attribute", "attributeByte", "colour",
                    "color", "highlight", "protection", "validation", "mapName", "mapsetName",
                    "cursor", "row", "column", "line", "position", "screenRow", "screenColumn",
                    "dfhred", "dfhgreen", "marker", "mask", "editMask", "pictureMask"))
                    .isEmpty();
        }

        @Test
        @DisplayName("publishes no legacy map field name, because the contract uses its own names")
        void publishesNoLegacyMapFieldName() {
            // The screen field identifiers travel as data on the error entries, where they let a
            // response be correlated with the map it derives from. They are never property names.
            final Set<String> mapNames = new LinkedHashSet<>(decorationIdentities().values());
            mapNames.add("ACCTSID");
            mapNames.add("AADDGRP");
            mapNames.add("ACSTNUM");
            mapNames.add("ACSGOVT");

            assertThat(wireKeysOf(populated(), mapNames.toArray(new String[0]))).isEmpty();
        }

        @Test
        @DisplayName("publishes no locking, versioning or entity-tag token")
        void publishesNoLockingOrVersioningToken() {
            // Optimistic locking lives on the persistent entity, and the write path's own asymmetry -
            // only the customer arm abandons the transaction, at COACTUPC 4099 to 4101, where the
            // account arm at 4076 to 4081 abandons nothing - is the service's concern. A conflict
            // reaches a client as a summary message plus field errors, never as a token to echo.
            assertThat(wireKeysOf(populated(),
                    "concurrencyToken", "version", "rowVersion", "recordVersion", "lockVersion",
                    "etag", "eTag", "optimisticLock", "optimisticLockVersion", "lock", "lockToken",
                    "revision", "timestamp", "lastModified"))
                    .isEmpty();
        }

        @Test
        @DisplayName("publishes no numeric token that a version could hide inside")
        void publishesNoNumericTokenAVersionCouldHideInside() throws JsonProcessingException {
            // The only integral-looking values on the wire are the five amounts, and they are decimals.
            // Everything else numeric would be a candidate hiding place for a version counter.
            final ObjectMapper mapper = moduleEquivalentMapper();
            final JsonNode document = mapper.readTree(mapper.writeValueAsString(populated()));
            final Set<String> integralKeys = new TreeSet<>();
            for (final Map.Entry<String, JsonNode> property : document.properties()) {
                if (property.getValue().isIntegralNumber()) {
                    integralKeys.add(property.getKey());
                }
            }

            assertThat(integralKeys).isEmpty();
        }

        @Test
        @DisplayName("is not a problem document, because that representation is deliberately off")
        void isNotAProblemDocument() {
            // The standard problem-detail representation is not enabled for this module, so this type
            // is neither a wrapper for it nor a stand-in.
            assertThat(wireKeysOf(populated(),
                    "type", "title", "status", "detail", "instance", "problem"))
                    .isEmpty();
        }

        @Test
        @DisplayName("omits every absent property rather than emitting a null")
        void omitsEveryAbsentProperty() throws JsonProcessingException {
            final AccountUpdateResponse response = blank().build();

            final Set<String> keys = wireKeysOf(response);

            // Only the primitive indicator and the always-present collection survive.
            assertThat(keys).containsExactlyInAnyOrder("error", "fieldErrors");
            assertThat(moduleEquivalentMapper().writeValueAsString(response))
                    .doesNotContain("null");
        }

        @Test
        @DisplayName("tolerates an unknown incoming property instead of refusing the body")
        void toleratesAnUnknownIncomingProperty() throws JsonProcessingException {
            final ObjectMapper mapper = moduleEquivalentMapper();
            final String body = """
                    {"accountId":"00000000001","ficoScore":"001","error":false,\
                    "fkeys":"F3=Exit F5=Save","someFieldNobodyDeclared":"ignored",\
                    "version":7}""";

            final AccountUpdateResponse revived =
                    mapper.readValue(body, AccountUpdateResponse.class);

            assertThat(revived.accountId()).isEqualTo("00000000001");
            assertThat(revived.ficoScore()).isEqualTo("001");
            assertThat(revived.error()).isFalse();
            assertThat(revived.fieldErrors()).isEmpty();
        }

        @Test
        @DisplayName("survives a full round trip unchanged and compares by value")
        void survivesAFullRoundTripUnchanged() throws JsonProcessingException {
            final ObjectMapper mapper = moduleEquivalentMapper();
            final AccountUpdateResponse original = populated();

            final AccountUpdateResponse revived = mapper.readValue(
                    mapper.writeValueAsString(original), AccountUpdateResponse.class);

            assertThat(revived).isEqualTo(original);
            assertThat(revived).hasSameHashCodeAs(original);
            assertThat(revived).isNotSameAs(original);
        }

        @Test
        @DisplayName("compares unequal when a single component differs")
        void comparesUnequalWhenASingleComponentDiffers() {
            final AccountUpdateResponse original = populated();
            final AccountUpdateResponse altered = componentNamed("ficoScore").carrying("850");

            assertThat(altered).isNotEqualTo(original);
            assertThat(blank().build()).isEqualTo(blank().build());
            assertThat(blank().build()).hasSameHashCodeAs(blank().build());
        }
    }

    /**
     * Immutability and diagnostic rendering, both shown by construction and by observed behaviour.
     */
    @Nested
    @DisplayName("immutability and diagnostics")
    class ImmutabilityAndDiagnostics {

        @Test
        @DisplayName("hands back the same values on every read, so nothing shifts underneath")
        void handsBackTheSameValuesOnEveryRead() {
            final AccountUpdateResponse response = populated();

            assertThat(response.accountId()).isEqualTo(response.accountId());
            assertThat(response.creditLimit()).isEqualTo(response.creditLimit());
            assertThat(response.fieldErrors()).isEqualTo(response.fieldErrors());
            assertThat(response.navigationContext()).isEqualTo(response.navigationContext());
        }

        @Test
        @DisplayName("offers no way to change a component after construction")
        void offersNoWayToChangeAComponentAfterConstruction() {
            // Shown by construction rather than by inspecting the type: the only route to a different
            // value is a new instance, and the original is unaffected by building one.
            final AccountUpdateResponse original = componentNamed("ficoScore").carrying("300");
            final AccountUpdateResponse other = componentNamed("ficoScore").carrying("850");

            assertThat(original.ficoScore()).isEqualTo("300");
            assertThat(other.ficoScore()).isEqualTo("850");
            assertThat(original).isNotEqualTo(other);
        }

        @Test
        @DisplayName("keeps the echoed state unaffected when a new one is derived from it")
        void keepsTheEchoedStateUnaffectedWhenANewOneIsDerived() {
            final AccountUpdateResponse response = populated();
            final NavigationContext echoed = response.navigationContext();

            final NavigationContext derived = echoed.withFirstEntry();

            assertThat(derived.reEntry()).isFalse();
            assertThat(response.navigationContext().reEntry()).isTrue();
            assertThat(response.navigationContext()).isSameAs(echoed);
        }

        @Test
        @DisplayName("withholds every regulated value from its diagnostic text")
        void withholdsEveryRegulatedValueFromItsDiagnosticText() {
            // A diagnostic text travels into logs, so it must carry no identifier, no name, no
            // address and no monetary amount.
            final String rendered = populated().toString();

            assertThat(rendered)
                    .doesNotContain(LEADING_ZERO_ACCOUNT_ID)
                    .doesNotContain("917253869")
                    .doesNotContain("900")
                    .doesNotContain("1234")
                    .doesNotContain("Kingsleigh")
                    .doesNotContain("Wynn Ashby")
                    .doesNotContain("148 Corvid Row")
                    .doesNotContain("FICTIONAL-GOVT-ID-01")
                    .doesNotContain("5000.00")
                    .doesNotContain("-249.37");
        }

        @Test
        @DisplayName("retains the control state a reader needs, including the flagged-field count")
        void retainsTheControlStateAReaderNeeds() {
            final AccountUpdateResponse response = populated();

            assertThat(response.toString())
                    .contains("error=false")
                    .contains("Looks Good.... so far")
                    .contains("focusScreenFieldId=ACSTTUS")
                    .contains("fieldErrorCount=1")
                    .contains("nextRoute=/api/v1/accounts/update");
        }

        @Test
        @DisplayName("renders a diagnostic text for a wholly absent response without failing")
        void rendersADiagnosticTextForAWhollyAbsentResponse() {
            assertThat(blank().build().toString())
                    .contains("error=false")
                    .contains("fieldErrorCount=0");
        }
    }
}
