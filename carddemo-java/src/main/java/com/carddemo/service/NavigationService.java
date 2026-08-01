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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.carddemo.api.dto.NavigationContext;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.domain.enums.UserType;

/**
 * The CardDemo navigation graph, expressed as a route vocabulary plus the rules that select a route.
 *
 * <p><strong>Provenance.</strong> Translated from the AWS CardDemo z/OS mainframe estate at checkout
 * SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, whose every COBOL and job-control member
 * carries the trailer release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The legacy
 * authorities for this class are the <strong>25</strong> transfer-control ({@code EXEC CICS XCTL})
 * dispatch sites and the <strong>19</strong> pseudo-conversational re-arm
 * ({@code EXEC CICS RETURN TRANSID}) sites spread across the 17 online programs of
 * {@code app/cbl}, the transaction-to-program bindings registered in {@code app/csd/CARDDEMO.CSD},
 * and the navigation state carried in the communication area {@code app/cpy/COCOM01Y.cpy}. Both
 * counts were verified mechanically over non-comment source lines: the transfer-control sites
 * distribute as {@code COACTUPC} 1, {@code COACTVWC} 1, {@code COADM01C} 2, {@code COBIL00C} 1,
 * {@code COCRDLIC} 3, {@code COCRDSLC} 1, {@code COCRDUPC} 1, {@code COMEN01C} 2, {@code CORPT00C} 1,
 * {@code COSGN00C} 2, {@code COTRN00C} 2, {@code COTRN01C} 1, {@code COTRN02C} 1, {@code COUSR00C} 3,
 * {@code COUSR01C} 1, {@code COUSR02C} 1 and {@code COUSR03C} 1.
 *
 * <p><strong>There is no server-side forwarding. This is the central design decision of this
 * class.</strong> In the legacy system a transfer-control command hands control to another program
 * inside the same task, carrying the communication area with it, and a return-with-transaction
 * command re-arms the next pseudo-conversational turn on the same terminal. Neither is reproduced.
 * In the target an endpoint <em>returns a route constant in its response body</em> and the
 * <strong>client drives the next call</strong>. That is precisely what makes every endpoint
 * independently testable: no endpoint is reachable only as the continuation of another, and no test
 * has to drive a conversation to reach a screen. Accordingly this class performs no redirect, no
 * {@code forward:}, no request-dispatcher call, no response-entity construction and no web-framework
 * operation of any kind. It has exactly two responsibilities:
 *
 * <ul>
 *   <li>it <strong>owns the route vocabulary</strong> - one constant per reachable destination, in
 *       {@link Routes} as compile-time string constants and in {@link Route} as the typed form; and</li>
 *   <li>it <strong>owns the route-resolution rules</strong> - which destination a given outcome leads
 *       to, one named method per legacy rule.</li>
 * </ul>
 *
 * <p>It returns values. Callers place those values in their own response payloads.
 *
 * <p><strong>Destinations are named for their role, never for a COBOL program.</strong> No route
 * value on the wire contains a legacy program name; each legacy program and transaction identifier
 * is recorded in the documentation of its constant instead, which keeps the published contract free
 * of mainframe vocabulary while leaving every route traceable back to its origin. The 18 reachable
 * destinations correspond exactly to the 18 transaction definitions registered in the resource
 * definition file.
 *
 * <p><strong>Two source anomalies are recorded here and neither produces a target.</strong> The
 * resource definition file registers a program definition named {@code COCRDSEC} for which no source
 * member exists anywhere in {@code app/cbl}; it is a dangling definition, so <em>no route constant is
 * created for it</em>. Verification of that finding turned up a second one: the developer transaction
 * {@code CDV1} is itself bound to that dangling program definition, which means the transaction
 * cannot have been dispatchable in the shipped estate. The date-validation destination below
 * therefore records {@code CSUTLDTC} - the date-validation subprogram that does exist - as its legacy
 * program, and the binding anomaly is documented rather than reproduced.
 *
 * <p><strong>Rules, and where each comes from.</strong> Every method below is one legacy navigation
 * rule, named so it can be cited:
 *
 * <ul>
 *   <li>the sign-on role split - {@link #resolveSignOnRoute(UserType)} and
 *       {@link #resolveSignOnRouteForUserTypeCode(String)};</li>
 *   <li>entry with no prior state - {@link #isNavigationContextAbsent(NavigationContext)} and
 *       {@link #resolveAbsentContextRoute()};</li>
 *   <li>return to the previous screen - {@link #resolveBackNavigation(NavigationContext, Route)},
 *       reached through {@link #resolveAttentionKeyRoute(KeyAction, NavigationContext, Route)};</li>
 *   <li>return to an already nominated destination -
 *       {@link #resolveNominatedDestination(NavigationContext, Route)} and its sign-off special case
 *       {@link #resolveSignOffRoute(NavigationContext)};</li>
 *   <li>forward dispatch from a menu - {@link #resolveMenuDispatch(UserType, String, String)} and
 *       {@link #resolveAdminMenuDispatch(String)}.</li>
 * </ul>
 *
 * <p>The 25 transfer-control and 19 return-with-transaction sites are not themselves paragraph units
 * and add no row to the paragraph traceability matrix; the paragraphs containing them belong to their
 * owning programs' services. What this class contributes to traceability is a named, citable method
 * per rule.
 *
 * <p><strong>What this class deliberately does not do.</strong> It decodes no attention key -
 * translating a raw terminal identifier into an action, including folding program-function keys 13
 * through 24 back onto 1 through 12, belongs to the utility-layer key translator, which returns an
 * action and never a route; this class maps an action plus context <em>to</em> a route. It carries no
 * screen message text - the messages belong to {@code MessageCatalogService} and to the owning online
 * service, so the "coming soon" text and the admin-only text are composed by {@code MenuService} and
 * not here. It makes no authorisation decision - role gating is enforced by the security
 * configuration and at the controller; {@link #adminScopedRoutes()} and {@link Route#isAdminScoped()}
 * merely <em>report</em> that a destination is administrative, which the controller layer consumes.
 * It touches no database, no repository, no monetary value and no fixed-width record offset.
 *
 * <p><strong>Immutability and thread safety.</strong> Every lookup table is built once during class
 * initialization and published only through an unmodifiable view, and the class declares no mutable
 * field of any kind. Instances are therefore stateless and safe to share across threads, which is
 * what allows the framework to manage a single one. Constructor injection is the convention
 * throughout this layer; this class injects nothing because it collaborates with nothing.
 *
 * <p><strong>Faithful beats idiomatic.</strong> Where the legacy semantics and the natural Java shape
 * diverge, the legacy wins and the divergence is documented at the member concerned. The three that
 * matter most are the unconditional alternative in the sign-on split, which routes an undeclared user
 * type to the main menu rather than rejecting it; the two documented-unreachable menu branches, which
 * are preserved rather than deleted or corrected; and the treatment of an unrecognised program name,
 * described on {@link #resolveBackNavigation(NavigationContext, Route)}.
 */
