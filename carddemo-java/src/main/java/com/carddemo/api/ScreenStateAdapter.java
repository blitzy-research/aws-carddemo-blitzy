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
import com.carddemo.api.dto.FieldErrorDecorator;
import com.carddemo.api.dto.NavigationContext;
import com.carddemo.api.dto.PageMetadata;
import com.carddemo.api.dto.ScreenWorkArea;
import com.carddemo.service.BrowseWindow;
import com.carddemo.service.FieldErrorMarks;
import com.carddemo.service.ScreenInputState;
import com.carddemo.service.ScreenNavigationState;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The single boundary at which the four screen-contract wire carriers and their service-owned counterparts
 * are converted into one another.
 *
 * <p><strong>Why the conversion exists at all.</strong> The module's layering runs one way: the API layer
 * may depend on the service layer and nothing may depend upward. A service that took a wire record as a
 * parameter, or returned one, inverted that direction, and until this class existed ten screen services
 * did exactly that. Each of the four pairs below is therefore two types - the wire form in
 * {@code api.dto} with its width bounds and its serialization contract, and the service-owned form in
 * {@code com.carddemo.service} with neither - and this class is the only place either direction happens.
 * Component names are identical on both sides of every pair, which is deliberate: a conversion that has to
 * rename a field is a conversion that can quietly map the wrong one.
 *
 * <p><strong>Every conversion is a positional copy and nothing more.</strong> No value is trimmed, padded,
 * defaulted, re-cased, reordered or reconciled. Three of the four carriers hold fixed-width,
 * blank-significant values - the sixteen communication-area fields, the two screen message slots and the
 * two boundary browse cursors - and a fourth holds an operator-facing page indicator whose leading zeros
 * are part of what the screen displayed, so a helpful normalisation here would be a parity defect several
 * layers away from where it was introduced.
 *
 * <p><strong>What this class deliberately does not do.</strong> It does not reconcile the echoed identity
 * against the authenticated principal. The ten screen services carry all sixteen communication-area fields
 * across a turn and hand back what they were given, exactly as their legacy programs did, and overwriting
 * the identity members here would change what those screens echo. Where reconciliation is the required
 * behaviour it is already performed, by {@code ConversationStateAdapter} on the sign-on and menu families,
 * whose services take the narrow five-field carrier instead; {@link ScreenNavigationState} exposes the same
 * reconciliation for a caller that needs it. This class also resolves no route, applies no validation and
 * reads no repository: it converts, and that is all.
 *
 * <p><strong>Absence is preserved rather than filled in, except where a null-free carrier exists.</strong>
 * An absent wire record converts to the empty service-owned carrier rather than to {@code null}, because a
 * service should not have to null-check state it is handed and the empty carrier is exactly what the legacy
 * treats as no carry-over. In the outbound direction an absent carrier converts back to {@code null}, so a
 * response omits a member the turn never produced rather than carrying an all-blank one.
 *
 * <p>Stateless and immutable, holding no field of any kind, so the singleton is safe for unsynchronised
 * concurrent use.
 *
 * <p>Provenance: {@code app/cpy/COCOM01Y.cpy}, {@code app/cpy/CVCRD01Y.cpy},
 * {@code app/cpy/CSSETATY.cpy} and the three paginated programs {@code app/cbl/COCRDLIC.cbl},
 * {@code app/cbl/COTRN00C.cbl} and {@code app/cbl/COUSR00C.cbl}, read as read-only reference at checkout
 * SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No copybook or program text is transcribed.
 *
 * @since 1.0.0
 */
@Component
public final class ScreenStateAdapter {

    /**
     * Converts the client-echoed navigation record into the state the service layer owns.
     *
     * @param context the record the client echoed, which may be {@code null}
     * @return the sixteen-field service-owned state, never {@code null}
     */
    public ScreenNavigationState toNavigationState(final NavigationContext context) {
        if (context == null) {
            return ScreenNavigationState.empty();
        }
        return new ScreenNavigationState(
                context.fromTransactionId(),
                context.fromProgram(),
                context.toTransactionId(),
                context.toProgram(),
                context.userId(),
                context.userType(),
                toProgramContext(context.programContext()),
                context.customerId(),
                context.customerFirstName(),
                context.customerMiddleName(),
                context.customerLastName(),
                context.accountId(),
                context.accountStatus(),
                context.cardNumber(),
                context.lastMap(),
                context.lastMapset());
    }

