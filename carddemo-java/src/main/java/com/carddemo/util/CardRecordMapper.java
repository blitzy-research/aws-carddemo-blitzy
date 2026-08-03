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

import com.carddemo.domain.Card;

/**
 * Sole holder of the 150-byte card record layout and of the exact two-way mapping between that
 * record image and the {@link Card} entity.
 *
 * <p>Every offset is a named constant and every field is positioned by explicit offset arithmetic.
 * That is a constraint rather than a preference: the module's reflection budget is zero, so no
 * annotation-driven or bean-mapping library may place a field here, and a reviewer must be able to
 * audit each offset against the copybook without reading a method body.
 *
 * <p><strong>Layout authority, corroborated three ways.</strong> The layout is declared by the group
 * item {@code CARD-RECORD} of {@code [app/cpy/CVACT02Y.cpy]}, whose header states a record length of
 * 150. Three independent artifacts of the read-only estate agree on it:
 *
 * <ul>
 *   <li>the copybook's six named fields plus its trailing filler run sum to exactly 150;</li>
 *   <li>the base cluster is defined with a 16-byte key at offset zero and a record fixed at 150 for
 *       both its minimum and its maximum {@code [app/jcl/CARDFILE.jcl]};</li>
 *   <li>the sequential reader's file section splits the same record into a 16-byte key field and a
 *       134-byte remainder, and 16 + 134 = 150 {@code [app/cbl/CBACT02C.cbl]}.</li>
 * </ul>
 *
 * <p><strong>The layout.</strong> Offsets are zero-based byte positions and lengths are encoded byte
 * counts. The widths sum as 16 + 11 + 3 + 50 + 10 + 1 + 59 = 150.
 *
 * <table>
 * <caption>The 150-byte card record, in declaration order</caption>
 * <tr><th scope="col">#</th><th scope="col">Legacy field</th><th scope="col">Picture</th>
 *     <th scope="col">Offset</th><th scope="col">Length</th>
 *     <th scope="col">Entity property</th></tr>
 * <tr><td>1</td><td>{@code CARD-NUM}</td><td>{@code X(16)}</td><td>0</td><td>16</td>
 *     <td>{@code cardNum}, the persistent identity</td></tr>
 * <tr><td>2</td><td>{@code CARD-ACCT-ID}</td><td>{@code 9(11)}</td><td>16</td><td>11</td>
 *     <td>{@code cardAcctId}, carried as text</td></tr>
 * <tr><td>3</td><td>{@code CARD-CVV-CD}</td><td>{@code 9(03)}</td><td>27</td><td>3</td>
 *     <td>{@code cardCvvCd}, carried as text</td></tr>
 * <tr><td>4</td><td>{@code CARD-EMBOSSED-NAME}</td><td>{@code X(50)}</td><td>30</td><td>50</td>
 *     <td>{@code cardEmbossedName}, never case folded</td></tr>
 * <tr><td>5</td><td>{@code CARD-EXPIRAION-DATE} (sic)</td><td>{@code X(10)}</td><td>80</td>
 *     <td>10</td><td>{@code cardExpirationDate}, spelled correctly</td></tr>
 * <tr><td>6</td><td>{@code CARD-ACTIVE-STATUS}</td><td>{@code X(01)}</td><td>90</td><td>1</td>
 *     <td>{@code cardActiveStatus}, a raw code</td></tr>
 * <tr><td>-</td><td>{@code FILLER}</td><td>{@code X(59)}</td><td>91</td><td>59</td>
 *     <td>not mapped</td></tr>
 * </table>
 *
 * <p><strong>The copybook genuinely misspells the fifth field.</strong> At physical line 9 of the
 * copybook the expiry date is declared without its {@code T} - {@code CARD-EXPIRAION-DATE} rather
 * than {@code CARD-EXPIRATION-DATE} {@code [app/cpy/CVACT02Y.cpy:L9]}. The defect is preserved where
 * it is load bearing and corrected where it is not: byte offset {@value #CARD_EXPIRAION_DATE_OFFSET}
 * and length {@value #CARD_EXPIRAION_DATE_LENGTH} are reproduced exactly, so the record image stays
 * byte compatible; the geometry constant and the diagnostic name here mirror the misspelling, so the
 * mapping stays findable by searching for the copybook's own spelling; and the entity property is
 * spelled correctly. The layout is never "fixed". Recorded as row 1 of the source anomaly register.
 * That the field really does begin at offset 80 is corroborated by the card-update program, which
 * addresses this same field by substring - positions 1 for 4, 6 for 2 and 9 for 2 - which is a
 * hyphenated ten-byte external form {@code [app/cbl/COCRDUPC.cbl:L1361]}.
 *
 * <p><strong>There are no zoned-decimal fields in this layout.</strong> Every field is either
 * {@code PIC X(n)} character data or an unsigned {@code PIC 9(n)} numeric field, so no amount, rate
 * or overpunched sign appears anywhere in the card record. This class therefore performs no decimal
 * conversion and does not call {@link ZonedDecimalCodec}: the codec remains the module's authority
 * for scale, truncation and zoned widths, and inventing a use for it here would be a fiction. There
 * is no arithmetic of any kind in this file, no {@code setScale} call, no rounding mode and no
 * binary floating-point type.
 *
 * <p><strong>Worked example, row 0 of the shipped fixture.</strong> The fixture
 * {@code [app/data/ASCII/carddata.txt]} is 7,550 bytes, which factors exactly as 50 records of 150
 * bytes each followed by a one-byte terminator: 50 x 151 = 7,550. The line feed is a record
 * separator and never record content, so a caller strips it before presenting a record here. Slicing
 * row 0 at the offsets above yields:
 *
 * <table>
 * <caption>Row 0 of the shipped card fixture, sliced at the offsets above</caption>
 * <tr><th scope="col">Legacy field</th><th scope="col">Slice</th></tr>
 * <tr><td>{@code CARD-NUM}</td><td>sixteen digits whose leading digit is zero; see the note
 *     below</td></tr>
 * <tr><td>{@code CARD-ACCT-ID}</td><td>{@code 00000000050}</td></tr>
 * <tr><td>{@code CARD-CVV-CD}</td><td>three digits; see the note below</td></tr>
 * <tr><td>{@code CARD-EMBOSSED-NAME}</td><td>{@code Aniya Von} followed by 41 spaces</td></tr>
 * <tr><td>{@code CARD-EXPIRAION-DATE}</td><td>{@code 2023-03-09}</td></tr>
 * <tr><td>{@code CARD-ACTIVE-STATUS}</td><td>{@code Y}</td></tr>
 * <tr><td>{@code FILLER}</td><td>59 spaces</td></tr>
 * </table>
 *
 * <p>Two of those seven slices are quoted by shape rather than by value, and deliberately so. The
 * card number and the verification code are the two sensitive fields of this record: the entity
 * redacts the card number from its own {@code toString()}, and the shared placement primitive's
 * documentation uses a synthetic stand-in rather than a value drawn from any data set, so this file
 * follows the same convention rather than introducing the module's first card-number literal into
 * shipped code. Their geometry is what matters here and it is stated exactly - sixteen bytes at
 * offset {@value #CARD_NUM_OFFSET}, three bytes at offset {@value #CARD_CVV_CD_OFFSET}, both with
 * significant leading zeros - and the fixture assertions that check the values themselves live in
 * test sources, where the fixture is read from disk.
 *
 * <p><strong>Trap one: the verification code is numeric by picture and text by type.</strong>
 * {@code CARD-CVV-CD} is {@code PIC 9(03)}, and a code of seven is the three characters {@code 007},
 * not the integer 7. It is never parsed to {@code int} and re-formatted, because the leading zeros
 * are significant, the column is three characters wide, and re-formatting would silently narrow a
 * three-byte field to one byte and shift every byte that follows it. Values with a leading zero do
 * occur in the shipped fixture, so this is a live case rather than a hypothetical one. The same rule
 * governs {@code CARD-ACCT-ID}: {@code 00000000050} is eleven characters and stays eleven
 * characters.
 *
 * <p><strong>Trap two: the embossed name is never upper-cased here.</strong> The legacy fold is
 * applied by the card-update program, not during record mapping: it converts the name in place
 * immediately before comparing it with the screen's previous image
 * {@code [app/cbl/COCRDUPC.cbl:L1357]} and again when checking whether the record changed
 * {@code [app/cbl/COCRDUPC.cbl:L1500]}. That is a service-layer concern, so this mapper copies the
 * value raw - untrimmed, unstripped, not case folded, not normalised - and {@code Aniya Von} plus
 * its 41 trailing spaces round-trips unchanged in case and in padding. Where a fold is genuinely
 * required it must go through {@link CobolStringUtils}, whose ASCII-only 26-character table
 * reproduces the legacy conversion exactly. {@code String.toUpperCase()} is forbidden: it is
 * locale sensitive and Unicode aware, so it would transform characters the strict table leaves
 * untouched and could change the encoded byte length of a field that must stay exactly 50 bytes
 * wide.
 *
 * <p><strong>The status code is raw, and an unfamiliar value is not an error here.</strong> All 50
 * fixture rows carry {@code Y}, and this class neither translates the code, nor consults the status
 * enumeration, nor rejects a value it does not recognise: that vocabulary belongs to the screen and
 * service layers. A record the legacy system accepted maps here without complaint, which is why the
 * only checks this class performs are geometric.
 *
 * <p><strong>The filler run has no canonical byte value.</strong> COBOL {@code FILLER X(59)} carries
 * no initialising clause, so its content is undefined by the copybook, and the sample data shows
 * exactly that divergence across the estate: the master files carry space filler while the
 * reference-table files carry ASCII zeros. Neither value is canonical and neither is silently
 * corrected. This mapper emits spaces, matching all 50 rows of the card fixture, and exposes that
 * choice as {@link #FILLER_CHARACTER}. The consequence for verification is stated rather than left
 * implicit: a fixture round-trip compares only the mapped prefix, byte range zero up to but
 * excluding {@value #MAPPED_PREFIX_LENGTH}, and not the whole 150 bytes, because the trailing bytes
 * are not this mapper's to guarantee. Recorded as row 20 of the source anomaly register.
 *
 * <p><strong>Identity is the legacy business key, never a surrogate.</strong> The cluster defines a
 * 16-byte key at offset zero {@code [app/jcl/CARDFILE.jcl]}, so the key is the leading substring of
 * the record image and the entity's {@code @Id} is that card number itself. A generated identifier
 * would sever the correspondence between record image and table row on which byte-level output
 * parity depends.
 *
 * <p><strong>The account identifier carries an alternate index, which is documentation here rather
 * than behaviour.</strong> The same job defines {@code CARDAIX} over {@code CARD-ACCT-ID} as
 * {@code KEYS(11 16) NONUNIQUEKEY UPGRADE} {@code [app/jcl/CARDFILE.jcl]}, which the migration
 * reproduces as {@code CardRepository.findByCardAcctId} plus a B-tree index created by
 * {@code V2__create_indexes.sql}. The offset in that declaration, 16, independently corroborates the
 * second field's position. This mapper implements no lookup, holds no repository and performs no
 * query; the note exists so the correspondence is recorded where the offsets are.
 *
 * <p><strong>The optimistic-locking counter is not part of the record image.</strong> The entity
 * carries a {@code @Version} counter for optimistic locking, and the 150-byte image has no
 * representation for it, so this mapper never reads it and never sets it: it is the persistence
 * provider's to own. The entity is populated through its public all-argument constructor, whose
 * parameter order is exactly the record-image order, because its no-argument constructor is
 * {@code protected} for the provider and is therefore not reachable from this package; the version
 * counter is deliberately absent from that signature.
 *
 * <p><strong>Failure contract.</strong> A record image whose encoded width is not exactly
 * {@value #RECORD_LENGTH} raises {@link IllegalArgumentException} naming the artefact, the expected
 * width and the actual encoded byte length, and names an unstripped line terminator as the likely
 * cause when the overshoot is exactly one byte. Input is never silently padded, never truncated,
 * never partially mapped and never returned as {@code null}; {@code null} arguments raise
 * deterministically through {@link Objects#requireNonNull(Object, String)}. No exception type from
 * this module's own package is used, and that is deliberate: none of the six models a caller
 * supplying the wrong number of bytes, which is a failure mode with no legacy antecedent at all,
 * because VSAM and QSAM records are fixed length by construction. The check is a Java-only defensive
 * guard against a caller defect.
 *
 * <p><strong>Deliberately absent.</strong> Business validation of any kind, since card-number
 * format, verification-code rules, expiry sanity and status vocabulary belong to the card services;
 * enumeration translation; case folding; arithmetic; paging, since the seven-row card list is a
 * service concern; persistence, since no repository, entity manager or transaction appears here; and
 * any logger, console stream, file, clock, environment lookup or source of randomness, whose absence
 * is what makes every method on this class a pure function. Emitting a diagnostic before abending is
 * a caller's obligation.
 *
 * <p><strong>Shape and thread safety.</strong> Final, private constructor, every member static, no
 * mutable static state, and the encoding builder is scoped to the single call that creates it, so
 * every method is safe to call concurrently.
 *
 * <p><strong>Provenance.</strong> Translated from the estate at commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}. The copybook's trailer records the upstream
 * release stamp {@code CardDemo_v1.0-15-g27d6c6f-68}, dated 2022-07-19. The legacy tree is read-only
 * reference: no copybook, program or job-stream text is reproduced here, so traceability is carried
 * by citation of member names, field names, pictures, widths, offsets and codes only.
 *
 * @see Card
 * @see FixedWidthFieldReader
 * @see CobolStringUtils
 */