@Service
public final class NavigationService {

    /**
     * Diagnostic channel for this class, replacing the console-display statements that were the
     * legacy system's only instrumentation.
     *
     * <p>The logger name is the fully qualified class name, which places it under the
     * {@code com.carddemo.service} logger declared in the logging configuration. Only route tokens,
     * legacy program names and transaction identifiers are ever logged from here; no navigation
     * context is logged as a whole, and nothing regulated passes through this class at all.
     */
    private static final Logger LOG = LoggerFactory.getLogger(NavigationService.class);

    /**
     * Number of transfer-control dispatch sites in the legacy estate: 25.
     *
     * <p>Published as a constant because it is the size of the graph this class replaces, and because
     * the migration evidence records it. Counted over non-comment lines of {@code app/cbl}; a naive
     * count finds 29 and includes four occurrences that sit inside comments.
     */
    public static final int LEGACY_DISPATCH_SITE_COUNT = 25;

    /**
     * Number of pseudo-conversational re-arm sites in the legacy estate: 19, spread across the 17
     * online programs. Two programs contain two apiece.
     */
    public static final int LEGACY_REARM_SITE_COUNT = 19;

    /**
     * Number of reachable destinations: 18, one per transaction definition registered in
     * {@code app/csd/CARDDEMO.CSD}.
     *
     * <p>The resource definition file also registers 18 program definitions, but the two sets are not
     * in correspondence: one program definition is dangling, as the class documentation describes.
     */
    public static final int ROUTE_COUNT = 18;

    /**
     * The five leading characters that suppress menu dispatch: {@code DUMMY}.
     *
     * <p>The legacy menu programs guard dispatch with a comparison of the first five characters of the
     * catalogued program name against this literal, at {@code app/cbl/COMEN01C.cbl} line 146 and
     * {@code app/cbl/COADM01C.cbl} line 138. Held here because the literal and the five-character
     * width are both part of the rule; see {@link #isDispatchSuppressed(String)} for why the branch is
     * unreachable in the shipped estate and is nonetheless preserved.
     */
    public static final String DUMMY_PROGRAM_PREFIX = "DUMMY";

    /**
     * The catalogued option user-type code that the user menu's administrator-only gate tests:
     * {@code A}.
     *
     * <p>From the gate at {@code app/cbl/COMEN01C.cbl} lines 136 to 143. Declared here rather than
     * borrowed from the menu catalogue because that catalogue lives in the configuration layer, which
     * this layer must not depend on.
     */
    public static final String ADMIN_ONLY_OPTION_USER_TYPE_CODE = "A";

    /**
     * The route values as compile-time string constants - the vocabulary that travels on the wire.
     *
     * <p>These are the values a response body carries and a client echoes back. They are compile-time
     * constants so that a controller, a response payload or a test can name a destination without
     * duplicating its text: the typed {@link Route} constants are initialized from exactly these
     * fields, so there is one definition of each value and no possibility of the two drifting apart.
     *
     * <p><strong>Every value is named for the destination's role.</strong> None contains a COBOL
     * program name, a transaction identifier or any other mainframe token. The form is lower case with
     * hyphen separators, which is stable, readable inside a JSON payload and independent of the
     * endpoint paths that the API layer chooses - this layer does not know those paths and must not
     * encode them.
     *
     * <p>A holder of constants only: it is final, it declares no instance member, and its single
     * private constructor exists to make instantiation impossible rather than merely unusual.
     */
    public static final class Routes {

        /** Sign-on. Legacy transaction {@code CC00}, legacy program {@code COSGN00C}. */
        public static final String SIGN_ON = "sign-on";

        /** User main menu, 10 options. Legacy transaction {@code CM00}, legacy program {@code COMEN01C}. */
        public static final String USER_MENU = "user-menu";

        /** Administrative menu, 4 options. Legacy transaction {@code CA00}, legacy program {@code COADM01C}. */
        public static final String ADMIN_MENU = "admin-menu";

        /** Account view. Legacy transaction {@code CAVW}, legacy program {@code COACTVWC}. */
        public static final String ACCOUNT_VIEW = "account-view";

        /** Account update. Legacy transaction {@code CAUP}, legacy program {@code COACTUPC}. */
        public static final String ACCOUNT_UPDATE = "account-update";

        /** Card list. Legacy transaction {@code CCLI}, legacy program {@code COCRDLIC}. */
        public static final String CARD_LIST = "card-list";

        /** Card detail. Legacy transaction {@code CCDL}, legacy program {@code COCRDSLC}. */
        public static final String CARD_DETAIL = "card-detail";

        /** Card update. Legacy transaction {@code CCUP}, legacy program {@code COCRDUPC}. */
        public static final String CARD_UPDATE = "card-update";

        /** Transaction list. Legacy transaction {@code CT00}, legacy program {@code COTRN00C}. */
        public static final String TRANSACTION_LIST = "transaction-list";

        /** Transaction view. Legacy transaction {@code CT01}, legacy program {@code COTRN01C}. */
        public static final String TRANSACTION_VIEW = "transaction-view";

        /** Transaction add. Legacy transaction {@code CT02}, legacy program {@code COTRN02C}. */
        public static final String TRANSACTION_ADD = "transaction-add";

        /** Report request. Legacy transaction {@code CR00}, legacy program {@code CORPT00C}. */
        public static final String REPORT_REQUEST = "report-request";

        /** Bill payment. Legacy transaction {@code CB00}, legacy program {@code COBIL00C}. */
        public static final String BILL_PAYMENT = "bill-payment";

        /** User list, administrative. Legacy transaction {@code CU00}, legacy program {@code COUSR00C}. */
        public static final String USER_LIST = "user-list";

        /** User add, administrative. Legacy transaction {@code CU01}, legacy program {@code COUSR01C}. */
        public static final String USER_ADD = "user-add";

        /** User update, administrative. Legacy transaction {@code CU02}, legacy program {@code COUSR02C}. */
        public static final String USER_UPDATE = "user-update";

        /** User delete, administrative. Legacy transaction {@code CU03}, legacy program {@code COUSR03C}. */
        public static final String USER_DELETE = "user-delete";

        /**
         * Date-validation utility. Legacy transaction {@code CDV1}, legacy program {@code CSUTLDTC}.
         *
         * <p>The resource definition file binds {@code CDV1} to the dangling program definition
         * {@code COCRDSEC}, which has no source member; the date-validation subprogram
         * {@code CSUTLDTC} is the destination's real implementation and is recorded here in its place.
         */
        public static final String DATE_VALIDATION = "date-validation";

