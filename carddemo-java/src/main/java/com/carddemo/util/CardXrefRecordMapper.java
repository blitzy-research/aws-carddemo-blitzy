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

import java.util.Objects;

import com.carddemo.domain.CardCrossReference;

/**
 * Sole holder of the card cross-reference record layout and of the exact two-way mapping between a
 * record image and the {@link CardCrossReference} entity.
 *
 * <p>Layout authority is {@code [app/cpy/CVACT03Y.cpy]}, whose group declares four items in this
 * order and nothing else. Three of them carry information and the fourth is an unnamed filler run
 * that completes the declared width:
 *
 * <table>
 * <caption>The card cross-reference record, in declaration order</caption>
 * <tr><th scope="col">#</th><th scope="col">Legacy field</th><th scope="col">Picture</th>
 *     <th scope="col">Offset</th><th scope="col">Length</th>
 *     <th scope="col">Entity property</th></tr>
 * <tr><td>1</td><td>{@code XREF-CARD-NUM}</td><td>{@code X(16)}</td><td>0</td><td>16</td>
 *     <td>{@code xrefCardNum}, the persistent identity</td></tr>
 * <tr><td>2</td><td>{@code XREF-CUST-ID}</td><td>{@code 9(09)}</td><td>16</td><td>9</td>
 *     <td>{@code xrefCustId}, carried as text</td></tr>
 * <tr><td>3</td><td>{@code XREF-ACCT-ID}</td><td>{@code 9(11)}</td><td>25</td><td>11</td>
 *     <td>{@code xrefAcctId}, carried as text</td></tr>
 * <tr><td>-</td><td>{@code FILLER}</td><td>{@code X(14)}</td><td>36</td><td>14</td>
 *     <td>not mapped, not persisted</td></tr>
 * </table>
 *
 * <p>The arithmetic that follows from that table is the whole subject of this class:
 * 16 + 9 + 11 = {@value #DATA_RECORD_LENGTH} bytes of data, plus a filler run of
 * {@value #FILLER_LENGTH} bytes, gives the {@value #RECORD_LENGTH} bytes the copybook's own header
 * declares. Both numbers are live, and the rest of this documentation exists so that no caller ever
 * has to guess which of the two it is holding.
 *
 * <p><strong>The critical fact: this record exists at two physical widths, and both are correct.
 * </strong> The same fifty logical rows are shipped twice, once with the filler and once without,
 * and both files are named validation artefacts:
 *
 * <table>
 * <caption>The two live widths of one logical record</caption>
 * <tr><th scope="col">Artefact</th><th scope="col">Total bytes</th><th scope="col">Stride</th>
 *     <th scope="col">Record width</th><th scope="col">Rows</th></tr>
 * <tr><td>{@code [app/data/ASCII/cardxref.txt]}</td><td>1,850</td>
 *     <td>37, being {@value #DATA_RECORD_LENGTH} plus one {@code 0x0A} terminator</td>
 *     <td>{@value #DATA_RECORD_LENGTH} - filler absent</td><td>50</td></tr>
 * <tr><td>{@code [app/data/EBCDIC/AWS.M2.CARDDEMO.CARDXREF.PS]}</td><td>2,500</td>
 *     <td>{@value #RECORD_LENGTH}, with no terminator at all</td>
 *     <td>{@value #RECORD_LENGTH} - filler present</td><td>50</td></tr>
 * </table>
 *
 * <p><strong>A reader that advances through the text fixture at a {@value #RECORD_LENGTH}-byte
 * stride is {@value #FILLER_LENGTH} bytes out of step from the second row onward and misparses every
 * row after the first.</strong> That defect does not raise anything: it yields a card number
 * assembled from the tail of one row and the head of the next, which is plausible enough to survive
 * review and wrong in every row it touches. Preventing it is why this class treats
 * {@value #DATA_RECORD_LENGTH} as a first-class width rather than as a degraded form of
 * {@value #RECORD_LENGTH}, why {@link #fromRecord(String)} and {@link #fromRecord(byte[])} accept
 * either width and reject every other, and why the buffer overload
 * {@link #fromRecord(byte[], int, int)} makes the caller state the width instead of defaulting to
 * one. Neither file is wrong and neither is repaired; the divergence is a layout fact.
 *
 * <p><strong>Fixture evidence, and the cross-file agreement it establishes.</strong> Read at a
 * {@value #DATA_RECORD_LENGTH}-byte stride, row 0 of {@code [app/data/ASCII/cardxref.txt]} yields a
 * 16-byte card number whose leading digit is zero, the customer identifier {@code 000000050} and the
 * account identifier {@code 00000000050}. That same row 0 agrees, byte for byte, with row 0 of
 * {@code [app/data/ASCII/carddata.txt]}: the card number here is identical to the one that file
 * carries at offset 0 for 16 bytes, and this account identifier {@code 00000000050} is identical to
 * the one it carries at offset 16 for 11 bytes. The two files describe the same card, which is what
 * makes the {@value #DATA_RECORD_LENGTH}-byte reading demonstrably the correct one rather than
 * merely the one that happens to divide evenly. The two fixed-length forms agree as well: every one
 * of the fifty rows of the {@value #RECORD_LENGTH}-byte dataset, translated out of its mainframe
 * code page, matches the corresponding text row across all {@value #DATA_RECORD_LENGTH} data bytes.
 *
 * <p><strong>The card number's digits are deliberately not transcribed above, and the omission is
 * the module's convention rather than an oversight.</strong> It is a primary account number, and no
 * card-number literal appears in this module's shipped sources: {@link CardCrossReference} withholds
 * it from its own {@code toString()}, {@link FixedWidthFieldReader} documents placement with a
 * synthetic stand-in, and the card record mapper states the same choice explicitly. What matters to
 * a reader of this file is geometry and identity, and both are stated exactly - 16 bytes at offset
 * {@value #XREF_CARD_NUM_OFFSET}, a significant leading zero, and byte-for-byte equality with the
 * card file's own key - while the assertions that check the digits themselves belong in test
 * sources, where the fixture is read from disk. Nothing about this convention touches behaviour:
 * every method below reads, carries and emits the card number completely untouched.
 *
 * <p><strong>The entity has exactly three attributes, and the filler is not one of them.</strong>
 * {@link CardCrossReference} maps three columns and no fourth, because the filler carries no
 * information and there is nothing for it to mean. This mapper is consequently the only place in the
 * module that knows the filler exists: {@link #toRecord(CardCrossReference)} writes it and
 * {@link #fromRecord(String)} steps over it, and no other layer is aware of it at all.
 *
 * <p><strong>This entity carries no optimistic-locking counter, and none is missing.</strong> The
 * account and card entities each carry a version attribute that has no representation in their
 * record images, so their mappers must consciously leave it alone. Here there is nothing to leave
 * alone: {@link CardCrossReference} declares no version attribute, so the three mapped fields are
 * the whole of the entity's mapped state and a reviewer should not look for a fourth.
 *
 * <p><strong>Every identifier is text, and none is ever parsed.</strong> The customer and account
 * identifiers are declared as digits, and both are carried as {@link String} from record image to
 * column and back. Their leading zeros are contractual: {@code 000000050} is nine characters and
 * {@code 00000000050} is eleven, and the external width the record publishes is fixed. Parsing
 * either to {@code int} or {@code long} and re-formatting it would narrow the value, shift every
 * byte that follows and break the byte-level correspondence between a record image and its row, so
 * no numeric or floating-point type appears anywhere in this class. There is no zoned-decimal field
 * in this layout either - every item is {@code PIC X(n)} or unsigned {@code PIC 9(n)} - so no
 * decimal codec participates and no scale or rounding decision arises here.
 *
 * <p><strong>Identity is the legacy business key, never a surrogate.</strong> The base cluster is
 * defined with a 16-byte key at offset zero, {@code KEYS(16 0)} with {@code RECORDSIZE(50 50)} on an
 * {@code INDEXED} cluster {@code [app/jcl/XREFFILE.jcl]}, so the key is the leading substring of the
 * stored image and the entity's identity is that card number itself. Identical low and high record
 * sizes in that same declaration are the third independent confirmation that the layout is fixed
 * length.
 *
 * <p><strong>The alternate index is documentation here, never behaviour.</strong> The same job
 * stream defines {@code CXACAIX} over {@code XREF-ACCT-ID} as {@code KEYS(11 25)},
 * {@code NONUNIQUEKEY} and {@code UPGRADE} {@code [app/jcl/XREFFILE.jcl]} - non-unique because many
 * cards resolve to one account, upgraded because the index is maintained in step with the base
 * cluster. The target reproduces that access path as a derived finder,
 * {@code CardCrossReferenceRepository.findByXrefAcctId}, backed by the B-tree index
 * {@code idx_card_cross_reference_xref_acct_id} created by {@code V2__create_indexes.sql}. The
 * offset in that declaration, {@value #XREF_ACCT_ID_OFFSET}, independently corroborates the third
 * field's position in the table above. This class holds no repository, issues no query and resolves
 * no key to any other; the note exists so the correspondence is recorded where the offsets are.
 *
 * <p><strong>Referential integrity belongs to the database, and this class assumes nothing about
 * it.</strong> All three columns of this table are constrained by {@code V2__create_indexes.sql} -
 * {@code fk_card_xref_card} on the card number, {@code fk_card_xref_account} on the account
 * identifier and {@code fk_card_xref_customer} on the customer identifier - which makes this the
 * most heavily constrained table in the schema and makes a referential check here look tempting.
 * None is performed. This mapper decodes and encodes bytes; whether the row it produces satisfies a
 * constraint is settled at insert time by the database, and whether the values it produces name
 * anything that exists is a question for the repository and service layers. The absence of a check
 * here is deliberate in both directions: nothing is rejected because a parent might be missing, and
 * nothing is assumed to exist because a constraint would normally require it. A mapper that probed
 * for a parent would need a repository, and a mapper with a repository is no longer a pure function.
 *
 * <p><strong>Failure contract.</strong> A record image whose encoded width is neither
 * {@value #DATA_RECORD_LENGTH} nor {@value #RECORD_LENGTH} raises {@link IllegalArgumentException}
 * naming the artefact, both accepted widths and the actual encoded byte length, and naming an
 * unstripped {@code 0x0A} terminator as the likely cause when the overshoot past either width is
 * exactly one byte. Input is never silently padded, never truncated, never partially mapped and
 * never returned as {@code null}; {@code null} arguments raise deterministically through
 * {@link Objects#requireNonNull(Object, String)}. No exception type from this module's own package
 * is used, and that is deliberate: none of the six models a caller supplying the wrong number of
 * bytes, which is a failure mode with no legacy antecedent at all, because VSAM and QSAM records are
 * fixed length by construction. The width check is a Java-only defensive guard against a caller
 * defect.
 *
 * <p><strong>Widths are always measured in encoded bytes, and no charset is ever left implicit.
 * </strong> Every check below counts US-ASCII bytes through
 * {@link FixedWidthFieldReader#encodedLength(String)} or through an array's own length, and never a
 * character count: the two measures coincide for well-formed input and diverge for exactly the input
 * a guard exists to catch, so a character count would let a malformed image through and then
 * misplace every field after it. This class performs no character-to-byte conversion of its own at
 * all - every such boundary, on both the decode and the encode path, is crossed inside
 * {@link FixedWidthFieldReader}, which names US-ASCII explicitly at each one. Delegating rather than
 * repeating that choice is what guarantees the platform default charset can never reach this
 * layout, and it is also why this file imports no charset type: an import it did not use would be a
 * claim it does not honour.
 *
 * <p><strong>Deliberately absent.</strong> Resolution and navigation of any kind, since turning a
 * card number into an account or a customer is precisely what the repository and service layers
 * exist for and this table's whole purpose is to make that hop cheap; validation of any kind, since
 * no card-number checksum, existence probe, digit rule or referential test appears here; arithmetic
 * of any kind; trimming, stripping, case folding, padding normalisation and every other silent
 * alteration of a caller's value; enumeration translation, since this layout has no coded field;
 * persistence, since no repository, entity manager or transaction appears here; and any logger,
 * console stream, file, clock, environment lookup or source of randomness, whose absence is what
 * makes every method on this class a pure function. This package is not among the module's pinned
 * logger names, so emitting a diagnostic before abending is a caller's obligation.
 *
 * <p><strong>Shape and thread safety.</strong> Final, private constructor, every member static, no
 * mutable static state, and each encoding builder is scoped to the single call that creates it, so
 * every method is safe to call concurrently.
 *
 * <p>Four translation decisions are raised by this file and belong in {@code docs/decision-log.md}:
 *
 * <ol>
 * <li>The record has two live widths. {@link #fromRecord(String)} accepts either
 *     {@value #DATA_RECORD_LENGTH} or {@value #RECORD_LENGTH};
 *     {@link #toRecord(CardCrossReference)} emits the canonical {@value #RECORD_LENGTH};
 *     {@link #toDataRecord(CardCrossReference)} emits the {@value #DATA_RECORD_LENGTH}-byte data
 *     projection under its own name so a fixture round trip is unambiguous. The text fixture's
 *     stride is {@value #DATA_RECORD_LENGTH} plus a terminator and the fixed-length dataset's is
 *     {@value #RECORD_LENGTH} with none; both are named validation artefacts.</li>
 * <li>The {@value #FILLER_LENGTH}-byte filler run is not persisted, so the entity has exactly three
 *     columns and this class is the only holder of the filler's existence.</li>
 * <li>Filler bytes are not uniform across the estate's fixtures, which is row 20 of the source
 *     anomaly register, and this layout is the family's fortunate case: its text fixture omits the
 *     filler altogether while every one of the fifty rows of its fixed-length dataset carries space
 *     filler, which is exactly what {@link #FILLER_CHARACTER} emits. Both round trips are therefore
 *     byte-for-byte exact over the whole image and neither needs a comparison bound - unlike the
 *     reference-table layouts, whose fixtures carry ASCII zero where their mappers write a
 *     space.</li>
 * <li>A malformed fixed-width image raises {@link IllegalArgumentException} rather than any module
 *     exception type, for the reason given under the failure contract above.</li>
 * </ol>
 *
 * <p><strong>Provenance.</strong> Translated from the estate at commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}. The copybook's trailer records the upstream
 * release stamp {@code CardDemo_v1.0-15-g27d6c6f-68}, dated 2022-07-19. Ten programs include this
 * copybook - the third-highest inclusion count of any data copybook in the estate, and a
 * consequence of this table being the resolution point for every card-to-account and
 * card-to-customer hop - with two further inclusions commented out at source and therefore not
 * counted. The legacy tree is read-only reference: no copybook, program or job-stream text is
 * reproduced here, so traceability is carried by citation of member names, field names, pictures,
 * widths, offsets and counts only.
 *
 * @see CardCrossReference
 * @see FixedWidthFieldReader
 */