public final class CardRecordMapper {

    /**
     * Encoded byte width of the whole record image, from the copybook's stated record length and
     * corroborated by the cluster definition's fixed minimum and maximum.
     */
    public static final int RECORD_LENGTH = 150;

    /**
     * Byte width of the primary key, which the cluster declares at offset zero and which the file
     * section declares as the record's leading field.
     */
    public static final int KEY_LENGTH = 16;

    /**
     * Byte width of the remainder after the key, as the file section declares it:
     * {@value #KEY_LENGTH} + {@value #DATA_LENGTH} = {@value #RECORD_LENGTH}.
     */
    public static final int DATA_LENGTH = 134;

    /**
     * Number of leading bytes this mapper is the authority for, that is everything before the filler
     * run. A fixture round-trip compares this prefix only, because the filler byte is not canonical.
     */
    public static final int MAPPED_PREFIX_LENGTH = 91;

    /** Zero-based offset of the card number, the leading field and the record's key. */
    public static final int CARD_NUM_OFFSET = 0;

    /** Byte width of the card number. Leading zeros are significant, so it is carried as text. */
    public static final int CARD_NUM_LENGTH = 16;

    /**
     * Zero-based offset of the account identifier. This offset is corroborated independently by the
     * alternate index, which is declared over eleven bytes at this same position.
     */
    public static final int CARD_ACCT_ID_OFFSET = 16;

