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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Verifies {@link JclCardImageBuilder}, which emits the seventeen fixed eighty-byte job-submission
 * card images that trigger the transaction-report batch job.
 *
 * <p>This is an <strong>external interface contract</strong> test, not an internal helper test. The
 * card images are transmitted verbatim, one card per message, and are then consumed by a job
 * scheduler, so every byte of every card is contractual. Seventeen cards of eighty bytes give a
 * concatenated image of exactly 1360 bytes, and a single wrong byte anywhere breaks the trigger. That
 * is why this class is the batch-trigger half of the interface-contract acceptance gate.
 *
 * <p><strong>The oracle is independent by construction.</strong> Every expected value is hand written
 * from the legacy card table and the declared component widths of the legacy record layout. No
 * expectation is produced by calling a constant or a method of the class under test, none is a
 * snapshot of the builder's own output, and no test compares the builder against itself. The helpers
 * below assemble expectations from a hand-written content literal plus a hand-written declared byte
 * width and assert that the literal really is that width, so a mistyped literal fails loudly instead
 * of quietly agreeing with a mistyped padding count. Where the published constants of the class under
 * test are themselves asserted, the hand-written literal is always the expected value and the
 * published constant always the actual, which pins the published contract rather than borrowing from
 * it.
 *
 * <p><strong>What this class proves.</strong> Exactly seventeen cards, in the order the legacy card
 * table declares them, each exactly eighty encoded bytes and each matching a hand-written image byte
 * for byte. Four ten-byte substitution slots carrying only two values - the start date on cards 11 and
 * 15, the end date on cards 12 and 15 - with the two occurrences of each value byte identical. The
 * terminating sentinel card present as the seventeenth and last, because the legacy submission loop
 * writes it rather than merely holding it. The one-thousand-entry bound of the legacy oversized
 * redefine honoured as an asserted constant while only seventeen cards are ever produced. The
 * concatenated image exactly 1360 bytes of plain concatenation, with no separator and no line
 * terminator. And slot values validated for encoded byte width only, with width failures rejected
 * rather than silently truncated, per decisions D-06 and D-08.
 *
 * <p>The eighty-byte card width is also what makes the downstream queue's fixed record size
 * satisfiable, but the queue attributes themselves - fixed unblocked eighty-byte records, append
 * disposition, output only, opened at initialisation, errors ignored - belong to the job submission
 * service and are deliberately absent from this test. There is no queue client, no messaging
 * dependency and no process invocation here.
 */
@DisplayName("JclCardImageBuilder :: seventeen eighty-byte job-submission card images")
class JclCardImageBuilderTest {

    // ORACLE DIMENSIONS
    // Hand written from the legacy record layout. These are the expected values that the
    // published constants of the class under test are checked against, never the other
    // way round.

    /** Declared width of one card, from the eighty-byte write buffer of the legacy program. */
    private static final int ORACLE_CARD_WIDTH = 80;

    /** Number of cards the legacy card table declares. */
    private static final int ORACLE_CARD_COUNT = 17;

    /** Declared width of each of the four date substitution slots. */
    private static final int ORACLE_DATE_SLOT_WIDTH = 10;

    /**
     * The one-thousand-entry bound of the legacy oversized redefine. It is asserted as a published
     * constant and is never used to allocate, pad or iterate anything in this test.
     */
    private static final int ORACLE_MAX_CARD_BOUND = 1000;

    /** Total width of the concatenated image, hand written rather than multiplied out. */
    private static final int ORACLE_TOTAL_IMAGE_WIDTH = 1360;

    /**
     * Width of the date-parameter record that occupies the leading bytes of card 15, being the
     * ten-byte start slot, the one-byte separator and the ten-byte end slot. The report line
     * formatter's date-parameter record builder must agree with these same bytes; that agreement is
     * documented rather than coded, so nothing from the report formatter is imported or called here.
     */
    private static final int ORACLE_DATE_PARAMETER_RECORD_WIDTH = 21;

    // ORACLE BYTE VALUES
    // Named numerically so that no character escape sequence for a line terminator or a
    // tab ever appears in this source file. The cards are fixed-width records, not text
    // lines, and nothing in the contract may contain a terminator.

    /** The ASCII space, the only padding byte any card may use. */
    private static final byte ORACLE_SPACE_BYTE = 0x20;

    /** The ASCII apostrophe that closes the character constants on cards 11 and 12. */
    private static final byte ORACLE_APOSTROPHE_BYTE = 0x27;

    /** The ASCII comma that continues card 1 onto card 2. */
    private static final byte ORACLE_COMMA_BYTE = 0x2C;

    /** The ASCII digit zero, which is the message-class value on card 1. */
    private static final byte ORACLE_DIGIT_ZERO_BYTE = 0x30;

    /** The ASCII capital letter O, which the message-class value must never be. */
    private static final byte ORACLE_LETTER_CAPITAL_O_BYTE = 0x4F;

    /** The zero byte, which must never be used as padding and must appear nowhere in the image. */
    private static final byte ORACLE_NULL_BYTE = 0x00;

    /** The ASCII horizontal tab, which must appear nowhere in the image. */
    private static final byte ORACLE_TAB_BYTE = 0x09;

    /** The ASCII line feed, which must appear nowhere in the image. */
    private static final byte ORACLE_LINE_FEED_BYTE = 0x0A;

    /** The ASCII carriage return, which must appear nowhere in the image. */
    private static final byte ORACLE_CARRIAGE_RETURN_BYTE = 0x0D;

    /**
     * The ASCII question mark, which US-ASCII substitutes for a character it cannot represent.
     *
     * <p>Named so the slot validation test can demonstrate that a lossy substitution keeps the
     * encoded width at ten and would therefore slip past a width-only guard undetected.
     */
    private static final byte ORACLE_REPLACEMENT_BYTE = 0x3F;

    /** Sentinel returned by the byte search helper when a byte is absent. */
    private static final int BYTE_NOT_FOUND = -1;

    // SLOT VALUES USED BY THE TESTS
    // Each is exactly ten characters. The legacy screen used a ten-character year, month
    // and day shape, and the builder validates that width and nothing else: it does not
    // parse a date, does not reformat one and does not check calendar validity, so no
    // date or time API is involved anywhere in this test.

    /** Primary start-date slot value, ten characters. */
    private static final String START_DATE = "2022-01-01";

    /** Primary end-date slot value, ten characters. */
    private static final String END_DATE = "2022-07-06";

    /** Second, deliberately different start-date slot value, ten characters. */
    private static final String OTHER_START_DATE = "2019-11-30";

    /** Second, deliberately different end-date slot value, ten characters. */
    private static final String OTHER_END_DATE = "2020-02-29";

    /** A slot value one byte short of the declared width. */
    private static final String NINE_BYTE_DATE = "2022-01-0";

    /** A slot value one byte over the declared width. */
    private static final String ELEVEN_BYTE_DATE = "2022-01-011";

    /** A zero-length slot value. */
    private static final String EMPTY_DATE = "";

    /**
     * A ten-character slot value whose final character is not representable as a single byte in the
     * encoding the card frame is defined in. Written as an escape so this source file stays pure
     * ASCII while still exercising the frame-integrity guard.
     */
    private static final String NON_SINGLE_BYTE_DATE = "2022-01-0\u00e9";

    /**
     * The hand-written date-slot shape, taken from the {@code WS-DATE-FORMAT} literal declared at
     * {@code [app/cbl/CORPT00C.cbl:L72]}. Four digits, a hyphen, two digits, a hyphen, two digits.
     */
    private static final String ORACLE_DATE_SLOT_FORMAT = "YYYY-MM-DD";

    /**
     * A ten-byte, single-byte US-ASCII slot value carrying an apostrophe where a date digit belongs.
     * Cards 11 and 12 wrap the slot inside a sort character constant closed by an apostrophe, so
     * this value closes that constant early and turns the card's remainder into further sort
     * specification. Both the width check and the encodability check admit it, which is exactly why
     * a shape check is required.
     */
    private static final String APOSTROPHE_INJECTION_SLOT = "2026-01-'X";

    /**
     * The one-based position of the apostrophe within {@link #APOSTROPHE_INJECTION_SLOT}. The shape
     * walk reports the first offending position it reaches, and the apostrophe precedes the trailing
     * letter, so this is the position the diagnostic must name.
     */
    private static final int APOSTROPHE_INJECTION_POSITION = 9;

    /** The one-based positions of the eight numeric characters of a date slot. */
    private static final int[] NUMERIC_SLOT_POSITIONS = {1, 2, 3, 4, 6, 7, 9, 10};

    /**
     * The one-based positions of the two hyphens of a date slot. They correspond to the two
     * {@code FILLER} constants of the legacy date group at {@code [app/cbl/CORPT00C.cbl:L62]} and
     * {@code [app/cbl/CORPT00C.cbl:L64]}.
     */
    private static final int[] SEPARATOR_SLOT_POSITIONS = {5, 8};

    /** The one-based position of the first hyphen, used where a single case suffices. */
    private static final int FIRST_SEPARATOR_SLOT_POSITION = 5;

    /** The one-based position of the last numeric character, used where a single case suffices. */
    private static final int LAST_NUMERIC_SLOT_POSITION = 10;

    /** The hand-written lowest digit a numeric slot position may hold. */
    private static final char ORACLE_LOWEST_DIGIT_CHARACTER = '0';

    /** The hand-written highest digit a numeric slot position may hold. */
    private static final char ORACLE_HIGHEST_DIGIT_CHARACTER = '9';

    /** The hand-written hyphen a separator slot position must hold. */
    private static final char ORACLE_HYPHEN_CHARACTER = '-';

    /** The highest character value representable as a single US-ASCII byte. */
    private static final char HIGHEST_US_ASCII_CHARACTER = 0x7F;

    /** The US-ASCII escape control character, swept as one of the record-corrupting bytes. */
    private static final char ESCAPE_CHARACTER = 0x1B;

    /** The US-ASCII delete character, swept as one of the record-corrupting bytes. */
    private static final char DELETE_CHARACTER = 0x7F;

    // ORACLE CARD CONTENT
    // Fourteen cards are fixed literals and three are composed. Each fixed literal is
    // paired with its hand-written content width so that the literal and the padding
    // count cannot both be wrong in a way that still totals eighty.

    /** Card 1, the job card. The message class is the digit zero and the trailing comma is content. */
    private static final String CARD_01_CONTENT = "//TRNRPT00 JOB 'TRAN REPORT',CLASS=A,MSGCLASS=0,";

    /** Hand-written content width of card 1. */
    private static final int CARD_01_CONTENT_WIDTH = 48;

    /** Card 2, the notify card continued from card 1. The system-user symbol is spelled correctly. */
    private static final String CARD_02_CONTENT = "// NOTIFY=&SYSUID";

    /** Hand-written content width of card 2. */
    private static final int CARD_02_CONTENT_WIDTH = 17;

    /** Cards 3, 5 and 7, the comment cards. All three carry the same literal. */
    private static final String COMMENT_CARD_CONTENT = "//*";

    /** Hand-written content width of the comment cards. */
    private static final int COMMENT_CARD_CONTENT_WIDTH = 3;

    /** Card 4, the procedure-library card. */
    private static final String CARD_04_CONTENT = "//JOBLIB JCLLIB ORDER=('AWS.M2.CARDDEMO.PROC')";

    /** Hand-written content width of card 4. */
    private static final int CARD_04_CONTENT_WIDTH = 46;

    /** Card 6, the step card invoking the cataloged report procedure. */
    private static final String CARD_06_CONTENT = "//STEP10 EXEC PROC=TRANREPT";

    /** Hand-written content width of card 6. */
    private static final int CARD_06_CONTENT_WIDTH = 27;

    /**
     * Card 8, the in-stream override of the sort step's symbol-names input.
     *
     * <p>Source anomaly, recorded because it touches this card's step qualifier and must not be
     * mistaken for a defect here: the report job member of the legacy estate declares the same sort
     * step name twice. Distinct step names are generated in the batch tier, which is where that
     * anomaly is resolved. This card's qualifier is emitted exactly as the legacy card table
     * declares it, so the card image itself is unaffected and nothing is corrected in this file.
     */
    private static final String CARD_08_CONTENT = "//STEP05R.SYMNAMES DD *";

    /** Hand-written content width of card 8. */
    private static final int CARD_08_CONTENT_WIDTH = 23;

