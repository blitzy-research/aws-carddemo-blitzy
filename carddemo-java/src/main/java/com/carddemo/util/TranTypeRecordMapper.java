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

import com.carddemo.domain.TransactionType;
import java.util.Objects;

/**
 * Sole holder of the 60-byte transaction-type reference layout and of the exact two-way mapping
 * between that record image and the {@link TransactionType} entity.
 *
 * <p>Layout authority is {@code app/cpy/CVTRA03Y.cpy}: a two-character type code that is also the
 * key, a fifty-character description, and an eight-byte trailing filler run. Offsets are zero-based
 * byte positions in the record image and lengths are encoded byte counts, never character counts.
 * The key is a bare field rather than a composite, so this layout has no identifier class of its own,
 * unlike the three layouts whose keys have several parts.
 *
 * <p><strong>Two different 60-byte layouts coexist in this estate, and a width check cannot tell
 * them apart.</strong> The transaction-<em>category</em> layout is also sixty bytes wide, but its
 * fields sit at different offsets, so a category record handed to this mapper will pass the width
 * check and produce nonsense rather than fail. <strong>The width constant declared here must never be
 * shared with that layout's mapper</strong>: a shared width would read as permission to share an
 * offset, and the offsets differ. Each layout therefore declares its own geometry even where the
 * totals coincide.
 *
 * <p>The filler run is emitted as spaces, the module-wide default for filler that carries no
 * initialising clause and therefore has no canonical value. The shipped fixture for this layout holds
 * ASCII zero there instead, so <strong>a whole-record comparison against that fixture will fail, and
 * will fail on the filler bytes alone</strong>. Every fixture round-trip assertion for this layout
 * compares only the mapped data prefix, from zero up to but excluding
 * {@link #TRAN_TYPE_MAPPED_LENGTH}.
 *
 * <p>The description is fifty bytes here and fifteen in the transaction report line. That narrowing
 * is the report formatter's, not this mapper's: the stored value is carried at its full width and is
 * never pre-truncated for a downstream presentation.
 *
 * <p>The type code stays text and nothing is normalised - not trimmed, not case folded, not padded
 * beyond the fixed-width placement itself - because a value the legacy system accepted must survive
 * this mapper rather than be reshaped by it.
 *
 * <p>Deliberately absent: any enumeration of the type codes, since they are reference <em>data</em>
 * loaded by a seed migration and freezing a data table into code would make adding a row a code
 * change; content validation of any kind, the only guard being geometric; decimal handling, both
 * fields being alphanumeric so the layout has no numeric value field at all; the optimistic-locking
 * counter, which this reference entity does not declare; slicing of its own, every read and placement
 * going through {@link FixedWidthFieldReader}; and logging, persistence, clock, environment and
 * randomness, whose absence is what makes the mapping a pure function of its argument.
 *
 * <p>An image whose encoded length is not exactly {@link #TRAN_TYPE_RECORD_LENGTH} raises
 * {@link IllegalArgumentException} naming the artefact, the expected width and the actual length;
 * {@code null} arguments raise {@link NullPointerException}. Input is never silently padded,
 * truncated, partially mapped or returned as {@code null}.
 *
 * <p>Stateless and thread safe: final, private constructor, every member static and immutable.
 * Traceability is carried by citation only.
 *
 * @see TransactionType
 * @see FixedWidthFieldReader
 */
public final class TranTypeRecordMapper {

    /**
     * Full record width. The transaction-category layout is also this wide but places its fields
     * differently, so this constant must never be shared with that layout's mapper: a shared width would
     * read as permission to share an offset.
     */
    public static final int TRAN_TYPE_RECORD_LENGTH = 60;

    /**
     * Width of the mapped data prefix, and the exact bound for a fixture round-trip comparison: compare
     * from zero up to but excluding this value and nothing beyond it.
     */
    public static final int TRAN_TYPE_MAPPED_LENGTH = 52;

    public static final int TRAN_TYPE_CODE_OFFSET = 0;

    public static final int TRAN_TYPE_CODE_LENGTH = 2;

    public static final int TRAN_TYPE_DESCRIPTION_OFFSET = 2;

    /**
     * Width of the description as this layout stores it. The transaction report presents a narrower
     * form; that narrowing belongs to the report formatter, not here.
     */
    public static final int TRAN_TYPE_DESCRIPTION_LENGTH = 50;

    public static final int TRAN_TYPE_FILLER_OFFSET = 52;

    /** Width of the unmapped trailing filler run. */
    public static final int TRAN_TYPE_FILLER_LENGTH = 8;

    /**
     * Byte this mapper writes across the filler run. The shipped fixture holds ASCII zero instead, which
     * is why a whole-record comparison against it fails on the filler alone.
     */
    public static final char TRAN_TYPE_FILLER_CHARACTER = ' ';

    /** Layout name carried into every diagnostic; it names both the record group and the copybook. */
    private static final String ARTEFACT = "TRAN-TYPE-RECORD (CVTRA03Y)";

