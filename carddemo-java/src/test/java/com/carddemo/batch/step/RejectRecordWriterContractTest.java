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
package com.carddemo.batch.step;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.carddemo.batch.step.RejectRecordWriter.RejectedTransaction;
import com.carddemo.domain.DailyTransaction;
import com.carddemo.domain.enums.RejectReason;
import com.carddemo.util.DailyTransactionRecordMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.core.io.FileSystemResource;

/**
 * Verifies the byte contract of the daily-transaction reject dataset writer.
 *
 * <p>The assertions here are deliberately made on <strong>encoded bytes</strong> rather than on
 * characters or on parsed values, because the contract under test is a fixed-width record image: a
 * 430-byte record built from a 350-byte source segment, a four-digit reason code and a 76-character
 * description. A test that compared parsed fields would pass while the dataset was one byte wide of
 * the allocation, which is the failure this suite exists to prevent.
 *
 * <p>Two further properties are asserted at the file level rather than at the method level, because
 * they cannot be observed any other way: that the finished stream is an exact multiple of the record
 * width, and that it contains no separator, terminator or byte-order mark of any kind. Those are
 * checked by writing to a real file and reading its bytes back, never by inspecting a builder's return
 * value.
 *
 * <p>Provenance of the expectations: the legacy program {@code CBTRN02C} and the job that runs it, at
 * commit SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is transcribed here.
 */
@DisplayName("RejectRecordWriter - 430-byte reject record contract")
class RejectRecordWriterContractTest {

    /** A representative purchase amount, at the layout's scale of two. */
    private static final BigDecimal PURCHASE_AMOUNT = new BigDecimal("42.75");

    /** A representative return amount, negative so the overpunched sign is exercised. */
    private static final BigDecimal RETURN_AMOUNT = new BigDecimal("-42.75");

    /** Origin descriptor of a point-of-sale purchase, at its contractual ten characters. */
    private static final String POS_SOURCE = "POS TERM  ";

    /** Origin descriptor of an operator-originated return, at its contractual ten characters. */
    private static final String OPERATOR_SOURCE = "OPERATOR  ";

    /** The default locale in force before a test replaced it, restored afterwards. */
    private Locale originalLocale;

    /**
     * Restores the default locale after any test that replaced it, so a locale-hostility check cannot
     * leak into the rest of the suite.
     */
    @AfterEach
    void restoreLocale() {
        if (this.originalLocale != null) {
            Locale.setDefault(this.originalLocale);
            this.originalLocale = null;
        }
    }

    /**
     * Builds a fully populated daily-transaction record at the layout's contractual field widths.
     *
     * @param  transactionId the sixteen-character transaction identifier
     * @param  amount        the transaction amount at scale two
     * @param  source        the ten-character origin descriptor
     * @return a record the mapper can render without complaint
     */
    private static DailyTransaction transaction(final String transactionId, final BigDecimal amount,
            final String source) {
        return new DailyTransaction(transactionId, "01", "0005", source, "Grocery purchase", amount,
                "000000123", "Corner Store", "Seattle", "98101     ", "4111111111111111",
                "2022-01-01 10:00:00.000000", "2022-01-02 03:00:00.000000");
    }

    /**
     * Pairs a representative source record with a reject reason.
     *
     * @param  reason the reject reason to carry
     * @return an item the writer accepts
     */
    private static RejectedTransaction rejected(final RejectReason reason) {
        return new RejectedTransaction(
                transaction("0000000000000001", PURCHASE_AMOUNT, POS_SOURCE), reason);
    }

