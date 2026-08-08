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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.carddemo.domain.enums.DateFormat;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.domain.enums.ReportPeriod;
import com.carddemo.exception.JobSubmissionException;
import com.carddemo.exception.ValidationException;
import com.carddemo.util.CobolStringUtils;
import com.carddemo.util.FailureDiagnostics;
import com.carddemo.util.JclCardImageBuilder;

/**
 * The transaction-report request screen: the online half of the estate's only online-to-batch bridge.
 *
 * <p>Translation of {@code app/cbl/CORPT00C.cbl}, legacy CICS transaction {@code CR00}, 649 source
 * lines and 10 procedure-division paragraphs. Provenance: checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * <h2>Paragraph map</h2>
 * Every paragraph resolves to one named method, so the traceability matrix has one row per paragraph:
 * <ul>
 *   <li>{@code MAIN-PARA} line 163 &rarr; {@code mainPara}, reached from
 *       {@code processReportRequest}, which also carries the terminal re-arm of this
 *       transaction at lines 199 to 202;</li>
 *   <li>{@code PROCESS-ENTER-KEY} line 208 &rarr; {@code processEnterKey}, whose three period
 *       branches invoke submission at lines 238 and 255 and, for the operator-supplied range, at
 *       line 435, and whose acknowledgement is composed at line 450;</li>
 *   <li>{@code SUBMIT-JOB-TO-INTRDR} line 462 &rarr; {@code submitJobToIntrdr};</li>
 *   <li>{@code WIRTE-JOBSUB-TDQ} line 515 &rarr; {@code writeJobSubmissionTdq}. <strong>The
 *       paragraph name is misspelled in the source</strong> &mdash; row 6 of the AAP source-anomaly
 *       register. The Java method is spelled correctly and the original spelling is recorded in the
 *       traceability row so the mapping stays findable;</li>
 *   <li>{@code RETURN-TO-PREV-SCREEN} line 540 &rarr; {@code returnToPrevScreen};</li>
 *   <li>{@code SEND-TRNRPT-SCREEN} line 556 &rarr; {@code sendTrnrptScreen};</li>
 *   <li>{@code RETURN-TO-CICS} line 585 &rarr; {@code returnToCics}. This member has its own
 *       return paragraph, which the bill-payment program does not, so it gets its own method rather
 *       than being folded into the send;</li>
 *   <li>{@code RECEIVE-TRNRPT-SCREEN} line 596 &rarr; {@code receiveTrnrptScreen};</li>
 *   <li>{@code POPULATE-HEADER-INFO} line 609 &rarr; {@code populateHeaderInfo};</li>
 *   <li>{@code INITIALIZE-ALL-FIELDS} line 633 &rarr; {@code initializeAllFields}.</li>
 * </ul>
 *
 * <h2>The date-validation acceptance test is two-level, and both levels are kept</h2>
 * The operator-supplied range is checked by two invocations of the shared date-validation subprogram,
 * at lines 392 and 412, whose result block is declared at {@code app/cbl/CSUTLDTC.cbl} lines 42 to 57
 * as eighty characters and overlaid by this caller at {@code app/cbl/CORPT00C.cbl} lines 129 to 136
 * as a four-character severity, an eleven-character filler, a four-character message number and a
 * sixty-one-character remainder. The test the source applies at lines 396 to 406 and again at 416 to
 * 426 accepts a severity of {@code 0000} outright and <strong>otherwise still accepts silently when
 * the message number is the tolerated one</strong>, rejecting only when it is something else. Both
 * comparisons are four-character text comparisons, never integer comparisons, and the two levels are
 * deliberately not collapsed into one boolean: a one-level test would reject dates the legacy
 * accepts.
 *
 * <h2>The submission loop transmits the sentinel, so a complete submission is seventeen messages</h2>
 * The emitting loop at lines 496 to 508 sets its end-of-stream flag <em>before</em> writing the card
 * that raised it, so the end-of-stream card is itself transmitted and a complete submission publishes
 * exactly {@code JclCardImageBuilder.CARD_COUNT} messages. The same loop guard also tests the
 * write-error flag, so a failed publish stops the remaining cards rather than skipping one and
 * carrying on. Both consequences are contractual and are reproduced here.
 *
 * <h2>A publish failure is not fatal</h2>
 * The queue is defined {@code ERROROPTION(IGNORE)} at {@code app/csd/CARDDEMO.CSD} lines 499 to 505,
 * alongside an eighty-byte fixed unblocked record, append disposition, output-only direction and
 * open-at-initialisation. The write paragraph therefore reports the failure to the operator and
 * returns control normally: there is no abend and no re-raise. Accordingly {@code
 * JobSubmissionException} is caught and logged here and never leaves this service, and no retry,
 * backoff, timeout or delay is applied.
 *
 * <h2>What this service does not do</h2>
 * It requests a report; it does not generate one. It builds no card image &mdash; the seventeen
 * eighty-column cards and their four ten-character substitution slots belong to {@code
 * JclCardImageBuilder}. It knows no queue name, no message group and no endpoint &mdash; those
 * belong to {@code JobSubmissionService}. It touches no database, holds no transaction, performs no
 * monetary arithmetic, spawns no process and wires no abend path, because the source member is not in
 * the five-program family and carries neither an attention-key copybook nor a CICS abend handler.
 *
 * <h2>Divergences raised for the decision log</h2>
 * Every place where legacy semantics and idiomatic Java pull apart is resolved in favour of the legacy
 * and raised as a decision-log entry. The owning document is maintained elsewhere; the entries this
 * translation raises are:
 * <ol>
 *   <li>the two-level date acceptance test is kept verbatim, so a non-zero severity carrying the
 *       tolerated message number is accepted silently, exactly as the legacy accepts it;</li>
 *   <li>the declined-confirmation arm deliberately emits no message text, matching the legacy's silent
 *       rejection, rather than inventing a cancellation message;</li>
 *   <li>the end-of-stream card is transmitted, so a complete submission is exactly
 *       {@code JclCardImageBuilder.CARD_COUNT} messages;</li>
 *   <li>a publish failure stops the remaining cards and returns normally instead of aborting the
 *       request, matching the queue's ignore-on-error attribute, so the submission exception is caught
 *       and logged and never propagated;</li>
 *   <li>the misspelled queue-write paragraph name is corrected in Java, with the original spelling
 *       recorded in the traceability row;</li>
 *   <li>the month-to-date end date is derived by the legacy's first-of-next-month-minus-one-day
 *       computation rather than by a month-length helper;</li>
 *   <li>the eighty-column job image is built by the shared card builder rather than assembled here, and
 *       the thousand-slot bound is a defensive upper limit derived from an oversized redefinition and not
 *       a tuning value;</li>
 *   <li>no process invocation is used anywhere, so the module's process-execution audit count stays at
 *       zero;</li>
 *   <li>this member declares no clear-key arm, so the clear key reaches the catch-all and produces the
 *       invalid-key message; no arm the source lacks was added;</li>
 *   <li>the send paragraph ends the turn, because the legacy's send jumps to the return paragraph and
 *       that paragraph ends the task; so the <em>first</em> failed validation is the last thing that happens
 *       in the turn, with the summary message and the cursor position latched from it, and no later
 *       normalisation, validation, report-name assignment or submission runs;</li>
 *   <li>consequently the operator-supplied cascade reports at most one field failure per turn, which is
 *       the legacy's own cardinality: the six emptiness tests are an ordered evaluation that fires once,
 *       and the six range tests and the two subprogram calls each stop at their own first failure
 *       because each ends in a send;</li>
 *   <li>the numeric conversion of an argument that is not a well-formed numeric lexeme is undefined in
 *       the language, and the field is left as transmitted so the following numeric test fires.</li>
 * </ol>
 *
 * <p>This bean is a stateless singleton. Everything the legacy held in working storage lives in a
 * per-invocation state object, so concurrent turns cannot observe one another.
 *
 * @since 1.0.0
 */
@Service
public final class ReportRequestService {

    /** Diagnostic channel replacing the two {@code DISPLAY} statements at lines 210 and 529. */
    private static final Logger LOG = LoggerFactory.getLogger(ReportRequestService.class);

    // ==========================================================================================
    // Program identity, from WS-VARIABLES at lines 37 and 38
    // ==========================================================================================

    /** {@code WS-PGMNAME}, {@code PIC X(08) VALUE 'CORPT00C'} at line 37. */
    private static final String WS_PGMNAME = "CORPT00C";

    /** {@code WS-TRANID}, {@code PIC X(04) VALUE 'CR00'} at line 38. */
    private static final String WS_TRANID = "CR00";

    // ==========================================================================================
    // The 21 external-contract message literals, in source order.
    //
    // Casing and punctuation are contractual and are never normalised: the six emptiness texts
    // carry a capital-N NOT, the six range texts capitalise Month, Day and Year, and the two
    // date-validation texts use a lower-case "date". Dot counts are equally contractual.
    // ==========================================================================================

    /** Line 261. */
    private static final String MSG_START_DATE_MONTH_EMPTY = "Start Date - Month can NOT be empty...";

    /** Line 268. */
    private static final String MSG_START_DATE_DAY_EMPTY = "Start Date - Day can NOT be empty...";

    /** Line 275. */
    private static final String MSG_START_DATE_YEAR_EMPTY = "Start Date - Year can NOT be empty...";

    /** Line 282. */
    private static final String MSG_END_DATE_MONTH_EMPTY = "End Date - Month can NOT be empty...";

    /** Line 289. */
    private static final String MSG_END_DATE_DAY_EMPTY = "End Date - Day can NOT be empty...";

    /** Line 296. */
    private static final String MSG_END_DATE_YEAR_EMPTY = "End Date - Year can NOT be empty...";

    /** Line 331. */
    private static final String MSG_START_DATE_MONTH_INVALID = "Start Date - Not a valid Month...";

    /** Line 340. */
    private static final String MSG_START_DATE_DAY_INVALID = "Start Date - Not a valid Day...";

    /** Line 348. */
    private static final String MSG_START_DATE_YEAR_INVALID = "Start Date - Not a valid Year...";

    /** Line 357. */
    private static final String MSG_END_DATE_MONTH_INVALID = "End Date - Not a valid Month...";

    /** Line 366. */
    private static final String MSG_END_DATE_DAY_INVALID = "End Date - Not a valid Day...";

    /** Line 374. */
    private static final String MSG_END_DATE_YEAR_INVALID = "End Date - Not a valid Year...";

    /** Line 400. Lower-case {@code date} is deliberate and differs from the range texts above. */
    private static final String MSG_START_DATE_INVALID = "Start Date - Not a valid date...";

    /** Line 420. Lower-case {@code date} is deliberate. */
    private static final String MSG_END_DATE_INVALID = "End Date - Not a valid date...";

    /** Line 438, the catch-all arm when no report type was marked. */
    private static final String MSG_SELECT_REPORT_TYPE = "Select a report type to print report...";

    /**
     * Line 450, appended to the space-delimited report name. Both spaces are contractual: the
     * leading one separates it from the report name and the one before the three dots is part of
     * the literal.
     */
    private static final String FRAGMENT_SUBMITTED_SUFFIX = " report submitted for printing ...";

    /** Line 466, the head of the confirmation prompt. */
    private static final String FRAGMENT_CONFIRM_PROMPT_PREFIX = "Please confirm to print the ";

    /** Line 469, the tail of the confirmation prompt, appended after the report name. */
    private static final String FRAGMENT_CONFIRM_PROMPT_SUFFIX = " report...";

    /** Line 486, the opening quotation mark of the unrecognised-confirmation text. */
    private static final String FRAGMENT_INVALID_CONFIRM_PREFIX = "\"";

    /** Line 488, the tail of the unrecognised-confirmation text. */
    private static final String FRAGMENT_INVALID_CONFIRM_SUFFIX =
            "\" is not a valid value to confirm...";

    /**
     * Line 531. Three trailing dots, exactly as the source writes them.
     *
     * <p>Written out rather than referenced, so the text a reviewer reads here is the text the
     * operator sees. It is character for character the frozen text {@code
     * JobSubmissionException.DEFAULT_MESSAGE} publishes, because both render the same source literal;
     * neither may be changed without the other.
     */
    private static final String MSG_UNABLE_TO_WRITE_TDQ = "Unable to Write TDQ (JOBS)...";

    // ==========================================================================================
    // The two four-character acceptance-test literals, lines 396 and 399 (and identically 416, 419)
    // ==========================================================================================

    /**
     * The severity that accepts outright, compared as four characters at lines 396 and 416. Never
     * parsed: the source compares text and so does this.
     */
    private static final String ACCEPTED_SEVERITY_CODE = "0000";

    /**
     * The message number that accepts <em>despite</em> a non-zero severity, compared as four
     * characters at lines 399 and 419. This is the second level of the acceptance test and the
     * reason it cannot be collapsed into one boolean.
     */
    private static final String TOLERATED_MESSAGE_NUMBER = "2513";

    // ==========================================================================================
    // Screen field identifiers and widths, from app/cpy-bms/CORPT00.CPY
    // ==========================================================================================

    /** {@code MONTHLY}, the field whose length item the member makes negative to place the cursor. */
    private static final String FIELD_MONTHLY = "MONTHLY";

    /** {@code SDTMM}, the start-month field and the cursor target at lines 264, 334 and 403. */
    private static final String FIELD_START_MONTH = "SDTMM";

    /** {@code SDTDD}, the start-day field and the cursor target at lines 271 and 343. */
    private static final String FIELD_START_DAY = "SDTDD";

