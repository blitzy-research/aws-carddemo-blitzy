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
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;

/**
 * Converts between the legacy zoned-decimal field image and {@link BigDecimal}, and is the
 * <em>single point of decimal truth</em> for the whole module.
 *
 * <p><strong>Why this class exists.</strong> Every persisted monetary and rate value in the
 * CardDemo estate is stored as zoned decimal under {@code USAGE DISPLAY}: one ASCII byte per
 * digit, with the sign overpunched into the final digit byte. This class is the only authority
 * that converts that on-disk representation to and from {@code BigDecimal}, and the only place
 * in the module where a scale is ever applied.</p>
 *
 * <p><strong>No other class may call {@code setScale}.</strong> Services, batch processors,
 * controllers and record mappers must route every scaling operation through
 * {@link #toMonetaryScale(BigDecimal)} or {@link #toScale(BigDecimal, int)}. Centralising the
 * policy here is what makes it impossible for one caller to introduce a different rounding
 * mode; a codec that merely offered a rounding-mode parameter would have surrendered exactly
 * the guarantee it exists to provide, which is why no method on this class accepts one.</p>
 *
 * <p><strong>Truncation, never rounding.</strong> A search for the {@code ROUNDED} keyword
 * across every program and copybook in the estate returns zero occurrences: not one arithmetic
 * statement specifies rounding. A COBOL arithmetic store without {@code ROUNDED} truncates
 * toward zero, so {@link RoundingMode#DOWN} is mandatory and is the mode published as
 * {@link #COBOL_TRUNCATION_MODE}. The half-even and half-up modes named {@code HALF_EVEN} and
 * {@code HALF_UP} on {@link RoundingMode} are forbidden in this codec and in every caller:
 * {@code HALF_EVEN} &mdash; the conventional Java choice &mdash; would differ by one cent on roughly half
 * of all interest computations, which is a byte-parity failure that is completely invisible to
 * a test suite written under the same wrong assumption.</p>
 *
 * <p><strong>Zoned decimal only; there is no packed-decimal path here by design.</strong>
 * {@code COMP-3} occurs zero times in the copybook tree, and the nine declaration sites in the
 * program tree are all transient working-storage work fields that are never part of a persisted
 * layout. A binary-coded-decimal decoder would therefore be dead code, and none is provided.
 * Do not add one.</p>
 *
 * <p><strong>Field image geometry.</strong> {@code PIC S9(n)V99} occupies exactly
 * {@code n + 2} bytes. The sign is overpunched into the final digit byte, so there is
 * <em>no separate sign byte</em> and no byte is spent on the implied decimal point. The scale
 * is implied by the picture clause, never present in the image.</p>
 *
 * <table>
 * <caption>Overpunched sign convention carried by the final digit byte</caption>
 * <tr><th scope="col">Low-order digit</th><th scope="col">Positive</th>
 *     <th scope="col">Negative</th></tr>
 * <tr><td>0</td><td><code>&#123;</code></td><td><code>&#125;</code></td></tr>
 * <tr><td>1</td><td><code>A</code></td><td><code>J</code></td></tr>
 * <tr><td>2</td><td><code>B</code></td><td><code>K</code></td></tr>
 * <tr><td>3</td><td><code>C</code></td><td><code>L</code></td></tr>
 * <tr><td>4</td><td><code>D</code></td><td><code>M</code></td></tr>
 * <tr><td>5</td><td><code>E</code></td><td><code>N</code></td></tr>
 * <tr><td>6</td><td><code>F</code></td><td><code>O</code></td></tr>
 * <tr><td>7</td><td><code>G</code></td><td><code>P</code></td></tr>
 * <tr><td>8</td><td><code>H</code></td><td><code>Q</code></td></tr>
 * <tr><td>9</td><td><code>I</code></td><td><code>R</code></td></tr>
 * </table>
 *
 * <p>All twenty of those characters occur in production-representative fixture data. A census
 * of the final byte of the daily-transaction amount field (byte offset 142 of the 350-byte
 * record) across all 300 records of {@code [app/data/ASCII/dailytran.txt]} yields
 * <code>&#123;</code>&nbsp;25, {@code A} 28, {@code B} 29, {@code C} 30, {@code D} 29,
 * {@code E} 23, {@code F} 21, {@code G} 24, {@code H} 17, {@code I} 24 &mdash; 250 positive &mdash; and
 * <code>&#125;</code>&nbsp;6, {@code J} 3, {@code K} 5, {@code L} 5, {@code M} 6, {@code N} 2,
 * {@code O} 4, {@code P} 7, {@code Q} 4, {@code R} 8 &mdash; 50 negative. The sign table is therefore
 * exercised end to end by real data rather than by synthetic fixtures.</p>
 *
 * <p>An <em>unsigned</em> zoned field simply carries {@code '0'} through {@code '9'} in every
 * byte, including the last. Decoding accepts that form and treats it as positive. Encoding
 * always emits the signed, overpunched form, because every signed field in the estate is
 * written that way &mdash; including positive values, which carry <code>&#123;</code> through
 * {@code I} rather than a plain digit.</p>
 *
 * <table>
 * <caption>Persisted receiving fields, their picture clauses, encoded widths and scales</caption>
 * <tr><th scope="col">Field</th><th scope="col">Picture</th>
 *     <th scope="col">Encoded bytes</th><th scope="col">Scale</th></tr>
 * <tr><td>{@code ACCT-CURR-BAL}, {@code ACCT-CREDIT-LIMIT}, {@code ACCT-CASH-CREDIT-LIMIT},
 *         {@code ACCT-CURR-CYC-CREDIT}, {@code ACCT-CURR-CYC-DEBIT}
 *         {@code [app/cpy/CVACT01Y.cpy]}</td>
 *     <td>{@code S9(10)V99}</td><td>12</td><td>2</td></tr>
 * <tr><td>{@code TRAN-AMT} {@code [app/cpy/CVTRA05Y.cpy]}</td>
 *     <td>{@code S9(09)V99}</td><td>11</td><td>2</td></tr>
 * <tr><td>{@code DALYTRAN-AMT} {@code [app/cpy/CVTRA06Y.cpy]}</td>
 *     <td>{@code S9(09)V99}</td><td>11</td><td>2</td></tr>
 * <tr><td>{@code TRAN-CAT-BAL} {@code [app/cpy/CVTRA01Y.cpy]}</td>
 *     <td>{@code S9(09)V99}</td><td>11</td><td>2</td></tr>
 * <tr><td>{@code DIS-INT-RATE} {@code [app/cpy/CVTRA02Y.cpy]}</td>
 *     <td>{@code S9(04)V99}</td><td>6</td><td>2</td></tr>
 * <tr><td>{@code WS-MONTHLY-INT}, {@code WS-TOTAL-INT}
 *         {@code [app/cbl/CBACT04C.cbl:L168-L169]}</td>
 *     <td>{@code S9(09)V99}</td><td>11 (never written to a file)</td><td>2</td></tr>
 * <tr><td>{@code WS-TEMP-BAL} {@code [app/cbl/CBTRN02C.cbl:L187]}</td>
 *     <td>{@code S9(09)V99}</td><td>11 (never written to a file)</td><td>2</td></tr>
 * </table>
 *
 * <p>The last two rows are working-storage receiving fields rather than record fields. They
 * have no encoded image, so this class publishes no width constant for them; what matters is
 * that their scale is 2 and that a store into them truncates, which is precisely what
 * {@link #toMonetaryScale(BigDecimal)} reproduces.</p>
 *
 * <table>
 * <caption>Worked examples taken byte for byte from the shipped fixtures</caption>
 * <tr><th scope="col">Source</th><th scope="col">Raw bytes</th>
 *     <th scope="col">Unsigned digits</th><th scope="col">Scale</th>
 *     <th scope="col">Decoded value</th></tr>
 * <tr><td>{@code [app/data/ASCII/acctdata.txt]} row 0 {@code ACCT-CURR-BAL}
 *         (offset 12, length 12)</td>
 *     <td><code>00000001940&#123;</code></td><td>{@code 000000019400}</td><td>2</td>
 *     <td>{@code 194.00}</td></tr>
 * <tr><td>{@code [app/data/ASCII/acctdata.txt]} row 0 {@code ACCT-CREDIT-LIMIT}
 *         (offset 24, length 12)</td>
 *     <td><code>00000020200&#123;</code></td><td>{@code 000000202000}</td><td>2</td>
 *     <td>{@code 2020.00}</td></tr>
 * <tr><td>{@code [app/data/ASCII/acctdata.txt]} row 0 {@code ACCT-CURR-CYC-CREDIT}
 *         (offset 78, length 12)</td>
 *     <td><code>00000000000&#123;</code></td><td>{@code 000000000000}</td><td>2</td>
 *     <td>{@code 0.00}</td></tr>
 * <tr><td>{@code [app/data/ASCII/dailytran.txt]} row 0 {@code DALYTRAN-AMT}
 *         (offset 132, length 11)</td>
 *     <td>{@code 0000005047G}</td><td>{@code 00000050477}</td><td>2</td>
 *     <td>{@code 504.77}</td></tr>
 * <tr><td>{@code [app/data/ASCII/dailytran.txt]} row 250 {@code DALYTRAN-AMT}</td>
 *     <td>{@code 0000000349I}</td><td>{@code 00000003499}</td><td>2</td>
 *     <td>{@code 34.99}</td></tr>
 * <tr><td>{@code [app/data/ASCII/dailytran.txt]} row 299 {@code DALYTRAN-AMT}</td>
 *     <td>{@code 0000006032B}</td><td>{@code 00000060322}</td><td>2</td>
 *     <td>{@code 603.22}</td></tr>
 * <tr><td>{@code [app/data/ASCII/discgrp.txt]} row 0 {@code DIS-INT-RATE}
 *         (offset 16, length 6)</td>
 *     <td><code>00150&#123;</code></td><td>{@code 001500}</td><td>2</td>
 *     <td>{@code 15.00}</td></tr>
 * <tr><td>{@code [app/data/ASCII/discgrp.txt]} row 34 {@code DIS-INT-RATE}
 *         (the zero-rate disclosure group)</td>
 *     <td><code>00000&#123;</code></td><td>{@code 000000}</td><td>2</td>
 *     <td>{@code 0.00}</td></tr>
 * <tr><td>{@code [app/data/ASCII/tcatbal.txt]} row 0 {@code TRAN-CAT-BAL}
 *         (offset 17, length 11)</td>
 *     <td><code>0000000000&#123;</code></td><td>{@code 00000000000}</td><td>2</td>
 *     <td>{@code 0.00}</td></tr>
 * </table>
 *
 * <p>The unsigned-digits column of that table is authoritative, because the decoded value follows
 * from it by arithmetic alone. One row is worth spelling out, since the migration plan's own
 * table transposes it: an image ending in {@code G} carries a low-order 7, so
 * {@code 0000005047G} is 50477 at scale 2, which is {@code 504.77} and cannot be {@code 500.47}.
 * The value was recomputed from the fixture bytes rather than copied, and every one of the 651
 * amount fields in the four shipped fixtures decodes and re-encodes byte for byte under this
 * implementation.</p>
 *
 * <p><strong>Negative zero.</strong> The legacy byte image distinguishes <code>&#125;</code>
 * (negative zero) from <code>&#123;</code> (positive zero), but {@code BigDecimal} has no
 * negative zero. Both images therefore decode to zero at the requested scale, and encoding a
 * zero value always emits <code>&#123;</code>. The asymmetry is deliberate and is recorded in
 * the module decision log; it is observable only for a field whose every digit is zero, where
 * the arithmetic value is identical either way.</p>
 *
 * <p>Read that narrowly. A trailing <code>&#125;</code> means "negative, low-order digit zero",
 * so it marks a negative zero <em>only</em> when every other digit is zero as well. Six of the
 * 300 daily-transaction amounts end in <code>&#125;</code> and none of them is a negative zero:
 * they are ordinary negative amounts whose cent digit happens to be zero, and they re-encode
 * byte for byte. Treating every <code>&#125;</code> as a negative zero would silently flip the
 * sign of those records.</p>
 *
 * <p><strong>Operand order is contractual, and this class is why.</strong> Truncation makes
 * arithmetic non-associative, so an algebraically identical rearrangement can produce a
 * different stored value. Two computations in the estate depend on that and must be reproduced
 * operand for operand by their owning services, not by this codec:</p>
 * <ul>
 *   <li>The monthly interest accrual {@code [app/cbl/CBACT04C.cbl:L464]} multiplies the
 *       category balance by the disclosure rate <em>first</em> and divides by 1200
 *       <em>second</em>. Dividing the rate by 1200 first is algebraically identical in exact
 *       arithmetic but moves the truncation point and changes the result.</li>
 *   <li>The overlimit basis {@code [app/cbl/CBTRN02C.cbl:L403]} evaluates strictly left to
 *       right &mdash; cycle credit, minus cycle debit, plus the daily transaction amount &mdash; into a
 *       {@code PIC S9(09)V99} field. Reordering it changes which transactions are rejected.</li>
 * </ul>
 * <p>Those computations belong to the interest-calculation and transaction-posting services.
 * This class only guarantees that the truncation they depend on is correct.</p>
 *
 * <p><strong>Failure contract.</strong> Every rejection raises
 * {@link IllegalArgumentException}, whose message names the field being processed together with
 * either the expected and actual <em>encoded byte</em> widths or the offending byte and its
 * zero-based offset. Nothing is ever silently padded, truncated, coerced or returned as
 * {@code null}, and no partial value is ever produced. Messages deliberately omit both the
 * record image and the monetary value so that a rejection cannot leak account data into a log.
 * A {@code null} image or value is treated as a programming error on the same code path,
 * so a caller needs to catch exactly one exception type.</p>
 *
 * <p><strong>Encoded bytes, never character counts.</strong> Every width this class validates
 * or emits is measured in bytes encoded as {@link StandardCharsets#US_ASCII}, never as
 * {@code String.length()}, so a multi-byte character can never silently break a fixed-width
 * field. A character outside the US-ASCII range is invalid data in a zoned field and is
 * rejected rather than transcoded to a replacement byte. No conversion in this class relies on
 * the platform default charset.</p>
 *
 * <p><strong>On the numbers quoted above.</strong> Record widths, byte offsets, field lengths,
 * code frequencies and seeded-row counts are factual layout evidence, not service-level
 * figures. This class declares no buffer size, timeout, throughput, latency or heap figure of
 * any kind, and nothing in it should be read as a performance target.</p>
 *
 * <p>Every value in this class is derived from the legacy estate at commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, whose members carry the upstream release
 * stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Copybooks, programs and job
 * control are cited, never transcribed: only field lengths, byte offsets and scales cross the
 * boundary into this file.</p>
 *
 * <p>This class is stateless, immutable and thread-safe. All methods are pure: they perform no
 * I/O, consult no clock, read no environment and hold no mutable state.</p>
 */
