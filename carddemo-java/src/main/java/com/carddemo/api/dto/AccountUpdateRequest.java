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

import java.math.BigDecimal;

import jakarta.validation.constraints.Size;

/**
 * Immutable account-update request contract for legacy CICS transaction {@code CAUP}.
 *
 * <h2>Provenance</h2>
 * <ul>
 *   <li>Program: {@code app/cbl/COACTUPC.cbl} - 4,236 lines. The migration plan records 85
 *       procedure paragraphs and the execution brief records 88 "measured"; a strict count of
 *       Area-A labels below the procedure division header (line 858) reproduces the plan's 85,
 *       so the difference is a counting-convention artefact and is recorded in
 *       {@code docs/decision-log.md} rather than silently resolved. Either way this is the
 *       largest single translation in the estate.</li>
 *   <li>Symbolic map: {@code app/cpy-bms/COACTUP.CPY} - declares 54 input families.</li>
 *   <li>Mapset: {@code app/bms/COACTUP.bms} - 512 lines; declares exactly 43 of those 54
 *       families unprotected, i.e. editable by the terminal operator.</li>
 *   <li>Error-decoration macro: {@code app/cpy/CSSETATY.cpy} - a three-token
 *       {@code COPY ... REPLACING} macro expanded 39 times in {@code COACTUPC} between
 *       lines 3208 and 3432.</li>
 *   <li>Record layouts consulted for the persisted types: {@code app/cpy/CVACT01Y.cpy}
 *       (account, 300 bytes) and {@code app/cpy/CVCUS01Y.cpy} (customer, 500 bytes).</li>
 *   <li>Licence header taken from {@code app/cbl/COSGN00C.cbl} lines 7-20.</li>
 *   <li>Checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}; upstream release stamp
 *       {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.</li>
 * </ul>
 *
 * <h2>Field arithmetic: 54 to 43 to 39</h2>
 * <p>The map declares <strong>54</strong> input families. Eleven of them are not editable and
 * are therefore absent from this request: the six screen-metadata items (transaction name,
 * both title lines, current date, program name, current time), the two message items
 * (information message and error message), and the three function-key legend items. The
 * metadata and message items belong on {@link AccountUpdateResponse}; the legends are pure
 * 3270 screen furniture and belong nowhere. That leaves the <strong>43</strong> components
 * carried here, confirmed three independent ways: 54 input families minus the 11 protected
 * families, 43 unprotected mapset definitions, and 43 map-to-working-storage moves in
 * {@code COACTUPC} between lines 1056 and 1423.</p>
 * <p><strong>39</strong> of the 43 are error-decoration targets, so 43 minus 39 leaves exactly
 * <strong>four editable-but-undecorated</strong> fields: the account id, the account group id,
 * the customer id, and the government-issued id. They are genuine inputs that simply never
 * receive field-level error decoration.</p>
 *
 * <h2>Component order</h2>
 * <p>Components follow the declaration order of the symbolic map, which places the three
 * date-of-birth parts at ordinals 22 to 24 - immediately after the third social-security
 * part and before the credit score. The two-column summary table in the execution brief
 * renders those three at the end of its second column; that is a table-layout artefact, not
 * a different source order. Both readings contain the same 43 identities and JSON binding is
 * by name, so nothing downstream depends on the choice; map declaration order is used because
 * it is the reproducible authority.</p>
 *
 * <h2>Split fields stay split</h2>
 * <p>The legacy screen decomposes four dates, one social-security number and two telephone
 * numbers into independently entered, independently validated and independently decorated
 * sub-fields. All of them are carried apart, never joined:</p>
 * <ul>
 *   <li><strong>Twelve date components</strong> - four dates, each split into year, month and
 *       day: account open date, account expiry date, account reissue date and customer date of
 *       birth. The reissue date is easy to overlook because it is often described alongside
 *       "three dates"; the map declares it and the program stages it, so it is a fourth split
 *       date and contributes three more components.</li>
 *   <li><strong>Three social-security components</strong> of widths 3, 2 and 4.</li>
 *   <li><strong>Six telephone components</strong> - two numbers, each split into area code,
 *       prefix and line number of widths 3, 3 and 4.</li>
 * </ul>
 * <p>Merging any of these would destroy the field-level error contract, because each sub-field
 * owns its own validation flag and its own decoration site. Nothing here is parsed, converted
 * or assembled: no date-and-time type from the platform date package is imported, no date is
 * interpreted, and no phone number is formatted. The persisted telephone form is
 * {@code (999)999-9999} inside a 15-character field - 13 characters of content followed by two
 * trailing spaces - and that
 * assembly is performed by {@code AccountUpdateService}, never here. Date interpretation
 * belongs to {@code DateValidationService}, whose cascade spans eleven paragraphs of
 * {@code app/cpy/CSUTLDPY.cpy}.</p>
 *
 * <h2>The five monetary components</h2>
 * <p>Credit limit, cash credit limit, current balance, current cycle credit and current cycle
 * debit are 15-character fields on the input side of the map, staged through 15-character
 * alphanumeric work fields in {@code COACTUPC} (lines 412-416) and edited by paragraph
 * {@code 1250-EDIT-SIGNED-9V2} (invoked at line 1486). Their record counterparts in
 * {@code CVACT01Y} are signed zoned decimals with ten integer digits and two decimal places,
 * and the corresponding database columns are numeric with precision 12 and scale 2. They are
 * therefore declared as {@link BigDecimal} - never a binary floating-point type, never a
 * primitive, never a preformatted string.</p>
 * <p>Scale 2 is a contract, not something this request applies. The estate contains no
 * rounding clause on any arithmetic statement, so every legacy store into a two-decimal field
 * truncates toward zero; that truncation is applied in exactly one place,
 * {@code com.carddemo.util.ZonedDecimalCodec}, and half-up or half-even rounding is forbidden
 * module-wide. Consequently this file performs no arithmetic, no rounding, no scaling, no
 * negation and no formatting, and imports neither a rounding mode nor a math context.</p>
 *
 * <h2>Validation policy: why this request tolerates bad input</h2>
 * <p>{@code COACTUPC} runs a <strong>first-error-wins</strong> validation cascade. Every edit
 * paragraph is gated on the summary-message slot still being empty, so a submission with five
 * bad fields yields <em>one</em> summary message - the message of the first failing stage in
 * source order - together with <em>N</em> independently set field flags that drive decoration.
 * Bean Validation evaluates constraints in an unspecified order and would therefore produce a
 * different message set for the same input. The ordered cascade consequently lives in
 * {@code AccountUpdateService}, and this request deliberately <strong>accepts null, blank and
 * out-of-range values without rejecting them</strong> so the service can run that cascade and
 * emit the correct single summary message alongside the correct decorated field set.</p>
 * <p>The only declarative constraint used here is {@code @Size(max = n)} at each field's
 * measured map width, which restates the physical width of the 3270 field rather than any
 * business rule; it neither trims a value nor disturbs leading or trailing spaces. No other
 * constraint annotation appears anywhere in this file - in particular no null, blank, empty,
 * pattern, digits, minimum, maximum, decimal-bound, e-mail, positivity, temporal or assertion
 * constraint, and no custom constraint.</p>
 *
 * <h2>Two components that carry no annotation at all</h2>
 * <p>The middle name and the second address line are decorated for error display but are not
 * validated. Their absence of constraints is deliberate and load-bearing, and is documented on
 * each component below with its source citation. Attaching any constraint - even a width
 * constraint - would reject input the legacy system accepts, which is a behavioural
 * regression.</p>
 *
 * <h2>Credit score: 300 to 850 inclusive, documented rather than annotated</h2>
 * <p>The credit-score field is three characters wide and is carried as a string so that a
 * value such as {@code 001} survives intact. The legacy range test is a condition name over
 * the inclusive range 300 through 850 ({@code COACTUPC} lines 848-849) and it fires only when
 * the score has already passed the required-numeric stage (lines 1553-1554), in paragraph
 * {@code 1275-EDIT-FICO-SCORE} (lines 2514-2530). Because that gating is part of the ordered
 * cascade, the bound is <strong>documented here and enforced by
 * {@code AccountUpdateService}</strong>; annotating it would hoist the check out of the
 * cascade and change which message is produced. The bound exists only on this request:
 * {@link AccountViewResponse} must not apply it, because the persistence layer carries no such
 * constraint and 21 of the 50 seeded customers score below 300, the lowest being {@code 001}.</p>
 *
 * <h2>Character-class semantics</h2>
 * <p>The legacy alphabetic check blanks every letter in the field and then tests whether
 * anything remains ({@code 1225-EDIT-ALPHA-REQD}, lines 1898 and 1924-1933), so
 * <strong>embedded spaces pass</strong>: values such as {@code MARY ANN} and {@code Aniya Von}
 * are valid. No letters-only pattern may therefore be attached to any name component, and the
 * faithful predicate - every character is a letter or a space - lives in
 * {@code com.carddemo.util.CobolStringUtils} and is invoked by the service. The four
 * character-class edit paragraphs are required-alphabetic (line 1898), required-alphanumeric
 * (line 1955), optional-alphabetic (line 2012) and optional-alphanumeric (line 2061). One
 * source comment is stale: line 2078 claims alphabetic-plus-space while lines 2079-2082 use
 * the 62-character alphanumeric table - the code governs, not the comment.</p>
 *
 * <h2>External contract text this request must support</h2>
 * <p>These messages are composed by {@code AccountUpdateService}, never here. They are
 * reproduced because they are externally observable contract text that operators and
 * downstream tooling match on. Those beginning with a colon are suffixes appended to the
 * trimmed field label; note the exact presence and absence of trailing periods and the capital
 * letter in the digit-count messages.</p>
 * <ul>
 *   <li>{@code : should be between 300 and 850} - line 2523, 31 characters, no trailing
 *       period.</li>
 *   <li>{@code : is not a valid state code} - line 2503, no trailing period.</li>
 *   <li>{@code Invalid zip code for state} - lines 2549-2552; a bare literal carrying
 *       <strong>no field-name prefix</strong>.</li>
 *   <li>{@code : Area code must be supplied.} - line 2254.</li>
 *   <li>{@code : Area code must be A 3 digit number.} - line 2272.</li>
 *   <li>{@code : Area code cannot be zero} - line 2286, no trailing period.</li>
 *   <li>{@code : Not valid North America general purpose area code} - line 2306, no trailing
 *       period.</li>
 *   <li>{@code : Prefix code must be supplied.} - line 2325.</li>
 *   <li>{@code : Prefix code must be A 3 digit number.} - line 2343.</li>
 *   <li>{@code : Prefix code cannot be zero} - line 2357, no trailing period.</li>
 *   <li>{@code : Line number code must be supplied.} - line 2378.</li>
 *   <li>{@code : Line number code must be A 4 digit number.} - line 2396.</li>
 *   <li>{@code : Line number code cannot be zero} - line 2410, no trailing period.</li>
 *   <li>{@code Account Active Status must be Y or N} - line 504.</li>
 *   <li>{@code Account number must be a non zero 11 digit number} - lines 494 and 496, where
 *       the same literal is declared twice.</li>
 *   <li>{@code Credit Limit is not valid} - line 508.</li>
 *   <li>{@code Credit Limit must be supplied} - line 506.</li>
 * </ul>
 *
 * <h2>Validation behaviour recorded for the service, not implemented here</h2>
 * <ul>
 *   <li><strong>State and postal code can both be flagged by one check.</strong> The
 *       combination test (lines 2536-2557) builds its lookup key by positional concatenation
 *       of the two-character state with the first two characters of the postal code, with no
 *       trimming, and on failure sets both the state flag and the postal-code flag. A single
 *       submission can therefore decorate two fields from one comparison, which is why the
 *       error contract in {@link ErrorResponse} is a per-field collection rather than a single
 *       error. No lookup, concatenation or key building occurs in this file.</li>
 *   <li><strong>The state check is a flat membership test.</strong> Paragraph
 *       {@code 1270-EDIT-US-STATE-CD} (lines 2493-2510) performs no trim, no numeric check and
 *       no blank pre-check, so no pattern or minimum-length constraint may be attached to the
 *       state component.</li>
 *   <li><strong>The telephone cascade always runs all three stages</strong> and sets all three
 *       flags independently: range head at line 2225, area code at 2246, prefix at 2316, line
 *       number at 2370, inner exit at 2424 and range exit at 2427, invoked at lines 1632-1638
 *       and 1640-1646. It carries a <strong>preserved defect</strong>: the all-blank shortcut
 *       at lines 2238-2239 tests the area-code sub-field where it should test the line-number
 *       sub-field, so a submission with a blank area code, a blank prefix and a populated line
 *       number is silently treated as "no telephone supplied". The defect is reproduced in the
 *       service and recorded in {@code docs/decision-log.md}; it is noted here only for
 *       traceability.</li>
 *   <li><strong>Lookup cardinalities</strong> asserted by
 *       {@code com.carddemo.service.ValidationLookupService}: 490 valid area codes, being 410
 *       general-purpose plus 80 easily-recognisable codes in a disjoint partition, so 490 is
 *       derived and never stored; 56 state codes; and 240 state-plus-postal-prefix
 *       combinations. Six of those 240 use prefixes that are absent from the 56-code list, so
 *       the two lists must never be intersected. No lookup table of any kind appears in this
 *       request.</li>
 * </ul>
 *
 * <h2>Faithfully recorded source oddities</h2>
 * <ul>
 *   <li>The decoration cascade follows map declaration order rather than conventional postal
 *       order, so the state (line 3364) is decorated between the first and second address
 *       lines, and the postal code (line 3376) is decorated before the city and the country.
 *       Recorded as-is.</li>
 *   <li>Three comments in the decoration block are wrong and the substitution tokens govern:
 *       line 3375 reads "State" but introduces the postal-code expansion, and lines 3426 and
 *       3431 are transposed, so the correct mappings are primary card holder to the
 *       primary-holder field and electronic-funds-transfer account id to the transfer-account
 *       field. The decoration order also emits the primary card holder (line 3427) before the
 *       transfer account id (line 3432), inverting the map declaration order.</li>
 *   <li>A hand-written copy of the decoration logic sits commented out at lines 3196-3205 and
 *       stays inactive.</li>
 *   <li>The city entered on this screen is persisted into the customer record's third address
 *       line (move at line 1333), and the five-character postal code entered here is persisted
 *       into a ten-character record field. Both are layout facts, reproduced by the mapper
 *       layer, and neither changes this contract.</li>
 * </ul>
 *
 * <h2>Layering</h2>
 * <p>This request depends only on the platform library and on the validation API. It
 * references no entity, repository, service, utility, configuration, exception or framework
 * type, holds no logging, performs no input or output, and contains no business logic. It is a
 * {@code record}, so it is immutable and constructed in one step without any code generator or
 * annotation processor. It carries an unmasked social-security number in three parts and an
 * unmasked government-issued id because the legacy screen does: they are transported, never
 * logged and never redacted here.</p>
 *
 * @param accountId account id - map field {@code ACCTSID}, width 11. Editable but never
 *        decorated. Moved to the search key at {@code COACTUPC} line 1056. The service reports
 *        {@code Account number must be a non zero 11 digit number} when it is unusable.
 * @param accountStatus account active status - map field {@code ACSTTUS}, width 1, decorated at
 *        {@code COACTUPC} line 3208 from token {@code ACCT-STATUS}. Restricted to yes or no by
 *        the service, which reports {@code Account Active Status must be Y or N}.
 * @param openYear account open date, year part - map field {@code OPNYEAR}, width 4, decorated
 *        at line 3214.
 * @param openMonth account open date, month part - map field {@code OPNMON}, width 2, decorated
 *        at line 3220.
 * @param openDay account open date, day part - map field {@code OPNDAY}, width 2, decorated at
 *        line 3226.
 * @param creditLimit credit limit - map field {@code ACRDLIM}, width 15 on the screen, two
 *        decimal places in the record. Decorated at line 3232. The service reports
 *        {@code Credit Limit must be supplied} or {@code Credit Limit is not valid}.
 * @param expiryYear account expiry date, year part - map field {@code EXPYEAR}, width 4,
 *        decorated at line 3238.
 * @param expiryMonth account expiry date, month part - map field {@code EXPMON}, width 2,
 *        decorated at line 3244.
 * @param expiryDay account expiry date, day part - map field {@code EXPDAY}, width 2, decorated
 *        at line 3250.
 * @param cashCreditLimit cash credit limit - map field {@code ACSHLIM}, width 15 on the screen,
 *        two decimal places in the record. Decorated at line 3256.
 * @param reissueYear account reissue date, year part - map field {@code RISYEAR}, width 4,
 *        decorated at line 3262. Part of the fourth split date.
 * @param reissueMonth account reissue date, month part - map field {@code RISMON}, width 2,
 *        decorated at line 3268.
 * @param reissueDay account reissue date, day part - map field {@code RISDAY}, width 2,
 *        decorated at line 3274.
 * @param currentBalance current balance - map field {@code ACURBAL}, width 15 on the screen,
 *        two decimal places in the record. Decorated at line 3280.
 * @param currentCycleCredit current cycle credit - map field {@code ACRCYCR}, width 15 on the
 *        screen, two decimal places in the record. Decorated at line 3286.
 * @param accountGroupId account group id - map field {@code AADDGRP}, width 10. Editable but
 *        never decorated.
 * @param currentCycleDebit current cycle debit - map field {@code ACRCYDB}, width 15 on the
 *        screen, two decimal places in the record. Decorated at line 3292.
 * @param customerId customer id - map field {@code ACSTNUM}, width 9. Editable but never
 *        decorated.
 * @param ssnPart1 social-security number, first part - map field {@code ACTSSN1}, width 3,
 *        decorated at line 3298. Transported unmasked.
 * @param ssnPart2 social-security number, second part - map field {@code ACTSSN2}, width 2,
 *        decorated at line 3304. Transported unmasked.
 * @param ssnPart3 social-security number, third part - map field {@code ACTSSN3}, width 4,
 *        decorated at line 3310. Transported unmasked.
 * @param dateOfBirthYear customer date of birth, year part - map field {@code DOBYEAR}, width
 *        4, decorated at line 3316.
 * @param dateOfBirthMonth customer date of birth, month part - map field {@code DOBMON}, width
 *        2, decorated at line 3322.
 * @param dateOfBirthDay customer date of birth, day part - map field {@code DOBDAY}, width 2,
 *        decorated at line 3328.
 * @param ficoScore customer credit score - map field {@code ACSTFCO}, width 3, decorated at
 *        line 3334. Carried as a string so that {@code 001} is not reduced to {@code 1}. The
 *        inclusive 300-to-850 bound is documented, not annotated; see the class notes.
 * @param firstName customer first name - map field {@code ACSFNAM}, width 25, decorated at line
 *        3340. Validated by the required-alphabetic stage, which permits embedded spaces.
 * @param middleName customer middle name - map field {@code ACSMNAM}, width 25, decorated at
 *        line 3346. <strong>Carries no annotation of any kind, deliberately.</strong> The
 *        source comment at line 3345 states that no edits are coded for it. Measured evidence
 *        refines that comment without changing the conclusion: lines 1568-1574 do run the
 *        <em>optional</em> alphabetic stage over this field and line 3110 consults the
 *        resulting flag for cursor placement, so the comment is stale - but an optional
 *        alphabetic stage accepts blank values and accepts embedded spaces, and no declarative
 *        constraint can express that while preserving cascade order. Any constraint added here,
 *        including a width constraint, would reject input the legacy system accepts.
 * @param lastName customer last name - map field {@code ACSLNAM}, width 25, decorated at line
 *        3352. Validated by the required-alphabetic stage, which permits embedded spaces.
 * @param addressLine1 customer first address line - map field {@code ACSADL1}, width 50,
 *        decorated at line 3358. Validated only for presence.
 * @param stateCode customer state code - map field {@code ACSSTTE}, width 2, decorated at line
 *        3364. Checked by flat membership against 56 codes and, jointly with the postal code,
 *        against 240 combinations; both checks live in the service.
 * @param addressLine2 customer second address line - map field {@code ACSADL2}, width 50,
 *        decorated at line 3370. <strong>Carries no annotation of any kind,
 *        deliberately.</strong> The source comment at line 3369 states that no edits are coded
 *        as yet, and measurement confirms it completely: the field's validation flag is
 *        declared at line 295 and consumed by the decoration at line 3370, yet it is never
 *        assigned anywhere in the 4,236 lines, and the statement that would set this field's
 *        error label is commented out at line 1614 under the note that the field is optional.
 *        The flag therefore can never leave its valid state and the decoration can never fire.
 *        This field accepts any value; adding a constraint, including a width constraint, would
 *        be a behavioural regression.
 * @param zipCode customer postal code - map field {@code ACSZIPC}, width 5, decorated at line
 *        3376 despite the mislabelled comment at line 3375. Persisted into a ten-character
 *        record field.
 * @param city customer city - map field {@code ACSCITY}, width 50, decorated at line 3382.
 *        Persisted into the customer record's third address line.
 * @param countryCode customer country code - map field {@code ACSCTRY}, width 3, decorated at
 *        line 3388.
 * @param phone1AreaCode first telephone number, area code - map field {@code ACSPH1A}, width 3,
 *        decorated at line 3394.
 * @param phone1Prefix first telephone number, prefix - map field {@code ACSPH1B}, width 3,
 *        decorated at line 3400.
 * @param phone1LineNumber first telephone number, line number - map field {@code ACSPH1C},
 *        width 4, decorated at line 3405.
 * @param governmentIssuedId customer government-issued id - map field {@code ACSGOVT}, width
 *        20. Editable but never decorated. Transported unmasked.
 * @param phone2AreaCode second telephone number, area code - map field {@code ACSPH2A}, width
 *        3, decorated at line 3411.
 * @param phone2Prefix second telephone number, prefix - map field {@code ACSPH2B}, width 3,
 *        decorated at line 3417.
 * @param phone2LineNumber second telephone number, line number - map field {@code ACSPH2C},
 *        width 4, decorated at line 3422.
 * @param eftAccountId customer electronic-funds-transfer account id - map field
 *        {@code ACSEFTC}, width 10, decorated at line 3432 from token
 *        {@code EFT-ACCOUNT-ID}; the adjacent comment at line 3431 is transposed and the token
 *        governs.
 * @param primaryCardHolderIndicator customer primary-card-holder indicator - map field
 *        {@code ACSPFLG}, width 1, decorated at line 3427 from token {@code PRI-CARDHOLDER};
 *        the adjacent comment at line 3426 is transposed and the token governs. Restricted to
 *        yes or no by the service.
 */
