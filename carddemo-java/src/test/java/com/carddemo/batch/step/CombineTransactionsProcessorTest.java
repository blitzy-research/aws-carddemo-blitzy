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

import com.carddemo.domain.Transaction;
import com.carddemo.util.TransactionRecordMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the per-record contract of the combine-transactions load stage: that a record passes
 * through unaltered, that the stream order and every equal-key duplicate survive, that the
 * sixteen-character identifier is treated as characters and nothing else, and that every record
 * still renders to its exact fixed width.
 *
 * <p>The width assertions are made on <strong>encoded bytes</strong> rather than on characters or on
 * parsed values, because the contract carried forward is a fixed-width record image. A suite that
 * compared parsed fields would pass while the image was a byte wide of its allocation, an
 * overpunched sign had been normalised into a leading minus or a trailing space run had been
 * trimmed - and every one of those would break the byte fidelity of the next backup generation taken
 * from the master rather than anything visible in this stage.
 *
 * <p>Two properties are asserted behaviourally rather than structurally, deliberately. That the
 * processor applies no ordering of its own is proved by driving a deliberately unsorted stream
 * through it and requiring the output order to equal the input order, which is what a reader of the
 * job actually depends on; and that it never filters is proved by requiring the count out to equal
 * the count in. Neither is inspected through reflection, and this suite mocks no static call, in
 * keeping with the rest of the test estate.
 *
 * <p>The width postcondition is exercised directly rather than through the processor's own entry
 * point. It cannot be reached that way, because the mapper builds its buffer at the declared record
 * width and therefore cannot return an image of any other width; reaching it through the entry point
 * would mean instrumenting a static call, which no test in this estate does. The check is
 * package-visible for exactly this reason, so the assertion is verified rather than merely present.
 *
 * <p>Provenance of the expectations: the legacy combine job, the copy procedure and control member
 * it invokes, the job that mints its first input, and the transaction copybook, at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is transcribed here.
 */
@DisplayName("CombineTransactionsProcessor - order, duplicate retention and 350-byte fidelity")
class CombineTransactionsProcessorTest {

    /** The layout's record width, taken from the authority rather than restated as a literal. */
    private static final int RECORD_WIDTH = TransactionRecordMapper.RECORD_LENGTH;

    /** Origin descriptor of a point-of-sale purchase, at its contractual ten characters. */
    private static final String POS_SOURCE = "POS TERM  ";

    /** Origin descriptor of an operator-originated return, at its contractual ten characters. */
    private static final String OPERATOR_SOURCE = "OPERATOR  ";

    /**
     * Marker used to tell the two concatenated input streams apart within a test.
     *
     * <p>It lives in the description field because <strong>the record layout carries no origin
     * field at all</strong> - which is precisely the property the processor's documentation records,
     * and the reason a stream-origin wrapper must never reach the image. The marker is therefore a
     * test device standing in for stream origin, never a claim that the estate marks origin in a
     * record.
     */
    private static final String BACKUP_MARKER = "FROM THE BACKUP CURRENT GENERATION";

    /** Companion of {@link #BACKUP_MARKER} for the second of the two concatenated inputs. */
    private static final String SYNTHESIZED_MARKER = "FROM THE SYNTHESIZED CURRENT GENERATION";

    /** A representative purchase amount, at the layout's scale of two. */
    private static final BigDecimal PURCHASE_AMOUNT = new BigDecimal("42.75");

    /** A representative return amount, negative so the overpunched sign is exercised. */
    private static final BigDecimal RETURN_AMOUNT = new BigDecimal("-42.75");

    /** An unstamped processing timestamp: twenty-six spaces, which the layout permits. */
    private static final String UNSTAMPED_TIMESTAMP = " ".repeat(26);

    /** A stamped origination timestamp at the layout's twenty-six characters. */
    private static final String ORIGIN_TIMESTAMP = "2022-07-19-23.23.05.000000";

    /** The processor under test. Stateless, so one instance serves every case. */
    private final CombineTransactionsProcessor processor = new CombineTransactionsProcessor();

