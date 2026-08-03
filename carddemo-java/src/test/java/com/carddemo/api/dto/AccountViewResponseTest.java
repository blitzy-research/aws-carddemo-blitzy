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
 * {@code CAVW}.
 *
 * <p>The subject is derived from four artefacts of the legacy estate, all read as reference and none
 * copied: the program {@code app/cbl/COACTVWC.cbl}, its symbolic map {@code app/cpy-bms/COACTVW.CPY},
 * the mapset {@code app/bms/COACTVW.bms}, and the two record layouts {@code app/cpy/CVACT01Y.cpy} and
 * {@code app/cpy/CVCUS01Y.cpy}. Every width, line number and count quoted below was measured against
 * those files at the analysed checkout, whose commit hash and upstream release stamp are recorded once
 * in the traceability matrix rather than embedded as a constant anywhere in the module.
 *
 * <h2>What this class owns</h2>
 *
 * <p>Two folder-level acceptance obligations land here.
 *
 * <p><strong>The decimal half.</strong> Each of the five monetary components is checked independently
 * for the same five properties: a static type of {@link BigDecimal}, a scale of exactly two preserved
 * in both directions, plain rather than exponential rendering on the wire, faithful binding back from a
 * plain decimal, and correct carriage of a negative and of a zero amount. The estate carries negative
 * cycle amounts, and the screen's own numeric edit reserves a leading sign position precisely because a
 * balance can be negative, so neither case is hypothetical.
 *
 * <p><strong>The read-path half of the credit-score obligation.</strong> The score carries no range
 * constraint here, and that is a measurement rather than an omission: of the fifty seeded customer
 * records, <strong>twenty-one score below 300 and the lowest is {@code 001}</strong>. A lower bound of
 * 300 on this read path would make forty-two per cent of the reference data unviewable. The 300-to-850
 * window is a rule of the update path and is asserted there, against the update request type, never
 * here.
 *
 * <h2>A pure unit test, with no run-time type inspection anywhere</h2>
 *
 * <p>Nothing here starts an application context, a container or a database. The subject is constructed
 * directly, and where the wire shape is what is under test it is serialised with a mapper built locally
 * in this file from the six Jackson settings the module declares in
 * {@code src/main/resources/application.yml}, so an asserted payload is the payload a client actually
 * receives.
 *
 * <p>No assertion in this class inspects an annotation, a field or a record component at run time, and
 * that constraint made the checks stronger rather than weaker, because every such question has a
 * better answer:
 *
 * <ul>
 *   <li><em>Is the accessor's static type really {@code BigDecimal}?</em> Each of the five accessors is
 *       bound to a {@code Function<AccountViewResponse, BigDecimal>} in {@link #MONEY_SLOTS}, and each
 *       is also assigned to a {@link BigDecimal} local. {@link BigDecimal} is final, so a component
 *       typed {@code double}, {@code Double}, {@code float}, {@code Float} or {@link String} would not
 *       compile. The compiler enforces it, which fails the build rather than one test.</li>
 *   <li><em>Is the type an immutable record?</em> An instance is assigned to a {@link Record} local,
 *       which compiles only for a record, and record components are final by language rule, so no
 *       mutator can exist to look for.</li>
 *   <li><em>Are exactly the thirty-seven map items and four control components declared, and nothing
 *       else?</em> The serialised key set is compared, in order, against the independently transcribed
 *       list in {@link #COMPONENTS_IN_MAP_ORDER}. That single assertion also proves the absence of
 *       everything the wire must not carry: no map control-byte group, no filler, no coordinate or
 *       terminal attribute, no optimistic-lock or version member, and none of the RFC-7807 members,
 *       which this module deliberately does not use.</li>
 *   <li><em>Does the type carry an unknown-property annotation of its own?</em> A second, strict mapper
 *       with unknown-property failure enabled must reject an unknown key. Tolerance therefore comes
 *       from the module's mapper configuration and not from an annotation on the type.</li>
 *   <li><em>Is a width bound the only constraint?</em> A real {@link Validator} is asked. Zero
 *       violations for the all-absent instance and for the four awkward-but-legitimate data shapes,
 *       and exactly one violation when a value exceeds its map width - the last of these being the
 *       control that proves validation ran at all.</li>
 * </ul>
 *
 * <h2>Fixture values are the measured ones</h2>
 *
 * <p>Every text component is populated at exactly its map width, padding included, because the map
 * items are fixed-width and space-significant. The four widths the contract singles out are asserted by
 * name: an eight-character time, an eleven-character account identifier, a forty-five-character
 * informational line and a seventy-eight-character error line.
 *
 * <p>The clock is pinned. Where a date or time value is needed it is the fixed text form of the instant
 * 2022-06-10 19:27:53, never a reading of the system clock, and never a temporal type: these components
 * are opaque screen text, so no date library and no formatter appears in this file at all.
 *
 * <p>No credential appears here in any form. The national identifier is the reserved-and-never-issued
 * form {@code 000-00-0000}, so no real identifier is present either.
 *
 * @see AccountStatus
 * @see NavigationContext
 * @since 1.0.0
 */
@DisplayName("AccountViewResponse")
class AccountViewResponseTest {

    /**
     * The forty-one components in declaration order: the thirty-seven value items of
     * {@code app/cpy-bms/COACTVW.CPY} in map order, then the four response-only components.
     *
     * <p>Transcribed independently from the map rather than derived from the type, so that comparing
     * the serialised key set against it is a real check and not a tautology. The map order is followed
     * literally, which is why the state code sits between the two address lines and the account group
     * identifier sits between the two cycle amounts: regrouping them into a conventional order would
     * silently renegotiate the contract.
     */
    private static final List<String> COMPONENTS_IN_MAP_ORDER = List.of(
            "transactionName", "title01", "currentDate", "programName", "title02", "currentTime",
            "accountId", "accountStatus", "openDate", "creditLimit", "expirationDate",
            "cashCreditLimit", "reissueDate", "currentBalance", "currentCycleCredit",
            "accountGroupId", "currentCycleDebit", "customerId", "ssn", "dateOfBirth", "ficoScore",
            "firstName", "middleName", "lastName", "addressLine1", "stateCode", "addressLine2",
            "zipCode", "city", "countryCode", "phoneNumber1", "governmentIssuedId", "phoneNumber2",
            "eftAccountId", "primaryCardHolderIndicator", "infoMessage", "errorMessage",
            "inputError", "focusScreenFieldId", "nextRoute", "navigationContext");

    /* ------------------------------------------------------------------------------------------
     * The thirty-seven map values, each at the exact width its map item declares.
     *
     * Widths are quoted from app/cpy-bms/COACTVW.CPY with the declaring line, and every one of them
     * was measured rather than assumed. Two are worth singling out because a neighbouring screen
     * declares the same name at a different width, and normalising either would break a contract:
     *
     *   INFOMSGO is X(45) here (line 458) and X(45) on COACTUP (line 644),
     *             but X(40) on COCRDSL (line 188) and X(40) on COCRDUP (line 206).
     *   ERRMSGO  is X(78) here (line 464) and X(78) on COACTUP (line 650),
     *             but X(80) on COCRDSL (line 194) and X(80) on COCRDUP (line 212).
     *
     * The current-time item is the third such case: eight characters here (line 278), where the
     * sign-on map declares its own time item one character wider, uniquely in the estate.
     * ------------------------------------------------------------------------------------------ */

    /** {@code TRNNAMEO}, width 4, map line 248. */
    private static final String TRANSACTION_NAME = "CAVW";

    /** {@code TITLE01O}, width 40, map line 254. */
    private static final String TITLE_01 = "CardDemo Account View                   ";

    /** {@code CURDATEO}, width 8, map line 260. The pinned date, as screen text. */
    private static final String CURRENT_DATE = "06/10/22";

    /** {@code PGMNAMEO}, width 8, map line 266. */
    private static final String PROGRAM_NAME = "COACTVWC";

    /** {@code TITLE02O}, width 40, map line 272. */
    private static final String TITLE_02 = "View Account                            ";

    /**
     * {@code CURTIMEO}, width <strong>8</strong>, map line 278. The pinned time, as screen text.
     *
     * <p>Eight is measured on this map. The sign-on map declares its own time item one character
     * wider, uniquely across the estate's seventeen symbolic maps, so this width must not be copied
     * from there.
     */
    private static final String CURRENT_TIME = "19:27:53";

    /** {@code ACCTSIDO}, width 11, map line 284. Leading zeros are contractual. */
    private static final String ACCOUNT_ID = "00000000011";

    /** {@code ACSTTUSO}, width 1, map line 290. The declared active code. */
    private static final String ACCOUNT_STATUS = "Y";

    /** {@code ADTOPENO}, width 10, map line 296. Text, never a temporal type. */
    private static final String OPEN_DATE = "2020-01-15";

    /**
     * {@code AEXPDTO}, width 10, map line 308.
     *
     * <p>The stored field is misspelled {@code ACCT-EXPIRAION-DATE} at {@code app/cpy/CVACT01Y.cpy}
     * line 11. The record layout keeps that spelling so images stay byte-compatible; the Java
     * component is spelled correctly, and the defect is carried in the decision log rather than into
     * an identifier.
     */
    private static final String EXPIRATION_DATE = "2028-12-31";

    /** {@code AREISDTO}, width 10, map line 320. Text, never a temporal type. */
    private static final String REISSUE_DATE = "2024-06-30";

    /**
     * {@code AADDGRPO}, width 10, map line 338.
     *
     * <p>Ten spaces, which is what {@code ACCT-GROUP-ID} holds at offset 112 in all fifty seeded
     * account records. The padding is the value: a component that came back shortened, or read as
     * absent, would be a parity defect rather than a tidier result.
     */
    private static final String ACCOUNT_GROUP_ID = "          ";

    /** {@code ACSTNUMO}, width 9, map line 350. Leading zeros are contractual. */
    private static final String CUSTOMER_ID = "000000011";

    /**
     * {@code ACSTSSNO}, width 12, map line 356, in the hyphenated form the program assembles.
     *
     * <p>Twelve characters because the program builds a three-two-four hyphenated form from the nine
     * stored digits. The value used here is the reserved sequence that is never issued, so the fixture
     * carries no real identifier while still exercising the exact width and punctuation.
     */
    private static final String SSN = "000-00-0000 ";

    /** {@code ACSTDOBO}, width 10, map line 362. Text, never a temporal type. */
    private static final String DATE_OF_BIRTH = "1985-03-22";

    /**
     * {@code ACSTFCOO}, width 3, map line 368.
     *
     * <p>The lowest score in the seeded reference data, and the reason this component carries no
     * range constraint. Held as text so the three characters survive instead of collapsing to one.
     */
    private static final String FICO_SCORE = "001";

    /** {@code ACSFNAMO}, width 25, map line 374. Space padding preserved. */
    private static final String FIRST_NAME = "Mary                     ";

    /**
     * {@code ACSMNAMO}, width 25, map line 380.
     *
     * <p>One of the two components the update program decorates for error display but never edits.
     * The value carries a space <em>between two letters</em> rather than only trailing padding, and
     * that is deliberate: the legacy alphabetic check blanks every letter and then tests the remainder
     * for emptiness, so a space inside a name passes it. Nothing on this read path may reject one, and
     * a fixture padded only on the right would not have put the question.
     */
    private static final String MIDDLE_NAME = "Ann Marie                ";

    /** {@code ACSLNAMO}, width 25, map line 386. Space padding preserved. */
    private static final String LAST_NAME = "Vandelay                 ";

    /** {@code ACSADL1O}, width 50, map line 392. */
    private static final String ADDRESS_LINE_1 = "1 Corporate Way                                   ";

    /**
     * {@code ACSSTTEO}, width 2, map line 398.
     *
     * <p>The map declares it between the two address lines and that order is followed. Not checked
     * against the state table, which belongs to the update path.
     */
    private static final String STATE_CODE = "NY";

    /**
     * {@code ACSADL2O}, width 50, map line 404.
     *
     * <p>The second of the two components decorated but never edited by the update program.
     */
    private static final String ADDRESS_LINE_2 = "Suite 400                                         ";

    /**
     * {@code ACSZIPCO}, width 5, map line 410.
     *
     * <p>Five characters, fed from the ten-character stored postal code. The narrowing happens where
     * the value is projected, never on this record.
     */
    private static final String ZIP_CODE = "10118";

    /**
     * {@code ACSCITYO}, width 50, map line 416.
     *
     * <p>Populated from the <em>third</em> address line; the customer layout declares no city field.
     */
    private static final String CITY = "New York                                          ";

    /** {@code ACSCTRYO}, width 3, map line 422. */
    private static final String COUNTRY_CODE = "USA";

    /**
     * {@code ACSPHN1O}, width 13, map line 428, already formatted.
     *
     * <p>The stored field is fifteen characters holding thirteen characters of formatted text and two
     * trailing spaces; this is the shape every one of the fifty seeded customer records carries. It is
     * transported verbatim: never parsed, never split, never re-formatted, and never stripped of its
     * parentheses or hyphen.
     */
    private static final String PHONE_NUMBER_1 = "(908)119-8310";

    /** {@code ACSGOVTO}, width 20, map line 434. Synthetic, and transported unaltered. */
    private static final String GOVERNMENT_ISSUED_ID = "GOVT-ID-0000000011  ";

    /** {@code ACSPHN2O}, width 13, map line 440. Formatted exactly as the primary number. */
    private static final String PHONE_NUMBER_2 = "(373)693-8684";

    /** {@code ACSEFTCO}, width 10, map line 446. Text, never a numeric type. */
    private static final String EFT_ACCOUNT_ID = "EFT0000001";

    /**
     * {@code ACSPFLGO}, width 1, map line 452.
     *
     * <p>Carried as the raw character. No program in the estate compares this field against a
     * literal, so resolving it to a boolean would assert a vocabulary the source does not declare.
     */
    private static final String PRIMARY_CARD_HOLDER_INDICATOR = "Y";

    /** {@code INFOMSGO}, width <strong>45</strong>, map line 458. */
    private static final String INFO_MESSAGE = "Enter account number and press Enter         ";

    /** {@code ERRMSGO}, width <strong>78</strong>, map line 464. */
    private static final String ERROR_MESSAGE =
            "Account:00000000011 not found in Cross ref file.                              ";

    /* ------------------------------------------------------------------------------------------
     * The four response-only components.
     * ------------------------------------------------------------------------------------------ */

    /**
     * The widest legacy screen field name there is, and the value this program always nominates.
     *
     * <p>Seven characters: the map generator reserves the eighth position of a symbolic name for the
     * suffix it appends, so no declared field name can occupy it, and this mapset's longest names are
     * exactly seven.
     */
    private static final String FOCUS_SCREEN_FIELD_ID = "ACCTSID";

    /**
     * An opaque route value. The service layer owns the vocabulary; this record carries the text.
     *
     * <p>Deliberately free of any business key. The diagnostic rendering retains the route verbatim -
     * correctly, since a route is server-defined and describes the shape of a response rather than a
     * person - so a route with an identifier written into it would put that identifier back into the
     * rendering after the identifier components had been withheld from it. The identifier reaches this
     * screen as a request parameter rather than as part of a path, so a key-free route is also the
     * realistic value. Asserted in both directions below.
     */
    private static final String NEXT_ROUTE = "/api/accounts/view";

    /**
     * The echoed conversation state, with both identifiers carrying leading zeros.
     *
     * <p>This is the replacement for the communication area the legacy transaction carried across a
     * pseudo-conversational turn. It is echoed by the client rather than held server-side, so the
     * response's obligation is to carry it back unchanged and nothing more. The user identifier is
     * synthetic and no credential component exists on the type to populate.
     *
     * <p>The card number is the zero-padded placeholder this test tree uses throughout rather than any
     * publishable test card number. Both would be equally non-functional, but the padded form is not
     * shaped like a payment card at all, so it cannot be mistaken for cardholder data by a reader or by
     * a scanner. The response under test carries no card number of its own, and none may be added.
     */
    private static final NavigationContext NAVIGATION_CONTEXT = new NavigationContext(
            "CAVW", "COACTVWC", "CAVW", "COACTVWC", "TESTUSR1", "U",
            NavigationContext.ProgramContext.REENTER, CUSTOMER_ID,
            FIRST_NAME, MIDDLE_NAME, LAST_NAME, ACCOUNT_ID, ACCOUNT_STATUS,
            "0000000000000001", "CACTVWA", "COACTVW");

    /* ------------------------------------------------------------------------------------------
     * The five monetary values.
     *
     * Their record counterparts are the signed zoned decimals of app/cpy/CVACT01Y.cpy - lines 7, 8, 9,
     * 13 and 14 - each with ten integer digits and two decimal places, so a total precision of twelve
     * and a scale of exactly two. The corresponding map items carry a numeric-edited screen picture at
     * lines 302, 314, 326, 332 and 344, and that edit is deliberately absent from the wire contract:
     * it is 3270 presentation, and reproducing it would smuggle a display artefact into a machine
     * interface. Nothing in this file formats, groups, signs, scales or rounds an amount.
     * ------------------------------------------------------------------------------------------ */

    /** A positive credit limit at contract scale. */
    private static final BigDecimal CREDIT_LIMIT = new BigDecimal("5000.00");

    /** A positive cash credit limit at contract scale. */
    private static final BigDecimal CASH_CREDIT_LIMIT = new BigDecimal("1500.00");

    /** A negative balance - a real state, which is why the screen edit reserves a sign position. */
    private static final BigDecimal CURRENT_BALANCE = new BigDecimal("-247.83");

    /** A zero cycle credit, which must keep its two decimal places rather than collapse to {@code 0}. */
    private static final BigDecimal CURRENT_CYCLE_CREDIT = new BigDecimal("0.00");

    /** A positive cycle debit at contract scale. */
    private static final BigDecimal CURRENT_CYCLE_DEBIT = new BigDecimal("312.45");

    /** The widest amount the record field can hold: ten integer digits, negative, at scale two. */
    private static final BigDecimal WIDEST_AMOUNT = new BigDecimal("-9999999999.99");

    /**
     * One integer digit too many for the record field, and otherwise perfectly well formed.
     *
     * <p>Eleven integer digits at scale two. Used to show that the shape check reads the amount's own
     * precision rather than its text.
     */
    private static final BigDecimal TOO_WIDE_AMOUNT = new BigDecimal("12345678901.00");

    /**
     * The value whose own rendering is exponential: unscaled one at scale minus two.
     *
     * <p>This is the amount the acceptance obligation names. Its {@link BigDecimal#toString()} is
     * {@code 1E+2}, and it is the shape a plain-decimal contract must never emit. Two independent
     * guards keep it off the wire, and both are asserted: the canonical constructor refuses a scale
     * other than two, so this value cannot enter the record at all, and the module's mapper is
     * configured to write decimals plainly even when handed one.
     */
    private static final BigDecimal EXPONENTIAL_FORM = new BigDecimal("1E+2");

    /**
     * The same numeric value as {@link #EXPONENTIAL_FORM}, expressed at scale two: {@code 100.00}.
     *
     * <p>Built from an unscaled integer and an explicit scale rather than by re-scaling the
     * exponential form, because re-scaling is a rounding decision and this module concentrates every
     * rounding decision in the zoned-decimal codec of the utility layer. That codec truncates toward
     * zero, since the estate declares no rounding anywhere, and it is deliberately neither imported
     * here nor used to produce an expected value: an expected value must come from an independent
     * oracle, which here is the literal decimal text a reader can check by eye.
     */
    private static final BigDecimal PLAIN_FORM = new BigDecimal(BigInteger.valueOf(10_000L), 2);

    /**
     * One monetary component, paired with the two things a test needs to exercise it in isolation.
     *
     * <p>The accessor is held as a {@code Function<AccountViewResponse, BigDecimal>}, which is where
     * the static-type obligation is discharged: a method reference conforms to that type only if the
     * accessor returns {@link BigDecimal}. Because {@link BigDecimal} is final, an accessor returning
     * {@code double}, {@code Double}, {@code float}, {@code Float} or {@link String} would fail to
     * compile, so the check is enforced by the compiler on every build rather than by an assertion.
     *
     * @param jsonKey the property name this component serialises under
     * @param accessor the component accessor, typed to prove its return type
     * @param carrying builds a response carrying an amount in this slot and nothing in the other four
     */
    private record MoneySlot(String jsonKey,
            Function<AccountViewResponse, BigDecimal> accessor,
            Function<BigDecimal, AccountViewResponse> carrying) {
    }

    /** The five monetary components, each exercised independently by the decimal tests. */
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

    /* ------------------------------------------------------------------------------------------
     * Fixture builders.
     *
     * The record declares forty-one components and has no wither, which is exactly right for an
     * immutable response and slightly awkward for a test. One builder therefore takes the twelve
     * components any test here needs to vary and fixes the remaining twenty-nine at their measured
     * map-width values, and the named wrappers below read as the single fact each test is about.
     * ------------------------------------------------------------------------------------------ */

    /**
     * Builds a fully populated response, varying only the components a test needs to control.
     *
     * <p>Every other component is set to its measured map-width value, space padding included.
     *
     * @param accountId the account identifier to carry
     * @param accountStatus the raw one-character status to carry
     * @param ssn the displayed national identifier to carry, or {@code null} for the absent case
     * @param ficoScore the credit score to carry
     * @param focusScreenFieldId the focus hint to carry
     * @param nextRoute the opaque route to carry
     * @param navigationContext the echoed conversation state to carry
     * @param creditLimit the credit limit to carry
     * @param cashCreditLimit the cash credit limit to carry
     * @param currentBalance the current balance to carry
     * @param currentCycleCredit the current cycle credit to carry
     * @param currentCycleDebit the current cycle debit to carry
     * @return a populated response
     */
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
                // Screen header, map lines 248 to 278.
                TRANSACTION_NAME, TITLE_01, CURRENT_DATE, PROGRAM_NAME, TITLE_02, CURRENT_TIME,
                // Account body, map lines 284 to 344, in map order.
                accountId, accountStatus, OPEN_DATE, creditLimit, EXPIRATION_DATE, cashCreditLimit,
                REISSUE_DATE, currentBalance, currentCycleCredit, ACCOUNT_GROUP_ID,
                currentCycleDebit,
                // Customer body, map lines 350 to 452, in map order.
                CUSTOMER_ID, ssn, DATE_OF_BIRTH, ficoScore, FIRST_NAME, MIDDLE_NAME, LAST_NAME,
                ADDRESS_LINE_1, STATE_CODE, ADDRESS_LINE_2, ZIP_CODE, CITY, COUNTRY_CODE,
                PHONE_NUMBER_1, GOVERNMENT_ISSUED_ID, PHONE_NUMBER_2, EFT_ACCOUNT_ID,
                PRIMARY_CARD_HOLDER_INDICATOR,
                // Message lines, map lines 458 and 464.
                INFO_MESSAGE, ERROR_MESSAGE,
                // The four response-only components.
                true, focusScreenFieldId, nextRoute, navigationContext);
    }

    /**
     * Builds the canonical fully populated response.
     *
     * @return a response with every component at its measured value
     */
    private static AccountViewResponse populated() {
        return populated(ACCOUNT_ID, ACCOUNT_STATUS, SSN, FICO_SCORE, FOCUS_SCREEN_FIELD_ID,
                NEXT_ROUTE, NAVIGATION_CONTEXT, CREDIT_LIMIT, CASH_CREDIT_LIMIT, CURRENT_BALANCE,
                CURRENT_CYCLE_CREDIT, CURRENT_CYCLE_DEBIT);
    }

    /**
     * Builds a response in which every component is absent.
     *
     * <p>A real displayed state rather than a contrivance: the program clears the account-number item
     * when the filter was left empty, so an empty screen is something the legacy transaction produces.
     * The rejection flag is primitive, so its absent form is {@code false}.
     *
     * @return a response carrying nothing
     */
    private static AccountViewResponse allAbsent() {
        return new AccountViewResponse(
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, false, null,
                null, null);
    }

    /**
     * Builds a response carrying only the five monetary components.
     *
     * @param creditLimit the credit limit to carry
     * @param cashCreditLimit the cash credit limit to carry
     * @param currentBalance the current balance to carry
     * @param currentCycleCredit the current cycle credit to carry
     * @param currentCycleDebit the current cycle debit to carry
     * @return a response carrying the supplied amounts and nothing else
     */
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
                false, null, null, null);
    }

    /**
     * Builds a populated response carrying the supplied account identifier.
     *
     * @param accountId the account identifier to carry
     * @return a populated response
     */
    private static AccountViewResponse withAccountId(String accountId) {
        return populated(accountId, ACCOUNT_STATUS, SSN, FICO_SCORE, FOCUS_SCREEN_FIELD_ID,
                NEXT_ROUTE, NAVIGATION_CONTEXT, CREDIT_LIMIT, CASH_CREDIT_LIMIT, CURRENT_BALANCE,
                CURRENT_CYCLE_CREDIT, CURRENT_CYCLE_DEBIT);
    }

    /**
     * Builds a populated response carrying the supplied raw status character.
     *
     * @param accountStatus the raw status value to carry, which need not be a declared code
     * @return a populated response
     */
    private static AccountViewResponse withAccountStatus(String accountStatus) {
        return populated(ACCOUNT_ID, accountStatus, SSN, FICO_SCORE, FOCUS_SCREEN_FIELD_ID,
                NEXT_ROUTE, NAVIGATION_CONTEXT, CREDIT_LIMIT, CASH_CREDIT_LIMIT, CURRENT_BALANCE,
                CURRENT_CYCLE_CREDIT, CURRENT_CYCLE_DEBIT);
    }

    /**
     * Builds a populated response carrying the supplied displayed national identifier.
     *
     * @param ssn the value to carry, or {@code null} for the absent case the reference seed produces
     * @return a populated response
     */
    private static AccountViewResponse withSsn(String ssn) {
        return populated(ACCOUNT_ID, ACCOUNT_STATUS, ssn, FICO_SCORE, FOCUS_SCREEN_FIELD_ID,
                NEXT_ROUTE, NAVIGATION_CONTEXT, CREDIT_LIMIT, CASH_CREDIT_LIMIT, CURRENT_BALANCE,
                CURRENT_CYCLE_CREDIT, CURRENT_CYCLE_DEBIT);
    }

    /**
     * Builds a populated response carrying the supplied credit score.
     *
     * @param ficoScore the score to carry, including one below the screen's documented range
     * @return a populated response
     */
    private static AccountViewResponse withFicoScore(String ficoScore) {
        return populated(ACCOUNT_ID, ACCOUNT_STATUS, SSN, ficoScore, FOCUS_SCREEN_FIELD_ID,
                NEXT_ROUTE, NAVIGATION_CONTEXT, CREDIT_LIMIT, CASH_CREDIT_LIMIT, CURRENT_BALANCE,
                CURRENT_CYCLE_CREDIT, CURRENT_CYCLE_DEBIT);
    }

    /**
     * Builds a populated response carrying the supplied route.
     *
     * @param nextRoute the opaque route to carry
     * @return a populated response
     */
    private static AccountViewResponse withNextRoute(String nextRoute) {
        return populated(ACCOUNT_ID, ACCOUNT_STATUS, SSN, FICO_SCORE, FOCUS_SCREEN_FIELD_ID,
                nextRoute, NAVIGATION_CONTEXT, CREDIT_LIMIT, CASH_CREDIT_LIMIT, CURRENT_BALANCE,
                CURRENT_CYCLE_CREDIT, CURRENT_CYCLE_DEBIT);
    }

    /**
     * Builds a populated response carrying the supplied conversation state.
     *
     * @param navigationContext the echoed state to carry
     * @return a populated response
     */
    private static AccountViewResponse withNavigationContext(NavigationContext navigationContext) {
        return populated(ACCOUNT_ID, ACCOUNT_STATUS, SSN, FICO_SCORE, FOCUS_SCREEN_FIELD_ID,
                NEXT_ROUTE, navigationContext, CREDIT_LIMIT, CASH_CREDIT_LIMIT, CURRENT_BALANCE,
                CURRENT_CYCLE_CREDIT, CURRENT_CYCLE_DEBIT);
    }

    /* ------------------------------------------------------------------------------------------
     * Serialisation and validation fixtures.
     * ------------------------------------------------------------------------------------------ */

    /**
     * Builds a mapper carrying the module's own serialisation settings.
     *
     * <p>All six settings declared under the mapper section of
     * {@code src/main/resources/application.yml} are mirrored, so an asserted payload is the payload a
     * client receives rather than a Jackson default. Absent properties are omitted rather than written
     * as nulls; date values are not written as numbers; unknown incoming properties are tolerated; a
     * bare number may not bind an enumerated component; a fractional number may not bind an integral
     * one; and decimals are written plainly rather than exponentially.
     *
     * <p>Built locally in this file rather than injected or shared, because the whole point of a unit
     * test is that nothing outside the file under test decides what it means.
     *
     * @return a mapper equivalent to the module's own
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
     * Builds a mapper that refuses an unknown property.
     *
     * <p>This is the control that proves where unknown-property tolerance comes from. If the response
     * type carried an ignore-unknown annotation of its own, this mapper would accept an unknown key
     * too; because it must reject one, tolerance is a property of the module's configuration and of
     * nothing on the type. That matters, since an annotation on the type would apply everywhere and
     * could not be reconsidered per profile.
     *
     * @return a mapper with unknown-property failure enabled
     */
    private static ObjectMapper strictUnknownPropertyMapper() {
        return JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    /**
     * Serialises a response with the module's settings and reads the result back as a tree.
     *
     * @param response the response to serialise
     * @return the parsed payload
     * @throws JsonProcessingException if serialisation or parsing fails
     */
    private static JsonNode payloadOf(AccountViewResponse response) throws JsonProcessingException {
        ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(response));
    }

    /**
     * Returns the property names a payload actually carries, in payload order.
     *
     * <p>Read from the serialised payload rather than from the type, which is what makes the
     * comparison against the independently transcribed component list meaningful.
     *
     * @param payload the parsed payload
     * @return the property names present, in order
     */
    private static Set<String> keysOf(JsonNode payload) {
        Set<String> keys = new LinkedHashSet<>();
        for (Map.Entry<String, JsonNode> property : payload.properties()) {
            keys.add(property.getKey());
        }
        return keys;
    }

    /**
     * Validates a response with a plain Bean Validation validator.
     *
     * <p>Obtained from the default provider directly rather than from an application context, so no
     * framework configuration can add, remove or reorder a constraint behind the assertion.
     *
     * @param response the response to validate
     * @return the violations, which for this display-only type are expected to be empty except when a
     *     value exceeds its map width
     */
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
            // The static-type obligation is discharged by the compiler twice over: every accessor in
            // MONEY_SLOTS is bound to a Function returning BigDecimal, and each result is assigned to
            // a BigDecimal local here. BigDecimal is final and unrelated to Double, Float and String,
            // so any of those types in a component would fail to compile rather than fail an
            // assertion. An approximate binary type would also breach the mapping table's requirement
            // that decimal precision be identical.
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

                // Scale and plain text are compared separately and explicitly. Comparing by numeric
                // value alone would treat 1234567890.1 and 1234567890.12 as the same amount and let a
                // scale drift through unnoticed, which is the whole defect this contract exists to
                // prevent.
                assertThat(carried.scale())
                        .describedAs("scale carried by %s", slot.jsonKey())
                        .isEqualTo(AccountViewResponse.MONEY_SCALE);
                assertThat(carried.toPlainString())
                        .describedAs("plain text carried by %s", slot.jsonKey())
                        .isEqualTo("1234567890.12");

                // The outbound direction is asserted on the serialised text and the inbound direction
                // by binding that text straight back into the record. Neither goes through a parsed
                // numeric node: a tree read of a JSON decimal yields a binary floating-point node
                // whose scale is an artefact of that conversion, so asserting on one would measure the
                // parser rather than the contract.
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
                // Asserted against the serialised text rather than a re-parsed node: reading a
                // payload back and re-rendering it would test the parser instead of the contract.
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
            // The premise, stated rather than assumed: this value's own rendering is exponential.
            assertThat(EXPONENTIAL_FORM.scale()).isEqualTo(-2);
            assertThat(EXPONENTIAL_FORM.toString()).isEqualTo("1E+2");

            // First guard. The canonical constructor refuses any scale but two, so the exponential
            // shape cannot enter the record at all, in any of the five slots. Refusal rather than
            // re-scaling is deliberate: re-scaling is a rounding decision, and a display-only
            // response quietly altering a monetary figure would be the worst possible site for one.
            for (MoneySlot slot : MONEY_SLOTS) {
                assertThatThrownBy(() -> slot.carrying().apply(EXPONENTIAL_FORM))
                        .describedAs("refusal of an exponential amount in %s", slot.jsonKey())
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining(slot.jsonKey())
                        .hasMessageContaining("scale 2");
            }

            // Second guard, independent of the first. Handed the exponential value directly, the
            // module's own mapper still writes it plainly, so plain rendering does not depend on the
            // constructor check having run.
            assertThat(moduleEquivalentMapper().writeValueAsString(EXPONENTIAL_FORM))
                    .describedAs("the mapper's own plain-decimal setting")
                    .isEqualTo("100")
                    .doesNotContain("E");

            // And the same numeric value at contract scale crosses the boundary in plain form.
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
            // Both directions of drift are checked, because re-scaling in either would be a silent
            // change to a monetary figure. A shorter scale is refused rather than padded out, and a
            // longer scale is refused rather than truncated, so the record has no opportunity to
            // apply a rounding policy of its own. The module's one rounding policy lives in the
            // zoned-decimal codec of the utility layer and is not reachable from this package.
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
                        // The diagnostic must not become a disclosure route of its own.
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
            // The map declares these five items with a leading sign position, comma-grouped
            // zero-suppressed digits and two fixed decimals. That edit is 3270 presentation laid out
            // for a fixed column, and the wire carries a bare decimal number instead.
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
            // Five components derive from fields their record layouts declare as digits: the account
            // identifier, the customer identifier, the national identifier, the credit score and the
            // external funds-transfer identifier. Every one is text here, and the assignments below
            // are the proof: String is final, so an int, long, Integer, Long or BigInteger component
            // would not compile. The reason is contractual rather than stylistic - leading zeros and
            // fixed external widths are compared directly by the byte-equivalence criterion - and
            // these are the estate's own business keys, so none is generated or renumbered.
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

            // On the accessor and on the wire alike. A numeric component would render 00000000011 as
            // 11 and 001 as 1, and the difference is observable by any client comparing to the screen.
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
            // The informational line is forty-five characters here (map line 458) and forty-five on
            // COACTUP (line 644), but forty on COCRDSL (line 188) and forty on COCRDUP (line 206).
            // The error line is seventy-eight here (line 464) and seventy-eight on COACTUP (line 650),
            // but eighty on COCRDSL (line 194) and eighty on COCRDUP (line 212). Four widths, not two,
            // and none of them may be normalised towards another: each is the observable contract of
            // its own screen. This map's own program writes narrower working fields - forty and
            // seventy-five characters - into these wider items, and the map width is what is declared,
            // because the map width is what a client sees.
            AccountViewResponse response = populated();

            assertThat(response.infoMessage()).isEqualTo(INFO_MESSAGE).hasSize(45);
            assertThat(response.errorMessage()).isEqualTo(ERROR_MESSAGE).hasSize(78);
        }

        @Test
        @DisplayName("keeps every space-padded value untrimmed on the accessor and on the wire")
        void keepsEverySpacePaddedValueUntrimmed() throws JsonProcessingException {
            JsonNode payload = payloadOf(populated());

            // The padding is the value. A component or a payload that came back shortened, or that
            // read an all-blank value as absent, would be a parity defect rather than a tidier result.
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
            // Both numbers arrive already formatted: a parenthesised area code, the exchange, a hyphen
            // and the line number, thirteen characters wide, which is the shape all fifty seeded
            // customer records carry inside a fifteen-character stored field. They are transported
            // verbatim - never parsed, never split into parts, never re-formatted, never stripped of
            // punctuation, and never checked against the area-code tables, which belong to the update
            // path.
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
            // The three-hundred-byte account image and the five-hundred-byte customer image are
            // decoded by hand-written mappers in the utility layer, and this record is the decoded
            // result. Nothing here parses a fixed-width image, so an over-long value is carried whole
            // rather than sliced to a map width: a width bound measures, it does not cut. The bound is
            // reported as a violation by the validator instead, which is asserted separately.
            String longer = "1 Corporate Way, Thirty-Fourth Floor, Suite Four Hundred, New York";

            AccountViewResponse response = new AccountViewResponse(
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, longer, null,
                    null, null, null, null, null, null, null, null, null, null, null, false, null,
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
            // Four items are dates and all four are ten characters: the account open date (map line
            // 296), the account expiration date (line 308), the account reissue date (line 320) and
            // the customer date of birth (line 362). The assignments are the static-type proof, since
            // String is final: a temporal component would not compile into any of them. No date
            // library, no formatter and no resolver style appears in this file or on the record,
            // because a conversion would impose a calendar interpretation the legacy display never
            // performs, then lose whatever the field actually holds when that interpretation fails.
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
            // A ninety-ninth month and a ninety-ninth day. Anything that parsed or normalised these
            // components would either reject this response or silently rewrite the value; both are
            // observable, and neither happens. The screen shows what the field holds.
            String impossible = "9999-99-99";
            String blank = "          ";

            AccountViewResponse response = new AccountViewResponse(
                    null, null, null, null, null, null, null, null, impossible, null, impossible,
                    null, blank, null, null, null, null, null, null, impossible, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, false, null, null, null);
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

            // An unmapped code is reported as an absent result, not as a constant. A fallback member
            // would invent an account state the estate does not have, and it would also make the
            // active predicate's exhaustive switch fall through to a wrong answer instead of failing
            // to compile.
            assertThat(names)
                    .doesNotContain("UNKNOWN", "NONE", "OTHER", "INVALID", "DEFAULT", "UNSPECIFIED",
                            "BLANK", "NOT_OK");
        }

        @Test
        @DisplayName("excludes the two validation-flag states, which are not account states")
        void excludesTheValidationFlagStates() {
            // The legacy level-88 group that supplies this vocabulary also carries a not-OK state and
            // a blank state. Neither is ever stored in the field or written to the three-hundred-byte
            // record: they are states of the validation flag, and they belong to the field-error
            // surface, which exposes them per field as a missing value and an invalid value.
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
            // The legacy editor tests the raw character, so folding here would admit a value the
            // legacy system rejects.
            assertThat(AccountStatus.fromCode('y')).isEmpty();
            assertThat(AccountStatus.fromCode("y")).isEmpty();
            assertThat(AccountStatus.fromCode("n")).isEmpty();
            assertThat(AccountStatus.isActiveCode("y")).isFalse();
            assertThat(AccountStatus.isActiveCode("Y")).isTrue();
        }

        @Test
        @DisplayName("carries the status on the response as the raw character, not as the enumeration")
        void carriesTheStatusAsTheRawCharacter() {
            // Read from the production source and recorded here as the deliberate choice it is: the
            // response holds a one-character String, and the enumeration is offered alongside it
            // through an interpreting accessor. The assignment below is the static-type proof. The
            // reason is that the status column carries no check constraint and only the online update
            // program validates the field, so a value outside the vocabulary reaches the screen in the
            // legacy system; an enumerated component would fail construction on data the legacy
            // system displays.
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
            // The assignment is the static-type proof that the response holds the shared conversation
            // type rather than a private copy of its fields. Nothing here reconciles, rewrites or
            // interprets the state: this is a response body, and reconciliation against the
            // authenticated principal belongs to the service layer.
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
            // An online program entered with an empty communication area has no signed-on user, no
            // selection and no previous screen.
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
            // This is the control for every emptiness assertion below it. A validator that had not
            // been wired, or a type that carried no constraint at all, would report zero violations
            // for everything and make the rest of this class pass while testing nothing. One
            // twelve-character account identifier against an eleven-character map item proves the
            // validator runs, that the bound is real, and that the bound is exactly where the map
            // puts it.
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
            // No presence constraint of any kind: not required, not non-blank, not non-empty. An
            // absent value is a real displayed state - the program clears the account-number item when
            // the filter was left empty - and a response that refused to render it would be a
            // behavioural regression rather than a safeguard. A width bound is satisfied by an absent
            // value, which is why the only constraint on the type is silent here.
            assertThat(violationsOf(allAbsent())).isEmpty();
        }

        @Test
        @DisplayName("accepts a credit score below the screen's documented range, down to the lowest seeded")
        void acceptsACreditScoreBelowTheDocumentedRange() throws JsonProcessingException {
            // The read-path half of the credit-score obligation. The relational column has no check
            // constraint, and twenty-one of the fifty seeded customer records score below 300 with the
            // lowest at 001, so a lower bound of 300 and an upper bound of 850 here would make
            // forty-two per cent of the reference data unviewable. That window is a rule of the update
            // path and is asserted against the update request type, never here.
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
            // Ten spaces is what the field holds in all fifty seeded account records. A non-blank or
            // non-empty constraint would reject every one of them.
            AccountViewResponse response = populated();

            assertThat(violationsOf(response)).isEmpty();
            assertThat(response.accountGroupId()).isEqualTo("          ").hasSize(10);
        }

        @Test
        @DisplayName("accepts a pre-formatted telephone number, so no pattern constraint can exist")
        void acceptsAPreFormattedTelephoneNumber() {
            // A pattern constraint of digits only would reject the parentheses and the hyphen that
            // every seeded number carries, and a pattern constraint of the formatted shape would
            // reject a legitimately blank field. Neither exists.
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
            // The update program decorates the middle name and the second address line for error
            // display but states in its own comments that no edit is coded for either. Adding a
            // constraint to either here would reject input the legacy system accepts. Both carry a
            // width bound only, which measures and never alters, and both accept a value with an
            // embedded space - the legacy alphabetic check blanks letters and then trims, so an
            // embedded space passes it.
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
            // Two independent overflows, so the bound is per component rather than a single check over
            // the record, and the twenty-nine in-width components stay silent.
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

            // All forty text and object components read back absent, and the one primitive reads back
            // false. Every accessor on the type is exercised here, which is also what keeps this
            // component inventory honest: a component added to the record without being added below
            // would leave this method failing to compile.
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
            // The assignment to a Record local compiles only for a record, and every component of a
            // record is final by language rule, so no mutator can exist to look for and none has to be
            // hunted for at run time. Repeated reads therefore return the same value, and two
            // independently built instances carrying the same values are equal.
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
        @DisplayName("serialises exactly the thirty-seven map items and the four control components")
        void serialisesExactlyTheDeclaredComponents() throws JsonProcessingException {
            Set<String> keys = keysOf(payloadOf(populated()));

            // One assertion, several obligations. Comparing the payload's own key set against the list
            // transcribed from the map proves the inventory and the order, and it simultaneously proves
            // the absence of everything the wire must not carry: no length, flag, attribute, colour,
            // highlight or validation control byte accompanying a field, no terminal input-output area
            // filler, no map coordinate, no optimistic-lock or version member, and none of the members
            // of the problem-detail media type, which this module deliberately does not use.
            assertThat(keys)
                    .describedAs("the payload's own property set, in payload order")
                    .containsExactlyElementsOf(COMPONENTS_IN_MAP_ORDER)
                    .hasSize(41);
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

            // The wholly absent response carries exactly one property, because the rejection flag is
            // primitive and always has a value.
            assertThat(keysOf(payloadOf(allAbsent()))).containsExactly("inputError");
            assertThat(payload).isEqualTo("{\"inputError\":false}").doesNotContain("null");
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

            // The control. Tolerance comes from the module's mapper configuration, so a mapper without
            // it must refuse the same body. If the type carried an ignore-unknown annotation of its
            // own, this would pass instead of throwing - and that annotation would apply everywhere
            // and could not be reconsidered per profile.
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
            // The assignment is the static-type proof: the route is text, not an enumeration. The
            // estate's transfer-of-control dispatches and its return-with-transaction re-arms all
            // become a route value returned in a response body, so the client drives the next call and
            // the server forwards nothing. Naming and resolving routes belongs to the service layer,
            // which is why no route table, route constant, route enumeration, dispatch method or
            // nested route holder appears on this record or in this file, and why a shared top-level
            // route holder is excluded module-wide.
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
            // If the route drove any behaviour on this record, changing it would change something else.
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
            // The legacy authority is a dedicated one-character working flag, not the presence of text.
            // The two are independent and the flag is the authority, so a response may carry an error
            // line without being a rejection.
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
            // The rendered form is exercised here because it is part of the type's behaviour and this
            // record assembles an unusually complete picture of one person. What matters for this
            // contract is that withholding happens on the diagnostic path only: the accessors and the
            // payload continue to carry every value untouched, which is what the legacy screen
            // established.
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

        @Test
        @DisplayName("retains the route verbatim in the diagnostic, so a route is no place for a key")
        void retainsTheRouteVerbatimInTheDiagnostic() {
            // Recorded because it is a real and easily-missed property of the rendering, and it is the
            // right behaviour rather than a defect: a route is server-defined and describes the shape
            // of a response, not a person, so withholding it would cost a reader the one value that
            // says what happens next. The consequence belongs to the caller, not to this record - a
            // service that writes a business key into a route text puts that key into every rendering
            // of the response, after the identifier components themselves have been withheld from it.
            String keyBearingRoute = "/api/accounts/00000000011/view";

            assertThat(withNextRoute(keyBearingRoute).toString())
                    .describedAs("the route is retained exactly as supplied")
                    .contains("nextRoute=" + keyBearingRoute);
            assertThat(populated().toString())
                    .describedAs("a key-free route leaves no identifier anywhere in the rendering")
                    .contains("nextRoute=/api/accounts/view")
                    .doesNotContain("00000000011")
                    .doesNotContain("000000011");
        }
    }
}
