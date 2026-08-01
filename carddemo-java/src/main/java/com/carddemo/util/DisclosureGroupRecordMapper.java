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

import com.carddemo.domain.DisclosureGroup;
import com.carddemo.domain.id.DisclosureGroupId;

/**
 * Maps between the legacy 50-byte disclosure-group record image and {@link DisclosureGroup}, in both
 * directions, by explicit byte offset and with no reflection of any kind.
 *
 * <p><strong>Legacy antecedent.</strong> The layout is declared as {@code DIS-GROUP-RECORD} in
 * {@code [app/cpy/CVTRA02Y.cpy]}, whose header states a record length of 50, with the three key
 * components gathered into a nested {@code DIS-GROUP-KEY} group. The cluster definition in
 * {@code [app/jcl/DISCGRP.jcl]} corroborates both figures independently: it declares
 * {@code KEYS(16 0)}, a 16-byte key at offset zero, and {@code RECORDSIZE(50 50)}, a fixed 50-byte
 * record. The 50 and the 16 are therefore each attested twice, from a copybook and from a job
 * stream that were written independently of one another.</p>
 *
 * <table>
 * <caption>The authoritative 50-byte layout, zero-based</caption>
 * <tr><th scope="col">#</th><th scope="col">COBOL field</th><th scope="col">Picture</th>
 *     <th scope="col">Offset</th><th scope="col">Length</th><th scope="col">Java property</th></tr>
 * <tr><td>&mdash;</td><td>{@code DIS-GROUP-KEY} (group)</td><td>&mdash;</td>
 *     <td>0</td><td>16</td><td>the {@code @IdClass} composite, {@link DisclosureGroupId}</td></tr>
 * <tr><td>1</td><td>{@code DIS-ACCT-GROUP-ID}</td><td>{@code X(10)}</td>
 *     <td>0</td><td>10</td><td>{@code disAcctGroupId} ({@link String}, key part 1)</td></tr>
 * <tr><td>2</td><td>{@code DIS-TRAN-TYPE-CD}</td><td>{@code X(02)}</td>
 *     <td>10</td><td>2</td><td>{@code disTranTypeCd} ({@link String}, key part 2)</td></tr>
 * <tr><td>3</td><td>{@code DIS-TRAN-CAT-CD}</td><td>{@code 9(04)}</td>
 *     <td>12</td><td>4</td><td>{@code disTranCatCd} ({@link String}, key part 3)</td></tr>
 * <tr><td>4</td><td>{@code DIS-INT-RATE}</td><td><strong>{@code S9(04)V99}</strong></td>
 *     <td>16</td><td><strong>6</strong></td>
 *     <td>{@code disIntRate} ({@link BigDecimal}, precision 6, scale 2)</td></tr>
 * <tr><td>&mdash;</td><td>{@code FILLER}</td><td>{@code X(28)}</td>
 *     <td>22</td><td>28</td><td><em>not mapped, not persisted</em></td></tr>
 * </table>
 *
 * <p><strong>Width arithmetic.</strong> The key is 10 + 2 + 4 = 16 bytes, which is exactly the
 * {@code KEYS(16 0)} figure; the 6-byte rate follows it, so 16 + 6 = 22 bytes are mapped; and
 * 22 + 28 filler bytes = 50, the stated record length. Those three sums are the whole geometry of
 * this layout and every constant below is derived from them rather than restated, so a wrong figure
 * cannot leave the offsets looking self-consistent.</p>
 *
 * <h2>The rate is six bytes, not eleven</h2>
 *
 * <p>{@code DIS-INT-RATE} is {@code PIC S9(04)V99}: four integer digits plus two implied decimal
 * digits, and therefore <strong>six</strong> encoded bytes. A census of the copybook and program
 * trees finds {@code S9(04)V99} exactly <strong>once</strong> in the entire estate, at this field,
 * so this is the only field of this shape anywhere. Every other signed decimal is
 * {@code S9(09)V99} at 11 bytes &mdash; the transaction amount, the daily-transaction amount and the
 * category balance &mdash; or {@code S9(10)V99} at 12 bytes, being the five account money fields.
 * The consequence reaches the schema: {@code dis_int_rate} is the only precision-6 column in the
 * database, and every other monetary column is precision 11 or 12.</p>
 *
 * <p>This asymmetry is the single most dangerous thing about this layout, because the failure mode is
 * silent. Copying an 11-byte slice from a sibling mapper would read the rate's six bytes plus the
 * first four bytes of filler, shift no subsequent field, and still parse &mdash; the fixture's filler
 * is ASCII zeros, so an 11-byte slice of row 0 yields digits that decode without complaint to a
 * value four decimal orders away from the truth. {@link #DIS_INT_RATE_LENGTH} is therefore declared
 * as the literal 6, is documented here as deliberately neither 11 nor 12, and must not be
 * "harmonised" with its siblings by a reviewer who has seen ten mappers use 11 or 12.</p>
 *
 * <h2>Decimal representation</h2>
 *
 * <p>The value is zoned decimal under {@code USAGE DISPLAY}: one ASCII byte per digit with the sign
 * overpunched into the final digit byte, so there is no separate sign byte and no byte is spent on
 * the implied decimal point. Positive digits 0 through 9 are carried as <code>&#123;</code> and
 * {@code A} through {@code I}; negative digits 0 through 9 as <code>&#125;</code> and {@code J}
 * through {@code R}.</p>
 *
 * <p>Every conversion in both directions goes through {@link ZonedDecimalCodec}, which is the
 * module's single point of decimal truth. This class never calls {@code setScale}, never names a
 * rounding mode and never performs arithmetic, so it cannot introduce a second rounding policy. The
 * codec applies scale 2 and {@link java.math.RoundingMode#DOWN}, which is mandatory here and is not
 * a stylistic preference: a search for the {@code ROUNDED} keyword across every program and copybook
 * in the estate returns <strong>zero</strong> occurrences, and a COBOL arithmetic store without
 * {@code ROUNDED} truncates toward zero. {@code HALF_EVEN} &mdash; the conventional Java choice
 * &mdash; and {@code HALF_UP} are both forbidden, because either would differ by one cent on roughly
 * half of all interest computations, a byte-parity failure completely invisible to a test suite
 * written under the same wrong assumption. {@code COMP-3} occurs <strong>zero</strong> times in the
 * copybook tree, so no packed-decimal path is needed or provided, and no {@code double},
 * {@code float}, {@code Double} or {@code Float} appears anywhere in this class.</p>
 *
 * <p><strong>One representational limit, stated rather than hidden.</strong> The codec offers a
 * signed decode that carries a negative-zero bit alongside the value, because the legacy image
 * distinguishes a negative zero from a positive zero while {@link BigDecimal} does not. This mapper
 * uses the plain decode instead, because {@link DisclosureGroup} models the rate as a
 * {@link BigDecimal} and so has nowhere to keep that bit. The consequence is exact and narrow: every
 * image whose digits are not all zero round-trips byte for byte on either path, including a negative
 * rate, and only a true negative zero &mdash; an all-zero image whose sign byte is
 * <code>&#125;</code> &mdash; would be re-emitted with <code>&#123;</code>. No such image exists in
 * the reference data: a census of the final rate byte across all 51 rows of
 * {@code [app/data/ASCII/discgrp.txt]} finds <code>&#123;</code> every time, so no seeded row is
 * affected. A caller that must preserve that one bit has to carry it outside the entity; this class
 * will not silently invent a place for it.</p>
 *
 * <p><strong>This rate is the interest computation's multiplicand, and the computation is not
 * here.</strong> The accrual in {@code [app/cbl/CBACT04C.cbl]} computes the monthly interest as the
 * category balance multiplied by this rate <em>first</em> and divided by 1200 <em>second</em>, into a
 * {@code PIC S9(09)V99} working field. Dividing the rate by 1200 first is algebraically identical in
 * exact arithmetic but moves the truncation point and changes the stored cent, so the expression must
 * never be rearranged. That obligation belongs to the interest-calculation service; this class
 * decodes the operand faithfully and stops. No arithmetic of any kind is performed below: no
 * multiplication, no division, no accumulation, no rescaling of a decoded rate, and no numeric
 * literal from that expression &mdash; the divisor is named here in prose and appears nowhere in
 * code.</p>
 *
 * <h2>The ten-character group key is never trimmed</h2>
 *
 * <p>{@code DIS-ACCT-GROUP-ID} is {@code PIC X(10)} and the reference data uses all ten bytes:
 * {@code A000000000} fills the field exactly, while {@code DEFAULT} and {@code ZEROAPR} are seven
 * characters each followed by <strong>three trailing spaces</strong>. Those spaces are never
 * trimmed, stripped, right-trimmed, case-folded or normalised, in either direction.</p>
 *
 * <p>The reason is behavioural rather than cosmetic. When the direct group lookup fails with file
 * status {@code '23'}, the accrual program in {@code [app/cbl/CBACT04C.cbl]} recovers by moving the
 * {@code DEFAULT} literal into a {@code PIC X(10)} key field &mdash; which space-pads it to ten
 * characters &mdash; and reading again. If a stored key had been trimmed to seven characters, the
 * padded probe would no longer match it, the fallback would never fire, and the set of accounts that
 * accrue interest would change silently. The account record's own {@code ACCT-GROUP-ID} is likewise
 * {@code X(10)} and is ten spaces on all 50 seeded account rows, so the join is between two padded
 * ten-byte fields and neither side may be normalised. The fallback itself is a service concern and is
 * not implemented here; this class's entire obligation is to carry the ten bytes through verbatim,
 * which is what makes the fallback possible.</p>
 *
 * <h2>Verified fixture evidence</h2>
 *
 * <p>{@code [app/data/ASCII/discgrp.txt]} is 2,601 bytes, which factors exactly as 51 rows on a
 * 51-byte stride: 50 record bytes plus one {@code 0x0A}. The line feed is a record
 * <em>terminator</em> and is never record content, so a caller strips it before presenting a record
 * here. The row count of 51 is odd and reads like an off-by-one, but it is not: it is three complete
 * groups of seventeen.</p>
 *
 * <table>
 * <caption>Rows verified byte for byte against the shipped fixture</caption>
 * <tr><th scope="col">Row</th><th scope="col">Offset 0, 10 bytes</th>
 *     <th scope="col">Offset 16, 6 bytes</th><th scope="col">Decoded rate</th></tr>
 * <tr><td>0</td><td>{@code A000000000}</td><td><code>00150&#123;</code></td>
 *     <td>{@code 15.00}</td></tr>
 * <tr><td>17</td><td>{@code DEFAULT} + three spaces</td><td><code>00150&#123;</code></td>
 *     <td>{@code 15.00}</td></tr>
 * <tr><td>34</td><td>{@code ZEROAPR} + three spaces</td><td><code>00000&#123;</code></td>
 *     <td>{@code 0.00}</td></tr>
 * <tr><td>50</td><td>{@code ZEROAPR} + three spaces</td><td><code>00000&#123;</code></td>
 *     <td>{@code 0.00}</td></tr>
 * </table>
 *
 * <p>The field positions are confirmed by that data: the group identifier at offset 0 for 10 bytes,
 * the type code at 10 for 2, the category code at 12 for 4, the rate at <strong>16 for 6</strong>,
 * and the filler at 22 for 28. Only three distinct rate images occur across the 51 rows &mdash;
 * <code>00000&#123;</code> thirty times, <code>00150&#123;</code> fifteen times and
 * <code>00250&#123;</code> six times &mdash; and every one of the 51 sign bytes is
 * <code>&#123;</code>, so the fixture contains no negative rate at all and a negative case has to be
 * constructed rather than sampled.</p>
 *
 * <p>The three seventeen-row groups are keyed {@code A000000000}, {@code DEFAULT} plus three spaces
 * and {@code ZEROAPR} plus three spaces. That composition matters beyond this class: it makes both
 * the {@code '23'}-status default-group fallback and the zero-rate skip in the accrual program
 * reachable from seeded data alone, with no synthetic fixture required. The zero-rate skip is service
 * logic; here a {@code 0.00} rate is simply decoded faithfully and is never treated as absent,
 * missing, {@code null} or invalid.</p>
 *
 * <h2>Filler bytes and the exact comparison bound</h2>
 *
 * <p>{@link #toRecord(DisclosureGroup)} emits the 28-byte filler as spaces. COBOL {@code FILLER X(n)}
 * with no {@code VALUE} clause is uninitialised, so no byte value is canonical, and the shipped
 * sample data disagrees with itself: the four master files carry space filler while the four
 * reference-table files carry ASCII-zero filler. This layout is one of the reference tables, and all
 * 1,428 filler bytes in {@code [app/data/ASCII/discgrp.txt]} are ASCII {@code '0'}. Space is the
 * module-wide default and is what {@link #FILLER_CHARACTER} declares; the divergence is recorded as a
 * source anomaly rather than silently corrected in either direction.</p>
 *
 * <p><strong>Every fixture round-trip assertion for this layout therefore compares only the mapped
 * data prefix, byte 0 up to but excluding byte 22</strong>, which {@link #MAPPED_PREFIX_LENGTH}
 * names. <strong>A whole-record 50-byte round-trip comparison against that fixture will fail</strong>
 * &mdash; not because the mapping is wrong, but because the fixture's 28 filler bytes are {@code '0'}
 * and this class emits spaces. A caller that needs whole-record equality against the fixture must
 * either compare the prefix or supply {@code '0'} filler itself; it must not "fix" the mapper.</p>
 *
 * <h2>Numeric identifiers stay strings</h2>
 *
 * <p>{@code DIS-TRAN-TYPE-CD} and {@code DIS-TRAN-CAT-CD} carry significant leading zeros &mdash;
 * the fixture's category code is {@code 0001} throughout and its type codes run {@code 01} to
 * {@code 07} &mdash; so neither is ever parsed to {@code int} or {@code long} and re-formatted. Both
 * are carried as {@link String} end to end, and {@code DIS-ACCT-GROUP-ID} is alphanumeric in any
 * case. Round-tripping through an integral type would drop the leading zeros and change the key.</p>
 *
 * <h2>Entity contract</h2>
 *
 * <p>{@link DisclosureGroup} is annotated {@code @IdClass}, so its three key components are each
 * annotated {@code @Id} <em>on the entity itself</em> and the composite {@link DisclosureGroupId}
 * exists only as an addressable copy of them. This class consequently never attempts to set an
 * identifier object onto an entity; it supplies the three components directly, and
 * {@link #keyFromRecord(String)} is offered purely as a convenience for a caller that wants a key
 * without materialising a row. The key is the natural business key at offset zero, exactly as
 * {@code KEYS(16 0)} states, and is never a surrogate: a generated identifier would break the
 * correspondence between record image and table row on which byte-level output parity depends.</p>
 *
 * <p>Two further properties of the entity are stated because their absence is easy to misread as an
 * oversight. It has <strong>no {@code @Version} field</strong> and therefore no optimistic-locking
 * column, because a disclosure group is reference data that the online tier never updates. And the
 * schema declares <strong>no foreign key in any migration version</strong>, so this class bakes in no
 * referential assumption whatsoever: it performs no group-identifier whitelist check, no rate range
 * check and no transaction type or category lookup. Validation of every kind belongs elsewhere.</p>
 *
 * <p>Population uses the entity's public four-argument constructor rather than a no-argument
 * constructor followed by setters. That is a consequence of visibility rather than a preference: the
 * entity's no-argument constructor is {@code protected} and reserved for the persistence provider, so
 * it is not reachable from this package. The four-argument constructor is documented by the entity as
 * the path for application code and assigns every argument verbatim, which is precisely the guarantee
 * this mapper needs.</p>
 *
 * <h2>Failure contract</h2>
 *
 * <p>A record image is validated as exactly {@value #RECORD_LENGTH} <em>encoded bytes</em> &mdash;
 * never as a character count &mdash; and any other length raises {@link IllegalArgumentException}
 * naming the artefact, the expected width and the actual encoded byte length. Nothing is ever
 * silently padded, silently truncated, partially returned or returned as {@code null}. The check is
 * delegated to {@link FixedWidthFieldReader}, which owns it for every layout in the module and whose
 * diagnostic additionally points out an unstripped {@code 0x0A} when the overshoot is exactly one
 * byte &mdash; the precise mistake a caller makes when it hands over a 51-byte stride slice.</p>
 *
 * <p>{@link IllegalArgumentException} is used deliberately in preference to any exception type from
 * this module's own exception package. None of those types models "the caller handed me the wrong
 * number of bytes", and the condition has no legacy antecedent at all, because VSAM records are fixed
 * length by construction; a short record is a Java-side caller defect and this guard exists only to
 * catch it. Nothing from the exception package is imported here. An invalid overpunch character in
 * the rate field is the codec's contract rather than this class's, and the codec's failure is allowed
 * to propagate unwrapped so that a caller sees the offending byte and its offset.</p>
 *
 * <p>{@code null} arguments raise {@link NullPointerException} deterministically by way of
 * {@link Objects#requireNonNull(Object, String)}, including each individual entity property on the
 * encode path, so a partially populated entity identifies the missing field by its legacy name rather
 * than failing further downstream.</p>
 *
 * <h2>Shape</h2>
 *
 * <p>This class is a stateless collection of pure static functions: it is final, it cannot be
 * instantiated, it holds no mutable static state, and every method performs no I/O, consults no
 * clock, reads no environment and uses no randomness. It has no logger, and deliberately so, because
 * {@code com.carddemo.util} is not among the packages whose log levels the module pins, so a logger
 * here would be unconfigured; a mapper diagnoses by exception rather than by log line. It performs no
 * {@code String} to byte conversion of its own either: every charset decision belongs to
 * {@link FixedWidthFieldReader} and {@link ZonedDecimalCodec}, both of which name US-ASCII explicitly
 * at every boundary, so there is exactly one place per direction where an encoding choice is made and
 * no path in this module relies on a platform default. Slicing likewise goes only through
 * {@link FixedWidthFieldReader}; there is no {@code substring} call below.</p>
 *
 * <p>Every figure quoted above is factual layout evidence &mdash; record widths, byte offsets, field
 * lengths, row counts and byte-value censuses &mdash; and never a service level, a buffer size or a
 * performance target.</p>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * // Decode one record, having stripped the 0x0A terminator.
 * DisclosureGroup group = DisclosureGroupRecordMapper.fromRecord(line);
 *
 * // Decode record i straight out of a whole-file buffer, terminator left behind.
 * int stride = DisclosureGroupRecordMapper.RECORD_LENGTH + 1;
 * DisclosureGroup row17 = DisclosureGroupRecordMapper.fromRecord(fileBytes, 17 * stride);
 *
 * // Re-emit, then compare only the mapped prefix against the fixture (see the filler note).
 * String image = DisclosureGroupRecordMapper.toRecord(group);
 * }</pre>
 *
 * <h2>Decision-log entries raised by this file</h2>
 *
 * <p>These are the translation decisions this mapper makes where legacy semantics and idiomatic
 * Java diverge. Each is developed in full above and is enumerated here so that the module's
 * decision log can be assembled from the sources that raise the decisions rather than from a
 * reviewer's recollection.</p>
 *
 * <ol>
 * <li>{@code DIS-INT-RATE} is {@code PIC S9(04)V99} and therefore <strong>six</strong> encoded bytes:
 *     the only field of that shape in the estate and the only precision-6 column in the schema. An
 *     eleven-byte slice copied from a sibling mapper would read filler into the value and still
 *     parse.</li>
 * <li>Decoding fixes scale at 2 and truncates toward zero, never rounding half-even or half-up,
 *     because {@code ROUNDED} occurs nowhere in the estate and a COBOL store without it truncates.
 *     That policy belongs to {@link ZonedDecimalCodec}; this class never rescales a decoded rate.</li>
 * <li>The ten-character group identifier is never trimmed, in either direction, because the
 *     file-status {@code '23'} recovery re-probes with the space-padded literal and trimming the
 *     stored value would silently disable the fallback. The account record's group identifier is ten
 *     spaces on every seeded row, so the join is between two padded ten-byte fields.</li>
 * <li>A rate of {@code 0.00} is a legitimate decoded value and is never treated as absent, null,
 *     missing or invalid. The zero-rate skip is a service concern, not a mapping concern.</li>
 * <li>The fixture carries three consecutive seventeen-row groups, which makes both the fallback
 *     branch and the zero-rate branch of the interest program reachable from seeded data alone, with
 *     no synthetic fixture required.</li>
 * <li>Filler bytes are not uniform across the estate's fixtures: this layout's fixture carries ASCII
 *     zero whereas {@link #toRecord(DisclosureGroup)} emits spaces, an uninitialised-{@code FILLER}
 *     anomaly. Round-trip assertions therefore compare only the mapped prefix, and a whole-record
 *     comparison would fail.</li>
 * <li>A record image of the wrong length raises {@link IllegalArgumentException} rather than any
 *     module exception type, because a short or long fixed-width image has no legacy antecedent and
 *     the condition is a caller defect rather than a modelled business outcome.</li>
 * </ol>
 *
 * <p>Traceability: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release
 * stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated {@code 2022-07-19}. Sources are cited, never
 * transcribed: no COBOL, copybook or job-stream statement appears in this file.</p>
 *
 * @see DisclosureGroup
 * @see DisclosureGroupId
 * @see FixedWidthFieldReader
 * @see ZonedDecimalCodec
 */
