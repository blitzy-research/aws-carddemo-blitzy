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

import jakarta.validation.constraints.Size;

/**
 * Immutable account-update request contract for legacy CICS transaction {@code CAUP}, derived from
 * symbolic map {@code app/cpy-bms/COACTUP.CPY}, mapset {@code app/bms/COACTUP.bms} and program
 * {@code app/cbl/COACTUPC.cbl}, the largest single translation in the estate. The persisted types come
 * from the account and customer record layouts at {@code app/cpy/CVACT01Y.cpy} and
 * {@code app/cpy/CVCUS01Y.cpy}.
 *
 * <p>Of the map's input families, eleven are protected and therefore absent here - screen metadata,
 * the two message items and the function-key legends, which belong on the response contract or nowhere
 * - leaving <strong>43</strong> carried map components. The error-decoration macro
 * {@code app/cpy/CSSETATY.cpy} is expanded 39 times, so 39 of the 43 are decoration targets and
 * exactly four are editable but never decorated: the account id, the account group id, the customer id
 * and the government-issued id. Component order follows map declaration order because that is the
 * reproducible authority; JSON binding is by name, so nothing downstream depends on it.
 *
 * <p><strong>Split fields stay split.</strong> Four dates - account open, expiry, reissue and customer
 * date of birth - contribute twelve year, month and day components; the social-security number
 * contributes three; and two telephone numbers contribute six. The reissue date is easy to overlook
 * because the screen is often described as carrying three dates, but the map declares it and the
 * program stages it. Merging any of these would destroy the field-level error contract, because each
 * sub-field owns its own validation flag and its own decoration site. Nothing here is parsed, converted
 * or assembled: no date is interpreted and no telephone number is formatted. The persisted telephone
 * form is a parenthesised area code followed by prefix and line number - thirteen characters of content
 * in a fifteen-character field, completed by two trailing spaces - and that assembly, like date
 * interpretation, belongs to the service layer.
 *
 * <p><strong>The five monetary components</strong> - credit limit, cash credit limit, current balance,
 * current cycle credit and current cycle debit - are carried as raw fifteen-character screen lexemes
 * and not as decoded numbers, exactly as the legacy alphanumeric work fields stage them. The reason is
 * the three-state outcome of the legacy numeric edit: it distinguishes a field that was <em>not
 * supplied</em> - blank, all spaces, or carrying the marker character the previous turn wrote back -
 * from one that was <em>supplied but unparseable</em>, and in the second case it keeps the operator's
 * own keystrokes so the screen can redisplay and decorate them. Only a successful numeric test
 * populates the signed two-decimal view. A decoded numeric component cannot represent the middle state
 * at all: an unparseable lexeme would fail body binding before any component was populated, which
 * would replace one ordered summary message plus N decorated fields with a single opaque body-read
 * rejection and lose every other field's error with it. Carrying the lexeme keeps {@code MISSING} and
 * {@code INVALID} independently reachable for these five fields.
 *
 * <p>Their record counterparts remain signed zoned decimals with ten integer digits and two decimal
 * places, and the columns remain numeric with precision 12 and scale 2. Decoding a lexeme to that form
 * is the service's work: it reproduces the legacy three-state edit through
 * {@code com.carddemo.util.CobolStringUtils} and converts through the one sanctioned truncation point,
 * {@code com.carddemo.util.ZonedDecimalCodec} (decision log entries D-02, DL-078 and DL-079).
 * Truncation rather than rounding is required because the estate carries no rounding clause on any
 * arithmetic statement, so every legacy store into a two-decimal field truncates toward zero. This file
 * performs no arithmetic, rounding, scaling, negation, parsing or formatting.
 *
 * <p><strong>The concurrency token, and why one component is not a map field.</strong> The
 * forty-fourth component is a concurrency token, present because the legacy transaction carried state
 * across its turns that the map never showed. The program appended the complete old image of the
 * account and customer records - as they stood when the screen was presented - to the shared
 * communication area, returned it with the screen, sliced it back off on the following turn, and on
 * confirmation compared the freshly locked records field by field against that image, abandoning the
 * write on any single difference. Re-reading the records at the start of the update turn would not do:
 * the point of the comparison is to detect a change made <em>after</em> the screen was displayed, so
 * the compared state has to have travelled with the conversation. In the legacy that state was safe
 * because the communication area is held by the transaction manager and the terminal never sees it;
 * echoed to a client it is no longer safe, so the token is opaque and integrity-protected rather than a
 * readable version number - a client can return it and cannot forge, edit or fabricate one. Nothing
 * about the records can be read out of it, and it is neither a map field, a screen field nor a
 * decoration target. Decision log entry DL-074 records why it is a sealed digest pair.
 *
 * <p><strong>Why this request tolerates bad input.</strong> The program runs a first-error-wins
 * validation cascade: every edit stage is gated on the summary-message slot still being empty, so a
 * submission with five bad fields yields <em>one</em> summary message - that of the first failing stage
 * in source order - together with <em>N</em> independently set field flags that drive decoration. Bean
 * Validation evaluates constraints in an unspecified order and would produce a different message set
 * for the same input. The ordered cascade therefore belongs to the service layer, and this request
 * deliberately <strong>accepts null, blank and out-of-range values without rejecting them</strong>. The
 * only declarative constraint used is {@code @Size(max = n)} at each component's measured map width,
 * which restates the physical width of the 3270 field rather than any business rule and neither trims a
 * value nor disturbs leading or trailing spaces. No other constraint annotation appears in this file.
 *
 * <p>Two components - the middle name and the second address line - are decorated for error display but
 * never effectively validated, and therefore carry <strong>no annotation at all</strong>. The middle
 * name is put through the <em>optional</em> alphabetic stage, which accepts blank values and embedded
 * spaces, so no declarative constraint can express it while preserving cascade order; the second address
 * line's validation flag is consumed by its decoration yet never assigned anywhere in the program, and
 * the statement that would set its error label is commented out as optional, so the decoration can never
 * fire and the field accepts any value. Attaching any constraint to either, even a width constraint,
 * would reject input the legacy system accepts. Decision log entry D-34 records the decision.
 *
 * <p>The credit score is carried as a string so that a value such as {@code 001} survives intact. Its
 * legacy range test is an inclusive 300-to-850 bound that fires only after the required-numeric stage
 * has passed. Because that gating is part of the ordered cascade, the bound is <strong>documented here
 * and enforced by the service</strong>; annotating it would hoist the check out of the cascade and
 * change which message is produced. It is a request-side rule only: the persistence layer carries no
 * such constraint, and seeded customers legitimately score below the lower bound.
 *
 * <p><strong>Character-class semantics.</strong> The legacy alphabetic check blanks every letter in the
 * field and then tests whether anything remains, so <strong>embedded spaces pass</strong> and a value
 * such as {@code MARY ANN} is valid. No letters-only pattern may be attached to any name component; the
 * faithful predicate - every character is a letter or a space - lives in
 * {@code com.carddemo.util.CobolStringUtils} (decision log entry D-17). The state check is a flat
 * membership test performing no trim, no numeric check and no blank pre-check, so no pattern or
 * minimum-length constraint may be attached to the state component either. A failing state-and-postal
 * code combination check sets both the state flag and the postal-code flag, which is why the field error
 * contract is a per-field collection rather than a single error (decision log entry D-33).
 *
 * <p>Three adjacent source comments in the decoration block are unreliable and are recorded in the
 * anomaly register: two are transposed against the expansions they describe and one is mislabelled. The
 * macro's substitution token governs in every case, never the neighbouring comment.
 *
 * <p>This request is a {@code record}: immutable, constructed in one step, with no code generator or
 * annotation processor involved. It depends only on the platform library and the validation API, and
 * holds no logging, no input or output and no business logic. It carries an unmasked social-security
 * number in three parts and an unmasked government-issued id because the legacy screen does; both are
 * transported here and must not be written to a log or persisted from here. Decision log entry D-13
 * records that both values are sealed at rest by the customer entity, so an inbound screen contract such
 * as this one carries them unsealed on the wire only.
 *
 * @param accountId the account id, used as the search key.
 * @param creditLimit the credit limit as the raw fifteen-character screen lexeme, and the pattern for
 *        the other four monetary components: two decimal places once the service decodes it, with one
 *        service message for the not-supplied state and a different one for the
 *        supplied-but-unparseable state, which is why the lexeme rather than a decoded number is
 *        carried.
 * @param ssnPart1 the first part of the social-security number; it and the other two parts are
 *        transported unmasked.
 * @param ficoScore the customer credit score, carried as a string so that {@code 001} is not reduced to
 *        {@code 1}. The inclusive 300-to-850 bound is documented, not annotated; see above.
 * @param middleName the customer middle name. <strong>Carries no annotation of any kind,
 *        deliberately</strong> - see above.
 * @param stateCode the customer state code, checked by flat membership and, jointly with the postal
 *        code, against the state-and-postal combination table; both checks live in the service.
 * @param addressLine2 the customer second address line. <strong>Carries no annotation of any kind,
 *        deliberately</strong>: this field accepts any value - see above.
 * @param zipCode the customer postal code, five characters on the screen and persisted into a
 *        ten-character record field.
 * @param city the customer city, persisted into the customer record's third address line.
 * @param governmentIssuedId the customer government-issued id, transported unmasked.
 * @param eftAccountId the customer electronic-funds-transfer account id. Its decoration site is one of
 *        the two whose adjacent source comment is transposed; the substitution token governs.
 * @param primaryCardHolderIndicator the customer primary-card-holder indicator, restricted to yes or no
 *        by the service. Its decoration site is emitted before the transfer-account id, inverting map
 *        declaration order, and its adjacent source comment is transposed.
 * @param concurrencyToken the opaque, integrity-protected description of the account and customer
 *        records as they stood when this screen was presented, minted and verified by
 *        {@code com.carddemo.service.AccountConcurrencyTokenService} and returned here unchanged by the
 *        client. Not a map field. Absent or altered is a conflict the service reports, not a binding
 *        failure, so no constraint is attached.
 */
