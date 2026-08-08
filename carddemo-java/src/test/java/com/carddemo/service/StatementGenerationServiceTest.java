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
package com.carddemo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import com.carddemo.api.dto.StatementSummary;
import com.carddemo.domain.Account;
import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.Customer;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.enums.FileStatus;
import com.carddemo.exception.AbendException;
import com.carddemo.exception.FileStatusException;
import com.carddemo.service.StatementDataAccessService.StatementFileRequest;
import com.carddemo.service.StatementDataAccessService.StatementFileResponse;
import com.carddemo.support.SensitiveValues;
import com.carddemo.support.TestDataFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StatementGenerationService}, the migrated form of
 * {@code app/cbl/CBSTM03A.CBL} - 924 lines, 25 paragraph units - reading the statement work area of
 * {@code app/cpy/COSTM01.CPY}, the card cross-reference of {@code app/cpy/CVACT03Y.cpy}, the customer
 * layout of {@code app/cpy/CUSTREC.cpy}, the account layout of {@code app/cpy/CVACT01Y.cpy} and the
 * transaction layout of {@code app/cpy/CVTRA05Y.cpy}, and reaching the file-handling subprogram of
 * {@code app/cbl/CBSTM03B.CBL} at 13 call sites.
 *
 * <p>Legacy provenance: the AWS CardDemo z/OS estate at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No user rules were supplied for this
 * engagement - {@code review_rules} returns a single sentinel line - so this suite is held to
 * enterprise-standard best practice instead, and no assertion here originates from a rule.
 *
 * <h2>Why the first group of tests is the important one</h2>
 *
 * <p>The member under test is <strong>not a loop</strong>. It is a hand-rolled dispatcher: an
 * eight-character state field selects a phase, the phase runs, the phase assigns the <em>next</em>
 * state and jumps <em>backwards</em> into the dispatcher, and the dispatcher branches again on the new
 * value. Six of the estate's nine backward jumps live in this one member - four returning to the
 * dispatcher, one to the statement mainline and one to the transaction-read paragraph itself.
 *
 * <p>That structure cannot be observed from the emitted records alone. A translation that folded each
 * phase into a nested call from the phase that triggered it would produce the same records for the same
 * input on the happy path and would still be wrong, because it could never re-enter a dispatcher after
 * a state change. The first nested group therefore asserts the <em>observed phase sequence</em>, which
 * the service publishes for exactly this reason, and the rest of the suite asserts the byte-level
 * output the dispatcher produces.
 *
 * <h2>The oracle is independent of the code it judges</h2>
 *
 * <p>Every expected record in this file is a literal declared here, with its padding written as an
 * explicit repeat so that each field width and each deliberate duplicate is visible to a reviewer.
 * <strong>No expected value is obtained by calling the service under test, either template class, the
 * decimal codec, the string utilities or any record mapper.</strong> The two template classes are the
 * production collaborators whose output is being judged; borrowing their constants would make this
 * suite agree with itself rather than with the legacy contract.
 *
 * <p>Record <em>images</em> are inputs rather than expectations, and are built with the shared test
 * factory. The transaction payload is the card-first projection the statement job's sort step
 * materialises, reproduced here by test-side slicing of a canonical image so that no production
 * projection helper is consulted even for an input.
 *
 * <h2>Harness</h2>
 *
 * <p>A surefire unit test: no container, no Spring context, no Spring Batch, no JDBC, no port and no
 * network. The file handler and the abort service are Mockito mocks, so an abend is observed as the
 * exception the service raises and as the diagnostic it logs first. The service under test declares no
 * clock and reads no ambient time source, so there is no clock to inject; determinism comes from the
 * pinned fixture stamp {@code 2022-06-10 19:27:53.000000} instead.
 *
 * <h2>Bounds are never approached, so no overflow path is exercised</h2>
 *
 * <p>The internal card table is declared 51 entries by 10 transactions. The delivered daily
 * transaction population presents seven records for a card - six posted plus one interest - across
 * fifty cards, so neither bound is ever reached in a real run. This suite therefore drives the
 * realistic load and deliberately contains <strong>no table-overflow test</strong>: writing one would
 * assert a condition the estate cannot produce.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StatementGenerationService: the statement dispatcher, its two record widths and the "
        + "oddities that must survive translation")
class StatementGenerationServiceTest {

    // ================================================================================================
    // Contract metadata - widths, statuses and names read from the legacy member and its subprogram
    // ================================================================================================

    /** {@code FD-STMTFILE-REC PIC X(80)} at {@code [app/cbl/CBSTM03A.CBL:L45]}. */
    private static final int STATEMENT_RECORD_WIDTH = 80;

    /** {@code FD-HTMLFILE-REC PIC X(100)} at {@code [app/cbl/CBSTM03A.CBL:L47]}. */
    private static final int MARKUP_RECORD_WIDTH = 100;

    /** {@code WS-M03B-FLDT PIC X(1000)} at {@code [app/cbl/CBSTM03A.CBL:L83]}. */
    private static final int PAYLOAD_FIELD_WIDTH = 1000;

    /** {@code WS-M03B-KEY PIC X(25)} at {@code [app/cbl/CBSTM03A.CBL:L81]}. */
    private static final int KEY_FIELD_WIDTH = 25;

    /** {@code WS-M03B-RC PIC X(02)} at {@code [app/cbl/CBSTM03A.CBL:L80]}. */
    private static final int RETURN_CODE_FIELD_WIDTH = 2;

    /** Width of both 26-character record timestamps of the transaction layout. */
    private static final int TIMESTAMP_WIDTH = 26;

    /** Leading asterisk run of the start banner, {@code [app/cbl/CBSTM03A.CBL:L87]}. */
    private static final int START_BANNER_FILL_WIDTH = 31;

    /** Caption width of the start banner, {@code [app/cbl/CBSTM03A.CBL:L88]}. */
    private static final int START_BANNER_CAPTION_WIDTH = 18;

    /** Leading asterisk run of the end banner, {@code [app/cbl/CBSTM03A.CBL:L144]}. */
    private static final int END_BANNER_FILL_WIDTH = 32;

    /** Caption width of the end banner, {@code [app/cbl/CBSTM03A.CBL:L145]}. */
    private static final int END_BANNER_CAPTION_WIDTH = 16;

    /** The success status, the only code the four selection-form status tests accept. */
    private static final String STATUS_SUCCESS = "00";

    /**
     * The record-length-mismatch status, accepted alongside success at the nine guard-form sites - the
     * four opens, the priming transaction read and the four closes - and nowhere else. Treating it as an
     * error is a plausible defect, so it is asserted explicitly.
     */
    private static final String STATUS_RECORD_LENGTH_MISMATCH = "04";

    /** The at-end status, which terminates a sequential read and is never an error. */
    private static final String STATUS_END_OF_FILE = "10";

    /** The record-not-found status: the only error code this suite drives. */
    private static final String STATUS_RECORD_NOT_FOUND = "23";

    /**
     * The complete status vocabulary of this suite.
     *
     * <p>A census of the estate found that only the success, at-end and record-not-found codes are ever
     * compared against a status-bearing field, and this member's guard form adds the
     * record-length-mismatch code. The duplicate-key and file-not-found codes are declared by the status
     * enum because prior specification text cites them, but nothing in the source compares either, so
     * <strong>no test here may depend on them</strong>.
     */
    private static final List<String> DRIVEN_STATUS_VOCABULARY =
            List.of(STATUS_SUCCESS, STATUS_RECORD_LENGTH_MISMATCH, STATUS_END_OF_FILE,
                    STATUS_RECORD_NOT_FOUND);

    /** Documented by prior specification text yet compared nowhere in the source. */
    private static final String STATUS_DOCUMENTED_DUPLICATE_KEY = "22";

    /** Documented by prior specification text yet compared nowhere in the source. */
    private static final String STATUS_DOCUMENTED_FILE_NOT_FOUND = "35";

    /** The legacy member name, which is the culprit every abend from this service names. */
    private static final String LEGACY_PROGRAM_NAME = "CBSTM03A";

    /** Diagnostic wording of a failing open, from the legacy display literals. */
    private static final String OPERATION_OPEN = "OPEN";

    /** Diagnostic wording of a failing read. */
    private static final String OPERATION_READ = "READ";

    /** Diagnostic wording of a failing close. */
    private static final String OPERATION_CLOSE = "CLOSE";

    // ================================================================================================
    // Fixture values - inputs, chosen so that every expected literal below can be hand-computed
    // ================================================================================================

    /** The card number the cross-reference record names and the tabulated transactions carry. */
    private static final String CARD_NUMBER = "0500024453765740";

    /** A card number sorting after the tabulated one, used to prove the card walk short-circuits. */
    private static final String LATER_CARD_NUMBER = "9500024453765740";

    /** The nine-character customer key the cross-reference record carries. */
    private static final String CUSTOMER_ID = "000000050";

    /** The eleven-character account key the cross-reference record carries. */
    private static final String ACCOUNT_ID = "00000000050";

    /** First name, a single word, so the assembled name has exactly one space before the middle name. */
    private static final String FIRST_NAME = "Immanuel";

    /** Middle name, a single word. */
    private static final String MIDDLE_NAME = "Madeline";

    /** Surname, a single word. */
    private static final String LAST_NAME = "Kessler";

    /**
     * First address line, carrying two <em>single</em> internal spaces.
     *
     * <p>Deliberate: the markup composer stops its transfer at the first pair of <em>adjacent</em>
     * spaces, so an implementation that split on one space would truncate this value after
     * {@code 618} and still produce a well-formed record of the right width.
     */
    private static final String ADDRESS_LINE_1 = "618 Deshaun Route";

    /** Second address line, also carrying a single internal space. */
    private static final String ADDRESS_LINE_2 = "Apt. 802";

    /** Third address line, a single word, which the third assembly joins with state, country and postcode. */
    private static final String ADDRESS_LINE_3 = "Altenwerthshire";

    /** Two-character state code. */
    private static final String STATE_CODE = "NC";

    /** Three-character country code. */
    private static final String COUNTRY_CODE = "USA";

    /** Postcode, held in a ten-byte field. */
    private static final String ADDRESS_ZIP = "12546";

    /** Three-character credit score. */
    private static final String CREDIT_SCORE = "750";

    /** Account balance, rendered by the mask that does not suppress leading zeros. */
    private static final BigDecimal CURRENT_BALANCE = new BigDecimal("194.00");

    /** First tabulated transaction: a positive purchase. */
    private static final String FIRST_TRANSACTION_ID = "0000000000683580";

    /** Description of the first tabulated transaction, 24 characters inside a 49-byte field. */
    private static final String FIRST_TRANSACTION_DESCRIPTION = "Purchase at Abshire-Lowe";

    /** Amount of the first tabulated transaction. */
    private static final BigDecimal FIRST_TRANSACTION_AMOUNT = new BigDecimal("504.77");

    /** Second tabulated transaction: a larger positive purchase. */
    private static final String SECOND_TRANSACTION_ID = "0000000000683581";

    /** Description of the second tabulated transaction, 25 characters inside a 49-byte field. */
    private static final String SECOND_TRANSACTION_DESCRIPTION = "Purchase at Kuhic-Weimann";

    /** Amount of the second tabulated transaction. */
    private static final BigDecimal SECOND_TRANSACTION_AMOUNT = new BigDecimal("1234.56");

    /** Third tabulated transaction: an operator-originated return, so the total must fall. */
    private static final String THIRD_TRANSACTION_ID = "0000000000683582";

    /** Description of the third tabulated transaction, 22 characters inside a 49-byte field. */
    private static final String THIRD_TRANSACTION_DESCRIPTION = "Return to Abshire-Lowe";

    /**
     * Amount of the third tabulated transaction, negative.
     *
     * <p>The delivered daily transaction population is 250 positive purchases and 50 negative returns,
     * so a suite that drove only positive amounts would never exercise the trailing-minus position of
     * either amount mask.
     */
    private static final BigDecimal THIRD_TRANSACTION_AMOUNT = new BigDecimal("-25.00");

    /**
     * The hand-computed total of the three amounts: {@code 504.77 + 1234.56 - 25.00}.
     *
     * <p>Written as a literal rather than summed here, so that the expected value does not share an
     * arithmetic path with the value under test.
     */
    private static final BigDecimal EXPECTED_TOTAL = new BigDecimal("1714.33");

    // ================================================================================================
    // The independent oracle - every expected record, declared here as a literal
    //
    // Padding is written as an explicit repeat so that each declared field width, and each deliberate
    // duplicate, is visible without counting characters. Nothing below is produced by, derived from or
    // copied out of the service under test or either template class.
    // ================================================================================================

    /** The 13-byte zero-suppressed mask of the first amount: six pad positions, then the value. */
    private static final String MASK_FIRST_AMOUNT = " ".repeat(6) + "504.77" + " ";

    /** The 13-byte zero-suppressed mask of the second amount. */
    private static final String MASK_SECOND_AMOUNT = " ".repeat(5) + "1234.56" + " ";

    /** The 13-byte zero-suppressed mask of the negative amount, whose sign position carries a minus. */
    private static final String MASK_THIRD_AMOUNT = " ".repeat(7) + "25.00" + "-";

    /** The 13-byte zero-suppressed mask of the accumulated total. */
    private static final String MASK_TOTAL_AMOUNT = " ".repeat(5) + "1714.33" + " ";

    /**
     * The 13-byte zero-suppressed mask of a zero total: suppression covers all nine integer positions
     * and stops at the decimal point, which is never suppressed.
     */
    private static final String MASK_ZERO_AMOUNT = " ".repeat(9) + ".00" + " ";

    /**
     * The 13-byte mask of the balance, which does <em>not</em> suppress leading zeros.
     *
     * <p>The two masks are different pictures and must never be produced by one shared renderer with a
     * flag: this one prints every integer position, the transaction mask blanks the leading ones.
     */
    private static final String MASK_BALANCE_UNSUPPRESSED = "000000194.00" + " ";

    /** The start banner: a 31-character fill, an 18-character caption, another 31-character fill. */
    private static final String EXPECTED_START_BANNER =
            "*".repeat(START_BANNER_FILL_WIDTH) + "START OF STATEMENT"
                    + "*".repeat(START_BANNER_FILL_WIDTH);

    /**
     * The end banner: a 32-character fill, a 16-character caption, another 32-character fill.
     *
     * <p>Not the start banner's split. Both total eighty, so a shared centring computation would pass a
     * total-only assertion and emit the wrong bytes.
     */
    private static final String EXPECTED_END_BANNER =
            "*".repeat(END_BANNER_FILL_WIDTH) + "END OF STATEMENT"
                    + "*".repeat(END_BANNER_FILL_WIDTH);

    /** The hyphen rule line, emitted six times per statement from three declared positions. */
    private static final String EXPECTED_RULE_LINE = "-".repeat(STATEMENT_RECORD_WIDTH);

    /** The basic-details heading: 33 pad, a 14-byte field holding a 13-character literal, 33 pad. */
    private static final String EXPECTED_BASIC_DETAILS_HEADING =
            " ".repeat(33) + "Basic Details" + " " + " ".repeat(33);

    /** The transaction-summary heading: 30 pad, a 20-byte field whose literal ends in a space, 30 pad. */
    private static final String EXPECTED_TRANSACTION_SUMMARY_HEADING =
            " ".repeat(30) + "TRANSACTION SUMMARY " + " ".repeat(30);

    /** The column headings: a 16-byte, a 51-byte and a 13-byte field, the last with two leading spaces. */
    private static final String EXPECTED_COLUMN_HEADINGS =
            "Tran ID         " + "Tran Details    " + " ".repeat(35) + "  Tran Amount";

    /**
     * The customer-name line: the assembled name in a 75-byte field, then five pad bytes.
     *
     * <p>The assembly transfers each of the three name operands only as far as its own first space and
     * interleaves one space between them, so the trailing space after the surname is the assembly's, not
     * the field's.
     */
    private static final String EXPECTED_NAME_LINE =
            FIRST_NAME + " " + MIDDLE_NAME + " " + LAST_NAME + " " + " ".repeat(54);

    /** The first address line: a 50-byte field, then 30 pad bytes. */
    private static final String EXPECTED_ADDRESS_1_LINE = ADDRESS_LINE_1 + " ".repeat(63);

    /** The second address line: a 50-byte field, then 30 pad bytes. */
    private static final String EXPECTED_ADDRESS_2_LINE = ADDRESS_LINE_2 + " ".repeat(72);

    /** The third address line: one 80-byte field holding the four assembled operands. */
    private static final String EXPECTED_ADDRESS_3_LINE = ADDRESS_LINE_3 + " " + STATE_CODE + " "
            + COUNTRY_CODE + " " + ADDRESS_ZIP + " " + " ".repeat(51);

    /** The account-identifier line: a 20-byte label, a 20-byte value field, 40 pad bytes. */
    private static final String EXPECTED_ACCOUNT_ID_LINE =
            "Account ID         :" + ACCOUNT_ID + " ".repeat(49);

