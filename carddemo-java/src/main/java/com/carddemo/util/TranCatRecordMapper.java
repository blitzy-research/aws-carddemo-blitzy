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

import com.carddemo.domain.TransactionCategory;
import java.util.Objects;

/**
 * Hand-written, reflection-free mapper between the 60-byte transaction-category reference record
 * {@code TRAN-CAT-RECORD} [app/cpy/CVTRA04Y.cpy] and the {@link TransactionCategory} entity, whose
 * identity is the two-component composite
 * {@link com.carddemo.domain.id.TransactionCategoryId TransactionCategoryId}.
 *
 * <p>The record is pure reference data: the posting, interest and reporting paths read it and never
 * write it. This class is the <em>only</em> place in the module where this layout's byte offsets are
 * declared. The entity carries column widths and no offsets, and it is produced by this mapper and
 * never refers to it, so the layer dependency runs one way only.
 *
 * <h2>Verified layout - 60 bytes, of which 56 are mapped</h2>
 *
 * <p><strong>Width arithmetic.</strong> The key is 2 + 4 = 6; the 50-byte description then brings
 * the mapped data to 6 + 50 = 56; the 4 filler bytes bring the stored record to 56 + 4 = 60. The
 * cluster definition corroborates both figures independently: the base cluster is defined
 * with an indexed organisation, a 6-byte key at offset 0 and a fixed 60-byte record
 * [app/jcl/TRANCATG.jcl]. A key length of 6 at offset 0 confirms the key has exactly the two
 * components above, in that order, and
 * confirms that the key is the leading substring of the record image - which is why the identifier
 * is the legacy business key itself and never a surrogate (decision D-29). A surrogate would break
 * the record-image-to-row correspondence on which byte-level output parity depends.
 *
 * <h2>Collision one - {@code TRAN-CAT-KEY} is 6 bytes here and 17 bytes in CVTRA01Y</h2>
 *
 * <p>Two different copybooks declare a group named {@code TRAN-CAT-KEY} and they are different
 * sizes: 6 bytes here - a 2-byte type code plus a 4-byte category code [app/cpy/CVTRA04Y.cpy] -
 * against 17 bytes for the category-balance group, which leads with an 11-byte account identifier
 * before its own type and category components [app/cpy/CVTRA01Y.cpy].
 *
 * <p><strong>The 6-byte key is NOT a prefix of the 17-byte key</strong> - the 17-byte key leads with
 * an 11-byte account identifier that this key does not have at all [app/cpy/CVTRA01Y.cpy]. The two
 * type-and-category pairs sit at <strong>completely different offsets</strong>: here at 0 and 2,
 * there at 11 and 13. The provisioning jobs attest both widths independently, 6 bytes here
 * [app/jcl/TRANCATG.jcl] against 17 for the category-balance cluster
 * [app/jcl/TCATBALF.jcl]. Consequently no key constant, no key-building helper and no identifier
 * class is ever shared between this mapper and the category-balance mapper, and no shared supertype
 * is introduced to "reuse" the overlapping components. Decision D-37 records the collision.
 *
 * <h2>Collision two - two distinct 60-byte layouts coexist</h2>
 *
 * <p>The transaction-<em>type</em> record [app/cpy/CVTRA03Y.cpy] is also exactly 60 bytes, but its
 * geometry differs:
 *
 * <pre>{@code layout
 *           composition     key                              description at  filler
 * CVTRA04Y (here)  2 + 4 + 50 + 4  composite TRAN-CAT-KEY, 6 bytes  offset 6        4 bytes at 56
 * CVTRA03Y (type)  2 + 50 + 8      bare TRAN-TYPE, 2 bytes          offset 2        8 bytes at 52
 * }</pre>
 *
 * <p><strong>Because the totals coincide, a length check cannot distinguish the two records.</strong>
 * A transaction-type image handed to this mapper passes width validation and yields a description
 * shifted by four bytes, with the first four characters of that description swallowed into the
 * category code. The result compiles, runs and looks plausible. No content sniffing is attempted to
 * tell the two layouts apart, because any such heuristic would be wrong for some legitimate record;
 * the caller is responsible for handing the right record image to the right mapper. The two mappers
 * therefore share no constant, no offset and no slicing helper.
 *
 * <h2>Fixture evidence - app/data/ASCII/trancatg.txt</h2>
 *
 * <p>The fixture measures 1,098 bytes, which is 18 records at a 61-byte stride: the 60-byte record
 * plus one {@code 0x0A} terminator. The terminator is a record separator and never record content
 * (decision D-30), so a caller strips it before calling here. Inspected by offset, the fixture shows
 * a 2-character type code at 0, a 4-character category code at 2, a 50-byte space-padded description
 * at 6 ranging from {@code Regular Sales Draft} in the first record to
 * {@code Sales draft credit adjustment} in the last, and at 56 four ASCII {@code '0'} bytes rather
 * than spaces. The descriptions are <strong>mixed case</strong>: the last of them carries a
 * lower-case {@code d} in {@code draft}, so any case folding would corrupt the reference vocabulary,
 * and none is performed anywhere in this class [app/data/ASCII/trancatg.txt]. The seed migration
 * {@code V3__seed_reference_data.sql} loads exactly those 18 rows, which is a measured row count
 * and not a tuning figure.
 *
 * <h2>Filler bytes and the exact comparison bound [0, 56)</h2>
 *
 * <p>{@code toRecord} emits the 4-byte filler as <strong>spaces</strong>. The legacy filler run is
 * declared without an initial value and is uninitialised, so no byte value is canonical, and this
 * layout's
 * fixture is one of the four that disagree with the module-wide default: the four master fixtures
 * carry space filler while the four reference-table fixtures, this one included, carry ASCII-zero
 * filler. Decision D-10 records the resolution and anomaly 20 the source divergence.
 *
 * <p><strong>Every fixture round-trip assertion for this layout therefore compares only the mapped
 * data prefix {@code [0, 56)}</strong>, the bound published here as {@link #MAPPED_DATA_WIDTH}. A
 * whole-record 60-byte round-trip comparison against the fixture <strong>will fail</strong>, because
 * the fixture's 4 filler bytes are {@code '0'} while this mapper emits spaces. This layout has the
 * narrowest filler run in the estate at 4 bytes, so exactly four bytes differ - a subtle failure that
 * is easy to misdiagnose as a mapping defect when it is in fact the documented filler divergence.
 *
 * <h2>The description is 50 bytes here and is narrowed elsewhere</h2>
 *
 * <p>The category description is 50 bytes wide here and this mapper always maps all 50,
 * untruncated and untrimmed. The daily transaction report narrows it: the report line declares its
 * own 29-byte description field [app/cpy/CVTRA07Y.cpy], so moving the 50-byte description into it
 * keeps only the leading 29 characters [app/cbl/CBTRN03C.cbl]. The sibling type description in the
 * same report line is narrowed to 15, not 29 - two different truncation
 * widths in one line. Both narrowings belong to {@link ReportLineFormatter} and neither belongs here,
 * so the report's shorter text is not a defect in this mapper. Two of the 18 seeded descriptions are
 * exactly 29 characters long once their padding is disregarded - {@code Online purchase
 * authorization} and {@code Sales draft credit adjustment} - and they are the longest of the 18, so
 * both sit precisely on the report's truncation boundary and make an off-by-one there immediately
 * visible.
 *
 * <h2>Both codes stay text</h2>
 *
 * <p>The type code is 2 bytes and the category code 4, the latter carrying values
 * such as {@code 0001}, so leading zeros are significant in both. Neither is ever parsed to
 * {@code int} or {@code long} and re-formatted, and neither is treated as numeric even though every
 * seeded value happens to consist of digits: a category of {@code 0001} that narrowed to {@code 1}
 * would no longer reconstruct the 6-byte key image from its two components. No numeric, decimal,
 * temporal or monetary conversion occurs anywhere in this class, so it references no decimal codec
 * and uses no floating-point type.
 *
 * <h2>What this mapper deliberately does not do</h2>
 *
 * <p><strong>Neither code becomes a Java {@code enum}, and no membership check against the 18 seeded
 * values exists.</strong> They are reference <em>data</em>, loaded by a seed migration and extensible
 * by a later one; compiling them into a closed vocabulary would reject a row the legacy system accepts.
 * Decision D-24 draws the same line for the codes that genuinely are enumerated. Beyond the record
 * width there is no validation of any kind, and in particular none of the type code against the
 * transaction-type table: no migration declares a foreign key into or out of this table, so no
 * referential assumption may be baked in here (decision D-38).
 *
 * <p><strong>All three values are carried raw</strong> - no trimming, case folding, pad normalisation
 * or truncation - because trailing spaces in the description are contractual and must survive both
 * directions. The entity carries no {@code @Version} attribute either, being read-only reference data
 * that no online transaction updates. Fixed-width mapping is hand-written by explicit offset arithmetic
 * precisely so that the module's reflection budget stays at zero (decision D-26).
 *
 * <h2>Failure contract</h2>
 *
 * <p>Every decode validates the record image as exactly {@link #RECORD_WIDTH} <em>encoded bytes</em>
 * - never a character count - and any other length raises {@link IllegalArgumentException} naming
 * this artefact, the expected width and the actual encoded byte length. Input is never silently
 * padded, never truncated, never partially mapped and never returned as {@code null}. An over-length
 * field value on the encode side is likewise rejected rather than truncated (decision D-06). No
 * exception type from the module's own package is used, deliberately: none of them models "the caller
 * handed me the wrong number of bytes", and a short record has no legacy antecedent at all, because
 * VSAM records are fixed length by construction - so the condition is a caller defect and the check
 * is a Java-only defensive guard (decisions D-08 and D-11). {@code null} arguments raise
 * {@link NullPointerException} through {@link Objects#requireNonNull(Object, String)}.
 * <strong>The width check cannot catch collision two</strong>: the transaction-type layout is exactly
 * as wide as this one.
 *
 * <h2>Character encoding</h2>
 *
 * <p>This class performs no character conversion of its own. Every conversion between text and bytes,
 * in both directions, happens inside {@link FixedWidthFieldReader}, which names US-ASCII explicitly
 * at each conversion and rejects any value it cannot represent rather than transcoding it to a
 * substitute byte. No platform-default conversion therefore exists anywhere in this mapper's path,
 * and no slicing is performed here by any other means.
 *
 * <h2>Shape and thread safety</h2>
 *
 * <p>Stateless by construction: the class is final, its constructor is private, every member is
 * static and no mutable static state exists. Every operation is pure and side-effect free - no
 * input or output, no clock, no environment and no randomness - so instances are unnecessary and all
 * operations are safe to call concurrently. The entity a decode returns is a mutable persistence
 * object, as the provider requires, and is owned by its caller.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code String
 * image = fixtureRecord;          // 60 bytes, 0x0A terminator already stripped
 * TransactionCategory row = TranCatRecordMapper.fromRecord(image);
 * row.getTranTypeCd();                   // "01"
 * row.getTranCatCd();                    // "0001", leading zeros intact
 * row.getTranCatTypeDesc();              // 50 bytes, space padded, mixed case preserved
 *
 * String rebuilt = TranCatRecordMapper.toRecord(row);
 * // rebuilt is 60 characters and matches image over its first MAPPED_DATA_WIDTH characters;
 * // the last FILLER_LENGTH characters are spaces here and ASCII zeros in the fixture.
 * TranCatRecordMapper.typeAndCategoryKeyImage(row);   // "010001", the 6-byte stored key
 * }</pre>
 *
 * @see TransactionCategory
 * @see FixedWidthFieldReader
 * @see com.carddemo.domain.id.TransactionCategoryId
 */
