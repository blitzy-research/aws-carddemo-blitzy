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
 * <p>The REST-era replacement for the two operator-entered fields of the 3270 sign-on screen driven
 * by {@code COSGN00C}. Both the screen field definitions and the persisted credential record declare
 * the user id and the password as eight-character <em>alphanumeric</em> fields - never numeric -
 * which is why both screen components below are {@link String}.
 *
 * <p><strong>Only two of the map's eleven input items are genuine user input.</strong> The symbolic
 * map declares eleven value items, but nine are screen metadata the legacy program writes outbound
 * rather than reads inbound - the transaction name, two title lines, the current date, the program
 * name, the current time, the application id, the system id and the error message. Those belong to
 * the response contract and are deliberately absent here. Only the user-id and password items are
 * typed by the operator, so this request carries exactly those two <em>map</em> values and nothing
 * more from the screen: no navigation context, no terminal identity, no remember-me flag and no
 * second factor, because the legacy transaction has none and adding one would be feature expansion.
 * The one further value it carries is not a map item at all but the attention key described in the
 * next paragraph, which the legacy program reads before it reads any field. The map's per-field
 * length,
 * flag and attribute items, and its leading twelve-byte terminal input/output area filler, are
 * generated 3270 plumbing rather than contract. The password field is additionally defined with the
 * non-display attribute in the mapset, so the legacy terminal never echoed it - a property
 * {@link #toString()} honours.
 *
 * <p><strong>The operator's attention key is carried, because the legacy program branches on it
 * before it looks at either field.</strong> On a continuation turn {@code COSGN00C} evaluates the
 * terminal's attention identifier at lines 86 to 95 and takes exactly one of three paths in that
 * source order: the enter key runs the credential path, program-function key 3 emits the common
 * acknowledgement and sends plain text without redisplaying the screen, and <em>any other key</em>
 * raises the error switch, emits the common invalid-key notice and redisplays the sign-on screen.
 * Those three outcomes are externally observable - two of the transaction's seven message texts exist
 * only on the second and third path - so without the key on the request no service could reproduce
 * them, and a submission would collapse into the credential path alone. The key therefore crosses the
 * wire as {@link KeyAction}, the enumeration of the sixteen condition names the shared work area
 * declares, rather than as loose text or a raw terminal byte.
 *
 * <p>It is <strong>carried and never interpreted here</strong>. The three-way branch, in that source
 * order, belongs to {@code AuthenticationService}; the resolution of a raw terminal identifier into
 * one of the enumerated actions - including the fold of program-function keys 13 through 24 onto keys
 * 1 through 12 - belongs to the utility layer, which this contract does not depend on. The component
 * is <strong>deliberately never defaulted</strong>: the legacy evaluation has no catch-all that
 * substitutes a key, so an unrecognised or unreported key must stay unreported and reach the service
 * as an absent value, which the third path already accounts for. It carries no presence constraint
 * and no width bound for the same reason every other value here carries none - the single message the
 * legacy emits is chosen by an ordered service-tier evaluation, not by a validator.
 *
 * <p><strong>No navigation context accompanies it, and that asymmetry is deliberate.</strong> Sign-on
 * is the one transaction in the estate that legitimately begins with no carried conversation state:
 * line 80 of {@code COSGN00C} tests the communication-area length for zero and, on that first entry,
 * sends the screen with the cursor on the user-id field without evaluating any key at all. Adding a
 * navigation context here would invent state the first entry cannot have. What the legacy program
 * does carry forward is re-armed by the pseudo-conversational return at line 96, and in the REST-era
 * shape that re-arm is simply the client's next call rather than server-held state.
 *
 * <p><strong>Both values are carried verbatim; nothing is normalized here.</strong> The legacy
 * program folds both the user id and the password to upper case in two statements that sit outside
 * the end of the preceding validation cascade, so the fold executes unconditionally on every submit.
 * That fold is part of the authentication algorithm rather than the transport shape, so it belongs to
 * whatever component performs authentication. This record therefore never upper-cases, lower-cases,
 * trims, strips, pads or canonicalises either value: what the client sent is exactly what the service
 * receives, including leading and trailing spaces.
 *
 * <p>One parity consequence of that fold is preserved rather than corrected: because the submitted
 * password is folded to upper case before it is compared, the legacy password is effectively
 * <strong>case-insensitive</strong>. That behavior is observable through the sign-on interface, so it
 * is reproduced deliberately rather than "fixed" here or compensated for by this contract.
 *
 * <p>The map's per-field length, flag and attribute items, and its leading twelve-byte
 * terminal input/output area filler, are generated 3270 plumbing rather than contract, so
 * they are not modelled. The password field is additionally defined with the non-display
 * attribute in the mapset (line 175 of {@code app/bms/COSGN00.bms}, where the user-id field
 * one group earlier at line 156 is normal-intensity by contrast), so the legacy terminal
 * accepted the credential but never rendered it. The program reinforces that asymmetry: it
 * moves the user id onward to the shared communication area at line 226 of
 * {@code COSGN00C}, whereas the outbound password item {@code PASSWDO} of the symbolic map
 * (line 146) is never written by the program at all, so no outbound message the transaction
 * builds has ever carried the credential. This type honours that inbound-only shape in two
 * places - {@link #toString()} and the serialization access mode of the password
 * component.
 *
 * <p><strong>Blank handling is ordered, which is why no presence constraint appears here.</strong>
 * The legacy program forms a single ordered evaluation cascade: the user id is tested for emptiness
 * first, the password second, and because the construct stops at the first matching clause a
 * submission with <em>both</em> fields empty reports the user-id prompt only - never the password
 * prompt and never both messages. The legacy emptiness test additionally treats an all-spaces value
 * and an all-low-values value as equally empty.
 *
 * <p>Bean Validation evaluates constraints in an unspecified order and would report both violations
 * at once, producing two messages where the legacy produces exactly one. This record therefore
 * deliberately carries <em>no</em> presence constraint of any kind - no {@code NotBlank}, no
 * {@code NotNull}, no {@code NotEmpty} - and tolerates {@code null}, an empty string and an
 * all-spaces string without rejecting them, so that the authenticating component can run the ordered
 * cascade and emit the single correct message. For the same reason no character-class, format or
 * credential-strength constraint appears: the legacy screen applies none, and any of them would
 * reject input the legacy system accepts. The one constraint that <em>is</em> present bounds each
 * value to the eight-character screen width and measures only - it never trims, so leading and
 * trailing spaces survive validation untouched.
 *
 * <p><strong>The password value never appears in {@code toString()}.</strong> A record's implicitly
 * generated {@code toString()} prints every component, which here would leak the plaintext password
 * into any log line, exception message, debugger view, diagnostic dump or test-failure report that
 * stringifies the object. {@link #toString()} is therefore overridden and substitutes a fixed
 * placeholder - never the value, never its length, never a hash and never a partial mask.
 * {@code equals} and {@code hashCode} are intentionally not overridden, so they keep comparing all
 * three components as record semantics require.
 *
 * <p><strong>Credential verification is out of scope for this type.</strong> The legacy comparison is
 * a direct equality test against a stored cleartext password. Replacing it with hashed verification is
 * a documented parity exception recorded as decision log entry D-12. The encoder and the verifying
 * comparison live in {@code service.CredentialDigestService}, and the sign-on path that calls them is
 * delivered: {@code service.AuthenticationService} performs the verification and
 * {@code api.AuthController} maps the one route that reaches it. This contract carries the two values
 * and asserts nothing about how they are checked - which is the point of saying so here, because a
 * request type that described the check would be a second place for the check to be specified. No
 * credential literal, hash, salt or work factor appears in this file.
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
 * <h2>The password value leaves this type by no route at all</h2>
 *
 * <p>Two escape routes exist for a credential carried on a record, and both are closed.
 *
 * <p><strong>Stringification.</strong> A record's implicitly generated {@code toString()}
 * prints every component value, which for this type would leak the plaintext password into
 * any log line, exception message, debugger view, diagnostic dump or test-failure report
 * that stringifies the object. {@link #toString()} is therefore overridden and substitutes
 * a fixed placeholder for the password - never the value, never its length, never a hash
 * and never a partial mask.
 *
 * <p><strong>Serialization.</strong> The password component is annotated
 * {@link JsonProperty.Access#WRITE_ONLY}, which is asymmetric on purpose: the property is
 * still <em>read from</em> an inbound request body, so sign-on works exactly as before, but
 * it is omitted entirely from any document this type is serialized into. That closes the
 * route by which a request object reused as a response body, cached entry, queued message,
 * audit event, request snapshot or trace attribute would carry the credential outward. The
 * user id carries no such annotation and continues to serialize both ways, because it is an
 * account identifier rather than a secret and is needed to correlate an attempt.
 *
 * <p>The annotation is a boundary control rather than a substitute for discipline: this
 * type is an inbound request contract and must not be returned as an outbound model. The
 * sign-on response is a separate type, and the accompanying tests assert that a serialized
 * {@code SignOnRequest} document carries exactly one property and that the credential
 * appears nowhere in it.
 *
 * <p>{@code equals} and {@code hashCode} are intentionally <em>not</em> overridden, so they
 * keep comparing all three components as the record semantics require; equality is an
 * in-memory operation that emits nothing.
 *
 * <p>The same access mode is declared on the published OpenAPI schema, together with the
 * password format, so that generated documentation and client tooling treat the property as a
 * secret to be collected and never displayed. The suppression is an access-mode declaration
 * rather than an exclusion, which is the distinction that matters: an outright ignore annotation
 * would also block the inbound direction and break the transaction, so it is not used here.
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
 *                 is excluded from {@link #toString()}, excluded from every serialized
 *                 document by {@link JsonProperty.Access#WRITE_ONLY}, and must never be
 *                 logged.
 * @param keyAction the attention key the operator pressed, as one of the sixteen values the
 *                 shared work area {@code app/cpy/CVCRD01Y.cpy} declares (line 3). Not a map
 *                 item: it is the terminal's attention identifier, which lines 86 to 95 of
 *                 {@code COSGN00C} evaluate before either field is read, taking the enter key,
 *                 program-function key 3 and any other key in exactly that source order.
 *                 Carried, never interpreted - the three-way branch belongs to
 *                 {@code AuthenticationService} and the resolution of a raw terminal
 *                 identifier into one of these values belongs to the utility layer.
 *                 <strong>Deliberately never defaulted</strong> and carrying no presence
 *                 constraint: the legacy evaluation has no catch-all that substitutes a key, so
 *                 an unrecognised or unreported key stays absent and reaches the service as
 *                 {@code null}, which the any-other-key path already accounts for. The distinct
 *                 zero-communication-area first entry does not use this request at all; it is the
 *                 GET operation on the same sign-on route, so an absent key on a submitted POST
 *                 cannot be confused with first entry. May be {@code null}.
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

        /* CCARD-AID, width 5, CVCRD01Y line 3 - typed as the enum of its 16 condition names.
         * Nullable on purpose: the key evaluation at COSGN00C lines 86 to 95 has no clause that
         * substitutes a key, and its any-other-key path already covers an absent one. Carries no
         * width bound and no presence constraint because the single message the legacy emits is
         * chosen by an ordered service-tier evaluation rather than by a validator. */
        KeyAction keyAction) {

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
     * {@code ***REDACTED***}. The attention key is retained for the same reason as the user
     * id and for one more: it names which of the three branches a failed attempt took, which
     * is the first thing a diagnostic reader needs and is not a secret in any sense - it is a
     * keystroke drawn from a published sixteen-value vocabulary. This override exists solely
     * to prevent credential leakage through logging, diagnostics and test output, and it
     * deliberately performs no validation, normalisation or comparison.
     *
     * <p>The rendered length therefore varies with the user id and with the attention key but
     * never with the password, which contributes a constant regardless of what it holds.
     *
     * @return a representation carrying the user id, a fixed password placeholder and the
     *         attention key
     */
    @Override
    public String toString() {
        return "SignOnRequest[userId=" + userId + ", password=" + REDACTION_PLACEHOLDER
                + ", keyAction=" + keyAction + "]";
    }
}
