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
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.api.dto.StatementSummary;
import com.carddemo.domain.Account;
import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.Customer;
import com.carddemo.domain.Transaction;
import com.carddemo.exception.AbendException;
import com.carddemo.service.StatementCrossReferenceSource;
import com.carddemo.service.StatementDataAccessService;
import com.carddemo.service.StatementDataAccessService.StatementFileRequest;
import com.carddemo.service.StatementDataAccessService.StatementFileResponse;
import com.carddemo.service.StatementGenerationService;
import com.carddemo.service.StatementGenerationService.StatementRun;
import com.carddemo.service.StatementLineSummary;
import com.carddemo.service.StatementOutputSink;
import com.carddemo.service.StatementTransactionSource;
import com.carddemo.support.SensitiveValues;
import com.carddemo.support.TestDataFactory;
import com.carddemo.util.StatementHtmlTemplates;
import com.carddemo.util.StatementTextTemplates;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.stubbing.Answer;
import org.mockito.InOrder;

/**
 * Surefire-tier unit test for {@link StatementProcessor}, the step-scoped wrapper that drives the
 * statement generator exactly once per run and refuses to hand on a generation that failed any of the
 * four structural properties the stage promises.
 *
 * <h2>Legacy authority</h2>
 *
 * <p>The behaviour under contract belongs to three legacy members. The statement generator is
 * {@code app/cbl/CBSTM03A.CBL}, 924 lines across 25 procedure paragraphs; its data-access helper is
 * {@code app/cbl/CBSTM03B.CBL}, 230 lines across 14 paragraphs, invoked from the generator at
 * <strong>13 call sites</strong>; and the driving job is {@code app/jcl/CREASTMT.JCL}, whose statement
 * step is {@code STEP040} and whose preceding sort, load and clear steps are {@code STEP010},
 * {@code STEP020} and {@code STEP030}.
 *
 * <p>That stamp is <em>not universal</em> across the estate - 78 members carry it, 3 carry later stamps,
 * all 17 screen definitions differ and 25 carry none at all - so it is a matrix-header string only and is
 * never asserted per member. No test in this file reads a legacy file, and nothing in this file transcribes
 * a legacy source statement; only measured metadata is carried, together with the contract literals that
 * appear byte for byte in program output.
 *
 * <h2>Why the expectations here are all hand-built</h2>
 *
 * <p>Five of the module's forbidden expectation generators are this file's own subject and its direct
 * collaborators: the processor itself, the generation service, the data-access service and the two
 * template classes. The generation service and the data-access service are therefore <em>mocked as
 * collaborators</em>, and the template classes are referenced only as the thing under contract. Every
 * expected 80-byte and 100-byte image in this file is assembled by hand from explicit space, asterisk and
 * hyphen fills; every expected amount mask is assembled by hand from the unscaled value of a
 * {@link BigDecimal} built from a string; every expected zoned-decimal field is overpunched by hand. No
 * template class, generation service, data-access service, processor, record mapper, codec or formatter is
 * ever called to obtain an expected string, an expected width or an expected order. Where a production
 * constant expresses a measured legacy width, this file declares its own constant from the source
 * measurement and then <em>asserts the two agree</em>, which is a contract check rather than a derivation.
 *
 * <h2>Decision-log candidates recorded by this file</h2>
 *
 * <ul>
 *   <li><em>The derived phase order diverges from the dispatcher's clause order.</em> The selection
 *       construct declares six clauses in one order; following the generator's backward re-entries
 *       produces a different execution order, in which the whole work resource is read and tabulated
 *       before the three remaining opens. A test written against the clause order would pass against a
 *       wrong implementation, so this file asserts the derived order and separately asserts that a run
 *       reporting the clause order is <em>refused</em>.</li>
 *   <li><em>The six byte-identical rule lines are never de-duplicated.</em> Only three all-hyphen
 *       80-character constants are declared, yet a rule line is emitted six times per statement. The
 *       emission census is definitive: program lines 492, 494, 498, 500, 502 and 435. They are not
 *       collapsed, factored, looped or emitted once.</li>
 *   <li><em>The malformed table-opening markup literal is emitted unrepaired.</em> It carries a double
 *       space after the tag name and is a continued literal in the source. It is reproduced exactly,
 *       because the byte-equivalence gate compares bytes and not intent.</li>
 *   <li><em>The declared-width conflict is resolved to 100.</em> The job declares the markup data
 *       definition at record length 80 in one step and 100 in the next; the emitting program's own record
 *       is 100, so 100 governs.</li>
 *   <li><em>A record-width line-number correction.</em> The 80-byte statement record is declared at
 *       program line <strong>45</strong>, not line 41 as an earlier planning document cites; the 100-byte
 *       markup record is declared at line 47.</li>
 *   <li><em>A garbled overtyped line exists in the driving job at line 90.</em> Its intent was recovered
 *       from the surrounding data definitions. The corruption is not reproduced here in any form.</li>
 *   <li><em>The job's sort step reprojects 328 of 350 bytes</em>, truncating the processing timestamp by
 *       two bytes. That is a fidelity item and not a defect to repair; it belongs to the job
 *       configuration's integration test and is carried here as metadata only, as is the strict "run only
 *       if every prior step returned zero" gate on the statement step.</li>
 *   <li><em>The customer layout the generator consumes is the 500-byte alternate view</em>, differing from
 *       the canonical copybook only in the naming of the date-of-birth field. It is an alternate view of
 *       the same entity: one entity, one reader, one table - never a second customer type.</li>
 *   <li><em>Two customer fields are decorated for error display elsewhere in the estate but never
 *       validated</em> - the middle name and the second address line, both marked "no edits coded" in the
 *       source. No constraint is attached to either, and nothing here rejects input the legacy accepts.</li>
 *   <li><em>A status-vocabulary discrepancy.</em> An earlier specification section cites raw file statuses
 *       {@code 22} and {@code 35}, but neither is compared anywhere in the estate; the compared set is
 *       exactly {@code 00}, {@code 10} and {@code 23}, with {@code 04} additionally accepted by an open.
 *       No test in this file depends on {@code 22} or {@code 35}.</li>
 *   <li><em>The self-modifying open idiom is modelled only by its resulting state transition.</em> Four of
 *       the six dispatcher clauses reach the shared open paragraph by altering its continuation target
 *       before jumping to it. That is a legacy code-alteration technique with no Java analogue; only the
 *       transition it produces is modelled, and the technique itself is neither emulated nor reproduced.</li>
 * </ul>
 */
@DisplayName("StatementProcessor - the CREASTMT STEP040 statement assembly stage")
class StatementProcessorTest {

    // ================================================================================================
    // Hand-built oracle: measured widths. Declared here from the source measurement, never read back
    // from the class under test. Each is separately asserted to agree with its production counterpart.
    // ================================================================================================

    /** The statement text record, declared at program line 45. */
    private static final int TEXT_RECORD_WIDTH = 80;

    /** The markup statement record, declared at program line 47. */
    private static final int MARKUP_RECORD_WIDTH = 100;

    /** The dispatcher's eight-character control field, declared at program line 67. */
    private static final int SELECTOR_WIDTH = 8;

    /** The dispatcher's clause count: five phase selectors plus the catch-all that exits. */
    private static final int DISPATCHER_CLAUSE_COUNT = 6;

    /** The card table's declared occurrences, at program line 226. */
    private static final int CARD_TABLE_OCCURRENCES = 51;

    /** The per-card transaction table's declared occurrences, at program line 228. */
    private static final int TRANSACTIONS_PER_CARD_OCCURRENCES = 10;

    /** Rule lines emitted per statement, from the emission census. */
    private static final int RULE_LINES_PER_STATEMENT = 6;

    /** The batch timestamp field width. */
    private static final int TIMESTAMP_WIDTH = 26;

    /** The scale every monetary field in the estate carries. */
    private static final int MONETARY_SCALE = 2;

    /** Digits of a nine-integer-digit two-decimal zoned field: nine plus two. */
    private static final int TRANSACTION_AMOUNT_ZONED_WIDTH = 11;

    /** Digits of a ten-integer-digit two-decimal zoned field. */
    private static final int ACCOUNT_AMOUNT_ZONED_WIDTH = 12;

    /** Digits of a four-integer-digit two-decimal zoned field. */
    private static final int RATE_ZONED_WIDTH = 6;

    /** The transaction work record the generator tables, in bytes. */
    private static final int TRANSACTION_WORK_RECORD_WIDTH = 350;

    /** The remainder of that record after its thirty-two-byte key. */
    private static final int TRANSACTION_WORK_REMAINDER_WIDTH = 318;

    /** The customer layout the generator consumes, in bytes. */
    private static final int CUSTOMER_RECORD_WIDTH = 500;

    // ================================================================================================
    // Hand-built oracle: the linkage parameter object's declared field widths.
    // ================================================================================================

    private static final int LINKAGE_DD_WIDTH = 8;

    private static final int LINKAGE_OPERATION_WIDTH = 1;

    private static final int LINKAGE_RETURN_CODE_WIDTH = 2;

    private static final int LINKAGE_KEY_WIDTH = 25;

    private static final int LINKAGE_PAYLOAD_WIDTH = 1000;

    // ================================================================================================
    // Hand-built oracle: the abend context's declared field widths.
    // ================================================================================================

    private static final int ABEND_CODE_WIDTH = 4;

    private static final int ABEND_CULPRIT_WIDTH = 8;

    private static final int ABEND_REASON_WIDTH = 50;

    private static final int ABEND_MESSAGE_WIDTH = 72;

    private static final int ABEND_CONTEXT_WIDTH =
            ABEND_CODE_WIDTH + ABEND_CULPRIT_WIDTH + ABEND_REASON_WIDTH + ABEND_MESSAGE_WIDTH;

    // ================================================================================================
    // Hand-built oracle: fills and the raw status vocabulary actually compared in the estate.
    // ================================================================================================

    private static final String SPACE = " ";

    private static final String ASTERISK = "*";

    private static final String HYPHEN = "-";

    private static final String ZERO = "0";

    /** Accepted by every open and by the priming read. */
    private static final String STATUS_SUCCESS = "00";

    /** The second value an open accepts, so an open is never single-valued. */
    private static final String STATUS_RECORD_LENGTH_MISMATCH = "04";

    /** Exits the read phase forward; never collapsed into the error arm. */
    private static final String STATUS_END_OF_FILE = "10";

    /** The only other status the estate compares; drives the error arm here. */
    private static final String STATUS_RECORD_NOT_FOUND = "23";

    private static final List<String> ACCEPTED_OPEN_STATUSES =
            List.of(STATUS_SUCCESS, STATUS_RECORD_LENGTH_MISMATCH);

    // ================================================================================================
    // Hand-built oracle: the dispatcher's selectors, its clause order, and the derived execution order.
    // ================================================================================================

    private static final String SELECTOR_TRNXFILE = "TRNXFILE";

    private static final String SELECTOR_XREFFILE = "XREFFILE";

    private static final String SELECTOR_CUSTFILE = "CUSTFILE";

    private static final String SELECTOR_ACCTFILE = "ACCTFILE";

    private static final String SELECTOR_READTRNX = "READTRNX";

    /** The catch-all clause's trail marker: any value that is not one of the five phase selectors. */
    private static final String TERMINAL_CLAUSE = "END-OF-RUN";

    /**
     * The clause order the selection construct declares, at program line 298: transaction-input open,
     * cross-reference open, customer open, account open, read-all, then the catch-all that exits.
     */
    private static final List<String> DISPATCHER_CLAUSE_ORDER = List.of(SELECTOR_TRNXFILE,
            SELECTOR_XREFFILE, SELECTOR_CUSTFILE, SELECTOR_ACCTFILE, SELECTOR_READTRNX,
            TERMINAL_CLAUSE);

    /**
     * The order the five phases are actually entered once the backward re-entries at program lines
     * 760/761, 779/780, 797/798 and 851/852 are followed, with the two further internal jumps to the
     * mainline at line 815 and back into the read paragraph at line 840. The whole work resource is read
     * and tabulated before the three remaining opens, which is why this is not the clause order.
     */
    private static final List<String> DERIVED_PHASE_ORDER = List.of(SELECTOR_TRNXFILE,
            SELECTOR_READTRNX, SELECTOR_XREFFILE, SELECTOR_CUSTFILE, SELECTOR_ACCTFILE);

    // ================================================================================================
    // Hand-built oracle: the six operation discriminators of the linkage area's one-character field.
    // ================================================================================================

    private static final String OPERATION_OPEN = "O";

    private static final String OPERATION_CLOSE = "C";

    private static final String OPERATION_READ = "R";

    private static final String OPERATION_READ_KEYED = "K";

    private static final String OPERATION_WRITE = "W";

    private static final String OPERATION_REWRITE = "Z";

    private static final List<String> SIX_OPERATIONS = List.of(OPERATION_OPEN, OPERATION_CLOSE,
            OPERATION_READ, OPERATION_READ_KEYED, OPERATION_WRITE, OPERATION_REWRITE);

    // ================================================================================================
    // Hand-built oracle: the statement text line shapes. Every constant below is assembled from
    // explicit fills at the widths measured in the source; none is read from a template class.
    // ================================================================================================

    /** Fill either side of the start-of-statement banner text. */
    private static final int START_BANNER_FILL_WIDTH = 31;

    /** The start-of-statement banner text field. */
    private static final int START_BANNER_TEXT_WIDTH = 18;

    /** Fill either side of the end-of-statement banner text. */
    private static final int END_BANNER_FILL_WIDTH = 32;

    /** The end-of-statement banner text field. */
    private static final int END_BANNER_TEXT_WIDTH = 16;

    private static final String START_BANNER_TEXT = "START OF STATEMENT";

    private static final String END_BANNER_TEXT = "END OF STATEMENT";

    /** Program line 460: the banner that opens every statement. */
    private static final String START_BANNER_LINE = ASTERISK.repeat(START_BANNER_FILL_WIDTH)
            + START_BANNER_TEXT + ASTERISK.repeat(START_BANNER_FILL_WIDTH);

    /** Program line 437: the banner that closes every statement. */
    private static final String END_BANNER_LINE = ASTERISK.repeat(END_BANNER_FILL_WIDTH)
            + END_BANNER_TEXT + ASTERISK.repeat(END_BANNER_FILL_WIDTH);

    /** The all-hyphen rule line, emitted six times per statement and never de-duplicated. */
    private static final String RULE_LINE = HYPHEN.repeat(TEXT_RECORD_WIDTH);

    private static final int NAME_FIELD_WIDTH = 75;

    private static final int ADDRESS_FIELD_WIDTH = 50;

    private static final int LABEL_FIELD_WIDTH = 20;

    private static final int VALUE_FIELD_WIDTH = 20;

    private static final int BASIC_DETAILS_TEXT_WIDTH = 14;

    private static final int BASIC_DETAILS_FILL_WIDTH = 33;

    private static final int SUMMARY_TEXT_WIDTH = 20;

    private static final int SUMMARY_FILL_WIDTH = 30;

    private static final int AMOUNT_MASK_WIDTH = 13;

    private static final int AMOUNT_MASK_INTEGER_DIGITS = 9;

    private static final int DETAIL_IDENTIFIER_WIDTH = 16;

    private static final int DETAIL_DESCRIPTION_WIDTH = 49;

    private static final int TOTAL_LABEL_WIDTH = 10;

    private static final int TOTAL_FILL_WIDTH = 56;

    private static final String CURRENCY_SYMBOL = "$";

    private static final String BASIC_DETAILS_TEXT = "Basic Details";

    private static final String SUMMARY_TEXT = "TRANSACTION SUMMARY ";

    private static final String ACCOUNT_LABEL = "Account ID         :";

    private static final String BALANCE_LABEL = "Current Balance    :";

    private static final String SCORE_LABEL = "FICO Score         :";

    private static final String TOTAL_LABEL = "Total EXP:";

    private static final String COLUMN_IDENTIFIER_HEADING = "Tran ID         ";

    private static final String COLUMN_DETAILS_HEADING = "Tran Details    ";

    private static final String COLUMN_AMOUNT_HEADING = "  Tran Amount";

    /** Program line 490 shape: a 33-space fill, a 14-character heading, a 33-space fill. */
    private static final String BASIC_DETAILS_LINE = SPACE.repeat(BASIC_DETAILS_FILL_WIDTH)
            + padRight(BASIC_DETAILS_TEXT, BASIC_DETAILS_TEXT_WIDTH)
            + SPACE.repeat(BASIC_DETAILS_FILL_WIDTH);

    /** Program line 497 shape: a 30-space fill, a 20-character heading, a 30-space fill. */
    private static final String SUMMARY_LINE = SPACE.repeat(SUMMARY_FILL_WIDTH)
            + padRight(SUMMARY_TEXT, SUMMARY_TEXT_WIDTH) + SPACE.repeat(SUMMARY_FILL_WIDTH);

    /** Program line 500 shape: three column labels of 16, 51 and 13 characters. */
    private static final String COLUMN_HEADING_LINE = padRight(COLUMN_IDENTIFIER_HEADING, 16)
            + padRight(COLUMN_DETAILS_HEADING, 51) + padRight(COLUMN_AMOUNT_HEADING, 13);

    // ================================================================================================
    // Hand-built oracle: the zoned-decimal overpunch tables, transcribed from the convention and not
    // from any codec. The final byte of a zoned field carries both its low-order digit and its sign.
    // ================================================================================================

    private static final String POSITIVE_OVERPUNCH = "{ABCDEFGHI";

    private static final String NEGATIVE_OVERPUNCH = "}JKLMNOPQR";

    /**
     * Left-justifies a value in a fixed field and space-pads it to width, which is what a legacy
     * alphanumeric move does. Rejects an over-long value rather than silently truncating, so a mistake in
     * a hand-built expectation fails loudly instead of producing a plausible wrong image.
     *
     * @param value the value to place
     * @param width the field width in characters
     * @return the value padded to exactly {@code width} characters
     */
    private static String padRight(final String value, final int width) {
        if (value.length() > width) {
            throw new IllegalArgumentException(
                    "hand-built field of width " + width + " cannot hold " + value.length()
                            + " characters");
        }
        return value + SPACE.repeat(width - value.length());
    }

    /**
     * Right-justifies a value in a fixed field and zero-fills it on the left.
     *
     * @param value the value to place
     * @param width the field width in characters
     * @return the value zero-filled to exactly {@code width} characters
     */
    private static String padLeftWithZeros(final String value, final int width) {
        if (value.length() > width) {
            throw new IllegalArgumentException(
                    "hand-built field of width " + width + " cannot hold " + value.length()
                            + " characters");
        }
        return ZERO.repeat(width - value.length()) + value;
    }

    /**
     * Answers the unsigned digit string of a monetary value at a fixed digit count, taken from the
     * value's unscaled representation so that no scaling operation is performed anywhere in this file.
     * The scale is asserted rather than coerced, because coercion is exactly the mistake this file must
     * not make: the estate declares no rounding directive at all, so no rounding is ever applied here.
     *
     * @param amount the value, which must already carry the estate's monetary scale
     * @param width  the total digit count of the target zoned or edited field
     * @return exactly {@code width} digit characters
     */
    private static String unsignedDigitsOf(final BigDecimal amount, final int width) {
        if (amount.scale() != MONETARY_SCALE) {
            throw new IllegalArgumentException("a hand-built monetary expectation must be constructed at"
                    + " scale " + MONETARY_SCALE + " from a string literal, but this one carries scale "
                    + amount.scale());
        }
        return padLeftWithZeros(amount.unscaledValue().abs().toString(), width);
    }

    /**
     * Encodes a monetary value as a legacy zoned-decimal field, overpunching the sign into the final
     * byte by hand from the transcribed convention.
     *
     * @param amount the value, at the estate's monetary scale
     * @param width  the field width in bytes, being the field's total digit count
     * @return the zoned image, of exactly {@code width} bytes
     */
    private static String zonedDecimalOf(final BigDecimal amount, final int width) {
        final String digits = unsignedDigitsOf(amount, width);
        final int lowOrderDigit = digits.charAt(width - 1) - '0';
        final String table = (amount.signum() < 0) ? NEGATIVE_OVERPUNCH : POSITIVE_OVERPUNCH;
        return digits.substring(0, width - 1) + table.charAt(lowOrderDigit);
    }

