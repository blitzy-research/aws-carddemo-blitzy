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

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Offset-slicing primitive for the fixed-width record images that back every CardDemo data set,
 * and the exact inverse operation that reassembles such an image byte for byte.
 *
 * <p><strong>Legacy antecedent.</strong> The legacy estate declares each record as a fixed-length
 * image and splits it positionally rather than by delimiter. The account file's {@code FD} record is
 * a leading 11-digit identifier plus a 289-byte remainder, and 11 + 289 = 300 is the account record
 * width {@code [app/cbl/CBACT01C.cbl:L42-L43]}; the transaction file splits three ways into a
 * 304-byte prefix, a 26-byte processing timestamp and a 20-byte filler, and 304 + 26 + 20 = 350
 * {@code [app/cbl/CBTRN03C.cbl:L83-L86]}. Those two splits are the whole justification for this
 * class: the legacy programs address record content by byte position, so the migrated code must be
 * able to do the same, in exactly one place. Both citations use the locator form, which differs from
 * the physical line by a small offset for the reason recorded as decision D-42.
 *
 * <p><strong>Base-record keys are leading substrings, so identifiers are never surrogates.</strong>
 * Every {@code DEFINE CLUSTER} in the estate declares its key as {@code KEYS(length 0)} - key length
 * at offset zero - so a <em>base</em> record's primary key is always the leading substring of the
 * record image, exactly as the account {@code FD} split shows. That statement is about base clusters
 * and {@code FD} splits only, and not about keyed access in general: the three
 * {@code DEFINE ALTERNATEINDEX} definitions key at non-zero offsets - the card file's account
 * identifier at offset 16, the cross-reference file's account identifier at offset 25 and the
 * transaction file's processing timestamp at offset 304 - and each of those is an ordinary interior
 * field reached through {@link #field(int, int)}, never through {@link #key(int)}. The consequence
 * for the persistence layer is that the JPA {@code @Id} is always the natural business key and
 * <strong>never</strong> a generated surrogate: a surrogate would break the correspondence between
 * the record image and the table row on which byte-level output parity depends. {@link #key(int)}
 * and {@link #data(int)} express the two-part base split, and both are defined in terms of the same
 * {@link #field(int, int)} primitive so there is only one implementation of the arithmetic.
 *
 * <p><strong>Sole locus of byte offsets.</strong> This class and {@code ZonedDecimalCodec} are the
 * only two places in the module where a byte offset or a {@code PIC}-derived width may be
 * <em>interpreted</em>. The eleven record mappers declare their own offsets as constants - each
 * mapper owns its own layout - and hand them here; no {@code substring} call, byte offset,
 * {@code PIC}-derived width, overpunch decode or fixed-width template may appear anywhere in
 * {@code service}, {@code api}, {@code repository}, {@code batch} or {@code config}. The layouts
 * served are the eleven verified record widths: account 300, card 150, card cross-reference 50 (36
 * data bytes plus 14 filler), customer 500, transaction 350, daily transaction 350, transaction
 * category balance 50, disclosure group 50, transaction type 60, transaction category 60 and user
 * security 80. None of those widths is hard-coded here, because this class is the generic primitive
 * and each mapper is the authority for its own layout.
 *
 * <p><strong>Widths are measured in encoded bytes, never in characters.</strong> Every width check,
 * range check and padding computation uses encoded bytes from {@link StandardCharsets#US_ASCII}; a
 * character count is never a width authority. The record data is 7-bit ASCII, so one character is
 * one byte - but that is <em>enforced</em>, not assumed, because
 * {@link String#getBytes(java.nio.charset.Charset)} silently substitutes {@code '?'} for an
 * unrepresentable character. Non-representable input is therefore rejected rather than transcoded,
 * so a stray character can never shift a record's byte geometry by pretending to be a question mark.
 * The charset is named at every conversion; no platform-default conversion exists in this file.
 *
 * <p><strong>The line feed is a terminator, never record content.</strong> The nine ASCII sample
 * files are newline-terminated fixed-width files whose stride is {@code recordWidth + 1}, the extra
 * byte being {@code 0x0A}, and every byte count factors exactly on that stride: {@code acctdata.txt}
 * 15,050 = 50 x 301; {@code carddata.txt} 7,550 = 50 x 151; {@code cardxref.txt} 1,850 = 50 x 37;
 * {@code custdata.txt} 25,050 = 50 x 501; {@code dailytran.txt} 105,300 = 300 x 351;
 * {@code discgrp.txt} 2,601 = 51 x 51; {@code tcatbal.txt} 2,550 = 50 x 51; {@code trancatg.txt}
 * 1,098 = 18 x 61; {@code trantype.txt} 427 = 7 x 61. The terminator is never part of the record, so
 * a caller must exclude it, and a caller that forgets receives the intended diagnostic rather than
 * corrupt data: presenting 301 bytes for a 300-byte record raises {@link IllegalArgumentException}
 * naming both widths, and the message points at the unstripped terminator whenever the overshoot is
 * exactly one byte. {@link #of(String, byte[], int, int)} exists so that a caller holding a whole
 * file in one buffer can address record <em>i</em> without this class knowing anything about strides
 * or files.
 *
 * <p><strong>Values are never trimmed.</strong> Trailing and interior spaces are contractual in this
 * estate, so no slice returned here is trimmed, stripped, case-folded or normalised. Two verified
 * proofs make the rule load-bearing. Disclosure-group keys are exactly ten characters wide and
 * include {@code DEFAULT} and {@code ZEROAPR} each followed by three spaces, and the interest
 * calculation's not-found fallback looks up the <em>padded</em> ten-character default value, so a
 * trimmed key would never match and the fallback would silently stop working. All 300
 * daily-transaction processing timestamps in {@code app/data/ASCII/dailytran.txt} are 26 spaces, and
 * each must round-trip as 26 spaces - never {@code null}, never the empty string and never trimmed.
 * No trimming convenience is offered here, not even a clearly named one, because the safest design
 * for a rule this easy to violate is to make the violation unavailable.
 *
 * <p><strong>Leading zeros are significant.</strong> Unsigned numeric fields are right-justified,
 * zero-filled character data, not integers. Verified examples: a customer identifier is
 * {@code 000000001}, a card account identifier is {@code 00000000050} and a transaction category
 * code is {@code 0001}. A card verification value is three characters wide and the sample data does
 * contain such a value with a leading zero, so a single-digit value must survive as three characters
 * rather than one. A numeric field must therefore never be parsed to {@code int} or {@code long} and
 * re-formatted; it is carried as a {@link String} end to end, and {@link Builder#putNumeric} pads it
 * rather than converting it.
 *
 * <p><strong>Filler bytes are not uniform in the sample data.</strong> COBOL {@code FILLER X(n)}
 * with no {@code VALUE} clause is uninitialised, and the sample files show exactly that divergence:
 * the four master files carry space filler - 178 bytes per account record, 59 per card record, 168
 * per customer record, 20 per daily-transaction record - while the four reference-table files carry
 * ASCII-zero filler - 28 bytes per disclosure-group row, 22 per category-balance row, 8 per
 * transaction-type row, 4 per transaction-category row. The cross-reference file carries none,
 * because its text stride is 36 rather than the 50-byte cluster record length. Neither byte value is
 * canonical and neither is silently corrected: decision D-10 records the resolution and anomaly 20
 * the source divergence. Space is the module-wide <em>default</em>, so a {@link Builder} buffer is
 * space-initialised and every byte a mapper does not write emerges as a space, while
 * {@link Builder#putFiller(int, int, char)} lets a mapper reproduce the byte its own record carries
 * and {@link Builder#putSpaceFiller(int, int)} is the shorthand for the common case.
 *
 * <p><strong>Failure contract.</strong> Malformed input raises {@link IllegalArgumentException}
 * naming the artefact and, as applicable, the expected and actual encoded widths or the offset,
 * length and record width; {@code null} arguments raise {@link NullPointerException} by way of
 * {@link Objects#requireNonNull(Object, String)}. Input is never silently padded, never silently
 * truncated, never partially returned and never returned as {@code null}. No exception type from the
 * module's own package is used, deliberately: decision D-11 records that none of them models "the
 * caller handed me 297 bytes instead of 300", because a short record has no legacy antecedent at all
 * - VSAM and QSAM records are fixed length by construction - so the condition is a caller defect.
 *
 * <p><strong>Shape and thread safety.</strong> An instance is an immutable value object: the class is
 * final, both data fields are final, the record image is defensively copied on the way in and on the
 * way out, and the record width is validated once by the constructor so no subsequent slice
 * re-validates or re-encodes it. Instances are therefore safe to share across threads. The encode
 * direction needs a mutable buffer, so it lives in {@link Builder}, which is scoped to a single
 * record, is not thread safe and is not shared; this class holds no mutable static state.
 *
 * <p><strong>Usage.</strong> Decoding recovers the key and data halves of the {@code FD} split;
 * encoding is its exact inverse. The card number below is a synthetic, width-preserving placeholder
 * and not a value drawn from any data set.
 *
 * <pre>{@code
 * FixedWidthFieldReader record = FixedWidthFieldReader.of("ACCOUNT", image, 300);
 * String accountId = record.field("ACCT-ID", 0, 11); // "00000000001", untrimmed
 * String key       = record.key(11);                 // leading 11 bytes
 * String data      = record.data(11);                // remaining 289 bytes
 *
 * String xref = FixedWidthFieldReader.builder("CARD-XREF", 50)
 *         .putAlphanumeric("XREF-CARD-NUM", 0, 16, "0000000000000000")
 *         .putNumeric("XREF-CUST-ID", 16, 9, "50")
 *         .putNumeric("XREF-ACCT-ID", 25, 11, "50")
 *         .putSpaceFiller(36, 14)
 *         .build()
 *         .image();
 * }</pre>
 */
public final class FixedWidthFieldReader {

    /**
     * The single byte used for every alphanumeric pad byte this class emits and for the default
     * filler byte, {@code 0x20}.
     */
    private static final byte ASCII_SPACE = 0x20;

    /**
     * The single byte used to left-pad right-justified numeric fields, {@code 0x30}. It pads
     * unsigned numeric fields whose leading zeros are significant, and it is also the correct pad
     * for a zoned-decimal image, which is right-justified and zero-filled with its sign overpunched
     * into the final byte.
     */
    private static final byte ASCII_ZERO = 0x30;

    /** Highest code point US-ASCII can represent. Anything above it is rejected, never transcoded. */
    private static final char MAX_ASCII = 0x7F;

    /**
     * Uppercase hexadecimal digits, held as an immutable {@link String} rather than a
     * {@code char[]} so that this class carries no mutable static state of any kind.
     */
    private static final String HEX_DIGITS = "0123456789ABCDEF";

    /**
     * Name of the record layout, used only to make diagnostics identify their subject. Never
     * interpreted, never parsed and never used to select behaviour.
     */
    private final String artefact;

    /**
     * The validated record image, exactly {@link #recordWidth} bytes long. Private to this
     * instance: every path that accepts an array copies it in, and every path that returns bytes
     * copies them out, so this array can never be observed or mutated from outside.
     */
    private final byte[] image;

    /** Encoded byte length of the record image. Validated once, by the constructor. */
    private final int recordWidth;

    /**
     * Wraps an already validated, already private byte array.
     *
     * <p>The array is taken by reference on purpose: every caller of this constructor is a factory
     * in this file that has just produced a fresh array of exactly {@code recordWidth} bytes, so a
     * further copy here would be dead work. That invariant is the constructor's precondition and is
     * the reason the constructor is private.
     *
     * @param artefact    layout name for diagnostics, already checked non-blank
     * @param ownedImage  freshly allocated array of exactly {@code recordWidth} bytes, not shared
     * @param recordWidth already validated encoded byte width
     */
    private FixedWidthFieldReader(String artefact, byte[] ownedImage, int recordWidth) {
        this.artefact = artefact;
        this.image = ownedImage;
        this.recordWidth = recordWidth;
    }

    /**
     * Creates a reader over a record image supplied as a string.
     *
     * <p>The string is checked for US-ASCII representability before it is encoded, then its encoded
     * byte length is compared with {@code recordWidth}. A string whose encoded length differs from
     * the declared width is rejected outright; it is never padded to fit and never truncated to
     * fit.
     *
     * @param artefact    name of the record layout, used in diagnostics, for example
     *                    {@code "ACCOUNT"}; must be non-null and contain a non-space character
     * @param recordImage the complete record image, excluding any line terminator
     * @param recordWidth the layout's record width in encoded bytes; must be at least 1
     * @return an immutable reader over a private copy of the image
     * @throws NullPointerException     if {@code artefact} or {@code recordImage} is {@code null}
     * @throws IllegalArgumentException if {@code artefact} is blank, if {@code recordWidth} is less
     *                                  than 1, if the image contains a character US-ASCII cannot
     *                                  represent, or if the image's encoded byte length is not
     *                                  exactly {@code recordWidth}
     */
    public static FixedWidthFieldReader of(String artefact, String recordImage, int recordWidth) {
        String checkedArtefact = requireArtefact(artefact);
        Objects.requireNonNull(recordImage, "recordImage must not be null");
        requireWidthAtLeastOne(checkedArtefact, recordWidth);
        byte[] encoded = encodeAscii(checkedArtefact, null, recordImage);
        requireExactWidth(checkedArtefact, recordWidth, encoded.length);
        return new FixedWidthFieldReader(checkedArtefact, encoded, recordWidth);
    }

    /**
     * Creates a reader over a record image supplied as bytes.
     *
     * <p>Preferred over {@link #of(String, String, int)} when the caller already holds raw bytes,
     * because it removes any need for the caller to choose a charset: the bytes are verified to be
     * 7-bit ASCII here and decoded with US-ASCII on every slice.
     *
     * @param artefact    name of the record layout, used in diagnostics
     * @param recordImage the complete record image as bytes, excluding any line terminator
     * @param recordWidth the layout's record width in encoded bytes; must be at least 1
     * @return an immutable reader over a private copy of the supplied bytes
     * @throws NullPointerException     if {@code artefact} or {@code recordImage} is {@code null}
     * @throws IllegalArgumentException if {@code artefact} is blank, if {@code recordWidth} is less
     *                                  than 1, if {@code recordImage.length} is not exactly
     *                                  {@code recordWidth}, or if any byte is not 7-bit ASCII
     */
    public static FixedWidthFieldReader of(String artefact, byte[] recordImage, int recordWidth) {
        String checkedArtefact = requireArtefact(artefact);
        Objects.requireNonNull(recordImage, "recordImage must not be null");
        requireWidthAtLeastOne(checkedArtefact, recordWidth);
        requireExactWidth(checkedArtefact, recordWidth, recordImage.length);
        byte[] owned = new byte[recordWidth];
        System.arraycopy(recordImage, 0, owned, 0, recordWidth);
        requireAsciiBytes(checkedArtefact, owned);
        return new FixedWidthFieldReader(checkedArtefact, owned, recordWidth);
    }

    /**
     * Creates a reader over one record held inside a larger byte buffer.
     *
     * <p>This is the seam for callers that read a whole newline-terminated fixed-width file into a
     * single buffer. Because the stride of such a file is {@code recordWidth + 1}, record
     * <em>i</em> is addressed as {@code of(artefact, buffer, i * (recordWidth + 1), recordWidth)},
     * which selects the record and leaves the {@code 0x0A} terminator behind. Stride arithmetic and
     * file access stay with the caller; this class only ever sees one record's worth of bytes.
     *
     * @param artefact    name of the record layout, used in diagnostics
     * @param buffer      buffer containing the record, and possibly many others
     * @param from        zero-based index in {@code buffer} at which the record starts
     * @param recordWidth the layout's record width in encoded bytes; must be at least 1
     * @return an immutable reader over a private copy of the selected byte range
     * @throws NullPointerException     if {@code artefact} or {@code buffer} is {@code null}
     * @throws IllegalArgumentException if {@code artefact} is blank, if {@code recordWidth} is less
     *                                  than 1, if {@code from} is negative, if the range
     *                                  {@code [from, from + recordWidth)} is not wholly inside
     *                                  {@code buffer}, or if any byte in that range is not 7-bit
     *                                  ASCII
     */
    public static FixedWidthFieldReader of(String artefact, byte[] buffer, int from, int recordWidth) {
        String checkedArtefact = requireArtefact(artefact);
        Objects.requireNonNull(buffer, "buffer must not be null");
        requireWidthAtLeastOne(checkedArtefact, recordWidth);
        if (from < 0) {
            throw new IllegalArgumentException(checkedArtefact
                    + " record image start index must not be negative: from=" + from);
        }
        // Written as a subtraction so the bound cannot overflow for large indices.
        if (from > buffer.length - recordWidth) {
            throw new IllegalArgumentException(checkedArtefact
                    + " record image does not fit inside the supplied buffer: from=" + from
                    + ", recordWidth=" + recordWidth + ", buffer length=" + buffer.length);
        }
        byte[] owned = new byte[recordWidth];
        System.arraycopy(buffer, from, owned, 0, recordWidth);
        requireAsciiBytes(checkedArtefact, owned);
        return new FixedWidthFieldReader(checkedArtefact, owned, recordWidth);
    }

    /**
     * <p>The builder's buffer is space-initialised, so every byte the caller does not explicitly
     * place emerges as a space. That is what makes the module's default filler byte the default
     * outcome rather than something each mapper has to remember; a mapper whose record carries a
     * different filler byte states it through {@link Builder#putFiller(int, int, char)}.
     *
     * @param artefact    name of the record layout, used in diagnostics
     * @param recordWidth the layout's record width in encoded bytes; must be at least 1
     * @return a builder scoped to one record image
     * @throws NullPointerException     if {@code artefact} is {@code null}
     * @throws IllegalArgumentException if {@code artefact} is blank or {@code recordWidth} is less
     *                                  than 1
     */
    public static Builder builder(String artefact, int recordWidth) {
        String checkedArtefact = requireArtefact(artefact);
        requireWidthAtLeastOne(checkedArtefact, recordWidth);
        return new Builder(checkedArtefact, recordWidth);
    }

    /**
     * Returns the number of bytes {@code value} occupies when encoded as US-ASCII.
     *
     * <p>This class's public answer to "how wide is this value?", and the reason it exists as a
     * named operation is that a character count must never be used as a width authority. Other
     * utilities in this package keep their own width helpers for their own output formats; this one
     * is not a module-wide singleton for width and does not claim to be. A value containing a
     * character US-ASCII cannot represent has no valid encoded length and is rejected rather than
     * measured, because encoding it would substitute {@code '?'} and report a plausible but wrong
     * width.
     *
     * @param value the value to measure
     * @return the encoded byte length, zero for an empty value
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} contains a character US-ASCII cannot
     *                                  represent
     */
    public static int encodedLength(String value) {
        Objects.requireNonNull(value, "value must not be null");
        return encodeAscii("value", null, value).length;
    }

    /**
     * Returns the layout name this reader reports in diagnostics.
     *
     * @return the artefact name supplied at construction, never {@code null} and never blank
     */
    public String artefact() {
        return artefact;
    }

    /**
     * Returns the validated encoded byte width of this record image.
     *
     * @return the record width in encoded bytes, always at least 1
     */
    public int recordWidth() {
        return recordWidth;
    }

    /**
     * Slices a character field out of the record image by zero-based byte offset and byte length.
     *
     * <p>The value is returned exactly as it appears in the record: untrimmed, unstripped, not
     * case-folded and not normalised. A field of 26 spaces returns 26 spaces; a ten-byte key holding
     * {@code DEFAULT} followed by three spaces returns all ten characters. Diagnostics from this
     * overload cannot name the field, because none was supplied, so a record mapper should prefer
     * {@link #field(String, int, int)}, where a mis-declared offset identifies itself.
     *
     * @param offset zero-based byte offset of the field within the record image; must not be
     *               negative
     * @param length byte length of the field; must be at least 1
     * @return the raw, untrimmed field value, never {@code null}
     * @throws IllegalArgumentException if {@code offset} is negative, if {@code length} is less than
     *                                  1, or if {@code offset + length} exceeds the record width
     */
    public String field(int offset, int length) {
        // Delegates to the private primitive rather than to the named overload, because that
        // overload requires a non-null field name and this one has none to give.
        return sliceToString(null, offset, length);
    }

    /**
     * Slices a named character field out of the record image by zero-based byte offset and byte
     * length.
     *
     * <p>Behaves exactly as {@link #field(int, int)} and additionally names the field in any
     * diagnostic, so a mis-declared offset in a mapper identifies itself.
     *
     * @param fieldName name of the field for diagnostics, conventionally the legacy field name such
     *                  as {@code "ACCT-ID"}; must not be {@code null}
     * @param offset    zero-based byte offset of the field within the record image; must not be
     *                  negative
     * @param length    byte length of the field; must be at least 1
     * @return the raw, untrimmed field value, never {@code null}
     * @throws NullPointerException     if {@code fieldName} is {@code null}
     * @throws IllegalArgumentException if {@code offset} is negative, if {@code length} is less than
     *                                  1, or if {@code offset + length} exceeds the record width
     */
    public String field(String fieldName, int offset, int length) {
        Objects.requireNonNull(fieldName, "fieldName must not be null");
        return sliceToString(fieldName, offset, length);
    }

    /**
     * Slices a field out of the record image and returns its raw bytes.
     *
     * <p>Provided for the zoned-decimal path and any other consumer that prefers to work on bytes
     * rather than characters. The returned array is a fresh copy, so a caller may mutate it freely
     * without affecting this immutable reader.
     *
     * @param offset zero-based byte offset of the field within the record image; must not be
     *               negative
     * @param length byte length of the field; must be at least 1
     * @return a new array of exactly {@code length} bytes
     * @throws IllegalArgumentException if {@code offset} is negative, if {@code length} is less than
     *                                  1, or if {@code offset + length} exceeds the record width
     */
    public byte[] fieldBytes(int offset, int length) {
        // Delegates to the private primitive for the same reason as field(int, int).
        return sliceToBytes(null, offset, length);
    }

    /**
     * Slices a named field out of the record image and returns its raw bytes.
     *
     * <p>Behaves exactly as {@link #fieldBytes(int, int)} and additionally names the field in any
     * diagnostic.
     *
     * @param fieldName name of the field for diagnostics; must not be {@code null}
     * @param offset    zero-based byte offset of the field within the record image; must not be
     *                  negative
     * @param length    byte length of the field; must be at least 1
     * @return a new array of exactly {@code length} bytes
     * @throws NullPointerException     if {@code fieldName} is {@code null}
     * @throws IllegalArgumentException if {@code offset} is negative, if {@code length} is less than
     *                                  1, or if {@code offset + length} exceeds the record width
     */
    public byte[] fieldBytes(String fieldName, int offset, int length) {
        Objects.requireNonNull(fieldName, "fieldName must not be null");
        return sliceToBytes(fieldName, offset, length);
    }

    /**
     * Returns the leading key substring of the record image.
     *
     * <p>This is the first half of the {@code FD} record split, and it applies to a <em>base</em>
     * record's primary key: every {@code DEFINE CLUSTER} in the estate declares its key at offset
     * zero, so a base record's primary key is always its leading substring. For the account layout
     * {@code key(11)} yields the 11-digit account identifier {@code [app/cbl/CBACT01C.cbl:L42-L43]}.
     * Alternate-index keys are a different matter and are <strong>not</strong> reached through this
     * method: the three {@code DEFINE ALTERNATEINDEX} definitions key at non-zero offsets - the card
     * file's account identifier at offset 16, the cross-reference file's account identifier at
     * offset 25 and the transaction file's processing timestamp at offset 304 - so each is an
     * ordinary interior field addressed through {@link #field(int, int)}.
     *
     * @param keyLength byte length of the key; must be at least 1 and must not exceed the record
     *                  width
     * @return the raw, untrimmed key value, never {@code null}
     * @throws IllegalArgumentException if {@code keyLength} is less than 1 or exceeds the record
     *                                  width
     */
    public String key(int keyLength) {
        return sliceToString("key", 0, keyLength);
    }

    /**
     * Returns the data remainder that follows the leading key substring.
     *
     * <p>This is the second half of the {@code FD} record split. For the account layout
     * {@code data(11)} yields the 289-byte remainder, and 11 + 289 = 300 recovers the full record
     * width {@code [app/cbl/CBACT01C.cbl:L42-L43]}.
     *
     * @param keyLength byte length of the key that precedes the remainder; must be at least 1 and
     *                  must be strictly less than the record width, since a remainder of zero bytes
     *                  is not a slice
     * @return the raw, untrimmed data remainder, never {@code null}
     * @throws IllegalArgumentException if {@code keyLength} is less than 1, or if it leaves no
     *                                  remainder
     */
    public String data(int keyLength) {
        if (keyLength < 1) {
            throw new IllegalArgumentException(artefact
                    + " record image key length must be at least 1: keyLength=" + keyLength);
        }
        if (keyLength >= recordWidth) {
            throw new IllegalArgumentException(artefact
                    + " record image key length leaves no data remainder: keyLength=" + keyLength
                    + ", recordWidth=" + recordWidth);
        }
        return sliceToString("data", keyLength, recordWidth - keyLength);
    }

    /**
     * Returns the complete record image as a string.
     *
     * <p>Exactly {@link #recordWidth()} characters wide, untrimmed, and free of any line terminator.
     *
     * @return the whole record image, never {@code null}
     */
    public String image() {
        return new String(image, 0, recordWidth, StandardCharsets.US_ASCII);
    }

    /**
     * Returns the complete record image as bytes.
     *
     * <p>The returned array is a defensive copy; the internal image is never exposed.
     *
     * @return a new array of exactly {@link #recordWidth()} bytes
     */
    public byte[] toByteArray() {
        byte[] copy = new byte[recordWidth];
        System.arraycopy(image, 0, copy, 0, recordWidth);
        return copy;
    }

    /**
     * Returns a diagnostic description that deliberately excludes the record content.
     *
     * <p>Record images carry the estate's most sensitive data - social security numbers, primary
     * account numbers and card verification values - so rendering the image inside
     * {@code toString()} would leak it into any log line, assertion failure or debugger view that
     * happens to print the object. Only the layout name and the width appear, both of which are
     * layout facts rather than data. A caller that genuinely needs the content asks for it
     * explicitly through {@link #image()}.
     *
     * @return a content-free description such as {@code FixedWidthFieldReader[ACCOUNT, 300 bytes]}
     */
    @Override
    public String toString() {
        return "FixedWidthFieldReader[" + artefact + ", " + recordWidth + " bytes]";
    }

    /**
     * Slices the record image and decodes the slice as US-ASCII.
     *
     * @param fieldName field name for diagnostics, or {@code null} when the caller supplied none
     * @param offset    zero-based byte offset
     * @param length    byte length
     * @return the raw, untrimmed slice
     */
    private String sliceToString(String fieldName, int offset, int length) {
        requireSliceWithinRecord(fieldName, offset, length);
        return new String(image, offset, length, StandardCharsets.US_ASCII);
    }

    /**
     * Slices the record image and returns a fresh copy of the slice's bytes.
     *
     * @param fieldName field name for diagnostics, or {@code null} when the caller supplied none
     * @param offset    zero-based byte offset
     * @param length    byte length
     * @return a new array of exactly {@code length} bytes
     */
    private byte[] sliceToBytes(String fieldName, int offset, int length) {
        requireSliceWithinRecord(fieldName, offset, length);
        byte[] slice = new byte[length];
        System.arraycopy(image, offset, slice, 0, length);
        return slice;
    }

    /**
     * Verifies that a requested slice lies wholly inside this record image.
     *
     * @param fieldName field name for diagnostics, or {@code null} when the caller supplied none
     * @param offset    zero-based byte offset
     * @param length    byte length
     */
    private void requireSliceWithinRecord(String fieldName, int offset, int length) {
        requireSliceWithin(artefact, fieldName, offset, length, recordWidth);
    }


    /**
     * Validates the artefact name that every diagnostic in this class relies on.
     *
     * <p>A blank name is rejected rather than tolerated, because the entire value of the failure
     * contract is that a message identifies its subject; a reader built with an empty name produces
     * diagnostics nobody can act on.
     *
     * @param artefact candidate layout name
     * @return the same name, once validated
     * @throws NullPointerException     if {@code artefact} is {@code null}
     * @throws IllegalArgumentException if {@code artefact} contains no non-space character
     */
    private static String requireArtefact(String artefact) {
        Objects.requireNonNull(artefact, "artefact must not be null");
        boolean hasContent = false;
        for (int i = 0; i < artefact.length(); i++) {
            if (artefact.charAt(i) != ' ') {
                hasContent = true;
                break;
            }
        }
        if (!hasContent) {
            throw new IllegalArgumentException(
                    "artefact must name the record layout so diagnostics can identify it, "
                            + "but it contained no non-space character");
        }
        return artefact;
    }

    /**
     * Validates a declared record width.
     *
     * @param artefact    layout name for diagnostics
     * @param recordWidth candidate record width in encoded bytes
     * @throws IllegalArgumentException if {@code recordWidth} is less than 1
     */
    private static void requireWidthAtLeastOne(String artefact, int recordWidth) {
        if (recordWidth < 1) {
            throw new IllegalArgumentException(artefact
                    + " record width must be at least 1 encoded byte: recordWidth=" + recordWidth);
        }
    }

    /**
     * Compares an actual encoded byte length with the width the layout declares.
     *
     * <p>The message names the artefact, the expected encoded width and the actual encoded byte
     * length, which is the whole failure contract for a malformed record. When the overshoot is
     * exactly one byte the message also names the most common cause by far - an unstripped
     * {@code 0x0A} line terminator - because the fixtures are newline-terminated and their stride is
     * one greater than their record width.
     *
     * @param artefact layout name for diagnostics
     * @param expected the width the layout declares, in encoded bytes
     * @param actual   the width actually supplied, in encoded bytes
     * @throws IllegalArgumentException if the two widths differ
     */
    private static void requireExactWidth(String artefact, int expected, int actual) {
        if (actual == expected) {
            return;
        }
        StringBuilder message = new StringBuilder();
        message.append(artefact)
                .append(" record image must be exactly ")
                .append(expected)
                .append(" encoded bytes in US-ASCII, but the supplied image is ")
                .append(actual)
                .append(" encoded bytes; fixed-width records are never padded or truncated on input");
        if (actual == expected + 1) {
            message.append(" (an overshoot of exactly one byte is usually an unstripped 0x0A line ")
                    .append("terminator, which is a record separator and never record content)");
        }
        throw new IllegalArgumentException(message.toString());
    }

    /**
     * Verifies that a requested slice lies wholly inside a record of the given width.
     *
     * <p>The three range checks are deliberately separate so that the message says which rule was
     * broken rather than merely that something was out of range. The containment test is written as
     * a subtraction, {@code offset > recordWidth - length}, so it cannot overflow.
     *
     * @param artefact    layout name for diagnostics
     * @param fieldName   field name for diagnostics, or {@code null} when the caller supplied none
     * @param offset      zero-based byte offset
     * @param length      byte length
     * @param recordWidth record width in encoded bytes
     * @throws IllegalArgumentException if {@code offset} is negative, if {@code length} is less than
     *                                  1, or if the slice extends beyond the record
     */
    private static void requireSliceWithin(String artefact, String fieldName, int offset, int length,
            int recordWidth) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative in " + artefact
                    + " record image" + describeField(fieldName) + ": offset=" + offset);
        }
        if (length < 1) {
            throw new IllegalArgumentException("length must be at least 1 in " + artefact
                    + " record image" + describeField(fieldName) + ": length=" + length);
        }
        if (offset > recordWidth - length) {
            throw new IllegalArgumentException("slice out of range in " + artefact + " record image"
                    + describeField(fieldName) + ": offset=" + offset + ", length=" + length
                    + ", recordWidth=" + recordWidth);
        }
    }

    /**
     * Encodes a value as US-ASCII, refusing to transcode anything US-ASCII cannot represent.
     *
     * <p>The representability scan runs <em>before</em> the encode, and that ordering is the point
     * of this method. {@link String#getBytes(java.nio.charset.Charset)} substitutes {@code '?'} for
     * an unmappable character, so encoding first and inspecting afterwards cannot distinguish a
     * substituted byte from a question mark that was genuinely present. Scanning first makes the
     * rejection exact. The loop bound is a character count used purely to walk the value; the width
     * authority is the length of the returned array, never the character count.
     *
     * @param subject   layout or value name for diagnostics
     * @param fieldName field name for diagnostics, or {@code null} when the caller supplied none
     * @param value     the value to encode
     * @return the encoded bytes
     * @throws IllegalArgumentException if any character cannot be represented in US-ASCII
     */
    private static byte[] encodeAscii(String subject, String fieldName, String value) {
        for (int i = 0; i < value.length(); i++) {
            char candidate = value.charAt(i);
            if (candidate > MAX_ASCII) {
                throw new IllegalArgumentException(subject + describeField(fieldName)
                        + " contains a character that US-ASCII cannot represent at index " + i
                        + " (code unit 0x" + hex(candidate)
                        + "); CardDemo fixed-width records are 7-bit ASCII and must never be"
                        + " transcoded silently");
            }
        }
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Verifies that every byte of a record image is 7-bit ASCII.
     *
     * <p>A byte at or above {@code 0x80} is negative in Java's signed byte representation, so the
     * test is a sign test. Such a byte cannot have come from any of the nine ASCII fixtures, all of
     * which were verified to contain no byte above {@code 0x7F}, so its presence means the caller is
     * holding data in some other encoding - most likely an undecoded EBCDIC image, which must be
     * converted before it reaches this class rather than reinterpreted here.
     *
     * @param artefact layout name for diagnostics
     * @param bytes    the record image to inspect
     * @throws IllegalArgumentException if any byte is not 7-bit ASCII
     */
    private static void requireAsciiBytes(String artefact, byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] < 0) {
                throw new IllegalArgumentException(artefact
                        + " record image contains a non-ASCII byte at index " + i + " (0x"
                        + hex(bytes[i] & 0xFF)
                        + "); CardDemo fixed-width records are 7-bit ASCII, so a byte above 0x7F"
                        + " indicates data in another encoding rather than a record to slice");
            }
        }
    }

    /**
     * Renders a non-negative value as uppercase hexadecimal, two digits for a byte and four for a
     * wider code unit.
     *
     * <p>Written out rather than delegated so that the rendering is locale-independent by
     * construction: a locale-sensitive case conversion has no place in a diagnostic that describes
     * byte geometry.
     *
     * @param value the value to render, expected to be non-negative and no wider than 16 bits
     * @return the hexadecimal rendering, for example {@code "0A"} or {@code "4E2D"}
     */
    private static String hex(int value) {
        StringBuilder out = new StringBuilder();
        for (int shift = value > 0xFF ? 12 : 4; shift >= 0; shift -= 4) {
            out.append(HEX_DIGITS.charAt((value >>> shift) & 0xF));
        }
        return out.toString();
    }

    /**
     * Renders an optional field name as a diagnostic fragment.
     *
     * @param fieldName field name, or {@code null} when the caller supplied none
     * @return {@code ""} when no name was supplied, otherwise {@code " field 'NAME'"}
     */
    private static String describeField(String fieldName) {
        return fieldName == null ? "" : " field '" + fieldName + "'";
    }

    /**
     * Builds a fixed-width record image, one field at a time, and is the exact inverse of the
     * reader's slicing.
     *
     * <p>The buffer is allocated at the declared record width and filled with spaces before any
     * field is placed, so a mapper that leaves a filler run untouched still produces space filler -
     * the module-wide <em>default</em> recorded as decision D-10. A mapper whose own record carries a
     * different filler byte names that byte through {@link #putFiller(int, int, char)}, so the
     * default is a default and not a normalisation the mapper cannot escape. Placement is positional
     * and absolute: a field goes exactly where its offset says, so fields may be placed in any order
     * and a mapper need not mirror the copybook's declaration order.
     *
     * <p>Two placement modes cover the estate's two field kinds. Alphanumeric placement is
     * left-justified and space-padded, matching {@code PIC X(n)}. Numeric placement is
     * right-justified and zero-padded, matching {@code PIC 9(n)}, whose leading zeros are
     * significant, and equally matching a zoned-decimal image, which is right-justified and
     * zero-filled with its sign overpunched into the final byte.
     *
     * <p>A value wider than its field is rejected rather than truncated, as decision D-06 records:
     * a truncated field would shift nothing and corrupt everything, leaving the record exactly the
     * right width while carrying a wrong value - the one failure mode a width check can never catch
     * downstream.
     *
     * <p>An instance is mutable and scoped to a single record image. It is not thread safe and is
     * not intended to be shared or reused across records; a mapper creates one, fills it and calls
     * {@link #build()}. The builder holds no static state, and {@link #build()} hands the reader a
     * private copy, so continuing to place fields after building cannot alter an already built
     * record.
     */
    public static final class Builder {

        /** Layout name reported by this builder's diagnostics. */
        private final String artefact;

        /** Encoded byte width of the image under construction. */
        private final int recordWidth;

        /**
         * The image under construction, space-initialised at allocation. Private to this builder:
         * {@link #build()} copies it rather than handing it over, so no built record ever shares
         * this array.
         */
        private final byte[] buffer;

        /**
         * Allocates a space-initialised buffer of the declared width.
         *
         * @param artefact    validated layout name
         * @param recordWidth validated record width in encoded bytes
         */
        private Builder(String artefact, int recordWidth) {
            this.artefact = artefact;
            this.recordWidth = recordWidth;
            this.buffer = new byte[recordWidth];
            // Space-initialised before any placement, so every byte the caller never writes - every
            // filler run included - emerges as the module's default filler byte (decision D-10).
            for (int i = 0; i < recordWidth; i++) {
                this.buffer[i] = ASCII_SPACE;
            }
        }

        /**
         * Returns the layout name this builder reports in diagnostics.
         *
         * @return the artefact name, never {@code null} and never blank
         */
        public String artefact() {
            return artefact;
        }

        /**
         * Returns the encoded byte width of the image under construction.
         *
         * @return the record width in encoded bytes, always at least 1
         */
        public int recordWidth() {
            return recordWidth;
        }

        /**
         * Places an alphanumeric value left-justified and space-padded, matching {@code PIC X(n)}.
         *
         * <p>A value exactly as wide as the field is placed unchanged, which is what preserves
         * contractual trailing spaces: placing the ten-character value {@code DEFAULT} plus three
         * spaces into a ten-byte field reproduces all ten bytes, and placing 26 spaces into a
         * 26-byte timestamp field reproduces 26 spaces. A shorter value is padded on the right with
         * spaces; the empty value therefore yields a field of spaces, which is exactly how COBOL
         * renders an unset alphanumeric field.
         *
         * @param fieldName name of the field for diagnostics, conventionally the legacy field name;
         *                  must not be {@code null}
         * @param offset    zero-based byte offset at which the field starts; must not be negative
         * @param length    byte width of the field; must be at least 1
         * @param value     the value to place; may be empty but must not be {@code null}
         * @return this builder, for chaining
         * @throws NullPointerException     if {@code fieldName} or {@code value} is {@code null}
         * @throws IllegalArgumentException if the slice lies outside the record, if the value
         *                                  contains a character US-ASCII cannot represent, or if the
         *                                  value's encoded byte length exceeds {@code length}
         */
        public Builder putAlphanumeric(String fieldName, int offset, int length, String value) {
            byte[] encoded = prepare(fieldName, offset, length, value);
            // Left-justified: the value starts at the field offset, and the trailing pad is written
            // explicitly rather than left to the buffer's initial state. Writing the field's full
            // width makes a placement idempotent, so re-placing a shorter value over a longer one
            // cannot leave stale bytes behind - a defect that would keep the record exactly the
            // right width while corrupting its content.
            System.arraycopy(encoded, 0, buffer, offset, encoded.length);
            for (int i = encoded.length; i < length; i++) {
                buffer[offset + i] = ASCII_SPACE;
            }
            return this;
        }

        /**
         * Places an unsigned numeric value right-justified and zero-padded, matching
         * {@code PIC 9(n)}.
         *
         * <p>The value is carried as a {@link String} end to end and is never parsed to a numeric
         * type, because leading zeros are significant: a three-byte card verification field holding
         * the value {@code 7} must emerge as three characters and not as one, and the sample data
         * does contain such a leading-zero value. Parsing would also assume every field is numeric,
         * and one is not: a zoned-decimal image carries an overpunched sign in its final byte, which
         * is a letter or a brace. This method therefore performs no digit check at all - it is purely
         * justification and padding - so an unsigned numeric field and a zoned-decimal image
         * produced by the codec are both placed correctly.
         *
         * @param fieldName name of the field for diagnostics, conventionally the legacy field name;
         *                  must not be {@code null}
         * @param offset    zero-based byte offset at which the field starts; must not be negative
         * @param length    byte width of the field; must be at least 1
         * @param value     the value to place; may be empty, in which case the field becomes all
         *                  zeros, but must not be {@code null}
         * @return this builder, for chaining
         * @throws NullPointerException     if {@code fieldName} or {@code value} is {@code null}
         * @throws IllegalArgumentException if the slice lies outside the record, if the value
         *                                  contains a character US-ASCII cannot represent, or if the
         *                                  value's encoded byte length exceeds {@code length}
         */
        public Builder putNumeric(String fieldName, int offset, int length, String value) {
            byte[] encoded = prepare(fieldName, offset, length, value);
            int padWidth = length - encoded.length;
            // Right-justified: zero-fill the leading pad, then place the value flush against the
            // field's trailing edge so an overpunched sign byte stays in the final position.
            for (int i = 0; i < padWidth; i++) {
                buffer[offset + i] = ASCII_ZERO;
            }
            System.arraycopy(encoded, 0, buffer, offset + padWidth, encoded.length);
            return this;
        }

        /**
         * Declares a filler run and writes it with an explicit fill byte.
         *
         * <p>The filler byte is not uniform in the estate, so a mapper that must reproduce the byte
         * its own record actually carries states that byte here rather than inheriting the module's
         * space default. Decision D-10 records the resolution and anomaly 20 records the source
         * divergence: the four reference-table layouts carry ASCII-zero filler while the four master
         * layouts carry space filler, and neither value is canonical. Naming the run also keeps a
         * mapper's placements adding up to the full record width, which is how a missing field is
         * noticed during review, and it re-establishes the fill byte if a caller has already written
         * over part of the run.
         *
         * @param offset        zero-based byte offset at which the filler run starts; must not be
         *                      negative
         * @param length        byte width of the filler run; must be at least 1
         * @param fillCharacter the character repeated across the run, typically {@code ' '} for a
         *                      master layout or {@code '0'} for a reference table; must be
         *                      representable in US-ASCII
         * @return this builder, for chaining
         * @throws IllegalArgumentException if the run lies outside the record, or if
         *                                  {@code fillCharacter} is not representable in US-ASCII
         */
        public Builder putFiller(int offset, int length, char fillCharacter) {
            requireSliceWithin(artefact, "FILLER", offset, length, recordWidth);
            byte fillByte = encodeAscii(artefact, "FILLER", String.valueOf(fillCharacter))[0];
            for (int i = 0; i < length; i++) {
                buffer[offset + i] = fillByte;
            }
            return this;
        }

        /**
         * Declares a filler run and writes it as spaces, the module's default filler byte.
         *
         * <p>Named shorthand for {@link #putFiller(int, int, char)} with a space. Space is the
         * module-wide default recorded as decision D-10, and the buffer is space-initialised at
         * allocation, so this restates the default rather than changing it - which is the point:
         * naming the run makes the default visible at the call site instead of leaving it implicit in
         * a buffer initialisation several classes away, and keeps a mapper's placements adding up to
         * the full record width.
         *
         * @param offset zero-based byte offset at which the filler run starts; must not be negative
         * @param length byte width of the filler run; must be at least 1
         * @return this builder, for chaining
         * @throws IllegalArgumentException if the run lies outside the record
         */
        public Builder putSpaceFiller(int offset, int length) {
            return putFiller(offset, length, ' ');
        }

        /**
         * Completes the record image and returns an immutable reader over it.
         *
         * <p>Returning the enclosing type rather than a bare string or array is what makes encoding
         * the exact inverse of decoding: a freshly built record can be sliced immediately, which is
         * how a round-trip assertion is expressed in one expression. The reader receives a private
         * copy of the buffer, so this builder may continue to be used without affecting it.
         *
         * @return an immutable reader over the completed record image, always exactly
         *         {@link #recordWidth()} bytes wide
         */
        public FixedWidthFieldReader build() {
            byte[] owned = new byte[recordWidth];
            System.arraycopy(buffer, 0, owned, 0, recordWidth);
            return new FixedWidthFieldReader(artefact, owned, recordWidth);
        }

        /**
         * Returns a diagnostic description that deliberately excludes the buffer content, for the
         * same privacy reason as the enclosing class.
         *
         * @return a content-free description such as
         *         {@code FixedWidthFieldReader.Builder[ACCOUNT, 300 bytes]}
         */
        @Override
        public String toString() {
            return "FixedWidthFieldReader.Builder[" + artefact + ", " + recordWidth + " bytes]";
        }

        /**
         * Validates a placement and encodes its value, returning the bytes ready to be positioned.
         *
         * <p>Shared by both placement modes so that the range check, the US-ASCII check and the
         * overflow check are written once and cannot drift apart. The overflow comparison uses the
         * encoded byte length, never a character count.
         *
         * @param fieldName name of the field for diagnostics
         * @param offset    zero-based byte offset at which the field starts
         * @param length    byte width of the field
         * @param value     the value to place
         * @return the value's encoded bytes, guaranteed no wider than {@code length}
         */
        private byte[] prepare(String fieldName, int offset, int length, String value) {
            Objects.requireNonNull(fieldName, "fieldName must not be null");
            Objects.requireNonNull(value, "value must not be null");
            requireSliceWithin(artefact, fieldName, offset, length, recordWidth);
            byte[] encoded = encodeAscii(artefact, fieldName, value);
            if (encoded.length > length) {
                throw new IllegalArgumentException("value does not fit " + artefact
                        + " record image field '" + fieldName + "': field width is " + length
                        + " encoded bytes but the value is " + encoded.length
                        + " encoded bytes; a fixed-width field is never truncated to fit, because a"
                        + " truncated value would leave the record the right width and the wrong"
                        + " content");
            }
            return encoded;
        }
    }
}