    /**
     * Measures a value the way the contract is stated: in encoded US-ASCII bytes.
     *
     * @param  value the value to measure
     * @return its encoded width in bytes
     */
    private static int encodedWidth(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    // ------------------------------------------------------------------------------------------
    // The independent oracle.
    //
    // Everything below reassembles the 430-byte reject record from literal layout offsets, the
    // literal field values that transaction() supplies, and the reason enumeration's own code and
    // description. It calls NEITHER the record mapper NOR the writer, which is the whole point: an
    // expectation produced by the code under test cannot fail when that code is wrong, and a
    // coordinated defect in the mapper and the writer would agree with itself. The widths below are
    // the copybook's own and are written as integer literals so a shifted layout cannot survive.
    // ------------------------------------------------------------------------------------------

    /** Declared width of the 350-byte source record image the reject record opens with. */
    private static final int SOURCE_WIDTH = 350;

    /** Declared width of the four-digit reason code that opens the trailer. */
    private static final int REASON_CODE_WIDTH = 4;

    /** Declared width of the reason description that follows the code. */
    private static final int REASON_DESCRIPTION_WIDTH = 76;

    /** Declared width of the whole reject record: the source image plus the trailer. */
    private static final int REJECT_WIDTH = 430;

    /** The ten characters that overpunch a positive final digit, zero through nine in order. */
    private static final String POSITIVE_OVERPUNCH = "{ABCDEFGHI";

    /** The ten characters that overpunch a negative final digit, zero through nine in order. */
    private static final String NEGATIVE_OVERPUNCH = "}JKLMNOPQR";

    /**
     * Places a value the way a {@code PIC X(n)} field holds it: left-justified, space-padded.
     *
     * @param  value the significant content
     * @param  width the declared field width
     * @return the value at exactly {@code width} characters
     */
    private static String alphanumeric(final String value, final int width) {
        return value + " ".repeat(width - value.length());
    }

    /**
     * Encodes an amount the way an eleven-byte {@code PIC S9(09)V99} field holds it, by hand.
     *
     * <p>Eleven digit positions, right-justified and zero-filled, with the sign folded into the final
     * byte from the two overpunch alphabets above. No production codec is involved.
     *
     * @param  amount the amount, already at scale two
     * @return its eleven-byte zoned image
     */
    private static String zonedAmountImage(final BigDecimal amount) {
        String digits = amount.abs().movePointRight(2).toBigIntegerExact().toString();
        digits = "0".repeat(11 - digits.length()) + digits;
        final int finalDigit = digits.charAt(10) - '0';
        final char sign = amount.signum() < 0
                ? NEGATIVE_OVERPUNCH.charAt(finalDigit)
                : POSITIVE_OVERPUNCH.charAt(finalDigit);
        return digits.substring(0, 10) + sign;
    }

    /**
     * Reassembles, by hand, the 350-byte source image of the record {@link #transaction} builds.
     *
     * <p>The literal field values are the same ones that method supplies, and the padding is applied
     * here by this class's own helper at the copybook's declared widths.
     *
     * @param  transactionId the sixteen-character transaction identifier
     * @param  amount        the transaction amount at scale two
     * @param  source        the ten-character origin descriptor
     * @return the source image, exactly {@value #SOURCE_WIDTH} characters
     */
    private static String sourceImage(final String transactionId, final BigDecimal amount,
            final String source) {
        final String image = alphanumeric(transactionId, 16)
                + alphanumeric("01", 2)
                + alphanumeric("0005", 4)
                + alphanumeric(source, 10)
                + alphanumeric("Grocery purchase", 100)
                + zonedAmountImage(amount)
                + alphanumeric("000000123", 9)
                + alphanumeric("Corner Store", 50)
                + alphanumeric("Seattle", 50)
                + alphanumeric("98101     ", 10)
                + alphanumeric("4111111111111111", 16)
                + alphanumeric("2022-01-01 10:00:00.000000", 26)
                + alphanumeric("2022-01-02 03:00:00.000000", 26)
                + " ".repeat(20);
        assertThat(encodedWidth(image))
                .as("this class's own source-image oracle must itself be 350 bytes")
                .isEqualTo(SOURCE_WIDTH);
        return image;
    }

    /**
     * Reassembles, by hand, the 80-byte validation trailer for a reason.
     *
     * <p>A four-digit zero-filled code followed by the description padded out to its full field. The
     * code and the description come from the enumeration, which is the contract; the geometry comes
     * from the two width literals above.
     *
     * @param  reason the reject reason
     * @return the trailer, exactly 80 characters
     */
    private static String trailerImage(final RejectReason reason) {
        final String code = String.valueOf(reason.getReasonCode());
        final String trailer = "0".repeat(REASON_CODE_WIDTH - code.length()) + code
                + alphanumeric(reason.getDescription(), REASON_DESCRIPTION_WIDTH);
        assertThat(encodedWidth(trailer))
                .as("this class's own trailer oracle must itself be 80 bytes")
                .isEqualTo(REASON_CODE_WIDTH + REASON_DESCRIPTION_WIDTH);
        return trailer;
    }

    /**
     * Reassembles, by hand, the complete 430-byte reject record.
     *
     * @param  transactionId the sixteen-character transaction identifier
     * @param  amount        the transaction amount at scale two
     * @param  source        the ten-character origin descriptor
     * @param  reason        the reject reason the trailer carries
     * @return the reject record, exactly {@value #REJECT_WIDTH} characters
     */
    private static String rejectImage(final String transactionId, final BigDecimal amount,
            final String source, final RejectReason reason) {
        final String image = sourceImage(transactionId, amount, source) + trailerImage(reason);
        assertThat(encodedWidth(image))
                .as("this class's own reject-record oracle must itself be 430 bytes")
                .isEqualTo(REJECT_WIDTH);
        return image;
    }

    @Nested
    @DisplayName("declared geometry")
    class DeclaredGeometry {

        @Test
        @DisplayName("matches the file description, the working-storage record and the job DD")
        void matchesTheLegacyDeclarations() {
            assertThat(RejectRecordWriter.SOURCE_IMAGE_LENGTH).isEqualTo(350);
            assertThat(RejectRecordWriter.FAIL_REASON_LENGTH).isEqualTo(4);
            assertThat(RejectRecordWriter.FAIL_REASON_DESC_LENGTH).isEqualTo(76);
            assertThat(RejectRecordWriter.VALIDATION_TRAILER_LENGTH).isEqualTo(80);
            assertThat(RejectRecordWriter.REJECT_RECORD_LENGTH).isEqualTo(430);
        }

        @Test
        @DisplayName("adds up, so the fields cannot drift apart from the record width")
        void addsUp() {
            assertThat(RejectRecordWriter.FAIL_REASON_LENGTH
                    + RejectRecordWriter.FAIL_REASON_DESC_LENGTH)
                    .isEqualTo(RejectRecordWriter.VALIDATION_TRAILER_LENGTH);
            assertThat(RejectRecordWriter.SOURCE_IMAGE_LENGTH
                    + RejectRecordWriter.VALIDATION_TRAILER_LENGTH)
                    .isEqualTo(RejectRecordWriter.REJECT_RECORD_LENGTH);
        }

        @Test
        @DisplayName("takes the source width from the mapper rather than restating it")
        void takesTheSourceWidthFromTheMapper() {
            assertThat(RejectRecordWriter.SOURCE_IMAGE_LENGTH)
                    .isEqualTo(DailyTransactionRecordMapper.RECORD_LENGTH);
        }

        @Test
        @DisplayName("names an empty separator and an explicit charset")
        void namesAnEmptySeparatorAndAnExplicitCharset() {
            assertThat(RejectRecordWriter.RECORD_SEPARATOR).isEmpty();
            assertThat(RejectRecordWriter.OUTPUT_CHARSET_NAME)
                    .isEqualTo(StandardCharsets.US_ASCII.name());
            assertThat(RejectRecordWriter.PAD_CHARACTER).isEqualTo(' ');
            assertThat(RejectRecordWriter.REASON_FILL_DIGIT).isEqualTo('0');
            assertThat(RejectRecordWriter.LEGACY_PROGRAM).isEqualTo("CBTRN02C");
            assertThat(RejectRecordWriter.LEGACY_DD_NAME).isEqualTo("DALYREJS");
            assertThat(RejectRecordWriter.ARTEFACT).isNotBlank();
            assertThat(RejectRecordWriter.EXECUTION_CONTEXT_NAME).isNotBlank();
        }
    }

    @Nested
    @DisplayName("validation trailer")
    class ValidationTrailer {

        @ParameterizedTest
        @EnumSource(RejectReason.class)
        @DisplayName("is exactly eighty encoded bytes for every reason")
        void isExactlyEightyBytes(final RejectReason reason) {
            assertThat(encodedWidth(RejectRecordWriter.validationTrailer(reason))).isEqualTo(80);
            assertThat(encodedWidth(RejectRecordWriter.failReasonField(reason))).isEqualTo(4);
            assertThat(encodedWidth(RejectRecordWriter.failReasonDescriptionField(reason)))
                    .isEqualTo(76);
        }

        @ParameterizedTest
        @CsvSource({
            "INVALID_CARD_NUMBER,0100,INVALID CARD NUMBER FOUND",
            "ACCOUNT_NOT_FOUND_ON_READ,0101,ACCOUNT RECORD NOT FOUND",
            "OVERLIMIT_TRANSACTION,0102,OVERLIMIT TRANSACTION",
            "TRANSACTION_AFTER_ACCOUNT_EXPIRATION,0103,TRANSACTION RECEIVED AFTER ACCT EXPIRATION",
            "ACCOUNT_NOT_FOUND_ON_REWRITE,0109,ACCOUNT RECORD NOT FOUND"
        })
        @DisplayName("renders a four-digit zero-filled code and the verbatim description")
        void rendersTheContractualFields(final RejectReason reason, final String expectedCode,
                final String expectedDescription) {
            String trailer = RejectRecordWriter.validationTrailer(reason);

            assertThat(RejectRecordWriter.failReasonField(reason)).isEqualTo(expectedCode);
            assertThat(trailer).startsWith(expectedCode);
            assertThat(trailer.substring(4)).startsWith(expectedDescription);
            assertThat(trailer.substring(4 + expectedDescription.length()))
                    .isEqualTo(" ".repeat(76 - expectedDescription.length()));
        }

        @Test
        @DisplayName("keeps codes 101 and 109 distinct even though their descriptions are identical")
        void keepsTheTwoAccountNotFoundReasonsDistinct() {
            String onRead = RejectRecordWriter
                    .validationTrailer(RejectReason.ACCOUNT_NOT_FOUND_ON_READ);
            String onRewrite = RejectRecordWriter
                    .validationTrailer(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE);

            // The shared description is stated as a literal rather than by comparing the two
            // production outputs to each other, which would hold even if both were wrong.
            String sharedDescription =
                    alphanumeric("ACCOUNT RECORD NOT FOUND", REASON_DESCRIPTION_WIDTH);

            assertThat(onRead).isNotEqualTo(onRewrite);
            assertThat(onRead).isEqualTo("0101" + sharedDescription);
            assertThat(onRewrite).isEqualTo("0109" + sharedDescription);
            assertThat(RejectRecordWriter
                    .failReasonDescriptionField(RejectReason.ACCOUNT_NOT_FOUND_ON_READ))
                    .isEqualTo(sharedDescription);
            assertThat(RejectRecordWriter
                    .failReasonDescriptionField(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE))
                    .isEqualTo(sharedDescription);
        }

        @ParameterizedTest
        @EnumSource(RejectReason.class)
        @DisplayName("pads with spaces only, never with a null or a control byte")
        void padsWithSpacesOnly(final RejectReason reason) {
            String description = RejectRecordWriter.failReasonDescriptionField(reason);

            assertThat(description).startsWith(reason.getDescription());
            assertThat(description.substring(reason.getDescription().length()).chars())
                    .allMatch(codePoint -> codePoint == ' ');
        }

        @Test
        @DisplayName("renders digits that survive a locale whose default numbering system is not "
                + "Western Arabic")
        void survivesAHostileLocale() {
            RejectRecordWriterContractTest.this.originalLocale = Locale.getDefault();
            List<Locale> hostile = List.of(Locale.forLanguageTag("tr-TR"),
                    Locale.forLanguageTag("ar-EG-u-nu-arab"),
                    Locale.forLanguageTag("hi-IN-u-nu-deva"));

            for (Locale locale : hostile) {
                Locale.setDefault(locale);
                for (RejectReason reason : RejectReason.values()) {
                    String field = RejectRecordWriter.failReasonField(reason);
                    assertThat(encodedWidth(field)).isEqualTo(4);
                    assertThat(field).containsOnlyDigits();
                    assertThat(Integer.parseInt(field)).isEqualTo(reason.getReasonCode());
                }
            }
        }
    }

    @Nested
    @DisplayName("reject record image")
    class RejectRecordImage {

        @ParameterizedTest
        @EnumSource(RejectReason.class)
        @DisplayName("is exactly 430 encoded bytes, measured on the encoding and not the characters")
        void isExactlyFourHundredAndThirtyBytes(final RejectReason reason) {
            RejectedTransaction item = rejected(reason);

            assertThat(encodedWidth(RejectRecordWriter.rejectRecordImage(item))).isEqualTo(430);
            assertThat(RejectRecordWriter.rejectRecordImageBytes(item)).hasSize(430);
        }

        @Test
        @DisplayName("places the source record image first, byte for byte and unaltered, compared "
                + "against an image this class assembles from the layout rather than from the mapper")
        void placesTheSourceImageFirst() {
            DailyTransaction source = transaction("0000000000000002", RETURN_AMOUNT,
                    OPERATOR_SOURCE);
            RejectedTransaction item = new RejectedTransaction(source,
                    RejectReason.OVERLIMIT_TRANSACTION);

            byte[] image = RejectRecordWriter.rejectRecordImageBytes(item);

            // The expected side is hand-assembled at the copybook's declared offsets, so a
            // coordinated defect in the mapper and the writer cannot agree with itself and pass.
            assertThat(Arrays.copyOfRange(image, 0, SOURCE_WIDTH))
                    .isEqualTo(sourceImage("0000000000000002", RETURN_AMOUNT, OPERATOR_SOURCE)
                            .getBytes(StandardCharsets.US_ASCII));
        }

        @Test
        @DisplayName("places the validation trailer immediately after the source image")
        void placesTheTrailerSecond() {
            RejectedTransaction item = rejected(RejectReason.INVALID_CARD_NUMBER);

            byte[] image = RejectRecordWriter.rejectRecordImageBytes(item);

            assertThat(new String(Arrays.copyOfRange(image, SOURCE_WIDTH, REJECT_WIDTH),
                            StandardCharsets.US_ASCII))
                    .isEqualTo(trailerImage(RejectReason.INVALID_CARD_NUMBER));
        }

        @Test
        @DisplayName("assembles the whole 430-byte record exactly as this class assembles it from the "
                + "layout, so both halves and their junction are proved at once")
        void assemblesTheWholeRecordAsTheLayoutPrescribes() {
            RejectedTransaction purchase = new RejectedTransaction(
                    transaction("0000000000000004", PURCHASE_AMOUNT, POS_SOURCE),
                    RejectReason.TRANSACTION_AFTER_ACCOUNT_EXPIRATION);
            RejectedTransaction refund = new RejectedTransaction(
                    transaction("0000000000000005", RETURN_AMOUNT, OPERATOR_SOURCE),
                    RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE);

            assertThat(RejectRecordWriter.rejectRecordImage(purchase))
                    .isEqualTo(rejectImage("0000000000000004", PURCHASE_AMOUNT, POS_SOURCE,
                            RejectReason.TRANSACTION_AFTER_ACCOUNT_EXPIRATION));
            assertThat(RejectRecordWriter.rejectRecordImageBytes(refund))
                    .isEqualTo(rejectImage("0000000000000005", RETURN_AMOUNT, OPERATOR_SOURCE,
                            RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE)
                            .getBytes(StandardCharsets.US_ASCII));
        }

        @Test
        @DisplayName("carries a negative amount and its trailing spaces through unchanged")
        void carriesANegativeAmountThrough() {
            DailyTransaction source = transaction("0000000000000003", RETURN_AMOUNT,
                    OPERATOR_SOURCE);
            RejectedTransaction item = new RejectedTransaction(source,
                    RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE);

            byte[] image = RejectRecordWriter.rejectRecordImageBytes(item);
            String emitted = new String(Arrays.copyOfRange(image, 0, SOURCE_WIDTH),
                    StandardCharsets.US_ASCII);

            // Read back by slicing at the layout's own offsets rather than by handing the bytes to the
            // mapper: the point is that the negative overpunch and the ten-byte origin descriptor
            // survive as BYTES, which a round trip through the mapper would not establish.
            assertThat(emitted)
                    .isEqualTo(sourceImage("0000000000000003", RETURN_AMOUNT, OPERATOR_SOURCE));
            assertThat(emitted.substring(132, 143))
                    .as("the amount keeps its negative overpunch in its final byte")
                    .isEqualTo(zonedAmountImage(RETURN_AMOUNT))
                    .endsWith("N");
            assertThat(emitted.substring(22, 32))
                    .as("the ten-byte origin descriptor keeps its trailing spaces")
                    .isEqualTo(OPERATOR_SOURCE);
        }

        @Test
        @DisplayName("returns the same bytes whether asked for text or for bytes")
        void agreesWithItself() {
            RejectedTransaction item = rejected(RejectReason.OVERLIMIT_TRANSACTION);

            assertThat(RejectRecordWriter.rejectRecordImageBytes(item))
                    .isEqualTo(RejectRecordWriter.rejectRecordImage(item)
                            .getBytes(StandardCharsets.US_ASCII));
        }

        @Test
        @DisplayName("returns a fresh array so a caller cannot corrupt a later record")
        void returnsAFreshArray() {
            RejectedTransaction item = rejected(RejectReason.INVALID_CARD_NUMBER);

            byte[] first = RejectRecordWriter.rejectRecordImageBytes(item);
            Arrays.fill(first, (byte) '#');

            assertThat(RejectRecordWriter.rejectRecordImageBytes(item)).isNotEqualTo(first);
        }

        @ParameterizedTest
        @EnumSource(RejectReason.class)
        @DisplayName("carries no terminator, no control byte and nothing outside US-ASCII")
        void carriesNoTerminator(final RejectReason reason) {
            String image = RejectRecordWriter.rejectRecordImage(rejected(reason));

            assertThat(image).doesNotContain("\n").doesNotContain("\r").doesNotContain("\u0000");
            assertThat(image.chars()).allMatch(codePoint -> codePoint <= 0x7F);
        }
    }

    @Nested
    @DisplayName("fixed unblocked output")
    class FixedUnblockedOutput {

        @Test
        @DisplayName("writes a stream that is an exact multiple of the record width")
        void writesAnExactMultipleOfTheRecordWidth(@TempDir final Path directory) throws Exception {
            Path target = directory.resolve("dalyrejs-multiple.dat");
            RejectRecordWriter writer = new RejectRecordWriter(new FileSystemResource(target),
                    new SimpleMeterRegistry());
            List<RejectedTransaction> items = representativeItems();

            ExecutionContext context = new ExecutionContext();
            writer.open(context);
            writer.write(new Chunk<>(items));
            writer.update(context);
            writer.close();

            byte[] written = Files.readAllBytes(target);
            assertThat(written).hasSize(items.size() * RejectRecordWriter.REJECT_RECORD_LENGTH);
            assertThat(written.length % RejectRecordWriter.REJECT_RECORD_LENGTH).isZero();
        }

        @Test
        @DisplayName("writes exactly the concatenation of the images, with no delimiter or mark")
        void writesTheConcatenationOfTheImages(@TempDir final Path directory) throws Exception {
            Path target = directory.resolve("dalyrejs-concatenation.dat");
            RejectRecordWriter writer = new RejectRecordWriter(new FileSystemResource(target),
                    new SimpleMeterRegistry());
            List<RejectedTransaction> items = representativeItems();

            ExecutionContext context = new ExecutionContext();
            writer.open(context);
            writer.write(new Chunk<>(items));
            writer.update(context);
            writer.close();

            StringBuilder expected = new StringBuilder();
            for (String image : representativeExpectedImages()) {
                expected.append(image);
            }
            byte[] written = Files.readAllBytes(target);

            assertThat(written).isEqualTo(expected.toString().getBytes(StandardCharsets.US_ASCII));
            assertThat(written).doesNotContain((byte) '\n').doesNotContain((byte) '\r');
            assertThat(written[0]).isNotEqualTo((byte) 0xEF);
            for (byte value : written) {
                assertThat(value & 0xFF).isLessThanOrEqualTo(0x7F);
            }
        }

        @Test
        @DisplayName("keeps every record addressable at its own multiple of the record width")
        void keepsEveryRecordAddressable(@TempDir final Path directory) throws Exception {
            Path target = directory.resolve("dalyrejs-addressable.dat");
            RejectRecordWriter writer = new RejectRecordWriter(new FileSystemResource(target),
                    new SimpleMeterRegistry());
            List<RejectedTransaction> items = representativeItems();

            ExecutionContext context = new ExecutionContext();
            writer.open(context);
            writer.write(new Chunk<>(items));
            writer.update(context);
            writer.close();

            byte[] written = Files.readAllBytes(target);
            List<String> expected = representativeExpectedImages();
            for (int index = 0; index < items.size(); index++) {
                int start = index * REJECT_WIDTH;
                assertThat(new String(Arrays.copyOfRange(written, start, start + REJECT_WIDTH),
                        StandardCharsets.US_ASCII))
                        .as("record %d must equal the image this class assembles from the layout",
                                index)
                        .isEqualTo(expected.get(index));
            }
        }

        @Test
        @DisplayName("spreads records across chunks without disturbing a boundary")
        void spreadsRecordsAcrossChunks(@TempDir final Path directory) throws Exception {
            Path target = directory.resolve("dalyrejs-chunks.dat");
            RejectRecordWriter writer = new RejectRecordWriter(new FileSystemResource(target),
                    new SimpleMeterRegistry());

            ExecutionContext context = new ExecutionContext();
            writer.open(context);
            for (int chunk = 0; chunk < 4; chunk++) {
                writer.write(Chunk.of(
                        new RejectedTransaction(transaction("000000000000000" + chunk,
                                PURCHASE_AMOUNT, POS_SOURCE), RejectReason.INVALID_CARD_NUMBER),
                        new RejectedTransaction(transaction("000000000000001" + chunk,
                                RETURN_AMOUNT, OPERATOR_SOURCE),
                                RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE)));
                writer.update(context);
            }
            writer.close();

            assertThat(Files.readAllBytes(target))
                    .hasSize(8 * RejectRecordWriter.REJECT_RECORD_LENGTH);
            assertThat(writer.recordsWritten()).isEqualTo(8L);
        }

        @Test
        @DisplayName("contributes nothing for an empty chunk")
        void contributesNothingForAnEmptyChunk(@TempDir final Path directory) throws Exception {
            Path target = directory.resolve("dalyrejs-empty-chunk.dat");
            RejectRecordWriter writer = new RejectRecordWriter(new FileSystemResource(target),
                    new SimpleMeterRegistry());

            ExecutionContext context = new ExecutionContext();
            writer.open(context);
            writer.write(new Chunk<>(List.of()));
            writer.update(context);
            writer.close();

            assertThat(Files.readAllBytes(target)).isEmpty();
            assertThat(writer.recordsWritten()).isZero();
        }

        @Test
        @DisplayName("leaves an empty dataset behind rather than none when nothing was rejected")
        void leavesAnEmptyDatasetBehind(@TempDir final Path directory) throws Exception {
            Path target = directory.resolve("dalyrejs-no-rejects.dat");
            RejectRecordWriter writer = new RejectRecordWriter(new FileSystemResource(target),
                    new SimpleMeterRegistry());

            ExecutionContext context = new ExecutionContext();
            writer.open(context);
            writer.update(context);
            writer.close();

            assertThat(target).exists();
            assertThat(Files.readAllBytes(target)).isEmpty();
        }

        /**
         * Builds one rejected transaction per reject reason, alternating the sign of the amount and the
         * origin descriptor so that both signed directions and both descriptors are written.
         *
         * @return the representative items, one per reason
         */
        private List<RejectedTransaction> representativeItems() {
            List<RejectedTransaction> items = new ArrayList<>();
            RejectReason[] reasons = RejectReason.values();
            for (int index = 0; index < reasons.length; index++) {
                boolean purchase = index % 2 == 0;
                items.add(new RejectedTransaction(
                        transaction(String.format(Locale.ROOT, "%016d", index),
                                purchase ? PURCHASE_AMOUNT : RETURN_AMOUNT,
                                purchase ? POS_SOURCE : OPERATOR_SOURCE),
                        reasons[index]));
            }
            return items;
        }

        /**
         * The same representative population, as the 430-byte images this class assembles by hand.
         *
         * <p>Deliberately a second, parallel loop rather than a projection of
         * {@link #representativeItems()}: every image here is built from layout offsets and literal
         * field values, so the expected side of the file comparisons never passes through the mapper
         * or the writer that produced the actual side.
         *
         * @return the expected reject records, in the order the writer receives them
         */
        private List<String> representativeExpectedImages() {
            List<String> images = new ArrayList<>();
            RejectReason[] reasons = RejectReason.values();
            for (int index = 0; index < reasons.length; index++) {
                boolean purchase = index % 2 == 0;
                images.add(rejectImage(String.format(Locale.ROOT, "%016d", index),
                        purchase ? PURCHASE_AMOUNT : RETURN_AMOUNT,
                        purchase ? POS_SOURCE : OPERATOR_SOURCE, reasons[index]));
            }
            return images;
        }
    }

    @Nested
    @DisplayName("lifecycle and restart")
    class LifecycleAndRestart {

        @Test
        @DisplayName("resumes at the last committed boundary rather than duplicating")
        void resumesRatherThanDuplicating(@TempDir final Path directory) throws Exception {
            Path target = directory.resolve("dalyrejs-restart.dat");
            ExecutionContext context = new ExecutionContext();

            RejectRecordWriter first = new RejectRecordWriter(new FileSystemResource(target),
                    new SimpleMeterRegistry());
            first.open(context);
            first.write(Chunk.of(rejected(RejectReason.INVALID_CARD_NUMBER)));
            first.update(context);
            first.close();
            assertThat(Files.readAllBytes(target))
                    .hasSize(RejectRecordWriter.REJECT_RECORD_LENGTH);

            RejectRecordWriter resumed = new RejectRecordWriter(new FileSystemResource(target),
                    new SimpleMeterRegistry());
            resumed.open(context);
            resumed.write(Chunk.of(rejected(RejectReason.OVERLIMIT_TRANSACTION)));
            resumed.update(context);
            resumed.close();

            byte[] written = Files.readAllBytes(target);
            assertThat(written).hasSize(2 * REJECT_WIDTH);
            assertThat(new String(Arrays.copyOfRange(written, SOURCE_WIDTH, REJECT_WIDTH),
                    StandardCharsets.US_ASCII))
                    .isEqualTo(trailerImage(RejectReason.INVALID_CARD_NUMBER));
            assertThat(new String(Arrays.copyOfRange(written, REJECT_WIDTH + SOURCE_WIDTH,
                    2 * REJECT_WIDTH), StandardCharsets.US_ASCII))
                    .isEqualTo(trailerImage(RejectReason.OVERLIMIT_TRANSACTION));
        }

        @Test
        @DisplayName("counts only what the current execution wrote, resetting when the stream opens")
        void countsOnlyTheCurrentExecution(@TempDir final Path directory) throws Exception {
            Path target = directory.resolve("dalyrejs-count.dat");
            ExecutionContext context = new ExecutionContext();
            RejectRecordWriter writer = new RejectRecordWriter(new FileSystemResource(target),
                    new SimpleMeterRegistry());

            writer.open(context);
            assertThat(writer.recordsWritten()).isZero();
            writer.write(Chunk.of(rejected(RejectReason.INVALID_CARD_NUMBER),
                    rejected(RejectReason.OVERLIMIT_TRANSACTION)));
            assertThat(writer.recordsWritten()).isEqualTo(2L);
            writer.update(context);
            writer.close();

            writer.open(context);
            assertThat(writer.recordsWritten()).isZero();
            writer.close();
        }

        @Test
        @DisplayName("replaces an existing dataset on a fresh run, as a new allocation would")
        void replacesAnExistingDatasetOnAFreshRun(@TempDir final Path directory) throws Exception {
            Path target = directory.resolve("dalyrejs-replaced.dat");
            Files.write(target, "stale content that no allocation would keep"
                    .getBytes(StandardCharsets.US_ASCII));

            RejectRecordWriter writer = new RejectRecordWriter(new FileSystemResource(target),
                    new SimpleMeterRegistry());
            ExecutionContext context = new ExecutionContext();
            writer.open(context);
            writer.write(Chunk.of(rejected(RejectReason.INVALID_CARD_NUMBER)));
            writer.update(context);
            writer.close();

            assertThat(Files.readAllBytes(target))
                    .hasSize(RejectRecordWriter.REJECT_RECORD_LENGTH);
        }
    }

    @Nested
    @DisplayName("failure handling")
    class FailureHandling {

        @Test
        @DisplayName("propagates a write against an unopened destination without inventing a code")
        void propagatesAWriteBeforeOpen(@TempDir final Path directory) {
            Path target = directory.resolve("dalyrejs-never-opened.dat");
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            RejectRecordWriter writer = new RejectRecordWriter(new FileSystemResource(target),
                    registry);

            Throwable thrown = catchThrowable(() -> writer
                    .write(Chunk.of(rejected(RejectReason.INVALID_CARD_NUMBER))));

            assertThat(thrown).isNotNull();
            assertThat(String.valueOf(thrown.getMessage()))
                    .doesNotContain("0100")
                    .doesNotContain("INVALID CARD NUMBER");
            assertThat(writer.recordsWritten()).isZero();
            assertThat(registry.find("carddemo.batch.reject.write").tag("outcome", "FAILED").timer())
                    .isNotNull();
            assertThat(registry.find("carddemo.batch.reject.write").tag("outcome", "WRITTEN")
                    .timer()).isNull();
        }

        @Test
        @DisplayName("propagates a destination that cannot be prepared")
        void propagatesAnUnusableDestination(@TempDir final Path directory) throws IOException {
            Path occupied = Files.createDirectory(directory.resolve("occupied.dat"));
            Files.createFile(occupied.resolve("keeps-the-directory-undeletable"));
            RejectRecordWriter writer = new RejectRecordWriter(new FileSystemResource(occupied),
                    new SimpleMeterRegistry());

            assertThatThrownBy(() -> writer.open(new ExecutionContext()))
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("never leaves a partial record behind when a write fails")
        void neverLeavesAPartialRecord(@TempDir final Path directory) throws Exception {
            Path target = directory.resolve("dalyrejs-partial.dat");
            RejectRecordWriter writer = new RejectRecordWriter(new FileSystemResource(target),
                    new SimpleMeterRegistry());
            ExecutionContext context = new ExecutionContext();

            writer.open(context);
            writer.write(Chunk.of(rejected(RejectReason.INVALID_CARD_NUMBER)));
            writer.update(context);
            writer.close();

            assertThat(catchThrowable(() -> writer
                    .write(Chunk.of(rejected(RejectReason.OVERLIMIT_TRANSACTION))))).isNotNull();
            assertThat(Files.readAllBytes(target).length
                    % RejectRecordWriter.REJECT_RECORD_LENGTH).isZero();
        }
    }

    @Nested
    @DisplayName("observability")
    class Observability {

        @Test
        @DisplayName("registers a record counter and a per-write timer on the injected registry")
        void registersItsMeters(@TempDir final Path directory) throws Exception {
            Path target = directory.resolve("dalyrejs-meters.dat");
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            RejectRecordWriter writer = new RejectRecordWriter(new FileSystemResource(target),
                    registry);

            assertThat(registry.find("carddemo.batch.reject.records").counter()).isNotNull();

            ExecutionContext context = new ExecutionContext();
            writer.open(context);
            writer.write(Chunk.of(rejected(RejectReason.INVALID_CARD_NUMBER),
                    rejected(RejectReason.OVERLIMIT_TRANSACTION)));
            writer.update(context);
            writer.close();

            assertThat(registry.find("carddemo.batch.reject.records")
                    .tag("resource", RejectRecordWriter.LEGACY_DD_NAME).counter().count())
                    .isEqualTo(2.0d);
            assertThat(registry.find("carddemo.batch.reject.write")
                    .tag("resource", RejectRecordWriter.LEGACY_DD_NAME)
                    .tag("outcome", "WRITTEN").timer().count()).isEqualTo(1L);
        }

        @Test
        @DisplayName("does not stand in for whole-step timing, which the step template owns")
        void doesNotOwnWholeStepTiming(@TempDir final Path directory) throws Exception {
            Path target = directory.resolve("dalyrejs-step-timer.dat");
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            RejectRecordWriter writer = new RejectRecordWriter(new FileSystemResource(target),
                    registry);

            ExecutionContext context = new ExecutionContext();
            writer.open(context);
            writer.write(Chunk.of(rejected(RejectReason.INVALID_CARD_NUMBER)));
            writer.update(context);
            writer.close();

            assertThat(registry.find("carddemo.batch.cobol.step").timer()).isNull();
        }
    }

    @Nested
    @DisplayName("argument validation")
    class ArgumentValidation {

        @Test
        @DisplayName("refuses a missing destination or registry")
        void refusesMissingCollaborators(@TempDir final Path directory) throws IOException {
            Path target = Files.createTempFile(directory, "collaborators", ".dat");

            assertThatThrownBy(() -> new RejectRecordWriter(null, new SimpleMeterRegistry()))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new RejectRecordWriter(new FileSystemResource(target), null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("refuses an item that names no record or no reason")
        void refusesAnIncompleteItem() {
            assertThatThrownBy(() -> new RejectedTransaction(null,
                    RejectReason.INVALID_CARD_NUMBER)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new RejectedTransaction(
                    transaction("0000000000000001", PURCHASE_AMOUNT, POS_SOURCE), null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("refuses a missing item, reason, chunk or execution context")
        void refusesMissingArguments(@TempDir final Path directory) throws IOException {
            Path target = Files.createTempFile(directory, "arguments", ".dat");
            RejectRecordWriter writer = new RejectRecordWriter(new FileSystemResource(target),
                    new SimpleMeterRegistry());

            assertThatThrownBy(() -> RejectRecordWriter.rejectRecordImage(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> RejectRecordWriter.rejectRecordImageBytes(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> RejectRecordWriter.validationTrailer(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> RejectRecordWriter.failReasonField(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> RejectRecordWriter.failReasonDescriptionField(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> writer.open(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> writer.update(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> writer.write(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("exposes the item type's own accessors so the processor can build it")
        void exposesTheItemTypeAccessors() {
            DailyTransaction source = transaction("0000000000000009", PURCHASE_AMOUNT, POS_SOURCE);
            RejectedTransaction item = new RejectedTransaction(source,
                    RejectReason.TRANSACTION_AFTER_ACCOUNT_EXPIRATION);

            assertThat(item.sourceRecord()).isSameAs(source);
            assertThat(item.reason()).isEqualTo(RejectReason.TRANSACTION_AFTER_ACCOUNT_EXPIRATION);
            assertThat(item).isEqualTo(new RejectedTransaction(source,
                    RejectReason.TRANSACTION_AFTER_ACCOUNT_EXPIRATION));
            assertThat(item).hasSameHashCodeAs(new RejectedTransaction(source,
                    RejectReason.TRANSACTION_AFTER_ACCOUNT_EXPIRATION));
            assertThat(item.toString()).contains("TRANSACTION_AFTER_ACCOUNT_EXPIRATION");
        }
    }
}
