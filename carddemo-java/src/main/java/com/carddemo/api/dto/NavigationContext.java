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
 * turn.
 *
 * <p>The legacy antecedent is the group {@code CARDDEMO-COMMAREA} declared at
 * {@code app/cpy/COCOM01Y.cpy} line 19 and textually included by all seventeen online COBOL
 * programs, the joint-highest copybook fan-out in the estate. The area is a fixed 160 bytes, and
 * summing the sixteen elementary field widths cited on the components below accounts for every one
 * of them: 4 + 8 + 4 + 8 + 8 + 1 + 1 + 9 + 25 + 25 + 25 + 11 + 1 + 16 + 7 + 7 = 160. There is no
 * filler anywhere in the layout and therefore nothing omitted here. All sixteen fields are modelled,
 * in declaration order, across the five groups the copybook declares: general information (line 20),
 * customer information (line 32), account information (line 37), card information (line 40) and the
 * trailing screen-state group (line 42).</p>
 *
 * <p><strong>This is echoed request state, not a server session.</strong> A CICS pseudo-conversation
 * ends each turn by returning to the terminal and is re-entered on the next one with the
 * communication area handed back, so the area is the whole of the state that survives between turns.
 * The REST translation keeps that shape exactly: the server returns this state in a response body,
 * the client holds it, and the client sends it back on the next call. There is consequently no
 * server-side session behind this type - no servlet session, no session-scoped attribute, no
 * server-side cache and no sticky routing - which is what makes every endpoint independently
 * testable. An instance is a value: never stored, never mutated, never shared as writable state, and
 * the two derivation methods below return new instances.
 *
 * <p><strong>The four routing components are declarative only.</strong> The estate dispatches program
 * to program with twenty-five transfer-control commands and re-arms the next turn with nineteen
 * return-with-transaction commands; all of them become route constants returned in response bodies,
 * so the client drives the next call and the server forwards nothing. The transaction-id and
 * program-name components are therefore data recording where a turn came from and where the previous
 * turn said it was going: sign-on stamps its own transaction id and program name into the area at
 * {@code app/cbl/COSGN00C.cbl} lines 224 and 225 before handing control on. This record accordingly
 * declares <strong>no route table, no route constant and no dispatch method</strong>; route constants
 * belong to the service layer, and placing them here would put navigation decisions in a
 * data-transfer type and invert the layering. {@link #administrator()} is a predicate over an echoed
 * value: it reports a condition and selects nothing.
 *
 * <p><strong>Identifiers are text, never numbers.</strong> The customer identifier (line 33, nine
 * digits), the account identifier (line 38, eleven digits) and the card number (line 41, sixteen
 * digits) are declared numeric in the copybook, yet all three are identifiers with contractual
 * leading zeros and fixed external widths, so all three are carried as {@code String}. A nine-
 * character customer identifier of {@code "000000042"} must round-trip as those exact nine
 * characters and never as {@code 42}; an eleven-character account identifier of
 * {@code "00000000001"} must never become {@code "1"}. Coercing any of them to a numeric type would
 * discard leading zeros and shorten the external width, and that width is compared directly by the
 * byte-equivalence acceptance criterion. These are also the genuine business keys of the estate,
 * never surrogates, so nothing here is generated, renumbered or reformatted.
 *
 * <p><strong>An unrecognised user type is accepted, never rejected.</strong> The user-type field
 * (line 26) carries two condition names: the administrator value {@code A} at line 27 and the
 * standard-user value {@code U} at line 28. Sign-on moves the persisted type into the area at
 * {@code app/cbl/COSGN00C.cbl} line 227, tests the administrator condition at line 230, and supplies
 * an <strong>unconditional</strong> alternative at line 235 that closes at line 240. That
 * alternative is not a second test of the standard-user condition: there is no third branch and no
 * error path, so every value other than the administrator code reaches the main menu, including a
 * character the estate never declared. Two consequences follow. The component is the raw
 * one-character code rather than {@link UserType}, because holding the enum directly would erase an
 * unrecognised byte at construction and the client would echo back something different from what it
 * received. Resolution is a derived, non-throwing projection: {@link #resolvedUserType()} delegates
 * to the lookup on {@link UserType}, which returns an empty result rather than raising for an
 * absent, blank, over-long or undeclared code and applies no case fold, so construction from a
 * legacy record whose type character is neither {@code A} nor {@code U} always succeeds. A throwing
 * lookup or a membership constraint would abort a sign-on that the legacy program completes.
 *
 * <p><strong>The enter and re-enter flag gates field-error decoration.</strong> The program-context
 * field (line 29) is a single digit with two condition names, first entry at line 30 and re-entry at
 * line 31, and is modelled as the nested {@link ProgramContext} enum because the digit is an
 * artefact of the fixed-width area while the two named states are the contract. The flag is
 * behaviourally load-bearing: field-level error decoration is applied only on re-entry, so per-field
 * errors are absent on a first submission and appear only when the same screen is submitted again
 * (decision log entry D-33). {@link ErrorResponse} deliberately does not evaluate that gate and
 * leaves it to the service, which reads it from here. An absent value is first entry, matching
 * sign-on setting the digit to zero at {@code app/cbl/COSGN00C.cbl} line 228.
 *
 * <p><strong>Nothing is validated, defaulted or normalised here.</strong> There is no canonical
 * constructor because there is nothing for one to do: every component may legitimately be
 * {@code null}, an entirely empty area is a real state, and it routes to sign-on unconditionally.
 * Values cross this boundary byte for byte and are never trimmed, padded, case-folded, stripped,
 * canonicalised or re-formatted. Legacy fixed-width fields are space-padded and that padding is
 * contract, which matters most for the three twenty-five-character customer names and the two
 * seven-character screen-state names. The only constraint used is a maximum length, which measures
 * and never alters, so leading and trailing spaces survive validation untouched. No presence,
 * pattern, character-class or numeric range constraint appears anywhere: each would reject input the
 * legacy system accepts, and Bean Validation reports violations in an unspecified order, which would
 * replace the source-ordered message cascades that belong to the services.
 *
 * <p><strong>Only two of these values may ever become signed token claims</strong> - the
 * eight-character user identifier and the one-character user type. Everything else here is
 * client-echoed state and must never enter a token: no selection identifier, no route, no program
 * name and no screen name. There is no credential component and none may be added; sign-on carries
 * the operator's secret in its own request type, and the user-security table stores only a password
 * digest.
 *
 * @param fromTransactionId the transaction the turn arrived from, from {@code CDEMO-FROM-TRANID}
 *     ({@code PIC X(04)}, four characters, line 21). Stamped by the sending program with its own
 *     transaction id, as sign-on does at {@code app/cbl/COSGN00C.cbl} line 224. Declarative only:
 *     nothing here dispatches on it. May be {@code null}.
 * @param fromProgram the program the turn arrived from, from {@code CDEMO-FROM-PROGRAM}
 *     ({@code PIC X(08)}, eight characters, line 22). Stamped by the sending program with its own
 *     name, as sign-on does at {@code app/cbl/COSGN00C.cbl} line 225. Declarative only. May be
 *     {@code null}.
 * @param toTransactionId the transaction the previous turn nominated next, from
 *     {@code CDEMO-TO-TRANID} ({@code PIC X(04)}, four characters, line 23). Declarative only; the
 *     client drives the next call. May be {@code null}.
 * @param toProgram the program the previous turn nominated next, from {@code CDEMO-TO-PROGRAM}
 *     ({@code PIC X(08)}, eight characters, line 24). Declarative only; no server-side forwarding
 *     occurs. May be {@code null}.
 * @param userId the signed-on user identifier, from {@code CDEMO-USER-ID} ({@code PIC X(08)}, eight
 *     characters, line 25), set by sign-on at {@code app/cbl/COSGN00C.cbl} line 226. Carried exactly
 *     as received: never trimmed, never case-folded and never parsed as a number. One of the two
 *     values that also travels as a signed token claim. May be {@code null}.
 * @param userType the raw one-character user-type code, from {@code CDEMO-USER-TYPE}
 *     ({@code PIC X(01)}, one character, line 26), whose two condition names carry {@code A} at
 *     line 27 and {@code U} at line 28. Held raw rather than as an enum so an undeclared character
 *     survives the round trip; resolve it through {@link #resolvedUserType()}, which never throws.
 *     Not constrained to the declared values, because the legacy routes an undeclared value instead
 *     of rejecting it. The second of the two values that also travels as a signed token claim. May
 *     be {@code null}.
 * @param programContext whether this turn is a first entry or a re-entry, the typed form of
 *     {@code CDEMO-PGM-CONTEXT} ({@code PIC 9(01)}, one digit, line 29) whose condition names are
 *     first entry at line 30 and re-entry at line 31. Gates whether field-level error decoration
 *     applies at all. {@code null} means first entry; see {@link #firstEntry()} and
 *     {@link #reEntry()}.
 * @param customerId the selected customer identifier, from {@code CDEMO-CUST-ID}
 *     ({@code PIC 9(09)}, nine digits, line 33). A {@code String} so its leading zeros and its
 *     nine-character external width are preserved exactly; never a numeric type. May be
 *     {@code null} when no customer is selected.
 * @param customerFirstName the selected customer's first name, from {@code CDEMO-CUST-FNAME}
 *     ({@code PIC X(25)}, twenty-five characters, line 34). Space-padded in the legacy area and
 *     carried byte for byte, including that padding. May be {@code null}.
 * @param customerMiddleName the selected customer's middle name, from {@code CDEMO-CUST-MNAME}
 *     ({@code PIC X(25)}, twenty-five characters, line 35). Space-padded and carried byte for byte.
 *     May be {@code null}.
 * @param customerLastName the selected customer's last name, from {@code CDEMO-CUST-LNAME}
 *     ({@code PIC X(25)}, twenty-five characters, line 36). Space-padded and carried byte for byte.
 *     May be {@code null}.
 * @param accountId the selected account identifier, from {@code CDEMO-ACCT-ID}
 *     ({@code PIC 9(11)}, eleven digits, line 38). A {@code String} so its leading zeros and its
 *     eleven-character external width are preserved exactly; never a numeric type. May be
 *     {@code null} when no account is selected.
 * @param accountStatus the raw one-character account status of the selected account, from
 *     {@code CDEMO-ACCT-STATUS} ({@code PIC X(01)}, one character, line 39). Echoed raw rather than
 *     interpreted here; the account status vocabulary belongs to the domain layer and this type is
 *     not the place to translate it. May be {@code null}.
 * @param cardNumber the selected card number, from {@code CDEMO-CARD-NUM} ({@code PIC 9(16)},
 *     sixteen digits, line 41). A {@code String} so its leading zeros and its sixteen-character
 *     external width are preserved exactly; never a numeric type. May be {@code null} when no card
 *     is selected.
 * @param lastMap the screen last presented, from {@code CDEMO-LAST-MAP} ({@code PIC X(7)}, seven
 *     characters, line 43). Carried verbatim as the name of the previous screen; no screen geometry,
 *     attribute or control value is modelled anywhere in this type. May be {@code null}.
 * @param lastMapset the screen group last presented, from {@code CDEMO-LAST-MAPSET}
 *     ({@code PIC X(7)}, seven characters, line 44). Carried verbatim alongside {@code lastMap}. May
 *     be {@code null}.
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

    /**
     * Fixed stand-in emitted by {@link #toString()} in place of each identifying component.
     *
     * <p>A constant rather than any transformation of the value, so nothing about a redacted component
     * - not its length, not a prefix or suffix, not a digest - can be recovered from a stringified
     * instance. A partial mask was rejected deliberately: a truncated primary account number is still
     * cardholder data, and a digest of a nine-character identifier is trivially reversible by
     * enumeration.
     *
     * <p>Private because it is a rendering detail and not part of the navigation contract.</p>
     */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    /**
     * Width in characters of a CICS transaction identifier: 4.
     *
     * <p>The legacy width of {@code CDEMO-FROM-TRANID} at {@code app/cpy/COCOM01Y.cpy} line 21 and of
     * {@code CDEMO-TO-TRANID} at line 23. The two fields share this constant because they are the
     * same kind of value at the same declared width, not because their widths happen to coincide.
     * The bound only reports an over-long value; it never trims, pads or otherwise alters one.</p>
     */
    public static final int TRANSACTION_ID_LENGTH = 4;

    /**
     * Width in characters of a program name: 8 - the legacy width of
     * {@code CDEMO-FROM-PROGRAM} at {@code app/cpy/COCOM01Y.cpy} line 22 and of
     * {@code CDEMO-TO-PROGRAM} at line 24. Declared separately from {@link #USER_ID_LENGTH} even
     * though the values are equal, because a program name and a user identifier are unrelated
     * fields whose widths coincide by accident; neither is derived from the other.
     */
    public static final int PROGRAM_NAME_LENGTH = 8;

    /**
     * Width in characters of the signed-on user identifier: 8 - the legacy width of
     * {@code CDEMO-USER-ID} at {@code app/cpy/COCOM01Y.cpy} line 25, matching the user-id field of
     * the credential record {@code app/cpy/CSUSR01Y.cpy}. Declared separately from
     * {@link #PROGRAM_NAME_LENGTH} for the reason given there.
     */
    public static final int USER_ID_LENGTH = 8;

    /**
     * Width in characters of the raw user-type code: 1 - the legacy width of
     * {@code CDEMO-USER-TYPE} at {@code app/cpy/COCOM01Y.cpy} line 26. The bound deliberately does
     * <em>not</em> restrict the value to the two condition values at lines 27 and 28, because an
     * undeclared single character is accepted and routed by the legacy program rather than rejected.
     */
    public static final int USER_TYPE_LENGTH = 1;

    /**
     * Width in characters of the customer identifier: 9 - the legacy width of
     * {@code CDEMO-CUST-ID} at {@code app/cpy/COCOM01Y.cpy} line 33, declared numeric there and
     * carried as text here so its leading zeros and external width survive.
     */
    public static final int CUSTOMER_ID_LENGTH = 9;

    /**
     * Width in characters of each customer name part: 25 - the legacy width of
     * {@code CDEMO-CUST-FNAME}, {@code CDEMO-CUST-MNAME} and {@code CDEMO-CUST-LNAME} at
     * {@code app/cpy/COCOM01Y.cpy} lines 34, 35 and 36. The legacy fields are space-padded to this
     * width and the padding is contract.
     */
    public static final int CUSTOMER_NAME_LENGTH = 25;

    /**
     * Width in characters of the account identifier: 11 - the legacy width of
     * {@code CDEMO-ACCT-ID} at {@code app/cpy/COCOM01Y.cpy} line 38, declared numeric there and
     * carried as text here so its leading zeros and external width survive.
     */
    public static final int ACCOUNT_ID_LENGTH = 11;

    /**
     * Width in characters of the raw account-status code: 1 - the legacy width of
     * {@code CDEMO-ACCT-STATUS} at {@code app/cpy/COCOM01Y.cpy} line 39. As with the user type the
     * bound does not restrict the value, because the status is echoed rather than interpreted here.
     */
    public static final int ACCOUNT_STATUS_LENGTH = 1;

    /**
     * Width in characters of the card number: 16 - the legacy width of
     * {@code CDEMO-CARD-NUM} at {@code app/cpy/COCOM01Y.cpy} line 41, declared numeric there and
     * carried as text here because sixteen digits are an identifier rather than a quantity.
     */
    public static final int CARD_NUMBER_LENGTH = 16;

    /**
     * Width in characters of a screen name and of a screen-group name: 7 - the legacy width of
     * {@code CDEMO-LAST-MAP} at {@code app/cpy/COCOM01Y.cpy} line 43 and of
     * {@code CDEMO-LAST-MAPSET} at line 44, which always travel together. These are names only: no
     * screen geometry, attribute or control value is modelled by this type.
     */
    public static final int MAP_NAME_LENGTH = 7;

    /**
     * The wholly empty state, with every component absent.
     *
     * <p>Cached because the type is deeply immutable &mdash; every component is a {@code String} or an
     * enum constant, and a record exposes no mutator &mdash; so one shared instance is safe and
     * behaves identically to a fresh one. It is private; callers obtain it through {@link #empty()}.
     * </p>
     */
    private static final NavigationContext EMPTY = new NavigationContext(
            null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null);

    /**
     * Returns the wholly empty state, with every component absent.
     *
     * <p>This is a real legacy state rather than a placeholder: an online program that is entered with
     * an empty communication area has no signed-on user, no selection and no previous screen, and it
     * routes to sign-on unconditionally. Having one named form of it keeps the sixteen-component
     * constructor out of every caller and every test that needs to express "nothing carried yet".</p>
     *
     * <p>Pure: it allocates nothing per call and mutates nothing. Because the type is deeply
     * immutable, the returned instance can be held and compared freely, and
     * {@link #firstEntry()} answers {@code true} for it, matching a program context that has not been
     * set to the re-entry value.</p>
     *
     * @return the state in which every one of the sixteen components is {@code null}
     */
    public static NavigationContext empty() {
        return EMPTY;
    }

    /**
     * Resolves the raw user-type code to its declared type without ever throwing.
     *
     * <p>Delegates to the lookup on {@link UserType}, which returns an empty result for an absent,
     * blank, wrong-length or undeclared code and applies no case fold, so a lower-case administrator
     * character is not an administrator. An empty result is a legitimate outcome rather than an
     * error: {@code app/cbl/COSGN00C.cbl} supplies an unconditional alternative at line 235 for every
     * value other than the administrator code, closing at line 240 with no third branch and no error
     * path, so an undeclared code is routed rather than rejected.</p>
     *
     * <p>This method is the reason {@link #userType()} is a raw character rather than an enum: the raw
     * value survives the round trip to the client untouched, and interpretation happens here, on
     * demand, where it can be absent without failing.</p>
     *
     * @return the declared user type, or an empty result when the code is absent or is not one of the
     *     two values declared at {@code app/cpy/COCOM01Y.cpy} lines 27 and 28, carried there by the
     *     condition names {@code CDEMO-USRTYP-ADMIN} and {@code CDEMO-USRTYP-USER}
     */
    public Optional<UserType> resolvedUserType() {
        return UserType.fromCode(userType);
    }

    /**
     * Reports whether the carried user-type code is the administrator code.
     *
     * <p>Mirrors the single condition tested at {@code app/cbl/COSGN00C.cbl} line 230 and nothing
     * else. {@code true} for the administrator code only; every other outcome, including an absent
     * code and an undeclared one, is {@code false}, which is exactly what the unconditional
     * alternative at line 235 encodes.</p>
     *
     * <p>This is a predicate over echoed data, <strong>not a routing decision</strong>: it returns a
     * boolean, selects no destination and performs no dispatch. Which route an administrator or a
     * non-administrator is sent to belongs to the service layer.</p>
     *
     * @return {@code true} if and only if the carried code is the administrator code
     */
    public boolean administrator() {
        return resolvedUserType().map(UserType::isAdmin).orElse(false);
    }

    /**
     * Reports whether this turn is a first entry into the screen.
     *
     * <p>True for the first-entry state and true when the program context is absent, because sign-on
     * sets the context to the first-entry value at {@code app/cbl/COSGN00C.cbl} line 228 before it
     * hands control to a menu, so an unset context is a first entry rather than an unknown state.</p>
     *
     * <p>Exact complement of {@link #reEntry()}. Both are provided because the copybook declares both
     * condition names, at {@code app/cpy/COCOM01Y.cpy} lines 30 and 31, and a caller reads more
     * clearly asserting the state it means than negating the other one.</p>
     *
     * @return {@code true} when the program context is the first-entry state or is absent
     */
    public boolean firstEntry() {
        return !reEntry();
    }

    /**
     * Reports whether this turn is a re-entry into the same screen.
     *
     * <p>True only for the re-entry state; an absent program context is a first entry, as explained on
     * {@link #firstEntry()}. This is the gate on field-level error decoration: per-field errors are
     * populated only when this answers {@code true}, so a first submission carries none and errors
     * appear only when the same screen is submitted again. The error contract deliberately does not
     * evaluate the gate itself and leaves it to the service, which reads it here.</p>
     *
     * @return {@code true} when the program context is the re-entry state
     */
    public boolean reEntry() {
        return programContext == ProgramContext.REENTER;
    }

    /**
     * Returns a copy of this state marked as a first entry, leaving every other component untouched.
     *
     * <p>Pure: it returns a new instance and mutates nothing, so the receiver is unchanged and safe to
     * keep using. The other fifteen components are carried across byte for byte &mdash; nothing is
     * trimmed, padded, re-cased or re-formatted &mdash; which is the point of having this method
     * rather than reconstructing sixteen components at every call site, where one transposed argument
     * would silently corrupt echoed state.</p>
     *
     * <p>This is the value-level equivalent of a program setting the context to the first-entry value,
     * as sign-on does at {@code app/cbl/COSGN00C.cbl} line 228.</p>
     *
     * @return a new instance identical to this one except that the program context is the first-entry
     *     state
     */
    public NavigationContext withFirstEntry() {
        return withProgramContext(ProgramContext.ENTER);
    }

    /**
     * Returns a copy of this state marked as a re-entry, leaving every other component untouched.
     *
     * <p>Pure, and carries the other fifteen components across byte for byte, for the reasons given on
     * {@link #withFirstEntry()}. A service uses it when it re-presents the same screen, which is what
     * opens the field-error gate described on {@link #reEntry()}.</p>
     *
     * @return a new instance identical to this one except that the program context is the re-entry
     *     state
     */
    public NavigationContext withReEntry() {
        return withProgramContext(ProgramContext.REENTER);
    }

    /**
     * Builds a copy carrying the supplied program context and every other component unchanged.
     *
     * <p>Private because the two named derivations above are the whole of the intended surface: the
     * enter and re-enter states are the only two the copybook declares, so exposing a general setter
     * would add an unnecessary way to reach the same two results. It performs no transformation of
     * any component.</p>
     *
     * @param context the program context the copy carries; may be {@code null}, which is the
     *     first-entry state
     * @return a new instance differing from this one only in its program context
     */
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
     * Returns a diagnostic representation carrying the navigation state and redacting the identity and
     * account state that travels beside it.
     *
     * <p><strong>Why the implicit record rendering could not stand.</strong> A record's generated
     * {@code toString()} prints every component. Seven of the sixteen here are regulated: the customer
     * identifier, the three customer name parts, the account identifier and the card number - a
     * primary account number - together with the signed-on identity. Because this type is echoed on
     * every online turn, it is the single most frequently stringified object in the online tier, so a
     * default rendering would have put cardholder data into a log line on every screen transition.
     *
     * <p><strong>What is retained, and why it is safe.</strong> The whole diagnostic value of this type
     * is the transition it describes, and none of that is regulated: the originating and destination
     * transaction identifiers and program names, the first-entry or re-entry state, the last map and
     * mapset, the one-character user-type code and the one-character account-status code. Those nine
     * components answer the questions a navigation trace is read to answer - which screen handed off to
     * which, on a first submission or a re-submission, in which role - and none of them identifies a
     * person, an account or a card. The two single-character codes are behavioural state rather than
     * identity: neither can be resolved to a subject without the identifiers, which are withheld.
     *
     * <p><strong>What is withheld, and why the signed-on identifier is not.</strong> The customer
     * identifier, the three name parts, the account identifier and the card number are replaced by a
     * fixed placeholder - never truncated, never masked in part, never hashed, because a partial
     * primary account number is still cardholder data and a hash of a nine-digit identifier is
     * trivially reversible. The signed-on user identifier is retained: it is an operator credential
     * subject rather than a cardholder attribute, it is what makes a navigation trace attributable at
     * all, and it is the same value the sign-on request contract retains in its own redacted rendering.
     *
     * <p>{@code equals} and {@code hashCode} remain as the record contract generates them. They compare
     * every component and emit nothing, so echoed state still compares correctly across a turn.
     *
     * @return the transition state, with every identifying component replaced by a fixed placeholder
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
     * Whether an online turn is a first entry into a screen or a re-entry into the same screen.
     *
     * <p>The typed form of {@code CDEMO-PGM-CONTEXT} ({@code PIC 9(01)},
     * {@code app/cpy/COCOM01Y.cpy} line 29) and of the two condition names declared on it,
     * {@code CDEMO-PGM-ENTER} for first entry at line 30 and {@code CDEMO-PGM-REENTER} for re-entry at
     * line 31. The underlying digit belongs to the fixed-width area
     * and is deliberately not exposed by this type or by this enum: the two named states are the
     * contract, and no component, accessor or lookup here surfaces a raw {@code 0} or {@code 1}.</p>
     *
     * <p>Nested inside the record on purpose. It models presentation state that no domain enumeration
     * covers, it is meaningful only alongside the rest of this state, and nesting keeps it out of the
     * shared enumeration package without adding a file. A nested enum in a record is implicitly
     * static, so it carries no reference to any enclosing instance.</p>
     */
    public enum ProgramContext {

        /**
         * First entry into the screen, the state whose condition name {@code CDEMO-PGM-ENTER} is
         * declared at {@code app/cpy/COCOM01Y.cpy} line 30.
         *
         * <p>Sign-on establishes this state before handing control to a menu, at
         * {@code app/cbl/COSGN00C.cbl} line 228. Field-level error decoration is suppressed in this
         * state, which is why a first submission of a screen reports no per-field error.</p>
         */
        ENTER,

        /**
         * Re-entry into the same screen, the state whose condition name {@code CDEMO-PGM-REENTER} is
         * declared at {@code app/cpy/COCOM01Y.cpy} line 31.
         *
         * <p>Field-level error decoration applies only in this state, so per-field errors appear on a
         * re-submission and never on a first submission.</p>
         */
        REENTER
    }
}