    /**
     * Builds a transaction whose every field already sits at its contractual width, then rounds it
     * through the mapper so that the entity under test is exactly the one a reader would deliver.
     *
     * @param  tranId identifier, sixteen characters, carried through verbatim
     * @param  marker stream-origin marker for the description field, a test device only
     * @param  source ten-character origin descriptor
     * @param  amount amount at the layout's scale of two
     * @return an entity identical to one parsed from a well-formed record image
     */
    private static Transaction combinedRecord(final String tranId, final String marker,
            final String source, final BigDecimal amount) {
        return TransactionRecordMapper.fromRecord(TransactionRecordMapper.toRecordBytes(
                new Transaction(tranId, "01", "0005", source, pad(marker, 100), amount, "000123456",
                        pad("ACME MERCHANT", 50), pad("SEATTLE", 50), "98101-0001",
                        "4111111111111111", ORIGIN_TIMESTAMP, UNSTAMPED_TIMESTAMP)));
    }

    /** Left-justifies a value into a field of the given width, space padded as the layout is. */
    private static String pad(final String value, final int width) {
        return value + " ".repeat(width - value.length());
    }

    /** Reads one field out of a rendered image as text, for a targeted byte-level assertion. */
    private static String fieldOf(final byte[] image, final int offset, final int length) {
        return new String(image, offset, length, StandardCharsets.US_ASCII);
    }

    @Nested
    @DisplayName("Pass-through: the record that arrives is the record that leaves")
    class PassThrough {

        @Test
        @DisplayName("returns the very instance supplied, never a copy")
        void returnsTheVeryInstanceSupplied() {
            Transaction item = combinedRecord("0000000000000001", BACKUP_MARKER, POS_SOURCE,
                    PURCHASE_AMOUNT);

            Transaction result = processor.process(item);

            assertThat(result).isSameAs(item);
        }

        @Test
        @DisplayName("never returns null, because a null return would filter the record away")
        void neverReturnsNull() {
            Transaction item = combinedRecord("0000000000000002", SYNTHESIZED_MARKER,
                    OPERATOR_SOURCE, RETURN_AMOUNT);

            assertThat(processor.process(item)).isNotNull();
        }

        @Test
        @DisplayName("leaves every one of the thirteen mapped fields exactly as it was")
        void leavesEveryMappedFieldUnchanged() {
            Transaction item = combinedRecord("0000000000000003", BACKUP_MARKER, POS_SOURCE,
                    RETURN_AMOUNT);

            Transaction result = processor.process(item);

            assertThat(result.getTranId()).isEqualTo("0000000000000003");
            assertThat(result.getTranTypeCd()).isEqualTo("01");
            assertThat(result.getTranCatCd()).isEqualTo("0005");
            assertThat(result.getTranSource()).isEqualTo(POS_SOURCE);
            assertThat(result.getTranDesc()).isEqualTo(pad(BACKUP_MARKER, 100));
            assertThat(result.getTranAmt()).isEqualByComparingTo(RETURN_AMOUNT);
            assertThat(result.getTranAmt().scale()).isEqualTo(2);
            assertThat(result.getMerchantId()).isEqualTo("000123456");
            assertThat(result.getMerchantName()).isEqualTo(pad("ACME MERCHANT", 50));
            assertThat(result.getMerchantCity()).isEqualTo(pad("SEATTLE", 50));
            assertThat(result.getMerchantZip()).isEqualTo("98101-0001");
            assertThat(result.getTranCardNum()).isEqualTo("4111111111111111");
            assertThat(result.getTranOrigTs()).isEqualTo(ORIGIN_TIMESTAMP);
            assertThat(result.getTranProcTs()).isEqualTo(UNSTAMPED_TIMESTAMP);
        }

        @Test
        @DisplayName("leaves the rendered image byte-identical at the contracted width")
        void leavesTheRenderedImageByteIdentical() {
            Transaction item = combinedRecord("0000000000000004", SYNTHESIZED_MARKER, POS_SOURCE,
                    PURCHASE_AMOUNT);
            byte[] before = TransactionRecordMapper.toRecordBytes(item);

            byte[] after = TransactionRecordMapper.toRecordBytes(processor.process(item));

            assertThat(after).isEqualTo(before);
            assertThat(after).hasSize(RECORD_WIDTH);
        }