    /**
     * Byte width of the account identifier. Right-justified, zero-filled numeric text that is never
     * parsed to a numeric type.
     */
    public static final int CARD_ACCT_ID_LENGTH = 11;

    /** Zero-based offset of the card verification code. */
    public static final int CARD_CVV_CD_OFFSET = 27;

    /**
     * Byte width of the card verification code. Three characters, always: a code of seven is
     * {@code 007}.
     */
    public static final int CARD_CVV_CD_LENGTH = 3;

    /** Zero-based offset of the embossed cardholder name. */
    public static final int CARD_EMBOSSED_NAME_OFFSET = 30;

    /**
     * Byte width of the embossed cardholder name. Mixed case with embedded spaces in live data, and
     * copied verbatim: the legacy upper-case fold belongs to the card-update service.
     */
    public static final int CARD_EMBOSSED_NAME_LENGTH = 50;

    /**
     * Zero-based offset of the expiry date, whose copybook name is genuinely misspelled at physical
     * line 9 of the copybook. The constant mirrors the misspelling so the mapping stays findable
     * from the legacy name; the entity property is spelled correctly.
     */
    public static final int CARD_EXPIRAION_DATE_OFFSET = 80;

    /** Byte width of the expiry date, a hyphenated ten-character external form. */
    public static final int CARD_EXPIRAION_DATE_LENGTH = 10;