    /** Card 9, the card-number sort symbol: sixteen bytes at offset 263, zoned decimal. */
    private static final String CARD_09_CONTENT = "TRAN-CARD-NUM,263,16,ZD";

    /** Hand-written content width of card 9. */
    private static final int CARD_09_CONTENT_WIDTH = 23;

    /** Card 10, the processing-date sort symbol: ten bytes at offset 305, character. */
    private static final String CARD_10_CONTENT = "TRAN-PROC-DT,305,10,CH";

    /** Hand-written content width of card 10. */
    private static final int CARD_10_CONTENT_WIDTH = 22;

    /** Cards 13 and 16, the in-stream data terminators. */
    private static final String TERMINATOR_CARD_CONTENT = "/*";

    /** Hand-written content width of the terminator cards. */
    private static final int TERMINATOR_CARD_CONTENT_WIDTH = 2;

    /** Card 14, the in-stream override of the report step's date-parameter input. */
    private static final String CARD_14_CONTENT = "//STEP10R.DATEPARM DD *";

    /** Hand-written content width of card 14. */
    private static final int CARD_14_CONTENT_WIDTH = 23;

    /** Card 17, the terminating sentinel, which the legacy submission loop transmits. */
    private static final String CARD_17_CONTENT = "/*EOF";

    /** Hand-written content width of card 17. */
    private static final int CARD_17_CONTENT_WIDTH = 5;

    // Composed card 11: eighteen-byte lead, ten-byte start slot, fifty-two-byte trailer
    // whose first byte is the closing apostrophe and whose remaining fifty-one bytes are
    // spaces. 18 + 10 + 52 = 80.

    /** The eighteen-byte leading literal of card 11. */
    private static final String CARD_11_LEAD = "PARM-START-DATE,C'";

    /** Hand-written width of the card 11 leading literal. */
    private static final int CARD_11_LEAD_WIDTH = 18;

    /** Hand-written width of the card 11 trailing field. */
    private static final int CARD_11_TRAILER_WIDTH = 52;

    /** Hand-written count of spaces that follow the closing apostrophe on card 11. */
    private static final int CARD_11_TRAILER_SPACE_COUNT = 51;

    // Composed card 12: sixteen-byte lead, ten-byte end slot, fifty-four-byte trailer
    // whose first byte is the closing apostrophe and whose remaining fifty-three bytes
    // are spaces. 16 + 10 + 54 = 80.

    /** The sixteen-byte leading literal of card 12. */
    private static final String CARD_12_LEAD = "PARM-END-DATE,C'";

    /** Hand-written width of the card 12 leading literal. */
    private static final int CARD_12_LEAD_WIDTH = 16;

    /** Hand-written width of the card 12 trailing field. */
    private static final int CARD_12_TRAILER_WIDTH = 54;

    /** Hand-written count of spaces that follow the closing apostrophe on card 12. */
    private static final int CARD_12_TRAILER_SPACE_COUNT = 53;

    // Composed card 15: ten-byte start slot, a separator declared as a bare single-byte
    // field, ten-byte end slot, then fifty-nine spaces. 10 + 1 + 10 + 59 = 80. Card 15
    // is the only card with no leading literal at all.

    /** Hand-written width of the card 15 separator, which is exactly one byte. */
    private static final int CARD_15_SEPARATOR_WIDTH = 1;

    /** Hand-written count of trailing spaces on card 15. */
    private static final int CARD_15_TRAILER_SPACE_COUNT = 59;

    // ORACLE OFFSETS
    // Zero-based, half-open byte ranges inside the eighty-byte frame, computed by hand
    // from the component widths above.

    /** Offset of the message-class value byte on card 1. */
    private static final int CARD_01_MESSAGE_CLASS_VALUE_OFFSET = 46;

    /** Offset of the continuation comma that ends the content of card 1. */
    private static final int CARD_01_CONTINUATION_COMMA_OFFSET = 47;

    /** Offset of the system-user symbol on card 2. */
    private static final int CARD_02_SYSTEM_USER_SYMBOL_OFFSET = 10;

    /** Offset at which the start-date slot begins on card 11. */
    private static final int CARD_11_SLOT_OFFSET = 18;

    /** Offset of the apostrophe that closes the card 11 character constant. */
    private static final int CARD_11_CLOSING_APOSTROPHE_OFFSET = 28;

    /** Offset at which the end-date slot begins on card 12. */
    private static final int CARD_12_SLOT_OFFSET = 16;

    /** Offset of the apostrophe that closes the card 12 character constant. */
    private static final int CARD_12_CLOSING_APOSTROPHE_OFFSET = 26;

    /** Offset at which the start-date slot begins on card 15. */
    private static final int CARD_15_START_SLOT_OFFSET = 0;

    /** Offset of the single-byte separator on card 15. */
    private static final int CARD_15_SEPARATOR_OFFSET = 10;

    /** Offset at which the end-date slot begins on card 15. */
    private static final int CARD_15_END_SLOT_OFFSET = 11;

    // ORACLE HELPERS
    // Test local only. None of these consults the class under test. Every width is taken
    // as an encoded byte count in the single-byte encoding the card frame is defined in,
    // never as a character count, so a multi-byte character could not slip past a width
    // check. Nothing here trims, strips, normalises, reflows or reformats anything:
    // trailing spaces are contractual content.