        @Test
        @DisplayName("holds no state, so repeated and interleaved use changes nothing")
        void holdsNoStateAcrossRepeatedUse() {
            Transaction first = combinedRecord("0000000000000005", BACKUP_MARKER, POS_SOURCE,
                    PURCHASE_AMOUNT);
            Transaction second = combinedRecord("0000000000000006", SYNTHESIZED_MARKER,
                    OPERATOR_SOURCE, RETURN_AMOUNT);
            byte[] firstImage = TransactionRecordMapper.toRecordBytes(first);

            assertThat(processor.process(first)).isSameAs(first);
            assertThat(processor.process(second)).isSameAs(second);
            assertThat(processor.process(first)).isSameAs(first);

            assertThat(TransactionRecordMapper.toRecordBytes(first)).isEqualTo(firstImage);
        }
    }

    @Nested
    @DisplayName("Input concatenation: two current generations, backup first, duplicates kept")
    class InputConcatenation {

        @Test
        @DisplayName("preserves the order of the two concatenated current generations")
        void preservesTheOrderOfTheTwoConcatenatedGenerations() {
            List<Transaction> stream = List.of(
                    combinedRecord("0000000000000001", BACKUP_MARKER, POS_SOURCE, PURCHASE_AMOUNT),
                    combinedRecord("0000000000000002", BACKUP_MARKER, POS_SOURCE, PURCHASE_AMOUNT),
                    combinedRecord("0000000000000003", SYNTHESIZED_MARKER, OPERATOR_SOURCE,
                            RETURN_AMOUNT));

            List<String> emitted = drive(stream);

            assertThat(emitted).containsExactly("0000000000000001", "0000000000000002",
                    "0000000000000003");
        }

        @Test
        @DisplayName("keeps both records when two identifiers are equal, backup ahead of synthesized")
        void keepsBothEqualKeyRecordsInStreamOrder() {
            Transaction fromBackup = combinedRecord("0000000000000007", BACKUP_MARKER, POS_SOURCE,
                    PURCHASE_AMOUNT);
            Transaction fromSynthesized = combinedRecord("0000000000000007", SYNTHESIZED_MARKER,
                    OPERATOR_SOURCE, RETURN_AMOUNT);
            List<Transaction> stream = List.of(fromBackup, fromSynthesized);

            List<Transaction> emitted = new ArrayList<>();
            for (Transaction item : stream) {
                emitted.add(processor.process(item));
            }

            assertThat(emitted).hasSize(2);
            assertThat(emitted.get(0)).isSameAs(fromBackup);
            assertThat(emitted.get(1)).isSameAs(fromSynthesized);
            assertThat(emitted.get(0).getTranDesc()).isEqualTo(pad(BACKUP_MARKER, 100));
            assertThat(emitted.get(1).getTranDesc()).isEqualTo(pad(SYNTHESIZED_MARKER, 100));
        }

        @Test
        @DisplayName("drops no record, whatever the stream contains")
        void dropsNoRecord() {
            List<Transaction> stream = List.of(
                    combinedRecord("0000000000000008", BACKUP_MARKER, POS_SOURCE, PURCHASE_AMOUNT),
                    combinedRecord("0000000000000008", SYNTHESIZED_MARKER, POS_SOURCE,
                            PURCHASE_AMOUNT),
                    combinedRecord("0000000000000008", SYNTHESIZED_MARKER, OPERATOR_SOURCE,
                            RETURN_AMOUNT));

            assertThat(drive(stream)).hasSameSizeAs(stream);
        }

        @Test
        @DisplayName("applies no ordering of its own, so an unsorted stream emerges unsorted")
        void appliesNoOrderingOfItsOwn() {
            List<Transaction> stream = List.of(
                    combinedRecord("0000000000000009", BACKUP_MARKER, POS_SOURCE, PURCHASE_AMOUNT),
                    combinedRecord("0000000000000002", BACKUP_MARKER, POS_SOURCE, PURCHASE_AMOUNT),
                    combinedRecord("0000000000000005", SYNTHESIZED_MARKER, OPERATOR_SOURCE,
                            RETURN_AMOUNT));

            List<String> emitted = drive(stream);

            assertThat(emitted).containsExactly("0000000000000009", "0000000000000002",
                    "0000000000000005");
            // Stated as an inequality against the ascending arrangement of the same identifiers, so
            // the assertion fails the moment anything on this path starts ordering records.
            assertThat(emitted).isNotEqualTo(emitted.stream().sorted().toList());
        }

