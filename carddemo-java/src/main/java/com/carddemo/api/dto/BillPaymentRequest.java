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

/**
 * Immutable bill-payment request contract for legacy CICS transaction {@code CB00}.
 *
 * <p>The REST-era replacement for the two operator-typed fields of the 3270 bill-payment screen. The
 * behaviour being reproduced lives in {@code app/cbl/COBIL00C.cbl} - 572 lines across sixteen
 * paragraphs - the screen geometry in mapset {@code app/bms/COBIL00.bms}, and the field-level
 * contract in symbolic map {@code app/cpy-bms/COBIL00.CPY}. This is the smallest request contract in
 * the package, and deliberately so: the transaction asks the operator for an account and for a
 * single confirmation character, and derives every other value on the server.
 *
 * <h2>Two of the map's ten value items are operator input; eight are not</h2>
 *
 * <p>The symbolic map declares ten value items, and its output group redefines its input group, so
 * every item appears on both sides at identical width. That parity is a code-generation property of
 * every symbolic map in the estate rather than a statement about direction, and reading it as
 * permission to accept all ten inbound is the easiest mistake to make here. Only two items are typed
 * by the operator, and this record carries exactly those two and nothing else.
 *
 * <p>Seven of the eight excluded items are unambiguous. Six are screen metadata the program writes
 * outbound while assembling the screen - the transaction name, the two title lines, the program
 * name, and the current date and time - and the seventh is the seventy-eight-character diagnostic
 * line the program writes when it has something to report. All seven are server-produced and belong
 * to the response contract. The map's per-field length, flag and attribute items, and its leading
 * twelve-byte terminal input/output area filler, are generated 3270 plumbing rather than contract and
 * are not modelled anywhere in this type.
 *
 * <h2>The eighth exclusion is the load-bearing one: the fourteen-character display field</h2>
 *
 * <p>The map declares a fourteen-character field carrying the account's outstanding total, and it
 * declares it on the input side for the parity reason above. It is nevertheless <strong>not</strong>
 * a request component, and the mapset settles the question independently of any argument about
 * intent: that field is defined auto-skip at line 103 of {@code app/bms/COBIL00.bms}, so the
 * terminal cursor could never enter it and the operator could never type into it, whereas the two
 * fields this record does carry are defined unprotected at lines 85 and 115. The program only ever
 * writes it, moving the pre-settlement figure out of the account record and onto the screen at lines
 * 193 to 194 so the operator can see what is about to be settled.
 *
 * <p>Accepting it inbound would hand the client control over how much is settled, which the legacy
 * transaction never permits at any width: the sum settled is always the full outstanding total read
 * from the account record, and settling it drives the account's stored figure to exactly zero. There
 * is no partial-settlement feature, no operator-supplied figure and no override, and inventing one
 * would be feature expansion. The value is therefore server-produced and display-only, and it is
 * carried by {@code BillPaymentResponse}.
 *
 * <h2>The confirmation character is one character of text, never a two-valued flag</h2>
 *
 * <p>The program keeps its own one-character confirmation work flag, initialised to the negative
 * value with two condition names at lines 51 to 53, and decides what to do by examining the
 * character the operator typed. That decision has <strong>four</strong> distinct outcomes, not two:
 * an affirmative character in either letter case proceeds to settle; a negative character in either
 * letter case clears the screen and stops; an absent character - all spaces or all low values -
 * reads the account and merely displays its outstanding total without settling anything, which is
 * how the screen is first filled; and any other character is an error that must be reported with the
 * exact diagnostic declared at line 187, whose text names the two acceptable values back to the
 * operator.
 *
 * <p>A two-valued flag cannot express four outcomes, and it cannot carry back the unacceptable third
 * character the operator actually typed. The component is therefore one character of text, it has
 * <strong>no default</strong>, and an absent value is a real legacy state rather than a missing
 * input. Both letter cases are accepted explicitly by the legacy evaluation, so this record must not
 * case fold: folding here would silently rewrite what the operator typed and would erase the
 * distinction the diagnostic at line 187 exists to draw. Deciding which of the four outcomes applies
 * belongs to {@code BillPaymentService}.
 *
 * <h2>Nothing here computes, derives or identifies</h2>
 *
 * <p>Every derived value of the transaction is produced by {@code BillPaymentService}, and not one of
 * them is a component of this record.
 *
 * <ul>
 *   <li><strong>The refusal test.</strong> The program declines to settle only when the account's
 *       outstanding total is non-positive <em>and</em> the account-id input is non-blank - both
 *       conditions together, tested at line 198 - and reports the refusal with the diagnostic at
 *       line 201. Neither the test nor its message is performed or declared here; this record runs
 *       no comparison and no sign test of any kind.</li>
 *   <li><strong>The transaction identifier.</strong> The program draws it from no generator. It
 *       positions on the highest existing key by browsing backwards from the high value and takes
 *       the one immediately above it at lines 212 to 219, seeding from zeros when the file is empty
 *       at line 488. On an initially empty file the first identifier is therefore the
 *       sixteen-character {@code 0000000000000001} and never {@code 1}. No identifier component
 *       appears here and none is derived here.</li>
 *   <li><strong>The synthesized transaction.</strong> The record the program writes is assembled
 *       from fixed values it holds itself: a type code, a category code, a source at line 222 that
 *       is space-padded to its full declared width, a description at line 223, a merchant
 *       identifier carried as digits rather than as a quantity, a merchant name at line 227, and a
 *       merchant city and postal code. None of those values is client-supplied, so none of them is
 *       declared in this file.</li>
 *   <li><strong>The timestamps.</strong> A single clock reading is stamped into both the origination
 *       and the processing timestamp at lines 231 to 232, so the two are identical for a settlement
 *       made through this screen. The two twenty-six-character renderings used across the estate
 *       differ from one another deliberately, and building either is the service's work. This record
 *       carries no timestamp and imports no date or time type at all.</li>
 *   <li><strong>The order of the writes.</strong> The program writes the transaction, recomputes the
 *       account's stored figure at line 234 and only then updates the account at line 235.
 *       Transaction boundaries and write order are service concerns and are not expressible on a
 *       transfer object.</li>
 *   <li><strong>The confirmation prompt.</strong> When the operator has not yet confirmed, the
 *       program emits the prompt at line 237 instead of settling. That message, like the two
 *       diagnostics above, is declared once on {@code BillPaymentResponse}.</li>
 * </ul>
 *
 * <h2>Nothing is validated, defaulted or normalised here</h2>
 *
 * <p>There is no canonical constructor because there is nothing for one to do. Both components may
 * legitimately be absent: an empty account-id field is precisely the state the emptiness diagnostic
 * at line 161 exists to report, and an absent confirmation character is the ordinary first pass
 * through the screen. Values cross this boundary character for character and are never trimmed,
 * padded, case folded, stripped, canonicalised or reformatted.
 *
 * <p>The only constraint declared is an upper bound on length. It measures and never alters, so
 * leading and trailing spaces survive validation untouched - which matters because the legacy fields
 * are fixed-width and their padding is contract. No presence, pattern, character-class or
 * numeric-range constraint appears anywhere in this file. Each would reject input the legacy
 * transaction accepts, and, more decisively, both checks that do exist are message-bearing and
 * strictly ordered: the emptiness test at line 161 runs first and ends the pass, and the
 * confirmation test at line 187 runs only afterwards. Bean Validation reports violations in an
 * unspecified order and under its own messages, so it cannot reproduce a first-error-wins cascade
 * carrying exact legacy text. Both cascades therefore belong to {@code BillPaymentService}, and both
 * message texts are declared once, on {@code BillPaymentResponse}, so that no paraphrase of either
 * can drift.
 *
 * <h2>The attention key and the echoed navigation state</h2>
 *
 * <p>The program branches on the attention key directly at lines 125 to 142 with three named
 * outcomes and a catch-all: the enter key runs the validation and settlement pass, the third
 * program-function key returns to the previous screen, the fourth clears the screen, and every other
 * key is reported as an invalid key. {@link #keyAction()} carries that key as a {@link KeyAction},
 * which declares one constant per attention key the estate names and <strong>no default and no
 * unknown constant</strong>, so an absent key stays absent rather than being invented. Which
 * behaviour a given key selects, and how a raw terminal key identifier is resolved to a constant,
 * both belong elsewhere: this record dispatches nothing and resolves nothing.
 *
 * <p>One detail of this transaction is worth recording because it differs from the five-program
 * family that shares a procedural key-mapping copybook: {@code COBIL00C} includes no such copybook
 * and compares the raw key identifier itself, so the higher-numbered function keys are not folded
 * onto the lower twelve here and fall to the catch-all instead. That is legacy behaviour rather than
 * an omission, and carrying the key itself - rather than a pre-interpreted intent - is what keeps it
 * reproducible.
 *
 * <p>{@link #navigationContext()} carries the client-echoed navigation state: the same state every
 * other online contract in this package carries, and not a server session. The program's own copybook
 * set does not include the screen work area used by that five-program family, so this record declares
 * no screen work-area component either.
 *
 * <h2>Immutability and layering</h2>
 *
 * <p>A record, so every component is final, no mutator exists, no mutable state is held or exposed,
 * and no code generation or annotation processing is involved. The type depends only on the platform
 * library, the single length constraint, the attention-key enumeration in the domain enumeration
 * package and the navigation record in this package; it holds no framework, persistence, messaging or
 * service type. It performs no input or output, logs nothing, and reads nothing at run time - least of
 * all anything under {@code app/}. Equality, hashing and stringification are the record defaults:
 * neither carried value is a secret, so nothing is redacted and nothing is suppressed.
 *
 * <h2>Provenance</h2>
 *
 * <p>Translated from the CardDemo COBOL estate at checkout commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The estate under {@code app/} is read-only
 * reference: it is cited here by member name, field width and line number only, and no COBOL text is
 * reproduced.
 *
 * @param accountId the operator-typed account identifier, corresponding to the eleven-character
 *     account-id item of symbolic map {@code COBIL00} at line 60 of
 *     {@code app/cpy-bms/COBIL00.CPY} and to the unprotected screen field at line 85 of
 *     {@code app/bms/COBIL00.bms}. Carried as text and never as a numeric type, because its leading
 *     zeros and its eleven-character external width are both contract and a numeric type would
 *     discard both. Bounded to {@link #ACCOUNT_ID_LENGTH} characters and otherwise unaltered: not
 *     trimmed, not padded, not case folded and not reformatted. May be {@code null}, empty or all
 *     spaces, so that the ordered emptiness cascade reported at line 161 of
 *     {@code app/cbl/COBIL00C.cbl} stays in the service layer.
 * @param confirm the operator-typed confirmation character, corresponding to the one-character
 *     confirmation item of symbolic map {@code COBIL00} at line 72 of
 *     {@code app/cpy-bms/COBIL00.CPY} and to the unprotected screen field at line 115 of
 *     {@code app/bms/COBIL00.bms}. One character of text rather than a two-valued flag, because the
 *     legacy evaluation has four outcomes and has to be able to quote an unacceptable third
 *     character back through the diagnostic at line 187. Bounded to {@link #CONFIRM_LENGTH}
 *     characters, never case folded even though the legacy accepts either letter case, and
 *     deliberately given no default. May be {@code null} or a space, which is the ordinary first pass
 *     through the screen and displays the account without settling anything.
 * @param keyAction the attention key the operator pressed, as evaluated at lines 125 to 142 of
 *     {@code app/cbl/COBIL00C.cbl}. Data only: no default is applied, no key is folded onto another
 *     and no destination is chosen here. May be {@code null} when the caller carries no key.
 * @param navigationContext the client-echoed navigation state handed back on this turn, the REST-era
 *     stand-in for the communication area the legacy transaction received and returned. Declarative
 *     only; this record performs no routing. May be {@code null}; the navigation record additionally
 *     offers a wholly empty instance for a turn that carries nothing yet.
 */