    /** {@code SDTYYYY}, the start-year field and the cursor target at lines 278 and 351. */
    private static final String FIELD_START_YEAR = "SDTYYYY";

    /** {@code EDTMM}, the end-month field and the cursor target at lines 285, 360 and 423. */
    private static final String FIELD_END_MONTH = "EDTMM";

    /** {@code EDTDD}, the end-day field and the cursor target at lines 292 and 369. */
    private static final String FIELD_END_DAY = "EDTDD";

    /** {@code EDTYYYY}, the end-year field and the cursor target at lines 299 and 377. */
    private static final String FIELD_END_YEAR = "EDTYYYY";

    /** {@code CONFIRM}, the confirmation field and the cursor target at lines 472 and 492. */
    private static final String FIELD_CONFIRM = "CONFIRM";

    /** Property name reported for a whole-date failure at lines 400 and 420. */
    private static final String PROPERTY_START_DATE = "startDate";

    /** Property name reported for a whole-date failure at line 420. */
    private static final String PROPERTY_END_DATE = "endDate";

    /** Property name reported when the catch-all arm at line 438 fires. */
    private static final String PROPERTY_REPORT_TYPE = "reportType";

    /** Property name of the start-month screen part. */
    private static final String PROPERTY_START_MONTH = "startMonth";

    /** Property name of the start-day screen part. */
    private static final String PROPERTY_START_DAY = "startDay";

    /** Property name of the start-year screen part. */
    private static final String PROPERTY_START_YEAR = "startYear";

    /** Property name of the end-month screen part. */
    private static final String PROPERTY_END_MONTH = "endMonth";

    /** Property name of the end-day screen part. */
    private static final String PROPERTY_END_DAY = "endDay";

    /** Property name of the end-year screen part. */
    private static final String PROPERTY_END_YEAR = "endYear";

    /** Property name of the confirmation field. */
    private static final String PROPERTY_CONFIRM = "confirm";

    /** Width of the three report-type markers, {@code PIC X(1)} at symbolic-map lines 60, 66, 72. */
    private static final int SELECTION_WIDTH = 1;

    /** Width of a month or day screen part, {@code PIC X(2)}. */
    private static final int MONTH_DAY_WIDTH = 2;

    /** Width of a year screen part, {@code PIC X(4)}. */
    private static final int YEAR_WIDTH = 4;

    /** Width of the confirmation field, {@code PIC X(1)} at symbolic-map line 114. */
    private static final int CONFIRM_WIDTH = 1;

    /** Width of the outbound message field, {@code ERRMSGO PIC X(78)} at symbolic-map line 120. */
    private static final int ERROR_MESSAGE_WIDTH = 78;

    /** Width of {@code WS-REPORT-NAME}, {@code PIC X(10)} at line 58. */
    private static final int REPORT_NAME_WIDTH = 10;

    /** Width of the two-digit header parts assembled by the header paragraph. */
    private static final int HEADER_PART_WIDTH = 2;

    // ==========================================================================================
    // Range-check bounds and calendar constants
    // ==========================================================================================

    /** Upper bound compared at lines 330 and 356, as the two-character literal the source uses. */
    private static final String MONTH_UPPER_BOUND = "12";

    /** Upper bound compared at lines 339 and 365, as the two-character literal the source uses. */
    private static final String DAY_UPPER_BOUND = "31";

    /** The first month, moved at line 227 after the roll and at lines 245 and 246. */
    private static final int FIRST_MONTH = 1;

    /** The last month, the threshold compared at line 225. */
    private static final int LAST_MONTH = 12;

    /** The first day of a month, moved at lines 219, 223 and 246. */
    private static final int FIRST_DAY_OF_MONTH = 1;

    /** The two-character month the yearly range ends in, moved at line 250. */
    private static final String YEARLY_END_MONTH = "12";

    /** The two-character day the yearly range ends on, moved at line 251. */
    private static final String YEARLY_END_DAY = "31";

    /** The two-character day both derived ranges start on, moved at lines 219 and 246. */
    private static final String PERIOD_START_DAY = "01";

    /** The two-character month the yearly range starts in, moved at line 245. */
    private static final String YEARLY_START_MONTH = "01";

    /** One day, subtracted from the integer date at line 230. */
    private static final int ONE_DAY = 1;

    /**
     * Day zero of the intrinsic integer-date scale: {@code FUNCTION INTEGER-OF-DATE} numbers days
     * from 1601-01-01 as one, so the day before that is the origin. Held as an epoch-day offset so
     * both intrinsics can be modelled by name rather than folded away.
     */
    private static final long INTEGER_DATE_ORIGIN_EPOCH_DAY =
            LocalDate.of(1600, 12, 31).toEpochDay();

    /** Zero-based offset of the two-digit year within the four-character year, {@code year(3:2)}. */
    private static final int YEAR_SHORT_FORM_OFFSET = 2;

    /*
     * The emitting loop's own two constants are deliberately NOT declared here: the one-based first
     * slot at line 498 and the card-slot ceiling that line also tests, which comes from the
     * thousand-element card-array redefinition at line 127. The whole card stream is handed to the
     * bridge in one call, so the loop, its guard, its slot index, its ceiling, the sentinel test and
     * the write-error flag all belong to the bridge, and the full-width sentinel record and the
     * predicate that compares against it belong there with them.
     *
     * Nothing about the legacy bound or the sentinel is lost: JclCardImageBuilder publishes the first
     * slot, the ceiling, the sentinel card and the card width, and the bridge applies the sentinel
     * test to a card before writing it, which is what transmits the sentinel. A second declaration
     * here would restate a rule this class does not apply, which is the shape two copies of one rule
     * drift apart in. See writeJobSubmissionTdq for why the stream is published as one submission.
     *
     * The separator between the two date slots of a submission identity is absent here for the same
     * reason: the identity is minted by the bridge, which owns the queue's composition and length
     * rules. See newSubmissionIdentity.
     */

    /** Digest used to turn an opaque retry token into a bounded printable identity component. */
    private static final String SUBMISSION_TOKEN_DIGEST_ALGORITHM = "SHA-256";

    /** Accepted affirmative confirmation, upper case, compared at line 478. */
    private static final String CONFIRM_YES_UPPER = "Y";

    /** Accepted affirmative confirmation, lower case, compared at line 478. */
    private static final String CONFIRM_YES_LOWER = "y";

    /** Accepted negative confirmation, upper case, compared at line 480. */
    private static final String CONFIRM_NO_UPPER = "N";

    /** Accepted negative confirmation, lower case, compared at line 480. */
    private static final String CONFIRM_NO_LOWER = "n";

    // ==========================================================================================
    // Character and text constants
    // ==========================================================================================

    /** The space character, the fill of every alphanumeric screen field. */
    private static final char SPACE = ' ';

    /** Separator between the two date slots and the stable logical-request digest. */
    private static final String SUBMISSION_IDENTITY_SEPARATOR = "_";

    /**
     * Separator between the operator and the logical-request token inside the submission digest.
     *
     * <p>A unit separator, chosen because it cannot occur in either value: an operator identifier is the
     * eight-character key of a user record and a token is header text the request contract bounds to
     * printable characters. A separator that could occur in either would let one pair of values be
     * rearranged into a different pair with the same digest, which is exactly the collision the operator
     * component exists to prevent. It never reaches the queue, because only the digest does.
     */
    private static final char SUBMISSION_DIGEST_FIELD_SEPARATOR = '\u001f';

    /**
     * Stands in for the operator when no identity was established, so the digest stays a total function.
     *
     * <p>Not reachable over the delivered boundary, which authenticates the report route; it exists for a
     * turn driven without a security chain, and it is a fixed marker rather than a blank so that "no
     * operator" is one namespace of its own rather than a value an operator could be mistaken for.
     */
    private static final String SUBMISSION_PRINCIPAL_ABSENT = "unauthenticated";

    /**
     * The low-value character. A 3270 field the terminal did not transmit arrives as low values
     * rather than as spaces, and the source tests for both in the same breath at lines 213, 239,
     * 256, 259, 464 and 503.
     */
    private static final char LOW_VALUE = '\0';

    /** The hyphen separating the parts of a ten-character date, {@code FILLER} at lines 62 and 64. */
    private static final String DATE_PART_SEPARATOR = "-";

    /** The separator of the header date, {@code FILLER VALUE '/'} in {@code app/cpy/CSDAT01Y.cpy}. */
    private static final String HEADER_DATE_SEPARATOR = "/";

    /** The separator of the header time, {@code FILLER VALUE ':'} in {@code app/cpy/CSDAT01Y.cpy}. */
    private static final String HEADER_TIME_SEPARATOR = ":";

    /** The authored form of a message field holding nothing. */
    private static final String NO_MESSAGE = "";

    /** The decimal point that separates a numeric lexeme's integer and fractional parts. */
    private static final char DECIMAL_POINT = '.';

    /** The sign a plain decimal string carries when the lexeme was negative. */
    private static final char MINUS_SIGN = '-';

    /** The digit an unsupplied numeric field normalises to. */
    private static final String ZERO_DIGIT = "0";

    // ==========================================================================================
    // Collaborators, all constructor injected
    // ==========================================================================================

    /** The shared date-validation subprogram, invoked at lines 392 and 412. */
    private final DateValidationService dateValidationService;

    /** The queue bridge that replaces the transient-data-queue write to {@code JOBS} at line 517. */
    private final JobSubmissionService jobSubmissionService;

    /** The common-message catalogue supplying the invalid-key text and the two screen titles. */
    private final MessageCatalogService messageCatalogService;

    /** The navigation rules replacing the transfer-control dispatch at lines 548 to 551. */
    private final NavigationService navigationService;