        /** Drives a stream through the processor and collects the identifiers it emits, in order. */
        private List<String> drive(final List<Transaction> stream) {
            List<String> emitted = new ArrayList<>();
            for (Transaction item : stream) {
                emitted.add(processor.process(item).getTranId());
            }
            return emitted;
        }
    }

    @Nested
    @DisplayName("Identifier: sixteen characters, compared as characters, never parsed")
    class IdentifierSemantics {

        @Test
        @DisplayName("carries a leading-zero identifier through unparsed")
        void carriesLeadingZeroIdentifiersThroughUnparsed() {
            Transaction item = combinedRecord("0000000000000001", BACKUP_MARKER, POS_SOURCE,
                    PURCHASE_AMOUNT);

            Transaction result = processor.process(item);

            assertThat(result.getTranId()).isEqualTo("0000000000000001").isNotEqualTo("1");
            assertThat(fieldOf(TransactionRecordMapper.toRecordBytes(result),
                    TransactionRecordMapper.TRAN_ID_OFFSET,
                    TransactionRecordMapper.TRAN_ID_LENGTH)).isEqualTo("0000000000000001");
        }

        @Test
        @DisplayName("never trims the identifier, so its trailing spaces survive in the image")
        void neverTrimsTheIdentifier() {
            String paddedIdentifier = pad("1", TransactionRecordMapper.TRAN_ID_LENGTH);
            Transaction item = combinedRecord(paddedIdentifier, BACKUP_MARKER, POS_SOURCE,
                    PURCHASE_AMOUNT);

            Transaction result = processor.process(item);

            assertThat(result.getTranId()).isEqualTo(paddedIdentifier);
            assertThat(fieldOf(TransactionRecordMapper.toRecordBytes(result),
                    TransactionRecordMapper.TRAN_ID_OFFSET,
                    TransactionRecordMapper.TRAN_ID_LENGTH)).isEqualTo(paddedIdentifier);
        }

        @Test
        @DisplayName("never case folds the identifier, so two case variants stay distinct")
        void neverCaseFoldsTheIdentifier() {
            Transaction upper = combinedRecord("A000000000000001", BACKUP_MARKER, POS_SOURCE,
                    PURCHASE_AMOUNT);
            Transaction lower = combinedRecord("a000000000000001", SYNTHESIZED_MARKER, POS_SOURCE,
                    PURCHASE_AMOUNT);

            assertThat(processor.process(upper).getTranId()).isEqualTo("A000000000000001");
            assertThat(processor.process(lower).getTranId()).isEqualTo("a000000000000001");
            assertThat(upper.getTranId()).isNotEqualTo(lower.getTranId());
        }
    }

    @Nested
    @DisplayName("Fixed-width fidelity: 350 encoded bytes, every one of them unchanged")
    class FixedWidthFidelity {

        @Test
        @DisplayName("renders exactly the contracted number of encoded bytes")
        void rendersExactlyTheContractedWidth() {
            Transaction item = combinedRecord("0000000000000010", BACKUP_MARKER, POS_SOURCE,
                    PURCHASE_AMOUNT);

            byte[] image = TransactionRecordMapper.toRecordBytes(processor.process(item));

            assertThat(image).hasSize(350);
            assertThat(CombineTransactionsProcessor.COMBINED_RECORD_LENGTH).isEqualTo(350)
                    .isEqualTo(TransactionRecordMapper.RECORD_LENGTH);
        }

