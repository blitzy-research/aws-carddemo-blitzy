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
import java.util.Locale;

/**
 * The module's single point of decimal truth: the only place a zoned-decimal field image becomes a
 * {@link BigDecimal} and the only place a {@link BigDecimal} becomes a field image.
 *
 * <p>Every persisted monetary and rate value in this estate is stored as zoned decimal, so every one
 * of them passes through this class. One monetary value does not arrive as a field image at all - the
 * amount a user keys into a screen field arrives as a character lexeme - and
 * {@link #fromNumericLexeme(String)} is its entry point, so that even that path cannot acquire a
 * different scale or rounding policy.
 *
 * <p><strong>No other class may call {@code setScale}.</strong> Services, batch processors,
 * controllers and record mappers all route through here. That is what keeps the module's rounding
 * policy single-valued: a second call site is a second policy, and the two would diverge by a cent
 * on roughly half of all computations without either one looking wrong.
 *
 * <p><strong>Truncation, never rounding.</strong> No arithmetic statement anywhere in the estate
 * specifies rounding, and a COBOL store without a rounding clause truncates toward zero rather than
 * rounding. {@link #COBOL_TRUNCATION_MODE} is therefore the only mode this
 * class uses, and {@code HALF_EVEN} and {@code HALF_UP} are forbidden here and everywhere else in the
 * module. No binary floating-point type appears in this class: the mandated construct mapping
 * requires decimal precision to be identical with no floating-point substitution, and an IEEE-754
 * approximation of a cent is precisely what that forbids.
 *
 * <p><strong>Zoned decimal only; there is no packed-decimal path here, by design.</strong> Packed
 * decimal does not occur in any persisted field in this estate, so a decoder for it would be
 * unreachable code in a class whose whole purpose is to be the one authority.
 *
 * <p>A signed zoned field of scale two occupies two bytes more than its integer digit count. The
 * sign is overpunched into the final digit byte, so there is <em>no separate sign byte</em>, and no
 * byte is spent on the implied decimal point: the scale is implied by the field's declaration and is
 * never present in the image. {@link #POSITIVE_OVERPUNCH} and {@link #NEGATIVE_OVERPUNCH} declare the
 * convention exactly - each string is indexed by the low-order digit, so the character at index
 * <em>d</em> is the final byte of a value whose last digit is <em>d</em>. An <em>unsigned</em> field
 * simply carries digits in every byte including the last; decoding accepts that form and treats it as
 * positive, while encoding always emits the signed, overpunched form, because every signed field in
 * the estate is written that way - positive values included.
 *
 * <p>Three field shapes occur, at twelve, eleven and six encoded bytes, and each is published as a
 * named width constant below rather than left to a call site. Two further receiving fields exist only
 * in working storage, so they have no encoded image and no width constant here; what matters about
 * them is that their scale is two and that a store into them truncates, which is exactly what
 * {@link #toMonetaryScale(BigDecimal)} reproduces.
 *
 * <p><strong>Negative zero round-trips byte for byte.</strong> The legacy image distinguishes a
 * negative zero from a positive zero by its final byte, while {@link BigDecimal} has no negative zero
 * at all. That representation gap is bridged by {@link ZonedValue}, which carries the decoded amount
 * together with the one bit of sign information the amount itself cannot hold. A caller that must
 * preserve the distinction uses the signed entry points; a caller that need not may use the plain
 * ones, which discard the bit and re-emit an all-zero value as positive.
 *
 * <p><strong>Operand order is contractual, and this class is why.</strong> Because every store
 * truncates, arithmetic in this estate is not associative: re-ordering an expression moves the
 * truncation point and changes the cent. The expressions whose order is therefore fixed live in the
 * posting and interest services, and no arithmetic beyond scaling happens here.
 *
 * <p><strong>Failure contract.</strong> Every rejection raises {@link IllegalArgumentException} whose
 * message names the field, the expected geometry and what was actually supplied, and never the value
 * itself. Input is never silently padded, truncated, re-scaled or defaulted, and no method returns
 * {@code null}.
 *
 * <p><strong>Encoded bytes, never character counts.</strong> Every width this class validates or
 * emits is measured in encoded US-ASCII bytes, and a character outside printable US-ASCII is rejected
 * rather than transcoded.
 *
 * <p>Stateless and thread safe: final, private constructor, every member static and immutable, and
 * every method a pure function of its arguments.
 */
