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
package com.carddemo.service;

import com.carddemo.exception.ValidationException;
import java.math.BigDecimal;
import java.util.List;

/**
 * One settled account-update turn, as a type the service layer owns.
 *
 * <p>The six protected screen-metadata items of {@code app/cpy-bms/COACTUP.CPY}, then the forty-three
 * resulting values in the map's own declaration order, then the two message families, then the five
 * control components the screen has no field for, then the concurrency token. Fifty-seven components, in
 * the order the wire response declares them.
 *
 * <p><strong>Why this exists beside {@code api.dto.AccountUpdateResponse}.</strong> The module's layering
 * runs one way - the API layer may depend on the service layer, and nothing may depend upward - so a
 * service that returned the wire record inverted that direction. This is the same fifty-seven components
 * under the same names, with two differences and only two: the echoed communication area is
 * {@link ScreenNavigationState} rather than the wire record, and the per-field findings are
 * {@link ValidationException.FieldError} - the module's own field-error vocabulary, which the exception
 * layer owns and every other screen service already produces - rather than the transport contract's.
 * {@code api.AccountUpdateContractAdapter} is the only place the two are converted.
 *
 * <p><strong>Why the message texts are declared here as well as on the wire record.</strong> They are the
 * legacy program's own screen text, and this service is the only thing in the module that emits any of
 * them; the wire record declares them because they are part of its published contract, which a client and
 * its contract tests read. Neither declaration can reference the other without creating an edge the
 * layering forbids - {@code api.dto} may name nothing but {@code domain.enums} - so both declare, and
 * {@code CrossLayerConstantAgreementTest} asserts every pair is character-identical, which makes drift a
 * build failure rather than a latent parity defect. The same arrangement already governs the four shared
 * screen carriers and their wire twins.
 *
 * <p>The single summary message slot is exactly that: {@code app/cbl/COACTUPC.cbl} writes its error
 * message once in four thousand two hundred and thirty-six lines, so one text accompanies however many
 * field findings the cascade produced. {@link #error()} is an explicit fact the turn recorded and is never
 * derived from either the message or the findings.
 *
 * @param transactionName the four-character transaction-name item
 * @param title01 the forty-character first title item
 * @param currentDate the eight-character current-date item, month-day-year
 * @param programName the eight-character program-name item
 * @param title02 the forty-character second title item
 * @param currentTime the eight-character current-time item, hour-minute-second
 * @param accountId the eleven-character account-identifier item
 * @param accountStatus the one-character active-status item
 * @param openYear the four-character open-date year item
 * @param openMonth the two-character open-date month item
 * @param openDay the two-character open-date day item
 * @param creditLimit the credit limit at the record field's own scale, or {@code null} when blank
 * @param expiryYear the four-character expiry-date year item
 * @param expiryMonth the two-character expiry-date month item
 * @param expiryDay the two-character expiry-date day item
 * @param cashCreditLimit the cash credit limit at the record field's own scale, or {@code null}
 * @param reissueYear the four-character reissue-date year item
 * @param reissueMonth the two-character reissue-date month item
 * @param reissueDay the two-character reissue-date day item
 * @param currentBalance the current balance at the record field's own scale, or {@code null}
 * @param currentCycleCredit the cycle credit at the record field's own scale, or {@code null}
 * @param accountGroupId the ten-character account-group item
 * @param currentCycleDebit the cycle debit at the record field's own scale, or {@code null}
 * @param customerId the nine-character customer-identifier item
 * @param ssnPart1 the three-character first part of the national identifier
 * @param ssnPart2 the two-character second part of the national identifier
 * @param ssnPart3 the four-character third part of the national identifier
 * @param dateOfBirthYear the four-character date-of-birth year item
 * @param dateOfBirthMonth the two-character date-of-birth month item
 * @param dateOfBirthDay the two-character date-of-birth day item
 * @param ficoScore the three-character credit-score item
 * @param firstName the twenty-five-character first-name item
 * @param middleName the twenty-five-character middle-name item, which the legacy never validates
 * @param lastName the twenty-five-character last-name item
 * @param addressLine1 the fifty-character first address line
 * @param stateCode the two-character state item
 * @param addressLine2 the fifty-character second address line, which the legacy never validates
 * @param zipCode the five-character postal-code item
 * @param city the fifty-character city item
 * @param countryCode the three-character country item
 * @param phone1AreaCode the three-character first-telephone area code
 * @param phone1Prefix the three-character first-telephone prefix
 * @param phone1LineNumber the four-character first-telephone line number
 * @param governmentIssuedId the twenty-character government-issued identifier item
 * @param phone2AreaCode the three-character second-telephone area code
 * @param phone2Prefix the three-character second-telephone prefix
 * @param phone2LineNumber the four-character second-telephone line number
 * @param eftAccountId the ten-character transfer-account identifier item
 * @param primaryCardHolderIndicator the one-character primary-cardholder item
 * @param infoMessage the forty-five-character guidance item, which is not an error
 * @param errorMessage the seventy-eight-character single summary slot
 * @param error the turn's own error switch, recorded rather than derived
 * @param focusScreenFieldId the identity of the field the cursor returns to, at most seven characters
 * @param nextRoute the declarative next route, whose vocabulary the navigation service owns
 * @param navigationContext the communication area as the turn leaves it
 * @param fieldErrors the independent per-field findings, in the order the cascade reported them;
 *     normalised to an unmodifiable list, empty when the turn flagged no field
 * @param concurrencyToken the before-image proof to echo, or {@code null} when the turn presents nothing
 *     to confirm
 * @since 1.0.0
 */
