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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.carddemo.api.dto.NavigationContext;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.domain.enums.ReportPeriod;
import com.carddemo.util.JclCardImageBuilder;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

/**
 * Unit test for {@link ReportRequestService}, the translation of the report-request transaction
 * {@code CR00} carried by {@code app/cbl/CORPT00C.cbl}.
 *
 * <p><strong>What is under test.</strong> One pseudo-conversational turn. The service decides, from
 * the navigation state and the attention key alone, whether to route away without a screen, send the
 * screen for a first entry, or receive and process a submitted screen. On a processed submission it
 * selects one of three reporting periods, gates the submission behind an explicit confirmation, and
 * publishes the job-submission card image one card at a time.
 *
 * <p><strong>Collaborators.</strong> The date validator, the message catalogue and the navigation
 * rules are the real classes, because each is a pure decision this service must agree with rather
 * than a boundary to be stubbed - substituting them would let this suite pass while the module
 * disagreed with itself. Only {@link JobSubmissionService} is a test double, because it publishes to
 * a real queue; its per-card contract is a boolean, so a double states card-by-card success or
 * failure exactly as the queue does. The clock is fixed so the rendered header is deterministic.
 *
 * <p><strong>The seventeen-card count is the legacy contract.</strong> A submission is accepted only
 * when every one of {@link JclCardImageBuilder#CARD_COUNT} cards reaches the queue, which is why the
 * counts below are asserted against that constant rather than against a repeated literal.
 *
 * <p><strong>Provenance.</strong> Legacy checkout SHA 7756d895ffeb65f7ea72aaa609e356d9899afcec;
 * upstream release stamp CardDemo_v1.0-15-g27d6c6f-68 (2022-07-19).
 */
@DisplayName("ReportRequestService - the CR00 report-request turn")
class ReportRequestServiceTest {

    /** A fixed instant, so the rendered date and time are the same on every run and every host. */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2022-07-19T23:12:33Z"), ZoneOffset.UTC);

    /** The affirmative confirmation entry the screen accepts. */
    private static final String CONFIRM_YES = "Y";

    /** The selection character the screen writes into whichever period the operator chose. */
    private static final String SELECTED = "Y";

    /** A publishing double that reports every card as written. */
    private static JobSubmissionService publishingEveryCard() {
        final JobSubmissionService publisher = Mockito.mock(JobSubmissionService.class);
        Mockito.when(publisher.writeJobSubmissionQueue(ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(), ArgumentMatchers.anyInt())).thenReturn(true);
        return publisher;
    }

    /** A publishing double that reports every card as refused, which is the ignore-on-error path. */
    private static JobSubmissionService publishingNoCard() {
        final JobSubmissionService publisher = Mockito.mock(JobSubmissionService.class);
        Mockito.when(publisher.writeJobSubmissionQueue(ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(), ArgumentMatchers.anyInt())).thenReturn(false);
        return publisher;
    }

    /** Builds the service over the real decision collaborators and the supplied publisher. */
    private static ReportRequestService serviceWith(final JobSubmissionService publisher) {
        return new ReportRequestService(new DateValidationService(), publisher,
                new MessageCatalogService(), new NavigationService(), FIXED_CLOCK);
    }

    /** A turn carrying navigation state that has already been through one entry. */
    private static NavigationContext returningContext() {
        return signedOnState(NavigationContext.ProgramContext.REENTER);
    }

    /**
     * Builds a signed-on navigation state.
     *
     * <p>A context equal to {@link NavigationContext#empty()} is what the service reads as an absent
     * communication area, so a turn that is present but has not yet been through the screen must
     * carry real state with its program context still on first entry.
     *
     * @param programContext whether this turn is a first entry or a re-entry
     * @return the assembled navigation state
     */
    private static NavigationContext signedOnState(
            final NavigationContext.ProgramContext programContext) {
        return new NavigationContext("CR00", "COSGN00C", null, null, "USER0001", "U",
                programContext, "000000042", "MARY", null, "SMITH", "00000000042", "Y",
                "4111111111111111", "CORPT0A", "CORPT00");
    }

    /**
     * Builds a screen input.
     *
     * @param monthly  the monthly selection entry, or {@code null}
     * @param yearly   the yearly selection entry, or {@code null}
     * @param custom   the custom selection entry, or {@code null}
     * @param confirm  the confirmation entry, or {@code null}
     * @param key      the attention key the turn arrived on
     * @param context  the navigation state
     * @return the assembled input
     */
    private static ReportRequestService.ReportScreenInput input(final String monthly,
            final String yearly, final String custom, final String confirm, final KeyAction key,
            final NavigationContext context) {
        return new ReportRequestService.ReportScreenInput(monthly, yearly, custom,
                null, null, null, null, null, null, confirm, key, context);
    }