public final class DisclosureGroupRecordMapper {

    /**
     * Layout name used in every diagnostic this class produces, naming both the COBOL group and the
     * copybook that declares it so that a rejected image identifies its own contract.
     */
    public static final String ARTEFACT = "DIS-GROUP-RECORD (CVTRA02Y)";

    /** Legacy field name of key part 1, used in diagnostics. */
    private static final String FIELD_ACCT_GROUP_ID = "DIS-ACCT-GROUP-ID";

    /** Legacy field name of key part 2, used in diagnostics. */
    private static final String FIELD_TRAN_TYPE_CD = "DIS-TRAN-TYPE-CD";

    /** Legacy field name of key part 3, used in diagnostics. */
    private static final String FIELD_TRAN_CAT_CD = "DIS-TRAN-CAT-CD";

    /** Legacy field name of the interest rate, used in diagnostics. */
    private static final String FIELD_INT_RATE = "DIS-INT-RATE";

    /**
     * Encoded byte width of the whole record, {@value #RECORD_LENGTH}.
     *
     * <p>Attested twice and independently: the copybook header states a record length of 50
     * {@code [app/cpy/CVTRA02Y.cpy]} and the cluster is defined with {@code RECORDSIZE(50 50)}
     * {@code [app/jcl/DISCGRP.jcl]}. The fixture confirms it a third time, since 2,601 bytes factors
     * exactly as 51 rows of 50 plus one terminator each.</p>
     */
    public static final int RECORD_LENGTH = 50;

