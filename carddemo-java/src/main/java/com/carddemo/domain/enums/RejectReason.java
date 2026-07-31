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
package com.carddemo.domain.enums;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The five daily-transaction reject reasons produced by the legacy posting program
 * {@code CBTRN02C}, each pairing a numeric reason code with the operator-visible
 * description text that the legacy program emits alongside it.
 *
 * <p>This is a pure value type. It holds two data values per constant and exposes them;
 * it performs no I/O, no arithmetic, no text assembly and no persistence mapping, and it
 * depends on nothing outside {@code java.util}.
 *
 * <h2>Provenance</h2>
 *
 * <p>Translated from the CardDemo mainframe estate at checkout commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated {@code 2022-07-19}. The legacy source is read-only
 * reference and is never copied: the five description strings below are reproduced because they
 * are contractual <em>output data</em>, byte-compared by the end-to-end parity checks.
 *
 * <h2>The record layout these values feed</h2>
 *
 * <p>The reject record written by the legacy program is <strong>430 bytes</strong>, composed of a
 * <strong>350-byte</strong> verbatim copy of the source daily-transaction image followed by an
 * <strong>80-byte</strong> validation trailer. The trailer is itself two fields:
 *
 * <ul>
 *   <li>the reason code, declared {@code PIC 9(04)} - a four-digit numeric field;</li>
 *   <li>the description, declared {@code PIC X(76)} - a 76-character fixed-width text field.</li>
 * </ul>
 *
 * <p>4 + 76 = 80, and 350 + 80 = 430. Every description defined here is well inside the
 * 76-character field: their lengths are 25, 24, 21, 42 and 24 characters. A description longer
 * than 76 characters would overflow the trailer and shift the record width, which is why the
 * text may not drift by a single character.
 *
 * <h2>How the reason code reaches the record</h2>
 *
 * <p>This enum stores the <em>numeric</em> reason code, because {@code PIC 9(04)} is a numeric
 * field and an {@code int} states that intent most directly. The code's emitted form is
 * nonetheless <strong>four digits with leading zeros</strong> - code 100 is written to the record
 * as the four characters {@code 0100}, not as the three characters {@code 100} - because a
 * three-character code would shift the description by one byte and break the 80-byte trailer.
 *
 * <p>Producing that zero-padded four-character image, and space-padding the description to its
 * 76-character width, belong to the batch step that assembles and writes the reject record. This
 * enum deliberately performs no padding, no truncation and no text formatting of any kind: it
 * supplies the two values, and the writing step lays them out.
 *
 * <h2>Why codes 101 and 109 are separate constants</h2>
 *
 * <p>Codes {@code 101} and {@code 109} carry <strong>byte-identical description text</strong>.
 * That is not a defect to be corrected and not a redundancy to be collapsed. The two codes arise
 * from <em>different operations</em> on the account record, at different points in the posting
 * program:
 *
 * <ul>
 *   <li>{@code 101} is raised when the account <strong>read</strong> fails on an invalid key,
 *       during the validation cascade that runs before anything is posted;</li>
 *   <li>{@code 109} is raised when the account <strong>rewrite</strong> fails on an invalid key,
 *       during the balance update that follows a successful posting.</li>
 * </ul>
 *
 * <p>They therefore mean different things operationally even though the text an operator sees is
 * the same, and downstream tooling distinguishes them by code. Consolidating them - aliasing one
 * to the other, having one delegate to the other, or hiding a single constant behind two names -
 * would compile, would pass a naive test, and would silently stop emitting one of the five codes
 * the 430-byte trailer is contractually required to carry. Equally, neither description may be
 * reworded to remove the duplication, because the text is contractual output.
 *
 * <p>Faithful translation outranks idiomatic Java here, and this finding is recorded as a
 * decision-log item: identical text under distinct codes, preserved on purpose.
 *
 * <p>One consequence follows directly: any index over these constants must be keyed by
 * <em>reason code</em>, never by description. The two identical descriptions would collide in a
 * description-keyed map and force exactly the consolidation described above. See
 * {@link #byReasonCode(int)}.
 *
 * <h2>Reason code zero is represented by absence, not by a constant</h2>
 *
 * <p>The legacy program initialises the reason code to {@code 0} and the description to spaces
 * before validating each transaction, then posts the transaction only while the reason code is
 * still zero - a condition it tests at two separate gates, so that the validation cascade
 * short-circuits at the first failure.
 *
 * <p>That "no reject" state is intentionally <strong>not</strong> modelled as a constant here.
 * Zero is the absence of a reject reason rather than a sixth reject reason, and expressing it as
 * absence at the call site - an empty {@link Optional}, or a null-free equivalent chosen by the
 * caller - keeps two useful invariants exact: {@link #values()} enumerates precisely the five
 * legacy reject reasons and nothing else, and {@link #byReasonCode(int)} returns
 * {@link Optional#empty()} for {@code 0} just as it does for any other code the legacy program
 * never emits.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p>Code {@code 102} originates in an overlimit computation over monetary fields, but that
 * arithmetic - and the left-to-right operand order it must preserve - belongs to the
 * transaction-posting service. No monetary field, no decimal type and no arithmetic appears in
 * this enum. Likewise there is no persistence mapping: reject reasons are emitted to a
 * fixed-width flat file, never written to a table, so no column is bound to this type.
 */
public enum RejectReason {

    /**
     * Reason code {@code 100} - the card cross-reference read failed on an invalid key, so the
     * card number carried by the daily-transaction record does not exist.
     *
     * <p>First stage of the validation cascade; when it fires, the account lookup is skipped.
     * Description length 25.
     */
    INVALID_CARD_NUMBER(100, "INVALID CARD NUMBER FOUND"),

    /**
     * Reason code {@code 101} - the account <strong>read</strong> failed on an invalid key during
     * validation, so the account the cross-reference points at does not exist.
     *
     * <p>Distinct from {@link #ACCOUNT_NOT_FOUND_ON_REWRITE} despite carrying identical
     * description text: this one reports a failed read before posting. Description length 24.
     */
    ACCOUNT_NOT_FOUND_ON_READ(101, "ACCOUNT RECORD NOT FOUND"),

    /**
     * Reason code {@code 102} - the transaction would take the account past its credit limit.
     *
     * <p>Raised on the negative branch of the credit-limit comparison. The balance basis behind
     * that comparison is computed by the transaction-posting service, not here. Description
     * length 21.
     */
    OVERLIMIT_TRANSACTION(102, "OVERLIMIT TRANSACTION"),

    /**
     * Reason code {@code 103} - the transaction was received after the account expiration date.
     *
     * <p>Raised on the negative branch of the expiry comparison, which the legacy program
     * evaluates after the credit-limit comparison within the same account read. Description
     * length 42.
     */
    TRANSACTION_AFTER_ACCOUNT_EXPIRATION(103, "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"),

    /**
     * Reason code {@code 109} - the account <strong>rewrite</strong> failed on an invalid key
     * while the updated balances were being written back after a successful posting.
     *
     * <p>Distinct from {@link #ACCOUNT_NOT_FOUND_ON_READ} despite carrying identical description
     * text: this one reports a failed rewrite after posting, in a different paragraph of the
     * legacy program and a different phase of the run. Description length 24.
     */
    ACCOUNT_NOT_FOUND_ON_REWRITE(109, "ACCOUNT RECORD NOT FOUND");

    /**
     * Immutable index of every constant by its reason code, built once when the enum is
     * initialised and never mutated thereafter.
     *
     * <p>Keyed by reason code rather than by description on purpose: codes 101 and 109 share
     * their description text and would collide in a description-keyed map, whereas their codes
     * are distinct, so this index holds all five constants with no loss.
     *
     * <p>The index exists so that a reason code recovered from a persisted 430-byte reject record
     * can be resolved back to its constant during byte-parity verification, without any caller
     * having to scan {@link #values()} or hard-code the numeric literals a second time.
     */
    private static final Map<Integer, RejectReason> BY_REASON_CODE = indexByReasonCode();

    /**
     * The numeric reason code, corresponding to the {@code PIC 9(04)} field of the validation
     * trailer. Stored numerically; its emitted form is four digits with leading zeros.
     */
    private final int reasonCode;

    /**
     * The operator-visible description exactly as the legacy program emits it, corresponding to
     * the {@code PIC X(76)} field of the validation trailer. Stored unpadded; the writing step
     * pads it to its fixed 76-character width.
     */
    private final String description;

    /**
     * Binds a constant to its reason code and description. Enum constructors are implicitly
     * private, so no constant can be created outside this type and both values are fixed at
     * class-initialisation time.
     *
     * @param reasonCode  the numeric reason code, as carried by the {@code PIC 9(04)} field
     * @param description the description text, as carried by the {@code PIC X(76)} field,
     *                    supplied unpadded and never altered
     */
    RejectReason(int reasonCode, String description) {
        this.reasonCode = reasonCode;
        this.description = description;
    }

    /**
     * Builds the immutable reason-code index consumed by {@link #byReasonCode(int)}.
     *
     * <p>Derived from {@link #values()} rather than from repeated numeric literals, so the index
     * cannot drift out of step with the constants. The intermediate map is local to this method
     * and is discarded; only the immutable copy escapes, so no mutable state is retained.
     *
     * @return an immutable map from reason code to the constant that carries it
     */
    private static Map<Integer, RejectReason> indexByReasonCode() {
        Map<Integer, RejectReason> index = new LinkedHashMap<>();
        for (RejectReason reason : values()) {
            index.put(reason.reasonCode, reason);
        }
        return Map.copyOf(index);
    }

    /**
     * Returns the numeric reason code of this constant.
     *
     * <p>The value is the plain number - 100, 101, 102, 103 or 109. When it is written to the
     * validation trailer it must appear as four digits with leading zeros, so 100 becomes
     * {@code 0100}; that zero-padded rendering is applied by the step that assembles the reject
     * record, never here.
     *
     * @return the reason code carried by the {@code PIC 9(04)} field of the trailer
     */
    public int getReasonCode() {
        return reasonCode;
    }

    /**
     * Returns the description text of this constant, exactly as the legacy program emits it.
     *
     * <p>The text is returned exactly as declared: no whitespace is removed, no letter case is
     * folded, and no widening to the 76-character field is applied. Space-padding to that width
     * is applied by the step that assembles the reject record. Note that
     * {@link #ACCOUNT_NOT_FOUND_ON_READ} and {@link #ACCOUNT_NOT_FOUND_ON_REWRITE} return equal
     * text; that is intentional and is explained in this type's documentation.
     *
     * @return the description carried by the {@code PIC X(76)} field of the trailer, unpadded
     */
    public String getDescription() {
        return description;
    }

    /**
     * Resolves a numeric reason code to its constant.
     *
     * <p>Returns an empty result for any code the legacy program does not emit, including
     * {@code 0}: zero is the initial "not rejected" state of the reason field rather than a
     * reject reason, so it is represented here by absence.
     *
     * @param reasonCode the numeric reason code to resolve, as recovered from a reject record
     * @return the matching constant, or an empty {@link Optional} if no constant carries the code
     */
    public static Optional<RejectReason> byReasonCode(int reasonCode) {
        return Optional.ofNullable(BY_REASON_CODE.get(reasonCode));
    }
}