    /** Zero-based offset of the active status code. */
    public static final int CARD_ACTIVE_STATUS_OFFSET = 90;

    /** Byte width of the active status code, carried as a raw single character. */
    public static final int CARD_ACTIVE_STATUS_LENGTH = 1;

    /** Zero-based offset of the trailing filler run, which this mapper does not map. */
    public static final int FILLER_OFFSET = 91;

    /**
     * Byte width of the trailing filler run:
     * {@value #MAPPED_PREFIX_LENGTH} + {@value #FILLER_LENGTH} = {@value #RECORD_LENGTH}.
     */
    public static final int FILLER_LENGTH = 59;

    /**
     * Byte this mapper writes across the filler run. The copybook supplies no initialising clause,
     * so no value is canonical; a space is what all 50 rows of the shipped fixture carry and what
     * the module uses for every master layout.
     */
    public static final char FILLER_CHARACTER = ' ';

    /** Layout name carried into every diagnostic; it names the copybook group and the copybook. */
    private static final String ARTEFACT = "CARD-RECORD (CVACT02Y)";

    /** Legacy field name used in diagnostics, so a message matches the copybook exactly. */
    private static final String FIELD_CARD_NUM = "CARD-NUM";

    /** Legacy field name used in diagnostics. */
    private static final String FIELD_CARD_ACCT_ID = "CARD-ACCT-ID";

