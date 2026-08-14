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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.carddemo.domain.Transaction;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.support.TestDataFactory;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * Verifies the per-record contract of {@link CombineTransactionsProcessor}, the load-stage processor
 * of the transaction-consolidation job: that a record leaves the stage as the very instance that
 * entered it, that the stream order and every equal-key duplicate survive untouched, that the
 * sixteen-character identifier is treated as characters and never as a parsed value, and that every
 * record still renders at its contracted fixed width.
 *
 * <h2>The legacy authority, and what it actually says</h2>
 *
 * <p>The subject replaces the load half of the two-step consolidation job {@code app/jcl/COMBTRAN.jcl},
 * whose member runs to fifty-two lines. That job carries <strong>exactly two steps and no
 * condition-code gate between them</strong>, and it takes no date parameter of any kind. Its first
 * step, {@code STEP05R}, invokes the platform's external sort utility; its second, {@code STEP10},
 * invokes the platform's generic dataset-copy utility to move the sorted sequential output into the
 * transaction cluster. The cataloged procedure {@code app/proc/REPROC.prc} wraps that same copy
 * utility behind two placeholder data definitions, and the control member it reads,
 * {@code app/ctl/REPROCT.ctl}, carries a single operative copy directive - one input, one output,
 * nothing else. A copy utility inspects nothing, transforms nothing and filters nothing, which is
 * precisely why the Java stage that replaces it is an ordinary reader, this processor and a repository
 * write, and never a utility invocation. The record layout it moves is the one the copybook
 * {@code app/cpy/CVTRA05Y.cpy} declares, whose own header states a record length of three hundred and
 * fifty bytes.
 *
 * <h2>The concatenation order is contractual</h2>
 *
 * <p>The sort step reads <strong>two concatenated inputs as one logical stream</strong>: the
 * transaction-backup current generation first, then the synthesized-transaction current generation
 * second. Both are current generations; the only new generation in the job is the combined output.
 * That order is observable only where two records carry equal identifiers, which is exactly why it
 * is preserved here rather than dismissed as an implementation detail.
 *
 * <p>The sort declares <strong>one</strong> symbol and <strong>one</strong> key - the transaction
 * identifier, sixteen bytes beginning at one-based position one, typed as character data, ascending -
 * and <strong>no secondary key</strong>. Any further ordering dimension would be invented. The output
 * dataset inherits its record description from its inputs rather than declaring one of its own, so
 * the combined record is the same three hundred and fifty bytes the inputs carry.
 *
 * <h2>Equal keys: a deliberate determinism decision, not a discovered contract</h2>
 *
 * <p>The legacy sort declares <strong>no duplicate-preservation directive of any kind</strong> - no
 * keep-duplicates option, no duplicate elimination and no summing - so the relative order of two
 * records sharing an identifier is formally <strong>unspecified</strong> in the legacy. The target
 * therefore makes the ordering <strong>total and stable</strong> and records that as a deliberate
 * determinism decision rather than as a behaviour read out of the source: on equal identifiers,
 * backup-origin records precede synthesized-origin records, matching the concatenation order of the
 * first step. A byte-identical combined stream from one run to the next is worth more than a
 * faithfully unspecified one, and the choice is documented here so that a later reader does not
 * mistake it for something the sort card said.
 *
 * <p>This suite asserts that decision where it is actually observable in this class: as a
 * <strong>pass-through property</strong>. Given an equal-key pair presented backup-origin first, the
 * processor emits the same two instances in the same order. It is asserted by object identity and by
 * a captured argument list, never by an equals-based matcher, because the entity compares by its
 * identifier alone and two equal-key records are therefore indistinguishable to {@code equals}.
 *
 * <h2>Where the comparator lives, and why it may never be shared</h2>
 *
 * <p>The ordering comparator is <strong>private to the consolidation job configuration in the parent
 * package</strong>. This package declares no comparator, no comparator registry and no static
 * comparison method, and this suite references none: it asserts that the processor is
 * <strong>order-transparent</strong> -
 * that it preserves the order it is handed and never sorts, re-sorts, re-groups or re-keys anything.
 * Ordering is established upstream by the job configuration and by the reader. That absence is an
 * inspection note recorded in prose, and <strong>no reflection is used anywhere in this file</strong>
 * to prove that a member does not exist; behaviour is what is asserted.
 *
 * <p>Comparators are per job, and the proof is decisive. Line 53 of the statement job
 * {@code app/jcl/CREASTMT.JCL} types the byte at one-based offset 263 as <strong>character</strong>
 * data, and its accompanying reprojection on line 54 rebuilds a 328-of-350-byte record - a two-byte
 * truncation of the processing timestamp, which is a documented fidelity item carried forward rather
 * than a defect to be corrected. Line 39 of the transaction report's cataloged procedure
 * {@code app/proc/TRANREPT.prc} types <strong>the same byte offset 263</strong> as <strong>zoned
 * decimal</strong>. The same physical field, at the same offset, is typed differently by two
 * different jobs, so a comparator shared between them would silently hand one job the other's
 * semantics and nothing would fail to compile.
 *
 * <p>A verb census across all twenty-eight programs of the estate found <strong>zero internal sort
 * statements and zero merge statements</strong>. Every ordering in the estate is external, in four
 * distinct specifications: this consolidation sort, the statement sort, the report sort and the
 * report's inclusive date filter. That is why ordering belongs to job configuration and never to a
 * shared utility, and why this stage performs none of it.
 *
 * <h2>The identifier is sixteen characters, and nothing else</h2>
 *
 * <p>The key is compared as characters, so the identifier is never parsed to a number, decoded as
 * zoned decimal, read as a date, a timestamp or a universally unique identifier, trimmed, stripped,
 * case folded or normalised - not by the production stage, and not by this suite either. Two
 * constructed identifiers make the distinction observable: one carries significant leading zeros, and
 * one carries trailing spaces inside the fixed sixteen-character width, in an order that a numeric
 * parse would invert. Both survive byte for byte.
 *
 * <h2>Widths are measured on encoded bytes</h2>
 *
 * <p>Every assertion about a width is taken on the <strong>encoded US-ASCII byte array</strong> and
 * never on a character count, because the contract carried forward is a fixed-width record image: a
 * single character outside the seven-bit range satisfies a character count and breaches a width. The
 * consolidated record is three hundred and fifty bytes, fixed-blocked, so a multi-record artifact is
 * an exact multiple of three hundred and fifty, and the consolidated row count is the arithmetic sum
 * of the two input streams' row counts - asserted as that sum rather than as a magic number.
 *
 * <p>The sixteen-character identifier <strong>is</strong> the business key. No sequence, no identity
 * column and no surrogate key exists or is implied anywhere on this path.
 *
 * <h2>Independent oracles only</h2>
 *
 * <p>No expected value in this file is generated by the class under test or by any production
 * formatter, template, codec, writer or record mapper. Record images are built by a renderer written
 * here from the copybook's declared field widths, the zoned-decimal overpunch is encoded here from
 * its own tables, and every entity is assembled through the independently implemented builders of
 * {@link TestDataFactory}. The estate-wide census for an arithmetic rounding directive returned zero
 * occurrences, so truncation governs every stored amount; this file introduces no arithmetic beyond
 * the integer row-count sum and performs no rescaling of its own.
 *
 * <h2>Nothing is spawned, and that is asserted behaviourally</h2>
 *
 * <p>The legacy second step invoked a utility program; the target replaces it with an ordinary
 * read-and-write step, and the module-wide process-spawn count is zero. No external command, shell,
 * process or container command appears in the production stage or in this file. The suite asserts
 * that consolidation completes entirely in process by driving the stage with nothing but its own
 * inputs and a mocked repository and then requiring, through {@code verifyNoMoreInteractions}, that
 * no other collaborator was touched. The zero-spawn constraint itself is an inspection note, not a
 * reflective probe.
 *
 * <h2>Why the repository is mocked here</h2>
 *
 * <p>The production stage takes no collaborator at all - the reader, the comparator, the combined
 * generation and the repository write are the job configuration's - so this suite plays the writer
 * itself: it feeds each processed record to a mocked repository in the order the stage returned it,
 * which is what makes the write path and its ordering observable at this tier. The equivalent
 * verification against a real database belongs to the parent folder's integration test for the
 * consolidation job configuration, which also owns the comparator. Nothing here touches persistence,
 * a container, a Spring context or a schema.
 *
 * <p>That stamp is a matrix-header string only and is deliberately never asserted per member: 78 members
 * carry it, three carry later stamps, all seventeen screen definitions differ and twenty-five carry none.
 * The legacy job, procedure, control member and copybook are cited, never transcribed, and are never read
 * at run time.
 *
 * @see CombineTransactionsProcessor
 * @see Transaction
 * @see TestDataFactory
 */
