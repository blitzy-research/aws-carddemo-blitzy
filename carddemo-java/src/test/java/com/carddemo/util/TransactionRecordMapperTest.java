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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit test for {@link TransactionRecordMapper}, the two-way mapping between the <strong>350-byte</strong>
 * posted-transaction record image and the {@link Transaction} entity.
 *
 * <h2>Why three of this record's offsets are contract and not implementation detail</h2>
 *
 * <p>Most byte offsets in a fixed-width layout concern only the class that slices them. Three of this
 * record's do not. They are addressed by <em>absolute column</em> from outside the program that owns the
 * record - by two external sort specifications and by a keyed-cluster alternate index - so a field
 * displaced by a single byte would leave every Java file compiling, would round-trip cleanly through this
 * mapper, and would still hand the report job, the statement job and the index the wrong bytes. All three
 * would break at once. Every offset below is therefore asserted against the integer the copybook declares,
 * written here as an independent literal, rather than against another constant of the code under test.
 *
 * <ul>
 *   <li>The report procedure's sort symbol names the card number at <strong>one-based column 263 for 16
 *       bytes, typed as zoned decimal</strong>.</li>
 *   <li>The report procedure's sort symbol names the processing date at <strong>one-based column 305 for
 *       10 bytes, typed as character</strong>, and an inclusive date-range condition filters on it.</li>
 *   <li>The statement job's sort fields address the <strong>same sixteen bytes at one-based column 263,
 *       typed as character</strong>, then the transaction identifier at column 1.</li>
 *   <li>The alternate index takes a non-unique, upgraded key of <strong>26 bytes at offset 304</strong>,
 *       which independently corroborates the processing stamp's position and width.</li>
 * </ul>
 *
 * <p><strong>The same sixteen bytes are typed two different ways by two different jobs.</strong> One
 * declares the card number zoned decimal; the other declares the identical bytes character. Those two
 * collations are not interchangeable - a zoned-decimal reading treats the final byte as carrying a sign as
 * well as a digit - so the two jobs can order the same input differently. Nothing in the estate reconciles
 * them, and nothing here does either: <strong>an ordering comparator therefore belongs to the job that
 * needs it and is never shared</strong>. This mapper performs no sorting and applies no field typing, so
 * the tests below assert only the offset and the width and record the dual typing so that nobody later
 * factors out a single shared comparator.
 *
 * <p><strong>The processing date is not a field.</strong> The ten bytes the report filters on are the
 * <em>leading part</em> of the 26-byte processing stamp - column 305 one-based is exactly where the stamp
 * begins - and the copybook declares no independent date item. There is no {@code tranProcDt} property to
 * read, and none is introduced.
 *
 * <h2>Every expectation here is hand-derived, and the witness is a real shipped file</h2>
 *
 * <p>No assertion calls a production method to compute the value it then checks, no expected image is
 * snapshotted from an earlier run, and no offset or width constant of the mapper appears on both sides of
 * a comparison. Reference records are declared as literal field values and reassembled by this class's own
 * two padding helpers, which reimplement the justification a character field and an unsigned numeric field
 * each receive.
 *
 * <p><strong>The posted-transaction table starts empty</strong>: the reference-data seed populates the
 * account, card, customer and reference tables but inserts no transaction, so there is no seeded fixture
 * for this layout at all. The offset witness is consequently the daily-transaction sample file
 * {@code app/data/ASCII/dailytran.txt}, whose geometry is field-for-field parallel to this record at
 * identical offsets and identical widths. That file measures 105,300 bytes, which is exactly 300 records at
 * a stride of 351 - the 350-byte record plus one line-feed terminator. <strong>The terminator is a
 * separator and never record content.</strong> Three of its rows are transcribed below, field by field, and
 * they are the oracle for the reading tests:
 *
 * <table>
 * <caption>The three witness rows and what each one proves</caption>
 * <tr><th scope="col">Row</th><th scope="col">Proves</th></tr>
 * <tr><td>0</td><td>every one of the thirteen properties at its declared offset; a positive overpunch;
 *     a space-padded five-digit postal code; a blank processing stamp</td></tr>
 * <tr><td>1</td><td>a negative overpunch; an operator-originated source; the second of only two source
 *     values and two type codes the file carries</td></tr>
 * <tr><td>299</td><td>a hyphenated nine-digit postal code filling all ten bytes, which is why that field
 *     is free-form and is never validated or reformatted</td></tr>
 * </table>
 *
 * <p>Measured over the amount field's final byte across all 300 rows, every one of the twenty overpunch
 * forms occurs - 250 positive and 50 negative - so both signed directions are exercised by shipped data
 * rather than by an invented edge case.
 *
 * <h2>Two entities share one geometry and are deliberately kept apart</h2>
 *
 * <p>The daily-transaction layout has the same order, the same widths and the same offsets as this one.
 * They remain separate entities over separate tables because they are separate datasets with separate
 * lifecycles - this one was a keyed cluster, that one arrives as a sequential dataset. Accordingly this
 * file imports neither the sibling mapper nor the sibling entity, extracts no shared base class, no shared
 * offset holder and no shared helper. The visible naming asymmetry is preserved rather than tidied:
 * <strong>this entity leaves all four merchant properties unprefixed</strong> - the merchant identifier
 * maps to the column {@code merchant_id} - <strong>where the sibling prefixes all thirteen</strong>. A test
 * below records that divergence as behaviour so that harmonising it cannot pass unnoticed.
 *
 * <h2>The amount is zoned decimal with its sign overpunched, and it truncates</h2>
 *
 * <p>Eleven bytes hold nine integer digits and two decimals; the trailing byte carries the low-order digit
 * <em>and</em> the sign, drawn from ten positive forms and ten negative forms. Decoding truncates toward
 * zero at scale two and never rounds, because no arithmetic statement anywhere in the estate specifies a
 * rounding clause and a store without one truncates. This class names no rounding mode, rescales nothing,
 * calls no scaling operation, and builds every expected amount from a decimal string literal rather than
 * from any binary approximation type.
 *
 * <h2>Both stamps stay raw twenty-six-byte text</h2>
 *
 * <p>Nothing here parses, formats or normalises one, and no temporal type is referenced. A processing stamp
 * that has not yet been written is twenty-six spaces, which no temporal type can hold: it must survive as
 * spaces rather than becoming null, empty or shortened. Every row of the witness file is blank in exactly
 * that way, so the blank state is the normal one rather than the exception.
 *
 * <h2>Two downstream projections that are recorded here and implemented nowhere near here</h2>
 *
 * <p>The statement job reprojects this record as the 16-byte card number, then its first 262 bytes, then 50
 * bytes taken from one-based column 279 - which is 328 of the 350 bytes, and those last 50 cover the whole
 * origination stamp but only 24 of the 26 processing-stamp bytes, so the processing stamp loses exactly two
 * bytes. The consuming program's own working storage shows the same shortfall independently, holding each
 * transaction as a 16-byte identifier plus a 318-byte remainder, which is 334 rather than 350. Both belong
 * to the batch tier. <strong>Neither is implemented, asserted or emulated in this test</strong>; they are
 * noted only so that a reader who meets those figures elsewhere knows they are expected.
 *
 * <h2>Decision-log divergences this file proves</h2>
 *
 * <p>Where a faithful translation and an idiomatic Java one pull in different directions, the faithful one
 * wins and the divergence is recorded in {@code docs/decision-log.md}. Six of those entries are the ones
 * this file exists to hold in place. They are named here so a reviewer can find the corresponding
 * assertion; the log itself is not edited from a test.
 *
 * <ol>
 *   <li>Three offsets - 262, 278 and 304 - are fixed by external sort declarations and a cluster
 *       definition rather than chosen by this module.</li>
 *   <li>The same sixteen bytes are typed zoned decimal by one job and character by another, which is why
 *       an ordering comparator is per-job and is never shared.</li>
 *   <li>The report's processing date is the leading ten bytes of the twenty-six-byte stamp and not a field,
 *       so no separate date property exists.</li>
 *   <li>Both stamps are kept as raw twenty-six-byte text rather than a temporal type, because the shipped
 *       value is twenty-six spaces and no temporal type can hold that.</li>
 *   <li>The four merchant properties are unprefixed here and prefixed on the parallel daily-transaction
 *       entity; the asymmetry is preserved rather than harmonised.</li>
 *   <li>The posted-transaction table is seeded empty, so the daily-transaction sample file is this
 *       layout's offset witness.</li>
 * </ol>
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Layout authority is copybook
 * {@code app/cpy/CVTRA05Y.cpy}; the record width and primary key come from {@code app/jcl/TRANFILE.jcl};
 * the alternate-index key from {@code app/jcl/TRANIDX.jcl}; the two sort specifications from
 * {@code app/proc/TRANREPT.prc} and {@code app/jcl/CREASTMT.JCL}. Provenance is a header string only and is
 * never asserted on a member. No legacy source line is transcribed anywhere in this file - only widths,
 * offsets, counts, field names, cluster attributes, sort symbol names and contract literals, which are
 * metadata rather than source.
 *
 * @see TransactionRecordMapper
 * @see Transaction
 */
@DisplayName("posted transaction record mapper: the 350-byte CVTRA05Y keyed layout")
class TransactionRecordMapperTest {

    // ------------------------------------------------------------------------------------------------
    // The copybook's declared widths, written as integer literals.
    //
    // These are the independent side of every geometry assertion. They live here, once, as literals
    // rather than being read from the mapper, so that the mapper's published constants are compared
    // against the copybook and never against themselves. A layout shifted uniformly - the one error a
    // self-consistent constant set cannot catch - fails against these.
    // ------------------------------------------------------------------------------------------------

    /** {@code TRAN-ID PIC X(16)}: the record key. */
    private static final int WIDTH_ID = 16;

    /** {@code TRAN-TYPE-CD PIC X(02)}. */
    private static final int WIDTH_TYPE_CD = 2;

    /** {@code TRAN-CAT-CD PIC 9(04)}: unsigned numeric, so leading zeros are significant. */
    private static final int WIDTH_CAT_CD = 4;

    /** {@code TRAN-SOURCE PIC X(10)}: trailing blanks are significant content. */
    private static final int WIDTH_SOURCE = 10;

    /** {@code TRAN-DESC PIC X(100)}. */
    private static final int WIDTH_DESC = 100;

    /** {@code TRAN-AMT PIC S9(09)V99}: nine integer digits and two decimals in eleven bytes. */
    private static final int WIDTH_AMT = 11;

    /** {@code TRAN-MERCHANT-ID PIC 9(09)}: unsigned numeric, leading zeros significant. */
    private static final int WIDTH_MERCHANT_ID = 9;

    /** {@code TRAN-MERCHANT-NAME PIC X(50)}. */
    private static final int WIDTH_MERCHANT_NAME = 50;

    /** {@code TRAN-MERCHANT-CITY PIC X(50)}. */
    private static final int WIDTH_MERCHANT_CITY = 50;

    /** {@code TRAN-MERCHANT-ZIP PIC X(10)}: free-form, never numeric. */
    private static final int WIDTH_MERCHANT_ZIP = 10;

    /** {@code TRAN-CARD-NUM PIC X(16)}: the sixteen bytes both external sorts address. */
    private static final int WIDTH_CARD_NUM = 16;

    /** {@code TRAN-ORIG-TS PIC X(26)}: raw text. */
    private static final int WIDTH_ORIG_TS = 26;

    /** {@code TRAN-PROC-TS PIC X(26)}: raw text, legitimately all blanks. */
    private static final int WIDTH_PROC_TS = 26;

    /** {@code FILLER PIC X(20)}: the unmapped trailing run. */
    private static final int WIDTH_FILLER = 20;

    /** The declared record length, and the sum of the fourteen widths above. */
    private static final int RECORD_WIDTH = 350;

    /** The mapped prefix: every field the mapper reads, and nothing beyond. */
    private static final int MAPPED_WIDTH = RECORD_WIDTH - WIDTH_FILLER;

    // ------------------------------------------------------------------------------------------------
    // Zero-based offsets, obtained by summing the widths above in declaration order but written out as
    // literals rather than as sums, so that an arithmetic slip in one offset cannot propagate silently
    // into the next.
    // ------------------------------------------------------------------------------------------------

    /** Zero-based offset of the transaction identifier: the record begins with it. */
    private static final int OFFSET_ID = 0;

    /** Zero-based offset of the transaction type code. */
    private static final int OFFSET_TYPE_CD = 16;

    /** Zero-based offset of the transaction category code. */
    private static final int OFFSET_CAT_CD = 18;

    /** Zero-based offset of the origination source. */
    private static final int OFFSET_SOURCE = 22;

    /** Zero-based offset of the description. */
    private static final int OFFSET_DESC = 32;

    /** Zero-based offset of the zoned-decimal amount. */
    private static final int OFFSET_AMT = 132;

