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

import com.carddemo.domain.Transaction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Mapper for the statement job's keyed transaction work record.
 *
 * <p>The record consumed by {@code CBSTM03B} is 350 bytes:
 * {@code card-number(16) + transaction-id(16) + transaction-rest(318)}. Its first 328 bytes come from
 * the {@code CREASTMT} reprojection, and the remaining 22 bytes are blank padding. The reprojection
 * deliberately carries only 24 of the processing timestamp's 26 bytes, so decoding a work record must
 * leave the final two timestamp bytes blank rather than recovering them from the live transaction row.
 *
 * <p>This class is the sole authority for converting between the canonical transaction layout and the
 * statement-work layout. Keeping both directions here prevents the ordering step, the data-access
 * adapter and the generator from each implementing a slightly different offset map.
 */
public final class StatementWorkRecordMapper {

    /** Diagnostic name of the work layout. */
    public static final String ARTEFACT = "TRNX-FILE statement work record";

    /** Full fixed record length declared by the transient work cluster. */
    public static final int RECORD_LENGTH = TransactionRecordMapper.RECORD_LENGTH;

    /** Card number moved to the beginning of the work record. */
    public static final int CARD_NUMBER_OFFSET = 0;

    /** Width of the leading card number. */
    public static final int CARD_NUMBER_LENGTH = TransactionRecordMapper.TRAN_CARD_NUM_LENGTH;

    /** Transaction identifier immediately following the card number. */
    public static final int TRANSACTION_ID_OFFSET = CARD_NUMBER_OFFSET + CARD_NUMBER_LENGTH;

    /** Width of the transaction identifier. */
    public static final int TRANSACTION_ID_LENGTH = TransactionRecordMapper.TRAN_ID_LENGTH;

    /** Offset of the 318-byte remainder declared by {@code FD-ACCT-DATA}. */
    public static final int TRANSACTION_REST_OFFSET =
            TRANSACTION_ID_OFFSET + TRANSACTION_ID_LENGTH;

    /** Width of the remainder after card number and transaction identifier. */
    public static final int TRANSACTION_REST_LENGTH =
            RECORD_LENGTH - TRANSACTION_REST_OFFSET;

    /** Width of the canonical record prefix preceding its card number. */
    public static final int LEADING_SEGMENT_LENGTH = TransactionRecordMapper.TRAN_CARD_NUM_OFFSET;

    /** Offset of the copied timestamp segment in both canonical and work records. */
    public static final int TIMESTAMP_SEGMENT_OFFSET =
            CARD_NUMBER_LENGTH + LEADING_SEGMENT_LENGTH;

    /** Width selected by {@code OUTREC FIELDS=(...,279:279,50)}. */
    public static final int TIMESTAMP_SEGMENT_LENGTH = 50;

    /** Bytes populated by the three reprojection segments. */
    public static final int PROJECTED_CONTENT_LENGTH =
            CARD_NUMBER_LENGTH + LEADING_SEGMENT_LENGTH + TIMESTAMP_SEGMENT_LENGTH;

    /** Blank bytes completing the 350-byte work record. */
    public static final int BLANK_PAD_LENGTH = RECORD_LENGTH - PROJECTED_CONTENT_LENGTH;

    /**
     * Processing-timestamp bytes the reprojection deliberately drops.
     *
     * <p>The copied timestamp segment is {@value #TIMESTAMP_SEGMENT_LENGTH} bytes beginning at the
     * origination timestamp, so it carries that field whole and only the leading part of the processing
     * timestamp. The remainder - this many bytes - never reaches the work record and must not be
     * recovered from the live transaction row, which is the whole reason the truncation is named here
     * rather than left as arithmetic at a call site.
     */
    public static final int TRUNCATED_PROCESSING_TIMESTAMP_LENGTH =
            TransactionRecordMapper.TRAN_PROC_TS_LENGTH
                    - (TIMESTAMP_SEGMENT_LENGTH - TransactionRecordMapper.TRAN_ORIG_TS_LENGTH);

    /** Work-record key: card number followed by transaction identifier. */
    public static final int KEY_LENGTH = CARD_NUMBER_LENGTH + TRANSACTION_ID_LENGTH;

