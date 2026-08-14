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
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;

/**
 * Immutable sign-on request contract for legacy CICS transaction {@code CC00}.
 *
 * <p>The REST-era replacement for the two operator-entered fields of the 3270 sign-on screen driven by
 * {@code COSGN00C}. Both the screen field definitions and the persisted credential record declare the
 * user id and the password as eight-character <em>alphanumeric</em> fields - never numeric - which is why
 * both screen components below are {@link String}.
 *
 * <p><strong>Only two of the map's eleven input items are genuine user input.</strong> The other nine are
 * screen metadata the program writes outbound rather than reads inbound - the transaction name, two title
 * lines, the current date, the program name, the current time, the application id, the system id and the
 * error message - and they belong to the response contract. So this request carries exactly two
 * <em>map</em> values and nothing more from the screen: no navigation context, no terminal identity, no
 * remember-me flag and no second factor, because the legacy transaction has none and adding one would be
 * feature expansion. The map's per-field length, flag and attribute items, and its leading twelve-byte
 * terminal input/output area filler, are generated 3270 plumbing rather than contract.
 *
 * <h2>The attention key is carried, because the program branches on it before it reads either field</h2>
 *
 * <p>On a continuation turn {@code COSGN00C} evaluates the terminal's attention identifier at lines 86 to
 * 95 and takes exactly one of three paths in that source order: the enter key runs the credential path,
 * program-function key 3 emits the common acknowledgement and sends plain text without redisplaying the
 * screen, and <em>any other key</em> raises the error switch, emits the common invalid-key notice and
 * redisplays the screen. Those outcomes are externally observable - two of the transaction's seven message
 * texts exist only on the second and third path - so without the key on the request no service could
 * reproduce them and a submission would collapse into the credential path alone. The key crosses the wire
 * as {@link KeyAction}, the enumeration of the sixteen condition names the shared work area declares,
 * rather than as loose text or a raw terminal byte.
 *
 * <p>It is <strong>carried and never interpreted here</strong>. The three-way branch in that source order
 * belongs to {@code AuthenticationService}, and resolving a raw terminal identifier into one of the
 * enumerated actions - including the fold of program-function keys 13 through 24 onto keys 1 through 12 -
 * belongs to the utility layer. The component is <strong>deliberately never defaulted</strong>: the legacy
 * evaluation has no catch-all that substitutes a key, so an unrecognised or unreported key must stay
 * unreported and reach the service as an absent value, which the third path already accounts for.
 *
 * <p><strong>No navigation context accompanies it, and that asymmetry is deliberate.</strong> Sign-on is
 * the one transaction in the estate that legitimately begins with no carried conversation state: line 80
 * tests the communication-area length for zero and, on that first entry, sends the screen with the cursor
 * on the user-id field without evaluating any key at all. Adding a navigation context here would invent
 * state the first entry cannot have. What the legacy carries forward is re-armed by the
 * pseudo-conversational return at line 96, and in the REST-era shape that re-arm is the client's next call
 * rather than server-held state.
 *
 * <h2>Both values are carried verbatim, and the case-insensitivity that follows is deliberate</h2>
 *
 * <p>The legacy program folds both the user id and the password to upper case in two statements that sit
 * outside the end of the preceding validation cascade, so the fold executes unconditionally on every
 * submit. That fold is part of the authentication algorithm rather than the transport shape, so it belongs
 * to whatever component performs authentication; this record never upper-cases, lower-cases, trims,
 * strips, pads or canonicalises either value, and what the client sent is exactly what the service
 * receives, including leading and trailing spaces.
 *
 * <p>One parity consequence is preserved rather than corrected: because the submitted password is folded
 * to upper case before it is compared, the legacy password is effectively
 * <strong>case-insensitive</strong>. That behaviour is observable through the sign-on interface, so it is
 * reproduced deliberately rather than "fixed" here or compensated for by this contract.
 *
 * <h2>Blank handling is ordered, which is why no presence constraint appears here</h2>
 *
 * <p>Lines 118 to 131 form a single ordered evaluation cascade: the user id is tested for emptiness first,
 * the password second, and because the construct stops at the first matching clause a submission with
 * <em>both</em> fields empty reports the user-id prompt only - never the password prompt and never both
 * messages. The legacy emptiness test additionally treats an all-spaces value and an all-low-values value
 * as equally empty.
 *
 * <p>Bean Validation evaluates constraints in an unspecified order and would report both violations at
 * once, producing two messages where the legacy produces exactly one. This record therefore deliberately
 * carries <em>no</em> presence constraint of any kind - no {@code NotBlank}, {@code NotNull} or
 * {@code NotEmpty} - and tolerates {@code null}, an empty string and an all-spaces string without
 * rejecting them, so that the authenticating component can run the ordered cascade and emit the single
 * correct message. No format or credential-strength constraint appears either, because the legacy screen
 * applies none and any of them would reject input the legacy system accepts. The width bounds measure each
 * value against the eight-character screen width and never trim, so leading and trailing spaces survive
 * validation untouched.
 *
 * <p>The one character-class rule on this contract is a transport control rather than a legacy validation
 * rule: the user id refuses control and format characters, which no 3270 field could have carried and
 * which would otherwise reach a log record or a response header. It rejects nothing the legacy screen
 * accepts, and it deliberately has no counterpart on the credential, whose every byte must reach the
 * verifier unexamined.
 *
 * <h2>The password value leaves this type by no route at all</h2>
 *
 * <p>The mapset defines the password field with the non-display attribute at line 175 of
 * {@code app/bms/COSGN00.bms}, where the user-id field one group earlier at line 156 is normal-intensity
 * by contrast, so the legacy terminal accepted the credential and never rendered it. The program
 * reinforces the asymmetry: it moves the user id onward to the shared communication area at line 226,
 * whereas the outbound password item {@code PASSWDO} of the symbolic map at line 146 is never written at
 * all, so no outbound message the transaction builds has ever carried the credential. Two escape routes
 * exist for a credential carried on a record and both are closed.
 *
 * <p><strong>Stringification.</strong> A record's implicitly generated {@code toString()} prints every
 * component value, which here would leak the plaintext password into any log line, exception message,
 * debugger view, diagnostic dump or test-failure report that stringifies the object. {@link #toString()}
 * is therefore overridden and substitutes a fixed placeholder - never the value, its length, a hash or a
 * partial mask. {@code equals} and {@code hashCode} are intentionally <em>not</em> overridden, so they keep
 * comparing all three components as record semantics require; equality is an in-memory operation that
 * emits nothing.
 *
 * <p><strong>Serialization.</strong> The password component is annotated
 * {@link JsonProperty.Access#WRITE_ONLY}, which is asymmetric on purpose: the property is still
 * <em>read from</em> an inbound request body, so sign-on works exactly as before, but it is omitted
 * entirely from any document this type is serialized into - closing the route by which a request object
 * reused as a response body, cached entry, queued message, audit event, request snapshot or trace
 * attribute would carry the credential outward. The user id carries no such annotation and continues to
 * serialize both ways, because it is an account identifier rather than a secret and is needed to correlate
 * an attempt. The same access mode is declared on the published OpenAPI schema together with the password
 * format, so generated documentation and client tooling treat the property as a secret to be collected and
 * never displayed. An access-mode declaration rather than an outright ignore annotation is the distinction
 * that matters: an ignore would also block the inbound direction and break the transaction. The annotation
 * is a boundary control rather than a substitute for discipline - this type is an inbound request contract
 * and must not be returned as an outbound model, and the accompanying tests assert that a serialized
 * document carries exactly one property and that the credential appears nowhere in it.
 *
 * <p>Credential verification itself is out of scope for this type. The legacy plaintext comparison at line
 * 223 is replaced by hashed verification: the encoder and the verifying comparison live in
 * {@code service.CredentialDigestService}, {@code service.AuthenticationService} performs the verification
 * and {@code api.AuthController} maps the one route that reaches it. That substitution is a documented
 * parity exception recorded as decision log entry D-12, and no credential literal, hash, salt or work
 * factor appears in this file. Saying so here matters, because a request type that described the check
 * would be a second place for the check to be specified.
 *
 * @param userId   the operator-entered user id, corresponding to the {@code USERIDI} item of symbolic map
 *                 {@code COSGN00} (line 72) and to the eight-character user-id field of the credential
 *                 record {@code CSUSR01Y} (line 18). Bounded to eight characters, refused only for control
 *                 or format characters, and otherwise carried unaltered: not upper-cased, trimmed or
 *                 padded here, and permitted to be {@code null}, empty or blank so that the ordered blank
 *                 cascade stays in the service layer.
 * @param password the operator-entered password, corresponding to the {@code PASSWDI} item of symbolic map
 *                 {@code COSGN00} (line 78) and to the eight-character password field of the credential
 *                 record {@code CSUSR01Y} (line 21). Bounded to eight characters and carried unaltered,
 *                 and likewise permitted to be {@code null}, empty or blank. This value is a secret: it is
 *                 excluded from {@link #toString()}, excluded from every serialized document by
 *                 {@link JsonProperty.Access#WRITE_ONLY}, and must never be logged.
 * @param keyAction the attention key the operator pressed, as one of the sixteen values the shared work
 *                 area {@code app/cpy/CVCRD01Y.cpy} declares (line 3). Not a map item: it is the
 *                 terminal's attention identifier, which lines 86 to 95 evaluate before either field is
 *                 read. Carried, never interpreted, <strong>deliberately never defaulted</strong> and
 *                 carrying no width bound or presence constraint, so an unrecognised or unreported key
 *                 stays absent and reaches the service as {@code null}, which the any-other-key path
 *                 accounts for. The zero-communication-area first entry does not use this request at all -
 *                 it is the GET operation on the same route - so an absent key on a submitted POST cannot
 *                 be confused with first entry. May be {@code null}.
 */