public final class ZonedDecimalCodec {

    /**
     * The one rounding mode this module is permitted to use.
     *
     * <p>{@code ROUNDED} appears zero times across every program and copybook in the estate, and
     * a COBOL arithmetic store without it truncates toward zero. {@link RoundingMode#DOWN} is
     * therefore the faithful translation; {@code HALF_EVEN} and {@code HALF_UP} are forbidden.
     * The constant is published so that the policy is inspectable and assertable, not so that
     * callers can apply it themselves &mdash; use {@link #toMonetaryScale(BigDecimal)} or
     * {@link #toScale(BigDecimal, int)} instead of calling {@code setScale} directly.</p>
     */
    public static final RoundingMode COBOL_TRUNCATION_MODE = RoundingMode.DOWN;

    /**
     * The scale shared by every monetary and rate field in the estate, being the two digits
     * after the implied decimal point in a {@code V99} picture clause.
     */
    public static final int MONETARY_SCALE = 2;

    /**
     * Encoded byte width of a {@code PIC S9(10)V99} field: ten integer digits plus two implied
     * decimal digits, with the sign overpunched into the last of them.
     */
    public static final int WIDTH_PIC_S9_10_V99 = 12;

    /**
     * Encoded byte width of a {@code PIC S9(09)V99} field: nine integer digits plus two implied
     * decimal digits, with the sign overpunched into the last of them.
     */
    public static final int WIDTH_PIC_S9_09_V99 = 11;

