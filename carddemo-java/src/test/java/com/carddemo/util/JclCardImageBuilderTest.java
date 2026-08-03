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
import java.util.Locale;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Verifies {@link JclCardImageBuilder}, which emits the seventeen fixed eighty-byte job-submission
 * card images that trigger the transaction-report batch job.
 *
 * <p>Three properties are contractual and all three are asserted on encoded bytes rather than
 * characters: the count and order of the cards, the eighty-byte width with right padding by the
 * ASCII space and by nothing else, and the presence of the terminating sentinel card, which the
 * legacy loop transmits before it stops rather than discarding. Each card is compared against its
 * own hand-written image, so a substitution that lands in the wrong column fails here rather than at
 * the queue.
 *
 * <p>The four ten-character date slots are the only variable part of the stream; the assertions
 * confirm that substituting them moves nothing else in the eighty-column frame.
 */
@DisplayName("JclCardImageBuilder :: seventeen eighty-byte job-submission card images")
class JclCardImageBuilderTest {
    private static final int ORACLE_CARD_WIDTH = 80;

    private static final int ORACLE_CARD_COUNT = 17;

    private static final int ORACLE_DATE_SLOT_WIDTH = 10;

    private static final int ORACLE_MAX_CARD_BOUND = 1000;

    private static final int ORACLE_TOTAL_IMAGE_WIDTH = 1360;

    private static final int ORACLE_DATE_PARAMETER_RECORD_WIDTH = 21;

    private static final byte ORACLE_SPACE_BYTE = 0x20;

    private static final byte ORACLE_APOSTROPHE_BYTE = 0x27;

    private static final byte ORACLE_COMMA_BYTE = 0x2C;

    private static final byte ORACLE_DIGIT_ZERO_BYTE = 0x30;

    private static final byte ORACLE_LETTER_CAPITAL_O_BYTE = 0x4F;

    private static final byte ORACLE_NULL_BYTE = 0x00;

    private static final byte ORACLE_TAB_BYTE = 0x09;

    private static final byte ORACLE_LINE_FEED_BYTE = 0x0A;

    private static final byte ORACLE_CARRIAGE_RETURN_BYTE = 0x0D;

    private static final byte ORACLE_REPLACEMENT_BYTE = 0x3F;

    private static final int BYTE_NOT_FOUND = -1;

    private static final String START_DATE = "2022-01-01";

    private static final String END_DATE = "2022-07-06";

    private static final String OTHER_START_DATE = "2019-11-30";

    private static final String OTHER_END_DATE = "2020-02-29";

    private static final String NINE_BYTE_DATE = "2022-01-0";

    private static final String ELEVEN_BYTE_DATE = "2022-01-011";

    private static final String EMPTY_DATE = "";

    private static final String NON_SINGLE_BYTE_DATE = "2022-01-0\u00e9";

    private static final String ORACLE_DATE_SLOT_FORMAT = "YYYY-MM-DD";

    private static final String APOSTROPHE_INJECTION_SLOT = "2026-01-'X";

    private static final int APOSTROPHE_INJECTION_POSITION = 9;

    private static final int[] NUMERIC_SLOT_POSITIONS = {1, 2, 3, 4, 6, 7, 9, 10};

    private static final int[] SEPARATOR_SLOT_POSITIONS = {5, 8};

    private static final int FIRST_SEPARATOR_SLOT_POSITION = 5;

    private static final int LAST_NUMERIC_SLOT_POSITION = 10;

    private static final char ORACLE_LOWEST_DIGIT_CHARACTER = '0';

    private static final char ORACLE_HIGHEST_DIGIT_CHARACTER = '9';

    private static final char ORACLE_HYPHEN_CHARACTER = '-';

    private static final char HIGHEST_US_ASCII_CHARACTER = 0x7F;

    private static final char ESCAPE_CHARACTER = 0x1B;

    private static final char DELETE_CHARACTER = 0x7F;