public record SignOnRequest(
        @Size(max = 8)
        @Pattern(regexp = "^[^\\p{Cc}\\p{Cf}]*$",
                message = "must not contain control or format characters")
        String userId,
        @Size(max = 8)
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @Schema(accessMode = Schema.AccessMode.WRITE_ONLY, format = "password",
                description = "Operator-entered sign-on password, at most eight characters. "
                        + "Accepted on request only and never returned in any response.")
        String password,
        KeyAction keyAction) {

    /**
     * Fixed stand-in emitted by {@link #toString()} in place of the password.
     *
     * <p>A constant placeholder rather than any transformation of the secret, so nothing about the
     * password - not its value, length, prefix, suffix or digest - can be recovered from a stringified
     * instance. The constant is deliberately named without the word it stands in for, so a credential scan
     * of this module cannot mistake it for a transcribed secret.
     */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    /**
     * Returns a diagnostic representation that mirrors the record layout but redacts the password.
     *
     * <p>The user id is retained because it is an account identifier rather than a secret and is required
     * to correlate a sign-on attempt. The attention key is retained for the same reason and for one more:
     * it names which of the three branches a failed attempt took, which is the first thing a diagnostic
     * reader needs and is a keystroke drawn from a published sixteen-value vocabulary. The rendered length
     * therefore varies with the user id and the attention key but never with the password, which
     * contributes a constant regardless of what it holds. This override performs no validation,
     * normalisation or comparison.
     *
     * @return a representation carrying the user id, a fixed password placeholder and the attention key
     */
    @Override
    public String toString() {
        return "SignOnRequest[userId=" + userId + ", password=" + REDACTION_PLACEHOLDER
                + ", keyAction=" + keyAction + "]";
    }
}