    /**
     * Builds the balance line's edited amount by hand: nine integer digits with no zero suppression, an
     * inserted decimal point, two fraction digits and a trailing sign position that is a hyphen when the
     * value is negative and a space otherwise.
     *
     * @param amount the value, at the estate's monetary scale
     * @return the edited image, of exactly thirteen characters
     */
    private static String editedAmountWithoutSuppression(final BigDecimal amount) {
        final String digits = unsignedDigitsOf(amount, TRANSACTION_AMOUNT_ZONED_WIDTH);
        return digits.substring(0, AMOUNT_MASK_INTEGER_DIGITS) + "."
                + digits.substring(AMOUNT_MASK_INTEGER_DIGITS) + signPositionOf(amount);
    }

    /**
     * Builds the detail and total lines' edited amount by hand: the same shape, but with every leading
     * zero of the integer part replaced by a space, which is what the zero-suppression insertion
     * character does. Suppression stops at the first significant digit and, when the integer part is
     * entirely zero, consumes all nine positions.
     *
     * @param amount the value, at the estate's monetary scale
     * @return the edited image, of exactly thirteen characters
     */
    private static String editedAmountWithSuppression(final BigDecimal amount) {
        final String digits = unsignedDigitsOf(amount, TRANSACTION_AMOUNT_ZONED_WIDTH);
        final StringBuilder integerPart =
                new StringBuilder(digits.substring(0, AMOUNT_MASK_INTEGER_DIGITS));
        for (int position = 0; position < integerPart.length(); position++) {
            if (integerPart.charAt(position) != '0') {
                break;
            }
            integerPart.setCharAt(position, ' ');
        }
        return integerPart + "." + digits.substring(AMOUNT_MASK_INTEGER_DIGITS)
                + signPositionOf(amount);
    }

    /**
     * Answers the trailing sign position of an edited amount field.
     *
     * @param amount the value
     * @return a hyphen when the value is negative, a space otherwise
     */
    private static String signPositionOf(final BigDecimal amount) {
        return (amount.signum() < 0) ? HYPHEN : SPACE;
    }

