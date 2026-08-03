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
package com.carddemo.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.batch.step.RejectRecordWriter;
import com.carddemo.batch.step.RejectRecordWriter.RejectedTransaction;
import com.carddemo.domain.DailyTransaction;
import com.carddemo.domain.enums.RejectReason;
import com.carddemo.util.DailyTransactionRecordMapper;
import com.carddemo.util.ReportLineFormatter;
import com.carddemo.util.StatementTextTemplates;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.core.io.FileSystemResource;

/**
 * Executes the three committed expected-output fixtures against the production emitters that own their
 * bytes, so that the golden files are a build-enforced contract rather than three correct but inert
 * artefacts.
 *
 * <h2>Why this class exists</h2>
 * The module ships three fixed-width golden files - a 430-byte reject dataset, a 133-byte transaction
 * report and an 80-byte statement - and a golden file that no test opens proves nothing at all. This
 * class is their only consumer, and it is the byte-equivalence gate for all three: every record of
 * every fixture is compared, on raw encoded bytes, to output produced by the shipped code, and every
 * record is accounted for so that no line can be quietly skipped.
 *
 * <h2>Which side is which, stated explicitly</h2>
 * <strong>The expected side is always the committed fixture bytes, and nothing else.</strong> No
 * expected value anywhere below is produced by calling the code under test, no output is snapshotted,
 * and no fixture is regenerated. The production emitters appear only on the actual side. Where a data
 * value has to be recovered in order to ask an emitter to lay it out - an amount, an account
 * identifier, a description - it is read out of the committed bytes by this class's own slicing and
 * mask-parsing helpers, which reimplement the numeric-edited masks in reverse and share no code with
 * the emitters. The emitter is then asked to place that value, and the placement is what is compared.
 *
 * <h2>What each of the three proves, and how far it goes</h2>
 * <ul>
 *   <li><strong>The reject dataset is end to end.</strong> The 38 records the posting run rejects are
 *       selected out of the committed 300-record daily-transaction input, mapped by the production
 *       mapper, written by the production {@link RejectRecordWriter} to a real file, and that file's
 *       bytes are compared to {@code daily-reject.txt}. Nothing is mocked and nothing is stubbed:
 *       committed input in, committed output out.</li>
 *   <li><strong>The report and the statement are line exact.</strong> Their driver services - the ones
 *       that will decide page breaks, accumulate totals and order records - are not part of the
 *       delivered surface yet, so this class classifies every committed record by its own structure,
 *       re-emits it through the production formatter that owns that record type, and compares bytes.
 *       Every one of the 519 report records and every one of the 1,262 statement records is
 *       re-emitted; the classification is asserted to be total, so an unrecognised record fails rather
 *       than being skipped.</li>
 * </ul>
 *
 * <h2>The arithmetic is verified independently of the formatting</h2>
 * Re-emitting a value proves the layout but not the value, so the accumulated figures the fixtures
 * carry are checked against the detail records this class sums for itself. Three properties hold, and
 * the third is a legacy anomaly that is pinned rather than corrected:
 * <ul>
 *   <li>every account total equals the sum of the detail amounts in its group, exactly;</li>
 *   <li>every statement's total expenditure equals the sum of that statement's transaction amounts,
 *       exactly;</li>
 *   <li>the report's page and grand totals equal their sums <em>plus the final detail amount once
 *       more</em>, because the legacy report adds the last record's amount a second time when it
 *       reaches end of file. That is a documented source behaviour, so it is asserted as the contract
 *       it is; "corrected" totals would fail parity.</li>
 * </ul>
 *
 * <h2>Record separation</h2>
 * The fixtures use one line feed per record, which is how a fixed-block dataset is carried in a text
 * file; the reject writer emits no separator at all, because separation belongs to the dataset
 * definition rather than to the record. Both facts are asserted, and the comparison is made record by
 * record on the fixture's own stride and then again over the whole concatenation, so neither a lost
 * terminator nor a spurious one can pass.
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The emitting programs are
 * {@code app/cbl/CBTRN02C.cbl} for the reject dataset, {@code app/cbl/CBTRN03C.cbl} for the report and
 * {@code app/cbl/CBSTM03A.CBL} for the statement. No legacy source line is transcribed here - only
 * widths, offsets, counts and contract literals, which are metadata rather than source.
 */
@DisplayName("Expected-output fixtures: the 430, 133 and 80-byte golden files, executed")
final class ExpectedOutputFixtureContractTest {

    /** Classpath location of the committed reject dataset. */
    private static final String REJECT_FIXTURE = "/fixtures/expected/daily-reject.txt";

    /** Classpath location of the committed transaction report. */
    private static final String REPORT_FIXTURE = "/fixtures/expected/transaction-report.txt";

    /** Classpath location of the committed statement. */
    private static final String STATEMENT_FIXTURE = "/fixtures/expected/statement.txt";

    /** Classpath location of the committed daily-transaction input the reject dataset derives from. */
    private static final String DAILY_TRANSACTION_INPUT = "/fixtures/input/dailytran.txt";

    /** Record width of the reject dataset: a 350-byte source image plus an 80-byte trailer. */
    private static final int REJECT_RECORD_WIDTH = 430;

    /** Record width of the transaction report, from the report dataset's record length. */
    private static final int REPORT_RECORD_WIDTH = 133;

    /** Record width of the statement text dataset. */
    private static final int STATEMENT_RECORD_WIDTH = 80;

    /** Record width of the daily-transaction input. */
    private static final int DAILY_TRANSACTION_RECORD_WIDTH = 350;

    /** How many records the committed reject dataset carries. */
    private static final int REJECT_RECORD_COUNT = 38;

    /** How many records the committed report carries. */
    private static final int REPORT_RECORD_COUNT = 519;

    /** How many records the committed statement carries. */
    private static final int STATEMENT_RECORD_COUNT = 1262;

    /** How many statements the committed statement dataset carries, one per seeded account. */
    private static final int STATEMENT_COUNT = 50;

    /** How many records the committed daily-transaction input carries. */
    private static final int DAILY_TRANSACTION_RECORD_COUNT = 300;

