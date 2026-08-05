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

import com.carddemo.api.dto.AccountUpdateRequest;
import com.carddemo.api.dto.AccountUpdateResponse;
import com.carddemo.api.dto.ErrorResponse;
import com.carddemo.exception.ValidationException;
import com.carddemo.service.AccountUpdateCommand;
import com.carddemo.service.AccountUpdateOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * The single boundary at which the account-update wire contract and the service-owned command and outcome
 * are converted into one another.
 *
 * <p><strong>Why the conversion exists.</strong> The module's layering runs one way: the API layer may
 * depend on the service layer and nothing may depend upward. A service that took the wire request as a
 * parameter, or returned the wire response, inverted that direction. {@code api.dto.AccountUpdateRequest}
 * and {@code api.dto.AccountUpdateResponse} therefore stay where they are, with their width bounds, their
 * operation semantics and their serialization contract, and {@link AccountUpdateCommand} and
 * {@link AccountUpdateOutcome} carry the same components with none of that. This class is the only place
 * either direction happens.
 *
 * <p><strong>Every conversion is a positional copy and nothing more.</strong> Forty-six components cross
 * inbound and fifty-seven cross outbound, each under the same name on both sides, and not one value is
 * trimmed, padded, defaulted, re-cased, reordered, parsed or re-scaled. That matters more here than
 * anywhere else in the module: the account-update screen carries forty-three operator-typed values whose
 * blanks are significant, whose ordered validation cascade reports the first failure it finds, and whose
 * five monetary components are already at the scale of the record fields they came from.
 *
 * <p><strong>What this class deliberately does not do.</strong> It applies no validation, resolves no
 * route, reads no repository, composes no message and re-scales no amount. It does not reconcile the
 * echoed identity either: the update screen carries all sixteen communication-area fields across a turn
 * and hands back what it was given, exactly as {@code app/cbl/COACTUPC.cbl} did, so overwriting the
 * identity members here would change what the screen echoes.
 *
 * <p>The two carriers it does not convert itself are delegated: the echoed communication area goes through
 * {@link ScreenStateAdapter}, which is the module's single seam for that pair, and the per-field findings
 * are translated to the transport contract's own vocabulary here because that mapping has no other home.
 *
 * <p>Stateless apart from the one injected collaborator, holding no mutable field, so the singleton is
 * safe for unsynchronised concurrent use.
 *
 * <p>Provenance: {@code app/cbl/COACTUPC.cbl} and {@code app/cpy-bms/COACTUP.CPY}, read as read-only
 * reference at checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No copybook or program text is transcribed.
 *
 * @since 1.0.0
 */
@Component
public final class AccountUpdateContractAdapter {

    /** Stand-in for an absent field name or screen-field identifier on a translated finding. */
    private static final String EMPTY = "";

    /** The module's single seam between the wire communication area and the service-owned state. */
    private final ScreenStateAdapter screenStateAdapter;

    /**
     * Creates the adapter over the navigation-state seam it delegates to.
     *
     * @param screenStateAdapter the converter for the echoed communication area; must not be {@code null}
     * @throws NullPointerException if the collaborator is {@code null}
     */
    public AccountUpdateContractAdapter(final ScreenStateAdapter screenStateAdapter) {
        this.screenStateAdapter =
                Objects.requireNonNull(screenStateAdapter, "screenStateAdapter must not be null");
    }

