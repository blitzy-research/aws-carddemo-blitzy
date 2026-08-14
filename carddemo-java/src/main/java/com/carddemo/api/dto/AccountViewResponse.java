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
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Immutable, display-only account view response for legacy transaction {@code CAVW}: the thirty-seven
 * value items of symbolic map {@code app/cpy-bms/COACTVW.CPY} in map order, plus five response-only
 * components - the rejection flag, the ordered field-level detail behind it, the field to focus, the next
 * route and the echoed navigation state.
 *
 * <p><strong>Why the flag alone was not enough.</strong> The transaction keeps two filter states, not one,
 * and each of them holds three values rather than two: valid, not in order, and blank. The blank state is
 * how the screen says a filter was never supplied, and the not-in-order state is how it says one was
 * supplied and could not be used - a distinction the legacy makes visible with two different devices, a
 * marker beside a blank field and a colour change on an unusable one. Collapsing both into one boolean and
 * one message line loses which of the two filters was at fault and which of the two states it was in, so
 * the ordered detail is published alongside the flag, in the order the transaction established it.
 *
 * <p>Display-only, so it validates nothing and alters nothing on the way out. Identifiers are text
 * rather than numbers so leading zeros survive; the five monetary values are exact decimals at the
 * scale their picture clauses declare, never floating point; and dates remain text in the shape the
 * screen carried them.
 *
 * <p>Two map items are populated from a source their names do not suggest, and both are recorded so a
 * later reader does not assume a mapping that does not exist: the city item comes from the
 * <strong>third address line</strong>, there being no city field in the customer layout, and the
 * five-character postal-code item shows the leading five characters of a ten-character stored value.
 * Both projections happen in the service; this record neither performs nor conceals them.
 *
 * <p>Message widths follow the map rather than the program's working fields - forty-five characters
 * for the informational line and seventy-eight for the error line, where the working fields are forty
 * and seventy-five - because the map width is what is externally observable.
 *
 * <p>Routing is declarative: the response carries the next route rather than the server forwarding,
 * which is what replaces the legacy transfer-control dispatch.
 *
 * <p>Sensitive values are transported as the screen transported them and are never altered here;
 * redaction of what may be rendered or logged is applied where a value is rendered, and no diagnostic
 * detail reaches this payload.
 *
 * <p><strong>Four of the components are regulated, and this record is not what protects them.</strong>
 * The national identifier, the date of birth, the government-issued identifier and the
 * electronic-funds account identifier are gated by
 * {@code com.carddemo.api.AccountProtectedDataAdapter}, which reveals them only under an authorization
 * naming the account operation being served and either the administrator role or an established
 * ownership determination, and masks them otherwise. <em>That adapter is the only permitted source of
 * those four values.</em> The reason the rule sits there and not here is worth stating, because the
 * generated rendering below makes it easy to assume otherwise: this record's {@code toString} does
 * redact all four, but a redacting {@code toString} protects a log line and nothing else &mdash; Jackson
 * serialises the components, not the rendering, so a value placed here in the clear crosses to the
 * client in the clear however thoroughly the rendering hides it. Populating any of the four from an
 * entity accessor rather than from the adapter's result therefore defeats the protection completely,
 * which is why a production-source audit forbids any other reader of those stored values.
 *
 * <p>A source anomaly is recorded for traceability: the legacy program declares the same exit
 * paragraph label twice ({@code app/cbl/COACTVWC.cbl} L408 and L411). The two collapse to one method
 * in the service and the defect is entered in {@code docs/decision-log.md}; nothing in this record
 * changes because of it.
 */
