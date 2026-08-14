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
import com.carddemo.domain.enums.UserType;
import com.carddemo.service.BrowseWindow;
import com.carddemo.service.FieldErrorMarks;
import com.carddemo.service.NavigationService;
import com.carddemo.service.ScreenInputState;
import com.carddemo.service.ScreenNavigationState;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
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
 * <p><strong>Identity is reconciled against the authenticated principal, in both directions.</strong> Both
 * conversion methods require the established identity as an argument, so a protected route cannot forget to
 * supply it - the compiler refuses the call. Inbound, the two identity members are replaced by the
 * principal's own, which means a service physically cannot read a client-chosen identifier or role: the
 * values are gone before it is called. Outbound, they are restated from the same principal, so a
 * client-supplied identity cannot survive a turn and come back looking as though the server had asserted
 * it. The other fourteen members cross byte for byte in both directions, because correcting identity is not
 * licence to rewrite echoed navigation state, and the ten screen services still carry and hand back the
 * whole communication area exactly as their legacy programs did.
 *
 * <p><strong>The two routing nominations are screened, for a reason the legacy did not have.</strong> The
 * from- and to-program members carry an eight-character program name, and the navigation authority's
 * unresolvable arm reproduces the abend a legacy transfer to an unknown program would have raised. That arm
 * was defensive and unreachable on the mainframe, where the region held the communication area; over HTTP
 * the caller holds it. A nomination that resolves to no destination is therefore carried as empty - the
 * legacy's own "nominates nothing" state, on which the calling screen's default applies - so every name the
 * estate declares still crosses unchanged while an invented one can no longer reach a terminal failure. The
 * from-side transaction identifier is screened on the same terms, because the sign-off rule resolves it
 * through the same kind of lookup.
 *
 * <p>Beyond those two things this class still resolves no route, applies no validation, takes no
 * authorization decision and reads no repository: it converts, and that is all. Where the narrower
 * five-field carrier is what a service takes, {@code ConversationStateAdapter} performs the same
 * reconciliation for the sign-on and menu families.
 *
 * <p><strong>Absence is preserved rather than filled in, except where a null-free carrier exists.</strong>
 * An absent wire record converts to the empty service-owned carrier rather than to {@code null}, because a
 * service should not have to null-check state it is handed and the empty carrier is exactly what the legacy
 * treats as no carry-over. In the outbound direction an absent carrier converts back to {@code null}, so a
 * response omits a member the turn never produced rather than carrying an all-blank one.
 *
 * <p>Immutable and free of per-turn state, holding only the navigation authority it screens nominations
 * against, so the singleton is safe for unsynchronised concurrent use.
 *
 * @since 1.0.0
 */
@Component
public final class ScreenStateAdapter {

    /**
     * Prefix the filter chain grants a user type's authority under.
     *
     * <p>Declared here so the grant and this inverse reading name one spelling. The chain grants the
     * framework's conventional role prefix followed by the user type's own constant name, and reading it
     * back is the only way this boundary can learn a type from an identity the chain established.
     */
    private static final String ROLE_AUTHORITY_PREFIX = "ROLE_";

    /** Diagnostic channel. It records that a nomination was screened, never the value screened out. */
    private static final Logger LOG = LoggerFactory.getLogger(ScreenStateAdapter.class);

    /** Resolves an echoed nomination to a destination, which is how a screened nomination is recognised. */
    private final NavigationService navigationService;

    /**
     * Creates the adapter over the navigation authority it screens nominations against.
     *
     * @param navigationService the destination vocabulary; must not be {@code null}
     */
    public ScreenStateAdapter(final NavigationService navigationService) {
        this.navigationService =
                Objects.requireNonNull(navigationService, "navigationService must not be null");
    }