    private static final String CODE_FIELD = "TRAN-TYPE";

    private static final String DESCRIPTION_FIELD = "TRAN-TYPE-DESC";

    /**
     * Refuses construction. An instance would be a per-caller copy of a layout the copybook defines
     * exactly once, which is how two callers come to disagree about a record that has only one
     * correct shape. An error rather than an exception, so it cannot be caught and worked around, and
     * so it survives reflective access, which a private constructor alone does not.
     *
     * @throws AssertionError always
     */
    private TranTypeRecordMapper() {
        throw new AssertionError("TranTypeRecordMapper is a static contract and is not instantiable");
    }

    /**
     * Maps a complete record image, supplied as text, to a populated entity.
     *
     * <p>The image must be exactly {@link #TRAN_TYPE_RECORD_LENGTH} encoded bytes and must exclude
     * any line terminator. Both fields are returned exactly as they appear in the record: untrimmed,
     * unstripped, not case-folded and not normalised, so a description that occupies its full 50
     * bytes arrives with all of its trailing spaces and a code of {@code 01} keeps its leading zero.
     * The trailing filler is read by nobody, because it carries no information.
     *
     * @param recordImage the complete 60-byte record image, without any line terminator
     * @return a fully populated entity, never {@code null}
     * @throws NullPointerException     if {@code recordImage} is {@code null}
     * @throws IllegalArgumentException if the image's encoded byte length is not exactly
     *                                  {@link #TRAN_TYPE_RECORD_LENGTH}, or if it contains a
     *                                  character US-ASCII cannot represent
     */
    public static TransactionType fromRecord(final String recordImage) {
        Objects.requireNonNull(recordImage, "recordImage must not be null");
        return map(FixedWidthFieldReader.of(ARTEFACT, recordImage, TRAN_TYPE_RECORD_LENGTH));
    }

    /**
     * Maps a complete record image, supplied as bytes, to a populated entity.
     *
     * <p>Preferred over {@link #fromRecord(String)} by a reader that already holds raw bytes, because
     * it removes any need for the caller to choose a charset: the width is checked in encoded bytes
     * and the slices are decoded as US-ASCII inside the slicing primitive, so no platform-default
     * conversion can occur on this path or on any other.
     *
     * @param recordImage the complete 60-byte record image, without any line terminator
     * @return a fully populated entity, never {@code null}
     * @throws NullPointerException     if {@code recordImage} is {@code null}
     * @throws IllegalArgumentException if the array's length is not exactly
     *                                  {@link #TRAN_TYPE_RECORD_LENGTH}, or if any byte is not
     *                                  7-bit ASCII
     */
    public static TransactionType fromRecord(final byte[] recordImage) {
        Objects.requireNonNull(recordImage, "recordImage must not be null");
        return map(FixedWidthFieldReader.of(ARTEFACT, recordImage, TRAN_TYPE_RECORD_LENGTH));
    }

    /**
     * Maps one record held inside a larger buffer to a populated entity.
     *
     * <p>The seam for a batch reader that holds a whole file in one buffer. The reference fixture is
     * newline-terminated with a stride of {@link #TRAN_TYPE_RECORD_LENGTH} plus one, so record
     * <em>i</em> of that file starts at {@code i * (TRAN_TYPE_RECORD_LENGTH + 1)} and this overload
     * selects it while leaving the terminator behind. Stride arithmetic stays with the caller on
     * purpose: this class knows about one record's geometry and nothing about files.
     *
     * <p>The guard here is a range check rather than a width check, because the caller states where
     * the record begins: exactly {@link #TRAN_TYPE_RECORD_LENGTH} bytes are read, and a buffer that
     * cannot supply them from the given position is rejected rather than short-read.
     *
     * @param buffer buffer containing the record, and possibly many others
     * @param from   zero-based index in {@code buffer} at which the record starts
     * @return a fully populated entity, never {@code null}
     * @throws NullPointerException     if {@code buffer} is {@code null}
     * @throws IllegalArgumentException if {@code from} is negative, if the 60 bytes starting at
     *                                  {@code from} do not lie wholly inside {@code buffer}, or if
     *                                  any byte in that range is not 7-bit ASCII
     */
    public static TransactionType fromRecord(final byte[] buffer, final int from) {
        Objects.requireNonNull(buffer, "buffer must not be null");
        return map(FixedWidthFieldReader.of(ARTEFACT, buffer, from, TRAN_TYPE_RECORD_LENGTH));
    }

    /**
     * Slices a validated record image into an entity.
     *
     * <p>The single point at which this layout's offsets are read, so all three decode entry points
     * cannot drift apart; the filler run is not read at all. The entity is built through its
     * all-argument constructor rather than by instantiating and then assigning, because the
     * no-argument constructor is reserved for the persistence provider and because a constructor
     * cannot leave an attribute unset.
     */
    private static TransactionType map(final FixedWidthFieldReader record) {
        final String code = record.field(CODE_FIELD, TRAN_TYPE_CODE_OFFSET, TRAN_TYPE_CODE_LENGTH);
        final String description = record.field(
                DESCRIPTION_FIELD, TRAN_TYPE_DESCRIPTION_OFFSET, TRAN_TYPE_DESCRIPTION_LENGTH);
        return new TransactionType(code, description);
    }