    /**
     * Zero-based offset of {@code DIS-ACCT-GROUP-ID}, {@value #DIS_ACCT_GROUP_ID_OFFSET}. It is the
     * first field of the record, so the record and its key both begin here.
     */
    public static final int DIS_ACCT_GROUP_ID_OFFSET = 0;

    /**
     * Encoded byte length of {@code DIS-ACCT-GROUP-ID}, {@value #DIS_ACCT_GROUP_ID_LENGTH}, from
     * {@code PIC X(10)}. All ten bytes are significant: {@code DEFAULT} and {@code ZEROAPR} occupy
     * seven of them and are followed by three contractual spaces.
     */
    public static final int DIS_ACCT_GROUP_ID_LENGTH = 10;

    /**
     * Zero-based offset of {@code DIS-TRAN-TYPE-CD}, {@value #DIS_TRAN_TYPE_CD_OFFSET}. Derived by
     * addition rather than restated, so it cannot disagree with the field that precedes it.
     */
    public static final int DIS_TRAN_TYPE_CD_OFFSET =
            DIS_ACCT_GROUP_ID_OFFSET + DIS_ACCT_GROUP_ID_LENGTH;

    /**
     * Encoded byte length of {@code DIS-TRAN-TYPE-CD}, {@value #DIS_TRAN_TYPE_CD_LENGTH}, from
     * {@code PIC X(02)}.
     */
    public static final int DIS_TRAN_TYPE_CD_LENGTH = 2;

