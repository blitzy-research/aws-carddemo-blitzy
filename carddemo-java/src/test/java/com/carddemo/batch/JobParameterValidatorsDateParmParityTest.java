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
package com.carddemo.batch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.carddemo.batch.JobParameterValidators.ReportDateWindow;
import com.carddemo.exception.ValidationException;
import com.carddemo.service.DateValidationService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Verifies that {@link JobParameterValidators#parseDateParmRecord(String)} reproduces the legacy
 * eighty-to-twenty-one byte move rather than imposing a stricter contract of its own.
 *
 * <p>The legacy read is {@code READ DATE-PARMS-FILE INTO WS-DATEPARM-RECORD} at
 * {@code [app/cbl/CBTRN03C.cbl:L221]}. The sending item is the record area
 * {@code FD-DATEPARM-REC PIC X(80)} at {@code [app/cbl/CBTRN03C.cbl:L88]}; the receiving item is the
 * group {@code WS-DATEPARM-RECORD} at {@code [app/cbl/CBTRN03C.cbl:L122]}, which is a ten-character
 * start date, a one-character filler and a ten-character end date - twenty-one bytes in total. A COBOL
 * group move from a longer sender into a shorter receiver keeps the sender's leading bytes and discards
 * the remainder, silently.</p>
 *
 * <p>The behaviour that distinguishes the corrected implementation from its predecessor is
 * {@link IgnoringWhatTheGroupCannotHold} below. A record carrying content past position twenty-one used
 * to be refused; it is now truncated, because that is what the move does. The refusal was a behavioural
 * regression: it rejected input the mainframe accepted. Everything else here is unchanged and is tested
 * so that removing the refusal cannot be mistaken for loosening the layout.</p>
 */
@DisplayName("JobParameterValidators.parseDateParmRecord - the 80-to-21 byte move")
class JobParameterValidatorsDateParmParityTest {

    /** A well-formed start date. */
    private static final String START_DATE = "2022-06-01";

    /** A well-formed end date, later than the start date. */
    private static final String END_DATE = "2022-06-30";

    /** The twenty-one bytes the receiving group holds: date, separator, date. */
    private static final String GROUP = START_DATE + " " + END_DATE;

    /** The width of the record area the legacy declares. */
    private static final int RECORD_AREA_WIDTH = 80;

    /** The width of the group the legacy reads into. */
    private static final int GROUP_WIDTH = 21;

    private JobParameterValidators validators;

    @BeforeEach
    void setUp() {
        validators = new JobParameterValidators(new DateValidationService());
    }

    /** Pads a value out to the eighty-byte record area with spaces, as a real record arrives. */
    private static String asRecordArea(String content) {
        return content + " ".repeat(RECORD_AREA_WIDTH - content.length());
    }

    @Nested
    @DisplayName("reading the group")
    class ReadingTheGroup {

        @Test
        @DisplayName("the bare twenty-one-byte group parses to the window it carries")
        void theBareGroupParses() {
            assertThat(GROUP).hasSize(GROUP_WIDTH);

            ReportDateWindow window = validators.parseDateParmRecord(GROUP);

            assertThat(window.startDate()).isEqualTo(START_DATE);
            assertThat(window.endDate()).isEqualTo(END_DATE);
        }

        @Test
        @DisplayName("the full eighty-byte record area parses to the same window, because only the "
                + "leading twenty-one bytes reach the group")
        void theFullRecordAreaParses() {
            String recordArea = asRecordArea(GROUP);
            assertThat(recordArea).hasSize(RECORD_AREA_WIDTH);

            ReportDateWindow window = validators.parseDateParmRecord(recordArea);

            assertThat(window.startDate()).isEqualTo(START_DATE);
            assertThat(window.endDate()).isEqualTo(END_DATE);
        }

        @Test
        @DisplayName("both bounds are inclusive, so a single-day window whose bounds are equal is "
                + "accepted")
        void anEqualBoundedWindowIsAccepted() {
            ReportDateWindow window = validators.parseDateParmRecord(START_DATE + " " + START_DATE);

            assertThat(window.startDate()).isEqualTo(START_DATE);
            assertThat(window.endDate()).isEqualTo(START_DATE);
        }
    }

    @Nested
    @DisplayName("ignoring what the group cannot hold")
    class IgnoringWhatTheGroupCannotHold {

        @Test
        @DisplayName("content beyond position twenty-one is discarded rather than refused, which is "
                + "what the group move does")
        void contentBeyondTheGroupIsDiscarded() {
            String withTrailingContent = asRecordArea(GROUP + "STRAY CONTENT 9999");

            ReportDateWindow window = validators.parseDateParmRecord(withTrailingContent);

            assertThat(window.startDate()).isEqualTo(START_DATE);
            assertThat(window.endDate()).isEqualTo(END_DATE);
        }

        @Test
        @DisplayName("a record area filled edge to edge with content still yields only the leading "
                + "twenty-one bytes")
        void aFullyPopulatedRecordAreaYieldsOnlyTheGroup() {
            String saturated = GROUP + "X".repeat(RECORD_AREA_WIDTH - GROUP_WIDTH);
            assertThat(saturated).hasSize(RECORD_AREA_WIDTH);

            ReportDateWindow window = validators.parseDateParmRecord(saturated);

            assertThat(window.startDate()).isEqualTo(START_DATE);
            assertThat(window.endDate()).isEqualTo(END_DATE);
        }

        @Test
        @DisplayName("the bare group, the space-padded record area and a record area carrying stray "
                + "content all produce one identical window - which is the move's defining property")
        void everyLongerSenderProducesTheSameWindow() {
            ReportDateWindow fromGroup = validators.parseDateParmRecord(GROUP);
            ReportDateWindow fromPadded = validators.parseDateParmRecord(asRecordArea(GROUP));
            ReportDateWindow fromStray =
                    validators.parseDateParmRecord(asRecordArea(GROUP + "IGNORED"));

            assertThat(fromPadded).isEqualTo(fromGroup);
            assertThat(fromStray).isEqualTo(fromGroup);
        }

        @Test
        @DisplayName("a sender longer than the declared record area is still read, because the move "
                + "is bounded by the receiving group and not by the sender")
        void aSenderLongerThanTheRecordAreaIsStillRead() {
            String overlong = GROUP + "Z".repeat(200);

            ReportDateWindow window = validators.parseDateParmRecord(overlong);

            assertThat(window.startDate()).isEqualTo(START_DATE);
            assertThat(window.endDate()).isEqualTo(END_DATE);
        }
    }

    @Nested
    @DisplayName("refusing a sender too small to fill the group")
    class RefusingASenderTooSmallToFillTheGroup {

        @Test
        @DisplayName("a record one byte short of the group is refused, and the message names the "
                + "twenty-one bytes the group occupies")
        void aRecordOneByteShortIsRefused() {
            String tooShort = GROUP.substring(0, GROUP_WIDTH - 1);

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> validators.parseDateParmRecord(tooShort))
                    .withMessageContaining(String.valueOf(GROUP_WIDTH))
                    .withMessageContaining("at least");
        }

        @Test
        @DisplayName("an empty record is refused")
        void anEmptyRecordIsRefused() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> validators.parseDateParmRecord(""))
                    .withMessageContaining(String.valueOf(GROUP_WIDTH));
        }

        @Test
        @DisplayName("a record of twenty spaces is refused for width before any date is examined, "
                + "because twenty bytes cannot fill a twenty-one byte group")
        void twentySpacesAreRefusedForWidth() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> validators.parseDateParmRecord(" ".repeat(20)))
                    .withMessageContaining("at least");
        }

        @Test
        @DisplayName("an absent record is refused as missing rather than as malformed")
        void anAbsentRecordIsRefused() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> validators.parseDateParmRecord(null))
                    .withMessageContaining("was not supplied");
        }
    }

    @Nested
    @DisplayName("validating the group it did read")
    class ValidatingTheGroupItDidRead {

        @Test
        @DisplayName("the eleventh position must carry the separator, and a digit there is refused")
        void theSeparatorPositionIsEnforced() {
            String noSeparator = START_DATE + "9" + END_DATE;
            assertThat(noSeparator).hasSize(GROUP_WIDTH);

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> validators.parseDateParmRecord(noSeparator))
                    .withMessageContaining("separator")
                    .withMessageContaining("11");
        }

        @Test
        @DisplayName("a start date that is not a real calendar date is refused, even though it "
                + "occupies the right positions")
        void anImpossibleStartDateIsRefused() {
            String impossible = "2022-02-30" + " " + END_DATE;

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> validators.parseDateParmRecord(impossible));
        }

        @Test
        @DisplayName("a window whose end precedes its start is refused, because the legacy range is "
                + "inclusive and ordered")
        void aReversedWindowIsRefused() {
            String reversed = END_DATE + " " + START_DATE;

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> validators.parseDateParmRecord(reversed));
        }

        @Test
        @DisplayName("a blank group of exactly twenty-one spaces passes the width and separator "
                + "tests and is then refused on its dates")
        void aBlankGroupIsRefusedOnItsDates() {
            String blankGroup = " ".repeat(GROUP_WIDTH);

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> validators.parseDateParmRecord(blankGroup));
        }

        @Test
        @DisplayName("a character outside the single-byte set the record area reserves bytes for is "
                + "refused, because it could not occupy a declared position")
        void aMultiByteCharacterIsRefused() {
            String multiByte = "2022-06-0\u20ac" + " " + END_DATE;

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> validators.parseDateParmRecord(multiByte))
                    .withMessageContaining("single-byte");
        }

        @Test
        @DisplayName("the separator and date tests apply to the group only, so an invalid byte "
                + "beyond position twenty-one cannot make a well-formed record fail")
        void bytesBeyondTheGroupCannotCauseAFailure() {
            String strayNonDate = asRecordArea(GROUP + "!!not-a-date!!");

            ReportDateWindow window = validators.parseDateParmRecord(strayNonDate);

            assertThat(window.startDate()).isEqualTo(START_DATE);
            assertThat(window.endDate()).isEqualTo(END_DATE);
        }
    }
}