        /**
         * Not instantiable. This type is a holder of compile-time constants and has no behaviour and
         * no state, so an instance would be meaningless.
         */
        private Routes() {
            throw new AssertionError("Routes is a constant holder and must not be instantiated");
        }
    }

    /**
     * The typed route vocabulary - one constant per reachable destination, 18 in all.
     *
     * <p>The destinations correspond exactly to the 18 transaction definitions registered in
     * {@code app/csd/CARDDEMO.CSD}. Each constant carries four things: the wire value, taken directly
     * from the matching field of {@link Routes} so the two can never diverge; the legacy transaction
     * identifier and legacy program name, held for traceability and never published as the route value
     * itself; and whether the destination is administrative.
     *
     * <p>Declaration order is the order a user traverses the application - sign-on, then the two
     * menus, then the account, card, transaction, reporting and payment destinations, then the
     * administrative user-maintenance destinations, then the date-validation utility. That order is
     * what {@link #routes()} exposes.
     *
     * <p><strong>No constant exists for {@code COCRDSEC}</strong>, the dangling program definition
     * described in the class documentation, and none may be added.
     *
     * <p>This is a typed vocabulary and nothing more: it holds no route table, resolves nothing and
     * decides nothing. Selection is the enclosing service's responsibility.
     */
    public enum Route {

        /**
         * Sign-on, the entry point and the destination of every sign-off and every
         * entry that carries no prior navigation state.
         *
         * <p>Legacy transaction {@code CC00}, legacy program {@code COSGN00C}. It is the target that
         * both menu programs nominate on their sign-off path, at {@code app/cbl/COMEN01C.cbl} line 173
         * and {@code app/cbl/COADM01C.cbl} line 163.
         */
        SIGN_ON(Routes.SIGN_ON, "CC00", "COSGN00C", false),

        /**
         * User main menu, carrying 10 options.
         *
         * <p>Legacy transaction {@code CM00}, legacy program {@code COMEN01C}. This is the destination
         * of the unconditional alternative in the sign-on role split at
         * {@code app/cbl/COSGN00C.cbl} line 235, and the verified back-navigation default of the
         * bill-payment program at {@code app/cbl/COBIL00C.cbl} line 130.
         */
        USER_MENU(Routes.USER_MENU, "CM00", "COMEN01C", false),

        /**
         * Administrative menu, carrying 4 options.
         *
         * <p>Legacy transaction {@code CA00}, legacy program {@code COADM01C}. Reached only when the
         * administrator condition holds at {@code app/cbl/COSGN00C.cbl} line 230. Administrative.
         */
        ADMIN_MENU(Routes.ADMIN_MENU, "CA00", "COADM01C", true),

        /** Account view. Legacy transaction {@code CAVW}, legacy program {@code COACTVWC}. */
        ACCOUNT_VIEW(Routes.ACCOUNT_VIEW, "CAVW", "COACTVWC", false),

        /** Account update. Legacy transaction {@code CAUP}, legacy program {@code COACTUPC}. */
        ACCOUNT_UPDATE(Routes.ACCOUNT_UPDATE, "CAUP", "COACTUPC", false),

        /** Card list. Legacy transaction {@code CCLI}, legacy program {@code COCRDLIC}. */
        CARD_LIST(Routes.CARD_LIST, "CCLI", "COCRDLIC", false),

        /** Card detail. Legacy transaction {@code CCDL}, legacy program {@code COCRDSLC}. */
        CARD_DETAIL(Routes.CARD_DETAIL, "CCDL", "COCRDSLC", false),

        /** Card update. Legacy transaction {@code CCUP}, legacy program {@code COCRDUPC}. */
        CARD_UPDATE(Routes.CARD_UPDATE, "CCUP", "COCRDUPC", false),

        /** Transaction list. Legacy transaction {@code CT00}, legacy program {@code COTRN00C}. */
        TRANSACTION_LIST(Routes.TRANSACTION_LIST, "CT00", "COTRN00C", false),

        /** Transaction view. Legacy transaction {@code CT01}, legacy program {@code COTRN01C}. */
        TRANSACTION_VIEW(Routes.TRANSACTION_VIEW, "CT01", "COTRN01C", false),

        /**
         * Transaction add. Legacy transaction {@code CT02}, legacy program {@code COTRN02C}.
         *
         * <p>Not administrative. The user menu's eighth option carries a commented-out alternative
         * label marking this destination as administrator-only, and that label is inactive in the
         * shipped estate; the active label and the option's user-type code both make it available to a
         * standard user, so treating it as administrative here would deny access the legacy grants.
         */
        TRANSACTION_ADD(Routes.TRANSACTION_ADD, "CT02", "COTRN02C", false),

        /**
         * Report request. Legacy transaction {@code CR00}, legacy program {@code CORPT00C}.
         *
         * <p>The only destination whose own processing crosses into the batch tier, by way of the
         * estate's single transient-data-queue write. That bridge belongs to the job-submission
         * service; nothing about it is navigation, and nothing about it appears here.
         */
        REPORT_REQUEST(Routes.REPORT_REQUEST, "CR00", "CORPT00C", false),

        /** Bill payment. Legacy transaction {@code CB00}, legacy program {@code COBIL00C}. */
        BILL_PAYMENT(Routes.BILL_PAYMENT, "CB00", "COBIL00C", false),

        /**
         * User list. Legacy transaction {@code CU00}, legacy program {@code COUSR00C}. Administrative:
         * reachable in the legacy estate only from the administrative menu.
         */
        USER_LIST(Routes.USER_LIST, "CU00", "COUSR00C", true),

        /** User add. Legacy transaction {@code CU01}, legacy program {@code COUSR01C}. Administrative. */
        USER_ADD(Routes.USER_ADD, "CU01", "COUSR01C", true),

        /** User update. Legacy transaction {@code CU02}, legacy program {@code COUSR02C}. Administrative. */
        USER_UPDATE(Routes.USER_UPDATE, "CU02", "COUSR02C", true),

        /** User delete. Legacy transaction {@code CU03}, legacy program {@code COUSR03C}. Administrative. */
        USER_DELETE(Routes.USER_DELETE, "CU03", "COUSR03C", true),

