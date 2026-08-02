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
package com.carddemo.util;

import java.math.BigDecimal;
import java.util.Objects;

import com.carddemo.domain.Account;

/**
 * Sole holder of the 300-byte account record layout and of the exact two-way mapping between that
 * record image and the {@link Account} entity.
 *
 * <p>Every offset is a named constant and the mapping is written by hand. That is a constraint, not
 * a preference: the module's reflection budget is zero, so no annotation-driven or bean-mapping
 * library may place a field, and a reviewer must be able to audit each offset against the copybook
 * without reading a method body. Layout authority is {@code app/cpy/CVACT01Y.cpy}, whose group item
 * declares twelve data items followed by a filler run. The 300-byte total is corroborated
 * independently twice: by the cluster that provisions the data set, which fixes an eleven-byte key
 * at offset zero {@code [app/jcl/ACCTFILE.jcl]}, and by the sequential reader, which splits the
 * same record into an eleven-byte identifier plus a 289-byte remainder
 * {@code [app/cbl/CBACT01C.cbl]}.
 *
 * <p><strong>The five monetary fields are not contiguous, and that is the single easiest mistake to
 * make in this layout.</strong> Three of them run consecutively, then the three ten-byte date
 * fields, then the remaining two. An implementation that walks five consecutive twelve-byte amounts
 * misreads every byte from the first date onward while still producing a plausible object. Each
 * offset is therefore its own constant and none is computed by advancing a running cursor, so the
 * discontinuity stays visible rather than hidden inside an accumulation.
 *
 * <p><strong>The amounts are zoned decimal, not packed.</strong> Each occupies twelve encoded bytes
 * - ten integer digits and two implied decimals - and carries its sign overpunched into the final
 * digit byte; there is no separate sign byte and no packed field anywhere in this record. All
 * conversion in both directions goes through {@link ZonedDecimalCodec}, which applies scale 2 and
 * truncates toward zero, matching a COBOL store into a two-decimal receiving field, because no
 * arithmetic statement in the estate specifies rounding. <strong>This class never calls
 * {@code setScale} and never names a rounding mode</strong>, which is what prevents a second,
 * inconsistent rounding policy from appearing in the module; no binary floating-point type appears
 * here either, an IEEE-754 approximation of a cent being exactly what the mandated construct
 * mapping forbids. One asymmetry follows: a negatively-signed all-zero image has no
 * {@link BigDecimal} counterpart, so it decodes to zero and re-emits with the positive sign. It is
 * observable only where every digit is zero - an ordinary negative amount whose cent digit happens
 * to be zero round-trips exactly - and a caller that must preserve the distinction byte for byte
 * reads the field through the codec's signed entry points, which exist for that purpose.
 *
 * <p>The copybook genuinely spells the seventh item without its {@code T}. The defect is preserved
 * where it is load bearing and corrected where it is not: the byte offset and length are reproduced
 * exactly so the image stays compatible, the geometry constants here mirror the misspelling so the
 * mapping stays findable by searching for the copybook's own name, and the Java property is spelled
 * correctly. Recorded as row 1 of the source anomaly register.
 *
 * <p>Three properties of the reference data are load bearing rather than cosmetic. The group
 * identifier is blank, and must round-trip as ten spaces - never {@code null}, never empty, never
 * trimmed - because a blank group identifier is what makes the disclosure-group lookup miss, which
 * in turn makes the interest program's default-group fallback the path the reference data actually
 * exercises; trimming it here would silently disable that path, and a padded key is a different key
 * from a trimmed one everywhere in this module. The ZIP begins with a letter rather than a digit,
 * so it is copied verbatim and never treated as a number. The active status is carried as a raw
 * one-character code: this class does not translate it, does not consult the status enumeration and
 * does not reject an unrecognised value, because that vocabulary belongs to the screen and service
 * layers.
 *
 * <p>The filler run carries no initialising clause, so no byte value is canonical; this mapper
 * emits spaces, matching the reference records. A fixture round-trip assertion therefore compares
 * only the mapped prefix - from zero up to but excluding {@value #MAPPED_PREFIX_LENGTH} - and not
 * the whole 300 bytes, because later bytes are not this mapper's to guarantee.
 *
 * <p>Deliberately absent: business validation of any kind, since a record the legacy system
 * accepted must map here without complaint and the only checks performed are geometric; arithmetic
 * of any kind, since every store truncates and the estate's arithmetic is therefore not
 * associative, so the two expressions whose evaluation order is contractual stay in the posting and
 * interest services; the optimistic-locking counter, which has no representation in the image and
 * belongs to the persistence provider; and any logger, file, clock or repository, whose absence is
 * what makes every method a pure function.
 *
 * <p>A record image whose encoded width is not exactly {@value #RECORD_LENGTH} raises
 * {@link IllegalArgumentException} naming the artefact, the expected width and the actual width,
 * and names an unstripped line terminator as the likely cause when the overshoot is exactly one
 * byte. Input is never silently padded, truncated, partially mapped or returned as {@code null}. No
 * exception type from this module's own package is used, deliberately: none of them models a caller
 * supplying the wrong number of bytes, which is a failure mode with no legacy antecedent because
 * the legacy records are fixed length by construction.
 *
 * <p>Stateless and thread safe: final, private constructor, every member static, no mutable static
 * state, and an encoding builder scoped to the single call that creates it. Traceability is carried
 * by citation only; no copybook, program or job-stream text is reproduced here.
 *
 * @see Account
 * @see ZonedDecimalCodec
 * @see FixedWidthFieldReader
 */
