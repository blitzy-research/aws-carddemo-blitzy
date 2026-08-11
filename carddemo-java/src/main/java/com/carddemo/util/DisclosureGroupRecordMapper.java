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
 * Sole holder of the 50-byte disclosure-group record layout and of the exact two-way mapping between
 * that record image and the {@link DisclosureGroup} entity.
 *
 * <p>Layout authority is {@code app/cpy/CVTRA02Y.cpy}: a three-part composite key - a ten-character
 * account group identifier, a two-character transaction type and a four-character transaction
 * category - followed by the interest rate and a trailing filler run. Offsets are zero-based byte
 * positions in the record image and lengths are encoded byte counts, never character counts, and
 * every offset is a named constant so a reviewer can audit it against the copybook without reading a
 * method body. The key width is derived from its three components rather than written as a literal,
 * and the cluster that provisions the dataset attests the same figure independently. It is
 * deliberately <em>not</em> the width of the transaction-category-balance key, which is one byte
 * longer, and no constant is shared between the two layouts.
 *
 * <p><strong>The rate is six encoded bytes, not eleven, and that is the single easiest mistake to
 * make in this layout.</strong> It is the only field of its shape anywhere in the estate:
 * deliberately neither the eleven bytes of the transaction amount, the daily-transaction amount and
 * the category balance, nor the twelve of the account monetary fields. An implementation that
 * assumes the common monetary width misreads every byte from the rate onward while still producing a
 * plausible object.
 *
 * <p>The rate is zoned decimal - one ASCII byte per digit, sign overpunched into the final digit
 * byte, no separate sign byte and no packed representation - and all conversion in both directions
 * goes through {@link ZonedDecimalCodec}, which applies scale 2 with truncation toward zero because
 * no arithmetic statement anywhere in the estate specifies rounding. This class never calls
 * {@code setScale} and never names a rounding mode, and no binary floating-point type appears here.
 * One representational limit is bridged rather than accepted: a {@link BigDecimal} cannot carry a
 * negative zero, so this mapper reads and writes the rate through the codec's <em>signed</em> entry
 * points and carries the missing bit on the entity's transient negative-zero marker. A negatively-signed
 * all-zero rate therefore re-emits the byte it arrived as, which matters here because the reference data
 * contains a whole zero-rate group.
 *
 * <p><strong>This rate is the interest computation's multiplicand, and the computation is not
 * here.</strong> The accrual service multiplies a category balance by this rate and only then
 * divides; because every store truncates, that arithmetic is not associative, so re-ordering the
 * expression would move the truncation point and change the cent. Keeping this class free of
 * arithmetic is what guarantees it cannot contribute such a defect.
 *
 * <p><strong>The ten-character group identifier is never trimmed.</strong> Reference rows carry
 * trailing spaces, and those spaces are never trimmed, stripped, case folded or normalised: a padded
 * key is a different key from a trimmed one everywhere in this module. The default-group fallback the
 * interest program performs depends on the padded form matching, so trimming here would silently
 * break a lookup rather than fail visibly.
 *
 * <p>The filler run is emitted as spaces. Filler with no initialising clause is uninitialised, so no
 * byte value is canonical, and the shipped fixture for this layout uses ASCII zero where this mapper
 * writes space. <strong>A whole-record round-trip comparison against that fixture will therefore
 * fail</strong> - not because the mapping is wrong, but because the filler bytes were never
 * specified. Every fixture assertion for this layout compares only the mapped data prefix, from zero
 * up to but excluding {@value #MAPPED_PREFIX_LENGTH}.
 *
 * <p>The identifiers stay {@link String}s even where they look numeric, because leading zeros are
 * significant and an identifier is not a number. The entity declares no optimistic-locking counter,
 * a disclosure group being reference data rather than a concurrently updated row, so no such token is
 * read from or written to the record image. This class bakes in no referential assumption of any
 * kind: it resolves no association and reads no other table.
 *
 * <p>An image whose encoded length is not exactly {@value #RECORD_LENGTH} raises
 * {@link IllegalArgumentException} naming the artefact, the expected width and the actual length;
 * {@code null} arguments raise {@link NullPointerException}. Input is never silently padded,
 * truncated, partially mapped or returned as {@code null}. No type from this module's own exception
 * package is used: none of them models a caller supplying the wrong number of bytes, which has no
 * legacy antecedent because the legacy records are fixed length by construction.
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
 * and {@code ZEROAPR} plus three spaces. That composition matters beyond this class, and it is worth
 * being precise about what it does and does not reach. It makes the {@code '23'}-status default-group
 * fallback reachable from seeded data alone: no seeded account carries any of these three keys - all
 * fifty hold ten spaces - so every seeded account misses its first probe and re-probes as
 * {@code DEFAULT} plus three spaces. It does <em>not</em> make the zero-rate skip reachable from the
 * seed, because that re-probe finds a rate of 15.00; reaching the skip needs an account constructed
 * with the zero-rate key plus a category balance on the matching type and category. Either way the
 * skip is service logic; here a {@code 0.00} rate is simply decoded faithfully and is never treated as
 * absent, missing, {@code null} or invalid.</p>
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
 * <p>A final, non-instantiable holder of pure static functions: no mutable static state, no I/O, no
 * clock, no environment and no randomness. It has no logger, deliberately, because
 * {@code com.carddemo.util} is not among the packages whose log levels the module pins, so a logger here
 * would be unconfigured; a mapper diagnoses by exception. Every charset decision belongs to
 * {@link FixedWidthFieldReader} and {@link ZonedDecimalCodec}, both of which name US-ASCII explicitly at
 * every boundary, so there is exactly one place per direction where an encoding choice is made and no
 * path relies on a platform default. Slicing likewise goes only through {@link FixedWidthFieldReader};
 * there is no {@code substring} call below.</p>
 *
 * <p>Every figure quoted above is factual layout evidence &mdash; record widths, byte offsets, field
 * lengths, row counts and byte-value censuses &mdash; and never a service level, a buffer size or a
 * performance target.</p>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code //
 * Decode one record, having stripped the 0x0A terminator.
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
 * @see DisclosureGroup
 * @see ZonedDecimalCodec
 * @see FixedWidthFieldReader
 */
public final class DisclosureGroupRecordMapper {

    /** Layout name carried into every diagnostic; it names both the record group and the copybook. */
    public static final String ARTEFACT = "DIS-GROUP-RECORD (CVTRA02Y)";

    private static final String FIELD_ACCT_GROUP_ID = "DIS-ACCT-GROUP-ID";

    private static final String FIELD_TRAN_TYPE_CD = "DIS-TRAN-TYPE-CD";

    private static final String FIELD_TRAN_CAT_CD = "DIS-TRAN-CAT-CD";

    private static final String FIELD_INT_RATE = "DIS-INT-RATE";

    /** Full record width: the mapped data prefix plus the trailing filler run. */
    public static final int RECORD_LENGTH = 50;

    public static final int DIS_ACCT_GROUP_ID_OFFSET = 0;

    /** Width of the group identifier, whose contractual trailing spaces are never trimmed. */
    public static final int DIS_ACCT_GROUP_ID_LENGTH = 10;

    public static final int DIS_TRAN_TYPE_CD_OFFSET =
            DIS_ACCT_GROUP_ID_OFFSET + DIS_ACCT_GROUP_ID_LENGTH;

    public static final int DIS_TRAN_TYPE_CD_LENGTH = 2;

    public static final int DIS_TRAN_CAT_CD_OFFSET =
            DIS_TRAN_TYPE_CD_OFFSET + DIS_TRAN_TYPE_CD_LENGTH;

    public static final int DIS_TRAN_CAT_CD_LENGTH = 4;

    /**
     * Composite key width, derived from its three components rather than written as a literal. It is
     * deliberately not the width of the transaction-category-balance key, which is one byte longer.
     */
    public static final int KEY_LENGTH =
            DIS_ACCT_GROUP_ID_LENGTH + DIS_TRAN_TYPE_CD_LENGTH + DIS_TRAN_CAT_CD_LENGTH;

    public static final int DIS_INT_RATE_OFFSET =
            DIS_TRAN_CAT_CD_OFFSET + DIS_TRAN_CAT_CD_LENGTH;

    /**
     * Encoded width of the interest rate: <strong>six bytes</strong>, the only field of this shape in
     * the estate. Deliberately neither the eleven bytes of the transaction, daily-transaction and
     * category-balance amounts nor the twelve of the account monetary fields, so an implementation that
     * assumes the common monetary width misreads every byte from here onward. Taken from the codec's own
     * published width rather than restated, so the layout and the decoder cannot disagree.
     */
    public static final int DIS_INT_RATE_LENGTH = ZonedDecimalCodec.INTEREST_RATE_WIDTH;

    /**
     * Width of the mapped data prefix, and the exact bound for a fixture round-trip comparison: compare
     * from zero up to but excluding this value and nothing beyond it.
     */
    public static final int MAPPED_PREFIX_LENGTH = DIS_INT_RATE_OFFSET + DIS_INT_RATE_LENGTH;

    public static final int FILLER_OFFSET = MAPPED_PREFIX_LENGTH;

    /** Width of the unmapped trailing filler run. */
    public static final int FILLER_LENGTH = RECORD_LENGTH - MAPPED_PREFIX_LENGTH;

    /**
     * Byte this mapper writes across the filler run. Filler with no initialising clause is
     * uninitialised, so no value is canonical; space is the module-wide default, and the shipped fixture
     * uses ASCII zero instead - which is why a whole-record comparison against it fails.
     */
    public static final char FILLER_CHARACTER = ' ';

    /** Not instantiable: a stateless mapper exposing only static members. */
    private DisclosureGroupRecordMapper() {
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
     * <p>For a caller that only needs to address or probe a row. The components are sliced at the
     * same offsets and copied just as verbatim as {@link #fromRecord(String)} copies them, so a
     * padded group identifier yields a key that matches the stored row exactly. The whole image is
     * still required and still validated, because the key is only meaningful as part of a well-formed
     * record.
     *
     * @param  recordImage the complete record image, excluding any line terminator
     * @return the composite key
     * @throws NullPointerException     if {@code recordImage} is {@code null}
     * @throws IllegalArgumentException if the image is not exactly {@value #RECORD_LENGTH} encoded
     *                                  bytes, or contains a character US-ASCII cannot represent
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
     * <p>Padding is part of the contract: the alphanumeric fields are placed left-justified, so a
     * value already at its full declared width passes through byte for byte and nothing is ever
     * trimmed, and the unsigned numeric field is placed right-justified and zero-filled so its
     * leading zeros survive. The rate is encoded by {@link ZonedDecimalCodec} and placed flush
     * against the field's trailing edge so the overpunched sign byte stays last. The trailing filler
     * is written as {@link #FILLER_CHARACTER}.
     *
     * <p><strong>Comparison bound.</strong> Because the filler is uninitialised in the source and the
     * shipped fixture uses ASCII zero where this method writes a space, a round trip against that
     * fixture must compare only the mapped prefix; a whole-record comparison will fail on the filler
     * alone.
     *
     * @param  group the entity to encode; every mapped property must be populated
     * @return the record image, exactly {@value #RECORD_LENGTH} US-ASCII bytes
     * @throws NullPointerException     if {@code group} or any mapped property is {@code null}
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
        // The SIGNED entry point: a negatively-signed all-zero rate differs from a positive one only in
        // its final byte, and the zero-rate group in the reference data makes that a live case rather
        // than a theoretical one. The bit travels on the entity's transient marker.
        ZonedDecimalCodec.ZonedValue rate = ZonedDecimalCodec.decodeSigned(rateImage,
                DIS_INT_RATE_LENGTH, ZonedDecimalCodec.MONETARY_SCALE, FIELD_INT_RATE);
        DisclosureGroup mapped =
                new DisclosureGroup(acctGroupId, tranTypeCd, tranCatCd, rate.value());
        mapped.setDisIntRateNegativeZero(rate.negativeZero());
        return mapped;
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
                // Alphanumeric fields are left-justified and space-padded. A value already at full width is
                // placed unchanged, which is what carries the contractual trailing spaces of the
                // padded group identifiers through.
                .putAlphanumeric(FIELD_ACCT_GROUP_ID, DIS_ACCT_GROUP_ID_OFFSET,
                        DIS_ACCT_GROUP_ID_LENGTH, acctGroupId)
                .putAlphanumeric(FIELD_TRAN_TYPE_CD, DIS_TRAN_TYPE_CD_OFFSET,
                        DIS_TRAN_TYPE_CD_LENGTH, tranTypeCd)
                // Numeric fields are right-justified and zero-filled, which is what preserves the
                // significant leading zeros of the category code.
                .putNumeric(FIELD_TRAN_CAT_CD, DIS_TRAN_CAT_CD_OFFSET, DIS_TRAN_CAT_CD_LENGTH,
                        tranCatCd)
                // The codec returns exactly the declared width, so this placement is positional only; it is
                // right-justified so an overpunched sign byte stays in the final position.
                .putNumeric(FIELD_INT_RATE, DIS_INT_RATE_OFFSET, DIS_INT_RATE_LENGTH,
                        ZonedDecimalCodec.encodeSigned(
                                new ZonedDecimalCodec.ZonedValue(intRate,
                                        group.isDisIntRateNegativeZero()
                                                && intRate.signum() == 0),
                                DIS_INT_RATE_LENGTH, ZonedDecimalCodec.MONETARY_SCALE,
                                FIELD_INT_RATE))
                // Stated explicitly rather than inherited from the buffer's default, so the deliberate
                // choice of space over the fixture's ASCII zero is visible right here.
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

    /** Builds the diagnostic for an absent mapped field, naming the artefact and the field. */
    private static String nullFieldMessage(String fieldName) {
        return ARTEFACT + " cannot be encoded because " + fieldName
                + " is null; every mapped field of a fixed-width record must be present";
    }
}
