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
import static org.assertj.core.api.Assertions.assertThatCode;

import com.carddemo.batch.step.RejectRecordWriter.RejectedTransaction;
import com.carddemo.domain.DailyTransaction;
import com.carddemo.domain.enums.RejectReason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.core.io.FileSystemResource;

/**
 * Verifies what {@link RejectRecordWriter} actually puts on disk, by writing through it and then
 * slicing the finished artefact at exact byte offsets.
 *
 * <p>This suite is deliberately positioned at the <em>artefact</em>, not at the assembly methods. A
 * test that checked only the assembled image would still pass while the destination inserted a
 * platform line ending after every record, shifted the second record by one byte, or emitted a
 * byte-order mark at the head of the file - three defects that a fixed-length unblocked dataset
 * cannot survive and that only the written bytes reveal. Every assertion below is therefore made on
 * bytes read back from the file, measured with
 * {@code getBytes(java.nio.charset.StandardCharsets.US_ASCII).length} rather than with
 * {@code String.length()}, and nothing is ever trimmed before it is compared.
 *
 * <h2>The record geometry under test, and the three places that declare it</h2>
 *
 * <p>The reject record is <strong>430 bytes</strong>: a <strong>350-byte</strong> copy of the source
 * daily-transaction image, followed by an <strong>80-byte</strong> validation trailer made of a
 * <strong>four-digit</strong> zero-padded numeric reason code and a <strong>76-character</strong>
 * space-padded description. 350 + 4 + 76 = 430, and 4 + 76 = 80.
 *
 * <p>Direct inspection found that geometry stated three independent times, and consistent every
 * time. The file description of the reject dataset in {@code app/cbl/CBTRN02C.cbl} lines 81 to 84
 * splits the record into a 350-byte reject portion and an 80-byte validation-trailer portion. The
 * working-storage record at lines 176 to 178 restates the same split, and the trailer is itself
 * broken out at lines 180 to 182 into a four-digit numeric failure-reason field and a 76-character
 * description field. The reject dataset's own job allocation, at {@code app/jcl/POSTTRAN.jcl} lines
 * 34 to 38, declares a record length of 430 with a fixed unblocked record format and a block size of
 * zero. Because all three agree, there is nothing here to reconcile.
 *
 * <p>The 350-byte segment is <strong>copied unchanged</strong>. The reject-write paragraph
 * {@code 2500-WRITE-REJECT-REC}, at {@code app/cbl/CBTRN02C.cbl} lines 446 to 449, moves the whole
 * inbound daily-transaction record into the leading segment without altering a byte of it, then moves
 * the assembled trailer into the segment behind it, then writes. The assertions here are consequently
 * byte-for-byte comparisons of the leading 350 bytes against a hand-built input image, never
 * field-by-field equivalence checks, because equivalence would hold even if a field had been
 * re-justified, re-encoded or re-scaled on the way through.
 *
 * <h2>A circulating 500-byte claim: searched for, and not found</h2>
 *
 * <p>A prior planning document alleged a 500-byte reject allocation. It <strong>was searched for in
 * the legacy source and does not exist</strong>: a case-inclusive search of
 * {@code app/cbl/CBTRN02C.cbl}, {@code app/jcl/POSTTRAN.jcl} and {@code app/jcl/DALYREJS.jcl} for a
 * 500-byte picture clause, record length, record size or space allocation returned no match at all.
 * The only reject widths present anywhere are 350 plus 80 in the program and 430 in the job. This is
 * recorded so that a later reviewer does not spend time chasing a phantom anomaly; it is
 * <strong>not</strong> a real source discrepancy, and no assertion in this file mentions that width.
 *
 * <h2>The five reason codes, and why two of them look redundant and are not</h2>
 *
 * <p>Five codes can reach the trailer, raised at {@code app/cbl/CBTRN02C.cbl} lines 385, 397, 410,
 * 417 and 556, and the reason field is cleared again at lines 208 to 209 before the next transaction
 * is validated. Their descriptions are contractual output that operators and downstream tooling match
 * on, so they are reproduced verbatim and padded, never reworded, abbreviated or trimmed: 25
 * characters and 51 spaces of padding for 0100, 24 and 52 for 0101, 21 and 55 for 0102, 42 and 34 for
 * 0103, and 24 and 52 for 0109.
 *
 * <p><strong>Codes 0101 and 0109 carry byte-identical description text and are nonetheless distinct
 * codes.</strong> The first reports an account read that missed during the validation cascade, before
 * anything is posted; the second reports an account rewrite that failed while updated balances were
 * being written back after a successful posting. They are never merged, never aliased, and neither
 * ever substitutes for the other. A dedicated test below writes both through the writer and asserts
 * that the 76-byte description block is identical while the four-digit code differs.
 *
 * <h2>Decision-log candidates arising from this suite</h2>
 *
 * <p>Four points where faithful translation beats the idiomatic alternative, each asserted below and
 * each recorded here rather than in any document under {@code docs/}:
 *
 * <ul>
 *   <li><strong>Identical description text, distinct codes.</strong> Consolidating 0101 and 0109
 *       would compile, would pass a naive test, and would silently stop emitting one of the five
 *       codes the trailer is contractually required to carry.
 *   <li><strong>A blank processing timestamp is content, not an absence.</strong> Every one of the
 *       300 records in the reference daily-transaction input carries 26 spaces in that field, so a
 *       copied-unchanged reject image must carry 26 spaces at offset 304 - not {@code null}, not an
 *       empty string, and not a rendered instant. The assertion for this is a regression guard
 *       against a stray normalisation on the way through.
 *   <li><strong>The dataset stays unblocked.</strong> The allocation is fixed unblocked rather than
 *       fixed blocked, so records abut with no delimiter at all. Inheriting the framework's default
 *       separator, which is the platform line ending, would append one or two stray bytes to every
 *       record and leave the file no longer a multiple of the record width.
 *   <li><strong>Nothing is ever trimmed.</strong> Trailing spaces in a fixed-width field are content.
 *       Every width here is asserted on the encoded form for that reason.
 * </ul>
 *
 * <h2>Independent oracle</h2>
 *
 * <p>Every expected value below is assembled inside this class from literal offsets, literal field
 * widths and the reason enumeration's own code and description. It calls neither the writer under
 * test nor its image methods, neither the daily-transaction record mapper nor the zoned-decimal
 * codec, and no formatter, template or other production assembler. The overpunched sign of the amount
 * is encoded here from first principles. An expectation produced by the code under test cannot fail
 * when that code is wrong, and a defect shared by the mapper and the writer would agree with itself.
 *
 * <p>One consequence is worth stating because it is easy to reintroduce by accident: no rescaling
 * operation appears anywhere in this file. The estate's truncating rounding policy has exactly one
 * home, the zoned-decimal codec, and this is not it, so the amount fixtures here are declared at the
 * scale a stored monetary field carries and the oracle simply renders them.
 *
 * <h2>Standards</h2>
 *
 * <p>{@code review_rules} reports that <strong>no user-specified rules were provided</strong> for
 * this migration, so this file is held to enterprise-standard best practice instead. Concretely: it
 * is a surefire-tier unit test that starts no Spring context, provisions no container and touches no
 * persistence; every temporary file it uses comes from JUnit's own temporary-directory support so the
 * run is hermetic and repeatable; it adds no dependency and no annotation processor; it names no
 * credential, no numeric performance figure and no wall-clock expectation; and it compiles under all
 * lint categories with warnings promoted to errors and no warning suppressed anywhere.
 *
 * <h2>Provenance</h2>
 *
 * <p>Legacy estate at commit SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release
 * stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * <p>That stamp is a <strong>matrix-header string only and is never asserted per member</strong>,
 * because it is demonstrably not universal: a census of the legacy tree found 78 members carrying it,
 * 3 carrying later stamps, all 17 screen definitions carrying a different one again, and 25 carrying
 * no stamp at all. A per-member assertion on it would fail on a third of the estate while proving
 * nothing about provenance. No legacy source line is transcribed here either: the source is cited by
 * member, paragraph, field and line number, and the only legacy text reproduced is the five
 * description literals, which are contract literals appearing byte for byte in program output.
 *
 * @see RejectRecordWriter
 * @see RejectReason
 */