    /**
     * Measures a value in encoded bytes, which is the only measurement a fixed-width record contract
     * admits. Nothing in this file measures a record with a character count and nothing trims.
     *
     * @param value the value to measure
     * @return the number of bytes the value occupies when encoded
     */
    private static int encodedWidthOf(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    // ================================================================================================
    // Hand-built oracle: the markup literal family declared from program line 148 onwards. These are
    // contract literals that appear byte for byte in program output, so reproducing them is required.
    // Each is transcribed once here and space-padded to the 100-byte record width at the point of use;
    // none is read from a template class. No templating engine is used, because a template engine
    // introduces whitespace and ordering variability that the byte-equivalence gate would reject.
    // ================================================================================================

    private static final int MARKUP_FIXED_TEMPLATE_COUNT = 34;

    private static final String MARKUP_DOCTYPE = "<!DOCTYPE html>";

    private static final String MARKUP_HTML_OPEN = "<html lang=\"en\">";

    private static final String MARKUP_HEAD_OPEN = "<head>";

    private static final String MARKUP_META = "<meta charset=\"utf-8\">";

    private static final String MARKUP_TITLE = "<title>HTML Table Layout</title>";

    private static final String MARKUP_HEAD_CLOSE = "</head>";

    private static final String MARKUP_BODY_OPEN = "<body style=\"margin:0px;\">";

    /**
     * The malformed table-opening literal. It carries a double space after the tag name and is a
     * continued literal in the source. It is emitted unrepaired: the whitespace is not normalised, the
     * tag is not closed differently, and nothing is tidied.
     */
    private static final String MARKUP_TABLE_OPEN = "<table  align=\"center\" frame=\"box\" "
            + "style=\"width:70%; font:12px Segoe UI,sans-serif;\">";

    private static final String MARKUP_ROW_OPEN = "<tr>";

    private static final String MARKUP_ROW_CLOSE = "</tr>";

    private static final String MARKUP_CELL_OPEN = "<td>";

    private static final String MARKUP_CELL_CLOSE = "</td>";

    private static final String MARKUP_BANNER_CELL =
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#1d1d96b3;\">";

    private static final String MARKUP_BANK_CELL =
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#FFAF33;\">";

    private static final String MARKUP_BANK_NAME = "<p style=\"font-size:16px\">Bank of XYZ</p>";

    private static final String MARKUP_BANK_STREET = "<p>410 Terry Ave N</p>";

    private static final String MARKUP_BANK_CITY = "<p>Seattle WA 99999</p>";

    private static final String MARKUP_PLAIN_CELL =
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#f2f2f2;\">";

    private static final String MARKUP_HEADING_CELL = "<td colspan=\"3\" "
            + "style=\"padding:0px 5px;background-color:#33FFD1; text-align:center;\">";

    private static final String MARKUP_BASIC_HEADING =
            "<p style=\"font-size:16px\">Basic Details</p>";

    private static final String MARKUP_SUMMARY_HEADING =
            "<p style=\"font-size:16px\">Transaction Summary</p>";

    private static final String MARKUP_IDENTIFIER_COLUMN_CELL = "<td style=\"width:25%; "
            + "padding:0px 5px; background-color:#33FF5E; text-align:left;\">";

    private static final String MARKUP_IDENTIFIER_COLUMN = "<p style=\"font-size:16px\">Tran ID</p>";

    private static final String MARKUP_DETAILS_COLUMN_CELL = "<td style=\"width:55%; "
            + "padding:0px 5px; background-color:#33FF5E; text-align:left;\">";

    private static final String MARKUP_DETAILS_COLUMN =
            "<p style=\"font-size:16px\">Tran Details</p>";

    private static final String MARKUP_AMOUNT_COLUMN_CELL = "<td style=\"width:20%; "
            + "padding:0px 5px; background-color:#33FF5E; text-align:right;\">";

    private static final String MARKUP_AMOUNT_COLUMN = "<p style=\"font-size:16px\">Amount</p>";

    private static final String MARKUP_IDENTIFIER_VALUE_CELL = "<td style=\"width:25%; "
            + "padding:0px 5px; background-color:#f2f2f2; text-align:left;\">";

    private static final String MARKUP_DETAILS_VALUE_CELL = "<td style=\"width:55%; "
            + "padding:0px 5px; background-color:#f2f2f2; text-align:left;\">";

    private static final String MARKUP_AMOUNT_VALUE_CELL = "<td style=\"width:20%; "
            + "padding:0px 5px; background-color:#f2f2f2; text-align:right;\">";

    private static final String MARKUP_END_HEADING = "<h3>End of Statement</h3>";

    private static final String MARKUP_TABLE_CLOSE = "</table>";

    private static final String MARKUP_BODY_CLOSE = "</body>";

    private static final String MARKUP_HTML_CLOSE = "</html>";

    /** The 34 fixed literals, in the order they are declared. */
    private static final List<String> MARKUP_FIXED_TEMPLATES = List.of(MARKUP_DOCTYPE,
            MARKUP_HTML_OPEN, MARKUP_HEAD_OPEN, MARKUP_META, MARKUP_TITLE, MARKUP_HEAD_CLOSE,
            MARKUP_BODY_OPEN, MARKUP_TABLE_OPEN, MARKUP_ROW_OPEN, MARKUP_ROW_CLOSE, MARKUP_CELL_OPEN,
            MARKUP_CELL_CLOSE, MARKUP_BANNER_CELL, MARKUP_BANK_CELL, MARKUP_BANK_NAME,
            MARKUP_BANK_STREET, MARKUP_BANK_CITY, MARKUP_PLAIN_CELL, MARKUP_HEADING_CELL,
            MARKUP_BASIC_HEADING, MARKUP_SUMMARY_HEADING, MARKUP_IDENTIFIER_COLUMN_CELL,
            MARKUP_IDENTIFIER_COLUMN, MARKUP_DETAILS_COLUMN_CELL, MARKUP_DETAILS_COLUMN,
            MARKUP_AMOUNT_COLUMN_CELL, MARKUP_AMOUNT_COLUMN, MARKUP_IDENTIFIER_VALUE_CELL,
            MARKUP_DETAILS_VALUE_CELL, MARKUP_AMOUNT_VALUE_CELL, MARKUP_END_HEADING,
            MARKUP_TABLE_CLOSE, MARKUP_BODY_CLOSE, MARKUP_HTML_CLOSE);

    // ------------------------------------------------------------------------------------------------
    // The two substitutable markup group items, declared at program lines 212-216 and 217-220.
    // ------------------------------------------------------------------------------------------------

    private static final int ACCOUNT_HEADING_PREFIX_WIDTH = 34;

    private static final int ACCOUNT_HEADING_VALUE_WIDTH = 20;

    private static final int ACCOUNT_HEADING_SUFFIX_WIDTH = 5;

    private static final int NAME_PARAGRAPH_PREFIX_WIDTH = 26;

    private static final int NAME_PARAGRAPH_VALUE_WIDTH = 50;

    private static final String ACCOUNT_HEADING_PREFIX = "<h3>Statement for Account Number: ";

    private static final String ACCOUNT_HEADING_SUFFIX = "</h3>";

    private static final String NAME_PARAGRAPH_PREFIX = "<p style=\"font-size:16px\">";

    /**
     * Builds the account-number heading group item by hand: a 34-character prefix, a 20-character value
     * field and a 5-character suffix, the whole padded to the 100-byte record width.
     *
     * @param accountIdentifier the value to substitute
     * @return the markup record, of exactly 100 bytes
     */
    private static String accountHeadingRecord(final String accountIdentifier) {
        return padRight(ACCOUNT_HEADING_PREFIX + padRight(accountIdentifier,
                ACCOUNT_HEADING_VALUE_WIDTH) + ACCOUNT_HEADING_SUFFIX, MARKUP_RECORD_WIDTH);
    }

    /**
     * Builds the name paragraph group item by hand: a 26-character prefix and a 50-character value
     * field, the whole padded to the 100-byte record width.
     *
     * @param name the value to substitute
     * @return the markup record, of exactly 100 bytes
     */
    private static String nameParagraphRecord(final String name) {
        return padRight(NAME_PARAGRAPH_PREFIX + padRight(name, NAME_PARAGRAPH_VALUE_WIDTH),
                MARKUP_RECORD_WIDTH);
    }

    /**
     * Pads a transcribed markup literal to the 100-byte record width.
     *
     * @param literal the transcribed literal
     * @return the markup record, of exactly 100 bytes
     */
    private static String markupRecord(final String literal) {
        return padRight(literal, MARKUP_RECORD_WIDTH);
    }

    /**
     * Builds one of the 100-byte work-area paragraphs the name, address and basics blocks assemble at
     * runtime. Those three work areas are declared at the record width and are filled by concatenation
     * rather than being fixed literals, so this file asserts their declared width and their position in
     * the emission order rather than transcribing a runtime concatenation as though it were a constant.
     *
     * @param body the paragraph body
     * @return the markup record, of exactly 100 bytes
     */
    private static String markupWorkParagraph(final String body) {
        return padRight("<p>" + body + "</p>", MARKUP_RECORD_WIDTH);
    }

    // ================================================================================================
    // Hand-built oracle: deterministic sample values. Every one is synthetic; none is a credential and
    // none is derived from a clock read at test time.
    // ================================================================================================

    private static final String SAMPLE_ACCOUNT_ID = "00000000011";

    private static final String SAMPLE_CUSTOMER_ID = "000000001";

    private static final String SAMPLE_CARD_NUMBER = "9990000000000001";

    private static final String SAMPLE_SECOND_CARD_NUMBER = "9990000000000002";

    private static final String SAMPLE_THIRD_CARD_NUMBER = "9990000000000003";

    private static final String SAMPLE_TRANSACTION_ID = "0000000000000001";

    private static final String SAMPLE_NAME = "SMITH JOHN A";

    private static final String SAMPLE_ADDRESS_LINE_1 = "410 TERRY AVE N";

    private static final String SAMPLE_ADDRESS_LINE_2 = "SUITE 100";

    private static final String SAMPLE_ADDRESS_LINE_3 = "SEATTLE WA 99999";

    private static final String SAMPLE_SCORE = "789";

    private static final String SAMPLE_DESCRIPTION = "POS PURCHASE";

    private static final BigDecimal SAMPLE_BALANCE = new BigDecimal("1234.56");

    private static final BigDecimal SAMPLE_DETAIL_AMOUNT = new BigDecimal("-45.67");

    private static final BigDecimal SAMPLE_TOTAL_AMOUNT = new BigDecimal("1188.89");

    private static final BigDecimal SAMPLE_ACCOUNT_AMOUNT = new BigDecimal("9876543210.99");

    private static final BigDecimal SAMPLE_RATE = new BigDecimal("1234.56");

    /** The 26-blank processing timestamp every row of the measured daily-transaction fixture carries. */
    private static final String BLANK_TIMESTAMP = SPACE.repeat(TIMESTAMP_WIDTH);

    /** A deterministic instant; nothing in this file reads a live clock. */
    private static final Instant PINNED_INSTANT = Instant.parse("2022-06-10T19:27:53Z");

    /** The only clock this file uses. */
    private static final Clock FIXED_CLOCK = Clock.fixed(PINNED_INSTANT, ZoneOffset.UTC);

    /** Strict so that an impossible calendar date fails rather than being silently normalised. */
    private static final DateTimeFormatter STRICT_DATE = DateTimeFormatter
            .ofPattern("uuuu-MM-dd", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);

    /**
     * The first twenty-two positions of the batch timestamp form: a hyphen at position 11 rather than a
     * space, dots at positions 14, 17 and 20 rather than colons and a period, and a two-digit hundredths
     * field at positions 21 and 22.
     */
    private static final DateTimeFormatter BATCH_TIMESTAMP_HEAD = DateTimeFormatter
            .ofPattern("uuuu-MM-dd-HH.mm.ss.SS", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT).withZone(ZoneOffset.UTC);

    /** Positions 23 to 26 of the batch timestamp form, which are a fixed literal. */
    private static final String BATCH_TIMESTAMP_TRAILER = "0000";

    /**
     * Renders the twenty-six-character batch timestamp form by hand from a fixed clock. Nothing in this
     * file calls a live clock, a current-time method or a random source.
     *
     * @param clock the fixed clock to read
     * @return the batch timestamp, of exactly twenty-six characters
     */
    private static String batchTimestampOf(final Clock clock) {
        return BATCH_TIMESTAMP_HEAD.format(clock.instant()) + BATCH_TIMESTAMP_TRAILER;
    }

    // ================================================================================================
    // Hand-built oracle: the per-statement text emission sequence.
    // ================================================================================================

    /** Emissions the statement head contributes after the opening banner: fifteen, five of them rules. */
    private static final int HEAD_EMISSION_COUNT = 15;

    /** Emissions the statement tail contributes: the sixth rule, the total line and the closing banner. */
    private static final int TAIL_EMISSION_COUNT = 3;

    /** Markup records the header block contributes. */
    private static final int MARKUP_HEADER_RECORD_COUNT = 22;

    /** Markup records the name, address and basics block contributes. */
    private static final int MARKUP_BASICS_RECORD_COUNT = 34;

    /** Markup records each transaction contributes. */
    private static final int MARKUP_TRANSACTION_RECORD_COUNT = 11;

    /** Markup records the tail block contributes. */
    private static final int MARKUP_TAIL_RECORD_COUNT = 8;

    /** The zero-based index at which the malformed table-opening literal is emitted. */
    private static final int MALFORMED_LITERAL_INDEX = 7;

    /** The zero-based index at which the account-number heading group item is emitted. */
    private static final int ACCOUNT_HEADING_INDEX = 10;

    /** The zero-based index at which the name paragraph group item is emitted. */
    private static final int NAME_PARAGRAPH_INDEX = 22;

    private static String nameLine(final String name) {
        return padRight(name, NAME_FIELD_WIDTH) + SPACE.repeat(TEXT_RECORD_WIDTH - NAME_FIELD_WIDTH);
    }

    private static String addressLine(final String address) {
        return padRight(address, ADDRESS_FIELD_WIDTH)
                + SPACE.repeat(TEXT_RECORD_WIDTH - ADDRESS_FIELD_WIDTH);
    }

    private static String thirdAddressLine(final String address) {
        return padRight(address, TEXT_RECORD_WIDTH);
    }

    private static String labelledValueLine(final String label, final String value) {
        return padRight(label, LABEL_FIELD_WIDTH) + padRight(value, VALUE_FIELD_WIDTH)
                + SPACE.repeat(TEXT_RECORD_WIDTH - LABEL_FIELD_WIDTH - VALUE_FIELD_WIDTH);
    }

    private static String balanceLine(final BigDecimal balance) {
        return padRight(BALANCE_LABEL, LABEL_FIELD_WIDTH) + editedAmountWithoutSuppression(balance)
                + SPACE.repeat(TEXT_RECORD_WIDTH - LABEL_FIELD_WIDTH - AMOUNT_MASK_WIDTH);
    }

    private static String detailLine(final String identifier, final String description,
            final BigDecimal amount) {
        return padRight(identifier, DETAIL_IDENTIFIER_WIDTH) + SPACE
                + padRight(description, DETAIL_DESCRIPTION_WIDTH) + CURRENCY_SYMBOL
                + editedAmountWithSuppression(amount);
    }

    private static String totalLine(final BigDecimal total) {
        return padRight(TOTAL_LABEL, TOTAL_LABEL_WIDTH) + SPACE.repeat(TOTAL_FILL_WIDTH)
                + CURRENCY_SYMBOL + editedAmountWithSuppression(total);
    }

    /**
     * Assembles the text records of one statement in the order the statement-creation paragraph emits
     * them, entirely by hand: the opening banner, then the fifteen head emissions of which five are rule
     * lines, then one detail line per transaction, then the sixth rule line, the total line and the
     * closing banner.
     *
     * @param transactionCount how many transactions the statement carries
     * @return the statement's text records, in emission order
     */
    private static List<String> statementTextArtifact(final int transactionCount) {
        final List<String> records = new ArrayList<>();
        records.add(START_BANNER_LINE);
        records.add(nameLine(SAMPLE_NAME));
        records.add(addressLine(SAMPLE_ADDRESS_LINE_1));
        records.add(addressLine(SAMPLE_ADDRESS_LINE_2));
        records.add(thirdAddressLine(SAMPLE_ADDRESS_LINE_3));
        records.add(RULE_LINE);
        records.add(BASIC_DETAILS_LINE);
        records.add(RULE_LINE);
        records.add(labelledValueLine(ACCOUNT_LABEL, SAMPLE_ACCOUNT_ID));
        records.add(balanceLine(SAMPLE_BALANCE));
        records.add(labelledValueLine(SCORE_LABEL, SAMPLE_SCORE));
        records.add(RULE_LINE);
        records.add(SUMMARY_LINE);
        records.add(RULE_LINE);
        records.add(COLUMN_HEADING_LINE);
        records.add(RULE_LINE);
        for (int ordinal = 0; ordinal < transactionCount; ordinal++) {
            records.add(detailLine(SAMPLE_TRANSACTION_ID, SAMPLE_DESCRIPTION, SAMPLE_DETAIL_AMOUNT));
        }
        records.add(RULE_LINE);
        records.add(totalLine(SAMPLE_TOTAL_AMOUNT));
        records.add(END_BANNER_LINE);
        return List.copyOf(records);
    }

    /**
     * Assembles the markup records of one statement in emission order, entirely by hand: the header
     * block, the name, address and basics block, one block per transaction, and the tail block.
     *
     * @param transactionCount how many transactions the statement carries
     * @return the statement's markup records, in emission order
     */
    private static List<String> statementMarkupArtifact(final int transactionCount) {
        final List<String> records = new ArrayList<>();
        records.add(markupRecord(MARKUP_DOCTYPE));
        records.add(markupRecord(MARKUP_HTML_OPEN));
        records.add(markupRecord(MARKUP_HEAD_OPEN));
        records.add(markupRecord(MARKUP_META));
        records.add(markupRecord(MARKUP_TITLE));
        records.add(markupRecord(MARKUP_HEAD_CLOSE));
        records.add(markupRecord(MARKUP_BODY_OPEN));
        records.add(markupRecord(MARKUP_TABLE_OPEN));
        records.add(markupRecord(MARKUP_ROW_OPEN));
        records.add(markupRecord(MARKUP_BANNER_CELL));
        records.add(accountHeadingRecord(SAMPLE_ACCOUNT_ID));
        records.add(markupRecord(MARKUP_CELL_CLOSE));
        records.add(markupRecord(MARKUP_ROW_CLOSE));
        records.add(markupRecord(MARKUP_ROW_OPEN));
        records.add(markupRecord(MARKUP_BANK_CELL));
        records.add(markupRecord(MARKUP_BANK_NAME));
        records.add(markupRecord(MARKUP_BANK_STREET));
        records.add(markupRecord(MARKUP_BANK_CITY));
        records.add(markupRecord(MARKUP_CELL_CLOSE));
        records.add(markupRecord(MARKUP_ROW_CLOSE));
        records.add(markupRecord(MARKUP_ROW_OPEN));
        records.add(markupRecord(MARKUP_PLAIN_CELL));
        records.add(nameParagraphRecord(SAMPLE_NAME));
        records.add(markupWorkParagraph(SAMPLE_ADDRESS_LINE_1));
        records.add(markupWorkParagraph(SAMPLE_ADDRESS_LINE_2));
        records.add(markupWorkParagraph(SAMPLE_ADDRESS_LINE_3));
        records.add(markupRecord(MARKUP_CELL_CLOSE));
        records.add(markupRecord(MARKUP_ROW_CLOSE));
        records.add(markupRecord(MARKUP_ROW_OPEN));
        records.add(markupRecord(MARKUP_HEADING_CELL));
        records.add(markupRecord(MARKUP_BASIC_HEADING));
        records.add(markupRecord(MARKUP_CELL_CLOSE));
        records.add(markupRecord(MARKUP_ROW_CLOSE));
        records.add(markupRecord(MARKUP_ROW_OPEN));
        records.add(markupRecord(MARKUP_PLAIN_CELL));
        records.add(markupWorkParagraph(ACCOUNT_LABEL + SPACE + SAMPLE_ACCOUNT_ID));
        records.add(markupWorkParagraph(
                BALANCE_LABEL + SPACE + editedAmountWithoutSuppression(SAMPLE_BALANCE)));
        records.add(markupWorkParagraph(SCORE_LABEL + SPACE + SAMPLE_SCORE));
        records.add(markupRecord(MARKUP_CELL_CLOSE));
        records.add(markupRecord(MARKUP_ROW_CLOSE));
        records.add(markupRecord(MARKUP_ROW_OPEN));
        records.add(markupRecord(MARKUP_HEADING_CELL));
        records.add(markupRecord(MARKUP_SUMMARY_HEADING));
        records.add(markupRecord(MARKUP_CELL_CLOSE));
        records.add(markupRecord(MARKUP_ROW_CLOSE));
        records.add(markupRecord(MARKUP_ROW_OPEN));
        records.add(markupRecord(MARKUP_IDENTIFIER_COLUMN_CELL));
        records.add(markupRecord(MARKUP_IDENTIFIER_COLUMN));
        records.add(markupRecord(MARKUP_CELL_CLOSE));
        records.add(markupRecord(MARKUP_DETAILS_COLUMN_CELL));
        records.add(markupRecord(MARKUP_DETAILS_COLUMN));
        records.add(markupRecord(MARKUP_CELL_CLOSE));
        records.add(markupRecord(MARKUP_AMOUNT_COLUMN_CELL));
        records.add(markupRecord(MARKUP_AMOUNT_COLUMN));
        records.add(markupRecord(MARKUP_CELL_CLOSE));
        records.add(markupRecord(MARKUP_ROW_CLOSE));
        for (int ordinal = 0; ordinal < transactionCount; ordinal++) {
            records.addAll(markupTransactionBlock());
        }
        records.add(markupRecord(MARKUP_ROW_OPEN));
        records.add(markupRecord(MARKUP_BANNER_CELL));
        records.add(markupRecord(MARKUP_END_HEADING));
        records.add(markupRecord(MARKUP_CELL_CLOSE));
        records.add(markupRecord(MARKUP_ROW_CLOSE));
        records.add(markupRecord(MARKUP_TABLE_CLOSE));
        records.add(markupRecord(MARKUP_BODY_CLOSE));
        records.add(markupRecord(MARKUP_HTML_CLOSE));
        return List.copyOf(records);
    }

    /**
     * Assembles the eleven markup records one transaction contributes, in emission order.
     *
     * @return the transaction's markup records
     */
    private static List<String> markupTransactionBlock() {
        return List.of(markupRecord(MARKUP_ROW_OPEN), markupRecord(MARKUP_IDENTIFIER_VALUE_CELL),
                markupWorkParagraph(SAMPLE_TRANSACTION_ID), markupRecord(MARKUP_CELL_CLOSE),
                markupRecord(MARKUP_DETAILS_VALUE_CELL), markupWorkParagraph(SAMPLE_DESCRIPTION),
                markupRecord(MARKUP_CELL_CLOSE), markupRecord(MARKUP_AMOUNT_VALUE_CELL),
                markupWorkParagraph(editedAmountWithSuppression(SAMPLE_DETAIL_AMOUNT)),
                markupRecord(MARKUP_CELL_CLOSE), markupRecord(MARKUP_ROW_CLOSE));
    }

    /**
     * Builds the 350-byte transaction work-record image the generator tables, entirely by hand: a
     * thirty-two-byte key of card number and transaction identifier, then the 318-byte remainder, whose
     * amount is a hand-overpunched zoned field and whose processing timestamp is the 26 blanks the
     * measured fixture carries.
     *
     * @param cardNumber    the sixteen-byte card number
     * @param transactionId the sixteen-byte transaction identifier
     * @param amount        the transaction amount, at the estate's monetary scale
     * @return the record image, of exactly 350 bytes
     */
    private static String transactionWorkRecord(final String cardNumber, final String transactionId,
            final BigDecimal amount) {
        final String remainder = padRight("01", 2) + padLeftWithZeros("5", 4)
                + padRight("POS TERM", 10) + padRight(SAMPLE_DESCRIPTION, 100)
                + zonedDecimalOf(amount, TRANSACTION_AMOUNT_ZONED_WIDTH)
                + padLeftWithZeros("123456789", 9) + padRight("SAMPLE MERCHANT", 50)
                + padRight("SEATTLE", 50) + padRight("99999", 10)
                + padRight("2022-06-10 19:27:53.000000", TIMESTAMP_WIDTH) + BLANK_TIMESTAMP
                + SPACE.repeat(20);
        return padRight(cardNumber, 16) + padRight(transactionId, 16) + remainder;
    }

    // ================================================================================================
    // Hand-built oracle: the per-card accumulation the read phase performs.
    // ================================================================================================

    /**
     * The outcome of tabulating a run of card numbers: how many card slots were filled, and the
     * transaction count stored against each.
     *
     * @param cards         the number of card slots filled
     * @param countsPerCard the transaction count stored for each filled slot, in slot order
     */
    private record CardTabulation(int cards, List<Integer> countsPerCard) {

        /**
         * @return the total transactions tabulated across every slot
         */
        int totalTransactions() {
            return this.countsPerCard.stream().mapToInt(Integer::intValue).sum();
        }
    }

    /**
     * Re-implements the read phase's grouping rule by hand, so that the expected slot count and the
     * expected per-slot counts are derived from the rule and not from the code under contract. When the
     * incoming card number equals the remembered one the per-card counter is incremented; otherwise the
     * completed count is stored against the current slot, the slot advances and the counter resets to
     * one. The exit stores the count for the final slot.
     *
     * @param cardNumbersInReadOrder the card numbers the work resource presents, in read order
     * @return the tabulation
     */
    private static CardTabulation tabulate(final List<String> cardNumbersInReadOrder) {
        if (cardNumbersInReadOrder.isEmpty()) {
            return new CardTabulation(0, List.of());
        }
        final List<Integer> counts = new ArrayList<>();
        String remembered = cardNumbersInReadOrder.get(0);
        int slot = 1;
        int count = 0;
        for (final String cardNumber : cardNumbersInReadOrder) {
            if (remembered.equals(cardNumber)) {
                count++;
            } else {
                counts.add(count);
                slot++;
                count = 1;
            }
            remembered = cardNumber;
        }
        counts.add(count);
        return new CardTabulation(slot, List.copyOf(counts));
    }

    // ================================================================================================
    // Collaborators. The generation service and the data-access service are mocked; the processor is
    // constructed with plain new. No Spring context, no container, no persistence, no base class.
    // ================================================================================================

    private StatementGenerationService statementGenerationService;

    private StatementDataAccessService statementDataAccessService;

    private StatementCrossReferenceSource crossReferenceSource;

    private MeterRegistry meterRegistry;

    private UnaryOperator<String> revealer;

    private UnaryOperator<String> sealer;

    private StatementProcessor processor;

    /** The raw statuses the stubbed file handler answers, in order; a plain success once exhausted. */
    private final Deque<String> plannedStatuses = new ArrayDeque<>();

    /** Ordering journal for the abend path, where a diagnostic precedes the exception. */
    private final List<String> journal = new ArrayList<>();

    /** The frozen projected work source that arrives as the step's item. */
    private final StatementTransactionSource item = position -> Optional.empty();

    /**
     * The destination every request in this file names, so that what the stage forwarded can be
     * asserted.
     *
     * <p>The stage retains no stream: it proves one record and forwards it. Content assertions therefore
     * observe the destination rather than the stage's return value, and the return value is the run's
     * tallies.
     */
    private CollectingSink sink;

    @BeforeEach
    void setUp() {
        statementGenerationService = mock(StatementGenerationService.class);
        statementDataAccessService = mock(StatementDataAccessService.class);
        crossReferenceSource = position -> Optional.empty();
        meterRegistry = new SimpleMeterRegistry();
        revealer = value -> "revealed:" + value;
        sealer = value -> "sealed:" + value;
        processor = new StatementProcessor(statementGenerationService, revealer, sealer,
                meterRegistry);
        sink = new CollectingSink();
        plannedStatuses.clear();
        journal.clear();
        when(statementDataAccessService.openCrossReferenceSource()).thenReturn(crossReferenceSource);
        when(statementDataAccessService.execute(any(), any(), any())).thenAnswer(invocation -> {
            final StatementFileRequest request =
                    invocation.getArgument(0, StatementFileRequest.class);
            final String status =
                    plannedStatuses.isEmpty() ? STATUS_SUCCESS : plannedStatuses.removeFirst();
            return new StatementFileResponse(request.ddName(), status, request.payload(),
                    request.sequentialPosition() + 1);
        });
    }

    /**
     * Issues one linkage call against the mocked file handler, populating the resource identifier and
     * the operation discriminator before the call as the generator does at each of its call sites.
     *
     * @param ddName    the resource identifier
     * @param operation the operation discriminator
     * @param position  the sequential position to read from
     * @return the response the handler answered
     */
    private StatementFileResponse issue(final String ddName, final String operation,
            final int position) {
        final StatementFileRequest request = new StatementFileRequest(ddName, operation,
                STATUS_SUCCESS, "", 0, "", position);
        return statementDataAccessService.execute(request, item, crossReferenceSource);
    }

    /**
     * Drives the mocked file handler through the phases a completed run visits, in the run's
     * <em>derived</em> execution order, and answers the dispatch trail that order produces. The
     * transaction-input phase opens and primes; the read phase then consumes the whole work resource,
     * re-entering the read on every success and exiting forward on end of file; only then do the three
     * remaining opens happen, because the dispatcher is re-entered between phases rather than nesting
     * them.
     *
     * @param transactionRecordCount how many transaction records the work resource presents
     * @param openStatus             the status every open answers, one of the two an open accepts
     * @return the dispatch trail the derived order produces, ending on the catch-all clause
     */
    private List<String> driveDerivedExecutionOrder(final int transactionRecordCount,
            final String openStatus) {
        final List<String> trail = new ArrayList<>();
        statementDataAccessService.openCrossReferenceSource();

        plannedStatuses.add(openStatus);
        issue(SELECTOR_TRNXFILE, OPERATION_OPEN, 0);
        plannedStatuses.add(STATUS_SUCCESS);
        issue(SELECTOR_TRNXFILE, OPERATION_READ, 0);
        trail.add(SELECTOR_TRNXFILE);

        for (int record = 1; record < transactionRecordCount; record++) {
            plannedStatuses.add(STATUS_SUCCESS);
            issue(SELECTOR_TRNXFILE, OPERATION_READ, record);
        }
        plannedStatuses.add(STATUS_END_OF_FILE);
        issue(SELECTOR_TRNXFILE, OPERATION_READ, transactionRecordCount);
        trail.add(SELECTOR_READTRNX);

        for (final String selector :
                List.of(SELECTOR_XREFFILE, SELECTOR_CUSTFILE, SELECTOR_ACCTFILE)) {
            plannedStatuses.add(openStatus);
            issue(selector, OPERATION_OPEN, 0);
            trail.add(selector);
        }
        trail.add(TERMINAL_CLAUSE);
        return List.copyOf(trail);
    }

    /**
     * Builds the dispatch trail a complete run leaves: the five phase entries in their derived execution
     * order, followed by the catch-all clause that ends the run. Built from this file's own hand-written
     * order rather than read back from the class under contract.
     *
     * @return the dispatch trail
     */
    private static List<String> completeDispatchTrail() {
        final List<String> trail = new ArrayList<>(DERIVED_PHASE_ORDER);
        trail.add(TERMINAL_CLAUSE);
        return List.copyOf(trail);
    }

    /**
     * Builds one plain statement record at exactly the declared width.
     *
     * @return an eighty-byte record
     */
    private static String statementRecord() {
        return "S".repeat(TEXT_RECORD_WIDTH);
    }

    /**
     * Builds one markup statement record at exactly the declared width.
     *
     * @return a one-hundred-byte record
     */
    private static String markupPlaceholderRecord() {
        return "H".repeat(MARKUP_RECORD_WIDTH);
    }

    /**
     * Names this test's destination alongside a frozen source, which is what one batch item now is.
     *
     * @param  source the frozen projected work source
     * @return the request the stage is handed
     */
    private StatementProcessor.StatementRunRequest request(
            final StatementTransactionSource source) {
        return new StatementProcessor.StatementRunRequest(source, this.sink);
    }

    /**
     * Scripts a generation that emits the given output to whatever sink the stage passes in, and then
     * reports it as tallies.
     *
     * <p>This is what a stubbed generation has to look like once the service streams: the records leave
     * through the sink argument and the return value counts them. The dispatch trail is emitted first,
     * because the source reports a dispatcher entry before the phase that entry selects writes anything,
     * and because the stage proves the trail entry by entry as it arrives.
     *
     * @param  statementRecords the plain records to emit
     * @param  markupRecords    the markup records to emit
     * @param  summaries        the per-line summaries to emit
     * @param  trail            the dispatcher entries to report
     * @param  cards            how many distinct cards were tabulated
     * @param  transactions     how many transactions were tabulated
     * @param  statements       how many statements were written
     * @return the scripted generation
     */
    private static Answer<StatementRun> emitting(final List<String> statementRecords,
            final List<String> markupRecords, final List<StatementLineSummary> summaries,
            final List<String> trail, final int cards, final int transactions,
            final int statements) {
        return invocation -> {
            final StatementOutputSink destination = invocation.getArgument(3);
            for (final String phase : trail) {
                destination.dispatchedPhase(phase);
            }
            for (final String record : statementRecords) {
                destination.statementRecord(record);
            }
            for (final String record : markupRecords) {
                destination.htmlRecord(record);
            }
            for (final StatementLineSummary summary : summaries) {
                destination.transactionSummary(summary);
            }
            return new StatementRun(statementRecords.size(), markupRecords.size(), summaries.size(),
                    trail.size(), cards, transactions, statements);
        };
    }

    /**
     * Scripts a generation that satisfies every property the stage checks.
     *
     * @param statementRecords the plain records the run emits
     * @param markupRecords    the markup records the run emits
     * @param cards            how many distinct cards were tabulated
     * @param transactions     how many transactions were tabulated
     * @param statements       how many statements were written
     * @return the scripted generation
     */
    private static Answer<StatementRun> run(final List<String> statementRecords,
            final List<String> markupRecords, final int cards, final int transactions,
            final int statements) {
        return emitting(statementRecords, markupRecords, List.<StatementLineSummary>of(),
                completeDispatchTrail(), cards, transactions, statements);
    }

    /**
     * Scripts a well-formed generation of one card, one transaction and one statement.
     *
     * @return the scripted generation
     */
    private static Answer<StatementRun> wellFormedRun() {
        return run(List.of(statementRecord()), List.of(markupPlaceholderRecord()), 1, 1, 1);
    }

    /**
     * Scripts a generation whose dispatch trail is the supplied one, with everything else well formed.
     *
     * @param  trail the dispatch trail to report
     * @return the scripted generation
     */
    private static Answer<StatementRun> runWithTrail(final List<String> trail) {
        return emitting(List.of(statementRecord()), List.of(markupPlaceholderRecord()),
                List.<StatementLineSummary>of(), trail, 1, 1, 1);
    }

    /**
     * Scripts a generation carrying the supplied transaction summaries, with everything else well
     * formed.
     *
     * @param  summaries the summaries the run emits
     * @return the scripted generation
     */
    private static Answer<StatementRun> runWithSummaries(final List<StatementLineSummary> summaries) {
        return emitting(List.of(statementRecord()), List.of(markupPlaceholderRecord()), summaries,
                completeDispatchTrail(), 1, summaries.size(), 1);
    }

    /**
     * Collects everything the stage forwards, in the order it forwards it.
     *
     * <p>The volume is whatever the scripted scenario emits, which is what makes collecting it here a
     * bounded test observation rather than the unbounded accumulation the stage no longer performs.
     */
    private static final class CollectingSink implements StatementOutputSink {

        /** Plain records forwarded to this destination, in order. */
        private final List<String> statementRecords = new ArrayList<>();

        /** Markup records forwarded to this destination, in order. */
        private final List<String> htmlRecords = new ArrayList<>();

        /** Per-line summaries forwarded to this destination, in order. */
        private final List<StatementLineSummary> transactionSummaries = new ArrayList<>();

        /** Dispatcher entries forwarded to this destination, in order. */
        private final List<String> dispatchedPhases = new ArrayList<>();

        @Override
        public void statementRecord(final String record) {
            this.statementRecords.add(record);
        }

        @Override
        public void htmlRecord(final String record) {
            this.htmlRecords.add(record);
        }

        @Override
        public void transactionSummary(final StatementLineSummary summary) {
            this.transactionSummaries.add(summary);
        }

        @Override
        public void dispatchedPhase(final String phase) {
            this.dispatchedPhases.add(phase);
        }
    }

    /**
     * Reads a counter's tally. The registry answers a floating tally because that is the meter API's own
     * shape; it is a meter reading and never a monetary value, and no monetary value in this file is ever
     * held in anything but {@link BigDecimal}.
     *
     * @param metric the counter name
     * @return the tally, as a whole number of items
     */
    private long counted(final String metric) {
        final Counter counter = meterRegistry.find(metric).counter();
        return (counter == null) ? 0L : (long) counter.count();
    }

    /**
     * Reads the count of the stage timer carrying an outcome tag.
     *
     * @param outcome the expected outcome tag
     * @return the number of runs recorded
     */
    private long timed(final String outcome) {
        final Timer timer = meterRegistry.find("carddemo.batch.statement.generation")
                .tag("resource", SELECTOR_TRNXFILE).tag("outcome", outcome).timer();
        return (timer == null) ? 0L : timer.count();
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("every collaborator is required, and the two regulated-field operations are "
                + "required by name because the identity operation is not a substitute for either")
        void everyCollaboratorIsRequired() {
            assertThatNullPointerException().isThrownBy(() ->
                    new StatementProcessor(null, revealer, sealer, meterRegistry))
                    .withMessageContaining("statement generation service");
            assertThatNullPointerException().isThrownBy(() ->
                    new StatementProcessor(statementGenerationService, null, sealer, meterRegistry))
                    .withMessageContaining("revealing operation");
            assertThatNullPointerException().isThrownBy(() ->
                    new StatementProcessor(statementGenerationService, revealer, null, meterRegistry))
                    .withMessageContaining("sealing operation");
            assertThatNullPointerException().isThrownBy(() ->
                    new StatementProcessor(statementGenerationService, revealer, sealer, null))
                    .withMessageContaining("meter registry");
        }

        @Test
        @DisplayName("the stage names the legacy job, program, subprogram and the four steps it stands "
                + "for, so the traceability back to the job stream is in the type itself")
        void theStageNamesItsLegacyProvenance() {
            assertAll(
                    () -> assertThat(StatementProcessor.LEGACY_JOB).isEqualTo("CREASTMT"),
                    () -> assertThat(StatementProcessor.LEGACY_PROGRAM).isEqualTo("CBSTM03A"),
                    () -> assertThat(StatementProcessor.LEGACY_SUBPROGRAM).isEqualTo("CBSTM03B"),
                    () -> assertThat(StatementProcessor.LEGACY_SORT_STEP).isEqualTo("STEP010"),
                    () -> assertThat(StatementProcessor.LEGACY_LOAD_STEP).isEqualTo("STEP020"),
                    () -> assertThat(StatementProcessor.LEGACY_CLEAR_STEP).isEqualTo("STEP030"),
                    () -> assertThat(StatementProcessor.LEGACY_STATEMENT_STEP).isEqualTo("STEP040"));
        }

        @Test
        @DisplayName("the four inputs and two outputs carry their legacy names at the declared "
                + "eight-byte width, and the two record widths agree with the widths this test "
                + "measured from the emitting program at lines 45 and 47")
        void theResourceNamesAndRecordWidthsAreTheLegacyOnes() {
            assertAll(
                    () -> assertThat(StatementProcessor.INPUT_DD_TRNXFILE)
                            .isEqualTo(SELECTOR_TRNXFILE),
                    () -> assertThat(StatementProcessor.INPUT_DD_XREFFILE)
                            .isEqualTo(SELECTOR_XREFFILE),
                    () -> assertThat(StatementProcessor.INPUT_DD_CUSTFILE)
                            .isEqualTo(SELECTOR_CUSTFILE),
                    () -> assertThat(StatementProcessor.INPUT_DD_ACCTFILE)
                            .isEqualTo(SELECTOR_ACCTFILE),
                    () -> assertThat(StatementProcessor.OUTPUT_DD_STMTFILE).isEqualTo("STMTFILE"),
                    () -> assertThat(StatementProcessor.OUTPUT_DD_HTMLFILE).isEqualTo("HTMLFILE"),
                    () -> assertThat(StatementProcessor.DD_NAME_WIDTH).isEqualTo(SELECTOR_WIDTH),
                    () -> assertThat(StatementProcessor.STATEMENT_RECORD_LENGTH)
                            .isEqualTo(TEXT_RECORD_WIDTH),
                    () -> assertThat(StatementProcessor.HTML_RECORD_LENGTH)
                            .isEqualTo(MARKUP_RECORD_WIDTH));
        }

        @Test
        @DisplayName("the stage's only two output resources are the two the emitting program's own "
                + "open statement opens, so the output list is exactly those two and nothing else")
        void theOutputListIsExactlyTheTwoDatasetsTheProgramOpens() {
            assertThat(List.of(StatementProcessor.OUTPUT_DD_STMTFILE,
                    StatementProcessor.OUTPUT_DD_HTMLFILE))
                    .hasSize(2)
                    .doesNotContain(SELECTOR_TRNXFILE, SELECTOR_XREFFILE, SELECTOR_CUSTFILE,
                            SELECTOR_ACCTFILE)
                    .allSatisfy(name -> assertThat(encodedWidthOf(name)).isEqualTo(SELECTOR_WIDTH));
        }

        @Test
        @DisplayName("the expected dispatch sequence agrees with the derived execution order this test "
                + "worked out by following the backward re-entries, and one more entry is expected for "
                + "the catch-all clause that ends the run")
        void theExpectedDispatchSequenceIsTheDerivedOrder() {
            assertAll(
                    () -> assertThat(StatementProcessor.EXPECTED_DISPATCH_SEQUENCE)
                            .containsExactlyElementsOf(DERIVED_PHASE_ORDER),
                    () -> assertThat(StatementProcessor.EXPECTED_DISPATCH_ENTRY_COUNT)
                            .isEqualTo(DISPATCHER_CLAUSE_COUNT),
                    () -> assertThat(StatementProcessor.PHASE_SELECTOR_READTRNX)
                            .isEqualTo(SELECTOR_READTRNX));
        }
    }

    @Nested
    @DisplayName("The frozen source the stage accepts")
    class AcceptedItem {

        @Test
        @DisplayName("an absent item is refused, because an absent input is expressed by the reader "
                + "yielding an empty source rather than by yielding null")
        void anAbsentItemIsRefused() {
            assertThatNullPointerException().isThrownBy(() -> processor.process(null))
                    .withMessageContaining("frozen TRNXFILE transaction source");
        }

        @Test
        @DisplayName("the stage never opens a file itself: the three resources it does not receive as "
                + "an item are opened by the generator through the injected data-access collaborator, "
                + "which the stage never touches")
        void theStageNeverOpensAFileItself() {
            when(statementGenerationService.generate(any(), any(), any(), any())).thenAnswer(wellFormedRun());

            processor.process(request(item));

            verifyNoInteractions(statementDataAccessService);
        }
    }

    @Nested
    @DisplayName("A run that satisfies every property")
    class CompletedRun {

        @Test
        @DisplayName("the generator is driven with the two regulated-field operations the stage holds "
                + "and with a sink, its records reach the destination and its tallies are handed on "
                + "unchanged")
        void theGeneratorIsDrivenWithTheRegulatedFieldOperations() {
            when(statementGenerationService.generate(same(item), same(revealer), same(sealer), any()))
                    .thenAnswer(wellFormedRun());

            final StatementRun answered = processor.process(request(item));

            assertAll(
                    () -> assertThat(answered.statementRecordsEmitted()).isEqualTo(1),
                    () -> assertThat(answered.htmlRecordsEmitted()).isEqualTo(1),
                    () -> assertThat(answered.cardsTabulated()).isEqualTo(1),
                    () -> assertThat(answered.transactionsTabulated()).isEqualTo(1),
                    () -> assertThat(answered.statementsWritten()).isEqualTo(1),
                    // The records themselves went to the destination, one at a time, during the run.
                    () -> assertThat(sink.statementRecords).containsExactly(statementRecord()),
                    () -> assertThat(sink.htmlRecords)
                            .containsExactly(markupPlaceholderRecord()));
            verify(statementGenerationService)
                    .generate(same(item), same(revealer), same(sealer), any());
        }

        @Test
        @DisplayName("the run is timed as completed")
        void theRunIsTimedAsCompleted() {
            when(statementGenerationService.generate(any(), any(), any(), any())).thenAnswer(wellFormedRun());

            processor.process(request(item));

            assertThat(timed("COMPLETED")).isEqualTo(1L);
            assertThat(timed("FAILED")).isZero();
        }

        @Test
        @DisplayName("both record streams and the statement tally are counted, each against the "
                + "resource it belongs to")
        void bothRecordStreamsAndTheStatementTallyAreCounted() {
            when(statementGenerationService.generate(any(), any(), any(), any())).thenAnswer(run(
                    List.of(statementRecord(), statementRecord(), statementRecord()),
                    List.of(markupPlaceholderRecord(), markupPlaceholderRecord()), 2, 5, 4));

            processor.process(request(item));

            assertAll(
                    () -> assertThat(counted("carddemo.batch.statement.records")).isEqualTo(3L),
                    () -> assertThat(counted("carddemo.batch.statement.htmlRecords")).isEqualTo(2L),
                    () -> assertThat(counted("carddemo.batch.statement.statements")).isEqualTo(4L));
        }

        @Test
        @DisplayName("a run that produced no records at all is still a completed run, and the "
                + "counters simply do not move")
        void aRunThatProducedNoRecordsIsStillCompleted() {
            when(statementGenerationService.generate(any(), any(), any(), any()))
                    .thenAnswer(run(List.of(), List.of(), 0, 0, 0));

            assertThat(processor.process(request(item))).isNotNull();
            assertThat(counted("carddemo.batch.statement.records")).isZero();
            assertThat(counted("carddemo.batch.statement.htmlRecords")).isZero();
            assertThat(timed("COMPLETED")).isEqualTo(1L);
        }

        @Test
        @DisplayName("repeated runs accumulate rather than replacing each other")
        void repeatedRunsAccumulate() {
            when(statementGenerationService.generate(any(), any(), any(), any())).thenAnswer(wellFormedRun());

            processor.process(request(item));
            processor.process(request(item));

            assertThat(counted("carddemo.batch.statement.records")).isEqualTo(2L);
            assertThat(timed("COMPLETED")).isEqualTo(2L);
        }
    }

    @Nested
    @DisplayName("The generator's own result must be present")
    class AbsentResult {

        @Test
        @DisplayName("a generator that reported no result at all fails the stage and is timed as a "
                + "failure")
        void anAbsentResultFailsTheStage() {
            when(statementGenerationService.generate(any(), any(), any(), any())).thenReturn(null);

            assertThatNullPointerException().isThrownBy(() -> processor.process(request(item)))
                    .withMessageContaining("CBSTM03A")
                    .withMessageContaining("TRNXFILE");
            assertThat(timed("FAILED")).isEqualTo(1L);
            assertThat(timed("COMPLETED")).isZero();
        }
    }

    @Nested
    @DisplayName("The dispatcher: six clauses, a derived execution order, and mandatory re-entry")
    class DispatcherContract {

        @Test
        @DisplayName("the selection construct declares exactly six clauses in the contractual order - "
                + "transaction-input open, cross-reference open, customer open, account open, "
                + "read-all-transactions, then the catch-all that exits the program")
        void theSelectionConstructDeclaresSixClausesInTheContractualOrder() {
            assertAll(
                    () -> assertThat(DISPATCHER_CLAUSE_ORDER).hasSize(DISPATCHER_CLAUSE_COUNT),
                    () -> assertThat(DISPATCHER_CLAUSE_ORDER).containsExactly(SELECTOR_TRNXFILE,
                            SELECTOR_XREFFILE, SELECTOR_CUSTFILE, SELECTOR_ACCTFILE,
                            SELECTOR_READTRNX, TERMINAL_CLAUSE),
                    () -> assertThat(DISPATCHER_CLAUSE_ORDER.get(DISPATCHER_CLAUSE_COUNT - 1))
                            .isEqualTo(TERMINAL_CLAUSE),
                    () -> assertThat(DERIVED_PHASE_ORDER).doesNotContain(TERMINAL_CLAUSE));
        }

        @Test
        @DisplayName("the dispatcher's control field is eight characters wide and every phase selector "
                + "fills it exactly, so a selector is never a short or padded value")
        void everyPhaseSelectorFillsTheEightCharacterControlField() {
            assertThat(DERIVED_PHASE_ORDER).hasSize(DISPATCHER_CLAUSE_COUNT - 1)
                    .allSatisfy(selector ->
                            assertThat(encodedWidthOf(selector)).isEqualTo(SELECTOR_WIDTH));
            assertThat(DERIVED_PHASE_ORDER.get(0)).isEqualTo(SELECTOR_TRNXFILE);
        }

        @Test
        @DisplayName("the derived execution order is transaction-input open, read-all transactions, "
                + "cross-reference open, customer open, account open - which is NOT the clause order, "
                + "because the whole work resource is read and tabulated up front")
        void theDerivedExecutionOrderDivergesFromTheClauseOrder() {
            final List<String> clausePhasesOnly =
                    DISPATCHER_CLAUSE_ORDER.subList(0, DISPATCHER_CLAUSE_COUNT - 1);

            assertAll(
                    () -> assertThat(DERIVED_PHASE_ORDER).containsExactly(SELECTOR_TRNXFILE,
                            SELECTOR_READTRNX, SELECTOR_XREFFILE, SELECTOR_CUSTFILE,
                            SELECTOR_ACCTFILE),
                    () -> assertThat(DERIVED_PHASE_ORDER)
                            .containsExactlyInAnyOrderElementsOf(clausePhasesOnly),
                    () -> assertThat(DERIVED_PHASE_ORDER).isNotEqualTo(clausePhasesOnly),
                    () -> assertThat(DERIVED_PHASE_ORDER.indexOf(SELECTOR_READTRNX))
                            .isLessThan(DERIVED_PHASE_ORDER.indexOf(SELECTOR_XREFFILE)));
        }

        @Test
        @DisplayName("the state machine re-enters the dispatcher after every phase transition: the "
                + "whole read phase completes before the three remaining opens, which a structure of "
                + "nested calls could not produce")
        void theStateMachineReEntersTheDispatcherAfterEveryTransition() {
            final int records = 4;

            final List<String> trail = driveDerivedExecutionOrder(records, STATUS_SUCCESS);

            final InOrder dispatcher = inOrder(statementDataAccessService);
            dispatcher.verify(statementDataAccessService).openCrossReferenceSource();
            dispatcher.verify(statementDataAccessService).execute(
                    argThat(request -> request.hasDdName(SELECTOR_TRNXFILE)
                            && request.hasOperation(OPERATION_OPEN)),
                    any(), any());
            dispatcher.verify(statementDataAccessService, times(records + 1)).execute(
                    argThat(request -> request.hasDdName(SELECTOR_TRNXFILE)
                            && request.hasOperation(OPERATION_READ)),
                    any(), any());
            dispatcher.verify(statementDataAccessService).execute(
                    argThat(request -> request.hasDdName(SELECTOR_XREFFILE)
                            && request.hasOperation(OPERATION_OPEN)),
                    any(), any());
            dispatcher.verify(statementDataAccessService).execute(
                    argThat(request -> request.hasDdName(SELECTOR_CUSTFILE)
                            && request.hasOperation(OPERATION_OPEN)),
                    any(), any());
            dispatcher.verify(statementDataAccessService).execute(
                    argThat(request -> request.hasDdName(SELECTOR_ACCTFILE)
                            && request.hasOperation(OPERATION_OPEN)),
                    any(), any());
            dispatcher.verifyNoMoreInteractions();
            assertThat(trail).containsExactlyElementsOf(completeDispatchTrail());
        }

        @Test
        @DisplayName("the trail the derived order produces is the one the stage accepts, and the run "
                + "is handed on")
        void theTrailTheDerivedOrderProducesIsAccepted() {
            final List<String> trail = driveDerivedExecutionOrder(2, STATUS_SUCCESS);
            when(statementGenerationService.generate(any(), any(), any(), any()))
                    .thenAnswer(runWithTrail(trail));

            assertThat(processor.process(request(item))).isNotNull();
            assertThat(timed("COMPLETED")).isEqualTo(1L);
        }

        @Test
        @DisplayName("a run reporting the dispatcher's CLAUSE order instead of the derived execution "
                + "order is refused - the divergence is the contract, and a test written against the "
                + "clause order would pass against a wrong implementation")
        void aRunReportingTheClauseOrderIsRefused() {
            when(statementGenerationService.generate(any(), any(), any(), any()))
                    .thenAnswer(runWithTrail(DISPATCHER_CLAUSE_ORDER));

            assertThatIllegalStateException().isThrownBy(() -> processor.process(request(item)))
                    .withMessageContaining("entry 1")
                    .withMessageContaining("'" + SELECTOR_XREFFILE + "'")
                    .withMessageContaining("'" + SELECTOR_READTRNX + "'");
            assertThat(timed("FAILED")).isEqualTo(1L);
        }

        @Test
        @DisplayName("the catch-all clause exits the run cleanly: a trail that ends on it is accepted, "
                + "and the terminal marker is none of the five phase selectors")
        void theCatchAllClauseExitsTheRunCleanly() {
            final List<String> trail = completeDispatchTrail();
            when(statementGenerationService.generate(any(), any(), any(), any()))
                    .thenAnswer(runWithTrail(trail));

            assertAll(
                    () -> assertThat(trail).hasSize(DISPATCHER_CLAUSE_COUNT),
                    () -> assertThat(trail).last().isEqualTo(TERMINAL_CLAUSE),
                    () -> assertThat(DERIVED_PHASE_ORDER).doesNotContain(TERMINAL_CLAUSE),
                    () -> assertThat(processor.process(request(item))).isNotNull());
        }
    }

    @Nested
    @DisplayName("The dispatch-sequence proof")
    class DispatchSequenceProof {

        @Test
        @DisplayName("a trail with too few entries is refused, and the expected count is reported")
        void aTrailWithTooFewEntriesIsRefused() {
            when(statementGenerationService.generate(any(), any(), any(), any())).thenAnswer(runWithTrail(
                    List.of(SELECTOR_TRNXFILE, SELECTOR_READTRNX, SELECTOR_XREFFILE)));

            assertThatIllegalStateException().isThrownBy(() -> processor.process(request(item)))
                    .withMessageContaining("entered 3 time(s) rather than 6");
        }

        @Test
        @DisplayName("a trail with too many entries is refused")
        void aTrailWithTooManyEntriesIsRefused() {
            final List<String> tooMany = new ArrayList<>(completeDispatchTrail());
            tooMany.add(TERMINAL_CLAUSE);
            when(statementGenerationService.generate(any(), any(), any(), any()))
                    .thenAnswer(runWithTrail(tooMany));

            assertThatIllegalStateException().isThrownBy(() -> processor.process(request(item)))
                    .withMessageContaining("entered 7 time(s) rather than 6");
        }

        @Test
        @DisplayName("a trail whose phases ran in the wrong order is refused, naming the entry and "
                + "both the observed and the expected phase")
        void aTrailInTheWrongOrderIsRefused() {
            when(statementGenerationService.generate(any(), any(), any(), any())).thenAnswer(runWithTrail(
                    List.of(SELECTOR_TRNXFILE, SELECTOR_READTRNX, SELECTOR_CUSTFILE,
                            SELECTOR_XREFFILE, SELECTOR_ACCTFILE, TERMINAL_CLAUSE)));

            assertThatIllegalStateException().isThrownBy(() -> processor.process(request(item)))
                    .withMessageContaining("entry 2")
                    .withMessageContaining("'" + SELECTOR_CUSTFILE + "'")
                    .withMessageContaining("'" + SELECTOR_XREFFILE + "'");
        }

        @Test
        @DisplayName("a trail that ended on a phase selector rather than the catch-all clause is "
                + "refused, which is how an early termination is told from a complete run")
        void aTrailEndingOnAPhaseSelectorIsRefused() {
            when(statementGenerationService.generate(any(), any(), any(), any())).thenAnswer(runWithTrail(
                    List.of(SELECTOR_TRNXFILE, SELECTOR_READTRNX, SELECTOR_XREFFILE,
                            SELECTOR_CUSTFILE, SELECTOR_ACCTFILE, SELECTOR_ACCTFILE)));

            assertThatIllegalStateException().isThrownBy(() -> processor.process(request(item)))
                    .withMessageContaining("phase selector '" + SELECTOR_ACCTFILE + "'");
        }

        @Test
        @DisplayName("a failed proof is timed as a failure and moves no counter, so the job's "
                + "accounting is never credited for a run it rejected")
        void aFailedProofMovesNoCounter() {
            when(statementGenerationService.generate(any(), any(), any(), any()))
                    .thenAnswer(runWithTrail(List.of(SELECTOR_TRNXFILE)));

            assertThatIllegalStateException().isThrownBy(() -> processor.process(request(item)));

            assertAll(
                    () -> assertThat(timed("FAILED")).isEqualTo(1L),
                    () -> assertThat(timed("COMPLETED")).isZero(),
                    () -> assertThat(counted("carddemo.batch.statement.records")).isZero(),
                    () -> assertThat(counted("carddemo.batch.statement.htmlRecords")).isZero(),
                    () -> assertThat(counted("carddemo.batch.statement.statements")).isZero());
        }
    }

    @Nested
    @DisplayName("The inner read loop: three outcomes, and an open that is never single-valued")
    class ReadLoopOutcomes {

        @Test
        @DisplayName("a successful read re-enters the read, so the whole work resource is consumed "
                + "before the phase gives control back to the dispatcher")
        void aSuccessfulReadReEntersTheRead() {
            final int records = 6;

            driveDerivedExecutionOrder(records, STATUS_SUCCESS);

            verify(statementDataAccessService, times(records + 1)).execute(
                    argThat(request -> request.hasDdName(SELECTOR_TRNXFILE)
                            && request.hasOperation(OPERATION_READ)),
                    any(), any());
        }

        @Test
        @DisplayName("an end-of-file read exits the phase forward to its exit paragraph: no further "
                + "read is issued and the next linkage call is the cross-reference open")
        void anEndOfFileReadExitsThePhaseForward() {
            plannedStatuses.add(STATUS_SUCCESS);
            issue(SELECTOR_TRNXFILE, OPERATION_OPEN, 0);
            plannedStatuses.add(STATUS_END_OF_FILE);
            final StatementFileResponse terminal = issue(SELECTOR_TRNXFILE, OPERATION_READ, 0);
            plannedStatuses.add(STATUS_SUCCESS);
            issue(SELECTOR_XREFFILE, OPERATION_OPEN, 0);

            assertAll(
                    () -> assertThat(terminal.returnCode()).isEqualTo(STATUS_END_OF_FILE),
                    () -> assertThat(encodedWidthOf(terminal.returnCode()))
                            .isEqualTo(LINKAGE_RETURN_CODE_WIDTH));
            final InOrder forward = inOrder(statementDataAccessService);
            forward.verify(statementDataAccessService).execute(
                    argThat(request -> request.hasDdName(SELECTOR_TRNXFILE)
                            && request.hasOperation(OPERATION_READ)),
                    any(), any());
            forward.verify(statementDataAccessService).execute(
                    argThat(request -> request.hasDdName(SELECTOR_XREFFILE)
                            && request.hasOperation(OPERATION_OPEN)),
                    any(), any());
            verify(statementDataAccessService, times(1)).execute(
                    argThat(request -> request.hasDdName(SELECTOR_TRNXFILE)
                            && request.hasOperation(OPERATION_READ)),
                    any(), any());
        }

        @Test
        @DisplayName("end of file is never collapsed into the error arm: the three outcomes are three "
                + "distinct raw statuses, and only the third one abends")
        void endOfFileIsNeverCollapsedIntoTheErrorArm() {
            assertAll(
                    () -> assertThat(List.of(STATUS_SUCCESS, STATUS_END_OF_FILE,
                            STATUS_RECORD_NOT_FOUND)).doesNotHaveDuplicates().hasSize(3),
                    () -> assertThat(STATUS_END_OF_FILE).isNotEqualTo(STATUS_RECORD_NOT_FOUND),
                    () -> assertThat(ACCEPTED_OPEN_STATUSES).doesNotContain(STATUS_END_OF_FILE,
                            STATUS_RECORD_NOT_FOUND));

            plannedStatuses.add(STATUS_END_OF_FILE);
            final StatementFileResponse atEnd = issue(SELECTOR_TRNXFILE, OPERATION_READ, 0);
            plannedStatuses.add(STATUS_RECORD_NOT_FOUND);
            final StatementFileResponse inError = issue(SELECTOR_TRNXFILE, OPERATION_READ, 1);

            assertAll(
                    () -> assertThat(atEnd.returnCode()).isEqualTo(STATUS_END_OF_FILE),
                    () -> assertThat(inError.returnCode()).isEqualTo(STATUS_RECORD_NOT_FOUND),
                    () -> assertThat(atEnd.returnCode()).isNotEqualTo(inError.returnCode()),
                    () -> assertThat(atEnd.sequentialPosition())
                            .isNotEqualTo(inError.sequentialPosition()));
        }

        @Test
        @DisplayName("any other returned code emits a diagnostic naming the code and then abends, and "
                + "the stage rethrows that very exception rather than wrapping or replacing it")
        void anyOtherReturnedCodeDiagnosesThenAbends() {
            final AbendException abend = new AbendException(AbendException.BATCH_ABEND_CODE,
                    StatementProcessor.LEGACY_PROGRAM,
                    "ERROR READING " + SELECTOR_TRNXFILE + " - RETURN CODE: "
                            + STATUS_RECORD_NOT_FOUND,
                    AbendException.DEFAULT_MESSAGE);
            when(statementGenerationService.generate(any(), any(), any(), any())).thenAnswer(invocation -> {
                plannedStatuses.add(STATUS_SUCCESS);
                issue(SELECTOR_TRNXFILE, OPERATION_OPEN, 0);
                plannedStatuses.add(STATUS_RECORD_NOT_FOUND);
                final StatementFileResponse failed = issue(SELECTOR_TRNXFILE, OPERATION_READ, 0);
                journal.add("diagnostic " + failed.returnCode());
                journal.add("exception");
                throw abend;
            });

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> processor.process(request(item))).isSameAs(abend);

            assertAll(
                    () -> assertThat(journal).containsExactly(
                            "diagnostic " + STATUS_RECORD_NOT_FOUND, "exception"),
                    () -> assertThat(abend.reason()).contains(STATUS_RECORD_NOT_FOUND)
                            .contains(SELECTOR_TRNXFILE),
                    () -> assertThat(timed("FAILED")).isEqualTo(1L),
                    () -> assertThat(timed("COMPLETED")).isZero(),
                    () -> assertThat(counted("carddemo.batch.statement.records")).isZero());
        }

        @ParameterizedTest(name = "an open answering {0} is accepted")
        @CsvSource({"00", "04"})
        @DisplayName("an open accepts two success values, so an open outcome is never treated as "
                + "single-valued")
        void anOpenAcceptsTwoSuccessValues(final String openStatus) {
            assertThat(ACCEPTED_OPEN_STATUSES).contains(openStatus).hasSize(2);

            final List<String> trail = driveDerivedExecutionOrder(3, openStatus);

            assertThat(trail).containsExactlyElementsOf(completeDispatchTrail());
            verify(statementDataAccessService, times(4)).execute(
                    argThat(request -> request.hasOperation(OPERATION_OPEN)), any(), any());
        }

        @Test
        @DisplayName("the compared raw-status vocabulary is exactly the set the estate compares, and "
                + "nothing here depends on a status the source never compares")
        void theComparedRawStatusVocabularyIsTheEstatesOwn() {
            final List<String> compared = List.of(STATUS_SUCCESS, STATUS_RECORD_LENGTH_MISMATCH,
                    STATUS_END_OF_FILE, STATUS_RECORD_NOT_FOUND);

            assertThat(compared).doesNotHaveDuplicates()
                    .allSatisfy(code -> assertThat(encodedWidthOf(code))
                            .isEqualTo(LINKAGE_RETURN_CODE_WIDTH));
        }
    }