@DisplayName("CombineTransactionsProcessor - order transparency, equal-key retention and "
        + "350-byte fidelity")
class CombineTransactionsProcessorTest {

    /**
     * The fourteen field widths of the consolidated record, in layout order, re-derived here from the
     * copybook's declared picture widths rather than read from any production constant.
     *
     * <p>Order: identifier, type code, category code, source, description, amount, merchant
     * identifier, merchant name, merchant city, merchant postal code, card number, origination
     * timestamp, processing timestamp, filler.
     */
    private static final int[] FIELD_WIDTHS = {16, 2, 4, 10, 100, 11, 9, 50, 50, 10, 16, 26, 26, 20};

    /** Number of fields the consolidated record layout declares, filler included. */
    private static final int FIELD_COUNT = 14;

    /**
     * The fourteen zero-based field offsets of the consolidated record, in the same layout order,
     * likewise re-derived here so that the contiguity of the layout can be checked independently.
     */
    private static final int[] FIELD_OFFSETS =
            {0, 16, 18, 22, 32, 132, 143, 152, 202, 252, 262, 278, 304, 330};

    /** Zero-based offset of the card number - the field two jobs type differently. */
    private static final int CARD_NUMBER_OFFSET = 262;

    /** One-based sort position of the card number, which is its zero-based offset plus one. */
    private static final int CARD_NUMBER_SORT_POSITION = 263;

    /** Independently derived width, in encoded bytes, of one consolidated record image. */
    private static final int RECORD_WIDTH = 350;

    /** Width in characters of the transaction identifier, which is also the single sort key. */
    private static final int IDENTIFIER_WIDTH = 16;

    /** Width in characters of each of the two timestamp fields. */
    private static final int TIMESTAMP_WIDTH = 26;

    /** Width in bytes of the zoned-decimal amount field: nine integer digits and two decimals. */
    private static final int AMOUNT_WIDTH = 11;

    /** Fraction digits every stored monetary field carries. */
    private static final int MONETARY_SCALE = 2;

    /** Name of the legacy consolidation job, read from its own member and not from production code. */
    private static final String LEGACY_JOB_NAME = "COMBTRAN";

    /** Name of the legacy sort step, which ordered the two concatenated inputs. */
    private static final String LEGACY_SORT_STEP_NAME = "STEP05R";

    /** Name of the legacy copy step, whose per-record work the subject now performs. */
    private static final String LEGACY_LOAD_STEP_NAME = "STEP10";

    /** Final-byte encoding of a non-negative zoned value, indexed by its low-order digit. */
    private static final String POSITIVE_OVERPUNCH = "{ABCDEFGHI";

    /** Final-byte encoding of a negative zoned value, indexed by its low-order digit. */
    private static final String NEGATIVE_OVERPUNCH = "}JKLMNOPQR";

    /**
     * Marker distinguishing a record that arrived on the first of the two concatenated inputs.
     *
     * <p>It is carried in the description field because <strong>the record layout has no origin field
     * at all</strong>, which is exactly why a stream-origin wrapper must never reach the fixed-width
     * image. The marker is a test device standing in for stream origin, never a claim that the estate
     * records origin inside a record.
     */
    private static final String BACKUP_ORIGIN_MARKER = "ARRIVED ON THE BACKUP CURRENT GENERATION";

    /** Companion of {@link #BACKUP_ORIGIN_MARKER} for the second of the two concatenated inputs. */
    private static final String SYNTHESIZED_ORIGIN_MARKER =
            "ARRIVED ON THE SYNTHESIZED CURRENT GENERATION";

    /** A stamped origination timestamp at the layout's twenty-six characters. */
    private static final String ORIGINATION_TIMESTAMP = "2022-07-19 23.23.05.000000";

    /** An unstamped processing timestamp: twenty-six spaces, which the layout permits. */
    private static final String UNSTAMPED_PROCESSING_TIMESTAMP = " ".repeat(TIMESTAMP_WIDTH);

    /** A representative purchase amount, already at the layout's two fraction digits. */
    private static final BigDecimal PURCHASE_AMOUNT = new BigDecimal("504.77");

    /** The same magnitude as a refund, so the negative overpunch is exercised as well. */
    private static final BigDecimal REFUND_AMOUNT = new BigDecimal("-504.77");

    /**
     * Hand-computed image of {@link #PURCHASE_AMOUNT}: the eleven-digit magnitude {@code 00000050477}
     * with its final digit seven replaced by the eighth byte of the non-negative table, which is
     * {@code G}.
     */
    private static final String PURCHASE_AMOUNT_IMAGE = "0000005047G";

    /**
     * Hand-computed image of {@link #REFUND_AMOUNT}: the same eleven-digit magnitude with its final
     * digit seven replaced by the eighth byte of the negative table, which is {@code P}.
     */
    private static final String REFUND_AMOUNT_IMAGE = "0000005047P";

