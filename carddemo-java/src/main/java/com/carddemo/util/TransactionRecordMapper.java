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
import java.util.Objects;

/**
 * Sole holder of the 350-byte posted-transaction record layout, and of the exact two-way mapping
 * between that record image and the {@link Transaction} entity.
 *
 * <p>Layout authority is the copybook group {@code TRAN-RECORD} of {@code [app/cpy/CVTRA05Y.cpy]},
 * whose own header states a record length of 350. Offsets published here are <strong>zero-based
 * byte positions</strong> in the record image and lengths are <strong>encoded byte counts</strong>,
 * never character counts. Every offset is a named constant so that a reviewer can audit it against
 * the copybook without reading a method body, and a static initialiser re-checks the whole geometry
 * for contiguity so that a mis-typed offset cannot survive a single execution.
 *
 * <p>Two independent artefacts of the read-only legacy estate corroborate the width, and a third
 * corroborates one interior offset. {@code [app/jcl/TRANFILE.jcl]} defines the base cluster with a
 * record size of 350 fixed and a key of 16 bytes at offset 0. {@code [app/jcl/TRANIDX.jcl]} defines
 * the alternate index with the same record size and a key of 26 bytes at offset 304.
 * {@code [app/cbl/CBTRN03C.cbl]} splits the same record three ways in its file description - a
 * 304-byte prefix, a 26-byte processing timestamp and a 20-byte filler - and those three parts sum
 * to 350, which independently confirms both the record width and the offset of the processing
 * timestamp.
 *
 * <h2>The record, field by field</h2>
 *
 * <table>
 * <caption>The 350-byte posted-transaction record, in declaration order</caption>
 * <tr><th scope="col">#</th><th scope="col">Legacy field</th><th scope="col">Picture</th>
 *     <th scope="col">Offset</th><th scope="col">Length</th>
 *     <th scope="col">Entity property</th></tr>
 * <tr><td>1</td><td>{@code TRAN-ID}</td><td>{@code X(16)}</td><td>0</td><td>16</td>
 *     <td>{@code tranId}, the persistent identity</td></tr>
 * <tr><td>2</td><td>{@code TRAN-TYPE-CD}</td><td>{@code X(02)}</td><td>16</td><td>2</td>
 *     <td>{@code tranTypeCd}, a raw code</td></tr>
 * <tr><td>3</td><td>{@code TRAN-CAT-CD}</td><td>{@code 9(04)}</td><td>18</td><td>4</td>
 *     <td>{@code tranCatCd}, carried as text</td></tr>
 * <tr><td>4</td><td>{@code TRAN-SOURCE}</td><td>{@code X(10)}</td><td>22</td><td>10</td>
 *     <td>{@code tranSource}, raw, trailing blanks significant</td></tr>
 * <tr><td>5</td><td>{@code TRAN-DESC}</td><td>{@code X(100)}</td><td>32</td><td>100</td>
 *     <td>{@code tranDesc}</td></tr>
 * <tr><td>6</td><td>{@code TRAN-AMT}</td><td>{@code S9(09)V99}</td><td>132</td><td>11</td>
 *     <td>{@code tranAmt}, a {@link BigDecimal} at scale 2</td></tr>
 * <tr><td>7</td><td>{@code TRAN-MERCHANT-ID}</td><td>{@code 9(09)}</td><td>143</td><td>9</td>
 *     <td>{@code merchantId}, column {@code merchant_id}, unprefixed</td></tr>
 * <tr><td>8</td><td>{@code TRAN-MERCHANT-NAME}</td><td>{@code X(50)}</td><td>152</td><td>50</td>
 *     <td>{@code merchantName}</td></tr>
 * <tr><td>9</td><td>{@code TRAN-MERCHANT-CITY}</td><td>{@code X(50)}</td><td>202</td><td>50</td>
 *     <td>{@code merchantCity}</td></tr>
 * <tr><td>10</td><td>{@code TRAN-MERCHANT-ZIP}</td><td>{@code X(10)}</td><td>252</td><td>10</td>
 *     <td>{@code merchantZip}, free-form, never numeric</td></tr>
 * <tr><td>11</td><td>{@code TRAN-CARD-NUM}</td><td>{@code X(16)}</td><td>262</td><td>16</td>
 *     <td>{@code tranCardNum}; one-based 263 to an external sort</td></tr>
 * <tr><td>12</td><td>{@code TRAN-ORIG-TS}</td><td>{@code X(26)}</td><td>278</td><td>26</td>
 *     <td>{@code tranOrigTs}, a raw {@link String}</td></tr>
 * <tr><td>13</td><td>{@code TRAN-PROC-TS}</td><td>{@code X(26)}</td><td>304</td><td>26</td>
 *     <td>{@code tranProcTs}, a raw {@link String}, may be all blanks</td></tr>
 * <tr><td>-</td><td>{@code FILLER}</td><td>{@code X(20)}</td><td>330</td><td>20</td>
 *     <td>not mapped, not persisted</td></tr>
 * </table>
 *
 * <p>The width arithmetic is explicit and is asserted rather than asserted-in-prose:
 * {@code 16 + 2 + 4 + 10 + 100 + 11 + 9 + 50 + 50 + 10 + 16 + 26 + 26 = 330} mapped bytes, and
 * {@code 330 + 20 = 350}, so {@link #MAPPED_DATA_LENGTH} plus {@link #FILLER_LENGTH} equals
 * {@link #RECORD_LENGTH}. Both sums are re-derived in a static initialiser.
 *
 * <h2>Three offsets are contract rather than implementation detail</h2>
 *
 * <p>Most offsets above concern nobody but this class. Three are addressed by <em>absolute
 * position</em> from outside the program that owns the record, by an external sort and by a
 * cluster definition, so a field reordering that left every Java file compiling would still break a
 * job that never mentions Java. Those three are published as constants precisely so that the
 * ordering built in the {@code batch} package consults this class rather than re-deriving a position
 * from a copybook listing.
 *
 * <table>
 * <caption>External consumers that address this record by absolute position</caption>
 * <tr><th scope="col">Consumer</th><th scope="col">Specification</th>
 *     <th scope="col">Field addressed</th></tr>
 * <tr><td>{@code [app/proc/TRANREPT.prc]}</td><td>sort symbol {@code TRAN-CARD-NUM,263,16,ZD}</td>
 *     <td>card number, one-based 263, typed <strong>zoned decimal</strong>, ordered ascending</td></tr>
 * <tr><td>{@code [app/proc/TRANREPT.prc]}</td><td>sort symbol {@code TRAN-PROC-DT,305,10,CH}</td>
 *     <td>the leading 10 bytes of the processing timestamp, one-based 305, typed
 *         <strong>character</strong>, used by an inclusive date-range include condition</td></tr>
 * <tr><td>{@code [app/jcl/CREASTMT.JCL]}</td><td>sort fields {@code (263,16,CH,A,1,16,CH,A)}</td>
 *     <td>the <strong>same</strong> sixteen bytes at one-based 263, typed
 *         <strong>character</strong>, then the transaction identifier</td></tr>
 * <tr><td>{@code [app/jcl/TRANIDX.jcl]}</td><td>alternate index key of 26 bytes at 304</td>
 *     <td>processing timestamp, zero-based 304, non-unique, upgraded with the base cluster</td></tr>
 * </table>
 *
 * <p><strong>The one-based to zero-based conversions, stated explicitly</strong>, because an
 * off-by-one between the two conventions is the commonest defect in fixed-width work. A sort
 * position is one-based; a Java offset is zero-based; the sort position is therefore always the Java
 * offset plus one. One-based 263 is zero-based 262, which is {@link #TRAN_CARD_NUM_OFFSET}.
 * One-based 305 is zero-based 304, which is {@link #TRAN_PROC_TS_OFFSET}. One-based 279 is
 * zero-based 278, which is {@link #TRAN_ORIG_TS_OFFSET}. Each of those three relationships is
 * asserted in the static initialiser, so the published pair can never drift apart.
 *
 * <p><strong>The processing date is not a field of this record.</strong> The sort symbol at
 * one-based 305 for 10 bytes names the <em>leading ten bytes</em> of the 26-byte processing
 * timestamp, not an independent item: 305 one-based is exactly where the timestamp begins, and the
 * copybook declares no separate date. {@link #TRAN_PROC_DT_OFFSET} therefore equals
 * {@link #TRAN_PROC_TS_OFFSET} by construction and is published only so that a date-range predicate
 * can be written against the same authority as the rest of the layout.
 *
 * <p><strong>The same sixteen bytes are typed differently by two different jobs, and that is why an
 * ordering comparator must never be shared.</strong> The copybook declares the card number as
 * character data; the report procedure declares it to the sort as zoned decimal; the statement job
 * declares the identical bytes as character. A zoned-decimal collation and a character collation are
 * not interchangeable - among other things a zoned-decimal reading interprets the final byte as
 * carrying a sign - so the two orderings can disagree on the same input. Nothing in the estate
 * reconciles them, so nothing here reconciles them either: this class publishes the offset and the
 * width, and the job that needs an ordering owns its own comparator. <strong>No comparator, no sort
 * and no ordering appears in this class.</strong>
 *
 * <h2>The amount is zoned decimal, and it truncates</h2>
 *
 * <p>{@code TRAN-AMT} is a signed display-usage item of nine digits before the decimal point and two
 * after, which occupies <strong>eleven encoded bytes</strong> - one ASCII byte per digit, with no
 * separate sign byte, no separate decimal point and no packed representation. The sign is
 * <em>overpunched</em> into the final digit byte, which carries a digit and a sign together: the ten
 * positive digits zero through nine are written {@code {}, {@code A} through {@code I}, and the ten
 * negative digits zero through nine are written {@code }}, {@code J} through {@code R}.
 *
 * <p>All decoding and encoding of that field goes through {@link ZonedDecimalCodec}, the module's
 * single point of decimal truth. <strong>Truncation toward zero is mandatory and {@code HALF_EVEN}
 * and {@code HALF_UP} are forbidden.</strong> The evidence is a verified absence: the keyword that
 * requests rounding occurs <strong>zero times</strong> across every program and copybook of the
 * estate, and a COBOL arithmetic store into a two-decimal receiving field without that keyword
 * truncates rather than rounds. Half-even would differ by one cent on roughly half of all interest
 * computations, and it would do so invisibly to any test written under the same wrong assumption,
 * which is exactly why the policy lives in one place. This class consequently <strong>never calls
 * {@code setScale}, never names a rounding mode and never performs arithmetic</strong> on a decoded
 * amount; no binary floating-point type appears anywhere in it.
 *
 * <p><strong>No packed-decimal decoder is required.</strong> Packed usage occurs zero times in
 * {@code app/cpy}: every persisted monetary field in the estate is zoned decimal under display
 * usage, so the overpunch convention above is the whole of the numeric representation problem.
 *
 * <p>One asymmetry of the target type is bridged rather than accepted: a {@link BigDecimal} cannot
 * carry a negative zero, so this mapper reads and writes the amount through the codec's <em>signed</em>
 * entry points and carries the missing bit on the entity's transient negative-zero marker. An all-zero
 * image with a negative overpunch therefore re-emits with the negative overpunch, so a read followed by
 * a write reproduces the byte the record held.
 *
 * <h2>Fixture evidence, verified by measurement</h2>
 *
 * <p><strong>The persisted transaction table starts empty.</strong> Unlike the account, card and
 * customer masters, no sample file populates it in {@code V3__seed_reference_data.sql}, so a query
 * against a freshly migrated database legitimately returns nothing; rows arrive only from the posting
 * job, the interest run and the online bill-payment path. The offset witness for this layout is
 * therefore the daily-transaction fixture {@code [app/data/ASCII/dailytran.txt]}, whose layout is
 * field-for-field parallel to this
 * one at identical offsets and identical widths. That file is 105,300 bytes, which is exactly 300
 * records at a stride of 351 - the record width plus one line-feed byte. <strong>The line feed is a
 * terminator and never record content</strong>, so a caller reading lines must exclude it.
 *
 * <table>
 * <caption>Slices of the parallel fixture at the offsets published here, rows 0, 250 and 299</caption>
 * <tr><th scope="col">Offset</th><th scope="col">Observed</th></tr>
 * <tr><td>0</td><td>a sixteen-character transaction identifier with leading zeros</td></tr>
 * <tr><td>16</td><td>{@code 01}</td></tr>
 * <tr><td>18</td><td>{@code 0001}</td></tr>
 * <tr><td>22</td><td>a ten-byte source code, each of the two observed values carrying two trailing
 *     spaces: 250 records point-of-sale and 50 operator-originated</td></tr>
 * <tr><td>32</td><td>a hundred-byte description</td></tr>
 * <tr><td>132</td><td>{@code 0000005047G}, {@code 0000000349I} and {@code 0000006032B}, decoding to
 *     504.77, 34.99 and 603.22 - see the correction below</td></tr>
 * <tr><td>143</td><td>{@code 800000000}</td></tr>
 * <tr><td>152</td><td>a fifty-byte merchant name</td></tr>
 * <tr><td>202</td><td>a fifty-byte merchant city</td></tr>
 * <tr><td>252</td><td>{@code 72112} followed by five spaces, and {@code 53200-7529} - a hyphenated
 *     nine-digit postal code, which is why this field is free-form and never numeric</td></tr>
 * <tr><td>262</td><td>sixteen-digit card numbers, confirming that one-based 263 is this field</td></tr>
 * <tr><td>278</td><td>a twenty-six-character timestamp of the form
 *     {@code 2022-06-10 19:27:53.000000}, identical on all 300 records</td></tr>
 * <tr><td>304</td><td><strong>twenty-six spaces</strong>, on all 300 records</td></tr>
 * <tr><td>330</td><td>twenty spaces</td></tr>
 * </table>
 *
 * <p><strong>A correction, recorded because the wrong figure is the more plausible one.</strong> The
 * first image above is sometimes reported as decoding to 500.47. It does not. The trailing
 * {@code G} carries a digit as well as a sign, so the eleven digits are {@code 0000005047} followed
 * by {@code 7}, and at scale two that is <strong>504.77</strong>. Reading the trailing letter as a
 * sign-only byte is what produces 500.47, and the same misreading applied to the other two images
 * would contradict their values, which do check out under the correct rule: {@code 0000000349I} is
 * 34.99 and {@code 0000006032B} is 603.22. The corrected figure is the one this module asserts
 * throughout.
 *
 * <p><strong>Every one of the twenty overpunch characters appears in the shipped data</strong>, so
 * both signed directions are exercised by seeded records alone with no synthetic fixture. Counted
 * over the trailing byte of all 300 amounts, at offset 142: the positive characters
 * {@code {}, {@code A} through {@code I} occur 25, 28, 29, 30, 29, 23, 21, 24, 17 and 24 times, for
 * <strong>250 positive</strong>; the negative characters {@code }}, {@code J} through {@code R}
 * occur 6, 3, 5, 5, 6, 2, 4, 7, 4 and 8 times, for <strong>50 negative</strong>.
 *
 * <h2>Four field-level contracts that each look like something to tidy up</h2>
 *
 * <p><strong>A twenty-six-space processing timestamp is legitimate and must be tolerated.</strong>
 * An unposted record carries a blank processing timestamp - it is blank until the posting job stamps
 * it - and every one of the 300 seeded parallel records is blank in exactly that way. The value is
 * carried across as a raw twenty-six-byte string and must round-trip as twenty-six spaces. It is
 * never parsed, never defaulted, never rejected and never replaced by an epoch or by an empty
 * string.
 *
 * <p><strong>Both timestamps stay strings.</strong> Neither is converted to a date-time type, and
 * no date-time parsing of any kind happens here. The observed form is a timestamp with six
 * fractional digits, but an all-blank value is equally legal, and no temporal type can represent
 * blank; a temporal type would therefore fail on the seeded data or invent a value. Keeping the raw
 * text also preserves the byte image on which output parity depends.
 *
 * <p><strong>Numeric-looking identifiers stay strings.</strong> The transaction identifier, the
 * category code, the merchant identifier and the card number are right-justified, zero-filled
 * character data of fixed width, not integers: {@code 0001} and {@code 800000000} both carry
 * significant leading zeros. None is ever parsed to a Java integral type and re-formatted, because
 * that round trip loses the leading zeros and the fixed width with them.
 *
 * <p><strong>The merchant postal code is free-form.</strong> The shipped data carries both a
 * five-digit code padded with spaces and a hyphenated nine-digit code, so the field is not parseable
 * as a number at all and is never normalised, re-padded or re-punctuated.
 *
 * <h2>The filler run, and the exact bound for a fixture comparison</h2>
 *
 * <p>The encode direction emits the twenty-byte filler as spaces, which is what the parallel fixture
 * carries. That choice is a default rather than a certainty: filler declared with no initialising
 * clause is uninitialised, so no byte value is canonical, and the estate's own fixtures disagree -
 * the four master files carry space filler while the four reference-table files carry ASCII-zero
 * filler. That divergence is recorded as source anomaly 20 and is neither propagated into new logic
 * nor silently corrected.
 *
 * <p><strong>Every fixture round-trip assertion for this layout therefore compares only the mapped
 * data prefix, from zero up to but excluding {@value #MAPPED_DATA_LENGTH}, and never the whole
 * record.</strong> Bytes at and beyond that point are not this mapper's to guarantee.
 *
 * <h2>Deliberate absences, each a boundary rather than an oversight</h2>
 *
 * <ul>
 *   <li><strong>No comparator, sort or ordering</strong> - the three external sort specifications
 *       become per-job comparators and range predicates in the {@code batch} package, for the typing
 *       reason given above.</li>
 *   <li><strong>No arithmetic and no re-scaling</strong> - the over-limit basis is evaluated strictly
 *       left to right and the interest expression multiplies before it divides, both in the
 *       {@code service} package. Because every store truncates, re-ordering either expression would
 *       move the truncation point and change the cent, so neither may be rearranged and neither
 *       belongs here.</li>
 *   <li><strong>No date or timestamp parsing</strong>, and no date-time import.</li>
 *   <li><strong>No validation</strong> - no type or category lookup, no merchant check, no
 *       card-number checksum and no reject-reason assignment; the reject reason codes and the
 *       430-byte reject record belong to the posting step.</li>
 *   <li><strong>No enumeration translation</strong> - the source code stays a raw ten-byte value.</li>
 *   <li><strong>No statement-work projection of any kind.</strong> The 328-of-350-byte COSTM01
 *       reprojection that {@code [app/jcl/CREASTMT.JCL]} declares has exactly one authority, and it is
 *       {@link StatementWorkRecordMapper}, not this class. An earlier revision carried a second, fully
 *       parallel copy of that projection here - its own constants, projector, parser and key reader -
 *       and the two happened to agree byte for byte, which is precisely the hazard: either could have
 *       been changed alone and nothing would have failed to compile. This class publishes the canonical
 *       350-byte layout that the projection reads FROM, and nothing else.</li>
 *   <li><strong>No optimistic-lock counter</strong> - this entity declares none, because a posted
 *       transaction is inserted and read, never edited in place.</li>
 *   <li><strong>No logging</strong> - this package is not among the module's configured logger
 *       names, so a logger here would be unconfigured; emit-then-abend logging is a caller
 *       obligation.</li>
 *   <li><strong>No persistence</strong> - no repository, no entity manager, no transaction.</li>
 * </ul>
 *
 * <h2>Decision-log entries raised by this file</h2>
 *
 * <ol>
 *   <li>Monetary values are decoded and encoded at scale two with truncation toward zero, never
 *       half-even and never half-up, because the rounding keyword occurs zero times across the
 *       estate and a store without it truncates.</li>
 *   <li>The same sixteen bytes at one-based 263 are typed zoned decimal by the report procedure and
 *       character by the statement job, so ordering comparators are per-job and are never shared.</li>
 *   <li>The sort symbol at one-based 305 is the leading ten bytes of the twenty-six-byte processing
 *       timestamp, not an independent field.</li>
 *   <li>Both timestamps are preserved as raw twenty-six-byte strings rather than temporal types,
 *       because the seeded processing timestamp is twenty-six spaces and no temporal type can
 *       represent that.</li>
 *   <li>The posted-transaction table is empty after the reference-data seed, so the daily-transaction
 *       fixture is the offset witness for this layout; the two layouts are field-for-field
 *       parallel.</li>
 *   <li>The statement job's 328-of-350-byte reprojection truncates the processing timestamp by two
 *       bytes. The job owns when it occurs and {@link StatementWorkRecordMapper} owns the projection
 *       and parse mechanics; this class owns neither.</li>
 *   <li>The merchant identifier maps to the column {@code merchant_id}, unprefixed, diverging from
 *       the parallel daily-transaction entity whose every column carries a prefix. The divergence is
 *       preserved rather than corrected.</li>
 *   <li>Filler bytes are not uniform across the estate's fixtures - a recorded source anomaly - so
 *       the encode direction emits spaces and every fixture round-trip assertion compares only the
 *       mapped prefix.</li>
 *   <li>A record image of the wrong encoded width raises {@link IllegalArgumentException} rather than
 *       any exception type of this module's own exception package, for the reason given on
 *       {@link #fromRecord(String)}.</li>
 * </ol>
 *
 * <h2>Shape</h2>
 *
 * <p>Stateless and thread safe: the class is final, its only constructor is private and raises, every
 * member is static and immutable, and there is no mutable static state. Every slice and every
 * placement goes through {@link FixedWidthFieldReader}, so there is no {@code substring} call here,
 * no annotation-driven mapping, no reflection and no generated code - the module's reflection budget
 * is zero, which is the whole reason this class is written by hand. Character fields are copied raw:
 * nothing is trimmed, stripped, case folded, re-padded or normalised.
 *
 * <p><strong>No character encoding decision is taken here, and no platform default is ever
 * reachable.</strong> Every conversion between text and bytes on every path through this class is
 * delegated to {@link FixedWidthFieldReader}, which names US-ASCII explicitly at each boundary and
 * <em>rejects</em> a character it cannot represent rather than transcoding it - so a stray character
 * can never shift this record's byte geometry by silently becoming a question mark. Delegating rather
 * than repeating that choice is what makes the guarantee checkable: this file imports no charset at
 * all, so there is no second place for the decision to drift.
 *
 * <p>Provenance: translated from the estate at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}; the copybook trailer records the upstream release
 * stamp {@code CardDemo_v1.0-15-g27d6c6f-68}, dated 2022-07-19. The legacy tree is read-only
 * reference: no source text from it is reproduced here, so traceability cites member names, field
 * names, pictures, widths, offsets and positions only.
 *
 * @see Transaction
 * @see ZonedDecimalCodec
 * @see FixedWidthFieldReader
 */
