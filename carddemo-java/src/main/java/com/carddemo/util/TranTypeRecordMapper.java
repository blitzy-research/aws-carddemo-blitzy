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
    public static final int TRAN_TYPE_RECORD_LENGTH = 60;

    public static final int TRAN_TYPE_MAPPED_LENGTH = 52;

    public static final int TRAN_TYPE_CODE_OFFSET = 0;

    public static final int TRAN_TYPE_CODE_LENGTH = 2;

    public static final int TRAN_TYPE_DESCRIPTION_OFFSET = 2;

    public static final int TRAN_TYPE_DESCRIPTION_LENGTH = 50;

    public static final int TRAN_TYPE_FILLER_OFFSET = 52;

    public static final int TRAN_TYPE_FILLER_LENGTH = 8;

    public static final char TRAN_TYPE_FILLER_CHARACTER = ' ';

    private static final String ARTEFACT = "TRAN-TYPE-RECORD (CVTRA03Y)";

    private static final String CODE_FIELD = "TRAN-TYPE";

    private static final String DESCRIPTION_FIELD = "TRAN-TYPE-DESC";

    private TranTypeRecordMapper() {
        throw new AssertionError("TranTypeRecordMapper is a static contract and is not instantiable");
    }

    /**
     * Maps a 60-byte record image onto an entity.
     *
     * @param recordImage the record, whose encoded length must be exactly
     *                    {@link #TRAN_TYPE_RECORD_LENGTH}
     * @return a fully populated entity, never partially mapped
     */
    public static TransactionType fromRecord(final String recordImage) {
        Objects.requireNonNull(recordImage, "recordImage must not be null");
        return map(FixedWidthFieldReader.of(ARTEFACT, recordImage, TRAN_TYPE_RECORD_LENGTH));
    }

    /**
     * Maps a 60-byte record image onto an entity.
     *
     * @param recordImage the record bytes, exactly {@link #TRAN_TYPE_RECORD_LENGTH} long
     * @return a fully populated entity, never partially mapped
     */
    public static TransactionType fromRecord(final byte[] recordImage) {
        Objects.requireNonNull(recordImage, "recordImage must not be null");
        return map(FixedWidthFieldReader.of(ARTEFACT, recordImage, TRAN_TYPE_RECORD_LENGTH));
    }

    /**
     * Maps one record held inside a larger buffer, for a reader that holds many records at once.
     * Stride arithmetic - and therefore the record separator, if the file carries one - stays with the
     * caller.
     *
     * @param buffer the buffer holding the record, and possibly others
     * @param from the zero-based index at which the record starts
     * @return a fully populated entity, never partially mapped
     */
    public static TransactionType fromRecord(final byte[] buffer, final int from) {
        Objects.requireNonNull(buffer, "buffer must not be null");
        return map(FixedWidthFieldReader.of(ARTEFACT, buffer, from, TRAN_TYPE_RECORD_LENGTH));
    }

    private static TransactionType map(final FixedWidthFieldReader record) {
        final String code = record.field(CODE_FIELD, TRAN_TYPE_CODE_OFFSET, TRAN_TYPE_CODE_LENGTH);
        final String description = record.field(
                DESCRIPTION_FIELD, TRAN_TYPE_DESCRIPTION_OFFSET, TRAN_TYPE_DESCRIPTION_LENGTH);
        return new TransactionType(code, description);
    }

    /**
     * Renders an entity back to its 60-byte image, filler included.
     *
     * @param transactionType the entity to render
     * @return the 60-byte record image
     */
    public static String toRecord(final TransactionType transactionType) {
        return assemble(transactionType).image();
    }

    /**
     * Renders an entity back to its 60-byte image as bytes.
     *
     * @param transactionType the entity to render
     * @return the 60 encoded bytes
     */
    public static byte[] toRecordBytes(final TransactionType transactionType) {
        return assemble(transactionType).toByteArray();
    }

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
