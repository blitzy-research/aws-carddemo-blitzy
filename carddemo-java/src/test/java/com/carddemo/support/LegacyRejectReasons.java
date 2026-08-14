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
 * The five reject reasons of the daily posting program, transcribed by hand from the legacy source and
 * carrying NO dependency on the shipped implementation.
 *
 * <h2>Why this class exists, and why it must not import the production enumeration</h2>
 *
 * <p>The reject reason code and its description are an external contract: they occupy the eighty-byte
 * trailer of a four-hundred-and-thirty byte record that leaves the system as a file, and a consumer reads
 * them positionally. An expectation built by reading {@code com.carddemo.domain.enums.RejectReason} cannot
 * establish that contract, because it asserts the implementation against itself: a code recorded as 104 in
 * the enumeration would produce an expectation of 104, the comparison would pass, and the emitted file would
 * be wrong. That is a real risk rather than a theoretical one - two of the five reasons carry the SAME
 * description under DIFFERENT codes, which is exactly the kind of pairing a self-referential expectation
 * cannot police.
 *
 * <p>A comparison of SETS is not sufficient either, and that was established by measurement rather than
 * assumed: transposing the two identically-described codes in the shipped enumeration left the set of codes
 * unchanged, left both descriptions unchanged, and left every set-and-description assertion passing over a
 * defect that would have put the wrong four digits in a delivered file and rejected the wrong transaction.
 * Each transcribed reason therefore also names the shipped constant that must carry it;
 * {@link Reason#shippedConstantName()} explains why naming it does not compromise this table's independence.
 *
 * <p>This class is therefore an ORACLE: five literal pairs, each transcribed from the statement that sets it,
 * each citing the source member and line. It imports nothing from {@code com.carddemo.domain} and nothing
 * from {@code com.carddemo.batch}, so a change to either cannot move the expectation. The production
 * enumeration is asserted AGAINST this table in {@code RejectReasonOracleTest}, which is the direction that
 * proves something.
 *
 * <h2>The trailer's shape, and where the widths come from</h2>
 *
 * <p>The trailer is declared as a four-digit numeric reason code followed by a seventy-six character
 * description, appended to the three-hundred-and-fifty byte source image, at
 * {@code app/cbl/CBTRN02C.cbl} lines 181 and 182. Four digits means zero-filled on the left, which is why
 * {@link #fourDigitCode(int)} renders {@code 100} as {@code "0100"} and never as {@code "100 "}. Seventy-six
 * characters means blank-padded on the right, because the receiving field is alphanumeric and a shorter move
 * leaves the remainder as spaces.
 *
 * <h2>Which reasons are reachable, and why two of them are not reachable through data alone</h2>
 *
 * <p>The main read loop writes a reject record only when the reason is non-zero after the validation
 * paragraph has run, at {@code app/cbl/CBTRN02C.cbl} lines 206 to 216. Three of the five are set inside that
 * paragraph and are reachable by shaping a landing record. The other two are not:
 *
 * <ul>
 *   <li><strong>{@value #ACCOUNT_NOT_FOUND_ON_READ_CODE}</strong> is set when the account read finds nothing,
 *       at lines 397 and 398. In the migrated schema a cross-reference row cannot name an account that does
 *       not exist - the foreign key added by {@code V2__create_indexes.sql} forbids it - so the state that
 *       reaches this reason cannot be created by inserting rows. It is reachable only by making the account
 *       read itself report nothing, which is what {@code RejectReasonArmsIT} does.</li>
 *   <li><strong>{@value #ACCOUNT_NOT_FOUND_ON_REWRITE_CODE}</strong> is INERT BY LEGACY DESIGN and produces
 *       no reject record at all. It is set at lines 556 and 557, inside the account-rewrite paragraph, which
 *       runs during posting - that is, AFTER the reject decision at line 209 has already sent the record down
 *       the posting path. Nothing re-tests the reason afterwards, so the value is set and then never read.
 *       The transaction is posted regardless. Reproducing that inertness, rather than quietly turning it into
 *       a sixth reject case, is the faithful translation.</li>
 * </ul>
 *
 * <p>The reasoning behind this table, and the measurement that showed a set comparison to be
 * insufficient, are recorded as DL-279 in {@code docs/decision-log.md}.
 *
 * <p>The literal texts below are field VALUES of an external record contract, not program logic, and no
 * COBOL statement is transcribed.
 */
public final class LegacyRejectReasons {

    /** The declared width of the reason code, {@code PIC 9(04)} at line 181. */
    public static final int CODE_WIDTH = 4;

    /** The declared width of the description, {@code PIC X(76)} at line 182. */
    public static final int DESCRIPTION_WIDTH = 76;

    /** The reason code that means no rejection: posting proceeds only on this value, tested at line 209. */
    public static final int NO_REJECTION_CODE = 0;

    /** Set when the cross-reference read finds no row for the card, at lines 385 and 386. */
    public static final int INVALID_CARD_NUMBER_CODE = 100;

    /** Set when the account read finds no row for the resolved account, at lines 397 and 398. */
    public static final int ACCOUNT_NOT_FOUND_ON_READ_CODE = 101;

    /** Set when the credit limit is below the computed cycle basis, at lines 410 and 411. */
    public static final int OVERLIMIT_TRANSACTION_CODE = 102;

    /** Set when the account expiry date precedes the transaction's origination date, at lines 417/418. */
    public static final int TRANSACTION_AFTER_EXPIRATION_CODE = 103;

    /** Set when the account rewrite reports no row, at lines 556 and 557. INERT: see the class note. */
    public static final int ACCOUNT_NOT_FOUND_ON_REWRITE_CODE = 109;

    /**
     * One transcribed reason: the code, the description exactly as the source moves it, the source location
     * it was transcribed from, and the name of the shipped constant that must carry the pair.
     *
     * <h3>Why the shipped constant's NAME appears in an oracle that imports nothing from production</h3>
     *
     * <p>Because a set comparison cannot police this contract on its own, and the reason is measurable. Two
     * of the five reasons carry the identical description under different codes. Transposing those two codes
     * in the shipped enumeration leaves the SET of codes unchanged, leaves both descriptions unchanged, and
     * leaves every by-code lookup internally consistent - so a test that only compares sets and descriptions
     * passes over a genuine defect. That defect is not cosmetic: the two reasons are set at different sites
     * and are routed differently, one to a reject record and one to a posted record, so transposing them puts
     * the wrong four digits in a delivered file and rejects the wrong transaction.
     *
     * <p>Naming the constant closes that hole without weakening the oracle's independence. The independence
     * that matters is that no EXPECTED VALUE is read out of production: every code and every description
     * below is a hand transcription. The name is the other half of a hand-authored mapping - "the constant
     * the source's account-read site corresponds to must carry 101" - which is the same statement the
     * traceability matrix makes, and it is the statement a value-only comparison cannot make. A rename in
     * production therefore fails this oracle, deliberately, because a rename of a contract-bearing constant
     * is a reviewable act.
     *
     * @param code                the four-digit reason code as an integer
     * @param description         the description text, exactly as the source moves it, unpadded
     * @param sourceLocation      the member and line the pair was transcribed from
     * @param producesRecord      whether reaching this reason produces a reject record; false for the one
     *                            reason the source sets after the reject decision has already been taken
     * @param shippedConstantName the name of the constant in the shipped enumeration that must carry this
     *                            pair, so that a transposition of two identically-described codes is visible
     * @param role                what the source is doing at the site that sets this reason, in prose, so a
     *                            failure names the behaviour rather than only the number
     */
    public record Reason(int code, String description, String sourceLocation, boolean producesRecord,
            String shippedConstantName, String role) {

        /**
         * Returns the code in the zero-filled four-digit form the trailer carries.
         *
         * @return four characters
         */
        public String fourDigitCode() {
            return LegacyRejectReasons.fourDigitCode(this.code);
        }

        /**
         * Returns the description in the blank-padded seventy-six character form the trailer carries.
         *
         * @return seventy-six characters
         */
        public String paddedDescription() {
            return LegacyRejectReasons.paddedDescription(this.description);
        }

        /**
         * Returns the eighty-character trailer this reason contributes to a reject record.
         *
         * @return eighty characters: four digits then seventy-six characters
         */
        public String trailer() {
            return fourDigitCode() + paddedDescription();
        }

        /**
         * Returns the code, the description and the shipped constant name, so a parameterised test case is
         * identified by the contract it asserts rather than by an argument index.
         *
         * @return a short identification of this reason
         */
        @Override
        public String toString() {
            return fourDigitCode() + " " + this.description + " (" + this.shippedConstantName + ")";
        }
    }

    /**
     * The five reasons, in the order the source's own codes ascend.
     *
     * <p>Every value here is a hand transcription. Two entries carry the SAME description text under
     * different codes, and that is not a mistake to be tidied: the source moves the identical literal at
     * lines 397/398 and at lines 556/557, and a consumer reading the trailer positionally sees the same
     * seventy-six characters in both cases and distinguishes them by the four digits in front.
     */
    public static final List<Reason> REASONS = List.of(
            new Reason(INVALID_CARD_NUMBER_CODE, "INVALID CARD NUMBER FOUND",
                    "app/cbl/CBTRN02C.cbl:385-386", true,
                    "INVALID_CARD_NUMBER",
                    "the cross-reference read found no row for the transaction's card number"),
            new Reason(ACCOUNT_NOT_FOUND_ON_READ_CODE, "ACCOUNT RECORD NOT FOUND",
                    "app/cbl/CBTRN02C.cbl:397-398", true,
                    "ACCOUNT_NOT_FOUND_ON_READ",
                    "the account read found no row for the account the cross-reference named"),
            new Reason(OVERLIMIT_TRANSACTION_CODE, "OVERLIMIT TRANSACTION",
                    "app/cbl/CBTRN02C.cbl:410-411", true,
                    "OVERLIMIT_TRANSACTION",
                    "the credit limit was below the cycle basis the transaction would create"),
            new Reason(TRANSACTION_AFTER_EXPIRATION_CODE, "TRANSACTION RECEIVED AFTER ACCT EXPIRATION",
                    "app/cbl/CBTRN02C.cbl:417-418", true,
                    "TRANSACTION_AFTER_ACCOUNT_EXPIRATION",
                    "the account's expiry date preceded the transaction's origination date"),
            new Reason(ACCOUNT_NOT_FOUND_ON_REWRITE_CODE, "ACCOUNT RECORD NOT FOUND",
                    "app/cbl/CBTRN02C.cbl:556-557", false,
                    "ACCOUNT_NOT_FOUND_ON_REWRITE",
                    "the account rewrite during POSTING reported no row, which the loop never re-tests"));

    /** The five codes, in ascending order, as an independent expectation. */
    public static final List<Integer> CODES = List.of(
            INVALID_CARD_NUMBER_CODE,
            ACCOUNT_NOT_FOUND_ON_READ_CODE,
            OVERLIMIT_TRANSACTION_CODE,
            TRANSACTION_AFTER_EXPIRATION_CODE,
            ACCOUNT_NOT_FOUND_ON_REWRITE_CODE);

    /** Not instantiable: this is a table of literals. */
    private LegacyRejectReasons() {
        throw new AssertionError("LegacyRejectReasons is a table of literals and is never instantiated");
    }

    /**
     * Renders a reason code in the zero-filled four-digit form the trailer carries.
     *
     * <p>Zero-filling on the LEFT, because the receiving field is numeric. Blank-padding it, or rendering
     * it as three characters, would place a different byte at each of the four positions a consumer reads.
     *
     * @param code the reason code
     * @return exactly {@value #CODE_WIDTH} characters
     * @throws IllegalArgumentException if the code cannot be expressed in four digits
     */
    public static String fourDigitCode(final int code) {
        if (code < 0 || code > 9999) {
            throw new IllegalArgumentException("a reason code occupies " + CODE_WIDTH
                    + " digits, so it lies between 0 and 9999, but was " + code);
        }
        final String digits = Integer.toString(code);
        return "0".repeat(CODE_WIDTH - digits.length()) + digits;
    }

    /**
     * Renders a description in the blank-padded seventy-six character form the trailer carries.
     *
     * <p>Padding on the RIGHT, because the receiving field is alphanumeric and a shorter move leaves the
     * remainder as the spaces the field was initialised to.
     *
     * @param description the description text
     * @return exactly {@value #DESCRIPTION_WIDTH} characters
     * @throws IllegalArgumentException if the text is longer than the field
     */
    public static String paddedDescription(final String description) {
        if (description.length() > DESCRIPTION_WIDTH) {
            throw new IllegalArgumentException("a description occupies at most " + DESCRIPTION_WIDTH
                    + " characters, but was " + description.length());
        }
        return description + " ".repeat(DESCRIPTION_WIDTH - description.length());
    }

    /**
     * Returns the transcribed reason carrying the supplied code.
     *
     * @param code the reason code
     * @return the reason, or empty when no transcribed reason carries that code
     */
    public static Optional<Reason> byCode(final int code) {
        return REASONS.stream().filter(reason -> reason.code() == code).findFirst();
    }

    /**
     * Returns the transcribed reason carrying the supplied code, failing when there is none.
     *
     * @param code the reason code
     * @return the reason
     * @throws IllegalArgumentException if no transcribed reason carries that code
     */
    public static Reason requireByCode(final int code) {
        return byCode(code).orElseThrow(() -> new IllegalArgumentException(
                "no legacy reject reason carries code " + code));
    }

    /**
     * Returns the transcribed reason that the shipped constant of the given name must carry.
     *
     * <p>This is the lookup a test uses when it is parameterised over the shipped enumeration - which is
     * the natural way to write such a test, because the production code hands back enumeration values - but
     * wants its EXPECTED BYTES to come from this table rather than from the value under test. The constant is
     * NAMED, and the code and description are then read from here, so a drifted code in production changes
     * the produced bytes without changing the expectation, and the comparison fails.
     *
     * @param shippedConstantName the name of a constant in the shipped enumeration
     * @return the reason that constant must carry
     * @throws IllegalArgumentException if this table records no reason for a constant of that name
     */
    public static Reason requireByShippedConstantName(final String shippedConstantName) {
        return REASONS.stream()
                .filter(reason -> reason.shippedConstantName().equals(shippedConstantName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "this table records no legacy reject reason for a shipped constant named "
                                + shippedConstantName + "; it records "
                                + REASONS.stream().map(Reason::shippedConstantName).toList()
                                + ". A reject reason was added to or renamed in the shipped enumeration "
                                + "without the transcription from the legacy source being extended "
                                + "alongside it, which means no independent expectation exists for it"));
    }
}