        /**
         * Date-validation utility. Legacy transaction {@code CDV1}, legacy program {@code CSUTLDTC}.
         *
         * <p>Not administrative, and not reachable from either menu catalogue: the resource definition
         * file describes {@code CDV1} as a developer transaction. It is included because it is one of
         * the 18 registered transaction definitions, and omitting it would leave the vocabulary short
         * of the resource definition it is derived from.
         *
         * <p>The transaction definition binds to the dangling program definition {@code COCRDSEC},
         * which has no source member. {@code CSUTLDTC} is recorded here instead because it is the
         * date-validation subprogram that exists and that four call sites in the estate invoke; the
         * binding anomaly is documented rather than reproduced, and no route is created for
         * {@code COCRDSEC}.
         */
        DATE_VALIDATION(Routes.DATE_VALIDATION, "CDV1", "CSUTLDTC", false);

        /** The wire value, identical to the matching field of {@link Routes}. Never {@code null}. */
        private final String routeValue;

        /** The four-character legacy transaction identifier. Traceability only. Never {@code null}. */
        private final String legacyTransactionId;

        /** The eight-character legacy program name. Traceability only. Never {@code null}. */
        private final String legacyProgramName;

        /** Whether the destination is administrative. */
        private final boolean adminScoped;

        /**
         * Binds a destination to its wire value, its legacy identifiers and its administrative scope.
         *
         * @param routeValue          the wire value, supplied from {@link Routes}
         * @param legacyTransactionId the four-character legacy transaction identifier
         * @param legacyProgramName   the eight-character legacy program name
         * @param adminScoped         whether the destination is administrative
         */
        Route(String routeValue, String legacyTransactionId, String legacyProgramName,
                boolean adminScoped) {
            this.routeValue = routeValue;
            this.legacyTransactionId = legacyTransactionId;
            this.legacyProgramName = legacyProgramName;
            this.adminScoped = adminScoped;
        }

        /**
         * Returns the wire value for this destination - the token a response body carries and a client
         * echoes back.
         *
         * <p>Role-named, and free of any legacy program name or transaction identifier.
         *
         * @return the wire value; never {@code null}
         */
        public String getRouteValue() {
            return routeValue;
        }

        /**
         * Returns the legacy transaction identifier this destination replaces, as registered in
         * {@code app/csd/CARDDEMO.CSD}.
         *
         * <p>Traceability only: it is never the wire value and nothing in the published contract
         * depends on it.
         *
         * @return the four-character legacy transaction identifier; never {@code null}
         */
        public String getLegacyTransactionId() {
            return legacyTransactionId;
        }

        /**
         * Returns the legacy program name this destination replaces.
         *
         * <p>This is also the value the communication area carries in its originating- and
         * destination-program fields, which is why the enclosing service can resolve one of those
         * fields to a destination. Traceability only as a published value: it is never the wire value.
         *
         * @return the eight-character legacy program name; never {@code null}
         */
        public String getLegacyProgramName() {
            return legacyProgramName;
        }

        /**
         * Reports whether this destination is administrative - that is, one of the administrative menu
         * and the four user-maintenance destinations.
         *
         * <p>This <strong>reports</strong> scope and <strong>decides</strong> nothing. Enforcement
         * belongs to the security configuration and to the controller; nothing in this class or this
         * enum performs an access check.
         *
         * @return {@code true} for the administrative menu and the four user-maintenance destinations
         */
        public boolean isAdminScoped() {
            return adminScoped;
        }
    }

    /**
     * Every destination, in declaration order, as an immutable list.
     *
     * <p>Built from the declared constants themselves rather than from a second hand-written list, so
     * it cannot fall out of step with them. Exposed through {@link #routes()}.
     */
    private static final List<Route> ALL_ROUTES = List.of(Route.values());

    /**
     * The administrative destinations: the administrative menu and the four user-maintenance
     * destinations, five in all.
     *
     * <p>Derived by filtering the declared constants on their own administrative flag, so adding a
     * destination marked administrative extends this set automatically and no second list has to be
     * maintained. Immutable, and exposed through {@link #adminScopedRoutes()}.
     */
    private static final Set<Route> ADMIN_SCOPED_ROUTES = indexAdminScoped();

    /**
     * Index from legacy program name to destination, used to resolve the originating- and
     * destination-program fields of the communication area, and the program name a menu catalogue
     * entry carries.
     *
     * <p>Immutable and built once. This is the table that makes back-navigation possible at all: the
     * legacy fields carry an eight-character program name, not a route.
     */
    private static final Map<String, Route> ROUTES_BY_LEGACY_PROGRAM = indexBy(Route::getLegacyProgramName);

    /**
     * Index from legacy transaction identifier to destination, mirroring the transaction definitions
     * registered in {@code app/csd/CARDDEMO.CSD}. Immutable and built once.
     */
    private static final Map<String, Route> ROUTES_BY_LEGACY_TRANSACTION =
            indexBy(Route::getLegacyTransactionId);

    /**
     * Index from wire value to destination, used to read back a route a client echoed. Immutable and
     * built once.
     */
    private static final Map<String, Route> ROUTES_BY_VALUE = indexBy(Route::getRouteValue);

    /**
     * Creates the navigation service.
     *
     * <p>Explicit and empty because this class injects nothing: it is the base of the service layer
     * and collaborates with no other service, so there is no dependency to receive. Constructor
     * injection remains the convention for every service that does have collaborators.
     *
     * <p>The instance holds no state. All four lookup structures are immutable static members
     * initialized once during class initialization, so instances are interchangeable and safe to share
     * across threads.
     */
    public NavigationService() {
        // No collaborators and no state to initialize; the route tables are immutable static members.
    }

    // ------------------------------------------------------------------------------------------
    // Rule: the sign-on role split
    // ------------------------------------------------------------------------------------------

    /**
     * Resolves the destination a completed sign-on leads to, from the signed-on user's type.
     *
     * <p><strong>Reproduces an asymmetry that must not be tidied up.</strong> The legacy program tests
     * the administrator condition at {@code app/cbl/COSGN00C.cbl} line 230 and hands control to the
     * administrative menu when it holds. What follows at line 235 is an
     * <strong>unconditional alternative</strong>, closing at line 240: it is <em>not</em> a second test
     * of the standard-user condition, there is no third branch, and there is no validation of the type
     * value anywhere on the path. The value reaching the test was moved straight out of the credential
     * record at line 227 without inspection.
     *
     * <p>Two consequences are therefore contractual. Only the administrator type reaches the
     * administrative menu. <em>Every</em> other input - the standard user type, and equally an absent
     * type or one outside the two-value vocabulary the estate declares - reaches the user main menu.
     * This method consequently accepts {@code null} and <strong>never throws</strong>: the conventional
     * Java shape would reject an unrecognised type with an exception, and that would abort a sign-on
     * the legacy program completes.
     *
     * @param userType the signed-on user's type, or {@code null} when the raw code could not be
     *                 resolved to one of the two declared values
     * @return {@link Route#ADMIN_MENU} for the administrator type; {@link Route#USER_MENU} for every
     *         other input, including {@code null}
     */
    public Route resolveSignOnRoute(final UserType userType) {
        final Route route;
        if (userType != null && userType.isAdmin()) {
            route = Route.ADMIN_MENU;
        } else {
            // The unconditional alternative: the standard user type, an absent type and any type
            // outside the declared vocabulary all arrive here. No further test, and no error path.
            route = Route.USER_MENU;
        }
        LOG.debug("Sign-on route resolved: rule=sign-on-role-split userType={} route={}",
                userType, route.getRouteValue());
        return route;
    }