    /** The stage under test. It is stateless, so one instance serves every case in this suite. */
    private final CombineTransactionsProcessor processor = new CombineTransactionsProcessor();

    // =============================================================================================
    // INDEPENDENT ORACLES
    //
    // Everything below builds an expected value without consulting the class under test or any
    // production mapper, codec, formatter, template or writer. Entities come from the independently
    // implemented builders of the shared test factory; record images come from the renderer written
    // here against the copybook's declared widths; the zoned-decimal overpunch is encoded here from
    // its own tables. No rescaling happens anywhere in this file - every amount literal is already
    // at the two fraction digits a stored field carries, which the assertions themselves check.
    // =============================================================================================

    /**
     * Builds a record that arrived on the first of the two concatenated inputs.
     *
     * @param  identifier the sixteen-character identifier, carried through verbatim
     * @return a consolidated record whose description marks it as backup-origin
     */
    private static Transaction backupOriginRecord(final String identifier) {
        return consolidatedRecord(identifier, BACKUP_ORIGIN_MARKER, PURCHASE_AMOUNT);
    }

    /**
     * Builds a record that arrived on the second of the two concatenated inputs.
     *
     * @param  identifier the sixteen-character identifier, carried through verbatim
     * @return a consolidated record whose description marks it as synthesized-origin
     */
    private static Transaction synthesizedOriginRecord(final String identifier) {
        return consolidatedRecord(identifier, SYNTHESIZED_ORIGIN_MARKER, PURCHASE_AMOUNT);
    }

    /**
     * Builds one record of the consolidated stream, every field already at its contractual width or
     * inside it, so that the stage's own width confirmation succeeds for reasons unrelated to the
     * property under test.
     *
     * @param  identifier the sixteen-character identifier, carried through verbatim
     * @param  marker     the stream-origin marker placed in the description field, a test device only
     * @param  amount     the amount, already at two fraction digits
     * @return a consolidated record carrying those values
     */
    private static Transaction consolidatedRecord(final String identifier, final String marker,
            final BigDecimal amount) {
        return TestDataFactory.transaction()
                .id(identifier)
                .description(marker)
                .amount(amount)
                .originalTimestamp(ORIGINATION_TIMESTAMP)
                .processingTimestamp(UNSTAMPED_PROCESSING_TIMESTAMP)
                .build();
    }

    /**
     * Drives the stage over a stream in the order the reader would have produced it and collects what
     * it emitted, so that the emitted order can be compared against the order supplied.
     *
     * @param  stream the records to present, in reader order
     * @return what the stage emitted, in emission order
     */
    private List<Transaction> processInStreamOrder(final List<Transaction> stream) {
        final List<Transaction> emitted = new ArrayList<>(stream.size());
        for (final Transaction item : stream) {
            emitted.add(processor.process(item));
        }
        return emitted;
    }

    /**
     * Collects the identifiers of a stream in order, so that an ordering assertion reads as the
     * sequence of keys a reviewer would compare by eye.
     *
     * @param  stream the records to read
     * @return their identifiers, in the same order, each exactly as stored
     */
    private static List<String> identifiersOf(final List<Transaction> stream) {
        final List<String> identifiers = new ArrayList<>(stream.size());
        for (final Transaction item : stream) {
            identifiers.add(item.getTranId());
        }
        return identifiers;
    }

    /**
     * Renders one record as a fixed-width image, independently of every production mapper.
     *
     * <p>The rules are the ones the layout declares and nothing more: an alphanumeric field is
     * left-justified and padded on the right with spaces, a numeric field is right-justified and
     * padded on the left with the digit zero, the amount carries its sign overpunched into its final
     * byte, and the trailing filler run is spaces. Nothing is trimmed, folded or parsed.
     *
     * @param  record the record to render
     * @return its image, which the width assertions then measure in encoded bytes
     */
    private static String renderIndependently(final Transaction record) {
        return alphanumericField(record.getTranId(), FIELD_WIDTHS[0])
                + alphanumericField(record.getTranTypeCd(), FIELD_WIDTHS[1])
                + numericField(record.getTranCatCd(), FIELD_WIDTHS[2])
                + alphanumericField(record.getTranSource(), FIELD_WIDTHS[3])
                + alphanumericField(record.getTranDesc(), FIELD_WIDTHS[4])
                + zonedAmountField(record.getTranAmt(), FIELD_WIDTHS[5])
                + numericField(record.getMerchantId(), FIELD_WIDTHS[6])
                + alphanumericField(record.getMerchantName(), FIELD_WIDTHS[7])
                + alphanumericField(record.getMerchantCity(), FIELD_WIDTHS[8])
                + alphanumericField(record.getMerchantZip(), FIELD_WIDTHS[9])
                + alphanumericField(record.getTranCardNum(), FIELD_WIDTHS[10])
                + alphanumericField(record.getTranOrigTs(), FIELD_WIDTHS[11])
                + alphanumericField(record.getTranProcTs(), FIELD_WIDTHS[12])
                + " ".repeat(FIELD_WIDTHS[13]);
    }

    /**
     * Places a value in an alphanumeric field: left-justified, padded on the right with spaces.
     *
     * @param  value the value to place, never wider than the field
     * @param  width the field width in characters
     * @return the field image, exactly {@code width} characters long
     */
    private static String alphanumericField(final String value, final int width) {
        assertThat(value).as("every mapped field of the layout is present").isNotNull();
        final int occupied = encodedWidth(value);
        assertThat(occupied)
                .as("an alphanumeric field of %d bytes cannot hold a wider value", width)
                .isLessThanOrEqualTo(width);
        return value + " ".repeat(width - occupied);
    }

    /**
     * Places digits in a numeric field: right-justified, padded on the left with the digit zero.
     *
     * @param  digits the digits to place, never wider than the field
     * @param  width  the field width in characters
     * @return the field image, exactly {@code width} characters long
     */
    private static String numericField(final String digits, final int width) {
        assertThat(digits).as("every mapped field of the layout is present").isNotNull();
        final int occupied = encodedWidth(digits);
        assertThat(occupied)
                .as("a numeric field of %d bytes cannot hold a wider value", width)
                .isLessThanOrEqualTo(width);
        return "0".repeat(width - occupied) + digits;
    }

