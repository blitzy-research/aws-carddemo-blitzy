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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link AccountUpdateRequest}, the inbound contract for legacy CICS transaction
 * {@code CAUP}.
 *
 * <h2>What this class is for</h2>
 *
 * <p>The legacy account-update program runs a <strong>first-error-wins</strong> validation cascade:
 * every edit stage is gated on the summary-message slot still being empty, so a submission with
 * five bad fields yields exactly one summary message - the message of the first failing stage in
 * source order - alongside as many independently set field flags as there are bad fields. Bean
 * Validation evaluates constraints in an unspecified order and would report all of them at once
 * under an arbitrary message, which is a different externally observable contract. The ordered
 * cascade therefore lives in the service layer, and this request must <em>tolerate</em> null, blank
 * and out-of-range input rather than reject it.
 *
 * <p>That makes the interesting property of this type the constraints it does <strong>not</strong>
 * carry. A test that only checked happy-path getters would pass while a well-meaning future edit
 * silently added a bound and broke parity. Every assertion below therefore either proves a value
 * is carried untouched or proves that no rule fired on it, and two of them are contrast proofs that
 * would fail if the constraint inventory ever drifted.
 *
 * <h2>Provenance</h2>
 *
 * <ul>
 *   <li>Program {@code app/cbl/COACTUPC.cbl} - 4,236 lines, the largest single translation in the
 *       estate.</li>
 *   <li>Symbolic map {@code app/cpy-bms/COACTUP.CPY} - 54 input families and 54 output families
 *       with no width disagreement between the two views.</li>
 *   <li>Mapset {@code app/bms/COACTUP.bms} - 512 lines, declaring 43 of those 54 families
 *       unprotected.</li>
 *   <li>Record layouts {@code app/cpy/CVACT01Y.cpy} (account, 300 bytes) and
 *       {@code app/cpy/CVCUS01Y.cpy} (customer, 500 bytes) for the persisted types.</li>
 *   <li>Validation tables {@code app/cpy/CSLKPCDY.cpy}; date cascade
 *       {@code app/cpy/CSUTLDPY.cpy} (14 paragraphs).</li>
 *   <li>Checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}; upstream release stamp
 *       {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Recorded here as documentation
 *       only - the stamp is not universal across the estate and is never compiled into a
 *       constant.</li>
 * </ul>
 *
 * <h2>The 54 to 43 to 39 arithmetic</h2>
 *
 * <p>Eleven of the 54 map families are non-editable screen furniture and are absent from this
 * request: the transaction name, both title lines, the current date, the program name, the current
 * time, the information message, the error message and three function-key legends. 54 minus 11
 * leaves the <strong>43</strong> components exercised here - 38 bounded strings and 5 exact
 * decimals.
 *
 * <p>Of those 43, <strong>39</strong> are error-decoration targets, so 43 minus 39 leaves exactly
 * <strong>four editable-but-undecorated</strong> fields: the account id, the account group id, the
 * customer id and the government-issued id. They are genuine inputs that simply never receive
 * field-level decoration, and {@link StringComponent#UNDECORATED} pins that set down.
 *
 * <h2>Lookup cardinalities - documentation, never asserted here</h2>
 *
 * <p>The validation tables hold 490 telephone area codes, being 410 general-purpose plus 80
 * easily-recognisable codes in a disjoint partition, 56 state codes, and 240 state-plus-postal-
 * prefix combinations. Six of those 240 use prefixes that are absent from the 56-code list, so the
 * two lists must never be intersected. Those sets live under
 * {@code src/main/resources/lookup/} and are asserted by the gate verification test; no lookup
 * resource is loaded here and no cardinality is asserted here.
 *
 * <h2>Contract text composed elsewhere</h2>
 *
 * <p>The following externally observable literals belong to the service and the response contract
 * and are recorded only so a reader can find the enforcement site rather than assume this request
 * lost a constraint. Measured lengths are given because trailing punctuation and word spacing are
 * contractual.
 *
 * <ul>
 *   <li>Credit score window, line 2523: {@code : should be between 300 and 850} - 31 characters,
 *       leading colon-space, no trailing period. Enforced by paragraph
 *       {@code 1275-EDIT-FICO-SCORE} at lines 2514-2530 over the condition name declared at lines
 *       848-849 and gated at lines 1553-1554.</li>
 *   <li>State membership, line 2503: {@code : is not a valid state code} - 27 characters, from
 *       paragraph {@code 1270-EDIT-US-STATE-CD} at lines 2493-2510.</li>
 *   <li>State-and-postal combination, line 2550: {@code Invalid zip code for state} - 26
 *       characters and <strong>bare</strong>, carrying no field-name prefix, from paragraph
 *       {@code 1280-EDIT-US-STATE-ZIP-CD} at lines 2536-2557.</li>
 *   <li>Telephone stages, from paragraph {@code 1260-EDIT-US-PHONE-NUM} at lines 2225-2427: line
 *       2254 at 29 characters, line 2272 {@code : Area code must be A 3 digit number.} at 37
 *       characters with a capital {@code A} and a trailing period, line 2286 at 26 characters and
 *       line 2306 at 51 characters.</li>
 *   <li>Service outcomes: line 508 {@code Credit Limit is not valid} at 25, line 510 at 42, line
 *       512 at 24, line 514 at 43, line 516 at 44, line 518
 *       {@code Could not lock account record for update} at 40, line 520
 *       {@code Could not lock customer record for update} at 41, line 522
 *       {@code Record changed by some one else. Please review} at 46 - two words, not one - line
 *       524 at 23, line 526 at 28 and line 528 {@code Looks Good.... so far} at 21 with four
 *       dots.</li>
 * </ul>
 *
 * <h2>Independent oracles</h2>
 *
 * <p>No expected value here is produced by the type under test or by any production collaborator.
 * Every declared width in {@link StringComponent} and {@link MoneyComponent} was read from the
 * symbolic map, not from the request. The only helpers used to build an expectation are the
 * platform's own {@link String#repeat(int)} and string concatenation, which produce literal runs
 * rather than compute anything. The zoned-decimal codec is deliberately not imported: it owns
 * truncation for the whole module and using it to generate an expectation would make this test
 * agree with a defect rather than detect one.
 *
 * <h2>Byte-exact, never trimmed</h2>
 *
 * <p>Leading, interior and trailing spaces are contractual data on a 3270 screen, so no comparison
 * here trims, strips, case-folds or normalises either side. Nothing in this class parses a date,
 * assembles a telephone number, concatenates a lookup key, slices a fixed-width image or rescales
 * a decimal.
 */
@DisplayName("AccountUpdateRequest - the CAUP inbound contract")
class AccountUpdateRequestTest {

    /**
     * Shared factory for the whole class. {@link ValidatorFactory} is expensive to build and is
     * closed once in {@link #closeValidatorFactory()}. This is the reference implementation
     * obtained straight from the specification's bootstrap entry point, never a framework-managed
     * validator bean, because this is a pure unit test with no application context.
     */
    private static ValidatorFactory validatorFactory;

    /** Validator drawn from {@link #validatorFactory}. */
    private static Validator validator;

    /**
     * Mapper mirroring the module's serialisation settings by hand: null-valued properties omitted,
     * date-as-timestamp serialisation off, unknown incoming properties tolerated and decimals
     * written in plain notation. Built locally and per-class rather than injected, so the test
     * cannot accidentally depend on framework auto-configuration.
     *
     * <p>The builder form matters: the single-argument inclusion setter is deprecated in the
     * pinned databind release and the module compiles with warnings promoted to errors, so the
     * value-based setter is used instead.
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .defaultPropertyInclusion(JsonInclude.Value.construct(
                    JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    @BeforeAll
    static void openValidatorFactory() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }

    private static Set<ConstraintViolation<AccountUpdateRequest>> violations(
            AccountUpdateRequest request) {
        return validator.validate(request);
    }

    /**
     * Right-pads with spaces to an exact screen width. A 3270 field is always transmitted at its
     * declared width, so this builds realistic <em>input</em>; it never touches a value being
     * asserted. Passing a value longer than the width fails fast, which keeps the seed data below
     * honest about the widths it claims.
     */
    private static String padded(String value, int width) {
        return value + " ".repeat(width - value.length());
    }

    /* =================================================================================
     * Staging holder.
     *
     * The request has 43 components, so the canonical constructor takes 43 arguments.
     * Writing that call out at every test site would be unreadable and would invite a
     * silent argument transposition, so the call appears EXACTLY ONCE, in build().
     * Tests assign only the fields they care about and leave the rest null, which is
     * itself faithful: an operator who tabs past a 3270 field transmits nothing for it.
     *
     * This is a hand-written holder. There is no builder library, no annotation
     * processor and no code generation of any kind, and nothing here reads or writes a
     * field by name at run time - every assignment below is an ordinary typed one.
     * ================================================================================= */
    static final class Draft {

        String accountId;
        String accountStatus;
        String openYear;
        String openMonth;
        String openDay;
        BigDecimal creditLimit;
        String expiryYear;
        String expiryMonth;
        String expiryDay;
        BigDecimal cashCreditLimit;
        String reissueYear;
        String reissueMonth;
        String reissueDay;
        BigDecimal currentBalance;
        BigDecimal currentCycleCredit;
        String accountGroupId;
        BigDecimal currentCycleDebit;
        String customerId;
        String ssnPart1;
        String ssnPart2;
        String ssnPart3;
        String dateOfBirthYear;
        String dateOfBirthMonth;
        String dateOfBirthDay;
        String ficoScore;
        String firstName;
        String middleName;
        String lastName;
        String addressLine1;
        String stateCode;
        String addressLine2;
        String zipCode;
        String city;
        String countryCode;
        String phone1AreaCode;
        String phone1Prefix;
        String phone1LineNumber;
        String governmentIssuedId;
        String phone2AreaCode;
        String phone2Prefix;
        String phone2LineNumber;
        String eftAccountId;
        String primaryCardHolderIndicator;

        /**
         * The one and only invocation of the 43-argument canonical constructor. Argument order
         * follows the symbolic map's declaration order, which interleaves the account group id
         * between two of the monetary components and places the three date-of-birth parts before
         * the credit score.
         */
        AccountUpdateRequest build() {
            return new AccountUpdateRequest(
                    accountId, accountStatus, openYear, openMonth, openDay,
                    creditLimit,
                    expiryYear, expiryMonth, expiryDay,
                    cashCreditLimit,
                    reissueYear, reissueMonth, reissueDay,
                    currentBalance, currentCycleCredit, accountGroupId, currentCycleDebit,
                    customerId, ssnPart1, ssnPart2, ssnPart3,
                    dateOfBirthYear, dateOfBirthMonth, dateOfBirthDay,
                    ficoScore,
                    firstName, middleName, lastName, addressLine1, stateCode, addressLine2,
                    zipCode, city, countryCode,
                    phone1AreaCode, phone1Prefix, phone1LineNumber,
                    governmentIssuedId,
                    phone2AreaCode, phone2Prefix, phone2LineNumber,
                    eftAccountId, primaryCardHolderIndicator);
        }

        /**
         * Every one of the 43 components populated with realistic terminal input at its exact
         * declared width. Deliberate properties of this seed data:
         *
         * <ul>
         *   <li>The names carry <strong>embedded spaces</strong>, which the legacy alphabetic
         *       check accepts.</li>
         *   <li>The account group id and the padded text fields carry <strong>trailing
         *       spaces</strong>, which must survive untrimmed.</li>
         *   <li>The monetary components span positive, negative, zero and the full ten-integer-
         *       digit width, all at scale 2.</li>
         *   <li>The social-security parts use a number range that is never issued and the
         *       government-issued id is self-evidently invented, so no real identifier and no
         *       credential of any kind appears in this file.</li>
         *   <li>The middle name and the second address line are deliberately punctuated and are
         *       <strong>not</strong> padded to a width, because no width applies to them.</li>
         * </ul>
         */
        static Draft realistic() {
            Draft draft = new Draft();
            draft.accountId = "00000000011";
            draft.accountStatus = "Y";
            draft.openYear = "2020";
            draft.openMonth = "01";
            draft.openDay = "15";
            draft.creditLimit = new BigDecimal("5000.00");
            draft.expiryYear = "2027";
            draft.expiryMonth = "12";
            draft.expiryDay = "31";
            draft.cashCreditLimit = new BigDecimal("1500.00");
            draft.reissueYear = "2024";
            draft.reissueMonth = "06";
            draft.reissueDay = "30";
            draft.currentBalance = new BigDecimal("-250.75");
            draft.currentCycleCredit = new BigDecimal("0.00");
            draft.accountGroupId = padded("DEFAULT", 10);
            draft.currentCycleDebit = new BigDecimal("1234567890.12");
            draft.customerId = "000000011";
            draft.ssnPart1 = "999";
            draft.ssnPart2 = "88";
            draft.ssnPart3 = "7777";
            draft.dateOfBirthYear = "1985";
            draft.dateOfBirthMonth = "07";
            draft.dateOfBirthDay = "04";
            draft.ficoScore = "742";
            draft.firstName = padded("MARY ANN", 25);
            draft.middleName = "Q. Ann-Marie 3rd, Jr.";
            draft.lastName = padded("Aniya Von", 25);
            draft.addressLine1 = padded("1500 Woodward Avenue", 50);
            draft.stateCode = "MI";
            draft.addressLine2 = "Apt. 4B / Bldg #7, c/o D'Angelo & Sons";
            draft.zipCode = "48226";
            draft.city = padded("Detroit", 50);
            draft.countryCode = "USA";
            draft.phone1AreaCode = "313";
            draft.phone1Prefix = "555";
            draft.phone1LineNumber = "0100";
            draft.governmentIssuedId = padded("FICTIONAL-ID-0000001", 20);
            draft.phone2AreaCode = "248";
            draft.phone2Prefix = "555";
            draft.phone2LineNumber = "0199";
            draft.eftAccountId = "EFT0000001";
            draft.primaryCardHolderIndicator = "Y";
            return draft;
        }
    }

    /**
     * Convenience for the common shape "one string component populated, the other 42 absent".
     */
    private static AccountUpdateRequest withOnly(StringComponent component, String value) {
        Draft draft = new Draft();
        component.write(draft, value);
        return draft.build();
    }

    /**
     * Convenience for the common shape "one monetary component populated, the other 42 absent".
     */
    private static AccountUpdateRequest withOnly(MoneyComponent component, BigDecimal value) {
        Draft draft = new Draft();
        component.write(draft, value);
        return draft.build();
    }

    @Nested
    @DisplayName("No declarative constraint pre-empts the ordered cascade")
    class NoConstraintPreemptsTheOrderedCascade {

        @Test
        @DisplayName("all 43 components absent draws no violation, so nothing is mandatory")
        void allFortyThreeComponentsAbsentDrawsNoViolation() {
            AccountUpdateRequest empty = new Draft().build();

            assertThat(violations(empty))
                    .as("an entirely empty submission must reach the service intact so the "
                            + "first-error-wins cascade can choose the single summary message")
                    .isEmpty();
        }

        @Test
        @DisplayName("all 43 components blank draws no violation, so nothing is non-blank")
        void allFortyThreeComponentsBlankDrawsNoViolation() {
            Draft draft = new Draft();
            for (StringComponent component : StringComponent.values()) {
                component.write(draft, "");
            }
            for (MoneyComponent component : MoneyComponent.values()) {
                component.write(draft, new BigDecimal("0.00"));
            }

            assertThat(violations(draft.build()))
                    .as("a blank string field and a zero amount are both legitimate terminal "
                            + "input; the required-versus-optional distinction belongs to the "
                            + "service, not to this contract")
                    .isEmpty();
        }

        @Test
        @DisplayName("a fully populated realistic submission draws no violation")
        void fullyPopulatedRealisticSubmissionDrawsNoViolation() {
            assertThat(violations(Draft.realistic().build())).isEmpty();
        }

        @ParameterizedTest(name = "{0} tolerates an absent value")
        @EnumSource(StringComponent.class)
        @DisplayName("every string component independently tolerates being absent")
        void everyStringComponentToleratesAbsence(StringComponent component) {
            Draft draft = Draft.realistic();
            component.write(draft, null);

            assertThat(violations(draft.build()))
                    .as("clearing %s must not raise a violation", component.described())
                    .isEmpty();
        }

        @ParameterizedTest(name = "{0} tolerates a blank value")
        @EnumSource(StringComponent.class)
        @DisplayName("every string component independently tolerates being blank")
        void everyStringComponentToleratesBlank(StringComponent component) {
            Draft draft = Draft.realistic();
            component.write(draft, "");

            assertThat(violations(draft.build()))
                    .as("blanking %s must not raise a violation", component.described())
                    .isEmpty();
        }

        @ParameterizedTest(name = "{0} tolerates an absent amount")
        @EnumSource(MoneyComponent.class)
        @DisplayName("every monetary component independently tolerates being absent")
        void everyMonetaryComponentToleratesAbsence(MoneyComponent component) {
            Draft draft = Draft.realistic();
            component.write(draft, null);

            assertThat(violations(draft.build()))
                    .as("clearing %s must not raise a violation", component.described())
                    .isEmpty();
        }

        /**
         * Contrast proof. Without this, "no violation was raised" could equally mean the width
         * constraints are missing everywhere and the suite is asserting nothing. Here each of the
         * 36 annotated string components is pushed one character past its declared width and must
         * raise exactly one violation naming itself - which establishes that width enforcement is
         * genuinely active, and therefore that its deliberate absence on the other two components
         * is a real, load-bearing property rather than a vacuous one.
         */
        @ParameterizedTest(name = "{0} rejects one character past its declared width")
        @EnumSource(value = StringComponent.class, mode = EnumSource.Mode.EXCLUDE,
                names = {"MIDDLE_NAME", "ADDRESS_LINE_2"})
        @DisplayName("each width-bounded component rejects exactly one character too many")
        void eachWidthBoundedComponentRejectsOneCharacterTooMany(StringComponent component) {
            AccountUpdateRequest request = withOnly(component, component.overWidthValue());

            Set<ConstraintViolation<AccountUpdateRequest>> raised = violations(request);

            assertThat(raised)
                    .as("%s carries a width constraint, so one character past its width must "
                            + "raise precisely one violation", component.described())
                    .hasSize(1);
            assertThat(raised.iterator().next().getPropertyPath())
                    .hasToString(component.jsonProperty());
        }

        @Test
        @DisplayName("the two unvalidated components are the only ones without a width bound")
        void theTwoUnvalidatedComponentsAreTheOnlyOnesWithoutAWidthBound() {
            List<StringComponent> unbounded = new ArrayList<>();
            for (StringComponent component : StringComponent.values()) {
                if (violations(withOnly(component, component.overWidthValue())).isEmpty()) {
                    unbounded.add(component);
                }
            }

            assertThat(unbounded)
                    .as("exactly two components accept a value past their declared width")
                    .containsExactlyInAnyOrderElementsOf(StringComponent.UNVALIDATED);
        }

        @Test
        @DisplayName("no monetary component carries a bound, a digit rule or a scale rule")
        void noMonetaryComponentCarriesABoundADigitRuleOrAScaleRule() {
            Draft draft = new Draft();
            BigDecimal farBeyondAnyBusinessRange = new BigDecimal("99999999999999999999.99");
            for (MoneyComponent component : MoneyComponent.values()) {
                component.write(draft, farBeyondAnyBusinessRange);
            }

            assertThat(violations(draft.build()))
                    .as("the credit-limit rule is the service's; the request only transports the "
                            + "value so the cascade can emit its own single message")
                    .isEmpty();
        }

        @Test
        @DisplayName("a negative amount is transported rather than rejected")
        void negativeAmountIsTransportedRatherThanRejected() {
            Draft draft = new Draft();
            for (MoneyComponent component : MoneyComponent.values()) {
                component.write(draft, new BigDecimal("-1234567890.12"));
            }

            assertThat(violations(draft.build())).isEmpty();
        }
    }

    /**
     * The middle name and the second address line are decorated for error display but are never
     * validated. The program says so in its own comments - line 3345 for the middle name and line
     * 3369 for the second address line, with a related note at line 3124 - and measurement bears
     * it out: the second address line's validation flag is declared at line 295 and consumed by
     * the decoration at line 3370 but is never assigned anywhere in the 4,236 lines, and the
     * statement that would set its error label is commented out at line 1614 as optional. The
     * middle name does pass through the <em>optional</em> alphabetic stage at lines 1568-1574,
     * whose flag line 3110 reads for cursor placement, but an optional stage accepts blank values
     * and accepts embedded spaces, which no declarative constraint can express while leaving
     * cascade order intact.
     *
     * <p>So both components must carry <strong>zero</strong> constraints - not even a width
     * constraint. Attaching one would reject input the legacy system accepts, which is exactly the
     * behavioural regression the migration forbids.
     *
     * <p>Unvalidated is not the same as undecorable. Both fields <em>can</em> carry a per-field
     * error state, and the response contract's own test proves they can be marked missing or
     * invalid. "Unvalidated" here means precisely "no rule fires on the way in".
     */
    @Nested
    @DisplayName("The middle name and second address line carry no constraint at all")
    class UnvalidatedComponentsCarryNoConstraint {

        private static final String PUNCTUATED_AND_SPACED =
                "  Mary-Ann 3rd, c/o O'Neill & Sons (Apt. 4B)  ";

        @ParameterizedTest(name = "{0} accepts a value far past its declared width")
        @EnumSource(value = StringComponent.class, mode = EnumSource.Mode.INCLUDE,
                names = {"MIDDLE_NAME", "ADDRESS_LINE_2"})
        @DisplayName("a value far past the declared width draws no violation")
        void valueFarPastTheDeclaredWidthDrawsNoViolation(StringComponent component) {
            String farTooLong = "Q".repeat(component.declaredWidth() * 10);

            AccountUpdateRequest request = withOnly(component, farTooLong);

            assertThat(violations(request))
                    .as("%s must accept any length; its declared width is documentation, never a "
                            + "rule", component.described())
                    .isEmpty();
            assertThat(component.read(request))
                    .as("and the over-length value must be carried untouched")
                    .isEqualTo(farTooLong)
                    .hasSize(component.declaredWidth() * 10);
        }

        @ParameterizedTest(name = "{0} accepts one character past its declared width")
        @EnumSource(value = StringComponent.class, mode = EnumSource.Mode.INCLUDE,
                names = {"MIDDLE_NAME", "ADDRESS_LINE_2"})
        @DisplayName("even one character past the declared width draws no violation")
        void evenOneCharacterPastTheDeclaredWidthDrawsNoViolation(StringComponent component) {
            assertThat(violations(withOnly(component, component.overWidthValue())))
                    .as("the boundary case matters most: a width constraint would fire here and "
                            + "nowhere else, so this is where a regression would first show")
                    .isEmpty();
        }

        @ParameterizedTest(name = "{0} accepts digits, punctuation and surrounding spaces")
        @EnumSource(value = StringComponent.class, mode = EnumSource.Mode.INCLUDE,
                names = {"MIDDLE_NAME", "ADDRESS_LINE_2"})
        @DisplayName("digits, punctuation, embedded, leading and trailing spaces all pass")
        void digitsPunctuationAndSurroundingSpacesAllPass(StringComponent component) {
            AccountUpdateRequest request = withOnly(component, PUNCTUATED_AND_SPACED);

            assertThat(violations(request))
                    .as("no character-class rule, no pattern and no length rule applies to %s",
                            component.described())
                    .isEmpty();
            assertThat(component.read(request))
                    .as("the value is carried byte for byte, keeping both leading spaces and both "
                            + "trailing spaces")
                    .isEqualTo(PUNCTUATED_AND_SPACED)
                    .startsWith("  ")
                    .endsWith("  ");
        }

        @ParameterizedTest(name = "{0} accepts an absent value")
        @EnumSource(value = StringComponent.class, mode = EnumSource.Mode.INCLUDE,
                names = {"MIDDLE_NAME", "ADDRESS_LINE_2"})
        @DisplayName("an absent value draws no violation")
        void absentValueDrawsNoViolation(StringComponent component) {
            AccountUpdateRequest request = withOnly(component, null);

            assertThat(violations(request)).isEmpty();
            assertThat(component.read(request)).isNull();
        }

        @ParameterizedTest(name = "{0} accepts an empty value")
        @EnumSource(value = StringComponent.class, mode = EnumSource.Mode.INCLUDE,
                names = {"MIDDLE_NAME", "ADDRESS_LINE_2"})
        @DisplayName("an empty value draws no violation and stays empty")
        void emptyValueDrawsNoViolationAndStaysEmpty(StringComponent component) {
            AccountUpdateRequest request = withOnly(component, "");

            assertThat(violations(request)).isEmpty();
            assertThat(component.read(request))
                    .as("empty must not be normalised to null")
                    .isNotNull()
                    .isEmpty();
        }

        /**
         * The sharpest form of the proof: the unvalidated component and its width-bounded
         * neighbour are pushed past their widths in the <em>same</em> submission. Exactly one
         * violation must come back, and it must name the neighbour.
         */
        @Test
        @DisplayName("over-length middle name is ignored while over-length first name is not")
        void overLengthMiddleNameIsIgnoredWhileOverLengthFirstNameIsNot() {
            Draft draft = new Draft();
            draft.firstName = StringComponent.FIRST_NAME.overWidthValue();
            draft.middleName = StringComponent.MIDDLE_NAME.overWidthValue();

            Set<ConstraintViolation<AccountUpdateRequest>> raised = violations(draft.build());

            assertThat(raised)
                    .as("both fields are 25 characters wide on the map, yet only one is enforced")
                    .hasSize(1);
            assertThat(raised.iterator().next().getPropertyPath()).hasToString("firstName");
        }

        @Test
        @DisplayName("over-length second address line is ignored while the first line is not")
        void overLengthSecondAddressLineIsIgnoredWhileTheFirstLineIsNot() {
            Draft draft = new Draft();
            draft.addressLine1 = StringComponent.ADDRESS_LINE_1.overWidthValue();
            draft.addressLine2 = StringComponent.ADDRESS_LINE_2.overWidthValue();

            Set<ConstraintViolation<AccountUpdateRequest>> raised = violations(draft.build());

            assertThat(raised)
                    .as("both lines are 50 characters wide on the map, yet only one is enforced")
                    .hasSize(1);
            assertThat(raised.iterator().next().getPropertyPath()).hasToString("addressLine1");
        }

        @Test
        @DisplayName("both unvalidated components survive a JSON round trip byte for byte")
        void bothUnvalidatedComponentsSurviveAJsonRoundTripByteForByte() throws Exception {
            Draft draft = new Draft();
            draft.middleName = PUNCTUATED_AND_SPACED;
            draft.addressLine2 = PUNCTUATED_AND_SPACED + "X".repeat(200);

            AccountUpdateRequest sent = draft.build();
            AccountUpdateRequest received = MAPPER.readValue(
                    MAPPER.writeValueAsString(sent), AccountUpdateRequest.class);

            assertThat(received.middleName()).isEqualTo(draft.middleName);
            assertThat(received.addressLine2()).isEqualTo(draft.addressLine2);
            assertThat(violations(received)).isEmpty();
        }
    }

    /**
     * The credit score window is inclusive 300 through 850. The range is declared as a condition
     * name at lines 848-849, applied by paragraph {@code 1275-EDIT-FICO-SCORE} at lines 2514-2530,
     * and reached only after the score has already passed the required-numeric stage because of
     * the gate at lines 1553-1554. Its message is the 31-character suffix
     * {@code : should be between 300 and 850}.
     *
     * <p>That gating is the whole reason the bound is <strong>delegated to
     * {@code com.carddemo.service.AccountUpdateService} and not annotated here</strong>. A
     * declarative minimum and maximum would hoist the check out of the ordered cascade and change
     * which single summary message a bad submission produces, so this request must accept 299 and
     * 851 exactly as readily as it accepts 300 and 850. The window is not weakened by that - it is
     * simply enforced one layer in, where source order can be honoured.
     *
     * <p>The value also crosses the API as a three-character string rather than a number, so that
     * a score such as 001 survives. Twenty-one of the fifty seeded customers score below 300, the
     * lowest being 001, which is why the read path must never apply the window either.
     */
    @Nested
    @DisplayName("The credit score window is delegated, not annotated")
    class CreditScoreWindowIsDelegatedNotAnnotated {

        @ParameterizedTest(name = "credit score {0} draws no violation here")
        @ValueSource(strings = {"300", "850", "299", "851", "001"})
        @DisplayName("both boundaries and both values just outside them are accepted")
        void bothBoundariesAndBothValuesJustOutsideThemAreAccepted(String score) {
            AccountUpdateRequest request = withOnly(StringComponent.FICO_SCORE, score);

            assertThat(violations(request))
                    .as("300 and 850 are inside the window and 299 and 851 are outside it, yet "
                            + "all four must reach the service so paragraph "
                            + "1275-EDIT-FICO-SCORE can apply the inclusive test in cascade order")
                    .isEmpty();
        }

        @ParameterizedTest(name = "credit score {0} is carried as three characters")
        @ValueSource(strings = {"300", "850", "299", "851", "001"})
        @DisplayName("every boundary value round-trips byte for byte as three characters")
        void everyBoundaryValueRoundTripsByteForByteAsThreeCharacters(String score)
                throws Exception {
            AccountUpdateRequest sent = withOnly(StringComponent.FICO_SCORE, score);

            String json = MAPPER.writeValueAsString(sent);
            AccountUpdateRequest received = MAPPER.readValue(json, AccountUpdateRequest.class);

            assertThat(json)
                    .as("the score is a quoted string on the wire, never a bare number")
                    .isEqualTo("{\"ficoScore\":\"" + score + "\"}");
            assertThat(received.ficoScore())
                    .as("the service must receive exactly what the client sent")
                    .isEqualTo(score)
                    .hasSize(StringComponent.FICO_SCORE.declaredWidth());
        }

        @Test
        @DisplayName("the lower boundary is never widened, renumbered or re-padded")
        void theLowerBoundaryIsNeverWidenedRenumberedOrRePadded() {
            AccountUpdateRequest request = withOnly(StringComponent.FICO_SCORE, "300");

            assertThat(request.ficoScore())
                    .isEqualTo("300")
                    .hasSize(3)
                    .isNotEqualTo("0300")
                    .isNotEqualTo("300.0")
                    .isNotEqualTo(" 300");
        }

        @Test
        @DisplayName("leading zeroes survive, so 001 never collapses to 1")
        void leadingZeroesSurviveSo001NeverCollapsesTo1() throws Exception {
            AccountUpdateRequest sent = withOnly(StringComponent.FICO_SCORE, "001");

            AccountUpdateRequest received = MAPPER.readValue(
                    MAPPER.writeValueAsString(sent), AccountUpdateRequest.class);

            assertThat(received.ficoScore())
                    .as("carrying the score as a numeric type would silently produce 1 and break "
                            + "the fixed-width record contract")
                    .isEqualTo("001")
                    .hasSize(3)
                    .isNotEqualTo("1");
        }

        @Test
        @DisplayName("a non-numeric score is transported rather than rejected")
        void aNonNumericScoreIsTransportedRatherThanRejected() {
            AccountUpdateRequest request = withOnly(StringComponent.FICO_SCORE, "ABC");

            assertThat(violations(request))
                    .as("the required-numeric stage runs before the window and belongs to the "
                            + "service; a pattern here would report the wrong message")
                    .isEmpty();
            assertThat(request.ficoScore()).isEqualTo("ABC");
        }

        @Test
        @DisplayName("a blank score is transported rather than rejected")
        void aBlankScoreIsTransportedRatherThanRejected() {
            assertThat(violations(withOnly(StringComponent.FICO_SCORE, "   "))).isEmpty();
            assertThat(withOnly(StringComponent.FICO_SCORE, "   ").ficoScore())
                    .as("three spaces are three spaces, never trimmed to empty")
                    .isEqualTo("   ");
        }
    }

    @Nested
    @DisplayName("Every map width is carried exactly, never padded and never trimmed")
    class MapWidthsAreCarriedExactly {

        @ParameterizedTest(name = "{0} accepts a value at its exact declared width")
        @EnumSource(StringComponent.class)
        @DisplayName("a value occupying the field exactly is accepted and carried untouched")
        void valueAtExactDeclaredWidthIsAcceptedAndCarriedUntouched(StringComponent component) {
            String value = component.exactWidthValue();

            AccountUpdateRequest request = withOnly(component, value);

            assertThat(violations(request))
                    .as("%s must accept a value that fills it exactly", component.described())
                    .isEmpty();
            assertThat(component.read(request))
                    .isEqualTo(value)
                    .hasSize(component.declaredWidth());
        }

        @ParameterizedTest(name = "{0} keeps a short value short")
        @EnumSource(StringComponent.class)
        @DisplayName("a value shorter than the field is never padded up to the width")
        void shortValueIsNeverPaddedUpToTheWidth(StringComponent component) {
            AccountUpdateRequest request = withOnly(component, "X");

            assertThat(component.read(request))
                    .as("%s must not be right-padded on the way in; padding is the mapper's job "
                            + "at the record boundary, not this contract's", component.described())
                    .isEqualTo("X")
                    .hasSize(1);
        }

        @ParameterizedTest(name = "{0} keeps its trailing space")
        @EnumSource(StringComponent.class)
        @DisplayName("a trailing space is contractual data and is never trimmed")
        void trailingSpaceIsNeverTrimmed(StringComponent component) {
            String value = component.trailingSpaceValue();

            AccountUpdateRequest request = withOnly(component, value);

            assertThat(component.read(request))
                    .as("%s must keep its trailing space", component.described())
                    .isEqualTo(value)
                    .hasSize(component.declaredWidth())
                    .endsWith(" ");
        }

        @ParameterizedTest(name = "{0} keeps its leading space")
        @EnumSource(StringComponent.class)
        @DisplayName("a leading space is contractual data and is never stripped")
        void leadingSpaceIsNeverStripped(StringComponent component) {
            String value = component.leadingSpaceValue();

            AccountUpdateRequest request = withOnly(component, value);

            assertThat(component.read(request))
                    .as("%s must keep its leading space", component.described())
                    .isEqualTo(value)
                    .hasSize(component.declaredWidth())
                    .startsWith(" ");
        }

        @ParameterizedTest(name = "{0} survives a JSON round trip byte for byte")
        @EnumSource(StringComponent.class)
        @DisplayName("an exact-width value with surrounding spaces survives serialisation")
        void exactWidthValueWithSurroundingSpacesSurvivesSerialisation(StringComponent component)
                throws Exception {
            String value = component.trailingSpaceValue();
            AccountUpdateRequest sent = withOnly(component, value);

            AccountUpdateRequest received = MAPPER.readValue(
                    MAPPER.writeValueAsString(sent), AccountUpdateRequest.class);

            assertThat(component.read(received))
                    .as("%s must survive the wire unchanged", component.described())
                    .isEqualTo(value)
                    .hasSize(component.declaredWidth());
        }

        @ParameterizedTest(name = "{0} binds by its record component name")
        @EnumSource(StringComponent.class)
        @DisplayName("each component binds under its own property name, with no renaming")
        void eachComponentBindsUnderItsOwnPropertyName(StringComponent component)
                throws Exception {
            String json = MAPPER.writeValueAsString(
                    withOnly(component, component.exactWidthValue()));

            assertThat(json)
                    .as("%s must appear under exactly one key", component.described())
                    .isEqualTo("{\"" + component.jsonProperty() + "\":\""
                            + component.exactWidthValue() + "\"}");
        }

        @Test
        @DisplayName("the inventory is 38 bounded strings and 5 exact decimals, so 43 in total")
        void theInventoryIs38BoundedStringsAnd5ExactDecimals() {
            assertThat(StringComponent.values())
                    .as("54 map input families minus 11 non-editable furniture families leaves 43 "
                            + "editable components, of which 38 are bounded strings")
                    .hasSize(38);
            assertThat(MoneyComponent.values()).hasSize(5);
            assertThat(StringComponent.values().length + MoneyComponent.values().length)
                    .isEqualTo(43);
        }

        @Test
        @DisplayName("the four editable-but-undecorated components are all present and editable")
        void theFourEditableButUndecoratedComponentsArePresentAndEditable() {
            Draft draft = new Draft();
            for (StringComponent component : StringComponent.UNDECORATED) {
                component.write(draft, component.exactWidthValue());
            }
            AccountUpdateRequest request = draft.build();

            assertThat(StringComponent.UNDECORATED)
                    .as("43 unprotected mapset fields minus 39 decoration sites leaves exactly "
                            + "four editable fields that never receive field-level decoration")
                    .hasSize(4);
            assertThat(violations(request)).isEmpty();
            assertThat(request.accountId()).isEqualTo("X".repeat(11));
            assertThat(request.accountGroupId()).isEqualTo("X".repeat(10));
            assertThat(request.customerId()).isEqualTo("X".repeat(9));
            assertThat(request.governmentIssuedId()).isEqualTo("X".repeat(20));
        }
    }

    /**
     * The legacy screen decomposes four dates, one social-security number and two telephone numbers
     * into independently entered, independently validated and independently decorated sub-fields.
     * Merging any of them would destroy the field-level error contract, because each sub-field owns
     * its own validation flag and its own decoration site. Nothing here parses, converts or
     * assembles: no platform date type is imported, no date is interpreted and no telephone number
     * is formatted. The persisted telephone form is assembled by the service, never by this
     * request.
     */
    @Nested
    @DisplayName("Split fields stay split")
    class SplitFieldsStaySplit {

        @Test
        @DisplayName("all four dates are carried as twelve separate bounded strings")
        void allFourDatesAreCarriedAsTwelveSeparateBoundedStrings() {
            AccountUpdateRequest request = Draft.realistic().build();

            assertThat(request.openYear()).isEqualTo("2020").hasSize(4);
            assertThat(request.openMonth()).isEqualTo("01").hasSize(2);
            assertThat(request.openDay()).isEqualTo("15").hasSize(2);
            assertThat(request.expiryYear()).isEqualTo("2027").hasSize(4);
            assertThat(request.expiryMonth()).isEqualTo("12").hasSize(2);
            assertThat(request.expiryDay()).isEqualTo("31").hasSize(2);
            assertThat(request.reissueYear()).isEqualTo("2024").hasSize(4);
            assertThat(request.reissueMonth()).isEqualTo("06").hasSize(2);
            assertThat(request.reissueDay()).isEqualTo("30").hasSize(2);
            assertThat(request.dateOfBirthYear()).isEqualTo("1985").hasSize(4);
            assertThat(request.dateOfBirthMonth()).isEqualTo("07").hasSize(2);
            assertThat(request.dateOfBirthDay()).isEqualTo("04").hasSize(2);
        }

        @Test
        @DisplayName("the reissue date is genuinely present as the fourth split date")
        void theReissueDateIsGenuinelyPresentAsTheFourthSplitDate() {
            AccountUpdateRequest request = new Draft().build();

            assertThat(request.reissueYear()).isNull();
            assertThat(request.reissueMonth()).isNull();
            assertThat(request.reissueDay()).isNull();

            Draft draft = new Draft();
            draft.reissueYear = "1999";
            draft.reissueMonth = "02";
            draft.reissueDay = "28";
            AccountUpdateRequest populated = draft.build();

            assertThat(populated.reissueYear()).isEqualTo("1999");
            assertThat(populated.reissueMonth()).isEqualTo("02");
            assertThat(populated.reissueDay()).isEqualTo("28");
        }

        @Test
        @DisplayName("no date part is ever merged into a single date value")
        void noDatePartIsEverMergedIntoASingleDateValue() throws Exception {
            String json = MAPPER.writeValueAsString(Draft.realistic().build());

            assertThat(json)
                    .as("the twelve date parts must appear as twelve keys")
                    .contains("\"openYear\":\"2020\"", "\"openMonth\":\"01\"",
                            "\"openDay\":\"15\"")
                    .as("and no merged eight- or ten-character date may appear")
                    .doesNotContain("20200115", "2020-01-15", "\"openDate\"",
                            "\"expiryDate\"", "\"reissueDate\"", "\"dateOfBirth\":");
        }

        @Test
        @DisplayName("the social-security number arrives in three parts of widths 3, 2 and 4")
        void theSocialSecurityNumberArrivesInThreePartsOfWidths324() {
            AccountUpdateRequest request = Draft.realistic().build();

            assertThat(request.ssnPart1()).hasSize(3);
            assertThat(request.ssnPart2()).hasSize(2);
            assertThat(request.ssnPart3()).hasSize(4);
            assertThat(StringComponent.SSN_PART_1.declaredWidth()).isEqualTo(3);
            assertThat(StringComponent.SSN_PART_2.declaredWidth()).isEqualTo(2);
            assertThat(StringComponent.SSN_PART_3.declaredWidth()).isEqualTo(4);
        }

        @Test
        @DisplayName("no nine-character social-security value is exposed anywhere")
        void noNineCharacterSocialSecurityValueIsExposedAnywhere() throws Exception {
            String json = MAPPER.writeValueAsString(Draft.realistic().build());

            assertThat(json)
                    .contains("\"ssnPart1\":\"999\"", "\"ssnPart2\":\"88\"",
                            "\"ssnPart3\":\"7777\"")
                    .as("a joined or hyphenated form would collapse three decoration sites into "
                            + "one")
                    .doesNotContain("999887777", "999-88-7777", "\"ssn\":");
        }

        @Test
        @DisplayName("both telephone numbers arrive in three parts each, widths 3, 3 and 4")
        void bothTelephoneNumbersArriveInThreePartsEach() {
            AccountUpdateRequest request = Draft.realistic().build();

            assertThat(request.phone1AreaCode()).isEqualTo("313").hasSize(3);
            assertThat(request.phone1Prefix()).isEqualTo("555").hasSize(3);
            assertThat(request.phone1LineNumber()).isEqualTo("0100").hasSize(4);
            assertThat(request.phone2AreaCode()).isEqualTo("248").hasSize(3);
            assertThat(request.phone2Prefix()).isEqualTo("555").hasSize(3);
            assertThat(request.phone2LineNumber()).isEqualTo("0199").hasSize(4);
        }

        @Test
        @DisplayName("no preformatted telephone number is exposed anywhere")
        void noPreformattedTelephoneNumberIsExposedAnywhere() throws Exception {
            String json = MAPPER.writeValueAsString(Draft.realistic().build());

            assertThat(json)
                    .as("the persisted parenthesised form belongs to the service; this contract "
                            + "carries six discrete parts")
                    .doesNotContain("(313)555-0100", "(248)555-0199", "313-555-0100",
                            "\"phone1\":", "\"phone2\":");
        }

        @Test
        @DisplayName("a leading zero in a telephone line number is preserved")
        void aLeadingZeroInATelephoneLineNumberIsPreserved() {
            AccountUpdateRequest request = Draft.realistic().build();

            assertThat(request.phone1LineNumber())
                    .as("a numeric type would render 0100 as 100 and break the fixed-width record")
                    .startsWith("0")
                    .hasSize(4);
        }

        /**
         * The telephone cascade always runs all three stages - the range head at line 2225, the
         * area code at 2246, the prefix at 2316, the line number at 2370, the inner exit at 2424
         * and the range exit at 2427, invoked at lines 1632-1638 and 1640-1646 - and sets all three
         * flags independently.
         *
         * <p>It carries a <strong>preserved defect</strong>: the all-blank shortcut at lines
         * 2238-2239 tests the area-code sub-field where the two clauses beside it test their own
         * sub-fields, so a submission with a blank area code, a blank prefix and a populated line
         * number is silently treated as no telephone supplied. That is reproduced in the service
         * and recorded in the module decision log; it is contract, not a defect to correct. This
         * request must therefore deliver exactly that combination to the service without pattern
         * matching it away first.
         */
        @Test
        @DisplayName("the combination the preserved shortcut mishandles reaches the service intact")
        void theCombinationThePreservedShortcutMishandlesReachesTheServiceIntact() {
            Draft draft = new Draft();
            draft.phone1AreaCode = "   ";
            draft.phone1Prefix = "   ";
            draft.phone1LineNumber = "0100";

            AccountUpdateRequest request = draft.build();

            assertThat(violations(request))
                    .as("no pattern may fire on any of the six telephone components, or the "
                            + "service would never see the combination the shortcut mishandles")
                    .isEmpty();
            assertThat(request.phone1AreaCode()).isEqualTo("   ").hasSize(3);
            assertThat(request.phone1Prefix()).isEqualTo("   ").hasSize(3);
            assertThat(request.phone1LineNumber()).isEqualTo("0100").hasSize(4);
        }
    }

    /**
     * The legacy alphabetic check blanks every letter in the field and then tests whether anything
     * is left, so <strong>embedded spaces pass</strong>. A letters-only predicate would reject
     * values the legacy system accepts, and the seeded customer data contains such values, so it
     * would break existing data on the first submission. The four character-class stages are
     * required-alphabetic at line 1898, required-alphanumeric at 1955, optional-alphabetic at 2012
     * and optional-alphanumeric at 2061; the optional variants accept blank and the required ones
     * do not, and that distinction is the service's rather than this contract's.
     *
     * <p>One source comment is stale: line 2078 claims alphabetic-plus-space while lines 2079-2082
     * use the 62-character alphanumeric table. The code governs, so an alphanumeric value must be
     * accepted in the affected field.
     */
    @Nested
    @DisplayName("Character-class semantics belong to the service")
    class CharacterClassSemanticsBelongToTheService {

        @Test
        @DisplayName("names carrying embedded spaces are accepted and carried byte for byte")
        void namesCarryingEmbeddedSpacesAreAcceptedAndCarriedByteForByte() {
            Draft draft = new Draft();
            draft.firstName = "MARY ANN";
            draft.lastName = "Aniya Von";

            AccountUpdateRequest request = draft.build();

            assertThat(violations(request))
                    .as("the legacy idiom blanks letters and then tests the remainder, so a space "
                            + "inside a name is valid input")
                    .isEmpty();
            assertThat(request.firstName()).isEqualTo("MARY ANN").contains(" ");
            assertThat(request.lastName()).isEqualTo("Aniya Von").contains(" ");
        }

        @Test
        @DisplayName("a name that is entirely spaces is accepted")
        void aNameThatIsEntirelySpacesIsAccepted() {
            Draft draft = new Draft();
            draft.firstName = " ".repeat(StringComponent.FIRST_NAME.declaredWidth());
            draft.lastName = " ".repeat(StringComponent.LAST_NAME.declaredWidth());

            AccountUpdateRequest request = draft.build();

            assertThat(violations(request))
                    .as("the required-versus-optional distinction is applied by the cascade, so an "
                            + "all-blank name must still arrive")
                    .isEmpty();
            assertThat(request.firstName()).hasSize(25);
        }

        @Test
        @DisplayName("an alphanumeric value passes where the stale comment claims letters only")
        void anAlphanumericValuePassesWhereTheStaleCommentClaimsLettersOnly() {
            Draft draft = new Draft();
            draft.addressLine1 = "1500 Woodward Avenue Suite 300";
            draft.city = "Detroit 48226";

            AccountUpdateRequest request = draft.build();

            assertThat(violations(request))
                    .as("the optional stage converts through the 62-character alphanumeric table, "
                            + "so digits are permitted whatever the adjacent comment says")
                    .isEmpty();
            assertThat(request.addressLine1()).isEqualTo("1500 Woodward Avenue Suite 300");
            assertThat(request.city()).isEqualTo("Detroit 48226");
        }

        @Test
        @DisplayName("a blank value in an optional-class field is accepted and stays blank")
        void aBlankValueInAnOptionalClassFieldIsAcceptedAndStaysBlank() {
            Draft draft = Draft.realistic();
            draft.addressLine1 = "";
            draft.city = "";
            draft.countryCode = "";

            AccountUpdateRequest request = draft.build();

            assertThat(violations(request)).isEmpty();
            assertThat(request.addressLine1()).isEmpty();
            assertThat(request.city()).isEmpty();
            assertThat(request.countryCode()).isEmpty();
        }

        @Test
        @DisplayName("punctuation is never rejected on any component")
        void punctuationIsNeverRejectedOnAnyComponent() {
            Draft draft = new Draft();
            draft.firstName = "O'Neill";
            draft.lastName = "Smith-Jones";
            draft.addressLine1 = "c/o Suite #7, Bldg. 4";
            draft.governmentIssuedId = "ID/0001-A";

            assertThat(violations(draft.build()))
                    .as("no pattern constraint exists anywhere on this contract")
                    .isEmpty();
        }

        @Test
        @DisplayName("the case an operator typed is the case the service receives")
        void theCaseAnOperatorTypedIsTheCaseTheServiceReceives() {
            Draft draft = new Draft();
            draft.firstName = "mary ann";
            draft.lastName = "aNiYa";

            AccountUpdateRequest request = draft.build();

            assertThat(request.firstName())
                    .as("the embossed-name fold uses a strict 26-character table and lives in the "
                            + "utility layer, so this contract must not case-fold anything")
                    .isEqualTo("mary ann");
            assertThat(request.lastName()).isEqualTo("aNiYa");
        }
    }

    /**
     * The state and the postal code are two independent components. The flat state-membership test
     * is paragraph {@code 1270-EDIT-US-STATE-CD} at lines 2493-2510, which performs no trim, no
     * numeric check and no blank pre-check. The combination test is paragraph
     * {@code 1280-EDIT-US-STATE-ZIP-CD} at lines 2536-2557, which builds its lookup key at lines
     * 2537-2540 by <strong>positional concatenation with no trimming</strong> - the two-character
     * state followed by the first two characters of the postal code - and on failure sets
     * <strong>both</strong> flags, so one comparison can decorate two fields.
     *
     * <p>All of that is the service's work. This request performs no concatenation, no lookup, no
     * slicing and no trimming; it only has to deliver both components at their exact widths with
     * every space intact, because a trimmed value would build a different key and change the
     * outcome.
     */
    @Nested
    @DisplayName("State and postal code are carried positionally and untrimmed")
    class StateAndPostalCodeAreCarriedPositionally {

        @Test
        @DisplayName("the state is two characters wide and the postal code five")
        void theStateIsTwoCharactersWideAndThePostalCodeFive() {
            assertThat(StringComponent.STATE_CODE.declaredWidth()).isEqualTo(2);
            assertThat(StringComponent.ZIP_CODE.declaredWidth()).isEqualTo(5);

            AccountUpdateRequest request = Draft.realistic().build();

            assertThat(request.stateCode()).isEqualTo("MI").hasSize(2);
            assertThat(request.zipCode()).isEqualTo("48226").hasSize(5);
        }

        @Test
        @DisplayName("both are carried untrimmed, because the lookup key is positional")
        void bothAreCarriedUntrimmedBecauseTheLookupKeyIsPositional() {
            Draft draft = new Draft();
            draft.stateCode = "M ";
            draft.zipCode = "48 26";

            AccountUpdateRequest request = draft.build();

            assertThat(violations(request)).isEmpty();
            assertThat(request.stateCode())
                    .as("trimming the state would shift the positional key and change which "
                            + "combination is looked up")
                    .isEqualTo("M ")
                    .hasSize(2);
            assertThat(request.zipCode()).isEqualTo("48 26").hasSize(5);
        }

        @Test
        @DisplayName("no concatenated lookup key is exposed by the contract")
        void noConcatenatedLookupKeyIsExposedByTheContract() throws Exception {
            Draft draft = new Draft();
            draft.stateCode = "MI";
            draft.zipCode = "48226";

            String json = MAPPER.writeValueAsString(draft.build());

            assertThat(json)
                    .as("the state and the postal code cross the wire as two discrete keys; the "
                            + "four-character key is assembled downstream and never here")
                    .isEqualTo("{\"stateCode\":\"MI\",\"zipCode\":\"48226\"}")
                    .doesNotContain("MI48", "\"stateZip\"", "\"stateAndZip\"");
        }

        @Test
        @DisplayName("a state code absent from the reference list is transported, not rejected")
        void aStateCodeAbsentFromTheReferenceListIsTransportedNotRejected() {
            Draft draft = new Draft();
            draft.stateCode = "ZZ";
            draft.zipCode = "00000";

            assertThat(violations(draft.build()))
                    .as("membership is checked against the reference set by the service, whose "
                            + "27-character message names the field; no set is loaded here")
                    .isEmpty();
        }

        @Test
        @DisplayName("a state code outside the flat list but inside the combination list is carried")
        void aStateCodeOutsideTheFlatListButInsideTheCombinationListIsCarried() {
            Draft draft = new Draft();
            draft.stateCode = "AE";
            draft.zipCode = "09000";

            AccountUpdateRequest request = draft.build();

            assertThat(violations(request))
                    .as("six of the combination prefixes are absent from the flat state list, so "
                            + "the two reference sets must never be intersected; this contract "
                            + "simply carries whatever was typed")
                    .isEmpty();
            assertThat(request.stateCode()).isEqualTo("AE");
        }
    }

    /**
     * The five monetary components are 15 characters wide on the screen and signed zoned decimals
     * with ten integer digits and two decimal places in the account record, which maps to a numeric
     * column of precision 12 and scale 2. They are therefore exact decimals - never a binary
     * floating-point type, never a primitive and never a preformatted string.
     *
     * <p>Scale 2 is a contract this request <em>carries</em> rather than <em>applies</em>. The
     * estate declares no rounding on any arithmetic statement, so every legacy store into a
     * two-decimal field truncates toward zero, and that truncation happens in exactly one place in
     * the module. This request must therefore hand a decimal on unaltered in either direction: it
     * must not rescale, round, negate or format, and the tests below prove it by passing scales
     * above, below and outside the contract scale and getting the identical object back.
     */
    @Nested
    @DisplayName("Monetary components are exact decimals carried unaltered")
    class MonetaryComponentsAreExactDecimalsCarriedUnaltered {

        @ParameterizedTest(name = "{0} is an exact decimal")
        @EnumSource(MoneyComponent.class)
        @DisplayName("each monetary accessor yields an exact decimal, never a floating-point type")
        void eachMonetaryAccessorYieldsAnExactDecimal(MoneyComponent component) {
            BigDecimal value = new BigDecimal("1234567890.12");

            AccountUpdateRequest request = withOnly(component, value);

            assertThat(component.read(request))
                    .as("%s must be an exact decimal; the accessor's static type is already "
                            + "BigDecimal, which this confirms at run time too",
                            component.described())
                    .isExactlyInstanceOf(BigDecimal.class)
                    .isEqualTo(value);
        }

        @ParameterizedTest(name = "{0} preserves scale 2 on construction")
        @EnumSource(MoneyComponent.class)
        @DisplayName("a scale-2 amount keeps scale exactly 2 through construction")
        void aScale2AmountKeepsScaleExactly2ThroughConstruction(MoneyComponent component) {
            BigDecimal value = new BigDecimal("9999999999.99");

            BigDecimal carried = component.read(withOnly(component, value));

            assertThat(carried.scale())
                    .as("%s must keep the record's two decimal places", component.described())
                    .isEqualTo(MoneyComponent.CONTRACT_SCALE);
            assertThat(carried.unscaledValue()).isEqualTo(value.unscaledValue());
            assertThat(carried.toPlainString()).isEqualTo("9999999999.99");
        }

        @ParameterizedTest(name = "{0} preserves scale 2 across the wire")
        @EnumSource(MoneyComponent.class)
        @DisplayName("a scale-2 amount still has scale exactly 2 after a JSON round trip")
        void aScale2AmountStillHasScaleExactly2AfterAJsonRoundTrip(MoneyComponent component)
                throws Exception {
            BigDecimal value = new BigDecimal("1.20");
            AccountUpdateRequest sent = withOnly(component, value);

            AccountUpdateRequest received = MAPPER.readValue(
                    MAPPER.writeValueAsString(sent), AccountUpdateRequest.class);

            assertThat(component.read(received).scale())
                    .as("%s must not lose its trailing zero on the wire", component.described())
                    .isEqualTo(MoneyComponent.CONTRACT_SCALE);
            assertThat(component.read(received).toPlainString()).isEqualTo("1.20");
        }

        @ParameterizedTest(name = "{0} serialises in plain notation")
        @EnumSource(MoneyComponent.class)
        @DisplayName("serialisation is plain decimal text, never scientific notation")
        void serialisationIsPlainDecimalTextNeverScientificNotation(MoneyComponent component)
                throws Exception {
            BigDecimal veryLarge = new BigDecimal("1E+10");

            String json = MAPPER.writeValueAsString(withOnly(component, veryLarge));

            assertThat(json)
                    .as("%s must never appear in exponent form; a downstream fixed-width writer "
                            + "cannot interpret one", component.described())
                    .isEqualTo("{\"" + component.jsonProperty() + "\":10000000000}")
                    .doesNotContain("E+", "E-", "e+", "e-");
        }

        @ParameterizedTest(name = "{0} carries zero and negative amounts")
        @EnumSource(MoneyComponent.class)
        @DisplayName("zero and negative amounts round-trip correctly")
        void zeroAndNegativeAmountsRoundTripCorrectly(MoneyComponent component) throws Exception {
            BigDecimal zero = new BigDecimal("0.00");
            BigDecimal negative = new BigDecimal("-9999999999.99");

            AccountUpdateRequest zeroReceived = MAPPER.readValue(
                    MAPPER.writeValueAsString(withOnly(component, zero)),
                    AccountUpdateRequest.class);
            AccountUpdateRequest negativeReceived = MAPPER.readValue(
                    MAPPER.writeValueAsString(withOnly(component, negative)),
                    AccountUpdateRequest.class);

            assertThat(component.read(zeroReceived).toPlainString()).isEqualTo("0.00");
            assertThat(component.read(zeroReceived).scale())
                    .isEqualTo(MoneyComponent.CONTRACT_SCALE);
            assertThat(component.read(negativeReceived).toPlainString())
                    .as("the record field is signed, so a debit balance must survive intact")
                    .isEqualTo("-9999999999.99");
            assertThat(component.read(negativeReceived).signum()).isNegative();
        }

        @ParameterizedTest(name = "{0} never rescales in either direction")
        @EnumSource(MoneyComponent.class)
        @DisplayName("an amount is handed on as the very same object, at whatever scale it arrived")
        void anAmountIsHandedOnAsTheVerySameObject(MoneyComponent component) {
            BigDecimal scaleZero = new BigDecimal("42");
            BigDecimal scaleFive = new BigDecimal("42.00000");
            BigDecimal scaleNegative = new BigDecimal("1E+10");

            assertThat(component.read(withOnly(component, scaleZero)))
                    .as("%s must not scale up to the contract scale", component.described())
                    .isSameAs(scaleZero)
                    .returns(0, BigDecimal::scale);
            assertThat(component.read(withOnly(component, scaleFive)))
                    .as("%s must not scale down to the contract scale", component.described())
                    .isSameAs(scaleFive)
                    .returns(5, BigDecimal::scale);
            assertThat(component.read(withOnly(component, scaleNegative)))
                    .as("%s must not normalise a negative scale either", component.described())
                    .isSameAs(scaleNegative)
                    .returns(-10, BigDecimal::scale);
        }

        @ParameterizedTest(name = "{0} is not formatted for display")
        @EnumSource(MoneyComponent.class)
        @DisplayName("no grouping separator, leading plus sign or currency symbol is introduced")
        void noGroupingSeparatorLeadingPlusOrCurrencySymbolIsIntroduced(MoneyComponent component)
                throws Exception {
            String json = MAPPER.writeValueAsString(
                    withOnly(component, new BigDecimal("1234567890.12")));

            assertThat(json)
                    .as("the map's edited picture is a 3270 display artefact; the wire form is a "
                            + "bare exact decimal")
                    .isEqualTo("{\"" + component.jsonProperty() + "\":1234567890.12}")
                    .doesNotContain(",", "+", "$", "USD");
        }

        @Test
        @DisplayName("the five monetary components are independent of one another")
        void theFiveMonetaryComponentsAreIndependentOfOneAnother() {
            AccountUpdateRequest request = Draft.realistic().build();

            assertThat(request.creditLimit()).isEqualTo(new BigDecimal("5000.00"));
            assertThat(request.cashCreditLimit()).isEqualTo(new BigDecimal("1500.00"));
            assertThat(request.currentBalance()).isEqualTo(new BigDecimal("-250.75"));
            assertThat(request.currentCycleCredit()).isEqualTo(new BigDecimal("0.00"));
            assertThat(request.currentCycleDebit()).isEqualTo(new BigDecimal("1234567890.12"));
        }

        @Test
        @DisplayName("all five are 15 characters wide on the screen")
        void allFiveAre15CharactersWideOnTheScreen() {
            assertThat(MoneyComponent.SCREEN_WIDTH).isEqualTo(15);
            assertThat(MoneyComponent.values()).hasSize(5);
        }
    }

    @Nested
    @DisplayName("The JSON contract")
    class TheJsonContract {

        private Set<String> propertyNamesOf(AccountUpdateRequest request) throws Exception {
            Map<String, Object> parsed = MAPPER.readValue(
                    MAPPER.writeValueAsString(request),
                    new TypeReference<Map<String, Object>>() { });
            return new LinkedHashSet<>(parsed.keySet());
        }

        @Test
        @DisplayName("absent components are omitted entirely rather than sent as nulls")
        void absentComponentsAreOmittedEntirelyRatherThanSentAsNulls() throws Exception {
            String json = MAPPER.writeValueAsString(new Draft().build());

            assertThat(json)
                    .as("null-valued properties are omitted, so an untouched 3270 field costs "
                            + "nothing on the wire")
                    .isEqualTo("{}");
        }

        @Test
        @DisplayName("an unknown incoming property is tolerated rather than rejected")
        void anUnknownIncomingPropertyIsToleratedRatherThanRejected() throws Exception {
            AccountUpdateRequest received = MAPPER.readValue(
                    "{\"accountId\":\"00000000011\",\"aFieldThatDoesNotExist\":42}",
                    AccountUpdateRequest.class);

            assertThat(received.accountId()).isEqualTo("00000000011");
            assertThat(received.middleName()).isNull();
        }

        @Test
        @DisplayName("a fully populated request carries exactly the 43 expected property names")
        void aFullyPopulatedRequestCarriesExactlyThe43ExpectedPropertyNames() throws Exception {
            Set<String> expected = new LinkedHashSet<>();
            for (StringComponent component : StringComponent.values()) {
                expected.add(component.jsonProperty());
            }
            for (MoneyComponent component : MoneyComponent.values()) {
                expected.add(component.jsonProperty());
            }

            Set<String> actual = propertyNamesOf(Draft.realistic().build());

            assertThat(expected).hasSize(43);
            assertThat(actual)
                    .as("the wire form is exactly the 43 editable components and nothing else")
                    .hasSize(43)
                    .containsExactlyInAnyOrderElementsOf(expected);
        }

        /**
         * Eleven of the 54 map families are non-editable screen furniture: the transaction name,
         * both title lines, the current date, the program name, the current time, the information
         * message, the error message and the three function-key legends. The metadata and message
         * items belong on the response; the legends are pure 3270 decoration and belong nowhere.
         * The exact-43 assertion above already excludes them arithmetically, and this states the
         * conclusion in the form a reviewer will look for.
         */
        @Test
        @DisplayName("no screen furniture appears on the request")
        void noScreenFurnitureAppearsOnTheRequest() throws Exception {
            Set<String> actual = propertyNamesOf(Draft.realistic().build());

            assertThat(actual).doesNotContain(
                    "transactionName", "trnName", "title01", "titleLine1", "title02",
                    "titleLine2", "currentDate", "curDate", "currentTime", "curTime",
                    "programName", "pgmName", "infoMessage", "informationMessage",
                    "errorMessage", "errMsg", "functionKeys", "fKeys", "functionKey05",
                    "functionKey12");
        }

        @Test
        @DisplayName("no terminal attribute, control byte or map coordinate appears on the request")
        void noTerminalAttributeControlByteOrMapCoordinateAppearsOnTheRequest() throws Exception {
            String json = MAPPER.writeValueAsString(Draft.realistic().build());

            assertThat(json)
                    .as("the symbolic map's length, flag and attribute companions to every field, "
                            + "the leading terminal-buffer filler and the colour constants are all "
                            + "3270 artefacts with no place in a machine contract")
                    .doesNotContain("Length\":", "\"attribute", "Attribute\":", "\"cursor",
                            "DFHRED", "DFHGREEN", "\"row\":", "\"column\":", "\"filler");
        }

        @Test
        @DisplayName("the contract is order-independent, so a reordered payload binds identically")
        void theContractIsOrderIndependentSoAReorderedPayloadBindsIdentically() throws Exception {
            AccountUpdateRequest first = MAPPER.readValue(
                    "{\"accountId\":\"00000000011\",\"ficoScore\":\"742\"}",
                    AccountUpdateRequest.class);
            AccountUpdateRequest second = MAPPER.readValue(
                    "{\"ficoScore\":\"742\",\"accountId\":\"00000000011\"}",
                    AccountUpdateRequest.class);

            assertThat(first).isEqualTo(second);
        }

        @Test
        @DisplayName("an explicit null on the wire is accepted and stays null")
        void anExplicitNullOnTheWireIsAcceptedAndStaysNull() throws Exception {
            AccountUpdateRequest received = MAPPER.readValue(
                    "{\"middleName\":null,\"addressLine2\":null,\"creditLimit\":null}",
                    AccountUpdateRequest.class);

            assertThat(violations(received)).isEmpty();
            assertThat(received.middleName()).isNull();
            assertThat(received.addressLine2()).isNull();
            assertThat(received.creditLimit()).isNull();
        }
    }

    @Nested
    @DisplayName("Value semantics and immutability")
    class ValueSemanticsAndImmutability {

        @Test
        @DisplayName("two requests built from identical input are equal and agree on hash code")
        void twoRequestsBuiltFromIdenticalInputAreEqualAndAgreeOnHashCode() {
            AccountUpdateRequest first = Draft.realistic().build();
            AccountUpdateRequest second = Draft.realistic().build();

            assertThat(first).isEqualTo(second).isNotSameAs(second);
            assertThat(first).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("changing any single component makes two requests unequal")
        void changingAnySingleComponentMakesTwoRequestsUnequal() {
            AccountUpdateRequest baseline = Draft.realistic().build();

            for (StringComponent component : StringComponent.values()) {
                Draft altered = Draft.realistic();
                component.write(altered, "different");

                assertThat(altered.build())
                        .as("%s must participate in equality", component.described())
                        .isNotEqualTo(baseline);
            }
            for (MoneyComponent component : MoneyComponent.values()) {
                Draft altered = Draft.realistic();
                component.write(altered, new BigDecimal("7.77"));

                assertThat(altered.build())
                        .as("%s must participate in equality", component.described())
                        .isNotEqualTo(baseline);
            }
        }

        @Test
        @DisplayName("an empty request equals another empty request but not a populated one")
        void anEmptyRequestEqualsAnotherEmptyRequestButNotAPopulatedOne() {
            assertThat(new Draft().build())
                    .isEqualTo(new Draft().build())
                    .isNotEqualTo(Draft.realistic().build())
                    .isNotEqualTo(null)
                    .isNotEqualTo("not a request");
        }

        /**
         * Immutability is demonstrated by construction, never by inspecting the type at run time.
         * The contract is a record, so the canonical constructor is the only way to produce one and
         * there is no mutator to call: every test in this class had to build a fresh instance to
         * change anything, which is the demonstration. What remains to prove is that construction
         * itself does not copy or normalise, so the service receives the identical object the
         * client's decoder produced.
         */
        @Test
        @DisplayName("construction neither copies nor normalises the values handed to it")
        void constructionNeitherCopiesNorNormalisesTheValuesHandedToIt() {
            String spacedValue = "  spaced  ";
            BigDecimal amount = new BigDecimal("1.20");
            Draft draft = new Draft();
            draft.middleName = spacedValue;
            draft.firstName = spacedValue;
            draft.creditLimit = amount;

            AccountUpdateRequest request = draft.build();

            assertThat(request.middleName()).isSameAs(spacedValue);
            assertThat(request.firstName()).isSameAs(spacedValue);
            assertThat(request.creditLimit()).isSameAs(amount);
        }

        @Test
        @DisplayName("the textual form names the type and carries the component values")
        void theTextualFormNamesTheTypeAndCarriesTheComponentValues() {
            String text = Draft.realistic().build().toString();

            assertThat(text)
                    .startsWith("AccountUpdateRequest[")
                    .endsWith("]")
                    .contains("accountId=00000000011")
                    .contains("ficoScore=742")
                    .contains("creditLimit=5000.00")
                    .contains("middleName=Q. Ann-Marie 3rd, Jr.");
        }

        @Test
        @DisplayName("all 43 accessors return exactly the values they were constructed with")
        void allFortyThreeAccessorsReturnExactlyTheValuesTheyWereConstructedWith() {
            Draft draft = Draft.realistic();
            AccountUpdateRequest request = draft.build();
            List<String> readBack = new ArrayList<>();

            for (StringComponent component : StringComponent.values()) {
                String value = component.read(request);
                assertThat(value)
                        .as("%s must be readable exactly as supplied", component.described())
                        .isNotNull();
                readBack.add(component.jsonProperty() + "=" + value);
            }
            for (MoneyComponent component : MoneyComponent.values()) {
                BigDecimal value = component.read(request);
                assertThat(value)
                        .as("%s must be readable exactly as supplied", component.described())
                        .isNotNull();
                readBack.add(component.jsonProperty() + "=" + value.toPlainString());
            }

            assertThat(readBack)
                    .as("every one of the 43 accessors is exercised, and none of them duplicates "
                            + "another component's binding")
                    .hasSize(43)
                    .doesNotHaveDuplicates();
            assertThat(request.accountId()).isEqualTo(draft.accountId);
            assertThat(request.primaryCardHolderIndicator())
                    .isEqualTo(draft.primaryCardHolderIndicator);
        }

        @Test
        @DisplayName("a request survives two consecutive round trips unchanged")
        void aRequestSurvivesTwoConsecutiveRoundTripsUnchanged() throws Exception {
            AccountUpdateRequest original = Draft.realistic().build();

            AccountUpdateRequest once = MAPPER.readValue(
                    MAPPER.writeValueAsString(original), AccountUpdateRequest.class);
            AccountUpdateRequest twice = MAPPER.readValue(
                    MAPPER.writeValueAsString(once), AccountUpdateRequest.class);

            assertThat(once)
                    .as("serialisation must be lossless for every one of the 43 components")
                    .isEqualTo(original);
            assertThat(twice).isEqualTo(original);
            assertThat(MAPPER.writeValueAsString(twice))
                    .isEqualTo(MAPPER.writeValueAsString(original));
        }
    }

    /* =================================================================================
     * The 38 bounded string components.
     *
     * Declaration order follows the symbolic map. Every declared width in this table was
     * read from the symbolic map and is therefore an INDEPENDENT ORACLE: none of these
     * numbers was taken from the request under test, so a width edited on the request
     * alone will fail here rather than agree with itself.
     *
     * Each constant carries the legacy map field name (for readable diagnostics), the
     * declared screen width, the JSON property name, an accessor and a writer. The
     * accessor is an ordinary method reference and the writer an ordinary assignment
     * lambda; nothing is resolved by name at run time.
     * ================================================================================= */
    enum StringComponent {

        /* ----- account key, status and the split open date ----- */
        ACCOUNT_ID("ACCTSID", 11, "accountId",
                (d, v) -> d.accountId = v, AccountUpdateRequest::accountId),
        ACCOUNT_STATUS("ACSTTUS", 1, "accountStatus",
                (d, v) -> d.accountStatus = v, AccountUpdateRequest::accountStatus),
        OPEN_YEAR("OPNYEAR", 4, "openYear",
                (d, v) -> d.openYear = v, AccountUpdateRequest::openYear),
        OPEN_MONTH("OPNMON", 2, "openMonth",
                (d, v) -> d.openMonth = v, AccountUpdateRequest::openMonth),
        OPEN_DAY("OPNDAY", 2, "openDay",
                (d, v) -> d.openDay = v, AccountUpdateRequest::openDay),

        /* ----- the split expiry date ----- */
        EXPIRY_YEAR("EXPYEAR", 4, "expiryYear",
                (d, v) -> d.expiryYear = v, AccountUpdateRequest::expiryYear),
        EXPIRY_MONTH("EXPMON", 2, "expiryMonth",
                (d, v) -> d.expiryMonth = v, AccountUpdateRequest::expiryMonth),
        EXPIRY_DAY("EXPDAY", 2, "expiryDay",
                (d, v) -> d.expiryDay = v, AccountUpdateRequest::expiryDay),

        /* ----- the split reissue date: the fourth split date, easy to overlook ----- */
        REISSUE_YEAR("RISYEAR", 4, "reissueYear",
                (d, v) -> d.reissueYear = v, AccountUpdateRequest::reissueYear),
        REISSUE_MONTH("RISMON", 2, "reissueMonth",
                (d, v) -> d.reissueMonth = v, AccountUpdateRequest::reissueMonth),
        REISSUE_DAY("RISDAY", 2, "reissueDay",
                (d, v) -> d.reissueDay = v, AccountUpdateRequest::reissueDay),

        /* ----- account group id: editable, never decorated ----- */
        ACCOUNT_GROUP_ID("AADDGRP", 10, "accountGroupId",
                (d, v) -> d.accountGroupId = v, AccountUpdateRequest::accountGroupId),

        /* ----- customer key: editable, never decorated ----- */
        CUSTOMER_ID("ACSTNUM", 9, "customerId",
                (d, v) -> d.customerId = v, AccountUpdateRequest::customerId),

        /* ----- the split social-security number, widths 3 / 2 / 4 ----- */
        SSN_PART_1("ACTSSN1", 3, "ssnPart1",
                (d, v) -> d.ssnPart1 = v, AccountUpdateRequest::ssnPart1),
        SSN_PART_2("ACTSSN2", 2, "ssnPart2",
                (d, v) -> d.ssnPart2 = v, AccountUpdateRequest::ssnPart2),
        SSN_PART_3("ACTSSN3", 4, "ssnPart3",
                (d, v) -> d.ssnPart3 = v, AccountUpdateRequest::ssnPart3),

        /* ----- the split date of birth, declared before the credit score ----- */
        DATE_OF_BIRTH_YEAR("DOBYEAR", 4, "dateOfBirthYear",
                (d, v) -> d.dateOfBirthYear = v, AccountUpdateRequest::dateOfBirthYear),
        DATE_OF_BIRTH_MONTH("DOBMON", 2, "dateOfBirthMonth",
                (d, v) -> d.dateOfBirthMonth = v, AccountUpdateRequest::dateOfBirthMonth),
        DATE_OF_BIRTH_DAY("DOBDAY", 2, "dateOfBirthDay",
                (d, v) -> d.dateOfBirthDay = v, AccountUpdateRequest::dateOfBirthDay),

        /* ----- credit score: window 300-850 delegated, never annotated ----- */
        FICO_SCORE("ACSTFCO", 3, "ficoScore",
                (d, v) -> d.ficoScore = v, AccountUpdateRequest::ficoScore),

        /* ----- customer names and address ----- */
        FIRST_NAME("ACSFNAM", 25, "firstName",
                (d, v) -> d.firstName = v, AccountUpdateRequest::firstName),
        /** Carries no constraint at all. Its declared width is recorded but never enforced. */
        MIDDLE_NAME("ACSMNAM", 25, "middleName",
                (d, v) -> d.middleName = v, AccountUpdateRequest::middleName),
        LAST_NAME("ACSLNAM", 25, "lastName",
                (d, v) -> d.lastName = v, AccountUpdateRequest::lastName),
        ADDRESS_LINE_1("ACSADL1", 50, "addressLine1",
                (d, v) -> d.addressLine1 = v, AccountUpdateRequest::addressLine1),
        STATE_CODE("ACSSTTE", 2, "stateCode",
                (d, v) -> d.stateCode = v, AccountUpdateRequest::stateCode),
        /** Carries no constraint at all. Its declared width is recorded but never enforced. */
        ADDRESS_LINE_2("ACSADL2", 50, "addressLine2",
                (d, v) -> d.addressLine2 = v, AccountUpdateRequest::addressLine2),
        ZIP_CODE("ACSZIPC", 5, "zipCode",
                (d, v) -> d.zipCode = v, AccountUpdateRequest::zipCode),
        CITY("ACSCITY", 50, "city",
                (d, v) -> d.city = v, AccountUpdateRequest::city),
        COUNTRY_CODE("ACSCTRY", 3, "countryCode",
                (d, v) -> d.countryCode = v, AccountUpdateRequest::countryCode),

        /* ----- first telephone number, split 3 / 3 / 4 ----- */
        PHONE_1_AREA_CODE("ACSPH1A", 3, "phone1AreaCode",
                (d, v) -> d.phone1AreaCode = v, AccountUpdateRequest::phone1AreaCode),
        PHONE_1_PREFIX("ACSPH1B", 3, "phone1Prefix",
                (d, v) -> d.phone1Prefix = v, AccountUpdateRequest::phone1Prefix),
        PHONE_1_LINE_NUMBER("ACSPH1C", 4, "phone1LineNumber",
                (d, v) -> d.phone1LineNumber = v, AccountUpdateRequest::phone1LineNumber),

        /* ----- government-issued id, declared between the two telephone numbers ----- */
        GOVERNMENT_ISSUED_ID("ACSGOVT", 20, "governmentIssuedId",
                (d, v) -> d.governmentIssuedId = v, AccountUpdateRequest::governmentIssuedId),

        /* ----- second telephone number, split 3 / 3 / 4 ----- */
        PHONE_2_AREA_CODE("ACSPH2A", 3, "phone2AreaCode",
                (d, v) -> d.phone2AreaCode = v, AccountUpdateRequest::phone2AreaCode),
        PHONE_2_PREFIX("ACSPH2B", 3, "phone2Prefix",
                (d, v) -> d.phone2Prefix = v, AccountUpdateRequest::phone2Prefix),
        PHONE_2_LINE_NUMBER("ACSPH2C", 4, "phone2LineNumber",
                (d, v) -> d.phone2LineNumber = v, AccountUpdateRequest::phone2LineNumber),

        /* ----- transfer account id and primary-holder indicator ----- */
        EFT_ACCOUNT_ID("ACSEFTC", 10, "eftAccountId",
                (d, v) -> d.eftAccountId = v, AccountUpdateRequest::eftAccountId),
        PRIMARY_CARD_HOLDER_INDICATOR("ACSPFLG", 1, "primaryCardHolderIndicator",
                (d, v) -> d.primaryCardHolderIndicator = v,
                AccountUpdateRequest::primaryCardHolderIndicator);

        /**
         * The two components the legacy program decorates for error display but never validates.
         * They must carry no constraint whatsoever, not even a width constraint.
         */
        static final Set<StringComponent> UNVALIDATED =
                Set.of(MIDDLE_NAME, ADDRESS_LINE_2);

        /**
         * The four components that are editable on the mapset but are not among the 39 decoration
         * targets: 43 unprotected fields minus 39 decoration sites.
         */
        static final Set<StringComponent> UNDECORATED =
                Set.of(ACCOUNT_ID, ACCOUNT_GROUP_ID, CUSTOMER_ID, GOVERNMENT_ISSUED_ID);

        private final String mapField;
        private final int declaredWidth;
        private final String jsonProperty;
        private final BiConsumer<Draft, String> writer;
        private final Function<AccountUpdateRequest, String> reader;

        StringComponent(String mapField, int declaredWidth, String jsonProperty,
                BiConsumer<Draft, String> writer,
                Function<AccountUpdateRequest, String> reader) {
            this.mapField = mapField;
            this.declaredWidth = declaredWidth;
            this.jsonProperty = jsonProperty;
            this.writer = writer;
            this.reader = reader;
        }

        String mapField() {
            return mapField;
        }

        int declaredWidth() {
            return declaredWidth;
        }

        String jsonProperty() {
            return jsonProperty;
        }

        void write(Draft draft, String value) {
            writer.accept(draft, value);
        }

        String read(AccountUpdateRequest request) {
            return reader.apply(request);
        }

        /** A value occupying the field exactly, with no leading or trailing space. */
        String exactWidthValue() {
            return "X".repeat(declaredWidth);
        }

        /** A value occupying the field exactly and ending in a space that must not be trimmed. */
        String trailingSpaceValue() {
            return declaredWidth == 1 ? " " : "X".repeat(declaredWidth - 1) + " ";
        }

        /** A value occupying the field exactly and starting with a space that must not be cut. */
        String leadingSpaceValue() {
            return declaredWidth == 1 ? " " : " " + "X".repeat(declaredWidth - 1);
        }

        /** One character past the declared width - rejected only where a width is enforced. */
        String overWidthValue() {
            return "X".repeat(declaredWidth + 1);
        }

        /** Readable diagnostic prefix naming the legacy field and its declared width. */
        String described() {
            return name() + " (map field " + mapField + ", declared width " + declaredWidth + ")";
        }
    }

    /* =================================================================================
     * The 5 exact-decimal components.
     *
     * All five are 15 characters wide on the screen and are signed zoned decimals with
     * ten integer digits and two decimal places in the account record, which maps to a
     * numeric column of precision 12 and scale 2. Scale 2 is a CONTRACT the service and
     * the codec honour, not something this request applies: the request must carry a
     * decimal through untouched, whatever its scale.
     * ================================================================================= */
    enum MoneyComponent {

        CREDIT_LIMIT("ACRDLIM", "creditLimit",
                (d, v) -> d.creditLimit = v, AccountUpdateRequest::creditLimit),
        CASH_CREDIT_LIMIT("ACSHLIM", "cashCreditLimit",
                (d, v) -> d.cashCreditLimit = v, AccountUpdateRequest::cashCreditLimit),
        CURRENT_BALANCE("ACURBAL", "currentBalance",
                (d, v) -> d.currentBalance = v, AccountUpdateRequest::currentBalance),
        CURRENT_CYCLE_CREDIT("ACRCYCR", "currentCycleCredit",
                (d, v) -> d.currentCycleCredit = v, AccountUpdateRequest::currentCycleCredit),
        CURRENT_CYCLE_DEBIT("ACRCYDB", "currentCycleDebit",
                (d, v) -> d.currentCycleDebit = v, AccountUpdateRequest::currentCycleDebit);

        /** Every monetary field is 15 characters wide on this screen. */
        static final int SCREEN_WIDTH = 15;

        /** The record's decimal places, and therefore the contract scale. */
        static final int CONTRACT_SCALE = 2;

        private final String mapField;
        private final String jsonProperty;
        private final BiConsumer<Draft, BigDecimal> writer;
        private final Function<AccountUpdateRequest, BigDecimal> reader;

        MoneyComponent(String mapField, String jsonProperty,
                BiConsumer<Draft, BigDecimal> writer,
                Function<AccountUpdateRequest, BigDecimal> reader) {
            this.mapField = mapField;
            this.jsonProperty = jsonProperty;
            this.writer = writer;
            this.reader = reader;
        }

        String mapField() {
            return mapField;
        }

        String jsonProperty() {
            return jsonProperty;
        }

        void write(Draft draft, BigDecimal value) {
            writer.accept(draft, value);
        }

        BigDecimal read(AccountUpdateRequest request) {
            return reader.apply(request);
        }

        String described() {
            return name() + " (map field " + mapField + ", 15 characters on screen)";
        }
    }
}
