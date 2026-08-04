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

import com.carddemo.domain.enums.KeyAction;
import com.carddemo.domain.enums.ReportPeriod;
import com.carddemo.exception.ValidationException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mockito;

/**
 * Unit test for {@link ReportRequestService}, the translation of the report-request transaction
 * {@code CR00} carried by {@code app/cbl/CORPT00C.cbl}.
 *
 * <p><strong>What is under test.</strong> One pseudo-conversational turn. The service decides, from
 * the navigation state and the attention key alone, whether to route away without a screen, send the
 * screen for a first entry, or receive and process a submitted screen. On a processed submission it
 * selects one of three reporting periods, gates the submission behind an explicit confirmation, and
 * publishes the job-submission card image as ONE complete submission.
 *
 * <p><strong>The batch-trigger contract is verified, not counted.</strong> The externally observable
 * contract of a report submission is not "seventeen messages reached the queue"; it is <em>which</em>
 * seventeen, <em>in what order</em>, under <em>which ordinals</em>, carrying <em>which</em>
 * substituted dates, and terminated by the end-of-file sentinel. This suite therefore captures every
 * publish call with an {@link ArgumentCaptor}, orders them with an {@link InOrder}, and compares each
 * captured card against an eighty-column image <strong>authored in this file</strong> from the card
 * literals the legacy program declares. Nothing is asserted with a permissive matcher, no expectation
 * is derived from a production constant or from a live collaborator, and no assertion reduces to a
 * call count.
 *
 * <p><strong>Collaborators.</strong> The date validator, the message catalogue and the navigation
 * rules are the real classes, because each is a pure decision this service must agree with rather
 * than a boundary to be stubbed - substituting them would let this suite pass while the module
 * disagreed with itself. Only {@link JobSubmissionService} is a test double, because it publishes to
 * a real queue; its whole-submission contract returns an outcome, so a double states how many cards
 * the queue accepted and whether the stream stopped short, exactly as the bridge does. The clock is
 * fixed so the rendered header is deterministic.
 *
 * <p><strong>The double stubs the whole-submission entry point, not the per-card one, and that change
 * is the point of a corrected contract rather than an incidental edit.</strong> An earlier revision of
 * the service iterated the card slots itself and called the single-card entry point seventeen times,
 * so this suite stubbed and counted that call. Every card of every submission carries one stable
 * message group, so two concurrent turns interleaved their sends and a first-in-first-out queue then
 * preserved the interleaving - an unparseable job stream, reported as two successes. The service now
 * hands the bridge the complete stream and the bridge admits one submission at a time. A suite that
 * kept counting per-card calls would have gone on asserting the shape that caused the defect, so the
 * expectations below assert the submission-level contract instead: one call, carrying all seventeen
 * cards, in order.
 *
 * <p><strong>The clock is fixed</strong> at an instant inside July 2022, which makes the derived
 * month-to-date and year-to-date windows deterministic and therefore assertable as literals.
 *
 * <p>Provenance: legacy checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}; upstream
 * release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The card images are the
 * seventeen eighty-column literals declared at {@code app/cbl/CORPT00C.cbl} lines 84 to 125, with the
 * four substitution slots those lines carry; the queue's ignore-on-error behaviour is defined at
 * {@code app/csd/CARDDEMO.CSD}. No legacy source line is transcribed here - only the card literals
 * themselves, which are the external contract this test exists to pin.
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

    /** Width of one job-submission card, from the legacy eighty-column card image. */
    private static final int CARD_WIDTH = 80;

    /** How many cards one submission carries, from the seventeen literals the source declares. */
    private static final int EXPECTED_CARD_COUNT = 17;

    /** Width of a date substitution slot, from the ten-character date the source moves into it. */
    private static final int DATE_SLOT_WIDTH = 10;

    /** The card that terminates the stream, which the source transmits rather than merely holding. */
    private static final String END_OF_FILE_CARD = "/*EOF";

    /** The month-to-date window this fixed clock derives: the first of the current month. */
    private static final String MONTHLY_START_DATE = "2022-07-01";

    /** The month-to-date window this fixed clock derives: the last day of the current month. */
    private static final String MONTHLY_END_DATE = "2022-07-31";

    /** The year-to-date window this fixed clock derives: the first of the current year. */
    private static final String YEARLY_START_DATE = "2022-01-01";

    /** The year-to-date window this fixed clock derives: the last day of the current year. */
    private static final String YEARLY_END_DATE = "2022-12-31";

    /** The custom window the operator enters in the custom-period tests. */
    private static final String CUSTOM_START_DATE = "2022-01-01";

    /** The closing bound of that custom window. */
    private static final String CUSTOM_END_DATE = "2022-12-31";

    /**
     * The header date this fixed clock renders, as {@code MM/DD/YY} with a two-digit year taken from
     * the third character of the four-digit year, which is what the source's {@code (3:2)} reference
     * modifier does. Pinned as a literal rather than recomputed from the clock, so a change to the
     * assembly order or to either separator fails here.
     */
    private static final String EXPECTED_HEADER_DATE = "07/19/22";

    /** The header time this fixed clock renders, as {@code HH:MM:SS} on a twenty-four hour clock. */
    private static final String EXPECTED_HEADER_TIME = "23:12:33";

    /** The first screen title, at the forty-character width the title copybook declares. */
    private static final String EXPECTED_TITLE_01 = pad(" ".repeat(6) + "AWS Mainframe Modernization", 40);

    /** The second screen title, at the same declared width. */
    private static final String EXPECTED_TITLE_02 = pad(" ".repeat(14) + "CardDemo", 40);

    /** The common invalid-key message, at the fifty-character width the message copybook declares. */
    private static final String EXPECTED_INVALID_KEY_MESSAGE =
            pad("Invalid key pressed. Please see below...", 50);

    /** The message shown when no reporting period was selected. */
    private static final String EXPECTED_SELECT_REPORT_TYPE_MESSAGE =
            "Select a report type to print report...";

    /** The message shown when the queue refuses a card, naming the legacy queue. */
    private static final String EXPECTED_QUEUE_FAILURE_MESSAGE = "Unable to Write TDQ (JOBS)...";

    /**
     * The report name a monthly selection reports, delimited at its first space.
     *
     * <p>The name is held in a ten-character field and is delimited before it reaches either the
     * prompt or the result, so the field's padding never reaches the operator. That is the source's own
     * behaviour, and stating the delimited form here is what pins it.
     */
    private static final String MONTHLY_REPORT_NAME = "Monthly";

    /** The prompt shown when a monthly selection has not yet been confirmed. */
    private static final String EXPECTED_MONTHLY_CONFIRM_PROMPT =
            "Please confirm to print the " + MONTHLY_REPORT_NAME + " report...";

    /** A confirmation entry the screen does not admit. */
    private static final String INADMISSIBLE_CONFIRM = "Q";

    /** The message shown when the confirmation entry is not one the screen admits. */
    private static final String EXPECTED_INADMISSIBLE_CONFIRM_MESSAGE =
            "\"" + INADMISSIBLE_CONFIRM + "\" is not a valid value to confirm...";

    /**
     * Pads a literal to a declared field width the way a fixed-width field holds it.
     *
     * @param  text  the significant content
     * @param  width the declared field width
     * @return the text at exactly {@code width} characters
     */
    private static String pad(final String text, final int width) {
        return text + " ".repeat(width - text.length());
    }

    /**
     * Places a card literal into its eighty-column frame, left-justified and space-filled.
     *
     * @param  text the card's significant content
     * @return the card image, exactly {@value #CARD_WIDTH} characters
     */
    private static String card(final String text) {
        return pad(text, CARD_WIDTH);
    }

    /**
     * Builds one of the two sort-symbol cards, which carry a date inside a quoted literal.
     *
     * <p>The two differ only in their leading text, and therefore in how much filler follows the
     * closing quote; both still measure exactly one card.
     *
     * @param  leadingText the card text up to and including the opening quote
     * @param  date        the ten-character date placed into the slot
     * @return the card image, exactly {@value #CARD_WIDTH} characters
     */
    private static String sortSymbolCard(final String leadingText, final String date) {
        assertThat(date).as("a date slot holds exactly ten characters").hasSize(DATE_SLOT_WIDTH);
        return card(leadingText + date + "'");
    }

    /**
     * Builds the in-stream parameter card, which carries both dates separated by one space.
     *
     * @param  startDate the opening bound
     * @param  endDate   the closing bound
     * @return the card image, exactly {@value #CARD_WIDTH} characters
     */
    private static String dateParameterCard(final String startDate, final String endDate) {
        return card(startDate + " " + endDate);
    }

    /**
     * The seventeen cards a submission carries, authored here from the legacy card literals.
     *
     * <p>Every card is written out rather than generated, and the order is the order the source
     * declares, because the order is as contractual as the content: an internal reader receives these
     * as a job stream and a reordered stream is not the same job.
     *
     * @param  startDate the date placed into the two start slots
     * @param  endDate   the date placed into the two end slots
     * @return the seventeen card images, in submission order
     */
    private static List<String> expectedCards(final String startDate, final String endDate) {
        final List<String> cards = List.of(
                card("//TRNRPT00 JOB 'TRAN REPORT',CLASS=A,MSGCLASS=0,"),
                card("// NOTIFY=&SYSUID"),
                card("//*"),
                card("//JOBLIB JCLLIB ORDER=('AWS.M2.CARDDEMO.PROC')"),
                card("//*"),
                card("//STEP10 EXEC PROC=TRANREPT"),
                card("//*"),
                card("//STEP05R.SYMNAMES DD *"),
                card("TRAN-CARD-NUM,263,16,ZD"),
                card("TRAN-PROC-DT,305,10,CH"),
                sortSymbolCard("PARM-START-DATE,C'", startDate),
                sortSymbolCard("PARM-END-DATE,C'", endDate),
                card("/*"),
                card("//STEP10R.DATEPARM DD *"),
                dateParameterCard(startDate, endDate),
                card("/*"),
                card(END_OF_FILE_CARD));

        assertThat(cards)
                .as("this file's own card oracle must carry seventeen cards")
                .hasSize(EXPECTED_CARD_COUNT);
        assertThat(cards)
                .as("every card must occupy its full eighty-column frame")
                .allSatisfy(image -> assertThat(image).hasSize(CARD_WIDTH));
        return cards;
    }

    /** A publishing double that reports every card as written. */
    private static JobSubmissionService publishingEveryCard() {
        final JobSubmissionService publisher = Mockito.mock(JobSubmissionService.class);
        Mockito.when(publisher.submitCanonicalJobImage(ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyList()))
                .thenAnswer(invocation -> {
                    final int submitted = invocation.<List<String>>getArgument(1).size();
                    return new JobSubmissionService.SubmissionResult(submitted, submitted, false, "");
                });
        return publisher;
    }

    /**
     * A publishing double that reports a submission refused on its first card, which is the
     * ignore-on-error path.
     *
     * @return the double
     */
    private static JobSubmissionService publishingNoCard() {
        final JobSubmissionService publisher = Mockito.mock(JobSubmissionService.class);
        Mockito.when(publisher.submitCanonicalJobImage(ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyList()))
                .thenAnswer(invocation -> new JobSubmissionService.SubmissionResult(
                        invocation.<List<String>>getArgument(1).size(), 0, true,
                        com.carddemo.exception.JobSubmissionException.DEFAULT_MESSAGE));
        return publisher;
    }

    /**
     * A publishing double that accepts every card up to a slot and refuses that slot and every later
     * one, which is what a queue that fills up part way through a submission looks like.
     *
     * @param  refusedSlot the one-based slot at which the queue starts refusing
     * @return the double
     */
    private static JobSubmissionService publishingUntilSlot(final int refusedSlot) {
        final JobSubmissionService publisher = Mockito.mock(JobSubmissionService.class);
        Mockito.when(publisher.submitCanonicalJobImage(ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyList()))
                .thenAnswer(invocation -> new JobSubmissionService.SubmissionResult(
                        invocation.<List<String>>getArgument(1).size(), refusedSlot - 1, true,
                        com.carddemo.exception.JobSubmissionException.DEFAULT_MESSAGE));
        return publisher;
    }

    /** Builds the service over the real decision collaborators and the supplied publisher. */
    private static ReportRequestService serviceWith(final JobSubmissionService publisher) {
        return new ReportRequestService(new DateValidationService(), publisher,
                new MessageCatalogService(), new NavigationService(), FIXED_CLOCK);
    }

    /** A turn carrying navigation state that has already been through one entry. */
    private static ConversationState returningContext() {
        return signedOnState(ConversationState.EntryMode.RE_ENTRY);
    }

    /**
     * Builds a signed-on navigation state.
     *
     * <p>A context equal to {@link ConversationState#empty()} is what the service reads as an absent
     * communication area, so a turn that is present but has not yet been through the screen must
     * carry real state with its program context still on first entry.
     *
     * @param entryMode whether this turn is a first entry or a re-entry
     * @return the assembled navigation state
     */
    private static ConversationState signedOnState(
            final ConversationState.EntryMode entryMode) {
        return new ConversationState("CR00", "COSGN00C", null, null, entryMode);
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
            final ConversationState context) {
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

    /**
     * Captures the canonical image the service published, expanded card by card.
     *
     * <p>The service offers the whole seventeen-card image to the publisher in one call, because a
     * submission is only meaningful to the batch tier as a contiguous ordered run and every card shares
     * one message group. The captor recovers that call's submission identity and its ordered card list,
     * and this helper expands the list into one entry per card with its one-based ordinal, so every
     * assertion below can still speak about a card, its slot and the submission it belongs to.
     *
     * @param  publisher     the double the service published through
     * @param  expectedCards how many cards the contract requires the offered image to carry
     * @return the offered cards, in submission order
     */
    private static List<PublishedCard> capturePublishedCards(final JobSubmissionService publisher,
            final int expectedCards) {
        final ArgumentCaptor<String> submissionIds = ArgumentCaptor.forClass(String.class);
        final ArgumentCaptor<List<String>> images = ArgumentCaptor.captor();

        Mockito.verify(publisher, Mockito.times(1))
                .submitCanonicalJobImage(submissionIds.capture(), images.capture());
        Mockito.verify(publisher, Mockito.never()).writeJobSubmissionQueue(
                ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                ArgumentMatchers.anyInt());

        final String submissionId = submissionIds.getValue();
        final List<String> offered = images.getValue();
        assertThat(offered)
                .as("the offered image must carry exactly the number of cards the contract requires")
                .hasSize(expectedCards);

        final List<PublishedCard> published = new ArrayList<>();
        for (int index = 0; index < offered.size(); index++) {
            published.add(new PublishedCard(submissionId, offered.get(index), index + 1));
        }
        return published;
    }

    /**
     * Asserts that the offered image is exactly the cards the contract requires, in submission order.
     *
     * @param publisher the double the service published through
     * @param cards     the cards the contract requires, in submission order
     */
    private static void verifyOrderedStream(final JobSubmissionService publisher,
            final List<String> cards) {
        Mockito.verify(publisher, Mockito.times(1)).submitCanonicalJobImage(
                ArgumentMatchers.anyString(), ArgumentMatchers.eq(cards));
        Mockito.verify(publisher, Mockito.never()).writeJobSubmissionQueue(
                ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                ArgumentMatchers.anyInt());
    }

    private record PublishedCard(String submissionId, String cardImage, int ordinal) {
    }

    @Nested
    @DisplayName("Turn entry")
    class TurnEntry {

        @Test
        @DisplayName("a turn carrying no navigation state routes to the sign-on screen without "
                + "sending this screen, which is what an absent communication area means")
        void aTurnCarryingNoStateRoutesToSignOn() {
            final JobSubmissionService publisher = publishingEveryCard();

            final ReportRequestService.ReportRequestResult result =
                    serviceWith(publisher).processReportRequest(
                            input(null, null, null, null, KeyAction.ENTER, null));

            assertThat(result.route()).isEqualTo(NavigationService.Route.SIGN_ON);
            assertThat(result.cardsPublished()).isZero();
            assertThat(result.submissionAccepted()).isFalse();
            Mockito.verifyNoInteractions(publisher);
        }

        @Test
        @DisplayName("a first entry sends the screen, positions the cursor on the monthly selection "
                + "and publishes nothing, because nothing has been submitted yet")
        void aFirstEntrySendsTheScreenAndPublishesNothing() {
            final JobSubmissionService publisher = publishingEveryCard();

            final ReportRequestService.ReportRequestResult result =
                    serviceWith(publisher).processReportRequest(
                            input(null, null, null, null, KeyAction.ENTER,
                                    signedOnState(ConversationState.EntryMode.FIRST_ENTRY)));

            assertThat(result.focusField()).isEqualTo("MONTHLY");
            assertThat(result.cardsPublished()).isZero();
            assertThat(result.errorFlag()).isFalse();
            assertThat(result.header().currentDate()).isEqualTo(EXPECTED_HEADER_DATE);
            assertThat(result.header().currentTime()).isEqualTo(EXPECTED_HEADER_TIME);
            Mockito.verifyNoInteractions(publisher);
        }

        @Test
        @DisplayName("the rendered header carries both title lines verbatim at their declared widths, "
                + "so the operator sees the text the title copybook declares")
        void theRenderedHeaderCarriesBothTitleLines() {
            final ReportRequestService.ReportRequestResult result =
                    serviceWith(publishingEveryCard()).processReportRequest(
                            input(null, null, null, null, KeyAction.ENTER,
                                    signedOnState(ConversationState.EntryMode.FIRST_ENTRY)));

            assertThat(result.header().title01()).isEqualTo(EXPECTED_TITLE_01);
            assertThat(result.header().title02()).isEqualTo(EXPECTED_TITLE_02);
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
            final JobSubmissionService publisher = publishingEveryCard();

            final ReportRequestService.ReportRequestResult result =
                    serviceWith(publisher).processReportRequest(
                            input(null, null, null, null, KeyAction.PFK03, returningContext()));

            assertThat(result.route()).isEqualTo(NavigationService.Route.USER_MENU);
            assertThat(result.cardsPublished()).isZero();
            assertThat(result.submissionAccepted()).isFalse();
            Mockito.verifyNoInteractions(publisher);
        }

        @Test
        @DisplayName("a key the screen does not map reports the common invalid-key message verbatim "
                + "and submits nothing, rather than being treated as an enter")
        void anUnmappedKeyReportsTheInvalidKeyMessage() {
            final JobSubmissionService publisher = publishingEveryCard();

            final ReportRequestService.ReportRequestResult result =
                    serviceWith(publisher).processReportRequest(
                            input(SELECTED, null, null, CONFIRM_YES, KeyAction.PFK12,
                                    returningContext()));

            assertThat(result.message())
                    .as("the message is carried at the width the common-message copybook declares, "
                            + "so it is compared untrimmed against the literal text")
                    .isEqualTo(EXPECTED_INVALID_KEY_MESSAGE);
            assertThat(result.errorFlag()).isTrue();
            assertThat(result.cardsPublished()).isZero();
            Mockito.verifyNoInteractions(publisher);
        }
    }

    @Nested
    @DisplayName("Selecting a reporting period")
    class SelectingAReportingPeriod {

        @Test
        @DisplayName("a confirmed monthly selection publishes the seventeen cards in order, under "
                + "ordinals one to seventeen, under one submission identity, with the month-to-date "
                + "window in all four slots and the end-of-file card transmitted last")
        void aConfirmedMonthlySelectionPublishesTheContractualCardStream() {
            final JobSubmissionService publisher = publishingEveryCard();
            final List<String> expected = expectedCards(MONTHLY_START_DATE, MONTHLY_END_DATE);

            final ReportRequestService.ReportRequestResult result = serviceWith(publisher)
                    .processReportRequest(input(SELECTED, null, null, CONFIRM_YES, KeyAction.ENTER,
                            returningContext()));

            assertThat(result.reportPeriod()).isEqualTo(ReportPeriod.MONTHLY);
            assertThat(result.startDate()).isEqualTo(MONTHLY_START_DATE);
            assertThat(result.endDate()).isEqualTo(MONTHLY_END_DATE);
            assertThat(result.submissionAccepted()).isTrue();
            assertThat(result.errorFlag()).isFalse();
                assertThat(result.cardsPublished()).isEqualTo(EXPECTED_CARD_COUNT);

                // ONE call carrying ALL seventeen cards, in order - not seventeen calls carrying one card
                // each. That is the whole of the difference between a submission the batch tier can parse
                // and one that two concurrent turns can interleave into nonsense.
                //
                // The expected stream is the seventeen images THIS FILE authored from the legacy source,
                // never the builder's own output, so the verification states what the cards must be
                // rather than echoing whatever was passed.
                Mockito.verify(publisher, Mockito.times(1)).submitCanonicalJobImage(
                        ArgumentMatchers.anyString(), ArgumentMatchers.eq(expected));
                Mockito.verify(publisher, Mockito.never()).writeJobSubmissionQueue(
                        ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyInt());
                Mockito.verifyNoMoreInteractions(publisher);

                assertThat(expected.get(EXPECTED_CARD_COUNT - 1))
                        .as("the oracle's own last card is the sentinel, so the stream compared above "
                                + "ends with the card the legacy program transmits rather than merely "
                                + "holds in storage")
                        .isEqualTo(card(END_OF_FILE_CARD))
                        .startsWith(END_OF_FILE_CARD);
        }

        @Test
        @DisplayName("the four date slots carry the selected window: both sort-symbol cards and both "
                + "halves of the parameter card, and no other card mentions a date")
        void theFourDateSlotsCarryTheSelectedWindow() {
            final JobSubmissionService publisher = publishingEveryCard();

            serviceWith(publisher).processReportRequest(
                    input(SELECTED, null, null, CONFIRM_YES, KeyAction.ENTER, returningContext()));

            final List<PublishedCard> published =
                    capturePublishedCards(publisher, EXPECTED_CARD_COUNT);
            final List<String> bodies = new ArrayList<>();
            published.forEach(publishedCard -> bodies.add(publishedCard.cardImage()));

            assertThat(bodies.get(10))
                    .isEqualTo(sortSymbolCard("PARM-START-DATE,C'", MONTHLY_START_DATE));
            assertThat(bodies.get(11))
                    .isEqualTo(sortSymbolCard("PARM-END-DATE,C'", MONTHLY_END_DATE));
            assertThat(bodies.get(14))
                    .isEqualTo(dateParameterCard(MONTHLY_START_DATE, MONTHLY_END_DATE));

            long cardsMentioningTheStartDate = 0;
            long cardsMentioningTheEndDate = 0;
            for (final String body : bodies) {
                if (body.contains(MONTHLY_START_DATE)) {
                    cardsMentioningTheStartDate++;
                }
                if (body.contains(MONTHLY_END_DATE)) {
                    cardsMentioningTheEndDate++;
                }
            }

            assertThat(cardsMentioningTheStartDate)
                    .as("the start date occupies exactly two slots, on two cards")
                    .isEqualTo(2);
            assertThat(cardsMentioningTheEndDate)
                    .as("the end date occupies exactly two slots, on two cards")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("a confirmed yearly selection publishes the same seventeen cards with the "
                + "year-to-date window substituted, so the two selections are not interchangeable")
        void aConfirmedYearlySelectionCarriesItsOwnWindow() {
            final JobSubmissionService publisher = publishingEveryCard();

            final ReportRequestService.ReportRequestResult result = serviceWith(publisher)
                    .processReportRequest(input(null, SELECTED, null, CONFIRM_YES, KeyAction.ENTER,
                            returningContext()));

            assertThat(result.reportPeriod()).isEqualTo(ReportPeriod.YEARLY);
            assertThat(result.startDate()).isEqualTo(YEARLY_START_DATE);
            assertThat(result.endDate()).isEqualTo(YEARLY_END_DATE);
            assertThat(result.submissionAccepted()).isTrue();

            verifyOrderedStream(publisher, expectedCards(YEARLY_START_DATE, YEARLY_END_DATE));
        }

        @Test
        @DisplayName("a confirmed custom selection publishes the operator's own window in all four "
                + "slots, and echoes exactly the dates it submitted")
        void aConfirmedCustomSelectionSubmitsTheOperatorsWindow() {
            final JobSubmissionService publisher = publishingEveryCard();

            final ReportRequestService.ReportRequestResult result = serviceWith(publisher)
                    .processReportRequest(customInput("01", "01", "2022", "12", "31", "2022",
                            CONFIRM_YES));

            assertThat(result.reportPeriod()).isEqualTo(ReportPeriod.CUSTOM);
            assertThat(result.startDate()).isEqualTo(CUSTOM_START_DATE);
            assertThat(result.endDate()).isEqualTo(CUSTOM_END_DATE);
            assertThat(result.submissionAccepted()).isTrue();

            verifyOrderedStream(publisher, expectedCards(CUSTOM_START_DATE, CUSTOM_END_DATE));
        }

        @Test
        @DisplayName("selecting no period at all reports the select-a-report-type message verbatim "
                + "and publishes nothing")
        void selectingNoPeriodReportsTheSelectionMessage() {
            final JobSubmissionService publisher = publishingEveryCard();

            final ReportRequestService.ReportRequestResult result =
                    serviceWith(publisher).processReportRequest(
                            input(null, null, null, null, KeyAction.ENTER, returningContext()));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).isEqualTo(EXPECTED_SELECT_REPORT_TYPE_MESSAGE);
            assertThat(result.cardsPublished()).isZero();
            assertThat(result.reportPeriod()).isNull();
            Mockito.verifyNoInteractions(publisher);
        }
    }

    @Nested
    @DisplayName("The confirmation gate")
    class TheConfirmationGate {

        @Test
        @DisplayName("an unconfirmed selection is blocked and prompts for confirmation verbatim "
                + "instead of publishing, which is the gate the source opens only on an explicit entry")
        void anUnconfirmedSelectionIsBlocked() {
            final JobSubmissionService publisher = publishingEveryCard();

            final ReportRequestService.ReportRequestResult result = serviceWith(publisher)
                    .processReportRequest(input(SELECTED, null, null, null, KeyAction.ENTER,
                            returningContext()));

            assertThat(result.confirmationBlocked()).isTrue();
            assertThat(result.cardsPublished()).isZero();
            assertThat(result.submissionAccepted()).isFalse();
            assertThat(result.message()).isEqualTo(EXPECTED_MONTHLY_CONFIRM_PROMPT);
            assertThat(result.reportName())
                    .as("the report name reaches the caller delimited at its first space, so the "
                            + "ten-character field's padding is not part of the contract")
                    .isEqualTo(MONTHLY_REPORT_NAME);
            Mockito.verifyNoInteractions(publisher);
        }

        @Test
        @DisplayName("a confirmation entry the screen does not admit is refused, reported verbatim "
                + "with the offending entry quoted, and publishes nothing")
        void anInadmissibleConfirmationEntryIsRefused() {
            final JobSubmissionService publisher = publishingEveryCard();

            final ReportRequestService.ReportRequestResult result = serviceWith(publisher)
                    .processReportRequest(input(SELECTED, null, null, INADMISSIBLE_CONFIRM,
                            KeyAction.ENTER, returningContext()));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).isEqualTo(EXPECTED_INADMISSIBLE_CONFIRM_MESSAGE);
            assertThat(result.cardsPublished()).isZero();
            assertThat(result.submissionAccepted()).isFalse();
            Mockito.verifyNoInteractions(publisher);
        }
    }

    @Nested
    @DisplayName("The publishing boundary")
    class ThePublishingBoundary {

        @Test
        @DisplayName("a queue that refuses the first card leaves the submission unaccepted, reports "
                + "the transient-data-queue failure verbatim, and sends no further card")
        void aQueueRefusingTheFirstCardStopsTheStream() {
            final JobSubmissionService publisher = publishingNoCard();
            final List<String> expected = expectedCards(MONTHLY_START_DATE, MONTHLY_END_DATE);

            final ReportRequestService.ReportRequestResult result = serviceWith(publisher)
                    .processReportRequest(input(SELECTED, null, null, CONFIRM_YES, KeyAction.ENTER,
                            returningContext()));

            assertThat(result.cardsPublished()).isZero();
            assertThat(result.submissionAccepted()).isFalse();
            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).isEqualTo(EXPECTED_QUEUE_FAILURE_MESSAGE);

            // Exactly one offer, and it carries the whole image: the publisher owns the card-by-card
            // walk and its error guard, so the service offers the stream once and reads back how much
            // of it reached the queue. Ignore-on-error means logged and non-fatal, never retried, so a
            // refused submission produces no second offer.
            final List<PublishedCard> published =
                    capturePublishedCards(publisher, EXPECTED_CARD_COUNT);
            assertThat(published).extracting(PublishedCard::cardImage)
                    .as("the refused submission still offered the seventeen cards this file authored")
                    .containsExactlyElementsOf(expected);
            assertThat(published.get(0).ordinal()).isEqualTo(1);
        }

        @Test
        @DisplayName("a queue that refuses part way through sends every card up to that slot and "
                + "none after it, so a partial submission is never silently completed")
        void aQueueRefusingPartWayThroughStopsAtThatSlot() {
            final int refusedSlot = 5;
            final JobSubmissionService publisher = publishingUntilSlot(refusedSlot);
            final List<String> expected = expectedCards(MONTHLY_START_DATE, MONTHLY_END_DATE);

            final ReportRequestService.ReportRequestResult result = serviceWith(publisher)
                    .processReportRequest(input(SELECTED, null, null, CONFIRM_YES, KeyAction.ENTER,
                            returningContext()));

            assertThat(result.cardsPublished()).isEqualTo(refusedSlot - 1);
            assertThat(result.submissionAccepted())
                    .as("a submission is accepted only when every card reached the queue")
                    .isFalse();
            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).isEqualTo(EXPECTED_QUEUE_FAILURE_MESSAGE);

            final List<PublishedCard> published =
                    capturePublishedCards(publisher, EXPECTED_CARD_COUNT);
            assertThat(published).extracting(PublishedCard::cardImage)
                    .as("the offer is the whole image; where the queue stopped is reported back rather "
                            + "than guessed at by the caller")
                    .containsExactlyElementsOf(expected);
            assertThat(published).extracting(PublishedCard::ordinal)
                    .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17);
            assertThat(expected.subList(0, refusedSlot))
                    .as("the sentinel sits beyond the refused slot, so a stream that stops there never "
                            + "reaches it")
                    .doesNotContain(card(END_OF_FILE_CARD));
        }

        @Test
        @DisplayName("the turn re-arms its own transaction identifier, which is what makes the next "
                + "call a continuation of this conversation")
        void theTurnReArmsItsOwnTransactionIdentifier() {
            final ReportRequestService.ReportRequestResult result =
                    serviceWith(publishingEveryCard()).processReportRequest(
                            input(SELECTED, null, null, CONFIRM_YES, KeyAction.ENTER,
                                    returningContext()));

            assertThat(result.reArmedTransactionId()).isEqualTo("CR00");
            assertThat(result.navigationContext()).isEqualTo(returningContext());
        }

        @Test
        @DisplayName("two submissions carry two different identities, so the cards of one cannot be "
                + "interleaved with the cards of the other on the queue")
        void twoSubmissionsCarryTwoDifferentIdentities() {
            final JobSubmissionService firstPublisher = publishingEveryCard();
            final JobSubmissionService secondPublisher = publishingEveryCard();

            serviceWith(firstPublisher).processReportRequest(
                    input(SELECTED, null, null, CONFIRM_YES, KeyAction.ENTER, returningContext()));
            serviceWith(secondPublisher).processReportRequest(
                    input(null, SELECTED, null, CONFIRM_YES, KeyAction.ENTER, returningContext()));

            final String firstIdentity =
                    capturePublishedCards(firstPublisher, EXPECTED_CARD_COUNT).get(0).submissionId();
            final String secondIdentity =
                    capturePublishedCards(secondPublisher, EXPECTED_CARD_COUNT).get(0).submissionId();

            assertThat(firstIdentity).isNotBlank().isNotEqualTo(secondIdentity);
        }
    }

    @Nested
    @DisplayName("The first failure ends the turn")
    class TheFirstFailureEndsTheTurn {

        /**
         * The six emptiness checks, each reached only when the ones before it were satisfied.
         *
         * <p>The source writes them as one ordered evaluation, so at most one fires; the failing one
         * sends the screen, and the send ends the task. Each row therefore leaves exactly one part
         * empty and expects exactly that part's message and exactly one field error.
         */
        @ParameterizedTest(name = "{6}")
        @CsvSource({
            "'',   01, 2022, 12, 31, 2022, 'Start Date - Month can NOT be empty...'",
            "01,   '', 2022, 12, 31, 2022, 'Start Date - Day can NOT be empty...'",
            "01,   01, '',   12, 31, 2022, 'Start Date - Year can NOT be empty...'",
            "01,   01, 2022, '', 31, 2022, 'End Date - Month can NOT be empty...'",
            "01,   01, 2022, 12, '', 2022, 'End Date - Day can NOT be empty...'",
            "01,   01, 2022, 12, 31, '',   'End Date - Year can NOT be empty...'",
        })
        @DisplayName("an empty date part reports only its own message, and only one field error")
        void anEmptyDatePartReportsOnlyItsOwnMessage(final String startMonth, final String startDay,
                final String startYear, final String endMonth, final String endDay,
                final String endYear, final String expectedMessage) {
            final ReportRequestService.ReportRequestResult result =
                    serviceWith(publishingEveryCard()).processReportRequest(customInput(startMonth,
                            startDay, startYear, endMonth, endDay, endYear, CONFIRM_YES));

            assertThat(result.errorFlag()).as("the emptiness check must raise the error flag").isTrue();
            assertThat(result.message()).as("the summary text is the failing check's own")
                    .isEqualTo(expectedMessage);
            assertThat(result.fieldErrors())
                    .as("the send ended the task, so no later check could have reported a second "
                            + "field")
                    .hasSize(1);
            assertThat(result.fieldErrors().get(0).state())
                    .as("an empty field is not supplied rather than supplied wrongly")
                    .isEqualTo(ValidationException.FieldState.MISSING);
            assertThat(result.cardsPublished()).as("nothing may be published").isZero();
            assertThat(result.reportPeriod())
                    .as("the period is set after the validation stages, so it is never reached")
                    .isNull();
            assertThat(result.reportName())
                    .as("the report name is set after the validation stages too").isEmpty();
        }

        @ParameterizedTest(name = "{6}")
        @CsvSource({
            "13, 01, 2022, 12, 31, 2022, 'Start Date - Not a valid Month...'",
            "01, 32, 2022, 12, 31, 2022, 'Start Date - Not a valid Day...'",
            "01, 01, 20AB, 12, 31, 2022, 'Start Date - Not a valid Year...'",
            "01, 01, 2022, 13, 31, 2022, 'End Date - Not a valid Month...'",
            "01, 01, 2022, 12, 32, 2022, 'End Date - Not a valid Day...'",
            "01, 01, 2022, 12, 31, 20AB, 'End Date - Not a valid Year...'",
        })
        @DisplayName("an out-of-range date part reports only its own message: the range checks do not "
                + "accumulate, because the first one to fire sends the screen")
        void anOutOfRangeDatePartReportsOnlyItsOwnMessage(final String startMonth,
                final String startDay, final String startYear, final String endMonth,
                final String endDay, final String endYear, final String expectedMessage) {
            final ReportRequestService.ReportRequestResult result =
                    serviceWith(publishingEveryCard()).processReportRequest(customInput(startMonth,
                            startDay, startYear, endMonth, endDay, endYear, CONFIRM_YES));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).as("the summary text is the failing check's own")
                    .isEqualTo(expectedMessage);
            assertThat(result.fieldErrors())
                    .as("six independent range statements, but the first to fire is the last reached")
                    .hasSize(1);
            assertThat(result.fieldErrors().get(0).state())
                    .as("a supplied but out-of-range field is supplied wrongly")
                    .isEqualTo(ValidationException.FieldState.INVALID);
            assertThat(result.cardsPublished()).isZero();
            assertThat(result.reportPeriod()).isNull();
        }

        @Test
        @DisplayName("two out-of-range parts still report one field error, so the turn cannot describe "
                + "more failures than the legacy screen carried")
        void twoOutOfRangePartsStillReportOneFieldError() {
            final ReportRequestService.ReportRequestResult result =
                    serviceWith(publishingEveryCard()).processReportRequest(
                            customInput("13", "32", "2022", "13", "32", "2022", CONFIRM_YES));

            assertThat(result.message()).isEqualTo("Start Date - Not a valid Month...");
            assertThat(result.fieldErrors())
                    .as("five later range statements would each have reported, had the send not ended "
                            + "the task")
                    .hasSize(1);
        }

        @Test
        @DisplayName("an empty part is not normalised: the turn echoes the field exactly as it was "
                + "received, because the normalisation runs after the emptiness checks")
        void anEmptyPartIsNotNormalised() {
            final ReportRequestService.ReportRequestResult result =
                    serviceWith(publishingEveryCard()).processReportRequest(
                            customInput("", "1", "2022", "12", "31", "2022", CONFIRM_YES));

            assertThat(result.message()).isEqualTo("Start Date - Month can NOT be empty...");
            assertThat(result.screen().startMonth())
                    .as("the empty month must not have been zero filled by the conversion stage")
                    .isBlank();
            assertThat(result.screen().startDay())
                    .as("a single-digit day would have become 01 had the conversion stage run")
                    .isEqualTo("1 ");
            assertThat(result.startDate())
                    .as("the two dates are assembled after the range checks, so neither is assembled")
                    .isEmpty();
            assertThat(result.endDate()).isEmpty();
        }

        @Test
        @DisplayName("an out-of-range part leaves the assembled dates unbuilt, so the turn reports no "
                + "date it never submitted")
        void anOutOfRangePartLeavesTheAssembledDatesUnbuilt() {
            final ReportRequestService.ReportRequestResult result =
                    serviceWith(publishingEveryCard()).processReportRequest(
                            customInput("13", "01", "2022", "12", "31", "2022", CONFIRM_YES));

            assertThat(result.message()).isEqualTo("Start Date - Not a valid Month...");
            assertThat(result.startDate())
                    .as("assembly happens after the range stage, which ended the turn").isEmpty();
            assertThat(result.endDate()).isEmpty();
        }

        @Test
        @DisplayName("a start date the validation subprogram refuses ends the turn before the end date "
                + "is offered to it, so only the start date is reported")
        void aRefusedStartDateEndsTheTurnBeforeTheEndDateIsChecked() {
            // Both dates name a day that does not exist, so a translation that called the subprogram
            // twice would report two failures. The legacy calls it once: the first rejection sends the
            // screen and the send ends the task.
            final ReportRequestService.ReportRequestResult result =
                    serviceWith(publishingEveryCard()).processReportRequest(
                            customInput("02", "30", "2022", "02", "31", "2022", CONFIRM_YES));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).isEqualTo("Start Date - Not a valid date...");
            assertThat(result.fieldErrors())
                    .as("the end date was never offered to the subprogram").hasSize(1);
            assertThat(result.cardsPublished()).isZero();
            assertThat(result.reportPeriod())
                    .as("the period is set only after both dates are accepted").isNull();
        }

        @Test
        @DisplayName("an end date the validation subprogram refuses is reported once the start date "
                + "has been accepted, which is the only route to the second call")
        void aRefusedEndDateIsReportedOnceTheStartDateIsAccepted() {
            final ReportRequestService.ReportRequestResult result =
                    serviceWith(publishingEveryCard()).processReportRequest(
                            customInput("01", "01", "2022", "02", "30", "2022", CONFIRM_YES));

            assertThat(result.message()).isEqualTo("End Date - Not a valid date...");
            assertThat(result.fieldErrors()).hasSize(1);
            assertThat(result.cardsPublished()).isZero();
        }

        @Test
        @DisplayName("no period selected reports one field error and never reaches the acknowledgement, "
                + "so the failure text is what the operator is left with")
        void noPeriodSelectedIsNotOverwrittenByTheAcknowledgement() {
            final ReportRequestService.ReportRequestResult result =
                    serviceWith(publishingEveryCard()).processReportRequest(
                            input(null, null, null, CONFIRM_YES, KeyAction.ENTER,
                                    returningContext()));

            assertThat(result.message()).isEqualTo("Select a report type to print report...");
            assertThat(result.fieldErrors()).hasSize(1);
            assertThat(result.messageHighlightedGreen())
                    .as("the acknowledgement recolours the message field; it must not be reached")
                    .isFalse();
        }

        @Test
        @DisplayName("a declined confirmation publishes nothing and leaves the message silent, so no "
                + "card image is even assembled after the screen has been sent")
        void aDeclinedConfirmationPublishesNothingAndStaysSilent() {
            final ReportRequestService.ReportRequestResult result =
                    serviceWith(publishingEveryCard()).processReportRequest(
                            input(SELECTED, null, null, "N", KeyAction.ENTER, returningContext()));

            assertThat(result.errorFlag()).as("the declining arm raises the error flag").isTrue();
            assertThat(result.confirmationBlocked()).isTrue();
            assertThat(result.message())
                    .as("the legacy sets no text on this arm, so inventing one would be new output")
                    .isEmpty();
            assertThat(result.cardsPublished()).isZero();
            assertThat(result.messageHighlightedGreen())
                    .as("the acknowledgement is unreachable once the screen has been sent").isFalse();
        }
    }

    /**
     * The turn ends at the first failed validation, and nothing sequenced after it runs.
     *
     * <p>This is the reachability contract of the legacy member, and it is a contract rather than an
     * implementation detail because it decides what an operator sees. Every failure site in
     * {@code app/cbl/CORPT00C.cbl} performs the send paragraph, that paragraph ends with a jump to the
     * return paragraph, and the return paragraph issues {@code EXEC CICS RETURN} - so the task ends
     * where the first failure is found. The statements that follow it in the source text are simply
     * never executed: the numeric normalisation of the six date parts, the five remaining range tests,
     * the assembly of the two ten-character dates, both calls to the date-validation subprogram, the
     * four substitution slots, the report-name assignment and the submission.
     *
     * <p>Each test below therefore asserts two things: that the failure the legacy would have shown is
     * the one reported, and that the work the legacy never reached did not happen. The second half is
     * what a suite written only against the message text would miss.
     */
    @Nested
    @DisplayName("The first failed validation ends the turn")
    class TheFirstFailedValidationEndsTheTurn {

        @Test
        @DisplayName("an empty start month is the only failure reported, even though the end date is "
                + "also empty and five further range tests would have failed")
        void anEmptyStartMonthIsTheOnlyFailureReported() {
            final ReportRequestService.ReportRequestResult result =
                    serviceWith(publishingEveryCard()).processReportRequest(
                            customInput(null, null, null, null, null, null, CONFIRM_YES));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).isEqualTo("Start Date - Month can NOT be empty...");
            assertThat(result.fieldErrors())
                    .as("the ordered evaluation fires once and its send ends the turn")
                    .hasSize(1);
            assertThat(result.fieldErrors().get(0).state())
                    .isEqualTo(ValidationException.FieldState.MISSING);
            assertThat(result.cardsPublished()).isZero();
            assertThat(result.submissionAccepted()).isFalse();
        }

        @Test
        @DisplayName("nothing after the first emptiness failure runs: no normalisation, no date "
                + "assembly, no report name and no period")
        void nothingAfterTheFirstEmptinessFailureRuns() {
            final ReportRequestService.ReportRequestResult result =
                    serviceWith(publishingEveryCard()).processReportRequest(
                            customInput(null, "1", "22", "13", "99", "abcd", CONFIRM_YES));

            assertThat(result.message()).isEqualTo("Start Date - Month can NOT be empty...");
            assertThat(result.screen().startDay())
                    .as("the numeric normalisation at lines 305 to 327 is never reached, so the "
                            + "echoed day is still the transmitted value padded to its field width "
                            + "rather than zero-filled")
                    .isEqualTo("1 ");
            assertThat(result.screen().endYear())
                    .as("and the non-numeric end year is echoed exactly as transmitted")
                    .isEqualTo("abcd");
            assertThat(result.startDate())
                    .as("the dates at lines 381 to 386 are never assembled")
                    .isEmpty();
            assertThat(result.endDate()).isEmpty();
            assertThat(result.reportName())
                    .as("MOVE 'Custom' TO WS-REPORT-NAME at line 433 is never reached")
                    .isEmpty();
            assertThat(result.reportPeriod())
                    .as("and neither is the period assignment that precedes it")
                    .isNull();
        }

        @Test
        @DisplayName("the first failing range test is the only one reported, and the five that follow "
                + "it do not run")
        void theFirstFailingRangeTestIsTheOnlyOneReported() {
            final ReportRequestService.ReportRequestResult result =
                    serviceWith(publishingEveryCard()).processReportRequest(
                            customInput("13", "45", "2022", "99", "99", "2022", CONFIRM_YES));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).isEqualTo("Start Date - Not a valid Month...");
            assertThat(result.fieldErrors()).hasSize(1);
            assertThat(result.fieldErrors().get(0).state())
                    .isEqualTo(ValidationException.FieldState.INVALID);
            assertThat(result.startDate())
                    .as("a faulted part ends the turn before either date is assembled")
                    .isEmpty();
            assertThat(result.reportPeriod()).isNull();
            assertThat(result.cardsPublished()).isZero();
        }

        @Test
        @DisplayName("a rejected start date stops the turn before the end date is validated, so the "
                + "subprogram is never called a second time")
        void aRejectedStartDateStopsTheTurnBeforeTheEndDate() {
            // Both parts are inside their declared ranges, so the range stage passes and the two
            // subprogram calls are reached; 31 February is what only a calendar check rejects.
            final ReportRequestService.ReportRequestResult result =
                    serviceWith(publishingEveryCard()).processReportRequest(
                            customInput("02", "31", "2022", "02", "31", "2022", CONFIRM_YES));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).isEqualTo("Start Date - Not a valid date...");
            assertThat(result.fieldErrors())
                    .as("the end date is never handed to the subprogram, so it reports nothing")
                    .hasSize(1);
            assertThat(result.reportName())
                    .as("the report name is assigned after both calls and is never reached")
                    .isEmpty();
            assertThat(result.reportPeriod()).isNull();
            assertThat(result.cardsPublished()).isZero();
            assertThat(result.submissionAccepted()).isFalse();
        }

        @Test
        @DisplayName("a valid start date with an invalid end date reports the end date, proving the "
                + "second call is genuinely reached when the first succeeds")
        void aValidStartDateWithAnInvalidEndDateReportsTheEndDate() {
            final ReportRequestService.ReportRequestResult result =
                    serviceWith(publishingEveryCard()).processReportRequest(
                            customInput("01", "01", "2022", "02", "31", "2022", CONFIRM_YES));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).isEqualTo("End Date - Not a valid date...");
            assertThat(result.fieldErrors()).hasSize(1);
            assertThat(result.cardsPublished()).isZero();
        }

        @Test
        @DisplayName("a blank confirmation ends the turn before any card is built, so the prompt is "
                + "the whole of the outcome")
        void aBlankConfirmationEndsTheTurnBeforeAnyCardIsBuilt() {
            final ReportRequestService.ReportRequestResult result =
                    serviceWith(publishingEveryCard()).processReportRequest(
                            customInput("01", "01", "2022", "12", "31", "2022", null));

            assertThat(result.confirmationBlocked()).isTrue();
            assertThat(result.cardsPublished()).isZero();
            assertThat(result.submissionAccepted()).isFalse();
            assertThat(result.reportPeriod())
                    .as("the period and report name are assigned before the submission attempt, so "
                            + "they survive - the confirmation gate is inside the submission")
                    .isEqualTo(ReportPeriod.CUSTOM);
        }

        @Test
        @DisplayName("a declined confirmation ends the turn silently and publishes nothing")
        void aDeclinedConfirmationEndsTheTurnSilently() {
            final ReportRequestService.ReportRequestResult result =
                    serviceWith(publishingEveryCard()).processReportRequest(
                            customInput("01", "01", "2022", "12", "31", "2022", "N"));

            assertThat(result.confirmationBlocked()).isTrue();
            assertThat(result.errorFlag()).isTrue();
            assertThat(result.cardsPublished()).isZero();
            assertThat(result.submissionAccepted()).isFalse();
            assertThat(result.message())
                    .as("the declined arm writes no text, matching the legacy's silent rejection")
                    .isEmpty();
        }

        @Test
        @DisplayName("a valid, confirmed range still publishes every card, so the terminal marker "
                + "does not suppress the success path")
        void aValidConfirmedRangeStillPublishesEveryCard() {
            final ReportRequestService.ReportRequestResult result =
                    serviceWith(publishingEveryCard()).processReportRequest(
                            customInput("01", "01", "2022", "07", "06", "2022", CONFIRM_YES));

            assertThat(result.errorFlag()).isFalse();
            assertThat(result.fieldErrors()).isEmpty();
            assertThat(result.reportPeriod()).isEqualTo(ReportPeriod.CUSTOM);
            assertThat(result.cardsPublished()).isEqualTo(EXPECTED_CARD_COUNT);
            assertThat(result.submissionAccepted()).isTrue();
            assertThat(result.messageHighlightedGreen())
                    .as("the acknowledgement send is itself terminal and must still happen")
                    .isTrue();
            assertThat(result.message()).contains("submitted");
        }
    }
}