    /**
     * Encoded byte width of a {@code PIC S9(04)V99} field: four integer digits plus two implied
     * decimal digits, with the sign overpunched into the last of them.
     */
    public static final int WIDTH_PIC_S9_04_V99 = 6;

    /**
     * Encoded byte width of each of the five account money fields &mdash; current balance, credit
     * limit, cash credit limit, current cycle credit and current cycle debit &mdash; declared
     * {@code PIC S9(10)V99} in {@code [app/cpy/CVACT01Y.cpy]}.
     */
    public static final int ACCOUNT_AMOUNT_WIDTH = WIDTH_PIC_S9_10_V99;

    /**
     * Encoded byte width of the transaction amount, declared {@code PIC S9(09)V99} in
     * {@code [app/cpy/CVTRA05Y.cpy]}.
     */
    public static final int TRANSACTION_AMOUNT_WIDTH = WIDTH_PIC_S9_09_V99;

    /**
     * Encoded byte width of the daily-transaction amount, declared {@code PIC S9(09)V99} in
     * {@code [app/cpy/CVTRA06Y.cpy]}. It shares the transaction amount's shape because the
     * daily-transaction layout is byte-for-byte identical to the transaction layout.
     */
    public static final int DAILY_TRANSACTION_AMOUNT_WIDTH = WIDTH_PIC_S9_09_V99;