        @Test
        @DisplayName("keeps an unstamped processing timestamp as twenty-six spaces")
        void keepsAnUnstampedProcessingTimestamp() {
            Transaction item = combinedRecord("0000000000000011", BACKUP_MARKER, POS_SOURCE,
                    PURCHASE_AMOUNT);

            byte[] image = TransactionRecordMapper.toRecordBytes(processor.process(item));

            assertThat(fieldOf(image, TransactionRecordMapper.TRAN_PROC_TS_OFFSET,
                    TransactionRecordMapper.TRAN_PROC_TS_LENGTH)).isEqualTo(UNSTAMPED_TIMESTAMP);
        }

        @Test
        @DisplayName("keeps the overpunched sign of a negative amount in the field's final byte")
        void keepsTheOverpunchedSignOfANegativeAmount() {
            Transaction item = combinedRecord("0000000000000012", SYNTHESIZED_MARKER,
                    OPERATOR_SOURCE, RETURN_AMOUNT);

            byte[] image = TransactionRecordMapper.toRecordBytes(processor.process(item));
            String amountField = fieldOf(image, TransactionRecordMapper.TRAN_AMT_OFFSET,
                    TransactionRecordMapper.TRAN_AMT_LENGTH);

            assertThat(amountField).isEqualTo("0000000427N");
            assertThat(TransactionRecordMapper.fromRecord(image).getTranAmt())
                    .isEqualByComparingTo(RETURN_AMOUNT);
        }

        @Test
        @DisplayName("keeps the overpunched sign of a positive amount, which is not a bare digit")
        void keepsTheOverpunchedSignOfAPositiveAmount() {
            Transaction item = combinedRecord("0000000000000013", BACKUP_MARKER, POS_SOURCE,
                    PURCHASE_AMOUNT);

            byte[] image = TransactionRecordMapper.toRecordBytes(processor.process(item));
            String amountField = fieldOf(image, TransactionRecordMapper.TRAN_AMT_OFFSET,
                    TransactionRecordMapper.TRAN_AMT_LENGTH);

            assertThat(amountField).isEqualTo("0000000427E");
            assertThat(TransactionRecordMapper.fromRecord(image).getTranAmt())
                    .isEqualByComparingTo(PURCHASE_AMOUNT);
        }

        @Test
        @DisplayName("keeps the card number and the filler run untouched")
        void keepsTheCardNumberAndTheFillerRun() {
            Transaction item = combinedRecord("0000000000000014", BACKUP_MARKER, POS_SOURCE,
                    PURCHASE_AMOUNT);

            byte[] image = TransactionRecordMapper.toRecordBytes(processor.process(item));

            assertThat(fieldOf(image, TransactionRecordMapper.TRAN_CARD_NUM_OFFSET,
                    TransactionRecordMapper.TRAN_CARD_NUM_LENGTH)).isEqualTo("4111111111111111");
            assertThat(fieldOf(image, TransactionRecordMapper.FILLER_OFFSET,
                    TransactionRecordMapper.FILLER_LENGTH))
                    .isEqualTo(" ".repeat(TransactionRecordMapper.FILLER_LENGTH));
        }

        @Test
        @DisplayName("survives a full round trip through the image at every byte")
        void survivesAFullRoundTripAtEveryByte() {
            Transaction item = combinedRecord("0000000000000015", SYNTHESIZED_MARKER,
                    OPERATOR_SOURCE, RETURN_AMOUNT);
            byte[] image = TransactionRecordMapper.toRecordBytes(item);

            Transaction reparsed = TransactionRecordMapper.fromRecord(image);

            assertThat(TransactionRecordMapper.toRecordBytes(processor.process(reparsed)))
                    .isEqualTo(image);
        }
    }

    @Nested
    @DisplayName("Failure is technical and terminal: nothing is skipped, rejected or swallowed")
    class TechnicalFailure {