    /**
     * Zero-based indices, within the committed daily-transaction input, of the records the posting run
     * rejects.
     *
     * <p>Transcribed as literals and never searched for, so that this list is an assertion about the
     * fixture rather than a description of it: if the reject dataset ever stopped corresponding to
     * exactly these 38 input records, the comparison below would fail instead of quietly re-deriving
     * itself. All 38 fail the same check - the over-limit test - which is why one reason code covers
     * the whole dataset.
     */
    private static final int[] REJECTED_INPUT_INDICES = {
        15, 31, 34, 42, 49, 65, 78, 88, 100, 128, 154, 157, 158, 161, 172, 175, 177, 180, 189, 207,
        222, 223, 224, 226, 230, 233, 234, 239, 241, 252, 266, 270, 271, 272, 279, 282, 291, 298,
    };

    /** The reason every one of the 38 rejected records carries. */
    private static final RejectReason REJECT_REASON = RejectReason.OVERLIMIT_TRANSACTION;

    /** Reporting-window start date the committed report header carries. */
    private static final String REPORT_START_DATE = "2022-01-01";

    /** Reporting-window end date the committed report header carries. */
    private static final String REPORT_END_DATE = "2022-07-06";

    // Report record geometry, transcribed from the committed bytes and cross-checked against the
    // report line's own field widths. Every slice below is taken at one of these offsets.

    /** Offset of the transaction identifier on a report detail record. */
    private static final int REPORT_TRANSACTION_ID_OFFSET = 0;

    /** Width of the transaction identifier on a report detail record. */
    private static final int REPORT_TRANSACTION_ID_WIDTH = 16;

    /** Offset of the account identifier on a report detail record. */
    private static final int REPORT_ACCOUNT_ID_OFFSET = 17;

    /** Width of the account identifier on a report detail record. */
    private static final int REPORT_ACCOUNT_ID_WIDTH = 11;

    /** Offset of the transaction type code on a report detail record. */
    private static final int REPORT_TYPE_CODE_OFFSET = 29;

    /** Width of the transaction type code on a report detail record. */
    private static final int REPORT_TYPE_CODE_WIDTH = 2;

    /** Offset of the transaction type description on a report detail record. */
    private static final int REPORT_TYPE_DESCRIPTION_OFFSET = 32;

    /** Width of the transaction type description on a report detail record. */
    private static final int REPORT_TYPE_DESCRIPTION_WIDTH = 15;

    /** Offset of the transaction category code on a report detail record. */
    private static final int REPORT_CATEGORY_CODE_OFFSET = 48;

    /** Width of the transaction category code on a report detail record. */
    private static final int REPORT_CATEGORY_CODE_WIDTH = 4;

    /** Offset of the transaction category description on a report detail record. */
    private static final int REPORT_CATEGORY_DESCRIPTION_OFFSET = 53;

    /** Width of the transaction category description on a report detail record. */
    private static final int REPORT_CATEGORY_DESCRIPTION_WIDTH = 29;

    /** Offset of the transaction source on a report detail record. */
    private static final int REPORT_SOURCE_OFFSET = 83;

    /** Width of the transaction source on a report detail record. */
    private static final int REPORT_SOURCE_WIDTH = 10;

    /** Offset of the numeric-edited amount mask on every report record that carries one. */
    private static final int REPORT_AMOUNT_OFFSET = 97;

    /** Width of the numeric-edited amount mask on a report record. */
    private static final int REPORT_AMOUNT_WIDTH = 15;

    // Statement record geometry, transcribed from the committed bytes.

    /** Width of the customer-name field on the statement name record. */
    private static final int STATEMENT_NAME_WIDTH = 75;

    /** Width of an address field on the first two statement address records. */
    private static final int STATEMENT_ADDRESS_WIDTH = 50;

    /** Width of the label that opens the account, balance and score records. */
    private static final int STATEMENT_LABEL_WIDTH = 20;

    /** Width of the value that follows the label on the account and score records. */
    private static final int STATEMENT_LABEL_VALUE_WIDTH = 20;

    /** Offset of the amount mask on the balance record, immediately after its label. */
    private static final int STATEMENT_BALANCE_AMOUNT_OFFSET = 20;

    /** Width of a statement amount mask: nine integer digits, a point, two decimals and a sign. */
    private static final int STATEMENT_AMOUNT_WIDTH = 13;

    /** Width of the transaction identifier on a statement transaction record. */
    private static final int STATEMENT_TRANSACTION_ID_WIDTH = 16;

    /** Offset of the transaction detail text on a statement transaction record. */
    private static final int STATEMENT_TRANSACTION_DETAIL_OFFSET = 17;

    /** Width of the transaction detail text on a statement transaction record. */
    private static final int STATEMENT_TRANSACTION_DETAIL_WIDTH = 49;

    /** Offset of the currency symbol that precedes every statement amount mask. */
    private static final int STATEMENT_CURRENCY_OFFSET = 66;

    /** Offset of the amount mask on the transaction and total records. */
    private static final int STATEMENT_TRANSACTION_AMOUNT_OFFSET = 67;

    /** The label that opens the statement's total-expenditure record. */
    private static final String STATEMENT_TOTAL_LABEL = "Total EXP:";

    /** The label that opens the statement's account-identifier record. */
    private static final String STATEMENT_ACCOUNT_LABEL = "Account ID";

    /** The label that opens the statement's balance record. */
    private static final String STATEMENT_BALANCE_LABEL = "Current Balance";

    /** The label that opens the statement's credit-score record. */
    private static final String STATEMENT_SCORE_LABEL = "FICO Score";

    /** The single byte that separates one fixture record from the next. */
    private static final char LINE_FEED = '\n';

    /** The byte that must never appear: these fixtures are not carriage-return delimited. */
    private static final char CARRIAGE_RETURN = '\r';

    /** Scale every monetary value in the estate carries. */
    private static final int MONETARY_SCALE = 2;

    /**
     * Reads a classpath fixture whole.
     *
     * @param  resource the classpath location
     * @return its bytes, exactly as committed
     * @throws IOException if the fixture cannot be read
     */
    private static byte[] fixtureBytes(final String resource) throws IOException {
        try (InputStream stream =
                ExpectedOutputFixtureContractTest.class.getResourceAsStream(resource)) {
            assertThat(stream).as("%s must be on the test classpath", resource).isNotNull();
            return stream.readAllBytes();
        }
    }

