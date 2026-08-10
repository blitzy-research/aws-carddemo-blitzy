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

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.carddemo.domain.Account;
import com.carddemo.domain.Card;
import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.Customer;
import com.carddemo.domain.DailyTransaction;
import com.carddemo.domain.DisclosureGroup;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.TransactionCategory;
import com.carddemo.domain.TransactionCategoryBalance;
import com.carddemo.domain.TransactionType;
import com.carddemo.domain.UserSecurity;
import com.carddemo.domain.enums.RejectReason;
import com.carddemo.domain.enums.TransactionSourceType;
import com.carddemo.domain.enums.UserType;
import com.carddemo.domain.id.DisclosureGroupId;
import com.carddemo.domain.id.TransactionCategoryBalanceId;
import com.carddemo.domain.id.TransactionCategoryId;
import com.carddemo.util.DailyTransactionRecordMapper;

/**
 * Hand-written builders that produce records at the exact verified widths and offsets of the eleven
 * legacy record layouts, plus the small number of deliberately constructed fixtures that the seeded
 * data cannot supply.
 *
 * <p><strong>THE INDEPENDENT-ORACLE MANDATE.</strong> Nothing here delegates to a production
 * formatter, a production fixed-width assembler, the production zoned-decimal codec, or any of the
 * eleven production record-image mappers. Every fixed-width image below is assembled from the
 * verified widths and offsets using plain string arithmetic, and the overpunched-sign encoding is
 * implemented from first principles in {@link #encodeZonedDecimal(BigDecimal, int)}. The reason is
 * not tidiness: if this class encoded a signed amount by calling the same codec the system under
 * test uses, a codec defect would produce a matching input and a matching expectation, and the
 * byte-equivalence gate would pass while the system was wrong. An oracle that shares an
 * implementation with the thing it measures is not an oracle.</p>
 *
 * <p><strong>WHAT THIS CLASS EXPOSES.</strong> Two clearly separated capability families:</p>
 * <ol>
 *   <li><em>Entity builders</em> - {@link #account()}, {@link #card()}, {@link #cardCrossReference()},
 *       {@link #customer()}, {@link #transaction()}, {@link #dailyTransaction()},
 *       {@link #transactionCategoryBalance()}, {@link #disclosureGroup()},
 *       {@link #transactionType()}, {@link #transactionCategory()} and {@link #userSecurity()} -
 *       each returning the production domain type with measured defaults and a per-field override
 *       for every field of its layout.</li>
 *   <li><em>Fixed-width record-image builders</em> - the same eleven builders each also answer
 *       {@code image()}, returning the legacy record image at its exact byte width. The
 *       cross-reference builder answers two, because two widths coexist and both are correct.</li>
 * </ol>
 *
 * <p><strong>NO CODE GENERATION AND NO REFLECTION.</strong> Every builder is written out by hand.
 * No annotation processor is declared by the build, and diagnostics are promoted to errors, so
 * processor-generated code would be a live source of build failures; the reflection budget for the
 * module is zero, which is the same constraint that rules out an annotation-driven mapping library
 * for the production mappers.</p>
 *
 * <p><strong>DEFAULTS ARE MEASURED, NOT INVENTED.</strong> Each builder's defaults are the values of
 * the first record of the corresponding delivered fixture, read field-by-field at the verified
 * offsets. Two deliberate departures are documented at their point of use: the two regulated
 * customer identifiers, which the entity refuses in cleartext, and the customer credit score, which
 * the first seeded record carries outside the range the update service enforces.</p>
 *
 * <p><strong>DETERMINISM.</strong> No system clock, no unseeded pseudo-random source and no
 * identifier generator is consulted anywhere. Date and timestamp text is either a measured constant or
 * is formatted through a formatter that names {@link Locale#ROOT} and resolves strictly, so an invalid
 * calendar date fails instead of being silently normalised. Where a clock or a date window is needed,
 * the caller supplies it - {@link #recordTimestampAt(Clock)} and
 * {@link #inclusiveWindowProbeDates(java.time.LocalDate, java.time.LocalDate)} take it as an argument
 * rather than reading a pinned one. Section 17 records the measured reason that matters: reading
 * {@code AbstractPostgresIT}'s pinned constants would initialise that class, and initialising it starts
 * a database server, so a unit test that only assembles a record image must not touch them.</p>
 *
 * <p><strong>THIS CLASS IS NOT A TEST.</strong> Its name ends in neither {@code Test} nor {@code IT},
 * so the unit phase and the integration phase both decline to collect it - which is correct, because
 * it is a utility consumed by both. Renaming it to match either convention would make the build try
 * to run it.</p>
 *
 * <p><strong>CREDENTIAL HANDLING.</strong> The shared legacy cleartext credential appears nowhere in
 * this file - not in a literal, not in a comment, not in a method name and not in a diagnostic - and
 * nowhere else in this module's Java sources either. It lives in exactly one test resource, in the
 * credential window of the sign-on fixture, and is read at run time by offset and overwritten after use.
 * That window carries the delivered provisioning value rather than a stand-in, because acceptance is the
 * only property that distinguishes a correct shipped digest from a merely well-formed one. See
 * {@link #fixtureCredentialWindow()}, which states the whole contract and the measurement that makes
 * the distinction load-bearing rather than pedantic.</p>
 *
 * <p><strong>PROVENANCE.</strong> The layouts, offsets, filler characters and record censuses
 * recorded here were verified against the checkout at commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}. The upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} of 2022-07-19 is a matrix-header provenance string only: it is
 * not carried by every legacy member, so nothing here asserts it against one.</p>
 *
 * @see AbstractPostgresIT for the shared migrated server, the pinned clock and the pinned window
 * @see SeededRecordFixture for reading a newline-delimited fixture record-by-record
 */
public final class TestDataFactory {

    // =============================================================================================
    // SECTION 1 - THE FILLER-CHARACTER CONTRACT
    //
    // Established by a byte census of every delivered fixture, not by reading a specification. It is
    // NOT uniform, and unifying it "for cleanliness" breaks fixed-width equality on the very first
    // record. Four layouts pad with a space, four pad with the ASCII digit zero, and the
    // cross-reference fixture image carries no filler at all.
    // =============================================================================================

    /** Pad byte of the account, card, customer, transaction and daily-transaction layouts. */
    public static final char SPACE_FILLER = ' ';

    /**
     * Pad byte of the disclosure-group, category-balance, transaction-category and transaction-type
     * layouts. It is the ASCII digit zero and not a space, measured across every filler byte of all
     * four fixtures.
     */
    public static final char ZERO_FILLER = '0';

    // =============================================================================================
    // SECTION 2 - THE FOUR CONTRACTUAL OUTPUT WIDTHS
    //
    // Each is an external file-format contract, so each is a byte width rather than a formatting
    // preference. Held here so an expectation can be measured against a named constant.
    // =============================================================================================

    /** Statement text record width, as declared by the statement program's own output description. */
    public static final int STATEMENT_TEXT_WIDTH = 80;

    /**
     * Statement markup record width. Two job steps declare conflicting logical record lengths for the
     * same data set; the conflict resolves to this width because it is what the emitting program's
     * own output description declares.
     */
    public static final int STATEMENT_MARKUP_WIDTH = 100;

    /** Transaction report line width, fixed-blocked. */
    public static final int TRANSACTION_REPORT_WIDTH = 133;

    /** Reject record width: the verbatim source image plus the validation trailer. */
    public static final int REJECT_RECORD_WIDTH = 430;

    /** Width of the reason-code field of the validation trailer, zero-padded on the left. */
    public static final int REJECT_REASON_CODE_WIDTH = 4;

    /** Width of the description field of the validation trailer, space-padded on the right. */
    public static final int REJECT_DESCRIPTION_WIDTH = 76;

    // =============================================================================================
    // SECTION 3 - PROVENANCE AND PINNED TEXT
    // =============================================================================================

    /** Commit the layouts, censuses and offsets in this file were verified against. */
    public static final String VERIFIED_CHECKOUT_COMMIT =
            "7756d895ffeb65f7ea72aaa609e356d9899afcec";

    /**
     * Upstream release stamp, for a document header only.
     *
     * <p>It is deliberately <em>not</em> a per-member assertion: most legacy members carry it, a few
     * carry later stamps, the screen definitions differ, and a number carry none. Asserting it
     * against an individual member would fail for a reason unrelated to the migration.</p>
     */
    public static final String UPSTREAM_RELEASE_STAMP = "CardDemo_v1.0-15-g27d6c6f-68";

    /** Width of both timestamp fields of the transaction and daily-transaction layouts. */
    public static final int TIMESTAMP_TEXT_WIDTH = 26;

    /** Width of every date field in every layout. */
    public static final int DATE_TEXT_WIDTH = 10;

    /**
     * Formats the pinned instant into the twenty-six character timestamp text the record carries.
     *
     * <p>The proleptic-year symbol is used rather than year-of-era because the resolver is strict,
     * and the locale is named rather than inherited so that no host default can alter the digits.</p>
     */
    private static final DateTimeFormatter RECORD_TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS", Locale.ROOT)
                    .withResolverStyle(ResolverStyle.STRICT)
                    .withZone(ZoneOffset.UTC);