    /**
     * Zero-based offset of {@code DIS-TRAN-CAT-CD}, {@value #DIS_TRAN_CAT_CD_OFFSET}, derived from
     * the field that precedes it.
     */
    public static final int DIS_TRAN_CAT_CD_OFFSET =
            DIS_TRAN_TYPE_CD_OFFSET + DIS_TRAN_TYPE_CD_LENGTH;

    /**
     * Encoded byte length of {@code DIS-TRAN-CAT-CD}, {@value #DIS_TRAN_CAT_CD_LENGTH}, from
     * {@code PIC 9(04)}. The leading zeros are significant, so the value is carried as a
     * {@link String} of exactly this width and never as an integral type.
     */
    public static final int DIS_TRAN_CAT_CD_LENGTH = 4;

    /**
     * Encoded byte length of the three-part composite key, {@value #KEY_LENGTH}.
     *
     * <p>Derived as 10 + 2 + 4 from the three key components, and equal to the {@code KEYS(16 0)}
     * figure declared by the cluster definition {@code [app/jcl/DISCGRP.jcl]}, which is an
     * independent attestation of the same number. It is deliberately <em>not</em> 17: the
     * transaction-category-balance layout has a 17-byte key, and no constant is shared with it.</p>
     */
    public static final int KEY_LENGTH =
            DIS_ACCT_GROUP_ID_LENGTH + DIS_TRAN_TYPE_CD_LENGTH + DIS_TRAN_CAT_CD_LENGTH;