public record AccountUpdateRequest(

        @Size(max = 11) String accountId,

        @Size(max = 1) String accountStatus,

        @Size(max = 4) String openYear,

        @Size(max = 2) String openMonth,

        @Size(max = 2) String openDay,

        @Size(max = 15) String creditLimit,

        @Size(max = 4) String expiryYear,

        @Size(max = 2) String expiryMonth,

        @Size(max = 2) String expiryDay,

        @Size(max = 15) String cashCreditLimit,

        @Size(max = 4) String reissueYear,

        @Size(max = 2) String reissueMonth,

        @Size(max = 2) String reissueDay,

        @Size(max = 15) String currentBalance,

        @Size(max = 15) String currentCycleCredit,

        @Size(max = 10) String accountGroupId,

        @Size(max = 15) String currentCycleDebit,

        @Size(max = 9) String customerId,

        @Size(max = 3) String ssnPart1,

        @Size(max = 2) String ssnPart2,

        @Size(max = 4) String ssnPart3,

        @Size(max = 4) String dateOfBirthYear,

        @Size(max = 2) String dateOfBirthMonth,

        @Size(max = 2) String dateOfBirthDay,

        @Size(max = 3) String ficoScore,

        @Size(max = 25) String firstName,

        /* INTENTIONALLY UNANNOTATED - DO NOT ADD ANY CONSTRAINT, NOT EVEN @Size. See the type
         * documentation and decision log entry D-34. */
        String middleName,

        @Size(max = 25) String lastName,

        @Size(max = 50) String addressLine1,

        @Size(max = 2) String stateCode,

        /* INTENTIONALLY UNANNOTATED - DO NOT ADD ANY CONSTRAINT, NOT EVEN @Size. This field accepts
         * ANY value; see the type documentation and decision log entry D-34. */
        String addressLine2,

        @Size(max = 5) String zipCode,

        @Size(max = 50) String city,

        @Size(max = 3) String countryCode,

        @Size(max = 3) String phone1AreaCode,

        @Size(max = 3) String phone1Prefix,

        @Size(max = 4) String phone1LineNumber,

        @Size(max = 20) String governmentIssuedId,

        @Size(max = 3) String phone2AreaCode,

        @Size(max = 3) String phone2Prefix,

        @Size(max = 4) String phone2LineNumber,

        @Size(max = 10) String eftAccountId,

        @Size(max = 1) String primaryCardHolderIndicator,

        /* Not a map field: the echoed counterpart of the state the program carried across the
         * pseudo-conversational turn, described on the type above. Opaque and unbounded by design, and
         * deliberately unannotated - its absence is a conflict for the service to report, not a binding
         * failure for the framework to reject. */
        String concurrencyToken) {

    /**
     * Fixed stand-in emitted by {@link #toString()} in place of the whole component set. A constant
     * rather than any transformation of the values, so nothing about them - not a length, not a prefix,
     * not a digest, not a partial mask - can be recovered from a stringified instance.
     */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    /**
     * Returns a diagnostic representation that names the type and discloses none of its values.
     *
     * <p>A record's generated {@code toString()} prints every component, and every one of the
     * forty-three map components here is regulated personal data, a regulated financial value, or a key
     * that joins directly to both - the social-security and date-of-birth parts, the government-issued
     * and transfer-account identifiers, the name parts, the address block, the two telephone numbers,
     * the credit score and the five monetary values all sit on one object.</p>
     *
     * <p><strong>Nothing at all is retained, not even the identifiers.</strong> The account and customer
     * identifiers look like harmless correlation handles, and in isolation they nearly are; on this type
     * they are the join keys to the very record whose regulated fields travel beside them, so emitting
     * them alongside a partially redacted payload would still let a reader reassemble the subject from
     * two lines. The correlation need is met properly by the request-scoped trace identifier the
     * observability configuration attaches to every log event, rather than by carrying a business key
     * out of a request body.</p>
     *
     * <p>{@code equals} and {@code hashCode} are deliberately left as the record contract generates
     * them: they compare every component by value, which is what a request contract requires, and
     * neither emits anything. Redaction belongs on the rendering path alone.</p>
     *
     * @return the type name followed by a fixed placeholder, carrying no component value
     */
    @Override
    public String toString() {
        return "AccountUpdateRequest[" + REDACTION_PLACEHOLDER + "]";
    }
}