    /** Legacy field name used in diagnostics. */
    private static final String FIELD_CARD_CVV_CD = "CARD-CVV-CD";

    /** Legacy field name used in diagnostics. */
    private static final String FIELD_CARD_EMBOSSED_NAME = "CARD-EMBOSSED-NAME";

    /** Reproduced with the copybook's own misspelling so a diagnostic matches the copybook. */
    private static final String FIELD_CARD_EXPIRAION_DATE = "CARD-EXPIRAION-DATE";

    /** Legacy field name used in diagnostics. */
    private static final String FIELD_CARD_ACTIVE_STATUS = "CARD-ACTIVE-STATUS";

    /** Not instantiable: a stateless mapper exposing only static members. */
    private CardRecordMapper() {
    }

    /**
     * Maps a complete card record image, supplied as a string, onto a fully populated entity.
     *
     * <p>The encoded width is checked before a single field is sliced, so a mis-sized record is
     * reported rather than mapped from the wrong offsets. Every field is copied verbatim -
     * untrimmed, unstripped, not case folded, not normalised and not validated - so the embossed
     * name keeps its trailing spaces and its original case, and both numeric identifiers keep their
     * leading zeros.
     *
     * @param  recordImage the whole record image, excluding any line terminator
     * @return a fully populated card, never partially mapped
     * @throws NullPointerException     if {@code recordImage} is {@code null}
     * @throws IllegalArgumentException if the encoded width is not exactly
     *                                  {@value #RECORD_LENGTH}, or if a character cannot be
     *                                  represented in US-ASCII
     */
    public static Card fromRecord(String recordImage) {
        Objects.requireNonNull(recordImage, "recordImage must not be null");
        requireRecordWidth(FixedWidthFieldReader.encodedLength(recordImage));
        return mapFrom(FixedWidthFieldReader.of(ARTEFACT, recordImage, RECORD_LENGTH));
    }