    /**
     * Zero-based offset of {@code DIS-INT-RATE}, {@value #DIS_INT_RATE_OFFSET}, derived from the last
     * key component. It coincides with {@link #KEY_LENGTH} because the key occupies the leading
     * portion of the record, which is what {@code KEYS(16 0)} asserts.
     */
    public static final int DIS_INT_RATE_OFFSET =
            DIS_TRAN_CAT_CD_OFFSET + DIS_TRAN_CAT_CD_LENGTH;

    /**
     * Encoded byte length of {@code DIS-INT-RATE}, {@value #DIS_INT_RATE_LENGTH}.
     *
     * <p>{@code PIC S9(04)V99} is four integer digits plus two implied decimal digits, so the image
     * is six bytes with the sign overpunched into the last of them. This is the estate's only
     * {@code S9(04)V99} field and the schema's only precision-6 column, and the figure is
     * deliberately neither 11 &mdash; the {@code S9(09)V99} width of the transaction amount, the
     * daily-transaction amount and the category balance &mdash; nor 12, the {@code S9(10)V99} width
     * of the five account money fields. It equals {@link ZonedDecimalCodec#INTEREST_RATE_WIDTH},
     * which the codec publishes for this same field; the two are asserted equal by test rather than
     * aliased, so that neither can be widened by editing the other.</p>
     */
    public static final int DIS_INT_RATE_LENGTH = 6;