public final class TranCatRecordMapper {

    /**
     * Layout name this mapper puts in every diagnostic, naming both the record group and the
     * copybook it is declared in.
     *
     * <p>Both names appear because either one alone is ambiguous in this estate: the group name
     * collides on its key with another copybook and the record width collides with a third layout,
     * so a diagnostic that named only one of them would leave a reader guessing which 60-byte record
     * had actually been rejected. Published rather than private so that a test may assert the text of
     * a rejection instead of matching a substring by eye.
     */
    public static final String ARTEFACT = "TRAN-CAT-RECORD (CVTRA04Y)";

    /**
     * Layout name used in diagnostics about the composite key alone, stating the copybook and the
     * width so that the 6-byte key can never be read as the identically named 17-byte key of the
     * category-balance layout.
     */
    public static final String KEY_ARTEFACT = "TRAN-CAT-KEY (CVTRA04Y, 6 bytes)";

    /**
     * Stored record width in encoded bytes, 60, corroborated by the fixed record length of the
     * cluster definition [app/jcl/TRANCATG.jcl]. Equal to {@link #MAPPED_DATA_WIDTH} plus
     * {@link #FILLER_LENGTH}.
     *
     * <p>This width is shared with the transaction-type layout, which is why it can never be used to
     * tell the two apart; see the collision-two discussion on this class.
     */
    public static final int RECORD_WIDTH = 60;