    /** The clock standing in for {@code FUNCTION CURRENT-DATE} at lines 215, 241 and 611. */
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param dateValidationService the subprogram invoked for the operator-supplied range; mandatory
     * @param jobSubmissionService  the queue bridge that publishes each card; mandatory
     * @param messageCatalogService the common-message catalogue; mandatory
     * @param navigationService     the navigation rules; mandatory
     * @param clock                 the clock the derived periods and the screen header read;
     *                              mandatory
     * @throws NullPointerException if any collaborator is {@code null}
     */
    public ReportRequestService(final DateValidationService dateValidationService,
            final JobSubmissionService jobSubmissionService,
            final MessageCatalogService messageCatalogService,
            final NavigationService navigationService,
            final Clock clock) {
        this.dateValidationService =
                Objects.requireNonNull(dateValidationService, "dateValidationService must not be null");
        this.jobSubmissionService =
                Objects.requireNonNull(jobSubmissionService, "jobSubmissionService must not be null");
        this.messageCatalogService =
                Objects.requireNonNull(messageCatalogService, "messageCatalogService must not be null");
        this.navigationService =
                Objects.requireNonNull(navigationService, "navigationService must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    // ==========================================================================================
    // The screen contract, as values
    // ==========================================================================================

    /**
     * One inbound turn of the report-request screen: the ten transmitted fields of the input map
     * {@code CORPT0AI}, the attention key that arrived, and the navigation state echoed by the
     * client in place of the legacy communication area.
     *
     * <p>Every field is carried verbatim and every field may be {@code null}, because a 3270 field
     * the terminal did not transmit arrives as low values rather than as spaces and an omitted JSON
     * component is the same state. Bounding each value to its declared screen width is the receive
     * paragraph's job, not the caller's.
     *
     * <p>The attention key arrives already decoded, including the fold of the upper program-function
     * keys onto the lower twelve, because that decoding belongs to the module's key translator. A
     * {@code null} key means none was decoded, which the dispatch treats as an unmapped key exactly
     * as the source's catch-all arm does.
     *
     * @param monthlySelection the month-to-date marker, {@code MONTHLYI}, tested first at line 213
     * @param yearlySelection  the year-to-date marker, {@code YEARLYI}, tested second at line 239
     * @param customSelection  the operator-range marker, {@code CUSTOMI}, tested third at line 256
     * @param startMonth       {@code SDTMMI}, the start month
     * @param startDay         {@code SDTDDI}, the start day
     * @param startYear        {@code SDTYYYYI}, the start year
     * @param endMonth         {@code EDTMMI}, the end month
     * @param endDay           {@code EDTDDI}, the end day
     * @param endYear          {@code EDTYYYYI}, the end year
     * @param confirm          {@code CONFIRMI}, the confirmation field the gate at line 464 reads
     * @param keyAction        the decoded attention key, or {@code null} when none was decoded
     * @param navigationContext the echoed navigation state, or {@code null} for a turn carrying none
     */
    public record ReportScreenInput(String monthlySelection,
                                    String yearlySelection,
                                    String customSelection,
                                    String startMonth,
                                    String startDay,
                                    String startYear,
                                    String endMonth,
                                    String endDay,
                                    String endYear,
                                    String confirm,
                                    KeyAction keyAction,
                                    ConversationState navigationContext) {
    }

    /**
     * The screen header the header paragraph populates at lines 609 to 628.
     *
     * <p>The two titles keep the catalogue's contractual widths and the outbound message field keeps
     * the {@value #ERROR_MESSAGE_WIDTH}-character width of {@code ERRMSGO}, because the move at line
     * 560 truncates the eighty-character work field into it. Nothing here is trimmed.
     *
     * @param title01         {@code CCDA-TITLE01}, moved at line 613
     * @param title02         {@code CCDA-TITLE02}, moved at line 614
     * @param transactionName the transaction identifier, moved at line 615
     * @param programName     the program name, moved at line 616
     * @param currentDate     the header date as {@code MM/DD/YY}, assembled at lines 618 to 622
     * @param currentTime     the header time as {@code HH:MM:SS}, assembled at lines 624 to 628
     * @param errorMessage    the outbound message field, {@value #ERROR_MESSAGE_WIDTH} characters,
     *                        as moved at line 560
     */
    public record ScreenHeader(String title01,
                               String title02,
                               String transactionName,
                               String programName,
                               String currentDate,
                               String currentTime,
                               String errorMessage) {
    }

    /**
     * The ten screen fields as they stand when the turn ends, each at its declared width.
     *
     * <p>This is what the operator sees echoed back. It matters because the reset paragraph at lines
     * 633 to 646 blanks all ten, so a successful submission and a declined confirmation both return
     * a cleared screen while every error path returns the values that were transmitted.
     *
     * @param monthlySelection the month-to-date marker
     * @param yearlySelection  the year-to-date marker
     * @param customSelection  the operator-range marker
     * @param startMonth       the start month, after the normalisation at lines 305 to 307
     * @param startDay         the start day, after the normalisation at lines 309 to 311
     * @param startYear        the start year, after the normalisation at lines 313 to 315
     * @param endMonth         the end month, after the normalisation at lines 317 to 319
     * @param endDay           the end day, after the normalisation at lines 321 to 323
     * @param endYear          the end year, after the normalisation at lines 325 to 327
     * @param confirm          the confirmation field
     */
    public record ScreenFields(String monthlySelection,
                               String yearlySelection,
                               String customSelection,
                               String startMonth,
                               String startDay,
                               String startYear,
                               String endMonth,
                               String endDay,
                               String endYear,
                               String confirm) {
    }

    /**
     * The outcome of one turn of the report-request screen.
     *
     * <p>This is a value, not an HTTP response: it carries no status code and no response entity,
     * because the legacy program's outcome is a screen plus a communication area and the mapping onto
     * a transport belongs to the controller.
     *
     * @param route                   the destination the turn leads to. The screen's own destination
     *                                whenever the turn re-presents the screen, since the return at
     *                                lines 199 to 202 and again at 587 to 591 re-arms this same
     *                                transaction; the resolved destination on the two transfer paths
     *                                at lines 174 and 189. Never {@code null}
     * @param navigationContext       the navigation state the turn hands back, standing in for the
     *                                communication area the return and the transfer both carry.
     *                                Never {@code null}
     * @param reArmedTransactionId    the transaction identifier the turn re-armed on the return;
     *                                empty on the two transfer paths, which transfer control instead
     *                                of returning. Never {@code null}
     * @param reportPeriod            the period the ordered evaluation resolved, or {@code null}
     *                                when the catch-all arm at line 437 fired because no report type
     *                                was marked
     * @param reportName              the report name the acknowledgement and the confirmation prompt
     *                                are composed from, bare rather than padded to the ten
     *                                characters of the legacy work field. Empty when no period
     *                                resolved. Never {@code null}
     * @param startDate               the ten-character start date, or empty when none was derived.
     *                                A ten-character string and never a calendar object, because the
     *                                parts are validated individually and the assembled form is what
     *                                crosses the contract. Never {@code null}
     * @param endDate                 the ten-character end date, or empty when none was derived.
     *                                Never {@code null}
     * @param cardsPublished          how many cards the queue accepted. Exactly {@code
     *                                JclCardImageBuilder.CARD_COUNT} on a complete submission,
     *                                because the end-of-stream card is itself transmitted; fewer when
     *                                a publish failed; zero when the confirmation gate blocked the
     *                                submission or no submission was attempted
     * @param submissionToken         the opaque logical-request token a caller repeats when retrying
     *                                this submission. Minted when the caller supplied none and returned
     *                                unchanged so a subsequent retry can reuse it
     * @param confirmationBlocked     {@code true} when the confirmation gate at lines 464 to 494
     *                                stopped the submission, whether because the field was blank,
     *                                because it declined, or because it held something else
     * @param message                 the summary message, byte-exact and authored rather than padded.
     *                                When more than one check failed this is the <em>first</em>
     *                                failure's text, which is the text the legacy screen showed.
     *                                Empty when there is nothing to say &mdash; notably on the
     *                                declined-confirmation path, which sets no text at all. Never
     *                                {@code null}
     * @param messageHighlightedGreen {@code true} only where the source recolours the message field
     *                                at line 448, which is the successful-submission path
     * @param focusField              the screen field the cursor is positioned on, from the
     *                                corresponding negative length item the source writes for that
     *                                field. Never {@code null}
     * @param errorFlag               the state of {@code WS-ERR-FLG}. The explicit flag rather than
     *                                an inference from the message, because the declined-confirmation
     *                                path raises the flag and sets no message
     * @param fieldErrors             one entry per field the turn faulted, in the order the source
     *                                checks them, distinguishing a field that was not supplied from
     *                                one supplied wrongly. Unmodifiable and never {@code null}; empty
     *                                on a first entry, because the source's re-enter gate means field
     *                                detail can only arise on a re-submission
     * @param header                  the screen header, as the header paragraph populated it
     * @param screen                  the ten screen fields as the turn leaves them
     */
    public record ReportRequestResult(NavigationService.Route route,
                                      ConversationState navigationContext,
                                      String reArmedTransactionId,
                                      ReportPeriod reportPeriod,
                                      String reportName,
                                      String startDate,
                                      String endDate,
                                      int cardsPublished,
                                      String submissionToken,
                                      boolean confirmationBlocked,
                                      String message,
                                      boolean messageHighlightedGreen,
                                      String focusField,
                                      boolean errorFlag,
                                      List<ValidationException.FieldError> fieldErrors,
                                      ScreenHeader header,
                                      ScreenFields screen) {

        /**
         * Compatibility constructor for callers that describe a turn with no submission token.
         *
         * <p>Production turns use the canonical constructor and always carry a token. This overload keeps
         * manually assembled non-submission results concise and makes the absence explicit rather than
         * manufacturing a token outside the service. Every component is passed straight through and
         * {@code submissionToken} is supplied as {@code null}; the canonical constructor's contract for
         * each component is the authority and is not restated here.
         *
         * @param route                   the destination the turn leads to; never {@code null}
         * @param navigationContext       the navigation state the turn hands back; never {@code null}
         * @param reArmedTransactionId    the transaction identifier the turn re-armed, empty on a
         *                                transfer path; never {@code null}
         * @param reportPeriod            the period the ordered evaluation resolved, or {@code null}
         *                                when no report type was marked
         * @param reportName              the report name the acknowledgement is composed from; never
         *                                {@code null}
         * @param startDate               the resolved start date, or the empty field when none
         *                                resolved; never {@code null}
         * @param endDate                 the resolved end date, or the empty field when none resolved;
         *                                never {@code null}
         * @param cardsPublished          how many cards of the canonical image reached the queue, zero
         *                                on a turn that submitted nothing
         * @param confirmationBlocked     whether the turn stopped at the confirmation prompt
         * @param message                 the summary message the screen carries; never {@code null}
         * @param messageHighlightedGreen whether the message field is recoloured, which is the
         *                                successful-submission path alone
         * @param focusField              the screen field the cursor is positioned on; never
         *                                {@code null}
         * @param errorFlag               the state of the turn's error flag
         * @param fieldErrors             one entry per faulted field, in the order the source checks
         *                                them; never {@code null}
         * @param header                  the screen header as the header paragraph populated it; never
         *                                {@code null}
         * @param screen                  the ten screen fields as the turn leaves them; never
         *                                {@code null}
         */
        public ReportRequestResult(final NavigationService.Route route,
                final ConversationState navigationContext,
                final String reArmedTransactionId,
                final ReportPeriod reportPeriod,
                final String reportName,
                final String startDate,
                final String endDate,
                final int cardsPublished,
                final boolean confirmationBlocked,
                final String message,
                final boolean messageHighlightedGreen,
                final String focusField,
                final boolean errorFlag,
                final List<ValidationException.FieldError> fieldErrors,
                final ScreenHeader header,
                final ScreenFields screen) {
            this(route, navigationContext, reArmedTransactionId, reportPeriod, reportName, startDate,
                    endDate, cardsPublished, null, confirmationBlocked, message,
                    messageHighlightedGreen, focusField, errorFlag, fieldErrors, header, screen);
        }

        /**
         * Reports whether the turn published a complete card stream.
         *
         * @return {@code true} when no error was raised and every card of the canonical image
         *         reached the queue
         */
        public boolean submissionAccepted() {
            return !this.errorFlag && this.cardsPublished == JclCardImageBuilder.CARD_COUNT;
        }
    }

    // ==========================================================================================
    // Entry point: the procedure division, lines 162 to 202
    // ==========================================================================================

    /**
     * Runs one turn of the report-request screen.
     *
     * <p>This is the procedure division: it establishes the working storage the legacy declares at
     * lines 36 to 79, runs the main paragraph, and then performs the terminal return at lines 199 to
     * 202, which re-arms this transaction and hands the communication area back. Nothing is retained
     * between calls, so two concurrent turns are wholly independent.
     *
     * <p>The turn always completes normally. A failed queue write is reported through the returned
     * value rather than raised, matching the queue's ignore-on-error definition, and no exception of
     * this service's own is thrown on any path.
     *
     * @param input the transmitted screen, the decoded attention key and the echoed navigation state;
     *              must not be {@code null}
     * @return the outcome of the turn, never {@code null}
     * @throws NullPointerException if {@code input} is {@code null}
     */
    public ReportRequestResult processReportRequest(final ReportScreenInput input) {
        return processReportRequest(input, null);
    }

    /**
     * Runs one turn under an optional logical-request token, with no operator named.
     *
     * <p>A caller repeats the same token when retrying an interrupted submission. Supplying no token means
     * this is a deliberate new logical request, so a fresh opaque token is minted and returned in the
     * result. The token is not itself sent to the queue; the date range and a deterministic digest of the
     * token and the operator form the bounded submission identity from which each card's deduplication id
     * is derived.
     *
     * <p><strong>This overload names no operator, and the delivered route does not use it.</strong> The
     * submission identity is scoped to the authenticated operator so that one caller's token cannot reach
     * another caller's submission, which means a turn that names none is identified as unauthenticated -
     * one namespace of its own. It is kept for a driver that has no security context to offer; the shipped
     * controller always names the authenticated operator.
     *
     * @param input the transmitted screen, decoded attention key and echoed navigation state
     * @param retryToken token of an earlier attempt, or {@code null}/blank for a new submission
     * @return the outcome of the turn, carrying the effective token
     */
    public ReportRequestResult processReportRequest(final ReportScreenInput input,
            final String retryToken) {
        return processReportRequest(input, retryToken, null);
    }

    /**
     * Runs one turn under an optional logical-request token, on behalf of a named operator.
     *
     * <p>The operator scopes the submission identity: a retry by the same operator with the same token and
     * the same window reissues exactly the deduplication identifiers of its first attempt, so the queue
     * completes a half-published stream rather than doubling it, while the same token presented by a
     * <em>different</em> operator is a different submission and publishes its own cards. Without that
     * scoping, two operators who happened to choose the same token collapsed into one submission and the
     * second was told its request had been submitted having published nothing.
     *
     * <p>The operator must be the authenticated principal. Nothing echoed by the client is admissible here:
     * the navigation state a caller sends back is a value it can write anything into, and using it would let
     * a caller choose whose namespace to publish in - which is the defect this parameter closes, reopened
     * from the other side.
     *
     * @param input the transmitted screen, decoded attention key and echoed navigation state
     * @param retryToken token of an earlier attempt, or {@code null}/blank for a new submission
     * @param submissionPrincipal the authenticated operator this turn runs as, or {@code null} when no
     *                   identity was established; never a client-supplied value
     * @return the outcome of the turn, carrying the effective token
     */
    public ReportRequestResult processReportRequest(final ReportScreenInput input,
            final String retryToken, final String submissionPrincipal) {
        Objects.requireNonNull(input, "input must not be null");

        final TurnState state = new TurnState();
        state.submissionToken = resolveSubmissionToken(retryToken);
        state.submissionPrincipal = submissionPrincipal;
        mainPara(state, input);

        // The unconditional return at lines 199 to 202, which re-arms this transaction. The main
        // paragraph's transfer paths have already ended the turn by transferring control, and
        // re-arming is idempotent, so this reproduces the return without overriding a transfer.
        returnToCics(state);

        LOG.debug("Report-request turn complete: route={} period={} cardsPublished={} errorFlag={}"
                        + " confirmationBlocked={} fieldErrors={}",
                state.route.getRouteValue(), state.reportPeriod, state.cardsPublished,
                state.errorFlag, state.confirmationBlocked, state.fieldErrors.size());
        return state.toResult();
    }

    // ==========================================================================================
    // MAIN-PARA, line 163
    // ==========================================================================================

    /**
     * The main paragraph at line 163.
     *
     * <p>Clears the error, end-of-file and erase flags and blanks both message fields at lines 165 to
     * 170; routes a turn carrying no navigation state to sign-on at lines 172 to 174; otherwise takes
     * the echoed state at line 176 and branches on the re-enter gate at line 177. A first entry sets
     * the gate, clears the outbound map, positions the cursor on the first report-type field and
     * sends. A re-entry receives the screen and dispatches on the attention key.
     *
     * <p><strong>The attention-key evaluation at lines 184 to 195 has exactly three arms</strong>
     * &mdash; the enter key, the third program-function key, and a catch-all. This member declares
     * <em>no</em> clear-key arm, unlike several of its siblings, so the clear key reaches the
     * catch-all and produces the invalid-key message. Adding an arm the source does not have would
     * change observable output and is therefore not done; the finding is recorded in the decision
     * log.
     *
     * <p><strong>The re-enter gate is the decoration gate.</strong> Per-field detail can only be
     * produced inside the re-entry branch, which is precisely the condition the source's
     * {@code CDEMO-PGM-REENTER} test expresses, so the gate is structural here rather than a
     * repeated runtime test.
     *
     * @param state the turn's working storage
     * @param input the transmitted screen and echoed navigation state
     */
    private void mainPara(final TurnState state, final ReportScreenInput input) {
        // Lines 165 to 170 open the paragraph by clearing the error flag, clearing the end-of-file
        // flag, raising the erase flag and blanking both the message work field and the outbound
        // message field. The state is constructed in exactly that condition, and the erase flag is
        // never reset anywhere in the member, which is why the two arms of the send at lines 562 to
        // 578 differ only in a 3270 attribute with no equivalent here. The end-of-file flag belongs
        // to a file this member never opens.
        if (navigationService.isConversationStateAbsent(input.navigationContext())) {
            // A turn arriving with no communication area at all, tested at line 172, is nominated to
            // the sign-on destination at line 173. That destination is the one the navigation rules
            // hold for a turn carrying no state, so no program name is written here.
            state.context = withNominatedProgram(ConversationState.empty(),
                    navigationService.resolveAbsentContextRoute().getLegacyProgramName());
            returnToPrevScreen(state);
            return;
        }

        // Line 176 restores the whole communication area the client echoed back.
        state.context = input.navigationContext();

        if (state.context.firstEntry()) {
            // A first entry, tested against the re-entry gate at line 177: the gate is set at line
            // 178, the outbound map cleared at line 179 and the cursor positioned at line 180. The
            // outbound map is assembled from this state, which is blank on a first entry, so clearing
            // it needs no separate step.
            state.context = state.context.withReEntry();
            state.focusField = FIELD_MONTHLY;
            sendTrnrptScreen(state);
            return;
        }

        receiveTrnrptScreen(state, input);

        // The attention-key dispatch at lines 184 to 195. Clause order is preserved and the
        // catch-all maps to the default arm. A key that was never decoded reaches the same arm,
        // because an absent key is not one of the two the source names.
        switch (input.keyAction()) {
            case ENTER -> processEnterKey(state);
            case PFK03 -> {
                // Line 188 nominates this screen's own exit destination, named through the
                // navigation vocabulary rather than as a literal.
                state.context = withNominatedProgram(state.context,
                        NavigationService.Route.USER_MENU.getLegacyProgramName());
                returnToPrevScreen(state);
            }
            case null, default -> {
                // Lines 191 to 194. The catalogue message is carried at its full contractual width
                // and is deliberately not trimmed.
                state.raiseError(messageCatalogService.invalidKeyMessage(), FIELD_MONTHLY);
                sendTrnrptScreen(state);
            }
        }
    }

    // ==========================================================================================
    // PROCESS-ENTER-KEY, line 208
    // ==========================================================================================

    /**
     * The enter-key paragraph at line 208.
     *
     * <p>An ordered, top-down evaluation over the three report-type markers whose clause order is
     * contractual and whose branches are mutually exclusive: month-to-date at line 213, year-to-date
     * at line 239, the operator-supplied range at line 256, and a catch-all at line 437. Only the
     * operator-supplied range validates anything; the two derived periods compute their own dates and
     * do not re-check them.
     *
     * <p>After the evaluation, lines 445 to 456 compose the acknowledgement, but only when the error
     * flag is still clear &mdash; so a blocked confirmation, a declined confirmation, a rejected
     * range or a failed publish all leave their own message standing instead.
     *
     * <p>The source construct is an evaluation of independent conditions rather than of a single
     * selector, so its Java form is an ordered chain of tests and its catch-all is the final arm. A
     * selector-based construct would need a selector that the source does not have, and inventing one
     * &mdash; a derived report-type enumeration, say &mdash; would collapse three independently
     * markable screen fields into one value and lose the first-match-wins behaviour when an operator
     * marks more than one.
     *
     * @param state the turn's working storage
     */
    private void processEnterKey(final TurnState state) {
        LOG.debug("Processing the enter key for the report-request screen");

        if (isSupplied(state.monthlySelection)) {
            monthToDatePeriod(state);
        } else if (isSupplied(state.yearlySelection)) {
            yearToDatePeriod(state);
        } else if (isSupplied(state.customSelection)) {
            operatorSuppliedPeriod(state);
        } else {
            // The catch-all arm at lines 437 to 442, reached when no report type was marked.
            state.raiseError(MSG_SELECT_REPORT_TYPE, FIELD_MONTHLY);
            state.recordFieldError(PROPERTY_REPORT_TYPE, FIELD_MONTHLY,
                    ValidationException.FieldState.MISSING, MSG_SELECT_REPORT_TYPE);
            sendTrnrptScreen(state);
        }

        // A send inside any arm above ended the task at line 580, so line 445 is not reached at all
        // and the acknowledgement below cannot overwrite the failure text the operator was shown.
        if (state.screenSent) {
            return;
        }

        if (!state.errorFlag) {
            // Lines 445 to 456: clear the screen, recolour the message field and compose the
            // acknowledgement from the space-delimited report name.
            initializeAllFields(state);
            state.messageHighlightedGreen = true;
            state.setMessage(delimitedBySpace(state.reportName) + FRAGMENT_SUBMITTED_SUFFIX);
            state.focusField = FIELD_MONTHLY;
            sendTrnrptScreen(state);
        }
    }

    /**
     * The month-to-date arm at lines 213 to 238.
     *
     * <p>The range opens on the first of the current month. The closing day is derived exactly as the
     * source derives it and <strong>not</strong> by asking how long the month is: the day is forced
     * to the first, the month is advanced by one, the year rolls when the month passes the twelfth,
     * and one day is then subtracted on the intrinsic integer-date scale. Reproducing the derivation
     * rather than substituting a month-length lookup keeps the December roll and the leap-year cases
     * behaving as the source behaves.
     *
     * @param state the turn's working storage
     */
    private void monthToDatePeriod(final TurnState state) {
        state.reportPeriod = ReportPeriod.MONTHLY;
        state.reportName = moveToField(ReportPeriod.MONTHLY.getValue(), REPORT_NAME_WIDTH);

        // Line 215 takes the current date once, here from the injected clock.
        final LocalDate currentDate = LocalDate.now(clock);

        // Lines 217 to 221: the current year and month with the first of the month as the day.
        state.startDate = assembleDate(numericField(currentDate.getYear(), YEAR_WIDTH),
                numericField(currentDate.getMonthValue(), MONTH_DAY_WIDTH),
                PERIOD_START_DAY);
        state.parmStartDate = state.startDate;

        // Lines 223 to 228, applied to the same working date the source mutates in place.
        int year = currentDate.getYear();
        int month = currentDate.getMonthValue();
        final int day = FIRST_DAY_OF_MONTH;
        month = month + 1;
        if (month > LAST_MONTH) {
            year = year + 1;
            month = FIRST_MONTH;
        }

        // Lines 229 and 230 step back exactly one day from the first of the following month, through
        // the integer-date conversion the source applies in both directions.
        final LocalDate periodEnd =
                dateOfInteger(integerOfDate(LocalDate.of(year, month, day)) - ONE_DAY);

        // Lines 232 to 236.
        state.endDate = assembleDate(numericField(periodEnd.getYear(), YEAR_WIDTH),
                numericField(periodEnd.getMonthValue(), MONTH_DAY_WIDTH),
                numericField(periodEnd.getDayOfMonth(), MONTH_DAY_WIDTH));
        state.parmEndDate = state.endDate;

        submitJobToIntrdr(state);
    }

    /**
     * The year-to-date arm at lines 239 to 255: the whole of the current year, opening on the first
     * of the first month and closing on the thirty-first of the twelfth, with no derivation and no
     * validation.
     *
     * @param state the turn's working storage
     */
    private void yearToDatePeriod(final TurnState state) {
        state.reportPeriod = ReportPeriod.YEARLY;
        state.reportName = moveToField(ReportPeriod.YEARLY.getValue(), REPORT_NAME_WIDTH);

        // Line 241 takes the current date once, here from the injected clock.
        final String year = numericField(LocalDate.now(clock).getYear(), YEAR_WIDTH);

        // Lines 243 to 253: the same year on both ends of the range.
        state.startDate = assembleDate(year, YEARLY_START_MONTH, PERIOD_START_DAY);
        state.parmStartDate = state.startDate;
        state.endDate = assembleDate(year, YEARLY_END_MONTH, YEARLY_END_DAY);
        state.parmEndDate = state.endDate;

        submitJobToIntrdr(state);
    }

    /**
     * The operator-supplied range arm at lines 256 to 436, and the only arm that validates.
     *
     * <p>Five stages run in source order and the order is contractual. First the six emptiness checks
     * at lines 258 to 303, which are a single ordered evaluation, so at most one of them fires and the
     * one that fires is the first. Then the numeric normalisation of all six parts at lines 305 to
     * 327. Then the six range checks at lines 329 to 379, which are six independent tests, so any
     * number of them can fire. Then the two invocations of the date-validation subprogram at lines 388
     * to 426. Finally the four substitution slots are filled at lines 429 to 432, the report name is
     * set at line 433, and submission is attempted only while the error flag is clear, at lines 434 to
     * 436.
     *
     * <p><strong>The first failure ends the turn, and that is true between stages and within
     * them.</strong> Every failure site in this arm performs the send, and the send jumps out of the
     * performed range to the return paragraph, which ends the task - so in the source the very first
     * failing test is the last thing the turn does. Nothing later is normalised, no later bound is compared, neither
     * subprogram is called, no substitution slot is written, the report name is not set and no
     * submission is attempted. Each stage below is therefore entered only while the turn is still
     * running, and the stages that hold several independent tests stop at the first one that fires
     * rather than reporting all of them: reporting the rest would put field errors in the response that
     * the legacy never evaluated, and continuing past them would return normalised values it never
     * produced. The two-state field contract still distinguishes a field that was not supplied from one
     * supplied wrongly; what it does not do is report more fields in one turn than the legacy screen
     * could carry.
     *
     * @param state the turn's working storage
     */
    private void operatorSuppliedPeriod(final TurnState state) {
        editSuppliedDateParts(state);
        if (state.screenSent) {
            return;
        }

        normaliseSuppliedDateParts(state);

        editDatePartRanges(state);
        if (state.screenSent) {
            return;
        }

        // Lines 381 to 386: assemble both ten-character dates from the normalised parts. Reached only
        // while the turn is still running, which is the only condition under which the source reaches
        // the corresponding moves.
        state.startDate = assembleDate(state.startYear, state.startMonth, state.startDay);
        state.endDate = assembleDate(state.endYear, state.endMonth, state.endDay);

        editSuppliedDates(state);
        if (state.screenSent) {
            return;
        }

        // Lines 429 to 432. Each move writes both occurrences of its slot in one statement, so the
        // two occurrences of a date can never diverge; the card builder fills both card slots from
        // one argument for the same reason.
        state.parmStartDate = state.startDate;
        state.parmEndDate = state.endDate;

        state.reportPeriod = ReportPeriod.CUSTOM;
        state.reportName = moveToField(ReportPeriod.CUSTOM.getValue(), REPORT_NAME_WIDTH);

        // The guarded submission at lines 434 to 436: submission is attempted only while the error
        // flag is clear. The flag cannot be raised at this point without the send having ended the
        // turn above, so the test is the source's own and is kept rather than assumed away.
        if (!state.errorFlag) {
            submitJobToIntrdr(state);
        }
    }

    /**
     * The six emptiness checks at lines 258 to 303.
     *
     * <p>A single ordered evaluation with an explicit no-operation catch-all at lines 301 and 302, so
     * <strong>at most one</strong> of the six fires. Each reports the field as not supplied and each
     * carries its own text, in which {@code NOT} is capitalised exactly as the source capitalises it.
     *
     * @param state the turn's working storage
     */
    private void editSuppliedDateParts(final TurnState state) {
        if (isBlankField(state.startMonth)) {
            faultField(state, MSG_START_DATE_MONTH_EMPTY, PROPERTY_START_MONTH, FIELD_START_MONTH,
                    ValidationException.FieldState.MISSING);
        } else if (isBlankField(state.startDay)) {
            faultField(state, MSG_START_DATE_DAY_EMPTY, PROPERTY_START_DAY, FIELD_START_DAY,
                    ValidationException.FieldState.MISSING);
        } else if (isBlankField(state.startYear)) {
            faultField(state, MSG_START_DATE_YEAR_EMPTY, PROPERTY_START_YEAR, FIELD_START_YEAR,
                    ValidationException.FieldState.MISSING);
        } else if (isBlankField(state.endMonth)) {
            faultField(state, MSG_END_DATE_MONTH_EMPTY, PROPERTY_END_MONTH, FIELD_END_MONTH,
                    ValidationException.FieldState.MISSING);
        } else if (isBlankField(state.endDay)) {
            faultField(state, MSG_END_DATE_DAY_EMPTY, PROPERTY_END_DAY, FIELD_END_DAY,
                    ValidationException.FieldState.MISSING);
        } else if (isBlankField(state.endYear)) {
            faultField(state, MSG_END_DATE_YEAR_EMPTY, PROPERTY_END_YEAR, FIELD_END_YEAR,
                    ValidationException.FieldState.MISSING);
        }
        // The catch-all arm at lines 301 and 302 explicitly does nothing, so the absence of a final
        // else arm below is faithful rather than an omission.
    }

    /**
     * The numeric normalisation of all six date parts at lines 305 to 327.
     *
     * <p>Each part is converted with the currency-tolerant numeric conversion into a two- or
     * four-digit unsigned field and moved straight back over the screen field, which zero-fills a
     * short value and, on overflow, keeps the low-order digits. The consequence the range checks below
     * depend on is that a well-formed part always emerges as digits, so a part that is still
     * non-numeric afterwards was never a well-formed numeric value.
     *
     * @param state the turn's working storage
     */
    private void normaliseSuppliedDateParts(final TurnState state) {
        state.startMonth = numericConversion(state.startMonth, MONTH_DAY_WIDTH);
        state.startDay = numericConversion(state.startDay, MONTH_DAY_WIDTH);
        state.startYear = numericConversion(state.startYear, YEAR_WIDTH);
        state.endMonth = numericConversion(state.endMonth, MONTH_DAY_WIDTH);
        state.endDay = numericConversion(state.endDay, MONTH_DAY_WIDTH);
        state.endYear = numericConversion(state.endYear, YEAR_WIDTH);
    }

    /**
     * The six range checks at lines 329 to 379.
     *
     * <p>Six separate statements rather than one ordered evaluation, so each is reached only if the
     * one before it did not fire. A month must be digits and no greater than the twelfth, a day must be
     * digits and no greater than the thirty-first, and a year must be digits with no upper bound
     * &mdash; the source tests no year range. The bound comparisons are text comparisons against the
     * two-character literals the source writes, matching how it compares its fixed-width fields.
     *
     * <p>The source writes these as six independent statements rather than as an evaluation, which
     * would suggest that all six run and all six report. They do not, and the reason is the send: each
     * failing statement performs it, and the send ends the task, so the first statement to fire is the
     * last one reached. Each test below is therefore guarded on the turn still running, which reproduces
     * that without pretending the source wrote an evaluation it did not write.
     *
     * <p><strong>Only the first failing test is reported, because its send ends the turn.</strong> The
     * six statements are written sequentially rather than as an evaluation, which reads as though every
     * failure would be collected - but each of the six ends in a send, and a send ends the task, so the
     * five that follow the first failure are never reached. Each test therefore returns after faulting,
     * which reproduces that reachability exactly.
     *
     * @param state the turn's working storage
     */
    private void editDatePartRanges(final TurnState state) {
        // IF at lines 329 to 336.
        if (!isAllDigits(state.startMonth) || state.startMonth.compareTo(MONTH_UPPER_BOUND) > 0) {
            faultField(state, MSG_START_DATE_MONTH_INVALID, PROPERTY_START_MONTH, FIELD_START_MONTH,
                    ValidationException.FieldState.INVALID);
            return;
        }
        // IF at lines 338 to 345.
        if (!isAllDigits(state.startDay) || state.startDay.compareTo(DAY_UPPER_BOUND) > 0) {
            faultField(state, MSG_START_DATE_DAY_INVALID, PROPERTY_START_DAY, FIELD_START_DAY,
                    ValidationException.FieldState.INVALID);
            return;
        }
        // IF at lines 347 to 353.
        if (!isAllDigits(state.startYear)) {
            faultField(state, MSG_START_DATE_YEAR_INVALID, PROPERTY_START_YEAR, FIELD_START_YEAR,
                    ValidationException.FieldState.INVALID);
            return;
        }
        // IF at lines 355 to 362.
        if (!isAllDigits(state.endMonth) || state.endMonth.compareTo(MONTH_UPPER_BOUND) > 0) {
            faultField(state, MSG_END_DATE_MONTH_INVALID, PROPERTY_END_MONTH, FIELD_END_MONTH,
                    ValidationException.FieldState.INVALID);
            return;
        }
        // IF at lines 364 to 371.
        if (!isAllDigits(state.endDay) || state.endDay.compareTo(DAY_UPPER_BOUND) > 0) {
            faultField(state, MSG_END_DATE_DAY_INVALID, PROPERTY_END_DAY, FIELD_END_DAY,
                    ValidationException.FieldState.INVALID);
            return;
        }
        // IF at lines 373 to 379.
        if (!isAllDigits(state.endYear)) {
            faultField(state, MSG_END_DATE_YEAR_INVALID, PROPERTY_END_YEAR, FIELD_END_YEAR,
                    ValidationException.FieldState.INVALID);
        }
    }

    /**
     * The two invocations of the date-validation subprogram at lines 388 to 426.
     *
     * <p>Each call moves the assembled ten-character date and the hyphenated ten-character format
     * selector into the parameter group, blanks the eighty-character result block, and calls with the
     * three arguments. Blanking is inherent here, because the result is a return value rather than an
     * output area. The selector is the hyphenated ten-character form the work field at line 72 holds;
     * the compact form the procedural copybook uses is a different contract and is not this caller's.
     *
     * <p><strong>The second call is reached only when the first date is accepted.</strong> The two are
     * written as independent statement groups, but the first group's rejection performs the send, and
     * the send ends the task, so a rejected start date is the end of the turn and the end date is never
     * offered to the subprogram at all. Guarding the second call is what keeps the number of
     * subprogram invocations, and the number of reported failures, the same as the legacy's.
     *
     * @param state the turn's working storage
     */
    private void editSuppliedDates(final TurnState state) {
        // Lines 388 to 406.
        if (!isDateAccepted(dateValidationService.validateDate(state.startDate,
                DateFormat.YYYY_MM_DD))) {
            faultField(state, MSG_START_DATE_INVALID, PROPERTY_START_DATE, FIELD_START_MONTH,
                    ValidationException.FieldState.INVALID);
            return;
        }

        // Lines 408 to 426.
        if (!isDateAccepted(dateValidationService.validateDate(state.endDate,
                DateFormat.YYYY_MM_DD))) {
            faultField(state, MSG_END_DATE_INVALID, PROPERTY_END_DATE, FIELD_END_MONTH,
                    ValidationException.FieldState.INVALID);
        }
    }

    /**
     * The two-level acceptance test the source applies to the result block at lines 396 to 406 and
     * identically at lines 416 to 426.
     *
     * <p><strong>Both levels are load bearing and neither may be dropped.</strong> The accepted
     * severity passes outright. Otherwise a message number that is <em>not</em> the tolerated one is
     * rejected. Otherwise &mdash; a non-zero severity carrying the tolerated message number &mdash;
     * the date is accepted silently, exactly as the legacy accepts it. Collapsing the two levels into
     * a single boolean would reject dates the legacy admits.
     *
     * <p>Both comparisons are four-character text comparisons. Neither field is parsed into a number,
     * because the source compares the character fields and a numeric reading would accept forms the
     * character comparison rejects.
     *
     * @param result the typed form of the eighty-character result block the subprogram returned
     * @return {@code true} when the date is accepted by either route
     */
    private static boolean isDateAccepted(final DateValidationService.SubprogramResult result) {
        // Level one, lines 396 and 416: IF CSUTLDTC-RESULT-SEV-CD = '0000' CONTINUE.
        if (ACCEPTED_SEVERITY_CODE.equals(result.severityCode())) {
            return true;
        }
        // Level two, lines 399 and 419: IF CSUTLDTC-RESULT-MSG-NUM NOT = '2513' report the error.
        if (!TOLERATED_MESSAGE_NUMBER.equals(result.messageNumber())) {
            return false;
        }
        LOG.debug("Accepting a report date on the tolerated message number [{}] despite severity [{}]",
                result.messageNumber(), result.severityCode());
        return true;
    }

    // ==========================================================================================
    // SUBMIT-JOB-TO-INTRDR, line 462
    // ==========================================================================================

    /**
     * The job-submission paragraph at lines 462 to 510, in the three parts the source declares.
     *
     * <p><strong>Part one, lines 464 to 474 &mdash; the confirmation prompt.</strong> A blank
     * confirmation field raises the error flag, composes the prompt from the space-delimited report
     * name, positions the cursor on the confirmation field and sends. Because the whole of the rest of
     * the paragraph sits inside the error-flag guard at line 476, this returns without submitting
     * anything at all.
     *
     * <p><strong>Part two, lines 477 to 494 &mdash; the three-way gate, clause order contractual.</strong>
     * An affirmative confirmation continues. A negative one clears the screen and raises the error
     * flag <em>and sets no message text whatsoever</em>: the rejection is silent in the source and is
     * silent here, because inventing a cancellation message would be output the legacy never produced.
     * Anything else quotes the entered value back inside the unrecognised-value text.
     *
     * <p><strong>Part three, lines 496 to 508 &mdash; the emitting loop.</strong> The guard is
     * evaluated before every card and tests the one-based slot against the card count and against the
     * legacy slot ceiling, and tests the end-of-stream and error flags. Because the guard tests the
     * error flag, a negative or unrecognised confirmation skips the loop entirely without a single
     * write. Within a pass the end-of-stream test is applied to a card <em>before</em> that card is
     * written, which is why the end-of-stream card is itself transmitted and a complete submission is
     * {@code JclCardImageBuilder.CARD_COUNT} messages, and why a failed write stops the cards that
     * would have followed.
     *
     * @param state the turn's working storage
     */
    private void submitJobToIntrdr(final TurnState state) {
        // Part one, lines 464 to 474.
        if (isBlankField(state.confirm)) {
            state.confirmationBlocked = true;
            state.raiseError(FRAGMENT_CONFIRM_PROMPT_PREFIX + delimitedBySpace(state.reportName)
                    + FRAGMENT_CONFIRM_PROMPT_SUFFIX, FIELD_CONFIRM);
            sendTrnrptScreen(state);
            // The send ended the task, so the two remaining parts are unreachable rather than merely
            // guarded. Returning here is what the jump at line 580 did.
            return;
        }

        // The send above ends the task, so nothing below it is reachable once the confirmation was
        // blank. The error-flag guard at line 476 is the source's own guard over both remaining parts
        // and is kept alongside it.
        if (state.screenSent || state.errorFlag) {
            return;
        }

        // Part two, lines 477 to 494: the three-way gate, with its clause order preserved and its
        // catch-all mapped to the final arm.
        if (CONFIRM_YES_UPPER.equals(state.confirm) || CONFIRM_YES_LOWER.equals(state.confirm)) {
            // The affirmative arm at lines 478 and 479 explicitly does nothing: fall through to part
            // three with nothing set.
            LOG.debug("Report submission confirmed for period {}", state.reportPeriod);
        } else if (CONFIRM_NO_UPPER.equals(state.confirm)
                || CONFIRM_NO_LOWER.equals(state.confirm)) {
            // The negative arm at lines 480 to 483. No message text is set: the reset paragraph
            // blanks the message field and nothing writes one afterwards.
            initializeAllFields(state);
            state.errorFlag = true;
            state.confirmationBlocked = true;
            sendTrnrptScreen(state);
        } else {
            // The catch-all arm at lines 484 to 493.
            state.confirmationBlocked = true;
            state.raiseError(FRAGMENT_INVALID_CONFIRM_PREFIX + delimitedBySpace(state.confirm)
                    + FRAGMENT_INVALID_CONFIRM_SUFFIX, FIELD_CONFIRM);
            sendTrnrptScreen(state);
        }

        // Either of the two refusing arms above sent the screen, and the send ended the task, so part
        // three is not reached at all - not even to build the card images. The emitting loop's own
        // guard on the error flag would have suppressed every write, but the cards would still have
        // been assembled, which is work the legacy never did on a refused confirmation.
        if (state.screenSent) {
            return;
        }

        // Line 496 opens the emitting loop with its terminator flag clear.
        state.endLoop = false;

        // The emitting loop's guard at lines 498 and 499 tests the error flag, and the guard is
        // evaluated BEFORE the first card. Part two's negative and unrecognised arms both raise that
        // flag, so either of them skips the loop entirely without a single write - which is why a
        // declined or mistyped confirmation publishes nothing at all. Evaluating the condition here is
        // the whole of what the guard did on its first iteration; the remaining conditions bound the
        // iteration itself and belong with the loop, which is now the bridge's.
        if (state.errorFlag) {
            return;
        }

        // The seventeen eighty-column cards, with the start and end dates placed into their four
        // substitution slots. No card, no column frame and no slot is assembled here.
        final List<String> cardImages =
                JclCardImageBuilder.build(state.parmStartDate, state.parmEndDate);
        final String submissionIdentity = newSubmissionIdentity(
                state.parmStartDate, state.parmEndDate, state.submissionPrincipal,
                state.submissionToken);

        // Lines 498 to 509, the emitting loop - published as ONE SUBMISSION rather than card by card.
        //
        // Iterating the slots here and calling the bridge's single-card entry point once per card is
        // not safe. Every card of every submission carries one stable message group, which is what
        // preserves the append order the legacy queue's disposition guarantees, so a first-in-first-out
        // queue records whatever order the sends arrive in. This service is a singleton and two request
        // threads reaching this paragraph concurrently interleave their sends, after which the queue
        // preserves the interleaving: the batch tier reads back a job card followed by another
        // submission's library card, which is not a degraded job stream but an unparseable one, and both
        // requests answer successfully because the queue is ignore-on-error. Deduplication identifiers
        // do not close it - they make a retry of ONE submission idempotent and say nothing about two.
        //
        // Handing the whole stream to the bridge makes the submission the unit of publication, and the
        // bridge holds one submission at a time. The legacy loop's own semantics are preserved there
        // rather than lost: the end-of-stream test is applied to a card BEFORE that card is written, so
        // the sentinel is transmitted and a complete submission is seventeen messages; and a refused
        // write raises the loop's error flag, so the cards that would have followed are never sent.
        writeJobSubmissionTdq(state, submissionIdentity, cardImages);
    }

    // ==========================================================================================
    // WIRTE-JOBSUB-TDQ, line 515 - misspelled in the source, spelled correctly here
    // ==========================================================================================

    /**
     * The queue-write paragraph at lines 515 to 535.
     *
     * <p>The source spells this paragraph {@code WIRTE-JOBSUB-TDQ}. That misspelling is row 6 of the
     * AAP source-anomaly register: it is recorded in the traceability matrix under its original
     * spelling so the mapping stays findable, and it is deliberately not carried into a Java
     * identifier.
     *
     * <p>The paragraph writes eighty-character records to the queue while capturing the response and
     * reason codes, then evaluates the response: a normal response simply continues, and anything else
     * displays both codes, raises the error flag, sets the queue-write message, repositions the cursor
     * on the first report-type field and sends.
     *
     * <p><strong>It writes the whole stream in one call rather than one call per card, and that is a
     * correctness requirement rather than a tidiness one.</strong> Calling the bridge's single-card
     * entry point once per slot from the emitting loop would not be safe. Every card of every
     * submission carries one stable message group - which is what preserves the append order the legacy
     * queue's disposition guarantees - so a first-in-first-out queue records whatever order the sends
     * arrive in. This is a singleton service, so two request threads reaching this paragraph
     * concurrently interleave their sends and the queue then preserves the interleaving: the batch tier
     * reads back a job card followed by another submission's library card, which is not a degraded job
     * stream but an unparseable one, and both requests answer successfully because the queue is
     * ignore-on-error. Deduplication identifiers do not close that - they make a retry of ONE
     * submission idempotent and say nothing about two distinct ones. Handing the bridge the whole
     * stream makes the submission the unit of publication, and the bridge admits one submission at a
     * time.
     *
     * <p>The legacy loop's own semantics are preserved by the bridge rather than lost. The
     * end-of-stream test is applied to a card <em>before</em> that card is written, so the sentinel is
     * transmitted and a complete submission is {@code JclCardImageBuilder.CARD_COUNT} messages; and a
     * refused write raises the write-error flag the guard observes, so the cards that would have
     * followed are never sent, which is why a failure reports fewer cards published than requested.
     *
     * <p><strong>There is no abend and no re-raise.</strong> Three consequences follow and all three
     * are reproduced. The failure is logged rather than displayed. The remaining cards are not sent.
     * And control returns normally, so the caller receives a result and not an exception &mdash; which
     * is why a submission exception is caught and logged here and never leaves this service. That is
     * the queue's own ignore-on-error definition at {@code app/csd/CARDDEMO.CSD} lines 499 to 505, so
     * no retry, backoff, timeout or delay is applied either.
     *
     * <p>The failure object is never handed to the logger. It carries the underlying cloud-client
     * cause, whose message this module did not author and which can hold an endpoint, a request
     * identifier, a signed header fragment or an arbitrarily large payload echo. The bounded reason
     * codes the bridge already derived, plus the sanitised chain of failure types, say everything the
     * diagnostic needs and nothing that was not composed here. See {@link FailureDiagnostics}.
     *
     * @param state              the turn's working storage
     * @param submissionIdentity the identity of this submission, combined with each card's slot to form
     *                           that card's deduplication identifier
     * @param cardImages         the complete ordered card stream, each card eighty characters wide
     */
    private void writeJobSubmissionTdq(final TurnState state, final String submissionIdentity,
            final List<String> cardImages) {
        final JobSubmissionService.SubmissionResult submission;
        try {
            // Lines 517 to 523 write one eighty-character record to the named queue per card while
            // capturing the response and reason codes. The queue name, the record width, the message
            // grouping and the one-submission-at-a-time exclusion all belong to the bridge, not
            // here.
            submission = jobSubmissionService.submitCanonicalJobImage(submissionIdentity, cardImages);
        } catch (final JobSubmissionException publishFailure) {
            // Defensive: the bridge reports a publish failure as a value. Should it ever raise
            // instead, the outcome must still be the legacy's - logged, non-fatal, and the write
            // treated as refused.
            LOG.error("Job-submission queue write raised on card slot {}: queue={} response={}"
                            + " reason={} failureChain={}",
                    publishFailure.failedCardOrdinal(), publishFailure.queueName(),
                    publishFailure.responseCode(), publishFailure.reasonCode(),
                    FailureDiagnostics.failureChainOf(publishFailure));
            refuseSubmission(state, 0);
            return;
        }

        // The loop's own post-condition, restored from the outcome the bridge reports.
        state.cardsPublished = submission.cardsPublished();

        // The response-code evaluation at lines 525 to 535.
        if (submission.failed()) {
            refuseSubmission(state, submission.cardsPublished());
            return;
        }

        // The normal-response arm at lines 526 and 527 does nothing. The loop ended because the
        // sentinel card was reached and written, which is the only other way out of the legacy guard.
        // The loop terminator the source raises at lines 503 to 505.
        state.endLoop = true;
    }

    /**
     * The refusal arm of the queue-write paragraph, at lines 528 to 534.
     *
     * <p>The response and reason codes were already recorded at the publish boundary, which is where
     * they exist; this records the submission-level consequence and then does exactly what the
     * paragraph does - raises the error flag, sets the queue-write message, repositions the cursor on
     * the first report-type field and sends. The error flag is also what the emitting loop's guard
     * observed, which is why the cards after the failing one are not sent.
     *
     * @param state          the turn's working storage
     * @param cardsPublished how many cards the queue accepted before refusing
     */
    private void refuseSubmission(final TurnState state, final int cardsPublished) {
        state.cardsPublished = cardsPublished;
        LOG.error("Job-submission queue write refused after {} of {} cards; the remaining cards are"
                        + " not sent",
                cardsPublished, JclCardImageBuilder.CARD_COUNT);
        state.raiseError(MSG_UNABLE_TO_WRITE_TDQ, FIELD_MONTHLY);
        sendTrnrptScreen(state);
    }

    // ==========================================================================================
    // RETURN-TO-PREV-SCREEN, line 540
    // ==========================================================================================

    /**
     * The transfer paragraph at lines 540 to 551.
     *
     * <p>Both of its arms are modelled. When the nominated destination field holds spaces or low
     * values the paragraph falls back to this screen's own default, sign-on, at lines 542 to 544;
     * otherwise it honours what that field names. The resolution itself is the navigation rules'
     * concern, so no route table is declared here &mdash; only the one per-screen default the paragraph
     * itself hardcodes.
     *
     * <p>Lines 545 to 547 then stamp the originating transaction and program and reset the program
     * context to its first-entry value, and lines 548 to 551 transfer control. A transfer carries the
     * communication area but does not re-arm a transaction, so the turn reports no re-armed identifier.
     *
     * @param state the turn's working storage
     */
    private void returnToPrevScreen(final TurnState state) {
        // Lines 542 to 544, both arms, resolved by the navigation rules.
        final NavigationService.Route destination = navigationService
                .resolveNominatedDestination(state.context, NavigationService.Route.SIGN_ON);

        // Lines 545 to 547 stamp this turn's own transaction and program as the originator and clear
        // the program-context flag.
        state.context = withOriginatingProgram(
                withNominatedProgram(state.context, destination.getLegacyProgramName()))
                .withFirstEntry();

        // The transfer of control at lines 548 to 551, carrying the communication area to the
        // nominated destination.
        state.route = destination;
        state.transferred = true;
        LOG.debug("Transferring control from the report-request screen to route {}",
                destination.getRouteValue());
    }

    // ==========================================================================================
    // SEND-TRNRPT-SCREEN, line 556
    // ==========================================================================================

    /**
     * The send paragraph at lines 556 to 580.
     *
     * <p>It populates the header, moves the eighty-character message work field into the
     * {@value #ERROR_MESSAGE_WIDTH}-character outbound message field at line 560, and sends the map.
     * The two arms of the send at lines 562 to 578 differ only in the 3270 erase attribute; the erase
     * flag is set at line 167 and never reset anywhere in the member, so only the erasing arm is
     * reachable and the attribute itself has no equivalent in a machine contract, which is why it is
     * documented here rather than modelled.
     *
     * <p><strong>The paragraph ends at line 580 by jumping to the return paragraph, and that makes
     * the send terminal.</strong> The paragraph it jumps to returns at lines 587 to 590, which ends
     * the task, so the performing paragraph never resumes and nothing
     * after the first send executes: no later validation stage runs, no later field is normalised, no
     * later output field is written and no submission is attempted. The jump leaves the performed range
     * rather than reaching an exit label within it, so a {@code return} from this method could not
     * express it - every caller up the chain has to see that the turn is over. This method therefore
     * populates the outbound result, as a rendering step must, and additionally <strong>latches the
     * turn-ended state</strong> that its callers test. Control must not be allowed to continue past a
     * send so that a re-submission can report every faulted field: that would report field errors the
     * legacy never evaluated and return normalised values it never produced. The operator-visible pair
     * is unchanged either way, because the summary text and the cursor position are already latched at
     * the first failure.
     *
     * @param state the turn's working storage
     */
    private void sendTrnrptScreen(final TurnState state) {
        populateHeaderInfo(state);

        // Line 560 copies the message work field into the outbound message field.
        state.errorMessageField = moveToField(state.message, ERROR_MESSAGE_WIDTH);

        // The jump to the return paragraph at line 580: the task ends here, so the turn is over for
        // every paragraph that is still notionally on the stack.
        state.screenSent = true;
        returnToCics(state);
    }

    // ==========================================================================================
    // RETURN-TO-CICS, line 585
    // ==========================================================================================

    /**
     * The return paragraph at lines 585 to 591, which re-arms this transaction and hands the
     * communication area back.
     *
     * <p>This member has its own return paragraph, reached both by the jump at the end of the send and
     * as the terminal statement of the procedure division, so it gets its own method rather than being
     * folded into either. Re-arming is idempotent and never overrides a transfer, because a transfer
     * has already left the program.
     *
     * @param state the turn's working storage
     */
    private void returnToCics(final TurnState state) {
        if (state.transferred) {
            return;
        }
        state.reArmedTransactionId = WS_TRANID;
    }

    // ==========================================================================================
    // RECEIVE-TRNRPT-SCREEN, line 596
    // ==========================================================================================

    /**
     * The receive paragraph at lines 596 to 604.
     *
     * <p>Receives the input map, which is the point at which each transmitted value is bounded to the
     * width its symbolic-map field declares: a longer value loses its excess on the right and a
     * shorter one is space filled, so every later comparison works on a fixed-width value exactly as
     * the source's comparisons do. A field the client omitted arrives as low values, which the
     * blankness tests treat identically to spaces.
     *
     * @param state the turn's working storage
     * @param input the transmitted screen
     */
    private void receiveTrnrptScreen(final TurnState state, final ReportScreenInput input) {
        state.monthlySelection = moveToField(input.monthlySelection(), SELECTION_WIDTH);
        state.yearlySelection = moveToField(input.yearlySelection(), SELECTION_WIDTH);
        state.customSelection = moveToField(input.customSelection(), SELECTION_WIDTH);
        state.startMonth = moveToField(input.startMonth(), MONTH_DAY_WIDTH);
        state.startDay = moveToField(input.startDay(), MONTH_DAY_WIDTH);
        state.startYear = moveToField(input.startYear(), YEAR_WIDTH);
        state.endMonth = moveToField(input.endMonth(), MONTH_DAY_WIDTH);
        state.endDay = moveToField(input.endDay(), MONTH_DAY_WIDTH);
        state.endYear = moveToField(input.endYear(), YEAR_WIDTH);
        state.confirm = moveToField(input.confirm(), CONFIRM_WIDTH);
    }

    // ==========================================================================================
    // POPULATE-HEADER-INFO, line 609
    // ==========================================================================================

    /**
     * The header paragraph at lines 609 to 628.
     *
     * <p>Reads the current date and time, then stamps the two catalogue titles, the transaction
     * identifier and the program name, and assembles the two-digit header date and time. The header
     * date keeps the legacy's two-digit year, taken as the last two characters of the four-character
     * year exactly as the reference modification at line 620 takes it, and both separators are the
     * ones the date and time work groups declare.
     *
     * @param state the turn's working storage
     */
    private void populateHeaderInfo(final TurnState state) {
        // Line 611 takes the current date and time once, here from the injected clock.
        final LocalDateTime now = LocalDateTime.now(clock);

        state.title01 = messageCatalogService.screenTitle01();
        state.title02 = messageCatalogService.screenTitle02();
        state.transactionName = WS_TRANID;
        state.programName = WS_PGMNAME;

        // Lines 618 to 622: MM/DD/YY, the year taken as WS-CURDATE-YEAR(3:2).
        final String year = numericField(now.getYear(), YEAR_WIDTH);
        state.currentDate = numericField(now.getMonthValue(), HEADER_PART_WIDTH)
                + HEADER_DATE_SEPARATOR
                + numericField(now.getDayOfMonth(), HEADER_PART_WIDTH)
                + HEADER_DATE_SEPARATOR
                + year.substring(YEAR_SHORT_FORM_OFFSET);

        // Lines 624 to 628: HH:MM:SS.
        state.currentTime = numericField(now.getHour(), HEADER_PART_WIDTH)
                + HEADER_TIME_SEPARATOR
                + numericField(now.getMinute(), HEADER_PART_WIDTH)
                + HEADER_TIME_SEPARATOR
                + numericField(now.getSecond(), HEADER_PART_WIDTH);
    }

    // ==========================================================================================
    // INITIALIZE-ALL-FIELDS, line 633
    // ==========================================================================================

    /**
     * The reset paragraph at lines 633 to 646.
     *
     * <p>Positions the cursor on the first report-type field at line 635, then blanks the ten screen
     * input fields and the message work field. Both of its call sites are terminal for the turn: the
     * successful-submission path at line 447 and the declined-confirmation path at line 481. Clearing
     * the message is what leaves the declined-confirmation path with no text at all.
     *
     * @param state the turn's working storage
     */
    private void initializeAllFields(final TurnState state) {
        // Line 635 positions the cursor on the first report-type field.
        state.focusField = FIELD_MONTHLY;

        // Lines 636 to 646 blank the ten screen fields and the message work field.
        state.monthlySelection = blankField(SELECTION_WIDTH);
        state.yearlySelection = blankField(SELECTION_WIDTH);
        state.customSelection = blankField(SELECTION_WIDTH);
        state.startMonth = blankField(MONTH_DAY_WIDTH);
        state.startDay = blankField(MONTH_DAY_WIDTH);
        state.startYear = blankField(YEAR_WIDTH);
        state.endMonth = blankField(MONTH_DAY_WIDTH);
        state.endDay = blankField(MONTH_DAY_WIDTH);
        state.endYear = blankField(YEAR_WIDTH);
        state.confirm = blankField(CONFIRM_WIDTH);
        state.message = NO_MESSAGE;
    }

    // ==========================================================================================
    // Shared primitives for the constructs the source uses
    // ==========================================================================================

    /**
     * Records one field failure: raises the error flag, latches the summary text and cursor position
     * if this is the first failure of the turn, adds the per-field entry, and sends the screen.
     *
     * <p>Every failure site in the source does these four things in the same order, which is why they
     * live here once rather than being restated a dozen times.
     *
     * <p><strong>This method is terminal, because the send it ends with is terminal.</strong> The send
     * closes with the jump to the return paragraph, which ends the task, so a caller must treat a call
     * to this method as the end of the turn: it either returns immediately or tests the turn-ended state
     * before doing anything else. Nothing here can enforce that on a caller's behalf, which is why the
     * state is latched rather than left implicit.
     *
     * @param state      the turn's working storage
     * @param message    the failure's own text, byte exact
     * @param property   the property name a consumer binds to
     * @param bmsFieldId the legacy screen field name the cursor is positioned on
     * @param fieldState whether the field was not supplied or was supplied wrongly
     */
    private void faultField(final TurnState state, final String message, final String property,
            final String bmsFieldId, final ValidationException.FieldState fieldState) {
        state.raiseError(message, bmsFieldId);
        state.recordFieldError(property, bmsFieldId, fieldState, message);
        sendTrnrptScreen(state);
    }

    /**
     * Reports whether a report-type marker was supplied, reproducing the abbreviated combined
     * relation at lines 213, 239 and 256, which tests that the field is neither all spaces nor all
     * low values.
     *
     * @param field the marker field
     * @return {@code true} when the field carries something other than spaces and low values
     */
    private static boolean isSupplied(final String field) {
        return !isBlankField(field);
    }

    /**
     * Reports whether a fixed-width field is blank in the legacy sense - all spaces or all low
     * values - as tested at lines 259, 266, 273, 280, 287, 294, 464 and 503.
     *
     * <p>Blank means every character position is a space or a low value. An absent value and an empty
     * value are both blank, because a field the terminal did not transmit arrives as low values and
     * carries no character positions of its own.
     *
     * <p>The absent case is a defensive invariant rather than a live branch: every call site passes a
     * field the receive paragraph has already bounded to its declared width, and that bounding never
     * yields {@code null}. It is kept because the alternative to a guard here is an exception at a call
     * site that a future edit could introduce, and because a total predicate is the contract a
     * blankness test ought to have.
     *
     * @param field the field to test, which may be {@code null}
     * @return {@code true} when the field is absent, empty, or wholly spaces and low values
     */
    private static boolean isBlankField(final String field) {
        if (field == null) {
            return true;
        }
        for (int index = 0; index < field.length(); index++) {
            final char position = field.charAt(index);
            if (position != SPACE && position != LOW_VALUE) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reports whether every character position holds an ASCII digit, reproducing
     * {@code IS NOT NUMERIC} at lines 329, 338, 347, 355, 364 and 373 when negated.
     *
     * <p>Membership is tested against the ten ASCII digits and never against a Unicode digit test,
     * which would widen the accepted set past the single-byte characters the legacy field can hold.
     * An empty field is not numeric, matching a field with no digit in it.
     *
     * <p>The empty case is a defensive invariant rather than a live branch: every screen field is at
     * least one character position wide, so no call site can reach it. It is kept because a loop over
     * an empty sequence would otherwise answer {@code true} by vacuity, and a numeric test that
     * accepts nothing at all would be a real defect the moment a caller could reach it.
     *
     * @param field the field to test, never {@code null} at any call site here
     * @return {@code true} when the field is non-empty and holds nothing but ASCII digits
     */
    private static boolean isAllDigits(final String field) {
        if (field.isEmpty()) {
            return false;
        }
        for (int index = 0; index < field.length(); index++) {
            final char position = field.charAt(index);
            if (position < '0' || position > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Reproduces one pair of statements from lines 305 to 327: the currency-tolerant numeric
     * conversion of a screen field into an unsigned numeric field of the given digit count, followed
     * by the move straight back over the screen field.
     *
     * <p>Three outcomes, in the order they are tested:
     * <ul>
     *   <li>A field that was not supplied converts to zero, so the receiving numeric field zero-fills
     *       and the move back writes all zeros. That is why a part reported as not supplied is
     *       <em>not</em> also reported as out of range: zeros pass both range tests.</li>
     *   <li>A well-formed numeric lexeme is converted, its integer digits are taken &mdash; the
     *       receiving field is unsigned and has no decimal places, so a sign and a fraction are both
     *       discarded &mdash; and the result is right justified with zero fill, which pads a short
     *       value and, on overflow, keeps the low-order digits exactly as an unrounded store into a
     *       fixed-width numeric field does.</li>
     *   <li>Anything else is left exactly as it was transmitted. The conversion of an argument that is
     *       not a well-formed numeric lexeme is undefined in the language, and leaving the field alone
     *       is the only reading under which the three "not a valid" texts at lines 331, 340, 348, 357,
     *       366 and 374 are reachable at all &mdash; the following test is precisely a numeric test,
     *       and a converted field is always numeric. The divergence is recorded in the decision log.</li>
     * </ul>
     *
     * @param field the screen field, already bounded to its declared width
     * @param width the digit count of the receiving numeric field: two for a month or day, four for a
     *              year
     * @return the field as the move back leaves it
     */
    private static String numericConversion(final String field, final int width) {
        if (isBlankField(field)) {
            return ZERO_DIGIT.repeat(width);
        }
        if (!CobolStringUtils.isNumericLexeme(field)) {
            return field;
        }
        return CobolStringUtils.rightJustifyZeroFill(
                integerDigitsOf(CobolStringUtils.plainDecimalOfNumericLexeme(field)), width);
    }

    /**
     * Takes the integer digits of a plain decimal string, discarding a leading sign and any
     * fractional part, because the receiving field of the conversion is unsigned and has no decimal
     * places.
     *
     * @param plainDecimal a plain decimal string: an optional minus sign, at least one integer digit,
     *                     and an optional fractional part
     * @return the integer digits, at least one character
     */
    private static String integerDigitsOf(final String plainDecimal) {
        final int firstDigit = plainDecimal.charAt(0) == MINUS_SIGN ? 1 : 0;
        final int decimalPoint = plainDecimal.indexOf(DECIMAL_POINT);
        final int end = decimalPoint < 0 ? plainDecimal.length() : decimalPoint;
        return plainDecimal.substring(firstDigit, end);
    }

    /**
     * Assembles the ten-character date group declared at lines 60 to 71: a four-character year, a
     * hyphen, a two-character month, a hyphen and a two-character day.
     *
     * <p>The group is assembled as text and is never held as a calendar object, because its three
     * parts are validated individually and the assembled text is what crosses both the
     * date-validation contract and the job-submission contract.
     *
     * @param year  the four-character year
     * @param month the two-character month
     * @param day   the two-character day
     * @return the ten-character date
     */
    private static String assembleDate(final String year, final String month, final String day) {
        return year + DATE_PART_SEPARATOR + month + DATE_PART_SEPARATOR + day;
    }

    /**
     * Reproduces {@code FUNCTION INTEGER-OF-DATE}, which numbers 1601-01-01 as day one.
     *
     * <p>Modelled by name rather than folded away because the source subtracts on this scale at line
     * 230 and the subtraction is the whole of the derivation.
     *
     * @param calendarDay the calendar day
     * @return the integer date
     */
    private static long integerOfDate(final LocalDate calendarDay) {
        return calendarDay.toEpochDay() - INTEGER_DATE_ORIGIN_EPOCH_DAY;
    }

    /**
     * Reproduces {@code FUNCTION DATE-OF-INTEGER}, the inverse of {@code integerOfDate}.
     *
     * @param integerDate the integer date
     * @return the calendar day it names
     */
    private static LocalDate dateOfInteger(final long integerDate) {
        return LocalDate.ofEpochDay(integerDate + INTEGER_DATE_ORIGIN_EPOCH_DAY);
    }

    /**
     * Reproduces the source's space-delimited assembly, which stops at the first space, as applied to
     * the report name at lines 449 and 468 and to the confirmation field at line 487.
     *
     * @param field the fixed-width field to consume
     * @return the part of the field before its first space, or the whole field when it holds none
     */
    private static String delimitedBySpace(final String field) {
        final int firstSpace = field.indexOf(SPACE);
        return firstSpace < 0 ? field : field.substring(0, firstSpace);
    }

    /**
     * Reproduces a move into an alphanumeric field of the given width: left justified, space filled on
     * the right when the sender is shorter, and truncated on the right when it is longer. An absent
     * sender yields a blank field, because a field the terminal did not transmit holds no characters.
     *
     * @param value the sending value, which may be {@code null}
     * @param width the receiving field width in character positions
     * @return exactly {@code width} characters
     */
    private static String moveToField(final String value, final int width) {
        if (value == null) {
            return blankField(width);
        }
        if (value.length() >= width) {
            return value.substring(0, width);
        }
        return value + blankField(width - value.length());
    }

    /**
     * Reproduces a move of an unsigned numeric value into a display field of the given digit count:
     * zero filled on the left, and keeping the low-order digits when the value has more digits than
     * the field has positions, which is how an unrounded store into a fixed-width numeric field
     * behaves.
     *
     * @param value the value to render; its magnitude is used, matching an unsigned receiving field
     * @param width the digit count of the receiving field
     * @return exactly {@code width} digits
     */
    private static String numericField(final int value, final int width) {
        return CobolStringUtils.rightJustifyZeroFill(Integer.toString(Math.abs(value)), width);
    }

    /**
     * Produces a blank field of the given width, which is what {@code INITIALIZE} writes into an
     * alphanumeric item.
     *
     * @param width the field width in character positions
     * @return exactly {@code width} spaces
     */
    private static String blankField(final int width) {
        return String.valueOf(SPACE).repeat(width);
    }


    /**
     * Derives the identity of one submission from its date slots, its logical-request token and the
     * operator the turn was authenticated as.
     *
     * <p>The bridge composes each card's deduplication identifier from this identity plus the card's
     * one-based slot, and the bridge's own contract forbids a random value in the identity's place
     * because a fresh identity on a retry defeats idempotency. The identity is therefore a pure function
     * of the date range, the stable token and the operator: a caller that resubmits an interrupted request
     * with the same token reissues exactly the identifiers the first pass used, so the queue collapses the
     * cards that already landed and the stream is completed rather than doubled behind itself.
     *
     * <p><strong>The operator is part of the identity, and it has to be.</strong> The token is a value a
     * caller chooses, and a caller naturally chooses something meaningful to itself - a period name, a run
     * label - so two operators can arrive at the same token without either knowing about the other. An
     * identity composed from the dates and the token alone would make those two submissions the same
     * submission: the queue would collapse the second operator's cards as a duplicate of the first's and
     * the second operator would be told the request had been submitted, having published nothing. Naming
     * the authenticated operator inside the identity keeps one caller's token from reaching another
     * caller's submission, while a retry by the <em>same</em> operator still repeats its own identifiers
     * exactly.
     *
     * <p><strong>The operator is the authenticated principal, never an echoed field.</strong> It arrives
     * from the security context by way of the controller, not from the navigation state the client sends
     * back, because the navigation state is a value a caller can write anything into. A turn driven with no
     * security chain established carries none, and a fixed marker stands in for the absence so that the
     * identity remains a total function; the delivered route requires an authenticated identity, so that
     * marker is not reachable over the shipped boundary.
     *
     * <p>The legacy queue had no notion of identity at all: a second request for the same period was
     * appended and the job ran again. That remains reachable: a genuinely new submission of the same
     * period receives a different token, while a retry repeats the earlier token. The distinction is now
     * explicit instead of being guessed from the dates, which are identical in those two cases.
     *
     * <p>Whitespace is removed because the slots are fixed-width values that may be space-padded while
     * the bridge requires an identity free of whitespace. Only the identity is condensed; no card is,
     * because a card's padding is contractual. The two date slots have already been shaped and
     * validated by the time they reach here, so the date part is printable single-byte text. The operator
     * and the token are folded into <em>one</em> fixed hexadecimal digest rather than added as a further
     * component, which keeps arbitrary caller text and the operator's identifier out of diagnostics, keeps
     * the identity the same length it has always been, and keeps the composed identifier comfortably inside
     * the queue service's bound.
     *
     * @param startDate the start-date slot the submission carries
     * @param endDate   the end-date slot the submission carries
     * @param submissionPrincipal the operator the turn was authenticated as, or {@code null} when no
     *                  identity was established
     * @param submissionToken the stable token of this logical submission
     * @return a whitespace-free identity, identical only for the same dates, operator and token
     */
    private static String newSubmissionIdentity(final String startDate, final String endDate,
            final String submissionPrincipal, final String submissionToken) {
        return withoutWhitespace(startDate) + SUBMISSION_IDENTITY_SEPARATOR
                + withoutWhitespace(endDate) + SUBMISSION_IDENTITY_SEPARATOR
                + digestSubmissionToken(submissionPrincipal, submissionToken);
    }

    /**
     * Uses the caller's stable retry token, or mints one for a deliberate new logical request.
     *
     * @param retryToken caller-supplied token, possibly absent or blank
     * @return the effective token, never blank
     */
    private static String resolveSubmissionToken(final String retryToken) {
        return retryToken == null || retryToken.isBlank()
                ? UUID.randomUUID().toString()
                : retryToken;
    }

    /**
     * Produces the bounded printable component used inside the queue deduplication identity, over the
     * operator and the logical-request token together.
     *
     * <p>The two values are separated by a byte that cannot occur in either of them, so no pair of an
     * operator and a token can be rearranged into another pair with the same digest: an operator
     * identifier is the eight-character key of a user record and a token is text a caller supplied over an
     * HTTP header, and neither can carry a control byte. Digesting the pair rather than concatenating it
     * into the identity keeps the operator's identifier out of the queue, out of the bridge's diagnostics
     * and out of the identity's length.
     *
     * @param submissionPrincipal the authenticated operator, or {@code null} when none was established
     * @param submissionToken     the effective logical-request token
     * @return a lower-case hexadecimal SHA-256 digest
     */
    private static String digestSubmissionToken(final String submissionPrincipal,
            final String submissionToken) {
        final String pair = (submissionPrincipal == null
                ? SUBMISSION_PRINCIPAL_ABSENT : submissionPrincipal)
                + SUBMISSION_DIGEST_FIELD_SEPARATOR + submissionToken;
        try {
            final MessageDigest digest =
                    MessageDigest.getInstance(SUBMISSION_TOKEN_DIGEST_ALGORITHM);
            return HexFormat.of().formatHex(digest.digest(pair.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(
                    SUBMISSION_TOKEN_DIGEST_ALGORITHM + " must be available in every Java runtime",
                    unavailable);
        }
    }

    /**
     * Returns a value with every whitespace character removed.
     *
     * @param value the value to condense
     * @return {@code value} without whitespace
     */
    private static String withoutWhitespace(final String value) {
        final StringBuilder condensed = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            if (!Character.isWhitespace(character)) {
                condensed.append(character);
            }
        }
        return condensed.toString();
    }

    /**
     * Returns a copy of the navigation state whose nominated-destination program is the one supplied,
     * reproducing how the member nominates its destination at lines 173, 188 and 543.
     *
     * <p>Every other component is carried across unchanged. The sixteen components are restated
     * explicitly because the state is an immutable record; the alternative, a mutable copy, would let
     * echoed client state be altered in place.
     *
     * @param context     the state to copy
     * @param programName the destination program name to nominate
     * @return a new state differing only in its nominated-destination program
     */
    private static ConversationState withNominatedProgram(final ConversationState context,
            final String programName) {
        return context.withNominatedProgram(programName);
    }

    /**
     * Returns a copy of the navigation state stamped with this screen's transaction identifier and
     * program name as the originator, reproducing lines 545 and 546.
     *
     * @param context the state to copy
     * @return a new state differing only in its originating transaction and program
     */
    private static ConversationState withOriginatingProgram(final ConversationState context) {
        // Lines 545 to 547 stamp the originator and clear the program-context flag together, which is
        // exactly what the carried state's own origin derivation does. The eleven identity and
        // cardholder members of the legacy area are absent from the service-tier state, so there is no
        // copy here that could let an echoed identity survive a turn.
        return context.withOrigin(WS_TRANID, WS_PGMNAME);
    }

    // ==========================================================================================
    // Per-invocation working storage
    // ==========================================================================================

    /**
     * The working storage the legacy declares at lines 36 to 79, held per invocation so the service
     * bean itself stays stateless and two concurrent turns cannot observe one another.
     *
     * <p>Package-private mutable fields rather than accessors: this is a local scratch area belonging
     * to one call of one enclosing class, and accessors would add ceremony without adding safety.
     */
    private static final class TurnState {

        /** {@code WS-ERR-FLG}, cleared at line 165 and raised by every failure site. */
        private boolean errorFlag;

        /** {@code WS-END-LOOP}, cleared at line 496 and raised at line 504. */
        private boolean endLoop;

        /** {@code WS-MESSAGE}, blanked at line 169, in its authored rather than padded form. */
        private String message = NO_MESSAGE;

        /** {@code WS-REPORT-NAME}, {@code PIC X(10)} at line 58. */
        private String reportName = blankField(REPORT_NAME_WIDTH);

        /** {@code WS-START-DATE}, the ten-character group at lines 60 to 65. */
        private String startDate = NO_MESSAGE;

        /** {@code WS-END-DATE}, the ten-character group at lines 66 to 71. */
        private String endDate = NO_MESSAGE;

        /**
         * The start-date substitution value. One field rather than two, because every move writes both
         * {@code PARM-START-DATE-1} and {@code PARM-START-DATE-2} in a single statement at lines 220,
         * 247 and 429, so the two occurrences cannot diverge; the card builder likewise fills both of
         * its card slots from one argument.
         */
        private String parmStartDate = NO_MESSAGE;

        /** The end-date substitution value, written to both of its slots at lines 235, 252 and 431. */
        private String parmEndDate = NO_MESSAGE;

        /** {@code MONTHLYI}, the month-to-date marker. */
        private String monthlySelection = blankField(SELECTION_WIDTH);

        /** {@code YEARLYI}, the year-to-date marker. */
        private String yearlySelection = blankField(SELECTION_WIDTH);

        /** {@code CUSTOMI}, the operator-range marker. */
        private String customSelection = blankField(SELECTION_WIDTH);

        /** {@code SDTMMI}, the start month. */
        private String startMonth = blankField(MONTH_DAY_WIDTH);

        /** {@code SDTDDI}, the start day. */
        private String startDay = blankField(MONTH_DAY_WIDTH);

        /** {@code SDTYYYYI}, the start year. */
        private String startYear = blankField(YEAR_WIDTH);

        /** {@code EDTMMI}, the end month. */
        private String endMonth = blankField(MONTH_DAY_WIDTH);

        /** {@code EDTDDI}, the end day. */
        private String endDay = blankField(MONTH_DAY_WIDTH);

        /** {@code EDTYYYYI}, the end year. */
        private String endYear = blankField(YEAR_WIDTH);

        /** {@code CONFIRMI}, the confirmation field. */
        private String confirm = blankField(CONFIRM_WIDTH);

        /** {@code ERRMSGO}, the outbound message field at its own narrower width. */
        private String errorMessageField = blankField(ERROR_MESSAGE_WIDTH);

        /** {@code CCDA-TITLE01} as moved at line 613. */
        private String title01 = NO_MESSAGE;

        /** {@code CCDA-TITLE02} as moved at line 614. */
        private String title02 = NO_MESSAGE;

        /** {@code TRNNAMEO} as moved at line 615. */
        private String transactionName = NO_MESSAGE;

        /** {@code PGMNAMEO} as moved at line 616. */
        private String programName = NO_MESSAGE;

        /** {@code CURDATEO} as assembled at lines 618 to 622. */
        private String currentDate = NO_MESSAGE;

        /** {@code CURTIMEO} as assembled at lines 624 to 628. */
        private String currentTime = NO_MESSAGE;

        /** The cursor position, from the negative length item the source writes for that field. */
        private String focusField = NO_MESSAGE;

        /** Whether the message field was recoloured at line 448. */
        private boolean messageHighlightedGreen;

        /** Whether the confirmation gate at lines 464 to 494 stopped the submission. */
        private boolean confirmationBlocked;

        /** How many cards the queue accepted. */
        private int cardsPublished;

        /** Stable token that identifies this logical request across retries. */
        private String submissionToken;

        /**
         * The operator this turn was authenticated as, which scopes the submission identity.
         *
         * <p>Not a legacy field and not a screen field: the legacy region knew who was signed on and its
         * queue had no notion of identity at all, so nothing in the source corresponds to this. It exists
         * because the target's queue deduplicates, and a deduplication namespace shared between callers is
         * one caller able to suppress another's submission. Absent when no security context was
         * established, which the delivered route does not permit.
         */
        private String submissionPrincipal;

        /** The transaction identifier re-armed by a return, empty when control was transferred. */
        private String reArmedTransactionId = NO_MESSAGE;

        /** Whether control was transferred, which is what makes a return unreachable for this turn. */
        private boolean transferred;

        /**
         * Whether the screen has been sent, which in the legacy is the end of the task.
         *
         * <p>The send paragraph closes by jumping to the return paragraph at line 580, and that
         * paragraph returns at lines 587 to 590, so the task ends at the <strong>first</strong>
         * send: the paragraph that performed it never resumes,
         * and no statement after that point in the procedure division executes at all. That jump is a
         * genuine transfer out of a performed range rather than an exit label, so it cannot be
         * translated as a {@code return} from one method - every caller in the chain has to observe it.
         * This flag is how they observe it. It is latched once and never cleared, and every stage that
         * could otherwise run after a send tests it first.
         */
        private boolean screenSent;

        /** {@code CARDDEMO-COMMAREA}, the navigation state the turn carries. */
        private ConversationState context = ConversationState.empty();

        /**
         * The resolved period, absent until one of the three arms resolves it and left absent when the
         * catch-all fires.
         */
        private ReportPeriod reportPeriod;

        /**
         * The destination the turn leads to, opening on this screen's own destination because the
         * return at lines 199 to 202 re-arms this same transaction, and overridden only by a transfer.
         */
        private NavigationService.Route route = NavigationService.Route.REPORT_REQUEST;

        /**
         * The per-field detail. A list because the outbound contract carries a list, and because the
         * catch-all report-type failure records one entry while the cascade records one of its own -
         * but never more than one entry in a single turn, because the send that follows every failure
         * ends the turn.
         */
        private final List<ValidationException.FieldError> fieldErrors = new ArrayList<>();

        /**
         * Raises the error flag and latches the summary text and cursor position on the first failure
         * of the turn.
         *
         * <p>Latching is what keeps the operator-visible outcome byte-identical to the legacy's: the
         * screen carries the first failure's text and the first failure's cursor position. Since the
         * first send now ends the turn as the legacy's does, only one failure can be recorded per turn
         * anyway, and the latch is what guarantees it stays the first one even where a caller sets a
         * message before delegating.
         *
         * @param text       the failure's text, byte exact
         * @param cursorField the screen field the cursor is positioned on
         */
        private void raiseError(final String text, final String cursorField) {
            this.errorFlag = true;
            if (this.message.isEmpty()) {
                this.message = text;
                this.focusField = cursorField;
            }
        }

        /**
         * Sets the message unconditionally, for the one site that writes a message with no failure
         * behind it: the acknowledgement at lines 449 to 452.
         *
         * @param text the message text, byte exact
         */
        private void setMessage(final String text) {
            this.message = text;
        }

        /**
         * Adds one per-field entry.
         *
         * @param property   the property name a consumer binds to
         * @param bmsFieldId the legacy screen field name
         * @param fieldState whether the field was not supplied or was supplied wrongly
         * @param text       the field's own message
         */
        private void recordFieldError(final String property, final String bmsFieldId,
                final ValidationException.FieldState fieldState, final String text) {
            this.fieldErrors.add(
                    new ValidationException.FieldError(property, bmsFieldId, fieldState, text));
        }

        /**
         * Projects the working storage onto the turn's outcome.
         *
         * @return the outcome, with the per-field detail copied so nothing mutable escapes
         */
        private ReportRequestResult toResult() {
            return new ReportRequestResult(this.route,
                    this.context,
                    this.reArmedTransactionId,
                    this.reportPeriod,
                    delimitedBySpace(this.reportName),
                    this.startDate,
                    this.endDate,
                    this.cardsPublished,
                    this.submissionToken,
                    this.confirmationBlocked,
                    this.message,
                    this.messageHighlightedGreen,
                    this.focusField,
                    this.errorFlag,
                    List.copyOf(this.fieldErrors),
                    new ScreenHeader(this.title01,
                            this.title02,
                            this.transactionName,
                            this.programName,
                            this.currentDate,
                            this.currentTime,
                            this.errorMessageField),
                    new ScreenFields(this.monthlySelection,
                            this.yearlySelection,
                            this.customSelection,
                            this.startMonth,
                            this.startDay,
                            this.startYear,
                            this.endMonth,
                            this.endDay,
                            this.endYear,
                            this.confirm));
        }
    }
}