    /**
     * Encoded byte length of the mapped prefix of the record, {@value #MAPPED_PREFIX_LENGTH}, being
     * the key plus the rate, and equivalently the exclusive upper bound of the byte range that
     * carries mapped data.
     *
     * <p>This is the comparison bound for a fixture round trip. Bytes 0 through 21 inclusive are
     * mapped; bytes 22 through 49 are uninitialised filler whose value differs between this class and
     * the shipped fixture, so a whole-record comparison against the fixture will fail on the filler
     * alone. Compare {@code [0, }{@value #MAPPED_PREFIX_LENGTH}{@code )}.</p>
     */
    public static final int MAPPED_PREFIX_LENGTH = DIS_INT_RATE_OFFSET + DIS_INT_RATE_LENGTH;

    /**
     * Zero-based offset of the trailing {@code FILLER}, {@value #FILLER_OFFSET}, which begins exactly
     * where the mapped data ends.
     */
    public static final int FILLER_OFFSET = MAPPED_PREFIX_LENGTH;

    /**
     * Encoded byte length of the trailing {@code FILLER}, {@value #FILLER_LENGTH}, from
     * {@code PIC X(28)}.
     *
     * <p>Derived as the remainder of the attested record length rather than restated, so the mapped
     * fields and the filler are guaranteed to sum to {@link #RECORD_LENGTH} by construction.</p>
     */
    public static final int FILLER_LENGTH = RECORD_LENGTH - MAPPED_PREFIX_LENGTH;

    /**
     * The byte this class writes across the trailing filler run on the encode path, a space.
     *
     * <p>COBOL {@code FILLER X(28)} carries no {@code VALUE} clause and is therefore uninitialised,
     * so no byte value is canonical. Space is the module-wide default. The shipped fixture
     * {@code [app/data/ASCII/discgrp.txt]} disagrees: all 1,428 of its filler bytes are ASCII
     * {@code '0'}, in common with the other three reference-table files and unlike the four master
     * files. The divergence is a recorded source anomaly and is neither propagated nor silently
     * corrected, which is exactly why a fixture round trip compares only
     * {@code [0, }{@value #MAPPED_PREFIX_LENGTH}{@code )}.</p>
     */
    public static final char FILLER_CHARACTER = ' ';

    /** Not instantiable: this is a stateless collection of pure mapping functions. */
    private DisclosureGroupRecordMapper() {
        // No instance state exists, so no instance is ever required.
    }

    /**
     * Decodes a complete disclosure-group record image supplied as a string.
     *
     * <p>The image must be exactly {@value #RECORD_LENGTH} encoded bytes with any {@code 0x0A}
     * terminator already removed. Character fields are copied verbatim: the ten-byte group identifier
     * keeps its trailing spaces, and the two- and four-byte codes keep their leading zeros. The rate
     * is decoded by {@link ZonedDecimalCodec} from its six-byte overpunched image to a
     * {@link BigDecimal} of scale 2.</p>
     *
     * @param  recordImage the complete record image, excluding any line terminator; must not be
     *                     {@code null}
     * @return a fully populated entity, never {@code null}
     * @throws NullPointerException     if {@code recordImage} is {@code null}
     * @throws IllegalArgumentException if the image is not exactly {@value #RECORD_LENGTH} encoded
     *                                 bytes, if it contains a character US-ASCII cannot represent, or
     *                                 if the rate field carries an invalid overpunched image
     */
    public static DisclosureGroup fromRecord(String recordImage) {
        Objects.requireNonNull(recordImage, "recordImage must not be null");
        return mapFrom(FixedWidthFieldReader.of(ARTEFACT, recordImage, RECORD_LENGTH));
    }

    /**
     * Decodes a complete disclosure-group record image supplied as bytes.
     *
     * <p>Preferred over {@link #fromRecord(String)} when the caller already holds raw bytes, because
     * it removes any need for the caller to choose a charset. Behaviour is otherwise identical.</p>
     *
     * @param  recordImage the complete record image as bytes, excluding any line terminator; must not
     *                     be {@code null}
     * @return a fully populated entity, never {@code null}
     * @throws NullPointerException     if {@code recordImage} is {@code null}
     * @throws IllegalArgumentException if the image is not exactly {@value #RECORD_LENGTH} bytes, if
     *                                 any byte is not 7-bit ASCII, or if the rate field carries an
     *                                 invalid overpunched image
     */
    public static DisclosureGroup fromRecord(byte[] recordImage) {
        Objects.requireNonNull(recordImage, "recordImage must not be null");
        return mapFrom(FixedWidthFieldReader.of(ARTEFACT, recordImage, RECORD_LENGTH));
    }