    /** Builds a custom-period input carrying an explicit start and end date. */
    private static ReportRequestService.ReportScreenInput customInput(final String startMonth,
            final String startDay, final String startYear, final String endMonth,
            final String endDay, final String endYear, final String confirm) {
        return new ReportRequestService.ReportScreenInput(null, null, SELECTED,
                startMonth, startDay, startYear, endMonth, endDay, endYear, confirm,
                KeyAction.ENTER, returningContext());
    }

    @Nested
    @DisplayName("Turn entry")
    class TurnEntry {

        @Test
        @DisplayName("a turn carrying no navigation state routes to the sign-on screen without "
                + "sending this screen, which is what an absent communication area means")
        void aTurnCarryingNoStateRoutesToSignOn() {
            final ReportRequestService.ReportRequestResult result =
                    serviceWith(publishingEveryCard()).processReportRequest(
                            input(null, null, null, null, KeyAction.ENTER, null));

            assertThat(result.route()).isNotNull();
            assertThat(result.cardsPublished()).isZero();
            assertThat(result.submissionAccepted()).isFalse();
        }

        @Test
        @DisplayName("a first entry sends the screen, positions the cursor on the monthly selection "
                + "and publishes nothing, because nothing has been submitted yet")
        void aFirstEntrySendsTheScreenAndPublishesNothing() {
            final ReportRequestService.ReportRequestResult result =
                    serviceWith(publishingEveryCard()).processReportRequest(
                            input(null, null, null, null, KeyAction.ENTER,
                                    signedOnState(NavigationContext.ProgramContext.ENTER)));

            assertThat(result.focusField()).isNotNull();
            assertThat(result.cardsPublished()).isZero();
            assertThat(result.errorFlag()).isFalse();
            assertThat(result.header()).isNotNull();
            assertThat(result.header().currentDate()).isNotBlank();
            assertThat(result.header().currentTime()).isNotBlank();
        }

        @Test
        @DisplayName("the rendered header carries both title lines from the catalogue that owns them")
        void theRenderedHeaderCarriesBothTitleLines() {
            final ReportRequestService.ReportRequestResult result =
                    serviceWith(publishingEveryCard()).processReportRequest(
                            input(null, null, null, null, KeyAction.ENTER,
                                    signedOnState(NavigationContext.ProgramContext.ENTER)));

            assertThat(result.header().title01())
                    .isEqualTo(new MessageCatalogService().screenTitle01());
            assertThat(result.header().title02())
                    .isEqualTo(new MessageCatalogService().screenTitle02());
        }

        @Test
        @DisplayName("a null input is refused rather than defaulted")
        void aNullInputIsRefused() {
            final ReportRequestService service = serviceWith(publishingEveryCard());

            assertThatNullPointerException()
                    .isThrownBy(() -> service.processReportRequest(null));
        }
    }

    @Nested
    @DisplayName("Attention keys")
    class AttentionKeys {

        @Test
        @DisplayName("the exit key leaves for the calling menu and submits nothing")
        void theExitKeyLeavesForTheCallingMenu() {
            final ReportRequestService.ReportRequestResult result =
                    serviceWith(publishingEveryCard()).processReportRequest(
                            input(null, null, null, null, KeyAction.PFK03, returningContext()));

            assertThat(result.route()).isNotNull();
            assertThat(result.cardsPublished()).isZero();
            assertThat(result.submissionAccepted()).isFalse();
        }

        @Test
        @DisplayName("a key the screen does not map reports the common invalid-key message and "
                + "submits nothing, rather than being treated as an enter")
        void anUnmappedKeyReportsTheInvalidKeyMessage() {
            final ReportRequestService.ReportRequestResult result =
                    serviceWith(publishingEveryCard()).processReportRequest(
                            input(SELECTED, null, null, CONFIRM_YES, KeyAction.PFK12,
                                    returningContext()));

            assertThat(result.message())
                    .as("the message is carried at the width the common-message copybook declares, "
                            + "so it is compared untrimmed")
                    .isEqualTo(new MessageCatalogService().invalidKeyMessage());
            assertThat(result.errorFlag()).isTrue();
            assertThat(result.cardsPublished()).isZero();
        }
    }

    @Nested
    @DisplayName("Selecting a reporting period")
    class SelectingAReportingPeriod

    {
        @Test
        @DisplayName("a confirmed monthly selection publishes all seventeen cards and is accepted")
        void aConfirmedMonthlySelectionPublishesEveryCard() {
            final JobSubmissionService publisher = publishingEveryCard();

            final ReportRequestService.ReportRequestResult result = serviceWith(publisher)
                    .processReportRequest(input(SELECTED, null, null, CONFIRM_YES, KeyAction.ENTER,
                            returningContext()));

            assertThat(result.reportPeriod()).isEqualTo(ReportPeriod.MONTHLY);
            assertThat(result.cardsPublished()).isEqualTo(JclCardImageBuilder.CARD_COUNT);
            assertThat(result.submissionAccepted()).isTrue();
            assertThat(result.errorFlag()).isFalse();
            Mockito.verify(publisher, Mockito.times(JclCardImageBuilder.CARD_COUNT))
                    .writeJobSubmissionQueue(ArgumentMatchers.anyString(),
                            ArgumentMatchers.anyString(), ArgumentMatchers.anyInt());
        }