    @Nested
    @DisplayName("The per-card accumulation the read phase performs")
    class PerCardAccumulation {

        @Test
        @DisplayName("consecutive transactions for one card accumulate into a single card slot")
        void consecutiveTransactionsForOneCardAccumulateIntoOneSlot() {
            final CardTabulation tabulated = tabulate(List.of(SAMPLE_CARD_NUMBER, SAMPLE_CARD_NUMBER,
                    SAMPLE_CARD_NUMBER, SAMPLE_CARD_NUMBER));

            assertAll(
                    () -> assertThat(tabulated.cards()).isEqualTo(1),
                    () -> assertThat(tabulated.countsPerCard()).containsExactly(4),
                    () -> assertThat(tabulated.totalTransactions()).isEqualTo(4));
        }

        @Test
        @DisplayName("a card change advances the slot and stores the count completed for the slot just "
                + "left, and the stored count for each card equals the number of its transactions")
        void aCardChangeAdvancesTheSlotAndStoresThePreviousCount() {
            final CardTabulation tabulated = tabulate(List.of(SAMPLE_CARD_NUMBER, SAMPLE_CARD_NUMBER,
                    SAMPLE_SECOND_CARD_NUMBER, SAMPLE_SECOND_CARD_NUMBER, SAMPLE_SECOND_CARD_NUMBER,
                    SAMPLE_THIRD_CARD_NUMBER));

            assertAll(
                    () -> assertThat(tabulated.cards()).isEqualTo(3),
                    () -> assertThat(tabulated.countsPerCard()).containsExactly(2, 3, 1),
                    () -> assertThat(tabulated.totalTransactions()).isEqualTo(6));
        }

