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

import com.carddemo.domain.enums.UserType;
import java.util.Optional;

/**
 * The whole sixteen-field communication area as a type the service layer owns.
 *
 * <p>The legacy antecedent is {@code CARDDEMO-COMMAREA} in {@code app/cpy/COCOM01Y.cpy}, textually
 * included by all seventeen online programs. All sixteen fields are modelled here in declaration order,
 * because the ten screen services that carry this state reproduce their legacy programs paragraph for
 * paragraph and every one of those programs echoes the whole area across a pseudo-conversational turn.
 *
 * <p><strong>Why this type exists beside {@link ConversationState}.</strong> Two service-owned carriers
 * cover the same copybook at two different widths, and the difference is deliberate rather than
 * accidental. {@code ConversationState} models the four routing fields and the entry-mode flag and
 * nothing else, so a service that only needs to route cannot read an echoed identity even by accident;
 * the sign-on and menu families take that one. This type models all sixteen, for the services whose
 * legacy programs demonstrably carry all sixteen forward - they read the previous map and mapset to
 * decide where a cancel returns to, they carry the selected account, card and customer between the list
 * and detail screens, and they re-present the cardholder names they were given. Projecting those away
 * would change what the screens carry, and the migration's first constraint is that no observable
 * behaviour changes. {@link #toConversationState()} narrows this to the smaller carrier wherever a
 * routing decision is all that is needed, so a service still routes off the narrow view.
 *
 * <p><strong>What is echoed is still not evidence.</strong> Because the client echoes this state back,
 * none of it may be trusted for authorization. The user identifier and type it carries are reconciled
 * against the authenticated principal by {@link #agreesWith(String, UserType)} and
 * {@link #reconciledWith(String, UserType)}, and {@link #echoesAdministratorCode()} reports only what the
 * client claimed, never what it is entitled to. Every service that needs an entitlement takes the
 * authenticated identity as its own parameter, and every service that needs a customer, an account or a
 * card loads the record.
 *
 * <p><strong>Why this is not the wire record.</strong> The wire form of this state lives in the
 * {@code api.dto} package because it is a REST wire type with its own width bounds and its own
 * serialization contract, and the module's layering forbids a service from depending upward on the API
 * package. A service that took the wire record as a parameter, or returned one, inverted that direction.
 * The two are therefore separate types with identical component names, and one adapter in the API layer
 * converts between them, so the conversion happens exactly once, at the boundary, and every service
 * signature names only types the service layer owns. No width bound is declared here: bounding a
 * submitted value is the transport boundary's job, and restating a bound below it would put a second
 * answer where one already exists.
 *
 * <p>The legacy program-context flag is preserved as an explicit two-state value because the field-error
 * surface depends on it: the legacy validation macros fired only on re-entry, so {@link #reEntry()} is
 * what tells a service whether to decorate fields at all.
 *
 * <p>{@link #toString()} withholds the customer identifier and names, the account identifier and the card
 * number, because this state reaches diagnostics and none of those values may appear in a log. The
 * account status and the routing fields are not identifiers and are rendered as they stand.
 *
 * <p>Deeply immutable: every component is a {@code String} or an enumeration constant, every derivation
 * returns a new instance, and there is no setter and no lazily populated field, so an instance is safe
 * for unsynchronised concurrent use.
 *
 * @param fromTransactionId the transaction the conversation came from, or {@code null}
 * @param fromProgram the program the conversation came from, or {@code null}
 * @param toTransactionId the transaction the conversation nominates next, or {@code null}
 * @param toProgram the program the conversation nominates next, or {@code null}
 * @param userId the signed-on user identifier the client echoed, or {@code null}
 * @param userType the raw one-character user-type code the client echoed, or {@code null}
 * @param programContext whether this turn is a first entry or a re-entry, or {@code null}
 * @param customerId the selected customer identifier the client echoed, or {@code null}
 * @param customerFirstName the echoed customer first name, or {@code null}
 * @param customerMiddleName the echoed customer middle name, or {@code null}
 * @param customerLastName the echoed customer last name, or {@code null}
 * @param accountId the selected account identifier the client echoed, or {@code null}
 * @param accountStatus the echoed account status code, or {@code null}
 * @param cardNumber the selected card number the client echoed, or {@code null}
 * @param lastMap the previous map name, or {@code null}
 * @param lastMapset the previous mapset name, or {@code null}
 * @since 1.0.0
 */
