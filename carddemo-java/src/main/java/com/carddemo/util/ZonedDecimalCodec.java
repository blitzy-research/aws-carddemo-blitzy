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
 * <p><strong>Why this class exists.</strong> Every persisted monetary and rate value in the CardDemo
 * estate is stored as zoned decimal under {@code USAGE DISPLAY}: one ASCII byte per digit, with the
 * sign overpunched into the final digit byte. This class is the only authority that converts that
 * on-disk representation to and from {@code BigDecimal}, and the only place in the module where a
 * scale is ever applied.</p>
 *
 * <p><strong>No other class may call {@code setScale}.</strong> Services, batch processors,
 * controllers and record mappers route every scaling operation through
 * {@link #toMonetaryScale(BigDecimal)} or {@link #toScale(BigDecimal, int)}. Centralising the policy
 * here is what makes it impossible for one caller to introduce a different rounding mode; a codec
 * that merely offered a rounding-mode parameter would have surrendered exactly the guarantee it
 * exists to provide, which is why no method on this class accepts one.</p>
 *
 * <p><strong>Truncation, never rounding.</strong> A search for the {@code ROUNDED} keyword across
 * every program and copybook in the estate returns zero occurrences: not one arithmetic statement
 * specifies rounding. A COBOL arithmetic store without {@code ROUNDED} truncates toward zero, so
 * {@link RoundingMode#DOWN} is mandatory and is published as {@link #COBOL_TRUNCATION_MODE};
 * decision D-02 records it. {@code HALF_EVEN} and {@code HALF_UP} are forbidden in this codec and in
 * every caller: {@code HALF_EVEN} - the conventional Java choice - would differ by one cent on
 * roughly half of all interest computations, a byte-parity failure completely invisible to a test
 * suite written under the same wrong assumption.</p>
 *
 * <p><strong>Zoned decimal only; there is no packed-decimal path here by design.</strong>
 * {@code COMP-3} occurs zero times in the copybook tree, and its nine declaration sites in the
 * program tree are all transient working-storage fields that are never part of a persisted layout
 * (decision D-01). A binary-coded-decimal decoder would be dead code, and none is provided. Do not
 * add one.</p>
 *
 * <p><strong>Field image geometry.</strong> {@code PIC S9(n)V99} occupies exactly {@code n + 2}
 * bytes. The sign is overpunched into the final digit byte, so there is <em>no separate sign
 * byte</em> and no byte is spent on the implied decimal point. The scale is implied by the picture
 * clause and is never present in the image.</p>
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
 * <p>All twenty characters occur in production-representative sample data: a census of the final
 * byte of the daily-transaction amount field, at byte offset 142 of the 350-byte record, across all
 * 300 records of {@code [app/data/ASCII/dailytran.txt]} finds every one of the ten positive forms
 * (250 records in total) and every one of the ten negative forms (50 records). The sign table is
 * therefore exercised end to end by real data rather than by synthetic fixtures.</p>
 *
 * <p>An <em>unsigned</em> zoned field simply carries {@code '0'} through {@code '9'} in every byte,
 * including the last. Decoding accepts that form and treats it as positive. Encoding always emits
 * the signed, overpunched form, because every signed field in the estate is written that way -
 * including positive values, which carry <code>&#123;</code> through {@code I} rather than a plain
 * digit.</p>
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
 * <p>The last two rows are working-storage receiving fields rather than record fields. They have no
 * encoded image, so this class publishes no width constant for them; what matters is that their
 * scale is 2 and that a store into them truncates, which is what
 * {@link #toMonetaryScale(BigDecimal)} reproduces.</p>
 *
 * <table>
 * <caption>Worked examples taken byte for byte from the shipped sample files</caption>
 * <tr><th scope="col">Source</th><th scope="col">Raw bytes</th>
 *     <th scope="col">Unsigned digits</th><th scope="col">Scale</th>
 *     <th scope="col">Decoded value</th></tr>
 * <tr><td>{@code [app/data/ASCII/acctdata.txt]} row 0 {@code ACCT-CURR-BAL}
 *         (offset 12, length 12)</td>
 *     <td><code>00000001940&#123;</code></td><td>{@code 000000019400}</td><td>2</td>
 *     <td>{@code 194.00}</td></tr>
 * <tr><td>{@code [app/data/ASCII/dailytran.txt]} row 0 {@code DALYTRAN-AMT}
 *         (offset 132, length 11)</td>
 *     <td>{@code 0000005047G}</td><td>{@code 00000050477}</td><td>2</td>
 *     <td>{@code 504.77}</td></tr>
 * <tr><td>{@code [app/data/ASCII/discgrp.txt]} row 0 {@code DIS-INT-RATE}
 *         (offset 16, length 6)</td>
 *     <td><code>00150&#123;</code></td><td>{@code 001500}</td><td>2</td>
 *     <td>{@code 15.00}</td></tr>
 * <tr><td>{@code [app/data/ASCII/discgrp.txt]} row 34 {@code DIS-INT-RATE}
 *         (the zero-rate disclosure group)</td>
 *     <td><code>00000&#123;</code></td><td>{@code 000000}</td><td>2</td>
 *     <td>{@code 0.00}</td></tr>
 * </table>
 *
 * <p>Each row is derived from the sample bytes by applying the overpunch convention recorded as
 * decision D-01 - the trailing byte carries both the low-order digit and the sign - and then placing
 * the decimal point at the field's scale. The second row is worth spelling out because the
 * arithmetic is easy to invert: {@code G} is the seventh positive overpunch, so {@code 0000005047G}
 * carries the digits {@code 00000050477}, which at scale 2 is {@code 504.77} and not {@code 500.47}.
 * Every amount field in the three sample files shipped as test fixtures - 250 account amounts, 300
 * daily-transaction amounts and 51 disclosure rates, 601 in all - decodes and re-encodes byte for
 * byte.</p>
 *
 * <p><strong>Negative zero round-trips byte for byte.</strong> The legacy byte image
 * distinguishes <code>&#125;</code> (negative zero) from <code>&#123;</code> (positive zero),
 * while {@code BigDecimal} has no negative zero at all. That representation gap is bridged by
 * {@link ZonedValue}, which carries the decoded amount together with the one bit of sign
 * information the amount itself cannot hold, so that every image the estate can present is
 * re-emitted exactly as it arrived. Byte parity is a gate requirement and is not weakened for
 * an all-zero field: reading <code>0000000000&#125;</code> through
 * {@link #decodeSigned(String, int, int, String)} and writing it back through
 * {@link #encodeSigned(ZonedValue, int, int, String)} reproduces
 * <code>0000000000&#125;</code>, not <code>0000000000&#123;</code>.</p>
 *
 * <p>Two decoding paths therefore exist, and the choice between them is a choice about what the
 * caller intends to do with the result:</p>
 * <ul>
 *   <li>{@link #decodeSigned(String, int, int, String)} and its byte-array twin return a
 *       {@link ZonedValue}. A record mapper that may write the field back must use this path,
 *       because it is the only one that survives a round trip unchanged.</li>
 *   <li>{@link #decode(String, int, int, String)} and its byte-array twin return the
 *       {@code BigDecimal} alone, for a caller that only computes with the value. Encoding a
 *       plain {@code BigDecimal} zero emits <code>&#123;</code>, because a value computed by
 *       arithmetic carries no image and no negative-zero bit to reproduce.</li>
 * </ul>
 *
 * <p>Read the negative-zero character narrowly. A trailing <code>&#125;</code> means "negative,
 * low-order digit zero", so it marks a negative zero <em>only</em> when every other digit is
 * zero as well. Six of the 300 daily-transaction amounts end in <code>&#125;</code> and none of
 * them is a negative zero: they are ordinary negative amounts whose cent digit happens to be
 * zero, and they re-encode byte for byte on either path. Treating every <code>&#125;</code> as
 * a negative zero would silently flip the sign of those records.</p>
 *
 * <p><strong>Operand order is contractual, and this class is why.</strong> Truncation makes
 * arithmetic non-associative, so an algebraically identical rearrangement can produce a different
 * stored value. Decision D-03 requires two computations to be reproduced operand for operand by
 * their owning services rather than by this codec. The monthly interest accrual
 * {@code [app/cbl/CBACT04C.cbl:L464]} multiplies the category balance by the disclosure rate
 * <em>first</em> and divides by 1200 <em>second</em>; dividing the rate by 1200 first is
 * algebraically identical in exact arithmetic but moves the truncation point and changes the result.
 * The overlimit basis {@code [app/cbl/CBTRN02C.cbl:L403]} evaluates strictly left to right - cycle
 * credit, minus cycle debit, plus the daily transaction amount - into a {@code PIC S9(09)V99} field,
 * and reordering it changes which transactions are rejected. Both belong to the interest-calculation
 * and transaction-posting services; this class only guarantees that the truncation they depend on is
 * correct.</p>
 *
 * <p><strong>Failure contract.</strong> Every rejection raises {@link IllegalArgumentException},
 * whose message names the field being processed together with either the expected and actual
 * <em>encoded byte</em> widths or the offending byte and its zero-based offset. Nothing is ever
 * silently padded, truncated, coerced or returned as {@code null}, and no partial value is ever
 * produced. Messages deliberately omit both the record image and the monetary value so that a
 * rejection cannot leak account data into a log (decision D-16), and a {@code null} image or value
 * is treated as a programming error on the same code path, so a caller catches exactly one exception
 * type.</p>
 *
 * <p><strong>Encoded bytes, never character counts.</strong> Every width this class validates or
 * emits is measured in bytes encoded as {@link StandardCharsets#US_ASCII}, never as
 * {@code String.length()}, so a multi-byte character can never silently break a fixed-width field. A
 * character outside the US-ASCII range is invalid data in a zoned field and is rejected rather than
 * transcoded. No conversion here relies on the platform default charset.</p>
 *
 * <p>This class is stateless, immutable and thread-safe. All methods are pure: they perform no I/O,
 * consult no clock, read no environment and hold no mutable state. Every figure quoted above is
 * factual layout evidence - widths, offsets, field lengths and row counts - and never a service
 * level, a buffer size or a performance target.</p>
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
     * the twenty overpunch characters, which supplies both the low-order digit and the sign.</p>
     *
     * <p>This entry point returns the amount alone, so a negative-zero image and a positive-zero
     * image both yield zero and are indistinguishable afterwards. Use
     * {@link #decodeSigned(String, int, int, String)} instead whenever the field may be written
     * back, because only that path preserves the sign of an all-zero field and so only that path
     * round-trips byte for byte.</p>
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
        return decodeSigned(image, width, scale, fieldName).value();
    }

    /**
     * Decodes a zoned-decimal field image held as a {@code String} into a {@link ZonedValue},
     * which carries the amount together with the negative-zero bit the amount cannot hold.
     *
     * <p>Validation, diagnostics and scale are identical to
     * {@link #decode(String, int, int, String)}; the only difference is that the sign of an
     * all-zero field survives, so re-encoding through
     * {@link #encodeSigned(ZonedValue, int, int, String)} reproduces the original image byte for
     * byte. Every record mapper that may write a field back must decode through this method.</p>
     *
     * <p>For example, the eleven-byte image <code>0000000000&#125;</code> at scale 2 decodes to
     * zero with the negative-zero bit set, and re-encodes to <code>0000000000&#125;</code>,
     * whereas <code>0000000000&#123;</code> decodes to zero with the bit clear and re-encodes to
     * <code>0000000000&#123;</code>.</p>
     *
     * @param  image     the field image; must not be {@code null}
     * @param  width     expected encoded byte width of the whole image, at least 1
     * @param  scale     number of implied decimal digits, between 0 and {@code width} inclusive;
     *                   the carried value has exactly this scale
     * @param  fieldName legacy field name used in diagnostics, such as {@code DALYTRAN-AMT}
     * @return the decoded value and its negative-zero bit, never {@code null}
     * @throws IllegalArgumentException on exactly the same conditions as
     *                                  {@link #decode(String, int, int, String)}
     */
    public static ZonedValue decodeSigned(String image, int width, int scale, String fieldName) {
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
        return decodeSigned(record, offset, width, scale, fieldName).value();
    }

    /**
     * Decodes a zoned-decimal field held as {@code width} bytes starting at {@code offset} of a
     * fixed-width record image into a {@link ZonedValue}.
     *
     * <p>This is the entry point a record mapper uses when it holds the raw record bytes and may
     * write the field back: it is the byte-array twin of
     * {@link #decodeSigned(String, int, int, String)} and preserves the sign of an all-zero field
     * so that the image round-trips byte for byte. The array is only read: it is neither retained
     * nor modified.</p>
     *
     * @param  record    the record image containing the field; must not be {@code null}
     * @param  offset    zero-based byte offset of the field within {@code record}
     * @param  width     encoded byte width of the field, at least 1
     * @param  scale     number of implied decimal digits, between 0 and {@code width} inclusive
     * @param  fieldName legacy field name used in diagnostics, such as {@code ACCT-CURR-BAL}
     * @return the decoded value and its negative-zero bit, never {@code null}
     * @throws IllegalArgumentException on exactly the same conditions as
     *                                  {@link #decode(byte[], int, int, int, String)}
     */
    public static ZonedValue decodeSigned(byte[] record, int offset, int width, int scale,
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
     * the overpunch character carrying both that digit and the sign.</p>
     *
     * <p>A plain {@code BigDecimal} zero emits the positive-zero character, because a value that
     * arrived by arithmetic rather than from an image carries no negative-zero bit to reproduce.
     * A caller re-emitting a field it decoded must therefore use
     * {@link #encodeSignedToBytes(ZonedValue, int, int, String)}, which preserves the sign of an
     * all-zero field.</p>
     *
     * <p>Encoding is the exact inverse of decoding for every one of the twenty signed images:
     * decoding {@code 0000005047G} at scale 2 and re-encoding it at width 11 reproduces
     * {@code 0000005047G} byte for byte.</p>
     *
     * <p>A value too large for the field is rejected rather than truncated on the left. That is a
     * deliberate divergence from COBOL, which would drop the high-order digits silently, and it is
     * recorded as decision D-06: a silently narrowed amount would corrupt the value while leaving the
     * record exactly the right width, which is the one failure a downstream width check can never
     * catch. The diagnostic reports the digit count required and the width available, and
     * deliberately omits the value itself so that a rejection cannot leak account data into a log.</p>
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
        return encodeImage(value, false, width, scale, label);
    }

    /**
     * Encodes a decoded field back into its zoned-decimal image of exactly {@code width} bytes,
     * preserving the negative-zero sign that a bare {@code BigDecimal} cannot carry.
     *
     * <p>This is the write half of the byte-parity guarantee. A field read with
     * {@link #decodeSigned(String, int, int, String)} or
     * {@link #decodeSigned(byte[], int, int, int, String)} and written back with this method
     * reproduces the original image byte for byte, including
     * <code>0000000000&#125;</code>, which the plain
     * {@link #encodeToBytes(BigDecimal, int, int, String)} path would emit as
     * <code>0000000000&#123;</code>.</p>
     *
     * <p>Truncation, padding, overflow rejection and diagnostics are identical to the plain path;
     * the negative-zero bit changes nothing except which overpunch table supplies the final byte
     * when every digit is zero.</p>
     *
     * @param  value     the decoded value and its negative-zero bit; must not be {@code null}
     * @param  width     encoded byte width of the field, at least 1
     * @param  scale     number of implied decimal digits, between 0 and {@code width} inclusive
     * @param  fieldName legacy field name used in diagnostics
     * @return a fresh array of exactly {@code width} US-ASCII bytes, never shared and never
     *         {@code null}
     * @throws IllegalArgumentException if the value is {@code null}, does not fit the field at
     *                                 the requested scale, or if {@code width} or {@code scale}
     *                                 is out of range
     */
    public static byte[] encodeSignedToBytes(ZonedValue value, int width, int scale,
            String fieldName) {
        String label = fieldLabel(fieldName);
        requireWidthAndScale(width, scale, label);
        if (value == null) {
            throw new IllegalArgumentException("zoned decimal field " + label
                    + ": the value to encode must not be null");
        }
        return encodeImage(value.value(), value.negativeZero(), width, scale, label);
    }

    /**
     * Encodes a decoded field back into its zoned-decimal image, returned as a {@code String}.
     *
     * <p>The image is produced from the byte form built by
     * {@link #encodeSignedToBytes(ZonedValue, int, int, String)} and decoded back with US-ASCII,
     * so its encoded byte width is exactly {@code width} by construction.</p>
     *
     * @param  value     the decoded value and its negative-zero bit; must not be {@code null}
     * @param  width     encoded byte width of the field, at least 1
     * @param  scale     number of implied decimal digits, between 0 and {@code width} inclusive
     * @param  fieldName legacy field name used in diagnostics
     * @return the field image, never {@code null}, of exactly {@code width} US-ASCII bytes
     * @throws IllegalArgumentException if the value is {@code null}, does not fit the field at
     *                                 the requested scale, or if {@code width} or {@code scale}
     *                                 is out of range
     */
    public static String encodeSigned(ZonedValue value, int width, int scale, String fieldName) {
        return new String(encodeSignedToBytes(value, width, scale, fieldName),
                StandardCharsets.US_ASCII);
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
     * @return the decoded value, whose scale is exactly {@code scale}, together with the
     *         negative-zero bit taken from the final byte
     * @throws IllegalArgumentException if any byte of the field is not a valid zoned-decimal byte
     */
    private static ZonedValue decodeSlice(byte[] bytes, int offset, int width, int scale,
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
        boolean negative = isNegativeFinalByte(finalByte);
        // Negating zero yields zero, so the sign of an all-zero field would be lost in the
        // BigDecimal alone. It is carried alongside instead, which is what lets the image be
        // re-emitted byte for byte.
        if (magnitude.signum() == 0) {
            return new ZonedValue(magnitude, negative);
        }
        return new ZonedValue(negative ? magnitude.negate() : magnitude, false);
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
     * overpunch character are both positive. The negative-zero character is reported as negative
     * here like any other member of the table; because negating zero yields zero, the caller
     * records that sign in the {@link ZonedValue} rather than in the amount.</p>
     *
     * @param  finalByte the final byte of the field image
     * @return {@code true} when the byte is one of the ten negative overpunch characters
     */
    private static boolean isNegativeFinalByte(byte finalByte) {
        return NEGATIVE_OVERPUNCH.indexOf(finalByte) >= 0;
    }

    /**
     * Builds the zoned-decimal image for an already-validated field, applying the truncating scale
     * policy and the overpunched sign.
     *
     * <p>This is the single place in the module where an overpunch character is chosen. The sign
     * comes from the amount whenever the amount has one, and from {@code negativeZero} only when
     * every digit is zero, which is exactly the case the amount cannot express.</p>
     *
     * @param  value        the value to encode, already known to be non-{@code null}
     * @param  negativeZero whether a zero-magnitude field carries the negative sign
     * @param  width        encoded byte width of the field, already validated
     * @param  scale        number of implied decimal digits, already validated
     * @param  label        already-formatted field label for diagnostics
     * @return a fresh array of exactly {@code width} US-ASCII bytes
     * @throws IllegalArgumentException if the value needs more digits than the field holds
     */
    private static byte[] encodeImage(BigDecimal value, boolean negativeZero, int width, int scale,
            String label) {
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
        boolean negative = truncated.signum() < 0
                || (truncated.signum() == 0 && negativeZero);
        String overpunch = negative ? NEGATIVE_OVERPUNCH : POSITIVE_OVERPUNCH;
        image[width - 1] = (byte) overpunch.charAt(lastDigit);
        return image;
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

    /**
     * A decoded zoned-decimal field: the amount, plus the one bit of sign information a
     * {@code BigDecimal} cannot hold.
     *
     * <p><strong>Why this exists.</strong> The legacy image distinguishes a negative zero from a
     * positive zero by its final byte, while {@code BigDecimal} has exactly one zero. Carrying the
     * distinction beside the amount, rather than discarding it, is what makes an all-zero field
     * round-trip byte for byte through
     * {@link ZonedDecimalCodec#decodeSigned(String, int, int, String)} and
     * {@link ZonedDecimalCodec#encodeSigned(ZonedValue, int, int, String)}.</p>
     *
     * <p>The bit is meaningful <em>only</em> for a zero amount, and the constructor enforces
     * that: a non-zero amount already carries its own sign, so pairing it with the marker would
     * describe two different signs at once. A field whose final byte is a negative overpunch but
     * whose digits are not all zero is an ordinary negative amount and arrives here with the
     * marker clear &mdash; six of the 300 shipped daily-transaction amounts are exactly that.</p>
     *
     * <p>This is a value carrier, not a diagnostic type: like the amount it wraps, it should not
     * be written to a log, because a monetary value in a log line is account data.</p>
     *
     * @param value        the decoded amount at the scale the caller requested, never
     *                     {@code null}
     * @param negativeZero {@code true} when the image carried the negative sign on an all-zero
     *                     field, which is the one case {@code value} cannot express
     */
    public record ZonedValue(BigDecimal value, boolean negativeZero) {

        /**
         * Validates the pairing, rejecting a null amount and a marker that contradicts the
         * amount's own sign.
         *
         * <p>Diagnostics name the defect without quoting the amount, matching the failure contract
         * of the enclosing codec, so that a rejection cannot leak account data into a log.</p>
         *
         * @throws IllegalArgumentException if {@code value} is {@code null}, or if
         *                                  {@code negativeZero} is set on a non-zero amount
         */
        public ZonedValue {
            if (value == null) {
                throw new IllegalArgumentException(
                        "zoned decimal value: the decoded amount must not be null");
            }
            if (negativeZero && value.signum() != 0) {
                throw new IllegalArgumentException("zoned decimal value: the negative-zero marker"
                        + " applies only to an amount of zero, but this amount has a non-zero"
                        + " magnitude and so already carries its own sign");
            }
        }
    }
}