    /**
     * Converts the client-echoed navigation record into the state the service layer owns, reconciled
     * against the authenticated principal and with its routing nominations screened.
     *
     * <p><strong>Identity is replaced, never carried.</strong> The two identity members are overwritten
     * with the authenticated principal's own, so a service reads who is acting from the credential the
     * chain verified rather than from a field the caller typed. The remaining fourteen members cross byte
     * for byte, because correcting identity is not licence to rewrite echoed navigation state.
     *
     * <p><strong>Routing nominations are screened.</strong> Two of the four routing members carry an
     * eight-character program name that the navigation authority resolves to a destination, and its
     * unresolvable arm reproduces the abend a legacy transfer to an unknown program would have raised.
     * On the mainframe that arm was defensive and unreachable: the communication area was held by the
     * region, so only the system itself could put a name in it. Over HTTP the caller holds it, so an
     * invented name would reach that arm and surface as a terminal failure. A nomination that resolves to
     * no destination is therefore carried as blank - which is exactly "nominates nothing", the legacy's own
     * empty-field case, so the calling screen's default applies. Every name the estate actually declares
     * still crosses unchanged, so no reachable legacy behaviour changes; what changes is that the
     * unreachable arm stays unreachable.
     *
     * @param context the record the client echoed, which may be {@code null}
     * @param authentication the identity the filter chain established, which may be {@code null} only on a
     *                       route the chain does not authenticate
     * @return the sixteen-field service-owned state, never {@code null}
     */
    public ScreenNavigationState toNavigationState(final NavigationContext context,
                                                   final Authentication authentication) {
        if (context == null) {
            return ScreenNavigationState.empty()
                    .reconciledWith(authenticatedUserId(authentication),
                            authenticatedUserType(authentication));
        }
        return new ScreenNavigationState(
                screenedTransactionId(context.fromTransactionId()),
                screenedProgramName(context.fromProgram()),
                screenedTransactionId(context.toTransactionId()),
                screenedProgramName(context.toProgram()),
                authenticatedUserId(authentication),
                codeOf(authenticatedUserType(authentication)),
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
     * Converts the service-owned navigation state back into the record a response carries, reconciled
     * against the authenticated principal.
     *
     * <p>Reconciling on the way out as well as on the way in is what stops a client-supplied identity
     * surviving a turn and coming back looking as though the server had asserted it. The service never saw
     * a client identity - the inbound conversion replaced it - so this is a restatement rather than a
     * correction, and it holds even if a service were later to write those members itself.
     *
     * @param state the state the service produced, which may be {@code null}
     * @param authentication the identity the filter chain established, which may be {@code null}
     * @return the wire record, or {@code null} when the turn produced no state
     */
    public NavigationContext toNavigationContext(final ScreenNavigationState state,
                                                 final Authentication authentication) {
        if (state == null) {
            return null;
        }
        return new NavigationContext(
                state.fromTransactionId(),
                state.fromProgram(),
                state.toTransactionId(),
                state.toProgram(),
                authenticatedUserId(authentication),
                codeOf(authenticatedUserType(authentication)),
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
     * Reads the identifier of the authenticated principal.
     *
     * <p>This is a read of an identity the chain has already established, not a check of one: nothing here
     * admits or refuses anybody. An absent principal yields {@code null}, which the reconciliation treats
     * as "no identity to assert" and which can only occur on a route the chain does not authenticate.
     *
     * @param authentication the established identity, which may be {@code null}
     * @return the principal's name, or {@code null} when none is established
     */
    public static String authenticatedUserId(final Authentication authentication) {
        return (authentication == null) ? null : authentication.getName();
    }

    /**
     * Reads the user type of the authenticated principal out of the authority the chain granted it.
     *
     * <p>Resolution is the inverse of the single mapping the chain applies when it grants the authority:
     * the framework's conventional role prefix followed by the name of the user type. An identity carrying
     * neither declared authority yields {@code null} rather than a type this method had to guess.
     *
     * @param authentication the established identity, which may be {@code null}
     * @return the user type the granted authority names, or {@code null} when none does
     */
    public static UserType authenticatedUserType(final Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        final Collection<? extends GrantedAuthority> granted = authentication.getAuthorities();
        if (granted == null) {
            return null;
        }
        for (final GrantedAuthority authority : granted) {
            for (final UserType candidate : UserType.values()) {
                if ((ROLE_AUTHORITY_PREFIX + candidate.name()).equals(authority.getAuthority())) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /**
     * Renders a user type as the raw one-character code the communication area carried.
     *
     * @param userType the type, which may be {@code null}
     * @return its declared code, or {@code null} when no type is established
     */
    private static String codeOf(final UserType userType) {
        return (userType == null) ? null : userType.getCode();
    }

    /**
     * Screens an echoed program-name nomination, keeping only a name that resolves to a destination.
     *
     * @param nominatedProgram the eight-character program name the client echoed, possibly {@code null}
     * @return the same value when it resolves or is already empty, and {@code null} otherwise
     */
    private String screenedProgramName(final String nominatedProgram) {
        if (isEffectivelyEmpty(nominatedProgram)
                || this.navigationService.routeForLegacyProgram(nominatedProgram).isPresent()) {
            return nominatedProgram;
        }
        LOG.debug("Echoed program nomination names no destination and is carried as empty: length={}",
                nominatedProgram.length());
        return null;
    }

    /**
     * Screens an echoed transaction-identifier nomination on the same terms as a program name.
     *
     * <p>Screened for the same reason and not for a different one: the sign-off rule reads the from-side
     * transaction identifier and resolves it through the same fixed-width lookup, so an invented value
     * there reaches the same unresolvable arm.
     *
     * @param nominatedTransactionId the four-character identifier the client echoed, possibly {@code null}
     * @return the same value when it resolves or is already empty, and {@code null} otherwise
     */
    private String screenedTransactionId(final String nominatedTransactionId) {
        if (isEffectivelyEmpty(nominatedTransactionId)
                || this.navigationService.routeForLegacyTransactionId(nominatedTransactionId)
                        .isPresent()) {
            return nominatedTransactionId;
        }
        LOG.debug("Echoed transaction nomination names no destination and is carried as empty: length={}",
                nominatedTransactionId.length());
        return null;
    }

    /**
     * Reports whether a fixed-width nomination field nominates nothing.
     *
     * <p>The legacy test is {@code = SPACES OR LOW-VALUES}, so an absent value, an empty one, one made only
     * of spaces and one made only of low values are the same state. Screening treats that state as already
     * empty and leaves it exactly as received, because a blank field is significant: it is what makes the
     * calling screen's default apply.
     *
     * @param value the transmitted nomination, possibly {@code null}
     * @return {@code true} when the field nominates nothing
     */
    private static boolean isEffectivelyEmpty(final String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            if (character != ' ' && character != '\0') {
                return false;
            }
        }
        return true;
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