    /**
     * Converts the service-owned navigation state back into the record a response carries.
     *
     * @param state the state the service produced, which may be {@code null}
     * @return the wire record, or {@code null} when the turn produced no state
     */
    public NavigationContext toNavigationContext(final ScreenNavigationState state) {
        if (state == null) {
            return null;
        }
        return new NavigationContext(
                state.fromTransactionId(),
                state.fromProgram(),
                state.toTransactionId(),
                state.toProgram(),
                state.userId(),
                state.userType(),
                toWireProgramContext(state.programContext()),
                state.customerId(),
                state.customerFirstName(),
                state.customerMiddleName(),
                state.customerLastName(),
                state.accountId(),
                state.accountStatus(),
                state.cardNumber(),
                state.lastMap(),
                state.lastMapset());
    }

    /**
     * Converts the submitted screen work area into the input state the service layer owns.
     *
     * @param workArea the submitted work area, which may be {@code null}
     * @return the nine-component service-owned input state, never {@code null}
     */
    public ScreenInputState toInputState(final ScreenWorkArea workArea) {
        if (workArea == null) {
            return ScreenInputState.empty();
        }
        return new ScreenInputState(
                workArea.keyAction(),
                workArea.nextProgram(),
                workArea.nextMapset(),
                workArea.nextMap(),
                workArea.errorMessage(),
                workArea.returnMessage(),
                workArea.accountId(),
                workArea.cardNumber(),
                workArea.customerId());
    }

    /**
     * Converts the service-owned input state back into the work area a response echoes.
     *
     * @param state the state the service produced, which may be {@code null}
     * @return the wire work area, or {@code null} when the turn produced none
     */
    public ScreenWorkArea toScreenWorkArea(final ScreenInputState state) {
        if (state == null) {
            return null;
        }
        return new ScreenWorkArea(
                state.keyAction(),
                state.nextProgram(),
                state.nextMapset(),
                state.nextMap(),
                state.errorMessage(),
                state.returnMessage(),
                state.accountId(),
                state.cardNumber(),
                state.customerId());
    }

    /**
     * Converts the inbound paging choice into the cursor request the service layer owns.
     *
     * @param request the paging choice the submission carried, which may be {@code null}
     * @return the service-owned cursor request, or {@code null} when the submission carried none
     */
    public BrowseWindow.CursorRequest toCursorRequest(final PageMetadata.PageCursorRequest request) {
        if (request == null) {
            return null;
        }
        return new BrowseWindow.CursorRequest(
                request.previousCursorKey(),
                request.nextCursorKey(),
                toPagingDirection(request.direction()));
    }

    /**
     * Converts the assembled browse window into the paging metadata a response carries.
     *
     * @param window the window the service assembled, which may be {@code null}
     * @return the wire paging metadata, or {@code null} when the turn assembled no page
     */
    public PageMetadata toPageMetadata(final BrowseWindow window) {
        if (window == null) {
            return null;
        }
        return new PageMetadata(
                window.pageSize(),
                window.previousCursorKey(),
                window.nextCursorKey(),
                toWirePagingDirection(window.direction()),
                window.hasMorePages(),
                window.hasPreviousPages(),
                window.displayedPageNumber());
    }

    /**
     * Converts the service-owned field marks into the wire decoration a response carries.
     *
     * <p>Marking sequence is preserved entry for entry, because it is the order an operator saw the fields
     * marked and the legacy expansions ran in.
     *
     * @param marks the accumulation the validation cascade grew, which may be {@code null}
     * @return the wire decoration, never {@code null}; empty when nothing was marked
     */
    public FieldErrorDecorator toFieldErrorDecorator(final FieldErrorMarks marks) {
        if (marks == null) {
            return FieldErrorDecorator.none();
        }
        final List<FieldErrorDecorator.MarkedField> entries =
                new ArrayList<>(marks.markedFields().size());
        for (final FieldErrorMarks.MarkedField marked : marks.markedFields()) {
            entries.add(new FieldErrorDecorator.MarkedField(marked.field(), marked.bmsFieldId(),
                    toWireFlagState(marked.flagState())));
        }
        return new FieldErrorDecorator(entries);
    }