public final class ZonedDecimalCodec {

    /**
     * The only rounding mode this class uses, and the only one the module may use: a COBOL arithmetic
     * store without a rounding clause truncates toward zero, and no such clause appears in the estate.
     */
    public static final RoundingMode COBOL_TRUNCATION_MODE = RoundingMode.DOWN;

    /** Scale of every monetary and rate field in the estate. */
    public static final int MONETARY_SCALE = 2;

    /** Encoded width of the account monetary fields. */
    public static final int WIDTH_PIC_S9_10_V99 = 12;

    /** Encoded width of the transaction, daily-transaction and category-balance amounts. */
    public static final int WIDTH_PIC_S9_09_V99 = 11;

    /** Encoded width of the interest rate: the only field of this shape in the estate. */
    public static final int WIDTH_PIC_S9_04_V99 = 6;

    public static final int ACCOUNT_AMOUNT_WIDTH = WIDTH_PIC_S9_10_V99;

    public static final int TRANSACTION_AMOUNT_WIDTH = WIDTH_PIC_S9_09_V99;

    public static final int DAILY_TRANSACTION_AMOUNT_WIDTH = WIDTH_PIC_S9_09_V99;

    public static final int CATEGORY_BALANCE_WIDTH = WIDTH_PIC_S9_09_V99;

    public static final int INTEREST_RATE_WIDTH = WIDTH_PIC_S9_04_V99;

    /**
     * Final-byte characters for a positive value, indexed by low-order digit: the character at index
     * <em>d</em> terminates a positive value whose last digit is <em>d</em>.
     */
    private static final String POSITIVE_OVERPUNCH = "{ABCDEFGHI";

    /** Final-byte characters for a negative value, indexed by low-order digit. */
    private static final String NEGATIVE_OVERPUNCH = "}JKLMNOPQR";

    private static final int MAX_ASCII_CODE_POINT = 0x7F;

    /** Bounds of the printable US-ASCII range a field image and a field label may occupy. */
    private static final char FIRST_PRINTABLE_US_ASCII = 0x20;

    private static final char LAST_PRINTABLE_US_ASCII = 0x7E;

    private static final String UNNAMED_FIELD = "(unnamed field)";

    /** Not instantiable: a stateless codec exposing only static members. */
    private ZonedDecimalCodec() {
    }

    /**
     * Decodes a zoned-decimal field image at the given width and scale.
     *
     * <p>Prefer {@link #decodeSigned(String, int, int, String)} whenever the field may be written
     * back, because only that path preserves the sign of an all-zero image.
     *
     * @param  image     the field image, exactly {@code width} encoded bytes
     * @param  width     encoded byte width the field declares
     * @param  scale     number of implied decimal digits
     * @param  fieldName legacy field name, used only in diagnostics
     * @return the decoded value, whose scale is exactly {@code scale}
     * @throws IllegalArgumentException if the image is absent, is not exactly {@code width} encoded
     *                                  bytes, or is not a valid zoned-decimal image
     */
    public static BigDecimal decode(String image, int width, int scale, String fieldName) {
        return decodeSigned(image, width, scale, fieldName).value();
    }

    /**
     * Decodes a zoned-decimal field image, retaining the negative-zero bit the value cannot hold.
     *
     * <p>The path to use whenever the field may be written back byte for byte.
     *
     * @param  image     the field image, exactly {@code width} encoded bytes
     * @param  width     encoded byte width the field declares
     * @param  scale     number of implied decimal digits
     * @param  fieldName legacy field name, used only in diagnostics
     * @return the decoded value together with its negative-zero bit
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
     * Decodes a zoned-decimal field held inside a larger buffer, without copying the record first.
     *
     * @param  record    buffer holding the field; read only, never retained or modified
     * @param  offset    zero-based offset of the field within {@code record}
     * @param  width     encoded byte width the field declares
     * @param  scale     number of implied decimal digits
     * @param  fieldName legacy field name, used only in diagnostics
     * @return the decoded value, whose scale is exactly {@code scale}
     * @throws IllegalArgumentException if the buffer is absent, the range is not wholly inside it, or
     *                                  the slice is not a valid zoned-decimal image
     */
    public static BigDecimal decode(byte[] record, int offset, int width, int scale,
            String fieldName) {
        return decodeSigned(record, offset, width, scale, fieldName).value();
    }