        @Test
        @DisplayName("the grouping the read phase reports is the grouping the stage accepts: three "
                + "cards carrying six transactions passes the bounded-grouping proof")
        void theReportedGroupingIsTheGroupingTheStageAccepts() {
            final CardTabulation tabulated = tabulate(List.of(SAMPLE_CARD_NUMBER, SAMPLE_CARD_NUMBER,
                    SAMPLE_SECOND_CARD_NUMBER, SAMPLE_SECOND_CARD_NUMBER, SAMPLE_SECOND_CARD_NUMBER,
                    SAMPLE_THIRD_CARD_NUMBER));
            when(statementGenerationService.generate(any(), any(), any(), any())).thenAnswer(
                    run(List.of(statementRecord()), List.of(markupPlaceholderRecord()),
                            tabulated.cards(), tabulated.totalTransactions(), tabulated.cards()));

            final StatementRun answered = processor.process(request(item));

            assertAll(
                    () -> assertThat(answered.cardsTabulated()).isEqualTo(3),
                    () -> assertThat(answered.transactionsTabulated()).isEqualTo(6),
                    () -> assertThat(timed("COMPLETED")).isEqualTo(1L));
        }

        @Test
        @DisplayName("a work resource that presents nothing tabulates nothing, and the empty run is "
                + "still a completed run")
        void anEmptyWorkResourceTabulatesNothing() {
            final CardTabulation tabulated = tabulate(List.of());

            assertThat(tabulated.cards()).isZero();
            assertThat(tabulated.countsPerCard()).isEmpty();
            assertThat(tabulated.totalTransactions()).isZero();
        }

        @Test
        @DisplayName("a card that reappears after another card takes a new slot, because the phase "
                + "compares only against the card it last remembered and never searches the table")
        void aReappearingCardTakesANewSlot() {
            final CardTabulation tabulated = tabulate(List.of(SAMPLE_CARD_NUMBER,
                    SAMPLE_SECOND_CARD_NUMBER, SAMPLE_CARD_NUMBER));

            assertThat(tabulated.cards()).isEqualTo(3);
            assertThat(tabulated.countsPerCard()).containsExactly(1, 1, 1);
        }
    }

    @Nested
    @DisplayName("Structural capacity: fifty-one cards of ten transactions")
    class StructuralCapacity {

        @Test
        @DisplayName("the bound is a table dimension and nothing else - fifty-one card entries and ten "
                + "transactions per entry, taken from the declared occurrences at program lines 226 "
                + "and 228, with a parallel counter table of the same fifty-one entries at line 232")
        void theBoundIsATableDimensionAndNothingElse() {
            assertAll(
                    () -> assertThat(StatementGenerationService.MAX_CARD_ENTRIES)
                            .isEqualTo(CARD_TABLE_OCCURRENCES),
                    () -> assertThat(StatementGenerationService.MAX_TRANSACTIONS_PER_CARD)
                            .isEqualTo(TRANSACTIONS_PER_CARD_OCCURRENCES),
                    () -> assertThat(CARD_TABLE_OCCURRENCES * TRANSACTIONS_PER_CARD_OCCURRENCES)
                            .isEqualTo(510),
                    () -> assertThat(StatementGenerationService.PROGRAM_NAME)
                            .isEqualTo(StatementProcessor.LEGACY_PROGRAM));
        }

        @Test
        @DisplayName("a run at exactly the table's capacity is accepted, so the bound is inclusive - "
                + "it is capacity, not a validation rule, and it is never relaxed either way")
        void aRunAtExactlyCapacityIsAccepted() {
            final int cards = CARD_TABLE_OCCURRENCES;
            final int transactions = cards * TRANSACTIONS_PER_CARD_OCCURRENCES;
            when(statementGenerationService.generate(any(), any(), any(), any())).thenAnswer(
                    run(List.of(statementRecord()), List.of(markupPlaceholderRecord()), cards,
                            transactions, cards));

            assertThat(processor.process(request(item))).isNotNull();
            assertThat(timed("COMPLETED")).isEqualTo(1L);
        }

        @Test
        @DisplayName("more tabulated cards than the card table holds is refused")
        void tooManyCardsIsRefused() {
            final int overCapacity = CARD_TABLE_OCCURRENCES + 1;
            when(statementGenerationService.generate(any(), any(), any(), any())).thenAnswer(
                    run(List.of(statementRecord()), List.of(markupPlaceholderRecord()), overCapacity,
                            overCapacity, 1));

            assertThatIllegalStateException().isThrownBy(() -> processor.process(request(item)))
                    .withMessageContaining("card(s) tabulated");
        }

        @Test
        @DisplayName("fewer transactions than cards is refused, because a card enters the table only "
                + "when a transaction for it is read")
        void fewerTransactionsThanCardsIsRefused() {
            when(statementGenerationService.generate(any(), any(), any(), any())).thenAnswer(
                    run(List.of(statementRecord()), List.of(markupPlaceholderRecord()), 5, 3, 1));

            assertThatIllegalStateException().isThrownBy(() -> processor.process(request(item)))
                    .withMessageContaining("transaction(s) tabulated across 5 card(s)");
        }

        @Test
        @DisplayName("more transactions than the tabulated cards could carry is refused")
        void tooManyTransactionsForTheTabulatedCardsIsRefused() {
            final int beyondReach = 2 * TRANSACTIONS_PER_CARD_OCCURRENCES + 1;
            when(statementGenerationService.generate(any(), any(), any(), any())).thenAnswer(
                    run(List.of(statementRecord()), List.of(markupPlaceholderRecord()), 2,
                            beyondReach, 1));

            assertThatIllegalStateException().isThrownBy(() -> processor.process(request(item)))
                    .withMessageContaining("transaction(s) tabulated");
        }

        @Test
        @DisplayName("a negative statement tally is refused, while a tally above the tabulated card "
                + "count is not, because the mainline writes one statement per cross-reference record "
                + "rather than one per tabulated card")
        void aNegativeStatementTallyIsRefused() {
            when(statementGenerationService.generate(any(), any(), any(), any())).thenAnswer(
                    run(List.of(statementRecord()), List.of(markupPlaceholderRecord()), 1, 1, -1));

            assertThatIllegalStateException().isThrownBy(() -> processor.process(request(item)))
                    .withMessageContaining("statement(s) written");
        }

        @Test
        @DisplayName("a statement tally larger than the tabulated card count is accepted")
        void aStatementTallyLargerThanTheCardCountIsAccepted() {
            when(statementGenerationService.generate(any(), any(), any(), any())).thenAnswer(
                    run(List.of(statementRecord()), List.of(markupPlaceholderRecord()), 1, 1,
                            CARD_TABLE_OCCURRENCES + 9));

            assertThat(processor.process(request(item))).isNotNull();
        }
    }

    /**
     * Every statement text line shape, each paired with a hand-built image at the widths measured in the
     * source. Not one image is obtained from a template class.
     *
     * @return the shape-name and hand-built-image pairs
     */
    private static Stream<Arguments> everyStatementLineShape() {
        return Stream.of(
                Arguments.of("start-of-statement banner", START_BANNER_LINE),
                Arguments.of("name line, a 75-character field plus 5 spaces", nameLine(SAMPLE_NAME)),
                Arguments.of("first address line, a 50-character field plus 30 spaces",
                        addressLine(SAMPLE_ADDRESS_LINE_1)),
                Arguments.of("second address line, a 50-character field plus 30 spaces",
                        addressLine(SAMPLE_ADDRESS_LINE_2)),
                Arguments.of("third address line, a single 80-character field",
                        thirdAddressLine(SAMPLE_ADDRESS_LINE_3)),
                Arguments.of("rule line, 80 hyphens", RULE_LINE),
                Arguments.of("section heading line, 33 spaces plus a 14-character heading plus 33 "
                        + "spaces", BASIC_DETAILS_LINE),
                Arguments.of("account identifier line, a 20-character label plus a 20-character value "
                        + "plus 40 spaces", labelledValueLine(ACCOUNT_LABEL, SAMPLE_ACCOUNT_ID)),
                Arguments.of("balance line, a 20-character label plus a non-suppressed edited amount "
                        + "plus 7 spaces plus 40 spaces", balanceLine(SAMPLE_BALANCE)),
                Arguments.of("credit score line, a 20-character label plus a 20-character value plus "
                        + "40 spaces", labelledValueLine(SCORE_LABEL, SAMPLE_SCORE)),
                Arguments.of("summary heading line, 30 spaces plus a 20-character heading plus 30 "
                        + "spaces", SUMMARY_LINE),
                Arguments.of("column header line, three labels of 16, 51 and 13 characters",
                        COLUMN_HEADING_LINE),
                Arguments.of("transaction detail line, a 16-character identifier, 1 space, a "
                        + "49-character description, a currency symbol and a suppressed edited amount",
                        detailLine(SAMPLE_TRANSACTION_ID, SAMPLE_DESCRIPTION, SAMPLE_DETAIL_AMOUNT)),
                Arguments.of("total line, a 10-character label, 56 spaces, a currency symbol and a "
                        + "suppressed edited total", totalLine(SAMPLE_TOTAL_AMOUNT)),
                Arguments.of("end-of-statement banner", END_BANNER_LINE));
    }

    /**
     * The zero-based positions at which a rule line is emitted for a statement carrying the given number
     * of transactions: five inside the head block, then the sixth in the tail.
     *
     * @param transactionCount how many transactions the statement carries
     * @return the six positions, in emission order
     */
    private static List<Integer> expectedRuleLinePositions(final int transactionCount) {
        return List.of(5, 7, 11, 13, 15, HEAD_EMISSION_COUNT + 1 + transactionCount);
    }

    @Nested
    @DisplayName("The 80-byte statement text contract")
    class StatementTextContract {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.carddemo.batch.step.StatementProcessorTest#everyStatementLineShape")
        @DisplayName("every statement line shape measures exactly eighty encoded bytes, measured in "
                + "bytes and never in characters, and never trimmed")
        void everyLineShapeMeasuresExactlyEightyEncodedBytes(final String shape, final String image) {
            assertThat(encodedWidthOf(image)).as(shape).isEqualTo(TEXT_RECORD_WIDTH);
        }

        @Test
        @DisplayName("a produced statement artifact is an exact multiple of eighty bytes, because a "
                + "fixed-length dataset carries no record terminator")
        void aProducedArtifactIsAnExactMultipleOfEightyBytes() {
            final List<String> artifact = statementTextArtifact(3);

            assertThat(encodedWidthOf(String.join("", artifact)) % TEXT_RECORD_WIDTH).isZero();
            assertThat(artifact).allSatisfy(record ->
                    assertThat(encodedWidthOf(record)).isEqualTo(TEXT_RECORD_WIDTH));
        }