    /**
     * Encoded byte width of the transaction category balance, declared {@code PIC S9(09)V99} in
     * {@code [app/cpy/CVTRA01Y.cpy]}.
     */
    public static final int CATEGORY_BALANCE_WIDTH = WIDTH_PIC_S9_09_V99;

    /**
     * Encoded byte width of the disclosure-group interest rate, declared
     * {@code PIC S9(04)V99} in {@code [app/cpy/CVTRA02Y.cpy]}. It is the only rate field in the
     * estate and the only field of this shape.
     */
    public static final int INTEREST_RATE_WIDTH = WIDTH_PIC_S9_04_V99;

    /**
     * Overpunch characters for a positive final digit, indexed by the digit itself, so that
     * position 0 holds the positive-zero character and position 9 the positive-nine character.
     * A {@code String} is used rather than an array because it is immutable and therefore
     * cannot become mutable static state.
     */
    private static final String POSITIVE_OVERPUNCH = "{ABCDEFGHI";

    /**
     * Overpunch characters for a negative final digit, indexed by the digit itself, so that
     * position 0 holds the negative-zero character and position 9 the negative-nine character.
     */
    private static final String NEGATIVE_OVERPUNCH = "}JKLMNOPQR";

    /** Highest code point that {@link StandardCharsets#US_ASCII} can represent. */
    private static final int MAX_ASCII_CODE_POINT = 0x7F;

    /** Label used in a diagnostic message when the caller supplied no field name. */
    private static final String UNNAMED_FIELD = "(unnamed field)";

    /**
     * Not instantiable: this is a stateless codec exposing only static members, so an instance
     * would carry no state and confer no capability.
     */
    private ZonedDecimalCodec() {
    }