    /**
     * Resolves the destination a completed sign-on leads to, from the raw one-character user-type code.
     *
     * <p>The whole of the legacy path in one call: the code is resolved to a type exactly as the
     * credential record's value is moved into the communication area at
     * {@code app/cbl/COSGN00C.cbl} line 227, and the resolved type then drives the split described on
     * {@link #resolveSignOnRoute(UserType)}.
     *
     * <p>Resolution never throws and applies no case fold, so a code that is absent, blank, over-long,
     * lower-cased or simply undeclared resolves to nothing - and nothing, being not the administrator
     * type, reaches the user main menu. That is the unconditional alternative faithfully reproduced,
     * and it is why this method has no error path.
     *
     * @param userTypeCode the raw one-character code, as carried by the communication area or read
     *                     from the credential record; may be {@code null}
     * @return {@link Route#ADMIN_MENU} for the administrator code; {@link Route#USER_MENU} for every
     *         other code, including {@code null}, a blank and any value outside the declared vocabulary
     */
    public Route resolveSignOnRouteForUserTypeCode(final String userTypeCode) {
        final Optional<UserType> resolved = UserType.fromCode(userTypeCode);
        return resolveSignOnRoute(resolved.orElse(null));
    }

    // ------------------------------------------------------------------------------------------
    // Rule: entry carrying no prior navigation state
    // ------------------------------------------------------------------------------------------

    /**
     * Reports whether an online turn carries no prior navigation state.
     *
     * <p>The equivalent of the zero-length communication-area test that opens every online program's
     * main paragraph, at {@code app/cbl/COBIL00C.cbl} line 107 and in its sixteen peers. A turn is
     * stateless when no context was supplied at all, and equally when the supplied context is the
     * wholly empty one: an empty context carries no signed-on user, no selection and no previous
     * screen, which is exactly the state a zero-length communication area describes.
     *
     * @param context the navigation state echoed by the client; may be {@code null}
     * @return {@code true} when the context is {@code null} or wholly empty
     */
    public boolean isNavigationContextAbsent(final NavigationContext context) {
        return context == null || NavigationContext.empty().equals(context);
    }

    /**
     * Returns the destination an online turn carrying no prior navigation state leads to: sign-on,
     * unconditionally.
     *
     * <p>From the zero-length communication-area branch at {@code app/cbl/COBIL00C.cbl} lines 107 and
     * 108, which nominates the sign-on program and returns to the previous screen without consulting
     * anything else. The administrative menu takes the same branch at
     * {@code app/cbl/COADM01C.cbl} lines 82 to 84.
     *
     * <p>Named and exposed rather than left to each caller so that the rule is stated once. It takes no
     * argument and admits no override, because the legacy branch takes none either: there is no
     * per-program default on this path, unlike the back-navigation path.
     *
     * @return {@link Route#SIGN_ON}, always
     */
    public Route resolveAbsentContextRoute() {
        return Route.SIGN_ON;
    }

    // ------------------------------------------------------------------------------------------
    // Rule: return to the previous screen
    // ------------------------------------------------------------------------------------------

    /**
     * Reports whether an attention-key action is the one that returns to the previous screen.
     *
     * <p>True for the third program-function key alone. All seventeen online programs treat that key as
     * "return to the previous screen", as the attention-key evaluation at
     * {@code app/cbl/COBIL00C.cbl} line 128 does.
     *
     * <p>This class performs <strong>no</strong> attention-key decoding: turning a raw terminal
     * identifier into an action, including the fold of program-function keys 13 through 24 back onto 1
     * through 12, belongs to the utility-layer key translator. This method consumes an already decoded
     * action and derives nothing. A {@code null} action means no key was decoded, which is not the
     * back-navigation key, so it answers {@code false} rather than throwing.
     *
     * @param keyAction the decoded attention-key action; may be {@code null}
     * @return {@code true} if and only if the action is the third program-function key
     */
    public boolean isBackNavigationKey(final KeyAction keyAction) {
        return keyAction == KeyAction.PFK03;
    }