    /** Zero-based offset of the merchant identifier. */
    private static final int OFFSET_MERCHANT_ID = 143;

    /** Zero-based offset of the merchant name. */
    private static final int OFFSET_MERCHANT_NAME = 152;

    /** Zero-based offset of the merchant city. */
    private static final int OFFSET_MERCHANT_CITY = 202;

    /** Zero-based offset of the merchant postal code. */
    private static final int OFFSET_MERCHANT_ZIP = 252;

    /**
     * <strong>Contractual.</strong> Zero-based offset of the card number, which both external sorts
     * address at one-based column 263.
     */
    private static final int OFFSET_CARD_NUM = 262;

    /**
     * <strong>Contractual.</strong> Zero-based offset of the origination stamp, which the statement job's
     * reprojection addresses at one-based column 279.
     */
    private static final int OFFSET_ORIG_TS = 278;

    /**
     * <strong>Contractual.</strong> Zero-based offset of the processing stamp: the alternate index's key
     * offset, and the position the report's date filter addresses at one-based column 305.
     */
    private static final int OFFSET_PROC_TS = 304;

    /** Zero-based offset at which the unmapped filler run begins. */
    private static final int OFFSET_FILLER = 330;

    // ------------------------------------------------------------------------------------------------
    // The one-based columns the external sort specifications carry, and the keyed-cluster attributes.
    //
    // Each is written as the literal the job stream declares rather than as an offset plus one, because
    // deriving it would make the assertion that the two addressing conventions agree vacuous.
    // ------------------------------------------------------------------------------------------------

    /**
     * One-based column at which the report procedure's sort symbol {@code TRAN-CARD-NUM} addresses the
     * card number, and at which the statement job's sort fields address the same bytes.
     */
    private static final int SORT_COLUMN_CARD_NUM = 263;

    /** One-based column of the origination stamp, as the statement job's reprojection addresses it. */
    private static final int SORT_COLUMN_ORIG_TS = 279;

    /**
     * One-based column at which the report procedure's sort symbol {@code TRAN-PROC-DT} addresses the
     * processing date, which is also where the processing stamp itself begins.
     */
    private static final int SORT_COLUMN_PROC_DT = 305;

    /** Bytes the report procedure's {@code TRAN-PROC-DT} symbol spans: the date only, not the stamp. */
    private static final int WIDTH_PROC_DT = 10;

    /**
     * The two implied decimals the amount's picture clause declares, written as a literal so that every
     * scale assertion below has an expectation of its own rather than one borrowed from the codec. One
     * test additionally pins the codec's published scale to this same literal, which is what keeps the two
     * from drifting apart.
     */
    private static final int EXPECTED_MONETARY_SCALE = 2;

    /**
     * The rounding policy name the estate's arithmetic implies. A store into a two-decimal receiving field
     * with no rounding clause truncates toward zero, and the clause occurs nowhere in the estate.
     */
    private static final String EXPECTED_ROUNDING_MODE = "DOWN";

    /**
     * The difference between a one-based external-sort column and the zero-based Java offset of the same
     * byte. Named rather than left as a bare literal, because this single byte is the commonest defect in
     * fixed-width work and both conversions are asserted explicitly below.
     */
    private static final int ONE_BASED_TO_ZERO_BASED = 1;

    /** Key length the base cluster declares for this record: sixteen bytes. */
    private static final int CLUSTER_KEY_LENGTH = 16;

    /** Key offset the base cluster declares: zero, so the key is the record's leading field. */
    private static final int CLUSTER_KEY_OFFSET = 0;

    /** Key length the alternate index declares: twenty-six bytes. */
    private static final int ALTERNATE_INDEX_KEY_LENGTH = 26;

    /** Key offset the alternate index declares: 304, the processing stamp's own offset. */
    private static final int ALTERNATE_INDEX_KEY_OFFSET = 304;

    /** The byte the filler run carries, and the byte a character field pads with: US-ASCII space. */
    private static final byte ASCII_SPACE = 0x20;

    /** Stride of the witness file: the record width plus its one-byte line-feed terminator. */
    private static final int WITNESS_FILE_STRIDE = RECORD_WIDTH + 1;

    // ------------------------------------------------------------------------------------------------
    // Witness row 0 of app/data/ASCII/dailytran.txt, transcribed field by field at the offsets above.
    //
    // Significant content only; the padding each field carries is applied by the helpers further down,
    // so the transcription stays readable while the assembled image stays byte-exact.
    // ------------------------------------------------------------------------------------------------

    /** Row 0 identifier: sixteen bytes, the leading zeros a keyed record holds. */
    private static final String ROW0_ID = "0000000000683580";

    /** Row 0 type code: one of only two the witness file carries. */
    private static final String ROW0_TYPE_CD = "01";

    /** Row 0 category code: four bytes with significant leading zeros. */
    private static final String ROW0_CAT_CD = "0001";

    /**
     * Row 0 origination source, written with the two trailing spaces it actually carries so that the
     * ten-byte field is filled exactly and no assertion can silently depend on padding being added.
     */
    private static final String ROW0_SOURCE = "POS TERM  ";

    /** The trimmed form of {@link #ROW0_SOURCE}, written as a literal so no trimming call is needed. */
    private static final String ROW0_SOURCE_TRIMMED = "POS TERM";

    /** Row 0 description: far shorter than its hundred-byte field, so the padding is exercised. */
    private static final String ROW0_DESC = "Purchase at Abshire-Lowe";

    /**
     * Row 0 amount field, transcribed as its eleven raw bytes.
     *
     * <p>Ten plain digits then an overpunched final byte. {@code G} is the eighth positive form, so it
     * contributes the digit seven as well as the sign: the eleven digits are {@code 00000050477} and, at
     * two implied decimals, the value is {@link #ROW0_AMOUNT}. Reading the trailing letter as a sign-only
     * byte would give 500.47 instead, which is the plausible wrong answer this literal guards against.
     */
    private static final String ROW0_AMOUNT_IMAGE = "0000005047G";

    /** Row 0 amount, decoded by hand from {@link #ROW0_AMOUNT_IMAGE}. */
    private static final BigDecimal ROW0_AMOUNT = new BigDecimal("504.77");

    /** Row 0 merchant identifier, filling its nine-byte field exactly. */
    private static final String ROW0_MERCHANT_ID = "800000000";

    /** Row 0 merchant name, shorter than its fifty-byte field. */
    private static final String ROW0_MERCHANT_NAME = "Abshire-Lowe";

    /** Row 0 merchant city, shorter than its fifty-byte field. */
    private static final String ROW0_MERCHANT_CITY = "North Enoshaven";

    /**
     * Row 0 merchant postal code, written with the five trailing spaces it carries: a five-digit code in
     * a ten-byte free-form field.
     */
    private static final String ROW0_MERCHANT_ZIP = "72112     ";

    /** The trimmed form of {@link #ROW0_MERCHANT_ZIP}, as a literal rather than a trimming call. */
    private static final String ROW0_MERCHANT_ZIP_TRIMMED = "72112";

    /** Row 0 card number, filling its sixteen-byte contractual field exactly. */
    private static final String ROW0_CARD_NUM = "4859452612877065";

    /** Row 0 origination stamp: twenty-six bytes of raw text, identical on all 300 witness rows. */
    private static final String ROW0_ORIG_TS = "2022-06-10 19:27:53.000000";

    /** The leading ten bytes of {@link #ROW0_ORIG_TS}, the span a date filter would address. */
    private static final String ROW0_ORIG_DATE = "2022-06-10";

    // ------------------------------------------------------------------------------------------------
    // Witness row 1: the negative overpunch, and the file's second source value and second type code.
    // ------------------------------------------------------------------------------------------------

    /** Row 1 identifier. */
    private static final String ROW1_ID = "0000000001774260";

    /** Row 1 type code: the second and last of the two codes the witness file carries. */
    private static final String ROW1_TYPE_CD = "03";

    /** Row 1 category code. */
    private static final String ROW1_CAT_CD = "0001";

    /** Row 1 origination source, with the two trailing spaces that fill its ten-byte field. */
    private static final String ROW1_SOURCE = "OPERATOR  ";

    /** Row 1 description. */
    private static final String ROW1_DESC = "Return item at Nitzsche, Nicolas and Lowe";

    /**
     * Row 1 amount field, transcribed as its eleven raw bytes.
     *
     * <p>The closing brace is the negative form of the digit zero, so the eleven digits are
     * {@code 00000091900} and the value is negative: {@link #ROW1_AMOUNT}.
     */
    private static final String ROW1_AMOUNT_IMAGE = "0000009190}";

    /** Row 1 amount, decoded by hand from {@link #ROW1_AMOUNT_IMAGE}. */
    private static final BigDecimal ROW1_AMOUNT = new BigDecimal("-919.00");

    /** Row 1 merchant identifier. */
    private static final String ROW1_MERCHANT_ID = "800000000";

    /** Row 1 merchant name. */
    private static final String ROW1_MERCHANT_NAME = "Nitzsche, Nicolas and Lowe";

    /** Row 1 merchant city. */
    private static final String ROW1_MERCHANT_CITY = "Fidelshire";

    /** Row 1 merchant postal code, a five-digit code with five trailing spaces. */
    private static final String ROW1_MERCHANT_ZIP = "53378     ";

    /** Row 1 card number, whose own leading zero is significant character data. */
    private static final String ROW1_CARD_NUM = "0927987108636232";

    /** Row 1 origination stamp, identical to row 0's as it is on every witness row. */
    private static final String ROW1_ORIG_TS = "2022-06-10 19:27:53.000000";

    // ------------------------------------------------------------------------------------------------
    // Witness row 299: the hyphenated nine-digit postal code that fills all ten bytes.
    // ------------------------------------------------------------------------------------------------

    /** Row 299 identifier. */
    private static final String ROW299_ID = "0000000996722787";

    /** Row 299 type code. */
    private static final String ROW299_TYPE_CD = "01";

    /** Row 299 category code. */
    private static final String ROW299_CAT_CD = "0001";

    /** Row 299 origination source. */
    private static final String ROW299_SOURCE = "POS TERM  ";

    /** Row 299 description. */
    private static final String ROW299_DESC = "Purchase at Kilback LLC";

    /**
     * Row 299 amount field: {@code B} is the third positive form, contributing the digit two as well as
     * the sign, so the eleven digits are {@code 00000060322}.
     */
    private static final String ROW299_AMOUNT_IMAGE = "0000006032B";

    /** Row 299 amount, decoded by hand from {@link #ROW299_AMOUNT_IMAGE}. */
    private static final BigDecimal ROW299_AMOUNT = new BigDecimal("603.22");

    /** Row 299 merchant identifier. */
    private static final String ROW299_MERCHANT_ID = "800000000";

    /** Row 299 merchant name. */
    private static final String ROW299_MERCHANT_NAME = "Kilback LLC";

    /** Row 299 merchant city. */
    private static final String ROW299_MERCHANT_CITY = "Cummeratamouth";

    /**
     * Row 299 merchant postal code: a hyphenated nine-digit value filling all ten bytes. This is the
     * evidence that the field is free-form character data rather than a number, and it must pass through
     * unvalidated and unreformatted.
     */
    private static final String ROW299_MERCHANT_ZIP = "53200-7529";

    /** Row 299 card number. */
    private static final String ROW299_CARD_NUM = "3260763612337560";

    /** Row 299 origination stamp. */
    private static final String ROW299_ORIG_TS = "2022-06-10 19:27:53.000000";

    // ------------------------------------------------------------------------------------------------
    // Values shared across the witness rows, and the overpunch alphabets.
    // ------------------------------------------------------------------------------------------------

    /**
     * An unwritten processing stamp: twenty-six spaces, which is what all 300 witness rows carry and what
     * a record written before the posting run stamps it looks like. It must survive as spaces.
     */
    private static final String UNSTAMPED_PROC_TS = "                          ";

    /**
     * The trimmed form of {@link #UNSTAMPED_PROC_TS}. Twenty-six spaces trim to nothing, so the trimmed
     * form is the empty string - written as a literal so that the inequality can be asserted without any
     * trimming call, which this file does not make.
     */
    private static final String UNSTAMPED_PROC_TS_TRIMMED = "";

    /** The ten characters that overpunch a positive final digit, zero through nine in order. */
    private static final String POSITIVE_OVERPUNCH = "{ABCDEFGHI";

    /** The ten characters that overpunch a negative final digit, zero through nine in order. */
    private static final String NEGATIVE_OVERPUNCH = "}JKLMNOPQR";

    /** Ten leading digits used by the sign-alphabet sweep, so only the final byte varies. */
    private static final String SWEEP_LEADING_DIGITS = "0000012345";

    /** The integer and first decimal digit the sweep's leading digits produce, before the final digit. */
    private static final String SWEEP_VALUE_PREFIX = "1234.5";