    /**
     * Decodes a zoned-decimal field image held as a {@code String} into a value whose scale is
     * exactly {@code scale}.
     *
     * <p>The image must be exactly {@code width} bytes when encoded as
     * {@link StandardCharsets#US_ASCII}. Every byte except the last must be an ASCII digit; the
     * last byte is either an ASCII digit, meaning the field is unsigned and positive, or one of
     * the twenty overpunch characters, which supplies both the low-order digit and the sign. The
     * negative-zero image decodes to zero, because {@code BigDecimal} has no negative zero.</p>
     *
     * <p>For example, the eleven-byte image {@code 0000005047G} at scale 2 decodes to
     * {@code 504.77}: the trailing {@code G} contributes a low-order 7 and a positive sign, so the
     * unsigned digits are {@code 00000050477}. That is the daily-transaction amount of the first
     * record of {@code [app/data/ASCII/dailytran.txt]}.</p>
     *
     * @param  image     the field image; must not be {@code null}
     * @param  width     expected encoded byte width of the whole image, at least 1; use one of
     *                   the published width constants for a known field shape
     * @param  scale     number of implied decimal digits, between 0 and {@code width} inclusive;
     *                   the returned value carries exactly this scale
     * @param  fieldName legacy field name used in diagnostics, such as {@code DALYTRAN-AMT}
     * @return the decoded value, never {@code null}, whose scale is exactly {@code scale}
     * @throws IllegalArgumentException if the image is {@code null}, is not exactly
     *                                  {@code width} encoded bytes, contains a character that
     *                                  US-ASCII cannot represent, contains a non-digit before
     *                                  the final byte, ends in a byte that is neither a digit
     *                                  nor an overpunch character, or if {@code width} or
     *                                  {@code scale} is itself out of range
     */
    public static BigDecimal decode(String image, int width, int scale, String fieldName) {
        String label = fieldLabel(fieldName);
        requireWidthAndScale(width, scale, label);
        byte[] bytes = asciiBytes(image, label);
        if (bytes.length != width) {
            throw widthMismatch(label, width, bytes.length);
        }
        return decodeSlice(bytes, 0, width, scale, label);
    }

    /**
     * Decodes a zoned-decimal field held as {@code width} bytes starting at {@code offset} of a
     * fixed-width record image.
     *
     * <p>This is the entry point for a caller that already holds the raw record bytes, so that
     * no caller has to guess an encoding or slice a {@code String} first. The array is only
     * read: it is neither retained nor modified.</p>
     *
     * @param  record the record image containing the field; must not be {@code null}
     * @param  offset zero-based byte offset of the field within {@code record}
     * @param  width  encoded byte width of the field, at least 1
     * @param  scale  number of implied decimal digits, between 0 and {@code width} inclusive
     * @param  fieldName legacy field name used in diagnostics, such as {@code ACCT-CURR-BAL}
     * @return the decoded value, never {@code null}, whose scale is exactly {@code scale}
     * @throws IllegalArgumentException if {@code record} is {@code null}, if the requested slice
     *                                  falls outside the record, if any byte of the field is
     *                                  invalid, or if {@code width} or {@code scale} is out of
     *                                  range
     */
    public static BigDecimal decode(byte[] record, int offset, int width, int scale,
            String fieldName) {
        String label = fieldLabel(fieldName);
        requireWidthAndScale(width, scale, label);
        if (record == null) {
            throw new IllegalArgumentException("zoned decimal field " + label
                    + ": the record image must not be null");
        }
        if (offset < 0 || offset > record.length - width) {
            throw new IllegalArgumentException("zoned decimal field " + label + ": a " + width
                    + "-byte field at zero-based offset " + offset
                    + " falls outside a record image of " + record.length + " byte(s)");
        }
        return decodeSlice(record, offset, width, scale, label);
    }

    /**
     * Decodes a monetary or rate field image held as a {@code String} at the canonical scale of
     * {@value #MONETARY_SCALE}, which every such field in the estate shares.
     *
     * @param  image     the field image; must not be {@code null}
     * @param  width     expected encoded byte width of the whole image, at least 1
     * @param  fieldName legacy field name used in diagnostics
     * @return the decoded value, never {@code null}, whose scale is exactly
     *         {@value #MONETARY_SCALE}
     * @throws IllegalArgumentException if the image or the width is invalid
     */
    public static BigDecimal decodeMonetary(String image, int width, String fieldName) {
        return decode(image, width, MONETARY_SCALE, fieldName);
    }

    /**
     * Decodes a monetary or rate field from a fixed-width record image at the canonical scale of
     * {@value #MONETARY_SCALE}.
     *
     * @param  record    the record image containing the field; must not be {@code null}
     * @param  offset    zero-based byte offset of the field within {@code record}
     * @param  width     encoded byte width of the field, at least 1
     * @param  fieldName legacy field name used in diagnostics
     * @return the decoded value, never {@code null}, whose scale is exactly
     *         {@value #MONETARY_SCALE}
     * @throws IllegalArgumentException if the record, the slice or the width is invalid
     */
    public static BigDecimal decodeMonetary(byte[] record, int offset, int width,
            String fieldName) {
        return decode(record, offset, width, MONETARY_SCALE, fieldName);
    }