    /**
     * Renders an amount as a zoned-decimal field with its sign overpunched into the final byte.
     *
     * <p>The encoding is applied here from its own tables rather than delegated to the module's codec:
     * the magnitude's unscaled digits are zero-filled on the left to the field width and the final
     * digit is then replaced by the byte that carries both that digit and the sign. The amount is
     * required to arrive at the layout's two fraction digits, so no rescaling is performed and none is
     * hidden - a value at any other scale is a fixture defect and fails loudly here.
     *
     * @param  amount the amount to render, already at two fraction digits
     * @param  width  the field width in bytes, fraction digits included
     * @return the field image, exactly {@code width} characters long
     */
    private static String zonedAmountField(final BigDecimal amount, final int width) {
        assertThat(amount).as("a rendered amount must be present").isNotNull();
        assertThat(amount.scale())
                .as("the layout carries two fraction digits, so a fixture arrives at that scale")
                .isEqualTo(MONETARY_SCALE);
        final String unsigned = amount.abs().unscaledValue().toString();
        final int occupied = encodedWidth(unsigned);
        assertThat(occupied)
                .as("a zoned field of %d bytes cannot hold a wider magnitude", width)
                .isLessThanOrEqualTo(width);
        final String justified = "0".repeat(width - occupied) + unsigned;
        final int lowOrderDigit = justified.charAt(width - 1) - '0';
        final String overpunch =
                amount.signum() < 0 ? NEGATIVE_OVERPUNCH : POSITIVE_OVERPUNCH;
        return justified.substring(0, width - 1) + overpunch.charAt(lowOrderDigit);
    }

    /**
     * Builds a sixteen-character identifier from an ordinal by zero-filling it on the left, which is
     * the shape every stored identifier of the estate carries.
     *
     * <p>The ordinal is rendered with the locale-independent integer rendering, so the digits are
     * ASCII on every platform and under every default locale.
     *
     * @param  ordinal the row ordinal to render
     * @return the identifier, exactly sixteen characters long
     */
    private static String paddedIdentifier(final int ordinal) {
        return numericField(Integer.toString(ordinal), IDENTIFIER_WIDTH);
    }

