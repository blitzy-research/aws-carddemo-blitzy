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

import jakarta.validation.constraints.Size;

/**
 * Immutable acknowledgement of a transaction-report request, legacy transaction {@code CR00}: the one
 * screen that transaction presented, re-presented after every rejection and presented once more,
 * cleared, after a successful submission.
 *
 * <p>The field contract comes from {@code app/cpy-bms/CORPT00.CPY} and {@code app/bms/CORPT00.bms};
 * the behaviour and every text below come from {@code app/cbl/CORPT00C.cbl}. The operator-visible
 * texts are reproduced character for character because they are an external interface contract;
 * nothing of the legacy grammar is reproduced.
 *
 * <p><strong>This type acknowledges a submission without describing one.</strong> That distinction is
 * why it exists as its own type. Once the legacy program had a validated request and the operator's
 * confirmation it assembled a fixed-width job image, handed it line by line to the asynchronous
 * bridge between the online and batch tiers, and reported the outcome on the screen; only the last of
 * those steps is modelled here. Nothing about the submitted payload appears in this contract - no
 * image, line, sentinel, width, offset, count, transport name, destination, transport-minted
 * identifier or acceptance moment. Building the payload belongs to the utility layer and publishing
 * it to the service layer; this record imports neither.
 *
 * <p>The transport was defined to ignore its own write failures, so a failed publish logs and
 * continues rather than aborting the caller. The text the legacy program showed in that case is
 * therefore deliberately <strong>not</strong> declared here: it is already owned, character for
 * character, by the module's job-submission failure type, and a second copy would be a second source
 * of truth for one contractual string. This record likewise declares no failure-mode enumeration, no
 * retry hint and no exception component, because the legacy path offered the operator none.
 *
 * <p><strong>On success the legacy screen is wiped, so blank echoed values are correct.</strong>
 * Before composing its success text the program clears the three report-type selectors, all six date
 * components, the confirmation field and the message work field, and returns attention to the first
 * selector. Every echoed component below is therefore optional, and nothing here reconstructs a value
 * the legacy program had already wiped.
 *
 * <p><strong>An error with no text is a real state, which is why the error indicator is its own
 * boolean.</strong> When the operator answers the confirmation prompt negatively the program clears
 * the message work field, sets its error flag and re-presents the screen <em>without composing any
 * text at all</em>. That silent rejection is the ordinary way an operator backs out of a confirmed
 * submission. {@link #generalError()} consequently reports the flag the program set and is never
 * inferred from whether {@link #message()} is present: deriving it from message presence would turn
 * every silent rejection into an apparent success, which is the single most damaging defect this
 * contract can carry. No placeholder text is invented to fill the gap either.
 *
 * <p><strong>Two text components, and why the pair is not redundant.</strong> {@link #message()} is
 * the summary the service selected or assembled - one text and only one, because the legacy cascade is
 * first-error-wins: each failing check writes the message field and immediately re-presents the
 * screen, so no second check is reached and no list of texts can arise. {@link #errorMessage()} is
 * that same text after projection onto the narrower screen field. The widths genuinely differ - the
 * legacy work field is the wider of the two, so a long text was visibly cut when it reached the screen
 * - and a client that renders the screen faithfully needs the projected form while a client that logs
 * or tests the decision needs the unprojected one. Neither is trimmed, padded or re-cased in transit.
 *
 * <p>This screen has no per-field error decoration: the legacy program owns one message field and one
 * attention target, so a single summary text plus a single focus identity is the complete contract. A
 * caller that must describe several fields at once uses {@link ErrorResponse} and its nested per-field
 * carrier, reached through {@link ErrorResponse} and never through the module's validation failure
 * type, even where the two enumerations are identical: the failure type belongs to a layer this
 * package must not depend on.
 *
 * <p><strong>Nothing presentational crosses this boundary.</strong> The legacy program set one
 * highlight on the message field for success and another for every failure. Colour, highlight and
 * control bytes are terminal concerns with no meaning to a REST client, so no colour component,
 * control-byte group, leading filler, map geometry, marker byte or edited screen mask appears here.
 * Severity is carried semantically by {@link #submissionAccepted()} and {@link #generalError()}.
 *
 * <p><strong>Immutability and threading.</strong> This is a {@code record}: every component is
 * final, there is no mutator, no builder, no mutable static state and no collection to expose or
 * copy. The only non-{@code String} reference component is {@link NavigationContext}, which is itself
 * an immutable record, so an instance is deeply immutable and safe to publish across threads. There is
 * no canonical constructor body because there is nothing a correct one could do: every component may
 * legitimately be absent, and normalising any of them would corrupt the contract.
 *
 * @param monthlySelection the echoed monthly marker, at most {@value #SELECTION_LENGTH} character
 *     ({@code app/cpy-bms/CORPT00.CPY} line 164). One of the three outbound selector items, which the
 *     map declares separately from their three inbound counterparts at lines 60, 66 and 72. Blank on
 *     every successful acknowledgement, because the program clears the inbound items through its
 *     field-initialisation paragraph at lines 635 to 638 and never writes the outbound ones itself.
 * @param yearlySelection the echoed yearly marker, at most {@value #SELECTION_LENGTH} character
 *     ({@code app/cpy-bms/CORPT00.CPY} line 170), on the same terms.
 * @param customSelection the echoed custom-range marker, at most {@value #SELECTION_LENGTH} character
 *     ({@code app/cpy-bms/CORPT00.CPY} line 176), on the same terms. The three are echoed
 *     independently rather than reduced to one value, because the legacy evaluation at line 213 tests
 *     the three inbound positions in the fixed order monthly, yearly, custom and stops at the first it
 *     finds marked, falling to its catch-all at line 438 when none is. Reducing three inputs to one
 *     value would discard both the submission an operator can actually make - more than one position
 *     marked - and the citation for which position won.
 * @param reportName the short name of the report the selection chose, at most
 *     {@value #REPORT_NAME_LENGTH} characters. This is the ten-character work field declared at
 *     {@code app/cbl/CORPT00C.cbl} line 58 and written at line 214, line 240 and line 433 as the
 *     ordered evaluation reaches each selector in turn, so it is derived from the selection rather than
 *     submitted alongside it - which is why it appears on this contract and not on the request. Its
 *     three values are {@link #REPORT_NAME_MONTHLY}, {@link #REPORT_NAME_YEARLY} and
 *     {@link #REPORT_NAME_CUSTOM}. Two properties are load-bearing and neither may be regularised: a
 *     capital initial letter followed by a lowercase remainder, and no padding out to the field width.
 *     The legacy reads at lines 449 and 468 both consume the field up to its first space, so the
 *     padding never reached an operator while the casing always did. Absent when no selection was made
 *     &mdash; the state answered by {@link #MSG_SELECT_REPORT_TYPE} (line 438) &mdash; and also the
 *     state a successful acknowledgement is cleared to.
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
 * @param confirm the echoed confirmation character. Text and not a truth value, deliberately: the
 *     legacy program recognises three outcomes rather than two, testing the affirmative characters,
 *     then the negative ones, then falling through to quote the operator's own character back
 *     verbatim. A truth value cannot carry the third case, and the quoted text cannot be composed
 *     without the original character. Blank or absent means the operator has not answered yet, which
 *     is what raises the confirmation prompt.
 * @param errorMessage the summary text projected onto the screen message field. Absent on the silent
 *     rejection described above, and populated with the success text on an accepted submission - the
 *     legacy field carried both outcomes and distinguished them only by a highlight, which this
 *     contract replaces with {@link #submissionAccepted()}.
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
 * @param focusScreenFieldId the identity of the field the operator's attention should return to, or
 *     {@code null} when the client should decide. One of the constants below, and an identity only
 *     &mdash; not a row, not a column, not a control byte and not a sentinel number. It travels
 *     independently of which check failed: a successful submission returns attention to the report-type
 *     selector (lines 452 to 453) while a confirmation failure returns it to the confirmation field
 *     (line 472 and line 492), and each date check returns it to the component it rejected. Bounded at
 *     {@value #SCREEN_FIELD_ID_LENGTH} characters, which is the widest identifier the BMS macro
 *     generator can emit: leaving it unbounded let an arbitrary-length value reach a client that will
 *     use it to position a cursor.
 * @param nextRoute the declarative next resource the client may call, carried as an opaque string, or
 *     {@code null} when the request stays on this screen. Opaque on purpose: the vocabulary belongs to
 *     the navigation service, so this record declares no route table, enumeration or dispatch of any
 *     kind, and nothing is forwarded on the server.
 * @param navigationContext the echoed navigation state, or {@code null} when none was carried. Client
 *     held and client returned, never a server session. The screen work area shared by the
 *     five-program family that declares it is deliberately absent: the report program is not a member
 *     of that family.
 * @since 1.0.0
 */