    private static final String CARD_01_CONTENT = "//TRNRPT00 JOB 'TRAN REPORT',CLASS=A,MSGCLASS=0,";

    private static final int CARD_01_CONTENT_WIDTH = 48;

    private static final String CARD_02_CONTENT = "// NOTIFY=&SYSUID";

    private static final int CARD_02_CONTENT_WIDTH = 17;

    private static final String COMMENT_CARD_CONTENT = "//*";

    private static final int COMMENT_CARD_CONTENT_WIDTH = 3;

    private static final String CARD_04_CONTENT = "//JOBLIB JCLLIB ORDER=('AWS.M2.CARDDEMO.PROC')";

    private static final int CARD_04_CONTENT_WIDTH = 46;

    private static final String CARD_06_CONTENT = "//STEP10 EXEC PROC=TRANREPT";

    private static final int CARD_06_CONTENT_WIDTH = 27;

    private static final String CARD_08_CONTENT = "//STEP05R.SYMNAMES DD *";

    private static final int CARD_08_CONTENT_WIDTH = 23;

    private static final String CARD_09_CONTENT = "TRAN-CARD-NUM,263,16,ZD";

    private static final int CARD_09_CONTENT_WIDTH = 23;

    private static final String CARD_10_CONTENT = "TRAN-PROC-DT,305,10,CH";

    private static final int CARD_10_CONTENT_WIDTH = 22;

    private static final String TERMINATOR_CARD_CONTENT = "/*";

    private static final int TERMINATOR_CARD_CONTENT_WIDTH = 2;

    private static final String CARD_14_CONTENT = "//STEP10R.DATEPARM DD *";

    private static final int CARD_14_CONTENT_WIDTH = 23;

    private static final String CARD_17_CONTENT = "/*EOF";

    private static final int CARD_17_CONTENT_WIDTH = 5;

    private static final String CARD_11_LEAD = "PARM-START-DATE,C'";

    private static final int CARD_11_LEAD_WIDTH = 18;

    private static final int CARD_11_TRAILER_WIDTH = 52;

    private static final int CARD_11_TRAILER_SPACE_COUNT = 51;

    private static final String CARD_12_LEAD = "PARM-END-DATE,C'";

    private static final int CARD_12_LEAD_WIDTH = 16;

    private static final int CARD_12_TRAILER_WIDTH = 54;

    private static final int CARD_12_TRAILER_SPACE_COUNT = 53;

    private static final int CARD_15_SEPARATOR_WIDTH = 1;

    private static final int CARD_15_TRAILER_SPACE_COUNT = 59;

    private static final int CARD_01_MESSAGE_CLASS_VALUE_OFFSET = 46;

    private static final int CARD_01_CONTINUATION_COMMA_OFFSET = 47;

    private static final int CARD_02_SYSTEM_USER_SYMBOL_OFFSET = 10;

    private static final int CARD_11_SLOT_OFFSET = 18;

    private static final int CARD_11_CLOSING_APOSTROPHE_OFFSET = 28;

    private static final int CARD_12_SLOT_OFFSET = 16;

    private static final int CARD_12_CLOSING_APOSTROPHE_OFFSET = 26;

    private static final int CARD_15_START_SLOT_OFFSET = 0;

    private static final int CARD_15_SEPARATOR_OFFSET = 10;

    private static final int CARD_15_END_SLOT_OFFSET = 11;

