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
package com.carddemo.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.carddemo.api.dto.ErrorResponse;
import com.carddemo.api.dto.NavigationContext;
import com.carddemo.api.dto.ReportRequest;
import com.carddemo.api.dto.ReportResponse;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.domain.enums.ReportPeriod;
import com.carddemo.domain.enums.UserType;
import com.carddemo.exception.ValidationException;
import com.carddemo.service.ConversationState;
import com.carddemo.service.NavigationService;
import com.carddemo.service.ReportRequestService;
import com.carddemo.util.JclCardImageBuilder;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Unit tests for {@link ReportContractAdapter}, which converts the submitted report request into the
 * service's inbound turn type and the service's turn result into the response.
 *
 * <p><strong>The inbound conversion must be lossless, component for component.</strong> That is the
 * central claim of this file, and it is what makes the three-marker inbound contract worth having. The
 * screen declares three independently markable one-character selector fields at symbolic-map lines 60,
 * 66 and 72, and {@code app/cbl/CORPT00C.cbl} tests them in the fixed order month-to-date at line 214,
 * year-to-date at line 240 and operator-supplied range at line 256, acting on the first non-blank one.
 * If the adapter collapsed, normalised, trimmed or reordered any of the three on the way in, the
 * service's ordered evaluation would be deciding on values the operator did not send. The conversion is
 * therefore asserted to be a pure one-to-one carry, including the blank and empty cases, and including a
 * multiply-marked submission - which is a state the 3270 screen can genuinely produce.
 *
 * <p><strong>The outbound conversion is deliberately asymmetric, and that is the point.</strong> The
 * request carries three markers and no resolved period; the response carries the resolved period and no
 * markers. The resolution is the program's, so it is produced rather than accepted, and the two
 * assertions that pin this are the shape assertions in the last nested class.
 *
 * <p><strong>What the adapter must not do.</strong> It recomputes nothing. Every screen field on the
 * response comes from the result's own end-of-turn screen rather than from the request, because the
 * service's reset stage has already decided whether the operator sees a cleared screen or a
 * re-presented one; and every header item, message and flag comes from the result. A recomputation here
 * would let the response disagree with the outcome it describes.
 *
 * <p>A pure unit test: no Spring context, no connection, no container. The navigation-record conversion
 * is the real {@link ConversationStateAdapter} rather than a double, because a double would only restate
 * this file's own expectations.
 *
 * @since 1.0.0
 */
@DisplayName("ReportContractAdapter :: the CR00 request and response boundary")
final class ReportContractAdapterTest {

    /** The one character a marked selector position carried on the legacy screen. */
    private static final String MARK = "Y";

    /** The identifier of the authenticated principal. */
    private static final String AUTHENTICATED_USER_ID = "ADMIN001";

    /** The identifier the client echoes, which is deliberately not the authenticated one. */
    private static final String ECHOED_USER_ID = "STALEUSR";

    /**
     * The ten screen components the inbound conversion must carry across untouched, in screen order.
     */
    private static final List<String> CARRIED_SCREEN_COMPONENTS = List.of(
            "monthlySelection", "yearlySelection", "customSelection",
            "startMonth", "startDay", "startYear", "endMonth", "endDay", "endYear", "confirm");

    /** Subject under test. */
    private ReportContractAdapter subject;

    /** Sets up the adapter with the real navigation-record conversion. */
    @BeforeEach
    void setUp() {
        subject = new ReportContractAdapter(new ConversationStateAdapter(new NavigationService()));
    }

    /**
     * Builds a request marking the given selector positions and populating both date triples.
     *
     * @param monthly the month-to-date marker, which may be {@code null}
     * @param yearly the year-to-date marker, which may be {@code null}
     * @param custom the operator-range marker, which may be {@code null}
     * @return a request carrying those markers, both date triples, a confirmation and a key
     */
    private static ReportRequest requestMarking(final String monthly, final String yearly,
                                                final String custom) {
        return new ReportRequest(monthly, yearly, custom, "01", "15", "2022", "03", "31", "2022",
                "Y", KeyAction.ENTER, echoedContext());
    }