    /**
     * Converts one transmitted screen into the command the transaction reads.
     *
     * <p>All forty-six components cross positionally. The echoed communication area crosses through the
     * shared navigation seam, so an absent one becomes the empty carried state rather than a null
     * reference - which is what this screen's own reset condition already treats as no carry-over.
     *
     * @param request the transmitted screen; must not be {@code null}
     * @return the command the transaction reads, never {@code null}
     * @throws NullPointerException if the request is {@code null}
     */
    public AccountUpdateCommand toCommand(final AccountUpdateRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return new AccountUpdateCommand(
                request.accountId(),
                request.accountStatus(),
                request.openYear(),
                request.openMonth(),
                request.openDay(),
                request.creditLimit(),
                request.expiryYear(),
                request.expiryMonth(),
                request.expiryDay(),
                request.cashCreditLimit(),
                request.reissueYear(),
                request.reissueMonth(),
                request.reissueDay(),
                request.currentBalance(),
                request.currentCycleCredit(),
                request.accountGroupId(),
                request.currentCycleDebit(),
                request.customerId(),
                request.ssnPart1(),
                request.ssnPart2(),
                request.ssnPart3(),
                request.dateOfBirthYear(),
                request.dateOfBirthMonth(),
                request.dateOfBirthDay(),
                request.ficoScore(),
                request.firstName(),
                request.middleName(),
                request.lastName(),
                request.addressLine1(),
                request.stateCode(),
                request.addressLine2(),
                request.zipCode(),
                request.city(),
                request.countryCode(),
                request.phone1AreaCode(),
                request.phone1Prefix(),
                request.phone1LineNumber(),
                request.governmentIssuedId(),
                request.phone2AreaCode(),
                request.phone2Prefix(),
                request.phone2LineNumber(),
                request.eftAccountId(),
                request.primaryCardHolderIndicator(),
                request.keyAction(),
                this.screenStateAdapter.toNavigationState(request.navigationContext()),
                request.concurrencyToken());
    }

    /**
     * Converts one settled turn into the response the client receives.
     *
     * <p>All fifty-seven components cross positionally. The communication area crosses back through the
     * shared navigation seam, and the per-field findings are translated entry for entry in the order the
     * cascade reported them, because that order is the order an operator saw the fields marked.
     *
     * @param outcome the settled turn; must not be {@code null}
     * @return the published response, never {@code null}
     * @throws NullPointerException if the outcome is {@code null}
     */
    public AccountUpdateResponse toResponse(final AccountUpdateOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome must not be null");
        return new AccountUpdateResponse(
                outcome.transactionName(),
                outcome.title01(),
                outcome.currentDate(),
                outcome.programName(),
                outcome.title02(),
                outcome.currentTime(),
                outcome.accountId(),
                outcome.accountStatus(),
                outcome.openYear(),
                outcome.openMonth(),
                outcome.openDay(),
                outcome.creditLimit(),
                outcome.expiryYear(),
                outcome.expiryMonth(),
                outcome.expiryDay(),
                outcome.cashCreditLimit(),
                outcome.reissueYear(),
                outcome.reissueMonth(),
                outcome.reissueDay(),
                outcome.currentBalance(),
                outcome.currentCycleCredit(),
                outcome.accountGroupId(),
                outcome.currentCycleDebit(),
                outcome.customerId(),
                outcome.ssnPart1(),
                outcome.ssnPart2(),
                outcome.ssnPart3(),
                outcome.dateOfBirthYear(),
                outcome.dateOfBirthMonth(),
                outcome.dateOfBirthDay(),
                outcome.ficoScore(),
                outcome.firstName(),
                outcome.middleName(),
                outcome.lastName(),
                outcome.addressLine1(),
                outcome.stateCode(),
                outcome.addressLine2(),
                outcome.zipCode(),
                outcome.city(),
                outcome.countryCode(),
                outcome.phone1AreaCode(),
                outcome.phone1Prefix(),
                outcome.phone1LineNumber(),
                outcome.governmentIssuedId(),
                outcome.phone2AreaCode(),
                outcome.phone2Prefix(),
                outcome.phone2LineNumber(),
                outcome.eftAccountId(),
                outcome.primaryCardHolderIndicator(),
                outcome.infoMessage(),
                outcome.errorMessage(),
                outcome.error(),
                outcome.focusScreenFieldId(),
                outcome.nextRoute(),
                this.screenStateAdapter.toNavigationContext(outcome.navigationContext()),
                toResponseFieldErrors(outcome.fieldErrors()),
                outcome.concurrencyToken());
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