public final class CardXrefRecordMapper {

    /** Layout name carried into every diagnostic; it names both the record group and the copybook. */
    public static final String ARTEFACT = "CARD-XREF-RECORD (CVACT03Y)";

    /**
     * Layout name used by the data projection, so a diagnostic says which of the two widths was in
     * force rather than leaving a reader to infer it from a byte count.
     */
    public static final String DATA_ARTEFACT = "CARD-XREF-RECORD data projection (CVACT03Y)";

    private static final String FIELD_CARD_NUM = "XREF-CARD-NUM";

    private static final String FIELD_CUST_ID = "XREF-CUST-ID";

    private static final String FIELD_ACCT_ID = "XREF-ACCT-ID";

    /** Phrase naming the offending value when a whole image was measured. */
    private static final String SUBJECT_MEASURED_IMAGE = "the image supplied measures";

    /** Phrase naming the offending value when a caller declared a width for a buffer range. */
    private static final String SUBJECT_REQUESTED_WIDTH = "the width requested is";

    public static final int XREF_CARD_NUM_OFFSET = 0;

    /**
     * Width of the card number, which is also the cluster key width declared as
     * {@code KEYS(16 0)} {@code [app/jcl/XREFFILE.jcl]}.
     */
    public static final int XREF_CARD_NUM_LENGTH = 16;