public record ReportResponse(
        @Size(max = ReportResponse.SELECTION_LENGTH) String monthlySelection,
        @Size(max = ReportResponse.SELECTION_LENGTH) String yearlySelection,
        @Size(max = ReportResponse.SELECTION_LENGTH) String customSelection,
        @Size(max = ReportResponse.REPORT_NAME_LENGTH) String reportName,
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
        @Size(max = ReportResponse.SCREEN_FIELD_ID_LENGTH) String focusScreenFieldId,
        String nextRoute,
        NavigationContext navigationContext) {

    public static final int MONTH_LENGTH = 2;

    public static final int DAY_LENGTH = 2;

    public static final int YEAR_LENGTH = 4;

    /**
     * Width in characters of the confirmation field: 1. The bound deliberately does <em>not</em>
     * restrict the value to the affirmative and negative characters: a third character is legitimate
     * input that the legacy program accepts and answers by quoting it back, so constraining the value
     * here would reject input the legacy system handles and make
     * {@link #FRAGMENT_INVALID_CONFIRM_SUFFIX} unreachable.
     */
    public static final int CONFIRM_LENGTH = 1;

    /**
     * Width in characters of each echoed report-type selector: 1 &mdash; the map width of the outbound
     * monthly, yearly and custom items at {@code app/cpy-bms/CORPT00.CPY} lines 164, 170 and 176.
     *
     * <p>Declared separately from {@link #CONFIRM_LENGTH} even though the two values are equal, because
     * the two describe unrelated map items and a later correction to one must not silently move the
     * other. The bound only reports an over-long value: it never trims, pads or re-cases one.
     */
    public static final int SELECTION_LENGTH = 1;

    /**
     * Width in characters of the derived report name: 10 &mdash; the width of the work field declared
     * at {@code app/cbl/CORPT00C.cbl} line 58.
     *
     * <p>This is a <em>program</em> work field rather than a map item, which is why its citation is a
     * program line and not a symbolic-map line. The three values it ever holds are all shorter than the
     * field, and the legacy reads at lines 449 and 468 consume it up to its first space, so the unused
     * remainder never reaches an operator. The bound therefore measures the field, not the values: it
     * exists so that a value wider than the legacy field could ever have held is reported rather than
     * transported.
     */
    public static final int REPORT_NAME_LENGTH = 10;

    /**
     * Width in characters of a screen field identifier: 7.
     *
     * <p>The widest identifier the BMS macro generator can emit for a field name, and therefore the
     * widest value {@link #focusScreenFieldId()} can legitimately carry. The component was previously
     * unbounded, which let a value of arbitrary length reach a client that uses it to position a
     * cursor; the bound closes that without constraining which identifier is chosen.
     */
    public static final int SCREEN_FIELD_ID_LENGTH = 7;

    /**
     * Width in characters of the transaction identity shown in the screen corner: 4 &mdash; the map
     * width at {@code app/cpy-bms/CORPT00.CPY} line 24. Declared separately from
     * {@link #YEAR_LENGTH}, whose value coincides, because a transaction identity and a year are
     * unrelated.
     */
    public static final int TRANSACTION_NAME_LENGTH = 4;

    public static final int SCREEN_TITLE_LENGTH = 40;

    public static final int DATE_LENGTH = 8;

    public static final int TIME_LENGTH = 8;

    public static final int PROGRAM_NAME_LENGTH = 8;

    /**
     * Width in characters of the screen message field: 78. This is a genuinely different width from
     * the wider message field several other screens in the estate use, and the difference is easy to
     * carry over by mistake because every other aspect of their message handling is identical. The
     * legacy work field the text was composed in is wider than the field it was displayed in, so a
     * long text was cut when it reached the screen - which is why {@link #message()} is unbounded
     * while {@link #errorMessage()} carries this bound. The bound measures without altering, so no
     * character is removed by validation.
     */
    public static final int ERROR_MESSAGE_LENGTH = 78;

    /*
     * The operator-visible texts. Every text below is reproduced character for character from
     * app/cbl/CORPT00C.cbl, because preserving them is an interface-contract obligation: a single
     * misplaced space, a lowered capital or a fourth dot is an observable regression.
     *
     * Three properties of the set are load bearing and easy to lose.
     *
     *   1. The casing is inconsistent between the groups and must stay inconsistent. The six
     *      emptiness texts write the negation in full capitals, which no other text does; all twelve
     *      component texts capitalise the component name, while the two calendar texts leave their
     *      final word in lower case.
     *
     *   2. The three composed texts are published as separate fragments and are never pre-joined
     *      here, because the value that sits between them is only known at request time. Assembly
     *      belongs to the report-request service; this record neither joins, formats nor trims.
     *
     *   3. Two fragments differ by one space and are otherwise identical. They are declared
     *      separately and neither is derived from the other. See the notes on each.
     */

    /** Rejection text when the start-of-range month was not supplied. */
    public static final String MSG_START_DATE_MONTH_EMPTY = "Start Date - Month can NOT be empty...";

    /** Rejection text when the start-of-range day was not supplied. */
    public static final String MSG_START_DATE_DAY_EMPTY = "Start Date - Day can NOT be empty...";

    /** Rejection text when the start-of-range year was not supplied. */
    public static final String MSG_START_DATE_YEAR_EMPTY = "Start Date - Year can NOT be empty...";

    /** Rejection text when the end-of-range month was not supplied. */
    public static final String MSG_END_DATE_MONTH_EMPTY = "End Date - Month can NOT be empty...";

    /** Rejection text when the end-of-range day was not supplied. */
    public static final String MSG_END_DATE_DAY_EMPTY = "End Date - Day can NOT be empty...";

    /** Rejection text when the end-of-range year was not supplied. */
    public static final String MSG_END_DATE_YEAR_EMPTY = "End Date - Year can NOT be empty...";

    /** Rejection text when the start-of-range month is not numeric or exceeds the twelfth month. */
    public static final String MSG_START_DATE_MONTH_INVALID = "Start Date - Not a valid Month...";

    /** Rejection text when the start-of-range day is not numeric or exceeds the thirty-first day. */
    public static final String MSG_START_DATE_DAY_INVALID = "Start Date - Not a valid Day...";

    /** Rejection text when the start-of-range year is not numeric. */
    public static final String MSG_START_DATE_YEAR_INVALID = "Start Date - Not a valid Year...";

    /** Rejection text when the end-of-range month is not numeric or exceeds the twelfth month. */
    public static final String MSG_END_DATE_MONTH_INVALID = "End Date - Not a valid Month...";

    /** Rejection text when the end-of-range day is not numeric or exceeds the thirty-first day. */
    public static final String MSG_END_DATE_DAY_INVALID = "End Date - Not a valid Day...";

    /** Rejection text when the end-of-range year is not numeric. */
    public static final String MSG_END_DATE_YEAR_INVALID = "End Date - Not a valid Year...";

    /**
     * Rejection text when the assembled start-of-range value is not a real calendar day. It belongs to
     * the separate calendar stage that follows the component checks and is raised only when that stage
     * reports a non-zero severity whose result number is not the one number the legacy program
     * tolerates - so a value can pass every component check and still be rejected here, and a non-zero
     * severity does not on its own produce this text.
     */
    public static final String MSG_START_DATE_INVALID = "Start Date - Not a valid date...";

    /** Rejection text when the assembled end-of-range value is not a real calendar day. */
    public static final String MSG_END_DATE_INVALID = "End Date - Not a valid date...";

    /**
     * Rejection text when no report type was selected at all. The catch-all outcome of the legacy
     * ordered evaluation of the three selectors, and an input-validation result rather than a fourth
     * report type - which is why {@link #period()} is simply absent when a client receives this text.
     */
    public static final String MSG_SELECT_REPORT_TYPE = "Select a report type to print report...";

    /**
     * Trailing fragment of the acceptance text, appended after the selected report name.
     *
     * <p><strong>This fragment has a leading space and a space before its three dots.</strong>
     * {@link #FRAGMENT_CONFIRM_PROMPT_SUFFIX} looks interchangeable with it and is not: that one has
     * the leading space but no space before its dots. Unifying the two, or deriving either from the
     * other, shifts the assembled text by one byte and fails the interface-contract check.</p>
     *
     * <p>The report name that precedes it is {@link #reportName()}. The legacy composition
     * consumed the report-name work field up to its first space, so the trailing padding of that
     * fixed-width field never reached the text, which is precisely why the enumeration carries bare
     * unpadded values. The service places the value and this fragment adjacently with nothing between
     * them.
     */
    public static final String FRAGMENT_SUBMITTED_SUFFIX = " report submitted for printing ...";

    /**
     * The report name the program writes when the monthly selector is the first one found marked.
     * {@code app/cbl/CORPT00C.cbl} line 214, reached from the evaluation clause at line 213.
     *
     * <p><strong>Capital initial letter, lowercase remainder, and no padding.</strong> All three are
     * contract. The value is shorter than the {@value #REPORT_NAME_LENGTH}-character work field that
     * holds it, and the two legacy reads consume that field up to its first space, so the unused
     * remainder never reached an operator while the casing always did. It must not be upper-cased,
     * lower-cased, padded or derived from any identifier.
     */
    public static final String REPORT_NAME_MONTHLY = "Monthly";

    /**
     * The report name the program writes when the yearly selector is the first one found marked.
     * {@code app/cbl/CORPT00C.cbl} line 240, reached from the evaluation clause at line 239.
     *
     * <p>Same casing and padding contract as {@link #REPORT_NAME_MONTHLY}.
     */
    public static final String REPORT_NAME_YEARLY = "Yearly";

    /**
     * The report name the program writes when the custom-range selector is the first one found marked.
     * {@code app/cbl/CORPT00C.cbl} line 433, reached from the evaluation clause at line 256 by way of
     * the date-range validation cascade.
     *
     * <p>Same casing and padding contract as {@link #REPORT_NAME_MONTHLY}. This is the only one of the
     * three whose clause depends on the six date parts having passed validation first, which is why its
     * assignment sits far below the other two rather than beside them.
     */
    public static final String REPORT_NAME_CUSTOM = "Custom";

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
     * the two fragments is {@link #reportName()}, consumed up to its first space by the
     * legacy composition exactly as in the acceptance text (line 468).
     */
    public static final String FRAGMENT_CONFIRM_PROMPT_SUFFIX = " report...";

    /**
     * Opening fragment of the invalid-confirmation text: one straight double-quote character, not a
     * typographic one. The operator's own character is quoted back between this fragment and
     * {@link #FRAGMENT_INVALID_CONFIRM_SUFFIX}, consumed up to its first space, which is why
     * {@link #confirm()} is carried as text rather than as a truth value.
     */
    public static final String FRAGMENT_INVALID_CONFIRM_PREFIX = "\"";

    /**
     * Closing fragment of the invalid-confirmation text. <strong>The closing double-quote is part of
     * this fragment</strong>, not a separate one, so the service appends exactly this string after the
     * operator's character and adds no quote of its own. Raised only for a third character: the
     * affirmative characters proceed and the negative ones take the silent-rejection branch, which
     * produces no text at all.
     */
    public static final String FRAGMENT_INVALID_CONFIRM_SUFFIX =
            "\" is not a valid value to confirm...";

    /*
     * ---------------------------------------------------------------------------------------------
     * THE FIELD IDENTITIES USED BY focusScreenFieldId
     * ---------------------------------------------------------------------------------------------
     * Each value is the identifier the generated symbolic map gives the field, read from
     * app/cpy-bms/CORPT00.CPY: MONTHLY at line 55, SDTMM at 73, SDTDD at 79, SDTYYYY at 85, EDTMM at
     * 91, EDTDD at 97, EDTYYYY at 103 and CONFIRM at 109, each corroborated by its matching input item
     * at lines 60, 78, 84, 90, 96, 102, 108 and 114.
     *
     * Map identifiers and not property names, and the distinction is what makes the bound on
     * focusScreenFieldId meaningful. Every one of these eight names is at most seven characters, which
     * is the ceiling the generator imposes and the exact bound the component declares; a property name
     * such as the sixteen-character name of the monthly selector would breach that bound and make the
     * contract contradict itself. It is also the vocabulary the rest of this package already uses -
     * the sign-on, card and bill-payment contracts all publish seven-character map identifiers for the
     * same purpose - so a client can read focusScreenFieldId the same way on every response instead of
     * one way here and another way everywhere else.
     *
     * These are identities and nothing else. There is no row, no column, no control byte and no
     * sentinel number here, and a client that has its own focus policy may ignore them entirely.
     * ---------------------------------------------------------------------------------------------
     */

    /**
     * Identity of the monthly selector, the map item {@code MONTHLY} at line 55, which is where
     * attention returns for the selector block as a whole. Attention returns here after an accepted submission &mdash; first by the
     * initialise-all-fields paragraph at line 633 when it is performed at line 447, then again at line
     * 453 once the acceptance text has been composed &mdash; and also when no report type was selected
     * at all (line 441).
     *
     * <p><strong>It is the monthly field specifically, not an abstract report-type selector.</strong>
     * The program places the cursor at seven sites and every one of them names the monthly field: lines
     * 180, 192, 441, 453, 533 and 635. It never places the cursor on the yearly or custom positions,
     * which is why there is no identity constant for either. That asymmetry is contract, and inventing
     * the two missing constants would offer a client a focus target the legacy screen never used.
     */
    public static final String FIELD_MONTHLY_SELECTION = "MONTHLY";

    /**
     * Identity of the start-of-range month, the map item {@code SDTMM} at line 73. Attention returns
     * here from lines 264 and 334.
     */
    public static final String FIELD_START_MONTH = "SDTMM";

    /**
     * Identity of the start-of-range day, the map item {@code SDTDD} at line 79. Attention returns
     * here from lines 271 and 343.
     */
    public static final String FIELD_START_DAY = "SDTDD";

    /**
     * Identity of the start-of-range year, the map item {@code SDTYYYY} at line 85. Attention returns
     * here from lines 278 and 351.
     */
    public static final String FIELD_START_YEAR = "SDTYYYY";

    /**
     * Identity of the end-of-range month, the map item {@code EDTMM} at line 91. Attention returns
     * here from lines 285 and 360.
     */
    public static final String FIELD_END_MONTH = "EDTMM";

    /**
     * Identity of the end-of-range day, the map item {@code EDTDD} at line 97. Attention returns here
     * from lines 292 and 369.
     */
    public static final String FIELD_END_DAY = "EDTDD";

    /**
     * Identity of the end-of-range year, the map item {@code EDTYYYY} at line 103. Attention returns
     * here from lines 299 and 377.
     */
    public static final String FIELD_END_YEAR = "EDTYYYY";

    /**
     * Identity of the confirmation field, the map item {@code CONFIRM} at line 109. Attention returns
     * here when the prompt is raised (line 472)
     * and when the supplied character is neither affirmative nor negative (line 492) &mdash; the two
     * cases that prove focus travels independently of which check failed.
     */
    public static final String FIELD_CONFIRM = "CONFIRM";
}