    /**
     * Converts the service-owned field marks straight into the per-field entries of the error contract.
     *
     * <p>Offered beside {@link #toFieldErrorDecorator(FieldErrorMarks)} because a response that carries
     * field errors directly, rather than a decoration, would otherwise have to build the intermediate
     * accumulation only to unwrap it.
     *
     * @param marks the accumulation the validation cascade grew, which may be {@code null}
     * @return one entry per mark in marking sequence, never {@code null}; empty when nothing was marked
     */
    public List<ErrorResponse.FieldError> toFieldErrors(final FieldErrorMarks marks) {
        if (marks == null) {
            return List.of();
        }
        final List<ErrorResponse.FieldError> entries =
                new ArrayList<>(marks.markedFields().size());
        for (final FieldErrorMarks.MarkedField marked : marks.markedFields()) {
            entries.add(new ErrorResponse.FieldError(marked.field(), marked.bmsFieldId(),
                    toErrorFieldState(marked.flagState())));
        }
        return List.copyOf(entries);
    }

    /**
     * Maps the wire program-context flag onto the service-owned one.
     *
     * <p>Exhaustive over the two constants with no default arm, so a third state added to either
     * enumeration stops the build here rather than being funnelled into a catch-all. An absent flag stays
     * absent: the service-owned carrier treats anything other than re-enter as a first entry, which is what
     * the legacy single-digit field does, and inventing {@code ENTER} here would make an absent flag
     * indistinguishable from a present one on the way back out.
     *
     * @param context the wire flag, which may be {@code null}
     * @return the service-owned flag, or {@code null} when the wire flag was absent
     */
    private static ScreenNavigationState.ProgramContext toProgramContext(
            final NavigationContext.ProgramContext context) {
        if (context == null) {
            return null;
        }
        return switch (context) {
            case ENTER -> ScreenNavigationState.ProgramContext.ENTER;
            case REENTER -> ScreenNavigationState.ProgramContext.REENTER;
        };
    }

    /**
     * Maps the service-owned program-context flag back onto the wire one.
     *
     * @param context the service-owned flag, which may be {@code null}
     * @return the wire flag, or {@code null} when the service-owned flag was absent
     */
    private static NavigationContext.ProgramContext toWireProgramContext(
            final ScreenNavigationState.ProgramContext context) {
        if (context == null) {
            return null;
        }
        return switch (context) {
            case ENTER -> NavigationContext.ProgramContext.ENTER;
            case REENTER -> NavigationContext.ProgramContext.REENTER;
        };
    }

    /**
     * Maps the wire paging direction onto the service-owned one.
     *
     * @param direction the wire direction, which may be {@code null}
     * @return the service-owned direction, or {@code null} when the submission asked for neither
     */
    private static BrowseWindow.PagingDirection toPagingDirection(
            final PageMetadata.PagingDirection direction) {
        if (direction == null) {
            return null;
        }
        return switch (direction) {
            case FORWARD -> BrowseWindow.PagingDirection.FORWARD;
            case BACKWARD -> BrowseWindow.PagingDirection.BACKWARD;
        };
    }

    /**
     * Maps the service-owned paging direction back onto the wire one.
     *
     * @param direction the service-owned direction, never {@code null} on an assembled window
     * @return the wire direction
     */
    private static PageMetadata.PagingDirection toWirePagingDirection(
            final BrowseWindow.PagingDirection direction) {
        return switch (direction) {
            case FORWARD -> PageMetadata.PagingDirection.FORWARD;
            case BACKWARD -> PageMetadata.PagingDirection.BACKWARD;
        };
    }

    /**
     * Maps the service-owned flag state onto the wire decoration's flag state.
     *
     * @param flagState the service-owned state, never {@code null}
     * @return the wire state
     */
    private static FieldErrorDecorator.FlagState toWireFlagState(
            final FieldErrorMarks.FlagState flagState) {
        return switch (flagState) {
            case BLANK -> FieldErrorDecorator.FlagState.BLANK;
            case NOT_OK -> FieldErrorDecorator.FlagState.NOT_OK;
        };
    }

    /**
     * Maps the service-owned flag state onto the two-state error contract a client reads.
     *
     * <p>A field left blank becomes {@code MISSING} and a field filled in wrongly becomes {@code INVALID},
     * which is the distinction the legacy macro drew by writing an extra marker for the blank case.
     *
     * @param flagState the service-owned state, never {@code null}
     * @return the contract state
     */
    private static ErrorResponse.FieldState toErrorFieldState(
            final FieldErrorMarks.FlagState flagState) {
        return switch (flagState) {
            case BLANK -> ErrorResponse.FieldState.MISSING;
            case NOT_OK -> ErrorResponse.FieldState.INVALID;
        };
    }
}