    /** The balance line: a 20-byte label, the 13-byte unsuppressed mask, 7 pad then 40 pad. */
    private static final String EXPECTED_CURRENT_BALANCE_LINE =
            "Current Balance    :" + MASK_BALANCE_UNSUPPRESSED + " ".repeat(47);

    /** The credit-score line: a 20-byte label, a 20-byte value field, 40 pad bytes. */
    private static final String EXPECTED_FICO_LINE =
            "FICO Score         :" + CREDIT_SCORE + " ".repeat(57);

    /** The first detail line: a 16-byte key, one space, a 49-byte detail, a currency byte, the mask. */
    private static final String EXPECTED_FIRST_DETAIL_LINE = FIRST_TRANSACTION_ID + " "
            + FIRST_TRANSACTION_DESCRIPTION + " ".repeat(25) + "$" + MASK_FIRST_AMOUNT;

    /** The second detail line, whose description is one character longer than the first. */
    private static final String EXPECTED_SECOND_DETAIL_LINE = SECOND_TRANSACTION_ID + " "
            + SECOND_TRANSACTION_DESCRIPTION + " ".repeat(24) + "$" + MASK_SECOND_AMOUNT;

    /** The third detail line, carrying the negative amount and therefore the trailing minus. */
    private static final String EXPECTED_THIRD_DETAIL_LINE = THIRD_TRANSACTION_ID + " "
            + THIRD_TRANSACTION_DESCRIPTION + " ".repeat(27) + "$" + MASK_THIRD_AMOUNT;

    /** The total line: a 10-byte label, 56 pad bytes, a currency byte, the mask. */
    private static final String EXPECTED_TOTAL_LINE =
            "Total EXP:" + " ".repeat(56) + "$" + MASK_TOTAL_AMOUNT;

    /** The total line of a statement whose card tabulated nothing. */
    private static final String EXPECTED_ZERO_TOTAL_LINE =
            "Total EXP:" + " ".repeat(56) + "$" + MASK_ZERO_AMOUNT;

    // ------------------------------------------------------------------------------------------------
    // The 34 invariant markup templates, declared here rather than borrowed from the class that emits
    // them. Thirty-three are written by this member; the table-cell opener is declared by the legacy
    // condition-name list and never set, so it must appear nowhere in the output.
    // ------------------------------------------------------------------------------------------------

    private static final String MARKUP_DOCTYPE = "<!DOCTYPE html>";

    private static final String MARKUP_HTML_OPEN = "<html lang=\"en\">";

    private static final String MARKUP_HEAD_OPEN = "<head>";

    private static final String MARKUP_META_CHARSET = "<meta charset=\"utf-8\">";

    private static final String MARKUP_TITLE = "<title>HTML Table Layout</title>";

    private static final String MARKUP_HEAD_CLOSE = "</head>";

    private static final String MARKUP_BODY_OPEN = "<body style=\"margin:0px;\">";

    /**
     * The opening table tag, carrying <strong>two adjacent spaces</strong> after the element name.
     *
     * <p>This is the constant that prior project documentation describes as truncated or malformed.
     * Direct reading of the legacy source shows otherwise: the literal is continued across two source
     * lines, split mid-token, and rejoins with no space at the join, so the element is well formed and
     * the double space after {@code <table} is genuine content. It is reproduced here exactly, neither
     * collapsed to one space nor abbreviated into a broken tag, because the emitted bytes are
     * contractual either way.
     */
    private static final String MARKUP_TABLE_OPEN = "<table  align=\"center\" frame=\"box\" "
            + "style=\"width:70%; font:12px Segoe UI,sans-serif;\">";

    private static final String MARKUP_ROW_OPEN = "<tr>";

    private static final String MARKUP_ROW_CLOSE = "</tr>";

    /**
     * The table-cell opener the legacy condition-name list declares and no paragraph ever sets.
     *
     * <p>Declared but never written: every cell in the emitted document is opened by one of the styled
     * cell templates instead. An over-eager translation that emitted every template it found declared
     * would put this record into the output, so its absence is asserted.
     */
    private static final String MARKUP_CELL_OPEN_UNUSED = "<td>";

    private static final String MARKUP_CELL_CLOSE = "</td>";

    private static final String MARKUP_BANNER_CELL =
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#1d1d96b3;\">";

    private static final String MARKUP_ADDRESS_CELL =
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#FFAF33;\">";

    private static final String MARKUP_BANK_NAME = "<p style=\"font-size:16px\">Bank of XYZ</p>";

    private static final String MARKUP_BANK_STREET = "<p>410 Terry Ave N</p>";

    private static final String MARKUP_BANK_CITY = "<p>Seattle WA 99999</p>";

    private static final String MARKUP_PLAIN_CELL =
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#f2f2f2;\">";

    private static final String MARKUP_HEADING_CELL = "<td colspan=\"3\" style=\"padding:0px 5px;"
            + "background-color:#33FFD1; text-align:center;\">";

    private static final String MARKUP_BASIC_DETAILS_HEADING =
            "<p style=\"font-size:16px\">Basic Details</p>";

    private static final String MARKUP_TRANSACTION_SUMMARY_HEADING =
            "<p style=\"font-size:16px\">Transaction Summary</p>";

    private static final String MARKUP_KEY_HEADING_CELL = "<td style=\"width:25%; padding:0px 5px; "
            + "background-color:#33FF5E; text-align:left;\">";

    private static final String MARKUP_KEY_HEADING = "<p style=\"font-size:16px\">Tran ID</p>";

    private static final String MARKUP_DETAIL_HEADING_CELL = "<td style=\"width:55%; padding:0px 5px; "
            + "background-color:#33FF5E; text-align:left;\">";

    private static final String MARKUP_DETAIL_HEADING = "<p style=\"font-size:16px\">Tran Details</p>";

    private static final String MARKUP_AMOUNT_HEADING_CELL = "<td style=\"width:20%; padding:0px 5px; "
            + "background-color:#33FF5E; text-align:right;\">";

    private static final String MARKUP_AMOUNT_HEADING = "<p style=\"font-size:16px\">Amount</p>";

    private static final String MARKUP_KEY_CELL = "<td style=\"width:25%; padding:0px 5px; "
            + "background-color:#f2f2f2; text-align:left;\">";

    private static final String MARKUP_DETAIL_CELL = "<td style=\"width:55%; padding:0px 5px; "
            + "background-color:#f2f2f2; text-align:left;\">";

    private static final String MARKUP_AMOUNT_CELL = "<td style=\"width:20%; padding:0px 5px; "
            + "background-color:#f2f2f2; text-align:right;\">";

    private static final String MARKUP_END_OF_STATEMENT = "<h3>End of Statement</h3>";

    private static final String MARKUP_TABLE_CLOSE = "</table>";

    private static final String MARKUP_BODY_CLOSE = "</body>";

    private static final String MARKUP_HTML_CLOSE = "</html>";

    /** The declared invariant templates, in legacy declaration order. */
    private static final List<String> DECLARED_MARKUP_TEMPLATES = List.of(
            MARKUP_DOCTYPE, MARKUP_HTML_OPEN, MARKUP_HEAD_OPEN, MARKUP_META_CHARSET, MARKUP_TITLE,
            MARKUP_HEAD_CLOSE, MARKUP_BODY_OPEN, MARKUP_TABLE_OPEN,
            MARKUP_ROW_OPEN, MARKUP_ROW_CLOSE, MARKUP_CELL_OPEN_UNUSED, MARKUP_CELL_CLOSE,
            MARKUP_BANNER_CELL, MARKUP_ADDRESS_CELL, MARKUP_BANK_NAME, MARKUP_BANK_STREET,
            MARKUP_BANK_CITY, MARKUP_PLAIN_CELL, MARKUP_HEADING_CELL, MARKUP_BASIC_DETAILS_HEADING,
            MARKUP_TRANSACTION_SUMMARY_HEADING, MARKUP_KEY_HEADING_CELL, MARKUP_KEY_HEADING,
            MARKUP_DETAIL_HEADING_CELL, MARKUP_DETAIL_HEADING, MARKUP_AMOUNT_HEADING_CELL,
            MARKUP_AMOUNT_HEADING, MARKUP_KEY_CELL, MARKUP_DETAIL_CELL, MARKUP_AMOUNT_CELL,
            MARKUP_END_OF_STATEMENT, MARKUP_TABLE_CLOSE, MARKUP_BODY_CLOSE, MARKUP_HTML_CLOSE);

    /** How many invariant templates the legacy condition-name list declares. */
    private static final int DECLARED_MARKUP_TEMPLATE_COUNT = 34;

    // ------------------------------------------------------------------------------------------------
    // The composed markup records, and the wrong outcomes a mis-implementation would produce
    // ------------------------------------------------------------------------------------------------

    /** Width of the detail field of a transaction row, shared by the text and markup renderings. */
    private static final int DETAIL_FIELD_WIDTH = 49;

    /**
     * Width of the transaction record's own description field, {@code app/cpy/CVTRA05Y.cpy}.
     *
     * <p>Wider than the 49-byte field the statement row displays, so the row narrows the value while the
     * run summary keeps every byte the record held. The two are not the same field and must not be
     * conflated.
     */
    private static final int TRANSACTION_DESCRIPTION_FIELD_WIDTH = 100;

    /** The account-number heading: a 34-byte literal, a 20-byte value field, a 5-byte literal. */
    private static final String EXPECTED_ACCOUNT_NUMBER_MARKUP =
            markup("<h3>Statement for Account Number: " + ACCOUNT_ID + " ".repeat(9) + "</h3>");

    /**
     * The customer-name heading, whose transfer stops at the first pair of adjacent spaces.
     *
     * <p>The staged name field is fifty bytes, so the pair the transfer stops at is the boundary between
     * the assembly's own trailing space and the field's first pad byte. Everything before it - all three
     * name words, single-spaced - reaches the record.
     */
    private static final String EXPECTED_CUSTOMER_NAME_MARKUP = markup(
            "<p style=\"font-size:16px\">" + FIRST_NAME + " " + MIDDLE_NAME + " " + LAST_NAME
                    + "  </p>");

    /** The first address paragraph, whose transfer likewise stops at the first adjacent pair. */
    private static final String EXPECTED_ADDRESS_1_MARKUP = markup("<p>" + ADDRESS_LINE_1 + "  </p>");

    /** The second address paragraph. */
    private static final String EXPECTED_ADDRESS_2_MARKUP = markup("<p>" + ADDRESS_LINE_2 + "  </p>");

    /** The third address paragraph, carrying the four assembled operands. */
    private static final String EXPECTED_ADDRESS_3_MARKUP = markup("<p>" + ADDRESS_LINE_3 + " "
            + STATE_CODE + " " + COUNTRY_CODE + " " + ADDRESS_ZIP + "  </p>");

    /**
     * The account-identifier detail paragraph.
     *
     * <p>Its label is twenty-one characters - the plain statement's twenty-character label plus one
     * trailing space - so the two labels are genuinely different literals and neither may be derived
     * from the other.
     */
    private static final String EXPECTED_ACCOUNT_DETAIL_MARKUP =
            markup("<p>Account ID         : " + ACCOUNT_ID + " ".repeat(9) + "</p>");

    /** The balance detail paragraph, carrying the mask that does not suppress leading zeros. */
    private static final String EXPECTED_BALANCE_DETAIL_MARKUP =
            markup("<p>Current Balance    : " + MASK_BALANCE_UNSUPPRESSED + "</p>");

    /** The credit-score detail paragraph. */
    private static final String EXPECTED_FICO_DETAIL_MARKUP =
            markup("<p>FICO Score         : " + CREDIT_SCORE + " ".repeat(17) + "</p>");

    /**
     * What the customer-name heading would carry if the transfer split on a <em>single</em> space.
     *
     * <p>Declared so that the test can assert inequality explicitly. It is a well-formed record of
     * exactly the right width, which is why a width-only assertion would not catch the defect.
     */
    private static final String SINGLE_SPACE_SPLIT_NAME_MARKUP =
            markup("<p style=\"font-size:16px\">" + FIRST_NAME + "  </p>");

    /** What the first address paragraph would carry if the transfer split on a single space. */
    private static final String SINGLE_SPACE_SPLIT_ADDRESS_MARKUP = markup("<p>618  </p>");

    /**
     * The image of the group the legacy populates and never writes.
     *
     * <p>The source moves the assembled name into a staging group that pairs a 26-byte literal with a
     * 50-byte name field, and then builds the record it actually writes with a separate transfer. The
     * group itself is never the source of a write, so this image - the literal followed by the whole
     * padded field, with no closing tag - must appear nowhere in the output.
     */
    private static final String NEVER_WRITTEN_NAME_GROUP_IMAGE =
            markup("<p style=\"font-size:16px\">" + FIRST_NAME + " " + MIDDLE_NAME + " " + LAST_NAME
                    + " " + " ".repeat(24));

    // ================================================================================================
    // Record layout metadata - the offsets and widths of the four images this member reads
    // ================================================================================================

    /** The transaction record is 350 bytes, {@code app/cpy/CVTRA05Y.cpy}. */
    private static final int TRANSACTION_RECORD_WIDTH = 350;

    /** The customer record is 500 bytes, {@code app/cpy/CUSTREC.cpy}. */
    private static final int CUSTOMER_RECORD_WIDTH = 500;

    /** The account record is 300 bytes, {@code app/cpy/CVACT01Y.cpy}. */
    private static final int ACCOUNT_RECORD_WIDTH = 300;

    /** Eleven-character key of the account record. */
    private static final int ACCOUNT_KEY_WIDTH = 11;

    /** The 289 data bytes that follow the account key. */
    private static final int ACCOUNT_DATA_WIDTH = 289;

    /** The cross-reference record is 50 bytes, {@code app/cpy/CVACT03Y.cpy}. */
    private static final int CROSS_REFERENCE_RECORD_WIDTH = 50;

    /** Of which 36 bytes are data. */
    private static final int CROSS_REFERENCE_DATA_WIDTH = 36;

    /** And 14 bytes are filler. */
    private static final int CROSS_REFERENCE_FILLER_WIDTH = 14;

    /** Zero-based offset of the card number inside a canonical transaction image. */
    private static final int CARD_NUMBER_OFFSET = 262;

    /** Width of the card number. */
    private static final int CARD_NUMBER_WIDTH = 16;

    /** Zero-based offset of the origination timestamp inside a canonical transaction image. */
    private static final int ORIGINATION_TIMESTAMP_OFFSET = 278;

    /** Zero-based offset of the processing timestamp inside a canonical transaction image. */
    private static final int PROCESSING_TIMESTAMP_OFFSET = 304;

    /**
     * Width the statement job's reprojection copies from the origination timestamp onwards.
     *
     * <p>Fifty bytes covers the origination stamp whole and only the leading 24 of the processing
     * stamp's 26, so the reprojection drops its final two bytes. That truncation is part of the work
     * resource's contract and is reproduced here rather than repaired.
     */
    private static final int PROJECTED_TIMESTAMP_SEGMENT_WIDTH = 50;

    /** Blank bytes the reprojection leaves after its 328 projected ones. */
    private static final int PROJECTED_TAIL_WIDTH = 22;

    /** Processing-timestamp bytes the reprojection drops. */
    private static final int TRUNCATED_TIMESTAMP_BYTES = 2;

    /** Ordinal position the file handler reports after an open or a close. */
    private static final int FIRST_RECORD_POSITION = 0;

    /** Sentinel meaning no read of a driven scenario is scripted to fail. */
    private static final int NO_SCRIPTED_FAILURE = -1;

    /** Fixed statement records per statement, before the per-transaction detail lines. */
    private static final int FIXED_STATEMENT_RECORDS_PER_STATEMENT = 19;

    /** Fixed markup records per statement, before the per-transaction blocks. */
    private static final int FIXED_MARKUP_RECORDS_PER_STATEMENT = 64;

    /** Markup records each tabulated transaction contributes. */
    private static final int MARKUP_RECORDS_PER_TRANSACTION = 11;

    /** Rule lines every statement emits, from three declared positions. */
    private static final int RULE_LINES_PER_STATEMENT = 6;

    /** Transactions the delivered population presents for one card: six posted plus one interest. */
    private static final int REALISTIC_TRANSACTIONS_PER_CARD = 7;

    /** Leading marker of the module's protected-value envelope. */
    private static final String PROTECTED_ENVELOPE_PREFIX = "ENC1:";

    /** Body width of the envelope this suite's sealing operation produces. */
    private static final int PROTECTED_ENVELOPE_BODY_WIDTH = 32;