@Schema(description = "PADDING RULE FOR EVERY FIXED-WIDTH TEXT FIELD ON THIS CONTRACT. Each value derived from a legacy record or map field is carried exactly as it was stored or composed: this contract never trims a value and never pads one. Trailing spaces are therefore part of the value wherever the underlying record holds them, and a client comparing values must not assume they have been stripped. Two consequences are visible and both are intentional. A field the legacy layout space-filled to its declared width arrives at that full width, so a comparison should trim before testing equality or compare on the declared width. A field whose stored value is shorter than the declared width arrives at its stored length rather than being padded out to the maximum the schema publishes, so maxLength states the width of the map field and not the length of the value. Where a value is bounded to a screen width, the bound truncates an over-long value and never pads a short one.")
public record AccountViewResponse(

        @Size(max = 4) String transactionName,

        @Size(max = 40) String title01,

        @Size(max = 8) String currentDate,

        @Size(max = 8) String programName,

        @Size(max = 40) String title02,

        @Size(max = 8) String currentTime,

        @Size(max = 11) String accountId,

        @Size(max = 1) String accountStatus,

        @Size(max = 10) String openDate,

        /* 10. ACRDLIMO, COACTVW.CPY line 302. Exact decimal, scale 2; the screen edit is absent by
         * design and no scaling happens on this record. Record counterpart ACCT-CREDIT-LIMIT at
         * CVACT01Y.cpy line 8. */
        BigDecimal creditLimit,

        @Size(max = 10) String expirationDate,

        /* 12. ACSHLIMO, COACTVW.CPY line 314. Exact decimal, scale 2. Record counterpart
         * ACCT-CASH-CREDIT-LIMIT at CVACT01Y.cpy line 9. */
        BigDecimal cashCreditLimit,

        @Size(max = 10) String reissueDate,

        /* 14. ACURBALO, COACTVW.CPY line 326. Exact decimal, scale 2; legitimately negative. Record
         * counterpart ACCT-CURR-BAL at CVACT01Y.cpy line 7. */
        BigDecimal currentBalance,

        /* 15. ACRCYCRO, COACTVW.CPY line 332. Exact decimal, scale 2. Record counterpart
         * ACCT-CURR-CYC-CREDIT at CVACT01Y.cpy line 13. */
        BigDecimal currentCycleCredit,

        @Size(max = 10) String accountGroupId,

        /* 17. ACRCYDBO, COACTVW.CPY line 344. Exact decimal, scale 2. Record counterpart
         * ACCT-CURR-CYC-DEBIT at CVACT01Y.cpy line 14. */
        BigDecimal currentCycleDebit,

        @Size(max = 9) String customerId,

        @Size(max = 12) String ssn,

        @Size(max = 10) String dateOfBirth,

        @Size(max = 3) String ficoScore,

        @Size(max = 25) String firstName,

        @Size(max = 25) String middleName,

        @Size(max = 25) String lastName,

        @Size(max = 50) String addressLine1,

        @Size(max = 2) String stateCode,

        @Size(max = 50) String addressLine2,

        @Size(max = 5) String zipCode,

        @Size(max = 50) String city,

        @Size(max = 3) String countryCode,

        @Size(max = 13) String phoneNumber1,

        @Size(max = 20) String governmentIssuedId,

        @Size(max = 13) String phoneNumber2,

        @Size(max = 10) String eftAccountId,

        @Size(max = 1) String primaryCardHolderIndicator,

        @Size(max = 45) String infoMessage,

        @Size(max = 78) String errorMessage,

        boolean inputError,

        /* The field-level detail behind the flag above, in the order the transaction established it:
         * the account filter first, then the customer filter. Never null; empty when the turn raised
         * nothing. Two states, not one - the legacy screen distinguishes a filter that was left blank
         * from one that was supplied and could not be used, and it distinguishes them with two
         * different devices, so a single flag cannot carry both. */
        List<ErrorResponse.FieldError> fieldErrors,

        @Size(max = 7) String focusScreenFieldId,

        String nextRoute,

        NavigationContext navigationContext) {
    public static final int MONEY_SCALE = 2;

    public static final int MONEY_INTEGER_DIGITS = 10;

    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    public AccountViewResponse {
        fieldErrors = (fieldErrors == null) ? List.of() : List.copyOf(fieldErrors);
        requireRecordShape("creditLimit", creditLimit);
        requireRecordShape("cashCreditLimit", cashCreditLimit);
        requireRecordShape("currentBalance", currentBalance);
        requireRecordShape("currentCycleCredit", currentCycleCredit);
        requireRecordShape("currentCycleDebit", currentCycleDebit);
    }

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

    public Optional<AccountStatus> resolvedAccountStatus() {
        return AccountStatus.fromCode(this.accountStatus);
    }

    public boolean active() {
        return AccountStatus.isActiveCode(this.accountStatus);
    }

    @Override
    public String toString() {
        return "AccountViewResponse["
                + "transactionName=" + transactionName
                + ", title01=" + title01
                + ", currentDate=" + currentDate
                + ", programName=" + programName
                + ", title02=" + title02
                + ", currentTime=" + currentTime
                + ", accountId=" + REDACTION_PLACEHOLDER
                + ", accountStatus=" + accountStatus
                + ", openDate=" + openDate
                + ", creditLimit=" + REDACTION_PLACEHOLDER
                + ", expirationDate=" + expirationDate
                + ", cashCreditLimit=" + REDACTION_PLACEHOLDER
                + ", reissueDate=" + reissueDate
                + ", currentBalance=" + REDACTION_PLACEHOLDER
                + ", currentCycleCredit=" + REDACTION_PLACEHOLDER
                + ", accountGroupId=" + accountGroupId
                + ", currentCycleDebit=" + REDACTION_PLACEHOLDER
                + ", customerId=" + REDACTION_PLACEHOLDER
                + ", ssn=" + REDACTION_PLACEHOLDER
                + ", dateOfBirth=" + REDACTION_PLACEHOLDER
                + ", ficoScore=" + REDACTION_PLACEHOLDER
                + ", firstName=" + REDACTION_PLACEHOLDER
                + ", middleName=" + REDACTION_PLACEHOLDER
                + ", lastName=" + REDACTION_PLACEHOLDER
                + ", addressLine1=" + REDACTION_PLACEHOLDER
                + ", stateCode=" + REDACTION_PLACEHOLDER
                + ", addressLine2=" + REDACTION_PLACEHOLDER
                + ", zipCode=" + REDACTION_PLACEHOLDER
                + ", city=" + REDACTION_PLACEHOLDER
                + ", countryCode=" + REDACTION_PLACEHOLDER
                + ", phoneNumber1=" + REDACTION_PLACEHOLDER
                + ", governmentIssuedId=" + REDACTION_PLACEHOLDER
                + ", phoneNumber2=" + REDACTION_PLACEHOLDER
                + ", eftAccountId=" + REDACTION_PLACEHOLDER
                + ", primaryCardHolderIndicator=" + primaryCardHolderIndicator
                + ", infoMessage=" + REDACTION_PLACEHOLDER
                + ", errorMessage=" + REDACTION_PLACEHOLDER
                + ", inputError=" + inputError
                + ", fieldErrors=" + fieldErrors
                + ", focusScreenFieldId=" + focusScreenFieldId
                + ", nextRoute=" + nextRoute
                + ", navigationContext=" + navigationContext
                + "]";
    }
}