        @Test
        @DisplayName("a confirmed yearly selection is accepted on the same terms and carries its own "
                + "period, so the two selections are not interchangeable")
        void aConfirmedYearlySelectionCarriesItsOwnPeriod() {
            final ReportRequestService.ReportRequestResult result =
                    serviceWith(publishingEveryCard()).processReportRequest(
                            input(null, SELECTED, null, CONFIRM_YES, KeyAction.ENTER,
                                    returningContext()));

            assertThat(result.reportPeriod()).isEqualTo(ReportPeriod.YEARLY);
            assertThat(result.cardsPublished()).isEqualTo(JclCardImageBuilder.CARD_COUNT);
            assertThat(result.submissionAccepted()).isTrue();
        }

        @Test
        @DisplayName("a confirmed custom selection carrying a valid range is accepted and echoes the "
                + "dates it submitted")
        void aConfirmedCustomSelectionEchoesItsDates() {
            final ReportRequestService.ReportRequestResult result =
                    serviceWith(publishingEveryCard())
                            .processReportRequest(customInput("01", "01", "2022",
                                    "12", "31", "2022", CONFIRM_YES));

            assertThat(result.reportPeriod()).isEqualTo(ReportPeriod.CUSTOM);
            assertThat(result.startDate()).isNotBlank();
            assertThat(result.endDate()).isNotBlank();
            assertThat(result.submissionAccepted()).isTrue();
        }

        @Test
        @DisplayName("selecting no period at all reports the select-a-report-type message and "
                + "publishes nothing")
        void selectingNoPeriodReportsTheSelectionMessage() {
            final ReportRequestService.ReportRequestResult result =
                    serviceWith(publishingEveryCard()).processReportRequest(
                            input(null, null, null, null, KeyAction.ENTER, returningContext()));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).contains("report type");
            assertThat(result.cardsPublished()).isZero();
            assertThat(result.reportPeriod()).isNull();
        }
    }

    @Nested
    @DisplayName("The confirmation gate")
    class TheConfirmationGate {

        @Test
        @DisplayName("an unconfirmed selection is blocked and prompts for confirmation instead of "
                + "publishing, which is the gate the source opens only on an explicit entry")
        void anUnconfirmedSelectionIsBlocked() {
            final JobSubmissionService publisher = publishingEveryCard();

            final ReportRequestService.ReportRequestResult result = serviceWith(publisher)
                    .processReportRequest(input(SELECTED, null, null, null, KeyAction.ENTER,
                            returningContext()));

            assertThat(result.confirmationBlocked()).isTrue();
            assertThat(result.cardsPublished()).isZero();
            assertThat(result.submissionAccepted()).isFalse();
            assertThat(result.message()).contains("confirm");
            Mockito.verify(publisher, Mockito.never())
                    .writeJobSubmissionQueue(ArgumentMatchers.anyString(),
                            ArgumentMatchers.anyString(), ArgumentMatchers.anyInt());
        }

        @Test
        @DisplayName("a confirmation entry the screen does not admit is refused and publishes "
                + "nothing, rather than being read as an affirmative")
        void anInadmissibleConfirmationEntryIsRefused() {
            final ReportRequestService.ReportRequestResult result =
                    serviceWith(publishingEveryCard()).processReportRequest(
                            input(SELECTED, null, null, "Q", KeyAction.ENTER, returningContext()));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.cardsPublished()).isZero();
            assertThat(result.submissionAccepted()).isFalse();
        }
    }

    @Nested
    @DisplayName("The publishing boundary")
    class ThePublishingBoundary {

        @Test
        @DisplayName("a queue that refuses every card leaves the submission unaccepted and reports "
                + "the transient-data-queue failure the operator sees, naming the legacy queue")
        void aQueueRefusingEveryCardLeavesTheSubmissionUnaccepted() {
            final ReportRequestService.ReportRequestResult result =
                    serviceWith(publishingNoCard()).processReportRequest(
                            input(SELECTED, null, null, CONFIRM_YES, KeyAction.ENTER,
                                    returningContext()));

            assertThat(result.cardsPublished()).isZero();
            assertThat(result.submissionAccepted()).isFalse();
            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).contains("JOBS");
        }

        @Test
        @DisplayName("the turn re-arms its own transaction identifier, which is what makes the next "
                + "call a continuation of this conversation")
        void theTurnReArmsItsOwnTransactionIdentifier() {
            final ReportRequestService.ReportRequestResult result =
                    serviceWith(publishingEveryCard()).processReportRequest(
                            input(SELECTED, null, null, CONFIRM_YES, KeyAction.ENTER,
                                    returningContext()));

            assertThat(result.reArmedTransactionId()).isNotBlank();
            assertThat(result.navigationContext()).isNotNull();
        }
    }
}
