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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
 * <p><strong>The two region identifiers are configured, because the screen always carried them.</strong>
 * The program assigns them on every send - {@code EXEC CICS ASSIGN APPLID} at line 199 and
 * {@code ASSIGN SYSID} at line 203, each straight into the map's own output item - so an operator saw
 * which application and which system had served the screen on every one of the six screens this
 * transaction can present. They are not diagnostics and not optional: they are two of the fifteen
 * components of a frozen contract, and publishing them permanently absent left a client unable to tell
 * one deployment's screen from another's. The values cannot be assigned here, because there is no region
 * to ask; they are therefore configuration, read once at construction from
 * {@link #APPLICATION_ID_PROPERTY} and {@link #SYSTEM_ID_PROPERTY}, and a deployment that names neither
 * gets the shipped pair. Each is bounded to the eight characters the map item declares, which is what the
 * legacy move into {@code PIC X(8)} did with a longer value, and an explicitly emptied setting publishes
 * absence rather than an empty string.
 *
 * <p>The two region identifiers are configuration, and the provenance of each default is recorded as
 * decision {@code DL-330} in {@code docs/decision-log.md}.
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

    /** Setting that names the application this deployment serves the screen as. */
    public static final String APPLICATION_ID_PROPERTY = "carddemo.region.application-id";

    /** Setting that names the system this deployment serves the screen from. */
    public static final String SYSTEM_ID_PROPERTY = "carddemo.region.system-id";

    /**
     * The application identifier a deployment that names none publishes.
     *
     * <p>The estate's own application name, which its CICS resource definitions carry as the group every
     * transaction, program, map and file belongs to in {@code app/csd/CARDDEMO.CSD}, and which every
     * dataset name carries as its middle qualifier. Exactly the eight characters the map item declares.
     */
    public static final String DEFAULT_APPLICATION_ID = "CARDDEMO";

    /**
     * The system identifier a deployment that names none publishes.
     *
     * <p>The estate names no region anywhere - there is no system initialisation table, no operator
     * command and no job that states one - so there is no legacy literal to carry across and this is
     * openly this module's own default rather than a recovered value. It names the platform the estate's
     * datasets are qualified for, at the same eight characters, and a deployment that wants its own
     * system named sets {@link #SYSTEM_ID_PROPERTY}.
     */
    public static final String DEFAULT_SYSTEM_ID = "AWSMFRAM";

    /** Width of both region items, from {@code app/cpy-bms/COSGN00.CPY} lines 128 and 134. */
    private static final int REGION_IDENTIFIER_WIDTH = SignOnResponse.APPLICATION_ID_LENGTH;

    /** Supplies the two literals the sign-on screen shares with every other screen. */
    private final MessageCatalogService messageCatalogService;

    /** The configured application identifier, already bounded to the map item's width. */
    private final String applicationId;

    /** The configured system identifier, already bounded to the map item's width. */
    private final String systemId;

    /**
     * Creates the adapter over the shared message catalog and the shipped region identifiers.
     *
     * @param messageCatalogService the shared catalog
     */
    public SignOnContractAdapter(final MessageCatalogService messageCatalogService) {
        this(messageCatalogService, DEFAULT_APPLICATION_ID, DEFAULT_SYSTEM_ID);
    }

    /**
     * Creates the adapter over the shared message catalog and the configured region identifiers.
     *
     * @param messageCatalogService the shared catalog
     * @param configuredApplicationId the application identifier this deployment publishes, bounded here
     *                                to the map item's eight characters
     * @param configuredSystemId the system identifier this deployment publishes, bounded the same way
     */
    @Autowired
    public SignOnContractAdapter(final MessageCatalogService messageCatalogService,
            @Value("${" + APPLICATION_ID_PROPERTY + ":" + DEFAULT_APPLICATION_ID + "}")
            final String configuredApplicationId,
            @Value("${" + SYSTEM_ID_PROPERTY + ":" + DEFAULT_SYSTEM_ID + "}")
            final String configuredSystemId) {
        this.messageCatalogService = Objects.requireNonNull(messageCatalogService,
                "messageCatalogService must not be null");
        this.applicationId = withinScreenItem(configuredApplicationId);
        this.systemId = withinScreenItem(configuredSystemId);
    }

    /**
     * Bounds a configured region identifier to the screen item that carries it.
     *
     * <p>A longer value is cut rather than refused, which is what the move into {@code PIC X(8)} did with
     * a longer source: the item has eight positions and keeps the first eight. A value that is absent or
     * blank publishes absence, because a screen item full of spaces and a component carrying an empty
     * string are the same statement - that this deployment named none - and absence says it without a
     * client having to trim.
     *
     * @param  configured the setting as supplied, which may be {@code null}
     * @return the value the response carries, or {@code null} when none was named
     */
    private static String withinScreenItem(final String configured) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        final String named = configured.strip();
        return named.length() <= REGION_IDENTIFIER_WIDTH
                ? named
                : named.substring(0, REGION_IDENTIFIER_WIDTH);
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
                // The map's safe output echo, not the authenticated identity: a redisplayed screen has to
                // restate the identifier the operator keyed, and on a rejected turn there is no
                // authenticated identity to publish. The service carries the two apart for exactly this
                // reason, and reading the wrong one here is what left every rejection's echo empty.
                screen.displayUserId(),
                screen.userTypeCode(),
                SignOnResponse.TRANSACTION_NAME,
                SignOnResponse.PROGRAM_NAME,
                screen.title01(),
                screen.title02(),
                screen.currentDate(),
                screen.currentTime(),
                // The two ASSIGN results of lines 199 and 203. Configuration rather than an assignment,
                // because this deployment is the region; see the class note.
                this.applicationId,
                this.systemId);
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