    /**
     * Encodes a value into a zoned-decimal field image of exactly {@code width} bytes, returning
     * the bytes themselves so that the emitted width is guaranteed by construction rather than
     * asserted after the fact.
     *
     * <p>The value is first brought to {@code scale} using {@link #COBOL_TRUNCATION_MODE}, which
     * truncates toward zero exactly as a COBOL store without {@code ROUNDED} does. The absolute
     * unscaled digits are then left-padded with ASCII zeros and the final digit is replaced by
     * the overpunch character carrying both that digit and the sign. A value of zero always
     * emits the positive-zero character, since {@code BigDecimal} has no negative zero.</p>
     *
     * <p>Encoding is the exact inverse of decoding for every one of the twenty signed images:
     * decoding {@code 0000005047G} at scale 2 and re-encoding it at width 11 reproduces
     * {@code 0000005047G} byte for byte.</p>
     *
     * <p>A value too large for the field is rejected rather than truncated on the left, because
     * silently dropping a high-order digit would corrupt the amount instead of failing. The
     * diagnostic reports the digit count required and the width available, and deliberately
     * omits the value itself so that a rejection cannot leak account data into a log.</p>
     *
     * @param  value     the value to encode; must not be {@code null}
     * @param  width     encoded byte width of the field, at least 1
     * @param  scale     number of implied decimal digits, between 0 and {@code width} inclusive
     * @param  fieldName legacy field name used in diagnostics
     * @return a fresh array of exactly {@code width} US-ASCII bytes, never shared and never
     *         {@code null}
     * @throws IllegalArgumentException if the value is {@code null}, does not fit the field at
     *                                 the requested scale, or if {@code width} or {@code scale}
     *                                 is out of range
     */
    public static byte[] encodeToBytes(BigDecimal value, int width, int scale, String fieldName) {
        String label = fieldLabel(fieldName);
        requireWidthAndScale(width, scale, label);
        if (value == null) {
            throw new IllegalArgumentException("zoned decimal field " + label
                    + ": the value to encode must not be null");
        }
        BigDecimal truncated = value.setScale(scale, COBOL_TRUNCATION_MODE);
        String digits = truncated.unscaledValue().abs().toString();
        if (digits.length() > width) {
            throw new IllegalArgumentException("zoned decimal field " + label + ": the value"
                    + " needs " + digits.length() + " digit(s) at scale " + scale
                    + " but the field holds only " + width);
        }
        byte[] image = new byte[width];
        int padding = width - digits.length();
        for (int i = 0; i < padding; i++) {
            image[i] = (byte) '0';
        }
        int lastDigitIndex = digits.length() - 1;
        for (int i = 0; i < lastDigitIndex; i++) {
            image[padding + i] = (byte) digits.charAt(i);
        }
        int lastDigit = digits.charAt(lastDigitIndex) - '0';
        String overpunch = truncated.signum() < 0 ? NEGATIVE_OVERPUNCH : POSITIVE_OVERPUNCH;
        image[width - 1] = (byte) overpunch.charAt(lastDigit);
        return image;
    }

    /**
     * Encodes a value into a zoned-decimal field image of exactly {@code width} bytes, returned
     * as a {@code String}.
     *
     * <p>The image is produced from the byte form built by
     * {@link #encodeToBytes(BigDecimal, int, int, String)} and decoded back with US-ASCII, so its
     * encoded byte width is exactly {@code width} by construction and one byte per character is
     * a guarantee rather than an assumption.</p>
     *
     * @param  value     the value to encode; must not be {@code null}
     * @param  width     encoded byte width of the field, at least 1
     * @param  scale     number of implied decimal digits, between 0 and {@code width} inclusive
     * @param  fieldName legacy field name used in diagnostics
     * @return the field image, never {@code null}, of exactly {@code width} US-ASCII bytes
     * @throws IllegalArgumentException if the value is {@code null}, does not fit the field at
     *                                 the requested scale, or if {@code width} or {@code scale}
     *                                 is out of range
     */
    public static String encode(BigDecimal value, int width, int scale, String fieldName) {
        return new String(encodeToBytes(value, width, scale, fieldName), StandardCharsets.US_ASCII);
    }

    /**
     * Encodes a monetary or rate value at the canonical scale of {@value #MONETARY_SCALE},
     * returning the field image as a {@code String}.
     *
     * @param  value     the value to encode; must not be {@code null}
     * @param  width     encoded byte width of the field, at least 1
     * @param  fieldName legacy field name used in diagnostics
     * @return the field image, never {@code null}, of exactly {@code width} US-ASCII bytes
     * @throws IllegalArgumentException if the value is {@code null} or does not fit the field
     */
    public static String encodeMonetary(BigDecimal value, int width, String fieldName) {
        return encode(value, width, MONETARY_SCALE, fieldName);
    }