public final class TransactionRecordMapper {

    /** Layout name carried into every diagnostic; it names both the record group and the copybook. */
    public static final String ARTEFACT = "TRAN-RECORD (CVTRA05Y)";

    /**
     * Encoded byte width of the whole record image, excluding any line terminator. Corroborated by
     * the base cluster's fixed record size, by the alternate index's record size and by the reporting
     * program's own three-way file-description split.
     */
    public static final int RECORD_LENGTH = 350;

    /**
     * Encoded byte width of the mapped data prefix - every field this class maps, and nothing else.
     * The remaining bytes up to {@link #RECORD_LENGTH} are the unmapped filler run.
     *
     * <p>This is also the exclusive upper bound for a fixture round-trip comparison: compare
     * {@code [0, 330)} and never the whole record, because the filler byte is not uniform across the
     * estate's fixtures.
     */
    public static final int MAPPED_DATA_LENGTH = 330;

    /** Zero-based offset of the transaction identifier, the record key and the persistent identity. */
    public static final int TRAN_ID_OFFSET = 0;

    /** Encoded byte length of the transaction identifier. */
    public static final int TRAN_ID_LENGTH = 16;

    /** Zero-based offset of the transaction type code. */
    public static final int TRAN_TYPE_CD_OFFSET = 16;

