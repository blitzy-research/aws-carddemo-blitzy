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
import java.math.BigInteger;
import java.util.Optional;

/**
 * The screen work area as a type the service layer owns: the resolved attention key, the three
 * declarative next-screen fields, the two message slots and the three business keys.
 *
 * <p>The legacy antecedent is {@code CC-WORK-AREAS} in {@code app/cpy/CVCRD01Y.cpy}, included by the five
 * programs of the account and card family. Nine members are modelled, matching the nine live members of
 * that structure; the numeric redefinitions of the three business keys are not stored but derived, which
 * is what a redefinition is - two views of one storage area that can never disagree.
 *
 * <p><strong>Why this is not the wire record.</strong> The wire form lives in {@code api.dto} with its own
 * width bounds and its own serialization contract, and the module's layering forbids a service depending
 * upward on the API package. This type carries the same nine components under the same names, and one
 * adapter in the API layer converts between the two, so a service names only types its own layer owns. No
 * width bound is declared here, because bounding a submitted value belongs to the transport boundary; the
 * widths are published as constants because a service legitimately reasons about them.
 *
 * <p><strong>Nothing is trimmed, padded or folded.</strong> Every value is stored exactly as supplied,
 * including {@code null}, the empty string and any legacy space padding, because the members it derives
 * from are fixed width and blank significant, and because two of them are operator-facing message slots
 * whose padding is part of the screen contract.
 *
 * <p><strong>The attention key has no catch-all.</strong> The legacy key mapping at
 * {@code app/cpy/CSSTRPFY.cpy} has twenty-eight branches and no otherwise branch, so an unrecognised
 * identifier causes no assignment at all and leaves whatever was previously in effect intact. The
 * component is therefore nullable on purpose and {@link #attentionKey()} answers an empty result rather
 * than a placeholder; no constant of {@link KeyAction} represents "unrecognised" and none may be invented.
 *
 * <p>{@link #toString()} withholds the three business keys and both message slots, because the keys are
 * cardholder and account data and the legacy assembles its messages by concatenating an identifier into
 * their leading characters.
 *
 * <p>Deeply immutable: every component is a {@code String} or an enumeration constant and every derived
 * view returns a fresh immutable value, so an instance is safe for unsynchronised concurrent use.
 *
 * <p>Provenance: {@code app/cpy/CVCRD01Y.cpy} and {@code app/cpy/CSSTRPFY.cpy}, read as read-only
 * reference at checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No copybook text is transcribed.
 *
 * @param keyAction the resolved attention key, or {@code null} when the identifier resolved to none
 * @param nextProgram the declarative next-program field, never dispatched, or {@code null}
 * @param nextMapset the declarative next-mapset field, or {@code null}
 * @param nextMap the declarative next-map field, or {@code null}
 * @param errorMessage the error message slot exactly as assembled, or {@code null}
 * @param returnMessage the return message slot exactly as assembled, or {@code null}
 * @param accountId the account identifier exactly as submitted, or {@code null}
 * @param cardNumber the card number exactly as submitted, or {@code null}
 * @param customerId the customer identifier exactly as submitted, or {@code null}
 * @since 1.0.0
 */