    /**
     * Mapped data width in encoded bytes, 56, being the key plus the description and excluding the
     * trailing filler run.
     *
     * <p>This is also the exclusive upper bound of the round-trip comparison window {@code [0, 56)}:
     * the fixture carries ASCII-zero filler while this mapper emits space filler, so a comparison
     * that ran to 60 would fail on the last four bytes alone.
     */
    public static final int MAPPED_DATA_WIDTH = 56;

    /**
     * Width in encoded bytes of the composite key, <strong>6</strong>, comprising the 2-byte type
     * code followed by the 4-byte category code and corroborated by the cluster's key length
     * [app/jcl/TRANCATG.jcl].
     *
     * <p>Named for its two components on purpose. The identically named key group of the
     * category-balance layout is 17 bytes and leads with an 11-byte account identifier, so it is a
     * different key entirely rather than a longer form of this one; naming this constant after the
     * pair it actually contains makes the two impossible to confuse at a call site.
     */
    public static final int TYPE_AND_CATEGORY_KEY_WIDTH = 6;

    /** Zero-based byte offset of {@code TRAN-TYPE-CD}, key component 1: 0. */
    public static final int TRAN_TYPE_CD_OFFSET = 0;

    /** Byte length of the transaction type code: 2. */
    public static final int TRAN_TYPE_CD_LENGTH = 2;