    /** Encoded byte length of the transaction type code. */
    public static final int TRAN_TYPE_CD_LENGTH = 2;

    /** Zero-based offset of the transaction category code, whose leading zeros are significant. */
    public static final int TRAN_CAT_CD_OFFSET = 18;

    /** Encoded byte length of the transaction category code. */
    public static final int TRAN_CAT_CD_LENGTH = 4;

    /** Zero-based offset of the source code, whose trailing blanks are significant. */
    public static final int TRAN_SOURCE_OFFSET = 22;

    /** Encoded byte length of the source code. */
    public static final int TRAN_SOURCE_LENGTH = 10;

    /** Zero-based offset of the description. */
    public static final int TRAN_DESC_OFFSET = 32;

    /** Encoded byte length of the description. */
    public static final int TRAN_DESC_LENGTH = 100;

    /**
     * Zero-based offset of the amount, a signed zoned-decimal field whose sign is overpunched into
     * its final byte, at {@code TRAN_AMT_OFFSET + TRAN_AMT_LENGTH - 1}.
     */
    public static final int TRAN_AMT_OFFSET = 132;

    /**
     * Encoded byte length of the amount: nine digits before the decimal point and two after, in
     * eleven bytes, with no separate sign byte and no separate decimal point.
     *
     * <p>Taken from {@link ZonedDecimalCodec} rather than restated, so that the field width and the
     * codec that reads it can never disagree.
     */
    public static final int TRAN_AMT_LENGTH = ZonedDecimalCodec.TRANSACTION_AMOUNT_WIDTH;