    /**
     * Encodes a monetary or rate value at the canonical scale of {@value #MONETARY_SCALE},
     * returning the field image as bytes ready to be written into a fixed-width record.
     *
     * @param  value     the value to encode; must not be {@code null}
     * @param  width     encoded byte width of the field, at least 1
     * @param  fieldName legacy field name used in diagnostics
     * @return a fresh array of exactly {@code width} US-ASCII bytes, never shared
     * @throws IllegalArgumentException if the value is {@code null} or does not fit the field
     */
    public static byte[] encodeMonetaryToBytes(BigDecimal value, int width, String fieldName) {
        return encodeToBytes(value, width, MONETARY_SCALE, fieldName);
    }

    /**
     * Brings a value to the canonical monetary scale of {@value #MONETARY_SCALE} by truncating
     * toward zero, and is the only sanctioned way for a caller to change the scale of a monetary
     * value.
     *
     * <p>This exists so that no service, batch processor, controller or record mapper ever needs
     * to call {@code setScale} itself. Every store into a {@code V99} receiving field in the
     * estate truncates, because no arithmetic statement anywhere specifies {@code ROUNDED}, so
     * this method is what makes an interest accrual or a running balance land on the same cent
     * the legacy program produced.</p>
     *
     * @param  value the value to bring to the monetary scale; must not be {@code null}
     * @return an equal-or-truncated value whose scale is exactly {@value #MONETARY_SCALE}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    public static BigDecimal toMonetaryScale(BigDecimal value) {
        return toScale(value, MONETARY_SCALE);
    }

    /**
     * Brings a value to an arbitrary non-negative scale by truncating toward zero.
     *
     * <p>Increasing the scale is exact and simply appends implied zeros; decreasing it discards
     * the surplus digits without rounding, which is the behaviour of a COBOL store without
     * {@code ROUNDED}. No overload accepts a {@link RoundingMode}, so a caller cannot select a
     * different policy.</p>
     *
     * @param  value the value to rescale; must not be {@code null}
     * @param  scale the target scale; must not be negative
     * @return a value whose scale is exactly {@code scale}
     * @throws IllegalArgumentException if {@code value} is {@code null} or {@code scale} is
     *                                 negative
     */
    public static BigDecimal toScale(BigDecimal value, int scale) {
        if (value == null) {
            throw new IllegalArgumentException("the value to rescale must not be null");
        }
        if (scale < 0) {
            throw new IllegalArgumentException("the target scale must not be negative but was "
                    + scale);
        }
        return value.setScale(scale, COBOL_TRUNCATION_MODE);
    }

    /**
     * Decodes {@code width} bytes starting at {@code offset}, having already established that the
     * slice lies within {@code bytes}.
     *
     * <p>Offsets quoted in diagnostics are relative to the start of the field rather than to the
     * start of the record, so a message reads the same whether the caller passed a standalone
     * image or a slice of a 350-byte record.</p>
     *
     * @param  bytes  buffer holding the field; read only, never retained or modified
     * @param  offset zero-based offset of the field within {@code bytes}
     * @param  width  encoded byte width of the field, at least 1
     * @param  scale  number of implied decimal digits
     * @param  label  already-formatted field label for diagnostics
     * @return the decoded value whose scale is exactly {@code scale}
     * @throws IllegalArgumentException if any byte of the field is not a valid zoned-decimal byte
     */
    private static BigDecimal decodeSlice(byte[] bytes, int offset, int width, int scale,
            String label) {
        int lastIndex = offset + width - 1;
        StringBuilder digits = new StringBuilder(width);
        for (int index = offset; index < lastIndex; index++) {
            byte current = bytes[index];
            if (current < '0' || current > '9') {
                throw new IllegalArgumentException("zoned decimal field " + label + ": expected an"
                        + " ASCII digit '0' through '9' at zero-based offset " + (index - offset)
                        + " of a " + width + "-byte image but found " + describeByte(current));
            }
            digits.append((char) current);
        }
        byte finalByte = bytes[lastIndex];
        int finalDigit = finalDigitOf(finalByte);
        if (finalDigit < 0) {
            throw new IllegalArgumentException("zoned decimal field " + label + ": expected an"
                    + " ASCII digit or an overpunched sign character at zero-based offset "
                    + (width - 1) + " of a " + width + "-byte image but found "
                    + describeByte(finalByte));
        }
        digits.append((char) ('0' + finalDigit));
        BigDecimal magnitude = new BigDecimal(new BigInteger(digits.toString()), scale);
        return isNegativeFinalByte(finalByte) ? magnitude.negate() : magnitude;
    }