public final class AccountRecordMapper {

    public static final int RECORD_LENGTH = 300;

    public static final int KEY_LENGTH = 11;

    public static final int DATA_LENGTH = 289;

    /**
     * Number of leading bytes this mapper is the authority for: everything before the filler run. A
     * fixture round-trip compares this prefix only.
     */
    public static final int MAPPED_PREFIX_LENGTH = 122;

    public static final int ACCT_ID_OFFSET = 0;

    /** Leading zeros are significant, so the identifier is carried as text and never parsed. */
    public static final int ACCT_ID_LENGTH = 11;

    public static final int ACCT_ACTIVE_STATUS_OFFSET = 11;

    /** The status is carried as a raw code; nothing here translates or validates it. */
    public static final int ACCT_ACTIVE_STATUS_LENGTH = 1;

    public static final int ACCT_CURR_BAL_OFFSET = 12;

    public static final int ACCT_CURR_BAL_LENGTH = 12;

    public static final int ACCT_CREDIT_LIMIT_OFFSET = 24;

    public static final int ACCT_CREDIT_LIMIT_LENGTH = 12;

    public static final int ACCT_CASH_CREDIT_LIMIT_OFFSET = 36;

    public static final int ACCT_CASH_CREDIT_LIMIT_LENGTH = 12;

    public static final int ACCT_OPEN_DATE_OFFSET = 48;

    public static final int ACCT_OPEN_DATE_LENGTH = 10;

    /**
     * Offset of the expiry date, whose copybook name is genuinely misspelled. The geometry constants
     * mirror the misspelling so the mapping stays findable; the entity property is spelled correctly.
     */
    public static final int ACCT_EXPIRAION_DATE_OFFSET = 58;

    public static final int ACCT_EXPIRAION_DATE_LENGTH = 10;

    public static final int ACCT_REISSUE_DATE_OFFSET = 68;

    public static final int ACCT_REISSUE_DATE_LENGTH = 10;

    public static final int ACCT_CURR_CYC_CREDIT_OFFSET = 78;

    public static final int ACCT_CURR_CYC_CREDIT_LENGTH = 12;

    public static final int ACCT_CURR_CYC_DEBIT_OFFSET = 90;

    public static final int ACCT_CURR_CYC_DEBIT_LENGTH = 12;

    /**
     * Offset of the ZIP, which is alphanumeric rather than numeric: reference values begin with a
     * letter, so it is copied verbatim and never treated as a number.
     */
    public static final int ACCT_ADDR_ZIP_OFFSET = 102;

    public static final int ACCT_ADDR_ZIP_LENGTH = 10;

    public static final int ACCT_GROUP_ID_OFFSET = 112;

    /** Trailing spaces are significant here and are never trimmed. */
    public static final int ACCT_GROUP_ID_LENGTH = 10;

    public static final int FILLER_OFFSET = 122;

    /**
     * Byte length of the trailing filler run, which this mapper does not map:
     * {@value #MAPPED_PREFIX_LENGTH} + 178 = {@value #RECORD_LENGTH}.
     */
    public static final int FILLER_LENGTH = 178;

    /** Layout name carried into every diagnostic; it names both the copybook group and the copybook. */
    private static final String ARTEFACT = "ACCOUNT-RECORD (CVACT01Y)";