    /**
     * Decodes a zoned-decimal field held inside a larger buffer, retaining the negative-zero bit.
     *
     * @param  record    buffer holding the field; read only, never retained or modified
     * @param  offset    zero-based offset of the field within {@code record}
     * @param  width     encoded byte width the field declares
     * @param  scale     number of implied decimal digits
     * @param  fieldName legacy field name, used only in diagnostics
     * @return the decoded value together with its negative-zero bit
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
     * Decodes a monetary field image at the canonical monetary scale.
     *
     * @param  image     the field image, exactly {@code width} encoded bytes
     * @param  width     encoded byte width the field declares
     * @param  fieldName legacy field name, used only in diagnostics
     * @return the decoded value, whose scale is exactly {@value #MONETARY_SCALE}
     * @throws IllegalArgumentException if the image is absent, mis-sized, or not a valid
     *                                  zoned-decimal image
     */
    public static BigDecimal decodeMonetary(String image, int width, String fieldName) {
        return decode(image, width, MONETARY_SCALE, fieldName);
    }

    /**
     * Decodes a monetary field held inside a larger buffer at the canonical monetary scale.
     *
     * @param  record    buffer holding the field; read only, never retained or modified
     * @param  offset    zero-based offset of the field within {@code record}
     * @param  width     encoded byte width the field declares
     * @param  fieldName legacy field name, used only in diagnostics
     * @return the decoded value, whose scale is exactly {@value #MONETARY_SCALE}
     * @throws IllegalArgumentException if the buffer is absent, the range is not wholly inside it, or
     *                                  the slice is not a valid zoned-decimal image
     */
    public static BigDecimal decodeMonetary(byte[] record, int offset, int width,
            String fieldName) {
        return decode(record, offset, width, MONETARY_SCALE, fieldName);
    }

    /**
     * Encodes a value as a zoned-decimal field image, returned as bytes.
     *
     * <p>A value needing more integer digits than the field provides is <strong>rejected</strong>
     * rather than narrowed. That is a deliberate divergence from COBOL, which would drop the
     * high-order digits silently: a silently narrowed amount is a wrong amount that looks right. The
     * rejection message names the field and the geometry and deliberately omits the value, so a
     * rejection cannot put account data into a diagnostic.
     *
     * @param  value     the value to encode
     * @param  width     encoded byte width the field declares
     * @param  scale     number of implied decimal digits
     * @param  fieldName legacy field name, used only in diagnostics
     * @return a fresh array of exactly {@code width} US-ASCII bytes, never shared
     * @throws IllegalArgumentException if the value is absent or needs more integer digits than the
     *                                  field provides
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
     * Encodes a value together with its negative-zero bit as a zoned-decimal field image, as bytes.
     *
     * <p>The path that reproduces a legacy image byte for byte, including a negatively-signed zero.
     *
     * @param  value     the value and its negative-zero bit
     * @param  width     encoded byte width the field declares
     * @param  scale     number of implied decimal digits
     * @param  fieldName legacy field name, used only in diagnostics
     * @return a fresh array of exactly {@code width} US-ASCII bytes, never shared
     * @throws IllegalArgumentException if the value is absent or does not fit the field
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
     * Encodes a value together with its negative-zero bit as a zoned-decimal field image.
     *
     * @param  value     the value and its negative-zero bit
     * @param  width     encoded byte width the field declares
     * @param  scale     number of implied decimal digits
     * @param  fieldName legacy field name, used only in diagnostics
     * @return the field image, exactly {@code width} US-ASCII bytes
     * @throws IllegalArgumentException if the value is absent or does not fit the field
     */
    public static String encodeSigned(ZonedValue value, int width, int scale, String fieldName) {
        return new String(encodeSignedToBytes(value, width, scale, fieldName),
                StandardCharsets.US_ASCII);
    }