    private static final String FIELD_CARD_NUMBER = "FD-TRNX-CARD";
    private static final String FIELD_TRANSACTION_ID = "FD-TRNX-ID";
    private static final String FIELD_LEADING_SEGMENT = "TRANSACTION-LEADING-SEGMENT";
    private static final String FIELD_TIMESTAMP_SEGMENT = "TRANSACTION-TIMESTAMP-SEGMENT";
    private static final String FIELD_TRAILING_PAD = "STATEMENT-WORK-TRAILING-PAD";

    /**
     * Verifies the declared geometry once, at class initialisation, so a mis-typed figure fails on first
     * use rather than producing a plausible but wrongly framed work record.
     *
     * <p>These checks used to live beside a second copy of this layout in
     * {@link TransactionRecordMapper}. They belong here, with the layout they describe: this class is
     * the sole authority for the reprojection, so it is also the only place that can assert the
     * reprojection is self-consistent.
     */
    static {
        requireSum("work key", KEY_LENGTH, CARD_NUMBER_LENGTH + TRANSACTION_ID_LENGTH);
        requireSum("transaction-identifier offset", TRANSACTION_ID_OFFSET, CARD_NUMBER_LENGTH);
        requireSum("remainder offset", TRANSACTION_REST_OFFSET, KEY_LENGTH);
        requireSum("remainder width", TRANSACTION_REST_LENGTH, RECORD_LENGTH - KEY_LENGTH);
        requireSum("timestamp-segment offset", TIMESTAMP_SEGMENT_OFFSET,
                CARD_NUMBER_LENGTH + LEADING_SEGMENT_LENGTH);
        requireSum("projected content", PROJECTED_CONTENT_LENGTH,
                CARD_NUMBER_LENGTH + LEADING_SEGMENT_LENGTH + TIMESTAMP_SEGMENT_LENGTH);
        requireSum("work record", RECORD_LENGTH, PROJECTED_CONTENT_LENGTH + BLANK_PAD_LENGTH);
        requireSum("processing-timestamp truncation", TRUNCATED_PROCESSING_TIMESTAMP_LENGTH,
                TransactionRecordMapper.TRAN_PROC_TS_LENGTH
                        - (TIMESTAMP_SEGMENT_LENGTH - TransactionRecordMapper.TRAN_ORIG_TS_LENGTH));
    }

    private StatementWorkRecordMapper() {
    }

    /**
     * Refuses a declared width or offset that disagrees with the parts it is composed of.
     *
     * @param subject  what the figure describes, for the diagnostic
     * @param declared the published figure
     * @param computed the figure its parts sum to
     */
    private static void requireSum(final String subject, final int declared, final int computed) {
        if (declared != computed) {
            throw new IllegalStateException(ARTEFACT + " layout is inconsistent: the " + subject
                    + " is published as " + declared + " encoded bytes but its parts sum to "
                    + computed);
        }
    }

    /**
     * Builds the statement-work image for one transaction.
     *
     * @param transaction the transaction to project
     * @return one 350-byte work record
     */
    public static String toRecord(final Transaction transaction) {
        Objects.requireNonNull(transaction, "transaction must not be null");
        return fromTransactionRecord(TransactionRecordMapper.toRecord(transaction));
    }

    /**
     * Applies the exact three-segment JCL reprojection to a canonical transaction image.
     *
     * @param transactionRecord the canonical 350-byte transaction image
     * @return one 350-byte work record containing 328 projected bytes and 22 trailing spaces
     */
    public static String fromTransactionRecord(final String transactionRecord) {
        final FixedWidthFieldReader source = FixedWidthFieldReader.of(
                TransactionRecordMapper.ARTEFACT,
                Objects.requireNonNull(transactionRecord, "transactionRecord must not be null"),
                TransactionRecordMapper.RECORD_LENGTH);

        return FixedWidthFieldReader.builder(ARTEFACT, RECORD_LENGTH)
                .putAlphanumeric(FIELD_CARD_NUMBER, CARD_NUMBER_OFFSET, CARD_NUMBER_LENGTH,
                        source.field(FIELD_CARD_NUMBER,
                                TransactionRecordMapper.TRAN_CARD_NUM_OFFSET,
                                CARD_NUMBER_LENGTH))
                .putAlphanumeric(FIELD_LEADING_SEGMENT, TRANSACTION_ID_OFFSET,
                        LEADING_SEGMENT_LENGTH,
                        source.field(FIELD_LEADING_SEGMENT,
                                TransactionRecordMapper.TRAN_ID_OFFSET,
                                LEADING_SEGMENT_LENGTH))
                .putAlphanumeric(FIELD_TIMESTAMP_SEGMENT, TIMESTAMP_SEGMENT_OFFSET,
                        TIMESTAMP_SEGMENT_LENGTH,
                        source.field(FIELD_TIMESTAMP_SEGMENT,
                                TransactionRecordMapper.TRAN_ORIG_TS_OFFSET,
                                TIMESTAMP_SEGMENT_LENGTH))
                .putSpaceFiller(PROJECTED_CONTENT_LENGTH, BLANK_PAD_LENGTH)
                .build()
                .image();
    }