    private static int usAsciiLength(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    private static byte[] usAsciiBytes(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] usAsciiSlice(final String value, final int fromInclusive,
            final int toExclusive) {
        return Arrays.copyOfRange(usAsciiBytes(value), fromInclusive, toExclusive);
    }

    private static int indexOfByte(final byte[] bytes, final byte wanted) {
        for (int offset = 0; offset < bytes.length; offset++) {
            if (bytes[offset] == wanted) {
                return offset;
            }
        }
        return BYTE_NOT_FOUND;
    }

    private static String oracleSpaces(final int count) {
        return " ".repeat(count);
    }

    private static String card(final List<String> cards, final int oneBasedCardNumber) {
        return cards.get(oneBasedCardNumber - 1);
    }

    private static String slotWithCharacterAt(final int oneBasedPosition, final char replacement) {
        final char[] slot = START_DATE.toCharArray();
        slot[oneBasedPosition - 1] = replacement;
        final String candidate = new String(slot);

        assertThat(candidate.length()).as("character count of a single-position slot substitution")
                .isEqualTo(ORACLE_DATE_SLOT_WIDTH);
        return candidate;
    }

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

    private static String oracleConcatenatedImage(final List<String> cards) {
        final StringBuilder joined = new StringBuilder();
        for (final String cardImage : cards) {
            joined.append(cardImage);
        }
        return joined.toString();
    }

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

            assertThat(usAsciiSlice(cardTwo, CARD_02_SYSTEM_USER_SYMBOL_OFFSET,
                    CARD_02_CONTENT_WIDTH))
                    .as("system-user symbol on card 2").isEqualTo(usAsciiBytes("&SYSUID"));
            assertThat(cardTwo).as("card 2 carries the correctly spelled system-user symbol")
                    .contains("&SYSUID");

            final String transposedSymbol = "&SY" + "UID";
            assertThat(cardTwo).as("card 2 must not import the sibling member's transposed spelling")
                    .doesNotContain(transposedSymbol);
        }