    /**
     * Key width of the base cluster, derived from the field it covers rather than written again as a
     * literal, because the key <em>is</em> the leading field and the two can never legitimately
     * differ.
     */
    public static final int KEY_LENGTH = XREF_CARD_NUM_LENGTH;

    public static final int XREF_CUST_ID_OFFSET = XREF_CARD_NUM_OFFSET + XREF_CARD_NUM_LENGTH;

    /** Width of the customer identifier, every leading zero of which is significant. */
    public static final int XREF_CUST_ID_LENGTH = 9;

    /**
     * Offset of the account identifier: <strong>{@code 25}</strong>, which is also the offset the
     * {@code CXACAIX} alternate index keys on, {@code KEYS(11 25)}
     * {@code [app/jcl/XREFFILE.jcl]}. Derived from the two fields that precede it so that the
     * declaration and the index agree by construction.
     */
    public static final int XREF_ACCT_ID_OFFSET = XREF_CUST_ID_OFFSET + XREF_CUST_ID_LENGTH;

    /** Width of the account identifier, every leading zero of which is significant. */
    public static final int XREF_ACCT_ID_LENGTH = 11;

    /**
     * The data-only record width, {@code 16 + 9 + 11}: the width at which
     * {@code [app/data/ASCII/cardxref.txt]} is written, with the filler absent entirely. A
     * first-class accepted width, not a fallback.
     */
    public static final int DATA_RECORD_LENGTH =
            XREF_CARD_NUM_LENGTH + XREF_CUST_ID_LENGTH + XREF_ACCT_ID_LENGTH;

