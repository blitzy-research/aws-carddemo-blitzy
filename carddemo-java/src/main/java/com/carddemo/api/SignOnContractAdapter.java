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
import com.carddemo.api.dto.SignOnResponse;
import com.carddemo.service.AuthenticationService;
import com.carddemo.service.MessageCatalogService;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Turns a sign-on turn into the response the sign-on screen contract publishes.
 *
 * <p><strong>Why this sits at the boundary rather than in the service.</strong> The five sign-on message
 * literals and the two shared ones are wire text: they are what an operator reads and what downstream
 * tooling matches on, so they belong to the contract and are declared on the response record and the
 * message catalog. The service answers with a {@link AuthenticationService.Decision} instead, and this
 * class is the single place a decision becomes text. Keeping the mapping here means the service can be
 * exercised on its decisions alone, and it means the mapping is exhaustive: the switch below has no
 * default arm, so adding a decision without giving it a literal does not compile.
 *
 * <p><strong>What is not carried, and why.</strong> No session token appears anywhere in the response.
 * The screen contract declares fifteen components and none of them is a credential, so a token placed in
 * the body would be an addition to a frozen contract; it travels as a response header instead, which is
 * the controller's business rather than this class's. The operator's own entry is likewise not echoed:
 * the legacy program leaves it in the map for redisplay, but the client still holds what it sent, and the
 * secret in particular must not make a return trip.
 *
 * <p><strong>The navigation context is built here, not echoed.</strong> On an admitted turn the context
 * carries the identifier and role the <em>service</em> resolved from the credential master, together with
 * the originating transaction and program. Nothing in it comes from the request. That is the whole point:
 * this is the one turn that establishes identity, so identity here is derived and never accepted.
 *
 * <p>Provenance: {@code app/cbl/COSGN00C.cbl}, whose first-entry screen at lines 80 to 83 and screen
 * writes at lines 88, 93, 121, 126, 244, 250 and 255 are the outcomes mapped below, read as read-only
 * reference at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No COBOL statement is transcribed.
 *
 * @since 1.0.0
 */
@Component
public final class SignOnContractAdapter {

    /** Supplies the two literals the sign-on screen shares with every other screen. */
    private final MessageCatalogService messageCatalogService;

    /**
     * Creates the adapter over the shared message catalog.
     *
     * @param messageCatalogService the shared catalog
     */
    public SignOnContractAdapter(final MessageCatalogService messageCatalogService) {
        this.messageCatalogService = Objects.requireNonNull(messageCatalogService,
                "messageCatalogService must not be null");
    }

    /**
     * Projects a sign-on turn onto the published response.
     *
     * @param screen the turn the service produced
     * @return the response the client receives
     */
    public SignOnResponse toResponse(final AuthenticationService.SignOnScreen screen) {
        Objects.requireNonNull(screen, "screen must not be null");

        return new SignOnResponse(
                messageFor(screen.decision()),
                screen.errorFlag(),
                screen.focusScreenFieldId(),
                screen.route() == null ? null : screen.route().getRouteValue(),
                navigationContextFor(screen),
                screen.userId(),
                screen.userTypeCode(),
                SignOnResponse.TRANSACTION_NAME,
                SignOnResponse.PROGRAM_NAME,
                screen.title01(),
                screen.title02(),
                screen.currentDate(),
                screen.currentTime(),
                null,
                null);
    }

    /**
     * Resolves the frozen literal each decision presents.
     *
     * <p>Exhaustive with no default arm, so a decision added to the service without a literal here is a
     * compilation failure rather than a silently empty message. An admitted turn carries no message at
     * all, matching the program: control transfers to the menu and the operator sees that screen, not a
     * confirmation of the one they left.
     *
     * @param decision the decision the service reached
     * @return the literal to present, or {@code null} when the turn presents none
     */
    private String messageFor(final AuthenticationService.Decision decision) {
        return switch (decision) {
            case INITIAL_ENTRY -> null;
            case ADMITTED -> null;
            case USER_ID_MISSING -> SignOnResponse.MSG_PROMPT_USERID;
            case PASSWORD_MISSING -> SignOnResponse.MSG_PROMPT_PASSWD;
            case USER_NOT_FOUND -> SignOnResponse.MSG_USER_NOT_FOUND;
            case WRONG_PASSWORD -> SignOnResponse.MSG_WRONG_PASSWD;
            case UNABLE_TO_VERIFY -> SignOnResponse.MSG_UNABLE_TO_VERIFY;
            case SIGNED_OFF -> messageCatalogService.thankYouMessage();
            case KEY_NOT_MAPPED -> messageCatalogService.invalidKeyMessage();
        };
    }

    /**
     * Builds the navigation state an admitted turn establishes.
     *
     * <p>Absent on every other turn, because a turn that did not admit the operator establishes no state
     * and a context carrying an unauthenticated identifier is exactly the confusion this boundary exists
     * to prevent. The program context is set to the entering state, matching the zeros the program moves
     * into that field at line 228 against the condition name valued zero: the destination screen is being
     * entered rather than re-entered, and that flag gates field-level error decoration there, so it is
     * written explicitly rather than left to inference.
     *
     * @param screen the turn
     * @return the established state, or {@code null} when the turn established none
     */
    private static NavigationContext navigationContextFor(
            final AuthenticationService.SignOnScreen screen) {
        if (!screen.decision().isAdmitted()) {
            return null;
        }
        return new NavigationContext(
                SignOnResponse.TRANSACTION_NAME,
                SignOnResponse.PROGRAM_NAME,
                screen.route().getLegacyTransactionId(),
                screen.route().getLegacyProgramName(),
                screen.userId(),
                screen.userTypeCode(),
                NavigationContext.ProgramContext.ENTER,
                null, null, null, null, null, null, null, null, null);
    }
}
