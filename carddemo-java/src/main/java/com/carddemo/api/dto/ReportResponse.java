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
package com.carddemo.api.dto;

import com.carddemo.domain.enums.ReportPeriod;
import jakarta.validation.constraints.Size;

/**
 * Immutable acknowledgement of a transaction-report request &mdash; the REST-era form of the one
 * screen that legacy transaction {@code CR00} presented, re-presented after every rejection, and
 * presented once more, cleared, after a successful submission.
 *
 * <p>The field contract is taken from the symbolic map {@code app/cpy-bms/CORPT00.CPY} and from the
 * screen definition {@code app/bms/CORPT00.bms}. The behaviour, and every text below, is taken from
 * the report-request program {@code app/cbl/CORPT00C.cbl}, whose request paragraph begins at line
 * 208 and whose submission paragraph begins at line 462. Provenance of the reading: checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Nothing from the legacy grammar is
 * reproduced here &mdash; no statement, no picture clause, no field declaration. The operator-visible
 * texts <em>are</em> reproduced, character for character, because they are an external interface
 * contract that the migration is required to preserve rather than restate.
 *
 * <p><strong>This type acknowledges a submission without describing one.</strong> That distinction
 * is the whole reason it exists as its own type. The legacy program, once it had a validated request
 * and the operator's confirmation, assembled a fixed-width job image and handed it line by line to
 * the asynchronous bridge between the online tier and the batch tier, then reported the outcome on
 * the screen. Only the last of those steps is modelled here. Nothing about the submitted payload
 * appears in this contract: no image, no line, no sentinel, no fixed width, no offset, no column, no
 * count, no transport name, no destination, no address, no identifier minted by the transport and no
 * moment at which it was accepted. A client learns <em>that</em> a request was accepted and
 * <em>what</em> to tell the operator, and nothing further. Building the payload belongs to the
 * utility layer, publishing it to the service layer; both are deliberately invisible from here, and
 * this record imports neither.
 *
 * <p>The transport was defined to ignore its own write failures, so a failed publish logs and
 * continues rather than aborting the caller. The text the legacy program showed in that case (line
 * 531) is therefore <em>not</em> declared in this file: it is already owned, character for
 * character, by the module's job-submission failure type, and a second copy here would be a second
 * source of truth for one contractual string. This record also declares no failure-mode enumeration,
 * no retry hint and no exception component, because the legacy path offered the operator none of
 * those.
 *
 * <p><strong>On success the legacy screen is wiped, so blank echoed values are correct.</strong>
 * Before composing its success text the program performs the initialise-all-fields paragraph (line
 * 447, paragraph at line 633), which clears the three report-type selectors, all six date
 * components, the confirmation field and the message work field, and returns operator attention to
 * the first selector. A successful acknowledgement therefore legitimately carries blank or absent
 * echoed values alongside a populated summary text, and every echoed component below is optional for
 * exactly that reason. Nothing here requires an echoed value to be present, and nothing here
 * reconstructs one that the legacy program had already wiped.
 *
 * <p><strong>An error with no text is a real state, which is why the error indicator is its own
 * boolean.</strong> When the operator answers the confirmation prompt with the negative character in
 * either case, the program takes its negative-confirmation branch: it performs the same
 * initialise-all-fields paragraph at line 481 &mdash; which clears the message work field &mdash;
 * sets its error flag at line 482, and re-presents the screen at line 483 <em>without composing any
 * text at all</em>. That is a silent rejection, and it is not a rarity: it is the ordinary way an
 * operator backs out of a confirmed submission. {@link #generalError()} consequently reports the flag
 * the program set and is never inferred from whether {@link #message()} is present. Deriving it from
 * message presence would turn every silent rejection into an apparent success, which is the single
 * most damaging defect this contract can carry. By the same token no placeholder text is invented to
 * fill the gap: an error indicator that is set while the summary text is absent is a valid, expected
 * combination, and so is an accepted submission that carries text.
 *
 * <p><strong>Two text components, and why the pair is not redundant.</strong> {@link #message()} is
 * the summary the service selected or assembled &mdash; one text and only one, because the legacy
 * cascade is first-error-wins: each check that fails writes the message work field and immediately
 * re-presents the screen, so no second check is ever reached and no list of texts can arise.
 * {@link #errorMessage()} is the same text after projection onto the screen field it was displayed
 * in, which is bounded at {@value #ERROR_MESSAGE_LENGTH} characters by the map
 * ({@code app/cpy-bms/CORPT00.CPY} line 120, corroborated by {@code app/bms/CORPT00.bms} line 220).
 * The two are kept apart because the widths genuinely differ: the legacy work field is the wider of
 * the two, so a long text was visibly cut when it reached the screen, and a client that renders the
 * screen faithfully needs the projected form while a client that logs or tests the decision needs
 * the unprojected one. Neither component is trimmed, padded or re-cased on the way through.
 *
 * <p><strong>Where field-level detail belongs.</strong> This screen has no per-field error
 * decoration: the legacy program owns one message field and one operator-attention target, so a
 * single summary text plus a single focus identity is the complete contract. When a caller does need
 * to describe several fields at once it uses {@link ErrorResponse} and its nested per-field carrier,
 * whose two states distinguish a value that was not supplied from one that was supplied and
 * rejected. That carrier is reached through {@link ErrorResponse}, never through the module's
 * validation failure type, even where the two enumerations happen to be identical: the failure type
 * belongs to a layer this package must not depend on.
 *
 * <p><strong>Nothing presentational crosses this boundary.</strong> The legacy program set one
 * highlight on the message field for a success (line 448) and a different one for every failure.
 * Colour, highlight and control bytes are terminal concerns with no meaning to a REST client, so
 * none is modelled: there is no colour component, no control-byte group, no leading filler, no map
 * geometry, no marker byte and no edited screen mask anywhere in this record. Severity, where a
 * client needs it, is carried semantically by {@link #submissionAccepted()} and
 * {@link #generalError()} rather than by a rendering hint.
 *
 * <p><strong>Immutability and threading.</strong> This is a {@code record}: every component is
 * final, there is no mutator, no builder, no mutable static state and no collection to expose or
 * copy. The only non-{@code String} reference components are an enumeration constant and
 * {@link NavigationContext}, which is itself an immutable record, so an instance is deeply immutable
 * and safe to publish across threads. There is no canonical constructor body because there is
 * nothing a correct one could do: every component may legitimately be absent, and normalising any of
 * them would corrupt the contract.
 *
 * @param period the report period the operator selected, or {@code null} when the request carried no
 *     selection. Collapses the three single-character selectors of the map
 *     ({@code app/cpy-bms/CORPT00.CPY} lines 60, 66 and 72) into one value, because the legacy
 *     program itself reduced them to a single work field, assigning it at line 214, line 240 and line
 *     433 as its ordered evaluation of the three selectors reached each in turn. The report name that
 *     appears in the assembled texts is {@link ReportPeriod#getValue()} and never the constant's own
 *     identifier: the two differ in case, and the legacy text carries the value. Absent is a real
 *     state &mdash; it is what the operator sees answered by {@link #MSG_SELECT_REPORT_TYPE} (line
 *     438) &mdash; and it is also the state a successful acknowledgement is cleared to.
 * @param startMonth the echoed start-of-range month, at most {@value #MONTH_LENGTH} characters
 *     ({@code app/cpy-bms/CORPT00.CPY} line 78). Text, not a number: a single-digit month is carried
 *     as two characters with its leading zero intact, and any numeric type would drop that zero and
 *     shorten the external width the byte-equivalence criterion compares. Blank or absent whenever
 *     the selected period is not the operator-supplied range, and blank on every successful
 *     acknowledgement.
 * @param startDay the echoed start-of-range day, at most {@value #DAY_LENGTH} characters
 *     ({@code app/cpy-bms/CORPT00.CPY} line 84). Text for the same reason as {@code startMonth}.
 * @param startYear the echoed start-of-range year, at most {@value #YEAR_LENGTH} characters
 *     ({@code app/cpy-bms/CORPT00.CPY} line 90). Text for the same reason as {@code startMonth}.
 * @param endMonth the echoed end-of-range month, at most {@value #MONTH_LENGTH} characters
 *     ({@code app/cpy-bms/CORPT00.CPY} line 96).
 * @param endDay the echoed end-of-range day, at most {@value #DAY_LENGTH} characters
 *     ({@code app/cpy-bms/CORPT00.CPY} line 102).
 * @param endYear the echoed end-of-range year, at most {@value #YEAR_LENGTH} characters
 *     ({@code app/cpy-bms/CORPT00.CPY} line 108). The six components stay separate and stay text:
 *     they are never merged here, no ten-character submission parameter is built here, and no
 *     date-and-time API is used here. The screen orders them month, day, year while the submission
 *     parameter orders them year, month, day; that reordering is a service concern, and performing it
 *     in a data-transfer type would put a translation rule where no test can see it.
 * @param confirm the echoed confirmation character, at most {@value #CONFIRM_LENGTH} character
 *     ({@code app/cpy-bms/CORPT00.CPY} line 114). Text and not a truth value, deliberately: the
 *     legacy program recognises three outcomes and not two, testing the affirmative characters at
 *     line 478 and the negative characters immediately after them, before falling through at line 484
 *     to quote the operator's own character back verbatim. A truth value cannot carry the third case,
 *     and the quoted text cannot be composed without the original character. Blank or absent means
 *     the operator has not answered yet, which is what raises the confirmation prompt at lines 466
 *     to 469.
 * @param transactionName the echoed transaction identity shown in the screen corner, at most
 *     {@value #TRANSACTION_NAME_LENGTH} characters ({@code app/cpy-bms/CORPT00.CPY} line 24).
 *     Declarative only: nothing here dispatches on it.
 * @param title01 the first screen title line, at most {@value #SCREEN_TITLE_LENGTH} characters
 *     ({@code app/cpy-bms/CORPT00.CPY} line 30). Supplied by the common title catalogue, which pads
 *     it; the padding is contract and is not trimmed here.
 * @param currentDate the date the screen was built, at most {@value #DATE_LENGTH} characters
 *     ({@code app/cpy-bms/CORPT00.CPY} line 36). An already-formatted display value carried through
 *     unaltered; this record neither formats nor interprets it.
 * @param programName the echoed program identity shown in the screen corner, at most
 *     {@value #PROGRAM_NAME_LENGTH} characters ({@code app/cpy-bms/CORPT00.CPY} line 42).
 *     Declarative only.
 * @param title02 the second screen title line, at most {@value #SCREEN_TITLE_LENGTH} characters
 *     ({@code app/cpy-bms/CORPT00.CPY} line 48).
 * @param currentTime the time the screen was built, at most {@value #TIME_LENGTH} characters
 *     ({@code app/cpy-bms/CORPT00.CPY} line 54). An already-formatted display value, as
 *     {@code currentDate} is.
 * @param errorMessage the summary text projected onto the screen message field, at most
 *     {@value #ERROR_MESSAGE_LENGTH} characters ({@code app/cpy-bms/CORPT00.CPY} line 120). Absent on
 *     the silent rejection described above, and populated with the success text on an accepted
 *     submission &mdash; the legacy field carried both outcomes and was distinguished only by a
 *     highlight, which this contract replaces with {@link #submissionAccepted()}.
 * @param submissionAccepted whether the request passed every check, was confirmed, and was handed to
 *     the submission bridge. Semantic acknowledgement and nothing more: no identifier, no receipt, no
 *     depth and no timestamp accompanies it, because the legacy operator was given none of those
 *     either.
 * @param message the one summary text the service selected or assembled, or {@code null} when there
 *     is none. Exactly one, never a list, because the legacy cascade stops at its first failure.
 *     Unbounded here on purpose: it is the text before projection onto the narrower screen field, and
 *     bounding it to the screen width would silently drop the very characters the projection is
 *     supposed to reveal.
 * @param generalError whether the legacy error flag was set for this turn. Read it as set or clear
 *     and never as "a text is present": the two are independent, and the negative-confirmation branch
 *     sets this at line 482 while leaving {@link #message()} absent.
 * @param fieldToFocus the identity of the field the operator's attention should return to, or
 *     {@code null} when the client should decide. One of the constants below, and an identity only
 *     &mdash; not a row, not a column, not a control byte and not a sentinel number. It travels
 *     independently of which check failed: a successful submission returns attention to the report-type
 *     selector (lines 452 to 453) while a confirmation failure returns it to the confirmation field
 *     (line 472 and line 492), and each date check returns it to the component it rejected.
 * @param route the declarative next resource the client may call, carried as an opaque string, or
 *     {@code null} when the request stays on this screen. Opaque on purpose: the vocabulary belongs to
 *     the navigation service, so this record declares no route table, no route enumeration and no
 *     dispatch of any kind. Nothing is forwarded on the server; the client drives the next call.
 * @param navigationContext the echoed navigation state, or {@code null} when none was carried. Client
 *     held and client returned, never a server session. The screen work area shared by the
 *     five-program family that declares it is deliberately absent: the report program is not a member
 *     of that family and carries no such area.
 * @since 1.0.0
 */