    /**
     * Splits a fixture into records on a fixed stride, asserting the terminator of every one.
     *
     * @param  resource    the classpath location
     * @param  recordWidth the record width in encoded bytes, excluding the terminator
     * @return the records, each exactly {@code recordWidth} bytes and terminator-free
     * @throws IOException if the fixture cannot be read
     */
    private static List<byte[]> records(final String resource, final int recordWidth)
            throws IOException {
        final byte[] content = fixtureBytes(resource);
        final int stride = recordWidth + 1;

        assertThat(content.length % stride)
                .as("%s must be an exact multiple of its %d-byte stride, or no record boundary is "
                        + "recoverable", resource, stride)
                .isZero();

        final List<byte[]> records = new ArrayList<>();
        for (int start = 0; start < content.length; start += stride) {
            assertThat((char) content[start + recordWidth])
                    .as("%s: the record beginning at byte %d must be closed by one line feed",
                            resource, start)
                    .isEqualTo(LINE_FEED);
            final byte[] record = new byte[recordWidth];
            System.arraycopy(content, start, record, 0, recordWidth);
            records.add(record);
        }
        return records;
    }

    /**
     * Splits a fixture into records and decodes each as US-ASCII text.
     *
     * @param  resource    the classpath location
     * @param  recordWidth the record width in encoded bytes
     * @return the records as text, each exactly {@code recordWidth} characters
     * @throws IOException if the fixture cannot be read
     */
    private static List<String> textRecords(final String resource, final int recordWidth)
            throws IOException {
        final List<String> text = new ArrayList<>();
        for (final byte[] record : records(resource, recordWidth)) {
            text.add(new String(record, StandardCharsets.US_ASCII));
        }
        return text;
    }

    /**
     * Recovers the value a report amount mask carries.
     *
     * <p>Reads the mask in reverse: the leading byte is the fixed sign insertion, the remainder is the
     * zero-suppressed magnitude with grouping separators. An entirely suppressed magnitude is zero.
     * This shares no code with the emitter, which is the point.
     *
     * @param  record the report record
     * @return the value the mask carries, at the canonical monetary scale
     */
    private static BigDecimal reportAmount(final String record) {
        final String mask =
                record.substring(REPORT_AMOUNT_OFFSET, REPORT_AMOUNT_OFFSET + REPORT_AMOUNT_WIDTH);
        final char sign = mask.charAt(0);
        final String magnitude = mask.substring(1).replace(",", "").replace(" ", "");
        final BigDecimal value = magnitude.isEmpty()
                ? BigDecimal.ZERO.setScale(MONETARY_SCALE)
                : new BigDecimal(magnitude);
        return sign == '-' ? value.negate() : value;
    }

    /**
     * Recovers the value a statement amount mask carries.
     *
     * <p>Reads the mask in reverse: the trailing byte is the sign, the twelve bytes before it are the
     * magnitude, which mask A carries with leading zeros and mask B with leading spaces.
     *
     * @param  record the statement record
     * @param  offset the offset at which the mask begins
     * @return the value the mask carries, at the canonical monetary scale
     */
    private static BigDecimal statementAmount(final String record, final int offset) {
        final String mask = record.substring(offset, offset + STATEMENT_AMOUNT_WIDTH);
        final String magnitude = mask.substring(0, STATEMENT_AMOUNT_WIDTH - 1).replace(" ", "");
        final char sign = mask.charAt(STATEMENT_AMOUNT_WIDTH - 1);
        final BigDecimal value = magnitude.isEmpty()
                ? BigDecimal.ZERO.setScale(MONETARY_SCALE)
                : new BigDecimal(magnitude);
        return sign == '-' ? value.negate() : value;
    }

    /** The structural kinds a report record can be, which together must cover every record. */
    private enum ReportRecordKind {

        /** The report name and date-range header that opens every page. */
        NAME_HEADER,

        /** The blank record inside the header block. */
        BLANK,

        /** The column-heading record inside the header block. */
        COLUMN_HEADER,

        /** A rule record of hyphens. */
        RULE,

        /** A transaction detail record. */
        DETAIL,

        /** A page total record. */
        PAGE_TOTAL,

        /** An account total record. */
        ACCOUNT_TOTAL,

        /** The single grand total record that closes the report. */
        GRAND_TOTAL,
    }

    /** The structural kinds a statement record can be, which together must cover every record. */
    private enum StatementRecordKind {

        /** The banner that opens a statement. */
        START_BANNER,

        /** The customer-name record. */
        CUSTOMER_NAME,

        /** The first address record. */
        ADDRESS_LINE_1,

        /** The second address record. */
        ADDRESS_LINE_2,

        /** The third address record, which spans the whole width. */
        ADDRESS_LINE_3,

        /** A rule record of hyphens. */
        RULE,

        /** The basic-details heading. */
        BASIC_DETAILS_HEADING,

        /** The account-identifier record. */
        ACCOUNT_ID,

        /** The current-balance record. */
        CURRENT_BALANCE,

        /** The credit-score record. */
        FICO_SCORE,

        /** The transaction-summary heading. */
        TRANSACTION_SUMMARY_HEADING,

        /** The transaction column headings. */
        TRANSACTION_COLUMN_HEADINGS,

        /** A transaction record. */
        TRANSACTION,

        /** The total-expenditure record. */
        TOTAL_EXPENDITURE,

        /** The banner that closes a statement. */
        END_BANNER,
    }

    /**
     * Classifies a report record by its own committed bytes.
     *
     * @param  record the record to classify
     * @return its structural kind
     */
    private static ReportRecordKind classifyReport(final String record) {
        if (record.startsWith("DALYREPT")) {
            return ReportRecordKind.NAME_HEADER;
        }
        if (record.isBlank()) {
            return ReportRecordKind.BLANK;
        }
        if (record.startsWith("Transaction ID")) {
            return ReportRecordKind.COLUMN_HEADER;
        }
        if (record.stripTrailing().chars().allMatch(character -> character == '-')) {
            return ReportRecordKind.RULE;
        }
        if (record.startsWith("Page Total")) {
            return ReportRecordKind.PAGE_TOTAL;
        }
        if (record.startsWith("Account Total")) {
            return ReportRecordKind.ACCOUNT_TOTAL;
        }
        if (record.startsWith("Grand Total")) {
            return ReportRecordKind.GRAND_TOTAL;
        }
        return ReportRecordKind.DETAIL;
    }