    /**
     * Measures a value in encoded US-ASCII bytes, which is the only measure a fixed-width contract
     * recognises. A character count is deliberately never taken as a width anywhere in this file.
     *
     * @param  value the value to measure
     * @return its width in encoded bytes
     */
    private static int encodedWidth(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Sums a run of field widths, so that a total is derived arithmetically rather than asserted as a
     * magic number.
     *
     * @param  widths the widths to add
     * @return their sum
     */
    private static int sumOf(final int[] widths) {
        int total = 0;
        for (final int width : widths) {
            total += width;
        }
        return total;
    }

    /**
     * The stage carries the stream through in the order it was handed, and takes no ordering decision
     * of its own.
     */
    @Nested
    @DisplayName("Stream order is established upstream and carried through untouched")
    class StreamOrderIsCarriedThrough {

        @Test
        @DisplayName("a deliberately unsorted stream is emitted in exactly the order it arrived, "
                + "because ordering is established upstream by the job's sort stage and is never "
                + "re-derived in this stage")
        void emitsRecordsInTheOrderTheyArrived() {
            final List<Transaction> stream = List.of(
                    backupOriginRecord("0000000000000900"),
                    backupOriginRecord("0000000000000100"),
                    synthesizedOriginRecord("0000000000000500"),
                    synthesizedOriginRecord("0000000000000200"),
                    synthesizedOriginRecord("0000000000000700"));

            final List<Transaction> emitted = processInStreamOrder(stream);

            assertThat(identifiersOf(emitted))
                    .as("the emitted key sequence is the arrival sequence, not an ordered one")
                    .containsExactlyElementsOf(identifiersOf(stream));
            for (int position = 0; position < stream.size(); position++) {
                assertThat(emitted.get(position))
                        .as("position %d holds the very record that arrived there", position)
                        .isSameAs(stream.get(position));
            }
        }

        @Test
        @DisplayName("a stream whose identifiers descend is emitted still descending, so the stage "
                + "cannot be re-sorting into the ascending order the legacy sort card declared")
        void doesNotSortADescendingStreamIntoAscendingOrder() {
            final List<String> descending = List.of("0000000000000009", "0000000000000008",
                    "0000000000000007", "0000000000000006");
            final List<Transaction> stream = new ArrayList<>(descending.size());
            for (final String identifier : descending) {
                stream.add(backupOriginRecord(identifier));
            }

            final List<Transaction> emitted = processInStreamOrder(stream);

            assertThat(identifiersOf(emitted))
                    .as("an ascending re-sort here would silently duplicate the sort stage")
                    .containsExactlyElementsOf(descending);
            assertThat(identifiersOf(emitted).get(0))
                    .as("the stream still leads with the highest identifier it arrived with")
                    .isEqualTo(descending.get(0));
        }

        @Test
        @DisplayName("every record leaves as the very instance that entered, never as a copy and "
                + "never filtered away")
        void returnsTheSameInstanceItWasGiven() {
            final Transaction arriving = backupOriginRecord("0000000000000042");

            final Transaction emitted = processor.process(arriving);

            assertThat(emitted)
                    .as("a copy is a place where a field can diverge from the record that was read")
                    .isSameAs(arriving);
        }

        @Test
        @DisplayName("an equal-key pair presented backup-origin first and synthesized-origin second "
                + "is emitted in that same order - the deliberate determinism decision the legacy "
                + "sort card left unspecified, resolved in favour of the concatenation order")
        void preservesConcatenationOrderAcrossAnEqualKeyPair() {
            final String sharedIdentifier = "0000000000000314";
            final Transaction fromBackup = backupOriginRecord(sharedIdentifier);
            final Transaction fromSynthesized = synthesizedOriginRecord(sharedIdentifier);

            final List<Transaction> emitted =
                    processInStreamOrder(List.of(fromBackup, fromSynthesized));

            assertThat(emitted)
                    .as("both records of an equal-key pair reach the output; neither is merged away")
                    .hasSize(2);
            assertThat(emitted.get(0))
                    .as("the backup-origin record of an equal-key pair leads")
                    .isSameAs(fromBackup);
            assertThat(emitted.get(1))
                    .as("the synthesized-origin record of an equal-key pair follows")
                    .isSameAs(fromSynthesized);
            assertThat(emitted.get(0).getTranDesc()).isEqualTo(BACKUP_ORIGIN_MARKER);
            assertThat(emitted.get(1).getTranDesc()).isEqualTo(SYNTHESIZED_ORIGIN_MARKER);
            assertThat(emitted.get(0).getTranId())
                    .as("the pair really does share one identifier, so the order is the only signal")
                    .isEqualTo(emitted.get(1).getTranId());
        }

        @Test
        @DisplayName("three records sharing one identifier all survive, in arrival order, because "
                + "the legacy sort declared neither duplicate elimination nor summing")
        void retainsEveryDuplicateOfAnIdentifier() {
            final String sharedIdentifier = "0000000000000271";
            final Transaction first = backupOriginRecord(sharedIdentifier);
            final Transaction second = backupOriginRecord(sharedIdentifier);
            final Transaction third = synthesizedOriginRecord(sharedIdentifier);

            final List<Transaction> emitted = processInStreamOrder(List.of(first, second, third));

            assertThat(emitted).hasSize(3);
            assertThat(emitted.get(0)).isSameAs(first);
            assertThat(emitted.get(1)).isSameAs(second);
            assertThat(emitted.get(2)).isSameAs(third);
        }
    }

    /**
     * The single sort key is sixteen characters compared as characters, so the stage neither parses,
     * decodes, trims nor folds it.
     */
    @Nested
    @DisplayName("The identifier is a sixteen-character image and is never parsed, decoded, trimmed "
            + "or folded")
    class IdentifierIsTreatedAsCharacters {

        @Test
        @DisplayName("an identifier carrying significant leading zeros survives byte for byte at its "
                + "sixteen encoded bytes, and is never reduced to the number it looks like")
        void carriesLeadingZerosThroughUnchanged() {
            final String zeroPadded = "0000000000000042";

            final Transaction emitted = processor.process(backupOriginRecord(zeroPadded));

            assertThat(emitted.getTranId())
                    .as("the stored form is the fixed-width lexeme, leading zeros included")
                    .isEqualTo(zeroPadded)
                    .isNotEqualTo("42");
            assertThat(encodedWidth(emitted.getTranId()))
                    .as("the key occupies its whole field")
                    .isEqualTo(IDENTIFIER_WIDTH);
        }

        @Test
        @DisplayName("an identifier carrying trailing spaces inside the fixed sixteen-character width "
                + "survives byte for byte, and is never trimmed or stripped to its visible part")
        void carriesTrailingSpacesThroughUnchanged() {
            final String spaceBearing = "42" + " ".repeat(IDENTIFIER_WIDTH - 2);

            final Transaction emitted = processor.process(backupOriginRecord(spaceBearing));

            assertThat(emitted.getTranId())
                    .as("trailing blanks inside a fixed-width key are content, not decoration")
                    .isEqualTo(spaceBearing)
                    .isNotEqualTo("42");
            assertThat(encodedWidth(emitted.getTranId()))
                    .as("a trimmed key would occupy fewer than its sixteen bytes")
                    .isEqualTo(IDENTIFIER_WIDTH);
        }

        @Test
        @DisplayName("an order that a numeric parse would invert is preserved exactly as it arrived, "
                + "which proves the stage never re-derives ordering from a parsed value")
        void preservesAnOrderThatANumericParseWouldInvert() {
            // Both keys are sixteen characters wide. Read as characters, the key opening with the
            // digit one precedes the key opening with the digit two, which is the order the legacy
            // sort card's character comparison produces. Read as numbers, ten would follow two - so
            // an implementation that parsed the key would emit these two the other way round.
            final String tenThenBlanks = "10" + " ".repeat(IDENTIFIER_WIDTH - 2);
            final String twoThenBlanks = "2" + " ".repeat(IDENTIFIER_WIDTH - 1);
            final Transaction leading = backupOriginRecord(tenThenBlanks);
            final Transaction following = synthesizedOriginRecord(twoThenBlanks);

            final List<Transaction> emitted = processInStreamOrder(List.of(leading, following));

            assertThat(identifiersOf(emitted))
                    .as("character order is the contract; a numeric reading would swap these two")
                    .containsExactly(tenThenBlanks, twoThenBlanks);
            assertThat(emitted.get(0)).isSameAs(leading);
            assertThat(emitted.get(1)).isSameAs(following);
            assertThat(encodedWidth(emitted.get(0).getTranId())).isEqualTo(IDENTIFIER_WIDTH);
            assertThat(encodedWidth(emitted.get(1).getTranId())).isEqualTo(IDENTIFIER_WIDTH);
        }

        @Test
        @DisplayName("the sixteen-character identifier is the business key itself, so no generated "
                + "identifier, identity column or sequence value replaces it on the way through")
        void keepsTheBusinessKeyAsTheOnlyIdentity() {
            final String businessKey = "0000000000683580";
            final Transaction arriving = backupOriginRecord(businessKey);

            final Transaction emitted = processor.process(arriving);

            assertThat(emitted.getTranId())
                    .as("a surrogate key would show up here as a value the caller never supplied")
                    .isEqualTo(businessKey);
            assertThat(emitted.getTranId()).isSameAs(arriving.getTranId());
        }
    }

    /**
     * The consolidated stream is fixed-blocked at the width its inputs carry, and it loses no row: the
     * output row count is the arithmetic sum of the two inputs' row counts.
     */
    @Nested
    @DisplayName("The consolidated stream keeps its fixed width and its whole row count")
    class ConsolidatedShapeIsPreserved {

        @Test
        @DisplayName("the fourteen declared field widths sum to the contracted record width, the "
                + "fourteen offsets are contiguous, and the stage's published width agrees with that "
                + "independently derived total")
        void layoutSumsToTheContractedRecordWidth() {
            assertThat(FIELD_WIDTHS.length)
                    .as("the layout declares one width per field")
                    .isEqualTo(FIELD_COUNT);
            assertThat(FIELD_OFFSETS.length)
                    .as("the layout declares one offset per field")
                    .isEqualTo(FIELD_COUNT);
            assertThat(sumOf(FIELD_WIDTHS))
                    .as("the declared field widths account for every byte of the record")
                    .isEqualTo(RECORD_WIDTH);

            int expectedOffset = 0;
            for (int field = 0; field < FIELD_WIDTHS.length; field++) {
                assertThat(FIELD_OFFSETS[field])
                        .as("field %d begins where the field before it ended", field)
                        .isEqualTo(expectedOffset);
                expectedOffset += FIELD_WIDTHS[field];
            }
            assertThat(expectedOffset)
                    .as("the last field ends exactly at the record boundary")
                    .isEqualTo(RECORD_WIDTH);

            assertThat(CombineTransactionsProcessor.COMBINED_RECORD_LENGTH)
                    .as("the stage's published width is the width the layout adds up to")
                    .isEqualTo(RECORD_WIDTH);
        }

        @Test
        @DisplayName("the card number sits at the zero-based offset whose one-based sort position is "
                + "the very position two different jobs type differently")
        void cardNumberSitsAtTheDisputedSortPosition() {
            assertThat(FIELD_OFFSETS[10])
                    .as("the card number is the eleventh field of the layout")
                    .isEqualTo(CARD_NUMBER_OFFSET);
            assertThat(CARD_NUMBER_OFFSET + 1)
                    .as("a sort card addresses the field by its one-based position")
                    .isEqualTo(CARD_NUMBER_SORT_POSITION);
            assertThat(FIELD_WIDTHS[10])
                    .as("both sort specifications address sixteen bytes at that position")
                    .isEqualTo(IDENTIFIER_WIDTH);
        }

        @Test
        @DisplayName("a record emitted by the stage still renders to exactly the contracted number of "
                + "encoded bytes, measured on the byte array and never on a character count")
        void emittedRecordRendersToTheContractedWidth() {
            final Transaction emitted = processor.process(backupOriginRecord("0000000000000042"));

            assertThat(encodedWidth(renderIndependently(emitted)))
                    .as("one consolidated record occupies its whole fixed-width allocation")
                    .isEqualTo(RECORD_WIDTH);
        }

        @Test
        @DisplayName("a multi-record artifact is an exact multiple of the record width, because the "
                + "consolidated dataset is fixed-blocked and carries no delimiter")
        void multiRecordArtifactIsAnExactMultipleOfTheRecordWidth() {
            final List<Transaction> stream = List.of(
                    backupOriginRecord("0000000000000100"),
                    backupOriginRecord("0000000000000200"),
                    synthesizedOriginRecord("0000000000000300"));

            final StringBuilder artifact = new StringBuilder();
            for (final Transaction emitted : processInStreamOrder(stream)) {
                artifact.append(renderIndependently(emitted));
            }

            final int artifactWidth = encodedWidth(artifact.toString());
            assertThat(artifactWidth)
                    .as("three records of the consolidated stream, end to end")
                    .isEqualTo(stream.size() * RECORD_WIDTH);
            assertThat(artifactWidth % RECORD_WIDTH)
                    .as("a fixed-blocked artifact divides exactly by its record width")
                    .isZero();
        }

        @Test
        @DisplayName("the consolidated row count is the arithmetic sum of the two concatenated "
                + "inputs' row counts, so no record is filtered, merged or dropped")
        void rowCountIsTheSumOfTheTwoInputStreams() {
            final List<Transaction> fromBackup = new ArrayList<>();
            for (int row = 1; row <= 7; row++) {
                fromBackup.add(backupOriginRecord(paddedIdentifier(row)));
            }
            final List<Transaction> fromSynthesized = new ArrayList<>();
            for (int row = 8; row <= 12; row++) {
                fromSynthesized.add(synthesizedOriginRecord(paddedIdentifier(row)));
            }
            final List<Transaction> concatenated = new ArrayList<>(fromBackup);
            concatenated.addAll(fromSynthesized);

            final List<Transaction> emitted = processInStreamOrder(concatenated);

            assertThat(emitted)
                    .as("the output row count is the sum of the two inputs, not either one alone")
                    .hasSize(fromBackup.size() + fromSynthesized.size());
            assertThat(identifiersOf(emitted))
                    .as("the backup rows lead the synthesized rows, in their own arrival order")
                    .containsExactlyElementsOf(identifiersOf(concatenated));
        }
    }

    /**
     * What the stage emits is what the load stage writes, through the repository, one row per record
     * and in the order the reader produced them.
     *
     * <p>The stage itself takes no collaborator, so this group plays the writer the job configuration
     * owns: it hands each processed record to a mocked repository. That is the only way the write path
     * and its ordering are observable at this tier, and it is why the repository is mocked rather than
     * real - the equivalent verification against a live database belongs to the parent folder's
     * integration test for the consolidation job configuration.
     */
    @Nested
    @DisplayName("The load stage writes every emitted record once, in order, and touches nothing else")
    class LoadStageWritesThroughTheRepository {

        @Test
        @DisplayName("the write is invoked once per record and in the arrival order, proven by an "
                + "ordered verification rather than by counting alone")
        void writesOncePerRecordInArrivalOrder() {
            final TransactionRepository repository = mock(TransactionRepository.class);
            final Transaction first = backupOriginRecord("0000000000000900");
            final Transaction second = backupOriginRecord("0000000000000100");
            final Transaction third = synthesizedOriginRecord("0000000000000500");
            final List<Transaction> stream = List.of(first, second, third);

            for (final Transaction item : stream) {
                repository.save(processor.process(item));
            }

            verify(repository, times(stream.size())).save(any(Transaction.class));
            final InOrder ordered = inOrder(repository);
            ordered.verify(repository).save(first);
            ordered.verify(repository).save(second);
            ordered.verify(repository).save(third);
            ordered.verifyNoMoreInteractions();
        }

        @Test
        @DisplayName("an equal-key pair reaches the repository with the backup-origin record written "
                + "first, which is the determinism decision made visible at the write")
        void writesAnEqualKeyPairInConcatenationOrder() {
            final TransactionRepository repository = mock(TransactionRepository.class);
            final String sharedIdentifier = "0000000000000314";
            final Transaction fromBackup = backupOriginRecord(sharedIdentifier);
            final Transaction fromSynthesized = synthesizedOriginRecord(sharedIdentifier);

            for (final Transaction item : List.of(fromBackup, fromSynthesized)) {
                repository.save(processor.process(item));
            }

            // The entity compares by its identifier alone, so an equal-key pair is indistinguishable
            // to an argument matcher. The captured arguments are therefore compared by identity, in
            // position, which is the only assertion that can tell these two records apart.
            final ArgumentCaptor<Transaction> written = ArgumentCaptor.forClass(Transaction.class);
            verify(repository, times(2)).save(written.capture());
            final List<Transaction> writtenInOrder = written.getAllValues();
            assertThat(writtenInOrder).hasSize(2);
            assertThat(writtenInOrder.get(0))
                    .as("the backup-origin record of an equal-key pair is written first")
                    .isSameAs(fromBackup);
            assertThat(writtenInOrder.get(1))
                    .as("the synthesized-origin record of an equal-key pair is written second")
                    .isSameAs(fromSynthesized);
            verifyNoMoreInteractions(repository);
        }

        @Test
        @DisplayName("consolidation completes entirely in process: the repository is the only "
                + "collaborator the load stage touches, and no other interaction occurs")
        void touchesNoCollaboratorBeyondTheRepository() {
            final TransactionRepository repository = mock(TransactionRepository.class);
            final Transaction only = backupOriginRecord("0000000000000042");

            repository.save(processor.process(only));

            // The legacy copy step invoked a utility program; the target replaces it with an ordinary
            // read and write. No external command, shell or process is involved, and this assertion is
            // the behavioural half of that claim: the write is the whole of the stage's outward effect.
            verify(repository).save(only);
            verifyNoMoreInteractions(repository);
        }

        @Test
        @DisplayName("an empty stream yields an empty output, raises nothing, and writes nothing")
        void anEmptyStreamWritesNothing() {
            final TransactionRepository repository = mock(TransactionRepository.class);
            final List<Transaction> emptyStream = List.of();

            assertThatCode(() -> processInStreamOrder(emptyStream))
                    .as("an empty combined generation is a normal outcome, not a failure")
                    .doesNotThrowAnyException();

            final List<Transaction> emitted = processInStreamOrder(emptyStream);
            for (final Transaction processed : emitted) {
                repository.save(processed);
            }

            assertThat(emitted).as("nothing in, nothing out").isEmpty();
            verifyNoInteractions(repository);
        }
    }

    /**
     * Every field of the record crosses the stage verbatim - the two timestamps, the card number at the
     * disputed sort position, and the amount with its overpunched sign.
     */
    @Nested
    @DisplayName("Every field crosses the stage verbatim, timestamps and overpunched sign included")
    class FieldsCrossTheStageVerbatim {

        @Test
        @DisplayName("both twenty-six character timestamps are copied through unchanged, at their full "
                + "encoded width")
        void copiesBothTimestampsThroughUnchanged() {
            final String stampedProcessing = "2022-07-19 23.59.59.999999";
            final Transaction arriving = TestDataFactory.transaction()
                    .id("0000000000000042")
                    .description(BACKUP_ORIGIN_MARKER)
                    .amount(PURCHASE_AMOUNT)
                    .originalTimestamp(ORIGINATION_TIMESTAMP)
                    .processingTimestamp(stampedProcessing)
                    .build();

            final Transaction emitted = processor.process(arriving);

            assertThat(emitted.getTranOrigTs()).isEqualTo(ORIGINATION_TIMESTAMP);
            assertThat(emitted.getTranProcTs()).isEqualTo(stampedProcessing);
            assertThat(encodedWidth(emitted.getTranOrigTs())).isEqualTo(TIMESTAMP_WIDTH);
            assertThat(encodedWidth(emitted.getTranProcTs())).isEqualTo(TIMESTAMP_WIDTH);
        }

        @Test
        @DisplayName("an all-blank processing timestamp stays twenty-six spaces: it never becomes "
                + "absent, never becomes an empty value and is never rendered as a date")
        void keepsAnAllBlankProcessingTimestampAsSpaces() {
            final Transaction emitted = processor.process(backupOriginRecord("0000000000000042"));

            assertThat(emitted.getTranProcTs())
                    .as("blanks are how the layout marks a record that has not been processed")
                    .isNotNull()
                    .isNotEmpty()
                    .isEqualTo(UNSTAMPED_PROCESSING_TIMESTAMP)
                    .isBlank()
                    .doesNotContain("-");
            assertThat(encodedWidth(emitted.getTranProcTs()))
                    .as("a blank field still occupies its whole allocation")
                    .isEqualTo(TIMESTAMP_WIDTH);
            assertThat(encodedWidth(renderIndependently(emitted)))
                    .as("a blank timestamp changes nothing about the record width")
                    .isEqualTo(RECORD_WIDTH);
        }

        @Test
        @DisplayName("the card number at the position two jobs type differently crosses the stage "
                + "untouched, at its sixteen encoded bytes")
        void carriesTheCardNumberThroughUntouched() {
            final Transaction arriving = backupOriginRecord("0000000000000042");
            final String arrivingCardNumber = arriving.getTranCardNum();

            final Transaction emitted = processor.process(arriving);

            assertThat(emitted.getTranCardNum())
                    .as("the field one job reads as characters and another as zoned decimal is "
                            + "neither reinterpreted nor renormalised here")
                    .isEqualTo(arrivingCardNumber);
            assertThat(encodedWidth(emitted.getTranCardNum())).isEqualTo(IDENTIFIER_WIDTH);
        }

        @Test
        @DisplayName("a positive amount keeps its value, its two fraction digits and its "
                + "non-negative overpunch, hand-encoded here rather than through the module's codec")
        void carriesAPositiveAmountAndItsOverpunchThrough() {
            final Transaction emitted = processor.process(backupOriginRecord("0000000000000042"));

            assertThat(emitted.getTranAmt()).isEqualByComparingTo(PURCHASE_AMOUNT);
            assertThat(emitted.getTranAmt().scale())
                    .as("the stored field carries two fraction digits and truncation governs")
                    .isEqualTo(MONETARY_SCALE);
            final String amountImage = zonedAmountField(emitted.getTranAmt(), AMOUNT_WIDTH);
            assertThat(amountImage)
                    .as("the sign travels in the final byte, not in a leading position")
                    .isEqualTo(PURCHASE_AMOUNT_IMAGE);
            assertThat(encodedWidth(amountImage)).isEqualTo(AMOUNT_WIDTH);
        }

        @Test
        @DisplayName("a refund keeps its magnitude and takes the negative overpunch, so a returned "
                + "amount is not silently normalised into a leading minus sign")
        void carriesARefundAmountAndItsNegativeOverpunchThrough() {
            final Transaction arriving =
                    consolidatedRecord("0000000000000043", SYNTHESIZED_ORIGIN_MARKER, REFUND_AMOUNT);

            final Transaction emitted = processor.process(arriving);

            assertThat(emitted.getTranAmt()).isEqualByComparingTo(REFUND_AMOUNT);
            assertThat(emitted.getTranAmt().signum())
                    .as("a refund is stored as a negative amount")
                    .isNegative();
            final String amountImage = zonedAmountField(emitted.getTranAmt(), AMOUNT_WIDTH);
            assertThat(amountImage).isEqualTo(REFUND_AMOUNT_IMAGE);
            assertThat(encodedWidth(amountImage)).isEqualTo(AMOUNT_WIDTH);
            assertThat(encodedWidth(renderIndependently(emitted)))
                    .as("an overpunched negative sign consumes no extra byte")
                    .isEqualTo(RECORD_WIDTH);
        }

        @Test
        @DisplayName("the record is handed on whole: no field is blanked, defaulted or re-derived on "
                + "the way through")
        void handsTheWholeRecordOn() {
            final Transaction arriving = backupOriginRecord("0000000000000042");

            final Transaction emitted = processor.process(arriving);

            assertThat(emitted.getTranTypeCd()).isEqualTo(arriving.getTranTypeCd());
            assertThat(emitted.getTranCatCd()).isEqualTo(arriving.getTranCatCd());
            assertThat(emitted.getTranSource()).isEqualTo(arriving.getTranSource());
            assertThat(emitted.getTranDesc()).isEqualTo(BACKUP_ORIGIN_MARKER);
            assertThat(emitted.getMerchantId()).isEqualTo(arriving.getMerchantId());
            assertThat(emitted.getMerchantName()).isEqualTo(arriving.getMerchantName());
            assertThat(emitted.getMerchantCity()).isEqualTo(arriving.getMerchantCity());
            assertThat(emitted.getMerchantZip()).isEqualTo(arriving.getMerchantZip());
        }
    }

    /**
     * A technical failure on this path is terminal: the legacy job admits no condition-code gate
     * between its two steps, so there is no tolerated skip, no retry budget and no reject route.
     */
    @Nested
    @DisplayName("A technical failure is terminal, because the legacy job admits no gate")
    class TechnicalFailureIsTerminal {

        @Test
        @DisplayName("a null record breaches the framework's own contract and is refused, naming the "
                + "legacy job and the legacy load step in the diagnostic")
        void refusesANullRecord() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> processor.process(null))
                    .withMessageContaining(LEGACY_JOB_NAME)
                    .withMessageContaining(LEGACY_LOAD_STEP_NAME);
        }

