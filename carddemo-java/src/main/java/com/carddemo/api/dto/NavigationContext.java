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
 * Immutable, client-echoed navigation state &mdash; the REST-era replacement for the CICS
 * communication area that every online CardDemo transaction carried across a pseudo-conversational
 * turn. The legacy antecedent is {@code CARDDEMO-COMMAREA} in {@code app/cpy/COCOM01Y.cpy},
 * textually included by all seventeen online programs; all sixteen of its fields are modelled here
 * in declaration order.
 *
 * <p>This is echoed request state, not a server session: the server returns it in a response body,
 * the client holds it and sends it back on the next call, so no servlet session, session attribute,
 * server-side cache or sticky routing stands behind it. An instance is a value &mdash; never stored,
 * never mutated &mdash; and the derivation methods return new instances. The four routing components
 * are declarative data only: this type declares no route table, no route constant and no dispatch
 * method, because route selection belongs to the service layer.
 *
 * <p>The customer identifier, account identifier and card number are declared numeric in the
 * copybook and carried here as {@code String}, because all three have contractual leading zeros and
 * a fixed external width that the byte-equivalence criterion compares directly: {@code "000000042"}
 * must never round-trip as {@code 42}. They are the genuine business keys, never surrogates.
 *
 * <p>Nothing is validated, defaulted or normalised. Every component may legitimately be
 * {@code null}, a wholly empty area is a real state that routes to sign-on, and values cross this
 * boundary byte for byte &mdash; never trimmed, padded, case-folded or re-formatted &mdash; because
 * legacy space padding is contract. The only constraint is a maximum length, which measures and
 * never alters. No presence, pattern, character-class or numeric range constraint appears anywhere:
 * each would reject input the legacy accepts, and Bean Validation orders violations arbitrarily,
 * which would displace the source-ordered message cascades owned by the services.
 *
 * <p>The user type is held as its raw character rather than as {@link UserType} because sign-on
 * routes an undeclared code instead of rejecting it, so an unrecognised byte must survive the round
 * trip unchanged; interpret it through {@link #resolvedUserType()}, which never throws. The program
 * context gates field-level error decoration: per-field errors are populated only on re-entry, so a
 * first submission carries none (decision log D-33).
 *
 * <p>Every component here is untrusted client input, including the identity ones, and that is a
 * trust change the migration introduced: in the legacy the identity bytes were server-authored from
 * an authenticated user-security read before any branch tested them, whereas a REST caller can send
 * any byte. No method here decides authorization or routing, and the one predicate over the
 * user-type code is named for the byte it reads. Reconcile the echoed identity against the
 * authenticated principal before acting on it, through {@link #reconciledWith(String, UserType)} to
 * overwrite or {@link #agreesWith(String, UserType)} to reject. Recorded as DL-087.
 *
 * <p>Only the user identifier and the user-type code may travel as signed token claims; no
 * selection identifier, route, program name or screen name may. There is no credential component and
 * none may be added.
 */
public record NavigationContext(
        @Size(max = NavigationContext.TRANSACTION_ID_LENGTH) String fromTransactionId,
        @Size(max = NavigationContext.PROGRAM_NAME_LENGTH) String fromProgram,
        @Size(max = NavigationContext.TRANSACTION_ID_LENGTH) String toTransactionId,
        @Size(max = NavigationContext.PROGRAM_NAME_LENGTH) String toProgram,
        @Size(max = NavigationContext.USER_ID_LENGTH) String userId,
        /* Raw code: an undeclared value is routed rather than rejected; see resolvedUserType(). */
        @Size(max = NavigationContext.USER_TYPE_LENGTH) String userType,
        ProgramContext programContext,
        @Size(max = NavigationContext.CUSTOMER_ID_LENGTH) String customerId,
        /* The three name parts are space-padded in the legacy area, and that padding is the value. */
        @Size(max = NavigationContext.CUSTOMER_NAME_LENGTH) String customerFirstName,
        @Size(max = NavigationContext.CUSTOMER_NAME_LENGTH) String customerMiddleName,
        @Size(max = NavigationContext.CUSTOMER_NAME_LENGTH) String customerLastName,
        @Size(max = NavigationContext.ACCOUNT_ID_LENGTH) String accountId,
        /* Echoed raw; the account-status vocabulary belongs to the domain layer, not here. */
        @Size(max = NavigationContext.ACCOUNT_STATUS_LENGTH) String accountStatus,
        @Size(max = NavigationContext.CARD_NUMBER_LENGTH) String cardNumber,
        @Size(max = NavigationContext.MAP_NAME_LENGTH) String lastMap,
        @Size(max = NavigationContext.MAP_NAME_LENGTH) String lastMapset) {

    /**
     * Fixed stand-in emitted by {@link #toString()} in place of each redacted component. A constant
     * rather than any transformation of the value, so neither the length nor a prefix nor a digest of
     * a redacted component can be recovered: a truncated primary account number is still cardholder
     * data, and a digest of a nine-character identifier is reversible by enumeration.
     */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    public static final int TRANSACTION_ID_LENGTH = 4;

    /**
     * Program-name width. Equal to {@link #USER_ID_LENGTH} only by coincidence; the two are unrelated
     * fields and neither is derived from the other.
     */
    public static final int PROGRAM_NAME_LENGTH = 8;

    public static final int USER_ID_LENGTH = 8;

    public static final int USER_TYPE_LENGTH = 1;

    public static final int CUSTOMER_ID_LENGTH = 9;

    public static final int CUSTOMER_NAME_LENGTH = 25;

    public static final int ACCOUNT_ID_LENGTH = 11;

    public static final int ACCOUNT_STATUS_LENGTH = 1;

    public static final int CARD_NUMBER_LENGTH = 16;

    public static final int MAP_NAME_LENGTH = 7;

    /** The wholly empty state, shared because the type is deeply immutable. */
    private static final NavigationContext EMPTY = new NavigationContext(
            null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null);

    /**
     * Returns the wholly empty state, with every component absent. This is a real legacy state rather
     * than a placeholder: an online program entered with an empty communication area has no signed-on
     * user, no selection and no previous screen, and routes to sign-on unconditionally.
     * {@link #firstEntry()} answers {@code true} for it.
     *
     * @return the state in which every component is {@code null}
     */
    public static NavigationContext empty() {
        return EMPTY;
    }

    /**
     * Resolves the raw user-type code to its declared type without ever throwing. The lookup returns
     * an empty result for an absent, blank, wrong-length or undeclared code and applies no case fold,
     * so a lower-case administrator character is not an administrator. An empty result is a
     * legitimate outcome rather than an error, because sign-on routes an undeclared code instead of
     * rejecting it.
     *
     * @return the declared user type, or an empty result when the code is absent or undeclared
     */
    public Optional<UserType> resolvedUserType() {
        return UserType.fromCode(userType);
    }

    /**
     * Reports whether the <em>echoed</em> user-type code is the administrator code.
     *
     * <p><strong>This is not an authorization check and must never be used as one.</strong> It reads
     * one character of client-supplied input and says what that character is; a caller who sends the
     * administrator character makes this answer {@code true} without holding any administrative
     * right, which is why the method is named for the byte it inspects. Authorization belongs to the
     * authenticated principal's signed role. It exists for parity, because the legacy tested this
     * condition on this byte &mdash; but there the byte had been read from the user-security record
     * first. Reconcile before reading it.
     *
     * @return {@code true} if and only if the echoed code is the administrator code, with no
     *     implication that the caller holds that role
     */
    public boolean echoesAdministratorCode() {
        return resolvedUserType().map(UserType::isAdmin).orElse(false);
    }

    /**
     * Reports whether the echoed identity matches the authenticated principal's: the reject half of
     * the reconciliation contract, whose overwrite half is
     * {@link #reconciledWith(String, UserType)}. The identifier is compared byte for byte with no
     * trim and no case fold, since it travels exactly as received and the user-security key is
     * fixed-width; the type is compared through {@link #resolvedUserType()}, so an undeclared or
     * absent echo never matches a declared principal role.
     *
     * @param authenticatedUserId   the identifier the authenticated principal holds; {@code null}
     *                              agrees only with an absent echo
     * @param authenticatedUserType the role the authenticated principal holds; {@code null} agrees
     *                              only with an unresolvable echo
     * @return {@code true} when the echoed identifier and role both match the authenticated ones
     */
    public boolean agreesWith(String authenticatedUserId, UserType authenticatedUserType) {
        boolean idAgrees = (authenticatedUserId == null)
                ? userId == null
                : authenticatedUserId.equals(userId);
        return idAgrees && resolvedUserType().orElse(null) == authenticatedUserType;
    }

    /**
     * Returns a copy whose identity components are taken from the authenticated principal rather than
     * from the client, leaving the other fourteen components untouched: the overwrite half of the
     * reconciliation contract, which restores the legacy property that those two bytes were always
     * server-authored. A disagreeing echo is replaced rather than reported; ask
     * {@link #agreesWith(String, UserType)} first to detect a substitution. The role is written back
     * as its raw one-character code so the copy still round-trips, and the remaining components are
     * carried across byte for byte.
     *
     * @param authenticatedUserId   the principal's identifier, written verbatim; {@code null} clears
     *                              the component
     * @param authenticatedUserType the principal's role, written as its declared code; {@code null}
     *                              clears the component
     * @return a new instance whose identifier and user-type code come from the principal
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

    /**
     * Reports whether this turn is a first entry into the screen. An absent program context is a
     * first entry rather than an unknown state, because the legacy sets the first-entry value before
     * handing control to a menu. Exact complement of {@link #reEntry()}.
     *
     * @return {@code true} when the program context is the first-entry state or is absent
     */
    public boolean firstEntry() {
        return !reEntry();
    }

    /**
     * Reports whether this turn is a re-entry into the same screen. This is the gate on field-level
     * error decoration: per-field errors are populated only when this answers {@code true}. The error
     * contract does not evaluate the gate itself and leaves it to the service, which reads it here.
     *
     * @return {@code true} when the program context is the re-entry state
     */
    public boolean reEntry() {
        return programContext == ProgramContext.REENTER;
    }

    /**
     * Returns a copy of this state marked as a first entry, leaving every other component untouched
     * and carried across byte for byte. Exists so that no caller has to restate sixteen components,
     * where one transposed argument would silently corrupt echoed state.
     *
     * @return a new instance differing from this one only in its program context
     */
    public NavigationContext withFirstEntry() {
        return withProgramContext(ProgramContext.ENTER);
    }

    /**
     * Returns a copy of this state marked as a re-entry, leaving every other component untouched. A
     * service uses it when it re-presents the same screen, which opens the field-error gate described
     * on {@link #reEntry()}.
     *
     * @return a new instance differing from this one only in its program context
     */
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

    /**
     * Returns a diagnostic representation that redacts the cardholder-identifying components. The
     * generated record rendering could not stand: it prints every component, and this type is echoed
     * on every online turn, so a default rendering would place a primary account number in a log line
     * at every screen transition.
     *
     * <p>The customer identifier, the three customer name parts, the account identifier and the card
     * number are replaced by a fixed placeholder &mdash; never truncated, never partially masked,
     * never hashed. Retained are the transition components (the originating and destination
     * transaction identifiers and program names, the first-entry or re-entry state, the last map and
     * mapset), the one-character user-type and account-status codes, which are behavioural state that
     * resolves to no subject without the withheld identifiers, and the signed-on user identifier,
     * which is what makes a navigation trace attributable at all. That identifier is identifying
     * operator data: a rendering of this record belongs in diagnostics with controlled access and
     * must not be emitted to unrestricted logs or echoed into a response payload.
     *
     * <p>{@code equals} and {@code hashCode} remain as the record contract generates them, so echoed
     * state still compares correctly across a turn.
     *
     * @return the transition state and the signed-on identifier, with the customer, account and card
     *     identifiers and the customer name parts replaced by a fixed placeholder
     */
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

    /**
     * Whether an online turn is a first entry into a screen or a re-entry into the same screen: the
     * typed form of the two states the copybook declares as condition names on a single digit. The
     * digit itself is an artefact of the fixed-width area and is deliberately never exposed. Nested
     * because it models presentation state that no domain enumeration covers and that is meaningful
     * only alongside the rest of this state.
     */
    public enum ProgramContext {

        /** First entry into the screen; field-level error decoration is suppressed in this state. */
        ENTER,

        /** Re-entry into the same screen; field-level error decoration applies only in this state. */
        REENTER
    }
}
