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

import com.carddemo.domain.enums.UserType;
import jakarta.validation.constraints.Size;
import java.util.Optional;

/**
 * Immutable, client-echoed navigation state: the REST-era replacement for the CICS communication
 * area every online transaction carried across a pseudo-conversational turn. The antecedent is
 * {@code CARDDEMO-COMMAREA} in {@code app/cpy/COCOM01Y.cpy}, textually included by all seventeen
 * online programs; all sixteen of its fields are modelled here in declaration order, and each
 * {@code @Size} bound is the legacy field's own width.
 *
 * <p>Because the client echoes this state back, none of it may be trusted for authorization. The
 * user identifier and type it carries are reconciled against the authenticated principal - see
 * {@link #agreesWith(String, UserType)} and {@link #reconciledWith(String, UserType)} - and
 * {@link #echoesAdministratorCode()} reports only what the client claimed, never what it is
 * entitled to.
 *
 * <p>The legacy program-context flag is preserved as an explicit two-state value because the field
 * error surface depends on it: the legacy validation macros fired only on re-entry, so
 * {@link #reEntry()} is what tells a service whether to decorate fields at all.
 *
 * <p>{@link #toString()} redacts the customer identifier and names, the account identifier and the
 * card number, because this state reaches diagnostics and none of those values may appear in a log.
 * The account status and the routing fields are not identifiers and are rendered as they are.
 */
public record NavigationContext(
        @Size(max = NavigationContext.TRANSACTION_ID_LENGTH) String fromTransactionId,
        @Size(max = NavigationContext.PROGRAM_NAME_LENGTH) String fromProgram,
        @Size(max = NavigationContext.TRANSACTION_ID_LENGTH) String toTransactionId,
        @Size(max = NavigationContext.PROGRAM_NAME_LENGTH) String toProgram,
        @Size(max = NavigationContext.USER_ID_LENGTH) String userId,
        @Size(max = NavigationContext.USER_TYPE_LENGTH) String userType,
        ProgramContext programContext,
        @Size(max = NavigationContext.CUSTOMER_ID_LENGTH) String customerId,
        @Size(max = NavigationContext.CUSTOMER_NAME_LENGTH) String customerFirstName,
        @Size(max = NavigationContext.CUSTOMER_NAME_LENGTH) String customerMiddleName,
        @Size(max = NavigationContext.CUSTOMER_NAME_LENGTH) String customerLastName,
        @Size(max = NavigationContext.ACCOUNT_ID_LENGTH) String accountId,
        @Size(max = NavigationContext.ACCOUNT_STATUS_LENGTH) String accountStatus,
        @Size(max = NavigationContext.CARD_NUMBER_LENGTH) String cardNumber,
        @Size(max = NavigationContext.MAP_NAME_LENGTH) String lastMap,
        @Size(max = NavigationContext.MAP_NAME_LENGTH) String lastMapset) {
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    public static final int TRANSACTION_ID_LENGTH = 4;

    public static final int PROGRAM_NAME_LENGTH = 8;

    public static final int USER_ID_LENGTH = 8;

    public static final int USER_TYPE_LENGTH = 1;

    public static final int CUSTOMER_ID_LENGTH = 9;

    public static final int CUSTOMER_NAME_LENGTH = 25;

    public static final int ACCOUNT_ID_LENGTH = 11;

    public static final int ACCOUNT_STATUS_LENGTH = 1;

    public static final int CARD_NUMBER_LENGTH = 16;

    public static final int MAP_NAME_LENGTH = 7;

    /** Shared all-null instance: the first turn of a conversation carries no prior state. */
    private static final NavigationContext EMPTY = new NavigationContext(
            null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null);

    public static NavigationContext empty() {
        return EMPTY;
    }

    public Optional<UserType> resolvedUserType() {
        return UserType.fromCode(userType);
    }

    public boolean echoesAdministratorCode() {
        return resolvedUserType().map(UserType::isAdmin).orElse(false);
    }

    /**
     * Reports whether the echoed identity matches the authenticated one, comparing identifier and
     * type together so a mismatch in either is a disagreement.
     *
     * @param authenticatedUserId the identifier of the authenticated principal, possibly {@code null}
     * @param authenticatedUserType the type of the authenticated principal, possibly {@code null}
     * @return {@code true} only when both agree with the echoed values
     */
    public boolean agreesWith(String authenticatedUserId, UserType authenticatedUserType) {
        boolean idAgrees = (authenticatedUserId == null)
                ? userId == null
                : authenticatedUserId.equals(userId);
        return idAgrees && resolvedUserType().orElse(null) == authenticatedUserType;
    }

    /**
     * Returns a copy whose identity fields are the authenticated ones, so a client-supplied identity
     * can never survive into the state a response carries back.
     *
     * @param authenticatedUserId the identifier of the authenticated principal
     * @param authenticatedUserType the type of the authenticated principal, possibly {@code null}
     * @return a copy carrying the authenticated identity and every other field unchanged
     */
    public NavigationContext reconciledWith(String authenticatedUserId,
            UserType authenticatedUserType) {
        return new NavigationContext(
                fromTransactionId,
                fromProgram,
                toTransactionId,
                toProgram,
                authenticatedUserId,
                (authenticatedUserType == null) ? null : authenticatedUserType.getCode(),
                programContext,
                customerId,
                customerFirstName,
                customerMiddleName,
                customerLastName,
                accountId,
                accountStatus,
                cardNumber,
                lastMap,
                lastMapset);
    }

    public boolean firstEntry() {
        return !reEntry();
    }

    public boolean reEntry() {
        return programContext == ProgramContext.REENTER;
    }

    public NavigationContext withFirstEntry() {
        return withProgramContext(ProgramContext.ENTER);
    }

    public NavigationContext withReEntry() {
        return withProgramContext(ProgramContext.REENTER);
    }

    private NavigationContext withProgramContext(ProgramContext context) {
        return new NavigationContext(
                fromTransactionId,
                fromProgram,
                toTransactionId,
                toProgram,
                userId,
                userType,
                context,
                customerId,
                customerFirstName,
                customerMiddleName,
                customerLastName,
                accountId,
                accountStatus,
                cardNumber,
                lastMap,
                lastMapset);
    }

    @Override
    public String toString() {
        return "NavigationContext["
                + "fromTransactionId=" + fromTransactionId
                + ", fromProgram=" + fromProgram
                + ", toTransactionId=" + toTransactionId
                + ", toProgram=" + toProgram
                + ", userId=" + userId
                + ", userType=" + userType
                + ", programContext=" + programContext
                + ", customerId=" + REDACTION_PLACEHOLDER
                + ", customerFirstName=" + REDACTION_PLACEHOLDER
                + ", customerMiddleName=" + REDACTION_PLACEHOLDER
                + ", customerLastName=" + REDACTION_PLACEHOLDER
                + ", accountId=" + REDACTION_PLACEHOLDER
                + ", accountStatus=" + accountStatus
                + ", cardNumber=" + REDACTION_PLACEHOLDER
                + ", lastMap=" + lastMap
                + ", lastMapset=" + lastMapset
                + "]";
    }

    public enum ProgramContext {
        ENTER,

        REENTER
    }
}