    /**
     * Zero-based offset of the merchant identifier, whose leading zeros are significant and which
     * maps to the column {@code merchant_id}, unprefixed.
     */
    public static final int TRAN_MERCHANT_ID_OFFSET = 143;

    /** Encoded byte length of the merchant identifier. */
    public static final int TRAN_MERCHANT_ID_LENGTH = 9;

    /** Zero-based offset of the merchant name. */
    public static final int TRAN_MERCHANT_NAME_OFFSET = 152;

    /** Encoded byte length of the merchant name. */
    public static final int TRAN_MERCHANT_NAME_LENGTH = 50;

    /** Zero-based offset of the merchant city. */
    public static final int TRAN_MERCHANT_CITY_OFFSET = 202;

    /** Encoded byte length of the merchant city. */
    public static final int TRAN_MERCHANT_CITY_LENGTH = 50;

    /**
     * Zero-based offset of the merchant postal code, which is free-form character data: the shipped
     * data carries a hyphenated nine-digit value as well as a space-padded five-digit one, so this
     * field is never treated as numeric.
     */
    public static final int TRAN_MERCHANT_ZIP_OFFSET = 252;

    /** Encoded byte length of the merchant postal code. */
    public static final int TRAN_MERCHANT_ZIP_LENGTH = 10;

    /**
     * <strong>Contractual.</strong> Zero-based (Java) offset of the card number. The equivalent
     * one-based (external sort) position is {@link #TRAN_CARD_NUM_ONE_BASED_SORT_POSITION}, which is
     * this value plus one.
     */
    public static final int TRAN_CARD_NUM_OFFSET = 262;

    /** Encoded byte length of the card number, the same 16 bytes both external sorts address. */
    public static final int TRAN_CARD_NUM_LENGTH = 16;

    /**
     * <strong>Contractual.</strong> Zero-based (Java) offset of the origination timestamp. The
     * equivalent one-based (external sort) position is
     * {@link #TRAN_ORIG_TS_ONE_BASED_SORT_POSITION}.
     */
    public static final int TRAN_ORIG_TS_OFFSET = 278;

    /** Encoded byte length of the origination timestamp, carried as raw text. */
    public static final int TRAN_ORIG_TS_LENGTH = 26;

    /**
     * <strong>Contractual.</strong> Zero-based (Java) offset of the processing timestamp, and the key
     * offset of the record's alternate index. The equivalent one-based (external sort) position is
     * {@link #TRAN_PROC_TS_ONE_BASED_SORT_POSITION}.
     */
    public static final int TRAN_PROC_TS_OFFSET = 304;

    /**
     * Encoded byte length of the processing timestamp, carried as raw text and legitimately all
     * blanks until the posting job stamps it. Also the alternate index's key length.
     */
    public static final int TRAN_PROC_TS_LENGTH = 26;

    /**
     * Zero-based (Java) offset of the processing <em>date</em> the report's date-range filter
     * addresses, which is the leading part of the processing timestamp and <strong>not a separate
     * field</strong>. Equal to {@link #TRAN_PROC_TS_OFFSET} by construction, and asserted to be so.
     */
    public static final int TRAN_PROC_DT_OFFSET = 304;

    /**
     * Encoded byte length of the processing date the report's date-range filter addresses: the
     * leading 10 bytes of the {@value #TRAN_PROC_TS_LENGTH}-byte processing timestamp.
     */
    public static final int TRAN_PROC_DT_LENGTH = 10;

    /**
     * <strong>One-based (external sort) position</strong> of the card number, 263, as declared by the
     * report procedure's sort symbol and by the statement job's sort fields. The zero-based (Java)
     * equivalent is {@link #TRAN_CARD_NUM_OFFSET}.
     *
     * <p>Published because the two jobs disagree about this field's <em>type</em> - one declares these
     * bytes zoned decimal and the other declares them character - so an ordering built on this
     * position must be per-job and must never be shared.
     */
    public static final int TRAN_CARD_NUM_ONE_BASED_SORT_POSITION = 263;