    /**
     * Decodes one disclosure-group record held inside a larger byte buffer.
     *
     * <p>This is the seam for a batch reader that holds a whole newline-terminated file in one
     * buffer. The file's stride is {@value #RECORD_LENGTH} plus one for the terminator, so record
     * <em>i</em> starts at {@code i * (RECORD_LENGTH + 1)} and the terminator is simply left behind
     * rather than stripped. Stride arithmetic and file access stay with the caller; this class only
     * ever sees one record's worth of bytes.</p>
     *
     * <p>Because this overload selects a range rather than validating a whole image, an out-of-range
     * request is reported as a range failure against the buffer instead of as a width mismatch.</p>
     *
     * @param  buffer buffer containing the record, and possibly many others; must not be {@code null}
     * @param  from   zero-based index in {@code buffer} at which the record starts
     * @return a fully populated entity, never {@code null}
     * @throws NullPointerException     if {@code buffer} is {@code null}
     * @throws IllegalArgumentException if {@code from} is negative, if the range
     *                                 {@code [from, from + }{@value #RECORD_LENGTH}{@code )} is not
     *                                 wholly inside {@code buffer}, if any byte in that range is not
     *                                 7-bit ASCII, or if the rate field carries an invalid
     *                                 overpunched image
     */
    public static DisclosureGroup fromRecord(byte[] buffer, int from) {
        Objects.requireNonNull(buffer, "buffer must not be null");
        return mapFrom(FixedWidthFieldReader.of(ARTEFACT, buffer, from, RECORD_LENGTH));
    }

    /**
     * Extracts just the three-part composite key from a complete record image, without materialising
     * a row.
     *
     * <p>Offered for a caller that only needs to address or probe a row, for instance to look one up
     * before deciding whether to decode it. The three components are sliced at the same offsets and
     * copied just as verbatim as {@link #fromRecord(String)} copies them, so a padded group
     * identifier such as {@code DEFAULT} followed by three spaces yields a key that matches the
     * stored row exactly. The whole image is still required and still validated, because the key is
     * only meaningful as part of a well-formed record.</p>
     *
     * @param  recordImage the complete record image, excluding any line terminator; must not be
     *                     {@code null}
     * @return the composite key, never {@code null}
     * @throws NullPointerException     if {@code recordImage} is {@code null}
     * @throws IllegalArgumentException if the image is not exactly {@value #RECORD_LENGTH} encoded
     *                                 bytes, or if it contains a character US-ASCII cannot represent
     */
    public static DisclosureGroupId keyFromRecord(String recordImage) {
        Objects.requireNonNull(recordImage, "recordImage must not be null");
        FixedWidthFieldReader record = FixedWidthFieldReader.of(ARTEFACT, recordImage, RECORD_LENGTH);
        return new DisclosureGroupId(
                record.field(FIELD_ACCT_GROUP_ID, DIS_ACCT_GROUP_ID_OFFSET, DIS_ACCT_GROUP_ID_LENGTH),
                record.field(FIELD_TRAN_TYPE_CD, DIS_TRAN_TYPE_CD_OFFSET, DIS_TRAN_TYPE_CD_LENGTH),
                record.field(FIELD_TRAN_CAT_CD, DIS_TRAN_CAT_CD_OFFSET, DIS_TRAN_CAT_CD_LENGTH));
    }

    /**
     * Encodes an entity into a complete {@value #RECORD_LENGTH}-byte record image.
     *
     * <p>The two alphanumeric fields are placed left-justified, matching {@code PIC X(n)}, so a value
     * already at its full declared width passes through byte for byte and nothing is ever trimmed;
     * the unsigned numeric field is placed right-justified and zero-filled, matching {@code PIC 9(n)},
     * so its leading zeros survive. The rate is encoded by {@link ZonedDecimalCodec} into its
     * six-byte overpunched image, which is placed flush against the field's trailing edge so the sign
     * byte stays last. The trailing {@value #FILLER_LENGTH}-byte filler is written as
     * {@link #FILLER_CHARACTER}.</p>
     *
     * <p><strong>Comparison bound.</strong> Because the filler byte is uninitialised in COBOL and the
     * shipped fixture uses ASCII {@code '0'} where this method writes a space, a round trip against
     * {@code [app/data/ASCII/discgrp.txt]} must compare only
     * {@code [0, }{@value #MAPPED_PREFIX_LENGTH}{@code )}. A whole-record comparison will fail on the
     * filler alone.</p>
     *
     * @param  group the entity to encode; must not be {@code null}, and every mapped property must be
     *               populated
     * @return the record image, never {@code null}, of exactly {@value #RECORD_LENGTH} US-ASCII bytes
     * @throws NullPointerException     if {@code group} is {@code null} or any mapped property of it
     *                                  is {@code null}
     * @throws IllegalArgumentException if any value is wider than its field, contains a character
     *                                  US-ASCII cannot represent, or does not fit the rate field
     */
    public static String toRecord(DisclosureGroup group) {
        return buildImage(group).image();
    }

    /**
     * Encodes an entity into a complete {@value #RECORD_LENGTH}-byte record image, returning the
     * bytes themselves so that the emitted width is guaranteed by construction rather than asserted
     * after the fact.
     *
     * <p>Behaves exactly as {@link #toRecord(DisclosureGroup)}, including the filler byte and the
     * comparison bound described there.</p>
     *
     * @param  group the entity to encode; must not be {@code null}, and every mapped property must be
     *               populated
     * @return a fresh array of exactly {@value #RECORD_LENGTH} US-ASCII bytes, never shared
     * @throws NullPointerException     if {@code group} is {@code null} or any mapped property of it
     *                                  is {@code null}
     * @throws IllegalArgumentException if any value is wider than its field, contains a character
     *                                  US-ASCII cannot represent, or does not fit the rate field
     */
    public static byte[] toRecordBytes(DisclosureGroup group) {
        return buildImage(group).toByteArray();
    }