    private static final String FIELD_ACCT_ID = "ACCT-ID";

    private static final String FIELD_ACCT_ACTIVE_STATUS = "ACCT-ACTIVE-STATUS";

    private static final String FIELD_ACCT_CURR_BAL = "ACCT-CURR-BAL";

    private static final String FIELD_ACCT_CREDIT_LIMIT = "ACCT-CREDIT-LIMIT";

    private static final String FIELD_ACCT_CASH_CREDIT_LIMIT = "ACCT-CASH-CREDIT-LIMIT";

    private static final String FIELD_ACCT_OPEN_DATE = "ACCT-OPEN-DATE";

    /** Reproduced with the copybook's own misspelling so a diagnostic matches the copybook. */
    private static final String FIELD_ACCT_EXPIRAION_DATE = "ACCT-EXPIRAION-DATE";

    private static final String FIELD_ACCT_REISSUE_DATE = "ACCT-REISSUE-DATE";

    private static final String FIELD_ACCT_CURR_CYC_CREDIT = "ACCT-CURR-CYC-CREDIT";

    private static final String FIELD_ACCT_CURR_CYC_DEBIT = "ACCT-CURR-CYC-DEBIT";

    private static final String FIELD_ACCT_ADDR_ZIP = "ACCT-ADDR-ZIP";

    private static final String FIELD_ACCT_GROUP_ID = "ACCT-GROUP-ID";

    /** Not instantiable: a stateless mapper exposing only static members. */
    private AccountRecordMapper() {
    }

    /**
     * Maps a complete account record image, supplied as a string, onto a fully populated entity.
     *
     * <p>The encoded width is checked before a single field is sliced, so a mis-sized record is
     * reported rather than mapped from the wrong offsets. Character fields are copied verbatim -
     * untrimmed, not case folded, not normalised.
     *
     * @param  recordImage the whole record image, excluding any line terminator
     * @return a fully populated account, never partially mapped
     * @throws NullPointerException     if {@code recordImage} is {@code null}
     * @throws IllegalArgumentException if the encoded width is not exactly
     *                                  {@value #RECORD_LENGTH}, if a character cannot be
     *                                  represented in US-ASCII, or if a monetary field is not a
     *                                  valid zoned-decimal image
     */
    public static Account fromRecord(String recordImage) {
        Objects.requireNonNull(recordImage, "recordImage must not be null");
        requireRecordWidth(FixedWidthFieldReader.encodedLength(recordImage));
        return mapFrom(FixedWidthFieldReader.of(ARTEFACT, recordImage, RECORD_LENGTH));
    }

    /**
     * Maps a complete account record image, supplied as bytes, onto a fully populated entity.
     *
     * <p>Preferred when the caller already holds raw bytes, because it removes any need to choose a
     * charset. The array is only read: neither retained nor modified.
     *
     * @param  recordImage the whole record image as bytes, excluding any line terminator
     * @return a fully populated account, never partially mapped
     * @throws NullPointerException     if {@code recordImage} is {@code null}
     * @throws IllegalArgumentException if the length is not exactly {@value #RECORD_LENGTH}, if any
     *                                  byte is not 7-bit ASCII, or if a monetary field is not a
     *                                  valid zoned-decimal image
     */
    public static Account fromRecord(byte[] recordImage) {
        Objects.requireNonNull(recordImage, "recordImage must not be null");
        requireRecordWidth(recordImage.length);
        return mapFrom(FixedWidthFieldReader.of(ARTEFACT, recordImage, RECORD_LENGTH));
    }

    /**
     * Maps one account record held inside a larger byte buffer onto a fully populated entity.
     *
     * <p>The seam for a caller holding a whole newline-terminated fixed-width file in one buffer.
     * Such a file has a stride of {@value #RECORD_LENGTH} + 1, so record <em>i</em> starts at
     * {@code i * (RECORD_LENGTH + 1)}, which selects the record and leaves its terminator behind.
     * Stride arithmetic and file access stay with the caller. No whole-buffer width check applies,
     * because the buffer is not the record: the requested range must simply lie wholly inside it.
     *
     * @param  buffer the buffer containing the record, and possibly many others
     * @param  offset zero-based index at which the record starts
     * @return a fully populated account, never partially mapped
     * @throws NullPointerException     if {@code buffer} is {@code null}
     * @throws IllegalArgumentException if {@code offset} is negative, if the record range is not
     *                                  wholly inside {@code buffer}, if any byte in it is not
     *                                  7-bit ASCII, or if a monetary field is not a valid
     *                                  zoned-decimal image
     */
    public static Account fromRecord(byte[] buffer, int offset) {
        Objects.requireNonNull(buffer, "buffer must not be null");
        return mapFrom(FixedWidthFieldReader.of(ARTEFACT, buffer, offset, RECORD_LENGTH));
    }

