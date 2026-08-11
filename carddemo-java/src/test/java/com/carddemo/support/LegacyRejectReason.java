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
package com.carddemo.support;

import java.util.List;
import java.util.Optional;

/**
 * The five reject reasons the legacy posting member sets, typed out from that member and from nothing
 * else.
 *
 * <h2>Why this exists, when the module already has a reject-reason enumeration</h2>
 * It exists because the module's own enumeration is the <strong>subject</strong> of these tests, not
 * their oracle. Every reject expectation in this suite - the four-digit code in a trailer, the
 * description in the seventy-six byte field, the byte-exact four-hundred-and-thirty byte record, and the
 * record constructed to trigger a given refusal - must NOT be derived from
 * {@code com.carddemo.domain.enums.RejectReason}. A single wrong digit or a mistyped description in that
 * enumeration would then move the subject and the expectation <em>together</em>: the produced
 * reject record and the expected reject record would both carry the wrong value, the byte
 * comparison would pass, and the defect would ship having failed nothing.
 *
 * <p>So the codes, the descriptions and the conditions that fire them are restated here, read off
 * {@code app/cbl/CBTRN02C.cbl} at the lines each constant cites. That member is read-only and is the
 * parity baseline, which makes it the only defensible source for an expectation about it. Nothing in this
 * file imports, reads or reflects on any production type, and that is deliberate: independence that
 * depends on an author remembering is not independence.
 *
 * <h2>Where the two meet, exactly once</h2>
 * {@code LegacyRejectReasonTest} pairs each constant here with the production constant of the same name
 * and asserts that the production code and description equal <em>these</em> literals - oracle as
 * expected, production as actual. That is the single place the two vocabularies touch. If the production
 * enumeration drifts, that one test fails and says so; every fixture and every byte comparison built on
 * this file keeps asserting what the legacy member actually does.
 *
 * <h2>Two reasons carry one description, and that is not a mistake</h2>
 * Codes 101 and 109 both carry {@code ACCOUNT RECORD NOT FOUND}. They are distinct reasons that differ in
 * <em>when</em> the account lookup fails - on the read before posting, or on the rewrite after it - and
 * the description does not distinguish them. Collapsing them would erase a real distinction, so both are
 * present with the same text and different codes, as the member has them.
 *
 * <p>Reason codes and description texts are external contract - they are written into a 430-byte record an
 * operator reads - and contract metadata is what a parity oracle is made of; no legacy implementation line
 * is transcribed.
 */
public enum LegacyRejectReason {

    /**
     * The cross-reference read found no row for the card the landing record names.
     *
     * <p>Set on the {@code INVALID KEY} arm of the cross-reference read, before any account is looked up,
     * which is why a record refused for this reason never has its amount measured against a limit.
     */
    INVALID_CARD_NUMBER(100, "INVALID CARD NUMBER FOUND", 385,
            "the cross-reference read takes its INVALID KEY arm, so the card names no row"),

    /**
     * The card resolved but the account behind it did not, on the read that precedes posting.
     */
    ACCOUNT_NOT_FOUND_ON_READ(101, "ACCOUNT RECORD NOT FOUND", 397,
            "the account read takes its INVALID KEY arm after the cross-reference resolved"),

    /**
     * The transaction would carry the account past its credit limit.
     *
     * <p>The basis is cycle credit less cycle debit plus the transaction amount, evaluated strictly left
     * to right into a two-decimal field, and the refusal fires when the credit limit is below it. The
     * expression is never rearranged anywhere, because rearranging it moves the truncation point.
     */
    OVERLIMIT_TRANSACTION(102, "OVERLIMIT TRANSACTION", 410,
            "cycle credit less cycle debit plus the amount, evaluated left to right, exceeds the "
                    + "credit limit"),

    /**
     * The transaction arrived after the account's expiry date.
     *
     * <p>Compared against the first ten characters of the record's original timestamp, so the comparison
     * is a date comparison over a timestamp field rather than an instant comparison.
     */
    TRANSACTION_AFTER_ACCOUNT_EXPIRATION(103, "TRANSACTION RECEIVED AFTER ACCT EXPIRATION", 417,
            "the account expiry date is earlier than the first ten characters of the record's "
                    + "original timestamp"),