    /**
     * Builds a wire record with every one of the sixteen members populated, whose identity members
     * deliberately disagree with the authenticated principal.
     *
     * @return a fully populated record
     */
    private static NavigationContext echoedContext() {
        return new NavigationContext("CR00", "CORPT00C", "CM00", "COMEN01C",
                ECHOED_USER_ID, UserType.USER.getCode(), NavigationContext.ProgramContext.REENTER,
                "000000123", "MARY", "ANN", "SMITH", "00000000011", "Y", "4111111111111111",
                "CORPT0A", "CORPT00");
    }

    /**
     * Builds a turn result with every component populated.
     *
     * @return a fully populated turn result
     */
    private static ReportRequestService.ReportRequestResult resultOf() {
        return new ReportRequestService.ReportRequestResult(
                NavigationService.Route.REPORT_REQUEST,
                new ConversationState("CR00", "CORPT00C", "CM00", "COMEN01C",
                        ConversationState.EntryMode.RE_ENTRY),
                "CR00",
                ReportPeriod.CUSTOM,
                "Custom",
                "2022-01-15",
                "2022-03-31",
                JclCardImageBuilder.CARD_COUNT,
                false,
                "Job submitted",
                true,
                "CONFIRM",
                false,
                List.of(),
                new ReportRequestService.ScreenHeader("CardDemo", "Report Request", "CR00",
                        "CORPT00C", "07/19/22", "14:30:00", null),
                new ReportRequestService.ScreenFields(null, null, MARK,
                        "01", "15", "2022", "03", "31", "2022", "Y"));
    }

    // ----------------------------------------------------------------------------------------
    // Inbound: lossless, component for component
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the inbound conversion is lossless, component for component")
    final class TheInboundConversionIsLossless {