    /** Zero-based byte offset of {@code TRAN-CAT-CD}, key component 2: 2. */
    public static final int TRAN_CAT_CD_OFFSET = 2;

    /** Byte length of the transaction category code, carried as text rather than a number: 4. */
    public static final int TRAN_CAT_CD_LENGTH = 4;

    /**
     * Zero-based byte offset of {@code TRAN-CAT-TYPE-DESC}: 6.
     *
     * <p>Six, not two. The transaction-type layout puts its own 50-byte description at offset 2
     * because it has no category component in front of it, and that four-byte shift is the whole of
     * collision two.
     */
    public static final int TRAN_CAT_TYPE_DESC_OFFSET = 6;

    /**
     * Byte length of the category description: 50.
     *
     * <p>All 50 bytes are always mapped. The narrowing to 29 for the report line, and the sibling
     * type description's narrowing to 15, belong to {@link ReportLineFormatter}.
     */
    public static final int TRAN_CAT_TYPE_DESC_LENGTH = 50;

    /** Zero-based byte offset of the trailing {@code FILLER} run: 56. */
    public static final int FILLER_OFFSET = 56;

    /**
     * Byte length of the trailing filler run: 4.
     *
     * <p>Four, not eight. The transaction-type layout's filler run is 8 bytes at offset 52, which is
     * the other half of collision two. Four bytes is the narrowest filler run in the estate, so a
     * whole-record comparison against the ASCII-zero-filled fixture fails on four bytes only.
     */
    public static final int FILLER_LENGTH = 4;

    /**
     * The byte this mapper writes across the filler run, a space.
     *
     * <p>Space is the module-wide default for an uninitialised {@code FILLER} run (decision D-10).
     * The reference-table fixture for this layout carries ASCII zero instead, and that divergence is
     * recorded as anomaly 20 rather than resolved by changing this value: neither byte is canonical,
     * and reproducing the fixture's byte here would misrepresent an uninitialised field as a
     * contractual one.
     */
    public static final char EMITTED_FILLER_CHARACTER = ' ';