    /**
     * <strong>One-based (external sort) position</strong> of the origination timestamp, 279, as
     * addressed by the statement job's record reprojection. The zero-based (Java) equivalent is
     * {@link #TRAN_ORIG_TS_OFFSET}.
     */
    public static final int TRAN_ORIG_TS_ONE_BASED_SORT_POSITION = 279;

    /**
     * <strong>One-based (external sort) position</strong> of the processing timestamp, 305. The
     * zero-based (Java) equivalent is {@link #TRAN_PROC_TS_OFFSET}, which is also the alternate
     * index's key offset.
     */
    public static final int TRAN_PROC_TS_ONE_BASED_SORT_POSITION = 305;

    /**
     * <strong>One-based (external sort) position</strong> of the processing date the report's
     * inclusive date-range include condition addresses, 305 - identical to
     * {@link #TRAN_PROC_TS_ONE_BASED_SORT_POSITION}, because the date is the leading
     * {@value #TRAN_PROC_DT_LENGTH} bytes of the timestamp rather than a field of its own.
     */
    public static final int TRAN_PROC_DT_ONE_BASED_SORT_POSITION = 305;

    /** Zero-based offset of the unmapped trailing filler run; equal to {@link #MAPPED_DATA_LENGTH}. */
    public static final int FILLER_OFFSET = 330;

    /** Encoded byte length of the unmapped trailing filler run. */
    public static final int FILLER_LENGTH = 20;

    /**
     * Byte the encode direction writes across the filler run. Space, which is what the parallel
     * fixture carries; no byte value is canonical, because the filler is declared uninitialised.
     */
    public static final char FILLER_CHARACTER = ' ';

    /** Legacy field name, used only to make a diagnostic name its subject. */
    private static final String TRAN_ID = "TRAN-ID";

    /** Legacy field name, used only to make a diagnostic name its subject. */
    private static final String TRAN_TYPE_CD = "TRAN-TYPE-CD";

    /** Legacy field name, used only to make a diagnostic name its subject. */
    private static final String TRAN_CAT_CD = "TRAN-CAT-CD";

    /** Legacy field name, used only to make a diagnostic name its subject. */
    private static final String TRAN_SOURCE = "TRAN-SOURCE";

    /** Legacy field name, used only to make a diagnostic name its subject. */
    private static final String TRAN_DESC = "TRAN-DESC";

    /** Legacy field name, used only to make a diagnostic name its subject. */
    private static final String TRAN_AMT = "TRAN-AMT";

    /** Legacy field name, used only to make a diagnostic name its subject. */
    private static final String TRAN_MERCHANT_ID = "TRAN-MERCHANT-ID";

    /** Legacy field name, used only to make a diagnostic name its subject. */
    private static final String TRAN_MERCHANT_NAME = "TRAN-MERCHANT-NAME";

    /** Legacy field name, used only to make a diagnostic name its subject. */
    private static final String TRAN_MERCHANT_CITY = "TRAN-MERCHANT-CITY";

    /** Legacy field name, used only to make a diagnostic name its subject. */
    private static final String TRAN_MERCHANT_ZIP = "TRAN-MERCHANT-ZIP";

    /** Legacy field name, used only to make a diagnostic name its subject. */
    private static final String TRAN_CARD_NUM = "TRAN-CARD-NUM";

    /** Legacy field name, used only to make a diagnostic name its subject. */
    private static final String TRAN_ORIG_TS = "TRAN-ORIG-TS";

    /** Legacy field name, used only to make a diagnostic name its subject. */
    private static final String TRAN_PROC_TS = "TRAN-PROC-TS";

    /** Name of the unmapped trailing run, used only in the geometry assertions. */
    private static final String FILLER = "FILLER";

    /**
     * Difference between a one-based external-sort position and the zero-based Java offset of the same
     * byte. Named rather than written as a bare literal, because this single byte of difference is the
     * commonest defect in fixed-width work and the geometry assertions below check it explicitly.
     */
    private static final int ONE_BASED_ADJUSTMENT = 1;

    /**
     * Verifies the declared geometry once, at class initialisation, so that a mis-typed figure fails
     * on first use rather than producing a plausible but wrong object.
     *
     * <p>Three families of check run here. The mapped fields must be contiguous from zero with no gap
     * and no overlap, and must sum to the declared prefix width, which must in turn sum with the
     * filler run to the record width. Each published one-based sort position must be exactly one more
     * than the zero-based offset of the same byte, which is what keeps the two conventions from
     * drifting apart. And the processing date must remain what the copybook makes it - the leading
     * bytes of the processing timestamp, at the same offset and no longer than it - rather than
     * becoming a field in its own right.
     */
    static {
        requireContiguous(TRAN_ID, TRAN_ID_OFFSET, 0);
        requireContiguous(TRAN_TYPE_CD, TRAN_TYPE_CD_OFFSET, TRAN_ID_OFFSET + TRAN_ID_LENGTH);
        requireContiguous(TRAN_CAT_CD, TRAN_CAT_CD_OFFSET,
                TRAN_TYPE_CD_OFFSET + TRAN_TYPE_CD_LENGTH);
        requireContiguous(TRAN_SOURCE, TRAN_SOURCE_OFFSET, TRAN_CAT_CD_OFFSET + TRAN_CAT_CD_LENGTH);
        requireContiguous(TRAN_DESC, TRAN_DESC_OFFSET, TRAN_SOURCE_OFFSET + TRAN_SOURCE_LENGTH);
        requireContiguous(TRAN_AMT, TRAN_AMT_OFFSET, TRAN_DESC_OFFSET + TRAN_DESC_LENGTH);
        requireContiguous(TRAN_MERCHANT_ID, TRAN_MERCHANT_ID_OFFSET, TRAN_AMT_OFFSET + TRAN_AMT_LENGTH);
        requireContiguous(TRAN_MERCHANT_NAME, TRAN_MERCHANT_NAME_OFFSET,
                TRAN_MERCHANT_ID_OFFSET + TRAN_MERCHANT_ID_LENGTH);
        requireContiguous(TRAN_MERCHANT_CITY, TRAN_MERCHANT_CITY_OFFSET,
                TRAN_MERCHANT_NAME_OFFSET + TRAN_MERCHANT_NAME_LENGTH);
        requireContiguous(TRAN_MERCHANT_ZIP, TRAN_MERCHANT_ZIP_OFFSET,
                TRAN_MERCHANT_CITY_OFFSET + TRAN_MERCHANT_CITY_LENGTH);
        requireContiguous(TRAN_CARD_NUM, TRAN_CARD_NUM_OFFSET,
                TRAN_MERCHANT_ZIP_OFFSET + TRAN_MERCHANT_ZIP_LENGTH);
        requireContiguous(TRAN_ORIG_TS, TRAN_ORIG_TS_OFFSET,
                TRAN_CARD_NUM_OFFSET + TRAN_CARD_NUM_LENGTH);
        requireContiguous(TRAN_PROC_TS, TRAN_PROC_TS_OFFSET,
                TRAN_ORIG_TS_OFFSET + TRAN_ORIG_TS_LENGTH);
        requireContiguous(FILLER, FILLER_OFFSET, TRAN_PROC_TS_OFFSET + TRAN_PROC_TS_LENGTH);
        requireSum("mapped data prefix", MAPPED_DATA_LENGTH,
                TRAN_PROC_TS_OFFSET + TRAN_PROC_TS_LENGTH);
        requireSum("record image", RECORD_LENGTH, MAPPED_DATA_LENGTH + FILLER_LENGTH);
        requireSum("filler run offset", FILLER_OFFSET, MAPPED_DATA_LENGTH);

        requireOneBasedPosition(TRAN_CARD_NUM, TRAN_CARD_NUM_ONE_BASED_SORT_POSITION,
                TRAN_CARD_NUM_OFFSET);
        requireOneBasedPosition(TRAN_ORIG_TS, TRAN_ORIG_TS_ONE_BASED_SORT_POSITION,
                TRAN_ORIG_TS_OFFSET);
        requireOneBasedPosition(TRAN_PROC_TS, TRAN_PROC_TS_ONE_BASED_SORT_POSITION,
                TRAN_PROC_TS_OFFSET);
        requireOneBasedPosition("TRAN-PROC-DT", TRAN_PROC_DT_ONE_BASED_SORT_POSITION,
                TRAN_PROC_DT_OFFSET);
        requireSum("processing date offset", TRAN_PROC_DT_OFFSET, TRAN_PROC_TS_OFFSET);
        requireLeadingPartOf("TRAN-PROC-DT", TRAN_PROC_DT_LENGTH, TRAN_PROC_TS, TRAN_PROC_TS_LENGTH);
    }