    /**
     * Slices the four mapped fields out of a validated record and assembles the entity.
     *
     * <p>Every slice is taken through {@link FixedWidthFieldReader}, which returns raw, untrimmed
     * values, and the rate is converted by {@link ZonedDecimalCodec}, which is the only place a scale
     * is applied. The entity is built with its public four-argument constructor rather than a
     * no-argument constructor and setters, because the no-argument constructor is {@code protected}
     * and reserved for the persistence provider and so is not reachable from this package; the
     * four-argument constructor assigns every argument verbatim, which is the guarantee this mapper
     * needs. The three key components are supplied individually, never as an identifier object,
     * because the entity carries them as {@code @Id} properties under {@code @IdClass}.</p>
     *
     * @param  record the validated record image
     * @return a fully populated entity, never {@code null}
     * @throws IllegalArgumentException if the rate field carries an invalid overpunched image
     */
    private static DisclosureGroup mapFrom(FixedWidthFieldReader record) {
        String acctGroupId =
                record.field(FIELD_ACCT_GROUP_ID, DIS_ACCT_GROUP_ID_OFFSET, DIS_ACCT_GROUP_ID_LENGTH);
        String tranTypeCd =
                record.field(FIELD_TRAN_TYPE_CD, DIS_TRAN_TYPE_CD_OFFSET, DIS_TRAN_TYPE_CD_LENGTH);
        String tranCatCd =
                record.field(FIELD_TRAN_CAT_CD, DIS_TRAN_CAT_CD_OFFSET, DIS_TRAN_CAT_CD_LENGTH);
        // The rate image is sliced here and converted there: this class owns the offset, the codec
        // owns the overpunch convention and the scale, and neither owns both.
        String rateImage = record.field(FIELD_INT_RATE, DIS_INT_RATE_OFFSET, DIS_INT_RATE_LENGTH);
        BigDecimal intRate =
                ZonedDecimalCodec.decodeMonetary(rateImage, DIS_INT_RATE_LENGTH, FIELD_INT_RATE);
        return new DisclosureGroup(acctGroupId, tranTypeCd, tranCatCd, intRate);
    }

    /**
     * Places the four mapped fields and the filler run into a fresh record buffer.
     *
     * <p>Shared by both encode entrypoints so that the placement rules exist once. Each property is
     * null-checked before placement, so a partially populated entity names the offending legacy field
     * instead of failing obscurely inside the builder.</p>
     *
     * @param  group the entity to encode
     * @return a reader over the completed image, of exactly {@value #RECORD_LENGTH} bytes
     * @throws NullPointerException     if {@code group} or any mapped property of it is {@code null}
     * @throws IllegalArgumentException if any value does not fit its field
     */
    private static FixedWidthFieldReader buildImage(DisclosureGroup group) {
        Objects.requireNonNull(group, "group must not be null");
        String acctGroupId = requireField(group.getDisAcctGroupId(), FIELD_ACCT_GROUP_ID);
        String tranTypeCd = requireField(group.getDisTranTypeCd(), FIELD_TRAN_TYPE_CD);
        String tranCatCd = requireField(group.getDisTranCatCd(), FIELD_TRAN_CAT_CD);
        BigDecimal intRate = Objects.requireNonNull(group.getDisIntRate(),
                () -> nullFieldMessage(FIELD_INT_RATE));
        return FixedWidthFieldReader.builder(ARTEFACT, RECORD_LENGTH)
                // PIC X(10) and PIC X(02) are alphanumeric, so they are left-justified and
                // space-padded. A value already ten bytes wide is placed unchanged, which is what
                // carries the contractual trailing spaces of the padded group identifiers through.
                .putAlphanumeric(FIELD_ACCT_GROUP_ID, DIS_ACCT_GROUP_ID_OFFSET,
                        DIS_ACCT_GROUP_ID_LENGTH, acctGroupId)
                .putAlphanumeric(FIELD_TRAN_TYPE_CD, DIS_TRAN_TYPE_CD_OFFSET,
                        DIS_TRAN_TYPE_CD_LENGTH, tranTypeCd)
                // PIC 9(04) is right-justified and zero-filled, which is what preserves the
                // significant leading zeros of the category code.
                .putNumeric(FIELD_TRAN_CAT_CD, DIS_TRAN_CAT_CD_OFFSET, DIS_TRAN_CAT_CD_LENGTH,
                        tranCatCd)
                // The codec returns exactly six bytes, so this placement is positional only; it is
                // right-justified so that an overpunched sign byte stays in the final position.
                .putNumeric(FIELD_INT_RATE, DIS_INT_RATE_OFFSET, DIS_INT_RATE_LENGTH,
                        ZonedDecimalCodec.encodeMonetary(intRate, DIS_INT_RATE_LENGTH,
                                FIELD_INT_RATE))
                // Stated explicitly rather than inherited from the buffer's default, so that the
                // deliberate choice of space over the fixture's ASCII zero is visible right here.
                .putFiller(FILLER_OFFSET, FILLER_LENGTH, FILLER_CHARACTER)
                .build();
    }

    /**
     * Returns a mapped character field, having verified that it is present.
     *
     * @param  value     the property value as the entity holds it
     * @param  fieldName legacy field name used in the diagnostic
     * @return {@code value}, unchanged and in particular untrimmed
     * @throws NullPointerException if {@code value} is {@code null}
     */
    private static String requireField(String value, String fieldName) {
        return Objects.requireNonNull(value, () -> nullFieldMessage(fieldName));
    }

    /**
     * Builds the diagnostic for an absent mapped field.
     *
     * @param  fieldName legacy field name of the absent field
     * @return the message, naming the artefact and the field
     */
    private static String nullFieldMessage(String fieldName) {
        return ARTEFACT + " cannot be encoded because " + fieldName
                + " is null; every mapped field of a fixed-width record must be present";
    }
}