    /**
     * Measures a value in encoded bytes. Every width assertion in this class routes through here.
     *
     * @param value the value to measure
     * @return the encoded byte length of the value
     */
    private static int usAsciiLength(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Encodes a value to the bytes that would travel on the wire.
     *
     * @param value the value to encode
     * @return the encoded bytes of the value
     */
    private static byte[] usAsciiBytes(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Extracts a half-open byte range from an encoded value, so a slot can be compared as the bytes
     * it really occupies rather than as a substring of characters.
     *
     * @param value         the value to slice
     * @param fromInclusive the first byte offset to take
     * @param toExclusive   the byte offset to stop before
     * @return the extracted bytes
     */
    private static byte[] usAsciiSlice(final String value, final int fromInclusive,
            final int toExclusive) {
        return Arrays.copyOfRange(usAsciiBytes(value), fromInclusive, toExclusive);
    }

    /**
     * Finds the first offset at which a byte occurs, or the not-found sentinel.
     *
     * <p>Used instead of a character search so that the absence of a line feed, a carriage return
     * or a tab can be asserted numerically, without any escape sequence appearing in this source.
     *
     * @param bytes  the bytes to search
     * @param wanted the byte to look for
     * @return the first offset of the byte, or {@link #BYTE_NOT_FOUND} if it is absent
     */
    private static int indexOfByte(final byte[] bytes, final byte wanted) {
        for (int offset = 0; offset < bytes.length; offset++) {
            if (bytes[offset] == wanted) {
                return offset;
            }
        }
        return BYTE_NOT_FOUND;
    }

    /**
     * Produces a run of ASCII spaces of a hand-written length.
     *
     * @param count how many spaces, taken from a hand-written padding count
     * @return the run of spaces
     */
    private static String oracleSpaces(final int count) {
        return " ".repeat(count);
    }

    /**
     * Reads one card by its one-based number, so a test reads in the same numbering the legacy card
     * table uses and an off-by-one cannot hide behind a zero-based index.
     *
     * @param cards            the produced card sequence
     * @param oneBasedCardNumber the card number between one and seventeen
     * @return the requested card image
     */
    private static String card(final List<String> cards, final int oneBasedCardNumber) {
        return cards.get(oneBasedCardNumber - 1);
    }

    /**
     * Produces a ten-byte slot value that is the valid start date with a single character replaced
     * at one one-based position, so a shape assertion isolates exactly one position at a time.
     *
     * <p>The replacement never changes the length, which is what keeps the width check and the
     * encodability check out of the way and leaves the shape check as the only property under test.
     *
     * @param oneBasedPosition the slot position to replace, between one and ten
     * @param replacement      the character to place there
     * @return the ten-character slot value carrying {@code replacement} at that position
     */
    private static String slotWithCharacterAt(final int oneBasedPosition, final char replacement) {
        final char[] slot = START_DATE.toCharArray();
        slot[oneBasedPosition - 1] = replacement;
        final String candidate = new String(slot);

        assertThat(candidate.length()).as("character count of a single-position slot substitution")
                .isEqualTo(ORACLE_DATE_SLOT_WIDTH);
        return candidate;
    }

    /**
     * Assembles the expected image of a fixed-literal card from a hand-written content literal and
     * its hand-written declared content width.
     *
     * <p>The declared width is passed in rather than measured so that the literal is checked against
     * an independently written number. If the literal were mistyped, its measured width would no
     * longer match the declared width and this helper would fail, instead of silently padding to
     * eighty bytes around the wrong content.
     *
     * @param content              the hand-written card content, before padding
     * @param declaredContentWidth the hand-written width that content is expected to occupy
     * @return the expected eighty-byte card image
     */
    private static String oracleFixedCard(final String content, final int declaredContentWidth) {
        assertThat(usAsciiLength(content))
                .as("hand-written oracle content width for card content: " + content)
                .isEqualTo(declaredContentWidth);

        final String cardImage = content + oracleSpaces(ORACLE_CARD_WIDTH - declaredContentWidth);

        assertThat(usAsciiLength(cardImage))
                .as("hand-written oracle card width for card content: " + content)
                .isEqualTo(ORACLE_CARD_WIDTH);
        return cardImage;
    }

    /**
     * Assembles the expected image of card 11 from its declared component widths: an eighteen-byte
     * lead, the ten-byte start-date slot, then a fifty-two-byte trailer whose first byte is the
     * closing apostrophe and whose remaining fifty-one bytes are spaces.
     *
     * @param startDate the ten-byte start-date slot value
     * @return the expected eighty-byte card 11 image
     */
    private static String oracleStartDateSortSymbolCard(final String startDate) {
        assertThat(usAsciiLength(CARD_11_LEAD)).as("oracle card 11 lead width")
                .isEqualTo(CARD_11_LEAD_WIDTH);
        assertThat(usAsciiLength(startDate)).as("oracle card 11 slot width")
                .isEqualTo(ORACLE_DATE_SLOT_WIDTH);

        final String trailer = "'" + oracleSpaces(CARD_11_TRAILER_SPACE_COUNT);
        assertThat(usAsciiLength(trailer)).as("oracle card 11 trailer width")
                .isEqualTo(CARD_11_TRAILER_WIDTH);

        final String cardImage = CARD_11_LEAD + startDate + trailer;
        assertThat(usAsciiLength(cardImage)).as("oracle card 11 width")
                .isEqualTo(ORACLE_CARD_WIDTH);
        return cardImage;
    }

    /**
     * Assembles the expected image of card 12 from its declared component widths: a sixteen-byte
     * lead, the ten-byte end-date slot, then a fifty-four-byte trailer whose first byte is the
     * closing apostrophe and whose remaining fifty-three bytes are spaces.
     *
     * @param endDate the ten-byte end-date slot value
     * @return the expected eighty-byte card 12 image
     */
    private static String oracleEndDateSortSymbolCard(final String endDate) {
        assertThat(usAsciiLength(CARD_12_LEAD)).as("oracle card 12 lead width")
                .isEqualTo(CARD_12_LEAD_WIDTH);
        assertThat(usAsciiLength(endDate)).as("oracle card 12 slot width")
                .isEqualTo(ORACLE_DATE_SLOT_WIDTH);

        final String trailer = "'" + oracleSpaces(CARD_12_TRAILER_SPACE_COUNT);
        assertThat(usAsciiLength(trailer)).as("oracle card 12 trailer width")
                .isEqualTo(CARD_12_TRAILER_WIDTH);

        final String cardImage = CARD_12_LEAD + endDate + trailer;
        assertThat(usAsciiLength(cardImage)).as("oracle card 12 width")
                .isEqualTo(ORACLE_CARD_WIDTH);
        return cardImage;
    }

    /**
     * Assembles the expected image of card 15 from its declared component widths: the ten-byte
     * start-date slot, a separator of exactly one space, the ten-byte end-date slot, then
     * fifty-nine spaces.
     *
     * @param startDate the ten-byte start-date slot value
     * @param endDate   the ten-byte end-date slot value
     * @return the expected eighty-byte card 15 image
     */
    private static String oracleDateParameterCard(final String startDate, final String endDate) {
        assertThat(usAsciiLength(startDate)).as("oracle card 15 start slot width")
                .isEqualTo(ORACLE_DATE_SLOT_WIDTH);
        assertThat(usAsciiLength(endDate)).as("oracle card 15 end slot width")
                .isEqualTo(ORACLE_DATE_SLOT_WIDTH);

        final String cardImage = startDate + oracleSpaces(CARD_15_SEPARATOR_WIDTH) + endDate
                + oracleSpaces(CARD_15_TRAILER_SPACE_COUNT);

        assertThat(usAsciiLength(cardImage)).as("oracle card 15 width")
                .isEqualTo(ORACLE_CARD_WIDTH);
        return cardImage;
    }

    /**
     * Assembles the complete expected seventeen-card sequence, in the order the legacy card table
     * declares it, entirely from hand-written literals and hand-written widths.
     *
     * @param startDate the ten-byte start-date slot value
     * @param endDate   the ten-byte end-date slot value
     * @return the expected sequence of seventeen eighty-byte card images
     */
    private static List<String> oracleCards(final String startDate, final String endDate) {
        return List.of(
                oracleFixedCard(CARD_01_CONTENT, CARD_01_CONTENT_WIDTH),
                oracleFixedCard(CARD_02_CONTENT, CARD_02_CONTENT_WIDTH),
                oracleFixedCard(COMMENT_CARD_CONTENT, COMMENT_CARD_CONTENT_WIDTH),
                oracleFixedCard(CARD_04_CONTENT, CARD_04_CONTENT_WIDTH),
                oracleFixedCard(COMMENT_CARD_CONTENT, COMMENT_CARD_CONTENT_WIDTH),
                oracleFixedCard(CARD_06_CONTENT, CARD_06_CONTENT_WIDTH),
                oracleFixedCard(COMMENT_CARD_CONTENT, COMMENT_CARD_CONTENT_WIDTH),
                oracleFixedCard(CARD_08_CONTENT, CARD_08_CONTENT_WIDTH),
                oracleFixedCard(CARD_09_CONTENT, CARD_09_CONTENT_WIDTH),
                oracleFixedCard(CARD_10_CONTENT, CARD_10_CONTENT_WIDTH),
                oracleStartDateSortSymbolCard(startDate),
                oracleEndDateSortSymbolCard(endDate),
                oracleFixedCard(TERMINATOR_CARD_CONTENT, TERMINATOR_CARD_CONTENT_WIDTH),
                oracleFixedCard(CARD_14_CONTENT, CARD_14_CONTENT_WIDTH),
                oracleDateParameterCard(startDate, endDate),
                oracleFixedCard(TERMINATOR_CARD_CONTENT, TERMINATOR_CARD_CONTENT_WIDTH),
                oracleFixedCard(CARD_17_CONTENT, CARD_17_CONTENT_WIDTH));
    }

    /**
     * Joins an expected card sequence into the expected concatenated image with no separator, no
     * line terminator and no trailing newline, because the cards are fixed-width records rather
     * than text lines.
     *
     * @param cards the expected card sequence
     * @return the expected concatenated image
     */
    private static String oracleConcatenatedImage(final List<String> cards) {
        final StringBuilder joined = new StringBuilder();
        for (final String cardImage : cards) {
            joined.append(cardImage);
        }
        return joined.toString();
    }

    // TESTS

    @Nested
    @DisplayName("the seventeen-card sequence")
    class CardSequenceContract {

        @Test
        @DisplayName("returns exactly seventeen cards")
        void returnsExactlySeventeenCards() {
            final List<String> cards = JclCardImageBuilder.build(START_DATE, END_DATE);

            assertThat(cards).as("produced card count").hasSize(ORACLE_CARD_COUNT);
        }

        @Test
        @DisplayName("returns the cards in the order the legacy card table declares them")
        void returnsTheCardsInSourceOrder() {
            final List<String> cards = JclCardImageBuilder.build(START_DATE, END_DATE);

            assertThat(cards).as("produced card sequence, in source order")
                    .containsExactlyElementsOf(oracleCards(START_DATE, END_DATE));
        }

        @Test
        @DisplayName("emits every card at exactly eighty encoded bytes")
        void everyCardIsExactlyEightyEncodedBytes() {
            final List<String> cards = JclCardImageBuilder.build(START_DATE, END_DATE);

            for (int cardNumber = 1; cardNumber <= ORACLE_CARD_COUNT; cardNumber++) {
                assertThat(usAsciiLength(card(cards, cardNumber)))
                        .as("encoded byte width of card " + cardNumber)
                        .isEqualTo(ORACLE_CARD_WIDTH);
            }
        }

        @Test
        @DisplayName("each of the seventeen cards matches its hand-written image byte for byte")
        void eachCardMatchesItsHandWrittenImage() {
            final List<String> cards = JclCardImageBuilder.build(START_DATE, END_DATE);

            assertThat(usAsciiBytes(card(cards, 1))).as("card 1, the job card")
                    .isEqualTo(usAsciiBytes(oracleFixedCard(CARD_01_CONTENT, CARD_01_CONTENT_WIDTH)));
            assertThat(usAsciiBytes(card(cards, 2))).as("card 2, the notify continuation")
                    .isEqualTo(usAsciiBytes(oracleFixedCard(CARD_02_CONTENT, CARD_02_CONTENT_WIDTH)));
            assertThat(usAsciiBytes(card(cards, 3))).as("card 3, a comment card")
                    .isEqualTo(usAsciiBytes(
                            oracleFixedCard(COMMENT_CARD_CONTENT, COMMENT_CARD_CONTENT_WIDTH)));
            assertThat(usAsciiBytes(card(cards, 4))).as("card 4, the procedure library")
                    .isEqualTo(usAsciiBytes(oracleFixedCard(CARD_04_CONTENT, CARD_04_CONTENT_WIDTH)));
            assertThat(usAsciiBytes(card(cards, 5))).as("card 5, a comment card")
                    .isEqualTo(usAsciiBytes(
                            oracleFixedCard(COMMENT_CARD_CONTENT, COMMENT_CARD_CONTENT_WIDTH)));
            assertThat(usAsciiBytes(card(cards, 6))).as("card 6, the cataloged procedure step")
                    .isEqualTo(usAsciiBytes(oracleFixedCard(CARD_06_CONTENT, CARD_06_CONTENT_WIDTH)));
            assertThat(usAsciiBytes(card(cards, 7))).as("card 7, a comment card")
                    .isEqualTo(usAsciiBytes(
                            oracleFixedCard(COMMENT_CARD_CONTENT, COMMENT_CARD_CONTENT_WIDTH)));
            assertThat(usAsciiBytes(card(cards, 8))).as("card 8, the symbol-names override")
                    .isEqualTo(usAsciiBytes(oracleFixedCard(CARD_08_CONTENT, CARD_08_CONTENT_WIDTH)));
            assertThat(usAsciiBytes(card(cards, 9))).as("card 9, the card-number sort symbol")
                    .isEqualTo(usAsciiBytes(oracleFixedCard(CARD_09_CONTENT, CARD_09_CONTENT_WIDTH)));
            assertThat(usAsciiBytes(card(cards, 10))).as("card 10, the processing-date sort symbol")
                    .isEqualTo(usAsciiBytes(oracleFixedCard(CARD_10_CONTENT, CARD_10_CONTENT_WIDTH)));
            assertThat(usAsciiBytes(card(cards, 11))).as("card 11, the start-date sort symbol")
                    .isEqualTo(usAsciiBytes(oracleStartDateSortSymbolCard(START_DATE)));
            assertThat(usAsciiBytes(card(cards, 12))).as("card 12, the end-date sort symbol")
                    .isEqualTo(usAsciiBytes(oracleEndDateSortSymbolCard(END_DATE)));
            assertThat(usAsciiBytes(card(cards, 13))).as("card 13, an in-stream data terminator")
                    .isEqualTo(usAsciiBytes(
                            oracleFixedCard(TERMINATOR_CARD_CONTENT, TERMINATOR_CARD_CONTENT_WIDTH)));
            assertThat(usAsciiBytes(card(cards, 14))).as("card 14, the date-parameter override")
                    .isEqualTo(usAsciiBytes(oracleFixedCard(CARD_14_CONTENT, CARD_14_CONTENT_WIDTH)));
            assertThat(usAsciiBytes(card(cards, 15))).as("card 15, the date-parameter record")
                    .isEqualTo(usAsciiBytes(oracleDateParameterCard(START_DATE, END_DATE)));
            assertThat(usAsciiBytes(card(cards, 16))).as("card 16, an in-stream data terminator")
                    .isEqualTo(usAsciiBytes(
                            oracleFixedCard(TERMINATOR_CARD_CONTENT, TERMINATOR_CARD_CONTENT_WIDTH)));
            assertThat(usAsciiBytes(card(cards, 17))).as("card 17, the terminating sentinel")
                    .isEqualTo(usAsciiBytes(oracleFixedCard(CARD_17_CONTENT, CARD_17_CONTENT_WIDTH)));
        }

        @Test
        @DisplayName("pads on the right with the ASCII space and never with a zero byte, a tab or a terminator")
        void padsOnTheRightWithTheAsciiSpaceOnly() {
            final List<String> cards = JclCardImageBuilder.build(START_DATE, END_DATE);

            for (int cardNumber = 1; cardNumber <= ORACLE_CARD_COUNT; cardNumber++) {
                final byte[] cardBytes = usAsciiBytes(card(cards, cardNumber));

                assertThat(cardBytes[ORACLE_CARD_WIDTH - 1])
                        .as("final padding byte of card " + cardNumber)
                        .isEqualTo(ORACLE_SPACE_BYTE);
                assertThat(indexOfByte(cardBytes, ORACLE_NULL_BYTE))
                        .as("zero byte in card " + cardNumber).isEqualTo(BYTE_NOT_FOUND);
                assertThat(indexOfByte(cardBytes, ORACLE_TAB_BYTE))
                        .as("tab byte in card " + cardNumber).isEqualTo(BYTE_NOT_FOUND);
                assertThat(indexOfByte(cardBytes, ORACLE_LINE_FEED_BYTE))
                        .as("line feed byte in card " + cardNumber).isEqualTo(BYTE_NOT_FOUND);
                assertThat(indexOfByte(cardBytes, ORACLE_CARRIAGE_RETURN_BYTE))
                        .as("carriage return byte in card " + cardNumber).isEqualTo(BYTE_NOT_FOUND);
            }
        }
    }

    @Nested
    @DisplayName("the published contract constants")
    class PublishedConstantsContract {

        @Test
        @DisplayName("publishes a card width of eighty")
        void publishesACardWidthOfEighty() {
            assertThat(JclCardImageBuilder.CARD_IMAGE_WIDTH).as("published card image width")
                    .isEqualTo(ORACLE_CARD_WIDTH);
        }

        @Test
        @DisplayName("publishes a card count of seventeen")
        void publishesACardCountOfSeventeen() {
            assertThat(JclCardImageBuilder.CARD_COUNT).as("published card count")
                    .isEqualTo(ORACLE_CARD_COUNT);
        }

        @Test
        @DisplayName("publishes a date slot width of ten")
        void publishesADateSlotWidthOfTen() {
            assertThat(JclCardImageBuilder.DATE_SLOT_WIDTH).as("published date slot width")
                    .isEqualTo(ORACLE_DATE_SLOT_WIDTH);
        }

        @Test
        @DisplayName("publishes a total image width of 1360, which is seventeen cards of eighty bytes")
        void publishesATotalImageWidthOfOneThousandThreeHundredAndSixty() {
            assertThat(JclCardImageBuilder.TOTAL_IMAGE_WIDTH).as("published total image width")
                    .isEqualTo(ORACLE_TOTAL_IMAGE_WIDTH);
            assertThat(ORACLE_CARD_COUNT * ORACLE_CARD_WIDTH)
                    .as("hand-written arithmetic behind the total image width")
                    .isEqualTo(ORACLE_TOTAL_IMAGE_WIDTH);
        }

        @Test
        @DisplayName("publishes the two legacy sort-symbol names used to identify a rejected slot")
        void publishesTheTwoSlotNames() {
            assertThat(JclCardImageBuilder.SLOT_PARM_START_DATE).as("published start slot name")
                    .isEqualTo("PARM-START-DATE");
            assertThat(JclCardImageBuilder.SLOT_PARM_END_DATE).as("published end slot name")
                    .isEqualTo("PARM-END-DATE");
        }

        @Test
        @DisplayName("publishes every card literal exactly as the legacy card table declares it")
        void publishesEveryCardLiteral() {
            assertThat(JclCardImageBuilder.JOB_CARD).as("published job card literal")
                    .isEqualTo(CARD_01_CONTENT);
            assertThat(JclCardImageBuilder.NOTIFY_CARD).as("published notify card literal")
                    .isEqualTo(CARD_02_CONTENT);
            assertThat(JclCardImageBuilder.COMMENT_CARD).as("published comment card literal")
                    .isEqualTo(COMMENT_CARD_CONTENT);
            assertThat(JclCardImageBuilder.JOBLIB_CARD).as("published procedure library literal")
                    .isEqualTo(CARD_04_CONTENT);
            assertThat(JclCardImageBuilder.EXEC_PROC_CARD).as("published procedure step literal")
                    .isEqualTo(CARD_06_CONTENT);
            assertThat(JclCardImageBuilder.SYMNAMES_DD_CARD)
                    .as("published symbol-names override literal").isEqualTo(CARD_08_CONTENT);
            assertThat(JclCardImageBuilder.SORT_SYMBOL_CARD_NUM_CARD)
                    .as("published card-number sort symbol literal").isEqualTo(CARD_09_CONTENT);
            assertThat(JclCardImageBuilder.SORT_SYMBOL_PROC_DT_CARD)
                    .as("published processing-date sort symbol literal").isEqualTo(CARD_10_CONTENT);
            assertThat(JclCardImageBuilder.SORT_SYMBOL_START_DATE_LEAD)
                    .as("published card 11 leading literal").isEqualTo(CARD_11_LEAD);
            assertThat(JclCardImageBuilder.SORT_SYMBOL_END_DATE_LEAD)
                    .as("published card 12 leading literal").isEqualTo(CARD_12_LEAD);
            assertThat(JclCardImageBuilder.IN_STREAM_TERMINATOR_CARD)
                    .as("published in-stream terminator literal").isEqualTo(TERMINATOR_CARD_CONTENT);
            assertThat(JclCardImageBuilder.DATEPARM_DD_CARD)
                    .as("published date-parameter override literal").isEqualTo(CARD_14_CONTENT);
            assertThat(JclCardImageBuilder.EOF_SENTINEL_CARD).as("published sentinel literal")
                    .isEqualTo(CARD_17_CONTENT);
        }
    }

    @Nested
    @DisplayName("the fourteen fixed literal cards")
    class FixedLiteralCardDetails {

        @Test
        @DisplayName("card 1 carries the digit zero as its message class, not the capital letter")
        void cardOneMessageClassIsTheDigitZero() {
            final List<String> cards = JclCardImageBuilder.build(START_DATE, END_DATE);
            final byte[] cardOneBytes = usAsciiBytes(card(cards, 1));

            assertThat(cardOneBytes[CARD_01_MESSAGE_CLASS_VALUE_OFFSET])
                    .as("message class value byte of card 1").isEqualTo(ORACLE_DIGIT_ZERO_BYTE);
            assertThat(cardOneBytes[CARD_01_MESSAGE_CLASS_VALUE_OFFSET])
                    .as("message class value byte of card 1 is not the capital letter")
                    .isNotEqualTo(ORACLE_LETTER_CAPITAL_O_BYTE);
        }

        @Test
        @DisplayName("card 1 retains its trailing comma, which is the continuation marker onto card 2")
        void cardOneRetainsTheTrailingContinuationComma() {
            final List<String> cards = JclCardImageBuilder.build(START_DATE, END_DATE);
            final String cardOne = card(cards, 1);
            final byte[] cardOneBytes = usAsciiBytes(cardOne);

            assertThat(cardOneBytes[CARD_01_CONTINUATION_COMMA_OFFSET])
                    .as("continuation comma byte of card 1").isEqualTo(ORACLE_COMMA_BYTE);
            assertThat(cardOneBytes[CARD_01_CONTINUATION_COMMA_OFFSET + 1])
                    .as("byte immediately after the continuation comma, where padding starts")
                    .isEqualTo(ORACLE_SPACE_BYTE);
            assertThat(usAsciiSlice(cardOne, 0, CARD_01_CONTENT_WIDTH))
                    .as("card 1 content up to and including the continuation comma")
                    .isEqualTo(usAsciiBytes(CARD_01_CONTENT));
        }

        @Test
        @DisplayName("card 2 spells the system-user symbol correctly and does not carry the sibling member's transposed spelling")
        void cardTwoSpellsTheSystemUserSymbolCorrectly() {
            final List<String> cards = JclCardImageBuilder.build(START_DATE, END_DATE);
            final String cardTwo = card(cards, 2);

            // The correctly spelled six-letter symbol, at its exact offset.
            assertThat(usAsciiSlice(cardTwo, CARD_02_SYSTEM_USER_SYMBOL_OFFSET,
                    CARD_02_CONTENT_WIDTH))
                    .as("system-user symbol on card 2").isEqualTo(usAsciiBytes("&SYSUID"));
            assertThat(cardTwo).as("card 2 carries the correctly spelled system-user symbol")
                    .contains("&SYSUID");

            // A sibling member of the estate carries a transposed, five-letter spelling of the same
            // symbol. Its absence is asserted here, but the token is assembled from two fragments so
            // that the typo never appears as a literal anywhere in this source file.
            final String transposedSymbol = "&SY" + "UID";
            assertThat(cardTwo).as("card 2 must not import the sibling member's transposed spelling")
                    .doesNotContain(transposedSymbol);
        }

        @Test
        @DisplayName("card 9 declares sixteen zoned-decimal bytes at one-based record offset 263")
        void cardNineDeclaresSixteenZonedDecimalBytesAtOffset263() {
            final List<String> cards = JclCardImageBuilder.build(START_DATE, END_DATE);
            final String cardNine = card(cards, 9);

            // Token layout of the sort-symbol card: name, comma, offset, comma, length, comma, type.
            final int symbolNameEnd = 13;
            final int recordOffsetStart = 14;
            final int recordOffsetEnd = 17;
            final int fieldLengthStart = 18;
            final int fieldLengthEnd = 20;
            final int fieldTypeStart = 21;
            final int fieldTypeEnd = 23;

            assertThat(usAsciiSlice(cardNine, 0, symbolNameEnd)).as("card 9 symbol name")
                    .isEqualTo(usAsciiBytes("TRAN-CARD-NUM"));
            assertThat(usAsciiSlice(cardNine, recordOffsetStart, recordOffsetEnd))
                    .as("card 9 one-based record offset").isEqualTo(usAsciiBytes("263"));
            assertThat(usAsciiSlice(cardNine, fieldLengthStart, fieldLengthEnd))
                    .as("card 9 field length").isEqualTo(usAsciiBytes("16"));
            assertThat(usAsciiSlice(cardNine, fieldTypeStart, fieldTypeEnd))
                    .as("card 9 field type token").isEqualTo(usAsciiBytes("ZD"));
            assertThat(usAsciiBytes(cardNine)).as("card 9 complete image")
                    .isEqualTo(usAsciiBytes(oracleFixedCard(CARD_09_CONTENT, CARD_09_CONTENT_WIDTH)));
        }

        @Test
        @DisplayName("card 10 declares ten character bytes at one-based record offset 305")
        void cardTenDeclaresTenCharacterBytesAtOffset305() {
            final List<String> cards = JclCardImageBuilder.build(START_DATE, END_DATE);
            final String cardTen = card(cards, 10);

            final int symbolNameEnd = 12;
            final int recordOffsetStart = 13;
            final int recordOffsetEnd = 16;
            final int fieldLengthStart = 17;
            final int fieldLengthEnd = 19;
            final int fieldTypeStart = 20;
            final int fieldTypeEnd = 22;

            assertThat(usAsciiSlice(cardTen, 0, symbolNameEnd)).as("card 10 symbol name")
                    .isEqualTo(usAsciiBytes("TRAN-PROC-DT"));
            assertThat(usAsciiSlice(cardTen, recordOffsetStart, recordOffsetEnd))
                    .as("card 10 one-based record offset").isEqualTo(usAsciiBytes("305"));
            assertThat(usAsciiSlice(cardTen, fieldLengthStart, fieldLengthEnd))
                    .as("card 10 field length").isEqualTo(usAsciiBytes("10"));
            assertThat(usAsciiSlice(cardTen, fieldTypeStart, fieldTypeEnd))
                    .as("card 10 field type token").isEqualTo(usAsciiBytes("CH"));
            assertThat(usAsciiBytes(cardTen)).as("card 10 complete image")
                    .isEqualTo(usAsciiBytes(oracleFixedCard(CARD_10_CONTENT, CARD_10_CONTENT_WIDTH)));
        }

        @Test
        @DisplayName("comment cards 3, 5 and 7 are the same image and all three are emitted")
        void commentCardsThreeFiveAndSevenAreTheSameImage() {
            final List<String> cards = JclCardImageBuilder.build(START_DATE, END_DATE);
            final byte[] expected =
                    usAsciiBytes(oracleFixedCard(COMMENT_CARD_CONTENT, COMMENT_CARD_CONTENT_WIDTH));

            assertThat(usAsciiBytes(card(cards, 3))).as("card 3").isEqualTo(expected);
            assertThat(usAsciiBytes(card(cards, 5))).as("card 5").isEqualTo(expected);
            assertThat(usAsciiBytes(card(cards, 7))).as("card 7").isEqualTo(expected);
        }

        @Test
        @DisplayName("in-stream terminator cards 13 and 16 are the same image and both are emitted")
        void terminatorCardsThirteenAndSixteenAreTheSameImage() {
            final List<String> cards = JclCardImageBuilder.build(START_DATE, END_DATE);
            final byte[] expected = usAsciiBytes(
                    oracleFixedCard(TERMINATOR_CARD_CONTENT, TERMINATOR_CARD_CONTENT_WIDTH));

            assertThat(usAsciiBytes(card(cards, 13))).as("card 13").isEqualTo(expected);
            assertThat(usAsciiBytes(card(cards, 16))).as("card 16").isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("the three composed cards and their declared component widths")
    class ComposedCardArithmetic {

        @Test
        @DisplayName("card 11 is an eighteen-byte lead, a ten-byte slot and a fifty-two-byte trailer, 18 + 10 + 52 = 80")
        void cardElevenComposesEighteenPlusTenPlusFiftyTwo() {
            final List<String> cards = JclCardImageBuilder.build(START_DATE, END_DATE);
            final String cardEleven = card(cards, 11);
            final byte[] cardElevenBytes = usAsciiBytes(cardEleven);

            assertThat(CARD_11_LEAD_WIDTH + ORACLE_DATE_SLOT_WIDTH + CARD_11_TRAILER_WIDTH)
                    .as("declared component widths of card 11").isEqualTo(ORACLE_CARD_WIDTH);
            assertThat(cardElevenBytes.length).as("card 11 total encoded width")
                    .isEqualTo(ORACLE_CARD_WIDTH);

            assertThat(usAsciiSlice(cardEleven, 0, CARD_11_SLOT_OFFSET))
                    .as("card 11 eighteen-byte leading literal")
                    .isEqualTo(usAsciiBytes(CARD_11_LEAD));
            assertThat(usAsciiSlice(cardEleven, CARD_11_SLOT_OFFSET,
                    CARD_11_CLOSING_APOSTROPHE_OFFSET))
                    .as("card 11 ten-byte start-date slot").isEqualTo(usAsciiBytes(START_DATE));
            assertThat(cardElevenBytes[CARD_11_CLOSING_APOSTROPHE_OFFSET])
                    .as("first byte of the card 11 fifty-two-byte trailer, the closing apostrophe")
                    .isEqualTo(ORACLE_APOSTROPHE_BYTE);
            assertThat(usAsciiSlice(cardEleven, CARD_11_CLOSING_APOSTROPHE_OFFSET + 1,
                    ORACLE_CARD_WIDTH))
                    .as("card 11 fifty-one trailing spaces after the closing apostrophe")
                    .isEqualTo(usAsciiBytes(oracleSpaces(CARD_11_TRAILER_SPACE_COUNT)));
        }

        @Test
        @DisplayName("card 12 is a sixteen-byte lead, a ten-byte slot and a fifty-four-byte trailer, 16 + 10 + 54 = 80")
        void cardTwelveComposesSixteenPlusTenPlusFiftyFour() {
            final List<String> cards = JclCardImageBuilder.build(START_DATE, END_DATE);
            final String cardTwelve = card(cards, 12);
            final byte[] cardTwelveBytes = usAsciiBytes(cardTwelve);

            assertThat(CARD_12_LEAD_WIDTH + ORACLE_DATE_SLOT_WIDTH + CARD_12_TRAILER_WIDTH)
                    .as("declared component widths of card 12").isEqualTo(ORACLE_CARD_WIDTH);
            assertThat(cardTwelveBytes.length).as("card 12 total encoded width")
                    .isEqualTo(ORACLE_CARD_WIDTH);

            assertThat(usAsciiSlice(cardTwelve, 0, CARD_12_SLOT_OFFSET))
                    .as("card 12 sixteen-byte leading literal")
                    .isEqualTo(usAsciiBytes(CARD_12_LEAD));
            assertThat(usAsciiSlice(cardTwelve, CARD_12_SLOT_OFFSET,
                    CARD_12_CLOSING_APOSTROPHE_OFFSET))
                    .as("card 12 ten-byte end-date slot").isEqualTo(usAsciiBytes(END_DATE));
            assertThat(cardTwelveBytes[CARD_12_CLOSING_APOSTROPHE_OFFSET])
                    .as("first byte of the card 12 fifty-four-byte trailer, the closing apostrophe")
                    .isEqualTo(ORACLE_APOSTROPHE_BYTE);
            assertThat(usAsciiSlice(cardTwelve, CARD_12_CLOSING_APOSTROPHE_OFFSET + 1,
                    ORACLE_CARD_WIDTH))
                    .as("card 12 fifty-three trailing spaces after the closing apostrophe")
                    .isEqualTo(usAsciiBytes(oracleSpaces(CARD_12_TRAILER_SPACE_COUNT)));
        }

        @Test
        @DisplayName("card 15 is a ten-byte slot, exactly one separator byte, a ten-byte slot and fifty-nine spaces, 10 + 1 + 10 + 59 = 80")
        void cardFifteenComposesTenPlusOnePlusTenPlusFiftyNine() {
            final List<String> cards = JclCardImageBuilder.build(START_DATE, END_DATE);
            final String cardFifteen = card(cards, 15);
            final byte[] cardFifteenBytes = usAsciiBytes(cardFifteen);

            assertThat(ORACLE_DATE_SLOT_WIDTH + CARD_15_SEPARATOR_WIDTH + ORACLE_DATE_SLOT_WIDTH
                    + CARD_15_TRAILER_SPACE_COUNT)
                    .as("declared component widths of card 15").isEqualTo(ORACLE_CARD_WIDTH);
            assertThat(cardFifteenBytes.length).as("card 15 total encoded width")
                    .isEqualTo(ORACLE_CARD_WIDTH);

            assertThat(usAsciiSlice(cardFifteen, CARD_15_START_SLOT_OFFSET,
                    CARD_15_SEPARATOR_OFFSET))
                    .as("card 15 ten-byte start-date slot").isEqualTo(usAsciiBytes(START_DATE));
            assertThat(usAsciiSlice(cardFifteen, CARD_15_END_SLOT_OFFSET,
                    CARD_15_END_SLOT_OFFSET + ORACLE_DATE_SLOT_WIDTH))
                    .as("card 15 ten-byte end-date slot").isEqualTo(usAsciiBytes(END_DATE));
            assertThat(usAsciiSlice(cardFifteen,
                    CARD_15_END_SLOT_OFFSET + ORACLE_DATE_SLOT_WIDTH, ORACLE_CARD_WIDTH))
                    .as("card 15 fifty-nine trailing spaces")
                    .isEqualTo(usAsciiBytes(oracleSpaces(CARD_15_TRAILER_SPACE_COUNT)));
        }

        @Test
        @DisplayName("the card 15 separator is exactly one ASCII space, because the legacy field is a bare single-byte item")
        void cardFifteenSeparatorIsExactlyOneAsciiSpace() {
            final List<String> cards = JclCardImageBuilder.build(START_DATE, END_DATE);
            final String cardFifteen = card(cards, 15);
            final byte[] cardFifteenBytes = usAsciiBytes(cardFifteen);

            assertThat(usAsciiSlice(cardFifteen, CARD_15_SEPARATOR_OFFSET,
                    CARD_15_SEPARATOR_OFFSET + CARD_15_SEPARATOR_WIDTH))
                    .as("the card 15 separator, which is exactly one byte wide")
                    .isEqualTo(usAsciiBytes(" "));
            assertThat(cardFifteenBytes[CARD_15_SEPARATOR_OFFSET])
                    .as("the card 15 separator byte").isEqualTo(ORACLE_SPACE_BYTE);

            // One byte, not two: the byte immediately after the separator is the first byte of the
            // end-date slot, so a second separator byte would have displaced it.
            assertThat(cardFifteenBytes[CARD_15_END_SLOT_OFFSET])
                    .as("first byte of the end-date slot, immediately after the single separator")
                    .isEqualTo(usAsciiBytes(END_DATE)[0]);
        }

        @Test
        @DisplayName("the leading twenty-one bytes of card 15 are the ten-plus-one-plus-ten date-parameter record the report line formatter must agree with")
        void leadingTwentyOneBytesOfCardFifteenAreTheDateParameterRecord() {
            final List<String> cards = JclCardImageBuilder.build(START_DATE, END_DATE);
            final String cardFifteen = card(cards, 15);

            assertThat(ORACLE_DATE_SLOT_WIDTH + CARD_15_SEPARATOR_WIDTH + ORACLE_DATE_SLOT_WIDTH)
                    .as("declared width of the date-parameter record")
                    .isEqualTo(ORACLE_DATE_PARAMETER_RECORD_WIDTH);

            // Hand written from the same component widths the report line formatter's date-parameter
            // record builder is required to use. The agreement between the two is documented rather
            // than coded: nothing from the report formatter is imported, referenced or called here.
            final byte[] expectedRecord =
                    usAsciiBytes(START_DATE + oracleSpaces(CARD_15_SEPARATOR_WIDTH) + END_DATE);

            assertThat(expectedRecord.length).as("hand-written date-parameter record width")
                    .isEqualTo(ORACLE_DATE_PARAMETER_RECORD_WIDTH);
            assertThat(usAsciiSlice(cardFifteen, 0, ORACLE_DATE_PARAMETER_RECORD_WIDTH))
                    .as("leading twenty-one bytes of card 15").isEqualTo(expectedRecord);
            assertThat(usAsciiBytes(cardFifteen)[ORACLE_DATE_PARAMETER_RECORD_WIDTH])
                    .as("byte immediately after the date-parameter record, where padding starts")
                    .isEqualTo(ORACLE_SPACE_BYTE);
        }
    }

    @Nested
    @DisplayName("four substitution slots carrying two values")
    class FourSubstitutionSlotsCarryTwoValues {

        @Test
        @DisplayName("four ten-byte substitution slots carry only two values: the start date fills the card 11 and card 15 slots, the end date fills the card 12 and card 15 slots, and the two occurrences of each value are byte identical")
        void theTwoOccurrencesOfEachDateAreByteIdentical() {
            final List<String> cards = JclCardImageBuilder.build(START_DATE, END_DATE);

            final byte[] startDateOnCardEleven = usAsciiSlice(card(cards, 11), CARD_11_SLOT_OFFSET,
                    CARD_11_CLOSING_APOSTROPHE_OFFSET);
            final byte[] startDateOnCardFifteen = usAsciiSlice(card(cards, 15),
                    CARD_15_START_SLOT_OFFSET, CARD_15_SEPARATOR_OFFSET);
            final byte[] endDateOnCardTwelve = usAsciiSlice(card(cards, 12), CARD_12_SLOT_OFFSET,
                    CARD_12_CLOSING_APOSTROPHE_OFFSET);
            final byte[] endDateOnCardFifteen = usAsciiSlice(card(cards, 15),
                    CARD_15_END_SLOT_OFFSET, CARD_15_END_SLOT_OFFSET + ORACLE_DATE_SLOT_WIDTH);

            assertThat(startDateOnCardEleven.length).as("width of the card 11 start-date slot")
                    .isEqualTo(ORACLE_DATE_SLOT_WIDTH);
            assertThat(startDateOnCardFifteen.length).as("width of the card 15 start-date slot")
                    .isEqualTo(ORACLE_DATE_SLOT_WIDTH);
            assertThat(endDateOnCardTwelve.length).as("width of the card 12 end-date slot")
                    .isEqualTo(ORACLE_DATE_SLOT_WIDTH);
            assertThat(endDateOnCardFifteen.length).as("width of the card 15 end-date slot")
                    .isEqualTo(ORACLE_DATE_SLOT_WIDTH);

            assertThat(startDateOnCardEleven)
                    .as("the start date on card 11 and on card 15 must never diverge")
                    .isEqualTo(startDateOnCardFifteen);
            assertThat(endDateOnCardTwelve)
                    .as("the end date on card 12 and on card 15 must never diverge")
                    .isEqualTo(endDateOnCardFifteen);

            assertThat(startDateOnCardEleven).as("card 11 start-date slot content")
                    .isEqualTo(usAsciiBytes(START_DATE));
            assertThat(startDateOnCardFifteen).as("card 15 start-date slot content")
                    .isEqualTo(usAsciiBytes(START_DATE));
            assertThat(endDateOnCardTwelve).as("card 12 end-date slot content")
                    .isEqualTo(usAsciiBytes(END_DATE));
            assertThat(endDateOnCardFifteen).as("card 15 end-date slot content")
                    .isEqualTo(usAsciiBytes(END_DATE));
        }

        @Test
        @DisplayName("only cards 11, 12 and 15 vary with the date pair; the other fourteen are invariant")
        void onlyTheThreeComposedCardsVaryWithTheDatePair() {
            final List<String> firstPair = JclCardImageBuilder.build(START_DATE, END_DATE);
            final List<String> secondPair =
                    JclCardImageBuilder.build(OTHER_START_DATE, OTHER_END_DATE);

            assertThat(card(secondPair, 11)).as("card 11 must change with the start date")
                    .isNotEqualTo(card(firstPair, 11));
            assertThat(card(secondPair, 12)).as("card 12 must change with the end date")
                    .isNotEqualTo(card(firstPair, 12));
            assertThat(card(secondPair, 15)).as("card 15 must change with both dates")
                    .isNotEqualTo(card(firstPair, 15));

            final int[] invariantCardNumbers = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 13, 14, 16, 17};
            for (final int cardNumber : invariantCardNumbers) {
                assertThat(usAsciiBytes(card(secondPair, cardNumber)))
                        .as("card " + cardNumber + " must not vary with the date pair")
                        .isEqualTo(usAsciiBytes(card(firstPair, cardNumber)));
            }
        }

        @Test
        @DisplayName("the second date pair lands in all four slots, so no slot is left holding a stale value")
        void theSecondDatePairFillsAllFourSlots() {
            final List<String> cards =
                    JclCardImageBuilder.build(OTHER_START_DATE, OTHER_END_DATE);

            assertThat(usAsciiSlice(card(cards, 11), CARD_11_SLOT_OFFSET,
                    CARD_11_CLOSING_APOSTROPHE_OFFSET))
                    .as("card 11 start-date slot").isEqualTo(usAsciiBytes(OTHER_START_DATE));
            assertThat(usAsciiSlice(card(cards, 12), CARD_12_SLOT_OFFSET,
                    CARD_12_CLOSING_APOSTROPHE_OFFSET))
                    .as("card 12 end-date slot").isEqualTo(usAsciiBytes(OTHER_END_DATE));
            assertThat(usAsciiSlice(card(cards, 15), CARD_15_START_SLOT_OFFSET,
                    CARD_15_SEPARATOR_OFFSET))
                    .as("card 15 start-date slot").isEqualTo(usAsciiBytes(OTHER_START_DATE));
            assertThat(usAsciiSlice(card(cards, 15), CARD_15_END_SLOT_OFFSET,
                    CARD_15_END_SLOT_OFFSET + ORACLE_DATE_SLOT_WIDTH))
                    .as("card 15 end-date slot").isEqualTo(usAsciiBytes(OTHER_END_DATE));
        }
    }

    @Nested
    @DisplayName("the terminating sentinel card")
    class TerminatingSentinelCard {

        @Test
        @DisplayName("the sentinel is transmitted rather than merely held: the legacy loop writes the card after setting its terminator, so all seventeen cards reach the queue")
        void theSentinelIsTransmittedRatherThanMerelyHeld() {
            // The legacy emission loop moves the card into the transmit buffer, then raises its
            // terminator flag once the card matches the sentinel, and only then performs the write.
            // The write therefore happens in the same iteration that recognised the sentinel, so the
            // sentinel is transmitted and the count is seventeen rather than sixteen.
            //
            //
            // Source anomaly, recorded for auditability and deliberately not reproduced: the legacy
            // submission paragraph's own name carries a transposed spelling, carried as row 6 of the
            // source anomaly register. The Java member is named correctly, so the misspelling appears
            // nowhere in this file's code.
            final List<String> cards = JclCardImageBuilder.build(START_DATE, END_DATE);

            assertThat(cards).as("seventeen cards reach the queue, sentinel included")
                    .hasSize(ORACLE_CARD_COUNT);
            assertThat(usAsciiBytes(card(cards, ORACLE_CARD_COUNT)))
                    .as("the seventeenth and last card is the sentinel")
                    .isEqualTo(usAsciiBytes(oracleFixedCard(CARD_17_CONTENT, CARD_17_CONTENT_WIDTH)));
            assertThat(usAsciiLength(card(cards, ORACLE_CARD_COUNT)))
                    .as("the sentinel card occupies the full eighty-byte frame")
                    .isEqualTo(ORACLE_CARD_WIDTH);
        }

        @Test
        @DisplayName("the sentinel is neither dropped nor replaced by a blank card")
        void theSentinelIsNeitherDroppedNorBlanked() {
            final List<String> cards = JclCardImageBuilder.build(START_DATE, END_DATE);
            final String lastCard = card(cards, ORACLE_CARD_COUNT);

            assertThat(lastCard).as("the last card is not a blank card")
                    .isNotEqualTo(oracleSpaces(ORACLE_CARD_WIDTH));
            assertThat(usAsciiSlice(lastCard, 0, CARD_17_CONTENT_WIDTH))
                    .as("the sentinel content occupies the leading bytes of the last card")
                    .isEqualTo(usAsciiBytes(CARD_17_CONTENT));
            assertThat(cards).as("the sentinel appears exactly once in the sequence")
                    .containsOnlyOnce(oracleFixedCard(CARD_17_CONTENT, CARD_17_CONTENT_WIDTH));
        }

        @Test
        @DisplayName("the sentinel is emitted for every date pair, never conditionally")
        void theSentinelIsEmittedForEveryDatePair() {
            final byte[] expectedSentinel =
                    usAsciiBytes(oracleFixedCard(CARD_17_CONTENT, CARD_17_CONTENT_WIDTH));

            assertThat(usAsciiBytes(
                    card(JclCardImageBuilder.build(START_DATE, END_DATE), ORACLE_CARD_COUNT)))
                    .as("sentinel for the first date pair").isEqualTo(expectedSentinel);
            assertThat(usAsciiBytes(card(
                    JclCardImageBuilder.build(OTHER_START_DATE, OTHER_END_DATE), ORACLE_CARD_COUNT)))
                    .as("sentinel for the second date pair").isEqualTo(expectedSentinel);
        }
    }

    @Nested
    @DisplayName("there is no report-name substitution slot")
    class NoReportNameSubstitutionSlot {

        @Test
        @DisplayName("the job card on card 1 is a fixed literal: the legacy report-name work field is screen only and is never substituted into any card")
        void theJobCardIsInvariantAcrossDifferentDatePairs() {
            final List<String> firstPair = JclCardImageBuilder.build(START_DATE, END_DATE);
            final List<String> secondPair =
                    JclCardImageBuilder.build(OTHER_START_DATE, OTHER_END_DATE);

            // Card 1 is byte identical for both date pairs, and it equals the hand-written literal.
            // There is deliberately no third builder parameter for a report name, so nobody should
            // add one: the report name only ever composed a screen message in the legacy program.
            assertThat(usAsciiBytes(card(secondPair, 1)))
                    .as("card 1 is invariant across two different date pairs")
                    .isEqualTo(usAsciiBytes(card(firstPair, 1)));
            assertThat(usAsciiBytes(card(firstPair, 1)))
                    .as("card 1 equals the hand-written fixed job card")
                    .isEqualTo(usAsciiBytes(oracleFixedCard(CARD_01_CONTENT, CARD_01_CONTENT_WIDTH)));
        }

        @Test
        @DisplayName("no card carries a report-name placeholder")
        void noCardCarriesAReportNamePlaceholder() {
            final List<String> cards = JclCardImageBuilder.build(START_DATE, END_DATE);

            for (int cardNumber = 1; cardNumber <= ORACLE_CARD_COUNT; cardNumber++) {
                assertThat(card(cards, cardNumber))
                        .as("card " + cardNumber + " must carry no report-name symbol")
                        .doesNotContain("PARM-REPORT-NAME")
                        .doesNotContain("REPORT-NAME");
            }
        }
    }

    @Nested
    @DisplayName("the oversized redefine of the legacy card table")
    class OversizedRedefineAnomaly {

        @Test
        @DisplayName("the legacy card table is redefined as one thousand eighty-byte entries over a 1360-byte group, an oversized-redefine anomaly honoured as an asserted constant and never allocated")
        void theOneThousandEntryBoundIsAssertedButNeverAllocated() {
            final List<String> cards = JclCardImageBuilder.build(START_DATE, END_DATE);

            assertThat(JclCardImageBuilder.OVERSIZED_REDEFINE_CARD_BOUND)
                    .as("published bound of the legacy oversized redefine")
                    .isEqualTo(ORACLE_MAX_CARD_BOUND);
            assertThat(cards).as("only seventeen cards are ever produced, never one thousand")
                    .hasSize(ORACLE_CARD_COUNT);
            assertThat(cards.size())
                    .as("produced card count stays far below the legacy bound")
                    .isLessThan(ORACLE_MAX_CARD_BOUND);
        }

        @Test
        @DisplayName("no blank or padding-only card is emitted to fill the oversized bound")
        void noBlankOrPaddingOnlyCardIsEmitted() {
            final List<String> cards = JclCardImageBuilder.build(START_DATE, END_DATE);
            final String blankCard = oracleSpaces(ORACLE_CARD_WIDTH);

            assertThat(cards).as("no card in the sequence is blank").doesNotContain(blankCard);
            for (int cardNumber = 1; cardNumber <= ORACLE_CARD_COUNT; cardNumber++) {
                final byte[] cardBytes = usAsciiBytes(card(cards, cardNumber));

                assertThat(cardBytes[0]).as("first byte of card " + cardNumber + " is real content")
                        .isNotEqualTo(ORACLE_SPACE_BYTE);
            }
        }
    }

    @Nested
    @DisplayName("the concatenated job-submission image")
    class ConcatenatedImageContract {

        @Test
        @DisplayName("is exactly 1360 encoded bytes, being seventeen cards of eighty bytes")
        void isExactlyOneThousandThreeHundredAndSixtyEncodedBytes() {
            final String image = JclCardImageBuilder.buildConcatenatedImage(START_DATE, END_DATE);

            assertThat(usAsciiLength(image)).as("concatenated image encoded width")
                    .isEqualTo(ORACLE_TOTAL_IMAGE_WIDTH);
        }

        @Test
        @DisplayName("carries no line feed, carriage return or tab byte, because the cards are fixed-width records and not text lines")
        void carriesNoTerminatorOrTabByte() {
            final byte[] imageBytes =
                    usAsciiBytes(JclCardImageBuilder.buildConcatenatedImage(START_DATE, END_DATE));

            assertThat(indexOfByte(imageBytes, ORACLE_LINE_FEED_BYTE))
                    .as("line feed byte in the concatenated image").isEqualTo(BYTE_NOT_FOUND);
            assertThat(indexOfByte(imageBytes, ORACLE_CARRIAGE_RETURN_BYTE))
                    .as("carriage return byte in the concatenated image").isEqualTo(BYTE_NOT_FOUND);
            assertThat(indexOfByte(imageBytes, ORACLE_TAB_BYTE))
                    .as("tab byte in the concatenated image").isEqualTo(BYTE_NOT_FOUND);
            assertThat(indexOfByte(imageBytes, ORACLE_NULL_BYTE))
                    .as("zero byte in the concatenated image").isEqualTo(BYTE_NOT_FOUND);
        }

        @Test
        @DisplayName("equals the seventeen hand-written cards joined in order, with no separator between them")
        void equalsTheSeventeenHandWrittenCardsJoinedInOrder() {
            final String image = JclCardImageBuilder.buildConcatenatedImage(START_DATE, END_DATE);
            final String expected = oracleConcatenatedImage(oracleCards(START_DATE, END_DATE));

            assertThat(usAsciiLength(expected)).as("hand-written joined image width")
                    .isEqualTo(ORACLE_TOTAL_IMAGE_WIDTH);
            assertThat(usAsciiBytes(image)).as("concatenated image against the hand-written join")
                    .isEqualTo(usAsciiBytes(expected));
        }

        @Test
        @DisplayName("places each of the seventeen cards at its own exact eighty-byte offset")
        void placesEachCardAtItsExactEightyByteOffset() {
            final String image = JclCardImageBuilder.buildConcatenatedImage(START_DATE, END_DATE);
            final List<String> expectedCards = oracleCards(START_DATE, END_DATE);

            for (int cardNumber = 1; cardNumber <= ORACLE_CARD_COUNT; cardNumber++) {
                final int fromOffset = (cardNumber - 1) * ORACLE_CARD_WIDTH;

                assertThat(usAsciiSlice(image, fromOffset, fromOffset + ORACLE_CARD_WIDTH))
                        .as("card " + cardNumber + " at byte offset " + fromOffset)
                        .isEqualTo(usAsciiBytes(card(expectedCards, cardNumber)));
            }
        }

        @Test
        @DisplayName("has no trailing newline: its final byte is the sentinel card's padding space")
        void hasNoTrailingNewline() {
            final byte[] imageBytes =
                    usAsciiBytes(JclCardImageBuilder.buildConcatenatedImage(START_DATE, END_DATE));

            assertThat(imageBytes[imageBytes.length - 1])
                    .as("final byte of the concatenated image").isEqualTo(ORACLE_SPACE_BYTE);
        }

        @Test
        @DisplayName("holds the same seventeen cards the card sequence accessor returns")
        void holdsTheSameSeventeenCardsAsTheSequenceAccessor() {
            final String image =
                    JclCardImageBuilder.buildConcatenatedImage(OTHER_START_DATE, OTHER_END_DATE);
            final String expected = oracleConcatenatedImage(
                    oracleCards(OTHER_START_DATE, OTHER_END_DATE));

            assertThat(usAsciiBytes(image))
                    .as("concatenated image for the second date pair")
                    .isEqualTo(usAsciiBytes(expected));
            assertThat(usAsciiLength(image)).as("concatenated image width for the second date pair")
                    .isEqualTo(ORACLE_TOTAL_IMAGE_WIDTH);
        }
    }

    @Nested
    @DisplayName("the returned card sequence is unmodifiable")
    class UnmodifiableResult {

        // The class under test publishes its seventeen card literals as individual immutable string
        // constants rather than as a collection, so there is no exposed literal collection view whose
        // unmodifiability could be asserted. The one collection the contract exposes is the card
        // sequence returned below, and every mutation of it is rejected.

        @Test
        @DisplayName("rejects add")
        void rejectsAdd() {
            final List<String> cards = JclCardImageBuilder.build(START_DATE, END_DATE);

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> cards.add(CARD_17_CONTENT));
        }

        @Test
        @DisplayName("rejects set")
        void rejectsSet() {
            final List<String> cards = JclCardImageBuilder.build(START_DATE, END_DATE);

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> cards.set(0, CARD_17_CONTENT));
        }

        @Test
        @DisplayName("rejects remove")
        void rejectsRemove() {
            final List<String> cards = JclCardImageBuilder.build(START_DATE, END_DATE);

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> cards.remove(0));
        }

        @Test
        @DisplayName("rejects clear")
        void rejectsClear() {
            final List<String> cards = JclCardImageBuilder.build(START_DATE, END_DATE);

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(cards::clear);
        }

        @Test
        @DisplayName("a rejected mutation leaves the sequence intact")
        void aRejectedMutationLeavesTheSequenceIntact() {
            final List<String> cards = JclCardImageBuilder.build(START_DATE, END_DATE);

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> cards.add(CARD_17_CONTENT));
            assertThat(cards).as("card sequence after a rejected mutation")
                    .containsExactlyElementsOf(oracleCards(START_DATE, END_DATE));
        }
    }

    @Nested
    @DisplayName("date slot width validation")
    class DateSlotWidthValidation {

        @Test
        @DisplayName("rejecting a malformed slot is a deliberate divergence: the legacy move padded or truncated silently, and the Java target rejects instead so no malformed card is ever emitted")
        void rejectionIsADeliberateDivergenceFromTheLegacyMove() {
            // The legacy program moved a screen field into a ten-byte slot, and a move into a field of
            // a different size pads or truncates with no diagnostic, so a malformed value would have
            // produced a silently corrupt card and a broken eighty-column frame. Decisions D-06 and
            // D-08 record the rejection that replaces that silence, and decision D-11 records the
            // exception type; neither is decided here.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JclCardImageBuilder.build(NINE_BYTE_DATE, END_DATE));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JclCardImageBuilder.build(START_DATE, ELEVEN_BYTE_DATE));
        }

        @Test
        @DisplayName("rejects a start date of nine encoded bytes, naming the slot, the expected width and the actual width")
        void rejectsAStartDateOfNineEncodedBytes() {
            assertThat(usAsciiLength(NINE_BYTE_DATE)).as("width of the too-short slot value")
                    .isEqualTo(9);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JclCardImageBuilder.build(NINE_BYTE_DATE, END_DATE))
                    .withMessageContaining("PARM-START-DATE")
                    .withMessageContaining("10 encoded bytes")
                    .withMessageEndingWith("9");
        }

        @Test
        @DisplayName("rejects an end date of nine encoded bytes, naming the end slot")
        void rejectsAnEndDateOfNineEncodedBytes() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JclCardImageBuilder.build(START_DATE, NINE_BYTE_DATE))
                    .withMessageContaining("PARM-END-DATE")
                    .withMessageContaining("10 encoded bytes")
                    .withMessageEndingWith("9");
        }

        @Test
        @DisplayName("rejects a start date of eleven encoded bytes")
        void rejectsAStartDateOfElevenEncodedBytes() {
            assertThat(usAsciiLength(ELEVEN_BYTE_DATE)).as("width of the too-long slot value")
                    .isEqualTo(11);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JclCardImageBuilder.build(ELEVEN_BYTE_DATE, END_DATE))
                    .withMessageContaining("PARM-START-DATE")
                    .withMessageContaining("10 encoded bytes")
                    .withMessageEndingWith("11");
        }

        @Test
        @DisplayName("rejects an end date of eleven encoded bytes")
        void rejectsAnEndDateOfElevenEncodedBytes() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JclCardImageBuilder.build(START_DATE, ELEVEN_BYTE_DATE))
                    .withMessageContaining("PARM-END-DATE")
                    .withMessageContaining("10 encoded bytes")
                    .withMessageEndingWith("11");
        }

        @Test
        @DisplayName("rejects an empty start date")
        void rejectsAnEmptyStartDate() {
            assertThat(usAsciiLength(EMPTY_DATE)).as("width of the empty slot value").isEqualTo(0);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JclCardImageBuilder.build(EMPTY_DATE, END_DATE))
                    .withMessageContaining("PARM-START-DATE")
                    .withMessageContaining("10 encoded bytes")
                    .withMessageEndingWith("0");
        }

        @Test
        @DisplayName("rejects an empty end date")
        void rejectsAnEmptyEndDate() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JclCardImageBuilder.build(START_DATE, EMPTY_DATE))
                    .withMessageContaining("PARM-END-DATE")
                    .withMessageContaining("10 encoded bytes")
                    .withMessageEndingWith("0");
        }

        @Test
        @DisplayName("rejects a null start date")
        void rejectsANullStartDate() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> JclCardImageBuilder.build(null, END_DATE))
                    .withMessageContaining("PARM-START-DATE");
        }

        @Test
        @DisplayName("rejects a null end date")
        void rejectsANullEndDate() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> JclCardImageBuilder.build(START_DATE, null))
                    .withMessageContaining("PARM-END-DATE");
        }

        @Test
        @DisplayName("rejects a ten-character slot whose bytes are not single-byte encodable, because the eighty-column frame would shift")
        void rejectsASlotThatIsNotSingleByteEncodable() {
            // This is the one place a character count is asserted, and it is asserted only to
            // establish why a width check cannot carry this case on its own. The value is ten
            // characters long and it also encodes to ten bytes, because US-ASCII substitutes a
            // replacement byte for a character it cannot represent rather than refusing or
            // expanding. A width-only guard would therefore wave this value straight through and
            // the card would silently carry a corrupted byte where a date digit belongs, which is
            // why the builder additionally requires that every character be representable.
            assertThat(NON_SINGLE_BYTE_DATE.length())
                    .as("character count of the non-single-byte slot value")
                    .isEqualTo(ORACLE_DATE_SLOT_WIDTH);
            assertThat(usAsciiBytes(NON_SINGLE_BYTE_DATE).length)
                    .as("encoded width, which replacement makes indistinguishable from a valid slot")
                    .isEqualTo(ORACLE_DATE_SLOT_WIDTH);
            assertThat(usAsciiBytes(NON_SINGLE_BYTE_DATE)[ORACLE_DATE_SLOT_WIDTH - 1])
                    .as("the lossy replacement byte a width-only guard would have admitted")
                    .isEqualTo(ORACLE_REPLACEMENT_BYTE);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JclCardImageBuilder.build(NON_SINGLE_BYTE_DATE, END_DATE))
                    .withMessageContaining("PARM-START-DATE")
                    .withMessageContaining("US-ASCII");
        }

        @Test
        @DisplayName("the concatenated image accessor applies the same slot validation")
        void theConcatenatedImageAccessorAppliesTheSameValidation() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JclCardImageBuilder
                            .buildConcatenatedImage(NINE_BYTE_DATE, END_DATE))
                    .withMessageContaining("PARM-START-DATE")
                    .withMessageContaining("encoded bytes");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JclCardImageBuilder
                            .buildConcatenatedImage(START_DATE, ELEVEN_BYTE_DATE))
                    .withMessageContaining("PARM-END-DATE")
                    .withMessageContaining("encoded bytes");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> JclCardImageBuilder.buildConcatenatedImage(null, END_DATE))
                    .withMessageContaining("PARM-START-DATE");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> JclCardImageBuilder.buildConcatenatedImage(START_DATE, null))
                    .withMessageContaining("PARM-END-DATE");
        }

        @Test
        @DisplayName("the width guard reports first and reports only width, so a wrong-width value is never diagnosed positionally and a right-width value is never diagnosed as a width failure")
        void theWidthGuardReportsFirstAndReportsOnlyWidth() {
            // Width is the only property that can be checked before the value is examined position by
            // position, so it is checked first and its diagnostic stands alone. A ten-byte value moves
            // on to the positional allowlist and then to the calendar guard, each of which names
            // itself. Keeping the three diagnostics disjoint is what makes any one of them actionable.
            final String tenByteNonCalendarValue = "9999-99-99";
            assertThat(usAsciiLength(tenByteNonCalendarValue))
                    .as("width of the non-calendar slot value").isEqualTo(ORACLE_DATE_SLOT_WIDTH);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JclCardImageBuilder.build(NINE_BYTE_DATE, END_DATE))
                    .withMessageContaining("10 encoded bytes")
                    .withMessageNotContaining("shape")
                    .withMessageNotContaining("day that exists");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JclCardImageBuilder
                            .build(tenByteNonCalendarValue, tenByteNonCalendarValue))
                    .withMessageContaining("day that exists")
                    .withMessageNotContaining("10 encoded bytes");
        }
    }

    /**
     * Reports whether a ten-character value names a day that actually exists.
     *
     * <p>Deliberately computed here from the platform calendar rather than read back from the class
     * under test, so the expectation stays independent of the implementation it checks. The
     * era-independent year pattern under strict resolution is used because the conventional pattern is
     * year-of-era and would demand an era field the slot does not carry.
     *
     * @param slotValue a ten-character value already known to satisfy the positional allowlist
     * @return {@code true} when the value names a real calendar day
     */
    private static boolean namesARealDay(final String slotValue) {
        try {
            LocalDate.parse(slotValue, DateTimeFormatter.ofPattern("uuuu-MM-dd")
                    .withResolverStyle(ResolverStyle.STRICT));
            return true;
        } catch (DateTimeParseException notARealDay) {
            return false;
        }
    }

    @Nested
    @DisplayName("date slot shape validation: the frame includes the slot's own structure")
    class DateSlotShapeValidation {

        @Test
        @DisplayName("an apostrophe inside a slot is rejected, because it would close the sort character constant early and turn the rest of card 11 into further specification")
        void anApostropheInsideAStartSlotIsRejected() {
            // The whole reason a right-width slot still needs a shape check. Card 11 wraps the slot
            // in a character constant opened by the leading literal and closed by the apostrophe in
            // the trailing field. An apostrophe inside the slot closes that constant eight bytes
            // early, and the bytes after it are then read by the sort step as further specification
            // rather than as the tail of a date. The value below is exactly ten single-byte
            // US-ASCII characters, so neither the width check nor the encodability check sees it.
            assertThat(usAsciiLength(APOSTROPHE_INJECTION_SLOT))
                    .as("width of the apostrophe-carrying slot value")
                    .isEqualTo(ORACLE_DATE_SLOT_WIDTH);
            assertThat(indexOfByte(usAsciiBytes(APOSTROPHE_INJECTION_SLOT), ORACLE_APOSTROPHE_BYTE))
                    .as("the apostrophe a width-only guard would have admitted")
                    .isNotEqualTo(BYTE_NOT_FOUND);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JclCardImageBuilder.build(APOSTROPHE_INJECTION_SLOT, END_DATE))
                    .withMessageContaining("PARM-START-DATE")
                    .withMessageContaining(ORACLE_DATE_SLOT_FORMAT);
        }

        @Test
        @DisplayName("an apostrophe inside an end slot is rejected, naming the end slot")
        void anApostropheInsideAnEndSlotIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JclCardImageBuilder.build(START_DATE, APOSTROPHE_INJECTION_SLOT))
                    .withMessageContaining("PARM-END-DATE")
                    .withMessageContaining(ORACLE_DATE_SLOT_FORMAT);
        }

        @Test
        @DisplayName("a comma inside a slot is rejected, because it would introduce a fresh sort operand")
        void aCommaInsideASlotIsRejected() {
            final String slot = slotWithCharacterAt(LAST_NUMERIC_SLOT_POSITION, ',');
            assertThat(usAsciiLength(slot)).as("width of the comma-carrying slot value")
                    .isEqualTo(ORACLE_DATE_SLOT_WIDTH);
            assertThat(indexOfByte(usAsciiBytes(slot), ORACLE_COMMA_BYTE))
                    .as("the comma a width-only guard would have admitted")
                    .isNotEqualTo(BYTE_NOT_FOUND);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JclCardImageBuilder.build(slot, END_DATE))
                    .withMessageContaining("PARM-START-DATE");
        }

        @Test
        @DisplayName("a carriage return or a line feed inside a slot is rejected, because the queue record is fixed and unblocked and a newline would split it")
        void aRecordSplittingNewlineInsideASlotIsRejected() {
            final String carriageReturnSlot =
                    slotWithCharacterAt(LAST_NUMERIC_SLOT_POSITION, '\r');
            final String lineFeedSlot = slotWithCharacterAt(LAST_NUMERIC_SLOT_POSITION, '\n');

            assertThat(indexOfByte(usAsciiBytes(carriageReturnSlot), ORACLE_CARRIAGE_RETURN_BYTE))
                    .as("the carriage return a width-only guard would have admitted")
                    .isNotEqualTo(BYTE_NOT_FOUND);
            assertThat(indexOfByte(usAsciiBytes(lineFeedSlot), ORACLE_LINE_FEED_BYTE))
                    .as("the line feed a width-only guard would have admitted")
                    .isNotEqualTo(BYTE_NOT_FOUND);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JclCardImageBuilder.build(carriageReturnSlot, END_DATE))
                    .withMessageContaining("PARM-START-DATE");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JclCardImageBuilder.build(START_DATE, lineFeedSlot))
                    .withMessageContaining("PARM-END-DATE");
        }

        @Test
        @DisplayName("a tab, a null, an escape or a delete byte inside a slot is rejected, at a numeric position and at a hyphen position alike")
        void aControlOrDeleteByteInsideASlotIsRejected() {
            final char[] rejectedBytes = {'\t', '\0', ESCAPE_CHARACTER, DELETE_CHARACTER};

            assertThat(usAsciiBytes(String.valueOf(rejectedBytes[0])))
                    .as("the tab byte under test").containsExactly(ORACLE_TAB_BYTE);
            assertThat(usAsciiBytes(String.valueOf(rejectedBytes[1])))
                    .as("the null byte under test").containsExactly(ORACLE_NULL_BYTE);

            for (final char rejected : rejectedBytes) {
                final String numericPositionSlot =
                        slotWithCharacterAt(LAST_NUMERIC_SLOT_POSITION, rejected);
                final String separatorPositionSlot =
                        slotWithCharacterAt(FIRST_SEPARATOR_SLOT_POSITION, rejected);

                assertThatExceptionOfType(IllegalArgumentException.class)
                        .as("a numeric position holding US-ASCII 0x"
                                + Integer.toHexString(rejected))
                        .isThrownBy(() -> JclCardImageBuilder.build(numericPositionSlot, END_DATE));
                assertThatExceptionOfType(IllegalArgumentException.class)
                        .as("a hyphen position holding US-ASCII 0x"
                                + Integer.toHexString(rejected))
                        .isThrownBy(() -> JclCardImageBuilder.build(separatorPositionSlot, END_DATE));
            }
        }

        @Test
        @DisplayName("at each of the eight numeric positions only a digit passes the allowlist, and every other US-ASCII character is rejected positionally rather than by the calendar guard behind it")
        void atEachNumericPositionOnlyADigitIsAccepted() {
            // An exhaustive sweep rather than a sample, so no admitted character can hide. Sweeping
            // every single-byte US-ASCII value at every numeric position also makes the enumeration
            // of individual dangerous bytes above redundant as proof and useful only as
            // documentation of why each one matters.
            //
            // Substituting a digit does not always leave a real day - a nine in the first month
            // position gives a ninety-first month - so the digit branch splits on whether the
            // resulting value names one. Either way the allowlist has admitted it: a digit is refused
            // only by the calendar guard, never positionally, and that distinction is asserted rather
            // than assumed. Refusal for a non-digit is always positional.
            for (final int position : NUMERIC_SLOT_POSITIONS) {
                for (char candidate = 0; candidate <= HIGHEST_US_ASCII_CHARACTER; candidate++) {
                    final String slot = slotWithCharacterAt(position, candidate);
                    final String description = "US-ASCII 0x" + Integer.toHexString(candidate)
                            + " at numeric slot position " + position;

                    if (candidate >= ORACLE_LOWEST_DIGIT_CHARACTER
                            && candidate <= ORACLE_HIGHEST_DIGIT_CHARACTER) {
                        if (namesARealDay(slot)) {
                            assertThat(JclCardImageBuilder.build(slot, END_DATE)).as(description)
                                    .containsExactlyElementsOf(oracleCards(slot, END_DATE));
                        } else {
                            assertThatExceptionOfType(IllegalArgumentException.class).as(description)
                                    .isThrownBy(() -> JclCardImageBuilder.build(slot, END_DATE))
                                    .withMessageContaining("day that exists")
                                    .withMessageNotContaining("position");
                        }
                    } else {
                        assertThatExceptionOfType(IllegalArgumentException.class).as(description)
                                .isThrownBy(() -> JclCardImageBuilder.build(slot, END_DATE))
                                .withMessageContaining("position " + position);
                    }
                }
            }
        }

        @Test
        @DisplayName("at each of the two hyphen positions only a hyphen is accepted, because those bytes are FILLER constants of the legacy group rather than data")
        void atEachHyphenPositionOnlyAHyphenIsAccepted() {
            for (final int position : SEPARATOR_SLOT_POSITIONS) {
                for (char candidate = 0; candidate <= HIGHEST_US_ASCII_CHARACTER; candidate++) {
                    final String slot = slotWithCharacterAt(position, candidate);
                    final String description = "US-ASCII 0x" + Integer.toHexString(candidate)
                            + " at hyphen slot position " + position;

                    if (candidate == ORACLE_HYPHEN_CHARACTER) {
                        assertThat(JclCardImageBuilder.build(slot, END_DATE)).as(description)
                                .containsExactlyElementsOf(oracleCards(slot, END_DATE));
                    } else {
                        assertThatExceptionOfType(IllegalArgumentException.class).as(description)
                                .isThrownBy(() -> JclCardImageBuilder.build(slot, END_DATE));
                    }
                }
            }
        }

        @Test
        @DisplayName("the hyphen positions are fixed: a slot that moves them, or uses a different separator, is rejected even at the right width")
        void theHyphenPositionsAreFixed() {
            final List<String> misshapedSlots = List.of(
                    "20-22-0101",  // hyphens moved one position left
                    "2022/01/01",  // a different separator entirely
                    "2022.01.01",  // a dotted separator
                    "20220101  ",  // no separators, space padded
                    "  20220101"); // no separators, space led

            for (final String misshaped : misshapedSlots) {
                assertThat(usAsciiLength(misshaped)).as("width of misshaped slot " + misshaped)
                        .isEqualTo(ORACLE_DATE_SLOT_WIDTH);
                assertThatExceptionOfType(IllegalArgumentException.class)
                        .as("misshaped slot " + misshaped)
                        .isThrownBy(() -> JclCardImageBuilder.build(misshaped, END_DATE))
                        .withMessageContaining("PARM-START-DATE");
            }
        }

        @Test
        @DisplayName("the diagnostic names the slot, the required shape and the offending one-based position, and never echoes the rejected value")
        void theDiagnosticNamesThePositionAndNeverEchoesTheRejectedValue() {
            // A rejected slot is attacker-supplied text by assumption, so it must not travel onward
            // inside a message that a caller may log or surface. The diagnostic therefore carries
            // the position and the required shape and nothing of the value itself.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JclCardImageBuilder.build(APOSTROPHE_INJECTION_SLOT, END_DATE))
                    .withMessageContaining("PARM-START-DATE")
                    .withMessageContaining(ORACLE_DATE_SLOT_FORMAT)
                    .withMessageContaining("position " + APOSTROPHE_INJECTION_POSITION)
                    .withMessageNotContaining(APOSTROPHE_INJECTION_SLOT);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JclCardImageBuilder
                            .build(slotWithCharacterAt(FIRST_SEPARATOR_SLOT_POSITION, '9'), END_DATE))
                    .withMessageContaining("PARM-START-DATE")
                    .withMessageContaining("position " + FIRST_SEPARATOR_SLOT_POSITION)
                    .withMessageNotContaining("20229");
        }

        @Test
        @DisplayName("the shape check runs after the width and encodability checks, so their own diagnostics are unchanged")
        void theShapeCheckRunsAfterTheWidthAndEncodabilityChecks() {
            // A value can fail more than one property at once. Ordering the checks from the coarsest
            // to the finest keeps each failure reported against its narrowest cause, so a nine-byte
            // misshaped value is still a width failure and a non-single-byte value is still an
            // encodability failure.
            final String shortAndMisshaped = "2022/01/0";
            assertThat(usAsciiLength(shortAndMisshaped)).as("width of the short misshaped value")
                    .isEqualTo(ORACLE_DATE_SLOT_WIDTH - 1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JclCardImageBuilder.build(shortAndMisshaped, END_DATE))
                    .withMessageContaining("10 encoded bytes")
                    .withMessageEndingWith("9");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JclCardImageBuilder.build(NON_SINGLE_BYTE_DATE, END_DATE))
                    .withMessageContaining("US-ASCII");
        }

        @Test
        @DisplayName("the positional allowlist is structural and admits an impossible calendar date, which the calendar guard behind it then refuses - so the two guards are separate and both are needed")
        void theStructuralAllowlistAdmitsWhatTheCalendarGuardThenRefuses() {
            // Each value below satisfies every positional rule and would pass a width-and-shape guard
            // untouched. Each would then be embedded in the sort include-condition on cards 11 and 12
            // and in the report parameter on card 15, producing a job whose date window names no real
            // interval, so a second guard refuses it. That the diagnostic names the calendar and not a
            // position is what proves the allowlist admitted it and the calendar guard is what caught
            // it: the two are separate checks and neither subsumes the other.
            final List<String> structurallyValidNonDates = List.of(
                    "9999-99-99",  // no such month and no such day
                    "0000-00-00",  // all zeroes
                    "2019-02-30",  // a day that month never has
                    "2019-13-01"); // a thirteenth month

            for (final String nonDate : structurallyValidNonDates) {
                assertThat(namesARealDay(nonDate))
                        .as("the value " + nonDate + " names no real day")
                        .isFalse();
                assertThatExceptionOfType(IllegalArgumentException.class)
                        .as("the structurally valid non-date " + nonDate)
                        .isThrownBy(() -> JclCardImageBuilder.build(nonDate, nonDate))
                        .withMessageContaining("day that exists")
                        .withMessageNotContaining("position")
                        .withMessageNotContaining(nonDate);
            }
        }

        @Test
        @DisplayName("the calendar guard rejects but never converts: an accepted slot reaches its card byte for byte, including a genuine leap day a lenient resolver would have moved")
        void theCalendarGuardRejectsButNeverConverts() {
            // 2020-02-29 is a real day and must survive untouched; 2019-02-29 is not, and a lenient
            // resolver would have quietly turned it into 2019-02-28 and emitted a card the caller never
            // asked for. Strict resolution refuses instead, and the accepted value is placed on the
            // card as the caller's own bytes rather than as a reformatted parse result.
            assertThat(namesARealDay(OTHER_END_DATE)).as("the leap day is real").isTrue();
            assertThat(JclCardImageBuilder.build(OTHER_START_DATE, OTHER_END_DATE))
                    .as("card sequence for a genuine leap day")
                    .containsExactlyElementsOf(oracleCards(OTHER_START_DATE, OTHER_END_DATE));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JclCardImageBuilder.build(START_DATE, "2019-02-29"))
                    .withMessageContaining("PARM-END-DATE")
                    .withMessageContaining("day that exists");
        }

        @Test
        @DisplayName("the concatenated image accessor applies the same shape validation")
        void theConcatenatedImageAccessorAppliesTheSameShapeValidation() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JclCardImageBuilder
                            .buildConcatenatedImage(APOSTROPHE_INJECTION_SLOT, END_DATE))
                    .withMessageContaining("PARM-START-DATE")
                    .withMessageContaining(ORACLE_DATE_SLOT_FORMAT);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JclCardImageBuilder
                            .buildConcatenatedImage(START_DATE, APOSTROPHE_INJECTION_SLOT))
                    .withMessageContaining("PARM-END-DATE")
                    .withMessageContaining(ORACLE_DATE_SLOT_FORMAT);
        }

        @Test
        @DisplayName("a rejected slot never reaches a card image: nothing partial is produced and a later valid submission is unaffected")
        void aRejectedSlotNeverReachesACardImage() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JclCardImageBuilder.build(APOSTROPHE_INJECTION_SLOT, END_DATE));

            assertThat(JclCardImageBuilder.build(START_DATE, END_DATE))
                    .as("card sequence built after a rejected slot")
                    .containsExactlyElementsOf(oracleCards(START_DATE, END_DATE));
        }

        @Test
        @DisplayName("the published slot format constant is the legacy work-field literal")
        void thePublishedSlotFormatConstantIsTheLegacyLiteral() {
            assertThat(JclCardImageBuilder.DATE_SLOT_FORMAT).as("published date slot format")
                    .isEqualTo(ORACLE_DATE_SLOT_FORMAT);
            assertThat(usAsciiLength(JclCardImageBuilder.DATE_SLOT_FORMAT))
                    .as("width of the published date slot format")
                    .isEqualTo(ORACLE_DATE_SLOT_WIDTH);
        }
    }

}