    /** Offset at which the unmapped filler run begins, which is the end of the data prefix. */
    public static final int FILLER_OFFSET = DATA_RECORD_LENGTH;

    /** Width of the unmapped filler run that completes the canonical record. */
    public static final int FILLER_LENGTH = 14;

    /**
     * The canonical record width the copybook declares: the data prefix plus the filler run, and the
     * width at which {@code [app/data/EBCDIC/AWS.M2.CARDDEMO.CARDXREF.PS]} and the VSAM cluster's
     * {@code RECORDSIZE(50 50)} both hold the record.
     */
    public static final int RECORD_LENGTH = DATA_RECORD_LENGTH + FILLER_LENGTH;

    /**
     * Byte written across the filler run. Filler with no initialising clause is uninitialised, so
     * the copybook makes no value canonical; space is the module-wide default and, uniquely in this
     * family, it is also what every row of this layout's fixed-length dataset actually carries, so
     * emitting it reproduces that dataset exactly rather than merely plausibly.
     */
    public static final char FILLER_CHARACTER = ' ';

    /** Not instantiable: a stateless mapper exposing only static members. */
    private CardXrefRecordMapper() {
    }

    /**
     * Decodes a card cross-reference record image supplied as a string, at either accepted width.
     *
     * <p>The image must measure exactly {@value #DATA_RECORD_LENGTH} encoded bytes, in which case the
     * filler is absent, or exactly {@value #RECORD_LENGTH} encoded bytes, in which case the bytes
     * from {@value #FILLER_OFFSET} up to {@value #RECORD_LENGTH} are filler and are stepped over. The
     * three data fields are sliced from offsets {@value #XREF_CARD_NUM_OFFSET},
     * {@value #XREF_CUST_ID_OFFSET} and {@value #XREF_ACCT_ID_OFFSET} in both cases, which is the
     * point: the width decides only whether a filler run follows, never where a field begins. Any
     * {@code 0x0A} terminator must already have been removed, because a terminator is a record
     * separator and never record content.
     *
     * <p>All three values are copied verbatim - untrimmed, unstripped, not case folded and not
     * normalised - so the significant leading zeros of both identifiers survive intact and the card
     * number is returned exactly as the record holds it.
     *
     * @param  recordImage the record image at either accepted width, excluding any line terminator;
     *                     must not be {@code null}
     * @return a fully populated entity, never {@code null}
     * @throws NullPointerException     if {@code recordImage} is {@code null}
     * @throws IllegalArgumentException if the image measures neither {@value #DATA_RECORD_LENGTH} nor
     *                                  {@value #RECORD_LENGTH} encoded bytes, or if it contains a
     *                                  character US-ASCII cannot represent
     */
    public static CardCrossReference fromRecord(final String recordImage) {
        Objects.requireNonNull(recordImage, "recordImage must not be null");
        // Measured in encoded bytes rather than characters, so a non-ASCII image is rejected by the
        // measurement itself instead of passing a character count that happens to look right.
        final int width = requireAcceptedWidth(FixedWidthFieldReader.encodedLength(recordImage),
                SUBJECT_MEASURED_IMAGE);
        return mapFrom(FixedWidthFieldReader.of(artefactFor(width), recordImage, width));
    }