public record ScreenInputState(
        KeyAction keyAction,
        String nextProgram,
        String nextMapset,
        String nextMap,
        String errorMessage,
        String returnMessage,
        String accountId,
        String cardNumber,
        String customerId) {

    /** Width in characters of the attention identifier: 5. */
    public static final int ATTENTION_ID_LENGTH = 5;

    /** Width in characters of the next-program field: 8. */
    public static final int NEXT_PROGRAM_LENGTH = 8;

    /** Width in characters of the next-mapset field: 7. */
    public static final int NEXT_MAPSET_LENGTH = 7;

    /** Width in characters of the next-map field: 7. */
    public static final int NEXT_MAP_LENGTH = 7;

    /** Width in characters of the error message slot: 75. */
    public static final int ERROR_MESSAGE_LENGTH = 75;

    /** Width in characters of the return message slot: 75. */
    public static final int RETURN_MESSAGE_LENGTH = 75;

    /** Width in characters of the account identifier: 11. */
    public static final int ACCOUNT_ID_LENGTH = 11;

    /** Width in characters of the card number: 16. */
    public static final int CARD_NUMBER_LENGTH = 16;

    /** Width in characters of the customer identifier: 9. */
    public static final int CUSTOMER_ID_LENGTH = 9;

    /**
     * Fixed stand-in emitted by {@link #toString()} in place of each withheld component: the three
     * business keys and the two message slots.
     *
     * <p>A constant rather than any transformation of the value. A partial mask was rejected
     * deliberately: a truncated primary account number is still cardholder data, a digest of a nine- or
     * eleven-character numeric key is trivially reversible by enumeration, and a truncated message slot
     * would still expose whichever identifier the legacy concatenated into its leading characters.
     */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    /** Shared all-null instance: a turn that carries no screen input at all. */
    private static final ScreenInputState EMPTY =
            new ScreenInputState(null, null, null, null, null, null, null, null, null);

    /**
     * Returns the empty input state, which is what an absent submission resolves to.
     *
     * @return the shared all-null instance
     */
    public static ScreenInputState empty() {
        return EMPTY;
    }

    /**
     * Returns the resolved attention key, or reports that the identifier resolved to none.
     *
     * <p>A caller must mirror the legacy behaviour on an empty result: retain whatever key action was
     * already in effect rather than substituting a placeholder.
     *
     * @return the resolved key action, or an empty result when none is present
     */
    public Optional<KeyAction> attentionKey() {
        return Optional.ofNullable(keyAction);
    }

    /**
     * Returns the attention identifier in its fixed-width textual form.
     *
     * <p>The padded form verbatim: the padding is real data at real positions in a fixed-width area, so
     * it is never trimmed, normalised or case folded here.
     *
     * @return the five-character identifier, or an empty result when no key action is present
     */
    public Optional<String> attentionIdText() {
        return attentionKey().map(KeyAction::getAid);
    }

    /**
     * Returns the account identifier read as a number, the derived equivalent of the legacy unsigned
     * numeric redefinition.
     *
     * @return the numeric value of the whole identifier, or an empty result when it is absent, empty or
     *         not composed solely of ASCII digits
     */
    public Optional<BigInteger> accountIdNumeric() {
        return numericView(accountId);
    }

    /**
     * Returns the card number read as a number, the derived equivalent of the legacy unsigned numeric
     * redefinition.
     *
     * @return the numeric value of the whole card number, or an empty result when it is absent, empty or
     *         not composed solely of ASCII digits
     */
    public Optional<BigInteger> cardNumberNumeric() {
        return numericView(cardNumber);
    }

    /**
     * Returns the customer identifier read as a number, the derived equivalent of the legacy unsigned
     * numeric redefinition.
     *
     * @return the numeric value of the whole identifier, or an empty result when it is absent, empty or
     *         not composed solely of ASCII digits
     */
    public Optional<BigInteger> customerIdNumeric() {
        return numericView(customerId);
    }

    /**
     * Reads a fixed-width textual identifier as an unsigned number, or reports that it cannot be read as
     * one.
     *
     * <p>The whole value is examined. Nothing is trimmed, stripped, padded or sliced: splitting a record
     * image into fields is the business of the fixed-width reader in the utility layer. Every character
     * must be an ASCII digit, which is why an explicit range test is used in preference to the library
     * digit predicate: that predicate also accepts digit code points outside the ASCII range, which a
     * single-byte fixed-width field cannot hold and which the legacy numeric alias would never have read
     * as a number.
     *
     * <p>The method is total and never raises. All three identifiers are initialised to spaces in the
     * legacy structure, so an absent, empty or blank value is an ordinary state and is reported as an
     * empty result rather than as a parse failure.
     *
     * @param value the textual identifier exactly as stored, which may be {@code null}
     * @return the numeric value of the whole identifier, or an empty result
     */
    private static Optional<BigInteger> numericView(final String value) {
        if (value == null || value.isEmpty()) {
            return Optional.empty();
        }
        for (int position = 0; position < value.length(); position++) {
            final char character = value.charAt(position);
            if (character < '0' || character > '9') {
                return Optional.empty();
            }
        }
        return Optional.of(new BigInteger(value));
    }

    /**
     * Returns a diagnostic representation carrying the screen-routing state and withholding the three
     * business keys and both message slots.
     *
     * <p>Only the rendering changes: the accessors, {@code equals} and {@code hashCode} continue to carry
     * and compare every component byte for byte, because an operator-facing message is part of the screen
     * contract and must reach the terminal unaltered.
     *
     * @return the screen-routing state, with the keys and message slots replaced by a fixed placeholder
     */
    @Override
    public String toString() {
        return "ScreenInputState["
                + "keyAction=" + keyAction
                + ", nextProgram=" + nextProgram
                + ", nextMapset=" + nextMapset
                + ", nextMap=" + nextMap
                + ", errorMessage=" + REDACTION_PLACEHOLDER
                + ", returnMessage=" + REDACTION_PLACEHOLDER
                + ", accountId=" + REDACTION_PLACEHOLDER
                + ", cardNumber=" + REDACTION_PLACEHOLDER
                + ", customerId=" + REDACTION_PLACEHOLDER
                + "]";
    }
}
