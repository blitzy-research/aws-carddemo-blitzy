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

import com.carddemo.api.dto.NavigationContext;
import com.carddemo.api.dto.ReportRequest;
import com.carddemo.api.dto.ReportResponse;
import com.carddemo.domain.enums.UserType;
import com.carddemo.service.ReportRequestService;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * The single, lossless conversion between the report-request transport contract and the report service's
 * own turn types.
 *
 * <p>Two problems are closed here at once. The transport request used to carry a derived period
 * enumeration while the service took the three raw screen markers, so the two contracts described
 * different screens and no code connected them - a client's submission could not be turned into a
 * service call without a consumer inventing the missing half. And the service returned a turn result
 * that overlapped the transport response heavily with no mapping between them, so the two could drift
 * apart and every future controller would have re-derived the mapping for itself.
 *
 * <p><strong>Inbound is one-to-one, which is what makes it lossless.</strong> The request now carries
 * the three markers at their declared one-character widths, so the conversion is a straight
 * component-for-component copy with no derivation, no defaulting and no re-ordering. The ordered
 * evaluation that decides which marker wins - month-to-date, then year-to-date, then operator range,
 * first non-blank winning - stays entirely in the service, which is where the legacy program performs
 * it. Nothing here resolves a period, and nothing here can silently pick a different arm from the one
 * the legacy screen would have taken.
 *
 * <p><strong>Outbound is a projection, and the resolved period is published rather than accepted.</strong>
 * The response carries all ten screen fields as the turn leaves them - the three selector positions,
 * the six date parts and the confirmation character - plus the period the service resolved, the header
 * the header paragraph populated, the summary message and its error indicator, the focus field and the
 * route. Every one of those is read from the turn result; none is recomputed here, so the response
 * cannot disagree with the outcome it describes.
 *
 * <p><strong>Why the three selector positions go out as well as in.</strong> They are not a duplicate
 * of the resolved period. The reset paragraph at {@code app/cbl/CORPT00C.cbl:L633-L646} blanks all ten
 * screen fields, so a successful submission and a declined confirmation both return a cleared screen
 * while every error path returns the marks that were transmitted. Publishing only the resolved period
 * would collapse the outbound half of the screen contract exactly as an earlier revision collapsed the
 * inbound half: a client could not re-present two surviving marks, and could not tell a cleared screen
 * from one whose single mark still stands. The marks are therefore taken from the result's own
 * end-of-turn screen, which is where the reset stage recorded what the operator will see.
 *
 * <p><strong>Identity on the returned state is the authenticated identity.</strong> The navigation
 * record is built by {@link ConversationStateAdapter}, so the routing change the service made is merged
 * onto the record the client echoed and the identity members are reconciled against the authenticated
 * principal rather than echoed back.
 *
 * <p>Stateless apart from the injected adapter, holding no mutable field, so the singleton is safe for
 * unsynchronised concurrent use.
 *
 * @since 1.0.0
 */
@Component
public final class ReportContractAdapter {

    /** The single conversion point for the navigation record, injected rather than reimplemented. */
    private final ConversationStateAdapter conversationStateAdapter;

    /**
     * @param conversationStateAdapter the boundary conversion for the navigation record
     */
    public ReportContractAdapter(final ConversationStateAdapter conversationStateAdapter) {
        this.conversationStateAdapter = Objects.requireNonNull(conversationStateAdapter,
                "conversationStateAdapter must not be null");
    }

    /**
     * Converts a submitted request into the service's inbound turn type, component for component.
     *
     * <p>The three markers, the six date parts and the confirmation character cross exactly as
     * transmitted, including {@code null}, the empty string and any blank padding, because the screen
     * fields they come from are fixed-width and blank-significant and the service's own receive stage
     * is what bounds them. The navigation record is reduced to the five-field carried state, dropping
     * the eleven identity and cardholder members the service must not read.
     *
     * @param request the submitted request, never {@code null}
     * @return the service's inbound turn type, never {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     */
    public ReportRequestService.ReportScreenInput toScreenInput(final ReportRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return new ReportRequestService.ReportScreenInput(
                request.monthlySelection(),
                request.yearlySelection(),
                request.customSelection(),
                request.startMonth(),
                request.startDay(),
                request.startYear(),
                request.endMonth(),
                request.endDay(),
                request.endYear(),
                request.confirm(),
                request.keyAction(),
                conversationStateAdapter.toConversationState(request.navigationContext()));
    }

    /**
     * Converts one report turn result into the response the client receives.
     *
     * <p>The screen fields come from the result's own end-of-turn screen rather than from the request,
     * which is what makes a cleared screen after a successful submission and a re-presented screen after
     * a rejection both come out correctly: the service's reset stage has already decided which of the
     * two the operator sees.
     *
     * @param result the service's turn result, never {@code null}
     * @param inboundContext the navigation record the client echoed, which may be {@code null}
     * @param authenticatedUserId the identifier of the authenticated principal, possibly {@code null}
     * @param authenticatedUserType the type of the authenticated principal, possibly {@code null}
     * @return the transport response, never {@code null}
     * @throws NullPointerException if {@code result} is {@code null}
     */
    public ReportResponse toResponse(final ReportRequestService.ReportRequestResult result,
                                     final NavigationContext inboundContext,
                                     final String authenticatedUserId,
                                     final UserType authenticatedUserType) {
        Objects.requireNonNull(result, "result must not be null");
        final ReportRequestService.ScreenFields screen = result.screen();
        final ReportRequestService.ScreenHeader header = result.header();
        final NavigationContext outboundContext = conversationStateAdapter.toNavigationContext(
                inboundContext, result.navigationContext(), authenticatedUserId,
                authenticatedUserType);

        return new ReportResponse(
                screen.monthlySelection(),
                screen.yearlySelection(),
                screen.customSelection(),
                result.reportPeriod(),
                screen.startMonth(),
                screen.startDay(),
                screen.startYear(),
                screen.endMonth(),
                screen.endDay(),
                screen.endYear(),
                screen.confirm(),
                header.transactionName(),
                header.title01(),
                header.currentDate(),
                header.programName(),
                header.title02(),
                header.currentTime(),
                header.errorMessage(),
                result.submissionAccepted(),
                result.message(),
                result.errorFlag(),
                result.focusField(),
                result.route().getRouteValue(),
                outboundContext);
    }
}