    /**
     * Decodes a card cross-reference record image supplied as bytes, at either accepted width.
     *
     * <p>Preferred over {@link #fromRecord(String)} when the caller already holds raw bytes, because
     * it removes any need for the caller to choose a charset. The width is taken from the array's own
     * length and behaviour is otherwise identical, including the two accepted widths and the verbatim
     * copying of every value.
     *
     * @param  recordImage the record image at either accepted width, excluding any line terminator;
     *                     must not be {@code null}
     * @return a fully populated entity, never {@code null}
     * @throws NullPointerException     if {@code recordImage} is {@code null}
     * @throws IllegalArgumentException if the array's length is neither {@value #DATA_RECORD_LENGTH}
     *                                  nor {@value #RECORD_LENGTH}, or if any byte is not 7-bit ASCII
     */
    public static CardCrossReference fromRecord(final byte[] recordImage) {
        Objects.requireNonNull(recordImage, "recordImage must not be null");
        final int width = requireAcceptedWidth(recordImage.length, SUBJECT_MEASURED_IMAGE);
        return mapFrom(FixedWidthFieldReader.of(artefactFor(width), recordImage, width));
    }

    /**
     * Decodes one card cross-reference record held inside a larger byte buffer, at the width the
     * caller states.
     *
     * <p>This is the seam for a batch reader that holds a whole file in one buffer, and it is the
     * one entrypoint where the width cannot be inferred, because a range inside a buffer carries no
     * length of its own. Stride arithmetic stays with the caller and differs by artefact, which is
     * exactly why the width is a parameter here:
     *
     * <ul>
     * <li>For the newline-terminated text form the stride is {@code recordWidth + 1}, so record
     *     <em>i</em> starts at {@code i * (DATA_RECORD_LENGTH + 1)} and the terminator is left behind
     *     rather than stripped. Over {@code [app/data/ASCII/cardxref.txt]} that is a stride of 37
     *     across 1,850 bytes, which is 50 rows exactly.</li>
     * <li>For the unterminated fixed-length form the stride <em>is</em> the record width, so record
     *     <em>i</em> starts at {@code i * RECORD_LENGTH}. Over the
     *     {@value #RECORD_LENGTH}-byte dataset that is 2,500 bytes, which is the same 50 rows.</li>
     * </ul>
     *
     * <p><strong>A two-argument overload that defaulted the width is deliberately absent.</strong>
     * Defaulting to {@value #RECORD_LENGTH} would read the text fixture {@value #FILLER_LENGTH} bytes
     * out of step from its second row onward, and defaulting to {@value #DATA_RECORD_LENGTH} would
     * read the fixed-length dataset out of step by the same amount in the other direction. Both
     * failures are silent. Requiring the width makes the caller's stride and the caller's width one
     * decision taken in one place, which is the only arrangement in which they cannot disagree.
     *
     * <p>Because this overload selects a range rather than measuring a whole image, a request that
     * runs off the end of the buffer is reported as a range failure against the buffer rather than as
     * a width mismatch.
     *
     * @param  buffer      buffer containing the record, and possibly many others; must not be
     *                     {@code null}
     * @param  from        zero-based index in {@code buffer} at which the record starts
     * @param  recordWidth the width in force, which must be either {@value #DATA_RECORD_LENGTH} or
     *                     {@value #RECORD_LENGTH}
     * @return a fully populated entity, never {@code null}
     * @throws NullPointerException     if {@code buffer} is {@code null}
     * @throws IllegalArgumentException if {@code recordWidth} is neither
     *                                  {@value #DATA_RECORD_LENGTH} nor {@value #RECORD_LENGTH}, if
     *                                  {@code from} is negative, if the range
     *                                  {@code [from, from + recordWidth)} is not wholly inside
     *                                  {@code buffer}, or if any byte in that range is not 7-bit
     *                                  ASCII
     */
    public static CardCrossReference fromRecord(final byte[] buffer, final int from,
            final int recordWidth) {
        Objects.requireNonNull(buffer, "buffer must not be null");
        final int width = requireAcceptedWidth(recordWidth, SUBJECT_REQUESTED_WIDTH);
        return mapFrom(FixedWidthFieldReader.of(artefactFor(width), buffer, from, width));
    }