    /**
     * Prevents instantiation, raising rather than returning.
     *
     * <p>Every operation is a pure static function of its arguments, so an instance would carry no
     * state and grant no capability. The constructor raises because this class is layout-bearing: it
     * holds the only copy of this record's offsets, and a per-instance copy would let two callers
     * disagree about a layout the legacy record defines exactly once. Private accessibility alone
     * would not prevent that, since a private constructor can still be reached reflectively.
     */
    private TranCatRecordMapper() {
        throw new AssertionError("TranCatRecordMapper is a static utility and is not instantiable");
    }

    /**
     * Maps a 60-byte record image held as text into a fully populated entity.
     *
     * <p>All three values are taken raw: untrimmed, unstripped, not case-folded, not normalised and
     * not truncated. The 50-byte description therefore arrives complete with its contractual trailing
     * spaces, and a category code of {@code 0001} arrives with its leading zeros intact.
     *
     * @param recordImage the complete record image, exactly {@link #RECORD_WIDTH} encoded bytes, with
     *                    any {@code 0x0A} terminator already stripped by the caller
     * @return a new entity carrying the two key components and the description
     * @throws NullPointerException     if {@code recordImage} is {@code null}
     * @throws IllegalArgumentException if the image is not exactly {@link #RECORD_WIDTH} encoded
     *                                  bytes, the message naming this artefact, the expected width
     *                                  and the actual encoded byte length, or if the image contains a
     *                                  character US-ASCII cannot represent
     */
    public static TransactionCategory fromRecord(final String recordImage) {
        Objects.requireNonNull(recordImage, "recordImage must not be null for " + ARTEFACT);
        return fromReader(FixedWidthFieldReader.of(ARTEFACT, recordImage, RECORD_WIDTH));
    }

    /**
     * Maps a 60-byte record image held as bytes into a fully populated entity.
     *
     * <p>The preferred entry point for a batch reader that already holds raw bytes, because it leaves
     * no charset decision to the caller. Behaviour is otherwise identical to
     * {@link #fromRecord(String)}.
     *
     * @param recordImage the complete record image, exactly {@link #RECORD_WIDTH} bytes, with any
     *                    {@code 0x0A} terminator already stripped by the caller
     * @return a new entity carrying the two key components and the description
     * @throws NullPointerException     if {@code recordImage} is {@code null}
     * @throws IllegalArgumentException if the image is not exactly {@link #RECORD_WIDTH} bytes, the
     *                                  message naming this artefact, the expected width and the
     *                                  actual byte length, or if any byte is not 7-bit ASCII
     */
    public static TransactionCategory fromRecord(final byte[] recordImage) {
        Objects.requireNonNull(recordImage, "recordImage must not be null for " + ARTEFACT);
        return fromReader(FixedWidthFieldReader.of(ARTEFACT, recordImage, RECORD_WIDTH));
    }

    /**
     * Maps one 60-byte record held inside a larger buffer into a fully populated entity.
     *
     * <p>The seam for a caller holding a whole newline-terminated fixture in one buffer. That file's
     * stride is {@link #RECORD_WIDTH} plus one, so record <em>i</em> starts at
     * {@code i * (RECORD_WIDTH + 1)} and the terminator is left behind by construction: this overload
     * reads exactly {@link #RECORD_WIDTH} bytes from {@code from} and never one more. Stride
     * arithmetic stays with the caller, because a file's stride is a property of the file rather than
     * of the record layout.
     *
     * @param buffer buffer containing the record, and possibly many others
     * @param from   zero-based index in {@code buffer} at which the record starts
     * @return a new entity carrying the two key components and the description
     * @throws NullPointerException     if {@code buffer} is {@code null}
     * @throws IllegalArgumentException if {@code from} is negative, if the range
     *                                  {@code [from, from + 60)} does not lie wholly inside
     *                                  {@code buffer}, or if any byte in that range is not 7-bit
     *                                  ASCII
     */
    public static TransactionCategory fromRecord(final byte[] buffer, final int from) {
        Objects.requireNonNull(buffer, "buffer must not be null for " + ARTEFACT);
        return fromReader(FixedWidthFieldReader.of(ARTEFACT, buffer, from, RECORD_WIDTH));
    }