    /**
     * The account could not be rewritten after posting had already updated it in storage.
     *
     * <p>Set on the {@code INVALID KEY} arm of the rewrite, which is after the posting arithmetic rather
     * than before it - the reason this is a separate code from 101 despite sharing its description.
     */
    ACCOUNT_NOT_FOUND_ON_REWRITE(109, "ACCOUNT RECORD NOT FOUND", 556,
            "the account rewrite takes its INVALID KEY arm after the posting arithmetic ran");

    /** The read-only member every literal in this file was read off. */
    public static final String MEMBER = "app/cbl/CBTRN02C.cbl";

    /** Line of the member that declares the reason field as four digits. */
    public static final int REASON_FIELD_DECLARATION_LINE = 181;

    /** Line of the member that declares the description field as seventy-six characters. */
    public static final int DESCRIPTION_FIELD_DECLARATION_LINE = 182;

    /** Digits the reason field occupies, from its own picture clause. */
    public static final int REASON_CODE_DIGITS = 4;

    /** Characters the description field occupies, from its own picture clause. */
    public static final int DESCRIPTION_CHARACTERS = 76;

    /**
     * The value the reason field carries when nothing was refused.
     *
     * <p>Posting proceeds only on this value, so it is a member of the vocabulary in the same sense the
     * five refusals are, and it is deliberately not one of the enumerated constants.
     */
    public static final int NO_REASON_CODE = 0;

    /** The reason code, as the member moves it. */
    private final int code;

    /** The description text, character for character as the member moves it. */
    private final String description;

    /** The line of {@link #MEMBER} that moves this code. */
    private final int line;

    /** What has to be true of the record and the stored state for this reason to fire. */
    private final String trigger;

    /**
     * Declares one reason.
     *
     * @param reasonCode        the four-digit code
     * @param reasonDescription the description text
     * @param memberLine        the line of {@link #MEMBER} that moves the code
     * @param triggerCondition  the condition under which the member sets it
     */
    LegacyRejectReason(final int reasonCode, final String reasonDescription, final int memberLine,
            final String triggerCondition) {
        this.code = reasonCode;
        this.description = reasonDescription;
        this.line = memberLine;
        this.trigger = triggerCondition;
    }

    /**
     * The reason code.
     *
     * @return the code
     */
    public int code() {
        return this.code;
    }

    /**
     * The description text, exactly as the member carries it and never trimmed or normalised.
     *
     * @return the description
     */
    public String description() {
        return this.description;
    }

    /**
     * The line of {@link #MEMBER} that moves this code, so any reviewer can check one literal at source.
     *
     * @return the line number
     */
    public int line() {
        return this.line;
    }

    /**
     * What has to hold for the member to set this reason, in words rather than in code.
     *
     * <p>Carried so a fixture that claims to trigger a reason can be read against the condition it
     * claims to satisfy, which is the part a code and a description do not state.
     *
     * @return the trigger condition
     */
    public String trigger() {
        return this.trigger;
    }

    /**
     * The emitted form of the code: four digits, zero-filled on the left.
     *
     * <p>Assembled from the digits rather than by formatting, so no locale can influence the grouping and
     * no format string decides the width.
     *
     * @return the four-character field content
     */
    public String fourDigitForm() {
        final String digits = Integer.toString(this.code);
        return "0".repeat(REASON_CODE_DIGITS - digits.length()) + digits;
    }

    /**
     * Every reason code the member can set, in ascending code order.
     *
     * @return the five codes
     */
    public static List<Integer> codes() {
        return List.of(Integer.valueOf(INVALID_CARD_NUMBER.code),
                Integer.valueOf(ACCOUNT_NOT_FOUND_ON_READ.code),
                Integer.valueOf(OVERLIMIT_TRANSACTION.code),
                Integer.valueOf(TRANSACTION_AFTER_ACCOUNT_EXPIRATION.code),
                Integer.valueOf(ACCOUNT_NOT_FOUND_ON_REWRITE.code));
    }

    /**
     * The reason carrying one code, if the member sets that code at all.
     *
     * @param  reasonCode the code to resolve
     * @return the reason, or empty when the member sets no such code
     */
    public static Optional<LegacyRejectReason> byCode(final int reasonCode) {
        for (final LegacyRejectReason reason : values()) {
            if (reason.code == reasonCode) {
                return Optional.of(reason);
            }
        }
        return Optional.empty();
    }
}