        @Test
        @DisplayName("card 9 declares sixteen zoned-decimal bytes at one-based record offset 263")
        void cardNineDeclaresSixteenZonedDecimalBytesAtOffset263() {
            final List<String> cards = JclCardImageBuilder.build(START_DATE, END_DATE);
            final String cardNine = card(cards, 9);

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

    private static boolean namesARealDay(final String slotValue) {
        try {
            LocalDate.parse(slotValue, DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT)
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
                    "20-22-0101",
                    "2022/01/01",
                    "2022.01.01",
                    "20220101  ",
                    "  20220101");

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
            final List<String> structurallyValidNonDates = List.of(
                    "9999-99-99",
                    "0000-00-00",
                    "2019-02-30",
                    "2019-13-01");

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


    /**
     * Evaluates a supplier with the JVM's default locale temporarily replaced.
     *
     * <p>The build pins {@code -Duser.language=en -Duser.country=US} for the ordinary unit run, so a
     * locale defect in this contract cannot be observed by any test that does not do this. The
     * previous default is restored in a {@code finally} block, and the format category is restored
     * explicitly because {@link Locale#setDefault(Locale)} overwrites both categories.
     *
     * <p>The setting being replaced belongs to the process rather than to the test, so the enclosing
     * class declares exclusive access through {@link ResourceLock}, naming both the global resource
     * and the locale. The global one is what actually confers the guarantee: a lock on the locale
     * alone excludes only tests that claim the locale themselves, and every byte-parity assertion in
     * this class claims nothing while depending on the pinned default.
     *
     * @param <T>    the type the body produces
     * @param locale the locale to install for the duration of the call
     * @param body   the value to compute under that locale
     * @return whatever {@code body} produced
     */
    private static <T> T underLocale(final Locale locale, final Supplier<T> body) {
        final Locale previousDefault = Locale.getDefault();
        final Locale previousFormat = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(locale);
            return body.get();
        } finally {
            Locale.setDefault(previousDefault);
            Locale.setDefault(Locale.Category.FORMAT, previousFormat);
        }
    }

    /*
     * ========================================================================================
     * Locale invariance.
     * ========================================================================================
     */

    @Nested
    @DisplayName("locale invariance - the ambient default locale cannot change a card byte or a slot verdict")
    // Every test below replaces the JVM-wide default locale for the duration of one call. That is
    // process state rather than test state, so the isolation is declared here instead of being left to
    // whatever execution settings happen to be in force.
    //
    // Both locks are needed and the wider one is load bearing. A lock on the locale alone excludes
    // only tests that themselves claim the locale, and every other assertion in this class depends on
    // the pinned default while claiming nothing - so a narrow lock would leave them free to run
    // alongside the replacement and read an Arabic-Indic digit out of a card slot. The narrow lock is
    // kept beside it because it names the specific resource being written, so a future test that
    // declares a read lock on the locale interlocks correctly rather than relying on the global one
    // still being here.
    @ResourceLock(Resources.GLOBAL)
    @ResourceLock(Resources.LOCALE)
    class LocaleInvariance {

        /**
         * The two locales the continuous-integration definition re-runs the whole unit tier under,
         * followed by two that additionally carry a non-Latin default numbering system.
         *
         * <p>The first two are the configured gate. The second two are the stronger cases: a locale
         * whose numbering system is Arabic-Indic or Thai is the one under which a formatter that
         * resolved its decimal style from ambient state would read or write digits this eighty-column
         * frame cannot carry, so including them proves the invariance rather than merely sampling it.
         */
        @ParameterizedTest(name = "locale = {0}")
        @ValueSource(strings = {"tr-TR", "ar-EG", "ar-EG-u-nu-arab", "th-TH-u-nu-thai"})
        @DisplayName("the four date slots stay ten US-ASCII bytes and every card stays eighty US-ASCII bytes")
        void theSlotsAndCardsAreUnchangedUnderAHostileDefaultLocale(final String languageTag) {
            final Locale hostile = Locale.forLanguageTag(languageTag);

            final List<String> cards =
                    underLocale(hostile, () -> JclCardImageBuilder.build(START_DATE, END_DATE));

            assertThat(cards).as("produced card count under " + languageTag)
                    .hasSize(ORACLE_CARD_COUNT);
            for (int cardNumber = 1; cardNumber <= ORACLE_CARD_COUNT; cardNumber++) {
                assertThat(usAsciiLength(card(cards, cardNumber)))
                        .as("encoded width of card " + cardNumber + " under " + languageTag)
                        .isEqualTo(ORACLE_CARD_WIDTH);
            }

            final byte[] startOnCardEleven = usAsciiSlice(card(cards, 11), CARD_11_SLOT_OFFSET,
                    CARD_11_CLOSING_APOSTROPHE_OFFSET);
            final byte[] endOnCardTwelve = usAsciiSlice(card(cards, 12), CARD_12_SLOT_OFFSET,
                    CARD_12_CLOSING_APOSTROPHE_OFFSET);
            final byte[] startOnCardFifteen = usAsciiSlice(card(cards, 15),
                    CARD_15_START_SLOT_OFFSET, CARD_15_SEPARATOR_OFFSET);
            final byte[] endOnCardFifteen = usAsciiSlice(card(cards, 15), CARD_15_END_SLOT_OFFSET,
                    CARD_15_END_SLOT_OFFSET + ORACLE_DATE_SLOT_WIDTH);

            for (final byte[] slot : List.of(startOnCardEleven, endOnCardTwelve, startOnCardFifteen,
                    endOnCardFifteen)) {
                assertThat(slot.length).as("encoded slot width under " + languageTag)
                        .isEqualTo(ORACLE_DATE_SLOT_WIDTH);
            }

            assertThat(startOnCardEleven).as("card 11 start-date slot under " + languageTag)
                    .isEqualTo(usAsciiBytes(START_DATE));
            assertThat(endOnCardTwelve).as("card 12 end-date slot under " + languageTag)
                    .isEqualTo(usAsciiBytes(END_DATE));
            assertThat(startOnCardFifteen).as("card 15 start-date slot under " + languageTag)
                    .isEqualTo(usAsciiBytes(START_DATE));
            assertThat(endOnCardFifteen).as("card 15 end-date slot under " + languageTag)
                    .isEqualTo(usAsciiBytes(END_DATE));
        }

        @ParameterizedTest(name = "locale = {0}")
        @ValueSource(strings = {"tr-TR", "ar-EG", "ar-EG-u-nu-arab", "th-TH-u-nu-thai"})
        @DisplayName("the whole seventeen-card sequence is byte identical to the sequence built under the pinned default")
        void theWholeSequenceIsByteIdenticalUnderAHostileDefaultLocale(final String languageTag) {
            final Locale hostile = Locale.forLanguageTag(languageTag);

            final List<String> underHostileLocale =
                    underLocale(hostile, () -> JclCardImageBuilder.build(START_DATE, END_DATE));

            assertThat(underHostileLocale)
                    .as("the card sequence must not depend on the host's locale")
                    .containsExactlyElementsOf(oracleCards(START_DATE, END_DATE));
            assertThat(underLocale(hostile,
                    () -> JclCardImageBuilder.buildConcatenatedImage(START_DATE, END_DATE)))
                    .as("the concatenated image must not depend on the host's locale")
                    .isEqualTo(oracleConcatenatedImage(oracleCards(START_DATE, END_DATE)));
        }

        @ParameterizedTest(name = "locale = {0}")
        @ValueSource(strings = {"tr-TR", "ar-EG", "ar-EG-u-nu-arab", "th-TH-u-nu-thai"})
        @DisplayName("the calendar verdict is a property of the value rather than of the environment: a real day is still accepted and an impossible one still refused")
        void theCalendarVerdictIsUnchangedUnderAHostileDefaultLocale(final String languageTag) {
            final Locale hostile = Locale.forLanguageTag(languageTag);
            final String realLeapDay = "2020-02-29";
            final String impossibleLeapDay = "2023-02-29";

            assertThat(namesARealDay(realLeapDay))
                    .as("the oracle must agree that the accepted value is a real day").isTrue();
            assertThat(namesARealDay(impossibleLeapDay))
                    .as("the oracle must agree that the refused value is not a real day").isFalse();

            assertThat(underLocale(hostile,
                    () -> JclCardImageBuilder.build(realLeapDay, realLeapDay)))
                    .as("a real day must still be accepted under " + languageTag)
                    .containsExactlyElementsOf(oracleCards(realLeapDay, realLeapDay));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> underLocale(hostile,
                            () -> JclCardImageBuilder.build(impossibleLeapDay, END_DATE)))
                    .as("an impossible day must still be refused under " + languageTag)
                    .withMessageContaining("PARM-START-DATE")
                    .withMessageContaining("day that exists");
        }

        @ParameterizedTest(name = "locale = {0}")
        @ValueSource(strings = {"tr-TR", "ar-EG", "ar-EG-u-nu-arab", "th-TH-u-nu-thai"})
        @DisplayName("a slot carrying that locale's own digits is refused, because the frame admits ASCII digits and nothing else")
        void aSlotCarryingLocaleSpecificDigitsIsRefused(final String languageTag) {
            final Locale hostile = Locale.forLanguageTag(languageTag);
            // The same instant rendered with whatever digits the locale's numbering system uses. Under
            // the two Latin-digit locales this is the ordinary ASCII rendering and is accepted; under
            // the two that carry their own numbering system it is not, and must be refused rather than
            // silently written into a slot whose width is counted in bytes.
            final String localisedDigits = underLocale(hostile,
                    () -> DateTimeFormatter.ofPattern("uuuu-MM-dd", hostile)
                            .format(LocalDate.of(2022, 1, 1)));

            if (localisedDigits.equals(START_DATE)) {
                assertThat(underLocale(hostile,
                        () -> JclCardImageBuilder.build(localisedDigits, END_DATE)))
                        .as("an ASCII rendering must still be accepted under " + languageTag)
                        .containsExactlyElementsOf(oracleCards(START_DATE, END_DATE));
                return;
            }

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> underLocale(hostile,
                            () -> JclCardImageBuilder.build(localisedDigits, END_DATE)))
                    .as("a non-ASCII digit must never reach an eighty-column card")
                    .withMessageContaining("PARM-START-DATE");
        }
    }

}
