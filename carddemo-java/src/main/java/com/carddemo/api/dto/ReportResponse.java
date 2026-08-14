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
import java.util.List;

/**
 * Immutable acknowledgement of a transaction-report request, legacy transaction {@code CR00}: the one
 * screen that transaction presented, re-presented after every rejection and presented once more,
 * cleared, after a successful submission.
 *
 * <p>The submission it acknowledges is a queue publish whose failure is non-fatal, so a response may
 * report a failure message while the request itself completed normally - which is the legacy
 * ignore-on-error behaviour and not an inconsistency.
 *
 * <p>The report name is the literal the operator reads back, carried at the legacy work field's width
 * and never derived from an enum constant name. The focus field identifier is bounded at the widest
 * name the screen macro generator can emit, so a client that positions a cursor from it cannot be
 * handed a value of arbitrary length.
 *
 * <p>Nothing here is validated, defaulted or normalised, and no diagnostic detail reaches this
 * payload: the failure text is the frozen operator-facing literal and nothing else.
 */
public record ReportResponse(
        /*
         * The three report-type selector positions as they stand when the turn ends, in the order the
         * symbolic map declares them: MONTHLYI at line 60, YEARLYI at line 66, CUSTOMI at line 72.
         *
         * They are published, and published separately, for the same reason the request carries them
         * separately. The legacy screen re-presents whatever the operator marked: the reset paragraph
         * INITIALIZE-ALL-FIELDS at [app/cbl/CORPT00C.cbl:L633-L646] blanks all three along with the six
         * date parts and the confirmation, so a successful submission and a declined confirmation both
         * come back cleared, while every error path comes back with the transmitted marks still
         * standing. A response carrying only the resolved period cannot express either state: it cannot
         * say "the operator's monthly and custom marks are both still marked", and it cannot
         * distinguish a cleared screen from one whose single mark survived. A client would have to
         * guess, and guessing is how a re-presented screen loses a mark the operator made.
         *
         * These do not duplicate reportPeriod below. These three are what the operator SEES; that one
         * is what the service RESOLVED by the ordered evaluation at lines 214, 240 and 256. The two
         * genuinely differ - an error turn can re-present three marks while the period is absent, and a
         * turn can publish the month-to-date period while a stale custom mark is still standing - so
         * both are carried and neither is derived from the other.
         */
        @Size(max = ReportResponse.SELECTION_LENGTH) String monthlySelection,
        @Size(max = ReportResponse.SELECTION_LENGTH) String yearlySelection,
        @Size(max = ReportResponse.SELECTION_LENGTH) String customSelection,
        ReportPeriod reportPeriod,
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

        /* The field-level detail behind the flag above, in the order the turn established it. Never
         * null; empty when the turn faulted nothing.
         *
         * Two states rather than one, because the legacy screen distinguishes a field the operator left
         * blank from one that was supplied and failed its edit: the first is highlighted AND marked with
         * an asterisk, the second is only highlighted. This screen raises both kinds - a report type
         * that was never marked and a date part that was marked and cannot be used are different
         * failures - and the whole-screen flag beside this list cannot say which occurred, nor on which
         * of the ten input positions. Without this component a client re-presenting the screen can
         * highlight nothing, so the operator is told only that something is wrong. */
        List<ErrorResponse.FieldError> fieldErrors,

        @Size(max = ReportResponse.SCREEN_FIELD_ID_LENGTH) String focusScreenFieldId,
        String nextRoute,
        NavigationContext navigationContext) {

    /**
     * Canonical constructor. Normalises the field-error list and leaves every other component exactly
     * as supplied.
     *
     * <p>An absent list becomes an empty one, and a supplied one is copied into an unmodifiable view.
     * Normalising rather than admitting a null is what makes the component's two meanings distinguishable
     * on the wire: because the module omits nulls and writes empties, an empty list is published as
     * {@code []} and a reader can tell "this turn faulted no field" from "this reply says nothing about
     * fields". A null would collapse both onto absence.
     *
     * <p>No string is trimmed, padded, re-cased or reordered here. The three selector positions and the
     * six date parts are re-presented exactly as the operator transmitted them, and the field-error
     * entries keep the order the turn established.
     */
    public ReportResponse {
        fieldErrors = (fieldErrors == null) ? List.of() : List.copyOf(fieldErrors);
    }

    /**
     * Declared width of each report-type selector position, one character, from the symbolic map
     * {@code app/cpy-bms/CORPT00.CPY} lines 60, 66 and 72.
     */
    public static final int SELECTION_LENGTH = 1;

    public static final int MONTH_LENGTH = 2;

    public static final int DAY_LENGTH = 2;

    public static final int YEAR_LENGTH = 4;

    public static final int CONFIRM_LENGTH = 1;

    /**
     * Width in characters of a screen field identifier: 7.
     *
     * <p>The widest identifier the BMS macro generator can emit for a field name, taken from the
     * generated symbolic maps under {@code app/cpy-bms}, and therefore the widest value
     * {@link #focusScreenFieldId()} can legitimately carry. The bound keeps a value of arbitrary length
     * from reaching a client that uses it to position a cursor, and constrains no choice of identifier.
     */
    public static final int SCREEN_FIELD_ID_LENGTH = 7;

    public static final int TRANSACTION_NAME_LENGTH = 4;

    public static final int SCREEN_TITLE_LENGTH = 40;

    public static final int DATE_LENGTH = 8;

    public static final int TIME_LENGTH = 8;

    public static final int PROGRAM_NAME_LENGTH = 8;

    public static final int ERROR_MESSAGE_LENGTH = 78;

    public static final String MSG_START_DATE_MONTH_EMPTY = "Start Date - Month can NOT be empty...";

    public static final String MSG_START_DATE_DAY_EMPTY = "Start Date - Day can NOT be empty...";

    public static final String MSG_START_DATE_YEAR_EMPTY = "Start Date - Year can NOT be empty...";

    public static final String MSG_END_DATE_MONTH_EMPTY = "End Date - Month can NOT be empty...";

    public static final String MSG_END_DATE_DAY_EMPTY = "End Date - Day can NOT be empty...";

    public static final String MSG_END_DATE_YEAR_EMPTY = "End Date - Year can NOT be empty...";

    public static final String MSG_START_DATE_MONTH_INVALID = "Start Date - Not a valid Month...";

    public static final String MSG_START_DATE_DAY_INVALID = "Start Date - Not a valid Day...";

    public static final String MSG_START_DATE_YEAR_INVALID = "Start Date - Not a valid Year...";

    public static final String MSG_END_DATE_MONTH_INVALID = "End Date - Not a valid Month...";

    public static final String MSG_END_DATE_DAY_INVALID = "End Date - Not a valid Day...";

    public static final String MSG_END_DATE_YEAR_INVALID = "End Date - Not a valid Year...";

    public static final String MSG_START_DATE_INVALID = "Start Date - Not a valid date...";

    public static final String MSG_END_DATE_INVALID = "End Date - Not a valid date...";

    /**
     * Rejection text when no report type was selected at all. The catch-all outcome of the legacy
     * ordered evaluation of the three screen positions, and an input-validation result rather than a
     * fourth report type - which is why {@link #reportPeriod()} is simply absent when a client receives
     * this text, and why the period vocabulary carries no member standing for absence.
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
     * <p>The report name that precedes it is the carried value of {@link #reportPeriod()}. The legacy
     * composition consumed the report-name work item up to its first space, so the trailing padding of
     * that fixed-width item never reached the text, which is precisely why the period vocabulary carries
     * bare unpadded values. The service places the value and this fragment adjacently with nothing
     * between them.
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
     * the two fragments is the carried value of {@link #reportPeriod()}, consumed up to its first space
     * by the legacy composition exactly as in the acceptance text (line 468).
     */
    public static final String FRAGMENT_CONFIRM_PROMPT_SUFFIX = " report...";

    public static final String FRAGMENT_INVALID_CONFIRM_PREFIX = "\"";

    public static final String FRAGMENT_INVALID_CONFIRM_SUFFIX =
            "\" is not a valid value to confirm...";

    public static final String FIELD_MONTHLY_SELECTION = "MONTHLY";

    public static final String FIELD_START_MONTH = "SDTMM";

    public static final String FIELD_START_DAY = "SDTDD";

    public static final String FIELD_START_YEAR = "SDTYYYY";

    public static final String FIELD_END_MONTH = "EDTMM";

    public static final String FIELD_END_DAY = "EDTDD";

    public static final String FIELD_END_YEAR = "EDTYYYY";

    public static final String FIELD_CONFIRM = "CONFIRM";
}
