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

import com.carddemo.domain.enums.KeyAction;
import jakarta.validation.constraints.Size;
import java.math.BigInteger;
import java.util.Optional;

/**
 * Immutable screen work area carried across one CardDemo pseudo-conversational turn.
 *
 * <p>This record is the transport-era projection of the {@code CC-WORK-AREAS} structure declared in
 * copybook member {@code app/cpy/CVCRD01Y.cpy}: the group item occupies line 1, its single
 * subordinate group line 2, and the members modelled here are declared between lines 3 and 42. The
 * structure is the per-interaction scratch pad the legacy screens used to remember which attention
 * key the operator pressed, where the flow was headed next, which message to render, and which
 * account, card and customer the operator was working on. {@code CVCRD01Y} is copied by
 * {@code COACTUPC}, {@code COACTVWC}, {@code COCRDLIC}, {@code COCRDSLC} and {@code COCRDUPC} and by
 * no other member of the estate - the account and card family, which is also the only family that
 * arms a CICS abend handler and the only family that copies the attention-key procedural member
 * {@code app/cpy/CSSTRPFY.cpy}. That fan-out of five is why exactly five services consume this type.
 *
 * <p><strong>Four members of the originating copybook are commented out and are absent here.</strong>
 * They are not part of the record layout at all, and a reader skimming the member for subordinate
 * declarations would model them by accident and invent state the legacy system deliberately
 * abandoned, so each is named to make the exclusion auditable: the last-program field (line 20), the
 * return-to-program field (line 22), the return-flag field (line 25) with both its condition names
 * (lines 26 and 27), and the function field (line 31) with both its condition names (lines 32 and
 * 33). A mechanical scan of every program finds zero references to any of the four and zero to any of
 * their condition names, corroborating abandonment rather than mere disuse. This record therefore
 * declares no last-program, return-to-program, return-flag or function component, and no on/off state
 * for the return flag: inventing a boolean that nothing would ever set would fabricate a state
 * transition the 3270 contract never had. Decision log entry D-40 records the exclusion.
 *
 * <p><strong>Two similarly named members must not be confused.</strong> The return <em>message</em>
 * field on line 29 is live, and so is its own "off" condition name on line 30; the return
 * <em>flag</em> field on line 25 and its "off" condition name on line 26 are the dead pair. This
 * record carries the return message and does not carry the return flag. Two live members are
 * themselves unreferenced by the five including programs - the customer identifier with its numeric
 * alias, and the return message with its condition name - and are modelled all the same, because the
 * distinction that governs this file is membership of the layout, not reference count: a live member
 * is modelled whether or not a program happens to read it, while a commented-out member is not a
 * member at all.
 *
 * <p><strong>The attention identifier has no default, because the legacy mapping has no
 * fallback.</strong> It is the five-character field on line 3, beneath which the copybook declares
 * sixteen level-88 condition names on lines 4 through 19 - ENTER, CLEAR, the two program-attention
 * keys and the twelve program-function keys - realised as the sixteen constants of
 * {@link KeyAction}, so the component below is typed as that enum rather than as loose text. The
 * mapping from a terminal attention identifier to one of those names lives in
 * {@code app/cpy/CSSTRPFY.cpy}, whose two paragraphs begin on lines 17 and 80; between them sits a
 * single ordered conditional running from line 21 to line 78 with twenty-eight branches and,
 * verified by a mechanical count, <strong>no otherwise branch</strong>. That absence is
 * behaviourally significant: when the incoming identifier matches no branch nothing is assigned and
 * the field retains whatever value it already held, so absence is modelled as an absent component -
 * {@code null}, surfaced explicitly by {@link #attentionKey()} - and never as a synthetic default,
 * unknown, none or invalid constant, which would manufacture a state the legacy system cannot
 * produce. The same conditional folds program-function keys 13 through 24 back onto the same twelve
 * flags as keys 1 through 12 on lines 54 through 77, so keys 13 through 24 are not distinct actions
 * and no constant exists for them (decision log entry D-20). Performing that fold is the work of the
 * utility-layer key translator: this record accepts an already-resolved key action and performs no
 * translation, folding or key interpretation, and holds no dependency on the utility layer.
 *
 * <p><strong>Three identifiers are stored as text and expose derived numeric views.</strong> The
 * account identifier, the card number and the customer identifier are each declared twice in the
 * copybook - once as fixed-width text (lines 34, 37 and 40) and once, immediately afterwards, as an
 * unsigned numeric redefinition over the very same bytes (lines 36, 39 and 42). A redefinition is
 * not a second field but a second way of reading one storage area, which is why the two views can
 * never disagree, and that property is reproduced by storing only the textual form and deriving each
 * numeric view on demand. The text is authoritative: every fixed-width numeric field in this
 * migration crosses the API as bounded text rather than as a numeric type, because leading zeros and
 * external field widths are contractual. The derived views - {@link #accountIdNumeric()},
 * {@link #cardNumberNumeric()} and {@link #customerIdNumeric()} - exist only for callers that
 * genuinely need arithmetic or a numeric comparison, mirroring how the legacy programs use the
 * numeric alias to test an identifier against zero, compare it with a record key and move it into a
 * numeric key field. All three text fields are initialised to spaces by the copybook, so a blank
 * identifier is a normal state rather than an error, and every derived view is total: it reports
 * absence for a missing, empty, blank or otherwise non-numeric value and never raises. Writing
 * <em>through</em> the numeric alias, which the legacy programs also do, right-justifies and
 * zero-fills the shared bytes; forming that text is the caller's business, so this record neither
 * pads nor justifies anything.
 *
 * <p><strong>The screen-flow components are declarative only.</strong> The next-program, next-mapset
 * and next-map components record where the interaction is headed and are data and nothing more: the
 * legacy transfer-of-control dispatches become route values returned in a response body, the client
 * drives the following call, and there is no server-side forwarding anywhere in the target. This
 * record declares no route table, no route constant and no dispatch behaviour; naming and resolving
 * routes belongs to the service layer.
 *
 * <p><strong>Absence, not sentinel bytes, and no presence constraints.</strong> The return-message
 * field carries a condition name on line 30 that reads "off" as a low-values field. Here "off" is
 * simply an absent component: no low-values sentinel is encoded, no null character is ever produced,
 * and callers test for absence rather than for a magic byte. That substitution is the one place where
 * this transport type departs from the legacy byte-level representation, and decision log entry D-40
 * records it. Every component may legitimately be absent or blank, so none carries a presence,
 * pattern or range constraint; the only constraint used is an upper bound at the width the copybook
 * declares, because a bound measures and reports without trimming, padding or rewriting a value,
 * which is the only safe constraint on fixed-width fields whose leading and trailing spaces are real
 * data.
 *
 * <p>The record is deeply immutable: every component is either an enum constant or a {@code String},
 * there is no collection, array or mutable state, and the canonical constructor is deliberately left
 * exactly as generated - it defaults nothing, normalises nothing and rejects nothing, so every value
 * crosses this boundary character for character. The type is framework-free apart from the length
 * bounds, is mapped to no database column, and represents transient screen-interaction state rather
 * than persisted data.
 *
 * @param keyAction the attention key the operator pressed, from the five-character
 *     {@code CCARD-AID} field {@code PIC X(5)} on line 3 of {@code app/cpy/CVCRD01Y.cpy} and its
 *     sixteen condition names on lines 4 through 19. May be {@code null}, which is the faithful
 *     representation of an identifier the legacy mapping does not recognise; see
 *     {@link #attentionKey()}. Never substitute a fallback constant.
 * @param nextProgram the program the flow is headed to, from {@code CCARD-NEXT-PROG}
 *     {@code PIC X(8)} on line 21. Declarative only. Arrives space padded to eight characters and
 *     is carried unaltered. May be {@code null} or blank.
 * @param nextMapset the mapset the flow is headed to, from {@code CCARD-NEXT-MAPSET}
 *     {@code PIC X(7)} on line 23. Declarative only. Arrives space padded to seven characters and
 *     is carried unaltered. May be {@code null} or blank.
 * @param nextMap the map the flow is headed to, from {@code CCARD-NEXT-MAP} {@code PIC X(7)} on
 *     line 24. Declarative only. Arrives space padded to seven characters and is carried
 *     unaltered. May be {@code null} or blank.
 * @param errorMessage the message rendered when the interaction fails validation, from
 *     {@code CCARD-ERROR-MSG} {@code PIC X(75)} on line 28. Seventy-five characters of
 *     space-padded text, carried byte for byte so the rendered message is identical to the legacy
 *     one. May be {@code null} or blank when there is nothing to report.
 * @param returnMessage the message carried back to the program being returned to, from
 *     {@code CCARD-RETURN-MSG} {@code PIC X(75)} on line 29, whose condition name on line 30 reads
 *     an unset field as off. Seventy-five characters of space-padded text, carried byte for byte.
 *     Absence expresses the off state; no sentinel byte is encoded. Not to be confused with the
 *     commented-out return flag on line 25, which this record does not model.
 * @param accountId the account identifier as text, from {@code CC-ACCT-ID} {@code PIC X(11)} on
 *     line 34, which the copybook initialises to spaces. Eleven characters, carried exactly as
 *     supplied: never trimmed, padded, re-justified or stripped of a leading zero. The unsigned
 *     numeric redefinition on line 36 is exposed as the derived view
 *     {@link #accountIdNumeric()} rather than as a second stored component.
 * @param cardNumber the card number as text, from {@code CC-CARD-NUM} {@code PIC X(16)} on line 37,
 *     which the copybook initialises to spaces. Sixteen characters, carried exactly as supplied.
 *     The unsigned numeric redefinition on line 39 is exposed as the derived view
 *     {@link #cardNumberNumeric()}; because sixteen digits exceed the range of a 32-bit integer,
 *     that view is an arbitrary-precision value.
 * @param customerId the customer identifier as text, from {@code CC-CUST-ID} {@code PIC X(09)} on
 *     line 40, which the copybook initialises to spaces. Nine characters, carried exactly as
 *     supplied. The unsigned numeric redefinition on line 42 is exposed as the derived view
 *     {@link #customerIdNumeric()}.
 */
