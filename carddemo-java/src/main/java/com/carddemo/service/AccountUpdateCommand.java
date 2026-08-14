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

import com.carddemo.domain.enums.KeyAction;

/**
 * One transmitted account-update screen, as a type the service layer owns.
 *
 * <p>The forty-three editable map items of {@code app/cpy-bms/COACTUP.CPY}, in the map's own declaration
 * order, followed by the three values that are not map items at all: the resolved attention key, the
 * echoed communication area, and the concurrency token that stands in for the before-image the legacy
 * program carried across its pseudo-conversational turn.
 *
 * <p><strong>Why this exists beside {@code api.dto.AccountUpdateRequest}.</strong> The module's layering
 * runs one way - the API layer may depend on the service layer, and nothing may depend upward - so a
 * service that named the wire record as a parameter inverted that direction. This is the same forty-six
 * components under the same names, with two differences and only two: every value arrives as a
 * {@code String} exactly as transmitted, because the ordered validation cascade of
 * {@code app/cbl/COACTUPC.cbl} is what reports a malformed one; and the echoed communication area arrives
 * as {@link ScreenNavigationState} rather than as the wire record. It carries no width bound and no
 * presence constraint, because the wire record already applied its own and the cascade applies the rest.
 * {@code api.AccountUpdateContractAdapter} is the only place the two are converted.
 *
 * <p>Component names are identical to the wire record's on purpose: a conversion that has to rename a
 * field is a conversion that can quietly map the wrong one, and there are forty-six chances to do so here.
 *
 * <p><strong>Two components are deliberately unconstrained, here and on the wire record alike.</strong>
 * {@code middleName} and {@code addressLine2} are decorated for error display by the legacy program but
 * never validated by it - the source comments at {@code app/cbl/COACTUPC.cbl:3346} and {@code :3370}
 * record that no edits are coded - so any value an operator can type is a value the legacy accepts.
 * Nothing in this module may narrow them.
 *
 * @param accountId the eleven-character account-identifier search item, as transmitted
 * @param accountStatus the one-character active-status item
 * @param openYear the four-character open-date year item
 * @param openMonth the two-character open-date month item
 * @param openDay the two-character open-date day item
 * @param creditLimit the fifteen-character credit-limit item, unparsed
 * @param expiryYear the four-character expiry-date year item
 * @param expiryMonth the two-character expiry-date month item
 * @param expiryDay the two-character expiry-date day item
 * @param cashCreditLimit the fifteen-character cash-credit-limit item, unparsed
 * @param reissueYear the four-character reissue-date year item
 * @param reissueMonth the two-character reissue-date month item
 * @param reissueDay the two-character reissue-date day item
 * @param currentBalance the fifteen-character current-balance item, unparsed
 * @param currentCycleCredit the fifteen-character cycle-credit item, unparsed
 * @param accountGroupId the ten-character account-group item
 * @param currentCycleDebit the fifteen-character cycle-debit item, unparsed
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
 * @param keyAction the resolved attention key, or {@code null} when the transmitted identifier resolved
 *     to none, which is a reachable state because the legacy key mapping declares no catch-all
 * @param navigationContext the echoed communication area, or {@code null} when the turn echoed none
 * @param concurrencyToken the echoed before-image proof, or {@code null} when the turn presents nothing
 *     to confirm
 * @param protectedValuesWithheld whether the regulated components were withheld from the caller when the
 *     screen this submission echoes was composed. Set by the boundary from the caller's own authority and
 *     never from anything the caller typed. It is what makes the withheld stand-in a <em>server-minted</em>
 *     sentinel rather than an ordinary value: on a turn where nothing was withheld, a submitted run of
 *     asterisks is exactly what it looks like and is edited accordingly, so an authorized operator's
 *     literal entry is never silently reinterpreted
 * @since 1.0.0
 */
public record AccountUpdateCommand(
        String accountId,
        String accountStatus,
        String openYear,
        String openMonth,
        String openDay,
        String creditLimit,
        String expiryYear,
        String expiryMonth,
        String expiryDay,
        String cashCreditLimit,
        String reissueYear,
        String reissueMonth,
        String reissueDay,
        String currentBalance,
        String currentCycleCredit,
        String accountGroupId,
        String currentCycleDebit,
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
        KeyAction keyAction,
        ScreenNavigationState navigationContext,
        String concurrencyToken,
        boolean protectedValuesWithheld) {

    /** Fixed stand-in this command's rendering uses in place of every value. */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    /**
     * Renders the command without any of its values.
     *
     * <p>Forty-three of the forty-six components are operator-typed account and customer values, four of
     * them regulated - the national identifier in three parts and the government-issued identifier - and
     * the concurrency token is integrity-protected. Rendering the whole record component by component
     * would put all of it on the diagnostic channel, so nothing is rendered at all. A per-component
     * rendering was rejected deliberately: it cannot stay correct as components are added, whereas a whole
     * placeholder cannot go wrong.
     *
     * <p>{@code equals} and {@code hashCode} are left as the record contract generates them. They compare
     * every component by value, which is what a parity comparison needs, and neither emits anything.
     *
     * @return the type name followed by a fixed placeholder, carrying no component value
     */
    @Override
    public String toString() {
        return "AccountUpdateCommand[" + REDACTION_PLACEHOLDER + "]";
    }
}