    /**
     * Encodes a value as a zoned-decimal field image.
     *
     * @param  value     the value to encode
     * @param  width     encoded byte width the field declares
     * @param  scale     number of implied decimal digits
     * @param  fieldName legacy field name, used only in diagnostics
     * @return the field image, exactly {@code width} US-ASCII bytes
     * @throws IllegalArgumentException if the value is absent or does not fit the field
     */
    public static String encode(BigDecimal value, int width, int scale, String fieldName) {
        return new String(encodeToBytes(value, width, scale, fieldName), StandardCharsets.US_ASCII);
    }

    /**
     * Encodes a value as a monetary field image at the canonical monetary scale.
     *
     * @param  value     the value to encode
     * @param  width     encoded byte width the field declares
     * @param  fieldName legacy field name, used only in diagnostics
     * @return the field image, exactly {@code width} US-ASCII bytes
     * @throws IllegalArgumentException if the value is absent or does not fit the field
     */
    public static String encodeMonetary(BigDecimal value, int width, String fieldName) {
        return encode(value, width, MONETARY_SCALE, fieldName);
    }

    /**
     * Encodes a value as a monetary field image at the canonical monetary scale, returned as bytes.
     *
     * @param  value     the value to encode
     * @param  width     encoded byte width the field declares
     * @param  fieldName legacy field name, used only in diagnostics
     * @return a fresh array of exactly {@code width} US-ASCII bytes, never shared
     * @throws IllegalArgumentException if the value is absent or does not fit the field
     */
    public static byte[] encodeMonetaryToBytes(BigDecimal value, int width, String fieldName) {
        return encodeToBytes(value, width, MONETARY_SCALE, fieldName);
    }

    /**
     * Brings a value to the canonical monetary scale by truncating toward zero.
     *
     * <p>Reproduces a COBOL store into a two-decimal receiving field, including the working-storage
     * receiving fields that have no encoded image of their own.
     *
     * @param  value the value to scale
     * @return the value at scale {@value #MONETARY_SCALE}
     * @throws IllegalArgumentException if the value is absent
     */
    public static BigDecimal toMonetaryScale(BigDecimal value) {
        return toScale(value, MONETARY_SCALE);
    }

    /**
     * Brings a value to the given scale by truncating toward zero.
     *
     * @param  value the value to scale
     * @param  scale the scale to impose
     * @return the value at exactly {@code scale}
     * @throws IllegalArgumentException if the value is absent or the scale is negative
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
     * Converts a screen-keyed numeric lexeme to a monetary value.
     *
     * <p>The entry point for the one monetary value that does not arrive as a field image. The lexeme
     * <em>grammar</em> is a character question answered elsewhere, so this method converts and does
     * not re-litigate the format. <strong>Surplus fractional digits are truncated, not
     * rounded</strong>, matching the store into the receiving field. <strong>Magnitude is not judged
     * here</strong>: the screen field is wider than the receiving field's integer capacity, so a
     * lexeme may convert cleanly and still be too large to encode - the deliberate divergence being
     * that the overflow surfaces at encoding rather than at conversion. Callers run the three-state
     * edit in the order the legacy paragraph performs it.
     *
     * @param  lexeme the characters keyed into the screen field
     * @return the converted value at scale {@value #MONETARY_SCALE}
     * @throws IllegalArgumentException if the lexeme is absent or is not a valid numeric lexeme
     */
    public static BigDecimal fromNumericLexeme(String lexeme) {
        if (lexeme == null) {
            throw new IllegalArgumentException("the screen lexeme to convert must not be null");
        }
        if (!CobolStringUtils.isNumericLexeme(lexeme)) {
            // Neither the lexeme nor any digit of it appears in the message: a rejection must not leak
            // a monetary value into a log, which is the rule decision D-16 applies throughout.
            throw new IllegalArgumentException(
                    "the screen lexeme is not a well-formed FUNCTION NUMVAL-C argument");
        }
        return toMonetaryScale(new BigDecimal(CobolStringUtils.plainDecimalOfNumericLexeme(lexeme)));
    }