    /**
     * Re-emits a report record through the production formatter that owns its record type.
     *
     * @param  record the committed record, read for the values it carries
     * @return the record the production formatter produces for those values
     */
    private static String reemitReport(final String record) {
        return switch (classifyReport(record)) {
            case NAME_HEADER ->
                    ReportLineFormatter.buildReportNameHeader(REPORT_START_DATE, REPORT_END_DATE);
            case BLANK -> ReportLineFormatter.buildBlankLine();
            case COLUMN_HEADER -> ReportLineFormatter.buildColumnHeaderLine();
            case RULE -> ReportLineFormatter.buildRuleLine();
            case PAGE_TOTAL -> ReportLineFormatter.buildPageTotalLine(reportAmount(record));
            case ACCOUNT_TOTAL -> ReportLineFormatter.buildAccountTotalLine(reportAmount(record));
            case GRAND_TOTAL -> ReportLineFormatter.buildGrandTotalLine(reportAmount(record));
            case DETAIL -> ReportLineFormatter.buildTransactionDetailLine(
                    slice(record, REPORT_TRANSACTION_ID_OFFSET, REPORT_TRANSACTION_ID_WIDTH),
                    slice(record, REPORT_ACCOUNT_ID_OFFSET, REPORT_ACCOUNT_ID_WIDTH),
                    slice(record, REPORT_TYPE_CODE_OFFSET, REPORT_TYPE_CODE_WIDTH),
                    slice(record, REPORT_TYPE_DESCRIPTION_OFFSET, REPORT_TYPE_DESCRIPTION_WIDTH),
                    Integer.parseInt(
                            slice(record, REPORT_CATEGORY_CODE_OFFSET, REPORT_CATEGORY_CODE_WIDTH)),
                    slice(record, REPORT_CATEGORY_DESCRIPTION_OFFSET,
                            REPORT_CATEGORY_DESCRIPTION_WIDTH),
                    slice(record, REPORT_SOURCE_OFFSET, REPORT_SOURCE_WIDTH),
                    reportAmount(record));
        };
    }

    /**
     * Classifies a statement record by its own committed bytes.
     *
     * @param  record       the record to classify
     * @param  positionInStatement how many records of the current statement precede it, which is what
     *                      distinguishes the three free-text records from one another
     * @return its structural kind
     */
    private static StatementRecordKind classifyStatement(final String record,
            final int positionInStatement) {
        if (record.equals(StatementTextTemplates.ST_LINE0_START_BANNER)) {
            return StatementRecordKind.START_BANNER;
        }
        if (record.equals(StatementTextTemplates.ST_LINE15_END_BANNER)) {
            return StatementRecordKind.END_BANNER;
        }
        if (record.equals(StatementTextTemplates.RULE_LINE)) {
            return StatementRecordKind.RULE;
        }
        if (record.equals(StatementTextTemplates.ST_LINE6_BASIC_DETAILS_HEADING)) {
            return StatementRecordKind.BASIC_DETAILS_HEADING;
        }
        if (record.equals(StatementTextTemplates.ST_LINE11_TRANSACTION_SUMMARY_HEADING)) {
            return StatementRecordKind.TRANSACTION_SUMMARY_HEADING;
        }
        if (record.equals(StatementTextTemplates.ST_LINE13_TRANSACTION_COLUMN_HEADINGS)) {
            return StatementRecordKind.TRANSACTION_COLUMN_HEADINGS;
        }
        if (record.startsWith(STATEMENT_ACCOUNT_LABEL)) {
            return StatementRecordKind.ACCOUNT_ID;
        }
        if (record.startsWith(STATEMENT_BALANCE_LABEL)) {
            return StatementRecordKind.CURRENT_BALANCE;
        }
        if (record.startsWith(STATEMENT_SCORE_LABEL)) {
            return StatementRecordKind.FICO_SCORE;
        }
        if (record.startsWith(STATEMENT_TOTAL_LABEL)) {
            return StatementRecordKind.TOTAL_EXPENDITURE;
        }
        if (record.charAt(STATEMENT_CURRENCY_OFFSET) == '$') {
            return StatementRecordKind.TRANSACTION;
        }
        return switch (positionInStatement) {
            case 1 -> StatementRecordKind.CUSTOMER_NAME;
            case 2 -> StatementRecordKind.ADDRESS_LINE_1;
            case 3 -> StatementRecordKind.ADDRESS_LINE_2;
            default -> StatementRecordKind.ADDRESS_LINE_3;
        };
    }