    /**
     * Maps a complete card record image, supplied as bytes, onto a fully populated entity.
     *
     * <p>Preferred when the caller already holds raw bytes, because it removes any need for the
     * caller to choose a charset: the bytes are verified to be 7-bit ASCII and every slice is
     * decoded as US-ASCII. The array is only read - neither retained nor modified.
     *
     * @param  recordImage the whole record image as bytes, excluding any line terminator
     * @return a fully populated card, never partially mapped
     * @throws NullPointerException     if {@code recordImage} is {@code null}
     * @throws IllegalArgumentException if the length is not exactly {@value #RECORD_LENGTH}, or if
     *                                  any byte is not 7-bit ASCII
     */
    public static Card fromRecord(byte[] recordImage) {
        Objects.requireNonNull(recordImage, "recordImage must not be null");
        requireRecordWidth(recordImage.length);
        return mapFrom(FixedWidthFieldReader.of(ARTEFACT, recordImage, RECORD_LENGTH));
    }

    /**
     * Maps one card record held inside a larger byte buffer onto a fully populated entity.
     *
     * <p>The seam for a caller holding a whole newline-terminated fixed-width file in one buffer.
     * Such a file has a stride of {@value #RECORD_LENGTH} + 1, so record <em>i</em> starts at
     * {@code i * (RECORD_LENGTH + 1)}, which selects the record and leaves its terminator behind;
     * the shipped fixture's 7,550 bytes are exactly 50 such strides. Stride arithmetic and file
     * access stay with the caller. No whole-buffer width check applies, because the buffer is not
     * the record: the requested range must simply lie wholly inside it.
     *
     * @param  buffer the buffer containing the record, and possibly many others
     * @param  from   zero-based index in {@code buffer} at which the record starts
     * @return a fully populated card, never partially mapped
     * @throws NullPointerException     if {@code buffer} is {@code null}
     * @throws IllegalArgumentException if {@code from} is negative, if the record range is not
     *                                  wholly inside {@code buffer}, or if any byte in that range
     *                                  is not 7-bit ASCII
     */
    public static Card fromRecord(byte[] buffer, int from) {
        Objects.requireNonNull(buffer, "buffer must not be null");
        return mapFrom(FixedWidthFieldReader.of(ARTEFACT, buffer, from, RECORD_LENGTH));
    }

    /**
     * Renders a card as its canonical {@value #RECORD_LENGTH}-byte record image, as a string
     * carrying no line terminator.
     *
     * <p>The exact inverse of {@link #fromRecord(String)} for the mapped fields, and the padding is
     * part of the contract: character fields are placed left-justified and space-padded, so a
     * nine-character embossed name acquires 41 trailing spaces and a value that already fills its
     * field is placed unchanged; the two numeric fields are placed right-justified and zero-padded,
     * so a verification code of {@code 7} emerges as {@code 007}; the filler run is emitted as
     * {@link #FILLER_CHARACTER}. Nothing is trimmed, folded or otherwise altered on the way out. The
     * width is guaranteed twice, by a builder that allocates exactly {@value #RECORD_LENGTH} bytes
     * and rejects any over-wide value, and by an explicit encoded-byte check before the image is
     * returned.
     *
     * @param  card the card to render; no mapped property may be {@code null}
     * @return the record image, exactly {@value #RECORD_LENGTH} encoded bytes wide
     * @throws NullPointerException     if {@code card} is {@code null}
     * @throws IllegalArgumentException if a mapped property is {@code null}, or if a value is wider
     *                                  than its field or cannot be represented in US-ASCII
     */
    public static String toRecord(Card card) {
        String image = imageOf(card).image();
        requireRecordWidth(FixedWidthFieldReader.encodedLength(image));
        return image;
    }

