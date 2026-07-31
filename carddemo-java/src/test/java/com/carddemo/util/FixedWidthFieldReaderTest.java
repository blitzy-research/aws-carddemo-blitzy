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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for {@link FixedWidthFieldReader}, the offset-slicing primitive on which all eleven
 * CardDemo record mappers are built and the exact inverse operation that reassembles a record image.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>Fixed-width layout knowledge lives exclusively in the production utility layer, so this class
 * is the only proof that offsets are read and written correctly, that nothing is ever trimmed, and
 * that a slice cannot silently walk off the end of a record. The reflection budget for the
 * production tree is zero, which is precisely why the eleven mappers are hand-written over explicit
 * offsets rather than driven by an annotation-based mapping framework; nothing here uses reflection
 * either, so the test cannot undermine the constraint it exists to protect.
 *
 * <h2>Every expectation is an independent oracle</h2>
 *
 * <p>No expected value in this class is produced by calling the class under test. Field offsets and
 * widths were derived from the verified copybook and file-section declarations, and every literal
 * record image below was hand-assembled field by field from the measured contents of the ASCII
 * sample data. The only helpers used to build an expectation are the JDK's own
 * {@link String#repeat(int)} and {@link String#substring(int, int)}, which generate literal pad runs
 * and literal sub-ranges rather than compute anything. A round trip is never the sole assertion:
 * where a built image is compared with a fixture image, both sides are independently known.
 *
 * <h2>Byte-exact, never trimmed</h2>
 *
 * <p>Trailing and interior spaces are contractual data in this estate, so every comparison here is
 * byte-exact. Nothing is trimmed, stripped, case-folded, whitespace-collapsed or normalised on
 * either side of an assertion, and every width assertion measures encoded bytes through
 * {@link StandardCharsets#US_ASCII} rather than counting characters.
 *
 * <h2>Divergences this class pins down</h2>
 *
 * <p>Four deliberate divergences between faithful legacy behaviour and idiomatic Java are recorded
 * in the module's decision log and are exercised here rather than merely described: a malformed
 * record image surfaces as {@link IllegalArgumentException} rather than as any domain exception,
 * because a short record has no legacy antecedent at all - the legacy records are fixed length by
 * construction; the sample data's non-uniform filler bytes are resolved by emitting space filler
 * uniformly on write while comparing only the mapped data prefix on read; slices are never trimmed;
 * and US-ASCII is named explicitly at every boundary between characters and bytes.
 *
 * <h2>Scope</h2>
 *
 * <p>This is a pure in-process unit test. It starts no application context, opens no database, no
 * queue, no network connection and no file: every record image it uses is a literal in this file, so
 * the test is hermetic and its oracle is visible in review.
 *
 * <p>Provenance: the legacy estate was read at commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Legacy sources are cited by member and
 * field name, never transcribed.
 */
@DisplayName("FixedWidthFieldReader")
class FixedWidthFieldReaderTest {

    // =================================================================================
    // Layout names. Only diagnostics consume these, so they are free-form labels rather
    // than legacy identifiers with meaning.
    // =================================================================================

    private static final String ACCOUNT = "ACCOUNT";
    private static final String TRANSACTION = "TRANSACTION";
    private static final String CARD = "CARD";
    private static final String CARD_XREF = "CARD-XREF";
    private static final String DISCLOSURE_GROUP = "DISCLOSURE-GROUP";
    private static final String CATEGORY_BALANCE = "TRAN-CAT-BALANCE";

    // =================================================================================
    // Record widths in encoded bytes, each the sum of its own layout's field widths.
    // Only the layouts a test below actually slices are named here; a mapper is the
    // authority for its own layout and declares its own constants.
    // =================================================================================

    /** 11 + 1 + 12 + 12 + 12 + 10 + 10 + 10 + 12 + 12 + 10 + 10 + 178 = 300. */
    private static final int ACCOUNT_WIDTH = 300;

    /** Leading key of the account file-section split, an 11-digit account identifier. */
    private static final int ACCOUNT_KEY_LENGTH = 11;

    /** Data remainder of the account file-section split. 11 + 289 = 300. */
    private static final int ACCOUNT_DATA_LENGTH = 289;

    /** 16 + 2 + 4 + 10 + 100 + 11 + 9 + 50 + 50 + 10 + 16 + 26 + 26 + 20 = 350. */
    private static final int TRANSACTION_WIDTH = 350;

    /** Leading data prefix of the transaction file-section split. */
    private static final int TRANSACTION_DATA_PREFIX_LENGTH = 304;

    /** Processing timestamp that follows the data prefix. */
    private static final int TRANSACTION_PROC_TS_LENGTH = 26;

    /** Trailing filler that closes the transaction record. 304 + 26 + 20 = 350. */
    private static final int TRANSACTION_FILLER_LENGTH = 20;

    /** 16 + 11 + 3 + 50 + 10 + 1 + 59 = 150. */
    private static final int CARD_WIDTH = 150;

    /**
     * Mapped width of a cross-reference record as the sample data carries it: 16 + 9 + 11 = 36. The
     * declared cluster record length is 50, because the copybook closes the record with a 14-byte
     * filler that the sample file simply does not carry.
     */
    private static final int CARD_XREF_DATA_WIDTH = 36;

    /** Declared cross-reference cluster record length, 36 mapped bytes plus 14 filler bytes. */
    private static final int CARD_XREF_CLUSTER_WIDTH = 50;

    /** Filler run that separates the mapped cross-reference prefix from the cluster width. */
    private static final int CARD_XREF_FILLER_LENGTH = 14;

    /** 10 + 2 + 4 + 6 + 28 = 50. */
    private static final int DISCLOSURE_GROUP_WIDTH = 50;

    /** Mapped prefix of a disclosure-group record, everything ahead of its filler run. */
    private static final int DISCLOSURE_GROUP_DATA_LENGTH = 22;

    /** Filler run that closes a disclosure-group record. 22 + 28 = 50. */
    private static final int DISCLOSURE_GROUP_FILLER_LENGTH = 28;

    /** 11 + 2 + 4 + 11 + 22 = 50. */
    private static final int CATEGORY_BALANCE_WIDTH = 50;

    /** Mapped prefix of a category-balance record, everything ahead of its filler run. */
    private static final int CATEGORY_BALANCE_DATA_LENGTH = 28;

    /**
     * Byte value of the line terminator that ends every row of the sample files. It is a record
     * separator and never record content, which is why every stride below is one greater than its
     * record width.
     */
    private static final String LINE_TERMINATOR = "\n";

    // =================================================================================
    // Hand-assembled record images. Each is built field by field from the measured
    // contents of the named sample file, with the offset and length of every field
    // stated beside it, so the oracle is auditable without leaving this file. The
    // repeat calls generate literal pad runs; they compute nothing.
    // =================================================================================

    /**
     * Row 0 of the account sample data, 300 bytes.
     *
     * <p>Two positions in this row are a recorded anomaly of the sample data and are reproduced
     * exactly as measured rather than repaired: on all fifty rows the ten bytes at offset 102, the
     * postal-code position, hold a group-shaped value, and the ten bytes at offset 112, the
     * group-identifier position, hold spaces. Slicing is strictly positional, so neither is swapped,
     * inferred or corrected here.
     */
    private static final String ACCOUNT_IMAGE_ROW_0 =
            "00000000001"                 // ACCT-ID                 offset   0, length  11
                    + "Y"                 // ACCT-ACTIVE-STATUS      offset  11, length   1
                    + "00000001940{"      // ACCT-CURR-BAL           offset  12, length  12
                    + "00000020200{"      // ACCT-CREDIT-LIMIT       offset  24, length  12
                    + "00000010200{"      // ACCT-CASH-CREDIT-LIMIT  offset  36, length  12
                    + "2014-11-20"        // ACCT-OPEN-DATE          offset  48, length  10
                    + "2025-05-20"        // ACCT-EXPIRAION-DATE     offset  58, length  10
                    + "2025-05-20"        // ACCT-REISSUE-DATE       offset  68, length  10
                    + "00000000000{"      // ACCT-CURR-CYC-CREDIT    offset  78, length  12
                    + "00000000000{"      // ACCT-CURR-CYC-DEBIT     offset  90, length  12
                    + "A000000000"        // ACCT-ADDR-ZIP           offset 102, length  10
                    + spaces(10)          // ACCT-GROUP-ID           offset 112, length  10
                    + spaces(178);        // FILLER                  offset 122, length 178

    /** Row 1 of the account sample data, 300 bytes, used to prove stride addressing selects a row. */
    private static final String ACCOUNT_IMAGE_ROW_1 =
            "00000000002"                 // ACCT-ID                 offset   0, length  11
                    + "Y"                 // ACCT-ACTIVE-STATUS      offset  11, length   1
                    + "00000001580{"      // ACCT-CURR-BAL           offset  12, length  12
                    + "00000020200{"      // ACCT-CREDIT-LIMIT       offset  24, length  12
                    + "00000010200{"      // ACCT-CASH-CREDIT-LIMIT  offset  36, length  12
                    + "2014-11-20"        // ACCT-OPEN-DATE          offset  48, length  10
                    + "2025-05-20"        // ACCT-EXPIRAION-DATE     offset  58, length  10
                    + "2025-05-20"        // ACCT-REISSUE-DATE       offset  68, length  10
                    + "00000000000{"      // ACCT-CURR-CYC-CREDIT    offset  78, length  12
                    + "00000000000{"      // ACCT-CURR-CYC-DEBIT     offset  90, length  12
                    + "A000000000"        // ACCT-ADDR-ZIP           offset 102, length  10
                    + spaces(10)          // ACCT-GROUP-ID           offset 112, length  10
                    + spaces(178);        // FILLER                  offset 122, length 178

    /**
     * Row 0 of the daily-transaction sample data, 350 bytes. Its processing timestamp is 26 spaces,
     * as it is on all three hundred rows of that file, and its trailing filler is 20 spaces.
     */
    private static final String TRANSACTION_IMAGE_ROW_0 =
            "0000000000683580"                              // TRAN-ID            off   0, len  16
                    + "01"                                  // TRAN-TYPE-CD       off  16, len   2
                    + "0001"                                // TRAN-CAT-CD        off  18, len   4
                    + "POS TERM  "                          // TRAN-SOURCE        off  22, len  10
                    + "Purchase at Abshire-Lowe" + spaces(76)     // TRAN-DESC     off  32, len 100
                    + "0000005047G"                         // TRAN-AMT           off 132, len  11
                    + "800000000"                           // TRAN-MERCHANT-ID   off 143, len   9
                    + "Abshire-Lowe" + spaces(38)           // MERCHANT-NAME      off 152, len  50
                    + "North Enoshaven" + spaces(35)        // MERCHANT-CITY      off 202, len  50
                    + "72112     "                          // MERCHANT-ZIP       off 252, len  10
                    + "4859452612877065"                    // TRAN-CARD-NUM      off 262, len  16
                    + "2022-06-10 19:27:53.000000"          // TRAN-ORIG-TS       off 278, len  26
                    + spaces(26)                            // TRAN-PROC-TS       off 304, len  26
                    + spaces(20);                           // FILLER             off 330, len  20

    /** Row 0 of the card sample data, 150 bytes, whose 59-byte filler run is spaces. */
    private static final String CARD_IMAGE_ROW_0 =
            "0500024453765740"                          // CARD-NUM              off   0, len  16
                    + "00000000050"                     // CARD-ACCT-ID          off  16, len  11
                    + "747"                             // CARD-CVV-CD           off  27, len   3
                    + "Aniya Von" + spaces(41)          // CARD-EMBOSSED-NAME    off  30, len  50
                    + "2023-03-09"                      // CARD-EXPIRAION-DATE   off  80, len  10
                    + "Y"                               // CARD-ACTIVE-STATUS    off  90, len   1
                    + spaces(59);                       // FILLER                off  91, len  59

    /** Row 0 of the cross-reference sample data, 36 bytes with no filler run at all. */
    private static final String CARD_XREF_IMAGE_ROW_0 =
            "0500024453765740"                          // XREF-CARD-NUM         off   0, len  16
                    + "000000050"                       // XREF-CUST-ID          off  16, len   9
                    + "00000000050";                    // XREF-ACCT-ID          off  25, len  11

    /** Row 1 of the cross-reference sample data, 36 bytes. */
    private static final String CARD_XREF_IMAGE_ROW_1 =
            "0683586198171516" + "000000027" + "00000000027";

    /** Row 2 of the cross-reference sample data, 36 bytes. */
    private static final String CARD_XREF_IMAGE_ROW_2 =
            "0923877193247330" + "000000002" + "00000000002";

    /**
     * The disclosure-group row whose key is the padded ten-character default value, 50 bytes. Its
     * 28-byte filler run is ASCII zero rather than space, which is the recorded filler anomaly.
     */
    private static final String DISCLOSURE_GROUP_DEFAULT_IMAGE =
            "DEFAULT   "                    // DIS-ACCT-GROUP-ID     offset  0, length 10
                    + "01"                  // DIS-TRAN-TYPE-CD      offset 10, length  2
                    + "0001"                // DIS-TRAN-CAT-CD       offset 12, length  4
                    + "00150{"              // DIS-INT-RATE          offset 16, length  6
                    + zeros(28);            // FILLER                offset 22, length 28

    /** The disclosure-group row whose key is the padded ten-character zero-rate value, 50 bytes. */
    private static final String DISCLOSURE_GROUP_ZEROAPR_IMAGE =
            "ZEROAPR   " + "01" + "0001" + "00000{" + zeros(28);

    /** Row 0 of the category-balance sample data, 50 bytes, whose 22-byte filler run is ASCII zero. */
    private static final String CATEGORY_BALANCE_IMAGE_ROW_0 =
            "00000000001"                   // TRANCAT-ACCT-ID       offset  0, length 11
                    + "01"                  // TRANCAT-TYPE-CD      offset 11, length  2
                    + "0001"                // TRANCAT-CD           offset 13, length  4
                    + "0000000000{"         // TRAN-CAT-BAL         offset 17, length 11
                    + zeros(22);            // FILLER               offset 28, length 22

    /**
     * Generates a literal run of spaces. A pad run, not a computation: the JDK produces the
     * characters and the class under test is not involved.
     *
     * @param count number of spaces
     * @return a string of exactly {@code count} spaces
     */
    private static String spaces(int count) {
        return " ".repeat(count);
    }

    /**
     * Generates a literal run of ASCII zero characters, the filler byte the reference-table sample
     * files carry.
     *
     * @param count number of zero characters
     * @return a string of exactly {@code count} zero characters
     */
    private static String zeros(int count) {
        return "0".repeat(count);
    }

    /**
     * Measures a value in encoded bytes under US-ASCII, which is the only width authority this test
     * recognises. A character count is never used in its place.
     *
     * @param value the value to measure
     * @return the encoded byte length
     */
    private static int encodedBytes(String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    // =================================================================================
    // Subjects under test. These build the object being exercised; they never produce an
    // expected value.
    // =================================================================================

    /**
     * Builds a reader over account row 0.
     *
     * @return a reader over the 300-byte account image
     */
    private static FixedWidthFieldReader accountRow0() {
        return FixedWidthFieldReader.of(ACCOUNT, ACCOUNT_IMAGE_ROW_0, ACCOUNT_WIDTH);
    }

    /**
     * Builds a reader over daily-transaction row 0.
     *
     * @return a reader over the 350-byte transaction image
     */
    private static FixedWidthFieldReader transactionRow0() {
        return FixedWidthFieldReader.of(TRANSACTION, TRANSACTION_IMAGE_ROW_0, TRANSACTION_WIDTH);
    }

    /**
     * Builds a reader over cross-reference row 0 at its mapped 36-byte width.
     *
     * @return a reader over the 36-byte cross-reference image
     */
    private static FixedWidthFieldReader cardXrefRow0() {
        return FixedWidthFieldReader.of(CARD_XREF, CARD_XREF_IMAGE_ROW_0, CARD_XREF_DATA_WIDTH);
    }

    /**
     * Builds a reader over the disclosure-group row keyed by the padded default value.
     *
     * @return a reader over the 50-byte disclosure-group image
     */
    private static FixedWidthFieldReader disclosureGroupDefault() {
        return FixedWidthFieldReader.of(DISCLOSURE_GROUP, DISCLOSURE_GROUP_DEFAULT_IMAGE,
                DISCLOSURE_GROUP_WIDTH);
    }

    @Nested
    @DisplayName("hand-assembled oracle")
    class HandAssembledOracle {

        @Test
        @DisplayName("every literal record image in this test has its layout's encoded width")
        void everyLiteralImageHasItsDeclaredEncodedWidth() {
            // Guards the oracle itself: if a literal above were mis-assembled, every slice
            // expectation derived from it would be wrong in the same direction and the error would
            // hide. Widths are measured in encoded bytes, never in characters.
            assertThat(encodedBytes(ACCOUNT_IMAGE_ROW_0)).isEqualTo(300);
            assertThat(encodedBytes(ACCOUNT_IMAGE_ROW_1)).isEqualTo(300);
            assertThat(encodedBytes(TRANSACTION_IMAGE_ROW_0)).isEqualTo(350);
            assertThat(encodedBytes(CARD_IMAGE_ROW_0)).isEqualTo(150);
            assertThat(encodedBytes(CARD_XREF_IMAGE_ROW_0)).isEqualTo(36);
            assertThat(encodedBytes(CARD_XREF_IMAGE_ROW_1)).isEqualTo(36);
            assertThat(encodedBytes(CARD_XREF_IMAGE_ROW_2)).isEqualTo(36);
            assertThat(encodedBytes(DISCLOSURE_GROUP_DEFAULT_IMAGE)).isEqualTo(50);
            assertThat(encodedBytes(DISCLOSURE_GROUP_ZEROAPR_IMAGE)).isEqualTo(50);
            assertThat(encodedBytes(CATEGORY_BALANCE_IMAGE_ROW_0)).isEqualTo(50);
        }

        @Test
        @DisplayName("declared field widths sum to the declared record width")
        void declaredFieldWidthsSumToTheRecordWidth() {
            // Account layout: identifier, status, three amounts, three dates, two cycle amounts,
            // postal code, group identifier, filler.
            assertThat(11 + 1 + 12 + 12 + 12 + 10 + 10 + 10 + 12 + 12 + 10 + 10 + 178)
                    .isEqualTo(ACCOUNT_WIDTH);
            // Transaction layout, in copybook order.
            assertThat(16 + 2 + 4 + 10 + 100 + 11 + 9 + 50 + 50 + 10 + 16 + 26 + 26 + 20)
                    .isEqualTo(TRANSACTION_WIDTH);
            assertThat(16 + 11 + 3 + 50 + 10 + 1 + 59).isEqualTo(CARD_WIDTH);
            assertThat(16 + 9 + 11).isEqualTo(CARD_XREF_DATA_WIDTH);
            assertThat(CARD_XREF_DATA_WIDTH + CARD_XREF_FILLER_LENGTH)
                    .isEqualTo(CARD_XREF_CLUSTER_WIDTH);
            assertThat(10 + 2 + 4 + 6 + 28).isEqualTo(DISCLOSURE_GROUP_WIDTH);
            assertThat(11 + 2 + 4 + 11 + 22).isEqualTo(CATEGORY_BALANCE_WIDTH);
        }

        @Test
        @DisplayName("the line terminator is one byte and is not part of any record")
        void theLineTerminatorIsOneByteAndIsNotRecordContent() {
            assertThat(encodedBytes(LINE_TERMINATOR)).isEqualTo(1);
            assertThat(LINE_TERMINATOR.charAt(0)).isEqualTo('\n');
            // A record image therefore never ends with it.
            assertThat(ACCOUNT_IMAGE_ROW_0.endsWith(LINE_TERMINATOR)).isFalse();
            assertThat(TRANSACTION_IMAGE_ROW_0.endsWith(LINE_TERMINATOR)).isFalse();
        }
    }

    @Nested
    @DisplayName("record image construction")
    class RecordImageConstruction {

        @Test
        @DisplayName("an image of exactly the declared width is accepted and reported unchanged")
        void anImageOfExactlyTheDeclaredWidthIsAccepted() {
            FixedWidthFieldReader reader = accountRow0();

            assertThat(reader.artefact()).isEqualTo("ACCOUNT");
            assertThat(reader.recordWidth()).isEqualTo(300);
            assertThat(reader.image()).isEqualTo(ACCOUNT_IMAGE_ROW_0);
            assertThat(encodedBytes(reader.image())).isEqualTo(300);
        }

        @Test
        @DisplayName("an image narrower than the declared width names both widths and is refused")
        void anImageNarrowerThanTheDeclaredWidthIsRefused() {
            // One byte short of the layout: a fixed-width record is never padded to fit.
            String tooShort = ACCOUNT_IMAGE_ROW_0.substring(0, ACCOUNT_WIDTH - 1);
            assertThat(encodedBytes(tooShort)).isEqualTo(299);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FixedWidthFieldReader.of(ACCOUNT, tooShort, ACCOUNT_WIDTH))
                    .withMessageContaining("ACCOUNT")
                    .withMessageContaining("must be exactly 300 encoded bytes in US-ASCII")
                    .withMessageContaining("the supplied image is 299 encoded bytes")
                    .withMessageContaining("never padded or truncated on input");
        }

        @Test
        @DisplayName("an unstripped line terminator overshoots by one byte and is named as such")
        void anUnstrippedLineTerminatorIsRefusedAndDiagnosed() {
            // This is the mistake a caller reading a newline-terminated file actually makes, so the
            // diagnostic has to point at it rather than merely report a width mismatch.
            String withTerminator = ACCOUNT_IMAGE_ROW_0 + LINE_TERMINATOR;
            assertThat(encodedBytes(withTerminator)).isEqualTo(301);

            assertThatIllegalArgumentException()
                    .isThrownBy(() ->
                            FixedWidthFieldReader.of(ACCOUNT, withTerminator, ACCOUNT_WIDTH))
                    .withMessageContaining("must be exactly 300 encoded bytes in US-ASCII")
                    .withMessageContaining("the supplied image is 301 encoded bytes")
                    .withMessageContaining("unstripped 0x0A line terminator");
        }

        @Test
        @DisplayName("the byte factory applies the same width rule as the string factory")
        void theByteFactoryAppliesTheSameWidthRule() {
            byte[] exact = ACCOUNT_IMAGE_ROW_0.getBytes(StandardCharsets.US_ASCII);
            byte[] tooLong = (ACCOUNT_IMAGE_ROW_0 + LINE_TERMINATOR)
                    .getBytes(StandardCharsets.US_ASCII);

            assertThat(FixedWidthFieldReader.of(ACCOUNT, exact, ACCOUNT_WIDTH).image())
                    .isEqualTo(ACCOUNT_IMAGE_ROW_0);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FixedWidthFieldReader.of(ACCOUNT, tooLong, ACCOUNT_WIDTH))
                    .withMessageContaining("the supplied image is 301 encoded bytes");
        }

        @Test
        @DisplayName("a character US-ASCII cannot represent is refused, never transcoded")
        void aNonRepresentableCharacterIsRefused() {
            // Encoding first and inspecting afterwards could not tell a substituted question mark
            // from one that was genuinely present, so the refusal has to come first. The character
            // count stays at 300 here, which is exactly why a character count is not a width.
            String withLatinCharacter = "\u00E9" + ACCOUNT_IMAGE_ROW_0.substring(1);
            String withCjkCharacter = "\u4E2D" + ACCOUNT_IMAGE_ROW_0.substring(1);

            assertThatIllegalArgumentException()
                    .isThrownBy(() ->
                            FixedWidthFieldReader.of(ACCOUNT, withLatinCharacter, ACCOUNT_WIDTH))
                    .withMessageContaining("US-ASCII cannot represent")
                    .withMessageContaining("at index 0")
                    .withMessageContaining("0xE9");
            assertThatIllegalArgumentException()
                    .isThrownBy(() ->
                            FixedWidthFieldReader.of(ACCOUNT, withCjkCharacter, ACCOUNT_WIDTH))
                    .withMessageContaining("0x4E2D");
        }

        @Test
        @DisplayName("a byte above 0x7F is refused rather than reinterpreted")
        void aNonAsciiByteIsRefused() {
            // A high byte cannot have come from any of the ASCII sample files, so its presence means
            // the caller holds data in another encoding and must convert it before slicing.
            byte[] withHighByte = ACCOUNT_IMAGE_ROW_0.getBytes(StandardCharsets.US_ASCII);
            withHighByte[11] = (byte) 0x80;

            assertThatIllegalArgumentException()
                    .isThrownBy(() ->
                            FixedWidthFieldReader.of(ACCOUNT, withHighByte, ACCOUNT_WIDTH))
                    .withMessageContaining("non-ASCII byte at index 11")
                    .withMessageContaining("0x80")
                    .withMessageContaining("7-bit ASCII");
        }

        @Test
        @DisplayName("a blank layout name is refused so no diagnostic is anonymous")
        void aBlankLayoutNameIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() ->
                            FixedWidthFieldReader.of("   ", ACCOUNT_IMAGE_ROW_0, ACCOUNT_WIDTH))
                    .withMessageContaining("artefact must name the record layout");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FixedWidthFieldReader.builder("", CARD_WIDTH))
                    .withMessageContaining("no non-space character");
        }

        @ParameterizedTest(name = "recordWidth={0}")
        @CsvSource({"0", "-1", "-300"})
        @DisplayName("a record width below one byte is refused")
        void aRecordWidthBelowOneByteIsRefused(int recordWidth) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() ->
                            FixedWidthFieldReader.of(ACCOUNT, ACCOUNT_IMAGE_ROW_0, recordWidth))
                    .withMessageContaining("record width must be at least 1 encoded byte")
                    .withMessageContaining("recordWidth=" + recordWidth);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FixedWidthFieldReader.builder(CARD, recordWidth))
                    .withMessageContaining("record width must be at least 1 encoded byte");
        }

        @Test
        @DisplayName("a null argument raises NullPointerException with a naming message")
        void aNullArgumentIsRefused() {
            String absentArtefact = null;
            String absentImage = null;
            byte[] absentBytes = null;
            byte[] absentBuffer = null;
            String absentFieldName = null;
            String absentValue = null;
            FixedWidthFieldReader reader = accountRow0();
            FixedWidthFieldReader.Builder builder =
                    FixedWidthFieldReader.builder(CARD, CARD_WIDTH);

            assertThatNullPointerException()
                    .isThrownBy(() -> FixedWidthFieldReader.of(absentArtefact,
                            ACCOUNT_IMAGE_ROW_0, ACCOUNT_WIDTH))
                    .withMessage("artefact must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() ->
                            FixedWidthFieldReader.of(ACCOUNT, absentImage, ACCOUNT_WIDTH))
                    .withMessage("recordImage must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() ->
                            FixedWidthFieldReader.of(ACCOUNT, absentBytes, ACCOUNT_WIDTH))
                    .withMessage("recordImage must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() ->
                            FixedWidthFieldReader.of(ACCOUNT, absentBuffer, 0, ACCOUNT_WIDTH))
                    .withMessage("buffer must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> reader.field(absentFieldName, 0, ACCOUNT_KEY_LENGTH))
                    .withMessage("fieldName must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> reader.fieldBytes(absentFieldName, 0, ACCOUNT_KEY_LENGTH))
                    .withMessage("fieldName must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> builder.putAlphanumeric("CARD-NUM", 0, 16, absentValue))
                    .withMessage("value must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> builder.putNumeric("CARD-ACCT-ID", 16, 11, absentValue))
                    .withMessage("value must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> builder.putAlphanumeric(absentFieldName, 0, 16, "0"))
                    .withMessage("fieldName must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> FixedWidthFieldReader.encodedLength(absentValue))
                    .withMessage("value must not be null");
        }

        @Test
        @DisplayName("the byte-range factory selects one record from a multi-record buffer")
        void theByteRangeFactorySelectsOneRecordFromABuffer() {
            // Stride is the record width plus the one-byte terminator, so record i starts at
            // i * stride and the terminator is left behind rather than sliced.
            int stride = CARD_XREF_DATA_WIDTH + 1;
            byte[] buffer = (CARD_XREF_IMAGE_ROW_0 + LINE_TERMINATOR
                    + CARD_XREF_IMAGE_ROW_1 + LINE_TERMINATOR
                    + CARD_XREF_IMAGE_ROW_2 + LINE_TERMINATOR)
                    .getBytes(StandardCharsets.US_ASCII);
            assertThat(buffer).hasSize(3 * stride);

            assertThat(FixedWidthFieldReader
                    .of(CARD_XREF, buffer, 0, CARD_XREF_DATA_WIDTH).image())
                    .isEqualTo(CARD_XREF_IMAGE_ROW_0);
            assertThat(FixedWidthFieldReader
                    .of(CARD_XREF, buffer, stride, CARD_XREF_DATA_WIDTH).image())
                    .isEqualTo(CARD_XREF_IMAGE_ROW_1);
            assertThat(FixedWidthFieldReader
                    .of(CARD_XREF, buffer, 2 * stride, CARD_XREF_DATA_WIDTH).image())
                    .isEqualTo(CARD_XREF_IMAGE_ROW_2);
            // The selected record carries no terminator, so its own width check passes.
            assertThat(FixedWidthFieldReader
                    .of(CARD_XREF, buffer, stride, CARD_XREF_DATA_WIDTH).recordWidth())
                    .isEqualTo(36);
        }

        @Test
        @DisplayName("the byte-range factory addresses a 300-byte record at its 301-byte stride")
        void theByteRangeFactoryAddressesTheAccountStride() {
            int stride = ACCOUNT_WIDTH + 1;
            byte[] buffer = (ACCOUNT_IMAGE_ROW_0 + LINE_TERMINATOR
                    + ACCOUNT_IMAGE_ROW_1 + LINE_TERMINATOR)
                    .getBytes(StandardCharsets.US_ASCII);
            assertThat(buffer).hasSize(2 * stride);
            assertThat(stride).isEqualTo(301);

            assertThat(FixedWidthFieldReader.of(ACCOUNT, buffer, 0, ACCOUNT_WIDTH)
                    .field("ACCT-ID", 0, ACCOUNT_KEY_LENGTH)).isEqualTo("00000000001");
            assertThat(FixedWidthFieldReader.of(ACCOUNT, buffer, stride, ACCOUNT_WIDTH)
                    .field("ACCT-ID", 0, ACCOUNT_KEY_LENGTH)).isEqualTo("00000000002");
            assertThat(FixedWidthFieldReader.of(ACCOUNT, buffer, stride, ACCOUNT_WIDTH)
                    .field("ACCT-CURR-BAL", 12, 12)).isEqualTo("00000001580{");
        }

        @Test
        @DisplayName("a negative start index and an overrunning range are both refused")
        void anInvalidByteRangeIsRefused() {
            byte[] buffer = (CARD_XREF_IMAGE_ROW_0 + LINE_TERMINATOR)
                    .getBytes(StandardCharsets.US_ASCII);

            assertThatIllegalArgumentException()
                    .isThrownBy(() ->
                            FixedWidthFieldReader.of(CARD_XREF, buffer, -1, CARD_XREF_DATA_WIDTH))
                    .withMessageContaining("start index must not be negative")
                    .withMessageContaining("from=-1");
            assertThatIllegalArgumentException()
                    .isThrownBy(() ->
                            FixedWidthFieldReader.of(CARD_XREF, buffer, 2, CARD_XREF_DATA_WIDTH))
                    .withMessageContaining("does not fit inside the supplied buffer")
                    .withMessageContaining("from=2")
                    .withMessageContaining("recordWidth=36")
                    .withMessageContaining("buffer length=37");
        }
    }


    @Nested
    @DisplayName("character slices")
    class CharacterSlices {

        @Test
        @DisplayName("every account field slices to its hand-derived value")
        void everyAccountFieldSlicesToItsHandDerivedValue() {
            FixedWidthFieldReader reader = accountRow0();

            assertThat(reader.field("ACCT-ID", 0, 11)).isEqualTo("00000000001");
            assertThat(reader.field("ACCT-ACTIVE-STATUS", 11, 1)).isEqualTo("Y");
            assertThat(reader.field("ACCT-CURR-BAL", 12, 12)).isEqualTo("00000001940{");
            assertThat(reader.field("ACCT-CREDIT-LIMIT", 24, 12)).isEqualTo("00000020200{");
            assertThat(reader.field("ACCT-CASH-CREDIT-LIMIT", 36, 12)).isEqualTo("00000010200{");
            assertThat(reader.field("ACCT-OPEN-DATE", 48, 10)).isEqualTo("2014-11-20");
            // The expiry field's name is misspelled in the copybook. Its position is what the layout
            // guarantees, so the offset is asserted and the spelling is a documented anomaly.
            assertThat(reader.field("ACCT-EXPIRAION-DATE", 58, 10)).isEqualTo("2025-05-20");
            assertThat(reader.field("ACCT-REISSUE-DATE", 68, 10)).isEqualTo("2025-05-20");
            assertThat(reader.field("ACCT-CURR-CYC-CREDIT", 78, 12)).isEqualTo("00000000000{");
            assertThat(reader.field("ACCT-CURR-CYC-DEBIT", 90, 12)).isEqualTo("00000000000{");
            // Sample-data anomaly, reproduced rather than repaired: the postal-code position holds a
            // group-shaped value and the group-identifier position holds spaces.
            assertThat(reader.field("ACCT-ADDR-ZIP", 102, 10)).isEqualTo("A000000000");
            assertThat(reader.field("ACCT-GROUP-ID", 112, 10)).isEqualTo("          ");
            assertThat(reader.field("FILLER", 122, 178)).isEqualTo(spaces(178));
        }

        @Test
        @DisplayName("a slice is returned raw, with its leading and trailing spaces intact")
        void aSliceIsReturnedRaw() {
            FixedWidthFieldReader reader = transactionRow0();

            // Two trailing spaces are part of the source field's contractual ten bytes.
            assertThat(reader.field("TRAN-SOURCE", 22, 10)).isEqualTo("POS TERM  ");
            assertThat(encodedBytes(reader.field("TRAN-SOURCE", 22, 10))).isEqualTo(10);
            // Description text followed by 76 pad bytes, returned as the full hundred.
            assertThat(reader.field("TRAN-DESC", 32, 100))
                    .isEqualTo("Purchase at Abshire-Lowe" + spaces(76));
            // A leading-space value survives too: the merchant postal code is left-justified, so its
            // pad is trailing, while a slice that starts inside a pad run begins with spaces.
            assertThat(reader.field("TRAN-MERCHANT-ZIP", 252, 10)).isEqualTo("72112     ");
            assertThat(reader.field(257, 5)).isEqualTo("     ");
            // A slice that straddles the end of the merchant name's text and the start of its pad
            // run returns both, unaltered: four text bytes followed by one pad byte.
            assertThat(reader.field(160, 5)).isEqualTo("Lowe ");
        }

        @Test
        @DisplayName("the named and unnamed slice overloads return the same raw value")
        void theNamedAndUnnamedOverloadsReturnTheSameRawValue() {
            FixedWidthFieldReader reader = accountRow0();

            assertThat(reader.field(58, 10)).isEqualTo("2025-05-20");
            assertThat(reader.field("ACCT-EXPIRAION-DATE", 58, 10)).isEqualTo("2025-05-20");
            assertThat(reader.field(112, 10)).isEqualTo(spaces(10));
            assertThat(reader.field("ACCT-GROUP-ID", 112, 10)).isEqualTo(spaces(10));
        }

        @Test
        @DisplayName("a slice at offset zero and a slice ending exactly at the record width succeed")
        void boundarySlicesSucceed() {
            FixedWidthFieldReader reader = accountRow0();

            assertThat(reader.field(0, 1)).isEqualTo("0");
            assertThat(reader.field(0, ACCOUNT_WIDTH)).isEqualTo(ACCOUNT_IMAGE_ROW_0);
            // Final byte, and the final field, both end exactly on the record width.
            assertThat(reader.field(ACCOUNT_WIDTH - 1, 1)).isEqualTo(" ");
            assertThat(reader.field(122, 178)).isEqualTo(spaces(178));
            assertThat(encodedBytes(reader.field(122, 178))).isEqualTo(ACCOUNT_WIDTH - 122);
        }

        @Test
        @DisplayName("the byte and string factories agree with each other and with the literal")
        void allThreeFactoriesAgreeSliceForSlice() {
            byte[] imageBytes = ACCOUNT_IMAGE_ROW_0.getBytes(StandardCharsets.US_ASCII);
            byte[] buffer = (ACCOUNT_IMAGE_ROW_0 + LINE_TERMINATOR)
                    .getBytes(StandardCharsets.US_ASCII);

            FixedWidthFieldReader fromString = accountRow0();
            FixedWidthFieldReader fromBytes =
                    FixedWidthFieldReader.of(ACCOUNT, imageBytes, ACCOUNT_WIDTH);
            FixedWidthFieldReader fromRange =
                    FixedWidthFieldReader.of(ACCOUNT, buffer, 0, ACCOUNT_WIDTH);

            // Each side is compared with the same hand-written literal, so agreement is proved
            // against an independent value rather than by comparing two production results.
            assertThat(fromString.field("ACCT-ID", 0, 11)).isEqualTo("00000000001");
            assertThat(fromBytes.field("ACCT-ID", 0, 11)).isEqualTo("00000000001");
            assertThat(fromRange.field("ACCT-ID", 0, 11)).isEqualTo("00000000001");

            assertThat(fromString.field("ACCT-CURR-BAL", 12, 12)).isEqualTo("00000001940{");
            assertThat(fromBytes.field("ACCT-CURR-BAL", 12, 12)).isEqualTo("00000001940{");
            assertThat(fromRange.field("ACCT-CURR-BAL", 12, 12)).isEqualTo("00000001940{");

            assertThat(fromString.image()).isEqualTo(ACCOUNT_IMAGE_ROW_0);
            assertThat(fromBytes.image()).isEqualTo(ACCOUNT_IMAGE_ROW_0);
            assertThat(fromRange.image()).isEqualTo(ACCOUNT_IMAGE_ROW_0);
        }

        @Test
        @DisplayName("a byte slice carries the raw bytes and both overloads agree")
        void aByteSliceCarriesTheRawBytes() {
            FixedWidthFieldReader reader = accountRow0();
            byte[] expectedKey = "00000000001".getBytes(StandardCharsets.US_ASCII);
            byte[] expectedGroupId = spaces(10).getBytes(StandardCharsets.US_ASCII);

            assertThat(reader.fieldBytes(0, 11)).isEqualTo(expectedKey);
            assertThat(reader.fieldBytes("ACCT-ID", 0, 11)).isEqualTo(expectedKey);
            assertThat(reader.fieldBytes(112, 10)).isEqualTo(expectedGroupId);
            assertThat(reader.fieldBytes("ACCT-GROUP-ID", 112, 10)).isEqualTo(expectedGroupId);
            assertThat(reader.fieldBytes("ACCT-ID", 0, 11)).hasSize(11);
            // The overpunched sign byte of a zoned-decimal amount is carried through untouched.
            assertThat(reader.fieldBytes("ACCT-CURR-BAL", 12, 12))
                    .isEqualTo("00000001940{".getBytes(StandardCharsets.US_ASCII));
        }

        @Test
        @DisplayName("a cross-reference record slices at its three verified offsets")
        void aCrossReferenceRecordSlicesAtItsVerifiedOffsets() {
            FixedWidthFieldReader reader = cardXrefRow0();

            assertThat(reader.field("XREF-CARD-NUM", 0, 16)).isEqualTo("0500024453765740");
            assertThat(reader.field("XREF-CUST-ID", 16, 9)).isEqualTo("000000050");
            assertThat(reader.field("XREF-ACCT-ID", 25, 11)).isEqualTo("00000000050");
            // Leading zeros are significant: these are right-justified character fields, never
            // integers, so nothing here may be parsed and re-rendered.
            assertThat(reader.field("XREF-CUST-ID", 16, 9)).startsWith("0");
            assertThat(reader.field("XREF-ACCT-ID", 25, 11)).startsWith("0");
            assertThat(reader.recordWidth()).isEqualTo(36);
        }
    }

    @Nested
    @DisplayName("values are never trimmed")
    class UntrimmedValues {

        @Test
        @DisplayName("an all-space processing timestamp survives as twenty-six spaces")
        void anAllSpaceProcessingTimestampSurvivesIntact() {
            // Every one of the three hundred daily-transaction rows carries a 26-space processing
            // timestamp. It is a legal, meaningful value: never null, never empty, never trimmed.
            FixedWidthFieldReader reader = transactionRow0();
            String processingTimestamp = reader.field("DALYTRAN-PROC-TS",
                    TRANSACTION_DATA_PREFIX_LENGTH, TRANSACTION_PROC_TS_LENGTH);

            assertThat(processingTimestamp).isEqualTo(spaces(26));
            assertThat(encodedBytes(processingTimestamp)).isEqualTo(26);
            assertThat(processingTimestamp).isNotEmpty();
            assertThat(processingTimestamp).isNotEqualTo("");
            // The origination timestamp beside it is populated, which is what makes the empty one a
            // value rather than an artefact of a mis-set offset.
            assertThat(reader.field("DALYTRAN-ORIG-TS", 278, 26))
                    .isEqualTo("2022-06-10 19:27:53.000000");
        }

        @Test
        @DisplayName("padded ten-character disclosure-group keys keep their three trailing spaces")
        void paddedDisclosureGroupKeysKeepTheirTrailingSpaces() {
            // The interest calculation's not-found fallback probes with the padded ten-character
            // default value, so trimming the key would silently disable the fallback entirely.
            FixedWidthFieldReader defaultGroup = disclosureGroupDefault();
            FixedWidthFieldReader zeroRateGroup = FixedWidthFieldReader.of(DISCLOSURE_GROUP,
                    DISCLOSURE_GROUP_ZEROAPR_IMAGE, DISCLOSURE_GROUP_WIDTH);

            assertThat(defaultGroup.field("DIS-ACCT-GROUP-ID", 0, 10)).isEqualTo("DEFAULT   ");
            assertThat(zeroRateGroup.field("DIS-ACCT-GROUP-ID", 0, 10)).isEqualTo("ZEROAPR   ");
            // Explicitly not equal to the unpadded form a trimming reader would have produced.
            assertThat(defaultGroup.field("DIS-ACCT-GROUP-ID", 0, 10)).isNotEqualTo("DEFAULT");
            assertThat(zeroRateGroup.field("DIS-ACCT-GROUP-ID", 0, 10)).isNotEqualTo("ZEROAPR");
            assertThat(encodedBytes(defaultGroup.field("DIS-ACCT-GROUP-ID", 0, 10))).isEqualTo(10);
            assertThat(encodedBytes(zeroRateGroup.field("DIS-ACCT-GROUP-ID", 0, 10))).isEqualTo(10);
            // The three pad bytes are individually present.
            assertThat(defaultGroup.field(7, 3)).isEqualTo("   ");
            assertThat(zeroRateGroup.field(7, 3)).isEqualTo("   ");
            // Both groups carry a rate at the same offset, one non-zero and one zero, which is what
            // makes the rate-lookup branches reachable from seeded data alone.
            assertThat(defaultGroup.field("DIS-INT-RATE", 16, 6)).isEqualTo("00150{");
            assertThat(zeroRateGroup.field("DIS-INT-RATE", 16, 6)).isEqualTo("00000{");
        }

        @Test
        @DisplayName("case is preserved exactly, never folded in either direction")
        void caseIsPreservedExactly() {
            FixedWidthFieldReader card = FixedWidthFieldReader.of(CARD, CARD_IMAGE_ROW_0,
                    CARD_WIDTH);

            assertThat(card.field("CARD-EMBOSSED-NAME", 30, 50))
                    .isEqualTo("Aniya Von" + spaces(41));
            assertThat(card.field("CARD-EMBOSSED-NAME", 30, 50))
                    .isNotEqualTo("ANIYA VON" + spaces(41));
            assertThat(card.field("CARD-EMBOSSED-NAME", 30, 50))
                    .isNotEqualTo("aniya von" + spaces(41));
        }

        @Test
        @DisplayName("an all-space field and an all-zero field are different values")
        void anAllSpaceFieldIsNotAnAllZeroField() {
            FixedWidthFieldReader account = accountRow0();
            FixedWidthFieldReader categoryBalance = FixedWidthFieldReader.of(CATEGORY_BALANCE,
                    CATEGORY_BALANCE_IMAGE_ROW_0, CATEGORY_BALANCE_WIDTH);

            assertThat(account.field("ACCT-GROUP-ID", 112, 10)).isEqualTo(spaces(10));
            assertThat(account.field("ACCT-GROUP-ID", 112, 10)).isNotEqualTo(zeros(10));
            assertThat(categoryBalance.field("FILLER", CATEGORY_BALANCE_DATA_LENGTH, 22))
                    .isEqualTo(zeros(22));
            assertThat(categoryBalance.field("FILLER", CATEGORY_BALANCE_DATA_LENGTH, 22))
                    .isNotEqualTo(spaces(22));
        }
    }


    @Nested
    @DisplayName("key and data split")
    class KeyAndDataSplit {

        @Test
        @DisplayName("the account split is an 11-byte key and a 289-byte remainder summing to 300")
        void theAccountSplitIsElevenPlusTwoHundredEightyNine() {
            // The account file section declares an 11-digit identifier followed by a 289-byte data
            // remainder. Widths are the contract; the physical line the declaration occupies is not.
            assertThat(ACCOUNT_KEY_LENGTH + ACCOUNT_DATA_LENGTH).isEqualTo(ACCOUNT_WIDTH);

            FixedWidthFieldReader reader = accountRow0();

            assertThat(reader.key(ACCOUNT_KEY_LENGTH)).isEqualTo("00000000001");
            assertThat(encodedBytes(reader.key(ACCOUNT_KEY_LENGTH))).isEqualTo(11);
            assertThat(encodedBytes(reader.data(ACCOUNT_KEY_LENGTH))).isEqualTo(289);
            assertThat(reader.data(ACCOUNT_KEY_LENGTH))
                    .isEqualTo(ACCOUNT_IMAGE_ROW_0.substring(11, 300));
            // The key is the leading substring of the image, which is why it is also reachable as a
            // slice at offset zero and why the persisted identifier is never a surrogate.
            assertThat(reader.field(0, ACCOUNT_KEY_LENGTH)).isEqualTo("00000000001");
            assertThat(reader.image()).startsWith("00000000001");
        }

        @Test
        @DisplayName("the transaction split is 304 plus 26 plus 20, summing to 350")
        void theTransactionSplitIsThreeHundredFourPlusTwentySixPlusTwenty() {
            assertThat(TRANSACTION_DATA_PREFIX_LENGTH + TRANSACTION_PROC_TS_LENGTH
                    + TRANSACTION_FILLER_LENGTH).isEqualTo(TRANSACTION_WIDTH);

            FixedWidthFieldReader reader = transactionRow0();

            // Leading prefix, addressed as the key half of the split at offset zero.
            assertThat(encodedBytes(reader.key(TRANSACTION_DATA_PREFIX_LENGTH))).isEqualTo(304);
            assertThat(reader.key(TRANSACTION_DATA_PREFIX_LENGTH))
                    .isEqualTo(TRANSACTION_IMAGE_ROW_0.substring(0, 304));
            assertThat(reader.key(TRANSACTION_DATA_PREFIX_LENGTH)).startsWith("0000000000683580");
            // Remainder, which is the timestamp and the filler taken together.
            assertThat(encodedBytes(reader.data(TRANSACTION_DATA_PREFIX_LENGTH))).isEqualTo(46);
            assertThat(reader.data(TRANSACTION_DATA_PREFIX_LENGTH)).isEqualTo(spaces(46));
            // And each of the remainder's two fields addressed individually.
            assertThat(reader.field("FD-TRAN-PROC-TS", 304, 26)).isEqualTo(spaces(26));
            assertThat(reader.field("FD-FILLER", 330, 20)).isEqualTo(spaces(20));
        }

        @Test
        @DisplayName("every key starts at offset zero, so no cluster needs a key offset")
        void everyKeyStartsAtOffsetZero() {
            // Every cluster in the estate declares its key at offset zero without exception, so the
            // key convenience needs no offset argument at all - and there is nowhere for a surrogate
            // identifier to live.
            assertThat(accountRow0().key(11)).isEqualTo("00000000001");
            assertThat(cardXrefRow0().key(16)).isEqualTo("0500024453765740");
            assertThat(disclosureGroupDefault().key(10)).isEqualTo("DEFAULT   ");
            assertThat(FixedWidthFieldReader
                    .of(CATEGORY_BALANCE, CATEGORY_BALANCE_IMAGE_ROW_0, CATEGORY_BALANCE_WIDTH)
                    .key(11)).isEqualTo("00000000001");
            assertThat(FixedWidthFieldReader.of(CARD, CARD_IMAGE_ROW_0, CARD_WIDTH).key(16))
                    .isEqualTo("0500024453765740");
        }

        @Test
        @DisplayName("a composite key is the concatenation of its parts, still at offset zero")
        void aCompositeKeyIsTheConcatenationOfItsParts() {
            // The disclosure-group key is a group identifier, a type code and a category code taken
            // together, so the composite key is still a single leading substring.
            FixedWidthFieldReader reader = disclosureGroupDefault();

            assertThat(reader.key(16)).isEqualTo("DEFAULT   010001");
            assertThat(reader.field("DIS-ACCT-GROUP-ID", 0, 10)).isEqualTo("DEFAULT   ");
            assertThat(reader.field("DIS-TRAN-TYPE-CD", 10, 2)).isEqualTo("01");
            assertThat(reader.field("DIS-TRAN-CAT-CD", 12, 4)).isEqualTo("0001");
            assertThat(encodedBytes(reader.key(16))).isEqualTo(10 + 2 + 4);
        }

        @Test
        @DisplayName("a key length of zero, or one leaving no remainder, is refused")
        void anInvalidKeyLengthIsRefused() {
            FixedWidthFieldReader reader = accountRow0();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> reader.data(0))
                    .withMessageContaining("key length must be at least 1")
                    .withMessageContaining("keyLength=0");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> reader.data(-11))
                    .withMessageContaining("key length must be at least 1");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> reader.data(ACCOUNT_WIDTH))
                    .withMessageContaining("leaves no data remainder")
                    .withMessageContaining("keyLength=300")
                    .withMessageContaining("recordWidth=300");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> reader.data(ACCOUNT_WIDTH + 1))
                    .withMessageContaining("leaves no data remainder");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> reader.key(0))
                    .withMessageContaining("length must be at least 1");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> reader.key(ACCOUNT_WIDTH + 1))
                    .withMessageContaining("slice out of range");
        }

        @Test
        @DisplayName("a key of the full width minus one leaves a one-byte remainder")
        void aKeyOfTheFullWidthMinusOneLeavesOneByte() {
            FixedWidthFieldReader reader = accountRow0();

            assertThat(encodedBytes(reader.key(ACCOUNT_WIDTH))).isEqualTo(300);
            assertThat(encodedBytes(reader.data(ACCOUNT_WIDTH - 1))).isEqualTo(1);
            assertThat(reader.data(ACCOUNT_WIDTH - 1)).isEqualTo(" ");
        }
    }

    @Nested
    @DisplayName("slice bounds")
    class SliceBounds {

        @ParameterizedTest(name = "offset={0}, length={1}")
        @CsvSource({
            "-1, 11",
            "-300, 1",
            "0, 0",
            "0, -5",
            "11, 0",
            "290, 11",
            "299, 2",
            "300, 1",
            "301, 1",
            "0, 301",
        })
        @DisplayName("a slice outside the record is refused rather than clipped")
        void aSliceOutsideTheRecordIsRefused(int offset, int length) {
            FixedWidthFieldReader reader = accountRow0();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> reader.field(offset, length));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> reader.field("ACCT-ID", offset, length));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> reader.fieldBytes(offset, length));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> reader.fieldBytes("ACCT-ID", offset, length));
        }

        @Test
        @DisplayName("a negative offset says so, and names the field when one was supplied")
        void aNegativeOffsetIsNamed() {
            FixedWidthFieldReader reader = accountRow0();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> reader.field(-1, 11))
                    .withMessageContaining("offset must not be negative")
                    .withMessageContaining("ACCOUNT record image")
                    .withMessageContaining("offset=-1");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> reader.field("ACCT-ID", -1, 11))
                    .withMessageContaining("offset must not be negative")
                    .withMessageContaining("field 'ACCT-ID'");
        }

        @Test
        @DisplayName("a length below one says so, and names the field when one was supplied")
        void aLengthBelowOneIsNamed() {
            FixedWidthFieldReader reader = accountRow0();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> reader.field(0, 0))
                    .withMessageContaining("length must be at least 1")
                    .withMessageContaining("length=0");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> reader.field("ACCT-GROUP-ID", 112, -10))
                    .withMessageContaining("length must be at least 1")
                    .withMessageContaining("field 'ACCT-GROUP-ID'")
                    .withMessageContaining("length=-10");
        }

        @Test
        @DisplayName("an overrunning slice names the offset, the length and the record width")
        void anOverrunningSliceNamesAllThreeNumbers() {
            FixedWidthFieldReader reader = accountRow0();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> reader.field("FILLER", 122, 179))
                    .withMessageContaining("slice out of range")
                    .withMessageContaining("ACCOUNT record image")
                    .withMessageContaining("field 'FILLER'")
                    .withMessageContaining("offset=122")
                    .withMessageContaining("length=179")
                    .withMessageContaining("recordWidth=300");
        }
    }


    @Nested
    @DisplayName("writing fields")
    class WritingFields {

        @Test
        @DisplayName("an alphanumeric value is left-justified and padded on the right with spaces")
        void anAlphanumericValueIsLeftJustifiedAndSpacePadded() {
            FixedWidthFieldReader built = FixedWidthFieldReader.builder(CARD, CARD_WIDTH)
                    .putAlphanumeric("CARD-EMBOSSED-NAME", 30, 50, "Aniya Von")
                    .build();

            assertThat(built.field("CARD-EMBOSSED-NAME", 30, 50))
                    .isEqualTo("Aniya Von" + spaces(41));
            assertThat(built.field(30, 9)).isEqualTo("Aniya Von");
            assertThat(built.field(39, 41)).isEqualTo(spaces(41));
            assertThat(encodedBytes(built.field("CARD-EMBOSSED-NAME", 30, 50))).isEqualTo(50);
        }

        @Test
        @DisplayName("an alphanumeric value of exactly the field width is placed unchanged")
        void anAlphanumericValueOfExactlyTheFieldWidthIsUnchanged() {
            FixedWidthFieldReader built =
                    FixedWidthFieldReader.builder(TRANSACTION, TRANSACTION_WIDTH)
                            .putAlphanumeric("TRAN-ORIG-TS", 278, 26, "2022-06-10 19:27:53.000000")
                            // A value that is itself all spaces is a value, not an omission.
                            .putAlphanumeric("TRAN-PROC-TS", 304, 26, spaces(26))
                            .putAlphanumeric("TRAN-SOURCE", 22, 10, "POS TERM  ")
                            .build();

            assertThat(built.field("TRAN-ORIG-TS", 278, 26))
                    .isEqualTo("2022-06-10 19:27:53.000000");
            assertThat(built.field("TRAN-PROC-TS", 304, 26)).isEqualTo(spaces(26));
            assertThat(built.field("TRAN-SOURCE", 22, 10)).isEqualTo("POS TERM  ");
        }

        @Test
        @DisplayName("an empty alphanumeric value yields a field of spaces")
        void anEmptyAlphanumericValueYieldsSpaces() {
            FixedWidthFieldReader built = FixedWidthFieldReader.builder(ACCOUNT, ACCOUNT_WIDTH)
                    .putAlphanumeric("ACCT-GROUP-ID", 112, 10, "")
                    .build();

            assertThat(built.field("ACCT-GROUP-ID", 112, 10)).isEqualTo(spaces(10));
            assertThat(built.field("ACCT-GROUP-ID", 112, 10)).isNotEqualTo(zeros(10));
        }

        @Test
        @DisplayName("an unsigned numeric value is right-justified and padded on the left with zeros")
        void anUnsignedNumericValueIsRightJustifiedAndZeroPadded() {
            // Leading zeros are significant, so a three-byte verification code holding seven must
            // emerge as three characters, not as one. Both padded shapes below occur in the sample
            // card data, which is what makes this a data requirement rather than a preference.
            FixedWidthFieldReader built = FixedWidthFieldReader.builder(CARD, CARD_WIDTH)
                    .putNumeric("CARD-CVV-CD", 27, 3, "7")
                    .build();
            assertThat(built.field("CARD-CVV-CD", 27, 3)).isEqualTo("007");

            FixedWidthFieldReader twoDigits = FixedWidthFieldReader.builder(CARD, CARD_WIDTH)
                    .putNumeric("CARD-CVV-CD", 27, 3, "47")
                    .build();
            assertThat(twoDigits.field("CARD-CVV-CD", 27, 3)).isEqualTo("047");

            FixedWidthFieldReader threeDigits = FixedWidthFieldReader.builder(CARD, CARD_WIDTH)
                    .putNumeric("CARD-CVV-CD", 27, 3, "747")
                    .build();
            assertThat(threeDigits.field("CARD-CVV-CD", 27, 3)).isEqualTo("747");

            // The same rule at a wider field: an eleven-digit identifier carried as characters.
            FixedWidthFieldReader identifier = FixedWidthFieldReader.builder(CARD, CARD_WIDTH)
                    .putNumeric("CARD-ACCT-ID", 16, 11, "50")
                    .build();
            assertThat(identifier.field("CARD-ACCT-ID", 16, 11)).isEqualTo("00000000050");
            assertThat(encodedBytes(identifier.field("CARD-ACCT-ID", 16, 11))).isEqualTo(11);
        }

        @Test
        @DisplayName("an empty numeric value yields a field of zeros")
        void anEmptyNumericValueYieldsZeros() {
            FixedWidthFieldReader built = FixedWidthFieldReader.builder(CARD, CARD_WIDTH)
                    .putNumeric("CARD-CVV-CD", 27, 3, "")
                    .build();

            assertThat(built.field("CARD-CVV-CD", 27, 3)).isEqualTo("000");
        }

        @Test
        @DisplayName("a zoned-decimal image keeps its overpunched sign byte in the final position")
        void aZonedDecimalImageKeepsItsSignByteLast() {
            // Numeric placement is justification and padding only, with no digit check, so a zoned
            // amount whose final byte carries the sign is positioned correctly rather than rejected.
            FixedWidthFieldReader built = FixedWidthFieldReader.builder(ACCOUNT, ACCOUNT_WIDTH)
                    .putNumeric("ACCT-CURR-BAL", 12, 12, "1940{")
                    .build();

            assertThat(built.field("ACCT-CURR-BAL", 12, 12)).isEqualTo("00000001940{");
            assertThat(built.field(23, 1)).isEqualTo("{");
        }

        @Test
        @DisplayName("a value wider than its field is refused, never truncated to fit")
        void anOverLengthValueIsRefused() {
            FixedWidthFieldReader.Builder builder =
                    FixedWidthFieldReader.builder(CARD, CARD_WIDTH);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> builder.putNumeric("CARD-CVV-CD", 27, 3, "7470"))
                    .withMessageContaining("value does not fit")
                    .withMessageContaining("field 'CARD-CVV-CD'")
                    .withMessageContaining("field width is 3 encoded bytes")
                    .withMessageContaining("the value is 4 encoded bytes")
                    .withMessageContaining("never truncated to fit");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> builder.putAlphanumeric("CARD-ACTIVE-STATUS", 90, 1, "YN"))
                    .withMessageContaining("field width is 1 encoded bytes")
                    .withMessageContaining("the value is 2 encoded bytes");
            // The refused placements left no trace: the bytes are still the initial spaces.
            assertThat(builder.build().field(27, 3)).isEqualTo("   ");
            assertThat(builder.build().field(90, 1)).isEqualTo(" ");
        }

        @Test
        @DisplayName("a placement outside the record is refused, and a non-representable one too")
        void anInvalidPlacementIsRefused() {
            FixedWidthFieldReader.Builder builder =
                    FixedWidthFieldReader.builder(CARD, CARD_WIDTH);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> builder.putAlphanumeric("FILLER", 149, 2, "A"))
                    .withMessageContaining("slice out of range")
                    .withMessageContaining("recordWidth=150");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> builder.putNumeric("CARD-CVV-CD", -1, 3, "7"))
                    .withMessageContaining("offset must not be negative");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> builder.putAlphanumeric("CARD-NUM", 0, 0, "0"))
                    .withMessageContaining("length must be at least 1");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> builder.putSpaceFiller(91, 60))
                    .withMessageContaining("slice out of range")
                    .withMessageContaining("field 'FILLER'");
            assertThatIllegalArgumentException()
                    .isThrownBy(() ->
                            builder.putAlphanumeric("CARD-EMBOSSED-NAME", 30, 50, "Aniya \u00C9"))
                    .withMessageContaining("US-ASCII cannot represent")
                    .withMessageContaining("field 'CARD-EMBOSSED-NAME'");
        }

        @Test
        @DisplayName("an unwritten byte emerges as a space, so filler needs no special handling")
        void anUnwrittenByteEmergesAsASpace() {
            FixedWidthFieldReader untouched =
                    FixedWidthFieldReader.builder(DISCLOSURE_GROUP, DISCLOSURE_GROUP_WIDTH).build();

            assertThat(untouched.image()).isEqualTo(spaces(50));
            assertThat(untouched.image()).isNotEqualTo(zeros(50));
            assertThat(encodedBytes(untouched.image())).isEqualTo(50);
            // Not a zero byte either: an unwritten byte is 0x20, never 0x00.
            assertThat(untouched.toByteArray()).containsOnly((byte) 0x20);
        }

        @Test
        @DisplayName("a declared filler run is written as spaces, never as zeros or nulls")
        void aDeclaredFillerRunIsWrittenAsSpaces() {
            FixedWidthFieldReader built = FixedWidthFieldReader.builder(CARD, CARD_WIDTH)
                    .putNumeric("CARD-CVV-CD", 27, 3, "747")
                    .putSpaceFiller(91, 59)
                    .build();

            assertThat(built.field("FILLER", 91, 59)).isEqualTo(spaces(59));
            byte[] filler = built.fieldBytes("FILLER", 91, 59);
            assertThat(filler).hasSize(59);
            assertThat(filler).containsOnly((byte) 0x20);
            assertThat(filler).doesNotContain((byte) 0x30);
            assertThat(filler).doesNotContain((byte) 0x00);
        }

        @Test
        @DisplayName("a filler run re-establishes spaces over bytes a caller already wrote")
        void aFillerRunReEstablishesSpaces() {
            FixedWidthFieldReader built = FixedWidthFieldReader.builder(CARD, CARD_WIDTH)
                    .putNumeric("FILLER", 91, 59, "9")
                    .putSpaceFiller(91, 59)
                    .build();

            assertThat(built.field(91, 59)).isEqualTo(spaces(59));
        }

        @Test
        @DisplayName("a whole card record assembles to its declared width and matches the sample row")
        void aWholeCardRecordAssemblesToTheSampleRow() {
            // The card sample data carries space filler, so a record assembled under the module's
            // uniform space-filler rule reproduces the sample row byte for byte. Both sides are
            // independently known: the left is assembled from field values, the right is the measured
            // image.
            FixedWidthFieldReader built = FixedWidthFieldReader.builder(CARD, CARD_WIDTH)
                    .putAlphanumeric("CARD-NUM", 0, 16, "0500024453765740")
                    .putNumeric("CARD-ACCT-ID", 16, 11, "50")
                    .putNumeric("CARD-CVV-CD", 27, 3, "747")
                    .putAlphanumeric("CARD-EMBOSSED-NAME", 30, 50, "Aniya Von")
                    .putAlphanumeric("CARD-EXPIRAION-DATE", 80, 10, "2023-03-09")
                    .putAlphanumeric("CARD-ACTIVE-STATUS", 90, 1, "Y")
                    .putSpaceFiller(91, 59)
                    .build();

            assertThat(built.recordWidth()).isEqualTo(150);
            assertThat(encodedBytes(built.image())).isEqualTo(150);
            assertThat(built.image()).isEqualTo(CARD_IMAGE_ROW_0);
            assertThat(built.toByteArray())
                    .isEqualTo(CARD_IMAGE_ROW_0.getBytes(StandardCharsets.US_ASCII));
        }

        @Test
        @DisplayName("placement is positional, so declaration order does not matter")
        void placementIsPositionalRatherThanOrdered() {
            // The same record assembled back to front is byte-identical, because an offset says
            // where a field goes and nothing depends on the order of the calls.
            FixedWidthFieldReader reversed = FixedWidthFieldReader.builder(CARD, CARD_WIDTH)
                    .putSpaceFiller(91, 59)
                    .putAlphanumeric("CARD-ACTIVE-STATUS", 90, 1, "Y")
                    .putAlphanumeric("CARD-EXPIRAION-DATE", 80, 10, "2023-03-09")
                    .putAlphanumeric("CARD-EMBOSSED-NAME", 30, 50, "Aniya Von")
                    .putNumeric("CARD-CVV-CD", 27, 3, "747")
                    .putNumeric("CARD-ACCT-ID", 16, 11, "50")
                    .putAlphanumeric("CARD-NUM", 0, 16, "0500024453765740")
                    .build();

            assertThat(reversed.image()).isEqualTo(CARD_IMAGE_ROW_0);
        }

        @Test
        @DisplayName("re-placing a shorter value over a longer one leaves no stale bytes")
        void rePlacingAShorterValueLeavesNoStaleBytes() {
            // The failure this guards against would keep the record exactly the right width while
            // carrying the wrong content, which no downstream width check could ever catch.
            FixedWidthFieldReader built = FixedWidthFieldReader.builder(CARD, CARD_WIDTH)
                    .putAlphanumeric("CARD-EMBOSSED-NAME", 30, 50, "Aniya Von Longer Name")
                    .putAlphanumeric("CARD-EMBOSSED-NAME", 30, 50, "Aniya Von")
                    .build();

            assertThat(built.field("CARD-EMBOSSED-NAME", 30, 50))
                    .isEqualTo("Aniya Von" + spaces(41));
        }

        @Test
        @DisplayName("a builder reports its layout and width, and hides its content in diagnostics")
        void aBuilderReportsItsLayoutAndWidth() {
            FixedWidthFieldReader.Builder builder =
                    FixedWidthFieldReader.builder(CARD, CARD_WIDTH);

            assertThat(builder.artefact()).isEqualTo("CARD");
            assertThat(builder.recordWidth()).isEqualTo(150);
            assertThat(builder.toString())
                    .isEqualTo("FixedWidthFieldReader.Builder[CARD, 150 bytes]");
        }

        @Test
        @DisplayName("a built record is unaffected by further placement on the same builder")
        void aBuiltRecordIsUnaffectedByFurtherPlacement() {
            FixedWidthFieldReader.Builder builder = FixedWidthFieldReader.builder(CARD, CARD_WIDTH)
                    .putNumeric("CARD-CVV-CD", 27, 3, "747");
            FixedWidthFieldReader first = builder.build();

            builder.putNumeric("CARD-CVV-CD", 27, 3, "003");
            FixedWidthFieldReader second = builder.build();

            assertThat(first.field("CARD-CVV-CD", 27, 3)).isEqualTo("747");
            assertThat(second.field("CARD-CVV-CD", 27, 3)).isEqualTo("003");
        }
    }

    @Nested
    @DisplayName("filler regimes")
    class FillerRegimes {

        @Test
        @DisplayName("a zero-filled reference row is not byte-equal to its space-filled counterpart")
        void aZeroFilledRowIsNotByteEqualToItsSpaceFilledCounterpart() {
            // The sample data does not pad filler consistently: the master files carry space filler
            // and the reference tables carry ASCII-zero filler, because uninitialised filler has no
            // canonical value. The module resolves this by emitting space filler uniformly on write
            // and comparing only the mapped data prefix on read, so the whole-record comparison below
            // is expected to differ and that difference is asserted rather than glossed over.
            FixedWidthFieldReader spaceFilled =
                    FixedWidthFieldReader.builder(DISCLOSURE_GROUP, DISCLOSURE_GROUP_WIDTH)
                            .putAlphanumeric("DIS-ACCT-GROUP-ID", 0, 10, "DEFAULT   ")
                            .putAlphanumeric("DIS-TRAN-TYPE-CD", 10, 2, "01")
                            .putNumeric("DIS-TRAN-CAT-CD", 12, 4, "1")
                            .putNumeric("DIS-INT-RATE", 16, 6, "150{")
                            .putSpaceFiller(DISCLOSURE_GROUP_DATA_LENGTH,
                                    DISCLOSURE_GROUP_FILLER_LENGTH)
                            .build();
            FixedWidthFieldReader zeroFilled = disclosureGroupDefault();

            // Same width, same mapped prefix. Each side is compared with the same hand-written
            // value, so neither side's result is used as the other's expectation.
            assertThat(spaceFilled.recordWidth()).isEqualTo(DISCLOSURE_GROUP_WIDTH);
            assertThat(zeroFilled.recordWidth()).isEqualTo(DISCLOSURE_GROUP_WIDTH);
            assertThat(spaceFilled.field(0, DISCLOSURE_GROUP_DATA_LENGTH))
                    .isEqualTo("DEFAULT   01000100150{");
            assertThat(zeroFilled.field(0, DISCLOSURE_GROUP_DATA_LENGTH))
                    .isEqualTo("DEFAULT   01000100150{");

            // Different filler, therefore different images. This is the rule, made visible.
            assertThat(spaceFilled.field(DISCLOSURE_GROUP_DATA_LENGTH,
                    DISCLOSURE_GROUP_FILLER_LENGTH)).isEqualTo(spaces(28));
            assertThat(zeroFilled.field(DISCLOSURE_GROUP_DATA_LENGTH,
                    DISCLOSURE_GROUP_FILLER_LENGTH)).isEqualTo(zeros(28));
            // The whole-record comparison is stated against the measured image itself, in both
            // directions and in both representations, so the rule is visible rather than implied.
            assertThat(spaceFilled.image()).isNotEqualTo(DISCLOSURE_GROUP_DEFAULT_IMAGE);
            assertThat(zeroFilled.image()).isEqualTo(DISCLOSURE_GROUP_DEFAULT_IMAGE);
            assertThat(spaceFilled.image())
                    .isEqualTo("DEFAULT   01000100150{" + spaces(28));
            assertThat(spaceFilled.toByteArray())
                    .isNotEqualTo(DISCLOSURE_GROUP_DEFAULT_IMAGE
                            .getBytes(StandardCharsets.US_ASCII));
        }

        @Test
        @DisplayName("a zero-filled reference row still slices every mapped field correctly")
        void aZeroFilledRowStillSlicesItsMappedFields() {
            // Reading is unaffected by which byte the filler happens to be, because a mapper only
            // ever addresses the mapped prefix.
            FixedWidthFieldReader reader = FixedWidthFieldReader.of(CATEGORY_BALANCE,
                    CATEGORY_BALANCE_IMAGE_ROW_0, CATEGORY_BALANCE_WIDTH);

            assertThat(reader.field("TRANCAT-ACCT-ID", 0, 11)).isEqualTo("00000000001");
            assertThat(reader.field("TRANCAT-TYPE-CD", 11, 2)).isEqualTo("01");
            assertThat(reader.field("TRANCAT-CD", 13, 4)).isEqualTo("0001");
            assertThat(reader.field("TRAN-CAT-BAL", 17, 11)).isEqualTo("0000000000{");
            assertThat(reader.field("FILLER", CATEGORY_BALANCE_DATA_LENGTH, 22))
                    .isEqualTo(zeros(22));
        }

        @Test
        @DisplayName("a space-filled master row round-trips byte for byte")
        void aSpaceFilledMasterRowRoundTripsByteForByte() {
            // The four master files carry space filler, so for those layouts the uniform rule and
            // the sample data agree exactly and a whole-record comparison holds.
            FixedWidthFieldReader built = FixedWidthFieldReader.builder(ACCOUNT, ACCOUNT_WIDTH)
                    .putNumeric("ACCT-ID", 0, 11, "1")
                    .putAlphanumeric("ACCT-ACTIVE-STATUS", 11, 1, "Y")
                    .putNumeric("ACCT-CURR-BAL", 12, 12, "1940{")
                    .putNumeric("ACCT-CREDIT-LIMIT", 24, 12, "20200{")
                    .putNumeric("ACCT-CASH-CREDIT-LIMIT", 36, 12, "10200{")
                    .putAlphanumeric("ACCT-OPEN-DATE", 48, 10, "2014-11-20")
                    .putAlphanumeric("ACCT-EXPIRAION-DATE", 58, 10, "2025-05-20")
                    .putAlphanumeric("ACCT-REISSUE-DATE", 68, 10, "2025-05-20")
                    .putNumeric("ACCT-CURR-CYC-CREDIT", 78, 12, "0{")
                    .putNumeric("ACCT-CURR-CYC-DEBIT", 90, 12, "0{")
                    .putAlphanumeric("ACCT-ADDR-ZIP", 102, 10, "A000000000")
                    .putAlphanumeric("ACCT-GROUP-ID", 112, 10, "")
                    .putSpaceFiller(122, 178)
                    .build();

            assertThat(built.image()).isEqualTo(ACCOUNT_IMAGE_ROW_0);
            assertThat(encodedBytes(built.image())).isEqualTo(300);
        }
    }


    @Nested
    @DisplayName("sample-file stride arithmetic")
    class SampleFileStrideArithmetic {

        @ParameterizedTest(name = "{0}: {1} bytes = {2} rows x ({3} + 1)")
        @CsvSource({
            "acctdata.txt,   15050,  50, 300",
            "carddata.txt,    7550,  50, 150",
            "cardxref.txt,    1850,  50,  36",
            "custdata.txt,   25050,  50, 500",
            "dailytran.txt, 105300, 300, 350",
            "discgrp.txt,     2601,  51,  50",
            "tcatbal.txt,     2550,  50,  50",
            "trancatg.txt,    1098,  18,  60",
            "trantype.txt,     427,   7,  60",
        })
        @DisplayName("every measured byte count factors as rows times record width plus terminator")
        void everyMeasuredByteCountFactorsExactly(String fixtureName, int totalBytes, int rows,
                int recordWidth) {
            // The stride of a newline-terminated fixed-width file is the record width plus one. This
            // arithmetic is what proves a reader configured with the wrong width misaligns every row
            // after the first rather than failing visibly on the first, which is the failure mode a
            // fixed-width reader has to make impossible. No file is opened here: the byte counts were
            // measured once and are carried as literals, and the name identifies which file each row
            // describes.
            assertThat(fixtureName).endsWith(".txt");
            assertThat(totalBytes).isEqualTo(rows * (recordWidth + 1));
            assertThat(totalBytes % (recordWidth + 1)).isZero();
            assertThat(totalBytes / (recordWidth + 1)).isEqualTo(rows);
        }

        @Test
        @DisplayName("a reader given the stride instead of the record width refuses the extra byte")
        void aReaderGivenTheStrideRefusesTheExtraByte() {
            // 51 is the disclosure-group stride and 50 is its record width. Handing the reader an
            // image of stride length is the mistake, and it is refused rather than absorbed.
            String withTerminator = DISCLOSURE_GROUP_DEFAULT_IMAGE + LINE_TERMINATOR;
            assertThat(encodedBytes(withTerminator)).isEqualTo(51);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FixedWidthFieldReader.of(DISCLOSURE_GROUP, withTerminator,
                            DISCLOSURE_GROUP_WIDTH))
                    .withMessageContaining("must be exactly 50 encoded bytes")
                    .withMessageContaining("the supplied image is 51 encoded bytes")
                    .withMessageContaining("unstripped 0x0A line terminator");
        }
    }

    @Nested
    @DisplayName("cross-reference dual width")
    class CrossReferenceDualWidth {

        @Test
        @DisplayName("a 36-byte image slices at its three offsets and stays 36 bytes wide")
        void aThirtySixByteImageSlicesAndStaysThirtySix() {
            // The sample file's stride is 37, so its records are 36 bytes and the copybook's 14-byte
            // filler is simply absent. A read expectation is never padded out to the 50-byte cluster
            // length to make the two agree.
            FixedWidthFieldReader reader = cardXrefRow0();

            assertThat(reader.recordWidth()).isEqualTo(CARD_XREF_DATA_WIDTH);
            assertThat(encodedBytes(reader.image())).isEqualTo(36);
            assertThat(reader.field("XREF-CARD-NUM", 0, 16)).isEqualTo("0500024453765740");
            assertThat(reader.field("XREF-CUST-ID", 16, 9)).isEqualTo("000000050");
            assertThat(reader.field("XREF-ACCT-ID", 25, 11)).isEqualTo("00000000050");
            // The final field ends exactly on the record width, so nothing lies beyond it.
            assertThat(reader.field(25, 11)).isEqualTo("00000000050");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> reader.field("FILLER", 36, 14))
                    .withMessageContaining("slice out of range")
                    .withMessageContaining("recordWidth=36");
        }

        @Test
        @DisplayName("declaring the 50-byte cluster width for a 36-byte image fails width validation")
        void declaringTheClusterWidthForAThirtySixByteImageFails() {
            // The important property is that the mismatch is refused loudly. Silently padding to 50
            // would misalign every subsequent field for any caller that then trusted the width.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FixedWidthFieldReader.of(CARD_XREF, CARD_XREF_IMAGE_ROW_0,
                            CARD_XREF_CLUSTER_WIDTH))
                    .withMessageContaining("CARD-XREF")
                    .withMessageContaining("must be exactly 50 encoded bytes in US-ASCII")
                    .withMessageContaining("the supplied image is 36 encoded bytes")
                    .withMessageContaining("never padded or truncated on input");
        }

        @Test
        @DisplayName("a record written at the cluster width carries space filler beyond the prefix")
        void aRecordWrittenAtTheClusterWidthCarriesSpaceFiller() {
            // Writing is where the two widths are reconciled: the mapped prefix is placed and the
            // declared filler run is emitted as spaces, which is the module's uniform rule.
            FixedWidthFieldReader built =
                    FixedWidthFieldReader.builder(CARD_XREF, CARD_XREF_CLUSTER_WIDTH)
                            .putAlphanumeric("XREF-CARD-NUM", 0, 16, "0500024453765740")
                            .putNumeric("XREF-CUST-ID", 16, 9, "50")
                            .putNumeric("XREF-ACCT-ID", 25, 11, "50")
                            .putSpaceFiller(CARD_XREF_DATA_WIDTH, CARD_XREF_FILLER_LENGTH)
                            .build();

            assertThat(built.recordWidth()).isEqualTo(50);
            assertThat(encodedBytes(built.image())).isEqualTo(50);
            // The mapped prefix reproduces the sample row exactly.
            assertThat(built.field(0, CARD_XREF_DATA_WIDTH)).isEqualTo(CARD_XREF_IMAGE_ROW_0);
            // And the 14 bytes the sample file does not carry are spaces, never zeros.
            assertThat(built.field("FILLER", CARD_XREF_DATA_WIDTH, CARD_XREF_FILLER_LENGTH))
                    .isEqualTo(spaces(14));
            assertThat(built.image())
                    .isEqualTo(CARD_XREF_IMAGE_ROW_0 + spaces(CARD_XREF_FILLER_LENGTH));
        }
    }

    @Nested
    @DisplayName("immutability and defensive copies")
    class ImmutabilityAndDefensiveCopies {

        @Test
        @DisplayName("mutating the caller's array after construction does not change the reader")
        void mutatingTheCallerArrayDoesNotChangeTheReader() {
            byte[] caller = ACCOUNT_IMAGE_ROW_0.getBytes(StandardCharsets.US_ASCII);
            FixedWidthFieldReader reader = FixedWidthFieldReader.of(ACCOUNT, caller, ACCOUNT_WIDTH);

            // Overwrite the whole array the caller still holds, including the key.
            for (int i = 0; i < caller.length; i++) {
                caller[i] = (byte) 0x39;
            }

            assertThat(reader.field("ACCT-ID", 0, ACCOUNT_KEY_LENGTH)).isEqualTo("00000000001");
            assertThat(reader.image()).isEqualTo(ACCOUNT_IMAGE_ROW_0);
            assertThat(reader.field("ACCT-GROUP-ID", 112, 10)).isEqualTo(spaces(10));
        }

        @Test
        @DisplayName("mutating the caller's buffer after range construction does not change the reader")
        void mutatingTheCallerBufferDoesNotChangeTheReader() {
            int stride = CARD_XREF_DATA_WIDTH + 1;
            byte[] buffer = (CARD_XREF_IMAGE_ROW_0 + LINE_TERMINATOR
                    + CARD_XREF_IMAGE_ROW_1 + LINE_TERMINATOR)
                    .getBytes(StandardCharsets.US_ASCII);
            FixedWidthFieldReader reader =
                    FixedWidthFieldReader.of(CARD_XREF, buffer, stride, CARD_XREF_DATA_WIDTH);

            for (int i = 0; i < buffer.length; i++) {
                buffer[i] = (byte) 0x39;
            }

            assertThat(reader.image()).isEqualTo(CARD_XREF_IMAGE_ROW_1);
            assertThat(reader.field("XREF-CARD-NUM", 0, 16)).isEqualTo("0683586198171516");
        }

        @Test
        @DisplayName("the whole-record accessor returns a fresh array on every call")
        void theWholeRecordAccessorReturnsAFreshArray() {
            FixedWidthFieldReader reader = accountRow0();
            byte[] first = reader.toByteArray();
            byte[] second = reader.toByteArray();

            // Distinct arrays, each independently equal to the hand-written image's bytes; the
            // pair comparison states the identity property rather than supplying an expectation.
            assertThat(first).isNotSameAs(second);
            assertThat(first).isEqualTo(ACCOUNT_IMAGE_ROW_0.getBytes(StandardCharsets.US_ASCII));
            assertThat(second).isEqualTo(ACCOUNT_IMAGE_ROW_0.getBytes(StandardCharsets.US_ASCII));
            assertThat(first).hasSize(ACCOUNT_WIDTH);

            // Mutating what was handed out cannot reach the reader's own image.
            first[0] = (byte) 0x39;
            assertThat(reader.field("ACCT-ID", 0, ACCOUNT_KEY_LENGTH)).isEqualTo("00000000001");
            assertThat(reader.toByteArray())
                    .isEqualTo(ACCOUNT_IMAGE_ROW_0.getBytes(StandardCharsets.US_ASCII));
        }

        @Test
        @DisplayName("a field's byte slice is a fresh array too")
        void aFieldByteSliceIsAFreshArray() {
            FixedWidthFieldReader reader = accountRow0();
            byte[] first = reader.fieldBytes("ACCT-ID", 0, ACCOUNT_KEY_LENGTH);
            byte[] second = reader.fieldBytes("ACCT-ID", 0, ACCOUNT_KEY_LENGTH);

            assertThat(first).isNotSameAs(second);
            first[0] = (byte) 0x39;

            assertThat(reader.field("ACCT-ID", 0, ACCOUNT_KEY_LENGTH)).isEqualTo("00000000001");
            assertThat(reader.fieldBytes("ACCT-ID", 0, ACCOUNT_KEY_LENGTH))
                    .isEqualTo("00000000001".getBytes(StandardCharsets.US_ASCII));
        }

        @Test
        @DisplayName("repeated reads of the same field are stable")
        void repeatedReadsAreStable() {
            FixedWidthFieldReader reader = transactionRow0();

            for (int attempt = 0; attempt < 3; attempt++) {
                assertThat(reader.field("TRAN-CARD-NUM", 262, 16)).isEqualTo("4859452612877065");
                assertThat(reader.field("TRAN-PROC-TS", 304, 26)).isEqualTo(spaces(26));
                assertThat(reader.recordWidth()).isEqualTo(350);
            }
        }

        @Test
        @DisplayName("the diagnostic description names the layout and width and hides the content")
        void theDiagnosticDescriptionHidesTheContent() {
            // Record images carry the estate's most sensitive data, so the description deliberately
            // excludes it: a reader that printed its content would leak it into any log line or
            // assertion failure that happened to render the object.
            FixedWidthFieldReader reader = accountRow0();

            assertThat(reader.toString()).isEqualTo("FixedWidthFieldReader[ACCOUNT, 300 bytes]");
            assertThat(reader.toString()).doesNotContain("00000000001");
            assertThat(reader.toString()).doesNotContain("A000000000");
            assertThat(FixedWidthFieldReader.of(CARD, CARD_IMAGE_ROW_0, CARD_WIDTH).toString())
                    .isEqualTo("FixedWidthFieldReader[CARD, 150 bytes]");
            // The verification code and the primary account number never appear in it.
            assertThat(FixedWidthFieldReader.of(CARD, CARD_IMAGE_ROW_0, CARD_WIDTH).toString())
                    .doesNotContain("747");
            assertThat(FixedWidthFieldReader.of(CARD, CARD_IMAGE_ROW_0, CARD_WIDTH).toString())
                    .doesNotContain("0500024453765740");
        }
    }

    @Nested
    @DisplayName("encoded length")
    class EncodedLengthMeasurement {

        @Test
        @DisplayName("a value is measured in encoded bytes, not in characters")
        void aValueIsMeasuredInEncodedBytes() {
            // Each expectation is a hand-counted number, cross-checked against the JDK's own encoder
            // rather than against the class under test.
            assertThat(FixedWidthFieldReader.encodedLength("")).isZero();
            assertThat(FixedWidthFieldReader.encodedLength("007")).isEqualTo(3);
            assertThat(FixedWidthFieldReader.encodedLength("00000000001")).isEqualTo(11);
            assertThat(FixedWidthFieldReader.encodedLength("DEFAULT   ")).isEqualTo(10);
            assertThat(FixedWidthFieldReader.encodedLength(spaces(26))).isEqualTo(26);
            assertThat(FixedWidthFieldReader.encodedLength(ACCOUNT_IMAGE_ROW_0)).isEqualTo(300);

            assertThat(FixedWidthFieldReader.encodedLength("00000001940{"))
                    .isEqualTo(encodedBytes("00000001940{"));
            assertThat(FixedWidthFieldReader.encodedLength(CARD_XREF_IMAGE_ROW_0))
                    .isEqualTo(encodedBytes(CARD_XREF_IMAGE_ROW_0));
        }

        @Test
        @DisplayName("a value US-ASCII cannot represent has no width and is refused")
        void aNonRepresentableValueHasNoWidth() {
            // Measuring it would report a plausible but wrong width, because the encoder substitutes
            // a question mark for what it cannot represent.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FixedWidthFieldReader.encodedLength("Aniya V\u00F6n"))
                    .withMessageContaining("US-ASCII cannot represent")
                    .withMessageContaining("at index 7")
                    .withMessageContaining("0xF6");
        }
    }

}