    /**
     * Re-emits a statement record through the production template that owns its record type.
     *
     * @param  record              the committed record, read for the values it carries
     * @param  positionInStatement how many records of the current statement precede it
     * @return the record the production template produces for those values
     */
    private static String reemitStatement(final String record, final int positionInStatement) {
        return switch (classifyStatement(record, positionInStatement)) {
            case START_BANNER -> StatementTextTemplates.ST_LINE0_START_BANNER;
            case END_BANNER -> StatementTextTemplates.ST_LINE15_END_BANNER;
            case RULE -> StatementTextTemplates.RULE_LINE;
            case BASIC_DETAILS_HEADING -> StatementTextTemplates.ST_LINE6_BASIC_DETAILS_HEADING;
            case TRANSACTION_SUMMARY_HEADING ->
                    StatementTextTemplates.ST_LINE11_TRANSACTION_SUMMARY_HEADING;
            case TRANSACTION_COLUMN_HEADINGS ->
                    StatementTextTemplates.ST_LINE13_TRANSACTION_COLUMN_HEADINGS;
            case CUSTOMER_NAME ->
                    StatementTextTemplates.stLine1CustomerName(slice(record, 0, STATEMENT_NAME_WIDTH));
            case ADDRESS_LINE_1 -> StatementTextTemplates
                    .stLine2AddressLine1(slice(record, 0, STATEMENT_ADDRESS_WIDTH));
            case ADDRESS_LINE_2 -> StatementTextTemplates
                    .stLine3AddressLine2(slice(record, 0, STATEMENT_ADDRESS_WIDTH));
            case ADDRESS_LINE_3 -> StatementTextTemplates
                    .stLine4AddressLine3(slice(record, 0, STATEMENT_RECORD_WIDTH));
            case ACCOUNT_ID -> StatementTextTemplates.stLine7AccountId(
                    slice(record, STATEMENT_LABEL_WIDTH, STATEMENT_LABEL_VALUE_WIDTH));
            case FICO_SCORE -> StatementTextTemplates.stLine9FicoScore(
                    slice(record, STATEMENT_LABEL_WIDTH, STATEMENT_LABEL_VALUE_WIDTH));
            case CURRENT_BALANCE -> StatementTextTemplates.stLine8CurrentBalance(
                    statementAmount(record, STATEMENT_BALANCE_AMOUNT_OFFSET));
            case TRANSACTION -> StatementTextTemplates.stLine14Transaction(
                    slice(record, 0, STATEMENT_TRANSACTION_ID_WIDTH),
                    slice(record, STATEMENT_TRANSACTION_DETAIL_OFFSET,
                            STATEMENT_TRANSACTION_DETAIL_WIDTH),
                    statementAmount(record, STATEMENT_TRANSACTION_AMOUNT_OFFSET));
            case TOTAL_EXPENDITURE -> StatementTextTemplates.stLine14aTotalExpenditure(
                    statementAmount(record, STATEMENT_TRANSACTION_AMOUNT_OFFSET));
        };
    }

    /**
     * Takes a field out of a committed record.
     *
     * @param  record the record
     * @param  offset zero-based offset of the field
     * @param  width  width of the field
     * @return the field's bytes as text, untrimmed
     */
    private static String slice(final String record, final int offset, final int width) {
        return record.substring(offset, offset + width);
    }

    @Nested
    @DisplayName("the reject dataset, produced end to end from the committed input")
    final class TheRejectDataset {

        @Test
        @DisplayName("the committed fixture carries 38 records of exactly 430 bytes, each closed by "
                + "one line feed and by no carriage return")
        void theCommittedFixtureCarriesThirtyEightRecords() throws IOException {
            final List<byte[]> committed = records(REJECT_FIXTURE, REJECT_RECORD_WIDTH);

            assertThat(committed).hasSize(REJECT_RECORD_COUNT);
            assertThat(committed).allSatisfy(record ->
                    assertThat(record).hasSize(REJECT_RECORD_WIDTH));
            assertThat(fixtureBytes(REJECT_FIXTURE))
                    .hasSize(REJECT_RECORD_COUNT * (REJECT_RECORD_WIDTH + 1));
            assertThat(new String(fixtureBytes(REJECT_FIXTURE), StandardCharsets.US_ASCII))
                    .doesNotContain(String.valueOf(CARRIAGE_RETURN));
        }

        @Test
        @DisplayName("the production writer, driven over the 38 rejected records of the committed "
                + "input, reproduces every one of the committed 430-byte records byte for byte")
        void theProductionWriterReproducesEveryCommittedRecord(@TempDir final Path directory)
                throws Exception {
            final List<byte[]> committed = records(REJECT_FIXTURE, REJECT_RECORD_WIDTH);
            final byte[] produced = writeRejectDataset(directory);

            assertThat(produced)
                    .as("the writer emits no separator, so its length is the record count times the "
                            + "record width")
                    .hasSize(REJECT_RECORD_COUNT * REJECT_RECORD_WIDTH);

            for (int index = 0; index < committed.size(); index++) {
                final byte[] actual = new byte[REJECT_RECORD_WIDTH];
                System.arraycopy(produced, index * REJECT_RECORD_WIDTH, actual, 0,
                        REJECT_RECORD_WIDTH);

                assertThat(actual)
                        .as("reject record %d must equal the committed record byte for byte", index)
                        .isEqualTo(committed.get(index));
            }
        }

        @Test
        @DisplayName("the whole produced stream equals the committed fixture with its record "
                + "separators removed, so neither a lost nor a spurious terminator can pass")
        void theWholeProducedStreamEqualsTheCommittedFixture(@TempDir final Path directory)
                throws Exception {
            final byte[] committed = fixtureBytes(REJECT_FIXTURE);
            final byte[] expected = new byte[REJECT_RECORD_COUNT * REJECT_RECORD_WIDTH];
            for (int index = 0; index < REJECT_RECORD_COUNT; index++) {
                System.arraycopy(committed, index * (REJECT_RECORD_WIDTH + 1), expected,
                        index * REJECT_RECORD_WIDTH, REJECT_RECORD_WIDTH);
            }

            assertThat(writeRejectDataset(directory)).isEqualTo(expected);
        }

        @Test
        @DisplayName("every committed record echoes its 350-byte source image unchanged and appends "
                + "the 80-byte trailer, which is what makes the reject dataset re-readable")
        void everyCommittedRecordEchoesItsSourceImageUnchanged() throws IOException {
            final List<String> input =
                    textRecords(DAILY_TRANSACTION_INPUT, DAILY_TRANSACTION_RECORD_WIDTH);
            final List<String> committed = textRecords(REJECT_FIXTURE, REJECT_RECORD_WIDTH);

            assertThat(input).hasSize(DAILY_TRANSACTION_RECORD_COUNT);
            assertThat(REJECTED_INPUT_INDICES).hasSize(REJECT_RECORD_COUNT);

            for (int index = 0; index < REJECT_RECORD_COUNT; index++) {
                assertThat(committed.get(index).substring(0, DAILY_TRANSACTION_RECORD_WIDTH))
                        .as("reject record %d must carry input record %d verbatim", index,
                                REJECTED_INPUT_INDICES[index])
                        .isEqualTo(input.get(REJECTED_INPUT_INDICES[index]));
            }
        }