    /**
     * Renders a card as its canonical {@value #RECORD_LENGTH}-byte record image, as bytes.
     *
     * <p>Byte-for-byte identical to {@link #toRecord(Card)} and offered so a writer need not choose
     * a charset. The array is fresh and unshared, and carries no line terminator: record separation
     * belongs to the writer.
     *
     * @param  card the card to render; no mapped property may be {@code null}
     * @return a new array of exactly {@value #RECORD_LENGTH} US-ASCII bytes
     * @throws NullPointerException     if {@code card} is {@code null}
     * @throws IllegalArgumentException on exactly the same conditions as {@link #toRecord(Card)}
     */
    public static byte[] toRecordBytes(Card card) {
        byte[] image = imageOf(card).toByteArray();
        requireRecordWidth(image.length);
        return image;
    }

    /**
     * Reads the six mapped fields at their declared offsets and builds the entity.
     *
     * <p>Populated through the entity's public all-argument constructor, whose parameter order is
     * the record-image order, because the no-argument constructor is {@code protected} for the
     * persistence provider and is not reachable from this package. The optimistic-locking counter is
     * deliberately not touched: it has no representation in the image.
     *
     * <p>Each field is read through the named slicing overload, so a mis-declared offset identifies
     * itself by legacy field name rather than by position alone. No slice is taken any other way:
     * this class never calls {@code substring} and never indexes the image directly.
     *
     * @param  record the validated reader over one record image
     * @return a fully populated card
     */
    private static Card mapFrom(FixedWidthFieldReader record) {
        return new Card(
                record.field(FIELD_CARD_NUM, CARD_NUM_OFFSET, CARD_NUM_LENGTH),
                record.field(FIELD_CARD_ACCT_ID, CARD_ACCT_ID_OFFSET, CARD_ACCT_ID_LENGTH),
                record.field(FIELD_CARD_CVV_CD, CARD_CVV_CD_OFFSET, CARD_CVV_CD_LENGTH),
                record.field(FIELD_CARD_EMBOSSED_NAME, CARD_EMBOSSED_NAME_OFFSET,
                        CARD_EMBOSSED_NAME_LENGTH),
                record.field(FIELD_CARD_EXPIRAION_DATE, CARD_EXPIRAION_DATE_OFFSET,
                        CARD_EXPIRAION_DATE_LENGTH),
                record.field(FIELD_CARD_ACTIVE_STATUS, CARD_ACTIVE_STATUS_OFFSET,
                        CARD_ACTIVE_STATUS_LENGTH));
    }