public record BillPaymentRequest(
        @Size(max = BillPaymentRequest.ACCOUNT_ID_LENGTH) String accountId,
        @Size(max = BillPaymentRequest.CONFIRM_LENGTH) String confirm,
        KeyAction keyAction,
        NavigationContext navigationContext) {

    /**
     * Width in characters of the account-id field: 11.
     *
     * <p>The declared width of the account-id item of symbolic map {@code COBIL00} at line 60 of
     * {@code app/cpy-bms/COBIL00.CPY} and of the matching unprotected screen field at line 85 of
     * {@code app/bms/COBIL00.bms}, and the same width the account record itself declares for the
     * key. This constant is a character width and nothing more: the identifier it bounds is text,
     * never a number, so that its leading zeros and its external width both survive. The bound only
     * reports an over-long value; it never trims, pads or otherwise alters one.
     */
    public static final int ACCOUNT_ID_LENGTH = 11;

    /**
     * Width in characters of the confirmation field: 1.
     *
     * <p>The declared width of the confirmation item of symbolic map {@code COBIL00} at line 72 of
     * {@code app/cpy-bms/COBIL00.CPY} and of the matching unprotected screen field at line 115 of
     * {@code app/bms/COBIL00.bms}, matching the one-character confirmation work flag the program
     * declares at lines 51 to 53 of {@code app/cbl/COBIL00C.cbl}. The bound deliberately does
     * <em>not</em> restrict the value to the two acceptable characters: an unacceptable third
     * character must reach the service intact so the diagnostic at line 187 can name it, and an
     * absent character is a legitimate state rather than a violation.
     */
    public static final int CONFIRM_LENGTH = 1;
}