@DisplayName("RejectRecordWriter - the 430-byte reject dataset as it reaches disk")
class RejectRecordWriterTest {

    // ----------------------------------------------------------------------------------------------
    // Declared geometry, written as integer literals.
    //
    // These are the copybook's and the job allocation's own numbers rather than references to the
    // production constants, so that a layout which shifted in production would be caught here instead
    // of being tracked silently.
    // ----------------------------------------------------------------------------------------------

    /** Width of the whole reject record, as the reject dataset is allocated. */
    private static final int REJECT_RECORD_WIDTH = 430;

    /** Width of the source-record segment the reject record opens with. */
    private static final int SOURCE_IMAGE_WIDTH = 350;

    /** Width of the validation trailer that follows the source segment. */
    private static final int VALIDATION_TRAILER_WIDTH = 80;

    /** Width of the numeric reason field at the head of the trailer. */
    private static final int REASON_CODE_WIDTH = 4;

    /** Width of the description field that closes the trailer. */
    private static final int REASON_DESCRIPTION_WIDTH = 76;

    /** Zero-based offset of the reason field within the reject record. */
    private static final int REASON_CODE_OFFSET = 350;

    /** Zero-based offset of the description field within the reject record. */
    private static final int REASON_DESCRIPTION_OFFSET = 354;

    // ----------------------------------------------------------------------------------------------
    // The 350-byte source layout, zero-based, so an image can be built by hand.
    // ----------------------------------------------------------------------------------------------

    /** Zero-based offset of the transaction identifier, which opens the source image. */
    private static final int ID_OFFSET = 0;

    /** Width of the transaction identifier. */
    private static final int ID_WIDTH = 16;

    /** Width of the transaction type code. */
    private static final int TYPE_CODE_WIDTH = 2;

    /** Width of the transaction category code. */
    private static final int CATEGORY_CODE_WIDTH = 4;

    /** Width of the origination source descriptor. */
    private static final int SOURCE_WIDTH = 10;

    /** Width of the transaction description. */
    private static final int DESCRIPTION_WIDTH = 100;

    /** Zero-based offset of the zoned-decimal amount field. */
    private static final int AMOUNT_OFFSET = 132;

    /** Width of the zoned-decimal amount field: nine integer digits and two fraction digits. */
    private static final int AMOUNT_WIDTH = 11;

    /**
     * Zero-based offset of the amount's overpunched sign byte, which is the field's final byte and
     * therefore one-based column 143 of the record.
     */
    private static final int SIGN_BYTE_OFFSET = 142;

    /** Width of the merchant identifier. */
    private static final int MERCHANT_ID_WIDTH = 9;

    /** Width of the merchant name. */
    private static final int MERCHANT_NAME_WIDTH = 50;

    /** Width of the merchant city. */
    private static final int MERCHANT_CITY_WIDTH = 50;

    /** Width of the merchant postal code. */
    private static final int MERCHANT_ZIP_WIDTH = 10;

    /** Width of the card number. */
    private static final int CARD_NUMBER_WIDTH = 16;

    /** Width of either timestamp lexeme. */
    private static final int TIMESTAMP_WIDTH = 26;

    /** Zero-based offset of the processing timestamp, which the reference input leaves blank. */
    private static final int PROCESSING_TIMESTAMP_OFFSET = 304;

    /** Width of the unmapped filler run that closes the source image. */
    private static final int FILLER_WIDTH = 20;

    /** Fraction digits every stored monetary field carries. */
    private static final int MONETARY_SCALE = 2;

    // ----------------------------------------------------------------------------------------------
    // The overpunched sign encoding, transcribed from the convention rather than from the codec.
    //
    // The sign folds into the field's final byte, which carries a digit and a sign together. This
    // class indexes both tables by the low-order digit, exactly as the convention defines them, and
    // never calls the production codec to obtain a signed image.
    // ----------------------------------------------------------------------------------------------

    /** Final byte of a non-negative zoned-decimal field, indexed by its low-order digit. */
    private static final String POSITIVE_OVERPUNCH = "{ABCDEFGHI";

    /** Final byte of a negative zoned-decimal field, indexed by its low-order digit. */
    private static final String NEGATIVE_OVERPUNCH = "}JKLMNOPQR";

    // ----------------------------------------------------------------------------------------------
    // Field values for the hand-built source image.
    //
    // Every one is presented at its exact declared width, so placing it into the record is a pure
    // byte copy and no justification or padding rule of the production layout participates in the
    // expectation. The same constants feed both the expected image and the entity handed to the
    // writer, which is what makes the two agree by construction without either consulting the other.
    // ----------------------------------------------------------------------------------------------

    /** Transaction type code, at its declared width. */
    private static final String TYPE_CODE = alphanumeric("01", TYPE_CODE_WIDTH);

    /** Transaction category code, right-justified and zero-filled as a numeric field is. */
    private static final String CATEGORY_CODE = zeroFilled("5", CATEGORY_CODE_WIDTH);

    /** Origination source of a point-of-sale purchase, carrying its contractual trailing spaces. */
    private static final String POS_SOURCE = alphanumeric("POS TERM", SOURCE_WIDTH);

    /** Origination source of an operator-entered return, carrying its contractual trailing spaces. */
    private static final String OPERATOR_SOURCE = alphanumeric("OPERATOR", SOURCE_WIDTH);

    /** Transaction description, space-padded to its declared width. */
    private static final String DESCRIPTION = alphanumeric("GROCERY PURCHASE", DESCRIPTION_WIDTH);

    /** Merchant identifier, right-justified and zero-filled as a numeric field is. */
    private static final String MERCHANT_ID = zeroFilled("123", MERCHANT_ID_WIDTH);

    /** Merchant name, space-padded to its declared width. */
    private static final String MERCHANT_NAME = alphanumeric("CORNER STORE", MERCHANT_NAME_WIDTH);

    /** Merchant city, space-padded to its declared width. */
    private static final String MERCHANT_CITY = alphanumeric("SEATTLE", MERCHANT_CITY_WIDTH);

    /** Merchant postal code, which is free-form text and not a number. */
    private static final String MERCHANT_ZIP = alphanumeric("98101", MERCHANT_ZIP_WIDTH);

    /** Card number, at its declared width. */
    private static final String CARD_NUMBER = alphanumeric("4111111111111111", CARD_NUMBER_WIDTH);