    // ------------------------------------------------------------------------------------------------
    // This class's own oracle helpers. They reassemble a record image from transcribed field values and
    // measure everything in encoded bytes, so that no assertion depends on the code under test to
    // produce the value it then checks.
    // ------------------------------------------------------------------------------------------------

    /**
     * Reassembles a complete record image from its significant field values.
     *
     * <p>The amount arrives as its already-encoded eleven-byte field rather than as a number, so that no
     * assertion in this class depends on the codec to produce the bytes it then checks. The filler run is
     * appended as spaces, which is what the witness file carries.
     *
     * @param  id           transaction identifier
     * @param  typeCd       transaction type code
     * @param  catCd        transaction category code
     * @param  source       origination source
     * @param  desc         description
     * @param  amountImage  the eleven raw bytes of the amount field
     * @param  merchantId   merchant identifier
     * @param  merchantName merchant name
     * @param  merchantCity merchant city
     * @param  merchantZip  merchant postal code
     * @param  cardNum      card number
     * @param  origTs       origination stamp
     * @param  procTs       processing stamp, possibly all spaces
     * @return the complete image at exactly {@value #RECORD_WIDTH} encoded bytes, filler included
     */
    private static String recordImage(final String id, final String typeCd, final String catCd,
            final String source, final String desc, final String amountImage, final String merchantId,
            final String merchantName, final String merchantCity, final String merchantZip,
            final String cardNum, final String origTs, final String procTs) {
        return alphanumeric(id, WIDTH_ID)
                + alphanumeric(typeCd, WIDTH_TYPE_CD)
                + numeric(catCd, WIDTH_CAT_CD)
                + alphanumeric(source, WIDTH_SOURCE)
                + alphanumeric(desc, WIDTH_DESC)
                + amountImage
                + numeric(merchantId, WIDTH_MERCHANT_ID)
                + alphanumeric(merchantName, WIDTH_MERCHANT_NAME)
                + alphanumeric(merchantCity, WIDTH_MERCHANT_CITY)
                + alphanumeric(merchantZip, WIDTH_MERCHANT_ZIP)
                + alphanumeric(cardNum, WIDTH_CARD_NUM)
                + alphanumeric(origTs, WIDTH_ORIG_TS)
                + alphanumeric(procTs, WIDTH_PROC_TS)
                + " ".repeat(WIDTH_FILLER);
    }

    /**
     * Places a value the way a character field holds it: left-justified, space-padded to the full width.
     *
     * @param  value the significant content, already 7-bit ASCII
     * @param  width the declared field width in encoded bytes
     * @return the value at exactly {@code width} encoded bytes
     */
    private static String alphanumeric(final String value, final int width) {
        final int supplied = encodedWidth(value);
        assertThat(supplied)
                .as("the transcribed value '%s' cannot exceed its %d-byte field", value, width)
                .isLessThanOrEqualTo(width);
        return value + " ".repeat(width - supplied);
    }

    /**
     * Places a value the way an unsigned numeric field holds it: right-justified, zero-padded, so that
     * significant leading zeros are restored rather than lost.
     *
     * @param  value the significant content, already 7-bit ASCII
     * @param  width the declared field width in encoded bytes
     * @return the value at exactly {@code width} encoded bytes
     */
    private static String numeric(final String value, final int width) {
        final int supplied = encodedWidth(value);
        assertThat(supplied)
                .as("the transcribed value '%s' cannot exceed its %d-byte field", value, width)
                .isLessThanOrEqualTo(width);
        return "0".repeat(width - supplied) + value;
    }

    /** @return the reassembled image of witness row 0, whose processing stamp is unwritten */
    private static String rowZeroImage() {
        return recordImage(ROW0_ID, ROW0_TYPE_CD, ROW0_CAT_CD, ROW0_SOURCE, ROW0_DESC, ROW0_AMOUNT_IMAGE,
                ROW0_MERCHANT_ID, ROW0_MERCHANT_NAME, ROW0_MERCHANT_CITY, ROW0_MERCHANT_ZIP,
                ROW0_CARD_NUM, ROW0_ORIG_TS, UNSTAMPED_PROC_TS);
    }

    /** @return the reassembled image of witness row 1, the negatively signed operator-entered record */
    private static String rowOneImage() {
        return recordImage(ROW1_ID, ROW1_TYPE_CD, ROW1_CAT_CD, ROW1_SOURCE, ROW1_DESC, ROW1_AMOUNT_IMAGE,
                ROW1_MERCHANT_ID, ROW1_MERCHANT_NAME, ROW1_MERCHANT_CITY, ROW1_MERCHANT_ZIP,
                ROW1_CARD_NUM, ROW1_ORIG_TS, UNSTAMPED_PROC_TS);
    }

    /** @return the reassembled image of witness row 299, carrying the hyphenated postal code */
    private static String rowTwoNineNineImage() {
        return recordImage(ROW299_ID, ROW299_TYPE_CD, ROW299_CAT_CD, ROW299_SOURCE, ROW299_DESC,
                ROW299_AMOUNT_IMAGE, ROW299_MERCHANT_ID, ROW299_MERCHANT_NAME, ROW299_MERCHANT_CITY,
                ROW299_MERCHANT_ZIP, ROW299_CARD_NUM, ROW299_ORIG_TS, UNSTAMPED_PROC_TS);
    }

    /**
     * Reassembles witness row 0 with a substituted amount field, for the sign-alphabet cases.
     *
     * @param  amountImage the eleven raw bytes of the amount field to place
     * @return the complete image at exactly {@value #RECORD_WIDTH} encoded bytes
     */
    private static String rowZeroImageWithAmount(final String amountImage) {
        return recordImage(ROW0_ID, ROW0_TYPE_CD, ROW0_CAT_CD, ROW0_SOURCE, ROW0_DESC, amountImage,
                ROW0_MERCHANT_ID, ROW0_MERCHANT_NAME, ROW0_MERCHANT_CITY, ROW0_MERCHANT_ZIP,
                ROW0_CARD_NUM, ROW0_ORIG_TS, UNSTAMPED_PROC_TS);
    }

    /**
     * Reassembles witness row 0 with a written processing stamp in place of its blank one.
     *
     * <p>Every witness row ships with the stamp blank, because the file is daily input that the posting run
     * has not yet touched. A record that <em>has</em> been posted carries twenty-six bytes of content
     * there, and the report's date filter reads the leading ten of them, so both states are exercised.
     *
     * @param  procTs the twenty-six-byte processing stamp to place
     * @return the complete image at exactly {@value #RECORD_WIDTH} encoded bytes
     */
    private static String rowZeroImageWithProcessingStamp(final String procTs) {
        return recordImage(ROW0_ID, ROW0_TYPE_CD, ROW0_CAT_CD, ROW0_SOURCE, ROW0_DESC, ROW0_AMOUNT_IMAGE,
                ROW0_MERCHANT_ID, ROW0_MERCHANT_NAME, ROW0_MERCHANT_CITY, ROW0_MERCHANT_ZIP,
                ROW0_CARD_NUM, ROW0_ORIG_TS, procTs);
    }

    /**
     * Builds an entity carrying exactly witness row 0's field values, through the entity's public
     * thirteen-argument constructor.
     *
     * <p>The argument order <em>is</em> the record-image order, so this construction also serves as a check
     * that the constructor's parameter sequence matches the copybook's declaration sequence: a
     * transposition would place two fields at each other's offsets and the round-trip assertions would
     * fail. The entity's no-argument constructor is {@code protected} and reserved for the persistence
     * provider, so it is not called here.
     *
     * @return a fully populated entity, every character field at its full declared width
     */
    private static Transaction rowZeroEntity() {
        return new Transaction(alphanumeric(ROW0_ID, WIDTH_ID),
                alphanumeric(ROW0_TYPE_CD, WIDTH_TYPE_CD),
                numeric(ROW0_CAT_CD, WIDTH_CAT_CD),
                alphanumeric(ROW0_SOURCE, WIDTH_SOURCE),
                alphanumeric(ROW0_DESC, WIDTH_DESC),
                ROW0_AMOUNT,
                numeric(ROW0_MERCHANT_ID, WIDTH_MERCHANT_ID),
                alphanumeric(ROW0_MERCHANT_NAME, WIDTH_MERCHANT_NAME),
                alphanumeric(ROW0_MERCHANT_CITY, WIDTH_MERCHANT_CITY),
                alphanumeric(ROW0_MERCHANT_ZIP, WIDTH_MERCHANT_ZIP),
                alphanumeric(ROW0_CARD_NUM, WIDTH_CARD_NUM),
                alphanumeric(ROW0_ORIG_TS, WIDTH_ORIG_TS),
                alphanumeric(UNSTAMPED_PROC_TS, WIDTH_PROC_TS));
    }

