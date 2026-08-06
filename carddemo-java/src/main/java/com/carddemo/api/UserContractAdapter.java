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

import com.carddemo.api.dto.ErrorResponse;
import com.carddemo.api.dto.UserRequest;
import com.carddemo.api.dto.UserResponse;
import com.carddemo.exception.ValidationException;
import com.carddemo.service.UserCommand;
import com.carddemo.service.UserOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * The single boundary at which the user-administration wire contract and the service-owned command and
 * outcome are converted into one another.
 *
 * <p><strong>Why the conversion exists.</strong> The module's layering runs one way: the API layer may
 * depend on the service layer and nothing may depend upward. A service that took the wire request as a
 * parameter, or returned the wire response, inverted that direction. {@code api.dto.UserRequest} and
 * {@code api.dto.UserResponse} therefore stay where they are, with their width bounds, their four
 * operation groups and their serialization contract, and {@link UserCommand} and {@link UserOutcome} carry
 * the same components with none of that. This class is the only place either direction happens.
 *
 * <p><strong>Every conversion is a positional copy and nothing more.</strong> Twelve components cross
 * inbound and nineteen cross outbound, each under the same name on both sides, and not one value is
 * trimmed, padded, defaulted, re-cased or reordered. Three details make that stricter than it sounds: the
 * displayed page number is text rather than a number so its leading zeros survive; the ten selection
 * characters are positional, so their order is the row order they were marked in; and the page of rows is
 * published in the order the browse settled them, which for a backward page is the order the legacy screen
 * presented after filling its rows downward from the last slot.
 *
 * <p><strong>What this class deliberately does not do.</strong> It applies no validation, resolves no
 * route, reads no repository, composes no message and performs no paging arithmetic. It does not reconcile
 * the echoed identity either: these four screens carry all sixteen communication-area fields across a turn
 * and hand back what they were given, exactly as their legacy programs did.
 *
 * <p>The carriers it does not convert itself are delegated to {@link ScreenStateAdapter}, which is the
 * module's single seam for the communication area and the browse window alike; the per-field findings are
 * translated to the transport contract's own vocabulary here because that mapping has no other home.
 *
 * <p>Stateless apart from the one injected collaborator, holding no mutable field, so the singleton is
 * safe for unsynchronised concurrent use.
 *
 * <p>Provenance: {@code app/cbl/COUSR00C.cbl}, {@code COUSR01C.cbl}, {@code COUSR02C.cbl},
 * {@code COUSR03C.cbl} and their four mapsets, read as read-only reference at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No copybook or program text is transcribed.
 *
 * @since 1.0.0
 */
@Component
public final class UserContractAdapter {

    /** Stand-in for an absent field name or screen-field identifier on a translated finding. */
    private static final String EMPTY = "";

    /** The module's single seam for the wire communication area and the wire browse window. */
    private final ScreenStateAdapter screenStateAdapter;

    /**
     * Creates the adapter over the carrier seam it delegates to.
     *
     * @param screenStateAdapter the converter for the communication area and the browse window; must not
     *     be {@code null}
     * @throws NullPointerException if the collaborator is {@code null}
     */
    public UserContractAdapter(final ScreenStateAdapter screenStateAdapter) {
        this.screenStateAdapter =
                Objects.requireNonNull(screenStateAdapter, "screenStateAdapter must not be null");
    }

    /**
     * Converts one transmitted screen into the command the transaction reads.
     *
     * <p>All twelve components cross positionally. The selection column crosses as the wire record left it
     * - already normalised there to an unmodifiable list, and normalised again by the command's own
     * constructor, so an absent column is an empty one on both sides rather than a null on either. The
     * echoed communication area crosses through the shared navigation seam, so an absent one becomes the
     * empty carried state rather than a null reference, which is what these screens already treat as no
     * carry-over.
     *
     * @param request the transmitted screen; must not be {@code null}
     * @param authentication the identity the filter chain established, which the echoed navigation
     *                       state is reconciled against so the transaction reads the authenticated
     *                       administrator rather than the one the caller typed
     * @return the command the transaction reads, never {@code null}
     * @throws NullPointerException if the request is {@code null}
     */
    public UserCommand toCommand(final UserRequest request, final Authentication authentication) {
        Objects.requireNonNull(request, "request must not be null");
        return new UserCommand(
                request.userId(),
                request.searchUserId(),
                request.firstName(),
                request.lastName(),
                request.password(),
                request.userType(),
                request.rowSelections(),
                request.displayedPageNumber(),
                request.firstUserIdOnPage(),
                request.lastUserIdOnPage(),
                request.rowSnapshotToken(),
                request.keyAction(),
                this.screenStateAdapter.toNavigationState(request.navigationContext(), authentication));
    }