    /**
     * Resolves the destination that returning to the previous screen leads to, given the navigation
     * state and the calling screen's own default.
     *
     * <p><strong>Both arms of the legacy rule are modelled.</strong> At
     * {@code app/cbl/COBIL00C.cbl} lines 129 to 134 the program tests the originating-program field of
     * the communication area: when the field holds spaces or low values it nominates the calling
     * screen's own default, and otherwise it nominates the destination that field names. The default is
     * <strong>per screen and not global</strong> - the bill-payment program's is the user main menu at
     * line 130, while both menu programs default their sign-off path to sign-on - which is why the
     * fallback is a parameter here and is never assumed.
     *
     * <p>The blank test is the legacy test, not the conventional Java one: the field is fixed width, so
     * a value consisting entirely of spaces or of low values is blank, while a value containing other
     * white space is not.
     *
     * <p><strong>One documented divergence.</strong> When the field is present but names no reachable
     * destination, the caller's default applies and the event is logged as a warning. The legacy would
     * have attempted the transfer and abended on an unresolvable program name. The divergence is
     * deliberate: this field is client-echoed state and therefore untrusted, so honouring it blindly
     * would let a client provoke a server failure by echoing an unknown name, while a value that names
     * no reachable destination nominates nothing - which is behaviourally what an empty field does.
     * In the shipped estate the field can only ever hold one of the seventeen online program names, so
     * the divergence is unreachable from legitimate input.
     *
     * @param context       the navigation state echoed by the client; must not be {@code null}. Entry
     *                      carrying no state at all is a different rule - see
     *                      {@link #isNavigationContextAbsent(NavigationContext)} - and is resolved
     *                      before this one is reached
     * @param callerDefault the calling screen's own default destination, applied when the originating
     *                      program field nominates nothing usable; must not be {@code null}
     * @return the destination the originating-program field names, or {@code callerDefault} when that
     *         field is blank or names no reachable destination
     * @throws NullPointerException if {@code context} or {@code callerDefault} is {@code null}
     */
    public Route resolveBackNavigation(final NavigationContext context, final Route callerDefault) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(callerDefault, "callerDefault must not be null");
        return resolveNominatedProgram(context.fromProgram(), callerDefault, "back-navigation");
    }

    /**
     * Resolves the destination an attention key leads to, producing a destination only for the key that
     * returns to the previous screen.
     *
     * <p>The navigation-bearing arm of the attention-key evaluation at
     * {@code app/cbl/COBIL00C.cbl} lines 125 to 142. Only the third program-function key transfers
     * control there; the enter key is processed by the screen itself, and every other key produces an
     * invalid-key message and re-presents the same screen. Neither of those transfers control, so
     * neither yields a destination, and both are reported here as an empty result.
     *
     * <p>The message text that accompanies an unmapped key is not this class's concern; it belongs to
     * the common message catalogue and is emitted by the owning online service.
     *
     * @param keyAction     the decoded attention-key action; may be {@code null}, which yields an empty
     *                      result
     * @param context       the navigation state echoed by the client; must not be {@code null}
     * @param callerDefault the calling screen's own back-navigation default; must not be {@code null}
     * @return the back-navigation destination when the action is the third program-function key,
     *         otherwise an empty result
     * @throws NullPointerException if {@code context} or {@code callerDefault} is {@code null}
     */
    public Optional<Route> resolveAttentionKeyRoute(final KeyAction keyAction,
            final NavigationContext context, final Route callerDefault) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(callerDefault, "callerDefault must not be null");
        if (!isBackNavigationKey(keyAction)) {
            LOG.debug("Attention key produces no route: rule=attention-key-dispatch keyAction={}",
                    keyAction);
            return Optional.empty();
        }
        return Optional.of(resolveBackNavigation(context, callerDefault));
    }

    // ------------------------------------------------------------------------------------------
    // Rule: return to an already nominated destination
    // ------------------------------------------------------------------------------------------

    /**
     * Resolves the destination already nominated in the navigation state, falling back to the caller's
     * own default when nothing is nominated.
     *
     * <p>A rule distinct from back-navigation, and distinct in the source too. Back-navigation inspects
     * the <em>originating</em>-program field; this inspects the <em>destination</em>-program field,
     * which a screen sets before handing off. The legacy shape is the sign-off paragraph of the two
     * menu programs, at {@code app/cbl/COMEN01C.cbl} lines 170 to 177 and
     * {@code app/cbl/COADM01C.cbl} lines 160 to 167: the field is tested for low values or spaces, the
     * program's own default is substituted when it is blank, and control transfers to whatever the
     * field then holds.
     *
     * <p>The fallback is a parameter for the same reason it is on back-navigation: the default is per
     * screen. The unrecognised-name divergence documented on
     * {@link #resolveBackNavigation(NavigationContext, Route)} applies identically here.
     *
     * @param context       the navigation state echoed by the client; must not be {@code null}
     * @param callerDefault the destination to use when nothing is nominated; must not be {@code null}
     * @return the nominated destination, or {@code callerDefault} when the destination-program field is
     *         blank or names no reachable destination
     * @throws NullPointerException if {@code context} or {@code callerDefault} is {@code null}
     */
    public Route resolveNominatedDestination(final NavigationContext context, final Route callerDefault) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(callerDefault, "callerDefault must not be null");
        return resolveNominatedProgram(context.toProgram(), callerDefault, "nominated-destination");
    }

    /**
     * Resolves the destination a sign-off leads to, defaulting to sign-on.
     *
     * <p>The verified special case of {@link #resolveNominatedDestination(NavigationContext, Route)}
     * for the two menu programs: each nominates the sign-on program on its exit key and each defaults
     * the destination to sign-on when nothing is nominated, at {@code app/cbl/COMEN01C.cbl} lines 97
     * and 173 and {@code app/cbl/COADM01C.cbl} lines 97 and 163.
     *
     * <p>This method fixes the fallback because the source fixes it for these two programs, and for
     * them only. It is <strong>not</strong> a global default: any other screen resolving a nominated
     * destination must supply its own fallback through
     * {@link #resolveNominatedDestination(NavigationContext, Route)}.
     *
     * @param context the navigation state echoed by the client; must not be {@code null}
     * @return the nominated destination, or {@link Route#SIGN_ON} when nothing usable is nominated
     * @throws NullPointerException if {@code context} is {@code null}
     */
    public Route resolveSignOffRoute(final NavigationContext context) {
        return resolveNominatedDestination(context, Route.SIGN_ON);
    }

    // ------------------------------------------------------------------------------------------
    // Rule: forward dispatch from a menu
    // ------------------------------------------------------------------------------------------

    /**
     * Reports whether a catalogued program name suppresses dispatch.
     *
     * <p>The legacy guard compares the <strong>first five characters</strong> of the catalogued program
     * name against the literal {@code DUMMY} and dispatches only when they differ, at
     * {@code app/cbl/COMEN01C.cbl} line 146 and {@code app/cbl/COADM01C.cbl} line 138. When they match,
     * control never transfers and the program falls through to compose a "coming soon" message instead -
     * so a suppressed selection produces <strong>no route at all</strong>, which is why the menu
     * dispatch methods return an empty result rather than a destination.
     *
     * <p><strong>Documented unreachable, and deliberately preserved.</strong> No entry in either menu
     * catalogue names a program beginning with {@code DUMMY}: the user catalogue's ten entries name the
     * account, card, transaction, reporting and payment programs, and the administrative catalogue's
     * four name the user-maintenance programs. The branch therefore cannot fire in the shipped estate.
     * It is reproduced anyway, because deleting it would discard a documented behaviour of the source
     * and because the guard is what makes a catalogue entry safely extensible.
     *
     * <p>A blank or absent name is <em>not</em> suppressed, faithfully: it does not begin with the
     * literal, so the legacy guard would let dispatch proceed. Such a name simply names no reachable
     * destination, and the dispatch methods report that as an empty result.
     *
     * @param catalogProgramName the program name carried by the selected menu catalogue entry; may be
     *                           {@code null}
     * @return {@code true} if and only if the name's first five characters are the suppression literal
     */
    public boolean isDispatchSuppressed(final String catalogProgramName) {
        return catalogProgramName != null && catalogProgramName.startsWith(DUMMY_PROGRAM_PREFIX);
    }

    /**
     * Reports whether the user menu's administrator-only gate denies a selection.
     *
     * <p>The gate at {@code app/cbl/COMEN01C.cbl} lines 136 to 143 fires when two conditions hold
     * together: the signed-on type satisfies the <em>standard-user</em> condition, and the selected
     * catalogue entry's own user-type code is the administrator code. When it fires, an error is raised,
     * the menu is re-presented and no control transfer occurs - so no route is produced.
     *
     * <p><strong>The first condition is the standard-user condition, not "not an administrator".</strong>
     * That distinction is behavioural and is preserved: a type outside the declared two-value vocabulary
     * satisfies neither condition name, so it does <em>not</em> trip the gate, even though it is equally
     * not an administrator. Widening the test to "not an administrator" would deny a selection the
     * legacy permits.
     *
     * <p><strong>Documented unreachable, and deliberately preserved.</strong> All ten entries of the
     * user menu catalogue carry the standard-user code, so the second condition can never hold and the
     * gate can never fire. The eighth entry does carry a commented-out alternative label marking it
     * administrator-only, and that label is inactive in the shipped estate - which is precisely why the
     * gate exists and equally why it is dormant. It is reproduced rather than deleted or corrected.
     *
     * <p>The denial message text belongs to the owning menu service, not here.
     *
     * @param selectingUserType  the signed-on user's type; may be {@code null}, which trips nothing
     * @param optionUserTypeCode the user-type code carried by the selected catalogue entry; may be
     *                           {@code null}, which trips nothing
     * @return {@code true} if and only if a standard user selected an administrator-only entry
     */
    public boolean isAdminOnlyOptionDenied(final UserType selectingUserType,
            final String optionUserTypeCode) {
        return selectingUserType == UserType.USER
                && ADMIN_ONLY_OPTION_USER_TYPE_CODE.equals(optionUserTypeCode);
    }

    /**
     * Resolves the destination a user-menu selection dispatches to, applying the legacy gates in source
     * order.
     *
     * <p>The order is the contract and is preserved: the administrator-only gate at
     * {@code app/cbl/COMEN01C.cbl} lines 136 to 143 is evaluated first and short-circuits, then the
     * suppression guard at line 146, and only then does control transfer at lines 152 to 155. Either
     * gate yields an empty result, because in both cases the legacy program re-presents the menu instead
     * of transferring control.
     *
     * <p>The option's program name and user-type code are passed in as plain values rather than read
     * from the menu catalogue, because the catalogue is a configuration-layer component and this layer
     * must not depend on it. The caller supplies the two fields of the selected entry.
     *
     * <p>Both gates are documented-unreachable in the shipped estate; see
     * {@link #isAdminOnlyOptionDenied(UserType, String)} and {@link #isDispatchSuppressed(String)} for
     * the evidence and for why each is preserved.
     *
     * @param selectingUserType  the signed-on user's type; may be {@code null}
     * @param optionUserTypeCode the user-type code of the selected catalogue entry; may be {@code null}
     * @param catalogProgramName the program name of the selected catalogue entry; may be {@code null}
     * @return the destination to dispatch to, or an empty result when the administrator-only gate denies
     *         the selection, when suppression applies, or when the name resolves to no destination
     */
    public Optional<Route> resolveMenuDispatch(final UserType selectingUserType,
            final String optionUserTypeCode, final String catalogProgramName) {
        if (isAdminOnlyOptionDenied(selectingUserType, optionUserTypeCode)) {
            LOG.debug("Menu dispatch denied: rule=user-menu-admin-only-gate userType={} optionUserType={}",
                    selectingUserType, optionUserTypeCode);
            return Optional.empty();
        }
        return resolveCatalogDispatch(catalogProgramName, "user-menu-dispatch");
    }

    /**
     * Resolves the destination an administrative-menu selection dispatches to.
     *
     * <p>The administrative menu applies the suppression guard at
     * {@code app/cbl/COADM01C.cbl} line 138 and <strong>no user-type gate at all</strong>: its
     * catalogue entries carry only a number, a label and a program name, with no user-type field for a
     * gate to test. That is why this is a separate method rather than the same one with a null type -
     * the absence of the gate is a property of the administrative menu, not an argument a caller
     * chooses.
     *
     * <p>Access to the administrative menu itself is what restricts these destinations, and that is
     * enforced by the security configuration and the controller, never here.
     *
     * @param catalogProgramName the program name of the selected catalogue entry; may be {@code null}
     * @return the destination to dispatch to, or an empty result when suppression applies or the name
     *         resolves to no destination
     */
    public Optional<Route> resolveAdminMenuDispatch(final String catalogProgramName) {
        return resolveCatalogDispatch(catalogProgramName, "admin-menu-dispatch");
    }

    // ------------------------------------------------------------------------------------------
    // Route vocabulary lookups
    // ------------------------------------------------------------------------------------------

    /**
     * Resolves a legacy program name to its destination.
     *
     * <p>This is the lookup the two nomination rules rest on, because the communication-area fields they
     * read carry an eight-character program name rather than a route. It also serves menu dispatch,
     * whose catalogue entries carry the same kind of value.
     *
     * <p>Matching tolerates the trailing padding of a fixed-width field - the legacy field is eight
     * characters wide and the transfer-control command ignores trailing blanks - but applies no case
     * fold and no other normalisation, because program names are case-sensitive in the resource
     * definition. The value inspected is never altered; only the key used for the lookup is.
     *
     * <p>Never throws. An absent name, a blank one and one naming no reachable destination all yield an
     * empty result. No name resolves to the dangling program definition, which has no destination.
     *
     * @param legacyProgramName the eight-character legacy program name; may be {@code null}
     * @return the matching destination, or an empty result
     */
    public Optional<Route> routeForLegacyProgram(final String legacyProgramName) {
        return lookupFixedWidth(ROUTES_BY_LEGACY_PROGRAM, legacyProgramName);
    }

    /**
     * Resolves a legacy transaction identifier to its destination, mirroring the 18 transaction
     * definitions registered in {@code app/csd/CARDDEMO.CSD}.
     *
     * <p>Matching tolerates the trailing padding of the four-character fixed-width field and applies no
     * case fold, exactly as {@link #routeForLegacyProgram(String)} does. Never throws.
     *
     * @param legacyTransactionId the four-character legacy transaction identifier; may be {@code null}
     * @return the matching destination, or an empty result
     */
    public Optional<Route> routeForLegacyTransactionId(final String legacyTransactionId) {
        return lookupFixedWidth(ROUTES_BY_LEGACY_TRANSACTION, legacyTransactionId);
    }

    /**
     * Resolves a wire value back to its destination - the inverse of {@link Route#getRouteValue()}.
     *
     * <p>Used to read back a route a client echoed. Matching is <strong>exact</strong>: unlike the two
     * legacy-identifier lookups, a wire value is not a fixed-width field, so its surrounding white space
     * is not padding to be tolerated but a difference from the published token. Never throws; an
     * unrecognised value yields an empty result rather than an error, so a malformed echo cannot fail a
     * request here.
     *
     * @param routeValue the wire value to resolve; may be {@code null}
     * @return the matching destination, or an empty result
     */
    public Optional<Route> routeForValue(final String routeValue) {
        if (routeValue == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(ROUTES_BY_VALUE.get(routeValue));
    }

    /**
     * Returns every destination, in declaration order.
     *
     * <p>The order is the order a user traverses the application, as described on {@link Route}. The
     * list is immutable, so a caller can hold it without copying and cannot disturb the vocabulary. Its
     * size is {@link #ROUTE_COUNT}.
     *
     * @return an immutable list of all 18 destinations
     */
    public List<Route> routes() {
        return ALL_ROUTES;
    }

    /**
     * Returns the administrative destinations - the administrative menu and the four user-maintenance
     * destinations.
     *
     * <p>This <strong>reports</strong> scope so the controller layer can consume it; it enforces
     * nothing. Role gating is applied by the security configuration and at the controller, and this
     * class performs no access check and holds no dependency on the configuration layer.
     *
     * @return an immutable set of the five administrative destinations
     */
    public Set<Route> adminScopedRoutes() {
        return ADMIN_SCOPED_ROUTES;
    }

    // ------------------------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------------------------

    /**
     * Applies the shared nomination rule: a blank field yields the fallback, a field naming a reachable
     * destination yields that destination, and a field naming an unreachable one yields the fallback
     * with a warning.
     *
     * <p>Shared by back-navigation and by nominated-destination resolution because the two rules differ
     * only in which communication-area field they read. The rule name is passed in so that a log line
     * identifies which of the two produced it.
     *
     * @param nominatedProgram the program name read from the communication area; may be {@code null}
     * @param fallback         the caller's own default; never {@code null} by the time this is reached
     * @param rule             the rule name, for diagnostics
     * @return the effective destination
     */
    private Route resolveNominatedProgram(final String nominatedProgram, final Route fallback,
            final String rule) {
        if (isSpacesOrLowValues(nominatedProgram)) {
            LOG.debug("Nomination empty, applying caller default: rule={} route={}",
                    rule, fallback.getRouteValue());
            return fallback;
        }
        final Optional<Route> nominated = routeForLegacyProgram(nominatedProgram);
        if (nominated.isEmpty()) {
            LOG.warn("Nominated program names no reachable destination, applying caller default: "
                    + "rule={} nominatedProgram={} route={}",
                    rule, nominatedProgram, fallback.getRouteValue());
            return fallback;
        }
        final Route route = nominated.get();
        LOG.debug("Nomination honoured: rule={} nominatedProgram={} route={}",
                rule, nominatedProgram, route.getRouteValue());
        return route;
    }

    /**
     * Applies the suppression guard and then the program-name lookup, shared by both menu dispatch
     * rules.
     *
     * @param catalogProgramName the program name of the selected catalogue entry; may be {@code null}
     * @param rule               the rule name, for diagnostics
     * @return the destination to dispatch to, or an empty result
     */
    private Optional<Route> resolveCatalogDispatch(final String catalogProgramName, final String rule) {
        if (isDispatchSuppressed(catalogProgramName)) {
            LOG.debug("Menu dispatch suppressed: rule={} catalogProgramName={}",
                    rule, catalogProgramName);
            return Optional.empty();
        }
        final Optional<Route> target = routeForLegacyProgram(catalogProgramName);
        if (target.isEmpty()) {
            LOG.warn("Menu entry names no reachable destination: rule={} catalogProgramName={}",
                    rule, catalogProgramName);
        }
        return target;
    }

    /**
     * Reports whether a fixed-width field value is blank in the legacy sense - entirely spaces or
     * entirely low values.
     *
     * <p>This is deliberately narrower than the conventional Java blank test. The legacy comparison is
     * against the space and low-value figurative constants only, so a tab or a line feed is not blank
     * even though a general white-space test would call it so. An absent value and an empty one are both
     * blank: neither names anything.
     *
     * @param value the field value; may be {@code null}
     * @return {@code true} when the value is absent, empty, all spaces or all low values
     */
    private static boolean isSpacesOrLowValues(final String value) {
        if (value == null) {
            return true;
        }
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            if (character != ' ' && character != '\u0000') {
                return false;
            }
        }
        return true;
    }

    /**
     * Looks a fixed-width field value up in an index, tolerating the field's trailing padding.
     *
     * @param index    the immutable index to probe
     * @param rawValue the raw field value; may be {@code null}
     * @return the matching destination, or an empty result
     */
    private static Optional<Route> lookupFixedWidth(final Map<String, Route> index,
            final String rawValue) {
        if (rawValue == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(index.get(stripTrailingPadding(rawValue)));
    }

    /**
     * Removes the trailing space and low-value padding of a fixed-width field value, leaving everything
     * else untouched.
     *
     * <p>Only trailing padding is removed, and only the two characters the legacy fields are padded
     * with. Leading characters, interior characters and letter case are never altered, so a name that
     * differs other than by padding still fails to match - which is the intended outcome.
     *
     * @param value the raw field value; never {@code null} by the time this is reached
     * @return the value without its trailing padding, possibly empty
     */
    private static String stripTrailingPadding(final String value) {
        int end = value.length();
        while (end > 0 && (value.charAt(end - 1) == ' ' || value.charAt(end - 1) == '\u0000')) {
            end--;
        }
        return value.substring(0, end);
    }

    /**
     * Builds an immutable index of every destination, keyed by one of its own attributes.
     *
     * <p>Built from the declared constants themselves, so an index can never disagree with the
     * vocabulary, and copied into a genuinely immutable map rather than merely wrapped, so that no
     * caller and no later change can mutate it.
     *
     * <p>The iteration order of the returned map is unspecified, which is deliberate and harmless: an
     * index is only ever probed by key. Order-bearing exposure is {@link #routes()}, which preserves
     * declaration order. Every key is distinct - each destination has its own wire value, its own
     * legacy transaction identifier and its own legacy program name - so no entry can silently
     * displace another.
     *
     * @param keyExtractor supplies the key for a destination
     * @return an immutable index from key to destination
     */
    private static Map<String, Route> indexBy(final Function<Route, String> keyExtractor) {
        final Map<String, Route> index = new LinkedHashMap<>();
        for (final Route route : Route.values()) {
            index.put(keyExtractor.apply(route), route);
        }
        return Map.copyOf(index);
    }

    /**
     * Builds the immutable set of administrative destinations by filtering the declared constants on
     * their own administrative flag.
     *
     * @return an immutable set of the administrative destinations
     */
    private static Set<Route> indexAdminScoped() {
        final List<Route> scoped = new ArrayList<>();
        for (final Route route : Route.values()) {
            if (route.isAdminScoped()) {
                scoped.add(route);
            }
        }
        return Set.copyOf(scoped);
    }
}