    /**
     * Assembles the entity from an already validated reader.
     *
     * <p>The single decode implementation, so the three public entry points cannot drift apart in
     * which offsets they use. Each field is named as well as positioned, so a mis-declared offset
     * identifies the field it belongs to in any diagnostic. The filler run at
     * {@link #FILLER_OFFSET} is deliberately not read: it is not mapped, not persisted and carries no
     * canonical value.
     *
     * <p>The entity is built through its public all-arguments constructor rather than through its
     * no-argument constructor and setters. That constructor is the same plain assignment the setters
     * perform - it trims nothing, pads nothing and validates nothing - and the no-argument
     * constructor is {@code protected} for the persistence provider's benefit, so it is not reachable
     * from this package at all. No identifier object is ever set onto the entity, because with an
     * {@code @IdClass} the key components live on the entity itself.
     *
     * @param record the validated 60-byte record image
     * @return a new entity carrying the two key components and the description
     */
    private static TransactionCategory fromReader(final FixedWidthFieldReader record) {
        return new TransactionCategory(
                record.field("TRAN-TYPE-CD", TRAN_TYPE_CD_OFFSET, TRAN_TYPE_CD_LENGTH),
                record.field("TRAN-CAT-CD", TRAN_CAT_CD_OFFSET, TRAN_CAT_CD_LENGTH),
                record.field("TRAN-CAT-TYPE-DESC", TRAN_CAT_TYPE_DESC_OFFSET,
                        TRAN_CAT_TYPE_DESC_LENGTH));
    }

    /**
     * Renders an entity as a 60-byte record image held as text, the exact inverse of a decode over
     * the mapped prefix {@code [0, 56)}.
     *
     * <p>Values are placed exactly as the copybook justifies them: the type code and the description
     * are left-justified and space-padded, and the category code is right-justified and
     * zero-padded. A value already at its full field
     * width is placed unchanged, which is what preserves the description's trailing spaces and a
     * category code's leading zeros. An over-length value is rejected rather than truncated: a
     * truncated field would leave the record exactly the right width while carrying a wrong value,
     * which is the one failure a downstream width check can never catch.
     *
     * <p>The filler run is written as {@link #EMITTED_FILLER_CHARACTER}, so a comparison against the
     * reference-table fixture must stop at {@link #MAPPED_DATA_WIDTH}; see the filler discussion on
     * this class.
     *
     * @param category the entity to render; its three mapped values must all be present
     * @return the complete record image, exactly {@link #RECORD_WIDTH} characters
     * @throws NullPointerException     if {@code category} is {@code null} or any of its three mapped
     *                                  values is {@code null}
     * @throws IllegalArgumentException if any value is wider than its field or contains a character
     *                                  US-ASCII cannot represent
     */
    public static String toRecord(final TransactionCategory category) {
        return encode(category).image();
    }

    /**
     * Renders an entity as a 60-byte record image held as bytes.
     *
     * <p>The form a byte-exact writer wants, since output parity is asserted over bytes rather than
     * characters. Behaviour is otherwise identical to {@link #toRecord(TransactionCategory)}, and the
     * returned array is freshly allocated on every call.
     *
     * @param category the entity to render; its three mapped values must all be present
     * @return a new array of exactly {@link #RECORD_WIDTH} bytes
     * @throws NullPointerException     if {@code category} is {@code null} or any of its three mapped
     *                                  values is {@code null}
     * @throws IllegalArgumentException if any value is wider than its field or contains a character
     *                                  US-ASCII cannot represent
     */
    public static byte[] toRecordBytes(final TransactionCategory category) {
        return encode(category).toByteArray();
    }