    /**
     * Origination timestamp, at its declared width.
     *
     * <p>All 300 records of the reference daily-transaction input share one origination timestamp, so
     * a single fixed value is representative rather than a simplification. It is a literal here and is
     * never derived from a clock, so nothing in this suite depends on when it runs.
     */
    private static final String ORIGINATION_TIMESTAMP =
            alphanumeric("2022-06-10 19:27:53.000000", TIMESTAMP_WIDTH);

    /**
     * Processing timestamp as the reference input actually carries it: entirely blank.
     *
     * <p>All 300 records leave this field as 26 spaces, because an unposted record has no processing
     * timestamp. Blank is a legitimate value of a fixed-width field, not an absent one.
     */
    private static final String BLANK_PROCESSING_TIMESTAMP =
            alphanumeric("", TIMESTAMP_WIDTH);

    /**
     * A populated processing timestamp, used only to prove that the blank-timestamp assertion is
     * discriminating rather than vacuously true of any input.
     */
    private static final String POSTED_PROCESSING_TIMESTAMP =
            alphanumeric("2022-06-11 04:15:00.000000", TIMESTAMP_WIDTH);

    /** The unmapped filler run that closes the source image, emitted as spaces. */
    private static final String FILLER = alphanumeric("", FILLER_WIDTH);

    /** A positive amount, declared at scale two so this oracle never rescales. */
    private static final BigDecimal PURCHASE_AMOUNT = new BigDecimal("42.75");

    /** A negative amount, declared at scale two, standing for an operator-entered return. */
    private static final BigDecimal RETURN_AMOUNT = new BigDecimal("-42.75");

    /** Identifier of the first record written by the multi-record tests. */
    private static final String FIRST_ID = alphanumeric("0000000000000001", ID_WIDTH);

    /** Identifier of the second record written by the multi-record tests. */
    private static final String SECOND_ID = alphanumeric("0000000000000002", ID_WIDTH);

    /** Identifier of the third record written by the multi-record tests. */
    private static final String THIRD_ID = alphanumeric("0000000000000003", ID_WIDTH);

    // ----------------------------------------------------------------------------------------------
    // The independent oracle.
    // ----------------------------------------------------------------------------------------------

    /**
     * Places a value left-justified in a field of the given width and pads it on the right with
     * spaces, which is how a fixed-width character field is filled.
     *
     * @param  value the value to place, which may be empty to obtain a field of spaces
     * @param  width the field's declared width in bytes
     * @return the field image, exactly {@code width} characters wide
     */
    private static String alphanumeric(final String value, final int width) {
        return value + " ".repeat(width - value.length());
    }

    /**
     * Places a value right-justified in a field of the given width and fills the leading pad with
     * zeros, which is how an unsigned numeric field is filled.
     *
     * @param  value the value to place
     * @param  width the field's declared width in bytes
     * @return the field image, exactly {@code width} characters wide
     */
    private static String zeroFilled(final String value, final int width) {
        return "0".repeat(width - value.length()) + value;
    }