    /**
     * Never instantiable. The class is a layout, and a per-instance copy of a layout would let two
     * callers disagree about a record the legacy estate defines exactly once, so an instance is made
     * impossible rather than merely discouraged.
     */
    private TransactionRecordMapper() {
        throw new AssertionError("TransactionRecordMapper is a static contract and is not instantiable");
    }

    /**
     * Maps a complete record image, supplied as a string, onto a fully populated entity.
     *
     * <p>The image must be exactly {@value #RECORD_LENGTH} <strong>encoded bytes</strong> and must
     * exclude any line terminator: a fixture line is one byte shorter than the file's stride, the
     * difference being the separator, which is never record content. The width is measured in encoded
     * bytes rather than characters, and it is checked before a single field is sliced, so a mis-sized
     * record can never yield a partially mapped object. Character fields are copied verbatim -
     * untrimmed, not case folded, not re-padded, not normalised - and the amount is decoded through
     * {@link ZonedDecimalCodec} at scale two with truncation toward zero.
     *
     * <p><strong>Why a wrong width raises {@link IllegalArgumentException} and not one of this
     * module's own exception types.</strong> None of them models "the caller handed me the wrong
     * number of bytes", and the condition has no legacy antecedent at all: the legacy records are
     * fixed length by construction, so the programs never had a wrong-length record to handle. The
     * check is a Java-only defensive guard against a caller defect, which makes the platform's own
     * precondition exception the honest choice. The image is never silently padded, never silently
     * truncated, never partially mapped and never mapped to {@code null}.
     *
     * <p>An invalid overpunch character in the amount is the codec's contract rather than this
     * class's, so the codec's own failure propagates unwrapped: its diagnostic already names the
     * offending byte, and re-wrapping it would hide that.
     *
     * @param  recordImage the whole record image, excluding any line terminator
     * @return a fully populated entity, never partially mapped
     * @throws NullPointerException     if {@code recordImage} is {@code null}
     * @throws IllegalArgumentException if the encoded width is not exactly {@value #RECORD_LENGTH},
     *                                  if a character cannot be represented in US-ASCII, or if the
     *                                  amount is not a valid zoned-decimal image
     */
    public static Transaction fromRecord(String recordImage) {
        Objects.requireNonNull(recordImage, ARTEFACT + " record image must not be null");
        // Measured through the reader's own encoded-length operation, never through String.length(),
        // because a character count is not a width authority. The reader re-asserts the same invariant
        // when it takes ownership of the image; that repetition is deliberate, since the reader's
        // invariant has to hold however it was constructed.
        requireRecordWidth(FixedWidthFieldReader.encodedLength(recordImage));
        return fromReader(FixedWidthFieldReader.of(ARTEFACT, recordImage, RECORD_LENGTH));
    }

    /**
     * Maps a complete record image, supplied as bytes, onto a fully populated entity.
     *
     * <p>Preferred when the caller already holds raw bytes, because it removes any need to choose a
     * charset. The array is only read: it is neither retained nor modified. Semantics are otherwise
     * exactly those of {@link #fromRecord(String)}.
     *
     * @param  recordImage the whole record image as bytes, excluding any line terminator
     * @return a fully populated entity, never partially mapped
     * @throws NullPointerException     if {@code recordImage} is {@code null}
     * @throws IllegalArgumentException if the length is not exactly {@value #RECORD_LENGTH}, if any
     *                                  byte is not 7-bit ASCII, or if the amount is not a valid
     *                                  zoned-decimal image
     */
    public static Transaction fromRecord(byte[] recordImage) {
        Objects.requireNonNull(recordImage, ARTEFACT + " record image must not be null");
        requireRecordWidth(recordImage.length);
        return fromReader(FixedWidthFieldReader.of(ARTEFACT, recordImage, RECORD_LENGTH));
    }

    /**
     * Maps one record held inside a larger byte buffer onto a fully populated entity.
     *
     * <p>The seam for a batch reader holding many records, or a whole newline-terminated file, in one
     * buffer. Such a file has a stride one greater than the record width, so record <em>i</em> begins
     * at {@code i * (RECORD_LENGTH + 1)}, which selects the record and leaves its separator behind.
     * Stride arithmetic and file access stay with the caller; the geometry check - that the range lies
     * wholly inside the buffer - is made by {@link FixedWidthFieldReader}, which reports whether the
     * fault was a negative index or a range overrunning the buffer. The record width is deliberately
     * not re-checked here, because the buffer is legitimately longer than one record.
     *
     * @param  buffer the buffer containing the record, and possibly many others
     * @param  from   zero-based index within {@code buffer} at which the record starts
     * @return a fully populated entity, never partially mapped
     * @throws NullPointerException     if {@code buffer} is {@code null}
     * @throws IllegalArgumentException if {@code from} is negative, if the record range is not wholly
     *                                  inside {@code buffer}, if any byte in it is not 7-bit ASCII,
     *                                  or if the amount is not a valid zoned-decimal image
     */
    public static Transaction fromRecord(byte[] buffer, int from) {
        Objects.requireNonNull(buffer, ARTEFACT + " record buffer must not be null");
        return fromReader(FixedWidthFieldReader.of(ARTEFACT, buffer, from, RECORD_LENGTH));
    }