        @Test
        @DisplayName("every committed trailer carries the four-digit over-limit reason code and its "
                + "description padded to the full 76-byte field")
        void everyCommittedTrailerCarriesTheOverLimitReason() throws IOException {
            // The trailer is composed here from the enumeration's own code and description and the
            // two field widths, and never by calling the writer that produces it.
            final String expectedTrailer =
                    "0".repeat(RejectRecordWriter.FAIL_REASON_LENGTH
                                    - String.valueOf(REJECT_REASON.getReasonCode()).length())
                            + REJECT_REASON.getReasonCode()
                            + REJECT_REASON.getDescription()
                            + " ".repeat(RejectRecordWriter.FAIL_REASON_DESC_LENGTH
                                    - REJECT_REASON.getDescription().length());

            assertThat(expectedTrailer).hasSize(RejectRecordWriter.VALIDATION_TRAILER_LENGTH);
            assertThat(textRecords(REJECT_FIXTURE, REJECT_RECORD_WIDTH))
                    .allSatisfy(record -> assertThat(record.substring(
                                    DAILY_TRANSACTION_RECORD_WIDTH))
                            .isEqualTo(expectedTrailer));
        }

        /**
         * Runs the production writer over the 38 rejected records of the committed input.
         *
         * @param  directory a temporary directory the writer may create its dataset in
         * @return the bytes the writer produced
         * @throws Exception if the writer or the file system fails
         */
        private byte[] writeRejectDataset(final Path directory) throws Exception {
            final byte[] input = fixtureBytes(DAILY_TRANSACTION_INPUT);
            final int stride = DAILY_TRANSACTION_RECORD_WIDTH + 1;

            final List<RejectedTransaction> rejected = new ArrayList<>();
            for (final int index : REJECTED_INPUT_INDICES) {
                final DailyTransaction source =
                        DailyTransactionRecordMapper.fromRecord(input, index * stride);
                rejected.add(new RejectedTransaction(source, REJECT_REASON));
            }

            final Path target = directory.resolve("dalyrejs-from-committed-input.dat");
            final RejectRecordWriter writer =
                    new RejectRecordWriter(new FileSystemResource(target), new SimpleMeterRegistry());
            final ExecutionContext context = new ExecutionContext();
            writer.open(context);
            writer.write(new Chunk<>(rejected));
            writer.update(context);
            writer.close();

            assertThat(writer.recordsWritten()).isEqualTo(REJECT_RECORD_COUNT);
            return Files.readAllBytes(target);
        }
    }

    @Nested
    @DisplayName("the transaction report, re-emitted record by record")
    final class TheTransactionReport {

        @Test
        @DisplayName("the committed fixture carries 519 records of exactly 133 bytes, each closed by "
                + "one line feed and by no carriage return")
        void theCommittedFixtureCarriesFiveHundredAndNineteenRecords() throws IOException {
            final List<byte[]> committed = records(REPORT_FIXTURE, REPORT_RECORD_WIDTH);

            assertThat(committed).hasSize(REPORT_RECORD_COUNT);
            assertThat(committed).allSatisfy(record ->
                    assertThat(record).hasSize(REPORT_RECORD_WIDTH));
            assertThat(fixtureBytes(REPORT_FIXTURE))
                    .hasSize(REPORT_RECORD_COUNT * (REPORT_RECORD_WIDTH + 1));
            assertThat(new String(fixtureBytes(REPORT_FIXTURE), StandardCharsets.US_ASCII))
                    .doesNotContain(String.valueOf(CARRIAGE_RETURN));
        }

        @Test
        @DisplayName("every one of the 519 committed records is reproduced byte for byte by the "
                + "production report formatter, with no record left unaccounted for")
        void everyCommittedRecordIsReproducedByTheProductionFormatter() throws IOException {
            final List<String> committed = textRecords(REPORT_FIXTURE, REPORT_RECORD_WIDTH);
            int accounted = 0;

            for (int index = 0; index < committed.size(); index++) {
                final String record = committed.get(index);

                assertThat(reemitReport(record))
                        .as("report record %d (%s) must be reproduced byte for byte", index,
                                classifyReport(record))
                        .isEqualTo(record);
                accounted++;
            }

            assertThat(accounted)
                    .as("every committed record must have been classified and re-emitted")
                    .isEqualTo(REPORT_RECORD_COUNT);
        }

        @Test
        @DisplayName("the record census matches the committed structure: 18 pages of header block, "
                + "312 detail records, 49 account totals, 18 page totals and one grand total")
        void theRecordCensusMatchesTheCommittedStructure() throws IOException {
            final List<String> committed = textRecords(REPORT_FIXTURE, REPORT_RECORD_WIDTH);
            final List<ReportRecordKind> kinds = new ArrayList<>();
            for (final String record : committed) {
                kinds.add(classifyReport(record));
            }

            assertThat(kinds).hasSize(REPORT_RECORD_COUNT);
            assertThat(count(kinds, ReportRecordKind.NAME_HEADER)).isEqualTo(18);
            assertThat(count(kinds, ReportRecordKind.BLANK)).isEqualTo(18);
            assertThat(count(kinds, ReportRecordKind.COLUMN_HEADER)).isEqualTo(18);
            assertThat(count(kinds, ReportRecordKind.RULE)).isEqualTo(85);
            assertThat(count(kinds, ReportRecordKind.DETAIL)).isEqualTo(312);
            assertThat(count(kinds, ReportRecordKind.ACCOUNT_TOTAL)).isEqualTo(49);
            assertThat(count(kinds, ReportRecordKind.PAGE_TOTAL)).isEqualTo(18);
            assertThat(count(kinds, ReportRecordKind.GRAND_TOTAL)).isEqualTo(1);
            assertThat(kinds.get(0)).isEqualTo(ReportRecordKind.NAME_HEADER);
            assertThat(kinds.get(kinds.size() - 1)).isEqualTo(ReportRecordKind.GRAND_TOTAL);
        }

        @Test
        @DisplayName("the four-record header block the formatter builds is exactly the four records "
                + "that open every page of the committed report")
        void theHeaderBlockIsExactlyTheFourRecordsThatOpenEveryPage() throws IOException {
            final List<String> committed = textRecords(REPORT_FIXTURE, REPORT_RECORD_WIDTH);
            final List<String> block =
                    ReportLineFormatter.buildHeaderBlock(REPORT_START_DATE, REPORT_END_DATE);

            assertThat(block).hasSize(ReportLineFormatter.HEADER_BLOCK_RECORD_COUNT);
            assertThat(block).containsExactlyElementsOf(committed.subList(0, block.size()));
        }