        @Test
        @DisplayName("the two types declare the same ten screen components in the same order, so the "
                + "conversion has nowhere to lose or reorder one")
        void bothTypesDeclareTheSameTenScreenComponentsInOrder() {
            final List<String> onTheWire = Arrays.stream(ReportRequest.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .filter(CARRIED_SCREEN_COMPONENTS::contains)
                    .toList();
            final List<String> inTheService = Arrays.stream(
                            ReportRequestService.ReportScreenInput.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .filter(CARRIED_SCREEN_COMPONENTS::contains)
                    .toList();

            assertThat(onTheWire).containsExactlyElementsOf(CARRIED_SCREEN_COMPONENTS);
            assertThat(inTheService).containsExactlyElementsOf(CARRIED_SCREEN_COMPONENTS);
        }

        @Test
        @DisplayName("every one of the ten screen components crosses with the value transmitted, and "
                + "the attention key crosses as the same constant")
        void everyScreenComponentCrossesWithTheValueTransmitted() {
            final ReportRequestService.ReportScreenInput input =
                    subject.toScreenInput(requestMarking(null, null, MARK));

            assertThat(input.monthlySelection()).isNull();
            assertThat(input.yearlySelection()).isNull();
            assertThat(input.customSelection()).isEqualTo(MARK);
            assertThat(input.startMonth()).isEqualTo("01");
            assertThat(input.startDay()).isEqualTo("15");
            assertThat(input.startYear()).isEqualTo("2022");
            assertThat(input.endMonth()).isEqualTo("03");
            assertThat(input.endDay()).isEqualTo("31");
            assertThat(input.endYear()).isEqualTo("2022");
            assertThat(input.confirm()).isEqualTo("Y");
            assertThat(input.keyAction()).isSameAs(KeyAction.ENTER);
        }

        @ParameterizedTest(name = "a submission marking only the {0} position arrives on that position")
        @EnumSource(ReportPeriod.class)
        @DisplayName("each of the three positions is carried on its own, so the service's ordered "
                + "evaluation reads the position the operator actually marked")
        void eachPositionIsCarriedOnItsOwn(final ReportPeriod period) {
            final ReportRequestService.ReportScreenInput input = subject.toScreenInput(
                    requestMarking(period == ReportPeriod.MONTHLY ? MARK : null,
                            period == ReportPeriod.YEARLY ? MARK : null,
                            period == ReportPeriod.CUSTOM ? MARK : null));

            assertThat(List.of(
                    input.monthlySelection() == null ? "" : input.monthlySelection(),
                    input.yearlySelection() == null ? "" : input.yearlySelection(),
                    input.customSelection() == null ? "" : input.customSelection()))
                    .as("exactly the marked position carries the mark for %s", period)
                    .containsExactly(
                            period == ReportPeriod.MONTHLY ? MARK : "",
                            period == ReportPeriod.YEARLY ? MARK : "",
                            period == ReportPeriod.CUSTOM ? MARK : "");
        }

        @Test
        @DisplayName("a multiply-marked submission crosses with all three marks intact, so the "
                + "first-match-wins resolution stays the program's to make and is not pre-decided here")
        void aMultiplyMarkedSubmissionCrossesIntact() {
            final ReportRequestService.ReportScreenInput input =
                    subject.toScreenInput(requestMarking(MARK, MARK, MARK));

            assertThat(input.monthlySelection()).isEqualTo(MARK);
            assertThat(input.yearlySelection()).isEqualTo(MARK);
            assertThat(input.customSelection()).isEqualTo(MARK);
        }

        @Test
        @DisplayName("a marker carrying any other non-blank character crosses verbatim, because the "
                + "program tests only that a position is non-blank and never which character marked it")
        void anyNonBlankMarkerCharacterCrossesVerbatim() {
            final ReportRequestService.ReportScreenInput input =
                    subject.toScreenInput(requestMarking("X", null, "1"));

            assertThat(input.monthlySelection()).isEqualTo("X");
            assertThat(input.customSelection()).isEqualTo("1");
        }

        @Test
        @DisplayName("a blank marker crosses as a blank rather than being normalised to absence, "
                + "because the fields are fixed-width and blank-significant")
        void aBlankMarkerCrossesAsABlank() {
            final ReportRequestService.ReportScreenInput input =
                    subject.toScreenInput(requestMarking(" ", "", null));

            assertThat(input.monthlySelection()).isEqualTo(" ");
            assertThat(input.yearlySelection()).isEmpty();
            assertThat(input.customSelection()).isNull();
        }

        @Test
        @DisplayName("an unmarked submission with no dates crosses as absence throughout rather than "
                + "acquiring a default, because marking none is the state the catch-all arm answers")
        void anUnmarkedSubmissionCrossesAsAbsenceThroughout() {
            final ReportRequestService.ReportScreenInput input = subject.toScreenInput(
                    new ReportRequest(null, null, null, null, null, null, null, null, null, null,
                            null, null));

            assertThat(input.monthlySelection()).isNull();
            assertThat(input.yearlySelection()).isNull();
            assertThat(input.customSelection()).isNull();
            assertThat(input.startMonth()).isNull();
            assertThat(input.endYear()).isNull();
            assertThat(input.confirm()).isNull();
            assertThat(input.keyAction()).isNull();
        }

        @Test
        @DisplayName("an over-long date part crosses unshortened, because the width rule is the "
                + "service's own receive stage to apply and truncating here would hide the violation")
        void anOverLongDatePartCrossesUnshortened() {
            final ReportRequestService.ReportScreenInput input = subject.toScreenInput(
                    new ReportRequest(MARK, null, null, "123", null, "20222", null, null, null, "YY",
                            KeyAction.ENTER, null));

            assertThat(input.startMonth()).isEqualTo("123");
            assertThat(input.startYear()).isEqualTo("20222");
            assertThat(input.confirm()).isEqualTo("YY");
        }

        @Test
        @DisplayName("the navigation record is reduced to the five carried fields, so the eleven "
                + "echoed identity and cardholder members never reach the service")
        void theNavigationRecordIsReducedToTheFiveCarriedFields() {
            final ReportRequestService.ReportScreenInput input =
                    subject.toScreenInput(requestMarking(MARK, null, null));

            assertThat(input.navigationContext().fromTransactionId()).isEqualTo("CR00");
            assertThat(input.navigationContext().fromProgram()).isEqualTo("CORPT00C");
            assertThat(input.navigationContext().entryMode())
                    .isSameAs(ConversationState.EntryMode.RE_ENTRY);
            assertThat(input.navigationContext().toString())
                    .doesNotContain(ECHOED_USER_ID)
                    .doesNotContain("000000123")
                    .doesNotContain("4111111111111111");
        }

        @Test
        @DisplayName("an absent navigation record becomes the empty carried state, so the service "
                + "never has to null-check the state it is handed")
        void anAbsentNavigationRecordBecomesTheEmptyCarriedState() {
            final ReportRequestService.ReportScreenInput input = subject.toScreenInput(
                    new ReportRequest(MARK, null, null, null, null, null, null, null, null, null,
                            null, null));

            assertThat(input.navigationContext()).isSameAs(ConversationState.empty());
        }

        @Test
        @DisplayName("refuses an absent request rather than converting nothing into an empty turn")
        void refusesAnAbsentRequest() {
            assertThatNullPointerException()
                    .isThrownBy(() -> subject.toScreenInput(null))
                    .withMessageContaining("request");
        }

        @Test
        @DisplayName("repeated conversions of the same request are equal, so the boundary carries "
                + "nothing over between turns")
        void repeatedConversionsAreEqual() {
            assertThat(subject.toScreenInput(requestMarking(MARK, null, MARK)))
                    .isEqualTo(subject.toScreenInput(requestMarking(MARK, null, MARK)));
        }
    }

    // ----------------------------------------------------------------------------------------
    // Outbound: read from the result, recomputed nowhere
    // ----------------------------------------------------------------------------------------

    /**
     * The per-field detail the turn computed crosses this boundary rather than being dropped here.
     *
     * <p>The service records one entry per faulted field, in the order it edited them, and distinguishes
     * a field the operator never marked from one that was marked and cannot be used. That distinction is
     * what the legacy screen draws with an asterisk beside the first kind and only a colour change on the
     * second, so a response carrying the whole-screen flag alone leaves a client able to say that
     * something is wrong and unable to say what or where.</p>
     */
    @Nested
    @DisplayName("the outbound conversion publishes the per-field detail the turn computed")
    final class TheOutboundConversionPublishesFieldDetail {

        /**
         * Builds a turn result carrying the supplied field findings and nothing else of note.
         *
         * @param fieldErrors the findings the turn raised, in the order it raised them
         * @return the result
         */
        private static ReportRequestService.ReportRequestResult faulting(
                final List<ValidationException.FieldError> fieldErrors) {
            return new ReportRequestService.ReportRequestResult(
                    NavigationService.Route.REPORT_REQUEST,
                    new ConversationState("CR00", "CORPT00C", "CR00", "CORPT00C",
                            ConversationState.EntryMode.RE_ENTRY),
                    "CR00", null, null, null, null, 0, false,
                    "Please select a report type", false, "MONTHLY", true, fieldErrors,
                    new ReportRequestService.ScreenHeader("CardDemo", "Report Request", "CR00",
                            "CORPT00C", "07/19/22", "14:30:00", null),
                    new ReportRequestService.ScreenFields(null, null, null, null, null, null, null,
                            null, null, null));
        }

        @Test
        @DisplayName("both states cross under their own names, in the order the turn raised them")
        void bothStatesCrossInOrder() {
            final ReportResponse response = subject.toResponse(faulting(List.of(
                    new ValidationException.FieldError("reportType",
                            ReportResponse.FIELD_MONTHLY_SELECTION,
                            ValidationException.FieldState.MISSING,
                            "Please select a report type"),
                    new ValidationException.FieldError("startDay", ReportResponse.FIELD_START_DAY,
                            ValidationException.FieldState.INVALID,
                            "Start date day is not valid"))),
                    echoedContext(), AUTHENTICATED_USER_ID, UserType.ADMIN);

            assertThat(response.fieldErrors()).hasSize(2);
            assertThat(response.fieldErrors().get(0).fieldName()).isEqualTo("reportType");
            assertThat(response.fieldErrors().get(0).screenFieldId())
                    .isEqualTo(ReportResponse.FIELD_MONTHLY_SELECTION);
            assertThat(response.fieldErrors().get(0).state())
                    .isEqualTo(ErrorResponse.FieldState.MISSING);
            assertThat(response.fieldErrors().get(0).message())
                    .isEqualTo("Please select a report type");
            assertThat(response.fieldErrors().get(1).fieldName()).isEqualTo("startDay");
            assertThat(response.fieldErrors().get(1).screenFieldId())
                    .isEqualTo(ReportResponse.FIELD_START_DAY);
            assertThat(response.fieldErrors().get(1).state())
                    .isEqualTo(ErrorResponse.FieldState.INVALID);
        }

        /**
         * The two states stay distinguishable, which is the whole reason the list exists.
         */
        @Test
        @DisplayName("a field that was never marked and one that was marked wrongly publish as "
                + "different states")
        void theTwoStatesRemainDistinguishable() {
            final ReportResponse missing = subject.toResponse(faulting(List.of(
                    new ValidationException.FieldError("reportType",
                            ReportResponse.FIELD_MONTHLY_SELECTION,
                            ValidationException.FieldState.MISSING, "text"))),
                    echoedContext(), AUTHENTICATED_USER_ID, UserType.ADMIN);
            final ReportResponse invalid = subject.toResponse(faulting(List.of(
                    new ValidationException.FieldError("confirm", ReportResponse.FIELD_CONFIRM,
                            ValidationException.FieldState.INVALID, "text"))),
                    echoedContext(), AUTHENTICATED_USER_ID, UserType.ADMIN);

            assertThat(missing.fieldErrors().getFirst().state())
                    .isEqualTo(ErrorResponse.FieldState.MISSING)
                    .isNotEqualTo(invalid.fieldErrors().getFirst().state());
        }

        @Test
        @DisplayName("all six date parts publish under their own map identifiers, so a client can "
                + "highlight the exact part that failed")
        void everyDatePartPublishesUnderItsOwnIdentifier() {
            final List<String> identifiers = List.of(ReportResponse.FIELD_START_MONTH,
                    ReportResponse.FIELD_START_DAY, ReportResponse.FIELD_START_YEAR,
                    ReportResponse.FIELD_END_MONTH, ReportResponse.FIELD_END_DAY,
                    ReportResponse.FIELD_END_YEAR);
            final List<ValidationException.FieldError> raised = identifiers.stream()
                    .map(identifier -> new ValidationException.FieldError(identifier, identifier,
                            ValidationException.FieldState.INVALID, "not valid"))
                    .toList();

            final ReportResponse response = subject.toResponse(faulting(raised), echoedContext(),
                    AUTHENTICATED_USER_ID, UserType.ADMIN);

            assertThat(response.fieldErrors())
                    .extracting(ErrorResponse.FieldError::screenFieldId)
                    .containsExactlyElementsOf(identifiers);
        }

        @Test
        @DisplayName("a turn that faulted nothing publishes an empty list, not an absent one")
        void aCleanTurnPublishesAnEmptyList() {
            final ReportResponse response = subject.toResponse(resultOf(), echoedContext(),
                    AUTHENTICATED_USER_ID, UserType.ADMIN);

            assertThat(response.fieldErrors()).isNotNull().isEmpty();
        }

        /**
         * The adapter is a mapping and does not decide which fields are at fault.
         */
        @Test
        @DisplayName("the adapter neither composes nor filters the detail, so an entry with no text "
                + "crosses with no text")
        void theAdapterNeitherComposesNorFilters() {
            final ReportResponse response = subject.toResponse(faulting(List.of(
                    new ValidationException.FieldError("reportType",
                            ReportResponse.FIELD_MONTHLY_SELECTION,
                            ValidationException.FieldState.MISSING, null))),
                    echoedContext(), AUTHENTICATED_USER_ID, UserType.ADMIN);

            assertThat(response.fieldErrors()).singleElement()
                    .satisfies(finding -> assertThat(finding.message()).isNull());
        }
    }

    @Nested
    @DisplayName("the outbound conversion reads the turn result and recomputes nothing")
    final class TheOutboundConversionReadsTheResult {

        @Test
        @DisplayName("the resolved period, the header items, the message, the flags, the focus field "
                + "and the route all come from the result")
        void everyResponseComponentComesFromTheResult() {
            final ReportResponse response = subject.toResponse(resultOf(), echoedContext(),
                    AUTHENTICATED_USER_ID, UserType.ADMIN);

            assertThat(response.reportPeriod()).isSameAs(ReportPeriod.CUSTOM);
            assertThat(response.transactionName()).isEqualTo("CR00");
            assertThat(response.programName()).isEqualTo("CORPT00C");
            assertThat(response.title01()).isEqualTo("CardDemo");
            assertThat(response.title02()).isEqualTo("Report Request");
            assertThat(response.currentDate()).isEqualTo("07/19/22");
            assertThat(response.currentTime()).isEqualTo("14:30:00");
            assertThat(response.errorMessage()).isNull();
            assertThat(response.message()).isEqualTo("Job submitted");
            assertThat(response.generalError()).isFalse();
            assertThat(response.focusScreenFieldId()).isEqualTo("CONFIRM");
            assertThat(response.nextRoute())
                    .isEqualTo(NavigationService.Route.REPORT_REQUEST.getRouteValue());
            assertThat(response.submissionAccepted())
                    .as("acceptance is the result's own derivation from its error flag and its "
                            + "published card count, never recomputed here")
                    .isTrue();
        }

        @Test
        @DisplayName("the screen fields come from the result's end-of-turn screen and not from the "
                + "request, so a cleared screen after a successful submission is published as cleared")
        void theScreenFieldsComeFromTheResultAndNotTheRequest() {
            final ReportRequestService.ReportRequestResult cleared =
                    new ReportRequestService.ReportRequestResult(
                            NavigationService.Route.REPORT_REQUEST,
                            ConversationState.empty(), "CR00", ReportPeriod.MONTHLY, "Monthly",
                            null, null, JclCardImageBuilder.CARD_COUNT, false,
                            "Job submitted", true, "MONTHLY", false, List.of(),
                            new ReportRequestService.ScreenHeader(null, null, "CR00", "CORPT00C",
                                    null, null, null),
                            new ReportRequestService.ScreenFields(null, null, null,
                                    null, null, null, null, null, null, null));

            final ReportResponse response =
                    subject.toResponse(cleared, echoedContext(), AUTHENTICATED_USER_ID,
                            UserType.ADMIN);

            assertThat(response.startMonth()).isNull();
            assertThat(response.startDay()).isNull();
            assertThat(response.startYear()).isNull();
            assertThat(response.endMonth()).isNull();
            assertThat(response.endDay()).isNull();
            assertThat(response.endYear()).isNull();
            assertThat(response.confirm()).isNull();
            assertThat(response.reportPeriod())
                    .as("the resolved period is still published even when the screen was cleared")
                    .isSameAs(ReportPeriod.MONTHLY);
        }

        @Test
        @DisplayName("a re-presented screen publishes the fields the reset stage left standing, "
                + "unchanged and unreformatted")
        void aRePresentedScreenPublishesTheFieldsLeftStanding() {
            final ReportResponse response = subject.toResponse(resultOf(), null, null, null);

            assertThat(response.startMonth()).isEqualTo("01");
            assertThat(response.startDay()).isEqualTo("15");
            assertThat(response.startYear()).isEqualTo("2022");
            assertThat(response.endMonth()).isEqualTo("03");
            assertThat(response.endDay()).isEqualTo("31");
            assertThat(response.endYear()).isEqualTo("2022");
            assertThat(response.confirm()).isEqualTo("Y");
        }

        @Test
        @DisplayName("the navigation record on the response is the reconciled one, so the identity it "
                + "carries is the authenticated principal's rather than the client's echo")
        void theNavigationRecordOnTheResponseIsReconciled() {
            final ReportResponse response = subject.toResponse(resultOf(), echoedContext(),
                    AUTHENTICATED_USER_ID, UserType.ADMIN);

            assertThat(response.navigationContext().userId())
                    .isEqualTo(AUTHENTICATED_USER_ID)
                    .isNotEqualTo(ECHOED_USER_ID);
            assertThat(response.navigationContext().userType()).isEqualTo(UserType.ADMIN.getCode());
            assertThat(response.navigationContext().customerLastName())
                    .as("the members the service never saw are still carried through")
                    .isEqualTo("SMITH");
            assertThat(response.navigationContext().fromProgram())
                    .as("the routing the service recorded is what the response publishes")
                    .isEqualTo("CORPT00C");
        }

        @Test
        @DisplayName("an absent echoed record still yields a publishable navigation record")
        void anAbsentEchoedRecordStillYieldsAPublishableRecord() {
            final ReportResponse response =
                    subject.toResponse(resultOf(), null, AUTHENTICATED_USER_ID, UserType.ADMIN);

            assertThat(response.navigationContext()).isNotNull();
            assertThat(response.navigationContext().userId()).isEqualTo(AUTHENTICATED_USER_ID);
            assertThat(response.navigationContext().customerId()).isNull();
        }

        @Test
        @DisplayName("refuses an absent turn result rather than inventing a response for it")
        void refusesAnAbsentTurnResult() {
            assertThatNullPointerException()
                    .isThrownBy(() -> subject.toResponse(null, null, null, null))
                    .withMessageContaining("result");
        }

        @Test
        @DisplayName("repeated conversions of the same result are equal")
        void repeatedConversionsAreEqual() {
            assertThat(subject.toResponse(resultOf(), echoedContext(), AUTHENTICATED_USER_ID,
                    UserType.ADMIN))
                    .isEqualTo(subject.toResponse(resultOf(), echoedContext(), AUTHENTICATED_USER_ID,
                            UserType.ADMIN));
        }
    }

    // ----------------------------------------------------------------------------------------
    // The asymmetry: markers inbound, resolved period outbound
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("markers arrive and a resolved period departs, and the asymmetry is the contract")
    final class MarkersArriveAndAResolvedPeriodDeparts {

        @Test
        @DisplayName("the request declares the three markers and no resolved period, because the "
                + "ordered evaluation is the program's to perform")
        void theRequestDeclaresMarkersAndNoResolvedPeriod() {
            final List<String> declared = Arrays.stream(ReportRequest.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared).containsSubsequence("monthlySelection", "yearlySelection",
                    "customSelection");
            assertThat(declared).doesNotContain("reportPeriod");
        }

        @Test
        @DisplayName("the response declares the resolved period alongside the three re-presented "
                + "markers, because the period is produced while the markers are echoed back")
        void theResponseDeclaresTheResolvedPeriodAlongsideTheMarkers() {
            final List<String> declared = Arrays.stream(ReportResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared)
                    .as("the period the ordered evaluation resolved is published, never accepted")
                    .contains("reportPeriod");
            assertThat(declared)
                    .as("the three positions are published too, because the reset paragraph at "
                            + "app/cbl/CORPT00C.cbl:L633-L646 blanks them on a successful submission "
                            + "while every error path returns the transmitted marks still standing, "
                            + "and a response naming only the period could describe neither state")
                    .containsSubsequence("monthlySelection", "yearlySelection", "customSelection");
            assertThat(declared.indexOf("customSelection"))
                    .as("the markers lead the resolved period, as the map declares them")
                    .isLessThan(declared.indexOf("reportPeriod"));
        }

        @Test
        @DisplayName("the marks the response publishes come from the result's end-of-turn screen and "
                + "not from the resolved period, so they can disagree with it")
        void theMarksPublishedComeFromTheEndOfTurnScreen() {
            final ReportRequestService.ReportRequestResult staleMark =
                    new ReportRequestService.ReportRequestResult(
                            NavigationService.Route.REPORT_REQUEST, ConversationState.empty(),
                            "CR00", ReportPeriod.MONTHLY, "Monthly", null, null, 0, false,
                            null, false, null, false, List.of(),
                            new ReportRequestService.ScreenHeader(null, null, null, null, null,
                                    null, null),
                            new ReportRequestService.ScreenFields(MARK, null, MARK,
                                    null, null, null, null, null, null, null));

            final ReportResponse response = subject.toResponse(staleMark, null, null, null);

            assertThat(response.monthlySelection()).isEqualTo(MARK);
            assertThat(response.yearlySelection()).isNull();
            assertThat(response.customSelection())
                    .as("a mark the reset stage left standing is re-presented even though the "
                            + "resolved period is not the one it names")
                    .isEqualTo(MARK);
            assertThat(response.reportPeriod()).isSameAs(ReportPeriod.MONTHLY);
        }

        @Test
        @DisplayName("a cleared end-of-turn screen publishes all three marks as absent, which is what "
                + "a successful submission and a declined confirmation both return")
        void aClearedScreenPublishesAllThreeMarksAsAbsent() {
            final ReportResponse response = subject.toResponse(
                    new ReportRequestService.ReportRequestResult(
                            NavigationService.Route.REPORT_REQUEST, ConversationState.empty(),
                            "CR00", ReportPeriod.CUSTOM, "Custom", null, null,
                            JclCardImageBuilder.CARD_COUNT, false, "Job submitted", true, null,
                            false, List.of(),
                            new ReportRequestService.ScreenHeader(null, null, null, null, null,
                                    null, null),
                            new ReportRequestService.ScreenFields(null, null, null,
                                    null, null, null, null, null, null, null)),
                    null, null, null);

            assertThat(response.monthlySelection()).isNull();
            assertThat(response.yearlySelection()).isNull();
            assertThat(response.customSelection()).isNull();
            assertThat(response.submissionAccepted()).isTrue();
        }

        @Test
        @DisplayName("the adapter never derives the period itself: the response publishes the period "
                + "the result named, even where the screen fields would suggest another")
        void theAdapterNeverDerivesThePeriodItself() {
            // The end-of-turn screen here marks the custom position while the result names the
            // month-to-date period. That combination is exactly what the reset stage produces when a
            // month-to-date submission is accepted and a stale custom mark is left standing, and the
            // adapter must publish what the service resolved rather than re-reading the marks.
            final ReportRequestService.ReportRequestResult disagreeing =
                    new ReportRequestService.ReportRequestResult(
                            NavigationService.Route.REPORT_REQUEST, ConversationState.empty(),
                            "CR00", ReportPeriod.MONTHLY, "Monthly", null, null, 0, false,
                            null, false, null, false, List.of(),
                            new ReportRequestService.ScreenHeader(null, null, null, null, null,
                                    null, null),
                            new ReportRequestService.ScreenFields(null, null, MARK,
                                    null, null, null, null, null, null, null));

            assertThat(subject.toResponse(disagreeing, null, null, null).reportPeriod())
                    .isSameAs(ReportPeriod.MONTHLY);
        }

        @Test
        @DisplayName("an unresolved period is published as absence rather than as a default, because "
                + "the unmarked screen is a state the program reports with its own message")
        void anUnresolvedPeriodIsPublishedAsAbsence() {
            final ReportRequestService.ReportRequestResult unresolved =
                    new ReportRequestService.ReportRequestResult(
                            NavigationService.Route.REPORT_REQUEST, ConversationState.empty(),
                            "CR00", null, null, null, null, 0, false,
                            "Select a report type to print report", false, "MONTHLY", true,
                            List.of(),
                            new ReportRequestService.ScreenHeader(null, null, null, null, null,
                                    null, null),
                            new ReportRequestService.ScreenFields(null, null, null,
                                    null, null, null, null, null, null, null));

            final ReportResponse response = subject.toResponse(unresolved, null, null, null);

            assertThat(response.reportPeriod()).isNull();
            assertThat(response.generalError()).isTrue();
            assertThat(response.submissionAccepted()).isFalse();
            assertThat(response.focusScreenFieldId())
                    .as("the catch-all arm places the cursor on the first position it tested")
                    .isEqualTo("MONTHLY");
        }
    }

    // ----------------------------------------------------------------------------------------
    // The adapter itself
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the adapter is a boundary mapping and holds no decision of its own")
    final class TheAdapterIsABoundaryMapping {

        @Test
        @DisplayName("refuses construction without the navigation-record conversion, because a "
                + "response that skipped reconciliation would carry a client-supplied identity")
        void refusesConstructionWithoutTheNavigationConversion() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new ReportContractAdapter(null))
                    .withMessageContaining("conversationStateAdapter");
        }

        @Test
        @DisplayName("declares exactly the two public conversions, so no period is resolved, no date "
                + "is validated and no job is submitted at the boundary")
        void declaresExactlyTheTwoPublicConversions() {
            assertThat(Arrays.stream(ReportContractAdapter.class.getDeclaredMethods())
                    .filter(method -> !method.isSynthetic())
                    .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                    .map(java.lang.reflect.Method::getName)
                    .toList())
                    .containsExactlyInAnyOrder("toScreenInput", "toResponse");
        }

        @Test
        @DisplayName("carries no job-submission detail across in either direction: no card image, no "
                + "queue name and no sentinel appears on either type")
        void carriesNoJobSubmissionDetailInEitherDirection() {
            final List<String> inbound = Arrays.stream(
                            ReportRequestService.ReportScreenInput.class.getRecordComponents())
                    .map(RecordComponent::getName).toList();
            final List<String> outbound = Arrays.stream(ReportResponse.class.getRecordComponents())
                    .map(RecordComponent::getName).toList();

            assertThat(inbound).doesNotContain("cards", "cardImages", "jobName", "queueName",
                    "sentinel");
            assertThat(outbound).doesNotContain("cards", "cardImages", "jobName", "queueName",
                    "sentinel");
        }
    }
}