public record AccountUpdateOutcome(
        String transactionName,
        String title01,
        String currentDate,
        String programName,
        String title02,
        String currentTime,
        String accountId,
        String accountStatus,
        String openYear,
        String openMonth,
        String openDay,
        BigDecimal creditLimit,
        String expiryYear,
        String expiryMonth,
        String expiryDay,
        BigDecimal cashCreditLimit,
        String reissueYear,
        String reissueMonth,
        String reissueDay,
        BigDecimal currentBalance,
        BigDecimal currentCycleCredit,
        String accountGroupId,
        BigDecimal currentCycleDebit,
        String customerId,
        String ssnPart1,
        String ssnPart2,
        String ssnPart3,
        String dateOfBirthYear,
        String dateOfBirthMonth,
        String dateOfBirthDay,
        String ficoScore,
        String firstName,
        String middleName,
        String lastName,
        String addressLine1,
        String stateCode,
        String addressLine2,
        String zipCode,
        String city,
        String countryCode,
        String phone1AreaCode,
        String phone1Prefix,
        String phone1LineNumber,
        String governmentIssuedId,
        String phone2AreaCode,
        String phone2Prefix,
        String phone2LineNumber,
        String eftAccountId,
        String primaryCardHolderIndicator,
        String infoMessage,
        String errorMessage,
        boolean error,
        String focusScreenFieldId,
        String nextRoute,
        ScreenNavigationState navigationContext,
        List<ValidationException.FieldError> fieldErrors,
        String concurrencyToken) {

    /** Credit-score range failure, from {@code app/cbl/COACTUPC.cbl} line 2452. */
    public static final String SUFFIX_FICO_OUT_OF_RANGE = ": should be between 300 and 850";

    /** State-code membership failure, from {@code app/cbl/COACTUPC.cbl} line 2503. */
    public static final String SUFFIX_STATE_NOT_VALID = ": is not a valid state code";

    /** Telephone area code absent, from {@code app/cbl/COACTUPC.cbl} line 2254. */
    public static final String SUFFIX_AREA_CODE_REQUIRED = ": Area code must be supplied.";

    /** Telephone area code not three digits, from {@code app/cbl/COACTUPC.cbl} line 2272. */
    public static final String SUFFIX_AREA_CODE_NOT_3_DIGITS =
            ": Area code must be A 3 digit number.";

    /** Telephone area code zero, from {@code app/cbl/COACTUPC.cbl} line 2289. */
    public static final String SUFFIX_AREA_CODE_ZERO = ": Area code cannot be zero";

    /**
     * Telephone area code outside the general-purpose set, from {@code app/cbl/COACTUPC.cbl} line 2306.
     */
    public static final String SUFFIX_AREA_CODE_NOT_GENERAL_PURPOSE =
            ": Not valid North America general purpose area code";

    /** Telephone prefix absent, from {@code app/cbl/COACTUPC.cbl} line 2325. */
    public static final String SUFFIX_PREFIX_REQUIRED = ": Prefix code must be supplied.";

    /** Telephone prefix not three digits, from {@code app/cbl/COACTUPC.cbl} line 2343. */
    public static final String SUFFIX_PREFIX_NOT_3_DIGITS =
            ": Prefix code must be A 3 digit number.";

    /** Telephone prefix zero, from {@code app/cbl/COACTUPC.cbl} line 2360. */
    public static final String SUFFIX_PREFIX_ZERO = ": Prefix code cannot be zero";

    /** Telephone line number absent, from {@code app/cbl/COACTUPC.cbl} line 2378. */
    public static final String SUFFIX_LINE_NUMBER_REQUIRED =
            ": Line number code must be supplied.";

    /** Telephone line number not four digits, from {@code app/cbl/COACTUPC.cbl} line 2396. */
    public static final String SUFFIX_LINE_NUMBER_NOT_4_DIGITS =
            ": Line number code must be A 4 digit number.";

    /** Telephone line number zero, from {@code app/cbl/COACTUPC.cbl} line 2413. */
    public static final String SUFFIX_LINE_NUMBER_ZERO = ": Line number code cannot be zero";

    /**
     * Combined state-and-postal-code failure, from {@code app/cbl/COACTUPC.cbl} line 2550.
     *
     * <p>A whole message rather than a suffix, because the legacy emits it without a field-name prefix
     * even though the same failure flags two fields.
     */
    public static final String MSG_INVALID_ZIP_FOR_STATE = "Invalid zip code for state";

    /**
     * The scale every monetary component carries, from the two decimal places of the five
     * {@code PIC S9(10)V99} fields of {@code app/cpy/CVACT01Y.cpy}.
     */
    public static final int MONEY_SCALE = 2;

    /**
     * The number of integer digits every monetary component may carry, from the ten integer digits of
     * the same five record fields.
     */
    public static final int MONEY_INTEGER_DIGITS = 10;

    /** Fixed stand-in this outcome's rendering uses in place of the whole value payload. */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    /**
     * Normalises the field findings and confirms the decimal shape of every monetary component.
     *
     * <p>The findings become an unmodifiable copy, and an absent list becomes an empty one, so a caller
     * never has to distinguish "no findings" from "no list". The five monetary components are checked
     * rather than corrected: a value whose scale is not {@link #MONEY_SCALE}, or whose integer part needs
     * more than {@link #MONEY_INTEGER_DIGITS} digits, does not describe the record field it stands for,
     * and adjusting it here would be a rounding decision, which belongs in exactly one place elsewhere in
     * the module. A {@code null} amount is accepted, because the legacy screen leaves a monetary field
     * blank on a submission that never reached the record.
     *
     * @throws IllegalArgumentException if any monetary component carries a scale other than
     *     {@link #MONEY_SCALE} or needs more than {@link #MONEY_INTEGER_DIGITS} integer digits
     * @throws NullPointerException if the finding list holds a {@code null} element
     */
    public AccountUpdateOutcome {
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
     * <p>Reads the amount's own scale and precision only. It performs no arithmetic, does not re-scale,
     * does not round and does not format. The failure text names the component and the offending scale or
     * digit count and never the amount, so a rejected value cannot reach a log through its own diagnostic.
     *
     * @param component the component name, for the failure text
     * @param amount the amount to check, or {@code null} for a field the screen leaves blank
     * @throws IllegalArgumentException if the amount does not fit the record field
     */
    private static void requireRecordShape(final String component, final BigDecimal amount) {
        if (amount == null) {
            return;
        }
        if (amount.scale() != MONEY_SCALE) {
            throw new IllegalArgumentException(component + " must carry scale " + MONEY_SCALE
                    + " to describe its record field, but carries scale " + amount.scale());
        }
        if (amount.precision() - amount.scale() > MONEY_INTEGER_DIGITS) {
            throw new IllegalArgumentException(component + " may carry at most "
                    + MONEY_INTEGER_DIGITS + " integer digits to fit its record field, but needs "
                    + (amount.precision() - amount.scale()));
        }
    }

    /**
     * Reports whether the turn flagged at least one field.
     *
     * <p>Separate from {@link #error()} on purpose. The error switch is the turn's own summary state and
     * is set on paths that flag no individual field at all, so neither answer implies the other.
     *
     * @return {@code true} when at least one field finding is present
     */
    public boolean hasFieldErrors() {
        return !fieldErrors.isEmpty();
    }

    /**
     * Renders the outcome's control state and none of its values.
     *
     * <p>Forty-nine of the fifty-seven components are account and customer values, four of them regulated,
     * and the concurrency token is integrity-protected, so only the control state is rendered. The finding
     * count is rendered rather than the findings themselves, because a finding names a field and quotes a
     * message and the message can carry a value the operator typed.
     *
     * <p>{@code equals} and {@code hashCode} are left as the record contract generates them: they compare
     * every component by value, which is what a parity comparison needs, and neither emits anything.
     *
     * @return the type name, the control state, and a fixed placeholder in place of every value
     */
    @Override
    public String toString() {
        return "AccountUpdateOutcome[error=" + error
                + ", errorMessage=" + errorMessage
                + ", focusScreenFieldId=" + focusScreenFieldId
                + ", fieldErrorCount=" + fieldErrors.size()
                + ", nextRoute=" + nextRoute
                + ", navigationContext=" + navigationContext
                + ", values=" + REDACTION_PLACEHOLDER
                + "]";
    }
}