    /**
     * Renders an amount as a zoned-decimal field image with its sign overpunched into the final byte.
     *
     * <p>Implemented here from the convention rather than by calling the production codec, which is
     * the whole point of an independent oracle. The amount is required to arrive already at the scale
     * a stored monetary field carries, so this method rescales nothing: the estate's truncating
     * rounding policy has exactly one home and this is not it.
     *
     * @param  amount the amount to render, which must already carry two fraction digits
     * @return the field image, exactly {@link #AMOUNT_WIDTH} characters wide
     * @throws IllegalArgumentException if the amount is not at scale two, or needs more digits than
     *                                  the field holds
     */
    private static String zonedAmountImage(final BigDecimal amount) {
        if (amount.scale() != MONETARY_SCALE) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "this suite's amount fixtures are declared at scale %d so that the oracle never"
                            + " needs to rescale, but %s carries scale %d",
                    MONETARY_SCALE, amount, amount.scale()));
        }
        final String digits = amount.abs().unscaledValue().toString();
        if (digits.length() > AMOUNT_WIDTH) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "the amount %s needs %d digits but the field holds only %d",
                    amount, digits.length(), AMOUNT_WIDTH));
        }
        final String justified = zeroFilled(digits, AMOUNT_WIDTH);
        final int lowOrderDigit = justified.charAt(AMOUNT_WIDTH - 1) - '0';
        final String table = amount.signum() < 0 ? NEGATIVE_OVERPUNCH : POSITIVE_OVERPUNCH;
        return justified.substring(0, AMOUNT_WIDTH - 1) + table.charAt(lowOrderDigit);
    }

    /**
     * Assembles the 350-byte source image by hand, concatenating each field at its declared width in
     * copybook order.
     *
     * <p>The concatenation is checked against the declared record width before it is returned, so an
     * arithmetic slip in this oracle fails loudly here rather than quietly weakening an expectation
     * somewhere below.
     *
     * @param  transactionId       the identifier, at its declared width
     * @param  amount              the amount, at scale two
     * @param  source              the origination descriptor, at its declared width
     * @param  processingTimestamp the processing timestamp lexeme, at its declared width
     * @return the source image, exactly {@link #SOURCE_IMAGE_WIDTH} characters wide
     */
    private static String sourceImage(final String transactionId, final BigDecimal amount,
            final String source, final String processingTimestamp) {
        final String image = transactionId
                + TYPE_CODE
                + CATEGORY_CODE
                + source
                + DESCRIPTION
                + zonedAmountImage(amount)
                + MERCHANT_ID
                + MERCHANT_NAME
                + MERCHANT_CITY
                + MERCHANT_ZIP
                + CARD_NUMBER
                + ORIGINATION_TIMESTAMP
                + processingTimestamp
                + FILLER;
        if (encodedWidth(image) != SOURCE_IMAGE_WIDTH) {
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "this suite's own source-image oracle assembled %d encoded bytes rather than the"
                            + " declared %d, so its field widths no longer sum to the layout",
                    encodedWidth(image), SOURCE_IMAGE_WIDTH));
        }
        return image;
    }

    /**
     * Builds the entity whose rendered image is the one {@link #sourceImage} assembles.
     *
     * <p>Every field is handed over at its declared width and the amount is handed over as a decimal
     * at scale two, so the production layout has nothing left to justify, pad or rescale. The two
     * methods therefore describe the same record without either consulting the other.
     *
     * @param  transactionId       the identifier, at its declared width
     * @param  amount              the amount, at scale two
     * @param  source              the origination descriptor, at its declared width
     * @param  processingTimestamp the processing timestamp lexeme, at its declared width
     * @return a fully populated daily-transaction record
     */
    private static DailyTransaction transaction(final String transactionId, final BigDecimal amount,
            final String source, final String processingTimestamp) {
        return new DailyTransaction(transactionId, TYPE_CODE, CATEGORY_CODE, source, DESCRIPTION,
                amount, MERCHANT_ID, MERCHANT_NAME, MERCHANT_CITY, MERCHANT_ZIP, CARD_NUMBER,
                ORIGINATION_TIMESTAMP, processingTimestamp);
    }

    /**
     * Assembles the 80-byte validation trailer by hand: the four-digit zero-padded code followed by
     * the description space-padded to its full width.
     *
     * <p>The code is rendered by locale-independent integer conversion and then zero-filled here, and
     * the description is taken from the enumeration unaltered, so this trailer is built from the two
     * data values the enumeration publishes and from nothing the writer produces.
     *
     * @param  reason the reason the trailer reports
     * @return the trailer image, exactly {@link #VALIDATION_TRAILER_WIDTH} characters wide
     */
    private static String trailerImage(final RejectReason reason) {
        return expectedReasonCode(reason)
                + alphanumeric(reason.getDescription(), REASON_DESCRIPTION_WIDTH);
    }

    /**
     * Renders the four-digit zero-padded form of a reason code.
     *
     * @param  reason the reason whose code is rendered
     * @return the code image, exactly {@link #REASON_CODE_WIDTH} characters wide
     */
    private static String expectedReasonCode(final RejectReason reason) {
        return zeroFilled(Integer.toString(reason.getReasonCode()), REASON_CODE_WIDTH);
    }

    /**
     * Assembles the whole 430-byte reject record by hand, source image first and trailer second.
     *
     * @param  transactionId       the identifier, at its declared width
     * @param  amount              the amount, at scale two
     * @param  source              the origination descriptor, at its declared width
     * @param  processingTimestamp the processing timestamp lexeme, at its declared width
     * @param  reason              the reason the trailer reports
     * @return the reject record image, exactly {@link #REJECT_RECORD_WIDTH} characters wide
     */
    private static String rejectImage(final String transactionId, final BigDecimal amount,
            final String source, final String processingTimestamp, final RejectReason reason) {
        return sourceImage(transactionId, amount, source, processingTimestamp)
                + trailerImage(reason);
    }

    /**
     * Pairs a representative point-of-sale purchase carrying a blank processing timestamp, as every
     * record of the reference input does, with a reject reason.
     *
     * @param  transactionId the identifier the record carries
     * @param  reason        the reason it was rejected
     * @return an item the writer accepts
     */
    private static RejectedTransaction rejected(final String transactionId,
            final RejectReason reason) {
        return new RejectedTransaction(
                transaction(transactionId, PURCHASE_AMOUNT, POS_SOURCE, BLANK_PROCESSING_TIMESTAMP),
                reason);
    }

    /**
     * Measures a value the way the contract is stated: in encoded US-ASCII bytes, never in characters.
     *
     * @param  value the value to measure
     * @return its encoded width in bytes
     */
    private static int encodedWidth(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Cuts a run of bytes out of the written artefact and decodes it as US-ASCII, so that a slice can
     * be compared against a hand-built field or record image.
     *
     * @param  written the bytes read back from the dataset
     * @param  from    the zero-based offset at which the slice starts
     * @param  width   the slice's width in bytes
     * @return the slice, decoded as US-ASCII
     */
    private static String slice(final byte[] written, final int from, final int width) {
        return new String(Arrays.copyOfRange(written, from, from + width), StandardCharsets.US_ASCII);
    }

    /**
     * Writes the supplied items through a freshly constructed writer and returns the finished
     * artefact's bytes.
     *
     * <p>Runs the whole stream lifecycle - open, write, checkpoint, close - because the last record is
     * only complete on disk once the destination has been closed, so a suite that skipped the close
     * would be measuring a partially flushed file. The destination is an ordinary file inside the
     * test's own temporary directory and the meter registry is an in-memory one, so nothing outside
     * the test is touched.
     *
     * @param  target the file the dataset is written to
     * @param  items  the rejected transactions to write, in the order they are to be emitted
     * @return every byte of the finished dataset
     * @throws Exception if the writer reports a failure, which is never expected here
     */
    private static byte[] writeDataset(final Path target, final List<RejectedTransaction> items)
            throws Exception {
        final RejectRecordWriter writer =
                new RejectRecordWriter(new FileSystemResource(target), new SimpleMeterRegistry());
        final ExecutionContext context = new ExecutionContext();
        final Chunk<RejectedTransaction> chunk = new Chunk<>(items);
        writer.open(context);
        writer.write(chunk);
        writer.update(context);
        writer.close();
        return Files.readAllBytes(target);
    }

    @Nested
    @DisplayName("the written record's width")
    class WrittenRecordWidth {

        /** Somewhere hermetic for this group's datasets to be written. */
        @TempDir
        private Path directory;

        @Test
        @DisplayName("is exactly 430 encoded bytes for a single reject, measured on the encoding and "
                + "never on the character count")
        void singleRecordIsExactlyFourHundredAndThirtyEncodedBytes() throws Exception {
            final byte[] written = writeDataset(this.directory.resolve("dalyrejs-one-record.dat"),
                    List.of(rejected(FIRST_ID, RejectReason.INVALID_CARD_NUMBER)));

            assertThat(written).hasSize(REJECT_RECORD_WIDTH);
            assertThat(written).hasSize(430);
            assertThat(encodedWidth(slice(written, 0, REJECT_RECORD_WIDTH)))
                    .isEqualTo(REJECT_RECORD_WIDTH);
            assertThat(slice(written, 0, REJECT_RECORD_WIDTH)).isEqualTo(rejectImage(FIRST_ID,
                    PURCHASE_AMOUNT, POS_SOURCE, BLANK_PROCESSING_TIMESTAMP,
                    RejectReason.INVALID_CARD_NUMBER));
        }

        @Test
        @DisplayName("is the sum of a 350-byte source segment and an 80-byte trailer, and the writer "
                + "publishes the same three numbers")
        void theGeometryAddsUpAndMatchesWhatTheWriterPublishes() {
            assertThat(REASON_CODE_WIDTH + REASON_DESCRIPTION_WIDTH)
                    .isEqualTo(VALIDATION_TRAILER_WIDTH);
            assertThat(SOURCE_IMAGE_WIDTH + VALIDATION_TRAILER_WIDTH).isEqualTo(REJECT_RECORD_WIDTH);
            assertThat(REASON_CODE_OFFSET).isEqualTo(SOURCE_IMAGE_WIDTH);
            assertThat(REASON_DESCRIPTION_OFFSET).isEqualTo(REASON_CODE_OFFSET + REASON_CODE_WIDTH);

            assertThat(RejectRecordWriter.REJECT_RECORD_LENGTH)
                    .as("RejectRecordWriter.REJECT_RECORD_LENGTH must equal the record length the "
                            + "reject dataset is allocated at in app/jcl/POSTTRAN.jcl line 36")
                    .isEqualTo(REJECT_RECORD_WIDTH);
            assertThat(RejectRecordWriter.SOURCE_IMAGE_LENGTH)
                    .as("RejectRecordWriter.SOURCE_IMAGE_LENGTH must equal the source segment "
                            + "declared at app/cbl/CBTRN02C.cbl line 83 and restated at line 177")
                    .isEqualTo(SOURCE_IMAGE_WIDTH);
            assertThat(RejectRecordWriter.VALIDATION_TRAILER_LENGTH)
                    .as("RejectRecordWriter.VALIDATION_TRAILER_LENGTH must equal the trailer segment "
                            + "declared at app/cbl/CBTRN02C.cbl line 84 and restated at line 178")
                    .isEqualTo(VALIDATION_TRAILER_WIDTH);
            assertThat(RejectRecordWriter.FAIL_REASON_LENGTH)
                    .as("RejectRecordWriter.FAIL_REASON_LENGTH must equal the numeric reason field "
                            + "declared at app/cbl/CBTRN02C.cbl line 181")
                    .isEqualTo(REASON_CODE_WIDTH);
            assertThat(RejectRecordWriter.FAIL_REASON_DESC_LENGTH)
                    .as("RejectRecordWriter.FAIL_REASON_DESC_LENGTH must equal the description field "
                            + "declared at app/cbl/CBTRN02C.cbl line 182")
                    .isEqualTo(REASON_DESCRIPTION_WIDTH);
        }
    }

    @Nested
    @DisplayName("the copied 350-byte source image")
    class CopiedSourceImage {

        /** Somewhere hermetic for this group's datasets to be written. */
        @TempDir
        private Path directory;

        @Test
        @DisplayName("is the inbound record byte for byte, compared against an image this suite "
                + "assembles by hand rather than against anything the module produced")
        void leadingSegmentIsTheInboundImageByteForByte() throws Exception {
            final String expected =
                    sourceImage(FIRST_ID, PURCHASE_AMOUNT, POS_SOURCE, BLANK_PROCESSING_TIMESTAMP);
            final byte[] written = writeDataset(this.directory.resolve("dalyrejs-verbatim.dat"),
                    List.of(rejected(FIRST_ID, RejectReason.ACCOUNT_NOT_FOUND_ON_READ)));

            assertThat(Arrays.copyOfRange(written, 0, SOURCE_IMAGE_WIDTH))
                    .as("app/cbl/CBTRN02C.cbl line 447 moves the whole inbound daily-transaction "
                            + "record into the leading segment of the reject record without altering "
                            + "a byte of it, so the comparison here is byte-for-byte and not "
                            + "field-by-field equivalence")
                    .isEqualTo(expected.getBytes(StandardCharsets.US_ASCII));
            assertThat(encodedWidth(expected)).isEqualTo(SOURCE_IMAGE_WIDTH);
        }

        @Test
        @DisplayName("carries the all-blank 26-byte processing timestamp through unchanged, because "
                + "blank is a legitimate value of that field and not an absent one")
        void blankProcessingTimestampSurvivesUnchanged() throws Exception {
            final byte[] written = writeDataset(this.directory.resolve("dalyrejs-blank-ts.dat"),
                    List.of(rejected(FIRST_ID, RejectReason.INVALID_CARD_NUMBER)));

            final String emitted = slice(written, PROCESSING_TIMESTAMP_OFFSET, TIMESTAMP_WIDTH);

            assertThat(encodedWidth(emitted)).isEqualTo(TIMESTAMP_WIDTH);
            assertThat(emitted)
                    .as("every one of the 300 records of the reference daily-transaction input "
                            + "carries 26 spaces in this field, so a copied-unchanged reject image "
                            + "must carry 26 spaces here rather than a null, an empty run or a "
                            + "rendered instant")
                    .isEqualTo(BLANK_PROCESSING_TIMESTAMP);
            assertThat(emitted.chars()).allMatch(codePoint -> codePoint == ' ');
        }

        @Test
        @DisplayName("still carries a populated processing timestamp through unchanged, so the "
                + "blank-timestamp assertion discriminates rather than holding of any input")
        void populatedProcessingTimestampAlsoSurvivesUnchanged() throws Exception {
            final byte[] written = writeDataset(this.directory.resolve("dalyrejs-posted-ts.dat"),
                    List.of(new RejectedTransaction(transaction(THIRD_ID, PURCHASE_AMOUNT, POS_SOURCE,
                            POSTED_PROCESSING_TIMESTAMP), RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE)));

            final String emitted = slice(written, PROCESSING_TIMESTAMP_OFFSET, TIMESTAMP_WIDTH);

            assertThat(encodedWidth(emitted)).isEqualTo(TIMESTAMP_WIDTH);
            assertThat(emitted).isEqualTo(POSTED_PROCESSING_TIMESTAMP);
            assertThat(emitted).isNotEqualTo(BLANK_PROCESSING_TIMESTAMP);
        }

        @Test
        @DisplayName("carries a negative overpunched amount through unchanged, with no re-encoding, "
                + "no rescaling and no sign normalisation")
        void negativeOverpunchedAmountSurvivesUnchanged() throws Exception {
            final byte[] written = writeDataset(this.directory.resolve("dalyrejs-negative.dat"),
                    List.of(new RejectedTransaction(transaction(SECOND_ID, RETURN_AMOUNT,
                            OPERATOR_SOURCE, BLANK_PROCESSING_TIMESTAMP),
                            RejectReason.OVERLIMIT_TRANSACTION)));

            final String expectedAmount = zonedAmountImage(RETURN_AMOUNT);
            final String emittedAmount = slice(written, AMOUNT_OFFSET, AMOUNT_WIDTH);
            final String emittedSignByte = slice(written, SIGN_BYTE_OFFSET, 1);

            assertThat(encodedWidth(expectedAmount)).isEqualTo(AMOUNT_WIDTH);
            assertThat(emittedAmount)
                    .as("the amount is a zoned-decimal image whose sign is folded into its final "
                            + "byte, and this suite encodes that byte itself rather than asking the "
                            + "module for it")
                    .isEqualTo(expectedAmount);
            assertThat(emittedSignByte).isEqualTo(expectedAmount.substring(AMOUNT_WIDTH - 1));
            assertThat(NEGATIVE_OVERPUNCH)
                    .as("the sign byte at one-based column 143 belongs to the negative overpunch "
                            + "encoding, which is what proves the sign was neither dropped nor moved "
                            + "into a separate byte")
                    .contains(emittedSignByte);
            assertThat(POSITIVE_OVERPUNCH).doesNotContain(emittedSignByte);
            assertThat(Character.isDigit(emittedSignByte.charAt(0)))
                    .as("a plain digit here would mean the overpunch had been normalised away")
                    .isFalse();
            assertThat(Arrays.copyOfRange(written, 0, SOURCE_IMAGE_WIDTH))
                    .isEqualTo(sourceImage(SECOND_ID, RETURN_AMOUNT, OPERATOR_SOURCE,
                            BLANK_PROCESSING_TIMESTAMP).getBytes(StandardCharsets.US_ASCII));
        }
    }

    @Nested
    @DisplayName("the 80-byte validation trailer")
    class ValidationTrailer {

        /** Somewhere hermetic for this group's datasets to be written. */
        @TempDir
        private Path directory;

        @ParameterizedTest(name = "{0} is emitted as {1}")
        @CsvSource({
            "INVALID_CARD_NUMBER,0100",
            "ACCOUNT_NOT_FOUND_ON_READ,0101",
            "OVERLIMIT_TRANSACTION,0102",
            "TRANSACTION_AFTER_ACCOUNT_EXPIRATION,0103",
            "ACCOUNT_NOT_FOUND_ON_REWRITE,0109"
        })
        @DisplayName("carries the reason code at bytes 350 to 353 as exactly four zero-padded digits")
        void reasonCodeOccupiesFourZeroPaddedDigits(final RejectReason reason,
                final String expectedCode) throws Exception {
            final byte[] written = writeDataset(
                    this.directory.resolve("dalyrejs-code-" + reason.name() + ".dat"),
                    List.of(rejected(FIRST_ID, reason)));

            final String emittedCode = slice(written, REASON_CODE_OFFSET, REASON_CODE_WIDTH);

            assertThat(encodedWidth(emittedCode)).isEqualTo(REASON_CODE_WIDTH);
            assertThat(emittedCode)
                    .as("the reason field is four digits wide, so a three-character rendering would "
                            + "shift the description one byte to the left and shorten the record")
                    .isEqualTo(expectedCode);
            assertThat(emittedCode).containsOnlyDigits();
        }

        @ParameterizedTest(name = "{0} carries {2} description characters and {3} spaces of padding")
        @CsvSource({
            "INVALID_CARD_NUMBER,INVALID CARD NUMBER FOUND,25,51",
            "ACCOUNT_NOT_FOUND_ON_READ,ACCOUNT RECORD NOT FOUND,24,52",
            "OVERLIMIT_TRANSACTION,OVERLIMIT TRANSACTION,21,55",
            "TRANSACTION_AFTER_ACCOUNT_EXPIRATION,TRANSACTION RECEIVED AFTER ACCT EXPIRATION,42,34",
            "ACCOUNT_NOT_FOUND_ON_REWRITE,ACCOUNT RECORD NOT FOUND,24,52"
        })
        @DisplayName("carries the verbatim description at bytes 354 to 429, left-justified and padded "
                + "to the full field width with spaces and nothing else")
        void descriptionBlockCarriesTheVerbatimTextAndItsExactPadding(final RejectReason reason,
                final String expectedDescription, final int expectedDescriptionWidth,
                final int expectedPaddingWidth) throws Exception {
            final byte[] written = writeDataset(
                    this.directory.resolve("dalyrejs-desc-" + reason.name() + ".dat"),
                    List.of(rejected(FIRST_ID, reason)));

            final String emittedBlock =
                    slice(written, REASON_DESCRIPTION_OFFSET, REASON_DESCRIPTION_WIDTH);

            assertThat(encodedWidth(expectedDescription)).isEqualTo(expectedDescriptionWidth);
            assertThat(expectedDescriptionWidth + expectedPaddingWidth)
                    .isEqualTo(REASON_DESCRIPTION_WIDTH);
            assertThat(encodedWidth(emittedBlock)).isEqualTo(REASON_DESCRIPTION_WIDTH);
            assertThat(emittedBlock)
                    .as("the description is contractual output that operators and downstream tooling "
                            + "match on, so it is reproduced verbatim and never trimmed, folded, "
                            + "reworded, abbreviated or truncated")
                    .isEqualTo(expectedDescription + " ".repeat(expectedPaddingWidth));
            assertThat(emittedBlock.substring(0, expectedDescriptionWidth))
                    .isEqualTo(expectedDescription);
            assertThat(emittedBlock.substring(expectedDescriptionWidth))
                    .as("the remainder of the field is space padding, and there is exactly "
                            + expectedPaddingWidth + " byte(s) of it")
                    .isEqualTo(" ".repeat(expectedPaddingWidth));
            assertThat(emittedBlock.substring(expectedDescriptionWidth).chars())
                    .as("padding is the space character 0x20 only, never a null and never a control "
                            + "byte")
                    .allMatch(codePoint -> codePoint == 0x20);
        }

        @Test
        @DisplayName("keeps 0101 and 0109 distinct: the two reasons carry byte-identical description "
                + "text and are nonetheless emitted with different four-digit codes")
        void codesOneHundredAndOneAndOneHundredAndNineStayDistinctDespiteIdenticalText()
                throws Exception {
            final byte[] onRead = writeDataset(this.directory.resolve("dalyrejs-0101.dat"),
                    List.of(rejected(FIRST_ID, RejectReason.ACCOUNT_NOT_FOUND_ON_READ)));
            final byte[] onRewrite = writeDataset(this.directory.resolve("dalyrejs-0109.dat"),
                    List.of(rejected(FIRST_ID, RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE)));

            // The shared text is stated as a literal here rather than by comparing the two emitted
            // blocks only to each other, which would hold even if both were wrong.
            final String sharedBlock =
                    alphanumeric("ACCOUNT RECORD NOT FOUND", REASON_DESCRIPTION_WIDTH);

            assertThat(slice(onRead, REASON_DESCRIPTION_OFFSET, REASON_DESCRIPTION_WIDTH))
                    .isEqualTo(sharedBlock);
            assertThat(slice(onRewrite, REASON_DESCRIPTION_OFFSET, REASON_DESCRIPTION_WIDTH))
                    .isEqualTo(sharedBlock);
            assertThat(slice(onRead, REASON_DESCRIPTION_OFFSET, REASON_DESCRIPTION_WIDTH))
                    .isEqualTo(slice(onRewrite, REASON_DESCRIPTION_OFFSET, REASON_DESCRIPTION_WIDTH));

            assertThat(slice(onRead, REASON_CODE_OFFSET, REASON_CODE_WIDTH)).isEqualTo("0101");
            assertThat(slice(onRewrite, REASON_CODE_OFFSET, REASON_CODE_WIDTH)).isEqualTo("0109");
            assertThat(slice(onRead, REASON_CODE_OFFSET, REASON_CODE_WIDTH))
                    .as("0101 reports an account read that missed during validation, before anything "
                            + "was posted; 0109 reports an account rewrite that failed while updated "
                            + "balances were being written back afterwards. Merging them would "
                            + "silently stop emitting one of the five codes the trailer is required "
                            + "to carry")
                    .isNotEqualTo(slice(onRewrite, REASON_CODE_OFFSET, REASON_CODE_WIDTH));

            assertThat(slice(onRead, REASON_CODE_OFFSET, VALIDATION_TRAILER_WIDTH))
                    .isNotEqualTo(slice(onRewrite, REASON_CODE_OFFSET, VALIDATION_TRAILER_WIDTH));
        }

        @ParameterizedTest
        @EnumSource(RejectReason.class)
        @DisplayName("fills exactly 80 bytes for every reason, code field and description field "
                + "adjacent with nothing between them and nothing after them")
        void trailerFillsExactlyEightyBytesForEveryReason(final RejectReason reason)
                throws Exception {
            final byte[] written = writeDataset(
                    this.directory.resolve("dalyrejs-trailer-" + reason.name() + ".dat"),
                    List.of(rejected(FIRST_ID, reason)));

            final String emittedTrailer =
                    slice(written, REASON_CODE_OFFSET, VALIDATION_TRAILER_WIDTH);

            assertThat(encodedWidth(emittedTrailer)).isEqualTo(VALIDATION_TRAILER_WIDTH);
            assertThat(emittedTrailer).isEqualTo(trailerImage(reason));
            assertThat(emittedTrailer.substring(0, REASON_CODE_WIDTH))
                    .isEqualTo(expectedReasonCode(reason));
            assertThat(emittedTrailer.substring(REASON_CODE_WIDTH))
                    .isEqualTo(alphanumeric(reason.getDescription(), REASON_DESCRIPTION_WIDTH));
            assertThat(written).hasSize(REJECT_RECORD_WIDTH);
        }
    }

    @Nested
    @DisplayName("the fixed unblocked dataset")
    class FixedUnblockedDataset {

        /** Somewhere hermetic for this group's datasets to be written. */
        @TempDir
        private Path directory;

        /**
         * Three rejects covering both signed directions of the amount, both origination descriptors,
         * both the blank and the populated processing timestamp, and three different reasons.
         *
         * @return the items the multi-record tests write, in the order they are to be emitted
         */
        private List<RejectedTransaction> threeRejects() {
            return List.of(
                    new RejectedTransaction(transaction(FIRST_ID, PURCHASE_AMOUNT, POS_SOURCE,
                            BLANK_PROCESSING_TIMESTAMP), RejectReason.INVALID_CARD_NUMBER),
                    new RejectedTransaction(transaction(SECOND_ID, RETURN_AMOUNT, OPERATOR_SOURCE,
                            BLANK_PROCESSING_TIMESTAMP), RejectReason.OVERLIMIT_TRANSACTION),
                    new RejectedTransaction(transaction(THIRD_ID, PURCHASE_AMOUNT, POS_SOURCE,
                            POSTED_PROCESSING_TIMESTAMP),
                            RejectReason.TRANSACTION_AFTER_ACCOUNT_EXPIRATION));
        }

        /**
         * The three reject images the same three items must produce, assembled by hand.
         *
         * @return the expected record images, in emission order
         */
        private List<String> threeExpectedImages() {
            return List.of(
                    rejectImage(FIRST_ID, PURCHASE_AMOUNT, POS_SOURCE, BLANK_PROCESSING_TIMESTAMP,
                            RejectReason.INVALID_CARD_NUMBER),
                    rejectImage(SECOND_ID, RETURN_AMOUNT, OPERATOR_SOURCE, BLANK_PROCESSING_TIMESTAMP,
                            RejectReason.OVERLIMIT_TRANSACTION),
                    rejectImage(THIRD_ID, PURCHASE_AMOUNT, POS_SOURCE, POSTED_PROCESSING_TIMESTAMP,
                            RejectReason.TRANSACTION_AFTER_ACCOUNT_EXPIRATION));
        }

        @Test
        @DisplayName("writes three rejects as exactly 1,290 contiguous bytes, with no line ending, no "
                + "delimiter, no byte-order mark and no padding to a block boundary")
        void threeRecordsAreExactlyOneThousandTwoHundredAndNinetyContiguousBytes() throws Exception {
            final byte[] written = writeDataset(this.directory.resolve("dalyrejs-unblocked.dat"),
                    threeRejects());

            assertThat(written).hasSize(3 * REJECT_RECORD_WIDTH);
            assertThat(written)
                    .as("the reject dataset is allocated fixed unblocked with a block size of zero at "
                            + "app/jcl/POSTTRAN.jcl line 36, so three records occupy three record "
                            + "widths and not one byte more")
                    .hasSize(1290);
            assertThat(written.length % REJECT_RECORD_WIDTH).isZero();

            assertThat(written)
                    .as("inheriting the framework's default separator, which is the platform line "
                            + "ending, would append a stray byte to every record")
                    .doesNotContain((byte) '\n')
                    .doesNotContain((byte) '\r');

            int highestByte = 0;
            for (final byte value : written) {
                highestByte = Math.max(highestByte, value & 0xFF);
            }
            assertThat(highestByte)
                    .as("every byte of this dataset is US-ASCII, which rules out a byte-order mark of "
                            + "any encoding as well as any high-order control byte")
                    .isLessThanOrEqualTo(0x7F);

            final StringBuilder concatenated = new StringBuilder();
            for (final String image : threeExpectedImages()) {
                concatenated.append(image);
            }
            assertThat(written)
                    .as("the artefact is exactly the concatenation of the three images and carries no "
                            + "header, footer or trailer of its own")
                    .isEqualTo(concatenated.toString().getBytes(StandardCharsets.US_ASCII));
        }

        @Test
        @DisplayName("starts record n at byte offset (n - 1) times 430, so the boundaries fall at 0, "
                + "430 and 860")
        void recordBoundariesFallAtExactMultiplesOfTheRecordWidth() throws Exception {
            final byte[] written = writeDataset(this.directory.resolve("dalyrejs-boundaries.dat"),
                    threeRejects());
            final List<String> expected = threeExpectedImages();

            assertThat(slice(written, 0, REJECT_RECORD_WIDTH)).isEqualTo(expected.get(0));
            assertThat(slice(written, 430, REJECT_RECORD_WIDTH)).isEqualTo(expected.get(1));
            assertThat(slice(written, 860, REJECT_RECORD_WIDTH)).isEqualTo(expected.get(2));

            for (int index = 0; index < expected.size(); index++) {
                final int start = index * REJECT_RECORD_WIDTH;
                assertThat(slice(written, start, REJECT_RECORD_WIDTH))
                        .as("record " + index + " must begin at byte offset " + start
                                + " and must equal the image this suite assembles by hand")
                        .isEqualTo(expected.get(index));
            }
        }

        @Test
        @DisplayName("emits records in the order the chunk holds them, which is the order the "
                + "transactions were read")
        void recordsAreEmittedInChunkOrder() throws Exception {
            final byte[] written = writeDataset(this.directory.resolve("dalyrejs-ordering.dat"),
                    threeRejects());

            assertThat(slice(written, ID_OFFSET, ID_WIDTH)).isEqualTo(FIRST_ID);
            assertThat(slice(written, REJECT_RECORD_WIDTH + ID_OFFSET, ID_WIDTH))
                    .isEqualTo(SECOND_ID);
            assertThat(slice(written, 2 * REJECT_RECORD_WIDTH + ID_OFFSET, ID_WIDTH))
                    .isEqualTo(THIRD_ID);

            assertThat(List.of(slice(written, ID_OFFSET, ID_WIDTH),
                    slice(written, REJECT_RECORD_WIDTH + ID_OFFSET, ID_WIDTH),
                    slice(written, 2 * REJECT_RECORD_WIDTH + ID_OFFSET, ID_WIDTH)))
                    .as("reordering reject records would change the dataset, so emission order is "
                            + "part of the contract rather than an implementation detail")
                    .containsExactly(FIRST_ID, SECOND_ID, THIRD_ID);

            assertThat(slice(written, REASON_CODE_OFFSET, REASON_CODE_WIDTH)).isEqualTo("0100");
            assertThat(slice(written, REJECT_RECORD_WIDTH + REASON_CODE_OFFSET, REASON_CODE_WIDTH))
                    .isEqualTo("0102");
            assertThat(slice(written, 2 * REJECT_RECORD_WIDTH + REASON_CODE_OFFSET,
                    REASON_CODE_WIDTH)).isEqualTo("0103");
        }

        @Test
        @DisplayName("leaves an empty artefact behind for an empty chunk, which is still an exact "
                + "multiple of the record width, and raises nothing")
        void emptyChunkLeavesAnEmptyArtefactAndRaisesNothing() throws Exception {
            final Path target = this.directory.resolve("dalyrejs-empty-chunk.dat");
            final RejectRecordWriter writer = new RejectRecordWriter(new FileSystemResource(target),
                    new SimpleMeterRegistry());
            final ExecutionContext context = new ExecutionContext();
            final Chunk<RejectedTransaction> empty = new Chunk<>(List.of());

            assertThatCode(() -> {
                writer.open(context);
                writer.write(empty);
                writer.update(context);
                writer.close();
            }).doesNotThrowAnyException();

            final byte[] written = Files.readAllBytes(target);

            assertThat(written).isEmpty();
            assertThat(written.length % REJECT_RECORD_WIDTH)
                    .as("zero bytes is an exact multiple of the record width, so a run that rejected "
                            + "nothing still leaves a well-formed dataset")
                    .isZero();
            assertThat(writer.recordsWritten()).isZero();
            assertThat(target)
                    .as("the reject dataset is catalogued whether or not any transaction was "
                            + "rejected, so a run with no rejects leaves an empty dataset rather "
                            + "than none")
                    .exists();
        }
    }

    @Nested
    @DisplayName("boundaries the reason contract cannot cross")
    class ReasonContractBoundaries {

        /** Somewhere hermetic for this group's datasets to be written. */
        @TempDir
        private Path directory;

        @ParameterizedTest
        @EnumSource(RejectReason.class)
        @DisplayName("never truncates a description, because no published description can overflow "
                + "the field in the first place")
        void noPublishedDescriptionCanOverflowTheField(final RejectReason reason) throws Exception {
            final String description = reason.getDescription();
            final int descriptionWidth = encodedWidth(description);

            assertThat(descriptionWidth)
                    .as("RejectReason." + reason.name() + " publishes a description of "
                            + descriptionWidth + " encoded bytes. The description field of the "
                            + "validation trailer, declared at app/cbl/CBTRN02C.cbl line 182, holds "
                            + REASON_DESCRIPTION_WIDTH + " bytes, and "
                            + "RejectRecordWriter.failReasonDescriptionField refuses an over-wide "
                            + "description rather than shortening it, because no truncation of this "
                            + "field is authorised anywhere in the legacy program. A wider "
                            + "description could therefore only mean the reason contract had changed "
                            + "and the trailer geometry would have to change with it")
                    .isLessThanOrEqualTo(REASON_DESCRIPTION_WIDTH);

            final byte[] written = writeDataset(
                    this.directory.resolve("dalyrejs-untruncated-" + reason.name() + ".dat"),
                    List.of(rejected(FIRST_ID, reason)));
            final String emittedBlock =
                    slice(written, REASON_DESCRIPTION_OFFSET, REASON_DESCRIPTION_WIDTH);

            assertThat(encodedWidth(emittedBlock)).isEqualTo(REASON_DESCRIPTION_WIDTH);
            assertThat(emittedBlock)
                    .as("the whole description reaches the dataset, so nothing was cut")
                    .startsWith(description);
            assertThat(emittedBlock.substring(descriptionWidth))
                    .isEqualTo(" ".repeat(REASON_DESCRIPTION_WIDTH - descriptionWidth));
        }

        @Test
        @DisplayName("leaves the refusal branch unreachable from the published enumeration: the "
                + "widest of the five descriptions is 42 bytes, so 34 bytes of the field go spare")
        void theWidestPublishedDescriptionLeavesTheFieldWithRoomToSpare() {
            int widest = 0;
            for (final RejectReason reason : RejectReason.values()) {
                widest = Math.max(widest, encodedWidth(reason.getDescription()));
            }

            assertThat(widest).isEqualTo(42);
            assertThat(REASON_DESCRIPTION_WIDTH - widest)
                    .as("no description approaches the field width, which is why "
                            + "RejectRecordWriter.failReasonDescriptionField can refuse an over-wide "
                            + "value without any published reason ever reaching that refusal")
                    .isEqualTo(34);
            assertThat(widest).isLessThan(REASON_DESCRIPTION_WIDTH);
        }

        @ParameterizedTest
        @EnumSource(RejectReason.class)
        @DisplayName("renders every published code inside the four-digit field, so the field can "
                + "never overflow and shift the description")
        void everyPublishedCodeFitsTheFourDigitField(final RejectReason reason) {
            final int code = reason.getReasonCode();

            assertThat(code)
                    .as("RejectReason." + reason.name() + " carries code " + code
                            + "; the trailer's reason field is unsigned, so a negative code would "
                            + "need a sign byte the field declared at app/cbl/CBTRN02C.cbl line 181 "
                            + "does not provide")
                    .isNotNegative();
            assertThat(encodedWidth(Integer.toString(code)))
                    .as("RejectReason." + reason.name() + " carries code " + code
                            + ", which must fit the " + REASON_CODE_WIDTH + "-digit reason field; "
                            + "RejectRecordWriter.failReasonField refuses a wider code rather than "
                            + "shortening it")
                    .isLessThanOrEqualTo(REASON_CODE_WIDTH);
            assertThat(encodedWidth(expectedReasonCode(reason))).isEqualTo(REASON_CODE_WIDTH);
        }

        @ParameterizedTest(name = "code {0} resolves to no reason at all")
        @ValueSource(ints = {0, 99, 104, 105, 106, 107, 108, 110, 200, 999})
        @DisplayName("resolves a code outside the five to absence rather than to a defaulted or "
                + "invented reason")
        void aCodeOutsideTheFiveResolvesToAbsence(final int code) {
            assertThat(RejectReason.byReasonCode(code))
                    .as("RejectReason.byReasonCode reports a code the legacy program never emits by "
                            + "absence rather than by a sixth constant, and zero in particular is the "
                            + "initial not-rejected state of the reason field, cleared at "
                            + "app/cbl/CBTRN02C.cbl lines 208 to 209, rather than a reject reason")
                    .isEmpty();
        }

        @Test
        @DisplayName("publishes exactly the five codes the legacy program raises, and resolves each "
                + "one back by its own code")
        void exactlyFiveReasonsArePublishedAndEachResolvesByItsOwnCode() {
            assertThat(RejectReason.values())
                    .as("codes 100, 101, 102, 103 and 109 are raised at app/cbl/CBTRN02C.cbl lines "
                            + "385, 397, 410, 417 and 556 respectively, and nothing else is")
                    .hasSize(5);
            assertThat(Arrays.stream(RejectReason.values())
                    .map(RejectReason::getReasonCode)
                    .toList())
                    .containsExactly(100, 101, 102, 103, 109);

            for (final RejectReason reason : RejectReason.values()) {
                assertThat(RejectReason.byReasonCode(reason.getReasonCode()))
                        .as("the index over the reasons is keyed by code and never by description, "
                                + "because the two identical descriptions would otherwise collide and "
                                + "force exactly the consolidation the contract forbids")
                        .contains(reason);
            }
        }

        @ParameterizedTest
        @EnumSource(RejectReason.class)
        @DisplayName("emits a code that resolves back to the very reason that produced it, so a "
                + "reject recovered from the dataset stays attributable")
        void theEmittedCodeResolvesBackToTheReasonThatProducedIt(final RejectReason reason)
                throws Exception {
            final byte[] written = writeDataset(
                    this.directory.resolve("dalyrejs-attributable-" + reason.name() + ".dat"),
                    List.of(rejected(FIRST_ID, reason)));

            final String emittedCode = slice(written, REASON_CODE_OFFSET, REASON_CODE_WIDTH);
            final Optional<RejectReason> resolved =
                    RejectReason.byReasonCode(Integer.parseInt(emittedCode));

            assertThat(encodedWidth(emittedCode)).isEqualTo(REASON_CODE_WIDTH);
            assertThat(resolved)
                    .as("a code recovered from the four digits at offset " + REASON_CODE_OFFSET
                            + " of a persisted reject record must resolve back to " + reason.name()
                            + " and to nothing else, which is what keeps 0101 and 0109 "
                            + "distinguishable once the record has left the program")
                    .contains(reason);
            assertThat(slice(written, REASON_DESCRIPTION_OFFSET, REASON_DESCRIPTION_WIDTH))
                    .isEqualTo(alphanumeric(resolved.orElseThrow().getDescription(),
                            REASON_DESCRIPTION_WIDTH));
        }
    }
}