public record ScreenWorkArea(
        /* CCARD-AID, width 5, CVCRD01Y line 3 - typed as the enum of its 16 condition names.
         * Nullable on purpose: the legacy key mapping has no otherwise branch. */
        KeyAction keyAction,

        /* CCARD-NEXT-PROG, width 8, CVCRD01Y line 21 - declarative only, never dispatched. */
        @Size(max = ScreenWorkArea.NEXT_PROGRAM_LENGTH) String nextProgram,

        /* CCARD-NEXT-MAPSET, width 7, CVCRD01Y line 23 - declarative only. */
        @Size(max = ScreenWorkArea.NEXT_MAPSET_LENGTH) String nextMapset,

        /* CCARD-NEXT-MAP, width 7, CVCRD01Y line 24 - declarative only. */
        @Size(max = ScreenWorkArea.NEXT_MAP_LENGTH) String nextMap,

        /* CCARD-ERROR-MSG, width 75, CVCRD01Y line 28. */
        @Size(max = ScreenWorkArea.ERROR_MESSAGE_LENGTH) String errorMessage,

        /* CCARD-RETURN-MSG, width 75, CVCRD01Y line 29, with its off condition name on line 30.
         * Live - not to be confused with the commented-out return flag on line 25. */
        @Size(max = ScreenWorkArea.RETURN_MESSAGE_LENGTH) String returnMessage,

        /* CC-ACCT-ID, width 11, CVCRD01Y line 34; numeric redefinition on line 36 is derived. */
        @Size(max = ScreenWorkArea.ACCOUNT_ID_LENGTH) String accountId,

        /* CC-CARD-NUM, width 16, CVCRD01Y line 37; numeric redefinition on line 39 is derived. */
        @Size(max = ScreenWorkArea.CARD_NUMBER_LENGTH) String cardNumber,

        /* CC-CUST-ID, width 9, CVCRD01Y line 40; numeric redefinition on line 42 is derived. */
        @Size(max = ScreenWorkArea.CUSTOMER_ID_LENGTH) String customerId) {

    /**
     * Fixed stand-in emitted by {@link #toString()} in place of each business key.
     *
     * <p>A constant rather than any transformation of the value, so nothing about a redacted key - not
     * its length, not a prefix or suffix, not a digest - survives into a stringified instance. A
     * partial mask was rejected deliberately: a truncated primary account number is still cardholder
     * data, and a digest of a nine- or eleven-character numeric key is trivially reversible by
     * enumeration.
     *
     * <p>Private because it is a rendering detail and not part of the screen work-area
     * contract.</p>
     */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    /**
     * Width in characters of the attention identifier: 5.
     *
     * <p>Declared {@code PIC X(5)} on line 3 of {@code app/cpy/CVCRD01Y.cpy}. Every one of the
     * sixteen condition-name literals beneath it therefore occupies exactly five positions, which
     * is why the two program-attention identifiers are space padded in the literal itself and why
     * {@link #attentionIdText()} must never trim what it returns.</p>
     */
    public static final int ATTENTION_ID_LENGTH = 5;

    /**
     * Width in characters of the next-program name: 8.
     *
     * <p>Declared {@code PIC X(8)} on line 21 of {@code app/cpy/CVCRD01Y.cpy}. This is a legacy
     * field width rather than a restriction invented here, and the bound that uses it only reports
     * over-long input - it never alters, pads or trims a value.</p>
     */
    public static final int NEXT_PROGRAM_LENGTH = 8;

    /**
     * Width in characters of the next-mapset name: 7.
     *
     * <p>Declared {@code PIC X(7)} on line 23 of {@code app/cpy/CVCRD01Y.cpy}. Declared separately
     * from {@link #NEXT_MAP_LENGTH} on purpose: the two widths are equal in the legacy structure
     * but they describe two different fields, so neither is derived from the other.</p>
     */
    public static final int NEXT_MAPSET_LENGTH = 7;

    /**
     * Width in characters of the next-map name: 7.
     *
     * <p>Declared {@code PIC X(7)} on line 24 of {@code app/cpy/CVCRD01Y.cpy}. Declared separately
     * from {@link #NEXT_MAPSET_LENGTH} for the reason given there.</p>
     */
    public static final int NEXT_MAP_LENGTH = 7;

    /**
     * Width in characters of the error message: 75.
     *
     * <p>Declared {@code PIC X(75)} on line 28 of {@code app/cpy/CVCRD01Y.cpy}. Message text is an
     * external contract, so the width is reproduced rather than rounded to a convenient
     * number.</p>
     */
    public static final int ERROR_MESSAGE_LENGTH = 75;

    /**
     * Width in characters of the return message: 75.
     *
     * <p>Declared {@code PIC X(75)} on line 29 of {@code app/cpy/CVCRD01Y.cpy}. Declared separately
     * from {@link #ERROR_MESSAGE_LENGTH} on purpose: the two widths coincide in the legacy
     * structure but the fields are independent, and a future divergence in one must not propagate
     * silently to the other.</p>
     */
    public static final int RETURN_MESSAGE_LENGTH = 75;

    /**
     * Width in characters of the account identifier: 11.
     *
     * <p>Declared {@code PIC X(11)} on line 34 of {@code app/cpy/CVCRD01Y.cpy}, with an unsigned
     * eleven-digit numeric redefinition over the same bytes on line 36.</p>
     */
    public static final int ACCOUNT_ID_LENGTH = 11;

    /**
     * Width in characters of the card number: 16.
     *
     * <p>Declared {@code PIC X(16)} on line 37 of {@code app/cpy/CVCRD01Y.cpy}, with an unsigned
     * sixteen-digit numeric redefinition over the same bytes on line 39. Sixteen digits exceed the
     * range of a 32-bit integer, which is why {@link #cardNumberNumeric()} yields an
     * arbitrary-precision value.</p>
     */
    public static final int CARD_NUMBER_LENGTH = 16;

    /**
     * Width in characters of the customer identifier: 9.
     *
     * <p>Declared {@code PIC X(09)} on line 40 of {@code app/cpy/CVCRD01Y.cpy}, with an unsigned
     * nine-digit numeric redefinition over the same bytes on line 42.</p>
     */
    public static final int CUSTOMER_ID_LENGTH = 9;

    /*
     * The canonical constructor generated for this record is intentionally left in place. There is
     * no compact constructor, no defaulting, no normalisation and no presence check, so a blank
     * identifier, a space-padded message and an absent key action all cross this boundary exactly
     * as supplied - which is what the legacy structure, whose three identifiers are initialised to
     * spaces and whose key field may be unrecognised, actually holds.
     */

    /**
     * Returns the attention key the operator pressed, with absence made explicit.
     *
     * <p>This is the same value as {@link #keyAction()}, wrapped so that a caller cannot overlook
     * the unmapped case. An empty result means the legacy key mapping recognised no branch for the
     * incoming identifier: the ordered conditional in {@code app/cpy/CSSTRPFY.cpy}, spanning lines
     * 21 to 78, has twenty-eight branches and no otherwise branch, so an unrecognised identifier
     * causes no assignment at all and leaves the previously held value intact.</p>
     *
     * <p>Callers must mirror that behaviour: on an empty result, retain whatever key action was
     * already in effect rather than substituting a placeholder. No constant of {@link KeyAction}
     * represents "unrecognised", and none may be invented for the purpose.</p>
     *
     * @return the resolved key action, or an empty {@link Optional} when no key action is present
     */
    public Optional<KeyAction> attentionKey() {
        return Optional.ofNullable(keyAction);
    }

    /**
     * Returns the attention identifier in its fixed-width textual form.
     *
     * <p>The underlying legacy field is {@link #ATTENTION_ID_LENGTH} characters wide, so each of
     * the sixteen identifiers is exactly that long and two of them - the program-attention pair -
     * are space padded in the literal itself. This accessor hands back the padded form verbatim,
     * exactly as {@link KeyAction} holds it: the padding is real data at real positions in a
     * fixed-width area, so it is never trimmed, normalised or case folded here.</p>
     *
     * @return the five-character identifier, or an empty {@link Optional} when no key action is
     *     present
     */
    public Optional<String> attentionIdText() {
        return attentionKey().map(KeyAction::getAid);
    }

    /**
     * Returns the account identifier read as a number, the derived equivalent of the unsigned
     * numeric redefinition declared on line 36 of {@code app/cpy/CVCRD01Y.cpy}.
     *
     * <p>Derived from {@link #accountId()} on every call rather than stored, so the textual and
     * numeric views can never disagree - precisely the guarantee a redefinition gives, since both
     * views describe one storage area. The stored text remains authoritative and keeps its leading
     * zeros; this view is for callers that need arithmetic or a numeric comparison, mirroring the
     * legacy programs' use of the alias to test the identifier against zero and to move it into a
     * numeric key field.</p>
     *
     * @return the numeric value of the whole account identifier, or an empty {@link Optional} when
     *     it is absent, empty, blank or not composed solely of digits
     */
    public Optional<BigInteger> accountIdNumeric() {
        return numericView(accountId);
    }

    /**
     * Returns the card number read as a number, the derived equivalent of the unsigned numeric
     * redefinition declared on line 39 of {@code app/cpy/CVCRD01Y.cpy}.
     *
     * <p>Derived from {@link #cardNumber()} on every call rather than stored, for the reason given
     * on {@link #accountIdNumeric()}. The value is arbitrary precision because
     * {@link #CARD_NUMBER_LENGTH} digits exceed the range of a 32-bit integer, so no narrowing can
     * silently lose a card number.</p>
     *
     * @return the numeric value of the whole card number, or an empty {@link Optional} when it is
     *     absent, empty, blank or not composed solely of digits
     */
    public Optional<BigInteger> cardNumberNumeric() {
        return numericView(cardNumber);
    }

    /**
     * Returns the customer identifier read as a number, the derived equivalent of the unsigned
     * numeric redefinition declared on line 42 of {@code app/cpy/CVCRD01Y.cpy}.
     *
     * <p>Derived from {@link #customerId()} on every call rather than stored, for the reason given
     * on {@link #accountIdNumeric()}. This member and its alias are declared in the structure but
     * referenced by none of the five including programs; both are modelled all the same, because
     * membership of the layout is what governs, not reference count.</p>
     *
     * @return the numeric value of the whole customer identifier, or an empty {@link Optional} when
     *     it is absent, empty, blank or not composed solely of digits
     */
    public Optional<BigInteger> customerIdNumeric() {
        return numericView(customerId);
    }

    /**
     * Reads a fixed-width textual identifier as an unsigned number, or reports that it cannot be
     * read as one.
     *
     * <p>The whole value is examined. Nothing is trimmed, stripped, padded or sliced: there is no
     * offset arithmetic and no fixed-width extraction anywhere in this type, because splitting a
     * record image into fields is the business of the fixed-width reader in the utility layer and
     * not of a transport object. Every character must be an ASCII digit for the value to be
     * numeric, which is why an explicit range test is used in preference to the library digit
     * predicate: that predicate also accepts digit code points outside the ASCII range, which a
     * single-byte fixed-width field cannot hold and which the legacy numeric alias would never
     * have read as a number.</p>
     *
     * <p>The method is total and never raises an exception. All three identifiers are initialised
     * to spaces in the legacy structure, so an absent, empty or blank value is an ordinary state
     * and is reported as an empty result rather than as a parse failure. Anything else that is not
     * wholly digits - a partially filled field, a left-justified value with trailing spaces, or
     * text - is likewise reported as absent, faithfully reflecting that the numeric alias over
     * those bytes does not hold a valid unsigned number.</p>
     *
     * <p>The result type is arbitrary precision so that one implementation serves all three widths
     * without any risk of overflow, and because the returned value is immutable the record remains
     * deeply immutable.</p>
     *
     * @param value the textual identifier exactly as stored, which may be {@code null}
     * @return the numeric value of the whole identifier, or an empty {@link Optional} when it is
     *     absent, empty, or contains any character that is not an ASCII digit
     */
    private static Optional<BigInteger> numericView(String value) {
        if (value == null || value.isEmpty()) {
            return Optional.empty();
        }
        for (int position = 0; position < value.length(); position++) {
            char character = value.charAt(position);
            if (character < '0' || character > '9') {
                return Optional.empty();
            }
        }
        return Optional.of(new BigInteger(value));
    }

    /**
     * Returns a diagnostic representation carrying the screen-control state and redacting the three
     * business keys.
     *
     * <p><strong>Why the implicit record rendering could not stand.</strong> A record's generated
     * {@code toString()} prints every component, and three of the nine here are the keys the five
     * card-and-account screens carry between turns: the account identifier, the card number - a primary
     * account number - and the customer identifier. This work area is populated on every turn of those
     * screens, so a default rendering would have written cardholder data into any log line, assertion
     * failure or diagnostic dump that touched an instance.
     *
     * <p><strong>What is retained.</strong> The six control components, which are exactly what makes
     * this type worth rendering: the resolved attention key, the declared next program, mapset and map,
     * and the two message slots. None of them is regulated. The two message slots carry catalogue text
     * destined for a terminal operator - they are written to be read by a human and are the first thing
     * anyone diagnosing a screen turn wants - so withholding them would remove the type's diagnostic
     * value without protecting anything.
     *
     * <p><strong>What is withheld.</strong> The three business keys, each replaced by a fixed
     * placeholder rather than a partial mask, for the reason given on the placeholder constant.
     *
     * <p>{@code equals} and {@code hashCode} remain as the record contract generates them: they compare
     * every component and emit nothing.
     *
     * @return the screen-control state, with the three business keys replaced by a fixed placeholder
     */
    @Override
    public String toString() {
        return "ScreenWorkArea["
                + "keyAction=" + keyAction
                + ", nextProgram=" + nextProgram
                + ", nextMapset=" + nextMapset
                + ", nextMap=" + nextMap
                + ", errorMessage=" + errorMessage
                + ", returnMessage=" + returnMessage
                + ", accountId=" + REDACTION_PLACEHOLDER
                + ", cardNumber=" + REDACTION_PLACEHOLDER
                + ", customerId=" + REDACTION_PLACEHOLDER
                + "]";
    }
}