    /**
     * Places the six mapped fields and the filler run, in declaration order, and completes the
     * image.
     *
     * <p>Written as separate placements against named offset constants rather than as one fluent
     * chain, so each field's offset and length are visible on the line that uses them and no offset
     * is derived by advancing a running cursor. The two {@code PIC 9} fields are placed
     * right-justified and zero-padded and the four {@code PIC X} fields left-justified and
     * space-padded, which is what makes each placement match its own picture clause rather than a
     * single house style applied to all six.
     *
     * @param  card the card to render
     * @return a reader over the completed image, which is also how the round trip is expressed
     */
    private static FixedWidthFieldReader imageOf(Card card) {
        Objects.requireNonNull(card, "card must not be null");
        FixedWidthFieldReader.Builder image = FixedWidthFieldReader.builder(ARTEFACT, RECORD_LENGTH);
        image.putAlphanumeric(FIELD_CARD_NUM, CARD_NUM_OFFSET, CARD_NUM_LENGTH,
                requireText(FIELD_CARD_NUM, "cardNum", card.getCardNum()));
        image.putNumeric(FIELD_CARD_ACCT_ID, CARD_ACCT_ID_OFFSET, CARD_ACCT_ID_LENGTH,
                requireText(FIELD_CARD_ACCT_ID, "cardAcctId", card.getCardAcctId()));
        image.putNumeric(FIELD_CARD_CVV_CD, CARD_CVV_CD_OFFSET, CARD_CVV_CD_LENGTH,
                requireText(FIELD_CARD_CVV_CD, "cardCvvCd", card.getCardCvvCd()));
        image.putAlphanumeric(FIELD_CARD_EMBOSSED_NAME, CARD_EMBOSSED_NAME_OFFSET,
                CARD_EMBOSSED_NAME_LENGTH, requireText(FIELD_CARD_EMBOSSED_NAME, "cardEmbossedName",
                        card.getCardEmbossedName()));
        image.putAlphanumeric(FIELD_CARD_EXPIRAION_DATE, CARD_EXPIRAION_DATE_OFFSET,
                CARD_EXPIRAION_DATE_LENGTH, requireText(FIELD_CARD_EXPIRAION_DATE,
                        "cardExpirationDate", card.getCardExpirationDate()));
        image.putAlphanumeric(FIELD_CARD_ACTIVE_STATUS, CARD_ACTIVE_STATUS_OFFSET,
                CARD_ACTIVE_STATUS_LENGTH, requireText(FIELD_CARD_ACTIVE_STATUS, "cardActiveStatus",
                        card.getCardActiveStatus()));
        image.putFiller(FILLER_OFFSET, FILLER_LENGTH, FILLER_CHARACTER);
        return image.build();
    }

    /**
     * Verifies that a character property is present, and returns it verbatim.
     *
     * <p>Nothing is trimmed, padded, case folded or defaulted, because the padding a value carries
     * is part of the record. A {@code null} property is a caller defect rather than a data
     * condition: a fixed-width record has no representation for an absent value, so an unset
     * character field is presented as spaces and an unset numeric field as zeros.
     *
     * @param  fieldName the legacy field name, used in the diagnostic
     * @param  property  the entity property name, used in the diagnostic
     * @param  value     the value to check
     * @return {@code value}, unchanged
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    private static String requireText(String fieldName, String property, String value) {
        if (value == null) {
            throw new IllegalArgumentException(ARTEFACT + " field '" + fieldName + "' (Card."
                    + property + ") must not be null: a fixed-width record has no representation"
                    + " for an absent value, so an unset field is presented as spaces or zeros"
                    + " rather than as null");
        }
        return value;
    }

    /**
     * Compares an encoded byte length with the width this layout declares.
     *
     * <p>Serves both directions, so the input precondition and the output postcondition report the
     * same message shape. The message names the artefact, the expected width and the actual encoded
     * byte length, which is the whole failure contract for a mis-sized record. When the overshoot is
     * exactly one byte it also names the overwhelmingly likely cause, since the shipped fixture is
     * newline terminated and its stride is one byte greater than its record width.
     *
     * @param actualEncodedBytes the encoded byte length actually observed
     * @throws IllegalArgumentException if {@code actualEncodedBytes} is not exactly
     *                                  {@value #RECORD_LENGTH}
     */
    private static void requireRecordWidth(int actualEncodedBytes) {
        if (actualEncodedBytes == RECORD_LENGTH) {
            return;
        }
        String terminatorHint = actualEncodedBytes == RECORD_LENGTH + 1
                ? " (an overshoot of exactly one byte is almost always an unstripped 0x0A line"
                        + " terminator: the fixture's stride is 151 bytes, of which only the leading"
                        + " 150 are the record)"
                : "";
        throw new IllegalArgumentException(ARTEFACT + " record image must be exactly "
                + RECORD_LENGTH + " encoded bytes in US-ASCII, but the supplied image is "
                + actualEncodedBytes + " encoded bytes; fixed-width records are never padded or"
                + " truncated to fit" + terminatorHint);
    }
}