    /**
     * Renders an account as its canonical {@value #RECORD_LENGTH}-byte record image, as a string
     * carrying no line terminator.
     *
     * <p>The exact inverse of {@link #fromRecord(String)} for the mapped fields, and the padding is
     * part of the contract: character fields are placed left-justified and space-padded, so a
     * nine-character value in a ten-byte field acquires one trailing space and a value that already
     * fills its field - including one that is all spaces - is placed unchanged; the identifier is
     * placed right-justified and zero-padded; each amount is encoded with its sign overpunched into
     * the final byte; the filler run is emitted as spaces. The width is guaranteed twice, by a
     * builder that allocates exactly {@value #RECORD_LENGTH} bytes and rejects any over-wide value,
     * and by an explicit check before the image is returned.
     *
     * @param  account the account to render; no mapped property may be {@code null}
     * @return the record image, exactly {@value #RECORD_LENGTH} encoded bytes wide
     * @throws NullPointerException     if {@code account} is {@code null}
     * @throws IllegalArgumentException if a mapped property is {@code null}, if a character value is
     *                                  wider than its field or cannot be represented in US-ASCII,
     *                                  or if an amount needs more digits than its field provides
     */
    public static String toRecord(Account account) {
        String image = imageOf(account).image();
        requireRecordWidth(FixedWidthFieldReader.encodedLength(image));
        return image;
    }

    /**
     * Renders an account as its canonical {@value #RECORD_LENGTH}-byte record image, as bytes.
     *
     * <p>Byte-for-byte identical to {@link #toRecord(Account)} and offered so a writer need not
     * choose a charset. The array is fresh and unshared, and carries no line terminator: record
     * separation belongs to the writer.
     *
     * @param  account the account to render; no mapped property may be {@code null}
     * @return a new array of exactly {@value #RECORD_LENGTH} US-ASCII bytes
     * @throws NullPointerException     if {@code account} is {@code null}
     * @throws IllegalArgumentException on exactly the same conditions as {@link #toRecord(Account)}
     */
    public static byte[] toRecordBytes(Account account) {
        byte[] image = imageOf(account).toByteArray();
        requireRecordWidth(image.length);
        return image;
    }