public record ScreenNavigationState(
        String fromTransactionId,
        String fromProgram,
        String toTransactionId,
        String toProgram,
        String userId,
        String userType,
        ProgramContext programContext,
        String customerId,
        String customerFirstName,
        String customerMiddleName,
        String customerLastName,
        String accountId,
        String accountStatus,
        String cardNumber,
        String lastMap,
        String lastMapset) {

    /**
     * Fixed stand-in emitted by {@link #toString()} in place of each withheld component.
     *
     * <p>A constant rather than any transformation of the value, so nothing about a withheld component -
     * not its length, not a prefix or suffix, not a digest - survives into a stringified instance.
     */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    /** Shared all-null instance: the first turn of a conversation carries no prior state. */
    private static final ScreenNavigationState EMPTY = new ScreenNavigationState(
            null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null);

    /**
     * Returns the empty state, which is what an absent carry-over resolves to.
     *
     * @return the shared all-null instance
     */
    public static ScreenNavigationState empty() {
        return EMPTY;
    }

    /**
     * Resolves the echoed one-character type code onto the module's user-type enumeration.
     *
     * @return the resolved type, or an empty result when the code is absent or unrecognised
     */
    public Optional<UserType> resolvedUserType() {
        return UserType.fromCode(userType);
    }

    /**
     * Reports what the client claimed about its role, and nothing about what it is entitled to.
     *
     * @return {@code true} when the echoed code resolves to the administrator type
     */
    public boolean echoesAdministratorCode() {
        return resolvedUserType().map(UserType::isAdmin).orElse(false);
    }

    /**
     * Reports whether the echoed identity matches the authenticated one, comparing identifier and type
     * together so a mismatch in either is a disagreement.
     *
     * @param authenticatedUserId the identifier of the authenticated principal, possibly {@code null}
     * @param authenticatedUserType the type of the authenticated principal, possibly {@code null}
     * @return {@code true} only when both agree with the echoed values
     */
    public boolean agreesWith(final String authenticatedUserId,
            final UserType authenticatedUserType) {
        final boolean idAgrees = (authenticatedUserId == null)
                ? userId == null
                : authenticatedUserId.equals(userId);
        return idAgrees && resolvedUserType().orElse(null) == authenticatedUserType;
    }

    /**
     * Returns a copy whose identity fields are the authenticated ones, so a client-supplied identity can
     * never survive into the state a response carries back.
     *
     * <p>The role is written back as its declared one-character code so the reconciled instance still
     * carries a raw byte and still round-trips like any other, and an absent principal role clears the
     * byte rather than inventing one. The other fourteen components cross byte for byte, because
     * correcting identity is not licence to rewrite echoed navigation state.
     *
     * @param authenticatedUserId the identifier of the authenticated principal
     * @param authenticatedUserType the type of the authenticated principal, possibly {@code null}
     * @return a copy carrying the authenticated identity and every other field unchanged
     */
    public ScreenNavigationState reconciledWith(final String authenticatedUserId,
            final UserType authenticatedUserType) {
        return new ScreenNavigationState(
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

    /**
     * Reports whether this turn is the first entry into the screen.
     *
     * @return {@code true} when the program-context flag is anything other than re-enter
     */
    public boolean firstEntry() {
        return !reEntry();
    }

    /**
     * Reports whether this turn is a re-entry, which is the condition field decoration depends on.
     *
     * @return {@code true} when the program-context flag is set to re-enter
     */
    public boolean reEntry() {
        return programContext == ProgramContext.REENTER;
    }

    /**
     * Returns a copy standing at first entry, leaving every other field unchanged.
     *
     * @return a copy whose program context is {@link ProgramContext#ENTER}
     */
    public ScreenNavigationState withFirstEntry() {
        return withProgramContext(ProgramContext.ENTER);
    }

    /**
     * Returns a copy standing at re-entry, leaving every other field unchanged.
     *
     * @return a copy whose program context is {@link ProgramContext#REENTER}
     */
    public ScreenNavigationState withReEntry() {
        return withProgramContext(ProgramContext.REENTER);
    }

    /**
     * Narrows this state onto the routing carrier the navigation rules read.
     *
     * <p>Loss-free for its purpose: back-navigation and nominated-destination resolution consult the
     * originating and nominated program names and the entry mode and nothing else, so the projection
     * carries exactly those and drops the identity and selection members, which no routing rule reads.
     *
     * @return the five-field carried state, never {@code null}
     */
    public ConversationState toConversationState() {
        return new ConversationState(
                fromTransactionId,
                fromProgram,
                toTransactionId,
                toProgram,
                reEntry()
                        ? ConversationState.EntryMode.RE_ENTRY
                        : ConversationState.EntryMode.FIRST_ENTRY);
    }

    /**
     * Returns a copy carrying a different program context.
     *
     * @param context the context the copy stands at
     * @return a copy carrying that context and every other field unchanged
     */
    private ScreenNavigationState withProgramContext(final ProgramContext context) {
        return new ScreenNavigationState(
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

    /**
     * Returns a diagnostic representation that withholds every cardholder-bearing component.
     *
     * <p>A record's generated rendering prints every component, and five of these are a customer
     * identifier, three name parts, an account identifier and a card number. This state is carried on
     * every turn of every screen, so a default rendering would put cardholder data one interpolation away
     * from every log line and assertion message on the online path. Only the rendering changes: the
     * accessors, {@code equals} and {@code hashCode} continue to carry and compare every component.
     *
     * @return the navigation state, with the identifiers and names replaced by a fixed placeholder
     */
    @Override
    public String toString() {
        return "ScreenNavigationState["
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

    /**
     * The legacy program-context flag as two named states.
     *
     * <p>The antecedent is {@code CDEMO-PGM-CONTEXT} with its condition names
     * {@code CDEMO-PGM-ENTER} and {@code CDEMO-PGM-REENTER}. Two constants and no third, because the
     * legacy field is a single-digit numeric item that cannot be absent and every program tests only the
     * re-entry side of it.
     */
    public enum ProgramContext {

        /** The screen has not yet been presented on this conversation. */
        ENTER,

        /** The screen has been presented and is being submitted back. */
        REENTER
    }
}
