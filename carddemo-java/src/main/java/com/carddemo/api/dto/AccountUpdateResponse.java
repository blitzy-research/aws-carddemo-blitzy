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
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Immutable account-update response contract for legacy CICS transaction {@code CAUP}, derived
 * from symbolic map {@code app/cpy-bms/COACTUP.CPY}, mapset {@code app/bms/COACTUP.bms} and
 * program {@code app/cbl/COACTUPC.cbl} - at 4,236 lines the largest single translation in the
 * estate. The persisted layouts behind the values are {@code app/cpy/CVACT01Y.cpy} (account,
 * 300 bytes) and {@code app/cpy/CVCUS01Y.cpy} (customer, 500 bytes); the echoed conversation
 * state derives from {@code app/cpy/COCOM01Y.cpy}; the per-field error semantics derive from the
 * decoration macro {@code app/cpy/CSSETATY.cpy}, whose executable body is lines 18 to 27.
 *
 * <p>Provenance: repository checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream
 * release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No statement, picture
 * clause or other source text is transcribed anywhere in this file; every claim below is a line
 * citation into the read-only legacy tree.
 *
 * <h2>What the map declares, and what this response carries</h2>
 *
 * <p>The symbolic map declares <strong>54</strong> field families, and its input and output
 * value widths are <strong>identical family for family</strong> - all 54 names appear on both
 * sides at the same width, with no exception. That full parity is why a single width figure per
 * component below serves both directions, and it is why the values a client submits come back
 * shaped exactly as they went out.
 *
 * <p>The mapset corroborates the split: of its 54 named field definitions, <strong>43</strong>
 * are unprotected - the operator-editable set that {@link AccountUpdateRequest} carries - and 11
 * are protected. This response carries <strong>51</strong> of the 54 families: the 43 resulting
 * values, plus eight of the protected families that a request has no business sending, namely the
 * six screen-metadata items and the two message items. Component order follows map declaration
 * order throughout, which places the six metadata items first, then the 43 values in the same
 * sequence {@link AccountUpdateRequest} uses, then the two message items. JSON binding is by
 * name, so nothing downstream depends on the ordering; declaration order is used because it is
 * the reproducible authority rather than a matter of taste.
 *
 * <p>The three families deliberately left out are the function-key legends, at widths 21, 7 and
 * 10. They are static terminal furniture that told an operator which keys the screen honoured;
 * a REST client learns the same thing from the published interface description, so carrying them
 * would put screen decoration on a machine contract. The generated per-field length, flag,
 * attribute, colour, highlight, protection and validation sub-items are likewise absent, together
 * with the map's leading twelve-byte terminal input-output area filler: those are 3270 plumbing
 * with no remote meaning, and modelling any of them would leak a presentation mechanism into a
 * data contract.
 *
 * <h2>Split fields stay split</h2>
 *
 * <p>Four dates - account open, account expiry, account reissue and customer date of birth -
 * contribute twelve year, month and day components; the social-security number contributes three
 * components of widths 3, 2 and 4; and the two telephone numbers contribute six components of
 * widths 3, 3 and 4. Every one of those twenty-one sub-fields owns its own decoration site among
 * the 39 listed below, so merging any of them would destroy the field-level error contract: a
 * client told that "the date of birth is wrong" cannot highlight the month box. Nothing here is
 * parsed, converted, assembled or reformatted. The persisted telephone form is
 * {@code (999)999-9999} inside a 15-character field, as the program's own note at
 * {@code COACTUPC} line 2227 records, and that assembly belongs to the service layer, as does
 * date interpretation.
 *
 * <h2>The five monetary components</h2>
 *
 * <p>Credit limit, cash credit limit, current balance, current cycle credit and current cycle
 * debit are 15-character screen fields whose record counterparts are the five signed zoned
 * decimals with ten integer digits and two decimal places declared in {@code CVACT01Y.cpy}
 * (lines 7, 8, 9, 13 and 14). They are declared {@link BigDecimal} - never a binary
 * floating-point type, never a primitive, never a preformatted string. The estate carries no
 * rounding clause on any arithmetic statement, so every legacy store into a two-decimal field
 * truncates toward zero, and that truncation is applied in exactly one place,
 * {@code com.carddemo.util.ZonedDecimalCodec} (decision log entry D-02). This file performs no
 * arithmetic, no scaling, no rounding, no negation and no formatting, and it declares no edited
 * presentation mask.
 *
 * <p><strong>The shape those five values must have is published, and it is checked.</strong>
 * Ten integer digits and exactly two decimal places is the whole of the numeric contract, and a
 * client has no way to discover it from the type alone: {@link BigDecimal} is unbounded, so a
 * value of any scale and any magnitude satisfies the declaration while breaking the contract.
 * Each of the five therefore carries schema documentation naming its source picture clause, its
 * total precision of 12 and its scale of 2, which is what reaches the published interface
 * description a client actually reads; and the canonical constructor refuses a value whose scale
 * is not 2 or whose integer part exceeds ten digits.
 *
 * <p>It <em>refuses</em> rather than adjusts, and that is the point rather than an oversight.
 * Adjusting would mean re-scaling, and re-scaling is a rounding decision - the one decision this
 * module deliberately concentrates in a single place, because the legacy truncates toward zero
 * where idiomatic Java would round half-even and the difference is a cent on roughly half of all
 * interest results. A response DTO silently changing a monetary value would be the worst possible
 * location for that decision, so a value arriving in the wrong shape is a fault in the caller and
 * is reported as one. The check reads the value's own scale and precision and performs no
 * arithmetic on it.
 *
 * <p>A declarative digit-count annotation was considered for this and rejected on two grounds.
 * It would not be enforced, because nothing validates an outbound response - constraints are
 * evaluated on the way in, not on the way out - and it would not be published either, since the
 * schema generator maps the length, bound and pattern annotations but not that one. It would have
 * been decoration that neither documents nor enforces.
 *
 * <p>Every identifier stays a bounded string: the account id at 11, the customer id at 9 and the
 * credit score at 3. None becomes an integral type, because a credit score of {@code 001} must
 * survive the round trip as three characters rather than arriving as {@code 1}.
 *
 * <h2>One summary message, and independently flagged fields</h2>
 *
 * <p>This is the single most consequential thing to get right about the shape of this type, and
 * it is easy to miss. {@code COACTUPC} runs a first-error-wins gate on the summary message: every
 * edit paragraph writes its text only while the summary slot is still empty, so a submission with
 * five bad fields yields <em>one</em> summary text - that of the first failing stage in source
 * order - while the field flags are set <em>independently</em> and accumulate. Two places prove
 * it beyond doubt. The combined state-and-postal-code check (lines 2536 to 2557) sets the state
 * flag at line 2546 and the postal-code flag at line 2547 on a single failure, and writes one
 * text. The telephone range {@code 1260-EDIT-US-PHONE-NUM} (head at line 2225, exit at 2427)
 * always runs all three of its stages - area code at 2246, prefix at 2316, line number at 2370,
 * inner exit at 2424 - so all three parts can be flagged from one submission.
 *
 * <p>The response shape therefore is <strong>one summary message plus {@code N} independent
 * field errors</strong>. It is never a single boolean, never a single error, and never a list of
 * summary messages. The summary slot is the {@code ERRMSG} family, and the program writes to that
 * family <strong>exactly once in its entire 4,236 lines</strong>, at line 2981 inside
 * {@code 3250-SETUP-INFOMSG} - which is the mechanical confirmation that one slot is the whole
 * contract.
 *
 * <h2>The field errors are re-entry only</h2>
 *
 * <p>The decoration macro fires only when a field's flag is not-OK <em>or</em> blank
 * <em>and</em> the program-context re-enter condition is set. Blank means the operator supplied
 * nothing and maps to {@link ErrorResponse.FieldState#MISSING}; not-OK means the operator
 * supplied something that failed its edit and maps to
 * {@link ErrorResponse.FieldState#INVALID}. The macro's two rendering acts - writing a colour
 * into the field's indicator sub-item, and in the blank case additionally overwriting the
 * displayed value with a single marker character - are 3270 mechanisms with no remote analogue
 * and are discarded entirely: neither surfaces here in any form.
 *
 * <p>Because the gate is on re-entry, <strong>a first submission carries no field errors at
 * all</strong>, and this type is fully constructible in that state - the collection is simply
 * empty. This type does not evaluate the gate. The enter-versus-re-enter condition travels as
 * echoed client state on {@link NavigationContext}, and the service decides from it whether to
 * populate anything.
 *
 * <p>The entries reuse {@link ErrorResponse.FieldError} and its two-constant state enum rather
 * than declaring a parallel pair. A structurally identical pair also exists on the
 * validation-failure carrier in {@code com.carddemo.exception}; that duplication is deliberate,
 * because the module's layering forbids {@code api.dto} from depending on the failure-carrier
 * package and {@code com.carddemo.api.GlobalExceptionHandler} owns the translation. Importing it
 * here would invert the dependency direction the build enforces (decision log entry D-33).
 *
 * <h2>The 39 decoration identities</h2>
 *
 * <p>The macro is expanded 39 times between {@code COACTUPC} lines 3208 and 3432, always against
 * the same map, so 39 of the 43 values are decoration targets and exactly four are
 * editable-but-undecorated: the account id, the account group id, the customer id and the
 * government-issued id. Each value component below names its own expansion line, so the full
 * inventory is verifiable component by component rather than only in prose.
 *
 * <p>Three cautions attach to that inventory, all of them measured.
 *
 * <ul>
 *   <li><b>Three source comments are mislabelled, and the substitution token governs.</b> The
 *       comment at line 3375 reads as the state field but sits above the postal-code expansion;
 *       the comments at lines 3426 and 3431 are a transposed pair, so the correct final mappings
 *       are primary-cardholder to the {@code ACSPFLG} field at line 3427 and transfer-account id
 *       to the {@code ACSEFTC} field at line 3432. A hand-written expansion of the same macro
 *       sits commented out at lines 3198 to 3205, under the banner at 3194 to 3197, and stays
 *       inactive.</li>
 *   <li><b>The emission order is irregular and is recorded as found.</b> The state field (3364)
 *       sits between the two address lines (3358 and 3370), and the postal code (3376) precedes
 *       the city (3382) and the country (3388). That is map declaration order rather than
 *       conventional postal order, and nothing here reorders it.</li>
 *   <li><b>Two of the 39 are decorated but never validated.</b> The source says so directly: the
 *       comment at line 3345 marks the middle name as having no edits coded, and the comment at
 *       line 3369 marks the second address line the same way. Both are representable as entries
 *       structurally, because the macro is expanded for them, but no service ever flags them and
 *       - exactly as on {@link AccountUpdateRequest} - <strong>neither component carries any
 *       constraint annotation at all</strong> (decision log entry D-34).</li>
 * </ul>
 *
 * <h2>Message widths, and a figure worth correcting</h2>
 *
 * <p>Two widths matter per message family and both are recorded, because they differ. The map
 * declares the summary family at 78 characters and the informational family at 45, and those are
 * the wire figures used by the constraints below. The program stages each through a narrower
 * working field first: the summary through a 75-character field declared at {@code COACTUPC} line
 * 479, and the informational text through a 40-character field declared at line 463. A reader who
 * expects the summary staging field to be 80 characters is thinking of the sign-on program, whose
 * message field is that width; this program declares no 80-character message field, and its only
 * wider one is the 500-character diagnostic buffer at line 462.
 *
 * <h2>The control components</h2>
 *
 * <p>Six components carry no legacy field value and exist to make the response actionable.
 *
 * <ul>
 *   <li><b>An explicit error indicator.</b> It is its own fact, supplied by the service, and is
 *       <em>never</em> inferred from the summary message being present or from the field-error
 *       collection being non-empty. The legacy carried a distinct condition for exactly this and
 *       consulted it separately, and collapsing the three into one derived test would lose the
 *       state in which a submission failed with a summary text but no decorated field.</li>
 *   <li><b>A focus hint.</b> The legacy positioned the cursor by writing a sentinel into a
 *       field's generated length sub-item, in screen-location order as its comment at line 3008
 *       says, across 41 sites from 3012 to 3166, and then named the cursor option on the send at
 *       line 3597. None of that mechanism crosses the boundary: this component carries the
 *       <em>identity</em> of the field to focus and nothing else - no sentinel value, no row, no
 *       column, no length sub-item and no terminal indicator byte. It is bounded at 7 because 7
 *       is the longest map field name in this mapset, measured across all 54.</li>
 *   <li><b>A declarative route.</b> The legacy transferred control program to program 25 times
 *       and re-armed the next transaction 19 times; both become a route value the client acts on
 *       for its next call, because there is no server-side forwarding. It is an opaque string
 *       supplied by the service: this file holds no route table, no route enum and no navigation
 *       behaviour, and the routing vocabulary belongs to the navigation service.</li>
 *   <li><b>The echoed conversation state.</b> A {@link NavigationContext} stands in for the
 *       communication area that the legacy carried across pseudo-conversational turns. It is
 *       echoed client state and not a server session, and its program-context condition is what
 *       gates whether field-level decoration is applied at all.</li>
 *   <li><b>The field-error collection</b>, always present, never {@code null}, never mutable.</li>
 *   <li><b>The echoed conversation token</b>, described in its own section below. Like the four
 *       above it, it is state the response has to carry for the next turn to be possible; unlike
 *       them it stands for something the map never showed.</li>
 * </ul>
 *
 * <h2>The conversation token, and why this response must carry it</h2>
 *
 * <p>This response has one component that is not a map field and not a control hint, and it is
 * here because the legacy transaction carries state across its turns that the screen never
 * displayed. {@code COACTUPC} declares a program communication-area extension at line 652 whose
 * leading group is the complete old image of the account and the customer as they stood when the
 * screen was presented. That extension is appended to the shared communication area and handed
 * back with the screen at lines 1010 to 1018, then sliced off again on the following turn at
 * lines 888 to 892. When the operator confirms, the program reads both records for update and
 * only then compares the freshly read records field by field against the carried old image, in
 * paragraph {@code 9700-CHECK-CHANGE-IN-REC} at line 4109 - reached from line 3947 and running
 * to its exit at 4193. Any single difference abandons the write.
 *
 * <p>Re-reading the records at the start of the confirming turn would not reproduce that, because
 * the whole purpose of the comparison is to detect a change made <em>after</em> the screen was
 * presented. The state being compared therefore has to travel with the conversation, which in a
 * stateless request-response contract means it has to leave on this response and come back on the
 * next request. {@link AccountUpdateRequest} already declares the returning half; without the
 * outbound half published here there is nothing for a client to return, and
 * {@code com.carddemo.service.AccountConcurrencyTokenService} treats an absent token as a
 * conflict - so the second turn of the transaction could never complete. The two halves are one
 * contract and only work as a pair.
 *
 * <p>In the legacy the carried image was safe because the communication area is held by the
 * transaction manager and the terminal never sees it. Handed to a client it would not be, so what
 * travels is not the image: the service seals a pair of digests into an opaque,
 * integrity-protected value that a client can return and cannot read, forge or edit. Nothing
 * about the records can be recovered from it.
 *
 * <p><strong>This remains a data carrier and performs no business logic.</strong> It does not
 * mint the token, does not verify it, does not compare images, does not detect change and holds
 * no record image, digest, sequence number or row-revision counter of any kind - the token is one
 * opaque string, and every mechanism behind it belongs to the service. For context only: the
 * estate's single rollback sits on the customer-rewrite failure arm at {@code COACTUPC} lines
 * 4095 to 4103, with the rollback itself at 4099 to 4101, while the account-rewrite failure arm
 * at 4076 to 4081 issues none - an asymmetry preserved in the update service, not here. When the
 * service finds that a record moved it raises the dedicated failure carrier from
 * {@code com.carddemo.exception}, which this file does not and may not import; the response
 * simply carries whatever resulting text reaches the summary slot.
 *
 * <h2>Validation policy</h2>
 *
 * <p>This is a response, so declarative validation is largely beside the point, and the only
 * annotation used anywhere in this file is a maximum-length constraint at each component's
 * measured map width. A length constraint neither trims a value nor disturbs leading or trailing
 * spaces, which matters because every one of these fields is fixed-width and space-significant.
 * No presence, pattern, digit-count or numeric-bound annotation appears: every field can
 * legitimately arrive blank or space-padded; the credit-score bound of 300 through 850 is a
 * request-side rule enforced inside the service's ordered cascade, where 21 of the 50 seeded
 * customers score below 300 with a minimum of {@code 001}; and unordered declarative validation
 * must never replace a source-ordered cascade, because it would produce a different summary
 * message for the same input.
 *
 * <p><strong>Nothing is trimmed, padded, re-cased or normalised.</strong> Values and message
 * texts are carried exactly as supplied, including trailing spaces, because a padded fixed-width
 * value that arrives trimmed is a parity defect.
 *
 * <h2>Wire contract</h2>
 *
 * <p>The module configures absent members to be omitted rather than sent as {@code null},
 * unknown inbound members to be tolerated, and decimals to be rendered plainly so that a
 * fixed-scale value can never arrive in scientific notation. This type therefore needs, and
 * carries, no serialization annotation, no custom serializer and no mapper override. Combined
 * with the normalization in the canonical constructor that fixes the payload shape: the
 * field-error collection is always present and is emitted as an empty array when there are none,
 * so a client never has to test it for {@code null}, while absent values and absent texts are
 * simply absent. Instances are deeply immutable and therefore safe to share across threads.
 *
 * @param transactionName transaction name - map field {@code TRNNAME}, width 4. Screen metadata,
 *        protected on the mapset and written by {@code 3100-SCREEN-INIT} at {@code COACTUPC}
 *        line 2675 from the program's own transaction literal.
 * @param title01 first title line - map field {@code TITLE01}, width 40. Screen metadata, written
 *        at line 2673 from the shared title catalog.
 * @param currentDate current date - map field {@code CURDATE}, width 8. Screen metadata, written
 *        at line 2684 in month-day-year form. Carried as characters, never as a temporal type,
 *        and never parsed here.
 * @param programName program name - map field {@code PGMNAME}, width 8. Screen metadata, written
 *        at line 2676 from the program's own name literal.
 * @param title02 second title line - map field {@code TITLE02}, width 40. Screen metadata,
 *        written at line 2674 from the shared title catalog.
 * @param currentTime current time - map field {@code CURTIME}, width 8. Screen metadata, written
 *        at line 2690 in hour-minute-second form. Carried as characters, never parsed here.
 * @param accountId account id - map field {@code ACCTSID}, width 11. Editable but never
 *        decorated. Kept a string so an 11-character key with leading zeroes survives intact.
 * @param accountStatus account active status - map field {@code ACSTTUS}, width 1, decorated at
 *        line 3208. Carried as the raw character rather than an enumeration, because the legacy
 *        accepted the value first and judged it afterwards.
 * @param openYear account open date, year part - map field {@code OPNYEAR}, width 4, decorated at
 *        line 3214.
 * @param openMonth account open date, month part - map field {@code OPNMON}, width 2, decorated
 *        at line 3220.
 * @param openDay account open date, day part - map field {@code OPNDAY}, width 2, decorated at
 *        line 3226.
 * @param creditLimit credit limit - map field {@code ACRDLIM}, width 15, decorated at line 3232.
 *        Scale 2 by contract; see the monetary note above.
 * @param expiryYear account expiry date, year part - map field {@code EXPYEAR}, width 4,
 *        decorated at line 3238.
 * @param expiryMonth account expiry date, month part - map field {@code EXPMON}, width 2,
 *        decorated at line 3244.
 * @param expiryDay account expiry date, day part - map field {@code EXPDAY}, width 2, decorated
 *        at line 3250.
 * @param cashCreditLimit cash credit limit - map field {@code ACSHLIM}, width 15, decorated at
 *        line 3256. Scale 2 by contract.
 * @param reissueYear account reissue date, year part - map field {@code RISYEAR}, width 4,
 *        decorated at line 3262. Easy to overlook because the screen is often described as
 *        carrying three dates; the map declares four.
 * @param reissueMonth account reissue date, month part - map field {@code RISMON}, width 2,
 *        decorated at line 3268.
 * @param reissueDay account reissue date, day part - map field {@code RISDAY}, width 2, decorated
 *        at line 3274.
 * @param currentBalance current balance - map field {@code ACURBAL}, width 15, decorated at line
 *        3280. Scale 2 by contract.
 * @param currentCycleCredit current cycle credit - map field {@code ACRCYCR}, width 15, decorated
 *        at line 3286. Scale 2 by contract.
 * @param accountGroupId account group id - map field {@code AADDGRP}, width 10. Editable but
 *        never decorated.
 * @param currentCycleDebit current cycle debit - map field {@code ACRCYDB}, width 15, decorated
 *        at line 3292. Scale 2 by contract.
 * @param customerId customer id - map field {@code ACSTNUM}, width 9. Editable but never
 *        decorated. Kept a string so a 9-character key with leading zeroes survives intact.
 * @param ssnPart1 social-security number, first part - map field {@code ACTSSN1}, width 3,
 *        decorated at line 3298. Carried unmasked, exactly as the legacy screen did.
 * @param ssnPart2 social-security number, second part - map field {@code ACTSSN2}, width 2,
 *        decorated at line 3304. Carried unmasked.
 * @param ssnPart3 social-security number, third part - map field {@code ACTSSN3}, width 4,
 *        decorated at line 3310. Carried unmasked.
 * @param dateOfBirthYear date of birth, year part - map field {@code DOBYEAR}, width 4, decorated
 *        at line 3316.
 * @param dateOfBirthMonth date of birth, month part - map field {@code DOBMON}, width 2,
 *        decorated at line 3322.
 * @param dateOfBirthDay date of birth, day part - map field {@code DOBDAY}, width 2, decorated at
 *        line 3328.
 * @param ficoScore credit score - map field {@code ACSTFCO}, width 3, decorated at line 3334.
 *        A string, not a number, so that {@code 001} survives. Its inclusive 300 to 850 bound is
 *        documented here and enforced by the service inside the ordered cascade.
 * @param firstName first name - map field {@code ACSFNAM}, width 25, decorated at line 3340.
 * @param middleName middle name - map field {@code ACSMNAM}, width 25, decorated at line 3346 but
 *        never validated, as the comment at line 3345 states. Carries no annotation.
 * @param lastName last name - map field {@code ACSLNAM}, width 25, decorated at line 3352.
 * @param addressLine1 first address line - map field {@code ACSADL1}, width 50, decorated at line
 *        3358.
 * @param stateCode state code - map field {@code ACSSTTE}, width 2, decorated at line 3364, which
 *        the cascade emits between the two address lines. Also flagged by the combined
 *        state-and-postal-code check at line 2546.
 * @param addressLine2 second address line - map field {@code ACSADL2}, width 50, decorated at
 *        line 3370 but never validated, as the comment at line 3369 states. Carries no
 *        annotation.
 * @param zipCode postal code - map field {@code ACSZIPC}, width 5, decorated at line 3376, before
 *        the city and the country. The adjacent comment at line 3375 is mislabelled; the
 *        substitution token governs. Also flagged by the combined check at line 2547.
 * @param city city - map field {@code ACSCITY}, width 50, decorated at line 3382.
 * @param countryCode country code - map field {@code ACSCTRY}, width 3, decorated at line 3388.
 * @param phone1AreaCode first telephone number, area code - map field {@code ACSPH1A}, width 3,
 *        decorated at line 3394.
 * @param phone1Prefix first telephone number, prefix - map field {@code ACSPH1B}, width 3,
 *        decorated at line 3400.
 * @param phone1LineNumber first telephone number, line number - map field {@code ACSPH1C}, width
 *        4, decorated at line 3405.
 * @param governmentIssuedId government-issued id - map field {@code ACSGOVT}, width 20. Editable
 *        but never decorated. Carried unmasked, exactly as the legacy screen did.
 * @param phone2AreaCode second telephone number, area code - map field {@code ACSPH2A}, width 3,
 *        decorated at line 3411.
 * @param phone2Prefix second telephone number, prefix - map field {@code ACSPH2B}, width 3,
 *        decorated at line 3417.
 * @param phone2LineNumber second telephone number, line number - map field {@code ACSPH2C}, width
 *        4, decorated at line 3422.
 * @param eftAccountId transfer-account id - map field {@code ACSEFTC}, width 10, decorated at
 *        line 3432. The comment at line 3431 is the transposed half of a mislabelled pair.
 * @param primaryCardHolderIndicator primary-cardholder indicator - map field {@code ACSPFLG},
 *        width 1, decorated at line 3427, which the cascade emits before the transfer-account id.
 *        The comment at line 3426 is the other transposed half.
 * @param infoMessage informational text - map field {@code INFOMSG}, width 45, selected by
 *        {@code 3250-SETUP-INFOMSG} and written at line 2979 from a 40-character working field
 *        declared at line 463. This is guidance, not an error.
 * @param errorMessage <strong>the single summary message</strong> - map field {@code ERRMSG},
 *        width 78, written exactly once in the whole program, at line 2981, from the 75-character
 *        working field declared at line 479. One text accompanies any number of field errors,
 *        because of the first-error-wins gate. Composing the text is the service's job, not this
 *        type's, and the constants below are its byte-exact vocabulary.
 * @param error whether the submission is in error. An explicit fact supplied by the service,
 *        never derived from the message or the collection; see the control-component note above.
 * @param focusScreenFieldId the legacy screen field identifier that input focus should be placed
 *        on, or {@code null} when the response gives no hint. An opaque label only - never a
 *        cursor position, a sentinel or an indicator byte.
 * @param nextRoute the route the client should call next, as an opaque value supplied by the
 *        service, or {@code null} when the response implies no move. This type resolves nothing.
 * @param navigationContext the echoed conversation state, or {@code null} when the caller sent
 *        none. Its program-context condition gates field-level decoration.
 * @param fieldErrors the independent per-field errors, never {@code null} and never mutable.
 *        Empty means no field-level error, which is also the first-submission case.
 * @param concurrencyToken the opaque, integrity-protected description of the account and customer
 *        records as they stood when this screen was presented, minted by
 *        {@code com.carddemo.service.AccountConcurrencyTokenService} and to be returned unchanged
 *        on {@link AccountUpdateRequest}. Not a map field, and {@code null} on a response that
 *        presents no record to confirm. Opaque by construction: nothing about the records can be
 *        read out of it.
 * @since 1.0.0
 */
public record AccountUpdateResponse(

        /* SCREEN METADATA - map families 1 to 6, protected on the mapset, absent from the request.
         * All six are written by 3100-SCREEN-INIT at COACTUPC lines 2668-2692. */

        /* 1. TRNNAME, width 4 - written at COACTUPC:2675. */
        @Size(max = 4) String transactionName,

        /* 2. TITLE01, width 40 - written at COACTUPC:2673. */
        @Size(max = 40) String title01,

        /* 3. CURDATE, width 8 - written at COACTUPC:2684, month-day-year. */
        @Size(max = 8) String currentDate,

        /* 4. PGMNAME, width 8 - written at COACTUPC:2676. */
        @Size(max = 8) String programName,

        /* 5. TITLE02, width 40 - written at COACTUPC:2674. */
        @Size(max = 40) String title02,

        /* 6. CURTIME, width 8 - written at COACTUPC:2690, hour-minute-second. */
        @Size(max = 8) String currentTime,

        /* THE 43 RESULTING VALUES - map families 7 to 49, in the same order the request uses.
         * Written back by 3201-SHOW-INITIAL-VALUES (COACTUPC:2731), 3202-SHOW-ORIGINAL-VALUES
         * (2787) and 3203-SHOW-UPDATED-VALUES (2870). */

        /* 7. ACCTSID, width 11 - editable, NOT decorated. */
        @Size(max = 11) String accountId,

        /* 8. ACSTTUS, width 1 - decorated at COACTUPC:3208 (token ACCT-STATUS). */
        @Size(max = 1) String accountStatus,

        /* 9. OPNYEAR, width 4 - decorated at COACTUPC:3214 (token OPEN-YEAR). */
        @Size(max = 4) String openYear,

        /* 10. OPNMON, width 2 - decorated at COACTUPC:3220 (token OPEN-MONTH). */
        @Size(max = 2) String openMonth,

        /* 11. OPNDAY, width 2 - decorated at COACTUPC:3226 (token OPEN-DAY). */
        @Size(max = 2) String openDay,

        /* 12. ACRDLIM, width 15 - decorated at COACTUPC:3232 (token CRED-LIMIT). Record
         * counterpart ACCT-CREDIT-LIMIT at CVACT01Y.cpy line 8. */
        @Schema(description = "Account credit limit. Record field ACCT-CREDIT-LIMIT of "
                + "CVACT01Y.cpy line 8: a signed zoned decimal with ten integer digits and two "
                + "decimal places, so total precision 12 and scale exactly 2.")
        BigDecimal creditLimit,

        /* 13. EXPYEAR, width 4 - decorated at COACTUPC:3238 (token EXPIRY-YEAR). */
        @Size(max = 4) String expiryYear,

        /* 14. EXPMON, width 2 - decorated at COACTUPC:3244 (token EXPIRY-MONTH). */
        @Size(max = 2) String expiryMonth,

        /* 15. EXPDAY, width 2 - decorated at COACTUPC:3250 (token EXPIRY-DAY). */
        @Size(max = 2) String expiryDay,

        /* 16. ACSHLIM, width 15 - decorated at COACTUPC:3256 (token CASH-CREDIT-LIMIT). Record
         * counterpart ACCT-CASH-CREDIT-LIMIT at CVACT01Y.cpy line 9. */
        @Schema(description = "Account cash credit limit. Record field ACCT-CASH-CREDIT-LIMIT of "
                + "CVACT01Y.cpy line 9: a signed zoned decimal with ten integer digits and two "
                + "decimal places, so total precision 12 and scale exactly 2.")
        BigDecimal cashCreditLimit,

        /* 17. RISYEAR, width 4 - decorated at COACTUPC:3262 (token REISSUE-YEAR). */
        @Size(max = 4) String reissueYear,

        /* 18. RISMON, width 2 - decorated at COACTUPC:3268 (token REISSUE-MONTH). */
        @Size(max = 2) String reissueMonth,

        /* 19. RISDAY, width 2 - decorated at COACTUPC:3274 (token REISSUE-DAY). */
        @Size(max = 2) String reissueDay,

        /* 20. ACURBAL, width 15 - decorated at COACTUPC:3280 (token CURR-BAL). Record
         * counterpart ACCT-CURR-BAL at CVACT01Y.cpy line 7. */
        @Schema(description = "Account current balance. Record field ACCT-CURR-BAL of "
                + "CVACT01Y.cpy line 7: a signed zoned decimal with ten integer digits and two "
                + "decimal places, so total precision 12 and scale exactly 2.")
        BigDecimal currentBalance,

        /* 21. ACRCYCR, width 15 - decorated at COACTUPC:3286 (token CURR-CYC-CREDIT). Record
         * counterpart ACCT-CURR-CYC-CREDIT at CVACT01Y.cpy line 13. */
        @Schema(description = "Current cycle credit. Record field ACCT-CURR-CYC-CREDIT of "
                + "CVACT01Y.cpy line 13: a signed zoned decimal with ten integer digits and two "
                + "decimal places, so total precision 12 and scale exactly 2.")
        BigDecimal currentCycleCredit,

        /* 22. AADDGRP, width 10 - editable, NOT decorated. */
        @Size(max = 10) String accountGroupId,

        /* 23. ACRCYDB, width 15 - decorated at COACTUPC:3292 (token CURR-CYC-DEBIT). Record
         * counterpart ACCT-CURR-CYC-DEBIT at CVACT01Y.cpy line 14. */
        @Schema(description = "Current cycle debit. Record field ACCT-CURR-CYC-DEBIT of "
                + "CVACT01Y.cpy line 14: a signed zoned decimal with ten integer digits and two "
                + "decimal places, so total precision 12 and scale exactly 2.")
        BigDecimal currentCycleDebit,

        /* 24. ACSTNUM, width 9 - editable, NOT decorated. */
        @Size(max = 9) String customerId,

        /* 25. ACTSSN1, width 3 - decorated at COACTUPC:3298 (token EDIT-US-SSN-PART1). */
        @Size(max = 3) String ssnPart1,

        /* 26. ACTSSN2, width 2 - decorated at COACTUPC:3304 (token EDIT-US-SSN-PART2). */
        @Size(max = 2) String ssnPart2,

        /* 27. ACTSSN3, width 4 - decorated at COACTUPC:3310 (token EDIT-US-SSN-PART3). */
        @Size(max = 4) String ssnPart3,

        /* 28. DOBYEAR, width 4 - decorated at COACTUPC:3316 (token DT-OF-BIRTH-YEAR). */
        @Size(max = 4) String dateOfBirthYear,

        /* 29. DOBMON, width 2 - decorated at COACTUPC:3322 (token DT-OF-BIRTH-MONTH). */
        @Size(max = 2) String dateOfBirthMonth,

        /* 30. DOBDAY, width 2 - decorated at COACTUPC:3328 (token DT-OF-BIRTH-DAY). */
        @Size(max = 2) String dateOfBirthDay,

        /* 31. ACSTFCO, width 3 - decorated at COACTUPC:3334 (token FICO-SCORE). */
        @Size(max = 3) String ficoScore,

        /* 32. ACSFNAM, width 25 - decorated at COACTUPC:3340 (token FIRST-NAME). */
        @Size(max = 25) String firstName,

        /* 33. ACSMNAM, width 25 - decorated at COACTUPC:3346 (token MIDDLE-NAME).
         * INTENTIONALLY UNANNOTATED - DO NOT ADD ANY CONSTRAINT, NOT EVEN @Size. The comment at
         * COACTUPC:3345 records that no edits are coded for this field, so the legacy accepts any
         * value; decision log entry D-34 and the @param tag above carry the measured evidence.
         * The request component is unannotated for the same reason, and the two must agree. */
        String middleName,

        /* 34. ACSLNAM, width 25 - decorated at COACTUPC:3352 (token LAST-NAME). */
        @Size(max = 25) String lastName,

        /* 35. ACSADL1, width 50 - decorated at COACTUPC:3358 (token ADDRESS-LINE-1). */
        @Size(max = 50) String addressLine1,

        /* 36. ACSSTTE, width 2 - decorated at COACTUPC:3364 (token STATE), which the cascade
         * emits between the two address lines, following map declaration order rather than
         * conventional postal order; recorded as-is. Also flagged at COACTUPC:2546 by the
         * combined state-and-postal-code check, which flags two fields from one failure. */
        @Size(max = 2) String stateCode,

        /* 37. ACSADL2, width 50 - decorated at COACTUPC:3370 (token ADDRESS-LINE-2).
         * INTENTIONALLY UNANNOTATED - DO NOT ADD ANY CONSTRAINT, NOT EVEN @Size. The comment at
         * COACTUPC:3369 records that no edits are coded as yet, so this field accepts ANY value;
         * decision log entry D-34 and the @param tag above carry the measured evidence. */
        String addressLine2,

        /* 38. ACSZIPC, width 5 - decorated at COACTUPC:3376 (token ZIPCODE), before the city and
         * the country, following map declaration order. The adjacent source comment at
         * COACTUPC:3375 reads "State" and is mislabelled; the substitution token governs. Also
         * flagged at COACTUPC:2547 by the combined check. */
        @Size(max = 5) String zipCode,

        /* 39. ACSCITY, width 50 - decorated at COACTUPC:3382 (token CITY). */
        @Size(max = 50) String city,

        /* 40. ACSCTRY, width 3 - decorated at COACTUPC:3388 (token COUNTRY). */
        @Size(max = 3) String countryCode,

        /* 41. ACSPH1A, width 3 - decorated at COACTUPC:3394 (token PHONE-NUM-1A). */
        @Size(max = 3) String phone1AreaCode,

        /* 42. ACSPH1B, width 3 - decorated at COACTUPC:3400 (token PHONE-NUM-1B). */
        @Size(max = 3) String phone1Prefix,

        /* 43. ACSPH1C, width 4 - decorated at COACTUPC:3405 (token PHONE-NUM-1C). */
        @Size(max = 4) String phone1LineNumber,

        /* 44. ACSGOVT, width 20 - editable, NOT decorated. Transported unmasked. */
        @Size(max = 20) String governmentIssuedId,

        /* 45. ACSPH2A, width 3 - decorated at COACTUPC:3411 (token PHONE-NUM-2A). */
        @Size(max = 3) String phone2AreaCode,

        /* 46. ACSPH2B, width 3 - decorated at COACTUPC:3417 (token PHONE-NUM-2B). */
        @Size(max = 3) String phone2Prefix,

        /* 47. ACSPH2C, width 4 - decorated at COACTUPC:3422 (token PHONE-NUM-2C). */
        @Size(max = 4) String phone2LineNumber,

        /* 48. ACSEFTC, width 10 - decorated at COACTUPC:3432 (token EFT-ACCOUNT-ID). The comment
         * at COACTUPC:3431 reads "Primary Card Holder" and is the transposed half of a
         * mislabelled pair; the substitution token governs. */
        @Size(max = 10) String eftAccountId,

        /* 49. ACSPFLG, width 1 - decorated at COACTUPC:3427 (token PRI-CARDHOLDER), which the
         * cascade emits BEFORE the transfer-account id, inverting map declaration order. The
         * comment at COACTUPC:3426 reads "EFT Account Id" and is the other transposed half. */
        @Size(max = 1) String primaryCardHolderIndicator,

        /* THE TWO MESSAGE FAMILIES - map families 50 and 51, protected on the mapset. Both are
         * written by 3250-SETUP-INFOMSG at COACTUPC:2955-2982. */

        /* 50. INFOMSG, width 45 - written at COACTUPC:2979 from the 40-character working field
         * declared at COACTUPC:463. Guidance, not an error. */
        @Size(max = 45) String infoMessage,

        /* 51. ERRMSG, width 78 - THE SINGLE SUMMARY SLOT, written exactly once in the whole
         * 4,236-line program, at COACTUPC:2981, from the 75-character working field declared at
         * COACTUPC:479. One text accompanies N field errors. */
        @Size(max = 78) String errorMessage,

        /* THE FIVE CONTROL COMPONENTS - no legacy field value; see the class documentation. */

        /* 52. An explicit fact, NEVER derived from errorMessage or fieldErrors. */
        boolean error,

        /* 53. The identity of the field to focus, bounded at 7 - the longest map field name in
         * this mapset, measured across all 54. Identity only: no cursor position, no sentinel,
         * no length sub-item, no indicator byte. */
        @Size(max = 7) String focusScreenFieldId,

        /* 54. The declarative next route, opaque and unbounded: it is not a legacy field, so it
         * has no measured width, and the navigation service owns its vocabulary. */
        String nextRoute,

        /* 55. Echoed conversation state, not a server session. */
        NavigationContext navigationContext,

        /* 56. Independent per-field errors, normalized in the canonical constructor below. */
        List<ErrorResponse.FieldError> fieldErrors,

        /* 57. Not a map field. The outbound half of the program commarea extension COACTUPC
         * carries across the pseudo-conversational turn, described on the type above. Declared
         * last, matching the position its returning counterpart occupies on AccountUpdateRequest.
         * Opaque and unbounded by design, and deliberately unannotated: it has no legacy width
         * because it is not a legacy field, and its absence on a response that presents nothing
         * to confirm is ordinary rather than a defect. */
        String concurrencyToken) {

    /*
     * ================================================================================
     * THE MESSAGE VOCABULARY - byte-exact, and deliberately quirky
     * ================================================================================
     *
     * Every constant below is the legacy text at the cited line of app/cbl/COACTUPC.cbl,
     * reproduced character for character because these texts are external interface contract:
     * operators read them and downstream tooling matches on them, so the interface-contract gate
     * checks them character for character.
     *
     * DO NOT "CLEAN UP" ANY OF THEM. Four traps are live, and each looks like a typo:
     *   - "Looks Good.... so far" really has FOUR dots.
     *   - "Record changed by some one else. Please review" really spells "some one" as TWO words.
     *   - The three digit-count texts really capitalise the article: "must be A 3 digit number.".
     *   - Two texts really end without a full stop, and one carries no field-name prefix at all.
     * Each of those is called out again on the constant it belongs to.
     *
     * Twelve of the constants begin with a colon and a space because the legacy composed them by
     * concatenating a trimmed field label with the suffix. THIS FILE DOES NO COMPOSING: it
     * neither concatenates, formats, trims nor pads. The update service assembles the text and
     * hands the finished value to the summary slot; these constants exist so that the assembling
     * code and the tests that check it share one authority for the fragment, rather than each
     * retyping a string whose exact punctuation is load-bearing.
     */

    /**
     * Credit-score range failure, from {@code COACTUPC} line 2523.
     *
     * <p>Written by {@code 1275-EDIT-FICO-SCORE} onto a trimmed field label. <strong>It ends
     * without a full stop</strong>, unlike most of its neighbours, and that is the legacy text.
     */
    public static final String SUFFIX_FICO_OUT_OF_RANGE = ": should be between 300 and 850";

    /**
     * State-code membership failure, from {@code COACTUPC} line 2503.
     *
     * <p>Written by {@code 1270-EDIT-US-STATE-CD}, which is a flat membership test performing no
     * trim, no numeric check and no blank pre-check. <strong>It ends without a full stop.</strong>
     */
    public static final String SUFFIX_STATE_NOT_VALID = ": is not a valid state code";

    /**
     * Telephone area code absent, from {@code COACTUPC} line 2254, in {@code EDIT-AREA-CODE}.
     */
    public static final String SUFFIX_AREA_CODE_REQUIRED = ": Area code must be supplied.";

    /**
     * Telephone area code not three digits, from {@code COACTUPC} line 2272.
     *
     * <p><strong>The article is capitalised in the legacy text</strong> - "must be A 3 digit
     * number." - and is reproduced that way deliberately.
     */
    public static final String SUFFIX_AREA_CODE_NOT_3_DIGITS =
            ": Area code must be A 3 digit number.";

    /**
     * Telephone area code of zero, from {@code COACTUPC} line 2286. Ends without a full stop.
     */
    public static final String SUFFIX_AREA_CODE_ZERO = ": Area code cannot be zero";

    /**
     * Telephone area code outside the general-purpose set, from {@code COACTUPC} line 2306.
     *
     * <p>Raised after the membership test against the general-purpose numbering-plan codes. Ends
     * without a full stop. At 51 characters this is the longest of the suffixes, which is worth
     * knowing when a caller composes it onto a label and the result has to fit the summary slot.
     */
    public static final String SUFFIX_AREA_CODE_NOT_GENERAL_PURPOSE =
            ": Not valid North America general purpose area code";

    /**
     * Telephone prefix absent, from {@code COACTUPC} line 2325, in
     * {@code EDIT-US-PHONE-PREFIX}.
     */
    public static final String SUFFIX_PREFIX_REQUIRED = ": Prefix code must be supplied.";

    /**
     * Telephone prefix not three digits, from {@code COACTUPC} line 2343.
     *
     * <p><strong>The article is capitalised in the legacy text.</strong>
     */
    public static final String SUFFIX_PREFIX_NOT_3_DIGITS =
            ": Prefix code must be A 3 digit number.";

    /**
     * Telephone prefix of zero, from {@code COACTUPC} line 2357. Ends without a full stop.
     */
    public static final String SUFFIX_PREFIX_ZERO = ": Prefix code cannot be zero";

    /**
     * Telephone line number absent, from {@code COACTUPC} line 2378, in
     * {@code EDIT-US-PHONE-LINENUM}.
     */
    public static final String SUFFIX_LINE_NUMBER_REQUIRED =
            ": Line number code must be supplied.";

    /**
     * Telephone line number not four digits, from {@code COACTUPC} line 2396.
     *
     * <p><strong>The article is capitalised in the legacy text</strong>, and the digit count is
     * four here rather than the three of the two stages above.
     */
    public static final String SUFFIX_LINE_NUMBER_NOT_4_DIGITS =
            ": Line number code must be A 4 digit number.";

    /**
     * Telephone line number of zero, from {@code COACTUPC} line 2410. Ends without a full stop.
     */
    public static final String SUFFIX_LINE_NUMBER_ZERO = ": Line number code cannot be zero";

    /**
     * Combined state-and-postal-code failure, from {@code COACTUPC} line 2550.
     *
     * <p><strong>This one is bare.</strong> It is the only text in the program's validation
     * surface written without a leading field-name prefix, because no single field is at fault:
     * the check concatenates the state code with the first two characters of the postal code and
     * tests the pair, so on failure it flags <em>both</em> fields - the state at line 2546 and the
     * postal code at line 2547 - and emits one unattributed sentence. A caller must not prefix a
     * field name onto it, and must expect two field errors alongside it.
     */
    public static final String MSG_INVALID_ZIP_FOR_STATE = "Invalid zip code for state";

    /**
     * Account key unusable, from {@code COACTUPC} lines 494 and 496.
     *
     * <p>One text serves two distinct conditions - an all-zeroes key and a non-numeric key - so
     * the legacy deliberately told the operator the same thing either way. Reproduced once here
     * because the two conditions are indistinguishable on the wire, exactly as they were on the
     * screen.
     */
    public static final String MSG_ACCOUNT_NUMBER_NOT_USABLE =
            "Account number must be a non zero 11 digit number";

    /**
     * Account active status outside the permitted pair, from {@code COACTUPC} line 504.
     */
    public static final String MSG_ACCOUNT_STATUS_MUST_BE_YES_NO =
            "Account Active Status must be Y or N";

    /**
     * Credit limit absent, from {@code COACTUPC} line 506. Ends without a full stop.
     */
    public static final String MSG_CREDIT_LIMIT_REQUIRED = "Credit Limit must be supplied";

    /**
     * Credit limit present but not a valid signed two-decimal value, from {@code COACTUPC} line
     * 508.
     */
    public static final String MSG_CREDIT_LIMIT_NOT_VALID = "Credit Limit is not valid";

    /**
     * Card expiry month outside 1 through 12, from {@code COACTUPC} line 510.
     */
    public static final String MSG_EXPIRY_MONTH_NOT_VALID =
            "Card expiry month must be between 1 and 12";

    /**
     * Card expiry year rejected, from {@code COACTUPC} line 512.
     */
    public static final String MSG_EXPIRY_YEAR_NOT_VALID = "Invalid card expiry year";

    /**
     * Account absent from the card database, from {@code COACTUPC} line 514.
     *
     * <p>A near-duplicate condition name earlier in the same block carries different wording, so
     * this text and its neighbour are not interchangeable; this is the one at line 514.
     */
    public static final String MSG_ACCOUNT_NOT_IN_CARD_DATABASE =
            "Did not find this account in cards database";

    /**
     * No cards matched the search condition, from {@code COACTUPC} line 516.
     */
    public static final String MSG_NO_CARDS_FOR_SEARCH_CONDITION =
            "Did not find cards for this search condition";

    /**
     * Card data could not be read, from {@code COACTUPC} line 526.
     *
     * <p>The word "File" is part of the legacy operator wording rather than a path or a storage
     * object name, and nothing about the module's own storage is disclosed by it.
     */
    public static final String MSG_CARD_DATA_READ_ERROR = "Error reading Card Data File";

    /**
     * The success text, from {@code COACTUPC} line 528.
     *
     * <p><strong>FOUR dots.</strong> Not three, not an ellipsis character. The legacy text is
     * exactly {@code Looks Good.... so far} at 21 characters, and shortening the run to an
     * ordinary ellipsis is an interface-contract failure. The condition name it belongs to marks
     * the point at which the program considered the submission acceptable so far, which is why the
     * wording is provisional rather than final.
     */
    public static final String MSG_LOOKS_GOOD_SO_FAR = "Looks Good.... so far";

    /*
     * ----------------------------------------------------------------------------------
     * The four texts of the update-failure arms, from COACTUPC lines 518, 520, 522 and 524.
     *
     * These four are ALSO declared, with the same byte-exact values, on the concurrent-change
     * failure carrier in com.carddemo.exception, which owns the raising side and declares its own
     * kind enumeration alongside them. The duplication is deliberate and is the same trade the
     * module already makes for the per-field state enumeration: this package may not depend on the
     * failure-carrier package - api.dto to exception would invert the layer direction the build
     * enforces, and the global failure handler one level up is the designated translator - so the
     * wire-contract copy lives here and the raising-side copy lives there. Neither imports the
     * other. Both are cited to the same source lines, so a reviewer can confirm they agree without
     * either file reaching across the boundary.
     * ----------------------------------------------------------------------------------
     */

    /**
     * The account record could not be held for update, from {@code COACTUPC} line 518.
     */
    public static final String MSG_COULD_NOT_HOLD_ACCOUNT_FOR_UPDATE =
            "Could not lock account record for update";

    /**
     * The customer record could not be held for update, from {@code COACTUPC} line 520.
     */
    public static final String MSG_COULD_NOT_HOLD_CUSTOMER_FOR_UPDATE =
            "Could not lock customer record for update";

    /**
     * A concurrent change was detected before the update, from {@code COACTUPC} line 522.
     *
     * <p><strong>"some one" is TWO words in the legacy text</strong>, and the sentence ends
     * "Please review" without a full stop. Fusing the two words into one, or adding the stop, is
     * an interface-contract failure. The legacy reached this text by comparing the record image it
     * had read against the image on the store immediately before rewriting.
     */
    public static final String MSG_RECORD_CHANGED_BEFORE_UPDATE =
            "Record changed by some one else. Please review";

    /**
     * The rewrite itself failed after the records were held, from {@code COACTUPC} line 524.
     *
     * <p>One text serves both failure arms, and the arms are not symmetrical: the customer-rewrite
     * arm at lines 4095 to 4103 reverses the work already done, at lines 4099 to 4101, while the
     * account-rewrite arm at lines 4076 to 4081 does not. That asymmetry is reproduced in the
     * update service; from the wire's point of view both arms look identical, which is exactly the
     * legacy behaviour.
     */
    public static final String MSG_UPDATE_OF_RECORD_FAILED = "Update of record failed";

    /**
     * The number of decimal places every monetary component carries, from the two decimal places
     * of the five signed zoned decimals in {@code CVACT01Y.cpy} lines 7, 8, 9, 13 and 14.
     *
     * <p>Public because it is part of the numeric contract rather than an implementation choice:
     * the service that builds a response, and the tests that check one, need the same authority
     * for the figure instead of each restating it.
     */
    public static final int MONEY_SCALE = 2;

    /**
     * The number of integer digits every monetary component may carry, from the ten integer digits
     * of the same five record fields. With {@link #MONEY_SCALE} this gives a total precision of 12,
     * matching the persistence columns.
     */
    public static final int MONEY_INTEGER_DIGITS = 10;

    /**
     * Fixed stand-in emitted by {@link #toString()} in place of the whole value payload.
     *
     * <p>A constant rather than any transformation of the values, so nothing about them - not a
     * length, not a prefix, not a digest, not a partial mask - can be recovered from a stringified
     * instance.
     *
     * <p>Private because it is a rendering detail and not part of the response contract.
     */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    /**
     * Normalizes the field-error collection so that the component is never {@code null}, never
     * aliased to caller-owned state, and never mutable.
     *
     * <p>A {@code null} collection becomes the empty immutable list rather than being stored, so
     * every accessor and every serialized payload sees a usable collection - and so the
     * first-submission case, in which the legacy decoration gate had not fired and no field was
     * flagged, is expressible by passing nothing at all. A non-{@code null} collection is
     * defensively copied with {@link List#copyOf(java.util.Collection)}, which both detaches it
     * from the caller and rejects a {@code null} element: an entry with no state would be
     * meaningless, and silently dropping it would hide an error the client has to show.
     *
     * <p>Nothing else is normalized, and that is deliberate. Every other component is stored
     * exactly as supplied, including {@code null} and including any leading or trailing space,
     * because the legacy fields they derive from are fixed-width and space-significant: a padded
     * value that came back trimmed, or a message that came back re-cased, would be a parity
     * defect. No value is trimmed, padded, re-cased, truncated, scaled, rounded or reformatted
     * here.
     *
     * <p>The five monetary components are checked rather than normalized, for the reason given on
     * the type: a value whose scale is not {@link #MONEY_SCALE}, or whose integer part exceeds
     * {@link #MONEY_INTEGER_DIGITS} digits, does not describe the record field it stands for, and
     * correcting it here would mean making a rounding decision that belongs in exactly one place
     * elsewhere in the module. A {@code null} amount is accepted: the legacy screen leaves a
     * monetary field blank on a submission that never reached the record.
     *
     * @throws IllegalArgumentException if any monetary component carries a scale other than
     *         {@link #MONEY_SCALE} or needs more than {@link #MONEY_INTEGER_DIGITS} integer digits
     */
    public AccountUpdateResponse {
        requireRecordShape("creditLimit", creditLimit);
        requireRecordShape("cashCreditLimit", cashCreditLimit);
        requireRecordShape("currentBalance", currentBalance);
        requireRecordShape("currentCycleCredit", currentCycleCredit);
        requireRecordShape("currentCycleDebit", currentCycleDebit);
        fieldErrors = (fieldErrors == null) ? List.of() : List.copyOf(fieldErrors);
    }

    /**
     * Confirms that an amount has the decimal shape of the record field it represents.
     *
     * <p>Reads only the amount's own scale and precision; it performs no arithmetic on the value,
     * does not re-scale it, does not round it and does not format it. The failure text names the
     * component and the offending scale or digit count and never the amount itself, so a rejected
     * value cannot reach a log through the diagnostic that reports it.
     *
     * @param component the component name, for the failure text
     * @param amount    the amount to check, or {@code null} for a field the screen leaves blank
     * @throws IllegalArgumentException if the amount does not fit the record field
     */
    private static void requireRecordShape(final String component, final BigDecimal amount) {
        if (amount == null) {
            return;
        }
        if (amount.scale() != MONEY_SCALE) {
            throw new IllegalArgumentException(component + " must carry scale " + MONEY_SCALE
                    + ", because its record field stores two decimal places, but its scale is "
                    + amount.scale());
        }
        final int integerDigits = amount.precision() - amount.scale();
        if (integerDigits > MONEY_INTEGER_DIGITS) {
            throw new IllegalArgumentException(component + " must fit " + MONEY_INTEGER_DIGITS
                    + " integer digits, because that is the width of its record field, but it "
                    + "needs " + integerDigits);
        }
    }

    /**
     * Tests whether this response carries any per-field error.
     *
     * <p>A convenience test over {@link #fieldErrors()} for callers that only need to branch on
     * presence, such as a handler choosing a status code.
     *
     * <p><strong>It is not the error indicator, and it is not a substitute for reading the
     * entries.</strong> Three facts on this response are genuinely independent, and a caller has
     * to keep them apart:
     *
     * <ul>
     *   <li>{@link #error()} is the service's own verdict on the submission, supplied explicitly
     *       and never derived. A submission can be in error with no field decorated at all - the
     *       whole update-failure family of texts above arises that way - and on a first
     *       submission the legacy gate suppressed decoration entirely while still showing a
     *       summary line.</li>
     *   <li>{@link #errorMessage()} is the single summary line, subject to the legacy
     *       first-error-wins gate, so it describes one failing stage and not the set.</li>
     *   <li>{@link #fieldErrors()} is the set of independently flagged fields, and only
     *       {@link ErrorResponse.FieldError#state()} on each entry tells a caller whether to ask
     *       the operator to supply a value or to correct one.</li>
     * </ul>
     *
     * @return {@code true} when at least one field error is present
     */
    public boolean hasFieldErrors() {
        return !fieldErrors.isEmpty();
    }

    /**
     * Returns a diagnostic representation that discloses the response's control state and none of
     * its values.
     *
     * <p><strong>Why the implicit record rendering could not stand.</strong> A record's generated
     * rendering prints every component, and this response carries the same regulated payload as
     * the request it answers: the three social-security parts, the three date-of-birth parts, the
     * government-issued identifier, the transfer-account identifier, the three name parts, the
     * address block, the two telephone numbers, the credit score and the five monetary values, all
     * on one object. Any structured logger, framework diagnostic, failed assertion or string
     * interpolation touching an instance would have emitted the lot - and a response is if anything
     * more exposed than a request, because it is the object a controller hands to the serialization
     * layer and the one most likely to appear in a diagnostic on the way out.
     *
     * <p><strong>Why the identifiers go too.</strong> The account and customer identifiers look
     * like harmless correlation handles. They are not, on this object: they are the join keys to
     * the very record whose regulated fields travel beside them, so emitting them alongside a
     * partially redacted payload would still let a reader reassemble the subject from two log
     * lines. The correlation need is genuine and is met properly by the request-scoped trace
     * identifier the observability configuration attaches to every log event, rather than by
     * leaking a business key out of a response body.
     *
     * <p><strong>What is retained, and why it is safe.</strong> The five control components carry
     * no subject data by construction, and they are exactly what a reader needs in order to
     * understand an interaction: the explicit verdict, the single summary line, the focus hint, how
     * many fields were flagged, and the route offered next. The summary line is a fixed catalog
     * text composed onto a field <em>label</em> - never onto a field <em>value</em> - so it names
     * which box is wrong without saying what was typed into it. The count is rendered instead of
     * the entries so that the volume of a diagnostic cannot grow with the number of mistakes an
     * operator made. The echoed conversation state is delegated to its own renderer, which applies
     * the same protection to its own identifying members.
     *
     * <p><strong>The conversation token is withheld as well</strong>, and for a different reason
     * from the values. It discloses nothing about the records - that is what being an opaque
     * sealed digest pair means - but it is a capability: whoever holds it can present it on the
     * confirming turn. A diagnostic is the wrong place to leave one, and it is of no use to a
     * reader who cannot read it, so it is covered by the same placeholder rather than rendered.
     *
     * <p>{@code equals} and {@code hashCode} are deliberately left as the record contract
     * generates them. They compare every component by value, which is what a wire contract
     * requires, and neither emits anything: an in-memory comparison is not a disclosure surface.
     * Protection belongs on the rendering path alone.
     *
     * @return the type name, the control state and a fixed placeholder in place of every value
     */
    @Override
    public String toString() {
        return "AccountUpdateResponse[error=" + error
                + ", errorMessage=" + errorMessage
                + ", focusScreenFieldId=" + focusScreenFieldId
                + ", fieldErrorCount=" + fieldErrors.size()
                + ", nextRoute=" + nextRoute
                + ", navigationContext=" + navigationContext
                + ", values=" + REDACTION_PLACEHOLDER
                + "]";
    }
}