public record ReportResponse(
        ReportPeriod period,
        @Size(max = ReportResponse.MONTH_LENGTH) String startMonth,
        @Size(max = ReportResponse.DAY_LENGTH) String startDay,
        @Size(max = ReportResponse.YEAR_LENGTH) String startYear,
        @Size(max = ReportResponse.MONTH_LENGTH) String endMonth,
        @Size(max = ReportResponse.DAY_LENGTH) String endDay,
        @Size(max = ReportResponse.YEAR_LENGTH) String endYear,
        @Size(max = ReportResponse.CONFIRM_LENGTH) String confirm,
        @Size(max = ReportResponse.TRANSACTION_NAME_LENGTH) String transactionName,
        @Size(max = ReportResponse.SCREEN_TITLE_LENGTH) String title01,
        @Size(max = ReportResponse.DATE_LENGTH) String currentDate,
        @Size(max = ReportResponse.PROGRAM_NAME_LENGTH) String programName,
        @Size(max = ReportResponse.SCREEN_TITLE_LENGTH) String title02,
        @Size(max = ReportResponse.TIME_LENGTH) String currentTime,
        @Size(max = ReportResponse.ERROR_MESSAGE_LENGTH) String errorMessage,
        boolean submissionAccepted,
        String message,
        boolean generalError,
        String fieldToFocus,
        String route,
        NavigationContext navigationContext) {

    /**
     * Width in characters of a month component: 2 &mdash; the map width of the start-of-range month
     * at {@code app/cpy-bms/CORPT00.CPY} line 78 and of the end-of-range month at line 96.
     *
     * <p>Declared separately from {@link #DAY_LENGTH} even though the two values are equal, because a
     * month and a day are different kinds of value whose widths coincide; neither is derived from the
     * other, and a later correction to one must not silently move the other. The bound only reports
     * an over-long value: it never trims, pads or re-cases one.
     */
    public static final int MONTH_LENGTH = 2;

    /**
     * Width in characters of a day component: 2 &mdash; the map width of the start-of-range day at
     * {@code app/cpy-bms/CORPT00.CPY} line 84 and of the end-of-range day at line 102. Declared
     * separately from {@link #MONTH_LENGTH} for the reason given there.
     */
    public static final int DAY_LENGTH = 2;

    /**
     * Width in characters of a year component: 4 &mdash; the map width of the start-of-range year at
     * {@code app/cpy-bms/CORPT00.CPY} line 90 and of the end-of-range year at line 108. Four
     * characters is a full year and not an abbreviation, matching the year-month-day submission
     * parameter the service builds from these components.
     */
    public static final int YEAR_LENGTH = 4;

    /**
     * Width in characters of the confirmation field: 1 &mdash; the map width at
     * {@code app/cpy-bms/CORPT00.CPY} line 114.
     *
     * <p>The bound deliberately does <em>not</em> restrict the value to the affirmative and negative
     * characters tested at {@code app/cbl/CORPT00C.cbl} line 478 and immediately after it. A third
     * character is legitimate input that the legacy program accepts and answers by quoting it back
     * (lines 484 to 493), so constraining the value here would reject input the legacy system
     * handles and would make {@link #FRAGMENT_INVALID_CONFIRM_SUFFIX} unreachable.
     */
    public static final int CONFIRM_LENGTH = 1;

    /**
     * Width in characters of the transaction identity shown in the screen corner: 4 &mdash; the map
     * width at {@code app/cpy-bms/CORPT00.CPY} line 24. Declared separately from
     * {@link #YEAR_LENGTH}, whose value coincides, because a transaction identity and a year are
     * unrelated.
     */
    public static final int TRANSACTION_NAME_LENGTH = 4;

    /**
     * Width in characters of each screen title line: 40 &mdash; the map width of the first title at
     * {@code app/cpy-bms/CORPT00.CPY} line 30 and of the second at line 48. The two share this
     * constant because they are the same kind of value at the same declared width. The common title
     * catalogue pads both to this width and the padding is contract, so a validated title keeps every
     * one of its trailing spaces.
     */
    public static final int SCREEN_TITLE_LENGTH = 40;

    /**
     * Width in characters of the displayed date: 8 &mdash; the map width at
     * {@code app/cpy-bms/CORPT00.CPY} line 36. An already-formatted display value; nothing here
     * interprets its layout.
     */
    public static final int DATE_LENGTH = 8;

    /**
     * Width in characters of the displayed time: 8 &mdash; the map width at
     * {@code app/cpy-bms/CORPT00.CPY} line 54. Declared separately from {@link #DATE_LENGTH} because
     * a date and a time are different kinds of value that happen to be displayed at the same width.
     */
    public static final int TIME_LENGTH = 8;

    /**
     * Width in characters of the program identity shown in the screen corner: 8 &mdash; the map width
     * at {@code app/cpy-bms/CORPT00.CPY} line 42. Declared separately from {@link #DATE_LENGTH} and
     * {@link #TIME_LENGTH} for the same reason: three unrelated fields, one coincidental width.
     */
    public static final int PROGRAM_NAME_LENGTH = 8;

    /**
     * Width in characters of the screen message field: 78 &mdash; the map width at
     * {@code app/cpy-bms/CORPT00.CPY} line 120, corroborated by the screen definition at
     * {@code app/bms/CORPT00.bms} line 220.
     *
     * <p>This is a genuinely different width from the wider message field that several other screens
     * in the estate use, and the difference is easy to carry over by mistake because every other
     * aspect of those screens' message handling is identical. The legacy work field this text was
     * composed in is wider than the screen field it was displayed in, so a long text was cut when it
     * reached the screen. That is exactly why {@link #message()} is unbounded while
     * {@link #errorMessage()} carries this bound: the pair records the text before and after the
     * projection, and the bound measures without altering, so no character is removed by validation.
     */
    public static final int ERROR_MESSAGE_LENGTH = 78;

    /*
     * ---------------------------------------------------------------------------------------------
     * THE OPERATOR-VISIBLE TEXTS
     * ---------------------------------------------------------------------------------------------
     * Every text below is reproduced character for character from app/cbl/CORPT00C.cbl at the line
     * cited on it. They are published here, on the response contract, because they are what the
     * operator reads: preserving them is an interface-contract obligation of the migration, and a
     * single misplaced space, a lowered capital or a fourth dot is an observable regression.
     *
     * Three properties of the set are load bearing and are easy to lose:
     *
     *   1. The casing is inconsistent between the groups and must stay inconsistent. The six
     *      emptiness texts write the negation in full capitals, which no other text does. All twelve
     *      component texts capitalise the component name, while the two calendar texts leave their
     *      final word in lower case. That is not tidy, and it is not to be tidied.
     *
     *   2. The three composed texts are published as their separate fragments and are never
     *      pre-joined here, because the value that sits between them is only known at request time.
     *      Assembly belongs to the report-request service; this record neither joins, formats nor
     *      trims anything.
     *
     *   3. Two fragments differ by one space and are otherwise identical. They are declared
     *      separately, and neither is derived from the other. See the notes on each.
     * ---------------------------------------------------------------------------------------------
     */

    /**
     * Rejection text when the start-of-range month was not supplied. Line 261.
     *
     * <p>Note the full-capital negation. All six emptiness texts share it and no other text in this
     * file uses it, so lowering it to sentence case is an observable change.
     */
    public static final String MSG_START_DATE_MONTH_EMPTY = "Start Date - Month can NOT be empty...";

    /** Rejection text when the start-of-range day was not supplied. Line 268. */
    public static final String MSG_START_DATE_DAY_EMPTY = "Start Date - Day can NOT be empty...";

    /** Rejection text when the start-of-range year was not supplied. Line 275. */
    public static final String MSG_START_DATE_YEAR_EMPTY = "Start Date - Year can NOT be empty...";

    /** Rejection text when the end-of-range month was not supplied. Line 282. */
    public static final String MSG_END_DATE_MONTH_EMPTY = "End Date - Month can NOT be empty...";

    /** Rejection text when the end-of-range day was not supplied. Line 289. */
    public static final String MSG_END_DATE_DAY_EMPTY = "End Date - Day can NOT be empty...";

    /** Rejection text when the end-of-range year was not supplied. Line 296. */
    public static final String MSG_END_DATE_YEAR_EMPTY = "End Date - Year can NOT be empty...";

    /**
     * Rejection text when the start-of-range month is not numeric or exceeds the twelfth month. Line
     * 331.
     *
     * <p>Note the difference from the emptiness texts above: those write the negation in full
     * capitals and these write it in sentence case. Both groups capitalise the component name, so it
     * is only the negation that differs between them.
     */
    public static final String MSG_START_DATE_MONTH_INVALID = "Start Date - Not a valid Month...";

    /**
     * Rejection text when the start-of-range day is not numeric or exceeds the thirty-first day. Line
     * 340. The legacy check is a plain upper bound and is not calendar aware; the calendar check is a
     * later, separate stage with its own text.
     */
    public static final String MSG_START_DATE_DAY_INVALID = "Start Date - Not a valid Day...";

    /** Rejection text when the start-of-range year is not numeric. Line 348. */
    public static final String MSG_START_DATE_YEAR_INVALID = "Start Date - Not a valid Year...";

    /**
     * Rejection text when the end-of-range month is not numeric or exceeds the twelfth month.
     * Line 357.
     */
    public static final String MSG_END_DATE_MONTH_INVALID = "End Date - Not a valid Month...";

    /**
     * Rejection text when the end-of-range day is not numeric or exceeds the thirty-first day.
     * Line 366.
     */
    public static final String MSG_END_DATE_DAY_INVALID = "End Date - Not a valid Day...";

    /** Rejection text when the end-of-range year is not numeric. Line 374. */
    public static final String MSG_END_DATE_YEAR_INVALID = "End Date - Not a valid Year...";

    /**
     * Rejection text when the assembled start-of-range value is not a real calendar day. Line 400.
     *
     * <p>The final word is lower case here, unlike the capitalised component name of the six range
     * texts above, and that asymmetry is contract. This text belongs to the separate calendar stage
     * that follows the component checks, and it is raised only when that stage reports a non-zero
     * severity whose result number is not the one number the legacy program tolerates &mdash; so a
     * value can pass every component check and still be rejected here, and a non-zero severity does
     * not on its own produce this text.
     */
    public static final String MSG_START_DATE_INVALID = "Start Date - Not a valid date...";

    /**
     * Rejection text when the assembled end-of-range value is not a real calendar day. Line 420. The
     * final word is lower case, as in the start-of-range counterpart.
     */
    public static final String MSG_END_DATE_INVALID = "End Date - Not a valid date...";

    /**
     * Rejection text when no report type was selected at all. Line 438.
     *
     * <p>This is the catch-all outcome of the legacy ordered evaluation of the three selectors, and it
     * is an input-validation result rather than a fourth report type &mdash; which is why
     * {@link #period()} is simply absent when a client receives this text.
     */
    public static final String MSG_SELECT_REPORT_TYPE = "Select a report type to print report...";

    /**
     * Trailing fragment of the acceptance text, appended after the selected report name. Lines 449 to
     * 450, composed into the message work field at line 452.
     *
     * <p><strong>This fragment has a leading space and a space before its three dots.</strong> The
     * confirmation fragment {@link #FRAGMENT_CONFIRM_PROMPT_SUFFIX} looks interchangeable with it and
     * is not: that one has the leading space but no space before its dots. Unifying the two, or
     * deriving either from the other, shifts the assembled text by one byte and fails the interface
     * contract check. They are two constants on purpose.
     *
     * <p>The report name that precedes it is {@link ReportPeriod#getValue()}. The legacy composition
     * consumed the report-name work field up to its first space, so the trailing padding of that
     * fixed-width field never reached the text, which is precisely why the enumeration carries bare
     * unpadded values. The service places the value and this fragment adjacently with nothing between
     * them.
     */
    public static final String FRAGMENT_SUBMITTED_SUFFIX = " report submitted for printing ...";

    /**
     * Leading fragment of the confirmation prompt raised when the confirmation field is blank. Lines
     * 466 to 467, composed into the message work field at line 470.
     *
     * <p><strong>This fragment ends with a space</strong>, and that trailing space is the separator
     * before the report name. It must not be trimmed, and the service must not insert a second space
     * of its own.
     */
    public static final String FRAGMENT_CONFIRM_PROMPT_PREFIX = "Please confirm to print the ";

    /**
     * Trailing fragment of the confirmation prompt, appended after the selected report name. Line 469.
     *
     * <p><strong>This fragment has a leading space and no space before its three dots</strong>
     * &mdash; the one-byte difference from {@link #FRAGMENT_SUBMITTED_SUFFIX}. The report name between
     * the two fragments is {@link ReportPeriod#getValue()}, consumed up to its first space by the
     * legacy composition exactly as in the acceptance text (line 468).
     */
    public static final String FRAGMENT_CONFIRM_PROMPT_SUFFIX = " report...";

    /**
     * Opening fragment of the invalid-confirmation text: one straight double-quote character. Line
     * 486.
     *
     * <p>Straight and not typographic. The operator's own character is quoted back between this
     * fragment and {@link #FRAGMENT_INVALID_CONFIRM_SUFFIX}, consumed up to its first space (line 487),
     * which is why {@link #confirm()} is carried as text rather than as a truth value: without the
     * original character this text cannot be composed.
     */
    public static final String FRAGMENT_INVALID_CONFIRM_PREFIX = "\"";

    /**
     * Closing fragment of the invalid-confirmation text. Line 488.
     *
     * <p><strong>The closing double-quote is part of this fragment</strong>, not a separate one, so the
     * service appends exactly this string after the operator's character and adds no quote of its own.
     * Raised only for a third character: the affirmative characters proceed (line 478) and the negative
     * characters take the silent-rejection branch whose body runs at lines 481 to 483 and produces no
     * text at all.
     */
    public static final String FRAGMENT_INVALID_CONFIRM_SUFFIX =
            "\" is not a valid value to confirm...";

    /*
     * ---------------------------------------------------------------------------------------------
     * THE FIELD IDENTITIES USED BY fieldToFocus
     * ---------------------------------------------------------------------------------------------
     * Each value is the name of the corresponding property of this contract, which is the identity a
     * REST client can act on. The legacy screen field names are deliberately not used: this record
     * models no screen artefact of any kind, and the sibling per-field error carrier already
     * documents its own field-name component as the property a client sent, so the property name is
     * the established vocabulary for field identity in this package.
     *
     * These are identities and nothing else. There is no row, no column, no control byte and no
     * sentinel number here, and a client that has its own focus policy may ignore them entirely.
     * ---------------------------------------------------------------------------------------------
     */

    /**
     * Identity of the report-type selection. Attention returns here after an accepted submission
     * &mdash; first by the initialise-all-fields paragraph at line 633 when it is performed at line
     * 447, then again at line 453 once the acceptance text has been composed &mdash; and also when no
     * report type was selected at all (line 441).
     */
    public static final String FIELD_PERIOD = "period";

    /** Identity of the start-of-range month. Attention returns here from lines 264 and 334. */
    public static final String FIELD_START_MONTH = "startMonth";

    /** Identity of the start-of-range day. Attention returns here from lines 271 and 343. */
    public static final String FIELD_START_DAY = "startDay";

    /** Identity of the start-of-range year. Attention returns here from lines 278 and 351. */
    public static final String FIELD_START_YEAR = "startYear";

    /** Identity of the end-of-range month. Attention returns here from lines 285 and 360. */
    public static final String FIELD_END_MONTH = "endMonth";

    /** Identity of the end-of-range day. Attention returns here from lines 292 and 369. */
    public static final String FIELD_END_DAY = "endDay";

    /** Identity of the end-of-range year. Attention returns here from lines 299 and 377. */
    public static final String FIELD_END_YEAR = "endYear";

    /**
     * Identity of the confirmation field. Attention returns here when the prompt is raised (line 472)
     * and when the supplied character is neither affirmative nor negative (line 492) &mdash; the two
     * cases that prove focus travels independently of which check failed.
     */
    public static final String FIELD_CONFIRM = "confirm";
}