    /**
     * Resolves the low-order digit carried by the final byte of a zoned field.
     *
     * <p>A plain ASCII digit denotes an unsigned, positive field. Otherwise the byte is looked up
     * in the positive and then the negative overpunch table, each of which is indexed by the
     * digit it encodes.</p>
     *
     * @param  finalByte the final byte of the field image
     * @return the digit 0 through 9, or a negative number if the byte is neither an ASCII digit
     *         nor one of the twenty overpunch characters
     */
    private static int finalDigitOf(byte finalByte) {
        if (finalByte >= '0' && finalByte <= '9') {
            return finalByte - '0';
        }
        int positiveIndex = POSITIVE_OVERPUNCH.indexOf(finalByte);
        if (positiveIndex >= 0) {
            return positiveIndex;
        }
        return NEGATIVE_OVERPUNCH.indexOf(finalByte);
    }

    /**
     * Reports whether the final byte of a zoned field carries a negative sign.
     *
     * <p>Only the ten negative overpunch characters do. An unsigned trailing digit and a positive
     * overpunch character are both positive, and the negative-zero character is reported as
     * negative here even though negating zero yields zero, which is what collapses the legacy
     * negative zero onto {@code BigDecimal} zero.</p>
     *
     * @param  finalByte the final byte of the field image
     * @return {@code true} when the byte is one of the ten negative overpunch characters
     */
    private static boolean isNegativeFinalByte(byte finalByte) {
        return NEGATIVE_OVERPUNCH.indexOf(finalByte) >= 0;
    }

    /**
     * Encodes a field image to US-ASCII bytes, rejecting anything US-ASCII cannot represent.
     *
     * <p>The check on the characters happens before the conversion, because the US-ASCII encoder
     * would otherwise substitute a replacement byte and a caller would never learn that its data
     * had been altered. Rejecting instead of transcoding is what keeps a fixed-width field
     * honest.</p>
     *
     * @param  image the field image; must not be {@code null}
     * @param  label already-formatted field label for diagnostics
     * @return the image encoded as US-ASCII bytes, one byte per character
     * @throws IllegalArgumentException if the image is {@code null} or holds a character outside
     *                                 the US-ASCII range
     */
    private static byte[] asciiBytes(String image, String label) {
        if (image == null) {
            throw new IllegalArgumentException("zoned decimal field " + label
                    + ": the field image must not be null");
        }
        for (int index = 0; index < image.length(); index++) {
            char current = image.charAt(index);
            if (current > MAX_ASCII_CODE_POINT) {
                throw new IllegalArgumentException("zoned decimal field " + label + ": character"
                        + " U+" + String.format("%04X", (int) current)
                        + " at zero-based character index " + index + " cannot be represented in"
                        + " US-ASCII, so the image is not a valid zoned decimal field");
            }
        }
        return image.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Validates the field geometry a caller asked for, independently of any data.
     *
     * @param  width the requested encoded byte width
     * @param  scale the requested number of implied decimal digits
     * @param  label already-formatted field label for diagnostics
     * @throws IllegalArgumentException if the width is below one, or the scale is negative or
     *                                 wider than the field
     */
    private static void requireWidthAndScale(int width, int scale, String label) {
        if (width < 1) {
            throw new IllegalArgumentException("zoned decimal field " + label + ": the field width"
                    + " must be at least one encoded byte but was " + width);
        }
        if (scale < 0 || scale > width) {
            throw new IllegalArgumentException("zoned decimal field " + label + ": the scale must"
                    + " be between 0 and the field width " + width + " inclusive but was "
                    + scale);
        }
    }

    /**
     * Builds the width-mismatch diagnostic, naming the expected and actual encoded byte counts.
     *
     * <p>A short or long fixed-width field has no legacy antecedent at all, because a VSAM or
     * QSAM record is fixed length by construction, so this is a defensive guard against a
     * Java-side slicing defect rather than the translation of a legacy status code.</p>
     *
     * @param  label    already-formatted field label for diagnostics
     * @param  expected encoded byte width the field must have
     * @param  actual   encoded byte length the caller actually supplied
     * @return the exception to throw
     */
    private static IllegalArgumentException widthMismatch(String label, int expected, int actual) {
        return new IllegalArgumentException("zoned decimal field " + label + ": expected exactly "
                + expected + " encoded byte(s) but received " + actual);
    }

    /**
     * Renders a byte for a diagnostic as its unsigned hexadecimal value, adding the character it
     * represents only when that character is printable ASCII, so that a control byte can never
     * corrupt a log line.
     *
     * @param  value the byte to describe
     * @return a printable description such as {@code 0x20 (' ')} or {@code 0x00}
     */
    private static String describeByte(byte value) {
        int unsigned = value & 0xFF;
        String hex = String.format("0x%02X", unsigned);
        if (unsigned >= ' ' && unsigned <= '~') {
            return hex + " ('" + (char) unsigned + "')";
        }
        return hex;
    }

    /**
     * Formats the field name for a diagnostic, degrading to a fixed substitute label when the
     * caller supplied none, so that building a message can never mask the defect being reported.
     *
     * @param  fieldName the legacy field name, possibly {@code null} or blank
     * @return the quoted field name, or the substitute label
     */
    private static String fieldLabel(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            return UNNAMED_FIELD;
        }
        return "'" + fieldName + "'";
    }
}
