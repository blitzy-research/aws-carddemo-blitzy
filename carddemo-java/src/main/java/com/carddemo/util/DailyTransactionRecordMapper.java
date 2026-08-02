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

import com.carddemo.domain.DailyTransaction;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Sole holder of the 350-byte daily-transaction record layout and of the exact two-way mapping
 * between that record image and the {@link DailyTransaction} entity.
 *
 * <p>This layout matters more than its ten siblings because the daily-transaction dataset is the
 * <em>primary</em> production-representative input to the batch pipeline: the posting run reads it,
 * and the end-to-end byte-parity gate is driven by it. A defect here is not contained - it
 * propagates into posted transactions, account balances, rejects and statements alike. Layout
 * authority is {@code app/cpy/CVTRA06Y.cpy}: thirteen data items totalling 330 bytes followed by a
 * twenty-byte filler run. Offsets are zero-based byte positions in the record image and lengths are
 * encoded byte counts, never character counts, and every offset is a named constant so a reviewer
 * can audit it against the copybook without reading a method body.
 *
 * <p><strong>This layout has no keyed-cluster definition, and that is not an omission.</strong>
 * Alone among the estate's record layouts it is provisioned as a sequential dataset rather than as a
 * keyed cluster, so there is no cluster-declared key offset or record size to corroborate the width
 * against - the copybook is the only authority, and the width arithmetic is therefore derived in
 * code: {@link #MAPPED_DATA_LENGTH} plus {@link #FILLER_LENGTH} equals {@link #RECORD_LENGTH}, and a
 * static initialiser re-checks the field offsets for contiguity so a mis-typed offset cannot survive
 * a single execution.
 *
 * <p><strong>Two entities share one geometry, and there is deliberately no shared base class.</strong>
 * This layout is field-for-field parallel to the posted-transaction layout, differing only in field
 * name prefix. The two are nonetheless kept wholly separate: they are not merged, neither subclasses
 * the other, and no shared abstract base is extracted. They are distinct datasets with distinct
 * lifecycles, and a shared supertype would make it possible to hand one to the other's mapper, or to
 * "promote" one to the other by a cast rather than by the explicit service-layer step the legacy
 * program performs.
 *
 * <p><strong>The amount is zoned decimal in eleven encoded bytes</strong> - one ASCII byte per digit,
 * with no separate sign byte and no packed representation - and its sign is overpunched into the
 * final digit byte, which encodes a digit and a sign together. All decoding and encoding of that
 * field goes through {@link ZonedDecimalCodec}, the module's single point of decimal truth.
 * <strong>Truncation is mandatory and {@code HALF_EVEN} and {@code HALF_UP} are forbidden:</strong>
 * the codec applies scale 2 with truncation toward zero because no arithmetic statement anywhere in
 * the estate specifies rounding, and a COBOL store without a rounding clause truncates. This class
 * never calls {@code setScale} and never names a rounding mode, which is what keeps the module's
 * rounding policy single-valued; no binary floating-point type appears here either. One asymmetry
 * follows: a {@link BigDecimal} cannot carry a negative zero, so a negatively-signed all-zero image
 * decodes to zero and re-emits with the positive sign. It is observable only where every digit is
 * zero, and a caller needing byte-exact preservation reads the field through the codec's signed
 * entry points.
 *
 * <p>Four field-level contracts must survive untouched, because each looks like something to tidy
 * up and none is. <strong>A 26-space processing timestamp is legitimate</strong>: an unposted record
 * carries a blank processing timestamp, so the value must round-trip as 26 spaces rather than as
 * {@code null}, an empty string or a defaulted instant. <strong>Both timestamps stay
 * {@link String}s</strong> - neither is converted to a temporal type, because a blank value has no
 * temporal counterpart and parsing would either fail or invent one. <strong>Numeric-looking
 * identifiers stay {@link String}s</strong>, since leading zeros are significant and an identifier
 * is not a number. <strong>The merchant postal code is free-form and never numeric</strong>, the
 * reference data carrying values that are not parseable as numbers at all.
 *
 * <p>The filler run is emitted as spaces. Filler with no initialising clause is uninitialised, so no
 * byte value is canonical, and the estate's own fixtures disagree about it. <strong>Every fixture
 * round-trip assertion for this layout therefore compares only the mapped data prefix - from zero up
 * to but excluding {@value #MAPPED_DATA_LENGTH} - and never the whole record</strong>, because bytes
 * beyond that point are not this mapper's to guarantee. Note also that a fixture line is one byte
 * shorter than the file's stride, the difference being the record separator, so a caller reading
 * lines must exclude it: the separator is never record content.
 *
 * <p>The reference input's composition - point-of-sale purchases alongside operator-originated
 * returns - is what makes both signed directions of the posting computation reachable from seeded
 * data, and every record in it carries the same processing date, so any date-window filtering must
 * be exercised by a separately constructed fixture rather than by this one.
 *
 * <p>Deliberately absent, and each absence is a boundary rather than an oversight: validation and
 * reject handling of any kind, since the reject reason codes and the 430-byte reject record belong
 * to the posting service; arithmetic of any kind, since the over-limit basis is evaluated strictly
 * left to right in that same service and every store truncates, so re-ordering would move the
 * truncation point and change the cent; posting, balance update and cross-reference resolution; date
 * or timestamp parsing; enumeration translation, the transaction source staying a raw value;
 * comparator, sort or ordering, which the job that needs it owns; any mapping between this entity
 * and the posted-transaction entity, since promoting a daily transaction is an explicit service-layer
 * step; the optimistic-locking counter, which this entity does not declare; logging, this package not
 * being among the module's configured logger names; and persistence of any kind.
 *
 * <p>One consumer of this layout is a complete program that no job member, procedure or resource
 * definition invokes - recorded as an anomaly - which becomes a job that is defined and exercised by
 * tests but excluded from the default pipeline.
 *
 * <p>Stateless and thread safe: final, private constructor, every member static and immutable, no
 * mutable static state. Every slice and placement goes through {@link FixedWidthFieldReader}, so
 * there is no {@code substring} call here, no annotation-driven mapping, no reflection and no
 * generated code - the module's reflection budget is zero, which is why this class exists at all.
 * Traceability is carried by citation only.
 *
 * @see DailyTransaction
 * @see ZonedDecimalCodec
 * @see FixedWidthFieldReader
 */
public final class DailyTransactionRecordMapper {

    /** Layout name carried into every diagnostic; it names both the record group and the copybook. */
    public static final String ARTEFACT = "DALYTRAN-RECORD (CVTRA06Y)";

    /** Full record width: the mapped data prefix plus the trailing filler run. */
    public static final int RECORD_LENGTH = 350;

    /**
     * Width of the mapped data prefix, and <strong>the exact bound for a fixture round-trip
     * comparison</strong>: compare from zero up to but excluding this value and nothing beyond it,
     * because the filler bytes are not uniform across the estate's fixtures.
     */
    public static final int MAPPED_DATA_LENGTH = 330;

    public static final int DALYTRAN_ID_OFFSET = 0;

    public static final int DALYTRAN_ID_LENGTH = 16;

    public static final int DALYTRAN_TYPE_CD_OFFSET = 16;

    public static final int DALYTRAN_TYPE_CD_LENGTH = 2;

    public static final int DALYTRAN_CAT_CD_OFFSET = 18;

    public static final int DALYTRAN_CAT_CD_LENGTH = 4;

    public static final int DALYTRAN_SOURCE_OFFSET = 22;

    public static final int DALYTRAN_SOURCE_LENGTH = 10;

    public static final int DALYTRAN_DESC_OFFSET = 32;

    public static final int DALYTRAN_DESC_LENGTH = 100;

    /** Offset of the amount, the only numeric-valued field in this layout. */
    public static final int DALYTRAN_AMT_OFFSET = 132;

    /**
     * Encoded width of the amount, taken from {@link ZonedDecimalCodec} rather than written as a literal
     * so the codec and the layout cannot disagree. The final byte carries the overpunched sign.
     */
    public static final int DALYTRAN_AMT_LENGTH = ZonedDecimalCodec.DAILY_TRANSACTION_AMOUNT_WIDTH;

    public static final int DALYTRAN_MERCHANT_ID_OFFSET = 143;

    public static final int DALYTRAN_MERCHANT_ID_LENGTH = 9;

    public static final int DALYTRAN_MERCHANT_NAME_OFFSET = 152;

    public static final int DALYTRAN_MERCHANT_NAME_LENGTH = 50;

    public static final int DALYTRAN_MERCHANT_CITY_OFFSET = 202;

    public static final int DALYTRAN_MERCHANT_CITY_LENGTH = 50;

    public static final int DALYTRAN_MERCHANT_ZIP_OFFSET = 252;

    /** Width of the merchant postal code, which is free-form and never treated as a number. */
    public static final int DALYTRAN_MERCHANT_ZIP_LENGTH = 10;

    /**
     * Offset of the card number. The estate's external sort specifications address this field by
     * position, so the offset is contractual and not merely internal.
     */
    public static final int DALYTRAN_CARD_NUM_OFFSET = 262;

    public static final int DALYTRAN_CARD_NUM_LENGTH = 16;

    public static final int DALYTRAN_ORIG_TS_OFFSET = 278;

    public static final int DALYTRAN_ORIG_TS_LENGTH = 26;

    /**
     * Offset of the processing timestamp, which the batch alternate index and a sort specification both
     * address by position.
     */
    public static final int DALYTRAN_PROC_TS_OFFSET = 304;

    /** Width of the processing timestamp; an all-space value is legitimate and must survive. */
    public static final int DALYTRAN_PROC_TS_LENGTH = 26;

    /** Offset at which the unmapped trailing filler run begins. */
    public static final int FILLER_OFFSET = 330;

    public static final int FILLER_LENGTH = 20;

    /**
     * Byte this mapper writes across the filler run. Filler with no initialising clause is
     * uninitialised, so no value is canonical; space is the module-wide default.
     */
    public static final char FILLER_CHARACTER = ' ';

    private static final String DALYTRAN_ID = "DALYTRAN-ID";

    private static final String DALYTRAN_TYPE_CD = "DALYTRAN-TYPE-CD";

    private static final String DALYTRAN_CAT_CD = "DALYTRAN-CAT-CD";

    private static final String DALYTRAN_SOURCE = "DALYTRAN-SOURCE";

    private static final String DALYTRAN_DESC = "DALYTRAN-DESC";

    private static final String DALYTRAN_AMT = "DALYTRAN-AMT";

    private static final String DALYTRAN_MERCHANT_ID = "DALYTRAN-MERCHANT-ID";

    private static final String DALYTRAN_MERCHANT_NAME = "DALYTRAN-MERCHANT-NAME";

    private static final String DALYTRAN_MERCHANT_CITY = "DALYTRAN-MERCHANT-CITY";

    private static final String DALYTRAN_MERCHANT_ZIP = "DALYTRAN-MERCHANT-ZIP";

    private static final String DALYTRAN_CARD_NUM = "DALYTRAN-CARD-NUM";

    private static final String DALYTRAN_ORIG_TS = "DALYTRAN-ORIG-TS";

    private static final String DALYTRAN_PROC_TS = "DALYTRAN-PROC-TS";

    /**
     * Verifies the declared geometry once, at class initialisation: that the mapped fields are
     * contiguous from zero with no gap or overlap, and that they sum to the declared prefix width. A
     * mis-typed offset therefore fails on first use rather than producing a plausible object.
     */
    static {
        requireContiguous(DALYTRAN_ID, DALYTRAN_ID_OFFSET, 0);
        requireContiguous(DALYTRAN_TYPE_CD, DALYTRAN_TYPE_CD_OFFSET,
                DALYTRAN_ID_OFFSET + DALYTRAN_ID_LENGTH);
        requireContiguous(DALYTRAN_CAT_CD, DALYTRAN_CAT_CD_OFFSET,
                DALYTRAN_TYPE_CD_OFFSET + DALYTRAN_TYPE_CD_LENGTH);
        requireContiguous(DALYTRAN_SOURCE, DALYTRAN_SOURCE_OFFSET,
                DALYTRAN_CAT_CD_OFFSET + DALYTRAN_CAT_CD_LENGTH);
        requireContiguous(DALYTRAN_DESC, DALYTRAN_DESC_OFFSET,
                DALYTRAN_SOURCE_OFFSET + DALYTRAN_SOURCE_LENGTH);
        requireContiguous(DALYTRAN_AMT, DALYTRAN_AMT_OFFSET,
                DALYTRAN_DESC_OFFSET + DALYTRAN_DESC_LENGTH);
        requireContiguous(DALYTRAN_MERCHANT_ID, DALYTRAN_MERCHANT_ID_OFFSET,
                DALYTRAN_AMT_OFFSET + DALYTRAN_AMT_LENGTH);
        requireContiguous(DALYTRAN_MERCHANT_NAME, DALYTRAN_MERCHANT_NAME_OFFSET,
                DALYTRAN_MERCHANT_ID_OFFSET + DALYTRAN_MERCHANT_ID_LENGTH);
        requireContiguous(DALYTRAN_MERCHANT_CITY, DALYTRAN_MERCHANT_CITY_OFFSET,
                DALYTRAN_MERCHANT_NAME_OFFSET + DALYTRAN_MERCHANT_NAME_LENGTH);
        requireContiguous(DALYTRAN_MERCHANT_ZIP, DALYTRAN_MERCHANT_ZIP_OFFSET,
                DALYTRAN_MERCHANT_CITY_OFFSET + DALYTRAN_MERCHANT_CITY_LENGTH);
        requireContiguous(DALYTRAN_CARD_NUM, DALYTRAN_CARD_NUM_OFFSET,
                DALYTRAN_MERCHANT_ZIP_OFFSET + DALYTRAN_MERCHANT_ZIP_LENGTH);
        requireContiguous(DALYTRAN_ORIG_TS, DALYTRAN_ORIG_TS_OFFSET,
                DALYTRAN_CARD_NUM_OFFSET + DALYTRAN_CARD_NUM_LENGTH);
        requireContiguous(DALYTRAN_PROC_TS, DALYTRAN_PROC_TS_OFFSET,
                DALYTRAN_ORIG_TS_OFFSET + DALYTRAN_ORIG_TS_LENGTH);
        requireContiguous("FILLER", FILLER_OFFSET,
                DALYTRAN_PROC_TS_OFFSET + DALYTRAN_PROC_TS_LENGTH);
        requireSum("mapped data prefix", MAPPED_DATA_LENGTH,
                DALYTRAN_PROC_TS_OFFSET + DALYTRAN_PROC_TS_LENGTH);
        requireSum("record image", RECORD_LENGTH, MAPPED_DATA_LENGTH + FILLER_LENGTH);
    }

    /** Not instantiable: a stateless mapper exposing only static members. */
    private DailyTransactionRecordMapper() {
    }

    /**
     * Maps a complete record image, supplied as a string, onto a fully populated entity.
     *
     * <p>The image must be exactly {@value #RECORD_LENGTH} encoded bytes and must exclude any line
     * terminator: a fixture line is one byte shorter than the file's stride, so a caller reading
     * lines must drop the separator, which is never record content. The encoded width is checked
     * before a single field is sliced. Character fields are copied verbatim - untrimmed, not case
     * folded, not normalised.
     *
     * @param  recordImage the whole record image, excluding any line terminator
     * @return a fully populated entity, never partially mapped
     * @throws NullPointerException     if {@code recordImage} is {@code null}
     * @throws IllegalArgumentException if the encoded width is not exactly
     *                                  {@value #RECORD_LENGTH}, if a character cannot be
     *                                  represented in US-ASCII, or if the amount is not a valid
     *                                  zoned-decimal image
     */
    public static DailyTransaction fromRecord(String recordImage) {
        Objects.requireNonNull(recordImage, ARTEFACT + " record image must not be null");
        // Measured through the reader's own encoded-length operation, never through String.length(),
        // because a character count is not a width authority. The reader re-asserts the same
        // invariant when it takes ownership of the image; that repetition is deliberate, since the
        // reader's invariant must hold however it was constructed.
        requireRecordWidth(FixedWidthFieldReader.encodedLength(recordImage));
        return fromReader(FixedWidthFieldReader.of(ARTEFACT, recordImage, RECORD_LENGTH));
    }

    /**
     * Maps a complete record image, supplied as bytes, onto a fully populated entity.
     *
     * <p>Preferred when the caller already holds raw bytes, because it removes any need to choose a
     * charset. The array is only read: neither retained nor modified.
     *
     * @param  recordImage the whole record image as bytes, excluding any line terminator
     * @return a fully populated entity, never partially mapped
     * @throws NullPointerException     if {@code recordImage} is {@code null}
     * @throws IllegalArgumentException if the length is not exactly {@value #RECORD_LENGTH}, if any
     *                                  byte is not 7-bit ASCII, or if the amount is not a valid
     *                                  zoned-decimal image
     */
    public static DailyTransaction fromRecord(byte[] recordImage) {
        Objects.requireNonNull(recordImage, ARTEFACT + " record image must not be null");
        requireRecordWidth(recordImage.length);
        return fromReader(FixedWidthFieldReader.of(ARTEFACT, recordImage, RECORD_LENGTH));
    }

    /**
     * Maps one record held inside a larger byte buffer onto a fully populated entity.
     *
     * <p>The seam for a batch reader holding a whole newline-terminated file in one buffer. Such a
     * file has a stride one greater than the record width, so record <em>i</em> starts at
     * {@code i * (RECORD_LENGTH + 1)}, which selects the record and leaves its separator behind;
     * stride arithmetic and file access stay with the caller. The remaining geometry check - that
     * the range lies wholly inside the buffer - is made inside {@link FixedWidthFieldReader}.
     *
     * @param  buffer the buffer containing the record, and possibly many others
     * @param  offset zero-based index at which the record starts
     * @return a fully populated entity, never partially mapped
     * @throws NullPointerException     if {@code buffer} is {@code null}
     * @throws IllegalArgumentException if {@code offset} is negative, if the record range is not
     *                                  wholly inside {@code buffer}, if any byte in it is not
     *                                  7-bit ASCII, or if the amount is not a valid zoned-decimal
     *                                  image
     */
    public static DailyTransaction fromRecord(byte[] buffer, int from) {
        Objects.requireNonNull(buffer, ARTEFACT + " record buffer must not be null");
        // The range check belongs to the reader, which reports whether the fault was a negative index
        // or a range overrunning the buffer. Re-checking the width here would be wrong rather than
        // merely redundant: the buffer is legitimately longer than one record.
        return fromReader(FixedWidthFieldReader.of(ARTEFACT, buffer, from, RECORD_LENGTH));
    }

    /**
     * Renders an entity as its canonical {@value #RECORD_LENGTH}-byte record image, as a string
     * carrying no line terminator.
     *
     * <p>The exact inverse of {@link #fromRecord(String)} for the mapped fields, and the padding is
     * part of the contract: character fields are placed left-justified and space-padded, so a value
     * that already fills its field - including one that is all spaces - is placed unchanged; the
     * amount is encoded with its sign overpunched into the final byte; the filler run is emitted as
     * {@link #FILLER_CHARACTER}. <strong>Compare only the mapped prefix in a fixture assertion</strong>,
     * since the filler byte is not uniform across the estate's fixtures.
     *
     * @param  record the entity to render; no mapped property may be {@code null}
     * @return the record image, exactly {@value #RECORD_LENGTH} encoded bytes wide
     * @throws NullPointerException     if {@code record} or any mapped property is {@code null}
     * @throws IllegalArgumentException if a character value is wider than its field or cannot be
     *                                  represented in US-ASCII, or if the amount needs more digits
     *                                  than its field provides
     */
    public static String toRecord(DailyTransaction record) {
        return toReader(record).image();
    }

    /**
     * Renders an entity as its canonical {@value #RECORD_LENGTH}-byte record image, as bytes.
     *
     * <p>Byte-for-byte identical to {@link #toRecord(DailyTransaction)} and offered so a writer need
     * not choose a charset. The array is fresh and unshared and carries no line terminator: record
     * separation belongs to the writer.
     *
     * @param  record the entity to render; no mapped property may be {@code null}
     * @return a new array of exactly {@value #RECORD_LENGTH} US-ASCII bytes
     * @throws NullPointerException     if {@code record} or any mapped property is {@code null}
     * @throws IllegalArgumentException on exactly the same conditions as
     *                                  {@link #toRecord(DailyTransaction)}
     */
    public static byte[] toRecordBytes(DailyTransaction record) {
        return toReader(record).toByteArray();
    }

    /**
     * Reads the mapped fields at their declared offsets and builds the entity.
     *
     * <p>Populated through the entity's all-argument constructor, whose parameter order is the
     * record-image order, because the no-argument constructor is reserved for the persistence
     * provider. Timestamps and identifiers are carried across as text exactly as read.
     */
    private static DailyTransaction fromReader(FixedWidthFieldReader reader) {
        return new DailyTransaction(
                reader.field(DALYTRAN_ID, DALYTRAN_ID_OFFSET, DALYTRAN_ID_LENGTH),
                reader.field(DALYTRAN_TYPE_CD, DALYTRAN_TYPE_CD_OFFSET, DALYTRAN_TYPE_CD_LENGTH),
                reader.field(DALYTRAN_CAT_CD, DALYTRAN_CAT_CD_OFFSET, DALYTRAN_CAT_CD_LENGTH),
                reader.field(DALYTRAN_SOURCE, DALYTRAN_SOURCE_OFFSET, DALYTRAN_SOURCE_LENGTH),
                reader.field(DALYTRAN_DESC, DALYTRAN_DESC_OFFSET, DALYTRAN_DESC_LENGTH),
                decodeAmount(reader),
                reader.field(DALYTRAN_MERCHANT_ID, DALYTRAN_MERCHANT_ID_OFFSET,
                        DALYTRAN_MERCHANT_ID_LENGTH),
                reader.field(DALYTRAN_MERCHANT_NAME, DALYTRAN_MERCHANT_NAME_OFFSET,
                        DALYTRAN_MERCHANT_NAME_LENGTH),
                reader.field(DALYTRAN_MERCHANT_CITY, DALYTRAN_MERCHANT_CITY_OFFSET,
                        DALYTRAN_MERCHANT_CITY_LENGTH),
                reader.field(DALYTRAN_MERCHANT_ZIP, DALYTRAN_MERCHANT_ZIP_OFFSET,
                        DALYTRAN_MERCHANT_ZIP_LENGTH),
                reader.field(DALYTRAN_CARD_NUM, DALYTRAN_CARD_NUM_OFFSET, DALYTRAN_CARD_NUM_LENGTH),
                reader.field(DALYTRAN_ORIG_TS, DALYTRAN_ORIG_TS_OFFSET, DALYTRAN_ORIG_TS_LENGTH),
                reader.field(DALYTRAN_PROC_TS, DALYTRAN_PROC_TS_OFFSET, DALYTRAN_PROC_TS_LENGTH));
    }

    /**
     * Places the mapped fields and the filler run, in declaration order, and completes the image.
     *
     * <p>The placements deliberately add up to the full record width, because that is what makes a
     * missing field visible during review: the builder rejects any placement falling outside the
     * record, so an offset that does not add up cannot survive a single execution.
     */
    private static FixedWidthFieldReader toReader(DailyTransaction record) {
        Objects.requireNonNull(record, ARTEFACT + " source entity must not be null");
        return FixedWidthFieldReader.builder(ARTEFACT, RECORD_LENGTH)
                .putAlphanumeric(DALYTRAN_ID, DALYTRAN_ID_OFFSET, DALYTRAN_ID_LENGTH,
                        requirePresent(record.getDalytranId(), DALYTRAN_ID))
                .putAlphanumeric(DALYTRAN_TYPE_CD, DALYTRAN_TYPE_CD_OFFSET, DALYTRAN_TYPE_CD_LENGTH,
                        requirePresent(record.getDalytranTypeCd(), DALYTRAN_TYPE_CD))
                .putNumeric(DALYTRAN_CAT_CD, DALYTRAN_CAT_CD_OFFSET, DALYTRAN_CAT_CD_LENGTH,
                        requirePresent(record.getDalytranCatCd(), DALYTRAN_CAT_CD))
                .putAlphanumeric(DALYTRAN_SOURCE, DALYTRAN_SOURCE_OFFSET, DALYTRAN_SOURCE_LENGTH,
                        requirePresent(record.getDalytranSource(), DALYTRAN_SOURCE))
                .putAlphanumeric(DALYTRAN_DESC, DALYTRAN_DESC_OFFSET, DALYTRAN_DESC_LENGTH,
                        requirePresent(record.getDalytranDesc(), DALYTRAN_DESC))
                .putNumeric(DALYTRAN_AMT, DALYTRAN_AMT_OFFSET, DALYTRAN_AMT_LENGTH,
                        encodeAmount(record.getDalytranAmt()))
                .putNumeric(DALYTRAN_MERCHANT_ID, DALYTRAN_MERCHANT_ID_OFFSET,
                        DALYTRAN_MERCHANT_ID_LENGTH,
                        requirePresent(record.getDalytranMerchantId(), DALYTRAN_MERCHANT_ID))
                .putAlphanumeric(DALYTRAN_MERCHANT_NAME, DALYTRAN_MERCHANT_NAME_OFFSET,
                        DALYTRAN_MERCHANT_NAME_LENGTH,
                        requirePresent(record.getDalytranMerchantName(), DALYTRAN_MERCHANT_NAME))
                .putAlphanumeric(DALYTRAN_MERCHANT_CITY, DALYTRAN_MERCHANT_CITY_OFFSET,
                        DALYTRAN_MERCHANT_CITY_LENGTH,
                        requirePresent(record.getDalytranMerchantCity(), DALYTRAN_MERCHANT_CITY))
                .putAlphanumeric(DALYTRAN_MERCHANT_ZIP, DALYTRAN_MERCHANT_ZIP_OFFSET,
                        DALYTRAN_MERCHANT_ZIP_LENGTH,
                        requirePresent(record.getDalytranMerchantZip(), DALYTRAN_MERCHANT_ZIP))
                .putAlphanumeric(DALYTRAN_CARD_NUM, DALYTRAN_CARD_NUM_OFFSET,
                        DALYTRAN_CARD_NUM_LENGTH,
                        requirePresent(record.getDalytranCardNum(), DALYTRAN_CARD_NUM))
                .putAlphanumeric(DALYTRAN_ORIG_TS, DALYTRAN_ORIG_TS_OFFSET, DALYTRAN_ORIG_TS_LENGTH,
                        requirePresent(record.getDalytranOrigTs(), DALYTRAN_ORIG_TS))
                .putAlphanumeric(DALYTRAN_PROC_TS, DALYTRAN_PROC_TS_OFFSET, DALYTRAN_PROC_TS_LENGTH,
                        requirePresent(record.getDalytranProcTs(), DALYTRAN_PROC_TS))
                .putFiller(FILLER_OFFSET, FILLER_LENGTH, FILLER_CHARACTER)
                .build();
    }

    /**
     * Slices the amount and decodes it at the canonical monetary scale.
     *
     * <p>An exception raised by the codec is deliberately not re-wrapped, because its diagnostic
     * already names the offending byte and re-wrapping would hide it.
     */
    private static BigDecimal decodeAmount(FixedWidthFieldReader reader) {
        return ZonedDecimalCodec.decodeMonetary(
                reader.field(DALYTRAN_AMT, DALYTRAN_AMT_OFFSET, DALYTRAN_AMT_LENGTH),
                DALYTRAN_AMT_LENGTH, DALYTRAN_AMT);
    }

    /** Encodes the amount into its declared width, sign overpunched into the final byte. */
    private static String encodeAmount(BigDecimal amount) {
        return ZonedDecimalCodec.encodeMonetary(requirePresent(amount, DALYTRAN_AMT),
                DALYTRAN_AMT_LENGTH, DALYTRAN_AMT);
    }

    /**
     * Rejects an image whose encoded byte length is not exactly the declared record width.
     *
     * <p>A Java-only defensive guard with no legacy antecedent: the legacy records are fixed length
     * by construction, so the programs never had a wrong-length record to handle. When the overshoot
     * is exactly one byte the message names an unstripped separator as the likely cause.
     */
    private static void requireRecordWidth(int actualEncodedLength) {
        if (actualEncodedLength == RECORD_LENGTH) {
            return;
        }
        StringBuilder message = new StringBuilder()
                .append(ARTEFACT)
                .append(" record image must be exactly ")
                .append(RECORD_LENGTH)
                .append(" encoded bytes in US-ASCII, but the supplied image is ")
                .append(actualEncodedLength)
                .append(" encoded bytes; a fixed-width record is never padded or truncated to fit");
        if (actualEncodedLength == RECORD_LENGTH + 1) {
            message.append(" (an overshoot of exactly one byte is usually an unstripped 0x0A line ")
                    .append("terminator: the sample file's stride is 351 because the terminator ")
                    .append("separates records and is never record content)");
        }
        throw new IllegalArgumentException(message.toString());
    }

    /** Rejects an absent property on the encoding path, naming the field rather than the value. */
    private static <T> T requirePresent(T value, String fieldName) {
        return Objects.requireNonNull(value, ARTEFACT + " field '" + fieldName
                + "' must be present: a fixed-width record has no concept of an absent field, and"
                + " emitting spaces for one would produce a record of the right width and the wrong"
                + " content");
    }

    /** Verifies that a field begins exactly where the previous one ended, with no gap or overlap. */
    private static void requireContiguous(String fieldName, int declaredOffset, int computedOffset) {
        if (declaredOffset != computedOffset) {
            throw new IllegalStateException(ARTEFACT + " layout is inconsistent: field '" + fieldName
                    + "' declares offset " + declaredOffset + " but the widths of the fields before"
                    + " it sum to " + computedOffset);
        }
    }

    /** Verifies that a set of declared widths sums to the width it is required to fill. */
    private static void requireSum(String subject, int declared, int computed) {
        if (declared != computed) {
            throw new IllegalStateException(ARTEFACT + " layout is inconsistent: the " + subject
                    + " is published as " + declared + " encoded bytes but its parts sum to "
                    + computed);
        }
    }
}