    /**
     * Renders an entity as a complete record image, as text.
     *
     * <p>The exact inverse of {@link #fromRecord(String)} over the mapped prefix: a record decoded
     * and re-encoded reproduces its first {@link #TRAN_TYPE_MAPPED_LENGTH} bytes unchanged, including
     * every trailing space of the description and the leading zero of the code.
     *
     * <p>The result is always exactly {@link #TRAN_TYPE_RECORD_LENGTH} bytes and carries no line
     * terminator. Both fields are placed left-justified and space-padded, which is what an
     * alphanumeric legacy declaration means, so a description shorter than 50 characters is padded on
     * the right to the declared width and the code is never zero-padded into a leading zero it did
     * not have. A value wider than its field is rejected rather than truncated: a truncated field
     * would leave the record exactly the right width while carrying the wrong content, which is the
     * one failure mode a downstream width check can never catch.
     *
     * <p><strong>The filler run is emitted as {@link #TRAN_TYPE_FILLER_CHARACTER}, so a whole-record
     * comparison against the reference fixture will fail.</strong> The fixture holds ASCII zero in
     * those eight bytes {@code [app/data/ASCII/trantype.txt]}. Compare only the byte range from 0 up
     * to but excluding {@link #TRAN_TYPE_MAPPED_LENGTH}.
     *
     * @param transactionType the entity to render; neither the entity nor either of its two
     *                        attributes may be {@code null}
     * @return the complete 60-byte record image, never {@code null}
     * @throws NullPointerException     if {@code transactionType} is {@code null}, or if either of
     *                                  its attributes is {@code null}
     * @throws IllegalArgumentException if either value's encoded byte length exceeds its field, or
     *                                  if either contains a character US-ASCII cannot represent
     */
    public static String toRecord(final TransactionType transactionType) {
        return assemble(transactionType).image();
    }

    /**
     * Renders an entity as a complete record image, as bytes.
     *
     * <p>Behaves exactly as {@link #toRecord(TransactionType)} and returns the same image, for a
     * writer that emits bytes rather than text. The array is a fresh copy of exactly
     * {@link #TRAN_TYPE_RECORD_LENGTH} bytes and the caller may mutate it freely. The same
     * comparison bound applies: compare only the first {@link #TRAN_TYPE_MAPPED_LENGTH} bytes against
     * the reference fixture.
     *
     * @param transactionType the entity to render; neither the entity nor either of its two
     *                        attributes may be {@code null}
     * @return a new array of exactly {@link #TRAN_TYPE_RECORD_LENGTH} bytes, never {@code null}
     * @throws NullPointerException     if {@code transactionType} is {@code null}, or if either of
     *                                  its attributes is {@code null}
     * @throws IllegalArgumentException if either value's encoded byte length exceeds its field, or
     *                                  if either contains a character US-ASCII cannot represent
     */
    public static byte[] toRecordBytes(final TransactionType transactionType) {
        return assemble(transactionType).toByteArray();
    }

    /**
     * Assembles a record image from an entity.
     *
     * <p>The single point at which this layout's offsets are written, so both encode entry points
     * cannot drift apart. Every byte of the record is accounted for by an explicit placement, which
     * is how a forgotten field is noticed during review rather than in a byte comparison.
     *
     * <p>Both attributes are required. An absent code could not be written into the field the
     * cluster declares as its key, and an absent description has no defensible rendering:
     * substituting spaces would silently manufacture a blank description, and skipping the field
     * would leave the buffer's initial spaces with exactly the same result. Refusing is the only
     * answer that tells the caller what is wrong, and each check names the attribute at fault.
     */
    private static FixedWidthFieldReader assemble(final TransactionType transactionType) {
        Objects.requireNonNull(transactionType, "transactionType must not be null");
        final String code = Objects.requireNonNull(transactionType.getTranType(),
                "transactionType.tranType must not be null: TRAN-TYPE is the whole of the record key");
        final String description = Objects.requireNonNull(transactionType.getTranTypeDesc(),
                "transactionType.tranTypeDesc must not be null: TRAN-TYPE-DESC is a mapped field");
        return FixedWidthFieldReader.builder(ARTEFACT, TRAN_TYPE_RECORD_LENGTH)
                .putAlphanumeric(CODE_FIELD, TRAN_TYPE_CODE_OFFSET, TRAN_TYPE_CODE_LENGTH, code)
                .putAlphanumeric(DESCRIPTION_FIELD, TRAN_TYPE_DESCRIPTION_OFFSET,
                        TRAN_TYPE_DESCRIPTION_LENGTH, description)
                .putFiller(TRAN_TYPE_FILLER_OFFSET, TRAN_TYPE_FILLER_LENGTH,
                        TRAN_TYPE_FILLER_CHARACTER)
                .build();
    }
}