    /**
     * Encodes an entity into the canonical {@value #RECORD_LENGTH}-byte record image, filler
     * included.
     *
     * <p>Padding is part of the contract. The card number is placed left-justified and space-padded,
     * matching {@code PIC X(16)}, so a value already at its full 16 bytes passes through byte for
     * byte and nothing is ever trimmed. Both identifiers are placed right-justified and zero-filled,
     * matching {@code PIC 9(n)}, so their significant leading zeros survive and a value handed over
     * short is widened by leading zeros exactly as a legacy move would widen it. Neither identifier
     * is parsed to a numeric type at any point.
     *
     * <p>The trailing {@value #FILLER_LENGTH} bytes are written as {@link #FILLER_CHARACTER}. Unlike
     * the reference-table layouts, this one needs no comparison bound: every row of
     * {@code [app/data/EBCDIC/AWS.M2.CARDDEMO.CARDXREF.PS]} carries space filler, so a whole-image
     * comparison against that dataset succeeds across all {@value #RECORD_LENGTH} bytes. A round trip
     * against the {@value #DATA_RECORD_LENGTH}-byte text fixture should use
     * {@link #toDataRecord(CardCrossReference)} instead, which reproduces that artefact's width
     * exactly and therefore needs no bound either.
     *
     * @param  crossReference the entity to encode; every mapped property must be populated
     * @return the record image, exactly {@value #RECORD_LENGTH} US-ASCII bytes wide
     * @throws NullPointerException     if {@code crossReference} or any mapped property is
     *                                  {@code null}
     * @throws IllegalArgumentException if any value is wider than its field or contains a character
     *                                  US-ASCII cannot represent
     */
    public static String toRecord(final CardCrossReference crossReference) {
        return buildImage(crossReference, ARTEFACT, RECORD_LENGTH).image();
    }

    /**
     * Encodes an entity into the canonical {@value #RECORD_LENGTH}-byte record image, returning the
     * bytes themselves so that the emitted width is guaranteed by construction rather than asserted
     * after the fact.
     *
     * <p>Behaves exactly as {@link #toRecord(CardCrossReference)}, including the filler byte and the
     * round-trip guidance given there.
     *
     * @param  crossReference the entity to encode; must not be {@code null}, and every mapped
     *                        property must be populated
     * @return a fresh array of exactly {@value #RECORD_LENGTH} US-ASCII bytes, never shared
     * @throws NullPointerException     if {@code crossReference} or any mapped property is
     *                                  {@code null}
     * @throws IllegalArgumentException if any value is wider than its field or contains a character
     *                                  US-ASCII cannot represent
     */
    public static byte[] toRecordBytes(final CardCrossReference crossReference) {
        return buildImage(crossReference, ARTEFACT, RECORD_LENGTH).toByteArray();
    }