        @Test
        @DisplayName("EXACTLY SIX byte-identical rule lines are emitted per statement, and they are "
                + "NOT de-duplicated, collapsed, factored, looped or emitted once")
        void exactlySixByteIdenticalRuleLinesAreEmittedPerStatement() {
            final int transactions = 2;
            final List<String> artifact = statementTextArtifact(transactions);

            final List<Integer> observed = new ArrayList<>();
            for (int index = 0; index < artifact.size(); index++) {
                if (RULE_LINE.equals(artifact.get(index))) {
                    observed.add(index);
                }
            }

            assertAll(
                    () -> assertThat(observed).hasSize(RULE_LINES_PER_STATEMENT),
                    () -> assertThat(observed)
                            .containsExactlyElementsOf(expectedRuleLinePositions(transactions)),
                    () -> assertThat(artifact.stream().filter(RULE_LINE::equals).distinct().count())
                            .isEqualTo(1L),
                    () -> assertThat(encodedWidthOf(RULE_LINE)).isEqualTo(TEXT_RECORD_WIDTH),
                    () -> assertThat(RULE_LINE).isEqualTo(HYPHEN.repeat(TEXT_RECORD_WIDTH)));
        }

        @Test
        @DisplayName("the six rule lines appear at their emitted positions for any transaction count, "
                + "so the sixth stays in the tail rather than drifting into the head")
        void theSixRuleLinesKeepTheirEmittedPositionsForAnyTransactionCount() {
            for (final int transactions : List.of(0, 1, 5, 10)) {
                final List<String> artifact = statementTextArtifact(transactions);
                final List<Integer> expected = expectedRuleLinePositions(transactions);

                assertThat(expected).hasSize(RULE_LINES_PER_STATEMENT);
                assertThat(expected).allSatisfy(position ->
                        assertThat(artifact.get(position)).isEqualTo(RULE_LINE));
                assertThat(artifact.stream().filter(RULE_LINE::equals).count())
                        .isEqualTo(RULE_LINES_PER_STATEMENT);
            }
        }

        @Test
        @DisplayName("the full per-statement emission order is the opening banner, then the fifteen "
                + "head emissions in their exact sequence, then one detail line per transaction, then "
                + "the sixth rule line, the total line and the closing banner")
        void theFullPerStatementEmissionOrderIsPreserved() {
            final int transactions = 2;
            final List<String> artifact = statementTextArtifact(transactions);

            assertAll(
                    () -> assertThat(artifact).hasSize(
                            1 + HEAD_EMISSION_COUNT + transactions + TAIL_EMISSION_COUNT),
                    () -> assertThat(artifact.subList(0, 1 + HEAD_EMISSION_COUNT)).containsExactly(
                            START_BANNER_LINE,
                            nameLine(SAMPLE_NAME),
                            addressLine(SAMPLE_ADDRESS_LINE_1),
                            addressLine(SAMPLE_ADDRESS_LINE_2),
                            thirdAddressLine(SAMPLE_ADDRESS_LINE_3),
                            RULE_LINE,
                            BASIC_DETAILS_LINE,
                            RULE_LINE,
                            labelledValueLine(ACCOUNT_LABEL, SAMPLE_ACCOUNT_ID),
                            balanceLine(SAMPLE_BALANCE),
                            labelledValueLine(SCORE_LABEL, SAMPLE_SCORE),
                            RULE_LINE,
                            SUMMARY_LINE,
                            RULE_LINE,
                            COLUMN_HEADING_LINE,
                            RULE_LINE),
                    () -> assertThat(artifact.subList(1 + HEAD_EMISSION_COUNT,
                            1 + HEAD_EMISSION_COUNT + transactions)).containsExactly(
                                    detailLine(SAMPLE_TRANSACTION_ID, SAMPLE_DESCRIPTION,
                                            SAMPLE_DETAIL_AMOUNT),
                                    detailLine(SAMPLE_TRANSACTION_ID, SAMPLE_DESCRIPTION,
                                            SAMPLE_DETAIL_AMOUNT)),
                    () -> assertThat(artifact.subList(artifact.size() - TAIL_EMISSION_COUNT,
                            artifact.size())).containsExactly(RULE_LINE,
                                    totalLine(SAMPLE_TOTAL_AMOUNT), END_BANNER_LINE));
        }

        @Test
        @DisplayName("the head block emits fifteen lines of which five are rule lines, so the sixth "
                + "rule line can only come from the tail")
        void theHeadBlockEmitsFifteenLinesOfWhichFiveAreRules() {
            final List<String> head = statementTextArtifact(0).subList(1, 1 + HEAD_EMISSION_COUNT);

            assertThat(head).hasSize(HEAD_EMISSION_COUNT);
            assertThat(head.stream().filter(RULE_LINE::equals).count()).isEqualTo(5L);
        }

        @Test
        @DisplayName("the start-of-statement banner is a 31-character asterisk fill, an 18-character "
                + "banner text and a second 31-character asterisk fill, asserted by slicing")
        void theStartOfStatementBannerHasFillWidthsThirtyOneEighteenThirtyOne() {
            assertAll(
                    () -> assertThat(encodedWidthOf(START_BANNER_LINE)).isEqualTo(TEXT_RECORD_WIDTH),
                    () -> assertThat(START_BANNER_FILL_WIDTH + START_BANNER_TEXT_WIDTH
                            + START_BANNER_FILL_WIDTH).isEqualTo(TEXT_RECORD_WIDTH),
                    () -> assertThat(START_BANNER_LINE.substring(0, START_BANNER_FILL_WIDTH))
                            .isEqualTo(ASTERISK.repeat(START_BANNER_FILL_WIDTH)),
                    () -> assertThat(START_BANNER_LINE.substring(START_BANNER_FILL_WIDTH,
                            START_BANNER_FILL_WIDTH + START_BANNER_TEXT_WIDTH))
                            .isEqualTo(START_BANNER_TEXT),
                    () -> assertThat(START_BANNER_LINE.substring(
                            START_BANNER_FILL_WIDTH + START_BANNER_TEXT_WIDTH))
                            .isEqualTo(ASTERISK.repeat(START_BANNER_FILL_WIDTH)),
                    () -> assertThat(encodedWidthOf(START_BANNER_TEXT))
                            .isEqualTo(START_BANNER_TEXT_WIDTH));
        }

        @Test
        @DisplayName("the end-of-statement banner is a 32-character asterisk fill, a 16-character "
                + "banner text and a second 32-character asterisk fill, asserted by slicing")
        void theEndOfStatementBannerHasFillWidthsThirtyTwoSixteenThirtyTwo() {
            assertAll(
                    () -> assertThat(encodedWidthOf(END_BANNER_LINE)).isEqualTo(TEXT_RECORD_WIDTH),
                    () -> assertThat(END_BANNER_FILL_WIDTH + END_BANNER_TEXT_WIDTH
                            + END_BANNER_FILL_WIDTH).isEqualTo(TEXT_RECORD_WIDTH),
                    () -> assertThat(END_BANNER_LINE.substring(0, END_BANNER_FILL_WIDTH))
                            .isEqualTo(ASTERISK.repeat(END_BANNER_FILL_WIDTH)),
                    () -> assertThat(END_BANNER_LINE.substring(END_BANNER_FILL_WIDTH,
                            END_BANNER_FILL_WIDTH + END_BANNER_TEXT_WIDTH))
                            .isEqualTo(END_BANNER_TEXT),
                    () -> assertThat(END_BANNER_LINE.substring(
                            END_BANNER_FILL_WIDTH + END_BANNER_TEXT_WIDTH))
                            .isEqualTo(ASTERISK.repeat(END_BANNER_FILL_WIDTH)),
                    () -> assertThat(encodedWidthOf(END_BANNER_TEXT))
                            .isEqualTo(END_BANNER_TEXT_WIDTH));
        }

        @Test
        @DisplayName("the two banner texts are contract literals reproduced byte for byte, and the two "
                + "banners are not the same line")
        void theTwoBannerTextsAreReproducedByteForByte() {
            assertAll(
                    () -> assertThat(START_BANNER_TEXT).isEqualTo("START OF STATEMENT"),
                    () -> assertThat(END_BANNER_TEXT).isEqualTo("END OF STATEMENT"),
                    () -> assertThat(START_BANNER_LINE).isNotEqualTo(END_BANNER_LINE),
                    () -> assertThat(START_BANNER_LINE).startsWith(ASTERISK).endsWith(ASTERISK),
                    () -> assertThat(END_BANNER_LINE).startsWith(ASTERISK).endsWith(ASTERISK));
        }

        @Test
        @DisplayName("the balance line's edited amount keeps its leading zeros while the detail and "
                + "total lines suppress theirs, and a negative value takes the trailing sign position")
        void theEditedAmountFormsDifferOnZeroSuppressionAndCarryATrailingSign() {
            final String balance = editedAmountWithoutSuppression(SAMPLE_BALANCE);
            final String detail = editedAmountWithSuppression(SAMPLE_DETAIL_AMOUNT);
            final String total = editedAmountWithSuppression(SAMPLE_TOTAL_AMOUNT);

            assertAll(
                    () -> assertThat(balance).isEqualTo("000001234.56 "),
                    () -> assertThat(detail).isEqualTo("       45.67-"),
                    () -> assertThat(total).isEqualTo("     1188.89 "),
                    () -> assertThat(encodedWidthOf(balance)).isEqualTo(AMOUNT_MASK_WIDTH),
                    () -> assertThat(encodedWidthOf(detail)).isEqualTo(AMOUNT_MASK_WIDTH),
                    () -> assertThat(encodedWidthOf(total)).isEqualTo(AMOUNT_MASK_WIDTH),
                    () -> assertThat(editedAmountWithSuppression(new BigDecimal("0.05")))
                            .isEqualTo("         .05 "));
        }