    /**
     * Places the mapped fields and the filler run, in declaration order, and completes the image.
     *
     * <p>Written as separate placements against named offset constants rather than one fluent
     * chain, so each field's offset and length are visible on the line that uses them and the
     * discontinuity in the monetary run stays legible in the body as well as in the constants.
     */
    private static FixedWidthFieldReader imageOf(Account account) {
        Objects.requireNonNull(account, "account must not be null");
        FixedWidthFieldReader.Builder image = FixedWidthFieldReader.builder(ARTEFACT, RECORD_LENGTH);
        image.putNumeric(FIELD_ACCT_ID, ACCT_ID_OFFSET, ACCT_ID_LENGTH,
                requireText(FIELD_ACCT_ID, "acctId", account.getAcctId()));
        image.putAlphanumeric(FIELD_ACCT_ACTIVE_STATUS, ACCT_ACTIVE_STATUS_OFFSET,
                ACCT_ACTIVE_STATUS_LENGTH, requireText(FIELD_ACCT_ACTIVE_STATUS,
                        "acctActiveStatus", account.getAcctActiveStatus()));
        putAmount(image, FIELD_ACCT_CURR_BAL, "acctCurrBal", ACCT_CURR_BAL_OFFSET,
                ACCT_CURR_BAL_LENGTH, account.getAcctCurrBal());
        putAmount(image, FIELD_ACCT_CREDIT_LIMIT, "acctCreditLimit", ACCT_CREDIT_LIMIT_OFFSET,
                ACCT_CREDIT_LIMIT_LENGTH, account.getAcctCreditLimit());
        putAmount(image, FIELD_ACCT_CASH_CREDIT_LIMIT, "acctCashCreditLimit",
                ACCT_CASH_CREDIT_LIMIT_OFFSET, ACCT_CASH_CREDIT_LIMIT_LENGTH,
                account.getAcctCashCreditLimit());
        image.putAlphanumeric(FIELD_ACCT_OPEN_DATE, ACCT_OPEN_DATE_OFFSET, ACCT_OPEN_DATE_LENGTH,
                requireText(FIELD_ACCT_OPEN_DATE, "acctOpenDate", account.getAcctOpenDate()));
        image.putAlphanumeric(FIELD_ACCT_EXPIRAION_DATE, ACCT_EXPIRAION_DATE_OFFSET,
                ACCT_EXPIRAION_DATE_LENGTH, requireText(FIELD_ACCT_EXPIRAION_DATE,
                        "acctExpirationDate", account.getAcctExpirationDate()));
        image.putAlphanumeric(FIELD_ACCT_REISSUE_DATE, ACCT_REISSUE_DATE_OFFSET,
                ACCT_REISSUE_DATE_LENGTH, requireText(FIELD_ACCT_REISSUE_DATE, "acctReissueDate",
                        account.getAcctReissueDate()));
        putAmount(image, FIELD_ACCT_CURR_CYC_CREDIT, "acctCurrCycCredit",
                ACCT_CURR_CYC_CREDIT_OFFSET, ACCT_CURR_CYC_CREDIT_LENGTH,
                account.getAcctCurrCycCredit());
        putAmount(image, FIELD_ACCT_CURR_CYC_DEBIT, "acctCurrCycDebit", ACCT_CURR_CYC_DEBIT_OFFSET,
                ACCT_CURR_CYC_DEBIT_LENGTH, account.getAcctCurrCycDebit());
        image.putAlphanumeric(FIELD_ACCT_ADDR_ZIP, ACCT_ADDR_ZIP_OFFSET, ACCT_ADDR_ZIP_LENGTH,
                requireText(FIELD_ACCT_ADDR_ZIP, "acctAddrZip", account.getAcctAddrZip()));
        image.putAlphanumeric(FIELD_ACCT_GROUP_ID, ACCT_GROUP_ID_OFFSET, ACCT_GROUP_ID_LENGTH,
                requireText(FIELD_ACCT_GROUP_ID, "acctGroupId", account.getAcctGroupId()));
        image.putSpaceFiller(FILLER_OFFSET, FILLER_LENGTH);
        return image.build();
    }

    /**
     * Reads the mapped fields at their declared offsets and builds the entity.
     *
     * <p>Populated through the entity's public all-argument constructor, whose parameter order is
     * the record-image order, because the no-argument constructor is protected for the persistence
     * provider and is not reachable from this package. The optimistic-locking counter is
     * deliberately not touched: it has no representation in the image.
     */
    private static Account mapFrom(FixedWidthFieldReader record) {
        return new Account(
                record.field(FIELD_ACCT_ID, ACCT_ID_OFFSET, ACCT_ID_LENGTH),
                record.field(FIELD_ACCT_ACTIVE_STATUS, ACCT_ACTIVE_STATUS_OFFSET,
                        ACCT_ACTIVE_STATUS_LENGTH),
                amountAt(record, FIELD_ACCT_CURR_BAL, ACCT_CURR_BAL_OFFSET, ACCT_CURR_BAL_LENGTH),
                amountAt(record, FIELD_ACCT_CREDIT_LIMIT, ACCT_CREDIT_LIMIT_OFFSET,
                        ACCT_CREDIT_LIMIT_LENGTH),
                amountAt(record, FIELD_ACCT_CASH_CREDIT_LIMIT, ACCT_CASH_CREDIT_LIMIT_OFFSET,
                        ACCT_CASH_CREDIT_LIMIT_LENGTH),
                record.field(FIELD_ACCT_OPEN_DATE, ACCT_OPEN_DATE_OFFSET, ACCT_OPEN_DATE_LENGTH),
                record.field(FIELD_ACCT_EXPIRAION_DATE, ACCT_EXPIRAION_DATE_OFFSET,
                        ACCT_EXPIRAION_DATE_LENGTH),
                record.field(FIELD_ACCT_REISSUE_DATE, ACCT_REISSUE_DATE_OFFSET,
                        ACCT_REISSUE_DATE_LENGTH),
                amountAt(record, FIELD_ACCT_CURR_CYC_CREDIT, ACCT_CURR_CYC_CREDIT_OFFSET,
                        ACCT_CURR_CYC_CREDIT_LENGTH),
                amountAt(record, FIELD_ACCT_CURR_CYC_DEBIT, ACCT_CURR_CYC_DEBIT_OFFSET,
                        ACCT_CURR_CYC_DEBIT_LENGTH),
                record.field(FIELD_ACCT_ADDR_ZIP, ACCT_ADDR_ZIP_OFFSET, ACCT_ADDR_ZIP_LENGTH),
                record.field(FIELD_ACCT_GROUP_ID, ACCT_GROUP_ID_OFFSET, ACCT_GROUP_ID_LENGTH));
    }