    /**
     * Decodes one field slice, the single implementation behind every decode entry point.
     *
     * @param  bytes  buffer holding the field; read only, never retained or modified
     * @param  offset zero-based offset of the field within {@code bytes}
     * @param  width  encoded byte width the field declares
     * @param  scale  number of implied decimal digits
     * @param  label  field label already prepared for diagnostics
     * @return the decoded value together with its negative-zero bit
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

    /** Maps a final byte to the low-order digit it carries, whether overpunched or plain. */
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

    /** Reports whether a final byte carries the negative overpunch. */
    private static boolean isNegativeFinalByte(byte finalByte) {
        return NEGATIVE_OVERPUNCH.indexOf(finalByte) >= 0;
    }

    /**
     * Renders a value as a field image, the single implementation behind every encode entry point.
     *
     * <p>The sign comes from the amount whenever the amount has one, and from {@code negativeZero}
     * only when every digit is zero - which is exactly the case an amount cannot express.
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
     * Converts a field image to US-ASCII bytes, rejecting any character outside printable US-ASCII.
     *
     * <p>Rejecting rather than transcoding is deliberate: a transcoder would substitute a replacement
     * byte and the caller would never learn that its data had been altered.
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
                        + " U+" + String.format(Locale.ROOT, "%04X", (int) current)
                        + " at zero-based character index " + index + " cannot be represented in"
                        + " US-ASCII, so the image is not a valid zoned decimal field");
            }
        }
        return image.getBytes(StandardCharsets.US_ASCII);
    }

    /** Rejects a width or scale that no field in this estate could declare. */
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

    /** Builds the diagnostic for a width disagreement, naming the geometry and never the value. */
    private static IllegalArgumentException widthMismatch(String label, int expected, int actual) {
        return new IllegalArgumentException("zoned decimal field " + label + ": expected exactly "
                + expected + " encoded byte(s) but received " + actual);
    }

    /** Renders one byte for a diagnostic, so an invalid image can be reported without echoing it. */
    private static String describeByte(byte value) {
        int unsigned = value & 0xFF;
        // Locale.ROOT is stated even though a hexadecimal conversion is not localised the way a
        // decimal one is, so that "every formatter in this module names its locale" is a property a
        // reader can confirm by grep rather than by knowing the Formatter specification. DL-042
        // requires this text to be printable US-ASCII, and a pinned locale is how that is guaranteed
        // rather than inferred. See docs/decision-log.md DL-118.
        String hex = String.format(Locale.ROOT, "0x%02X", unsigned);
        if (unsigned >= FIRST_PRINTABLE_US_ASCII && unsigned <= LAST_PRINTABLE_US_ASCII) {
            return hex + " ('" + (char) unsigned + "')";
        }
        return hex;
    }

    /**
     * Prepares a caller-supplied field name for use in a diagnostic.
     *
     * <p>The name is developer-supplied metadata rather than rejected data, but it still reaches a
     * message, so a name carrying a character outside printable US-ASCII is replaced by a substitute
     * label instead of raising. Every call site is already on a failure path: throwing here would
     * discard the original diagnostic and tell the caller its field name is malformed while never
     * telling it what was actually wrong with its data.
     *
     * @param  fieldName the caller-supplied name, which may be absent
     * @return the quoted name, or a substitute label; never carrying an unprintable character
     */
    private static String fieldLabel(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            return UNNAMED_FIELD;
        }
        for (int position = 0; position < fieldName.length(); position++) {
            char character = fieldName.charAt(position);
            if (character < FIRST_PRINTABLE_US_ASCII || character > LAST_PRINTABLE_US_ASCII) {
                return "(field name not printable US-ASCII: the character at zero-based position "
                        + position + " is code point " + (int) character + ")";
            }
        }
        return "'" + fieldName + "'";
    }

    /**
     * A decoded zoned-decimal value together with the one bit of sign information the value itself
     * cannot carry.
     *
     * <p>Exists because the legacy image distinguishes a negative zero from a positive zero by its
     * final byte while {@link BigDecimal} has no negative zero at all. The bit is meaningful only
     * when every digit is zero.
     *
     * @param value        the decoded value at the field's scale
     * @param negativeZero whether the image carried the negative overpunch on an all-zero value
     */
    public record ZonedValue(BigDecimal value, boolean negativeZero) {

        /** Rejects an absent value, and a negative-zero bit set on a value that is not zero. */
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