    /**
     * Builds the record image once for both encode entry points.
     *
     * <p>Placement is positional and absolute, and the four runs named here - three fields and the
     * filler - add up to the full 60 bytes, so a field omitted by a future edit shows up as a gap in
     * the arithmetic rather than as a silently space-filled column. The filler is named explicitly
     * even though the builder's buffer is already space-initialised, which keeps the choice of filler
     * byte visible at the point it is made.
     *
     * @param category the entity to render
     * @return a reader over the completed 60-byte image
     */
    private static FixedWidthFieldReader encode(final TransactionCategory category) {
        Objects.requireNonNull(category, "category must not be null for " + ARTEFACT);
        return FixedWidthFieldReader.builder(ARTEFACT, RECORD_WIDTH)
                .putAlphanumeric("TRAN-TYPE-CD", TRAN_TYPE_CD_OFFSET, TRAN_TYPE_CD_LENGTH,
                        requireValue(category.getTranTypeCd(), "TRAN-TYPE-CD"))
                .putNumeric("TRAN-CAT-CD", TRAN_CAT_CD_OFFSET, TRAN_CAT_CD_LENGTH,
                        requireValue(category.getTranCatCd(), "TRAN-CAT-CD"))
                .putAlphanumeric("TRAN-CAT-TYPE-DESC", TRAN_CAT_TYPE_DESC_OFFSET,
                        TRAN_CAT_TYPE_DESC_LENGTH,
                        requireValue(category.getTranCatTypeDesc(), "TRAN-CAT-TYPE-DESC"))
                .putFiller(FILLER_OFFSET, FILLER_LENGTH, EMITTED_FILLER_CHARACTER)
                .build();
    }

    /**
     * Renders the 6-byte composite key image of an entity, the leading substring of its record image.
     *
     * <p>The exact inverse of taking the leading {@link #TYPE_AND_CATEGORY_KEY_WIDTH} bytes of a
     * record image, which is what the cluster definition makes the stored key
     * [app/jcl/TRANCATG.jcl]. Useful wherever the key is needed as one value rather than two - key
     * ordering, a keyed extract, or asserting that a key round-trips - without any caller
     * reassembling it and risking the component order.
     *
     * <p>Named for the pair of components it contains so that it can never be mistaken for the
     * identically named but structurally unrelated 17-byte key of the category-balance layout, which
     * leads with an account identifier this key does not contain.
     *
     * @param category the entity whose key image is wanted; both key components must be present
     * @return the key image, exactly {@link #TYPE_AND_CATEGORY_KEY_WIDTH} characters
     * @throws NullPointerException     if {@code category} is {@code null} or either key component is
     *                                  {@code null}
     * @throws IllegalArgumentException if either component is wider than its field or contains a
     *                                  character US-ASCII cannot represent
     */
    public static String typeAndCategoryKeyImage(final TransactionCategory category) {
        Objects.requireNonNull(category, "category must not be null for " + KEY_ARTEFACT);
        return FixedWidthFieldReader.builder(KEY_ARTEFACT, TYPE_AND_CATEGORY_KEY_WIDTH)
                .putAlphanumeric("TRAN-TYPE-CD", TRAN_TYPE_CD_OFFSET, TRAN_TYPE_CD_LENGTH,
                        requireValue(category.getTranTypeCd(), "TRAN-TYPE-CD"))
                .putNumeric("TRAN-CAT-CD", TRAN_CAT_CD_OFFSET, TRAN_CAT_CD_LENGTH,
                        requireValue(category.getTranCatCd(), "TRAN-CAT-CD"))
                .build()
                .image();
    }

    /**
     * Checks that a mapped value is present, naming the field if it is not.
     *
     * <p>An entity may legitimately be unpopulated - the persistence provider constructs one empty
     * before filling it - so a missing value is a caller defect rather than a record defect, and it
     * is reported with the legacy field name so the caller can see which of the three is absent. An
     * empty value is <em>not</em> rejected: an empty description is a legitimate all-spaces field and
     * an empty code is a legitimate all-zeros field, exactly as COBOL renders an unset field.
     *
     * @param value     the value to check
     * @param fieldName the legacy field name, for the diagnostic
     * @return the same value, once checked
     */
    private static String requireValue(final String value, final String fieldName) {
        return Objects.requireNonNull(value, fieldName + " must not be null in " + ARTEFACT);
    }
}
