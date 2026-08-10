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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.TransactionCategory;
import com.carddemo.domain.TransactionType;
import com.carddemo.domain.enums.FileStatus;
import com.carddemo.domain.id.TransactionCategoryId;
import com.carddemo.exception.AbendException;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.TransactionCategoryRepository;
import com.carddemo.repository.TransactionTypeRepository;
import com.carddemo.service.AbendService;
import com.carddemo.service.ReportTransactionInput;
import com.carddemo.service.ReportTransactionSource;
import com.carddemo.service.TransactionReportService;
import com.carddemo.service.TransactionReportService.TransactionReportResult;
import com.carddemo.support.TestDataFactory;
import com.carddemo.util.ReportLineFormatter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * Surefire-tier verification of {@link TransactionReportProcessor}, the batch stage that produces the
 * transaction detail report.
 *
 * <h2>Legacy authority</h2>
 *
 * <p>The behaviour asserted here belongs to the transaction-report program
 * {@code app/cbl/CBTRN03C.cbl} - 649 lines, 26 paragraphs - driven by the job
 * {@code app/jcl/TRANREPT.jcl} and the cataloged procedure {@code app/proc/TRANREPT.prc}. Those
 * members are <strong>cited, never transcribed</strong>: no COBOL, JCL, copybook, cataloged-procedure
 * or resource-definition source line appears in this file, and nothing on this path reads the legacy
 * tree at run time. What is carried across is metadata only - record lengths, field widths, byte
 * offsets, counts, the page size, the compared file-status codes, program, paragraph, step, dataset
 * and data-definition names, the two date bounds the job bakes in, and the handful of contract
 * literals that appear byte for byte in the program's own output.
 *
 * <p>Matrix header for the provenance of every expectation below:
 * {@code SHA 7756d895ffeb65f7ea72aaa609e356d9899afcec / CardDemo_v1.0-15-g27d6c6f-68 (2022-07-19)}.
 * That release stamp is deliberately recorded as a <strong>matrix-header string and never as a
 * per-member assertion</strong>, because it is not universal across the estate: 78 members carry it,
 * 3 carry later stamps, all 17 screen definitions differ and 25 members carry none at all. Asserting
 * it member by member would therefore encode a falsehood; carrying it once, here, records the
 * checkout the figures were measured from without claiming more than the estate supports.
 *
 * <h2>Why the suite is shaped the way it is</h2>
 *
 * <p>The stage delegates the whole report to {@link TransactionReportService} and every record image
 * to {@link ReportLineFormatter}. Those two are <strong>the subject's direct collaborators and are
 * therefore barred from generating any expected value here</strong>: an expectation produced by the
 * formatter would agree with the formatter by construction and would assert nothing. So the suite has
 * exactly two kinds of oracle, and no third:
 *
 * <ol>
 *   <li><strong>Mockito-isolated interaction verification</strong> - {@link InOrder},
 *       {@link org.mockito.Mockito#verify}, {@link ArgumentCaptor} and a recording sink - for the
 *       date filter, the card break, the accumulation chain, the pagination arithmetic and the
 *       terminal abend ordering; and</li>
 *   <li><strong>independently hand-built 133-byte line images</strong> - assembled in this file from
 *       plain text and explicit padding - for every width and content assertion. The zoned-decimal
 *       overpunch is likewise encoded here by hand, and {@code ZonedDecimalCodec} is never called.
 *       No scaling operation appears anywhere in this file.</li>
 * </ol>
 *
 * <p>A mocked generator is used only where a real one cannot reach the condition at all: the width
 * and purity postconditions the stage imposes on its delegate, an absent result, and a generation
 * that fails. Everywhere else the real service runs over mocked lookup repositories and a spied real
 * abend path, so the properties asserted are properties of the report rather than of a stub.
 *
 * <p>Determinism needs no clock here. Every date, bound, amount and identifier is an explicit
 * literal, nothing reads the wall clock, no random source is consulted and no sleep is used for
 * synchronisation. The one place a locale could matter - the strict calendar parse that proves the
 * date comparison is textual rather than temporal - pins {@link Locale#ROOT} and
 * {@link ResolverStyle#STRICT} explicitly.
 *
 * <h2>The measured report contract</h2>
 *
 * <p>Every written record is exactly {@value #REPORT_RECORD_BYTES} encoded bytes - the report-name
 * header, the blank separator, the column header, the all-hyphen rule line, every detail row, every
 * page-total line, every account-total line and the grand-total line alike. The program declares that
 * record length at its line 85 and the job allocates the output dataset fixed-blocked at the same
 * figure at its line 78, which is what makes the width a byte contract rather than a text convention.
 * Widths are therefore measured on the <strong>encoded byte array</strong> throughout and never on a
 * character count, and nothing is ever trimmed before a comparison. A produced artefact's size is an
 * exact multiple of that width, and this suite proves it by <em>repeated addition</em> rather than by
 * a division or a multiplication.
 *
 * <p>The 80-byte date-parameter record declared at program line 88 is a separate thing: it is the
 * date-window <em>input</em> image, not an output line. Its significant prefix is 21 bytes - ten
 * start-date characters, a single space at position 11, ten end-date characters - and both figures are
 * asserted separately so the two cannot be conflated.
 *
 * <h2>Decision-log candidates recorded here</h2>
 *
 * <p>Each of the following is faithful legacy behaviour that a well-meaning "fix" would destroy. They
 * are recorded as prose because this file must not create or edit {@code docs/traceability-matrix.md},
 * {@code docs/decision-log.md} or {@code docs/gate-evidence.md}.
 *
 * <ul>
 *   <li><strong>The zero-arithmetic census.</strong> A census for arithmetic-compute statements across
 *       all 649 lines of the program returned <strong>zero</strong>. The only numeric operations in the
 *       feature are accumulation by addition and the modulo page test. This suite therefore introduces
 *       no arithmetic of its own either: no multiplication, no division, no subtraction, no average and
 *       no percentage appears in any expectation. Where a total is expected it is the sum of the
 *       amounts that were actually added, accumulated by hand from explicit
 *       {@link BigDecimal} string constructions. The estate-wide census for a rounding directive
 *       likewise returned zero occurrences, which is why the module truncates rather than rounds - and
 *       why this file, which introduces no arithmetic at all, needs no rounding mode.</li>
 *   <li><strong>The line counter and the page size are plain integers.</strong> They are declared with
 *       zero decimal places at program lines 129-132, the counter initialised to zero and the page size
 *       carrying the value {@value #PAGE_SIZE_LINES}. They are never monetary, never scaled, never
 *       rounded and never routed through the zoned-decimal codec. Every monetary field of the same
 *       program is a nine-integer-digit two-decimal zoned value, so conflating the two families would
 *       be a real defect rather than a stylistic one - which is why the two are asserted as separate
 *       families below.</li>
 *   <li><strong>The missed page break.</strong> The account-total block runs in the mainline, before
 *       the report-writing paragraph, so its {@code +2} is applied outside the modulo test's field of
 *       view. A card change arriving while the counter stands at 19 carries it to 21, and the modulo
 *       test then evaluates 21 against {@value #PAGE_SIZE_LINES} - which is not zero. The multiple of
 *       the page size was stepped over and never observed, so the break is genuinely missed and the
 *       page runs long. Generally, any start-of-record counter value congruent to 19 modulo the page
 *       size produces the miss. Reproduced, not fixed.</li>
 *   <li><strong>Account totals never reach the grand total.</strong> The page-total block folds the
 *       page total into the grand total and zeroes the page total; the account-total block zeroes only
 *       its own accumulator. Amounts therefore reach the grand total <em>only</em> through page
 *       totals, and the account totals are a parallel accumulation that is reported and discarded.
 *       Folding them in as well would count every amount of the report twice.</li>
 *   <li><strong>The end-of-input double count.</strong> The end-of-file arm at program lines 197-204
 *       emits two diagnostics and then re-adds the <em>stale</em> last record's amount to both the page
 *       total and the account total before flushing. The last transaction of a run is therefore counted
 *       twice in the page total and, because the page total is what feeds the grand total, twice in the
 *       grand total. Not reconciled and not deduplicated.</li>
 *   <li><strong>No account-total block at end of input.</strong> The same arm writes the page-total
 *       block and the grand-total block and no account-total block, so the final account's total line
 *       never appears. Not supplied, because supplying it would add a record the legacy report never
 *       contained.</li>
 *   <li><strong>The grand-total block increments nothing.</strong> Every other write site in the member
 *       is followed by a counter increment; the grand total is the last record of the report, so none
 *       follows it. An increment added "for consistency" would be an edit to the program.</li>
 *   <li><strong>The out-of-window arm is a control transfer, not a per-record skip.</strong> In the
 *       legacy the non-matching arm of the inclusive range guard at lines 173-178 transfers control
 *       past the end of the enclosing sentence, and because the read loop is a single sentence that
 *       arm leaves the <em>whole</em> loop rather than skipping one record. It is unreachable in
 *       practice only because the external sort in {@code app/proc/TRANREPT.prc} pre-filters the
 *       identical window - inclusive at both ends, start bound {@code 2022-01-01} at job line 43 and
 *       end bound {@code 2022-07-06} at job line 44 - before the program reads anything. Accordingly
 *       this suite asserts the inclusive first-ten-character filter exactly as the production class
 *       implements it and <strong>no test here asserts loop termination on the first out-of-window
 *       record</strong>, because doing so would encode an unreachable arm as an expectation.</li>
 *   <li><strong>Ordering is external and its comparator is per job.</strong> The report job orders on
 *       one key only - the card number, sixteen bytes at one-based position 263, ascending, typed
 *       <em>zoned decimal</em> in this job's specification - and defines a second symbol for the
 *       processing date, ten bytes at one-based position 305, typed <em>character</em>, used only by
 *       the inclusion condition. There is no secondary key. The decisive reason a comparator must
 *       never be shared between jobs is that the <em>same</em> byte offset 263 is typed
 *       <em>character</em> in the statement job at {@code app/jcl/CREASTMT.JCL} line 53 and
 *       <em>zoned decimal</em> here at {@code app/proc/TRANREPT.prc} line 39, so a shared comparator
 *       would hand one job the other's semantics without anything failing to compile. Reinforcing
 *       census: a verb census across all 28 programs found <strong>zero internal sort statements and
 *       zero merge statements</strong> - all ordering is external, in four distinct specifications.
 *       This is metadata only: the comparator belongs to the parent folder's job configuration and is
 *       neither declared, referenced nor asserted in this file.</li>
 *   <li><strong>The compared file-status vocabulary.</strong> The statuses actually compared across
 *       the estate are exactly {@code 00}, {@code 10} and {@code 23}. Two further codes appear in an
 *       earlier specification section - as a file-not-found and a duplicate-key value - but neither is
 *       compared anywhere in the source, so no test in this file depends on either of them. The
 *       discrepancy is recorded here rather than silently resolved.</li>
 *   <li><strong>The report-write failure arm is unreachable in this tier.</strong> The service
 *       diagnoses, then emits the raw two-byte status, then abends - the same ordering as the three
 *       reference lookups - but it can only be entered by a record that does not measure the contracted
 *       width, and the layout authority builds every group at that width and checks it. The reachable
 *       equivalent is the stage's own postcondition on its delegate, which diagnoses and then raises,
 *       and that is what is asserted below; the abend code and the abend context are proved on the
 *       three reference-lookup paths, which reach the real abend path for real.</li>
 *   <li><strong>Two decorated-but-unvalidated legacy naming defects</strong> are published by the
 *       stage's own constants and asserted here rather than reproduced: the job labels two consecutive
 *       steps identically, and the cataloged procedure declares itself under the name of a different
 *       procedure member of the estate.</li>
 * </ul>
 *
 * @see TransactionReportProcessor
 * @see TransactionReportService
 * @see ReportLineFormatter
 */
@DisplayName("TransactionReportProcessor - the 133-byte transaction detail report of CBTRN03C")
class TransactionReportProcessorTest {

    // =============================================================================================
    // THE CONTRACT FIGURES, DECLARED HERE AS THIS SUITE'S OWN ORACLE
    //
    // Each figure is measured from the legacy authority named in the class documentation and is
    // declared independently of the production constant that carries it, so that the two can be
    // cross-checked against one another instead of one being read out of the other. Nothing below is
    // derived by arithmetic: every count is a literal.
    // =============================================================================================

    /** Encoded width of every written report record, from program line 85 and job line 78. */
    private static final int REPORT_RECORD_BYTES = 133;

    /** The page size the paginating modulo test runs against, from program lines 131-132. */
    private static final int PAGE_SIZE_LINES = 20;

    /** Records the header block writes, and therefore the counter increment it applies. */
    private static final int HEADER_BLOCK_RECORDS = 4;

    /** Counter increment of the page-total block: its own line, then a repeated rule line. */
    private static final int PAGE_TOTAL_BLOCK_INCREMENT = 2;

    /** Counter increment of the account-total block: its own line, then a repeated rule line. */
    private static final int ACCOUNT_TOTAL_BLOCK_INCREMENT = 2;

    /** Counter increment of one detail row. */
    private static final int DETAIL_ROW_INCREMENT = 1;

    /** Counter increment of the grand-total block: it writes a record and increments nothing. */
    private static final int GRAND_TOTAL_BLOCK_INCREMENT = 0;

    /** Detail rows the first page carries, the page size less the four header records. */
    private static final int DETAILS_ON_FIRST_PAGE = 16;

    /** Detail rows every later page carries, the page size less the six records a break writes. */
    private static final int DETAILS_PER_STEADY_PAGE = 14;

    /** Significant bytes of the date-parameter image: ten, a space, ten. */
    private static final int PARAMETER_SIGNIFICANT_BYTES = 21;

    /** Encoded width of the whole eighty-column date-parameter card image. */
    private static final int PARAMETER_CARD_BYTES = 80;

    /** One-based position of the single space separating the two bounds. */
    private static final int PARAMETER_SEPARATOR_POSITION = 11;

    /** Encoded width of one ten-character date bound. */
    private static final int DATE_BOUND_BYTES = 10;

    /** Encoded width of the fifteen-character amount mask both amount families use. */
    private static final int AMOUNT_MASK_BYTES = 15;

    /** Encoded width of a nine-integer-digit two-decimal zoned field. */
    private static final int NINE_DIGIT_ZONED_BYTES = 11;

    /** Encoded width of a ten-integer-digit two-decimal zoned field. */
    private static final int TEN_DIGIT_ZONED_BYTES = 12;

    /** Encoded width of a four-integer-digit two-decimal zoned field. */
    private static final int FOUR_DIGIT_ZONED_BYTES = 6;

    /** Overpunched final byte for positive digits zero through nine, encoded here by hand. */
    private static final String POSITIVE_OVERPUNCH_ALPHABET = "{ABCDEFGHI";

    /** Overpunched final byte for negative digits zero through nine, encoded here by hand. */
    private static final String NEGATIVE_OVERPUNCH_ALPHABET = "}JKLMNOPQR";

    /** The space this suite pads with, named so a padding call site cannot be misread. */
    private static final char SPACE = ' ';

    /** The hyphen the rule line is filled with and the detail line separates two fields with. */
    private static final char HYPHEN = '-';

    /** The dot the three total lines are filled with. */
    private static final char DOT = '.';

    /** Sign character the total mask carries for a value that is not negative. */
    private static final char PLUS = '+';

    // =============================================================================================
    // THE LEGACY DISPLAY LITERALS AND STATUS VOCABULARY
    //
    // These are contract values that appear byte for byte in the program's own diagnostic output,
    // which is why they are permitted here as metadata. The abend code is deliberately NOT restated:
    // it is taken from the exception's published constant.
    // =============================================================================================

    /** The raw two-byte status a reference lookup reports for a key it cannot resolve. */
    private static final String RECORD_NOT_FOUND_STATUS = FileStatus.RECORD_NOT_FOUND.getCode();

    /** The operation name the three reference lookups report. */
    private static final String READ_OPERATION = "READ";

    /** Data-definition name of the card cross-reference the report resolves an account through. */
    private static final String DD_CARDXREF = "CARDXREF";

    /** Data-definition name of the transaction type table. */
    private static final String DD_TRANTYPE = "TRANTYPE";

    /** Data-definition name of the transaction category table. */
    private static final String DD_TRANCATG = "TRANCATG";

    /** Legacy display literal for an unresolved card cross-reference. */
    private static final String DIAG_INVALID_CARDXREF = "INVALID CARD NUMBER";

    /** Legacy display literal for an unresolved transaction type. */
    private static final String DIAG_INVALID_TRANTYPE = "INVALID TRANSACTION TYPE";

    /** Legacy display literal for an unresolved transaction category. */
    private static final String DIAG_INVALID_TRANCATG = "INVALID TRAN CATG KEY";

    // =============================================================================================
    // THE FIXTURE VALUES
    // =============================================================================================

    /** Inclusive lower bound of the ordinary fixture window, ten characters in fixed ISO form. */
    private static final String START = "2022-07-01";

    /** Inclusive upper bound of the ordinary fixture window, ten characters in fixed ISO form. */
    private static final String END = "2022-07-31";

    /** A date comfortably inside the ordinary fixture window. */
    private static final String INSIDE = "2022-07-05";

    /** First card of the fixtures; treated as characters throughout, never parsed. */
    private static final String CARD_A = "4111111111111111";

    /** Second card of the fixtures, used to provoke the account break. */
    private static final String CARD_B = "4222222222222222";

    /** Account the first card resolves to, eleven characters as the layout carries it. */
    private static final String ACCOUNT_A = "00000000011";

    /** Account the second card resolves to. */
    private static final String ACCOUNT_B = "00000000022";

    /** Customer the first card resolves to, nine characters as the layout carries it. */
    private static final String CUSTOMER_A = "000000001";

    /** Customer the second card resolves to. */
    private static final String CUSTOMER_B = "000000002";

    /** The transaction type code every fixture record carries. */
    private static final String TYPE_CODE = "01";

    /** The type description the lookup resolves, short enough that no truncation is involved. */
    private static final String TYPE_DESCRIPTION = "PURCHASE";

    /** The transaction category code most fixture records carry. */
    private static final String CATEGORY_CODE = "0005";

    /** A second category code, so a second lookup is not served from the run's memo. */
    private static final String OTHER_CATEGORY_CODE = "0006";

    /** The category description the lookup resolves. */
    private static final String CATEGORY_DESCRIPTION = "RESTAURANT";

    /** The transaction source every fixture record carries. */
    private static final String SOURCE = "POS TERM";

    /** The description field of a fixture record; the report does not carry it. */
    private static final String DESCRIPTION = "PURCHASE AT MERCHANT";

    /** Merchant identifier of a fixture record; the report does not carry it. */
    private static final String MERCHANT_ID = "000000001";

    /** Merchant name of a fixture record; the report does not carry it. */
    private static final String MERCHANT_NAME = "MERCHANT";

    /** Merchant city of a fixture record; the report does not carry it. */
    private static final String MERCHANT_CITY = "CITY";

    /** Merchant postal code of a fixture record; the report does not carry it. */
    private static final String MERCHANT_ZIP = "1234567890";

    /** The amount every ordinary fixture record carries, one currency unit. */
    private static final String ONE_UNIT = "1.00";

    /** The time-of-day tail that completes a ten-character date into a 26-byte timestamp. */
    private static final String TIMESTAMP_TAIL = "-00.00.00.000000";

    // =============================================================================================
    // THE INDEPENDENT ORACLE
    //
    // Every expected record image below is assembled here, from plain text and explicit padding, and
    // nothing in this section calls the stage, the report service, the layout formatter, the
    // zoned-decimal codec, a record mapper or a statement template. That prohibition is absolute and
    // is sharpest in this file, because three of those are the subject and its two collaborators.
    //
    // Two primitives do the padding, and neither performs arithmetic: one appends a fill character
    // until a length is reached, the other repeats a character a stated number of times. No
    // difference of two lengths is ever taken.
    // =============================================================================================

    /**
     * Left-justifies a value in a field of the stated width by appending spaces until the width is
     * reached, which is what a COBOL alphanumeric move does to a shorter value.
     *
     * <p>Every call site supplies a value no longer than its field, so no truncation is involved and
     * none is performed here. The loop is a comparison and an append; no width difference is computed.
     *
     * @param value the value to place
     * @param width the field width
     * @return the value padded on the right to exactly {@code width} characters
     */
    private static String pad(final String value, final int width) {
        final StringBuilder padded = new StringBuilder(value);
        while (padded.length() < width) {
            padded.append(SPACE);
        }
        return padded.toString();
    }

    /**
     * Builds a run of one character at the stated width.
     *
     * @param character the character to repeat
     * @param width     how many times to repeat it
     * @return the run
     */
    private static String fill(final char character, final int width) {
        return String.valueOf(character).repeat(width);
    }

    /**
     * Builds one fifteen-character amount mask by right-aligning a literal magnitude text and pinning
     * the sign character at the leftmost position.
     *
     * <p>This is not a re-implementation of the layout's mask: it neither suppresses leading zeros nor
     * places group separators nor decides a sign. The caller supplies the rendered magnitude as a
     * literal - {@code "1.00"}, {@code "16.00"} and so on - and the sign it expects, so the expectation
     * remains hand-built. Every magnitude used in this suite is below one thousand, so no group
     * separator can arise; a fixture that needed one would state the separator in its own literal.
     *
     * <p>The detail family passes a space for a value that is not negative and a minus for one that is;
     * the total family passes a plus or a minus. That difference is the whole point of the two masks
     * being separate, and it is asserted rather than assumed.
     *
     * @param sign          the character the leftmost position carries
     * @param magnitudeText the rendered magnitude, without a sign
     * @return the fifteen-character mask
     */
    private static String mask(final char sign, final String magnitudeText) {
        final StringBuilder rendered = new StringBuilder(magnitudeText);
        while (rendered.length() < AMOUNT_MASK_BYTES) {
            rendered.insert(0, SPACE);
        }
        rendered.setCharAt(0, sign);
        return rendered.toString();
    }

    /** @return the detail mask for a value that is not negative, whose sign position is blank */
    private static String detailMask(final String magnitudeText) {
        return mask(SPACE, magnitudeText);
    }

    /** @return the detail mask for a negative value, whose sign position carries a minus */
    private static String negativeDetailMask(final String magnitudeText) {
        return mask(HYPHEN, magnitudeText);
    }

    /** @return the always-signed total mask for a value that is not negative */
    private static String totalMask(final String magnitudeText) {
        return mask(PLUS, magnitudeText);
    }

    /** @return the always-signed total mask for a negative value */
    private static String negativeTotalMask(final String magnitudeText) {
        return mask(HYPHEN, magnitudeText);
    }

    /** @return the mask both families render for a value of exactly zero: the field goes blank */
    private static String blankMask() {
        return fill(SPACE, AMOUNT_MASK_BYTES);
    }

    /**
     * Hand-builds the report-name header: the short report name, the long report name, the date-range
     * label, the two bounds and the literal that joins them, then the trailing pad to the record width.
     *
     * @param startDate the ten-character lower bound
     * @param endDate   the ten-character upper bound
     * @return the expected 133-byte record
     */
    private static String expectedNameHeader(final String startDate, final String endDate) {
        return pad("DALYREPT", 38)
                + pad("Daily Transaction Report", 41)
                + "Date Range: "
                + startDate
                + " to "
                + endDate
                + fill(SPACE, 18);
    }

    /** @return the expected blank separator record of the header block: the record width in spaces */
    private static String expectedBlankLine() {
        return fill(SPACE, REPORT_RECORD_BYTES);
    }

    /** @return the expected column header record of the header block */
    private static String expectedColumnHeader() {
        return pad("Transaction ID", 17)
                + pad("Account ID", 12)
                + pad("Transaction Type", 19)
                + pad("Tran Category", 35)
                + pad("Tran Source", 14)
                + fill(SPACE, 1)
                + pad("        Amount", 16)
                + fill(SPACE, 19);
    }

    /** @return the expected all-hyphen rule line: the record width in hyphens, never padded */
    private static String expectedRuleLine() {
        return fill(HYPHEN, REPORT_RECORD_BYTES);
    }

    /**
     * Hand-builds one detail record.
     *
     * <p>The two unconditional hyphen separators after the type code and after the category code are
     * written out explicitly, as are the single-byte fillers and the two trailing pads, so the
     * expectation states the layout rather than borrowing it.
     *
     * @param transactionId       the sixteen-character identifier
     * @param accountId           the eleven-character account the cross-reference resolved
     * @param typeCode            the two-character type code
     * @param typeDescription     the type description the lookup resolved
     * @param categoryCode        the four-digit category code
     * @param categoryDescription the category description the lookup resolved
     * @param source              the ten-character source
     * @param amountMask          the fifteen-character detail mask
     * @return the expected 133-byte record
     */
    private static String expectedDetailLine(final String transactionId, final String accountId,
            final String typeCode, final String typeDescription, final String categoryCode,
            final String categoryDescription, final String source, final String amountMask) {
        return pad(transactionId, 16)
                + fill(SPACE, 1)
                + pad(accountId, 11)
                + fill(SPACE, 1)
                + pad(typeCode, 2)
                + fill(HYPHEN, 1)
                + pad(typeDescription, 15)
                + fill(SPACE, 1)
                + pad(categoryCode, 4)
                + fill(HYPHEN, 1)
                + pad(categoryDescription, 29)
                + fill(SPACE, 1)
                + pad(source, 10)
                + fill(SPACE, 4)
                + amountMask
                + fill(SPACE, 2)
                + fill(SPACE, 19);
    }

    /** @return the expected detail record of an ordinary first-card fixture transaction */
    private static String expectedCardADetail(final String transactionId, final String amountMask) {
        return expectedDetailLine(transactionId, ACCOUNT_A, TYPE_CODE, TYPE_DESCRIPTION,
                CATEGORY_CODE, CATEGORY_DESCRIPTION, SOURCE, amountMask);
    }

    /** @return the expected page-total record carrying the supplied total mask */
    private static String expectedPageTotalLine(final String amountMask) {
        return pad("Page Total", 11) + fill(DOT, 86) + amountMask + fill(SPACE, 21);
    }

    /** @return the expected account-total record carrying the supplied total mask */
    private static String expectedAccountTotalLine(final String amountMask) {
        return pad("Account Total", 13) + fill(DOT, 84) + amountMask + fill(SPACE, 21);
    }

    /** @return the expected grand-total record carrying the supplied total mask */
    private static String expectedGrandTotalLine(final String amountMask) {
        return pad("Grand Total", 11) + fill(DOT, 86) + amountMask + fill(SPACE, 21);
    }

    /** @return the expected four records of the header block, in their one legal order */
    private static List<String> expectedHeaderBlock(final String startDate, final String endDate) {
        return List.of(expectedNameHeader(startDate, endDate), expectedBlankLine(),
                expectedColumnHeader(), expectedRuleLine());
    }

    /**
     * Hand-encodes a nine-integer-digit two-decimal zoned field, overpunching the sign into the final
     * byte from the alphabets declared in this file.
     *
     * <p>The codec is deliberately not called. The caller supplies the leading ten digit characters of
     * the magnitude as a literal and the value of the final digit, and this method appends that digit's
     * overpunch character from the appropriate alphabet. Concatenation is the whole of the
     * transformation - no length is differenced and no digit is computed - and that is what makes the
     * encoding an independent oracle rather than a second copy of the codec.
     *
     * @param leadingTenDigits the first ten magnitude digits, as a literal
     * @param finalDigit       the value of the eleventh magnitude digit, zero through nine
     * @param negative         whether the value is negative
     * @return the eleven-byte zoned field
     */
    private static String zonedField(final String leadingTenDigits, final int finalDigit,
            final boolean negative) {
        final String alphabet = negative ? NEGATIVE_OVERPUNCH_ALPHABET : POSITIVE_OVERPUNCH_ALPHABET;
        return leadingTenDigits + alphabet.charAt(finalDigit);
    }

    /** @return the encoded byte width of a record image, which is the only width this suite trusts */
    private static int encodedWidth(final String record) {
        return record.getBytes(StandardCharsets.US_ASCII).length;
    }

    /** Zero-based offset at which every amount of the layout begins, detail and total alike. */
    private static final int AMOUNT_FIELD_OFFSET = 97;

    /** Zero-based offset one past the end of the fifteen-character amount field. */
    private static final int AMOUNT_FIELD_END = 112;

    /**
     * Slices the fifteen-character amount field out of a record image.
     *
     * <p>Both bounds are literals, so no width is differenced. The slice is taken rather than the
     * formatter consulted, which is what keeps the sign and blanking assertions independent.
     *
     * @param record the record image
     * @return the fifteen-character amount field
     */
    private static String amountFieldOf(final String record) {
        return record.substring(AMOUNT_FIELD_OFFSET, AMOUNT_FIELD_END);
    }

    /** @return the twenty-one significant bytes of the date-parameter image, hand-assembled */
    private static String structuredParameterImage() {
        return START + fill(SPACE, 1) + END;
    }

    /** @return the eighty-column date-parameter card image whose leading bytes are the structure */
    private static String parameterCardImage() {
        return structuredParameterImage() + fill(SPACE, 59);
    }

    // =============================================================================================
    // THE HARNESS
    //
    // A REAL report service runs over mocked lookup repositories and a SPIED real abend path, wrapped
    // in the real stage. That shape is deliberate: bound inclusivity, the header order, the page break,
    // the missed page break and the accumulation chain are properties of the report that the stage is
    // required to preserve, and a mocked generator would assert nothing about any of them. Spying the
    // real abend path rather than mocking it means the diagnostic is really emitted and the exception
    // is really raised by production code, while the call order remains observable.
    //
    // Nothing here touches persistence, no container is started, no Spring context is built and neither
    // support base class is extended. The repositories are mocks and the sink is a recording list.
    // =============================================================================================

    /** The random reader that resolves a card number to its owning account. */
    private final CardCrossReferenceRepository crossReferences =
            mock(CardCrossReferenceRepository.class);

    /** The random reader for transaction type descriptions. */
    private final TransactionTypeRepository types = mock(TransactionTypeRepository.class);

    /** The random reader for transaction category descriptions. */
    private final TransactionCategoryRepository categories =
            mock(TransactionCategoryRepository.class);

    /** The real terminal path, spied so its two calls stay observable and its abend stays real. */
    private final AbendService abendService = spy(new AbendService());

    /** The real report generator: the module's single translation of the report program. */
    private final TransactionReportService service =
            new TransactionReportService(crossReferences, types, categories, abendService);

    /** A plain registry, so the stage's meters are recorded without a Spring context. */
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    /** The stage under test. */
    private final TransactionReportProcessor processor =
            new TransactionReportProcessor(service, registry);

    /** The frozen ordered generation the next item will carry. */
    private List<Transaction> fixture = List.of();

    /** Records the sink received from the most recent item, in emission order. */
    private List<String> emitted = new ArrayList<>();

    /**
     * Interleaved trace of what the run did, in the order it did it.
     *
     * <p>The sink appends a record event and each lookup stub appends a lookup event, so the relative
     * order of an emitted record and a repository call is observable without mocking a generic type -
     * which under this module's zero-warning build is not an option, because a mock of a generic
     * consumer cannot be created without an unchecked operation.
     */
    private final List<String> trace = new ArrayList<>();

    /** Trace prefix of one record offered to the sink. */
    private static final String TRACE_RECORD = "RECORD:";

    /** Trace prefix of one cross-reference lookup, carrying the key it was given. */
    private static final String TRACE_LOOKUP_XREF = "LOOKUP-XREF:";

    /**
     * Stubs the three lookups so that every fixture record resolves and no description is invented.
     *
     * <p>The cross-reference stub also writes a trace event, which is what makes the card break's
     * ordering - account total, then remember the new card, then resolve it - observable.
     */
    private void stubResolvableLookups() {
        when(crossReferences.findById(CARD_A)).thenAnswer(invocation -> {
            this.trace.add(TRACE_LOOKUP_XREF + CARD_A);
            return Optional.of(new CardCrossReference(CARD_A, CUSTOMER_A, ACCOUNT_A));
        });
        when(crossReferences.findById(CARD_B)).thenAnswer(invocation -> {
            this.trace.add(TRACE_LOOKUP_XREF + CARD_B);
            return Optional.of(new CardCrossReference(CARD_B, CUSTOMER_B, ACCOUNT_B));
        });
        when(types.findById(TYPE_CODE))
                .thenReturn(Optional.of(new TransactionType(TYPE_CODE, TYPE_DESCRIPTION)));
        when(categories.findById(any(TransactionCategoryId.class))).thenAnswer(invocation -> {
            final TransactionCategoryId key = invocation.getArgument(0);
            return Optional.of(new TransactionCategory(key.getTranTypeCd(), key.getTranCatCd(),
                    CATEGORY_DESCRIPTION));
        });
    }

    /** Arms the frozen ordered generation the next item will carry. */
    private void supply(final List<Transaction> content) {
        this.fixture = List.copyOf(content);
    }

    /**
     * Builds one complete item over a detached snapshot of the armed fixture.
     *
     * <p>The item carries its own destination because the stage streams each record to a sink as it is
     * composed and never hands back a list. The destination is remembered so a test can assert on what
     * the run actually emitted, and it also feeds the interleaved trace.
     *
     * @param dateParameterCard the parameter image this item carries
     * @return the item
     */
    private ReportTransactionInput item(final String dateParameterCard) {
        final List<Transaction> snapshot = List.copyOf(this.fixture);
        final ReportTransactionSource source = position -> position < snapshot.size()
                ? Optional.of(snapshot.get(position))
                : Optional.empty();
        final List<String> destination = new ArrayList<>();
        this.emitted = destination;
        final Consumer<String> sink = record -> {
            this.trace.add(TRACE_RECORD + record);
            destination.add(record);
        };
        return new ReportTransactionInput(dateParameterCard, source, sink);
    }

    /** @return one item over the armed fixture, carrying the twenty-one byte structured image */
    private ReportTransactionInput structuredItem() {
        return item(structuredParameterImage());
    }

    /** @return one item over the armed fixture, carrying the eighty-column card image */
    private ReportTransactionInput cardImageItem() {
        return item(parameterCardImage());
    }

    /**
     * One processed report: the stage's own observations paired with the records its sink received.
     *
     * @param observed the stage's observations of the run
     * @param records  the records the sink received, in emission order
     */
    private record ProcessedReport(TransactionReportResult observed, List<String> records) {

        /** @return the grand total the run reported */
        BigDecimal grandTotal() {
            return this.observed.grandTotal();
        }

        /** @return how many times the page-total block ran */
        int pageCount() {
            return this.observed.pageCount();
        }

        /** @return the final value of the legacy line counter */
        long lineCount() {
            return this.observed.lineCount();
        }

        /** @return how many times the account-total block ran */
        int accountBreakCount() {
            return this.observed.accountBreakCount();
        }
    }

    /**
     * Runs the stage over one item and pairs its observations with what its sink received.
     *
     * @param candidate the item to process
     * @return the processed report
     */
    private ProcessedReport process(final ReportTransactionInput candidate) {
        final List<String> destination = this.emitted;
        final TransactionReportResult observed = this.processor.process(candidate);
        assertThat(observed.reportRecordCount())
                .as("the stage's own record count must agree with what its sink received")
                .isEqualTo(destination.size());
        return new ProcessedReport(observed, List.copyOf(destination));
    }

    /** @return the armed fixture processed through the structured parameter image */
    private ProcessedReport reportOfFixture() {
        return process(structuredItem());
    }

    /**
     * Builds one fixture transaction at the widths the 350-byte transaction layout declares.
     *
     * <p>Only the fields the report reads carry meaningful values; the remainder are present because the
     * layout declares them. The processing timestamp is the ten-character date followed by a fixed
     * time-of-day tail, which is exactly the shape the report's first-ten-character filter reads.
     *
     * @param transactionId  the sixteen-character identifier
     * @param cardNumber     the sixteen-character card number
     * @param amount         the amount, as a decimal literal
     * @param processingDate the ten-character processing date
     * @param categoryCode   the four-digit category code
     * @return the transaction
     */
    private static Transaction transaction(final String transactionId, final String cardNumber,
            final String amount, final String processingDate, final String categoryCode) {
        final String timestamp = processingDate + TIMESTAMP_TAIL;
        return new Transaction(transactionId, TYPE_CODE, categoryCode, SOURCE, DESCRIPTION,
                new BigDecimal(amount), MERCHANT_ID, MERCHANT_NAME, MERCHANT_CITY, MERCHANT_ZIP,
                cardNumber, timestamp, timestamp);
    }

    /** @return one fixture transaction carrying the ordinary category code */
    private static Transaction transaction(final String transactionId, final String cardNumber,
            final String amount, final String processingDate) {
        return transaction(transactionId, cardNumber, amount, processingDate, CATEGORY_CODE);
    }

    /** @return the sixteen-character zero-filled identifier the layout carries for an ordinal */
    private static String identifier(final int ordinal) {
        final StringBuilder digits = new StringBuilder(Integer.toString(ordinal));
        while (digits.length() < 16) {
            digits.insert(0, '0');
        }
        return digits.toString();
    }

    /**
     * Builds a run of one-unit transactions on one card, all inside the fixture window.
     *
     * @param count      how many to build
     * @param cardNumber the card they all carry
     * @return the transactions, in ordinal order
     */
    private static List<Transaction> oneUnitEach(final int count, final String cardNumber) {
        final List<Transaction> all = new ArrayList<>();
        for (int ordinal = 1; ordinal <= count; ordinal++) {
            all.add(transaction(identifier(ordinal), cardNumber, ONE_UNIT, INSIDE));
        }
        return all;
    }

    /**
     * Builds a run of one-unit transactions that switches card partway through, so the account break
     * lands on a chosen ordinal.
     *
     * @param count            how many to build in total
     * @param firstOnSecondCard the ordinal of the first record carried by the second card
     * @return the transactions, in ordinal order
     */
    private static List<Transaction> oneUnitEachSwitchingCardAt(final int count,
            final int firstOnSecondCard) {
        final List<Transaction> all = new ArrayList<>();
        for (int ordinal = 1; ordinal <= count; ordinal++) {
            final String card = ordinal < firstOnSecondCard ? CARD_A : CARD_B;
            all.add(transaction(identifier(ordinal), card, ONE_UNIT, INSIDE));
        }
        return all;
    }

    /** @return the records of a report whose leading character is a digit, that is, the detail rows */
    private static List<String> detailsOf(final ProcessedReport result) {
        final List<String> details = new ArrayList<>();
        for (final String record : result.records()) {
            if (Character.isDigit(record.charAt(0))) {
                details.add(record);
            }
        }
        return details;
    }

    /** @return the records of a report that begin with the supplied label */
    private static List<String> labelled(final ProcessedReport result, final String label) {
        final List<String> matches = new ArrayList<>();
        for (final String record : result.records()) {
            if (record.startsWith(label)) {
                matches.add(record);
            }
        }
        return matches;
    }

    /** @return how many detail rows precede the first record carrying the supplied label */
    private static int detailsBefore(final ProcessedReport result, final String label) {
        final List<String> details = new ArrayList<>();
        for (final String record : result.records()) {
            if (record.startsWith(label)) {
                break;
            }
            if (Character.isDigit(record.charAt(0))) {
                details.add(record);
            }
        }
        return details.size();
    }

    /**
     * Counts the detail rows of each page by walking the emitted records once and closing the current
     * page whenever a page-total record is met.
     *
     * @param result the processed report
     * @return the detail count of each page, in page order
     */
    private static List<Integer> detailsPerPage(final ProcessedReport result) {
        final List<Integer> perPage = new ArrayList<>();
        final List<String> current = new ArrayList<>();
        for (final String record : result.records()) {
            if (record.startsWith("Page Total")) {
                perPage.add(current.size());
                current.clear();
            } else if (Character.isDigit(record.charAt(0))) {
                current.add(record);
            }
        }
        return perPage;
    }

    /** Sums a run of amounts the way the program accumulates them: by repeated addition, nothing else. */
    private static BigDecimal accumulated(final List<String> amounts) {
        BigDecimal running = new BigDecimal("0.00");
        for (final String amount : amounts) {
            running = running.add(new BigDecimal(amount));
        }
        return running;
    }

    /** Repeats one amount literal the stated number of times, for the hand-computed sums below. */
    private static List<String> repeatedAmount(final String amount, final int occurrences) {
        final List<String> amounts = new ArrayList<>();
        for (int index = 1; index <= occurrences; index++) {
            amounts.add(amount);
        }
        return amounts;
    }

    /**
     * Builds a stage over a generator that offers exactly the supplied records to the item's sink.
     *
     * <p>This is the one place a mocked generator is legitimate: the stage's width and purity
     * postconditions cannot be reached through a real generator, because the layout authority builds
     * every group at the contracted width and refuses anything else. The stub does not produce an
     * expected value - it produces the malformed <em>input</em> to the postcondition under test.
     *
     * @param reportRecords the records the generator offers, in order
     * @return the stage
     */
    private TransactionReportProcessor stageYielding(final List<String> reportRecords) {
        final TransactionReportService stubbed = mock(TransactionReportService.class);
        when(stubbed.generateReportFromDateParameterCard(
                any(ReportTransactionSource.class), any(), any()))
                .thenAnswer(invocation -> {
                    final Consumer<String> sink = invocation.getArgument(1);
                    reportRecords.forEach(sink);
                    return new TransactionReportResult(reportRecords.size(),
                            new BigDecimal("0.00"), 0, 0L, 0);
                });
        return new TransactionReportProcessor(stubbed, registry);
    }

    @Nested
    @DisplayName("the fixed-width contract: every written record is exactly 133 encoded bytes")
    class TheFixedWidthContract {

        @Test
        @DisplayName("this suite's own width figures agree with the layout authority's constants")
        void handDeclaredWidthsAgreeWithTheLayoutAuthority() {
            // The figures were measured from the legacy authority and declared in this file
            // independently, so this cross-check is a genuine agreement between two sources rather
            // than one reading the other.
            assertThat(REPORT_RECORD_BYTES).isEqualTo(ReportLineFormatter.REPORT_RECORD_WIDTH);
            assertThat(REPORT_RECORD_BYTES).isEqualTo(TestDataFactory.TRANSACTION_REPORT_WIDTH);
            assertThat(REPORT_RECORD_BYTES)
                    .isEqualTo(TransactionReportProcessor.REPORT_RECORD_LENGTH);
            assertThat(PAGE_SIZE_LINES).isEqualTo(ReportLineFormatter.PAGE_SIZE);
            assertThat(HEADER_BLOCK_RECORDS)
                    .isEqualTo(ReportLineFormatter.HEADER_BLOCK_RECORD_COUNT);
            assertThat(AMOUNT_MASK_BYTES).isEqualTo(ReportLineFormatter.AMOUNT_MASK_WIDTH);
            assertThat(PARAMETER_SIGNIFICANT_BYTES)
                    .isEqualTo(ReportLineFormatter.DATE_PARAMETER_STRUCTURED_WIDTH);
            assertThat(PARAMETER_CARD_BYTES)
                    .isEqualTo(ReportLineFormatter.DATE_PARAMETER_CARD_WIDTH);
            assertThat(DATE_BOUND_BYTES).isEqualTo(ReportLineFormatter.DATE_WIDTH);
        }

        @Test
        @DisplayName("every hand-built record image of every group measures the contracted width")
        void everyHandBuiltGroupMeasuresTheContractedWidth() {
            final List<String> images = new ArrayList<>(expectedHeaderBlock(START, END));
            images.add(expectedCardADetail(identifier(1), detailMask(ONE_UNIT)));
            images.add(expectedPageTotalLine(totalMask("2.00")));
            images.add(expectedAccountTotalLine(totalMask("2.00")));
            images.add(expectedGrandTotalLine(totalMask("2.00")));

            for (final String image : images) {
                assertThat(encodedWidth(image))
                        .as("a hand-built expectation must itself be a legal report record")
                        .isEqualTo(REPORT_RECORD_BYTES);
            }
            assertThat(images).hasSize(8);
        }

        @Test
        @DisplayName("the four header records, the detail row and the three total lines all match")
        void everyEmittedGroupEqualsItsHandBuiltImage() {
            stubResolvableLookups();
            supply(List.of(transaction(identifier(1), CARD_A, ONE_UNIT, INSIDE),
                    transaction(identifier(2), CARD_B, ONE_UNIT, INSIDE)));

            final ProcessedReport result = reportOfFixture();
            final List<String> records = result.records();

            // Header block, then card A's detail, then the account-total block the card change fires,
            // then card B's detail, then the end-of-input page-total block and the grand total.
            assertThat(records).hasSize(11);
            assertThat(records.get(0)).isEqualTo(expectedNameHeader(START, END));
            assertThat(records.get(1)).isEqualTo(expectedBlankLine());
            assertThat(records.get(2)).isEqualTo(expectedColumnHeader());
            assertThat(records.get(3)).isEqualTo(expectedRuleLine());
            assertThat(records.get(4))
                    .isEqualTo(expectedCardADetail(identifier(1), detailMask(ONE_UNIT)));
            assertThat(records.get(5)).isEqualTo(expectedAccountTotalLine(totalMask(ONE_UNIT)));
            assertThat(records.get(6)).isEqualTo(expectedRuleLine());
            assertThat(records.get(7)).isEqualTo(expectedDetailLine(identifier(2), ACCOUNT_B,
                    TYPE_CODE, TYPE_DESCRIPTION, CATEGORY_CODE, CATEGORY_DESCRIPTION, SOURCE,
                    detailMask(ONE_UNIT)));
            // The stale last amount is re-added at the end of the input, so card B's single unit is
            // counted twice into the page total.
            assertThat(records.get(8)).isEqualTo(expectedPageTotalLine(totalMask("3.00")));
            assertThat(records.get(9)).isEqualTo(expectedRuleLine());
            assertThat(records.get(10)).isEqualTo(expectedGrandTotalLine(totalMask("3.00")));

            for (final String record : records) {
                assertThat(encodedWidth(record)).isEqualTo(REPORT_RECORD_BYTES);
            }
        }

        @Test
        @DisplayName("a multi-page, multi-card report holds no record of any other width")
        void everyRecordOfABusyReportMeasuresTheContractedWidth() {
            stubResolvableLookups();
            supply(oneUnitEachSwitchingCardAt(40, 21));

            final ProcessedReport result = reportOfFixture();

            assertThat(result.records()).isNotEmpty();
            for (final String record : result.records()) {
                assertThat(encodedWidth(record)).isEqualTo(REPORT_RECORD_BYTES);
                assertThat(record).doesNotContain(System.lineSeparator());
            }
        }

        @Test
        @DisplayName("the produced artefact's size is an exact multiple of the record width")
        void artefactSizeIsAnExactMultipleOfTheRecordWidth() {
            stubResolvableLookups();
            supply(oneUnitEachSwitchingCardAt(40, 21));

            final ProcessedReport result = reportOfFixture();
            final StringBuilder artefact = new StringBuilder();
            for (final String record : result.records()) {
                artefact.append(record);
            }

            // Built by repeated addition of the record width, never by a multiplication or a
            // division: one addition per record the run emitted. Equality with the measured artefact
            // is what makes the size an exact multiple of the width.
            int expectedSize = 0;
            for (int index = 0; index < result.records().size(); index++) {
                expectedSize = expectedSize + REPORT_RECORD_BYTES;
            }

            assertThat(result.records()).isNotEmpty();
            assertThat(encodedWidth(artefact.toString())).isEqualTo(expectedSize);
        }

        @Test
        @DisplayName("a mis-sized record is refused by the stage rather than handed on")
        void misSizedRecordIsRefused() {
            final TransactionReportProcessor narrow =
                    stageYielding(List.of(fill(SPACE, 132)));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> narrow.process(structuredItem()))
                    .withMessageContaining("132")
                    .withMessageContaining(Integer.toString(REPORT_RECORD_BYTES));
        }

        @Test
        @DisplayName("a record the charset cannot represent is refused although its byte count fits")
        void impureRecordIsRefused() {
            final String impure = "\u00e9" + fill(SPACE, 132);
            final TransactionReportProcessor tainted = stageYielding(List.of(impure));

            // The substitution byte keeps the count right, which is exactly why purity is proved
            // before the width is measured.
            assertThat(encodedWidth(impure)).isEqualTo(REPORT_RECORD_BYTES);
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> tainted.process(structuredItem()))
                    .withMessageContaining("US-ASCII")
                    .withMessageContaining("position 0");
        }

        @Test
        @DisplayName("nothing is trimmed: the rule line is hyphens to the very last byte")
        void nothingIsTrimmed() {
            stubResolvableLookups();
            supply(oneUnitEach(1, CARD_A));

            final ProcessedReport result = reportOfFixture();
            final String ruleLine = result.records().get(3);

            assertThat(ruleLine).isEqualTo(expectedRuleLine());
            assertThat(encodedWidth(ruleLine)).isEqualTo(REPORT_RECORD_BYTES);
            assertThat(encodedWidth(ruleLine.strip())).isEqualTo(REPORT_RECORD_BYTES);

            // The blank separator is a real emitted record of spaces, not padding: it keeps its full
            // width even though every one of its bytes is a space, and stripping it would destroy it
            // entirely - which is why no comparison in this suite ever trims.
            final String blankSeparator = result.records().get(1);
            assertThat(encodedWidth(blankSeparator)).isEqualTo(REPORT_RECORD_BYTES);
            assertThat(blankSeparator).isEqualTo(expectedBlankLine());
            assertThat(blankSeparator.strip()).isEmpty();
        }

        @Test
        @DisplayName("the amount oracle overpunches the sign by hand, never through the codec")
        void theAmountOracleEncodesItsOwnOverpunch() {
            assertThat(POSITIVE_OVERPUNCH_ALPHABET)
                    .isEqualTo(TestDataFactory.POSITIVE_OVERPUNCH);
            assertThat(NEGATIVE_OVERPUNCH_ALPHABET)
                    .isEqualTo(TestDataFactory.NEGATIVE_OVERPUNCH);

            // One currency unit in a nine-integer-digit two-decimal zoned field: ten leading digits
            // and a final digit of zero overpunched positive.
            final String positiveUnit = zonedField("0000000010", 0, false);
            // Five currency units negated: the same field with the negative overpunch.
            final String negativeFive = zonedField("0000000050", 0, true);

            assertThat(encodedWidth(positiveUnit)).isEqualTo(NINE_DIGIT_ZONED_BYTES);
            assertThat(encodedWidth(negativeFive)).isEqualTo(NINE_DIGIT_ZONED_BYTES);
            assertThat(positiveUnit).endsWith("{");
            assertThat(negativeFive).endsWith("}");
            assertThat(NINE_DIGIT_ZONED_BYTES)
                    .isEqualTo(TestDataFactory.TRANSACTION_AMOUNT_WIDTH);
            assertThat(TEN_DIGIT_ZONED_BYTES).isEqualTo(TestDataFactory.ACCOUNT_AMOUNT_WIDTH);
            assertThat(FOUR_DIGIT_ZONED_BYTES).isEqualTo(TestDataFactory.INTEREST_RATE_WIDTH);
        }
    }

    @Nested
    @DisplayName("the date-parameter image: twenty-one significant bytes inside eighty columns")
    class TheDateParameterImage {

        @Test
        @DisplayName("the significant prefix is ten bytes, one space at position eleven, then ten")
        void theSignificantPrefixIsTwentyOneBytes() {
            final String structured = structuredParameterImage();

            assertThat(encodedWidth(structured)).isEqualTo(PARAMETER_SIGNIFICANT_BYTES);
            assertThat(encodedWidth(START)).isEqualTo(DATE_BOUND_BYTES);
            assertThat(encodedWidth(END)).isEqualTo(DATE_BOUND_BYTES);
            assertThat(structured).startsWith(START).endsWith(END);
            // The separator is a single space at one-based position eleven, expressed as the slice
            // that begins where the ten-character start date ends. It is the only space in the prefix.
            assertThat(structured.substring(DATE_BOUND_BYTES, PARAMETER_SEPARATOR_POSITION))
                    .isEqualTo(String.valueOf(SPACE));
            assertThat(structured.charAt(ReportLineFormatter.DATE_PARAMETER_SEPARATOR_OFFSET))
                    .isEqualTo(SPACE);
            assertThat(structured.chars().filter(character -> character == SPACE).count())
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("the eighty-column envelope is measured separately from its significant prefix")
        void theCardEnvelopeIsEightyBytes() {
            final String card = parameterCardImage();

            assertThat(encodedWidth(card)).isEqualTo(PARAMETER_CARD_BYTES);
            assertThat(card).startsWith(structuredParameterImage());
            // The envelope is not the report record: the two widths are different contracts and are
            // asserted against one another so they cannot be conflated.
            assertThat(PARAMETER_CARD_BYTES).isNotEqualTo(REPORT_RECORD_BYTES);
        }

        @Test
        @DisplayName("both legal widths of the image produce byte-identical reports")
        void bothLegalWidthsAgree() {
            stubResolvableLookups();
            supply(oneUnitEach(3, CARD_A));

            final ProcessedReport fromStructured = process(structuredItem());
            final ProcessedReport fromCardImage = process(cardImageItem());

            assertThat(fromCardImage.records()).isEqualTo(fromStructured.records());
            assertThat(fromCardImage.lineCount()).isEqualTo(fromStructured.lineCount());
        }

        @Test
        @DisplayName("an image of neither legal width fails before any generation is attempted")
        void anIllegalWidthFailsBeforeGeneration() {
            final TransactionReportService untouched = mock(TransactionReportService.class);
            final TransactionReportProcessor guarded =
                    new TransactionReportProcessor(untouched, registry);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> guarded.process(item(START + fill(SPACE, 1) + "2022-07-3")));

            verifyNoInteractions(untouched);
        }

        @Test
        @DisplayName("the bounds reach the report-name header exactly as the image carried them")
        void theBoundsReachTheHeaderUnaltered() {
            stubResolvableLookups();
            supply(oneUnitEach(1, CARD_A));

            final ProcessedReport result = reportOfFixture();

            assertThat(result.records().get(0)).isEqualTo(expectedNameHeader(START, END));
            assertThat(result.records().get(0)).contains(START).contains(END);
        }
    }

    @Nested
    @DisplayName("the line counter and the page size are plain integers, never monetary values")
    class TheCounterFamily {

        @Test
        @DisplayName("the counter and the page size are integral, unscaled and never codec-routed")
        void theCounterAndThePageSizeAreIntegral() {
            stubResolvableLookups();
            supply(oneUnitEach(1, CARD_A));

            final ProcessedReport result = reportOfFixture();

            // These two assignments are the type proof, and they are made by the compiler rather than
            // by reflection: neither value would compile into an integral local if it were a decimal.
            final long counter = result.lineCount();
            final int pageSize = ReportLineFormatter.PAGE_SIZE;

            assertThat(counter).isEqualTo(7L);
            assertThat(pageSize).isEqualTo(PAGE_SIZE_LINES);
            // An integral rendering carries no decimal point and no scale of any kind, which is what
            // separates the counter family from the monetary family below.
            assertThat(Long.toString(counter)).doesNotContain(".").containsOnlyDigits();
            assertThat(Integer.toString(pageSize)).doesNotContain(".").containsOnlyDigits();
        }

        @Test
        @DisplayName("the monetary family is a scaled decimal, so the two families cannot be confused")
        void theMonetaryFamilyIsScaledAndTheCounterFamilyIsNot() {
            stubResolvableLookups();
            supply(List.of(transaction(identifier(1), CARD_A, "12.34", INSIDE)));

            final ProcessedReport result = reportOfFixture();

            // Every monetary field of this program is a nine-integer-digit two-decimal zoned value, so
            // the grand total carries a scale. The counter carries none. Conflating the two would be a
            // real defect, which is why both are asserted in one place.
            assertThat(result.grandTotal().scale()).isEqualTo(2);
            assertThat(result.lineCount()).isEqualTo(7L);
            assertThat(result.pageCount()).isEqualTo(1);
            assertThat(result.accountBreakCount()).isZero();
        }

        @Test
        @DisplayName("the page-total block advances the counter by two and the grand total by none")
        void thePageTotalBlockAddsTwoAndTheGrandTotalBlockAddsNone() {
            stubResolvableLookups();
            supply(List.of());

            final ProcessedReport result = reportOfFixture();

            // A range no record falls into never reaches the report driver, so the header block never
            // runs and the only blocks that do are the two the end of input flushes. Three records are
            // written and the counter reads two: the page-total block accounts for both increments and
            // the grand-total block writes its record and increments nothing.
            assertThat(result.records()).hasSize(3);
            assertThat(result.records().get(0)).isEqualTo(expectedPageTotalLine(blankMask()));
            assertThat(result.records().get(1)).isEqualTo(expectedRuleLine());
            assertThat(result.records().get(2)).isEqualTo(expectedGrandTotalLine(blankMask()));
            assertThat(result.lineCount()).isEqualTo((long) PAGE_TOTAL_BLOCK_INCREMENT);
            assertThat(GRAND_TOTAL_BLOCK_INCREMENT).isZero();
        }

        @Test
        @DisplayName("a detail row advances the counter by one, so the header block advances it by four")
        void aDetailRowAddsOneAndTheHeaderBlockAddsFour() {
            stubResolvableLookups();
            supply(oneUnitEach(1, CARD_A));
            final long withOneDetail = reportOfFixture().lineCount();

            supply(oneUnitEach(2, CARD_A));
            final long withTwoDetails = reportOfFixture().lineCount();

            // One further detail row, one further increment.
            assertThat(withOneDetail).isEqualTo(7L);
            assertThat(withTwoDetails).isEqualTo(8L);
            assertThat(DETAIL_ROW_INCREMENT).isOne();

            // The one-detail run is the empty run plus a header block plus a detail row, so the header
            // block's contribution is what remains once the page-total block's two and the detail row's
            // one are accounted for. Stated additively: two plus four plus one is seven.
            long accumulated = 0L;
            accumulated = accumulated + PAGE_TOTAL_BLOCK_INCREMENT;
            accumulated = accumulated + HEADER_BLOCK_RECORDS;
            accumulated = accumulated + DETAIL_ROW_INCREMENT;
            assertThat(accumulated).isEqualTo(withOneDetail);
            assertThat(HEADER_BLOCK_RECORDS).isEqualTo(4);
        }

        @Test
        @DisplayName("the account-total block advances the counter by two")
        void theAccountTotalBlockAddsTwo() {
            stubResolvableLookups();
            supply(oneUnitEach(2, CARD_A));
            final long withoutCardChange = reportOfFixture().lineCount();

            supply(oneUnitEachSwitchingCardAt(2, 2));
            final ProcessedReport withCardChange = reportOfFixture();

            // The two runs carry the same number of detail rows and the same header block; they differ
            // by exactly one account-total block. Stated additively rather than by a difference.
            long accumulated = withoutCardChange;
            accumulated = accumulated + ACCOUNT_TOTAL_BLOCK_INCREMENT;

            assertThat(withoutCardChange).isEqualTo(8L);
            assertThat(withCardChange.lineCount()).isEqualTo(accumulated);
            assertThat(withCardChange.lineCount()).isEqualTo(10L);
            assertThat(withCardChange.accountBreakCount()).isEqualTo(1);
            assertThat(withCardChange.records()).hasSize(11);
        }

        @Test
        @DisplayName("the counter counts every written record except the grand total's")
        void theCounterCountsEveryWrittenRecordButTheGrandTotal() {
            stubResolvableLookups();
            supply(oneUnitEachSwitchingCardAt(6, 4));

            final ProcessedReport result = reportOfFixture();

            // Every write site increments except the grand total's, so the record count runs exactly
            // one ahead of the counter for any report that reached its grand total. Expressed as an
            // addition on the counter, never as a difference of the two.
            long accumulated = result.lineCount();
            accumulated = accumulated + 1L;
            assertThat((long) result.records().size()).isEqualTo(accumulated);
        }
    }

    @Nested
    @DisplayName("pagination: sixteen detail rows on the first page, fourteen on every later page")
    class Pagination {

        @Test
        @DisplayName("a first page that exactly fills does not break, so one header block is emitted")
        void aFullFirstPageDoesNotBreak() {
            stubResolvableLookups();
            supply(oneUnitEach(DETAILS_ON_FIRST_PAGE, CARD_A));

            final ProcessedReport result = reportOfFixture();

            assertThat(detailsOf(result)).hasSize(DETAILS_ON_FIRST_PAGE);
            assertThat(labelled(result, "DALYREPT")).hasSize(1);
            // The single page total is the end-of-input flush, not a break.
            assertThat(result.pageCount()).isEqualTo(1);
            assertThat(labelled(result, "Page Total")).hasSize(1);
        }

        @Test
        @DisplayName("the first page carries exactly sixteen detail rows")
        void theFirstPageCarriesSixteenDetailRows() {
            stubResolvableLookups();
            supply(oneUnitEach(30, CARD_A));

            final ProcessedReport result = reportOfFixture();

            // The header block advances the counter to four before the first record is tested, so the
            // start-of-record counter for record n is n plus three and the break lands on record
            // seventeen. Records one through sixteen therefore share the first page.
            assertThat(detailsBefore(result, "Page Total")).isEqualTo(DETAILS_ON_FIRST_PAGE);
            assertThat(DETAILS_ON_FIRST_PAGE).isEqualTo(16);
        }

        @Test
        @DisplayName("one detail past the boundary breaks the page and re-emits the header block")
        void oneDetailPastTheBoundaryBreaksThePage() {
            stubResolvableLookups();
            supply(oneUnitEach(17, CARD_A));

            final ProcessedReport result = reportOfFixture();
            final List<String> records = result.records();

            // Two page totals: the break and the end-of-input flush. Two header blocks: the opening one
            // and the one the break re-emits.
            assertThat(result.pageCount()).isEqualTo(2);
            assertThat(labelled(result, "DALYREPT")).hasSize(2);

            // At the break the emitted page total excludes the breaking record's own amount, because the
            // page test runs before the accumulation. Sixteen units, not seventeen.
            final String breakingPageTotal = labelled(result, "Page Total").get(0);
            assertThat(breakingPageTotal)
                    .isEqualTo(expectedPageTotalLine(totalMask("16.00")));

            // The breaking record's detail row lands after the fresh header block, on the new page.
            final int breakIndex = records.indexOf(breakingPageTotal);
            assertThat(records.get(breakIndex)).startsWith("Page Total");
            assertThat(records.get(breakIndex + 1)).isEqualTo(expectedRuleLine());
            assertThat(records.get(breakIndex + 2)).isEqualTo(expectedNameHeader(START, END));
            assertThat(records.get(breakIndex + 3)).isEqualTo(expectedBlankLine());
            assertThat(records.get(breakIndex + 4)).isEqualTo(expectedColumnHeader());
            assertThat(records.get(breakIndex + 5)).isEqualTo(expectedRuleLine());
            assertThat(records.get(breakIndex + 6))
                    .isEqualTo(expectedCardADetail(identifier(17), detailMask(ONE_UNIT)));
        }

        @Test
        @DisplayName("every page after the first carries exactly fourteen detail rows")
        void everyLaterPageCarriesFourteenDetailRows() {
            stubResolvableLookups();
            // Long enough to cross two page boundaries: the break at record seventeen and the break at
            // record thirty-one, leaving a third page that fills exactly.
            supply(oneUnitEach(44, CARD_A));

            final ProcessedReport result = reportOfFixture();

            assertThat(detailsOf(result)).hasSize(44);
            assertThat(result.pageCount()).isEqualTo(3);
            assertThat(labelled(result, "DALYREPT")).hasSize(3);
            assertThat(detailsPerPage(result)).containsExactly(DETAILS_ON_FIRST_PAGE,
                    DETAILS_PER_STEADY_PAGE, DETAILS_PER_STEADY_PAGE);
            assertThat(DETAILS_PER_STEADY_PAGE).isEqualTo(14);
        }

        @Test
        @DisplayName("a page break re-emits all four header records in their one legal order")
        void aPageBreakReEmitsTheWholeHeaderBlock() {
            stubResolvableLookups();
            supply(oneUnitEach(20, CARD_A));

            final ProcessedReport result = reportOfFixture();
            final List<String> records = result.records();
            final int reopened = records.indexOf(expectedRuleLine());

            assertThat(records.subList(0, HEADER_BLOCK_RECORDS))
                    .isEqualTo(expectedHeaderBlock(START, END));
            assertThat(reopened).isEqualTo(3);
            assertThat(labelled(result, "DALYREPT")).hasSize(2);
            for (final String header : labelled(result, "DALYREPT")) {
                assertThat(header).isEqualTo(expectedNameHeader(START, END));
            }
        }

        @Test
        @DisplayName("the missed page break is faithful legacy behaviour: a card change at counter "
                + "nineteen steps over the multiple of twenty and the page runs long")
        void theMissedPageBreakIsReproducedRatherThanFixed() {
            stubResolvableLookups();

            // Control run: thirty-five records on one card, so no account-total block interferes with
            // the counter and the break lands where the modulo test can see it - on record seventeen.
            supply(oneUnitEach(35, CARD_A));
            final ProcessedReport unbroken = reportOfFixture();
            assertThat(detailsBefore(unbroken, "Page Total")).isEqualTo(DETAILS_ON_FIRST_PAGE);

            // The same thirty-five records, with the card changing on record sixteen. The
            // start-of-record counter for record sixteen is nineteen; the account-total block runs in
            // the mainline, before the report driver, and carries the counter to twenty-one. The modulo
            // test then evaluates twenty-one, which is not a multiple of twenty, so the break that was
            // due is MISSED - the multiple was stepped over and never observed.
            supply(oneUnitEachSwitchingCardAt(35, 16));
            final ProcessedReport missed = reportOfFixture();

            assertThat(missed.accountBreakCount()).isEqualTo(1);
            // The page runs long: thirty-four detail rows reach the first page total instead of
            // sixteen, because the first break slips all the way from record seventeen to record
            // thirty-five.
            assertThat(detailsBefore(missed, "Page Total")).isEqualTo(34);
            assertThat(detailsBefore(missed, "Page Total"))
                    .isGreaterThan(detailsBefore(unbroken, "Page Total"));
            // One opening header block and one at the very late break, where an observed break would
            // have produced three header blocks over thirty-five records.
            assertThat(labelled(missed, "DALYREPT")).hasSize(2);
            assertThat(labelled(unbroken, "DALYREPT")).hasSize(3);
        }

        @Test
        @DisplayName("an account-total block that does not step over the multiple only moves the "
                + "break earlier, which is what makes the missed break a genuine anomaly")
        void anAccountBlockThatDoesNotStepOverTheMultipleMerelyMovesTheBreak() {
            stubResolvableLookups();

            // A card change early in the page also advances the counter by two, but it does not skip
            // the multiple: the counter still lands on it, two records sooner than it otherwise would.
            // The page runs SHORT by two rows and the break still happens.
            supply(oneUnitEachSwitchingCardAt(20, 5));
            final ProcessedReport landsOnTheMultiple = reportOfFixture();

            // A card change while the counter stands at nineteen carries it to twenty-one, so no record
            // of the run ever observes the multiple and the break does not happen at all before the end
            // of input.
            supply(oneUnitEachSwitchingCardAt(20, 16));
            final ProcessedReport stepsOverTheMultiple = reportOfFixture();

            assertThat(landsOnTheMultiple.accountBreakCount()).isEqualTo(1);
            assertThat(stepsOverTheMultiple.accountBreakCount()).isEqualTo(1);

            // One account-total block each, one page size, the same twenty records - and two different
            // outcomes, decided entirely by where the block's invisible increment landed.
            assertThat(landsOnTheMultiple.pageCount()).isEqualTo(2);
            assertThat(detailsBefore(landsOnTheMultiple, "Page Total")).isEqualTo(14);
            assertThat(stepsOverTheMultiple.pageCount()).isEqualTo(1);
            assertThat(detailsBefore(stepsOverTheMultiple, "Page Total")).isEqualTo(20);
        }
    }

    @Nested
    @DisplayName("the accumulation chain: amounts reach the grand total only through page totals")
    class TheAccumulationChain {

        @Test
        @DisplayName("each amount is added to both the page total and the account total, and to "
                + "nothing else, before its own detail row is written")
        void eachAmountReachesBothRunningTotalsBeforeItsRow() {
            stubResolvableLookups();
            // Three records on the first card, then one on the second, so the account-total block runs
            // once and its amount states exactly what the account total had accumulated.
            supply(oneUnitEachSwitchingCardAt(4, 4));

            final ProcessedReport result = reportOfFixture();
            final List<String> records = result.records();

            // The account total emitted at the change carries all three of the first card's amounts and
            // none of the arriving record's, which proves each amount was accumulated before its row was
            // written and that the block runs before the arriving record is accumulated.
            final BigDecimal firstCardDetails = accumulated(repeatedAmount(ONE_UNIT, 3));
            assertThat(records.get(7))
                    .isEqualTo(expectedAccountTotalLine(totalMask("3.00")));
            assertThat(firstCardDetails).isEqualByComparingTo(new BigDecimal("3.00"));

            // The page total is fed by every amount of the run regardless of card, plus the stale
            // re-add at the end of input: three, then one, then the stale one.
            final BigDecimal pageContributions = accumulated(repeatedAmount(ONE_UNIT, 5));
            assertThat(labelled(result, "Page Total"))
                    .containsExactly(expectedPageTotalLine(totalMask("5.00")));
            assertThat(pageContributions).isEqualByComparingTo(new BigDecimal("5.00"));
            assertThat(result.grandTotal()).isEqualByComparingTo(pageContributions);
        }

        @Test
        @DisplayName("the grand total equals the sum of the page totals and not the sum of details")
        void theGrandTotalIsFedOnlyFromPageTotals() {
            stubResolvableLookups();
            supply(oneUnitEachSwitchingCardAt(20, 11));

            final ProcessedReport result = reportOfFixture();

            // Two page totals: the break, then the end-of-input flush. Accumulate the two the way the
            // program folds them - by repeated addition and nothing else.
            final List<String> pageTotals = labelled(result, "Page Total");
            assertThat(pageTotals).containsExactly(expectedPageTotalLine(totalMask("14.00")),
                    expectedPageTotalLine(totalMask("7.00")));
            final BigDecimal foldedFromPages =
                    accumulated(List.of("14.00", "7.00"));

            assertThat(result.grandTotal()).isEqualByComparingTo(foldedFromPages);
            assertThat(labelled(result, "Grand Total"))
                    .containsExactly(expectedGrandTotalLine(totalMask("21.00")));

            // A grand total fed straight from the detail rows would read twenty, and one that also
            // folded in the account total would read thirty-one. Both are stated so the assertion can
            // fail for the right reason.
            assertThat(result.grandTotal())
                    .isNotEqualByComparingTo(accumulated(repeatedAmount(ONE_UNIT, 20)));
            assertThat(result.grandTotal()).isNotEqualByComparingTo(new BigDecimal("31.00"));
        }

        @Test
        @DisplayName("an account-total block leaves the grand total exactly where it found it")
        void anAccountTotalBlockLeavesTheGrandTotalUnchanged() {
            stubResolvableLookups();

            // One run with a card change and one without, over the same number of records and the same
            // amounts. The account-total block is the only difference, and the grand total is identical.
            supply(oneUnitEach(6, CARD_A));
            final ProcessedReport withoutBreak = reportOfFixture();

            supply(oneUnitEachSwitchingCardAt(6, 4));
            final ProcessedReport withBreak = reportOfFixture();

            assertThat(withoutBreak.accountBreakCount()).isZero();
            assertThat(withBreak.accountBreakCount()).isEqualTo(1);
            assertThat(withBreak.grandTotal()).isEqualByComparingTo(withoutBreak.grandTotal());
            assertThat(withBreak.grandTotal()).isEqualByComparingTo(new BigDecimal("7.00"));

            // The account total that was emitted is a real, non-zero figure, so the assertion above is
            // not passing because the block accumulated nothing.
            assertThat(labelled(withBreak, "Account Total"))
                    .containsExactly(expectedAccountTotalLine(totalMask("3.00")));
        }

        @Test
        @DisplayName("the first card never emits a leading account total for an account with nothing")
        void theFirstCardEmitsNoLeadingAccountTotal() {
            stubResolvableLookups();
            supply(oneUnitEach(3, CARD_A));

            final ProcessedReport result = reportOfFixture();

            assertThat(result.accountBreakCount()).isZero();
            assertThat(labelled(result, "Account Total")).isEmpty();
        }

        @Test
        @DisplayName("the end of input counts the last record's amount TWICE, faithfully")
        void theEndOfInputDoubleCountsTheLastAmount() {
            stubResolvableLookups();
            supply(oneUnitEach(2, CARD_A));

            final ProcessedReport result = reportOfFixture();

            // Two detail rows of one unit each sum to two units; the page total reads three, because the
            // end-of-file arm re-adds the stale last record's amount before flushing.
            final BigDecimal detailRows = accumulated(repeatedAmount(ONE_UNIT, 2));
            final BigDecimal withTheStaleReAdd = accumulated(repeatedAmount(ONE_UNIT, 3));

            assertThat(detailRows).isEqualByComparingTo(new BigDecimal("2.00"));
            assertThat(labelled(result, "Page Total"))
                    .containsExactly(expectedPageTotalLine(totalMask("3.00")));
            assertThat(result.grandTotal()).isEqualByComparingTo(withTheStaleReAdd);
            // The un-doubled figure is stated so this assertion fails if the duplicate is ever
            // reconciled away.
            assertThat(result.grandTotal()).isNotEqualByComparingTo(detailRows);
            assertThat(labelled(result, "Grand Total"))
                    .containsExactly(expectedGrandTotalLine(totalMask("3.00")));
        }

        @Test
        @DisplayName("no account-total block is written at the end of input, so the final account's "
                + "total line never appears")
        void noAccountTotalBlockIsWrittenAtTheEndOfInput() {
            stubResolvableLookups();
            // Two records on the first card, three on the second. One card change, so exactly one
            // account-total block - and it belongs to the FIRST card.
            supply(oneUnitEachSwitchingCardAt(5, 3));

            final ProcessedReport result = reportOfFixture();
            final List<String> records = result.records();

            assertThat(result.accountBreakCount()).isEqualTo(1);
            assertThat(labelled(result, "Account Total"))
                    .containsExactly(expectedAccountTotalLine(totalMask("2.00")));

            // The second card accumulated three units and, with the stale re-add, four - and neither
            // figure ever reaches an account-total line, because none is written.
            assertThat(records).doesNotContain(expectedAccountTotalLine(totalMask("3.00")));
            assertThat(records).doesNotContain(expectedAccountTotalLine(totalMask("4.00")));

            // The report ends with the page-total block and then the grand total, with no account total
            // among them.
            assertThat(records).endsWith(expectedPageTotalLine(totalMask("6.00")),
                    expectedRuleLine(), expectedGrandTotalLine(totalMask("6.00")));
        }

        @Test
        @DisplayName("no derived figure appears: every total is the sum of the amounts actually added")
        void everyTotalIsAPlainSumOfTheAmountsActuallyAdded() {
            stubResolvableLookups();
            supply(List.of(transaction(identifier(1), CARD_A, "12.34", INSIDE),
                    transaction(identifier(2), CARD_A, "0.66", INSIDE)));

            final ProcessedReport result = reportOfFixture();

            // Twelve point three four, then zero point six six, then the stale re-add of zero point six
            // six: accumulated by repeated addition, with no average, percentage or recomputation from
            // the rows anywhere in sight.
            final BigDecimal expected = accumulated(List.of("12.34", "0.66", "0.66"));

            assertThat(expected).isEqualByComparingTo(new BigDecimal("13.66"));
            assertThat(result.grandTotal()).isEqualByComparingTo(expected);
            assertThat(result.grandTotal().scale()).isEqualTo(2);
            assertThat(labelled(result, "Page Total"))
                    .containsExactly(expectedPageTotalLine(totalMask("13.66")));
            // The sub-unit amount renders with its leading zero SUPPRESSED, so its hand-built magnitude
            // is a bare fractional part. That is the mask's own contract and it is stated here as a
            // literal rather than reconstructed.
            assertThat(detailsOf(result)).containsExactly(
                    expectedCardADetail(identifier(1), detailMask("12.34")),
                    expectedCardADetail(identifier(2), detailMask(".66")));
        }

        @Test
        @DisplayName("the end of input flushes the final page total and then the grand total")
        void theEndOfInputFlushesTheFinalTotalsInOrder() {
            stubResolvableLookups();
            supply(oneUnitEach(2, CARD_A));

            final List<String> records = reportOfFixture().records();

            assertThat(records).endsWith(expectedPageTotalLine(totalMask("3.00")),
                    expectedRuleLine(), expectedGrandTotalLine(totalMask("3.00")));
        }
    }

    @Nested
    @DisplayName("the reporting window: inclusive at both bounds, compared as characters")
    class TheReportingWindow {

        @Test
        @DisplayName("a record dated exactly on the start bound is included")
        void theStartBoundIsInclusive() {
            stubResolvableLookups();
            supply(List.of(transaction(identifier(1), CARD_A, ONE_UNIT, START)));

            final ProcessedReport result = reportOfFixture();

            assertThat(detailsOf(result))
                    .containsExactly(expectedCardADetail(identifier(1), detailMask(ONE_UNIT)));
        }

        @Test
        @DisplayName("a record dated exactly on the end bound is included")
        void theEndBoundIsInclusive() {
            stubResolvableLookups();
            supply(List.of(transaction(identifier(2), CARD_A, ONE_UNIT, END)));

            final ProcessedReport result = reportOfFixture();

            assertThat(detailsOf(result))
                    .containsExactly(expectedCardADetail(identifier(2), detailMask(ONE_UNIT)));
        }

        @Test
        @DisplayName("both bounds and an interior date are reported together, in the supplied order")
        void bothBoundsAndTheInteriorAreReported() {
            stubResolvableLookups();
            supply(List.of(transaction(identifier(1), CARD_A, ONE_UNIT, START),
                    transaction(identifier(2), CARD_A, ONE_UNIT, "2022-07-15"),
                    transaction(identifier(3), CARD_A, ONE_UNIT, END)));

            final ProcessedReport result = reportOfFixture();

            assertThat(detailsOf(result)).containsExactly(
                    expectedCardADetail(identifier(1), detailMask(ONE_UNIT)),
                    expectedCardADetail(identifier(2), detailMask(ONE_UNIT)),
                    expectedCardADetail(identifier(3), detailMask(ONE_UNIT)));
        }

        @Test
        @DisplayName("a record above the end bound is not reported, exactly as implemented")
        void aRecordAboveTheEndBoundIsNotReported() {
            stubResolvableLookups();
            // The out-of-window record is the LAST of the fixture, deliberately: this suite asserts
            // only that such a record is not reported, and never that the run stops at it. In the
            // legacy the non-matching arm is a control transfer that leaves the whole read loop, and
            // that arm is unreachable in the target because the job's sort pre-filters the identical
            // window - so encoding loop termination as an expectation would be encoding an unreachable
            // arm. See the class documentation.
            supply(List.of(transaction(identifier(1), CARD_A, ONE_UNIT, "2022-07-15"),
                    transaction(identifier(9), CARD_A, ONE_UNIT, "2022-08-01")));

            final ProcessedReport result = reportOfFixture();

            assertThat(detailsOf(result))
                    .containsExactly(expectedCardADetail(identifier(1), detailMask(ONE_UNIT)));
            assertThat(result.records()).noneMatch(record -> record.startsWith(identifier(9)));
        }

        @Test
        @DisplayName("a record below the start bound is not reported, exactly as implemented")
        void aRecordBelowTheStartBoundIsNotReported() {
            stubResolvableLookups();
            supply(List.of(transaction(identifier(8), CARD_A, ONE_UNIT, "2022-06-30")));

            final ProcessedReport result = reportOfFixture();

            assertThat(detailsOf(result)).isEmpty();
            assertThat(result.records()).noneMatch(record -> record.startsWith(identifier(8)));
        }

        @Test
        @DisplayName("the comparison is textual, not temporal: a bound no calendar accepts still "
                + "selects records, because only the first ten characters are compared")
        void theComparisonIsTextualRatherThanTemporal() {
            stubResolvableLookups();

            // An operator can key a lexically well-formed but calendar-impossible bound into the
            // eighty-column card, and the program's character comparison accepts it without complaint.
            final String calendarImpossibleEnd = "2022-02-31";
            final String calendarImpossibleRecord = "2022-02-30";
            final String windowStart = "2022-02-01";

            supply(List.of(transaction(identifier(1), CARD_A, ONE_UNIT, calendarImpossibleRecord)));
            final ProcessedReport result =
                    process(item(windowStart + fill(SPACE, 1) + calendarImpossibleEnd));

            // The character comparison includes the record: its first ten characters sort at or above
            // the lower bound and below the upper one.
            assertThat(detailsOf(result))
                    .containsExactly(expectedCardADetail(identifier(1), detailMask(ONE_UNIT)));
            assertThat(result.records().get(0))
                    .isEqualTo(expectedNameHeader(windowStart, calendarImpossibleEnd));

            // A parsed-date comparison would have decided this differently: with strict resolution it
            // cannot even construct the bound or the record, so both would have been rejected and the
            // report would have been empty. That is the divergence, stated as an executable fact.
            final DateTimeFormatter strict = DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT)
                    .withResolverStyle(ResolverStyle.STRICT);
            assertThatExceptionOfType(DateTimeParseException.class)
                    .isThrownBy(() -> LocalDate.parse(calendarImpossibleEnd, strict));
            assertThatExceptionOfType(DateTimeParseException.class)
                    .isThrownBy(() -> LocalDate.parse(calendarImpossibleRecord, strict));
            // The ordinary bounds do parse, so the divergence above is specific to the impossible
            // values rather than to the formatter being wrong.
            assertThat(LocalDate.parse(START, strict)).isEqualTo(LocalDate.of(2022, 7, 1));
            assertThat(LocalDate.parse(END, strict)).isEqualTo(LocalDate.of(2022, 7, 31));
        }

        @Test
        @DisplayName("only the first ten characters decide: the time of day is never consulted")
        void onlyTheFirstTenCharactersDecide() {
            stubResolvableLookups();
            // Two records on the end bound whose time-of-day tails differ. A comparison that read past
            // the tenth character would order or exclude them differently; both are included.
            final String endOfDay = END + "-23.59.59.999999";
            final String startOfDay = END + TIMESTAMP_TAIL;
            supply(List.of(
                    new Transaction(identifier(1), TYPE_CODE, CATEGORY_CODE, SOURCE, DESCRIPTION,
                            new BigDecimal(ONE_UNIT), MERCHANT_ID, MERCHANT_NAME, MERCHANT_CITY,
                            MERCHANT_ZIP, CARD_A, endOfDay, endOfDay),
                    new Transaction(identifier(2), TYPE_CODE, CATEGORY_CODE, SOURCE, DESCRIPTION,
                            new BigDecimal(ONE_UNIT), MERCHANT_ID, MERCHANT_NAME, MERCHANT_CITY,
                            MERCHANT_ZIP, CARD_A, startOfDay, startOfDay)));

            final ProcessedReport result = reportOfFixture();

            assertThat(detailsOf(result)).containsExactly(
                    expectedCardADetail(identifier(1), detailMask(ONE_UNIT)),
                    expectedCardADetail(identifier(2), detailMask(ONE_UNIT)));
            assertThat(encodedWidth(endOfDay)).isEqualTo(TestDataFactory.TIMESTAMP_TEXT_WIDTH);
        }
    }

    @Nested
    @DisplayName("the terminal path: a reference-lookup miss abends, and nothing is skipped")
    class TheTerminalPath {

        @Test
        @DisplayName("an unresolved cross-reference emits the status and then abends, in that order")
        void anUnresolvedCrossReferenceAbends() {
            when(crossReferences.findById(CARD_A)).thenReturn(Optional.empty());
            supply(oneUnitEach(1, CARD_A));

            final ReportTransactionInput candidate = structuredItem();
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> processor.process(candidate));

            final InOrder ordered = inOrder(abendService);
            ordered.verify(abendService)
                    .displayIoStatus(RECORD_NOT_FOUND_STATUS, READ_OPERATION, DD_CARDXREF);
            ordered.verify(abendService).abendBatch(TransactionReportProcessor.LEGACY_PROGRAM,
                    DIAG_INVALID_CARDXREF, RECORD_NOT_FOUND_STATUS, READ_OPERATION, DD_CARDXREF);

            // The record that could not be resolved never reaches the report, and no substitute account
            // identifier is invented for it.
            assertThat(emitted).noneMatch(record -> record.startsWith(identifier(1)));
            // The lookups that follow the cross-reference in the MAINLINE are never reached, which is
            // what "nothing is skipped" means: the run stops at the first miss rather than resolving the
            // rest of the record and reporting it incomplete.
            //
            // Asserted as an absence of KEYED reads rather than as an absence of all interaction. The two
            // closed-vocabulary reference clusters are read in full when their own resource is opened,
            // and the open sequence precedes the driving loop in the legacy member exactly as it does
            // here, so a run that abends on its first record has already opened those files. What must
            // not happen - and does not - is a mainline resolution of this record's type or category.
            verify(types, never()).findById(any());
            verify(categories, never()).findById(any());
        }

        @Test
        @DisplayName("an unresolved transaction type emits the status and then abends, in that order")
        void anUnresolvedTransactionTypeAbends() {
            when(crossReferences.findById(CARD_A))
                    .thenReturn(Optional.of(new CardCrossReference(CARD_A, CUSTOMER_A, ACCOUNT_A)));
            when(types.findById(TYPE_CODE)).thenReturn(Optional.empty());
            supply(oneUnitEach(1, CARD_A));

            final ReportTransactionInput candidate = structuredItem();
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> processor.process(candidate));

            final InOrder ordered = inOrder(abendService);
            ordered.verify(abendService)
                    .displayIoStatus(RECORD_NOT_FOUND_STATUS, READ_OPERATION, DD_TRANTYPE);
            ordered.verify(abendService).abendBatch(TransactionReportProcessor.LEGACY_PROGRAM,
                    DIAG_INVALID_TRANTYPE, RECORD_NOT_FOUND_STATUS, READ_OPERATION, DD_TRANTYPE);

            assertThat(emitted).noneMatch(record -> record.startsWith(identifier(1)));
            // The category lookup follows the type lookup in the mainline and is never reached. Stated
            // as an absence of keyed reads for the reason given above.
            verify(categories, never()).findById(any());
        }

        @Test
        @DisplayName("an unresolved transaction category emits the status and then abends, in order")
        void anUnresolvedTransactionCategoryAbends() {
            when(crossReferences.findById(CARD_A))
                    .thenReturn(Optional.of(new CardCrossReference(CARD_A, CUSTOMER_A, ACCOUNT_A)));
            when(types.findById(TYPE_CODE))
                    .thenReturn(Optional.of(new TransactionType(TYPE_CODE, TYPE_DESCRIPTION)));
            when(categories.findById(any(TransactionCategoryId.class)))
                    .thenReturn(Optional.empty());
            supply(oneUnitEach(1, CARD_A));

            final ReportTransactionInput candidate = structuredItem();
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> processor.process(candidate));

            final InOrder ordered = inOrder(abendService);
            ordered.verify(abendService)
                    .displayIoStatus(RECORD_NOT_FOUND_STATUS, READ_OPERATION, DD_TRANCATG);
            ordered.verify(abendService).abendBatch(TransactionReportProcessor.LEGACY_PROGRAM,
                    DIAG_INVALID_TRANCATG, RECORD_NOT_FOUND_STATUS, READ_OPERATION, DD_TRANCATG);

            // The composite key the lookup was given is the type code and the category code of the
            // record, assembled in that order.
            final ArgumentCaptor<TransactionCategoryId> key =
                    ArgumentCaptor.forClass(TransactionCategoryId.class);
            verify(categories).findById(key.capture());
            assertThat(key.getValue().getTranTypeCd()).isEqualTo(TYPE_CODE);
            assertThat(key.getValue().getTranCatCd()).isEqualTo(CATEGORY_CODE);
            assertThat(emitted).noneMatch(record -> record.startsWith(identifier(1)));
        }

        @Test
        @DisplayName("a lookup miss is terminal: nothing is skipped, retried or rejected to a side "
                + "artefact, and the records after it are never reported")
        void aLookupMissIsTerminal() {
            stubResolvableLookups();
            // The second record carries a different category code, so its lookup is not served from the
            // run's memo, and that lookup finds nothing.
            when(categories.findById(new TransactionCategoryId(TYPE_CODE, OTHER_CATEGORY_CODE)))
                    .thenReturn(Optional.empty());
            supply(List.of(transaction(identifier(1), CARD_A, ONE_UNIT, INSIDE),
                    transaction(identifier(2), CARD_A, ONE_UNIT, INSIDE, OTHER_CATEGORY_CODE),
                    transaction(identifier(3), CARD_A, ONE_UNIT, INSIDE)));

            final ReportTransactionInput candidate = structuredItem();
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> processor.process(candidate));

            // The first record was reported; the offending record was not skipped over, so the third
            // never appears either. There is no skip limit, no skip policy and no retry to observe.
            assertThat(emitted).anyMatch(record -> record.startsWith(identifier(1)));
            assertThat(emitted).noneMatch(record -> record.startsWith(identifier(2)));
            assertThat(emitted).noneMatch(record -> record.startsWith(identifier(3)));
            // Exactly one attempt at the failing key: a retry would show as a second call.
            verify(categories, times(1))
                    .findById(new TransactionCategoryId(TYPE_CODE, OTHER_CATEGORY_CODE));
            // No reject artefact of any width is produced: every record the sink saw is a report record.
            for (final String record : emitted) {
                assertThat(encodedWidth(record)).isEqualTo(REPORT_RECORD_BYTES);
                assertThat(encodedWidth(record)).isNotEqualTo(TestDataFactory.REJECT_RECORD_WIDTH);
            }
        }

        @Test
        @DisplayName("the abend carries the published batch code and the 134-byte legacy context")
        void theAbendCarriesThePublishedCodeAndContextWidth() {
            when(crossReferences.findById(CARD_A)).thenReturn(Optional.empty());
            supply(oneUnitEach(1, CARD_A));

            final ReportTransactionInput candidate = structuredItem();
            final AbendException raised = catchAbend(candidate);

            // The code is taken from the exception's own published constant and is never restated as a
            // literal here.
            assertThat(raised.code()).isEqualTo(AbendException.BATCH_ABEND_CODE);
            assertThat(raised.culprit()).isEqualTo(TransactionReportProcessor.LEGACY_PROGRAM);
            assertThat(raised.reason()).isEqualTo(DIAG_INVALID_CARDXREF);

            // The context mirrors the legacy four-part structure: a code, a culprit, a reason and an
            // operator message, whose widths accumulate to the whole. Stated by repeated addition.
            int contextWidth = 0;
            contextWidth = contextWidth + AbendException.CODE_LENGTH;
            contextWidth = contextWidth + AbendException.CULPRIT_LENGTH;
            contextWidth = contextWidth + AbendException.REASON_LENGTH;
            contextWidth = contextWidth + AbendException.MESSAGE_LENGTH;

            assertThat(AbendException.CODE_LENGTH).isEqualTo(4);
            assertThat(AbendException.CULPRIT_LENGTH).isEqualTo(8);
            assertThat(AbendException.REASON_LENGTH).isEqualTo(50);
            assertThat(AbendException.MESSAGE_LENGTH).isEqualTo(72);
            assertThat(contextWidth).isEqualTo(134);
            assertThat(contextWidth).isEqualTo(AbendException.CONTEXT_LENGTH);
            assertThat(encodedWidth(raised.toFixedWidthContext())).isEqualTo(contextWidth);
        }

        @Test
        @DisplayName("the status the lookups report belongs to the vocabulary the source compares")
        void theReportedStatusBelongsToTheComparedVocabulary() {
            // The statuses actually compared across the estate are success, end of file and record not
            // found. A lookup miss reports the third of them.
            assertThat(RECORD_NOT_FOUND_STATUS).isEqualTo(FileStatus.RECORD_NOT_FOUND.getCode());
            assertThat(RECORD_NOT_FOUND_STATUS).isIn(FileStatus.SUCCESS.getCode(),
                    FileStatus.END_OF_FILE.getCode(), FileStatus.RECORD_NOT_FOUND.getCode());
        }

        @Test
        @DisplayName("the stage diagnoses a mis-sized record before it raises, and never catches it")
        void theStageDiagnosesBeforeItRaisesAndNeverCatches() {
            // The report-write arm of the generator shares this diagnose-then-raise ordering but cannot
            // be entered through a real generator, because the layout authority builds every group at
            // the contracted width and checks it. The reachable equivalent is the stage's own
            // postcondition, which names the resource, the offending record's position and both widths
            // before it raises - and it wraps nothing, so the delegate's own terminal ordering is never
            // disturbed by an intervening handler.
            final TransactionReportProcessor narrow = stageYielding(
                    List.of(expectedRuleLine(), fill(SPACE, 131)));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> narrow.process(structuredItem()))
                    .withMessageContaining(TransactionReportProcessor.LEGACY_DD_TRANREPT)
                    .withMessageContaining("131")
                    .withMessageContaining(Integer.toString(REPORT_RECORD_BYTES));

            // The record that passed the proof reached the destination; the one that failed did not.
            assertThat(emitted).containsExactly(expectedRuleLine());
        }

        @Test
        @DisplayName("a failure raised by the generator propagates as itself, unwrapped")
        void aGeneratorFailurePropagatesUnwrapped() {
            final TransactionReportService failing = mock(TransactionReportService.class);
            final IllegalStateException raised = new IllegalStateException("range unavailable");
            when(failing.generateReportFromDateParameterCard(
                    any(ReportTransactionSource.class), any(), any())).thenThrow(raised);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new TransactionReportProcessor(failing, registry)
                            .process(structuredItem()))
                    .isSameAs(raised);
        }

        /**
         * Runs the stage over an item that is expected to abend and hands back the exception, so a test
         * can assert on the context the abend carries.
         *
         * @param candidate the item to process
         * @return the abend the run raised
         */
        private AbendException catchAbend(final ReportTransactionInput candidate) {
            try {
                processor.process(candidate);
            } catch (AbendException abend) {
                return abend;
            }
            throw new AssertionError("the run was expected to abend on an unresolved reference but"
                    + " completed instead");
        }
    }

    @Nested
    @DisplayName("sequencing: the header block's order and the card break's order")
    class Sequencing {

        @Test
        @DisplayName("the report opens with the four header records in their one legal order")
        void theHeaderBlockOpensTheReportInOrder() {
            stubResolvableLookups();
            supply(oneUnitEach(1, CARD_A));

            final ProcessedReport result = reportOfFixture();
            final List<String> block = result.records().subList(0, HEADER_BLOCK_RECORDS);

            // Report-name header, blank separator, column header, all-hyphen rule line - in that order
            // and no other, each at the contracted width.
            assertThat(block).isEqualTo(expectedHeaderBlock(START, END));
            assertThat(block).hasSize(4);
            assertThat(block.get(0)).startsWith("DALYREPT");
            assertThat(block.get(1)).isEqualTo(expectedBlankLine());
            assertThat(block.get(2)).startsWith("Transaction ID");
            assertThat(block.get(3)).isEqualTo(fill(HYPHEN, REPORT_RECORD_BYTES));
            for (final String record : block) {
                assertThat(encodedWidth(record)).isEqualTo(REPORT_RECORD_BYTES);
            }
        }

        @Test
        @DisplayName("the card break writes the prior account's total BEFORE it remembers the new "
                + "card and BEFORE it resolves the new card's cross-reference")
        void theCardBreakWritesTheAccountTotalFirst() {
            stubResolvableLookups();
            supply(oneUnitEachSwitchingCardAt(4, 4));

            final ProcessedReport result = reportOfFixture();
            final String priorAccountTotal = expectedAccountTotalLine(totalMask("3.00"));
            final String arrivingDetail = expectedDetailLine(identifier(4), ACCOUNT_B, TYPE_CODE,
                    TYPE_DESCRIPTION, CATEGORY_CODE, CATEGORY_DESCRIPTION, SOURCE,
                    detailMask(ONE_UNIT));

            // The interleaved trace records what the run did in the order it did it, so the relative
            // order of an emitted record and a repository call is directly observable.
            assertThat(trace).containsSubsequence(
                    TRACE_RECORD + priorAccountTotal,
                    TRACE_RECORD + expectedRuleLine(),
                    TRACE_LOOKUP_XREF + CARD_B,
                    TRACE_RECORD + arrivingDetail);

            // The total that was written belongs to the PRIOR card and carries none of the arriving
            // record's amount, which is what proves the block ran before the new card was remembered.
            assertThat(labelled(result, "Account Total")).containsExactly(priorAccountTotal);
            assertThat(result.records()).contains(arrivingDetail);
        }

        @Test
        @DisplayName("the three lookups run before the report driver, so even the header block "
                + "follows the first record's cross-reference resolution")
        void theLookupsPrecedeTheReportDriver() {
            stubResolvableLookups();
            supply(oneUnitEach(1, CARD_A));

            reportOfFixture();

            // The mainline resolves the cross-reference, the type and the category and only then
            // performs the report-writing paragraph, which is where the header block is emitted.
            assertThat(trace).containsSubsequence(
                    TRACE_LOOKUP_XREF + CARD_A,
                    TRACE_RECORD + expectedNameHeader(START, END),
                    TRACE_RECORD + expectedCardADetail(identifier(1), detailMask(ONE_UNIT)));
        }

        @Test
        @DisplayName("a card's cross-reference is resolved once per card, not once per record")
        void aCrossReferenceIsResolvedOncePerCard() {
            stubResolvableLookups();
            supply(oneUnitEachSwitchingCardAt(6, 4));

            reportOfFixture();

            verify(crossReferences, times(1)).findById(CARD_A);
            verify(crossReferences, times(1)).findById(CARD_B);
            verify(crossReferences, never()).findById(ACCOUNT_A);
        }

        @Test
        @DisplayName("records leave in the order they were supplied; nothing is re-ordered here")
        void theSuppliedOrderSurvives() {
            stubResolvableLookups();
            supply(List.of(transaction(identifier(3), CARD_A, ONE_UNIT, INSIDE),
                    transaction(identifier(1), CARD_A, ONE_UNIT, "2022-07-06"),
                    transaction(identifier(2), CARD_A, ONE_UNIT, "2022-07-07")));

            final ProcessedReport result = reportOfFixture();

            // The ordering specification belongs to the job that owns it and is materialised in the
            // frozen generation, so this stage neither sorts nor re-orders: what was supplied is what
            // leaves, even when the identifiers are out of sequence.
            assertThat(detailsOf(result)).containsExactly(
                    expectedCardADetail(identifier(3), detailMask(ONE_UNIT)),
                    expectedCardADetail(identifier(1), detailMask(ONE_UNIT)),
                    expectedCardADetail(identifier(2), detailMask(ONE_UNIT)));
        }
    }

    @Nested
    @DisplayName("the two amount masks: a zero blanks the field, and only totals carry a sign")
    class TheTwoAmountMasks {

        @Test
        @DisplayName("the amount field sits where the layout says it does")
        void theAmountFieldSitsWhereTheLayoutSaysItDoes() {
            assertThat(AMOUNT_FIELD_OFFSET).isEqualTo(ReportLineFormatter.AMOUNT_OFFSET);
            // The end offset is a literal too, and it is checked against the offset plus the mask width
            // by addition rather than by taking a difference.
            int end = AMOUNT_FIELD_OFFSET;
            end = end + AMOUNT_MASK_BYTES;
            assertThat(AMOUNT_FIELD_END).isEqualTo(end);
        }

        @Test
        @DisplayName("a value of exactly zero blanks the whole amount field rather than rendering it")
        void aZeroBlanksTheWholeAmountField() {
            stubResolvableLookups();
            supply(List.of(transaction(identifier(1), CARD_A, "0.00", INSIDE)));

            final ProcessedReport result = reportOfFixture();

            // Hand-built expectation: fifteen spaces, not a rendered zero. Both families behave this
            // way, and the detail row and the grand total are checked separately so a change to either
            // mask alone would be caught.
            assertThat(detailsOf(result))
                    .containsExactly(expectedCardADetail(identifier(1), blankMask()));
            assertThat(labelled(result, "Grand Total"))
                    .containsExactly(expectedGrandTotalLine(blankMask()));
            assertThat(amountFieldOf(detailsOf(result).get(0))).isEqualTo(blankMask());
            assertThat(amountFieldOf(detailsOf(result).get(0)))
                    .doesNotContain("0.00")
                    .doesNotContain(".");
        }

        @Test
        @DisplayName("a detail that is not negative carries a space where a total carries a plus")
        void onlyTotalsCarryASignForANonNegativeValue() {
            stubResolvableLookups();
            supply(oneUnitEach(1, CARD_A));

            final ProcessedReport result = reportOfFixture();

            // The two masks are separate by design and render the same value differently. Hand-built
            // images state both renderings, and the sliced sign positions state the difference again.
            assertThat(detailsOf(result))
                    .containsExactly(expectedCardADetail(identifier(1), detailMask(ONE_UNIT)));
            assertThat(labelled(result, "Page Total"))
                    .containsExactly(expectedPageTotalLine(totalMask("2.00")));
            assertThat(amountFieldOf(detailsOf(result).get(0)).charAt(0)).isEqualTo(SPACE);
            assertThat(amountFieldOf(labelled(result, "Page Total").get(0)).charAt(0))
                    .isEqualTo(PLUS);
            assertThat(amountFieldOf(labelled(result, "Grand Total").get(0)).charAt(0))
                    .isEqualTo(PLUS);
        }

        @Test
        @DisplayName("a negative amount carries a leading minus in both families")
        void aNegativeAmountCarriesALeadingMinusInBothFamilies() {
            stubResolvableLookups();
            supply(List.of(transaction(identifier(1), CARD_A, "-5.00", INSIDE)));

            final ProcessedReport result = reportOfFixture();

            // Five units negated, then re-added stale at the end of input: ten units negated in the
            // total. Accumulated by repeated addition of the same signed literal.
            final BigDecimal expected = accumulated(repeatedAmount("-5.00", 2));

            assertThat(detailsOf(result)).containsExactly(
                    expectedCardADetail(identifier(1), negativeDetailMask("5.00")));
            assertThat(labelled(result, "Grand Total"))
                    .containsExactly(expectedGrandTotalLine(negativeTotalMask("10.00")));
            assertThat(result.grandTotal()).isEqualByComparingTo(expected);
            assertThat(amountFieldOf(detailsOf(result).get(0)).charAt(0)).isEqualTo(HYPHEN);
        }

        @Test
        @DisplayName("the account identifier the cross-reference resolved persists across a card's "
                + "records, exactly as the legacy record area did")
        void theResolvedAccountPersistsAcrossACardsRecords() {
            stubResolvableLookups();
            supply(oneUnitEachSwitchingCardAt(4, 4));

            final ProcessedReport result = reportOfFixture();

            assertThat(detailsOf(result)).containsExactly(
                    expectedCardADetail(identifier(1), detailMask(ONE_UNIT)),
                    expectedCardADetail(identifier(2), detailMask(ONE_UNIT)),
                    expectedCardADetail(identifier(3), detailMask(ONE_UNIT)),
                    expectedDetailLine(identifier(4), ACCOUNT_B, TYPE_CODE, TYPE_DESCRIPTION,
                            CATEGORY_CODE, CATEGORY_DESCRIPTION, SOURCE, detailMask(ONE_UNIT)));
        }
    }

    @Nested
    @DisplayName("the item contract, instrumentation and statelessness")
    class TheItemContract {

        /** Published identity of the generation timer, asserted rather than assumed. */
        private static final String GENERATION_TIMER = "carddemo.batch.report.generation";

        /** Published identity of the report-record counter. */
        private static final String RECORD_COUNTER = "carddemo.batch.report.records";

        /** The batch step template's own lifecycle timer, which this stage must not stand in for. */
        private static final String STEP_TIMER = "carddemo.batch.cobol.step";

        @Test
        @DisplayName("a null item is rejected, because an empty parameter dataset yields no item")
        void aNullItemIsRejected() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> processor.process(null))
                    .withMessageContaining(TransactionReportProcessor.LEGACY_DD_DATEPARM);
        }

        @Test
        @DisplayName("a result is always returned, never null, so the report is never filtered away")
        void aResultIsAlwaysReturned() {
            stubResolvableLookups();
            supply(oneUnitEach(1, CARD_A));

            assertThat(processor.process(structuredItem())).isNotNull();
        }

        @Test
        @DisplayName("a window no record falls into produces its totals rather than a failure")
        void anEmptyWindowIsNotAFailure() {
            stubResolvableLookups();
            supply(List.of());

            final ProcessedReport result = reportOfFixture();

            assertThat(detailsOf(result)).isEmpty();
            assertThat(labelled(result, "Grand Total"))
                    .containsExactly(expectedGrandTotalLine(blankMask()));
            assertThat(result.accountBreakCount()).isZero();
            verifyNoInteractions(crossReferences);
        }

        @Test
        @DisplayName("a generator that reports no result at all is a breach, not an empty report")
        void anAbsentResultIsRejected() {
            final TransactionReportService silent = mock(TransactionReportService.class);
            when(silent.generateReportFromDateParameterCard(
                    any(ReportTransactionSource.class), any(), any())).thenReturn(null);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new TransactionReportProcessor(silent, registry)
                            .process(structuredItem()))
                    .withMessageContaining(START)
                    .withMessageContaining(END);
        }

        @Test
        @DisplayName("both collaborators are required at construction")
        void bothCollaboratorsAreRequired() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new TransactionReportProcessor(null, registry));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new TransactionReportProcessor(service, null));
        }

        @Test
        @DisplayName("a completed generation is timed as completed and its records are counted")
        void aCompletedGenerationIsTimedAndCounted() {
            stubResolvableLookups();
            supply(oneUnitEach(3, CARD_A));

            final ProcessedReport result = reportOfFixture();

            // Resolved through the registry's required search, so an absent meter fails here rather
            // than reaching a null assertion.
            final Timer generation = registry.get(GENERATION_TIMER)
                    .tag("resource", TransactionReportProcessor.LEGACY_DD_TRANREPT)
                    .tag("outcome", "COMPLETED")
                    .timer();
            final Counter reportRecords = registry.get(RECORD_COUNTER)
                    .tag("resource", TransactionReportProcessor.LEGACY_DD_TRANREPT)
                    .counter();

            assertThat(generation.count()).isEqualTo(1L);
            // The counter is the metric form of a line count, so it agrees with what the sink received.
            assertThat(reportRecords.count()).isEqualTo(result.records().size());
        }

        @Test
        @DisplayName("a failed generation is timed as a failure and never as a completion")
        void aFailedGenerationIsTimedAsAFailure() {
            when(crossReferences.findById(CARD_A)).thenReturn(Optional.empty());
            supply(oneUnitEach(1, CARD_A));

            final ReportTransactionInput candidate = structuredItem();
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> processor.process(candidate));

            assertThat(registry.find(GENERATION_TIMER).tag("outcome", "FAILED").timer())
                    .isNotNull();
            assertThat(registry.find(GENERATION_TIMER).tag("outcome", "COMPLETED").timer())
                    .isNull();
        }

        @Test
        @DisplayName("whole-step timing is left to the batch step template, not replaced here")
        void wholeStepTimingIsNotReplaced() {
            stubResolvableLookups();
            supply(oneUnitEach(1, CARD_A));

            processor.process(structuredItem());

            assertThat(registry.find(STEP_TIMER).timer()).isNull();
        }

        @Test
        @DisplayName("two consecutive runs of one item produce identical reports, so nothing leaks")
        void consecutiveRunsDoNotAccumulate() {
            stubResolvableLookups();
            supply(oneUnitEach(4, CARD_A));

            final ProcessedReport first = reportOfFixture();
            final ProcessedReport second = reportOfFixture();

            // The stage holds no counter, no total, no current card number and no accumulated list, so a
            // second run over the same item cannot inherit the first run's state.
            assertThat(second.records()).isEqualTo(first.records());
            assertThat(second.grandTotal()).isEqualByComparingTo(first.grandTotal());
            assertThat(second.lineCount()).isEqualTo(first.lineCount());
            assertThat(second.pageCount()).isEqualTo(first.pageCount());
            assertThat(second.accountBreakCount()).isEqualTo(first.accountBreakCount());
        }

        @Test
        @DisplayName("the published legacy names record the duplicate step name they came from")
        void thePublishedLegacyNamesRecordTheSourceDefect() {
            assertThat(TransactionReportProcessor.LEGACY_JOB).isEqualTo("TRANREPT");
            assertThat(TransactionReportProcessor.LEGACY_PROGRAM).isEqualTo("CBTRN03C");
            assertThat(TransactionReportProcessor.LEGACY_REPORT_STEP).isEqualTo("STEP10R");
            assertThat(TransactionReportProcessor.LEGACY_DD_DATEPARM).isEqualTo("DATEPARM");
            assertThat(TransactionReportProcessor.LEGACY_DD_TRANREPT).isEqualTo("TRANREPT");
            // The job labels its first two steps identically, and the report procedure declares itself
            // under the name of a different procedure member. Both are source defects: they are recorded
            // by these constants and not reproduced, because the target's own step names are distinct.
            assertThat(TransactionReportProcessor.LEGACY_UNLOAD_STEP)
                    .isEqualTo(TransactionReportProcessor.LEGACY_SORT_STEP);
            assertThat(TransactionReportProcessor.LEGACY_PROCEDURE).isEqualTo("REPROC");
        }

        @Test
        @DisplayName("the provenance this suite measured its figures from is the verified checkout")
        void theProvenanceIsTheVerifiedCheckout() {
            // Carried once, as a matrix-header string. The release stamp is deliberately not asserted
            // against any individual member, because it is not universal across the estate.
            assertThat(TestDataFactory.VERIFIED_CHECKOUT_COMMIT)
                    .isEqualTo("7756d895ffeb65f7ea72aaa609e356d9899afcec");
            assertThat(TestDataFactory.UPSTREAM_RELEASE_STAMP)
                    .isEqualTo("CardDemo_v1.0-15-g27d6c6f-68");
        }
    }
}