        @Test
        @DisplayName("every account total equals the sum of the detail amounts in its group, exactly, "
                + "so the committed accumulations are arithmetically consistent with the details")
        void everyAccountTotalEqualsTheSumOfItsGroup() throws IOException {
            final List<String> committed = textRecords(REPORT_FIXTURE, REPORT_RECORD_WIDTH);
            BigDecimal group = BigDecimal.ZERO.setScale(MONETARY_SCALE);
            int groups = 0;

            for (final String record : committed) {
                final ReportRecordKind kind = classifyReport(record);
                if (kind == ReportRecordKind.DETAIL) {
                    group = group.add(reportAmount(record));
                } else if (kind == ReportRecordKind.ACCOUNT_TOTAL) {
                    assertThat(reportAmount(record))
                            .as("account total %d must equal the sum of its group", groups)
                            .isEqualByComparingTo(group);
                    group = BigDecimal.ZERO.setScale(MONETARY_SCALE);
                    groups++;
                }
            }

            assertThat(groups).isEqualTo(49);
        }

        @Test
        @DisplayName("the page and grand totals equal their sums plus the final detail amount once "
                + "more, which is the legacy end-of-file double count, pinned rather than corrected")
        void thePageAndGrandTotalsCarryTheLegacyEndOfFileDoubleCount() throws IOException {
            // The legacy report adds the last record's amount a second time when it reaches end of
            // file. That affects the page total of the final page and the grand total, and nothing
            // else - every earlier page total is exact. Asserting the anomaly is what keeps a
            // "corrected" implementation from silently failing byte parity.
            final List<String> committed = textRecords(REPORT_FIXTURE, REPORT_RECORD_WIDTH);
            final List<Integer> pageTotalPositions = new ArrayList<>();
            for (int index = 0; index < committed.size(); index++) {
                if (classifyReport(committed.get(index)) == ReportRecordKind.PAGE_TOTAL) {
                    pageTotalPositions.add(index);
                }
            }
            final int lastPageTotalPosition = pageTotalPositions.get(pageTotalPositions.size() - 1);

            BigDecimal page = BigDecimal.ZERO.setScale(MONETARY_SCALE);
            BigDecimal grand = BigDecimal.ZERO.setScale(MONETARY_SCALE);
            BigDecimal lastDetail = BigDecimal.ZERO.setScale(MONETARY_SCALE);
            int exactPages = 0;

            for (int index = 0; index < committed.size(); index++) {
                final String record = committed.get(index);
                final ReportRecordKind kind = classifyReport(record);
                if (kind == ReportRecordKind.DETAIL) {
                    lastDetail = reportAmount(record);
                    page = page.add(lastDetail);
                    grand = grand.add(lastDetail);
                } else if (kind == ReportRecordKind.PAGE_TOTAL) {
                    if (index == lastPageTotalPosition) {
                        assertThat(reportAmount(record))
                                .as("the final page total double counts the last detail amount")
                                .isEqualByComparingTo(page.add(lastDetail));
                    } else {
                        assertThat(reportAmount(record))
                                .as("page total at record %d must be exact", index)
                                .isEqualByComparingTo(page);
                        exactPages++;
                    }
                    page = BigDecimal.ZERO.setScale(MONETARY_SCALE);
                } else if (kind == ReportRecordKind.GRAND_TOTAL) {
                    assertThat(reportAmount(record))
                            .as("the grand total double counts the last detail amount")
                            .isEqualByComparingTo(grand.add(lastDetail));
                }
            }

            assertThat(exactPages)
                    .as("every page total but the last must be exact")
                    .isEqualTo(pageTotalPositions.size() - 1);
        }

        /**
         * Counts how many records carry a given kind.
         *
         * @param  kinds the classified records
         * @param  kind  the kind to count
         * @return how many records carry it
         */
        private long count(final List<ReportRecordKind> kinds, final ReportRecordKind kind) {
            return kinds.stream().filter(candidate -> candidate == kind).count();
        }
    }

    @Nested
    @DisplayName("the statement, re-emitted record by record")
    final class TheStatement {

        @Test
        @DisplayName("the committed fixture carries 1,262 records of exactly 80 bytes, each closed by "
                + "one line feed and by no carriage return")
        void theCommittedFixtureCarriesOneThousandTwoHundredAndSixtyTwoRecords() throws IOException {
            final List<byte[]> committed = records(STATEMENT_FIXTURE, STATEMENT_RECORD_WIDTH);

            assertThat(committed).hasSize(STATEMENT_RECORD_COUNT);
            assertThat(committed).allSatisfy(record ->
                    assertThat(record).hasSize(STATEMENT_RECORD_WIDTH));
            assertThat(fixtureBytes(STATEMENT_FIXTURE))
                    .hasSize(STATEMENT_RECORD_COUNT * (STATEMENT_RECORD_WIDTH + 1));
            assertThat(new String(fixtureBytes(STATEMENT_FIXTURE), StandardCharsets.US_ASCII))
                    .doesNotContain(String.valueOf(CARRIAGE_RETURN));
        }

        @Test
        @DisplayName("every one of the 1,262 committed records is reproduced byte for byte by the "
                + "production statement templates, with no record left unaccounted for")
        void everyCommittedRecordIsReproducedByTheProductionTemplates() throws IOException {
            final List<String> committed = textRecords(STATEMENT_FIXTURE, STATEMENT_RECORD_WIDTH);
            int positionInStatement = 0;
            int accounted = 0;

            for (int index = 0; index < committed.size(); index++) {
                final String record = committed.get(index);
                if (record.equals(StatementTextTemplates.ST_LINE0_START_BANNER)) {
                    positionInStatement = 0;
                }

                assertThat(reemitStatement(record, positionInStatement))
                        .as("statement record %d (%s) must be reproduced byte for byte", index,
                                classifyStatement(record, positionInStatement))
                        .isEqualTo(record);
                positionInStatement++;
                accounted++;
            }

            assertThat(accounted)
                    .as("every committed record must have been classified and re-emitted")
                    .isEqualTo(STATEMENT_RECORD_COUNT);
        }