public record AccountUpdateRequest(

        /* ---------------------------------------------------------------------------------
         * Ordinals 1-5: account key, status and the split open date.
         * Map declaration order (app/cpy-bms/COACTUP.CPY) is preserved throughout.
         * --------------------------------------------------------------------------------- */

        /* 1. ACCTSID, width 11 - editable, NOT decorated. */
        @Size(max = 11) String accountId,

        /* 2. ACSTTUS, width 1 - decorated at COACTUPC:3208 (token ACCT-STATUS). */
        @Size(max = 1) String accountStatus,

        /* 3. OPNYEAR, width 4 - decorated at COACTUPC:3214 (token OPEN-YEAR). */
        @Size(max = 4) String openYear,

        /* 4. OPNMON, width 2 - decorated at COACTUPC:3220 (token OPEN-MONTH). */
        @Size(max = 2) String openMonth,

        /* 5. OPNDAY, width 2 - decorated at COACTUPC:3226 (token OPEN-DAY). */
        @Size(max = 2) String openDay,

        /* ---------------------------------------------------------------------------------
         * Ordinal 6: first monetary component. Fifteen characters on the screen, two decimal
         * places in the record. No bound, no digit constraint and no scaling here: scale and
         * truncation are applied only by com.carddemo.util.ZonedDecimalCodec.
         * --------------------------------------------------------------------------------- */

        /* 6. ACRDLIM, width 15 - decorated at COACTUPC:3232 (token CRED-LIMIT). */
        BigDecimal creditLimit,

        /* ---------------------------------------------------------------------------------
         * Ordinals 7-9: the split expiry date.
         * --------------------------------------------------------------------------------- */

        /* 7. EXPYEAR, width 4 - decorated at COACTUPC:3238 (token EXPIRY-YEAR). */
        @Size(max = 4) String expiryYear,

        /* 8. EXPMON, width 2 - decorated at COACTUPC:3244 (token EXPIRY-MONTH). */
        @Size(max = 2) String expiryMonth,

        /* 9. EXPDAY, width 2 - decorated at COACTUPC:3250 (token EXPIRY-DAY). */
        @Size(max = 2) String expiryDay,

        /* 10. ACSHLIM, width 15 - decorated at COACTUPC:3256 (token CASH-CREDIT-LIMIT). */
        BigDecimal cashCreditLimit,

        /* ---------------------------------------------------------------------------------
         * Ordinals 11-13: the split reissue date - the fourth split date on this screen.
         * --------------------------------------------------------------------------------- */

        /* 11. RISYEAR, width 4 - decorated at COACTUPC:3262 (token REISSUE-YEAR). */
        @Size(max = 4) String reissueYear,

        /* 12. RISMON, width 2 - decorated at COACTUPC:3268 (token REISSUE-MONTH). */
        @Size(max = 2) String reissueMonth,

        /* 13. RISDAY, width 2 - decorated at COACTUPC:3274 (token REISSUE-DAY). */
        @Size(max = 2) String reissueDay,

        /* ---------------------------------------------------------------------------------
         * Ordinals 14-17: the remaining monetary components, interleaved with the account
         * group id exactly as the map declares them.
         * --------------------------------------------------------------------------------- */

        /* 14. ACURBAL, width 15 - decorated at COACTUPC:3280 (token CURR-BAL). */
        BigDecimal currentBalance,

        /* 15. ACRCYCR, width 15 - decorated at COACTUPC:3286 (token CURR-CYC-CREDIT). */
        BigDecimal currentCycleCredit,

        /* 16. AADDGRP, width 10 - editable, NOT decorated. */
        @Size(max = 10) String accountGroupId,

        /* 17. ACRCYDB, width 15 - decorated at COACTUPC:3292 (token CURR-CYC-DEBIT). */
        BigDecimal currentCycleDebit,

        /* ---------------------------------------------------------------------------------
         * Ordinals 18-24: customer key, the split social-security number and the split date
         * of birth. The map declares the date-of-birth parts here, before the credit score.
         * --------------------------------------------------------------------------------- */

        /* 18. ACSTNUM, width 9 - editable, NOT decorated. */
        @Size(max = 9) String customerId,

        /* 19. ACTSSN1, width 3 - decorated at COACTUPC:3298 (token EDIT-US-SSN-PART1). */
        @Size(max = 3) String ssnPart1,

        /* 20. ACTSSN2, width 2 - decorated at COACTUPC:3304 (token EDIT-US-SSN-PART2). */
        @Size(max = 2) String ssnPart2,

        /* 21. ACTSSN3, width 4 - decorated at COACTUPC:3310 (token EDIT-US-SSN-PART3). */
        @Size(max = 4) String ssnPart3,

        /* 22. DOBYEAR, width 4 - decorated at COACTUPC:3316 (token DT-OF-BIRTH-YEAR). */
        @Size(max = 4) String dateOfBirthYear,

        /* 23. DOBMON, width 2 - decorated at COACTUPC:3322 (token DT-OF-BIRTH-MONTH). */
        @Size(max = 2) String dateOfBirthMonth,

        /* 24. DOBDAY, width 2 - decorated at COACTUPC:3328 (token DT-OF-BIRTH-DAY). */
        @Size(max = 2) String dateOfBirthDay,

        /* ---------------------------------------------------------------------------------
         * Ordinal 25: credit score. Three characters, carried as a string so that 001 keeps
         * its leading zeros. The inclusive 300-850 bound (COACTUPC:848-849, enforced by
         * paragraph 1275-EDIT-FICO-SCORE at COACTUPC:2514-2530 and gated at
         * COACTUPC:1553-1554) is DOCUMENTED, NOT ANNOTATED, so that AccountUpdateService
         * keeps ownership of the ordered cascade.
         * --------------------------------------------------------------------------------- */

        /* 25. ACSTFCO, width 3 - decorated at COACTUPC:3334 (token FICO-SCORE). */
        @Size(max = 3) String ficoScore,

        /* ---------------------------------------------------------------------------------
         * Ordinals 26-34: customer names and address. The alphabetic stages that check the
         * name and city components blank every letter and then trim, so embedded spaces pass
         * and no letters-only pattern may be attached to any of them.
         * --------------------------------------------------------------------------------- */

        /* 26. ACSFNAM, width 25 - decorated at COACTUPC:3340 (token FIRST-NAME). */
        @Size(max = 25) String firstName,

        /* 27. ACSMNAM, width 25 - decorated at COACTUPC:3346 (token MIDDLE-NAME).
         *
         * INTENTIONALLY UNANNOTATED - DO NOT ADD ANY CONSTRAINT, NOT EVEN @Size.
         * COACTUPC:3345 records that no edits are coded for this field. Measurement refines
         * the comment without changing the outcome: COACTUPC:1568-1574 runs the OPTIONAL
         * alphabetic stage over it and COACTUPC:3110 reads the resulting flag for cursor
         * placement, so the comment is stale - but an optional alphabetic stage accepts blank
         * values and accepts embedded spaces, which no declarative constraint can express
         * without also hoisting the check out of the source-ordered cascade. Any constraint
         * added here would reject input the legacy system accepts. See the class Javadoc and
         * docs/decision-log.md. */
        String middleName,

        /* 28. ACSLNAM, width 25 - decorated at COACTUPC:3352 (token LAST-NAME). */
        @Size(max = 25) String lastName,

        /* 29. ACSADL1, width 50 - decorated at COACTUPC:3358 (token ADDRESS-LINE-1). */
        @Size(max = 50) String addressLine1,

        /* 30. ACSSTTE, width 2 - decorated at COACTUPC:3364 (token STATE). The decoration
         * cascade places the state between the two address lines, following map declaration
         * order rather than conventional postal order; recorded as-is. No pattern and no
         * minimum length: the membership test at COACTUPC:2493-2510 performs no trim, no
         * numeric check and no blank pre-check. */
        @Size(max = 2) String stateCode,

        /* 31. ACSADL2, width 50 - decorated at COACTUPC:3370 (token ADDRESS-LINE-2).
         *
         * INTENTIONALLY UNANNOTATED - DO NOT ADD ANY CONSTRAINT, NOT EVEN @Size.
         * COACTUPC:3369 records that no edits are coded as yet, and measurement confirms it
         * outright: the field's validation flag is declared at COACTUPC:295 and consumed by
         * the decoration at COACTUPC:3370, yet it is never assigned anywhere in the 4,236
         * lines, and the statement that would set this field's error label is commented out at
         * COACTUPC:1614 under the note that the field is optional. The flag can therefore
         * never leave its valid state and the decoration can never fire. This field accepts
         * ANY value. See the class Javadoc and docs/decision-log.md. */
        String addressLine2,

        /* 32. ACSZIPC, width 5 - decorated at COACTUPC:3376 (token ZIPCODE). The adjacent
         * comment at COACTUPC:3375 reads "State" and is mislabelled; the substitution token
         * governs. Decorated before the city and the country, following map declaration
         * order. A failing state-and-postal-code combination check (COACTUPC:2536-2557) flags
         * this field and the state field together. */
        @Size(max = 5) String zipCode,

        /* 33. ACSCITY, width 50 - decorated at COACTUPC:3382 (token CITY). Persisted into the
         * customer record's third address line (move at COACTUPC:1333). */
        @Size(max = 50) String city,

        /* 34. ACSCTRY, width 3 - decorated at COACTUPC:3388 (token COUNTRY). */
        @Size(max = 3) String countryCode,

        /* ---------------------------------------------------------------------------------
         * Ordinals 35-43: the two split telephone numbers, the government-issued id, the
         * transfer-account id and the primary-holder indicator. The government-issued id is
         * declared between the two telephone numbers by the map; that order is preserved.
         * The telephone cascade (COACTUPC:2225-2427, invoked at 1632-1638 and 1640-1646)
         * always runs all three stages and sets all three flags independently.
         * --------------------------------------------------------------------------------- */

        /* 35. ACSPH1A, width 3 - decorated at COACTUPC:3394 (token PHONE-NUM-1A). */
        @Size(max = 3) String phone1AreaCode,

        /* 36. ACSPH1B, width 3 - decorated at COACTUPC:3400 (token PHONE-NUM-1B). */
        @Size(max = 3) String phone1Prefix,

        /* 37. ACSPH1C, width 4 - decorated at COACTUPC:3405 (token PHONE-NUM-1C). */
        @Size(max = 4) String phone1LineNumber,

        /* 38. ACSGOVT, width 20 - editable, NOT decorated. Transported unmasked. */
        @Size(max = 20) String governmentIssuedId,

        /* 39. ACSPH2A, width 3 - decorated at COACTUPC:3411 (token PHONE-NUM-2A). */
        @Size(max = 3) String phone2AreaCode,

        /* 40. ACSPH2B, width 3 - decorated at COACTUPC:3417 (token PHONE-NUM-2B). */
        @Size(max = 3) String phone2Prefix,

        /* 41. ACSPH2C, width 4 - decorated at COACTUPC:3422 (token PHONE-NUM-2C). */
        @Size(max = 4) String phone2LineNumber,

        /* 42. ACSEFTC, width 10 - decorated at COACTUPC:3432 (token EFT-ACCOUNT-ID). The
         * adjacent comment at COACTUPC:3431 is transposed with the one at COACTUPC:3426; the
         * substitution tokens govern. */
        @Size(max = 10) String eftAccountId,

        /* 43. ACSPFLG, width 1 - decorated at COACTUPC:3427 (token PRI-CARDHOLDER), which the
         * cascade emits BEFORE the transfer-account id, inverting map declaration order. The
         * adjacent comment at COACTUPC:3426 is transposed; the substitution token governs. */
        @Size(max = 1) String primaryCardHolderIndicator) {
}

