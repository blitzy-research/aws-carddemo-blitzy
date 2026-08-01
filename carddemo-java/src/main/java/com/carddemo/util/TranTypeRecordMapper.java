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
 * Hand-written mapper between the legacy 60-byte transaction-type reference record and the
 * {@link TransactionType} entity, in both directions.
 *
 * <p>The layout authority is the copybook that declares the group {@code TRAN-TYPE-RECORD} with a
 * declared record length of 60 {@code [app/cpy/CVTRA03Y.cpy]}. Fifty-two of those sixty bytes carry
 * information and they resolve into exactly two attributes, which makes this the smallest mapped
 * layout in the module. Every offset and every length below is a named constant on this class, and
 * the mapping is placed by explicit offset arithmetic: no annotation, no mapping framework, no code
 * generator and no introspection participates, because the module's budget for reflective access is
 * zero and that budget is exactly why all eleven record mappers are written out by hand.
 *
 * <h2>Layout</h2>
 *
 * <pre>
 *  #   COBOL field       PIC     offset  length  Java property on TransactionType
 * ---  ----------------  ------  ------  ------  -----------------------------------------------
 *  1   TRAN-TYPE         X(02)        0       2  tranType     - the identifier, column tran_type
 *  2   TRAN-TYPE-DESC    X(50)        2      50  tranTypeDesc - column tran_type_desc
 *  -   FILLER            X(08)       52       8  not mapped, not persisted
 * </pre>
 *
 * <p>The width arithmetic is 2 + 50 = 52 mapped bytes, and 52 + 8 filler bytes = 60, which is the
 * record length the copybook declares. The cluster definition corroborates that geometry
 * independently: it declares {@code KEYS(2 0)} with {@code RECORDSIZE(60 60)} on an indexed cluster
 * {@code [app/jcl/TRANTYPE.jcl]} - a 2-byte key at offset zero, and identical low and high record
 * sizes, which is what a fixed-length record looks like in a cluster definition.
 *
 * <h2>The key is a bare field, so there is no identifier class</h2>
 *
 * <p>Three of the four reference-table copybooks wrap their key components inside a named group.
 * This one does not: its key field is declared at the same level as the description and the filler,
 * with no enclosing key group {@code [app/cpy/CVTRA03Y.cpy]}. Everything downstream follows from
 * that single fact. The entity has exactly two columns and a single-column identifier; there is no
 * identifier class, no composite-key type and nothing in the {@code com.carddemo.domain.id} package
 * corresponding to this layout, and this class references none of those things. Nor does it model a
 * key projection or a key-width constant beyond the trivial 2, because a 2-byte key at offset zero
 * <em>is</em> the code field already exposed as {@link #TRAN_TYPE_CODE_LENGTH}.
 *
 * <p>The identity column is named {@code tran_type} and carries no {@code _cd} suffix, which
 * diverges from the sibling columns that name a transaction type elsewhere in the schema - the
 * transaction and daily-transaction type codes, the category-balance type code, the disclosure-group
 * type code and the first component of the category composite key all use {@code tran_type_cd}. The
 * divergence is inherited from the shorter legacy field name and is preserved deliberately. It must
 * not be harmonised for the sake of consistency: the column name is part of the schema contract that
 * the persistence provider validates at start-up, so renaming it would fail the application rather
 * than tidy it.
 *
 * <h2>Two different 60-byte layouts coexist, and a length check cannot tell them apart</h2>
 *
 * <pre>
 * layout      composition       key                                  description  filler
 * ----------  ----------------  -----------------------------------  -----------  ------
 * CVTRA03Y    2 + 50 + 8        bare 2-byte code at offset 0          offset  2    8
 * CVTRA04Y    2 + 4 + 50 + 4    composite 6-byte key at offset 0      offset  6    4
 * </pre>
 *
 * <p>Both records measure 60 bytes, and both carry a 50-byte description, yet nothing else about
 * them agrees. The description begins at offset 2 here and at offset 6 in the category layout
 * {@code [app/cpy/CVTRA04Y.cpy]}, the filler run is 8 bytes here and 4 bytes there, and the keys are
 * attested separately by the two cluster definitions: {@code KEYS(2 0)}
 * {@code [app/jcl/TRANTYPE.jcl]} against {@code KEYS(6 0)} {@code [app/jcl/TRANCATG.jcl]}.
 *
 * <p><strong>A category record handed to this mapper will pass the width check and produce
 * nonsense.</strong> Sixty bytes is sixty bytes, so the geometric guard this class relies on cannot
 * distinguish the two records, and it does not try: no content sniffing, no probing of the fourth
 * through sixth bytes for digits, no heuristic of any kind appears here. Selecting the right mapper
 * for the record in hand is the caller's responsibility, and it is a responsibility a reader will
 * only take seriously if the hazard is stated. The practical defence is that the two mappers share
 * no constant, no offset and no slicing helper. Every constant on this class is prefixed so that it
 * reads as belonging to this layout and to no other, and a future edit that tried to hoist a
 * "common 60-byte record width" into one place would be reintroducing precisely the confusion this
 * separation exists to prevent.
 *
 * <h2>What the fixture actually contains</h2>
 *
 * <p>The reference fixture {@code [app/data/ASCII/trantype.txt]} measures 427 bytes, which is 7
 * records at a stride of 61 - the 60-byte record plus one line-feed terminator each. The terminator
 * is a record separator and never record content, so a caller reading the file must exclude it;
 * presenting 61 bytes to {@link #fromRecord(String)} is rejected rather than trimmed, and the
 * diagnostic points at the unstripped terminator because an overshoot of exactly one byte has almost
 * no other cause.
 *
 * <pre>
 * offset  observed across all seven records
 * ------  -----------------------------------------------------------------------
 *      0  01, 02, 03, 04, 05, 06 and 07 - sequential and zero-padded
 *      2  descriptions from Purchase through Adjustment, each space-padded to 50
 *     52  eight ASCII-zero bytes - NOT spaces
 * </pre>
 *
 * <p>Those seven codes and descriptions are the complete reference vocabulary, and the seed
 * migration loads exactly 7 rows from this fixture. The figure 7 is a measured row count, not a
 * capacity assumption: nothing here is sized, tuned or budgeted against it.
 *
 * <h2>Round-trip comparisons stop at offset 52</h2>
 *
 * <p>{@link #toRecord(TransactionType)} emits the filler run as spaces. A COBOL filler item with no
 * value clause is uninitialised, so no byte value is canonical, and the sample data proves the point
 * by disagreeing with itself: the four master files carry space filler while the four reference-table
 * files - this one included - carry ASCII-zero filler. Space is the module-wide default, and this
 * layout is one of the four that departs from what its own fixture holds. That divergence is
 * recorded as a source anomaly rather than corrected in either direction, because correcting it in
 * either direction would mean inventing a canonical value the legacy record never defined.
 *
 * <p>The consequence is a hard rule for anything that compares output with the fixture:
 * <strong>compare only the mapped data prefix, the byte range from 0 up to but excluding
 * {@link #TRAN_TYPE_MAPPED_LENGTH}.</strong> A whole-record 60-byte comparison against
 * {@code [app/data/ASCII/trantype.txt]} <strong>will fail</strong>, and it will fail on the eight
 * filler bytes alone - the fixture holds ASCII zero where this mapper emits space. That failure
 * would say nothing about the mapping and everything about a comparison bound chosen one byte-range
 * too wide.
 *
 * <h2>The description is 50 bytes here and 15 bytes in the report</h2>
 *
 * <p>The description field is 50 bytes wide in this record and this mapper always carries all 50 of
 * them, untruncated and untrimmed. The daily transaction report narrows it: the report structure
 * declares its own type-description field at 15 bytes {@code [app/cpy/CVTRA07Y.cpy]} and the report
 * program moves the 50-byte value into it {@code [app/cbl/CBTRN03C.cbl]}, so the value is silently
 * truncated to its leading 15 characters exactly as a COBOL move performs it. That narrowing belongs
 * to {@link ReportLineFormatter}, which owns the 133-byte report line and declares the 15-byte width
 * as {@code ReportLineFormatter.TYPE_DESCRIPTION_WIDTH}. It is stated here so that a reader who meets
 * the 15-byte field first does not mistake this mapper's full 50 bytes for a defect, and so that
 * nobody is tempted to move the truncation upstream into the mapper, where it would corrupt every
 * other consumer of the same description.
 *
 * <h2>The code stays text, and nothing is normalised</h2>
 *
 * <p>The type code is a 2-byte alphanumeric field whose seeded values are {@code 01} through
 * {@code 07}. The leading zero is significant, so the code is never parsed to a numeric type and
 * re-formatted, and it is never treated as numeric merely because every seeded value happens to be
 * made of digits. Both fields are alphanumeric, so both are placed left-justified and space-padded
 * on output, which is what the legacy declaration means; placing the code right-justified and
 * zero-padded would quietly turn a value of {@code 1} into {@code 01} and manufacture a leading zero
 * the caller never supplied.
 *
 * <p>Nothing read here is trimmed, stripped, case-folded, pad-normalised, truncated or validated.
 * The description's trailing spaces are part of the published external width, so a description that
 * arrives space-padded to 50 leaves again space-padded to 50, byte for byte.
 *
 * <h2>What this mapper deliberately does not do</h2>
 *
 * <ul>
 *   <li><strong>No enumeration of the seven type codes.</strong> They are reference <em>data</em>,
 *       loaded by a seed migration, not a compile-time vocabulary. Turning them into an enumeration
 *       would make the arrival of an eighth seeded type an application change and a redeployment,
 *       which is precisely the coupling a reference table exists to avoid. This class therefore
 *       references nothing in the enumeration package and performs no lookup, whitelist check or
 *       membership test against the seeded values.</li>
 *   <li><strong>No content validation.</strong> The only guard is geometric. No migration version
 *       declares a foreign key that targets or originates from this table, so no referential
 *       assumption may be baked in here, and none is.</li>
 *   <li><strong>No decimal handling.</strong> Both fields are alphanumeric, so this layout has no
 *       numeric value field at all. It is the one mapper in the family that neither imports nor
 *       invokes the zoned-decimal codec, and no decimal type, scale, rounding mode or
 *       floating-point type appears anywhere in this file.</li>
 *   <li><strong>No version attribute.</strong> The entity declares none, so optimistic locking does
 *       not apply to this reference table and this mapper neither reads nor writes such a value.</li>
 *   <li><strong>No slicing of its own.</strong> Every read and every placement goes through
 *       {@link FixedWidthFieldReader}; this class contains no substring call.</li>
 *   <li><strong>No logging, no persistence, no clock, no environment, no randomness.</strong> The
 *       mapping is a pure function of its argument, which is what makes it exhaustively testable.</li>
 * </ul>
 *
 * <h2>Failure contract</h2>
 *
 * <p>A record image whose encoded byte length is anything other than {@link #TRAN_TYPE_RECORD_LENGTH}
 * raises {@link IllegalArgumentException}, and the message names the artefact - the record group and
 * the copybook that declares it - the expected encoded width and the actual encoded byte length. The
 * width is always measured in encoded bytes and never in characters. A {@code null} argument raises
 * {@link NullPointerException} by way of {@link Objects#requireNonNull(Object, String)}. Input is
 * never silently padded, never silently truncated, never partially mapped and never answered with
 * {@code null}.
 *
 * <p>No exception type from the module's own exception package is used, and that is deliberate rather
 * than an oversight: not one of them models "the caller handed me the wrong number of bytes". A short
 * or long record has no legacy antecedent, because a keyed or sequential mainframe record is
 * fixed-length by construction, so the condition is a caller defect in the migrated code and belongs
 * to the language's own vocabulary for a bad argument.
 *
 * <h2>Shape and thread safety</h2>
 *
 * <p>This is a static contract rather than a component: the class is final, its only constructor is
 * private and raises, every member is static, and it holds no state of any kind - mutable or
 * otherwise - beyond the immutable layout constants. It is therefore safe to use from any number of
 * threads at once, and every layout fact it publishes is single-valued across the whole module.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * TransactionType type = TranTypeRecordMapper.fromRecord(sixtyByteImage);
 * String code = type.getTranType();      // "01", leading zero intact
 * String desc = type.getTranTypeDesc();  // 50 bytes, trailing spaces intact
 *
 * String image = TranTypeRecordMapper.toRecord(type);   // exactly 60 bytes, space filler
 * // Comparing against the fixture: take the byte range [0, TRAN_TYPE_MAPPED_LENGTH) and
 * // never the full TRAN_TYPE_RECORD_LENGTH, because the filler bytes disagree by design.
 * }</pre>
 *
 * <h2>Decisions recorded for the decision log</h2>
 *
 * <ol>
 *   <li>The key field is declared bare, with no enclosing key group, so the entity has exactly two
 *       columns, a single-column identifier and no identifier class - the only reference entity in
 *       the family without one.</li>
 *   <li>The identity column is {@code tran_type} rather than {@code tran_type_cd}, diverging from the
 *       transaction-category and transaction entities; inherited from the copybook and preserved
 *       deliberately.</li>
 *   <li>Two distinct 60-byte layouts coexist - 2 + 50 + 8 here against 2 + 4 + 50 + 4 for the
 *       category record - so a width check cannot distinguish them and the two mappers share no
 *       constant, offset or helper. {@code KEYS(2 0)} and {@code KEYS(6 0)} attest both keys.</li>
 *   <li>No enumeration is introduced for the seven seeded type codes; they are reference data loaded
 *       by a seed migration, not a compile-time vocabulary.</li>
 *   <li>The 50-byte description is truncated to 15 characters by the report line, and that truncation
 *       belongs to {@link ReportLineFormatter} rather than to this mapper.</li>
 *   <li>Filler bytes are not uniform in the sample data: the fixture carries eight ASCII-zero filler
 *       bytes while this mapper emits spaces, so round-trip assertions compare only the mapped prefix
 *       and a whole-record comparison would fail.</li>
 *   <li>Malformed fixed-width input raises {@link IllegalArgumentException} rather than a type from
 *       the module's own exception package.</li>
 * </ol>
 *
 * <h2>Provenance</h2>
 *
 * <p>Part of the migration of the AWS CardDemo z/OS application at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Legacy artefacts are cited, never
 * transcribed: no COBOL, copybook or job-control statement text is reproduced in this file. No
 * user-specified rules were provided for this engagement, so the work is held instead to the
 * enterprise standards the plan substitutes for them - among them a hermetic pinned build, a
 * zero-warning compilation gate, strict layer separation with fixed-width knowledge confined to this
 * layer, no code generation, a pure and therefore testable shape, full auditability of translation
 * decisions, and faithful translation in preference to idiomatic translation wherever the two
 * diverge.
 */
public final class TranTypeRecordMapper {

    /**
     * Encoded byte length of one complete record image, 60.
     *
     * <p>Declared by the copybook and corroborated by {@code RECORDSIZE(60 60)} on the cluster
     * definition {@code [app/jcl/TRANTYPE.jcl]}. It is the sum of {@link #TRAN_TYPE_MAPPED_LENGTH}
     * and {@link #TRAN_TYPE_FILLER_LENGTH}, that is 52 + 8. The category record measures 60 bytes
     * too, so this constant must never be shared with that layout's mapper: a shared width would
     * read as permission to share an offset, and the offsets differ.
     */
    public static final int TRAN_TYPE_RECORD_LENGTH = 60;

    /**
     * Encoded byte length of the mapped data prefix, 52, being 2 + 50.
     *
     * <p>Also the exclusive upper bound for every comparison of a produced image against the
     * reference fixture. The fixture carries ASCII-zero filler in the eight bytes beyond this point
     * while {@link #toRecord(TransactionType)} emits spaces, so a comparison that runs to the full
     * record width fails on filler alone.
     */
    public static final int TRAN_TYPE_MAPPED_LENGTH = 52;

    /**
     * Zero-based byte offset of the type code, 0.
     *
     * <p>Corroborated by {@code KEYS(2 0)} on the cluster definition {@code [app/jcl/TRANTYPE.jcl]}:
     * the key sits at offset zero, so the identifier is the leading substring of the record image and
     * never a surrogate.
     */
    public static final int TRAN_TYPE_CODE_OFFSET = 0;

    /**
     * Encoded byte length of the type code, 2.
     *
     * <p>Equal to the cluster's key length, since the whole of the key is this one field.
     */
    public static final int TRAN_TYPE_CODE_LENGTH = 2;

    /**
     * Zero-based byte offset of the description, 2.
     *
     * <p>The single most confusable number in this layout. The category record's description begins
     * at offset 6, not 2, because its key is a 6-byte composite {@code [app/cpy/CVTRA04Y.cpy]}.
     * Reading this layout at offset 6 - or that one at offset 2 - yields a plausible-looking string
     * and a wrong one.
     */
    public static final int TRAN_TYPE_DESCRIPTION_OFFSET = 2;

    /**
     * Encoded byte length of the description, 50.
     *
     * <p>Carried in full by this mapper. The report line narrows the same value to 15 bytes
     * {@code [app/cpy/CVTRA07Y.cpy]}, and that narrowing belongs to {@link ReportLineFormatter}.
     */
    public static final int TRAN_TYPE_DESCRIPTION_LENGTH = 50;

    /**
     * Zero-based byte offset of the trailing filler run, 52.
     *
     * <p>Equal to {@link #TRAN_TYPE_MAPPED_LENGTH}, because the filler begins exactly where the
     * mapped data ends.
     */
    public static final int TRAN_TYPE_FILLER_OFFSET = 52;

    /**
     * Encoded byte length of the trailing filler run, 8.
     *
     * <p>The category record's filler run is 4 bytes, not 8 {@code [app/cpy/CVTRA04Y.cpy]}. The two
     * layouts reach the same 60-byte total by different routes.
     */
    public static final int TRAN_TYPE_FILLER_LENGTH = 8;

    /**
     * The byte {@link #toRecord(TransactionType)} writes across the filler run, a space.
     *
     * <p>Published so that a caller comparing images need not restate the choice as a literal. The
     * reference fixture carries ASCII zero in this run instead, which is the source divergence
     * recorded above; space is the module-wide default and neither value is canonical.
     */
    public static final char TRAN_TYPE_FILLER_CHARACTER = ' ';

    /**
     * Artefact name carried into every diagnostic, naming both the record group and the copybook
     * that declares it, so a failure message identifies its subject without the reader having to
     * guess which of the two 60-byte layouts was involved.
     */
    private static final String ARTEFACT = "TRAN-TYPE-RECORD (CVTRA03Y)";

    /** Legacy name of the type code field, used only to make a diagnostic name the field. */
    private static final String CODE_FIELD = "TRAN-TYPE";

    /** Legacy name of the description field, used only to make a diagnostic name the field. */
    private static final String DESCRIPTION_FIELD = "TRAN-TYPE-DESC";

    /**
     * Refuses construction.
     *
     * <p>This class is a static contract holding the byte geometry of one legacy record. An instance
     * would be a per-caller copy of a layout the copybook defines exactly once, which is how two
     * callers come to disagree about a record that has only one correct shape. The guard raises an
     * error rather than an exception so that it cannot be caught and worked around, and it survives
     * reflective access, which a private constructor alone does not.
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
     * cannot drift apart. Placement is by explicit offset arithmetic against the constants declared
     * above; the filler run is not read at all.
     *
     * <p>The entity is built through its two-argument constructor rather than by instantiating and
     * then assigning: the no-argument constructor exists for the persistence provider and is
     * declared {@code protected}, so it is unreachable from this package. Both the constructor and
     * the entity's setters assign verbatim, so either route would store exactly these bytes; the
     * constructor is used because it cannot leave an attribute unset.
     *
     * @param record a reader already validated to be exactly {@link #TRAN_TYPE_RECORD_LENGTH} bytes
     * @return a fully populated entity, never {@code null}
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
     * cannot drift apart. Every byte of the record is accounted for by an explicit placement - the
     * code, the description and the filler run - which is how a forgotten field is noticed during
     * review rather than in a byte comparison.
     *
     * <p>Both attributes are required. A {@code null} code could not be written into a field the
     * cluster declares as its key, and a {@code null} description has no defensible rendering:
     * substituting spaces would silently manufacture a blank description, and skipping the field
     * would leave the buffer's initial spaces in place with exactly the same result. Refusing is the
     * only answer that tells the caller what is wrong, and each check names the attribute at fault
     * rather than reporting a generic bad value.
     *
     * @param transactionType the entity to render
     * @return a reader over the completed image, always exactly {@link #TRAN_TYPE_RECORD_LENGTH}
     *         bytes
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