        @Test
        @DisplayName("the fixture holds fifty statements, each opened by the start banner and closed "
                + "by the end banner, and the two banners are not the same shape")
        void theFixtureHoldsFiftyBalancedStatements() throws IOException {
            final List<String> committed = textRecords(STATEMENT_FIXTURE, STATEMENT_RECORD_WIDTH);
            long starts = 0;
            long ends = 0;
            for (final String record : committed) {
                if (record.equals(StatementTextTemplates.ST_LINE0_START_BANNER)) {
                    starts++;
                } else if (record.equals(StatementTextTemplates.ST_LINE15_END_BANNER)) {
                    ends++;
                }
            }

            assertThat(starts).isEqualTo(STATEMENT_COUNT);
            assertThat(ends).isEqualTo(STATEMENT_COUNT);
            assertThat(committed.get(0)).isEqualTo(StatementTextTemplates.ST_LINE0_START_BANNER);
            assertThat(committed.get(committed.size() - 1))
                    .isEqualTo(StatementTextTemplates.ST_LINE15_END_BANNER);
            assertThat(StatementTextTemplates.ST_LINE0_START_BANNER)
                    .as("the two banners have different asterisk runs, which is contractual")
                    .isNotEqualTo(StatementTextTemplates.ST_LINE15_END_BANNER);
        }

        @Test
        @DisplayName("every statement's total expenditure equals the sum of that statement's "
                + "transaction amounts, exactly, across all fifty statements")
        void everyStatementTotalEqualsTheSumOfItsTransactions() throws IOException {
            final List<String> committed = textRecords(STATEMENT_FIXTURE, STATEMENT_RECORD_WIDTH);
            BigDecimal running = BigDecimal.ZERO.setScale(MONETARY_SCALE);
            int positionInStatement = 0;
            int totalsChecked = 0;

            for (final String record : committed) {
                if (record.equals(StatementTextTemplates.ST_LINE0_START_BANNER)) {
                    positionInStatement = 0;
                    running = BigDecimal.ZERO.setScale(MONETARY_SCALE);
                }
                final StatementRecordKind kind = classifyStatement(record, positionInStatement);
                if (kind == StatementRecordKind.TRANSACTION) {
                    running = running.add(
                            statementAmount(record, STATEMENT_TRANSACTION_AMOUNT_OFFSET));
                } else if (kind == StatementRecordKind.TOTAL_EXPENDITURE) {
                    assertThat(statementAmount(record, STATEMENT_TRANSACTION_AMOUNT_OFFSET))
                            .as("statement %d: the total must equal the sum of its transactions",
                                    totalsChecked)
                            .isEqualByComparingTo(running);
                    totalsChecked++;
                }
                positionInStatement++;
            }

            assertThat(totalsChecked).isEqualTo(STATEMENT_COUNT);
        }

        @Test
        @DisplayName("the record census matches the committed structure: six rule records and one of "
                + "each fixed record per statement, plus 312 transaction records overall")
        void theRecordCensusMatchesTheCommittedStructure() throws IOException {
            final List<String> committed = textRecords(STATEMENT_FIXTURE, STATEMENT_RECORD_WIDTH);
            final List<StatementRecordKind> kinds = new ArrayList<>();
            int positionInStatement = 0;
            for (final String record : committed) {
                if (record.equals(StatementTextTemplates.ST_LINE0_START_BANNER)) {
                    positionInStatement = 0;
                }
                kinds.add(classifyStatement(record, positionInStatement));
                positionInStatement++;
            }

            assertThat(kinds).hasSize(STATEMENT_RECORD_COUNT);
            assertThat(count(kinds, StatementRecordKind.START_BANNER)).isEqualTo(STATEMENT_COUNT);
            assertThat(count(kinds, StatementRecordKind.END_BANNER)).isEqualTo(STATEMENT_COUNT);
            assertThat(count(kinds, StatementRecordKind.CUSTOMER_NAME)).isEqualTo(STATEMENT_COUNT);
            assertThat(count(kinds, StatementRecordKind.ADDRESS_LINE_1)).isEqualTo(STATEMENT_COUNT);
            assertThat(count(kinds, StatementRecordKind.ADDRESS_LINE_2)).isEqualTo(STATEMENT_COUNT);
            assertThat(count(kinds, StatementRecordKind.ADDRESS_LINE_3)).isEqualTo(STATEMENT_COUNT);
            assertThat(count(kinds, StatementRecordKind.BASIC_DETAILS_HEADING))
                    .isEqualTo(STATEMENT_COUNT);
            assertThat(count(kinds, StatementRecordKind.ACCOUNT_ID)).isEqualTo(STATEMENT_COUNT);
            assertThat(count(kinds, StatementRecordKind.CURRENT_BALANCE)).isEqualTo(STATEMENT_COUNT);
            assertThat(count(kinds, StatementRecordKind.FICO_SCORE)).isEqualTo(STATEMENT_COUNT);
            assertThat(count(kinds, StatementRecordKind.TRANSACTION_SUMMARY_HEADING))
                    .isEqualTo(STATEMENT_COUNT);
            assertThat(count(kinds, StatementRecordKind.TRANSACTION_COLUMN_HEADINGS))
                    .isEqualTo(STATEMENT_COUNT);
            assertThat(count(kinds, StatementRecordKind.TOTAL_EXPENDITURE)).isEqualTo(STATEMENT_COUNT);
            assertThat(count(kinds, StatementRecordKind.RULE))
                    .as("six rule records per statement, all six genuinely emitted")
                    .isEqualTo(6L * STATEMENT_COUNT);
            assertThat(count(kinds, StatementRecordKind.TRANSACTION))
                    .as("the same 312 transactions the report details")
                    .isEqualTo(312);
        }

        /**
         * Counts how many records carry a given kind.
         *
         * @param  kinds the classified records
         * @param  kind  the kind to count
         * @return how many records carry it
         */
        private long count(final List<StatementRecordKind> kinds, final StatementRecordKind kind) {
            return kinds.stream().filter(candidate -> candidate == kind).count();
        }
    }
}