    /**
     * Encodes a value to bytes, naming US-ASCII explicitly at the boundary as every width check in this
     * file does.
     *
     * @param  value the value to encode
     * @return its US-ASCII bytes
     */
    private static byte[] asciiBytes(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Measures a value the way the record measures it: in encoded bytes rather than characters. Every
     * width assertion in this file goes through here, so no character count is ever mistaken for a width.
     *
     * @param  value the value to measure
     * @return its US-ASCII encoded length
     */
    private static int encodedWidth(final String value) {
        return asciiBytes(value).length;
    }

    /**
     * Cuts a field out of an encoded image by byte offset and byte length, decoding US-ASCII explicitly.
     *
     * <p>Slicing the bytes rather than the characters is what makes an offset assertion honest: a
     * character index and a byte offset only coincide while every byte is 7-bit ASCII, and relying on
     * that coincidence is exactly the habit that lets a geometry defect hide.
     *
     * @param  image  the encoded record image
     * @param  offset zero-based byte offset of the field
     * @param  length encoded byte length of the field
     * @return the field's content
     */
    private static String byteSlice(final byte[] image, final int offset, final int length) {
        return new String(Arrays.copyOfRange(image, offset, offset + length), StandardCharsets.US_ASCII);
    }

    /**
     * Asserts all thirteen properties of a mapped entity against witness row 0's hand-transcribed values.
     *
     * <p>Shared by the reading test and by the entry-point agreement test so that both hold the mapping to
     * the same hand-derived oracle. Every expectation is composed from this class's literals and its own
     * padding helpers; nothing here consults the code under test.
     *
     * @param mapped the entity to check
     */
    private static void assertMatchesWitnessRowZero(final Transaction mapped) {
        assertThat(mapped.getTranId()).isEqualTo(alphanumeric(ROW0_ID, WIDTH_ID));
        assertThat(mapped.getTranTypeCd()).isEqualTo(alphanumeric(ROW0_TYPE_CD, WIDTH_TYPE_CD));
        assertThat(mapped.getTranCatCd()).isEqualTo(numeric(ROW0_CAT_CD, WIDTH_CAT_CD));
        assertThat(mapped.getTranSource()).isEqualTo(ROW0_SOURCE);
        assertThat(mapped.getTranDesc()).isEqualTo(alphanumeric(ROW0_DESC, WIDTH_DESC));
        assertThat(mapped.getTranAmt()).isEqualByComparingTo(ROW0_AMOUNT);
        assertThat(mapped.getMerchantId()).isEqualTo(numeric(ROW0_MERCHANT_ID, WIDTH_MERCHANT_ID));
        assertThat(mapped.getMerchantName())
                .isEqualTo(alphanumeric(ROW0_MERCHANT_NAME, WIDTH_MERCHANT_NAME));
        assertThat(mapped.getMerchantCity())
                .isEqualTo(alphanumeric(ROW0_MERCHANT_CITY, WIDTH_MERCHANT_CITY));
        assertThat(mapped.getMerchantZip()).isEqualTo(ROW0_MERCHANT_ZIP);
        assertThat(mapped.getTranCardNum()).isEqualTo(ROW0_CARD_NUM);
        assertThat(mapped.getTranOrigTs()).isEqualTo(ROW0_ORIG_TS);
        assertThat(mapped.getTranProcTs()).isEqualTo(UNSTAMPED_PROC_TS);
    }

    @Nested
    @DisplayName("the declared geometry")
    class TheDeclaredGeometry {

        @Test
        @DisplayName("all thirteen published offsets equal the zero-based positions the copybook declares, "
                + "and the filler run begins where the mapped prefix ends")
        void allThirteenPublishedOffsetsEqualTheDeclaredPositions() {
            assertThat(TransactionRecordMapper.TRAN_ID_OFFSET).isEqualTo(OFFSET_ID);
            assertThat(TransactionRecordMapper.TRAN_TYPE_CD_OFFSET).isEqualTo(OFFSET_TYPE_CD);
            assertThat(TransactionRecordMapper.TRAN_CAT_CD_OFFSET).isEqualTo(OFFSET_CAT_CD);
            assertThat(TransactionRecordMapper.TRAN_SOURCE_OFFSET).isEqualTo(OFFSET_SOURCE);
            assertThat(TransactionRecordMapper.TRAN_DESC_OFFSET).isEqualTo(OFFSET_DESC);
            assertThat(TransactionRecordMapper.TRAN_AMT_OFFSET).isEqualTo(OFFSET_AMT);
            assertThat(TransactionRecordMapper.TRAN_MERCHANT_ID_OFFSET).isEqualTo(OFFSET_MERCHANT_ID);
            assertThat(TransactionRecordMapper.TRAN_MERCHANT_NAME_OFFSET)
                    .isEqualTo(OFFSET_MERCHANT_NAME);
            assertThat(TransactionRecordMapper.TRAN_MERCHANT_CITY_OFFSET)
                    .isEqualTo(OFFSET_MERCHANT_CITY);
            assertThat(TransactionRecordMapper.TRAN_MERCHANT_ZIP_OFFSET).isEqualTo(OFFSET_MERCHANT_ZIP);
            assertThat(TransactionRecordMapper.TRAN_CARD_NUM_OFFSET).isEqualTo(OFFSET_CARD_NUM);
            assertThat(TransactionRecordMapper.TRAN_ORIG_TS_OFFSET).isEqualTo(OFFSET_ORIG_TS);
            assertThat(TransactionRecordMapper.TRAN_PROC_TS_OFFSET).isEqualTo(OFFSET_PROC_TS);
            assertThat(TransactionRecordMapper.FILLER_OFFSET).isEqualTo(OFFSET_FILLER);
        }

        @Test
        @DisplayName("all thirteen published lengths equal the encoded byte widths the copybook declares, "
                + "and the filler run is twenty bytes")
        void allThirteenPublishedLengthsEqualTheDeclaredWidths() {
            assertThat(TransactionRecordMapper.TRAN_ID_LENGTH).isEqualTo(WIDTH_ID);
            assertThat(TransactionRecordMapper.TRAN_TYPE_CD_LENGTH).isEqualTo(WIDTH_TYPE_CD);
            assertThat(TransactionRecordMapper.TRAN_CAT_CD_LENGTH).isEqualTo(WIDTH_CAT_CD);
            assertThat(TransactionRecordMapper.TRAN_SOURCE_LENGTH).isEqualTo(WIDTH_SOURCE);
            assertThat(TransactionRecordMapper.TRAN_DESC_LENGTH).isEqualTo(WIDTH_DESC);
            assertThat(TransactionRecordMapper.TRAN_AMT_LENGTH).isEqualTo(WIDTH_AMT);
            assertThat(TransactionRecordMapper.TRAN_MERCHANT_ID_LENGTH).isEqualTo(WIDTH_MERCHANT_ID);
            assertThat(TransactionRecordMapper.TRAN_MERCHANT_NAME_LENGTH)
                    .isEqualTo(WIDTH_MERCHANT_NAME);
            assertThat(TransactionRecordMapper.TRAN_MERCHANT_CITY_LENGTH)
                    .isEqualTo(WIDTH_MERCHANT_CITY);
            assertThat(TransactionRecordMapper.TRAN_MERCHANT_ZIP_LENGTH).isEqualTo(WIDTH_MERCHANT_ZIP);
            assertThat(TransactionRecordMapper.TRAN_CARD_NUM_LENGTH).isEqualTo(WIDTH_CARD_NUM);
            assertThat(TransactionRecordMapper.TRAN_ORIG_TS_LENGTH).isEqualTo(WIDTH_ORIG_TS);
            assertThat(TransactionRecordMapper.TRAN_PROC_TS_LENGTH).isEqualTo(WIDTH_PROC_TS);
            assertThat(TransactionRecordMapper.FILLER_LENGTH).isEqualTo(WIDTH_FILLER);
        }

        @Test
        @DisplayName("the record is 350 bytes: a 330-byte mapped prefix plus a 20-byte filler run, and "
                + "330 + 20 = 350 is asserted rather than asserted in prose")
        void theRecordIsThreeHundredAndFiftyBytesWide() {
            final int sumOfDeclaredWidths = WIDTH_ID + WIDTH_TYPE_CD + WIDTH_CAT_CD + WIDTH_SOURCE
                    + WIDTH_DESC + WIDTH_AMT + WIDTH_MERCHANT_ID + WIDTH_MERCHANT_NAME
                    + WIDTH_MERCHANT_CITY + WIDTH_MERCHANT_ZIP + WIDTH_CARD_NUM + WIDTH_ORIG_TS
                    + WIDTH_PROC_TS;

            assertThat(sumOfDeclaredWidths)
                    .as("the thirteen mapped widths must account for the whole mapped prefix")
                    .isEqualTo(MAPPED_WIDTH)
                    .isEqualTo(OFFSET_FILLER);
            assertThat(MAPPED_WIDTH + WIDTH_FILLER)
                    .as("the mapped prefix plus the filler run must be the whole record")
                    .isEqualTo(RECORD_WIDTH);
            assertThat(TransactionRecordMapper.RECORD_LENGTH).isEqualTo(RECORD_WIDTH);
            assertThat(TransactionRecordMapper.MAPPED_DATA_LENGTH).isEqualTo(MAPPED_WIDTH);
            assertThat(TransactionRecordMapper.MAPPED_DATA_LENGTH
                            + TransactionRecordMapper.FILLER_LENGTH)
                    .isEqualTo(TransactionRecordMapper.RECORD_LENGTH);
        }

        @Test
        @DisplayName("the amount occupies eleven bytes, nine integer digits and two decimals, its sign "
                + "overpunched into the last of them rather than carried separately")
        void theAmountOccupiesElevenBytes() {
            assertThat(TransactionRecordMapper.TRAN_AMT_LENGTH)
                    .as("a nine-digit, two-decimal display field is n + 2 bytes wide, with no separate "
                            + "sign byte and no separate decimal point")
                    .isEqualTo(WIDTH_AMT)
                    .isEqualTo(ZonedDecimalCodec.TRANSACTION_AMOUNT_WIDTH);
        }

        @Test
        @DisplayName("the filler run is emitted as the US-ASCII space byte")
        void theFillerRunIsEmittedAsTheSpaceByte() {
            assertThat(TransactionRecordMapper.FILLER_CHARACTER).isEqualTo(' ');
            assertThat((byte) TransactionRecordMapper.FILLER_CHARACTER).isEqualTo(ASCII_SPACE);
        }

        @Test
        @DisplayName("the artefact name identifies the record group and its copybook, so a diagnostic "
                + "names the layout rather than leaving it to be inferred from a width")
        void theArtefactNameIdentifiesTheRecordGroupAndItsCopybook() {
            assertThat(TransactionRecordMapper.ARTEFACT)
                    .contains("TRAN-RECORD")
                    .contains("CVTRA05Y");
        }

        @Test
        @DisplayName("this class's own three witness images are themselves exactly 350 encoded bytes, so "
                + "a fault in the oracle cannot be mistaken for a fault in the mapper")
        void theWitnessImagesAreThemselvesThreeHundredAndFiftyBytes() {
            assertThat(encodedWidth(rowZeroImage())).isEqualTo(RECORD_WIDTH);
            assertThat(encodedWidth(rowOneImage())).isEqualTo(RECORD_WIDTH);
            assertThat(encodedWidth(rowTwoNineNineImage())).isEqualTo(RECORD_WIDTH);
            assertThat(encodedWidth(ROW0_AMOUNT_IMAGE)).isEqualTo(WIDTH_AMT);
            assertThat(encodedWidth(ROW1_AMOUNT_IMAGE)).isEqualTo(WIDTH_AMT);
            assertThat(encodedWidth(ROW299_AMOUNT_IMAGE)).isEqualTo(WIDTH_AMT);
            assertThat(encodedWidth(UNSTAMPED_PROC_TS)).isEqualTo(WIDTH_PROC_TS);
            assertThat(encodedWidth(ROW0_ORIG_TS)).isEqualTo(WIDTH_ORIG_TS);
        }
    }

    @Nested
    @DisplayName("the three contractual offsets, fixed from outside this module")
    class TheThreeContractualOffsets {

        @Test
        @DisplayName("the card number sits at zero-based 262 for 16 bytes, the origination stamp at 278 "
                + "for 26, and the processing stamp at 304 for 26")
        void theThreeContractualOffsetsAndWidthsAreExactlyAsDeclared() {
            assertThat(TransactionRecordMapper.TRAN_CARD_NUM_OFFSET)
                    .as("displacing this field would mis-order the report and the statement job at once")
                    .isEqualTo(OFFSET_CARD_NUM);
            assertThat(TransactionRecordMapper.TRAN_CARD_NUM_LENGTH).isEqualTo(WIDTH_CARD_NUM);
            assertThat(TransactionRecordMapper.TRAN_ORIG_TS_OFFSET).isEqualTo(OFFSET_ORIG_TS);
            assertThat(TransactionRecordMapper.TRAN_ORIG_TS_LENGTH).isEqualTo(WIDTH_ORIG_TS);
            assertThat(TransactionRecordMapper.TRAN_PROC_TS_OFFSET).isEqualTo(OFFSET_PROC_TS);
            assertThat(TransactionRecordMapper.TRAN_PROC_TS_LENGTH).isEqualTo(WIDTH_PROC_TS);
        }

        @Test
        @DisplayName("the sort symbols TRAN-CARD-NUM at one-based 263 and TRAN-PROC-DT at one-based 305 "
                + "convert to zero-based 262 and 304: 263 - 1 = 262 and 305 - 1 = 304")
        void theOneBasedSortColumnsConvertToTheZeroBasedOffsets() {
            // A sort column is one-based and a Java offset is zero-based. Confusing the two produces code
            // that compiles and reads one byte off every record, so the arithmetic is written out rather
            // than trusted to a comment. Each column below is the literal the job stream carries.
            assertThat(SORT_COLUMN_CARD_NUM - ONE_BASED_TO_ZERO_BASED)
                    .as("TRAN-CARD-NUM: one-based 263 is zero-based 262")
                    .isEqualTo(OFFSET_CARD_NUM);
            assertThat(SORT_COLUMN_PROC_DT - ONE_BASED_TO_ZERO_BASED)
                    .as("TRAN-PROC-DT: one-based 305 is zero-based 304")
                    .isEqualTo(OFFSET_PROC_TS);
            assertThat(SORT_COLUMN_ORIG_TS - ONE_BASED_TO_ZERO_BASED)
                    .as("the reprojection's source column 279 is zero-based 278")
                    .isEqualTo(OFFSET_ORIG_TS);

            // And the mapper publishes both conventions, so the published pair must agree too.
            assertThat(TransactionRecordMapper.TRAN_CARD_NUM_ONE_BASED_SORT_POSITION)
                    .isEqualTo(SORT_COLUMN_CARD_NUM);
            assertThat(TransactionRecordMapper.TRAN_ORIG_TS_ONE_BASED_SORT_POSITION)
                    .isEqualTo(SORT_COLUMN_ORIG_TS);
            assertThat(TransactionRecordMapper.TRAN_PROC_TS_ONE_BASED_SORT_POSITION)
                    .isEqualTo(SORT_COLUMN_PROC_DT);
            assertThat(TransactionRecordMapper.TRAN_PROC_DT_ONE_BASED_SORT_POSITION)
                    .isEqualTo(SORT_COLUMN_PROC_DT);
        }

        @Test
        @DisplayName("the same sixteen bytes at one-based 263 are typed ZD by the report procedure's sort "
                + "symbol and CH by the statement job's sort fields, so an ordering comparator is per-job "
                + "and is never shared - this mapper types nothing and sorts nothing")
        void theSameSixteenBytesAreTypedZonedDecimalByOneJobAndCharacterByAnother() {
            // Recorded as a test rather than only as a comment, because the tempting refactor is to
            // extract one comparator over "the card number at 263" and hand it to both jobs. A
            // zoned-decimal collation reads the final byte as carrying a sign as well as a digit and a
            // character collation does not, so the two can order the same input differently. The mapper's
            // contribution is the offset and the width; the typing decision belongs to each job.
            assertThat(TransactionRecordMapper.TRAN_CARD_NUM_OFFSET)
                    .as("both specifications address this one offset")
                    .isEqualTo(OFFSET_CARD_NUM);
            assertThat(TransactionRecordMapper.TRAN_CARD_NUM_LENGTH)
                    .as("both specifications span this one width")
                    .isEqualTo(WIDTH_CARD_NUM);
            assertThat(TransactionRecordMapper.TRAN_CARD_NUM_ONE_BASED_SORT_POSITION)
                    .as("and both name it by the same one-based column")
                    .isEqualTo(SORT_COLUMN_CARD_NUM);

            // The field is character data in the copybook and is mapped as text, so the mapper applies no
            // numeric typing to it even though one job declares it numeric to the sort.
            assertThat(TransactionRecordMapper.fromRecord(rowZeroImage()).getTranCardNum())
                    .as("mapped as raw text: no numeric parse, no re-format, no sign interpretation")
                    .isEqualTo(ROW0_CARD_NUM);
        }

        @Test
        @DisplayName("the alternate index's key of 26 bytes at offset 304 independently corroborates the "
                + "processing stamp's offset and width")
        void theAlternateIndexKeyCorroboratesTheProcessingStamp() {
            assertThat(ALTERNATE_INDEX_KEY_OFFSET)
                    .as("the index key offset and the mapped field offset are the same byte")
                    .isEqualTo(OFFSET_PROC_TS);
            assertThat(ALTERNATE_INDEX_KEY_LENGTH)
                    .as("the index key length and the mapped field width are the same count")
                    .isEqualTo(WIDTH_PROC_TS);
            assertThat(TransactionRecordMapper.TRAN_PROC_TS_OFFSET)
                    .isEqualTo(ALTERNATE_INDEX_KEY_OFFSET);
            assertThat(TransactionRecordMapper.TRAN_PROC_TS_LENGTH)
                    .isEqualTo(ALTERNATE_INDEX_KEY_LENGTH);
        }

        @Test
        @DisplayName("the base cluster's key of 16 bytes at offset 0 is the transaction identifier itself, "
                + "so the persistent identity is that business key and never a generated surrogate")
        void theClusterKeyIsTheBusinessKeyAtOffsetZero() {
            assertThat(CLUSTER_KEY_OFFSET)
                    .as("the record begins with its key, so the key offset is zero")
                    .isEqualTo(OFFSET_ID);
            assertThat(CLUSTER_KEY_LENGTH)
                    .as("the key spans exactly the identifier field")
                    .isEqualTo(WIDTH_ID);
            assertThat(TransactionRecordMapper.TRAN_ID_OFFSET).isEqualTo(CLUSTER_KEY_OFFSET);
            assertThat(TransactionRecordMapper.TRAN_ID_LENGTH).isEqualTo(CLUSTER_KEY_LENGTH);

            // A surrogate identity would decouple the entity's key from the record image, which is what
            // the round trip below would immediately expose: the key read from the image is the key the
            // entity carries, character for character and with its leading zeros intact.
            final byte[] image = asciiBytes(rowZeroImage());
            assertThat(TransactionRecordMapper.fromRecord(image).getTranId())
                    .isEqualTo(byteSlice(image, CLUSTER_KEY_OFFSET, CLUSTER_KEY_LENGTH))
                    .isEqualTo(ROW0_ID);
        }

        @Test
        @DisplayName("TRAN-PROC-DT is the leading ten bytes of the twenty-six-byte processing stamp and "
                + "not a field of its own, so there is no separate processing-date property to read")
        void theProcessingDateIsALeadingPartAndNotAField() {
            assertThat(TransactionRecordMapper.TRAN_PROC_DT_OFFSET)
                    .as("the date shares the stamp's first byte, which is why the columns coincide")
                    .isEqualTo(OFFSET_PROC_TS)
                    .isEqualTo(TransactionRecordMapper.TRAN_PROC_TS_OFFSET);
            assertThat(TransactionRecordMapper.TRAN_PROC_DT_LENGTH)
                    .as("the symbol spans ten of the stamp's twenty-six bytes")
                    .isEqualTo(WIDTH_PROC_DT)
                    .isLessThan(WIDTH_PROC_TS);

            // The entity exposes the twenty-six-byte stamp and nothing narrower: the ten-byte span the
            // report filters on is obtained by reading the leading part of that one property. No
            // tranProcDt accessor is called here because none exists, and none is introduced.
            final String stampedImage = rowZeroImageWithProcessingStamp(ROW0_ORIG_TS);
            final String stamp = TransactionRecordMapper.fromRecord(stampedImage).getTranProcTs();

            assertThat(encodedWidth(stamp)).isEqualTo(WIDTH_PROC_TS);
            assertThat(stamp)
                    .as("the ten bytes the filter reads are a prefix of the one mapped property")
                    .startsWith(ROW0_ORIG_DATE);
            assertThat(byteSlice(asciiBytes(stampedImage), OFFSET_PROC_TS, WIDTH_PROC_DT))
                    .as("and the same ten bytes read straight out of the image agree")
                    .isEqualTo(ROW0_ORIG_DATE);
        }
    }

    @Nested
    @DisplayName("reading the offset witness")
    class ReadingTheOffsetWitness {

        @Test
        @DisplayName("all thirteen properties of witness row 0 map to their hand-transcribed values at "
                + "their full declared widths")
        void allThirteenPropertiesOfWitnessRowZeroMapToTheirHandTranscribedValues() {
            assertMatchesWitnessRowZero(TransactionRecordMapper.fromRecord(rowZeroImage()));
        }

        @Test
        @DisplayName("every field is read from the byte offset the copybook declares, checked by cutting "
                + "the same offsets out of the image independently of the mapper")
        void everyFieldIsReadFromTheDeclaredByteOffset() {
            final byte[] image = asciiBytes(rowZeroImage());
            final Transaction mapped = TransactionRecordMapper.fromRecord(image);

            assertThat(mapped.getTranId()).isEqualTo(byteSlice(image, OFFSET_ID, WIDTH_ID));
            assertThat(mapped.getTranTypeCd()).isEqualTo(byteSlice(image, OFFSET_TYPE_CD, WIDTH_TYPE_CD));
            assertThat(mapped.getTranCatCd()).isEqualTo(byteSlice(image, OFFSET_CAT_CD, WIDTH_CAT_CD));
            assertThat(mapped.getTranSource()).isEqualTo(byteSlice(image, OFFSET_SOURCE, WIDTH_SOURCE));
            assertThat(mapped.getTranDesc()).isEqualTo(byteSlice(image, OFFSET_DESC, WIDTH_DESC));
            assertThat(mapped.getMerchantId())
                    .isEqualTo(byteSlice(image, OFFSET_MERCHANT_ID, WIDTH_MERCHANT_ID));
            assertThat(mapped.getMerchantName())
                    .isEqualTo(byteSlice(image, OFFSET_MERCHANT_NAME, WIDTH_MERCHANT_NAME));
            assertThat(mapped.getMerchantCity())
                    .isEqualTo(byteSlice(image, OFFSET_MERCHANT_CITY, WIDTH_MERCHANT_CITY));
            assertThat(mapped.getMerchantZip())
                    .isEqualTo(byteSlice(image, OFFSET_MERCHANT_ZIP, WIDTH_MERCHANT_ZIP));
            assertThat(mapped.getTranCardNum())
                    .as("the contractual sixteen bytes at 262, which one-based column 263 names")
                    .isEqualTo(byteSlice(image, OFFSET_CARD_NUM, WIDTH_CARD_NUM));
            assertThat(mapped.getTranOrigTs())
                    .isEqualTo(byteSlice(image, OFFSET_ORIG_TS, WIDTH_ORIG_TS));
            assertThat(mapped.getTranProcTs())
                    .isEqualTo(byteSlice(image, OFFSET_PROC_TS, WIDTH_PROC_TS));
            assertThat(byteSlice(image, OFFSET_AMT, WIDTH_AMT))
                    .as("the amount's raw eleven bytes, whose final byte sits at zero-based 142")
                    .isEqualTo(ROW0_AMOUNT_IMAGE);
        }

        @Test
        @DisplayName("every character field arrives at its full declared encoded byte width, padding "
                + "included, because a fixed-width field is never shortened on the way in")
        void everyCharacterFieldArrivesAtItsFullDeclaredWidth() {
            final Transaction mapped = TransactionRecordMapper.fromRecord(rowZeroImage());

            assertThat(encodedWidth(mapped.getTranId())).isEqualTo(WIDTH_ID);
            assertThat(encodedWidth(mapped.getTranTypeCd())).isEqualTo(WIDTH_TYPE_CD);
            assertThat(encodedWidth(mapped.getTranCatCd())).isEqualTo(WIDTH_CAT_CD);
            assertThat(encodedWidth(mapped.getTranSource())).isEqualTo(WIDTH_SOURCE);
            assertThat(encodedWidth(mapped.getTranDesc())).isEqualTo(WIDTH_DESC);
            assertThat(encodedWidth(mapped.getMerchantId())).isEqualTo(WIDTH_MERCHANT_ID);
            assertThat(encodedWidth(mapped.getMerchantName())).isEqualTo(WIDTH_MERCHANT_NAME);
            assertThat(encodedWidth(mapped.getMerchantCity())).isEqualTo(WIDTH_MERCHANT_CITY);
            assertThat(encodedWidth(mapped.getMerchantZip())).isEqualTo(WIDTH_MERCHANT_ZIP);
            assertThat(encodedWidth(mapped.getTranCardNum())).isEqualTo(WIDTH_CARD_NUM);
            assertThat(encodedWidth(mapped.getTranOrigTs())).isEqualTo(WIDTH_ORIG_TS);
            assertThat(encodedWidth(mapped.getTranProcTs())).isEqualTo(WIDTH_PROC_TS);
        }

        @Test
        @DisplayName("padded character fields keep their padding: the source, the description, the "
                + "merchant name and city and the blank processing stamp each differ from their shortened "
                + "form, because the padding is contractual content and not whitespace to be discarded")
        void paddedCharacterFieldsDifferFromTheirShortenedForm() {
            // Each expected "shortened form" is written as a literal rather than produced by a trimming
            // call, so the inequality is asserted without this file ever shortening anything itself.
            final Transaction mapped = TransactionRecordMapper.fromRecord(rowZeroImage());

            assertThat(mapped.getTranSource())
                    .isEqualTo(ROW0_SOURCE)
                    .isNotEqualTo(ROW0_SOURCE_TRIMMED);
            assertThat(encodedWidth(mapped.getTranSource())).isEqualTo(WIDTH_SOURCE);

            assertThat(mapped.getTranDesc())
                    .startsWith(ROW0_DESC)
                    .isNotEqualTo(ROW0_DESC);
            assertThat(encodedWidth(mapped.getTranDesc())).isEqualTo(WIDTH_DESC);

            assertThat(mapped.getMerchantName())
                    .startsWith(ROW0_MERCHANT_NAME)
                    .isNotEqualTo(ROW0_MERCHANT_NAME);
            assertThat(encodedWidth(mapped.getMerchantName())).isEqualTo(WIDTH_MERCHANT_NAME);

            assertThat(mapped.getMerchantCity())
                    .startsWith(ROW0_MERCHANT_CITY)
                    .isNotEqualTo(ROW0_MERCHANT_CITY);
            assertThat(encodedWidth(mapped.getMerchantCity())).isEqualTo(WIDTH_MERCHANT_CITY);

            assertThat(mapped.getMerchantZip())
                    .isEqualTo(ROW0_MERCHANT_ZIP)
                    .isNotEqualTo(ROW0_MERCHANT_ZIP_TRIMMED);
            assertThat(encodedWidth(mapped.getMerchantZip())).isEqualTo(WIDTH_MERCHANT_ZIP);
        }

        @Test
        @DisplayName("an unwritten processing stamp arrives as exactly twenty-six spaces - not null, not "
                + "empty, not shortened - because no temporal type can hold that state")
        void anUnwrittenProcessingStampArrivesAsTwentySixSpaces() {
            final Transaction mapped = TransactionRecordMapper.fromRecord(rowZeroImage());

            assertThat(mapped.getTranProcTs())
                    .isNotNull()
                    .isEqualTo(UNSTAMPED_PROC_TS)
                    .isNotEqualTo(UNSTAMPED_PROC_TS_TRIMMED)
                    .isNotEmpty()
                    .isBlank();
            assertThat(encodedWidth(mapped.getTranProcTs()))
                    .as("twenty-six encoded bytes, every one a space")
                    .isEqualTo(WIDTH_PROC_TS);
            assertThat(asciiBytes(mapped.getTranProcTs()))
                    .hasSize(WIDTH_PROC_TS)
                    .containsOnly(ASCII_SPACE);
        }

        @Test
        @DisplayName("the origination stamp is carried as raw twenty-six-byte text and is neither parsed "
                + "nor normalised, its exact witness value preserved")
        void theOriginationStampIsCarriedAsRawText() {
            final Transaction mapped = TransactionRecordMapper.fromRecord(rowZeroImage());

            assertThat(mapped.getTranOrigTs())
                    .as("the witness carries this same value on all 300 of its rows")
                    .isEqualTo(ROW0_ORIG_TS);
            assertThat(encodedWidth(mapped.getTranOrigTs())).isEqualTo(WIDTH_ORIG_TS);
            assertThat(TransactionRecordMapper.fromRecord(rowOneImage()).getTranOrigTs())
                    .isEqualTo(ROW1_ORIG_TS);
            assertThat(TransactionRecordMapper.fromRecord(rowTwoNineNineImage()).getTranOrigTs())
                    .isEqualTo(ROW299_ORIG_TS);
        }

        @Test
        @DisplayName("leading zeros survive on the category code, the merchant identifier and both keys, "
                + "because each is carried as text rather than parsed into a number")
        void leadingZerosSurviveOnTheNumericLookingFields() {
            final Transaction fromRowZero = TransactionRecordMapper.fromRecord(rowZeroImage());
            final Transaction fromRowOne = TransactionRecordMapper.fromRecord(rowOneImage());

            assertThat(fromRowZero.getTranCatCd())
                    .as("a four-byte category code of one significant digit keeps three leading zeros")
                    .isEqualTo(ROW0_CAT_CD)
                    .startsWith("000");
            assertThat(fromRowZero.getMerchantId())
                    .as("the merchant identifier fills its field, so no zero is added or removed")
                    .isEqualTo(ROW0_MERCHANT_ID);
            assertThat(fromRowZero.getTranId())
                    .as("the sixteen-byte key is right-justified and zero-filled")
                    .isEqualTo(ROW0_ID)
                    .startsWith("0000000000");
            assertThat(fromRowOne.getTranCardNum())
                    .as("a card number beginning with a zero keeps it, which a numeric parse would lose")
                    .isEqualTo(ROW1_CARD_NUM)
                    .startsWith("0");
        }

        @Test
        @DisplayName("a hyphenated nine-digit postal code passes through unchanged, filling all ten bytes, "
                + "because the field is free-form character data and is never validated or reformatted")
        void aHyphenatedPostalCodePassesThroughUnchanged() {
            final Transaction mapped = TransactionRecordMapper.fromRecord(rowTwoNineNineImage());

            assertThat(mapped.getMerchantZip())
                    .as("witness row 299 carries a nine-digit code with its separator")
                    .isEqualTo(ROW299_MERCHANT_ZIP)
                    .contains("-");
            assertThat(encodedWidth(mapped.getMerchantZip()))
                    .as("the separator is content, so the value fills the ten-byte field exactly")
                    .isEqualTo(WIDTH_MERCHANT_ZIP);
            assertThat(byteSlice(TransactionRecordMapper.toRecordBytes(mapped), OFFSET_MERCHANT_ZIP,
                            WIDTH_MERCHANT_ZIP))
                    .as("and it is re-emitted at its own offset with the separator intact")
                    .isEqualTo(ROW299_MERCHANT_ZIP);
        }

        @Test
        @DisplayName("the four merchant properties are unprefixed on this entity - the identifier maps to "
                + "the column merchant_id - where the parallel daily-transaction entity prefixes all "
                + "thirteen; the divergence is deliberate and is preserved rather than harmonised")
        void theFourMerchantPropertiesAreUnprefixed() {
            // Recorded as behaviour rather than only as a comment. The two layouts share a geometry and are
            // deliberately not merged; tidying this asymmetry away would be the first step towards merging
            // them, and it would rename a persisted column at the same time.
            final Transaction mapped = TransactionRecordMapper.fromRecord(rowZeroImage());

            assertThat(mapped.getMerchantId()).isEqualTo(ROW0_MERCHANT_ID);
            assertThat(mapped.getMerchantName()).startsWith(ROW0_MERCHANT_NAME);
            assertThat(mapped.getMerchantCity()).startsWith(ROW0_MERCHANT_CITY);
            assertThat(mapped.getMerchantZip()).isEqualTo(ROW0_MERCHANT_ZIP);

            // The setters are unprefixed too, so a caller that mutates the entity meets the same names.
            mapped.setMerchantId(numeric(ROW299_MERCHANT_ID, WIDTH_MERCHANT_ID));
            mapped.setMerchantName(alphanumeric(ROW299_MERCHANT_NAME, WIDTH_MERCHANT_NAME));
            mapped.setMerchantCity(alphanumeric(ROW299_MERCHANT_CITY, WIDTH_MERCHANT_CITY));
            mapped.setMerchantZip(ROW299_MERCHANT_ZIP);

            assertThat(mapped.getMerchantId()).isEqualTo(ROW299_MERCHANT_ID);
            assertThat(mapped.getMerchantName()).startsWith(ROW299_MERCHANT_NAME);
            assertThat(mapped.getMerchantCity()).startsWith(ROW299_MERCHANT_CITY);
            assertThat(mapped.getMerchantZip()).isEqualTo(ROW299_MERCHANT_ZIP);
        }

        @Test
        @DisplayName("witness row 1 maps its own thirteen values, so the operator-originated source and "
                + "the second type code are exercised as well as the point-of-sale pair")
        void witnessRowOneMapsItsOwnThirteenValues() {
            final Transaction mapped = TransactionRecordMapper.fromRecord(rowOneImage());

            assertThat(mapped.getTranId()).isEqualTo(ROW1_ID);
            assertThat(mapped.getTranTypeCd()).isEqualTo(ROW1_TYPE_CD);
            assertThat(mapped.getTranCatCd()).isEqualTo(ROW1_CAT_CD);
            assertThat(mapped.getTranSource()).isEqualTo(ROW1_SOURCE);
            assertThat(mapped.getTranDesc()).isEqualTo(alphanumeric(ROW1_DESC, WIDTH_DESC));
            assertThat(mapped.getTranAmt()).isEqualByComparingTo(ROW1_AMOUNT);
            assertThat(mapped.getMerchantId()).isEqualTo(ROW1_MERCHANT_ID);
            assertThat(mapped.getMerchantName())
                    .isEqualTo(alphanumeric(ROW1_MERCHANT_NAME, WIDTH_MERCHANT_NAME));
            assertThat(mapped.getMerchantCity())
                    .isEqualTo(alphanumeric(ROW1_MERCHANT_CITY, WIDTH_MERCHANT_CITY));
            assertThat(mapped.getMerchantZip()).isEqualTo(ROW1_MERCHANT_ZIP);
            assertThat(mapped.getTranCardNum()).isEqualTo(ROW1_CARD_NUM);
            assertThat(mapped.getTranOrigTs()).isEqualTo(ROW1_ORIG_TS);
            assertThat(mapped.getTranProcTs()).isEqualTo(UNSTAMPED_PROC_TS);
        }

        @Test
        @DisplayName("witness row 299 maps its own thirteen values, so the third overpunch form and the "
                + "hyphenated postal code are exercised on a complete record")
        void witnessRowTwoNineNineMapsItsOwnThirteenValues() {
            final Transaction mapped = TransactionRecordMapper.fromRecord(rowTwoNineNineImage());

            assertThat(mapped.getTranId()).isEqualTo(ROW299_ID);
            assertThat(mapped.getTranTypeCd()).isEqualTo(ROW299_TYPE_CD);
            assertThat(mapped.getTranCatCd()).isEqualTo(ROW299_CAT_CD);
            assertThat(mapped.getTranSource()).isEqualTo(ROW299_SOURCE);
            assertThat(mapped.getTranDesc()).isEqualTo(alphanumeric(ROW299_DESC, WIDTH_DESC));
            assertThat(mapped.getTranAmt()).isEqualByComparingTo(ROW299_AMOUNT);
            assertThat(mapped.getMerchantId()).isEqualTo(ROW299_MERCHANT_ID);
            assertThat(mapped.getMerchantName())
                    .isEqualTo(alphanumeric(ROW299_MERCHANT_NAME, WIDTH_MERCHANT_NAME));
            assertThat(mapped.getMerchantCity())
                    .isEqualTo(alphanumeric(ROW299_MERCHANT_CITY, WIDTH_MERCHANT_CITY));
            assertThat(mapped.getMerchantZip()).isEqualTo(ROW299_MERCHANT_ZIP);
            assertThat(mapped.getTranCardNum()).isEqualTo(ROW299_CARD_NUM);
            assertThat(mapped.getTranOrigTs()).isEqualTo(ROW299_ORIG_TS);
            assertThat(mapped.getTranProcTs()).isEqualTo(UNSTAMPED_PROC_TS);
        }

        @Test
        @DisplayName("entity identity is the transaction identifier alone, so two records differing only "
                + "in their key are distinct and two carrying the same key are not")
        void entityIdentityIsTheTransactionIdentifierAlone() {
            final Transaction rowZero = TransactionRecordMapper.fromRecord(rowZeroImage());
            final Transaction rowOne = TransactionRecordMapper.fromRecord(rowOneImage());
            final Transaction rowZeroAgain = TransactionRecordMapper.fromRecord(rowZeroImage());

            assertThat(rowZero).isEqualTo(rowZeroAgain).hasSameHashCodeAs(rowZeroAgain);
            assertThat(rowZero).isNotEqualTo(rowOne);
        }
    }

    @Nested
    @DisplayName("the zoned-decimal amount and its overpunched sign")
    class TheZonedDecimalAmount {

        @Test
        @DisplayName("witness row 0's positive overpunch contributes a digit as well as a sign: a field "
                + "ending G is positive and its final digit is seven, giving 504.77 and not 500.47")
        void aPositiveOverpunchContributesADigitAsWellAsASign() {
            final Transaction mapped = TransactionRecordMapper.fromRecord(rowZeroImage());

            assertThat(ROW0_AMOUNT_IMAGE)
                    .as("the field as the witness file carries it")
                    .endsWith("G");
            assertThat(mapped.getTranAmt())
                    .as("G is the eighth positive form, so it carries the digit seven and the sign")
                    .isEqualByComparingTo(ROW0_AMOUNT)
                    .isPositive();
            assertThat(mapped.getTranAmt().scale())
                    .as("two implied decimals, so scale is exactly two")
                    .isEqualTo(EXPECTED_MONETARY_SCALE);
        }

        @Test
        @DisplayName("witness row 1's negative overpunch carries the sign in the final byte: a field "
                + "ending in a closing brace is negative and its final digit is zero, giving -919.00")
        void aNegativeOverpunchCarriesTheSignInTheFinalByte() {
            final Transaction mapped = TransactionRecordMapper.fromRecord(rowOneImage());

            assertThat(ROW1_AMOUNT_IMAGE).endsWith("}");
            assertThat(mapped.getTranAmt())
                    .isEqualByComparingTo(ROW1_AMOUNT)
                    .isNegative();
            assertThat(mapped.getTranAmt().scale()).isEqualTo(EXPECTED_MONETARY_SCALE);
        }

        @Test
        @DisplayName("witness row 299's field ending B decodes to 603.22, a third independent confirmation "
                + "that the trailing letter is a digit and not a sign-only byte")
        void aThirdWitnessAmountConfirmsTheOverpunchRule() {
            final Transaction mapped = TransactionRecordMapper.fromRecord(rowTwoNineNineImage());

            assertThat(ROW299_AMOUNT_IMAGE).endsWith("B");
            assertThat(mapped.getTranAmt())
                    .isEqualByComparingTo(ROW299_AMOUNT)
                    .isPositive();
            assertThat(mapped.getTranAmt().scale()).isEqualTo(EXPECTED_MONETARY_SCALE);
        }

        @Test
        @DisplayName("every decoded amount carries scale exactly two, and the module's rounding policy is "
                + "truncation toward zero because no arithmetic in the estate requests rounding")
        void everyDecodedAmountCarriesScaleExactlyTwo() {
            assertThat(TransactionRecordMapper.fromRecord(rowZeroImage()).getTranAmt().scale())
                    .isEqualTo(EXPECTED_MONETARY_SCALE);
            assertThat(TransactionRecordMapper.fromRecord(rowOneImage()).getTranAmt().scale())
                    .isEqualTo(EXPECTED_MONETARY_SCALE);
            assertThat(TransactionRecordMapper.fromRecord(rowTwoNineNineImage()).getTranAmt().scale())
                    .isEqualTo(EXPECTED_MONETARY_SCALE);
            assertThat(ZonedDecimalCodec.MONETARY_SCALE)
                    .as("the codec's published scale is pinned to the two implied decimals the picture "
                            + "clause declares, which is what lets every assertion above expect that "
                            + "literal rather than borrow the constant")
                    .isEqualTo(EXPECTED_MONETARY_SCALE);
            assertThat(ZonedDecimalCodec.COBOL_TRUNCATION_MODE.name())
                    .as("a store into a two-decimal field with no rounding clause truncates toward zero; "
                            + "a half-even policy would differ by one cent on about half of all interest "
                            + "computations, and no assertion in this file requests any rescaling")
                    .isEqualTo(EXPECTED_ROUNDING_MODE);
        }

        @Test
        @DisplayName("all twenty overpunch forms decode to the digit and the sign the convention gives "
                + "them, so no form reachable in the shipped data is unreadable")
        void allTwentyOverpunchFormsDecodeCorrectly() {
            // The witness file reaches every one of the twenty forms across its 300 rows - 250 positive and
            // 50 negative - so this sweep covers only what the estate actually produces. Each expectation
            // is composed from a decimal string literal, never from a production constant.
            for (int digit = 0; digit <= 9; digit++) {
                final char positiveForm = POSITIVE_OVERPUNCH.charAt(digit);
                final char negativeForm = NEGATIVE_OVERPUNCH.charAt(digit);

                assertThat(TransactionRecordMapper
                                .fromRecord(rowZeroImageWithAmount(SWEEP_LEADING_DIGITS + positiveForm))
                                .getTranAmt())
                        .as("the positive form of the digit %d", digit)
                        .isEqualByComparingTo(new BigDecimal(SWEEP_VALUE_PREFIX + digit));
                assertThat(TransactionRecordMapper
                                .fromRecord(rowZeroImageWithAmount(SWEEP_LEADING_DIGITS + negativeForm))
                                .getTranAmt())
                        .as("the negative form of the digit %d", digit)
                        .isEqualByComparingTo(new BigDecimal("-" + SWEEP_VALUE_PREFIX + digit));
            }
        }

        @Test
        @DisplayName("a plain trailing digit reads as a positive value, so a field written without an "
                + "overpunch is still read correctly")
        void aPlainTrailingDigitReadsAsPositive() {
            final Transaction mapped =
                    TransactionRecordMapper.fromRecord(rowZeroImageWithAmount("00000050477"));

            assertThat(mapped.getTranAmt())
                    .as("the same eleven digits witness row 0 carries, with no sign byte at all")
                    .isEqualByComparingTo(ROW0_AMOUNT)
                    .isPositive();
        }

        @Test
        @DisplayName("a negatively signed all-zero field decodes to zero at scale two, since a decimal "
                + "value has no negative zero to preserve")
        void aNegativelySignedAllZeroFieldDecodesToZero() {
            final Transaction mapped =
                    TransactionRecordMapper.fromRecord(rowZeroImageWithAmount("0000000000}"));

            assertThat(mapped.getTranAmt()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(mapped.getTranAmt().scale()).isEqualTo(EXPECTED_MONETARY_SCALE);
        }

        @Test
        @DisplayName("the amount is the record's only decimal property, and the sign of each witness row "
                + "matches its source: a point-of-sale purchase is positive, an operator return negative")
        void theAmountIsTheOnlyDecimalPropertyAndItsSignTracksTheSource() {
            final Transaction purchase = TransactionRecordMapper.fromRecord(rowZeroImage());
            final Transaction operatorReturn = TransactionRecordMapper.fromRecord(rowOneImage());

            assertThat(purchase.getTranAmt()).isInstanceOf(BigDecimal.class).isPositive();
            assertThat(purchase.getTranSource()).isEqualTo(ROW0_SOURCE);
            assertThat(operatorReturn.getTranAmt()).isInstanceOf(BigDecimal.class).isNegative();
            assertThat(operatorReturn.getTranSource()).isEqualTo(ROW1_SOURCE);

            // The source and type codes stay raw text: no enumeration translation happens in this layer.
            assertThat(purchase.getTranTypeCd()).isEqualTo(ROW0_TYPE_CD);
            assertThat(operatorReturn.getTranTypeCd()).isEqualTo(ROW1_TYPE_CD);
        }
    }

    @Nested
    @DisplayName("emitting a record image")
    class EmittingARecordImage {

        @Test
        @DisplayName("the mapped prefix from zero up to but excluding 330 is reproduced byte for byte "
                + "under US-ASCII")
        void theMappedPrefixIsReproducedByteForByte() {
            final byte[] expected = asciiBytes(rowZeroImage());
            final Transaction mapped = TransactionRecordMapper.fromRecord(expected);
            final byte[] emitted = TransactionRecordMapper.toRecordBytes(mapped);

            assertThat(Arrays.copyOfRange(emitted, 0, MAPPED_WIDTH))
                    .as("the 330-byte mapped prefix is the bound of a fixture comparison")
                    .isEqualTo(Arrays.copyOfRange(expected, 0, MAPPED_WIDTH));
        }

        @Test
        @DisplayName("the whole 350-byte image is reproduced, and the twenty filler bytes at 330 through "
                + "349 are every one the US-ASCII space byte 0x20")
        void theWholeImageIsReproducedAndTheFillerRunIsAllSpaceBytes() {
            final Transaction mapped = TransactionRecordMapper.fromRecord(rowZeroImage());
            final byte[] emitted = TransactionRecordMapper.toRecordBytes(mapped);

            assertThat(emitted).hasSize(RECORD_WIDTH).isEqualTo(asciiBytes(rowZeroImage()));
            assertThat(encodedWidth(TransactionRecordMapper.toRecord(mapped)))
                    .as("the string form measures the same 350 encoded bytes")
                    .isEqualTo(RECORD_WIDTH);
            assertThat(Arrays.copyOfRange(emitted, OFFSET_FILLER, RECORD_WIDTH))
                    .as("the unmapped run is emitted as spaces, which is what the witness file carries")
                    .hasSize(WIDTH_FILLER)
                    .containsOnly(ASCII_SPACE);
            for (int index = OFFSET_FILLER; index < RECORD_WIDTH; index++) {
                assertThat(emitted[index])
                        .as("filler byte at zero-based index %d", index)
                        .isEqualTo(ASCII_SPACE);
            }
        }

        @Test
        @DisplayName("an entity built through the public thirteen-argument constructor emits the witness "
                + "image, proving the constructor's parameter order is the copybook's declaration order")
        void anEntityBuiltThroughThePublicConstructorEmitsTheWitnessImage() {
            // A transposed parameter pair would place two values at each other's offsets, so this single
            // comparison is what holds the thirteen-argument order to the record layout.
            assertThat(TransactionRecordMapper.toRecord(rowZeroEntity())).isEqualTo(rowZeroImage());
            assertThat(TransactionRecordMapper.toRecordBytes(rowZeroEntity()))
                    .isEqualTo(asciiBytes(rowZeroImage()));
        }

        @Test
        @DisplayName("each call to the byte-emitting entry point returns a fresh array the caller may hold "
                + "and modify without disturbing the next call")
        void theByteEmitterReturnsAFreshArrayEachTime() {
            final Transaction mapped = TransactionRecordMapper.fromRecord(rowZeroImage());
            final byte[] first = TransactionRecordMapper.toRecordBytes(mapped);
            final byte[] second = TransactionRecordMapper.toRecordBytes(mapped);

            assertThat(second).isEqualTo(first).isNotSameAs(first);
        }

        @Test
        @DisplayName("an unwritten processing stamp is re-emitted as twenty-six spaces rather than being "
                + "collapsed, so a record written before the posting run round-trips unchanged")
        void anUnwrittenProcessingStampIsReEmittedAsSpaces() {
            final Transaction mapped = TransactionRecordMapper.fromRecord(rowZeroImage());
            final byte[] emitted = TransactionRecordMapper.toRecordBytes(mapped);

            assertThat(byteSlice(emitted, OFFSET_PROC_TS, WIDTH_PROC_TS))
                    .isEqualTo(UNSTAMPED_PROC_TS)
                    .isNotEqualTo(UNSTAMPED_PROC_TS_TRIMMED);
            assertThat(Arrays.copyOfRange(emitted, OFFSET_PROC_TS, OFFSET_PROC_TS + WIDTH_PROC_TS))
                    .hasSize(WIDTH_PROC_TS)
                    .containsOnly(ASCII_SPACE);
        }

        @Test
        @DisplayName("a written processing stamp round-trips as its own twenty-six bytes, so the two "
                + "states of the field are both preserved without either being parsed")
        void aWrittenProcessingStampRoundTrips() {
            final String stampedImage = rowZeroImageWithProcessingStamp(ROW0_ORIG_TS);
            final Transaction mapped = TransactionRecordMapper.fromRecord(stampedImage);

            assertThat(mapped.getTranProcTs()).isEqualTo(ROW0_ORIG_TS);
            assertThat(TransactionRecordMapper.toRecord(mapped)).isEqualTo(stampedImage);
        }

        @Test
        @DisplayName("the negatively signed witness row round-trips, its closing-brace sign byte restored "
                + "as the final byte of the amount field")
        void theNegativelySignedWitnessRowRoundTrips() {
            final Transaction mapped = TransactionRecordMapper.fromRecord(rowOneImage());
            final byte[] emitted = TransactionRecordMapper.toRecordBytes(mapped);

            assertThat(byteSlice(emitted, OFFSET_AMT, WIDTH_AMT)).isEqualTo(ROW1_AMOUNT_IMAGE);
            assertThat(byteSlice(emitted, OFFSET_AMT + WIDTH_AMT - 1, 1))
                    .as("the sign lives in the amount's last byte, at zero-based 142")
                    .isEqualTo("}");
            assertThat(emitted).isEqualTo(asciiBytes(rowOneImage()));
        }

        @Test
        @DisplayName("significant leading zeros are restored on the way out, so a category code and a "
                + "merchant identifier are right-justified and zero-filled rather than narrowed")
        void significantLeadingZerosAreRestoredOnTheWayOut() {
            final Transaction mapped = TransactionRecordMapper.fromRecord(rowZeroImage());
            final byte[] emitted = TransactionRecordMapper.toRecordBytes(mapped);

            assertThat(byteSlice(emitted, OFFSET_CAT_CD, WIDTH_CAT_CD)).isEqualTo(ROW0_CAT_CD);
            assertThat(byteSlice(emitted, OFFSET_MERCHANT_ID, WIDTH_MERCHANT_ID))
                    .isEqualTo(ROW0_MERCHANT_ID);
            assertThat(byteSlice(emitted, OFFSET_ID, WIDTH_ID)).isEqualTo(ROW0_ID);
        }

        @Test
        @DisplayName("all three witness rows round-trip to their own byte images, so the mapping is an "
                + "exact inverse over every field the layout carries")
        void allThreeWitnessRowsRoundTripToTheirOwnImages() {
            for (final String image : List.of(rowZeroImage(), rowOneImage(), rowTwoNineNineImage())) {
                assertThat(TransactionRecordMapper
                                .toRecordBytes(TransactionRecordMapper.fromRecord(image)))
                        .as("round trip of the image beginning %s", byteSlice(asciiBytes(image), 0,
                                WIDTH_ID))
                        .isEqualTo(asciiBytes(image));
            }
        }
    }

    @Nested
    @DisplayName("the three reading entry points")
    class TheThreeReadingEntryPoints {

        @Test
        @DisplayName("the string, byte-array and byte-range entry points produce equal entities from the "
                + "same bytes, all thirteen properties agreeing with the hand-transcribed witness")
        void theThreeEntryPointsProduceEqualEntities() {
            final String image = rowZeroImage();
            final byte[] bytes = asciiBytes(image);

            final Transaction fromString = TransactionRecordMapper.fromRecord(image);
            final Transaction fromArray = TransactionRecordMapper.fromRecord(bytes);
            final Transaction fromRange = TransactionRecordMapper.fromRecord(bytes, 0);

            // Each overload is held to the same hand-derived oracle rather than to one of the others, so an
            // overload that agreed with its siblings but disagreed with the record could not pass.
            assertMatchesWitnessRowZero(fromString);
            assertMatchesWitnessRowZero(fromArray);
            assertMatchesWitnessRowZero(fromRange);

            assertThat(fromArray).isEqualTo(fromString);
            assertThat(fromRange).isEqualTo(fromString);
            assertThat(TransactionRecordMapper.toRecordBytes(fromArray))
                    .isEqualTo(TransactionRecordMapper.toRecordBytes(fromString));
            assertThat(TransactionRecordMapper.toRecordBytes(fromRange))
                    .isEqualTo(TransactionRecordMapper.toRecordBytes(fromString));
        }

        @Test
        @DisplayName("the byte-range entry point selects one record out of a buffer holding several at the "
                + "witness file's 351-byte stride, leaving each line-feed terminator behind")
        void theByteRangeEntryPointSelectsOneRecordFromABuffer() {
            // A line-terminated file has a stride one greater than its record, so record i begins at
            // i * 351. Stride arithmetic belongs to the caller; this proves the mapper honours the index it
            // is given rather than assuming a record boundary of its own.
            final List<String> images = List.of(rowZeroImage(), rowOneImage(), rowTwoNineNineImage());
            final byte[] bytes = asciiBytes(String.join("\n", images) + "\n");

            assertThat(bytes)
                    .as("three records at a stride of 351 bytes each")
                    .hasSize(images.size() * WITNESS_FILE_STRIDE);

            final List<String> recovered = new ArrayList<>();
            for (int index = 0; index < images.size(); index++) {
                recovered.add(TransactionRecordMapper.toRecord(
                        TransactionRecordMapper.fromRecord(bytes, index * WITNESS_FILE_STRIDE)));
            }

            assertThat(recovered).containsExactlyElementsOf(images);
        }
    }

    @Nested
    @DisplayName("refusing a malformed image")
    class RefusingAMalformedImage {

        @Test
        @DisplayName("an image of 349 encoded bytes is refused rather than padded, and the diagnostic "
                + "names the artefact, the expected width of 350 and the actual byte length")
        void anImageOfThreeHundredAndFortyNineBytesIsRefused() {
            final byte[] full = asciiBytes(rowZeroImage());
            final String tooShort = byteSlice(full, 0, RECORD_WIDTH - 1);

            assertThat(encodedWidth(tooShort))
                    .as("one byte short of the declared width")
                    .isEqualTo(RECORD_WIDTH - 1);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TransactionRecordMapper.fromRecord(tooShort))
                    .withMessageContaining("TRAN-RECORD")
                    .withMessageContaining("CVTRA05Y")
                    .withMessageContaining(String.valueOf(RECORD_WIDTH))
                    .withMessageContaining(String.valueOf(RECORD_WIDTH - 1));
        }

        @Test
        @DisplayName("an image of 351 encoded bytes is refused rather than truncated, and the diagnostic "
                + "names an unstripped line terminator as the likely cause")
        void anImageOfThreeHundredAndFiftyOneBytesIsRefused() {
            final String tooLong = rowZeroImage() + "\n";

            assertThat(encodedWidth(tooLong))
                    .as("the witness file's stride, terminator included")
                    .isEqualTo(WITNESS_FILE_STRIDE);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TransactionRecordMapper.fromRecord(tooLong))
                    .withMessageContaining("CVTRA05Y")
                    .withMessageContaining(String.valueOf(RECORD_WIDTH))
                    .withMessageContaining(String.valueOf(WITNESS_FILE_STRIDE))
                    .withMessageContaining("terminator");
        }

        @Test
        @DisplayName("a byte array of the wrong length is refused on the same terms as a string, since the "
                + "width is measured in encoded bytes on both paths")
        void aByteArrayOfTheWrongLengthIsRefused() {
            final byte[] tooShort = Arrays.copyOfRange(asciiBytes(rowZeroImage()), 0, RECORD_WIDTH - 1);
            final byte[] tooLong = asciiBytes(rowZeroImage() + "\n");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TransactionRecordMapper.fromRecord(tooShort))
                    .withMessageContaining(String.valueOf(RECORD_WIDTH));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TransactionRecordMapper.fromRecord(tooLong))
                    .withMessageContaining(String.valueOf(RECORD_WIDTH));
        }

        @Test
        @DisplayName("a byte range that does not lie wholly inside its buffer is refused, and a negative "
                + "start index is refused too")
        void aByteRangeOutsideItsBufferIsRefused() {
            final byte[] oneRecord = asciiBytes(rowZeroImage());

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TransactionRecordMapper.fromRecord(oneRecord, 1));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TransactionRecordMapper.fromRecord(oneRecord, -1));
        }

        @Test
        @DisplayName("an absent image is refused on every reading entry point rather than yielding a partly "
                + "populated entity")
        void anAbsentImageIsRefusedOnEveryEntryPoint() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> TransactionRecordMapper.fromRecord((String) null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> TransactionRecordMapper.fromRecord((byte[]) null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> TransactionRecordMapper.fromRecord(null, 0));
        }

        @Test
        @DisplayName("an absent entity, and an entity with an absent mapped property, are both refused on "
                + "the emitting path, the diagnostic naming the field rather than the value")
        void anAbsentEntityOrPropertyIsRefusedOnTheEmittingPath() {
            final Transaction missingAmount = rowZeroEntity();
            missingAmount.setTranAmt(null);
            final Transaction missingDescription = rowZeroEntity();
            missingDescription.setTranDesc(null);
            final Transaction missingProcessingStamp = rowZeroEntity();
            missingProcessingStamp.setTranProcTs(null);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> TransactionRecordMapper.toRecord(null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> TransactionRecordMapper.toRecord(missingAmount))
                    .withMessageContaining("TRAN-AMT");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> TransactionRecordMapper.toRecordBytes(missingDescription))
                    .withMessageContaining("TRAN-DESC");
            assertThatExceptionOfType(NullPointerException.class)
                    .as("an absent stamp is refused rather than emitted as the spaces it resembles")
                    .isThrownBy(() -> TransactionRecordMapper.toRecord(missingProcessingStamp))
                    .withMessageContaining("TRAN-PROC-TS");
        }

        @Test
        @DisplayName("a value wider than its field is refused rather than silently truncated, because a "
                + "truncated value is a wrong value that looks right")
        void aValueWiderThanItsFieldIsRefused() {
            final Transaction overWideTypeCode = rowZeroEntity();
            overWideTypeCode.setTranTypeCd("010");
            final Transaction overWideCardNumber = rowZeroEntity();
            overWideCardNumber.setTranCardNum(ROW0_CARD_NUM + "0");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TransactionRecordMapper.toRecord(overWideTypeCode));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("seventeen bytes cannot be placed in the contractual sixteen at offset 262")
                    .isThrownBy(() -> TransactionRecordMapper.toRecordBytes(overWideCardNumber));
        }

        @Test
        @DisplayName("an amount needing more integer digits than the field provides is refused rather than "
                + "narrowed, a deliberate divergence from the legacy silent high-order truncation")
        void anAmountTooWideForItsFieldIsRefused() {
            final Transaction tooLarge = rowZeroEntity();
            tooLarge.setTranAmt(new BigDecimal("1234567890.12"));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("ten integer digits do not fit a nine-digit field")
                    .isThrownBy(() -> TransactionRecordMapper.toRecord(tooLarge));
        }

        @Test
        @DisplayName("an amount field carrying a character the overpunch convention does not define is "
                + "refused, the diagnostic naming the legacy field")
        void aMalformedAmountFieldIsRefused() {
            final String malformed = rowZeroImageWithAmount("0000005047*");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TransactionRecordMapper.fromRecord(malformed))
                    .withMessageContaining("TRAN-AMT");
        }
    }

    @Nested
    @DisplayName("the COSTM01 statement-work projection")
    class TheStatementWorkProjection {

        @Test
        @DisplayName("the projected geometry is card plus identifier key, 328 selected bytes and "
                + "22 bytes of fixed-record padding")
        void projectedGeometryIsPublished() {
            assertThat(TransactionRecordMapper.STATEMENT_WORK_RECORD_LENGTH).isEqualTo(350);
            assertThat(TransactionRecordMapper.STATEMENT_WORK_CARD_NUM_OFFSET).isZero();
            assertThat(TransactionRecordMapper.STATEMENT_WORK_TRAN_ID_OFFSET).isEqualTo(16);
            assertThat(TransactionRecordMapper.STATEMENT_WORK_KEY_LENGTH).isEqualTo(32);
            assertThat(TransactionRecordMapper.STATEMENT_WORK_REST_OFFSET).isEqualTo(32);
            assertThat(TransactionRecordMapper.STATEMENT_WORK_REST_LENGTH).isEqualTo(318);
            assertThat(TransactionRecordMapper.STATEMENT_WORK_TIMESTAMP_SEGMENT_OFFSET)
                    .isEqualTo(278);
            assertThat(TransactionRecordMapper.STATEMENT_WORK_PROJECTED_CONTENT_LENGTH)
                    .isEqualTo(328);
            assertThat(TransactionRecordMapper.STATEMENT_WORK_PADDING_LENGTH).isEqualTo(22);
            assertThat(TransactionRecordMapper
                    .STATEMENT_WORK_TRUNCATED_PROCESSING_TIMESTAMP_LENGTH).isEqualTo(2);
        }

        @Test
        @DisplayName("projection and parse consume the card-first frozen image and preserve the "
                + "intentional two-byte processing timestamp truncation")
        void projectionRoundTripsMappedFieldsWithTheDeclaredTruncation() {
            final Transaction source = rowZeroEntity();
            source.setTranOrigTs("2022-07-20-01.02.03.040000");
            source.setTranProcTs("2022-07-21-05.06.07.080000");
            final String canonical = TransactionRecordMapper.toRecord(source);

            final String projected =
                    TransactionRecordMapper.projectStatementWorkRecord(canonical);

            assertThat(projected).hasSize(350);
            assertThat(TransactionRecordMapper.statementWorkKey(projected))
                    .isEqualTo(source.getTranCardNum() + source.getTranId());
            assertThat(projected.substring(0, 16)).isEqualTo(source.getTranCardNum());
            assertThat(projected.substring(16, 278)).isEqualTo(canonical.substring(0, 262));
            assertThat(projected.substring(278, 328)).isEqualTo(canonical.substring(278, 328));
            assertThat(projected.substring(328)).isBlank().hasSize(22);
            assertThat(TransactionRecordMapper.toStatementWorkRecord(source)).isEqualTo(projected);

            final Transaction parsed = TransactionRecordMapper.fromStatementWorkRecord(projected);
            final Transaction parsedFromBuffer = TransactionRecordMapper.fromStatementWorkRecord(
                    ("prefix" + projected).getBytes(StandardCharsets.US_ASCII), "prefix".length());
            assertThat(parsed).usingRecursiveComparison()
                    .ignoringFields("tranProcTs")
                    .isEqualTo(source);
            assertThat(parsed.getTranProcTs())
                    .isEqualTo(source.getTranProcTs().substring(0, 24) + "  ");
            assertThat(parsedFromBuffer).usingRecursiveComparison().isEqualTo(parsed);
        }

        @Test
        @DisplayName("a malformed or absent projected record is refused before any field is parsed")
        void malformedProjectedRecordIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> TransactionRecordMapper.fromStatementWorkRecord((String) null));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TransactionRecordMapper.fromStatementWorkRecord("short"));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TransactionRecordMapper
                            .projectStatementWorkRecord("short"));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TransactionRecordMapper.statementWorkKey("short"));
        }
    }

    @Nested
    @DisplayName("the published layout is self-consistent")
    class ThePublishedLayoutIsSelfConsistent {

        @Test
        @DisplayName("the fourteen published offset and length pairs form one contiguous run from zero "
                + "with no gap and no overlap, and their widths sum to the whole 350-byte record")
        void thePublishedConstantsFormOneContiguousRun() {
            final int[][] fields = {
                {TransactionRecordMapper.TRAN_ID_OFFSET, TransactionRecordMapper.TRAN_ID_LENGTH},
                {TransactionRecordMapper.TRAN_TYPE_CD_OFFSET,
                        TransactionRecordMapper.TRAN_TYPE_CD_LENGTH},
                {TransactionRecordMapper.TRAN_CAT_CD_OFFSET,
                        TransactionRecordMapper.TRAN_CAT_CD_LENGTH},
                {TransactionRecordMapper.TRAN_SOURCE_OFFSET,
                        TransactionRecordMapper.TRAN_SOURCE_LENGTH},
                {TransactionRecordMapper.TRAN_DESC_OFFSET, TransactionRecordMapper.TRAN_DESC_LENGTH},
                {TransactionRecordMapper.TRAN_AMT_OFFSET, TransactionRecordMapper.TRAN_AMT_LENGTH},
                {TransactionRecordMapper.TRAN_MERCHANT_ID_OFFSET,
                        TransactionRecordMapper.TRAN_MERCHANT_ID_LENGTH},
                {TransactionRecordMapper.TRAN_MERCHANT_NAME_OFFSET,
                        TransactionRecordMapper.TRAN_MERCHANT_NAME_LENGTH},
                {TransactionRecordMapper.TRAN_MERCHANT_CITY_OFFSET,
                        TransactionRecordMapper.TRAN_MERCHANT_CITY_LENGTH},
                {TransactionRecordMapper.TRAN_MERCHANT_ZIP_OFFSET,
                        TransactionRecordMapper.TRAN_MERCHANT_ZIP_LENGTH},
                {TransactionRecordMapper.TRAN_CARD_NUM_OFFSET,
                        TransactionRecordMapper.TRAN_CARD_NUM_LENGTH},
                {TransactionRecordMapper.TRAN_ORIG_TS_OFFSET,
                        TransactionRecordMapper.TRAN_ORIG_TS_LENGTH},
                {TransactionRecordMapper.TRAN_PROC_TS_OFFSET,
                        TransactionRecordMapper.TRAN_PROC_TS_LENGTH},
                {TransactionRecordMapper.FILLER_OFFSET, TransactionRecordMapper.FILLER_LENGTH},
            };

            int expectedOffset = 0;
            for (final int[] field : fields) {
                assertThat(field[0])
                        .as("the field at %d must begin where the previous one ended", field[0])
                        .isEqualTo(expectedOffset);
                expectedOffset += field[1];
            }

            assertThat(expectedOffset)
                    .as("the fourteen published widths must account for the whole record")
                    .isEqualTo(RECORD_WIDTH);
            assertThat(Arrays.stream(fields).mapToInt(field -> field[1]).sum())
                    .isEqualTo(RECORD_WIDTH);
        }
    }
}