    /**
     * Encodes an entity into the {@value #DATA_RECORD_LENGTH}-byte data projection, with no filler at
     * all.
     *
     * <p>This is the width at which {@code [app/data/ASCII/cardxref.txt]} is written, and the method
     * exists under its own name so that a round trip against that artefact is unambiguous: read a row
     * at a {@value #DATA_RECORD_LENGTH}-byte stride, map it, encode it here, and the result is
     * byte-for-byte identical to the row that was read, with no comparison bound and no filler to
     * reason about. Asserting the same thing through {@link #toRecord(CardCrossReference)} would
     * require the caller to slice {@value #FILLER_LENGTH} bytes back off the result and would leave a
     * reader unsure whether the bound was a genuine layout fact or a concession to a mismatch.
     *
     * <p>Field placement is identical to {@link #toRecord(CardCrossReference)} - the same three
     * offsets, the same justification and the same padding - because both projections share one
     * placement routine. This is a first-class emission width, not a truncation of the canonical one.
     *
     * @param  crossReference the entity to encode; every mapped property must be populated
     * @return the data projection, exactly {@value #DATA_RECORD_LENGTH} US-ASCII bytes wide
     * @throws NullPointerException     if {@code crossReference} or any mapped property is
     *                                  {@code null}
     * @throws IllegalArgumentException if any value is wider than its field or contains a character
     *                                  US-ASCII cannot represent
     */
    public static String toDataRecord(final CardCrossReference crossReference) {
        return buildImage(crossReference, DATA_ARTEFACT, DATA_RECORD_LENGTH).image();
    }

    /**
     * Encodes an entity into the {@value #DATA_RECORD_LENGTH}-byte data projection, returning the
     * bytes themselves so that the emitted width is guaranteed by construction rather than asserted
     * after the fact.
     *
     * <p>Behaves exactly as {@link #toDataRecord(CardCrossReference)}.
     *
     * @param  crossReference the entity to encode; must not be {@code null}, and every mapped
     *                        property must be populated
     * @return a fresh array of exactly {@value #DATA_RECORD_LENGTH} US-ASCII bytes, never shared
     * @throws NullPointerException     if {@code crossReference} or any mapped property is
     *                                  {@code null}
     * @throws IllegalArgumentException if any value is wider than its field or contains a character
     *                                  US-ASCII cannot represent
     */
    public static byte[] toDataRecordBytes(final CardCrossReference crossReference) {
        return buildImage(crossReference, DATA_ARTEFACT, DATA_RECORD_LENGTH).toByteArray();
    }

    /**
     * Slices the three mapped fields out of a validated record and assembles the entity.
     *
     * <p>Every slice is taken through {@link FixedWidthFieldReader}, which returns raw, untrimmed
     * values, so no trimming, padding, case folding or normalisation happens anywhere on this path.
     * The offsets are the same whichever accepted width the reader was built at, because the widths
     * differ only in whether a filler run follows the data; nothing here reads past
     * {@value #FILLER_OFFSET}, which is what makes the filler invisible to the rest of the module.
     *
     * <p>The entity is built with its public three-argument constructor rather than a no-argument
     * constructor and setters, because its no-argument constructor is {@code protected} and reserved
     * for the persistence provider and so is not reachable from this package. That constructor
     * assigns every argument verbatim, which is the guarantee this mapper needs, and its parameter
     * order is exactly the record-image order, which is what makes the call below checkable against
     * the layout table.
     *
     * @param  record the validated record image, at either accepted width
     * @return a fully populated entity, never {@code null}
     */
    private static CardCrossReference mapFrom(final FixedWidthFieldReader record) {
        final String cardNum =
                record.field(FIELD_CARD_NUM, XREF_CARD_NUM_OFFSET, XREF_CARD_NUM_LENGTH);
        final String custId = record.field(FIELD_CUST_ID, XREF_CUST_ID_OFFSET, XREF_CUST_ID_LENGTH);
        final String acctId = record.field(FIELD_ACCT_ID, XREF_ACCT_ID_OFFSET, XREF_ACCT_ID_LENGTH);
        return new CardCrossReference(cardNum, custId, acctId);
    }