    /**
     * Decodes one statement-work image into the canonical transaction entity view.
     *
     * <p>The canonical image is reconstructed only from bytes present in the work record. Its final two
     * processing-timestamp bytes and its twenty filler bytes therefore come from the work record's
     * 22-byte blank pad, preserving the intentional truncation.
     *
     * @param workRecord one 350-byte statement-work image
     * @return the represented transaction
     */
    public static Transaction fromRecord(final String workRecord) {
        final FixedWidthFieldReader source = FixedWidthFieldReader.of(
                ARTEFACT, Objects.requireNonNull(workRecord, "workRecord must not be null"),
                RECORD_LENGTH);

        final String canonical = FixedWidthFieldReader
                .builder(TransactionRecordMapper.ARTEFACT, TransactionRecordMapper.RECORD_LENGTH)
                .putAlphanumeric(FIELD_LEADING_SEGMENT, TransactionRecordMapper.TRAN_ID_OFFSET,
                        LEADING_SEGMENT_LENGTH,
                        source.field(FIELD_LEADING_SEGMENT, TRANSACTION_ID_OFFSET,
                                LEADING_SEGMENT_LENGTH))
                .putAlphanumeric(FIELD_CARD_NUMBER,
                        TransactionRecordMapper.TRAN_CARD_NUM_OFFSET, CARD_NUMBER_LENGTH,
                        source.field(FIELD_CARD_NUMBER, CARD_NUMBER_OFFSET, CARD_NUMBER_LENGTH))
                .putAlphanumeric(FIELD_TIMESTAMP_SEGMENT,
                        TransactionRecordMapper.TRAN_ORIG_TS_OFFSET, TIMESTAMP_SEGMENT_LENGTH,
                        source.field(FIELD_TIMESTAMP_SEGMENT, TIMESTAMP_SEGMENT_OFFSET,
                                TIMESTAMP_SEGMENT_LENGTH))
                .putAlphanumeric(FIELD_TRAILING_PAD, PROJECTED_CONTENT_LENGTH, BLANK_PAD_LENGTH,
                        source.field(FIELD_TRAILING_PAD, PROJECTED_CONTENT_LENGTH,
                                BLANK_PAD_LENGTH))
                .build()
                .image();
        return TransactionRecordMapper.fromRecord(canonical);
    }

    /**
     * Byte-array overload for a staged-resource reader.
     *
     * @param workRecord one encoded statement-work record
     * @return the represented transaction
     */
    public static Transaction fromRecord(final byte[] workRecord) {
        Objects.requireNonNull(workRecord, "workRecord must not be null");
        FixedWidthFieldReader.of(ARTEFACT, workRecord, 0, RECORD_LENGTH);
        return fromRecord(new String(workRecord, StandardCharsets.US_ASCII));
    }

    /**
     * Decodes one work record held at an offset inside a larger buffer, for a reader that fills a block
     * of several fixed-length records in one read.
     *
     * @param buffer the buffer holding the record
     * @param from   zero-based offset of the record within that buffer
     * @return the represented transaction
     * @throws NullPointerException     if {@code buffer} is {@code null}
     * @throws IllegalArgumentException if the buffer does not hold a whole record at that offset
     */
    public static Transaction fromRecord(final byte[] buffer, final int from) {
        Objects.requireNonNull(buffer, "workRecord buffer must not be null");
        FixedWidthFieldReader.of(ARTEFACT, buffer, from, RECORD_LENGTH);
        return fromRecord(new String(buffer, from, RECORD_LENGTH, StandardCharsets.US_ASCII));
    }

    /**
     * Returns the work resource's complete 32-byte business key.
     *
     * @param workRecord one statement-work image
     * @return card number followed by transaction identifier
     */
    public static String key(final String workRecord) {
        return FixedWidthFieldReader.of(ARTEFACT,
                Objects.requireNonNull(workRecord, "workRecord must not be null"), RECORD_LENGTH)
                .key(KEY_LENGTH);
    }
}