    /** Formats a date into the ten-character text every date field of every layout carries. */
    private static final DateTimeFormatter RECORD_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT)
                    .withResolverStyle(ResolverStyle.STRICT);

    /**
     * The one original-timestamp value every delivered daily-transaction record carries.
     *
     * <p>Measured across the whole fixture: all three hundred records carry this and nothing else.</p>
     *
     * <p>It is the same instant {@code AbstractPostgresIT} pins its clock to, and a container-backed
     * test should assert that the two agree by comparing this constant with
     * {@link #recordTimestamp(Instant)} of that pinned instant. The comparison deliberately lives in a
     * test rather than in this class's initialiser: reading a non-constant field of that type would
     * initialise it, and initialising it starts a database server. A unit test that only needs a record
     * image must not start one.</p>
     */
    public static final String SEEDED_ORIGINAL_TIMESTAMP = "2022-06-10 19:27:53.000000";

    /**
     * The processing-timestamp value every delivered daily-transaction record carries: twenty-six
     * blanks.
     *
     * <p>This is why a date-window test needs a constructed record. The seed cannot exercise
     * processing-date filtering at all, because there is no processing date in it to filter on. See
     * {@link #dailyTransactionWithProcessingDate(LocalDate)}.</p>
     */
    public static final String BLANK_PROCESSING_TIMESTAMP =
            String.valueOf(SPACE_FILLER).repeat(TIMESTAMP_TEXT_WIDTH);

    static {
        // Self-contained shape check, with no dependency on any other type: the measured constant must
        // survive a strict parse and a re-render through this class's own formatter. That proves it is a
        // well-formed twenty-six character record timestamp rather than a mistyped literal, and it does
        // so without reading a field whose owning class starts a database server when it initialises.
        final LocalDateTime parsed =
                RECORD_TIMESTAMP_FORMATTER.parse(SEEDED_ORIGINAL_TIMESTAMP, LocalDateTime::from);
        final String rendered = RECORD_TIMESTAMP_FORMATTER.format(parsed.toInstant(ZoneOffset.UTC));
        if (!SEEDED_ORIGINAL_TIMESTAMP.equals(rendered)
                || SEEDED_ORIGINAL_TIMESTAMP.length() != TIMESTAMP_TEXT_WIDTH) {
            throw new IllegalStateException("the measured original-timestamp constant "
                    + SEEDED_ORIGINAL_TIMESTAMP + " does not survive a strict parse and re-render as "
                    + TIMESTAMP_TEXT_WIDTH + " characters; it rendered as " + rendered
                    + " at length " + rendered.length());
        }
    }

    /** Restricts construction: every member of this class is static. */
    private TestDataFactory() {
        // Intentionally empty: this type holds no per-instance state.
    }

    // =============================================================================================
    // SECTION 4 - THE LAYOUT MODEL
    //
    // A layout is described rather than hardcoded so that field ORDER is assertable and not merely
    // implied. This matters most for the account layout, where the intuitive arrangement is wrong:
    // the five monetary fields are NOT contiguous, and grouping them corrupts every offset from 36
    // onward while still producing a three-hundred byte image. Because RecordLayout refuses a field
    // list that is not contiguous from zero, a mis-ordered layout cannot be declared at all.
    // =============================================================================================

    /**
     * One field of a legacy record layout, named as the copybook names it.
     *
     * @param cobolName the legacy field name, preserved exactly - including the two misspellings the
     *                  source carries, because the name identifies the byte range and correcting it
     *                  here would break the correspondence the traceability matrix rests on
     * @param offset    zero-based byte offset of the field within the record image
     * @param width     width of the field in bytes
     */
    public record FieldSpec(String cobolName, int offset, int width) {

        /** Validates the shape of a field description. */
        public FieldSpec {
            Objects.requireNonNull(cobolName, "cobolName");
            if (cobolName.isBlank()) {
                throw new IllegalArgumentException("a field description must carry the legacy field "
                        + "name, because the name is what identifies the byte range");
            }
            if (offset < 0) {
                throw new IllegalArgumentException("field " + cobolName + " declares offset " + offset
                        + "; a record offset is zero-based and cannot be negative");
            }
            if (width < 1) {
                throw new IllegalArgumentException("field " + cobolName + " declares width " + width
                        + "; every field of every verified layout occupies at least one byte");
            }
        }

        /**
         * Returns the exclusive end offset, which is the next field's offset in a contiguous layout.
         *
         * @return {@code offset + width}
         */
        public int endOffset() {
            return offset + width;
        }

        /**
         * Returns the one-based starting column, which is how the external sort specifications and the
         * job control address this field.
         *
         * @return {@code offset + 1}
         */
        public int oneBasedPosition() {
            return offset + 1;
        }
    }

    /**
     * A complete legacy record layout: its data fields in record order, its total byte width, and the
     * byte its trailing filler is padded with.
     *
     * <p>The filler is described by subtraction rather than by a field, because a layout may have
     * none: the cross-reference fixture image is thirty-six bytes with no filler whatsoever, while
     * the same three fields in the mainframe data set are followed by fourteen filler bytes to reach
     * fifty. Both forms are correct, and modelling the filler as {@code recordLength - dataLength()}
     * lets one field list describe both.</p>
     *
     * @param name            a human-readable layout name, used in diagnostics
     * @param recordLength    total width of the record image in bytes
     * @param fillerCharacter byte the trailing filler is padded with; irrelevant, and never applied,
     *                        when {@link #fillerLength()} is zero
     * @param fields          the data fields in record order, contiguous from offset zero
     */
    public record RecordLayout(String name, int recordLength, char fillerCharacter,
            List<FieldSpec> fields) {

        /** Validates that the field list is contiguous from zero and fits inside the record. */
        public RecordLayout {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(fields, "fields");
            fields = List.copyOf(fields);
            if (fields.isEmpty()) {
                throw new IllegalArgumentException("layout " + name + " declares no field");
            }
            int cursor = 0;
            for (final FieldSpec field : fields) {
                if (field.offset() != cursor) {
                    throw new IllegalArgumentException("layout " + name + " places field "
                            + field.cobolName() + " at offset " + field.offset()
                            + " but the preceding fields end at " + cursor
                            + "; a record layout is contiguous from offset zero, and a gap or an "
                            + "overlap here means the field order is wrong");
                }
                cursor = field.endOffset();
            }
            if (cursor > recordLength) {
                throw new IllegalArgumentException("layout " + name + " declares " + cursor
                        + " bytes of data but a record length of only " + recordLength);
            }
        }

        /**
         * Returns the number of bytes the data fields occupy, filler excluded.
         *
         * @return the exclusive end offset of the last data field
         */
        public int dataLength() {
            return fields.get(fields.size() - 1).endOffset();
        }

        /**
         * Returns the number of trailing filler bytes, which may be zero.
         *
         * @return {@code recordLength - dataLength()}
         */
        public int fillerLength() {
            return recordLength - dataLength();
        }

        /**
         * Reports whether this layout carries trailing filler at all.
         *
         * @return {@code true} when at least one filler byte follows the data
         */
        public boolean hasFiller() {
            return fillerLength() > 0;
        }

        /**
         * Returns the trailing filler image, padded with this layout's own filler byte.
         *
         * @return the filler bytes, or the empty string when this layout has none
         */
        public String fillerImage() {
            return String.valueOf(fillerCharacter).repeat(fillerLength());
        }

        /**
         * Looks a field up by its legacy name.
         *
         * @param cobolName the legacy field name
         * @return the field description
         * @throws IllegalArgumentException if this layout declares no such field
         */
        public FieldSpec field(final String cobolName) {
            for (final FieldSpec candidate : fields) {
                if (candidate.cobolName().equals(cobolName)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("layout " + name + " declares no field named "
                    + cobolName + "; it declares " + fields.size() + " fields");
        }

        /**
         * Slices one field out of a record image of this layout.
         *
         * @param image     a record image measuring exactly {@link #recordLength()} bytes
         * @param cobolName the legacy field name to extract
         * @return the field's bytes, exactly as the image carries them, untrimmed
         * @throws IllegalArgumentException if the image is the wrong width or the field is unknown
         */
        public String slice(final String image, final String cobolName) {
            Objects.requireNonNull(image, "image");
            if (image.length() != recordLength) {
                throw new IllegalArgumentException("layout " + name + " measures " + recordLength
                        + " bytes but the supplied image measures " + image.length());
            }
            final FieldSpec field = field(cobolName);
            return image.substring(field.offset(), field.endOffset());
        }
    }

    // =============================================================================================
    // SECTION 5 - THE ELEVEN VERIFIED RECORD LAYOUTS
    //
    // Read field-by-field from the eleven copybooks and cross-checked against a byte census of the
    // nine delivered fixtures. Offsets are zero-based; every total was confirmed arithmetically and
    // is re-confirmed by RecordLayout's own contiguity check when this class initialises.
    // =============================================================================================

    /**
     * Account layout, three hundred bytes.
     *
     * <p><strong>THE FIELD-ORDER TRAP.</strong> The five monetary fields are not contiguous. The
     * current balance and the two credit limits occupy 12 through 47; the three dates occupy 48
     * through 77; the two cycle amounts occupy 78 through 101. Collecting all five amounts together
     * still yields a three-hundred byte image, which is why the defect is silent, but every offset
     * from 36 onward is then wrong.</p>
     *
     * <p>The seventh field's name is misspelled in the source. The misspelling is preserved here
     * because the name identifies the byte range; the corresponding Java property is spelled
     * correctly and the offset is unchanged.</p>
     */
    public static final RecordLayout ACCOUNT = new RecordLayout("account", 300, SPACE_FILLER,
            List.of(new FieldSpec("ACCT-ID", 0, 11),
                    new FieldSpec("ACCT-ACTIVE-STATUS", 11, 1),
                    new FieldSpec("ACCT-CURR-BAL", 12, 12),
                    new FieldSpec("ACCT-CREDIT-LIMIT", 24, 12),
                    new FieldSpec("ACCT-CASH-CREDIT-LIMIT", 36, 12),
                    new FieldSpec("ACCT-OPEN-DATE", 48, 10),
                    new FieldSpec("ACCT-EXPIRAION-DATE", 58, 10),
                    new FieldSpec("ACCT-REISSUE-DATE", 68, 10),
                    new FieldSpec("ACCT-CURR-CYC-CREDIT", 78, 12),
                    new FieldSpec("ACCT-CURR-CYC-DEBIT", 90, 12),
                    new FieldSpec("ACCT-ADDR-ZIP", 102, 10),
                    new FieldSpec("ACCT-GROUP-ID", 112, 10)));

    /**
     * Card layout, one hundred and fifty bytes. The fifth field's name carries the same misspelling
     * as the account expiry date, preserved for the same reason.
     */
    public static final RecordLayout CARD = new RecordLayout("card", 150, SPACE_FILLER,
            List.of(new FieldSpec("CARD-NUM", 0, 16),
                    new FieldSpec("CARD-ACCT-ID", 16, 11),
                    new FieldSpec("CARD-CVV-CD", 27, 3),
                    new FieldSpec("CARD-EMBOSSED-NAME", 30, 50),
                    new FieldSpec("CARD-EXPIRAION-DATE", 80, 10),
                    new FieldSpec("CARD-ACTIVE-STATUS", 90, 1)));

    /** The three cross-reference data fields, shared by both widths below. */
    private static final List<FieldSpec> CARD_CROSS_REFERENCE_FIELDS =
            List.of(new FieldSpec("XREF-CARD-NUM", 0, 16),
                    new FieldSpec("XREF-CUST-ID", 16, 9),
                    new FieldSpec("XREF-ACCT-ID", 25, 11));

    /**
     * Cross-reference layout as the delivered ASCII fixture carries it: thirty-six bytes, no filler.
     *
     * <p>This was settled by measurement rather than by reading. The fixture is 1,850 bytes over fifty
     * records - thirty-six data bytes and one line separator each - so every record ends on a digit
     * and there is no filler to pad. Padding this form to fifty bytes produces an image that will not
     * match the fixture.</p>
     *
     * @see #CARD_CROSS_REFERENCE_DATASET for the fifty-byte form, which is equally correct
     */
    public static final RecordLayout CARD_CROSS_REFERENCE_FIXTURE =
            new RecordLayout("card cross-reference (fixture image)", 36, SPACE_FILLER,
                    CARD_CROSS_REFERENCE_FIELDS);

    /**
     * Cross-reference layout as the mainframe sequential data set carries it: fifty bytes, the last
     * fourteen being filler.
     *
     * <p>Measured from the delivered encoded data set, which is 2,500 bytes over fifty records - fifty
     * bytes each, filler present. Every one of those filler bytes is the encoding's space character,
     * which is why the pad byte here is a space.</p>
     */
    public static final RecordLayout CARD_CROSS_REFERENCE_DATASET =
            new RecordLayout("card cross-reference (data set image)", 50, SPACE_FILLER,
                    CARD_CROSS_REFERENCE_FIELDS);

    /**
     * Customer layout, five hundred bytes.
     *
     * <p>A second copybook describes the same five hundred bytes at the same offsets and differs only
     * in the spelling of the date-of-birth field name. It is a second view of one entity, not a second
     * entity, so there is one layout here and one builder family.</p>
     */
    public static final RecordLayout CUSTOMER = new RecordLayout("customer", 500, SPACE_FILLER,
            List.of(new FieldSpec("CUST-ID", 0, 9),
                    new FieldSpec("CUST-FIRST-NAME", 9, 25),
                    new FieldSpec("CUST-MIDDLE-NAME", 34, 25),
                    new FieldSpec("CUST-LAST-NAME", 59, 25),
                    new FieldSpec("CUST-ADDR-LINE-1", 84, 50),
                    new FieldSpec("CUST-ADDR-LINE-2", 134, 50),
                    new FieldSpec("CUST-ADDR-LINE-3", 184, 50),
                    new FieldSpec("CUST-ADDR-STATE-CD", 234, 2),
                    new FieldSpec("CUST-ADDR-COUNTRY-CD", 236, 3),
                    new FieldSpec("CUST-ADDR-ZIP", 239, 10),
                    new FieldSpec("CUST-PHONE-NUM-1", 249, 15),
                    new FieldSpec("CUST-PHONE-NUM-2", 264, 15),
                    new FieldSpec("CUST-SSN", 279, 9),
                    new FieldSpec("CUST-GOVT-ISSUED-ID", 288, 20),
                    new FieldSpec("CUST-DOB-YYYY-MM-DD", 308, 10),
                    new FieldSpec("CUST-EFT-ACCOUNT-ID", 318, 10),
                    new FieldSpec("CUST-PRI-CARD-HOLDER-IND", 328, 1),
                    new FieldSpec("CUST-FICO-CREDIT-SCORE", 329, 3)));

    /**
     * Posted-transaction layout, three hundred and fifty bytes.
     *
     * <p><strong>THESE OFFSETS CARRY THREE EXTERNAL SORT CONTRACTS.</strong> The card number begins at
     * one-based column 263 for sixteen bytes and is addressed there by two different jobs, which type
     * it differently - one as character data and one as zoned decimal - so an ordering comparator
     * belongs to a single job and is never shared between them. The original timestamp begins at
     * zero-based 278. The processing timestamp begins at zero-based 304, which is both the alternate
     * index key position and the one-based column 305 the report filter addresses.</p>
     */
    public static final RecordLayout TRANSACTION = new RecordLayout("transaction", 350, SPACE_FILLER,
            List.of(new FieldSpec("TRAN-ID", 0, 16),
                    new FieldSpec("TRAN-TYPE-CD", 16, 2),
                    new FieldSpec("TRAN-CAT-CD", 18, 4),
                    new FieldSpec("TRAN-SOURCE", 22, 10),
                    new FieldSpec("TRAN-DESC", 32, 100),
                    new FieldSpec("TRAN-AMT", 132, 11),
                    new FieldSpec("TRAN-MERCHANT-ID", 143, 9),
                    new FieldSpec("TRAN-MERCHANT-NAME", 152, 50),
                    new FieldSpec("TRAN-MERCHANT-CITY", 202, 50),
                    new FieldSpec("TRAN-MERCHANT-ZIP", 252, 10),
                    new FieldSpec("TRAN-CARD-NUM", 262, 16),
                    new FieldSpec("TRAN-ORIG-TS", 278, 26),
                    new FieldSpec("TRAN-PROC-TS", 304, 26)));

    /**
     * Daily-transaction layout, three hundred and fifty bytes, structurally identical to the posted
     * layout and differing only in the field-name prefix.
     *
     * <p>It stays a separate layout and a separate entity because it is a distinct data set with a
     * distinct lifecycle: this is the landing image that validation reads and that the reject writer
     * copies verbatim into the first three hundred and fifty bytes of a reject record.</p>
     */
    public static final RecordLayout DAILY_TRANSACTION =
            new RecordLayout("daily transaction", 350, SPACE_FILLER,
                    List.of(new FieldSpec("DALYTRAN-ID", 0, 16),
                            new FieldSpec("DALYTRAN-TYPE-CD", 16, 2),
                            new FieldSpec("DALYTRAN-CAT-CD", 18, 4),
                            new FieldSpec("DALYTRAN-SOURCE", 22, 10),
                            new FieldSpec("DALYTRAN-DESC", 32, 100),
                            new FieldSpec("DALYTRAN-AMT", 132, 11),
                            new FieldSpec("DALYTRAN-MERCHANT-ID", 143, 9),
                            new FieldSpec("DALYTRAN-MERCHANT-NAME", 152, 50),
                            new FieldSpec("DALYTRAN-MERCHANT-CITY", 202, 50),
                            new FieldSpec("DALYTRAN-MERCHANT-ZIP", 252, 10),
                            new FieldSpec("DALYTRAN-CARD-NUM", 262, 16),
                            new FieldSpec("DALYTRAN-ORIG-TS", 278, 26),
                            new FieldSpec("DALYTRAN-PROC-TS", 304, 26)));

    /**
     * Transaction category balance layout, fifty bytes, padded with the ASCII digit zero.
     *
     * <p>Its composite key group spans the first seventeen bytes.</p>
     */
    public static final RecordLayout TRANSACTION_CATEGORY_BALANCE =
            new RecordLayout("transaction category balance", 50, ZERO_FILLER,
                    List.of(new FieldSpec("TRANCAT-ACCT-ID", 0, 11),
                            new FieldSpec("TRANCAT-TYPE-CD", 11, 2),
                            new FieldSpec("TRANCAT-CD", 13, 4),
                            new FieldSpec("TRAN-CAT-BAL", 17, 11)));

    /**
     * Disclosure group layout, fifty bytes, padded with the ASCII digit zero.
     *
     * <p>Its composite key group spans the first sixteen bytes, and its rate field is the estate's
     * only four-integer-digit signed amount.</p>
     */
    public static final RecordLayout DISCLOSURE_GROUP =
            new RecordLayout("disclosure group", 50, ZERO_FILLER,
                    List.of(new FieldSpec("DIS-ACCT-GROUP-ID", 0, 10),
                            new FieldSpec("DIS-TRAN-TYPE-CD", 10, 2),
                            new FieldSpec("DIS-TRAN-CAT-CD", 12, 4),
                            new FieldSpec("DIS-INT-RATE", 16, 6)));

    /** Transaction type layout, sixty bytes, padded with the ASCII digit zero. */
    public static final RecordLayout TRANSACTION_TYPE =
            new RecordLayout("transaction type", 60, ZERO_FILLER,
                    List.of(new FieldSpec("TRAN-TYPE", 0, 2),
                            new FieldSpec("TRAN-TYPE-DESC", 2, 50)));

    /**
     * Transaction category layout, sixty bytes, padded with the ASCII digit zero.
     *
     * <p><strong>DO NOT CONFLATE THE TWO KEY GROUPS.</strong> This layout's composite key group and
     * the category-balance layout's composite key group share a name in the source and are entirely
     * different keys: six bytes of type and category here, seventeen bytes of account, type and
     * category there. They are represented by two distinct identifier classes.</p>
     */
    public static final RecordLayout TRANSACTION_CATEGORY =
            new RecordLayout("transaction category", 60, ZERO_FILLER,
                    List.of(new FieldSpec("TRAN-TYPE-CD", 0, 2),
                            new FieldSpec("TRAN-CAT-CD", 2, 4),
                            new FieldSpec("TRAN-CAT-TYPE-DESC", 6, 50)));

    /**
     * User security layout, eighty bytes.
     *
     * <p>The trailing twenty-three bytes are a <em>named</em> filler rather than an anonymous one -
     * see {@link #USER_SECURITY_FILLER_NAME}. The credential field occupies the eight bytes at
     * zero-based offset 48, which is one-based columns 49 through 56; that position, and only that
     * position, is how {@link #fixtureCredentialWindow()} recovers what the fixture carries there.</p>
     */
    public static final RecordLayout USER_SECURITY = new RecordLayout("user security", 80,
            SPACE_FILLER,
            List.of(new FieldSpec("SEC-USR-ID", 0, 8),
                    new FieldSpec("SEC-USR-FNAME", 8, 20),
                    new FieldSpec("SEC-USR-LNAME", 28, 20),
                    new FieldSpec("SEC-USR-PWD", 48, 8),
                    new FieldSpec("SEC-USR-TYPE", 56, 1)));

    /**
     * Name of the user-security layout's trailing filler.
     *
     * <p>Recorded because it is the estate's only named filler; every other layout ends in an
     * anonymous one. It is held as metadata rather than as a field so that
     * {@link RecordLayout#fillerLength()} keeps reporting twenty-three rather than zero.</p>
     */
    public static final String USER_SECURITY_FILLER_NAME = "SEC-USR-FILLER";

    /** Every layout above, in record-length order, for a test that sweeps all eleven. */
    public static final List<RecordLayout> ALL_LAYOUTS = List.of(ACCOUNT, CARD,
            CARD_CROSS_REFERENCE_DATASET, CUSTOMER, TRANSACTION, DAILY_TRANSACTION,
            TRANSACTION_CATEGORY_BALANCE, DISCLOSURE_GROUP, TRANSACTION_TYPE, TRANSACTION_CATEGORY,
            USER_SECURITY);

    // ---------------------------------------------------------------------------------------------
    // Composite key group widths, and the sort positions three external contracts address.
    // ---------------------------------------------------------------------------------------------

    /** Width of the category-balance composite key group: account, type and category. */
    public static final int CATEGORY_BALANCE_KEY_WIDTH = 17;

    /** Width of the disclosure-group composite key group: group, type and category. */
    public static final int DISCLOSURE_GROUP_KEY_WIDTH = 16;

    /** Width of the transaction-category composite key group: type and category. */
    public static final int TRANSACTION_CATEGORY_KEY_WIDTH = 6;

    /** One-based column at which both transaction layouts carry the card number. */
    public static final int SORT_CARD_NUMBER_POSITION = 263;

    /** Width the sort specifications declare for the card-number key. */
    public static final int SORT_CARD_NUMBER_WIDTH = 16;

    /** One-based column at which the report filter reads the processing date. */
    public static final int SORT_PROCESSING_DATE_POSITION = 305;

    /** Width the report filter declares for the processing-date key. */
    public static final int SORT_PROCESSING_DATE_WIDTH = 10;

    // =============================================================================================
    // SECTION 6 - FIXED-WIDTH PRIMITIVES
    //
    // Plain string arithmetic, deliberately. Nothing here consults a production assembler, a
    // production mapper or a formatting library: an oracle that shares an implementation with the
    // thing it measures cannot detect a defect in that implementation.
    // =============================================================================================

    /** The twenty-six lower-case letters the legacy folding table translates from, in order. */
    private static final String ASCII_LOWER = "abcdefghijklmnopqrstuvwxyz";

    /** The twenty-six upper-case letters the legacy folding table translates to, in order. */
    private static final String ASCII_UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /**
     * Pads a value on the right to a fixed width with a chosen byte.
     *
     * @param value         the value to place at the start of the field
     * @param width         the field width in bytes
     * @param padCharacter  the byte to pad with
     * @param fieldName     the legacy field name, used only to make a failure actionable
     * @return the field image, exactly {@code width} bytes long
     * @throws IllegalArgumentException if the value is wider than the field
     */
    public static String padded(final String value, final int width, final char padCharacter,
            final String fieldName) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(fieldName, "fieldName");
        if (value.length() > width) {
            throw new IllegalArgumentException("field " + fieldName + " is " + width
                    + " bytes wide but a value of " + value.length()
                    + " bytes was supplied; a fixed-width field neither grows nor silently truncates");
        }
        return value + String.valueOf(padCharacter).repeat(width - value.length());
    }

    /**
     * Renders a text field: the value, then spaces to the declared width.
     *
     * @param value     the value to place at the start of the field
     * @param width     the field width in bytes
     * @param fieldName the legacy field name, used only to make a failure actionable
     * @return the field image, exactly {@code width} bytes long
     */
    public static String alphanumeric(final String value, final int width, final String fieldName) {
        return padded(value, width, SPACE_FILLER, fieldName);
    }

    /**
     * Renders an unsigned numeric field: the digits, right-justified and zero-filled on the left, as
     * every unsigned numeric field of every layout carries them.
     *
     * @param value     the digits, with or without their leading zeros
     * @param width     the field width in bytes
     * @param fieldName the legacy field name, used only to make a failure actionable
     * @return the field image, exactly {@code width} bytes long
     * @throws IllegalArgumentException if the value is not all digits or is wider than the field
     */
    public static String digits(final String value, final int width, final String fieldName) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(fieldName, "fieldName");
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            if (character < '0' || character > '9') {
                throw new IllegalArgumentException("field " + fieldName
                        + " is an unsigned numeric field, so every byte must be a digit, but the"
                        + " value supplied carries a non-digit at position " + index);
            }
        }
        if (value.length() > width) {
            throw new IllegalArgumentException("field " + fieldName + " is " + width
                    + " bytes wide but " + value.length() + " digits were supplied");
        }
        return String.valueOf(ZERO_FILLER).repeat(width - value.length()) + value;
    }

    /**
     * Folds a value to upper case using the legacy twenty-six character translation table and nothing
     * else.
     *
     * <p>This is deliberately not the platform's own case conversion, which is locale-sensitive and
     * Unicode-aware and therefore transforms characters the legacy table leaves untouched. Only the
     * twenty-six unaccented lower-case letters are folded here; every other byte is passed through
     * unchanged, which is exactly what the legacy translation does.</p>
     *
     * @param value the value to fold
     * @return the folded value, the same length as the input
     */
    public static String asciiUpperFold(final String value) {
        Objects.requireNonNull(value, "value");
        final StringBuilder folded = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            final int position = ASCII_LOWER.indexOf(character);
            folded.append(position < 0 ? character : ASCII_UPPER.charAt(position));
        }
        return folded.toString();
    }

    /**
     * Right-justifies a value in a field and replaces every remaining blank with the ASCII digit zero,
     * reproducing the legacy menu-option normalisation.
     *
     * <p>The legacy field is right-justified and every space in it is replaced by a zero, so a single
     * digit becomes a zero-filled two-digit value. It is written here as the byte substitution the
     * source performs rather than as a numeric parse and re-render, because the two differ on a field
     * that is entirely blank: the substitution yields all zeros, a parse fails.</p>
     *
     * @param value the value to justify
     * @param width the field width in bytes
     * @return the normalised field image, exactly {@code width} bytes long
     * @throws IllegalArgumentException if the value is wider than the field
     */
    public static String rightJustifyZeroFill(final String value, final int width) {
        Objects.requireNonNull(value, "value");
        if (value.length() > width) {
            throw new IllegalArgumentException("a right-justified field of " + width
                    + " bytes cannot carry a value of " + value.length() + " bytes");
        }
        final String justified =
                String.valueOf(SPACE_FILLER).repeat(width - value.length()) + value;
        return justified.replace(SPACE_FILLER, ZERO_FILLER);
    }

    // =============================================================================================
    // SECTION 7 - ZONED DECIMAL WITH AN OVERPUNCHED SIGN, IMPLEMENTED INDEPENDENTLY
    //
    // Every persisted monetary and rate field in the estate is zoned decimal held as display
    // characters; packed decimal occurs once in nineteen thousand lines, on a screen work field that
    // is never written to a file. The sign is folded into the FINAL byte, which encodes both the
    // low-order digit and the sign.
    //
    // THE ROUNDING MODE IS A MEASURED CONSEQUENCE, NOT A PREFERENCE. Searching every program and
    // every copybook for a rounding clause returns zero occurrences, and an arithmetic store without
    // one truncates toward zero. So truncation is what the codec applies. The conventional Java
    // choice - banker's rounding to the nearest even cent - would differ by one cent on roughly half
    // of all interest computations, and would do so identically in the system and in this oracle if
    // this oracle delegated to the system. It does not delegate.
    // =============================================================================================

    /** Number of fraction digits every monetary and rate field carries. */
    public static final int MONETARY_SCALE = 2;

    /**
     * Rounding applied when a supplied amount carries more precision than the field holds:
     * truncation toward zero, because no rounding clause exists anywhere in the estate.
     */
    public static final RoundingMode MONETARY_ROUNDING = RoundingMode.DOWN;

    /** Width of each of the five account amount fields: ten integer digits and two fraction digits. */
    public static final int ACCOUNT_AMOUNT_WIDTH = 12;

    /**
     * Width of the transaction, daily-transaction and category-balance amount fields: nine integer
     * digits and two fraction digits.
     */
    public static final int TRANSACTION_AMOUNT_WIDTH = 11;

    /**
     * Width of the disclosure interest rate, the estate's only four-integer-digit amount: four
     * integer digits and two fraction digits.
     */
    public static final int INTEREST_RATE_WIDTH = 6;

    /** Final-byte encoding of a non-negative value, indexed by the low-order digit. */
    public static final String POSITIVE_OVERPUNCH = "{ABCDEFGHI";

    /** Final-byte encoding of a negative value, indexed by the low-order digit. */
    public static final String NEGATIVE_OVERPUNCH = "}JKLMNOPQR";

    /**
     * Encodes an amount as a zoned decimal field with the sign overpunched into the final byte.
     *
     * <p>The amount is first brought to two fraction digits by truncation, then rendered as unsigned
     * digits, zero-filled on the left to the field width, and the final digit is replaced by the byte
     * that carries both that digit and the sign.</p>
     *
     * @param amount the amount to encode
     * @param width  the field width in bytes, fraction digits included
     * @return the field image, exactly {@code width} bytes long
     * @throws IllegalArgumentException if the field is too narrow to hold two fraction digits, or the
     *                                  truncated amount needs more integer digits than the field has
     */
    public static String encodeZonedDecimal(final BigDecimal amount, final int width) {
        Objects.requireNonNull(amount, "amount");
        if (width <= MONETARY_SCALE) {
            throw new IllegalArgumentException("a zoned decimal field of " + width
                    + " bytes cannot carry " + MONETARY_SCALE
                    + " fraction digits and at least one integer digit");
        }
        final BigDecimal truncated = amount.setScale(MONETARY_SCALE, MONETARY_ROUNDING);
        final String unsigned = truncated.abs().unscaledValue().toString();
        if (unsigned.length() > width) {
            throw new IllegalArgumentException("the amount " + truncated
                    + " needs " + unsigned.length() + " digits but the field holds only " + width);
        }
        final String justified =
                String.valueOf(ZERO_FILLER).repeat(width - unsigned.length()) + unsigned;
        final int lowOrderDigit = justified.charAt(width - 1) - '0';
        final String table = truncated.signum() < 0 ? NEGATIVE_OVERPUNCH : POSITIVE_OVERPUNCH;
        return justified.substring(0, width - 1) + table.charAt(lowOrderDigit);
    }

    /**
     * Decodes a zoned decimal field whose sign is overpunched into its final byte.
     *
     * @param field the field image, fraction digits included
     * @return the amount, at two fraction digits
     * @throws IllegalArgumentException if the field is too narrow, carries a non-digit before its
     *                                  final byte, or its final byte is not a recognised overpunch
     */
    public static BigDecimal decodeZonedDecimal(final String field) {
        Objects.requireNonNull(field, "field");
        if (field.length() <= MONETARY_SCALE) {
            throw new IllegalArgumentException("a zoned decimal field of " + field.length()
                    + " bytes cannot carry " + MONETARY_SCALE
                    + " fraction digits and at least one integer digit");
        }
        final int lastIndex = field.length() - 1;
        for (int index = 0; index < lastIndex; index++) {
            final char character = field.charAt(index);
            if (character < '0' || character > '9') {
                throw new IllegalArgumentException("a zoned decimal field carries digits in every"
                        + " byte but its last, yet a non-digit appears at position " + index);
            }
        }
        final char overpunch = field.charAt(lastIndex);
        int lowOrderDigit = POSITIVE_OVERPUNCH.indexOf(overpunch);
        boolean negative = false;
        if (lowOrderDigit < 0) {
            lowOrderDigit = NEGATIVE_OVERPUNCH.indexOf(overpunch);
            negative = true;
        }
        if (lowOrderDigit < 0) {
            throw new IllegalArgumentException("the final byte of a zoned decimal field carries both"
                    + " the low-order digit and the sign, but the byte supplied belongs to neither"
                    + " the non-negative nor the negative encoding");
        }
        final String unsigned = field.substring(0, lastIndex) + lowOrderDigit;
        final BigDecimal magnitude = new BigDecimal(unsigned).movePointLeft(MONETARY_SCALE);
        return negative ? magnitude.negate() : magnitude;
    }

    /**
     * Brings an amount to the two fraction digits a stored field holds, truncating toward zero.
     *
     * @param amount the amount to normalise
     * @return the amount at two fraction digits
     */
    public static BigDecimal storedAmount(final BigDecimal amount) {
        Objects.requireNonNull(amount, "amount");
        return amount.setScale(MONETARY_SCALE, MONETARY_ROUNDING);
    }

    /**
     * Formats an instant as the twenty-six character timestamp text a record carries.
     *
     * @param instant the instant to render, read at the coordinated universal offset
     * @return the twenty-six character timestamp text
     */
    public static String recordTimestamp(final Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return RECORD_TIMESTAMP_FORMATTER.format(instant);
    }

    /**
     * Formats a date as the ten character date text every date field of every layout carries.
     *
     * @param date the date to render
     * @return the ten character date text
     */
    public static String recordDate(final LocalDate date) {
        Objects.requireNonNull(date, "date");
        return RECORD_DATE_FORMATTER.format(date);
    }

    /**
     * Renders a date as a processing timestamp: the ten character date, then blanks to twenty-six.
     *
     * <p>This is the shape a constructed date-window record needs. The report filter reads the first
     * ten bytes of the field as character data, so a record whose processing timestamp begins with a
     * date is selected by a window containing that date and rejected by one that does not - which is
     * precisely what the delivered fixture cannot exercise, because its processing timestamp is
     * blank on every record.</p>
     *
     * @param date the processing date to place at the start of the field
     * @return a twenty-six byte processing timestamp beginning with the date
     */
    public static String processingTimestampFor(final LocalDate date) {
        return alphanumeric(recordDate(date), TIMESTAMP_TEXT_WIDTH, "PROC-TS");
    }

    // =============================================================================================
    // SECTION 8 - THE MEASURED SEED CENSUS, AND THE TWO ANOMALIES THAT FORCE CONSTRUCTED FIXTURES
    //
    // Every count below was measured from the delivered fixtures rather than read from a document.
    // Two of the measurements are load-bearing, because each makes a branch unreachable from seeded
    // data and therefore makes a constructed fixture mandatory rather than convenient.
    // =============================================================================================

    /** Accounts, cards, cross-references, customers and category balances the reference seed loads. */
    public static final int SEEDED_FIFTY_ROW_COUNT = 50;

    /** Daily transactions the reference seed loads. */
    public static final int SEEDED_DAILY_TRANSACTION_COUNT = 300;

    /** Of those, the count carrying the point-of-sale source and a non-negative amount. */
    public static final int SEEDED_PURCHASE_COUNT = 250;

    /** Of those, the count carrying the operator source and a negative amount. */
    public static final int SEEDED_RETURN_COUNT = 50;

    /** Disclosure-group rows the reference seed loads: three complete groups. */
    public static final int SEEDED_DISCLOSURE_GROUP_COUNT = 51;

    /** Rows in each of the three disclosure groups. */
    public static final int DISCLOSURE_GROUP_ROWS_PER_GROUP = 17;

    /** Transaction categories the reference seed loads. */
    public static final int SEEDED_TRANSACTION_CATEGORY_COUNT = 18;

    /** Transaction types the reference seed loads. */
    public static final int SEEDED_TRANSACTION_TYPE_COUNT = 7;

    /**
     * Posted transactions the reference seed loads: none.
     *
     * <p>The posted-transaction table is seeded empty, so a test that needs one builds it. Nothing
     * here may assume a seeded posted transaction exists.</p>
     */
    public static final int SEEDED_TRANSACTION_COUNT = 0;

    /** Sign-on identities the credential seed loads, once it is applied. */
    public static final int SEEDED_USER_COUNT = 10;

    /**
     * The address postcode every one of the fifty seeded accounts carries.
     *
     * <p>It looks like a group identifier and is not one: it sits in the postcode field.</p>
     */
    public static final String SEEDED_ACCOUNT_ADDRESS_ZIP = "A000000000";

    /**
     * The group identifier every one of the fifty seeded accounts carries: ten blanks.
     *
     * <p><strong>THIS IS LOAD-BEARING.</strong> Because no seeded account names a real group, the
     * seeded interest lookup misses the direct disclosure group on every account and therefore only
     * ever exercises the fallback path. A direct-hit test is impossible from seeded data alone, which
     * is why {@link #directHitDisclosureAccount()} exists.</p>
     *
     * <p>The account's group identifier also carries no referential constraint to the disclosure
     * group: it is a partial, non-unique reference resolved at run time with a fallback, so none may
     * be added or assumed.</p>
     */
    public static final String SEEDED_ACCOUNT_GROUP_ID =
            String.valueOf(SPACE_FILLER).repeat(10);

    /**
     * A real ten-character group identifier, being the first of the three seeded disclosure groups.
     *
     * @see #directHitDisclosureAccount()
     */
    public static final String DIRECT_HIT_DISCLOSURE_GROUP_ID = "A000000000";

    /**
     * The fallback disclosure group's identifier, blank-padded to ten characters and never trimmed.
     *
     * <p>The padding is part of the key. Trimming it, or writing the bare word, produces a key that
     * matches nothing.</p>
     */
    public static final String FALLBACK_DISCLOSURE_GROUP_ID = "DEFAULT   ";

    /** The zero-rate disclosure group's identifier, blank-padded to ten characters and never trimmed. */
    public static final String ZERO_RATE_DISCLOSURE_GROUP_ID = "ZEROAPR   ";

    /** All three seeded disclosure group identifiers, in the order the fixture carries them. */
    public static final List<String> SEEDED_DISCLOSURE_GROUP_IDS = List.of(
            DIRECT_HIT_DISCLOSURE_GROUP_ID, FALLBACK_DISCLOSURE_GROUP_ID,
            ZERO_RATE_DISCLOSURE_GROUP_ID);

    /** Lowest credit score the account-update surface accepts. */
    public static final int CREDIT_SCORE_MINIMUM = 300;

    /** Highest credit score the account-update surface accepts. */
    public static final int CREDIT_SCORE_MAXIMUM = 850;

    // =============================================================================================
    // SECTION 9 - MEASURED DEFAULTS
    //
    // Each value below is the corresponding field of the FIRST record of the delivered fixture, read
    // at the verified offset. Three defaults deliberately depart from the fixture, and each departure
    // is stated where it is declared: the two regulated customer identifiers, which the entity refuses
    // in cleartext, and the customer credit score, which the fixture carries out of range.
    // =============================================================================================

    private static final String DEFAULT_ACCOUNT_ID = "00000000001";
    private static final String DEFAULT_ACTIVE_INDICATOR = "Y";
    private static final BigDecimal DEFAULT_CURRENT_BALANCE = new BigDecimal("194.00");
    private static final BigDecimal DEFAULT_CREDIT_LIMIT = new BigDecimal("2020.00");
    private static final BigDecimal DEFAULT_CASH_CREDIT_LIMIT = new BigDecimal("1020.00");
    private static final String DEFAULT_ACCOUNT_OPEN_DATE = "2014-11-20";
    private static final String DEFAULT_ACCOUNT_EXPIRATION_DATE = "2025-05-20";
    private static final String DEFAULT_ACCOUNT_REISSUE_DATE = "2025-05-20";
    private static final BigDecimal DEFAULT_ZERO_AMOUNT = new BigDecimal("0.00");

    private static final String DEFAULT_CARD_NUMBER = "0500024453765740";
    private static final String DEFAULT_CARD_ACCOUNT_ID = "00000000050";
    private static final String DEFAULT_CARD_VERIFICATION_CODE = "747";
    private static final String DEFAULT_EMBOSSED_NAME = "Aniya Von";
    private static final String DEFAULT_CARD_EXPIRATION_DATE = "2023-03-09";

    private static final String DEFAULT_CUSTOMER_ID = "000000001";
    private static final String DEFAULT_FIRST_NAME = "Immanuel";
    private static final String DEFAULT_MIDDLE_NAME = "Madeline";
    private static final String DEFAULT_LAST_NAME = "Kessler";
    private static final String DEFAULT_ADDRESS_LINE_1 = "618 Deshaun Route";
    private static final String DEFAULT_ADDRESS_LINE_2 = "Apt. 802";
    private static final String DEFAULT_ADDRESS_LINE_3 = "Altenwerthshire";
    private static final String DEFAULT_STATE_CODE = "NC";
    private static final String DEFAULT_COUNTRY_CODE = "USA";
    private static final String DEFAULT_CUSTOMER_ZIP = "12546";
    private static final String DEFAULT_PHONE_NUMBER_1 = "(908)119-8310";
    private static final String DEFAULT_PHONE_NUMBER_2 = "(373)693-8684";
    private static final String DEFAULT_DATE_OF_BIRTH = "1961-06-08";
    private static final String DEFAULT_ELECTRONIC_TRANSFER_ACCOUNT_ID = "0053581756";

    /**
     * Default credit score, chosen inside the enforced range.
     *
     * <p>A deliberate departure from the fixture. Twenty-one of the fifty seeded customers carry a
     * score below the enforced lower bound - the first carries 274 - so taking the first record's
     * value as the default would make every builder produce input the update surface refuses.
     * Builders may still set an out-of-range value for a negative test.</p>
     */
    private static final String DEFAULT_CREDIT_SCORE = "750";

    /**
     * Envelope marker the customer entity requires on both regulated identifiers.
     *
     * <p>The entity refuses cleartext in either attribute, so a builder default cannot be the nine or
     * twenty cleartext characters the record image carries. The two values below are synthetic
     * envelopes whose bodies decode to twenty-nine bytes - one more than the entity's minimum - and
     * they protect nothing, because there is nothing here to protect.</p>
     */
    private static final String PROTECTED_VALUE_MARKER = "ENC1:";

    /** Synthetic protected value for the national identifier attribute. */
    public static final String SYNTHETIC_PROTECTED_VALUE =
            PROTECTED_VALUE_MARKER + "U1lOVEhFVElDLUZJWFRVUkUtRU5WRUxPUEUtMDE=";

    /** A second, distinct synthetic protected value, for the government-issued identifier attribute. */
    public static final String SYNTHETIC_PROTECTED_VALUE_SECOND =
            PROTECTED_VALUE_MARKER + "U1lOVEhFVElDLUZJWFRVUkUtRU5WRUxPUEUtMDI=";

    /**
     * Record-image text for the national identifier: nine synthetic digits.
     *
     * <p>The delivered fixture carries a nine-digit cleartext value here. It is not reproduced as a
     * literal in this file: the attribute is regulated, the schema holds it as a protected value, and
     * the reference seed leaves it absent on all fifty rows. A builder therefore renders a clearly
     * synthetic value into the image and hands the entity either an envelope or nothing at all.</p>
     */
    private static final String DEFAULT_NATIONAL_IDENTIFIER_DIGITS = "000000000";

    /** Record-image text for the government-issued identifier: twenty synthetic digits. */
    private static final String DEFAULT_GOVERNMENT_IDENTIFIER_DIGITS = "00000000000000000000";

    private static final String DEFAULT_TRANSACTION_ID = "0000000000683580";
    private static final String DEFAULT_TRANSACTION_TYPE_CODE = "01";
    private static final String DEFAULT_RETURN_TYPE_CODE = "03";
    private static final String DEFAULT_TRANSACTION_CATEGORY_CODE = "0001";
    private static final String DEFAULT_TRANSACTION_DESCRIPTION = "Purchase at Abshire-Lowe";
    private static final BigDecimal DEFAULT_TRANSACTION_AMOUNT = new BigDecimal("504.77");
    private static final String DEFAULT_MERCHANT_ID = "800000000";
    private static final String DEFAULT_MERCHANT_NAME = "Abshire-Lowe";
    private static final String DEFAULT_MERCHANT_CITY = "North Enoshaven";
    private static final String DEFAULT_MERCHANT_ZIP = "72112";
    private static final String DEFAULT_TRANSACTION_CARD_NUMBER = "4859452612877065";

    private static final BigDecimal DEFAULT_INTEREST_RATE = new BigDecimal("15.00");
    private static final String DEFAULT_TRANSACTION_TYPE_DESCRIPTION = "Purchase";
    private static final String DEFAULT_TRANSACTION_CATEGORY_DESCRIPTION = "Regular Sales Draft";

    /**
     * A card number, account identifier and customer identifier that exist in no seeded row.
     *
     * <p>The landing table deliberately carries no referential constraints, so a record naming these
     * can be written and will then fail validation - which is the only way to reach the reject reasons
     * that fire when a card or an account cannot be found.</p>
     */
    public static final String UNKNOWN_CARD_NUMBER = "9999999999999999";

    /** An account identifier that exists in no seeded row. */
    public static final String UNKNOWN_ACCOUNT_ID = "99999999999";

    /** A customer identifier that exists in no seeded row. */
    public static final String UNKNOWN_CUSTOMER_ID = "999999999";

    // =============================================================================================
    // SECTION 10 - IMAGE ASSEMBLY HELPERS
    //
    // Every field width comes from the layout rather than from a repeated literal, so a builder and
    // its layout cannot disagree, and every assembled image is measured before it is returned.
    // =============================================================================================

    /**
     * Renders a text field at the width its layout declares.
     *
     * @param layout    the layout the field belongs to
     * @param fieldName the legacy field name
     * @param value     the value to place at the start of the field
     * @return the field image
     */
    private static String text(final RecordLayout layout, final String fieldName,
            final String value) {
        return alphanumeric(value, layout.field(fieldName).width(), fieldName);
    }

    /**
     * Renders an unsigned numeric field at the width its layout declares.
     *
     * @param layout    the layout the field belongs to
     * @param fieldName the legacy field name
     * @param value     the digits, with or without their leading zeros
     * @return the field image
     */
    private static String numeric(final RecordLayout layout, final String fieldName,
            final String value) {
        return digits(value, layout.field(fieldName).width(), fieldName);
    }

    /**
     * Renders a signed zoned-decimal field at the width its layout declares.
     *
     * @param layout    the layout the field belongs to
     * @param fieldName the legacy field name
     * @param value     the amount
     * @return the field image, its final byte carrying both the low-order digit and the sign
     */
    private static String amount(final RecordLayout layout, final String fieldName,
            final BigDecimal value) {
        return encodeZonedDecimal(value, layout.field(fieldName).width());
    }

    /**
     * Appends a layout's trailing filler and proves the assembled image measures what it must.
     *
     * <p>The two checks are not defensive noise. The first catches a builder that renders its fields
     * in the wrong order or omits one; the second catches a layout whose filler length disagrees with
     * its record length. Either defect would otherwise surface as an unexplained byte-comparison
     * failure much later.</p>
     *
     * @param layout the layout being assembled
     * @param data   the concatenated data fields, filler excluded
     * @return the complete record image
     */
    private static String complete(final RecordLayout layout, final String data) {
        if (data.length() != layout.dataLength()) {
            throw new IllegalStateException("layout " + layout.name() + " declares "
                    + layout.dataLength() + " bytes of data but the assembled fields measure "
                    + data.length() + "; a field is missing, duplicated or out of order");
        }
        final String image = data + layout.fillerImage();
        if (image.length() != layout.recordLength()) {
            throw new IllegalStateException("layout " + layout.name() + " declares a record length of "
                    + layout.recordLength() + " but the assembled image measures " + image.length());
        }
        return image;
    }

    // =============================================================================================
    // SECTION 11 - ENTITY AND RECORD-IMAGE BUILDERS
    //
    // One hand-written builder per layout. Each answers build() for the production entity and image()
    // for the fixed-width record image, so a test can assert a persisted row and a byte stream from
    // one description. Every builder is final and assigns only fields in its constructor, so none can
    // publish a partially built instance.
    // =============================================================================================

    /**
     * Starts an account, defaulted to the first seeded account record.
     *
     * @return a new builder
     */
    public static AccountBuilder account() {
        return new AccountBuilder();
    }

    /**
     * Builds an account entity and the three-hundred byte account record image.
     *
     * <p>The image renders the fields in the order the layout declares them, which is the order that
     * matters: the balance and the two credit limits, then the three dates, then the two cycle
     * amounts. The layout refuses any other arrangement, so this order cannot drift.</p>
     */
    public static final class AccountBuilder {

        private String acctId = DEFAULT_ACCOUNT_ID;
        private String activeStatus = DEFAULT_ACTIVE_INDICATOR;
        private BigDecimal currentBalance = DEFAULT_CURRENT_BALANCE;
        private BigDecimal creditLimit = DEFAULT_CREDIT_LIMIT;
        private BigDecimal cashCreditLimit = DEFAULT_CASH_CREDIT_LIMIT;
        private String openDate = DEFAULT_ACCOUNT_OPEN_DATE;
        private String expirationDate = DEFAULT_ACCOUNT_EXPIRATION_DATE;
        private String reissueDate = DEFAULT_ACCOUNT_REISSUE_DATE;
        private BigDecimal cycleCredit = DEFAULT_ZERO_AMOUNT;
        private BigDecimal cycleDebit = DEFAULT_ZERO_AMOUNT;
        private String addressZip = SEEDED_ACCOUNT_ADDRESS_ZIP;
        private String groupId = SEEDED_ACCOUNT_GROUP_ID;

        private AccountBuilder() {
            // Intentionally empty: every field carries a measured default.
        }

        /**
         * Sets the eleven-digit account identifier, which is the business key.
         *
         * @param value the identifier
         * @return this builder
         */
        public AccountBuilder acctId(final String value) {
            this.acctId = value;
            return this;
        }

        /**
         * Sets the one-character active indicator.
         *
         * @param value the indicator
         * @return this builder
         */
        public AccountBuilder activeStatus(final String value) {
            this.activeStatus = value;
            return this;
        }

        /**
         * Sets the current balance.
         *
         * @param value the amount
         * @return this builder
         */
        public AccountBuilder currentBalance(final BigDecimal value) {
            this.currentBalance = value;
            return this;
        }

        /**
         * Sets the credit limit.
         *
         * @param value the amount
         * @return this builder
         */
        public AccountBuilder creditLimit(final BigDecimal value) {
            this.creditLimit = value;
            return this;
        }

        /**
         * Sets the cash credit limit.
         *
         * @param value the amount
         * @return this builder
         */
        public AccountBuilder cashCreditLimit(final BigDecimal value) {
            this.cashCreditLimit = value;
            return this;
        }

        /**
         * Sets the ten-character opening date.
         *
         * @param value the date text
         * @return this builder
         */
        public AccountBuilder openDate(final String value) {
            this.openDate = value;
            return this;
        }

        /**
         * Sets the ten-character expiry date, whose legacy field name is misspelled.
         *
         * @param value the date text
         * @return this builder
         */
        public AccountBuilder expirationDate(final String value) {
            this.expirationDate = value;
            return this;
        }

        /**
         * Sets the ten-character reissue date.
         *
         * @param value the date text
         * @return this builder
         */
        public AccountBuilder reissueDate(final String value) {
            this.reissueDate = value;
            return this;
        }

        /**
         * Sets the current-cycle credit amount.
         *
         * @param value the amount
         * @return this builder
         */
        public AccountBuilder cycleCredit(final BigDecimal value) {
            this.cycleCredit = value;
            return this;
        }

        /**
         * Sets the current-cycle debit amount.
         *
         * @param value the amount
         * @return this builder
         */
        public AccountBuilder cycleDebit(final BigDecimal value) {
            this.cycleDebit = value;
            return this;
        }

        /**
         * Sets the ten-character address postcode.
         *
         * @param value the postcode
         * @return this builder
         */
        public AccountBuilder addressZip(final String value) {
            this.addressZip = value;
            return this;
        }

        /**
         * Sets the ten-character group identifier.
         *
         * <p>Supplying a real group identifier here is what makes the direct disclosure-group lookup
         * reachable, because every seeded account leaves this blank.</p>
         *
         * @param value the group identifier, blank-padded to ten characters
         * @return this builder
         */
        public AccountBuilder groupId(final String value) {
            this.groupId = value;
            return this;
        }

        /**
         * Builds the entity.
         *
         * @return an account carrying the values configured on this builder
         */
        public Account build() {
            return new Account(acctId, activeStatus, storedAmount(currentBalance),
                    storedAmount(creditLimit), storedAmount(cashCreditLimit), openDate,
                    expirationDate, reissueDate, storedAmount(cycleCredit), storedAmount(cycleDebit),
                    addressZip, groupId);
        }

        /**
         * Builds the record image.
         *
         * @return the three-hundred byte account record image
         */
        public String image() {
            return complete(ACCOUNT, numeric(ACCOUNT, "ACCT-ID", acctId)
                    + text(ACCOUNT, "ACCT-ACTIVE-STATUS", activeStatus)
                    + amount(ACCOUNT, "ACCT-CURR-BAL", currentBalance)
                    + amount(ACCOUNT, "ACCT-CREDIT-LIMIT", creditLimit)
                    + amount(ACCOUNT, "ACCT-CASH-CREDIT-LIMIT", cashCreditLimit)
                    + text(ACCOUNT, "ACCT-OPEN-DATE", openDate)
                    + text(ACCOUNT, "ACCT-EXPIRAION-DATE", expirationDate)
                    + text(ACCOUNT, "ACCT-REISSUE-DATE", reissueDate)
                    + amount(ACCOUNT, "ACCT-CURR-CYC-CREDIT", cycleCredit)
                    + amount(ACCOUNT, "ACCT-CURR-CYC-DEBIT", cycleDebit)
                    + text(ACCOUNT, "ACCT-ADDR-ZIP", addressZip)
                    + text(ACCOUNT, "ACCT-GROUP-ID", groupId));
        }
    }

    /**
     * Starts a card, defaulted to the first seeded card record.
     *
     * @return a new builder
     */
    public static CardBuilder card() {
        return new CardBuilder();
    }

    /** Builds a card entity and the one-hundred-and-fifty byte card record image. */
    public static final class CardBuilder {

        private String cardNumber = DEFAULT_CARD_NUMBER;
        private String accountId = DEFAULT_CARD_ACCOUNT_ID;
        private String verificationCode = DEFAULT_CARD_VERIFICATION_CODE;
        private String embossedName = DEFAULT_EMBOSSED_NAME;
        private String expirationDate = DEFAULT_CARD_EXPIRATION_DATE;
        private String activeStatus = DEFAULT_ACTIVE_INDICATOR;

        private CardBuilder() {
            // Intentionally empty: every field carries a measured default.
        }

        /**
         * Sets the sixteen-character card number, which is the business key.
         *
         * @param value the card number
         * @return this builder
         */
        public CardBuilder cardNumber(final String value) {
            this.cardNumber = value;
            return this;
        }

        /**
         * Sets the eleven-digit owning account identifier.
         *
         * @param value the account identifier
         * @return this builder
         */
        public CardBuilder accountId(final String value) {
            this.accountId = value;
            return this;
        }

        /**
         * Sets the three-digit verification code.
         *
         * @param value the code
         * @return this builder
         */
        public CardBuilder verificationCode(final String value) {
            this.verificationCode = value;
            return this;
        }

        /**
         * Sets the fifty-character embossed name exactly as supplied.
         *
         * <p>Values containing embedded blanks are accepted, and must be: the legacy alphabetic check
         * blanks every letter and then measures what is left, so a name with a space inside it passes.
         * A stricter check here would reject input the estate accepts.</p>
         *
         * @param value the embossed name
         * @return this builder
         */
        public CardBuilder embossedName(final String value) {
            this.embossedName = value;
            return this;
        }

        /**
         * Sets the embossed name folded to upper case the way the legacy translation table folds it.
         *
         * @param value the embossed name before folding
         * @return this builder
         * @see #asciiUpperFold(String)
         */
        public CardBuilder embossedNameFolded(final String value) {
            this.embossedName = asciiUpperFold(value);
            return this;
        }

        /**
         * Sets the ten-character expiry date, whose legacy field name is misspelled.
         *
         * @param value the date text
         * @return this builder
         */
        public CardBuilder expirationDate(final String value) {
            this.expirationDate = value;
            return this;
        }

        /**
         * Sets the one-character active indicator.
         *
         * @param value the indicator
         * @return this builder
         */
        public CardBuilder activeStatus(final String value) {
            this.activeStatus = value;
            return this;
        }

        /**
         * Builds the entity.
         *
         * @return a card carrying the values configured on this builder
         */
        public Card build() {
            return new Card(cardNumber, accountId, verificationCode, embossedName, expirationDate,
                    activeStatus);
        }

        /**
         * Builds the record image.
         *
         * @return the one-hundred-and-fifty byte card record image
         */
        public String image() {
            return complete(CARD, text(CARD, "CARD-NUM", cardNumber)
                    + numeric(CARD, "CARD-ACCT-ID", accountId)
                    + numeric(CARD, "CARD-CVV-CD", verificationCode)
                    + text(CARD, "CARD-EMBOSSED-NAME", embossedName)
                    + text(CARD, "CARD-EXPIRAION-DATE", expirationDate)
                    + text(CARD, "CARD-ACTIVE-STATUS", activeStatus));
        }
    }

    /**
     * Starts a cross-reference, defaulted to the first seeded cross-reference record.
     *
     * @return a new builder
     */
    public static CardCrossReferenceBuilder cardCrossReference() {
        return new CardCrossReferenceBuilder();
    }

    /**
     * Builds a cross-reference entity and <em>both</em> of its record images.
     *
     * <p>Two widths coexist and both are correct. {@link #fixtureImage()} is the thirty-six byte form
     * the delivered ASCII fixture carries, which ends on a digit and has no filler.
     * {@link #datasetImage()} is the fifty byte form the mainframe sequential data set carries, whose
     * last fourteen bytes are blanks. Padding the fixture form to fifty produces an image that will
     * not match the fixture, which is why the two are separate methods with separate names rather
     * than one method with a flag.</p>
     */
    public static final class CardCrossReferenceBuilder {

        private String cardNumber = DEFAULT_CARD_NUMBER;
        private String customerId = "000000050";
        private String accountId = DEFAULT_CARD_ACCOUNT_ID;

        private CardCrossReferenceBuilder() {
            // Intentionally empty: every field carries a measured default.
        }

        /**
         * Sets the sixteen-character card number, which is the business key.
         *
         * @param value the card number
         * @return this builder
         */
        public CardCrossReferenceBuilder cardNumber(final String value) {
            this.cardNumber = value;
            return this;
        }

        /**
         * Sets the nine-digit customer identifier.
         *
         * @param value the customer identifier
         * @return this builder
         */
        public CardCrossReferenceBuilder customerId(final String value) {
            this.customerId = value;
            return this;
        }

        /**
         * Sets the eleven-digit account identifier, which sits at the position the alternate index
         * addressed.
         *
         * @param value the account identifier
         * @return this builder
         */
        public CardCrossReferenceBuilder accountId(final String value) {
            this.accountId = value;
            return this;
        }

        /**
         * Builds the entity.
         *
         * @return a cross-reference carrying the values configured on this builder
         */
        public CardCrossReference build() {
            return new CardCrossReference(cardNumber, customerId, accountId);
        }

        /**
         * Builds the thirty-six byte fixture image, which carries no filler.
         *
         * @return the fixture record image
         */
        public String fixtureImage() {
            return complete(CARD_CROSS_REFERENCE_FIXTURE, data());
        }

        /**
         * Builds the fifty byte data-set image, whose last fourteen bytes are blanks.
         *
         * @return the data-set record image
         */
        public String datasetImage() {
            return complete(CARD_CROSS_REFERENCE_DATASET, data());
        }

        /**
         * Renders the three data fields shared by both widths.
         *
         * @return thirty-six bytes of data
         */
        private String data() {
            return text(CARD_CROSS_REFERENCE_FIXTURE, "XREF-CARD-NUM", cardNumber)
                    + numeric(CARD_CROSS_REFERENCE_FIXTURE, "XREF-CUST-ID", customerId)
                    + numeric(CARD_CROSS_REFERENCE_FIXTURE, "XREF-ACCT-ID", accountId);
        }
    }

    /**
     * Starts a customer, defaulted to the first seeded customer record.
     *
     * @return a new builder
     */
    public static CustomerBuilder customer() {
        return new CustomerBuilder();
    }

    /**
     * Builds a customer entity and the five-hundred byte customer record image.
     *
     * <p><strong>THE TWO REGULATED IDENTIFIERS CARRY TWO REPRESENTATIONS EACH,</strong> and the builder
     * keeps them apart on purpose. The record image holds cleartext characters, nine for the national
     * identifier and twenty for the government-issued one; the entity refuses cleartext in either
     * attribute and admits only absence or a well-formed protected value. One value cannot serve both,
     * so there are two setters for each: one that fills the image and one that fills the entity.</p>
     *
     * <p><strong>THE MIDDLE NAME AND THE SECOND ADDRESS LINE ARE NEVER VALIDATED.</strong> The estate
     * decorates both for error display but codes no edit for either, so this builder places whatever
     * it is given in both fields - blank, punctuated or odd - and no test may reject a value on their
     * account. Rejecting one would refuse input the estate accepts.</p>
     */
    public static final class CustomerBuilder {

        private String customerId = DEFAULT_CUSTOMER_ID;
        private String firstName = DEFAULT_FIRST_NAME;
        private String middleName = DEFAULT_MIDDLE_NAME;
        private String lastName = DEFAULT_LAST_NAME;
        private String addressLine1 = DEFAULT_ADDRESS_LINE_1;
        private String addressLine2 = DEFAULT_ADDRESS_LINE_2;
        private String addressLine3 = DEFAULT_ADDRESS_LINE_3;
        private String stateCode = DEFAULT_STATE_CODE;
        private String countryCode = DEFAULT_COUNTRY_CODE;
        private String addressZip = DEFAULT_CUSTOMER_ZIP;
        private String phoneNumber1 = DEFAULT_PHONE_NUMBER_1;
        private String phoneNumber2 = DEFAULT_PHONE_NUMBER_2;
        private String nationalIdentifierDigits = DEFAULT_NATIONAL_IDENTIFIER_DIGITS;
        private String nationalIdentifierProtected;
        private String governmentIdentifierDigits = DEFAULT_GOVERNMENT_IDENTIFIER_DIGITS;
        private String governmentIdentifierProtected = SYNTHETIC_PROTECTED_VALUE_SECOND;
        private String dateOfBirth = DEFAULT_DATE_OF_BIRTH;
        private String electronicTransferAccountId = DEFAULT_ELECTRONIC_TRANSFER_ACCOUNT_ID;
        private String primaryCardHolderIndicator = DEFAULT_ACTIVE_INDICATOR;
        private String creditScore = DEFAULT_CREDIT_SCORE;

        private CustomerBuilder() {
            // The national identifier defaults to absent, matching the reference seed, which leaves it
            // absent on all fifty rows. Nothing here may assume the seed carries one.
        }

        /**
         * Sets the nine-digit customer identifier, which is the business key.
         *
         * @param value the identifier
         * @return this builder
         */
        public CustomerBuilder customerId(final String value) {
            this.customerId = value;
            return this;
        }

        /**
         * Sets the twenty-five character first name.
         *
         * @param value the name, embedded blanks included if wanted
         * @return this builder
         */
        public CustomerBuilder firstName(final String value) {
            this.firstName = value;
            return this;
        }

        /**
         * Sets the twenty-five character middle name, which the estate never validates.
         *
         * @param value the name, or blanks
         * @return this builder
         */
        public CustomerBuilder middleName(final String value) {
            this.middleName = value;
            return this;
        }

        /**
         * Sets the twenty-five character last name.
         *
         * @param value the name, embedded blanks included if wanted
         * @return this builder
         */
        public CustomerBuilder lastName(final String value) {
            this.lastName = value;
            return this;
        }

        /**
         * Sets the fifty character first address line.
         *
         * @param value the address line
         * @return this builder
         */
        public CustomerBuilder addressLine1(final String value) {
            this.addressLine1 = value;
            return this;
        }

        /**
         * Sets the fifty character second address line, which the estate never validates.
         *
         * @param value the address line, or blanks
         * @return this builder
         */
        public CustomerBuilder addressLine2(final String value) {
            this.addressLine2 = value;
            return this;
        }

        /**
         * Sets the fifty character third address line.
         *
         * @param value the address line
         * @return this builder
         */
        public CustomerBuilder addressLine3(final String value) {
            this.addressLine3 = value;
            return this;
        }

        /**
         * Sets the two character state code.
         *
         * @param value the state code
         * @return this builder
         */
        public CustomerBuilder stateCode(final String value) {
            this.stateCode = value;
            return this;
        }

        /**
         * Sets the three character country code.
         *
         * @param value the country code
         * @return this builder
         */
        public CustomerBuilder countryCode(final String value) {
            this.countryCode = value;
            return this;
        }

        /**
         * Sets the ten character address postcode.
         *
         * @param value the postcode
         * @return this builder
         */
        public CustomerBuilder addressZip(final String value) {
            this.addressZip = value;
            return this;
        }

        /**
         * Sets the fifteen character first telephone number.
         *
         * @param value the number as the record carries it, punctuation included
         * @return this builder
         */
        public CustomerBuilder phoneNumber1(final String value) {
            this.phoneNumber1 = value;
            return this;
        }

        /**
         * Sets the fifteen character second telephone number.
         *
         * @param value the number as the record carries it, punctuation included
         * @return this builder
         */
        public CustomerBuilder phoneNumber2(final String value) {
            this.phoneNumber2 = value;
            return this;
        }

        /**
         * Sets the nine digits the record image carries for the national identifier.
         *
         * <p>This is the image representation only. It does not reach the entity, which refuses
         * cleartext in that attribute.</p>
         *
         * @param value the nine digits
         * @return this builder
         */
        public CustomerBuilder nationalIdentifierDigits(final String value) {
            this.nationalIdentifierDigits = value;
            return this;
        }

        /**
         * Sets the protected national identifier the entity carries, or clears it.
         *
         * @param value a well-formed protected value, or {@code null} for absent, which is what the
         *              reference seed loads on every row
         * @return this builder
         */
        public CustomerBuilder nationalIdentifierProtected(final String value) {
            this.nationalIdentifierProtected = value;
            return this;
        }

        /**
         * Sets the entity's national identifier to a synthetic protected value.
         *
         * <p>For a test that needs the attribute present rather than absent. The value protects
         * nothing, because there is nothing here to protect.</p>
         *
         * @return this builder
         */
        public CustomerBuilder withSyntheticProtectedNationalIdentifier() {
            return nationalIdentifierProtected(SYNTHETIC_PROTECTED_VALUE);
        }

        /**
         * Sets the twenty characters the record image carries for the government-issued identifier.
         *
         * @param value the twenty characters
         * @return this builder
         */
        public CustomerBuilder governmentIdentifierDigits(final String value) {
            this.governmentIdentifierDigits = value;
            return this;
        }

        /**
         * Sets the protected government-issued identifier the entity carries.
         *
         * @param value a well-formed protected value; the column is mandatory, so a persisted row
         *              always carries one
         * @return this builder
         */
        public CustomerBuilder governmentIdentifierProtected(final String value) {
            this.governmentIdentifierProtected = value;
            return this;
        }

        /**
         * Sets the ten character date of birth.
         *
         * <p>One column carries both legacy spellings of this field name, which denote the same bytes
         * at the same offset.</p>
         *
         * @param value the date text
         * @return this builder
         */
        public CustomerBuilder dateOfBirth(final String value) {
            this.dateOfBirth = value;
            return this;
        }

        /**
         * Sets the ten character electronic transfer account identifier.
         *
         * @param value the identifier
         * @return this builder
         */
        public CustomerBuilder electronicTransferAccountId(final String value) {
            this.electronicTransferAccountId = value;
            return this;
        }

        /**
         * Sets the one character primary card holder indicator.
         *
         * @param value the indicator
         * @return this builder
         */
        public CustomerBuilder primaryCardHolderIndicator(final String value) {
            this.primaryCardHolderIndicator = value;
            return this;
        }

        /**
         * Sets the three digit credit score.
         *
         * <p>Values outside the enforced range are accepted here on purpose, so that a negative test
         * can build one. The default is inside the range.</p>
         *
         * @param value the three digits
         * @return this builder
         */
        public CustomerBuilder creditScore(final String value) {
            this.creditScore = value;
            return this;
        }

        /**
         * Builds the entity, which receives the protected identifiers rather than the image digits.
         *
         * @return a customer carrying the values configured on this builder
         */
        public Customer build() {
            return new Customer(customerId, firstName, middleName, lastName, addressLine1,
                    addressLine2, addressLine3, stateCode, countryCode, addressZip, phoneNumber1,
                    phoneNumber2, nationalIdentifierProtected, governmentIdentifierProtected,
                    dateOfBirth, electronicTransferAccountId, primaryCardHolderIndicator, creditScore);
        }

        /**
         * Builds the record image, which receives the image digits rather than the protected values.
         *
         * @return the five-hundred byte customer record image
         */
        public String image() {
            return complete(CUSTOMER, numeric(CUSTOMER, "CUST-ID", customerId)
                    + text(CUSTOMER, "CUST-FIRST-NAME", firstName)
                    + text(CUSTOMER, "CUST-MIDDLE-NAME", middleName)
                    + text(CUSTOMER, "CUST-LAST-NAME", lastName)
                    + text(CUSTOMER, "CUST-ADDR-LINE-1", addressLine1)
                    + text(CUSTOMER, "CUST-ADDR-LINE-2", addressLine2)
                    + text(CUSTOMER, "CUST-ADDR-LINE-3", addressLine3)
                    + text(CUSTOMER, "CUST-ADDR-STATE-CD", stateCode)
                    + text(CUSTOMER, "CUST-ADDR-COUNTRY-CD", countryCode)
                    + text(CUSTOMER, "CUST-ADDR-ZIP", addressZip)
                    + text(CUSTOMER, "CUST-PHONE-NUM-1", phoneNumber1)
                    + text(CUSTOMER, "CUST-PHONE-NUM-2", phoneNumber2)
                    + numeric(CUSTOMER, "CUST-SSN", nationalIdentifierDigits)
                    + text(CUSTOMER, "CUST-GOVT-ISSUED-ID", governmentIdentifierDigits)
                    + text(CUSTOMER, "CUST-DOB-YYYY-MM-DD", dateOfBirth)
                    + text(CUSTOMER, "CUST-EFT-ACCOUNT-ID", electronicTransferAccountId)
                    + text(CUSTOMER, "CUST-PRI-CARD-HOLDER-IND", primaryCardHolderIndicator)
                    + numeric(CUSTOMER, "CUST-FICO-CREDIT-SCORE", creditScore));
        }
    }

    /**
     * The thirteen fields both transaction layouts carry, held once so that two separate builders can
     * render them without either becoming the other.
     *
     * <p>The two layouts are byte-for-byte identical in structure and differ only in their field-name
     * prefix, so the rendering is parameterised by layout and prefix. The two entities stay separate,
     * because the landing data set and the posted table have different lifecycles and different
     * referential constraints.</p>
     */
    private static final class TransactionFields {

        private String id = DEFAULT_TRANSACTION_ID;
        private String typeCode = DEFAULT_TRANSACTION_TYPE_CODE;
        private String categoryCode = DEFAULT_TRANSACTION_CATEGORY_CODE;
        private String source = TransactionSourceType.POS_TERM.getValue();
        private String description = DEFAULT_TRANSACTION_DESCRIPTION;
        private BigDecimal transactionAmount = DEFAULT_TRANSACTION_AMOUNT;
        private String merchantId = DEFAULT_MERCHANT_ID;
        private String merchantName = DEFAULT_MERCHANT_NAME;
        private String merchantCity = DEFAULT_MERCHANT_CITY;
        private String merchantZip = DEFAULT_MERCHANT_ZIP;
        private String cardNumber = DEFAULT_TRANSACTION_CARD_NUMBER;
        private String originalTimestamp = SEEDED_ORIGINAL_TIMESTAMP;
        private String processingTimestamp = BLANK_PROCESSING_TIMESTAMP;

        private TransactionFields() {
            // Intentionally empty: every field carries a measured default.
        }

        /**
         * Renders the thirteen fields into a complete record image of the given layout.
         *
         * @param layout the layout to render, which supplies every field width
         * @param prefix the layout's field-name prefix
         * @return the three-hundred-and-fifty byte record image
         */
        private String render(final RecordLayout layout, final String prefix) {
            return complete(layout, text(layout, prefix + "-ID", id)
                    + text(layout, prefix + "-TYPE-CD", typeCode)
                    + numeric(layout, prefix + "-CAT-CD", categoryCode)
                    + text(layout, prefix + "-SOURCE", source)
                    + text(layout, prefix + "-DESC", description)
                    + amount(layout, prefix + "-AMT", transactionAmount)
                    + numeric(layout, prefix + "-MERCHANT-ID", merchantId)
                    + text(layout, prefix + "-MERCHANT-NAME", merchantName)
                    + text(layout, prefix + "-MERCHANT-CITY", merchantCity)
                    + text(layout, prefix + "-MERCHANT-ZIP", merchantZip)
                    + text(layout, prefix + "-CARD-NUM", cardNumber)
                    + text(layout, prefix + "-ORIG-TS", originalTimestamp)
                    + text(layout, prefix + "-PROC-TS", processingTimestamp));
        }
    }

    /**
     * Starts a posted transaction, defaulted to the first seeded daily-transaction record.
     *
     * <p>The posted table is seeded empty, so every posted transaction a test needs is built here.
     * Its identifier is always supplied explicitly: the schema creates no sequence and no generated
     * column, because the estate derived a new identifier from the highest existing one rather than
     * from a counter, and a counter diverges permanently after the first rolled-back attempt.</p>
     *
     * @return a new builder
     */
    public static TransactionBuilder transaction() {
        return new TransactionBuilder();
    }

    /** Builds a posted-transaction entity and the three-hundred-and-fifty byte record image. */
    public static final class TransactionBuilder {

        /** The layout's field-name prefix. */
        private static final String PREFIX = "TRAN";

        private final TransactionFields fields = new TransactionFields();

        private TransactionBuilder() {
            // Intentionally empty: every field carries a measured default.
        }

        /**
         * Sets the sixteen-character transaction identifier, which is the business key.
         *
         * @param value the identifier
         * @return this builder
         */
        public TransactionBuilder id(final String value) {
            fields.id = value;
            return this;
        }

        /**
         * Sets the two-character type code.
         *
         * @param value the type code
         * @return this builder
         */
        public TransactionBuilder typeCode(final String value) {
            fields.typeCode = value;
            return this;
        }

        /**
         * Sets the four-digit category code.
         *
         * @param value the category code
         * @return this builder
         */
        public TransactionBuilder categoryCode(final String value) {
            fields.categoryCode = value;
            return this;
        }

        /**
         * Sets the ten-character source, exactly as the record carries it, trailing blanks included.
         *
         * @param value the source text
         * @return this builder
         */
        public TransactionBuilder source(final String value) {
            fields.source = value;
            return this;
        }

        /**
         * Sets the source from the enumerated contract, which already carries its trailing blanks.
         *
         * @param value the source type
         * @return this builder
         */
        public TransactionBuilder source(final TransactionSourceType value) {
            fields.source = value.getValue();
            return this;
        }

        /**
         * Sets the hundred-character description.
         *
         * @param value the description
         * @return this builder
         */
        public TransactionBuilder description(final String value) {
            fields.description = value;
            return this;
        }

        /**
         * Sets the amount, which the image renders with its sign overpunched into the final byte.
         *
         * @param value the amount
         * @return this builder
         */
        public TransactionBuilder amount(final BigDecimal value) {
            fields.transactionAmount = value;
            return this;
        }

        /**
         * Sets the nine-digit merchant identifier.
         *
         * @param value the identifier
         * @return this builder
         */
        public TransactionBuilder merchantId(final String value) {
            fields.merchantId = value;
            return this;
        }

        /**
         * Sets the fifty-character merchant name.
         *
         * @param value the name
         * @return this builder
         */
        public TransactionBuilder merchantName(final String value) {
            fields.merchantName = value;
            return this;
        }

        /**
         * Sets the fifty-character merchant city.
         *
         * @param value the city
         * @return this builder
         */
        public TransactionBuilder merchantCity(final String value) {
            fields.merchantCity = value;
            return this;
        }

        /**
         * Sets the ten-character merchant postcode.
         *
         * @param value the postcode
         * @return this builder
         */
        public TransactionBuilder merchantZip(final String value) {
            fields.merchantZip = value;
            return this;
        }

        /**
         * Sets the sixteen-character card number, which sits at the position two sort specifications
         * address and which the posted table constrains to an existing card.
         *
         * @param value the card number
         * @return this builder
         */
        public TransactionBuilder cardNumber(final String value) {
            fields.cardNumber = value;
            return this;
        }

        /**
         * Sets the twenty-six character original timestamp.
         *
         * @param value the timestamp text
         * @return this builder
         */
        public TransactionBuilder originalTimestamp(final String value) {
            fields.originalTimestamp = value;
            return this;
        }

        /**
         * Sets the twenty-six character processing timestamp.
         *
         * @param value the timestamp text
         * @return this builder
         */
        public TransactionBuilder processingTimestamp(final String value) {
            fields.processingTimestamp = value;
            return this;
        }

        /**
         * Sets the processing timestamp from a date, so that the report's inclusive window can select
         * or reject this record.
         *
         * @param value the processing date
         * @return this builder
         */
        public TransactionBuilder processingDate(final LocalDate value) {
            fields.processingTimestamp = processingTimestampFor(value);
            return this;
        }

        /**
         * Builds the entity.
         *
         * @return a posted transaction carrying the values configured on this builder
         */
        public Transaction build() {
            return new Transaction(fields.id, fields.typeCode, fields.categoryCode, fields.source,
                    fields.description, storedAmount(fields.transactionAmount), fields.merchantId,
                    fields.merchantName, fields.merchantCity, fields.merchantZip, fields.cardNumber,
                    fields.originalTimestamp, fields.processingTimestamp);
        }

        /**
         * Builds the record image.
         *
         * @return the three-hundred-and-fifty byte posted-transaction record image
         */
        public String image() {
            return fields.render(TRANSACTION, PREFIX);
        }
    }

    /**
     * Starts a daily transaction, defaulted to the first seeded daily-transaction record.
     *
     * @return a new builder
     */
    public static DailyTransactionBuilder dailyTransaction() {
        return new DailyTransactionBuilder();
    }

    /**
     * Gives an in-memory daily-transaction record the provenance a record read from a resource has.
     *
     * <p>Production records always carry one: the posting job reads them from a fixed-width resource and
     * the mapper attaches the exact bytes it sliced, which is what the reject dataset's leading 350-byte
     * segment is. A record assembled field by field in a test has no such image, and the reject item type
     * refuses it rather than regenerating one - deliberately, because a regenerated prefix normalises the
     * uninitialised filler run and the amount's overpunched sign byte.
     *
     * <p>This helper supplies an image for a record whose <em>bytes</em> are not the subject of the test:
     * it renders the record once through the layout's own mapper and attaches the result, so the image
     * travels from that point on exactly as a resource's would. A test whose subject <em>is</em> the bytes
     * builds its record from an explicit image instead, with
     * {@code DailyTransactionRecordMapper.fromRecord}.
     *
     * @param  record the record to give provenance to; must not be {@code null}
     * @return the same record, now carrying its own rendered image
     */
    public static DailyTransaction withRecordProvenance(final DailyTransaction record) {
        Objects.requireNonNull(record, "record must not be null");
        record.setSourceRecordImage(DailyTransactionRecordMapper.toRecord(record));
        return record;
    }

    /**
     * Builds a daily-transaction entity and the three-hundred-and-fifty byte landing record image.
     *
     * <p><strong>THIS BUILDER MUST BE ABLE TO PRODUCE AN INVALID ROW, AND CAN.</strong> The landing
     * table carries no referential constraints at all - deliberately, so that a row naming a card, an
     * account or a customer that does not exist can be written and then rejected by validation. That
     * is the only route to the reject reasons that fire when a card or an account cannot be found, and
     * therefore the only route to a four-hundred-and-thirty byte reject record. No validity
     * precondition is imposed here, and none may be added.</p>
     */
    public static final class DailyTransactionBuilder {

        /** The layout's field-name prefix. */
        private static final String PREFIX = "DALYTRAN";

        private final TransactionFields fields = new TransactionFields();

        private DailyTransactionBuilder() {
            // Intentionally empty: every field carries a measured default.
        }

        /**
         * Sets the sixteen-character identifier, which is the business key.
         *
         * @param value the identifier
         * @return this builder
         */
        public DailyTransactionBuilder id(final String value) {
            fields.id = value;
            return this;
        }

        /**
         * Sets the two-character type code.
         *
         * @param value the type code
         * @return this builder
         */
        public DailyTransactionBuilder typeCode(final String value) {
            fields.typeCode = value;
            return this;
        }

        /**
         * Sets the four-digit category code.
         *
         * @param value the category code
         * @return this builder
         */
        public DailyTransactionBuilder categoryCode(final String value) {
            fields.categoryCode = value;
            return this;
        }

        /**
         * Sets the ten-character source, exactly as the record carries it, trailing blanks included.
         *
         * @param value the source text
         * @return this builder
         */
        public DailyTransactionBuilder source(final String value) {
            fields.source = value;
            return this;
        }

        /**
         * Sets the source from the enumerated contract, which already carries its trailing blanks.
         *
         * @param value the source type
         * @return this builder
         */
        public DailyTransactionBuilder source(final TransactionSourceType value) {
            fields.source = value.getValue();
            return this;
        }

        /**
         * Sets the hundred-character description.
         *
         * @param value the description
         * @return this builder
         */
        public DailyTransactionBuilder description(final String value) {
            fields.description = value;
            return this;
        }

        /**
         * Sets the amount, which the image renders with its sign overpunched into the final byte.
         *
         * @param value the amount
         * @return this builder
         */
        public DailyTransactionBuilder amount(final BigDecimal value) {
            fields.transactionAmount = value;
            return this;
        }

        /**
         * Sets the nine-digit merchant identifier.
         *
         * @param value the identifier
         * @return this builder
         */
        public DailyTransactionBuilder merchantId(final String value) {
            fields.merchantId = value;
            return this;
        }

        /**
         * Sets the fifty-character merchant name.
         *
         * @param value the name
         * @return this builder
         */
        public DailyTransactionBuilder merchantName(final String value) {
            fields.merchantName = value;
            return this;
        }

        /**
         * Sets the fifty-character merchant city.
         *
         * @param value the city
         * @return this builder
         */
        public DailyTransactionBuilder merchantCity(final String value) {
            fields.merchantCity = value;
            return this;
        }

        /**
         * Sets the ten-character merchant postcode.
         *
         * @param value the postcode
         * @return this builder
         */
        public DailyTransactionBuilder merchantZip(final String value) {
            fields.merchantZip = value;
            return this;
        }

        /**
         * Sets the sixteen-character card number, which may name a card that does not exist.
         *
         * @param value the card number
         * @return this builder
         */
        public DailyTransactionBuilder cardNumber(final String value) {
            fields.cardNumber = value;
            return this;
        }

        /**
         * Sets the twenty-six character original timestamp.
         *
         * @param value the timestamp text
         * @return this builder
         */
        public DailyTransactionBuilder originalTimestamp(final String value) {
            fields.originalTimestamp = value;
            return this;
        }

        /**
         * Sets the twenty-six character processing timestamp.
         *
         * @param value the timestamp text
         * @return this builder
         */
        public DailyTransactionBuilder processingTimestamp(final String value) {
            fields.processingTimestamp = value;
            return this;
        }

        /**
         * Sets the processing timestamp from a date, so that a date window can select or reject this
         * record.
         *
         * <p>The delivered fixture leaves this field blank on every one of its three hundred records,
         * so this method is the only way a date-window filter can be exercised at all.</p>
         *
         * @param value the processing date
         * @return this builder
         */
        public DailyTransactionBuilder processingDate(final LocalDate value) {
            fields.processingTimestamp = processingTimestampFor(value);
            return this;
        }

        /**
         * Builds the entity.
         *
         * @return a daily transaction carrying the values configured on this builder
         */
        public DailyTransaction build() {
            return new DailyTransaction(fields.id, fields.typeCode, fields.categoryCode,
                    fields.source, fields.description, storedAmount(fields.transactionAmount),
                    fields.merchantId, fields.merchantName, fields.merchantCity, fields.merchantZip,
                    fields.cardNumber, fields.originalTimestamp, fields.processingTimestamp);
        }

        /**
         * Builds the record image.
         *
         * @return the three-hundred-and-fifty byte landing record image
         */
        public String image() {
            return fields.render(DAILY_TRANSACTION, PREFIX);
        }
    }

    /**
     * Starts a category balance, defaulted to the first seeded category-balance record.
     *
     * @return a new builder
     */
    public static TransactionCategoryBalanceBuilder transactionCategoryBalance() {
        return new TransactionCategoryBalanceBuilder();
    }

    /**
     * Builds a category-balance entity, its composite identifier and the fifty byte record image.
     *
     * <p>Every one of the fifty seeded rows carries a zero balance - measured, all fifty - so a test
     * that needs a non-zero balance builds one here rather than reading the seed.</p>
     */
    public static final class TransactionCategoryBalanceBuilder {

        private String accountId = DEFAULT_ACCOUNT_ID;
        private String typeCode = DEFAULT_TRANSACTION_TYPE_CODE;
        private String categoryCode = DEFAULT_TRANSACTION_CATEGORY_CODE;
        private BigDecimal balance = DEFAULT_ZERO_AMOUNT;

        private TransactionCategoryBalanceBuilder() {
            // Intentionally empty: every field carries a measured default.
        }

        /**
         * Sets the eleven-digit account identifier, the first part of the composite key.
         *
         * @param value the account identifier
         * @return this builder
         */
        public TransactionCategoryBalanceBuilder accountId(final String value) {
            this.accountId = value;
            return this;
        }

        /**
         * Sets the two-character type code, the second part of the composite key.
         *
         * @param value the type code
         * @return this builder
         */
        public TransactionCategoryBalanceBuilder typeCode(final String value) {
            this.typeCode = value;
            return this;
        }

        /**
         * Sets the four-digit category code, the third part of the composite key.
         *
         * @param value the category code
         * @return this builder
         */
        public TransactionCategoryBalanceBuilder categoryCode(final String value) {
            this.categoryCode = value;
            return this;
        }

        /**
         * Sets the balance.
         *
         * @param value the amount
         * @return this builder
         */
        public TransactionCategoryBalanceBuilder balance(final BigDecimal value) {
            this.balance = value;
            return this;
        }

        /**
         * Builds the entity.
         *
         * @return a category balance carrying the values configured on this builder
         */
        public TransactionCategoryBalance build() {
            return new TransactionCategoryBalance(accountId, typeCode, categoryCode,
                    storedAmount(balance));
        }

        /**
         * Builds the seventeen-byte composite identifier, ordered account, type, category.
         *
         * @return the identifier
         */
        public TransactionCategoryBalanceId id() {
            return new TransactionCategoryBalanceId(accountId, typeCode, categoryCode);
        }

        /**
         * Builds the record image, padded with the ASCII digit zero.
         *
         * @return the fifty byte category-balance record image
         */
        public String image() {
            return complete(TRANSACTION_CATEGORY_BALANCE,
                    numeric(TRANSACTION_CATEGORY_BALANCE, "TRANCAT-ACCT-ID", accountId)
                            + text(TRANSACTION_CATEGORY_BALANCE, "TRANCAT-TYPE-CD", typeCode)
                            + numeric(TRANSACTION_CATEGORY_BALANCE, "TRANCAT-CD", categoryCode)
                            + amount(TRANSACTION_CATEGORY_BALANCE, "TRAN-CAT-BAL", balance));
        }
    }

    /**
     * Starts a disclosure group, defaulted to the first seeded disclosure-group record.
     *
     * @return a new builder
     */
    public static DisclosureGroupBuilder disclosureGroup() {
        return new DisclosureGroupBuilder();
    }

    /**
     * Builds a disclosure-group entity, its composite identifier and the fifty byte record image.
     *
     * <p>The group identifier is blank-padded to ten characters and is never trimmed, because the
     * padding is part of the key: the fallback group's key is the word followed by three blanks, and
     * the trimmed word matches nothing.</p>
     */
    public static final class DisclosureGroupBuilder {

        private String groupId = DIRECT_HIT_DISCLOSURE_GROUP_ID;
        private String typeCode = DEFAULT_TRANSACTION_TYPE_CODE;
        private String categoryCode = DEFAULT_TRANSACTION_CATEGORY_CODE;
        private BigDecimal interestRate = DEFAULT_INTEREST_RATE;

        private DisclosureGroupBuilder() {
            // Intentionally empty: every field carries a measured default.
        }

        /**
         * Sets the ten-character group identifier, the first part of the composite key.
         *
         * @param value the group identifier, blank-padded to ten characters and never trimmed
         * @return this builder
         */
        public DisclosureGroupBuilder groupId(final String value) {
            this.groupId = value;
            return this;
        }

        /**
         * Sets the two-character type code, the second part of the composite key.
         *
         * @param value the type code
         * @return this builder
         */
        public DisclosureGroupBuilder typeCode(final String value) {
            this.typeCode = value;
            return this;
        }

        /**
         * Sets the four-digit category code, the third part of the composite key.
         *
         * @param value the category code
         * @return this builder
         */
        public DisclosureGroupBuilder categoryCode(final String value) {
            this.categoryCode = value;
            return this;
        }

        /**
         * Sets the interest rate, the estate's only four-integer-digit amount.
         *
         * @param value the rate
         * @return this builder
         */
        public DisclosureGroupBuilder interestRate(final BigDecimal value) {
            this.interestRate = value;
            return this;
        }

        /**
         * Builds the entity.
         *
         * @return a disclosure group carrying the values configured on this builder
         */
        public DisclosureGroup build() {
            return new DisclosureGroup(groupId, typeCode, categoryCode, storedAmount(interestRate));
        }

        /**
         * Builds the sixteen-byte composite identifier, ordered group, type, category.
         *
         * @return the identifier
         */
        public DisclosureGroupId id() {
            return new DisclosureGroupId(groupId, typeCode, categoryCode);
        }

        /**
         * Builds the record image, padded with the ASCII digit zero.
         *
         * @return the fifty byte disclosure-group record image
         */
        public String image() {
            return complete(DISCLOSURE_GROUP,
                    text(DISCLOSURE_GROUP, "DIS-ACCT-GROUP-ID", groupId)
                            + text(DISCLOSURE_GROUP, "DIS-TRAN-TYPE-CD", typeCode)
                            + numeric(DISCLOSURE_GROUP, "DIS-TRAN-CAT-CD", categoryCode)
                            + amount(DISCLOSURE_GROUP, "DIS-INT-RATE", interestRate));
        }
    }

    /**
     * Starts a transaction type, defaulted to the first seeded transaction-type record.
     *
     * @return a new builder
     */
    public static TransactionTypeBuilder transactionType() {
        return new TransactionTypeBuilder();
    }

    /** Builds a transaction-type entity and the sixty byte record image. */
    public static final class TransactionTypeBuilder {

        private String typeCode = DEFAULT_TRANSACTION_TYPE_CODE;
        private String description = DEFAULT_TRANSACTION_TYPE_DESCRIPTION;

        private TransactionTypeBuilder() {
            // Intentionally empty: every field carries a measured default.
        }

        /**
         * Sets the two-character type code, which is the business key.
         *
         * @param value the type code
         * @return this builder
         */
        public TransactionTypeBuilder typeCode(final String value) {
            this.typeCode = value;
            return this;
        }

        /**
         * Sets the fifty-character description.
         *
         * @param value the description
         * @return this builder
         */
        public TransactionTypeBuilder description(final String value) {
            this.description = value;
            return this;
        }

        /**
         * Builds the entity.
         *
         * @return a transaction type carrying the values configured on this builder
         */
        public TransactionType build() {
            return new TransactionType(typeCode, description);
        }

        /**
         * Builds the record image, padded with the ASCII digit zero.
         *
         * @return the sixty byte transaction-type record image
         */
        public String image() {
            return complete(TRANSACTION_TYPE, text(TRANSACTION_TYPE, "TRAN-TYPE", typeCode)
                    + text(TRANSACTION_TYPE, "TRAN-TYPE-DESC", description));
        }
    }

    /**
     * Starts a transaction category, defaulted to the first seeded transaction-category record.
     *
     * @return a new builder
     */
    public static TransactionCategoryBuilder transactionCategory() {
        return new TransactionCategoryBuilder();
    }

    /**
     * Builds a transaction-category entity, its composite identifier and the sixty byte record image.
     *
     * <p>Its composite key is six bytes of type and category, and is a different key from the
     * seventeen-byte category-balance key that shares its name in the source.</p>
     */
    public static final class TransactionCategoryBuilder {

        private String typeCode = DEFAULT_TRANSACTION_TYPE_CODE;
        private String categoryCode = DEFAULT_TRANSACTION_CATEGORY_CODE;
        private String description = DEFAULT_TRANSACTION_CATEGORY_DESCRIPTION;

        private TransactionCategoryBuilder() {
            // Intentionally empty: every field carries a measured default.
        }

        /**
         * Sets the two-character type code, the first part of the composite key.
         *
         * @param value the type code
         * @return this builder
         */
        public TransactionCategoryBuilder typeCode(final String value) {
            this.typeCode = value;
            return this;
        }

        /**
         * Sets the four-digit category code, the second part of the composite key.
         *
         * @param value the category code
         * @return this builder
         */
        public TransactionCategoryBuilder categoryCode(final String value) {
            this.categoryCode = value;
            return this;
        }

        /**
         * Sets the fifty-character description.
         *
         * @param value the description
         * @return this builder
         */
        public TransactionCategoryBuilder description(final String value) {
            this.description = value;
            return this;
        }

        /**
         * Builds the entity.
         *
         * @return a transaction category carrying the values configured on this builder
         */
        public TransactionCategory build() {
            return new TransactionCategory(typeCode, categoryCode, description);
        }

        /**
         * Builds the six-byte composite identifier, ordered type, category.
         *
         * @return the identifier
         */
        public TransactionCategoryId id() {
            return new TransactionCategoryId(typeCode, categoryCode);
        }

        /**
         * Builds the record image, padded with the ASCII digit zero.
         *
         * @return the sixty byte transaction-category record image
         */
        public String image() {
            return complete(TRANSACTION_CATEGORY,
                    text(TRANSACTION_CATEGORY, "TRAN-TYPE-CD", typeCode)
                            + numeric(TRANSACTION_CATEGORY, "TRAN-CAT-CD", categoryCode)
                            + text(TRANSACTION_CATEGORY, "TRAN-CAT-TYPE-DESC", description));
        }
    }

    // =============================================================================================
    // SECTION 12 - READING A DELIVERED FIXTURE FROM THE TEST CLASS PATH
    //
    // Fixtures are read from the module's own test resources and from nowhere else. Reaching outside
    // the module into the legacy tree would couple this module to it and break the standalone-module
    // requirement, so no path here leaves the class path.
    // =============================================================================================

    /** Class-path directory holding the delivered input fixtures. */
    public static final String FIXTURE_DIRECTORY = "/fixtures/input/";

    /**
     * The sign-on fixture, carrying the ten identities at the eighty byte user-security layout.
     *
     * <p>Unlike the other eight fixtures it carries no line separators at all: it is eight hundred
     * bytes of ten concatenated records, mirroring the mainframe sequential data set, which is also
     * eight hundred bytes. A reader that splits on a line separator therefore cannot read it, which is
     * why {@link #fixedWidthRecords(String, int)} slices by width instead.</p>
     */
    public static final String USER_SECURITY_FIXTURE = "usrsec.txt";

    /** Repository-relative path of the read-only member that provisions the ten sign-on identities. */
    public static final String PROVISIONING_MEMBER = "app/jcl/DUSRSECJ.jcl";

    /** Directory name of this module, used to recognise the checkout root while walking upward. */
    private static final String MODULE_DIRECTORY = "carddemo-java";

    /** Directory name of the read-only legacy estate, recognised the same way. */
    private static final String LEGACY_ROOT = "app";

    /** The statement that opens the provisioning member's in-stream card images. */
    private static final String PROVISIONING_STREAM_OPEN = "//SYSUT1";

    /** The delimiter that closes them. */
    private static final String PROVISIONING_STREAM_CLOSE = "/*";

    /**
     * Reads a delivered fixture as fixed-width records.
     *
     * <p>Line separators are removed before slicing, so this reads both the newline-delimited fixtures
     * and the separator-free sign-on fixture with one implementation. Each byte becomes exactly one
     * character, because a fixed-width record is measured in bytes and a multi-byte decoding would
     * shift every offset after the first non-ASCII byte.</p>
     *
     * @param fileName    the fixture file name inside {@link #FIXTURE_DIRECTORY}
     * @param recordWidth the record width in bytes, taken from the verified layout
     * @return the records, in file order
     * @throws IllegalStateException if the fixture is absent, unreadable, empty, or does not divide
     *                               evenly into records of the declared width
     */
    public static List<String> fixedWidthRecords(final String fileName, final int recordWidth) {
        Objects.requireNonNull(fileName, "fileName");
        if (recordWidth < 1) {
            throw new IllegalArgumentException("a record width must be at least one byte, but "
                    + recordWidth + " was declared for fixture " + fileName);
        }
        final String resource = FIXTURE_DIRECTORY + fileName;
        final String content = readClasspathResource(resource, recordWidth);
        final StringBuilder packed = new StringBuilder(content.length());
        for (int index = 0; index < content.length(); index++) {
            final char character = content.charAt(index);
            if (character != '\n' && character != '\r') {
                packed.append(character);
            }
        }
        if (packed.length() == 0) {
            throw new IllegalStateException("fixture " + resource
                    + " carries no data; it must hold whole records of " + recordWidth + " bytes");
        }
        if (packed.length() % recordWidth != 0) {
            throw new IllegalStateException("fixture " + resource + " carries " + packed.length()
                    + " bytes once line separators are removed, which is not a whole number of "
                    + recordWidth + "-byte records; either the fixture or the declared layout width is"
                    + " wrong");
        }
        final int count = packed.length() / recordWidth;
        final List<String> records = new ArrayList<>(count);
        for (int ordinal = 0; ordinal < count; ordinal++) {
            records.add(packed.substring(ordinal * recordWidth, (ordinal + 1) * recordWidth));
        }
        return List.copyOf(records);
    }

    /**
     * Reads a delivered fixture as fixed-width records and additionally requires the record count.
     *
     * <p><strong>PREFER THIS OVERLOAD, AND HERE IS THE MEASURED REASON.</strong> Even divisibility
     * alone is a weak check, because a wrong width can still divide evenly. The cross-reference fixture
     * is the live case: eighteen hundred bytes read at the thirty-six byte fixture width yields the
     * fifty records it holds, and read at the fifty byte data-set width it divides just as evenly and
     * yields thirty-six records of shifted, meaningless fields - silently. Naming the expected count
     * turns that silent misreading into a failure that says which width was used and how many records it
     * produced.</p>
     *
     * @param fileName            the fixture file name inside {@link #FIXTURE_DIRECTORY}
     * @param recordWidth         the record width in bytes, taken from the verified layout
     * @param expectedRecordCount the number of records the fixture is known to hold
     * @return the records, in file order
     * @throws IllegalStateException if the fixture is absent, unreadable, empty, does not divide evenly
     *                               into records of the declared width, or holds a different number of
     *                               them
     */
    public static List<String> fixedWidthRecords(final String fileName, final int recordWidth,
            final int expectedRecordCount) {
        final List<String> records = fixedWidthRecords(fileName, recordWidth);
        if (records.size() != expectedRecordCount) {
            throw new IllegalStateException("fixture " + FIXTURE_DIRECTORY + fileName
                    + " read at a record width of " + recordWidth + " bytes yields " + records.size()
                    + " records, but it is known to hold " + expectedRecordCount
                    + "; the declared width is wrong, and it divides evenly enough to have gone"
                    + " unnoticed");
        }
        return records;
    }

    /**
     * Reads a class-path resource in full, one character per byte.
     *
     * @param resource    the absolute class-path resource path
     * @param recordWidth the record width, named in the diagnostic so a failure is actionable
     * @return the resource content
     * @throws IllegalStateException if the resource is absent or unreadable
     */
    private static String readClasspathResource(final String resource, final int recordWidth) {
        try (InputStream stream = TestDataFactory.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("fixture " + resource + " is not on the test class"
                        + " path, so records of " + recordWidth + " bytes cannot be read from it;"
                        + " it is a delivered test resource and must be present");
            }
            return new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
        } catch (final IOException readFailure) {
            throw new IllegalStateException("fixture " + resource + " could not be read",
                    readFailure);
        }
    }

    // =============================================================================================
    // SECTION 13 - THE CREDENTIAL WINDOW: LOCATION KNOWN, VALUE NEVER WRITTEN DOWN
    //
    // The ten sign-on identities share one eight-character cleartext credential in the legacy record.
    // Its value appears nowhere in this file - not in a literal, not in a comment, not in a method
    // name and not in a diagnostic - because the whole point of hashing it in the target is that the
    // cleartext stops existing in the module.
    //
    // What appears instead is its LOCATION, which is a layout fact rather than a secret: the credential
    // window is the eight bytes at zero-based offset 48 of the eighty byte record, being one-based
    // columns 49 through 56. Its contents are recovered by that offset, from the class-path fixture, at
    // run time, and handed back as a mutable character array so a caller can overwrite it.
    //
    // ---------------------------------------------------------------------------------------------
    // THE CLASS-PATH FIXTURE'S WINDOW CARRIES THE DELIVERED PROVISIONING VALUE, AND HAS TO.
    // ---------------------------------------------------------------------------------------------
    // It once carried an eight-character SYNTHETIC stand-in instead, on the reasoning that a module
    // which hashes a credential should not hold the credential. That reasoning is sound about the
    // module's own configuration and wrong about this fixture, for two independent reasons.
    //
    // First, the plan designates these fixtures as derived from the delivered reference data, and names
    // the credential seed as reproducing the ten in-stream provisioning records. A fabricated window
    // made the fixture silently disagree with the provenance it documents - a fidelity defect in a Gate
    // 4 named artefact, not a hardening measure.
    //
    // Second, and decisively: with a fabricated window the ten digests the credential seed loads became
    // UNVERIFIABLE from inside this module. The only questions answerable about them were their SHAPE
    // and their REFUSAL of a wrong value - and both of those are satisfied by a digest of ANY value
    // whatsoever. A future wrong literal in the seed would have passed every committed test while the
    // seeded sign-on silently stopped working, which is exactly the gap a reviewer found. Acceptance is
    // the only property that distinguishes a correct digest from a well-formed one, and acceptance
    // cannot be asserted without the value.
    //
    // The window is therefore the delivered value, recovered from the provisioning records' own layout
    // position. Note what did NOT change: the value still appears nowhere in this file and nowhere else
    // in this module's Java sources - not in a literal, not in a comment, not in a method name, not in a
    // diagnostic. It lives in one test resource, is read by offset, is used, and is overwritten. The
    // module remains standalone, because nothing reads the legacy tree at run time; the derivation
    // happened once, when the fixture was written.
    //
    // Nor is this a hardcoded credential in the sense the constraint forbids. That constraint governs
    // the production configuration, which resolves every secret from the environment with no fallback,
    // and the credential seed itself is scoped to the local and test profiles only.
    //
    // ---------------------------------------------------------------------------------------------
    // MEASURED, AND LOAD-BEARING: THE WINDOW IS INVARIANT UNDER THE SIGN-ON FOLD.
    // ---------------------------------------------------------------------------------------------
    // The sign-on program upper-cases the submitted credential before comparing anything, through a
    // twenty-six character ASCII substitution table. The delivered window contains no lower-case
    // character, so folding it is the identity - which means a digest of the raw window and a digest of
    // the folded window are digests of the same value, and matching the raw window is matching what the
    // boundary compares. That is asserted rather than assumed, by fixtureCredentialWindowIsFoldInvariant,
    // because a fixture whose window folded to something else would make every acceptance check here
    // quietly weaker than it reads.
    //
    // Recorded as DL-280 in docs/decision-log.md.
    //
    // A consequence worth naming: the fold's unconditionality can no longer be demonstrated using the
    // credential itself, since for an all-upper value a folded and an unfolded digest are the same
    // digest. That property is demonstrated with a mixed-case PROBE that is not a credential.
    // =============================================================================================

    /** Length of a digest of the credential, as the storage column and the entity both require. */
    public static final int BCRYPT_DIGEST_LENGTH = 60;

    /** Cost factor the module's own encoder is configured with. */
    public static final int BCRYPT_WORK_FACTOR = 12;

    /** Version markers a stored digest may open with. */
    public static final List<String> BCRYPT_VERSION_MARKERS = List.of("$2a$", "$2b$", "$2y$");

    /**
     * Digests the credential seed loads, all distinct and all carrying their own salt.
     *
     * <p>Measured: ten digests, ten distinct values, ten distinct salts, every one at the module's cost
     * factor. Distinct salts are the property that makes ten records of one shared value produce ten
     * different digests, so replacing any one of them is observable.</p>
     */
    public static final int SEEDED_DIGEST_COUNT = 10;

    /**
     * A structurally valid digest that hashes nothing at all.
     *
     * <p>It exists because the identity entity refuses to hold anything that is not shaped like a
     * digest, so a builder needs a default that satisfies the shape without performing a hash. Its
     * body spells out what it is, it is not derived from any value, and it accepts no input - a match
     * against it always fails. A test that needs a digest which genuinely accepts something uses
     * {@link #digestOfFixtureCredentialWindow()} instead.</p>
     */
    public static final String SYNTHETIC_BCRYPT_DIGEST =
            "$2b$12$SYNTHETICFIXTUREDIGESTNOTAREALCREDENTIALVALUEXXXXXXXX";

    /**
     * A value that is deliberately not the fixture's, for proving that a digest refuses as well as
     * accepts.
     */
    public static final String REFUSED_PROBE_VALUE = "not-the-fixture-value";

    /**
     * Recovers the contents of the credential window by offset, from the class-path fixture, at run
     * time.
     *
     * <p>The value is not matched for, searched for or compared against a literal: it is sliced out of a
     * known column range of a known record. The array is freshly allocated on every call, so a caller
     * may - and should - overwrite it once finished.</p>
     *
     * <p><strong>WHAT THIS IS, PRECISELY.</strong> It is the delivered provisioning value, recovered from
     * the credential window of the class-path fixture, which reproduces the ten in-stream provisioning
     * records. It is therefore the value that {@link #digestOfFixtureCredentialWindow()} hashes, the
     * value {@link #digestAcceptsFixtureCredentialWindow(PasswordEncoder, String)} matches with, and -
     * this being the point - the value <em>a seeded digest accepts</em>. That last property is what makes
     * the ten shipped digests verifiable at all; the section comment above records why a fabricated
     * window left them unverifiable and why shape and refusal alone were not enough.</p>
     *
     * <p><strong>DO NOT LOG, PRINT OR ASSERT ON THE RETURN VALUE.</strong> It stands in for a credential
     * and is treated as one. Pass it to an encoder or a matcher and discard it;
     * {@link #digestAcceptsFixtureCredentialWindow(PasswordEncoder, String)} does exactly that and is
     * the preferred entry point, because it keeps the value out of the calling frame altogether.</p>
     *
     * @return the eight characters the first fixture record carries in its credential window
     * @throws IllegalStateException if the fixture is absent or does not carry the ten records the
     *                               layout requires
     */
    public static char[] fixtureCredentialWindow() {
        final FieldSpec field = USER_SECURITY.field("SEC-USR-PWD");
        final List<String> records = fixedWidthRecords(USER_SECURITY_FIXTURE,
                USER_SECURITY.recordLength(), SEEDED_USER_COUNT);
        final char[] window = new char[field.width()];
        records.get(0).getChars(field.offset(), field.endOffset(), window, 0);
        return window;
    }

    /**
     * Reads the credential window of the read-only provisioning member's in-stream card images.
     *
     * <h4>What this is, and why it is not a literal anywhere</h4>
     * The ten delivered digests were produced from the value the legacy provisioning member carries for
     * all ten sign-on identities. That value is recovered here the only way it may be: <strong>at run
     * time, by offset, out of the read-only reference tree</strong>. It is never written down - not in a
     * literal, not in a comment, not in a method name and not in a diagnostic - which is the same
     * discipline {@link #fixtureCredentialWindow()} follows and the same one the credential-literal audit
     * under Gate 6 of {@code docs/gate-evidence.md} describes and reproduces.
     *
     * <p><strong>This is what a test proving the delivered digests must present.</strong> Installing a
     * digest of some other value first and then signing on proves that the encoder works; it proves
     * nothing whatever about the ten digests the migration shipped. Presenting this value does.
     *
     * <p>The ten card images are located by their in-stream delimiters rather than by line number, so a
     * comment added above them does not silently move the window. All ten are required to carry the same
     * value, which is the property the seed depends on and which would otherwise be an assumption.
     *
     * <p><strong>DO NOT LOG, PRINT OR ASSERT ON THE RETURN VALUE.</strong> Overwrite it when finished;
     * {@link #digestAcceptsProvisioningCredential(PasswordEncoder, String)} keeps it out of the calling
     * frame entirely and is the preferred entry point.
     *
     * @return the eight characters every provisioning card image carries in its credential window
     * @throws IllegalStateException if the member is absent, or does not carry ten card images, or the
     *                               ten do not agree
     */
    public static char[] provisioningCredentialWindow() {
        final FieldSpec field = USER_SECURITY.field("SEC-USR-PWD");
        final List<String> cards = provisioningCardImages();
        final char[] window = new char[field.width()];
        cards.get(0).getChars(field.offset(), field.endOffset(), window, 0);
        for (final String card : cards) {
            for (int index = 0; index < field.width(); index++) {
                if (card.charAt(field.offset() + index) != window[index]) {
                    Arrays.fill(window, SPACE_FILLER);
                    throw new IllegalStateException("the ten card images of " + PROVISIONING_MEMBER
                            + " must all carry one credential window, because the ten delivered digests"
                            + " are ten salted digests of one value; they differ at window position "
                            + (index + 1));
                }
            }
        }
        return window;
    }

    /**
     * Reports whether a digest accepts the provisioning credential, without that value entering the
     * caller.
     *
     * <p>The window is read here, used here and overwritten here, so it appears in no assertion message
     * and in no log line of the calling test. This is the method that makes a frozen digest provable:
     * it answers, for a digest read straight off the migrated server, whether the value the legacy
     * member provisions still opens it.
     *
     * @param encoder the encoder the module is configured with
     * @param digest  the digest to test
     * @return {@code true} when the digest accepts the provisioning credential
     */
    public static boolean digestAcceptsProvisioningCredential(final PasswordEncoder encoder,
            final String digest) {
        Objects.requireNonNull(encoder, "encoder");
        Objects.requireNonNull(digest, "digest");
        final char[] window = provisioningCredentialWindow();
        try {
            return encoder.matches(CharBuffer.wrap(window), digest);
        } finally {
            Arrays.fill(window, SPACE_FILLER);
        }
    }

    /**
     * Reads the provisioning member's ten in-stream card images.
     *
     * <p>Delimited rather than numbered: the images sit between the input data definition and the
     * in-stream terminator, so editing the member's comment block cannot shift what is read. Each image
     * is padded on the right to the layout's own width, because the member carries them at their content
     * length while the dataset they produce is fixed-width.
     *
     * @return the ten card images, each at the identity layout's record length
     */
    private static List<String> provisioningCardImages() {
        final Path member = legacyMember(PROVISIONING_MEMBER);
        final String content;
        try {
            content = Files.readString(member, StandardCharsets.ISO_8859_1);
        } catch (final IOException unreadable) {
            throw new IllegalStateException("the provisioning member at " + member
                    + " could not be read, so the credential it provisions cannot be recovered",
                    unreadable);
        }

        final List<String> images = new ArrayList<>(SEEDED_USER_COUNT);
        boolean inStream = false;
        for (String line : content.split("\n", -1)) {
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            if (line.startsWith(PROVISIONING_STREAM_OPEN)) {
                inStream = true;
                continue;
            }
            if (!inStream) {
                continue;
            }
            if (line.startsWith(PROVISIONING_STREAM_CLOSE)) {
                break;
            }
            images.add(line.length() >= USER_SECURITY.recordLength()
                    ? line.substring(0, USER_SECURITY.recordLength())
                    : line + String.valueOf(SPACE_FILLER)
                            .repeat(USER_SECURITY.recordLength() - line.length()));
        }

        if (images.size() != SEEDED_USER_COUNT) {
            throw new IllegalStateException(PROVISIONING_MEMBER + " must carry exactly "
                    + SEEDED_USER_COUNT + " in-stream card images between '"
                    + PROVISIONING_STREAM_OPEN + "' and '" + PROVISIONING_STREAM_CLOSE + "', but "
                    + images.size() + " were found");
        }
        return List.copyOf(images);
    }

    /**
     * Resolves one member of the read-only legacy estate, which lives above the module directory.
     *
     * <p>The build's working directory is the module, so the estate is reached by walking upward to the
     * checkout root - the directory that holds both the module and the estate. Bounded by the filesystem
     * root, so a misconfigured working directory produces a diagnostic instead of an endless loop.
     *
     * <p>Package-private rather than private so a specification in this package can verify a literal it
     * restates against the member it restates it from - which is what makes a hand-typed oracle a
     * verified oracle rather than a second opinion.
     *
     * @param  relativePath the member's repository-relative path
     * @return the resolved path
     */
    static Path legacyMember(final String relativePath) {
        final Path start = Path.of("").toAbsolutePath().normalize();
        for (Path candidate = start; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve(MODULE_DIRECTORY))
                    && Files.isDirectory(candidate.resolve(LEGACY_ROOT))) {
                final Path member = candidate.resolve(relativePath);
                if (Files.isRegularFile(member)) {
                    return member;
                }
            }
        }
        throw new IllegalStateException("no checkout root above " + start + " holds both a '"
                + MODULE_DIRECTORY + "' directory and a '" + LEGACY_ROOT + "' directory containing "
                + relativePath + ". The build's working directory is the module, so the read-only "
                + "estate is resolved by walking upward.");
    }

    /**
     * Produces a digest that genuinely accepts the fixture's credential-window value, at the module's
     * cost factor.
     *
     * <p>The window is read, wrapped rather than copied into a string of this frame's own making, handed
     * to the encoder, and then overwritten. Successive calls return different digests because each
     * carries its own salt - the same property that makes the ten seeded identities carry ten distinct
     * digests of one shared value.</p>
     *
     * <p>This is what a test seeding its own identity should store, because the pair of this method and
     * {@link #digestAcceptsFixtureCredentialWindow(PasswordEncoder, String)} is self-consistent: the
     * digest is produced from the window value and verified against the same window value, with the
     * value itself never leaving this class.</p>
     *
     * @return a fresh digest of {@link #BCRYPT_DIGEST_LENGTH} characters
     */
    public static String digestOfFixtureCredentialWindow() {
        final char[] window = fixtureCredentialWindow();
        try {
            return new BCryptPasswordEncoder(BCRYPT_WORK_FACTOR).encode(CharBuffer.wrap(window));
        } finally {
            Arrays.fill(window, SPACE_FILLER);
        }
    }

    /**
     * Reports whether a digest accepts the fixture's credential-window value, without that value
     * entering the caller.
     *
     * <p>The window is read here, used here and overwritten here, so it can appear in no assertion
     * message and in no log line of the calling test.</p>
     *
     * <p><strong>THIS IS THE ACCEPTANCE CHECK FOR A SEEDED DIGEST, AND IT RETURNS TRUE.</strong> The ten
     * digests the credential seed loads were produced from the delivered provisioning value, and the
     * fixture's window carries that same value, so each of the ten accepts it. Asserting that is the only
     * check which separates a <em>correct</em> shipped digest from a merely <em>well-formed</em> one:
     * {@link #hasStoredDigestShape(String)} and
     * {@link #digestRefusesOtherValues(PasswordEncoder, String)} are both satisfied by a digest of any
     * value at all, so on their own they would pass over a wrong literal in the seed. Assert all three
     * together - shape, acceptance, refusal - and none of them alone.</p>
     *
     * @param encoder the encoder the module is configured with
     * @param digest  the digest to test
     * @return {@code true} when the digest accepts the fixture's credential-window value
     */
    public static boolean digestAcceptsFixtureCredentialWindow(final PasswordEncoder encoder,
            final String digest) {
        Objects.requireNonNull(encoder, "encoder");
        Objects.requireNonNull(digest, "digest");
        final char[] window = fixtureCredentialWindow();
        try {
            return encoder.matches(CharBuffer.wrap(window), digest);
        } finally {
            Arrays.fill(window, SPACE_FILLER);
        }
    }

    /**
     * Reports whether the fixture's credential window is invariant under the sign-on fold.
     *
     * <p>The sign-on program upper-cases the submitted credential before comparing anything, through a
     * twenty-six character ASCII substitution table. When the window contains no lower-case character
     * that fold is the identity, and matching the raw window is therefore matching exactly what the
     * boundary compares - which is the assumption every acceptance check in this class rests on.</p>
     *
     * <p>Asserted rather than assumed, because a fixture whose window folded to something else would
     * make each of those checks quietly weaker than it reads: a digest produced from the raw window
     * would then be compared against a folded submission and would never match, and the refusal would
     * look like a contract failure rather than a fixture defect. The fold is applied over the character
     * array here rather than by calling the module's string utility, because that utility takes a
     * {@code String} and the value must not become one.</p>
     *
     * @return {@code true} when folding the window changes nothing
     */
    public static boolean fixtureCredentialWindowIsFoldInvariant() {
        final char[] window = fixtureCredentialWindow();
        try {
            for (final char character : window) {
                if (character >= 'a' && character <= 'z') {
                    return false;
                }
            }
            return true;
        } finally {
            Arrays.fill(window, SPACE_FILLER);
        }
    }

    /**
     * Returns a one-way fingerprint of the fixture's credential window, as lower-case hexadecimal.
     *
     * <p><strong>Why a fingerprint rather than the value.</strong> A specification that guards the
     * fixture wants an EXACT comparison - a measurement such as "not blank, and the same on all ten
     * records" is satisfied by any populated window and so would not notice one being swapped for
     * another. An exact comparison normally means a literal, and a literal here would put the credential
     * into a Java source and into every failure message that printed it.</p>
     *
     * <p>A SHA-256 fingerprint gives the exactness without either cost: it changes whenever the window
     * changes, and it is safe to declare as a constant, to print in a diagnostic and to read in a
     * review, because it cannot be inverted into the value it stands for. It is a guard against silent
     * drift, not a security control.</p>
     *
     * @return sixty-four lower-case hexadecimal characters
     */
    public static String fixtureCredentialWindowFingerprint() {
        final char[] window = fixtureCredentialWindow();
        final byte[] encoded = new byte[window.length];
        try {
            for (int index = 0; index < window.length; index++) {
                encoded[index] = (byte) window[index];
            }
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(encoded));
        } catch (final NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(
                    "SHA-256 is required of every Java platform, so its absence is not a condition this "
                            + "fixture can work around", unavailable);
        } finally {
            Arrays.fill(window, SPACE_FILLER);
            Arrays.fill(encoded, (byte) SPACE_FILLER);
        }
    }

    /**
     * Reports whether a digest refuses a value it was not derived from.
     *
     * <p>Answerable about any digest, seeded or built, and measured to hold for all ten seeded ones. A
     * digest that accepted everything would satisfy an acceptance check and be worthless, which is why a
     * refusal check is needed <em>alongside</em> one - and, symmetrically, why it is no substitute for
     * one: a digest of the wrong value refuses this probe just as readily as a digest of the right
     * value does.</p>
     *
     * @param encoder the encoder the module is configured with
     * @param digest  the digest to test
     * @return {@code true} when the digest refuses {@link #REFUSED_PROBE_VALUE}
     */
    public static boolean digestRefusesOtherValues(final PasswordEncoder encoder,
            final String digest) {
        Objects.requireNonNull(encoder, "encoder");
        Objects.requireNonNull(digest, "digest");
        return !encoder.matches(REFUSED_PROBE_VALUE, digest);
    }

    /**
     * Reports whether a value has the shape a stored digest must have: the required length, a
     * recognised version marker, and the module's cost factor.
     *
     * <p>Shape only, and shape is a weak property: a digest of the wrong value has exactly the same
     * shape as a digest of the right one. What the digest accepts is
     * {@link #digestAcceptsFixtureCredentialWindow(PasswordEncoder, String)}, and a seed assertion that
     * checks shape without checking acceptance would pass over a wrong literal.</p>
     *
     * @param digest the value to inspect
     * @return {@code true} when the value is shaped like a digest at the module's cost factor
     */
    public static boolean hasStoredDigestShape(final String digest) {
        if (digest == null || digest.length() != BCRYPT_DIGEST_LENGTH) {
            return false;
        }
        boolean markerRecognised = false;
        for (final String marker : BCRYPT_VERSION_MARKERS) {
            if (digest.startsWith(marker)) {
                markerRecognised = true;
                break;
            }
        }
        if (!markerRecognised) {
            return false;
        }
        final String cost = digits(String.valueOf(BCRYPT_WORK_FACTOR), 2, "cost factor");
        return digest.regionMatches(4, cost, 0, 2) && digest.charAt(6) == '$';
    }

    // =============================================================================================
    // SECTION 14 - THE TEN SIGN-ON IDENTITIES: IDENTITY PERMITTED, CREDENTIAL FORBIDDEN
    //
    // Identifier, given name, family name and role code are ordinary reference data and are recorded
    // here. The credential is not, and is not. Five identities carry the administrative role code and
    // five carry the standard one, measured over the ten records.
    // =============================================================================================

    /**
     * One sign-on identity, credential excluded by construction.
     *
     * <p>There is no credential component and no credential accessor. That is the point: an identity
     * can be named, compared and asserted on without any code path being able to reach the value.</p>
     *
     * @param userId       the eight-character identifier
     * @param firstName    the given name, up to twenty characters
     * @param lastName     the family name, up to twenty characters
     * @param userTypeCode the one-character role code
     */
    public record SeededIdentity(String userId, String firstName, String lastName,
            String userTypeCode) {

        /** Validates the widths the eighty byte layout declares. */
        public SeededIdentity {
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(firstName, "firstName");
            Objects.requireNonNull(lastName, "lastName");
            Objects.requireNonNull(userTypeCode, "userTypeCode");
            if (userId.length() != USER_SECURITY.field("SEC-USR-ID").width()) {
                throw new IllegalArgumentException("a sign-on identifier occupies exactly "
                        + USER_SECURITY.field("SEC-USR-ID").width() + " bytes, but " + userId.length()
                        + " were supplied");
            }
            if (userTypeCode.length() != USER_SECURITY.field("SEC-USR-TYPE").width()) {
                throw new IllegalArgumentException("a role code occupies exactly one byte, but "
                        + userTypeCode.length() + " were supplied for identity " + userId);
            }
            if (firstName.length() > USER_SECURITY.field("SEC-USR-FNAME").width()
                    || lastName.length() > USER_SECURITY.field("SEC-USR-LNAME").width()) {
                throw new IllegalArgumentException("the name fields of identity " + userId
                        + " each hold at most " + USER_SECURITY.field("SEC-USR-FNAME").width()
                        + " bytes");
            }
        }

        /**
         * Resolves the role the code denotes.
         *
         * <p>The estate treats the administrative code as the only distinguished value and routes every
         * other code to the standard surface, so this reproduces that rather than refusing an unknown
         * code.</p>
         *
         * @return the role
         */
        public UserType userType() {
            return UserType.ADMIN.getCode().equals(userTypeCode) ? UserType.ADMIN : UserType.USER;
        }

        /**
         * Reports whether this identity reaches the administrative surface.
         *
         * @return {@code true} for the administrative role
         */
        public boolean isAdministrator() {
            return userType().isAdmin();
        }
    }

    /**
     * The ten sign-on identities the credential seed loads, in the order the fixture carries them.
     *
     * <p>Read from the delivered provisioning member's in-stream card images, which are readable
     * without decoding the mainframe data set. Five carry the administrative role code and five carry
     * the standard one.</p>
     */
    public static final List<SeededIdentity> SEEDED_IDENTITIES = List.of(
            new SeededIdentity("ADMIN001", "MARGARET", "GOLD", "A"),
            new SeededIdentity("ADMIN002", "RUSSELL", "RUSSELL", "A"),
            new SeededIdentity("ADMIN003", "RAYMOND", "WHITMORE", "A"),
            new SeededIdentity("ADMIN004", "EMMANUEL", "CASGRAIN", "A"),
            new SeededIdentity("ADMIN005", "GRANVILLE", "LACHAPELLE", "A"),
            new SeededIdentity("USER0001", "LAWRENCE", "THOMAS", "U"),
            new SeededIdentity("USER0002", "AJITH", "KUMAR", "U"),
            new SeededIdentity("USER0003", "LAURITZ", "ALME", "U"),
            new SeededIdentity("USER0004", "AVERARDO", "MAZZI", "U"),
            new SeededIdentity("USER0005", "LEE", "TING", "U"));

    /**
     * Starts a sign-on identity, defaulted to the first seeded administrator.
     *
     * @return a new builder
     */
    public static UserSecurityBuilder userSecurity() {
        return new UserSecurityBuilder();
    }

    /**
     * Builds a sign-on identity entity and the eighty byte record image.
     *
     * <p>The entity's credential attribute holds a digest and refuses anything else, so it defaults to
     * {@link #SYNTHETIC_BCRYPT_DIGEST}; a test that needs a digest which genuinely accepts the fixture
     * credential-window value calls {@link #withDigestOfFixtureCredentialWindow()}.</p>
     *
     * <p>The record image's credential field is a different thing entirely - eight cleartext bytes in
     * the legacy layout - and it defaults to blanks, because the fixture's value is never written into
     * this file. {@link #credentialFieldFromFixture()} fills it at run time for a test that needs to
     * reproduce a fixture record image byte for byte.</p>
     */
    public static final class UserSecurityBuilder {

        private String userId = SEEDED_IDENTITIES.get(0).userId();
        private String firstName = SEEDED_IDENTITIES.get(0).firstName();
        private String lastName = SEEDED_IDENTITIES.get(0).lastName();
        private String userTypeCode = SEEDED_IDENTITIES.get(0).userTypeCode();
        private String storedDigest = SYNTHETIC_BCRYPT_DIGEST;
        private String credentialField =
                String.valueOf(SPACE_FILLER).repeat(USER_SECURITY.field("SEC-USR-PWD").width());

        private UserSecurityBuilder() {
            // Intentionally empty: every field carries a measured default.
        }

        /**
         * Sets the eight-character identifier, which is the business key.
         *
         * @param value the identifier
         * @return this builder
         */
        public UserSecurityBuilder userId(final String value) {
            this.userId = value;
            return this;
        }

        /**
         * Sets the twenty-character given name.
         *
         * @param value the name
         * @return this builder
         */
        public UserSecurityBuilder firstName(final String value) {
            this.firstName = value;
            return this;
        }

        /**
         * Sets the twenty-character family name.
         *
         * @param value the name
         * @return this builder
         */
        public UserSecurityBuilder lastName(final String value) {
            this.lastName = value;
            return this;
        }

        /**
         * Sets the one-character role code, stored exactly as given and unjudged, because the estate
         * screened no value here.
         *
         * @param value the role code
         * @return this builder
         */
        public UserSecurityBuilder userTypeCode(final String value) {
            this.userTypeCode = value;
            return this;
        }

        /**
         * Sets the role code from the enumerated contract.
         *
         * @param value the role
         * @return this builder
         */
        public UserSecurityBuilder userType(final UserType value) {
            this.userTypeCode = value.getCode();
            return this;
        }

        /**
         * Adopts every field of a seeded identity except its credential, which the identity does not
         * carry.
         *
         * @param value the identity to adopt
         * @return this builder
         */
        public UserSecurityBuilder identity(final SeededIdentity value) {
            this.userId = value.userId();
            this.firstName = value.firstName();
            this.lastName = value.lastName();
            this.userTypeCode = value.userTypeCode();
            return this;
        }

        /**
         * Sets the digest the entity stores.
         *
         * @param value a value shaped like a digest; the entity refuses anything else
         * @return this builder
         */
        public UserSecurityBuilder storedDigest(final String value) {
            this.storedDigest = value;
            return this;
        }

        /**
         * Sets the stored digest to a fresh one that genuinely accepts the fixture's credential-window
         * value.
         *
         * <p>Pairs with {@link #digestAcceptsFixtureCredentialWindow(PasswordEncoder, String)}, so an
         * identity built this way can be authenticated without the value entering the test at all.</p>
         *
         * @return this builder
         */
        public UserSecurityBuilder withDigestOfFixtureCredentialWindow() {
            this.storedDigest = digestOfFixtureCredentialWindow();
            return this;
        }

        /**
         * Sets the eight bytes the record image carries in its credential field.
         *
         * <p>Image representation only; it never reaches the entity, which holds a digest.</p>
         *
         * @param value the eight bytes
         * @return this builder
         */
        public UserSecurityBuilder credentialField(final String value) {
            this.credentialField = value;
            return this;
        }

        /**
         * Fills the record image's credential field from the fixture, at run time, by offset.
         *
         * <p>For a test that must reproduce a fixture record image exactly. The value is read into the
         * image and nowhere else; it is still never logged and never asserted on.</p>
         *
         * @return this builder
         */
        public UserSecurityBuilder credentialFieldFromFixture() {
            final char[] credential = fixtureCredentialWindow();
            try {
                this.credentialField = new String(credential);
            } finally {
                Arrays.fill(credential, ' ');
            }
            return this;
        }

        /**
         * Builds the entity.
         *
         * @return a sign-on identity carrying the values configured on this builder
         */
        public UserSecurity build() {
            return new UserSecurity(userId, firstName, lastName, storedDigest, userTypeCode);
        }

        /**
         * Builds the record image, whose trailing twenty-three bytes are the layout's named filler.
         *
         * @return the eighty byte sign-on record image
         */
        public String image() {
            return complete(USER_SECURITY, text(USER_SECURITY, "SEC-USR-ID", userId)
                    + text(USER_SECURITY, "SEC-USR-FNAME", firstName)
                    + text(USER_SECURITY, "SEC-USR-LNAME", lastName)
                    + text(USER_SECURITY, "SEC-USR-PWD", credentialField)
                    + text(USER_SECURITY, "SEC-USR-TYPE", userTypeCode));
        }
    }

    // =============================================================================================
    // SECTION 15 - THE CONSTRUCTED FIXTURES THE SEED CANNOT SUPPLY
    //
    // Three branches are unreachable from seeded data, each for a measured reason, and each therefore
    // needs a fixture built rather than read. The seed itself is never mutated to compensate: the
    // legacy tree is read-only, and the seeded content is what the byte-equivalence gate compares
    // against.
    // =============================================================================================

    /** Amount large enough to breach any seeded limit: the widest value the eleven-byte field holds. */
    public static final BigDecimal OVERLIMIT_AMOUNT = new BigDecimal("9999999.99");

    /**
     * A timestamp later than any seeded account expiry date, so a record carrying it arrives after the
     * account has expired.
     *
     * <p>A literal rather than a clock reading, so the record means the same thing on every run and on
     * every host.</p>
     */
    public static final String POST_EXPIRY_TIMESTAMP = "2099-12-31 23:59:59.000000";

    /** Amount of a return, which the seeded operator-sourced records carry as a negative value. */
    private static final BigDecimal DEFAULT_RETURN_AMOUNT = new BigDecimal("-504.77");

    /**
     * A card number that appears in no card table, no cross-reference and no account.
     *
     * <p>Its use is to prove the <strong>landing</strong> table's deliberate absence of referential
     * constraints: a record naming it is accepted by the server, which is what lets an input carrying an
     * unresolvable reference reach the validation cascade at all rather than being refused on insert.</p>
     *
     * <p><strong>It does NOT reach the account-not-found reasons, and it never could.</strong> That
     * would require a cross-reference from this card to an account the account table does not hold, and
     * {@code V2__create_indexes.sql} puts a foreign key from the cross-reference table to the account
     * table precisely to forbid such a row. Those two reasons are reached through the account-repository
     * spy in {@code RejectReasonArmsIT}, which makes one read report the absence the legacy file's
     * {@code INVALID KEY} arm reported while leaving the schema and the row intact. See
     * {@link #dailyTransactionRejectedBy(RejectReason)}, which refuses to pretend otherwise.</p>
     */
    public static final String ORPHANED_CARD_NUMBER = "8888888888888888";

    /**
     * The reason code that means no rejection; posting proceeds only on this value.
     *
     * <p>Read off {@link LegacyRejectReason#NO_REASON_CODE}, which is the legacy member's own value and
     * not the module's. See the note on the following constant for why the difference matters.
     */
    public static final int NO_REJECT_REASON_CODE = LegacyRejectReason.NO_REASON_CODE;

    /**
     * Every reject reason code the legacy validation cascade can produce, in ascending order, taken
     * from a hand transcription of the legacy source rather than from the shipped enumeration.
     *
     * <h4>Why these come from an oracle and not from the module's enumeration</h4>
     * They used to be collected from {@code RejectReason.values()}. That made this list agree with the
     * module by construction: a wrong code in the enumeration would have produced a wrong code in the
     * fixture and a wrong code in the expectation at the same time, and every comparison between them
     * would still have passed. They now come from {@link LegacyRejectReasons#CODES}, which imports
     * nothing from the shipped types, and the shipped enumeration is asserted AGAINST that table in
     * {@code RejectReasonOracleTest} - the direction that establishes the contract. A second, independently
     * written transcription of the same five sites, {@link LegacyRejectReason}, is held to the legacy
     * member by {@code LegacyRejectReasonTest}; the two agree, and either one leaves the module's
     * enumeration free to be wrong and be caught.
     */
    public static final List<Integer> REJECT_REASON_CODES = LegacyRejectReasons.CODES;

    /**
     * An account naming a real disclosure group, so the direct interest-rate lookup is reached.
     *
     * <p><strong>THIS FIXTURE IS MANDATORY, NOT CONVENIENT.</strong> Every one of the fifty seeded
     * accounts leaves its group identifier blank, so the seeded interest run misses the direct group on
     * every account and only ever takes the fallback path. Without an account built here, the
     * direct-hit branch cannot be reached at all.</p>
     *
     * @return a builder for an account whose group identifier names the first seeded disclosure group
     */
    public static AccountBuilder directHitDisclosureAccount() {
        return account().groupId(DIRECT_HIT_DISCLOSURE_GROUP_ID);
    }

    /**
     * An account whose group identifier is blank, reproducing what every seeded account carries and
     * therefore taking the fallback lookup path.
     *
     * @return a builder for an account with a blank group identifier
     */
    public static AccountBuilder fallbackDisclosureAccount() {
        return account().groupId(SEEDED_ACCOUNT_GROUP_ID);
    }

    /**
     * A landing record carrying a real processing date, so a date window can select or reject it.
     *
     * <p><strong>THIS FIXTURE IS MANDATORY, NOT CONVENIENT.</strong> The processing timestamp is blank
     * on all three hundred seeded records, so the seed cannot exercise processing-date filtering at
     * all. The report filter reads the first ten bytes of that field as character data; this builder is
     * what puts something there to read.</p>
     *
     * @param processingDate the date to place at the start of the processing timestamp
     * @return a builder for a landing record carrying that processing date
     */
    public static DailyTransactionBuilder dailyTransactionWithProcessingDate(
            final LocalDate processingDate) {
        return dailyTransaction().processingDate(processingDate);
    }

    /**
     * A posted record carrying a real processing date, for the report's inclusive window.
     *
     * @param processingDate the date to place at the start of the processing timestamp
     * @return a builder for a posted record carrying that processing date
     */
    public static TransactionBuilder transactionWithProcessingDate(final LocalDate processingDate) {
        return transaction().processingDate(processingDate);
    }

    /**
     * A landing record naming a card, an account and a customer that exist in no seeded row.
     *
     * <p>Writable because the landing table carries no referential constraints, and rejected once
     * validation runs, which is what makes the four-hundred-and-thirty byte reject record reachable.</p>
     *
     * @return a builder for a landing record that no seeded row backs
     */
    public static DailyTransactionBuilder orphanDailyTransaction() {
        return dailyTransaction().cardNumber(UNKNOWN_CARD_NUMBER);
    }

    /**
     * A landing record in the purchase direction, as two hundred and fifty of the three hundred seeded
     * records are: point-of-sale source, non-negative amount.
     *
     * @return a builder for a purchase
     */
    public static DailyTransactionBuilder purchaseDailyTransaction() {
        return dailyTransaction().source(TransactionSourceType.POS_TERM)
                .typeCode(DEFAULT_TRANSACTION_TYPE_CODE)
                .amount(DEFAULT_TRANSACTION_AMOUNT);
    }

    /**
     * A landing record in the return direction, as fifty of the three hundred seeded records are:
     * operator source, negative amount.
     *
     * <p>Both directions matter, because the balance computation is exercised only when both signs
     * appear.</p>
     *
     * @return a builder for a return
     */
    public static DailyTransactionBuilder returnDailyTransaction() {
        return dailyTransaction().source(TransactionSourceType.OPERATOR)
                .typeCode(DEFAULT_RETURN_TYPE_CODE)
                .amount(DEFAULT_RETURN_AMOUNT);
    }

    /**
     * A landing record shaped to reach one named reject reason - for the three reasons a record can
     * reach on its own.
     *
     * <p>Three of the five reject reasons are properties of the record and the seeded state together,
     * and this supplies the record half of each:</p>
     * <ul>
     *   <li>invalid card number - complete here: the record names a card no seeded row carries.</li>
     *   <li>overlimit - complete here: the amount alone breaches any seeded limit. The basis is
     *       evaluated strictly left to right as cycle credit less cycle debit plus this amount, so no
     *       rearrangement of that expression is performed anywhere.</li>
     *   <li>arrival after account expiry - complete here: the record's original timestamp is later than
     *       any seeded expiry date.</li>
     * </ul>
     *
     * <h3>The two reasons this method REFUSES to pretend it can shape</h3>
     *
     * <p>The two account-not-found reasons are not reachable from any landing record, and this method
     * used to claim otherwise. It handed back a record naming {@link #ORPHANED_CARD_NUMBER} and
     * instructed the caller to register a cross-reference from that card to {@link #UNKNOWN_ACCOUNT_ID}
     * so that the card would resolve and the account would not. <strong>That instruction cannot be
     * carried out.</strong> The cross-reference table carries a foreign key to the account table, added
     * by {@code V2__create_indexes.sql}, so the dangling row the recipe depends on is refused by the
     * server. Every caller that followed the recipe would fail on the arrangement, and any caller that
     * skipped it would receive a record that quietly posts - which is how the two arms came to be
     * unobserved while every assertion about them passed.</p>
     *
     * <p>Recorded as DL-279 in {@code docs/decision-log.md}.</p>
     *
     * <p>Both are therefore refused here, loudly, with a message naming where they ARE reached:
     * {@code RejectReasonArmsIT} enters them through a narrow spy over the account repository, which
     * makes one read report what the legacy file's {@code INVALID KEY} arm reported while the schema,
     * the foreign key and the row all stay intact. A factory that returns a fixture unable to do what
     * its name says is worse than one that refuses, because the refusal is discovered at the call site
     * rather than inferred from a test that never went red.</p>
     *
     * <p>The switch remains exhaustive over the contract enumeration with no fallback arm, so adding a
     * reason to the contract still fails this file at compile time rather than silently producing a
     * record that reaches nothing.</p>
     *
     * @param reason the legacy reason the record should reach, from the oracle rather than from the
     *               module's own enumeration - a record built to trigger what the module <em>thinks</em>
     *               a reason is proves nothing about what the estate does
     * @return a builder for a landing record shaped to reach that reason
     * @throws IllegalArgumentException if the reason is one of the two no landing record can reach
     */
    public static DailyTransactionBuilder dailyTransactionRejectedBy(final RejectReason reason) {
        Objects.requireNonNull(reason, "reason");
        return switch (reason) {
            case INVALID_CARD_NUMBER -> dailyTransaction().cardNumber(UNKNOWN_CARD_NUMBER);
            case ACCOUNT_NOT_FOUND_ON_READ, ACCOUNT_NOT_FOUND_ON_REWRITE ->
                    throw new IllegalArgumentException("no landing record can reach " + reason.name()
                            + " (code " + reason.getReasonCode() + "): the arm is entered when the"
                            + " account read or the account rewrite reports no row, and the"
                            + " cross-reference table's foreign key to the account table forbids the"
                            + " dangling row that would produce that from persisted state. Reach it"
                            + " through the account-repository spy in RejectReasonArmsIT instead, which"
                            + " leaves the schema, the foreign key and the row intact");
            case OVERLIMIT_TRANSACTION -> dailyTransaction().amount(OVERLIMIT_AMOUNT);
            case TRANSACTION_AFTER_ACCOUNT_EXPIRATION ->
                    dailyTransaction().originalTimestamp(POST_EXPIRY_TIMESTAMP);
        };
    }

    // =============================================================================================
    // SECTION 16 - AN INDEPENDENTLY DERIVED REJECT-RECORD EXPECTATION
    //
    // Four hundred and thirty bytes: the landing image copied verbatim, then a four-digit zero-filled
    // reason code, then a seventy-six character blank-padded description. 350 + 4 + 76 = 430.
    //
    // Assembled here from the widths rather than by calling the writing step, for the reason the class
    // documentation gives: an expectation produced by the code under test cannot detect a defect in
    // that code. A comparison against this image is byte equality over the whole four hundred and
    // thirty bytes, never a trimmed or semantic comparison, so a stray blank or a wrong sign byte
    // fails rather than passing unnoticed.
    //
    // The SAME argument applies to the two values inside the trailer, and it did not used to be
    // honoured. The four digits and the seventy-six characters were read out of the shipped
    // RejectReason enumeration, so the assembled expectation agreed with the implementation by
    // construction and a wrong code produced a wrong file and a passing test. Both values now come
    // from LegacyRejectReasons, a hand transcription of the legacy source that imports nothing from
    // the shipped types; the enumeration is asserted against that table in RejectReasonOracleTest.
    // =============================================================================================

    /**
     * Assembles the eighty byte validation trailer.
     *
     * @param reasonCode  the reason code, rendered as four digits zero-filled on the left
     * @param description the description, blank-padded on the right to seventy-six characters
     * @return the eighty byte trailer
     * @throws IllegalArgumentException if the code does not fit four digits or the description does not
     *                                  fit its field
     */
    public static String rejectTrailer(final int reasonCode, final String description) {
        if (reasonCode < 0 || reasonCode > 9999) {
            throw new IllegalArgumentException("a reject reason code occupies "
                    + REJECT_REASON_CODE_WIDTH + " digits, so it lies between 0 and 9999, but "
                    + reasonCode + " was supplied");
        }
        return digits(Integer.toString(reasonCode), REJECT_REASON_CODE_WIDTH, "reject reason code")
                + alphanumeric(description, REJECT_DESCRIPTION_WIDTH, "reject description");
    }

    /**
     * Assembles a reject record from a landing image and an explicit reason and description.
     *
     * <p>The landing image is copied verbatim, byte for byte. It is not re-rendered, re-padded or
     * normalised, because the contract is that the first three hundred and fifty bytes of a reject
     * record are the source record exactly as it arrived.</p>
     *
     * @param landingImage the three-hundred-and-fifty byte landing record image
     * @param reasonCode   the reason code
     * @param description  the description
     * @return the four-hundred-and-thirty byte reject record image
     * @throws IllegalArgumentException if the landing image is not the width its layout declares
     */
    public static String rejectRecordImage(final String landingImage, final int reasonCode,
            final String description) {
        Objects.requireNonNull(landingImage, "landingImage");
        if (landingImage.length() != DAILY_TRANSACTION.recordLength()) {
            throw new IllegalArgumentException("a reject record opens with the landing image copied"
                    + " verbatim, which measures " + DAILY_TRANSACTION.recordLength()
                    + " bytes, but an image of " + landingImage.length() + " bytes was supplied");
        }
        final String image = landingImage + rejectTrailer(reasonCode, description);
        if (image.length() != REJECT_RECORD_WIDTH) {
            throw new IllegalStateException("a reject record measures " + REJECT_RECORD_WIDTH
                    + " bytes - " + DAILY_TRANSACTION.recordLength() + " plus "
                    + REJECT_REASON_CODE_WIDTH + " plus " + REJECT_DESCRIPTION_WIDTH
                    + " - but the assembled image measures " + image.length());
        }
        return image;
    }

    /**
     * Assembles a reject record from a landing image and a reason, taking the four digits and the
     * seventy-six characters from the hand transcription of the legacy source rather than from the reason
     * value that was passed in.
     *
     * <h3>Why the supplied reason is NAMED here and not read</h3>
     *
     * <p>This overload used to call {@code reason.getReasonCode()} and {@code reason.getDescription()}, so
     * the expected trailer was assembled out of the very values a comparison against it was supposed to
     * check. Every reject-record assertion in the estate was therefore self-referential: had the shipped
     * enumeration recorded 104 where the legacy sets 103, the expectation would have said 104 too and the
     * comparison would have passed over a file no consumer could read.</p>
     *
     * <p>The reason is still accepted as a parameter, because a test parameterised over the shipped
     * enumeration is the natural way to cover every arm and because the production code hands back
     * enumeration values. But only its NAME is used: {@link LegacyRejectReasons} is asked which code and
     * which description the legacy source sets at the site that constant stands for, and the trailer is
     * built from those. A drifted code in production now changes the produced bytes while leaving this
     * expectation where the source put it, so the comparison fails - which is the whole point.</p>
     *
     * @param landingImage the three-hundred-and-fifty byte landing record image
     * @param reason       the reason, used to name the transcribed entry rather than to supply its values
     * @return the four-hundred-and-thirty byte reject record image
     * @throws IllegalArgumentException if no transcribed entry stands for the supplied reason
     */
    public static String rejectRecordImage(final String landingImage, final RejectReason reason) {
        final LegacyRejectReasons.Reason transcribed = transcribedReasonFor(reason);
        return rejectRecordImage(landingImage, transcribed.code(), transcribed.description());
    }

    /**
     * Returns the hand transcription of the legacy reason that the supplied shipped constant stands for.
     *
     * <p>The single place the binding between the shipped enumeration and the transcribed table lives, so
     * a specification that is parameterised over the enumeration - the natural shape, because the
     * production code hands back enumeration values - can still take its expected code and its expected
     * description from the legacy source rather than from the value it is checking. Only the constant's
     * NAME crosses the boundary; neither its code nor its description is read.</p>
     *
     * @param reason the shipped reason
     * @return the transcribed reason that constant must carry
     * @throws IllegalArgumentException if the transcription records nothing for a constant of that name
     */
    public static LegacyRejectReasons.Reason transcribedReasonFor(final RejectReason reason) {
        Objects.requireNonNull(reason, "reason");
        return LegacyRejectReasons.requireByShippedConstantName(reason.name());
    }

    // =============================================================================================
    // SECTION 17 - DATE-WINDOW PROBES FOR AN INCLUSIVE FILTER
    //
    // The report filter treats both bounds of its window as inclusive, which is the behaviour the
    // legacy filter had, so four positions distinguish an inclusive bound from an exclusive one: one
    // day below the lower bound, the lower bound itself, the upper bound itself, and one day above it.
    //
    // ---------------------------------------------------------------------------------------------
    // WHY THESE TAKE THE WINDOW AS AN ARGUMENT INSTEAD OF READING THE PINNED ONE.
    // ---------------------------------------------------------------------------------------------
    // The pinned instant, the pinned business date and the two pinned window bounds belong to
    // AbstractPostgresIT, and this class deliberately reads NONE of them. Reading one is not free:
    // those constants are not compile-time constants, so a read initialises their owning class, and
    // that class starts a PostgreSQL server and migrates it in its own initialiser. A static reference
    // from here would therefore make every unit test that so much as assembled a record image start a
    // database - which was observed, not theorised, and is why this section is shaped this way.
    //
    // Nothing is duplicated as a result. A container-backed test already has the pinned window in scope
    // through its own supertype and passes it in; a unit test passes whatever window it is testing. The
    // pinned values keep their single home, and this class keeps no opinion about them.
    // =============================================================================================

    /**
     * Renders a clock's current instant as record timestamp text.
     *
     * <p>Takes the clock rather than reading a system one, so a caller supplying a fixed clock gets a
     * timestamp that means the same thing on every run and on every host. Supplying a system clock here
     * would reintroduce exactly the non-determinism this class exists to avoid.</p>
     *
     * @param clock the clock to read, which should be a fixed one
     * @return the twenty-six character timestamp text of that clock's instant
     */
    public static String recordTimestampAt(final Clock clock) {
        Objects.requireNonNull(clock, "clock");
        return recordTimestamp(clock.instant());
    }

    /**
     * The four processing dates that distinguish an inclusive window from an exclusive one, in
     * ascending order: one day below the lower bound, the lower bound, the upper bound, one day above
     * the upper bound.
     *
     * <p>An inclusive filter selects the middle two and rejects the outer two. A filter that wrongly
     * treated either bound as exclusive would reject one of the middle two, which is what makes these
     * four the right probes and a single inside date the wrong one.</p>
     *
     * @param windowStart inclusive lower bound of the window under test
     * @param windowEnd   inclusive upper bound of the window under test
     * @return the four probe dates in ascending order
     * @throws IllegalArgumentException if the bounds are the wrong way round
     */
    public static List<LocalDate> inclusiveWindowProbeDates(final LocalDate windowStart,
            final LocalDate windowEnd) {
        Objects.requireNonNull(windowStart, "windowStart");
        Objects.requireNonNull(windowEnd, "windowEnd");
        if (windowEnd.isBefore(windowStart)) {
            throw new IllegalArgumentException("a reporting window ends on or after it begins, but "
                    + windowEnd + " precedes " + windowStart);
        }
        return List.of(windowStart.minusDays(1), windowStart, windowEnd, windowEnd.plusDays(1));
    }

    /**
     * A landing record one day before a bound, which an inclusive window must reject.
     *
     * @param windowStart the inclusive lower bound
     * @return a builder for a record just outside the window
     */
    public static DailyTransactionBuilder dailyTransactionOneDayBefore(final LocalDate windowStart) {
        Objects.requireNonNull(windowStart, "windowStart");
        return dailyTransactionWithProcessingDate(windowStart.minusDays(1));
    }

    /**
     * A landing record one day after a bound, which an inclusive window must reject.
     *
     * @param windowEnd the inclusive upper bound
     * @return a builder for a record just outside the window
     */
    public static DailyTransactionBuilder dailyTransactionOneDayAfter(final LocalDate windowEnd) {
        Objects.requireNonNull(windowEnd, "windowEnd");
        return dailyTransactionWithProcessingDate(windowEnd.plusDays(1));
    }

    /**
     * A posted record one day before a bound, which an inclusive window must reject.
     *
     * @param windowStart the inclusive lower bound
     * @return a builder for a posted record just outside the window
     */
    public static TransactionBuilder transactionOneDayBefore(final LocalDate windowStart) {
        Objects.requireNonNull(windowStart, "windowStart");
        return transactionWithProcessingDate(windowStart.minusDays(1));
    }

    /**
     * A posted record one day after a bound, which an inclusive window must reject.
     *
     * @param windowEnd the inclusive upper bound
     * @return a builder for a posted record just outside the window
     */
    public static TransactionBuilder transactionOneDayAfter(final LocalDate windowEnd) {
        Objects.requireNonNull(windowEnd, "windowEnd");
        return transactionWithProcessingDate(windowEnd.plusDays(1));
    }
}
