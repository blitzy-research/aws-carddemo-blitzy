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
package com.carddemo.api;

import com.carddemo.api.dto.NavigationContext;
import com.carddemo.domain.enums.UserType;
import com.carddemo.service.ConversationState;
import org.springframework.stereotype.Component;

/**
 * The single boundary at which the client-echoed navigation record and the service-tier conversation
 * state are converted into one another.
 *
 * <p>The wire record models all sixteen fields of {@code CARDDEMO-COMMAREA}
 * ({@code app/cpy/COCOM01Y.cpy}) because the communication area is an external contract that has to be
 * reproduced in full. The service-tier state models five of them, because the other eleven are the
 * identity and cardholder members and no service may make a decision out of a value the client chose.
 * This class is where those two facts are reconciled, and it is the only place either conversion
 * happens.
 *
 * <p><strong>Inbound: the eleven untrusted members are dropped, not passed along.</strong> Converting
 * a wire record to carried state keeps the four routing fields and the program-context flag and
 * discards the user identifier and type, the customer identifier and three name parts, the account
 * identifier and status, the primary account number and the two last-map fields. A service therefore
 * cannot read them even by accident: they are not present in the type it is handed. Where a service
 * needs to know who is acting it takes the authenticated identity as its own parameter, and where it
 * needs a customer, account or card it loads the record.
 *
 * <p><strong>Outbound: identity is replaced by the authenticated identity, never echoed.</strong>
 * Converting carried state back to a wire record merges the routing change the service made onto the
 * record the client sent, then reconciles the identity members against the authenticated principal
 * through {@link NavigationContext#reconciledWith(String, UserType)}. That is what stops a
 * client-supplied identity surviving a turn and coming back looking as though the server had asserted
 * it. The eleven members the service never saw are carried through from the inbound record unchanged,
 * so the round trip loses nothing from the client's point of view - the values simply stop being
 * something the server acts on.
 *
 * <p><strong>Why the mismatch check is available and separate.</strong>
 * {@link #echoedIdentityDisagrees(NavigationContext, String, UserType)} reports whether the echoed
 * identity differs from the authenticated one. It is deliberately a report and not a refusal: a
 * disagreement is not evidence of an attack, because a client may legitimately hold stale state after
 * a re-authentication, and the legacy screen has no message for it. Reconciliation already makes the
 * disagreement harmless, so this exists for diagnostics and for a caller that wants to log it.
 *
 * <p>Stateless and immutable, holding no field of any kind, so the singleton is safe for unsynchronised
 * concurrent use.
 *
 * @since 1.0.0
 */
@Component
public final class ConversationStateAdapter {

    /**
     * Converts the client-echoed navigation record into the state the service tier is entitled to
     * read.
     *
     * <p>An absent record becomes {@link ConversationState#empty()} rather than {@code null}, so a
     * service never has to null-check the state it is given, and the empty state is exactly what the
     * navigation authority treats as no carry-over at all.
     *
     * @param context the record the client echoed, which may be {@code null}
     * @return the five-field carried state, never {@code null}
     */
    public ConversationState toConversationState(final NavigationContext context) {
        if (context == null) {
            return ConversationState.empty();
        }
        return new ConversationState(
                context.fromTransactionId(),
                context.fromProgram(),
                context.toTransactionId(),
                context.toProgram(),
                entryModeOf(context));
    }

    /**
     * Merges the service's routing outcome back onto the echoed record and reconciles its identity
     * against the authenticated principal.
     *
     * <p>The four routing fields and the program-context flag are taken from the carried state, which
     * is where the service recorded its decision. Every other field is taken from the inbound record,
     * because the service never saw those and has nothing to say about them. The identity members are
     * then overwritten with the authenticated values, so what the response carries is what the server
     * knows rather than what the client claimed.
     *
     * @param inbound the record the client echoed, which may be {@code null}
     * @param state the carried state the service returned, which may be {@code null}
     * @param authenticatedUserId the identifier of the authenticated principal, which may be
     *        {@code null} when no principal is established
     * @param authenticatedUserType the type of the authenticated principal, which may be {@code null}
     * @return the record to publish on the response, never {@code null}
     */
    public NavigationContext toNavigationContext(final NavigationContext inbound,
                                                 final ConversationState state,
                                                 final String authenticatedUserId,
                                                 final UserType authenticatedUserType) {
        final NavigationContext carried = (inbound == null) ? NavigationContext.empty() : inbound;
        final ConversationState resolved = (state == null) ? ConversationState.empty() : state;
        final NavigationContext merged = new NavigationContext(
                resolved.fromTransactionId(),
                resolved.fromProgram(),
                resolved.toTransactionId(),
                resolved.toProgram(),
                carried.userId(),
                carried.userType(),
                programContextOf(resolved),
                carried.customerId(),
                carried.customerFirstName(),
                carried.customerMiddleName(),
                carried.customerLastName(),
                carried.accountId(),
                carried.accountStatus(),
                carried.cardNumber(),
                carried.lastMap(),
                carried.lastMapset());
        return merged.reconciledWith(authenticatedUserId, authenticatedUserType);
    }

    /**
     * Reports whether the identity the client echoed differs from the authenticated identity.
     *
     * <p>A report rather than a refusal, for the reason given on the type: a stale echo is not an
     * attack and the legacy screen has no message for it, and reconciliation has already made it
     * harmless. An absent record disagrees with any established principal, because an absent record
     * asserts no identity at all while a principal is one.
     *
     * @param context the record the client echoed, which may be {@code null}
     * @param authenticatedUserId the identifier of the authenticated principal, possibly {@code null}
     * @param authenticatedUserType the type of the authenticated principal, possibly {@code null}
     * @return {@code true} when the echoed identity does not match the authenticated one
     */
    public boolean echoedIdentityDisagrees(final NavigationContext context,
                                           final String authenticatedUserId,
                                           final UserType authenticatedUserType) {
        if (context == null) {
            return authenticatedUserId != null || authenticatedUserType != null;
        }
        return !context.agreesWith(authenticatedUserId, authenticatedUserType);
    }

    /**
     * Reads the program-context flag as the service-tier entry mode.
     *
     * @param context the record the client echoed, never {@code null}
     * @return the entry mode the flag stands for
     */
    private static ConversationState.EntryMode entryModeOf(final NavigationContext context) {
        return context.reEntry()
                ? ConversationState.EntryMode.RE_ENTRY
                : ConversationState.EntryMode.FIRST_ENTRY;
    }

    /**
     * Writes the service-tier entry mode back as the program-context flag.
     *
     * @param state the carried state the service returned, never {@code null}
     * @return the flag value the entry mode stands for
     */
    private static NavigationContext.ProgramContext programContextOf(final ConversationState state) {
        return state.reEntry()
                ? NavigationContext.ProgramContext.REENTER
                : NavigationContext.ProgramContext.ENTER;
    }
}