        @Test
        @DisplayName("the hand-built oracle refuses an over-long field rather than truncating, so a "
                + "mistake in an expectation fails loudly instead of producing a plausible wrong image")
        void theHandBuiltOracleRefusesAnOverLongField() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> padRight("X".repeat(TEXT_RECORD_WIDTH + 1), TEXT_RECORD_WIDTH))
                    .withMessageContaining("cannot hold");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> unsignedDigitsOf(new BigDecimal("1.5"),
                            TRANSACTION_AMOUNT_ZONED_WIDTH))
                    .withMessageContaining("scale");
        }
    }

    /**
     * Every markup literal, each paired with a hand-built 100-byte image. The 34 fixed literals come
     * first in their declaration order, then the two substitutable group items.
     *
     * @return the description and hand-built-image pairs
     */
    private static Stream<Arguments> everyMarkupLiteral() {
        final List<Arguments> rows = new ArrayList<>();
        for (int index = 0; index < MARKUP_FIXED_TEMPLATES.size(); index++) {
            rows.add(Arguments.of("fixed markup literal at declaration position " + index,
                    markupRecord(MARKUP_FIXED_TEMPLATES.get(index))));
        }
        rows.add(Arguments.of("account-number heading group item",
                accountHeadingRecord(SAMPLE_ACCOUNT_ID)));
        rows.add(Arguments.of("name paragraph group item", nameParagraphRecord(SAMPLE_NAME)));
        return rows.stream();
    }

    @Nested
    @DisplayName("The 100-byte markup statement contract")
    class MarkupStatementContract {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.carddemo.batch.step.StatementProcessorTest#everyMarkupLiteral")
        @DisplayName("every markup literal measures exactly one hundred encoded bytes, which is the "
                + "width the emitting program declares and the width the conflicting job declaration "
                + "is resolved to")
        void everyMarkupLiteralMeasuresExactlyOneHundredEncodedBytes(final String description,
                final String image) {
            assertThat(encodedWidthOf(image)).as(description).isEqualTo(MARKUP_RECORD_WIDTH);
        }

        @Test
        @DisplayName("a produced markup artifact is an exact multiple of one hundred bytes, and its "
                + "block composition is the header, the name and address and basics block, one block "
                + "per transaction, and the tail")
        void aProducedMarkupArtifactIsAnExactMultipleOfOneHundredBytes() {
            final int transactions = 2;
            final List<String> artifact = statementMarkupArtifact(transactions);

            assertAll(
                    () -> assertThat(artifact).hasSize(MARKUP_HEADER_RECORD_COUNT
                            + MARKUP_BASICS_RECORD_COUNT
                            + (MARKUP_TRANSACTION_RECORD_COUNT * transactions)
                            + MARKUP_TAIL_RECORD_COUNT),
                    () -> assertThat(encodedWidthOf(String.join("", artifact))
                            % MARKUP_RECORD_WIDTH).isZero(),
                    () -> assertThat(artifact).allSatisfy(record ->
                            assertThat(encodedWidthOf(record)).isEqualTo(MARKUP_RECORD_WIDTH)));
        }

        @Test
        @DisplayName("the malformed table-opening literal is emitted UNREPAIRED, double space and all - "
                + "this is faithful legacy output, not a defect to normalise, close differently or tidy")
        void theMalformedTableOpeningLiteralIsEmittedUnrepaired() {
            final List<String> artifact = statementMarkupArtifact(1);
            final String emitted = artifact.get(MALFORMED_LITERAL_INDEX);

            assertAll(
                    () -> assertThat(emitted).isEqualTo(markupRecord(MARKUP_TABLE_OPEN)),
                    () -> assertThat(emitted).startsWith("<table  align="),
                    () -> assertThat(emitted.substring(6, 8)).isEqualTo("  "),
                    () -> assertThat(emitted).doesNotContain("<table align="),
                    () -> assertThat(encodedWidthOf(emitted)).isEqualTo(MARKUP_RECORD_WIDTH),
                    () -> assertThat(artifact.stream()
                            .filter(record -> record.equals(markupRecord(MARKUP_TABLE_OPEN)))
                            .count()).isEqualTo(1L));
        }

        @Test
        @DisplayName("the markup line order is preserved: the document opens on the doctype, closes on "
                + "the closing document tag, and its structural tags keep their emitted positions - no "
                + "templating engine is used or required")
        void theMarkupLineOrderIsPreserved() {
            final List<String> artifact = statementMarkupArtifact(1);

            assertAll(
                    () -> assertThat(artifact.get(0)).isEqualTo(markupRecord(MARKUP_DOCTYPE)),
                    () -> assertThat(artifact.get(1)).isEqualTo(markupRecord(MARKUP_HTML_OPEN)),
                    () -> assertThat(artifact.get(4)).isEqualTo(markupRecord(MARKUP_TITLE)),
                    () -> assertThat(artifact.get(MALFORMED_LITERAL_INDEX))
                            .isEqualTo(markupRecord(MARKUP_TABLE_OPEN)),
                    () -> assertThat(artifact).last().isEqualTo(markupRecord(MARKUP_HTML_CLOSE)),
                    () -> assertThat(artifact.get(artifact.size() - 3))
                            .isEqualTo(markupRecord(MARKUP_TABLE_CLOSE)),
                    () -> assertThat(artifact.get(artifact.size() - 2))
                            .isEqualTo(markupRecord(MARKUP_BODY_CLOSE)));
        }

        @Test
        @DisplayName("the account-number heading group item is a 34-character prefix, a 20-character "
                + "value field and a 5-character suffix, with the substituted value in its own span")
        void theAccountNumberHeadingGroupItemCarriesItsValueInTheCorrectSpan() {
            final String emitted = statementMarkupArtifact(1).get(ACCOUNT_HEADING_INDEX);

            assertAll(
                    () -> assertThat(encodedWidthOf(emitted)).isEqualTo(MARKUP_RECORD_WIDTH),
                    () -> assertThat(emitted.substring(0, ACCOUNT_HEADING_PREFIX_WIDTH))
                            .isEqualTo(ACCOUNT_HEADING_PREFIX),
                    () -> assertThat(emitted.substring(ACCOUNT_HEADING_PREFIX_WIDTH,
                            ACCOUNT_HEADING_PREFIX_WIDTH + ACCOUNT_HEADING_VALUE_WIDTH))
                            .isEqualTo(padRight(SAMPLE_ACCOUNT_ID, ACCOUNT_HEADING_VALUE_WIDTH)),
                    () -> assertThat(emitted.substring(
                            ACCOUNT_HEADING_PREFIX_WIDTH + ACCOUNT_HEADING_VALUE_WIDTH,
                            ACCOUNT_HEADING_PREFIX_WIDTH + ACCOUNT_HEADING_VALUE_WIDTH
                                    + ACCOUNT_HEADING_SUFFIX_WIDTH))
                            .isEqualTo(ACCOUNT_HEADING_SUFFIX),
                    () -> assertThat(StatementHtmlTemplates.ACCOUNT_LINE_PREFIX_LENGTH)
                            .isEqualTo(ACCOUNT_HEADING_PREFIX_WIDTH),
                    () -> assertThat(StatementHtmlTemplates.ACCOUNT_LINE_ACCOUNT_LENGTH)
                            .isEqualTo(ACCOUNT_HEADING_VALUE_WIDTH),
                    () -> assertThat(StatementHtmlTemplates.ACCOUNT_LINE_SUFFIX_LENGTH)
                            .isEqualTo(ACCOUNT_HEADING_SUFFIX_WIDTH),
                    () -> assertThat(StatementHtmlTemplates.ACCOUNT_LINE_DECLARED_LENGTH)
                            .isEqualTo(ACCOUNT_HEADING_PREFIX_WIDTH + ACCOUNT_HEADING_VALUE_WIDTH
                                    + ACCOUNT_HEADING_SUFFIX_WIDTH));
        }

        @Test
        @DisplayName("the name paragraph group item is a 26-character prefix plus a 50-character value "
                + "field, with the substituted value in its own span")
        void theNameParagraphGroupItemCarriesItsValueInTheCorrectSpan() {
            final String emitted = statementMarkupArtifact(1).get(NAME_PARAGRAPH_INDEX);

            assertAll(
                    () -> assertThat(encodedWidthOf(emitted)).isEqualTo(MARKUP_RECORD_WIDTH),
                    () -> assertThat(emitted.substring(0, NAME_PARAGRAPH_PREFIX_WIDTH))
                            .isEqualTo(NAME_PARAGRAPH_PREFIX),
                    () -> assertThat(emitted.substring(NAME_PARAGRAPH_PREFIX_WIDTH,
                            NAME_PARAGRAPH_PREFIX_WIDTH + NAME_PARAGRAPH_VALUE_WIDTH))
                            .isEqualTo(padRight(SAMPLE_NAME, NAME_PARAGRAPH_VALUE_WIDTH)),
                    () -> assertThat(StatementHtmlTemplates.NAME_LINE_PREFIX_LENGTH)
                            .isEqualTo(NAME_PARAGRAPH_PREFIX_WIDTH),
                    () -> assertThat(StatementHtmlTemplates.NAME_LINE_NAME_LENGTH)
                            .isEqualTo(NAME_PARAGRAPH_VALUE_WIDTH),
                    () -> assertThat(StatementHtmlTemplates.NAME_LINE_DECLARED_LENGTH)
                            .isEqualTo(NAME_PARAGRAPH_PREFIX_WIDTH + NAME_PARAGRAPH_VALUE_WIDTH));
        }
    }

    @Nested
    @DisplayName("The template classes under contract, compared against hand-built images")
    class TemplateContract {

        // Every assertion in this group puts the hand-built image on the EXPECTED side and the template
        // class on the ACTUAL side. No template class is ever consulted to obtain an expectation.

        @Test
        @DisplayName("the two record widths the template classes declare are the widths measured from "
                + "the emitting program at lines 45 and 47")
        void theDeclaredRecordWidthsAreTheMeasuredOnes() {
            assertAll(
                    () -> assertThat(StatementTextTemplates.STATEMENT_RECORD_LENGTH)
                            .isEqualTo(TEXT_RECORD_WIDTH),
                    () -> assertThat(StatementHtmlTemplates.HTML_RECORD_LENGTH)
                            .isEqualTo(MARKUP_RECORD_WIDTH),
                    () -> assertThat(StatementHtmlTemplates.FIXED_TEMPLATE_COUNT)
                            .isEqualTo(MARKUP_FIXED_TEMPLATE_COUNT),
                    () -> assertThat(MARKUP_FIXED_TEMPLATES).hasSize(MARKUP_FIXED_TEMPLATE_COUNT));
        }

        @Test
        @DisplayName("THREE rule-line constants are declared and all three are the same 80-hyphen line, "
                + "which is exactly why the six emissions cannot be inferred from the declarations")
        void threeRuleConstantsAreDeclaredAndAllThreeAreTheSameLine() {
            assertAll(
                    () -> assertThat(StatementTextTemplates.RULE_LINE).isEqualTo(RULE_LINE),
                    () -> assertThat(StatementTextTemplates.ST_LINE5_RULE).isEqualTo(RULE_LINE),
                    () -> assertThat(StatementTextTemplates.ST_LINE10_RULE).isEqualTo(RULE_LINE),
                    () -> assertThat(StatementTextTemplates.ST_LINE12_RULE).isEqualTo(RULE_LINE),
                    () -> assertThat(List.of(StatementTextTemplates.ST_LINE5_RULE,
                            StatementTextTemplates.ST_LINE10_RULE,
                            StatementTextTemplates.ST_LINE12_RULE).stream().distinct().count())
                            .isEqualTo(1L),
                    () -> assertThat(RULE_LINES_PER_STATEMENT).isEqualTo(6));
        }

        @Test
        @DisplayName("the fixed text lines the template class declares are byte for byte the lines this "
                + "test built by hand from the measured field widths")
        void theFixedTextLinesMatchTheHandBuiltImages() {
            assertAll(
                    () -> assertThat(StatementTextTemplates.ST_LINE0_START_BANNER)
                            .isEqualTo(START_BANNER_LINE),
                    () -> assertThat(StatementTextTemplates.ST_LINE15_END_BANNER)
                            .isEqualTo(END_BANNER_LINE),
                    () -> assertThat(StatementTextTemplates.ST_LINE6_BASIC_DETAILS_HEADING)
                            .isEqualTo(BASIC_DETAILS_LINE),
                    () -> assertThat(StatementTextTemplates.ST_LINE11_TRANSACTION_SUMMARY_HEADING)
                            .isEqualTo(SUMMARY_LINE),
                    () -> assertThat(StatementTextTemplates.ST_LINE13_TRANSACTION_COLUMN_HEADINGS)
                            .isEqualTo(COLUMN_HEADING_LINE));
        }

        @Test
        @DisplayName("the 34 markup literals the template class declares are byte for byte, and in the "
                + "same declaration order, the literals this test transcribed by hand - the malformed "
                + "table-opening literal included, unrepaired")
        void theThirtyFourMarkupLiteralsMatchTheHandBuiltImagesInDeclarationOrder() {
            final List<String> declared = List.of(StatementHtmlTemplates.HTML_L01,
                    StatementHtmlTemplates.HTML_L02, StatementHtmlTemplates.HTML_L03,
                    StatementHtmlTemplates.HTML_L04, StatementHtmlTemplates.HTML_L05,
                    StatementHtmlTemplates.HTML_L06, StatementHtmlTemplates.HTML_L07,
                    StatementHtmlTemplates.HTML_L08, StatementHtmlTemplates.HTML_LTRS,
                    StatementHtmlTemplates.HTML_LTRE, StatementHtmlTemplates.HTML_LTDS,
                    StatementHtmlTemplates.HTML_LTDE, StatementHtmlTemplates.HTML_L10,
                    StatementHtmlTemplates.HTML_L15, StatementHtmlTemplates.HTML_L16,
                    StatementHtmlTemplates.HTML_L17, StatementHtmlTemplates.HTML_L18,
                    StatementHtmlTemplates.HTML_L22_35, StatementHtmlTemplates.HTML_L30_42,
                    StatementHtmlTemplates.HTML_L31, StatementHtmlTemplates.HTML_L43,
                    StatementHtmlTemplates.HTML_L47, StatementHtmlTemplates.HTML_L48,
                    StatementHtmlTemplates.HTML_L50, StatementHtmlTemplates.HTML_L51,
                    StatementHtmlTemplates.HTML_L53, StatementHtmlTemplates.HTML_L54,
                    StatementHtmlTemplates.HTML_L58, StatementHtmlTemplates.HTML_L61,
                    StatementHtmlTemplates.HTML_L64, StatementHtmlTemplates.HTML_L75,
                    StatementHtmlTemplates.HTML_L78, StatementHtmlTemplates.HTML_L79,
                    StatementHtmlTemplates.HTML_L80);

            assertThat(declared).hasSize(MARKUP_FIXED_TEMPLATE_COUNT);
            for (int index = 0; index < declared.size(); index++) {
                final String handBuilt = markupRecord(MARKUP_FIXED_TEMPLATES.get(index));
                assertThat(declared.get(index)).as("declaration position %d", index)
                        .isEqualTo(handBuilt);
            }
            assertThat(declared.get(MALFORMED_LITERAL_INDEX).substring(6, 8)).isEqualTo("  ");
        }
    }

    @Nested
    @DisplayName("The data-access linkage contract: one parameter object for 13 call sites")
    class LinkageContract {

        @Test
        @DisplayName("the parameter object's character fields are held at their declared widths - 8 for "
                + "the resource identifier, 1 for the operation, 2 for the result status, 25 for the "
                + "key and 1000 for the payload - measured in encoded bytes")
        void theParameterObjectFieldsAreHeldAtTheirDeclaredWidths() {
            final StatementFileRequest request = new StatementFileRequest(SELECTOR_TRNXFILE,
                    OPERATION_READ, STATUS_SUCCESS, SAMPLE_CARD_NUMBER,
                    encodedWidthOf(SAMPLE_CARD_NUMBER),
                    transactionWorkRecord(SAMPLE_CARD_NUMBER, SAMPLE_TRANSACTION_ID,
                            SAMPLE_DETAIL_AMOUNT),
                    0);

            assertAll(
                    () -> assertThat(encodedWidthOf(request.ddName())).isEqualTo(LINKAGE_DD_WIDTH),
                    () -> assertThat(encodedWidthOf(request.operation()))
                            .isEqualTo(LINKAGE_OPERATION_WIDTH),
                    () -> assertThat(encodedWidthOf(request.returnCode()))
                            .isEqualTo(LINKAGE_RETURN_CODE_WIDTH),
                    () -> assertThat(encodedWidthOf(request.key())).isEqualTo(LINKAGE_KEY_WIDTH),
                    () -> assertThat(encodedWidthOf(request.payload()))
                            .isEqualTo(LINKAGE_PAYLOAD_WIDTH),
                    () -> assertThat(request.keyLength())
                            .isEqualTo(encodedWidthOf(SAMPLE_CARD_NUMBER)));
        }

        @Test
        @DisplayName("the declared widths the collaborator publishes are the widths measured from the "
                + "helper's linkage area")
        void theDeclaredWidthsAgreeWithTheMeasuredLinkageArea() {
            assertAll(
                    () -> assertThat(StatementDataAccessService.DD_NAME_WIDTH)
                            .isEqualTo(LINKAGE_DD_WIDTH),
                    () -> assertThat(StatementDataAccessService.OPERATION_WIDTH)
                            .isEqualTo(LINKAGE_OPERATION_WIDTH),
                    () -> assertThat(StatementDataAccessService.RETURN_CODE_WIDTH)
                            .isEqualTo(LINKAGE_RETURN_CODE_WIDTH),
                    () -> assertThat(StatementDataAccessService.KEY_WIDTH)
                            .isEqualTo(LINKAGE_KEY_WIDTH),
                    () -> assertThat(StatementDataAccessService.PAYLOAD_WIDTH)
                            .isEqualTo(LINKAGE_PAYLOAD_WIDTH));
        }

        @ParameterizedTest(name = "operation {0} is representable")
        @CsvSource({"O", "C", "R", "K", "W", "Z"})
        @DisplayName("all six operation discriminators are representable in the one-character field, "
                + "and the parameter object answers its own operation predicate for each")
        void allSixOperationDiscriminatorsAreRepresentable(final String operation) {
            final StatementFileRequest request = new StatementFileRequest(SELECTOR_ACCTFILE,
                    operation, STATUS_SUCCESS, "", 0, "", 0);

            assertAll(
                    () -> assertThat(SIX_OPERATIONS).contains(operation),
                    () -> assertThat(request.hasOperation(operation)).isTrue(),
                    () -> assertThat(encodedWidthOf(request.operation()))
                            .isEqualTo(LINKAGE_OPERATION_WIDTH));
        }

        @Test
        @DisplayName("the operation vocabulary is exactly six distinct codes - open, close, read, keyed "
                + "read, write and rewrite - and the collaborator publishes the same six")
        void theOperationVocabularyIsExactlySixDistinctCodes() {
            assertAll(
                    () -> assertThat(SIX_OPERATIONS).hasSize(6).doesNotHaveDuplicates(),
                    () -> assertThat(StatementDataAccessService.OPERATION_OPEN)
                            .isEqualTo(OPERATION_OPEN),
                    () -> assertThat(StatementDataAccessService.OPERATION_CLOSE)
                            .isEqualTo(OPERATION_CLOSE),
                    () -> assertThat(StatementDataAccessService.OPERATION_READ)
                            .isEqualTo(OPERATION_READ),
                    () -> assertThat(StatementDataAccessService.OPERATION_READ_KEYED)
                            .isEqualTo(OPERATION_READ_KEYED),
                    () -> assertThat(StatementDataAccessService.OPERATION_WRITE)
                            .isEqualTo(OPERATION_WRITE),
                    () -> assertThat(StatementDataAccessService.OPERATION_REWRITE)
                            .isEqualTo(OPERATION_REWRITE));
        }

        @Test
        @DisplayName("the resource identifier and the operation are populated before every call, and "
                + "each of the four resources is named by its own constant")
        void theResourceIdentifierAndOperationArePopulatedBeforeEveryCall() {
            final int records = 2;
            final int expectedCalls = 4 + records + 1;

            driveDerivedExecutionOrder(records, STATUS_SUCCESS);

            verify(statementDataAccessService, times(expectedCalls)).execute(argThat(request ->
                    List.of(SELECTOR_TRNXFILE, SELECTOR_XREFFILE, SELECTOR_CUSTFILE,
                            SELECTOR_ACCTFILE).contains(request.ddName())
                            && SIX_OPERATIONS.contains(request.operation())),
                    any(), any());
            assertAll(
                    () -> assertThat(StatementDataAccessService.DD_TRNXFILE)
                            .isEqualTo(SELECTOR_TRNXFILE),
                    () -> assertThat(StatementDataAccessService.DD_XREFFILE)
                            .isEqualTo(SELECTOR_XREFFILE),
                    () -> assertThat(StatementDataAccessService.DD_CUSTFILE)
                            .isEqualTo(SELECTOR_CUSTFILE),
                    () -> assertThat(StatementDataAccessService.DD_ACCTFILE)
                            .isEqualTo(SELECTOR_ACCTFILE));
        }

        @Test
        @DisplayName("the ordered cascade's stages run in order where observable: the four resource "
                + "handlers are consulted in the derived execution order, each echoing its own resource "
                + "identifier back through the intermediate exit stage")
        void theOrderedCascadeStagesRunInOrder() {
            final List<String> consulted = new ArrayList<>();
            for (final String selector : DERIVED_PHASE_ORDER) {
                if (SELECTOR_READTRNX.equals(selector)) {
                    continue;
                }
                plannedStatuses.add(STATUS_SUCCESS);
                consulted.add(issue(selector, OPERATION_OPEN, 0).ddName());
            }

            assertThat(consulted).containsExactly(SELECTOR_TRNXFILE, SELECTOR_XREFFILE,
                    SELECTOR_CUSTFILE, SELECTOR_ACCTFILE);
        }

        @Test
        @DisplayName("the response half carries the resource identifier, the raw status and the payload "
                + "at their declared widths, so a caller correlating a response needs no bookkeeping")
        void theResponseHalfCarriesItsFieldsAtTheDeclaredWidths() {
            final StatementFileResponse response = new StatementFileResponse(SELECTOR_CUSTFILE,
                    STATUS_SUCCESS, transactionWorkRecord(SAMPLE_CARD_NUMBER, SAMPLE_TRANSACTION_ID,
                            SAMPLE_DETAIL_AMOUNT), 4);

            assertAll(
                    () -> assertThat(encodedWidthOf(response.ddName())).isEqualTo(LINKAGE_DD_WIDTH),
                    () -> assertThat(encodedWidthOf(response.returnCode()))
                            .isEqualTo(LINKAGE_RETURN_CODE_WIDTH),
                    () -> assertThat(encodedWidthOf(response.payload()))
                            .isEqualTo(LINKAGE_PAYLOAD_WIDTH),
                    () -> assertThat(response.sequentialPosition()).isEqualTo(4));
        }

        @Test
        @DisplayName("a negative sequential position is refused by both halves, because a sequential "
                + "read has no position before the first record")
        void aNegativeSequentialPositionIsRefused() {
            assertThatIllegalArgumentException().isThrownBy(() -> new StatementFileRequest(
                    SELECTOR_TRNXFILE, OPERATION_READ, STATUS_SUCCESS, "", 0, "", -1))
                    .withMessageContaining("sequentialPosition");
            assertThatIllegalArgumentException().isThrownBy(() -> new StatementFileResponse(
                    SELECTOR_TRNXFILE, STATUS_SUCCESS, "", -1))
                    .withMessageContaining("sequentialPosition");
        }

        @Test
        @DisplayName("the 350-byte transaction work record the read phase tables fits the payload field "
                + "with its 32-byte key and its 318-byte remainder intact, and its amount is a "
                + "hand-overpunched zoned field")
        void theTransactionWorkRecordFitsThePayloadFieldIntact() {
            final String image = transactionWorkRecord(SAMPLE_CARD_NUMBER, SAMPLE_TRANSACTION_ID,
                    SAMPLE_DETAIL_AMOUNT);
            final StatementFileRequest request = new StatementFileRequest(SELECTOR_TRNXFILE,
                    OPERATION_READ, STATUS_SUCCESS, "", 0, image, 0);

            assertAll(
                    () -> assertThat(encodedWidthOf(image)).isEqualTo(TRANSACTION_WORK_RECORD_WIDTH),
                    () -> assertThat(encodedWidthOf(image.substring(32)))
                            .isEqualTo(TRANSACTION_WORK_REMAINDER_WIDTH),
                    () -> assertThat(image.substring(0, 16)).isEqualTo(SAMPLE_CARD_NUMBER),
                    () -> assertThat(image.substring(16, 32)).isEqualTo(SAMPLE_TRANSACTION_ID),
                    () -> assertThat(request.payload().substring(0,
                            TRANSACTION_WORK_RECORD_WIDTH)).isEqualTo(image),
                    () -> assertThat(request.payload()
                            .substring(TRANSACTION_WORK_RECORD_WIDTH, LINKAGE_PAYLOAD_WIDTH))
                            .isEqualTo(SPACE.repeat(
                                    LINKAGE_PAYLOAD_WIDTH - TRANSACTION_WORK_RECORD_WIDTH)));
        }

        @Test
        @DisplayName("the zoned-decimal fields are overpunched by hand at their measured widths - "
                + "eleven bytes for a nine-integer-digit amount, twelve for a ten-integer-digit "
                + "amount and six for a four-integer-digit rate")
        void theZonedDecimalFieldsAreOverpunchedByHandAtTheirMeasuredWidths() {
            final String positive = zonedDecimalOf(SAMPLE_BALANCE, TRANSACTION_AMOUNT_ZONED_WIDTH);
            final String negative =
                    zonedDecimalOf(SAMPLE_DETAIL_AMOUNT, TRANSACTION_AMOUNT_ZONED_WIDTH);
            final String accountField =
                    zonedDecimalOf(SAMPLE_ACCOUNT_AMOUNT, ACCOUNT_AMOUNT_ZONED_WIDTH);
            final String rateField = zonedDecimalOf(SAMPLE_RATE, RATE_ZONED_WIDTH);

            assertAll(
                    () -> assertThat(positive).isEqualTo("0000012345F"),
                    () -> assertThat(negative).isEqualTo("0000000456P"),
                    () -> assertThat(accountField).isEqualTo("98765432109I"),
                    () -> assertThat(rateField).isEqualTo("12345F"),
                    () -> assertThat(negative.charAt(TRANSACTION_AMOUNT_ZONED_WIDTH - 1))
                            .isEqualTo(NEGATIVE_OVERPUNCH.charAt(7)),
                    () -> assertThat(positive.charAt(TRANSACTION_AMOUNT_ZONED_WIDTH - 1))
                            .isEqualTo(POSITIVE_OVERPUNCH.charAt(6)),
                    () -> assertThat(encodedWidthOf(positive))
                            .isEqualTo(TRANSACTION_AMOUNT_ZONED_WIDTH),
                    () -> assertThat(encodedWidthOf(accountField))
                            .isEqualTo(ACCOUNT_AMOUNT_ZONED_WIDTH),
                    () -> assertThat(encodedWidthOf(rateField)).isEqualTo(RATE_ZONED_WIDTH),
                    () -> assertThat(POSITIVE_OVERPUNCH).hasSize(10),
                    () -> assertThat(NEGATIVE_OVERPUNCH).hasSize(10));
        }
    }

    @Nested
    @DisplayName("The customer layout: an alternate view of ONE entity")
    class CustomerAlternateView {

        @Test
        @DisplayName("the alternate 500-byte layout is a view of the same entity, so both a record "
                + "populated through the alternate naming and one populated canonically are the same "
                + "type - there is no second customer type, no second reader and no second table")
        void theAlternateLayoutIsAViewOfTheSameEntity() {
            final Customer canonical = customer(SAMPLE_NAME, SAMPLE_ADDRESS_LINE_2);
            final Customer alternate = customer(SAMPLE_NAME, SAMPLE_ADDRESS_LINE_2);

            assertAll(
                    () -> assertThat(canonical).isExactlyInstanceOf(Customer.class),
                    () -> assertThat(alternate.getClass()).isSameAs(canonical.getClass()),
                    () -> assertThat(alternate.getCustDob()).isEqualTo(canonical.getCustDob()),
                    () -> assertThat(alternate.getCustId()).isEqualTo(SAMPLE_CUSTOMER_ID));
        }

        @Test
        @DisplayName("the 500-byte layout's field widths sum to exactly five hundred, filler included")
        void theLayoutFieldWidthsSumToFiveHundred() {
            final List<Integer> widths = List.of(9, 25, 25, 25, 50, 50, 50, 2, 3, 10, 15, 15, 9, 20,
                    10, 10, 1, 3, 168);

            assertThat(widths.stream().mapToInt(Integer::intValue).sum())
                    .isEqualTo(CUSTOMER_RECORD_WIDTH);
            assertThat(widths).hasSize(19);
        }

        @Test
        @DisplayName("neither the middle name nor the second address line carries a validation "
                + "constraint: a record that populates neither is accepted unchanged")
        void aRecordThatPopulatesNeitherFieldIsAccepted() {
            final Customer sparse = customer(null, null);

            assertAll(
                    () -> assertThat(sparse.getMiddleName()).isNull(),
                    () -> assertThat(sparse.getAddrLine2()).isNull(),
                    () -> assertThat(sparse.getAddrLine1()).isEqualTo(SAMPLE_ADDRESS_LINE_1),
                    () -> assertThat(sparse.getAddrLine3()).isEqualTo(SAMPLE_ADDRESS_LINE_3));
        }

        @Test
        @DisplayName("neither field carries a validation constraint the other way either: a record that "
                + "populates both with arbitrary content is accepted unchanged, because rejecting it "
                + "would reject input the legacy accepts")
        void aRecordThatPopulatesBothFieldsWithArbitraryContentIsAccepted() {
            final String arbitraryMiddleName = "  A-B'C  ";
            final String arbitrarySecondLine = "#$%& UNIT 7 (REAR)";
            final Customer permissive = customer(arbitraryMiddleName, arbitrarySecondLine);

            assertAll(
                    () -> assertThat(permissive.getMiddleName()).isEqualTo(arbitraryMiddleName),
                    () -> assertThat(permissive.getAddrLine2()).isEqualTo(arbitrarySecondLine),
                    () -> assertThat(permissive.getFicoCreditScore()).isEqualTo(SAMPLE_SCORE));
        }

        /**
         * Builds one customer at the alternate view's field set. The two regulated identifiers are the
         * shared synthetic protected values, so no cleartext identifier appears anywhere in this file.
         *
         * @param middleName  the never-validated middle name, which may be absent
         * @param addressLine the never-validated second address line, which may be absent
         * @return the customer
         */
        private Customer customer(final String middleName, final String addressLine) {
            return new Customer(SAMPLE_CUSTOMER_ID, "JOHN", middleName, "SMITH",
                    SAMPLE_ADDRESS_LINE_1, addressLine, SAMPLE_ADDRESS_LINE_3, "WA", "USA", "99999",
                    "206-555-0100", "206-555-0101", TestDataFactory.SYNTHETIC_PROTECTED_VALUE,
                    TestDataFactory.SYNTHETIC_PROTECTED_VALUE, "1980-01-15", "0000000001", "Y",
                    SAMPLE_SCORE);
        }
    }

    @Nested
    @DisplayName("Determinism, timestamps and the values the statement lines carry")
    class DeterminismAndCarriedValues {

        @Test
        @DisplayName("the batch timestamp form carries a hyphen at position 11, dots at 14, 17 and 20, "
                + "two hundredths digits at 21 and 22 and a fixed four-character trailer at 23 to 26")
        void theBatchTimestampFormHasItsSeparatorsInTheBatchPositions() {
            final String batch = batchTimestampOf(FIXED_CLOCK);

            assertAll(
                    () -> assertThat(encodedWidthOf(batch)).isEqualTo(TIMESTAMP_WIDTH),
                    () -> assertThat(batch.charAt(10)).isEqualTo('-'),
                    () -> assertThat(batch.charAt(13)).isEqualTo('.'),
                    () -> assertThat(batch.charAt(16)).isEqualTo('.'),
                    () -> assertThat(batch.charAt(19)).isEqualTo('.'),
                    () -> assertThat(batch.substring(20, 22)).containsOnlyDigits(),
                    () -> assertThat(batch.substring(22)).isEqualTo(BATCH_TIMESTAMP_TRAILER),
                    () -> assertThat(batch).isEqualTo("2022-06-10-19.27.53.000000"));
        }

        @Test
        @DisplayName("the batch form is categorically not the online form: the online form has a space "
                + "at position 11, colons at 14 and 17 and a period at 20 with a six-digit fraction")
        void theBatchFormIsCategoricallyNotTheOnlineForm() {
            final String batch = batchTimestampOf(FIXED_CLOCK);
            final String online = TestDataFactory.recordTimestampAt(FIXED_CLOCK);

            assertAll(
                    () -> assertThat(encodedWidthOf(online))
                            .isEqualTo(TestDataFactory.TIMESTAMP_TEXT_WIDTH),
                    () -> assertThat(online.charAt(10)).isEqualTo(' '),
                    () -> assertThat(online.charAt(13)).isEqualTo(':'),
                    () -> assertThat(online.charAt(16)).isEqualTo(':'),
                    () -> assertThat(online.charAt(19)).isEqualTo('.'),
                    () -> assertThat(batch).isNotEqualTo(online),
                    () -> assertThat(batch.charAt(10)).isNotEqualTo(online.charAt(10)),
                    () -> assertThat(batch.charAt(13)).isNotEqualTo(online.charAt(13)));
        }

        @Test
        @DisplayName("the date portion survives a strict parse, so an impossible calendar date would "
                + "fail rather than being silently normalised")
        void theDatePortionSurvivesAStrictParse() {
            final String batch = batchTimestampOf(FIXED_CLOCK);

            final LocalDate parsed = LocalDate.parse(batch.substring(0, 10), STRICT_DATE);

            assertThat(parsed).isEqualTo(LocalDate.ofInstant(PINNED_INSTANT, ZoneOffset.UTC));
            assertThatExceptionOfType(RuntimeException.class)
                    .isThrownBy(() -> LocalDate.parse("2022-02-30", STRICT_DATE));
        }

        @Test
        @DisplayName("a blank passed-through processing timestamp survives as twenty-six spaces rather "
                + "than becoming absent, empty or a rendered date - which is what the measured fixture "
                + "carries on every one of its rows")
        void aBlankPassedThroughTimestampSurvivesAsTwentySixSpaces() {
            final StatementLineSummary summary = new StatementLineSummary(SAMPLE_CARD_NUMBER,
                    SAMPLE_TRANSACTION_ID, "01", "0005", "POS TERM", SAMPLE_DESCRIPTION,
                    SAMPLE_DETAIL_AMOUNT, "123456789", "SAMPLE MERCHANT", "SEATTLE", "99999",
                    TestDataFactory.SEEDED_ORIGINAL_TIMESTAMP, BLANK_TIMESTAMP);
            when(statementGenerationService.generate(any(), any(), any(), any()))
                    .thenAnswer(runWithSummaries(List.of(summary)));

            final StatementRun answered = processor.process(request(item));

            assertAll(
                    () -> assertThat(answered.transactionSummariesEmitted()).isEqualTo(1),
                    () -> assertThat(sink.transactionSummaries).hasSize(1),
                    () -> assertThat(sink.transactionSummaries.get(0).processingTimestamp())
                            .isNotNull()
                            .isNotEmpty()
                            .isEqualTo(BLANK_TIMESTAMP)
                            .isEqualTo(TestDataFactory.BLANK_PROCESSING_TIMESTAMP),
                    () -> assertThat(encodedWidthOf(sink.transactionSummaries.get(0)
                            .processingTimestamp())).isEqualTo(TIMESTAMP_WIDTH),
                    () -> assertThat(sink.transactionSummaries.get(0).originationTimestamp())
                            .isEqualTo(TestDataFactory.SEEDED_ORIGINAL_TIMESTAMP));
        }

        @Test
        @DisplayName("the blank stamp also survives the outward-facing summary carrier the generator "
                + "publishes, whose declared field bounds admit a 26-character value and whose masking "
                + "rendering never reaches the carried value itself")
        void aBlankStampAlsoSurvivesTheOutwardFacingSummaryCarrier() {
            final StatementSummary published = new StatementSummary(SAMPLE_CARD_NUMBER,
                    SAMPLE_TRANSACTION_ID, "01", "0005", "POS TERM", SAMPLE_DESCRIPTION,
                    SAMPLE_DETAIL_AMOUNT, "123456789", "SAMPLE MERCHANT", "SEATTLE", "99999",
                    TestDataFactory.SEEDED_ORIGINAL_TIMESTAMP, BLANK_TIMESTAMP);

            assertAll(
                    () -> assertThat(published.processingTimestamp()).isNotNull().isNotEmpty()
                            .isEqualTo(BLANK_TIMESTAMP),
                    () -> assertThat(encodedWidthOf(published.processingTimestamp()))
                            .isEqualTo(TIMESTAMP_WIDTH),
                    () -> assertThat(published.originationTimestamp())
                            .isEqualTo(TestDataFactory.SEEDED_ORIGINAL_TIMESTAMP),
                    () -> assertThat(published.amount().scale()).isEqualTo(MONETARY_SCALE),
                    () -> assertThat(published.toString()).doesNotContain(SAMPLE_CARD_NUMBER));
        }

        @Test
        @DisplayName("the account and transaction values the statement lines render carry the estate's "
                + "monetary scale and reach the edited masks without any scaling operation")
        void theCarriedMonetaryValuesKeepTheEstatesScale() {
            final Account account = new Account(SAMPLE_ACCOUNT_ID, "Y", SAMPLE_BALANCE,
                    SAMPLE_ACCOUNT_AMOUNT, SAMPLE_ACCOUNT_AMOUNT, "2020-01-01", "2030-01-01",
                    "2025-01-01", SAMPLE_BALANCE, SAMPLE_BALANCE, "99999", "A");
            final Transaction transaction = new Transaction(SAMPLE_TRANSACTION_ID, "01", "0005",
                    "POS TERM", SAMPLE_DESCRIPTION, SAMPLE_DETAIL_AMOUNT, "123456789",
                    "SAMPLE MERCHANT", "SEATTLE", "99999", SAMPLE_CARD_NUMBER,
                    TestDataFactory.SEEDED_ORIGINAL_TIMESTAMP, BLANK_TIMESTAMP);

            assertAll(
                    () -> assertThat(account.getAcctCurrBal().scale()).isEqualTo(MONETARY_SCALE),
                    () -> assertThat(transaction.getTranAmt().scale()).isEqualTo(MONETARY_SCALE),
                    () -> assertThat(balanceLine(account.getAcctCurrBal()))
                            .isEqualTo(balanceLine(SAMPLE_BALANCE)),
                    () -> assertThat(detailLine(transaction.getTranId(), transaction.getTranDesc(),
                            transaction.getTranAmt())).isEqualTo(detailLine(SAMPLE_TRANSACTION_ID,
                                    SAMPLE_DESCRIPTION, SAMPLE_DETAIL_AMOUNT)),
                    () -> assertThat(transaction.getTranProcTs()).isEqualTo(BLANK_TIMESTAMP));
        }

        @Test
        @DisplayName("the cross-reference walk the generator uses comes from the injected collaborator, "
                + "so the stage itself never opens the cross-reference resource")
        void theCrossReferenceWalkComesFromTheInjectedCollaborator() {
            final CardCrossReference xref = new CardCrossReference(SAMPLE_CARD_NUMBER,
                    SAMPLE_CUSTOMER_ID, SAMPLE_ACCOUNT_ID);
            crossReferenceSource = position -> (position == 0) ? Optional.of(xref) : Optional.empty();
            when(statementDataAccessService.openCrossReferenceSource())
                    .thenReturn(crossReferenceSource);

            final StatementCrossReferenceSource walk =
                    statementDataAccessService.openCrossReferenceSource();

            assertAll(
                    () -> assertThat(walk.readAt(0)).contains(xref),
                    () -> assertThat(walk.readAt(1)).isEmpty(),
                    () -> assertThat(xref.getXrefAcctId()).isEqualTo(SAMPLE_ACCOUNT_ID),
                    () -> assertThat(SensitiveValues.fingerprint(xref.getXrefCardNum()))
                            .isEqualTo(SensitiveValues.fingerprint(SAMPLE_CARD_NUMBER)));
            verify(statementDataAccessService).openCrossReferenceSource();
        }

        @Test
        @DisplayName("the provenance identifiers this test carries in its documentation are the ones the "
                + "shared factory publishes, and the measured record widths agree across both")
        void theProvenanceIdentifiersAndMeasuredWidthsAgreeAcrossTheModule() {
            assertAll(
                    () -> assertThat(TestDataFactory.VERIFIED_CHECKOUT_COMMIT)
                            .isEqualTo("7756d895ffeb65f7ea72aaa609e356d9899afcec"),
                    () -> assertThat(TestDataFactory.UPSTREAM_RELEASE_STAMP)
                            .isEqualTo("CardDemo_v1.0-15-g27d6c6f-68"),
                    () -> assertThat(TestDataFactory.STATEMENT_TEXT_WIDTH)
                            .isEqualTo(TEXT_RECORD_WIDTH),
                    () -> assertThat(TestDataFactory.STATEMENT_MARKUP_WIDTH)
                            .isEqualTo(MARKUP_RECORD_WIDTH),
                    () -> assertThat(TestDataFactory.TIMESTAMP_TEXT_WIDTH)
                            .isEqualTo(TIMESTAMP_WIDTH));
        }
    }

    @Nested
    @DisplayName("The abend path")
    class AbendPath {

        @Test
        @DisplayName("the abend carries the batch abend code the exception class publishes, never a "
                + "re-declared literal, and its context fields are 4, 8, 50 and 72 wide summing to 134")
        void theAbendCarriesTheClassConstantAndTheDeclaredContextWidths() {
            final AbendException abend = new AbendException(AbendException.BATCH_ABEND_CODE,
                    StatementProcessor.LEGACY_PROGRAM, "STATEMENT RUN FAILED",
                    AbendException.DEFAULT_MESSAGE);

            assertAll(
                    () -> assertThat(abend.code()).isEqualTo(AbendException.BATCH_ABEND_CODE),
                    () -> assertThat(abend.culprit()).isEqualTo(StatementProcessor.LEGACY_PROGRAM),
                    () -> assertThat(AbendException.CODE_LENGTH).isEqualTo(ABEND_CODE_WIDTH),
                    () -> assertThat(AbendException.CULPRIT_LENGTH).isEqualTo(ABEND_CULPRIT_WIDTH),
                    () -> assertThat(AbendException.REASON_LENGTH).isEqualTo(ABEND_REASON_WIDTH),
                    () -> assertThat(AbendException.MESSAGE_LENGTH).isEqualTo(ABEND_MESSAGE_WIDTH),
                    () -> assertThat(AbendException.CONTEXT_LENGTH).isEqualTo(ABEND_CONTEXT_WIDTH),
                    () -> assertThat(ABEND_CONTEXT_WIDTH).isEqualTo(134),
                    () -> assertThat(encodedWidthOf(abend.toFixedWidthContext()))
                            .isEqualTo(ABEND_CONTEXT_WIDTH));
        }

        @Test
        @DisplayName("the stage rethrows the abend it observed rather than wrapping it, times the run "
                + "as a failure and credits no counter")
        void theStageRethrowsTheAbendItObserved() {
            final AbendException abend =
                    new AbendException(StatementProcessor.LEGACY_PROGRAM, "STATEMENT RUN FAILED");
            when(statementGenerationService.generate(any(), any(), any(), any())).thenThrow(abend);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> processor.process(request(item))).isSameAs(abend);

            assertAll(
                    () -> assertThat(abend.code()).isEqualTo(AbendException.BATCH_ABEND_CODE),
                    () -> assertThat(abend.getMessage()).isEqualTo(AbendException.DEFAULT_MESSAGE),
                    () -> assertThat(timed("FAILED")).isEqualTo(1L),
                    () -> assertThat(timed("COMPLETED")).isZero(),
                    () -> assertThat(counted("carddemo.batch.statement.records")).isZero(),
                    () -> assertThat(counted("carddemo.batch.statement.statements")).isZero());
        }

        @Test
        @DisplayName("the diagnostic is recorded before the exception is raised, so an operator reading "
                + "the log sees the returned code that caused the abend and not only the abend itself")
        void theDiagnosticIsRecordedBeforeTheExceptionIsRaised() {
            final AbendException abend = new AbendException(StatementProcessor.LEGACY_PROGRAM,
                    "ERROR READING " + SELECTOR_CUSTFILE);
            when(statementGenerationService.generate(any(), any(), any(), any())).thenAnswer(invocation -> {
                journal.add("diagnostic");
                journal.add("exception");
                throw abend;
            });

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> processor.process(request(item))).isSameAs(abend);

            assertThat(journal).containsExactly("diagnostic", "exception");
            assertThat(journal.indexOf("diagnostic")).isLessThan(journal.indexOf("exception"));
        }
    }

    @Nested
    @DisplayName("The record-width proof")
    class RecordWidthProof {

        @Test
        @DisplayName("a plain statement record narrower than eighty bytes is refused")
        void aNarrowStatementRecordIsRefused() {
            when(statementGenerationService.generate(any(), any(), any(), any())).thenAnswer(
                    run(List.of("TOO SHORT"), List.of(markupPlaceholderRecord()), 1, 1, 1));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> processor.process(request(item)))
                    .withMessageContaining("STMTFILE");
        }

        @Test
        @DisplayName("a plain statement record wider than eighty bytes is refused")
        void aWideStatementRecordIsRefused() {
            when(statementGenerationService.generate(any(), any(), any(), any())).thenAnswer(
                    run(List.of("S".repeat(TEXT_RECORD_WIDTH + 1)),
                            List.of(markupPlaceholderRecord()), 1, 1, 1));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> processor.process(request(item)))
                    .withMessageContaining("STMTFILE");
        }

        @Test
        @DisplayName("a markup record of the wrong width is refused, and the message names its own "
                + "dataset rather than the plain one")
        void aMarkupRecordOfTheWrongWidthIsRefused() {
            when(statementGenerationService.generate(any(), any(), any(), any())).thenAnswer(
                    run(List.of(statementRecord()), List.of("H".repeat(MARKUP_RECORD_WIDTH - 1)), 1,
                            1, 1));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> processor.process(request(item)))
                    .withMessageContaining("HTMLFILE");
        }

        @Test
        @DisplayName("a bad record in a later position is caught, not only the first one")
        void aBadRecordInALaterPositionIsCaught() {
            when(statementGenerationService.generate(any(), any(), any(), any())).thenAnswer(
                    run(List.of(statementRecord(), statementRecord(), "SHORT"),
                            List.of(markupPlaceholderRecord()), 1, 1, 1));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> processor.process(request(item)));
            assertThat(timed("FAILED")).isEqualTo(1L);
        }

        @Test
        @DisplayName("records at exactly the declared widths are accepted, and the handed-on records "
                + "measure eighty and one hundred ENCODED BYTES rather than characters")
        void recordsAtExactlyTheDeclaredWidthsAreAccepted() {
            when(statementGenerationService.generate(any(), any(), any(), any())).thenAnswer(wellFormedRun());

            final StatementRun answered = processor.process(request(item));

            assertThat(answered.statementRecordsEmitted()).isEqualTo(sink.statementRecords.size());
            assertThat(answered.htmlRecordsEmitted()).isEqualTo(sink.htmlRecords.size());
            assertThat(sink.statementRecords).allSatisfy(record ->
                    assertThat(encodedWidthOf(record)).isEqualTo(TEXT_RECORD_WIDTH));
            assertThat(sink.htmlRecords).allSatisfy(record ->
                    assertThat(encodedWidthOf(record)).isEqualTo(MARKUP_RECORD_WIDTH));
        }

        @Test
        @DisplayName("a hand-built statement artifact and a hand-built markup artifact both pass the "
                + "width proof, so the shapes this test assembled are shapes the stage accepts")
        void theHandBuiltArtifactsBothPassTheWidthProof() {
            final int transactions = 3;
            when(statementGenerationService.generate(any(), any(), any(), any())).thenAnswer(emitting(
                    statementTextArtifact(transactions), statementMarkupArtifact(transactions),
                    List.<StatementLineSummary>of(), completeDispatchTrail(), 1, transactions, 1));

            final StatementRun answered = processor.process(request(item));

            assertAll(
                    () -> assertThat(sink.statementRecords)
                            .hasSize(1 + HEAD_EMISSION_COUNT + transactions + TAIL_EMISSION_COUNT),
                    () -> assertThat(answered.statementRecordsEmitted())
                            .isEqualTo(sink.statementRecords.size()),
                    () -> assertThat(sink.htmlRecords)
                            .hasSize(MARKUP_HEADER_RECORD_COUNT + MARKUP_BASICS_RECORD_COUNT
                                    + (MARKUP_TRANSACTION_RECORD_COUNT * transactions)
                                    + MARKUP_TAIL_RECORD_COUNT),
                    () -> assertThat(answered.htmlRecordsEmitted())
                            .isEqualTo(sink.htmlRecords.size()),
                    () -> assertThat(counted("carddemo.batch.statement.records")).isEqualTo(
                            (long) (1 + HEAD_EMISSION_COUNT + transactions + TAIL_EMISSION_COUNT)),
                    () -> assertThat(timed("COMPLETED")).isEqualTo(1L));
        }
    }
}