    /**
     * Places the three mapped fields, and the filler run when the canonical width is in force, into a
     * fresh record buffer.
     *
     * <p>Shared by all four encode entrypoints so that the placement rules exist exactly once and the
     * two emission widths cannot drift apart. Each property is null-checked before placement, so a
     * partially populated entity names the offending legacy field instead of failing obscurely inside
     * the builder.
     *
     * @param  crossReference the entity to encode
     * @param  artefact       layout name to carry into diagnostics, naming which width is in force
     * @param  recordWidth    the width to emit, either {@value #DATA_RECORD_LENGTH} or
     *                        {@value #RECORD_LENGTH}
     * @return a reader over the completed image, of exactly {@code recordWidth} bytes
     * @throws NullPointerException     if {@code crossReference} or any mapped property of it is
     *                                  {@code null}
     * @throws IllegalArgumentException if any value does not fit its field
     */
    private static FixedWidthFieldReader buildImage(final CardCrossReference crossReference,
            final String artefact, final int recordWidth) {
        Objects.requireNonNull(crossReference, "crossReference must not be null");
        final String cardNum = requireField(crossReference.getXrefCardNum(), FIELD_CARD_NUM);
        final String custId = requireField(crossReference.getXrefCustId(), FIELD_CUST_ID);
        final String acctId = requireField(crossReference.getXrefAcctId(), FIELD_ACCT_ID);
        final FixedWidthFieldReader.Builder builder =
                FixedWidthFieldReader.builder(artefact, recordWidth)
                        // Alphanumeric: left-justified and space-padded, matching PIC X(16). A value
                        // already at full width passes through byte for byte.
                        .putAlphanumeric(FIELD_CARD_NUM, XREF_CARD_NUM_OFFSET,
                                XREF_CARD_NUM_LENGTH, cardNum)
                        // Numeric: right-justified and zero-filled, matching PIC 9(n), which is what
                        // preserves the significant leading zeros of both identifiers. Neither is
                        // parsed to a numeric type at any point.
                        .putNumeric(FIELD_CUST_ID, XREF_CUST_ID_OFFSET, XREF_CUST_ID_LENGTH, custId)
                        .putNumeric(FIELD_ACCT_ID, XREF_ACCT_ID_OFFSET, XREF_ACCT_ID_LENGTH, acctId);
        if (recordWidth == RECORD_LENGTH) {
            // Stated explicitly rather than inherited from the buffer's space initialisation, so the
            // filler byte is visible at the one place that writes it. The data projection has no
            // filler run to write, and declaring one would run past the end of its buffer.
            builder.putFiller(FILLER_OFFSET, FILLER_LENGTH, FILLER_CHARACTER);
        }
        return builder.build();
    }

    /**
     * Returns the layout name matching the width in force, so a diagnostic names the projection the
     * caller was actually working with.
     *
     * @param  width an already-accepted width
     * @return {@link #DATA_ARTEFACT} for the data projection, otherwise {@link #ARTEFACT}
     */
    private static String artefactFor(final int width) {
        return width == DATA_RECORD_LENGTH ? DATA_ARTEFACT : ARTEFACT;
    }

    /**
     * Verifies that a width is one of this layout's two accepted widths, and returns it.
     *
     * <p>The message names the artefact, both accepted widths with what each means, and the actual
     * value, because a caller that reached this point holds a number it believes to be a record
     * width and needs to be told which two numbers were acceptable rather than merely that its own
     * was not. An overshoot of exactly one byte past either width names an unstripped {@code 0x0A}
     * terminator as the likely cause, which is by far the most common way this check fires: the text
     * fixture's stride is one greater than its record width, so a reader that keeps the terminator
     * arrives here with 37 bytes in hand.
     *
     * @param  width   the width to check, in encoded bytes
     * @param  subject phrase naming what the width came from, so the message reads correctly for both
     *                 a measured image and a caller-declared width
     * @return {@code width}, unchanged, once it is known to be accepted
     * @throws IllegalArgumentException if {@code width} is neither {@value #DATA_RECORD_LENGTH} nor
     *                                  {@value #RECORD_LENGTH}
     */
    private static int requireAcceptedWidth(final int width, final String subject) {
        if (width == DATA_RECORD_LENGTH || width == RECORD_LENGTH) {
            return width;
        }
        final StringBuilder message = new StringBuilder();
        message.append(ARTEFACT)
                .append(" must be exactly ")
                .append(DATA_RECORD_LENGTH)
                .append(" encoded bytes in US-ASCII, the data-only width with the filler absent, or")
                .append(" exactly ")
                .append(RECORD_LENGTH)
                .append(" encoded bytes, the canonical width with the ")
                .append(FILLER_LENGTH)
                .append("-byte filler present, but ")
                .append(subject)
                .append(' ')
                .append(width)
                .append(" encoded bytes; fixed-width records are never padded or truncated to fit");
        if (width == DATA_RECORD_LENGTH + 1 || width == RECORD_LENGTH + 1) {
            message.append(" (an overshoot of exactly one byte is usually an unstripped 0x0A line ")
                    .append("terminator, which is a record separator and never record content)");
        }
        throw new IllegalArgumentException(message.toString());
    }

    /**
     * Returns a mapped field, having verified that it is present.
     *
     * @param  value     the property value as the entity holds it
     * @param  fieldName legacy field name used in the diagnostic
     * @return {@code value}, unchanged and in particular untrimmed
     * @throws NullPointerException if {@code value} is {@code null}
     */
    private static String requireField(final String value, final String fieldName) {
        return Objects.requireNonNull(value, () -> nullFieldMessage(fieldName));
    }

    /**
     * Builds the diagnostic for an absent mapped field, naming the artefact and the field.
     *
     * @param  fieldName legacy field name that was absent
     * @return the diagnostic message
     */
    private static String nullFieldMessage(final String fieldName) {
        return ARTEFACT + " cannot be encoded because " + fieldName
                + " is null; every mapped field of a fixed-width record must be present";
    }
}
