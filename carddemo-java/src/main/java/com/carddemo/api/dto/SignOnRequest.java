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

import jakarta.validation.constraints.Size;

/**
 * Immutable sign-on request contract for legacy CICS transaction {@code CC00}.
 *
 * <p>This record is the REST-era replacement for the two operator-entered fields of the
 * 3270 sign-on screen driven by {@code app/cbl/COSGN00C.cbl}, the program bound to
 * transaction {@code CC00} in {@code app/csd/CARDDEMO.CSD}, through mapset
 * {@code COSGN00}. The screen layout lives in {@code app/bms/COSGN00.bms}, where the
 * user-id field is defined at line 156 and the password field at line 175, each declared
 * eight characters wide. The generated symbolic map that the program actually reads is
 * {@code app/cpy-bms/COSGN00.CPY}. The persisted credential record behind the transaction
 * is {@code app/cpy/CSUSR01Y.cpy}, whose user-id field (line 18) and password field
 * (line 21) are each eight-character alphanumeric fields - never numeric - which is why
 * both components below are {@code String} and not a numeric type.
 *
 * <h2>Only two of the map's eleven input items are genuine user input</h2>
 *
 * <p>The symbolic map's input structure declares eleven value items, but nine of them are
 * screen metadata that the legacy program writes outbound rather than reads inbound: the
 * transaction name, two title lines, the current date, the program name, the current time,
 * the application id, the system id and the error message. Those nine belong to the
 * response contract, {@code SignOnResponse}, and are deliberately absent here. Only
 * {@code USERIDI} (symbolic map line 72) and {@code PASSWDI} (line 78) are typed by the
 * operator, so this request carries exactly those two values and nothing more - no
 * navigation context, no terminal identity, no remember-me flag and no second factor,
 * because the legacy transaction has none and adding one would be feature expansion.
 *
 * <p>The map's per-field length, flag and attribute items, and its leading twelve-byte
 * terminal input/output area filler, are generated 3270 plumbing rather than contract, so
 * they are not modelled. The password field is additionally defined with the non-display
 * attribute in the mapset, meaning the legacy terminal never echoed it - a property this
 * type honours in {@link #toString()}.
 *
 * <h2>Both values are carried verbatim; nothing is normalised here</h2>
 *
 * <p>Program {@code COSGN00C} folds both the user id and the password to upper case at
 * lines 132-136, and those two statements sit outside the end of the preceding validation
 * cascade, so the fold executes unconditionally on every ENTER. That fold is part of the
 * authentication algorithm rather than part of the transport shape, so it is performed by
 * {@code AuthenticationService}. This record therefore never upper-cases, lower-cases,
 * trims, strips, pads, canonicalises or otherwise alters either value: what the client
 * sent is exactly what the service receives, including leading and trailing spaces.
 *
 * <p>One parity consequence of that fold is documented here and is preserved rather than
 * corrected: because the submitted password is folded to upper case before it is compared,
 * the legacy password is effectively <strong>case-insensitive</strong>. That behaviour is
 * observable through the sign-on interface, so it is reproduced deliberately and is
 * recorded in {@code docs/decision-log.md}; it is neither "fixed" here nor compensated for
 * by this contract.
 *
 * <h2>Blank handling is ordered, which is why no presence constraint appears here</h2>
 *
 * <p>Lines 118-131 of {@code COSGN00C} form a single ordered evaluation cascade: the user
 * id is tested for emptiness first, the password second, and because the construct stops
 * at the first matching clause a submission with <em>both</em> fields empty reports the
 * user-id prompt only - never the password prompt and never both messages. The legacy
 * emptiness test additionally treats an all-spaces value and an all-low-values value as
 * equally empty.
 *
 * <p>Bean Validation evaluates constraints in an unspecified order and would report both
 * violations at once, producing two messages where the legacy produces exactly one. This
 * record therefore deliberately carries <em>no</em> presence constraint of any kind - no
 * {@code NotBlank}, {@code NotNull} and no {@code NotEmpty} - and tolerates {@code null},
 * an empty string and an all-spaces string without rejecting them, so that
 * {@code AuthenticationService} can run the ordered cascade and emit the single correct
 * message. For the same reason no character-class, format or credential-strength constraint
 * appears: the legacy screen applies none, and any of them would reject input the legacy
 * system accepts. The one constraint that <em>is</em> present bounds each value to the
 * eight-character screen width and measures only - it never trims, so leading and trailing
 * spaces survive validation untouched.
 *
 * <h2>The password value never appears in {@code toString()}</h2>
 *
 * <p>A record's implicitly generated {@code toString()} prints every component value,
 * which for this type would leak the plaintext password into any log line, exception
 * message, debugger view, diagnostic dump or test-failure report that stringifies the
 * object. {@link #toString()} is therefore overridden and substitutes a fixed placeholder
 * for the password - never the value, never its length, never a hash and never a partial
 * mask. {@code equals} and {@code hashCode} are intentionally <em>not</em> overridden, so
 * they keep comparing both components as the record semantics require, and the password
 * component carries no serialization annotation because it must still deserialize from the
 * request body for sign-on to work.
 *
 * <p>Credential verification itself is out of scope for this type. The legacy plaintext
 * password comparison at line 223 of {@code COSGN00C} is replaced by hashed verification
 * behind {@code AuthenticationService}; that substitution is a documented parity exception
 * recorded in {@code docs/decision-log.md}, and no credential literal, hash, salt or work
 * factor appears in this file.
 *
 * <h2>Provenance</h2>
 *
 * <p>Translated from the CardDemo COBOL estate at checkout commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The estate under {@code app/} is
 * read-only reference: it is cited here by member name, field name, field width and line
 * number only, and no COBOL text is reproduced.
 *
 * @param userId   the operator-entered user id, corresponding to the {@code USERIDI} item
 *                 of symbolic map {@code COSGN00} (line 72) and to the eight-character
 *                 user-id field of the credential record {@code CSUSR01Y} (line 18).
 *                 Bounded to eight characters and carried unaltered: it is not
 *                 upper-cased, trimmed or padded here, and it may be {@code null}, empty
 *                 or blank so that the ordered blank cascade stays in the service layer.
 * @param password the operator-entered password, corresponding to the {@code PASSWDI} item
 *                 of symbolic map {@code COSGN00} (line 78) and to the eight-character
 *                 password field of the credential record {@code CSUSR01Y} (line 21).
 *                 Bounded to eight characters and carried unaltered, and likewise
 *                 permitted to be {@code null}, empty or blank. This value is a secret: it
 *                 is excluded from {@link #toString()} and must never be logged.
 */
public record SignOnRequest(
        @Size(max = 8) String userId,
        @Size(max = 8) String password) {

    /**
     * Fixed stand-in emitted by {@link #toString()} in place of the password.
     *
     * <p>The text is a constant placeholder rather than any transformation of the secret,
     * so no information about the password - not its value, not its length and not a
     * prefix, suffix or digest of it - can be recovered from a stringified instance. The
     * constant is deliberately named without the word it stands in for, so that a
     * credential scan of this module cannot mistake it for a transcribed secret.
     */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    /**
     * Returns a diagnostic representation that mirrors the record layout but redacts the
     * password.
     *
     * <p>The user id is retained because it is an account identifier rather than a secret
     * and is required to correlate a sign-on attempt; the password is replaced by
     * {@code ***REDACTED***}. This override exists solely to prevent credential leakage
     * through logging, diagnostics and test output, and it deliberately performs no
     * validation, normalisation or comparison.
     *
     * @return a representation carrying the user id and a fixed password placeholder
     */
    @Override
    public String toString() {
        return "SignOnRequest[userId=" + userId + ", password=" + REDACTION_PLACEHOLDER + "]";
    }
}