    /**
     * Renders an entity as its canonical {@value #RECORD_LENGTH}-byte record image, as a string
     * carrying no line terminator.
     *
     * <p>The exact inverse of {@link #fromRecord(String)} over the mapped fields, and the padding is
     * part of the contract rather than a convenience: character fields are placed left-justified and
     * space-padded, so a value that already fills its field - including one that is entirely spaces,
     * such as an unstamped processing timestamp - is placed unchanged; numeric fields are placed
     * right-justified and zero-padded, so significant leading zeros are restored rather than lost; the
     * amount is encoded with its sign overpunched into its final byte; and the filler run is emitted
     * as {@link #FILLER_CHARACTER}.
     *
     * <p><strong>Compare only {@code [0, }{@value #MAPPED_DATA_LENGTH}{@code )} in a fixture
     * assertion</strong>, because the filler byte beyond that point is not uniform across the
     * estate's fixtures and is therefore not this mapper's to guarantee.
     *
     * <p>An amount needing more integer digits than the field provides is rejected by the codec
     * rather than narrowed, which is a deliberate divergence from the legacy behaviour of dropping
     * high-order digits silently: a silently narrowed amount is a wrong amount that looks right.
     *
     * @param  record the entity to render; no mapped property may be {@code null}
     * @return the record image, exactly {@value #RECORD_LENGTH} encoded bytes wide
     * @throws NullPointerException     if {@code record} or any mapped property is {@code null}
     * @throws IllegalArgumentException if a character value is wider than its field or cannot be
     *                                  represented in US-ASCII, or if the amount needs more digits
     *                                  than its field provides
     */
    public static String toRecord(Transaction record) {
        return toReader(record).image();
    }

    /**
     * Renders an entity as its canonical {@value #RECORD_LENGTH}-byte record image, as bytes.
     *
     * <p>Byte-for-byte identical to {@link #toRecord(Transaction)}, and offered so that a writer need
     * not choose a charset. The array is fresh and unshared, and it carries no line terminator:
     * record separation belongs to the writer.
     *
     * @param  record the entity to render; no mapped property may be {@code null}
     * @return a new array of exactly {@value #RECORD_LENGTH} US-ASCII bytes
     * @throws NullPointerException     if {@code record} or any mapped property is {@code null}
     * @throws IllegalArgumentException on exactly the same conditions as
     *                                  {@link #toRecord(Transaction)}
     */
    public static byte[] toRecordBytes(Transaction record) {
        return toReader(record).toByteArray();
    }

    /**
     * Reads the thirteen mapped fields at their declared offsets and builds the entity.
     *
     * <p>Populated through the entity's all-arguments constructor, whose parameter order is the
     * record-image order. That is the only route available and it is also the right one: the entity's
     * no-argument constructor is {@code protected} and reserved for the persistence provider, so it is
     * unreachable from this package, and building the object in one step means a partially populated
     * entity is never published. The entity's mutators are plain assignments and would work equally
     * well for a caller that already holds an instance.
     *
     * <p>Timestamps and identifiers are carried across as text exactly as read - no parsing, no
     * trimming, no conversion - and the filler run at {@link #FILLER_OFFSET} is deliberately not read
     * at all.
     */
    private static Transaction fromReader(FixedWidthFieldReader reader) {
        ZonedDecimalCodec.ZonedValue amount = decodeAmount(reader);
        Transaction record = new Transaction(
                reader.field(TRAN_ID, TRAN_ID_OFFSET, TRAN_ID_LENGTH),
                reader.field(TRAN_TYPE_CD, TRAN_TYPE_CD_OFFSET, TRAN_TYPE_CD_LENGTH),
                reader.field(TRAN_CAT_CD, TRAN_CAT_CD_OFFSET, TRAN_CAT_CD_LENGTH),
                reader.field(TRAN_SOURCE, TRAN_SOURCE_OFFSET, TRAN_SOURCE_LENGTH),
                reader.field(TRAN_DESC, TRAN_DESC_OFFSET, TRAN_DESC_LENGTH),
                amount.value(),
                reader.field(TRAN_MERCHANT_ID, TRAN_MERCHANT_ID_OFFSET, TRAN_MERCHANT_ID_LENGTH),
                reader.field(TRAN_MERCHANT_NAME, TRAN_MERCHANT_NAME_OFFSET, TRAN_MERCHANT_NAME_LENGTH),
                reader.field(TRAN_MERCHANT_CITY, TRAN_MERCHANT_CITY_OFFSET, TRAN_MERCHANT_CITY_LENGTH),
                reader.field(TRAN_MERCHANT_ZIP, TRAN_MERCHANT_ZIP_OFFSET, TRAN_MERCHANT_ZIP_LENGTH),
                reader.field(TRAN_CARD_NUM, TRAN_CARD_NUM_OFFSET, TRAN_CARD_NUM_LENGTH),
                reader.field(TRAN_ORIG_TS, TRAN_ORIG_TS_OFFSET, TRAN_ORIG_TS_LENGTH),
                reader.field(TRAN_PROC_TS, TRAN_PROC_TS_OFFSET, TRAN_PROC_TS_LENGTH));
        // The sign bit the amount cannot carry. Set after construction because it is not part of the
        // record-image constructor's parameter order and is not persisted state.
        record.setTranAmtNegativeZero(amount.negativeZero());
        return record;
    }

    /**
     * Places the thirteen mapped fields and the filler run, in declaration order, and completes the
     * image.
     *
     * <p>The placements add up to the full record width on purpose, because that is what makes a
     * missing field visible during review: the builder rejects any placement falling outside the
     * record, so an offset that does not add up cannot survive a single execution. Which placement a
     * field gets is a layout fact rather than a preference - the four right-justified, zero-padded
     * fields are the ones the copybook declares as unsigned numeric, plus the amount, whose
     * zoned-decimal image is likewise right-justified and zero-filled with its sign overpunched.
     */
    private static FixedWidthFieldReader toReader(Transaction record) {
        Objects.requireNonNull(record, ARTEFACT + " source entity must not be null");
        return FixedWidthFieldReader.builder(ARTEFACT, RECORD_LENGTH)
                .putAlphanumeric(TRAN_ID, TRAN_ID_OFFSET, TRAN_ID_LENGTH,
                        requirePresent(record.getTranId(), TRAN_ID))
                .putAlphanumeric(TRAN_TYPE_CD, TRAN_TYPE_CD_OFFSET, TRAN_TYPE_CD_LENGTH,
                        requirePresent(record.getTranTypeCd(), TRAN_TYPE_CD))
                .putNumeric(TRAN_CAT_CD, TRAN_CAT_CD_OFFSET, TRAN_CAT_CD_LENGTH,
                        requirePresent(record.getTranCatCd(), TRAN_CAT_CD))
                .putAlphanumeric(TRAN_SOURCE, TRAN_SOURCE_OFFSET, TRAN_SOURCE_LENGTH,
                        requirePresent(record.getTranSource(), TRAN_SOURCE))
                .putAlphanumeric(TRAN_DESC, TRAN_DESC_OFFSET, TRAN_DESC_LENGTH,
                        requirePresent(record.getTranDesc(), TRAN_DESC))
                .putNumeric(TRAN_AMT, TRAN_AMT_OFFSET, TRAN_AMT_LENGTH,
                        encodeAmount(record.getTranAmt(), record.isTranAmtNegativeZero()))
                .putNumeric(TRAN_MERCHANT_ID, TRAN_MERCHANT_ID_OFFSET, TRAN_MERCHANT_ID_LENGTH,
                        requirePresent(record.getMerchantId(), TRAN_MERCHANT_ID))
                .putAlphanumeric(TRAN_MERCHANT_NAME, TRAN_MERCHANT_NAME_OFFSET,
                        TRAN_MERCHANT_NAME_LENGTH,
                        requirePresent(record.getMerchantName(), TRAN_MERCHANT_NAME))
                .putAlphanumeric(TRAN_MERCHANT_CITY, TRAN_MERCHANT_CITY_OFFSET,
                        TRAN_MERCHANT_CITY_LENGTH,
                        requirePresent(record.getMerchantCity(), TRAN_MERCHANT_CITY))
                .putAlphanumeric(TRAN_MERCHANT_ZIP, TRAN_MERCHANT_ZIP_OFFSET,
                        TRAN_MERCHANT_ZIP_LENGTH,
                        requirePresent(record.getMerchantZip(), TRAN_MERCHANT_ZIP))
                .putAlphanumeric(TRAN_CARD_NUM, TRAN_CARD_NUM_OFFSET, TRAN_CARD_NUM_LENGTH,
                        requirePresent(record.getTranCardNum(), TRAN_CARD_NUM))
                .putAlphanumeric(TRAN_ORIG_TS, TRAN_ORIG_TS_OFFSET, TRAN_ORIG_TS_LENGTH,
                        requirePresent(record.getTranOrigTs(), TRAN_ORIG_TS))
                .putAlphanumeric(TRAN_PROC_TS, TRAN_PROC_TS_OFFSET, TRAN_PROC_TS_LENGTH,
                        requirePresent(record.getTranProcTs(), TRAN_PROC_TS))
                .putFiller(FILLER_OFFSET, FILLER_LENGTH, FILLER_CHARACTER)
                .build();
    }