        @Test
        @DisplayName("propagates an over-wide field fault from the mapper, unwrapped")
        void propagatesAnOverWideFieldFaultUnwrapped() {
            Transaction item = combinedRecord("0000000000000016", BACKUP_MARKER, POS_SOURCE,
                    PURCHASE_AMOUNT);
            item.setTranTypeCd("012");

            Throwable thrown = catchThrowable(() -> processor.process(item));

            assertThat(thrown).isInstanceOf(IllegalArgumentException.class)
                    .isNotInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("propagates a non-representable character fault from the mapper, unwrapped")
        void propagatesANonAsciiFaultUnwrapped() {
            Transaction item = combinedRecord("0000000000000017", BACKUP_MARKER, POS_SOURCE,
                    PURCHASE_AMOUNT);
            item.setMerchantCity(pad("SAO PAULO\u00e3", 50));

            assertThatThrownBy(() -> processor.process(item))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("propagates an absent mapped property from the mapper, unwrapped")
        void propagatesAnAbsentMappedPropertyUnwrapped() {
            Transaction item = combinedRecord("0000000000000018", BACKUP_MARKER, POS_SOURCE,
                    PURCHASE_AMOUNT);
            item.setTranDesc(null);

            assertThatThrownBy(() -> processor.process(item))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects a null item, naming the job and the step it was reading for")
        void rejectsANullItem() {
            assertThatThrownBy(() -> processor.process(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining(CombineTransactionsProcessor.LEGACY_JOB)
                    .hasMessageContaining(CombineTransactionsProcessor.LEGACY_LOAD_STEP);
        }
    }

    @Nested
    @DisplayName("The width postcondition, exercised directly with a mis-sized image")
    class WidthPostcondition {

        @Test
        @DisplayName("accepts an image of exactly the contracted width")
        void acceptsAnImageOfExactlyTheContractedWidth() {
            byte[] wellFormed = TransactionRecordMapper.toRecordBytes(combinedRecord(
                    "0000000000000019", BACKUP_MARKER, POS_SOURCE, PURCHASE_AMOUNT));

            assertThat(catchThrowable(() -> CombineTransactionsProcessor
                    .requireCombinedRecordWidth(wellFormed, "0000000000000019"))).isNull();
        }

        @Test
        @DisplayName("rejects an image one byte short, naming both widths and the identifier")
        void rejectsAnImageOneByteShort() {
            byte[] tooShort = new byte[RECORD_WIDTH - 1];

            assertThatThrownBy(() -> CombineTransactionsProcessor.requireCombinedRecordWidth(
                    tooShort, "0000000000000020"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(TransactionRecordMapper.ARTEFACT)
                    .hasMessageContaining(String.valueOf(RECORD_WIDTH - 1))
                    .hasMessageContaining(String.valueOf(RECORD_WIDTH))
                    .hasMessageContaining("0000000000000020");
        }

        @Test
        @DisplayName("rejects an image one byte long, the unstripped-terminator shape")
        void rejectsAnImageOneByteLong() {
            byte[] tooLong = new byte[RECORD_WIDTH + 1];

            assertThatThrownBy(() -> CombineTransactionsProcessor.requireCombinedRecordWidth(
                    tooLong, "0000000000000021"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(String.valueOf(RECORD_WIDTH + 1));
        }

        @Test
        @DisplayName("rejects an empty image rather than treating absence as a valid record")
        void rejectsAnEmptyImage() {
            assertThatThrownBy(() -> CombineTransactionsProcessor.requireCombinedRecordWidth(
                    new byte[0], "0000000000000022")).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("Traceability: the constants name the legacy job and its two steps")
    class Traceability {

        @Test
        @DisplayName("names the legacy job and both of its steps")
        void namesTheLegacyJobAndBothSteps() {
            assertThat(CombineTransactionsProcessor.LEGACY_JOB).isEqualTo("COMBTRAN");
            assertThat(CombineTransactionsProcessor.LEGACY_SORT_STEP).isEqualTo("STEP05R");
            assertThat(CombineTransactionsProcessor.LEGACY_LOAD_STEP).isEqualTo("STEP10");
            assertThat(Arrays.asList(CombineTransactionsProcessor.LEGACY_SORT_STEP,
                    CombineTransactionsProcessor.LEGACY_LOAD_STEP)).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("takes the record width from the layout authority rather than restating it")
        void takesTheRecordWidthFromTheLayoutAuthority() {
            assertThat(CombineTransactionsProcessor.COMBINED_RECORD_LENGTH)
                    .isEqualTo(TransactionRecordMapper.RECORD_LENGTH);
        }
    }
}