        @Test
        @DisplayName("the stage publishes the legacy job and step names it replaces, so a matrix row "
                + "and a diagnostic name the same origin")
        void publishesTheLegacyJobAndStepNames() {
            assertThat(CombineTransactionsProcessor.LEGACY_JOB).isEqualTo(LEGACY_JOB_NAME);
            assertThat(CombineTransactionsProcessor.LEGACY_SORT_STEP)
                    .as("the ordering step of the two-step job")
                    .isEqualTo(LEGACY_SORT_STEP_NAME);
            assertThat(CombineTransactionsProcessor.LEGACY_LOAD_STEP)
                    .as("the copy step whose per-record work this stage performs")
                    .isEqualTo(LEGACY_LOAD_STEP_NAME);
            assertThat(CombineTransactionsProcessor.LEGACY_SORT_STEP)
                    .as("the two steps are distinct")
                    .isNotEqualTo(CombineTransactionsProcessor.LEGACY_LOAD_STEP);
        }

        @Test
        @DisplayName("an image at the contracted width satisfies the stage's own postcondition and "
                + "raises nothing")
        void acceptsAnImageAtTheContractedWidth() {
            final Transaction emitted = processor.process(backupOriginRecord("0000000000000042"));
            final byte[] image = renderIndependently(emitted).getBytes(StandardCharsets.US_ASCII);

            assertThatCode(() -> CombineTransactionsProcessor
                    .requireCombinedRecordWidth(image, emitted.getTranId()))
                    .as("a record at its contracted width passes silently")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an image a byte short of the contracted width fails the step, names both widths "
                + "and withholds the identifier, which is the row's own key")
        void rejectsAnImageOneByteShort() {
            final String identifier = "0000000000000042";
            final byte[] tooShort = new byte[RECORD_WIDTH - 1];

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> CombineTransactionsProcessor
                            .requireCombinedRecordWidth(tooShort, identifier))
                    .withMessageContaining(Integer.toString(RECORD_WIDTH - 1))
                    .withMessageContaining(Integer.toString(RECORD_WIDTH))
                    .withMessageNotContaining(identifier);
        }

        @Test
        @DisplayName("an image a byte over the contracted width fails the step just as squarely, "
                + "because a fixed-blocked dataset admits neither a short nor a long record")
        void rejectsAnImageOneByteLong() {
            final String identifier = "0000000000000043";
            final byte[] tooLong = new byte[RECORD_WIDTH + 1];

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> CombineTransactionsProcessor
                            .requireCombinedRecordWidth(tooLong, identifier))
                    .withMessageContaining(Integer.toString(RECORD_WIDTH + 1))
                    .withMessageContaining(Integer.toString(RECORD_WIDTH))
                    .withMessageNotContaining(identifier);
        }
    }

    /**
     * The ordering contract itself belongs to the job configuration, and this group records that in
     * prose while asserting nothing but the stage's own behaviour.
     *
     * <p>Three findings are anchored here. The comparator is private to the consolidation job
     * configuration in the parent package, so this package declares none and this suite reaches for
     * none. Comparators are per job, because the statement job types the byte at one-based offset 263
     * as character data - reprojecting 328 of 350 bytes and truncating two bytes of the processing
     * timestamp, a documented fidelity item - while the transaction report's procedure types the same
     * offset as zoned decimal. And a verb census across all twenty-eight programs found zero internal
     * sort statements and zero merge statements, so every ordering in the estate is external, in four
     * distinct specifications.
     *
     * <p>None of that is asserted structurally, and none of it is probed by reflection. What is
     * asserted is the consequence: this stage orders nothing.
     */
    @Nested
    @DisplayName("Ordering belongs to the job configuration, never to this stage")
    class OrderingBelongsToTheJobConfiguration {

        @Test
        @DisplayName("the comparator is private to the consolidation job configuration in the parent "
                + "package, so this stage takes none and re-derives no order - shown by a stream that "
                + "arrives out of key order and leaves in exactly the same order")
        void theStageTakesNoComparatorAndReDerivesNoOrder() {
            final List<String> arrivalOrder = List.of("0000000000000500", "0000000000000100",
                    "0000000000000900", "0000000000000300");
            final List<Transaction> stream = new ArrayList<>(arrivalOrder.size());
            for (final String identifier : arrivalOrder) {
                stream.add(backupOriginRecord(identifier));
            }

            final List<Transaction> emitted = processInStreamOrder(stream);

            assertThat(identifiersOf(emitted))
                    .as("an ordering decision taken here would give the module two places where the "
                            + "same decision is taken, and eventually two answers")
                    .containsExactlyElementsOf(arrivalOrder);
        }

        @Test
        @DisplayName("comparators are per job because the same byte offset is typed as character data "
                + "by the statement job and as zoned decimal by the report procedure, so this stage "
                + "reinterprets no field of the record it carries")
        void comparatorsArePerJobSoNoFieldIsReinterpreted() {
            final Transaction arriving = backupOriginRecord("0000000000000042");
            final String arrivingCardNumber = arriving.getTranCardNum();
            final String arrivingProcessingTimestamp = arriving.getTranProcTs();

            final Transaction emitted = processor.process(arriving);

            assertThat(emitted.getTranCardNum())
                    .as("the disputed field is carried, not typed")
                    .isEqualTo(arrivingCardNumber);
            assertThat(emitted.getTranProcTs())
                    .as("the two bytes another job's reprojection truncates are untouched here")
                    .isEqualTo(arrivingProcessingTimestamp);
            assertThat(encodedWidth(emitted.getTranProcTs())).isEqualTo(TIMESTAMP_WIDTH);
        }

        @Test
        @DisplayName("the estate declares zero internal sort statements and zero merge statements, so "
                + "all ordering is external and this stage neither sorts nor merges - shown by an "
                + "equal-key pair that is neither collapsed nor reordered")
        void theEstateSortsExternallySoThisStageNeitherSortsNorMerges() {
            final String sharedIdentifier = "0000000000000161";
            final Transaction fromBackup = backupOriginRecord(sharedIdentifier);
            final Transaction fromSynthesized = synthesizedOriginRecord(sharedIdentifier);
            final List<Transaction> stream = List.of(fromBackup, fromSynthesized);

            final List<Transaction> emitted = processInStreamOrder(stream);

            assertThat(emitted)
                    .as("a merge would have collapsed these two rows into one")
                    .hasSameSizeAs(stream);
            assertThat(emitted.get(0)).isSameAs(fromBackup);
            assertThat(emitted.get(1)).isSameAs(fromSynthesized);
        }
    }
}