    /**
     * Slices the amount and decodes it at the canonical monetary scale, together with the one bit of sign
     * an amount cannot carry.
     *
     * <p>The <em>signed</em> codec entry point, deliberately. A negatively-signed all-zero image differs
     * from a positively-signed one only in its final byte, and the plain entry point discards that
     * difference, so a round trip would re-emit {@code '{'} where the record held {@code '}'}. The bit
     * travels on the entity as a transient marker - the only place it can travel, since neither
     * {@link java.math.BigDecimal} nor a numeric column has a negative zero - and this mapper is its only
     * producer and consumer.
     *
     * <p>The scale and the rounding policy remain the codec's, not this class's: nothing here calls
     * {@code setScale}, names a rounding mode or performs arithmetic.
     */
    private static ZonedDecimalCodec.ZonedValue decodeAmount(FixedWidthFieldReader reader) {
        return ZonedDecimalCodec.decodeSigned(
                reader.field(TRAN_AMT, TRAN_AMT_OFFSET, TRAN_AMT_LENGTH), TRAN_AMT_LENGTH,
                ZonedDecimalCodec.MONETARY_SCALE, TRAN_AMT);
    }

    /**
     * Encodes the amount into its declared width, sign overpunched into the final byte.
     *
     * <p>The value is passed to the codec exactly as the entity holds it. It is not rescaled here, so
     * a value the entity carries at a different scale is the codec's to accept or reject; that keeps
     * one decision in one place. The entity's negative-zero marker is passed alongside it and applies
     * only while the amount is zero, because a non-zero amount already carries its own sign.
     */
    private static String encodeAmount(BigDecimal amount, boolean negativeZero) {
        BigDecimal present = requirePresent(amount, TRAN_AMT);
        return ZonedDecimalCodec.encodeSigned(
                new ZonedDecimalCodec.ZonedValue(present, negativeZero && present.signum() == 0),
                TRAN_AMT_LENGTH, ZonedDecimalCodec.MONETARY_SCALE, TRAN_AMT);
    }

    /**
     * Rejects an image whose encoded byte length is not exactly the declared record width.
     *
     * <p>A Java-only defensive guard with no legacy antecedent, as {@link #fromRecord(String)}
     * explains. When the overshoot is exactly one byte the message names an unstripped record
     * separator as the likely cause, because that is by far the commonest way a caller arrives here.
     *
     * @param actualEncodedLength the encoded byte length the caller actually supplied
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
                    .append("terminator: the parallel fixture's stride is ")
                    .append(RECORD_LENGTH + 1)
                    .append(" because the terminator separates records and is never record content)");
        }
        throw new IllegalArgumentException(message.toString());
    }

    /**
     * Rejects an absent property on the encoding path, naming the field rather than the value.
     *
     * @param  <T>       type of the property being checked
     * @param  value     the property value to check
     * @param  fieldName legacy field name, used only in the diagnostic
     * @return {@code value}, when it is present
     */
    private static <T> T requirePresent(T value, String fieldName) {
        return Objects.requireNonNull(value, ARTEFACT + " field '" + fieldName
                + "' must be present: a fixed-width record has no concept of an absent field, and"
                + " emitting spaces for one would produce a record of the right width and the wrong"
                + " content");
    }

    /**
     * Verifies that a field begins exactly where the previous one ended, with no gap and no overlap.
     *
     * @param fieldName      legacy field name, used only in the diagnostic
     * @param declaredOffset the offset this class publishes for the field
     * @param computedOffset the offset the preceding widths sum to
     */
    private static void requireContiguous(String fieldName, int declaredOffset, int computedOffset) {
        if (declaredOffset != computedOffset) {
            throw new IllegalStateException(ARTEFACT + " layout is inconsistent: field '" + fieldName
                    + "' declares offset " + declaredOffset + " but the widths of the fields before it"
                    + " sum to " + computedOffset);
        }
    }

    /**
     * Verifies that a set of declared widths sums to the width it is required to fill.
     *
     * @param subject  what is being summed, for the diagnostic
     * @param declared the figure this class publishes
     * @param computed the figure its parts actually sum to
     */
    private static void requireSum(String subject, int declared, int computed) {
        if (declared != computed) {
            throw new IllegalStateException(ARTEFACT + " layout is inconsistent: the " + subject
                    + " is published as " + declared + " encoded bytes but its parts sum to "
                    + computed);
        }
    }

    /**
     * Verifies that a published one-based external-sort position is exactly one more than the
     * zero-based Java offset of the same byte.
     *
     * <p>This is the check that keeps the two addressing conventions from drifting apart. Both figures
     * are published, and a reader who confuses them produces code that compiles and reads one byte
     * off, so the relationship is asserted rather than trusted to the Javadoc.
     *
     * @param fieldName        legacy field name, used only in the diagnostic
     * @param oneBasedPosition the one-based position an external sort addresses
     * @param zeroBasedOffset  the zero-based offset this class publishes for the same byte
     */
    private static void requireOneBasedPosition(String fieldName, int oneBasedPosition,
            int zeroBasedOffset) {
        if (oneBasedPosition != zeroBasedOffset + ONE_BASED_ADJUSTMENT) {
            throw new IllegalStateException(ARTEFACT + " layout is inconsistent: field '" + fieldName
                    + "' publishes one-based sort position " + oneBasedPosition + " and zero-based"
                    + " offset " + zeroBasedOffset + ", but a one-based position must be exactly "
                    + ONE_BASED_ADJUSTMENT + " more than the zero-based offset of the same byte");
        }
    }

    /**
     * Verifies that a derived field is genuinely a leading part of the field it is carved out of,
     * rather than a field in its own right.
     *
     * @param derivedName    name of the derived field, used only in the diagnostic
     * @param derivedLength  encoded byte length the derived field claims
     * @param enclosingName  name of the field it is carved out of
     * @param enclosingLength encoded byte length of that enclosing field
     */
    private static void requireLeadingPartOf(String derivedName, int derivedLength,
            String enclosingName, int enclosingLength) {
        if (derivedLength <= 0 || derivedLength > enclosingLength) {
            throw new IllegalStateException(ARTEFACT + " layout is inconsistent: field '" + derivedName
                    + "' claims " + derivedLength + " encoded bytes, which cannot be a leading part of"
                    + " the " + enclosingLength + " encoded bytes of '" + enclosingName + "'");
        }
    }
}