    /** Slices one zoned-decimal field and decodes it at the canonical monetary scale. */
    private static BigDecimal amountAt(FixedWidthFieldReader record, String fieldName, int offset,
            int length) {
        return ZonedDecimalCodec.decodeMonetary(record.field(fieldName, offset, length), length,
                fieldName);
    }

    /**
     * Encodes one amount and places it right-justified at its declared offset.
     *
     * <p>Numeric placement left-pads with ASCII zeros and leaves the overpunched sign in the final
     * byte. The codec has already produced exactly {@code length} bytes, so no padding occurs; the
     * mode is stated for safety if a narrower value ever arrives.
     */
    private static void putAmount(FixedWidthFieldReader.Builder image, String fieldName,
            String property, int offset, int length, BigDecimal value) {
        image.putNumeric(fieldName, offset, length, ZonedDecimalCodec.encodeMonetary(
                requireAmount(fieldName, property, value), length, fieldName));
    }

    /**
     * Verifies that a character property is present, and returns it verbatim - nothing is trimmed,
     * padded, case folded or defaulted, because the padding a value carries is part of the record.
     */
    private static String requireText(String fieldName, String property, String value) {
        if (value == null) {
            throw new IllegalArgumentException(ARTEFACT + " field '" + fieldName + "' (Account."
                    + property + ") must not be null: a fixed-width record has no representation"
                    + " for an absent value, so an unset character field is presented as spaces"
                    + " rather than as null");
        }
        return value;
    }

    /**
     * Verifies that a monetary property is present, and returns it unchanged.
     *
     * <p>The value's scale is neither inspected nor adjusted here. Scale policy belongs to
     * {@link ZonedDecimalCodec}, which brings every amount to scale 2 by truncating toward zero,
     * and concentrating that decision in one class is what keeps the module's rounding policy
     * single-valued.
     *
     * @param  fieldName legacy field name, used in the diagnostic
     * @param  property  the entity property name, used in the diagnostic
     * @param  value     the value to check
     * @return {@code value}, unchanged
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    private static BigDecimal requireAmount(String fieldName, String property, BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException(ARTEFACT + " field '" + fieldName + "' (Account."
                    + property + ") must not be null: a zoned-decimal field has no representation"
                    + " for an absent amount, so an unset amount is presented as zero rather than"
                    + " as null");
        }
        return value;
    }

    /**
     * Compares an encoded byte length with the width this layout declares.
     *
     * <p>Serves both directions, so the input precondition and the output postcondition report the
     * same message shape. The message names the artefact, the expected width and the actual encoded
     * byte length, which is the whole failure contract for a mis-sized record; when the overshoot is
     * exactly one byte it also names the overwhelmingly likely cause, since the sample file is
     * newline terminated and its stride is one byte greater than its record width.
     *
     * @param actualEncodedBytes the encoded byte length actually observed
     * @throws IllegalArgumentException if {@code actualEncodedBytes} is not exactly
     *                                 {@value #RECORD_LENGTH}
     */
    private static void requireRecordWidth(int actualEncodedBytes) {
        if (actualEncodedBytes == RECORD_LENGTH) {
            return;
        }
        String terminatorHint = actualEncodedBytes == RECORD_LENGTH + 1
                ? " (an overshoot of exactly one byte is almost always an unstripped 0x0A line"
                        + " terminator: the sample file's stride is 301 bytes, of which only the"
                        + " leading 300 are the record)"
                : "";
        throw new IllegalArgumentException(ARTEFACT + " record image must be exactly "
                + RECORD_LENGTH + " encoded bytes in US-ASCII, but the supplied image is "
                + actualEncodedBytes + " encoded bytes; fixed-width records are never padded or"
                + " truncated to fit" + terminatorHint);
    }
}