    /**
     * Converts one settled turn into the response the client receives.
     *
     * <p>All nineteen components cross positionally. The rows are translated entry for entry in the order
     * the browse settled them and are neither re-ordered nor re-sorted; the browse window and the
     * communication area cross back through the shared carrier seam; and the per-field findings are
     * translated in the order the cascade reported them.
     *
     * @param outcome the settled turn; must not be {@code null}
     * @param authentication the identity the filter chain established, which the echoed navigation state
     *                       is reconciled against on the way out as well as on the way in
     * @return the published response, never {@code null}
     * @throws NullPointerException if the outcome is {@code null}
     */
    public UserResponse toResponse(final UserOutcome outcome, final Authentication authentication) {
        Objects.requireNonNull(outcome, "outcome must not be null");
        return new UserResponse(
                toResponseRows(outcome.rows()),
                this.screenStateAdapter.toPageMetadata(outcome.pageMetadata()),
                outcome.rowSnapshotToken(),
                outcome.userId(),
                outcome.firstName(),
                outcome.lastName(),
                outcome.userType(),
                outcome.transactionName(),
                outcome.title01(),
                outcome.currentDate(),
                outcome.programName(),
                outcome.title02(),
                outcome.currentTime(),
                outcome.message(),
                toResponseFieldErrors(outcome.fieldErrors()),
                outcome.generalError(),
                outcome.actionSucceeded(),
                outcome.preserveDisplayedPage(),
                outcome.focusScreenFieldId(),
                outcome.nextRoute(),
                this.screenStateAdapter.toNavigationContext(outcome.navigationContext(), authentication));
    }

    /**
     * Translates the settled page onto the published row contract, in the order given.
     *
     * <p>Five components per row, positionally, with no re-ordering and no padding. A slot the browse never
     * populated does not appear here at all: the outcome publishes only the rows it settled, so the page a
     * client receives has exactly as many entries as the screen displayed.
     *
     * @param rows the settled rows, never {@code null} because the outcome normalises them
     * @return the published rows in the same order, never {@code null}
     */
    private static List<UserResponse.UserRow> toResponseRows(final List<UserOutcome.UserRow> rows) {
        final List<UserResponse.UserRow> published = new ArrayList<>(rows.size());
        for (final UserOutcome.UserRow row : rows) {
            published.add(new UserResponse.UserRow(row.selector(), row.userId(), row.firstName(),
                    row.lastName(), row.userType()));
        }
        return List.copyOf(published);
    }

    /**
     * Translates the turn's per-field findings onto the transport contract's own field-error vocabulary.
     *
     * <p>A component-for-component rename across one layer boundary and nothing more: no finding is added,
     * dropped, reordered, reworded or re-classified. An absent field name or screen-field identifier
     * becomes the empty string rather than propagating a null, because the transport entry refuses a null
     * for either and an entry a client cannot locate is worse than one that names nothing. The state
     * mapping is exhaustive with no default arm, so a third state added to either enumeration stops the
     * build here rather than being funnelled into a catch-all.
     *
     * @param fieldErrors the turn's findings, never {@code null} because the outcome normalises them
     * @return the translated findings in the same order, never {@code null}
     */
    private static List<ErrorResponse.FieldError> toResponseFieldErrors(
            final List<ValidationException.FieldError> fieldErrors) {
        final List<ErrorResponse.FieldError> translated = new ArrayList<>(fieldErrors.size());
        for (final ValidationException.FieldError fieldError : fieldErrors) {
            translated.add(new ErrorResponse.FieldError(
                    Objects.requireNonNullElse(fieldError.field(), EMPTY),
                    Objects.requireNonNullElse(fieldError.bmsFieldId(), EMPTY),
                    switch (fieldError.state()) {
                        case MISSING -> ErrorResponse.FieldState.MISSING;
                        case INVALID -> ErrorResponse.FieldState.INVALID;
                    },
                    fieldError.message()));
        }
        return List.copyOf(translated);
    }
}