    /**
     * Seals a regulated customer identifier into a well-formed protected-value envelope.
     *
     * <p>The customer entity admits only a well-formed envelope or an absent value for its two regulated
     * identifiers, and the record mapper refuses an operation that drops one, so a run needs a real
     * sealing operation rather than the identity function. No cryptography is involved and none is
     * claimed: this is a shape-correct stand-in for the caller's policy, which is deliberately not the
     * service's concern.
     */
    private static final UnaryOperator<String> REGULATED_FIELD_SEALER = cleartext -> {
        final byte[] raw =
                (cleartext == null ? "" : cleartext).getBytes(StandardCharsets.US_ASCII);
        final byte[] body = new byte[PROTECTED_ENVELOPE_BODY_WIDTH];
        for (int index = 0; index < body.length; index++) {
            body[index] = index < raw.length ? raw[index] : (byte) ' ';
        }
        return PROTECTED_ENVELOPE_PREFIX + Base64.getEncoder().encodeToString(body);
    };

    /** The inverse of the sealing operation, which the real file handler consults on a keyed read. */
    private static final UnaryOperator<String> REGULATED_FIELD_REVEALER = envelope -> {
        if (envelope == null) {
            return "";
        }
        if (!envelope.startsWith(PROTECTED_ENVELOPE_PREFIX)) {
            return envelope;
        }
        return new String(
                Base64.getDecoder().decode(envelope.substring(PROTECTED_ENVELOPE_PREFIX.length())),
                StandardCharsets.US_ASCII).strip();
    };

    /** A sealing operation that drops the value it is given, which the record mapper must refuse. */
    private static final UnaryOperator<String> DROPPING_SEALER = cleartext -> null;

    // ================================================================================================
    // Collaborator doubles and the log recorder
    // ================================================================================================

    /** The file-handling subprogram, mocked: this suite scripts the four files rather than reading them. */
    private StatementDataAccessService dataAccess;

    /** The abort routine, mocked, so an abend is observed rather than performed. */
    private AbendService abendService;

    /** The service under test. */
    private StatementGenerationService service;

    /** The frozen transaction-work snapshot the run is handed. */
    private StatementTransactionSource transactionSource;

    /** The one sequential cross-reference walk the run acquires. */
    private StatementCrossReferenceSource crossReferenceWalk;

    /** The scripted behaviour of the four files. */
    private FileScript script;

    /** The service's own logger, so a diagnostic can be observed at the moment an abend is raised. */
    private Logger serviceLogger;

    /** The logger's configured level, restored after each test. */
    private Level originalLevel;

    /** Records the service's log events for the log-before-abend assertion. */
    private ListAppender<ILoggingEvent> recorder;

    @BeforeEach
    void bindCollaboratorsAndRecordLogging() {
        this.dataAccess = mock(StatementDataAccessService.class);
        this.abendService = mock(AbendService.class);
        this.service = new StatementGenerationService(this.dataAccess, this.abendService);
        // Identity-bearing stand-ins: the scripted handler answers from its own script rather than from
        // these, so their only role is to prove that one walk and one snapshot are threaded through
        // every call rather than re-acquired per call.
        this.transactionSource = position -> Optional.empty();
        this.crossReferenceWalk = position -> Optional.empty();
        this.script = new FileScript();
        this.serviceLogger = (Logger) LoggerFactory.getLogger(StatementGenerationService.class);
        this.originalLevel = this.serviceLogger.getLevel();
        this.serviceLogger.setLevel(Level.DEBUG);
        this.recorder = new ListAppender<>();
        this.recorder.start();
        this.serviceLogger.addAppender(this.recorder);
    }

    @AfterEach
    void releaseLogRecorder() {
        this.serviceLogger.detachAppender(this.recorder);
        this.recorder.stop();
        this.serviceLogger.setLevel(this.originalLevel);
    }

    // ================================================================================================
    // The scripted file handler
    // ================================================================================================

    /**
     * A scripted stand-in for the four files the statement feature reads.
     *
     * <p>Sequential reads answer from a list at the position the parameter object carries and advance
     * it, ending with the at-end status once the list is exhausted. Keyed reads answer from a fixed
     * image. Opens and closes report a parameterised status and reset the position, exactly as the real
     * handler does. Every request is recorded, together with the snapshot and walk it travelled with, so
     * a test can assert what the service asked for as well as what it produced.
     */
    private static final class FileScript {

        private final List<StatementFileRequest> requests = new ArrayList<>();

        private final List<StatementTransactionSource> snapshotsSeen = new ArrayList<>();

        private final List<StatementCrossReferenceSource> walksSeen = new ArrayList<>();

        private final List<String> transactionWorkRecords = new ArrayList<>();

        private final List<String> crossReferenceImages = new ArrayList<>();

        private String customerImage = customerImage();

        private String accountImage = accountImage();

        private String openStatus = STATUS_SUCCESS;

        private String closeStatus = STATUS_SUCCESS;

        private String primingReadStatus = STATUS_SUCCESS;

        private String customerReadStatus = STATUS_SUCCESS;

        private String accountReadStatus = STATUS_SUCCESS;

        private String failureStatus = STATUS_RECORD_NOT_FOUND;

        private int failingTransactionRead = NO_SCRIPTED_FAILURE;

        private int failingCrossReferenceRead = NO_SCRIPTED_FAILURE;

        private int transactionReads;

        private int crossReferenceReads;

        private FileScript withTransactions(final List<String> workRecords) {
            this.transactionWorkRecords.clear();
            this.transactionWorkRecords.addAll(workRecords);
            return this;
        }

        private FileScript withCrossReferenceRecords(final List<String> images) {
            this.crossReferenceImages.clear();
            this.crossReferenceImages.addAll(images);
            return this;
        }

        private FileScript withCustomerImage(final String image) {
            this.customerImage = image;
            return this;
        }

        private FileScript withOpenStatus(final String status) {
            this.openStatus = status;
            return this;
        }

        private FileScript withCloseStatus(final String status) {
            this.closeStatus = status;
            return this;
        }

        private FileScript withPrimingReadStatus(final String status) {
            this.primingReadStatus = status;
            return this;
        }

        private FileScript withCustomerReadStatus(final String status) {
            this.customerReadStatus = status;
            return this;
        }

        private FileScript withAccountReadStatus(final String status) {
            this.accountReadStatus = status;
            return this;
        }

        private FileScript failingTransactionReadAt(final int ordinal, final String status) {
            this.failingTransactionRead = ordinal;
            this.failureStatus = status;
            return this;
        }

        private FileScript failingCrossReferenceReadAt(final int ordinal, final String status) {
            this.failingCrossReferenceRead = ordinal;
            this.failureStatus = status;
            return this;
        }

        private StatementFileResponse answer(final StatementFileRequest request,
                                             final StatementTransactionSource snapshot,
                                             final StatementCrossReferenceSource walk) {
            this.requests.add(request);
            this.snapshotsSeen.add(snapshot);
            this.walksSeen.add(walk);
            final String ddName = request.ddName();
            if (request.hasOperation(StatementDataAccessService.OPERATION_OPEN)) {
                return new StatementFileResponse(ddName, this.openStatus, "", FIRST_RECORD_POSITION);
            }
            if (request.hasOperation(StatementDataAccessService.OPERATION_CLOSE)) {
                return new StatementFileResponse(ddName, this.closeStatus, "", FIRST_RECORD_POSITION);
            }
            final int position = request.sequentialPosition();
            if (request.hasDdName(StatementDataAccessService.DD_TRNXFILE)) {
                return answerTransactionRead(request, ddName, position);
            }
            if (request.hasDdName(StatementDataAccessService.DD_XREFFILE)) {
                return answerCrossReferenceRead(request, ddName, position);
            }
            if (request.hasDdName(StatementDataAccessService.DD_CUSTFILE)) {
                return new StatementFileResponse(ddName, this.customerReadStatus, this.customerImage,
                        position);
            }
            if (request.hasDdName(StatementDataAccessService.DD_ACCTFILE)) {
                return new StatementFileResponse(ddName, this.accountReadStatus, this.accountImage,
                        position);
            }
            throw new AssertionError(
                    "the service named a file the statement feature does not read: " + ddName);
        }

        private StatementFileResponse answerTransactionRead(final StatementFileRequest request,
                                                           final String ddName, final int position) {
            final int ordinal = this.transactionReads;
            this.transactionReads = ordinal + 1;
            if (ordinal == this.failingTransactionRead) {
                return new StatementFileResponse(ddName, this.failureStatus, request.payload(),
                        position);
            }
            if (position >= this.transactionWorkRecords.size()) {
                return new StatementFileResponse(ddName, STATUS_END_OF_FILE, request.payload(),
                        position);
            }
            // Only the priming read is guard-form and therefore able to accept the record-length
            // status; the loop reads are selection-form and accept success alone.
            final String status = ordinal == 0 ? this.primingReadStatus : STATUS_SUCCESS;
            return new StatementFileResponse(ddName, status,
                    this.transactionWorkRecords.get(position), position + 1);
        }

        private StatementFileResponse answerCrossReferenceRead(final StatementFileRequest request,
                                                              final String ddName,
                                                              final int position) {
            final int ordinal = this.crossReferenceReads;
            this.crossReferenceReads = ordinal + 1;
            if (ordinal == this.failingCrossReferenceRead) {
                return new StatementFileResponse(ddName, this.failureStatus, request.payload(),
                        position);
            }
            if (position >= this.crossReferenceImages.size()) {
                return new StatementFileResponse(ddName, STATUS_END_OF_FILE, request.payload(),
                        position);
            }
            return new StatementFileResponse(ddName, STATUS_SUCCESS,
                    this.crossReferenceImages.get(position), position + 1);
        }

        private List<String> ddNamesAndOperations() {
            final List<String> signatures = new ArrayList<>(this.requests.size());
            for (final StatementFileRequest request : this.requests) {
                signatures.add(request.ddName() + "/" + request.operation());
            }
            return signatures;
        }
    }

    // ================================================================================================
    // Test-side helpers - inputs and declared expectations, never a production computation
    // ================================================================================================

    /**
     * Right-pads a declared markup literal to the markup record width.
     *
     * <p>The pad is {@code " ".repeat(n)} over the literal's own encoded length, so the width cannot
     * drift as literals are edited and the record's 100 bytes stay self-evident. The literal itself is
     * always written out in full at its declaration.
     *
     * @param content the declared literal
     * @return the literal followed by enough spaces to reach the markup record width
     */
    private static String markup(final String content) {
        return content + " ".repeat(MARKUP_RECORD_WIDTH - content.length());
    }

    /** The customer image every scenario reads, carrying cleartext regulated identifiers. */
    private static String customerImage() {
        return TestDataFactory.customer()
                .customerId(CUSTOMER_ID)
                .firstName(FIRST_NAME)
                .middleName(MIDDLE_NAME)
                .lastName(LAST_NAME)
                .addressLine1(ADDRESS_LINE_1)
                .addressLine2(ADDRESS_LINE_2)
                .addressLine3(ADDRESS_LINE_3)
                .stateCode(STATE_CODE)
                .countryCode(COUNTRY_CODE)
                .addressZip(ADDRESS_ZIP)
                .creditScore(CREDIT_SCORE)
                .image();
    }

    /** The account image every scenario reads. */
    private static String accountImage() {
        return TestDataFactory.account().acctId(ACCOUNT_ID).currentBalance(CURRENT_BALANCE).image();
    }

    /** The cross-reference image naming one card, its customer and its account. */
    private static String crossReferenceImage(final String cardNumber) {
        return TestDataFactory.cardCrossReference()
                .cardNumber(cardNumber)
                .customerId(CUSTOMER_ID)
                .accountId(ACCOUNT_ID)
                .datasetImage();
    }

    /** One tabulated transaction, carrying the pinned origination stamp and a blank processing stamp. */
    private static String transactionWorkRecord(final String transactionId, final String description,
                                                final BigDecimal amount) {
        return transactionWorkRecord(transactionId, description, amount,
                TestDataFactory.BLANK_PROCESSING_TIMESTAMP);
    }

    /** One tabulated transaction with an explicit processing stamp. */
    private static String transactionWorkRecord(final String transactionId, final String description,
                                                final BigDecimal amount,
                                                final String processingTimestamp) {
        return transactionWorkRecordForCard(CARD_NUMBER, transactionId, description, amount,
                processingTimestamp);
    }

    /** One tabulated transaction on a named card, for the card-walk boundary scenarios. */
    private static String transactionWorkRecordForCard(final String cardNumber,
                                                       final String transactionId,
                                                       final String description,
                                                       final BigDecimal amount,
                                                       final String processingTimestamp) {
        return projected(TestDataFactory.transaction()
                .id(transactionId)
                .description(description)
                .amount(amount)
                .cardNumber(cardNumber)
                .originalTimestamp(TestDataFactory.SEEDED_ORIGINAL_TIMESTAMP)
                .processingTimestamp(processingTimestamp)
                .image());
    }

    /**
     * Applies the statement job's three-segment reprojection to a canonical transaction image.
     *
     * <p>The work resource the statement generator reads is card-number-first: the sort step lifts the
     * 16-byte card number to the front, follows it with the 262 bytes that preceded it, then copies 50
     * bytes from the origination timestamp onwards, and leaves 22 blanks. Reproduced here by test-side
     * slicing so that no production projection helper is consulted, even for an input.
     *
     * @param canonicalImage one 350-byte canonical transaction image
     * @return the 350-byte statement-work image the generator expects
     */
    private static String projected(final String canonicalImage) {
        return canonicalImage.substring(CARD_NUMBER_OFFSET, CARD_NUMBER_OFFSET + CARD_NUMBER_WIDTH)
                + canonicalImage.substring(0, CARD_NUMBER_OFFSET)
                + canonicalImage.substring(ORIGINATION_TIMESTAMP_OFFSET,
                        ORIGINATION_TIMESTAMP_OFFSET + PROJECTED_TIMESTAMP_SEGMENT_WIDTH)
                + " ".repeat(PROJECTED_TAIL_WIDTH);
    }

    /** The three tabulated transactions of the main scenario, one of them negative. */
    private static List<String> threeTabulatedTransactions() {
        return List.of(
                transactionWorkRecord(FIRST_TRANSACTION_ID, FIRST_TRANSACTION_DESCRIPTION,
                        FIRST_TRANSACTION_AMOUNT),
                transactionWorkRecord(SECOND_TRANSACTION_ID, SECOND_TRANSACTION_DESCRIPTION,
                        SECOND_TRANSACTION_AMOUNT),
                transactionWorkRecord(THIRD_TRANSACTION_ID, THIRD_TRANSACTION_DESCRIPTION,
                        THIRD_TRANSACTION_AMOUNT));
    }

    /**
     * A run of tabulated transactions with distinct keys, for the structural scenarios.
     *
     * @param count how many to tabulate on the one card
     * @return that many work images
     */
    private static List<String> tabulatedTransactions(final int count) {
        final List<String> workRecords = new ArrayList<>(count);
        for (int ordinal = 0; ordinal < count; ordinal++) {
            workRecords.add(transactionWorkRecord(
                    TestDataFactory.rightJustifyZeroFill(String.valueOf(ordinal + 1),
                            CARD_NUMBER_WIDTH),
                    FIRST_TRANSACTION_DESCRIPTION, FIRST_TRANSACTION_AMOUNT));
        }
        return workRecords;
    }

    /** Scripts one statement over one card carrying the three declared transactions. */
    private FileScript oneStatementOfThreeTransactions() {
        return this.script.withTransactions(threeTabulatedTransactions())
                .withCrossReferenceRecords(List.of(crossReferenceImage(CARD_NUMBER)));
    }

    /** Binds the mocked file handler to the script. */
    private void installFileHandler() {
        when(this.dataAccess.openCrossReferenceSource()).thenReturn(this.crossReferenceWalk);
        when(this.dataAccess.execute(any(), any(), any())).thenAnswer(this::answerFromScript);
    }

    /** Adapts a mock invocation to the script, keeping the argument extraction in one place. */
    private StatementFileResponse answerFromScript(final InvocationOnMock invocation) {
        final StatementFileRequest request = invocation.getArgument(0);
        final StatementTransactionSource snapshot = invocation.getArgument(1);
        final StatementCrossReferenceSource walk = invocation.getArgument(2);
        return this.script.answer(request, snapshot, walk);
    }

    /** Drives one complete generation. */
    private StatementGenerationService.StatementRun run() {
        return this.service.generate(this.transactionSource, REGULATED_FIELD_REVEALER,
                REGULATED_FIELD_SEALER);
    }

