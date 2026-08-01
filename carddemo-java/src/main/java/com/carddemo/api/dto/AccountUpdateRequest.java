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
 * Immutable account-update request contract for legacy CICS transaction {@code CAUP}, derived
 * from symbolic map {@code app/cpy-bms/COACTUP.CPY}, mapset {@code app/bms/COACTUP.bms} and
 * program {@code app/cbl/COACTUPC.cbl} - at 4,236 lines the largest single translation in the
 * estate. Record layouts consulted for the persisted types are {@code app/cpy/CVACT01Y.cpy}
 * (account, 300 bytes) and {@code app/cpy/CVCUS01Y.cpy} (customer, 500 bytes).
 *
 * <p>The map declares 54 input families. Eleven are protected and therefore absent here: six
 * screen-metadata items (transaction name, both title lines, current date, program name,
 * current time), two message items, and three function-key legends. The metadata and message
 * items belong on the corresponding response contract; the legends are 3270 screen furniture
 * and belong nowhere. The remaining <strong>43</strong> components are carried here, confirmed
 * three independent ways: 54 input families minus 11 protected families, 43 unprotected mapset
 * definitions, and 43 map-to-working-storage moves in {@code COACTUPC} lines 1056-1423. The
 * error-decoration macro {@code app/cpy/CSSETATY.cpy} is expanded 39 times between lines 3208
 * and 3432, so 39 of the 43 are decoration targets and exactly four components are
 * editable-but-undecorated: the account id, the account group id, the customer id and the
 * government-issued id.</p>
 *
 * <p>Component order follows the declaration order of the symbolic map, which places the three
 * date-of-birth parts at ordinals 22 to 24 - immediately after the third social-security part
 * and before the credit score. JSON binding is by name, so nothing downstream depends on the
 * ordering; map declaration order is used because it is the reproducible authority.</p>
 *
 * <p><strong>Split fields stay split.</strong> Four dates - account open, account expiry,
 * account reissue and customer date of birth - contribute twelve year, month and day
 * components; the social-security number contributes three components of widths 3, 2 and 4; and
 * two telephone numbers contribute six components of widths 3, 3 and 4. The reissue date is
 * easy to overlook because the screen is often described as carrying "three dates", but the map
 * declares it and the program stages it. Merging any of these would destroy the field-level
 * error contract, because each sub-field owns its own validation flag and its own decoration
 * site. Nothing here is parsed, converted or assembled: no date is interpreted and no telephone
 * number is formatted. The persisted telephone form is {@code (999)999-9999} inside a
 * 15-character field - 13 characters of content followed by two trailing spaces - and that
 * assembly belongs to the service layer, as does date interpretation.</p>
 *
 * <p><strong>The five monetary components</strong> - credit limit, cash credit limit, current
 * balance, current cycle credit and current cycle debit - are 15-character screen fields staged
 * through 15-character alphanumeric work fields ({@code COACTUPC} lines 412-416) and edited by
 * paragraph {@code 1250-EDIT-SIGNED-9V2} (invoked at line 1486). Their record counterparts are
 * signed zoned decimals with ten integer digits and two decimal places, and the database
 * columns are numeric with precision 12 and scale 2, so they are declared {@link BigDecimal} -
 * never a binary floating-point type, a primitive or a preformatted string. Scale 2 is a
 * contract this request records rather than applies: the estate carries no rounding clause on
 * any arithmetic statement, so every legacy store into a two-decimal field truncates toward
 * zero, and that truncation is applied in exactly one place,
 * {@code com.carddemo.util.ZonedDecimalCodec} (decision log entry D-02). This file performs no
 * arithmetic, rounding, scaling, negation or formatting.</p>
 *
 * <p><strong>Why this request tolerates bad input.</strong> {@code COACTUPC} runs a
 * first-error-wins validation cascade: every edit paragraph is gated on the summary-message
 * slot still being empty, so a submission with five bad fields yields <em>one</em> summary
 * message - that of the first failing stage in source order - together with <em>N</em>
 * independently set field flags that drive decoration. Bean Validation evaluates constraints in
 * an unspecified order and would produce a different message set for the same input. The
 * ordered cascade therefore belongs to the service layer, and this request deliberately
 * <strong>accepts null, blank and out-of-range values without rejecting them</strong>. The only
 * declarative constraint used here is {@code @Size(max = n)} at each component's measured map
 * width, which restates the physical width of the 3270 field rather than any business rule; it
 * neither trims a value nor disturbs leading or trailing spaces. No other constraint annotation
 * appears anywhere in this file.</p>
 *
 * <p>Two components - the middle name and the second address line - are decorated for error
 * display but never validated, and therefore carry <strong>no annotation at all</strong>.
 * Attaching any constraint, even a width constraint, would reject input the legacy system
 * accepts. Decision log entry D-34 records the decision; the measured evidence is on each
 * component below.</p>
 *
 * <p>The credit score is three characters wide and carried as a string so that a value such as
 * {@code 001} survives intact. Its legacy range test is a condition name over the inclusive
 * range 300 through 850 ({@code COACTUPC} lines 848-849) that fires only after the
 * required-numeric stage has passed (lines 1553-1554), in paragraph
 * {@code 1275-EDIT-FICO-SCORE} (lines 2514-2530). Because that gating is part of the ordered
 * cascade the bound is <strong>documented here and enforced by the service</strong>; annotating
 * it would hoist the check out of the cascade and change which message is produced. The bound
 * is a request-side rule only - the persistence layer carries no such constraint, and 21 of the
 * 50 seeded customers score below 300, the lowest being {@code 001}.</p>
 *
 * <p><strong>Character-class semantics.</strong> The legacy alphabetic check blanks every letter
 * in the field and then tests whether anything remains ({@code 1225-EDIT-ALPHA-REQD}, lines
 * 1898 and 1924-1933), so <strong>embedded spaces pass</strong> and values such as
 * {@code MARY ANN} are valid. No letters-only pattern may be attached to any name component;
 * the faithful predicate - every character is a letter or a space - lives in
 * {@code com.carddemo.util.CobolStringUtils} (decision log entry D-17). The four
 * character-class edit paragraphs are required-alphabetic (line 1898), required-alphanumeric
 * (line 1955), optional-alphabetic (line 2012) and optional-alphanumeric (line 2061); the
 * comment at line 2078 claims alphabetic-plus-space while lines 2079-2082 use the 62-character
 * alphanumeric table, and the code governs. The state check is a flat membership test
 * ({@code 1270-EDIT-US-STATE-CD}, lines 2493-2510) performing no trim, no numeric check and no
 * blank pre-check, so no pattern or minimum-length constraint may be attached to the state
 * component either. A failing state-and-postal-code combination check (lines 2536-2557) sets
 * both the state flag and the postal-code flag, which is why the field error contract is a
 * per-field collection rather than a single error (decision log entry D-33).</p>
 *
 * <p>This request is a {@code record}: immutable, constructed in one step, with no code
 * generator or annotation processor involved. It depends only on the platform library and the
 * validation API, and holds no logging, no input or output and no business logic. It carries an
 * unmasked social-security number in three parts and an unmasked government-issued id because
 * the legacy screen does; both are transported here, never logged and never redacted. Decision
 * log entry D-13 records that both values are sealed at rest by the customer entity, and that an
 * inbound screen contract such as this one therefore carries them unsealed on the wire and never
 * persists either from here.</p>
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

        /* 6. ACRDLIM, width 15 - decorated at COACTUPC:3232 (token CRED-LIMIT). */
        BigDecimal creditLimit,

        /* 7. EXPYEAR, width 4 - decorated at COACTUPC:3238 (token EXPIRY-YEAR). */
        @Size(max = 4) String expiryYear,

        /* 8. EXPMON, width 2 - decorated at COACTUPC:3244 (token EXPIRY-MONTH). */
        @Size(max = 2) String expiryMonth,

        /* 9. EXPDAY, width 2 - decorated at COACTUPC:3250 (token EXPIRY-DAY). */
        @Size(max = 2) String expiryDay,

        /* 10. ACSHLIM, width 15 - decorated at COACTUPC:3256 (token CASH-CREDIT-LIMIT). */
        BigDecimal cashCreditLimit,

        /* 11. RISYEAR, width 4 - decorated at COACTUPC:3262 (token REISSUE-YEAR). */
        @Size(max = 4) String reissueYear,

        /* 12. RISMON, width 2 - decorated at COACTUPC:3268 (token REISSUE-MONTH). */
        @Size(max = 2) String reissueMonth,

        /* 13. RISDAY, width 2 - decorated at COACTUPC:3274 (token REISSUE-DAY). */
        @Size(max = 2) String reissueDay,

        /* 14. ACURBAL, width 15 - decorated at COACTUPC:3280 (token CURR-BAL). */
        BigDecimal currentBalance,

        /* 15. ACRCYCR, width 15 - decorated at COACTUPC:3286 (token CURR-CYC-CREDIT). */
        BigDecimal currentCycleCredit,

        /* 16. AADDGRP, width 10 - editable, NOT decorated. */
        @Size(max = 10) String accountGroupId,

        /* 17. ACRCYDB, width 15 - decorated at COACTUPC:3292 (token CURR-CYC-DEBIT). */
        BigDecimal currentCycleDebit,

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

        /* 25. ACSTFCO, width 3 - decorated at COACTUPC:3334 (token FICO-SCORE). */
        @Size(max = 3) String ficoScore,

        /* 26. ACSFNAM, width 25 - decorated at COACTUPC:3340 (token FIRST-NAME). */
        @Size(max = 25) String firstName,

        /* 27. ACSMNAM, width 25 - decorated at COACTUPC:3346 (token MIDDLE-NAME).
         * INTENTIONALLY UNANNOTATED - DO NOT ADD ANY CONSTRAINT, NOT EVEN @Size. Decision log
         * entry D-34 and the @param tag above carry the measured evidence. */
        String middleName,

        /* 28. ACSLNAM, width 25 - decorated at COACTUPC:3352 (token LAST-NAME). */
        @Size(max = 25) String lastName,

        /* 29. ACSADL1, width 50 - decorated at COACTUPC:3358 (token ADDRESS-LINE-1). */
        @Size(max = 50) String addressLine1,

        /* 30. ACSSTTE, width 2 - decorated at COACTUPC:3364 (token STATE), which the cascade
         * emits between the two address lines, following map declaration order rather than
         * conventional postal order; recorded as-is. */
        @Size(max = 2) String stateCode,

        /* 31. ACSADL2, width 50 - decorated at COACTUPC:3370 (token ADDRESS-LINE-2).
         * INTENTIONALLY UNANNOTATED - DO NOT ADD ANY CONSTRAINT, NOT EVEN @Size. This field
         * accepts ANY value; decision log entry D-34 and the @param tag above carry the
         * measured evidence. */
        String addressLine2,

        /* 32. ACSZIPC, width 5 - decorated at COACTUPC:3376 (token ZIPCODE), before the city
         * and the country, following map declaration order. The adjacent source comment at
         * COACTUPC:3375 reads "State" and is mislabelled; the substitution token governs. */
        @Size(max = 5) String zipCode,

        /* 33. ACSCITY, width 50 - decorated at COACTUPC:3382 (token CITY). */
        @Size(max = 50) String city,

        /* 34. ACSCTRY, width 3 - decorated at COACTUPC:3388 (token COUNTRY). */
        @Size(max = 3) String countryCode,

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

        /* 42. ACSEFTC, width 10 - decorated at COACTUPC:3432 (token EFT-ACCOUNT-ID). */
        @Size(max = 10) String eftAccountId,

        /* 43. ACSPFLG, width 1 - decorated at COACTUPC:3427 (token PRI-CARDHOLDER), which the
         * cascade emits BEFORE the transfer-account id, inverting map declaration order. */
        @Size(max = 1) String primaryCardHolderIndicator) {

    /**
     * Fixed stand-in emitted by {@link #toString()} in place of the whole component set.
     *
     * <p>A constant rather than any transformation of the values, so nothing about them - not a
     * length, not a prefix, not a digest, not a partial mask - can be recovered from a stringified
     * instance.
     */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    /**
     * Returns a diagnostic representation that names the type and discloses none of its values.
     *
     * <p><strong>Why the implicit record rendering could not stand.</strong> A record's generated
     * {@code toString()} prints every component, and every one of the forty-three components of this
     * request is either regulated personal data, a regulated financial value, or a key that joins
     * directly to both. The three social-security parts, the three date-of-birth parts, the
     * government-issued identifier, the transfer-account identifier, the three name parts, the
     * address block, the two telephone numbers, the credit score and the five monetary values are all
     * present on one object. Any structured logger, framework diagnostic, failed assertion, exception
     * message or string interpolation touching an instance would have emitted the lot.
     *
     * <p><strong>Why nothing at all is retained, not even the identifiers.</strong> The account and
     * customer identifiers look like harmless correlation handles, and in isolation they nearly are.
     * On this type they are not in isolation: they are the join keys to the very record whose
     * regulated fields travel beside them, so emitting them alongside a partially redacted payload
     * would still let a reader reassemble the subject from two log lines. The correlation need is
     * genuine, and it is met properly elsewhere - by the request-scoped trace identifier the
     * observability configuration attaches to every log event - rather than by leaking a business key
     * from a request body.
     *
     * <p>{@code equals} and {@code hashCode} are deliberately left as the record contract generates
     * them. They compare every component by value, which is what a request contract requires, and
     * neither emits anything: an in-memory comparison is not a disclosure surface. Redaction belongs
     * on the rendering path alone.
     *
     * @return the type name followed by a fixed placeholder, carrying no component value
     */
    @Override
    public String toString() {
        return "AccountUpdateRequest[" + REDACTION_PLACEHOLDER + "]";
    }
}