    /**
     * The ordered statement records one statement must emit.
     *
     * <p>Assembled from the declared literals above in the source's own write order, including the
     * address rule line written twice and the transaction rule line written three times. The order is
     * the contract, so this list is compared exactly rather than as a set.
     *
     * @param detailLines the expected detail lines, in tabulation order
     * @param totalLine   the expected total line
     * @return the complete expected record sequence
     */
    private static List<String> expectedStatementRecords(final List<String> detailLines,
                                                         final String totalLine) {
        final List<String> expected = new ArrayList<>();
        expected.add(EXPECTED_START_BANNER);
        expected.add(EXPECTED_NAME_LINE);
        expected.add(EXPECTED_ADDRESS_1_LINE);
        expected.add(EXPECTED_ADDRESS_2_LINE);
        expected.add(EXPECTED_ADDRESS_3_LINE);
        expected.add(EXPECTED_RULE_LINE);
        expected.add(EXPECTED_BASIC_DETAILS_HEADING);
        // The same rule line a second time, from its own write. Never de-duplicated.
        expected.add(EXPECTED_RULE_LINE);
        expected.add(EXPECTED_ACCOUNT_ID_LINE);
        expected.add(EXPECTED_CURRENT_BALANCE_LINE);
        expected.add(EXPECTED_FICO_LINE);
        expected.add(EXPECTED_RULE_LINE);
        expected.add(EXPECTED_TRANSACTION_SUMMARY_HEADING);
        expected.add(EXPECTED_RULE_LINE);
        expected.add(EXPECTED_COLUMN_HEADINGS);
        // The transaction rule line's second write, immediately after the column headings.
        expected.add(EXPECTED_RULE_LINE);
        expected.addAll(detailLines);
        // Its third write, in the paragraph that walks the tabulated transactions.
        expected.add(EXPECTED_RULE_LINE);
        expected.add(totalLine);
        expected.add(EXPECTED_END_BANNER);
        return expected;
    }

    /**
     * The eleven markup records one tabulated transaction contributes.
     *
     * @param transactionId the 16-byte key
     * @param description   the detail text, which the composer pads to its field width
     * @param amountMask    the declared 13-byte amount mask
     * @return the eleven expected records, in order
     */
    private static List<String> expectedDetailMarkup(final String transactionId,
                                                     final String description,
                                                     final String amountMask) {
        return List.of(markup(MARKUP_ROW_OPEN),
                markup(MARKUP_KEY_CELL),
                markup("<p>" + transactionId + "</p>"),
                markup(MARKUP_CELL_CLOSE),
                markup(MARKUP_DETAIL_CELL),
                markup("<p>" + description
                        + " ".repeat(DETAIL_FIELD_WIDTH - description.length()) + "</p>"),
                markup(MARKUP_CELL_CLOSE),
                markup(MARKUP_AMOUNT_CELL),
                markup("<p>" + amountMask + "</p>"),
                markup(MARKUP_CELL_CLOSE),
                markup(MARKUP_ROW_CLOSE));
    }

    /**
     * The ordered markup records one statement must emit.
     *
     * @param detailBlocks the per-transaction blocks, already in tabulation order
     * @return the complete expected record sequence
     */
    private static List<String> expectedMarkupRecords(final List<String> detailBlocks) {
        final List<String> expected = new ArrayList<>();
        expected.add(markup(MARKUP_DOCTYPE));
        expected.add(markup(MARKUP_HTML_OPEN));
        expected.add(markup(MARKUP_HEAD_OPEN));
        expected.add(markup(MARKUP_META_CHARSET));
        expected.add(markup(MARKUP_TITLE));
        expected.add(markup(MARKUP_HEAD_CLOSE));
        expected.add(markup(MARKUP_BODY_OPEN));
        expected.add(markup(MARKUP_TABLE_OPEN));
        expected.add(markup(MARKUP_ROW_OPEN));
        expected.add(markup(MARKUP_BANNER_CELL));
        expected.add(EXPECTED_ACCOUNT_NUMBER_MARKUP);
        expected.add(markup(MARKUP_CELL_CLOSE));
        expected.add(markup(MARKUP_ROW_CLOSE));
        expected.add(markup(MARKUP_ROW_OPEN));
        expected.add(markup(MARKUP_ADDRESS_CELL));
        expected.add(markup(MARKUP_BANK_NAME));
        expected.add(markup(MARKUP_BANK_STREET));
        expected.add(markup(MARKUP_BANK_CITY));
        expected.add(markup(MARKUP_CELL_CLOSE));
        expected.add(markup(MARKUP_ROW_CLOSE));
        expected.add(markup(MARKUP_ROW_OPEN));
        expected.add(markup(MARKUP_PLAIN_CELL));
        expected.add(EXPECTED_CUSTOMER_NAME_MARKUP);
        expected.add(EXPECTED_ADDRESS_1_MARKUP);
        expected.add(EXPECTED_ADDRESS_2_MARKUP);
        expected.add(EXPECTED_ADDRESS_3_MARKUP);
        expected.add(markup(MARKUP_CELL_CLOSE));
        expected.add(markup(MARKUP_ROW_CLOSE));
        expected.add(markup(MARKUP_ROW_OPEN));
        expected.add(markup(MARKUP_HEADING_CELL));
        expected.add(markup(MARKUP_BASIC_DETAILS_HEADING));
        expected.add(markup(MARKUP_CELL_CLOSE));
        expected.add(markup(MARKUP_ROW_CLOSE));
        expected.add(markup(MARKUP_ROW_OPEN));
        expected.add(markup(MARKUP_PLAIN_CELL));
        expected.add(EXPECTED_ACCOUNT_DETAIL_MARKUP);
        expected.add(EXPECTED_BALANCE_DETAIL_MARKUP);
        expected.add(EXPECTED_FICO_DETAIL_MARKUP);
        expected.add(markup(MARKUP_CELL_CLOSE));
        expected.add(markup(MARKUP_ROW_CLOSE));
        expected.add(markup(MARKUP_ROW_OPEN));
        expected.add(markup(MARKUP_HEADING_CELL));
        expected.add(markup(MARKUP_TRANSACTION_SUMMARY_HEADING));
        expected.add(markup(MARKUP_CELL_CLOSE));
        expected.add(markup(MARKUP_ROW_CLOSE));
        expected.add(markup(MARKUP_ROW_OPEN));
        expected.add(markup(MARKUP_KEY_HEADING_CELL));
        expected.add(markup(MARKUP_KEY_HEADING));
        expected.add(markup(MARKUP_CELL_CLOSE));
        expected.add(markup(MARKUP_DETAIL_HEADING_CELL));
        expected.add(markup(MARKUP_DETAIL_HEADING));
        expected.add(markup(MARKUP_CELL_CLOSE));
        expected.add(markup(MARKUP_AMOUNT_HEADING_CELL));
        expected.add(markup(MARKUP_AMOUNT_HEADING));
        expected.add(markup(MARKUP_CELL_CLOSE));
        expected.add(markup(MARKUP_ROW_CLOSE));
        expected.addAll(detailBlocks);
        expected.add(markup(MARKUP_ROW_OPEN));
        expected.add(markup(MARKUP_BANNER_CELL));
        expected.add(markup(MARKUP_END_OF_STATEMENT));
        expected.add(markup(MARKUP_CELL_CLOSE));
        expected.add(markup(MARKUP_ROW_CLOSE));
        expected.add(markup(MARKUP_TABLE_CLOSE));
        expected.add(markup(MARKUP_BODY_CLOSE));
        expected.add(markup(MARKUP_HTML_CLOSE));
        return expected;
    }

    /** The three detail blocks of the main scenario, concatenated in tabulation order. */
    private static List<String> expectedThreeDetailMarkupBlocks() {
        final List<String> blocks = new ArrayList<>();
        blocks.addAll(expectedDetailMarkup(FIRST_TRANSACTION_ID, FIRST_TRANSACTION_DESCRIPTION,
                MASK_FIRST_AMOUNT));
        blocks.addAll(expectedDetailMarkup(SECOND_TRANSACTION_ID, SECOND_TRANSACTION_DESCRIPTION,
                MASK_SECOND_AMOUNT));
        blocks.addAll(expectedDetailMarkup(THIRD_TRANSACTION_ID, THIRD_TRANSACTION_DESCRIPTION,
                MASK_THIRD_AMOUNT));
        return blocks;
    }

    /** The declared detail lines of the main scenario, in tabulation order. */
    private static List<String> expectedThreeDetailLines() {
        return List.of(EXPECTED_FIRST_DETAIL_LINE, EXPECTED_SECOND_DETAIL_LINE,
                EXPECTED_THIRD_DETAIL_LINE);
    }

    /** Encoded byte length of one record, which is the only width measurement this suite makes. */
    private static int encodedLength(final String record) {
        return record.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Asserts the shape of a batch abend raised from a failing transaction read.
     *
     * <p>The abend carries the batch code rather than the online one, names the legacy member as its
     * culprit, and renders the four context values into a 134-character image at the declared field
     * boundaries: code, culprit, reason, operator message.
     *
     * @param abend the abend the run raised
     */
    private static void assertBatchAbendShape(final AbendException abend) {
        assertThat(abend.code()).isEqualTo(AbendException.BATCH_ABEND_CODE).isEqualTo("999");
        assertThat(abend.culprit()).isEqualTo(LEGACY_PROGRAM_NAME);
        assertThat(abend.reason()).isEqualTo("ERROR READING TRNXFILE");
        assertThat(abend.getMessage()).isEqualTo(AbendException.DEFAULT_MESSAGE);
        final int codeEnd = AbendException.CODE_LENGTH;
        final int culpritEnd = codeEnd + AbendException.CULPRIT_LENGTH;
        final int reasonEnd = culpritEnd + AbendException.REASON_LENGTH;
        final String context = abend.toFixedWidthContext();
        assertThat(context).hasSize(AbendException.CONTEXT_LENGTH);
        assertThat(AbendException.CONTEXT_LENGTH).isEqualTo(134);
        assertThat(context.substring(0, codeEnd)).isEqualTo("999 ");
        assertThat(context.substring(codeEnd, culpritEnd)).isEqualTo(LEGACY_PROGRAM_NAME);
        assertThat(context.substring(culpritEnd, reasonEnd)).startsWith("ERROR READING TRNXFILE");
        assertThat(context.substring(reasonEnd)).startsWith(AbendException.DEFAULT_MESSAGE);
    }

    /** The indices at which a record equal to the rule line appears. */
    private static List<Integer> positionsOfRuleLine(final List<String> records) {
        final List<Integer> positions = new ArrayList<>();
        for (int index = 0; index < records.size(); index++) {
            if (EXPECTED_RULE_LINE.equals(records.get(index))) {
                positions.add(index);
            }
        }
        return positions;
    }

    // ================================================================================================
    // 0000-START [L296] - the dispatcher, its six clauses and the six backward jumps that reach it
    // ================================================================================================

    @Nested
    @DisplayName("0000-START: the dispatcher and its backward re-entries")
    class DispatcherTransitions {

        @Test
        @DisplayName("the dispatcher is re-entered after EVERY state change, so the six clauses run in "
                + "dispatch order - an implementation that flattened the phases into nested calls "
                + "fails this test")
        void dispatcherIsReEnteredAfterEveryStateChange() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            final StatementGenerationService.StatementRun run = run();

            // Each entry is one arrival at the dispatcher. A nested-call translation would arrive once,
            // run the phases inside one another and record a single entry - or, worse, record the same
            // six and still execute them in the order of the call chain rather than of the state field.
            assertThat(run.dispatchedPhases()).containsExactly("TRNXFILE", "READTRNX", "XREFFILE",
                    "CUSTFILE", "ACCTFILE", "TERMINATED");
        }

        @Test
        @DisplayName("the initial state is the transaction-file phase the eight-character field is "
                + "initialised to")
        void initialStateIsTheTransactionFilePhase() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            final StatementGenerationService.StatementRun run = run();

            assertThat(run.dispatchedPhases()).isNotEmpty();
            assertThat(run.dispatchedPhases().get(0)).isEqualTo("TRNXFILE");
        }

        @Test
        @DisplayName("all six clauses of the selection are exercised, each exactly once, and the four "
                + "open clauses keep their source order relative to one another")
        void allSixClausesAreExercisedExactlyOnce() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            final StatementGenerationService.StatementRun run = run();

            // The clauses as the source writes them: four opens, then the read phase, then the
            // catch-all that transfers to the program exit.
            assertThat(run.dispatchedPhases()).containsExactlyInAnyOrderElementsOf(
                    List.of("TRNXFILE", "XREFFILE", "CUSTFILE", "ACCTFILE", "READTRNX", "TERMINATED"));
            final List<String> openClauses = new ArrayList<>();
            for (final String phase : run.dispatchedPhases()) {
                if (!"READTRNX".equals(phase) && !"TERMINATED".equals(phase)) {
                    openClauses.add(phase);
                }
            }
            assertThat(openClauses).containsExactly("TRNXFILE", "XREFFILE", "CUSTFILE", "ACCTFILE");
        }

        @Test
        @DisplayName("8100-TRNXFILE-OPEN [L760-L761]: the first backward jump sets the transaction-read "
                + "phase and re-enters the dispatcher")
        void transactionFileOpenReEntersWithTheReadPhase() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            final List<String> phases = run().dispatchedPhases();

            assertThat(phases.get(phases.indexOf("TRNXFILE") + 1)).isEqualTo("READTRNX");
        }

        @Test
        @DisplayName("8599-EXIT [L851-L852]: the fourth backward jump sets the cross-reference-file "
                + "phase and re-enters the dispatcher, so the read phase changes state rather than "
                + "returning")
        void readPhaseExitReEntersWithTheCrossReferencePhase() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            final List<String> phases = run().dispatchedPhases();

            assertThat(phases.get(phases.indexOf("READTRNX") + 1)).isEqualTo("XREFFILE");
        }

        @Test
        @DisplayName("8200-XREFFILE-OPEN [L779-L780]: the second backward jump sets the customer-file "
                + "phase and re-enters the dispatcher")
        void crossReferenceOpenReEntersWithTheCustomerPhase() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            final List<String> phases = run().dispatchedPhases();

            assertThat(phases.get(phases.indexOf("XREFFILE") + 1)).isEqualTo("CUSTFILE");
        }

        @Test
        @DisplayName("8300-CUSTFILE-OPEN [L797-L798]: the third backward jump sets the account-file "
                + "phase and re-enters the dispatcher")
        void customerOpenReEntersWithTheAccountPhase() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            final List<String> phases = run().dispatchedPhases();

            assertThat(phases.get(phases.indexOf("CUSTFILE") + 1)).isEqualTo("ACCTFILE");
        }

        @Test
        @DisplayName("8400-ACCTFILE-OPEN [L815]: the fifth backward jump reaches 1000-MAINLINE, which "
                + "runs the statement loop and falls through into 9999-GOBACK exactly once")
        void accountOpenTransfersToTheMainlineAndThenToTheProgramExit() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            final StatementGenerationService.StatementRun run = run();

            final List<String> phases = run.dispatchedPhases();
            assertThat(phases.get(phases.indexOf("ACCTFILE") + 1)).isEqualTo("TERMINATED");
            // The mainline really ran: it produced the statement and closed all four files.
            assertThat(run.statementsWritten()).isEqualTo(1);
            assertThat(phases).containsOnlyOnce("TERMINATED");
        }

        @Test
        @DisplayName("8500-READTRNX-READ [L840]: the sixth backward jump loops the read paragraph onto "
                + "itself, tabulating every record before the phase exits")
        void readParagraphLoopsOntoItselfUntilEndOfFile() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            final StatementGenerationService.StatementRun run = run();

            assertThat(run.cardsTabulated()).isEqualTo(1);
            assertThat(run.transactionsTabulated()).isEqualTo(3);
            // One dispatcher entry for the read phase, however many times the paragraph looped.
            assertThat(run.dispatchedPhases()).containsOnlyOnce("READTRNX");
        }

        @Test
        @DisplayName("the dispatcher holds no state between runs, so a second run repeats the first "
                + "phase for phase")
        void theDispatcherCarriesNoStateBetweenRuns() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            final StatementGenerationService.StatementRun first = run();
            this.resetScriptForSecondRun();
            final StatementGenerationService.StatementRun second = run();

            assertThat(second.dispatchedPhases()).isEqualTo(first.dispatchedPhases());
            assertThat(second.statementRecords()).isEqualTo(first.statementRecords());
            assertThat(second.htmlRecords()).isEqualTo(first.htmlRecords());
        }

        /** Rewinds the scripted read ordinals so the second run sees the same file as the first. */
        private void resetScriptForSecondRun() {
            script.transactionReads = 0;
            script.crossReferenceReads = 0;
        }
    }

    // ================================================================================================
    // The status tests - four selection-form sites accepting one code, nine guard-form sites accepting
    // two, and the abend path that every failure reaches
    // ================================================================================================

    @Nested
    @DisplayName("status handling: iterate on success, exit at end of file, log then abend otherwise")
    class ReadLoopStatuses {

        @Test
        @DisplayName("8500-READTRNX-READ [L837-L842]: two successes then end of file make exactly three "
                + "reads and terminate cleanly")
        void twoSuccessesThenEndOfFileMakeThreeReadsAndTerminateCleanly() {
            installFileHandler();
            script.withTransactions(tabulatedTransactions(2))
                    .withCrossReferenceRecords(List.of(crossReferenceImage(CARD_NUMBER)));

            final StatementGenerationService.StatementRun run = run();

            // The priming read at L746 and the two loop reads at L835: the third of the three reports
            // at-end, which leaves the phase rather than failing it.
            assertThat(script.transactionReads).isEqualTo(3);
            assertThat(run.transactionsTabulated()).isEqualTo(2);
            verifyNoInteractions(abendService);
        }

        @Test
        @DisplayName("9999-ABEND-PROGRAM [L921-L923]: an error status on a transaction read is logged "
                + "with its raw two-byte value BEFORE the abend is raised")
        void anErrorStatusOnATransactionReadIsLoggedBeforeTheAbendIsRaised() {
            installFileHandler();
            script.withTransactions(tabulatedTransactions(2))
                    .withCrossReferenceRecords(List.of(crossReferenceImage(CARD_NUMBER)))
                    .failingTransactionReadAt(1, STATUS_RECORD_NOT_FOUND);
            final List<String> loggedBeforeTheAbend = new ArrayList<>();
            Mockito.doAnswer(invocation -> {
                for (final ILoggingEvent event : recorder.list) {
                    loggedBeforeTheAbend.add(event.getFormattedMessage());
                }
                return null;
            }).when(abendService).abendBatch(any(), any(), any(), any(), any());

            assertThatThrownBy(StatementGenerationServiceTest.this::run)
                    .isInstanceOf(AbendException.class);

            // Already recorded at the moment the abort routine was reached, not merely afterwards.
            assertThat(loggedBeforeTheAbend)
                    .anySatisfy(message -> assertThat(message)
                            .contains("ERROR READING")
                            .contains(STATUS_RECORD_NOT_FOUND));
            verify(abendService).abendBatch(LEGACY_PROGRAM_NAME, "ERROR READING TRNXFILE",
                    STATUS_RECORD_NOT_FOUND, OPERATION_READ, "TRNXFILE");
        }

        @Test
        @DisplayName("the raised abend names the program as its culprit, carries the batch code and "
                + "renders a 134-character context at the four declared field offsets")
        void theRaisedAbendCarriesTheProgramNameAndTheBatchCode() {
            installFileHandler();
            script.withTransactions(tabulatedTransactions(2))
                    .withCrossReferenceRecords(List.of(crossReferenceImage(CARD_NUMBER)))
                    .failingTransactionReadAt(1, STATUS_RECORD_NOT_FOUND);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(StatementGenerationServiceTest.this::run)
                    .satisfies(StatementGenerationServiceTest::assertBatchAbendShape);
        }

        @Test
        @DisplayName("the record-length status is ACCEPTED at the four opens, never treated as an error")
        void theRecordLengthStatusIsAcceptedOnOpen() {
            installFileHandler();
            oneStatementOfThreeTransactions().withOpenStatus(STATUS_RECORD_LENGTH_MISMATCH);

            final StatementGenerationService.StatementRun run = run();

            assertThat(run.statementsWritten()).isEqualTo(1);
            assertThat(run.dispatchedPhases()).endsWith("TERMINATED");
            verifyNoInteractions(abendService);
        }

        @Test
        @DisplayName("the record-length status is ACCEPTED at the priming transaction read [L748], so "
                + "the read phase still primes and the run still produces a statement")
        void theRecordLengthStatusIsAcceptedOnThePrimingRead() {
            installFileHandler();
            oneStatementOfThreeTransactions()
                    .withPrimingReadStatus(STATUS_RECORD_LENGTH_MISMATCH);

            final StatementGenerationService.StatementRun run = run();

            assertThat(run.transactionsTabulated()).isEqualTo(3);
            assertThat(run.statementRecords())
                    .containsExactlyElementsOf(expectedStatementRecords(expectedThreeDetailLines(),
                            EXPECTED_TOTAL_LINE));
            verifyNoInteractions(abendService);
        }

        @Test
        @DisplayName("the record-length status is ACCEPTED at the four closes")
        void theRecordLengthStatusIsAcceptedOnClose() {
            installFileHandler();
            oneStatementOfThreeTransactions().withCloseStatus(STATUS_RECORD_LENGTH_MISMATCH);

            final StatementGenerationService.StatementRun run = run();

            assertThat(run.statementsWritten()).isEqualTo(1);
            verifyNoInteractions(abendService);
        }

        @Test
        @DisplayName("1000-XREFFILE-GET-NEXT [L356-L357]: end of file on the cross-reference read ends "
                + "the loop normally and is never an error")
        void endOfFileOnTheCrossReferenceReadEndsTheRunWithoutAbending() {
            installFileHandler();
            script.withTransactions(tabulatedTransactions(1))
                    .withCrossReferenceRecords(List.of());

            final StatementGenerationService.StatementRun run = run();

            assertThat(run.statementsWritten()).isZero();
            assertThat(run.statementRecords()).isEmpty();
            assertThat(run.htmlRecords()).isEmpty();
            assertThat(run.dispatchedPhases()).endsWith("TERMINATED");
            verifyNoInteractions(abendService);
        }

        @Test
        @DisplayName("1000-XREFFILE-GET-NEXT [L358-L361]: any other status on the cross-reference read "
                + "is logged and abends")
        void anErrorStatusOnTheCrossReferenceReadAbends() {
            installFileHandler();
            script.withTransactions(tabulatedTransactions(1))
                    .withCrossReferenceRecords(List.of(crossReferenceImage(CARD_NUMBER)))
                    .failingCrossReferenceReadAt(0, STATUS_RECORD_NOT_FOUND);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(StatementGenerationServiceTest.this::run)
                    .satisfies(abend -> assertThat(abend.reason()).isEqualTo("ERROR READING XREFFILE"));

            verify(abendService).abendBatch(LEGACY_PROGRAM_NAME, "ERROR READING XREFFILE",
                    STATUS_RECORD_NOT_FOUND, OPERATION_READ, "XREFFILE");
        }

        @Test
        @DisplayName("2000-CUSTFILE-GET [L379-L386]: the keyed customer read has no at-end arm, so a "
                + "record-not-found status abends rather than ending the loop")
        void aRecordNotFoundOnTheKeyedCustomerReadAbends() {
            installFileHandler();
            oneStatementOfThreeTransactions()
                    .withCustomerReadStatus(STATUS_RECORD_NOT_FOUND);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(StatementGenerationServiceTest.this::run)
                    .satisfies(abend -> assertThat(abend.reason()).isEqualTo("ERROR READING CUSTFILE"));

            verify(abendService).abendBatch(LEGACY_PROGRAM_NAME, "ERROR READING CUSTFILE",
                    STATUS_RECORD_NOT_FOUND, OPERATION_READ, "CUSTFILE");
        }

        @Test
        @DisplayName("2000-CUSTFILE-GET: the at-end status is NOT an accepted outcome of a keyed read "
                + "either, because the keyed selection accepts success alone")
        void theAtEndStatusOnTheKeyedCustomerReadAlsoAbends() {
            installFileHandler();
            oneStatementOfThreeTransactions().withCustomerReadStatus(STATUS_END_OF_FILE);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(StatementGenerationServiceTest.this::run)
                    .satisfies(abend -> assertThat(abend.reason()).isEqualTo("ERROR READING CUSTFILE"));
        }

        @Test
        @DisplayName("3000-ACCTFILE-GET [L403-L410]: the keyed account read behaves identically, "
                + "differing only in the key it presents")
        void aRecordNotFoundOnTheKeyedAccountReadAbends() {
            installFileHandler();
            oneStatementOfThreeTransactions().withAccountReadStatus(STATUS_RECORD_NOT_FOUND);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(StatementGenerationServiceTest.this::run)
                    .satisfies(abend -> assertThat(abend.reason()).isEqualTo("ERROR READING ACCTFILE"));

            verify(abendService).abendBatch(LEGACY_PROGRAM_NAME, "ERROR READING ACCTFILE",
                    STATUS_RECORD_NOT_FOUND, OPERATION_READ, "ACCTFILE");
        }

        @Test
        @DisplayName("8100-TRNXFILE-OPEN [L736-L742]: a status outside the two the guard accepts abends "
                + "at the open, naming the open operation")
        void anUnacceptedStatusOnTheOpenAbends() {
            installFileHandler();
            oneStatementOfThreeTransactions().withOpenStatus(STATUS_RECORD_NOT_FOUND);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(StatementGenerationServiceTest.this::run)
                    .satisfies(abend -> assertThat(abend.reason()).isEqualTo("ERROR OPENING TRNXFILE"));

            verify(abendService).abendBatch(LEGACY_PROGRAM_NAME, "ERROR OPENING TRNXFILE",
                    STATUS_RECORD_NOT_FOUND, OPERATION_OPEN, "TRNXFILE");
        }

        @Test
        @DisplayName("8100-TRNXFILE-OPEN [L748-L754]: an empty transaction file abends at the priming "
                + "read, because the at-end status is not one of the two the guard accepts")
        void anEmptyTransactionFileAbendsAtThePrimingRead() {
            installFileHandler();
            script.withTransactions(List.of())
                    .withCrossReferenceRecords(List.of(crossReferenceImage(CARD_NUMBER)));

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(StatementGenerationServiceTest.this::run)
                    .satisfies(abend -> assertThat(abend.reason()).isEqualTo("ERROR READING TRNXFILE"));

            verify(abendService).abendBatch(LEGACY_PROGRAM_NAME, "ERROR READING TRNXFILE",
                    STATUS_END_OF_FILE, OPERATION_READ, "TRNXFILE");
        }

        @Test
        @DisplayName("9100-TRNXFILE-CLOSE [L862-L868]: an unaccepted status on a close abends, naming "
                + "the close operation")
        void anUnacceptedStatusOnTheCloseAbends() {
            installFileHandler();
            oneStatementOfThreeTransactions().withCloseStatus(STATUS_RECORD_NOT_FOUND);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(StatementGenerationServiceTest.this::run)
                    .satisfies(abend -> assertThat(abend.reason()).isEqualTo("ERROR CLOSING TRNXFILE"));

            verify(abendService).abendBatch(LEGACY_PROGRAM_NAME, "ERROR CLOSING TRNXFILE",
                    STATUS_RECORD_NOT_FOUND, OPERATION_CLOSE, "TRNXFILE");
        }

        @Test
        @DisplayName("the coarse success / at-end / error tri-state is derived through the status enum "
                + "itself, never through a separate top-level normaliser")
        void theCoarseTriStateIsDerivedThroughTheStatusEnum() {
            final Optional<FileStatus> success = FileStatus.fromCode(STATUS_SUCCESS);
            final Optional<FileStatus> atEnd = FileStatus.fromCode(STATUS_END_OF_FILE);
            final Optional<FileStatus> notFound = FileStatus.fromCode(STATUS_RECORD_NOT_FOUND);
            final Optional<FileStatus> lengthMismatch =
                    FileStatus.fromCode(STATUS_RECORD_LENGTH_MISMATCH);

            assertThat(success).contains(FileStatus.SUCCESS);
            assertThat(success.orElseThrow().isSuccess()).isTrue();
            assertThat(success.orElseThrow().isEndOfFile()).isFalse();
            assertThat(atEnd).contains(FileStatus.END_OF_FILE);
            assertThat(atEnd.orElseThrow().isEndOfFile()).isTrue();
            assertThat(atEnd.orElseThrow().isSuccess()).isFalse();
            // Neither success nor at-end: the error arm, which is the third of the three outcomes.
            assertThat(notFound).contains(FileStatus.RECORD_NOT_FOUND);
            assertThat(notFound.orElseThrow().isSuccess()).isFalse();
            assertThat(notFound.orElseThrow().isEndOfFile()).isFalse();
            assertThat(lengthMismatch).contains(FileStatus.RECORD_LENGTH_MISMATCH);
            assertThat(lengthMismatch.orElseThrow().isSuccess()).isFalse();
            // An undeclared code is an empty result rather than a synthetic unknown constant.
            assertThat(FileStatus.fromCode("XX")).isEmpty();
            assertThat(FileStatus.fromCode(null)).isEmpty();
        }

        @Test
        @DisplayName("the driven status vocabulary is exactly the four codes the source compares, and "
                + "excludes the two that prior specification text cites but no program compares")
        void theDrivenStatusVocabularyExcludesTheDocumentedButUncomparedCodes() {
            assertThat(DRIVEN_STATUS_VOCABULARY).containsExactly("00", "04", "10", "23");
            assertThat(DRIVEN_STATUS_VOCABULARY)
                    .doesNotContain(STATUS_DOCUMENTED_DUPLICATE_KEY,
                            STATUS_DOCUMENTED_FILE_NOT_FOUND);
            // The enum still declares them, because documentation reach is wider than exercised
            // vocabulary - but nothing in this member or this suite branches on either.
            assertThat(FileStatus.DUPLICATE_KEY.getCode())
                    .isEqualTo(STATUS_DOCUMENTED_DUPLICATE_KEY);
            assertThat(FileStatus.FILE_NOT_FOUND.getCode())
                    .isEqualTo(STATUS_DOCUMENTED_FILE_NOT_FOUND);
        }

        @Test
        @DisplayName("the file-status exception models the error arm alone: constructing it with the "
                + "success or at-end code is rejected")
        void theFileStatusExceptionModelsTheErrorArmAlone() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new FileStatusException(FileStatusException.STATUS_SUCCESS,
                            OPERATION_READ, "TRNXFILE"));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new FileStatusException(FileStatusException.STATUS_END_OF_FILE,
                            OPERATION_READ, "TRNXFILE"));

            final FileStatusException failure =
                    new FileStatusException(STATUS_RECORD_NOT_FOUND, OPERATION_READ, "CUSTFILE");
            assertThat(failure.code()).isEqualTo(STATUS_RECORD_NOT_FOUND);
            assertThat(failure.code()).hasSize(FileStatusException.CODE_LENGTH);
            assertThat(failure.operation()).isEqualTo(OPERATION_READ);
            assertThat(failure.resourceName()).isEqualTo("CUSTFILE");
            assertThat(failure.firstByte()).isEqualTo('2');
            assertThat(failure.secondByte()).isEqualTo('3');
            assertThat(failure).isInstanceOf(RuntimeException.class);
            assertThat(FileStatusException.DISPLAY_PREFIX).isEqualTo("FILE STATUS IS: NNNN");
        }
    }

    // ================================================================================================
    // The two record widths [L45, L47] - measured on encoded bytes, across whole collections
    // ================================================================================================

    @Nested
    @DisplayName("record widths: 80 encoded bytes plain, 100 encoded bytes markup, never trimmed")
    class RecordWidths {

        @ParameterizedTest(name = "with {0} tabulated transactions")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7})
        @DisplayName("EVERY plain statement record is exactly 80 encoded bytes")
        void everyStatementRecordIsExactlyEightyEncodedBytes(final int transactionCount) {
            installFileHandler();
            script.withTransactions(tabulatedTransactions(transactionCount))
                    .withCrossReferenceRecords(List.of(crossReferenceImage(CARD_NUMBER)));

            final List<String> records = run().statementRecords();

            assertThat(records)
                    .hasSize(FIXED_STATEMENT_RECORDS_PER_STATEMENT + transactionCount)
                    .allSatisfy(record -> assertThat(encodedLength(record))
                            .isEqualTo(STATEMENT_RECORD_WIDTH));
        }

        @ParameterizedTest(name = "with {0} tabulated transactions")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7})
        @DisplayName("EVERY markup statement record is exactly 100 encoded bytes")
        void everyMarkupRecordIsExactlyOneHundredEncodedBytes(final int transactionCount) {
            installFileHandler();
            script.withTransactions(tabulatedTransactions(transactionCount))
                    .withCrossReferenceRecords(List.of(crossReferenceImage(CARD_NUMBER)));

            final List<String> records = run().htmlRecords();

            assertThat(records)
                    .hasSize(FIXED_MARKUP_RECORDS_PER_STATEMENT
                            + MARKUP_RECORDS_PER_TRANSACTION * transactionCount)
                    .allSatisfy(record -> assertThat(encodedLength(record))
                            .isEqualTo(MARKUP_RECORD_WIDTH));
        }

        @Test
        @DisplayName("no record is trimmed: the trailing pad bytes of the shortest records survive in "
                + "both streams")
        void noRecordIsTrimmedAtEitherWidth() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            final StatementGenerationService.StatementRun run = run();

            // The shortest significant content in each stream is a four-character markup tag and an
            // eight-character address line; both must still occupy their whole record.
            assertThat(run.htmlRecords()).contains(markup(MARKUP_ROW_OPEN));
            assertThat(markup(MARKUP_ROW_OPEN)).hasSize(MARKUP_RECORD_WIDTH).endsWith(" ");
            assertThat(run.statementRecords()).contains(EXPECTED_ADDRESS_2_LINE);
            assertThat(EXPECTED_ADDRESS_2_LINE).endsWith(" ");
            assertThat(encodedLength(EXPECTED_ADDRESS_2_LINE)).isEqualTo(STATEMENT_RECORD_WIDTH);
        }

        @Test
        @DisplayName("the two widths are measured in encoded bytes, and the emitted records are pure "
                + "US-ASCII so encoding cannot change a length")
        void recordsArePureUsAsciiSoEncodedLengthEqualsFieldWidth() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            final StatementGenerationService.StatementRun run = run();

            assertThat(run.statementRecords()).allSatisfy(record -> assertThat(
                    new String(record.getBytes(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII))
                    .isEqualTo(record));
            assertThat(run.htmlRecords()).allSatisfy(record -> assertThat(
                    new String(record.getBytes(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII))
                    .isEqualTo(record));
        }
    }

    // ================================================================================================
    // ST-LINE0 [L86-L89] and ST-LINE15 [L143-L146] - two banners with two different splits
    // ================================================================================================

    @Nested
    @DisplayName("banner geometry: 31 / 18 / 31 at the start, 32 / 16 / 32 at the end")
    class BannerGeometry {

        @Test
        @DisplayName("the start banner splits 31 marker bytes, an 18-character caption and 31 more")
        void theStartBannerSplitsThirtyOneEighteenThirtyOne() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            final String banner = run().statementRecords().get(0);

            final int captionEnd = START_BANNER_FILL_WIDTH + START_BANNER_CAPTION_WIDTH;
            assertThat(banner.substring(0, START_BANNER_FILL_WIDTH))
                    .isEqualTo("*".repeat(START_BANNER_FILL_WIDTH));
            assertThat(banner.substring(START_BANNER_FILL_WIDTH, captionEnd))
                    .isEqualTo("START OF STATEMENT")
                    .hasSize(START_BANNER_CAPTION_WIDTH);
            assertThat(banner.substring(captionEnd))
                    .isEqualTo("*".repeat(START_BANNER_FILL_WIDTH));
            assertThat(encodedLength(banner)).isEqualTo(STATEMENT_RECORD_WIDTH);
            assertThat(banner).isEqualTo(EXPECTED_START_BANNER);
        }

        @Test
        @DisplayName("the end banner splits 32 marker bytes, a 16-character caption and 32 more")
        void theEndBannerSplitsThirtyTwoSixteenThirtyTwo() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            final List<String> records = run().statementRecords();
            final String banner = records.get(records.size() - 1);

            final int captionEnd = END_BANNER_FILL_WIDTH + END_BANNER_CAPTION_WIDTH;
            assertThat(banner.substring(0, END_BANNER_FILL_WIDTH))
                    .isEqualTo("*".repeat(END_BANNER_FILL_WIDTH));
            assertThat(banner.substring(END_BANNER_FILL_WIDTH, captionEnd))
                    .isEqualTo("END OF STATEMENT")
                    .hasSize(END_BANNER_CAPTION_WIDTH);
            assertThat(banner.substring(captionEnd)).isEqualTo("*".repeat(END_BANNER_FILL_WIDTH));
            assertThat(encodedLength(banner)).isEqualTo(STATEMENT_RECORD_WIDTH);
            assertThat(banner).isEqualTo(EXPECTED_END_BANNER);
        }

        @Test
        @DisplayName("the two splits differ although both total 80, so no shared centring computation "
                + "may produce them")
        void theTwoBannerSplitsAreNotInterchangeable() {
            assertThat(encodedLength(EXPECTED_START_BANNER)).isEqualTo(STATEMENT_RECORD_WIDTH);
            assertThat(encodedLength(EXPECTED_END_BANNER)).isEqualTo(STATEMENT_RECORD_WIDTH);
            assertThat(START_BANNER_FILL_WIDTH).isNotEqualTo(END_BANNER_FILL_WIDTH);
            assertThat(START_BANNER_CAPTION_WIDTH).isNotEqualTo(END_BANNER_CAPTION_WIDTH);
            assertThat(EXPECTED_START_BANNER).isNotEqualTo(EXPECTED_END_BANNER);
        }
    }

    // ================================================================================================
    // 5000-CREATE-STATEMENT [L488-L502] and 4000-TRNXFILE-GET [L435-L437] - the write order and its
    // deliberate repeats
    // ================================================================================================

    @Nested
    @DisplayName("write order: the source's sequence, including the repeats that must not be collapsed")
    class WriteOrderAndDuplicates {

        @Test
        @DisplayName("the whole statement is emitted in the source's write order, repeats included")
        void theStatementIsEmittedInSourceOrder() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            final List<String> records = run().statementRecords();

            assertThat(records).containsExactlyElementsOf(
                    expectedStatementRecords(expectedThreeDetailLines(), EXPECTED_TOTAL_LINE));
        }

        @Test
        @DisplayName("ST-LINE5 [L492, L494]: the address rule line is written TWICE, at both of its "
                + "positions, with the basic-details heading between them")
        void theAddressRuleLineIsWrittenTwice() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            final List<String> records = run().statementRecords();

            assertThat(records.get(5)).isEqualTo(EXPECTED_RULE_LINE);
            assertThat(records.get(6)).isEqualTo(EXPECTED_BASIC_DETAILS_HEADING);
            assertThat(records.get(7)).isEqualTo(EXPECTED_RULE_LINE);
        }

        @Test
        @DisplayName("ST-LINE12 [L500, L502, L435]: the transaction rule line is written THREE times, "
                + "at all three of its positions")
        void theTransactionRuleLineIsWrittenThreeTimes() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            final List<String> records = run().statementRecords();

            final int thirdPosition = records.size() - 3;
            assertThat(records.get(13)).isEqualTo(EXPECTED_RULE_LINE);
            assertThat(records.get(14)).isEqualTo(EXPECTED_COLUMN_HEADINGS);
            assertThat(records.get(15)).isEqualTo(EXPECTED_RULE_LINE);
            assertThat(records.get(thirdPosition)).isEqualTo(EXPECTED_RULE_LINE);
            assertThat(records.get(thirdPosition + 1)).isEqualTo(EXPECTED_TOTAL_LINE);
        }

        @Test
        @DisplayName("exactly six byte-identical 80-byte rule lines appear per statement, at the six "
                + "positions the source writes them")
        void sixByteIdenticalRuleLinesAppearPerStatement() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            final List<String> records = run().statementRecords();

            // Two from the address rule line, one from the balance rule line, three from the
            // transaction rule line. Identical content, six distinct writes.
            assertThat(positionsOfRuleLine(records))
                    .hasSize(RULE_LINES_PER_STATEMENT)
                    .containsExactly(5, 7, 11, 13, 15, records.size() - 3);
            assertThat(EXPECTED_RULE_LINE).hasSize(STATEMENT_RECORD_WIDTH);
        }

        @ParameterizedTest(name = "with {0} tabulated transactions")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7})
        @DisplayName("6000-WRITE-TRANS [L679]: the detail line appears once per tabulated transaction, "
                + "and the six rule lines do not multiply with it")
        void theDetailLineAppearsOncePerTransaction(final int transactionCount) {
            installFileHandler();
            script.withTransactions(tabulatedTransactions(transactionCount))
                    .withCrossReferenceRecords(List.of(crossReferenceImage(CARD_NUMBER)));

            final List<String> records = run().statementRecords();

            assertThat(records).hasSize(FIXED_STATEMENT_RECORDS_PER_STATEMENT + transactionCount);
            assertThat(positionsOfRuleLine(records)).hasSize(RULE_LINES_PER_STATEMENT);
            final List<String> detailLines = records.subList(16, 16 + transactionCount);
            assertThat(detailLines).hasSize(transactionCount)
                    .allSatisfy(line -> assertThat(line).contains("$"));
        }

        @Test
        @DisplayName("ST-LINE14A [L436]: the end-of-statement total variant appears exactly once, "
                + "immediately before the end banner")
        void theTotalVariantAppearsExactlyOnce() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            final List<String> records = run().statementRecords();

            assertThat(records).containsOnlyOnce(EXPECTED_TOTAL_LINE);
            assertThat(records.get(records.size() - 2)).isEqualTo(EXPECTED_TOTAL_LINE);
            assertThat(records.get(records.size() - 1)).isEqualTo(EXPECTED_END_BANNER);
        }

        @Test
        @DisplayName("5100-WRITE-HTML-HEADER through 4000-TRNXFILE-GET: the whole markup document is "
                + "emitted in the source's write order, its repeated tags included")
        void theMarkupDocumentIsEmittedInSourceOrder() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            final List<String> records = run().htmlRecords();

            assertThat(records).containsExactlyElementsOf(
                    expectedMarkupRecords(expectedThreeDetailMarkupBlocks()));
        }

        @Test
        @DisplayName("the markup document opens and closes on the tags the source writes, and the "
                + "table opener sits in its eighth position")
        void theMarkupDocumentOpensAndClosesOnTheSourceTags() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            final List<String> records = run().htmlRecords();

            assertThat(records.get(0)).isEqualTo(markup(MARKUP_DOCTYPE));
            assertThat(records.get(7)).isEqualTo(markup(MARKUP_TABLE_OPEN));
            assertThat(records.get(records.size() - 3)).isEqualTo(markup(MARKUP_TABLE_CLOSE));
            assertThat(records.get(records.size() - 2)).isEqualTo(markup(MARKUP_BODY_CLOSE));
            assertThat(records.get(records.size() - 1)).isEqualTo(markup(MARKUP_HTML_CLOSE));
        }
    }

    // ================================================================================================
    // 5200-WRITE-HTML-NMADBS [L560-L592] - the delimiter of two adjacent spaces
    // ================================================================================================

    @Nested
    @DisplayName("delimiter behaviour: the markup transfers stop at two adjacent spaces, never at one")
    class DelimiterBehaviour {

        @Test
        @DisplayName("the name heading keeps all three name words, because the transfer stops at the "
                + "first PAIR of spaces and not at the single ones between the words")
        void theNameHeadingStopsAtTheFirstAdjacentPairOfSpaces() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            final List<String> records = run().htmlRecords();

            assertThat(records).contains(EXPECTED_CUSTOMER_NAME_MARKUP);
            assertThat(records.get(22)).isEqualTo(EXPECTED_CUSTOMER_NAME_MARKUP);
            // The wrong outcome, declared so the intent is visible: splitting on one space would stop
            // after the first name and still emit a well-formed 100-byte record.
            assertThat(records).doesNotContain(SINGLE_SPACE_SPLIT_NAME_MARKUP);
            assertThat(EXPECTED_CUSTOMER_NAME_MARKUP).isNotEqualTo(SINGLE_SPACE_SPLIT_NAME_MARKUP);
            assertThat(encodedLength(SINGLE_SPACE_SPLIT_NAME_MARKUP))
                    .isEqualTo(MARKUP_RECORD_WIDTH);
        }

        @Test
        @DisplayName("the first address paragraph keeps its two internal single spaces for the same "
                + "reason, and a single-space split would truncate it after the house number")
        void theAddressParagraphStopsAtTheFirstAdjacentPairOfSpaces() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            final List<String> records = run().htmlRecords();

            assertThat(records.get(23)).isEqualTo(EXPECTED_ADDRESS_1_MARKUP);
            assertThat(records).doesNotContain(SINGLE_SPACE_SPLIT_ADDRESS_MARKUP);
            assertThat(EXPECTED_ADDRESS_1_MARKUP).isNotEqualTo(SINGLE_SPACE_SPLIT_ADDRESS_MARKUP);
            assertThat(encodedLength(SINGLE_SPACE_SPLIT_ADDRESS_MARKUP))
                    .isEqualTo(MARKUP_RECORD_WIDTH);
        }

        @Test
        @DisplayName("the second and third address paragraphs follow the same rule, the third keeping "
                + "all four assembled operands")
        void theRemainingAddressParagraphsFollowTheSameRule() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            final List<String> records = run().htmlRecords();

            assertThat(records.get(24)).isEqualTo(EXPECTED_ADDRESS_2_MARKUP);
            assertThat(records.get(25)).isEqualTo(EXPECTED_ADDRESS_3_MARKUP);
            assertThat(records.get(25)).contains(ADDRESS_LINE_3).contains(STATE_CODE)
                    .contains(COUNTRY_CODE).contains(ADDRESS_ZIP);
        }

        @Test
        @DisplayName("the plain statement's own assemblies use a SINGLE space instead, so the two "
                + "delimiters are not interchangeable")
        void thePlainAssembliesUseTheSingleSpaceDelimiterInstead() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            final List<String> records = run().statementRecords();

            // The plain name and third-address lines interleave one space between operands, each
            // operand transferred only as far as its own first space.
            assertThat(records.get(1)).isEqualTo(EXPECTED_NAME_LINE);
            assertThat(records.get(4)).isEqualTo(EXPECTED_ADDRESS_3_LINE);
            assertThat(records.get(1)).startsWith(FIRST_NAME + " " + MIDDLE_NAME + " " + LAST_NAME);
        }
    }

    // ================================================================================================
    // The populated-but-never-written group [L217-L220, L560] and the never-set template [L161]
    // ================================================================================================

    @Nested
    @DisplayName("output hygiene: what the legacy populates and never writes stays out of the output")
    class NeverWrittenContent {

        @Test
        @DisplayName("the staging group the source fills and never writes appears NOWHERE in either "
                + "output stream")
        void theStagingGroupNeverReachesTheOutput() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            final StatementGenerationService.StatementRun run = run();

            assertThat(run.htmlRecords()).doesNotContain(NEVER_WRITTEN_NAME_GROUP_IMAGE);
            assertThat(run.statementRecords()).doesNotContain(NEVER_WRITTEN_NAME_GROUP_IMAGE);
            // Its distinguishing feature is the absence of a closing tag: the group pairs its literal
            // with the whole padded name field and nothing else.
            assertThat(run.htmlRecords())
                    .filteredOn(record -> record.startsWith("<p style=\"font-size:16px\">"))
                    .isNotEmpty()
                    .allSatisfy(record -> assertThat(record).contains("</p>"));
        }

        @Test
        @DisplayName("the plain table-cell opener the condition-name list declares is never set by any "
                + "paragraph, so it must appear in neither stream")
        void theUnusedCellOpenerIsNeverEmitted() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            final StatementGenerationService.StatementRun run = run();

            assertThat(run.htmlRecords()).doesNotContain(markup(MARKUP_CELL_OPEN_UNUSED));
            assertThat(run.htmlRecords()).contains(markup(MARKUP_CELL_CLOSE));
        }

        @Test
        @DisplayName("no regulated customer identifier reaches either stream, so an absent national "
                + "identifier has no output position to render")
        void noRegulatedIdentifierReachesTheOutput() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            final StatementGenerationService.StatementRun run = run();

            assertThat(run.statementRecords())
                    .noneSatisfy(record -> assertThat(record).contains(PROTECTED_ENVELOPE_PREFIX));
            assertThat(run.htmlRecords())
                    .noneSatisfy(record -> assertThat(record).contains(PROTECTED_ENVELOPE_PREFIX));
        }
    }

    // ================================================================================================
    // The 13 call sites - the parameter object, its blanked payload and its keys
    // ================================================================================================

    @Nested
    @DisplayName("parameter-object hygiene: a blanked payload on every call, and the keys each read needs")
    class PayloadHygiene {

        /**
         * The ordered file operations one statement over one card and three transactions performs.
         *
         * <p>Sixteen calls across the member's thirteen call sites: the transaction-file read site is
         * reached once to prime and once per loop iteration, and the cross-reference read site once per
         * record plus once to observe exhaustion.
         */
        private static final List<String> EXPECTED_CALL_SEQUENCE = List.of(
                "TRNXFILE/O", "TRNXFILE/R", "TRNXFILE/R", "TRNXFILE/R", "TRNXFILE/R",
                "XREFFILE/O", "CUSTFILE/O", "ACCTFILE/O",
                "XREFFILE/R", "CUSTFILE/K", "ACCTFILE/K", "XREFFILE/R",
                "TRNXFILE/C", "XREFFILE/C", "CUSTFILE/C", "ACCTFILE/C");

        @Test
        @DisplayName("the payload is blank at the START of every call, so no image can leak from one "
                + "read into the next")
        void thePayloadIsBlankAtTheStartOfEveryCall() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            run();

            final ArgumentCaptor<StatementFileRequest> requests =
                    ArgumentCaptor.forClass(StatementFileRequest.class);
            verify(dataAccess, times(EXPECTED_CALL_SEQUENCE.size()))
                    .execute(requests.capture(), any(), any());
            assertThat(requests.getAllValues())
                    .hasSize(EXPECTED_CALL_SEQUENCE.size())
                    .allSatisfy(request -> assertThat(request.payload())
                            .isEqualTo(" ".repeat(PAYLOAD_FIELD_WIDTH)));
        }

        @Test
        @DisplayName("the ordered DD name and operation of every call match the source's own sequence, "
                + "and all thirteen call sites are reached")
        void theOrderedCallSequenceMatchesTheSource() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            run();

            assertThat(script.ddNamesAndOperations())
                    .containsExactlyElementsOf(EXPECTED_CALL_SEQUENCE);
            // Twelve distinct DD-and-operation pairs cover the thirteen call sites, because the
            // transaction-file read is performed at two of them - the priming read and the loop read.
            assertThat(script.ddNamesAndOperations()).containsOnly("TRNXFILE/O", "TRNXFILE/R",
                    "TRNXFILE/C", "XREFFILE/O", "XREFFILE/R", "XREFFILE/C", "CUSTFILE/O",
                    "CUSTFILE/K", "CUSTFILE/C", "ACCTFILE/O", "ACCTFILE/K", "ACCTFILE/C");
        }

        @Test
        @DisplayName("neither declared-but-dead operation is ever named, and every operation used is "
                + "one of the four the paragraphs perform")
        void neitherDeadOperationIsEverNamed() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            run();

            assertThat(script.requests)
                    .isNotEmpty()
                    .noneSatisfy(request -> assertThat(
                            request.hasOperation(StatementDataAccessService.OPERATION_WRITE)).isTrue())
                    .noneSatisfy(request -> assertThat(
                            request.hasOperation(StatementDataAccessService.OPERATION_REWRITE))
                            .isTrue())
                    .allSatisfy(request -> assertThat(request.operation())
                            .isIn(StatementDataAccessService.OPERATION_OPEN,
                                    StatementDataAccessService.OPERATION_CLOSE,
                                    StatementDataAccessService.OPERATION_READ,
                                    StatementDataAccessService.OPERATION_READ_KEYED));
        }

        @Test
        @DisplayName("2000-CUSTFILE-GET [L372-L374] and 3000-ACCTFILE-GET [L396-L398]: each keyed read "
                + "carries its own key at its own runtime length, and no other call carries a key")
        void eachKeyedReadCarriesItsOwnKeyAndRuntimeLength() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            run();

            final List<StatementFileRequest> keyedCustomerReads = new ArrayList<>();
            final List<StatementFileRequest> keyedAccountReads = new ArrayList<>();
            final List<StatementFileRequest> otherCalls = new ArrayList<>();
            for (final StatementFileRequest request : script.requests) {
                if (request.hasOperation(StatementDataAccessService.OPERATION_READ_KEYED)
                        && request.hasDdName(StatementDataAccessService.DD_CUSTFILE)) {
                    keyedCustomerReads.add(request);
                } else if (request.hasOperation(StatementDataAccessService.OPERATION_READ_KEYED)
                        && request.hasDdName(StatementDataAccessService.DD_ACCTFILE)) {
                    keyedAccountReads.add(request);
                } else {
                    otherCalls.add(request);
                }
            }

            assertThat(keyedCustomerReads).hasSize(1);
            assertThat(keyedCustomerReads.get(0).key()).startsWith(CUSTOMER_ID)
                    .hasSize(KEY_FIELD_WIDTH);
            assertThat(keyedCustomerReads.get(0).keyLength()).isEqualTo(CUSTOMER_ID.length());
            assertThat(keyedAccountReads).hasSize(1);
            assertThat(keyedAccountReads.get(0).key()).startsWith(ACCOUNT_ID)
                    .hasSize(KEY_FIELD_WIDTH);
            assertThat(keyedAccountReads.get(0).keyLength()).isEqualTo(ACCOUNT_ID.length());
            assertThat(otherCalls)
                    .isNotEmpty()
                    .allSatisfy(request -> assertThat(request.key())
                            .isEqualTo(" ".repeat(KEY_FIELD_WIDTH)))
                    .allSatisfy(request -> assertThat(request.keyLength()).isZero());
        }

        @Test
        @DisplayName("the run acquires exactly one cross-reference walk, before any call, and threads "
                + "that same walk and the same transaction snapshot through all sixteen")
        void oneWalkAndOneSnapshotAreThreadedThroughEveryCall() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            run();

            final InOrder ordered = inOrder(dataAccess);
            ordered.verify(dataAccess).openCrossReferenceSource();
            ordered.verify(dataAccess, times(EXPECTED_CALL_SEQUENCE.size()))
                    .execute(any(), any(), any());
            assertThat(script.walksSeen).isNotEmpty().containsOnly(crossReferenceWalk);
            assertThat(script.snapshotsSeen).isNotEmpty().containsOnly(transactionSource);
            verify(dataAccess, times(1)).openCrossReferenceSource();
        }

        @Test
        @DisplayName("the return code travels INTO every call, zeroed at eleven sites and deliberately "
                + "carried live into the two transaction-file reads")
        void theReturnCodeTravelsIntoEveryCall() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            run();

            assertThat(script.requests)
                    .isNotEmpty()
                    .allSatisfy(request -> assertThat(request.returnCode())
                            .hasSize(RETURN_CODE_FIELD_WIDTH));
            // Every call of this scenario succeeds, so the live value the two transaction reads inherit
            // is the success code rather than a stale failure.
            assertThat(script.requests)
                    .allSatisfy(request -> assertThat(request.returnCode())
                            .isEqualTo(STATUS_SUCCESS));
        }
    }

    // ================================================================================================
    // 4000-TRNXFILE-GET [L429] - the member's ONLY monetary operation
    // ================================================================================================

    @Nested
    @DisplayName("monetary accumulation: one addition, exact decimals at scale two, nothing else")
    class MonetaryAccumulation {

        @Test
        @DisplayName("the total is the exact sum of the tabulated amounts, at scale two, matching a "
                + "hand-computed value")
        void theTotalIsTheExactSumOfTheTabulatedAmounts() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            final StatementGenerationService.StatementRun run = run();

            BigDecimal accumulated = new BigDecimal("0.00");
            for (final StatementLineSummary summary : run.transactionSummaries()) {
                accumulated = accumulated.add(summary.amount());
            }
            assertThat(accumulated).isEqualTo(EXPECTED_TOTAL);
            assertThat(accumulated.scale()).isEqualTo(2);
            // And the emitted line carries the same value under the zero-suppressed mask.
            final List<String> records = run.statementRecords();
            assertThat(records.get(records.size() - 2)).isEqualTo(EXPECTED_TOTAL_LINE);
        }

        @Test
        @DisplayName("a negative amount reaches both the detail line's trailing-minus position and the "
                + "total, which is therefore lower than the two purchases alone")
        void aNegativeAmountReachesTheMaskAndLowersTheTotal() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            final StatementGenerationService.StatementRun run = run();

            assertThat(run.statementRecords().get(18)).isEqualTo(EXPECTED_THIRD_DETAIL_LINE);
            assertThat(EXPECTED_THIRD_DETAIL_LINE).endsWith("-");
            assertThat(run.transactionSummaries()).hasSize(3);
            assertThat(run.transactionSummaries().get(2).amount())
                    .isEqualTo(THIRD_TRANSACTION_AMOUNT);
            assertThat(THIRD_TRANSACTION_AMOUNT.signum()).isNegative();
            // The two purchases alone would total 1739.33; the return brings it to 1714.33.
            assertThat(EXPECTED_TOTAL).isEqualTo(new BigDecimal("1714.33"))
                    .isNotEqualTo(new BigDecimal("1739.33"));
        }

        @Test
        @DisplayName("every amount crosses the boundary at scale two and unchanged, so no scaling, "
                + "rounding, multiplication, division or percentage is applied anywhere")
        void everyAmountCrossesUnchangedAtScaleTwo() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            final StatementGenerationService.StatementRun run = run();

            assertThat(run.transactionSummaries())
                    .extracting(StatementLineSummary::amount)
                    .containsExactly(FIRST_TRANSACTION_AMOUNT, SECOND_TRANSACTION_AMOUNT,
                            THIRD_TRANSACTION_AMOUNT);
            assertThat(run.transactionSummaries())
                    .allSatisfy(summary -> assertThat(summary.amount().scale()).isEqualTo(2));
            // The truncating policy the estate implies - it declares no rounding clause anywhere - is
            // the one the shared fixtures encode, and no amount here needs it because every value
            // already carries exactly two decimals.
            assertThat(TestDataFactory.MONETARY_SCALE).isEqualTo(2);
            assertThat(TestDataFactory.MONETARY_ROUNDING).isEqualTo(RoundingMode.DOWN);
        }

        @Test
        @DisplayName("the balance is rendered by the mask that does NOT suppress leading zeros, which "
                + "is a different picture from the transaction and total mask")
        void theBalanceUsesTheUnsuppressedMask() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            final List<String> records = run().statementRecords();

            assertThat(records.get(9)).isEqualTo(EXPECTED_CURRENT_BALANCE_LINE);
            assertThat(MASK_BALANCE_UNSUPPRESSED).startsWith("0").hasSize(13);
            assertThat(MASK_FIRST_AMOUNT).startsWith(" ").hasSize(13);
            assertThat(MASK_BALANCE_UNSUPPRESSED).isNotEqualTo(MASK_FIRST_AMOUNT);
        }

        @Test
        @DisplayName("a card that tabulates nothing still totals zero under the zero-suppressed mask, "
                + "whose nine integer positions are blank and whose decimal point is not")
        void aCardThatTabulatesNothingTotalsZero() {
            installFileHandler();
            script.withTransactions(List.of(transactionWorkRecordForCard(CARD_NUMBER,
                            FIRST_TRANSACTION_ID, FIRST_TRANSACTION_DESCRIPTION,
                            FIRST_TRANSACTION_AMOUNT,
                            TestDataFactory.BLANK_PROCESSING_TIMESTAMP)))
                    .withCrossReferenceRecords(List.of(crossReferenceImage(LATER_CARD_NUMBER)));

            final StatementGenerationService.StatementRun run = run();

            assertThat(run.statementRecords())
                    .hasSize(FIXED_STATEMENT_RECORDS_PER_STATEMENT)
                    .containsExactlyElementsOf(
                            expectedStatementRecords(List.of(), EXPECTED_ZERO_TOTAL_LINE));
            assertThat(run.transactionSummaries()).isEmpty();
            assertThat(MASK_ZERO_AMOUNT).isEqualTo(" ".repeat(9) + ".00" + " ");
        }
    }

    // ================================================================================================
    // The invariant markup templates, and the reconciliation the opening table tag requires
    // ================================================================================================

    @Nested
    @DisplayName("invariant markup: the 34 declared templates, and the double space that is genuine")
    class MarkupTemplateReconciliation {

        @Test
        @DisplayName("the opening table tag carries TWO adjacent spaces after the element name and is "
                + "well formed - neither collapsed to one space nor a truncated tag")
        void theOpeningTableTagCarriesADoubleSpaceAndIsWellFormed() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            final String tableTag = run().htmlRecords().get(7);

            assertThat(tableTag).isEqualTo(markup(MARKUP_TABLE_OPEN));
            assertThat(encodedLength(tableTag)).isEqualTo(MARKUP_RECORD_WIDTH);
            // Two spaces, exactly where the source's continued literal puts them.
            assertThat(tableTag).startsWith("<table  align=");
            assertThat(tableTag).doesNotStartWith("<table align=");
            // Well formed: the element carries all three attributes and closes with a single '>'.
            assertThat(MARKUP_TABLE_OPEN).endsWith("sans-serif;\">")
                    .contains("frame=\"box\"")
                    .contains("style=\"width:70%; font:12px Segoe UI,sans-serif;\"");
            // And the collapsed reading, declared so the inequality is explicit.
            assertThat(tableTag).isNotEqualTo(markup("<table align=\"center\" frame=\"box\" "
                    + "style=\"width:70%; font:12px Segoe UI,sans-serif;\">"));
        }

        @Test
        @DisplayName("thirty-four invariant templates are declared, all distinct, each fitting inside "
                + "the markup record width")
        void thirtyFourDistinctTemplatesAreDeclared() {
            assertThat(DECLARED_MARKUP_TEMPLATES)
                    .hasSize(DECLARED_MARKUP_TEMPLATE_COUNT)
                    .doesNotHaveDuplicates()
                    .allSatisfy(template -> assertThat(encodedLength(template))
                            .isLessThanOrEqualTo(MARKUP_RECORD_WIDTH));
        }

        @Test
        @DisplayName("thirty-three of the thirty-four are emitted at exactly 100 bytes, and only the "
                + "never-set cell opener is absent")
        void thirtyThreeTemplatesAreEmittedAtTheRecordWidth() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            final List<String> records = run().htmlRecords();

            final List<String> emitted = new ArrayList<>();
            for (final String template : DECLARED_MARKUP_TEMPLATES) {
                if (records.contains(markup(template))) {
                    emitted.add(template);
                }
            }
            assertThat(emitted).hasSize(DECLARED_MARKUP_TEMPLATE_COUNT - 1);
            assertThat(emitted).doesNotContain(MARKUP_CELL_OPEN_UNUSED);
            assertThat(records).allSatisfy(record -> assertThat(encodedLength(record))
                    .isEqualTo(MARKUP_RECORD_WIDTH));
        }

        @Test
        @DisplayName("the two heading paragraphs the source spells differently from their plain "
                + "counterparts are emitted with their own wording")
        void theMarkupHeadingsKeepTheirOwnWording() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            final List<String> records = run().htmlRecords();

            // The plain statement heads these blocks 'Basic Details' and 'TRANSACTION SUMMARY ';
            // the markup uses 'Basic Details' and 'Transaction Summary'. Neither may be derived
            // from the other.
            assertThat(records).contains(markup(MARKUP_BASIC_DETAILS_HEADING));
            assertThat(records).contains(markup(MARKUP_TRANSACTION_SUMMARY_HEADING));
            assertThat(MARKUP_TRANSACTION_SUMMARY_HEADING).contains("Transaction Summary");
            assertThat(EXPECTED_TRANSACTION_SUMMARY_HEADING).contains("TRANSACTION SUMMARY ");
        }
    }

    // ================================================================================================
    // The two 26-character timestamp forms, which must never be unified
    // ================================================================================================

    @Nested
    @DisplayName("timestamps: 26 bytes each, two forms, the reprojection's truncation preserved")
    class TimestampHandling {

        @Test
        @DisplayName("the origination timestamp survives the reprojection whole, at exactly 26 encoded "
                + "bytes")
        void theOriginationTimestampSurvivesWholeAtTwentySixBytes() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            final StatementGenerationService.StatementRun run = run();

            assertThat(run.transactionSummaries())
                    .isNotEmpty()
                    .allSatisfy(summary -> {
                        assertThat(summary.originationTimestamp())
                                .isEqualTo(TestDataFactory.SEEDED_ORIGINAL_TIMESTAMP);
                        assertThat(encodedLength(summary.originationTimestamp()))
                                .isEqualTo(TIMESTAMP_WIDTH);
                    });
        }

        @Test
        @DisplayName("a stored all-blank processing timestamp renders as 26 spaces, never as null and "
                + "never as an empty value")
        void anAllBlankProcessingTimestampRendersAsTwentySixSpaces() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            final StatementGenerationService.StatementRun run = run();

            assertThat(run.transactionSummaries())
                    .isNotEmpty()
                    .allSatisfy(summary -> {
                        assertThat(summary.processingTimestamp()).isNotNull().isNotEmpty();
                        assertThat(summary.processingTimestamp())
                                .isEqualTo(" ".repeat(TIMESTAMP_WIDTH));
                        assertThat(encodedLength(summary.processingTimestamp()))
                                .isEqualTo(TIMESTAMP_WIDTH);
                    });
        }

        @Test
        @DisplayName("a populated processing timestamp keeps the reprojection's two-byte truncation, so "
                + "the two forms differ and must never be unified")
        void aPopulatedProcessingTimestampKeepsItsTruncation() {
            installFileHandler();
            script.withTransactions(List.of(transactionWorkRecord(FIRST_TRANSACTION_ID,
                            FIRST_TRANSACTION_DESCRIPTION, FIRST_TRANSACTION_AMOUNT,
                            TestDataFactory.SEEDED_ORIGINAL_TIMESTAMP)))
                    .withCrossReferenceRecords(List.of(crossReferenceImage(CARD_NUMBER)));

            final StatementGenerationService.StatementRun run = run();

            assertThat(run.transactionSummaries()).hasSize(1);
            final StatementLineSummary summary = run.transactionSummaries().get(0);
            // The 50-byte copied segment carries the origination stamp whole and only 24 of the
            // processing stamp's 26 bytes; the two dropped bytes come back as blanks.
            final String expectedProcessing = TestDataFactory.SEEDED_ORIGINAL_TIMESTAMP
                    .substring(0, TIMESTAMP_WIDTH - TRUNCATED_TIMESTAMP_BYTES)
                    + " ".repeat(TRUNCATED_TIMESTAMP_BYTES);
            assertThat(summary.processingTimestamp()).isEqualTo(expectedProcessing);
            assertThat(encodedLength(summary.processingTimestamp())).isEqualTo(TIMESTAMP_WIDTH);
            assertThat(summary.processingTimestamp())
                    .isNotEqualTo(summary.originationTimestamp());
            assertThat(summary.originationTimestamp())
                    .isEqualTo(TestDataFactory.SEEDED_ORIGINAL_TIMESTAMP);
        }

        @Test
        @DisplayName("the two record timestamps sit at their declared offsets in the canonical image, "
                + "26 bytes apart, which is what the reprojection's segment width relies on")
        void theTwoTimestampsSitAtTheirDeclaredOffsets() {
            assertThat(PROCESSING_TIMESTAMP_OFFSET - ORIGINATION_TIMESTAMP_OFFSET)
                    .isEqualTo(TIMESTAMP_WIDTH);
            assertThat(ORIGINATION_TIMESTAMP_OFFSET).isEqualTo(278);
            assertThat(PROCESSING_TIMESTAMP_OFFSET).isEqualTo(304);
            assertThat(PROJECTED_TIMESTAMP_SEGMENT_WIDTH)
                    .isEqualTo(TIMESTAMP_WIDTH + TIMESTAMP_WIDTH - TRUNCATED_TIMESTAMP_BYTES);
        }
    }

    // ================================================================================================
    // Null and boundary input
    // ================================================================================================

    @Nested
    @DisplayName("null and boundary input: rejected early, or tolerated exactly as declared")
    class NullAndBoundaryInput {

        @Test
        @DisplayName("a null transaction snapshot is rejected before any file is touched")
        void aNullTransactionSnapshotIsRejectedBeforeAnyFileIsTouched() {
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() -> service.generate(
                    null, REGULATED_FIELD_REVEALER, REGULATED_FIELD_SEALER));

            verifyNoInteractions(dataAccess);
            verifyNoInteractions(abendService);
        }

        @Test
        @DisplayName("a null revealing or sealing operation is rejected before any file is touched")
        void aNullRegulatedFieldOperationIsRejectedBeforeAnyFileIsTouched() {
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> service.generate(transactionSource, null, REGULATED_FIELD_SEALER));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> service.generate(transactionSource, REGULATED_FIELD_REVEALER, null));

            verifyNoInteractions(dataAccess);
        }

        @Test
        @DisplayName("a file handler that reports no cross-reference walk fails fast, before the "
                + "dispatcher runs")
        void aFileHandlerThatReportsNoWalkFailsFast() {
            when(dataAccess.openCrossReferenceSource()).thenReturn(null);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(StatementGenerationServiceTest.this::run)
                    .withMessageContaining("cross-reference source");

            verify(dataAccess, times(1)).openCrossReferenceSource();
        }

        @Test
        @DisplayName("a sealing operation that drops a regulated identifier is refused, so a customer "
                + "record can never be built with one silently missing")
        void aSealingOperationThatDropsAValueIsRefused() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(
                    () -> service.generate(transactionSource, REGULATED_FIELD_REVEALER,
                            DROPPING_SEALER));
        }

        @Test
        @DisplayName("the national identifier is the only nullable column, is absent on every seeded "
                + "row, and its absence is tolerated end to end")
        void anAbsentNationalIdentifierIsTolerated() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            final StatementGenerationService.StatementRun run = run();

            // The seeded entity shape: the national identifier is absent while the
            // government-issued identifier is not.
            final Customer seeded = TestDataFactory.customer().build();
            assertThat(seeded.getCustSsn()).isNull();
            assertThat(seeded.getGovtIssuedId()).isNotNull();
            // And a run over that shape produces its statement with no failure and nothing to render
            // for the absent value.
            assertThat(run.statementsWritten()).isEqualTo(1);
            assertThat(run.statementRecords()).isNotEmpty();
            assertThat(run.statementRecords()).containsExactlyElementsOf(
                    expectedStatementRecords(expectedThreeDetailLines(), EXPECTED_TOTAL_LINE));
        }

        @Test
        @DisplayName("an exhausted cross-reference walk produces no statement at all, and the four "
                + "closes still run")
        void anExhaustedWalkProducesNoStatementAndStillCloses() {
            installFileHandler();
            script.withTransactions(tabulatedTransactions(1)).withCrossReferenceRecords(List.of());

            final StatementGenerationService.StatementRun run = run();

            assertThat(run.statementsWritten()).isZero();
            assertThat(run.statementRecords()).isEmpty();
            assertThat(run.htmlRecords()).isEmpty();
            assertThat(script.ddNamesAndOperations()).endsWith("TRNXFILE/C", "XREFFILE/C",
                    "CUSTFILE/C", "ACCTFILE/C");
        }

        @Test
        @DisplayName("4000-TRNXFILE-GET [L418-L419]: the card walk short-circuits when the tabulated "
                + "card sorts after the one the cross-reference names, so no detail line is written")
        void theCardWalkShortCircuitsOnAHigherTabulatedCard() {
            installFileHandler();
            script.withTransactions(List.of(transactionWorkRecordForCard(LATER_CARD_NUMBER,
                            FIRST_TRANSACTION_ID, FIRST_TRANSACTION_DESCRIPTION,
                            FIRST_TRANSACTION_AMOUNT,
                            TestDataFactory.BLANK_PROCESSING_TIMESTAMP)))
                    .withCrossReferenceRecords(List.of(crossReferenceImage(CARD_NUMBER)));

            final StatementGenerationService.StatementRun run = run();

            assertThat(run.transactionSummaries()).isEmpty();
            assertThat(run.statementRecords())
                    .hasSize(FIXED_STATEMENT_RECORDS_PER_STATEMENT)
                    .containsExactlyElementsOf(
                            expectedStatementRecords(List.of(), EXPECTED_ZERO_TOTAL_LINE));
        }

        @Test
        @DisplayName("more than one cross-reference record produces one whole statement each, in walk "
                + "order, with the counts accumulating across them")
        void severalCrossReferenceRecordsProduceOneStatementEach() {
            installFileHandler();
            oneStatementOfThreeTransactions().withCrossReferenceRecords(
                    List.of(crossReferenceImage(CARD_NUMBER), crossReferenceImage(CARD_NUMBER)));

            final StatementGenerationService.StatementRun run = run();

            assertThat(run.statementsWritten()).isEqualTo(2);
            assertThat(run.statementRecords())
                    .hasSize(2 * (FIXED_STATEMENT_RECORDS_PER_STATEMENT + 3));
            assertThat(run.htmlRecords()).hasSize(2 * (FIXED_MARKUP_RECORDS_PER_STATEMENT
                    + MARKUP_RECORDS_PER_TRANSACTION * 3));
            assertThat(run.transactionSummaries()).hasSize(6);
        }
    }

    // ================================================================================================
    // The published contract of the class and of what one run yields
    // ================================================================================================

    @Nested
    @DisplayName("published contract: the constants, the constructor, and the result of one run")
    class PublishedContract {

        @Test
        @DisplayName("the published constants name the legacy member and its two declared table bounds")
        void thePublishedConstantsNameTheMemberAndItsTableBounds() {
            assertThat(StatementGenerationService.PROGRAM_NAME).isEqualTo(LEGACY_PROGRAM_NAME);
            assertThat(StatementGenerationService.MAX_CARD_ENTRIES).isEqualTo(51);
            assertThat(StatementGenerationService.MAX_TRANSACTIONS_PER_CARD).isEqualTo(10);
        }

        @Test
        @DisplayName("the parameter object's declared field widths are the picture widths of the "
                + "legacy linkage area, so this suite's own width constants agree with them")
        void theParameterObjectFieldWidthsMatchTheLinkageArea() {
            assertThat(StatementDataAccessService.DD_NAME_WIDTH).isEqualTo(8);
            assertThat(StatementDataAccessService.OPERATION_WIDTH).isEqualTo(1);
            assertThat(StatementDataAccessService.RETURN_CODE_WIDTH)
                    .isEqualTo(RETURN_CODE_FIELD_WIDTH).isEqualTo(2);
            assertThat(StatementDataAccessService.KEY_WIDTH)
                    .isEqualTo(KEY_FIELD_WIDTH).isEqualTo(25);
            assertThat(StatementDataAccessService.PAYLOAD_WIDTH)
                    .isEqualTo(PAYLOAD_FIELD_WIDTH).isEqualTo(1000);
        }

        @Test
        @DisplayName("the realistic load of seven transactions on one card is handled well inside the "
                + "declared bounds, so no overflow path exists to exercise")
        void theRealisticLoadSitsWellInsideTheDeclaredBounds() {
            installFileHandler();
            script.withTransactions(tabulatedTransactions(REALISTIC_TRANSACTIONS_PER_CARD))
                    .withCrossReferenceRecords(List.of(crossReferenceImage(CARD_NUMBER)));

            final StatementGenerationService.StatementRun run = run();

            assertThat(REALISTIC_TRANSACTIONS_PER_CARD)
                    .isLessThan(StatementGenerationService.MAX_TRANSACTIONS_PER_CARD);
            assertThat(run.cardsTabulated()).isEqualTo(1)
                    .isLessThan(StatementGenerationService.MAX_CARD_ENTRIES);
            assertThat(run.transactionsTabulated()).isEqualTo(REALISTIC_TRANSACTIONS_PER_CARD);
            assertThat(run.statementRecords()).hasSize(
                    FIXED_STATEMENT_RECORDS_PER_STATEMENT + REALISTIC_TRANSACTIONS_PER_CARD);
            verifyNoInteractions(abendService);
        }

        @Test
        @DisplayName("the constructor refuses either collaborator as null, because a statement run has "
                + "no fallback for a missing file handler or a missing abort routine")
        void theConstructorRefusesEitherCollaboratorAsNull() {
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> new StatementGenerationService(null, abendService));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> new StatementGenerationService(dataAccess, null));
        }

        @Test
        @DisplayName("what one run yields is published unmodifiable, so a result cannot be altered "
                + "after the fact")
        void whatOneRunYieldsIsPublishedUnmodifiable() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            final StatementGenerationService.StatementRun run = run();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> run.statementRecords().add(EXPECTED_RULE_LINE));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> run.htmlRecords().add(markup(MARKUP_ROW_OPEN)));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> run.dispatchedPhases().add("TRNXFILE"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> run.transactionSummaries().clear());
        }

        @Test
        @DisplayName("a run result refuses a null record list, so no caller can publish one")
        void aRunResultRefusesANullRecordList() {
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> new StatementGenerationService.StatementRun(null, List.of(), List.of(),
                            List.of(), 0, 0, 0));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> new StatementGenerationService.StatementRun(List.of(), null, List.of(),
                            List.of(), 0, 0, 0));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> new StatementGenerationService.StatementRun(List.of(), List.of(), null,
                            List.of(), 0, 0, 0));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> new StatementGenerationService.StatementRun(List.of(), List.of(),
                            List.of(), null, 0, 0, 0));
        }

        @Test
        @DisplayName("the four record layouts this member reads keep their declared widths, and the "
                + "cross-reference record keeps its 36 data bytes and 14 filler bytes")
        void theFourRecordLayoutsKeepTheirDeclaredWidths() {
            assertThat(customerImage()).hasSize(CUSTOMER_RECORD_WIDTH);
            assertThat(accountImage()).hasSize(ACCOUNT_RECORD_WIDTH);
            assertThat(ACCOUNT_KEY_WIDTH + ACCOUNT_DATA_WIDTH).isEqualTo(ACCOUNT_RECORD_WIDTH);
            assertThat(crossReferenceImage(CARD_NUMBER)).hasSize(CROSS_REFERENCE_RECORD_WIDTH);
            assertThat(CROSS_REFERENCE_DATA_WIDTH + CROSS_REFERENCE_FILLER_WIDTH)
                    .isEqualTo(CROSS_REFERENCE_RECORD_WIDTH);
            assertThat(crossReferenceImage(CARD_NUMBER).substring(CROSS_REFERENCE_DATA_WIDTH))
                    .isEqualTo(" ".repeat(CROSS_REFERENCE_FILLER_WIDTH));
            assertThat(transactionWorkRecord(FIRST_TRANSACTION_ID, FIRST_TRANSACTION_DESCRIPTION,
                    FIRST_TRANSACTION_AMOUNT)).hasSize(TRANSACTION_RECORD_WIDTH);
        }

        @Test
        @DisplayName("the statement records the run yields carry the customer's non-uniformly prefixed "
                + "attributes exactly as the entity declares them")
        void theRunReadsTheCustomerAttributesTheEntityDeclares() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            final StatementGenerationService.StatementRun run = run();

            // Only three of the customer's attribute names carry the record prefix - the identifier,
            // the national identifier and the date of birth - and the name, address and phone
            // attributes do not. The statement reads the unprefixed ones.
            final Customer seeded = TestDataFactory.customer().firstName(FIRST_NAME)
                    .middleName(MIDDLE_NAME).lastName(LAST_NAME).addressLine1(ADDRESS_LINE_1)
                    .addressLine2(ADDRESS_LINE_2).creditScore(CREDIT_SCORE).build();
            assertThat(seeded.getFirstName()).isEqualTo(FIRST_NAME);
            assertThat(seeded.getAddrLine1()).isEqualTo(ADDRESS_LINE_1);
            assertThat(seeded.getFicoCreditScore()).isEqualTo(CREDIT_SCORE);
            assertThat(run.statementRecords().get(1)).contains(seeded.getFirstName());
            assertThat(run.statementRecords().get(2)).startsWith(seeded.getAddrLine1());
            assertThat(run.statementRecords().get(10)).contains(seeded.getFicoCreditScore());
        }

        @Test
        @DisplayName("the account and cross-reference the run reads resolve to the same identifiers the "
                + "statement heads itself with")
        void theAccountAndCrossReferenceResolveToTheHeadedIdentifiers() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            final StatementGenerationService.StatementRun run = run();

            final CardCrossReference crossReference = TestDataFactory.cardCrossReference()
                    .cardNumber(CARD_NUMBER).customerId(CUSTOMER_ID).accountId(ACCOUNT_ID).build();
            final Account account =
                    TestDataFactory.account().acctId(ACCOUNT_ID).currentBalance(CURRENT_BALANCE)
                            .build();
            assertThat(SensitiveValues.fingerprint(crossReference.getXrefCardNum())).isEqualTo(SensitiveValues.fingerprint(CARD_NUMBER));
            assertThat(crossReference.getXrefCustId()).isEqualTo(CUSTOMER_ID);
            assertThat(crossReference.getXrefAcctId()).isEqualTo(ACCOUNT_ID);
            assertThat(account.getAcctId()).isEqualTo(ACCOUNT_ID);
            assertThat(run.statementRecords().get(8)).contains(account.getAcctId());
            assertThat(run.htmlRecords().get(10)).contains(account.getAcctId());
        }

        @Test
        @DisplayName("a tabulated transaction crosses into the run summary byte for byte, and the "
                + "service-layer summary is a distinct type from the interface data carrier")
        void aTabulatedTransactionCrossesIntoTheSummaryByteForByte() {
            installFileHandler();
            oneStatementOfThreeTransactions();

            final StatementGenerationService.StatementRun run = run();

            final Transaction tabulated = TestDataFactory.transaction().id(FIRST_TRANSACTION_ID)
                    .description(FIRST_TRANSACTION_DESCRIPTION).amount(FIRST_TRANSACTION_AMOUNT)
                    .cardNumber(CARD_NUMBER)
                    .originalTimestamp(TestDataFactory.SEEDED_ORIGINAL_TIMESTAMP)
                    .processingTimestamp(TestDataFactory.BLANK_PROCESSING_TIMESTAMP).build();
            final StatementLineSummary summary = run.transactionSummaries().get(0);
            assertThat(summary.transactionId()).isEqualTo(tabulated.getTranId());
            assertThat(summary.cardNumber()).isEqualTo(tabulated.getTranCardNum());
            assertThat(summary.amount()).isEqualTo(tabulated.getTranAmt());
            assertThat(summary.originationTimestamp()).isEqualTo(tabulated.getTranOrigTs());
            // The description crosses as the whole 100-byte record field, untrimmed: the detail line
            // narrows it to 49 bytes for display, and the summary keeps every byte the record held.
            assertThat(summary.description())
                    .isEqualTo(FIRST_TRANSACTION_DESCRIPTION + " ".repeat(
                            TRANSACTION_DESCRIPTION_FIELD_WIDTH
                                    - FIRST_TRANSACTION_DESCRIPTION.length()))
                    .hasSize(TRANSACTION_DESCRIPTION_FIELD_WIDTH)
                    .startsWith(tabulated.getTranDesc());
            assertThat(summary.description()).startsWith(
                    EXPECTED_FIRST_DETAIL_LINE.substring(CARD_NUMBER_WIDTH + 1,
                            CARD_NUMBER_WIDTH + 1 + FIRST_TRANSACTION_DESCRIPTION.length()));

            // The interface data carrier holds the same thirteen values and remains a separate type:
            // neither is narrowed into the other, and a caller projects one onto the other explicitly.
            final StatementSummary published = new StatementSummary(summary.cardNumber(),
                    summary.transactionId(), summary.typeCode(), summary.categoryCode(),
                    summary.source(), summary.description(), summary.amount(), summary.merchantId(),
                    summary.merchantName(), summary.merchantCity(), summary.merchantZip(),
                    summary.originationTimestamp(), summary.processingTimestamp());
            assertThat(published.amount()).isEqualTo(summary.amount());
            assertThat(published.processingTimestamp()).isEqualTo(" ".repeat(TIMESTAMP_WIDTH));
            assertThat(summary).isNotInstanceOf(StatementSummary.class);
        }
    }
}
